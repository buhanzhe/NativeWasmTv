package xiao.bu.tv;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.microedition.khronos.egl.*;
import net.ypresto.androidtranscoder.engine.TextureRender;

/** One-shot GLES2 readback. No permanent frame copying on old devices. */
final class LegacyVideoFrame implements java.io.Closeable {
    private final EGL10 egl=(EGL10)EGLContext.getEGL();
    private EGLDisplay display=EGL10.EGL_NO_DISPLAY;
    private EGLContext context=EGL10.EGL_NO_CONTEXT;
    private EGLSurface buffer=EGL10.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface surface;
    private final TextureRender renderer=new TextureRender();
    private final CountDownLatch frame=new CountDownLatch(1);
    private final int width,height;
    LegacyVideoFrame(int width,int height)throws IOException {
        this.width=width;this.height=height;
        try {
            display=egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if(!egl.eglInitialize(display,new int[2]))throw new IOException("截图 EGL 初始化失败");
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            int[] attrs={EGL10.EGL_SURFACE_TYPE,EGL10.EGL_PBUFFER_BIT,0x3040,4,EGL10.EGL_RED_SIZE,8,EGL10.EGL_GREEN_SIZE,8,EGL10.EGL_BLUE_SIZE,8,EGL10.EGL_ALPHA_SIZE,8,EGL10.EGL_NONE};
            if(!egl.eglChooseConfig(display,attrs,configs,1,count)||count[0]==0)throw new IOException("设备不支持截图纹理");
            context=egl.eglCreateContext(display,configs[0],EGL10.EGL_NO_CONTEXT,new int[]{0x3098,2,EGL10.EGL_NONE});
            buffer=egl.eglCreatePbufferSurface(display,configs[0],new int[]{EGL10.EGL_WIDTH,width,EGL10.EGL_HEIGHT,height,EGL10.EGL_NONE});
            if(!egl.eglMakeCurrent(display,buffer,buffer,context))throw new IOException("截图缓冲区创建失败");
            renderer.surfaceCreated();texture=new SurfaceTexture(renderer.getTextureId());
            texture.setOnFrameAvailableListener(t->frame.countDown());surface=new Surface(texture);
        }catch(Throwable e){close();if(e instanceof OutOfMemoryError)throw (OutOfMemoryError)e;throw new IOException("旧系统截图初始化失败",e);}
    }
    Surface surface(){return surface;}
    Bitmap read()throws IOException {
        try{if(!frame.await(4,TimeUnit.SECONDS))throw new IOException("未收到截图帧，请在播放时重试");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("截图已取消",e);}
        texture.updateTexImage();GLES20.glViewport(0,0,width,height);renderer.drawFrame(texture);
        ByteBuffer pixels=ByteBuffer.allocateDirect(width*height*4);
        GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
        if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IOException("截图像素读取失败");
        int[] argb=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int o=(y*width+x)*4;
            argb[(height-1-y)*width+x]=0xff000000|((pixels.get(o)&255)<<16)|((pixels.get(o+1)&255)<<8)|(pixels.get(o+2)&255);
        }
        return Bitmap.createBitmap(argb,width,height,Bitmap.Config.ARGB_8888);
    }
    @Override public void close(){
        if(surface!=null){surface.release();surface=null;}if(texture!=null){texture.release();texture=null;}
        if(display!=EGL10.EGL_NO_DISPLAY){
            egl.eglMakeCurrent(display,EGL10.EGL_NO_SURFACE,EGL10.EGL_NO_SURFACE,EGL10.EGL_NO_CONTEXT);
            if(buffer!=EGL10.EGL_NO_SURFACE)egl.eglDestroySurface(display,buffer);
            if(context!=EGL10.EGL_NO_CONTEXT)egl.eglDestroyContext(display,context);
            egl.eglTerminate(display);display=EGL10.EGL_NO_DISPLAY;
        }
    }
}
