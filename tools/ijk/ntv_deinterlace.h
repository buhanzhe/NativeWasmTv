/* LGPL-2.1-or-later. BWDIF send-field/auto parity, for software frames only.
 * The spatial/temporal kernels retain FFmpeg/BBC's original attribution. */
#ifndef NTV_DEINTERLACE_H
#define NTV_DEINTERLACE_H
#include "ntv_bwdif_kernel.h"
#include "libavutil/frame.h"
#include "libavutil/pixdesc.h"
#include "libavutil/cpu.h"
#include "ijksdl/ijksdl_thread.h"
#include "ijksdl/ijksdl_mutex.h"

typedef struct NtvDeinterlace NtvDeinterlace;
typedef struct NtvDeinterlaceWorker {
    NtvDeinterlace *owner;
    SDL_Thread thread;
    int index, generation;
} NtvDeinterlaceWorker;
struct NtvDeinterlace {
    AVFrame *prev, *cur, *next, *out;
    int serial, first_field, parity, tff, spatial;
    int threads, generation, remaining, stopped;
    SDL_mutex *mutex;
    SDL_cond *work, *done;
    NtvDeinterlaceWorker worker[4];
};

static int ntv_deinterlace_supported(const AVFrame *f)
{
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(f->format);
    if (!d || !(d->flags & AV_PIX_FMT_FLAG_PLANAR)
            || (d->flags & (AV_PIX_FMT_FLAG_RGB | AV_PIX_FMT_FLAG_BE | AV_PIX_FMT_FLAG_HWACCEL))
            || d->nb_components != 3 || f->width < 4 || f->height < 16) return 0;
    for (int p=0;p<3;++p)
        if (d->comp[p].plane != p || d->comp[p].depth != d->comp[0].depth
                || d->comp[p].depth < 8 || d->comp[p].depth > 16
                || d->comp[p].step != (d->comp[p].depth > 8 ? 2 : 1)) return 0;
    return 1;
}

static void ntv_deinterlace_slice(NtvDeinterlace *s, int job, int jobs)
{
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(s->cur->format);
    int bytes = d->comp[0].depth > 8 ? 2 : 1;
    int clip = (1 << d->comp[0].depth) - 1;
    for (int p=0;p<3;++p) {
        int w=AV_CEIL_RSHIFT(s->cur->width,p ? d->log2_chroma_w : 0);
        int h=AV_CEIL_RSHIFT(s->cur->height,p ? d->log2_chroma_h : 0);
        int stride=s->cur->linesize[p], refs=stride/bytes;
        for (int y=h*job/jobs;y<h*(job+1)/jobs;++y) {
            uint8_t *dst=s->out->data[p]+y*s->out->linesize[p];
            uint8_t *cur=s->cur->data[p]+y*stride;
            if (!((y ^ s->parity) & 1)) { memcpy(dst,cur,w*bytes); continue; }
            uint8_t *prev=s->prev->data[p]+y*stride, *next=s->next->data[p]+y*stride;
            int parity=s->parity ^ s->tff;
            if (s->spatial) {
                if(bytes==1) ntv_filter_intra(dst,cur,w,y+1<h?refs:-refs,y>0?-refs:refs,
                        y+3<h?3*refs:-refs,y>2?-3*refs:refs,parity,clip);
                else ntv_filter_intra_16bit(dst,cur,w,y+1<h?refs:-refs,y>0?-refs:refs,
                        y+3<h?3*refs:-refs,y>2?-3*refs:refs,parity,clip);
            } else if(y<4 || y+5>h) {
                int spat=!(y<2 || y+3>h);
                if(bytes==1) ntv_filter_edge(dst,prev,cur,next,w,y+1<h?refs:-refs,
                        y>0?-refs:refs,2*refs,-2*refs,parity,clip,spat);
                else ntv_filter_edge_16bit(dst,prev,cur,next,w,y+1<h?refs:-refs,
                        y>0?-refs:refs,2*refs,-2*refs,parity,clip,spat);
            } else {
                if(bytes==1) ntv_filter_line_c(dst,prev,cur,next,w,refs,-refs,2*refs,-2*refs,
                        3*refs,-3*refs,4*refs,-4*refs,parity,clip);
                else ntv_filter_line_c_16bit(dst,prev,cur,next,w,refs,-refs,2*refs,-2*refs,
                        3*refs,-3*refs,4*refs,-4*refs,parity,clip);
            }
        }
    }
}

static int ntv_deinterlace_worker(void *arg)
{
    NtvDeinterlaceWorker *w=arg; NtvDeinterlace *s=w->owner;
    SDL_LockMutex(s->mutex);
    for (;;) {
        while(!s->stopped && w->generation==s->generation) SDL_CondWait(s->work,s->mutex);
        if(s->stopped) break;
        w->generation=s->generation;
        SDL_UnlockMutex(s->mutex); ntv_deinterlace_slice(s,w->index,s->threads); SDL_LockMutex(s->mutex);
        if(!--s->remaining) SDL_CondSignal(s->done);
    }
    SDL_UnlockMutex(s->mutex); return 0;
}

