package xiao.bu.tv;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.webkit.WebView;

/** An explicit cache clear must not initialize legacy WebKit inside the player. */
public final class LegacyWebCacheService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        WebView cleaner = null;
        String failure=null;
        try {
            cleaner = WebViewAvailability.create(() -> new WebView(this));
            cleaner.clearCache(true); cleaner.clearFormData();
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (RuntimeException error) {
            xiao.bu.tv.AppLog.w("LegacyWebGuard", "Unable to clear browser cache", error);
            failure=error.getMessage()==null?"无法启动网页清理组件":error.getMessage();
        } finally {
            try { if (cleaner != null) cleaner.destroy(); }
            catch (RuntimeException error) { if(failure==null)failure="网页清理组件关闭失败"; }
            android.os.ResultReceiver receiver=intent==null?null:intent.getParcelableExtra("result");
            if(receiver!=null){android.os.Bundle data=new android.os.Bundle();data.putString("error",failure);receiver.send(failure==null?0:1,data);}
            stopSelf(id);
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
