"""Release asset names stay compatible; case variants never cross ABI boundaries."""
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
source = (root / 'app/src/main/java/xiao/bu/tv/AutoUpdater.java').read_text('utf-8')
method = source[source.index('    static String validatedApkFilename('):source.index('    private static boolean isArm32Build()')]
check = '''package xiao.bu.tv;
import java.io.*; import java.net.*; import org.json.JSONException;
class AssetCheck {
METHOD
 static void verify(String file,String expected,String display,boolean valid)throws Exception {
  try {String actual=validatedApkFilename("https://github.com/a/b/releases/latest/download/"+file,expected,display);
   if(!valid||!actual.equals(URLDecoder.decode(file,"UTF-8")))throw new AssertionError(file);
  }catch(JSONException e){if(valid)throw new AssertionError(file,e);}
 }
 public static void main(String[] args)throws Exception {
  if(supportsArm64(null)||supportsArm64(new String[]{"x86_64"})
    ||!supportsArm64(new String[]{"arm64-v8a"}))throw new AssertionError("ARM64 compatibility");
  verify("nTv.apk","nTv.apk","网页电视32位.apk",true);
  verify("ntv.apk","nTv.apk","网页电视32位.apk",true);
  verify("ntv64.apk","nTv64.apk","网页电视64位.apk",true);
  verify("nTvX86.apk","nTvX86.apk","网页电视x86.apk",true);
  verify("%E7%BD%91%E9%A1%B5%E7%94%B5%E8%A7%8632%E4%BD%8D.apk","nTv.apk","网页电视32位.apk",true);
  verify("nTv64.apk","nTv.apk","网页电视32位.apk",false);
  verify("ntv.apk","nTv64.apk","网页电视64位.apk",false);
  verify("nTvX86.apk","nTv64.apk","网页电视64位.apk",false);
  verify("nTv.apk.exe","nTv.apk","网页电视32位.apk",false);
  verify("%zz","nTv.apk","网页电视32位.apk",false);
 }
}'''.replace('METHOD', method)
with tempfile.TemporaryDirectory() as folder:
    tmp = Path(folder)
    (tmp/'AssetCheck.java').write_text(check, encoding='utf-8')
    (tmp/'JSONException.java').write_text('package org.json; public class JSONException extends Exception {public JSONException(String message){super(message);}}')
    jdk = Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '-d', folder, str(tmp/'AssetCheck.java'), str(tmp/'JSONException.java')], check=True)
    subprocess.run([str(jdk/'java.exe'), '-cp', folder, 'xiao.bu.tv.AssetCheck'], check=True)

names = ['ntv_bro.apk', 'nTv.apk', 'nTv64.apk', 'nTvX86.apk']
script = (root/'scripts/build-release.ps1').read_text('utf-8-sig')
gradle = (root/'app/build.gradle').read_text('utf-8')
assert re.findall(r"Name = '(.*\.apk)'", script) == names
for name in names:
    assert '\"' + name + '\"' in gradle
manifest = json.loads((root/'version-lite.json').read_text('utf-8'))
# The checked-in manifest describes the published release. Older releases may
# predate bro; only the build/export checks below require the new bro fields.
for key, name in zip(['apkBroUrl','apk32Url','apk64Url','apkX86Url'], names):
    if key == 'apkBroUrl' and key not in manifest:
        continue
    assert manifest[key].rsplit('/',1)[-1] == name, (key, name)
if os.name == 'nt':
    # Execute the production copy/rename steps over existing lowercase files.
    # Get-Item echoes requested casing; enumerate real directory entries instead.
    start = script.index('    $destination = Join-Path $OutputDirectory $artifact.Name')
    end = script.index('    & $apksigner verify', start)
    copy_steps = script[start:end]
    with tempfile.TemporaryDirectory(prefix='ntv-assets-') as folder:
        output=Path(folder)/'out';output.mkdir();apk=Path(folder)/'source.apk';apk.write_bytes(b'new apk')
        quote=lambda value: str(value).replace("'", "''")
        for name in names:
            (output/name.lower()).write_bytes(b'old apk')
            command=("$ErrorActionPreference='Stop';$OutputDirectory='"+quote(output)+"';"
                     "$artifact=[pscustomobject]@{Name='"+name+"';Source='"+quote(apk)+"'};\n"+copy_steps)
            subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',command],check=True)
            assert name in [p.name for p in output.iterdir()], name
            assert (output/name).read_bytes()==b'new apk'
    # Exercise the production export gate with stale hashes/versions, including
    # the deliberate difference between startup code 6 and actual APK code 9.
    start = script.index('foreach ($manifestPath in @($liteVersionPath, $versionPath, $legacyVersionPath))')
    end = script.index('Copy-Item -LiteralPath $liteVersionPath', start)
    gate = script[start:end]
    with tempfile.TemporaryDirectory(prefix='ntv-manifests-') as folder:
        paths = [Path(folder)/name for name in ['version-lite.json','version.json','version-iptv.json']]
        original = dict(versionCode=9, versionName='1.8.1', sha256='a',
                        sha25632='a', sha25664='b', sha256X86='c', sha256Bro='d')
        setup = "$ErrorActionPreference='Stop';\n"
        for var, path in zip(['liteVersionPath','versionPath','legacyVersionPath'], paths):
            setup += "$" + var + "='" + str(path).replace("'", "''") + "';\n"
        for var, sha in [('arm32Result','a'),('arm64Result','b'),('x86Result','c'),('broResult','d')]:
            setup += "$"+var+"=[pscustomobject]@{SHA256='"+sha+"';VersionCode=9;VersionName='1.8.1';APK='test.apk'};\n"
        for case in ['valid','stale_hash','stale_browser_hash','wrong_name','wrong_lite_code','wrong_startup_code']:
            for i, path in enumerate(paths):
                data = dict(original, versionCode=9 if i == 0 else 6)
                if case == 'stale_hash' and i == 2: data['sha256'] = 'old'
                if case == 'stale_browser_hash' and i == 0: data['sha256Bro'] = 'old'
                if case == 'wrong_name' and i == 1: data['versionName'] = 'old'
                if case == 'wrong_lite_code' and i == 0: data['versionCode'] = 8
                if case == 'wrong_startup_code' and i == 1: data['versionCode'] = 9
                path.write_text(json.dumps(data), encoding='utf-8')
            run = subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',setup+gate],
                                 capture_output=True, text=True)
            assert (run.returncode == 0) == (case == 'valid'), (case, run.stderr)
print('PASS release assets, ABI selection and manifest export gates (hashes, versions, startup threshold)')
