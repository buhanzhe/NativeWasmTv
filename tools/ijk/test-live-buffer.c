#include "ntv_live_buffer.h"
#include <assert.h>
#include <stdio.h>
int main(void) {
    assert(ntv_live_buffer_skip(14000,10000,100,10.5));
    assert(ntv_live_buffer_skip(14000,10000,100,13.999));
    assert(!ntv_live_buffer_skip(14000,10000,100,14.0));
    assert(!ntv_live_buffer_skip(14000,10000,100,1.0));
    assert(!ntv_live_buffer_skip(14000,10000,15000,11.0));
    assert(!ntv_live_buffer_skip(14000,10000,100,NAN));
    assert(!ntv_live_buffer_skip(14000,10000,100,INFINITY));
    assert(!ntv_live_buffer_skip(0,10000,100,11.0));
    assert(ntv_live_buffer_skip(14000,10000,14999,9.5));
    puts("PASS 9 native live-buffer boundaries, PTS discontinuity and timeout checks");
    return 0;
}
