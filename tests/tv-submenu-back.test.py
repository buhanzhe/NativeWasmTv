"""Exercise actual submenu wrapper and shared renderer key handling with tiny Android stubs."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
def method(text, signature):
    start=text.index(signature); end=text.index('{',start)+1; depth=1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[start:end]
menu=(root/'app/src/main/java/xiao/bu/tv/TvUiMenu.java').read_text(encoding='utf8')
ui=(root/'app/src/main/java/xiao/bu/tv/skin/SharedTvUi.java').read_text(encoding='utf8')
ui=ui[ui.index('    private final class Menu extends'):]
java=r'''import java.util.*;
class Check {
 static class Bundle {Map<String,Object> m=new HashMap<>();Bundle(){}Bundle(Bundle b){m.putAll(b.m);}void putStringArray(String k,String[] v){m.put(k,v);}String[] getStringArray(String k){return (String[])m.get(k);}void putBoolean(String k,boolean v){m.put(k,v);}boolean getBoolean(String k){return Boolean.TRUE.equals(m.get(k));}int getInt(String k,int d){return m.containsKey(k)?(Integer)m.get(k):d;}void putInt(String k,int v){m.put(k,v);}}
 static class View {boolean focus;void requestFocus(){focus=true;}boolean hasFocus(){return focus;}int getSelectedItemPosition(){return 0;}void setSelection(int n){}}
 static class TvUi {interface Events {Bundle options(int c);void action(int c,int o);void dismiss();}}
 static class Host implements TvUi.Events {int backs,actions;public Bundle options(int c){Bundle b=new Bundle();b.putStringArray("items",new String[]{"option"});return b;}public void action(int c,int o){actions++;}public void dismiss(){backs++;}}
 Bundle shown;TvUi.Events events;
 void show(View a,String p,Bundle s,TvUi.Events e){shown=s;events=e;}
 WRAPPER
 static class KeyEvent {static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_BACK=4,KEYCODE_MENU=82,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_NUMPAD_ENTER=160;int action,key,repeat;KeyEvent(int a,int k,int r){action=a;key=k;repeat=r;}int getAction(){return action;}int getKeyCode(){return key;}int getRepeatCount(){return repeat;}}
 static class Parent {public boolean dispatchKeyEvent(KeyEvent e){return false;}}
 static class Renderer extends Parent {boolean submenu;View options=new View(),categories=new View();Host events=new Host();Bundle choices=new Bundle();int category;void inputMode(boolean r){}
 KEYS
 }
 static void ok(boolean v,String why){if(!v)throw new AssertionError(why);}
 public static void main(String[] args){
  Check menu=new Check();Host host=new Host();Bundle original=new Bundle();original.putStringArray("categories",new String[]{"local"});
  menu.showSubmenu(new View(),original,host);ok(original.getStringArray("categories").length==1,"Do not mutate caller categories");
  ok(menu.shown.getBoolean("submenu")&&menu.shown.getStringArray("categories")[1].equals("返回上级"),"Visible back entry");
  ok(menu.events.options(1).getBoolean("openOnClick"),"Return is actionable directly");menu.events.action(1,-1);ok(host.backs==1&&host.actions==0,"Return delegates to parent only");
  menu.events.action(0,0);ok(host.actions==1,"Original choices remain mapped");menu.events.dismiss();ok(host.backs==2,"Dismiss delegates to parent");
  for(boolean focused:new boolean[]{false,true}){Renderer r=new Renderer();r.submenu=true;r.options.focus=focused;
   r.dispatchKeyEvent(new KeyEvent(0,4,0));ok(r.events.backs==1&&!r.categories.focus,"One Back returns immediately regardless of column");
   r.dispatchKeyEvent(new KeyEvent(0,4,1));r.dispatchKeyEvent(new KeyEvent(1,4,0));ok(r.events.backs==1,"Held key and key-up cannot pop another level");}
  Renderer main=new Renderer();main.options.focus=true;main.dispatchKeyEvent(new KeyEvent(0,4,0));ok(main.categories.focus&&main.events.backs==0,"Main menu focus navigation preserved");
  System.out.println("PASS submenu return row, parent routing, single Back from either column, repeat/up suppression, main menu behavior");
 }
}'''
java=java.replace('WRAPPER',method(menu,'    void showSubmenu(')).replace('KEYS',method(ui,'        public boolean dispatchKeyEvent('))
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'Check.java';p.write_text(java,encoding='utf8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'Check'],check=True)
