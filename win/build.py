"""Package the Windows x64 MVP with a target JRE, native core and FFplay.

Build liteWebCore/litebrowser/windows first. This script never downloads a
player or JDK implicitly: supply the already verified upstream distributions.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


def run(cmd):
    subprocess.run([str(x) for x in cmd], check=True)


def revision(path):
    try:
        return subprocess.check_output(['git','-C',str(path),'rev-parse','HEAD'],stderr=subprocess.DEVNULL,text=True).strip()
    except (OSError,subprocess.CalledProcessError):
        return None


def pe_x64(path):
    with path.open('rb') as f:
        if f.read(2) != b'MZ':
            raise RuntimeError('Not a Windows PE binary: ' + str(path))
        f.seek(60)
        offset = int.from_bytes(f.read(4),'little')
        f.seek(offset)
        if f.read(6) != b'PE\0\0\x64\x86':
            raise RuntimeError('Not a Windows x64 binary: ' + str(path))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--target', choices=['windows','linux'], default='windows')
    p.add_argument('--core-output', type=Path, required=True)
    p.add_argument('--player', type=Path, required=True)
    p.add_argument('--jdk', type=Path, required=True, help='Target JDK 17 (Windows JDK for Windows packages)')
    p.add_argument('--launcher-cc', default=os.environ.get('CC','gcc'))
    p.add_argument('--output', type=Path, help='Build directory (defaults to win/out/<target>)')
    p.add_argument('--no-runtime', action='store_true', help='Development Linux smoke-test layout only')
    a = p.parse_args()
    if a.no_runtime and a.target != 'linux':
        p.error('--no-runtime is only for the Linux test layout')
    core = a.core_output.resolve()
    playlist = HERE/'playlists/webview.txt'
    playlist_info = json.loads((HERE/'playlists/webview.source.json').read_text(encoding='utf-8'))
    playlist_bytes=playlist.read_bytes()
    if hashlib.sha256(playlist_bytes).hexdigest()!=playlist_info['sha256']:
        # Git's Windows checkout may normalize LF to CRLF. Accept only the
        # exact pinned upstream bytes after reversing that normalization.
        playlist_bytes=playlist_bytes.replace(b'\r\n',b'\n')
    if hashlib.sha256(playlist_bytes).hexdigest() != playlist_info['sha256']:
        raise RuntimeError('Bundled playlist differs from its recorded source snapshot')
    native = 'ntvLiteDesktop.dll' if a.target=='windows' else 'libntvLiteDesktop.so'
    if a.target=='windows':
        pe_x64(core/native); pe_x64(a.player); pe_x64(a.jdk/'bin/java.exe')
    output = a.output.resolve() if a.output else HERE/'out'/a.target
    output.mkdir(parents=True,exist_ok=True)
    bundle = output/'NativeWasmTv-win-x64'
    if bundle.exists():
        shutil.rmtree(bundle)
    app = bundle/'app'
    app.mkdir(parents=True)
    shutil.copy2(core/native,app/native)
    shutil.copy2(core/'litecore.jar',app/'litecore.jar')
    shutil.copytree(core/'lib',app/'lib')
    player = bundle/'player'
    player.mkdir()
    shutil.copy2(a.player,player/('ffplay.exe' if a.target=='windows' else 'ffplay'))
    shutil.copytree(HERE/'playlists',bundle/'playlists')
    (bundle/'playlists/webview.txt').write_bytes(playlist_bytes)
    shutil.copy2(HERE/'README.md',bundle/'README.md')
    shutil.copy2(HERE/'VALIDATION.md',bundle/'VALIDATION.md')
    classes = output/'classes'
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir()
    cp = os.pathsep.join(str(x) for x in [core/'litecore.jar',*sorted((core/'lib').glob('*.jar'))])
    host_sources=[ROOT/'app/src/main/java/xiao/bu/tv'/(name+'.java') for name in ['WebSiteCompatibility','LiteApiClock','LiteLiveMediaPolicy']]
    run(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',classes,*sorted((HERE/'src').rglob('*.java')),*host_sources])
    shutil.copytree(ROOT/'app/src/main/assets/userscripts',classes/'userscripts')
    manifest = output/'MANIFEST.MF'
    manifest.write_text('Manifest-Version: 1.0\nMain-Class: xiao.bu.tv.windows.WindowsTv\nClass-Path: litecore.jar lib/json-20180813.jar lib/okhttp-3.12.13.jar\n  lib/okio-1.17.5.jar\n\n')
    run(['jar','--create','--file',app/'ntv-tv.jar','--manifest',manifest,'-C',classes,'.'])
    if not a.no_runtime:
        modules='java.desktop,java.management,java.xml,java.naming,jdk.management,jdk.crypto.ec,jdk.charsets,jdk.unsupported,jdk.httpserver'
        if a.target=='windows':modules+=',jdk.crypto.mscapi'
        run(['jlink','--module-path',a.jdk/'jmods','--add-modules',
             modules,
             '--strip-debug','--no-header-files','--no-man-pages','--compress=2','--output',bundle/'runtime'])
    if a.target=='windows':
        run([a.launcher_cc,'-O2','-s','-Wl,--no-insert-timestamp','-municode','-mwindows',HERE/'native/launcher.c','-o',bundle/'NativeWasmTv.exe'])
        pe_x64(bundle/'NativeWasmTv.exe')
        (bundle/'启动网页电视.cmd').write_bytes(b'@echo off\r\ncd /d "%~dp0"\r\nstart "" "NativeWasmTv.exe"\r\n')
    else:
        (bundle/'run.sh').write_text('#!/bin/sh\ncd "$(dirname "$0")"\nexec java -Xmx256m -Dntv.home="$PWD" -jar app/ntv-tv.jar "$@"\n')
        (bundle/'run.sh').chmod(0o755)
    licenses = bundle/'licenses'
    licenses.mkdir()
    shutil.copy2(ROOT/'LICENSE',licenses/'NativeWasmTv-GPL-3.0.txt')
    private = ROOT/'browser-core'
    for source,name in [('native/quickjs/vendor/LICENSE','QuickJS.txt'),('native/litebrowser/vendor/LICENSE','litehtml.txt'),('native/litebrowser/wasm3/LICENSE','wasm3.txt'),('litebrowser/desktop/test/assets/hls-1.5.18.LICENSE','hls.js.txt')]:
        shutil.copy2(private/source,licenses/name)
    (licenses/'README.txt').write_text('NativeWasmTv: GPL-3.0-only.\nFFplay/FFmpeg: GPL build; source/build information: https://github.com/BtbN/FFmpeg-Builds\nQuickJS, litehtml, wasm3, hls.js: see adjacent original notices.\nOkHttp and Okio: Apache-2.0; original notices retained inside app/lib JARs.\norg.json: https://github.com/stleary/JSON-java/tree/20180813\nTemurin runtime: GPL-2.0 with Classpath Exception; see runtime/legal.\nPrivate browser core remains a separately authorized dependency; see its source repository and third-party notices.\n',encoding='utf-8')
    core_info = json.loads((core/'build-info.json').read_text())
    files = {str(x.relative_to(bundle)).replace('\\','/'):hashlib.sha256(x.read_bytes()).hexdigest() for x in sorted(bundle.rglob('*')) if x.is_file()}
    (bundle/'build-info.json').write_text(json.dumps({'project':'buhanzhe/NativeWasmTv','branch':'win','target':a.target,'architecture':'x86_64','version':'0.1.1','application_commit':revision(ROOT),'core_commit':revision(private),'built_in_playlist':playlist_info,'core':core_info,'files':files},indent=2)+'\n')
    if a.target=='windows':
        archive = output/'NativeWasmTv-win-x64-v0.1.1.zip'
        with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
            for file in sorted(bundle.rglob('*')):
                if file.is_file(): z.write(file,str(file.relative_to(output)))
        print(json.dumps({'bundle':str(bundle),'archive':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()}))
    else: print(json.dumps({'bundle':str(bundle)}))


if __name__=='__main__': main()
