package xiao.bu.tv;

import org.json.JSONObject;
public final class CompatibilityTest {
    static int checks;
    static void check(boolean b) { checks++; if (!b) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        check(CjsNativeProfile.select("armeabi-v7a", 14).equals("armv7-base"));
        check(CjsNativeProfile.select("armeabi-v7a", 18).equals("armv7-base"));
        check(CjsNativeProfile.select("armeabi-v7a", 19).equals("armv7-perf"));
        check(CjsNativeProfile.select("armeabi-v7a", 30).equals("armv7-perf"));
        check(CjsNativeProfile.select("arm64-v8a", 21).equals("arm64"));
        JSONObject nativeFile=new JSONObject().put("name", "cctv.so").put("abi", "armeabi-v7a")
                .put("profile", "armv7-perf").put("minSdk", 19);
        check(CjsNativeProfile.accepts(nativeFile,"armeabi-v7a",19));
        check(!CjsNativeProfile.accepts(nativeFile,"armeabi-v7a",18));
        check(!CjsNativeProfile.accepts(nativeFile,"arm64-v8a",21));
        boolean rejected=false;
        try { CjsNativeProfile.accepts(new JSONObject(nativeFile.toString()).put("minSdk",14),"armeabi-v7a",19); }
        catch(java.io.IOException expected){rejected=true;}
        check(rejected);
        check(CjsNativeProfile.accepts(new JSONObject().put("name","runtime.json").put("abi","all"),"armeabi-v7a",19));
        String secret="https://example.test/?token=DO_NOT_EXPOSE";
        String tls=VodErrors.json("subscribe",new javax.net.ssl.SSLHandshakeException(secret));
        check(tls.contains("TLS")&&!tls.contains(secret)&&!tls.contains("DO_NOT_EXPOSE"));
        check(VodErrors.json("search",new java.net.UnknownHostException(secret)).contains("DNS"));
        check(VodErrors.json("search",new java.net.SocketTimeoutException(secret)).contains("TIMEOUT"));
        check(VodErrors.json("subscribe",new org.json.JSONException(secret)).contains("JSON"));
        check(!VodErrors.json("subscribe",new java.io.IOException(secret)).contains("DO_NOT_EXPOSE"));
        HlsKeyRegistry<Integer> keys = new HlsKeyRegistry<Integer>();
        for (int i=0;i<715;i++) keys.put("vod"+i, i, true);
        check(keys.containsKey("vod0") && keys.get("vod0") == 0);
        check(keys.get("vod714") == 714);
        for (int i=0;i<1024;i++) keys.put("live"+i, i, false);
        check(!keys.containsKey("live0") && keys.get("live1023") == 1023);
        check(keys.containsKey("vod0"));
        for(int i=715;i<16384;i++) keys.put("vod"+i,i,true);
        boolean full=false;
        try {keys.put("overflow",1,true);}catch(java.io.IOException expected){full=true;}
        check(full && keys.containsKey("vod0"));
        keys.put("vod0",7,true);check(keys.get("vod0")==7);
        System.out.println("PASS compatibility " + checks + " assertions");
    }
}
