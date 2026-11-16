"""Execute process ABI selection: a universal APK must never load hardware-ABI plugins into a different process."""
import os, subprocess, tempfile
from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/xiao/bu/tv/RuntimeAbi.java'
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    (folder/'BuildConfig.java').write_text('package xiao.bu.tv; final class BuildConfig { static String CJS_PLUGIN_ABI="universal"; }')
    (folder/'AbiCheck.java').write_text('''package xiao.bu.tv;
class AbiCheck {
 static void check(String arch,String expected){System.setProperty("os.arch",arch);String actual=RuntimeAbi.current();if(!expected.equals(actual))throw new AssertionError(arch+" -> "+actual);}
 public static void main(String[] args){
  check("aarch64","arm64-v8a");check("arm64","arm64-v8a");
  check("armv7l","armeabi-v7a");check("armv8l","armeabi-v7a");
  check("i686","x86");check("i386","x86");check("x86","x86");
  try{check("mips","");throw new AssertionError("Unsupported architecture accepted");}catch(IllegalStateException expected){}
  BuildConfig.CJS_PLUGIN_ABI="armeabi-v7a";check("aarch64","armeabi-v7a");
  BuildConfig.CJS_PLUGIN_ABI="arm64-v8a";check("amd64","arm64-v8a");
 }
}''',encoding='utf-8')
    jdk = Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',tmp,str(source),str(folder/'BuildConfig.java'),str(folder/'AbiCheck.java')],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',tmp,'xiao.bu.tv.AbiCheck'],check=True)
print('PASS universal process ABI and fixed-flavor plugin selection')
