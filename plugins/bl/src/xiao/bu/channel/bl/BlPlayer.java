package xiao.bu.channel.bl;

import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import tv.danmaku.ijk.media.player.*;

/** BBLL control geometry rebuilt as native API-14 Views; no Activity or UI framework dependency. */
final class BlPlayer extends FrameLayout implements SurfaceHolder.Callback,BlDanmaku.Clock {
    interface Actions {void settings();void episodes();void comments();void subtitle();void more();void diagnostics();void related();void up();void step(int delta);void order();void exit();void complete();void position(long at);void failure(String error,BlStreams.Track track,boolean transport);}
    private final BlUi ui;private final BlMediaProxy proxy;private final Actions actions;private final BlApi api;private final BlImages images;private final SharedPreferences prefs;
    private final SurfaceView surface;private final LinearLayout controls;private final TextView time,status,subtitle,heading,subheading,clock,hint,debug;
    private final ImageButton toggle,dmButton;private final TimeBar seek;
    final BlDanmaku danmaku;
    private IMediaPlayer engine;private JSONArray streams=new JSONArray(),captions=new JSONArray();
    private int segment,width,height,buffered,engineGeneration,lineIndex;private long segmentStart,position,seekWhenReady,transportSession,lastBack;
    private double pixelAspectRatio=1;
    private boolean decodedAudioOnly;
    private final boolean systemPlayer,live,multipleParts;private boolean prepared,closed,active=true,seeking,hardware=true,hardwareFailed,wantPlaying=true;
    private String source="",audio="",format="";private float playbackSpeed=1;private long clockMinute=-1;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService releases=Executors.newSingleThreadExecutor();
    private final Map<String,ImageButton> icons=new HashMap<>();
    private Bitmap singleRepeatIcon;
    volatile String state="idle";
    private final Runnable prepareTimeout=new Runnable(){public void run(){if(!closed&&engine!=null&&!prepared&&state.equals("preparing")){showFailure("播放器准备超时",new java.net.SocketTimeoutException("30 秒内未完成播放器准备"),"请切换线路或画质");releaseEngine();}}};
    private final Runnable hideHint=new Runnable(){public void run(){hint.setVisibility(GONE);}};
    private final Runnable hide=new Runnable(){public void run(){
        controls.setVisibility(GONE);
        clock.setVisibility(GONE);
        hideHint.run();
        layoutOverlays();
    }};
    private final Runnable tick=new Runnable(){public void run(){
        if(closed||!active)return;
        checkPreparingTransport();
        long at=position(),length=duration();
        if(!seeking)updateTime(at,length);
        seek.update(at,length,buffered);
        long now=System.currentTimeMillis(),minute=now/60000;
        if(clockMinute!=minute){
            clockMinute=minute;
            clock.setText(android.text.format.DateFormat.getTimeFormat(ui.context).format(new Date(now)));
        }
        updatePlaybackIcon();
        updateSubtitle(at);
        actions.position(at);
        if(prefs.getBoolean("player_debug",false)){
            debug.setVisibility(VISIBLE);
            setTextChanged(debug,(engine==null?"正在准备":engine.getClass().getSimpleName())
                    +"\n"+width+" × "+height+"  "+format+"\n"+playbackSpeed+"x · "+state+" · 弹幕 "+danmaku.loaded());
        }else debug.setVisibility(GONE);
        main.postDelayed(this,500);
    }};
    BlPlayer(BlUi ui,BlMediaProxy proxy,BlApi api,BlImages images,String title,boolean live,int partCount,boolean hardware,boolean systemPlayer,Actions actions){
        super(ui.context);this.ui=ui;this.proxy=proxy;this.api=api;this.images=images;this.prefs=api.prefs;this.actions=actions;this.live=live;this.multipleParts=partCount>1;this.hardware=hardware;this.systemPlayer=systemPlayer||Build.VERSION.SDK_INT<16;playbackSpeed=prefs.getFloat("play_speed",1);setBackgroundColor(0xff000000);setContentDescription("BL播放器");setClipChildren(true);
        surface=new SurfaceView(ui.context);surface.setZOrderMediaOverlay(true);surface.getHolder().addCallback(this);surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        danmaku=new BlDanmaku(ui,this,prefs);addView(danmaku,new FrameLayout.LayoutParams(-1,-1));
        clock=ui.text("",33,0xffffffff);clock.setTypeface(null,android.graphics.Typeface.BOLD);clock.setShadowLayer(ui.px(4),0,0,0xff000000);FrameLayout.LayoutParams clockBox=ui.frame(-2,54,Gravity.RIGHT|Gravity.TOP);clockBox.rightMargin=ui.px(34);clockBox.topMargin=ui.px(16);addView(clock,clockBox);
        debug=ui.text("",17,0xffffffff);debug.setPadding(ui.px(14),ui.px(12),ui.px(14),ui.px(12));debug.setBackgroundColor(0x99000000);FrameLayout.LayoutParams debugBox=ui.frame(-2,-2,Gravity.LEFT|Gravity.TOP);debugBox.leftMargin=ui.px(16);debugBox.topMargin=ui.px(16);addView(debug,debugBox);debug.setVisibility(GONE);
        status=ui.text("正在准备播放…",21,BlUi.TEXT);status.setGravity(Gravity.CENTER);addView(status,ui.frame(-1,80,Gravity.CENTER));
        subtitle=ui.text("",27,0xffffffff);subtitle.setGravity(Gravity.CENTER);subtitle.setShadowLayer(ui.px(3),1,1,0xff000000);FrameLayout.LayoutParams sub=ui.frame(-1,90,Gravity.BOTTOM);sub.bottomMargin=ui.px(48);addView(subtitle,sub);
        controls=ui.vertical();controls.setPadding(ui.px(20),ui.px(44),ui.px(20),ui.px(20));controls.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xe6000000}));
        heading=ui.text(title,27,0xffffffff);heading.setTypeface(null,Typeface.BOLD);heading.setSingleLine();heading.setEllipsize(TextUtils.TruncateAt.END);heading.setShadowLayer(2,1,1,0xff000000);heading.setPadding(ui.px(17),0,ui.px(17),0);controls.addView(heading,new LinearLayout.LayoutParams(-1,ui.px(35)));
        subheading=ui.text("",18,BlUi.SUB);subheading.setSingleLine();subheading.setEllipsize(TextUtils.TruncateAt.END);subheading.setPadding(ui.px(17),0,ui.px(17),0);controls.addView(subheading,new LinearLayout.LayoutParams(-1,ui.px(30)));
        seek=new TimeBar();
        seek.setVisibility(live?GONE:VISIBLE);
        seek.setFocusable(!live);
        controls.addView(seek,new LinearLayout.LayoutParams(-1,ui.px(34)));
        LinearLayout row=ui.horizontal(),buttons=ui.horizontal();HorizontalScrollView scroll=new HorizontalScrollView(ui.context);scroll.setHorizontalScrollBarEnabled(false);scroll.addView(buttons);row.addView(scroll,new LinearLayout.LayoutParams(0,ui.px(66),1));
        toggle=icon(buttons,"暂停","icon_pause",18,new Runnable(){public void run(){toggle();}});
        icon(buttons,"上一集","icon_previous",18,new Runnable(){public void run(){actions.step(-1);}});icon(buttons,"下一集","icon_next",18,new Runnable(){public void run(){actions.step(1);}});
        icon(buttons,"快退","icon_rewind",7,new Runnable(){public void run(){seekTo(position()-10000);}});icon(buttons,"快进","icon_fast_forward",7,new Runnable(){public void run(){seekTo(position()+10000);}});
        dmButton=icon(buttons,"弹幕开关","icon_dm_enable",10,new Runnable(){public void run(){danmaku.enabled(!danmaku.enabled());images.icon(dmButton,danmaku.enabled()?"icon_dm_enable":"icon_dm_disable");}});
        icon(buttons,live?"直播设置":"播放器设置",live?"icon_setting":"player_icon_setting",14,new Runnable(){public void run(){actions.settings();}});icon(buttons,"选集","icon_list",19,new Runnable(){public void run(){actions.episodes();}});icon(buttons,"更多","icon_more",14,new Runnable(){public void run(){actions.more();}});
        icon(buttons,"UP主信息","icon_user",17,new Runnable(){public void run(){actions.up();}});icon(buttons,"字幕","icon_subtitles",15,new Runnable(){public void run(){actions.subtitle();}});icon(buttons,"相关视频","arrow_down",14,new Runnable(){public void run(){actions.related();}});icon(buttons,"播放顺序","icon_order_all_play",15,new Runnable(){public void run(){actions.order();}});icon(buttons,"关闭播放","icon_close",15,new Runnable(){public void run(){actions.exit();}});
        time=ui.text("",22,BlUi.TEXT);
        time.setSingleLine();
        time.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        time.setPadding(ui.px(12),0,ui.px(14),0);
        updateTime(0,0);
        row.addView(time,new LinearLayout.LayoutParams(-2,ui.px(66)));
        controls.addView(row);
        addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        hint=ui.text("",18,0xffffffff);hint.setGravity(Gravity.CENTER);hint.setBackgroundDrawable(ui.fill(0xaa161616,8));hint.setPadding(ui.px(18),ui.px(8),ui.px(18),ui.px(8));FrameLayout.LayoutParams hp=ui.frame(-2,42,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);hp.bottomMargin=ui.px(234);addView(hint,hp);hint.setVisibility(GONE);
        setOnTouchListener(new OnTouchListener(){public boolean onTouch(View view,android.view.MotionEvent event){if(event.getAction()==MotionEvent.ACTION_UP){if(controls.getVisibility()==VISIBLE)hide.run();else showControls();}return true;}});
        images.icon(dmButton,danmaku.enabled()?"icon_dm_enable":"icon_dm_disable");refreshButtons();main.post(tick);
    }
    private ImageButton icon(LinearLayout row,final String title,String asset,int padding,final Runnable action){final ImageButton button=new ImageButton(ui.context);icons.put(title,button);button.setBackgroundDrawable(ui.focus(0));button.setScaleType(ImageView.ScaleType.FIT_CENTER);button.setPadding(ui.px(padding),ui.px(padding),ui.px(padding),ui.px(padding));button.setContentDescription(title);button.setFocusable(true);images.icon(button,asset);row.addView(button,new LinearLayout.LayoutParams(ui.px(66),ui.px(66)));button.setOnClickListener(new OnClickListener(){public void onClick(View v){showControls();action.run();}});button.setOnFocusChangeListener(new OnFocusChangeListener(){public void onFocusChange(View v,boolean focus){if(focus&&controls.getVisibility()==VISIBLE)showControls();}});return button;}
    private void refreshButtons(){
        boolean showParts=!live&&multipleParts&&prefs.getBoolean("show_next_previous",true);
        boolean showSeek=!live&&prefs.getBoolean("show_re_ff",true);
        icons.get("上一集").setVisibility(showParts?VISIBLE:GONE);
        icons.get("下一集").setVisibility(showParts?VISIBLE:GONE);
        icons.get("快退").setVisibility(showSeek?VISIBLE:GONE);
        icons.get("快进").setVisibility(showSeek?VISIBLE:GONE);
        dmButton.setVisibility(prefs.getBoolean("show_dm_switch",true)?VISIBLE:GONE);
        for(String name:new String[]{"选集","UP主信息","字幕","相关视频","播放顺序"}){
            icons.get(name).setVisibility(live?GONE:VISIBLE);
        }
        images.icon(dmButton,danmaku.enabled()?"icon_dm_enable":"icon_dm_disable");
        refreshOrderIcon();
    }
    void refreshOrderIcon(){
        ImageButton order=icons.get("播放顺序");
        if(!prefs.getString("play_order","顺序播放").equals("单集循环")){
            images.icon(order,"icon_order_all_play");
            return;
        }
        if(singleRepeatIcon==null){
            Bitmap arrows=images.asset("icon_order_all_play.png");
            if(arrows==null)return;
            singleRepeatIcon=arrows.copy(Bitmap.Config.ARGB_8888,true);
            Canvas canvas=new Canvas(singleRepeatIcon);
            Paint number=new Paint(Paint.ANTI_ALIAS_FLAG);
            number.setColor(Color.WHITE);
            number.setTypeface(Typeface.DEFAULT_BOLD);
            number.setTextSize(canvas.getHeight()*.38f);
            number.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("1",canvas.getWidth()/2f,(canvas.getHeight()-number.ascent()-number.descent())/2f,number);
        }
        order.setImageBitmap(singleRepeatIcon);
    }
    List<String> lines(){ArrayList<String> result=new ArrayList<>();JSONObject item=streams.optJSONObject(segment);if(item==null)return result;result.add(item.optString("url"));JSONArray backups=BlApi.array(item,"backup_url");for(int i=0;i<Math.min(8,backups.length());i++){String url=backups.optString(i);if(!url.isEmpty()&&!result.contains(url))result.add(url);}return result;}
    void line(int index){List<String> urls=lines();if(index<0||index>=urls.size())return;lineIndex=index;seekWhenReady=live?0:Math.max(0,position()-segmentStart);position=segmentStart+seekWhenReady;source=urls.get(index);start();}
    void title(String title,String detail){heading.setText(title);subheading.setText(detail);subheading.setVisibility(detail.isEmpty()?GONE:VISIBLE);}
    void play(JSONArray list,long at){if(closed)return;releaseEngine();state="loading";lineIndex=0;streams=list;segment=-1;decodedAudioOnly=false;seekTo(live?0:Math.max(0,at));}
    void setSubtitle(JSONArray value){captions=value;}
    private void updateSubtitle(long at){String text="";for(int i=0;i<captions.length();i++){JSONObject c=captions.optJSONObject(i);if(c!=null&&c.optDouble("from")*1000<=at&&c.optDouble("to")*1000>at){text=c.optString("content");break;}}int size=ui.px(prefs.getInt("subtitle_size",27));if(subtitle.getTextSize()!=size)subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,size);setTextChanged(subtitle,text);layoutOverlays();}
    private static void setTextChanged(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private boolean playRequested(){return wantPlaying&&!state.equals("error")&&!state.equals("completed");}
    private void updatePlaybackIcon(){boolean playing=playRequested();images.icon(toggle,playing?"icon_pause":"icon_play");String label=playing?"暂停":"播放";if(!label.contentEquals(toggle.getContentDescription()))toggle.setContentDescription(label);}
    private void layoutOverlays(){int bottom=controls.getVisibility()==VISIBLE?Math.max(controls.getHeight()+ui.px(16),ui.px(244)):ui.px(62);FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)subtitle.getLayoutParams();int margin=Math.max(ui.px(48),bottom-ui.px(14));if(params.bottomMargin!=margin){params.bottomMargin=margin;subtitle.setLayoutParams(params);}danmaku.inset(bottom+(subtitle.getText().length()>0?ui.px(95):0));}
    long duration(){if(live)return 0;long total=0;for(int i=0;i<streams.length();i++){JSONObject d=streams.optJSONObject(i);if(d!=null)total+=d.optLong("length");}if(total==0&&engine!=null&&prepared)try{total=engine.getDuration();}catch(Exception ignored){}return total;}
    public long position(){if(engine!=null&&prepared)try{return segmentStart+engine.getCurrentPosition();}catch(Exception ignored){}return position;}
    long resumePosition(){return state.equals("completed")?0:prepared?position():-1;}
    public boolean running(){if(engine==null||!prepared||!active||closed)return false;try{return engine.isPlaying();}catch(Exception e){return false;}}
    void seekTo(long target){if(streams.length()==0||live&&segment>=0)return;long total=duration();if(total>0)target=Math.min(target,total-1);target=Math.max(0,target);int index=0;long start=0;for(int i=0;i<streams.length()-1;i++){long length=streams.optJSONObject(i).optLong("length");if(length<=0||target<start+length)break;start+=length;index++;}position=target;if(index==segment&&engine!=null&&prepared){try{engine.seekTo(target-start);danmaku.start();}catch(Exception ignored){}return;}segment=index;segmentStart=start;seekWhenReady=target-start;JSONObject item=streams.optJSONObject(index);source=item.optString("url");audio=item.optString("bl_audio");format=item.optString("bl_codec",audio.isEmpty()?"MP4/FLV":"DASH");start();}
    private void start(){
        if(closed||source.isEmpty()||!surface.getHolder().getSurface().isValid())return;releaseEngine();state="preparing";status.setText("正在加载…");status.setVisibility(VISIBLE);
        final int token=engineGeneration;
        // A single hardware decoder may still be held by release(). Wait off the UI thread.
        releases.execute(new Runnable(){public void run(){main.post(new Runnable(){public void run(){if(!closed&&token==engineGeneration&&surface.getHolder().getSurface().isValid())createEngine();}});}});
    }
    private void createEngine(){
        try{
            api.clearTransportFailure(source);
            IMediaPlayer p;if(!audio.isEmpty()&&Build.VERSION.SDK_INT>=16)p=new BlDashPlayer(ui.context,api,audio,lines(),BlApi.array(streams.optJSONObject(segment),"bl_audio_backups"),!decodedAudioOnly);
            else if(!systemPlayer){
                IjkMediaPlayer.loadLibrariesOnce(null);
                IjkMediaPlayer ijk=new IjkMediaPlayer();
                ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"mediacodec",hardware&&!hardwareFailed?1:0);
                ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"infbuf",0);
                ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"max-buffer-size",2*1024*1024);
                ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"max_cached_duration",3000);
                ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"start-on-prepared",0);
                p=ijk;
            }else p=new BlSystemPlayer();
            engine=p;transportSession=proxy.begin();p.setAudioStreamType(AudioManager.STREAM_MUSIC);p.setDisplay(surface.getHolder());
            p.setOnPreparedListener(new IMediaPlayer.OnPreparedListener(){public void onPrepared(IMediaPlayer mp){if(closed||engine!=mp)return;main.removeCallbacks(prepareTimeout);prepared=true;state=wantPlaying?"playing":"paused";status.setVisibility(GONE);applySpeed();if(seekWhenReady>0)mp.seekTo(seekWhenReady);if(active&&wantPlaying)mp.start();danmaku.start();showControls();}});
            p.setOnVideoSizeChangedListener((mp,w,h,n,d)->{
                if(engine!=mp||w<=0||h<=0)return;
                width=w;height=h;
                pixelAspectRatio=n>0&&d>0?n/(double)d:1;
                fit();
            });
            p.setOnBufferingUpdateListener(new IMediaPlayer.OnBufferingUpdateListener(){public void onBufferingUpdate(IMediaPlayer mp,int percent){if(engine==mp)buffered=percent;}});
            p.setOnCompletionListener(new IMediaPlayer.OnCompletionListener(){public void onCompletion(IMediaPlayer mp){if(engine!=mp||closed)return;if(segment+1<streams.length()){segmentStart+=streams.optJSONObject(segment).optLong("length");segment++;JSONObject item=streams.optJSONObject(segment);source=item.optString("url");audio=item.optString("bl_audio");seekWhenReady=0;start();}else{state="completed";actions.complete();}}});
            p.setOnErrorListener(this::engineError);
            p.setDataSource(p instanceof BlDashPlayer?source:proxy.url(source,transportSession));p.prepareAsync();main.removeCallbacks(prepareTimeout);main.postDelayed(prepareTimeout,30000);
        }catch(Exception e){showFailure("打开播放器",e,"");}catch(LinkageError e){showFailure("播放器能力不兼容",e,"请安装匹配设备架构的宿主");}
    }
    private boolean engineError(IMediaPlayer mp,int what,int extra){
        if(engine!=mp||closed)return true;
        main.removeCallbacks(prepareTimeout);
        BlDashPlayer dash=mp instanceof BlDashPlayer?(BlDashPlayer)mp:null;
        Throwable cause=dash==null?api.transportFailure(source):dash.failure();
        java.io.IOException error=new java.io.IOException("播放器错误 what="+what+", extra="+extra+"（"+mediaError(extra)+"）",cause);
        if(dash!=null&&!decodedAudioOnly&&dash.passthroughFailed()){
            decodedAudioOnly=true;
            api.diagnostics.record("杜比透传失败",error,describe()+"\n恢复：尝试解码当前音轨");
            seekWhenReady=Math.max(0,position()-segmentStart);
            start();
            return true;
        }
        if(live&&lineIndex+1<lines().size()){
            api.diagnostics.record("直播线路失败",error,describe()+"\n恢复：尝试下一线路");
            line(lineIndex+1);
            return true;
        }
        if(mp instanceof IjkMediaPlayer&&hardware&&!hardwareFailed){
            api.diagnostics.record("IJK 硬解失败",error,describe()+"\n恢复：尝试软件解码");
            hardwareFailed=true;
            seekWhenReady=Math.max(0,position()-segmentStart);
            start();
            return true;
        }
        showFailure("播放失败",error,"");
        return true;
    }
    private String describe(){return "引擎="+(engine==null?"未创建":engine.getClass().getSimpleName())+"; 格式="+format+"; 线路="+(lineIndex+1)+"/"+lines().size()+"; 分段="+(segment+1)+"; 解码="+(hardware&&!hardwareFailed?"硬件优先":"软件/系统")+"; 进度="+position()+"ms\n视频："+BlDiagnostics.safeUrl(source)+(audio.isEmpty()?"":"\n音频："+BlDiagnostics.safeUrl(audio));}
    private void checkPreparingTransport(){
        if(!(engine instanceof BlSystemPlayer)||prepared||!state.equals("preparing"))return;
        BlDiagnostics.Failure failure=api.transportFailure(source);if(failure==null)return;
        // Some platform decoders retry a rejected HTTP request for minutes without onError.
        // The shared transport already knows the failure; don't keep the UI waiting for that callback.
        if(live&&lineIndex+1<lines().size()){api.diagnostics.record("系统播放器直播线路失败",failure,describe()+"\n恢复：尝试下一线路");line(lineIndex+1);}
        else{showFailure("媒体请求失败",failure,"系统播放器准备中，上游请求已失败");releaseEngine();}
    }
    private static String mediaError(int code){switch(code){case -1004:return "媒体读取/网络 I/O";case -1007:return "媒体格式异常";case -1010:return "编码或格式不受支持";case -110:return "媒体准备超时";case -10000:return "解码器或媒体引擎失败";default:return "详见原始错误码与原因链";}}
    private void showFailure(String stage,Throwable error,String hint){
        main.removeCallbacks(prepareTimeout);
        BlDiagnostics.Entry entry=api.diagnostics.record(stage,error,describe()+"\n"+hint);
        state="error";
        BlDashPlayer dash=engine instanceof BlDashPlayer?(BlDashPlayer)engine:null;
        boolean transport=error instanceof java.net.SocketTimeoutException
                ||(dash==null?api.transportFailure(source)!=null:dash.transportFailed());
        actions.failure(entry.summary,dash==null?BlStreams.Track.UNKNOWN:dash.failureTrack(),transport);
    }
    void requestFailure(String summary){if(engine==null||!prepared)terminalFailure(summary);}
    void terminalFailure(String summary){
        main.removeCallbacks(prepareTimeout);
        state="error";
        status.setText(summary+"\nMENU → 错误详情");
        status.setVisibility(VISIBLE);
        status.setFocusable(true);
        status.setOnClickListener(v->actions.diagnostics());
    }
    void recovering(String message){
        position=position();
        releaseEngine();
        streams=new JSONArray();
        source="";
        state="loading";
        status.setText(message);status.setVisibility(VISIBLE);
    }
    private void fit(){
        if(width<=0||height<=0||getWidth()<=0||getHeight()<=0)return;
        String mode=prefs.getString("screen_ratio","自适应");
        double displayWidth=width*pixelAspectRatio;
        double fitWidth=getWidth()/displayWidth,fitHeight=getHeight()/(double)height;
        double scale;
        switch(mode){
            case "放大":scale=Math.max(fitWidth,fitHeight);break;
            case "宽度适应":scale=fitWidth;break;
            case "高度适应":scale=fitHeight;break;
            default:scale=Math.min(fitWidth,fitHeight);
        }
        int w=mode.equals("拉伸")?getWidth():Math.max(1,(int)Math.round(displayWidth*scale));
        int h=mode.equals("拉伸")?getHeight():Math.max(1,(int)Math.round(height*scale));
        FrameLayout.LayoutParams old=(FrameLayout.LayoutParams)surface.getLayoutParams();
        if(old.width!=w||old.height!=h)surface.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));
    }
    protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);fit();}
    private void releaseEngine(){engineGeneration++;main.removeCallbacks(prepareTimeout);if(engine==null)return;final IMediaPlayer old=engine;final long session=transportSession;engine=null;prepared=false;try{old.setOnPreparedListener(null);old.setOnCompletionListener(null);old.setOnErrorListener(null);old.setOnVideoSizeChangedListener(null);old.setDisplay(null);}catch(Exception ignored){}
        if(old instanceof BlDashPlayer){try{old.release();}catch(Exception ignored){}proxy.retire(session);}else releases.execute(new Runnable(){public void run(){proxy.retire(session);try{old.release();}catch(Exception ignored){}}});
    }
    private void requestPlaying(boolean value){if(closed)return;if(value&&state.equals("completed"))seekTo(0);wantPlaying=value;try{if(engine!=null&&prepared){if(value){engine.start();state="playing";danmaku.start();}else{engine.pause();state="paused";}}}catch(Exception ignored){}updatePlaybackIcon();showControls();}
    void toggle(){requestPlaying(state.equals("completed")||!wantPlaying);}
    boolean speed(float value){if(systemPlayer&&!(engine instanceof BlDashPlayer)&&value!=1)return false;playbackSpeed=value;prefs.edit().putFloat("play_speed",value).apply();applySpeed();return true;}
    private void applySpeed(){if(engine instanceof IjkMediaPlayer)((IjkMediaPlayer)engine).setSpeed(playbackSpeed);else if(engine instanceof BlDashPlayer)((BlDashPlayer)engine).speed(playbackSpeed);}
    void refreshSettings(){danmaku.configure();fit();refreshButtons();showControls();}
    private void updateTime(long position,long duration){
        if(live){setTextChanged(time,"直播中");return;}
        String elapsed=BlUi.time(position),display=elapsed+"  ·  "+BlUi.time(duration);
        if(TextUtils.equals(time.getText(),display))return;
        SpannableString displayText=new SpannableString(display);
        displayText.setSpan(new StyleSpan(Typeface.BOLD),0,elapsed.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        displayText.setSpan(new ForegroundColorSpan(BlUi.SUB),elapsed.length(),display.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        time.setText(displayText);
    }
    void showControls(){if(closed)return;lastBack=0;controls.setVisibility(VISIBLE);clock.setVisibility(VISIBLE);hideHint.run();layoutOverlays();main.removeCallbacks(hide);main.postDelayed(hide,6000);}
    boolean back(){long now=SystemClock.elapsedRealtime();if(now-lastBack<1600){lastBack=0;return false;}lastBack=now;hide.run();Toast.makeText(ui.context,"再按一次返回退出播放",Toast.LENGTH_SHORT).show();return true;}
    boolean key(KeyEvent e){int code=e.getKeyCode();if(code==KeyEvent.KEYCODE_MENU){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)actions.settings();return true;}
        if(code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||code==KeyEvent.KEYCODE_MEDIA_PLAY||code==KeyEvent.KEYCODE_MEDIA_PAUSE){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){if(code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)toggle();else requestPlaying(code==KeyEvent.KEYCODE_MEDIA_PLAY);}return true;}
        if(code==KeyEvent.KEYCODE_MEDIA_NEXT||code==KeyEvent.KEYCODE_MEDIA_PREVIOUS){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)actions.step(code==KeyEvent.KEYCODE_MEDIA_NEXT?1:-1);return true;}
        if(code==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||code==KeyEvent.KEYCODE_MEDIA_REWIND){if(e.getAction()==KeyEvent.ACTION_DOWN)seekTo(position()+(code==KeyEvent.KEYCODE_MEDIA_REWIND?-10000:10000));return true;}
        if(code==KeyEvent.KEYCODE_MEDIA_STOP){if(e.getAction()==KeyEvent.ACTION_DOWN){requestPlaying(false);seekTo(0);}return true;}
        if(controls.getVisibility()!=VISIBLE){
            if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT){
                if(!live&&e.getAction()==KeyEvent.ACTION_DOWN){
                    seekTo(position()+(code==KeyEvent.KEYCODE_DPAD_LEFT?-10000:10000));
                    hint.setText(BlUi.time(position()));
                    hint.setVisibility(VISIBLE);
                    main.removeCallbacks(hideHint);
                    main.postDelayed(hideHint,1400);
                }
                return true;
            }
            if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER
                    ||code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN){
                if(e.getAction()==KeyEvent.ACTION_DOWN){showControls();toggle.requestFocusFromTouch();}
                return true;
            }
        }
        if(e.getAction()==KeyEvent.ACTION_DOWN){main.removeCallbacks(hide);main.postDelayed(hide,6000);}return false;
    }
    void active(boolean value){active=value;danmaku.active(value);main.removeCallbacks(tick);if(!value){if(engine!=null&&prepared)try{engine.pause();}catch(Exception ignored){}main.removeCallbacks(hide);}else{if(engine!=null&&prepared&&wantPlaying)try{engine.start();}catch(Exception ignored){}main.post(tick);danmaku.start();}}
    public void surfaceCreated(SurfaceHolder h){if(engine==null)start();}public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){position=position();seekWhenReady=live?0:Math.max(0,position-segmentStart);releaseEngine();}
    void close(){if(closed)return;closed=true;main.removeCallbacksAndMessages(null);danmaku.close();releaseEngine();releases.shutdown();}
    boolean awaitRelease(long seconds)throws InterruptedException{return releases.awaitTermination(seconds,TimeUnit.SECONDS);}
    private final class TimeBar extends View {
        final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG);long at,length;int buffer;
        TimeBar(){super(ui.context);setContentDescription("播放进度");}
        void update(long at,long length,int buffered){if(seeking){if(buffer==buffered)return;buffer=buffered;}else{if(this.at==at&&this.length==length&&buffer==buffered)return;this.at=at;this.length=length;buffer=buffered;}invalidate();}
        private int inset(){return ui.px(14);}
        protected void onDraw(Canvas c){float left=inset(),right=getWidth()-left,y=getHeight()/2f,half=ui.px(2.5f);brush.setColor(0x55ffffff);c.drawRect(left,y-half,right,y+half,brush);brush.setColor(0x99ffffff);c.drawRect(left,y-half,left+(right-left)*buffer/100f,y+half,brush);float x=left+(right-left)*(length>0?Math.min(1,at/(float)length):0);brush.setColor(ui.theme.accent);c.drawRect(left,y-half,x,y+half,brush);brush.setColor(isFocused()?ui.theme.accent:0xffffffff);c.drawCircle(x,y,ui.px(isFocused()?9:7),brush);}
        public boolean onTouchEvent(MotionEvent e){showControls();seeking=true;length=duration();at=(long)(Math.max(0,Math.min(1,(e.getX()-inset())/Math.max(1,getWidth()-2*inset())))*length);updateTime(at,length);invalidate();if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){seeking=false;if(e.getAction()==MotionEvent.ACTION_UP)seekTo(at);}return true;}
        public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT){seekTo(position()+(code==KeyEvent.KEYCODE_DPAD_LEFT?-10000:10000));return true;}if(code==KeyEvent.KEYCODE_DPAD_CENTER){toggle.requestFocus();return true;}return super.onKeyDown(code,event);}
    }
}
