"""Build a pure DEX + assets channel, checking source against Android 4.0's API jar."""
import argparse,hashlib,json,pathlib,subprocess,zipfile,os,shutil,urllib.request,re
here=pathlib.Path(__file__).resolve().parent
root=here.parents[1]
p=argparse.ArgumentParser()
p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME','D:/android/sdk'))
p.add_argument('--java-home',default=os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/corretto-1.8.0_492'))
p.add_argument('--android14',help='Optional local API-14 stub (otherwise downloaded and hash checked)')
args=p.parse_args()
sdk=pathlib.Path(args.sdk);java=pathlib.Path(args.java_home)/'bin'
out=root/'.codex-tmp/bl/build';out.mkdir(parents=True,exist_ok=True)
classes=out/'classes'
assert classes.resolve().is_relative_to((root/'.codex-tmp/bl').resolve())
if classes.exists():shutil.rmtree(classes)
classes.mkdir()
lock=json.loads((here/'dependencies.json').read_text(encoding='utf-8'))
def dependency(name):
    entry=lock[name];target=out/name
    if not target.exists():
        filename=entry.get('maven','').split('/')[-1]
        cached=list((pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1').rglob(filename)) if filename else []
        if 'archiveSha256' in entry:
            with urllib.request.urlopen(entry['url'],timeout=60) as r:archive=r.read()
            if hashlib.sha256(archive).hexdigest()!=entry['archiveSha256']:raise SystemExit('SDK archive hash mismatch')
            import io
            with zipfile.ZipFile(io.BytesIO(archive)) as z:target.write_bytes(z.read(entry['entry']))
        elif cached:target.write_bytes(cached[0].read_bytes())
        else:
            base='https://maven.aliyun.com/repository/jcenter/' if name=='ijkplayer-java.aar' else 'https://repo.maven.apache.org/maven2/'
            with urllib.request.urlopen(entry.get('url') or base+entry['maven'],timeout=60) as r:target.write_bytes(r.read())
    if hashlib.sha256(target.read_bytes()).hexdigest()!=entry['sha256']:raise SystemExit('Dependency hash mismatch: '+name)
    return target
okhttp=dependency('okhttp.jar');okio=dependency('okio.jar');aar=dependency('ijkplayer-java.aar')
zxing_source=dependency('zxing-sources.jar');source=out/'zxing-source'
with zipfile.ZipFile(zxing_source) as z:z.extractall(source)
exe='.exe' if os.name=='nt' else ''
javac=str(java/('javac'+exe));compiler=subprocess.run([javac,'-version'],check=True,capture_output=True,text=True)
version=re.search(r'javac (\d+)(?:\.(\d+))?',compiler.stdout+compiler.stderr)
if not version:raise SystemExit('Could not identify javac version')
major=int(version.group(2)) if version.group(1)=='1' else int(version.group(1))
language=['--release','8'] if major>=9 else ['-source','8','-target','8']
android=pathlib.Path(args.android14) if args.android14 else dependency('android14.jar')
if not android.exists():raise SystemExit('Provide --android14 with the Android 4.0 API stub jar')
# Newer JDKs need --release: -source/-target alone allow Java 9 Buffer APIs into old Android DEX.
# javac compiles only QRCodeWriter's source dependency closure: no scanners/decoder UI.
subprocess.run([javac,'-encoding','UTF-8','-g:none',*language,'-sourcepath',str(source),'-d',str(classes),str(source/'com/google/zxing/qrcode/QRCodeWriter.java')],check=True)
ijk=out/'ijk-api.jar'
with zipfile.ZipFile(aar) as z:ijk.write_bytes(z.read('classes.jar'))
exo=out/'exoplayer.jar'
with zipfile.ZipFile(dependency('exoplayer-core-2.10.8.aar')) as z:exo.write_bytes(z.read('classes.jar'))
flac=dependency('jflac-codec-1.5.2.jar')
api=root/'app/src/main/java/xiao/bu/tv/jar/ChannelPlugin.java'
json_api=dependency('json.jar');xml_api=dependency('xmlpull.jar')
classpath=os.pathsep.join(map(str,[android,json_api,xml_api,okhttp,okio,classes,ijk,exo,flac]))
sources=[str(api),*map(str,(here/'src').rglob('*.java'))]
subprocess.run([javac,'-encoding','UTF-8','-g:source,lines',*language,'-cp',classpath,'-d',str(classes),*sources],check=True)
program=out/'program.jar';contract=out/'contract.jar'
with zipfile.ZipFile(program,'w') as pzip,zipfile.ZipFile(contract,'w') as azip:
    for f in sorted(classes.rglob('*.class')):
        name=f.relative_to(classes).as_posix();(azip if name.startswith('xiao/bu/tv/jar/') else pzip).write(f,name)
    for library in (exo,flac):
        with zipfile.ZipFile(library) as z:
            for name in sorted(z.namelist()):
                if name.endswith('.class'):pzip.writestr(name,z.read(name))
dex=out/'dex';dex.mkdir(exist_ok=True)
subprocess.run([str(java/('java'+exe)),'-cp',str(sdk/'build-tools/30.0.3/lib/d8.jar'),'com.android.tools.r8.R8','--min-api','14','--pg-conf',str(here/'shrink.pro'),'--lib',str(sdk/'platforms/android-30/android.jar'),'--lib',str(contract),'--lib',str(okhttp),'--lib',str(okio),'--lib',str(ijk),'--output',str(dex),str(program)],check=True)
dist=here/'dist';dist.mkdir(exist_ok=True);jar=dist/'bl.jar'
with zipfile.ZipFile(jar,'w',compression=zipfile.ZIP_DEFLATED) as z:
    def entry(name,data):
        info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,data)
    entry('classes.dex',(dex/'classes.dex').read_bytes())
    entry('channel-plugin.json',json.dumps(dict(api=1,version=16,minSdk=14,entry='xiao.bu.channel.bl.BlPlugin',native=True,hostCapability='NativeHost')))
    for f in sorted((here/'assets').rglob('*')):
        if f.is_file() and f.suffix!='.webp':entry('assets/'+f.relative_to(here/'assets').as_posix(),f.read_bytes())
    for name in ['NOTICE','LICENSE-newBV','LICENSE-ZXing','LICENSE-ExoPlayer','LICENSE-jFLAC']:
        if (here/name).exists():entry(name,(here/name).read_bytes())
sha=hashlib.sha256(jar.read_bytes()).hexdigest();(dist/'bl.sha256').write_text(sha+'\n',encoding='ascii')
(dist/'example.m3u').write_text('#EXTM3U\n#EXTINF:-1 group-title="视频",BL 哔哩哔哩\nhttps://raw.githubusercontent.com/buhanzhe/NativeWasmTv/master/plugins/bl/dist/bl.jar?sha256='+sha+'\n',encoding='utf-8')
print(f'Built {jar}: {jar.stat().st_size} bytes, API 14, SHA256 {sha}')
