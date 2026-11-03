[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$JavaHome,
    [string]$ReleaseNotes = '修复问题并提升播放体验。',
    [switch]$ImportantUpdate, # Compatibility only; all manifests are always generated.
    [switch]$Clean,
    [switch]$SkipClean,
    [string]$NdkRoot,
    [switch]$RebuildQuickJs,
    [switch]$RebuildTls,
    [switch]$DisableH5eStreaming # Compatibility: release APKs no longer bundle H5E streaming.
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repoRoot 'output'
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)

function Test-CompatibleJava([string]$candidate) {
    $java = if ($candidate) { Join-Path $candidate 'bin\java.exe' } else { $null }
    if (-not $java -or -not (Test-Path -LiteralPath $java)) { return $false }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $java
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $versionText = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    return $versionText -match 'version\s+"17[."]'
}

function Install-Java17 {
    $cacheRoot = Join-Path $repoRoot '.codex-tmp\jdk17'
    New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
    $cachedJava = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
        Select-Object -First 1
    if ($cachedJava) { return (Split-Path -Parent (Split-Path -Parent $cachedJava.FullName)) }

    $archive = Join-Path $cacheRoot 'temurin17-jdk.zip'
    $extractDirectory = Join-Path $cacheRoot 'runtime'
    Write-Host 'Compatible JDK 17 was not found; downloading a cached Temurin JDK 17 runtime...'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $archive
    New-Item -ItemType Directory -Path $extractDirectory -Force | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $extractDirectory -Force
    $downloadedJava = Get-ChildItem -LiteralPath $extractDirectory -Recurse -Filter java.exe |
        Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
        Select-Object -First 1
    if (-not $downloadedJava) { throw 'Downloaded JDK archive does not contain bin/java.exe.' }
    return (Split-Path -Parent (Split-Path -Parent $downloadedJava.FullName))
}

function Find-JavaHome {
    $candidates = @()
    if ($JavaHome) { $candidates += $JavaHome }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    if ($env:ANDROID_STUDIO_JDK) { $candidates += $env:ANDROID_STUDIO_JDK }
    $candidates += @(
        (Join-Path $repoRoot '.codex-tmp\jdk17\runtime'),
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Android\Android Studio\jre',
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'),
        (Join-Path $env:USERPROFILE '.jdks')
    )
    foreach ($candidate in $candidates) {
        if (Test-CompatibleJava $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            $nestedJava = Get-ChildItem -LiteralPath $candidate -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
                Select-Object -First 1
            if ($nestedJava) {
                $nestedHome = Split-Path -Parent (Split-Path -Parent $nestedJava.FullName)
                if (Test-CompatibleJava $nestedHome) { return $nestedHome }
            }
        }
    }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) {
        $commandHome = Split-Path -Parent (Split-Path -Parent $java.Source)
        if (Test-CompatibleJava $commandHome) { return $commandHome }
    }
    return Install-Java17
}

function Read-SdkDirectory {
    $propertiesPath = Join-Path $repoRoot 'local.properties'
    if (-not (Test-Path -LiteralPath $propertiesPath)) {
        throw 'local.properties is missing; sdk.dir is required.'
    }
    $sdkLine = Get-Content -LiteralPath $propertiesPath |
        Where-Object { $_ -match '^sdk\.dir=' } |
        Select-Object -First 1
    if (-not $sdkLine) { throw 'sdk.dir is missing from local.properties.' }
    $sdkPath = $sdkLine.Substring($sdkLine.IndexOf('=') + 1) -replace '\\:', ':' -replace '\\\\', '\'
    if (-not (Test-Path -LiteralPath $sdkPath)) {
        throw "Android SDK does not exist: $sdkPath"
    }
    return (Resolve-Path -LiteralPath $sdkPath).Path
}

function Find-BuildTool([string]$sdkDirectory, [string]$name) {
    $tools = Get-ChildItem -LiteralPath (Join-Path $sdkDirectory 'build-tools') -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending
    foreach ($tool in $tools) {
        $path = Join-Path $tool.FullName $name
        if (Test-Path -LiteralPath $path) { return $path }
    }
    throw "$name was not found under Android SDK build-tools."
}

function Get-ApkArchitectures([string]$apkPath) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        return @($archive.Entries |
            ForEach-Object {
                if ($_.FullName -match '^lib/([^/]+)/[^/]+\.so$') { $Matches[1] }
            } |
            Sort-Object -Unique)
    } finally {
        $archive.Dispose()
    }
}

function Get-Sha256([string]$path) {
    $stream = [System.IO.File]::OpenRead($path)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha256.ComputeHash($stream)) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
}

