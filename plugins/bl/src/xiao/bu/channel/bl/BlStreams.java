package xiao.bu.channel.bl;

import org.json.*;
import java.util.*;
import okhttp3.HttpUrl;

/** Normalize progressive segments or independently muxed DASH video/audio for the player. */
final class BlStreams {
    static final int QUALITY_1080P=80,QUALITY_HDR=125,QUALITY_8K=127;
    static final int AUDIO_192K=30280,AUDIO_ATMOS=30250,AUDIO_HI_RES=30251,AUDIO_DOLBY=30255;
    enum Track{VIDEO,AUDIO,UNKNOWN}

    static final class AudioTrack{
        final JSONObject data;
        final int id;
        final String format;

        AudioTrack(JSONObject data,String format){this.data=data;this.id=data.optInt("id");this.format=format;}
        boolean aac(){return format.equals("AAC");}
        String label(){return aac()?"AAC · "+Math.round(data.optDouble("bandwidth",0)/1000)+" kbps":format;}
    }

    static List<AudioTrack> audioTracks(JSONObject data){
        JSONObject dash=data==null?null:data.optJSONObject("dash");
        if(dash==null)return Collections.emptyList();
        Map<Integer,AudioTrack> tracks=new LinkedHashMap<>();
        addAudioTracks(tracks,dash,"AAC");
        addAudioTracks(tracks,dash.optJSONObject("dolby"),"杜比音频");
        addAudioTracks(tracks,dash.optJSONObject("flac"),"Hi-Res");
        return new ArrayList<>(tracks.values());
    }

    private static void addAudioTracks(Map<Integer,AudioTrack> tracks,JSONObject group,String format){
        if(group==null)return;
        addAudioTrack(tracks,group.optJSONObject("audio"),format);
        JSONArray audio=BlApi.array(group,"audio");
        for(int i=0;i<audio.length();i++)addAudioTrack(tracks,audio.optJSONObject(i),format);
    }

    private static void addAudioTrack(Map<Integer,AudioTrack> tracks,JSONObject data,String group){
        if(url(data).isEmpty())return;
        int id=data.optInt("id");
        if(id<=0||tracks.containsKey(id))return;
        String format=premiumAudioLabel(id);
        if(format.isEmpty()){
            String codecs=data.optString("codecs");
            if(group.equals("AAC")&&!codecs.isEmpty()&&!codecs.startsWith("mp4a"))return;
            format=group;
        }
        tracks.put(id,new AudioTrack(data,format));
    }

    static String premiumAudioLabel(int id){
        switch(id){
            case AUDIO_ATMOS:return "杜比全景声";
            case AUDIO_HI_RES:return "Hi-Res";
            case AUDIO_DOLBY:return "杜比音频";
            default:return "";
        }
    }

    static List<Integer> qualities(JSONObject data){
        JSONArray videos=BlApi.array(data==null?null:data.optJSONObject("dash"),"video");
        SortedSet<Integer> result=new TreeSet<>(Collections.reverseOrder());
        for(int i=0;i<videos.length();i++){
            JSONObject track=videos.optJSONObject(i);
            if(!url(track).isEmpty()&&track.optInt("id")>0)result.add(track.optInt("id"));
        }
        return new ArrayList<>(result);
    }

    static JSONArray select(JSONObject data,int quality,String codec,int audioId)throws JSONException{
        JSONObject dash=data.optJSONObject("dash");
        if(dash!=null){
            JSONObject video=selectVideo(dash,quality,codec,false,Collections.<String>emptySet());
            AudioTrack audio=selectAudio(audioTracks(data),audioId,false,Collections.<String>emptySet());
            if(video!=null&&audio!=null)return merge(data,dash,video,audio);
        }
        return BlApi.array(data,"durl");
    }

    static JSONArray compatibleDash(JSONObject data,JSONObject failed,Track track,Set<String> rejected)throws JSONException{
        JSONObject dash=data.optJSONObject("dash");
        if(dash==null)return new JSONArray();
        int quality=failed.optInt("bl_quality");
        String codec=videoCodec(failed.optString("bl_codec"),failed.optInt("bl_codecid"));
        boolean advanced=quality>=QUALITY_HDR||codec.equals("AV1")||codec.equals("Dolby Vision");
        boolean replaceAudio=track==Track.AUDIO||track==Track.UNKNOWN&&failed.optBoolean("bl_premium_audio");
        if(!replaceAudio&&track==Track.UNKNOWN&&!advanced)return new JSONArray();

        JSONObject video;
        AudioTrack audio;
        List<AudioTrack> tracks=audioTracks(data);
        if(replaceAudio){
            rejected.add(failed.optString("bl_audio"));
            video=findVideo(dash,failed.optString("url"));
            audio=selectAudio(tracks,AUDIO_192K,true,rejected);
        }else{
            rejected.add(failed.optString("url"));
            video=selectVideo(dash,advanced?QUALITY_1080P:quality,"AVC",true,rejected);
            audio=selectAudio(tracks,failed.optInt("bl_audio_id"),false,rejected);
        }
        if(video==null||audio==null)return new JSONArray();
        return merge(data,dash,video,audio);
    }

