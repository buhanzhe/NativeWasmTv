package xiao.bu.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Player root without the platform full-screen focus tint. */
public final class PlayerRootLayout extends FrameLayout {
    private android.view.View receiverVideo, receiverCursor;
    private android.view.View startupWallpaper;
    private int startupDrawIndex=-1;
    @Override protected void onFinishInflate(){
        super.onFinishInflate();startupWallpaper=findViewById(R.id.startup_background);
    }
    @Override protected void dispatchDraw(android.graphics.Canvas canvas){
        startupDrawIndex=startupWallpaper!=null&&startupWallpaper.getVisibility()==VISIBLE?indexOfChild(startupWallpaper):-1;
        try{super.dispatchDraw(canvas);}finally{startupDrawIndex=-1;}
    }
    @Override protected int getChildDrawingOrder(int count,int position){
        if(startupDrawIndex<0)return position;
        return position==count-1?startupDrawIndex:position>=startupDrawIndex?position+1:position;
    }
    void setReceiverLayers(android.view.View video, android.view.View cursor) {
        receiverVideo=video; receiverCursor=cursor; invalidate();
    }
    @Override protected boolean drawChild(android.graphics.Canvas canvas,
            android.view.View child, long drawingTime) {
        if(receiverVideo!=null && child!=receiverVideo && child!=receiverCursor)return true;
        return super.drawChild(canvas,child,drawingTime);
    }

    public PlayerRootLayout(Context context) {
        this(context, null);
    }

    public PlayerRootLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setChildrenDrawingOrderEnabled(true);
        // The player root receives focus when menus close so remote keys keep
        // working. It is not a selectable control: Oreo's default focus highlight
        // would otherwise tint the entire playback area in mouse/remote mode.
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            setDefaultFocusHighlightEnabled(false);
        }
    }

}
