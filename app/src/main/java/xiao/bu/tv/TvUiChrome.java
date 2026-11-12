package xiao.bu.tv;

import android.content.Context;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import xiao.bu.tv.skin.TvUi;

/** Swaps only UI chrome. Original controls and all their data/key handlers survive. */
final class TvUiChrome {
    private final View originalPanel,originalCard;
    private final ArrayList<Slot> slots=new ArrayList<>();
    private View panel,card;
    private TvUi renderer;
    private TextView originalClock,originalTraffic;
    private final FrameLayout.LayoutParams panelParams,cardParams;
    private static final class Slot {
        final View view;final ViewGroup parent;final int index;final ViewGroup.LayoutParams params;
        final int l,t,r,b;final android.graphics.drawable.Drawable selector,background;
        final int dividerHeight;final android.graphics.drawable.Drawable divider;
        final float textSize;
        Slot(View v){view=v;parent=(ViewGroup)v.getParent();index=parent.indexOfChild(v);params=copyParams(v.getLayoutParams());textSize=v instanceof TextView?((TextView)v).getTextSize():0;l=v.getPaddingLeft();t=v.getPaddingTop();r=v.getPaddingRight();b=v.getPaddingBottom();background=v.getBackground();
            selector=v instanceof ListView?((ListView)v).getSelector():null;divider=v instanceof ListView?((ListView)v).getDivider():null;dividerHeight=v instanceof ListView?((ListView)v).getDividerHeight():0;
        }
        @SuppressWarnings("deprecation") void restore(){if(view.getParent()!=null)((ViewGroup)view.getParent()).removeView(view);parent.addView(view,Math.min(index,parent.getChildCount()),params);view.setBackgroundDrawable(background);view.setPadding(l,t,r,b);if(view instanceof TextView)((TextView)view).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,textSize);if(view instanceof ListView){ListView l=(ListView)view;l.setSelector(selector);l.setDivider(divider);l.setDividerHeight(dividerHeight);}}
    }
    // Typed copy constructors were added in API 19; retain weight/gravity on API 14 too.
    private static ViewGroup.LayoutParams copyParams(ViewGroup.LayoutParams p){
        ViewGroup.LayoutParams copy;
        if(p instanceof LinearLayout.LayoutParams){
            LinearLayout.LayoutParams old=(LinearLayout.LayoutParams)p;
            LinearLayout.LayoutParams next=new LinearLayout.LayoutParams(p.width,p.height,old.weight);
            next.gravity=old.gravity;copy=next;
        }else if(p instanceof FrameLayout.LayoutParams){
            copy=new FrameLayout.LayoutParams(p.width,p.height,((FrameLayout.LayoutParams)p).gravity);
        }else if(p instanceof ViewGroup.MarginLayoutParams){copy=new ViewGroup.MarginLayoutParams(p);}
        else return new ViewGroup.LayoutParams(p);
        if(p instanceof ViewGroup.MarginLayoutParams){
            ViewGroup.MarginLayoutParams from=(ViewGroup.MarginLayoutParams)p,to=(ViewGroup.MarginLayoutParams)copy;
            to.setMargins(from.leftMargin,from.topMargin,from.rightMargin,from.bottomMargin);
        }
        return copy;
    }
    TvUiChrome(View panel,View card){originalPanel=panel;originalCard=card;this.panel=panel;this.card=card;panelParams=(FrameLayout.LayoutParams)copyParams(panel.getLayoutParams());cardParams=(FrameLayout.LayoutParams)copyParams(card.getLayoutParams());}
    View originalCard(){return originalCard;}
    View applyCard(Context context){
        View previous=card;FrameLayout parent=(FrameLayout)previous.getParent();
        int index=parent.indexOfChild(previous),visibility=previous.getVisibility();
        TvUi nextRenderer=TvUiPlugin.provider();View next=originalCard;
        try{if(nextRenderer!=null)next=nextRenderer.create(context,"card",new Bundle(),null,null);}
        catch(RuntimeException|LinkageError failure){TvUiPlugin.rejectRenderer(context,failure);nextRenderer=null;}
        next.setId(originalCard.getId());
        next.setLayoutParams(nextRenderer==null?copyParams(cardParams)
                :new FrameLayout.LayoutParams(1,1,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        parent.removeView(previous);parent.addView(next,Math.min(index,parent.getChildCount()));
        next.setVisibility(visibility);card=next;renderer=nextRenderer;return card;
    }
    View[] apply(Context c,View[] controls){
        int visibility=panel.getVisibility(),cardVisibility=card.getVisibility();
        FrameLayout parent=(FrameLayout)panel.getParent();
        int panelIndex=parent.indexOfChild(panel),cardIndex=parent.indexOfChild(card);
        for(Slot slot:slots)slot.restore();slots.clear();
        parent.removeView(panel);parent.removeView(card);
        TvUiStyle style=TvUiPlugin.current();renderer=TvUiPlugin.provider();
        try {
        if(style==null){
            panel=renderer==null?originalPanel:renderer.create(c,"panel",new Bundle(),new View[]{originalPanel},null);
            card=renderer==null?originalCard:renderer.create(c,"card",new Bundle(),new View[]{originalCard},null);
            panel.setLayoutParams((FrameLayout.LayoutParams)copyParams(panelParams));card.setLayoutParams((FrameLayout.LayoutParams)copyParams(cardParams));
        }else{
            for(View v:controls)slots.add(new Slot(v));
            if(controls.length>3&&controls[3] instanceof ViewGroup){
                ViewGroup epg=(ViewGroup)controls[3];for(int i=0;i<epg.getChildCount();i++)slots.add(new Slot(epg.getChildAt(i)));
            }
            panel=renderer.create(c,"panel",new Bundle(),controls,null);panel.setId(R.id.channel_list_panel);
            card=renderer.create(c,"card",new Bundle(),null,null);card.setId(R.id.channel_bar);
            panel.setLayoutParams(new FrameLayout.LayoutParams(1,-1,Gravity.LEFT|Gravity.TOP));
            card.setLayoutParams(new FrameLayout.LayoutParams(1,1,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        }
        } catch(RuntimeException|LinkageError failure){
            for(Slot slot:slots)slot.restore();slots.clear();
            TvUiPlugin.rejectRenderer(c,failure);renderer=null;panel=originalPanel;card=originalCard;
            panel.setLayoutParams((FrameLayout.LayoutParams)copyParams(panelParams));card.setLayoutParams((FrameLayout.LayoutParams)copyParams(cardParams));
        }
        // Retain z order relative to browser, subtitles and other overlays.
        if(panelIndex>cardIndex){parent.addView(card,Math.min(cardIndex,parent.getChildCount()));parent.addView(panel,Math.min(panelIndex,parent.getChildCount()));}
        else{parent.addView(panel,Math.min(panelIndex,parent.getChildCount()));parent.addView(card,Math.min(cardIndex,parent.getChildCount()));}
        panel.setVisibility(visibility);card.setVisibility(cardVisibility);
        return new View[]{panel,card};
    }
    TextView[] applyHud(Context c,TextView clock,TextView traffic){
        if(originalClock==null){originalClock=clock;originalTraffic=traffic;}
        TextView nextClock=originalClock,nextTraffic=originalTraffic;
        if(TvUiPlugin.current()!=null){
            try{Bundle b=new Bundle();b.putString("text",clock.getText().toString());nextClock=(TextView)renderer.create(c,"clock",b,null,null);
                b=new Bundle();b.putString("text",traffic.getText().toString());nextTraffic=(TextView)renderer.create(c,"traffic",b,null,null);
            }catch(RuntimeException|LinkageError failure){TvUiPlugin.rejectRenderer(c,failure);nextClock=originalClock;nextTraffic=originalTraffic;}
        }
        replaceHud(clock,nextClock);replaceHud(traffic,nextTraffic);return new TextView[]{nextClock,nextTraffic};
    }
    private static void replaceHud(TextView previous,TextView next){
        if(previous==next)return;
        FrameLayout root=(FrameLayout)previous.getParent();int index=root.indexOfChild(previous);
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)copyParams(previous.getLayoutParams());
        next.setTypeface(previous.getTypeface());next.setText(previous.getText());next.setId(previous.getId());next.setVisibility(previous.getVisibility());
        root.removeView(previous);root.addView(next,index,params);
    }
    void updateCard(Bundle state){if(TvUiPlugin.current()!=null&&renderer!=null){try{renderer.update(card,state);}catch(RuntimeException|LinkageError failure){TvUiPlugin.rejectRenderer(card.getContext(),failure);}}}
    static float scale(View root){if(root.getContext() instanceof xiao.bu.tv.skin.SharedTvUi.Viewport)return ((xiao.bu.tv.skin.SharedTvUi.Viewport)root.getContext()).themeScale();return Math.max(.25f,Math.min(root.getWidth()/960f,root.getHeight()/540f));}
}
