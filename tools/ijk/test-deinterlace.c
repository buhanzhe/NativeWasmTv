/* Exercise the production temporal wrapper with real AVFrames. Test-only SDL
 * pthread adapters let this run without an Android JVM, not in the APK. */
#undef NDEBUG
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "libavutil/frame.h"
#include "libavutil/pixdesc.h"
#include "libavutil/mathematics.h"
#include "libavutil/error.h"
#include "libavutil/cpu.h"
#include "libavutil/adler32.h"
#include "ijksdl/ijksdl_thread.h"
#include "ijksdl/ijksdl_mutex.h"
SDL_mutex *SDL_CreateMutex(void) { SDL_mutex *m=calloc(1,sizeof(*m)); if(m)pthread_mutex_init(&m->id,NULL);return m; }
void SDL_DestroyMutex(SDL_mutex *m) {pthread_mutex_destroy(&m->id);free(m);}
int SDL_LockMutex(SDL_mutex *m) {return pthread_mutex_lock(&m->id);}
int SDL_UnlockMutex(SDL_mutex *m) {return pthread_mutex_unlock(&m->id);}
SDL_cond *SDL_CreateCond(void) {SDL_cond *c=calloc(1,sizeof(*c));if(c)pthread_cond_init(&c->id,NULL);return c;}
void SDL_DestroyCond(SDL_cond *c) {pthread_cond_destroy(&c->id);free(c);}
int SDL_CondWait(SDL_cond *c,SDL_mutex *m) {return pthread_cond_wait(&c->id,&m->id);}
int SDL_CondSignal(SDL_cond *c) {return pthread_cond_signal(&c->id);}
int SDL_CondBroadcast(SDL_cond *c) {return pthread_cond_broadcast(&c->id);}
static void *run(void *p) {SDL_Thread *t=p;t->retval=t->func(t->data);return NULL;}
SDL_Thread *SDL_CreateThreadEx(SDL_Thread *t,int (*fn)(void *),void *arg,const char *name) {
    t->func=fn;t->data=arg;return pthread_create(&t->id,NULL,run,t) ? NULL : t;
}
void SDL_WaitThread(SDL_Thread *t,int *result) {pthread_join(t->id,NULL);if(result)*result=t->retval;}
typedef struct {struct {int pkt_serial;} viddec; void *video_st;} VideoState;
typedef struct {VideoState *is;uint32_t ntv_video_description;} FFPlayer;
#define NTV_DESCRIPTION_FRAME (1U<<25)
static uint32_t ntv_describe_video(void *stream,void *codec,AVFrame *f) {return 0;}
/* Only these two integrations are stubbed; pixel processing, references,
 * threading, flushing and ownership run through ntv_deinterlace.h unchanged. */
static FILE *output;
static int count,expectedSerial,progressive;
static int scenario,expectedCount;
static int failAt=-1;
static double expectedPts[64],expectedDur[64];
static AVFrame *queued;
static unsigned long queuedChecksum;
static unsigned long checksum(const AVFrame *f) {
    const AVPixFmtDescriptor *d=av_pix_fmt_desc_get(f->format);
    unsigned long value=1;
    for(int p=0;p<3;++p) {
        int w=AV_CEIL_RSHIFT(f->width,p?d->log2_chroma_w:0)*(d->comp[0].depth>8?2:1);
        int h=AV_CEIL_RSHIFT(f->height,p?d->log2_chroma_h:0);
        for(int y=0;y<h;++y)value=av_adler32_update(value,f->data[p]+y*f->linesize[p],w);
    }
    return value;
}
static void check_queued(void) {
    if(queued)assert(checksum(queued)==queuedChecksum);
    av_frame_free(&queued);
}
static int queue_picture(FFPlayer *ffp,AVFrame *f,double pts,double duration,int64_t pos,int serial) {
    /* The display queue retains pixel references after the renderer reuses its
     * AVFrame object, switches formats or destroys the deinterlacer. */
    check_queued();
    if(count==failAt)return AVERROR(EIO);
    assert(!f->interlaced_frame && serial==expectedSerial);
    assert(f->color_primaries==AVCOL_PRI_BT2020 && f->color_trc==AVCOL_TRC_ARIB_STD_B67);
    assert(f->colorspace==AVCOL_SPC_BT2020_NCL && f->color_range==AVCOL_RANGE_MPEG);
    if(scenario) {
        assert(count<expectedCount);
        if(fabs(duration-expectedDur[count])>=0.000001)
            fprintf(stderr,"output %d pts=%f duration=%f expected=%f\n",count,pts,duration,expectedDur[count]);
        if(isnan(expectedPts[count])) assert(isnan(pts));
        else assert(fabs(pts-expectedPts[count])<0.000001);
        assert(fabs(duration-expectedDur[count])<0.000001);
    } else {
        assert(fabs(pts-count*(progressive ? 0.04 : 0.02))<0.000001);
        assert(fabs(duration-(progressive ? 0.04 : 0.02))<0.000001);
    }
    const AVPixFmtDescriptor *d=av_pix_fmt_desc_get(f->format);
    int bytes=d->comp[0].depth>8 ? 2 : 1;
    if(output)for(int p=0;p<3;++p) {
        int w=AV_CEIL_RSHIFT(f->width,p?d->log2_chroma_w:0),h=AV_CEIL_RSHIFT(f->height,p?d->log2_chroma_h:0);
        for(int y=0;y<h;++y)assert(fwrite(f->data[p]+y*f->linesize[p],1,w*bytes,output)==w*bytes);
    }
    queued=av_frame_clone(f);assert(queued);queuedChecksum=checksum(f);
    ++count;return 0;
}
/* viddec.avctx is unused by the metadata stub. */
#define ntv_describe_video(stream, codec, f) 0U
#include "ntv_deinterlace.h"