static void ntv_deinterlace_clear(NtvDeinterlace *s)
{
    av_frame_free(&s->prev); av_frame_free(&s->cur); av_frame_free(&s->next);
    s->first_field=1;
}

static void ntv_deinterlace_destroy(NtvDeinterlace *s)
{
    if(s->mutex) {
        SDL_LockMutex(s->mutex); s->stopped=1; SDL_CondBroadcast(s->work); SDL_UnlockMutex(s->mutex);
        for(int i=0;i<s->threads-1;++i) SDL_WaitThread(&s->worker[i].thread,NULL);
        SDL_DestroyCond(s->work); SDL_DestroyCond(s->done); SDL_DestroyMutex(s->mutex);
    }
    ntv_deinterlace_clear(s); av_frame_free(&s->out);
}

static int ntv_deinterlace_pool(NtvDeinterlace *s)
{
    /* SD can initialise a single-thread path before an adaptive switch to HD.
     * A created mutex also marks an attempted pool, even if no worker started. */
    if(s->threads>1 || s->mutex) return 0;
    s->threads=1;
    if(s->cur->height<576) return 0;
    int wanted=FFMIN(4,av_cpu_count());
    if(wanted<=1) return 0;
    s->mutex=SDL_CreateMutex(); s->work=SDL_CreateCond(); s->done=SDL_CreateCond();
    if(!s->mutex || !s->work || !s->done) {
        if(s->mutex) SDL_DestroyMutex(s->mutex);
        if(s->work) SDL_DestroyCond(s->work);
        if(s->done) SDL_DestroyCond(s->done);
        s->mutex=NULL; s->work=s->done=NULL;
        return AVERROR(ENOMEM);
    }
    for(int i=0;i<wanted-1;++i) {
        s->worker[i].owner=s; s->worker[i].index=i+1;
        if(!SDL_CreateThreadEx(&s->worker[i].thread,ntv_deinterlace_worker,&s->worker[i],"nTv BWDIF")) break;
        ++s->threads;
    }
    return 0;
}

static int ntv_deinterlace_render(NtvDeinterlace *s, int second, int last)
{
    if(s->out) av_frame_unref(s->out);
    else s->out=av_frame_alloc();
    if(!s->out) return AVERROR(ENOMEM);
    s->out->format=s->cur->format; s->out->width=s->cur->width; s->out->height=s->cur->height;
    int ret=av_frame_get_buffer(s->out,32); if(ret<0) return ret;
    ret=av_frame_copy_props(s->out,s->cur); if(ret<0) return ret;
    s->out->interlaced_frame=0; s->out->repeat_pict=0;
    s->tff=s->cur->top_field_first;
    s->parity=s->tff ^ !second;
    s->spatial=s->first_field || (second && last); s->first_field=0;
    ret=ntv_deinterlace_pool(s); if(ret<0) return ret;
    if(s->threads==1) { ntv_deinterlace_slice(s,0,1); return 0; }
    SDL_LockMutex(s->mutex); s->remaining=s->threads-1; ++s->generation;
    SDL_CondBroadcast(s->work); SDL_UnlockMutex(s->mutex);
    ntv_deinterlace_slice(s,0,s->threads);
    SDL_LockMutex(s->mutex);
    while(s->remaining) SDL_CondWait(s->done,s->mutex);
    SDL_UnlockMutex(s->mutex); return 0;
}

/* Normalise only when decoder buffers have differing row strides. */
static int ntv_deinterlace_normalise(AVFrame **value)
{
    AVFrame *in=*value, *out=av_frame_alloc(); if(!out) return AVERROR(ENOMEM);
    out->format=in->format; out->width=in->width; out->height=in->height;
    int ret=av_frame_get_buffer(out,32);
    if(ret>=0) ret=av_frame_copy(out,in);
    if(ret>=0) ret=av_frame_copy_props(out,in);
    if(ret<0) { av_frame_free(&out); return ret; }
    av_frame_free(value); *value=out; return 0;
}

/* Resolve cadence and discontinuity together so pixel history and output
 * timestamps always use the same decision. */
