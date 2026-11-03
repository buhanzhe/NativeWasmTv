"""Fault injection for production hardware HDR program switching (no playback)."""
from pathlib import Path
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
java=root/'.codex-tmp/jdk8/runtime/jdk8u504-b01/bin'
text=(root/'app/src/main/java/xiao/bu/tv/NativeHdrOutput.java').read_text(encoding='utf-8')
methods=text[text.index('    private void refreshToneLut('):text.index('    static void surfaceSizeChanged(')]
stub=r'''package xiao.bu.tv;
class GLES20 {
 static final int GL_TEXTURE0=0,GL_TEXTURE1=1,GL_TEXTURE_2D=2,GL_NO_ERROR=0; static int glGetError(){return 0;}
 static int active,current,videoUnit=-1,lutUnit=-1;
 static void glActiveTexture(int n){active=n;}static void glBindTexture(int target,int n){}
 static void glDeleteProgram(int n){}
 static void glUseProgram(int n){current=n;}
 static int glGetAttribLocation(int p,String name){return p*100+name.length();}
 static int glGetUniformLocation(int p,String name){return p*100+(name.equals("video")?1:name.equals("ntvLut")?2:3);}
 static void glUniform1i(int loc,int value){
  if(loc/100!=current)throw new AssertionError("Uniform written to wrong program");
  if(loc%100==1)videoUnit=value;if(loc%100==2)lutUnit=value;
 }
}
class Log{static void w(String tag,String text,Throwable error){}}
class HdrDisplayPolicy{static String sdrShader(String reference,int transfer){return "SDR"+transfer;}}
class HdrSdrCurve {
 static boolean failBind,slow;int bound,released;
 static String shader(int transfer,boolean raw){return "CURVE"+transfer;}
 boolean isFloating(){return true;} String programSource(int transfer,boolean raw){return shader(transfer,raw);}
 boolean select(String reference,int transfer){bind();return !slow;}
 void bind(){bound++;if(failBind)throw new IllegalStateException("Curve upload rejected");}
 void release(){released++;}
}
class Lut {
 boolean fail,volume;int released,generated,disabled;float lastTransfer=-1,lastPeak=-1;
 boolean isVolume(){return volume;}int target(){return volume?3:GLES20.GL_TEXTURE_2D;}
 void disableVolume(){volume=false;disabled++;lastTransfer=lastPeak=-1;}
 int update(float trc,float peak){
  if(fail)throw new IllegalStateException("FBO rejected");
  if(lastTransfer!=trc||lastPeak!=peak){generated++;lastTransfer=trc;lastPeak=peak;GLES20.current=99;}
  return 7;
 }
 void release(){released++;}
}
class NativeHdrOutput {
 static final String TAG="HDR";
 boolean forceBt709,lutFailed,lutActive,drawStateDirty,viewportDirty,failCompile,lutProgramVolume,failVolumeCompile,failCurveCompile,compileOom;
 int program,referenceProgram=11,lutProgram,sdrProgram,sdrTransfer,position,texcoord,transform,yuvMatrix,yuvOffset,compiled;
 HdrSdrCurve sdrCurve;boolean sdrCurveFailed,sdrProgramUsesCurve,sdrCurveActive,sdrCurveFloating;
 String toneReference="reference";boolean rawYuv;Lut toneLut=new Lut();
 int buildProgram(String source,boolean yuv,boolean lookup){
  if(compileOom)throw new OutOfMemoryError("Compiler memory pressure");
  if(!lookup){compiled++;if(failCompile||failCurveCompile&&source.startsWith("CURVE"))throw new IllegalStateException("SDR compile rejected");return source.endsWith("3")?14:15;}
  compiled++;if(failCompile||failVolumeCompile&&toneLut.isVolume())throw new IllegalStateException("Shader rejected");return toneLut.isVolume()?13:12;
 }
'''+methods+r'''
 static void ok(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static void selected(NativeHdrOutput v,int expected){
  ok(v.program==expected&&GLES20.current==expected,"Wrong active program");
  ok(v.position==expected*100+8&&v.transform==expected*100+3,"Stale vertex/uniform locations");
  ok(GLES20.videoUnit==0&&GLES20.lutUnit==1&&GLES20.active==0,"Wrong sampler state");
  ok(v.drawStateDirty&&v.viewportDirty,"LUT generation state not invalidated");
 }
 public static void main(String[] args){
  NativeHdrOutput v=new NativeHdrOutput();
  v.refreshToneLut(7,1000);selected(v,12);ok(v.lutActive&&v.compiled==1,"HLG must select pure lookup");
  for(int i=0;i<100;i++)v.refreshToneLut(7,1000);
  ok(v.compiled==1&&v.toneLut.generated==1,"Unchanged mapping must stay cached across frames/formats");
  v.refreshToneLut(3,1000);selected(v,14);ok(!v.lutActive,"SDR gamut must select specialized formula");
  for(int i=0;i<100;i++)v.refreshToneLut(3,1000);
  ok(v.compiled==2,"Unchanged SDR format must not recompile");
  v.refreshToneLut(0,1000);selected(v,11);
  v.refreshToneLut(6,4000);selected(v,12);ok(v.compiled==2&&v.toneLut.generated==2,"Reuse pure program on PQ/peak change");
  v.toneLut.fail=true;v.refreshToneLut(6,1000);selected(v,11);
  ok(v.lutFailed&&!v.lutActive&&v.toneLut.released==1,"Failed LUT must release and fall back");
  v.refreshToneLut(7,1000);selected(v,11);ok(v.toneLut.released==1,"Failure must not retry every frame");
  NativeHdrOutput broken=new NativeHdrOutput();broken.failCompile=true;broken.refreshToneLut(7,1000);selected(broken,11);
  ok(broken.lutFailed&&!broken.lutActive&&broken.toneLut.released==1,"Compile rejection must use formula fallback");
  NativeHdrOutput volume=new NativeHdrOutput();volume.toneLut.volume=true;
  volume.refreshToneLut(7,1000);selected(volume,13);ok(volume.lutProgramVolume,"3D program selection");
  volume.toneLut.disableVolume();volume.refreshToneLut(7,1000);selected(volume,12);
  ok(!volume.lutProgramVolume&&volume.lutActive,"Later upload rejection must select 2D program");
  NativeHdrOutput fallback=new NativeHdrOutput();fallback.toneLut.volume=true;fallback.failVolumeCompile=true;
  fallback.refreshToneLut(7,1000);selected(fallback,12);
  ok(!fallback.lutFailed&&fallback.lutActive&&fallback.toneLut.disabled==1&&fallback.compiled==2,"3D compile failure keeps 2D lookup, not formula");
  NativeHdrOutput forced=new NativeHdrOutput();forced.forceBt709=true;forced.refreshToneLut(7,1000);
  ok(forced.compiled==0&&forced.toneLut.generated==0,"BT709 must not allocate HDR lookup resources");
  NativeHdrOutput sdr=new NativeHdrOutput();sdr.refreshToneLut(3,1000);selected(sdr,14);
  sdr.failCompile=true;sdr.refreshToneLut(1,1000);selected(sdr,11);
  ok(!sdr.lutFailed,"SDR compile failure must retain reference without disabling HDR LUT");
  sdr.failCompile=false;sdr.refreshToneLut(1,1000);selected(sdr,15);
  NativeHdrOutput curve=new NativeHdrOutput();curve.refreshToneLut(3,1000);selected(curve,14);
  ok(curve.sdrCurveActive&&curve.sdrProgramUsesCurve&&curve.sdrCurve.bound>=1,"SDR curve is not active");
  curve.refreshToneLut(7,1000);selected(curve,12);ok(!curve.sdrCurveActive,"HDR must retain original mapping");
  curve.refreshToneLut(3,1000);selected(curve,14);ok(curve.sdrCurveActive,"HDR/SDR return lost curve binding");
  curve.failCompile=true;curve.refreshToneLut(1,1000);selected(curve,11);
  ok(curve.sdrProgram==0&&curve.sdrCurve==null&&!curve.sdrProgramUsesCurve,"Failure left a cached program using a released texture");
  curve.failCompile=false;curve.refreshToneLut(3,1000);selected(curve,14);
  ok(!curve.sdrCurveActive&&curve.sdrCurveFailed,"Failed curve must retain original formula on return");
  HdrSdrCurve.slow=true;NativeHdrOutput slow=new NativeHdrOutput();slow.refreshToneLut(3,1000);selected(slow,14);
  ok(!slow.sdrCurveActive&&!slow.sdrCurveFailed,"Slower curves must retain formula without disabling future transfers");
  slow.refreshToneLut(1,1000);selected(slow,15);ok(!slow.sdrProgramUsesCurve,"Transfer change must retain formula");
  HdrSdrCurve.slow=false;
  NativeHdrOutput curveCompile=new NativeHdrOutput();curveCompile.failCurveCompile=true;
  curveCompile.refreshToneLut(3,1000);selected(curveCompile,14);
  ok(curveCompile.sdrCurveFailed&&!curveCompile.sdrCurveActive&&!curveCompile.sdrProgramUsesCurve&&curveCompile.sdrCurve==null,"Curve-only compile failure left a formula program requiring a freed texture");
  NativeHdrOutput memory=new NativeHdrOutput();memory.compileOom=true;
  memory.refreshToneLut(3,1000);selected(memory,11);
  ok(memory.sdrCurveFailed&&!memory.sdrCurveActive,"Optional SDR program memory failure must retain prebuilt reference");
  HdrSdrCurve.failBind=true;NativeHdrOutput upload=new NativeHdrOutput();upload.refreshToneLut(3,1000);selected(upload,14);
  ok(upload.sdrCurveFailed&&!upload.sdrCurveActive&&upload.sdrCurve==null,"Upload failure must cleanly select formula fallback");
  System.out.println("PASS cached LUT-only program, HDR/SDR transitions, sampler/location refresh, FBO/compile failures and BT709 isolation");
 }
}
'''
with tempfile.TemporaryDirectory() as directory:
    folder=Path(directory)
    (folder/'NativeHdrOutput.java').write_text(stub,encoding='utf-8')
    subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',str(folder),str(folder/'NativeHdrOutput.java')],check=True)
    subprocess.run([str(java/'java.exe'),'-cp',str(folder),'xiao.bu.tv.NativeHdrOutput'],check=True)

output=text[text.index('    public void outputFormat('):text.index('    private static boolean knownStandard(')]
assert output.index('refreshToneLut(') < output.index('updateYuvColor(outputStandard,'), 'Raw YUV uniforms must follow shader switch'
assert '? 0 : outputTransfer' in output, 'BT709 decoder output must bypass the BT2020 SDR program'
assert 'HdrToneMapLut.LOOKUP_ONLY' in text and 'HdrToneMapLut.LOOKUP;' not in text
print('PASS production uses lookup-only shader; raw YUV colour uniforms follow program selection')
