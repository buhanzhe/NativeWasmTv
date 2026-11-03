package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import java.lang.reflect.Method;
import org.json.JSONObject;
import xiao.bu.tv.skin.TvUi;

/** Real window attachment and exit-key regression checks, without media fixtures. */
public final class PopupWindowInstrumentation extends Instrumentation {
    interface Work { void run() throws Exception; }
    private void main(Work work) {
        Throwable[] failure={null};
        runOnMainSync(()->{try{work.run();}catch(Throwable t){failure[0]=t;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle result=new Bundle();MainActivity activity=null;String saved=null;int resultCode=Activity.RESULT_CANCELED;
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            final MainActivity host=activity;
            saved=new JSONObject(TvUiPlugin.status(host)).getString("selected");
            final TvUiMenu menu=new TvUiMenu();final int[] confirms={0};
            TvUi.Events events=new TvUi.Events(){
                public Bundle options(int category){return new Bundle();}
                public void action(int category,int option){if(option==1)confirms[0]++;}
                public void dismiss(){menu.dismiss();}
            };
            for(String id:new String[]{"ui2","ui3","ui4"}){
                TvUiPlugin.select(host,id);waitForIdleSync();
                main(()->{
                    View detached=new View(host);
                    check(detached.getWindowToken()==null,"Detached fixture unexpectedly has token");
                    menu.show(detached,"exit",new Bundle(),events);
                    check(!menu.visible(),"Detached anchor opened popup");
                    View anchor=host.getWindow().getDecorView();
                    check(anchor.getWindowToken()!=null,"Activity window not attached");
                    menu.show(anchor,"exit",new Bundle(),events);
                    check(menu.visible(),"Attached exit popup missing");
                    int before=confirms[0];
                    menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));
                    menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));
                    check(confirms[0]==before+1,"Back did not confirm exactly once");
                    menu.dismiss();check(!menu.confirmExitFromBack(),"Dismiss retained exit callback");
                });
            }
            main(()->{
                Method show=MainActivity.class.getDeclaredMethod("showTvUiExit");show.setAccessible(true);show.invoke(host);
                host.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));
                host.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));
                check(host.isFinishing(),"Actual exit dialog did not finish Activity");
                menu.show(host.getWindow().getDecorView(),"exit",new Bundle(),events);
                check(!menu.visible(),"Finishing Activity opened popup");
            });
            result.putString("result","PASS: 3 themes, detached/attached/finishing windows, Back confirmation and actual Activity exit");
            resultCode=Activity.RESULT_OK;
        }catch(Throwable failure){
            result.putString("failure",android.util.Log.getStackTraceString(failure));
        }finally{
            if(activity!=null&&saved!=null)try{TvUiPlugin.select(activity,saved);}catch(Exception ignored){}
        }
        finish(resultCode,result);
    }
}
