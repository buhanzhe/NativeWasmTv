"""Generate the actual media used by HealthBalanceInstrumentation.

python tests/fixtures/health-balance.py --ffmpeg /path/to/ffmpeg
Build androidTest with -PcjsV5Assets=<printed directory> and
-PapplicationIdOverride=xiao.bu.tv.healthbalance
-PtestRunner=xiao.bu.tv.HealthBalanceInstrumentation.
Modes: default (classification/audio), neighbors, public, firstFrames.
Network mode also needs a TCP-only RTSP fixture at device localhost:18554;
its paired USB bridge listens on device localhost:18558. RTP/UDP are local.
"""
import argparse
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ffmpeg', default='ffmpeg')
parser.add_argument('--output', type=Path, default=Path('.codex-tmp/health-balance/assets'))
args = parser.parse_args()
out = args.output.resolve()
(out / 'mse').mkdir(parents=True, exist_ok=True)

def ffmpeg(*flags):
    subprocess.run([args.ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', *map(str, flags)], check=True)

ffmpeg('-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25:duration=12',
       '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100:duration=12',
       '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-g', 50,
       '-keyint_min', 50, '-sc_threshold', 0, '-af', 'volume=3', '-c:a', 'aac', '-b:a', '64k',
       '-f', 'hls', '-hls_time', 2, '-hls_list_size', 0,
       '-hls_segment_filename', out / 'mse/seg%d.ts', out / 'mse/live.m3u8')
ffmpeg('-i', out / 'mse/live.m3u8', '-c', 'copy', '-movflags', '+faststart', out / 'fixture.mp4')
ffmpeg('-i', out / 'fixture.mp4', '-vn', '-c:a', 'libmp3lame', '-b:a', '64k', out / 'fixture.mp3')
ffmpeg('-i', out / 'fixture.mp4', '-c', 'copy', '-f', 'flv', out / 'fixture.flv')
for name, expression in [('loud', '.75*sin(2*PI*440*t)'), ('quiet', '.02*sin(2*PI*440*t)'),
                         ('delayed', r'if(lt(t\,4)\,0\,.75*sin(2*PI*440*t))'), ('silent', '0')]:
    ffmpeg('-f', 'lavfi', '-i', f'aevalsrc={expression}:s=22050:d=15',
           '-c:a', 'libmp3lame', '-b:a', '64k', out / f'{name}.mp3')
print(out)
