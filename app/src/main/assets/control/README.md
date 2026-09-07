# 局域网管理网站

此目录整体打入 Debug 和 Release APK，无需在线下载管理页面。

```text
index.html             一级入口
pages/*.html           独立二级页面（groups 是频道配置的子页面）
css/common.css         全站公共样式
js/common.js           HTTP 请求、连接状态、页面生命周期、返回导航
js/pointer-queue.js    飞鼠串行事件队列，逐帧合并移动、保留按键边界
js/pages/*.js          对应页面的渲染和交互逻辑
```

- 页面通过真实 HTML 地址跳转，不使用 hash 路由。APP 内的返回按钮和系统返回键均调用 WebView 返回堆栈；普通浏览器使用 `history.back()`，没有历史时回到父页面。
- `ControlSite.java` 是本地 HTTP 服务的静态资源白名单，新增页面或依赖时同步登记。所有 API 保持 `/api/…` 原地址。
- 每个页面只加载公共脚本和自己的脚本。飞鼠传感器与媒体轮询在各自页面退出/隐藏时暂停；浏览器返回后恢复。
- 频道配置中未保存的源和 EPG 输入保存在当前标签页的 sessionStorage；电视端配置发生变化时，不用旧草稿覆盖新配置。
- 保持 ES5 语法，管理网站仍兼容旧 Android WebView。Ku9 的 Android 版本要求不受此拆分影响。
- 原有独立在线页面 `/video-recorder.html`、`/flymouse.html` 继续走原来的分发方式；`/pages/flymouse.html` 是管理站内置的飞鼠页面。

从仓库根目录运行回归测试（Node.js 22.13+）：

```powershell
npm ci --prefix scripts
npm test --prefix scripts
```

测试覆盖页面独立初始化、资源/事件依赖、ES5 语法、返回接口、频道草稿、10000 频道解析合并、媒体轮询及视频截屏逻辑。

飞鼠移动、按下、松开、滚轮和键盘事件使用同一个队列。超时不重放旧点击，先取消旧拖动。
`docs/pointer-queue.js` 为在线页面使用的同一份脚本；修改后同步，回归测试会检查两份文件一致。

静态资源白名单和 MIME 测试（JDK 8+，仓库根目录）：

```powershell
New-Item -ItemType Directory -Force .codex-tmp/control-site-tests
javac -encoding UTF-8 -d .codex-tmp/control-site-tests app/src/main/java/xiao/bu/tv/ControlSite.java scripts/TestControlSite.java
java -cp .codex-tmp/control-site-tests xiao.bu.tv.TestControlSite app/src/main/assets/control
```
