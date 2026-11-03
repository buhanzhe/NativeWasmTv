"""Run the production software overlay/renderer and picture-rate gate on an adb device."""
import argparse
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--abi', choices=['arm64-v8a', 'armeabi-v7a'], required=True)
parser.add_argument('--ndk', type=Path, required=True)
parser.add_argument('--libs', type=Path, required=True, help='Directory containing ABI subdirectories')
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--bench-only', action='store_true')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
source = (repo/'.codex-tmp/ijkplayer-0.8.8/ijkmedia/ijkplayer/ff_ffplay.c').read_text(encoding='utf8')
start = source.index('        /* nTv: use picture rate,')
end = source.index('        if (is->is_video_high_fps)', start)
policy = source[start:end]
(out/'ntv_test_fps.h').write_text('''#include "libavutil/rational.h"
typedef struct {int max_fps;} RatePlayer;
typedef struct {AVRational avg_frame_rate,r_frame_rate;} RateStream;
typedef struct {RateStream *video_st;struct {int decode_profiler;} viddec;int is_video_high_fps;} RateState;
#define SDL_ProfilerReset(p,v) ((void)0)
#define av_log(...) ((void)0)
static int rate_limit(int an,int ad,int rn,int rd,int cap,int old) {
 RatePlayer player={cap},*ffp=&player;RateStream stream={{an,ad},{rn,rd}};
 RateState state={&stream,{0},old},*is=&state;
''' + policy + '''return is->is_video_high_fps;}
#undef av_log
#undef SDL_ProfilerReset
static void test_picture_rate(void) {
 assert(!rate_limit(25,1,50,1,31,0));assert(!rate_limit(30000,1001,60000,1001,31,0));
 assert(rate_limit(50,1,50,1,31,0));assert(rate_limit(0,0,50,1,31,0));
 assert(!rate_limit(0,0,0,0,31,1));assert(!rate_limit(25,1,50,1,31,1));
 assert(!rate_limit(50,1,50,1,-1,1));assert(!rate_limit(150,1,150,1,31,0));
}
''', encoding='utf8')
(out/'Application.mk').write_text('APP_ABI := '+args.abi+'\nAPP_PLATFORM := android-'+
    ('21' if args.abi=='arm64-v8a' else '14')+'\nNDK_TOOLCHAIN_VERSION := 4.9\n', encoding='utf8')
def run(command):
    result = subprocess.run([str(x) for x in command], cwd=repo, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    text = result.stdout.decode('utf8', 'replace')
    if result.returncode:
        raise RuntimeError(text)
    return text
build = run([args.ndk/'ndk-build.cmd', 'NDK_PROJECT_PATH=.',
    'APP_BUILD_SCRIPT=tools/ijk/SoftwareRenderTest.mk', 'NDK_APPLICATION_MK='+(out/'Application.mk').as_posix(),
    'NTV_RENDER_LIBS='+args.libs.resolve().as_posix(), 'NTV_RENDER_TEST='+out.as_posix(),
    'NDK_OUT='+(out/'obj').as_posix(), 'NDK_LIBS_OUT='+(out/'libs').as_posix(), '-j4'])
(out/'build.log').write_text(build, encoding='utf8')
remote = '/data/local/tmp/ntv-software-render-'+args.abi
run(['adb', '-s', args.serial, 'shell', 'mkdir', '-p', remote])
for name in ['software-render-test', 'libijksdl.so', 'libijkffmpeg.so']:
    run(['adb', '-s', args.serial, 'push', out/'libs'/args.abi/name, remote+'/'+name])
result = run(['adb', '-s', args.serial, 'shell', 'cd '+remote+
    ' && chmod 755 software-render-test && LD_LIBRARY_PATH=. ./software-render-test'+
    (' --bench' if args.bench_only else '')])
(out/'device.txt').write_text(result, encoding='utf8')
print(result)
