"""Collect matched APK/mapping pairs and package signed HDR comparison builds."""
from pathlib import Path
import argparse,hashlib,json,os,re,shutil,subprocess,zipfile

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
parser.add_argument('--collect',choices=['1MiB','8MiB'])
args=parser.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
flavors={'arm32':('v7a','电视盒子专用'),'arm64':('v8a','手机或高版本设备'),'x86':('x86','模拟器专用')}
if args.collect:
    for flavor in flavors:
        dest=out/args.collect/flavor;dest.mkdir(parents=True,exist_ok=True)
        shutil.copy2(ROOT/f'app/build/outputs/apk/{flavor}/release/app-{flavor}-release.apk',dest/'app.apk')
        shutil.copy2(ROOT/f'app/build/outputs/mapping/{flavor}Release/mapping.txt',dest/'mapping.txt')
    print('Collected '+args.collect);raise SystemExit(0)

props=(ROOT/'local.properties').read_text(encoding='utf-8')
sdk=Path(re.search(r'^sdk.dir=(.+)$',props,re.M)[1].strip().replace('\\:',':').replace('\\\\','\\'))
build_tools=sdk/'build-tools/35.0.0'
env=os.environ.copy()
if not env.get('JAVA_HOME'):env['JAVA_HOME']='C:/Users/Administrator/.jdks/jbr-17.0.14'
def command(*argv):return subprocess.check_output(list(map(str,argv)),env=env,stderr=subprocess.STDOUT).decode('utf-8',errors='replace')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
published=out/'packages';published.mkdir(exist_ok=True);records=[];certificates=set();version=None
for profile in ['1MiB','8MiB']:
    for flavor,(abi,title) in flavors.items():
        source=out/profile/flavor/'app.apk';mapping=out/profile/flavor/'mapping.txt'
        if not mapping.is_file() or mapping.stat().st_size==0:raise RuntimeError('Missing R8 mapping: '+str(mapping))
        badging=command(build_tools/'aapt.exe','dump','badging',source)
        package=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging)
        if not package or package[1]!='xiao.bu.tv':raise RuntimeError('Unexpected package identity')
        current=package[3]
        if version and version!=current:raise RuntimeError('Mismatched versions')
        version=current
        with zipfile.ZipFile(source) as apk:
            dex=b''.join(apk.read(n) for n in apk.namelist() if re.fullmatch(r'classes\d*\.dex',n))
            is_test=b'LUT128-center' in dex
            if is_test!=(profile=='8MiB'):raise RuntimeError('Wrong compiled HDR profile: '+str(source))
            libs=[n for n in apk.namelist() if n.endswith('/libijksdl.so')]
            if len(libs)!=1 or b'uploadLut3d128' not in apk.read(libs[0]):raise RuntimeError('Missing new native uploader')
        verified=command(build_tools/'apksigner.bat','verify','--print-certs',source)
        certificate=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',verified,re.I)
        if not certificate:raise RuntimeError('APK signature did not verify')
        certificates.add(certificate[1].lower())
        suffix='HDR8MiB测试' if profile=='8MiB' else 'HDR1MiB对照'
        name=f'nTv-{abi}-{title}-v{version}-{suffix}.apk';dest=published/name;shutil.copy2(source,dest)
        records.append(dict(file=name,profile=profile,flavor=flavor,package=package[1],versionCode=int(package[2]),versionName=version,sha256=sha(dest),size=dest.stat().st_size,certificateSha256=certificate[1].lower(),mapping=f'{profile}/{flavor}/mapping.txt',mappingSha256=sha(mapping)))
if len(certificates)!=1:raise RuntimeError('Comparison APKs have different signing certificates')
manifest=dict(version=version,apks=records)
(published/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
with zipfile.ZipFile(published/'mapping.zip','w',zipfile.ZIP_DEFLATED) as z:
    for record in records:z.write(out/record['mapping'],record['mapping'])
    z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
readme='''HDR 查表对比测试包

HDR8MiB测试：128³ 中心采样快速查表；HDR1MiB对照：当前稳定的 64³ 查表。
电视盒子/旧安卓一般先选 v7a；64 位手机选择 v8a；模拟器选择 x86。
两种版本均沿用 xiao.bu.tv 包名、正式签名和版本号，可互相覆盖安装并保留频道与设置。

使用相同 HDR 节目、线路和 HDR 档位对比。建议选“转 SDR”，观察流畅度、高亮灰阶、肤色及近白浅色。
普通 SDR 节目不会启用该映射，无法用于比较。调试信息显示 LUT128-center-3D 才是 8 MiB 三维路径。
若显示 2D，则设备使用兼容回退，不能套用三维路径的性能结论。
8 MiB 方案在测试的 4.4 Adreno 306 上更快，但在 7.1 上可能更慢；出现问题可覆盖安装 1 MiB 对照包。
8 MiB 是保留的 GPU 查表容量，建表时还会临时分配读取与上传缓冲区；首次启动映射可能稍慢。

mapping.zip 包含这六个 APK 各自匹配的 R8 mapping，通过 manifest.json 的 APK SHA-256 对应。
本目录为测试构建，不会更新正式发布元数据。
'''
(published/'测试说明.txt').write_text(readme,encoding='utf-8-sig')
archive=out/f'HDR查表对比测试-v{version}.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for record in records:z.write(published/record['file'],record['file'])
    for name in ['mapping.zip','manifest.json','测试说明.txt']:z.write(published/name,name)
print(json.dumps(dict(archive=str(archive),size=archive.stat().st_size,sha256=sha(archive),apks=len(records)),ensure_ascii=False))
