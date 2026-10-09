package xiao.bu.tv;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.os.Process;
import java.io.*;
import org.json.JSONObject;

/** Disposable process. IPC transfers private file names, never multi-MiB Binder strings. */
public final class LegacyJsCompilerService extends Service {
    private HandlerThread thread;
    private Handler worker;
    private NativeQuickJs.Compiler compiler;
    private NativeQuickJs.Compiler parser;
    private final Handler ui=new Handler();
    private final Runnable idle=()->worker.post(()->{closeCompiler();cleanOrphans();stopSelf();});
    private final Messenger inbox=new Messenger(new Handler(message->{
        if(message.what==0){Bundle result=new Bundle();result.putInt("pid",Process.myPid());reply(message.replyTo,0,result);return true;}
        ui.removeCallbacks(idle);
        final Bundle request=message.getData();final Messenger peer=message.replyTo;final int id=message.arg1;
        worker.post(()->compile(id,request,peer));return true;
    }));
    @Override public void onCreate(){super.onCreate();thread=new HandlerThread("legacy-js-compiler");thread.start();worker=new Handler(thread.getLooper());worker.post(this::cleanOrphans);}
    @Override public IBinder onBind(Intent intent){return inbox.getBinder();}
    private void compile(int id,Bundle request,Messenger peer){
        Bundle reply=new Bundle();long begin=SystemClock.elapsedRealtime();
        try{
            File input=privateFile(request.getString("input")),output=privateFile(request.getString("output"));
            if(input.length()>LegacyJsTranspiler.MAX_SOURCE)throw new IOException("Script size limit");
            String source;try(InputStream body=new FileInputStream(input)){source=new String(read(body,LegacyJsTranspiler.MAX_SOURCE),"UTF-8");}
            long initialization=0,started=SystemClock.elapsedRealtime();
            if(parser==null)try(InputStream asset=getAssets().open("legacy-compiler/parser.js")){parser=new NativeQuickJs.Compiler(read(asset,1024*1024));}
            JSONObject result=new JSONObject(parser.transform(source,request.getString("url"),request.getBoolean("module")));
            initialization=SystemClock.elapsedRealtime()-started;
            if(result.optBoolean("needsTransform")){
            if(compiler==null){
                started=SystemClock.elapsedRealtime();
                try(InputStream asset=getAssets().open("legacy-compiler/compiler.js")){compiler=new NativeQuickJs.Compiler(read(asset,4*1024*1024));}
                initialization+=SystemClock.elapsedRealtime()-started;
            }
            result=new JSONObject(compiler.transform(source,request.getString("url"),request.getBoolean("module")));
            }
            byte[] code=result.getString("code").getBytes("UTF-8");if(code.length>2*1024*1024)throw new IOException("Compiled script size limit");
            try(OutputStream body=new FileOutputStream(output)){body.write(code);}
            result.remove("code");result.put("initializationMs",initialization).put("totalMs",SystemClock.elapsedRealtime()-begin).put("pid",Process.myPid());
            reply.putString("result",result.toString());
        }catch(Throwable error){reply.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());closeCompiler();}
        reply(peer,id,reply);ui.postDelayed(idle,30000);
    }
    private File privateFile(String name)throws IOException{
        File root=new File(getCacheDir(),"legacy-js-ipc").getCanonicalFile(),file=new File(name).getCanonicalFile();
        if(!file.getPath().startsWith(root.getPath()+File.separator))throw new IOException("Invalid compiler file");return file;
    }
    static byte[] read(InputStream input,int limit)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=input.read(block))!=-1){if(bytes.size()+n>limit)throw new IOException("Response size limit");bytes.write(block,0,n);}return bytes.toByteArray();
    }
    private void closeCompiler(){if(compiler!=null){compiler.close();compiler=null;}if(parser!=null){parser.close();parser=null;}}
    private void cleanOrphans(){
        File[] files=new File(getCacheDir(),"legacy-js-ipc").listFiles();if(files==null)return;
        long now=System.currentTimeMillis();
        // The caller's request watchdog is 25 s. Never remove a recent transfer.
        for(File file:files)if(file.isFile()&&now-file.lastModified()>=30000
                &&(file.getName().endsWith(".input")||file.getName().endsWith(".output")))file.delete();
    }
    private static void reply(Messenger peer,int id,Bundle data){if(peer==null)return;try{Message m=Message.obtain(null,id);m.setData(data);peer.send(m);}catch(RemoteException ignored){}}
    @Override public void onDestroy(){ui.removeCallbacks(idle);worker.post(()->{closeCompiler();thread.quit();});super.onDestroy();}
}
