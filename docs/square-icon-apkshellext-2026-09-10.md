# 方形图标与 ApkShellext2 兼容

使用用户提供的 `E:\Downloads\ApkShellext2\ApkShellext2.dll`（0.2.0.7）直接调用 `ApkQuickReader.ApkReader.getIcon(Size)`，没有注册扩展或重启 Explorer。

## 原因与修复

- 旧包的自适应图标引用 shape 渐变背景，扩展在 `parseGradient` 抛出空引用异常，`getIcon` 返回空。
- 将背景临时替换为纯色后，扩展忽略 foreground 的 group 缩放、位移和 stroke，画面只剩被裁切的局部。
- 自适应背景改为与横幅一致的蓝色；前景生成等效的绝对坐标及填充轮廓，无 group 变换或 stroke 依赖。
- 保留 adaptive-icon 的前景/背景分层与系统裁切；旧系统仍使用原 PNG，电视横幅不变。
- `build-launcher-mark.py` 生成原始几何后自动调用 `build-launcher-compat.py`，避免后续修改遗漏兼容前景。

## 验证

`output/release-20260910-square-icon` 的 32/64 位正式包均构建及签名验证通过。使用同一 DLL 从两个 APK 提取 32、64、256 像素图标，六次均成功；检查 256 像素输出确认电视外框、天线和 TV 字样完整。扩展自身的矢量栅格化有锯齿，不能代表 Android 系统的矢量渲染质量。

未在用户的 Windows Explorer 实例中验证图标缓存更新，也未在 TV 桌面重新安装测试。
