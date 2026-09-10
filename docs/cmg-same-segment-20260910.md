# 央视频同分片解密对比：ARM32 编译器退化（2026-09-10）

## 结论

Android 4.4.4 / SM-G3608 上，当前插件库解密同一份分片的耗时约为旧内置库的 2.09 倍。保持插件源码，改用 NDK r20b Clang 重编译后恢复到旧库水平；输出逐字节相同。测试没有运行 QuickJS、WebView、播放器或网络下载，排除了它们作为本次差异的来源。

|库|有效次数|平均墙钟耗时|平均线程 CPU 耗时|最小–最大|
|---|---:|---:|---:|---:|
|线上 1.6.0 内置库|10|1171.0ms|1157.8ms|1156–1194ms|
|设备当前 GCC 插件库|10|2451.8ms|2437.1ms|2439–2472ms|
|Clang 临时验证插件库|10|1161.5ms|1151.4ms|1151–1177ms|

## 输入及正确性

- CCTV-2 央视频直播的一个 5 秒 TS 分片，1,228,956 字节。下载一次后所有测试使用同一本地文件，输入读取、复制和 SHA-256 计算均在计时之外。
- 输入 SHA-256：`aa91fc7b7c8e4d95334c707918a0d954c3e2b3c3171b41661384c468420cbfb4`。
- 三份库所有运行输出 SHA-256：`b0bdc13fce95e28ba161f646d03cc1ec82e4d7d5afac54a817709d9e9d3af831`，每次均有 912,994 字节相对密文改变。
- FFmpeg 全段解码退出码 0，错误日志为空；ffprobe 帧数和分辨率见 `video-validation.json`。
- 相同输出同时排除了“更快的库跳过了解密”或“慢库额外处理不同输入”的情况。

## 方法

1. 从指定线上 nTv.apk 提取 `lib/armeabi-v7a/libcmg_decrypt.so`。从设备应用实际安装目录读取 `cjs-sites-v4/yangshipin.cn/armeabi-v7a/1/yangshipin.so`。未以本地构建文件冒充安装版本。
2. 从 v1.6.0 源码原样提取 TS/PES/NAL 解密相关方法及辅助类，编译为同一独立 Java 测试程序。JNI 类仅将加载入口替换为命令行指定的 .so 路径。两库调用路径相同。
3. 通过 ADB app_process 启动独立进程；每轮清空解密会话，固定播放器标记、时钟和页面地址。计时覆盖原生初始化及 TS 解密流程，包含共同的 Java TS 拆包/JNI 开销，不包含下载、库加载和校验。
4. 每个进程执行 6 次，第 0 次作为预热单列，不计均值。顺序为旧库→插件库→插件库→旧库；每库共 10 个有效样本。Clang 验证库另执行两进程，各 5 个有效样本。
5. 应用全程在计时前 force-stop，测试单线程，不并行运行其他解密基准。墙钟来自 elapsedRealtime，CPU 来自 currentThreadTimeMillis。

## 编译差异证据

- 当前插件脚本 `D:/documents/cjs/tools/build-native.ps1:15` 强制指定 `NDK_TOOLCHAIN_VERSION=4.9`；设备插件二进制 .comment 记录 GCC 4.9。
- 旧内置库 .comment 记录 Android Clang 8.0.7（同时存在 GCC 运行库标记，不能仅凭 GCC 字样判断旧库）。
- 当前站点的 `cmg_decryptor.c`、`generated_cmg/cmg_wasm.c`、`cmg-rt/wasm-rt.h`、`cmg-rt/wasm-rt-impl.h` 与 v1.6.0 对应文件逐字节一致（忽略 CRLF）。
- 临时验证库保持当前站点 Android.mk、源码、O3 和 ARM32 ABI，使用 NDK r20b、`NDK_TOOLCHAIN_VERSION=clang`、android-16 编译，仅输出到测试目录。性能恢复且解密输出相同。

## 边界与后续

本次证明 ARM32 原生库构建产物存在退化，足以解释播放变慢的一部分；不能据此承诺 Android 9 总首播时间一定由 7 秒恢复到 3 秒。切台旧任务等待、网络和首播缓冲仍需单独计时。尚未定位到具体机器指令或编译器优化 pass。

正式插件、构建脚本和服务器文件均未修改或发布。下一步应将正式 ARM32 插件改为经过验证的 Clang 构建，并回归 Android 4.4 与 Android 9；ARM64 需要独立验证。

## 文件

所有证据位于 `D:/documents/nTv-private-merge/.codex-tmp/cmg-same-segment/`：`summary.json`、`*-valid*.log`、`input.ts`、`*-clear.ts`、Java 测试源码、`bench.jar`、`clang-build.log`。临时验证库为 `clang-libs/armeabi-v7a/yangshipin.so`。

- 旧内置库 SHA-256：`a7d6f85ed4fdb2e3518c62eef2af1ad73bfe281f4c75353bb7634121afe84a91`。
- 设备插件库 SHA-256：`89802d27e20ef45a0852a3bde986b42ee01888e90435f2bfb066bf60e8b0cec8`。
- Clang 验证库 SHA-256：`de977e2358736c6d64b37b19f07c8238442e5fbc20d92a2cd866f20a3fc10a98`。
