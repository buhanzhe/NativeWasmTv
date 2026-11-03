"""Execute production metadata and ELF validation decisions without Android dependencies."""
from pathlib import Path
import subprocess, tempfile, os
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/TvbusPlugin.java').read_text(encoding='utf-8')
def method(start,end):
    begin=source.index(start)
    return source[begin:source.index(end,begin)]
methods=method('    static String preparedUrl(', '    static boolean metadataChanged(')
methods+=method('    static boolean metadataChanged(', '    private File downloadWithRetry(')
methods+=method('    static void validate(', '    private static String hash(')
test='''import java.io.*;public class TvbusCheck {
static class BuildConfig { static String CJS_PLUGIN_ABI="arm64-v8a"; }
static class JSONObject {
 java.util.Map<String,String> values=new java.util.HashMap<>();
 JSONObject put(String key,String value){values.put(key,value);return this;}
 String optString(String key,String fallback){return values.containsKey(key)?values.get(key):fallback;}
}
METHODS
static void check(boolean value){if(!value)throw new AssertionError();}
static boolean changed(String url,String etag,String modified,int length){return metadataChanged("https://upstream/lib.so","tag1","date1",2048,url,etag,modified,length);}
static void rejected(byte[] b)throws Exception {try{validate(b);throw new AssertionError("Accepted invalid plugin");}catch(IOException expected){}}
public static void main(String[] args)throws Exception {
 check(preparedUrl(new JSONObject().put("hls","http://127.0.0.1:8902/live.m3u8").put("http","http://127.0.0.1:8902/live.ts")).endsWith(".m3u8"));
 check(preparedUrl(new JSONObject().put("hls"," ").put("http"," http://127.0.0.1:8902/live.ts ")).endsWith(".ts"));
 check(preparedUrl(new JSONObject()).isEmpty());
 check(preparedUrl(new JSONObject().put("http","http://127.0.0.1/live.ts").put("hls","http://127.0.0.1/live.m3u8"),true).endsWith(".ts"));
 check(preparedUrl(new JSONObject().put("hls","http://127.0.0.1/live.m3u8"),true).endsWith(".m3u8"));
 check(!changed("https://upstream/lib.so","tag1","date1",2048));
 check(changed("https://upstream/new.so","tag1","date1",2048));
 check(changed("https://upstream/lib.so","tag2","date1",2048));
 check(changed("https://upstream/lib.so","tag1","date2",2048));
 check(changed("https://upstream/lib.so","tag1","date1",4096));
 check(!changed("https://upstream/lib.so","","",-1));
 byte[] b=new byte[2048];b[0]=0x7f;b[1]='E';b[2]='L';b[3]='F';b[4]=2;b[5]=1;b[18]=(byte)183;validate(b);
 b[18]=40;rejected(b);b[4]=1;BuildConfig.CJS_PLUGIN_ABI="armeabi-v7a";validate(b);
 b[0]='<';rejected(b);rejected(new byte[12]);
 System.out.println("PASS TVBus HLS/HTTP preference and fallback, metadata changes, ABI validation and broken downloads");
}}
'''.replace('METHODS',methods)
with tempfile.TemporaryDirectory() as temp:
    file=Path(temp)/'TvbusCheck.java';file.write_text(test,encoding='utf-8')
    jdk=Path(os.environ.get('JAVA_HOME',root/'.codex-tmp/jdk8/runtime/jdk8u504-b01'))/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(file)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',temp,'TvbusCheck'],check=True)
