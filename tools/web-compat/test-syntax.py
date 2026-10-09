import os, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parents[2]
dest=root/'.codex-tmp/web-compat/syntax-test';dest.mkdir(parents=True,exist_ok=True)
source=r'''
package xiao.bu.tv;
public class SyntaxTest {
 public static void main(String[] args){
  String[] modern={"const a=1;","let a=1;","var f=x=>x;","class A{}","async function f(){await x;}","function* f(){yield 1;}","var a={x};","var a={f(){}};","var a={'f'(){}};","var a={[x]:1};","var {x}=a;","var [x]=a;","([x]=a);","function f(x=2){}","function f({x}){}","function f([x]){}","var x=[...a];","var x=`hi`;","var x=a?.b;","var x=a??2;","var x=2**3;","var x=0b11;","var x=42n;","var x=1_000;","var x=/a/u;","var x='\\u{1234}';","import('./a.js');"};
  for(String s:modern)if(!LegacyJsSyntax.needsCompilation(s))throw new AssertionError("Missed syntax: "+s);
  for(String s:new String[]{"var x=1;","function f(x){return x+1;}","var x='class const =>'; // const bad\n","var x={get a(){return 1;}};","var x=/abc/g;"})if(LegacyJsSyntax.needsCompilation(s))throw new AssertionError("Unnecessary compiler: "+s);
  System.out.println("PASS modern syntax preflight, classic pass-through, strings/comments and regex flags");
 }
}
'''
fixture=dest/'SyntaxTest.java';fixture.write_text(source,encoding='utf-8')
jdk=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(dest),str(root/'app/src/main/java/xiao/bu/tv/LegacyJsSyntax.java'),str(fixture)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(dest),'xiao.bu.tv.SyntaxTest'],check=True)
