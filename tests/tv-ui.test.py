"""Verify built-in theme delivery and real menu action/selection mappings."""
import hashlib,json,os,subprocess,tempfile,zipfile
from pathlib import Path
root=Path(__file__).resolve().parent.parent
# Themes are host code; no APK-side DEX archives or dynamic theme loader remain.
assert not list((root/'app/src/main/assets/tv-ui').glob('*.jar'))
loader=(root/'app/src/main/java/xiao/bu/tv/TvUiPlugin.java').read_text(encoding='utf-8')
assert 'DexClassLoader' not in loader and 'new BuiltinTheme' in loader
assert '银雾玻璃' in loader and '影院红黑' not in loader
with tempfile.TemporaryDirectory() as folder:
    out=Path(folder)
    files={
        'android/os/Build.java':'package android.os;public class Build {public static class VERSION {public static int SDK_INT=28;}}',
        'android/os/Bundle.java':'''package android.os; import java.util.*;public class Bundle {
         Map<String,Object> m=new HashMap<>();public void putAll(Bundle b){m.putAll(b.m);}public Object get(String k){return m.get(k);}public void putDouble(String k,double v){m.put(k,v);}public void putInt(String k,int v){m.put(k,v);}public void putBoolean(String k,boolean v){m.put(k,v);}public void putString(String k,String v){m.put(k,v);}public void putStringArray(String k,String[] v){m.put(k,v);}
         public int getInt(String k){return m.containsKey(k)?(Integer)m.get(k):0;}public int getInt(String k,int d){return m.containsKey(k)?(Integer)m.get(k):d;}public boolean getBoolean(String k){return m.containsKey(k)?(Boolean)m.get(k):false;}
         public void putBooleanArray(String k,boolean[] v){m.put(k,v);}public boolean[] getBooleanArray(String k){return (boolean[])m.get(k);}
         public String getString(String k){return (String)m.get(k);}public String[] getStringArray(String k){return (String[])m.get(k);}}''',
        'android/content/Context.java':'package android.content;public class Context {}',
        'android/view/View.java':'package android.view;public class View {}',
        'xiao/bu/tv/skin/TvUi.java':(root/'app/src/main/java/xiao/bu/tv/skin/TvUi.java').read_text(encoding='utf-8'),
        'xiao/bu/tv/TvUiBrowserMenu.java':(root/'app/src/main/java/xiao/bu/tv/TvUiBrowserMenu.java').read_text(encoding='utf-8'),
        'xiao/bu/tv/SourceSwitchTimeout.java':(root/'app/src/main/java/xiao/bu/tv/SourceSwitchTimeout.java').read_text(encoding='utf-8'),
        'xiao/bu/tv/TvUiSettingsMenu.java':(root/'app/src/main/java/xiao/bu/tv/TvUiSettingsMenu.java').read_text(encoding='utf-8'),
        'xiao/bu/tv/TvUiMenu.java':'package xiao.bu.tv;class TvUiMenu {int dismissed;void dismiss(){dismissed++;}}',
        'xiao/bu/tv/TvUiPlugin.java':'package xiao.bu.tv;class TvUiPlugin {static String[] IDS={"builtin","ui2","ui3","ui4"};}',
        'xiao/bu/tv/Check.java':'''package xiao.bu.tv;import android.os.Bundle;public class Check {
          static void ok(boolean b){if(!b)throw new AssertionError();}
          static class Host implements TvUiSettingsMenu.Host {
            Bundle state=new Bundle(),groups=new Bundle();String key,where,groupId;boolean visible;Object value;int source=-1;
            Host(){state.putInt("sources",3);state.putInt("source",2);state.putString("decode","software");state.putString("ratio","4:3");state.putInt("timeout",10);state.putString("version","1.8.0");groups.putStringArray("ids",new String[]{"favorites","group:a","group:b"});groups.putStringArray("names",new String[]{"我的收藏","央视频道","已隐藏分组"});groups.putBooleanArray("visible",new boolean[]{true,true,false});}
            public Bundle groups(){return groups;}public void group(String id,boolean value){groupId=id;visible=value;}
            public Bundle state(){return state;}public void setting(String k,Object v){key=k;value=v;}public void source(int i){source=i;}public void navigate(String s){where=s;}
          }
          static class BrowserHost implements TvUiBrowserMenu.Host {
            Bundle state=new Bundle(),last;int calls,backs;
            BrowserHost(){state.putBoolean("liteBrowserSupported",true);state.putString("webViewResolution","720p");state.putString("webViewPageCoverStyle","status");state.putString("webViewUserAgent","windows");state.putString("webViewBrowserVersion","native");state.putString("webSourceEngine","system");state.putInt("webViewPageScalePercent",100);}
            public Bundle state(){return state;}public void settings(Bundle patch){last=patch;state.putAll(patch);if(patch.get("webViewPageScale")!=null)state.putInt("webViewPageScalePercent",(int)Math.round((Double)patch.get("webViewPageScale")*100));calls++;}public void back(){backs++;}
          }
          public static void main(String[] args){
            for(String id:new String[]{"ui2","ui3","ui4","ui5","ui6"}){
              Host h=new Host();TvUiMenu window=new TvUiMenu();TvUiSettingsMenu menu=new TvUiSettingsMenu(h,window);
              String[] categories=menu.initial().getStringArray("categories");ok(categories.length==9);ok(categories[8].equals("版本信息"));
              for(int c=0;c<categories.length;c++){String[] rows=menu.options(c).getStringArray("items");ok(rows.length>0);for(int i=0;i<rows.length;i++)menu.action(c,i);}
              ok(categories[7].equals("网页配置"));ok(menu.options(7).getStringArray("items").length==9);ok(menu.options(7).getStringArray("items")[6].equals("加载图片"));menu.action(7,4);ok(h.where.equals("browser:4"));
              Bundle sources=menu.options(0);ok(sources.getStringArray("items").length==3&&sources.getInt("selected")==2);menu.action(0,1);ok(h.source==1);
              int ratio=1;ok(menu.options(ratio).getInt("selected")==4);menu.action(ratio,2);ok(h.key.equals("videoScaleMode")&&h.value.equals("18:9"));menu.action(ratio,3);ok(h.value.equals("16:9"));menu.action(ratio,4);ok(h.value.equals("4:3"));menu.action(ratio,5);ok(h.value.equals("fill"));h.state.putString("ratio","fill");ok(menu.options(ratio).getInt("selected")==5);
              int decode=2;ok(menu.options(decode).getInt("selected")==2);menu.action(decode,1);ok(h.key.equals("decodeMode")&&h.value.equals("hardware"));
              menu.action(3,2);ok(h.key.equals("autoSwitchSourceSeconds")&&h.value.equals(10));
              ok(menu.options(3).getStringArray("items").length==7);for(int seconds=0;seconds<=30;seconds+=5){h.state.putInt("timeout",seconds);ok(menu.options(3).getInt("selected")==seconds/5);menu.action(3,seconds/5);ok(h.value.equals(seconds));}
              ok(!SourceSwitchTimeout.valid(-5)&&!SourceSwitchTimeout.valid(31)&&!SourceSwitchTimeout.valid(7));
              menu.action(5,1);ok(h.key.equals("showNetworkSpeed")&&h.value.equals(true));
              menu.action(5,3);ok(h.key.equals("showPlaybackPosition")&&h.value.equals(true));
              ok(menu.options(5).getStringArray("items").length==8);ok(menu.options(5).getStringArray("items")[4].equals("界面大小"));menu.action(5,4);ok(h.where.equals("ui-size"));menu.action(5,5);ok(h.where.equals("hdr"));menu.action(5,6);ok(h.where.equals("guide"));menu.action(5,7);ok(h.where.equals("render"));
              Bundle groups=menu.options(4);ok(!groups.getBoolean("openOnClick"));ok(groups.getStringArray("items").length==3);ok(groups.getStringArray("items")[0].equals("我的收藏 ●"));ok(groups.getStringArray("items")[2].endsWith(" ○"));
              int dismisses=window.dismissed;h.where=null;h.groupId=null;menu.action(4,-1);ok(h.groupId==null);menu.action(4,0);ok(h.groupId.equals("favorites")&&!h.visible&&h.where==null&&window.dismissed==dismisses);
              menu.action(4,2);ok(h.groupId.equals("group:b")&&h.visible);
              Bundle replacement=new Bundle();replacement.putStringArray("ids",new String[]{"favorites","group:b"});replacement.putStringArray("names",new String[]{"我的收藏","已隐藏分组"});replacement.putBooleanArray("visible",new boolean[]{false,true});h.groups=replacement;
              menu.action(4,1);ok(h.groupId.equals("group:a"));
              ok(menu.options(4).getStringArray("items")[0].endsWith(" ○"));menu.action(4,1);ok(h.groupId.equals("group:b")&&!h.visible);
              ok(!menu.options(6).getBoolean("openOnClick"));menu.action(6,0);ok(h.key.equals("autoStart")&&h.value.equals(true));
              menu.action(6,1);ok(h.key.equals("reverseKeys")&&h.value.equals(true));
              menu.action(6,2);ok(h.where.equals("styles"));menu.action(6,3);ok(h.where.equals("radio-styles"));menu.action(6,4);ok(h.where.equals("manage"));menu.action(6,5);ok(h.where.equals("cache"));
              ok(menu.options(6).getStringArray("items").length==9);
              menu.action(6,8);ok(h.key.equals("updatePopupEnabled")&&h.value.equals(true));h.state.putBoolean("updatePopupEnabled",true);menu.action(6,8);ok(h.value.equals(false));
              h.key="unchanged";h.where=null;menu.action(6,9);menu.action(6,10);ok(h.key.equals("unchanged")&&h.where==null);
              ok(menu.options(7).getStringArray("items")[3].equals("浏览器 UA"));
              h.state.putBoolean("liteBrowserSupported",true);ok(menu.options(6).getStringArray("items").length==9);
              ok(menu.options(7).getStringArray("items").length==10&&menu.options(7).getStringArray("items")[6].equals("网页内核"));
              menu.action(7,3);ok(h.where.equals("browser:3"));menu.action(7,6);ok(h.where.equals("browser:6"));
              h.state.putBoolean("liteBrowserSupported",false);ok(menu.options(6).getStringArray("items").length==9);
              menu.action(6,6);ok(h.key.equals("rememberChannelSource")&&h.value.equals(true));h.state.putBoolean("rememberChannelSource",true);menu.action(6,6);ok(h.value.equals(false));
              menu.action(6,7);ok(h.key.equals("rememberChannelDecode")&&h.value.equals(true));h.state.putBoolean("rememberChannelDecode",true);menu.action(6,7);ok(h.value.equals(false));
              ok(menu.options(6).getStringArray("items")[0].endsWith(" ○"));h.state.putBoolean("boot",true);ok(menu.options(6).getStringArray("items")[0].endsWith(" ●"));
              ok(menu.options(8).getStringArray("items")[1].equals("检查更新"));ok(menu.options(6).getStringArray("items")[2].equals("界面主题"));ok(menu.options(6).getStringArray("items")[3].equals("唱片界面"));ok(menu.options(6).getStringArray("items")[4].equals("网页管理"));ok(menu.options(8).getStringArray("items").length==2);
              h.state.putString("updateState","idle");h.state.putString("updateMessage","点击检查更新");
              String[] versionRows=menu.options(8).getStringArray("items");int updatePrompts=0;for(String text:versionRows)if(text.contains("检查更新"))updatePrompts++;
              ok(updatePrompts==1&&versionRows.length==2);
              h.state.putString("updateState","checking");h.state.putString("updateMessage","正在检查更新…");
              ok(menu.options(8).getStringArray("items")[1].equals("正在检查…")&&menu.options(8).getStringArray("items").length==2);
              h.state.putString("updateState","error");h.state.putString("updateMessage","检查失败：网络超时");ok(menu.options(8).getStringArray("items").length==2&&menu.options(8).getStringArray("items")[1].equals("检查更新"));
              h.state.putString("updateState","idle");
              menu.action(8,1);ok(h.where.equals("update"));menu.action(8,2);ok(h.where.equals("update"));
              h.state.putString("updateState","available");h.state.putString("updateVersion","1.9.0");ok(menu.options(8).getStringArray("items").length==2&&menu.options(8).getStringArray("items")[1].equals("更新至 1.9.0"));menu.action(8,1);ok(h.where.equals("install-update"));
              ok(menu.options(-1).getStringArray("items").length==0);menu.action(100,0);
            }
            BrowserHost bh=new BrowserHost();TvUiBrowserMenu browser=new TvUiBrowserMenu(bh);
            ok(browser.initial(4,true).getInt("category")==4&&browser.initial(4,true).getBoolean("focusOptions"));
            for(int category=0;category<TvUiBrowserMenu.TITLES.length;category++){
              int count=browser.options(category).getStringArray("items").length;
              for(int option=0;option<count;option++){int calls=bh.calls;browser.action(category,option);ok(bh.calls==calls+1&&browser.options(category).getInt("selected")==option);}
            }
            browser.action(1,2);ok(bh.last.getBoolean("webViewPageCover")&&bh.last.getString("webViewPageCoverStyle").equals("black"));
            browser.action(1,0);ok(!bh.last.getBoolean("webViewPageCover")&&bh.last.get("webViewPageCoverStyle")==null);
            ok(browser.options(3).getStringArray("items").length==4);int uaCalls=bh.calls;browser.action(3,4);ok(bh.calls==uaCalls);
            browser.action(3,2);ok(bh.last.getString("webViewUserAgent").equals("ipad"));browser.action(4,3);ok(bh.last.getString("webViewBrowserVersion").equals("138"));
            browser.action(4,4);ok(bh.last.getString("webViewBrowserVersion").equals("155"));
            browser.action(5,0);ok(((Double)bh.last.get("webViewPageScale"))==.5d);
            browser.action(9,0);ok(Boolean.FALSE.equals(bh.last.get("webViewAutoPlay"))&&browser.options(9).getInt("selected")==0);
            browser.action(9,1);ok(Boolean.TRUE.equals(bh.last.get("webViewAutoPlay"))&&browser.options(9).getInt("selected")==1);
            bh.state.putBoolean("liteBrowserSupported",false);
            String[] visible=browser.initial(9,true).getStringArray("categories");ok(visible.length==9&&visible[6].equals("加载图片")&&visible[8].equals("自动播放"));
            ok(browser.initial(9,true).getInt("category")==8);
            browser.action(6,1);ok(Boolean.TRUE.equals(bh.last.get("webViewLoadImages"))&&bh.last.get("webSourceEngine")==null);
            browser.action(8,0);ok(Boolean.FALSE.equals(bh.last.get("webViewAutoPlay")));
            int calls=bh.calls;
            browser.action(9,0);browser.action(-1,0);browser.action(100,0);browser.action(0,-1);browser.action(0,999);ok(bh.calls==calls);browser.dismiss();ok(bh.backs==1);
            System.out.println("PASS browser menu: all ten categories/options, autoplay, persisted selection, atomic mask patch, UA/version/scale, unsupported core and invalid inputs");
            System.out.println("PASS 6 built-in themes and 5 complete menu action models, ratio/decoder/source/timeout/settings");
          }
        }'''
    }
    for name,source in files.items():
        p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source,encoding='utf-8')
    java=Path(os.environ.get('JAVA_HOME',root/'.codex-tmp/jdk8/runtime/jdk8u504-b01'))/'bin'
    subprocess.run([str(java/'javac'),'-encoding','UTF-8','-d',str(out)]+[str(out/p) for p in files],check=True)
    subprocess.run([str(java/'java'),'-cp',str(out),'xiao.bu.tv.Check'],check=True)
