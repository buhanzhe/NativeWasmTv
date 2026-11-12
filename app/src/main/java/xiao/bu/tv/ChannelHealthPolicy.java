package xiao.bu.tv;

/** A short background probe is advisory; only an entry 404/410 proves absence. */
final class ChannelHealthPolicy {
    private ChannelHealthPolicy() {}
    static long ttl(int status){return status>0?5*60*1000L:status<0?60*1000L:30*1000L;}
    static boolean fresh(int status,long checked,long now){return checked>0&&now>=checked&&now-checked<ttl(status);}
    static int result(boolean ready,boolean audio,boolean video,int width,int height){
        return ready&&(video?width>0&&height>0:audio)?1:0;
    }
    static int entryStatus(int http){return http==404||http==410?-1:0;}
    static boolean unresolved(String url){
        String raw=url.toLowerCase(java.util.Locale.US).split("[?#]",2)[0];
        return raw.endsWith(".cjs")||raw.endsWith(".js")||raw.startsWith("cctv://")||raw.startsWith("yangshipin://");
    }
}
