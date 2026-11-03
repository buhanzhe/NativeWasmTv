# 轻量内核二进制

本目录仅保存 `armeabi-v7a`、`arm64-v8a`、`x86` 的 `libntvLitebro.so`，不包含轻量内核源码。

这些文件是本地内核构建输入的快照，主工程仍通过本地 `browser-core/` 生成的 AAR 打包；此目录不另行加入 JNI 打包路径，避免重复打包。ARM32 文件已与本次 bro 验证包中的同名文件逐字节核对；ARM64 和 x86 文件仅存档，不代表完成设备验证。

`.so` 不能替代内核 Java 接口和 JS 资源，构建依赖见根目录 README。分发安装包时的许可及完整对应源码义务见根目录 `LICENSE`、`COPYRIGHT.md` 和 `THIRD_PARTY_NOTICES.md`。
