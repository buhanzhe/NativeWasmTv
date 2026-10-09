package xiao.bu.tv;

import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** KitKat's synchronous cookie writes must not block the WebView UI thread. */
final class LegacyWebCookies {
    private static final ThreadPoolExecutor writer = new ThreadPoolExecutor(1,1,30L,
            TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(128),task->{
                Thread thread=new Thread(task,"legacy-web-cookie-writer");thread.setDaemon(true);return thread;
            },new ThreadPoolExecutor.DiscardPolicy());
    static { writer.allowCoreThreadTimeOut(true); }
    static void save(WebView view,String url,String value) {
        Runnable write=()->{try{CookieManager.getInstance().setCookie(url,value);}
            catch(RuntimeException error){Log.w("LegacyWebCookies","Cookie write failed: "+error.getClass().getSimpleName());}};
        // On Chromium 33, nativeSetCookie waits for Chromium's worker. That worker
        // can itself await UI services (for example device-motion initialization).
        // Posting this synchronous call to UI therefore creates a circular wait.
        if(Build.VERSION.SDK_INT<21)writer.execute(write);else view.post(write);
    }
}
