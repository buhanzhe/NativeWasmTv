package xiao.bu.tv.skin;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import xiao.bu.tv.skin.TvUi;

/** Reference layouts in a 960 x 540 coordinate space. No media APIs or I/O. */
public abstract class SharedTvUi implements TvUi {
    public static final int RENDERER_API = 1;
    /** The host supplies the same usable viewport and size preset for every UI part. */
    public interface Viewport {
        float themeScale();
        int themeHeight();
    }
    /** Focusable popups receive input directly, outside Activity.dispatchKeyEvent. */
    public interface InputMode { void themeInputMode(boolean remote); }
    public interface MenuWidth { int preferredWidth(int height); }
    public interface TitleWidths { float titleWidth(Paint paint,int first,int count); default float leadingWidth(){return 0;} default float trailingWidth(){return 0;} }
    protected abstract int theme();
    private static int accentColor(int theme){return theme==6?0xfff4d63d:theme==5?0xffb8ddff:theme==2?0xff29a6de:theme==3?0xff1c73ee:0xff00d1ff;}
    private static int panelColor(int theme){return theme==1?0xe6000000:theme==6?0xc0282626:theme==5?0xea17212e:theme==2?0xc21a3031:theme==3?0xcc24252d:0xcc313b4a;}
    private int accent(){return accentColor(theme());}
    private int panel(){return panelColor(theme());}
    /** Popup controls use the channel palette, including dark ink on light focus. */
    public static Bundle dialogPalette(String id){
        int theme="ui2".equals(id)?2:"ui3".equals(id)?3:"ui4".equals(id)?4:"ui5".equals(id)?5:"ui6".equals(id)?6:1;
        Bundle colors=new Bundle();
        colors.putInt("panelStart",theme==6?0xff414141:panelColor(theme));
        colors.putInt("panelEnd",theme==6?0xff262525:panelColor(theme));
        colors.putInt("panel",panelColor(theme));colors.putInt("text",theme==6?0xfff3f3f5:0xffeeeeef);
        colors.putInt("secondary",theme==6?0xffbdbdc2:0xffbcbfc3);colors.putInt("accent",theme==1?0xff2f80ed:accentColor(theme));
        colors.putInt("focusText",theme>=5?0xff101112:0xffffffff);
        colors.putInt("focusStart",theme==6?0xfff8f8f7:theme==5?0xffe9f3ff:theme==4?0xff168de2:theme==3?0xff209cf0:theme==2?0xff29a6de:0xff2f80ed);
        colors.putInt("focusEnd",theme==6?0xffa9a7a5:theme==5?0xffe9f3ff:theme==4?0xff245bc4:theme==3?0xff209cf0:theme==2?0xff1bbdbd:0xff2f80ed);
        return colors;
    }
    private float radius(){return theme()==6?22:theme()==5?22:theme()==2?20:theme()==3?10:0;}
    public static int preferredRowHeight(String id){return "ui6".equals(id)?50:"ui2".equals(id)?70:"ui4".equals(id)?60:65;}
    private float rowHeight(){return theme()==6?50:theme()==2?70:theme()==4?60:65;}
    public static int fittedRowHeight(int available,int preferred){
        if(available<=0)return Math.max(1,preferred);
        return Math.max(1,available/Math.max(1,available/Math.max(1,preferred)));
    }
    private float menuFontSize(){return theme()==3?22:theme()<=4?20:19;}
    private float menuTextSize(float rowHeight){return Math.min(menuFontSize(),Math.max(12,rowHeight-20));}
    private int menuPitch(float d){return Math.round((theme()==2?58:theme()==3?65:60)*d);}
    private int menuTop(float d,int height,int count){
        int pitch=Math.min(menuPitch(d),Math.max(1,height/Math.max(1,count)));
        return Math.max(0,(height-count*pitch)/2);
    }
    private int menuCategoryPitch(float d,int height,int count){return Math.min(menuPitch(d),Math.max(1,height/Math.max(1,count)));}
    private float titleWidth(View slot,float size,int capacity,float minimum){
        Paint paint=new Paint();paint.setTextSize(size);
        android.widget.ListAdapter adapter=((ListView)slot).getAdapter();
        // The adapter supplies the cached catalog-wide channel width. Focus,
        // category switches and recycled children cannot change column width.
        float width=adapter instanceof TitleWidths?((TitleWidths)adapter).titleWidth(paint,
                0,adapter.getCount()):capacity*size;
        return Math.max(minimum,Math.min(capacity*size,width));
    }
    private float scale(Context c){if(c instanceof Viewport)return ((Viewport)c).themeScale();return Math.max(.25f,Math.min(c.getResources().getDisplayMetrics().widthPixels/960f,c.getResources().getDisplayMetrics().heightPixels/540f));}
    private GradientDrawable shape(int color,float r){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(r);return d;}
    @SuppressWarnings("deprecation") private void background(View v,int color,float r){v.setBackgroundDrawable(shape(color,r));}
    public View create(Context c,String part,Bundle state,View[] slots,Events events){
        if("clock".equals(part)||"traffic".equals(part))return new Hud(c,state);
        if("panel".equals(part))return new Panel(c,slots);
        if("card".equals(part))return new Card(c,state);
        if("menu".equals(part))return new Menu(c,state,events);
        if("exit".equals(part))return new Exit(c,events);
        if("guide".equals(part))return new Guide(c,events);
        if("epg-row".equals(part))return new EpgRow(c,state);
        return new Row(c,state);
    }
    public void update(View view,Bundle state){
        if(view instanceof Row)((Row)view).bind(state);
        else if(view instanceof EpgRow)((EpgRow)view).bind(state);
        else if(view instanceof Card)((Card)view).bind(state);
        else if(view instanceof Menu)((Menu)view).refresh();
    }
    private abstract class Drawing extends View {
        final Paint p=new Paint(3); final RectF rect=new RectF();
        final Rect numberBounds=new Rect();
        LinearGradient gradient;float gradientLeft,gradientRight;
        Drawing(Context c){super(c);if(android.os.Build.VERSION.SDK_INT>=16)setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
        void box(Canvas c,float l,float t,float r,float b,int color,float radius){p.setColor(color);p.setStyle(Paint.Style.FILL);rect.set(l,t,r,b);c.drawRoundRect(rect,radius,radius,p);}
        void line(Canvas c,float l,float t,float r,float b){p.setColor(0x35ffffff);p.setStrokeWidth(.6f);c.drawLine(l,t,r,b,p);}
        void text(Canvas c,String text,float x,float y,float size,int color,float max){
            if(text==null||text.length()==0||max<=0)return;
            p.setColor(color);p.setTextSize(size);p.setTypeface(Typeface.DEFAULT);p.setStyle(Paint.Style.FILL);
            // Clip once instead of measuring every substring; ellipsis uses breakText, not a quadratic loop.
            int n=p.breakText(text,true,max,null);if(n<text.length()){
                float ell=p.measureText("…");n=p.breakText(text,true,Math.max(0,max-ell),null);text=text.substring(0,n)+"…";
            }
            c.drawText(text,x,y,p);
        }
        void center(Canvas c,String text,float l,float r,float y,float size,int color){
            p.setTextSize(size);float room=Math.max(0,r-l);
            if(p.measureText(text)>room){int n=p.breakText(text,true,Math.max(0,room-p.measureText("…")),null);text=text.substring(0,n)+"…";}
            float width=p.measureText(text);text(c,text,l+Math.max(0,(room-width)/2),y,size,color,room);
        }
        void centerNumber(Canvas c,String value,float l,float r,float centerY,float size,int color){
            if(value==null||value.isEmpty()||r<=l)return;
            p.setTypeface(Typeface.DEFAULT);p.setFakeBoldText(false);p.setStyle(Paint.Style.FILL);
            p.setColor(color);p.setTextSize(size);
            float width=p.measureText(value),room=r-l;
            // Identifiers must remain complete; fit the glyphs instead of using
            // the title helper's ellipsis (four digits have different widths).
            if(width>room){p.setTextSize(size*room/width);width=p.measureText(value);}
            // Center the actual digits, after width fitting, inside the badge.
            // A fixed baseline offset leaves different padding at smaller sizes.
            p.getTextBounds(value,0,value.length(),numberBounds);
            float baseline=centerY-(numberBounds.top+numberBounds.bottom)/2f;
            c.drawText(value,(l+r-width)/2,baseline,p);
        }
        void selected(Canvas c,float l,float t,float r,float b){
            selected(c,l,t,r,b,-1);
        }
        void selected(Canvas c,float l,float t,float r,float b,float cornerRadius){
            if(theme()==6){
                float rr=cornerRadius>=0?cornerRadius:12;
                if(gradient==null||l!=gradientLeft||r!=gradientRight){gradient=new LinearGradient(l,t,r,b,0xfff8f8f7,0xffa9a7a5,Shader.TileMode.CLAMP);gradientLeft=l;gradientRight=r;}
                p.setShader(gradient);p.setColor(Color.WHITE);rect.set(l,t,r,b);c.drawRoundRect(rect,rr,rr,p);p.setShader(null);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(0xeaffffff);rect.inset(.5f,.5f);c.drawRoundRect(rect,rr,rr,p);p.setStyle(Paint.Style.FILL);return;
            }
            if(theme()==5){box(c,l,t,r,b,0xffe9f3ff,cornerRadius>=0?cornerRadius:Math.min(20,(b-t)/2));return;}
            if(theme()==4){
                if(gradient==null||l!=gradientLeft||r!=gradientRight){gradient=new LinearGradient(l,0,r,0,0xff168de2,0xff245bc4,Shader.TileMode.CLAMP);gradientLeft=l;gradientRight=r;}
                p.setShader(gradient);p.setColor(Color.WHITE);rect.set(l,t,r,b);c.drawRoundRect(rect,4,4,p);p.setShader(null);
                box(c,l,t+6,l+3,b-6,accent(),1);
                return;
            }
            if(theme()==2&&(gradient==null||l!=gradientLeft||r!=gradientRight)){gradient=new LinearGradient(l,0,r,0,0xff29a6de,0xff1bbdbd,Shader.TileMode.CLAMP);gradientLeft=l;gradientRight=r;}p.setShader(theme()==2?gradient:null);
            boolean listRow=this instanceof Row||this instanceof EpgRow;
            rect.set(l,t,r,b);p.setColor(theme()==3&&listRow?0xff209cf0:accent());float rr=cornerRadius>=0?cornerRadius:theme()==2?(b-t)/2:theme()==3&&listRow?6:4;c.drawRoundRect(rect,rr,rr,p);p.setShader(null);
            if(theme()==3&&listRow){p.setColor(0xff59b8ec);p.setStrokeWidth(.7f);p.setStyle(Paint.Style.STROKE);rect.inset(.4f,.4f);c.drawRoundRect(rect,rr,rr,p);p.setStyle(Paint.Style.FILL);}
        }
    }
    /** Uses the host's existing clock and transport sampling, without its own timer. */
    private final class Hud extends TextView {
        Hud(Context c,Bundle state){super(c);float d=scale(c);setText(state.getString("text",""));setTextColor(0xffeeeeef);setTextSize(TypedValue.COMPLEX_UNIT_PX,20*d);setSingleLine(true);setIncludeFontPadding(false);setGravity(Gravity.CENTER);setPadding(Math.round(9*d),Math.round(4*d),Math.round(9*d),Math.round(4*d));setShadowLayer(1*d,0,1*d,0x88000000);background(this,theme()==5?0xb31a2636:theme()==3?0x60000000:0x500d1720,28*d);setClickable(false);setFocusable(false);}
    }
    private final class Panel extends ViewGroup {
        final xiao.bu.tv.FrostedBackdrop glass=new xiao.bu.tv.FrostedBackdrop(this);
        LinearGradient glassTint;
        protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(theme()==6)glassTint=new LinearGradient(0,0,w,h,0xff414141,0xff262525,Shader.TileMode.CLAMP);}
        protected void onVisibilityChanged(View view,int visibility){super.onVisibilityChanged(view,visibility);if(theme()==6){if(visibility==VISIBLE)post(()->glass.capture());else glass.clear();}}
        protected void onDetachedFromWindow(){glass.clear();super.onDetachedFromWindow();}
        final View[] slots;final float d;final Paint shade=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF shadeBounds=new RectF();int channelPitch,groupPitch;
        Panel(Context c,View[] views){super(c);d=scale(c);slots=views;setWillNotDraw(false);if(theme()!=6)background(this,panel(),radius()*d);
            for(View v:views){if(v.getParent()!=null)((ViewGroup)v.getParent()).removeView(v);addView(v);}
            for(int i=0;i<2;i++){ListView list=(ListView)slots[i];list.setPadding(0,0,0,0);list.setDivider(null);list.setSelector(new ColorDrawable(Color.TRANSPARENT));list.setCacheColorHint(Color.TRANSPARENT);}
            TextView epg=(TextView)slots[2];epg.setBackgroundDrawable(new ColorDrawable(theme()==2||theme()==3?Color.TRANSPARENT:0x15000000));epg.setTextSize(TypedValue.COMPLEX_UNIT_PX,13*d);
            if(theme()==6){epg.setText("节目单  ›");epg.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));}
            styleGuide(slots[3]);
        }
        private void styleGuide(View child){
            if(child instanceof ListView){ListView list=(ListView)child;list.setSelector(new ColorDrawable(Color.TRANSPARENT));list.setDivider(null);list.setPadding(0,0,0,0);}
            else if(child instanceof TextView){((TextView)child).setTextSize(TypedValue.COMPLEX_UNIT_PX,14*d);int left=Math.round((theme()==2?24:theme()==3?12:16)*d);child.setPadding(left,Math.round(6*d),Math.round(12*d),Math.round(8*d));}
            else if(child instanceof ViewGroup){ViewGroup parent=(ViewGroup)child;for(int i=0;i<parent.getChildCount();i++)styleGuide(parent.getChildAt(i));}
        }
        protected void onMeasure(int ws,int hs){
            int limit=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);
            if(theme()==6){
                int g=Math.round((titleWidth(slots[0],18,8,90)+110)*d);
                int channel=Math.round((titleWidth(slots[1],18,xiao.bu.tv.ChannelNameWidth.MAX_CHARS,90)+146+channelLeading()+channelTrailing())*d);
                boolean expanded=slots[3].getVisibility()!=GONE;
                int w=Math.min(limit,g+channel+Math.round(24*d)+(expanded?Math.round((230+guideExtra())*d):0));
                g=Math.min(g,w/2);int epg=expanded?Math.min(Math.round((230+guideExtra())*d),Math.max(0,w-g-Math.round(180*d))):0;
                channel=Math.max(1,w-g-epg-Math.round(24*d));setMeasuredDimension(w,h);
                int available=Math.max(1,h-Math.round(124*d));channelPitch=fittedRowHeight(available,Math.round(rowHeight()*d));groupPitch=channelPitch;
                for(int i=0;i<4;i++)slots[i].measure(MeasureSpec.makeMeasureSpec(i==0?g:i==1?channel:i==2?Math.round(100*d):epg,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(i==2?Math.round(40*d):available,MeasureSpec.EXACTLY));
                return;
            }
            int handle=slots[2].getVisibility()==GONE?0:Math.max(32,Math.round(30*d));
            // Every theme exposes the host's guide. Theme 2 already reserved its
            // handle; add room in the others instead of narrowing channel names.
            float groupTitle=titleWidth(slots[0],20,8,100);
            float channelTitle=titleWidth(slots[1],20,xiao.bu.tv.ChannelNameWidth.MAX_CHARS,80);
            int groupDesired=Math.round((groupTitle+36)*d);
            // Width includes trailing breathing room even when the longest title fits exactly.
            float channelChrome=theme()==2?96:theme()==3?63:theme()==4?83:78;
            int desiredBase=groupDesired+Math.round((channelTitle+channelChrome+channelLeading()+channelTrailing())*d)+handle;
            boolean expanded=slots[3].getVisibility()!=GONE;
            int w=Math.min(limit,desiredBase+(expanded?Math.round((246+guideExtra())*d):0));setMeasuredDimension(w,h);
            int base=Math.min(w,desiredBase),epg=expanded?Math.max(0,w-base):0;
            int g=Math.min(Math.max(1,base-handle-1),Math.max(Math.round(132*d),Math.min(groupDesired,Math.round(base*.42f))));
            int channel=Math.max(1,base-g-handle),vertical=theme()==4?0:Math.round((theme()==3?20:theme()==2?12:6)*d);
            channelPitch=fittedRowHeight(Math.max(1,h-vertical*2),Math.round(rowHeight()*d));
            groupPitch=channelPitch;
            for(int i=0;i<slots.length;i++){
                int width=i==0?g:i==1?channel:i==2?handle:epg;
                int height=i==2?Math.round(110*d):h-vertical*2;
                slots[i].measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(1,height),MeasureSpec.EXACTLY));
            }
        }
        float channelLeading(){android.widget.ListAdapter a=((ListView)slots[1]).getAdapter();return a instanceof TitleWidths?((TitleWidths)a).leadingWidth():0;}
        float channelTrailing(){android.widget.ListAdapter a=((ListView)slots[1]).getAdapter();return a instanceof TitleWidths?((TitleWidths)a).trailingWidth():0;}
        float guideExtra(){Object width=slots[3].getTag();return width instanceof Number?((Number)width).floatValue():0;}
        protected void onLayout(boolean changed,int l,int t,int r,int b){int x=0,pad=theme()==4?0:Math.round((theme()==3?20:theme()==2?12:6)*d),h=b-t;
            if(theme()==6){
                x=Math.round(12*d);int top=Math.round(70*d);
                for(int i=0;i<4;i++){if(i==2)continue;View v=slots[i];v.layout(x,top,x+v.getMeasuredWidth(),top+v.getMeasuredHeight());x+=v.getMeasuredWidth();}
                View handle=slots[2];handle.layout(getWidth()-Math.round(116*d),h-Math.round(44*d),getWidth()-Math.round(16*d),h-Math.round(4*d));
                if(changed)post(()->glass.capture());return;
            }
            for(int i=0;i<slots.length;i++){View v=slots[i];int y=i==2?(h-v.getMeasuredHeight())/2:pad;v.layout(x,y,x+v.getMeasuredWidth(),y+v.getMeasuredHeight());x+=v.getMeasuredWidth();}
        }
        protected void onDraw(Canvas c){super.onDraw(c);Paint p=shade;
            if(theme()==6){
                shadeBounds.set(0,0,getWidth(),getHeight());
                if(!glass.draw(c,shadeBounds,22*d)){p.setShader(glassTint);p.setColor(Color.WHITE);c.drawRoundRect(shadeBounds,22*d,22*d,p);p.setShader(null);}
                float stroke=Math.max(1,Math.round(d));
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);p.setColor(0x668f8e8b);shadeBounds.inset(stroke/2,stroke/2);c.drawRoundRect(shadeBounds,22*d-stroke/2,22*d-stroke/2,p);p.setStyle(Paint.Style.FILL);
                p.setTextSize(18*d);p.setColor(0xffbdbdbf);c.drawText("频道",28*d,42*d,p);
                View selected=((ListView)slots[0]).getSelectedView();Bundle group=selected instanceof Row?((Row)selected).data:null;
                p.setColor(Color.WHITE);c.drawText(group==null?"频道列表":group.getString("title","频道列表"),slots[1].getLeft()+18*d,42*d,p);
                p.setTextSize(13*d);p.setColor(0xffbdbdbf);c.drawText(((ListView)slots[1]).getCount()+" 个频道",slots[1].getLeft()+18*d,62*d,p);
                p.setStrokeWidth(d/2);p.setColor(0x55aaaaaa);c.drawLine(slots[0].getRight(),24*d,slots[0].getRight(),getHeight()-60*d,p);c.drawLine(22*d,getHeight()-48*d,getWidth()-22*d,getHeight()-48*d,p);
                p.setTextSize(13*d);p.setColor(0xffdddddf);c.drawText("OK 播放    长按频道 收藏/取消",24*d,getHeight()-18*d,p);return;
            }
            p.setColor(theme()==2?0x6b001c20:theme()==3?0x30202430:theme()==4?0x80000106:0x74141c26);float width=slots[0].getRight(),radius=radius()*d;c.save();c.clipRect(0,0,width,getHeight());shadeBounds.set(0,0,width+radius,getHeight());c.drawRoundRect(shadeBounds,radius,radius,p);c.restore();
            if(theme()==2&&slots[2].getVisibility()!=GONE){
                // The reference uses a full-height guide rail, not a small button backdrop.
                float left=slots[2].getLeft(),right=slots[2].getRight();p.setColor(0x30000000);
                c.save();c.clipRect(left,0,right,getHeight());shadeBounds.set(left-radius,0,right,getHeight());
                c.drawRoundRect(shadeBounds,radius,radius,p);c.restore();
            }
        }
    }
    private final class Row extends Drawing {
        final Rect starBounds=new Rect();
        Bundle data;final float d;final boolean group;int beat;boolean attached,pulsePosted;
        final ChannelTitleMarquee titleScroll=new ChannelTitleMarquee();
        String measuredTitle;float measuredTitleSize,measuredTitleWidth;boolean measuredTitleBold,titleScrollPosted;
        final Runnable titleTick=new Runnable(){public void run(){titleScrollPosted=false;if(canScrollTitle())invalidate();else stopTitleScroll();}};
        boolean canScrollTitle(){return attached&&!group&&data!=null&&data.getBoolean("selected")&&!data.getBoolean("favoriteFocused")
                &&getWindowVisibility()==VISIBLE&&hasWindowFocus()&&isShown()
                &&(getParent() instanceof ListView?((ListView)getParent()).hasFocus():data.getBoolean("focused",true));}
        void stopTitleScroll(){if(titleScrollPosted)removeCallbacks(titleTick);titleScrollPosted=false;if(titleScroll!=null)titleScroll.stop();}
        void channelTitle(Canvas c,float x,float y,float size,int color,float room){
            String title=data.getString("title","");boolean bold=p.isFakeBoldText();
            if(!title.equals(measuredTitle)||size!=measuredTitleSize||bold!=measuredTitleBold){
                measuredTitle=title;measuredTitleSize=size;measuredTitleBold=bold;
                p.setTextSize(size);p.setTypeface(Typeface.DEFAULT);measuredTitleWidth=p.measureText(title);stopTitleScroll();
            }
            long now=android.os.SystemClock.uptimeMillis();
            titleScroll.configure(measuredTitleWidth,room,canScrollTitle(),now);
            long delay=titleScroll.delay(now);
            if(delay<0){stopTitleScroll();text(c,title,x,y,size,color,room);return;}
            p.setColor(color);p.setTextSize(size);p.setTypeface(Typeface.DEFAULT);p.setStyle(Paint.Style.FILL);
            c.save();c.clipRect(x,0,x+room,getHeight()/d);c.drawText(title,x-titleScroll.offset(now),y,p);c.restore();
            // Only the selected overflowing title owns a callback. Pauses do not redraw at 30 Hz.
            if(!titleScrollPosted){titleScrollPosted=true;postDelayed(titleTick,delay);}
        }
        final Runnable pulse=new Runnable(){public void run(){pulsePosted=false;if(!canPulse())return;beat=(beat+1)&7;invalidate(0,0,Math.round(70*d),getHeight());schedulePulse();}};
        boolean canPulse(){return attached&&!group&&data!=null&&data.getBoolean("playing")&&getWindowVisibility()==VISIBLE&&isShown();}
        // At most one 10 Hz callback per visible playing row; no per-frame allocations.
        void schedulePulse(){if(pulse==null)return;if(canPulse()){if(!pulsePosted){pulsePosted=true;postDelayed(pulse,100);}}else{removeCallbacks(pulse);pulsePosted=false;}}
        protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;schedulePulse();}
        protected void onDetachedFromWindow(){attached=false;schedulePulse();stopTitleScroll();super.onDetachedFromWindow();}
        public void onStartTemporaryDetach(){attached=false;schedulePulse();stopTitleScroll();super.onStartTemporaryDetach();}
        public void onFinishTemporaryDetach(){super.onFinishTemporaryDetach();attached=true;schedulePulse();}
        protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);schedulePulse();stopTitleScroll();}
        protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);schedulePulse();stopTitleScroll();}
        public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused)stopTitleScroll();invalidate();}
        protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        Row(Context c,Bundle b){super(c);d=scale(c);group=b.getBoolean("group");bind(b);setLayoutParams(new AbsListView.LayoutParams(-1,Math.round(rowHeight()*d)));}
        protected void onMeasure(int widthSpec,int heightSpec){
            if(getParent() instanceof ListView&&getParent().getParent() instanceof Panel){
                Panel panel=(Panel)getParent().getParent();
                int pitch=group?panel.groupPitch:panel.channelPitch;
                if(pitch>0)heightSpec=MeasureSpec.makeMeasureSpec(pitch,MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec,heightSpec);
        }
        void bind(Bundle b){data=b;String number=b.getString("number","");if(theme()==3&&number.length()>0&&number.length()<4)b.putString("number",("0000"+number).substring(number.length()));setContentDescription(b.getString("title","")+" "+b.getString("program",""));setActivated(b.getBoolean("selected"));if(!canScrollTitle()||!b.getString("title","").equals(measuredTitle))stopTitleScroll();schedulePulse();invalidate();}
        void playbackBars(Canvas c,float x,float bottom,float width,int first,int second,int third){
            for(int i=0;i<3;i++){int phase=(beat+i*3)&7;float height=5+(phase<4?phase:7-phase)*5;box(c,x+i*(width+3),bottom-height,x+i*(width+3)+width,bottom,i==0?first:i==1?second:third,1);}
        }
        protected void onDraw(Canvas canvas){canvas.save();canvas.scale(d,d);float w=getWidth()/d,h=getHeight()/d;
            boolean sel=data.getBoolean("selected"),playing=data.getBoolean("playing");
            // ListView may bind/attach while its drawer is still GONE. The first
            // visible draw also arms the pulse, without starting a second timer.
            if(playing)schedulePulse();
            if(theme()==6){glassRow(canvas,w,h);canvas.restore();return;}
            // Only the column owning navigation focus paints a filled selection.
            boolean focus=sel&&!data.getBoolean("favoriteFocused")&&(getParent() instanceof ListView?((ListView)getParent()).hasFocus():data.getBoolean("focused",!group));
            if(group){
                if(focus){
                    // A browsed group keeps accent text. Only the column owning
                    // keyboard focus draws a filled selection background.
                    if(theme()==4)selected(canvas,8,3,w-8,h-3);
                    else selected(canvas,8,theme()==2?8:3,w-8,h-(theme()==2?8:3));
                }
                int color=focus&&theme()==5?0xff142438:focus?0xffeeeeef:sel&&theme()==3?0xff29a6de:sel?accent():0xffe7e8e9;
                canvas.save();canvas.clipRect(12,0,w-12,h);
                center(canvas,data.getString("title",""),16,w-16,h/2+7,20,color);canvas.restore();
            }
            else{
                if(theme()<=4){referenceRow(canvas,w,h,focus,playing);canvas.restore();return;}
                if(focus)selected(canvas,0,3,w,h-3);
                if(playing){int color=focus&&theme()==5?0xff142438:0xffeeeeef;playbackBars(canvas,18,h/2+9,3,color,color,color);}
                else{p.setColor(0x6485939b);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);rect.set(10,h/2-10,46,h/2+10);canvas.drawRoundRect(rect,2,2,p);p.setStyle(Paint.Style.FILL);centerNumber(canvas,data.getString("number",""),10,46,h/2,16,focus&&theme()==5?0xff142438:0xffe1e4e7);}
                String program=data.getString("program","");float titleY=program.length()>0?h/2-3:h/2+7;
                float left=channelLogo(canvas,56,h);
                channelTitle(canvas,left,titleY,20,playing?(focus&&theme()==5?0xff1c73ee:focus?0xffeeeeef:accent()):focus&&theme()==5?0xff142438:0xffeeeeef,w-left-20-(data.getBoolean("showFavorite",true)?34:0));
                text(canvas,program,left,h/2+17,14,focus&&theme()==5?0xff4b6078:0xffaeb8bf,w-left-8-(data.getBoolean("showFavorite",true)?34:0));
                favoriteStar(canvas,w,h,focus);
            }canvas.restore();
        }
        private void glassRow(Canvas c,float w,float h){
            boolean playing=data.getBoolean("playing"),chosen=data.getBoolean("selected");
            boolean ownsFocus=!(getParent() instanceof ListView)||((ListView)getParent()).hasFocus();
            boolean favoriteFocus=!group&&data.getBoolean("showFavorite",true)&&chosen&&ownsFocus&&data.getBoolean("favoriteFocused");
            boolean focused=(isPressed()||chosen&&ownsFocus)&&!favoriteFocus;
            if(group){
                if(focused)selected(c,0,3,w-10,h-3);
                else if(chosen)box(c,0,3,w-10,h-3,0x604c4b4e,12);
                int ink=focused?0xff101112:0xfff0f0f2;String number=data.getBoolean("favoriteGroup")?"★":data.getString("number","");
                text(c,number,18,h/2+6,18,data.getBoolean("favoriteGroup")&&!focused?0xfff4d63d:ink,28);
                p.setFakeBoldText(chosen);text(c,data.getString("title",""),48,h/2+6,18,ink,w-94);p.setFakeBoldText(false);
                center(c,String.valueOf(data.getInt("count",0)),w-43,w-15,h/2+5,14,focused?0xff57585a:0xffc5c5c8);
                return;
            }
            if(focused)selected(c,8,2,w,h-2);
            int ink=focused?0xff101112:0xfff3f3f5,secondary=focused?0xff57585a:0xffbdbdc2;
            if(playing)playbackBars(c,10,h/2+9,3,ink,ink,ink);
            String number=data.getString("number","");if(number.length()==1)number="0"+number;
            centerNumber(c,number,28,68,h/2,16,ink);
            float left=channelLogo(c,72,h);
            String program=data.getString("program","");
            float trailing=data.getBoolean("showFavorite",true)?100:66;
            p.setFakeBoldText(true);channelTitle(c,left,program.length()>0?h/2-3:h/2+6,18,ink,w-left-trailing);p.setFakeBoldText(false);
            text(c,program,left,h/2+17,13,secondary,w-left-trailing);
            int sources=data.getInt("sources",1);
            float sourceRight=data.getBoolean("showFavorite",true)?w-46:w-12;
            center(c,playing?(data.getInt("source",0)+1)+"/"+sources:String.valueOf(sources),sourceRight-41,sourceRight,h/2+5,14,secondary);
            if(!data.getBoolean("showFavorite",true))return;
            if(favoriteFocus)selected(c,w-46,h/2-20,w-4,h/2+20);
            String star=data.getBoolean("favorite")?"★":"☆";
            p.setTypeface(Typeface.DEFAULT);p.setTextSize(25);p.getTextBounds(star,0,star.length(),starBounds);
            p.setColor(favoriteFocus?0xff101112:data.getBoolean("favorite")?0xfff4d63d:ink);
            c.drawText(star,w-25-(starBounds.left+starBounds.right)/2f,h/2-(starBounds.top+starBounds.bottom)/2f,p);
        }
        private float channelLogo(Canvas c,float left,float h){
            if(!data.getBoolean("showLogos"))return left;
            // Every row reserves the same slot while loading or when an icon is
            // missing. Asynchronous completion must not move channel titles.
            Bitmap bitmap=data.getParcelable("logo");if(bitmap==null||bitmap.isRecycled())return left+64;
            float factor=Math.min(56f/bitmap.getWidth(),Math.min(32f,h-8)/bitmap.getHeight());
            float width=bitmap.getWidth()*factor,height=bitmap.getHeight()*factor;
            rect.set(left+(56-width)/2,(h-height)/2,left+(56+width)/2,(h+height)/2);
            p.setColor(Color.WHITE);p.setFilterBitmap(true);c.drawBitmap(bitmap,null,rect,p);return left+64;
        }
        private void referenceRow(Canvas c,float w,float h,boolean sel,boolean playing){
            if(sel)selected(c,theme()==2?8:0,theme()==2?1:0,w-(theme()==2?8:0),h-(theme()==2?1:0));
            float badgeLeft=theme()==2?24:theme()==3?6:17,badgeWidth=theme()==2?42:theme()==3?34:41;
            if(playing){
                float x=badgeLeft+(theme()==3?10:theme()==4?14:12),cy=h/2;
                playbackBars(c,x,cy+11,4,theme()==2?0xfff8ee2b:0xfff1f3f5,theme()==2?0xffff8585:0xfff1f3f5,theme()==2?0xff00dc26:0xfff1f3f5);
            }else{
                p.setColor(theme()==3?0xffdddddf:0x6485939b);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(theme()==3?.6f:1);
                rect.set(badgeLeft,h/2-10,badgeLeft+badgeWidth,h/2+10);float r=theme()==2?10:theme()==3?1.5f:0;
                c.drawRoundRect(rect,r,r,p);p.setStyle(Paint.Style.FILL);
                centerNumber(c,data.getString("number",""),badgeLeft,badgeLeft+badgeWidth,h/2,theme()==3?13:16,0xffe1e4e7);
            }
            float left=channelLogo(c,theme()==2?82:theme()==3?53:73,h),available=w-left-(theme()==2?12:8)-(data.getBoolean("showFavorite",true)?34:0);
            String program=data.getString("program","");boolean withProgram=program.length()>0;
            channelTitle(c,left,withProgram?h/2-(theme()==2?2:5):h/2+(theme()==4?8:7),20,playing&&!sel?accent():0xffeeeeef,available);
            if(withProgram)text(c,program,left,h/2+(theme()==3?20:17),14,0xffbcbfc3,available);
            favoriteStar(c,w,h,sel);
        }
        private void favoriteStar(Canvas c,float w,float h,boolean rowFocused){
            if(!data.getBoolean("showFavorite",true))return;
            boolean focus=data.getBoolean("favoriteFocused")&&getParent() instanceof ListView&&((ListView)getParent()).hasFocus();
            if(focus)selected(c,w-34,h/2-18,w-2,h/2+18);
            int color=(focus||rowFocused)&&theme()==5?0xff142438:data.getBoolean("favorite")?accent():0xffeeeeef;
            text(c,data.getBoolean("favorite")?"★":"☆",w-29,h/2+7,22,color,27);
        }
    }
    /** Programme rows share channel selection geometry, palette and pitch; no timers. */
    private final class EpgRow extends Drawing {
        final float d;Bundle data;
        EpgRow(Context c,Bundle state){super(c);d=scale(c);bind(state);setLayoutParams(new AbsListView.LayoutParams(-1,Math.round(rowHeight()*d)));}
        protected void onMeasure(int ws,int hs){
            if(getParent() instanceof ListView){
                ListView list=(ListView)getParent();
                int available=list.getHeight()-2*list.getPaddingTop(),divider=list.getDividerHeight();
                if(available>0)hs=MeasureSpec.makeMeasureSpec(Math.max(1,fittedRowHeight(
                        available+divider,Math.round(rowHeight()*d)+divider)-divider),MeasureSpec.EXACTLY);
            }
            super.onMeasure(ws,hs);
        }
        void bind(Bundle state){data=state;setContentDescription(state.getString("time","")+" "+state.getString("title",""));invalidate();}
        protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        protected void onDraw(Canvas canvas){
            canvas.save();canvas.scale(d,d);float w=getWidth()/d,h=getHeight()/d;
            boolean focus=isPressed()||isSelected()&&(!(getParent() instanceof ListView)||((ListView)getParent()).hasFocus()),current=data.getBoolean("current");
            float inset=theme()==2?1:theme()==5?3:0;
            if(focus)selected(canvas,theme()==2?8:0,inset,w-(theme()==2?8:0),h-inset);
            int title=focus&&(theme()==5||theme()==6)?0xff142438:focus?Color.WHITE:0xffeeeeef;
            int secondary=focus&&(theme()==5||theme()==6)?0xff4b6078:focus?Color.WHITE:0xffbcbfc3;
            float left=theme()==2?24:theme()==3?12:16,right=theme()==2?24:12;
            text(canvas,data.getString("time",""),left,h/2-7,14,current&&!focus?accent():secondary,w-left-right);
            text(canvas,data.getString("title",""),left,h/2+(theme()==5?12:17),20,title,w-left-right);
            canvas.restore();
        }
    }
    private final class Card extends Drawing {
        Bundle data;String[] programs=new String[0];final Rect identityBounds=new Rect();Card(Context c,Bundle b){super(c);bind(b);}
        void bind(Bundle b){data=b;String epg=b.getString("epg","");programs=epg.length()==0?new String[0]:epg.split("\n");if(theme()<=4)for(int i=0;i<programs.length;i++){int dash=programs[i].indexOf('–'),space=programs[i].indexOf(' ');if(dash==5&&space>dash)programs[i]=programs[i].substring(0,dash)+programs[i].substring(space);}setContentDescription(b.getString("title","")+" "+b.getString("epg","")+" "+b.getString("status",""));invalidate();}
        protected void onMeasure(int ws,int hs){float d=scale(getContext());setMeasuredDimension(resolveSize(Math.round((theme()==3?600:theme()<=4?720:680)*d),ws),resolveSize(Math.round(124*d),hs));}
        protected void onDraw(Canvas canvas){float w=getWidth(),h=getHeight(),d=scale(getContext());canvas.save();canvas.scale(d,d);w/=d;h/=d;
            if(theme()==5||theme()==6){
                box(canvas,0,0,w,h,panel(),22);
                String number=data.getString("number","").replace(" |","");
                // Channel identity follows the reference: large number above a
                // smaller name, both centered in the left column.
                centerIdentity(canvas,number,16,234,h/2-3,30,0xfff5f7fa);
                centerIdentity(canvas,data.getString("title",""),16,234,h/2+25,18,0xfff5f7fa);
                line(canvas,250,14,250,h-14);
                cardLines(canvas,270,w-294,d);
                canvas.restore();return;
            }
            box(canvas,0,0,w,h,theme()==2?0xe61b3335:theme()==3?panel():0x99252c38,theme()==4?0:h/2);
            float split=theme()==3?230:theme()==2?294:194;
            String title=data.getString("title",""),number=data.getString("number","").replace(" |","");if(theme()==3&&number.length()>0&&number.length()<4)number=("0000"+number).substring(number.length());
            if(theme()==2){center(canvas,number,31,split-19,54,40,0xffefefef);center(canvas,title,31,split-19,89,22,0xffb8c4c5);}
            else if(theme()==3){center(canvas,title,26,split-14,50,20,0xffeeeeef);center(canvas,number,26,split-14,81,18,0xffcccccf);}
            else{p.setFakeBoldText(true);centerIdentity(canvas,number,16,split-16,47,38,0xffffffff);p.setFakeBoldText(false);centerIdentity(canvas,title,16,split-16,81,22,0xffeeeeef);}
            line(canvas,split,theme()==3?23:theme()==2?12:7,split,h-(theme()==3?23:theme()==2?12:7));
            float left=split+(theme()==4?8:theme()==2?10:16),available=w-left-16;
            cardLines(canvas,left,available,d);
            canvas.restore();
        }
        private void cardLines(Canvas canvas,float left,float available,float d){
            // Center the complete four-line block using font metrics rather than
            // baseline offsets, so visible top/bottom padding is equal.
            p.setTypeface(Typeface.DEFAULT);p.setTextSize(18);
            float top=p.ascent();p.setTextSize(16);float bottom=p.descent();
            float first=(getHeight()/d-(72+bottom-top))/2-top;
            text(canvas,data.getString("status",""),left,first,18,0xffeeeeef,available);
            text(canvas,programs.length>0?programs[0]:data.getString("info",""),left,first+24,16,0xffcbd0d5,available);
            text(canvas,programs.length>1?programs[1]:"",left,first+48,16,0xffcbd0d5,available);
            float x=left,y=first+72;
            x=hint(canvas,x,y,0,"设置",d);x=hint(canvas,x,y,1,"列表",d);x=hint(canvas,x,y,2,"换源",d);
            text(canvas,data.getString("dateTime",""),x+6,y,14,0xffafb6be,available-(x-left)-6);
        }
        private void centerIdentity(Canvas c,String value,float l,float r,float y,float size,int color){
            if(value==null||value.length()==0)return;
            p.setTypeface(Typeface.DEFAULT);p.setTextSize(size);p.setColor(color);p.setStyle(Paint.Style.FILL);
            if(p.measureText(value)>r-l){int n=p.breakText(value,true,Math.max(0,r-l-p.measureText("…")),null);value=value.substring(0,n)+"…";}
            // Center the visible glyph, including asymmetric side bearings (for example "1").
            p.getTextBounds(value,0,value.length(),identityBounds);
            c.drawText(value,(l+r-identityBounds.left-identityBounds.right)/2,y,p);
        }
        private float hint(Canvas c,float x,float y,int kind,String title,float d){
            // Pre-28 HWUI can cache a scaled Path at its unscaled resolution.
            // Draw simple primitives at final pixel size, with aligned stroke edges.
            int stroke=Math.max(1,Math.round(d)),color=theme()==2?0xff29a6de:0xffafb6be;
            float phase=(stroke&1)*.5f;
            float cx=Math.round((x+7)*d-phase)+phase,cy=Math.round((y-6.5f)*d-phase)+phase;
            int saved=c.save();c.scale(1/d,1/d);
            p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.MITER);
            if(kind!=2)c.drawCircle(cx,cy,Math.max(2,Math.round(6.75f*d-stroke*.5f)),p);
            if(kind==0){int half=Math.max(2,Math.round(3.3f*d)),gap=Math.max(2,Math.round(2.5f*d));for(int i=-1;i<=1;i++)c.drawLine(cx-half,cy+i*gap,cx+half,cy+i*gap,p);}
            else if(kind==1){
                int half=Math.max(2,Math.round(2.6f*d)),stem=Math.max(1,Math.round(d)),end=Math.round(4.4f*d);
                rect.set(cx-Math.round(4.5f*d),cy-half,cx-Math.max(1,Math.round(.7f*d)),cy+half);c.drawOval(rect,p);
                c.drawLine(cx+stem,cy-half,cx+stem,cy+half,p);
                c.drawLine(cx+end,cy-half,cx+stem,cy,p);c.drawLine(cx+stem,cy,cx+end,cy+half,p);
            }else{
                int inner=Math.round(2*d),outer=Math.round(6*d),half=Math.max(2,Math.round(4*d));
                c.drawLine(cx-inner,cy-half,cx-outer,cy,p);c.drawLine(cx-outer,cy,cx-inner,cy+half,p);
                c.drawLine(cx+inner,cy-half,cx+outer,cy,p);c.drawLine(cx+outer,cy,cx+inner,cy+half,p);
            }
            c.restoreToCount(saved);
            p.setStrokeCap(Paint.Cap.BUTT);p.setStrokeJoin(Paint.Join.MITER);p.setStyle(Paint.Style.FILL);
            text(c,title,x+19,y,16,0xffafb6be,34);return x+61;
        }
    }
    private final class Menu extends LinearLayout implements MenuWidth {
        final Events events;final ListView categories,options;final float d;final String[] titles;int category=-1,categoryPitch;Bundle choices;boolean binding;
        float textSize;
        final android.text.TextPaint ellipsisPaint=new android.text.TextPaint();
        Menu(Context c,Bundle state,Events e){super(c);submenu=state.getBoolean("submenu");focusOptions=state.getBoolean("focusOptions");remoteNavigation=state.getBoolean("remoteInput");events=e;d=scale(c);titles=state.getStringArray("categories");setOrientation(HORIZONTAL);background(this,panel(),radius()*d);
            textSize=menuFontSize();
            options=new ListView(c);categories=new ListView(c);
            for(ListView list:new ListView[]{options,categories}){list.setDivider(null);list.setSelector(new ColorDrawable(Color.TRANSPARENT));list.setCacheColorHint(Color.TRANSPARENT);list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);list.setFocusableInTouchMode(true);}
            GradientDrawable right=shape(theme()==5?0x40101a28:theme()==2?0xa9001c1b:theme()==3?0x40202430:0x7028313e,0);float rr=radius()*d;right.setCornerRadii(new float[]{0,0,rr,rr,rr,rr,0,0});categories.setBackgroundDrawable(right);
            addView(options,new LinearLayout.LayoutParams(0,-1,theme()==3?175:theme()==4?142:1));addView(categories,new LinearLayout.LayoutParams(0,-1,theme()==2?1.08f:theme()==3?151:theme()==4?133:1));
            categories.setPadding(0,Math.round((theme()==2?17:theme()==3?20:0)*d),0,0);
            categories.setAdapter(new MenuAdapter(c,titles,true,-1));
            categories.setOnHoverListener((view,event)->true);
            categories.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){choose(pos);}public void onNothingSelected(AdapterView<?> p){}});
            categories.setOnItemClickListener((p,v,pos,id)->{choose(pos);if(choices.getBoolean("openOnClick")){events.action(category,-1);return;}options.requestFocus();options.setSelection(Math.max(choices.getInt("firstEnabled",0),choices.getInt("selected",0)));});
            options.setOnItemClickListener((p,v,pos,id)->{events.action(category,pos);refresh();});
            View.OnFocusChangeListener focus=(v,hasFocus)->{invalidateRows(options);invalidateRows(categories);};
            options.setOnFocusChangeListener(focus);categories.setOnFocusChangeListener(focus);
            options.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){invalidateRows(options);}public void onNothingSelected(AdapterView<?> p){invalidateRows(options);}});
            int initial=Math.max(0,Math.min(titles.length-1,state.getInt("category",0)));
            categories.setSelection(initial);choose(initial);
            if(focusOptions){options.setSelection(Math.max(choices.getInt("firstEnabled",0),choices.getInt("selected",0)));options.requestFocus();}
            else categories.requestFocus();
        }
        public int preferredWidth(int height){
            Paint measure=new Paint(3);
            float font=menuTextSize(menuCategoryPitch(d,height,titles.length)/d);
            measure.setTextSize(font);
            float left=0,right=0;
            for(String title:titles)if(title!=null)right=Math.max(right,measure.measureText(title));
            // Fix the drawer width from all categories before showing it. Switching
            // between short and long submenus must never resize the popup.
            for(int i=0;i<titles.length;i++) {
                Bundle all=events.options(i);
                String[] items=all==null?null:all.getStringArray("items");
                if(items!=null)for(String title:items){
                    if(title==null)continue;
                    boolean radio=all.getInt("selected",-1)>=0||title.endsWith(" ●")||title.endsWith(" ○");
                    if(title.endsWith(" ●")||title.endsWith(" ○"))title=title.substring(0,title.length()-2);
                    left=Math.max(left,Math.min(font*6,measure.measureText(title)+(radio?20:0)));
                }
            }
            // Restore any host-side option snapshot for the active category.
            if(category>=0)events.options(category);
            // Both columns use the same outer inset; reserve radio space only
            // for rows that actually draw a mark.
            float padding=theme()==2?28:24;
            left+=padding;right+=padding+8;
            // Size each column independently: a long option must not inflate the
            // category column through equal layout weights.
            ((LinearLayout.LayoutParams)options.getLayoutParams()).weight=left;
            ((LinearLayout.LayoutParams)categories.getLayoutParams()).weight=right;
            return (int)Math.ceil((left+right)*d);
        }
        boolean remoteNavigation,focusOptions,submenu;
        int naturalPitch(){return menuPitch(d);}
        void optionPadding(int height){
            int count=options.getCount(),room=Math.max(0,height-count*naturalPitch());
            int top=room/2;
            options.setPadding(0,top,0,room-top);
        }
        protected void onMeasure(int widthSpec,int heightSpec){
            int height=MeasureSpec.getSize(heightSpec);
            if(height>0){
                // Fit the category column inside this popup, including enlarged UI presets.
                // Submenus may scroll, but the first/last category must remain fully visible.
                int top=menuTop(d,height,titles.length);
                categoryPitch=menuCategoryPitch(d,height,titles.length);
                // One typography size for both columns and every option. The
                // category column may be compressed by a large UI preset; its
                // siblings must not keep a different, natural-row font size.
                float nextTextSize=menuTextSize(categoryPitch/d);
                if(Math.abs(textSize-nextTextSize)>.001f){textSize=nextTextSize;invalidateRows(categories);invalidateRows(options);}
                int bottom=Math.max(0,height-top-titles.length*categoryPitch);
                categories.setPadding(0,top,0,bottom);
                for(int i=0;i<categories.getChildCount();i++){
                    View child=categories.getChildAt(i);
                    if(child.getLayoutParams().height!=categoryPitch){child.getLayoutParams().height=categoryPitch;child.requestLayout();}
                }
                optionPadding(height);
            }
            super.onMeasure(widthSpec,heightSpec);
        }
        void invalidateRows(ListView list){for(int i=0;i<list.getChildCount();i++)list.getChildAt(i).invalidate();}
        void inputMode(boolean remote){
            if(remoteNavigation!=remote){remoteNavigation=remote;invalidateRows(categories);invalidateRows(options);}
            if(getContext() instanceof InputMode)((InputMode)getContext()).themeInputMode(remote);
        }
        public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN)inputMode(false);return super.dispatchTouchEvent(event);}
        protected boolean onRequestFocusInDescendants(int direction,Rect previous){return focusOptions?options.requestFocus(direction,previous):categories.requestFocus(direction,previous);}
        void choose(int pos){if(binding||pos==category)return;category=pos;populate(false);}
        void refresh(){populate(true);}
        void populate(boolean preserve){if(category<0)return;binding=true;choices=events.options(category);if(choices==null)choices=new Bundle();String[] items=choices.getStringArray("items");if(items==null)items=new String[0];int selected=choices.getInt("selected",-1);
            if(preserve&&options.getAdapter() instanceof MenuAdapter){
                // Let ListView retain its visible-row offset and DPAD cursor.
                // Replacing the adapter/setSelection anchors the selected row at
                // the top, including on the deferred group-settings refresh.
                MenuAdapter adapter=(MenuAdapter)options.getAdapter();
                adapter.items=items;adapter.checked=selected;adapter.notifyDataSetChanged();
            }else{
                options.setAdapter(new MenuAdapter(getContext(),items,false,selected));
                if(items.length>0)options.setSelection(Math.max(choices.getInt("firstEnabled",0),Math.min(items.length-1,selected)));
            }
            optionPadding(getHeight());
            ((BaseAdapter)categories.getAdapter()).notifyDataSetChanged();binding=false;
        }
        // PopupWindow's decor consumes BACK before dispatchKeyEvent on several API levels.
        // Handle it in the pre-IME stage so the submenu can return to its category first.
        public boolean dispatchKeyEventPreIme(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_BACK)return dispatchKeyEvent(e);return super.dispatchKeyEventPreIme(e);}
        public boolean dispatchKeyEvent(KeyEvent e){int key=e.getKeyCode();
            if(e.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER||key==KeyEvent.KEYCODE_MENU||key==KeyEvent.KEYCODE_BACK))inputMode(true);
            if(key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_MENU){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){if(key==KeyEvent.KEYCODE_BACK&&!submenu&&options.hasFocus())categories.requestFocus();else events.dismiss();}return true;}
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){if(e.getAction()==KeyEvent.ACTION_DOWN){if(key==KeyEvent.KEYCODE_DPAD_LEFT){options.requestFocus();if(options.getSelectedItemPosition()<choices.getInt("firstEnabled",0))options.setSelection(choices.getInt("firstEnabled",0));}else{categories.requestFocus();categories.setSelection(category);}}return true;}
            return super.dispatchKeyEvent(e);
        }
        private final class MenuAdapter extends BaseAdapter {
            final Context context;String[] items;final boolean main;int checked;
            MenuAdapter(Context c,String[] values,boolean m,int selected){
                context=c;items=values==null?new String[0]:values.clone();main=m;checked=selected;
                // A missing option title must not crash ListView binding (Bugly 1262).
                for(int i=0;i<items.length;i++)if(items[i]==null)items[i]="";
            }
            public boolean areAllItemsEnabled(){return main||choices.getInt("firstEnabled",0)==0;}
            public boolean isEnabled(int position){return main||position>=choices.getInt("firstEnabled",0);}
            public int getCount(){return items.length;}public Object getItem(int i){return items[i];}public long getItemId(int i){return i;}
            public View getView(int i,View old,ViewGroup parent){MenuRow row=old instanceof MenuRow?(MenuRow)old:new MenuRow(context);row.getLayoutParams().height=main&&categoryPitch>0?categoryPitch:naturalPitch();row.label=items[i];row.main=main;row.index=i;row.radio=!main&&(checked>=0||items[i].endsWith(" ●")||items[i].endsWith(" ○"));row.checked=!main&&(checked==i||items[i].endsWith(" ●"));String title=row.radio&&(items[i].endsWith(" ●")||items[i].endsWith(" ○"))?items[i].substring(0,items[i].length()-2):items[i];row.setContentDescription(title+(row.radio?(row.checked?"，已选中":"，未选中"):""));row.invalidate();return row;}
        }
        private final class MenuRow extends Drawing {
            String label;boolean main,checked,radio;int index;
            MenuRow(Context c){super(c);setLayoutParams(new AbsListView.LayoutParams(-1,naturalPitch()));}
            protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
            protected void onDraw(Canvas canvas){canvas.save();canvas.scale(d,d);float w=getWidth()/d,h=getHeight()/d;
                boolean active=main&&index==category;
                // Remote focus owns one background; checked settings keep text/radio marks.
                boolean optionFocus=remoteNavigation&&options.hasFocus();
                boolean focus=main?active&&!optionFocus:optionFocus&&index>=choices.getInt("firstEnabled",0)&&options.getSelectedItemPosition()==index;
                if(focus){
                    if(theme()==2||theme()==5||theme()==6){
                        // Expand only the backdrop around the fixed text center;
                        // row spacing and the full-row touch/focus target stay stable.
                        float inset=h*.08f,horizontal=theme()==2?8:0;
                        selected(canvas,horizontal,inset,w-horizontal,h-inset,h*.18f);
                    }
                    else if(theme()==4)selected(canvas,8,3,w-8,h-3);
                    else selected(canvas,0,0,w,h);
                }
                int color=theme()==6?(focus?0xff142438:0xffe8e8ed):theme()==5&&focus?0xff142438:(active||!main&&checked)&&!focus?accent():0xffe1e3e6;
                // Selection, label length and radio decoration never change
                // the typeface, weight, size or baseline of a menu sibling.
                float size=textSize;
                p.setTypeface(Typeface.DEFAULT);p.setFakeBoldText(false);p.setTextScaleX(1f);p.setTextSkewX(0f);p.setTextSize(size);
                float baseline=(h-p.ascent()-p.descent())/2;
                if(radio){
                    String title=label.endsWith(" ●")||label.endsWith(" ○")?label.substring(0,label.length()-2):label;
                    float inset=(theme()==2?14:8)+(main?4:0);
                    p.setTextSize(size);float total=Math.min(p.measureText(title),Math.max(0,w-inset*2-20))+20,x=(w-total)/2;
                    text(canvas,title,x,baseline,size,color,total-20);float cx=x+total-7,cy=h/2;
                    // Draw in device pixels to avoid subpixel stroke scaling on old HWUI.
                    int stroke=Math.max(1,Math.round(1.5f*d));float phase=(stroke&1)*.5f;
                    float pixelX=Math.round(cx*d-phase)+phase,pixelY=Math.round(cy*d-phase)+phase;
                    int saved=canvas.save();canvas.scale(1/d,1/d);
                    p.setColor(theme()==6&&!focus?0xffb5b6bd:color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);
                    canvas.drawCircle(pixelX,pixelY,Math.max(3,Math.round(6*d)),p);
                    p.setStyle(Paint.Style.FILL);
                    if(checked)canvas.drawCircle(pixelX,pixelY,Math.max(1,Math.round(2.7f*d)),p);
                    canvas.restoreToCount(saved);
                }else{
                    // Keep the common font for long labels. Middle ellipsis
                    // also retains the version number at the end of the row.
                    String fitted=label;
                    float inset=(theme()==2?14:8)+(main?4:0);
                    if(p.measureText(label)>w-inset*2){
                        ellipsisPaint.set(p);float room=Math.max(0,w-inset*2);
                        if(!main&&label.startsWith("网页电视 ")){
                            String version=label.substring(label.lastIndexOf(' '));
                            float prefixRoom=Math.max(0,room-p.measureText(version));
                            fitted=android.text.TextUtils.ellipsize("网页电视",ellipsisPaint,prefixRoom,android.text.TextUtils.TruncateAt.END).toString()+version;
                        }else fitted=android.text.TextUtils.ellipsize(label,ellipsisPaint,room,android.text.TextUtils.TruncateAt.MIDDLE).toString();
                    }
                    center(canvas,fitted,inset,w-inset,baseline,size,color);
                }canvas.restore();
            }
        }
    }
    private final class Exit extends Drawing {
        final Events events;final Drawable icon;int selected=1;
        Exit(Context c,Events e){super(c);events=e;icon=c.getResources().getDrawable(c.getApplicationInfo().icon);setFocusableInTouchMode(true);requestFocus();setContentDescription("退出确认，左侧功能设置，右侧确认退出");}
        float contentOffsetY(){return theme()==4?-3:-1;}
        float buttonLeft(int i){return theme()==4?310+i*180:267+i*234;}
        float buttonTop(){return theme()==4?386:300;}
        float buttonWidth(){return theme()==4?160:192;}
        float dialogScale(){return .76f*Math.min(getWidth()/960f,getHeight()/540f);}
        // Compact the panel without shrinking text or bypassing the host's size preset.
        float fontSize(){float ui=scale(getContext());int inset=Math.round((theme()==2?8:theme()==4?0:22)*ui);
            return menuTextSize(menuCategoryPitch(ui,Math.max(1,getHeight()-2*inset),8)/ui)*ui/dialogScale();}
        protected void onDraw(Canvas canvas){float d=dialogScale(),x=(getWidth()-960*d)/2,y=(getHeight()-540*d)/2;canvas.save();canvas.translate(x,y+contentOffsetY()*d);canvas.scale(d,d);
            if(theme()==4){box(canvas,161,82,799,464,0xcc202733,0);icon.setBounds(432,139,528,235);icon.draw(canvas);center(canvas,"网页电视",180,780,286,fontSize()*1.35f,0xffeeeeef);center(canvas,"感谢使用，精彩下次继续",180,780,326,fontSize(),0xffbac3cf);}
            else{box(canvas,178,105,782,437,panel(),theme()==2?18:8);center(canvas,"您确认现在退出吗？",210,750,223,fontSize(),0xffeeeeef);}
            for(int i=0;i<2;i++){float l=buttonLeft(i),top=buttonTop(),width=buttonWidth(),height=theme()==4?54:60;if(i==selected){if(theme()==4)box(canvas,l,top,l+width,top+height,0xff008cff,3);else selected(canvas,l,top,l+width,top+height);}else box(canvas,l,top,l+width,top+height,theme()==2?0xff777777:0x663f3f3f,theme()==2?30:3);float size=fontSize();p.setTextSize(size);float baseline=top+(height-p.ascent()-p.descent())/2;center(canvas,i==0?"功能设置":"确认退出",l,l+width,baseline,size,(theme()==5||theme()==6)&&i==selected?0xff142438:0xffeeeeef);}canvas.restore();}
        public boolean onKeyDown(int key,KeyEvent e){if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){selected=key==KeyEvent.KEYCODE_DPAD_LEFT?0:1;invalidate();return true;}if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){events.action(-1,selected);return true;}if(key==KeyEvent.KEYCODE_BACK){if(e.getRepeatCount()==0)events.action(-1,1);return true;}return super.onKeyDown(key,e);}
        public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()==android.view.MotionEvent.ACTION_UP){float d=dialogScale(),x=(e.getX()-(getWidth()-960*d)/2)/d,y=(e.getY()-(getHeight()-540*d)/2)/d-contentOffsetY();boolean hit=false;for(int i=0;i<2;i++)if(y>=buttonTop()&&y<=buttonTop()+60&&x>=buttonLeft(i)&&x<=buttonLeft(i)+buttonWidth()){selected=i;events.action(-1,selected);hit=true;break;}if(!hit&&(y<(theme()==4?82:105)||y>(theme()==4?464:437)))events.dismiss();performClick();}return true;}
        public boolean performClick(){super.performClick();return true;}
    }
    private static final String[] GUIDE_LABELS={"上下键","左右键","OK键","菜单键"};
    private static final String[] GUIDE_ACTIONS={"切换频道","换源 / 快进","频道列表","播放设置"};
    private final class Guide extends Drawing {
        final Events events;Guide(Context c,Events e){super(c);events=e;setFocusableInTouchMode(true);requestFocus();}
        final Shader glow=new RadialGradient(0,0,53,new int[]{theme()==2?0xc000d5ff:0xb00065ff,0x000065ff},new float[]{.55f,1},Shader.TileMode.CLAMP);
        final Typeface numberFace=Typeface.create(theme()==2?"sans-serif-light":"sans-serif",theme()==2?Typeface.NORMAL:Typeface.BOLD);
        protected void onDraw(Canvas canvas){canvas.drawColor(theme()==2?0xff303030:0xbb000000);float d=Math.min(getWidth()/960f,getHeight()/540f);canvas.save();canvas.translate((getWidth()-960*d)/2,(getHeight()-540*d)/2);canvas.scale(d,d);
            for(int i=0;i<4;i++){
                float cx=theme()==2?236+i*166:201+i*184,top=theme()==2?122:142,bottom=theme()==2?376:373,cy=theme()==2?232:251;
                p.setColor(0xffeeeeef);p.setTextSize(36);p.setTypeface(numberFace);String n="0"+(i+1);canvas.drawText(n,cx-p.measureText(n)/2,theme()==2?102:114,p);
                p.setTypeface(Typeface.DEFAULT);p.setColor(0xff9b9b9b);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);rect.set(cx-51,top,cx+51,bottom);canvas.drawRoundRect(rect,theme()==2?51:15,theme()==2?51:15,p);
                if(theme()==2){canvas.drawCircle(cx-27,cy-55,10,p);canvas.drawCircle(cx+27,cy-55,10,p);rect.set(cx-11,cy+64,cx+11,cy+110);canvas.drawRoundRect(rect,11,11,p);}
                else{canvas.drawCircle(cx-22,cy-66,10,p);rect.set(cx-27,cy-71,cx-17,cy-61);canvas.drawArc(rect,-45,270,false,p);canvas.drawLine(cx-22,cy-73,cx-22,cy-66,p);}
                float menuX=cx-(theme()==2?27:21),buttonY=cy+61,backX=cx+(theme()==2?27:21);
                canvas.drawCircle(menuX,buttonY,10,p);canvas.drawCircle(backX,buttonY,10,p);
                canvas.drawCircle(cx,cy,38,p);canvas.drawCircle(cx,cy,20,p);p.setStyle(Paint.Style.FILL);
                canvas.save();canvas.translate(cx,cy);p.setShader(glow);
                if(i<2){rect.set(-53,-53,53,53);canvas.drawArc(rect,i==0?225:135,90,true,p);canvas.drawArc(rect,i==0?45:315,90,true,p);}
                else{canvas.save();if(i==3)canvas.translate(menuX-cx,buttonY-cy);canvas.scale(i==2?.7f:.42f,i==2?.7f:.42f);canvas.drawCircle(0,0,53,p);canvas.restore();}
                p.setShader(null);canvas.restore();
                int fill=theme()==2?0xffb4f3ff:0xff1c73ee;
                if(i<2){p.setColor(fill);rect.set(cx-38,cy-38,cx+38,cy+38);canvas.drawArc(rect,i==0?225:135,90,true,p);canvas.drawArc(rect,i==0?45:315,90,true,p);box(canvas,cx-20,cy-20,cx+20,cy+20,0xff303030,20);}
                else if(i==2)box(canvas,cx-20,cy-20,cx+20,cy+20,fill,20);
                else box(canvas,menuX-10,buttonY-10,menuX+10,buttonY+10,fill,10);
                p.setColor(0xffc8eff9);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);
                if(i<2){rect.set(cx-38,cy-38,cx+38,cy+38);canvas.drawArc(rect,i==0?225:135,90,false,p);canvas.drawArc(rect,i==0?45:315,90,false,p);canvas.drawCircle(cx,cy,20,p);}else if(i==2)canvas.drawCircle(cx,cy,20,p);else canvas.drawCircle(menuX,buttonY,10,p);
                p.setStyle(Paint.Style.FILL);center(canvas,"OK",cx-20,cx+20,cy+5,12,i==2&&theme()==2?0xff303030:0xffcccccc);
                p.setColor(i==0&&theme()==2?0xff303030:0xffbdbdbd);p.setStrokeWidth(1.1f);canvas.drawLine(cx-6,cy-25,cx,cy-31,p);canvas.drawLine(cx,cy-31,cx+6,cy-25,p);canvas.drawLine(cx-6,cy+25,cx,cy+31,p);canvas.drawLine(cx,cy+31,cx+6,cy+25,p);
                p.setColor(i==1&&theme()==2?0xff303030:0xffbdbdbd);canvas.drawLine(cx-26,cy-6,cx-32,cy,p);canvas.drawLine(cx-32,cy,cx-26,cy+6,p);canvas.drawLine(cx+26,cy-6,cx+32,cy,p);canvas.drawLine(cx+32,cy,cx+26,cy+6,p);
                p.setColor(i==3&&theme()==2?0xff29a6de:0xffcccccc);for(int k=-1;k<=1;k++)canvas.drawLine(menuX-5,buttonY+k*3,menuX+5,buttonY+k*3,p);
                if(theme()!=2){p.setColor(0xff999999);canvas.drawLine(backX+4,buttonY-2,backX-4,buttonY-2,p);canvas.drawLine(backX-4,buttonY-2,backX-1,buttonY-5,p);canvas.drawLine(backX-4,buttonY-2,backX-1,buttonY+1,p);}
                p.setFakeBoldText(true);center(canvas,GUIDE_LABELS[i],cx-90,cx+90,theme()==2?416:420,theme()==2?22:24,0xffeeeeef);p.setFakeBoldText(false);center(canvas,theme()==2&&i==2?"弹出频道列表":GUIDE_ACTIONS[i],cx-90,cx+90,theme()==2?444:451,20,0xffbfbfbf);
            }canvas.restore();}
        public boolean onKeyDown(int key,KeyEvent e){if(key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){events.dismiss();return true;}return super.onKeyDown(key,e);}
        public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()==android.view.MotionEvent.ACTION_UP){events.dismiss();performClick();}return true;}
        public boolean performClick(){super.performClick();return true;}
    }
}
