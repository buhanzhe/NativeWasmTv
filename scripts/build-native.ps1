param(
    [Parameter(Mandatory = $true)]
    [string]$NdkRoot
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Split-Path -Parent $PSScriptRoot)).Path
$ndkRoot = (Resolve-Path $NdkRoot).Path

function Convert-ToWslPath([string]$Path) {
    $converted = & wsl -e wslpath -a $Path
    if ($LASTEXITCODE -ne 0) {
        throw "wslpath failed for $Path"
    }
    return $converted.Trim()
}

$repo = Convert-ToWslPath $repoRoot
$ndk = Convert-ToWslPath $ndkRoot
$command = @"
set -e
mkdir -p /tmp/ndk-compat-libs
if [ ! -e /lib/x86_64-linux-gnu/libncurses.so.5 ] && [ -e /lib/x86_64-linux-gnu/libncurses.so.6 ]; then
  ln -sf /lib/x86_64-linux-gnu/libncurses.so.6 /tmp/ndk-compat-libs/libncurses.so.5
fi
if [ ! -e /lib/x86_64-linux-gnu/libtinfo.so.5 ] && [ -e /lib/x86_64-linux-gnu/libtinfo.so.6 ]; then
  ln -sf /lib/x86_64-linux-gnu/libtinfo.so.6 /tmp/ndk-compat-libs/libtinfo.so.5
fi
export LD_LIBRARY_PATH=/tmp/ndk-compat-libs:`${LD_LIBRARY_PATH:-}
cd '$repo'
'$ndk/prebuilt/linux-x86_64/bin/make' \
  -f '$ndk/build/core/build-local.mk' \
  NDK_PROJECT_PATH='$repo/app' \
  APP_BUILD_SCRIPT='$repo/app/src/main/jni/Android.mk' \
  NDK_APPLICATION_MK='$repo/app/src/main/jni/Application.mk' \
  NDK_OUT='$repo/app/build/native/obj' \
  NDK_LIBS_OUT='$repo/app/src/main/jniLibs' \
  -j2
"@

& wsl -e bash -lc $command
if ($LASTEXITCODE -ne 0) {
    throw "Native build failed with exit code $LASTEXITCODE"
}

Write-Host 'Built app/src/main/jniLibs/armeabi-v7a/libcctv_h5e.so'

