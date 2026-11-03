"""Run the production JAR installer: ABI selection, cache reuse, hashes and failed-upgrade preservation."""
from pathlib import Path
import json, hashlib, zipfile, tempfile, subprocess
root=Path(__file__).resolve().parents[1]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json'
jsonjar=next(p for p in cache.rglob('json-*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
plugin=(root/'app/src/main/java/xiao/bu/tv/TvbusPlugin.java').read_text(encoding='utf-8')
validation=plugin[plugin.index('    static void validate('):plugin.index('    private static String hash(')]
files={
'android/content/Context.java':'''package android.content;import java.io.*;public class Context {
final File dir;public Context(File d){dir=d;}public File getFilesDir(){return dir;} final SharedPreferences prefs=new SharedPreferences();
public SharedPreferences getSharedPreferences(String n,int mode){return prefs;}}
''',
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {
Map<String,String> map=new HashMap<>();public String getString(String k,String d){return map.containsKey(k)?map.get(k):d;}public Editor edit(){return new Editor();}
public class Editor{Map<String,String> pending=new HashMap<>();public Editor putString(String k,String v){pending.put(k,v);return this;}public boolean commit(){map.putAll(pending);return true;}}}''',
'xiao/bu/tv/BuildConfig.java':'''package xiao.bu.tv;class BuildConfig{static String CJS_PLUGIN_ABI="arm64-v8a";}''',
'xiao/bu/tv/TvbusPlugin.java':'package xiao.bu.tv;import java.io.*;class TvbusPlugin{'+validation+'}',
'xiao/bu/tv/TvbusLiveConfig.java':'''package xiao.bu.tv;class TvbusLiveConfig{static String online(String s)throws java.io.IOException{if(!s.startsWith("https://"))throw new java.io.IOException("bad URL");return s;}}''',
'xiao/bu/tv/BundleCheck.java':'''package xiao.bu.tv;import java.io.*;import java.nio.file.*;public class BundleCheck {
static void check(boolean b){if(!b)throw new AssertionError();}
public static void main(String[] args)throws Exception{
android.content.Context context=new android.content.Context(new File(args[0]));
check(TvbusBundle.installed(context)==null);byte[] good=Files.readAllBytes(Paths.get(args[1]));
check(TvbusBundle.install(context,good).equals("fixture"));TvbusBundle.Installed first=TvbusBundle.installed(context);
check(first.nativeFile.isFile()&&first.jar.isFile());long time=first.nativeFile.lastModified();
check(TvbusBundle.installed(context)==first);check(first.nativeFile.lastModified()==time);
for(int i=2;i<args.length;i++){try{TvbusBundle.install(context,Files.readAllBytes(Paths.get(args[i])));throw new AssertionError("bad JAR accepted");}catch(Exception expected){}check(TvbusBundle.installed(context).digest.equals(first.digest));}
BuildConfig.CJS_PLUGIN_ABI="x86";try{TvbusBundle.install(context,good);throw new AssertionError();}catch(IOException expected){}
System.out.println("PASS TVBus JAR: native/DEX hashes, ABI selection, immutable cache reuse and failed-upgrade preservation");
}}'''}
with tempfile.TemporaryDirectory() as tempdir:
    temp=Path(tempdir)
    for name,body in files.items():
        p=temp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
    native=bytearray(2048);native[:6]=b'\x7fELF\x02\x01';native[18]=183
    dex=b'dex\n035\0'+bytes(200)
    sha=lambda b:hashlib.sha256(b).hexdigest()
    info={'api':1,'type':'tvbus','version':'fixture','engines':{'arm64-v8a':sha(native)},'dexSha256':sha(dex)}
    paths=[]
    for name,metadata,data,code in [('good',info,native,dex),('bad-native',info,bytes(2048),dex),('bad-dex',info,native,dex+b'changed'),('bad-api',dict(info,api=99),native,dex)]:
        p=temp/(name+'.jar');paths.append(p)
        with zipfile.ZipFile(p,'w') as z:
            z.writestr('tvbus-plugin.json',json.dumps(metadata));z.writestr('classes.dex',code);z.writestr('native/arm64-v8a/libtvcore.so',data)
    subprocess.run(['javac','-encoding','UTF-8','-cp',str(jsonjar),'-d',tempdir,*map(str,temp.rglob('*.java')),str(root/'app/src/main/java/xiao/bu/tv/TvbusBundle.java')],check=True)
    subprocess.run(['java','-cp',str(jsonjar)+';'+tempdir,'xiao.bu.tv.BundleCheck',str(temp/'storage'),*map(str,paths)],check=True)
