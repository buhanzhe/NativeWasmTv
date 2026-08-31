[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$JavaHome,
    [string]$ReleaseNotes = '修复问题并提升播放体验。',
    [switch]$SkipClean
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repoRoot 'app\build\outputs\apk'
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
    return $versionText -match 'version\s+"1\.8\.'
}

function Install-Java8 {
    $cacheRoot = Join-Path $repoRoot '.codex-tmp\jdk8'
    New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
    $cachedJava = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
        Select-Object -First 1
    if ($cachedJava) { return (Split-Path -Parent (Split-Path -Parent $cachedJava.FullName)) }

    $archive = Join-Path $cacheRoot 'temurin8-jdk.zip'
    $extractDirectory = Join-Path $cacheRoot 'runtime'
    Write-Host 'Compatible JDK 8 was not found; downloading a cached Temurin JDK 8 runtime...'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://api.adoptium.net/v3/binary/latest/8/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $archive
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
        (Join-Path $repoRoot '.codex-tmp\jdk8\runtime'),
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Android\Android Studio\jre',
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr')
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
    return Install-Java8
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
    Add-Type -AssemblyName System.IO.Compression.FileSystem
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
if (-not $SkipClean) { $gradleTasks += 'clean' }
$gradleTasks += @(':app:assembleArm32Release', ':app:assembleArm64Release', '--no-daemon')

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
        Name = 'nTv.apk'
        Source = Join-Path $repoRoot 'app\build\outputs\apk\arm32\release\app-arm32-release.apk'
        ExpectedAbi = 'armeabi-v7a'
    },
    [pscustomobject]@{
        Name = 'nTv64.apk'
        Source = Join-Path $repoRoot 'app\build\outputs\apk\arm64\release\app-arm64-release.apk'
        ExpectedAbi = 'arm64-v8a'
    }
)

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$results = foreach ($artifact in $artifacts) {
    if (-not (Test-Path -LiteralPath $artifact.Source)) {
        throw "Expected APK was not generated: $($artifact.Source)"
    }
    $destination = Join-Path $OutputDirectory $artifact.Name
    Copy-Item -LiteralPath $artifact.Source -Destination $destination -Force

    & $apksigner verify --verbose --print-certs $destination | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Signature verification failed: $destination" }

    $architectures = @(Get-ApkArchitectures $destination)
    if ($architectures.Count -ne 1 -or $architectures[0] -ne $artifact.ExpectedAbi) {
        throw "Unexpected native architectures in $($artifact.Name): $($architectures -join ', ')"
    }

    $badgingOutput = @(& $aapt dump badging $destination)
    $badgingExitCode = $LASTEXITCODE
    $badging = $badgingOutput | Select-Object -First 1
    if ($badgingExitCode -ne 0 -or -not $badging) {
        throw "Unable to read APK metadata: $destination"
    }
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    $file = Get-Item -LiteralPath $destination
    [pscustomobject]@{
        APK = $artifact.Name
        ABI = $architectures[0]
        SizeMB = [math]::Round($file.Length / 1MB, 2)
        SHA256 = $hash
        Metadata = $badging
        Path = $file.FullName
    }
}

$arm32Result = $results | Where-Object { $_.APK -eq 'nTv.apk' } | Select-Object -First 1
$arm64Result = $results | Where-Object { $_.APK -eq 'nTv64.apk' } | Select-Object -First 1
if (-not $arm32Result -or -not $arm64Result) {
    throw 'Both ARM32 and ARM64 artifacts are required to generate update metadata.'
}
$manifestTasks = @(
    ':app:generateVersionFile',
    "-PupdateApk32=$($arm32Result.Path)",
    "-PupdateApk64=$($arm64Result.Path)",
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

$versionPath = Join-Path $repoRoot 'version.json'
$legacyVersionPath = Join-Path $repoRoot 'version-iptv.json'
$version = Get-Content -LiteralPath $versionPath -Raw | ConvertFrom-Json
if ($version.sha25632 -ne $arm32Result.SHA256 -or
        $version.sha25664 -ne $arm64Result.SHA256) {
    throw 'Generated update metadata does not match the release APK hashes.'
}
Copy-Item -LiteralPath $versionPath -Destination (Join-Path $OutputDirectory 'version.json') -Force
Copy-Item -LiteralPath $legacyVersionPath -Destination (Join-Path $OutputDirectory 'version-iptv.json') -Force

Write-Host ''
Write-Host 'Release artifacts:'
$results | Format-List APK, ABI, SizeMB, SHA256, Metadata, Path
Write-Host "Update metadata: $versionPath"
Write-Host "Legacy metadata: $legacyVersionPath"
