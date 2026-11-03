"""Exercise production SDR curve selection, precision guards and GL failures.

No decoder or video frame-rate claim. Optional NTV_HDR_CURVE_EXPORT writes the
actual Java shader/data for the GPU pixel comparison test.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
stub = r'''package android.opengl;
import java.nio.*;
public class GLES20 {
 public static final int GL_TEXTURE0=0,GL_TEXTURE1=1,GL_MAX_TEXTURE_SIZE=2,GL_VERSION=3,GL_EXTENSIONS=4,GL_TEXTURE_2D=5,GL_LINEAR=6,GL_NEAREST=7,GL_TEXTURE_MIN_FILTER=8,GL_TEXTURE_MAG_FILTER=9,GL_TEXTURE_WRAP_S=10,GL_TEXTURE_WRAP_T=11,GL_CLAMP_TO_EDGE=12,GL_RGBA=13,GL_FLOAT=14,GL_UNSIGNED_BYTE=15,GL_NO_ERROR=0,GL_VENDOR=16,GL_RENDERER=17,GL_FRAMEBUFFER_BINDING=18,GL_CURRENT_PROGRAM=19,GL_ARRAY_BUFFER_BINDING=20,GL_TEXTURE_BINDING_2D=21,GL_VIEWPORT=22,GL_ARRAY_BUFFER=23,GL_FRAMEBUFFER=24,GL_COLOR_ATTACHMENT0=25,GL_FRAMEBUFFER_COMPLETE=26,GL_TRIANGLE_STRIP=27;
 public static String version="OpenGL ES 3.2",extensions="GL_OES_texture_float_linear",device="initial";
 public static int active,maximum=4096,uploads,deletes,lastInternal,lastType,lastFilter,lastBytes,error,nextId=1,failFloat,quality,reads,draws;
 public static int framebuffer=91,current=92,array=93,texture0=94,texture1;public static int[] viewport={3,4,233,222};
 public static boolean failPacked,failFbo,failRead,slow,failCompile;
 public static long clock;public static long nanoTime(){return clock;}
 public static void reset(String name){device=name;quality=0;failFloat=0;failPacked=failFbo=failRead=slow=failCompile=false;version="OpenGL ES 3.2";extensions="GL_OES_texture_float_linear";maximum=4096;}
 public static void glActiveTexture(int v){active=v;}
 public static String glGetString(int key){return key==GL_VERSION?version:key==GL_EXTENSIONS?extensions:device;}
 public static void glGetIntegerv(int key,int[] out,int at){
  if(key==GL_VIEWPORT){System.arraycopy(viewport,0,out,at,4);return;}
  out[at]=key==GL_MAX_TEXTURE_SIZE?maximum:key==GL_FRAMEBUFFER_BINDING?framebuffer:key==GL_CURRENT_PROGRAM?current:key==GL_ARRAY_BUFFER_BINDING?array:active==0?texture0:texture1;
 }
 public static void glGenTextures(int count,int[] out,int at){out[at]=nextId++;}
 public static void glBindTexture(int target,int id){if(active==0)texture0=id;else texture1=id;}
 public static void glTexParameteri(int target,int key,int value){if(key==GL_TEXTURE_MIN_FILTER)lastFilter=value;}
 public static void glTexImage2D(int target,int level,int internal,int w,int h,int border,int format,int type,Buffer pixels){
  if(pixels==null)return;uploads++;lastInternal=internal;lastType=type;lastBytes=pixels.remaining();
  if(type==GL_FLOAT&&failFloat==2)throw new IllegalArgumentException("float rejected");
  if(type==GL_FLOAT&&failFloat==1||type==GL_UNSIGNED_BYTE&&failPacked)error=0x502;
 }
 public static int glGetError(){int value=error;error=0;return value;}
 public static void glDeleteTextures(int count,int[] ids,int offset){deletes+=count;}
 public static void glBindBuffer(int target,int id){array=id;}
 public static void glGenFramebuffers(int count,int[] out,int at){out[at]=nextId++;}
 public static void glBindFramebuffer(int target,int id){framebuffer=id;}
 public static void glFramebufferTexture2D(int a,int b,int c,int d,int e){}
 public static int glCheckFramebufferStatus(int target){return failFbo?0:GL_FRAMEBUFFER_COMPLETE;}
 public static void glViewport(int x,int y,int w,int h){viewport=new int[]{x,y,w,h};}
 public static void glDeleteFramebuffers(int count,int[] ids,int at){}
 public static void glDeleteProgram(int id){}
 public static void glUseProgram(int id){current=id;}
 public static int glGetUniformLocation(int id,String name){return 0;}
 public static int glGetAttribLocation(int id,String name){return 0;}
 public static void glUniform1i(int a,int b){}
 public static void glEnableVertexAttribArray(int id){}
 public static void glDisableVertexAttribArray(int id){}
 public static void glVertexAttribPointer(int a,int b,int c,boolean d,int e,Buffer data){}
 public static void glDrawArrays(int mode,int first,int count){draws++;}
 public static void glFinish(){clock+=current==100?2000000:slow?5000000:100000;}
 public static void glReadPixels(int x,int y,int w,int h,int format,int type,Buffer data){
  reads++;ByteBuffer b=(ByteBuffer)data;for(int i=0;i<b.remaining();i++)b.put(i,(byte)(i%4==3?255:100+(current==200?quality:0)));
  if(failRead)error=0x502;
 }
}
'''
check = r'''package xiao.bu.tv;
import android.opengl.GLES20;import java.nio.*;import java.nio.file.*;import java.util.*;
class NativeHdrOutput {
 static int linkProgram(String vertex,String fragment){
  if(GLES20.failCompile&&fragment.contains("ntvLut"))throw new IllegalStateException("compile");
  return fragment.contains("ntvLut")?200:100;
 }
}
public class CurveCheck {
 static void ok(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static void state(){ok(GLES20.active==0&&GLES20.framebuffer==91&&GLES20.current==92&&GLES20.array==93&&GLES20.texture0==94&&Arrays.equals(GLES20.viewport,new int[]{3,4,233,222}),"Probe leaked framebuffer/program/array/texture/viewport");}
 static boolean select(HdrSdrCurve curve,int trc){boolean selected=curve.select("reference",trc);state();ok(GLES20.error==0,"Probe leaked GL error");return selected;}
 static void rejected(String name,int failure){
  GLES20.reset(name);HdrSdrCurve curve=new HdrSdrCurve();
  if(failure==0)GLES20.quality=2;if(failure==1)GLES20.slow=true;if(failure==2)GLES20.failFbo=true;
  if(failure==3)GLES20.failRead=true;if(failure==4)GLES20.maximum=512;if(failure==5)GLES20.failCompile=true;
  ok(!select(curve,3),"Unsafe/slow candidate accepted "+name);int reads=GLES20.reads;
  ok(!select(new HdrSdrCurve(),3)&&GLES20.reads==reads,"Rejected decision not cached "+name);
 }
 static void save(ByteBuffer pixels,Path path)throws Exception{byte[] bytes=new byte[pixels.remaining()];pixels.get(bytes);Files.write(path,bytes);}
 public static void main(String[] args)throws Exception {
  long[] a={100000,100000,100000,100000,100000,100000,100000};
  ok(HdrSdrCurve.faster(a,new long[]{50000,50000,50000,50000,50000,50000,110000}),"Clear six-round win rejected");
  ok(!HdrSdrCurve.faster(a,new long[]{85000,85000,85000,85000,85000,85000,85000}),"Noise/margin accepted");
  ok(!HdrSdrCurve.faster(a,new long[]{50000,50000,50000,50000,50000,110000,110000}),"Inconsistent win accepted");
  ok(!HdrSdrCurve.faster(new long[7],new long[7]),"Zero timer accepted");
  ByteBuffer p=ByteBuffer.wrap(new byte[]{0,127,(byte)255,(byte)255});ByteBuffer q=ByteBuffer.wrap(new byte[]{1,(byte)128,(byte)254,(byte)255});
  ok(HdrSdrCurve.accurate(p,q)&&p.position()==0&&q.position()==0,"One code guard or buffer position");
  q.put(0,(byte)2);ok(!HdrSdrCurve.accurate(p,q),"Two code error accepted");q.put(0,(byte)0);q.put(3,(byte)254);ok(!HdrSdrCurve.accurate(p,q),"Alpha error accepted");
  ok(!HdrSdrCurve.supportsFloat("OpenGL ES 2.0","GL_OES_texture_float GL_OES_texture_float_linear"),"Unsized float accepted");
  ok(!HdrSdrCurve.supportsFloat("OpenGL ES 3.2","GL_OES_texture_float_linear_suffix"),"Partial extension accepted");
  for(int i=0;i<6;i++)rejected("reject"+i,i);
  GLES20.reset("fast");HdrSdrCurve curve=new HdrSdrCurve();ok(select(curve,3),"Faster precise path not selected");
  int reads=GLES20.reads,uploads=GLES20.uploads;for(int i=0;i<100;i++)curve.bind();
  ok(GLES20.reads==reads&&GLES20.uploads==uploads&&GLES20.active==0,"Per-frame profiling/upload");curve.release();
  ok(select(curve,3)&&GLES20.reads==reads,"Decision not reused across contexts");curve.release();
  // A float-only device path must explicitly upload sized RGBA32F, never RGBA/FLOAT.
  GLES20.reset("float");GLES20.failPacked=true;ok(select(curve,3)&&curve.isFloating(),"Float path not selected after packed rejection");
  ok(GLES20.lastInternal==0x8814&&GLES20.lastType==GLES20.GL_FLOAT&&GLES20.lastBytes==65536&&GLES20.lastFilter==GLES20.GL_LINEAR,"Wrong sized float precision/filter/storage");curve.release();
  GLES20.failFloat=1;try{curve.select("reference",3);throw new AssertionError("Cached float upload error swallowed");}catch(IllegalStateException expected){}
  ok(!select(curve,3)&&!curve.isFloating(),"Failed cached upload was retried");
  GLES20.reset("es2");GLES20.version="OpenGL ES 2.0";ok(select(curve,1)&&!curve.isFloating(),"ES2 packed path");
  ok(GLES20.lastType==GLES20.GL_UNSIGNED_BYTE&&GLES20.lastBytes==16384&&GLES20.lastFilter==GLES20.GL_NEAREST,"Packed byte interpolation/filter");curve.release();
  GLES20.reset("float-exception");GLES20.failFloat=2;ok(select(curve,3)&&!curve.isFloating(),"Float exception lost valid packed path");curve.release();
  for(int i=0;i<18;i++)rejected("lru"+i,2);
  java.lang.reflect.Field field=HdrSdrCurve.class.getDeclaredField("CHOICES");field.setAccessible(true);ok(((Map)field.get(null)).size()<=16,"Unbounded device cache");
  if(args.length>0){Path dir=Paths.get(args[0]);Files.createDirectories(dir);save(HdrSdrCurve.pixels(),dir.resolve("curve.bin"));save(HdrSdrCurve.floatPixels(),dir.resolve("curve-float.bin"));
   for(int trc:new int[]{1,3}){Files.write(dir.resolve("curve-"+trc+".glsl"),HdrSdrCurve.shader(trc,false).getBytes("UTF-8"));Files.write(dir.resolve("curve-float-"+trc+".glsl"),HdrSdrCurve.floatShader(trc,false).getBytes("UTF-8"));}
  }
  System.out.println("PASS adaptive speed/quality gates, sized float precision, ES2 packed fallback, GL failures/state restoration, context cache and no per-frame work");
 }
}
'''
with tempfile.TemporaryDirectory() as directory:
    folder=Path(directory)
    package=folder/'android/opengl';package.mkdir(parents=True)
    (package/'GLES20.java').write_text(stub,encoding='utf8')
    for name in ['HdrSdrCurve.java','HdrDisplayPolicy.java']:
        text=(ROOT/'app/src/main/java/xiao/bu/tv'/name).read_text(encoding='utf8')
        # A fake GL driver must also have a fake timer. Host scheduling is not GPU performance.
        # Keep production selection/quality/state logic intact; real GPU comparison is separate.
        if name=='HdrSdrCurve.java':
            assert 'System.nanoTime()' in text
            text=text.replace('System.nanoTime()','GLES20.nanoTime()')
        (folder/name).write_text(text,encoding='utf8')
    (folder/'CurveCheck.java').write_text(check,encoding='utf8')
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(folder)]+[str(p) for p in folder.rglob('*.java')],check=True)
    args=[os.environ['NTV_HDR_CURVE_EXPORT']] if 'NTV_HDR_CURVE_EXPORT' in os.environ else []
    subprocess.run([str(jdk/'java.exe'),'-cp',str(folder),'xiao.bu.tv.CurveCheck']+args,check=True)
