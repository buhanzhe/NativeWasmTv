# 144 exact iapp navigation corrections

This change preserves the existing51 mappings byte-for-byte and adds93 individually checked raw wrapper/target pairs. It changes only the finite pair table and its tests; the exact raw-matching algorithm is unchanged. Independent review approved the144-map change; it is now integrated.

The configured channel name and original URL remain unchanged. Navigation uses only the explicit same-host HTTPS share URL already present in each original wrapper. The original404 is retained. No generic extraction, host inference, decoding, credentials, queries, fragments, ports, security fallback or redirect following is introduced. Existing network/security behavior and the API19 CPU gate remain unchanged.

## Complete source accounting

All189 source wrappers were classified.173 had exact original404 evidence and their inner targets were attempted:146 usable HTML captures,11 cross-host redirects not followed,16 timeouts not retried.16 original non404 URLs were excluded:8 HTTP502,2 transport000,4 HTTP200 error pages and2 redirects. All146 captured pages have eight actualAPI19 Windows/native-Chrome30 snapshots.

Among95 new captured pages,93 have platform/channel-specific visible body. Most also show player UI or a media diagnostic. Rows769/770 instead retain119 characters of platform/channel and copyright/contact text plus a raw video node; video.js is missing and videojs/EventTarget errors prevent player initialization. Those two are explicitly weaker navigation-only observations, not player-UI success. Two title-only pages,788 and789 (临沭TV-1/TV-2), are excluded: only27 text characters, missing jQuery/video.js/zh-CN, and EventTarget/$ errors. Thus144 total mappings are proposed and45 of189 wrappers remain unmapped. No channel label is renamed even when the target's displayed title differs (e.g.773 source东阿综艺, displayed东阿县融媒体中心综合信息频道).

## Verification scope and limitations

These are navigation improvements only. Shared weixin JavaScript syntax errors, media failures and unknown/offline resources remain. Captured replay does not prove live playback, error-free compatibility, real network behavior, or full-app acceptance. No proprietary captured scripts/media are included in this candidate. The common weixin hash was already measured against the existing eight-second native compiler budget and was not recompiled.

Across all146 captures,129 dependency sets are complete and17 are partial;29 missing-resource occurrences represent20 unique URLs. Among95 new captures,83 are complete and12 partial; the two title-only exclusions are among those partial sets. Captured file hashes and every direct HTML reference were independently reconciled without additional requests. Original51 retain their prior evidence caveats, including row729's missing original headers.

Row660's initial five-snapshot observation log remains preserved. A separate longer local replay yielded eight snapshots and useful body lengths167/172/203/.../203. This is not a new network request. One previously timed-out Jiaozhou stylesheet was inadvertently read again during sibling capture; all remaining prior failed asset outcomes were then preserved without rereads. No denied/security URL was retried.

The host-JVM suite passed7,016 assertions:144 mapped and843 unchanged across987 entries, including all denied/timeout/redirect/title-only exclusions. Each pair also retains27 unsafe-input rejection mutations. A separate45-wrapper exclusion test verifies each unmapped original explicitly. Integration is complete. The expanded native-routing probe passed577/577 both before (old51-map baseline) and after (approved144-map helper). Earlier routing reports remain historical.

## Proposed93 additions

