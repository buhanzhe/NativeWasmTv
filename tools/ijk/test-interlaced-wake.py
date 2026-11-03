"""Measure the actual refresh-loop wait on Android with a 2ms field producer.

The production refresh function runs with pthread SDL adapters. Rendering is
stubbed after two loop passes; both original polling and new BWDIF wake paths
receive the same queue signal. No application/JVM or release fixtures required.
"""
import argparse
from pathlib import Path
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--ndk',required=True,type=Path)
p.add_argument('--output',required=True,type=Path)
a=p.parse_args();repo=Path(__file__).resolve().parents[2]
out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
source=(repo/'.codex-tmp/ijkplayer-0.8.8/ijkmedia/ijkplayer/ff_ffplay.c').read_text()
start=source.index('static int video_refresh_thread(void *arg)\n{')
function=source[start:source.index('\nstatic int lockmgr(',start)]
guard='''(ffp->ntv_frame_sync ||
                    (__atomic_load_n(&ffp->ntv_video_description, __ATOMIC_RELAXED) & (1U << 30)))'''
assert guard in function
old=function.replace(guard,'ffp->ntv_frame_sync').replace('video_refresh_thread','old_refresh_thread')
harness=r'''
#include <assert.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#define SHOW_MODE_NONE 0
#define REFRESH_RATE .01
typedef pthread_mutex_t SDL_mutex;
typedef pthread_cond_t SDL_cond;
typedef struct {int size,rindex_shown;SDL_mutex *mutex;SDL_cond *cond;} Queue;
typedef struct {
 int abort_request,realtime,paused,step,show_mode,force_refresh;
 void *audio_st;Queue pictq;double frame_timer;
} VideoState;
typedef struct {VideoState *is;int ntv_live_video,ntv_frame_sync;uint32_t ntv_video_description;} FFPlayer;
static int passes,waits,sleeps;
static int64_t av_gettime_relative(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000000LL+t.tv_nsec/1000;}
static void SDL_LockMutex(SDL_mutex *m){pthread_mutex_lock(m);}
static void SDL_UnlockMutex(SDL_mutex *m){pthread_mutex_unlock(m);}
static void av_usleep(int us){++sleeps;usleep(us);}
static void SDL_CondWaitTimeout(SDL_cond *c,SDL_mutex *m,int ms){
 struct timespec t;clock_gettime(CLOCK_REALTIME,&t);t.tv_nsec+=(long)ms*1000000;
 if(t.tv_nsec>=1000000000){++t.tv_sec;t.tv_nsec-=1000000000;}
 ++waits;pthread_cond_timedwait(c,m,&t);
}
static void video_refresh(FFPlayer *f,double *remaining){if(++passes==2)f->is->abort_request=1;}
FUNCTIONS
static void *producer(void *p){
 VideoState *s=p;usleep(2000);SDL_LockMutex(s->pictq.mutex);
 s->pictq.size=1;pthread_cond_signal(s->pictq.cond);SDL_UnlockMutex(s->pictq.mutex);return NULL;
}
static int cmp(const void *a,const void *b){int64_t x=*(const int64_t *)a,y=*(const int64_t *)b;return (x>y)-(x<y);}
static int64_t run(int patched,int bwdif,int hardware){
 int64_t elapsed[21];
 for(int n=0;n<21;++n){
  SDL_mutex mutex=PTHREAD_MUTEX_INITIALIZER;SDL_cond cond=PTHREAD_COND_INITIALIZER;
  VideoState s={.show_mode=1,.pictq={.mutex=&mutex,.cond=&cond}};
  FFPlayer f={.is=&s,.ntv_video_description=bwdif?1U<<30:0,.ntv_frame_sync=hardware};
  pthread_t thread;passes=waits=sleeps=0;int64_t start=av_gettime_relative();
  assert(!pthread_create(&thread,NULL,producer,&s));
  if(patched)video_refresh_thread(&f);else old_refresh_thread(&f);
  elapsed[n]=av_gettime_relative()-start;pthread_join(thread,NULL);
  assert(waits==((hardware||(patched&&bwdif))?1:0));assert(sleeps==!waits);
  pthread_mutex_destroy(&mutex);pthread_cond_destroy(&cond);
 }
 qsort(elapsed,21,sizeof(*elapsed),cmp);return elapsed[10];
}
int main(void){
 int64_t old=run(0,1,0),filtered=run(1,1,0),progressive=run(1,0,0),hardware=run(1,0,1);
 printf("poll_us=%lld bwdif_wake_us=%lld progressive_poll_us=%lld hardware_wake_us=%lld\n",
  (long long)old,(long long)filtered,(long long)progressive,(long long)hardware);
 assert(filtered+2000<old);assert(hardware+2000<progressive);
 puts("PASS new BWDIF fields wake on queue signal; progressive and hardware policies preserved");return 0;
}
'''
(out/'wake.c').write_text(harness.replace('FUNCTIONS',function+'\n'+old))
(out/'Application.mk').write_text('APP_ABI := arm64-v8a\nAPP_PLATFORM := android-21\nNDK_TOOLCHAIN_VERSION := 4.9\n')
(out/'Android.mk').write_text('LOCAL_PATH := $(call my-dir)\ninclude $(CLEAR_VARS)\nLOCAL_MODULE := wake-test\nLOCAL_SRC_FILES := wake.c\nLOCAL_CFLAGS := -std=c99 -UNDEBUG\ninclude $(BUILD_EXECUTABLE)\n')
subprocess.run([str(a.ndk/'ndk-build.cmd'),'NDK_PROJECT_PATH=.',
                'APP_BUILD_SCRIPT='+str(out/'Android.mk'),'NDK_APPLICATION_MK='+str(out/'Application.mk'),
                'NDK_OUT='+str(out/'obj'),'NDK_LIBS_OUT='+str(out/'libs')],cwd=repo,check=True)
remote='/data/local/tmp/ntv-interlaced-wake-test'
subprocess.run(['adb','-s',a.serial,'push',str(out/'libs/arm64-v8a/wake-test'),remote],check=True)
subprocess.run(['adb','-s',a.serial,'shell','chmod','755',remote],check=True)
subprocess.run(['adb','-s',a.serial,'shell',remote],check=True)
