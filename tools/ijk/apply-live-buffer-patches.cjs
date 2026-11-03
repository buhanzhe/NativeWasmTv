// Forward-only live buffer adjustment. Keep demux, reference frames and auth alive.
const fs=require('fs'),path=require('path');
module.exports=function(root){
  fs.copyFileSync(path.join(__dirname,'ntv_live_buffer.h'),path.join(root,'ijkmedia/ijkplayer/ntv_live_buffer.h'));
  function edit(file,before,after){
    const target=path.join(root,'ijkmedia/ijkplayer',file),text=fs.readFileSync(target,'utf8').replace(/\r\n/g,'\n');
    const marker=before==='    int ntv_frame_sync;' ? '    int32_t ntv_live_video_skip_ms;'
      : before==='            /* compute nominal last_duration */' ? '            /* Only discard display output.'
      : before==='    } while (af->serial != is->audioq.serial);' ? (text.includes('ntv_live_output(ffp,0,') ? 'ntv_live_output(ffp,0,' : '    int32_t ntv_audio_target =')
      : before==='        case FFP_PROP_INT64_SELECTED_VIDEO_STREAM:' ? '        case 22015: { /* live forward-buffer control capability */'
      : before==='        case FFP_PROP_INT64_SHARE_CACHE_DATA:' ? '        case 22015: {\n            if (!ffp || !ffp->is' : null;
    if(marker&&text.includes(marker))return;
    if(text.includes(after))return;
    if(text.split(before).length!==2)throw new Error('Unexpected live-buffer upstream: '+file+' / '+before);
    fs.writeFileSync(target,text.replace(before,after));
  }
  edit('ff_ffplay_def.h','    int ntv_frame_sync;',
    '    int32_t ntv_live_video_skip_ms; /* atomic, absolute media timestamp; zero when idle */\n    int32_t ntv_live_audio_skip_ms;\n    int ntv_frame_sync;');
  edit('ff_ffplay_def.h','    int32_t ntv_live_audio_skip_ms;',
    '    int32_t ntv_live_audio_skip_ms;\n    int32_t ntv_live_skip_start_ms;\n    uint32_t ntv_live_skip_started_at_ms;');
  edit('ff_ffplay.c','#include "ff_ffplay_options.h"','#include "ntv_live_buffer.h"\n#include "ff_ffplay_options.h"');
  edit('ff_ffplay_def.h','    ffp_reset_demux_cache_control(&ffp->dcc);',
    '    ffp_reset_demux_cache_control(&ffp->dcc);\n    __atomic_store_n(&ffp->ntv_live_video_skip_ms,0,__ATOMIC_RELAXED);\n    __atomic_store_n(&ffp->ntv_live_audio_skip_ms,0,__ATOMIC_RELAXED);');
  edit('ff_ffplay.c','            double last_duration, duration, delay;',
    '            double last_duration, duration, delay;\n            int ntv_live_arrived = 0;');
  edit('ff_ffplay.c','            /* compute nominal last_duration */',`            /* Only discard display output. Decoders still see every reference packet. */
            int32_t ntv_target = __atomic_load_n(&ffp->ntv_live_video_skip_ms,__ATOMIC_RELAXED);
            if (ntv_target > 0) {
                if (!isnan(vp->pts) && vp->pts * 1000.0 < ntv_target) {
                    frame_queue_next(&is->pictq);
                    goto retry;
                }
                __atomic_compare_exchange_n(&ffp->ntv_live_video_skip_ms,&ntv_target,0,0,__ATOMIC_RELAXED,__ATOMIC_RELAXED);
                is->frame_timer = av_gettime_relative() / 1000000.0;
                ntv_live_arrived = 1;
            }

            /* compute nominal last_duration */`);
  edit('ff_ffplay.c','            last_duration = vp_duration(is, lastvp, vp);',
    '            last_duration = ntv_live_arrived ? 0 : vp_duration(is, lastvp, vp);');
  edit('ff_ffplay.c','    } while (af->serial != is->audioq.serial);',`    } while (af->serial != is->audioq.serial);
    int32_t ntv_audio_target = __atomic_load_n(&ffp->ntv_live_audio_skip_ms,__ATOMIC_RELAXED);
    if (ntv_audio_target > 0) {
        if (!isnan(af->pts) && (af->pts + af->duration) * 1000.0 < ntv_audio_target)
            goto reload;
        __atomic_compare_exchange_n(&ffp->ntv_live_audio_skip_ms,&ntv_audio_target,0,0,__ATOMIC_RELAXED,__ATOMIC_RELAXED);
    }`);
  edit('ff_ffplay.c','        case FFP_PROP_INT64_SELECTED_VIDEO_STREAM:',`        case 22015: { /* live forward-buffer control capability */
            if (!ffp || !ffp->is || !ffp->is->ic || ffp->is->ic->duration > 0) return 0;
            double clock = get_master_clock(ffp->is) * 1000.0;
            return isfinite(clock) && clock >= 0 && clock < INT32_MAX - 3600000;
        }
        case 22016: /* pending A/V output advance */
            return ffp && (__atomic_load_n(&ffp->ntv_live_video_skip_ms,__ATOMIC_RELAXED) > 0
                    || __atomic_load_n(&ffp->ntv_live_audio_skip_ms,__ATOMIC_RELAXED) > 0);
        case FFP_PROP_INT64_SELECTED_VIDEO_STREAM:`);
  edit('ff_ffplay.c','        case FFP_PROP_INT64_SHARE_CACHE_DATA:',`        case 22015: {
            if (!ffp || !ffp->is || !ffp->is->ic || ffp->is->ic->duration > 0 || value <= 0) break;
            VideoState *is = ffp->is;
            int64_t cached = is->video_st ? ffp->stat.video_cache.duration : ffp->stat.audio_cache.duration;
            if (is->video_st && is->audio_st) cached = FFMIN(cached,ffp->stat.audio_cache.duration);
            value = FFMIN(value,FFMAX(0,cached-250));
            double now = get_master_clock(is) * 1000.0;
            double target = now + value;
            /* Reject absent clocks / unrepresentable clocks instead of wrapping. */
            if (value < 100 || !isfinite(now) || target <= 0 || target >= INT32_MAX) break;
            __atomic_store_n(&ffp->ntv_live_video_skip_ms,is->video_st ? (int32_t)target : 0,__ATOMIC_RELAXED);
            __atomic_store_n(&ffp->ntv_live_audio_skip_ms,is->audio_st ? (int32_t)target : 0,__ATOMIC_RELAXED);
            break;
        }
        case FFP_PROP_INT64_SHARE_CACHE_DATA:`);
  // Keep the insertion anchors above stable for all existing overlay reruns.
  const play=path.join(root,'ijkmedia/ijkplayer/ff_ffplay.c');
  let text=fs.readFileSync(play,'utf8');
  const guard='ntv_live_buffer_skip(ntv_target,__atomic_load_n(&ffp->ntv_live_skip_start_ms,__ATOMIC_RELAXED),\n                        (uint32_t)(av_gettime_relative()/1000)-__atomic_load_n(&ffp->ntv_live_skip_started_at_ms,__ATOMIC_RELAXED),vp->pts)';
  const audioGuard='ntv_live_buffer_skip(ntv_audio_target,__atomic_load_n(&ffp->ntv_live_skip_start_ms,__ATOMIC_RELAXED),\n                (uint32_t)(av_gettime_relative()/1000)-__atomic_load_n(&ffp->ntv_live_skip_started_at_ms,__ATOMIC_RELAXED),af->pts + af->duration)';
  // The migrated checks are also accepted by edit() on the next run.
  if(!text.includes(guard))text=text.replace('!isnan(vp->pts) && vp->pts * 1000.0 < ntv_target',guard);
  if(!text.includes(audioGuard))text=text.replace('!isnan(af->pts) && (af->pts + af->duration) * 1000.0 < ntv_audio_target',audioGuard);
  if(!text.includes('__atomic_store_n(&ffp->ntv_live_skip_start_ms,'))text=text.replace(
    '            __atomic_store_n(&ffp->ntv_live_video_skip_ms,is->video_st',
    '            __atomic_store_n(&ffp->ntv_live_skip_start_ms,(int32_t)now,__ATOMIC_RELAXED);\n            __atomic_store_n(&ffp->ntv_live_skip_started_at_ms,(uint32_t)(av_gettime_relative()/1000),__ATOMIC_RELAXED);\n            __atomic_store_n(&ffp->ntv_live_video_skip_ms,is->video_st');
  text=text.replace(/(ntv_live_(?:video|audio)_skip_ms,)__ATOMIC_RELAXED/g,'$1__ATOMIC_ACQUIRE');
  text=text.replace(/(ntv_live_(?:video|audio)_skip_ms,is->(?:video|audio)_st \? \(int32_t\)target : 0,)__ATOMIC_RELAXED/g,'$1__ATOMIC_RELEASE');
  // Serialize only active advances. Idle playback takes one atomic load, no lock.
  const def=path.join(root,'ijkmedia/ijkplayer/ff_ffplay_def.h');
  let definition=fs.readFileSync(def,'utf8');
  if(!definition.includes('SDL_mutex *ntv_live_buffer_mutex;'))definition=definition.replace(
    '    SDL_mutex  *play_mutex;', '    SDL_mutex *ntv_live_buffer_mutex;\n    SDL_mutex  *play_mutex;');
  fs.writeFileSync(def,definition);
  if(!text.includes('is->ntv_live_buffer_mutex = SDL_CreateMutex();'))text=text.replace(
    '    is->play_mutex = SDL_CreateMutex();','    is->ntv_live_buffer_mutex = SDL_CreateMutex();\n    is->play_mutex = SDL_CreateMutex();');
  if(!text.includes('SDL_DestroyMutex(is->ntv_live_buffer_mutex);'))text=text.replace(
    '    SDL_DestroyMutex(is->play_mutex);','    SDL_DestroyMutex(is->ntv_live_buffer_mutex);\n    SDL_DestroyMutex(is->play_mutex);');
  if(!text.includes('static int ntv_live_pending('))text=text.replace('static void video_refresh(',`/* Control-plane expiry also works while paused or starved of frames. */
static int ntv_live_pending(FFPlayer *ffp, int cancel) {
    VideoState *is = ffp ? ffp->is : NULL;
    if (!is || !is->ntv_live_buffer_mutex) return 0;
    if (!__atomic_load_n(&ffp->ntv_live_video_skip_ms,__ATOMIC_ACQUIRE)
            && !__atomic_load_n(&ffp->ntv_live_audio_skip_ms,__ATOMIC_ACQUIRE)) return 0;
    SDL_LockMutex(is->ntv_live_buffer_mutex);
    uint32_t elapsed = (uint32_t)(av_gettime_relative()/1000) - ffp->ntv_live_skip_started_at_ms;
    if (cancel || is->paused || elapsed >= 15000) {
        __atomic_store_n(&ffp->ntv_live_video_skip_ms,0,__ATOMIC_RELEASE);
        __atomic_store_n(&ffp->ntv_live_audio_skip_ms,0,__ATOMIC_RELEASE);
    }
    int pending = ffp->ntv_live_video_skip_ms > 0 || ffp->ntv_live_audio_skip_ms > 0;
    SDL_UnlockMutex(is->ntv_live_buffer_mutex);
    return pending;
}

/* 0: idle, 1: discard output, 2: first output after an advance. */
static int ntv_live_output(FFPlayer *ffp, int video, double pts) {
    int32_t *slot = video ? &ffp->ntv_live_video_skip_ms : &ffp->ntv_live_audio_skip_ms;
    if (!__atomic_load_n(slot,__ATOMIC_ACQUIRE)) return 0;
    VideoState *is = ffp->is;
    SDL_LockMutex(is->ntv_live_buffer_mutex);
    int32_t target = __atomic_load_n(slot,__ATOMIC_RELAXED);
    int skip = ntv_live_buffer_skip(target,ffp->ntv_live_skip_start_ms,
            (uint32_t)(av_gettime_relative()/1000)-ffp->ntv_live_skip_started_at_ms,pts);
    if (!skip) __atomic_store_n(slot,0,__ATOMIC_RELEASE);
    SDL_UnlockMutex(is->ntv_live_buffer_mutex);
    return skip ? 1 : target > 0 ? 2 : 0;
}

static void video_refresh(`);
  text=text.replace(/            int32_t ntv_target =[^]*?\n            \/\* compute nominal last_duration \*\//,`            int ntv_live_result = ntv_live_output(ffp,1,vp->pts);
            if (ntv_live_result == 1) {
                frame_queue_next(&is->pictq);
                goto retry;
            }
            if (ntv_live_result == 2) {
                is->frame_timer = av_gettime_relative() / 1000000.0;
                ntv_live_arrived = 1;
            }

            /* compute nominal last_duration */`);
  text=text.replace(/    int32_t ntv_audio_target =[^]*?\n    }/, '    if (ntv_live_output(ffp,0,af->pts + af->duration) == 1) goto reload;');
  text=text.replace(/        case 22016: \/\* pending A\/V output advance \*\/[^]*?(?=        case FFP_PROP_INT64_SELECTED_VIDEO_STREAM:)/,
    '        case 22016: /* pending A/V output advance */\n            return ntv_live_pending(ffp,0);\n');
  text=text.replace(/        case 22015: \{\n            if \(!ffp[^]*?(?=        case FFP_PROP_INT64_SHARE_CACHE_DATA:)/,`        case 22015: {
            if (!ffp || !ffp->is || !ffp->is->ic || ffp->is->ic->duration > 0 || value <= 0) break;
            VideoState *is = ffp->is;
            if (!is->ntv_live_buffer_mutex || ntv_live_pending(ffp,0)) break;
            SDL_LockMutex(is->ntv_live_buffer_mutex);
            int64_t cached = is->video_st ? ffp->stat.video_cache.duration : ffp->stat.audio_cache.duration;
            if (is->video_st && is->audio_st) cached = FFMIN(cached,ffp->stat.audio_cache.duration);
            value = FFMIN(value,FFMAX(0,cached-250));
            double now = get_master_clock(is) * 1000.0, target = now + value;
            if (!is->paused && !ffp->ntv_live_video_skip_ms && !ffp->ntv_live_audio_skip_ms
                    && value >= 100 && isfinite(now) && now >= 0 && target > 0 && target < INT32_MAX) {
                ffp->ntv_live_skip_start_ms = (int32_t)now;
                ffp->ntv_live_skip_started_at_ms = (uint32_t)(av_gettime_relative()/1000);
                __atomic_store_n(&ffp->ntv_live_video_skip_ms,is->video_st ? (int32_t)target : 0,__ATOMIC_RELEASE);
                __atomic_store_n(&ffp->ntv_live_audio_skip_ms,is->audio_st ? (int32_t)target : 0,__ATOMIC_RELEASE);
            }
            SDL_UnlockMutex(is->ntv_live_buffer_mutex);
            break;
        }
`);
  text=text.replace('if (cancel || elapsed >= 15000)', 'if (cancel || is->paused || elapsed >= 15000)');
  text=text.replace(/\n    ntv_live_pending\(ffp,1\);/g,'');
  text=text.replace(/(int ffp_pause_l\(FFPlayer \*ffp\)[^]*?    toggle_pause\(ffp, 1\);)/,'$1\n    ntv_live_pending(ffp,1);');
  fs.writeFileSync(play,text);
};
if(require.main===module)module.exports(path.resolve(process.argv[2]||'.codex-tmp/ijkplayer-0.8.8'));
