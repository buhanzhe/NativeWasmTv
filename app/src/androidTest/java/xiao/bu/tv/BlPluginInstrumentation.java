package xiao.bu.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.*;

/** Real host + downloaded JAR + deterministic API/MP4 fixtures. Compatible with API 14. */
public final class BlPluginInstrumentation extends Instrumentation {
    private Bundle args;private volatile MainActivity activity;private JarChannelRuntime runtime;private Object plugin;private View root;
    private java.util.Map<String,?> savedHostPreferences;
    private java.util.Map<String,?> savedBlPreferences;private final java.util.concurrent.atomic.AtomicInteger blockedWrites=new java.util.concurrent.atomic.AtomicInteger();
    private void guardAccountReads()throws Exception{
        savedBlPreferences=getTargetContext().getSharedPreferences("ntv-bl",0).getAll();
        okhttp3.OkHttpClient client=NetworkClient.sharedClient().newBuilder().addInterceptor(new okhttp3.Interceptor(){public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain)throws IOException{
            okhttp3.Request request=chain.request();String host=request.url().host();
            if((host.equals("bilibili.com")||host.endsWith(".bilibili.com"))&&!request.method().equals("GET")&&!request.method().equals("HEAD")){blockedWrites.incrementAndGet();throw new IOException("Account audit is read-only: blocked "+request.method()+" "+request.url().encodedPath());}
            return chain.proceed(request);
        }}).build();
        for(String name:new String[]{"client","factory"}){Field f=NetworkClient.class.getDeclaredField(name);f.setAccessible(true);f.set(null,name.equals("client")?client:new okhttp3.OkUrlFactory(client));}
    }
    private void restoreBlSettings(){if(savedBlPreferences==null)return;android.content.SharedPreferences p=getTargetContext().getSharedPreferences("ntv-bl",0);android.content.SharedPreferences.Editor edit=p.edit();java.util.Set<String> keys=new java.util.HashSet<String>(savedBlPreferences.keySet());keys.addAll(p.getAll().keySet());
        for(String k:keys){if(k.equals("cookies")||k.equals("user")||k.equals("history")||k.startsWith("position:"))continue;Object v=savedBlPreferences.get(k);if(v==null)edit.remove(k);else if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);else if(v instanceof Float)edit.putFloat(k,(Float)v);else if(v instanceof Integer)edit.putInt(k,(Integer)v);else if(v instanceof Long)edit.putLong(k,(Long)v);}edit.commit();savedBlPreferences=null;
    }
    private void preserveHostSelection(){savedHostPreferences=getTargetContext().getSharedPreferences("tv_player",0).getAll();}
    private void seedLocalJar()throws Exception{String source=args.getString("localJar");if(source==null)return;String expected=android.net.Uri.parse(args.getString("source")).getQueryParameter("sha256");java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");File dir=new File(getTargetContext().getFilesDir(),"channel-jars");check(dir.isDirectory()||dir.mkdirs(),"Cannot create test plugin cache");File target=new File(dir,expected+".jar");if(target.exists())return;
        try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1){digest.update(buffer,0,count);out.write(buffer,0,count);}}StringBuilder actual=new StringBuilder();for(byte b:digest.digest())actual.append(String.format(java.util.Locale.US,"%02x",b&255));if(!expected.equals(actual.toString())){target.delete();throw new IOException("Local test plugin hash mismatch");}check(target.setReadOnly(),"Cannot protect test plugin");}
    private void restoreHostSelection(){
        if(savedHostPreferences==null)return;
        android.content.SharedPreferences p=getTargetContext().getSharedPreferences("tv_player",0);android.content.SharedPreferences.Editor e=p.edit();
        java.util.Set<String> keys=new java.util.HashSet<String>(savedHostPreferences.keySet());keys.addAll(p.getAll().keySet());
        for(String key:keys)if(key.startsWith("last_channel_")||key.equals("last_group_index_v2")){
            Object value=savedHostPreferences.get(key);if(value==null)e.remove(key);else if(value instanceof String)e.putString(key,(String)value);else if(value instanceof Integer)e.putInt(key,(Integer)value);else if(value instanceof Long)e.putLong(key,(Long)value);
        }
        e.commit();savedHostPreferences=null;
    }
    public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    public void callActivityOnResume(Activity a){super.callActivityOnResume(a);if(a instanceof MainActivity){a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);activity=(MainActivity)a;}}
    private static Object field(Object o,String name)throws Exception{Class<?> c=o.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private String route()throws Exception{return String.valueOf(field(field(plugin,"route"),"kind"));}
    private void waitRoute(String kind)throws Exception{long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(route().equals(kind))return;SystemClock.sleep(100);}throw new AssertionError("Expected route "+kind+", got "+route());}
    private void waitItems()throws Exception{long end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){if(((java.util.List<?>)field(plugin,"items")).size()>0)return;SystemClock.sleep(100);}throw new AssertionError("No cards loaded");}
    private static View findButton(View node,String title){
        View result=null;
        if(node instanceof Button&&((Button)node).getText().toString().equals(title)
                || (node instanceof Button||node instanceof ImageButton)&&title.equals(node.getContentDescription()))result=node;
        if(node instanceof ViewGroup){
            ViewGroup group=(ViewGroup)node;
            for(int i=0;i<group.getChildCount();i++){
                View found=findButton(group.getChildAt(i),title);
                if(found!=null)result=found;
            }
        }
        return result;
    }
    private static EditText findEdit(View node){if(node instanceof EditText)return (EditText)node;if(node instanceof ViewGroup){ViewGroup g=(ViewGroup)node;for(int i=0;i<g.getChildCount();i++){EditText e=findEdit(g.getChildAt(i));if(e!=null)return e;}}return null;}
    private static boolean containsText(View node,String text){if(node instanceof TextView&&((TextView)node).getText().toString().contains(text))return true;if(node instanceof ViewGroup){ViewGroup g=(ViewGroup)node;for(int i=0;i<g.getChildCount();i++)if(containsText(g.getChildAt(i),text))return true;}return false;}
    private void click(String title)throws Exception{
        final View[] target=new View[1];long end=SystemClock.elapsedRealtime()+15000;
        do{onMain(new Runnable(){public void run(){target[0]=findButton(root,title);}});if(target[0]!=null&&target[0].isShown())break;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<end);
        check(target[0]!=null&&target[0].isShown(),"Button missing: "+title);
        onMain(new Runnable(){public void run(){target[0].requestFocusFromTouch();}});SystemClock.sleep(350);int[] point=new int[2];onMain(new Runnable(){public void run(){target[0].getLocationOnScreen(point);}});float x=point[0]+target[0].getWidth()/2f,y=point[1]+target[0].getHeight()/2f;
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now+60,MotionEvent.ACTION_UP,x,y,0);if(Build.VERSION.SDK_INT<=15){
            down.setLocation(target[0].getWidth()/2f,target[0].getHeight()/2f);
            up.setLocation(target[0].getWidth()/2f,target[0].getHeight()/2f);
            onMain(new Runnable(){public void run(){target[0].dispatchTouchEvent(down);}});
            SystemClock.sleep(60);
            onMain(new Runnable(){public void run(){target[0].dispatchTouchEvent(up);}});
        }else{sendPointerSync(down);SystemClock.sleep(60);sendPointerSync(up);}
        down.recycle();up.recycle();SystemClock.sleep(150);
    }
    private void key(int code){sendKeyDownUpSync(code);SystemClock.sleep(200);}
    private void focusButton(String title){onMain(()->{
        View button=findButton(root,title);
        check(button!=null&&button.requestFocusFromTouch(),"Unable to focus "+title);
    });}
    private void verifyBrowseIcons()throws Exception{
        onMain(()->{try{
            View sidebar=(View)field(plugin,"sidebar");
            Object images=field(plugin,"images");
            check(!containsText(sidebar,"BL"),"The BL heading is still in the rail");
            for(String[] icon:new String[][]{
                    {"搜索","icon_search"},{"首页","icon_home"},{"分区","tab_recommend"},
                    {"动态","tab_dynamic"},{"直播","tab_live"},{"我的","tab_me"},{"设置","icon_setting"}}){
                Button button=(Button)findButton(sidebar,icon[0]);
                checkIcon(button,1,images,icon[1]);
            }
            View card=((GridView)field(plugin,"grid")).getChildAt(0);
            View cover=(View)field(card,"cover");
            check(Math.abs(cover.getWidth()*9-cover.getHeight()*16)<=8,"Cover is not measured at 16:9");
            checkIcon((TextView)field(card,"views"),0,images,"icon_video_play_count");
            checkIcon((TextView)field(card,"danmaku"),0,images,"icon_video_danmaku");
            checkIcon((TextView)field(card,"owner"),0,images,"icon_video_up");
        }catch(Exception error){throw new RuntimeException(error);}});
    }
    private void checkIcon(TextView label,int slot,Object images,String name)throws Exception{
        checkIcon(label,label.getCompoundDrawables()[slot],images,name);
    }
    private void checkIcon(View view,android.graphics.drawable.Drawable icon,Object images,String name)throws Exception{
        Bitmap expected=(Bitmap)invoke(images,"asset",name+".png");
        check(view.isShown()&&expected!=null&&icon instanceof android.graphics.drawable.BitmapDrawable
                && ((android.graphics.drawable.BitmapDrawable)icon).getBitmap().sameAs(expected),"Wrong visible icon: "+name);
    }
    private void browseNavigation()throws Exception{
        GridView original=(GridView)field(plugin,"grid");
        onMain(()->{original.requestFocusFromTouch();original.setSelection(0);});
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        check(root.findFocus()==findButton(root,"首页"),"LEFT from the first card did not reach the rail");
        for(String title:new String[]{"分区","动态","直播","我的","用户","设置"}){
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(root.findFocus()==findButton(root,title),"DOWN did not traverse the rail to "+title);
        }
        onMain(()->{try{
            invoke(activity,"closeChannelList");
            check(root.findFocus()==findButton(root,"设置"),"Dismissing the host list stole the plugin focus");
        }catch(Exception error){throw new RuntimeException(error);}});
        key(KeyEvent.KEYCODE_DPAD_UP);
        onMain(()->check(root.findFocus()==findButton(root,"用户"),"UP did not reach the account shortcut. Focus: "+root.findFocus()+" Host: "+activity.getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        onMain(()->check(root.findFocus()==findButton(root,"设置"),"DOWN from account lost focus to "+activity.getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);waitRoute("settings");
        check(root.findFocus()==findButton(root,"返回"),"Remote settings entry did not focus Back");
        key(KeyEvent.KEYCODE_BACK);waitRoute("home");waitItems();
        focusButton("推荐");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check(root.findFocus()==findButton(root,"热门"),"RIGHT did not move between homepage tabs");
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        check(root.findFocus()==findButton(root,"推荐"),"LEFT did not return to the recommendation tab");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        key(KeyEvent.KEYCODE_DPAD_CENTER);waitRoute("popular");waitItems();
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(root.findFocus()==field(plugin,"grid"),"DOWN from the homepage tabs did not enter the video grid");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        int position=((GridView)field(plugin,"grid")).getSelectedItemPosition();
        check(position>0,"Grid did not move right");
        click("影视");waitRoute("movies");waitItems();waitText("电影分类测试");
        click("电视剧");waitText("电视剧分类测试");click("纪录片");waitText("纪录片分类测试");
        click("热门");waitRoute("popular");waitItems();
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(((GridView)field(plugin,"grid")).getSelectedItemPosition()==position,"Homepage section lost its card selection");
        click("分区");waitRoute("region");click("舞蹈");waitRoute("regionItems");waitText("分区视频 1004");
        click("动态");waitRoute("dynamic");waitText("登录后查看关注的 UP 主动态");
        click("直播");waitRoute("live");click("网游");waitRoute("liveArea");waitText("直播分区 2");
        click("首页");
        click("推荐");waitRoute("home");waitItems();
        key(KeyEvent.KEYCODE_DPAD_DOWN);
    }
    private void settingsNavigation()throws Exception{
        click("设置");waitRoute("settings");
        check(((View)field(plugin,"sidebar")).getVisibility()==View.GONE,"Settings did not open as an independent page");
        check(root.findFocus()==findButton(root,"返回"),"Settings did not initially focus Back");
        String[] options={"清除缓存","缓存限制","检查更新","默认启动页面","图片质量","主题","以全屏模式运行"};
        ListView general=(ListView)field(plugin,"settingsList");
        onMain(()->{try{
            Object images=field(plugin,"images");
            for(String[] icon:new String[][]{
                    {"通用设置","icon_setting"},{"播放设置","icon_video_play_count"},{"弹幕设置","icon_video_danmaku"},
                    {"关于应用","icon_about"},{"设备信息","ic_tv"}}){
                checkIcon((TextView)findButton(root,icon[0]),0,images,icon[1]);
            }
            ImageButton back=(ImageButton)findButton(root,"返回");
            checkIcon(back,back.getDrawable(),images,"icon_back");
            ViewGroup row=(ViewGroup)general.getChildAt(0);
            ImageView arrow=(ImageView)row.getChildAt(row.getChildCount()-1);
            checkIcon(arrow,arrow.getDrawable(),images,"icon_arrow_right");
        }catch(Exception error){throw new RuntimeException(error);}});
        check(general.getCount()==options.length,"Unexpected general settings rows");
        for(int i=0;i<options.length;i++){
            String title=(String)field(general.getItemAtPosition(i),"title");
            check(title.split("\n",2)[0].equals(options[i]),"Incorrect general settings order: "+title);
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(root.findFocus()==findButton(root,"通用设置"),"DOWN from Back did not reach the categories");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(root.findFocus()==findButton(root,"播放设置"),"DOWN left the settings categories");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        waitText("默认音轨");
        ListView playback=(ListView)field(plugin,"settingsList");
        waitSelectedRow(playback,1,"RIGHT did not select the nearest audio row");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        waitSelectedRow(playback,2,"DOWN did not advance to the default speed");
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        check(root.findFocus()==findButton(root,"弹幕设置"),"LEFT did not reach the nearest settings category");
        check(findButton(root,"播放设置").isSelected(),"Focus alone changed the active settings category");
        android.content.SharedPreferences prefs=getTargetContext().getSharedPreferences("ntv-bl-instrumentation",0);
        selectRow("默认音轨");option("132Kbps");
        check(prefs.getInt("audio_track",0)==30232,"Default audio track was not saved");
        screenshot("bl-browse-settings.png");
        click("通用设置");
        boolean fullscreen=FullscreenPolicy.isEnabled(getTargetContext());
        try{
            selectRow("以全屏模式运行");
            check(FullscreenPolicy.isEnabled(getTargetContext())!=fullscreen,"BL did not update the host fullscreen preference");
            selectRow("以全屏模式运行");
            check(FullscreenPolicy.isEnabled(getTargetContext())==fullscreen,"Fullscreen switch did not restore the previous setting");
        }finally{
            if(FullscreenPolicy.isEnabled(getTargetContext())!=fullscreen)
                onMain(()->activity.setFullscreenStartup(fullscreen));
        }
        selectRow("主题");option("经典主题");
        check(prefs.getString("theme","").equals("CLASSIC")&&root.getBackground() instanceof android.graphics.drawable.GradientDrawable,"Classic theme backdrop was not applied");
        screenshot("bl-theme-classic.png");
        selectRow("主题");option("粉色");
        check(prefs.getString("theme","").equals("PINK"),"Theme was not saved");
        check(((android.graphics.drawable.ColorDrawable)root.getBackground()).getColor()==0xfffce7ef,"Pink theme was not applied");
        selectRow("图片质量");option("大尺寸");
        selectRow("缓存限制");option("2 MB");
        selectRow("默认启动页面");option("稍后观看");
        check(prefs.getInt("image_quality",0)==640&&prefs.getInt("image_cache_mb",0)==2
                &&prefs.getString("home_section","").equals("later"),"General settings were not saved");
        screenshot("bl-theme-pink.png");
        click("设备信息");waitText(" / API "+Build.VERSION.SDK_INT);
        click("关于应用");waitText("插件版本");
        click("播放设置");selectRow("显示视频详情页");
        click("返回");waitRoute("search");
        check(findEdit(root).getText().toString().equals("BL test")
                &&findButton(root,"全键盘").isSelected(),"Settings back lost the search draft or keyboard mode");
        click("设置");waitRoute("settings");key(KeyEvent.KEYCODE_BACK);waitRoute("search");
        click("首页");waitRoute("home");waitItems();
        focusButton("首页");key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitRoute("player");waitPlaying(null);closePlayback();waitRoute("home");
    }
    private void waitSelectedRow(ListView list,int position,String failure){
        long deadline=SystemClock.elapsedRealtime()+2500;
        boolean[] selected={false};
        do{
            onMain(()->{
                View row=list.getSelectedView();
                selected[0]=list.hasFocus()&&list.getSelectedItemPosition()==position
                        &&row!=null&&row.isSelected()
                        &&row==list.getChildAt(position-list.getFirstVisiblePosition());
            });
            if(selected[0])return;
            SystemClock.sleep(16);
        }while(SystemClock.elapsedRealtime()<deadline);
        check(false,failure);
    }
    private void followedSeasonPages()throws Exception{
        final Object client=field(plugin,"api");
        @SuppressWarnings("unchecked")
        java.util.List<okhttp3.Cookie> cookies=(java.util.List<okhttp3.Cookie>)field(client,"cookies");
        synchronized(client){
            cookies.add(new okhttp3.Cookie.Builder().name("DedeUserID").value("123").domain("fixture.invalid").build());
            cookies.add(new okhttp3.Cookie.Builder().name("SESSDATA").value("local-fixture").domain("fixture.invalid").build());
        }
        try{
            click("追番");waitRoute("animeFollows");waitText("追番测试剧集");
            click("追剧");waitRoute("dramaFollows");waitText("追剧测试剧集");
        }finally{onMain(()->{try{invoke(client,"logout");}catch(Exception error){throw new RuntimeException(error);}});}
        click("稍后观看");waitRoute("later");waitText("登录后查看稍后观看");
    }
    private void reopenWithSavedSettings()throws Exception{
        Object previous=plugin;
        onMain(()->{try{invoke(activity,"startChannel",0);}catch(Exception error){throw new RuntimeException(error);}});
        long deadline=SystemClock.elapsedRealtime()+10000;
        Object reopened=null;
        while(SystemClock.elapsedRealtime()<deadline){
            reopened=field(runtime,"plugin");
            if(reopened!=null&&reopened!=previous)break;
            SystemClock.sleep(100);
        }
        check(reopened!=null&&reopened!=previous,"The BL channel did not reopen");
        plugin=reopened;root=(View)field(runtime,"view");
        waitRoute("later");waitText("登录后查看稍后观看");
        deadline=SystemClock.elapsedRealtime()+10000;
        while((root.getWidth()==0||!root.hasWindowFocus())&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
        check(root.getWidth()>0&&root.hasWindowFocus(),"Reopened BL view was not ready for input");
        check(((android.graphics.drawable.ColorDrawable)root.getBackground()).getColor()==0xfffce7ef,"Pink theme was lost after reopening");
    }
    private static ListView findList(View node){if(node instanceof ListView)return (ListView)node;if(node instanceof ViewGroup){ViewGroup g=(ViewGroup)node;for(int i=0;i<g.getChildCount();i++){ListView l=findList(g.getChildAt(i));if(l!=null)return l;}}return null;}
    private void option(final String title)throws Exception{final ListView[] list=new ListView[1];final int[] index={-1};long end=SystemClock.elapsedRealtime()+10000;do{onMain(new Runnable(){public void run(){try{View modal=(View)field(plugin,"modal");if(modal==null)return;list[0]=findList(modal);if(list[0]==null)return;for(int i=0;i<list[0].getCount();i++)if(String.valueOf(field(list[0].getAdapter().getItem(i),"title")).split("\n",2)[0].equals(title))index[0]=i;}catch(Exception e){throw new RuntimeException(e);}}});if(index[0]>=0)break;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<end);check(index[0]>=0,"Missing option: "+title);onMain(new Runnable(){public void run(){list[0].requestFocusFromTouch();list[0].setSelection(index[0]);}});SystemClock.sleep(150);key(KeyEvent.KEYCODE_DPAD_CENTER);}
    private Object waitPlaying(Object previous)throws Exception{long end=SystemClock.elapsedRealtime()+25000;while(SystemClock.elapsedRealtime()<end){Object p=field(plugin,"player");if(p!=null&&"playing".equals(field(p,"state"))&&field(p,"engine")!=null&&field(p,"engine")!=previous)return field(p,"engine");SystemClock.sleep(100);}throw new AssertionError("Playback did not become ready after switch");}
    private void controls()throws Exception{Object p=field(plugin,"player");if(p!=null&&((View)field(p,"controls")).getVisibility()!=View.VISIBLE)key(KeyEvent.KEYCODE_DPAD_CENTER);}

    private Object selectPlaybackOption(Object player,String menu,String value)throws Exception{
        Object previous=field(player,"engine");
        controls();key(KeyEvent.KEYCODE_MENU);option(menu);option(value);
        return waitPlaying(previous);
    }

    private JSONObject selectedStream()throws Exception{return (JSONObject)field(plugin,"selectedStream");}

    private long playbackPosition(Object player)throws Exception{
        long[] position={0};
        onMain(()->{
            try{position[0]=((Number)invoke(player,"position")).longValue();}
            catch(Exception error){throw new RuntimeException(error);}
        });
        return position[0];
    }

    private void focusedPlayback(String bvid,Bundle result)throws Exception{
        navigateAudit("detail","视频详情",bvid);
        waitRoute("detail");click("播放");waitRoute("player");
        Object engine=waitPlaying(null),player=field(plugin,"player");
        long start=playbackPosition(player),end=SystemClock.elapsedRealtime()+17000;
        while(playbackPosition(player)-start<1000&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(400);
        check(playbackPosition(player)-start>=1000,"Playback stalled before advancing one second");
        start=playbackPosition(player);
        int seconds=Math.min(30,Math.max(10,Integer.parseInt(args.getString("soakSeconds","10"))));
        SystemClock.sleep(seconds*1000L);
        long advanced=playbackPosition(player)-start;
        check(advanced>=seconds*900L&&"playing".equals(field(player,"state")),"Public video playback stalled during observation");
        Object playingEngine=field(player,"engine");
        check(playingEngine!=null&&engine.getClass()==playingEngine.getClass(),"Playback fell back to another engine during observation");
        screenshot("bl-focused-player.png");
        JSONObject stream=selectedStream();
        result.putString("stream","PASS: "+bvid+" advanced "+advanced+" ms with "+engine.getClass().getSimpleName()+
                ", qn="+stream.optInt("bl_quality")+", codec="+stream.optString("bl_codec"));
    }

    private void publicPlaybackDiagnostics()throws Exception{
        Object diagnostics=field(field(plugin,"api"),"diagnostics");
        java.util.List<?> entries=(java.util.List<?>)invoke(diagnostics,"snapshot");
        StringBuilder details=new StringBuilder();
        for(Object entry:entries.subList(0,Math.min(10,entries.size()))){
            for(String line:String.valueOf(field(entry,"details")).split("\n")){
                if(line.matches("^(阶段|上下文|异常|原因|接口|HTTP|业务码)：.*"))details.append(line).append('\n');
            }
        }
        if(details.length()>0){Bundle diagnostic=new Bundle();diagnostic.putString("stream",details.toString());sendStatus(0,diagnostic);}
    }

    private void dashResume(Object engine,Bundle result)throws Exception{
        check(engine.getClass().getSimpleName().equals("BlDashPlayer"),"DASH recovery switched to the progressive engine");
        JSONObject stream=selectedStream();
        check(stream.optInt("bl_quality")==64&&stream.optInt("bl_audio_id")==30216&&
                stream.optString("bl_audio").contains("backup=1"),"Failed AAC did not switch to the valid backup-only track at the same quality");
        Object diagnostics=field(field(plugin,"api"),"diagnostics");
        boolean resumed=false;
        for(Object entry:(java.util.List<?>)invoke(diagnostics,"snapshot")){
            String detail=String.valueOf(field(entry,"details"));
            if(detail.contains("DASH CDN 回退")&&java.util.regex.Pattern.compile("视频 字节位置=[1-9]").matcher(detail).find())resumed=true;
        }
        check(resumed&&!Boolean.TRUE.equals(field(plugin,"statusError")),"DASH byte-range resume or quiet recovery was not observed");
        result.putString("dashResume","Web headers and initial Range, resumed after truncation, invalid range skipped, alternate AAC at original quality");
    }

    private void premiumDash(Object player,Bundle result)throws Exception{
        Class<?> streams=plugin.getClass().getClassLoader().loadClass("xiao.bu.channel.bl.BlStreams");
        Method select=streams.getDeclaredMethod("select",JSONObject.class,int.class,String.class,int.class);
        select.setAccessible(true);
        JSONObject data=(JSONObject)field(plugin,"playData");
        check(selectedStream().optInt("bl_quality")==64,"Default quality used the response qn instead of the selected stream");
        Object engine=selectPlaybackOption(player,"画质","8K");
        check(engine.getClass().getSimpleName().equals("BlDashPlayer")&&selectedStream().optInt("bl_quality")==127&&
                selectedStream().optString("bl_codec").startsWith("hvc1"),"8K HEVC stream did not win over the default AVC preference");
        long begin=((Number)invoke(player,"position")).longValue();
        SystemClock.sleep(11000);
        long elapsed=((Number)invoke(player,"position")).longValue()-begin;
        check(elapsed>=10000&&field(player,"engine")==engine&&"playing".equals(field(player,"state")),"Tagged 8K stream stalled or fell back");
        Bundle sample=new Bundle();sample.putString("stream","BL_PREMIUM HEVC fixture playback advanced "+elapsed+"ms\n");sendStatus(0,sample);

        SharedPreferences prefs=getTargetContext().getSharedPreferences("ntv-bl-instrumentation",0);
        String[] names={"杜比全景声","Hi-Res","杜比音频"};
        int[] ids={30250,30251,30255};
        Object api=field(plugin,"api"),diagnostics=field(api,"diagnostics");
        for(int i=0;i<ids.length;i++){
            JSONObject track=((JSONArray)select.invoke(null,data,127,"AVC",ids[i])).getJSONObject(0);
            check(track.optInt("bl_audio_id")==ids[i]&&track.optString("bl_audio_label").equals(names[i])&&
                    track.getJSONArray("bl_audio_backups").length()==1,"Missing DASH audio or backup for "+names[i]);
            Object previousDiagnostic=invoke(diagnostics,"latest");
            engine=selectPlaybackOption(player,"音轨",names[i]);
            int actual=selectedStream().optInt("bl_audio_id");
            check(engine.getClass().getSimpleName().equals("BlDashPlayer")&&prefs.getInt("audio_track",0)==ids[i]&&
                    selectedStream().optInt("bl_quality")==127,"Audio selection did not preserve the 8K video");
            if(actual!=ids[i]){
                boolean recovered=false;
                for(Object entry:(java.util.List<?>)invoke(diagnostics,"snapshot")){
                    if(entry==previousDiagnostic)break;
                    if(String.valueOf(field(entry,"details")).contains("DASH 轨道回退")){recovered=true;break;}
                }
                check(actual==30216&&recovered,"Missing decoder fallback for "+names[i]);
            }
            result.putString("audio"+ids[i],actual==ids[i]?"selected and prepared":"AAC decoder fallback");
        }
        selectPlaybackOption(player,"音轨","AAC · 64 kbps");
        try{
            armFault("reject_8k");
            engine=selectPlaybackOption(player,"画质","8K");
            check(engine.getClass().getSimpleName().equals("BlDashPlayer")&&selectedStream().optInt("bl_quality")==64,"Failed 8K stream did not fall back to lower DASH quality");

            armFault("no_premium");
            selectPlaybackOption(player,"视频编码","AVC");
            check(selectedStream().optInt("bl_quality")==64&&prefs.getInt("quality",0)==127,"Unavailable 8K changed the saved preference or selected the wrong stream");
            controls();key(KeyEvent.KEYCODE_MENU);option("画质");
            onMain(()->{
                try{
                    ListView choices=findList((View)field(plugin,"modal"));
                    for(int i=0;i<choices.getCount();i++)check(!"8K".equals(field(choices.getAdapter().getItem(i),"title")),"8K was advertised without an 8K URL");
                }catch(Exception e){throw new RuntimeException(e);}
            });
            key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);

            armFault("");
            JSONObject pgc=(JSONObject)invoke(api,"episodeDash",1L,2000L);
            check(((JSONArray)select.invoke(null,pgc,127,"HEVC",30251)).getJSONObject(0).optInt("bl_audio_id")==30251,"PGC v2 video_info lost the Hi-Res track");
            armFault("pgc_v1");
            pgc=(JSONObject)invoke(api,"episodeDash",1L,2000L);
            check(((JSONArray)select.invoke(null,pgc,127,"HEVC",30255)).getJSONObject(0).optInt("bl_audio_id")==30255,"PGC v1 fallback lost the Dolby track");
        }finally{armFault("");}
        result.putString("premiumDash","8K selection, HEVC playback, three premium audio tracks, missing 8K, CDN fallback and both PGC endpoints");
    }
    private void verifyPlayerLayout(Object player)throws Exception{
        controls();
        TextView metadata=(TextView)field(player,"subheading");
        TextView time=(TextView)field(player,"time");
        View clock=(View)field(player,"clock");
        check(metadata.getText().toString().contains("第一集")&&metadata.getText().toString().contains("Native View 测试"),
                "Player title metadata is incomplete");
        check(time.getText().toString().contains(" · ")&&clock.isShown(),"Playback time or clock is missing");
        screenshot("bl-player-overlay.png");
        key(KeyEvent.KEYCODE_BACK);
        check(!clock.isShown()&&((View)field(player,"controls")).getVisibility()==View.GONE,"Clock did not hide with the controls");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(clock.isShown()&&root.findFocus()==field(player,"toggle"),"DOWN did not restore controls and focus Play/Pause");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check(root.findFocus()==findButton(root,"上一集"),"RIGHT did not traverse the player toolbar");
        click("相关视频");
        waitText("相关视频");
        ViewGroup overlay=(ViewGroup)field(plugin,"modal");
        ViewGroup shelf=(ViewGroup)overlay.getChildAt(0);
        FrameLayout.LayoutParams bounds=(FrameLayout.LayoutParams)shelf.getLayoutParams();
        check((bounds.gravity&Gravity.VERTICAL_GRAVITY_MASK)==Gravity.BOTTOM&&shelf.getChildAt(1) instanceof HorizontalScrollView,
                "Related videos did not open as a bottom card strip");
        screenshot("bl-player-related.png");
        key(KeyEvent.KEYCODE_BACK);
        check(field(plugin,"modal")==null&&field(plugin,"player")==player,"Related videos did not return to playback");
    }
    private void verifyPlayerSettingOrder()throws Exception{
        ListView settings=findList((View)field(plugin,"modal"));
        String[] expected={"画质","播放速度","字幕","视频编码","音轨","画面缩放","弹幕设置"};
        for(int i=0;i<expected.length;i++){
            String title=(String)field(settings.getItemAtPosition(i),"title");
            check(title.split("\n",2)[0].equals(expected[i]),"Incorrect player settings order: "+title);
        }
    }
    private long animationFrames(final Object dm){final long[] value={0};onMain(new Runnable(){public void run(){try{value[0]=((Number)field(dm,"animationFrames")).longValue();}catch(Exception e){throw new RuntimeException(e);}}});return value[0];}
    private void tapPlayer(View player){int[] origin=new int[2];player.getLocationOnScreen(origin);float x=origin[0]+player.getWidth()*.55f,y=origin[1]+player.getHeight()*.3f;long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,0,x,y,0),up=MotionEvent.obtain(now,now+40,1,x,y,0);sendPointerSync(down);SystemClock.sleep(40);sendPointerSync(up);down.recycle();up.recycle();SystemClock.sleep(120);}
    private void interactionScenarios(final Object player,Bundle result)throws Exception{
        final Object dm=field(player,"danmaku");long end=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<end&&((java.util.List<?>)field(dm,"visible")).isEmpty())SystemClock.sleep(50);
        final java.util.List<Object> before=new java.util.ArrayList<Object>();onMain(new Runnable(){public void run(){try{before.addAll((java.util.List<?>)field(dm,"visible"));}catch(Exception e){throw new RuntimeException(e);}}});check(!before.isEmpty(),"No on-screen danmaku for interaction test");
        tapPlayer((View)player);final int[] retained={0};onMain(new Runnable(){public void run(){try{for(Object item:(java.util.List<?>)field(dm,"visible"))if(before.contains(item))retained[0]++;}catch(Exception e){throw new RuntimeException(e);}}});
        final tv.danmaku.ijk.media.player.IMediaPlayer engine=(tv.danmaku.ijk.media.player.IMediaPlayer)field(player,"engine");
        onMain(new Runnable(){public void run(){engine.pause();}});SystemClock.sleep(300);
        long baseline=animationFrames(dm),renderedBefore=((Number)field(dm,"previous")).longValue();onMain(new Runnable(){public void run(){engine.start();}});SystemClock.sleep(1400);long resumed=animationFrames(dm)-baseline,renderedAdvance=((Number)field(dm,"previous")).longValue()-renderedBefore;
        Bundle sample=new Bundle();sample.putString("stream","BL_INTERACTION retainedAfterTap="+retained[0]+" resumedAnimationTicks="+resumed+"\n");sendStatus(0,sample);
        check(resumed>=15,"Danmaku animation did not recover after transient engine not-playing state: "+resumed);
        check(renderedAdvance>=700,"Danmaku drawing timeline stalled after playback recovery: "+renderedAdvance);
        check(retained[0]>0,"Tapping the player discarded all existing on-screen danmaku");
        final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());final java.util.ArrayList<Long> gaps=new java.util.ArrayList<Long>();final boolean[] sampling={true};
        final Runnable heartbeat=new Runnable(){long previous=SystemClock.uptimeMillis();public void run(){if(!sampling[0])return;long now=SystemClock.uptimeMillis();gaps.add(now-previous);previous=now;handler.postDelayed(this,16);}};
        onMain(new Runnable(){public void run(){handler.post(heartbeat);}});long at=animationFrames(dm);
        try{
            for(int i=0;i<12;i++)tapPlayer((View)player);
            controls();key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_MENU);option("画质");key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);
            controls();click("暂停");SystemClock.sleep(350);check(!engine.isPlaying(),"Pause touch target failed");click("播放");SystemClock.sleep(400);check(engine.isPlaying(),"Play touch target failed");
            key(KeyEvent.KEYCODE_MENU);option("弹幕设置");key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);SystemClock.sleep(1200);
            check(animationFrames(dm)-at>=60,"Danmaku animation stalled while touching player controls");
        }finally{onMain(new Runnable(){public void run(){sampling[0]=false;handler.removeCallbacks(heartbeat);}});}
        screenshot("bl-interactions.png");
        java.util.Collections.sort(gaps);long max=gaps.get(gaps.size()-1),p95=gaps.get(gaps.size()*95/100);
        result.putLong("uiHeartbeatP95Ms",p95);result.putLong("uiHeartbeatMaxMs",max);result.putLong("interactionAnimationTicks",animationFrames(dm)-at);result.putLong("resumedAnimationTicks",resumed);result.putInt("retainedAfterTap",retained[0]);check(max<1500,"UI thread blocked for "+max+"ms");
    }
    private void closePlayback()throws Exception{key(KeyEvent.KEYCODE_BACK);check(route().equals("player"),"First BACK exited playback");key(KeyEvent.KEYCODE_BACK);}
    private void screenshot(final String name)throws Exception{onMain(new Runnable(){public void run(){Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.RGB_565);try{root.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(activity.getExternalFilesDir(null),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}}catch(Exception e){throw new RuntimeException(e);}finally{bitmap.recycle();}}});}
    private void trustFixture()throws Exception{
        String path=args.getString("cert");if(path==null)return;
        java.security.cert.Certificate cert;try(InputStream in=new FileInputStream(path)){cert=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);}
        java.security.KeyStore keys=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());keys.load(null);keys.setCertificateEntry("fixture",cert);
        javax.net.ssl.TrustManagerFactory managers=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());managers.init(keys);
        final javax.net.ssl.X509TrustManager fixtureTrust=(javax.net.ssl.X509TrustManager)managers.getTrustManagers()[0];
        javax.net.ssl.TrustManagerFactory defaults=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());defaults.init((java.security.KeyStore)null);
        final javax.net.ssl.X509TrustManager systemTrust=(javax.net.ssl.X509TrustManager)defaults.getTrustManagers()[0];
        javax.net.ssl.X509TrustManager trust=new javax.net.ssl.X509TrustManager(){
            public java.security.cert.X509Certificate[] getAcceptedIssuers(){return systemTrust.getAcceptedIssuers();}
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{systemTrust.checkClientTrusted(chain,auth);}
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{try{systemTrust.checkServerTrusted(chain,auth);}catch(java.security.cert.CertificateException e){fixtureTrust.checkServerTrusted(chain,auth);}}
        };
        javax.net.ssl.SSLContext tls=javax.net.ssl.SSLContext.getInstance("TLS");tls.init(null,new javax.net.ssl.TrustManager[]{trust},null);
        final javax.net.ssl.HostnameVerifier original=NetworkClient.sharedClient().hostnameVerifier();
        okhttp3.OkHttpClient client=NetworkClient.sharedClient().newBuilder().sslSocketFactory(tls.getSocketFactory(),trust).hostnameVerifier(new javax.net.ssl.HostnameVerifier(){public boolean verify(String host,javax.net.ssl.SSLSession session){return host.equals(args.getString("fixtureHost"))||original.verify(host,session);}}).build();
        for(String name:new String[]{"client","factory"}){Field f=NetworkClient.class.getDeclaredField(name);f.setAccessible(true);f.set(null,name.equals("client")?client:new okhttp3.OkUrlFactory(client));}GithubProxy.setEnabled(false);
    }
    private void armFault(String mode)throws Exception{try(okhttp3.Response r=NetworkClient.sharedClient().newCall(new okhttp3.Request.Builder().url(UriOrigin(args.getString("source"))+"/__fixture/fault?mode="+mode).build()).execute()){check(r.isSuccessful(),"Could not arm test fixture fault");}}
    private void waitText(String value)throws Exception{long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(containsText(root,value))return;SystemClock.sleep(100);}throw new AssertionError("Missing error text: "+value);}
    private void errorScenarios()throws Exception{
        for(String mode:new String[]{"http","json","business"}){
            armFault(mode);click("热门");waitRoute("popular");waitText("查看错误详情");click("错误详情");
            waitText(mode.equals("http")?"HTTP：503":mode.equals("json")?"响应格式":"业务码：-352");
            check(containsText(root,"GET http://127.0.0.1:39626/x/web-interface/popular"),"Missing failing API method/path");
            check(!containsText(root,"fixture-secret-token"),"Credential leaked into error details");
            if(mode.equals("http")){waitText("bl-fixture-503");screenshot("bl-http-error.png");click("复制详情");final String[] clip={""};onMain(new Runnable(){public void run(){android.content.ClipboardManager c=(android.content.ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);if(c.hasPrimaryClip())clip[0]=c.getPrimaryClip().getItemAt(0).coerceToText(getTargetContext()).toString();}});check(clip[0].contains("HTTP：503")&&!clip[0].contains("fixture-secret-token"),"Copied diagnostics missing or unredacted");}
            key(KeyEvent.KEYCODE_BACK);check(field(plugin,"modal")==null,"Error details BACK did not close panel");click("刷新");waitItems();
        }
        click("推荐");waitItems();key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER);waitRoute("detail");armFault("media");click("播放");waitRoute("player");long end=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<end&&!"error".equals(field(field(plugin,"player"),"state")))SystemClock.sleep(100);
        check("error".equals(field(field(plugin,"player"),"state")),"Rejected CDN did not produce a terminal player error");key(KeyEvent.KEYCODE_MENU);option("错误详情");waitText("HTTP：403");waitText("what=");
        check(!containsText(root,"fixture-secret-token"),"CDN token leaked into player diagnostics");screenshot("bl-media-error.png");key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);closePlayback();
    }
    private void onMain(final Runnable task){final Throwable[] failure={null};runOnMainSync(new Runnable(){public void run(){try{task.run();}catch(Throwable error){failure[0]=error;}}});if(failure[0] instanceof Error)throw (Error)failure[0];if(failure[0]!=null)throw new RuntimeException(failure[0]);}
    private interface AuditCase{String run()throws Exception;}
    private static final class AuditSkip extends Exception{AuditSkip(String reason){super(reason);}}
    private final JSONArray accountChecks=new JSONArray();private int accountFailures,accountRestricted,accountSkipped;
    private Object auditApi;private JSONObject auditVideo;private JSONArray auditPopular,auditFolders,auditFollowing,auditSeasons,auditLives;private Object auditPlayer;
    private static boolean accepts(Class<?> type,Object value){return value==null||type.isInstance(value)||type==long.class&&value instanceof Long||type==int.class&&value instanceof Integer||type==boolean.class&&value instanceof Boolean;}
    private static Object invoke(Object object,String name,Object... args)throws Exception{for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods()){if(!m.getName().equals(name)||m.getParameterTypes().length!=args.length)continue;boolean matches=true;for(int i=0;i<args.length;i++)if(!accepts(m.getParameterTypes()[i],args[i]))matches=false;if(!matches)continue;m.setAccessible(true);try{return m.invoke(object,args);}catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof Exception)throw (Exception)t;if(t instanceof Error)throw (Error)t;throw e;}}throw new NoSuchMethodException(name);}
    private String safeAudit(String message){try{Class<?> c=plugin.getClass().getClassLoader().loadClass("xiao.bu.channel.bl.BlDiagnostics");Method m=c.getDeclaredMethod("clean",String.class);m.setAccessible(true);String clean=(String)m.invoke(null,message);return clean.length()>700?clean.substring(0,700):clean;}catch(Exception ignored){return "Diagnostic unavailable";}}
    private void audit(String name,AuditCase test)throws Exception{String state="PASS",detail="";long start=SystemClock.elapsedRealtime();try{detail=test.run();}catch(AuditSkip skip){state="SKIP";detail=skip.getMessage();accountSkipped++;}catch(Throwable error){detail=safeAudit(error.getClass().getSimpleName()+": "+error.getMessage());if(detail.contains("-352")||detail.contains("-412")||detail.contains("业务码：-403")||detail.contains("-10403")||detail.contains("要求验证")||detail.contains("地区限制")||detail.contains("HTTP 403")){state="RESTRICTED";accountRestricted++;}else{state="FAIL";accountFailures++;}}
        accountChecks.put(new JSONObject().put("name",name).put("status",state).put("detail",detail).put("elapsedMs",SystemClock.elapsedRealtime()-start));Bundle progress=new Bundle();progress.putString("stream","BL_ACCOUNT "+state+" "+name+" · "+detail+"\n");sendStatus(0,progress);
    }
    private Object read(String method,Object... args)throws Exception{return invoke(auditApi,method,args);}
    private static JSONArray list(JSONObject value,String name){JSONArray a=value==null?null:value.optJSONArray(name);return a==null?new JSONArray():a;}
    private void navigateAudit(final String kind,final String title,final String arg)throws Exception{onMain(new Runnable(){public void run(){try{Class<?> c=plugin.getClass().getClassLoader().loadClass("xiao.bu.channel.bl.BlPlugin$Route");Constructor<?> ctor=c.getDeclaredConstructor(String.class,String.class,String.class);ctor.setAccessible(true);invoke(plugin,"navigate",ctor.newInstance(kind,title,arg),false);}catch(Exception e){throw new RuntimeException(e);}}});}
    private void settled()throws Exception{long end=SystemClock.elapsedRealtime()+35000;while(SystemClock.elapsedRealtime()<end){if(Boolean.TRUE.equals(field(plugin,"statusError")))throw new IOException(((TextView)field(plugin,"status")).getText().toString());if(!Boolean.TRUE.equals(field(plugin,"loading")))return;SystemClock.sleep(100);}throw new IOException("Page did not finish loading");}
    private String pageAudit(String tab)throws Exception{if(field(plugin,"player")!=null)navigateAudit("home","推荐","");if(tab.equals("推荐")||tab.equals("热门")||tab.equals("番剧"))click("首页");click(tab);settled();return "cards="+((java.util.List<?>)field(plugin,"items")).size();}
    private void selectRow(final String title)throws Exception{final ListView[] target={null};final int[] at={-1};onMain(new Runnable(){public void run(){try{View modal=(View)field(plugin,"modal");target[0]=findList(modal==null?root:modal);if(target[0]==null)return;for(int i=0;i<target[0].getCount();i++){String text=String.valueOf(field(target[0].getAdapter().getItem(i),"title")).split("\n",2)[0].replace("● ","");if(text.equals(title))at[0]=i;}if(at[0]>=0){target[0].requestFocusFromTouch();target[0].setSelection(at[0]);}}catch(Exception e){throw new RuntimeException(e);}}});check(at[0]>=0,"Missing option: "+title);SystemClock.sleep(120);key(KeyEvent.KEYCODE_DPAD_CENTER);}
    private void dismissAuditPanels()throws Exception{for(int i=0;i<5&&field(plugin,"modal")!=null;i++)key(KeyEvent.KEYCODE_BACK);}
    private String minePage(String title)throws Exception{
        click("我的");
        if(title.equals("我的关注")){click("用户");selectRow(title);}
        else click(title);
        settled();
        return "cards="+((java.util.List<?>)field(plugin,"items")).size();
    }
    private void firstCard()throws Exception{onMain(new Runnable(){public void run(){try{GridView grid=(GridView)field(plugin,"grid");check(grid!=null&&grid.getCount()>0,"No card to open");grid.requestFocusFromTouch();grid.setSelection(0);}catch(Exception e){throw new RuntimeException(e);}}});key(KeyEvent.KEYCODE_DPAD_CENTER);}
    private void detailAudit()throws Exception{check(auditVideo!=null,"No public video sample");navigateAudit("detail","视频详情",auditVideo.getString("bvid"));long end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){if(Boolean.TRUE.equals(field(plugin,"statusError")))settled();if(field(field(plugin,"route"),"data")!=null&&findButton(root,"播放")!=null)return;SystemClock.sleep(100);}throw new IOException("Detail UI did not load");}
    private Object playingAudit(Object previous)throws Exception{long end=SystemClock.elapsedRealtime()+55000;while(SystemClock.elapsedRealtime()<end){auditPlayer=field(plugin,"player");if(auditPlayer!=null){String state=String.valueOf(field(auditPlayer,"state"));if(state.equals("error")&&(previous==null||field(auditPlayer,"engine")!=previous))throw new IOException(((TextView)field(auditPlayer,"status")).getText().toString());Object engine=field(auditPlayer,"engine");if(state.equals("playing")&&engine!=null&&engine!=previous)return engine;}SystemClock.sleep(100);}throw new IOException("Player did not become ready");}
    private void clockAudit()throws Exception{long before=((Number)invoke(auditPlayer,"position")).longValue(),end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){SystemClock.sleep(200);if(((Number)invoke(auditPlayer,"position")).longValue()>before+500)return;}throw new IOException("Playback clock did not advance within 15 seconds");}
    private void reloadAudit()throws Exception{final Object before=field(plugin,"playData");final Object previous=field(auditPlayer,"engine");onMain(new Runnable(){public void run(){try{invoke(plugin,"loadPlayback",0L);}catch(Exception e){throw new RuntimeException(e);}}});long end=SystemClock.elapsedRealtime()+25000;while(SystemClock.elapsedRealtime()<end&&field(plugin,"playData")==before&&field(auditPlayer,"engine")==previous)SystemClock.sleep(100);playingAudit(previous);}
    private void requirePlayer()throws Exception{if(auditPlayer==null||field(plugin,"player")!=auditPlayer)throw new AuditSkip("No player in this scenario");if(!"playing".equals(field(auditPlayer,"state"))){reloadAudit();}}
    private void settingsAudit(String title,String value)throws Exception{requirePlayer();dismissAuditPanels();controls();key(KeyEvent.KEYCODE_MENU);selectRow(title);if(value!=null)selectRow(value);dismissAuditPanels();}
    private String danmakuSetting(final String title,final String value)throws Exception{requirePlayer();dismissAuditPanels();controls();key(KeyEvent.KEYCODE_MENU);selectRow("弹幕设置");selectRow(title);if(value!=null)selectRow(value);dismissAuditPanels();return "setting applied";}
    private void accountScenarios(final Bundle result)throws Exception{
        auditApi=field(plugin,"api");
        if("true".equals(args.getString("accountProbe"))){
            for(final String type:new String[]{"video","media_bangumi","bili_user"})audit("搜索结构 · "+type,new AuditCase(){public String run()throws Exception{JSONObject response=(JSONObject)read("get","/x/web-interface/wbi/search/type",new String[]{"keyword","音乐","search_type",type,"page","1","page_size","24"});JSONObject data=response.optJSONObject("data");return data==null?"no data":"keys="+data.names()+"; result="+list(data,"result").length()+"; total="+data.optInt("numResults")+"; resultType="+(data.opt("result")==null?"null":data.opt("result").getClass().getSimpleName());}});
            result.putString("stream","PROBE COMPLETED: "+accountChecks.toString());return;
        }
        audit("登录会话与资料",new AuditCase(){public String run()throws Exception{JSONObject p=(JSONObject)read("profile");check(p.optBoolean("isLogin"),"Login is not valid");return "loggedIn=true; VIP="+(p.optJSONObject("vipStatus")!=null||p.optInt("vipStatus")>0);}});
        audit("推荐 UI",new AuditCase(){public String run()throws Exception{return pageAudit("推荐");}});
        audit("热门 UI 与公开视频样本",new AuditCase(){public String run()throws Exception{String ui=pageAudit("热门");auditPopular=(JSONArray)read("popular",1);JSONObject candidate=null;for(int i=0;i<auditPopular.length();i++){JSONObject v=auditPopular.getJSONObject(i);if(!v.optString("bvid").isEmpty()&&v.optInt("duration")>=120&&v.optInt("duration")<=1200&&candidate==null)candidate=v;}if(candidate!=null)auditVideo=(JSONObject)read("detail",candidate.getString("bvid"));check(auditVideo!=null,"No suitable public video");return ui+"; sampleParts="+list(auditVideo,"pages").length();}});
        for(final String entry:new String[]{"历史","收藏","追番","追剧","稍后观看","我的关注"})audit("账号 UI · "+entry,new AuditCase(){public String run()throws Exception{return minePage(entry);}});
        audit("账号历史下一页",new AuditCase(){public String run()throws Exception{return "items="+((JSONArray)read("history",2)).length();}});
        audit("收藏夹内容 UI",new AuditCase(){public String run()throws Exception{auditFolders=(JSONArray)read("favoriteFolders");if(auditFolders.length()==0)throw new AuditSkip("No favorite folder in this account");minePage("收藏");firstCard();waitRoute("favoriteItems");settled();return "folders="+auditFolders.length()+"; cards="+((java.util.List<?>)field(plugin,"items")).size();}});
        audit("关注列表与 UP 投稿 UI",new AuditCase(){public String run()throws Exception{auditFollowing=(JSONArray)read("followed",1);if(auditFollowing.length()==0)throw new AuditSkip("No followed creator in this account");minePage("我的关注");firstCard();waitRoute("uploads");settled();return "followed="+auditFollowing.length()+"; cards="+((java.util.List<?>)field(plugin,"items")).size();}});
        audit("动态 UI",new AuditCase(){public String run()throws Exception{return pageAudit("动态");}});
        final String[] regions={"动画","音乐","游戏","娱乐","知识","生活","科技","影视"};
        for(final String region:regions)audit("分区 UI · "+region,new AuditCase(){public String run()throws Exception{click("分区");click(region);waitRoute("regionItems");settled();return "cards="+((java.util.List<?>)field(plugin,"items")).size();}});
        audit("番剧列表和剧集 UI",new AuditCase(){public String run()throws Exception{pageAudit("番剧");auditSeasons=(JSONArray)read("seasons",1,1);if(auditSeasons.length()==0)throw new AuditSkip("No season returned");firstCard();waitRoute("seasonDetail");long end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end&&findList(root)==null){if(Boolean.TRUE.equals(field(plugin,"statusError")))settled();SystemClock.sleep(100);}check(findList(root)!=null,"Season episode list is missing");return "seasons="+auditSeasons.length()+"; episode UI loaded";}});
        for(final String type:new String[]{"视频","番剧","直播","UP主"})audit("搜索 UI · "+type,new AuditCase(){public String run()throws Exception{click("搜索");onMain(new Runnable(){public void run(){findEdit(root).setText(type.equals("视频")?"音乐":"哔哩哔哩");}});click("搜索");waitRoute("results");if(!type.equals("视频"))click(type);settled();int count=((java.util.List<?>)field(plugin,"items")).size();check(count>0,"Search returned no cards");return "cards="+count;}});
        audit("BV 链接识别与详情",new AuditCase(){public String run()throws Exception{check(auditVideo!=null,"No public sample");click("搜索");onMain(new Runnable(){public void run(){findEdit(root).setText("https://www.bilibili.com/video/"+auditVideo.optString("bvid"));}});click("搜索");waitRoute("detail");detailAudit();return "detail loaded";}});
        audit("相关推荐",new AuditCase(){public String run()throws Exception{detailAudit();return "items="+((JSONArray)read("related",auditVideo.getString("bvid"))).length();}});
        audit("评论 UI 与翻页",new AuditCase(){public String run()throws Exception{detailAudit();click("评论");long end=SystemClock.elapsedRealtime()+20000;while(field(plugin,"modal")==null&&SystemClock.elapsedRealtime()<end){if(Boolean.TRUE.equals(field(plugin,"statusError")))settled();SystemClock.sleep(100);}check(field(plugin,"modal")!=null,"Comment panel missing");dismissAuditPanels();return "page2Items="+((JSONArray)read("comments",auditVideo.getLong("aid"),2)).length();}});
        audit("收藏选择流程（取消）",new AuditCase(){public String run()throws Exception{detailAudit();click("收藏");long end=SystemClock.elapsedRealtime()+15000;while(field(plugin,"modal")==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);check(field(plugin,"modal")!=null,"Favorite chooser missing");dismissAuditPanels();return "folder chooser opened; no folder selected";}});
        audit("投币确认流程（取消）",new AuditCase(){public String run()throws Exception{detailAudit();click("投币");check(containsText(root,"确认投 1 枚硬币")&&containsText(root,"确认投 2 枚硬币"),"Coin confirmation choices missing");dismissAuditPanels();return "no coins spent";}});
        audit("UP 投稿独立入口",new AuditCase(){public String run()throws Exception{detailAudit();click("UP 主");waitRoute("uploads");settled();return "cards="+((java.util.List<?>)field(plugin,"items")).size();}});
        audit("DASH 优先点播与兼容回退",new AuditCase(){public String run()throws Exception{detailAudit();click("播放");waitRoute("player");Object engine=playingAudit(null);clockAudit();screenshot("bl-account-player.png");return "engine="+engine.getClass().getSimpleName()+"; clock advanced";}});
        audit("独立恢复交互测试",new AuditCase(){public String run()throws Exception{if(auditPlayer!=null&&"error".equals(field(auditPlayer,"state"))){getTargetContext().getSharedPreferences("ntv-bl",0).edit().putBoolean("prefer_dash",false).putBoolean("systemPlayer",false).apply();reloadAudit();return "DASH failure retained above; remaining interactions use IJK";}return "ready; remaining interactions keep "+field(auditPlayer,"engine").getClass().getSimpleName();}});
        audit("触摸暂停与遥控器恢复",new AuditCase(){public String run()throws Exception{requirePlayer();controls();click("暂停");check("paused".equals(field(auditPlayer,"state")),"Touch pause failed");key(KeyEvent.KEYCODE_MEDIA_PLAY);check("playing".equals(field(auditPlayer,"state")),"Remote resume failed");return "touch and media key passed";}});
        audit("缓冲时的暂停意图与媒体键",new AuditCase(){public String run()throws Exception{requirePlayer();final tv.danmaku.ijk.media.player.IMediaPlayer engine=(tv.danmaku.ijk.media.player.IMediaPlayer)field(auditPlayer,"engine");onMain(new Runnable(){public void run(){engine.pause();}});SystemClock.sleep(700);controls();check(findButton(root,"暂停")!=null,"Transient not-playing changed Pause to Play");key(KeyEvent.KEYCODE_MEDIA_PAUSE);check(!Boolean.TRUE.equals(field(auditPlayer,"wantPlaying")),"Pause key ignored during buffering");key(KeyEvent.KEYCODE_MEDIA_PLAY);check(Boolean.TRUE.equals(field(auditPlayer,"wantPlaying")),"Play key did not restore intention");SystemClock.sleep(800);return "pause intent preserved during transient engine pause";}});
        audit("播放器点击与弹幕恢复",new AuditCase(){public String run()throws Exception{requirePlayer();Object dm=field(auditPlayer,"danmaku");if(((java.util.List<?>)field(dm,"lines")).isEmpty())throw new AuditSkip("No danmaku returned for this video");Bundle metrics=new Bundle();interactionScenarios(auditPlayer,metrics);return "resumedTicks="+metrics.getLong("resumedAnimationTicks")+"; UI heartbeat max="+metrics.getLong("uiHeartbeatMaxMs")+"ms";}});
        audit("快进快退",new AuditCase(){public String run()throws Exception{requirePlayer();long at=((Number)invoke(auditPlayer,"position")).longValue();key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);SystemClock.sleep(1000);check(((Number)invoke(auditPlayer,"position")).longValue()>at+5000,"Fast forward failed");key(KeyEvent.KEYCODE_MEDIA_REWIND);return "media seek keys passed";}});
        audit("实际可用画质切换",new AuditCase(){public String run()throws Exception{requirePlayer();JSONObject data=(JSONObject)field(plugin,"playData");JSONArray qualities=list(data,"accept_quality");int chosen=0;for(int i=0;i<qualities.length();i++)if(qualities.optInt(i)==32)chosen=32;if(chosen==0)throw new AuditSkip("No 480P alternative advertised");Object old=field(auditPlayer,"engine");settingsAudit("画质","480P");playingAudit(old);return "480P decoder restarted";}});
        audit("倍速",new AuditCase(){public String run()throws Exception{settingsAudit("播放速度","1.5x");check(getTargetContext().getSharedPreferences("ntv-bl",0).getFloat("play_speed",0)==1.5f,"Speed not saved");settingsAudit("播放速度","1.0x");return "1.5x and 1x";}});
        audit("AVC 与自动编码",new AuditCase(){public String run()throws Exception{requirePlayer();Object old=field(auditPlayer,"engine");settingsAudit("视频编码","自动");playingAudit(old);old=field(auditPlayer,"engine");settingsAudit("视频编码","AVC");playingAudit(old);return "both selected and decoded; HEVC depends on device codec support";}});
        for(final String ratio:new String[]{"宽度适应","高度适应","拉伸","放大","自适应"})audit("缩放 · "+ratio,new AuditCase(){public String run()throws Exception{settingsAudit("画面缩放",ratio);return "applied";}});
        audit("实际音轨与备用线路",new AuditCase(){public String run()throws Exception{requirePlayer();JSONArray audios=list(((JSONObject)field(plugin,"playData")).optJSONObject("dash"),"audio");if(audios.length()>0){Object oldAudio=field(auditPlayer,"engine");JSONObject track=audios.getJSONObject(audios.length()-1);settingsAudit("音轨","AAC · "+Math.round(track.optDouble("bandwidth")/1000)+" kbps");playingAudit(oldAudio);}java.util.List<?> lines=(java.util.List<?>)invoke(auditPlayer,"lines");if(lines.size()>1){Object old=field(auditPlayer,"engine");settingsAudit("播放线路","备用线路 1");try{playingAudit(old);}catch(IOException error){Object failure=invoke(auditApi,"transportFailure",field(auditPlayer,"source"));boolean forbidden=failure!=null&&((Number)field(failure,"http")).intValue()==403;reloadAudit();if(forbidden)throw new IOException("备用 CDN HTTP 403；刷新主线路后已恢复");throw error;}}return "lineCount="+lines.size()+"; audioTracks="+audios.length()+(audios.length()>0?"; AAC switched":"; no selectable AAC track in progressive media");}});
        audit("字幕选择与读取",new AuditCase(){public String run()throws Exception{requirePlayer();JSONArray subs=(JSONArray)read("subtitles",auditVideo.getString("bvid"),((Number)field(plugin,"cid")).longValue());if(subs.length()==0)throw new AuditSkip("No subtitle on this public video");JSONObject s=subs.getJSONObject(0);settingsAudit("字幕",s.optString("lan_doc",s.optString("lan")));long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end&&((JSONArray)field(auditPlayer,"captions")).length()==0)SystemClock.sleep(100);check(((JSONArray)field(auditPlayer,"captions")).length()>0,"Subtitle body did not load");settingsAudit("字幕字号","32");return "tracks="+subs.length()+"; body loaded; font applied";}});
        final String[][] dmSettings={{"弹幕字体大小","120%"},{"弹幕透明度","50%"},{"弹幕速度","1.5x"},{"弹幕占屏比","75%"},{"弹幕密度","48"},{"弹幕智能屏蔽等级","2"},{"允许顶部悬停弹幕",null},{"允许底部悬停弹幕",null}};
        for(final String[] setting:dmSettings)audit("设置 · "+setting[0],new AuditCase(){public String run()throws Exception{return danmakuSetting(setting[0],setting[1]);}});
        audit("弹幕开关",new AuditCase(){public String run()throws Exception{requirePlayer();controls();click("弹幕开关");Object dm=field(auditPlayer,"danmaku");check(!((Boolean)invoke(dm,"enabled")),"Danmaku not disabled");click("弹幕开关");check((Boolean)invoke(dm,"enabled"),"Danmaku not enabled");return "off/on";}});
        audit("弹幕关键词",new AuditCase(){public String run()throws Exception{requirePlayer();controls();key(KeyEvent.KEYCODE_MENU);selectRow("弹幕设置");selectRow("关键词屏蔽");onMain(new Runnable(){public void run(){try{EditText input=findEdit((View)field(plugin,"modal"));check(input!=null,"Keyword editor missing");input.setText("codex-audit-only-word");}catch(Exception e){throw new RuntimeException(e);}}});click("保存");check(getTargetContext().getSharedPreferences("ntv-bl",0).getString("dm_block","").equals("codex-audit-only-word"),"Keyword not saved");return "local keyword saved; original restored after audit";}});
        audit("底部进度、调试信息与按钮偏好",new AuditCase(){public String run()throws Exception{settingsAudit("底部常驻进度条",null);settingsAudit("显示视频调试信息",null);dismissAuditPanels();controls();key(KeyEvent.KEYCODE_MENU);selectRow("控制栏与播放偏好");selectRow("显示快进快退按钮");dismissAuditPanels();check(findButton(root,"快进").getVisibility()==View.GONE,"Button visibility not applied");return "view preferences applied";}});
        for(final String order:new String[]{"倒序播放","单集循环","随机播放","播完停止","顺序播放"})audit("播放顺序 · "+order,new AuditCase(){public String run()throws Exception{requirePlayer();controls();click("播放顺序");selectRow(order);dismissAuditPanels();return "selected; completion behavior not simulated";}});
        audit("分 P 与连续切集",new AuditCase(){public String run()throws Exception{requirePlayer();JSONArray parts=list(auditVideo,"pages");if(parts.length()<2)throw new AuditSkip("Public sample has one part; prior fixture regression covers multi-part");Object old=field(auditPlayer,"engine");key(KeyEvent.KEYCODE_MEDIA_NEXT);playingAudit(old);old=field(auditPlayer,"engine");key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);playingAudit(old);return "next/previous decoded";}});
        audit("三连确认流程（取消）",new AuditCase(){public String run()throws Exception{requirePlayer();controls();click("更多");selectRow("一键三连");check(containsText(root,"确认点赞、投币并收藏"),"Triple confirmation missing");dismissAuditPanels();return "cancelled without submitting";}});
        audit("关注与取消关注确认（取消）",new AuditCase(){public String run()throws Exception{requirePlayer();controls();click("UP主信息");selectRow("关注 UP 主");check(containsText(root,"确认关注"),"Follow confirmation missing");dismissAuditPanels();controls();click("UP主信息");selectRow("取消关注");check(containsText(root,"确认取消关注"),"Unfollow confirmation missing");dismissAuditPanels();return "both cancelled without submitting";}});
        audit("前后台暂停恢复",new AuditCase(){public String run()throws Exception{requirePlayer();onMain(new Runnable(){public void run(){((xiao.bu.tv.jar.ChannelPlugin.NativePage)plugin).setActive(false);}});SystemClock.sleep(500);check(!((tv.danmaku.ijk.media.player.IMediaPlayer)field(auditPlayer,"engine")).isPlaying(),"Background did not pause");onMain(new Runnable(){public void run(){((xiao.bu.tv.jar.ChannelPlugin.NativePage)plugin).setActive(true);}});SystemClock.sleep(800);check(((tv.danmaku.ijk.media.player.IMediaPlayer)field(auditPlayer,"engine")).isPlaying(),"Foreground did not resume");return "paused/resumed";}});
        audit("错误详情与脱敏",new AuditCase(){public String run()throws Exception{requirePlayer();settingsAudit("错误详情",null);controls();key(KeyEvent.KEYCODE_MENU);selectRow("错误详情");String cookie=(String)read("cookie","SESSDATA");check(cookie.isEmpty()||!containsText(root,cookie),"Session credential exposed in diagnostics");screenshot("bl-account-settings.png");dismissAuditPanels();return "in-View panel; session not exposed";}});
        audit("播放返回与释放",new AuditCase(){public String run()throws Exception{requirePlayer();dismissAuditPanels();Object old=auditPlayer;closePlayback();check(field(old,"engine")==null,"Decoder not released");auditPlayer=null;return "double BACK released decoder";}});
        for(final boolean system:new boolean[]{false,true})audit(system?"系统播放器真实点播":"IJK 真实点播",new AuditCase(){public String run()throws Exception{getTargetContext().getSharedPreferences("ntv-bl",0).edit().putBoolean("prefer_dash",false).putBoolean("systemPlayer",system).putInt("quality",32).apply();detailAudit();click("播放");Object e=playingAudit(null);clockAudit();String name=e.getClass().getSimpleName();closePlayback();auditPlayer=null;getTargetContext().getSharedPreferences("ntv-bl",0).edit().putBoolean("prefer_dash",true).putBoolean("systemPlayer",false).apply();return "engine="+name+"; clock advanced";}});
        audit("番剧免费剧集播放",new AuditCase(){public String run()throws Exception{getTargetContext().getSharedPreferences("ntv-bl",0).edit().putBoolean("prefer_dash",true).putBoolean("systemPlayer",false).apply();pageAudit("番剧");if(((java.util.List<?>)field(plugin,"items")).isEmpty())throw new AuditSkip("No seasons available");firstCard();waitRoute("seasonDetail");long end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end&&findList(root)==null)SystemClock.sleep(100);final ListView episodes=findList(root);check(episodes!=null&&episodes.getCount()>0,"No episode");onMain(new Runnable(){public void run(){episodes.requestFocusFromTouch();episodes.setSelection(0);}});key(KeyEvent.KEYCODE_DPAD_CENTER);waitRoute("player");Object e=playingAudit(null);SystemClock.sleep(1000);closePlayback();auditPlayer=null;return "engine="+e.getClass().getSimpleName();}});
        audit("直播列表、解码和兼容弹幕",new AuditCase(){public String run()throws Exception{pageAudit("直播");auditLives=(JSONArray)read("live",1);if(auditLives.length()==0)throw new AuditSkip("No live room returned");firstCard();waitRoute("player");Object engine=playingAudit(null);SystemClock.sleep(4500);Object dm=field(plugin,"liveDanmaku");check(dm!=null,"Live danmaku client missing");String state=String.valueOf(field(dm,"status"));if(state.contains("不可用"))throw new IOException(state);return "engine="+engine.getClass().getSimpleName()+"; "+state+"; received="+field(dm,"received");}});
        audit("退出登录确认（取消）",new AuditCase(){public String run()throws Exception{click("我的");click("用户");selectRow("退出登录");check(containsText(root,"确认退出登录"),"Logout confirmation missing");dismissAuditPanels();return "cancelled; login retained";}});
        audit("登录保留与账号写保护",new AuditCase(){public String run()throws Exception{JSONObject p=(JSONObject)read("profile");check(p.optBoolean("isLogin"),"Login lost after tests");check(blockedWrites.get()==0,"Unexpected account-write attempts: "+blockedWrites.get());return "loggedIn=true; mutationAttempts=0";}});
        accountChecks.put(new JSONObject().put("name","点赞、收藏提交、稍后再看提交、投币、三连及关注写入").put("status","SKIP").put("detail","User explicitly limited this run to reads and confirmation/cancel flows"));accountSkipped++;
        JSONObject report=new JSONObject().put("sdk",Build.VERSION.SDK_INT).put("checks",accountChecks).put("failures",accountFailures).put("restricted",accountRestricted).put("skipped",accountSkipped).put("accountWriteAttempts",blockedWrites.get());
        try(FileOutputStream out=new FileOutputStream(new File(activity.getExternalFilesDir(null),"bl-account-audit.json"))){out.write(report.toString(2).getBytes("UTF-8"));}
        result.putString("stream",(accountFailures==0?"PASS:":"AUDIT COMPLETED:")+" read-only logged-in audit; checks="+accountChecks.length()+" failures="+accountFailures+" restricted="+accountRestricted+" skipped="+accountSkipped+" accountWriteAttempts="+blockedWrites.get());
    }
    public void onStart(){Bundle result=new Bundle();int code=0;long start=SystemClock.elapsedRealtime();
        try{
            Bundle processInfo=new Bundle();processInfo.putString("stream","BL_TEST_PID="+android.os.Process.myPid()+"\n");sendStatus(0,processInfo);
            boolean account="true".equals(args.getString("accountAudit"));
            check(account?!"true".equals(android.net.Uri.parse(args.getString("source")).getQueryParameter("testPreferences")):"true".equals(android.net.Uri.parse(args.getString("source")).getQueryParameter("testPreferences")),"Wrong preference namespace for selected test mode");
            preserveHostSelection();seedLocalJar();NetworkClient.initialize(getTargetContext());
            if(account)guardAccountReads();else{trustFixture();getTargetContext().getSharedPreferences("ntv-bl-instrumentation",0).edit().clear().putBoolean("systemPlayer","true".equals(args.getString("system"))).putBoolean("prefer_dash","true".equals(args.getString("dash"))).apply();}
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));long end=SystemClock.elapsedRealtime()+30000;while(activity==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);check(activity!=null,"Activity did not resume");SystemClock.sleep(2500);
            onMain(new Runnable(){public void run(){try{
                Channel channel=new Channel("1","BL 单 View","",args.getString("source"),null,null);ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("视频",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{channel})};
                for(String name:new String[]{"currentGroupIndex","currentChannelIndex","currentSourceIndex"}){Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.setInt(activity,0);}Method launch=MainActivity.class.getDeclaredMethod("startChannel",int.class);launch.setAccessible(true);launch.invoke(activity,0);
            }catch(Exception e){throw new RuntimeException(e);}}});
            String testVideo=args.getString("video");
            runtime=(JarChannelRuntime)field(activity,"jarChannelRuntime");end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){plugin=field(runtime,"plugin");if(plugin!=null)break;SystemClock.sleep(100);}check(plugin!=null,"JAR plugin did not load");root=(View)field(runtime,"view");if(!account&&testVideo==null)waitItems();
            end=SystemClock.elapsedRealtime()+20000;while((root.getWidth()==0||root.getHeight()==0||!root.isShown()||!root.hasWindowFocus())&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);check(root.getWidth()>0&&root.getHeight()>0&&root.isShown()&&root.hasWindowFocus(),"Native root did not become visible/focused");SystemClock.sleep(600);
            check(field(runtime,"browser")==null,"Native channel created a WebView");check(field(field(plugin,"api"),"client")==NetworkClient.sharedClient(),"Plugin did not reuse host OkHttp");check(field(field(plugin,"api"),"prefs")==getTargetContext().getSharedPreferences(account?"ntv-bl":"ntv-bl-instrumentation",0),"Wrong account/preferences namespace");
            if(account){accountScenarios(result);code=-1;return;}
            if(testVideo!=null){try{focusedPlayback(testVideo,result);}finally{publicPlaybackDiagnostics();}code=-1;return;}
            screenshot("bl-home.png");
            if("true".equals(args.getString("errors"))){errorScenarios();result.putString("stream","PASS: HTTP 503, malformed JSON, business -352, failing CDN 403, native error-details UI/copy/BACK, recovery and credential redaction");code=-1;return;}
            if(!"true".equals(args.getString("real"))){verifyBrowseIcons();browseNavigation();result.putString("icons","BBLL rail/card resources; no rail heading; measured 16:9 cover");}
            result.putLong("firstCardsMs",SystemClock.elapsedRealtime()-start);result.putInt("sdk",Build.VERSION.SDK_INT);result.putString("mode","true".equals(args.getString("smoke"))?"smoke":"full");
            Bundle focusStatus=new Bundle();View initialFocus=root.findFocus();focusStatus.putString("stream","BL_FOCUS "+(initialFocus==null?"null":initialFocus.getClass().getName()+":"+initialFocus.getContentDescription())+"\n");sendStatus(0,focusStatus);
            key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);waitRoute("detail");click("播放");waitRoute("player");
            end=SystemClock.elapsedRealtime()+30000;Object player=null;while(SystemClock.elapsedRealtime()<end){player=field(plugin,"player");if(player!=null&&"playing".equals(field(player,"state")))break;SystemClock.sleep(100);}check(player!=null&&"playing".equals(field(player,"state")),"Player did not prepare");
            final Object runningPlayer=player;Method position=player.getClass().getDeclaredMethod("position");position.setAccessible(true);SystemClock.sleep(1500);long p1=((Number)position.invoke(player)).longValue();SystemClock.sleep(800);long p2=((Number)position.invoke(player)).longValue();check(p2>p1,"Playback clock did not advance");
            Object engine=field(player,"engine");result.putString("engine",engine.getClass().getName());result.putLong("playbackMs",p2);
            if("true".equals(args.getString("interactions"))){interactionScenarios(player,result);result.putString("stream","PASS: touch/remote player interaction, existing danmaku continuity, transient playback recovery and main-thread responsiveness; "+result.toString());code=-1;return;}
            int soak=0;try{soak=Integer.parseInt(args.getString("soakSeconds","0"));}catch(NumberFormatException ignored){}long soakEnd=SystemClock.elapsedRealtime()+Math.min(600,Math.max(0,soak))*1000L;
            while(SystemClock.elapsedRealtime()<soakEnd){SystemClock.sleep(2000);check("playing".equals(field(player,"state")),"Playback stopped during sustained test: "+field(player,"state"));long at=((Number)position.invoke(player)).longValue();check(at>p2,"Playback clock stalled during sustained test");p2=at;Bundle sample=new Bundle();sample.putString("stream","BL_SOAK positionMs="+at+" javaUsed="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+" nativeUsed="+Debug.getNativeHeapAllocatedSize()+"\n");sendStatus(0,sample);}
            if("true".equals(args.getString("dashResume")))dashResume(engine,result);
            if("true".equals(args.getString("real"))){Object dm=field(player,"danmaku");end=SystemClock.elapsedRealtime()+12000;while(SystemClock.elapsedRealtime()<end&&((java.util.List<?>)field(dm,"lines")).isEmpty())SystemClock.sleep(100);result.putInt("officialDanmaku",((java.util.List<?>)field(dm,"lines")).size());result.putLong("officialDanmakuDraws",((Number)field(dm,"drawnCount")).longValue());result.putString("officialDanmakuStatus",String.valueOf(field(field(plugin,"dmFeed"),"status")));}
            Bundle playing=new Bundle();playing.putString("stream","BL_PLAYING engine="+engine.getClass().getName()+" position="+p2+"\n");sendStatus(0,playing);
            key(KeyEvent.KEYCODE_MEDIA_PAUSE);check("paused".equals(field(player,"state")),"Remote pause failed");key(KeyEvent.KEYCODE_MEDIA_PLAY);check("playing".equals(field(player,"state")),"Remote play failed");
            if(!"true".equals(args.getString("real"))){
                verifyPlayerLayout(player);
                result.putString("playerUi","Video metadata, clock, bottom related cards, side settings and live controls");
            }
            controls();key(KeyEvent.KEYCODE_MENU);check(field(plugin,"modal")!=null,"Remote MENU did not open player settings");
            if(!"true".equals(args.getString("real")))verifyPlayerSettingOrder();
            screenshot("bl-settings.png");option("画质");check(field(plugin,"modal")!=null,"Quality panel missing");key(KeyEvent.KEYCODE_BACK);check(field(plugin,"modal")!=null,"Nested BACK did not return to player settings");key(KeyEvent.KEYCODE_BACK);check(field(plugin,"modal")==null&&field(plugin,"player")==runningPlayer,"BACK did not dismiss only the in-View panel");
            if("true".equals(args.getString("premiumDash")))premiumDash(player,result);
            if(!"true".equals(args.getString("real"))&&!"true".equals(args.getString("smoke"))){
                long beforeSeek=((Number)position.invoke(player)).longValue();key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);end=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<end&&((Number)position.invoke(player)).longValue()<beforeSeek+5000)SystemClock.sleep(100);check(((Number)position.invoke(player)).longValue()>=beforeSeek+5000,"Remote fast forward failed");key(KeyEvent.KEYCODE_MEDIA_REWIND);SystemClock.sleep(800);
                controls();key(KeyEvent.KEYCODE_MENU);option("画质");option("360P");end=SystemClock.elapsedRealtime()+15000;
                while(SystemClock.elapsedRealtime()<end&&(field(player,"engine")==engine||!"playing".equals(field(player,"state"))))SystemClock.sleep(100);
                check(field(player,"engine")!=engine&&"playing".equals(field(player,"state")),"Quality switch did not replace and start the decoder");
                Object beforePart=field(player,"engine");controls();click("选集");screenshot("bl-episodes.png");key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER);end=SystemClock.elapsedRealtime()+15000;
                while(SystemClock.elapsedRealtime()<end&&((Number)field(plugin,"cid")).longValue()!=3000)SystemClock.sleep(100);check(((Number)field(plugin,"cid")).longValue()==3000,"Part selection failed");
                end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end&&(field(player,"engine")==beforePart||!"playing".equals(field(player,"state"))))SystemClock.sleep(100);check(field(player,"engine")!=null&&field(player,"engine")!=beforePart&&"playing".equals(field(player,"state")),"New part did not prepare");
                controls();click("字幕");option("中文测试字幕");
                end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end&&((JSONArray)field(player,"captions")).length()==0)SystemClock.sleep(100);check(((JSONArray)field(player,"captions")).length()>0,"Subtitle did not load");
                onMain(new Runnable(){public void run(){((xiao.bu.tv.jar.ChannelPlugin.NativePage)plugin).setActive(false);}});SystemClock.sleep(500);check(!((tv.danmaku.ijk.media.player.IMediaPlayer)field(player,"engine")).isPlaying(),"Background did not pause");
                onMain(new Runnable(){public void run(){((xiao.bu.tv.jar.ChannelPlugin.NativePage)plugin).setActive(true);}});SystemClock.sleep(500);check(((tv.danmaku.ijk.media.player.IMediaPlayer)field(player,"engine")).isPlaying(),"Foreground did not resume");
            }
            if(!"true".equals(args.getString("real"))&&!"true".equals(args.getString("smoke"))){
                Object dm=field(player,"danmaku");end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end&&((Number)field(dm,"drawnCount")).longValue()==0)SystemClock.sleep(100);check(((Number)field(dm,"drawnCount")).longValue()>0,"Danmaku never rendered");check(((java.util.List<?>)field(dm,"lines")).size()>=15,"Protobuf segments were not loaded");result.putLong("danmakuDraws",((Number)field(dm,"drawnCount")).longValue());
                controls();key(KeyEvent.KEYCODE_MENU);option("弹幕设置");screenshot("bl-danmaku-settings.png");option("弹幕透明度");option("50%");check(getTargetContext().getSharedPreferences("ntv-bl-instrumentation",0).getFloat("dm_alpha",0)==.5f,"Danmaku opacity setting not persisted");
                controls();key(KeyEvent.KEYCODE_MENU);option("播放速度");option("1.5x");check(getTargetContext().getSharedPreferences("ntv-bl-instrumentation",0).getFloat("play_speed",1)==("true".equals(args.getString("system"))?1:1.5f),"Playback speed setting failed");
                controls();key(KeyEvent.KEYCODE_MENU);option("画面缩放");option("拉伸");check(getTargetContext().getSharedPreferences("ntv-bl-instrumentation",0).getString("screen_ratio","").equals("拉伸"),"Screen ratio setting failed");
                for(int n=0;n<4;n++){Object old=field(player,"engine");key(n%2==0?KeyEvent.KEYCODE_MEDIA_PREVIOUS:KeyEvent.KEYCODE_MEDIA_NEXT);waitPlaying(old);check(((Number)field(plugin,"cid")).longValue()==(n%2==0?2000:3000),"Remote part switch CID mismatch");}
                Object beforeLine=field(player,"engine");controls();key(KeyEvent.KEYCODE_MENU);option("播放线路");option("备用线路 1");waitPlaying(beforeLine);check(String.valueOf(field(player,"source")).contains("backup=1"),"CDN backup line was not selected");
                controls();key(KeyEvent.KEYCODE_MENU);option("控制栏与播放偏好");option("默认启用字幕");end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end&&((JSONArray)field(player,"captions")).length()==0)SystemClock.sleep(100);check(((JSONArray)field(player,"captions")).length()>0,"Default subtitle preference did not load subtitles");
                controls();key(KeyEvent.KEYCODE_MENU);option("控制栏与播放偏好");option("显示快进快退按钮");check(findButton(root,"快进").getVisibility()==View.GONE&&findButton(root,"快退").getVisibility()==View.GONE,"Control button visibility preference failed");
                controls();screenshot("bl-controls.png");Bundle shot=new Bundle();shot.putString("stream","BL_CAPTURE bl-controls.png\n");sendStatus(0,shot);SystemClock.sleep(600);
            }
            closePlayback();waitRoute("detail");key(KeyEvent.KEYCODE_BACK);waitRoute("home");check(field(runningPlayer,"engine")==null,"Player was not released on return");waitItems();
            if(!"true".equals(args.getString("real"))){Object proxy=field(plugin,"proxy");Method url=proxy.getClass().getDeclaredMethod("url",String.class);url.setAccessible(true);String upstream=UriOrigin(args.getString("source"))+"/video.mp4",local=(String)url.invoke(proxy,upstream);okhttp3.OkHttpClient client=NetworkClient.sharedClient();
                try(okhttp3.Response r=client.newCall(new okhttp3.Request.Builder().url(local).header("Range","bytes=0-31").build()).execute()){check(r.code()==206&&r.body().bytes().length==32&&r.header("Content-Range").startsWith("bytes 0-31/"),"Media proxy Range failed");}
                try(okhttp3.Response r=client.newCall(new okhttp3.Request.Builder().url(local).head().build()).execute()){check(r.code()==200&&Long.parseLong(r.header("Content-Length"))>0,"Media proxy HEAD failed");}
                Method playlist=proxy.getClass().getDeclaredMethod("playlist",String.class,String.class);playlist.setAccessible(true);String rewritten=(String)playlist.invoke(proxy,"#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\nsegment.ts\n#EXT-X-ENDLIST\n",upstream);check(rewritten.contains("URI=\"http://127.0.0.1:")&&!rewritten.contains("key.bin")&&!rewritten.contains("segment.ts")&&!rewritten.contains("init.mp4"),"HLS URI rewrite failed");
            }
            click("直播");waitItems();key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);waitRoute("player");Object liveEngine=waitPlaying(null);Object live=field(plugin,"liveDanmaku");check(live!=null,"Live socket client missing");end=SystemClock.elapsedRealtime()+("true".equals(args.getString("real"))?20000:10000);while(SystemClock.elapsedRealtime()<end&&((Number)field(live,"received")).longValue()==0&&!String.valueOf(field(live,"status")).equals("直播弹幕已连接"))SystemClock.sleep(100);SystemClock.sleep(1200);
            if(!"true".equals(args.getString("real"))){check(((Number)field(live,"received")).longValue()>0,"Live danmaku not received");Object dm=field(field(plugin,"player"),"danmaku");check(((Number)field(dm,"drawnCount")).longValue()>0,"Live danmaku did not draw");
                controls();
                ImageButton liveSettings=(ImageButton)findButton(root,"直播设置");
                checkIcon(liveSettings,liveSettings.getDrawable(),field(plugin,"images"),"icon_setting");
                check(((View)field(field(plugin,"player"),"seek")).getVisibility()==View.GONE&&
                        findButton(root,"选集").getVisibility()==View.GONE,"Live toolbar contains video-only controls");
                if(!"true".equals(args.getString("smoke"))){key(KeyEvent.KEYCODE_MENU);option("直播画质");option("原画");waitPlaying(liveEngine);check(getTargetContext().getSharedPreferences("ntv-bl-instrumentation",0).getInt("live_quality",0)==10000,"Live quality selection failed");}}
            if("true".equals(args.getString("real")))SystemClock.sleep(8000);result.putLong("liveDanmaku",((Number)field(live,"received")).longValue());result.putString("liveDanmakuStatus",String.valueOf(field(live,"status")));screenshot("bl-live.png");Bundle liveShot=new Bundle();liveShot.putString("stream","BL_CAPTURE bl-live.png\n");sendStatus(0,liveShot);SystemClock.sleep(600);closePlayback();waitRoute("live");check(Boolean.TRUE.equals(field(live,"closed")),"Live socket not disposed");
            click("搜索");waitRoute("search");
            if(!"true".equals(args.getString("real"))){
                waitText("BL 热搜示例");
                View sidebar=(View)field(plugin,"sidebar");
                onMain(()->findButton(sidebar,"搜索").requestFocusFromTouch());key(KeyEvent.KEYCODE_DPAD_RIGHT);
                check(root.findFocus()==findButton(root,"T9键盘"),"RIGHT from the search shortcut did not reach the keyboard");
                key(KeyEvent.KEYCODE_DPAD_RIGHT);
                check(root.findFocus()==findButton(root,"全键盘"),"RIGHT did not move between keyboard modes");
                key(KeyEvent.KEYCODE_DPAD_LEFT);
                check(root.findFocus()==findButton(root,"T9键盘"),"LEFT did not return to the T9 mode button");
                onMain(()->{View digit=findButton(root,"2  ABC");digit.performClick();digit.performClick();check(findEdit(root).getText().toString().equals("B"),"Nine-key letter cycling failed");});
                click("全键盘");click("L");check(findEdit(root).getText().toString().equals("BL"),"Switching keyboard lost the current query");
                screenshot("bl-search-keyboard.png");
            }
            onMain(new Runnable(){public void run(){EditText input=findEdit(root);check(input!=null,"Search input missing");input.setText("true".equals(args.getString("real"))?"哔哩哔哩":"BL test");}});click("搜索");waitRoute("results");
            try{waitItems();result.putString("search","cards");}catch(AssertionError noCards){if(!"true".equals(args.getString("real")))throw noCards;if(containsText(root,"要求验证"))result.putString("search","site_verification_required");else if(containsText(root,"暂无内容"))result.putString("search","no_public_results");else throw noCards;}screenshot("bl-search.png");
            if(!"true".equals(args.getString("real"))){
                click("直播");waitText("直播搜索结果");key(KeyEvent.KEYCODE_BACK);waitRoute("search");
                click("最近搜索");
                check(findEdit(root).getText().toString().equals("BL test")&&findButton(root,"BL test")!=null,"Search query or history was lost");
                settingsNavigation();result.putString("browseUi","Home, regions, live filters, remote focus, keyboards, search, BBLL settings icons and theme");
            }
            click("我的");waitRoute("history");waitItems();screenshot("bl-history.png");
            click("用户");waitText("清空本地观看历史");key(KeyEvent.KEYCODE_BACK);waitRoute("history");
            for(String[] category:new String[][]{{"收藏","favorites"},{"追番","animeFollows"},{"追剧","dramaFollows"},{"稍后观看","later"}}){
                click(category[0]);waitRoute(category[1]);waitText("登录后查看"+category[0]);
            }
            focusButton("稍后观看");key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(root.findFocus()==findButton(root,"扫码登录"),"DOWN from the profile tab did not reach the login action");
            key(KeyEvent.KEYCODE_DPAD_UP);
            check(root.findFocus()==findButton(root,"稍后观看"),"UP from the login action did not return to the selected profile tab");
            click("重试");waitRoute("later");waitText("登录后查看稍后观看");
            screenshot("bl-mine.png");
            if(!"true".equals(args.getString("real")))followedSeasonPages();
            result.putLong("javaHeapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());result.putLong("nativeHeapBytes",Debug.getNativeHeapAllocatedSize());
            click("扫码登录");waitRoute("login");end=SystemClock.elapsedRealtime()+15000;while(!containsText(root,"扫码")&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);SystemClock.sleep(3000);check(containsText(root,"等待扫码")||containsText(root,"使用哔哩"),"QR encoding or polling failed");screenshot("bl-login.png");
            key(KeyEvent.KEYCODE_BACK);waitRoute("later");key(KeyEvent.KEYCODE_BACK);SystemClock.sleep(100);key(KeyEvent.KEYCODE_BACK);check(!runtime.visible(),"Root double BACK did not close the native channel");check(!activity.isFinishing(),"Plugin navigation closed the host Activity");
            check(((java.util.concurrent.ThreadPoolExecutor)field(plugin,"jobs")).isShutdown(),"Worker executor leaked after dispose");end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end&&!((java.util.Set<?>)field(field(plugin,"api"),"pending")).isEmpty())SystemClock.sleep(100);check(((java.util.Set<?>)field(field(plugin,"api"),"pending")).isEmpty(),"HTTP calls remained after dispose");
            check(((java.util.concurrent.ThreadPoolExecutor)field(plugin,"jobs")).awaitTermination(5,TimeUnit.SECONDS),"API worker threads did not terminate");
            check(((java.util.concurrent.ThreadPoolExecutor)field(field(plugin,"images"),"workers")).awaitTermination(5,TimeUnit.SECONDS),"Image workers did not terminate");
            check(((java.util.concurrent.ThreadPoolExecutor)field(field(plugin,"proxy"),"workers")).awaitTermination(5,TimeUnit.SECONDS),"Media proxy workers did not terminate");check(((java.net.ServerSocket)field(field(plugin,"proxy"),"server")).isClosed(),"Proxy server port remained open");
            if(!"true".equals(args.getString("real")))reopenWithSavedSettings();
            result.putString("stream","PASS: native JAR, shared host OkHttp, remote navigation, video decode/time advance, pause/resume, panel BACK, playback release, search, local history and root exit; "+result.toString());code=-1;
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));try{if(root!=null&&root.getWidth()>0&&root.getHeight()>0)screenshot("bl-error.png");}catch(Exception ignored){}}
        finally{if(activity!=null){onMain(new Runnable(){public void run(){activity.finish();}});waitForIdleSync();}restoreBlSettings();restoreHostSelection();finish(code,result);}
    }
    private static String UriOrigin(String source){return android.net.Uri.parse(source).getQueryParameter("apiOrigin");}
}
