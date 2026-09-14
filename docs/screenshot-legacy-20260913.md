# 旧 Android 截图进展

2026-09-13，API 15 emulator-5564，当前 ARM32 release。

通过媒体控制器使用的 /api/recording/screenshot 接口取到 PNG，640×360、56174 字节。使用固定 60 秒 H.264 baseline 测试片，截图时间约 0.52 秒；图片方向及红绿蓝颜色经查看正常。后续查询 positionMs=59960，播放已到片尾。

API 14–23 候选路径临时将当前 IJK 输出接到 GLES2 SurfaceTexture，读取一帧后恢复原 SurfaceHolder；API 24+ 保持 PixelCopy。没有新增 native 库。该路径可能短暂中断可见画面，暂停截图、切台并发和厂商硬解仍需验证。

4.4 安装/启动命令被自动审批拒绝（blocked by policy），4.4、7.1.1、Android 9 尚未完成测试；Android 17 未连接。相册保存未验证，不作为测试通过项。

已恢复虚拟机原频道源配置、停止应用及测试 HTTP 服务。图片见 .codex-tmp/screenshot-404.png；后续状态见 .codex-tmp/screenshot-404-after.json。

## 7.1.1 与 Android 9 补测

同日使用当前 ARM32 1.7.0（versionCode 7）release 和同一份 640×360 H.264 baseline 测试片：

| 设备 | 播放截图接口 | 相册保存 |
| --- | --- | --- |
| 小米 6，Android 7.1.1，ff0316c7 | 通过，PNG 66586 字节 | 未完成，进入管理页面的测试命令被自动审批拒绝，仅返回 `blocked by policy` |
| NX809J Android 9 虚拟机，127.0.0.1:21503 | 通过，PNG 57230 字节 | 保存逻辑与 MediaStore 登记通过 |

两张截图均人工查看，方向和测试色条正常。文件为 `.codex-tmp/screenshot-711.png`、`.codex-tmp/screenshot-9.png`。

Android 9 使用 `ScreenshotGalleryInstrumentation` 调用媒体控制器共用的 `ScreenshotGallery.save`，实际生成 `/storage/emulated/0/Pictures/nTv/nTv-1789289035014.png`，并从 MediaStore 查询到该图片。将相册文件拉回电脑后，其 SHA-256 与输入截图一致（`ABF9F3A7F800CF76056242DBF7236C7C8DB92A8BC9A2AA8DEC6050C5D38395C4`）。这项测试验证保存实现与媒体库登记，不代表已点击相册应用或完整验证媒体控制器按钮流程。设备原有存储权限已授权，首次授权弹窗未测试。

补测结束已恢复两台设备的原频道源配置，确认测试分组消失；Android 9 已从测试 debug 包恢复为 release 包；停止两台应用和测试 HTTP 服务。相册中的测试截图保留供查看。
