"""Run pure-Java lexical preflight cases without an Android runtime."""
import json, pathlib, shutil, subprocess
root=pathlib.Path(__file__).resolve().parents[2]
dest=root/'.codex-tmp/strict-preflight'; dest.mkdir(parents=True,exist_ok=True)
modern=json.loads((root/'tools/web-compat/results/strict-preflight-inputs.json').read_text())
ordinary=['var x=1;','function f(x){return x+1;}','var message="function name()";']
array=lambda values: ','.join(json.dumps(s,ensure_ascii=False) for s in values)
source='''package xiao.bu.tv;
public class StrictPreflightTest {public static void main(String[] args){
 for(String s:new String[]{%s})if(!LegacyJsSyntax.needsCompilation(s))throw new AssertionError("Missed: "+s);
 for(String s:new String[]{%s})if(LegacyJsSyntax.needsCompilation(s))throw new AssertionError("Unneeded: "+s);
 System.out.println("PASS native lexical route: strict-only, inherited, switch, comments, Unicode names; classic fast path");
}}
'''%(array(modern),array(ordinary))
fixture=dest/'StrictPreflightTest.java';fixture.write_text(source)
compiler=[shutil.which('javac')] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
subprocess.run(compiler+['-source','8','-target','8','-encoding','UTF-8','-d',str(dest),str(root/'app/src/main/java/xiao/bu/tv/LegacyJsSyntax.java'),str(fixture)],check=True)
subprocess.run(['java','-cp',str(dest),'xiao.bu.tv.StrictPreflightTest'],check=True)