$signingDirectory = Join-Path $repoRoot '.signing'
foreach ($requiredFile in @('iptv-release.jks', 'keystore-info.properties')) {
    if (-not (Test-Path -LiteralPath (Join-Path $signingDirectory $requiredFile))) {
        throw "Release signing file is missing: .signing/$requiredFile"
    }
}

$env:JAVA_HOME = Find-JavaHome
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + [System.IO.Path]::PathSeparator + $env:Path
$sdkDirectory = Read-SdkDirectory
$apksigner = Find-BuildTool $sdkDirectory 'apksigner.bat'
$aapt = Find-BuildTool $sdkDirectory 'aapt.exe'

$gradleTasks = @()
# Whole-segment CJS decryption remains available; omit the optional streaming SO.
if ($RebuildQuickJs -or $NdkRoot) {
    if (!$NdkRoot) { $NdkRoot = $env:ANDROID_NDK_HOME }
    & (Join-Path $PSScriptRoot 'build-quickjs.ps1') -NdkRoot $NdkRoot
}
foreach ($abi in @('armeabi-v7a','arm64-v8a','x86')) {
    if (!(Test-Path -LiteralPath (Join-Path $repoRoot "app/src/main/libs/$abi/libntvquickjs.so"))) {
        throw 'QuickJS library missing. Run scripts/build-quickjs.ps1 -NdkRoot <NDK-r14b> first.'
    }
}
if ($RebuildTls -or $NdkRoot) {
    if (!$NdkRoot) { $NdkRoot = $env:ANDROID_NDK_HOME }
    & (Join-Path $PSScriptRoot 'build-tls.ps1') -NdkRoot $NdkRoot
}
foreach ($abi in @('armeabi-v7a','x86')) {
    if (!(Test-Path -LiteralPath (Join-Path $repoRoot "app/src/main/libs/$abi/libntvtls.so"))) {
        throw 'Legacy TLS library missing. Run scripts/build-tls.ps1 -NdkRoot <NDK-r14b> first.'
    }
}
if ($Clean -and -not $SkipClean) { $gradleTasks += 'clean' }
$gradleTasks += @(':app:assembleArm32Release', ':app:assembleArm64Release',
    ':app:assembleX86Release', ':app:assembleNtvBroRelease', '--no-daemon')

