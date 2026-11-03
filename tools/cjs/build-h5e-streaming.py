"""Build H5E streaming libraries from a local CJS checkout (no publishing)."""
import argparse
import importlib.util
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[2]

def patched(name, source):
    spec = importlib.util.spec_from_file_location('h5e_patch', Path(__file__).with_name(name))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.patch(source)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkout', type=Path)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--bundle', action='store_true', help='Write all three release ABI libraries into app/src/h5eStreaming/jniLibs')
    args = parser.parse_args()
    source = args.checkout/'sites/tv.cctv.com/native'
    trial = root/'.codex-tmp/h5e-streaming'
    native = trial/'native'
    native.mkdir(parents=True, exist_ok=True)
    for name in ['generated', 'wasm-rt']:
        shutil.copytree(source/name, native/name, dirs_exist_ok=True)
    text = (source/'cctv_h5e_decryptor.c').read_text(encoding='utf-8')
    text = patched('apply-cctv-workspace.py', text)
    text = patched('apply-h5e-pes.py', text)
    text = patched('apply-h5e-streaming.py', text)
    (native/'cctv_h5e_decryptor.c').write_text(text, encoding='utf-8', newline='\n')
    (native/'Android.mk').write_text('''LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := h5estream
LOCAL_SRC_FILES := cctv_h5e_decryptor.c generated/cctv_h5e_wasm.c wasm-rt/wasm-rt-impl.c wasm-rt/wasm-rt-mem-impl.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/generated $(LOCAL_PATH)/wasm-rt
LOCAL_CFLAGS := -O3 -DNDEBUG -std=c99 -DWASM_RT_USE_MMAP=0 -fvisibility=hidden
LOCAL_ARM_MODE := arm
LOCAL_LDLIBS := -llog -lm
include $(BUILD_SHARED_LIBRARY)
''', encoding='utf-8')
    (native/'Application.mk').write_text('APP_OPTIM := release\n', encoding='utf-8')
    ndk = args.ndk/('ndk-build.cmd' if (args.ndk/'ndk-build.cmd').exists() else 'ndk-build')
    for abi, platform in [('armeabi-v7a', 'android-14'), ('arm64-v8a', 'android-21'), ('x86', 'android-14')]:
        subprocess.run([str(ndk), 'NDK_PROJECT_PATH=.', f'APP_BUILD_SCRIPT={native}/Android.mk',
                        f'NDK_APPLICATION_MK={native}/Application.mk', f'APP_ABI={abi}',
                        f'APP_PLATFORM={platform}', f'NDK_OUT={trial}/obj-{abi}',
                        f'NDK_LIBS_OUT={trial}/libs-{abi}', '-j4'], cwd=root, check=True)
        target = trial/'apk-libs'/abi
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(trial/f'libs-{abi}'/abi/'libh5estream.so', target/'libh5estream.so')
        if args.bundle:
            bundled = root/'app/src/h5eStreaming/jniLibs'/abi
            bundled.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target/'libh5estream.so', bundled/'libh5estream.so')
    print('Ready: scripts/build-release.ps1' if args.bundle else 'Ready: gradlew :app:assembleArm32Release :app:assembleArm64Release :app:assembleX86Release -Ph5eStreamingChecks')
