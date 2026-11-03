"""Run the production H5E TS writer's exhaustive boundary checks on Android."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--ndk', type=Path, required=True)
parser.add_argument('--adb', default='adb')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]

def run(command):
    subprocess.run([str(x) for x in command], check=True, cwd=root)

with tempfile.TemporaryDirectory(prefix='h5e-pes-') as temp:
    out = Path(temp)
    (out/'Android.mk').write_text('LOCAL_PATH := '+(root/'tools/cjs').as_posix()+'''
include $(CLEAR_VARS)
LOCAL_MODULE := h5e-pes-test
LOCAL_SRC_FILES := test-h5e-pes.c
LOCAL_CFLAGS := -std=c99 -O2 -UNDEBUG
include $(BUILD_EXECUTABLE)
''', encoding='utf-8')
    (out/'Application.mk').write_text('APP_ABI := armeabi-v7a\nAPP_PLATFORM := android-14\n', encoding='utf-8')
    run([args.ndk/'ndk-build.cmd', 'NDK_PROJECT_PATH=.', f'APP_BUILD_SCRIPT={out}/Android.mk',
         f'NDK_APPLICATION_MK={out}/Application.mk', f'NDK_OUT={out}/obj', f'NDK_LIBS_OUT={out}/libs'])
    remote = '/data/local/tmp/ntv-h5e-pes-test'
    run([args.adb, '-s', args.device, 'push', out/'libs/armeabi-v7a/h5e-pes-test', remote])
    run([args.adb, '-s', args.device, 'shell', 'chmod', '755', remote])
    result = subprocess.run([args.adb, '-s', args.device, 'shell', remote], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=True)
    text = result.stdout.decode('utf-8', 'replace')
    print(text)
    assert 'PASS all payload lengths' in text, 'Native writer checks failed'
