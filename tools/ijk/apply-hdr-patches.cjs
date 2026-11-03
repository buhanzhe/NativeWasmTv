// LGPL-2.1-or-later source overlays for IJK 0.8.8, applied after the playback overlays.
const fs = require('fs');
const path = require('path');
module.exports = function applyHdr(root) {
    function edit(file, before, after, count = 1) {
        const target = path.join(root,file);
        const text = fs.readFileSync(target,'utf8').replace(/\r\n/g,'\n');
        if (text.includes(after)) return;
        if (text.split(before).length - 1 !== count) throw new Error('Unexpected HDR upstream: '+file+' / '+before.slice(0,70));
        fs.writeFileSync(target,text.split(before).join(after));
    }
    function copy(name,target) { fs.copyFileSync(path.join(__dirname,name),path.join(root,target)); }
    // Upgrade an already-patched checkout to the new default without duplicating options.
    for (const [file,before,after] of [
        ['ijkmedia/ijkplayer/ff_ffplay_def.h','    ffp->ntv_hdr_mode = 0;','    ffp->ntv_hdr_mode = 1;'],
        ['ijkmedia/ijkplayer/ff_ffplay_options.h',
            'OPTION_OFFSET(ntv_hdr_mode), OPTION_INT(0, 0, 2)',
            'OPTION_OFFSET(ntv_hdr_mode), OPTION_INT(1, 0, 3)'],
        ['ijkmedia/ijkplayer/ff_ffplay_options.h',
            'OPTION_OFFSET(ntv_hdr_mode), OPTION_INT(1, 0, 2)',
            'OPTION_OFFSET(ntv_hdr_mode), OPTION_INT(1, 0, 3)'],
        ['ijkmedia/ijkplayer/ff_ffplay_options.h',
            'colour output: mapping/hardware/BT.709',
            'colour output: mapping/hardware/BT.709/hardware SDR'],
        ['ijkmedia/ijkplayer/ff_ffplay_def.h',
            '0: mapping, 1: hardware HDR, 2: BT.709 override',
            '0: mapping, 1: hardware HDR, 2: BT.709 override, 3: hardware SDR']
    ]) {
        const target = path.join(root,file);
        const source = fs.readFileSync(target,'utf8');
        if (!source.includes(after) && source.includes(before))
            fs.writeFileSync(target,source.replace(before,after));
    }
    copy('ntv_hdr_codec.h','ijkmedia/ijkplayer/ntv_hdr_codec.h');
    copy('ntv_hdr_jni.h','ijkmedia/ijksdl/android/ntv_hdr_jni.h');
    copy('ntv_hdr_render.h','ijkmedia/ijksdl/gles2/ntv_hdr_render.h');
    copy('ntv_hdr_lut.h','ijkmedia/ijksdl/gles2/ntv_hdr_lut.h');
    edit('ijkmedia/ijkplayer/ff_ffplay_def.h','    int mediacodec_hevc;',
        '    int ntv_hdr_mode; /* 0: mapping, 1: hardware HDR, 2: BT.709 override, 3: hardware SDR */\n    int mediacodec_hevc;');
    edit('ijkmedia/ijkplayer/ff_ffplay_def.h','    ffp->mediacodec_hevc                = 0;',
        '    ffp->ntv_hdr_mode = 1;\n    ffp->mediacodec_hevc                = 0;');
    edit('ijkmedia/ijkplayer/ff_ffplay_options.h','    { "mediacodec-avc",',
        '    { "ntv-hdr-mode", "colour output: mapping/hardware/BT.709/hardware SDR",\n        OPTION_OFFSET(ntv_hdr_mode), OPTION_INT(1, 0, 3) },\n    { "mediacodec-avc",');
    const glsl = fs.readFileSync(path.join(__dirname,'../../app/src/main/res/raw/hdr_tonemap.glsl'),'utf8');
    fs.writeFileSync(path.join(root,'ijkmedia/ijksdl/gles2/ntv_hdr_glsl.h'),
        '/* Generated from app/src/main/res/raw/hdr_tonemap.glsl; LGPL-2.1-or-later. */\n#define NTV_HDR_GLSL '+JSON.stringify(glsl)+'\n');
    const codec = 'ijkmedia/ijkplayer/android/pipeline/ffpipenode_android_mediacodec_vdec.c';
    const codecTarget = path.join(root,codec);
    const oldCodec = fs.readFileSync(codecTarget,'utf8');
    const migratedCodec = oldCodec.replace(
        'ntv_hdr_codec_format(opaque->input_aformat,opaque->codecpar,ffp->is->video_st,opaque->avctx,ffp->ntv_hdr_mode);',
        'ntv_hdr_codec_format(opaque->input_aformat,opaque->codecpar,ffp->is->video_st,opaque->avctx,ffp->ntv_hdr_mode,ffp->ntv_source_preferred_transfer);');
    if (oldCodec !== migratedCodec) fs.writeFileSync(codecTarget,migratedCodec);
    edit(codec,'#include "../../ntv_dolby_vision.h"','#include "../../ntv_hdr_codec.h"\n#include "../../ntv_dolby_vision.h"');
    edit(codec,'    if (opaque->codecpar->extradata && opaque->codecpar->extradata_size > 0) {',
        `    if (strcmp(opaque->mcc.mime_type,"video/dolby-vision"))
        ntv_hdr_codec_format(opaque->input_aformat,opaque->codecpar,ffp->is->video_st,opaque->avctx,ffp->ntv_hdr_mode,ffp->ntv_source_preferred_transfer);
    if (opaque->codecpar->extradata && opaque->codecpar->extradata_size > 0) {`);
    const jni = 'ijkmedia/ijkplayer/android/ijkplayer_jni.c';
    edit(jni,'extern void ntv_codec_jni_init(JNIEnv *env);','extern void ntv_codec_jni_init(JNIEnv *env);\nextern void ntv_hdr_jni_init(JNIEnv *env);');
    edit(jni,'    ntv_codec_jni_init(env);','    ntv_codec_jni_init(env);\n    ntv_hdr_jni_init(env);');
    const amc = 'ijkmedia/ijksdl/android/ijksdl_codec_android_mediacodec_java.c';
    edit(amc,'static SDL_Class g_amediacodec_class', '#include "ntv_hdr_jni.h"\n\nstatic SDL_Class g_amediacodec_class');
    edit(amc,'    jobject android_media_codec;','    jobject ntv_hdr_bridge;\n    jobject android_media_codec;');
    edit(amc,'    SDL_AMediaFormat *aformat = SDL_AMediaFormatJava_init(env, local_android_format);',
        `    if (opaque->ntv_hdr_bridge) {
        (*env)->CallVoidMethod(env,opaque->ntv_hdr_bridge,ntv_hdr_output,local_android_format);
        J4A_ExceptionCheck__catchAll(env);
    }
    SDL_AMediaFormat *aformat = SDL_AMediaFormatJava_init(env, local_android_format);`);
    edit(amc,'    SDL_JNI_DeleteLocalRefP(env, &local_android_format);',`    int32_t ntv_standard = -1, ntv_transfer = -1;
    SDL_AMediaFormat_getInt32(aformat,"color-standard",&ntv_standard);
    SDL_AMediaFormat_getInt32(aformat,"color-transfer",&ntv_transfer);
    ALOGI("nTv codec output colour: standard=%d transfer=%d",ntv_standard,ntv_transfer);
    SDL_JNI_DeleteLocalRefP(env, &local_android_format);`);
    edit(amc,'        SDL_JNI_DeleteGlobalRefP(env, &opaque->output_buffer_info);',
        '        ntv_hdr_close(env,&opaque->ntv_hdr_bridge);\n        SDL_JNI_DeleteGlobalRefP(env, &opaque->output_buffer_info);');
    // Migrate the old create/configure/verify split as one unit. Java owns the bounded
    // codec-SDR -> GPU retry, and returns the final bridge to the native codec owner.
    const configureTarget=path.join(root,amc);
    const configured=fs.readFileSync(configureTarget,'utf8').replace(/\r\n/g,'\n');
    const configureBody=`    ntv_hdr_close(env,&opaque->ntv_hdr_bridge);
    if (ntv_hdr_class && ntv_hdr_configure) {
        jobject bridge = (*env)->CallStaticObjectMethod(env,ntv_hdr_class,ntv_hdr_configure,
                android_media_codec,android_media_format,android_surface,crypto,(jint)flags);
        if (J4A_ExceptionCheck__catchAll(env)) return SDL_AMEDIA_ERROR_UNKNOWN;
        if (bridge) {
            opaque->ntv_hdr_bridge = (*env)->NewGlobalRef(env,bridge);
            (*env)->DeleteLocalRef(env,bridge);
        }
    } else {
        J4AC_MediaCodec__configure(env,android_media_codec,android_media_format,android_surface,crypto,flags);
        if (J4A_ExceptionCheck__catchAll(env)) return SDL_AMEDIA_ERROR_UNKNOWN;
    }
`;
    if(!configured.includes(configureBody)){
        let begin=configured.indexOf('    jobject ntv_surface = NULL;');
        if(begin<0)begin=configured.indexOf('    J4AC_MediaCodec__configure(env, android_media_codec, android_media_format, android_surface, crypto, flags);');
        const end=configured.indexOf('    opaque->is_input_buffer_valid = true;',begin);
        if(begin<0||end<begin)throw new Error('Unexpected HDR codec configure block');
        fs.writeFileSync(configureTarget,configured.slice(0,begin)+configureBody+configured.slice(end));
    }
    const overlay = 'ijkmedia/ijksdl/ijksdl_vout.h';
    edit(overlay,'    int is_private;', '    int ntv_colorspace, ntv_transfer, ntv_full_range;\n    float ntv_peak;\n    int is_private;');
    const fill = 'ijkmedia/ijksdl/ffmpeg/ijksdl_vout_overlay_ffmpeg.c';
    edit(fill,'static int func_fill_frame(SDL_VoutOverlay *overlay, const AVFrame *frame)',
        '#include "libavutil/mastering_display_metadata.h"\n\nstatic int func_fill_frame(SDL_VoutOverlay *overlay, const AVFrame *frame)');
    // Migrate the old field block before matching the complete insertion. Also
    // repair checkouts touched by the earlier split migration without duplicating
    // the declarations on a second application.
    const fillTarget = path.join(root,fill);
    const oldFill = fs.readFileSync(fillTarget,'utf8').replace(/\r\n/g,'\n');
    let blocks = 0;
    const migratedFill = oldFill.replace(
        /    overlay->ntv_colorspace = [\s\S]*?    overlay->ntv_peak = FFMAX\(100\.f,FFMIN\(10000\.f,overlay->ntv_peak\)\);/g,
        block => ++blocks === 1 ? block : '').replace('    overlay->ntv_colorspace = frame->colorspace;',
        '    overlay->ntv_colorspace = frame->colorspace == AVCOL_SPC_UNSPECIFIED\n            && frame->color_primaries == AVCOL_PRI_BT2020 ? AVCOL_SPC_BT2020_NCL : frame->colorspace;');
    if (oldFill !== migratedFill) fs.writeFileSync(fillTarget,migratedFill);
    edit(fill,'    AVFrame swscale_dst_pic = { { 0 } };',`    AVFrame swscale_dst_pic = { { 0 } };
    overlay->ntv_colorspace = frame->colorspace == AVCOL_SPC_UNSPECIFIED
            && frame->color_primaries == AVCOL_PRI_BT2020 ? AVCOL_SPC_BT2020_NCL : frame->colorspace;
    overlay->ntv_transfer = frame->color_trc;
    overlay->ntv_full_range = frame->color_range == AVCOL_RANGE_JPEG;
    overlay->ntv_peak = 1000.f;
    AVFrameSideData *mastering = av_frame_get_side_data(frame,AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    AVFrameSideData *light = av_frame_get_side_data(frame,AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    if (mastering && mastering->size >= sizeof(AVMasteringDisplayMetadata)) {
        AVMasteringDisplayMetadata *m = (void *)mastering->data;
        if (m->has_luminance && m->max_luminance.den && av_q2d(m->max_luminance) > 0)
            overlay->ntv_peak = av_q2d(m->max_luminance);
    }
    if (light && light->size >= sizeof(AVContentLightMetadata)) {
        AVContentLightMetadata *m = (void *)light->data;
        if (m->MaxCLL > 0) overlay->ntv_peak = m->MaxCLL;
    }
    overlay->ntv_peak = FFMAX(100.f,FFMIN(10000.f,overlay->ntv_peak));`);
    const player = 'ijkmedia/ijkplayer/ff_ffplay.c';
    const oldFrameCondition = '    if (src_frame->color_trc == AVCOL_TRC_SMPTE2084 || src_frame->color_trc == AVCOL_TRC_ARIB_STD_B67) {';
    const gamutFrameCondition = '    if (src_frame->color_trc == AVCOL_TRC_SMPTE2084 || src_frame->color_trc == AVCOL_TRC_ARIB_STD_B67\n            || src_frame->colorspace == AVCOL_SPC_BT2020_NCL) {';
    const playerSource = fs.readFileSync(path.join(root,player),'utf8').replace(/\r\n/g,'\n');
    if (playerSource.includes(oldFrameCondition))
        fs.writeFileSync(path.join(root,player),playerSource.replace(oldFrameCondition,gamutFrameCondition));
    // Software decoding cannot emit native HDR Surface buffers. In hardware mode
    // its fallback retains tone mapping; BT.709 explicitly bypasses it in both paths.
    edit(player,'        /* update the bitmap content */',`        if (ffp->ntv_hdr_mode == 2 && vp->bmp->format != SDL_FCC__AMC) {
            vp->bmp->ntv_colorspace = AVCOL_SPC_BT709;
            vp->bmp->ntv_transfer = AVCOL_TRC_BT709;
        }
        /* update the bitmap content */`);
    edit(player,'    /* alloc or resize hardware picture buffer */',`    /* HDR software frames need YUV + GPU tone mapping, not swscale RGB. */
    if (src_frame->color_trc == AVCOL_TRC_SMPTE2084 || src_frame->color_trc == AVCOL_TRC_ARIB_STD_B67
            || src_frame->colorspace == AVCOL_SPC_BT2020_NCL) {
        ffp->overlay_format = SDL_FCC__GLES2;
        SDL_VoutSetOverlayFormat(ffp->vout,SDL_FCC__GLES2);
    }
    /* alloc or resize hardware picture buffer */`);
    edit(player, '    /* HDR software frames need YUV + GPU tone mapping, not swscale RGB. */',
        '    ntv_restore_frame_transfer(is->video_st,src_frame);\n    /* HDR software frames need YUV + GPU tone mapping, not swscale RGB. */');
    const internal = 'ijkmedia/ijksdl/gles2/internal.h';
    const window = 'ijkmedia/ijksdl/android/ijksdl_vout_android_nativewindow.c';
    edit(window,'    IJK_EGL         *egl;', '    int ntv_hdr_checked;\n    IJK_EGL         *egl;');
    edit(window,'    switch(overlay->format) {',`    if (overlay->format != SDL_FCC__AMC && !opaque->ntv_hdr_checked) {
        extern int ntv_hdr_prepare_software(void);
        if (ntv_hdr_prepare_software() < 0) return -1;
        opaque->ntv_hdr_checked = 1;
    }
    switch(overlay->format) {`);
    edit(internal,'    GLuint um3_color_conversion;', '    GLint ntv_transfer, ntv_peak, ntv_yuv_offset;\n    GLuint um3_color_conversion;');
    for (const type of ['yuv420p','yuv444p10le']) {
        const file = 'ijkmedia/ijksdl/gles2/renderer_'+type+'.c';
        edit(file,'#include "internal.h"','#include "internal.h"\n#include "ntv_hdr_render.h"');
        edit(file,'    for (int i = 0; i < 3; ++i) {\n        int plane = planes[i];',
            '    ntv_hdr_render_uniforms(renderer,overlay);\n    for (int i = 0; i < 3; ++i) {\n        int plane = planes[i];');
        edit(file,'    renderer->func_use            =',`    renderer->ntv_transfer = glGetUniformLocation(renderer->program,"ntvTransfer");
    renderer->ntv_peak = glGetUniformLocation(renderer->program,"ntvPeak");
    renderer->ntv_yuv_offset = glGetUniformLocation(renderer->program,"ntvYuvOffset");
    renderer->func_use            =`);
        const shader = 'ijkmedia/ijksdl/gles2/fsh/'+type+'.fsh.c';
        edit(shader,'static const char g_shader[] = IJK_GLES_STRING(',
            '#include "ijksdl/gles2/ntv_hdr_glsl.h"\n\nstatic const char g_shader[] = "precision highp float;\\n" NTV_HDR_GLSL IJK_GLES_STRING(\n    uniform vec3 ntvYuvOffset;');
        edit(shader,'        lowp    vec3 rgb;','        highp   vec3 rgb;');
        edit(shader,'        gl_FragColor = vec4(rgb, 1);','        gl_FragColor = vec4(ntvToSdr(rgb), 1);');
        if (type === 'yuv420p') {
            edit(shader,'(16.0 / 255.0)','ntvYuvOffset.x');
            edit(shader,'- 0.5);','- ntvYuvOffset.y);',2);
        } else {
            edit(shader,'vec3(16.0 / 255.0, 0.5, 0.5)','ntvYuvOffset');
        }
    }
};
