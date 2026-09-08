param([string]$NdkRoot = $env:ANDROID_NDK_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$NdkRoot) { throw 'Pass -NdkRoot pointing to Android NDK r14b (API 14 support).' }
$ndkBuild = Join-Path $NdkRoot 'ndk-build.cmd'
if (!(Test-Path -LiteralPath $ndkBuild)) { throw "NDK not found: $ndkBuild" }
$jni = Join-Path $projectRoot 'native/tls'
$buildRoot = Join-Path $projectRoot '.codex-tmp/tls-build'
# Android 4.0 is 32-bit only; newer systems use their existing TLS provider.
& $ndkBuild "NDK_PROJECT_PATH=$jni" "APP_BUILD_SCRIPT=$jni/Android.mk" "NDK_APPLICATION_MK=$jni/Application.mk" `
    'NDK_TOOLCHAIN_VERSION=clang' 'APP_ABI=armeabi-v7a' 'APP_PLATFORM=android-14' `
    "NDK_OUT=$buildRoot/obj" "NDK_LIBS_OUT=$buildRoot/libs" -j4
if ($LASTEXITCODE -ne 0) { throw 'TLS build failed' }
Copy-Item -LiteralPath "$buildRoot/libs/armeabi-v7a/libntvtls.so" `
    -Destination (Join-Path $projectRoot 'app/src/main/libs/armeabi-v7a/libntvtls.so') -Force
