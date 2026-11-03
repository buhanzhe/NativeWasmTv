"""Actual hardware HDR playback -> SDR screenshot colour audit on an ADB device.

Uses the production release APK/API, lossless constant-colour HEVC Main10 charts,
and YUV reconstructed by FFmpeg. Restores changed app settings in finally.
"""
from pathlib import Path
import functools,http.server,json,os,re,subprocess,threading,time,urllib.request
import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parents[2]
ADB='D:/android/sdk/platform-tools/adb.exe'
FF='D:/ffmpeg-2026-10-04-git-a35c879992-full_build/bin/ffmpeg.exe'
DEVICE=os.environ.get('NTV_COLOUR_DEVICE','ff0316c7')
OUT=Path(os.environ.get('NTV_COLOUR_OUTPUT',str(ROOT/'output/regression/hdr-playback-colour-20261022')))
WORK=ROOT/'.codex-tmp/hdr-playback-colour';OUT.mkdir(parents=True,exist_ok=True);WORK.mkdir(parents=True,exist_ok=True)
BASE='http://127.0.0.1:19976';W,H,COLS,ROWS=1920,1080,12,6
M=np.array([[1.660491,-.587641,-.072850],[-.124550,1.132900,-.008349],[-.018151,-.100579,1.118730]])

def command(args,**kw):return subprocess.run(args,check=True,**kw)
def adb(*args,**kw):return command([ADB,'-s',DEVICE,*args],**kw)
def api(path,data=None):
    body=None if data is None else data if isinstance(data,bytes) else json.dumps(data).encode()
    request=urllib.request.Request(BASE+path,data=body,headers={'Content-Type':'application/json'})
    return json.load(urllib.request.urlopen(request,timeout=20))
def tone(v,kind):
    v=np.clip(np.asarray(v,float),0,1)
    if kind=='sdr':return v
    if kind=='pq':
        p=v**(1/78.84375);linear=10000*(np.maximum(p-.8359375,0)/(18.8515625-18.6875*p))**(1/.1593017578125)
        luma=np.maximum(linear@np.array([.2627,.678,.0593])/1000,1e-6)
        linear*= (.00291*luma**(1.03/1.2-1))[...,None]
    else:
        scene=np.where(v<=.5,v*v/3,(np.exp((v-.55991073)/.17883277)+.28466892)/12)
        linear=2.91*scene*np.maximum(scene@np.array([.2627,.678,.0593]),1e-6)[...,None]**.03
    rgb=np.maximum(linear@M.T,0);signal=rgb.max(axis=-1);t=signal-.55;peak=2.91
    shoulder=.45*(peak-.55)
    mapped=np.minimum(.55+shoulder*t/(shoulder+(peak-1)*t),1)
    scale=np.ones_like(signal);np.divide(mapped,signal,out=scale,where=signal>.55)
    return np.clip(rgb*scale[...,None],0,1)**(1/2.4)
def pq_encode(nits):
    p=np.maximum(nits,0)/10000;p=p**(.1593017578125)
    return ((.8359375+18.8515625*p)/(1+18.6875*p))**78.84375
def hlg_encode(scene):
    s=np.maximum(scene,0)
    return np.where(s<=1/12,np.sqrt(3*s),.17883277*np.log(np.maximum(12*s-.28466892,1e-20))+.55991073)
def patches(kind):
    values=[];names=[]
    for i in range(24):values.append([i/23]*3);names.append('grey-'+str(i))
    if kind=='pq' and os.environ.get('NTV_COLOUR_PATCH_SET')=='near-neutral':
        for level in [.5,.55,.6,.65,.7,.75,.8,.85]:
            for offset in [(0.006,0,0),(0,.006,0),(0,0,.006),(-.006,0,0),(0,-.006,0),(0,0,-.006)]:
                values.append(np.array([level]*3)+offset);names.append(f'near-neutral-{level}-{offset}')
        return np.clip(np.array(values),0,1),names
    for rgb in [(1,0,0),(0,1,0),(0,0,1),(1,1,0),(0,1,1),(1,0,1),(1,.5,0),(.5,1,0),(0,1,.5),(0,.5,1),(.5,0,1),(1,0,.5)]:
        values.append(rgb);names.append('2020-cube-'+str(len(values)-24))
    color709=np.array([(1,0,0),(0,1,0),(0,0,1),(1,1,0),(0,1,1),(1,0,1),(.5,.3,.2),(.7,.5,.4),(.15,.3,.5),(.1,.6,.3),(.6,.2,.1),(.3,.1,.5)])
    linear2020=color709@np.linalg.inv(M).T
    for level in (100,203):
        encoded=(pq_encode(linear2020*level) if kind=='pq' else hlg_encode(linear2020*(level/1000)**(1/1.2)) if kind=='hlg' else color709)
        for i,rgb in enumerate(encoded):values.append(rgb);names.append(f'709-{level}-{i}')
    anchors=[0,.025,.05,.1,.25,.5,.75,1,.58068888,.50807842,.9,.01]
    for value in anchors:values.append([value]*3);names.append('anchor-'+str(value))
    return np.clip(np.array(values),0,1),names
