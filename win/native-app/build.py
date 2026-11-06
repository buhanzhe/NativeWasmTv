"""Build a single native Win64 EXE. No Java or runtime extraction.

Requires MSYS2 UCRT64 GCC, a minimal static FFmpeg build and the checked-out
browser-core. FFmpeg configuration is recorded in out/native/ffmpeg-build.
"""
import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
from assets import pack, key_header

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
CORE=ROOT/'browser-core'
OUT=HERE.parent/'out/native'

def run(command):
    subprocess.run(list(map(str,command)),check=True)

def build():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg',type=Path,default=OUT/'ffmpeg-build')
    parser.add_argument('--cc',default='gcc')
    parser.add_argument('--cxx',default='g++')
    parser.add_argument('--windres',default='windres')
    parser.add_argument('--release', action='store_true', help='Packed assets and no diagnostic entry points')
    parser.add_argument('--diagnostics', action='store_true', help='Keep developer checks in a packed validation build')
    a=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    spec=importlib.util.spec_from_file_location('windows_core_build',CORE/'litebrowser/windows/build.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    (OUT/'bootstrap.js').write_bytes(module.bootstrap())
    licenses='NativeWasmTv: GPL-3.0-only\nFFmpeg 8.0.1: LGPL-2.1-or-later\nFFmpeg source: https://ffmpeg.org/releases/ffmpeg-8.0.1.tar.xz\nJSON for Modern C++ 3.12.0: MIT (copyright Niels Lohmann)\n'
    for source in [ROOT/'LICENSE',CORE/'native/quickjs/vendor/LICENSE',CORE/'native/litebrowser/vendor/LICENSE',CORE/'native/litebrowser/wasm3/LICENSE',CORE/'litebrowser/desktop/test/assets/hls-1.5.18.LICENSE',a.ffmpeg.parent/'ffmpeg-8.0.1/COPYING.LGPLv2.1']:
        licenses+='\nComponent notice\n'+source.read_text(encoding='utf-8')
    (OUT/'licenses.txt').write_text(licenses,encoding='utf-8')
    info={'version':'0.2.1-native-preview','application_commit':subprocess.check_output(['git','-C',ROOT,'rev-parse','HEAD'],text=True).strip(),'core_commit':subprocess.check_output(['git','-C',CORE,'rev-parse','HEAD'],text=True).strip(),'ffmpeg':'8.0.1','java':False,'self_extracting':False,'playlist_sha256':hashlib.sha256((HERE.parent/'playlists/webview.txt').read_bytes()).hexdigest()}
    (OUT/'build-info.json').write_text(json.dumps(info,indent=2))
    manifest='''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0"><assemblyIdentity version="0.2.0.0" processorArchitecture="amd64" name="NativeWasmTv" type="win32"/><description>NativeWasmTv portable</description><trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges><requestedExecutionLevel level="asInvoker" uiAccess="false"/></requestedPrivileges></security></trustInfo><application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings><dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/pm</dpiAware><dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2,PerMonitor</dpiAwareness></windowsSettings></application></assembly>'''
    (OUT/'app.manifest').write_text(manifest)
    (OUT/'public-info.json').write_text(json.dumps({'version':'0.2.1','architecture':'x64'}))
    resources={101:OUT/'bootstrap.js',102:CORE/'litebrowser/desktop/test/assets/hls-1.5.18.min.js',103:HERE.parent/'playlists/webview.txt',104:OUT/'licenses.txt',105:OUT/'public-info.json'}
    if a.release:
        key_path=OUT/'asset-key.bin'
        if not key_path.exists():key_path.write_bytes(os.urandom(32))
        key=key_path.read_bytes()
        header=OUT/'asset_key.hpp'
        if not header.exists():header.write_text(key_header(key))
        for identifier in (101,102,103,105):
            destination=OUT/f'asset-{identifier}.dat'
            destination.write_bytes(pack(resources[identifier].read_bytes(),key))
            resources[identifier]=destination
    (OUT/'app.rc').write_text('1 24 "'+(OUT/'app.manifest').as_posix()+'"\n'+''.join(f'{key} RCDATA "{path.as_posix()}"\n' for key,path in resources.items()))
    native=CORE/'native/litebrowser'
    if a.release:(OUT/'protected_bridge.inc').write_text((native/'bridge.cpp').read_text(encoding='utf-8').replace('\"browser.js\"','\"r\"'),encoding='utf-8')
    includes=[HERE,native,native/'vendor/include',native/'vendor/include/litehtml',native/'vendor/src',native/'vendor/src/gumbo/src',native/'wasm3/source',a.ffmpeg]
    common=['-Os','-DNDEBUG','-fno-ident',f'-ffile-prefix-map={ROOT}=.',f'-fmacro-prefix-map={ROOT}=.',f'-DNTV_DIAGNOSTICS={int(not a.release or a.diagnostics)}',f'-I{OUT}',*(['-DNTV_PACKED_ASSETS'] if a.release else []),'-fvisibility=hidden','-ffunction-sections','-fdata-sections','-iquote',str(CORE/'native/quickjs/vendor'),'-idirafter',str(a.ffmpeg.parent/'ffmpeg-8.0.1'),*[f'-I{p}' for p in includes]]
    quick=['-O2','-std=gnu11','-D_GNU_SOURCE','-DNTV_BROWSER_PARALLEL_RUNTIMES','-include',CORE/'litebrowser/windows/windows-compat.h']
    quick+=['-D'+x for x in ('NTV_SINGLE_THREADED','NTV_BROWSER_SORT_CALLBACKS','NTV_BROWSER_BIGINT_UINTN','NTV_BROWSER_NATIVE_FUNCTION_SOURCE','NTV_BROWSER_NESTED_PLUS_OPT','NTV_BROWSER_PAGE_CLOCK','NTV_BROWSER_BOUNDED_GC','NTV_BROWSER_MODULE_DOWNLOADS')]
    quick+=['-DCONFIG_VERSION="2026-06-04"']
    specs=[(CORE/'native/quickjs/vendor'/name,a.cc,quick) for name in ('quickjs.c','dtoa.c','libregexp.c','libunicode.c','cutils.c')]
    specs += [(p,a.cc,['-std=c99']) for p in sorted((native/'vendor/src/gumbo/src').glob('*.c'))]
    wasm='m3_bind m3_code m3_compile m3_core m3_deterministic m3_env m3_exec m3_function m3_info m3_module m3_parse m3_snapshot m3_validate m3_xxh64'.split()
    specs += [(native/'wasm3/source'/(x+'.c'),a.cc,['-O2','-std=c99','-D_GNU_SOURCE','-Dd_m3GuardedMemory=0','-Dd_m3MaxNativeStack=262144','-Dd_m3ValStack=2048','-Dd_m3ValCtrlDepth=512']) for x in wasm]
    specs += [(p,a.cxx,['-std=c++17']) for p in [HERE/'core.cpp',HERE/'page.cpp',HERE/'media.cpp',HERE/'main.cpp',HERE/'system.cpp',HERE/'worker.cpp',HERE/'display.cpp',HERE/'resources.cpp',native/'wasm-runtime.cpp',*sorted((native/'vendor/src').glob('*.cpp'))]]
    header_paths=sorted(p for base in (HERE,native,CORE/'native/quickjs') for p in base.rglob('*') if p.is_file() and p.suffix in ('.h','.hpp'))
    header_hash=hashlib.sha256(b''.join(p.read_bytes() for p in header_paths)+(native/'bridge.cpp').read_bytes()).digest()
    objects=[];pending=[]
    for src,compiler,flags in specs:
        obj=OUT/(hashlib.sha256(str(src).encode()).hexdigest()[:20]+'.o');stamp=obj.with_suffix('.sha256');digest=hashlib.sha256(header_hash+src.read_bytes()+repr(common+flags).encode()).hexdigest();objects.append(obj)
        if not obj.exists() or not stamp.exists() or stamp.read_text()!=digest:pending.append(([compiler,*common,*flags,'-c',src,'-o',obj],stamp,digest))
    print(f'Compile {len(pending)}/{len(specs)} native units',flush=True)
    def compile_one(job):
        command,stamp,digest=job;run(command);stamp.write_text(digest)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:list(executor.map(compile_one,pending))
    run([a.windres,'-i',OUT/'app.rc','-o',OUT/'resources.o'])
    libraries=[a.ffmpeg/d/(d+'.a') for d in ('libavformat','libavcodec','libswscale','libswresample','libavutil')]
    output=OUT/('NativeWasmTv.exe' if not a.release else 'NativeWasmTv-check.exe' if a.diagnostics else 'NativeWasmTv-release.exe')
    run([a.cxx,'-municode','-mwindows','-static','-s','-Wl,--gc-sections','-Wl,--no-insert-timestamp','-o',output,*objects,OUT/'resources.o','-Wl,--start-group',*libraries,'-Wl,--end-group','-lcabinet','-ld3d11','-ldxgi','-ldxguid','-lwininet','-lgdiplus','-lwinmm','-lgdi32','-lcomdlg32','-lcomctl32','-lshell32','-lole32','-loleaut32','-luuid','-lws2_32','-lsecur32','-lbcrypt','-lncrypt','-lcrypt32','-lm','-lpthread'])
    if a.release:
        data=bytearray(output.read_bytes())
        # __FILE__ strings in linked vendor assertions are diagnostic-only.
        # Scrub local build paths without changing section offsets or code.
        expression=re.escape(ROOT.as_posix().encode())+rb'[/\\][^\x00]{1,1024}'
        for match in re.finditer(expression,data):
            data[match.start():match.end()]=b'r'+b'_'*(match.end()-match.start()-1)
        output.write_bytes(data)
    size=output.stat().st_size
    if size>10_000_000:raise RuntimeError(f'EXE exceeds 10 MB: {size} bytes')
    result={**info,'protected_assets':a.release,'diagnostics':not a.release or a.diagnostics,'source_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in HERE.iterdir() if p.is_file()},'exe':str(output),'bytes':size,'sha256':hashlib.sha256(output.read_bytes()).hexdigest()}
    (OUT/'artifact.json').write_text(json.dumps(result,indent=2))
    print(json.dumps({k:result[k] for k in ('version','exe','bytes','sha256','protected_assets','diagnostics')}),flush=True)

if __name__=='__main__':build()
