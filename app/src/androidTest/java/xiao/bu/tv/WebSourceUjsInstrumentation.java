package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;

/** Real WebView and legacy IPC coverage of inline source scripts. */
public final class WebSourceUjsInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        android.app.Activity activity=null;
        int status=-1;
        try {
            android.content.ComponentName component=new android.content.ComponentName(getTargetContext(),MainActivity.class);
            try {
                android.content.ComponentName embedded=new android.content.ComponentName(getTargetContext().getPackageName(),"xiao.bu.tv.WebSourceUjsHost");
                getTargetContext().getPackageManager().getActivityInfo(embedded,0);component=embedded;
            } catch(android.content.pm.PackageManager.NameNotFoundException ignored) { }
            Bundle progress=new Bundle();progress.putString("stream","UJS check: launching fixture activity\n");sendStatus(0,progress);
            ActivityMonitor monitor=addMonitor(component.getClassName(),null,false);
            try {
                getTargetContext().startActivity(new Intent().setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                activity=monitor.waitForActivityWithTimeout(10000);
                if(activity==null)throw new IllegalStateException("UJS check activity launch timeout");
            } finally { removeMonitor(monitor); }
            progress.putString("stream","UJS check: main activity ready; starting real WebView fixture\n");sendStatus(0,progress);
            result.putString("stream",Ku9PageResultTest.run(this,activity));
        } catch(Throwable error) {
            status=0;result.putString("stream",android.util.Log.getStackTraceString(error));
        } finally {
            if(activity!=null) {final android.app.Activity closing=activity;runOnMainSync(closing::finish);}
        }
        finish(status,result);
    }
}
