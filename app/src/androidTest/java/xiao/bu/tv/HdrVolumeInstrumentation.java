package xiao.bu.tv;

import android.app.Instrumentation;
import android.opengl.*;
import android.os.Bundle;
import java.nio.*;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Release/JNI integration on a pbuffer. Does not create an Activity or decoder. */
public final class HdrVolumeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static int shader(int kind,String source){
        int id=GLES20.glCreateShader(kind);GLES20.glShaderSource(id,source);GLES20.glCompileShader(id);
        int[] status=new int[1];GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,status,0);
        if(status[0]==0)throw new AssertionError(GLES20.glGetShaderInfoLog(id));return id;
    }
    @Override public void onStart(){
        Bundle result=new Bundle();int code=0,program=0,texture=0;
        EGLDisplay display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGLContext context=EGL14.EGL_NO_CONTEXT;EGLSurface surface=EGL14.EGL_NO_SURFACE;
        try {
            IjkMediaPlayer.loadLibrariesOnce(null);
            int[] version=new int[2];if(!EGL14.eglInitialize(display,version,0,version,1))throw new AssertionError("EGL init");
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
                    EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_NONE};
            if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]==0)throw new AssertionError("EGL config");
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw new AssertionError("EGL current");
            String extensions=GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if(extensions==null||!(" "+extensions+" ").contains(" GL_OES_texture_3D ")){
                result.putString("stream","SKIP 3D extension unavailable; production retains 2D lookup\n");code=-1;
            }else{
                if(NativeHdrOutput.uploadLut3d(null)||NativeHdrOutput.uploadLut3d(ByteBuffer.allocateDirect(8))
                        ||NativeHdrOutput.uploadLut3d(ByteBuffer.allocate(64*64*64*4)))throw new AssertionError("Invalid JNI buffer accepted");
                ByteBuffer cube=ByteBuffer.allocateDirect(64*64*64*4);
                for(int z=0;z<64;z++)for(int y=0;y<64;y++)for(int x=0;x<64;x++)
                    cube.put((byte)Math.round(x*255f/63)).put((byte)Math.round(y*255f/63)).put((byte)Math.round(z*255f/63)).put((byte)255);
                cube.flip();int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);texture=ids[0];
                GLES20.glBindTexture(0x806F,texture);
                GLES20.glTexParameteri(0x806F,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
                GLES20.glTexParameteri(0x806F,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
                GLES20.glTexParameteri(0x806F,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(0x806F,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(0x806F,0x8072,GLES20.GL_CLAMP_TO_EDGE);
                if(!NativeHdrOutput.uploadLut3d(cube))throw new AssertionError("Production JNI volume upload failed");
                int vs=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 position;void main(){gl_Position=vec4(position,0.,1.);}");
                int fs=shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_texture_3D : require\nprecision highp float;uniform highp sampler3D lut;uniform vec3 color;uniform float edge;void main(){gl_FragColor=texture3D(lut,(clamp(color,0.,1.)*(edge-1.)+.5)/edge);}");
                program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);
                GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);int[] linked=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);
                if(linked[0]==0)throw new AssertionError(GLES20.glGetProgramInfoLog(program));
                GLES20.glUseProgram(program);GLES20.glViewport(0,0,1,1);GLES20.glDisable(GLES20.GL_DITHER);
                FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
                int pos=GLES20.glGetAttribLocation(program,"position");GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glEnableVertexAttribArray(pos);
                ByteBuffer pixel=ByteBuffer.allocateDirect(4);float[][] colors={{0,0,0},{1,1,1},{1,0,0},{0,1,0},{0,0,1},{.3f,.5f,.7f}};
                if(NativeHdrOutput.uploadLut3d128(null)||NativeHdrOutput.uploadLut3d128(ByteBuffer.allocateDirect(8)))throw new AssertionError("128 upload accepted short buffer");
                for(int edge:new int[]{64,128}){
                    if(edge==128){
                        ByteBuffer large=ByteBuffer.allocateDirect(128*128*128*4);
                        for(int z=0;z<128;z++)for(int y=0;y<128;y++)for(int x=0;x<128;x++)
                            large.put((byte)Math.round(x*255f/127)).put((byte)Math.round(y*255f/127)).put((byte)Math.round(z*255f/127)).put((byte)255);
                        large.flip();if(!NativeHdrOutput.uploadLut3d128(large))throw new AssertionError("128 production JNI upload failed");
                    }
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"edge"),edge);
                for(float[] color:colors){
                    GLES20.glUniform3fv(GLES20.glGetUniformLocation(program,"color"),1,color,0);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                    pixel.clear();GLES20.glReadPixels(0,0,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel);
                    for(int channel=0;channel<3;channel++)if(Math.abs((pixel.get(channel)&255)-Math.round(color[channel]*255))>2)throw new AssertionError("3D sample/order error");
                }
                }
                if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new AssertionError("GL error");
                result.putString("stream","PASS production release JNI registration, direct-buffer bounds, 64/128-cubed uploads and 12 trilinear colour samples; no playback\n");code=-1;
            }
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            if(program!=0)GLES20.glDeleteProgram(program);if(texture!=0)GLES20.glDeleteTextures(1,new int[]{texture},0);
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);
        }
        finish(code,result);
    }
}
