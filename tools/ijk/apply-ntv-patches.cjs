// Reproducible source overlays for the pinned IJK 0.8.8 / FFmpeg 3.4 tree.
// Exact matches fail closed on a different upstream revision; reruns are safe.
const fs = require('fs');
const path = require('path');
const root = path.resolve(process.argv[2] || '.codex-tmp/ijkplayer-0.8.8');
function edit(file, before, after, count = 1) {
    const target = path.join(root, file);
    let text = fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n');
    if (text.includes(after)) return;
    if (text.split(before).length - 1 !== count) throw new Error('Unexpected upstream: ' + file + ' / ' + before.slice(0, 65));
    fs.writeFileSync(target, text.split(before).join(after));
}
function overlay(source, target) {
    fs.copyFileSync(path.join(__dirname, source), path.join(root, target));
}
function migrate(file, before, after) {
    const target = path.join(root, file);
    let text = fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n');
    if (!text.includes(after) && text.includes(before)) {
        fs.writeFileSync(target, text.replace(before, after));
    }
}
overlay('module-ntv.sh', 'config/module.sh');
overlay('ntv_dolby_audio.inc', 'ijkmedia/ijkplayer/ntv_dolby_audio.inc');
overlay('ntv_dolby_vision.h', 'ijkmedia/ijkplayer/ntv_dolby_vision.h');
overlay('ntv_subtitle_text.h', 'ijkmedia/ijkplayer/ntv_subtitle_text.h');
const def = 'ijkmedia/ijkplayer/ff_ffplay_def.h';
edit(def, '    struct AudioParams audio_tgt;', '    void *ntv_audio_output;\n    struct AudioParams audio_tgt;');
edit(def, '    int mediacodec_all_videos;', '    int ntv_passthrough;\n    volatile int ntv_pcm_volume;\n    int mediacodec_all_videos;');
edit(def, '    int ntv_passthrough;', '    int ntv_initial_audio, ntv_initial_video, ntv_initial_subtitle;\n    int ntv_passthrough;');
edit(def, '    ffp->mediacodec_all_videos          = 0;', '    ffp->ntv_passthrough = 0;\n    ffp->ntv_pcm_volume = 0;\n    ffp->mediacodec_all_videos          = 0;');
edit(def, '    ffp->ntv_passthrough = 0;', '    ffp->ntv_initial_audio = ffp->ntv_initial_video = ffp->ntv_initial_subtitle = -2;\n    ffp->ntv_passthrough = 0;');
edit('ijkmedia/ijkplayer/ff_ffplay_options.h', '    { "mediacodec",', '    { "ntv-audio-passthrough", "nTv: capability-gated Dolby output",\n        OPTION_OFFSET(ntv_passthrough), OPTION_INT(0, 0, 1) },\n    { "mediacodec",');
edit('ijkmedia/ijkplayer/ff_ffplay_options.h', '    { "ntv-audio-passthrough",', `    { "ntv-initial-audio", "initial audio stream", OPTION_OFFSET(ntv_initial_audio), OPTION_INT(-2, -2, INT_MAX) },
    { "ntv-initial-video", "initial video stream", OPTION_OFFSET(ntv_initial_video), OPTION_INT(-2, -2, INT_MAX) },
    { "ntv-initial-subtitle", "initial subtitle stream", OPTION_OFFSET(ntv_initial_subtitle), OPTION_INT(-2, -2, INT_MAX) },
    { "ntv-audio-passthrough",`);
const player = 'ijkmedia/ijkplayer/ff_ffplay.c';
edit(def, '    int max_fps;', '    int ntv_trace_latency;\n    int max_fps;');
edit('ijkmedia/ijkplayer/ff_ffplay_options.h', '    { "start-on-prepared",', `    { "ntv-trace-latency", "diagnostic packet/decode/display timings (off by default)",
        OPTION_OFFSET(ntv_trace_latency), OPTION_INT(0, 0, 1) },
    { "start-on-prepared",`);
