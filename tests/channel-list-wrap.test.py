"""Exercise the production pager at channel boundaries with legacy Android stubs."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
files = {
    'android/annotation/TargetApi.java': 'package android.annotation;public @interface TargetApi {int value();}',
    'android/os/Build.java': 'package android.os;public class Build {public static class VERSION {public static int SDK_INT=16;}}',
    'android/view/View.java': '''package android.view;public class View {
        public int top;public int getTop(){return top;}public void requestLayout(){}
    }''',
    'android/view/ViewTreeObserver.java': '''package android.view;public class ViewTreeObserver {
        public interface OnPreDrawListener {boolean onPreDraw();}
        public OnPreDrawListener listener;public boolean isAlive(){return true;}
        public void addOnPreDrawListener(OnPreDrawListener v){listener=v;}
        public void removeOnPreDrawListener(OnPreDrawListener v){if(listener==v)listener=null;}
    }''',
    'android/widget/ListView.java': '''package android.widget;
        import android.view.*;
        public class ListView extends View {
        public int height,first,selected,checked,left=2,top=3,right=4,bottom=5;
        public boolean clip;public final ViewTreeObserver observer=new ViewTreeObserver();
        public int getPaddingLeft(){return left;}public int getPaddingTop(){return top;}
        public int getPaddingRight(){return right;}public int getPaddingBottom(){return bottom;}
        public void setPadding(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
        public boolean getClipToPadding(){return clip;}public void setClipToPadding(boolean v){clip=v;}
        public int getFirstVisiblePosition(){return first;}public int getDividerHeight(){return 1;}
        public int getHeight(){return height;}
        public View getChildAt(int i){View v=new View();v.top=top+i*11;return v;}
        public View getSelectedView(){View v=new View();v.top=top+(selected-first)*11;return v;}
        public void setItemChecked(int pos,boolean v){checked=pos;}
        public void setSelectionFromTop(int pos,int y){selected=pos;first=pos-y/11;}
        public ViewTreeObserver getViewTreeObserver(){return observer;}
    }''',
    'xiao/bu/tv/RemoteListPager.java': (ROOT/'app/src/main/java/xiao/bu/tv/RemoteListPager.java').read_text(encoding='utf8'),
    'xiao/bu/tv/Check.java': '''package xiao.bu.tv;
        import android.widget.ListView;
        public class Check {
        static int checks;
        static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
        static void anchor(ListView l){if(l.observer.listener!=null)check(l.observer.listener.onPreDraw(),"selection anchor stable");}
        public static void main(String[] args){
          for(int api:new int[]{16,25})for(int capacity:new int[]{1,5,7})for(int count:new int[]{1,4,5,6,11,23}){
            android.os.Build.VERSION.SDK_INT=api;
            ListView l=new ListView();l.height=capacity*11+7;
            RemoteListPager p=new RemoteListPager();p.show(l,count,0,0,10);int position=0;
            for(int direction:new int[]{1,-1})for(int i=0;i<count*3;i++){
              int previous=position;position=(position+direction+count)%count;
              check(p.moveWrapping(l,count,-1,direction,10)==position,"logical cursor wraps without stale native cursor");
              check(l.selected==position&&l.checked==position,"visible and checked selection agree");
              check(p.first(-1)==position/capacity*capacity,"page contains whole rows including partial last page");
              check(l.first==p.first(-1),"native page follows logical page");
              if(previous==0&&direction<0)check(l.first==(count-1)/capacity*capacity,"UP restores final page");
              anchor(l);
            }
            p.show(l,count,count-1,(count-1)/capacity*capacity,10);
            check(p.move(l,count,count-1,1,10)==count-1,"group and EPG end remains bounded");
            p.show(l,count,0,0,10);check(p.move(l,count,0,-1,10)==0,"group and EPG beginning remains bounded");
            p.reset();check(l.bottom==5&&!l.clip&&l.observer.listener==null,"reset restores touch viewport and removes anchor");
            p.show(l,count,0,0,10);check(p.moveWrapping(l,0,0,1,10)==-1,"empty list clears cursor");
            check(p.position(-1)==-1&&l.bottom==5,"empty list restores viewport");
          }
          System.out.println("PASS channel wrap: "+checks+" checks; API16/25, full/partial pages, bidirectional repeats, empty/single lists, bounded group/EPG and touch reset");
        }
    }''',
}

activity = (ROOT/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
assert 'channelPager.moveWrapping(channelList,' in activity
assert 'groupPager.move(groupList,' in activity
assert 'epgPager.move(epgList,' in activity
with tempfile.TemporaryDirectory() as folder:
    out = Path(folder)
    for name, body in files.items():
        path = out/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf8')
    jdk = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Android/Android Studio/jbr'))/'bin'
    subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '-d', str(out), *[str(out/name) for name in files]], check=True)
    subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'xiao.bu.tv.Check'], check=True)
