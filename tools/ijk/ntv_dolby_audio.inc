/* nTv additions, LGPL-2.1-or-later, included by ff_ffplay.c (Android only).
 * Compressed packets never pass through swresample/PCM volume mixing.
 */
#include "ijksdl/android/ijksdl_android.h"
static jclass ntv_audio_class;
static jmethodID ntv_open_id, ntv_write_id, ntv_position_id, ntv_flush_id, ntv_close_id;

void ntv_dolby_jni_init(JNIEnv *env)
{
    if (SDL_Android_GetApiLevel() < 21) return;
    jclass local = (*env)->FindClass(env, "xiao/bu/tv/DolbyAudioOutput");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    if (!local) return;
    ntv_audio_class = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    ntv_open_id = (*env)->GetStaticMethodID(env, ntv_audio_class, "open", "(III)Lxiao/bu/tv/DolbyAudioOutput;");
    ntv_write_id = (*env)->GetMethodID(env, ntv_audio_class, "write", "([BII)I");
    ntv_position_id = (*env)->GetMethodID(env, ntv_audio_class, "position", "(Z)J");
    ntv_flush_id = (*env)->GetMethodID(env, ntv_audio_class, "flush", "()V");
    ntv_close_id = (*env)->GetMethodID(env, ntv_audio_class, "close", "()V");
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, ntv_audio_class);
        ntv_audio_class = NULL;
    }
}

static int ntv_audio_exception(JNIEnv *env)
{
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionClear(env);
    return 1;
}

static void *ntv_audio_open(FFPlayer *ffp, AVCodecContext *codec)
{
    JNIEnv *env = NULL;
    int encoding = codec->codec_id == AV_CODEC_ID_AC3 ? 5 : codec->codec_id == AV_CODEC_ID_EAC3 ? 6
            : codec->codec_id == AV_CODEC_ID_TRUEHD ? 14 : 0;
    if (!encoding || !ffp->ntv_passthrough || ffp->ntv_pcm_volume
            || fabs(ffp->pf_playback_rate - 1.0) > 0.001 || !ntv_audio_class
            || SDL_JNI_SetupThreadEnv(&env) != JNI_OK) return NULL;
    jobject local = (*env)->CallStaticObjectMethod(env, ntv_audio_class, ntv_open_id,
            encoding, codec->sample_rate, codec->channels);
    if (ntv_audio_exception(env) || !local) return NULL;
    jobject result = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    return result;
}

static void ntv_audio_close(FFPlayer *ffp)
{
    JNIEnv *env = NULL;
    jobject output = ffp->is->ntv_audio_output;
    if (!output || SDL_JNI_SetupThreadEnv(&env) != JNI_OK) return;
    (*env)->CallVoidMethod(env, output, ntv_close_id);
    ntv_audio_exception(env);
    (*env)->DeleteGlobalRef(env, output);
    ffp->is->ntv_audio_output = NULL;
}

/* Returns 0 on route/write/speed failure, 1 when stopping. The caller then
 * opens the existing PCM sink and continues the normal FFmpeg decoder loop.
 * All AudioTrack calls are nonblocking; no Java/native lock spans a write.
 */
