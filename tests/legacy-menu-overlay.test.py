"""Exercise the actual legacy x86 overlay policy, geometry and focus lifecycle."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/LegacyMenuOverlay.java').read_text('utf8')
source='\n'.join(x for x in source.splitlines() if not x.startswith('package ') and not x.startswith('import '))
fakes='''class Build {static String CPU_ABI="x86";static class VERSION {static int SDK_INT=19;}}
class Gravity {static int RIGHT=1,LEFT=2,TOP=4;}
class View {static int LAYER_TYPE_SOFTWARE=1;View parent,focus;int layer;boolean focused=true;View getRootView(){return parent==null?this:parent.getRootView();}Object getParent(){return parent;}void setLayerType(int type,Object paint){layer=type;}int getSystemUiVisibility(){return 0;}void setSystemUiVisibility(int v){}void bringToFront(){}void requestFocus(){focused=true;}boolean isShown(){return true;}}
class FrameLayout extends View {int children;LayoutParams params;View findFocus(){return focus;}void addView(View v,LayoutParams p){v.parent=this;children++;params=p;}void removeView(View v){v.parent=null;children--;}static class LayoutParams{int rightMargin,leftMargin,topMargin,width,height,gravity;LayoutParams(int w,int h,int g){width=w;height=h;gravity=g;}}}
'''
check='''class Check {static void ok(boolean b){if(!b)throw new AssertionError();}public static void main(String[] args){
 FrameLayout root=new FrameLayout();View focus=new View();root.focus=focus;View menu=new View();LegacyMenuOverlay host=new LegacyMenuOverlay();
 ok(host.show(root,menu,300,600,22,24,true));ok(host.visible()&&host.contentView()==menu&&root.children==1);ok(menu.layer==View.LAYER_TYPE_SOFTWARE&&root.params.rightMargin==22&&root.params.topMargin==24);focus.focused=false;host.dismiss();ok(!host.visible()&&root.children==0&&focus.focused);host.dismiss();
 Build.CPU_ABI="armeabi-v7a";ok(!host.show(root,menu,300,600,0,0,true));Build.CPU_ABI="x86_64";Build.VERSION.SDK_INT=25;ok(!host.show(root,menu,300,600,0,0,true));Build.VERSION.SDK_INT=22;ok(host.show(root,menu,300,600,0,0,true));host.dismiss();
 System.out.println("PASS old x86 uses existing window, software menu, positioning, cleanup and focus; ARM/modern x86 unchanged");}}
'''
jdk=Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory() as temp:
 p=Path(temp)/'Check.java';p.write_text(fakes+source+check,'utf8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',temp,str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',temp,'Check'],check=True)
