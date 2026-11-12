package xiao.bu.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit user cleanup: filesystem accounting stays off the playback/UI thread. */
final class BrowserCacheClear {
    interface Action { void run(WebSourceView.MediaControlResult done); }
    private static final AtomicBoolean running = new AtomicBoolean();
    static final class Result {
        long before, remaining, cleared;String error;boolean busy;
        String message() {
            if (busy) return "正在清理缓存，请稍候…";
            if (error != null) return "缓存清理失败：" + error;
            if (before == 0 && cleared == 0) return "暂无可清理的网页缓存（0 B）";
            return cleared > 0 ? "已清理 " + bytes(cleared) + " 网页缓存"
                    : "清理完成，未释放额外空间（0 B）";
        }
    }
    static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024) return String.format(Locale.US,"%.1f KB",value/1024d);
        if (value >= 1024L * 1024 * 1024) return String.format(Locale.US,"%.2f GB",value/(1024d*1024*1024));
        return String.format(Locale.US,"%.2f MB",value/(1024d*1024));
    }
    static Result clear(Context context,Action action) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Cache cleanup requires a worker");
        Result result = new Result();
        if (!running.compareAndSet(false,true)) { result.busy=true;return result; }
        try {
            Map<String,Long> before=snapshot(context);result.before=total(before);
            SharedWebResources.clear(context);
            CountDownLatch done=new CountDownLatch(1);String[] error={null};
            new Handler(Looper.getMainLooper()).post(()->{
                try { action.run(value->{error[0]=value;done.countDown();}); }
                catch(RuntimeException e){error[0]=e.getMessage()==null?"网页缓存清理失败":e.getMessage();done.countDown();}
            });
            if (!done.await(6,TimeUnit.SECONDS)) { result.error="网页缓存服务响应超时";return result; }
            if (error[0]!=null) { result.error=error[0];return result; }
            // Obsolete proxy files have no live readers. Never recurse through
            // the app's general cache: replay video and metadata stay intact.
            File obsolete=new File(context.getCacheDir(),"browser");
            if(obsolete.getCanonicalFile().getParentFile().equals(context.getCacheDir().getCanonicalFile()))
                remove(obsolete,obsolete.getCanonicalPath(),new HashSet<String>());
            else obsolete.delete(); // Unlink an unexpected symlink; never follow it.
            long start=SystemClock.elapsedRealtime(),previous=-1;int stable=0;
            Map<String,Long> after;
            do {
                SystemClock.sleep(100);after=snapshot(context);long size=total(after);
                stable=size==previous?stable+1:0;previous=size;
            }while(SystemClock.elapsedRealtime()-start<600 || stable<2&&SystemClock.elapsedRealtime()-start<2500);
            result.remaining=total(after);
            // Count only bytes removed/truncated from files present at the start.
            // New cache files created meanwhile cannot inflate this result.
            for(Map.Entry<String,Long> file:before.entrySet()) {
                Long length=after.get(file.getKey());result.cleared+=Math.max(0,file.getValue()-(length==null?0:length));
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt();result.error="清理已中断"; }
          catch(IOException|RuntimeException e) { result.error=e.getMessage()==null?"无法读取缓存":e.getMessage(); }
        finally { running.set(false); }
        return result;
    }
    static long measure(Context context) { return total(snapshot(context)); }
    private static long total(Map<String,Long> files){long total=0;for(long size:files.values())total+=size;return total;}
    private static Map<String,Long> snapshot(Context c){
        Map<String,Long> files=new HashMap<>();
        collect(c.getDir("webview",Context.MODE_PRIVATE),false,files);
        for(String name:new String[]{"webview","WebView","webviewCacheChromium","browser","web-resource-http-cache","lite-http-cache"})collect(new File(c.getCacheDir(),name),true,files);
        return files;
    }
    private static void collect(File file,boolean cache,Map<String,Long> files){
        try{collect(file,cache,files,file.getCanonicalPath(),new HashSet<String>());}catch(IOException ignored){}
    }
    private static void collect(File file,boolean cache,Map<String,Long> files,String root,Set<String> seen)throws IOException{
        String path=file.getCanonicalPath();
        if(!path.equals(root)&&!path.startsWith(root+File.separator)||!seen.add(path))return;
        cache|=file.getName().toLowerCase(Locale.US).contains("cache");
        if(file.isFile()){if(cache)files.put(path,Math.max(0,file.length()));return;}
        File[] children=file.listFiles();if(children!=null)for(File child:children)collect(child,cache,files,root,seen);
    }
    private static void remove(File file,String root,Set<String> seen)throws IOException{
        String path=file.getCanonicalPath();
        if((path.equals(root)||path.startsWith(root+File.separator))&&seen.add(path)){
            File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child,root,seen);
        }
        file.delete();
    }
}
