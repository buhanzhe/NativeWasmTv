package xiao.bu.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import org.json.*;

/** Real DPAD paging on all three attached lists; playback must never follow browsing. */
public final class ChannelPagingInstrumentation extends Instrumentation {
    private volatile MainActivity activity;private Bundle args;private File out;private String base;
    private final JSONArray results=new JSONArray();private int assertions;
    interface Work{void run()throws Exception;}interface Condition{boolean get()throws Exception;}
    public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
    public void callActivityOnResume(Activity a){super.callActivityOnResume(a);if(a instanceof MainActivity)activity=(MainActivity)a;}
    private Object get(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(activity);}
    private void set(String n,Object v)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(activity,v);}
    private Object call(String n,Class<?>[] types,Object... values)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,values);}
    private void main(Work w)throws Exception{Throwable[] e={null};runOnMainSync(()->{try{w.run();}catch(Throwable x){e[0]=x;}});if(e[0]!=null)throw new AssertionError(e[0]);}
    private void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
    private void until(Condition c,long ms,String message)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(!c.get()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(30);check(c.get(),message);}
    private ListView list(String n)throws Exception{return (ListView)get(n);}
    private void idle(){waitForIdleSync();SystemClock.sleep(18);}
    private void key(int key)throws Exception{sendKeyDownUpSync(key);idle();}
    private void step(int offset)throws Exception{key(offset>0?KeyEvent.KEYCODE_DPAD_DOWN:KeyEvent.KEYCODE_DPAD_UP);}
    private int selected(String n)throws Exception{return list(n).getSelectedItemPosition();}
    private void ready()throws Exception{until(()->(Boolean)get("prepared")&&(Boolean)get("videoRenderingStarted")&&(Integer)get("playbackReadyRequestId")==((Integer)get("playRequestId")).intValue()&&get("pendingChannelListSelection")==null,20000,"New playback request did not render");idle();}
    private void shot(String n)throws Exception{android.graphics.Bitmap b=getUiAutomation().takeScreenshot();if(b!=null)try(FileOutputStream f=new FileOutputStream(new File(out,n+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,f);}finally{b.recycle();}}
    private int lastFull(ListView l){int last=-1;for(int i=0;i<l.getChildCount();i++){View v=l.getChildAt(i);if(v.getTop()>=l.getPaddingTop()-1&&v.getBottom()<=l.getHeight()-l.getPaddingBottom()+1)last=l.getFirstVisiblePosition()+i;}return last;}
    private void completeRows(String name)throws Exception{main(()->{ListView l=list(name);for(int i=0;i<l.getChildCount();i++){View v=l.getChildAt(i);check(v.getTop()>=l.getPaddingTop()-1&&v.getBottom()<=l.getHeight()-l.getPaddingBottom()+1,"Partial row "+name+" pos="+(l.getFirstVisiblePosition()+i)+" top="+v.getTop()+" bottom="+v.getBottom()+" viewport="+l.getHeight()+" padding="+l.getPaddingBottom());}});}
    private void nativeBackgrounds()throws Exception{
        if(TvUiPlugin.current()!=null)return;
        main(()->{for(String name:new String[]{"groupList","channelList","epgList"}){
            ListView l=list(name);if(!l.isShown()||l.hasFocus())continue;
            for(int i=0;i<l.getChildCount();i++){
                android.graphics.drawable.Drawable background=l.getChildAt(i).getBackground();
                check(background instanceof android.graphics.drawable.ColorDrawable&&android.graphics.Color.alpha(((android.graphics.drawable.ColorDrawable)background).getColor())==0,"Inactive column retained background "+name+"/"+i);
            }
        }});
    }
    private void exercise(String theme,String name,int count)throws Exception{
        Bundle progress=new Bundle();progress.putString("stream","CHECK "+theme+" "+name+" items="+count+"\n");sendStatus(0,progress);
        check(list(name).hasFocus(),"Wrong focus entering "+name);check(selected(name)==0,"Expected first row "+name+" got "+selected(name));
        int pages=0;
        for(int i=1;i<count;i++){
            int before=selected(name),last=lastFull(list(name));String state=" first="+list(name).getFirstVisiblePosition()+" top="+list(name).getChildAt(0).getTop()+" padding="+list(name).getPaddingTop()+" checked="+list(name).getCheckedItemPosition()+" logical="+((RemoteListPager)get(name.equals("groupList")?"groupPager":name.equals("channelList")?"channelPager":"epgPager")).position(-1);step(1);
            check(selected(name)==i,"DOWN skipped/repeated row "+theme+"/"+name+" expected="+i+" got="+selected(name)+state);
            if(before==last){check(list(name).getFirstVisiblePosition()==i,"Next page did not start on first row "+name+"/"+i+" first="+list(name).getFirstVisiblePosition()+" selected="+selected(name)+" height="+list(name).getHeight()+" bottomPad="+list(name).getPaddingBottom()+" firstTop="+list(name).getChildAt(0).getTop());pages++;}
            completeRows(name);nativeBackgrounds();
        }
        boolean wrapping=name.equals("channelList");
        shot(theme+"-"+name+"-last");step(1);
        if(wrapping){check(selected(name)==0,"Last channel DOWN did not wrap to first");completeRows(name);step(-1);check(selected(name)==count-1,"First channel UP did not wrap to last");completeRows(name);}
        else check(selected(name)==count-1,"List end wrapped "+name);
        if(name.equals("groupList")){key(KeyEvent.KEYCODE_DPAD_RIGHT);check(list(name).hasFocus()&&list("channelList").getCount()==0,"Empty group accepted child focus");}
        for(int i=count-2;i>=0;i--){int before=selected(name),first=list(name).getFirstVisiblePosition();step(-1);check(selected(name)==i,"UP skipped row "+name+"/"+i);if(before==first)check(lastFull(list(name))==i,"Previous page did not end on last row "+name+"/"+i);completeRows(name);}
        step(-1);
        if(wrapping){check(selected(name)==count-1,"First channel UP did not wrap");completeRows(name);step(1);check(selected(name)==0,"Last channel DOWN did not restore first");completeRows(name);}
        else check(selected(name)==0,"List beginning wrapped "+name);
        check(pages>=1,"Fixture did not cross pages "+name);
        results.put(new JSONObject().put("theme",theme).put("list",name).put("items",count).put("pageTransitions",pages).put("bothDirections",true).put("noPartialRows",true).put("wrappingEnds",wrapping));
    }
    private void guide(String path)throws Exception{
        main(()->{set("epgUrls",new String[]{base+path});call("refreshEpgNow",new Class[]{});});
        EpgManager manager=(EpgManager)get("epgManager");until(()->!manager.isLoading(),10000,"Guide load did not complete");idle();
    }
    private void touchDrag(ListView l)throws Exception{
        int[] xy=new int[2];main(()->l.getLocationOnScreen(xy));long down=SystemClock.uptimeMillis();float x=xy[0]+l.getWidth()*.5f,y=xy[1]+l.getHeight()*.7f;
        for(int i=0;i<=8;i++){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),i==0?0:2,x,y-l.getHeight()*.5f*i/8,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);sendPointerSync(e);e.recycle();SystemClock.sleep(20);}
        MotionEvent end=MotionEvent.obtain(down,SystemClock.uptimeMillis(),1,x,y-l.getHeight()*.5f,0);sendPointerSync(end);end.recycle();SystemClock.sleep(450);idle();
    }
    public void onStart(){Bundle result=new Bundle();int code=1;ChannelCatalog.Group[] saved=ChannelCatalog.GROUPS;String oldTheme="builtin",oldSize="auto";
        try{
            Context c=getTargetContext();base=args.getString("base");out=new File(c.getExternalFilesDir(null),"channel-paging");out.mkdirs();oldTheme=new JSONObject(TvUiPlugin.status(c)).getString("selected");
            c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until(()->activity!=null,15000,"No Activity");SystemClock.sleep(800);oldSize=(String)get("uiScaleMode");
            StringBuilder m3u=new StringBuilder("#EXTM3U\n");
            for(int g=0;g<23;g++)for(int i=0;i<23;i++)m3u.append("#EXTINF:-1 tvg-id=\"p\" catchup=\"append\" catchup-source=\"?utc={utc}&lutc={lutc}\" catchup-days=\"3\" group-title=\"分组").append(g).append("\",频道").append(g).append('-').append(i).append('\n').append(base).append("/vod.mp4?g=").append(g).append("&c=").append(i).append('\n');
            Method parse=PlaylistManager.class.getDeclaredMethod("parse",byte[].class,String.class);parse.setAccessible(true);ChannelCatalog.Group[] parsed=(ChannelCatalog.Group[])parse.invoke(null,m3u.toString().getBytes("UTF-8"),base+"/list.m3u");
            ChannelCatalog.Group[] groups=new ChannelCatalog.Group[24];System.arraycopy(parsed,0,groups,0,23);groups[23]=new ChannelCatalog.Group("空分组",ChannelCatalog.SOURCE_CUSTOM,new Channel[0]);
            main(()->{ChannelCatalog.GROUPS=groups;set("epgEnabled",true);set("epgManager",new EpgManager(activity));set("epgUrls",new String[]{base+"/guide.xml"});set("autoSwitchSource",false);});
            for(String id:TvUiPlugin.IDS){
                TvUiPlugin.select(c,id);until(()->(Integer)get("appliedTvUiRevision")==TvUiPlugin.revision(),5000,"Theme not applied");idle();
                main(()->{call("closeChannelList",new Class[]{});((java.util.Map)get("channelCursors")).clear();((java.util.Map)get("epgCursors")).clear();set("currentGroupIndex",0);set("currentSourceIndex",0);call("startChannel",new Class[]{int.class},0);});
                until(()->(Boolean)get("prepared")&&(Boolean)get("videoRenderingStarted"),20000,"No fixture frame");Object player=get("player");int request=(Integer)get("playRequestId");
                main(()->call("openChannelList",new Class[]{}));idle();
                key(KeyEvent.KEYCODE_DPAD_LEFT);exercise(id,"groupList",24);key(KeyEvent.KEYCODE_DPAD_RIGHT);exercise(id,"channelList",23);
                for(int i=0;i<5;i++)step(1);int savedFirst=list("channelList").getFirstVisiblePosition();
                key(KeyEvent.KEYCODE_DPAD_LEFT);step(1);key(KeyEvent.KEYCODE_DPAD_RIGHT);for(int i=0;i<3;i++)step(1);
                key(KeyEvent.KEYCODE_DPAD_LEFT);step(-1);key(KeyEvent.KEYCODE_DPAD_RIGHT);check(selected("channelList")==5&&list("channelList").getFirstVisiblePosition()==savedFirst,"Group did not restore channel cursor/page "+id);
                final ChannelCatalog.Group original=groups[0];Channel[] reordered=original.channels.clone();Channel moved=reordered[5];reordered[5]=reordered[9];reordered[9]=moved;
                main(()->{groups[0]=new ChannelCatalog.Group(original.title,original.source,reordered);call("showChannelMenu",new Class[]{int.class},0);});idle();check(selected("channelList")==9,"Channel reorder lost selected identity "+id);
                main(()->{groups[0]=original;call("showChannelMenu",new Class[]{int.class},0);});idle();check(selected("channelList")==5,"Restoring channel order lost identity");
                key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_RIGHT);guide("/guide.xml");
                EpgListAdapter adapter=(EpgListAdapter)get("epgAdapter");check(adapter.getCount()>20,"Insufficient programmes");
                while(selected("epgList")>0)step(-1);exercise(id,"epgList",adapter.getCount());
                for(int i=0;i<8;i++)step(1);long programme=adapter.getItem(selected("epgList")).startMillis;int epgFirst=list("epgList").getFirstVisiblePosition();
                key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_DPAD_RIGHT);check(adapter.getItem(selected("epgList")).startMillis==programme&&list("epgList").getFirstVisiblePosition()==epgFirst,"Guide return lost cursor/page "+id);
                guide("/guide-insert.xml");check(adapter.getItem(selected("epgList")).startMillis==programme,"Guide refresh moved selection identity "+id);
                guide("/guide-remove.xml");check(selected("epgList")>=0&&selected("epgList")<adapter.getCount(),"Removed programme left invalid selection");check(adapter.getItem(selected("epgList")).startMillis==programme+1800000L,"Removed programme did not choose chronological neighbor");
                guide("/empty.xml");check(adapter.getCount()==0,"Empty guide not displayed");step(1);step(-1);check(selected("epgList")==AdapterView.INVALID_POSITION,"Empty guide has a fake selected row");key(KeyEvent.KEYCODE_DPAD_LEFT);check(list("channelList").hasFocus(),"Empty guide trapped focus");
                guide("/guide.xml");
                key(KeyEvent.KEYCODE_DPAD_RIGHT);
                int future=-1;for(int i=0;i<adapter.getCount();i++)if(adapter.getItem(i).startMillis>System.currentTimeMillis()){future=i;break;}check(future>=0,"No future programme fixture");
                final int futureIndex=future;main(()->((RemoteListPager)get("epgPager")).show(list("epgList"),adapter.getCount(),futureIndex,futureIndex,adapter.navigationRowHeight(list("epgList"))));idle();
                key(KeyEvent.KEYCODE_DPAD_CENTER);check((Integer)get("playRequestId")==request&&((View)get("channelListPanel")).isShown(),"Future programme started playback");
                check(((TextView)get("epgStatus")).getText().toString().contains(new java.text.SimpleDateFormat("MM-dd",java.util.Locale.getDefault()).format(new java.util.Date(adapter.getItem(futureIndex).startMillis))),"Guide date header does not follow focused programme");
                key(KeyEvent.KEYCODE_DPAD_LEFT);
                // A swipe is a scroll, never a channel activation; next DPAD resumes from the visible viewport.
                touchDrag(list("channelList"));check((Integer)get("playRequestId")==request,"Touch scrolling switched playback");step(1);completeRows("channelList");
                main(()->{long now=SystemClock.uptimeMillis();for(int i=1;i<16;i++)activity.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN,i));});idle();int stopped=selected("channelList");SystemClock.sleep(300);check(selected("channelList")==stopped,"Held key left queued navigation");
                for(int repeat=1;repeat<=8;repeat++){int before=selected("channelList");SystemClock.sleep(130);final int r=repeat;main(()->activity.dispatchKeyEvent(new KeyEvent(0,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN,r)));idle();check(selected("channelList")==((before+1)%23),"Held repeat skipped rows");completeRows("channelList");}
                check(get("player")==player&&(Integer)get("playRequestId")==request,"Browsing changed playback "+id);
                shot(id+"-final");results.put(new JSONObject().put("theme",id).put("groupMemory",true).put("channelReorderIdentity",true).put("guideMemory",true).put("refreshIdentity",true).put("emptyGuideEscape",true).put("futureBlocked",true).put("dateHeader",true).put("touchToRemote",true).put("repeatBounded",true).put("samePlayer",true));
                key(KeyEvent.KEYCODE_DPAD_CENTER);until(()->(Integer)get("playRequestId")>request&&!((View)get("channelListPanel")).isShown(),5000,"OK did not activate/close channel list");
                ready();main(()->call("openChannelList",new Class[]{}));idle();
                final int[] tap={0,0};main(()->{View row=list("channelList").getChildAt(0);row.getLocationOnScreen(tap);tap[0]+=row.getWidth()/2;tap[1]+=row.getHeight()/2;});int beforeTap=(Integer)get("playRequestId");long down=SystemClock.uptimeMillis();
                MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,tap[0],tap[1],0),release=MotionEvent.obtain(down,down+50,MotionEvent.ACTION_UP,tap[0],tap[1],0);press.setSource(InputDevice.SOURCE_TOUCHSCREEN);release.setSource(InputDevice.SOURCE_TOUCHSCREEN);sendPointerSync(press);sendPointerSync(release);press.recycle();release.recycle();
                until(()->(Integer)get("playRequestId")>beforeTap&&!((View)get("channelListPanel")).isShown(),5000,"Touch did not activate/close channel list");ready();
            }
            for(String preset:new String[]{"small","large"})for(String id:TvUiPlugin.IDS){
                main(()->call("handleWebSettings",new Class[]{JSONObject.class},new JSONObject().put("uiScaleMode",preset)));TvUiPlugin.select(c,id);until(()->(Integer)get("appliedTvUiRevision")==TvUiPlugin.revision(),5000,"Resize theme pending");idle();
                main(()->call("openChannelList",new Class[]{}));idle();int index=selected("channelList");
                for(int i=0;i<10;i++){step(-1);index=(index+22)%23;check(selected("channelList")==index,"Resized UP moved incorrectly "+id+"/"+preset);completeRows("channelList");}
                for(int i=0;i<10;i++){step(1);index=(index+1)%23;check(selected("channelList")==index,"Resized DOWN moved incorrectly "+id+"/"+preset);completeRows("channelList");}
                main(()->call("closeChannelList",new Class[]{}));results.put(new JSONObject().put("theme",id).put("preset",preset).put("resizePaging",true));
            }
            result.putString("stream","PASS: 6 UIs; group/channel/programme full-page forward/back, short final pages, channel wrap, bounded group/EPG ends, per-group memory, EPG identity refresh/removal/empty, touch-to-remote, held-key throttle, no autoplay, real OK playback; assertions="+assertions+"\n");code=-1;
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{
            try{try(FileOutputStream f=new FileOutputStream(new File(out,"results.json"))){f.write(results.toString(2).getBytes("UTF-8"));}}catch(Exception ignored){}
            try{if(activity!=null){final String size=oldSize;main(()->{call("handleWebSettings",new Class[]{JSONObject.class},new JSONObject().put("uiScaleMode",size));activity.finish();});}TvUiPlugin.select(getTargetContext(),oldTheme);}catch(Exception ignored){}ChannelCatalog.GROUPS=saved;
        }
        finish(code,result);
    }
}
