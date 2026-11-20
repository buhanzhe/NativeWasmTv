package xiao.bu.channel.bl;

import android.os.Build;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;
import okhttp3.*;

/** Bounded, credential-free diagnostics shared by API, media and the single-View UI. */
final class BlDiagnostics {
    static final int VERSION=14;
    static class Failure extends IOException {
        final String kind,endpoint,requestId,contentType;final int http,apiCode;
        Failure(String kind,String message,Request request,int http,int apiCode,String requestId,String contentType,Throwable cause){
            super(clean(message),cause);this.kind=kind;this.endpoint=request==null?"":request.method()+" "+safeUrl(request.url().toString());
            this.http=http;this.apiCode=apiCode;this.requestId=clean(requestId);this.contentType=clean(contentType);
        }
    }
    static final class Entry {
        final String id,summary,details;final long at;
        Entry(String id,String summary,String details,long at){this.id=id;this.summary=summary;this.details=details;this.at=at;}
    }
    private final ArrayDeque<Entry> entries=new ArrayDeque<>();private int sequence;
    static Failure response(Response response){return new Failure("HTTP","服务响应 HTTP "+response.code(),response.request(),response.code(),0,response.header("X-Bili-Trace-Id",""),response.header("Content-Type",""),null);}
    synchronized Entry record(String stage,Throwable error,String context){
        long now=System.currentTimeMillis();String id=String.format(Locale.US,"BL-%04d",++sequence);
        String message=error==null?"未知错误":error.getMessage();if(message==null||message.trim().isEmpty())message=error==null?"未知错误":error.getClass().getSimpleName();
        String summary=shorten(clean(message),180)+" ["+id+"]";
        StringBuilder details=new StringBuilder("错误编号：").append(id).append("\n时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date(now)))
                .append("\n插件：BL v").append(VERSION).append(" · Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append(" · ").append(Build.CPU_ABI)
                .append("\n阶段：").append(clean(stage)).append("\n").append(summary).append('\n');
        if(context!=null&&!context.isEmpty())details.append("上下文：").append(clean(context)).append('\n');
        Throwable current=error;Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(int depth=0;current!=null&&depth<6&&seen.add(current);depth++,current=current.getCause()){
            details.append(depth==0?"异常：":"原因：").append(current.getClass().getSimpleName()).append(": ").append(clean(current.getMessage())).append('\n');
            if(current instanceof Failure){Failure f=(Failure)current;details.append("类型：").append(f.kind).append('\n');if(!f.endpoint.isEmpty())details.append("接口：").append(f.endpoint).append('\n');if(f.http!=0)details.append("HTTP：").append(f.http).append('\n');if(f.apiCode!=0)details.append("业务码：").append(f.apiCode).append('\n');if(!f.requestId.isEmpty())details.append("服务追踪：").append(f.requestId).append('\n');if(!f.contentType.isEmpty())details.append("响应类型：").append(f.contentType).append('\n');}
            StackTraceElement[] stack=current.getStackTrace();for(int i=0;i<Math.min(6,stack.length);i++)details.append("  at ").append(stack[i].toString()).append('\n');
        }
        details.append("建议：").append(advice(error)).append("\n地址参数、Cookie、签名及令牌不包含在此记录中。");
        Entry entry=new Entry(id,summary,shorten(clean(details.toString()),8000),now);entries.addFirst(entry);while(entries.size()>20)entries.removeLast();
        for(int i=0;i<entry.details.length();i+=1800)android.util.Log.w("BL-Diagnostics",entry.details.substring(i,Math.min(entry.details.length(),i+1800)));
        return entry;
    }
    synchronized List<Entry> snapshot(){return new ArrayList<>(entries);}
    synchronized Entry latest(){return entries.peekFirst();}
    static String advice(Throwable error){
        for(int i=0;error!=null&&i<6;i++,error=error.getCause()){
            if(error instanceof Failure){Failure f=(Failure)error;if(f.apiCode==-101)return "请重新扫码登录后重试。";if(f.apiCode==-352||f.apiCode==-412||f.http==412||f.http==429)return "站点验证或限流，请稍后再试，必要时登录；不要连续刷新。";if(f.http==403)return "媒体地址可能已过期或站点拒绝访问，可重新获取播放地址或切换线路/画质。";if(f.http==404)return "接口或媒体不存在，可刷新列表或重新选择内容。";if(f.http>=500)return "服务暂不可用，请稍后重试。";}
            if(error instanceof java.net.UnknownHostException)return "域名解析失败，请检查网络与 DNS。";
            if(error instanceof java.net.SocketTimeoutException)return "连接或读取超时，请检查网络，或尝试其他线路。";
            if(error instanceof javax.net.ssl.SSLException)return "TLS 握手或证书验证失败，请检查设备时间、网络和系统证书。";
            if(error instanceof LinkageError)return "宿主与插件接口或本机库不匹配，请更新匹配设备架构的宿主。";
        }
        return "请保留错误编号与详情；可刷新当前页面或切换画质/线路后重试。";
    }
    static String safeUrl(String value){
        try{HttpUrl url=HttpUrl.parse(value);if(url==null)return "[无效地址]";String path=url.encodedPath().replaceAll("(?i)/[0-9a-f]{8}-[0-9a-f-]{27,}(?=/|$)","/local-token-redacted");return url.scheme()+"://"+url.host()+((url.scheme().equals("https")&&url.port()==443||url.scheme().equals("http")&&url.port()==80)?"":":"+url.port())+path;}catch(Exception e){return "[无效地址]";}
    }
    static String clean(String value){
        if(value==null)return "";value=shorten(value,16000).replaceAll("(?im)((?:authorization|proxy-authorization|cookie|set-cookie)[\\\"']?\\s*[:=]\\s*)[^\\r\\n]+","$1[已隐藏]");
        Matcher urls=Pattern.compile("https?://[^\\s\\\"'<>\\]\\)]+").matcher(value);StringBuffer output=new StringBuffer();while(urls.find())urls.appendReplacement(output,Matcher.quoteReplacement(safeUrl(urls.group())));urls.appendTail(output);
        return output.toString().replaceAll("(?i)((?:SESSDATA|bili_jct|csrf|cookie|authorization|qrcode_key|w_rid|wts|access_key|access_token|refresh_token|token|signature|sign)[\\\"']?\\s*[:=]\\s*)(?:\\[已隐藏\\]|[\\\"']?[^\\s\\\"',;\\}\\]]+)","$1[已隐藏]").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","");
    }
    private static String shorten(String value,int max){return value.length()<=max?value:value.substring(0,max)+"…";}
}
