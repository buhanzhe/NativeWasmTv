// Software-only BWDIF; do not pretend there is an Android deinterlace switch.
const fs=require('fs'), path=require('path');
module.exports=function(root) {
    function edit(file,before,after) {
        const target=path.join(root,file),s=fs.readFileSync(target,'utf8').replace(/\r\n/g,'\n');
        if(s.includes(after))return;
        if(s.split(before).length!==2)throw new Error('Unexpected deinterlace upstream: '+file+' / '+before);
        fs.writeFileSync(target,s.replace(before,after));
    }
    for(const file of ['ntv_deinterlace.h','ntv_bwdif_kernel.h'])
        fs.copyFileSync(path.join(__dirname,file),path.join(root,'ijkmedia/ijkplayer',file));
    const play='ijkmedia/ijkplayer/ff_ffplay.c';
    // BWDIF needs consecutive source frames. Dropping a decoded interlaced
    // frame here removes both fields and makes its temporal neighbours 80 ms
    // apart at 1080i50. Keep references intact; video_refresh can still drop
    // late progressive output after filtering to maintain audio sync.
    edit(play,
        '        if (ffp->framedrop>0 || (ffp->framedrop && get_master_sync_type(is) != AV_SYNC_VIDEO_MASTER)) {',
        '        if (!frame->interlaced_frame &&\n            (ffp->framedrop>0 || (ffp->framedrop && get_master_sync_type(is) != AV_SYNC_VIDEO_MASTER))) {');
    // Wake when a filtered field is queued, instead of polling an empty queue
    // every 10 ms. Do not turn on the unrelated hardware frame-sync policies.
    edit(play,
        '            } else if (ffp->ntv_frame_sync && !is->paused) {',
        '            } else if ((ffp->ntv_frame_sync ||\n                    (__atomic_load_n(&ffp->ntv_video_description, __ATOMIC_RELAXED) & (1U << 30)))\n                    && !is->paused) {');
    edit(play,'    if (ffp->stat.vdec_type == FFP_PROPV_DECODER_AVCODEC && is->video_st)\n        __atomic_store_n(&ffp->ntv_video_description, ntv_describe_video(is->video_st, is->viddec.avctx, src_frame) | NTV_DESCRIPTION_FRAME, __ATOMIC_RELAXED);',
        '    /* Software source metadata is published before deinterlacing. */');
    edit(play,'static int ffplay_video_thread(void *arg)',
        '#include "ntv_deinterlace.h"\n\nstatic int ffplay_video_thread(void *arg)');
    edit(play,'    int convert_frame_count = 0;',
        '    int convert_frame_count = 0;\n    NtvDeinterlace ntv_deint = {0};');
    edit(play,'        ret = get_video_frame(ffp, frame);\n        if (ret < 0)\n            goto the_end;\n        if (!ret)\n            continue;',
        `        ret = get_video_frame(ffp, frame);
        if (ret < 0) goto the_end;
        if (!ret) {
            if (is->viddec.finished == is->viddec.pkt_serial) {
                ret=ntv_deinterlace_flush(ffp,&ntv_deint,tb,frame_rate);
                if(ret<0) goto the_end;
            }
            continue;
        }
        ntv_restore_frame_transfer(is->video_st,frame);
        __atomic_store_n(&ffp->ntv_video_description,
                ntv_describe_video(is->video_st,is->viddec.avctx,frame)|NTV_DESCRIPTION_FRAME
                |(frame->interlaced_frame ? __atomic_load_n(&ffp->ntv_video_description,__ATOMIC_RELAXED)&(1U<<30) : 0),__ATOMIC_RELAXED);
        if (!ffp->get_frame_mode) {
            int mapped=ntv_deinterlace_push(ffp,&ntv_deint,frame,tb,frame_rate);
            if(mapped<0) { ret=mapped; goto the_end; }
            if(mapped) continue;

        }`);
    edit(play,'    av_log(NULL, AV_LOG_INFO, "convert image convert_frame_count = %d\\n", convert_frame_count);',
        '    ntv_deinterlace_destroy(&ntv_deint);\n    if(ret<0 && !is->abort_request) ffp_notify_msg3(ffp,FFP_MSG_ERROR,0,ret);\n    av_log(NULL, AV_LOG_INFO, "convert image convert_frame_count = %d\\n", convert_frame_count);');
    edit(play,'        case 22010: /* source colour/depth, before any output conversion */',
        '        case 22012: /* software BWDIF active; hardware belongs to the vendor */\n            return ffp ? (__atomic_load_n(&ffp->ntv_video_description,__ATOMIC_RELAXED) >> 30) & 1 : 0;\n        case 22010: /* source colour/depth, before any output conversion */');
};
