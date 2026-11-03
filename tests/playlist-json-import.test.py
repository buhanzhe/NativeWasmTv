"""Run real JSON channel adapter and asynchronous import worker with JVM fixtures."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1];src=root/'app/src/main/java/xiao/bu/tv'
jars=list((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('json-*.jar'))
jar=next(p for p in jars if not p.name.endswith(('-sources.jar','-javadoc.jar')))
pm=(src/'PlaylistManager.java').read_text(encoding='utf8')
def method(signature):
 a=pm.index(signature);b=pm.index('{',a)+1;depth=1
 while depth:depth+=(pm[b]=='{')-(pm[b]=='}');b+=1
 return pm[a:b]
files={n:(src/n).read_text(encoding='utf8') for n in ['JsonChannelPlaylist.java','PlaylistImportJobs.java','ControlRequestException.java','Channel.java','IptvCatchup.java']}
files['ChannelCatalog.java']='package xiao.bu.tv; class ChannelCatalog {static final int SOURCE_CUSTOM=3;static int wrapIndex(int i,int n){return n==0?0:(i%n+n)%n;}static class Group {String title;Channel[] channels;Group(String t,int s,Channel[] c){title=t;channels=c;}}}'
files['PlaylistManager.java']='package xiao.bu.tv;import java.util.*;class PlaylistManager {'+method('    static String channelNumber(')+method('    private static boolean isChannelNumber(')+'}'
files['Check.java']=r'''package xiao.bu.tv;
import org.json.*;import java.util.concurrent.*;
class Check {
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static JSONObject row()throws Exception{return new JSONObject().put("name","CCTV-1 综合").put("group","测试组").put("epgId","CCTV1").put("urls",new JSONArray().put("webview://https://example.com/live?token=a%2Bb&x=1,2").put("https://example.com/live.m3u8"))
  .put("radio",true).put("logoUrl","https://example.com/logo.png").put("subtitleUrls",new JSONArray().put("https://example.com/sub.vtt"))
  .put("catchups",new JSONArray().put(new JSONObject().put("catchup","append").put("catchup-source","?start={utc}").put("catchup-days",3)));}
 public static void main(String[] args)throws Exception{
  Channel c=JsonChannelPlaylist.parse(new JSONArray().put(row()),"https://example.com/list")[0].channels[0];
  ok(c.name.equals("CCTV-1 综合")&&c.number.equals("1")&&c.urls.length==2&&c.urls[0].contains("a%2Bb&x=1,2"));
  ok(c.radio&&c.logoUrl.endsWith("logo.png")&&c.subtitleUrls.length==1&&c.catchupDays()>=3);
  JSONArray many=new JSONArray();for(int i=0;i<80;i++)many.put("https://example.com/live/"+i);
  ok(JsonChannelPlaylist.parse(new JSONArray().put(row().put("urls",many)),"")[0].channels[0].urls.length==80);
  for(JSONObject bad:new JSONObject[]{row().put("urls",new JSONArray().put("javascript:alert(1)")),row().put("name",""),row().put("urls",new JSONArray())})
   try{JsonChannelPlaylist.parse(new JSONArray().put(bad),"");throw new AssertionError();}catch(ControlRequestException expected){ok(expected.status==400);}
  PlaylistImportJobs jobs=new PlaylistImportJobs();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
  String id=new JSONObject(jobs.start(()->{started.countDown();release.await();return "{\"ok\":true,\"channelCount\":2537}";})).getString("jobId");started.await();
  ok(new JSONObject(jobs.status(id)).getString("state").equals("working"));
  try{jobs.start(()->"{}");throw new AssertionError();}catch(ControlRequestException expected){ok(expected.status==409);}
  release.countDown();long end=System.currentTimeMillis()+3000;JSONObject status;
  do{status=new JSONObject(jobs.status(id));Thread.sleep(5);}while(status.getString("state").equals("working")&&System.currentTimeMillis()<end);
  ok(status.getJSONObject("result").getInt("channelCount")==2537);
  id=new JSONObject(jobs.start(()->{throw new java.io.IOException("injected failure");})).getString("jobId");
  do{status=new JSONObject(jobs.status(id));Thread.sleep(5);}while(status.getString("state").equals("working")&&System.currentTimeMillis()<end);
  ok(status.getString("state").equals("failed")&&status.getString("message").contains("injected"));
  try{jobs.status("old-id");throw new AssertionError();}catch(ControlRequestException expected){ok(expected.status==404);}
  System.out.println("PASS JSON names/groups/URLs/logo/radio/subtitles/catchup validation; async acceptance, polling, concurrent rejection, completion, failure and stale job identity");
 }
}'''
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
 pkg=Path(folder)/'xiao/bu/tv';pkg.mkdir(parents=True)
 for name,text in files.items():(pkg/name).write_text(text,encoding='utf8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',folder,*map(str,pkg.glob('*.java'))],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder+os.pathsep+str(jar),'xiao.bu.tv.Check'],check=True)