static int ntv_audio_run(FFPlayer *ffp)
{
    VideoState *is = ffp->is;
    Decoder *d = &is->auddec;
    JNIEnv *env = NULL;
    AVPacket packet;
    jbyteArray bytes = NULL;
    int capacity = 0, offset = 0, serial = -1, eof = 0, result = 0;
    double base = NAN, submitted = 0;
    int64_t head = 0, previous_head = -1, progress_at = av_gettime_relative();
    av_init_packet(&packet);
    packet.data = NULL;
    packet.size = 0;
    if (SDL_JNI_SetupThreadEnv(&env) != JNI_OK) return 0;
    av_log(ffp, AV_LOG_INFO, "nTv Dolby: compressed AudioTrack output\n");
    while (!is->abort_request && !is->audioq.abort_request) {
        if (ffp->ntv_pcm_volume || fabs(ffp->pf_playback_rate - 1.0) > 0.001) break;
        if (serial != is->audioq.serial) {
            (*env)->CallVoidMethod(env, is->ntv_audio_output, ntv_flush_id);
            if (ntv_audio_exception(env)) break;
            av_packet_unref(&packet);
            offset = eof = 0;
            base = NAN;
            submitted = 0;
            previous_head = -1;
            serial = is->audioq.serial;
            progress_at = av_gettime_relative();
        }
        head = (*env)->CallLongMethod(env, is->ntv_audio_output, ntv_position_id, (jboolean)is->paused);
        if (ntv_audio_exception(env) || head < 0) break;
        if (!isnan(base)) {
            set_clock(&is->audclk, base + head / 1000000.0, serial);
            sync_clock_to_slave(&is->extclk, &is->audclk);
        }
        if (head != previous_head || is->paused || (submitted <= head / 1000000.0 + 0.02 && !packet.data))
            progress_at = av_gettime_relative();
        previous_head = head;
        if (av_gettime_relative() - progress_at > 3000000) break;
        if (is->paused) { SDL_Delay(10); continue; }
        if (eof && head / 1000000.0 >= submitted - 0.02) d->finished = serial;
        if (!packet.data) {
            int got = packet_queue_get(&is->audioq, &packet, 0, &d->pkt_serial);
            if (got < 0) break;
            if (!got) {
                SDL_CondSignal(is->continue_read_thread);
                if (!eof && !is->eof && submitted <= head / 1000000.0 + 0.02) ffp_toggle_buffering(ffp, 1);
                SDL_Delay(10);
                continue;
            }
            if (d->pkt_serial != serial || packet.data == flush_pkt.data) {
                av_packet_unref(&packet);
                d->finished = 0;
                continue;
            }
            if (!packet.data || !packet.size) {
                eof = 1;
                av_packet_unref(&packet);
                continue;
            }
            eof = 0;
            offset = 0;
            if (isnan(base)) base = packet.pts == AV_NOPTS_VALUE ? 0 : packet.pts * av_q2d(is->audio_st->time_base);
            // A demux packet is bounded independently from stream/playlist size.
            if (packet.size > 1024 * 1024) break;
            if (packet.size > capacity) {
                if (bytes) (*env)->DeleteLocalRef(env, bytes);
                capacity = packet.size;
                bytes = (*env)->NewByteArray(env, capacity);
                if (ntv_audio_exception(env) || !bytes) break;
            }
            (*env)->SetByteArrayRegion(env, bytes, 0, packet.size, (const jbyte *)packet.data);
            if (ntv_audio_exception(env)) break;
        }
        int written = (*env)->CallIntMethod(env, is->ntv_audio_output, ntv_write_id,
                bytes, offset, packet.size - offset);
        if (ntv_audio_exception(env) || written < 0 || written > packet.size - offset) break;
        offset += written;
        if (offset < packet.size) { SDL_Delay(5); continue; }
        double duration = packet.duration * av_q2d(is->audio_st->time_base);
        if (duration <= 0) duration = (double)av_get_audio_frame_duration(d->avctx, packet.size) / d->avctx->sample_rate;
        submitted += duration;
        if (!ffp->first_audio_frame_rendered) {
            ffp->first_audio_frame_rendered = 1;
            ffp_notify_msg1(ffp, FFP_MSG_AUDIO_RENDERING_START);
        }
        av_packet_unref(&packet);
    }
    result = is->abort_request || is->audioq.abort_request;
    // Replay a packet that was not completely accepted, but never an old seek serial.
    if (!result && packet.data && serial == is->audioq.serial) {
        av_packet_move_ref(&d->pkt, &packet);
        d->packet_pending = 1;
    }
    av_packet_unref(&packet);
    if (bytes) (*env)->DeleteLocalRef(env, bytes);
    ntv_audio_close(ffp);
    if (!result) av_log(ffp, AV_LOG_WARNING, "nTv Dolby: fallback to FFmpeg PCM\n");
    return result;
}