    private static JSONObject findVideo(JSONObject dash,String selected){
        JSONArray videos=BlApi.array(dash,"video");
        for(int i=0;i<videos.length();i++){
            JSONObject video=videos.optJSONObject(i);
            if(selected.equals(url(video)))return video;
        }
        return null;
    }

    private static JSONObject selectVideo(JSONObject dash,int quality,String codec,boolean compatible,Set<String> rejected){
        JSONArray videos=BlApi.array(dash,"video");
        JSONObject chosen=null;
        int score=Integer.MAX_VALUE;
        for(int i=0;i<videos.length();i++){
            JSONObject video=videos.optJSONObject(i);
            String address=url(video);
            if(address.isEmpty()||rejected.contains(address))continue;
            int q=video.optInt("id");
            String format=videoCodec(video.optString("codecs"),video.optInt("codecid"));
            if(q<=0||compatible&&(q>quality||!format.equals("AVC")&&!format.equals("HEVC")))continue;
            int distance=q<=quality?quality-q:1000+q-quality;
            int preference=format.equals(codec)?0:format.equals("AVC")?1:format.equals("HEVC")?2:3;
            int cost=4*distance+preference;
            if(cost<score||cost==score&&video.optLong("bandwidth")>chosen.optLong("bandwidth")){score=cost;chosen=video;}
        }
        return chosen;
    }

    private static String videoCodec(String codecs,int id){
        if(codecs.startsWith("dv"))return "Dolby Vision";
        if(codecs.startsWith("av01")||id==13)return "AV1";
        if(codecs.startsWith("hev")||codecs.startsWith("hvc")||id==12)return "HEVC";
        if(codecs.startsWith("avc")||id==7||codecs.isEmpty())return "AVC";
        return codecs;
    }

    private static AudioTrack selectAudio(List<AudioTrack> tracks,int audioId,boolean aacOnly,Set<String> rejected){
        AudioTrack aac=null,other=null;
        for(AudioTrack track:tracks){
            if(aacOnly&&!track.aac()||rejected.contains(url(track.data)))continue;
            if(track.id==audioId)return track;
            if(track.aac()){
                if(aac==null||track.data.optInt("bandwidth")>aac.data.optInt("bandwidth"))aac=track;
            }else if(other==null)other=track;
        }
        return aac==null?other:aac;
    }

    private static JSONArray merge(JSONObject data,JSONObject dash,JSONObject video,AudioTrack audio)throws JSONException{
        JSONObject item=new JSONObject()
                .put("url",url(video)).put("bl_audio",url(audio.data))
                .put("length",(long)(dash.optDouble("duration",data.optDouble("timelength",0)/1000)*1000))
                .put("bl_quality",video.optInt("id")).put("bl_codec",video.optString("codecs"))
                .put("bl_codecid",video.optInt("codecid")).put("bl_audio_id",audio.id)
                .put("bl_audio_label",audio.label()).put("bl_premium_audio",!audio.aac());
        item.put("backup_url",backups(video)).put("bl_audio_backups",backups(audio.data));
        return new JSONArray().put(item);
    }

    private static JSONArray backups(JSONObject track){
        Iterator<String> urls=mediaUrls(track).iterator();
        if(urls.hasNext())urls.next();
        JSONArray result=new JSONArray();
        while(urls.hasNext())result.put(urls.next());
        return result;
    }

    private static String url(JSONObject track){
        if(track==null)return "";
        Iterator<String> urls=mediaUrls(track).iterator();
        return urls.hasNext()?urls.next():"";
    }

    private static Set<String> mediaUrls(JSONObject track){
        Set<String> urls=new LinkedHashSet<>();
        for(String key:new String[]{"baseUrl","base_url","url"}){
            if(!track.isNull(key))addMediaUrl(urls,track.optString(key));
        }
        for(String key:new String[]{"backupUrl","backup_url"}){
            JSONArray backups=BlApi.array(track,key);
            for(int i=0;i<backups.length();i++)addMediaUrl(urls,backups.optString(i));
        }
        return urls;
    }

    private static void addMediaUrl(Set<String> urls,String value){
        HttpUrl url=HttpUrl.parse(value.startsWith("//")?"https:"+value:value);
        if(url!=null&&url.username().isEmpty()&&url.password().isEmpty())urls.add(url.toString());
    }
}
