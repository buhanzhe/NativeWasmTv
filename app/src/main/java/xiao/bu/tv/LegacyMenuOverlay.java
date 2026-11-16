package xiao.bu.tv;

import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/** Keep old x86 emulator menus in the existing window, away from a new EGL window. */
final class LegacyMenuOverlay {
    private FrameLayout host;
    private View content, previousFocus;
    static boolean required() {
        return Build.VERSION.SDK_INT<=23 && Build.CPU_ABI!=null && Build.CPU_ABI.startsWith("x86");
    }
    boolean show(View anchor,View view,int width,int height,int horizontal,int vertical,boolean menu) {
        if(!required())return false;
        View root=anchor.getRootView();
        FrameLayout parent=anchor instanceof FrameLayout?(FrameLayout)anchor:
                root instanceof FrameLayout?(FrameLayout)root:null;
        if(parent==null)return false;
        host=parent;content=view;previousFocus=parent.findFocus();
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(width,height,
                (menu?Gravity.RIGHT:Gravity.LEFT)|Gravity.TOP);
        params.rightMargin=menu?horizontal:0;params.leftMargin=menu?0:horizontal;params.topMargin=vertical;
        view.setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        view.setSystemUiVisibility(anchor.getSystemUiVisibility());
        parent.addView(view,params);view.bringToFront();view.requestFocus();
        return true;
    }
    boolean visible(){return content!=null&&content.getParent()==host;}
    View contentView(){return visible()?content:null;}
    void dismiss(){
        View old=content,focus=previousFocus;FrameLayout parent=host;
        content=previousFocus=null;host=null;
        if(old!=null&&old.getParent()==parent)parent.removeView(old);
        if(focus!=null&&focus.isShown())focus.requestFocus();
    }
}
