package xiao.bu.tv;

import android.content.Context;
import android.util.Log;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import org.json.JSONObject;
import okhttp3.MediaType;

/** Generic, bounded syntax adaptation. No host rules and no DOM executed in QuickJS. */
final class LegacyWebTranspilation {
    private static final String MARKER="__ntv_qjs_module=1";
    private static final Pattern ATTRIBUTE=Pattern.compile("([\\w:-]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?");
    private final Context context;
    private final Map<String,String> inline=new ConcurrentHashMap<>();
    private final Set<String> scripts=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
    private volatile String page="",cookies="";
    private long compileMs;
    private int changed,hits,passed,failed;
    private boolean exhausted;
    private int generation;
    private static String modules;
    LegacyWebTranspilation(Context context){this.context=context.getApplicationContext();}
    synchronized void navigate(String url,String cookie){generation++;page=url;cookies=cookie==null?"":cookie;inline.clear();scripts.clear();compileMs=0;changed=hits=passed=failed=0;exhausted=false;}
    void cookies(String url,String value){if(sameOrigin(page,url))cookies=value==null?"":value;}
    synchronized JSONObject stats(){JSONObject out=new JSONObject();try{out.put("changed",changed).put("cacheHits",hits).put("es5",passed).put("failed",failed).put("elapsedMs",compileMs).put("budgetExhausted",exhausted);}catch(Exception ignored){}return out;}
    private JSONObject transform(String source,String url,boolean module)throws Exception{
        final int current;final boolean allowCompile;synchronized(this){current=generation;allowCompile=!exhausted;}
        long begin=android.os.SystemClock.elapsedRealtime();
        try{JSONObject result=LegacyJsTranspiler.get(context).transform(source,url,module,allowCompile);
            synchronized(this){if(current==generation){if(result.optBoolean("cacheHit"))hits++;if(result.optBoolean("changed"))changed++;else passed++;}}
            return result;
        }catch(Exception error){synchronized(this){if(current==generation)failed++;}Log.w("LegacyTranspile","Script fallback: "+error.getClass().getSimpleName());throw error;}
        finally{synchronized(this){if(current==generation){compileMs+=android.os.SystemClock.elapsedRealtime()-begin;if(compileMs>=8000||failed>=2)exhausted=true;}}}
    }
    static synchronized String modulePrelude(Context context){
        if(modules==null)try(InputStream body=context.getAssets().open("legacy-compiler/modules.js")){modules=new String(LegacyJsCompilerService.read(body,64000),"UTF-8");}catch(IOException error){modules="";}
        return "<script>"+modules+"</script>";
    }
    InputStream document(InputStream body,Charset charset,String prelude,String url)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=body.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>1024*1024)throw new IOException("HTML size limit");}
        String html=new String(bytes.toByteArray(),charset);
        String adapted=rewrite(html,url);
        return LegacyDocumentInjector.prepend(new ByteArrayInputStream(adapted.getBytes(charset)),charset,prelude+modulePrelude(context));
    }
    String rewrite(String html,String url){
        String lower=html.toLowerCase(Locale.US),base=url;StringBuilder out=new StringBuilder(html.length());
        int pos=0,index=0;boolean legacyFallback=hasNomodule(html);
        while(pos<html.length()){
            int open=lower.indexOf('<',pos);if(open<0){out.append(html.substring(pos));break;}
            out.append(html.substring(pos,open));
            if(lower.startsWith("<!--",open)){int end=lower.indexOf("-->",open+4);end=end<0?html.length():end+3;out.append(html.substring(open,end));pos=end;continue;}
            int end=tagEnd(html,open+1);if(end<0){out.append(html.substring(open));break;}
            String tag=html.substring(open,end+1);Matcher name=Pattern.compile("^<\\s*([a-zA-Z][\\w:-]*)").matcher(tag);
            if(!name.find()){out.append(tag);pos=end+1;continue;}
            String kind=name.group(1).toLowerCase(Locale.US);
            if("base".equals(kind)){String href=attributes(tag).get("href");if(href!=null)try{base=new URL(new URL(url),decode(href)).toString();}catch(Exception ignored){} }
            if(Arrays.asList("style","textarea","title","noscript").contains(kind)){
                int close=lower.indexOf("</"+kind,end+1),tail=close<0?-1:tagEnd(html,close+2);
                if(tail<0){out.append(html.substring(open));break;}out.append(html.substring(open,tail+1));pos=tail+1;continue;
            }
            if(!"script".equals(kind)){out.append(tag);pos=end+1;continue;}
            int close=lower.indexOf("</script",end+1),tail=close<0?-1:tagEnd(html,close+2);
            if(tail<0){out.append(html.substring(open));break;}
            String original=html.substring(open,tail+1),source=html.substring(end+1,close);
            Map<String,String> attrs=attributes(tag);String type=attrs.containsKey("type")?attrs.get("type").trim().toLowerCase(Locale.US):"";
            if("importmap".equals(type)){out.append(tag.replaceFirst("(?i)(type\\s*=\\s*[\"']?)importmap","$1systemjs-importmap")).append(source).append(html.substring(close,tail+1));pos=tail+1;continue;}
            boolean module="module".equals(type),executable=type.isEmpty()||module||type.contains("javascript")||type.contains("ecmascript");
            if(!executable||(module&&legacyFallback)){out.append(original);pos=tail+1;continue;}
            String src=attrs.get("src"),scriptUrl;
            try{
                if(src!=null){scriptUrl=new URL(new URL(base),decode(src)).toString();scripts.add(scriptUrl);}
                else scriptUrl=new URL(new URL(base),"./__ntv_inline_qjs__"+(index++)+".js").toString();
                if(module){
                    if(src==null)inline.put(scriptUrl,source);
                    out.append("<script>window.__ntvImportModule(").append(JSONObject.quote(scriptUrl).replace("<","\\u003c"))
                            .append(',').append(attrs.containsKey("async")?"true":"false").append(");</script>");
                }else if(src!=null)out.append(original);
                else {JSONObject result=transform(source,scriptUrl,false);String code=result.getString("code");
                    out.append(tag).append(code.replaceAll("(?i)</script","<\\\\/script")).append(html.substring(close,tail+1));}
            }catch(Exception failure){out.append(original);}
            pos=tail+1;
        }
        return out.toString();
    }
    private static int tagEnd(String html,int pos){char quote=0;for(int i=pos;i<html.length();i++){char c=html.charAt(i);if(quote!=0){if(c==quote)quote=0;}else if(c=='\''||c=='"')quote=c;else if(c=='>')return i;}return -1;}
    private static Map<String,String> attributes(String tag){Map<String,String> out=new HashMap<>();Matcher m=ATTRIBUTE.matcher(tag);if(m.find())while(m.find()){String value=m.group(2)!=null?m.group(2):m.group(3)!=null?m.group(3):m.group(4)!=null?m.group(4):"";out.put(m.group(1).toLowerCase(Locale.US),value);}return out;}
    private static boolean hasNomodule(String html){Matcher m=Pattern.compile("(?is)<script\\b[^>]*\\bnomodule(?:\\s|=|>)").matcher(html);return m.find();}
    private static String decode(String value){return value.replace("&amp;","&").replace("&#38;","&").replace("&quot;","\"").replace("&#39;","'");}
    private static String withoutMarker(String url){return url.replaceAll("([?&])__ntv_qjs_module=1(?=&|#|$)","$1").replace("?&","?").replace("&&","&").replaceAll("[?&](?=#|$)","");}
    private static boolean sameOrigin(String a,String b){try{URL x=new URL(a),y=new URL(b);return x.getProtocol().equals(y.getProtocol())&&x.getHost().equalsIgnoreCase(y.getHost())&&(x.getPort()<0?x.getDefaultPort():x.getPort())==(y.getPort()<0?y.getDefaultPort():y.getPort());}catch(Exception error){return false;}}
    WebResourceResponse resource(String requested,String ua){
        if(!LegacyJsTranspiler.enabled()||requested==null)return null;
        boolean module=requested.contains(MARKER);String url=withoutMarker(requested);
        String local=inline.get(url);String path=url.split("[?#]",2)[0].toLowerCase(Locale.US);
        if(local==null&&!module&&!scripts.contains(url)&&!path.endsWith(".js")&&!path.endsWith(".mjs"))return null;
        HttpURLConnection connection=null;
        try{
            String source=local;Charset charset=Charset.forName("UTF-8");
            if(source==null){URL target=new URL(url);long deadline=System.nanoTime()+8000000000L;for(int hop=0;hop<5;hop++){
                if(!"http".equals(target.getProtocol())&&!"https".equals(target.getProtocol()))return null;
                int remaining=(int)((deadline-System.nanoTime())/1000000L);if(remaining<=0)return null;
                connection=NetworkClient.openBounded(target,remaining);connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent",ua);connection.setRequestProperty("Referer",page);
                if(sameOrigin(page,target.toString())&&!cookies.isEmpty())connection.setRequestProperty("Cookie",cookies);
                int status=connection.getResponseCode();if(status>=300&&status<400){String redirect=connection.getHeaderField("Location");if(redirect==null)return null;target=new URL(target,redirect);connection.disconnect();connection=null;continue;}
                if(status!=200)return null;
                if(connection.getContentLength()>LegacyJsTranspiler.MAX_SOURCE)return null;
                if(module&&!sameOrigin(page,target.toString())){String allow=connection.getHeaderField("Access-Control-Allow-Origin");URL origin=new URL(page);String expected=origin.getProtocol()+"://"+origin.getAuthority();if(!"*".equals(allow)&&!expected.equals(allow))return null;}
                MediaType mime=MediaType.parse(connection.getContentType()==null?"":connection.getContentType());
                if(mime==null||!(mime.subtype().contains("javascript")||mime.subtype().contains("ecmascript")))return null;
                charset=mime.charset(charset);
                InputStream body=connection.getInputStream();try{
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
                    while((n=body.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>LegacyJsTranspiler.MAX_SOURCE)return null;}source=new String(bytes.toByteArray(),charset);
                }finally{if(connection!=null)body.close();}break;
            }}
            if(source==null)return null;
            String output=source;try{output=transform(source,url,module).getString("code");}catch(Exception fallback){}
            return new WebResourceResponse("application/javascript","UTF-8",new ByteArrayInputStream(output.getBytes("UTF-8")));
        }catch(Exception error){Log.w("LegacyTranspile","Resource fallback: "+error.getClass().getSimpleName());return null;}
        finally{if(connection!=null)connection.disconnect();}
    }
}
