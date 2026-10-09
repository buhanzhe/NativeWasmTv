package xiao.bu.tv;

import android.content.*;
import android.os.*;
import android.os.Process;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** One bounded compilation queue with a content/config keyed disk cache. */
final class LegacyJsTranspiler {
    static final int MAX_SOURCE=512*1024;
    private static final String VERSION="babel-7.28.5-acorn-8.15.0-es5-systemjs-v3";
    private static LegacyJsTranspiler instance;
    private final Context context;
    private final String compilerVersion;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<Integer,Pending> pending=new ConcurrentHashMap<>();
    private volatile Messenger service;
    private volatile int servicePid;
    private volatile CountDownLatch connected=new CountDownLatch(1);
    private boolean binding;
    private int sequence;
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(message.what==0){servicePid=message.getData().getInt("pid");return true;}
        Pending call=pending.remove(message.what);if(call!=null){call.result=message.getData();call.done.countDown();}return true;
    }));
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){service=new Messenger(binder);connected.countDown();try{Message hello=Message.obtain(null,0);hello.replyTo=inbox;service.send(hello);}catch(RemoteException ignored){}}
        @Override public void onServiceDisconnected(ComponentName name){service=null;servicePid=0;connected=new CountDownLatch(1);for(Pending call:pending.values()){call.result=new Bundle();call.result.putString("error","Compiler process exited");call.done.countDown();}pending.clear();}
    };
    private static final class Pending{final CountDownLatch done=new CountDownLatch(1);Bundle result;}
    static synchronized LegacyJsTranspiler get(Context context){if(instance==null)instance=new LegacyJsTranspiler(context.getApplicationContext());return instance;}
    private LegacyJsTranspiler(Context context){this.context=context;String version=VERSION;try(InputStream body=context.getAssets().open("legacy-compiler/version.txt")){version+="-"+new String(LegacyJsCompilerService.read(body,128),"UTF-8").trim();}catch(IOException ignored){}compilerVersion=version;}
    static boolean enabled(){return Build.VERSION.SDK_INT<=19;}
    synchronized JSONObject transform(String source,String url,boolean module)throws Exception{
        return transform(source,url,module,true);
    }
    synchronized JSONObject transform(String source,String url,boolean module,boolean allowCompile)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("Compilation on UI thread denied");
        byte[] input=source.getBytes("UTF-8");if(input.length>MAX_SOURCE)throw new IOException("Script size limit");
        File cache=new File(context.getCacheDir(),"legacy-js-cache");cache.mkdirs();
        String key=hash(compilerVersion+"\n"+module+"\n"+url+"\n"+source);
        File meta=new File(cache,key+".json"),code=new File(cache,key+".js");
        JSONObject saved=null;
        if(meta.isFile())try{saved=new JSONObject(readText(meta,16384));}catch(Exception invalid){meta.delete();code.delete();}
        if(saved!=null){
            if(saved.optBoolean("failed")){
                long age=System.currentTimeMillis()-meta.lastModified();if(age>=0&&age<30000)throw new IOException(saved.optString("error","Cached compiler failure"));
            }else try{
                saved.put("code",saved.optBoolean("changed")?readText(code,2*1024*1024):source).put("cacheHit",true).put("initializationMs",0).put("transformMs",0).put("syntaxMs",0).put("totalMs",0);
                meta.setLastModified(System.currentTimeMillis());code.setLastModified(System.currentTimeMillis());return saved;
            }catch(Exception invalid){meta.delete();code.delete();}
        }
        if(!module&&!LegacyJsSyntax.needsCompilation(source)){
            JSONObject plain=new JSONObject().put("changed",false).put("module",false).put("cacheHit",false).put("preflight",true).put("initializationMs",0).put("transformMs",0).put("syntaxMs",0).put("totalMs",0);
            write(meta,plain.toString());trim(cache);return plain.put("code",source);
        }
        if(!allowCompile)throw new IOException("Document compilation budget");
        Pending call=new Pending();int id=++sequence;pending.put(id,call);
        File ipc=new File(context.getCacheDir(),"legacy-js-ipc");ipc.mkdirs();
        String token=UUID.randomUUID().toString();File request=new File(ipc,token+".input"),response=new File(ipc,token+".output");
        try{
            try(OutputStream body=new FileOutputStream(request)){body.write(input);}
            ui.post(this::bind);
            if(!connected.await(5,TimeUnit.SECONDS)||service==null)throw new IOException("Compiler bind timeout");
            Message message=Message.obtain(null,1);message.arg1=id;message.replyTo=inbox;Bundle data=new Bundle();
            data.putString("input",request.getPath());data.putString("output",response.getPath());data.putString("url",url);data.putBoolean("module",module);message.setData(data);service.send(message);
            if(!call.done.await(25,TimeUnit.SECONDS)){
                stopStuckCompiler();throw new IOException("Compiler timeout");
            }
            if(call.result.containsKey("error"))throw new IOException(call.result.getString("error"));
            JSONObject result=new JSONObject(call.result.getString("result"));String compiled=readText(response,2*1024*1024);
            if(result.optBoolean("changed"))write(code,compiled);write(meta,result.toString());trim(cache);
            result.put("code",compiled).put("cacheHit",false);return result;
        }catch(Exception error){write(meta,new JSONObject().put("failed",true).put("error",error.getMessage()).toString());throw error;}
        finally{pending.remove(id);request.delete();response.delete();}
    }
    private void bind(){if(service!=null||binding)return;binding=context.bindService(new Intent(context,LegacyJsCompilerService.class),connection,Context.BIND_AUTO_CREATE);}
    private void stopStuckCompiler(){
        int pid=servicePid;if(pid<=0||pid==Process.myPid())return;
        try{if((context.getPackageName()+":web_transpiler").equals(readText(new File("/proc/"+pid+"/cmdline"),512).replace("\u0000","")))Process.killProcess(pid);}catch(IOException ignored){}
    }
    static String readText(File file,int limit)throws IOException{try(InputStream body=new FileInputStream(file)){return new String(LegacyJsCompilerService.read(body,limit),"UTF-8");}}
    private static void write(File target,String text)throws IOException{File tmp=new File(target.getPath()+UUID.randomUUID()+".tmp");try(OutputStream body=new FileOutputStream(tmp)){body.write(text.getBytes("UTF-8"));}if(!tmp.renameTo(target)){tmp.delete();throw new IOException("Cache commit failed");}}
    private static String hash(String text)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();}
    private static void trim(File directory){File[] files=directory.listFiles();if(files==null)return;Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));long total=0;int count=files.length;for(File file:files)total+=file.length();for(File file:files){if(total<=16*1024*1024&&count<=512)break;long size=file.length();if(file.delete()){total-=size;count--;}}}
}
