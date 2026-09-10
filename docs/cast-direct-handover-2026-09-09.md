# IP 接管与后台 Direct 升级

接管先通过输入的 IP 建立会话、打开控制界面，再后台尝试 Direct。Direct 权限不阻塞首次 IP 接管。新地址验证及新控制连接确认后才替换旧连接，更新播放地址并清除延迟统计；不重置媒体 PTS 或 RTP 序号。电视保留接管前的频道快照。切换会重新解析当前节目，可能短暂重新起播。

接收端 `wifiDirect.handoverProtocol=1` 表示支持切换。新 hello 携带新的 `hostUrl` / `sessionId` 以及 `previousHostUrl` / `previousSessionId`，只允许当前未过期会话更换地址。旧版本接收端继续走 LAN。后台失败不主动退出原 LAN 会话；底层无线切换若真实断联，仍执行原有 3 秒租约。

API 19 及以下接收端使用电视组主兼容路径，其他接收端保留手机组主策略。已建立的 Direct 地址不会被后台重复拆组。修复了电视经 Direct 请求播放时仍上报路由器 IP、被手机误拒绝的问题。

权限同时判断系统与 target SDK。当前 target 25：API 23+ 使用 FINE_LOCATION，并检查发现所需的位置服务；Android 17 使用 INTERNET 的旧 target 兼容授权。NEARBY_WIFI_DEVICES 和 ACCESS_LOCAL_NETWORK 的高 target 分支已加入判断，未在本任务升级 target。未来升级 target 时仍需配套更新编译 SDK 和现代定位声明。官方依据：[Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifip2p)、[本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)。

## 实测

- 手机已连接 2437MHz 的“学尤尤网络科技”。原 xyy-1221 即使限制频带仍选择 5GHz，其自动连接已暂时关闭。手机当前普通 LAN 与电视原 LAN 不互通。
- USB 下发建组后，4.4 电视组主为 192.168.49.1，7.1.1 手机客户端为 192.168.49.197。接受电视邀请后，此角色组合多次成功；手机组主未成功。
- 已有 Direct IP 接管接口约 0.38–0.51 秒返回；这不是视频端到端延迟。
- 在同一 Direct 链路将手机管理地址从端口 9966 切到 9967，验证会话迁移。电视目录改用新地址，控制保持在线，最终 prepared=true、playing=true，硬解 1920×1080 H.264 / AAC。该测试不等于跨物理接口自动切换。
- 正常退出后，电视从“广西测试”恢复“吉林都市”，手机恢复自己的频道。
- 13 个网页回归及权限版本、会话校验、计时重置 JVM 回归通过；两种 ABI 的 debug 和签名 release 构建通过。
- 尚未完成双方普通 LAN 可互通条件下的完整自动升级实测；Android 10–17 本轮无对应实机。
- 组合故障测试及后续正式包自动替换安装被自动审批拦截，没有重试。当前设备保留调试包，本轮不新增实机故障恢复结论。

正式包：output/release-20260909-direct-handover/nTv.apk、nTv64.apk。包名 xiao.bu.tv，版本 1.6.0 / code 6。未更换原生解码库。

证据：本地 .codex-tmp/direct-handover/ 中的 direct24-final.log、direct-24-tv-owner.json、alias-final.json、normal-exit.json 和构建日志。
