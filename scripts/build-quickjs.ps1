param([string]$NdkRoot = $env:ANDROID_NDK_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$NdkRoot) { throw 'Pass -NdkRoot pointing to Android NDK r14b (API 14 support).' }
$ndkBuild = Join-Path $NdkRoot 'ndk-build.cmd'
if (!(Test-Path -LiteralPath $ndkBuild)) { throw "NDK not found: $ndkBuild" }
$jni = Join-Path $projectRoot 'native/quickjs'
foreach ($abi in @('armeabi-v7a', 'arm64-v8a')) {
    $platform = if ($abi -eq 'armeabi-v7a') { 'android-14' } else { 'android-21' }
    $buildRoot = Join-Path $projectRoot ".codex-tmp/quickjs-build/$abi"
    & $ndkBuild "NDK_PROJECT_PATH=$jni" "APP_BUILD_SCRIPT=$jni/Android.mk" "NDK_APPLICATION_MK=$jni/Application.mk" `
        'NDK_TOOLCHAIN_VERSION=clang' "APP_ABI=$abi" "APP_PLATFORM=$platform" `
        "NDK_OUT=$buildRoot/obj" "NDK_LIBS_OUT=$buildRoot/libs" -j4
    if ($LASTEXITCODE -ne 0) { throw "QuickJS build failed: $abi" }
    $target = Join-Path $projectRoot "app/src/main/libs/$abi/libntvquickjs.so"
    Copy-Item -LiteralPath "$buildRoot/libs/$abi/libntvquickjs.so" -Destination $target -Force
}
