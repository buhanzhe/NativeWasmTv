param(
    [string]$JsonJar = $env:JSON_JAR,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!$JsonJar -or !(Test-Path -LiteralPath $JsonJar)) {
    throw 'Pass -JsonJar <org.json jar> or set JSON_JAR; see docs/VOD.zh-CN.md.'
}
$JsonJar = (Resolve-Path -LiteralPath $JsonJar).Path
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java' } else { 'java' }
$output = Join-Path (Get-Location).Path '.codex-tmp/vod-unit-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$classes = @('TvBoxService','VodSearch','VodProbe','VodDeadline','VodErrors','CjsNativeProfile','HlsKeyRegistry')
$tests = @('TvBoxServiceTest','VodSearchTest','VodProbeTest','CompatibilityTest')
$sources = @($classes | ForEach-Object { "app/src/main/java/xiao/bu/tv/$_.java" }) + @($tests | ForEach-Object { "tests/$_.java" })
& $javac -encoding UTF-8 -source 8 -target 8 -cp $JsonJar -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Java test compilation failed' }
$classpath = $JsonJar + [IO.Path]::PathSeparator + $output
foreach ($test in $tests) {
    & $java -cp $classpath "xiao.bu.tv.$test"
    if ($LASTEXITCODE -ne 0) { throw "Test failed: $test" }
}
