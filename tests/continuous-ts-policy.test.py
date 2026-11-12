"""Production prefetch ordering and bounds for the continuous TS profile."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf-8')
a=s.index('    private List<String> buildCmgPrefetchWindowFromUrlsLocked(');b=s.index('    private List<String> buildCctvPrefetchWindowLocked',a);method=s[a:b]
java=r'''import java.util.*;
class Check {
 boolean continuousTsMode,remoteConsumer,systemPlayerStartsAtFirst=true;
 int adaptiveBufferLevel,cctvLiveEdgeHoldBackSegments=2;
 String lastCctvRequestedUrl;
 static final int CMG_REMOTE_PREFETCH_WINDOW=3,CMG_LOCAL_PREFETCH_WINDOW=2;
 Map<String,String> cctvNextSegments=new HashMap<>();
 METHOD
 static void eq(List<String> actual,String...expected){if(!actual.equals(Arrays.asList(expected)))throw new AssertionError(actual);}
 public static void main(String[]args){
  Check c=new Check();List<String> urls=Arrays.asList("first","second","third","fourth");
  for(int i=0;i<3;i++)c.cctvNextSegments.put(urls.get(i),urls.get(i+1));
  eq(c.buildCmgPrefetchWindowFromUrlsLocked(urls),"first","second");
  c.continuousTsMode=true;
  for(int level=0;level<3;level++){c.adaptiveBufferLevel=level;eq(c.buildCmgPrefetchWindowFromUrlsLocked(urls),"first");}
  c.lastCctvRequestedUrl="first";eq(c.buildCmgPrefetchWindowFromUrlsLocked(urls),"second");
  c.remoteConsumer=true;eq(c.buildCmgPrefetchWindowFromUrlsLocked(urls),"second","third","fourth");
  c.continuousTsMode=false;c.remoteConsumer=false;c.adaptiveBufferLevel=0;
  eq(c.buildCmgPrefetchWindowFromUrlsLocked(urls),"second","third");
  System.out.println("PASS initial segment is unchanged, one ordered prefetch, bounded adaptive level and remote/default routing");
 }
}'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'Check.java';p.write_text(java,encoding='utf-8')
 subprocess.run(['javac','--release','8','-encoding','UTF-8','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'Check'],check=True)
