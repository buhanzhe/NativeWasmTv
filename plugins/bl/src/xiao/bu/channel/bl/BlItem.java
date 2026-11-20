package xiao.bu.channel.bl;

import org.json.*;

/** A small UI projection; preserve API identifiers without copying complete response trees. */
final class BlItem {
    final JSONObject raw;final String kind,title,picture,owner,id;final long count,duration,danmakuCount;
    BlItem(JSONObject v,String expected){
        raw=v;String type=expected;
        if(expected.equals("dynamic")){JSONObject modules=v.optJSONObject("modules"),module=modules==null?null:modules.optJSONObject("module_dynamic"),major=module==null?null:module.optJSONObject("major"),archive=major==null?null:major.optJSONObject("archive");if(archive!=null)v=archive;type="video";}
        if(v.optLong("roomid")>0||v.optLong("room_id")>0)type="live";
        if(expected.equals("folder"))type="folder";
        if((expected.equals("user")||expected.equals("bili_user")))type="user";
        if(expected.equals("season")||expected.equals("media_bangumi"))type="season";
        kind=type;
        String bvid=v.optString("bvid");JSONObject history=v.optJSONObject("history");if(bvid.isEmpty()&&history!=null)bvid=history.optString("bvid");
        id=type.equals("folder")?v.optString("id"):type.equals("user")?v.optString("mid"):type.equals("season")?v.optString("season_id"):type.equals("live")?v.optString("roomid",v.optString("room_id")):bvid.isEmpty()?v.optString("aid",v.optString("id")):bvid;
        title=BlUi.plain(v.optString("title",v.optString("uname",v.optString("name","视频"))));
        picture=v.optString("pic",v.optString("cover",v.optString("face",v.optString("user_cover",v.optString("keyframe","")))));
        JSONObject author=v.optJSONObject("owner");if(author==null)author=v.optJSONObject("author");owner=author==null?v.optString("author",v.optString("uname",v.optString("upper_name",v.optString("author_name","")))):author.optString("name",author.optString("uname"));
        JSONObject stat=v.optJSONObject("stat");count=stat==null?v.optLong("play",v.optLong("cnt",v.optLong("online",v.optLong("media_count")))):stat.optLong("view");
        danmakuCount=stat==null?v.optLong("video_review",v.optLong("danmaku",-1)):stat.optLong("danmaku",-1);
        Object durationValue=v.opt("duration");long d=0;if(durationValue instanceof Number)d=((Number)durationValue).longValue()*1000;else if(durationValue instanceof String){String[] parts=((String)durationValue).split(":");for(String p:parts)try{d=d*60+Long.parseLong(p);}catch(Exception ignored){}d*=1000;}duration=d;
    }
}
