package xiao.bu.tv;

import okhttp3.HttpUrl;

/** Document and stream must both identify the station before changing transport. */
final class LiteLiveMediaPolicy {
  static boolean legacySnrtvHls(int sdk,String page,String source){
    HttpUrl owner=page==null?null:HttpUrl.parse(page),media=source==null?null:HttpUrl.parse(source);
    return sdk==19&&owner!=null&&owner.host().equals("live.snrtv.com")&&owner.encodedPath().equals("/5")
        &&media!=null&&media.host().equals("stream.snrtv.com")&&media.encodedPath().equals("/sxbc-5-DJSWX2.m3u8");
  }
  static String guangdianChannel(String url){
    HttpUrl page=url==null?null:HttpUrl.parse(url);
    if(page==null||!page.host().equals("web.guangdianyun.tv"))return null;
    String path=page.encodedPath(),id=null;
    if(path.matches("/tv/[0-9]+/?"))id=path.substring(4).replace("/","");
    else if((path.equals("/tv/")||path.equals("/tv"))&&page.queryParameterValues("id").size()==1)id=page.queryParameter("id");
    return id!=null&&id.matches("[0-9]+")?id:null;
  }
  static boolean legacyGuangdianPage(int sdk,String page){return sdk==19&&guangdianChannel(page)!=null;}
  static boolean legacyCctvNewsPage(int sdk,String value){
    HttpUrl page=value==null?null:HttpUrl.parse(value);
    String room=page==null?null:page.queryParameter("liveRoomNumber");
    return sdk==19&&page!=null&&page.isHttps()&&page.host().equals("m-live.cctvnews.cctv.com")
        &&page.encodedPath().equals("/live/landscape.html")&&page.queryParameterValues("liveRoomNumber").size()==1
        &&room!=null&&room.matches("[0-9]{1,24}");
  }
  static boolean legacyCctvNewsHls(int sdk,String page,String value){
    HttpUrl media=value==null?null:HttpUrl.parse(value);
    return legacyCctvNewsPage(sdk,page)&&media!=null&&media.isHttps()
        &&media.host().equals("live-play-hls.cctvnews.cctv.com")
        &&media.encodedPath().matches("/CCTVChannel/channel_[A-Za-z0-9_-]+\\.m3u8");
  }
  static boolean legacyGuangdianHls(int sdk,String page,String raw){
    if(!legacyGuangdianPage(sdk,page))return false;
    HttpUrl media=raw==null?null:HttpUrl.parse(raw);
    return media!=null&&media.host().matches("[0-9]+-live\\.hlsplay\\.aodianyun\\.com")
        &&media.encodedPath().matches("/tv_radio_[0-9]+/tv_channel_"+guangdianChannel(page)+"\\.m3u8");
  }
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