static AVFrame *frame(int w,int h,int depth,int index,int tff,int interlaced) {
    AVFrame *f=av_frame_alloc();assert(f);
    f->format=depth==8?AV_PIX_FMT_YUV420P:depth==9?AV_PIX_FMT_YUV420P9LE:
        depth==10?AV_PIX_FMT_YUV420P10LE:depth==12?AV_PIX_FMT_YUV420P12LE:
        depth==14?AV_PIX_FMT_YUV420P14LE:AV_PIX_FMT_YUV420P16LE;
    f->width=w;f->height=h;assert(av_frame_get_buffer(f,32)==0);
    f->pts=index;f->interlaced_frame=interlaced;f->top_field_first=tff;
    f->color_primaries=AVCOL_PRI_BT2020;f->color_trc=AVCOL_TRC_ARIB_STD_B67;
    f->colorspace=AVCOL_SPC_BT2020_NCL;f->color_range=AVCOL_RANGE_MPEG;return f;
}
static int read_frame(FILE *in,AVFrame *f,int depth) {
    for(int p=0;p<3;++p) {
        int w=AV_CEIL_RSHIFT(f->width,p?1:0)*(depth==8?1:2),h=AV_CEIL_RSHIFT(f->height,p?1:0);
        for(int y=0;y<h;++y) if(fread(f->data[p]+y*f->linesize[p],1,w,in)!=w)return 0;
    }
    return 1;
}
static void expect_frame(double pts,int interlaced,double duration) {
    expectedPts[expectedCount]=pts;expectedDur[expectedCount++]=duration/(interlaced?2:1);
    if(interlaced) {expectedPts[expectedCount]=pts+duration*.5;expectedDur[expectedCount++]=duration*.5;}
}
static void push_test(FFPlayer *ffp,NtvDeinterlace *s,int w,int h,int depth,int64_t pts,
        int tff,int interlaced,AVRational tb,AVRational rate) {
    AVFrame *f=frame(w,h,depth,0,tff,interlaced);f->pts=pts;
    for(int p=0;p<3;++p)memset(f->data[p],64,f->linesize[p]*AV_CEIL_RSHIFT(h,p?1:0));
    int used=ntv_deinterlace_push(ffp,s,f,tb,rate);assert(used>=0);
    if(!used)queue_picture(ffp,f,pts==AV_NOPTS_VALUE?NAN:pts*av_q2d(tb),av_q2d(av_inv_q(rate)),0,expectedSerial);
    av_frame_free(&f);
}
static void lifecycle(void) {
    VideoState is={0};FFPlayer ffp={.is=&is};NtvDeinterlace s={0};
    AVRational tb={1,25},rate={25,1};scenario=1;expectedSerial=is.viddec.pkt_serial=7;
    /* A stream can mix progressive and interlaced pictures in the same serial. */
    int flags[]={1,0,0,1,1,0,1,0};
    for(int i=0;i<8;++i)expect_frame(i*.04,flags[i],.04);
    for(int i=0;i<8;++i)push_test(&ffp,&s,64,32,8,i,i&1,flags[i],tb,rate);
    assert(ntv_deinterlace_flush(&ffp,&s,tb,rate)==0 && count==expectedCount);
    ntv_deinterlace_destroy(&s);puts("PASS mixed progressive/interlaced and field-order changes");
    memset(&s,0,sizeof(s));count=expectedCount=0;
    tb=(AVRational){1001,30000};rate=(AVRational){30000,1001};
    for(int i=0;i<8;++i)expect_frame(i*av_q2d(tb),1,av_q2d(tb));
    for(int i=0;i<8;++i)push_test(&ffp,&s,64,32,10,i,0,1,tb,rate);
    assert(ntv_deinterlace_flush(&ffp,&s,tb,rate)==0 && count==expectedCount);
    ntv_deinterlace_destroy(&s);puts("PASS 59.94-field fractional timestamps");
    memset(&s,0,sizeof(s));count=expectedCount=0;tb=(AVRational){1,25};rate=(AVRational){25,1};
    for(int i=0;i<3;++i)expect_frame(NAN,1,.04);
    for(int i=0;i<3;++i)push_test(&ffp,&s,64,32,8,AV_NOPTS_VALUE,1,1,tb,rate);
    assert(ntv_deinterlace_flush(&ffp,&s,tb,rate)==0 && count==expectedCount);
    ntv_deinterlace_destroy(&s);puts("PASS unknown timestamps");
    memset(&s,0,sizeof(s));count=expectedCount=0;
    expect_frame(0,1,.04);expect_frame(.08,1,.04);expect_frame(.12,1,.04);
    push_test(&ffp,&s,64,32,8,0,1,1,tb,rate);
    push_test(&ffp,&s,64,32,8,1,1,1,tb,rate);
    push_test(&ffp,&s,128,576,8,2,0,1,tb,rate);
    push_test(&ffp,&s,128,576,8,3,0,1,tb,rate);
    assert(ntv_deinterlace_flush(&ffp,&s,tb,rate)==0 && count==expectedCount);
    if(av_cpu_count()>1)assert(s.threads>1);
    ntv_deinterlace_destroy(&s);puts("PASS SD-to-HD worker upgrade and stale-size discard");
    memset(&s,0,sizeof(s));count=expectedCount=0;
    int positions[]={0,1,5,6};
    for(int i=0;i<4;++i)expect_frame(positions[i]*.04,1,.04);
    for(int i=0;i<4;++i)push_test(&ffp,&s,64,32,8,positions[i],1,1,tb,rate);
    assert(ntv_deinterlace_flush(&ffp,&s,tb,rate)==0 && count==expectedCount);
    ntv_deinterlace_destroy(&s);puts("PASS forward timestamp gap keeps nominal field spacing");
    int discontinuities[][4]={{0,1,1,2},{0,1,0,1}};
    for(int n=0;n<2;++n) {
        memset(&s,0,sizeof(s));count=expectedCount=0;
        for(int i=0;i<4;++i)expect_frame(discontinuities[n][i]*.04,1,.04);
        for(int i=0;i<4;++i)push_test(&ffp,&s,64,32,8,discontinuities[n][i],1,1,tb,rate);
        assert(!ntv_deinterlace_flush(&ffp,&s,tb,rate)&&count==expectedCount);
        ntv_deinterlace_destroy(&s);
    }
    puts("PASS duplicate and backward timestamps restart temporal history");
    memset(&s,0,sizeof(s));count=expectedCount=0;tb=(AVRational){1,50};
    for(int i=0;i<4;++i)expect_frame(i*.04,1,.04);
    for(int i=0;i<4;++i) {
        AVFrame *f=frame(64,32,8,0,1,1);f->pts=i*2;f->pkt_duration=1;
        assert(ntv_deinterlace_push(&ffp,&s,f,tb,rate)==1);av_frame_free(&f);
    }
    assert(ntv_deinterlace_flush(&ffp,&s,tb,rate)==0 && count==expectedCount);
    ntv_deinterlace_destroy(&s);puts("PASS field-coded packet durations do not double output cadence");
    memset(&s,0,sizeof(s));count=expectedCount=0;tb=(AVRational){1,25};
    for(int i=0;i<3;++i)expect_frame(i*.04,1,.04);
    for(int i=0;i<3;++i) {
        AVFrame *f=frame(64,32,8,i,1,1);
        if(i==1) {
            AVFrame *padded=av_frame_alloc();assert(padded);
            padded->format=f->format;padded->width=f->width;padded->height=f->height;
            assert(!av_frame_get_buffer(padded,128));assert(!av_frame_copy_props(padded,f));
            av_frame_free(&f);f=padded;
        }
        for(int p=0;p<3;++p)memset(f->data[p],64,f->linesize[p]*AV_CEIL_RSHIFT(f->height,p?1:0));
        assert(ntv_deinterlace_push(&ffp,&s,f,tb,rate)==1);av_frame_free(&f);
    }
    assert(!ntv_deinterlace_flush(&ffp,&s,tb,rate)&&count==expectedCount);
    ntv_deinterlace_destroy(&s);puts("PASS changing decoder strides");
    memset(&s,0,sizeof(s));count=expectedCount=0;
    for(int i=0;i<2;++i) {
        AVFrame *f=av_frame_alloc();assert(f);f->width=64;f->height=32;f->format=AV_PIX_FMT_RGB24;
        f->pts=i;f->interlaced_frame=1;assert(!av_frame_get_buffer(f,32));
        assert(ntv_deinterlace_push(&ffp,&s,f,tb,rate)==(i?AVERROR(ENOSYS):1));av_frame_free(&f);
    }
    ntv_deinterlace_destroy(&s);puts("PASS unsupported-format error cleanup");
    memset(&s,0,sizeof(s));count=expectedCount=0;failAt=1;
    expect_frame(0,1,.04);expect_frame(.04,1,.04);
    AVFrame *a=frame(64,576,8,0,1,1),*b=frame(64,576,8,1,1,1);
    assert(ntv_deinterlace_push(&ffp,&s,a,tb,rate)==1);
    assert(ntv_deinterlace_push(&ffp,&s,b,tb,rate)==AVERROR(EIO));
    av_frame_free(&a);av_frame_free(&b);ntv_deinterlace_destroy(&s);failAt=-1;
    puts("PASS output-abort worker cleanup");
    for(int n=0;n<32;++n) {
        memset(&s,0,sizeof(s));count=expectedCount=0;
        expect_frame(0,1,.04);expect_frame(.04,1,.04);
        push_test(&ffp,&s,64,576,n&1?10:8,0,n&1,1,tb,rate);
        push_test(&ffp,&s,64,576,n&1?10:8,1,n&1,1,tb,rate);
        assert(!ntv_deinterlace_flush(&ffp,&s,tb,rate)&&count==expectedCount);
        ntv_deinterlace_destroy(&s);
    }
    puts("PASS 32 threaded create/drain/destroy cycles");
    check_queued();puts("PASS queued pixels survive frame reuse and teardown");
}
int main(int argc,char **argv) {
    setvbuf(stdout,NULL,_IONBF,0);
    if(argc==2 && !strcmp(argv[1],"--lifecycle")){lifecycle();return 0;}
    assert(argc==9);
    int w=atoi(argv[2]),h=atoi(argv[3]),depth=atoi(argv[4]),tff=atoi(argv[5]);progressive=atoi(argv[6]);
    int expected=atoi(argv[8]);FILE *in=fopen(argv[1],"rb");output=fopen(argv[7],"wb");assert(in&&output);
    VideoState is={0};FFPlayer ffp={.is=&is};NtvDeinterlace state={0};is.viddec.pkt_serial=expectedSerial=3;
    int frames=0;
    for(;;) {
        AVFrame *f=frame(w,h,depth,frames,tff,!progressive);
        if(!read_frame(in,f,depth)) {av_frame_free(&f);break;}
        int used=ntv_deinterlace_push(&ffp,&state,f,(AVRational){1,25},(AVRational){25,1});assert(used>=0);
        if(!used) {assert(progressive);queue_picture(&ffp,f,frames*.04,.04,0,expectedSerial);}
        else assert(!f->buf[0]);
        av_frame_free(&f);++frames;
    }
    assert(ntv_deinterlace_flush(&ffp,&state,(AVRational){1,25},(AVRational){25,1})==0);
    assert(count==expected && count==frames*(progressive?1:2));
    assert(!state.cur && !state.prev && !state.next);
    printf("PASS frames=%d output=%d threads=%d depth=%d tff=%d progressive=%d\n",frames,count,state.threads,depth,tff,progressive);
    ntv_deinterlace_destroy(&state);fclose(in);fclose(output);output=NULL;
    /* A seek must discard the delayed old frame and all temporal references. */
    memset(&state,0,sizeof(state));count=0;progressive=0;
    AVFrame *a=frame(64,32,8,0,1,1);assert(ntv_deinterlace_push(&ffp,&state,a,(AVRational){1,25},(AVRational){25,1})==1);av_frame_free(&a);
    ++is.viddec.pkt_serial;expectedSerial=is.viddec.pkt_serial;
    a=frame(64,32,8,0,1,1);assert(ntv_deinterlace_push(&ffp,&state,a,(AVRational){1,25},(AVRational){25,1})==1);av_frame_free(&a);
    assert(!count && state.serial==expectedSerial);
    assert(ntv_deinterlace_flush(&ffp,&state,(AVRational){1,25},(AVRational){25,1})==0 && count==2);
    ntv_deinterlace_destroy(&state);check_queued();puts("PASS seek/flush/timestamps/colour metadata/ownership");return 0;
}
