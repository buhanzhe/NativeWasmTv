"""Run the patched production get_video_frame decision on an ADB device.

Clock/decoder stubs put every input 10 ms behind audio with queued packets.
Interlaced references must survive; progressive late dropping must still work.
"""
import argparse
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--ndk', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
repo = Path(__file__).resolve().parents[2]
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=True)
source = (repo / '.codex-tmp/ijkplayer-0.8.8/ijkmedia/ijkplayer/ff_ffplay.c').read_text()
start = source.index('static int get_video_frame(')
end = source.index('\n#if CONFIG_AVFILTER', start)
function = source[start:end]
harness = r'''
#include <assert.h>
#include <math.h>
#include <stdio.h>
#define AV_NOPTS_VALUE (-9223372036854775807LL-1)
#define AV_SYNC_VIDEO_MASTER 1
#define AV_NOSYNC_THRESHOLD 100.0
typedef struct {long long pts; int interlaced_frame, sample_aspect_ratio, released;} AVFrame;
typedef struct {double time_base;} Stream;
typedef struct {
    Stream *video_st; void *ic;
    struct {int pkt_serial;} viddec;
    struct {int serial;} vidclk;
    struct {int nb_packets;} videoq;
    double frame_last_filter_delay;
    int frame_drops_early, continuous_frame_drops_early;
} VideoState;
typedef struct {
    VideoState *is; int framedrop;
    struct {int decode_frame_count,drop_frame_count;float drop_frame_rate;} stat;
} FFPlayer;
static void ffp_video_statistic_l(FFPlayer *f) {}
static int decoder_decode_frame(FFPlayer *f,void *d,AVFrame *v,void *s) {return 1;}
static double av_q2d(double tb) {return tb;}
static int av_guess_sample_aspect_ratio(void *ic,Stream *s,AVFrame *v) {return 1;}
static int get_master_sync_type(VideoState *s) {return 0;}
static double get_master_clock(VideoState *s) {return 1.010;}
static void av_frame_unref(AVFrame *v) {v->released=1;}
FUNCTION
int main(void) {
    Stream stream={0.001};
    VideoState state={.video_st=&stream,.viddec={3},.vidclk={3},.videoq={10}};
    FFPlayer player={.is=&state,.framedrop=5};
    for(int n=0;n<20;++n) {
        AVFrame frame={.pts=1000,.interlaced_frame=1};
        assert(get_video_frame(&player,&frame)==1 && !frame.released);
    }
    assert(!state.frame_drops_early && !player.stat.drop_frame_count);
    AVFrame progressive={.pts=1000};
    assert(get_video_frame(&player,&progressive)==0 && progressive.released);
    assert(state.frame_drops_early==1 && player.stat.drop_frame_count==1);
    player.framedrop=0;
    progressive=(AVFrame){.pts=1000};
    assert(get_video_frame(&player,&progressive)==1 && !progressive.released);
    puts("PASS: 20 late interlaced references retained; progressive dropping and disabled-dropping preserved");
    return 0;
}
'''
(out / 'early.c').write_text(harness.replace('FUNCTION', function))
(out / 'Application.mk').write_text('APP_ABI := arm64-v8a\nAPP_PLATFORM := android-21\nNDK_TOOLCHAIN_VERSION := 4.9\n')
(out / 'Android.mk').write_text('LOCAL_PATH := $(call my-dir)\ninclude $(CLEAR_VARS)\nLOCAL_MODULE := early-test\nLOCAL_SRC_FILES := early.c\nLOCAL_CFLAGS := -std=c99 -UNDEBUG\ninclude $(BUILD_EXECUTABLE)\n')
subprocess.run([str(a.ndk / 'ndk-build.cmd'), 'NDK_PROJECT_PATH=.',
                'APP_BUILD_SCRIPT=' + str(out / 'Android.mk'),
                'NDK_APPLICATION_MK=' + str(out / 'Application.mk'),
                'NDK_OUT=' + str(out / 'obj'), 'NDK_LIBS_OUT=' + str(out / 'libs')], cwd=repo, check=True)
remote = '/data/local/tmp/ntv-interlaced-early-test'
subprocess.run(['adb','-s',a.serial,'push',str(out / 'libs/arm64-v8a/early-test'),remote],check=True)
subprocess.run(['adb','-s',a.serial,'shell','chmod','755',remote],check=True)
subprocess.run(['adb','-s',a.serial,'shell',remote],check=True)
