package xiao.bu.tv;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.ListView;
import java.io.*;
import java.lang.reflect.*;
import org.json.*;
import tv.danmaku.ijk.media.player.IMediaPlayer;

/** Exercise real bundled DEX, live layout switching, recycling and fallback. */
final class TvUiChecks {
    static void epgThemes(Instrumentation t,MainActivity a,JSONObject row,String base)throws Exception {
        EpgManager probe=new EpgManager(a);probe.refresh(new String[]{EpgManager.DEFAULT_URL},null);
        long until=SystemClock.elapsedRealtime()+80000;while(probe.isLoading()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
        check(!probe.programsFor(ChannelCatalog.CCTV_CHANNELS[0]).isEmpty(),"Default EPG unavailable: "+probe.getLastError());
        row.put("defaultEpg",EpgManager.DEFAULT_URL).put("defaultProgrammes",probe.programsFor(ChannelCatalog.CCTV_CHANNELS[0]).size());
        probe=new EpgManager(a);String retry=base+"/retry-guide.xml";probe.refresh(new String[]{retry},null);
        until=SystemClock.elapsedRealtime()+10000;while(probe.isLoading()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(30);
        check(probe.getLastError().length()>0,"Fixture did not fail first request");
        int generation=(Integer)member(probe,"refreshGeneration");for(int i=0;i<20;i++)probe.refresh(new String[]{retry},null);
        check((Integer)member(probe,"refreshGeneration")==generation,"Failure cooldown generated redundant requests");
        Field attempted=EpgManager.class.getDeclaredField("attemptedAt");attempted.setAccessible(true);attempted.setLong(probe,SystemClock.elapsedRealtime()-60001);
        probe.refresh(new String[]{retry},null);until=SystemClock.elapsedRealtime()+10000;while(probe.isLoading()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(30);
        check(probe.getLastError().isEmpty(),"Same-day EPG retry did not recover");
        generation=(Integer)member(probe,"refreshGeneration");for(int i=0;i<20;i++)probe.refresh(new String[]{retry},null);
        check((Integer)member(probe,"refreshGeneration")==generation,"Successful guide generated redundant requests");
        row.put("sameDayRetry",true).put("requestDeduplication",true);
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");
        ChannelCatalog.Group[] catalog=ChannelCatalog.GROUPS;Object[] coordinates={field(a,"currentGroupIndex"),field(a,"currentChannelIndex"),field(a,"currentSourceIndex"),field(a,"browsingGroupIndex")};
        Object epgUrls=field(a,"epgUrls"),oldManager=field(a,"epgManager");boolean expanded=(Boolean)field(a,"epgExpanded");
        Object player=field(a,"player");android.view.Surface surface=((DirectVideoView)field(a,"videoView")).getVideoSurface();
        File out=new File(a.getExternalFilesDir(null),"epg-theme");out.mkdirs();JSONArray themes=new JSONArray();
        String m3u="#EXTM3U\n#EXTINF:-1 tvg-id=\"catchup-test\" group-title=\"央视频道\" catchup=\"append\" catchup-days=\"7\" catchup-source=\"?start={utc}&end={utcend}\",CCTV-1 综合\n"+base+"/live.m3u8\n#EXTINF:-1 tvg-id=\"catchup-test\" group-title=\"央视频道\",CCTV-2 财经\n"+base+"/auto.m3u8\n";
        Method parse=PlaylistManager.class.getDeclaredMethod("parse",byte[].class,String.class);parse.setAccessible(true);
        ChannelCatalog.Group[] fixture=(ChannelCatalog.Group[])parse.invoke(null,m3u.getBytes("UTF-8"),base+"/channels.m3u");
        final EpgManager guide=probe;
        Method show=MainActivity.class.getDeclaredMethod("showEpgForBrowsingChannel",int.class);show.setAccessible(true);
        try{
            final EpgManager gated=new EpgManager(a);boolean wasLoading=(Boolean)field(a,"loadingActive");
            try{
                main(t,()->{set(a,"epgUrls",new String[]{base+"/gate-guide.xml"});set(a,"epgManager",gated);set(a,"loadingActive",true);set(a,"epgRefreshQueuedAt",SystemClock.uptimeMillis()-15001L);((Runnable)field(a,"deferredEpgRefresh")).run();});
                until=SystemClock.elapsedRealtime()+10000;while(gated.isLoading()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(30);
                check((Integer)member(gated,"refreshGeneration")==1&&gated.getLastError().isEmpty(),"Ongoing buffering starved the EPG deadline");row.put("bufferingDeadline",true);
            }finally{main(t,()->set(a,"loadingActive",wasLoading));}
            main(t,()->{ChannelCatalog.GROUPS=fixture;set(a,"currentGroupIndex",0);set(a,"currentChannelIndex",0);set(a,"currentSourceIndex",0);set(a,"browsingGroupIndex",0);set(a,"epgUrls",new String[]{retry});set(a,"epgManager",guide);((ChannelListAdapter)field(a,"channelAdapter")).setEpgManager(guide);});
            for(String id:TvUiPlugin.IDS){
                TvUiPlugin.select(a,id);t.waitForIdleSync();
                main(t,()->{invoke(a,"openChannelList");set(a,"epgExpanded",true);show.invoke(a,0);});
                t.waitForIdleSync();SystemClock.sleep(100);
                final int[] history={-1};
                main(t,()->{
                    EpgListAdapter adapter=(EpgListAdapter)field(a,"epgAdapter");ListView list=(ListView)field(a,"epgList");boolean live=false,archive=false;
                    for(int i=0;i<adapter.getCount();i++){
                        View v=adapter.getView(i,null,list);String title=TvUiPlugin.current()==null?((android.widget.TextView)v.findViewById(R.id.epg_program_title)).getText().toString():v.getContentDescription().toString();
                        check(!title.contains("尝试回看"),"Uncertain catchup label remains");
                        if(title.contains("回看 ·")){archive=true;history[0]=i;}if(title.contains("直播 ·"))live=true;
                    }
                    check(live&&archive,"Configured catchup labels missing "+id);
                    list.requestFocusFromTouch();list.requestFocus();list.setSelection(history[0]);
                });t.waitForIdleSync();SystemClock.sleep(100);
                main(t,()->{
                    ListView list=(ListView)field(a,"epgList"),channels=(ListView)field(a,"channelList");View epg=list.getSelectedView();
                    check(epg!=null&&list.hasFocus(),"Programme focus missing "+id);
                    if(TvUiPlugin.current()!=null)check(epg.getHeight()==channels.getChildAt(0).getHeight(),"Programme/channel row pitches differ "+id);
                    check(!((android.widget.TextView)field(a,"epgStatus")).getText().toString().contains("尝试"),"Header still advertises inferred support");
                    themes.put(new JSONObject().put("theme",id).put("rowHeight",epg.getHeight()).put("focused",list.hasFocus()));
                });screenshot(t,out,id+"-configured");
                main(t,()->{
                    EpgListAdapter adapter=(EpgListAdapter)field(a,"epgAdapter");adapter.showPrograms(guide.programsFor(fixture[0].channels[1]),fixture[0].channels[1]);
                    ListView list=(ListView)field(a,"epgList");for(int i=0;i<adapter.getCount();i++){
                        View v=adapter.getView(i,null,list);String title=TvUiPlugin.current()==null?((android.widget.TextView)v.findViewById(R.id.epg_program_title)).getText().toString():v.getContentDescription().toString();
                        check(!title.contains("回看 ·")&&!title.contains("直播 ·")&&!title.contains("尝试"),"Inferred catchup labelled as configured "+id);
                    }
                    invoke(a,"closeChannelList");
                });
            }
            check(field(a,"player")==player&&((DirectVideoView)field(a,"videoView")).getVideoSurface()==surface,"Guide theme switching restarted playback");
            row.put("epgThemes",themes).put("playbackPreserved",true);
        }finally{
            main(t,()->{invoke(a,"closeChannelList");ChannelCatalog.GROUPS=catalog;set(a,"currentGroupIndex",coordinates[0]);set(a,"currentChannelIndex",coordinates[1]);set(a,"currentSourceIndex",coordinates[2]);set(a,"browsingGroupIndex",coordinates[3]);set(a,"epgUrls",epgUrls);set(a,"epgManager",oldManager);((ChannelListAdapter)field(a,"channelAdapter")).setEpgManager((EpgManager)oldManager);set(a,"epgExpanded",expanded);});
            TvUiPlugin.select(a,saved);t.waitForIdleSync();
        }
    }
    private static void toggleGroup(Instrumentation t,MainActivity a,PlaylistManager manager,int index,String name,boolean visible)throws Exception {
        tapMenuOption(t,a,index);long end=SystemClock.elapsedRealtime()+8000;final boolean[] matched={false};
        while(!matched[0]&&SystemClock.elapsedRealtime()<end){
            main(t,()->{
                boolean found=false;for(ChannelCatalog.Group group:ChannelCatalog.GROUPS)if(name.equals(group.title))found=true;
                ListView list=menuList(a,false);String label=(String)list.getAdapter().getItem(index);
                matched[0]=found==visible&&label.endsWith(visible?" ●":" ○");
            });if(!matched[0])SystemClock.sleep(40);
        }
        check(matched[0],"Group switch did not apply: "+name+" visible="+visible);
        check(((TvUiMenu)field(a,"tvUiMenu")).visible(),"Group switch dismissed native menu");
    }
    static void groupVisibility(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        PlaylistManager manager=(PlaylistManager)field(a,"playlistManager");
        Field managed=PlaylistManager.class.getDeclaredField("managementGroups"),visible=PlaylistManager.class.getDeclaredField("visibleGroups");managed.setAccessible(true);visible.setAccessible(true);
        Object oldManaged=managed.get(manager),oldVisible=visible.get(manager);
        Field custom=ChannelCatalog.class.getDeclaredField("customGroups"),favorites=ChannelCatalog.class.getDeclaredField("favoriteChannels"),shown=ChannelCatalog.class.getDeclaredField("favoritesVisible");
        custom.setAccessible(true);favorites.setAccessible(true);shown.setAccessible(true);
        Object oldCustom=custom.get(null),oldFavorites=favorites.get(null);boolean oldShown=shown.getBoolean(null);
        ChannelCatalog.Group[] catalog=ChannelCatalog.GROUPS;int g=(Integer)field(a,"currentGroupIndex"),c=(Integer)field(a,"currentChannelIndex"),source=(Integer)field(a,"currentSourceIndex");
        String selected=new JSONObject(TvUiPlugin.status(a)).getString("selected");
        android.content.SharedPreferences prefs=a.getSharedPreferences("management",0);
        String disabledKey="disabled_merged_playlist_groups_v1",orderKey="merged_playlist_group_order_v1",favoritesKey="favorites_group_visible";
        java.util.Map<String,?> saved=prefs.getAll();
        java.util.Set<String> keys=(java.util.Set<String>)field(a,"favoriteChannelKeys"),oldKeys=new java.util.LinkedHashSet<>(keys);
        Channel current=catalog[g].channels[c];Channel base=new Channel("1","分组开关测试","",current.sourceUrl(source),null,null,null,null);
        ChannelCatalog.Group first=new ChannelCatalog.Group("分组甲",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{base});
        ChannelCatalog.Group second=new ChannelCatalog.Group("分组乙",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{base});
        ChannelCatalog.Group[] fixture={first,second};
        Method key=MainActivity.class.getDeclaredMethod("favoriteKey",ChannelCatalog.Group.class,Channel.class);key.setAccessible(true);
        String favorite=(String)key.invoke(null,first,base);
        File out=new File(a.getExternalFilesDir(null),"group-visibility");out.mkdirs();JSONArray results=new JSONArray();
        Instrumentation.ActivityMonitor monitor=t.addMonitor(ManagementActivity.class.getName(),null,false);
        try{
            prefs.edit().remove(disabledKey).remove(orderKey).putBoolean(favoritesKey,true).commit();
            managed.set(manager,fixture);manager.updateGroupStates(new JSONArray());
            main(t,()->{ChannelCatalog.setCustomGroups(fixture,true);set(a,"currentGroupIndex",1);set(a,"currentChannelIndex",0);set(a,"currentSourceIndex",0);keys.clear();keys.add(favorite);invoke(a,"refreshFavoriteCatalog");});
            for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}){
                TvUiPlugin.select(a,id);t.waitForIdleSync();Object player=field(a,"player");int request=(Integer)field(a,"playRequestId");
                main(t,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(4);menuList(a,true).performItemClick(null,4,4);});
                check(menuList(a,false).getCount()==3,"Group menu did not include favorites and all groups");
                toggleGroup(t,a,manager,2,"分组乙",false);screenshot(t,out,id+"-group-hidden");
                Field groups=PlaylistManager.class.getDeclaredField("managementGroups");groups.setAccessible(true);PlaylistManager reloaded=new PlaylistManager(a);groups.set(reloaded,fixture);
                check(!reloaded.getGroupSettingsJson().getJSONObject(1).getBoolean("enabled"),"Hidden group not saved");
                toggleGroup(t,a,manager,2,"分组乙",true);
                toggleGroup(t,a,manager,0,"我的收藏",false);
                check(!new PlaylistManager(a).isFavoritesGroupVisible(),"Favorites visibility not saved");
                check(keys.size()==1&&keys.contains(favorite),"Hiding favorites deleted saved records");
                toggleGroup(t,a,manager,0,"我的收藏",true);
                check(ChannelCatalog.GROUPS[0].channels.length==1,"Favorites did not restore");
                check(field(a,"player")==player&&(Integer)field(a,"playRequestId")==request,"Visibility of other groups restarted playback");
                screenshot(t,out,id+"-restored");closeMenu(t,a);
                results.put(new JSONObject().put("theme",id).put("groupHideRestore",true).put("favoritesHideRestore",true).put("persisted",true).put("keptPlayer",true));
            }
            // Hiding favorites during playback maps to its original channel, preserving the decoder.
            Object player=field(a,"player");main(t,()->{set(a,"currentGroupIndex",0);set(a,"currentChannelIndex",0);invoke(a,"openTvUiMenu");menuList(a,true).setSelection(4);menuList(a,true).performItemClick(null,4,4);});
            toggleGroup(t,a,manager,0,"我的收藏",false);
            check("分组甲".equals(ChannelCatalog.GROUPS[(Integer)field(a,"currentGroupIndex")].title)&&field(a,"player")==player,"Hiding playing favorites lost original channel");
            toggleGroup(t,a,manager,0,"我的收藏",true);
            toggleGroup(t,a,manager,2,"分组乙",false);
            JSONArray state=manager.getGroupSettingsJson();boolean rejected=false;
            try{manager.updateGroupStates(new JSONArray().put(new JSONObject().put("id",state.getJSONObject(0).getString("id")).put("enabled",false)));}catch(IOException expected){rejected=true;}
            check(rejected&&manager.getGroupSettingsJson().getJSONObject(0).getBoolean("enabled"),"All channel groups could be hidden");
            toggleGroup(t,a,manager,2,"分组乙",true);
            // Hiding the active ordinary group selects a valid visible channel.
            toggleGroup(t,a,manager,1,"分组甲",false);ready(t,a);
            check("分组乙".equals(ChannelCatalog.GROUPS[(Integer)field(a,"currentGroupIndex")].title),"Hidden active group retained stale cursor");
            toggleGroup(t,a,manager,1,"分组甲",true);
            check(monitor.getHits()==0,"Native channel management opened a webpage");
            row.put("groupVisibility",results).put("playingFavoritesPreserved",true).put("rejectHideLastGroup",true).put("hiddenActiveGroupRecovered",true).put("noManagementActivity",true);
        }finally{
            t.removeMonitor(monitor);closeMenu(t,a);
            android.content.SharedPreferences.Editor edit=prefs.edit();for(String name:new String[]{disabledKey,orderKey,favoritesKey}){Object value=saved.get(name);if(value==null)edit.remove(name);else if(value instanceof Boolean)edit.putBoolean(name,(Boolean)value);else edit.putString(name,(String)value);}edit.commit();
            managed.set(manager,oldManaged);visible.set(manager,oldVisible);
            main(t,()->{custom.set(null,oldCustom);favorites.set(null,oldFavorites);shown.setBoolean(null,oldShown);ChannelCatalog.GROUPS=catalog;keys.clear();keys.addAll(oldKeys);set(a,"currentGroupIndex",g);set(a,"currentChannelIndex",c);set(a,"currentSourceIndex",source);Method start=MainActivity.class.getDeclaredMethod("startChannel",int.class);start.setAccessible(true);start.invoke(a,c);});
            ready(t,a);TvUiPlugin.select(a,selected);t.waitForIdleSync();
        }
    }
    private static void managementEntry(Instrumentation t,MainActivity a,int category,File out,String name)throws Exception {
        managementEntry(t,a,category,out,name,false);
    }
    private static void managementEntry(Instrumentation t,MainActivity a,int category,File out,String name,boolean remote)throws Exception {
        Method device=MainActivity.class.getDeclaredMethod("isTelevisionDevice");device.setAccessible(true);
        Method tvMode=MainActivity.class.getDeclaredMethod("hasTelevisionUi");tvMode.setAccessible(true);
        boolean television=remote||(Boolean)tvMode.invoke(a)||!ManagementActivity.isTablet(a)&&(Boolean)device.invoke(a);
        Instrumentation.ActivityMonitor monitor=television?null:t.addMonitor(ManagementActivity.class.getName(),null,false);
        try {
            main(t,()->{Method mode=MainActivity.class.getDeclaredMethod("setRemoteInputMode",boolean.class);mode.setAccessible(true);mode.invoke(a,!remote);invoke(a,"openTvUiMenu");menuList(a,true).setSelection(category);menuList(a,true).performItemClick(null,category,category);});
            if(category==6){
                if(remote){
                    main(t,()->{ListView options=menuList(a,false);options.requestFocusFromTouch();options.requestFocus();options.setSelection(4);});t.waitForIdleSync();
                    t.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);t.waitForIdleSync();
                }else tapMenuOption(t,a,4);
            }
            if(television){check(((View)field(a,"managementPanel")).isShown(),"TV management did not show QR");main(t,()->invoke(a,"closeManagementPanel"));return;}
            android.app.Activity page=t.waitForMonitorWithTimeout(monitor,6000);check(page!=null,"Web management did not open Activity");
            try {
                check(!((View)field(a,"managementPanel")).isShown(),"Phone management still showed QR");
                check(ManagementActivity.usesSidebar(page)==ManagementActivity.usesSidebar(a),"Management page changed device layout policy");
                Field web=ManagementActivity.class.getDeclaredField("webView");web.setAccessible(true);
                final boolean[] loaded={false};final String[] observed={""};long deadline=SystemClock.elapsedRealtime()+15000;
                while(!loaded[0]&&SystemClock.elapsedRealtime()<deadline){main(t,()->{android.webkit.WebView v=(android.webkit.WebView)web.get(page);observed[0]=v==null?"no WebView":v.getUrl()+" progress="+v.getProgress()+" focus="+page.hasWindowFocus()+" size="+v.getWidth()+"x"+v.getHeight()+" finishing="+page.isFinishing();loaded[0]=v!=null&&v.getUrl()!=null&&v.getUrl().startsWith("http://127.0.0.1:")&&page.hasWindowFocus()&&v.getProgress()==100&&v.getWidth()>0&&v.getHeight()>0;});if(!loaded[0])SystemClock.sleep(50);}
                if(!loaded[0]&&out!=null)screenshot(t,out,name+"-failed");
                check(loaded[0],"Management page did not finish loading: "+observed[0]);SystemClock.sleep(650);
                if(out!=null)screenshot(t,out,name);
            }finally{main(t,()->page.finish());}
            long end=SystemClock.elapsedRealtime()+5000;while(!a.hasWindowFocus()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(40);
            ready(t,a);
        }finally{if(monitor!=null)t.removeMonitor(monitor);}
    }
    static void webManagement(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");JSONArray result=new JSONArray();
        File out=new File(a.getExternalFilesDir(null),"web-management-entry");out.mkdirs();
        try {
            for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}){
                TvUiPlugin.select(a,id);t.waitForIdleSync();
                managementEntry(t,a,6,out,id+"-preferences");
                managementEntry(t,a,6,out,id+"-remote-qr",true);
                result.put(new JSONObject().put("theme",id).put("preferencesWebManagement",true).put("touchAfterRemote",true).put("remoteAfterTouch",true).put("remoteQr",true));
            }
            row.put("webManagementEntries",result).put("usesSidebar",ManagementActivity.usesSidebar(a));
        }finally{TvUiPlugin.select(a,saved);t.waitForIdleSync();}
    }
    static void performance(Instrumentation test,MainActivity a,JSONObject row)throws Exception {
        EpgManager manager=new EpgManager(a);
        Class<?> guideType=Class.forName("xiao.bu.tv.EpgManager$Guide");
        Constructor<?> constructor=guideType.getDeclaredConstructor();constructor.setAccessible(true);
        Object guide=constructor.newInstance();
        Method normalize=EpgManager.class.getDeclaredMethod("normalize",String.class);normalize.setAccessible(true);
        String alias=(String)normalize.invoke(null,"perf");
        Field aliases=guideType.getDeclaredField("channelByAlias");aliases.setAccessible(true);
        ((java.util.Map)aliases.get(guide)).put(alias,"perf");
        Field programs=guideType.getDeclaredField("programsByChannel");programs.setAccessible(true);
        java.util.List<EpgManager.Program> list=new java.util.ArrayList<>();long now=System.currentTimeMillis();
        for(int n=0;n<600;n++){long start=now-(599-n)*60000L-30000;list.add(new EpgManager.Program(start,start+60000,"P"+n,""));}
        ((java.util.Map)programs.get(guide)).put("perf",list);
        Field published=EpgManager.class.getDeclaredField("guide");published.setAccessible(true);published.set(manager,guide);
        Channel channel=new Channel("1","perf","","http://localhost/sample.mp4",null,null,null,"perf");
        EpgManager.CurrentProgram cache=new EpgManager.CurrentProgram();
        check("P599".equals(cache.titleFor(manager,channel,now)),"Current EPG cache differs");
        int iterations=5000;long started=System.nanoTime();int matched=0;
        for(int n=0;n<iterations;n++)for(EpgManager.Program p:manager.programsFor(channel)){
            if(p.startMillis>now)break;if(p.isPlaying(now)){if("P599".equals(p.title))matched++;break;}
        }
        long scanNs=System.nanoTime()-started;check(matched==iterations,"Reference EPG scan differs");
        started=System.nanoTime();for(int n=0;n<iterations;n++)check("P599".equals(cache.titleFor(manager,channel,now)),"Warm EPG cache differs");
        long cacheNs=System.nanoTime()-started;
        final LoadingSpinnerView[] spinner=new LoadingSpinnerView[1];
        Field scheduled=LoadingSpinnerView.class.getDeclaredField("framePosted");scheduled.setAccessible(true);
        main(test,()->{
            spinner[0]=new LoadingSpinnerView(a);android.widget.FrameLayout host=(android.widget.FrameLayout)field(a,"root");
            host.addView(spinner[0],new android.widget.FrameLayout.LayoutParams(40,40));
            check(scheduled.getBoolean(spinner[0]),"Visible spinner did not start");
            spinner[0].setVisibility(View.GONE);check(!scheduled.getBoolean(spinner[0]),"Hidden spinner retained callback");
            spinner[0].setVisibility(View.VISIBLE);check(scheduled.getBoolean(spinner[0]),"Revealed spinner did not restart");
            host.removeView(spinner[0]);check(!scheduled.getBoolean(spinner[0]),"Detached spinner retained callback");
        });
        row.put("uiPerformance",new JSONObject().put("programs",600).put("iterations",iterations)
                .put("referenceScanMs",scanNs/1000000.0).put("cachedLookupMs",cacheNs/1000000.0)
                .put("spinnerHiddenAndDetachedStopped",true));
    }
    private static Object field(MainActivity a,String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    private static void invoke(MainActivity a,String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void screenshot(Instrumentation test,File out,String name)throws Exception {
        SystemClock.sleep(200);Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        if(bitmap!=null){try(FileOutputStream stream=new FileOutputStream(new File(out,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{bitmap.recycle();}}
    }
    private static ListView menuList(MainActivity a,boolean categories)throws Exception {
        Field popup=TvUiMenu.class.getDeclaredField("popup");popup.setAccessible(true);
        android.widget.PopupWindow window=(android.widget.PopupWindow)popup.get(field(a,"tvUiMenu"));
        return (ListView)((android.view.ViewGroup)window.getContentView()).getChildAt(categories?1:0);
    }
    private static void waitSetting(Instrumentation test,MainActivity a,String key,String value)throws Exception {
        long end=SystemClock.elapsedRealtime()+5000;
        while(!value.equals(String.valueOf(field(a,key)))&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);
        check(value.equals(String.valueOf(field(a,key))),"Menu setting did not apply: "+key);test.waitForIdleSync();
    }
    interface Op {void run()throws Exception;}
    private static void main(Instrumentation t,Op op)throws Exception{final Throwable[] e={null};t.runOnMainSync(()->{try{op.run();}catch(Throwable x){e[0]=x;}});if(e[0]!=null)throw new AssertionError(e[0]);t.waitForIdleSync();}
    private static void set(MainActivity a,String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void setting(Instrumentation t,MainActivity a,String key,String member,Object value)throws Exception{
        main(t,()->{Method m=MainActivity.class.getDeclaredMethod("applyTvUiSetting",String.class,Object.class);m.setAccessible(true);m.invoke(a,key,value);});waitSetting(t,a,member,String.valueOf(value));
    }
    private static void click(Instrumentation t,MainActivity a,int category,int option)throws Exception{
        main(t,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(category);menuList(a,true).performItemClick(null,category,category);});
        main(t,()->{ListView choices=menuList(a,false);check(option>=0&&option<choices.getAdapter().getCount(),"Missing menu option "+category+"/"+option);choices.requestFocus();choices.setSelection(option);choices.performItemClick(choices.getSelectedView(),option,option);});
    }
    private static void closeMenu(Instrumentation t,MainActivity a)throws Exception{main(t,()->((TvUiMenu)field(a,"tvUiMenu")).dismiss());}
    private static float drawnTextSize(View view)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(Math.max(1,view.getWidth()),Math.max(1,view.getHeight()),Bitmap.Config.ARGB_8888);
        try{view.draw(new android.graphics.Canvas(bitmap));}finally{bitmap.recycle();}
        for(Class<?> type=view.getClass();type!=null;type=type.getSuperclass())try{Field paint=type.getDeclaredField("p");paint.setAccessible(true);return ((android.graphics.Paint)paint.get(view)).getTextSize();}catch(NoSuchFieldException ignored){}
        throw new AssertionError("Drawing paint missing");
    }
    static void exitCentering(Instrumentation test,MainActivity a,JSONObject row)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected"),savedMode=(String)field(a,"uiScaleMode");
        android.widget.FrameLayout root=(android.widget.FrameLayout)field(a,"root");
        View anchor=new View(a),backdrop=new View(a);backdrop.setBackgroundColor(0xff60676c);
        File out=new File(a.getExternalFilesDir(null),"exit-centering");out.mkdirs();JSONArray checks=new JSONArray();
        Field popupField=TvUiMenu.class.getDeclaredField("popup");popupField.setAccessible(true);
        TvUiMenu menu=(TvUiMenu)field(a,"tvUiMenu");final int[] action={-1};
        try {
            main(test,()->{invoke(a,"closeChannelList");((View)field(a,"channelBar")).setVisibility(View.GONE);
                VideoControlsOverlay controls=(VideoControlsOverlay)field(a,"videoControlsOverlay");if(controls!=null)controls.hide();
                root.addView(backdrop,new android.widget.FrameLayout.LayoutParams(-1,-1));
                root.addView(anchor,new android.widget.FrameLayout.LayoutParams(root.getWidth()+400,root.getHeight()+200));});
            for(String preset:new String[]{"auto","small","large","extra_extra_large"}){
            sizeMode(test,a,preset);
            for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}) {
                TvUiPlugin.select(a,id);test.waitForIdleSync();
                for(boolean oversized:"auto".equals(preset)?new boolean[]{false,true}:new boolean[]{false}) {
                    main(test,()->{
                        if(!oversized)invoke(a,"showTvUiExit");
                        else menu.show(anchor,"exit",new android.os.Bundle(),new xiao.bu.tv.skin.TvUi.Events(){
                            public android.os.Bundle options(int n){return new android.os.Bundle();}
                            public void action(int n,int value){action[0]=value;menu.dismiss();}
                            public void dismiss(){menu.dismiss();}
                        });
                    });test.waitForIdleSync();SystemClock.sleep(100);
                    android.widget.PopupWindow popup=(android.widget.PopupWindow)popupField.get(menu);View content=popup.getContentView();
                    int[] location=new int[2],windowLocation=new int[2];content.getLocationOnScreen(location);
                    View window=a.getWindow().getDecorView();window.getLocationOnScreen(windowLocation);
                    double dx=location[0]+content.getWidth()/2d-windowLocation[0]-window.getWidth()/2d;
                    double dy=location[1]+content.getHeight()/2d-windowLocation[1]-window.getHeight()/2d;
                    check(Math.abs(dx)<=1 && Math.abs(dy)<=1,"Exit shifted: "+id+" oversized="+oversized+" dx="+dx+" dy="+dy);
                    JSONObject sample=new JSONObject().put("theme",id).put("preset",preset).put("oversizedAnchor",oversized).put("dx",dx).put("dy",dy)
                            .put("popupWidth",content.getWidth()).put("popupHeight",content.getHeight());checks.put(sample);
                    final float[] dialogFont={0};
                    main(test,()->{
                        float size=drawnTextSize(content);Method scale=content.getClass().getDeclaredMethod("dialogScale");scale.setAccessible(true);dialogFont[0]=size*(Float)scale.invoke(content);
                        Method width=content.getClass().getDeclaredMethod("buttonWidth");width.setAccessible(true);
                        android.graphics.Paint paint=new android.graphics.Paint();paint.setTextSize(size);
                        check(paint.measureText("功能设置")<=(Float)width.invoke(content),"Exit button text clipped "+id+"/"+preset);
                        check(paint.descent()-paint.ascent()<=("ui4".equals(id)?54:60),"Exit text exceeds button height "+id+"/"+preset);
                        sample.put("exitTextPx",dialogFont[0]);
                    });
                    if(!oversized){
                        screenshot(test,out,"auto".equals(preset)?id:id+"-"+preset);test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT);
                        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);test.waitForIdleSync();
                        check(menu.visible() && ((android.widget.PopupWindow)popupField.get(menu)).getContentView()!=content,"Settings button failed");
                        SystemClock.sleep(120);test.waitForIdleSync();
                        main(test,()->{
                            View right=((android.widget.PopupWindow)popupField.get(menu)).getContentView();
                            ListView categories=(ListView)((android.view.ViewGroup)right).getChildAt(1);View first=categories.getChildAt(0);check(first!=null,"Menu text reference missing");
                            Field scale=right.getClass().getDeclaredField("d");scale.setAccessible(true);float menuFont=drawnTextSize(first)*scale.getFloat(right);
                            check(Math.abs(menuFont-dialogFont[0])<.6f,"Exit/menu fonts differ "+id+"/"+preset+": "+dialogFont[0]+" vs "+menuFont);
                            sample.put("menuTextPx",menuFont);
                        });
                        if("ui2".equals(id)) {
                            View rightMenu=((android.widget.PopupWindow)popupField.get(menu)).getContentView();
                            int expected=root.getHeight()-2*Math.round(8*TvUiChrome.scale(root));
                            check(rightMenu.getHeight()==expected,"Second-theme menu height differs: "+rightMenu.getHeight());
                            row.put("ui2MenuHeight",rightMenu.getHeight()).put("ui2MenuInset",Math.round(8*TvUiChrome.scale(root)));
                            screenshot(test,out,"ui2-menu");
                        }
                        main(test,menu::dismiss);
                    } else {
                        action[0]=-1;float d=.76f*Math.min(content.getWidth()/960f,content.getHeight()/540f);
                        float x=location[0]+(content.getWidth()-960*d)/2+("ui4".equals(id)?570:597)*d;
                        float y=location[1]+(content.getHeight()-540*d)/2+("ui4".equals(id)?410:329)*d;
                        long now=SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,0,x,y,0);
                        android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+50,1,x,y,0);
                        try{test.sendPointerSync(down);test.sendPointerSync(up);}finally{down.recycle();up.recycle();}
                        test.waitForIdleSync();check(action[0]==1&&!menu.visible(),"Exit touch target moved: "+id);
                    }
                    check(!a.isFinishing(),"Test unexpectedly closed Activity");
                }
                main(test,()->invoke(a,"showTvUiExit"));closeMenu(test,a);
            }
            }
            row.put("exitCentering",checks).put("exitInteractionsPassed",true).put("exitTypographyPassed",true);
        } finally {
            main(test,()->{menu.dismiss();root.removeView(anchor);root.removeView(backdrop);});
            sizeMode(test,a,savedMode);TvUiPlugin.select(a,saved);test.waitForIdleSync();
        }
    }
    private static int fullRows(ListView list){int count=0;for(int i=0;i<list.getChildCount();i++){View v=list.getChildAt(i);if(v.getTop()>=list.getPaddingTop()&&v.getBottom()<=list.getHeight()-list.getPaddingBottom())count++;}return count;}
    private static final String[][] SETTINGS={{"showDateTime","showDateTime"},{"showNetworkSpeed","showNetworkSpeed"},{"showDebugInfo","showDebugInfo"},{"showColorDebugInfo","showColorDebugInfo"},{"showLoadTiming","showLoadTiming"},{"epgEnabled","epgEnabled"},{"autoStart","autoStart"},{"reverseKeys","reverseUpDown"},{"autoSwitchSourceSeconds","autoSwitchSourceSeconds"},{"decodeMode","decodeMode"},{"videoScaleMode","videoScaleMode"}};
    private static Object[] save(MainActivity a)throws Exception{Object[] values=new Object[SETTINGS.length];for(int i=0;i<values.length;i++)values[i]=field(a,SETTINGS[i][1]);return values;}
    private static void restore(Instrumentation t,MainActivity a,Object[] saved)throws Exception{for(int i=0;i<saved.length;i++)setting(t,a,SETTINGS[i][0],SETTINGS[i][1],saved[i]);}
    private static void cleanPicture(Instrumentation t,MainActivity a)throws Exception{
        setting(t,a,"showDateTime","showDateTime",true);setting(t,a,"showNetworkSpeed","showNetworkSpeed",true);
        setting(t,a,"showDebugInfo","showDebugInfo",false);setting(t,a,"showColorDebugInfo","showColorDebugInfo",false);setting(t,a,"showLoadTiming","showLoadTiming",false);
        setting(t,a,"epgEnabled","epgEnabled",true);
    }
    private static void seed(Instrumentation t,MainActivity a)throws Exception{main(t,()->{
        Channel original=ChannelCatalog.GROUPS[(Integer)field(a,"currentGroupIndex")].channels[(Integer)field(a,"currentChannelIndex")];String url=original.url;
        String[] names={"CCTV-1 综合","CCTV-2 财经","CCTV-3 综艺","CCTV-4 中文国际","CCTV-5 体育","CCTV-5+ 体育赛事","CCTV-6 电影","CCTV-7 国防军事","CCTV-8 电视剧","CCTV-9 纪录","CCTV-10 科教","CCTV-11 戏曲","CCTV-12 社会与法","CCTV-13 新闻","CCTV-14 少儿","CCTV-15 音乐","CCTV-16 奥林匹克","CCTV-17 农业农村","4K 超高清","测试长频道名称的省略与焦点"};
        Channel[] channels=new Channel[names.length];for(int i=0;i<channels.length;i++)channels[i]=new Channel(String.valueOf(i+1),names[i],"",new String[]{url,url+(url.contains("?")?"&":"?")+"ui-line=2"},null,null,null,null);
        String[] groups={"央视频道","卫视频道","地方频道","超清频道","体育赛事","影视轮播","纪录科教","少儿动漫","新闻资讯","海外频道","我的分组"};ChannelCatalog.Group[] catalog=new ChannelCatalog.Group[groups.length];for(int i=0;i<groups.length;i++)catalog[i]=new ChannelCatalog.Group(groups[i],ChannelCatalog.SOURCE_CUSTOM,channels);
        ChannelCatalog.GROUPS=catalog;set(a,"currentGroupIndex",0);set(a,"currentChannelIndex",0);set(a,"currentSourceIndex",0);
        Method bar=MainActivity.class.getDeclaredMethod("showChannelBar",String.class,String.class);bar.setAccessible(true);bar.invoke(a,names[0],"视频播放中");
    });}
    static void visualBaseline(Instrumentation t,MainActivity a,JSONObject row)throws Exception{
        ChannelCatalog.Group[] catalog=ChannelCatalog.GROUPS;Object[] saved=save(a);String selected=new JSONObject(TvUiPlugin.status(a)).getString("selected");
        File out=new File(a.getExternalFilesDir(null),"tv-ui-before");out.mkdirs();JSONArray result=new JSONArray();
        View backdrop=new View(a);backdrop.setBackgroundColor(0xff60676c);
        try{cleanPicture(t,a);seed(t,a);main(t,()->((android.widget.FrameLayout)field(a,"root")).addView(backdrop,1,new android.widget.FrameLayout.LayoutParams(-1,-1)));
            for(String id:TvUiPlugin.IDS){TvUiPlugin.select(a,id);t.waitForIdleSync();main(t,()->invoke(a,"openChannelList"));screenshot(t,out,id+"-channels");JSONObject r=new JSONObject().put("id",id);
                main(t,()->{View panel=(View)field(a,"channelListPanel");r.put("fullRows",fullRows((ListView)field(a,"channelList"))).put("panelWidth",panel.getWidth());invoke(a,"showVideoControls");});screenshot(t,out,id+"-layers");
                main(t,()->{invoke(a,"closeChannelList");VideoControlsOverlay controls=(VideoControlsOverlay)field(a,"videoControlsOverlay");if(controls!=null)controls.hide();invoke(a,"refreshTvUiCard");((View)field(a,"channelBar")).setVisibility(View.VISIBLE);});screenshot(t,out,id+"-card");
                if(!"builtin".equals(id)){main(t,()->{((View)field(a,"channelBar")).setVisibility(View.GONE);invoke(a,"showTvUiExit");});screenshot(t,out,id+"-exit");closeMenu(t,a);}
                result.put(r);
            }row.put("tvUiBaseline",result);
        }finally{main(t,()->((android.view.ViewGroup)backdrop.getParent()).removeView(backdrop));ChannelCatalog.GROUPS=catalog;main(t,()->{set(a,"currentGroupIndex",0);set(a,"currentChannelIndex",0);});restore(t,a,saved);TvUiPlugin.select(a,selected);t.waitForIdleSync();}
    }
    static void run(Instrumentation test,MainActivity a,JSONObject row)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");Object[] savedSettings=save(a);ChannelCatalog.Group[] catalog=ChannelCatalog.GROUPS;
        int savedGroup=(Integer)field(a,"currentGroupIndex"),savedChannel=(Integer)field(a,"currentChannelIndex"),savedSource=(Integer)field(a,"currentSourceIndex");
        IMediaPlayer player=(IMediaPlayer)field(a,"player");DirectVideoView surface=(DirectVideoView)field(a,"videoView");long startPosition=player.getCurrentPosition();
        android.view.Surface output=surface.getSurfaceView().getHolder().getSurface();JSONArray samples=new JSONArray();File out=new File(a.getExternalFilesDir(null),"tv-ui");out.mkdirs();
        View neutralBackdrop=new View(a);neutralBackdrop.setBackgroundColor(0xff60676c);
        try {
            cleanPicture(test,a);seed(test,a);
            main(test,()->((android.widget.FrameLayout)field(a,"root")).addView(neutralBackdrop,1,new android.widget.FrameLayout.LayoutParams(-1,-1)));
            long epgEnd=SystemClock.elapsedRealtime()+10000;
            while(((EpgManager)field(a,"epgManager")).programsFor(ChannelCatalog.GROUPS[0].channels[0]).isEmpty()&&SystemClock.elapsedRealtime()<epgEnd)SystemClock.sleep(100);
            for(String id:TvUiPlugin.IDS){
                long started=SystemClock.elapsedRealtime();TvUiPlugin.select(a,id);test.waitForIdleSync();check(TvUiPlugin.provider()!=null,"No JAR renderer for "+id);
                JSONObject sample=new JSONObject().put("id",id).put("switchMs",SystemClock.elapsedRealtime()-started);
                main(test,()->{
                    invoke(a,"closeChannelList");invoke(a,"showVideoControls");VideoControlsOverlay controls=(VideoControlsOverlay)field(a,"videoControlsOverlay");
                    if(player.getDuration()>0)check(controls!=null&&controls.visible(),"VOD controls not shown");
                    invoke(a,"openChannelList");if(controls!=null)check(!controls.visible(),"Controls cover channel drawer");
                    Method show=MainActivity.class.getDeclaredMethod("showVideoControls");show.setAccessible(true);((View)field(a,"root")).post(()->{try{show.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});
                });
                main(test,()->{
                    VideoControlsOverlay controls=(VideoControlsOverlay)field(a,"videoControlsOverlay");check(controls==null||!controls.visible(),"Delayed callback raised controls over channels");
                    ListView list=(ListView)field(a,"channelList");android.widget.ListAdapter adapter=list.getAdapter();View recycled=null;long begin=System.nanoTime();for(int n=0;n<200;n++)recycled=adapter.getView(n%adapter.getCount(),recycled,list);
                    View panel=(View)field(a,"channelListPanel");sample.put("recycle200Ms",(System.nanoTime()-begin)/1000000d).put("actualHeightPx",recycled.getLayoutParams().height).put("fullRows",fullRows(list)).put("panelWidth",panel.getWidth()).put("screenWidth",((View)field(a,"root")).getWidth());
                    if(!"builtin".equals(id)){int expectedRows="ui2".equals(id)?6:"ui3".equals(id)?7:"ui4".equals(id)?9:10;check(fullRows(list)>=expectedRows,"Channel rows differ from reference");check(panel.getWidth()<((View)field(a,"root")).getWidth()*.425f,"Drawer too wide");
                        if("ui2".equals(id)||"ui3".equals(id)||"ui4".equals(id)){
                            float d=TvUiChrome.scale((View)field(a,"root"));int expectedWidth="ui2".equals(id)?397:"ui3".equals(id)?376:406,groupWidth="ui2".equals(id)?134:"ui3".equals(id)?150:121,rowHeight="ui2".equals(id)?70:"ui3".equals(id)?65:60;
                            check(Math.abs(panel.getWidth()-expectedWidth*d)<=1,"Reference panel width mismatch");check(Math.abs(((ListView)field(a,"groupList")).getWidth()-groupWidth*d)<=1,"Reference group split mismatch");check(Math.abs(recycled.getLayoutParams().height-rowHeight*d)<=1,"Reference row pitch mismatch");
                            sample.put("referenceGeometryPassed",true).put("groupWidth",((ListView)field(a,"groupList")).getWidth()).put("panelLeft",panel.getLeft()).put("panelTop",panel.getTop()).put("panelHeight",panel.getHeight());
                        }
                        android.widget.TextView clock=(android.widget.TextView)field(a,"videoClock");check(clock.getText().toString().matches("[0-9]{2}:[0-9]{2}:[0-9]{2}"),"Wrong compact clock");check(clock.isShown()&&((View)field(a,"networkSpeedOverlay")).isShown(),"HUD not visible");}
                });screenshot(test,out,id+"-channels");screenshot(test,out,id+"-layers");
                main(test,()->{invoke(a,"closeChannelList");Method bar=MainActivity.class.getDeclaredMethod("showChannelBar",String.class,String.class);bar.setAccessible(true);bar.invoke(a,ChannelCatalog.GROUPS[0].channels[0].name,"视频播放中");invoke(a,"refreshTvUiCard");((View)field(a,"channelBar")).setVisibility(View.VISIBLE);});screenshot(test,out,id+"-card");main(test,()->{View card=(View)field(a,"channelBar");check(card.getWidth()>0&&card.getHeight()>0,"Card did not complete layout");sample.put("cardLeft",card.getLeft()).put("cardTop",card.getTop()).put("cardWidth",card.getWidth()).put("cardHeight",card.getHeight());});
                if(!"builtin".equals(id)){
                    int display=5,clockOption=0,speedOption=1;
                    click(test,a,display,clockOption);waitSetting(test,a,"showDateTime","false");main(test,()->check(((View)field(a,"videoClock")).getVisibility()==View.GONE,"Clock setting did not hide view"));click(test,a,display,clockOption);waitSetting(test,a,"showDateTime","true");
                    {
                    click(test,a,display,speedOption);waitSetting(test,a,"showNetworkSpeed","false");main(test,()->check(((View)field(a,"networkSpeedOverlay")).getVisibility()==View.GONE,"Network setting did not hide view"));click(test,a,display,speedOption);waitSetting(test,a,"showNetworkSpeed","true");
                    click(test,a,display,2);waitSetting(test,a,"showDebugInfo","true");main(test,()->check(((View)field(a,"debugInfoOverlay")).isShown(),"Debug menu is decorative"));click(test,a,display,2);waitSetting(test,a,"showDebugInfo","false");
                    }
                    int ratio=1;click(test,a,ratio,3);waitSetting(test,a,"videoScaleMode","4:3");check(Math.abs((float)surface.getWidth()/surface.getHeight()-4f/3f)<.02f,"Ratio did not change video");screenshot(test,out,id+"-ratio");
                    test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);test.waitForIdleSync();check(((TvUiMenu)field(a,"tvUiMenu")).visible(),"Back failed to return category");test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);test.waitForIdleSync();
                    click(test,a,ratio,1);waitSetting(test,a,"videoScaleMode","fit");closeMenu(test,a);
                    {
                        click(test,a,3,2);waitSetting(test,a,"autoSwitchSourceSeconds","10");check(a.getSharedPreferences(MainActivity.PREFERENCES,0).getInt("auto_switch_source_seconds",-1)==10,"Timeout not saved");click(test,a,3,0);waitSetting(test,a,"autoSwitchSourceSeconds","0");closeMenu(test,a);
                        main(test,()->{invoke(a,"openTvUiMenu");ListView categories=menuList(a,true);categories.setSelection(6);categories.performItemClick(null,6,6);});
                        check(!((View)field(a,"managementPanel")).isShown(),"Preferences unexpectedly opened QR");
                        check(((TvUiMenu)field(a,"tvUiMenu")).visible(),"Preference menu dismissed");
                        boolean reverse=(Boolean)field(a,"reverseUpDown");click(test,a,6,1);waitSetting(test,a,"reverseUpDown",String.valueOf(!reverse));
                        click(test,a,6,1);waitSetting(test,a,"reverseUpDown",String.valueOf(reverse));
                        main(test,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(6);menuList(a,true).performItemClick(null,6,6);});screenshot(test,out,id+"-preferences");closeMenu(test,a);
                    }
                    main(test,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(0);});screenshot(test,out,id+"-menu");main(test,()->sample.put("menuOptionWidth",menuList(a,false).getWidth()).put("menuCategoryWidth",menuList(a,true).getWidth()));closeMenu(test,a);
                    main(test,()->invoke(a,"showTvUiExit"));screenshot(test,out,id+"-exit");closeMenu(test,a);
                    main(test,()->{TvUiMenu menu=(TvUiMenu)field(a,"tvUiMenu");menu.show((View)field(a,"root"),"guide",new android.os.Bundle(),new xiao.bu.tv.skin.TvUi.Events(){public android.os.Bundle options(int n){return new android.os.Bundle();}public void action(int n,int v){}public void dismiss(){menu.dismiss();}});});screenshot(test,out,id+"-guide");test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);test.waitForIdleSync();
                    sample.put("menuSettingsApplied",true).put("ratio43Applied",true).put("layerPriority",true);
                }
                check(field(a,"player")==player,"UI/config restarted decoder unexpectedly");check(surface.getSurfaceView().getHolder().getSurface()==output&&output.isValid(),"UI recreated Surface");samples.put(sample);
            }
            JSONArray warm=new JSONArray();for(int n=0;n<12;n++){long begin=SystemClock.elapsedRealtime();TvUiPlugin.select(a,TvUiPlugin.IDS[n%TvUiPlugin.IDS.length]);test.waitForIdleSync();warm.put(SystemClock.elapsedRealtime()-begin);check(field(a,"player")==player,"Warm UI switch restarted playback");}
            check(player.getCurrentPosition()>startPosition,"UI stopped playback");row.put("tvUi",samples).put("tvUiWarmSwitchMs",warm).put("tvUiPlaybackAdvancedMs",player.getCurrentPosition()-startPosition);
            // Actual decoder and source changes are expected to replace the decoder, but keep the Surface.
            JSONArray actions=new JSONArray();for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}){
                TvUiPlugin.select(a,id);test.waitForIdleSync();int decode=2;
                click(test,a,decode,2);waitSetting(test,a,"decodeMode","software");ready(test,a);check(((tv.danmaku.ijk.media.player.IjkMediaPlayer)field(a,"player")).getVideoDecoder()==tv.danmaku.ijk.media.player.IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC,"Software selection not real");
                click(test,a,decode,1);waitSetting(test,a,"decodeMode","hardware");ready(test,a);check(((tv.danmaku.ijk.media.player.IjkMediaPlayer)field(a,"player")).getVideoDecoder()==tv.danmaku.ijk.media.player.IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC,"Hardware selection not real");
                click(test,a,0,1);waitSetting(test,a,"currentSourceIndex","1");ready(test,a);click(test,a,0,0);waitSetting(test,a,"currentSourceIndex","0");ready(test,a);actions.put(new JSONObject().put("id",id).put("realSoftwareHardware",true).put("realSourceSwitch",true));
            }row.put("tvUiActions",actions);
            main(test,()->neutralBackdrop.setVisibility(View.GONE));
            phoneGestures(test,a,row,out);
            TvUiPlugin.select(a,"ui2");test.waitForIdleSync();main(test,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(8);menuList(a,true).performItemClick(null,8,8);});screenshot(test,out,"ui2-version-idle");
            AutoUpdater updater=(AutoUpdater)field(a,"autoUpdater");click(test,a,7,1);long end=SystemClock.elapsedRealtime()+65000;while(("idle".equals(updater.stateJson().optString("state"))||updater.stateJson().optBoolean("checking"))&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);test.waitForIdleSync();
            check(!updater.stateJson().optBoolean("checking"),"Update check never completed");row.put("tvUiLiteUpdate",updater.stateJson());screenshot(test,out,"ui2-version-result");closeMenu(test,a);
        }finally{closeMenu(test,a);ChannelCatalog.GROUPS=catalog;main(test,()->{if(neutralBackdrop.getParent()!=null)((android.view.ViewGroup)neutralBackdrop.getParent()).removeView(neutralBackdrop);set(a,"currentGroupIndex",savedGroup);set(a,"currentChannelIndex",savedChannel);set(a,"currentSourceIndex",savedSource);});restore(test,a,savedSettings);TvUiPlugin.select(a,saved);test.waitForIdleSync();
            main(test,()->{Method m=MainActivity.class.getDeclaredMethod("startChannel",int.class);m.setAccessible(true);m.invoke(a,savedChannel);});ready(test,a);}
        groupSorting(test,a,row);
    }
    static void frameRateTicker(Instrumentation t,MainActivity a,JSONObject result)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");
        boolean debug=(Boolean)field(a,"showDebugInfo");JSONArray themes=new JSONArray();
        try {
            main(t,()->{set(a,"showDebugInfo",true);invoke(a,"applyDebugInfoVisibility");});
            for(String id:new String[]{"ui2","builtin","ui3","ui4","ui5","ui6"}) {
                TvUiPlugin.select(a,id);t.waitForIdleSync();SystemClock.sleep(150);
                Object last=field(a,"latestPlaybackDebugStats");long cpu=(Long)field(a,"lastCpuSampleAt");int samples=0,cpuSamples=0;
                long end=SystemClock.elapsedRealtime()+3500;JSONArray timestamps=new JSONArray(),cpuTimes=new JSONArray();
                // No refresh/collect calls in this interval: only the production ticker may update it.
                while(SystemClock.elapsedRealtime()<end) {
                    final Object[] snapshot={null};final long[] stamp={0};
                    main(t,()->{snapshot[0]=field(a,"latestPlaybackDebugStats");stamp[0]=(Long)field(a,"lastCpuSampleAt");});
                    if(snapshot[0]!=last){last=snapshot[0];samples++;timestamps.put(SystemClock.elapsedRealtime());}
                    if(stamp[0]!=cpu){cpu=stamp[0];cpuSamples++;cpuTimes.put(cpu);}
                    SystemClock.sleep(80);
                }
                JSONObject theme=new JSONObject().put("theme",id).put("automaticSamples",samples).put("cpuSamples",cpuSamples).put("sampleTimes",timestamps).put("cpuTimes",cpuTimes);
                themes.put(theme);result.put("fpsTicker",themes);
                check(samples>=3,"Debug ticker stopped after theme switch: "+theme);
                check(cpuSamples>=2,"CPU is not sampled every second: "+theme);
                for(int n=1;n<cpuTimes.length();n++)check(cpuTimes.getLong(n)-cpuTimes.getLong(n-1)<1600,"CPU sample interval exceeded one second: "+theme);
                Field fps=last.getClass().getDeclaredField("frameRate");fps.setAccessible(true);
                check(fps.getFloat(last)>1,"Displayed live FPS stayed zero: "+id);
            }
        }finally{main(t,()->set(a,"showDebugInfo",debug));TvUiPlugin.select(a,saved);t.waitForIdleSync();}
    }

    static void frameRateCards(Instrumentation t,MainActivity a,JSONObject result)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");JSONArray cards=new JSONArray();
        try {
            for(String id:TvUiPlugin.IDS){
                TvUiPlugin.select(a,id);t.waitForIdleSync();
                main(t,()->{
                    ((View)field(a,"channelBar")).setVisibility(View.VISIBLE);invoke(a,"refreshVideoInfo");
                    Object stats=field(a,"latestPlaybackDebugStats");
                    Method fps=MainActivity.class.getDeclaredMethod("formatDebugFrameRate",stats.getClass());fps.setAccessible(true);
                    String label=(String)fps.invoke(null,stats);
                    String text=((android.widget.TextView)field(a,"videoInfo")).getText().toString();
                    check(text.contains(label),"Theme did not receive measured/source FPS: "+id+" "+text);
                    cards.put(new JSONObject().put("theme",id).put("fps",label).put("text",text));
                });
            }
            result.put("themeFpsCards",cards);
        }finally{TvUiPlugin.select(a,saved);t.waitForIdleSync();}
    }

    private static void foreground(Instrumentation t,MainActivity a)throws Exception {
        if(android.os.Build.VERSION.SDK_INT>=21){
            // A test initiated return must not depend on MIUI's background-start permission.
            try(android.os.ParcelFileDescriptor fd=t.getUiAutomation().executeShellCommand("am start -n "+a.getPackageName()+"/xiao.bu.tv.MainActivity");FileInputStream input=new FileInputStream(fd.getFileDescriptor())){
                byte[] bytes=new byte[1024];while(input.read(bytes)>=0){}
            }
        } else t.getTargetContext().startActivity(new android.content.Intent(t.getTargetContext(),MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private static void markBackground(String url,String stage)throws Exception {
        java.net.URL source=new java.net.URL(url);
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(source.getProtocol(),source.getHost(),source.getPort(),"/background-marker?stage="+stage).openConnection();
        connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
        try {connection.getResponseCode();}finally{connection.disconnect();}
    }
    static void backgroundSmoke(Instrumentation t,MainActivity a,JSONObject result,boolean system,boolean paused)throws Exception {
        if(system) {
            main(t,()->{
                Channel channel=(Channel)field(a,"activePlayerChannel");String url=(String)field(a,"activePlayerStreamUrl");
                Method m=MainActivity.class.getDeclaredMethod("startAndroidMediaPlayer",Channel.class,String.class,boolean.class,boolean.class);
                m.setAccessible(true);m.invoke(a,channel,url,false,true);
            });ready(t,a);
        }
        SystemClock.sleep(3500L);
        if(paused)main(t,()->((IMediaPlayer)field(a,"player")).pause());
        IMediaPlayer before=(IMediaPlayer)field(a,"player");Object proxy=field(a,"proxy");
        String url=(String)field(a,"activePlayerStreamUrl");int request=(Integer)field(a,"playRequestId");
        boolean vod=before.getDuration()>0L;long position=before.getCurrentPosition();
        long keyTime=SystemClock.uptimeMillis();
        t.getUiAutomation().injectInputEvent(new android.view.KeyEvent(keyTime,keyTime,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_HOME,0),true);
        t.getUiAutomation().injectInputEvent(new android.view.KeyEvent(keyTime,keyTime+1,android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_HOME,0),true);
        long until=SystemClock.elapsedRealtime()+6000;
        while(!(Boolean)field(a,"backgroundPlaybackSuspended")&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(30);
        t.waitForIdleSync();
        check((Boolean)field(a,"backgroundPlaybackSuspended"),"App did not suspend on HOME");
        check(field(a,"player")==null,"Decoder still active in background");
        check(field(a,"proxy")==proxy,"Background discarded proxy state");
        check(url.equals(field(a,"backgroundStreamUrl")),"Resolved URL was discarded");
        if(proxy!=null) {
            Method open=HlsProxyServer.class.getDeclaredMethod("openUpstreamConnection",String.class);open.setAccessible(true);
            boolean blocked=false;try {open.invoke(proxy,url);}catch(InvocationTargetException expected){blocked=expected.getCause() instanceof IOException;}
            check(blocked,"HLS upstream accepts downloads while backgrounded");
        }
        SystemClock.sleep(500);markBackground(url,"stopped");
        SystemClock.sleep(2500);markBackground(url,"resume");
        check(field(a,"player")==null,"Background callback restarted decoder");
        foreground(t,a);
        if(paused){long preparedEnd=SystemClock.elapsedRealtime()+15000;while(!(Boolean)field(a,"prepared")&&SystemClock.elapsedRealtime()<preparedEnd)SystemClock.sleep(30);check((Boolean)field(a,"prepared"),"Paused decoder not prepared");}else ready(t,a);
        IMediaPlayer after=(IMediaPlayer)field(a,"player");
        check(after!=before,"Foreground reused stopped decoder");
        check((after instanceof tv.danmaku.ijk.media.player.AndroidMediaPlayer)==system,"Foreground changed decoder engine");
        check(!(Boolean)field(a,"backgroundPlaybackSuspended"),"Still suspended after foreground");
        check(field(a,"proxy")==proxy&&url.equals(field(a,"activePlayerStreamUrl")),"Foreground discarded resolved state");
        check((Integer)field(a,"playRequestId")==request,"Foreground re-resolved channel");
        JSONArray clocks=new JSONArray();long resumedAt=SystemClock.elapsedRealtime();
        while(SystemClock.elapsedRealtime()-resumedAt<4000){clocks.put(new JSONObject().put("ms",SystemClock.elapsedRealtime()-resumedAt).put("position",after.getCurrentPosition()).put("playing",after.isPlaying()));SystemClock.sleep(200);}
        result.put("backgroundResumeClocks",clocks).put("backgroundSavedPosition",position);
        if(vod) check(after.getCurrentPosition()>=Math.max(0,position-(paused?2200:1000)),"VOD clock restarted after background: "+clocks);
        if(paused){
            check(!after.isPlaying(),"Foreground discarded paused state");long stopped=after.getCurrentPosition();SystemClock.sleep(400);check(Math.abs(after.getCurrentPosition()-stopped)<250,"Paused clock kept advancing");
            main(t,after::start);ready(t,a);long untilAdvance=SystemClock.elapsedRealtime()+7000;while(after.getCurrentPosition()<stopped+500&&SystemClock.elapsedRealtime()<untilAdvance)SystemClock.sleep(40);check(after.getCurrentPosition()>=stopped+500,"Paused player could not resume");
            result.put("backgroundPausedRestored",true);
        }
        result.put("backgroundPlayback",new JSONObject().put("releasedDecoder",true).put("blockedHlsDownloads",true)
                .put("retainedProxy",true).put("retainedResolvedUrl",true).put("resumed",true)
                .put("systemPlayer",system).put("vod",vod).put("beforePosition",position).put("afterPosition",after.getCurrentPosition()));
    }

    static void radioPreferences(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        String theme=new JSONObject(TvUiPlugin.status(a)).getString("selected"),selected=new JSONObject(RadioUiPlugin.status(a)).getString("selected");
        Object player=field(a,"player");AudioArtworkView artwork=(AudioArtworkView)field(a,"audioArtwork");
        JSONArray results=new JSONArray();File out=new File(a.getExternalFilesDir(null),"radio-preferences");out.mkdirs();
        try{
            for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}){
                TvUiPlugin.select(a,id);t.waitForIdleSync();
                main(t,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(6);menuList(a,true).performItemClick(null,6,6);});
                main(t,()->check(menuList(a,false).getCount()==9&&"唱片界面".equals(menuList(a,false).getAdapter().getItem(3)),"Record picker entry missing "+id));
                screenshot(t,out,id+"-preferences");
                main(t,()->{ListView list=menuList(a,false);list.performItemClick(null,3,3);});
                long until=SystemClock.elapsedRealtime()+10000;
                while(SystemClock.elapsedRealtime()<until){final boolean[] ready={false};main(t,()->ready[0]=menuList(a,true).getCount()==2&&menuList(a,false).getCount()>=2);if(ready[0])break;SystemClock.sleep(30);}
                main(t,()->check(menuList(a,false).getCount()>=2,"Record choices did not load "+id));
                main(t,()->menuList(a,false).performItemClick(null,0,0));waitRadioStyle(t,a,artwork,"classic");
                // Rapid second clicks while an apply is pending must not queue another selection.
                main(t,()->{ListView list=menuList(a,false);list.performItemClick(null,1,1);list.performItemClick(null,0,0);});waitRadioStyle(t,a,artwork,"optical");
                main(t,()->{
                    TvUiMenu menu=(TvUiMenu)field(a,"tvUiMenu");android.os.Bundle choices=(android.os.Bundle)member(menu.contentView(),"choices");
                    check(choices.getInt("selected")==1,"Selected circle did not follow applied record style "+id);
                    check(field(a,"player")==player&&((IMediaPlayer)player).isPlaying(),"Record picker restarted playback "+id);
                });screenshot(t,out,id+"-selected");closeMenu(t,a);
                results.put(new JSONObject().put("theme",id).put("entry",true).put("classic",true).put("optical",true).put("rapidClickGuard",true).put("persisted",true));
            }
            row.put("nativeRecordMenus",results).put("playerPreserved",true);
        }finally{closeMenu(t,a);RadioUiPlugin.configure(a,new JSONObject().put("id",selected));TvUiPlugin.select(a,theme);t.waitForIdleSync();}
    }
    private static void waitRadioStyle(Instrumentation t,MainActivity a,AudioArtworkView artwork,String style)throws Exception {
        long until=SystemClock.elapsedRealtime()+30000;final boolean[] ready={false};
        while(SystemClock.elapsedRealtime()<until){
            main(t,()->{
                TvUiMenu menu=(TvUiMenu)field(a,"tvUiMenu");View content=menu.contentView();
                if(content==null)return;
                java.lang.reflect.Field events=content.getClass().getDeclaredField("events");events.setAccessible(true);Object model=events.get(content);
                ready[0]=!(Boolean)member(model,"applying")&&style.equals(RadioUiPlugin.style(a))&&style.equals(member(artwork,"uiStyle"));
            });if(ready[0])return;SystemClock.sleep(30);
        }
        throw new AssertionError("Native record style did not apply: "+style+" "+RadioUiPlugin.status(a));
    }
    static void menuSmoke(Instrumentation t,MainActivity a,JSONObject result)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");
        Object player=field(a,"player");DirectVideoView video=(DirectVideoView)field(a,"videoView");
        android.view.Surface surface=video.getVideoSurface();boolean boot=(Boolean)field(a,"autoStart");
        JSONArray themes=new JSONArray();File out=new File(a.getExternalFilesDir(null),"tv-ui-shared");out.mkdirs();
        java.util.concurrent.atomic.AtomicReference<String> updateToast=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}){
                TvUiPlugin.select(a,id);t.waitForIdleSync();
                check(TvUiPlugin.provider() instanceof xiao.bu.tv.skin.SharedTvUi,"Theme did not use host renderer: "+id);
                if(!(Boolean)field(a,"autoStart")){click(t,a,6,0);waitSetting(t,a,"autoStart","true");}
                main(t,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(6);menuList(a,true).performItemClick(null,6,6);});
                main(t,()->{
                    check(menuList(a,true).getAdapter().getCount()==8,"Theme categories differ");
                    ListView list=menuList(a,false);
                    check(list.getAdapter().getCount()==7,"Preferences differ");
                    View entry=list.getAdapter().getView(0,null,list);
                    check(entry.getContentDescription().toString().equals("开机自启，已选中"),"Selected circle state missing");
                });
                screenshot(t,out,id+"-selected");closeMenu(t,a);
                main(t,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(8);menuList(a,true).performItemClick(null,8,8);});
                main(t,()->check(menuList(a,false).getCount()==2,"Version menu contains a status row: "+id));
                screenshot(t,out,id+"-version");closeMenu(t,a);
                themes.put(new JSONObject().put("id",id).put("sharedRenderer",true).put("selectedCircle",true));
            }
            t.getUiAutomation().setOnAccessibilityEventListener(event->{
                if(event.getEventType()==android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                        &&a.getPackageName().contentEquals(event.getPackageName()==null?"":event.getPackageName()))
                    for(CharSequence text:event.getText())if(text!=null)updateToast.set(text.toString());
            });
            click(t,a,7,1);AutoUpdater updater=(AutoUpdater)field(a,"autoUpdater");long until=SystemClock.elapsedRealtime()+65000;
            while(("idle".equals(updater.stateJson().optString("state"))||updater.stateJson().optBoolean("checking"))&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
            check(!"idle".equals(updater.stateJson().optString("state"))&&!updater.stateJson().optBoolean("checking"),"Update action did not complete");
            long toastUntil=SystemClock.elapsedRealtime()+4000;
            while((updateToast.get()==null||!updateToast.get().contains(updater.stateJson().optString("message")))&&SystemClock.elapsedRealtime()<toastUntil)SystemClock.sleep(30);
            check(updateToast.get()!=null&&updateToast.get().contains(updater.stateJson().optString("message")),"Final update result was not shown as Toast: "+updateToast.get());
            t.waitForIdleSync();main(t,()->check(menuList(a,false).getCount()==2,"Update completion added a status row"));screenshot(t,out,"update-result");
            check(field(a,"player")==player&&video.getVideoSurface()==surface&&surface.isValid(),"Menu restarted video output");
            result.put("sharedMenus",themes).put("liteUpdate",updater.stateJson()).put("updateResultToast",updateToast.get());
        }finally{t.getUiAutomation().setOnAccessibilityEventListener(null);closeMenu(t,a);setting(t,a,"autoStart","autoStart",boot);TvUiPlugin.select(a,saved);t.waitForIdleSync();}
    }
    private static JSONArray groupIds(JSONArray groups)throws Exception {JSONArray ids=new JSONArray();for(int i=0;i<groups.length();i++)ids.put(groups.getJSONObject(i).getString("id"));return ids;}
    private static void groupSorting(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        PlaylistManager manager=(PlaylistManager)field(a,"playlistManager");
        Field managed=PlaylistManager.class.getDeclaredField("managementGroups");managed.setAccessible(true);Object savedGroups=managed.get(manager);
        android.content.SharedPreferences prefs=a.getSharedPreferences("management",0);String savedOrder=prefs.getString("merged_playlist_group_order_v1",null);
        // The playback runner supplies an in-memory catalog; make the manager fixture agree with it.
        ChannelCatalog.Group[] playbackCatalog=ChannelCatalog.GROUPS.clone();
        ChannelCatalog.Group[] fixture=java.util.Arrays.copyOf(playbackCatalog,playbackCatalog.length+2);
        ChannelCatalog.Group activeGroup=playbackCatalog[(Integer)field(a,"currentGroupIndex")];
        fixture[playbackCatalog.length]=new ChannelCatalog.Group("排序测试二",activeGroup.source,activeGroup.channels);
        fixture[playbackCatalog.length+1]=new ChannelCatalog.Group("排序测试三",activeGroup.source,activeGroup.channels);
        managed.set(manager,fixture);
        JSONArray original=groupIds(manager.getGroupSettingsJson()),reversed=new JSONArray();
        for(int i=original.length()-1;i>=0;i--)reversed.put(original.getString(i));
        Method apply=MainActivity.class.getDeclaredMethod("handleWebSettings",JSONObject.class);apply.setAccessible(true);
        IMediaPlayer player=(IMediaPlayer)field(a,"player");int[] before={(Integer)field(a,"currentGroupIndex"),(Integer)field(a,"currentChannelIndex")};
        Channel active=ChannelCatalog.GROUPS[before[0]].channels[before[1]];
        try{
            apply.invoke(a,new JSONObject().put("playlistGroupOrder",reversed));t.waitForIdleSync();
            check(groupIds(manager.getGroupSettingsJson()).toString().equals(reversed.toString()),"Group order not applied");
            check(field(a,"player")==player,"Group sorting restarted playback");
            int g=(Integer)field(a,"currentGroupIndex"),c=(Integer)field(a,"currentChannelIndex");
            check(ChannelCatalog.GROUPS[g].channels[c].url.equals(active.url),"Group sorting lost active channel");
            PlaylistManager fresh=new PlaylistManager(a);managed.set(fresh,managed.get(manager));check(groupIds(fresh.getGroupSettingsJson()).toString().equals(reversed.toString()),"Group order lost after reload");
            if(reversed.length()>1){JSONArray invalid=new JSONArray(reversed.toString());invalid.put(1,invalid.getString(0));boolean rejected=false;try{manager.updateGroupOrder(invalid);}catch(IOException expected){rejected=true;}check(rejected,"Duplicate group IDs accepted");check(groupIds(manager.getGroupSettingsJson()).toString().equals(reversed.toString()),"Invalid order changed saved order");}
            row.put("groupSorting",new JSONObject().put("groups",original.length()).put("applied",true).put("reload",true).put("retainedPlayer",true).put("retainedChannel",true).put("rejectDuplicate",true));
        }finally{try{apply.invoke(a,new JSONObject().put("playlistGroupOrder",original));t.waitForIdleSync();}finally{ChannelCatalog.GROUPS=playbackCatalog;managed.set(manager,savedGroups);android.content.SharedPreferences.Editor edit=prefs.edit();if(savedOrder==null)edit.remove("merged_playlist_group_order_v1");else edit.putString("merged_playlist_group_order_v1",savedOrder);edit.commit();}}
    }

    private static void touch(Instrumentation t,int action,long down,float x,float y) {
        android.view.MotionEvent e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);t.sendPointerSync(e);e.recycle();
    }
    private static void phoneGestures(Instrumentation t,MainActivity a,JSONObject row,File out)throws Exception {
        TvUiPlugin.select(a,"ui2");t.waitForIdleSync();closeMenu(t,a);
        main(t,()->{invoke(a,"closeChannelList");VideoControlsOverlay c=(VideoControlsOverlay)field(a,"videoControlsOverlay");if(c!=null)c.hide();});
        View root=(View)field(a,"root");int[] origin=new int[2];main(t,()->root.getLocationOnScreen(origin));
        float x=origin[0]+root.getWidth()*.88f,y=origin[1]+root.getHeight()*.62f;
        long down=SystemClock.uptimeMillis();touch(t,0,down,x,y);touch(t,1,down,x,y);t.waitForIdleSync();
        check(((TvUiMenu)field(a,"tvUiMenu")).visible(),"Right tap did not open native settings");
        check(!((View)field(a,"managementPanel")).isShown(),"Right tap opened QR");screenshot(t,out,"phone-right-settings");closeMenu(t,a);
        JSONArray gestures=new JSONArray();
        // Finite-video gestures are covered by PlaybackGesturesInstrumentation;
        // Live playback uses vertical channel swipes and horizontal source swipes.
        Method durationMethod=MainActivity.class.getDeclaredMethod("seekableDuration");durationMethod.setAccessible(true);
        for(boolean horizontal:(Long)durationMethod.invoke(a)>0?new boolean[0]:new boolean[]{false,true}){
            Method channelMethod=MainActivity.class.getDeclaredMethod("currentChannel");channelMethod.setAccessible(true);
            Channel active=(Channel)channelMethod.invoke(a);
            if(horizontal&&active.sourceCount()<2)continue; // Single-source rebound is covered separately.
            main(t,()->{VideoControlsOverlay c=(VideoControlsOverlay)field(a,"videoControlsOverlay");if(c!=null)c.hide();});
            DirectVideoView video=(DirectVideoView)field(a,"videoView");android.view.Surface surface=video.getVideoSurface();
            int channel=(Integer)field(a,"currentChannelIndex"),source=(Integer)field(a,"currentSourceIndex");
            down=SystemClock.uptimeMillis();touch(t,0,down,x,y);
            for(int step=1;step<=6;step++){touch(t,2,down,x-(horizontal?root.getWidth()*.52f*step/6:0),y-(horizontal?0:root.getHeight()*.38f*step/6));SystemClock.sleep(16);}
            check((horizontal?video.getTranslationX():video.getTranslationY())<-10,"Surface container did not follow finger");
            int[] surfaceAt=new int[2],viewAt=new int[2];main(t,()->{video.getLocationOnScreen(viewAt);video.getSurfaceView().getLocationOnScreen(surfaceAt);});
            check(Math.abs(surfaceAt[horizontal?0:1]-viewAt[horizontal?0:1])<3,"SurfaceView did not move with container");
            screenshot(t,out,horizontal?"source-drag":"channel-drag");
            touch(t,1,down,x-(horizontal?root.getWidth()*.52f:0),y-(horizontal?0:root.getHeight()*.38f));SystemClock.sleep(60);
            check((Boolean)field(a,"channelSwitchAnimating"),"Swipe completion animation missing");
            screenshot(t,out,horizontal?"source-slide":"channel-slide");SystemClock.sleep(250);t.waitForIdleSync();
            DirectVideoView incoming=(DirectVideoView)field(a,"videoView");
            check(!(Boolean)field(a,"channelSwitchAnimating")&&Math.abs(incoming.getTranslationX())<1&&Math.abs(incoming.getTranslationY())<1,"Swipe did not settle");
            boolean waiting=!(Boolean)field(a,"videoRenderingStarted");
            if(waiting)check(incoming.isFrameCovered(),"Unready incoming Surface became visible");
            check(video.getVideoSurface()==surface&&surface.isValid(),"Swipe destroyed retiring Surface");
            check(incoming!=video&&incoming.getVideoSurface()!=surface&&incoming.isSurfaceReady(),"Phone did not prepare on a second Surface");
            ready(t,a);check(!incoming.isFrameCovered(),"First frame remained covered");
            if(horizontal)check((Integer)field(a,"currentChannelIndex")==channel&&(Integer)field(a,"currentSourceIndex")!=source,"Swipe did not change source on the same channel");
            else check((Integer)field(a,"currentChannelIndex")!=channel,"Swipe did not change channel");
            gestures.put(new JSONObject().put("axis",horizontal?"horizontal-source":"vertical-channel").put("followed",true).put("settled",true).put("retainedOldSurface",true).put("parallelSurfaces",true).put("coveredUntilFirstFrame",waiting));
        }
        main(t,()->invoke(a,"openTvUiMenu"));
        main(t,()->menuList(a,true).performItemClick(null,4,4));
        check(((TvUiMenu)field(a,"tvUiMenu")).visible(),"Channel group menu closed");
        check(menuList(a,false).getCount()>0,"Missing group visibility switches");
        screenshot(t,out,"phone-channel-management");closeMenu(t,a);
        row.put("phoneChannelManagement",true);
        row.put("phoneRightSettings",true).put("phoneSwipes",gestures);
    }

    private static void ready(Instrumentation t,MainActivity a)throws Exception{long end=SystemClock.elapsedRealtime()+15000;while((!(Boolean)field(a,"prepared")||!(Boolean)field(a,"videoRenderingStarted"))&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(30);check((Boolean)field(a,"prepared")&&(Boolean)field(a,"videoRenderingStarted"),"Playback did not recover after menu action");t.waitForIdleSync();}

    static void seekFeedback(Instrumentation t,MainActivity a,JSONObject row,String mode)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");
        File out=new File(a.getExternalFilesDir(null),"seek-feedback");out.mkdirs();
        try {
            TvUiPlugin.select(a,"fail".equals(mode)?"ui6":"ui5");t.waitForIdleSync();
            main(t,()->invoke(a,"showVideoControls"));
            VideoControlsOverlay overlay=(VideoControlsOverlay)field(a,"videoControlsOverlay");
            Field pf=VideoControlsOverlay.class.getDeclaredField("playback");pf.setAccessible(true);
            VideoControlsOverlay.Playback playback=(VideoControlsOverlay.Playback)pf.get(overlay);
            main(t,()->{playback.seek(60000);overlay.show();});
            SystemClock.sleep(600);
            final String[] status={""};main(t,()->status[0]=playback.status());
            check(!status[0].isEmpty(),"Seek did not show loading feedback");
            screenshot(t,out,mode+"-loading");row.put("seekInitialFeedback",status[0]);
            long end=SystemClock.elapsedRealtime()+20000;boolean success=false;
            while(SystemClock.elapsedRealtime()<end){
                main(t,()->status[0]=playback.status());
                if("fail".equals(mode)?status[0].contains("失败")||status[0].contains("拒绝"):status[0].isEmpty()&&playback.position()>=59000){success=true;break;}
                SystemClock.sleep(100);
            }
            check(success,"Seek feedback did not reach expected outcome: "+mode+" "+status[0]);
            row.put("seekOutcome",status[0]).put("seekFeedbackPassed",true);
            screenshot(t,out,mode+"-result");
        } finally {
            main(t,()->{VideoControlsOverlay overlay=(VideoControlsOverlay)field(a,"videoControlsOverlay");if(overlay!=null)overlay.hide();Method m=MainActivity.class.getDeclaredMethod("startChannel",int.class);m.setAccessible(true);m.invoke(a,(Integer)field(a,"currentChannelIndex"));});ready(t,a);
            TvUiPlugin.select(a,saved);t.waitForIdleSync();
        }
    }

    private static void tapMenuOption(Instrumentation t,MainActivity a,int index)throws Exception {
        final int[] xy=new int[2];main(t,()->{ListView list=menuList(a,false);View item=list.getChildAt(index-list.getFirstVisiblePosition());check(item!=null,"Option not on screen");item.getLocationOnScreen(xy);xy[0]+=item.getWidth()/2;xy[1]+=item.getHeight()/2;});
        long down=SystemClock.uptimeMillis();touch(t,0,down,xy[0],xy[1]);touch(t,1,down,xy[0],xy[1]);t.waitForIdleSync();
    }
    private static int renderedMenuColor(View view)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(Math.max(1,view.getWidth()),Math.max(1,view.getHeight()),Bitmap.Config.ARGB_8888);
        try{view.draw(new android.graphics.Canvas(bitmap));Field paint=view.getClass().getSuperclass().getDeclaredField("p");paint.setAccessible(true);return ((android.graphics.Paint)paint.get(view)).getColor();}finally{bitmap.recycle();}
    }
    static void decoderSelection(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        String selected=new JSONObject(TvUiPlugin.status(a)).getString("selected"),decode=(String)field(a,"decodeMode");JSONArray results=new JSONArray();
        File out=new File(a.getExternalFilesDir(null),"menu-selection");out.mkdirs();
        try{
            for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}){
                TvUiPlugin.select(a,id);t.waitForIdleSync();
                main(t,()->{invoke(a,"openTvUiMenu");ListView list=menuList(a,true);list.setSelection(2);list.performItemClick(null,2,2);});
                for(int option:new int[]{2,1,0}){
                    tapMenuOption(t,a,option);String mode=new String[]{"auto","hardware","software"}[option];waitSetting(t,a,"decodeMode",mode);ready(t,a);SystemClock.sleep(150);t.waitForIdleSync();
                    main(t,()->{
                        ListView list=menuList(a,false);int accent="ui2".equals(id)?0xff29a6de:"ui3".equals(id)?0xff1c73ee:"ui4".equals(id)?0xff00d1ff:"ui5".equals(id)?0xffb8ddff:0xffe50914;
                        View item=list.getChildAt(option-list.getFirstVisiblePosition());check(item!=null&&item.getContentDescription().toString().endsWith("已选中"),"Touch did not mark selected "+id+"/"+mode);
                        // Touch mode may have focus without a keyboard selection: the original failure.
                        list.requestFocusFromTouch();list.setSelection(-1);
                        int touchColor="ui5".equals(id)&&list.hasFocus()&&list.getSelectedItemPosition()==option?0xff142438:accent;
                        check(renderedMenuColor(item)==touchColor,"Selected font is not accent/contrast color in touch focus "+id+"/"+mode);
                        list.requestFocus();list.setSelection(option);
                    });t.waitForIdleSync();
                    main(t,()->{
                        ListView list=menuList(a,false);View item=list.getChildAt(option-list.getFirstVisiblePosition());int expected="ui5".equals(id)?0xff142438:"ui2".equals(id)?0xff29a6de:"ui3".equals(id)?0xff1c73ee:"ui4".equals(id)?0xff00d1ff:0xffe50914;
                        check(renderedMenuColor(item)==expected,"Selected font lost in keyboard focus "+id+"/"+mode);
                        check(a.getSharedPreferences(MainActivity.PREFERENCES,0).getString("decode_mode","").equals(mode),"Decode choice not saved");
                        boolean software=((tv.danmaku.ijk.media.player.IjkMediaPlayer)field(a,"player")).getVideoDecoder()==tv.danmaku.ijk.media.player.IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC;
                        check(software=="software".equals(mode),"Decode choice did not affect player");
                    });
                    if(option==1)screenshot(t,out,id+"-hardware-selected");
                    // Return to the category then revisit; selection and color must persist.
                    main(t,()->{menuList(a,true).requestFocus();});t.waitForIdleSync();
                    main(t,()->{ListView list=menuList(a,false);View item=list.getChildAt(option-list.getFirstVisiblePosition());int accent="ui2".equals(id)?0xff29a6de:"ui3".equals(id)?0xff1c73ee:"ui4".equals(id)?0xff00d1ff:"ui5".equals(id)?0xffb8ddff:0xffe50914;check(renderedMenuColor(item)==accent,"Selected font lost when moving away");});
                    results.put(new JSONObject().put("theme",id).put("mode",mode).put("touchSelected",true).put("focusColor",true).put("decoderApplied",true));
                }
                closeMenu(t,a);
            }
            row.put("decoderSelection",results).put("decoderSelectionPassed",true);
        }finally{closeMenu(t,a);setting(t,a,"decodeMode","decodeMode",decode);ready(t,a);TvUiPlugin.select(a,selected);t.waitForIdleSync();}
    }

    private static void sizeMode(Instrumentation t,MainActivity a,String mode)throws Exception {
        Method m=MainActivity.class.getDeclaredMethod("handleWebSettings",JSONObject.class);m.setAccessible(true);
        m.invoke(a,new JSONObject().put("uiScaleMode",mode));t.waitForIdleSync();SystemClock.sleep(180);t.waitForIdleSync();
    }
    private static JSONObject geometry(MainActivity a)throws Exception {
        JSONObject data=new JSONObject();
        for(String name:new String[]{"channelListPanel","channelBar","epgToggle","videoClock","networkSpeedOverlay"}){
            View v=(View)field(a,name);JSONObject item=new JSONObject().put("width",v.getWidth()).put("height",v.getHeight())
                    .put("left",v.getLeft()).put("top",v.getTop()).put("padding",v.getPaddingLeft()+","+v.getPaddingTop()+","+v.getPaddingRight()+","+v.getPaddingBottom());
            if(v instanceof android.widget.TextView)item.put("textSize",((android.widget.TextView)v).getTextSize());
            data.put(name,item);
        }
        ListView list=(ListView)field(a,"channelList");
        data.put("rowHeight",list.getChildAt(0).getHeight()).put("scale",TvUiChrome.scale((View)field(a,"root")));
        return data;
    }
    static void rapidChannelKeys(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");ChannelCatalog.Group[] catalog=ChannelCatalog.GROUPS;
        int g=(Integer)field(a,"currentGroupIndex"),c=(Integer)field(a,"currentChannelIndex"),source=(Integer)field(a,"currentSourceIndex");JSONArray results=new JSONArray();
        try{
            seed(t,a);
            for(String id:TvUiPlugin.IDS){
                TvUiPlugin.select(a,id);t.waitForIdleSync();main(t,()->{set(a,"currentGroupIndex",0);set(a,"currentChannelIndex",0);invoke(a,"openChannelList");});
                // Initialize keyboard focus, then deliver a burst before the next layout.
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_DOWN);remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_UP);
                main(t,()->{
                    for(int i=0;i<6;i++)a.dispatchKeyEvent(new android.view.KeyEvent(0,SystemClock.uptimeMillis(),android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_DOWN,i));
                    check(((ListView)field(a,"channelList")).getCheckedItemPosition()==6,"Rapid DOWN lost input: "+id);
                    a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_CENTER));
                    check((Integer)field(a,"currentChannelIndex")==6,"Rapid OK played stale channel: "+id);
                });ready(t,a);results.put(new JSONObject().put("theme",id).put("burstDown",6).put("selectedChannel",6).put("immediateOkPassed",true));
            }
            row.put("rapidChannelKeys",results);
        }finally{main(t,()->{invoke(a,"closeChannelList");ChannelCatalog.GROUPS=catalog;set(a,"currentGroupIndex",g);set(a,"currentChannelIndex",c);set(a,"currentSourceIndex",source);});TvUiPlugin.select(a,saved);t.waitForIdleSync();}
    }
    static void playingIconCycle(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");
        File out=new File(a.getExternalFilesDir(null),"playing-icon-cycle");out.mkdirs();JSONArray checks=new JSONArray();
        try {
            for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}) {
                TvUiPlugin.select(a,id);t.waitForIdleSync();
                main(t,()->{
                    android.os.Bundle state=new android.os.Bundle();state.putBoolean("playing",true);state.putBoolean("selected",true);state.putString("title","CCTV-1 综合");
                    View icon=TvUiPlugin.provider().create(a,"channel-row",state,null,null);float d=a.themeScale();
                    int height=Math.round(("ui2".equals(id)?70:"ui3".equals(id)?65:"ui4".equals(id)?60:"ui5".equals(id)?46:48)*d);
                    int width=Math.round(252*d);icon.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));icon.layout(0,0,width,height);
                    Field beat=icon.getClass().getDeclaredField("beat");beat.setAccessible(true);
                    float x="ui2".equals(id)?36:"ui3".equals(id)?16:"ui4".equals(id)?31:"ui6".equals(id)?10:18,barWidth="ui5".equals(id)||"ui6".equals(id)?3:4;
                    float bottom=height/d/2+(barWidth==3?9:11);int left=Math.round((x-3)*d),top=Math.round((bottom-23)*d),right=Math.round((x+2*(barWidth+3)+barWidth+3)*d),end=Math.round((bottom+3)*d);
                    JSONArray frames=new JSONArray();java.util.Set<Integer>[] heights=new java.util.Set[]{new java.util.HashSet<Integer>(),new java.util.HashSet<Integer>(),new java.util.HashSet<Integer>()};
                    for(int frame=0;frame<8;frame++){
                        beat.setInt(icon,frame);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);icon.draw(new android.graphics.Canvas(bitmap));
                        JSONArray bars=new JSONArray();for(int column=0;column<3;column++){
                            int cx=Math.round((x+column*(barWidth+3)+barWidth/2)*d),color=bitmap.getPixel(cx,Math.round((bottom-2)*d)),pixels=0;
                            for(int y=top;y<end;y++)if(bitmap.getPixel(cx,y)==color)pixels++;
                            heights[column].add(pixels);bars.put(pixels);
                        }
                        frames.put(bars);android.graphics.Bitmap crop=android.graphics.Bitmap.createBitmap(bitmap,left,top,right-left,end-top);
                        try(FileOutputStream stream=new FileOutputStream(new File(out,id+"-"+frame+".png"))){crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);}finally{crop.recycle();bitmap.recycle();}
                    }
                    for(int column=0;column<3;column++)check(heights[column].size()>=4,"Static playing bar "+id+"/"+column);
                    check(!(Boolean)member(icon,"pulsePosted"),"Detached preview started an animation timer");
                    checks.put(new JSONObject().put("theme",id).put("barHeightsPx",frames).put("allThreeMove",true));
                });
            }
            row.put("playingIconCycle",checks);
        } finally {TvUiPlugin.select(a,saved);t.waitForIdleSync();}
    }
    private static void remoteKey(Instrumentation t,int key){t.sendKeyDownUpSync(key);t.waitForIdleSync();}
    private static void selectedChannel(MainActivity a,int expected)throws Exception {
        ListView list=(ListView)field(a,"channelList");
        check(list.getSelectedItemPosition()==expected,"Remote selection did not move: "+list.getSelectedItemPosition()+" expected "+expected);
        check(list.getCheckedItemPosition()==expected,"Checked channel differs from keyboard cursor");
        View selected=list.getSelectedView();check(selected!=null&&selected.isActivated(),"Skin highlight did not follow cursor "+expected);
        int active=0;for(int i=0;i<list.getChildCount();i++)if(list.getChildAt(i).isActivated())active++;
        check(active==1,"Stale or duplicate skin highlight: "+active);
    }
    private static Object member(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    static void channelRemote(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected");ChannelCatalog.Group[] catalog=ChannelCatalog.GROUPS;
        int group=(Integer)field(a,"currentGroupIndex"),channel=(Integer)field(a,"currentChannelIndex"),source=(Integer)field(a,"currentSourceIndex");
        JSONArray results=new JSONArray();File out=new File(a.getExternalFilesDir(null),"channel-remote");out.mkdirs();
        try {
            seed(t,a);
            for(String id:TvUiPlugin.IDS){
                TvUiPlugin.select(a,id);t.waitForIdleSync();main(t,()->{set(a,"currentGroupIndex",0);set(a,"currentChannelIndex",0);invoke(a,"openChannelList");});
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_DOWN);main(t,()->selectedChannel(a,1));
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_UP);main(t,()->selectedChannel(a,0));
                SystemClock.sleep(150);t.waitForIdleSync();View playing=((ListView)field(a,"channelList")).getChildAt(0);JSONObject sample=new JSONObject().put("theme",id);
                if(!"builtin".equals(id)){
                    int beat=(Integer)member(playing,"beat");SystemClock.sleep(350);
                    check((Boolean)member(playing,"pulsePosted")&&(Integer)member(playing,"beat")!=beat,"Playing icon did not animate "+id+" beat="+beat+"->"+member(playing,"beat")+" pending="+member(playing,"pulsePosted")+" attached="+member(playing,"attached")+" parent="+playing.getParent()+" shown="+playing.isShown()+" data="+member(playing,"data"));
                    screenshot(t,out,id+"-pulse-a");screenshot(t,out,id+"-pulse-b");
                    main(t,()->{
                        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(playing.getWidth(),playing.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);long start=SystemClock.currentThreadTimeMillis();
                        for(int i=0;i<200;i++)playing.draw(canvas);
                        sample.put("draw200CpuMs",SystemClock.currentThreadTimeMillis()-start);bitmap.recycle();
                    });
                }
                Object before=field(a,"player");
                for(int i=1;i<=19;i++){remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_DOWN);final int expected=i;main(t,()->selectedChannel(a,expected));}
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_DOWN);main(t,()->selectedChannel(a,19));
                if(!"builtin".equals(id)){
                    SystemClock.sleep(160);int beat=(Integer)member(playing,"beat");SystemClock.sleep(230);
                    check(!(Boolean)member(playing,"pulsePosted")&&(Integer)member(playing,"beat")==beat,"Offscreen animation kept running "+id);
                }
                for(int i=18;i>=0;i--){remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_UP);final int expected=i;main(t,()->selectedChannel(a,expected));}
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_UP);main(t,()->selectedChannel(a,0));
                // TV key repeat events use the same Activity path as ordinary DPAD keys.
                for(int i=1;i<=3;i++){final int repeat=i;main(t,()->a.dispatchKeyEvent(new android.view.KeyEvent(0,SystemClock.uptimeMillis(),android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_DOWN,repeat)));main(t,()->selectedChannel(a,repeat));}
                screenshot(t,out,id+"-remote-highlight");
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_LEFT);check(((ListView)field(a,"groupList")).hasFocus(),"LEFT did not focus groups");
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_DOWN);check((Integer)field(a,"browsingGroupIndex")==1,"Group DOWN failed");
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_UP);check((Integer)field(a,"browsingGroupIndex")==0,"Group UP failed");
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_RIGHT);check(((ListView)field(a,"channelList")).hasFocus(),"RIGHT did not focus channels");
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_DOWN);main(t,()->selectedChannel(a,1));
                check(field(a,"player")==before,"Browsing restarted playback");
                remoteKey(t,android.view.KeyEvent.KEYCODE_DPAD_CENTER);
                check(((View)field(a,"channelListPanel")).getVisibility()==View.GONE,"OK did not close channel list");
                check((Integer)field(a,"currentGroupIndex")==0&&(Integer)field(a,"currentChannelIndex")==1,"OK played wrong channel");
                ready(t,a);sample.put("remoteNavigationPassed",true).put("realChannelSwitchPassed",true);
                if(!"builtin".equals(id)){
                    main(t,()->invoke(a,"openChannelList"));ListView list=(ListView)field(a,"channelList");View current=list.getChildAt(list.getSelectedItemPosition()-list.getFirstVisiblePosition());
                    main(t,()->invoke(a,"closeChannelList"));SystemClock.sleep(150);int beat=(Integer)member(current,"beat");SystemClock.sleep(220);
                    check(!(Boolean)member(current,"pulsePosted")&&(Integer)member(current,"beat")==beat,"Closed panel animation kept running");
                    click(t,a,6,2);main(t,()->{check(menuList(a,true).getCount()==2&&menuList(a,false).getCount()==TvUiPlugin.IDS.length,"Theme selector not opened from preferences");});
                    remoteKey(t,android.view.KeyEvent.KEYCODE_BACK);
                    main(t,()->check(menuList(a,true).getCount()==9&&menuList(a,false).getCount()==9,"One Back must return directly to preferences"));
                    closeMenu(t,a);click(t,a,6,2);
                    main(t,()->{ListView backRows=menuList(a,true);check("返回上级".equals(backRows.getAdapter().getItem(1)),"Missing visible parent entry");backRows.performItemClick(null,1,1);});
                    main(t,()->check(menuList(a,true).getCount()==9&&menuList(a,false).getCount()==9,"Parent entry must return to preferences"));closeMenu(t,a);
                    managementEntry(t,a,6,null,id+"-management");
                    main(t,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(6);});screenshot(t,out,id+"-preferences");closeMenu(t,a);
                    main(t,()->{invoke(a,"openTvUiMenu");menuList(a,true).setSelection(8);menuList(a,true).performItemClick(null,8,8);});main(t,()->check(menuList(a,false).getCount()==2,"Version menu must contain only version and update action"));screenshot(t,out,id+"-version");closeMenu(t,a);
                    sample.put("preferencesNavigationPassed",true).put("animationLifecyclePassed",true);
                }
                results.put(sample);
            }
            row.put("channelRemoteChecks",results);
        }finally{
            closeMenu(t,a);main(t,()->{invoke(a,"closeChannelList");ChannelCatalog.GROUPS=catalog;set(a,"currentGroupIndex",group);set(a,"currentChannelIndex",channel);set(a,"currentSourceIndex",source);});
            TvUiPlugin.select(a,saved);t.waitForIdleSync();
        }
    }
    static void layoutAudit(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected"),mode=(String)field(a,"uiScaleMode");
        View root=(View)field(a,"root");Object player=field(a,"player");
        android.view.Surface surface=((DirectVideoView)field(a,"videoView")).getVideoSurface();
        JSONArray results=new JSONArray();File out=new File(a.getExternalFilesDir(null),"menu-layout");out.mkdirs();
        try {
            for(String preset:new String[]{"auto","small","large"}) {
                sizeMode(t,a,preset);
                for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}) {
                    TvUiPlugin.select(a,id);t.waitForIdleSync();main(t,()->invoke(a,"openTvUiMenu"));t.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);t.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP);t.waitForIdleSync();
                    if("auto".equals(preset))screenshot(t,out,id+"-initial");
                    for(int category=0;category<8;category++) {
                        final int index=category;
                        main(t,()->{ListView list=menuList(a,true);list.requestFocus();list.setSelection(index);});
                        main(t,()->{
                            ListView list=menuList(a,true);View selected=list.getSelectedView();
                            check(list.getFirstVisiblePosition()==0&&fullRows(list)==8,"Clipped category "+id+"/"+preset+"/"+index);
                            check(selected!=null&&selected.getTop()>=0&&selected.getBottom()<=list.getHeight(),"Selected category clipped "+id+"/"+preset+"/"+index+" selected="+selected+" position="+list.getSelectedItemPosition());
                            ListView options=menuList(a,false);check(options.getCount()>0,"Missing submenu "+index);
                            options.requestFocus();options.setSelection(options.getCount()-1);
                        });
                        main(t,()->{
                            ListView options=menuList(a,false);View selected=options.getSelectedView();
                            check(selected!=null&&selected.getTop()>=0&&selected.getBottom()<=options.getHeight(),"Last option clipped "+id+"/"+preset+"/"+index);
                            results.put(new JSONObject().put("theme",id).put("preset",preset).put("category",index).put("fullCategories",fullRows(menuList(a,true))));
                        });
                    }
                    main(t,()->{ListView list=menuList(a,true);list.requestFocus();list.setSelection(0);});
                    if("auto".equals(preset))screenshot(t,out,id+"-menu");
                    closeMenu(t,a);
                }
            }
            TvUiPlugin.select(a,"builtin");t.waitForIdleSync();sizeMode(t,a,"auto");
            main(t,()->{invoke(a,"requestAppExitFromBack");});
            main(t,()->{
                View prompt=(View)field(a,"backPrompt");
                check(prompt.isShown(),"Default exit prompt missing");
                check(Math.abs(prompt.getLeft()+prompt.getWidth()/2d-root.getWidth()/2d)<=1,"Default prompt not horizontally centered");
                android.widget.FrameLayout.LayoutParams params=(android.widget.FrameLayout.LayoutParams)prompt.getLayoutParams();
                check((params.gravity&android.view.Gravity.VERTICAL_GRAVITY_MASK)==android.view.Gravity.BOTTOM&&params.bottomMargin>0,"Default exit moved from bottom");
                check((long)field(a,"lastBackPressedAt")>0,"Default second-BACK confirmation lost");
            });
            screenshot(t,out,"builtin-exit");
            main(t,()->{((Runnable)field(a,"hideBackPrompt")).run();check(!a.isFinishing()&&((View)field(a,"backPrompt")).getVisibility()==View.GONE,"Default prompt timeout behavior changed");
                check(field(a,"player")==player,"Menu audit restarted player");check(((DirectVideoView)field(a,"videoView")).getVideoSurface()==surface&&surface.isValid(),"Menu audit recreated Surface");});
            row.put("menuLayoutAudit",results).put("defaultExitBehaviorPassed",true);
        } finally {closeMenu(t,a);sizeMode(t,a,mode);TvUiPlugin.select(a,saved);t.waitForIdleSync();}
    }
    static void sizing(Instrumentation t,MainActivity a,JSONObject row)throws Exception {
        String saved=new JSONObject(TvUiPlugin.status(a)).getString("selected"),mode=(String)field(a,"uiScaleMode");Object[] settings=save(a);
        ChannelCatalog.Group[] catalog=ChannelCatalog.GROUPS;View root=(View)field(a,"root");
        android.view.ViewGroup.LayoutParams rootParams=root.getLayoutParams();int oldWidth=rootParams.width,oldHeight=rootParams.height;
        int originalWidth=root.getWidth(),originalHeight=root.getHeight();Object player=field(a,"player");
        android.view.Surface surface=((DirectVideoView)field(a,"videoView")).getVideoSurface();
        JSONArray samples=new JSONArray();File out=new File(a.getExternalFilesDir(null),"theme-sizing");out.mkdirs();
        View backdrop=new View(a);backdrop.setBackgroundColor(0xff60676c);
        try {
            cleanPicture(t,a);seed(t,a);
            main(t,()->((android.widget.FrameLayout)root).addView(backdrop,1,new android.widget.FrameLayout.LayoutParams(-1,-1)));
            for(String preset:new String[]{"auto","small","large","viewport"}){
                if("viewport".equals(preset))main(t,()->{android.view.ViewGroup.LayoutParams p=root.getLayoutParams();p.width=originalWidth-160;p.height=originalHeight-120;root.setLayoutParams(p);});
                sizeMode(t,a,"viewport".equals(preset)?"auto":preset);
                java.util.Map<String,JSONObject> expected=new java.util.HashMap<>();float[] clock={-1},traffic={-1};
                for(int pass=0;pass<2;pass++)for(int n=0;n<TvUiPlugin.IDS.length;n++){
                    final int round=pass;String id=TvUiPlugin.IDS[pass==0?n:TvUiPlugin.IDS.length-1-n];TvUiPlugin.select(a,id);t.waitForIdleSync();
                    main(t,()->{invoke(a,"openChannelList");((android.widget.TextView)field(a,"videoClock")).setText("12:34:56");((android.widget.TextView)field(a,"networkSpeedOverlay")).setText("256 KB/s");
                        // Freeze the sample text while comparing layout, without changing playback.
                        Runnable tick=(Runnable)field(a,"updateClock");root.removeCallbacks(tick);root.removeCallbacks((Runnable)field(a,"updateVideoInfo"));
                        View card=(View)field(a,"channelBar");card.removeCallbacks((Runnable)field(a,"hideChannelBar"));card.setVisibility(View.VISIBLE);});
                    SystemClock.sleep(180);t.waitForIdleSync();
                    main(t,()->{
                        check(((View)field(a,"channelListPanel")).isShown(),"Channel drawer not visible");
                        JSONObject g=geometry(a);float c=(float)g.getJSONObject("videoClock").getDouble("textSize"),f=(float)g.getJSONObject("networkSpeedOverlay").getDouble("textSize");
                        if(clock[0]<0){clock[0]=c;traffic[0]=f;}
                        check(Math.abs(c-clock[0])<.1f&&Math.abs(f-traffic[0])<.1f,"HUD sizes differ after "+preset+"/"+id);
                        if(round==0)expected.put(id,g);else check(g.toString().equals(expected.get(id).toString()),"Geometry drift after round trip "+preset+"/"+id+": "+g+" expected "+expected.get(id));
                        if(!"builtin".equals(id)){
                            float d=(float)g.getDouble("scale");int pitch="ui2".equals(id)?70:"ui3".equals(id)?65:"ui4".equals(id)?60:"ui5".equals(id)?46:48;
                            check(Math.abs(g.getInt("rowHeight")-pitch*d)<=1,"Stale theme row scale "+preset+"/"+id);
                            if(round==0){invoke(a,"openTvUiMenu");ListView list=menuList(a,true);View item=list.getAdapter().getView(0,null,list);int menuPitch="ui2".equals(id)?58:"ui3".equals(id)?65:60;
                                check(item.getLayoutParams().height<=Math.round(menuPitch*d),"Menu exceeds preferred row height");((TvUiMenu)field(a,"tvUiMenu")).dismiss();}
                        }
                        samples.put(new JSONObject().put("preset",preset).put("id",id).put("pass",round).put("geometry",g));
                        check(field(a,"player")==player,"Sizing restarted decoder");check(((DirectVideoView)field(a,"videoView")).getVideoSurface()==surface&&surface.isValid(),"Sizing recreated Surface");
                    });
                    if(pass==0&&"auto".equals(preset)){
                        main(t,()->{invoke(a,"openChannelList");root.removeCallbacks((Runnable)field(a,"updateClock"));root.removeCallbacks((Runnable)field(a,"updateVideoInfo"));});
                        screenshot(t,out,id+"-channels");
                        main(t,()->{invoke(a,"closeChannelList");View card=(View)field(a,"channelBar");card.setVisibility(View.VISIBLE);card.removeCallbacks((Runnable)field(a,"hideChannelBar"));});
                        screenshot(t,out,id+"-card");
                    }
                }
            }
            row.put("themeSizing",samples).put("themeSizingPassed",true);
        }finally{
            main(t,()->{((android.view.ViewGroup)backdrop.getParent()).removeView(backdrop);android.view.ViewGroup.LayoutParams p=root.getLayoutParams();p.width=oldWidth;p.height=oldHeight;root.setLayoutParams(p);});
            ChannelCatalog.GROUPS=catalog;restore(t,a,settings);sizeMode(t,a,mode);TvUiPlugin.select(a,saved);t.waitForIdleSync();main(t,()->invoke(a,"scheduleVideoInfoRefresh"));
        }
    }

    static String storage(Instrumentation test,String action)throws Exception {
        android.content.Context c=test.getTargetContext();
        if("prepare".equals(action)){TvUiPlugin.select(c,"ui3");return "PASS saved ui3 for next process";}
        if("clean".equals(action)){TvUiPlugin.select(c,"builtin");return "PASS restored builtin";}
        check(TvUiPlugin.current()==null,"Cold test did not start in fresh process");
        check("ui3".equals(c.getSharedPreferences("tv_ui",0).getString("selected","")),"Selection not persisted");
        String sha=c.getSharedPreferences("tv_ui",0).getString("api2_ui3","");
        File jar=new File(new File(c.getFilesDir(),"tv-ui"),sha+".jar");byte[] backup=null;
        if("corrupt".equals(action)){
            backup=new byte[(int)jar.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(jar))){in.readFully(backup);}
            check(jar.setWritable(true),"Cannot prepare corruption fixture");
            try(FileOutputStream out=new FileOutputStream(jar)){out.write(new byte[]{1,2,3});}jar.setReadOnly();
        }
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
        TvUiPlugin.Listener listener=()->ready.countDown();long started=SystemClock.elapsedRealtime();
        try{
            TvUiPlugin.attach(c,listener);check(ready.await(8,java.util.concurrent.TimeUnit.SECONDS),"Cold UI load timed out");
            if("corrupt".equals(action))check(TvUiPlugin.current()==null&&!TvUiPlugin.failure().isEmpty(),"Damaged cache did not retain builtin");
            else check(TvUiPlugin.current()!=null&&"ui3".equals(TvUiPlugin.current().id),"Cold selection lost");
            return "PASS "+action+" backgroundMs="+(SystemClock.elapsedRealtime()-started);
        }finally{
            TvUiPlugin.detach(listener);
            if(backup!=null){jar.setWritable(true);try(FileOutputStream out=new FileOutputStream(jar)){out.write(backup);}jar.setReadOnly();}
        }
    }
}