Push-Location $repoRoot
try {
    Write-Host "JAVA_HOME=$env:JAVA_HOME"
    & (Join-Path $repoRoot 'gradlew.bat') @gradleTasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

$artifacts = @(
    [pscustomobject]@{
        Name = 'ntv_bro.apk'
        Flavor = 'ntvBro'
        CopyAs = 'nTv-bro-v7a-电视盒子专用-含轻量内核-v{0}.apk'
        Source = Join-Path $repoRoot 'app\build\outputs\apk\ntvBro\release\app-ntvBro-release.apk'
        ExpectedAbi = 'armeabi-v7a'
    },
    [pscustomobject]@{
        Name = 'nTv.apk'
        Flavor = 'arm32'
        CopyAs = 'nTv-v7a-电视盒子专用-v{0}.apk'
        Source = Join-Path $repoRoot 'app\build\outputs\apk\arm32\release\app-arm32-release.apk'
        ExpectedAbi = 'armeabi-v7a'
    },
    [pscustomobject]@{
        Name = 'nTv64.apk'
        Flavor = 'arm64'
        CopyAs = 'nTv-v8a-手机或高版本设备-v{0}.apk'
        Source = Join-Path $repoRoot 'app\build\outputs\apk\arm64\release\app-arm64-release.apk'
        ExpectedAbi = 'arm64-v8a'
    },
    [pscustomobject]@{
        Name = 'nTvX86.apk'
        Flavor = 'x86'
        CopyAs = 'nTv-x86-模拟器专用-v{0}.apk'
        Source = Join-Path $repoRoot 'app\build\outputs\apk\x86\release\app-x86-release.apk'
        ExpectedAbi = 'x86'
    }
)

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$results = foreach ($artifact in $artifacts) {
    if (-not (Test-Path -LiteralPath $artifact.Source)) {
        throw "Expected APK was not generated: $($artifact.Source)"
    }
    $mappingSource = Join-Path $repoRoot "app/build/outputs/mapping/$($artifact.Flavor)Release/mapping.txt"
    if (-not (Test-Path -LiteralPath $mappingSource -PathType Leaf) -or
        (Get-Item -LiteralPath $mappingSource).Length -eq 0) {
        throw "R8 mapping is missing or empty: $mappingSource"
    }
    # Validate an ABI entry point that must retain its name even in an obfuscated APK.
    $archive = [System.IO.Compression.ZipFile]::OpenRead($artifact.Source)
    try {
        if ($null -ne $archive.GetEntry("lib/$($artifact.ExpectedAbi)/libh5estream.so")) {
            throw "Release APK unexpectedly bundles H5E streaming: $($artifact.Source)"
        }
        $liteLibrary = $archive.GetEntry("lib/$($artifact.ExpectedAbi)/libntvLitebro.so")
        $hasLiteAssets = $null -ne $archive.GetEntry('assets/browser.js')
        if ($artifact.Flavor -eq 'ntvBro') {
            if ($null -eq $liteLibrary -or -not $hasLiteAssets) { throw "Browser edition is missing its lightweight core: $($artifact.Source)" }
        } elseif ($null -ne $liteLibrary -or $hasLiteAssets) {
            throw "Standard APK unexpectedly bundles the lightweight core: $($artifact.Source)"
        }
        $nativeBridge = $false
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notmatch '^classes\d*\.dex$') { continue }
            $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::ASCII)
            try { $dex = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($dex.Contains('Lxiao/bu/tv/NativeQuickJs;')) { $nativeBridge = $true }
        }
        if (-not $nativeBridge) { throw "Release APK is missing its JNI bridge: $($artifact.Source)" }

    } finally { $archive.Dispose() }
    $destination = Join-Path $OutputDirectory $artifact.Name
    Copy-Item -LiteralPath $artifact.Source -Destination $destination -Force
    # NTFS overwrites preserve an older filename's casing. GitHub asset URLs are
    # case-sensitive, so also normalize the actual directory entry after copying.
    Rename-Item -LiteralPath $destination -NewName $artifact.Name -ErrorAction Stop

    & $apksigner verify --verbose --print-certs $destination | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Signature verification failed: $destination" }

    $architectures = @(Get-ApkArchitectures $destination)
    if ($architectures.Count -ne 1 -or $architectures[0] -ne $artifact.ExpectedAbi) {
        throw "Unexpected native architectures in $($artifact.Name): $($architectures -join ', ')"
    }

    # Windows aapt cannot read Unicode APK names; inspect the source we just copied.
    $badgingOutput = @(& $aapt dump badging $artifact.Source)
    $badgingExitCode = $LASTEXITCODE
    $badging = $badgingOutput | Select-Object -First 1
    if ($badgingExitCode -ne 0 -or -not $badging) {
        throw "Unable to read APK metadata: $destination"
    }
    $hash = Get-Sha256 $destination
    # Hash-specific paths retain the right mapping for every shipped APK.
    $mappingDirectory = Join-Path $OutputDirectory "mapping/$hash"
    New-Item -ItemType Directory -Path $mappingDirectory -Force | Out-Null
    $mappingDestination = Join-Path $mappingDirectory 'mapping.txt'
    Copy-Item -LiteralPath $mappingSource -Destination $mappingDestination -Force
    $file = Get-Item -LiteralPath $destination
    if ($badging -notmatch "versionName='([^']+)'") {
        throw "APK versionName is missing: $destination"
    }
    $apkVersionName = $Matches[1]
    if ($badging -notmatch "versionCode='([0-9]+)'") {
        throw "APK versionCode is missing: $destination"
    }
    $apkVersionCode = [int]$Matches[1]
    if ($apkVersionName.IndexOfAny([System.IO.Path]::GetInvalidFileNameChars()) -ge 0) {
        throw "APK versionName contains invalid filename characters: $apkVersionName"
    }
    $aliasPath = Join-Path $OutputDirectory ($artifact.CopyAs -f $apkVersionName)
    Copy-Item -LiteralPath $destination -Destination $aliasPath -Force
    [pscustomobject]@{
        APK = $artifact.Name
        Flavor = $artifact.Flavor
        ABI = $architectures[0]
        SizeMB = [math]::Round($file.Length / 1MB, 2)
        SHA256 = $hash
        VersionName = $apkVersionName
        VersionCode = $apkVersionCode
        Obfuscation = 'R8 enabled'
        H5eStreaming = $false
        LightweightBrowser = $artifact.Flavor -eq 'ntvBro'
        Mapping = $mappingDestination
        Metadata = $badging
        Path = $file.FullName
        AliasPath = $aliasPath
    }
}

$arm32Result = $results | Where-Object { $_.Flavor -eq 'arm32' } | Select-Object -First 1
$arm64Result = $results | Where-Object { $_.ABI -eq 'arm64-v8a' } | Select-Object -First 1
$broResult = $results | Where-Object { $_.Flavor -eq 'ntvBro' } | Select-Object -First 1
$x86Result = $results | Where-Object { $_.ABI -eq 'x86' } | Select-Object -First 1
if (-not $arm32Result -or -not $arm64Result -or -not $x86Result -or -not $broResult) {
    throw 'ARM32, ARM64, x86 and ARM32 browser artifacts are required to generate update metadata.'
}

