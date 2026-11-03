"""Compare the real software wrapper on Android against FFmpeg BWDIF pixels.

Requires the existing IJK source/headers, NDK r17c, ffmpeg and an adb device.
Temporary fixtures and native test executables stay outside the release APK.
"""
import argparse
import array
import json
import pathlib
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--abi', choices=['arm64-v8a', 'armeabi-v7a'], required=True)
parser.add_argument('--ndk', required=True, type=pathlib.Path)
parser.add_argument('--output', required=True, type=pathlib.Path)
parser.add_argument('--scroll', action='store_true',
                    help='Also compare full-HD vertically scrolling text in both field orders')
parser.add_argument('--lifecycle', action='store_true', help='Also test mixed flags, fractional/unknown PTS, size changes and gaps')
args = parser.parse_args()
repo = pathlib.Path(__file__).resolve().parents[2]
root = args.output.resolve()
root.mkdir(parents=True, exist_ok=True)

def run(command):
    result = subprocess.run(list(map(str, command)), cwd=repo, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout

def adb(*command):
    return run(['adb', '-s', args.serial, *command])

make = root / 'Application.mk'
make.write_text('APP_ABI := ' + args.abi + '\nAPP_PLATFORM := android-'
                + ('21' if args.abi == 'arm64-v8a' else '14')
                + '\nNDK_TOOLCHAIN_VERSION := 4.9\nAPP_MODULES := deinterlace-test\n')
build = run([args.ndk / 'ndk-build.cmd', 'NDK_PROJECT_PATH=.',
             'APP_BUILD_SCRIPT=tools/ijk/DeinterlaceTest.mk', 'NDK_APPLICATION_MK=' + str(make),
             'NDK_OUT=' + str(root / 'obj'), 'NDK_LIBS_OUT=' + str(root / 'libs'), '-j4'])
(root / 'build.log').write_text(build)
remote = '/data/local/tmp/ntv-deinterlace-test-' + args.abi
adb('shell', 'mkdir -p ' + remote)
adb('push', root / 'libs' / args.abi / 'deinterlace-test', remote + '/')
adb('push', repo / 'app/src/main/libs' / args.abi / 'libijkffmpeg.so', remote + '/')
adb('shell', 'chmod 755 ' + remote + '/deinterlace-test')
if args.lifecycle:
    result=adb('shell', f'cd {remote} && LD_LIBRARY_PATH=. ./deinterlace-test --lifecycle')
    (root/'lifecycle.txt').write_text(result)
    print(result,flush=True)
results = []
cases = [('8-tff',64,32,8,'tff'), ('8-bff',64,32,8,'bff'),
         ('10-tff',64,32,10,'tff'), ('10-bff',64,32,10,'bff'),
         ('8-threaded',96,576,8,'tff'), ('10-threaded',96,576,10,'bff'),
         ('9-tff',66,34,9,'tff'), ('12-bff',66,34,12,'bff'),
         ('14-tff',64,18,14,'tff'), ('16-threaded',96,576,16,'bff'),
         ('8-progressive',64,32,8,'prog')]
if args.scroll:
    cases += [('8-scroll-tff',1920,1080,8,'tff'), ('8-scroll-bff',1920,1080,8,'bff')]
for name, width, height, depth, order in cases:
    fmt = 'yuv420p' if depth == 8 else f'yuv420p{depth}le'
    source, reference, actual = [root / (kind + name + '.yuv') for kind in ['input','reference','output']]
    progressive = order == 'prog'
    vf = 'format=' + fmt
    if 'scroll' in name:
        # Two pixels per field exposes reversed/skipped fields as vertical jumps.
        vf += ",drawtext=text='1080i SCROLL 0123456789':fontsize=52:fontcolor=white:x=180:y='800-100*t'"
    if not progressive:
        vf += ',tinterlace=mode=' + ('interleave_top' if order == 'tff' else 'interleave_bottom')
    run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
         f'testsrc2=size={width}x{height}:rate={25 if progressive else 50}:duration=0.4',
         '-vf', vf, '-frames:v', '10', '-pix_fmt', fmt, '-f', 'rawvideo', source])
    if progressive:
        reference.write_bytes(source.read_bytes())
    else:
        run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'rawvideo',
             '-pixel_format', fmt, '-video_size', f'{width}x{height}', '-framerate', '25', '-i', source,
             '-vf', f'setfield={order},bwdif=mode=send_field:parity=auto:deint=interlaced',
             '-pix_fmt', fmt, '-f', 'rawvideo', reference])
    adb('push', source, remote + '/')
    native = adb('shell', f'cd {remote} && LD_LIBRARY_PATH=. ./deinterlace-test {source.name} '
                 f'{width} {height} {depth} {int(order == "tff")} {int(progressive)} '
                 f'{actual.name} {10 if progressive else 20}')
    adb('pull', remote + '/' + actual.name, actual)
    a = array.array('B' if depth == 8 else 'H', actual.read_bytes())
    b = array.array('B' if depth == 8 else 'H', reference.read_bytes())
    if len(a) != len(b) or not a:
        raise AssertionError(name + ': output length differs')
    row = dict(case=name, samples=len(a), differences=sum(x != y for x,y in zip(a,b)),
               maxError=max(abs(x-y) for x,y in zip(a,b)), native=native.strip())
    results.append(row)
    (root / 'pixels.json').write_text(json.dumps(results,indent=2),encoding='utf8')
    print(row,flush=True)
    if row['differences']:
        raise AssertionError(name + ': pixels differ from FFmpeg BWDIF')
print('PASS all pixels match; timestamps, seek, EOF, colour and references checked')
