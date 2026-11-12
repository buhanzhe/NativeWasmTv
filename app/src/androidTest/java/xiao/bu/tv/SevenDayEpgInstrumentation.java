package xiao.bu.tv;

import android.content.*;import android.media.AudioManager;import android.os.*;import android.view.*;import android.widget.*;
import java.io.*;import java.lang.reflect.*;import java.net.*;import java.util.*;import org.json.*;
import tv.danmaku.ijk.media.player.*;

/** Actual HTTP XMLTV, seven selectable dates and decoded, time-labelled archives. */
public final class SevenDayEpgInstrumentation extends WebTaskNavigationInstrumentation {
    private Bundle args;private JSONObject report=new JSONObject();private JSONArray trials=new JSONArray();private File output;private String base;private Channel good;private SevenDayLocalFixture localFixture;
    interface Condition{boolean get()throws Exception;}
    public void onCreate(Bundle b){args=b;start();}
    private Object get(String key)throws Exception{return field(activity,key);}
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private void until(Condition c,long timeout,String why)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(!c.get()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(40);check(c.get(),why);}
    private byte[] read(String path)throws Exception{URLConnection c=NetworkClient.open(new URL(base+path));c.setConnectTimeout(5000);c.setReadTimeout(10000);try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){check(out.size()+n<=4*1024*1024,"Fixture response limit");out.write(b,0,n);}return out.toByteArray();}}
    private void save()throws Exception{try(FileOutputStream out=new FileOutputStream(new File(output,"results.json"))){out.write(report.toString(2).getBytes("UTF-8"));}}
    private EpgListAdapter epg()throws Exception{return (EpgListAdapter)get("epgAdapter");}
    private ListView dates()throws Exception{return (ListView)get("epgDateList");}
    private ListView programs()throws Exception{return (ListView)get("epgList");}
    private IMediaPlayer player()throws Exception{return (IMediaPlayer)get("player");}
    private void key(int code)throws Exception{main(()->activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code)));waitForIdleSync();}
    private void screen(String name)throws Exception{SystemClock.sleep(150);android.graphics.Bitmap b=getUiAutomation().takeScreenshot();check(b!=null,"Screenshot unavailable");try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
    private void playback(EpgManager.Program p)throws Exception{
        until(()->(Boolean)get("prepared")&&(Boolean)get("videoRenderingStarted")&&player()!=null&&player().isPlaying()&&
                (p==null?get("catchupProgram")==null&&!String.valueOf(get("activePlayerStreamUrl")).contains("start="):get("catchupProgram") instanceof EpgManager.Program&&((EpgManager.Program)get("catchupProgram")).startMillis==p.startMillis&&(Boolean)get("catchupTimeVerified")&&String.valueOf(p.startMillis/1000).equals(android.net.Uri.parse(String.valueOf(get("activePlayerStreamUrl"))).getQueryParameter("start"))),25000,"No rendered "+(p==null?"live":"archive")+" playback; status="+get("catchupValidationError"));
    }
    private void browse(int index)throws Exception{
        main(()->call("openChannelList"));waitForIdleSync();
        main(()->{ListView list=(ListView)get("channelList");list.clearChoices();list.setItemChecked(index,true);list.setSelection(index);set("epgExpanded",true);call("showEpgForBrowsingChannel",new Class[]{int.class},index);});waitForIdleSync();
        until(()->programs().isShown()&&programs().getWidth()>0,3000,"Guide has no visible layout");
        check((Integer)call("browsingChannelPosition",new Class[]{})==index,"Channel selection changed after guide layout");
    }
    private void day(int index)throws Exception{
        main(()->call("focusEpgList"));waitForIdleSync();
        check(dates().hasFocus(),"Date column cannot receive remote focus");
        int old=((EpgDateAdapter)get("epgDateAdapter")).selectedIndex();
        for(int i=old;i!=index;i+=index>old?1:-1)key(index>old?KeyEvent.KEYCODE_DPAD_DOWN:KeyEvent.KEYCODE_DPAD_UP);
        check(((EpgDateAdapter)get("epgDateAdapter")).selectedIndex()==index,"Remote date selection drifted");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);check(programs().hasFocus(),"Right key did not enter programme column");
    }
    private void selectProgram(int index)throws Exception{
        main(()->{Object pager=get("epgPager");Method reset=pager.getClass().getDeclaredMethod("reset");reset.setAccessible(true);reset.invoke(pager);
            ListView list=programs();list.requestFocusFromTouch();list.requestFocus();list.clearChoices();list.setItemChecked(index,true);list.setSelectionFromTop(index,0);});
        waitForIdleSync();SystemClock.sleep(120);check(programs().getSelectedItemPosition()==index,"Programme selection unavailable");
    }
    private void startLive()throws Exception{main(()->{set("currentGroupIndex",0);set("currentChannelIndex",0);set("currentSourceIndex",0);call("startChannel",new Class[]{int.class},0);});playback(null);}
    private void returnLive()throws Exception{main(()->call("returnToLive"));playback(null);check(!String.valueOf(get("activePlayerStreamUrl")).contains("start="),"Return to live retained archive parameters");}
    private void rebuild(String path,int count)throws Exception{
        EpgManager manager=(EpgManager)get("epgManager");settings(new JSONObject().put("epgUrls",new JSONArray().put(base+path)));
        until(()->!manager.isLoading()&&manager.getLoadedUrl().contains(path)&&manager.programsFor(good).size()==count,15000,"HTTP guide did not rebuild: "+path+" error="+manager.getLastError());
    }
    private void channelFocus()throws Exception{
        main(()->{set("epgNavigationActive",false);call("setFavoriteActionFocused",new Class[]{boolean.class},false);ListView list=(ListView)get("channelList");list.requestFocusFromTouch();list.requestFocus();list.clearChoices();list.setItemChecked(0,true);list.setSelection(0);});waitForIdleSync();
    }
    private boolean favorite()throws Exception{return (Boolean)call("isBrowsingChannelFavorite",new Class[]{int.class},0);}
    private void heldConfirm()throws Exception{
        long down=SystemClock.uptimeMillis();main(()->activity.dispatchKeyEvent(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0)));
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+180);waitForIdleSync();
        main(()->activity.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,3)));
        main(()->activity.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0)));waitForIdleSync();
    }
    private void touchRow(boolean star,boolean hold)throws Exception{
        android.view.accessibility.AccessibilityNodeInfo active=getUiAutomation().getRootInActiveWindow();
        if(active!=null){
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> tips=active.findAccessibilityNodeInfosByText("Swipe down from the top to exit full screen");
            if(!tips.isEmpty())for(android.view.accessibility.AccessibilityNodeInfo button:active.findAccessibilityNodeInfosByText("OK"))button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            if(!tips.isEmpty()){waitForIdleSync();SystemClock.sleep(120);}active.recycle();
        }
        android.graphics.Rect bounds=new android.graphics.Rect();
        main(()->{ListView list=(ListView)get("channelList");View row=list.getChildAt(0-list.getFirstVisiblePosition());check(row!=null&&row.getGlobalVisibleRect(bounds),"Channel row is clipped");
            if(star){View button=row.findViewById(R.id.channel_item_favorite);if(button!=null){check(button.getGlobalVisibleRect(bounds),"Favorite button clipped");}else bounds.left=bounds.right-Math.round(28*activity.themeScale());}
        });
        float x=star?bounds.exactCenterX():bounds.left+bounds.width()*.5f,y=bounds.exactCenterY();long down=SystemClock.uptimeMillis();
        MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0);press.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{check(getUiAutomation().injectInputEvent(press,true),"Touch down was not injected");}finally{press.recycle();}
        SystemClock.sleep(hold?ViewConfiguration.getLongPressTimeout()+180:60);
        MotionEvent release=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0);release.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{check(getUiAutomation().injectInputEvent(release,true),"Touch up was not injected");}finally{release.recycle();}waitForIdleSync();SystemClock.sleep(180);
    }
    private void favoriteVisibility(boolean visible)throws Exception{
        main(()->{ListView list=(ListView)get("channelList");View row=list.getChildAt(0-list.getFirstVisiblePosition());check(row!=null,"Missing favorite row");View button=row.findViewById(R.id.channel_item_favorite);
            if(button!=null)check(button.getVisibility()==(visible?View.VISIBLE:View.GONE),"Classic favorite visibility wrong");
            else{Object holder=row.getTag();java.lang.reflect.Field state=holder.getClass().getDeclaredField("state");state.setAccessible(true);check(((Bundle)state.get(holder)).getBoolean("showFavorite")==visible,"Themed favorite visibility wrong");}
            check(((ChannelListAdapter)get("channelAdapter")).trailingWidth()==(visible?34f:0f),"Hidden favorite retained reserved width");
        });
    }
    private void runFavoriteChecks(ChannelCatalog.Group[] groups)throws Exception{
        JSONArray checks=new JSONArray();report.put("favoriteChecks",checks);
        check((Boolean)get("epgShowFavorite"),"Existing favorite buttons were hidden by default");
        for(String id:TvUiPlugin.IDS){
            TvUiPlugin.select(getTargetContext(),id);waitForIdleSync();settings(new JSONObject().put("epgShowFavorite",false));browse(0);channelFocus();favoriteVisibility(false);
            IMediaPlayer live=player();check(!favorite(),"Unexpected initial favorite");
            key(KeyEvent.KEYCODE_DPAD_RIGHT);check(dates().hasFocus()&&!(Boolean)get("favoriteActionFocused"),"Hidden star stole RIGHT focus");
            main(()->{TextView date=(TextView)((EpgDateAdapter)get("epgDateAdapter")).getView(((EpgDateAdapter)get("epgDateAdapter")).selectedIndex(),null,dates());Bundle palette=xiao.bu.tv.skin.SharedTvUi.dialogPalette(id);check(date.getCurrentTextColor()==palette.getInt("focusText"),"Date focus ink mismatches theme");});
            screen("dates-"+id);key(KeyEvent.KEYCODE_DPAD_LEFT);check(((ListView)get("channelList")).hasFocus(),"LEFT did not return to channel");
            heldConfirm();check(favorite()&&player()==live&&live.isPlaying(),"Remote hold toggled repeatedly or changed playback");
            check(getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0).getString("favorite_channel_keys_v1","").contains(good.name),"Favorite membership was not saved");
            touchRow(false,true);if(favorite()||player()!=live){screen("touch-failure-"+id);report.put("touchFavorite",favorite()).put("touchPlayerPreserved",player()==live).put("windowFocused",activity.hasWindowFocus());}check(!favorite()&&player()==live,"Touch hold did not remove favorite without tuning");
            settings(new JSONObject().put("epgShowFavorite",true));browse(0);channelFocus();favoriteVisibility(true);screen("favorite-"+id);
            touchRow(true,false);check(favorite()&&player()==live,"Visible favorite touch did not add without tuning");
            channelFocus();key(KeyEvent.KEYCODE_DPAD_RIGHT);check((Boolean)get("favoriteActionFocused")&&((ListView)get("channelList")).hasFocus(),"Visible star cannot receive remote focus");
            key(KeyEvent.KEYCODE_DPAD_CENTER);check(!favorite()&&player()==live,"Favorite OK did not remove without tuning");
            checks.put(new JSONObject().put("theme",id).put("hiddenNavigation",true).put("datePalette",true).put("remoteLongPress",true).put("touchLongPress",true).put("visibleTouch",true).put("visibleRemote",true).put("keptLivePlayer",true));save();
        }
        settings(new JSONObject().put("epgShowFavorite",false));browse(0);channelFocus();
        IMediaPlayer live=player();long down=SystemClock.uptimeMillis();main(()->activity.dispatchKeyEvent(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0)));check(player()==live,"Short OK switched on key down");
        main(()->activity.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0,0,0,0,KeyEvent.FLAG_CANCELED)));check(player()==live&&!favorite(),"Canceled OK changed playback or favorites");
        key(KeyEvent.KEYCODE_DPAD_DOWN);check((Integer)call("browsingChannelPosition",new Class[]{})==1,"Remote DOWN did not select second channel");
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);until(()->(Integer)get("currentChannelIndex")==1,3000,"Short OK failed to tune on release");playback(null);startLive();
        main(()->{ChannelCatalog.setCustomGroups(groups,true);call("refreshFavoriteCatalog");});browse(0);channelFocus();
        // Reopen the original group after inserting the favorites group at index zero.
        main(()->{set("browsingGroupIndex",1);call("showChannelMenu",new Class[]{int.class},1);});waitForIdleSync();channelFocus();heldConfirm();check(favorite(),"Failed to seed favorites group");
        main(()->call("showChannelMenu",new Class[]{int.class},0));waitForIdleSync();channelFocus();heldConfirm();check(ChannelCatalog.GROUPS[0].channels.length==0,"Removing the last favorite retained a stale row");
        report.put("canceledConfirmPreservesPlayback",true).put("shortConfirmTunes",true).put("removeLastFavorite",true);
    }
    private void runChecks()throws Exception{
        JSONObject config=new JSONObject(new String(read("/config.json"),"UTF-8"));JSONArray expected=config.getJSONArray("dates");
        Method parse=PlaylistManager.class.getDeclaredMethod("parse",byte[].class,String.class);parse.setAccessible(true);
        ChannelCatalog.Group[] groups=(ChannelCatalog.Group[])parse.invoke(null,read("/channels.m3u"),base+"/channels.m3u");
        check(groups.length==1&&groups[0].channels.length==2,"Per-source M3U entries did not merge");good=groups[0].channels[0];check(good.sourceCount()==2&&good.catchupDays()==7,"Seven-day/per-line metadata lost");
        main(()->{call("stopCurrentChannelLoad");((java.util.concurrent.atomic.AtomicInteger)get("catalogLoadGeneration")).incrementAndGet();AutoUpdater updater=(AutoUpdater)get("autoUpdater");if(updater!=null)updater.destroy();ChannelCatalog.setCustomGroups(groups,false);set("autoSwitchSource",false);set("autoSwitchSourceSeconds",0);});
        rebuild("/guide.xml",169);startLive();browse(0);
        if("favorites".equals(args.getString("mode"))){runFavoriteChecks(groups);return;}
        check(dates().getVisibility()==View.VISIBLE&&dates().getCount()==7,"Seven dates not displayed exactly");
        for(int i=0;i<7;i++)check(epg().availableDays().get(i)==expected.getLong(i),"Wrong chronological date at "+i);
        report.put("sevenActualDates",true).put("expiredDayExcluded",true);
        day(0);selectProgram(5);key(KeyEvent.KEYCODE_DPAD_LEFT);day(1);key(KeyEvent.KEYCODE_DPAD_LEFT);day(0);
        check(programs().getSelectedItemPosition()==5,"Changing dates lost the selected programme within a day");report.put("perDayCursorRestored",true);
        for(int i=0;i<7;i++){
            TvUiPlugin.select(getTargetContext(),TvUiPlugin.IDS[i%TvUiPlugin.IDS.length]);waitForIdleSync();browse(0);day(i);
            long selected=expected.getLong(i);check(epg().selectedDay()==selected&&epg().getCount()==24,"Date did not expose its 24 programme entries");
            for(int j=0;j<epg().getCount();j++)check(EpgManager.dayStart(epg().getItem(j).startMillis)==selected,"Programme from another day leaked into selection");
            selectProgram(1);EpgManager.Program programme=epg().getItem(1);JSONObject row=new JSONObject().put("date",selected).put("theme",TvUiPlugin.current()==null?"builtin":TvUiPlugin.current().id).put("program",programme.title).put("expectedStart",programme.startMillis).put("expectedStop",programme.stopMillis);trials.put(row);save();
            if(i==0||i==5)screen("guide-day-"+i);
            long started=SystemClock.elapsedRealtime();
            if(i%2==0)key(KeyEvent.KEYCODE_DPAD_CENTER);else main(()->programs().performItemClick(programs().getSelectedView(),1,1));
            playback(programme);row.put("firstFrameMs",SystemClock.elapsedRealtime()-started);
            String url=String.valueOf(get("activePlayerStreamUrl"));android.net.Uri selectedUrl=android.net.Uri.parse(url);
            check(Long.parseLong(selectedUrl.getQueryParameter("start"))*1000==programme.startMillis&&Long.parseLong(selectedUrl.getQueryParameter("end"))*1000==programme.stopMillis,"Archive address used another date/time");
            check((Boolean)get("catchupTimeVerified"),"Archive timeline was not verified against returned PDT");
            IMediaPlayer archive=player();long before=archive.getCurrentPosition();long frames=archive instanceof IjkMediaPlayer?IjkStreamMetrics.outputFrameCount((IjkMediaPlayer)archive):-1;SystemClock.sleep(5000);
            long advanced=archive.getCurrentPosition()-before;check(player()==archive&&archive.isPlaying()&&advanced>3500,"Archive output clock stalled");
            if(frames>=0)check(IjkStreamMetrics.outputFrameCount((IjkMediaPlayer)archive)>frames+20,"Archive video frames did not advance");
            row.put("advancedMs",advanced).put("backend",archive.getClass().getSimpleName()).put("timelineVerified",true);
            if(i==0||i==3){long duration=(Long)call("seekableDuration",new Class[]{});check(duration>=30000,"Archive was not seekable");main(()->call("seekPlayback",new Class[]{long.class},18000L));until(()->Math.abs(player().getCurrentPosition()-18000L)<4000,10000,"Archive seek missed target");long position=player().getCurrentPosition();SystemClock.sleep(1500);check(player().getCurrentPosition()>position+700,"Archive did not continue after seek");row.put("seekPassed",true);}
            if(i==3){main(()->call("switchCustomSource",new Class[]{int.class,boolean.class,String.class},1,false,""));until(()->"2".equals(android.net.Uri.parse(String.valueOf(get("activePlayerStreamUrl"))).getQueryParameter("line")),25000,"Archive line switch returned to first source");playback(programme);check(epg().selectedDay()==selected,"Archive line switch lost selected date");row.put("perSourceAppendPassed",true);}
            if(i==0||i==5)screen("archive-day-"+i);
            returnLive();browse(0);check(epg().selectedDay()==selected,"Returning from archive lost browsed date");row.put("returnLivePassed",true).put("browseDatePreserved",true).put("passed",true);save();
        }
        // Future programmes and failed archives must preserve the current live decoder.
        day(6);selectProgram(23);IMediaPlayer live=player();main(()->call("playEpgProgram",new Class[]{int.class},23));SystemClock.sleep(300);check(player()==live&&get("catchupProgram")==null,"Future programme interrupted live");report.put("futurePreservesLive",true);
        browse(1);selectProgram(0);live=player();long position=live.getCurrentPosition();main(()->programs().performItemClick(programs().getSelectedView(),0,0));until(()->get("catchupPreloader")==null,10000,"Failed archive left preloader active");SystemClock.sleep(1500);check(player()==live&&live.isPlaying()&&live.getCurrentPosition()>position+700&&get("catchupProgram")==null,"HTTP failure stopped the current programme");report.put("failedArchivePreservesLive",true);
        rebuild("/guide-sparse.xml",121);browse(0);check(dates().getCount()==5,"Missing EPG dates were filled artificially");report.put("sparseDatesOnly",true);
        rebuild("/guide-single.xml",25);browse(0);check(dates().getVisibility()==View.GONE&&epg().getCount()==24,"Single-day refresh retained picker/old history");report.put("singleDayHidesPicker",true);
    }
    public void onStart(){Bundle result=new Bundle();SharedPreferences prefs=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);Map<String,?> saved=new HashMap<>(prefs.getAll());SharedPreferences theme=getTargetContext().getSharedPreferences("tv_ui",0);Map<String,?> savedTheme=new HashMap<>(theme.getAll());ChannelCatalog.Group[] savedGroups=ChannelCatalog.GROUPS;AudioManager audio=(AudioManager)getTargetContext().getSystemService(Context.AUDIO_SERVICE);int volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        try{
            check(Build.VERSION.SDK_INT>=19,"Run requires Android 4.4+");base=args.getString("base");if("1".equals(args.getString("localFixture"))){localFixture=new SevenDayLocalFixture(getContext());base=localFixture.base();}check(base!=null,"Missing fixture URL");output=new File(getTargetContext().getExternalFilesDir(null),"epg-seven");output.mkdirs();report.put("platform",Build.VERSION.RELEASE).put("model",Build.MODEL).put("controlledFixture",true).put("trials",trials);
            prefs.edit().putBoolean("auto_update_channel_list",false).putBoolean("neighbor_probe_enabled",false).putBoolean("player_volume_balance",false).putBoolean("continuous_ts_enabled",false).putBoolean("epg_enabled",true).putBoolean("epg_date_picker",true).putString("epg_url",base+"/guide.xml").putString("epg_urls_v2",new JSONArray().put(base+"/guide.xml").toString()).putString("player_engine","ijk").putString("decode_mode","software").putString("network_dns","system").putString("last_channel_snapshot_v2",new JSONObject().put("groupTitle","bootstrap").put("catalogSource",ChannelCatalog.SOURCE_CUSTOM).put("number","1").put("name","bootstrap").put("urls",new JSONArray().put(base+"/live.m3u8?line=1")).put("sourceIndex",0).toString()).commit();
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);
            if("1".equals(args.getString("legacyNumeric")))prefs.edit().putInt("web_view_page_scale",1).putInt("player_volume_target",-30).commit();
            android.app.Instrumentation.ActivityMonitor launcher=addMonitor(MainActivity.class.getName(),null,false);
            Bundle phase=new Bundle();phase.putString("phase","await_main");sendStatus(0,phase);
            activity=(MainActivity)launcher.waitForActivityWithTimeout(20000);removeMonitor(launcher);check(activity!=null,"Host did not launch the test Activity");
            SystemClock.sleep(1200);
            if("1".equals(args.getString("legacyNumeric"))){check(((Number)get("webViewPageScale")).doubleValue()==1d&&((Number)get("playerVolumeTarget")).floatValue()==-30f,"Legacy integer preferences did not survive Activity creation");report.put("legacyNumericStartupPassed",true);}
            runChecks();report.put("passed",true);
        }catch(Throwable e){try{report.put("passed",false).put("failure",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        finally{
            try{if(localFixture!=null)localFixture.close();}catch(IOException ignored){}
            try{if(activity!=null)main(()->activity.finish());}catch(Exception ignored){}ChannelCatalog.GROUPS=savedGroups;restore(prefs,saved);restore(theme,savedTheme);audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);
            try{report.put("preferencesRestored",saved.equals(prefs.getAll())).put("volumeRestored",audio.getStreamVolume(AudioManager.STREAM_MUSIC)==volume);if(output!=null)save();}catch(Exception ignored){}
            result.putString("stream",report.toString());finish(report.optBoolean("passed")?-1:0,result);
        }
    }
}
