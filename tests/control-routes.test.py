"""Check that every bundled page dependency is served by the real route allowlist."""
from pathlib import Path
import os, re, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets/control'
routes = sorted({url.split('?')[0] for page in assets.rglob('*.html')
                 for url in re.findall(r'(?:src|href)="(/[^"#]+)"', page.read_text(encoding='utf-8'))
                 if url.split('?')[0].endswith(('.js', '.css'))})
routes = sorted(set(routes) | {'/' + page.relative_to(assets).as_posix() for page in assets.rglob('*.html')})
java = '''package xiao.bu.tv;
public class RouteCheck {
 public static void main(String[] paths) {
  for (String path : paths) {
   if (!ControlSite.contains(path)) throw new AssertionError("Unserved dependency: " + path);
   if (!new java.io.File("app/src/main/assets/" + ControlSite.assetPath(path)).isFile())
    throw new AssertionError("Missing asset: " + path);
  }
  if (ControlSite.contains("/device-api.js") || ControlSite.contains("/mock.js")) throw new AssertionError("Preview files exposed");
  if (ControlSite.contains("/../private")) throw new AssertionError("Private route exposed");
  System.out.println("PASS management page dependencies: " + paths.length);
 }
}'''
jdk = Path(os.environ.get('JAVA_HOME', root / '.codex-tmp/jdk8/runtime/jdk8u504-b01')) / 'bin'
with tempfile.TemporaryDirectory() as temp:
    check = Path(temp) / 'RouteCheck.java'
    check.write_text(java, encoding='utf-8')
    subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-d', temp,
                    str(root / 'app/src/main/java/xiao/bu/tv/ControlSite.java'), str(check)], check=True)
    subprocess.run([str(jdk / 'java.exe'), '-cp', temp, 'xiao.bu.tv.RouteCheck', *routes], cwd=root, check=True)
