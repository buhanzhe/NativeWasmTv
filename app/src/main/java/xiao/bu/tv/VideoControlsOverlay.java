package xiao.bu.tv;

import android.app.Activity;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

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
    private final RemoteSeekState remoteSeek = new RemoteSeekState(ViewConfiguration.getLongPressTimeout());
    private final Runnable remoteRepeat = new Runnable() {
        public void run() {
            if (!remoteSeek.active()) return;
            previewRemoteSeek(); postDelayed(this, 120L);
        }
    };
    private final Runnable dismiss = new Runnable() { public void run() {
        if (remoteSeek.active() || dragging) return;
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
            private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            private final RectF destination = new RectF(), bounds = new RectF();
            private final Path outline = new Path();
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                bounds.set(0,0,getWidth(),getHeight());outline.reset();outline.addRoundRect(bounds,dp(40),dp(40),Path.Direction.CW);
                if(glassImage!=null){int save=canvas.save();canvas.clipPath(outline);
                    destination.set(-getLeft(),-getTop(),VideoControlsOverlay.this.getWidth()-getLeft(),VideoControlsOverlay.this.getHeight()-getTop());
                    canvas.drawBitmap(glassImage,null,destination,frost);canvas.restoreToCount(save);}
                if(glassTint!=null){glassTint.setBounds(0,0,getWidth(),getHeight());glassTint.setAlpha(glassImage==null?255:190);glassTint.draw(canvas);}
            }
        };
        controlBar.setWillNotDraw(false);
        if(android.os.Build.VERSION.SDK_INT<18)controlBar.setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        controlBar.setOrientation(LinearLayout.VERTICAL);
        controlBar.setPadding(dp(34), dp(16), dp(34), dp(16));
        controlBar.setClickable(true);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xbb6d7781, 0xbc26343d, 0xd51b252e});
        glass.setCornerRadius(dp(40)); glass.setStroke(dp(1), 0x99c3d3dc);
        glassTint=glass;
        LayoutParams barBounds = new LayoutParams(-1, -2, Gravity.BOTTOM);
        barBounds.leftMargin=dp(28); barBounds.rightMargin=dp(28); barBounds.bottomMargin=dp(28);
        addView(controlBar,barBounds);
        LinearLayout metadata = new LinearLayout(owner);
        metadata.setOrientation(LinearLayout.HORIZONTAL); metadata.setGravity(Gravity.CENTER_VERTICAL);
        title = label(playback.title(), 42);
        title.setTypeface(MediaTypography.display(owner));title.getPaint().setFakeBoldText(true);
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
        android.graphics.drawable.Drawable track = new ThinTrack(0x66ffffff,dp(3));
        android.graphics.drawable.Drawable fill = new ThinTrack(0xffffffff,dp(3));
        LayerDrawable progress = new LayerDrawable(new android.graphics.drawable.Drawable[] {
                track, new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL) });
        progress.setId(0, android.R.id.background); progress.setId(1, android.R.id.progress);
        seek.setProgressDrawable(progress);
        GradientDrawable thumb = new GradientDrawable(); thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(0xffffffff); thumb.setSize(dp(10), dp(10)); seek.setThumb(thumb);
        // Before Jelly Bean the track bounds also depend on thumb height.
        // Leave room for the 10dp thumb, otherwise those bounds become inverted.
        int seekPadding = dp(10);
        seek.setThumbOffset(dp(5)); seek.setPadding(dp(5), seekPadding, dp(5), seekPadding);
        if (android.os.Build.VERSION.SDK_INT >= 21) seek.setSplitTrack(false);
        timeline.addView(seek,new LinearLayout.LayoutParams(0,dp(42),1));
        timeline.addView(remaining,new LinearLayout.LayoutParams(dp(96),dp(42)));
        controlBar.addView(timeline);
        speedOptions = new LinearLayout(owner);
        speedOptions.setGravity(Gravity.RIGHT); speedOptions.setVisibility(GONE);
        for (final float rate : new float[] {0.5f, 1f, 1.25f, 1.5f, 2f, 3f}) {
            TextView choice = label(rate + "×", 15);
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
        GradientDrawable pauseGlass=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xffffffff,0xffafbac8,0xfff2f6fb});
        pauseGlass.setCornerRadius(dp(34));pauseGlass.setStroke(Math.max(1,dp(2)),0xffffffff);toggle.setBackgroundDrawable(pauseGlass);
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
        GradientDrawable optionGlass=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0x776c7c8b,0x55394650});
        optionGlass.setCornerRadius(dp(34));optionGlass.setStroke(Math.max(1,dp(1)),0x558f9fab);options.setBackgroundDrawable(optionGlass);
        transport.addView(options,new LinearLayout.LayoutParams(0,dp(64),1));
        episodes=label("选集",24);optionIcon(episodes,0);episodes.setContentDescription("选集");styleControl(episodes);
        options.addView(episodes,new LinearLayout.LayoutParams(0,-1,1));episodes.setOnClickListener(v->{hide();try{playback.episodes();}catch(LinkageError ignored){}});
        tracks=label("音轨与字幕",24);optionIcon(tracks,1);tracks.setContentDescription("音轨与字幕");styleControl(tracks);
        options.addView(tracks,new LinearLayout.LayoutParams(0,-1,1.6f));tracks.setOnClickListener(v->showTracks());
        speed = label("倍速 1.0×",24);optionIcon(speed,2);speed.setGravity(Gravity.CENTER);styleControl(speed);
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
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path shape=new Path();
            public void draw(Canvas canvas){int saved=canvas.save();android.graphics.Rect b=getBounds();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
                paint.setColor(0xffeef5fb);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);shape.reset();
                if(kind==0){shape.moveTo(3,7);shape.lineTo(12,2);shape.lineTo(21,7);shape.lineTo(12,12);shape.close();canvas.drawPath(shape,paint);for(int y:new int[]{12,17}){shape.reset();shape.moveTo(3,y);shape.lineTo(12,y+5);shape.lineTo(21,y);canvas.drawPath(shape,paint);}}
                else if(kind==1){canvas.drawRoundRect(new RectF(2,3,22,17),3,3,paint);shape.moveTo(7,17);shape.lineTo(5,22);shape.lineTo(13,17);canvas.drawPath(shape,paint);canvas.drawLine(7,8,17,8,paint);canvas.drawLine(7,12,14,12,paint);}
                else{canvas.drawArc(new RectF(3,3,21,21),140,260,false,paint);canvas.drawLine(12,13,17,6,paint);canvas.drawCircle(12,13,1,paint);}
                canvas.restoreToCount(saved);}
            public void setAlpha(int alpha){paint.setAlpha(alpha);}public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
        icon.setBounds(0,0,dp(30),dp(30));label.setCompoundDrawables(icon,null,null,null);label.setCompoundDrawablePadding(dp(8));label.setPadding(dp(14),0,dp(14),0);
    }
    private void styleControl(View view) {
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        GradientDrawable pressed=new GradientDrawable();pressed.setColor(0x55e3efff);pressed.setCornerRadius(dp(22));
        states.addState(new int[]{android.R.attr.state_pressed},pressed);
        GradientDrawable normal=new GradientDrawable();normal.setColor(0x00000000);states.addState(new int[]{},normal);
        view.setBackgroundDrawable(states);view.setFocusable(false);
    }
    private int dp(int value) { android.util.DisplayMetrics m=getResources().getDisplayMetrics();return Math.round(value*Math.max(.5f,Math.max(m.widthPixels,m.heightPixels)/1280f)); }
    void show() { captureGlassFrame();title.setText(MediaTypography.title(playback.title())); feedback.setText(""); speedOptions.setVisibility(GONE); setVisibility(VISIBLE); bringToFront(); refresh(); removeCallbacks(tick); post(tick); arm(); }
    void hide() { glassImage=null;controlBar.invalidate();cancelRemoteSeek(); setVisibility(GONE); removeCallbacks(tick); removeCallbacks(dismiss); dragging = false; gestureAxis = 0; }
    boolean remoteKey(KeyEvent event) { return remoteKey(event, event.getKeyCode()); }
    boolean remoteKey(KeyEvent event, int key) {
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
        loadPanel.setVisibility(status.isEmpty()?GONE:VISIBLE);loadSpinner.setVisibility(loading?VISIBLE:GONE);loadText.setTextColor(loading?0xffffffff:0xffffd5ad);toggle.setVisibility(status.isEmpty()?VISIBLE:INVISIBLE);
        toggle.kind = playback.playing() ? "pause" : "play"; toggle.invalidate();
        toggle.setContentDescription(completed ? "重新播放" : playback.playing() ? "暂停" : "播放");
        speed.setText("倍速 "+String.format(java.util.Locale.US,"%.1f×",playback.speed()));
        try{episodes.setVisibility(playback.hasEpisodes()?VISIBLE:GONE);String value=playback.quality();quality.setText(value);quality.setVisibility(value.isEmpty()?GONE:VISIBLE);tracks.setVisibility(playback.trackOptions().length()>0?VISIBLE:GONE);}
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
                android.graphics.Bitmap sample=surface.getBitmap(Math.max(1,128*surface.getWidth()/size),Math.max(1,128*surface.getHeight()/size));
                if(sample!=null){VideoBlurBackdrop.gaussian(sample);glassImage=sample;}
            }
        }catch(RuntimeException unavailable){glassImage=null;}
        controlBar.invalidate();
    }

    private void updateTimes(long position,long duration) {
        time.setText(duration>0?stamp(position):"直播");
        remaining.setText(duration>0?"−"+stamp(Math.max(0,duration-position)):"");
    }
    private void showTracks() {
        org.json.JSONArray rows;
        try{rows=playback.trackOptions();}catch(LinkageError oldPlugin){return;}
        if(rows.length()==0)return;
        android.app.Dialog dialog=new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(18),dp(24),dp(18));
        GradientDrawable glass=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xf04c5c69,0xfa101e29});glass.setCornerRadius(dp(28));glass.setStroke(Math.max(1,dp(1)),0x99a8bdcd);panel.setBackgroundDrawable(glass);
        TextView heading=label("音轨与字幕",28);panel.addView(heading,new LinearLayout.LayoutParams(-1,dp(52)));
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
        for(int i=0;i<rows.length();i++) {
            org.json.JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            TextView option=label((row.optBoolean("selected")?"✓  ":"")+row.optString("label"),22);option.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);option.setEllipsize(android.text.TextUtils.TruncateAt.END);
            list.addView(option,new LinearLayout.LayoutParams(-1,dp(52)));option.setFocusable(true);styleControl(option);option.setFocusable(true);
            option.setOnClickListener(v->{try{playback.selectTrack(row.optString("id"));}catch(LinkageError ignored){}dialog.dismiss();refresh();arm();});
        }
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(300),rows.length()*dp(52))));
        dialog.setContentView(panel);android.view.Window window=dialog.getWindow();if(window!=null)window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        dialog.setOnDismissListener(d->arm());dialog.show();if(window!=null)window.setLayout(Math.min(getWidth()-dp(48),dp(700)),-2);
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
        Icon(Context context, String value) { super(context); kind = value; }
        @Override protected void onDraw(Canvas canvas) {
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
            } else if ("rewind".equals(kind) || "forward".equals(kind)) {
                boolean backward="rewind".equals(kind);
                if(backward){canvas.scale(-1,1,12,12);}
                canvas.drawArc(new RectF(4,4,20,20),-65,285,false,paint);
                Path arrow=new Path();arrow.moveTo(17,2);arrow.lineTo(19,7);arrow.lineTo(14,7);canvas.drawPath(arrow,paint);
                if(backward){canvas.scale(-1,1,12,12);}
                paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(10);
                canvas.drawText("5",12,15.5f,paint);
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
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { cancelRemoteSeek(); removeCallbacks(dismiss); }
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