edit(player, '            packet_queue_put(&is->videoq, pkt);', `            if (ffp->ntv_trace_latency) av_log(ffp, AV_LOG_INFO, "NTV_LAT READ pts=%.6f us=%lld\\n", pkt->pts * av_q2d(is->video_st->time_base), (long long)av_gettime_relative());
            packet_queue_put(&is->videoq, pkt);`);
edit(player, '        SDL_VoutDisplayYUVOverlay(ffp->vout, vp->bmp);', `        SDL_VoutDisplayYUVOverlay(ffp->vout, vp->bmp);
        ffp->ntv_last_video_output_ms = (uint32_t)(av_gettime_relative() / 1000);`);
edit(player, `static int queue_picture(FFPlayer *ffp, AVFrame *src_frame, double pts, double duration, int64_t pos, int serial)
{`, `static int queue_picture(FFPlayer *ffp, AVFrame *src_frame, double pts, double duration, int64_t pos, int serial)
{
    if (ffp->ntv_trace_latency) av_log(ffp, AV_LOG_INFO, "NTV_LAT DECODE pts=%.6f us=%lld\\n", pts, (long long)av_gettime_relative());`);
// Opt-in only for nTv's interactive, video-only RTSP stream. Do not change
// regular live TV, VOD, pause/step, or streams requiring audio synchronisation.
edit(def, '    int pictq_size;', '    int ntv_live_video;\n    int pictq_size;');
edit(def, '    ffp->packet_buffering               = 1;', '    ffp->ntv_live_video = 0;\n    ffp->packet_buffering               = 1;');
edit(def, '    int ntv_live_video;', '    volatile uint32_t ntv_last_video_output_ms;\n    int ntv_live_video;');
edit(def, '    ffp->ntv_live_video = 0;', '    ffp->ntv_last_video_output_ms = 0;\n    ffp->ntv_live_video = 0;');
edit(player, '        ffp->ntv_last_video_output_ms = (uint32_t)(av_gettime_relative() / 1000);', `        ffp->ntv_last_video_output_ms = (uint32_t)(av_gettime_relative() / 1000);
        if (ffp->ntv_trace_latency) av_log(ffp, AV_LOG_INFO, "NTV_LAT DISPLAY pts=%.6f us=%lld\\n", vp->pts, (long long)av_gettime_relative());`);
// Upstream vfps is only updated by new frames, so a dead codec can report the
// last healthy FPS forever. Let the existing Java cast watchdog see a stall.
edit(player, `        case FFP_PROP_FLOAT_VIDEO_OUTPUT_FRAMES_PER_SECOND:
            return ffp ? ffp->stat.vfps : default_value;`, `        case FFP_PROP_FLOAT_VIDEO_OUTPUT_FRAMES_PER_SECOND:
            if (ffp && ffp->ntv_live_video && ffp->is && !ffp->is->paused
                    && (uint32_t)((uint32_t)(av_gettime_relative() / 1000)
                            - ffp->ntv_last_video_output_ms) > 1000)
                return 0;
            return ffp ? ffp->stat.vfps : default_value;`);
edit('ijkmedia/ijkplayer/ff_ffplay_options.h', '    { "video-pictq-size",', `    { "ntv-live-video", "present newest decoded video for interactive video-only RTSP",
        OPTION_OFFSET(ntv_live_video), OPTION_INT(0, 0, 1) },
    { "video-pictq-size",`);
const oldInteractiveVideo = `            /* Decoded pictures are safe to skip: never discard compressed P frames.
             * A LAN burst must not leave the remote cursor permanently behind. */
            int ntv_immediate = ffp->ntv_live_video && is->realtime && !is->audio_st && !is->step;
            if (ntv_immediate && frame_queue_nb_remaining(&is->pictq) > 1) {
                frame_queue_next(&is->pictq);
                goto retry;
            }

            /* compute nominal last_duration */`;
