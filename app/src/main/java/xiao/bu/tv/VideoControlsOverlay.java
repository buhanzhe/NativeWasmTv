package xiao.bu.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ClipDrawable;
import android.media.AudioManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns the whole touch sequence while visible; no gesture reaches the player. */
final class VideoControlsOverlay extends FrameLayout {
    interface Playback extends xiao.bu.tv.jar.ChannelPlugin.PlayerControls {
        boolean playing();
        boolean completed();
        String status();
        boolean loading();
        long duration();
        long position();
        void toggle();
        void seek(long position);
        String title();
        boolean speedSupported();
        boolean hasTracks();
        float speed();
        void speed(float value);
        boolean catchup();
        void live();
    }
    private final Activity activity;
    private final xiao.bu.tv.jar.ChannelPlugin.PlayerControls playback;
    private final boolean touchAdjustments;
    private final Icon toggle, rewind, forward;
    private final LinearLayout controlBar;
    private android.graphics.Bitmap glassImage;
    private GradientDrawable glassTint;
    private final TextView time, remaining, quality, feedback, speed, title, episodes, tracks;
    private Dialog trackDialog;
    private final TextView returnLive;
    private final LinearLayout speedOptions;
    private final SeekBar seek;
    private final LinearLayout loadPanel;
    private final LoadingSpinnerView loadSpinner;
    private final TextView loadText;
    private final Paint shade = new Paint();
    private String loadStatus="";
    private float downX, downY, startBrightness;
    private int startVolume;
    private long startPosition, gestureDuration, seekPosition;
    private int gestureAxis;
    private boolean moved, dragging;
    private boolean remoteNavigation, skipConfirmUp;
    private final RemoteSeekState remoteSeek = new RemoteSeekState(ViewConfiguration.getLongPressTimeout());
    private final Runnable remoteRepeat = new Runnable() {
        public void run() {
            if (!remoteSeek.active()) return;
            previewRemoteSeek(); postDelayed(this, 120L);
        }
    };
    private final Runnable dismiss = new Runnable() { public void run() {
        if (remoteSeek.active() || dragging || trackDialog != null) return;
        if (playback.completed() || !playback.playing() && !playback.loading()
                || speedOptions.getVisibility() == VISIBLE) return;
        if (playback.loading()) { postDelayed(this,1000L); return; }
        hide();
    } };
    private final Runnable tick = new Runnable() {
        public void run() {
            if (getVisibility() != VISIBLE) return;
            refresh();
            removeCallbacks(this);
            if (!playback.completed()) postDelayed(this, 500L);
        }
    };

