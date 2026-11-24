package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridView;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.security.MessageDigest;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;

/** Real TVBox fixture -> host MP3 playback -> downloaded classic/optical artwork.
 * Push the final JAR as tvbox-layout.jar. The fixture /config exposes a music album
 * backed by an owned 90s MP3; optional field/class arguments support an R8 build. */
public final class PluginAudioPresentationInstrumentation extends Instrumentation {
    private Activity activity;
    private ChannelPlugin plugin;
    private View root;
    private Bundle args;
    private String base;
    private int port;
    private interface Ready { boolean get() throws Exception; }
    @Override public void onCreate(Bundle value) { args=value; base=value.getString("base"); super.onCreate(value); start(); }
    private void log(String text) { Bundle b=new Bundle(); b.putString("stream",text+"\n"); sendStatus(0,b); }
    private void check(boolean ok,String text) { if(!ok)throw new AssertionError(text); log("PASS "+text); }
    private Object field(Object owner,String name) throws Exception { Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private void engine(String value) throws Exception { Field f=activity.getClass().getDeclaredField(args.getString("engineField","playerEngine")); f.setAccessible(true); runOnMainSync(()->{try{f.set(activity,value);}catch(Exception e){throw new RuntimeException(e);}}); }
    private Object player() throws Exception { return field(activity,args.getString("playerField","player")); }
    private void waitFor(String name,Ready ready) throws Exception { long end=SystemClock.elapsedRealtime()+35000; while(SystemClock.elapsedRealtime()<end){if(ready.get())return;SystemClock.sleep(80);}throw new AssertionError("Timeout: "+name); }
    private JSONObject api(String path,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection(); c.setConnectTimeout(1000);c.setReadTimeout(30000);
        if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}}
        try(InputStream in=c.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);return new JSONObject(new String(out.toByteArray(),"UTF-8"));}finally{c.disconnect();}
    }
    private void findPort() throws Exception { waitFor("host control server",()->{for(port=9966;port<=9975;port++)try{if(api("/api/media?detail=0",null).optBoolean("ok"))return true;}catch(IOException ignored){}return false;}); }
    private ChannelPlugin currentPlugin() throws Exception {
        for(Field f:activity.getClass().getDeclaredFields()){if(!f.getType().getName().startsWith("xiao.bu.tv."))continue;f.setAccessible(true);Object runtime=f.get(activity);if(runtime==null)continue;for(Field g:runtime.getClass().getDeclaredFields())if(ChannelPlugin.class.isAssignableFrom(g.getType())){g.setAccessible(true);Object p=g.get(runtime);if(p instanceof ChannelPlugin)return (ChannelPlugin)p;}}
        return null;
    }
    private void open(String url,String name) {
        runOnMainSync(()->{try{for(Method m:activity.getClass().getDeclaredMethods()){Class<?>[] p=m.getParameterTypes();if(p.length==5&&p[0]==String.class&&p[1]==String.class&&p[2]==File.class&&p[3]==Bitmap.class&&p[4]==boolean.class){m.setAccessible(true);m.invoke(activity,url,name,null,null,false);return;}}throw new AssertionError("Quick play entry missing");}catch(Exception e){throw new RuntimeException(e);}});
    }
    private void ready() throws Exception { waitFor("TVBox",()->{plugin=currentPlugin();return plugin!=null&&plugin.getClass().getName().endsWith("TvBoxPlugin")&&field(plugin,"pending")==null;});root=(View)field(plugin,"root"); }
    private void grid(int index) throws Exception {
        runOnMainSync(()->{try{GridView g=(GridView)field(plugin,"grid");g.setSelection(index);g.performItemClick(null,index,g.getAdapter().getItemId(index));}catch(Exception e){throw new RuntimeException(e);}});
        waitFor("page load",()->field(plugin,"pending")==null);waitForIdleSync();
    }
    private Object artwork() throws Exception {
        int id=activity.getResources().getIdentifier("audio_artwork","id","xiao.bu.tv");View view=activity.findViewById(id);
        if(view==null)return null;
        for(Field f:view.getClass().getDeclaredFields())if(f.getType().getName().equals("xiao.bu.tv.radio.RadioUi")){f.setAccessible(true);return f.get(view);}return null;
    }
    private void title(String expected) throws Exception { waitFor("track title "+expected,()->artwork()!=null&&expected.equals(field(artwork(),"title"))); check(true,"artwork title = "+expected); }
    private void choose(String style) throws Exception { check(api("/api/radio-ui",new JSONObject().put("id",style)).optString("selected").equals(style),"selected "+style);waitFor(style+" applied",()->artwork()!=null&&((Boolean)field(artwork(),"optical"))==style.equals("optical")); }
    private void audio() throws Exception { waitFor("audio playing",()->{JSONObject s=api("/api/media?detail=0",null);return s.optBoolean("playing")&&s.optBoolean("audioOnly");}); }
    private View hostView(String name) { return activity.findViewById(activity.getResources().getIdentifier(name,"id","xiao.bu.tv")); }
    private void noChannelCard(String label) { waitForIdleSync();check(hostView("channel_bar")!=null&&hostView("channel_bar").getVisibility()!=View.VISIBLE,label+": channel card hidden"); }
    private View named(View view,String description) { if(description.equals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=named(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null; }
    private void screenshot(String name) throws Exception { Bitmap b=getUiAutomation().takeScreenshot();check(b!=null,"screenshot "+name);File p=new File(activity.getExternalFilesDir(null),"audio-"+name+".png");try(OutputStream out=new FileOutputStream(p)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();log("CAPTURE "+name+" "+p); }
    private void install() throws Exception {
        File source=new File(activity.getExternalFilesDir(null),"tvbox-layout.jar"),dir=new File(activity.getFilesDir(),"installed-plugins");dir.mkdirs();
        byte[] bytes=new byte[(int)source.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(source))){in.readFully(bytes);}StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format(java.util.Locale.US,"%02x",b&255));
        File jar=new File(dir,sha+".jar");if(!jar.isFile()){try(OutputStream out=new FileOutputStream(jar)){out.write(bytes);}jar.setReadOnly();}check(activity.getSharedPreferences("installed-plugins",0).edit().putString("tvbox",sha.toString()).commit(),"fixture uses final TVBox JAR");
    }
    private int items() throws Exception { return ((GridView)field(plugin,"grid")).getCount(); }
    private void nativeRemote() throws Exception {
        setInTouchMode(false);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("one item right",()->((GridView)field(plugin,"grid")).getSelectedItemPosition()==1);check(true,"remote right moves exactly one item");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);waitFor("one item left",()->((GridView)field(plugin,"grid")).getSelectedItemPosition()==0);check(true,"remote left moves exactly one item");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);waitFor("standard OK",()->field(plugin,"pending")==null&&items()==1);check(true,"standard remote OK opens site");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor("site list",()->items()==3);
        for(int code:new int[]{KeyEvent.KEYCODE_BUTTON_SELECT,KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_NUMPAD_ENTER}){
            waitForIdleSync();SystemClock.sleep(150);View focus=root.findFocus();log("REMOTE "+code+" before index="+((GridView)field(plugin,"grid")).getSelectedItemPosition()+" focus="+(focus instanceof android.widget.TextView?((android.widget.TextView)focus).getText():focus==null?"none":focus.getClass().getSimpleName()));
            sendKeyDownUpSync(code);SystemClock.sleep(150);log("REMOTE "+code+" after count="+items()+" index="+((GridView)field(plugin,"grid")).getSelectedItemPosition());waitFor("vendor OK "+code,()->field(plugin,"pending")==null&&items()==1);check(true,"vendor OK "+code+" opens site");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor("site list",()->items()==3);
        }
        runOnMainSync(()->root.clearFocus());sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("remote focus recovery",()->root.findFocus()!=null);check(true,"direction key restores lost native focus");
        runOnMainSync(()->{try{((GridView)field(plugin,"grid")).setSelection(0);}catch(Exception e){throw new RuntimeException(e);}});
    }
    private boolean focused(String description) { View focused=activity.getCurrentFocus();return focused!=null&&android.text.TextUtils.equals(description,focused.getContentDescription()); }
    private boolean activeText(String value) { android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;try{java.util.List<android.view.accessibility.AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByText(value);boolean present=!found.isEmpty();for(android.view.accessibility.AccessibilityNodeInfo node:found)node.recycle();return present;}finally{root.recycle();} }
    private void playerRemote(String mode) throws Exception {
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);waitFor("pause button focus",()->focused("暂停"));check(true,mode+" remote opens focused player controls");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_SELECT);waitFor("OK pauses",()->!api("/api/media?detail=0",null).optBoolean("playing"));noChannelCard(mode+" remote pause");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A);waitFor("OK resumes",()->api("/api/media?detail=0",null).optBoolean("playing"));
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("forward button focus",()->focused("快进 5 秒"));long position=api("/api/media?detail=0",null).optLong("positionMs");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_SELECT);waitFor("focused forward button",()->api("/api/media?detail=0",null).optLong("positionMs")>=position+4000);check(true,mode+" remote can activate a focused player button");
        View tracks=named(activity.getWindow().getDecorView(),"音轨与字幕");
        if(tracks!=null&&tracks.isShown()){
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("tracks focus",()->focused("音轨与字幕"));sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_SELECT);waitFor("tracks dialog",()->activeText("关闭内嵌字幕"));sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A);waitFor("tracks choice confirmed",()->!activeText("关闭内嵌字幕"));check(true,mode+" vendor OK selects an audio track in the dialog");
            waitFor("tracks focus restored",()->activity.hasWindowFocus()&&focused("音轨与字幕"));waitForIdleSync();sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("speed focus",()->focused("倍速"));sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_SELECT);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("speed choice focus",()->{View current=activity.getCurrentFocus();return current instanceof android.widget.TextView&&"1.0×".contentEquals(((android.widget.TextView)current).getText());});sendKeyDownUpSync(KeyEvent.KEYCODE_NUMPAD_ENTER);waitFor("speed choice confirmed",()->focused("倍速"));check(true,mode+" remote selects a playback speed");
        }
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);api("/api/media/control",new JSONObject().put("action","pause"));waitFor("pause before hold",()->!api("/api/media?detail=0",null).optBoolean("playing"));
        long before=api("/api/media?detail=0",null).optLong("positionMs"),down=SystemClock.uptimeMillis();sendKeySync(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,0));SystemClock.sleep(1400);
        check(Math.abs(api("/api/media?detail=0",null).optLong("positionMs")-before)<700,mode+" long press previews without seeking");sendKeySync(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT,0));waitFor("held seek commit",()->api("/api/media?detail=0",null).optLong("positionMs")>before+6000);check(true,mode+" long press commits on release");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);sendKeyDownUpSync(KeyEvent.KEYCODE_NUMPAD_ENTER);waitFor("OK opens controls",()->focused("播放"));check(!api("/api/media?detail=0",null).optBoolean("playing"),mode+" opening controls does not also toggle playback");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("episodes focus",()->focused("选集"));screenshot("remote-controls-"+mode);sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_SELECT);waitFor("remote returns to episodes",()->root.isShown());check(true,mode+" remote opens episode list");
    }
    private long dashPosition(Object player) throws Exception { return ((Number)player.getClass().getMethod("getCurrentPosition").invoke(player)).longValue(); }
    private boolean dashPlaying(Object player) throws Exception { return (Boolean)player.getClass().getMethod("getPlayWhenReady").invoke(player); }
    private void dashRemote(String uri) throws Exception {
        ChannelPlugin before=plugin;open(uri,"TVBox");waitFor("DASH page",()->currentPlugin()!=null&&currentPlugin()!=before);ready();grid(2);grid(0);grid(0);waitFor("DASH player",()->field(plugin,"dashPlayer")!=null);
        Object dash=field(plugin,"dashPlayer"),player=field(dash,"player");waitFor("DASH frame",()->(Boolean)field(dash,"rendered")&&((Number)player.getClass().getMethod("getPlaybackState").invoke(player)).intValue()==3);
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);waitFor("DASH focused pause",()->focused("暂停"));sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_SELECT);waitFor("DASH paused",()->!dashPlaying(player));sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A);waitFor("DASH resumed",()->dashPlaying(player));check(true,"DASH remote confirms pause and resume");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("DASH forward focus",()->focused("快进 5 秒"));long position=dashPosition(player);sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_SELECT);waitFor("DASH focused seek",()->dashPosition(player)>=position+4000);check(true,"DASH remote activates focused seek button");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);waitFor("DASH paused before hold",()->!dashPlaying(player));long at=dashPosition(player),down=SystemClock.uptimeMillis();sendKeySync(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,0));SystemClock.sleep(1400);check(Math.abs(dashPosition(player)-at)<700,"DASH hold previews without seeking");sendKeySync(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT,0));waitFor("DASH held commit",()->dashPosition(player)>at+6000);check(true,"DASH hold commits on release");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("DASH episodes focus",()->focused("选集"));noChannelCard("DASH remote");screenshot("remote-controls-dash");sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_SELECT);waitFor("DASH return to episodes",()->root.isShown());check(true,"DASH remote opens episode list");
    }
    @Override public void onStart() {
        int result=Activity.RESULT_CANCELED;String originalEngine=null;
        try {
            log("LAUNCH_HOST");activity=startActivitySync(new Intent().setClassName("xiao.bu.tv","xiao.bu.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            originalEngine=(String)field(activity,args.getString("engineField","playerEngine"));install();engine("ijk");
            String uri="ntv-plugin://local/tvbox.jar?url="+URLEncoder.encode(base+"/config","UTF-8");open(uri,"TVBox");ready();findPort();nativeRemote();grid(0);grid(0);grid(0);audio();choose("classic");title("BIGBANG - IF YOU");
            ChannelPlugin.VodHost oldHost=(ChannelPlugin.VodHost)field(plugin,"host");Object first=player();long position=api("/api/media?detail=0",null).optLong("positionMs");
            choose("optical");title("BIGBANG - IF YOU");check(player()==first&&api("/api/media?detail=0",null).optBoolean("playing"),"style switch keeps the same audio player");check(api("/api/media?detail=0",null).optLong("positionMs")>=position,"style switch preserves progress");screenshot("optical-first-track");
            for(int i=0;i<4&&!root.isShown();i++){sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);SystemClock.sleep(150);}check(root.isShown(),"returned to song list");grid(1);audio();title("BIGBANG - 하루하루");check(player()!=first,"new song has a new playback session");
            engine("system");ChannelPlugin previous=plugin;open(uri,"TVBox");waitFor("new TVBox session",()->currentPlugin()!=null&&currentPlugin()!=previous);ready();grid(0);grid(0);grid(1);audio();title("BIGBANG - 하루하루");check(player().getClass().getName().equals(args.getString("systemPlayerClass","tv.danmaku.ijk.media.player.AndroidMediaPlayer")),"system player also receives current song title");
            runOnMainSync(()->oldHost.playVod(base+"/audio.mp3",new JSONObject(),"STALE TITLE"));
            Object runtime=field(activity,args.getString("runtimeField","jarChannelRuntime"));check("BIGBANG - 하루하루".equals(field(runtime,args.getString("vodTitleField","vodTitle"))),"stale plugin cannot replace active metadata");title("BIGBANG - 하루하루");
            Object system=player();choose("classic");choose("optical");title("BIGBANG - 하루하루");check(player()==system,"system player survives both artwork switches");screenshot("optical-system-track");
            for(String mode:new String[]{"ijk","system"}){
                engine(mode);ChannelPlugin before=plugin;open(uri,"TVBox");waitFor("new video page",()->currentPlugin()!=null&&currentPlugin()!=before);ready();grid(1);grid(0);grid(0);
                waitFor(mode+" video frame",()->{JSONObject s=api("/api/media?detail=0",null);return s.optBoolean("playing")&&!s.optBoolean("audioOnly")&&s.optBoolean("videoFrameConfirmed");});noChannelCard(mode+" startup");
                api("/api/media/control",new JSONObject().put("action","pause"));waitFor("video paused",()->!api("/api/media?detail=0",null).optBoolean("playing"));noChannelCard(mode+" pause");
                api("/api/media/control",new JSONObject().put("action","play"));waitFor("video resumed",()->api("/api/media?detail=0",null).optBoolean("playing"));noChannelCard(mode+" resume");
                ChannelPlugin.Host current=(ChannelPlugin.Host)field(plugin,"host");runOnMainSync(()->current.status("播放提示样本"));waitForIdleSync();noChannelCard(mode+" notice");
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitFor("VOD controls",()->{View episodes=named(activity.getWindow().getDecorView(),"选集");return episodes!=null&&episodes.isShown();});noChannelCard(mode+" VOD controls");screenshot("video-no-channel-card-"+mode);
                playerRemote(mode);
            }
            dashRemote(uri);open(base+"/audio.mp3","Ordinary audio");audio();title("Ordinary audio");check(currentPlugin()==null,"leaving plugin clears its metadata");
            api("/api/media/control",new JSONObject().put("action","pause"));waitFor("ordinary channel card",()->hostView("channel_bar").getVisibility()==View.VISIBLE);check(true,"ordinary channels retain their information card");
            result=Activity.RESULT_OK;
        } catch(Throwable failure) { log("FAIL "+android.util.Log.getStackTraceString(failure)); }
        finally { if(originalEngine!=null)try{engine(originalEngine);}catch(Exception ignored){} }
        Bundle done=new Bundle();done.putString("stream","PLUGIN_AUDIO="+(result==Activity.RESULT_OK?"PASS":"FAIL")+"\n");finish(result,done);
    }
}
