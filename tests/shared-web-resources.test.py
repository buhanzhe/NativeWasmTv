"""Exercise the real browser transport routing with real OkHttp request semantics."""
from pathlib import Path
import os, subprocess, tempfile
root = Path(__file__).resolve().parents[1]
cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group, artifact):
    return next(p for p in (cache/group/artifact).rglob('*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar')))
classpath = os.pathsep.join(map(str, [jar('com.squareup.okhttp3', 'okhttp'), jar('com.squareup.okio', 'okio')]))
java = r'''package xiao.bu.tv;
import okhttp3.*;
class Check {
 static void ok(boolean value){if(!value)throw new AssertionError();}
 static Request request(String path){return new Request.Builder().url("https://site.example"+path).build();}
 public static void main(String[] args){
  for(String path:new String[]{"/app.js?token=a", "/app.mjs", "/style.CSS", "/codec.wasm", "/font.woff2", "/poster.png"})ok(WebResourceHub.eligible(request(path)));
  for(String path:new String[]{"/live.m3u8", "/chunk.ts", "/movie.mp4", "/api?file=app.js", "/page.html"})ok(!WebResourceHub.eligible(request(path)));
  Request source=request("/app.js?token=one");
  ok(!WebResourceHub.eligible(source.newBuilder().header("Range","bytes=0-7").build()));
  ok(!WebResourceHub.eligible(source.newBuilder().head().build()));
  ok(!WebResourceHub.eligible(source.newBuilder().post(RequestBody.create(null,"x")).tag(WebResourceHub.Kind.class,WebResourceHub.Kind.DOCUMENT).build()));
  ok(WebResourceHub.eligible(request("/page").newBuilder().tag(WebResourceHub.Kind.class,WebResourceHub.Kind.DOCUMENT).build()));
  Request route=request("/page#/tvDetail?code=3001").newBuilder().tag(WebResourceHub.Kind.class,WebResourceHub.Kind.DOCUMENT).build();
  Request wire=route.newBuilder().url(route.url().newBuilder().fragment(null).build()).header("Cookie","owner-cookie").build();
  Request response=WebResourceHub.responseRequest(route,wire);ok(response.url().encodedFragment().equals("/tvDetail?code=3001"));ok(response.header("Cookie").equals("owner-cookie"));
  Request another=route.newBuilder().url("https://site.example/page#/tvDetail?code=3002").build();ok(WebResourceHub.responseRequest(another,wire).url().encodedFragment().equals("/tvDetail?code=3002"));
  ok(WebResourceHub.responseRequest(route.newBuilder().tag(WebResourceHub.Kind.class,null).build(),wire).url().fragment()==null);
  ok(!WebResourceHub.eligible(request("/page").newBuilder().tag(WebResourceHub.Kind.class,WebResourceHub.Kind.PUBLIC_STATIC).build()));
  final int[] intercepted={0};OkHttpClient client=new OkHttpClient();
  WebResourceHub.install((transport,request)->{ok(transport==client&&request==source);intercepted[0]++;return transport.newCall(request);});
  Call shared=WebResourceHub.newCall(client,source);ok(shared.request().url().query().equals("token=one")&&intercepted[0]==1);
  Call media=WebResourceHub.newCall(client,request("/live.m3u8"));ok(media.request().url().encodedPath().equals("/live.m3u8")&&intercepted[0]==1);
  WebResourceHub.install(null);WebResourceHub.newCall(client,source);ok(intercepted[0]==1);
  System.out.println("PASS static/doc routing, method/range isolation, live-media bypass, signed-query identity and optional transport fallback");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    source=Path(tmp)/'Check.java';source.write_text(java,encoding='utf8')
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'), '-encoding', 'UTF-8', '-cp', classpath, '-d', tmp, str(root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/WebResourceHub.java'), str(source)], check=True)
    subprocess.run([str(jdk/'java'), '-cp', tmp+os.pathsep+classpath, 'xiao.bu.tv.Check'], check=True)
