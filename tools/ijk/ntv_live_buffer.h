#ifndef NTV_LIVE_BUFFER_H
#define NTV_LIVE_BUFFER_H
#include <stdint.h>
#include <math.h>
/* A discontinuity can reset PTS while the same live input stays open. Never
 * discard a new programme waiting for an old timestamp, or wait indefinitely. */
static int ntv_live_buffer_skip(int32_t target, int32_t start, uint32_t elapsed_ms, double pts) {
    return target > 0 && elapsed_ms < 15000 && isfinite(pts)
            && pts * 1000.0 >= (double)start - 500.0 && pts * 1000.0 < target;
}
#endif
