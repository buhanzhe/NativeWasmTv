package xiao.bu.tv;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.*;
import org.json.*;

/** Authored route-dispatch test. Closes each session before its async browser launch. */
public final class MainNavigationProbe {
    private static final String[][] PAIRS={
        {"https://jnjapp.jntlj.com/tv-web/js/tv/iapp/live.html?url=https://jnjapp.jntlj.com/share/dHZsLTMwMy0x.html","https://jnjapp.jntlj.com/share/dHZsLTMwMy0x.html"},
        {"https://iapp.hichengyang.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.hichengyang.cn/share/dHZsLTQwMy01.html","https://iapp.hichengyang.cn/share/dHZsLTQwMy01.html"},
        {"https://iapp.licangmedia.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.licangmedia.cn/share/dHZsLTE3My0x.html","https://iapp.licangmedia.cn/share/dHZsLTE3My0x.html"},
        {"https://iapp.jimorongmei.com/tv-web/js/tv/iapp/live.html?url=https://iapp.jimorongmei.com/share/dHZsLTIyMS0z.html","https://iapp.jimorongmei.com/share/dHZsLTIyMS0z.html"},
        {"https://iapp.jncqrm.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.jncqrm.cn/share/dHZsLTE3MS0y.html","https://iapp.jncqrm.cn/share/dHZsLTE3MS0y.html"},
        {"https://iapp.shxrmtzx.com/tv-web/js/tv/iapp/live.html?url=https://iapp.shxrmtzx.com/share/dHZsLTk3LTE.html","https://iapp.shxrmtzx.com/share/dHZsLTk3LTE.html"},
        {"https://iapp.zhidongyingapp.com/tv-web/js/tv/iapp/live.html?url=https://iapp.zhidongyingapp.com/share/dHZsLTE2My01.html","https://iapp.zhidongyingapp.com/share/dHZsLTE2My01.html"},
        {"https://iapp.lijinnews.com/tv-web/js/tv/iapp/live.html?url=https://iapp.lijinnews.com/share/dHZsLTE1My0x.html","https://iapp.lijinnews.com/share/dHZsLTE1My0x.html"},
        {"https://iapp.qwmedia.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.qwmedia.cn/share/dHZsLTIyNy0x.html","https://iapp.qwmedia.cn/share/dHZsLTIyNy0x.html"},
        {"https://iapp.yunshangjiaozhou.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.yunshangjiaozhou.cn/share/dHZsLTMwNS0x.html","https://iapp.yunshangjiaozhou.cn/share/dHZsLTMwNS0x.html"},
        {"https://iapp.laixirongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.laixirongmei.cn/share/dHZsLTI1My0x.html","https://iapp.laixirongmei.cn/share/dHZsLTI1My0x.html"},
        {"https://iapp.aipingduapp.com/tv-web/js/tv/iapp/live.html?url=https://iapp.aipingduapp.com/share/dHZsLTQ1LTQ.html","https://iapp.aipingduapp.com/share/dHZsLTQ1LTQ.html"},
        {"https://iapp.wegood.wang/tv-web/js/tv/iapp/live.html?url=https://iapp.wegood.wang/share/dHZsLTQyMS0xNA.html","https://iapp.wegood.wang/share/dHZsLTQyMS0xNA.html"},
        {"https://iapp.aianqiu.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.aianqiu.cn/share/dHZsLTEzNy0z.html","https://iapp.aianqiu.cn/share/dHZsLTEzNy0z.html"},
        {"https://iapp.wfbhrmt.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.wfbhrmt.cn/share/dHZsLTE5OS0x.html","https://iapp.wfbhrmt.cn/share/dHZsLTE5OS0x.html"},
        {"https://iapp.clxrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.clxrmtzx.cn/share/dHZsLTEtMw.html","https://iapp.clxrmtzx.cn/share/dHZsLTEtMw.html"},
        {"https://iapp.changyirongmei.com/tv-web/js/tv/iapp/live.html?url=https://iapp.changyirongmei.com/share/dHZsLTQ3LTE.html","https://iapp.changyirongmei.com/share/dHZsLTQ3LTE.html"},
        {"https://iapp.fzqrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.fzqrmtzx.cn/share/dHZsLTI4NS0x.html","https://iapp.fzqrmtzx.cn/share/dHZsLTI4NS0x.html"},
        {"https://iapp.httv.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.httv.cn/share/dHZsLTEzMy0x.html","https://iapp.httv.cn/share/dHZsLTEzMy0x.html"},
        {"https://iapp.wfkwrm.com/tv-web/js/tv/iapp/live.html?url=https://iapp.wfkwrm.com/share/dHZsLTEyNy0xNw.html","https://iapp.wfkwrm.com/share/dHZsLTEyNy0xNw.html"},
        {"https://iapp.yunshanglinqu.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.yunshanglinqu.cn/share/dHZsLTIwNS0zOQ.html","https://iapp.yunshanglinqu.cn/share/dHZsLTIwNS0zOQ.html"},
        {"https://iapp.qingzhounews.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.qingzhounews.cn/share/dHZsLTEyNS0y.html","https://iapp.qingzhounews.cn/share/dHZsLTEyNS0y.html"},
        {"https://iapp.wcrmawckhd.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.wcrmawckhd.cn/share/dHZsLTE1LTM.html","https://iapp.wcrmawckhd.cn/share/dHZsLTE1LTM.html"},
        {"https://iapp.52zhucheng.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.52zhucheng.cn/share/dHZsLTExNS0yMw.html","https://iapp.52zhucheng.cn/share/dHZsLTExNS0yMw.html"},
        {"https://iapp.sgrmt.com/tv-web/js/tv/iapp/live.html?url=https://iapp.sgrmt.com/share/dHZsLTI3OS0x.html","https://iapp.sgrmt.com/share/dHZsLTI3OS0x.html"},
        {"https://iapp.changdaohao.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.changdaohao.cn/share/dHZsLTE3NS0x.html","https://iapp.changdaohao.cn/share/dHZsLTE3NS0x.html"},
        {"https://iapp.fuzaifushan.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.fuzaifushan.cn/share/dHZsLTE4OS00.html","https://iapp.fuzaifushan.cn/share/dHZsLTE4OS00.html"},
        {"https://iapp.hyzc.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.hyzc.cn/share/dHZsLTI1NS0x.html","https://iapp.hyzc.cn/share/dHZsLTI1NS0x.html"},
        {"https://iapp.laiyangrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.laiyangrongmei.cn/share/dHZsLTI0MS00.html","https://iapp.laiyangrongmei.cn/share/dHZsLTI0MS00.html"},
        {"https://iapp.laizhourongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.laizhourongmei.cn/share/dHZsLTIzOS0x.html","https://iapp.laizhourongmei.cn/share/dHZsLTIzOS0x.html"},
        {"https://iapp.mprmt.com/tv-web/js/tv/iapp/live.html?url=https://iapp.mprmt.com/share/dHZsLTI4MS0x.html","https://iapp.mprmt.com/share/dHZsLTI4MS0x.html"},
        {"https://iapp.xianjingpenglai.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.xianjingpenglai.cn/share/dHZsLTEwOS0y.html","https://iapp.xianjingpenglai.cn/share/dHZsLTEwOS0y.html"},
        {"https://iapp.aiqixiaapp.com/tv-web/js/tv/iapp/live.html?url=https://iapp.aiqixiaapp.com/share/dHZsLTE2NS0xMg.html","https://iapp.aiqixiaapp.com/share/dHZsLTE2NS0xMg.html"},
        {"https://iapp.zhaoyuanrongmei.com.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.zhaoyuanrongmei.com.cn/share/dHZsLTU1LTI.html","https://iapp.zhaoyuanrongmei.com.cn/share/dHZsLTU1LTI.html"},
        {"https://iapp.boshanhao.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.boshanhao.cn/share/dHZsLTE3LTg.html","https://iapp.boshanhao.cn/share/dHZsLTE3LTg.html"},
        {"https://iapp.gqxrmt.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.gqxrmt.cn/share/dHZsLTYxLTE.html","https://iapp.gqxrmt.cn/share/dHZsLTYxLTE.html"},
        {"https://iapp.htxw.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.htxw.cn/share/dHZsLTIzLTE1.html","https://iapp.htxw.cn/share/dHZsLTIzLTE1.html"},
        {"https://iapp.linzirongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.linzirongmei.cn/share/dHZsLTE1MS02.html","https://iapp.linzirongmei.cn/share/dHZsLTE1MS02.html"},
        {"https://iapp.yyrmapp.com/tv-web/js/tv/iapp/live.html?url=https://iapp.yyrmapp.com/share/dHZsLTIwMy02.html","https://iapp.yyrmapp.com/share/dHZsLTIwMy02.html"},
        {"https://iapp.zichuanrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.zichuanrongmei.cn/share/dHZsLTc1LTE.html","https://iapp.zichuanrongmei.cn/share/dHZsLTc1LTE.html"},
        {"https://iapp.aizhangdian.com/tv-web/js/tv/iapp/live.html?url=https://iapp.aizhangdian.com/share/dHZsLTEwMS0x.html","https://iapp.aizhangdian.com/share/dHZsLTEwMS0x.html"},
        {"https://iapp.izhoucun.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.izhoucun.cn/share/dHZsLTI1OS0x.html","https://iapp.izhoucun.cn/share/dHZsLTI1OS0x.html"},
        {"https://iapp.zzszrm.com/tv-web/js/tv/iapp/live.html?url=https://iapp.zzszrm.com/share/dHZsLTIzMy0x.html","https://iapp.zzszrm.com/share/dHZsLTIzMy0x.html"},
        {"https://iapp.tzrmt.com/tv-web/js/tv/iapp/live.html?url=https://iapp.tzrmt.com/share/dHZsLTEwMy0y.html","https://iapp.tzrmt.com/share/dHZsLTEwMy0y.html"},
        {"https://iapp.aixuechengapp.com/tv-web/js/tv/iapp/live.html?url=https://iapp.aixuechengapp.com/share/dHZsLTM3LTg.html","https://iapp.aixuechengapp.com/share/dHZsLTM3LTg.html"},
        {"https://iapp.yilan-app.com/tv-web/js/tv/iapp/live.html?url=https://iapp.yilan-app.com/share/dHZsLTIwOS0x.html","https://iapp.yilan-app.com/share/dHZsLTIwOS0x.html"},
        {"https://iapp.binchengrongmei.com/tv-web/js/tv/iapp/live.html?url=https://iapp.binchengrongmei.com/share/dHZsLTI0OS0zNQ.html","https://iapp.binchengrongmei.com/share/dHZsLTI0OS0zNQ.html"},
        {"https://iapp.aihuimin.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.aihuimin.cn/share/dHZsLTIxMS0y.html","https://iapp.aihuimin.cn/share/dHZsLTIxMS0y.html"},
        {"https://iapp.yangxinrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.yangxinrongmei.cn/share/dHZsLTIxNy0x.html","https://iapp.yangxinrongmei.cn/share/dHZsLTIxNy0x.html"},
        {"https://iapp.zhanhuarongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.zhanhuarongmei.cn/share/dHZsLTI3Ny0x.html","https://iapp.zhanhuarongmei.cn/share/dHZsLTI3Ny0x.html"},
        {"https://iapp.dztv.tv/tv-web/js/tv/iapp/live.html?url=https://iapp.dztv.tv/share/dHZsLTE3OS0y.html","https://iapp.dztv.tv/share/dHZsLTE3OS0y.html"},
        {"https://iapp.laolingrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.laolingrongmei.cn/share/dHZsLTI2Ny0x.html","https://iapp.laolingrongmei.cn/share/dHZsLTI2Ny0x.html"},
        {"https://iapp.lyxrmtzx.com/tv-web/js/tv/iapp/live.html?url=https://iapp.lyxrmtzx.com/share/dHZsLTQ5LTM.html","https://iapp.lyxrmtzx.com/share/dHZsLTQ5LTM.html"},
        {"https://iapp.njxrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.njxrmtzx.cn/share/dHZsLTE5My0x.html","https://iapp.njxrmtzx.cn/share/dHZsLTE5My0x.html"},
        {"https://iapp.pyxwapp.com/tv-web/js/tv/iapp/live.html?url=https://iapp.pyxwapp.com/share/dHZsLTE5LTI.html","https://iapp.pyxwapp.com/share/dHZsLTE5LTI.html"},
        {"https://iapp.wuchengrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.wuchengrmtzx.cn/share/dHZsLTMzLTQ.html","https://iapp.wuchengrmtzx.cn/share/dHZsLTMzLTQ.html"},
        {"https://iapp.cwcmc.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.cwcmc.cn/share/dHZsLTEzMS0x.html","https://iapp.cwcmc.cn/share/dHZsLTEzMS0x.html"},
        {"https://iapp.caoxianrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.caoxianrongmei.cn/share/dHZsLTg3LTI.html","https://iapp.caoxianrongmei.cn/share/dHZsLTg3LTI.html"},
        {"https://iapp.dongmingrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.dongmingrongmei.cn/share/dHZsLTExMS0y.html","https://iapp.dongmingrongmei.cn/share/dHZsLTExMS0y.html"},
        {"https://iapp.aidingtao.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.aidingtao.cn/share/dHZsLTI3LTc.html","https://iapp.aidingtao.cn/share/dHZsLTI3LTc.html"},
        {"https://iapp.juyerongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.juyerongmei.cn/share/dHZsLTEzOS0x.html","https://iapp.juyerongmei.cn/share/dHZsLTEzOS0x.html"},
        {"https://iapp.hzmdrm.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.hzmdrm.cn/share/dHZsLTIxOS02.html","https://iapp.hzmdrm.cn/share/dHZsLTIxOS02.html"},
        {"https://iapp.sdmlsz.com/tv-web/js/tv/iapp/live.html?url=https://iapp.sdmlsz.com/share/dHZsLTE1NS0y.html","https://iapp.sdmlsz.com/share/dHZsLTE1NS0y.html"},
        {"https://iapp.ycrmtzx.com.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.ycrmtzx.com.cn/share/dHZsLTEzNS0z.html","https://iapp.ycrmtzx.com.cn/share/dHZsLTEzNS0z.html"},
        {"https://iapp.jxrm.net/tv-web/js/tv/iapp/live.html?url=https://iapp.jxrm.net/share/dHZsLTEyOS0y.html","https://iapp.jxrm.net/share/dHZsLTEyOS0y.html"},
        {"https://iapp.lsrmapp.com/tv-web/js/tv/iapp/live.html?url=https://iapp.lsrmapp.com/share/dHZsLTg5LTE.html","https://iapp.lsrmapp.com/share/dHZsLTg5LTE.html"},
        {"https://iapp.jinriqufu.com/tv-web/js/tv/iapp/live.html?url=https://iapp.jinriqufu.com/share/dHZsLTEzLTE.html","https://iapp.jinriqufu.com/share/dHZsLTEzLTE.html"},
        {"https://iapp.rcrmt.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.rcrmt.cn/share/dHZsLTczLTg.html","https://iapp.rcrmt.cn/share/dHZsLTczLTg.html"},
        {"https://iapp.wstvs.com/tv-web/js/tv/iapp/live.html?url=https://iapp.wstvs.com/share/dHZsLTMwMS0x.html","https://iapp.wstvs.com/share/dHZsLTMwMS0x.html"},
        {"https://iapp.cprmtvs.com/tv-web/js/tv/iapp/live.html?url=https://iapp.cprmtvs.com/share/dHZsLTMxLTY.html","https://iapp.cprmtvs.com/share/dHZsLTMxLTY.html"},
        {"https://iapp.dcfrm.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.dcfrm.cn/share/dHZsLTI2NS0x.html","https://iapp.dcfrm.cn/share/dHZsLTI2NS0x.html"},
        {"https://iapp.decmc.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.decmc.cn/share/dHZsLTk1LTIy.html","https://iapp.decmc.cn/share/dHZsLTk1LTIy.html"},
        {"https://iapp.gaotangrongmei.com/tv-web/js/tv/iapp/live.html?url=https://iapp.gaotangrongmei.com/share/dHZsLTQzLTE.html","https://iapp.gaotangrongmei.com/share/dHZsLTQzLTE.html"},
        {"https://iapp.guanxianrongmei.com/tv-web/js/tv/iapp/live.html?url=https://iapp.guanxianrongmei.com/share/dHZsLTc5LTE.html","https://iapp.guanxianrongmei.com/share/dHZsLTc5LTE.html"},
        {"https://iapp.lqrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.lqrmtzx.cn/share/dHZsLTY1LTI.html","https://iapp.lqrmtzx.cn/share/dHZsLTY1LTI.html"},
        {"https://iapp.feixianshoufa.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.feixianshoufa.cn/share/dHZsLTQxLTExOQ.html","https://iapp.feixianshoufa.cn/share/dHZsLTQxLTExOQ.html"},
        {"https://iapp.lyhedong.com/tv-web/js/tv/iapp/live.html?url=https://iapp.lyhedong.com/share/dHZsLTE5MS0x.html","https://iapp.lyhedong.com/share/dHZsLTE5MS0x.html"},
        {"https://iapp.junanrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.junanrongmei.cn/share/dHZsLTEwNS00.html","https://iapp.junanrongmei.cn/share/dHZsLTEwNS00.html"},
        {"https://iapp.lylsrm.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.lylsrm.cn/share/dHZsLTIwMS0x.html","https://iapp.lylsrm.cn/share/dHZsLTIwMS0x.html"},
        {"https://iapp.luozhuangshoufa.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.luozhuangshoufa.cn/share/dHZsLTE0Ny0x.html","https://iapp.luozhuangshoufa.cn/share/dHZsLTE0Ny0x.html"},
        {"https://iapp.tanchengtv.com/tv-web/js/tv/iapp/live.html?url=https://iapp.tanchengtv.com/share/dHZsLTgzLTE.html","https://iapp.tanchengtv.com/share/dHZsLTgzLTE.html"},
        {"https://iapp.12345yinanshoufa.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.12345yinanshoufa.cn/share/dHZsLTE3Ny02.html","https://iapp.12345yinanshoufa.cn/share/dHZsLTE3Ny02.html"},
        {"https://iapp.jxrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.jxrmtzx.cn/share/dHZsLTE1OS0yMw.html","https://iapp.jxrmtzx.cn/share/dHZsLTE1OS0yMw.html"},
        {"https://iapp.wltvlive.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.wltvlive.cn/share/dHZsLTI5OS0xMA.html","https://iapp.wltvlive.cn/share/dHZsLTI5OS0xMA.html"},
        {"https://iapp.dpsjt.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.dpsjt.cn/share/dHZsLTE4Ny05.html","https://iapp.dpsjt.cn/share/dHZsLTE4Ny05.html"},
        {"https://iapp.daiyuerongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.daiyuerongmei.cn/share/dHZsLTI5My0x.html","https://iapp.daiyuerongmei.cn/share/dHZsLTI5My0x.html"},
        {"https://iapp.fcrmt.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.fcrmt.cn/share/dHZsLTUxLTY.html","https://iapp.fcrmt.cn/share/dHZsLTUxLTY.html"},
        {"https://iapp.nyxrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.nyxrmtzx.cn/share/dHZsLTEyMy0x.html","https://iapp.nyxrmtzx.cn/share/dHZsLTEyMy0x.html"},
        {"https://iapp.tsqrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.tsqrmtzx.cn/share/dHZsLTI2My0x.html","https://iapp.tsqrmtzx.cn/share/dHZsLTI2My0x.html"},
        {"https://iapp.xintairongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.xintairongmei.cn/share/dHZsLTU5LTI.html","https://iapp.xintairongmei.cn/share/dHZsLTU5LTI.html"},
        {"https://iapp.whhccm.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.whhccm.cn/share/dHZsLTIxMy01.html","https://iapp.whhccm.cn/share/dHZsLTIxMy01.html"},
        {"https://iapp.rcsrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.rcsrmtzx.cn/share/dHZsLTc3LTEw.html","https://iapp.rcsrmtzx.cn/share/dHZsLTc3LTEw.html"},
        {"https://iapp.rushanrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.rushanrongmei.cn/share/dHZsLTE0My04.html","https://iapp.rushanrongmei.cn/share/dHZsLTE0My04.html"},
        {"https://iapp.wendengsf.com/tv-web/js/tv/iapp/live.html?url=https://iapp.wendengsf.com/share/dHZsLTkxLTc.html","https://iapp.wendengsf.com/share/dHZsLTkxLTc.html"},
        {"https://iapp.jncqrm.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.jncqrm.cn/share/dHZsLTE3MS0yMA.html","https://iapp.jncqrm.cn/share/dHZsLTE3MS0yMA.html"},
        {"https://iapp.shxrmtzx.com/tv-web/js/tv/iapp/live.html?url=https://iapp.shxrmtzx.com/share/dHZsLTk3LTI.html","https://iapp.shxrmtzx.com/share/dHZsLTk3LTI.html"},
        {"https://iapp.zhidongyingapp.com/tv-web/js/tv/iapp/live.html?url=https://iapp.zhidongyingapp.com/share/dHZsLTE2My03.html","https://iapp.zhidongyingapp.com/share/dHZsLTE2My03.html"},
        {"https://iapp.qwmedia.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.qwmedia.cn/share/dHZsLTIyNy0z.html","https://iapp.qwmedia.cn/share/dHZsLTIyNy0z.html"},
        {"https://iapp.jimorongmei.com/tv-web/js/tv/iapp/live.html?url=https://iapp.jimorongmei.com/share/dHZsLTIyMS0y.html","https://iapp.jimorongmei.com/share/dHZsLTIyMS0y.html"},
        {"https://iapp.yunshangjiaozhou.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.yunshangjiaozhou.cn/share/dHZsLTMwNS0z.html","https://iapp.yunshangjiaozhou.cn/share/dHZsLTMwNS0z.html"},
        {"https://iapp.laixirongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.laixirongmei.cn/share/dHZsLTI1My0z.html","https://iapp.laixirongmei.cn/share/dHZsLTI1My0z.html"},
        {"https://iapp.aipingduapp.com/tv-web/js/tv/iapp/live.html?url=https://iapp.aipingduapp.com/share/dHZsLTQ1LTU.html","https://iapp.aipingduapp.com/share/dHZsLTQ1LTU.html"},
        {"https://iapp.wfcmw.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.wfcmw.cn/share/dHZsLTYzNS01.html","https://iapp.wfcmw.cn/share/dHZsLTYzNS01.html"},
        {"https://iapp.wfcmw.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.wfcmw.cn/share/dHZsLTYzNS03.html","https://iapp.wfcmw.cn/share/dHZsLTYzNS03.html"},
        {"https://iapp.wfcmw.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.wfcmw.cn/share/dHZsLTYzNS05.html","https://iapp.wfcmw.cn/share/dHZsLTYzNS05.html"},
        {"https://iapp.aianqiu.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.aianqiu.cn/share/dHZsLTEzNy00.html","https://iapp.aianqiu.cn/share/dHZsLTEzNy00.html"},
        {"https://iapp.changyirongmei.com/tv-web/js/tv/iapp/live.html?url=https://iapp.changyirongmei.com/share/dHZsLTQ3LTI.html","https://iapp.changyirongmei.com/share/dHZsLTQ3LTI.html"},
        {"https://iapp.qingzhounews.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.qingzhounews.cn/share/dHZsLTEyNS0z.html","https://iapp.qingzhounews.cn/share/dHZsLTEyNS0z.html"},
        {"https://iapp.sgrmt.com/tv-web/js/tv/iapp/live.html?url=https://iapp.sgrmt.com/share/dHZsLTI3OS0z.html","https://iapp.sgrmt.com/share/dHZsLTI3OS0z.html"},
        {"https://iapp.aizhangdian.com/tv-web/js/tv/iapp/live.html?url=https://iapp.aizhangdian.com/share/dHZsLTEwMS02.html","https://iapp.aizhangdian.com/share/dHZsLTEwMS02.html"},
        {"https://iapp.dztv.tv/tv-web/js/tv/iapp/live.html?url=https://iapp.dztv.tv/share/dHZsLTE3OS05.html","https://iapp.dztv.tv/share/dHZsLTE3OS05.html"},
        {"https://iapp.dztv.tv/tv-web/js/tv/iapp/live.html?url=https://iapp.dztv.tv/share/dHZsLTE3OS0zNg.html","https://iapp.dztv.tv/share/dHZsLTE3OS0zNg.html"},
        {"https://iapp.laolingrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.laolingrongmei.cn/share/dHZsLTI2Ny01.html","https://iapp.laolingrongmei.cn/share/dHZsLTI2Ny01.html"},
        {"https://iapp.dztv.tv/tv-web/js/tv/iapp/live.html?url=https://iapp.dztv.tv/share/dHZsLTE3OS02NQ.html","https://iapp.dztv.tv/share/dHZsLTE3OS02NQ.html"},
        {"https://iapp.dztv.tv/tv-web/js/tv/iapp/live.html?url=https://iapp.dztv.tv/share/dHZsLTE3OS01OA.html","https://iapp.dztv.tv/share/dHZsLTE3OS01OA.html"},
        {"https://iapp.dztv.tv/tv-web/js/tv/iapp/live.html?url=https://iapp.dztv.tv/share/dHZsLTE3OS0yMQ.html","https://iapp.dztv.tv/share/dHZsLTE3OS0yMQ.html"},
        {"https://iapp.dztv.tv/tv-web/js/tv/iapp/live.html?url=https://iapp.dztv.tv/share/dHZsLTE3OS0yMw.html","https://iapp.dztv.tv/share/dHZsLTE3OS0yMw.html"},
        {"https://iapp.dztv.tv/tv-web/js/tv/iapp/live.html?url=https://iapp.dztv.tv/share/dHZsLTE3OS00OA.html","https://iapp.dztv.tv/share/dHZsLTE3OS00OA.html"},
        {"https://iapp.dztv.tv/tv-web/js/tv/iapp/live.html?url=https://iapp.dztv.tv/share/dHZsLTE3OS01MA.html","https://iapp.dztv.tv/share/dHZsLTE3OS01MA.html"},
        {"https://iapp.cwcmc.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.cwcmc.cn/share/dHZsLTEzMS0y.html","https://iapp.cwcmc.cn/share/dHZsLTEzMS0y.html"},
        {"https://iapp.hzmdrm.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.hzmdrm.cn/share/dHZsLTIxOS0xNw.html","https://iapp.hzmdrm.cn/share/dHZsLTIxOS0xNw.html"},
        {"https://iapp.ycrmtzx.com.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.ycrmtzx.com.cn/share/dHZsLTEzNS0y.html","https://iapp.ycrmtzx.com.cn/share/dHZsLTEzNS0y.html"},
        {"https://iapp.jxrm.net/tv-web/js/tv/iapp/live.html?url=https://iapp.jxrm.net/share/dHZsLTEyOS00.html","https://iapp.jxrm.net/share/dHZsLTEyOS00.html"},
        {"https://iapp.rcrmt.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.rcrmt.cn/share/dHZsLTczLTk.html","https://iapp.rcrmt.cn/share/dHZsLTczLTk.html"},
        {"https://iapp.cprmtvs.com/tv-web/js/tv/iapp/live.html?url=https://iapp.cprmtvs.com/share/dHZsLTMxLTg.html","https://iapp.cprmtvs.com/share/dHZsLTMxLTg.html"},
        {"https://iapp.decmc.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.decmc.cn/share/dHZsLTk1LTI5.html","https://iapp.decmc.cn/share/dHZsLTk1LTI5.html"},
        {"https://iapp.gaotangrongmei.com/tv-web/js/tv/iapp/live.html?url=https://iapp.gaotangrongmei.com/share/dHZsLTQzLTU.html","https://iapp.gaotangrongmei.com/share/dHZsLTQzLTU.html"},
        {"https://iapp.lqrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.lqrmtzx.cn/share/dHZsLTY1LTU.html","https://iapp.lqrmtzx.cn/share/dHZsLTY1LTU.html"},
        {"https://iapp.feixianshoufa.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.feixianshoufa.cn/share/dHZsLTQxLTExNw.html","https://iapp.feixianshoufa.cn/share/dHZsLTQxLTExNw.html"},
        {"https://iapp.lyhedong.com/tv-web/js/tv/iapp/live.html?url=https://iapp.lyhedong.com/share/dHZsLTE5MS0y.html","https://iapp.lyhedong.com/share/dHZsLTE5MS0y.html"},
        {"https://iapp.junanrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.junanrongmei.cn/share/dHZsLTEwNS01.html","https://iapp.junanrongmei.cn/share/dHZsLTEwNS01.html"},
        {"https://iapp.luozhuangshoufa.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.luozhuangshoufa.cn/share/dHZsLTE0Ny0xNw.html","https://iapp.luozhuangshoufa.cn/share/dHZsLTE0Ny0xNw.html"},
        {"https://iapp.mengyinshoufa.com/tv-web/js/tv/iapp/live.html?url=https://iapp.mengyinshoufa.com/share/dHZsLTE2MS0xNQ.html","https://iapp.mengyinshoufa.com/share/dHZsLTE2MS0xNQ.html"},
        {"https://iapp.pyxrmt.com.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.pyxrmt.com.cn/share/dHZsLTM0NS0xNA.html","https://iapp.pyxrmt.com.cn/share/dHZsLTM0NS0xNA.html"},
        {"https://iapp.tanchengtv.com/tv-web/js/tv/iapp/live.html?url=https://iapp.tanchengtv.com/share/dHZsLTgzLTI.html","https://iapp.tanchengtv.com/share/dHZsLTgzLTI.html"},
        {"https://iapp.12345yinanshoufa.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.12345yinanshoufa.cn/share/dHZsLTE3Ny03.html","https://iapp.12345yinanshoufa.cn/share/dHZsLTE3Ny03.html"},
        {"https://iapp.jxrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.jxrmtzx.cn/share/dHZsLTE1OS0yNw.html","https://iapp.jxrmtzx.cn/share/dHZsLTE1OS0yNw.html"},
        {"https://iapp.wltvlive.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.wltvlive.cn/share/dHZsLTI5OS0xMg.html","https://iapp.wltvlive.cn/share/dHZsLTI5OS0xMg.html"},
        {"https://iapp.dpsjt.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.dpsjt.cn/share/dHZsLTE4Ny0xMQ.html","https://iapp.dpsjt.cn/share/dHZsLTE4Ny0xMQ.html"},
        {"https://iapp.nyxrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.nyxrmtzx.cn/share/dHZsLTEyMy03.html","https://iapp.nyxrmtzx.cn/share/dHZsLTEyMy03.html"},
        {"https://iapp.xintairongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.xintairongmei.cn/share/dHZsLTU5LTM.html","https://iapp.xintairongmei.cn/share/dHZsLTU5LTM.html"},
        {"https://iapp.rcsrmtzx.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.rcsrmtzx.cn/share/dHZsLTc3LTEx.html","https://iapp.rcsrmtzx.cn/share/dHZsLTc3LTEx.html"},
        {"https://iapp.rushanrongmei.cn/tv-web/js/tv/iapp/live.html?url=https://iapp.rushanrongmei.cn/share/dHZsLTE0My05.html","https://iapp.rushanrongmei.cn/share/dHZsLTE0My05.html"},
        {"https://iapp.wendengsf.com/tv-web/js/tv/iapp/live.html?url=https://iapp.wendengsf.com/share/dHZsLTkxLTg.html","https://iapp.wendengsf.com/share/dHZsLTkxLTg.html"}
    };
    private static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    public static void run(final Instrumentation it, final boolean adapted){
        final Bundle result=new Bundle();int code=-1;MainActivity activity=null;
        try{
            activity=(MainActivity)it.startActivitySync(new Intent(it.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1000);final MainActivity owner=activity;final JSONArray rows=new JSONArray();final Throwable[] failure={null};
            it.runOnMainSync(new Runnable(){public void run(){try{
                ((java.util.concurrent.atomic.AtomicInteger)field(owner,"catalogLoadGeneration")).incrementAndGet();
                for(String n:new String[]{"cancelCustomSourceTimeout","clearPendingPlayer","cancelRemoteResolve","releasePlayer","closeWebSource"}){Method m=MainActivity.class.getDeclaredMethod(n);m.setAccessible(true);m.invoke(owner);}
                Method open=MainActivity.class.getDeclaredMethod("openWebSource",Channel.class,String.class,int.class,String.class,boolean.class);open.setAccessible(true);
                Method close=MainActivity.class.getDeclaredMethod("closeWebSource");close.setAccessible(true);
                WebSourceView view=(WebSourceView)field(owner,"webSourceView");
                for(int i=0;i<PAIRS.length*4+1;i++){
                    String input;
                    if(i<PAIRS.length) input=PAIRS[i][0];
                    else if(i==PAIRS.length*4) input="https://example.com/unrelated";
                    else { int j=(i-PAIRS.length)/3, variant=(i-PAIRS.length)%3;
                        input=PAIRS[j][0]+(variant==0?"&extra=1":variant==1?"#extra":"x"); }
                    String configured="webview://"+input;Channel ch=new Channel("999","Navigation fixture","",configured,null,null);
                    String expected=i<PAIRS.length&&(adapted||i<51)?PAIRS[i][1]:input;
                    open.invoke(owner,ch,configured,70000+i,"",false);
                    String actual=view.currentPageUrl();boolean unchanged=configured.equals(ch.url)&&ch.urls.length==1&&configured.equals(ch.urls[0]);
                    JSONObject row=new JSONObject();row.put("case",i);row.put("input",input);row.put("expected",expected);row.put("actual",actual);row.put("channelSourceUnchanged",unchanged);row.put("pass",expected.equals(actual)&&unchanged);rows.put(row);
                    open.invoke(owner,ch,configured,71000+i,"",false);
                    String repeated=view.currentPageUrl();row.put("repeatedActual",repeated);
                    close.invoke(owner);boolean closed=view.currentPageUrl().length()==0;row.put("closedPageEmpty",closed);
                    row.put("pass",expected.equals(actual)&&expected.equals(repeated)&&unchanged&&closed);
                    if(!expected.equals(actual)||!expected.equals(repeated)||!unchanged||!closed)throw new AssertionError("Navigation dispatch mismatch: "+row);
                }
            }catch(Throwable e){failure[0]=e;}}});
            if(failure[0]!=null)throw failure[0];
            JSONObject proof=new JSONObject();proof.put("adaptedExpected",adapted);proof.put("sdk",android.os.Build.VERSION.SDK_INT);proof.put("cpuCores",Runtime.getRuntime().availableProcessors());proof.put("passed",rows.length());proof.put("total",PAIRS.length*4+1);proof.put("knownPairs",PAIRS.length);proof.put("baselineMappedPairs",51);proof.put("cases",rows);proof.put("scope","Real MainActivity openWebSource to WebSourceView/LegacyWebSession target selection. Sessions closed before async browser launch. No renderer/live-network acceptance assertion.");
            result.putString("stream","NAVIGATION_DISPATCH_PASS "+proof.toString()+"\n");
        }catch(Throwable e){code=0;result.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{if(activity!=null){final MainActivity a=activity;it.runOnMainSync(new Runnable(){public void run(){a.finish();}});}}
        it.finish(code,result);
    }
}
