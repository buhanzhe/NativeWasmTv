#!/usr/bin/env bash
set -euo pipefail
export PATH="/usr/bin:/bin:$PATH"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
tree="${IJK_ROOT:-$repo/.codex-tmp/ijkplayer-0.8.8}"
arch="${1:-arm64}"
case "$arch" in arm64) cross=aarch64-linux-android;; armv7a) cross=arm-linux-androideabi;; *) exit 2;; esac
export ANDROID_NDK="${ANDROID_NDK:-$repo/.codex-tmp/android-ndk-r14b}"
export PATH="/usr/bin:$tree/android/contrib/build/ffmpeg-$arch/toolchain/bin:$ANDROID_NDK/prebuilt/windows-x86_64/bin:$PATH"
cd "$tree/android/contrib/ffmpeg-$arch"
test -f ffbuild/config.mak
# Reuse the already configured cross-toolchain, retaining all prior IPTV flags.
flags="$(sed -n 's/^FFMPEG_CONFIGURATION=//p' ffbuild/config.mak)"
if [ "${NTV_SKIP_CONFIGURE:-0}" != 1 ]; then
    eval "./configure $flags --enable-decoder=ac3 --enable-decoder=eac3 --enable-decoder=truehd --enable-decoder=mlp --enable-parser=ac3 --enable-parser=mlp --enable-demuxer=ac3 --enable-demuxer=eac3 --enable-demuxer=truehd --enable-demuxer=mlp --enable-demuxer=matroska --enable-decoder=mpeg2video --enable-parser=mpegvideo --enable-decoder=movtext --enable-decoder=ass --enable-decoder=ssa --enable-decoder=subrip --enable-decoder=srt --enable-decoder=webvtt"
fi
grep -q '^#define CONFIG_EAC3_DECODER 1' config.h
make -j4
make install
prefix="$tree/android/contrib/build/ffmpeg-$arch/output"
mkdir -p "$prefix/include/libffmpeg"
cp config.h "$prefix/include/libffmpeg/config.h"
objects=()
for module in compat libavcodec libavfilter libavformat libavutil libswresample libswscale; do
    for file in "$module"/*.o "$module"/arm/*.o "$module"/aarch64/*.o "$module"/neon/*.o; do
        test ! -f "$file" || objects+=("$file")
    done
done
"$cross-gcc" -shared -Wl,--no-undefined -Wl,-z,noexecstack -Wl,-soname,libijkffmpeg.so \
    "${objects[@]}" -lm -lz -o "$prefix/libijkffmpeg.so"
echo "Built $prefix/libijkffmpeg.so"
