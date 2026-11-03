package xiao.bu.tv;

/** Opt-in same-package host; avoids restoring the user's previous TV channel. */
public final class WebSourceUjsHost extends android.app.Activity {
    private boolean started;
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(new android.widget.FrameLayout(this));
    }
    @Override public void onResume() {
        super.onResume();
        String id=getIntent().getStringExtra("runId");
        if(started||id==null||!id.matches("[A-Za-z0-9_-]{1,48}"))return;
        started=true;
        new Thread(()->{
            String result;
            try { result=id.startsWith("tv")?WebTvPlaybackChecks.run(new UiThread(),this)
                    :(id.startsWith("browser")?BrowserSessionChecks.run(new UiThread(),this):"")+Ku9PageResultTest.run(new UiThread(),this); }
            catch(Throwable error){ result="FAIL "+android.util.Log.getStackTraceString(error); }
            try {
                java.io.File folder=new java.io.File(getExternalFilesDir(null),"webview-ujs-test");
                if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("Cannot create evidence directory");
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,id+".txt"))){out.write(result.getBytes("UTF-8"));}
            }catch(Exception error){result+="\nEvidence write failed: "+error;}
            final String output=result;
            runOnUiThread(()->{android.widget.TextView text=new android.widget.TextView(this);text.setText(output);text.setTextSize(16);text.setPadding(24,24,24,24);setContentView(text);});
        },"ujs-loopback-check").start();
    }
    private static final class UiThread extends android.app.Instrumentation {
        @Override public void runOnMainSync(Runnable action) {
            if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){action.run();return;}
            java.util.concurrent.FutureTask<Void> task=new java.util.concurrent.FutureTask<>(action,null);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
            try { task.get(15,java.util.concurrent.TimeUnit.SECONDS); }
            catch(Exception error){throw new RuntimeException("UJS fixture UI task failed",error);}
        }
    }
}