static double ntv_deinterlace_timing(NtvDeinterlace *s, AVRational tb, AVRational rate,
        int *gap)
{
    double seconds=av_q2d(tb);
    double duration=rate.num>0 && rate.den>0 ? av_q2d(av_inv_q(rate)) : .04;
    int known=0;
    /* A field-coded packet may describe half a decoded picture. Prefer the
     * preceding PTS pair, then confirm the initial cadence with the next PTS. */
    double packet=s->cur->pkt_duration*seconds;
    if(packet>0 && packet<1) duration=packet;
    if(s->prev->pts!=AV_NOPTS_VALUE && s->cur->pts!=AV_NOPTS_VALUE) {
        double step=((double)s->cur->pts-(double)s->prev->pts)*seconds;
        if(step>0 && step<1) { known=1; duration=step; }
    }
    *gap=0;
    if(s->cur->pts!=AV_NOPTS_VALUE && s->next->pts!=AV_NOPTS_VALUE) {
        double step=((double)s->next->pts-(double)s->cur->pts)*seconds;
        /* SPS frame_rate may count fields until the first PTS pair arrives. */
        *gap=step<=0 || step>duration*(known?1.5:2.5);
        if(step>0 && step<1 && !*gap) duration=step;
    }
    return duration;
}

static int ntv_deinterlace_emit(FFPlayer *ffp, NtvDeinterlace *s, AVRational tb,
        double duration, int last)
{
    VideoState *is=ffp->is;
    double pts=s->cur->pts==AV_NOPTS_VALUE ? NAN : s->cur->pts*av_q2d(tb);
    __atomic_store_n(&ffp->ntv_video_description,
            ntv_describe_video(is->video_st,is->viddec.avctx,s->cur)|NTV_DESCRIPTION_FRAME
            |(s->cur->interlaced_frame ? 1U<<30 : 0),__ATOMIC_RELAXED);
    if(!s->cur->interlaced_frame) {
        return queue_picture(ffp,s->cur,pts,duration,s->cur->pkt_pos,s->serial);
    }
    if(!ntv_deinterlace_supported(s->cur)) return AVERROR(ENOSYS);
    for(int p=0;p<3;++p)
        if(s->cur->linesize[p]!=s->prev->linesize[p] || s->cur->linesize[p]!=s->next->linesize[p]) {
            int ret=ntv_deinterlace_normalise(&s->prev);
            if(ret>=0) ret=ntv_deinterlace_normalise(&s->cur);
            if(ret>=0) ret=ntv_deinterlace_normalise(&s->next);
            if(ret<0) return ret;
            break;
        }
    for(int field=0;field<2;++field) {
        int ret=ntv_deinterlace_render(s,field,last);
        if(ret<0) return ret;
        ret=queue_picture(ffp,s->out,pts+field*duration*0.5,duration*0.5,s->cur->pkt_pos,s->serial);
        if(ret<0) return ret;
    }
    return 0;
}

static int ntv_deinterlace_push(FFPlayer *ffp, NtvDeinterlace *s, AVFrame *frame,
        AVRational tb, AVRational rate)
{
    int serial=ffp->is->viddec.pkt_serial;
    if(s->cur && (s->serial!=serial || s->cur->width!=frame->width
            || s->cur->height!=frame->height || s->cur->format!=frame->format))
        ntv_deinterlace_clear(s);
    s->serial=serial;
    if(!s->cur && !frame->interlaced_frame) return 0;
    if(!s->cur) {
        s->first_field=1; s->cur=av_frame_clone(frame); s->prev=av_frame_clone(frame);
        if(!s->cur || !s->prev) return AVERROR(ENOMEM);
        av_frame_unref(frame); return 1;
    }
    s->next=av_frame_clone(frame); if(!s->next) return AVERROR(ENOMEM);
    int gap;
    double duration=ntv_deinterlace_timing(s,tb,rate,&gap);
    /* Never interpolate temporal pixels across a discontinuity. Drain the
     * delayed picture spatially, then start fresh at the new source timestamp. */
    if(gap) s->first_field=1;
    int ret=ntv_deinterlace_emit(ffp,s,tb,duration,gap); if(ret<0) return ret;
    av_frame_free(&s->prev);
    if(gap) av_frame_free(&s->cur);
    s->prev=s->cur; s->cur=s->next; s->next=NULL;
    if(gap) {
        s->prev=av_frame_clone(s->cur);if(!s->prev)return AVERROR(ENOMEM);
        s->first_field=1;
    }
    av_frame_unref(frame); return 1;
}

static int ntv_deinterlace_flush(FFPlayer *ffp, NtvDeinterlace *s, AVRational tb, AVRational rate)
{
    if(!s->cur) return 0;
    if(s->serial!=ffp->is->viddec.pkt_serial) { ntv_deinterlace_clear(s); return 0; }
    s->next=av_frame_clone(s->cur); if(!s->next) return AVERROR(ENOMEM);
    if(s->prev->pts!=AV_NOPTS_VALUE && s->cur->pts!=AV_NOPTS_VALUE)
        s->next->pts=s->cur->pts+(s->cur->pts-s->prev->pts);
    int gap;
    double duration=ntv_deinterlace_timing(s,tb,rate,&gap);
    int ret=ntv_deinterlace_emit(ffp,s,tb,duration,1);
    ntv_deinterlace_clear(s); return ret;
}
#endif
