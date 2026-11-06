"""Execute the real popup show/key/lifetime methods against window lifecycle races."""
import os
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
source = (root / 'app/src/main/java/xiao/bu/tv/TvUiMenu.java').read_text(encoding='utf8')

def method(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

methods = '\n'.join(method(s) for s in [
    '    void dismiss()', '    boolean visible()', '    boolean confirmExitFromBack()',
    '    boolean dispatchKeyEvent(', '    void show(View anchor,',
    '    private static Activity findActivity(', '    private static boolean canShow('])
methods = methods.replace('android.util.Log', 'Log').replace('xiao.bu.tv.AppLog', 'Log').replace('android.os.Build', 'Build')
methods = methods.replace('xiao.bu.tv.skin.SharedTvUi', 'SharedTvUi')
java = r'''
public class Check {
 static class Context {}
 static class ContextWrapper extends Context {Context base;ContextWrapper(Context b){base=b;}Context getBaseContext(){return base;}}
 static class Activity extends ContextWrapper {boolean finishing,destroyed;Window window=new Window();Activity(){super(new Context());}boolean isFinishing(){return finishing;}boolean isDestroyed(){return destroyed;}Window getWindow(){return window;}}
 static class Window {View decor=new View(new Context());View getDecorView(){return decor;}}
 static class Build {static class VERSION {static int SDK_INT=25;}}
 static class Log {static void w(String a,String b,Throwable t){}}
 static class Bundle {}
 static class View {Context context;Object token=new Object();boolean focused;View(Context c){context=c;}Context getContext(){return context;}Object getWindowToken(){return token;}int getHeight(){return 720;}int getWidth(){return 1280;}int getSystemUiVisibility(){return 0;}void setSystemUiVisibility(int n){}void requestFocus(){focused=true;}boolean dispatchKeyEvent(KeyEvent e){return true;}}
 static class ViewGroup {static class LayoutParams {static int MATCH_PARENT=-1;}}
 static class Color {static int TRANSPARENT=0;}
 static class ColorDrawable {ColorDrawable(int c){}}
 static class Gravity {static int RIGHT=1,TOP=2,LEFT=4;}
 static class WindowManager {static class BadTokenException extends RuntimeException {}static class LayoutParams {static int SOFT_INPUT_ADJUST_NOTHING=0;}}
 static class KeyEvent {static int KEYCODE_VOLUME_UP=24,KEYCODE_VOLUME_DOWN=25,KEYCODE_VOLUME_MUTE=164,KEYCODE_BACK=4,ACTION_DOWN=0;int key,action,repeat;KeyEvent(int a,int k,int r){action=a;key=k;repeat=r;}int getKeyCode(){return key;}int getAction(){return action;}int getRepeatCount(){return repeat;}}
 static class PopupWindow {
  static int shows;static boolean fail,detachOnDismiss;static final int INPUT_METHOD_NOT_NEEDED=0;View content;boolean showing,focusable;Runnable onDismiss;
  PopupWindow(View c,int w,int h,boolean f){content=c;focusable=f;}
  void dismiss(){showing=false;if(onDismiss!=null)onDismiss.run();if(detachOnDismiss)throw new IllegalArgumentException();}
  boolean isShowing(){return showing;}View getContentView(){return content;}void setOnDismissListener(Runnable r){onDismiss=r;}
  void setBackgroundDrawable(ColorDrawable d){}void setOutsideTouchable(boolean b){}void setAnimationStyle(int n){}void setInputMethodMode(int n){}void setSoftInputMode(int n){}
  void showAtLocation(View a,int g,int x,int y){shows++;showing=true;if(fail)throw new WindowManager.BadTokenException();}
  void setFocusable(boolean b){focusable=b;}void update(){}
 }
 static class TvUi {interface Events {void action(int c,int o);}}
 static class SharedTvUi {interface MenuWidth {int preferredWidth(int h);}}
 static class Provider {Runnable duringCreate;int creates;View create(Context c,String p,Bundle s,Object o,TvUi.Events e){creates++;if(duringCreate!=null)duringCreate.run();return new View(c);}}
 static class TvUiStyle {String id="ui2";Provider provider=new Provider();}
 static class TvUiPlugin {static TvUiStyle style=new TvUiStyle();static int rejected;static TvUiStyle current(){return style;}static void rejectRenderer(Context c,Throwable t){rejected++;}}
 static class TvUiChrome {static float scale(View v){return 1;}}
 static void inheritWindow(Activity a,View v){}
 private PopupWindow popup;private TvUi.Events exitEvents;
 METHODS
 static void ok(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  Check menu=new Check();Activity a=new Activity();View anchor=new View(a);int[] exits={0};TvUi.Events events=(c,o)->{if(o==1)exits[0]++;};
  menu.show(null,"exit",new Bundle(),events);ok(PopupWindow.shows==0,"Null anchor");
  anchor.token=null;menu.show(anchor,"exit",new Bundle(),events);ok(PopupWindow.shows==0,"Not yet attached");
  anchor.token=new Object();a.finishing=true;menu.show(anchor,"exit",new Bundle(),events);ok(PopupWindow.shows==0,"Finishing host");
  a.finishing=false;a.destroyed=true;menu.show(anchor,"exit",new Bundle(),events);ok(PopupWindow.shows==0,"Destroyed host");a.destroyed=false;
  menu.show(new View(new Context()),"exit",new Bundle(),events);ok(PopupWindow.shows==0,"Non-activity host");
  TvUiPlugin.style.provider.duringCreate=()->anchor.token=null;
  menu.show(anchor,"exit",new Bundle(),events);ok(PopupWindow.shows==0,"Detach during renderer creation");
  TvUiPlugin.style.provider.duringCreate=null;anchor.token=new Object();
  PopupWindow.fail=true;PopupWindow.detachOnDismiss=true;
  menu.show(anchor,"exit",new Bundle(),events);ok(!menu.visible()&&menu.exitEvents==null,"Bad token clears popup and exit callbacks");ok(TvUiPlugin.rejected==0,"Window failure must not reject theme");
  PopupWindow.fail=false;PopupWindow.detachOnDismiss=false;
  menu.show(new View(new ContextWrapper(a)),"exit",new Bundle(),events);ok(menu.visible()&&!menu.popup.focusable,"Wrapped host recovers and exit stays non-focusable");
  menu.dispatchKeyEvent(new KeyEvent(0,4,0));menu.dispatchKeyEvent(new KeyEvent(0,4,1));menu.dispatchKeyEvent(new KeyEvent(1,4,0));ok(exits[0]==1,"Back confirms once; repeat/up ignored");
  menu.dismiss();ok(!menu.visible()&&!menu.confirmExitFromBack(),"Dismiss clears stale callback");
  menu.show(anchor,"menu",new Bundle(),events);ok(menu.visible()&&menu.popup.focusable,"Normal menu still opens");menu.dismiss();
  Build.VERSION.SDK_INT=14;menu.show(anchor,"exit",new Bundle(),events);ok(menu.visible(),"Pre-17 host supported");
  System.out.println("PASS popup lifecycle: missing token, closing/destroyed/wrapped host, detach race, BadToken cleanup, recovery, Back confirmation and API14");
 }
}
'''.replace('METHODS', methods)
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Users/Administrator/.jdks/jbr-17.0.14')) / 'bin'
with tempfile.TemporaryDirectory() as folder:
    file = Path(folder) / 'Check.java'
    file.write_text(java, encoding='utf8')
    subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', str(file)], check=True)
    subprocess.run([str(jdk / 'java.exe'), '-cp', folder, 'Check'], check=True)