const lowLatencyAvVideo = `            /* Decoded pictures are safe to skip: never discard compressed P frames.
             * Video-only cast presents the newest decoded picture immediately. With
             * audio, retain A/V sync but discard pictures that are still stale after
             * advancing once, so a startup/network burst cannot become permanent
             * cursor latency. */
            int ntv_interactive = ffp->ntv_live_video && is->realtime && !is->step;
            int ntv_immediate = ntv_interactive && !is->audio_st;
            if (ntv_interactive && frame_queue_nb_remaining(&is->pictq) > 1) {
                Frame *ntv_next = frame_queue_peek_next(&is->pictq);
                double ntv_master = get_master_clock(is);
                if (ntv_immediate || (!isnan(ntv_master) && !isnan(ntv_next->pts)
                        && ntv_next->pts < ntv_master - 0.080)) {
                    frame_queue_next(&is->pictq);
                    goto retry;
                }
            }

            /* compute nominal last_duration */`;
migrate(player, oldInteractiveVideo, lowLatencyAvVideo);
edit(player, '            /* compute nominal last_duration */', lowLatencyAvVideo);
edit(player, '            delay = compute_target_delay(ffp, last_duration, is);', `            delay = ntv_immediate ? 0 : compute_target_delay(ffp, last_duration, is);
            if (ntv_immediate) is->frame_timer = av_gettime_relative() / 1000000.0;`);
// Nine decoded AAC frames add about 192 ms at 48 kHz before AudioTrack. The
// interactive RTSP sender already has a tiny loss-bounded queue, so retain only
// three receiver frames (~64 ms) while leaving normal live/VOD playback unchanged.
edit(player, '    if (frame_queue_init(&is->sampq, &is->audioq, SAMPLE_QUEUE_SIZE, 1) < 0)',
    '    if (frame_queue_init(&is->sampq, &is->audioq, ffp->ntv_live_video ? 3 : SAMPLE_QUEUE_SIZE, 1) < 0)');
// IJK doubles AudioTrack.getMinBufferSize() for 2x playback and always runs its
// output worker at high priority. Realtime casting never changes speed: use the
// platform-safe minimum buffer and normal receiver priority so video/UI can run
// on dual-core televisions. Regular playback keeps the upstream behavior.
const aoutHeader = 'ijkmedia/ijksdl/ijksdl_aout.h';
edit(aoutHeader, '    double     minimal_latency_seconds;',
    '    double     minimal_latency_seconds;\n    int        ntv_low_latency;');
const trackHeader = 'ijkmedia/ijksdl/android/android_audiotrack.h';
edit(trackHeader, '    int buffer_size_in_bytes;',
    '    int buffer_size_in_bytes;\n    int low_latency;');
edit(trackHeader,
    'SDL_Android_AudioTrack *SDL_Android_AudioTrack_new_from_sdl_spec(JNIEnv *env, const SDL_AudioSpec *sdl_spec);',
    'SDL_Android_AudioTrack *SDL_Android_AudioTrack_new_from_sdl_spec(JNIEnv *env, const SDL_AudioSpec *sdl_spec, int low_latency);');
const audioTrack = 'ijkmedia/ijksdl/android/android_audiotrack.c';
edit(audioTrack, '    spec->buffer_size_in_bytes = 0;',
    '    spec->buffer_size_in_bytes = 0;\n    spec->low_latency = 0;');
edit(audioTrack, '    min_buffer_size *= AUDIOTRACK_PLAYBACK_MAXSPEED;',
    '    if (!spec->low_latency) min_buffer_size *= AUDIOTRACK_PLAYBACK_MAXSPEED;');
edit(audioTrack,
    'SDL_Android_AudioTrack *SDL_Android_AudioTrack_new_from_sdl_spec(JNIEnv *env, const SDL_AudioSpec *sdl_spec)',
    'SDL_Android_AudioTrack *SDL_Android_AudioTrack_new_from_sdl_spec(JNIEnv *env, const SDL_AudioSpec *sdl_spec, int low_latency)');
edit(audioTrack, '    atrack_spec.buffer_size_in_bytes = sdl_spec->size;',
    '    atrack_spec.buffer_size_in_bytes = sdl_spec->size;\n    atrack_spec.low_latency = low_latency;');
