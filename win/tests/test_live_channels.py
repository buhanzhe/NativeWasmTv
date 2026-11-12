"""Ten real playlist channels: official page -> production Windows VM -> real decode.

Proxy policy denials are BLOCKED, not channel failures or playback passes.
All evidence stays in a new output directory; no prior failures are overwritten.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shlex
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from live_decode import observe

HERE=Path(__file__).resolve().parent


def policy_denial(message):
    return bool(re.search(r'(?:tunnel connection failed.*403|CONNECT.*403|403.*CONNECT)',message,re.I))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle',type=Path,required=True)
    p.add_argument('--java-command',default='java')
    p.add_argument('--decoder-command',default='ffmpeg')
    p.add_argument('--windows',action='store_true',default=os.name=='nt')
    p.add_argument('--proxy',action='store_true',help='Use inherited platform proxy and CA trust for cloud testing')
    p.add_argument('--seconds',type=int,default=8)
    p.add_argument('--jobs',type=int,default=2)
    p.add_argument('--actual-ffplay',action='store_true',help='Actual bundled FFplay/SDL and owned-window capture')
    p.add_argument('--fresh-hls-connections',action='store_true',help='A/B remote HLS connection reuse')
    p.add_argument('--selection',type=Path,default=HERE/'live-ten.json')
    p.add_argument('--fixture',action='store_true',help='Explicitly mark a local fixture run; never a real-channel pass')
    p.add_argument('--all-sites',action='store_true',help='Run a recorded complete site inventory instead of the ten-channel sample')
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();a.bundle=a.bundle.resolve();a.output=a.output.resolve()
    if a.output.exists() and any(a.output.iterdir()):p.error('Output is not empty; use a new directory')
    a.output.mkdir(parents=True,exist_ok=True)
    started_at=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat()
    manifest=json.loads(a.selection.read_text(encoding='utf-8'))
    raw=(a.bundle/'playlists/webview.txt').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=manifest['playlist_sha256']:p.error('Test selection and bundled playlist snapshots differ')
    selected=manifest['channels']
    if a.fixture and manifest.get('kind')!='local-fixture':p.error('Fixture marker is required')
    if a.all_sites and manifest.get('kind')!='site-sweep':p.error('All-sites runs require a site-sweep manifest')
    if not a.fixture and not a.all_sites and (len(selected)!=10 or manifest.get('kind')=='local-fixture'):p.error('Real run requires ten real channels')
    assert selected and len({x['id'] for x in selected})==len(selected)
    if not a.fixture:
        lines=raw.decode('utf-8-sig').splitlines()
        if len({x['url'] for x in selected})!=len(selected):p.error('Real selection contains duplicate source URLs')
        for item in selected:
            if a.all_sites and item.get('source')=='user-example':continue
            index=item['source_line']-1
            if index<0 or index>=len(lines) or lines[index]!=item['name']+','+item['url']:
                p.error('Selected channel differs from the exact bundled source line: '+item['id'])
    app=a.bundle/'app';classes=a.output/'classes';classes.mkdir(exist_ok=True)
    jars=[app/'ntv-tv.jar',app/'litecore.jar',*sorted((app/'lib').glob('*.jar'))]
    subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(HERE/'java/xiao/bu/tv/windows/RealChannelProbe.java')],check=True)
    def path(value):
        result=str(Path(value).resolve());return 'Z:'+result.replace('/','\\') if a.windows and os.name!='nt' else result
    cp=(';' if a.windows else os.pathsep).join(path(x) for x in [classes,*jars])
    options=['-Xmx256m','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true','-Dntv.library='+path(app/('ntvLiteDesktop.dll' if a.windows else 'libntvLiteDesktop.so'))]
    proxy=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY','')) if a.proxy else None
    if proxy and proxy.hostname:
        for scheme in ['http','https']:options.extend([f'-D{scheme}.proxyHost={proxy.hostname}',f'-D{scheme}.proxyPort={proxy.port or 80}'])
        options+=['-Dhttp.nonProxyHosts=localhost|127.*']
        if Path('/etc/ssl/certs/java/cacerts').exists():options+=['-Djavax.net.ssl.trustStore='+path('/etc/ssl/certs/java/cacerts')]
    def test(item):
        folder=a.output/item['id'];folder.mkdir()
        row={'id':item['id'],'name':item['name'],'source_line':item['source_line'],'requested_page':item['url'],
             'status':'running','page':'unrun','media':'unrun','decode':'unrun','decoded_seconds':0,'video_frames':0}
        original=item['url'].removeprefix('webview://');begin=time.monotonic();vm=None;decoder=None
        try:
            try:
                request=urllib.request.Request(original,headers={'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/110.0.0.0 Safari/537.36'})
                with urllib.request.urlopen(request,timeout=20) as response:
                    row['preflight_http']=response.status;response.read(4096)
            except Exception as error:
                row['preflight_error']=str(error)
                if policy_denial(str(error)):
                    row.update(status='blocked',page='blocked',reason='platform_proxy_policy_denied');return row
            command=shlex.split(a.java_command)+options+['-cp',cp,'xiao.bu.tv.windows.RealChannelProbe',item['url'],path(folder/'profile')]
            with (folder/'vm.log').open('w',encoding='utf-8') as errors:
                vm=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=errors,text=True,encoding='utf-8',errors='replace')
                messages=queue.Queue()
                def read_vm():
                    for line in vm.stdout:messages.put(line.rstrip())
                threading.Thread(target=read_vm,daemon=True).start()
                media=None;deadline=time.monotonic()+80
                while time.monotonic()<deadline:
                    try:message=messages.get(timeout=.2)
                    except queue.Empty:
                        if vm.poll() is not None:raise RuntimeError('Windows session exited before media handoff; see vm.log')
                        continue
                    if message.startswith('FAILED_JSON '):raise RuntimeError(json.loads(message[12:])['message'])
                    if message.startswith('MEDIA_JSON '):media=json.loads(message[11:]);break
                if not media:raise RuntimeError('Media handoff timeout')
                # Private local diagnostics only: never copied by the public report exporter.
                (folder/'media-private.json').write_text(json.dumps(media,ensure_ascii=False),encoding='utf8')
                row.update(page='loaded',media='handed_off',handoff_ms=round((time.monotonic()-begin)*1000),header_names=list(media['headers']))
                frame=folder/'frame.png'
                def command(chosen,picture,remaining):
                    cmd=shlex.split(a.decoder_command)+['-v','error','-nostdin','-y','-threads','2','-filter_threads','1','-re','-rw_timeout','12000000','-progress','pipe:1','-stats_period','0.5']
                    if urllib.parse.urlsplit(chosen['url']).hostname not in ('localhost','127.0.0.1') and proxy and proxy.hostname:
                        cmd+=['-http_proxy',proxy.geturl(),'-tls_verify','1','-ca_file',path('/etc/ssl/certs/ca-certificates.crt')]
                    headers=''
                    for key,value in chosen['headers'].items():
                        if not re.fullmatch('[A-Za-z0-9-]+',key) or '\r' in value or '\n' in value:raise RuntimeError('Invalid media header')
                        headers+=key+': '+value+'\r\n'
                    if headers:cmd+=['-headers',headers]
                    if chosen.get('virtualMse'):cmd+=['-live_start_index','0']
                    elif a.fresh_hls_connections and urllib.parse.urlsplit(chosen['url']).path.lower().endswith('.m3u8'):cmd+=['-http_persistent','0']
                    return cmd+['-i',chosen['url'],'-t',str(remaining),'-map','0:v:0','-map','0:a:0?','-f','null','-','-map','0:v:0','-vf','select=gte(n\\,25)','-frames:v','1','-an','-threads','1',path(picture)]
                if a.actual_ffplay or media.get("liveHls"):
                    from live_ffplay import observe as observe_ffplay
                    observe_ffplay(media,vm,messages,folder,row,a.seconds,a.bundle/'player/ffplay.exe',a.fresh_hls_connections)
                else:observe(media,vm,messages,folder,row,a.seconds,command)
                if not frame.exists() or frame.stat().st_size<100 or row['decoded_seconds']<a.seconds-.5 or row['video_frames']<20:
                    raise RuntimeError('Incomplete actual frame/playback clock evidence')
                row.update(status='passed',decode='passed',frame='frame.png',frame_sha256=hashlib.sha256(frame.read_bytes()).hexdigest())
                vm.stdin.write('STOP\n');vm.stdin.flush();vm.wait(timeout=12)
                if vm.returncode!=0:raise RuntimeError('Session cleanup failed')
        except Exception as error:
            row.update(status='blocked' if policy_denial(str(error)) else 'failed',reason=str(error))
            if row['page']=='unrun':row['page']='blocked' if row['status']=='blocked' else 'failed'
        finally:
            if vm is not None and vm.poll() is None:
                try:vm.stdin.write('STOP\n');vm.stdin.flush();vm.wait(timeout=3)
                except (OSError,subprocess.TimeoutExpired):pass
            for process in [decoder,vm]:
                if process is not None and process.poll() is None:process.kill();process.wait()
            row['elapsed_ms']=round((time.monotonic()-begin)*1000)
            (folder/'result.json').write_text(json.dumps(row,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
            print(item['name']+': '+row['status'],flush=True)
        return row
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1,min(3,a.jobs))) as pool:rows=list(pool.map(test,selected))
    counts={state:sum(x['status']==state for x in rows) for state in ['passed','failed','blocked']}
    report={'source_commit':manifest['source_commit'],'playlist_sha256':manifest['playlist_sha256'],'real_channels':not a.fixture,'started_at':started_at,'planned':len(selected),'completed':len(rows),'playback_completed':counts['passed'],'windows_core':a.windows,'seconds_required':a.seconds,'counts':counts,'channels':rows}
    (a.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(counts));raise SystemExit(0 if counts['passed']==len(selected) else 2)


if __name__=='__main__':main()
