package xiao.bu.tv;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.PopupWindow;
import xiao.bu.tv.skin.TvUi;

/** Popup lifetime only. Layout, focus, recycling and drawing live in the JAR. */
final class TvUiMenu {
    private PopupWindow popup;
    private TvUi.Events exitEvents;
    void dismiss(){
        PopupWindow old=popup;popup=null;exitEvents=null;
        if(old!=null)try{old.dismiss();}catch(IllegalArgumentException detached){
            android.util.Log.w("TvUiMenu","Popup window already detached",detached);
        }
    }
    boolean visible(){return popup!=null&&popup.isShowing();}
    View contentView(){return visible()?popup.getContentView():null;}
    boolean confirmExitFromBack(){if(!visible()||exitEvents==null)return false;TvUi.Events events=exitEvents;events.action(-1,1);return true;}
    boolean dispatchKeyEvent(KeyEvent event){
        if(!visible()||exitEvents==null)return false;
        int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_VOLUME_UP||key==KeyEvent.KEYCODE_VOLUME_DOWN||key==KeyEvent.KEYCODE_VOLUME_MUTE)return false;
        if(key==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)confirmExitFromBack();return true;}
        popup.getContentView().dispatchKeyEvent(event);return true;
    }
    void refresh(){if(visible()&&TvUiPlugin.provider()!=null){try{TvUiPlugin.provider().update(popup.getContentView(),new Bundle());}catch(RuntimeException|LinkageError failure){android.content.Context c=popup.getContentView().getContext();dismiss();TvUiPlugin.rejectRenderer(c,failure);}}}
    void showSubmenu(View anchor,Bundle state,TvUi.Events events){
        Bundle child=new Bundle(state);String[] categories=state.getStringArray("categories");
        final int backIndex=categories==null?0:categories.length;
        String[] rows=new String[backIndex+1];if(categories!=null)System.arraycopy(categories,0,rows,0,backIndex);
        rows[backIndex]="返回上级";child.putStringArray("categories",rows);child.putBoolean("submenu",true);
        show(anchor,"menu",child,new TvUi.Events(){
            public Bundle options(int category){
                if(category!=backIndex)return events.options(category);
                Bundle back=new Bundle();back.putStringArray("items",new String[0]);back.putBoolean("openOnClick",true);return back;
            }
            public void action(int category,int option){if(category==backIndex)events.dismiss();else events.action(category,option);}
            public void dismiss(){events.dismiss();}
        });
    }
    void show(View anchor,String part,Bundle state,TvUi.Events events){
        dismiss();if(!canShow(anchor))return;
        Activity activity=findActivity(anchor.getContext());
        TvUiStyle style=TvUiPlugin.current();if(style==null)return;
        final View content;
        try{content=style.provider.create(anchor.getContext(),part,state,null,events);}
        catch(RuntimeException|LinkageError failure){TvUiPlugin.rejectRenderer(anchor.getContext(),failure);return;}
        if(!canShow(anchor))return;
        float d=TvUiChrome.scale(anchor);boolean menu="menu".equals(part),exit="exit".equals(part);
        // Fullscreen drawings must follow the actual popup window bounds. A fixed
        // anchor size can be clipped by window insets while its content retains
        // that larger size, shifting the apparent center on wide phone screens.
        int width=menu?Math.round(("ui3".equals(style.id)?326:"ui5".equals(style.id)?320:275)*d):ViewGroup.LayoutParams.MATCH_PARENT;
        int inset=menu&&!"ui4".equals(style.id)?Math.round(22*d):0;
        int verticalInset=menu&&"ui2".equals(style.id)?Math.round(8*d):inset;
        int horizontalInset=menu&&"ui3".equals(style.id)?Math.round(24*d):inset;
        int height=menu?Math.max(1,anchor.getHeight()-2*verticalInset):ViewGroup.LayoutParams.MATCH_PARENT;
        final int maxWidth=Math.max(1,anchor.getWidth()-2*horizontalInset);
        if(menu&&content instanceof xiao.bu.tv.skin.SharedTvUi.MenuWidth)
            width=Math.min(maxWidth,((xiao.bu.tv.skin.SharedTvUi.MenuWidth)content).preferredWidth(height));
        // Android's focusable PopupWindow consumes BACK itself and only dismisses.
        // Keep exit keys in the Activity, forwarding DPAD/ENTER to the same renderer.
        popup=new PopupWindow(content,width,height,false);exitEvents=exit?events:null;
        PopupWindow shown=popup;
        shown.setOnDismissListener(()->{if(popup==shown){popup=null;exitEvents=null;}});
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setAnimationStyle(0);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        content.setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        try{popup.showAtLocation(anchor,menu?Gravity.RIGHT|Gravity.TOP:Gravity.LEFT|Gravity.TOP,horizontalInset,verticalInset);}
        catch(WindowManager.BadTokenException unavailable){
            // The host may lose its window between the token check and addView.
            dismiss();return;
        }
        inheritWindow(activity,content);
        popup.setFocusable(!exit);popup.update();
        inheritWindow(activity,content);
        content.requestFocus();
    }
    private static Activity findActivity(Context context){
        while(context instanceof ContextWrapper){
            if(context instanceof Activity)return (Activity)context;
            Context base=((ContextWrapper)context).getBaseContext();
            if(base==context)return null;
            context=base;
        }
        return context instanceof Activity?(Activity)context:null;
    }
    private static boolean canShow(View anchor){
        if(anchor==null||anchor.getWindowToken()==null)return false;
        Activity activity=findActivity(anchor.getContext());
        return activity!=null&&!activity.isFinishing()
                &&(android.os.Build.VERSION.SDK_INT<17||!activity.isDestroyed());
    }
    private static void inheritWindow(Activity activity,View content){
            View decor=content.getRootView();
            if(!(decor.getLayoutParams() instanceof WindowManager.LayoutParams))return;
            WindowManager.LayoutParams params=(WindowManager.LayoutParams)decor.getLayoutParams();
            WindowManager.LayoutParams host=activity.getWindow().getAttributes();
            // PopupWindow creates a separate window with the default cutout
            // policy. In landscape that excludes the notch strip even though
            // the player is edge-to-edge, leaving video/debug text uncovered.
            if(android.os.Build.VERSION.SDK_INT>=28)Api28.copyCutout(params,host);
            int flags=WindowManager.LayoutParams.FLAG_FULLSCREEN
                    |WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
            params.flags=(params.flags&~flags)|(host.flags&flags);
            decor.setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
            activity.getWindowManager().updateViewLayout(decor,params);
    }
    @android.annotation.TargetApi(28)
    private static final class Api28 {
        static void copyCutout(WindowManager.LayoutParams target,WindowManager.LayoutParams source){
            target.layoutInDisplayCutoutMode=source.layoutInDisplayCutoutMode;
        }
    }
}
