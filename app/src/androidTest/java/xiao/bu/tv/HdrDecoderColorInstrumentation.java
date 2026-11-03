package xiao.bu.tv;

import android.app.Instrumentation;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pixel comparison through MediaCodec -> production HDR bridge -> output Surface. */
public final class HdrDecoderColorInstrumentation extends Instrumentation {
    private Bundle args;
    private EGLDisplay display;
    private EGLContext context;
    private EGLSurface window;
    private int program, textureId;
    private SurfaceTexture texture;
    private Surface surface;
    private final float[] transform = new float[16];

    @Override public void onCreate(Bundle value) { super.onCreate(value); args=value; start(); }

    private int shader(int type,String source) {
        int id=GLES20.glCreateShader(type); GLES20.glShaderSource(id,source); GLES20.glCompileShader(id);
        int[] valid=new int[1]; GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,valid,0);
        if(valid[0]==0)throw new AssertionError(GLES20.glGetShaderInfoLog(id)); return id;
    }

    private void initialize() {
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version=new int[2]; if(!EGL14.eglInitialize(display,version,0,version,1))throw new AssertionError("EGL init");
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_NONE};
        if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]==0)throw new AssertionError("EGL config");
        context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        window=EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,640,
                EGL14.EGL_HEIGHT,360,EGL14.EGL_NONE},0);
        if(!EGL14.eglMakeCurrent(display,window,window,context))throw new AssertionError("EGL current");
        int vs=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 position;attribute vec2 coord;uniform mat4 transform;"
                +"varying vec2 uv;void main(){gl_Position=vec4(position,0.,1.);uv=(transform*vec4(coord,0.,1.)).xy;}");
        int fs=shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\n"
                +"precision highp float;uniform samplerExternalOES video;varying vec2 uv;"
                +"void main(){gl_FragColor=texture2D(video,uv);}");
        program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);
        GLES20.glLinkProgram(program);GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);
        int[] linked=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);
        if(linked[0]==0)throw new AssertionError(GLES20.glGetProgramInfoLog(program));
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);textureId=ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        texture=new SurfaceTexture(textureId);texture.setDefaultBufferSize(640,360);surface=new Surface(texture);
        FloatBuffer vertices=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(new float[]{-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1}).position(0);
        GLES20.glUseProgram(program);GLES20.glViewport(0,0,640,360);GLES20.glDisable(GLES20.GL_DITHER);
        int pos=GLES20.glGetAttribLocation(program,"position"),coord=GLES20.glGetAttribLocation(program,"coord");
        GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(pos);
        vertices.position(2);GLES20.glVertexAttribPointer(coord,2,GLES20.GL_FLOAT,false,16,vertices);
        GLES20.glEnableVertexAttribArray(coord);GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"video"),0);
    }

    private JSONObject measure(JSONObject fixture) throws Exception {
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;NativeHdrOutput bridge=null;
        try {
            extractor.setDataSource(fixture.getString("file"));extractor.selectTrack(0);
            MediaFormat format=extractor.getTrackFormat(0);
            format.setInteger("color-standard",fixture.getInt("standard"));
            format.setInteger("color-transfer",fixture.getInt("transfer"));
            format.setInteger("color-range",fixture.getInt("range"));
            format.setInteger("ntv-source-standard",fixture.getInt("standard"));
            format.setInteger("ntv-source-transfer",fixture.getInt("transfer"));
            format.setInteger("ntv-hdr-mode",fixture.optInt("mode",HdrMode.nativeValue(HdrMode.DEFAULT)));
            MediaFormat bridgeFormat=format;
            if(fixture.has("bridgeTransfer")) {
                // Complete SDR output metadata must still be honoured. The
                // decoded fixture is already SDR; only the bridge starts as HDR.
                bridgeFormat=MediaFormat.createVideoFormat(format.getString(MediaFormat.KEY_MIME),640,360);
                bridgeFormat.setInteger("color-standard",6);
                bridgeFormat.setInteger("color-transfer",fixture.getInt("bridgeTransfer"));
                bridgeFormat.setInteger("ntv-source-standard",6);
                bridgeFormat.setInteger("ntv-source-transfer",fixture.getInt("bridgeTransfer"));
                bridgeFormat.setInteger("ntv-hdr-mode",fixture.optInt("mode",HdrMode.nativeValue(HdrMode.DEFAULT)));
            }
            bridge=NativeHdrOutput.create(bridgeFormat,surface);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            String decoder=codec.getName();codec.configure(format,bridge==null?surface:bridge.getSurface(),null,0);codec.start();
            ByteBuffer[] buffers=codec.getInputBuffers();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            long deadline=SystemClock.elapsedRealtime()+10000;int frames=0;String output="";
            while(frames<8 && SystemClock.elapsedRealtime()<deadline) {
                int index=codec.dequeueInputBuffer(10000);
                if(index>=0) {
                    ByteBuffer buffer=buffers[index];buffer.clear();int size=extractor.readSampleData(buffer,0);
                    if(size<0)codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    else {codec.queueInputBuffer(index,0,size,extractor.getSampleTime(),0);extractor.advance();}
                }
                index=codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat reported=codec.getOutputFormat();output=reported.toString();
                    // Exercise legacy/vendor output metadata without changing decoded pixels.
                    // A partial SDR tag alone must not erase the validated source HDR transfer.
                    if(fixture.has("reportedTransfer")) {
                        reported=MediaFormat.createVideoFormat("video/raw",640,360);
                        if(fixture.has("reportedStandard"))reported.setInteger("color-standard",fixture.getInt("reportedStandard"));
                        reported.setInteger("color-transfer",fixture.getInt("reportedTransfer"));
                        if(fixture.has("reportedRange"))reported.setInteger("color-range",fixture.getInt("reportedRange"));
                    }
                    if(bridge!=null)bridge.outputFormat(reported);
                } else if(index>=0) {codec.releaseOutputBuffer(index,true);frames++;SystemClock.sleep(45);}
            }
            if(frames<8)throw new AssertionError("Decoder stalled");
            long outputDeadline=SystemClock.elapsedRealtime()+2000;
            do { texture.updateTexImage();if(texture.getTimestamp()!=0)break;SystemClock.sleep(20); }
            while(SystemClock.elapsedRealtime()<outputDeadline);
            if(texture.getTimestamp()==0)throw new AssertionError("No output Surface frame");
            texture.getTransformMatrix(transform);
            GLES20.glUseProgram(program);GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"transform"),1,false,transform,0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glFinish();
            ByteBuffer pixel=ByteBuffer.allocateDirect(4);JSONArray pixels=new JSONArray();int maxError=0;
            JSONArray expected=fixture.getJSONArray("expected");
            for(int i=0;i<16;i++) {
                pixel.clear();GLES20.glReadPixels((i%8)*80+40,360-((i/8)*180+90),1,1,
                        GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel);
                JSONArray actual=new JSONArray(),reference=expected.getJSONArray(i);
                for(int c=0;c<3;c++) {int value=pixel.get(c)&255;actual.put(value);maxError=Math.max(maxError,Math.abs(value-reference.getInt(c)));}
                pixels.put(actual);
            }
            if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new AssertionError("Readback error");
            java.lang.reflect.Field raw=NativeHdrOutput.class.getDeclaredField("rawYuv");raw.setAccessible(true);
            return new JSONObject().put("name",fixture.getString("name")).put("decoder",decoder).put("output",output)
                    .put("reportedTransfer",fixture.optInt("reportedTransfer",-1))
                    .put("reportedStandard",fixture.optInt("reportedStandard",-1))
                    .put("reportedRange",fixture.optInt("reportedRange",-1))
                    .put("frames",frames).put("surfaceTimestamp",texture.getTimestamp())
                    .put("gpuFrames",NativeHdrOutput.renderedFrameCount()).put("colorOutput",NativeHdrOutput.outputRoute(true))
                    .put("gpuTiming",NativeHdrOutput.performance())
                    .put("rawYuv",bridge!=null&&raw.getBoolean(bridge))
                    .put("pixels",pixels).put("expected",expected).put("maxError",maxError).put("pass",maxError<=5);
        } finally {
            if(codec!=null){try{codec.stop();}catch(IllegalStateException ignored){}finally{codec.release();}}
            if(bridge!=null)bridge.release();NativeHdrOutput.prepareSoftwareOutput();extractor.release();
        }
    }

    @Override public void onStart() {
        Bundle result=new Bundle();JSONArray report=new JSONArray();int failures=0;
        try {
            byte[] bytes;try(FileInputStream in=new FileInputStream(args.getString("manifest"))) {
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))>0)out.write(buffer,0,n);bytes=out.toByteArray();
            }
            JSONArray fixtures=new JSONArray(new String(bytes,"UTF-8"));NativeHdrOutput.initialize(getTargetContext(),null);initialize();
            if(!HdrDisplayPolicy.supportsTransfer(7,new int[]{3})
                    || HdrDisplayPolicy.passthrough(1,7,new int[]{3},25)
                    || HdrDisplayPolicy.passthrough(1,6,new int[]{2},24)
                    || !HdrDisplayPolicy.passthrough(1,7,new int[]{3},26)
                    || HdrDisplayPolicy.passthrough(1,7,new int[]{2},26)
                    || HdrDisplayPolicy.passthrough(3,7,new int[]{3},35))
                throw new AssertionError("HDR display/pipeline policy regression");
            int defaultMode=HdrMode.nativeValue(HdrMode.DEFAULT);
            for(int sdk:new int[]{14,19,25,26,29,30,31,35,37}){
                if(defaultMode!=1||HdrDisplayPolicy.decoderToneMapping(defaultMode,sdk)!=(sdk>=31))throw new AssertionError("Automatic HDR routing regression");
            }
            for(int i=0;i<fixtures.length();i++) {
                JSONObject row;
                try { row=measure(fixtures.getJSONObject(i)); }
                catch(Exception error) {
                    row=new JSONObject().put("name",fixtures.getJSONObject(i).getString("name"))
                            .put("pass",false).put("error",android.util.Log.getStackTraceString(error));
                }
                report.put(row);if(!row.getBoolean("pass"))failures++;
                Bundle progress=new Bundle();progress.putString("stream",row.toString()+"\n");sendStatus(0,progress);
            }
            File file=new File(getTargetContext().getExternalFilesDir(null),"hdr-decoder-color.json");
            try(FileOutputStream out=new FileOutputStream(file)){out.write(report.toString(2).getBytes("UTF-8"));}
            result.putString("stream","fixtures="+report.length()+" failures="+failures+" report="+file+"\n");finish(failures==0?-1:0,result);
        } catch(Throwable error){result.putString("stream",report+"\n"+android.util.Log.getStackTraceString(error));finish(0,result);}
        finally {
            if(surface!=null)surface.release();if(texture!=null)texture.release();
            if(display!=null){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(window!=null)EGL14.eglDestroySurface(display,window);if(context!=null)EGL14.eglDestroyContext(display,context);
                EGL14.eglTerminate(display);EGL14.eglReleaseThread();}
        }
    }
}
