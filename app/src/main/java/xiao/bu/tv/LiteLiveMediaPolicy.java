package xiao.bu.tv;

import okhttp3.HttpUrl;

/** Document and stream must both identify the station before changing transport. */
final class LiteLiveMediaPolicy {
  static boolean selectedLivePage(String url){
    HttpUrl page=url==null?null:HttpUrl.parse(url);if(page==null)return false;
    return (page.host().equals("www.gdtv.cn")||page.host().equals("m.gdtv.cn"))&&page.encodedPath().matches("/tvChannelDetail/[0-9]+/?")
        ||page.host().equals("www.cztv.com")&&page.encodedPath().matches("/liveTV/[0-9]+/?");
  }
  static boolean preferHls(String pageUrl,String mediaUrl) {
    HttpUrl page=pageUrl==null?null:HttpUrl.parse(pageUrl);
    HttpUrl media=mediaUrl==null?null:HttpUrl.parse(mediaUrl);
    if(page==null||media==null)return false;
    // This station's clear live manifests are already selected by its player.
    // Consume that actual response with the native live reader; an interrupted
    // old TS request must not hold the page's MSE queue at the old live edge.
    if(page.host().equals("www.hsnewsnet.com")&&(page.encodedPath().equals("/web/tvnews/")||page.encodedPath().equals("/web/tvlife/"))
        &&media.host().equals("hslive.hsnewsnet.com")&&media.encodedPath().matches("/lsdream/[A-Za-z0-9]+/[0-9]+/live\\.m3u8"))return true;
    return page.host().equals("live.jstv.com")&&"676".equals(page.queryParameter("channelId"))
        &&media.host().equals("litchi-play-encrypted-site.jstv.com")
        &&media.encodedPath().equals("/4klive/jsws4kpro.m3u8");
  }
  private LiteLiveMediaPolicy() { }
}