    VideoControlsOverlay(Activity owner, xiao.bu.tv.jar.ChannelPlugin.PlayerControls source, boolean television) {
        super(owner);
        activity = owner;
        playback = source;
        touchAdjustments = !television;
        setWillNotDraw(false);
        setClickable(true);
        setVisibility(GONE);
        loadPanel=new LinearLayout(owner);loadPanel.setOrientation(LinearLayout.HORIZONTAL);loadPanel.setGravity(Gravity.CENTER);loadPanel.setPadding(dp(14),dp(10),dp(14),dp(10));
        GradientDrawable loadingBackground=new GradientDrawable();loadingBackground.setColor(0xcc101820);loadingBackground.setCornerRadius(dp(10));loadPanel.setBackgroundDrawable(loadingBackground);
        loadSpinner=new LoadingSpinnerView(owner);loadPanel.addView(loadSpinner,new LinearLayout.LayoutParams(dp(24),dp(24)));
        loadText=label("",16);loadText.setMaxLines(3);loadText.setPadding(dp(12),0,0,0);loadPanel.addView(loadText,new LinearLayout.LayoutParams(-2,-2));
        LayoutParams loadBounds=new LayoutParams(-2,-2,Gravity.CENTER);loadBounds.leftMargin=dp(24);loadBounds.rightMargin=dp(24);addView(loadPanel,loadBounds);loadPanel.setVisibility(GONE);
        controlBar = new LinearLayout(owner) {
            private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
            private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF bounds = new RectF();
            private final android.graphics.Matrix transform=new android.graphics.Matrix();
            private android.graphics.Bitmap sampled;
            private android.graphics.BitmapShader texture;
            @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
                super.onSizeChanged(width,height,oldWidth,oldHeight);
                bounds.set(.5f,.5f,width-.5f,height-.5f);
                edge.setStyle(Paint.Style.STROKE);edge.setStrokeWidth(Math.max(1f,dp(1)));
                edge.setShader(new LinearGradient(0,0,width,height,new int[]{0x85ffffff,0x10ffffff,0x08ffffff,0x40ffffff},new float[]{0,.35f,.7f,1},Shader.TileMode.CLAMP));
            }
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if(glassImage!=null){
                    if(sampled!=glassImage){sampled=glassImage;texture=new android.graphics.BitmapShader(glassImage,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);}
                    transform.setScale((float)VideoControlsOverlay.this.getWidth()/glassImage.getWidth(),(float)VideoControlsOverlay.this.getHeight()/glassImage.getHeight());
                    transform.postTranslate(-getLeft(),-getTop());texture.setLocalMatrix(transform);
                    frost.setShader(texture);frost.setAlpha(190);canvas.drawRoundRect(bounds,dp(40),dp(40),frost);frost.setShader(null);
                }else{frost.setColor(0xb010151b);canvas.drawRoundRect(bounds,dp(40),dp(40),frost);}
                if(glassTint!=null){glassTint.setBounds(0,0,getWidth(),getHeight());glassTint.setAlpha(255);glassTint.draw(canvas);}
                canvas.drawRoundRect(bounds,dp(40),dp(40),edge);
            }
        };
        controlBar.setWillNotDraw(false);
        if(android.os.Build.VERSION.SDK_INT<18)controlBar.setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        controlBar.setOrientation(LinearLayout.VERTICAL);
        controlBar.setPadding(dp(34), dp(16), dp(34), dp(16));
        controlBar.setClickable(true);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x20ffffff, 0x2511161c, 0x380c1015});
        glass.setCornerRadius(dp(40));
        glassTint=glass;
        LayoutParams barBounds = new LayoutParams(-1, -2, Gravity.BOTTOM);
        barBounds.leftMargin=dp(28); barBounds.rightMargin=dp(28); barBounds.bottomMargin=dp(28);
        addView(controlBar,barBounds);
        LinearLayout metadata = new LinearLayout(owner);
        metadata.setOrientation(LinearLayout.HORIZONTAL); metadata.setGravity(Gravity.CENTER_VERTICAL);
        title = label(playback.title(), 42);
        title.setTypeface(MediaTypography.display(owner));title.getPaint().setSubpixelText(true);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        metadata.addView(title,new LinearLayout.LayoutParams(0,dp(66),1));
        returnLive=label("回到直播",13);returnLive.setContentDescription("回到直播");
        metadata.addView(returnLive,new LinearLayout.LayoutParams(dp(100),dp(44)));
        returnLive.setOnClickListener(v->{if(playback instanceof Playback)((Playback)playback).live();});
        returnLive.setVisibility(GONE); styleControl(returnLive);
        quality=label("",18);quality.setPadding(dp(8),dp(2),dp(8),dp(2));
        GradientDrawable badge=new GradientDrawable();badge.setColor(0x224c5a63);badge.setCornerRadius(dp(5));badge.setStroke(Math.max(1,dp(1)),0x99ffffff);
        quality.setBackgroundDrawable(badge);metadata.addView(quality,new LinearLayout.LayoutParams(-2,dp(28)));
        controlBar.addView(metadata);
        LinearLayout timeline=new LinearLayout(owner);timeline.setGravity(Gravity.CENTER_VERTICAL);
        time=label("",22);remaining=label("",22);
        time.setTypeface(android.graphics.Typeface.MONOSPACE);remaining.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeline.addView(time,new LinearLayout.LayoutParams(dp(82),dp(42)));
        feedback = label("",14);
        LayoutParams hint = new LayoutParams(dp(180),dp(32),Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM);
        hint.bottomMargin=dp(260);addView(feedback,hint);
        seek = new SeekBar(owner);
        seek.setMax(1000);
        android.graphics.drawable.Drawable track = new ThinTrack(0x66ffffff,dp(6));
        android.graphics.drawable.Drawable fill = new ThinTrack(0xffffffff,dp(6));
        LayerDrawable progress = new LayerDrawable(new android.graphics.drawable.Drawable[] {
                track, new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL) });
        progress.setId(0, android.R.id.background); progress.setId(1, android.R.id.progress);
        seek.setProgressDrawable(progress);
        GradientDrawable thumb = new GradientDrawable(); thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(0xffffffff); thumb.setSize(dp(14), dp(14)); seek.setThumb(thumb);
        // Before Jelly Bean the track bounds also depend on thumb height.
        // Leave room for the 14dp thumb, otherwise those bounds become inverted.
        int seekPadding = dp(10);
        seek.setThumbOffset(dp(7)); seek.setPadding(dp(7), seekPadding, dp(7), seekPadding);
        if (android.os.Build.VERSION.SDK_INT >= 21) seek.setSplitTrack(false);
        timeline.addView(seek,new LinearLayout.LayoutParams(0,dp(42),1));
        timeline.addView(remaining,new LinearLayout.LayoutParams(dp(96),dp(42)));
        controlBar.addView(timeline);
        speedOptions = new LinearLayout(owner);
        speedOptions.setGravity(Gravity.RIGHT); speedOptions.setVisibility(GONE);
        for (final float rate : new float[] {0.5f, 1f, 1.25f, 1.5f, 2f, 3f}) {
            TextView choice = label(rate + "×", 15);
            styleControl(choice);
            speedOptions.addView(choice, new LinearLayout.LayoutParams(dp(58), dp(44)));
            choice.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    playback.speed(rate); speedOptions.setVisibility(GONE); refresh(); arm();
                }
            });
        }
        controlBar.addView(speedOptions);
        LinearLayout transport = new LinearLayout(owner);transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.setPadding(0,dp(10),0,0);controlBar.addView(transport,new LinearLayout.LayoutParams(-1,dp(82)));
        rewind = new Icon(owner,"rewind");rewind.setContentDescription("快退 5 秒");styleControl(rewind);
        toggle = new Icon(owner,"pause");toggle.setContentDescription("暂停");
        toggle.setFocusable(true);
        GradientDrawable pauseGlass=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xf0f5f7fa,0xd4dce2e8});
        pauseGlass.setCornerRadius(dp(34));pauseGlass.setStroke(Math.max(1,dp(1)),0xffffffff);
        toggle.setBackgroundDrawable(pauseGlass);
        forward = new Icon(owner,"forward");forward.setContentDescription("快进 5 秒");styleControl(forward);
        transport.addView(rewind,new LinearLayout.LayoutParams(dp(72),dp(64)));
        LinearLayout.LayoutParams playBounds=new LinearLayout.LayoutParams(dp(112),dp(64));playBounds.leftMargin=dp(22);playBounds.rightMargin=dp(22);
        transport.addView(toggle,playBounds);transport.addView(forward,new LinearLayout.LayoutParams(dp(72),dp(64)));
        toggle.setOnClickListener(v->{playback.toggle();refresh();arm();});
        rewind.setOnClickListener(v->{step(-5000L);feedback.setText("快退 5 秒");});
        forward.setOnClickListener(v->{step(5000L);feedback.setText("快进 5 秒");});
        View divider=new View(owner);divider.setBackgroundColor(0x557d919e);
        LinearLayout.LayoutParams dividerBounds=new LinearLayout.LayoutParams(Math.max(1,dp(1)),dp(40));dividerBounds.leftMargin=dp(24);dividerBounds.rightMargin=dp(24);
        transport.addView(divider,dividerBounds);
        LinearLayout options=new LinearLayout(owner);options.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable optionGlass=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x20ffffff,0x101c2228});
        optionGlass.setCornerRadius(dp(34));optionGlass.setStroke(Math.max(1,dp(1)),0x38ffffff);options.setBackgroundDrawable(optionGlass);
        transport.addView(options,new LinearLayout.LayoutParams(0,dp(64),1));
        episodes=label("选集",24);optionIcon(episodes,0);episodes.setContentDescription("选集");styleControl(episodes);
        options.addView(episodes,new LinearLayout.LayoutParams(0,-1,1));episodes.setOnClickListener(v->{hide();try{playback.episodes();}catch(LinkageError ignored){}});
        tracks=label("音轨与字幕",24);optionIcon(tracks,1);tracks.setContentDescription("音轨与字幕");styleControl(tracks);
        options.addView(tracks,new LinearLayout.LayoutParams(0,-1,1.6f));tracks.setOnClickListener(v->showTracks());
        speed = label("倍速 1.0×",24);speed.setContentDescription("倍速");optionIcon(speed,2);speed.setGravity(Gravity.CENTER);styleControl(speed);
        options.addView(speed,new LinearLayout.LayoutParams(0,-1,1.2f));
        speed.setOnClickListener(v->{if(!speedSupported())return;
            speedOptions.setVisibility(speedOptions.getVisibility()==VISIBLE?GONE:VISIBLE);arm();});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { cancelRemoteSeek(); dragging = true; removeCallbacks(dismiss); }
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) updateTimes(playback.duration()*value/1000L,playback.duration());
            }
            public void onStopTrackingTouch(SeekBar bar) {
                playback.seek(playback.duration() * bar.getProgress() / 1000L);
                dragging = false; refresh(); arm();
            }
        });
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(getContext());
        view.setText(text); view.setTextColor(0xffffffff); view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(size));
        view.setIncludeFontPadding(false);view.setSingleLine(true);
        view.setGravity(Gravity.CENTER); return view;
    }
    private void optionIcon(TextView label,int kind) {
        android.graphics.drawable.Drawable icon=new android.graphics.drawable.Drawable(){
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path shape=new Path(),pixels=new Path();
            private final android.graphics.Matrix coordinates=new android.graphics.Matrix();
            private final RectF gauge=new RectF();
            private float unit,left,top;private int alpha=255;
            @Override protected void onBoundsChange(android.graphics.Rect b){float edge=Math.min(b.width(),b.height());unit=edge/24;left=b.left+(b.width()-edge)/2;top=b.top+(b.height()-edge)/2;coordinates.setScale(unit,unit);coordinates.postTranslate(left,top);}
            private void stroke(Canvas canvas){shape.transform(coordinates,pixels);canvas.drawPath(pixels,paint);}
            public void draw(Canvas canvas){
                paint.setColor(0xfff4f8ff);paint.setAlpha(alpha);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1.5f,Math.round(1.8f*unit)));paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);shape.reset();
                if(kind==0){shape.moveTo(3,7);shape.lineTo(12,2.5f);shape.lineTo(21,7);shape.lineTo(12,11.5f);shape.close();stroke(canvas);for(int y=12;y<=17;y+=5){shape.reset();shape.moveTo(3,y);shape.lineTo(12,y+4.5f);shape.lineTo(21,y);stroke(canvas);}}
                else if(kind==1){
                    shape.moveTo(6,4);shape.lineTo(18,4);shape.quadTo(21,4,21,7);shape.lineTo(21,15);shape.quadTo(21,18,18,18);
                    shape.lineTo(10,18);shape.lineTo(5,22);shape.lineTo(6,18);shape.quadTo(3,18,3,15);shape.lineTo(3,7);shape.quadTo(3,4,6,4);shape.close();stroke(canvas);
                    shape.reset();shape.moveTo(7,9);shape.lineTo(17,9);shape.moveTo(7,13);shape.lineTo(14,13);stroke(canvas);
                }else{
                    gauge.set(left+3*unit,top+3*unit,left+21*unit,top+21*unit);canvas.drawArc(gauge,140,260,false,paint);
                    canvas.drawLine(left+12*unit,top+13*unit,left+17*unit,top+6*unit,paint);canvas.drawCircle(left+12*unit,top+13*unit,unit,paint);
                }
            }
            public void setAlpha(int value){alpha=Math.max(0,Math.min(255,value));invalidateSelf();}public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
        icon.setBounds(0,0,dp(30),dp(30));label.setCompoundDrawables(icon,null,null,null);label.setCompoundDrawablePadding(dp(8));label.setPadding(dp(14),0,dp(14),0);
    }
    private void styleControl(View view) {
        styleControl(view, 22);
    }
    private void styleControl(View view, int radius) {
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        GradientDrawable pressed=new GradientDrawable();pressed.setColor(0x55e3efff);pressed.setCornerRadius(dp(radius));
        states.addState(new int[]{android.R.attr.state_pressed},pressed);
        GradientDrawable focused=new GradientDrawable();focused.setColor(0x334c6376);focused.setCornerRadius(dp(radius));focused.setStroke(Math.max(1,dp(2)),0xffe7f6ff);
        states.addState(new int[]{android.R.attr.state_focused},focused);
        GradientDrawable normal=new GradientDrawable();normal.setColor(0x00000000);states.addState(new int[]{},normal);
        view.setBackgroundDrawable(states);view.setFocusable(true);
    }
    private int dp(int value) { android.util.DisplayMetrics m=getResources().getDisplayMetrics();return Math.round(value*Math.max(.5f,Math.max(m.widthPixels,m.heightPixels)/1280f)); }
    void show() {
        if (!visible()) captureGlassFrame();
        title.setText(MediaTypography.title(playback.title()));
        feedback.setText("");
        speedOptions.setVisibility(GONE);
        setVisibility(VISIBLE);
        bringToFront();
        refresh();
        removeCallbacks(tick);
        post(tick);
        arm();
    }
    void hide() {
        remoteNavigation=false;skipConfirmUp=false;clearFocus();glassImage=null;controlBar.invalidate();
        cancelRemoteSeek();setVisibility(GONE);removeCallbacks(tick);removeCallbacks(dismiss);dragging=false;gestureAxis=0;
        if (trackDialog != null) trackDialog.dismiss();
    }
    void showForRemote(int openingKey) {
        show();remoteNavigation=true;
        skipConfirmUp=openingKey==KeyEvent.KEYCODE_DPAD_CENTER||openingKey==KeyEvent.KEYCODE_ENTER;
        toggle.requestFocusFromTouch();
        toggle.post(()->{if(remoteNavigation&&visible()&&findFocus()==null)toggle.requestFocusFromTouch();});
    }
    private boolean navigationKey(KeyEvent event,int key) {
        boolean confirm=key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER;
        if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
                cancelRemoteSeek();
                if(!visible())show();
                if(key==KeyEvent.KEYCODE_DPAD_UP){
                    if(speedOptions.getVisibility()==VISIBLE){remoteNavigation=true;speedOptions.getChildAt(0).requestFocusFromTouch();}
                    else{remoteNavigation=false;seek.requestFocusFromTouch();}
                }else{remoteNavigation=true;toggle.requestFocusFromTouch();}
                arm();
            }
            return true;
        }
        if(confirm){
            if(!visible()||!remoteNavigation){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)showForRemote(key);return true;}
            if(event.getAction()==KeyEvent.ACTION_UP){
                if(skipConfirmUp){skipConfirmUp=false;return true;}
                View focused=findFocus();if(!event.isCanceled()&&focused!=null&&focused.isEnabled())focused.performClick();
                if(visible()&&(focused==null||!focused.isShown()))speed.requestFocusFromTouch();
                arm();
            }
            return true;
        }
        if(!remoteNavigation||!visible()||(key!=KeyEvent.KEYCODE_DPAD_LEFT&&key!=KeyEvent.KEYCODE_DPAD_RIGHT))return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            View focused=findFocus();java.util.ArrayList<View> choices=new java.util.ArrayList<>();
            if(speedOptions.getVisibility()==VISIBLE&&focused!=null&&focused.getParent()==speedOptions){for(int i=0;i<speedOptions.getChildCount();i++)choices.add(speedOptions.getChildAt(i));}
            else for(View candidate:new View[]{rewind,toggle,forward,episodes,tracks,speed})if(candidate.isShown()&&candidate.isEnabled())choices.add(candidate);
            if(!choices.isEmpty()){int index=choices.indexOf(focused);int direction=key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;choices.get((Math.max(0,index)+direction+choices.size())%choices.size()).requestFocusFromTouch();}
            arm();
        }
        return true;
    }
    boolean remoteKey(KeyEvent event) { return remoteKey(event, event.getKeyCode()); }
    boolean remoteKey(KeyEvent event, int key) {
        if(navigationKey(event,key))return true;
        int direction = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND ? -1
                : key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 1 : 0;
        if (direction == 0) return false;
        long now = android.os.SystemClock.uptimeMillis();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (playback.duration() <= 0) return false;
            if (!remoteSeek.active() || remoteSeek.direction() != direction) {
                cancelRemoteSeek();
                remoteSeek.begin(direction, playback.position(), playback.duration(), now);
                show(); removeCallbacks(dismiss);
                postDelayed(remoteRepeat, ViewConfiguration.getLongPressTimeout());
            } else previewRemoteSeek();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (remoteSeek.active() && remoteSeek.direction() == direction) {
                remoteSeek.preview(now);
                boolean held = remoteSeek.holding();
                long target = remoteSeek.finish(now, playback.position(), event.isCanceled());
                removeCallbacks(remoteRepeat); dragging = false;
                if (target >= 0) playback.seek(target);
                refresh();
                feedback.setText(target < 0 ? "" : held ? "已定位 " + stamp(target)
                        : direction < 0 ? "快退 5 秒" : "快进 5 秒");
                arm();
            }
            return true;
        }
        return false;
    }
    private void previewRemoteSeek() {
        long target = remoteSeek.preview(android.os.SystemClock.uptimeMillis());
        if (target < 0) return;
        dragging = true; seekPosition = target;
        long duration = playback.duration();
        if (duration <= 0) { cancelRemoteSeek(); return; }
        seek.setProgress((int) (target * 1000L / duration));
        updateTimes(target,duration);
        feedback.setText((remoteSeek.direction() < 0 ? "快退 " : "快进 ") + stamp(target));
    }
    private void cancelRemoteSeek() { remoteSeek.cancel(); removeCallbacks(remoteRepeat); dragging = false; }
    void step(long delta) {
        long duration = playback.duration();
        if (duration <= 0L) return;
        playback.seek(Math.max(0L, Math.min(duration - 1L, playback.position() + delta)));
        refresh(); arm();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelRemoteSeek();
    }
    boolean visible() { return getVisibility() == VISIBLE; }
    private void arm() {
        removeCallbacks(dismiss);
        if (remoteSeek.active()) return;
        if (!playback.completed()) {
            postDelayed(dismiss, 5000L);
            removeCallbacks(tick); if (visible()) postDelayed(tick, 500L);
        }
    }
    private void refresh() {
        returnLive.setVisibility(catchup()?VISIBLE:GONE);
        boolean completed = playback.completed();
        if (completed) feedback.setText("播放完成 · 重新播放");
        else if ("播放完成 · 重新播放".contentEquals(feedback.getText())) feedback.setText("");
        String status=completed ? "" : playback.status();boolean loading=!completed && playback.loading();
        if(!status.equals(loadStatus)){loadStatus=status;loadText.setText(status);if(!loading&&!status.isEmpty())arm();}
        loadPanel.setVisibility(status.isEmpty()?GONE:VISIBLE);loadSpinner.setVisibility(loading?VISIBLE:GONE);loadText.setTextColor(loading?0xffffffff:0xffffd5ad);
        toggle.setVisibility(VISIBLE);toggle.setEnabled(status.isEmpty()||loading||completed);toggle.setAlpha(loading?.65f:status.isEmpty()?1f:.35f);
        toggle.kind = playback.playing() ? "pause" : "play"; toggle.invalidate();
        toggle.setContentDescription(completed ? "重新播放" : playback.playing() ? "暂停" : "播放");
        speed.setText("倍速 "+String.format(java.util.Locale.US,"%.1f×",playback.speed()));
        try{episodes.setVisibility(playback.hasEpisodes()?VISIBLE:GONE);String value=playback.quality();quality.setText(value);quality.setVisibility(value.isEmpty()?GONE:VISIBLE);
            boolean hasTracks = playback instanceof Playback ? ((Playback) playback).hasTracks() : playback.trackOptions().length() > 0;
            tracks.setVisibility(hasTracks ? VISIBLE : GONE);}
        catch(LinkageError olderPlugin){episodes.setVisibility(GONE);quality.setVisibility(GONE);tracks.setVisibility(GONE);}
        speed.setEnabled(speedSupported());
        speed.setAlpha(speedSupported() ? 1f : 0.4f);
        if (dragging) return;
        long duration = playback.duration(), position = playback.position();
        rewind.setVisibility(duration > 0 ? VISIBLE : GONE);
        forward.setVisibility(duration > 0 ? VISIBLE : GONE);
        speed.setVisibility(duration > 0 ? VISIBLE : GONE);
        if (duration <= 0) speedOptions.setVisibility(GONE);
        seek.setVisibility(duration > 0 ? VISIBLE : GONE);
        seek.setEnabled(duration > 0);
        seek.setProgress(duration > 0 ? (int) (Math.min(position, duration) * 1000L / duration) : 0);
        updateTimes(position,duration);
    }
    private android.view.TextureView texture(View view) {
        if(!view.isShown())return null;
        if(view instanceof android.view.TextureView&&view.getWidth()>0&&view.getHeight()>0)return (android.view.TextureView)view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=group.getChildCount()-1;i>=0;i--){android.view.TextureView found=texture(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private void captureGlassFrame() {
        glassImage=null;
        try{android.view.TextureView surface=texture(activity.getWindow().getDecorView());
            if(surface!=null&&surface.isAvailable()){
                if(surface.getParent() instanceof DirectVideoView&&((DirectVideoView)surface.getParent()).isFrameCovered())return;
                int size=Math.max(surface.getWidth(),surface.getHeight());
                android.graphics.Bitmap sample=surface.getBitmap(Math.max(1,192*surface.getWidth()/size),Math.max(1,192*surface.getHeight()/size));
                if(sample!=null){VideoBlurBackdrop.gaussian(sample);VideoBlurBackdrop.gaussian(sample);glassImage=sample;}
            }
        }catch(RuntimeException unavailable){glassImage=null;}
        controlBar.invalidate();
    }

    private void updateTimes(long position,long duration) {
        time.setText(duration>0?stamp(position):"直播");
        remaining.setText(duration>0?"−"+stamp(Math.max(0,duration-position)):"");
    }
    private void showTracks() {
        if (trackDialog != null) return;
        JSONArray rows;
        try { rows = playback.trackOptions(); } catch (LinkageError oldPlugin) { return; }
        if (rows.length() == 0) return;

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xf4384956, 0xfa101e29});
        glass.setCornerRadius(dp(24));
        glass.setStroke(Math.max(1, dp(1)), 0x779db5c7);
        panel.setBackgroundDrawable(glass);
        TextView heading = label("音轨与字幕", 24);
        heading.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(14), 0, dp(14), 0);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(46)));

        int maxListHeight = Math.min(dp(400), Math.max(dp(48), getHeight() - dp(128)));
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int width, int height) {
                int limit = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED
                        ? maxListHeight : Math.min(maxListHeight, MeasureSpec.getSize(height));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        scroll.setFocusable(false);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        View initial = addTrackSection(list, rows, "a:", "音轨", dialog);
        View subtitles = addTrackSection(list, rows, "s:", "字幕", dialog);
        View other = addTrackSection(list, rows, "", "", dialog);
        if (initial == null) initial = subtitles != null ? subtitles : other;
        if (initial == null) return;

        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setDimAmount(0.4f);
        }
        dialog.setOnKeyListener((owner, key, event) -> {
            if (key != KeyEvent.KEYCODE_BUTTON_SELECT && key != KeyEvent.KEYCODE_BUTTON_A
                    && key != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                View focused = dialog.getCurrentFocus();
                if (focused != null && focused.isEnabled()) focused.performClick();
            }
            return true;
        });
        dialog.setOnDismissListener(owner -> {
            trackDialog = null;
            if (!visible()) return;
            if (remoteNavigation) tracks.post(() -> {
                if (remoteNavigation && visible() && trackDialog == null) tracks.requestFocusFromTouch();
            });
            arm();
        });
        dialog.show();
        trackDialog = dialog;
        if (window != null) window.setLayout(Math.min(getWidth() - dp(48), dp(540)), -2);
        View initialOption = initial;
        initialOption.post(initialOption::requestFocusFromTouch);
    }

    private View addTrackSection(LinearLayout list, JSONArray rows, String prefix,
            String section, Dialog dialog) {
        View first = null;
        View current = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("id");
            boolean matches = prefix.isEmpty()
                    ? !id.startsWith("a:") && !id.startsWith("s:") : id.startsWith(prefix);
            if (!matches) continue;
            if (first == null && !section.isEmpty()) {
                TextView name = label(section, 16);
                name.setTextColor(0xffb7cad8);
                name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                name.setPadding(dp(14), 0, dp(14), 0);
                LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, dp(28));
                if (list.getChildCount() > 0) bounds.topMargin = dp(12);
                list.addView(name, bounds);
            }

            boolean selected = row.optBoolean("selected");
            LinearLayout option = new LinearLayout(activity);
            option.setGravity(Gravity.CENTER_VERTICAL);
            styleControl(option, 12);
            option.setPadding(dp(14), 0, dp(14), 0);
            option.setSelected(selected);

            TextView name = label(row.optString("label"), 20);
            name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            name.setEllipsize(TextUtils.TruncateAt.END);
            option.addView(name, new LinearLayout.LayoutParams(0, -1, 1f));
            TextView marker = label("✓", 18);
            marker.setTextColor(0xffb9e6ff);
            marker.setVisibility(selected ? VISIBLE : INVISIBLE);
            marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams markerBounds = new LinearLayout.LayoutParams(dp(22), -1);
            markerBounds.leftMargin = dp(10);
            option.addView(marker, markerBounds);

            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, dp(46));
            bounds.topMargin = dp(3);
            list.addView(option, bounds);
            option.setOnClickListener(v -> {
                try { playback.selectTrack(id); } catch (LinkageError ignored) {}
                dialog.dismiss();
                refresh();
            });
            if (first == null) first = option;
            if (selected && current == null) current = option;
        }
        return current != null ? current : first;
    }

    private boolean catchup() { return playback instanceof Playback && ((Playback) playback).catchup(); }
    private boolean speedSupported() {
        return playback instanceof Playback ? ((Playback) playback).speedSupported() : playback.duration() > 0;
    }
    private static String stamp(long ms) {
        long seconds = Math.max(0L, ms) / 1000L;
        return String.format(java.util.Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(shade.getShader()==null)shade.setShader(new LinearGradient(0, 0, 0, getHeight(),
                new int[] {0x00000000, 0x00000000, 0x00000000, 0x66000000},
                new float[] {0f, 0.3f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), shade);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);shade.setShader(null);}
    private static final class ThinTrack extends android.graphics.drawable.Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int thickness;
        ThinTrack(int color,int thickness){paint.setColor(color);this.thickness=Math.max(2,thickness);}
        public void draw(Canvas canvas){android.graphics.Rect b=getBounds();float center=b.exactCenterY();canvas.drawRoundRect(new RectF(b.left,center-thickness/2f,b.right,center+thickness/2f),thickness,thickness,paint);}
        public int getIntrinsicHeight(){return thickness;}
        public int getIntrinsicWidth(){return thickness;}
        public void setAlpha(int alpha){paint.setAlpha(alpha);}
        public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
        public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    /** Small native vector shapes keep icons sharp even on Android 4.x. */
    private static final class Icon extends View {
        String kind;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path seekArrow = new Path();
        final RectF seekRing = new RectF();
        final Paint.FontMetrics digitMetrics = new Paint.FontMetrics();
        Icon(Context context, String value) { super(context); kind = value; }
        @Override protected void onDraw(Canvas canvas) {
            if ("rewind".equals(kind) || "forward".equals(kind)) {
                drawSeekIcon(canvas);
                return;
            }
            canvas.save();
            float size = Math.min(getWidth(), getHeight()) * ("pause".equals(kind) || "play".equals(kind) ? 0.66f : 0.56f);
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            canvas.scale(size / 24f, size / 24f);
            paint.setColor("play".equals(kind)||"pause".equals(kind)?0xff0b121a:0xeeffffff); paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            if ("back".equals(kind)) {
                Path path = new Path(); path.moveTo(15, 4); path.lineTo(7, 12); path.lineTo(15, 20); canvas.drawPath(path, paint);
            } else if ("pause".equals(kind)) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(new RectF(5, 3, 9, 21), 1.3f, 1.3f, paint);
                canvas.drawRoundRect(new RectF(15, 3, 19, 21), 1.3f, 1.3f, paint);
            } else if ("play".equals(kind)) {
                paint.setStyle(Paint.Style.FILL); Path path = new Path();
                path.moveTo(7, 3); path.lineTo(21, 12); path.lineTo(7, 21); path.close(); canvas.drawPath(path, paint);
            } else if ("sun".equals(kind)) {
                canvas.drawCircle(12, 12, 4, paint);
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    canvas.drawLine(12 + (float)Math.cos(angle)*8, 12 + (float)Math.sin(angle)*8,
                            12 + (float)Math.cos(angle)*10, 12 + (float)Math.sin(angle)*10, paint);
                }
            } else {
                Path path = new Path(); path.moveTo(3, 9); path.lineTo(7, 9); path.lineTo(12, 5);
                path.lineTo(12, 19); path.lineTo(7, 15); path.lineTo(3, 15); path.close();
                paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint); paint.setStyle(Paint.Style.STROKE);
                canvas.drawArc(new RectF(10, 7, 20, 17), -50, 100, false, paint);
                canvas.drawArc(new RectF(8, 3, 24, 21), -48, 96, false, paint);
            }
            canvas.restore();
        }
        private void drawSeekIcon(Canvas canvas) {
            float unit=Math.min(getWidth(),getHeight())*.56f/24f;
            float cx=getWidth()/2f,cy=getHeight()/2f;
            boolean backward="rewind".equals(kind);
            paint.setColor(0xfff4f8ff);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f,Math.round(1.55f*unit)));
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            seekRing.set(cx-8.25f*unit,cy-8.25f*unit,cx+8.25f*unit,cy+8.25f*unit);
            canvas.drawArc(seekRing,backward?185:-5,backward?-315:315,false,paint);
            float mirror=backward?-1:1;
            seekArrow.reset();seekArrow.moveTo(cx+mirror*2.23f*unit,cy-6.54f*unit);
            seekArrow.lineTo(cx+mirror*5.3f*unit,cy-6.32f*unit);
            seekArrow.lineTo(cx+mirror*4.55f*unit,cy-9.3f*unit);
            canvas.drawPath(seekArrow,paint);
            paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));
            paint.setTextSize(9*unit);paint.getFontMetrics(digitMetrics);
            canvas.drawText("5",cx,cy+unit-(digitMetrics.ascent+digitMetrics.descent)/2,paint);
        }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { remoteNavigation=false;skipConfirmUp=false;clearFocus();cancelRemoteSeek(); removeCallbacks(dismiss); }
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) arm();
        return handled;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY(); moved = false;
                gestureAxis = 0;
                gestureDuration = playback.duration();
                startPosition = playback.position();
                seekPosition = startPosition;
                if (!touchAdjustments) return true;
                startVolume = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                startBrightness = PlaybackBrightness.percent(activity) / 100f;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = downY - event.getY();
                if (gestureAxis == 0) {
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.max(Math.abs(dx), Math.abs(dy)) <= slop) return true;
                    moved = true;
                    gestureAxis = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                }
                if (gestureAxis == 1) {
                    if (gestureDuration <= 0L) return true;
                    dragging = true;
                    seekPosition = Math.max(0L, Math.min(gestureDuration - 1L,
                            startPosition + Math.round(dx / Math.max(1f, getWidth()) * gestureDuration)));
                    seek.setProgress((int) (seekPosition * 1000L / gestureDuration));
                    updateTimes(seekPosition,gestureDuration);
                    feedback.setText("进度 " + stamp(seekPosition));
                    return true;
                }
                if (!touchAdjustments) return true;
                float delta = dy / Math.max(1f, getHeight()) * 1.5f;
                if (downX >= getWidth() / 2f && audio != null) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int value = Math.max(0, Math.min(max, startVolume + Math.round(delta * max)));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                    feedback.setText("音量 " + (max > 0 ? value * 100 / max : 0) + "%");
                } else if (downX < getWidth() / 2f) {
                    float value = Math.max(0.01f, Math.min(1f, startBrightness + delta));
                    PlaybackBrightness.apply(activity, Math.round(value * 100f));
                    feedback.setText("亮度 " + Math.round(value * 100f) + "%");
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (gestureAxis == 1 && dragging) playback.seek(seekPosition);
                dragging = false; gestureAxis = 0; refresh();
                if (!moved) hide();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false; gestureAxis = 0; feedback.setText(""); refresh();
                return true;
            default: return true;
        }
    }

    boolean handleSurfaceGesture(MotionEvent event) {
        removeCallbacks(dismiss);
        boolean handled = onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) arm();
        return handled;
    }
}
