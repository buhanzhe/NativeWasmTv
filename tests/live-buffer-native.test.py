"""Build a device harness from the production native control functions."""
from pathlib import Path
import re,sys,subprocess
root=Path(__file__).resolve().parents[1]
out=Path(sys.argv[1]).resolve();out.mkdir(parents=True,exist_ok=True)
source=(root/'.codex-tmp/ijkplayer-0.8.8/ijkmedia/ijkplayer/ff_ffplay.c').read_text()
helpers=source[source.index('static int ntv_live_pending('):source.index('static void video_refresh(')]
setter=re.search(r'        case 22015: \{\n            if \(!ffp[\s\S]*?(?=        case FFP_PROP_INT64_SHARE_CACHE_DATA:)',source)[0]
prefix=r'''
#include <pthread.h>
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <math.h>
#include "ntv_live_buffer.h"
typedef pthread_mutex_t SDL_mutex;
#define SDL_LockMutex pthread_mutex_lock
#define SDL_UnlockMutex pthread_mutex_unlock
#define FFMIN(a,b) ((a)<(b)?(a):(b))
#define FFMAX(a,b) ((a)>(b)?(a):(b))
typedef struct { int64_t duration; } Cache;
typedef struct { int64_t duration; } Input;
typedef struct { SDL_mutex *ntv_live_buffer_mutex; Input *ic; void *video_st,*audio_st; int paused; } VideoState;
typedef struct { VideoState *is; int32_t ntv_live_video_skip_ms,ntv_live_audio_skip_ms,ntv_live_skip_start_ms;
 uint32_t ntv_live_skip_started_at_ms; struct {Cache video_cache,audio_cache;} stat; } FFPlayer;
static uint64_t clock_ms=100;
static int64_t av_gettime_relative(void){return clock_ms*1000;}
static double get_master_clock(VideoState *is){return 10.0;}
'''
checks=r'''
static FFPlayer player;
static void *writer(void *unused){for(int i=0;i<100000;i++)set_buffer(&player,5000);return 0;}
static void *reader(void *raw){for(int i=0;i<200000;i++){
 int result=ntv_live_output(&player,(intptr_t)raw,(i%2)?16.0:11.0);assert(result>=0&&result<=2);
}return 0;}
int main(void){
 SDL_mutex mutex=PTHREAD_MUTEX_INITIALIZER;Input input={0};VideoState state={&mutex,&input,(void*)1,(void*)1,0};
 player.is=&state;player.stat.video_cache.duration=player.stat.audio_cache.duration=10000;
 set_buffer(&player,4000);assert(ntv_live_pending(&player,0));
 set_buffer(&player,1000);assert(player.ntv_live_video_skip_ms==14000);
 assert(ntv_live_output(&player,1,11)==1);assert(ntv_live_output(&player,1,14)==2);
 assert(ntv_live_pending(&player,0));assert(ntv_live_output(&player,0,14)==2);assert(!ntv_live_pending(&player,0));
 set_buffer(&player,4000);clock_ms+=15000;assert(!ntv_live_pending(&player,0));
 set_buffer(&player,4000);assert(!ntv_live_pending(&player,1));
 state.paused=1;set_buffer(&player,4000);assert(!ntv_live_pending(&player,0));state.paused=0;
 set_buffer(&player,INT64_MAX);assert(player.ntv_live_video_skip_ms==19750);ntv_live_pending(&player,1);
 set_buffer(&player,-1);assert(!ntv_live_pending(&player,0));
 set_buffer(&player,4000);assert(ntv_live_output(&player,1,NAN)==2);assert(ntv_live_output(&player,0,1.0)==2);
 clock_ms=UINT32_MAX-100;set_buffer(&player,4000);clock_ms+=15001;assert(!ntv_live_pending(&player,0));
 pthread_t threads[4];pthread_create(&threads[0],0,writer,0);pthread_create(&threads[1],0,writer,0);
 pthread_create(&threads[2],0,reader,(void*)1);pthread_create(&threads[3],0,reader,0);
 for(int i=0;i<4;i++)pthread_join(threads[i],0);ntv_live_pending(&player,1);
 assert(!ntv_live_pending(&player,0));puts("PASS native timeout/pause/overflow/PTS and 600000 concurrent control/output calls");return 0;
}
'''
(out/'native-stress.c').write_text(prefix+helpers+'\nstatic void set_buffer(FFPlayer *ffp,int64_t value){switch(22015){\n'+setter+'}}\n'+checks)
(out/'ntv_live_buffer.h').write_bytes((root/'tools/ijk/ntv_live_buffer.h').read_bytes())
(out/'native-stress.mk').write_text('LOCAL_PATH := $(call my-dir)\ninclude $(CLEAR_VARS)\nLOCAL_MODULE := live-buffer-stress\nLOCAL_SRC_FILES := native-stress.c\nLOCAL_CFLAGS := -O2 -fPIE -std=c99\nLOCAL_LDFLAGS := -fPIE -pie\ninclude $(BUILD_EXECUTABLE)\n')
subprocess.run(['D:/android/sdk/ndk/android-ndk-r17c/ndk-build.cmd','NDK_PROJECT_PATH=.',f'APP_BUILD_SCRIPT={out}/native-stress.mk',
 'APP_ABI=armeabi-v7a','APP_PLATFORM=android-16','APP_MODULES=live-buffer-stress',f'NDK_OUT={out}/obj',f'NDK_LIBS_OUT={out}/bin','-j2'],cwd=root,check=True)
