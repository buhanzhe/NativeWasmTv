"""Exercise production streaming document insertion with fragmented input."""
import subprocess, os
from pathlib import Path
root=Path(__file__).resolve().parents[2]
out=root/'.codex-tmp/web-compat/injector-test';out.mkdir(parents=True,exist_ok=True)
(out/'LegacyDocumentInjector.java').write_text((root/'app/src/main/java/xiao/bu/tv/LegacyDocumentInjector.java').read_text(encoding='utf-8'),encoding='utf-8')
(out/'InjectorTest.java').write_text(r'''package xiao.bu.tv;
import java.io.*;import java.nio.charset.*;import java.util.*;
public class InjectorTest {
 static final String INJECT="<script>window.early=true;</script>";
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Body extends ByteArrayInputStream {boolean closed;int read;
  Body(byte[] b){super(b);} public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,Math.min(len,7));if(n>0)read+=n;return n;}
  public void close(){closed=true;}
 }
 static void test(String prefix,String suffix,String charset)throws Exception {
  Charset cs=Charset.forName(charset);byte[] original=(prefix+suffix).getBytes(cs);Body body=new Body(original);
  InputStream stream=LegacyDocumentInjector.prepend(body,cs,INJECT);
  check(body.read<=16384,"Must not buffer whole response");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[128];int n;
  while((n=stream.read(b))!=-1)bytes.write(b,0,n);stream.close();
  check(Arrays.equals(bytes.toByteArray(),(prefix+INJECT+suffix).getBytes(cs)),"Preserve bytes/order: "+prefix);check(body.closed,"Close upstream stream");
 }
 public static void main(String[] args)throws Exception {
  test("<!doctype html><html><head>","<script>site()</script><title>中文</title></head><body>"+String.join("",Collections.nCopies(30000,"x")),"UTF-8");
  test("<!doctype html><!-- <head>comment</head> --> <html><HEAD x='y'>","<script>site()</script>中文","GBK");
  test("<!doctype html>","<script>earlySite()</script><head></head>","UTF-8");
  test("\ufeff","<body>no head</body>","UTF-8");
  Body raw=new Body("<head></head>".getBytes("UTF-16LE"));check(LegacyDocumentInjector.prepend(raw,Charset.forName("UTF-16LE"),INJECT)==raw,"Unsupported encoding unchanged");
  System.out.println("PASS streamed early injection, first-script ordering, fragmented markup, comments, BOM, GBK and upstream closure");
 }
}''',encoding='utf-8')
java=Path(os.environ['JAVA_HOME'])/'bin'
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',str(out),str(out/'LegacyDocumentInjector.java'),str(out/'InjectorTest.java')],check=True)
subprocess.run([str(java/'java.exe'),'-cp',str(out),'xiao.bu.tv.InjectorTest'],check=True)
