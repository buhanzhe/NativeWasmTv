"""Compile actual host policies: API19 transport, station ownership and isolation."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
def jar(group, name):
    return next(p for p in (cache / group / name).rglob('*.jar')
                if not p.name.endswith(('-sources.jar', '-javadoc.jar')))
cp = os.pathsep.join(map(str, [jar('org.json', 'json'), jar('com.squareup.okhttp3', 'okhttp'), jar('com.squareup.okio', 'okio')]))
playback = (root / 'app/src/main/java/xiao/bu/tv/LiteWebPlayback.java').read_text('utf-8')
start = playback.index('    boolean adopt(Host foreground)')
adopt = playback[start:playback.index('    static boolean supported()', start)]
java = r'''package xiao.bu.tv;import java.util.*;
class Check {
 static void ok(boolean value,String why){if(!value)throw new AssertionError(why);}
 interface Host{String userAgent(String page,String fallback);void stream(String url,boolean temporary,boolean localMse,Map<String,String> headers);}
 static class Foreground implements Host{int streams;String source;Map<String,String> headers;public String userAgent(String p,String f){return f;}public void stream(String u,boolean t,boolean m,Map<String,String> h){ok(!t&&!m,"Prepared HLS remains final native playback");streams++;source=u;headers=new LinkedHashMap<>(h);}}
 static class Engine{int promotions;void promotePreparse(){promotions++;}String userAgent(){return "selected-browser";}}
 static class SharedWebProfile{static void beginLite(Object c,String p){}}
 static class Handler{int posts;void post(Runnable r){posts++;}}
 static class Adoption{
  boolean preparing=true,closed,finalStream=true,liveHlsPlayback=true;
  String profilePage="https://web.guangdianyun.tv/tv/643",preparedStream="https://cdn.example/selected.m3u8";
  Map<String,String> preparedHeaders=new LinkedHashMap<>();Host host;Object context=new Object();Engine engine=new Engine();Handler handler=new Handler();Runnable progress=()->{};
  boolean prepared(){return preparing&&!closed&&finalStream&&preparedStream!=null;}
  ADOPT_METHOD
 }
 public static void main(String[] args){
  Adoption prepared=new Adoption();prepared.preparedHeaders.put("User-Agent","selected-browser");prepared.preparedHeaders.put("Referer",prepared.profilePage);prepared.preparedHeaders.put("Cookie","fixture=authenticated");
  Map<String,String> expected=new LinkedHashMap<>(prepared.preparedHeaders);Foreground foreground=new Foreground();String selected=prepared.preparedStream;
  ok(prepared.adopt(foreground)&&foreground.streams==1&&selected.equals(foreground.source)&&expected.equals(foreground.headers),"Preparsed native source retains exact authentication headers");
  ok(prepared.preparedHeaders.isEmpty()&&prepared.engine.promotions==1&&prepared.handler.posts==1,"Transfer headers once and resume page heartbeat");
  ok(!prepared.adopt(foreground)&&foreground.streams==1,"Exclusive foreground adoption");
  Adoption ordinary=new Adoption();ordinary.liveHlsPlayback=false;Foreground other=new Foreground();ok(ordinary.adopt(other)&&other.headers.isEmpty()&&ordinary.handler.posts==0,"Unrelated prepared transport keeps existing headers and clock behavior");
  WebSiteCompatibility policy=new WebSiteCompatibility();
  String snrtv="http://live.snrtv.com/5",snrtvHls="http://stream.snrtv.com/sxbc-5-DJSWX2.m3u8";
  ok(LiteLiveMediaPolicy.legacySnrtvHls(19,snrtv,snrtvHls),"Exact original slow QinQiang station uses one owned transport");
  android.os.Build.VERSION.SDK_INT=19;ok(policy.readTimeoutMillis(snrtv,snrtvHls,"GET")==15000&&policy.requestTimeoutMillis(snrtv,snrtvHls,"GET")==20000,"Allow measured slow original manifest while retaining bounded calls");
  ok(policy.readTimeoutMillis(snrtv,snrtvHls,"POST")==0&&policy.readTimeoutMillis(snrtv.replace("/5","/4"),snrtvHls,"GET")==0,"Other methods/channels retain request budgets");
  for(String page:new String[]{snrtv+"/",snrtv.replace("/5","/nl"),snrtv.replace("snrtv.com","snrtv.com.evil.test")})ok(!LiteLiveMediaPolicy.legacySnrtvHls(19,page,snrtvHls),"Other routes retain normal transport");
  ok(!LiteLiveMediaPolicy.legacySnrtvHls(21,snrtv,snrtvHls)&&!LiteLiveMediaPolicy.legacySnrtvHls(19,snrtv,snrtvHls.replace("sxbc-5","sxbc-4")),"Other versions and neighbouring streams excluded");
  for(String id:new String[]{"643","1434"})for(String page:new String[]{"https://web.guangdianyun.tv/tv/"+id+"?uin=1629","https://web.guangdianyun.tv/tv/?id="+id+"&uin=1629"}){
   String media="https://35781-live.hlsplay.aodianyun.com/tv_radio_35781/tv_channel_"+id+".m3u8?token=current";
   android.os.Build.VERSION.SDK_INT=19;
   ok(!policy.preserveMediaPipeline(page),"API19 uses native live transport");
   ok(policy.preferNativeHls(page,media),"Selected station manifest");
   ok(policy.allowAuthRenewal(page),"Keep page-owned auth renewal");
   ok(!policy.preferNativeHls(page,media.replace("tv_channel_"+id,"tv_channel_999")),"Other station excluded");
   ok(!policy.preferNativeHls(page,media.replace("aodianyun.com","aodianyun.com.evil.test")),"Exact CDN host");
   ok(!policy.preferNativeHls(page,media.replace(".m3u8?",".ts?")),"Only manifests");
   ok(!policy.preferNativeHls(page.replace("guangdianyun.tv","guangdianyun.tv.evil.test"),media),"Exact page host");
   for(int sdk:new int[]{15,18,21,25,34}){
    android.os.Build.VERSION.SDK_INT=sdk;
    ok(policy.preserveMediaPipeline(page),"Other Android versions retain pipeline");
    ok(!policy.preferNativeHls(page,media),"API19-only exception");
   }
  }
  android.os.Build.VERSION.SDK_INT=19;
  String news="https://m-live.cctvnews.cctv.com/live/landscape.html?liveRoomNumber=11200132825562653886",newsHls="https://live-play-hls.cctvnews.cctv.com/CCTVChannel/channel_cctv1_mhd.m3u8?auth=original";
  ok(!policy.preserveMediaPipeline(news)&&policy.preferNativeHls(news,newsHls)&&policy.allowAuthRenewal(news),"Selected CCTV News page keeps native HLS authorization session");
  for(String page:new String[]{news.replace("landscape.html","index.html"),news.replace("m-live.cctvnews.cctv.com","m-live.cctvnews.cctv.com.evil.test"),news+"&liveRoomNumber=2",news.replace("11200132825562653886","invalid"),news.replace("=11200132825562653886","")})ok(policy.preserveMediaPipeline(page)||!policy.preferNativeHls(page,newsHls),"Unselected news page excluded");
  for(String media:new String[]{newsHls.replace("live-play-hls.cctvnews.cctv.com","other.cctvnews.cctv.com"),newsHls.replace(".m3u8?",".ts?"),newsHls.replace("/CCTVChannel/","/ad/"),newsHls.replace("https:","http:")})ok(!policy.preferNativeHls(news,media),"Exact selected official HLS transport");
  for(int sdk:new int[]{15,18,21,25,34}){android.os.Build.VERSION.SDK_INT=sdk;ok(policy.preserveMediaPipeline(news)&&!policy.preferNativeHls(news,newsHls),"Other versions retain CCTV News pipeline");}
  android.os.Build.VERSION.SDK_INT=19;
  for(String path:new String[]{"/tv/","/tv/?id=643&id=1434","/tv/?id=643x","/news/643"}){
   String page="https://web.guangdianyun.tv"+path;
   ok(policy.preserveMediaPipeline(page)&&!policy.allowAuthRenewal(page),"Unselected pages excluded");
  }
  ok(policy.preserveMediaPipeline("https://tv.gxtv.cn/"),"Encrypted Guangxi SDK retained");
  String gx="https://tv.gxtv.cn/channel/channelivePlay_9dfd8600075811e9ba67e41f13b60c62.html";
  ok(!policy.allowWorkers(gx,19,3*1024*1024)&&policy.preserveMediaPipeline(gx),"Small KitKat worker packets select original SDK inline demux without bypassing encryption");
  ok(!policy.allowWorkers(gx,19,24*1024*1024)&&policy.allowWorkers(gx,21,3*1024*1024),"KitKat also avoids slow ARM clone interruption; newer Android retains workers");
  for(String page:new String[]{gx.replace("tv.gxtv.cn","tv.gxtv.cn.evil.test"),"https://tv.gxtv.cn/",gx.replace("/channel/","/news/"),"https://www.huya.com/1"})ok(policy.allowWorkers(page,19,3*1024*1024),"Unrelated pages retain normal worker behavior");
  for(int station=1;station<=4;station++){
   String nbtv="https://www.ncmc.nbtv.cn/gbds/folder8458/NBTV"+station+"/index.shtml";
   ok(!policy.allowWorkers(nbtv,19,3*1024*1024)&&policy.allowWorkers(nbtv,19,24*1024*1024)&&policy.allowWorkers(nbtv,21,3*1024*1024),"Original NBTV inline demux only for small KitKat packet budgets");
   ok(policy.allowWorkers(nbtv.replace("ncmc.nbtv.cn","ncmc.nbtv.cn.evil.test"),19,3*1024*1024),"Exact NBTV page ownership");
  }
  String cgtn="https://www.cgtn.com/tv",api="https://news.cgtn.com/tv/channel-en.json",chosen="https://english-livebkali.cgtn.com/live/encgtn.m3u8";
  String response="{\"status\":200,\"data\":[\""+chosen+"\",\"https://english-livebktx.cgtn.com/live/encgtn.m3u8\"]}";
  ok(chosen.equals(policy.selectedSource(cgtn,api,response)),"Use the English page's actual first selected CDN");
  ok(policy.scriptBudgetMillis(cgtn,19,6000)==15000&&policy.scriptBudgetMillis(cgtn,21,6000)==6000&&policy.scriptBudgetMillis("https://www.cgtn.com/channel/documentary",19,6000)==6000,"Allow the original KitKat English live init callback to reach its API without changing other routes");
  String cctv6="https://m.1905.com/m/cctv6/gzh/?home";
  ok(policy.scriptBudgetMillis(cctv6,19,6000)==30000&&policy.scriptBudgetMillis(cctv6,21,6000)==6000&&policy.scriptBudgetMillis("https://m.1905.com/m/vod/",19,6000)==6000,"Allow KitKat CCTV6 original SDK initialization; keep other pages/versions bounded normally");
  String wuhan="https://ronghehao-h5.whjyapp.com/#/pages/live/tv",wuhanApi="https://ronghehao.whjyapp.com/v3/media_channel_program/dateList",days="{\"code\":1,\"data\":{\"date\":[{\"active\":1,\"date\":\"2026-11-13\"}]}}";long fresh=1794569736000L;
  ok(policy.ignoreDocumentClock(wuhan)&&policy.apiClock(wuhan,wuhanApi,days,fresh)==fresh,"Calibrate Beijing programme time from the original fresh API, never cached SPA HTML Date");
  for(String body:new String[]{"{}",days.replace("2026-11-13","2026-11-12"),days.replace("\"code\":1","\"code\":0"),days.replace("\"active\":1","\"active\":0")})ok(policy.apiClock(wuhan,wuhanApi,body,fresh)==0,"Reject unselected or unsuccessful programme metadata");
  ok(policy.apiClock(wuhan,wuhanApi.replace(".com/",".com.evil.test/"),days,fresh)==0,"Exact clock API ownership");
  ok(!policy.ignoreDocumentClock(wuhan.replace("whjyapp.com","whjyapp.com.evil.test"))&&!policy.ignoreDocumentClock(wuhan.replace("/pages/live/tv","/pages/index/index")),"Only the selected Wuhan live route ignores document clock");
  android.os.Build.VERSION.SDK_INT=21;ok(!policy.ignoreDocumentClock(wuhan)&&policy.apiClock(wuhan,wuhanApi,days,fresh)==0,"Wuhan clock adapter remains API19 only");android.os.Build.VERSION.SDK_INT=19;
  ok(chosen.equals(policy.selectedSource(cgtn+"/",api,response)),"Canonical trailing slash");
  for(String page:new String[]{cgtn+"/other","https://www.cgtn.com/channel/documentary","https://francais.cgtn.com/channel","https://www.cgtn.com.evil.test/tv"})ok(policy.selectedSource(page,api,response)==null,"Do not change other selected channels");
  for(String endpoint:new String[]{api.replace("channel-en","channel-fr"),api.replace("news.cgtn.com","news.cgtn.com.evil.test"),api.replace("https:","http:")})ok(policy.selectedSource(cgtn,endpoint,response)==null,"Exact official channel response");
  for(String invalid:new String[]{"{}","not JSON","{\"status\":200,\"data\":[]}",response.replace("200","403"),response.replace(chosen,"https://ad.example.test/ad.m3u8"),response.replace(chosen,"https://english-livebkali.cgtn.com.evil.test/live/encgtn.m3u8"),response.replace(chosen,chosen.replace("m3u8","mp4")),response.replace(chosen,"https://english-livebkali.cgtn.com/live/ad.mp4")})ok(policy.selectedSource(cgtn,api,invalid)==null,"Never choose a second fallback or malformed source");
  for(int sdk:new int[]{15,18,21,25,34}){android.os.Build.VERSION.SDK_INT=sdk;ok(policy.selectedSource(cgtn,api,response)==null,"CGTN response adapter is API19 only");}
  System.out.println("PASS API19 Guangdian HLS policy, both routes, channel/CDN ownership and other-version isolation");
  System.out.println("PASS API19 CGTN exact page/API selection, first CDN ownership, invalid data and other-channel/version isolation");
 }
}'''.replace('ADOPT_METHOD', adopt)
with tempfile.TemporaryDirectory() as folder:
    tmp = Path(folder)
    check = tmp / 'Check.java'
    check.write_text(java, encoding='utf-8')
    build = tmp / 'android/os/Build.java'
    build.parent.mkdir(parents=True)
    build.write_text('package android.os;public class Build{public static class VERSION{public static int SDK_INT;}}', encoding='utf-8')
    sources = [root / 'app/src/main/java/xiao/bu/tv' / (name + '.java') for name in ('LiteLiveMediaPolicy', 'WebSiteCompatibility', 'LiteApiClock')]
    sources += [root / 'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteHostPolicy.java', check, build]
    jdk = Path(os.environ['JAVA_HOME']) / 'bin'
    subprocess.run([str(jdk / 'javac'), '--release', '8', '-encoding', 'UTF-8', '-cp', cp, '-d', folder, *map(str, sources)], check=True)
    subprocess.run([str(jdk / 'java'), '-cp', folder + os.pathsep + cp, 'xiao.bu.tv.Check'], check=True)