def fixture(kind):
    rgb,names=patches(kind);kr,kb=(.2126,.0722) if kind=='sdr' else (.2627,.0593)
    yy=rgb@np.array([kr,1-kr-kb,kb]);u=(rgb[:,2]-yy)/(2*(1-kb));v=(rgb[:,0]-yy)/(2*(1-kr))
    yuv=np.round(np.stack([64+876*yy,512+896*u,512+896*v],axis=1)).astype(np.uint16)
    planes=[]
    for channel in range(3):
        cw,ch=(W,H) if channel==0 else (W//2,H//2)
        plane=np.repeat(np.repeat(yuv[:,channel].reshape(ROWS,COLS),ch//ROWS,axis=0),cw//COLS,axis=1)
        planes.append(plane.astype('<u2'))
    raw=WORK/(kind+'.yuv');raw.write_bytes(b''.join(p.tobytes() for p in planes))
    path=WORK/(kind+'.mp4');trc={'pq':'smpte2084','hlg':'arib-std-b67','sdr':'bt709'}[kind]
    params='lossless=1:sao=0:deblock=0:repeat-headers=1:keyint=25:log-level=error:colorprim='+('1' if kind=='sdr' else '9')+':transfer='+{'pq':'16','hlg':'18','sdr':'1'}[kind]+':colormatrix='+('1' if kind=='sdr' else '9')+':range=limited'
    if kind=='pq':params+=':master-display=G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(10000000,1):max-cll=1000,400'
    with (WORK/(kind+'-encode.log')).open('wb') as log:
        colors=['-color_primaries','bt709' if kind=='sdr' else 'bt2020','-color_trc',trc,'-colorspace','bt709' if kind=='sdr' else 'bt2020nc','-color_range','tv']
        command([FF,'-v','error','-y','-stream_loop','-1','-f','rawvideo','-pix_fmt','yuv420p10le','-s',f'{W}x{H}','-r','25']+colors+['-i',str(raw),'-t','24','-c:v','libx265','-preset','ultrafast','-x265-params',params]+colors+['-movflags','+faststart',str(path)],stdout=log,stderr=log)
    decoded=command([FF,'-v','error','-i',str(path),'-frames:v','1','-pix_fmt','yuv420p10le','-f','rawvideo','pipe:1'],stdout=subprocess.PIPE).stdout
    a=np.frombuffer(decoded,dtype='<u2');n=W*H
    py=a[:n].reshape(H,W);pu=a[n:n+n//4].reshape(H//2,W//2);pv=a[n+n//4:].reshape(H//2,W//2)
    actual=[]
    for row in range(ROWS):
        for col in range(COLS):actual.append([py[(row*180)+90,col*160+80],pu[row*90+45,col*80+40],pv[row*90+45,col*80+40]])
    actual=np.array(actual,float);assert np.max(np.abs(actual-yuv))==0,'Lossless chart changed YUV'
    y=(actual[:,0]-64)/876;u=(actual[:,1]-512)/896;v=(actual[:,2]-512)/896
    r=y+2*(1-kr)*v;b=y+2*(1-kb)*u;g=(y-kr*r-kb*b)/(1-kr-kb)
    reconstructed=np.stack([r,g,b],axis=1)
    expected=np.round(tone(reconstructed,kind)*255).astype(int)
    preview=np.repeat(np.repeat(expected.reshape(ROWS,COLS,3),H//ROWS,axis=0),W//COLS,axis=1)
    Image.fromarray(preview.astype('uint8')).save(OUT/(kind+'-reference.png'))
    return dict(kind=kind,names=names,source_rgb=rgb.tolist(),yuv=actual.tolist(),decoded_rgb=reconstructed.tolist(),expected=expected.tolist())

class RangeHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        path=Path(self.translate_path(self.path));total=path.stat().st_size;start=0;end=total-1
        value=self.headers.get('Range');match=re.fullmatch(r'bytes=(\d+)-(\d*)',value or '')
        if match:start=int(match[1]);end=min(end,int(match[2])) if match[2] else end
        self.send_response(206 if match else 200);self.send_header('Accept-Ranges','bytes');self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(end-start+1))
        if match:self.send_header('Content-Range',f'bytes {start}-{end}/{total}')
        self.end_headers()
        try:
            with path.open('rb') as f:
                f.seek(start);remaining=end-start+1
                while remaining:
                    block=f.read(min(65536,remaining))
                    if not block:break
                    self.wfile.write(block);remaining-=len(block)
        except (BrokenPipeError,ConnectionResetError):pass

def measure(path,reference):
    image=np.asarray(Image.open(path).convert('RGB'));sh,sw=image.shape[:2]
    scale=min(sw/W,sh/H);left=(sw-W*scale)/2;top=(sh-H*scale)/2
    result=[]
    for i,name in enumerate(reference['names']):
        row,col=divmod(i,COLS);cx=round(left+(col*160+80)*scale);cy=round(top+(row*180+90)*scale);radius=max(3,round(20*scale))
        area=image[cy-radius:cy+radius,cx-radius:cx+radius]
        measured=np.median(area.reshape(-1,3),axis=0);expected=np.array(reference['expected'][i]);delta=np.abs(measured-expected)
        result.append(dict(name=name,expected=expected.tolist(),measured=measured.tolist(),difference=delta.tolist(),mean=float(delta.mean()),max=float(delta.max()),std=float(area.reshape(-1,3).std(axis=0).max())))
    return result

def main():
    print('GENERATING lossless PQ/HLG/SDR reference charts',flush=True)
    references={kind:fixture(kind) for kind in ('pq','hlg','sdr')}
    (OUT/'references.json').write_text(json.dumps(references,indent=2),encoding='utf-8')
    server=http.server.ThreadingHTTPServer(('127.0.0.1',39330),functools.partial(RangeHandler,directory=str(WORK)))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    adb('reverse','tcp:39330','tcp:39330',stdout=subprocess.DEVNULL)
    state=api('/api/state');saved=state['settings'];current=state['current'];results=[]
    changed=['playlistSources','hdrMode','decodeMode','videoScaleMode','showDebugInfo','showColorDebugInfo','showNetworkSpeed','showPlaybackPosition']
    try:
        playlist='#EXTM3U\n'+''.join(f'#EXTINF:-1 group-title="COLOUR_DIAG",COLOUR_{kind.upper()}\nhttp://127.0.0.1:39330/{kind}.mp4\n' for kind in references)
        uploaded=api('/api/playlist/upload?id=hdr_colour_diag&name=colour.m3u',playlist.encode())
        settings=dict(playlistSources=saved['playlistSources']+[dict(id='hdr_colour_diag',name='COLOUR_DIAG',location=uploaded['location'],enabled=True)],decodeMode='hardware',videoScaleMode='fit',showDebugInfo=False,showColorDebugInfo=False,showNetworkSpeed=False,showPlaybackPosition=False)
        api('/api/settings',settings)
        locations={}
        for _ in range(30):
            catalog=api('/api/catalog')
            for gi,group in enumerate(catalog['groups']):
                for ci,channel in enumerate(group['channels']):
                    if channel['name'].startswith('COLOUR_'):locations[channel['name']]=(gi,ci)
            if len(locations)==3:break
            time.sleep(.5)
        assert len(locations)==3,'Diagnostic channels not loaded'
        cases=[('pq','mapping'),('hlg','mapping'),('pq','hardware'),('hlg','hardware'),('sdr','mapping')]
        if os.environ.get('NTV_COLOUR_CASES'):cases=[tuple(case.split(':')) for case in os.environ['NTV_COLOUR_CASES'].split(',')]
        for kind,mode in cases:
            name='COLOUR_'+kind.upper();gi,ci=locations[name]
            api('/api/settings',dict(hdrMode=mode));api('/api/control',dict(action='play',group=gi,channel=ci))
            start=time.monotonic();m={}
            while time.monotonic()-start<20:
                m=api('/api/media?detail=1')
                if m.get('name')==name and m.get('playing') and m.get('outputFps',0)>0:break
                time.sleep(.25)
            assert m.get('playing') and m.get('name')==name,('Not playing',kind,mode,m)
            time.sleep(5);m=api('/api/media?detail=1')
            assert 'OMX.' in m.get('currentSourceStats',{}).get('videoDecoderName',''),'Hardware decode required'
            expected_mapping=os.environ.get('NTV_COLOUR_EXPECT_MAPPING')
            if expected_mapping and kind!='sdr':
                assert expected_mapping in m.get('currentSourceStats',{}).get('decoderColor',''),('Unexpected mapping profile',m)
                assert m.get('gpuTiming',{}).get('lut3d'),'3D upload must succeed for this comparison'
            # The chart is constant across frames. Capture during playback so
            # the pause notification cannot cover the measured colour blocks.
            screenshot=OUT/f'{kind}-{mode}.png'
            screenshot.write_bytes(adb('exec-out','screencap','-p',stdout=subprocess.PIPE).stdout)
            measured=measure(screenshot,references[kind]);errors=np.array([x['difference'] for x in measured])
            white_index=68 if kind=='pq' else 66 if kind=='hlg' else 67
            white=measured[white_index] if references[kind]['names'][white_index].startswith('anchor-') else None
            row=dict(kind=kind,mode=mode,screenshot=screenshot.name,media=m,patches=measured,mean_code_error=float(errors.mean()),max_code_error=float(errors.max()),reference_white=white)
            results.append(row);(OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
            print(kind,mode,'mean/max',round(errors.mean(),3),int(errors.max()),'fps',round(m.get('outputFps',0),2),'gpu',m.get('gpuTiming'),flush=True)
    finally:
        api('/api/settings',{k:saved[k] for k in changed if k in saved})
        try:api('/api/control',dict(action='play',group=current['groupIndex'],channel=current['channelIndex']))
        except Exception:pass
        server.shutdown();adb('reverse','--remove','tcp:39330',stdout=subprocess.DEVNULL)
        print('RESTORED original app settings and channel',flush=True)
if __name__=='__main__':main()
