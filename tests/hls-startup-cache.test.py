"""Exercise production one-shot startup manifests, including staleness and bounds."""
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
out = root / '.codex-tmp/hls-startup-cache'
out.mkdir(parents=True, exist_ok=True)
java = Path(os.environ.get('JAVA_HOME', r'C:/Users/Administrator/.jdks/corretto-1.8.0_492')) / 'bin'
source = r'''package xiao.bu.tv;
import java.util.Arrays;
public class HlsStartupCacheCheck {
 static class HlsStartupManifest {
  static class Result {
   String originUrl="master",mediaUrl="child";
   java.util.List<String> segmentUrls=Arrays.asList("segment");
  }
 }
 static class Policy {
  boolean nativeMediaDownloads;
  java.util.Set<String> allowed=new java.util.HashSet<>(Arrays.asList("master","child","segment"));
  boolean canUseDirectHttpHlsUrl(String url){return allowed.contains(url);}
  SELECT_DIRECT
 }
 static void check(boolean ok) {if(!ok)throw new AssertionError();}
 public static void main(String[] args) {
  HlsStartupPlaylistCache cache=new HlsStartupPlaylistCache();
  byte[] body={1,2,3};cache.put("media",body,100);body[0]=9;
  check(Arrays.equals(cache.take("media",1099),new byte[]{1,2,3}));
  check(cache.take("media",1099)==null); // reload must reach upstream
  cache.put("expired",body,100);check(cache.take("expired",1100)==null);
  cache.put("clock",body,100);check(cache.take("clock",99)==null);
  cache.put("oversize",new byte[128*1024+1],100);check(cache.take("oversize",101)==null);
  cache.put("max",new byte[128*1024],100);check(cache.take("max",101).length==128*1024);
  for(int n=0;n<7;n++)cache.put("url"+n,new byte[]{(byte)n},200);
  check(cache.take("url0",201)==null);check(cache.take("url6",201)[0]==6);
  cache.put("same",new byte[]{1},300);cache.put("same",new byte[]{2},301);
  check(cache.take("same",302)[0]==2);check(cache.take("different",302)==null);
  Policy policy=new Policy();HlsStartupManifest.Result prepared=new HlsStartupManifest.Result();
  check("child".equals(policy.selectDirectHttpHlsUrl(prepared)));

  for(String url:Arrays.asList("master","child","segment")) {
   policy.allowed.remove(url);check(policy.selectDirectHttpHlsUrl(prepared)==null);policy.allowed.add(url);
  }
  check(policy.selectDirectHttpHlsUrl(null)==null);
  System.out.println("PASS: one-shot handoff, exact URL, expiry, clock rollback, bounded memory, immutable insertion");
 }
}'''
proxy = (root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf-8')
start = proxy.index('    String selectDirectHttpHlsUrl(')
end = proxy.index('    private static boolean canUseNativeHlsDns(', start)
source = source.replace('SELECT_DIRECT', proxy[start:end])
test = out / 'HlsStartupCacheCheck.java'
test.write_text(source, encoding='utf-8')
subprocess.run([str(java/'javac.exe'), '-encoding', 'UTF-8', '-d', str(out),
               str(root/'app/src/main/java/xiao/bu/tv/HlsStartupPlaylistCache.java'), str(test)], check=True)
subprocess.run([str(java/'java.exe'), '-cp', str(out), 'xiao.bu.tv.HlsStartupCacheCheck'], check=True)
