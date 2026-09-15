package xiao.bu.tv;

public final class GithubProxyTest {
    private static void eq(String a,String b) {
        if(a == null ? b != null : !a.equals(b)) throw new AssertionError(a + " != " + b);
    }
    public static void main(String[] args) {
        String raw="https://raw.githubusercontent.com/a/b/main/script.js?id=x&token=a+b";
        GithubProxy.setBaseUrl(" https://mirror.example/proxy ");
        eq(GithubProxy.baseUrl(),"https://mirror.example/proxy/");
        eq(GithubProxy.apply(raw),GithubProxy.baseUrl()+raw);
        eq(GithubProxy.apply(GithubProxy.apply(raw)),GithubProxy.apply(raw));
        eq(GithubProxy.unwrap(GithubProxy.apply(raw)),raw);
        eq(GithubProxy.apply(GithubProxy.DEFAULT_BASE_URL+raw),GithubProxy.baseUrl()+raw);
        for(String ordinary:new String[]{null,"http://192.168.1.8:9966/x","https://cdn.example/video.ts",
                "https://github.com.evil.example/test","https://example.com/github.com/a"}) {
            eq(GithubProxy.apply(ordinary),ordinary);
        }
        for(String bad:new String[]{"ftp://mirror.example","mirror.example","https://u:p@mirror.example/",
                "https://mirror.example/?url=","https://mirror.example/#x","https://mirror.example:65536/"}) {
            String previous=GithubProxy.baseUrl();
            try { GithubProxy.setBaseUrl(bad); throw new AssertionError("Accepted " + bad); }
            catch(IllegalArgumentException expected) { eq(GithubProxy.baseUrl(),previous); }
        }
        GithubProxy.setBaseUrl("http://192.168.1.8:8080/proxy/");
        eq(GithubProxy.apply(raw),"http://192.168.1.8:8080/proxy/"+raw);
        GithubProxy.setEnabled(false);
        eq(GithubProxy.apply(raw),raw);
        eq(GithubProxy.apply(GithubProxy.baseUrl()+raw),raw);
        eq(GithubProxy.apply(GithubProxy.DEFAULT_BASE_URL+raw),raw);
        eq(GithubProxy.apply("https://gh-proxy.org/"+raw),raw);
        eq(GithubProxy.apply("https://ghfile.geekertao.top/"+raw),raw);
        eq(GithubProxy.apply("https://github-proxy.memory-echoes.cn/"+raw),raw);
        eq(GithubProxy.apply("https://github.tbap.top/"+raw),raw);

        eq(GithubProxy.apply("https://cdn.example/video.ts"),"https://cdn.example/video.ts");
        GithubProxy.setEnabled(true);
        eq(GithubProxy.apply(raw),GithubProxy.baseUrl()+raw);
        GithubProxy.setBaseUrl("");
        eq(GithubProxy.baseUrl(),GithubProxy.DEFAULT_BASE_URL);
        System.out.println("GitHub proxy routing and validation passed");
    }
}
