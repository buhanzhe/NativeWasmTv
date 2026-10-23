"""Exercise production LUT allocation/cache/packing/fallback with a GLES fault model."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
java=root/'.codex-tmp/jdk8/runtime/jdk8u504-b01/bin'
gl=r'''package android.opengl;
import java.nio.*;import java.util.*;
public class GLES20 {
 public static final int GL_TEXTURE_2D=3553,GL_TEXTURE1=33985,GL_TEXTURE0=33984,GL_EXTENSIONS=7939,GL_VERSION=7938,
 GL_TEXTURE_MIN_FILTER=10241,GL_TEXTURE_MAG_FILTER=10240,GL_LINEAR=9729,GL_TEXTURE_WRAP_S=10242,GL_TEXTURE_WRAP_T=10243,
 GL_CLAMP_TO_EDGE=33071,GL_RGBA=6408,GL_UNSIGNED_BYTE=5121,GL_FRAMEBUFFER=36160,GL_COLOR_ATTACHMENT0=36064,
 GL_FRAMEBUFFER_COMPLETE=36053,GL_FLOAT=5126,GL_TRIANGLE_STRIP=5,GL_NO_ERROR=0;
 public static int next=1,draws,allocations;public static String extensions="GL_OES_texture_3D",version="OpenGL ES 2.0";
 public static Set<Integer> textures=new HashSet<>(),framebuffers=new HashSet<>();
 public static Map<Integer,Integer> volumeParameters=new HashMap<>();
 public static void glGenTextures(int n,int[] ids,int offset){for(int i=0;i<n;i++){ids[offset+i]=next++;textures.add(ids[offset+i]);}}
 public static void glDeleteTextures(int n,int[] ids,int offset){for(int i=0;i<n;i++)textures.remove(ids[offset+i]);}
 public static void glGenFramebuffers(int n,int[] ids,int offset){for(int i=0;i<n;i++){ids[offset+i]=next++;framebuffers.add(ids[offset+i]);}}
 public static void glDeleteFramebuffers(int n,int[] ids,int offset){for(int i=0;i<n;i++)framebuffers.remove(ids[offset+i]);}
 public static void glActiveTexture(int n){}public static void glBindTexture(int target,int id){}
 public static void glTexParameteri(int target,int pname,int value){if(target==0x806f)volumeParameters.put(pname,value);}
 public static void glTexImage2D(int t,int l,int f,int w,int h,int b,int fm,int type,Buffer data){allocations++;}
 public static void glTexSubImage2D(int t,int l,int x,int y,int w,int h,int f,int type,Buffer data){}
 public static void glBindFramebuffer(int t,int f){}
 public static void glFramebufferTexture2D(int a,int b,int c,int d,int e){}
 public static int glCheckFramebufferStatus(int t){return GL_FRAMEBUFFER_COMPLETE;}
 public static void glViewport(int x,int y,int w,int h){}public static void glUseProgram(int p){}
 public static int glGetUniformLocation(int p,String name){return 1;}public static int glGetAttribLocation(int p,String name){return 0;}
 public static void glUniform1f(int l,float v){}public static void glVertexAttribPointer(int p,int s,int t,boolean n,int stride,Buffer b){}
 public static void glEnableVertexAttribArray(int n){}public static void glDrawArrays(int p,int f,int n){draws++;}
 public static int glGetError(){return 0;}public static String glGetString(int name){return name==GL_EXTENSIONS?extensions:version;}
 public static void glDeleteProgram(int p){}
 public static void glReadPixels(int x,int y,int w,int h,int f,int t,Buffer b){
  ByteBuffer data=(ByteBuffer)b;
  for(int yy=0;yy<512;yy++)for(int xx=0;xx<512;xx++){
   int p=(yy*512+xx)*4;data.put(p,(byte)(xx%64));data.put(p+1,(byte)(yy%64));
   data.put(p+2,(byte)(xx/64+8*(yy/64)));data.put(p+3,(byte)255);
  }
 }
}'''
test=r'''package xiao.bu.tv;
import java.nio.*;import android.opengl.GLES20;
class NativeHdrOutput {
 static boolean uploadLut3d128(ByteBuffer cube){throw new AssertionError("Stable profile must not call 128 upload");}
 static boolean reject,missing;static int uploads;
 static int linkProgram(String v,String f){return 9;}
 static boolean uploadLut3d(ByteBuffer cube){
  uploads++;if(missing)throw new UnsatisfiedLinkError("old native library");
  if(!cube.isDirect()||cube.remaining()!=64*64*64*4)throw new AssertionError("Native upload size");
  for(int z=0;z<64;z++)for(int y=0;y<64;y++)for(int x=0;x<64;x++){
   int p=((z*64+y)*64+x)*4;
   if((cube.get(p)&255)!=x||(cube.get(p+1)&255)!=y||(cube.get(p+2)&255)!=z||(cube.get(p+3)&255)!=255)
    throw new AssertionError("3D cube order");
  }
  return !reject;
 }
}
class HdrLutVolumeTest {
 static void ok(boolean v,String message){if(!v)throw new AssertionError(message);}
 public static void main(String[] args){
  ByteBuffer atlas=ByteBuffer.allocateDirect(512*512*4);
  for(int p=0;p<atlas.capacity();p++)atlas.put(p,(byte)((p*13+p/19)&255));
  byte[] before=new byte[atlas.capacity()];atlas.duplicate().get(before);
  HdrToneMapLut.repairNeutralAxis(atlas);
  int changed=0;
  for(int z=0;z<64;z++)for(int y=0;y<64;y++)for(int x=0;x<64;x++){
   int p=(((z/8)*64+y)*512+(z%8)*64+x)*4;
   ok(atlas.get(p+3)==before[p+3],"Alpha must remain unchanged");
   if(atlas.get(p)!=before[p]||atlas.get(p+1)!=before[p+1]||atlas.get(p+2)!=before[p+2]){
    changed++;ok(Math.max(x,Math.max(y,z))-Math.min(x,Math.min(y,z))==1,"Only six off-axis corners can change");
   }
  }
  ok(changed<=378,"Sparse PQ correction");
  for(int k=0;k<63;k++)for(int n=1;n<=2;n++)for(int channel=0;channel<3;channel++){
   int base=n==1?k:k+1,d=n==1?1:-1;
   int[] xs={base+d,base,base},ys={base,base+d,base},zs={base,base,base+d};int sum=0;
   for(int i=0;i<3;i++)sum+=atlas.get((((zs[i]/8)*64+ys[i])*512+(zs[i]%8)*64+xs[i])*4+channel)&255;
   int a=(((k/8)*64+k)*512+(k%8)*64+k)*4+channel,j=k+1;
   int b=(((j/8)*64+j)*512+(j%8)*64+j)*4+channel;
   ok(sum==(atlas.get(a)&255)*(3-n)+(atlas.get(b)&255)*n,"Exact grey Bezier control sum within byte bounds");
  }
  System.out.println("PASS 378-node bounded RGB projection, exact grey control sums and all unrelated nodes/alpha preserved");
  HdrToneMapLut lut=new HdrToneMapLut("mock reference");int id=lut.update(7,1000);
  ok(lut.isVolume()&&lut.target()==0x806f,"Enable supported 3D LUT");
  for(int coordinate:new int[]{GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_TEXTURE_WRAP_T,0x8072})
   ok(Integer.valueOf(GLES20.GL_CLAMP_TO_EDGE).equals(GLES20.volumeParameters.get(coordinate)),"Fast addressing requires edge clamping on all three axes");
  ok(GLES20.textures.size()==1,"Generation atlas must be released: only one 1 MiB colour texture remains");
  int generated=GLES20.draws;
  for(int i=0;i<100;i++)ok(lut.update(7,4000)==id,"HLG MaxCLL must reuse same texture");
  ok(GLES20.draws==generated&&NativeHdrOutput.uploads==1,"No work for unchanged table");
  lut.update(6,4000);ok(NativeHdrOutput.uploads==2&&GLES20.textures.size()==1,"PQ metadata change updates volume and frees scratch");
  NativeHdrOutput.reject=true;lut.update(6,1000);
  ok(!lut.isVolume()&&GLES20.textures.size()==1,"Optional upload failure retains 2D atlas and releases 3D");
  int attempted=NativeHdrOutput.uploads;lut.update(6,2000);ok(NativeHdrOutput.uploads==attempted,"Do not retry failed volume on every format");
  lut.release();ok(GLES20.textures.isEmpty()&&GLES20.framebuffers.isEmpty(),"All resources released");
  NativeHdrOutput.reject=false;NativeHdrOutput.missing=true;lut=new HdrToneMapLut("reference");lut.update(7,1000);
  ok(!lut.isVolume()&&GLES20.textures.size()==1,"Old native libraries keep 2D lookup");lut.release();
  NativeHdrOutput.missing=false;GLES20.extensions="GL_OES_texture_3D_fake";
  attempted=NativeHdrOutput.uploads;lut=new HdrToneMapLut("reference");lut.update(7,1000);
  ok(!lut.isVolume()&&NativeHdrOutput.uploads==attempted,"Exact extension match");lut.release();
  ok(HdrToneMapLut.supportsVolume("OpenGL ES 3.0",null),"ES3 core capability");
  ok(!HdrToneMapLut.supportsVolume(null,null),"Unknown capabilities remain 2D");
  try{HdrToneMapLut.packVolume(ByteBuffer.allocateDirect(32));throw new AssertionError("Short atlas accepted");}catch(IllegalArgumentException expected){}
  ok(GLES20.textures.isEmpty()&&GLES20.framebuffers.isEmpty(),"Fallback resource cleanup");
  System.out.println("PASS 262144-node CPU layout, 1 MiB retained texture, no per-frame rebuild, HLG cache, peak update, upload failure, old-library fallback and cleanup");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    out=Path(directory)
    for file,source in [('xiao/bu/tv/BuildConfig.java','package xiao.bu.tv; final class BuildConfig{static final boolean HDR_LUT_TEST_128=false;}'),('android/opengl/GLES20.java',gl),('android/util/Log.java','package android.util; public class Log{public static int w(String t,String m,Throwable e){return 0;}}'),('xiao/bu/tv/HdrLutVolumeTest.java',test)]:
        path=out/file;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source,encoding='utf-8')
    sources=[str(path) for path in out.rglob('*.java')]+[str(root/'app/src/main/java/xiao/bu/tv/HdrToneMapLut.java')]
    subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',str(out)]+sources,check=True)
    subprocess.run([str(java/'java.exe'),'-cp',str(out),'xiao.bu.tv.HdrLutVolumeTest'],check=True)
