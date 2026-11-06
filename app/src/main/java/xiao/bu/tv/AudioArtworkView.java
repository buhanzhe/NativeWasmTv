package xiao.bu.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.animation.TimeInterpolator;
import xiao.bu.tv.radio.RadioUi;

/** Small host container. The record, image and gestures live in a replaceable DEX JAR. */
public final class AudioArtworkView extends FrameLayout {
    private RadioUi ui;
    private RadioUiPlugin.Factory factory, pendingFactory;
    private String uiStyle = "";
    private final TextView fallback;
    private String title="", logo="";
    private Bitmap cover;
    private boolean live, playing, active=true, pending;
    private int revision;
    private Runnable listener;
    public AudioArtworkView(Context context, AttributeSet attrs) {
        super(context,attrs);
        fallback=new TextView(context);fallback.setGravity(Gravity.CENTER);fallback.setTextColor(0xfff2f5f4);
        fallback.setTextSize(24);fallback.setBackgroundColor(0xff0f1c25);
        addView(fallback,new LayoutParams(-1,-1));
    }
    private void request(){RadioUiPlugin.request(this);}
    @Override public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if(ui!=null && ui.view().getVisibility()!=visibility)ui.view().setVisibility(visibility);
    }
    void replacePlugin(RadioUiPlugin.Factory next) {
        String style = RadioUiPlugin.style(getContext());
        if(factory!=null&&factory.sha.equals(next.sha)&&uiStyle.equals(style)){pendingFactory=null;return;}
        if(ui!=null&&ui.isTransitionRunning()){pendingFactory=next;return;}
        try {
            pendingFactory=null;
            RadioUi incoming=next.create(getContext());
            boolean visible=getVisibility()==VISIBLE;
            Bitmap art=cover();
            incoming.setActive(active);incoming.setPlaying(playing);
            if(visible){if(pending)incoming.showPending(title,logo,art);else incoming.show(title,live,art);}
            incoming.setTransitionListener(new Runnable(){public void run(){
                syncVisibility();if(listener!=null)listener.run();
                if(pendingFactory!=null&&!ui.isTransitionRunning()) {
                    final RadioUiPlugin.Factory ready=pendingFactory;pendingFactory=null;
                    post(new Runnable(){public void run(){replacePlugin(ready);}});
                }
            }});
            RadioUi old=ui;ui=incoming;factory=next;uiStyle=style;cover=art;revision++;
            removeAllViews();addView(incoming.view(),new LayoutParams(-1,-1));
            if(old!=null)old.dispose();
            setVisibility(visible?VISIBLE:GONE);
        } catch(Throwable error){xiao.bu.tv.AppLog.w("RadioUi","Retaining current UI",error);}
    }
    private void syncVisibility(){if(ui!=null)setVisibility(ui.view().getVisibility());}
    void show(String name,boolean value){show(name,value,null);}
    void show(String name,boolean value,Bitmap art){
        title=name==null?"":name;live=value;pending=false;cover=art;revision++;
        if(ui!=null){ui.show(title,live,art);syncVisibility();}else{fallback.setText(title+"\n"+(live?"电台直播":"音乐"));setVisibility(VISIBLE);}
        request();
    }
    void showPending(String name,String source,Bitmap art){
        setPlaying(false);
        title=name==null?"":name;logo=source;pending=true;cover=art;revision++;
        if(ui!=null){ui.showPending(title,logo,art);syncVisibility();}else{fallback.setText(title+"\n缓冲中");setVisibility(VISIBLE);}
        request();
    }
    boolean hasPendingPresentation(){return ui!=null?ui.hasPendingPresentation():pending;}
    void clear(){clear(false);}
    void clear(boolean keep){playing=false;if(keep&&hasPendingPresentation()){if(ui!=null)ui.clear(true);return;}
        pending=false;cover=null;revision++;if(ui!=null){ui.clear(keep);syncVisibility();}else setVisibility(GONE);
    }
    void trimMemory(){if(ui!=null)ui.trimMemory();}
    void setTransitionListener(Runnable value){listener=value;}
    boolean isTransitionRunning(){return ui!=null&&ui.isTransitionRunning();}
    void beginChannelSwitch(int direction){if(ui!=null)ui.beginChannelSwitch(direction);}
    void finishChannelSwitch(){if(ui!=null)ui.finishChannelSwitch();}
    void previewSlide(float distance){previewSlide(distance,false,null,null);}
    void previewSlide(float distance,boolean audio,String source,Bitmap art){if(ui!=null)ui.previewSlide(distance,audio,source,art);}
    void updateNeighborCover(String source,Bitmap art){if(ui!=null)ui.updateNeighborCover(source,art);}
    void restoreSlide(){restoreSlide(180L,new android.view.animation.DecelerateInterpolator());}
    void restoreSlide(long duration,TimeInterpolator easing){if(ui!=null)ui.restoreSlide(duration,easing);}
    void resetSlide(){if(ui!=null)ui.resetSlide();}
    void setCover(Bitmap art){cover=art;revision++;if(ui!=null)ui.setCover(art);}
    void setPlaying(boolean value){playing=value;if(ui!=null)ui.setPlaying(value);}
    void setActive(boolean value){active=value;if(ui!=null)ui.setActive(value);}
    Bitmap cover(){return ui!=null?ui.cover():cover;}
    int coverRevision(){return revision;}
}
