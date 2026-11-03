"""Execute production EPG source selection/cache writes with injected fetch/parse failures."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/xiao/bu/tv/EpgManager.java').read_text(encoding='utf8')
a=s.index('    private LoadedGuide loadGuide(');b=s.index('    private static void resolveLogos(',a)
methods=s[a:b].replace('android.content.ContentValues','Values').replace('android.database.SQLException','SqlError')
gate=s[s.index('    private void refresh(final String[]'):s.index('        cacheWorker.execute(new Runnable()',s.index('    private void refresh(final String[]'))]
gate=gate.replace('android.os.SystemClock.elapsedRealtime()','1000L')+'        attempts++;\n    }'
java=r'''
import java.io.*;import java.util.*;import java.net.HttpURLConnection;
class Check {
 static class RetryGate {
  String attemptedSource,lastError="",loadedUrl="";long attemptedDay,attemptedAt;boolean loading;
  int refreshGeneration,attempts;Guide guide=new Guide();HttpURLConnection activeDownload;
  interface Listener{}
  List<String> sanitizeSources(String[] source){return Arrays.asList(source);}
  String joinSources(List<String> sources){return sources.toString();}
  long dayStart(long now){return now/86400000;}
  GATE
 }
 static final String CACHE_PREFS="cache",DEFAULT_URL="primary",FALLBACK_URL="backup",TAG="test";
 static class Context {static int MODE_PRIVATE;SharedPreferences getSharedPreferences(String s,int m){return new SharedPreferences();}}
 static class SharedPreferences {String getString(String k,String fallback){return fallback;}}
 static class SqlError extends RuntimeException {}
 static class Values {void put(String k,Object v){}}
 static class Db {Db getWritableDatabase(){return this;}void update(String t,Values v,String where,String[] args){}}
 static class Log {static void w(String t,String s){}static void w(String t,String s,Throwable e){}}
 static class Guide {Map<String,Object> programsByChannel=new HashMap<>();}
 static class StoredGuide {Guide guide;String resolved="old";long validated;StoredGuide(Guide g){guide=g;}}
 static class LoadedGuide {Guide guide;String sourceUrl;LoadedGuide(Guide g,String u){guide=g;sourceUrl=u;}}
 static class CacheSlot {String sourceKey="source",resolvedKey="resolved",metadataKey="meta";File file=new File("not-existing");}
 static class RemoteFileMetadata {static RemoteFileMetadata fromJson(String v){return null;}}
 static class Download {byte[] bytes;boolean notModified;String url;RemoteFileMetadata metadata;Download(String u,String content){url=u;bytes=content.getBytes();notModified=content.equals("304");}}
 Context context=new Context();Db database=new Db();int writes,validations;boolean fresh;List<String> attempts=new ArrayList<>();Map<String,String> replies=new HashMap<>();
 boolean freshDatabase(long time){return fresh;}boolean isCacheFresh(File f){return false;}
 Guide readCache(File f){return null;}void resolveLogos(Guide g,String u){}
 void saveDatabase(String s,String u,Guide g,long now){writes++;}
 void writeCache(CacheSlot s,byte[] b,String source,String u,RemoteFileMetadata m)throws IOException{}
 void refreshCacheValidation(CacheSlot s,RemoteFileMetadata m,String u){validations++;}
 Download download(String u,RemoteFileMetadata m,File f,boolean force)throws IOException{
  attempts.add(u);String result=replies.get(u);if(result==null||result.equals("error"))throw new IOException("network failure");return new Download(u,result);
 }
 Guide parse(byte[] b)throws IOException{String v=new String(b);if(v.equals("html"))throw new IOException("invalid XML");Guide g=new Guide();if(v.equals("valid"))g.programsByChannel.put("CCTV1",new Object());return g;}
 METHODS
 static void ok(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  RetryGate session=new RetryGate();String[] urls={"primary"};session.refresh(urls,false,true,null);
  ok(session.attempts==1);session.loading=false;session.lastError="network failed";
  session.refresh(urls,false,true,null);ok(session.attempts==1); // Same-session failure cooldown.
  RetryGate cold=new RetryGate();cold.refresh(urls,false,true,null);ok(cold.attempts==1); // Cold start retries immediately.
  for(String primary:new String[]{"error","html","empty","304"}){
   Check c=new Check();c.replies.put("primary",primary);c.replies.put("backup","valid");LoadedGuide g=c.loadGuide("primary",new CacheSlot(),null,false);
   ok(g.sourceUrl.equals("backup")&&c.attempts.equals(Arrays.asList("primary","backup"))&&c.writes==1&&c.validations==0);
  }
  Check c=new Check();c.replies.put("primary","empty");c.replies.put("backup","html");
  try{c.loadGuide("primary",new CacheSlot(),null,false);throw new AssertionError();}catch(IOException expected){ok(c.writes==0);}
  c=new Check();c.replies.put("custom","html");try{c.loadGuide("custom",new CacheSlot(),null,false);throw new AssertionError();}catch(IOException expected){ok(c.attempts.size()==1);}
  c=new Check();c.replies.put("primary","html");c.replies.put("backup","error");Guide cached=new Guide();cached.programsByChannel.put("CCTV1",new Object());
  ok(c.loadGuide("primary",new CacheSlot(),new StoredGuide(cached),false).guide==cached&&c.writes==0);
  c=new Check();c.replies.put("primary","304");ok(c.loadGuide("primary",new CacheSlot(),new StoredGuide(cached),false).guide==cached&&c.validations==1&&c.writes==0);
  System.out.println("PASS EPG network/HTML/empty/invalid-304 fallback, custom source isolation, valid stale cache and conditional validation");
 }
}
'''.replace('METHODS',methods).replace('GATE',gate)
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'Check.java';p.write_text(java,encoding='utf8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'Check'],check=True)
