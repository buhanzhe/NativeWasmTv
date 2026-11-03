/* LGPL-2.1-or-later. Exercise the actual SDL overlay and GLES renderer. */
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include "ijksdl/gles2/internal.h"
#include "ijksdl/ffmpeg/ijksdl_vout_overlay_ffmpeg.h"
#include "libavutil/frame.h"
#include "ntv_test_fps.h"

static void pixels(AVFrame *f, int y, int u, int v)
{
    assert(av_frame_make_writable(f) >= 0);
    int ten = f->format != AV_PIX_FMT_YUV420P;
    int sub = f->format != AV_PIX_FMT_YUV444P10LE;
    int values[] = {y,u,v};
    for (int p=0;p<3;p++) {
        int w=p && sub ? (f->width+1)/2 : f->width;
        int h=p && sub ? (f->height+1)/2 : f->height;
        for (int row=0;row<h;row++) for (int x=0;x<w;x++) {
            uint8_t *dst=f->data[p]+row*f->linesize[p];
            if (ten) ((uint16_t *)dst)[x]=values[p]; else dst[x]=values[p];
        }
    }
}
static AVFrame *frame(int format,int w,int h)
{
    AVFrame *f=av_frame_alloc();assert(f);
    f->format=format;f->width=w;f->height=h;
    f->colorspace=AVCOL_SPC_BT709;f->color_range=AVCOL_RANGE_MPEG;
    f->color_trc=AVCOL_TRC_BT709;f->color_primaries=AVCOL_PRI_BT709;
    assert(av_frame_get_buffer(f,32)>=0);return f;
}
static void check_color(int y,int u,int v,int ten,int full)
{
    unsigned char rgba[4];glReadPixels(56,32,1,1,GL_RGBA,GL_UNSIGNED_BYTE,rgba);
    double scale=ten?4:1,maximum=ten?1023:255;
    double yy=full?y/maximum:(y-16*scale)/(219*scale);
    double uu=(u-128*scale)/(full?maximum:224*scale),vv=(v-128*scale)/(full?maximum:224*scale);
    double rgb[]={yy+1.5748*vv,yy-0.187324*uu-0.468124*vv,yy+1.8556*uu};
    for (int p=0;p<3;p++) {
        int expected=(int)lrint(fmax(0,fmin(1,rgb[p]))*255);
        if(abs(rgba[p]-expected)>3) {
            fprintf(stderr,"YUV=%d,%d,%d ten=%d component=%d got=%d expected=%d\n",y,u,v,ten,p,rgba[p],expected);
            assert(0);
        }
    }
    assert(glGetError()==GL_NO_ERROR);
}
static double now_ms(void)
{
    struct timespec ts;clock_gettime(CLOCK_MONOTONIC,&ts);
    return ts.tv_sec*1000.+ts.tv_nsec/1e6;
}
int main(int argc,char **argv)
{
    test_picture_rate();
    int bench_only=argc>1&&!strcmp(argv[1],"--bench");
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);assert(d!=EGL_NO_DISPLAY);
    assert(eglInitialize(d,NULL,NULL));
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config;EGLint count;assert(eglChooseConfig(d,attrs,&config,1,&count)&&count);
    EGLint pbuf[]={EGL_WIDTH,64,EGL_HEIGHT,64,EGL_NONE},ctx[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    EGLSurface surface=eglCreatePbufferSurface(d,config,pbuf);assert(surface!=EGL_NO_SURFACE);
    EGLContext context=eglCreateContext(d,config,EGL_NO_CONTEXT,ctx);assert(context!=EGL_NO_CONTEXT);
    assert(eglMakeCurrent(d,surface,surface,context));glViewport(0,0,64,64);
    SDL_Vout display={0};display.overlay_format=SDL_FCC__GLES2;
    int formats[]={AV_PIX_FMT_YUV420P,AV_PIX_FMT_YUV420P10LE,AV_PIX_FMT_YUV444P10LE};
    for (int n=0;n<3&&!bench_only;n++) {
        IJK_GLES2_Renderer *renderer=NULL;
        for(int size=0;size<2;size++) {
            AVFrame *f=frame(formats[n],size?(n==1?66:128):64,size?35:18);
            SDL_VoutOverlay *overlay=SDL_VoutFFmpeg_CreateOverlay(f->width,f->height,f->format,&display);assert(overlay);
            int ten=n!=0,scale=ten?4:1;
            for(int change=0;change<6;change++) {
                int full=change>=3,bar=change%3,maximum=ten?1023:255;
                f->color_range=full?AVCOL_RANGE_JPEG:AVCOL_RANGE_MPEG;
                int y=bar==0?(full?0:16*scale):bar==1?(full?maximum:235*scale):120*scale;
                int u=(bar==2?80:128)*scale,v=(bar==2?185:128)*scale;
                pixels(f,y,u,v);assert(overlay->func_fill_frame(overlay,f)==0);
                if(n==1) {
                    assert(overlay->format==SDL_FCC_I420P10LE);
                    for(int p=0;p<3;p++)assert(overlay->pixels[p]==f->data[p]);
                }
                if(!renderer) {
                    renderer=IJK_GLES2_Renderer_create(overlay);
                    assert(renderer&&IJK_GLES2_Renderer_use(renderer));
                }
                IJK_GLES2_Renderer_setGravity(renderer,IJK_GLES2_GRAVITY_RESIZE,64,64);
                assert(IJK_GLES2_Renderer_renderOverlay(renderer,overlay));check_color(y,u,v,ten,full);
            }
            SDL_VoutFreeYUVOverlay(overlay);av_frame_free(&f);
        }
        IJK_GLES2_Renderer_reset(renderer);IJK_GLES2_Renderer_free(renderer);
    }
    /* Compare only conversion/texture submission, separately from decoder capacity. */
    AVFrame *f=frame(AV_PIX_FMT_YUV420P10LE,3840,2160);pixels(f,480,512,512);
    SDL_VoutOverlay *overlay=SDL_VoutFFmpeg_CreateOverlay(f->width,f->height,f->format,&display);assert(overlay);
    IJK_GLES2_Renderer *r=NULL;double start=now_ms();
    for(int i=0;i<40;i++) {
        assert(overlay->func_fill_frame(overlay,f)==0);
        if(!r){r=IJK_GLES2_Renderer_create(overlay);assert(r);IJK_GLES2_Renderer_use(r);IJK_GLES2_Renderer_setGravity(r,0,64,64);}
        assert(IJK_GLES2_Renderer_renderOverlay(r,overlay));glFinish();assert(glGetError()==GL_NO_ERROR);
    }
    printf("PASS: %s; 4K upload+conversion %.3f ms/frame (format=%x)\n",bench_only?"benchmark only":"picture-rate limits, real 8/10-bit 420/444 overlays, zero-copy planes, limited/full range, black/white/colour, updates and resize",(now_ms()-start)/40,overlay->format);
    IJK_GLES2_Renderer_reset(r);IJK_GLES2_Renderer_free(r);SDL_VoutFreeYUVOverlay(overlay);av_frame_free(&f);
    eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    eglDestroyContext(d,context);eglDestroySurface(d,surface);eglTerminate(d);return 0;
}
