/* LGPL-2.1-or-later. Match IJK's positive-duration reserve selection. */
#ifndef NTV_BUFFER_RESUME_H
#define NTV_BUFFER_RESUME_H
#include <stdint.h>

static int ntv_buffer_reserve_ready(int64_t audio_ms, int64_t video_ms, int target_ms)
{
    int64_t cached_ms = audio_ms > 0 ? audio_ms : video_ms;
    if (video_ms > 0 && audio_ms > 0 && video_ms < cached_ms)
        cached_ms = video_ms;
    return target_ms > 0 && cached_ms >= target_ms;
}
#endif
