package xiao.bu.tv;

import java.net.URI;

public final class VerifiedIappNavigationTest {
    private static int assertions;
    private static void equal(String want, String got) {
        assertions++;
        if (want == null ? got != null : !want.equals(got))
            throw new AssertionError("Expected " + want + ", got " + got);
    }
    public static void main(String[] args) throws Exception {
        String[][] pairs = {
            {"jnjapp.jntlj.com", "dHZsLTMwMy0x"},
            {"iapp.hichengyang.cn", "dHZsLTQwMy01"},
            {"iapp.licangmedia.cn", "dHZsLTE3My0x"},
            {"iapp.jimorongmei.com", "dHZsLTIyMS0z"},
            {"iapp.jncqrm.cn", "dHZsLTE3MS0y"},
            {"iapp.shxrmtzx.com", "dHZsLTk3LTE"},
            {"iapp.zhidongyingapp.com", "dHZsLTE2My01"},
            {"iapp.lijinnews.com", "dHZsLTE1My0x"},
            {"iapp.qwmedia.cn", "dHZsLTIyNy0x"},
            {"iapp.yunshangjiaozhou.cn", "dHZsLTMwNS0x"},
            {"iapp.laixirongmei.cn", "dHZsLTI1My0x"},
            {"iapp.aipingduapp.com", "dHZsLTQ1LTQ"},
            {"iapp.wegood.wang", "dHZsLTQyMS0xNA"},
            {"iapp.aianqiu.cn", "dHZsLTEzNy0z"},
            {"iapp.wfbhrmt.cn", "dHZsLTE5OS0x"},
            {"iapp.clxrmtzx.cn", "dHZsLTEtMw"},
            {"iapp.changyirongmei.com", "dHZsLTQ3LTE"},
            {"iapp.fzqrmtzx.cn", "dHZsLTI4NS0x"},
            {"iapp.httv.cn", "dHZsLTEzMy0x"},
            {"iapp.wfkwrm.com", "dHZsLTEyNy0xNw"},
            {"iapp.yunshanglinqu.cn", "dHZsLTIwNS0zOQ"},
            {"iapp.qingzhounews.cn", "dHZsLTEyNS0y"},
            {"iapp.wcrmawckhd.cn", "dHZsLTE1LTM"},
            {"iapp.52zhucheng.cn", "dHZsLTExNS0yMw"},
            {"iapp.sgrmt.com", "dHZsLTI3OS0x"},
            {"iapp.changdaohao.cn", "dHZsLTE3NS0x"},
            {"iapp.fuzaifushan.cn", "dHZsLTE4OS00"},
            {"iapp.hyzc.cn", "dHZsLTI1NS0x"},
            {"iapp.laiyangrongmei.cn", "dHZsLTI0MS00"},
            {"iapp.laizhourongmei.cn", "dHZsLTIzOS0x"},
            {"iapp.mprmt.com", "dHZsLTI4MS0x"},
            {"iapp.xianjingpenglai.cn", "dHZsLTEwOS0y"},
            {"iapp.aiqixiaapp.com", "dHZsLTE2NS0xMg"},
            {"iapp.zhaoyuanrongmei.com.cn", "dHZsLTU1LTI"},
            {"iapp.boshanhao.cn", "dHZsLTE3LTg"},
            {"iapp.gqxrmt.cn", "dHZsLTYxLTE"},
            {"iapp.htxw.cn", "dHZsLTIzLTE1"},
            {"iapp.linzirongmei.cn", "dHZsLTE1MS02"},
            {"iapp.yyrmapp.com", "dHZsLTIwMy02"},
            {"iapp.zichuanrongmei.cn", "dHZsLTc1LTE"},
            {"iapp.aizhangdian.com", "dHZsLTEwMS0x"},
            {"iapp.izhoucun.cn", "dHZsLTI1OS0x"},
            {"iapp.zzszrm.com", "dHZsLTIzMy0x"},
            {"iapp.tzrmt.com", "dHZsLTEwMy0y"},
            {"iapp.aixuechengapp.com", "dHZsLTM3LTg"},
            {"iapp.yilan-app.com", "dHZsLTIwOS0x"},
            {"iapp.binchengrongmei.com", "dHZsLTI0OS0zNQ"},
            {"iapp.aihuimin.cn", "dHZsLTIxMS0y"},
            {"iapp.yangxinrongmei.cn", "dHZsLTIxNy0x"},
            {"iapp.zhanhuarongmei.cn", "dHZsLTI3Ny0x"},
            {"iapp.dztv.tv", "dHZsLTE3OS0y"},
            {"iapp.laolingrongmei.cn", "dHZsLTI2Ny0x"},
            {"iapp.lyxrmtzx.com", "dHZsLTQ5LTM"},
            {"iapp.njxrmtzx.cn", "dHZsLTE5My0x"},
            {"iapp.pyxwapp.com", "dHZsLTE5LTI"},
            {"iapp.wuchengrmtzx.cn", "dHZsLTMzLTQ"},
            {"iapp.cwcmc.cn", "dHZsLTEzMS0x"},
            {"iapp.caoxianrongmei.cn", "dHZsLTg3LTI"},
            {"iapp.dongmingrongmei.cn", "dHZsLTExMS0y"},
            {"iapp.aidingtao.cn", "dHZsLTI3LTc"},
            {"iapp.juyerongmei.cn", "dHZsLTEzOS0x"},
            {"iapp.hzmdrm.cn", "dHZsLTIxOS02"},
            {"iapp.sdmlsz.com", "dHZsLTE1NS0y"},
            {"iapp.ycrmtzx.com.cn", "dHZsLTEzNS0z"},
            {"iapp.jxrm.net", "dHZsLTEyOS0y"},
            {"iapp.lsrmapp.com", "dHZsLTg5LTE"},
            {"iapp.jinriqufu.com", "dHZsLTEzLTE"},
            {"iapp.rcrmt.cn", "dHZsLTczLTg"},
            {"iapp.wstvs.com", "dHZsLTMwMS0x"},
            {"iapp.cprmtvs.com", "dHZsLTMxLTY"},
            {"iapp.dcfrm.cn", "dHZsLTI2NS0x"},
            {"iapp.decmc.cn", "dHZsLTk1LTIy"},
            {"iapp.gaotangrongmei.com", "dHZsLTQzLTE"},
            {"iapp.guanxianrongmei.com", "dHZsLTc5LTE"},
            {"iapp.lqrmtzx.cn", "dHZsLTY1LTI"},
            {"iapp.feixianshoufa.cn", "dHZsLTQxLTExOQ"},
            {"iapp.lyhedong.com", "dHZsLTE5MS0x"},
            {"iapp.junanrongmei.cn", "dHZsLTEwNS00"},
            {"iapp.lylsrm.cn", "dHZsLTIwMS0x"},
            {"iapp.luozhuangshoufa.cn", "dHZsLTE0Ny0x"},
            {"iapp.tanchengtv.com", "dHZsLTgzLTE"},
            {"iapp.12345yinanshoufa.cn", "dHZsLTE3Ny02"},
            {"iapp.jxrmtzx.cn", "dHZsLTE1OS0yMw"},
            {"iapp.wltvlive.cn", "dHZsLTI5OS0xMA"},
            {"iapp.dpsjt.cn", "dHZsLTE4Ny05"},
            {"iapp.daiyuerongmei.cn", "dHZsLTI5My0x"},
            {"iapp.fcrmt.cn", "dHZsLTUxLTY"},
            {"iapp.nyxrmtzx.cn", "dHZsLTEyMy0x"},
            {"iapp.tsqrmtzx.cn", "dHZsLTI2My0x"},
            {"iapp.xintairongmei.cn", "dHZsLTU5LTI"},
            {"iapp.whhccm.cn", "dHZsLTIxMy01"},
            {"iapp.rcsrmtzx.cn", "dHZsLTc3LTEw"},
            {"iapp.rushanrongmei.cn", "dHZsLTE0My04"},
            {"iapp.wendengsf.com", "dHZsLTkxLTc"},
            {"iapp.jncqrm.cn", "dHZsLTE3MS0yMA"},
            {"iapp.shxrmtzx.com", "dHZsLTk3LTI"},
            {"iapp.zhidongyingapp.com", "dHZsLTE2My03"},
            {"iapp.qwmedia.cn", "dHZsLTIyNy0z"},
            {"iapp.jimorongmei.com", "dHZsLTIyMS0y"},
            {"iapp.yunshangjiaozhou.cn", "dHZsLTMwNS0z"},
            {"iapp.laixirongmei.cn", "dHZsLTI1My0z"},
            {"iapp.aipingduapp.com", "dHZsLTQ1LTU"},
            {"iapp.wfcmw.cn", "dHZsLTYzNS01"},
            {"iapp.wfcmw.cn", "dHZsLTYzNS03"},
            {"iapp.wfcmw.cn", "dHZsLTYzNS05"},
            {"iapp.aianqiu.cn", "dHZsLTEzNy00"},
            {"iapp.changyirongmei.com", "dHZsLTQ3LTI"},
            {"iapp.qingzhounews.cn", "dHZsLTEyNS0z"},
            {"iapp.sgrmt.com", "dHZsLTI3OS0z"},
            {"iapp.aizhangdian.com", "dHZsLTEwMS02"},
            {"iapp.dztv.tv", "dHZsLTE3OS05"},
            {"iapp.dztv.tv", "dHZsLTE3OS0zNg"},
            {"iapp.laolingrongmei.cn", "dHZsLTI2Ny01"},
            {"iapp.dztv.tv", "dHZsLTE3OS02NQ"},
            {"iapp.dztv.tv", "dHZsLTE3OS01OA"},
            {"iapp.dztv.tv", "dHZsLTE3OS0yMQ"},
            {"iapp.dztv.tv", "dHZsLTE3OS0yMw"},
            {"iapp.dztv.tv", "dHZsLTE3OS00OA"},
            {"iapp.dztv.tv", "dHZsLTE3OS01MA"},
            {"iapp.cwcmc.cn", "dHZsLTEzMS0y"},
            {"iapp.hzmdrm.cn", "dHZsLTIxOS0xNw"},
            {"iapp.ycrmtzx.com.cn", "dHZsLTEzNS0y"},
            {"iapp.jxrm.net", "dHZsLTEyOS00"},
            {"iapp.rcrmt.cn", "dHZsLTczLTk"},
            {"iapp.cprmtvs.com", "dHZsLTMxLTg"},
            {"iapp.decmc.cn", "dHZsLTk1LTI5"},
            {"iapp.gaotangrongmei.com", "dHZsLTQzLTU"},
            {"iapp.lqrmtzx.cn", "dHZsLTY1LTU"},
            {"iapp.feixianshoufa.cn", "dHZsLTQxLTExNw"},
            {"iapp.lyhedong.com", "dHZsLTE5MS0y"},
            {"iapp.junanrongmei.cn", "dHZsLTEwNS01"},
            {"iapp.luozhuangshoufa.cn", "dHZsLTE0Ny0xNw"},
            {"iapp.mengyinshoufa.com", "dHZsLTE2MS0xNQ"},
            {"iapp.pyxrmt.com.cn", "dHZsLTM0NS0xNA"},
            {"iapp.tanchengtv.com", "dHZsLTgzLTI"},
            {"iapp.12345yinanshoufa.cn", "dHZsLTE3Ny03"},
            {"iapp.jxrmtzx.cn", "dHZsLTE1OS0yNw"},
            {"iapp.wltvlive.cn", "dHZsLTI5OS0xMg"},
            {"iapp.dpsjt.cn", "dHZsLTE4Ny0xMQ"},
            {"iapp.nyxrmtzx.cn", "dHZsLTEyMy03"},
            {"iapp.xintairongmei.cn", "dHZsLTU5LTM"},
            {"iapp.rcsrmtzx.cn", "dHZsLTc3LTEx"},
            {"iapp.rushanrongmei.cn", "dHZsLTE0My05"},
            {"iapp.wendengsf.com", "dHZsLTkxLTg"}
        };
        equal(null, VerifiedIappNavigation.targetFor(null));
        equal("", VerifiedIappNavigation.targetFor(""));
        for (String[] p : pairs) {
            String origin = "https://" + p[0];
            String target = origin + "/share/" + p[1] + ".html";
            String prefix = origin + "/tv-web/js/tv/iapp/live.html?url=";
            String wrapper = prefix + target;
            equal(target, VerifiedIappNavigation.targetFor(wrapper));
            equal(target, VerifiedIappNavigation.targetFor(target));
            URI outer = new URI(wrapper), inner = new URI(target);
            equal("https", outer.getScheme()); equal("https", inner.getScheme());
            equal(outer.getHost(), inner.getHost()); equal(null, inner.getRawUserInfo());
            equal(null, inner.getRawQuery()); equal(null, inner.getRawFragment());
            if (!inner.getPath().matches("/share/[A-Za-z0-9_-]+\\.html")
                    || outer.getPort() != -1 || inner.getPort() != -1)
                throw new AssertionError("Unsafe configured pair");
            String[] rejected = {
                wrapper + "&url=" + target, wrapper + "&extra=1", wrapper + "#fragment",
                prefix + target + "?url=https://evil.invalid", prefix + target + "#fragment",
                prefix + target.replace("https://", "http://"),
                wrapper.replaceFirst("https://", "http://"),
                prefix + target.replace(p[0], "evil.invalid"),
                prefix + target.replace(p[0], p[0] + ".evil.invalid"),
                prefix + target.replace("https://", "https://user:password@"),
                wrapper.replaceFirst(p[0], "user@" + p[0]),
                prefix + target.replace(p[0], p[0] + ":443"),
                prefix + target.replace(p[0], p[0] + ":8443"),
                prefix + target.replace("/share/", "/share/../share/"),
                prefix + target.replace("/share/", "/share/%2e%2e/share/"),
                prefix + target.replace("https://", "https%3A%2F%2F"),
                prefix + target.replace("https://", "https%253A%252F%252F"),
                prefix + target.replace("/share/", "%2Fshare%2F"),
                prefix + target.replace("/share/", "\\share\\"),
                prefix + target.replace(".html", ".html%00"),
                prefix + target.replace(".html", "%ZZ.html"),
                prefix + "//" + p[0] + "/share/" + p[1] + ".html",
                wrapper.replace("live.html", "other.html"),
                wrapper.replace("?url=", "?URL="), " " + wrapper, wrapper + "\n",
                prefix + origin + "/share/unverified.html"
            };
            for (String input : rejected) equal(input, VerifiedIappNavigation.targetFor(input));
        }
        if (args.length != 1) throw new AssertionError("Source list path required");
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(args[0]), "UTF-8"));
        String line; int sourceCount = 0, mappedCount = 0;
        while ((line = reader.readLine()) != null) {
            int marker = line.indexOf("webview://");
            if (marker < 0) continue;
            sourceCount++;
            String original = line.substring(marker + 10);
            String savedOriginal = new String(original);
            String expected = original;
            for (String[] p : pairs) {
                String target = "https://" + p[0] + "/share/" + p[1] + ".html";
                String wrapper = "https://" + p[0] + "/tv-web/js/tv/iapp/live.html?url=" + target;
                if (wrapper.equals(original)) { expected = target; mappedCount++; }
            }
            equal(expected, VerifiedIappNavigation.targetFor(original));
            equal(savedOriginal, original);
        }
        reader.close();
        if (sourceCount != 987 || mappedCount != 144)
            throw new AssertionError("Unexpected source/mapping count: " + sourceCount + "/" + mappedCount);
        System.out.println("VerifiedIappNavigation: " + assertions + " assertions passed; " + sourceCount + " corpus entries, " + mappedCount + " mapped, " + (sourceCount - mappedCount) + " unchanged");
    }
}
