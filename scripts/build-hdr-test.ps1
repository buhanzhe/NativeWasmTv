[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$JavaHome = 'C:/Users/Administrator/.jdks/jbr-17.0.14',
    [string]$Python = 'C:/Python314/python.exe'
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'output/hdr-comparison' }
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$env:JAVA_HOME = $JavaHome
Push-Location $repoRoot
try {
    # Native libraries must include uploadLut3d128; the packaging verifier checks it.
    foreach ($profile in @('1MiB','8MiB')) {
        $gradleArgs = @(':app:assembleArm32Release',':app:assembleArm64Release',':app:assembleX86Release','--offline','--console=plain')
        if ($profile -eq '8MiB') { $gradleArgs += '-PhdrLutTest128=true' }
        & ./gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "HDR build failed: $profile" }
        & $Python tools/regression/package-hdr-ab.py $OutputDirectory --collect $profile
        if ($LASTEXITCODE -ne 0) { throw "HDR collection failed: $profile" }
    }
    & $Python tools/regression/package-hdr-ab.py $OutputDirectory
    if ($LASTEXITCODE -ne 0) { throw 'HDR package verification failed' }
} finally { Pop-Location }
