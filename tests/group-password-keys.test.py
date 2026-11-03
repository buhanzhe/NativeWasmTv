"""Run real dialog key handling and the Activity modal gate, including final key-up."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
dialog=(root/'app/src/main/java/xiao/bu/tv/GroupPasswordDialog.java').read_text(encoding='utf8')
activity=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
def method(source,signature):
    start=source.index(signature);at=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[at]=='{')-(source[at]=='}');at+=1
    return source[start:at].replace('@Override ','')
gate=activity[activity.index('    public boolean dispatchKeyEvent(KeyEvent event) {'):]
gate=gate[:gate.index('        if(receiverRenderOnly)')]+ '        return false;\n    }'
java=r'''public class PasswordKeyCheck {
 static class KeyEvent {
  static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_0=7,KEYCODE_9=16,KEYCODE_NUMPAD_0=144,KEYCODE_NUMPAD_9=153,
  KEYCODE_DEL=67,KEYCODE_FORWARD_DEL=112,KEYCODE_ENTER=66,KEYCODE_NUMPAD_ENTER=160;
  int action,key,repeat;KeyEvent(int action,int key){this.action=action;this.key=key;}
  int getKeyCode(){return key;}int getAction(){return action;}int getRepeatCount(){return repeat;}
 }
 static class Activity {}
 static class MainActivity extends Activity {
  int passwordKeyUntilUp=-1;GroupPasswordDialog groupPasswordDialog;
  RETAIN
  GATE
 }
 static class Text {String text="";void setText(String v){text=v;}void setContentDescription(String v){}void requestFocus(){}}
 static class BaseDialog {boolean showing=true;boolean isShowing(){return showing;}boolean dispatchKeyEvent(KeyEvent e){return false;}public void dismiss(){showing=false;}}
 interface Unlock {boolean submit(String value);}
 static class GroupPasswordDialog extends BaseDialog {
  Activity activity;int length=4;StringBuilder digits=new StringBuilder();Text confirm=new Text(),status=new Text(),input=new Text();Unlock unlock;
  METHODS
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static KeyEvent key(int action,int digit){return new KeyEvent(action,KeyEvent.KEYCODE_0+digit);}
 static void digit(MainActivity a,int value){GroupPasswordDialog d=a.groupPasswordDialog;check(d.dispatchKeyEvent(key(0,value)),"digit down consumed");check(d.isShowing(),"typing a digit must never dismiss the dialog");check(d.dispatchKeyEvent(key(1,value)),"dialog consumes digit up");}
 static void confirm(MainActivity a,int code){
  GroupPasswordDialog d=a.groupPasswordDialog;
  check(a.dispatchKeyEvent(new KeyEvent(0,code)),"confirmation consumed by modal");
  check(a.dispatchKeyEvent(new KeyEvent(1,code)),"confirmation release consumed without reaching channel controls");
  check(a.passwordKeyUntilUp==-1,"confirmation release latch cleared");
 }
 static int submissions;
 static GroupPasswordDialog dialog(MainActivity a,String password){
  GroupPasswordDialog d=new GroupPasswordDialog();d.activity=a;d.length=password.length();
  d.unlock=v->{submissions++;return v.equals(password);};a.groupPasswordDialog=d;return d;
 }
 public static void main(String[] args){
  MainActivity a=new MainActivity();
  for(String password:new String[]{"0709","10086","01234567"})for(boolean numpad:new boolean[]{false,true}){
   GroupPasswordDialog d=dialog(a,password);int before=submissions;
   confirm(a,KeyEvent.KEYCODE_ENTER);check(submissions==before&&d.isShowing(),"empty confirmation never attempts unlock");
   for(int i=0;i<password.length();i++){
    int code=(numpad?KeyEvent.KEYCODE_NUMPAD_0:KeyEvent.KEYCODE_0)+password.charAt(i)-'0';
    check(a.dispatchKeyEvent(new KeyEvent(0,code))&&a.dispatchKeyEvent(new KeyEvent(1,code)),"all password digits stay in modal");
   }
   check(submissions==before&&d.isShowing()&&d.digits.toString().equals(password),"complete correct password must not auto-submit");
   check(d.input.text.length()==password.length()&&d.input.text.charAt(0)=='●',"complete entry remains masked");
   digit(a,9);check(d.digits.toString().equals(password)&&submissions==before,"extra digit cannot submit full entry");
   a.dispatchKeyEvent(new KeyEvent(0,KeyEvent.KEYCODE_DEL));a.dispatchKeyEvent(new KeyEvent(1,KeyEvent.KEYCODE_DEL));
   int wrong=password.charAt(password.length()-1)=='9'?8:9;digit(a,wrong);
   check(submissions==before&&d.isShowing()&&d.status.text.isEmpty(),"full wrong password also waits for confirmation");
   confirm(a,KeyEvent.KEYCODE_ENTER);
   check(submissions==before+1&&d.isShowing()&&d.digits.length()==0&&d.status.text.contains("密码错误"),"confirm rejects wrong password and permits retry");
   for(char ch:password.toCharArray())digit(a,ch-'0');
   check(submissions==before+1&&d.isShowing(),"correct retry still waits for confirmation");
   confirm(a,numpad?KeyEvent.KEYCODE_NUMPAD_ENTER:KeyEvent.KEYCODE_ENTER);
   check(submissions==before+2&&!d.isShowing(),"explicit Enter confirms exactly once");
   a.groupPasswordDialog=null;check(!a.dispatchKeyEvent(key(0,8)),"numeric channel controls resume after confirmation");
  }
  GroupPasswordDialog d=dialog(a,"0709");int before=submissions;
  digit(a,0);KeyEvent held=key(0,0);held.repeat=1;d.dispatchKeyEvent(held);check(d.digits.toString().equals("0"),"held numeric key does not repeat");
  d.append('7');d.append('0');d.append('9');check(d.isShowing()&&submissions==before,"onscreen keypad also waits");
  d.submit();check(!d.isShowing()&&submissions==before+1,"onscreen confirm handler unlocks");
  d=dialog(a,"0709");for(char ch:"0709".toCharArray())digit(a,ch-'0');before=submissions;d.dismiss();check(submissions==before&&d.digits.length()==0,"cancel after correct digits grants nothing");
  a.groupPasswordDialog=null;a.retainPasswordKeyRelease(KeyEvent.KEYCODE_ENTER);
  KeyEvent heldFinal=new KeyEvent(0,KeyEvent.KEYCODE_ENTER);heldFinal.repeat=1;
  check(a.dispatchKeyEvent(heldFinal),"held confirmation remains blocked after popup closes");
  check(!a.dispatchKeyEvent(new KeyEvent(0,KeyEvent.KEYCODE_ENTER))&&a.passwordKeyUntilUp==-1,"fresh press resumes if dialog lost key up");
  System.out.println("PASS explicit confirmation for 4/5/8 digits, physical/numpad/touch input, wrong retry, delete, cancel, repeats, modal isolation and confirmation release");
 }
}'''
java=java.replace('RETAIN',method(activity,'    void retainPasswordKeyRelease(')).replace('GATE',gate)
java=java.replace('METHODS','\n'.join(method(dialog,s) for s in (
    '    private void append(', '    private void erase(', '    private void updateInput(',
    '    private void submit(', 'public boolean dispatchKeyEvent(', 'public void dismiss(')))
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    p=Path(folder)/'PasswordKeyCheck.java';p.write_text(java,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'PasswordKeyCheck'],check=True)