- Line733: 乐陵新闻综合 → https://iapp.laolingrongmei.cn/share/dHZsLTI2Ny0x.html (displayed title: 新闻综合频道; body233 chars; missing captured assets0).
- Line735: 临邑1 → https://iapp.lyxrmtzx.com/share/dHZsLTQ5LTM.html (displayed title: 临邑一; body255 chars; missing captured assets0).
- Line736: 宁津综合 → https://iapp.njxrmtzx.cn/share/dHZsLTE5My0x.html (displayed title: 宁津融媒体中心综合频道; body394 chars; missing captured assets0).
- Line737: 平原综合 → https://iapp.pyxwapp.com/share/dHZsLTE5LTI.html (displayed title: 平原综合; body290 chars; missing captured assets0).
- Line739: 武城综合 → https://iapp.wuchengrmtzx.cn/share/dHZsLTMzLTQ.html (displayed title: 武城综合频道; body333 chars; missing captured assets0).
- Line745: 成武综合 → https://iapp.cwcmc.cn/share/dHZsLTEzMS0x.html (displayed title: 综合频道; body394 chars; missing captured assets0).
- Line747: 曹县综合 → https://iapp.caoxianrongmei.cn/share/dHZsLTg3LTI.html (displayed title: 曹县融媒体中心综合频道; body174 chars; missing captured assets0).
- Line748: 东明新闻综合 → https://iapp.dongmingrongmei.cn/share/dHZsLTExMS0y.html (displayed title: 新闻综合频道; body62 chars; missing captured assets0).
- Line749: 定陶新闻 → https://iapp.aidingtao.cn/share/dHZsLTI3LTc.html (displayed title: 定陶综合频道; body321 chars; missing captured assets0).
- Line750: 巨野新闻 → https://iapp.juyerongmei.cn/share/dHZsLTEzOS0x.html (displayed title: 综合频道; body290 chars; missing captured assets0).
- Line751: 牡丹区新闻综合 → https://iapp.hzmdrm.cn/share/dHZsLTIxOS02.html (displayed title: 牡丹电视台—新闻综合; body194 chars; missing captured assets0).
- Line753: 单县综合 → https://iapp.sdmlsz.com/share/dHZsLTE1NS0y.html (displayed title: 单县新闻综合频道; body87 chars; missing captured assets0).
- Line754: 郓城新闻 → https://iapp.ycrmtzx.com.cn/share/dHZsLTEzNS0z.html (displayed title: 郓城综合; body272 chars; missing captured assets0).
- Line756: 金乡综合 → https://iapp.jxrm.net/share/dHZsLTEyOS0y.html (displayed title: 综合频道; body61 chars; missing captured assets0).
- Line758: 梁山综合 → https://iapp.lsrmapp.com/share/dHZsLTg5LTE.html (displayed title: 梁山综合; body115 chars; missing captured assets0).
- Line759: 曲阜新闻综合 → https://iapp.jinriqufu.com/share/dHZsLTEzLTE.html (displayed title: 曲阜综合频道; body63 chars; missing captured assets0).
- Line760: 任城新闻综合 → https://iapp.rcrmt.cn/share/dHZsLTczLTg.html (displayed title: 新闻综合; body62 chars; missing captured assets1).
- Line764: 汶上综合 → https://iapp.wstvs.com/share/dHZsLTMwMS0x.html (displayed title: 汶上综合频道; body63 chars; missing captured assets0).
- Line769: 茌平综合 → https://iapp.cprmtvs.com/share/dHZsLTMxLTY.html (displayed title: 茌平综合; body119 chars; missing captured assets1).
- Line771: 东昌综合 → https://iapp.dcfrm.cn/share/dHZsLTI2NS0x.html (displayed title: 东昌综合; body62 chars; missing captured assets1).
- Line772: 东阿综合 → https://iapp.decmc.cn/share/dHZsLTk1LTIy.html (displayed title: 东阿县融媒体中心新闻综合频道; body281 chars; missing captured assets0).
- Line774: 高唐综合 → https://iapp.gaotangrongmei.com/share/dHZsLTQzLTE.html (displayed title: 高唐综合频道; body62 chars; missing captured assets0).
- Line776: 冠县综合 → https://iapp.guanxianrongmei.com/share/dHZsLTc5LTE.html (displayed title: 冠县综合频道; body116 chars; missing captured assets0).
- Line777: 临清综合 → https://iapp.lqrmtzx.cn/share/dHZsLTY1LTI.html (displayed title: 临清综合频道; body62 chars; missing captured assets0).
- Line779: 费县综合 → https://iapp.feixianshoufa.cn/share/dHZsLTQxLTExOQ.html (displayed title: 费县综合; body145 chars; missing captured assets0).
- Line781: 河东综合 → https://iapp.lyhedong.com/share/dHZsLTE5MS0x.html (displayed title: 河东综合频道; body63 chars; missing captured assets0).
- Line783: 莒南综合 → https://iapp.junanrongmei.cn/share/dHZsLTEwNS00.html (displayed title: 莒南综合频道; body184 chars; missing captured assets0).
- Line787: 兰山综合 → https://iapp.lylsrm.cn/share/dHZsLTIwMS0x.html (displayed title: 兰山电视; body95 chars; missing captured assets2).
- Line790: 罗庄综合 → https://iapp.luozhuangshoufa.cn/share/dHZsLTE0Ny0x.html (displayed title: 罗庄综合; body393 chars; missing captured assets3).
- Line796: 郯城综合 → https://iapp.tanchengtv.com/share/dHZsLTgzLTE.html (displayed title: 郯城TV-1; body63 chars; missing captured assets0).
- Line798: 沂南综合 → https://iapp.12345yinanshoufa.cn/share/dHZsLTE3Ny02.html (displayed title: 沂南综合频道; body63 chars; missing captured assets0).
- Line802: 莒县综合 → https://iapp.jxrmtzx.cn/share/dHZsLTE1OS0yMw.html (displayed title: 莒县综合; body60 chars; missing captured assets0).
- Line804: 五莲综合 → https://iapp.wltvlive.cn/share/dHZsLTI5OS0xMA.html (displayed title: 五莲综合频道; body63 chars; missing captured assets0).
- Line806: 东平综合 → https://iapp.dpsjt.cn/share/dHZsLTE4Ny05.html (displayed title: 东平综合; body62 chars; missing captured assets0).
- Line808: 岱岳TV → https://iapp.daiyuerongmei.cn/share/dHZsLTI5My0x.html (displayed title: 岱岳TV; body61 chars; missing captured assets0).
- Line809: 肥城生活 → https://iapp.fcrmt.cn/share/dHZsLTUxLTY.html (displayed title: 肥城生活频道; body63 chars; missing captured assets0).
- Line810: 宁阳综合 → https://iapp.nyxrmtzx.cn/share/dHZsLTEyMy0x.html (displayed title: 宁阳综合频道; body63 chars; missing captured assets1).
- Line812: 泰山TV → https://iapp.tsqrmtzx.cn/share/dHZsLTI2My0x.html (displayed title: 泰山频道; body61 chars; missing captured assets0).
- Line813: 新泰综合 → https://iapp.xintairongmei.cn/share/dHZsLTU5LTI.html (displayed title: 新泰综合频道; body63 chars; missing captured assets0).
- Line818: 威海环翠综合 → https://iapp.whhccm.cn/share/dHZsLTIxMy01.html (displayed title: 环翠综合频道; body65 chars; missing captured assets0).
- Line819: 荣成综合 → https://iapp.rcsrmtzx.cn/share/dHZsLTc3LTEw.html (displayed title: 荣成综合; body258 chars; missing captured assets0).
- Line821: 乳山综合 → https://iapp.rushanrongmei.cn/share/dHZsLTE0My04.html (displayed title: 综合频道; body61 chars; missing captured assets0).
- Line823: 文登TV1 → https://iapp.wendengsf.com/share/dHZsLTkxLTc.html (displayed title: 文登一台; body232 chars; missing captured assets0).
- Line636: 长清生活 → https://iapp.jncqrm.cn/share/dHZsLTE3MS0yMA.html (displayed title: 长清生活; body115 chars; missing captured assets0).
- Line642: 商河影视 → https://iapp.shxrmtzx.com/share/dHZsLTk3LTI.html (displayed title: 商河影视频道; body245 chars; missing captured assets0).
- Line646: 东营区科教影视 → https://iapp.zhidongyingapp.com/share/dHZsLTE2My03.html (displayed title: 科教影视频道; body62 chars; missing captured assets0).
- Line653: 黄岛生活 → https://iapp.qwmedia.cn/share/dHZsLTIyNy0z.html (displayed title: 生活频道; body98 chars; missing captured assets0).
- Line654: 即墨综合 → https://iapp.jimorongmei.com/share/dHZsLTIyMS0y.html (displayed title: 即墨融媒【综合频道】; body358 chars; missing captured assets0).
- Line656: 胶州生活 → https://iapp.yunshangjiaozhou.cn/share/dHZsLTMwNS0z.html (displayed title: 生活频道; body172 chars; missing captured assets0).
- Line660: 莱西生活 → https://iapp.laixirongmei.cn/share/dHZsLTI1My0z.html (displayed title: 莱西生活频道; body203 chars; missing captured assets0).
- Line662: 平度生活服务 → https://iapp.aipingduapp.com/share/dHZsLTQ1LTU.html (displayed title: 平度生活服务频道; body184 chars; missing captured assets0).
- Line664: 潍坊生活 → https://iapp.wfcmw.cn/share/dHZsLTYzNS01.html (displayed title: 经济生活; body59 chars; missing captured assets0).
- Line665: 潍坊影视综艺 → https://iapp.wfcmw.cn/share/dHZsLTYzNS03.html (displayed title: 影视综艺; body59 chars; missing captured assets0).
- Line666: 潍坊科教文旅 → https://iapp.wfcmw.cn/share/dHZsLTYzNS05.html (displayed title: 科教文旅; body59 chars; missing captured assets0).
- Line669: 安丘民生 → https://iapp.aianqiu.cn/share/dHZsLTEzNy00.html (displayed title: 民生频道; body60 chars; missing captured assets0).
- Line673: 昌邑经济生活 → https://iapp.changyirongmei.com/share/dHZsLTQ3LTI.html (displayed title: 昌邑经济生活; body62 chars; missing captured assets0).
- Line681: 青州文化旅游 → https://iapp.qingzhounews.cn/share/dHZsLTEyNS0z.html (displayed title: 【青州】文化旅游; body64 chars; missing captured assets0).
- Line685: 寿光蔬菜 → https://iapp.sgrmt.com/share/dHZsLTI3OS0z.html (displayed title: 寿光蔬菜频道; body62 chars; missing captured assets0).
- Line713: 张店2 → https://iapp.aizhangdian.com/share/dHZsLTEwMS02.html (displayed title: ZDTV-2; body94 chars; missing captured assets0).
- Line730: 德州图文 → https://iapp.dztv.tv/share/dHZsLTE3OS05.html (displayed title: 图文频道; body210 chars; missing captured assets0).
- Line732: 陵城综合2 → https://iapp.dztv.tv/share/dHZsLTE3OS0zNg.html (displayed title: 陵城区综合频道; body213 chars; missing captured assets0).
- Line734: 乐陵城市生活 → https://iapp.laolingrongmei.cn/share/dHZsLTI2Ny01.html (displayed title: 城市生活频道; body233 chars; missing captured assets0).
- Line738: 庆云综合 → https://iapp.dztv.tv/share/dHZsLTE3OS02NQ.html (displayed title: 庆云综合频道; body212 chars; missing captured assets0).
- Line740: 武城综艺影视 → https://iapp.dztv.tv/share/dHZsLTE3OS01OA.html (displayed title: 武城综艺影视; body212 chars; missing captured assets0).
- Line741: 夏津综合 → https://iapp.dztv.tv/share/dHZsLTE3OS0yMQ.html (displayed title: 夏津综合频道; body212 chars; missing captured assets0).
- Line742: 夏津公共 → https://iapp.dztv.tv/share/dHZsLTE3OS0yMw.html (displayed title: 夏津公共频道; body212 chars; missing captured assets0).
- Line743: 禹城综合 → https://iapp.dztv.tv/share/dHZsLTE3OS00OA.html (displayed title: 禹城综合频道; body212 chars; missing captured assets0).
- Line744: 禹城综艺 → https://iapp.dztv.tv/share/dHZsLTE3OS01MA.html (displayed title: 禹城综艺频道; body212 chars; missing captured assets0).
- Line746: 成武综艺 → https://iapp.cwcmc.cn/share/dHZsLTEzMS0y.html (displayed title: 综艺频道; body394 chars; missing captured assets0).
- Line752: 牡丹区综艺 → https://iapp.hzmdrm.cn/share/dHZsLTIxOS0xNw.html (displayed title: 牡丹电视台—综艺; body192 chars; missing captured assets0).
- Line755: 郓城综艺 → https://iapp.ycrmtzx.com.cn/share/dHZsLTEzNS0y.html (displayed title: 民生综艺; body272 chars; missing captured assets0).
- Line757: 金乡生活 → https://iapp.jxrm.net/share/dHZsLTEyOS00.html (displayed title: 生活频道; body61 chars; missing captured assets0).
- Line761: 任城影视娱乐 → https://iapp.rcrmt.cn/share/dHZsLTczLTk.html (displayed title: 影视娱乐; body62 chars; missing captured assets1).
- Line770: 茌平生活 → https://iapp.cprmtvs.com/share/dHZsLTMxLTg.html (displayed title: 茌平生活; body119 chars; missing captured assets1).
- Line773: 东阿综艺 → https://iapp.decmc.cn/share/dHZsLTk1LTI5.html (displayed title: 东阿县融媒体中心综合信息频道; body281 chars; missing captured assets0).
- Line775: 高唐综艺 → https://iapp.gaotangrongmei.com/share/dHZsLTQzLTU.html (displayed title: 高唐综艺频道; body62 chars; missing captured assets0).
- Line778: 临清经济信息 → https://iapp.lqrmtzx.cn/share/dHZsLTY1LTU.html (displayed title: 临清经济信息; body62 chars; missing captured assets0).
- Line780: 费县生活 → https://iapp.feixianshoufa.cn/share/dHZsLTQxLTExNw.html (displayed title: 费县生活; body145 chars; missing captured assets0).
- Line782: 河东影视 → https://iapp.lyhedong.com/share/dHZsLTE5MS0y.html (displayed title: 河东影视频道; body63 chars; missing captured assets0).
- Line784: 莒南影视 → https://iapp.junanrongmei.cn/share/dHZsLTEwNS01.html (displayed title: 莒南影视频道; body184 chars; missing captured assets0).
- Line791: 罗庄影视 → https://iapp.luozhuangshoufa.cn/share/dHZsLTE0Ny0xNw.html (displayed title: 罗庄影视; body393 chars; missing captured assets3).
- Line793: 蒙阴2套 → https://iapp.mengyinshoufa.com/share/dHZsLTE2MS0xNQ.html (displayed title: 蒙阴TV—2; body178 chars; missing captured assets0).
- Line795: 平邑生活 → https://iapp.pyxrmt.com.cn/share/dHZsLTM0NS0xNA.html (displayed title: 平邑生活; body61 chars; missing captured assets0).
- Line797: 郯城2套 → https://iapp.tanchengtv.com/share/dHZsLTgzLTI.html (displayed title: 郯城TV-2; body63 chars; missing captured assets0).
- Line799: 沂南红色影视 → https://iapp.12345yinanshoufa.cn/share/dHZsLTE3Ny03.html (displayed title: 沂南红色影视; body63 chars; missing captured assets0).
- Line803: 莒县2套 → https://iapp.jxrmtzx.cn/share/dHZsLTE1OS0yNw.html (displayed title: 莒县生活; body60 chars; missing captured assets0).
- Line805: 五莲文化旅游 → https://iapp.wltvlive.cn/share/dHZsLTI5OS0xMg.html (displayed title: 文化旅游频道; body63 chars; missing captured assets0).
- Line807: 东平民生 → https://iapp.dpsjt.cn/share/dHZsLTE4Ny0xMQ.html (displayed title: 东平民生; body62 chars; missing captured assets0).
- Line811: 宁阳2套 → https://iapp.nyxrmtzx.cn/share/dHZsLTEyMy03.html (displayed title: 宁阳-2; body61 chars; missing captured assets1).
- Line814: 新泰乡村 → https://iapp.xintairongmei.cn/share/dHZsLTU5LTM.html (displayed title: 新泰乡村频道; body63 chars; missing captured assets0).
- Line820: 荣成生活 → https://iapp.rcsrmtzx.cn/share/dHZsLTc3LTEx.html (displayed title: 荣成生活; body258 chars; missing captured assets0).
- Line822: 乳山生活 → https://iapp.rushanrongmei.cn/share/dHZsLTE0My05.html (displayed title: 生活频道; body61 chars; missing captured assets0).
- Line824: 文登TV2 → https://iapp.wendengsf.com/share/dHZsLTkxLTg.html (displayed title: 文登二台; body232 chars; missing captured assets0).

## Terminal full-App native routing verification

The actual API19 four-core MainActivity.openWebSource → WebSourceView/LegacyWebSession routing probe passes577/577 cases before and577/577 after. The before baseline correctly maps only the previous51 pairs; the after build maps144. Each case verifies expected exact routing, repeated opening, preserved Channel source and a cleared closed session. The probe closes sessions before asynchronous browser launch. It does not verify renderer navigation, live network, video playback, renderer Back/Forward history or error-free official-page operation. Those limits and all captured-page script/media failures remain unchanged.

Evidence: build-tools/iapp-final144-runtime/before-four-core.{log,json} and after-four-core.{log,json}. The after App also contains an independently reviewed selector change; this navigation delta excludes selector files. Policy fixture and strict compiler/WebView smoke proof42/errors[] pass. Final standalone20-case synthetic outcome is tracked separately and must not be inferred from the routing result.
