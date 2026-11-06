# Windows 单文件版（开发说明）

交付物只有一个 `NativeWasmTv.exe`，小于 10,000,000 字节。程序不安装、不释放
附带运行时，内置频道、脚本和许可均从资源读取。用户数据仍放在用户目录。

构建需要 Python 3、MSYS2 UCRT64 GCC/G++、Windows SDK 兼容头文件，及应用根目录的
`browser-core`。`win/out/native/ffmpeg-build` 为 FFmpeg 8.0.1 的静态构建目录。
FFmpeg 源码来自 `https://ffmpeg.org/releases/ffmpeg-8.0.1.tar.xz`。

本轮 FFmpeg 配置如下；现代设备上下文和兼容入口均须启用，否则裁剪构建会缺少
解码对象文件。`make` 使用 MSYS2 GNU Make。

```sh
../ffmpeg-8.0.1/configure --target-os=mingw32 --arch=x86_64 --cc=gcc --cxx=g++ \
 --disable-everything --disable-programs --disable-doc --disable-debug \
 --disable-shared --enable-static --disable-avdevice --disable-avfilter \
 --disable-x86asm --disable-autodetect --enable-small --optflags=-Os \
 --enable-network --enable-schannel --enable-d3d11va \
 --enable-hwaccel=h264_d3d11va,h264_d3d11va2,hevc_d3d11va,hevc_d3d11va2 \
 --enable-decoder=h264,hevc,aac,aac_latm,mp3,mp2,ac3,eac3 \
 --enable-parser=h264,hevc,aac,aac_latm,mpegaudio,ac3 \
 --enable-demuxer=mov,mpegts,hls,flv,aac,mp3,matroska \
 --enable-protocol=file,http,https,tcp,tls,crypto --enable-bsf=aac_adtstoasc \
 --extra-cflags='-Os -ffunction-sections -fno-common' --extra-ldflags=-Wl,--gc-sections
make -j6
```

```powershell
$env:Path = 'C:\tools\msys64\ucrt64\bin;' + $env:Path
python win/native-app/build.py --release --diagnostics
python win/native-app/verify.py --exe win/out/native/NativeWasmTv-check.exe `
  --output win/out/native/verification --ffmpeg C:/tools/ffmpeg/bin/ffmpeg.exe
python win/native-app/build.py --release
python win/native-app/verify.py --exe win/out/native/NativeWasmTv-release.exe `
  --output win/out/native/release-audit --audit-only
```

发行构建移除开发探针和播放技术日志，不显示内部异常堆栈。内嵌脚本在压缩和编码后
加载，构建路径、调试符号和内部版本清单不随包公开；开源许可在“帮助 → 第三方许可”可读。
这用于增加静态分析成本，不提供不可逆向的保证。构建密钥和校验信息仅留在忽略的 `win/out`。

显示采用硬件解码和视频缩放，保留原始宽高比并按显示刷新率呈现。设备不支持时使用
软件回退；后台脚本异常会通知网页，使原站播放器可以执行自己的回退流程。
公网测试结果保留在 `win/out/native`，不能把本地夹具通过视为全部频道通过。