const androidAout = 'ijkmedia/ijksdl/android/ijksdl_aout_android_audiotrack.c';
edit(androidAout, '    SDL_SetThreadPriority(SDL_THREAD_PRIORITY_HIGH);',
    '    SDL_SetThreadPriority(aout->ntv_low_latency ? SDL_THREAD_PRIORITY_NORMAL : SDL_THREAD_PRIORITY_HIGH);');
edit(androidAout,
    '    opaque->atrack = SDL_Android_AudioTrack_new_from_sdl_spec(env, desired);',
    '    opaque->atrack = SDL_Android_AudioTrack_new_from_sdl_spec(env, desired, aout->ntv_low_latency);');
edit('ijkmedia/ijkplayer/android/pipeline/ffpipeline_android.c', '    if (aout)\n        SDL_AoutSetStereoVolume(aout, pipeline->opaque->left_volume, pipeline->opaque->right_volume);',
    '    if (aout) {\n        aout->ntv_low_latency = ffp->ntv_live_video;\n        SDL_AoutSetStereoVolume(aout, pipeline->opaque->left_volume, pipeline->opaque->right_volume);\n    }');
// The picture producer already signals this condition. Wake as soon as a
// decoded picture is ready instead of adding a fixed REFRESH_RATE sleep.
// Only the single consumer advances rindex; compressed references stay intact.
edit(player, `        if (remaining_time > 0.0)
            av_usleep((int)(int64_t)(remaining_time * 1000000.0));`, `        if (remaining_time > 0.0) {
            if (ffp->ntv_live_video && is->realtime && !is->audio_st && !is->paused
                    && !is->step && is->show_mode != SHOW_MODE_NONE) {
                SDL_LockMutex(is->pictq.mutex);
                while (!is->abort_request && !is->paused && !is->step) {
                    if (is->pictq.size <= is->pictq.rindex_shown && !is->force_refresh) {
                        SDL_CondWaitTimeout(is->pictq.cond, is->pictq.mutex, 100);
                        continue;
                    }
                    /* Do not stuff Surface/codec buffers with a network burst.
                     * Normal 60 fps frames wake immediately; sub-8 ms arrivals
                     * coalesce so video_refresh can discard decoded old frames. */
                    int64_t wait_us = (int64_t)(is->frame_timer * 1000000.0)
                            + 8000 - av_gettime_relative();
                    if (wait_us <= 0 || wait_us > 8000) break;
                    SDL_CondWaitTimeout(is->pictq.cond, is->pictq.mutex, (wait_us + 999) / 1000);
                }
                SDL_UnlockMutex(is->pictq.mutex);
            } else {
                av_usleep((int)(int64_t)(remaining_time * 1000000.0));
            }
        }`);
edit(player, '    /* open the streams */', `    /* Recovery opens the requested tracks before any decoder/clock is started. */
    int ntv_initial[] = { ffp->ntv_initial_video, ffp->ntv_initial_audio, ffp->ntv_initial_subtitle };
    enum AVMediaType ntv_types[] = { AVMEDIA_TYPE_VIDEO, AVMEDIA_TYPE_AUDIO, AVMEDIA_TYPE_SUBTITLE };
    for (int n = 0; n < 3; n++) {
        int index = ntv_initial[n];
        if (index >= 0 && index < ic->nb_streams && ic->streams[index]->codecpar->codec_type == ntv_types[n]
                && avcodec_find_decoder(ic->streams[index]->codecpar->codec_id)) st_index[ntv_types[n]] = index;
        else if (n == 2 && index == -1) st_index[AVMEDIA_TYPE_SUBTITLE] = -1;
    }

    /* open the streams */`);
