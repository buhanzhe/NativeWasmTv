"""Check the final APK, after R8, for the ABI used by downloadable native channels."""
import argparse, pathlib, struct, zipfile

p = argparse.ArgumentParser()
p.add_argument('apk', type=pathlib.Path)
a = p.parse_args()
defined, methods = set(), set()
with zipfile.ZipFile(a.apk) as archive:
    for name in archive.namelist():
        if not (name.startswith('classes') and name.endswith('.dex')):
            continue
        data = archive.read(name)
        def word(at): return struct.unpack_from('<I', data, at)[0]
        def string(index):
            at = word(word(60) + 4 * index)
            while data[at] & 128: at += 1
            at += 1
            return data[at:data.index(b'\0', at)].decode('utf-8', errors='replace')
        types = [string(word(word(68) + 4 * i)) for i in range(word(64))]
        protos = []
        for i in range(word(72)):
            at = word(76) + 12 * i
            params = word(at + 8)
            arguments = [] if not params else [types[struct.unpack_from('<H', data, params + 4 + 2*j)[0]] for j in range(word(params))]
            protos.append('(' + ''.join(arguments) + ')' + types[word(at + 4)])
        for i in range(word(88)):
            owner, proto, method = struct.unpack_from('<HHI', data, word(92) + 8*i)
            methods.add((types[owner], string(method), protos[proto]))
        defined.update(types[word(word(100) + 32*i)] for i in range(word(96)))

loader = 'Ltv/danmaku/ijk/media/player/IjkLibLoader;'
native = 'Lxiao/bu/tv/jar/ChannelPlugin$NativeHost;'
playback = 'Lxiao/bu/tv/jar/ChannelPlugin$PlaybackHost;'
assert loader in defined, 'R8 renamed/removed IjkLibLoader, breaking the downloaded JAR method descriptor'
assert native in defined, 'Missing NativeHost contract'
assert ('Ltv/danmaku/ijk/media/player/IjkMediaPlayer;', 'loadLibrariesOnce', '(' + loader + ')V') in methods, 'Missing stable IJK library-loader method'
assert (native, 'httpClient', '()Ljava/lang/Object;') in methods, 'Missing shared OkHttp entry point'
assert playback in defined, 'R8 renamed/removed the optional playback diagnostics interface'
assert (playback, 'reportPlaybackDiagnostics', '(Ljava/lang/Object;Landroid/os/Bundle;)V') in methods, 'Missing playback diagnostics entry point'
assert (playback, 'isPlaybackDebugEnabled', '()Z') in methods and (playback, 'setPlaybackDebugEnabled', '(Z)V') in methods
assert not any(c.startswith('Lcom/google/android/exoplayer2/') for c in defined), 'Exo must remain in the JAR'
assert 'Lxiao/bu/tv/BlPluginInstrumentation;' not in defined, 'Temporary device runner remains in production APK'
print('PASS: final APK retains NativeHost/IJK loader ABI; no bundled Exo or temporary BL runner')