# Package only this build's mappings; retain the hash archives across releases.
$mappingZip = Join-Path $OutputDirectory 'mapping.zip'
$mappingZipTemp = Join-Path $OutputDirectory ('.mapping-' + [guid]::NewGuid().ToString('N') + '.zip')
try {
    $zip = [System.IO.Compression.ZipFile]::Open($mappingZipTemp, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $mappingIndex = @(foreach ($result in $results) {
            $entryName = "$($result.Flavor)/mapping.txt"
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $result.Mapping, $entryName, [System.IO.Compression.CompressionLevel]::Optimal)
            [ordered]@{
                apk = $result.APK
                abi = $result.ABI
                apkSha256 = $result.SHA256
                mapping = $entryName
                mappingSha256 = Get-Sha256 $result.Mapping
            }
        })
        $indexEntry = $zip.CreateEntry('manifest.json')
        $writer = New-Object System.IO.StreamWriter($indexEntry.Open(), (New-Object System.Text.UTF8Encoding($false)))
        try { $writer.WriteLine((ConvertTo-Json -InputObject $mappingIndex -Depth 3)) }
        finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    Move-Item -LiteralPath $mappingZipTemp -Destination $mappingZip -Force
} finally {
    if (Test-Path -LiteralPath $mappingZipTemp) {
        Remove-Item -LiteralPath $mappingZipTemp -Force
    }
}
# Remove the previous loose-file aliases after the ZIP has been saved successfully.
foreach ($artifact in $artifacts) {
    $mappingAlias = Join-Path $OutputDirectory (
        [System.IO.Path]::GetFileNameWithoutExtension($artifact.Name) + '-mapping.txt')
    if (Test-Path -LiteralPath $mappingAlias -PathType Leaf) {
        Remove-Item -LiteralPath $mappingAlias -Force
    }
}

$manifestTasks = @(
    ':app:generateVersionFile',
    "-PupdateApk32=$($arm32Result.Path)",
    "-PupdateApk64=$($arm64Result.Path)",
    "-PupdateApkX86=$($x86Result.Path)",
    "-PupdateApkBro=$($broResult.Path)",
    "-PreleaseNotes=$ReleaseNotes",
    '--no-daemon'
)
Push-Location $repoRoot
try {
    & (Join-Path $repoRoot 'gradlew.bat') @manifestTasks
    if ($LASTEXITCODE -ne 0) {
        throw "Update metadata generation failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

$liteVersionPath = Join-Path $repoRoot 'version-lite.json'
$versionPath = Join-Path $repoRoot 'version.json'
$legacyVersionPath = Join-Path $repoRoot 'version-iptv.json'
foreach ($manifestPath in @($liteVersionPath, $versionPath, $legacyVersionPath)) {
    $version = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($artifactResult in @($arm32Result, $arm64Result, $x86Result)) {
        if ($version.versionName -ne $artifactResult.VersionName -or
                ($manifestPath -eq $liteVersionPath -and
                    $version.versionCode -ne $artifactResult.VersionCode)) {
            throw "Update metadata version does not match APK $($artifactResult.APK): $manifestPath"
        }
    }
    if ($version.sha256 -ne $arm32Result.SHA256 -or
            $version.sha25632 -ne $arm32Result.SHA256 -or
            $version.sha25664 -ne $arm64Result.SHA256 -or
            $version.sha256X86 -ne $x86Result.SHA256 -or
            $version.sha256Bro -ne $broResult.SHA256) {
        throw "Generated update metadata does not match the release APK hashes: $manifestPath"
    }
    if ($manifestPath -ne $liteVersionPath -and $version.versionCode -ne 6) {
        throw "Startup update versionCode must stay at 6: $manifestPath"
    }
}
Copy-Item -LiteralPath $liteVersionPath -Destination (Join-Path $OutputDirectory 'version-lite.json') -Force
Copy-Item -LiteralPath $legacyVersionPath -Destination (Join-Path $OutputDirectory 'version-iptv.json') -Force
Copy-Item -LiteralPath $versionPath -Destination (Join-Path $OutputDirectory 'version.json') -Force

Write-Host ''
Write-Host 'Release artifacts:'
$results | Format-List APK, ABI, SizeMB, SHA256, Obfuscation, H5eStreaming, Metadata, Path, AliasPath
Write-Host "R8 mappings (all architectures): $mappingZip"
Write-Host "Lightweight metadata: $liteVersionPath"
Write-Host "Legacy metadata: $legacyVersionPath (review and publish to master after uploading matching APKs)"
Write-Host "Startup metadata: $versionPath (versionCode=6; upload to Release only)"