// The old parser expects legacy Dialogue headers, dereferences missing commas
// and copies unbounded text. FFmpeg 3.4 text decoders emit the compact ASS form.
{
    const file = path.join(root, player);
    let source = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
    const begin = source.indexOf('static size_t parse_ass_subtitle(');
    if (begin >= 0) {
        const end = source.indexOf('static void video_image_display2(', begin);
        if (end < 0) throw Error('Unexpected subtitle parser');
        source = source.slice(0, begin) + '#include "ntv_subtitle_text.h"\n\n' + source.slice(end);
        fs.writeFileSync(file, source);
    } else if (!source.includes('#include "ntv_subtitle_text.h"')) throw Error('Missing subtitle parser');
}
edit(player, 'char buffered_text[4096];', 'char buffered_text[4096] = {0};');
edit(player, 'strncpy(buffered_text, sp->sub.rects[0]->text, 4096);', 'av_strlcpy(buffered_text, sp->sub.rects[0]->text, sizeof(buffered_text));');
edit(player, 'parse_ass_subtitle(sp->sub.rects[0]->ass, buffered_text);', 'ntv_subtitle_text(sp->sub.rects[0]->ass, buffered_text, sizeof(buffered_text));');
// Refuse an unsupported/repeated selection before closing the working stream.
// Java selectTrack is void: older IJK silently lost the active audio on failure.
edit(player, '    if (selected) {\n        switch (codecpar->codec_type) {', `    if (selected) {
        int previous = codecpar->codec_type == AVMEDIA_TYPE_AUDIO ? is->audio_stream
                : codecpar->codec_type == AVMEDIA_TYPE_VIDEO ? is->video_stream
                : codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE ? is->subtitle_stream : -1;
        if (previous == stream) return 0;
        const char *forced = codecpar->codec_type == AVMEDIA_TYPE_AUDIO ? ffp->audio_codec_name
                : codecpar->codec_type == AVMEDIA_TYPE_VIDEO ? ffp->video_codec_name : ffp->subtitle_codec_name;
        AVCodec *decoder = forced ? avcodec_find_decoder_by_name(forced) : avcodec_find_decoder(codecpar->codec_id);
        if (!decoder) return AVERROR_DECODER_NOT_FOUND;
        switch (codecpar->codec_type) {`);
edit(player, '        return stream_component_open(ffp, stream);\n    } else {', `        int result = stream_component_open(ffp, stream);
        if (result < 0 && previous >= 0) {
            stream_component_open(ffp, previous);
            av_log(ffp, AV_LOG_WARNING, "nTv: track switch failed; restored stream %d\\n", previous);
        }
        return result;
    } else {`);
edit(player, '#include "ijksoundtouch/ijksoundtouch_wrap.h"', '#include "ijksoundtouch/ijksoundtouch_wrap.h"\n#include "ijksdl/android/ijksdl_android_jni.h"\nstatic void ntv_audio_close(FFPlayer *ffp);\nstatic int audio_open(FFPlayer *ffp, int64_t wanted_channel_layout, int wanted_nb_channels, int wanted_sample_rate, struct AudioParams *audio_hw_params);');
edit(player, '        decoder_abort(&is->auddec, &is->sampq);', '        decoder_abort(&is->auddec, &is->sampq);\n        ntv_audio_close(ffp);');
edit(player, 'static int audio_thread(void *arg)', '#include "ntv_dolby_audio.inc"\n\nstatic int audio_thread(void *arg)');
edit(player, '    int64_t deviation3 = 0;\n\n    if (!frame)', `    int64_t deviation3 = 0;

    if (is->ntv_audio_output) {
        if (ntv_audio_run(ffp)) { av_frame_free(&frame); return 0; }
        ret = audio_open(ffp, is->auddec.avctx->channel_layout, is->auddec.avctx->channels,
                is->auddec.avctx->sample_rate, &is->audio_tgt);
        if (ret < 0) { av_frame_free(&frame); ffp_notify_msg2(ffp, FFP_MSG_ERROR, ret); return ret; }
        is->audio_hw_buf_size = ret;
        is->audio_src = is->audio_tgt;
        ffp_set_audio_codec_info(ffp, AVCODEC_MODULE_NAME, avcodec_get_name(is->auddec.avctx->codec_id));
        SDL_AoutPauseAudio(ffp->aout, is->paused);
    }

    if (!frame)`);
