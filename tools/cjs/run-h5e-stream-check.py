"""Run real encrypted H5E fixtures in an isolated Android process with the trial library."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[2]

def method(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--vm', default='dalvikvm', choices=['dalvikvm', 'dalvikvm32', 'dalvikvm64'])
    parser.add_argument('fixtures', type=Path, nargs='+')
    args = parser.parse_args()
    def run(command):
        subprocess.run([str(x) for x in command], check=True)
    def adb(*command):
        run(['adb', '-s', args.device, *command])
    remote = '/data/local/tmp/ntv-h5estream-check'
    with tempfile.TemporaryDirectory(prefix='h5e-check-') as temp:
        out = Path(temp)
        classes, dex = out/'classes', out/'dex'
        classes.mkdir(); dex.mkdir()
        bridge = out/'NativeH5eDecryptor.java'
        bridge.write_text('''package com.bu.cc.tv; public final class NativeH5eDecryptor {
public static native byte[] decryptTransportStream(byte[] b);
public static native byte[] decryptTransportStreamPart(byte[] b, boolean first);
public static native boolean supportsStreamingTransportStream();
public static native void cancelPendingDecrypts();
public static native void releaseThreadContext(); }''', encoding='utf-8')
        source = (root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf-8')
        signatures = ['    private static int findVideoPid(byte[] ts)',
                      '    static int findAvcVideoPid(byte[] ts)',
                      '    private static int findVideoPid(byte[] ts, boolean avcOnly)',
                      '    static int payloadOffset(byte[] ts, int packetOffset)',
                      '    private static byte[] psiSection(']
        helper = out/'H5eFixturePid.java'
        helper.write_text('package xiao.bu.tv; final class H5eFixturePid {\n' +
                          '\n'.join(method(source, s) for s in signatures) + '\n}', encoding='utf-8')
        run(['javac', '--release', '8', '-encoding', 'UTF-8', '-d', classes,
             bridge, helper, root/'tools/cjs/H5eStreamCheck.java',
             root/'app/src/main/java/xiao/bu/tv/CmgTsStream.java'])
        run(['java', '-cp', args.sdk/'build-tools/34.0.0/lib/d8.jar',
             'com.android.tools.r8.D8', '--min-api', '14', '--lib',
             args.sdk/'platforms/android-34/android.jar', '--output', dex, *classes.rglob('*.class')])
        jar = out/'check.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            for file in dex.glob('*.dex'): archive.write(file, file.name)
        adb('shell', 'mkdir', '-p', remote+'/dalvik-cache')
        adb('push', args.library, remote+'/libh5estream.so')
        adb('push', jar, remote+'/check.jar')
        inputs = []
        for index, fixture in enumerate(args.fixtures):
            target = remote+'/fixture-%d.ts' % index
            adb('push', fixture, target); inputs.append(target)
        result = subprocess.run(['adb', '-s', args.device, 'shell', 'ANDROID_DATA='+remote,
                                 args.vm, '-cp', remote+'/check.jar', 'xiao.bu.tv.H5eStreamCheck',
                                 remote+'/libh5estream.so', *inputs], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, check=True)
        text = result.stdout.decode('utf-8', 'replace')
        print(text)
        assert 'PASS H5E exact plaintext' in text, 'Native decryption checks failed'
