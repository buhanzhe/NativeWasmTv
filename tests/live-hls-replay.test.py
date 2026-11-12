"""A finite-to-live handoff must not freeze the decoder's manifest snapshot."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
proxy=(root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text('utf8')
def method(marker):
 a=proxy.index(marker);b=proxy.index('{',a)+1;depth=1
 while depth:depth+=(proxy[b]=='{')-(proxy[b]=='}');b+=1
 return proxy[a:b]
methods='\n'.join(method(x) for x in ['    private String directLiveMediaPlaylist(', '    private boolean replayCacheable(', '    private static boolean isFiniteMediaPlaylist(', '    private static boolean isPlaylistBody('])
code=r'''package xiao.bu.tv;
import java.util.*;import java.nio.charset.*;
class Check {
 static final Charset UTF_8=Charset.forName("UTF-8");boolean finiteMedia=true,nativeMediaDownloads;
 boolean canUseDirectHttpHlsUrl(String url){return url.startsWith("http://");}
 static class ProxyResponse {byte[] body;ProxyResponse(String body){this.body=body.getBytes(UTF_8);}}
 METHODS
 static void ok(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Check proxy=new Check();String live="#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:18\n#EXTINF:6,\nsegment18.ts\n";
  ok(proxy.directLiveMediaPlaylist("http://127.0.0.1:9876/live/index.m3u8",live)!=null);
  ok(!proxy.finiteMedia&&proxy.nativeMediaDownloads);
  ok(!proxy.replayCacheable(new ProxyResponse(live)));
  // A separate track can change aggregate flags before the response is sent.
  proxy.finiteMedia=true;ok(!proxy.replayCacheable(new ProxyResponse(live)));
  ok(!proxy.replayCacheable(new ProxyResponse("\ufeff \n"+live)));
  ok(proxy.replayCacheable(new ProxyResponse(live+"#EXT-X-ENDLIST\n")));
  ok(proxy.replayCacheable(new ProxyResponse(live+"#EXT-X-PLAYLIST-TYPE:VOD\n")));
  ok(proxy.replayCacheable(new ProxyResponse("finite video bytes")));
  proxy.finiteMedia=false;ok(!proxy.replayCacheable(new ProxyResponse("live video bytes")));
  proxy.finiteMedia=true;ok(proxy.directLiveMediaPlaylist("http://test/live.m3u8",live+"#EXT-X-KEY:METHOD=AES-128,URI=key\n")==null&&proxy.finiteMedia);
  System.out.println("PASS finite-to-live proxy transition, live manifest/BOM cache guards, finite-media replay and encrypted-path preservation");
 }
}'''.replace('METHODS',methods)
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder);check=p/'Check.java';check.write_text(code,'utf8')
 subprocess.run(['javac','-encoding','UTF-8','-d',folder,str(check),str(root/'app/src/main/java/xiao/bu/tv/HlsStartupManifest.java'),str(root/'app/src/main/java/xiao/bu/tv/HlsUri.java')],check=True)
 subprocess.run(['java','-cp',folder,'xiao.bu.tv.Check'],check=True)