edit(player, `        if ((ret = audio_open(ffp, channel_layout, nb_channels, sample_rate, &is->audio_tgt)) < 0)
            goto fail;
        ffp_set_audio_codec_info(ffp, AVCODEC_MODULE_NAME, avcodec_get_name(avctx->codec_id));`, `        is->ntv_audio_output = ntv_audio_open(ffp, avctx);
        if (is->ntv_audio_output) {
            is->audio_tgt.freq = sample_rate;
            is->audio_tgt.channels = 2;
            is->audio_tgt.channel_layout = AV_CH_LAYOUT_STEREO;
            is->audio_tgt.fmt = AV_SAMPLE_FMT_S16;
            is->audio_tgt.frame_size = 4;
            is->audio_tgt.bytes_per_sec = sample_rate * 4;
            ret = 1024;
        } else if ((ret = audio_open(ffp, channel_layout, nb_channels, sample_rate, &is->audio_tgt)) < 0) {
            goto fail;
        }
        ffp_set_audio_codec_info(ffp, is->ntv_audio_output ? "AudioTrack passthrough" : AVCODEC_MODULE_NAME, avcodec_get_name(avctx->codec_id));`);
const jni = 'ijkmedia/ijkplayer/android/ijkplayer_jni.c';
edit(jni, 'JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)', 'extern void ntv_dolby_jni_init(JNIEnv *env);\n\nJNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)');
edit(jni, '    FFmpegApi_global_init(env);', '    FFmpegApi_global_init(env);\n    ntv_dolby_jni_init(env);');
const pipeline = 'ijkmedia/ijkplayer/android/pipeline/ffpipeline_android.c';
edit(pipeline, '#include "../../ff_ffplay.h"', '#include "../../ff_ffplay.h"\n#include "../../ntv_dolby_vision.h"');
edit(pipeline, `    if (!node) {
        node = ffpipenode_create_video_decoder_from_ffplay(ffp);`, `    if (!node && ntv_dovi_stream(ffp->is->video_st)) {
        // A generic HEVC software fallback may show incorrect colours for DV.
        ffp_notify_msg2(ffp, FFP_MSG_ERROR, -20001);
        return NULL;
    }
    if (!node) {
        node = ffpipenode_create_video_decoder_from_ffplay(ffp);`);
edit(pipeline, '    opaque->left_volume  = left;', '    if (opaque->ffp) opaque->ffp->ntv_pcm_volume = left != 1.0f || right != 1.0f;\n    opaque->left_volume  = left;');
const vdec = 'ijkmedia/ijkplayer/android/pipeline/ffpipenode_android_mediacodec_vdec.c';
edit(vdec, '#include "ffpipenode_android_mediacodec_vdec.h"', '#include "ffpipenode_android_mediacodec_vdec.h"\n#include "../../ntv_dolby_vision.h"');
const createFormat = '    opaque->input_aformat = SDL_AMediaFormatJava_createVideoFormat(env, opaque->mcc.mime_type, opaque->codecpar->width, opaque->codecpar->height);';
edit(vdec, createFormat, createFormat + `
    if (!strcmp(opaque->mcc.mime_type, "video/dolby-vision")) {
        SDL_AMediaFormat_setInt32(opaque->input_aformat, "profile", opaque->mcc.profile);
        SDL_AMediaFormat_setInt32(opaque->input_aformat, "level", opaque->mcc.level);
    }`);
