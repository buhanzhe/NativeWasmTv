package xiao.bu.tv;

import android.webkit.WebResourceResponse;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.json.JSONObject;
import android.util.Base64;
import java.util.regex.*;

/** Small local shells using the TV sites' current public playback responses. */
final class LegacyTvSiteAdapter {
    static boolean supports(String url) {
        if (!LegacyWebCompatibility.enabled() || url == null) return false;
        try {
            URI uri=URI.create(url);
            return ("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))
                    && (("www.cztv.com".equalsIgnoreCase(uri.getHost())&&uri.getPath().matches("/liveTV(?:/\\d+)?/?"))
                    ||("v.iqilu.com".equalsIgnoreCase(uri.getHost())&&uri.getPath().matches("/live/[a-z0-9]+/?"))
                    ||("www.gzstv.com".equalsIgnoreCase(uri.getHost())&&uri.getPath().matches("/tv/ch(?:0[1-6]|13)/?"))
                    ||("www.hebtv.com".equalsIgnoreCase(uri.getHost())&&"/19/19js/st/xdszb/index.shtml".equals(uri.getPath()))
                    ||("web.guangdianyun.tv".equalsIgnoreCase(uri.getHost())&&uri.getPath().matches("/tv/\\d+/?")&&uin(uri)!=null));
        } catch (IllegalArgumentException error) { return false; }
    }
    static WebResourceResponse page(String url,String prelude) {
        if (!supports(url) || prelude.isEmpty()) return null;
        return text("text/html","<!doctype html><html><head><meta charset=\"utf-8\">"
                +"<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                +"<title>电视直播</title><script>window.__ntvLegacySiteShell=true;</script>"
                +prelude+"</head><body style=\"margin:0;background:black\"></body></html>");
    }
    static WebResourceResponse resource(String document,String url,String userAgent) {
        if (!supports(document) || url == null) return null;
        try {
            URI target=URI.create(url),page=URI.create(document);
            if (!page.getScheme().equals(target.getScheme())||!page.getAuthority().equals(target.getAuthority())
                    )return null;
            if("v.iqilu.com".equals(page.getHost())&&"/__ntv_kitkat__/iqilu-play-info".equals(target.getPath())&&target.getRawQuery()==null)
                return text("application/json",iqilu(document,userAgent));
            if("www.gzstv.com".equals(page.getHost())&&"/__ntv_kitkat__/guizhou-play-info".equals(target.getPath())&&target.getRawQuery()==null)
                return text("application/json",guizhou(page,userAgent));
            if("www.hebtv.com".equals(page.getHost())&&"/__ntv_kitkat__/hebei-play-info".equals(target.getPath())&&target.getRawQuery()==null)
                return text("application/json",hebei(document,userAgent));
            if("web.guangdianyun.tv".equals(page.getHost())&&"/__ntv_kitkat__/gdy-play-info".equals(target.getPath())&&target.getRawQuery()==null)
                return text("application/json",guangdianyun(page,userAgent));
            if(!"www.cztv.com".equals(page.getHost())||!"/__ntv_kitkat__/cztv-play-info".equals(target.getPath())
                    ||target.getRawQuery()==null||!target.getRawQuery().matches("id=\\d{1,6}"))return null;
            return text("application/json",cztv(target.getRawQuery().substring(3)));
        } catch (Exception error) {
            android.util.Log.w("LegacyTvAdapter", "Playback API failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return text("application/json","{\"success\":false,\"message\":\"直播接口暂时不可用\"}");
        }
    }
    private static String cztv(String id)throws Exception {
        long deadline=System.nanoTime()+12000000000L;
        for(int attempt=0;attempt<2;attempt++){
            int remaining=(int)((deadline-System.nanoTime())/1000000L);
            if(remaining<=0)throw new IOException("Live API timeout");
            // Retry an idempotent GET once on transport failure, within one budget.
            HttpURLConnection connection=NetworkClient.openBounded(new URL("https://zlive-das.cztv.com/zapp/live/tv/channel/playInfo?channelId="+id+"&platform=WEB"),attempt==0?Math.min(5000,remaining):remaining);
            try{
                connection.setRequestMethod("GET");connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Referer","https://www.cztv.com/");
                int status=connection.getResponseCode();
                if(status!=200){
                    if(attempt==0&&status>=500)continue;
                    return "{\"success\":false,\"message\":\"直播接口 HTTP "+status+"\"}";
                }
                JSONObject info=new JSONObject(read(connection,262144));
                if(!info.optBoolean("success")||info.optJSONObject("data")==null)return info.toString();
                // Old boxes can have an incorrect clock; use the API's HTTP Date.
                long serverTime=connection.getDate();if(serverTime>0)info.put("_ntvServerTimeMs",serverTime);
                return info.toString();
            }catch(IOException error){if(attempt!=0)throw error;}
            finally{connection.disconnect();}
        }
        throw new IOException("Live API unavailable");
    }
    private static String guizhou(URI page,String userAgent)throws Exception {
        // Same public metadata call as the official Nuxt TV component. An empty
        // remote_address is its client-navigation value; never spoof a viewer IP.
        String channel=page.getPath().split("/")[2];
        if(!channel.matches("ch(?:0[1-6]|13)"))throw new IOException("Channel unavailable");
        HttpURLConnection connection=NetworkClient.openBounded(new URL(
                "https://api.gzstv.com/v1/tv/"+channel+"/?remote_address=&fields=description,title,stream_url,image,author"),10000);
        try {
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent",userAgent);connection.setRequestProperty("Referer",page.toString());
            connection.setRequestProperty("Origin","https://www.gzstv.com");
            // The metadata CDN has served already-expired URLs. Request normal
            // revalidation once; preserve returned signatures and any denial.
            connection.setUseCaches(false);connection.setRequestProperty("Cache-Control","no-cache");
            connection.setRequestProperty("Pragma","no-cache");
            JSONObject info=new JSONObject(read(connection,262144));
            long serverTime=connection.getDate();if(serverTime>0)info.put("_ntvServerTimeMs",serverTime);
            return info.toString();
        } finally {connection.disconnect();}
    }
    private static String hebei(String document,String userAgent)throws Exception {
        // The official page retrieves its seven-channel catalog with an empty POST.
        // Keep this fixed public endpoint; never accept an arbitrary proxy target.
        HttpURLConnection connection=NetworkClient.openBounded(new URL("https://api.cmc.hebrts.cn/cmsback/api/com/article/getArticleList?catalogId=32557&siteId=1"),10000);
        try {
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");
            connection.setRequestProperty("User-Agent",userAgent);connection.setRequestProperty("Referer",document);
            connection.setRequestProperty("Origin","https://www.hebtv.com");
            connection.setDoOutput(true);connection.setFixedLengthStreamingMode(0);
            connection.getOutputStream().close();
            JSONObject info=new JSONObject(read(connection,262144));
            // Propagate denial/changed API responses. No alternate private playback URL.
            long serverTime=connection.getDate();if(serverTime>0)info.put("_ntvServerTimeMs",serverTime);
            return info.toString();
        } finally {connection.disconnect();}
    }
    private static String uin(URI uri){
        Matcher value=Pattern.compile("(?:^|&)uin=(\\d{1,8})(?:&|$)").matcher(uri.getRawQuery()==null?"":uri.getRawQuery());
        return value.find()?value.group(1):null;
    }
    private static JSONObject publicJson(String url,String referer,String userAgent,long deadline)throws Exception{
        int remaining=(int)((deadline-System.nanoTime())/1000000L);if(remaining<=0)throw new IOException("Site timeout");
        HttpURLConnection c=NetworkClient.openBounded(new URL(url),remaining);
        try{c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent",userAgent);c.setRequestProperty("Referer",referer);
            c.setRequestProperty("Origin","https://web.guangdianyun.tv");return new JSONObject(read(c,262144));}
        finally{c.disconnect();}
    }
    private static String guangdianyun(URI page,String userAgent)throws Exception{
        String id=page.getPath().replaceAll("[^0-9]",""),tenant=uin(page);
        if(!id.matches("\\d{1,8}")||tenant==null)throw new IOException("Channel unavailable");
        long deadline=System.nanoTime()+10000000000L;
        String base="https://1812501212048408.cn-hangzhou.fc.aliyuncs.com/2016-08-15/proxy/node-api.online/node-api/tv/";
        String query="?id="+id+"&uin="+tenant;
        JSONObject info=publicJson(base+"channelInfo"+query,page.toString(),userAgent,deadline);
        JSONObject data=info.optJSONObject("data");
        if(info.optInt("code")!=200||info.optInt("errorCode",-1)!=0||data==null||data.optInt("status")!=1
                ||!tenant.equals(data.optString("uin")))throw new IOException("Channel unavailable");
        JSONObject play=publicJson(base+"getPlayAddress"+query+"&clientId="+java.util.UUID.randomUUID(),page.toString(),userAgent,deadline);
        // Never substitute pullUrl when the public playback endpoint denies access.
        if(play.optInt("code")!=200||play.optInt("errorCode",-1)!=0||play.optJSONObject("data")==null)throw new IOException("Playback unavailable");
        String stream=play.getJSONObject("data").optString("hlsUrl");URI url=URI.create(stream);
        if(url.getHost()==null||!("https".equals(url.getScheme())||"http".equals(url.getScheme())))throw new IOException("No playable stream");
        return new JSONObject().put("success",true).put("data",new JSONObject().put("url",stream).put("channelName",data.optString("name","直播"))).toString();
    }
    private static String read(HttpURLConnection connection,int limit)throws IOException {
        if(connection.getResponseCode()!=200)throw new IOException("Site API HTTP "+connection.getResponseCode()+" host="+connection.getURL().getHost());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;
        try(InputStream body=connection.getInputStream()){
            while((n=body.read(block))!=-1){if(bytes.size()+n>limit)throw new IOException("Site response too large");bytes.write(block,0,n);}
        }
        return bytes.toString("UTF-8");
    }
    private static String variable(String html,String name)throws IOException {
        Matcher m=Pattern.compile("\\bvar\\s+"+Pattern.quote(name)+"\\s*=\\s*['\"]([^'\"]{1,160})['\"]").matcher(html);
        if(!m.find())throw new IOException("Missing site configuration");return m.group(1);
    }
    private static String iqilu(String document,String userAgent)throws Exception {
        long deadline=System.nanoTime()+10000000000L;
        HttpURLConnection connection=NetworkClient.openBounded(new URL(document),10000);String html;
        try{connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent",userAgent);html=read(connection,131072);}finally{connection.disconnect();}
        android.util.Log.i("LegacyTvAdapter","IQilu current page config fetched");
        String id=variable(html,"_pdCid"),key=variable(html,"aly"),secret=variable(html,"mxpx");
        if(!id.matches("\\d{1,12}")||!key.matches("[A-Z]{16}")||!secret.matches("[A-Z]{16}"))throw new IOException("Site config changed");
        // Read current configuration from the page; honor the site's exchange response.
        if(!"https://feiying.litenews.cn/api/".equals(variable(html,"dF"))||!"v1/auth/exchange".equals(variable(html,"aF")))throw new IOException("Site API changed");
        String timestamp=Long.toString(System.currentTimeMillis()),sign=hex(MessageDigest.getInstance("MD5").digest((id+timestamp+secret).getBytes("UTF-8")));
        SecretKeySpec aes=new SecretKeySpec(key.getBytes("UTF-8"),"AES");IvParameterSpec iv=new IvParameterSpec("0000000000000000".getBytes("UTF-8"));
        Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.ENCRYPT_MODE,aes,iv);
        byte[] payload=Base64.encode(cipher.doFinal(new JSONObject().put("channelMark",id).toString().getBytes("UTF-8")),Base64.NO_WRAP);
        int remaining=(int)((deadline-System.nanoTime())/1000000L);if(remaining<=0)throw new IOException("Site timeout");
        connection=NetworkClient.openBounded(new URL("https://feiying.litenews.cn/api/v1/auth/exchange?t="+timestamp+"&s="+sign),remaining);
        android.util.Log.i("LegacyTvAdapter","IQilu playback exchange requested");
        String raw;
        try{
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","text/plain");connection.setRequestProperty("Referer",document);
            connection.setRequestProperty("User-Agent",userAgent);connection.setRequestProperty("Origin","https://v.iqilu.com");
            try(OutputStream body=connection.getOutputStream()){body.write(payload);}
            raw=read(connection,65536);
        }finally{connection.disconnect();}
        cipher.init(Cipher.DECRYPT_MODE,aes,iv);
        JSONObject value=new JSONObject(new String(cipher.doFinal(Base64.decode(raw,Base64.DEFAULT)),"UTF-8"));
        String stream=value.optString("data","");URI uri=URI.create(stream);
        if(uri.getHost()==null||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())))throw new IOException("Live stream unavailable");
        return new JSONObject().put("success",true).put("data",new JSONObject().put("url",stream).put("channelName",variable(html,"_pdName"))).toString();
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append("0123456789abcdef".charAt((b>>4)&15)).append("0123456789abcdef".charAt(b&15));return out.toString();}
    private static WebResourceResponse text(String mime,String value) {
        try {return new WebResourceResponse(mime,"UTF-8",new ByteArrayInputStream(value.getBytes("UTF-8")));}
        catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
    private LegacyTvSiteAdapter() { }
}
