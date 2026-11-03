"""Exercise the production buffering decision/guard before the next blocking read."""
from pathlib import Path
import argparse, subprocess
root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('--device',required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
source=(root/'.codex-tmp/ijkplayer-0.8.8/ijkmedia/ijkplayer/ff_ffplay.c').read_text(encoding='utf8')
def block(signature):
    a=source.index(signature);b=source.index('{',a)+1;depth=1
    while depth:depth+=(source[b]=='{')-(source[b]=='}');b+=1
    return source[a:b]
a=source.index('            /* nTv: release a satisfied live reserve before blocking demux I/O. */')
b=source.index('            if ((!ffp->first_video_frame_rendered',a)
guard=source[a:b]
prefix=r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "ntv_buffer_resume.h"
#include "ntv_vod_buffer.h"
#define FFMIN(a,b) ((a)<(b)?(a):(b))
#define FFMAX(a,b) ((a)>(b)?(a):(b))
#define IJKMIN FFMIN
#define MIN_MIN_FRAMES 2
#define av_log(...) ((void)0)
#define ffp_notify_msg3(...) ((void)0)
typedef struct {int num,den;} Ratio;
typedef struct {Ratio time_base;} Track;
typedef struct {int size,nb_packets,abort_request;int64_t duration;} Queue;
typedef struct {int64_t duration;} Input;
typedef struct {int buffering_on,abort_request,seek_buffering,audio_stream,video_stream;
 Track *audio_st,*video_st;Queue audioq,videoq,*buffer_indicator_queue;Input *ic;} VideoState;
typedef struct {int64_t duration;} Cache;
typedef struct {VideoState *is;struct {int current_high_water_mark_in_ms,high_water_mark_in_bytes,next_high_water_mark_in_ms,last_high_water_mark_in_ms,max_buffer_size;}dcc;
 struct {Cache audio_cache,video_cache;}stat;int ntv_vod_buffer_active,ntv_vod_proxy_wait_ms,ntv_vod_buffer_mode,ntv_vod_buffer_last_target_ms,ntv_vod_proxy_ready;
 int64_t ntv_vod_buffer_start_us,playable_duration_ms;} FFPlayer;
static int checks,ends,user_paused;
static int64_t av_gettime_relative(void){return 1000000;}
static int64_t ffp_get_current_position_l(FFPlayer *ffp){return 7541;}
static int64_t av_rescale(int64_t v,int64_t n,int64_t d){return (v*n+d/2)/d;}
static void ffp_toggle_buffering(FFPlayer *ffp,int on){ffp->is->buffering_on=on;ends+=!on;}
'''
checks=r'''
static Track track={{1,1000}};static Input input;static VideoState state;static FFPlayer player;
static void reset(void){state=(VideoState){0};player=(FFPlayer){0};checks=ends=user_paused=0;
 input.duration=0;state.ic=&input;state.buffering_on=1;state.audio_st=state.video_st=&track;
 state.audio_stream=0;state.video_stream=1;state.audioq.nb_packets=state.videoq.nb_packets=8;
 state.audioq.size=state.videoq.size=65536;state.buffer_indicator_queue=&state.videoq;
 player.is=&state;player.stat.audio_cache.duration=player.stat.video_cache.duration=8000;
 player.dcc.current_high_water_mark_in_ms=5000;player.dcc.next_high_water_mark_in_ms=player.dcc.last_high_water_mark_in_ms=5000;
 player.dcc.high_water_mark_in_bytes=512*1024;player.dcc.max_buffer_size=15*1024*1024;}
int main(void){
 reset();assert(state.buffering_on); /* A 5ms packet burst missed the legacy 500ms poll. */
 before_read(&player);assert(!state.buffering_on&&ends==1&&checks==1);
 reset();player.stat.audio_cache.duration=4000;before_read(&player);assert(state.buffering_on&&checks==0);
 player.stat.audio_cache.duration=5000;before_read(&player);assert(!state.buffering_on&&checks==1);
 reset();state.audioq.nb_packets=1;before_read(&player);assert(state.buffering_on&&checks==0);
 reset();state.buffer_indicator_queue=NULL;before_read(&player);assert(state.buffering_on&&checks==0);
 reset();state.abort_request=1;before_read(&player);assert(state.buffering_on&&checks==0);
 reset();player.ntv_vod_buffer_active=1;before_read(&player);assert(state.buffering_on&&checks==0);
 reset();input.duration=60000;before_read(&player);assert(state.buffering_on&&checks==0);
 reset();state.video_st=NULL;state.video_stream=-1;state.buffer_indicator_queue=&state.audioq;before_read(&player);assert(!state.buffering_on);
 reset();state.audio_st=NULL;state.audio_stream=-1;before_read(&player);assert(!state.buffering_on);
 reset();player.stat.audio_cache.duration=player.stat.video_cache.duration=0;before_read(&player);assert(checks==0);
 reset();player.dcc.current_high_water_mark_in_ms=0;before_read(&player);assert(checks==0);
 reset();user_paused=1;before_read(&player);assert(user_paused&&ends==1);
 reset();state.buffering_on=0;for(int i=0;i<1000000;i++)before_read(&player);assert(checks==0&&ends==0);
 assert(ntv_buffer_reserve_ready(INT64_MAX,INT64_MAX,INT32_MAX));
 puts("PASS real buffering function + packet guard: burst-before-block, both-track reserve, packet counts, abort, VOD, audio/video-only, pause, million steady calls");return 0;}
'''
function=block('void ffp_check_buffering_l(FFPlayer *ffp)')
function=function.replace('    VideoState *is            = ffp->is;', '    checks++;\n    VideoState *is            = ffp->is;')
(out/'check.c').write_text(prefix+function+'\nstatic void before_read(FFPlayer *ffp){VideoState *is=ffp->is;\n'+guard+'}\n'+checks,encoding='utf8')
for name in ['ntv_buffer_resume.h','ntv_vod_buffer.h']:(out/name).write_bytes((root/'tools/ijk'/name).read_bytes())
(out/'Android.mk').write_text('LOCAL_PATH := $(call my-dir)\ninclude $(CLEAR_VARS)\nLOCAL_MODULE := buffer-resume-check\nLOCAL_SRC_FILES := check.c\nLOCAL_CFLAGS := -O2 -std=c99 -fPIE\nLOCAL_LDFLAGS := -fPIE -pie\ninclude $(BUILD_EXECUTABLE)\n',encoding='utf8')
(out/'Application.mk').write_text('APP_ABI := armeabi-v7a\nAPP_PLATFORM := android-16\nAPP_STL := none\nNDK_TOOLCHAIN_VERSION := 4.9\n',encoding='utf8')
def run(cmd):
    r=subprocess.run([str(x)for x in cmd],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    text=r.stdout.decode('utf8','replace')
    if r.returncode:raise RuntimeError(text)
    return text
build=run(['D:/android/sdk/ndk/android-ndk-r17c/ndk-build.cmd','NDK_PROJECT_PATH=.',f'APP_BUILD_SCRIPT={out}/Android.mk',f'NDK_APPLICATION_MK={out}/Application.mk',f'NDK_OUT={out}/obj',f'NDK_LIBS_OUT={out}/libs','-j2'])
(out/'build.log').write_text(build,encoding='utf8')
adb='D:/android/sdk/platform-tools/adb.exe';remote='/data/local/tmp/ntv-buffer-resume-check'
run([adb,'-s',args.device,'push',out/'libs/armeabi-v7a/buffer-resume-check',remote])
result=run([adb,'-s',args.device,'shell',f'chmod 755 {remote} && {remote}'])
(out/'device.txt').write_text(result,encoding='utf8');print(result)
