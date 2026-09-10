package xiao.bu.tv;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;
import com.bu.cc.tv.*;

/** Local HTTP fixture test. No production preferences, channel changes, or online publication. */
public final class CjsV5Instrumentation extends Instrumentation {
 Bundle args; ServerSocket server; volatile boolean serving=true; final List<String> requests=Collections.synchronizedList(new ArrayList<String>());
 static final String BASE="https://raw.githubusercontent.com/TvWasm/cjs/main";
 public void onCreate(Bundle b){args=b;super.onCreate(b);start();}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static byte[] bytes(InputStream f)throws Exception {try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[32768];int n;while((n=f.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}finally{f.close();}}
 byte[] asset(String p)throws Exception{return bytes(getContext().getAssets().open("cjs-v5/"+p));}
 static String sha(byte[] b)throws Exception{check(b!=null,"null transform");StringBuilder s=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format(java.util.Locale.US,"%02x",v&255));return s.toString();}
 static void erase(File p){if(p.isDirectory()){File[] children=p.listFiles();if(children!=null)for(File c:children)erase(c);}p.delete();}
 static void write(File p,byte[] b)throws Exception{p.getParentFile().mkdirs();FileOutputStream f=new FileOutputStream(p);f.write(b);f.close();}
 void startServer()throws Exception {
  server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
  new Thread(new Runnable(){public void run(){while(serving)try{Socket c=server.accept();BufferedReader in=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));String line=in.readLine();String p=line.split(" ")[1].split("\\?")[0];while((line=in.readLine())!=null&&line.length()!=0){}requests.add(p);byte[] b=asset(p.substring(1));if(!p.endsWith("runtime.json")&&(p.endsWith(".json")||p.endsWith(".cjs")))b=new String(b,"UTF-8").replace(BASE,"http://127.0.0.1:"+server.getLocalPort()).getBytes("UTF-8");OutputStream out=c.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(b);out.flush();c.close();}catch(Exception e){if(serving)android.util.Log.e("CjsV5Test","server",e);}}},"fixture-server").start();
 }
 byte[] gxFixture()throws Exception {
  byte[] ts=new byte[188];ts[0]=0x47;ts[1]=0x41;ts[2]=0;ts[3]=0x10;ts[6]=1;ts[7]=(byte)0xe0;ts[12]=11;ts[13]=(byte)0x80;ts[23]=(byte)0xc2;
  byte[] clear=new byte[164];for(int i=0;i<clear.length;i++)clear[i]=(byte)(i*17+3);
  byte[] shuffled=clear.clone();int chunk=41;
  // Inverse of decoder's last-block-to-second-block permutation.
  System.arraycopy(clear,2*chunk,shuffled,chunk,2*chunk);System.arraycopy(clear,chunk,shuffled,3*chunk,chunk);
  Cipher cipher=Cipher.getInstance("AES/ECB/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(new byte[16],"AES"));byte[] enc=cipher.doFinal(Arrays.copyOfRange(shuffled,20,148));System.arraycopy(enc,0,shuffled,20,128);System.arraycopy(shuffled,0,ts,24,164);
  byte[] out=NativeGxtvTransformer.transformTransportStream(ts,"gxtv-xhls-v2",new String[]{"00000000000000000000000000000000","4"});check(out!=null&&Arrays.equals(clear,Arrays.copyOfRange(out,24,188)),"GXTV AES/permutation mismatch");return out;
 }
 public void onStart(){Bundle result=new Bundle();try{
  final boolean perf="true".equals(args.getString("forcePerf"));final boolean reuse="true".equals(args.getString("reuse"));
  final String prefix="cjs-v5-test-"+BuildConfig.CJS_PLUGIN_ABI+(perf?"-force-perf":"");
  final File root=new File(getTargetContext().getFilesDir(),prefix);if(!reuse)erase(root);root.mkdirs();
  Context isolated=new ContextWrapper(getTargetContext()){
   public Context getApplicationContext(){return this;}
   public File getFilesDir(){return root;}
   public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefix+"-"+name,mode);}
  };
  if(!reuse)isolated.getSharedPreferences("cjs_sites_v5",0).edit().clear().commit();
  CjsPluginRuntime.initialize(isolated);NetworkClient.initialize(isolated);
  check(CjsNativeProfile.select("armeabi-v7a",14).equals("armv7-base"),"API14");check(CjsNativeProfile.select("armeabi-v7a",18).equals("armv7-base"),"API18");check(CjsNativeProfile.select("armeabi-v7a",19).equals("armv7-perf"),"API19");check(CjsNativeProfile.select("armeabi-v7a",25).equals("armv7-perf"),"32-bit process on 64-bit device");check(CjsNativeProfile.select("arm64-v8a",21).equals("arm64"),"API21");
  startServer();CjsPluginRuntime.setManifestUrl("http://127.0.0.1:"+server.getLocalPort()+"/catalog.json");
  String[] sites={"tv.cctv.com","tv.gxtv.cn","yangshipin.cn"};String[] modules={"cctv","gxtv","yangshipin"};
  for(String id:sites){if(reuse)check(CjsPluginRuntime.isInstalled(id),"offline cache missing "+id);else CjsPluginRuntime.installOrUpdate(id);}
  if(reuse)check(requests.size()==0,"cached cold start performed HTTP");
  if(!reuse){int so=0;for(String p:requests)if(p.endsWith(".so")){so++;String profile=CjsPluginRuntime.currentProfile();String dir="armv7-base".equals(profile)?"armeabi-v7a":("arm64".equals(profile)?"arm64-v8a":profile);check(p.contains("/"+dir+"/"),"wrong native download "+p);}check(so==3,"expected exactly three SO downloads");}
  if(perf){ // Explicit experiment only: production selection remains API-gated.
   Field sf=CjsPluginRuntime.class.getDeclaredField("states");sf.setAccessible(true);Map states=(Map)sf.get(null);
   for(int i=0;i<sites.length;i++){File d=new File(root,"forced/"+sites[i]);write(new File(d,modules[i]+".so"),asset("sites/"+sites[i]+"/dist/armv7-perf/"+modules[i]+".so"));Object state=states.get(sites[i]);Field f=state.getClass().getDeclaredField("directory");f.setAccessible(true);f.set(state,d);}
  }
  JSONObject rows=new JSONObject();long start=SystemClock.elapsedRealtime();byte[] out=NativeH5eDecryptor.decryptTransportStream(asset("cctv.ts"));rows.put("cctvMs",SystemClock.elapsedRealtime()-start);check(sha(out).equals("a95d636add5da77fc568c557ab4622e0f95952421c11fc5ec6975495424ab287"),"CCTV output mismatch");
  rows.put("gxtvHash",sha(gxFixture()));
  CjsPluginRuntime.loadNativeLibrary("yangshipin.cn");HlsProxyServer.resetCmgSessionForChannelSwitch();NativeCmgDecryptor.setClockForProbe(1789021800000L);NativeCmgDecryptor.configureLocationForProbe("https://www.yangshipin.cn/tv/home?pid=600001800");check(NativeCmgDecryptor.configureRuntimeForProbe("1789021800000",0),"CMG config");Method m=HlsProxyServer.class.getDeclaredMethod("decryptYangshipinTransportStream",byte[].class);m.setAccessible(true);start=SystemClock.elapsedRealtime();out=(byte[])m.invoke(null,(Object)asset("cmg.ts"));rows.put("cmgMs",SystemClock.elapsedRealtime()-start);check(sha(out).equals("b0bdc13fce95e28ba161f646d03cc1ec82e4d7d5afac54a817709d9e9d3af831"),"CMG output mismatch");
  String token=NativeYspSigner.tokenRnd("cjs-v5-test","1789021800000");check(token!=null&&token.length()>0,"YSP signing failed");
  rows.put("sdk",Build.VERSION.SDK_INT).put("abi",BuildConfig.CJS_PLUGIN_ABI).put("profile",CjsPluginRuntime.currentProfile()).put("forcedPerf",perf).put("reuse",reuse).put("httpRequests",new JSONArray(requests));
  result.putString("stream","PASS "+rows.toString()+"\n");finish(-1,result);
 }catch(Throwable e){android.util.Log.e("CjsV5Test","failed",e);result.putString("stream","FAIL "+e+"\n");finish(1,result);}finally{serving=false;try{if(server!=null)server.close();}catch(Exception ignored){}}}
}
