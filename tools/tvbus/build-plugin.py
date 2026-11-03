"""Build a separate Android TVBus JAR from explicitly supplied native engines.

python tools/tvbus/build-plugin.py --apk INPUT.apk --output OUTPUT.jar
Only libtvcore is extracted. No app identity hooks, account state or Pcdn runtime is copied.
"""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, tempfile, zipfile

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
source = parser.add_mutually_exclusive_group(required=True)
source.add_argument('--apk', type=Path)
source.add_argument('--engines-dir', type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--version', default='tvbus-compat-1')
parser.add_argument('--upstream-base', help='Optional update base containing ABI/libtvcore.so; omit for a local-only bundle')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME', 'D:/android/sdk'))
android = sdk / 'platforms/android-34/android.jar'
d8 = sdk / 'build-tools/35.0.0/lib/d8.jar'

def sha(data): return hashlib.sha256(data).hexdigest()

with tempfile.TemporaryDirectory() as temporary:
    temp = Path(temporary)
    classes, dex = temp / 'classes', temp / 'dex'
    classes.mkdir(); dex.mkdir()
    java = root / 'app/src/main/java/com/tvbus/engine'
    subprocess.run(['javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-classpath', str(android), '-d', str(classes),
                    *map(str, sorted(java.glob('*.java')))], check=True)
    subprocess.run(['java', '-cp', str(d8), 'com.android.tools.r8.D8', '--min-api', '14',
                    '--lib', str(android), '--output', str(dex),
                    *map(str, sorted(classes.rglob('*.class')))], check=True)
    code = (dex / 'classes.dex').read_bytes()
    engines = {}
    for abi in ('armeabi-v7a', 'arm64-v8a'):
        if args.apk:
            with zipfile.ZipFile(args.apk) as apk: data = apk.read(f'lib/{abi}/libtvcore.so')
        else: data = (args.engines_dir / abi / 'libtvcore.so').read_bytes()
        expected_machine = 40 if abi == 'armeabi-v7a' else 183
        if data[:4] != b'\x7fELF' or int.from_bytes(data[18:20], 'little') != expected_machine:
            raise ValueError(f'Invalid engine for {abi}')
        engines[abi] = data
    metadata = {'type': 'tvbus', 'api': 1, 'version': args.version,
                'dexSha256': sha(code), 'engines': {abi: sha(data) for abi, data in engines.items()},
                'defaults': {'waitForInit': True, 'preferHttp': True, 'dynamicPort': True,
                             'domain': '.tvlive.app'}}
    if args.upstream_base:
        if not args.upstream_base.startswith(('https://', 'http://')):
            raise ValueError('Update base must be HTTP/HTTPS')
        metadata['engineUrls'] = {abi: args.upstream_base.rstrip('/') + f'/{abi}/libtvcore.so' for abi in engines}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as jar:
        jar.writestr('classes.dex', code)
        jar.writestr('tvbus-plugin.json', json.dumps(metadata, ensure_ascii=False, indent=2))
        for abi, data in engines.items(): jar.writestr(f'native/{abi}/libtvcore.so', data)
    print(json.dumps({'output': str(args.output.resolve()), 'bytes': args.output.stat().st_size,
                      'sha256': sha(args.output.read_bytes())}, ensure_ascii=False))
