package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import org.json.JSONObject;

/** Real cached wallpaper with a deliberately missing playback-ready callback. */
public final class StartupWallpaperInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        final PersonalBackground[] backdrop={null};
        final View[] overlay={null};
        try {
            MainActivity activity=(MainActivity)startActivitySync(new android.content.Intent(getTargetContext(),MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(()->{
                overlay[0]=new View(activity);
                ((FrameLayout)activity.findViewById(R.id.root)).addView(overlay[0],new FrameLayout.LayoutParams(-1,-1));
                backdrop[0]=new PersonalBackground(activity,overlay[0],activity.getSharedPreferences("tv_player",0));
            });
            android.os.SystemClock.sleep(1500);
            runOnMainSync(()->{
                try {
                    JSONObject state=backdrop[0].state();
                    check(state.getBoolean("imageAvailable"),"Cached image fixture available");
                    check(state.getLong("startupDisplayMs")>0,"Wallpaper really drawn on device");
                    check(!state.getBoolean("startupPlaybackReady")&&!state.getBoolean("startupFinished"),"Missing ready event reproduces waiting wallpaper");
                    check(overlay[0].getVisibility()==View.VISIBLE,"Wallpaper visible while awaiting frame");
                } catch(Exception e) { throw new RuntimeException(e); }
            });
            android.os.SystemClock.sleep(29500);
            runOnMainSync(()->{
                try {
                    JSONObject state=backdrop[0].state();
                    check(state.getBoolean("startupFinished")&&state.getBoolean("startupTimedOut"),"Watchdog removes stalled wallpaper");
                    check(!state.getBoolean("startupPlaybackReady"),"Timeout does not fake a first frame");
                    check(overlay[0].getVisibility()==View.GONE&&overlay[0].getBackground()==null,"Controls uncovered");
                    backdrop[0].finishStartup();
                    check(overlay[0].getVisibility()==View.GONE,"Late first frame cannot resurrect wallpaper");
                    backdrop[0].close();
                    ((FrameLayout)activity.findViewById(R.id.root)).removeView(overlay[0]);
                } catch(Exception e) { throw new RuntimeException(e); }
            });
            result.putString("result","PASS: real cached image drawn, missing first-frame reproduced, 30s deadline removes overlay, late callback safe");
            finish(-1,result);
        } catch(Throwable e) {
            if(backdrop[0]!=null)runOnMainSync(()->backdrop[0].close());
            result.putString("error",e.toString());finish(0,result);
        }
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
