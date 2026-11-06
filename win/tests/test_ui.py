"""Run actual Swing controls and decoder pixels under an available desktop."""
import argparse
import base64
import functools
import http.server
import os
from pathlib import Path
import shlex
import subprocess
import threading

HERE=Path(__file__).resolve().parent


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle',type=Path,required=True)
    p.add_argument('--fixture',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--java-command',default='java')
    p.add_argument('--windows',action='store_true',default=os.name=='nt')
    a=p.parse_args();a.bundle=a.bundle.resolve();a.output.mkdir(parents=True,exist_ok=True)
    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self,*args):pass
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Handler,directory=str(a.fixture.resolve())))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    origin='http://127.0.0.1:%d'%server.server_port
    script=base64.urlsafe_b64encode(b"document.getElementById('selected').click();").decode()
    first=a.output/'first.txt';first.write_text('Green,webview://'+origin+'/index.html?ujs='+script+'\nDirect,'+origin+'/selected.m3u8\n')
    second=a.output/'second.txt';second.write_text('Second,'+origin+'/selected.m3u8\n')
    app=a.bundle/'app';classes=a.output/'classes';classes.mkdir(exist_ok=True)
    files=[app/'ntv-tv.jar',app/'litecore.jar',*sorted((app/'lib').glob('*.jar'))]
    subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',os.pathsep.join(map(str,files)),'-d',str(classes),str(HERE/'java/xiao/bu/tv/windows/UiSmoke.java')],check=True)
    def path(v):
        value=str(Path(v).resolve());return 'Z:'+value.replace('/','\\') if a.windows and os.name!='nt' else value
    cp=(';' if a.windows else ':').join(map(path,[classes,*files]))
    command=shlex.split(a.java_command)+['-Xmx256m','-Duser.home='+path(a.output/'user'),'-cp',cp,'xiao.bu.tv.windows.UiSmoke',path(a.bundle),path(first),path(second),path(a.output/'actual-window.png')]
    try:subprocess.run(command,check=True,timeout=70)
    finally:server.shutdown();server.server_close()


if __name__=='__main__':main()
