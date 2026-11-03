# 管理页视觉预览副本

运行 `python previews/control-minimal/serve.py`，打开 http://127.0.0.1:18763/ 。

保留原页面条目、顺序与媒体控制器。minimal.css 微调视觉；system-refine.css 优化系统信息与日志工具栏。生产目录未修改。

已恢复原管理页脚本与交互。device-api.js 将原脚本的 /api/ 请求直接连接到 http://192.168.0.106:9966 ，用户操作会真实影响小米 6。图片、日志下载等普通浏览器 GET 请求由本机预览服务器转发到同一设备。系统信息使用原版完整字段渲染逻辑，所有 CPU、WebView、存储、显示与网络信息均保留。预览只在点击后检查更新，进入系统页不会自动检查更新。

旧 mock.js、system-mock.js、system-live.js、quick-add-mock.js 是早期模拟文件，当前 HTML 不再加载它们。

兼容目标 Android 4.1：适配脚本使用 ES5，样式补充旧 WebKit 布局回退。已在现代浏览器检查 320、375、768 宽度；尚未通过 Android 4.1 真机验收。

选择菜单统一使用 micro-select.js / micro-select.css：基于原 select.options 生成锚点浮层，动态选项也适用；值实际变化时仅触发一次原 change 事件。支持点击外部、Esc、浏览器返回关闭；不改变底层 API 和保存逻辑。Android 4.1 使用 ES5 和固定定位，无第三方依赖及模糊滤镜。

所有页面内弹窗（频道切换除外）使用 micro-dialogs.css 的紧凑浮层和 160ms 入场动画。micro-dialogs.js 提供异步确认卡；更新安装、智能添加、JS 上传、删除脚本和放弃修改的调用点已改为确认回调，取消不提交。原频道切换 JS/CSS 未改。选择菜单和确认卡有 120ms 退场；支持系统减少动态效果偏好。复杂表单和线路列表保留内部滚动。
