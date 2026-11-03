from pathlib import Path
import tempfile,subprocess
root=Path(__file__).resolve().parents[1]
files={
'android/os/Build.java':'package android.os; public class Build {public static class VERSION {public static int SDK_INT=33;}}',
'android/util/Log.java':'package android.util; public class Log {public static int w(String t,String s){return 0;}}',
'android/view/InputDevice.java':'package android.view; public class InputDevice {public static boolean present;public static InputDevice getDevice(int id){return present?new InputDevice():null;}}',
'android/view/MotionEvent.java':'package android.view;public class MotionEvent {public static final int ACTION_BUTTON_PRESS=11,BUTTON_PRIMARY=1;public static MotionEvent obtain(long a,long b,int c,float d,float e,int f){return new MotionEvent();}public void recycle(){}public void setActionButton(int button){}}',
'xiao/bu/tv/Check.java':'''package xiao.bu.tv;public class Check {public static void main(String[]args){
 if(MouseButtonCompat.supported())throw new AssertionError("Missing input device advertised as native mouse");
 android.view.InputDevice.present=true;if(!MouseButtonCompat.supported())throw new AssertionError("Real mouse disabled");
 android.view.InputDevice.present=false;if(MouseButtonCompat.supported())throw new AssertionError("Removed device retained");
 System.out.println("PASS missing/present/removed synthetic mouse device guard");}}'''}
with tempfile.TemporaryDirectory() as tmp:
 paths=[]
 for name,text in files.items():
  f=Path(tmp)/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text,encoding='utf-8');paths.append(str(f))
 jdk='C:/Users/Administrator/.jdks/jbr-17.0.14/bin/'
 subprocess.run([jdk+'javac.exe','-d',tmp,*paths,str(root/'app/src/main/java/xiao/bu/tv/MouseButtonCompat.java')],check=True)
 subprocess.run([jdk+'java.exe','-cp',tmp,'xiao.bu.tv.Check'],check=True)
