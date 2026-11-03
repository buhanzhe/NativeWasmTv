"""Exercise the production SPS sanitizer with interleaved proxy modes."""
import os, subprocess
from pathlib import Path
root=Path(__file__).resolve().parent.parent
source=(root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf-8')
a=source.index('    private static void sanitizeH264SpsMarker(')
b=source.index('    private static int capturedCmgUpdateTagForNal(',a)
method=source[a:b]
java='public class SpsModeIsolation {\nMETHOD\n static void check(boolean value){if(!value)throw new AssertionError();}\n public static void main(String[] args)throws Exception {\n  Thread enabled=new Thread(()->run(true));Thread disabled=new Thread(()->run(false));\n  enabled.start();disabled.start();enabled.join();disabled.join();\n  if(error!=null)throw new AssertionError(error);\n  System.out.println("PASS concurrent enabled/disabled SPS settings and short NAL safety");\n }\n static volatile Throwable error;\n static void run(boolean enabled) {\n  try {for(int i=0;i<10000;i++) {\n   byte[] nal={0x67,0x64,0x03};sanitizeH264SpsMarker(nal,0,nal.length,enabled);\n   check(nal[2]==(enabled?0:3));\n   sanitizeH264SpsMarker(new byte[1],0,1,enabled);\n  }}catch(Throwable t){error=t;}\n }\n}'.replace('METHOD',method)
# Production must pass each proxy's mode through the entire CMG path.
assert 'h264SpsCompatibilityMode' not in source
assert 'decryptYangshipinTransportStream(packets, spsCompatibilityMode, pid)' in source
assert 'decryptPesNals(output, currentPes, spsCompatibility)' in source
out=root/'.codex-tmp/pre-release-audit/sps-mode';out.mkdir(parents=True,exist_ok=True)
(out/'SpsModeIsolation.java').write_text(java,encoding='utf-8')
bin=Path(os.environ['JAVA_HOME'])/'bin'
subprocess.run([str(bin/'javac.exe'),str(out/'SpsModeIsolation.java')],check=True)
subprocess.run([str(bin/'java.exe'),'-cp',str(out),'SpsModeIsolation'],check=True)
