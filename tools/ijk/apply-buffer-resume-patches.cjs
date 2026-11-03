// LGPL-2.1-or-later: release a satisfied live reserve before blocking demux I/O.
const fs = require('fs'), path = require('path');
module.exports = function applyBufferResume(root) {
  const target = path.join(root, 'ijkmedia/ijkplayer/ff_ffplay.c');
  let text = fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n');
  fs.copyFileSync(path.join(__dirname, 'ntv_buffer_resume.h'),
    path.join(root, 'ijkmedia/ijkplayer/ntv_buffer_resume.h'));
  const include = '#include "ntv_buffer_resume.h"';
  // Migrate the initial insertion without splitting the source-probe overlay's
  // header anchor (which otherwise inserts that header again on a full rerun).
  text = text.replace('#include "ntv_stream_probe.h"\n' + include
    + '\n#include "ntv_stream_probe.h"', include + '\n#include "ntv_stream_probe.h"');
  text = text.replace(include + '\n', '');
  const header = '#include "ff_ffplay.h"';
  if (text.split(header).length !== 2) throw new Error('Unexpected buffer-resume include');
  text = text.replace(header, include + '\n' + header);
  const marker = '            /* nTv: release a satisfied live reserve before blocking demux I/O. */';
  const anchor = '            io_tick_counter = SDL_GetTickHR();';
  const guard = `${marker}
            if (is->buffering_on && !is->abort_request && !ffp->ntv_vod_buffer_active
                && is->ic && is->ic->duration <= 0
                && is->buffer_indicator_queue && is->buffer_indicator_queue->nb_packets > 0
                && (is->audioq.nb_packets >= MIN_MIN_FRAMES || is->audio_stream < 0 || is->audioq.abort_request)
                && (is->videoq.nb_packets >= MIN_MIN_FRAMES || is->video_stream < 0 || is->videoq.abort_request)
                && ntv_buffer_reserve_ready(
                    is->audio_st ? ffp->stat.audio_cache.duration : -1,
                    is->video_st ? ffp->stat.video_cache.duration : -1,
                    ffp->dcc.current_high_water_mark_in_ms)) {
                int ntv_resume_target_ms = ffp->dcc.current_high_water_mark_in_ms;
                ffp_check_buffering_l(ffp);
                if (!is->buffering_on)
                    av_log(ffp, AV_LOG_INFO, "NTV live reserve ready before read audioMs=%lld videoMs=%lld targetMs=%d\\n",
                           (long long)ffp->stat.audio_cache.duration,
                           (long long)ffp->stat.video_cache.duration, ntv_resume_target_ms);
            }
`;
  // Keep the VOD overlay's existing timer-site anchor contiguous on reruns.
  const addition = anchor + '\n' + guard.trimEnd();
  if (!text.includes(addition)) {
    if (text.includes(guard + anchor)) text = text.replace(guard + anchor, anchor);
    else if (text.includes(marker)) throw new Error('Unexpected existing buffer-resume guard');
    if (text.split(anchor).length !== 2) throw new Error('Unexpected buffer-resume polling site');
    text = text.replace(anchor, addition);
  }
  fs.writeFileSync(target, text);
};
if (require.main === module)
  module.exports(path.resolve(process.argv[2] || '.codex-tmp/ijkplayer-0.8.8'));
