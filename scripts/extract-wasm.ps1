param(
    [Parameter(Mandatory = $true)]
    [string]$WorkerJs,

    [Parameter(Mandatory = $true)]
    [string]$WabtRoot
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$generated = Join-Path $repoRoot 'app\src\main\jni\generated'
$runtime = Join-Path $repoRoot 'app\src\main\jni\wasm-rt'
$wasm2c = Join-Path $WabtRoot 'bin\wasm2c.exe'
$mingw = Join-Path $env:ProgramFiles 'Git\mingw64\bin'

if (-not (Test-Path $wasm2c)) {
    throw "wasm2c.exe not found: $wasm2c"
}
if (Test-Path $mingw) {
    $env:PATH = "$mingw;$env:PATH"
}

$source = Get-Content $WorkerJs -Raw
$match = [regex]::Match($source, 'data:application/octet-stream;base64,([A-Za-z0-9+/=]+)')
if (-not $match.Success) {
    throw 'Embedded wasm payload was not found in worker JS.'
}
$memoryMatches = [regex]::Matches($source, 'HEAPU8\.set\(\s*\[([0-9,\s-]+)\]\s*,\s*eb\s*\+\s*([0-9eE+.-]+)\s*\)')
if ($memoryMatches.Count -eq 0) {
    throw 'Emscripten pre-run memory payload was not found in worker JS.'
}

New-Item -ItemType Directory -Force -Path $generated, $runtime | Out-Null
$wasmFile = Join-Path $env:TEMP 'cctv_h5e.wasm'
[IO.File]::WriteAllBytes($wasmFile, [Convert]::FromBase64String($match.Groups[1].Value))

& $wasm2c $wasmFile -n cctv_h5e -o (Join-Path $generated 'cctv_h5e_wasm.c')
if ($LASTEXITCODE -ne 0) {
    throw "wasm2c failed with exit code $LASTEXITCODE"
}

$memoryLength = 0
foreach ($memoryMatch in $memoryMatches) {
    $offset = [int] [double]::Parse($memoryMatch.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
    $values = $memoryMatch.Groups[1].Value -split ','
    $memoryLength = [Math]::Max($memoryLength, $offset + $values.Length)
}
$memoryValues = New-Object byte[] $memoryLength
foreach ($memoryMatch in $memoryMatches) {
    $offset = [int] [double]::Parse($memoryMatch.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
    $values = $memoryMatch.Groups[1].Value -split ','
    for ($index = 0; $index -lt $values.Length; $index++) {
        $memoryValues[$offset + $index] = [byte] ([int] $values[$index].Trim())
    }
}
$relocationStart = $memoryMatches[$memoryMatches.Count - 1].Index + $memoryMatches[$memoryMatches.Count - 1].Length
$relocationEnd = $source.IndexOf('for (var e = 0; e < A.length; e++)', $relocationStart)
if ($relocationEnd -lt 0) {
    throw 'Emscripten pre-run relocation payload was not found in worker JS.'
}
$relocationSource = $source.Substring($relocationStart, $relocationEnd - $relocationStart)
$relocationMatches = [regex]::Matches($relocationSource, '\.concat\(\[([0-9eE+,\s.-]+)\]\)')
$relocations = New-Object Collections.Generic.List[uint32]
foreach ($relocationMatch in $relocationMatches) {
    foreach ($value in ($relocationMatch.Groups[1].Value -split ',')) {
        $relocations.Add([uint32] [double]::Parse($value.Trim(), [Globalization.CultureInfo]::InvariantCulture))
    }
}
$memoryHeader = New-Object Text.StringBuilder
[void] $memoryHeader.AppendLine('#ifndef CCTV_H5E_MEMORY_INIT_H')
[void] $memoryHeader.AppendLine('#define CCTV_H5E_MEMORY_INIT_H')
[void] $memoryHeader.AppendLine('')
[void] $memoryHeader.AppendLine('static const unsigned char CCTV_H5E_MEMORY_INIT[] = {')
for ($index = 0; $index -lt $memoryValues.Length; $index++) {
    if ($index % 24 -eq 0) {
        [void] $memoryHeader.Append('  ')
    }
    [void] $memoryHeader.Append($memoryValues[$index])
    if ($index + 1 -lt $memoryValues.Length) {
        [void] $memoryHeader.Append(', ')
    }
    if ($index % 24 -eq 23 -or $index + 1 -eq $memoryValues.Length) {
        [void] $memoryHeader.AppendLine()
    }
}
[void] $memoryHeader.AppendLine('};')
[void] $memoryHeader.AppendLine('')
[void] $memoryHeader.AppendLine('static const unsigned int CCTV_H5E_MEMORY_RELOCATIONS[] = {')
for ($index = 0; $index -lt $relocations.Count; $index++) {
    if ($index % 16 -eq 0) {
        [void] $memoryHeader.Append('  ')
    }
    [void] $memoryHeader.Append("$($relocations[$index])u")
    if ($index + 1 -lt $relocations.Count) {
        [void] $memoryHeader.Append(', ')
    }
    if ($index % 16 -eq 15 -or $index + 1 -eq $relocations.Count) {
        [void] $memoryHeader.AppendLine()
    }
}
[void] $memoryHeader.AppendLine('};')
[void] $memoryHeader.AppendLine('')
[void] $memoryHeader.AppendLine('#endif')
[IO.File]::WriteAllText((Join-Path $generated 'cctv_h5e_memory_init.h'), $memoryHeader.ToString())

Copy-Item (Join-Path $WabtRoot 'include\wasm-rt.h') $runtime
Copy-Item (Join-Path $WabtRoot 'share\wabt\wasm2c\wasm-rt-impl.c') $runtime
Copy-Item (Join-Path $WabtRoot 'share\wabt\wasm2c\wasm-rt-impl.h') $runtime
Copy-Item (Join-Path $WabtRoot 'share\wabt\wasm2c\wasm-rt-impl-tableops.inc') $runtime
Copy-Item (Join-Path $WabtRoot 'share\wabt\wasm2c\wasm-rt-mem-impl.c') $runtime
Copy-Item (Join-Path $WabtRoot 'share\wabt\wasm2c\wasm-rt-mem-impl-helper.inc') $runtime

$hash = (Get-FileHash $wasmFile -Algorithm SHA256).Hash.ToLower()
Write-Host "Generated native C from wasm SHA-256: $hash"
Write-Host "Generated pre-run memory payload: $($memoryValues.Length) bytes, $($relocations.Count) relocations"
