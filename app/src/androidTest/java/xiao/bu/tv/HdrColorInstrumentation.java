package xiao.bu.tv;

import android.app.Instrumentation;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Measures the production shader on a real GLES driver, without a decoder. */
public final class HdrColorInstrumentation extends Instrumentation {
    private int program, failures, checks;
    private final StringBuilder results = new StringBuilder();
    private final ByteBuffer pixel = ByteBuffer.allocateDirect(4);

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    private int shader(int kind, String source) {
        int id = GLES20.glCreateShader(kind);
        GLES20.glShaderSource(id, source); GLES20.glCompileShader(id);
        int[] status = new int[1]; GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) throw new AssertionError(GLES20.glGetShaderInfoLog(id));
        return id;
    }

    private int[] sample(float transfer, float peak, float r, float g, float b) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "ntvTransfer"), transfer);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "ntvPeak"), peak);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "color"), r, g, b);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        pixel.clear(); GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw new AssertionError("GL readback failed");
        return new int[]{pixel.get(0) & 255, pixel.get(1) & 255, pixel.get(2) & 255};
    }

    private void check(String name, int[] rgb, int r, int g, int b, int tolerance) {
        checks++;
        boolean pass = Math.abs(rgb[0]-r)<=tolerance && Math.abs(rgb[1]-g)<=tolerance && Math.abs(rgb[2]-b)<=tolerance;
        if (!pass) failures++;
        results.append(name).append(" = ").append(java.util.Arrays.toString(rgb))
                .append(pass ? " PASS\n" : " FAIL\n");
    }

    @Override public void onStart() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        Bundle result = new Bundle();
        try {
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new AssertionError("EGL init");
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            int[] attrs = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                    EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_NONE};
            if (!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0) || count[0]==0)
                throw new AssertionError("EGL config");
            context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            surface = EGL14.eglCreatePbufferSurface(display,configs[0],
                    new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            if (!EGL14.eglMakeCurrent(display,surface,surface,context)) throw new AssertionError("EGL current");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = getTargetContext().getResources().openRawResource(R.raw.hdr_tonemap)) {
                byte[] buffer = new byte[4096]; int n;
                while ((n=input.read(buffer))>0) bytes.write(buffer,0,n);
            }
            int vs = shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 position;void main(){gl_Position=vec4(position,0.,1.);}");
            int fs = shader(GLES20.GL_FRAGMENT_SHADER,"precision highp float;uniform vec3 color;uniform float benchmark;\n"
                    + bytes.toString("UTF-8") + HdrToneMapLut.LOOKUP + "\nvoid main(){gl_FragColor=vec4(ntvFastToSdr(benchmark>0.5?vec3(fract(gl_FragCoord.x/1920.0),fract(gl_FragCoord.y/1080.0),fract((gl_FragCoord.x+gl_FragCoord.y)/3000.0)):color),1.);}");
            program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,vs); GLES20.glAttachShader(program,fs);
            GLES20.glLinkProgram(program); GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs);
            int[] linked=new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);
            if(linked[0]==0)throw new AssertionError(GLES20.glGetProgramInfoLog(program));
            GLES20.glUseProgram(program); GLES20.glViewport(0,0,1,1); GLES20.glDisable(GLES20.GL_DITHER);
            FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
            int pos=GLES20.glGetAttribLocation(program,"position");
            GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices); GLES20.glEnableVertexAttribArray(pos);
            for(int i=0;i<=16;i++) {
                float value=i/16f; int code=Math.round(value*255);
                check("SDR bypass "+i,sample(0,1000,value,value,value),code,code,code,1);
                check("BT2020 neutral "+i,sample(3,1000,value,value,value),code,code,code,1);
            }
            // BT.2446 method-B reference viewing conditions: HLG peak 291 nits,
            // system gamma 1.03; the 55-nit knee preserves shadows/midtones.
            check("HLG black",sample(7,1000,0,0,0),0,0,0,1);
            check("HLG quarter",sample(7,1000,.25f,.25f,.25f),76,76,76,2);
            check("HLG mid",sample(7,1000,.5f,.5f,.5f),137,137,137,2);
            check("HLG reference white",sample(7,1000,.75f,.75f,.75f),219,219,219,3);
            check("HLG peak",sample(7,1000,1,1,1),255,255,255,1);
            // HLG encoded BT.709 primaries at 203 nits in a BT.2020 container.
            // Derived independently from BT.2100 and the BT.709 -> 2020 matrix.
            float[][] colors={{.70853657f,.26654934f,.12982403f},
                    {.52480767f,.74436791f,.27190927f},{.23091570f,.11827064f,.81335211f},
                    {.74362277f,.75008574f,.28979381f},{.71215887f,.28070973f,.77276848f}};
            int[][] primary={{1,0,0},{0,1,0},{0,0,1},{1,1,0},{1,0,1}};
            for(int i=0;i<colors.length;i++) {
                int[] rgb=sample(7,1000,colors[i][0],colors[i][1],colors[i][2]);
                int largest=Math.max(rgb[0],Math.max(rgb[1],rgb[2]));
                check("HLG primary "+i,rgb,largest*primary[i][0],largest*primary[i][1],largest*primary[i][2],3);
                if(largest<160) { failures++; results.append("Primary too dim\n"); }
            }
            // 203-nit PQ and 75% HLG represent the same reference white at the
            // 1000-nit HLG/PQ bridge; both output paths should match.
            check("PQ reference white",sample(6,1000,.58068888f,.58068888f,.58068888f),219,219,219,3);
            int previous=-1;
            for(int i=0;i<=128;i++) {
                int[] rgb=sample(7,1000,i/128f,i/128f,i/128f);
                if(rgb[0]<previous || Math.abs(rgb[0]-rgb[1])>1 || Math.abs(rgb[0]-rgb[2])>1)
                    throw new AssertionError("Nonmonotonic/nonneutral HLG ramp at "+i);
                previous=rgb[0];
            }
            HdrToneMapLut lut=new HdrToneMapLut(bytes.toString("UTF-8"));
            // This differential fixture compares the legacy two-sample lookup.
            // The optional 3D upload is covered by HdrVolumeInstrumentation.
            lut.disableVolume();
            try {
                for(int trc:new int[]{6,7}) {
                    GLES20.glUseProgram(program);GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"benchmark"),0);
                    int texture=lut.update(trc,1000);
                    GLES20.glUseProgram(program);GLES20.glViewport(0,0,1,1);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"ntvLut"),1);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    vertices.position(0);GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices);
                    java.util.Random random=new java.util.Random(42);int maxError=0;long totalError=0;
                    for(int n=0;n<600;n++) {
                        float r=random.nextFloat(),g=random.nextFloat(),b=random.nextFloat();
                        if(n<129)r=g=b=n/128f;
                        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvUseLut"),0);
                        int[] reference=sample(trc,1000,r,g,b);
                        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvUseLut"),1);
                        int[] mapped=sample(trc,1000,r,g,b);
                        for(int c=0;c<3;c++){int delta=Math.abs(reference[c]-mapped[c]);maxError=Math.max(maxError,delta);totalError+=delta;}
                    }
                    results.append("LUT transfer="+trc+" maxError="+maxError+" meanError="+(totalError/1800f)+"\n");
                    if(maxError>24 || totalError/1800f>1.5f)throw new AssertionError("LUT colour deviation "+maxError);
                    // Same full-screen fragment workload; exclude table creation and warmup.
                    int[] fbo=new int[1],target=new int[1];GLES20.glGenTextures(1,target,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,target[0]);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,1920,1080,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
                    GLES20.glGenFramebuffers(1,fbo,0);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo[0]);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,target[0],0);
                    if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Benchmark FBO");
                    GLES20.glViewport(0,0,1920,1080);
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"benchmark"),1);
                    for(int mode=0;mode<2;mode++){
                        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvUseLut"),mode);
                        for(int n=0;n<4;n++)GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glFinish();
                        long start=System.nanoTime();
                        for(int n=0;n<20;n++){GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glFinish();}
                        results.append("GPU 1080p transfer="+trc+" LUT="+mode+" ms="+((System.nanoTime()-start)/20000000.0)+"\n");
                    }
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);GLES20.glDeleteFramebuffers(1,fbo,0);GLES20.glDeleteTextures(1,target,0);
                }
            }finally{lut.release();}
            result.putString("stream",results+"checks="+checks+" failures="+failures+"\n");
            finish(failures==0 ? -1 : 0,result);
        } catch(Throwable error) {
            result.putString("stream",results+android.util.Log.getStackTraceString(error)); finish(0,result);
        } finally {
            if(program!=0)GLES20.glDeleteProgram(program);
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display); EGL14.eglReleaseThread();
        }
    }
}
