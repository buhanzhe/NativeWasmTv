package xiao.bu.tv;

import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Browser-normalized channels; no M3U tokenization or attribute regex on the receiver. */
final class JsonChannelPlaylist {
    private static final Pattern STREAM=Pattern.compile("(?i)(?:https?|rtmps?|rtmpt|rtsp|rtp|udp|tvbus|sop|webview)://[^\\s]+");
    static ChannelCatalog.Group[] parse(JSONArray input,String base)throws IOException,JSONException {
        if(input==null||input.length()==0||input.length()>50000)throw new ControlRequestException(400,"频道 JSON 数量无效");
        Map<String,List<Channel>> groups=new LinkedHashMap<>();int totalAddresses=0;
        for(int i=0;i<input.length();i++) {
            JSONObject row=input.getJSONObject(i);
            String name=row.optString("name","").trim(),group=row.optString("group","在线频道").trim();
            if(name.isEmpty()||name.length()>512||group.length()>512)throw new ControlRequestException(400,"频道 JSON 名称无效");
            if(group.isEmpty())group="在线频道";
            JSONArray addresses=row.optJSONArray("urls");
            if(addresses==null||addresses.length()==0||addresses.length()>10000
                    ||(totalAddresses+=addresses.length())>100000)throw new ControlRequestException(400,"频道 JSON 地址数量无效");
            String[] urls=new String[addresses.length()];
            for(int u=0;u<urls.length;u++) {
                urls[u]=addresses.getString(u).trim();
                if(urls[u].length()>16384||!STREAM.matcher(urls[u]).matches())throw new ControlRequestException(400,"频道 JSON 播放地址无效");
            }
            String epg=row.optString("epgId",name);
            Channel channel=new Channel(PlaylistManager.channelNumber(name,epg,i),name,"json-"+i,urls,null,null,null,epg)
                    .withLogo(row.optString("logoUrl","")).withRadio(row.optBoolean("radio",false));
            JSONArray subtitles=row.optJSONArray("subtitleUrls");StringBuilder sub=new StringBuilder();
            if(subtitles!=null)for(int j=0;j<Math.min(16,subtitles.length());j++){if(j>0)sub.append('\n');sub.append(subtitles.getString(j));}
            channel=channel.withSubtitles(sub.toString());
            JSONArray catchups=row.optJSONArray("catchups");
            if(catchups!=null)for(int u=0;u<Math.min(urls.length,catchups.length());u++) {
                JSONObject item=catchups.optJSONObject(u);if(item==null)continue;
                Map<String,String> attributes=new HashMap<>();
                for(String key:new String[]{"catchup","catchup-source","catchup-days","catchup-correction","timeshift"})
                    if(item.has(key))attributes.put(key,item.optString(key));
                channel=channel.withCatchup(urls[u],IptvCatchup.from("",attributes,base));
            }
            List<Channel> list=groups.get(group);if(list==null){list=new ArrayList<>();groups.put(group,list);}list.add(channel);
        }
        List<ChannelCatalog.Group> result=new ArrayList<>();
        for(Map.Entry<String,List<Channel>> group:groups.entrySet())result.add(new ChannelCatalog.Group(group.getKey(),ChannelCatalog.SOURCE_CUSTOM,group.getValue().toArray(new Channel[0])));
        return result.toArray(new ChannelCatalog.Group[0]);
    }
}
