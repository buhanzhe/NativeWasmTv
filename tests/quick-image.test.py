"""Compile real image decoding policy and file routing against Android bitmap stubs."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
java=r'''package xiao.bu.tv;
import java.io.*;
class QuickImageCheck {
 static void ok(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] a)throws Exception{
  for(String n:new String[]{"中文.JPG","p.jpeg","p.PNG","p.WEBP","p.bmp"})ok(QuickMediaFile.isImageName(n)&&QuickMediaFile.isSupportedName(n),n);
  for(String n:new String[]{"a.wav","a.FLAC","a.mp3","a.m4a","a.aac","a.ogg","a.opus"})ok(QuickMediaFile.isAudioName(n)&&QuickMediaFile.isSupportedName(n),n);
  ok(!QuickMediaFile.isSupportedName("file.exe")&&!QuickMediaFile.isSupportedName(null),"Unsupported suffix");
  ok(QuickImage.sampleSize(3200,2000)==2,"Large photo downsampling");
  for(int[] size:new int[][]{{8192,8192},{8193,8193},{120000,100000},{16000,1},{1,16000}}){int s=QuickImage.sampleSize(size[0],size[1]);ok(((size[0]+(long)s-1)/s)*((size[1]+(long)s-1)/s)<=4L*1024*1024&&(size[0]+(long)s-1)/s<=2048&&(size[1]+(long)s-1)/s<=2048,"Pixel/side limit");}
  try{QuickImage.sampleSize(0,5);throw new AssertionError();}catch(ControlRequestException e){ok(e.status==400,"Bad dimensions");}
  android.graphics.BitmapFactory.mime="image/png";ok(QuickImage.decode(new File("fixture"))!=null,"Decode");ok(android.graphics.BitmapFactory.lastSample==2,"Sample passed to decoder");
  android.graphics.BitmapFactory.mime="text/html";try{QuickImage.decode(new File("fixture"));throw new AssertionError();}catch(ControlRequestException e){ok(e.status==400,"Bad content is a client error");}
  System.out.println("PASS upload types, Chinese/case filenames, independent pixel/side bounds, decoder sampling and corrupt content rejection");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp);(p/'Bitmap.java').write_text('package android.graphics;public class Bitmap{public enum Config{ARGB_8888}}',encoding='utf8')
 (p/'BitmapFactory.java').write_text('package android.graphics;public class BitmapFactory{public static String mime="image/png";public static int lastSample;public static class Options{public boolean inJustDecodeBounds;public int outWidth,outHeight,inSampleSize;public String outMimeType;public Bitmap.Config inPreferredConfig;}public static Bitmap decodeFile(String f,Options o){if(o.inJustDecodeBounds){o.outWidth=3200;o.outHeight=2000;o.outMimeType=mime;return null;}lastSample=o.inSampleSize;return new Bitmap();}}',encoding='utf8')
 (p/'QuickImageCheck.java').write_text(java,encoding='utf8');jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
 files=[root/'app/src/main/java/xiao/bu/tv'/n for n in ['QuickImage.java','QuickMediaFile.java','ControlRequestException.java']]
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',tmp,*map(str,files),*map(str,p.glob('*.java'))],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',tmp,'xiao.bu.tv.QuickImageCheck'],check=True)