const dvOverride = `    if (ntv_dovi_stream(ffp->is->video_st)) {
        int profile = ntv_dovi_value(ffp->is->video_st, "ntv_dovi_profile");
        int level = ntv_dovi_value(ffp->is->video_st, "ntv_dovi_level");
        // Never guess a profile or collapse a dual-layer stream into HEVC.
        if ((profile != 5 && profile != 8 && profile != 9) || level < 1 || level > 13
                || ntv_dovi_value(ffp->is->video_st, "ntv_dovi_el") == 1) goto fail;
        strcpy(opaque->mcc.mime_type, "video/dolby-vision");
        opaque->mcc.profile = 1 << profile;
        opaque->mcc.level = 1 << (level - 1);
    }

`;
edit(vdec, '\n    ret = recreate_format_l(env, node);', '\n' + dvOverride + '    ret = recreate_format_l(env, node);', 2);
for (const arch of ['arm64', 'armv7a']) {
    const base = 'android/contrib/ffmpeg-' + arch + '/libavformat/';
    overlay('mov-dovi.inc', base + 'ntv_mov_dovi.inc');
    edit(base + 'hls.c', '3gp,aac,avi,flac,mkv,m3u8,m4a,m4s,m4v,mpg,mov,mp2,mp3,mp4,mpeg,mpegts,ogg,ogv,oga,ts,vob,wav',
        '3gp,aac,ac3,ec3,eac3,mlp,thd,avi,flac,mkv,m3u8,m4a,m4s,m4v,mpg,mov,mp2,mp3,mp4,mpeg,mpegts,ogg,ogv,oga,ts,vob,wav');
    edit(base + 'mov.c', 'static const MOVParseTableEntry mov_default_parse_table[] = {', '#include "ntv_mov_dovi.inc"\n\nstatic const MOVParseTableEntry mov_default_parse_table[] = {\n{ MKTAG(\'d\',\'v\',\'c\',\'C\'), mov_read_ntv_dovi },\n{ MKTAG(\'d\',\'v\',\'v\',\'C\'), mov_read_ntv_dovi },');
    edit(base + 'isom.c', "    { AV_CODEC_ID_HEVC, MKTAG('h', 'e', 'v', '1') },", "    { AV_CODEC_ID_HEVC, MKTAG('d', 'v', 'h', 'e') },\n    { AV_CODEC_ID_HEVC, MKTAG('d', 'v', 'h', '1') },\n    { AV_CODEC_ID_H264, MKTAG('d', 'v', 'a', 'v') },\n    { AV_CODEC_ID_H264, MKTAG('d', 'v', 'a', '1') },\n    { AV_CODEC_ID_HEVC, MKTAG('h', 'e', 'v', '1') },");
    edit(base + 'hls.c', '    err = avcodec_parameters_copy(st->codecpar, ist->codecpar);', '    av_dict_copy(&st->metadata, ist->metadata, 0); /* nTv: preserve Dolby configuration */\n    err = avcodec_parameters_copy(st->codecpar, ist->codecpar);');
    edit(base + 'hls.c', 'struct variant {\n    int bandwidth;', 'struct variant {\n    int bandwidth;\n    char codecs[MAX_FIELD_LEN];');
    edit(base + 'hls.c', 'struct variant_info {\n    char bandwidth[20];', 'struct variant_info {\n    char bandwidth[20];\n    char codecs[MAX_FIELD_LEN];');
    edit(base + 'hls.c', '        var->bandwidth = atoi(info->bandwidth);', '        var->bandwidth = atoi(info->bandwidth);\n        av_strlcpy(var->codecs, info->codecs, sizeof(var->codecs));');
    edit(base + 'hls.c', '        *dest_len = sizeof(info->bandwidth);', '        *dest_len = sizeof(info->bandwidth);\n    } else if (!strncmp(key, "CODECS=", key_len)) {\n        *dest = info->codecs;\n        *dest_len = sizeof(info->codecs);');
    edit(base + 'hls.c', '              av_program_add_stream_index(s, i, stream->index);'.trimStart(), `av_program_add_stream_index(s, i, stream->index);
              // HLS/TS has no MP4 dvcC box. Keep the explicit DV codec profile.
              const char *dv = strstr(v->codecs, "dvhe.");
              if (!dv) dv = strstr(v->codecs, "dvh1.");
              if (!dv) dv = strstr(v->codecs, "dvav.");
              if (!dv) dv = strstr(v->codecs, "dva1.");
              if (dv) {
                  int profile = -1, level = -1;
                  if (sscanf(dv + 5, "%d.%d", &profile, &level) == 2) {
                      av_dict_set_int(&stream->metadata, "ntv_dovi_profile", profile, 0);
                      av_dict_set_int(&stream->metadata, "ntv_dovi_level", level, 0);
                  }
              }`);
}
console.log('Applied nTv Dolby overlays to ' + root);
