package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Real touch dispatch through the Activity, including Android's touch-mode focus loss. */
public final class ChannelTouchInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Throwable failure;
    private Set<String> favorites;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private Object get(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void call(String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    private interface Action{void run()throws Exception;}
    private void main(Action action)throws Exception{runOnMainSync(()->{try{action.run();}catch(Throwable t){failure=t;}});if(failure!=null)throw new Exception(failure);}
    private void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
    private void tap(String name)throws Exception{
        final int[] location=new int[2];main(()->{View v=(View)get(name);check(v.isShown(),name+" hidden");v.getLocationOnScreen(location);location[0]+=v.getWidth()/2;location[1]+=v.getHeight()/2;});
        long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,location[0],location[1],0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        MotionEvent up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,location[0],location[1],0);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        sendPointerSync(down);SystemClock.sleep(80);sendPointerSync(up);down.recycle();up.recycle();waitForIdleSync();SystemClock.sleep(150);
    }
    @Override public void onStart(){Bundle result=new Bundle();int status=-1;
        try {
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1500);
            main(()->{favorites=new HashSet<>((Set<String>)get("favoriteChannelKeys"));call("openChannelList");});
            SystemClock.sleep(300);
            String expected=((Channel[])ChannelCatalog.GROUPS[(Integer)get("browsingGroupIndex")].channels)[(Integer)get("currentChannelIndex")].name;
            setInTouchMode(true);
            tap("epgToggle");
            main(()->{check(((View)get("epgColumn")).getVisibility()==View.VISIBLE,"Touch did not open EPG");check(((TextView)get("epgStatus")).getText().toString().startsWith(expected),"EPG changed channel on touch: "+((TextView)get("epgStatus")).getText());});
            tap("epgFavorite");
            main(()->check(!favorites.equals((Set<String>)get("favoriteChannelKeys")),"Touch favorite did nothing"));
            tap("epgFavorite");
            main(()->check(favorites.equals((Set<String>)get("favoriteChannelKeys")),"Touch unfavorite changed the wrong channel"));
            tap("epgToggle");
            main(()->check(((View)get("epgColumn")).getVisibility()==View.GONE,"Touch did not close EPG"));
            result.putString("stream","PASS touch: EPG open/correct channel, favorite/unfavorite, EPG close\n");
        }catch(Throwable t){status=0;result.putString("stream",android.util.Log.getStackTraceString(t));}
        finally{if(activity!=null)runOnMainSync(()->{try{if(favorites!=null){Set<String> current=(Set<String>)get("favoriteChannelKeys");current.clear();current.addAll(favorites);call("saveFavoriteChannels");call("refreshFavoriteCatalog");}activity.finish();}catch(Exception ignored){}});}
        finish(status,result);
    }
}
