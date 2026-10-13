#!/usr/bin/env python3
"""Host regression; optional captured input stays local and is never distributed."""
import hashlib, os, pathlib, shutil, subprocess, sys, tempfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
JAVA=pathlib.Path(os.environ.get('JAVA_HOME',''))/'bin/java'
JAVAC=pathlib.Path(os.environ.get('JAVA_HOME',''))/'bin/javac'
source=(ROOT/'app/src/main/java/xiao/bu/tv/LegacyScriptSourceAdapter.java').read_text()
fixture='// synthetic fixture\n&#036;(&quot;.nav&quot;); // &lt;&gt;\n'
runner='''package xiao.bu.tv;
import java.nio.file.*;
public class SourceAdapterTest {
 public static void main(String[] args)throws Exception {
  String source=new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8");
  String page="https://www.hrbtv.net/folder79/folder81/",url="https://www.hrbtv.net/t/1/7/js/subnav.js?v=20241218214025";
  String adapted=LegacyScriptSourceAdapter.adapt(page,url,source);
  if(adapted.equals(source)||adapted.contains("&#036;")||adapted.contains("&quot;"))throw new AssertionError("positive exact-source decode");
  String[][] cases={{"https://example.org/folder79/folder81/",url,source},{page,url+"&other=1",source},{page,url,source+" "},{page,url,adapted},{page,"http://www.hrbtv.net/t/1/7/js/subnav.js?v=20241218214025",source},{"https://www.hrbtv.net/news/",url,source},{"https://user@www.hrbtv.net/folder79/folder81/",url,source},{page,url,"<html>Access denied</html>"},{page,url,null}};
  for(String[] c:cases)if(LegacyScriptSourceAdapter.adapt(c[0],c[1],c[2])!=c[2])throw new AssertionError("mismatch must preserve identity");
  Files.write(Paths.get(args[1]),adapted.getBytes("UTF-8"));
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 d=pathlib.Path(tmp);(d/'SourceAdapterTest.java').write_text(runner)
 for captured in [False]+([True] if len(sys.argv)>1 else []):
  if captured:body=pathlib.Path(sys.argv[1]).read_bytes();code=source
  else:body=fixture.encode();code=source.replace('5b7b329f8fa198968a0e154c020cfe23a70211da63c49f5024c5660cecf33b0d',hashlib.sha256(body).hexdigest())
  (d/'LegacyScriptSourceAdapter.java').write_text(code);(d/'input.js').write_bytes(body)
  subprocess.run([str(JAVAC),'-source','1.7','-target','1.7','-d',str(d),str(d/'LegacyScriptSourceAdapter.java'),str(d/'SourceAdapterTest.java')],check=True)
  output=pathlib.Path(sys.argv[2]).resolve() if captured and len(sys.argv)>2 else d/'output.js'
  subprocess.run([str(JAVA),'-cp',str(d),'xiao.bu.tv.SourceAdapterTest',str(d/'input.js'),str(output)],check=True)
  print('PASS source adapter:', 'unaltered production fingerprint + local captured source' if captured else 'synthetic fixture with test-only fingerprint', '; host/url/version/body/site/auth mismatch, idempotence, denial unchanged')
assert 'source=LegacyScriptSourceAdapter.adapt(page,url,source);' in (ROOT/'app/src/main/java/xiao/bu/tv/LegacyWebTranspilation.java').read_text()
