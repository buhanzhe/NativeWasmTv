"""Compile the production source selectors, validating exact channel ownership."""
from pathlib import Path
import os, re, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/WebSiteCompatibility.java').read_text('utf8')
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,name):return next(p for p in (cache/group/name).rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
cp=os.pathsep.join(map(str,[jar('org.json','json'),jar('com.squareup.okhttp3','okhttp'),jar('com.squareup.okio','okio')]))
def method(signature,source=source):
    clean=re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',lambda m:' '*len(m[0]),source)
    start=source.index(signature);at=clean.index('{',start)+1;depth=1;end=at
    while depth:depth+=(clean[end]=='{')-(clean[end]=='}');end+=1
    return source[start:end]
methods='\n'.join(method('public static String '+name+'(') for name in ('selectedGdtvStream','selectedCztvStream'))
methods+='\n'+method('static long foregroundSniffTimeout(int', (root/'app/src/main/java/xiao/bu/tv/LiteWebPlayback.java').read_text('utf8'))
java=r'''package xiao.bu.tv; import okhttp3.*;import org.json.*;import java.util.*;
class Check {
 static final long SNIFF_TIMEOUT_MS=30000L;
 METHODS
 static void ok(boolean value){if(!value)throw new AssertionError();}
 static String page="https://www.gdtv.cn/tvChannelDetail/43",api="https://gdtv-api.gdtv.cn/api/tv/v2/tvChannel",media="https://cdn.example/live/gdws.m3u8?token=exact";
 static JSONObject entry(int id)throws Exception{return new JSONObject().put("pk",id).put("playUrl",new JSONObject().put("hd",media).toString());}
 public static void main(String[] args)throws Exception{
  String record=entry(43).toString(),list=new JSONArray().put(entry(44)).put(entry(43)).toString();
  ok(media.equals(selectedGdtvStream(page,api+"/43",record)));
  ok(selectedGdtvStream(page,api,list)==null);
  ok(media.equals(selectedGdtvStream(page,api+"/43?tvChannelPk=43",record)));
  ok(selectedGdtvStream(page,api+"?tvChannelPk=44",list)==null);
  ok(selectedGdtvStream(page,api+"?startTime=12345",list)==null);
  ok(selectedGdtvStream(page,api,new JSONArray().put(entry(44)).toString())==null);
  ok(selectedGdtvStream(page,api,new JSONArray().put(entry(43)).put(entry(43)).toString())==null);
  ok(selectedGdtvStream(page,api+"/44",record)==null);
  ok(selectedGdtvStream(page,api.replace("gdtv-api.gdtv.cn","evil.example"),list)==null);
  ok(selectedGdtvStream(page.replace("www.gdtv.cn","evil.example"),api,list)==null);
  ok(selectedGdtvStream(page,api+"/43",entry(43).put("playUrl","https://cdn.example/ad.mp4").toString())==null);
  ok(selectedGdtvStream(page,api,"not json")==null);
  ok(LiteHlsSession.sameSource(media,media.replace("token=exact","token=new")));
  ok(!LiteHlsSession.sameSource(media,media.replace("gdws.m3u8","zhujiang.m3u8")));
  ok(!LiteHlsSession.sameSource(media+"&channel=43",media+"&channel=44"));
  String timed="https://cdn.example/live/gdws.m3u8?sign=first&t=69000001&channel=43";
  ok(LiteHlsSession.sameSource(timed,timed.replace("first","renewed").replace("69000001","69000002")));
  ok(!LiteHlsSession.sameSource(timed,timed.replace("channel=43","channel=44")));
  ok(!LiteHlsSession.sameSource(media+"&t=69000001",media+"&t=69000002"));
  ok(!LiteHlsSession.sameSource(timed.replace("69000001","1"),timed.replace("69000001","2")));
  String signed="https://zwebl04.cztv.com/live/channel01720Pnew.m3u8?auth_key=original";
  ok(signed.equals(selectedCztvStream("https://www.cztv.com/liveTV/101",signed+" 我是src.value")));
  ok(selectedCztvStream("https://www.cztv.com/news/101",signed+" 我是src.value")==null);
  ok(selectedCztvStream("https://evil.example/liveTV/101",signed+" 我是src.value")==null);
  ok(selectedCztvStream("https://www.cztv.com/liveTV/101",signed+" other log")==null);
  ok(foregroundSniffTimeout(19,page)==30000&&foregroundSniffTimeout(25,page)==30000);
  ok(foregroundSniffTimeout(19,"https://www.gdtv.cn/login")==30000);
  System.out.println("PASS exact selected live channel from detail, mismatched/catalogue/archive/ad rejection and original CZTV source ownership");
 }
}'''.replace('METHODS',methods)
with tempfile.TemporaryDirectory() as tmp:
    check=Path(tmp)/'Check.java';check.write_text(java,encoding='utf8');jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'),'-encoding','UTF-8','-cp',cp,'-d',tmp,str(root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteHlsSession.java'),str(root/'app/src/main/java/xiao/bu/tv/LiteLiveMediaPolicy.java'),str(check)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp+os.pathsep+cp,'xiao.bu.tv.Check'],check=True)
