import http.server,threading,ssl,json,urllib.parse,pathlib,socket,re,time,argparse,struct,zlib,base64,hashlib
args=argparse.ArgumentParser();args.add_argument('--cert',required=True,type=pathlib.Path);args.add_argument('--key',required=True,type=pathlib.Path);args.add_argument('--video',required=True,type=pathlib.Path);args.add_argument('--host',help='Advertised host; 127.0.0.1 works with adb reverse');args.add_argument('--dash-fallbacks',action='store_true',help='Reject both DASH primary streams to verify supplied video/audio backups');args.add_argument("--dash-reject-all",action="store_true",help="Reject all DASH mirrors; progressive media remains available")
args.add_argument('--premium-dash',action='store_true',help='Advertise a test HEVC clip as 8K and expose Dolby and Hi-Res tracks')
args.add_argument('--dash-resume',action='store_true',help='Cut the video response, reject one range mirror and the default AAC track')
options=args.parse_args()
ROOT=pathlib.Path(__file__).resolve().parents[3]
work=ROOT/'.codex-tmp/bl';work.mkdir(parents=True,exist_ok=True)
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(('1.1.1.1',80));host=s.getsockname()[0];s.close()
if options.host:host=options.host
base=f'http://{host}:39626'
faults={'mode':''};fault_lock=threading.Lock()
def dash_track(track_id,filename,codecs,bandwidth,*,reject=False,snake_case=False):
    address=f'{base}/{filename}?track={track_id}'
    primary=address+('&fail=1' if options.dash_fallbacks or options.dash_reject_all or reject else '')
    backup=address+'&backup=1'+('&fail=1' if options.dash_reject_all or reject else '')
    return {
        'id':track_id,'codecs':codecs,'bandwidth':bandwidth,
        'base_url' if snake_case else 'baseUrl':primary,
        'backup_url' if snake_case else 'backupUrl':[backup],
    }
def video(i=0):return dict(bvid='BV1xx411c7mD' if i==0 else 'BV'+str(1000000000+i),aid=1000+i,cid=2000+i,title=f'BL 原生兼容测试 {i+1:02d}',pic=base+'/cover.png',duration=40,owner=dict(mid=123,name='Native View 测试'),stat=dict(view=123456+i,danmaku=17),desc='本地测试视频，验证单 View 界面、共享 OkHttp、遥控器和旧系统播放。',pages=[dict(cid=2000+i,page=1,part='第一集'),dict(cid=3000+i,page=2,part='第二集')])
def varint(n):
 data=bytearray()
 while n>127:data.append((n&127)|128);n>>=7
 data.append(n);return bytes(data)
def field(tag,value):return varint(tag)+((varint(len(value))+value) if isinstance(value,bytes) else varint(value))
def danmaku_segment(index):
 result=b''
 for i in range(15):
  mode=[1,5,4][i%3];at=(index-1)*10000+300+(i//3)*1900
  text=f'{["滚动弹幕","顶部固定","底部固定"][i%3]} · 分段{index} / {i}'.encode()
  result+=field(10,field(8,index*100+i)+field(16,at)+field(24,mode)+field(32,25)+field(40,[0xffffff,0xaaccff,0x88ff88][i%3])+field(58,text)+field(72,10))
 return result
def live_packet(op,body,version=1):return struct.pack('>IHHII',16+len(body),16,version,op,1)+body
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,fmt,*args):pass
 def do_HEAD(self):self.do_GET()
 def response(self,code,data,typ='application/json'):
  if not isinstance(data,bytes):data=json.dumps(data,ensure_ascii=False).encode()
  self.send_response(code);self.send_header('Content-Type',typ);self.send_header('Content-Length',len(data));self.end_headers()
  if self.command!='HEAD':self.wfile.write(data)
 def do_GET(self):
  try:self.handle_get()
  except (BrokenPipeError,ConnectionResetError):pass
 def handle_get(self):
  p=urllib.parse.urlsplit(self.path);q=urllib.parse.parse_qs(p.query);page=int(q.get('pn',q.get('page',q.get('fresh_idx',['1'])))[0]);offset=(page-1)*24
  with (work/'requests.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps({'time':time.time(),'method':self.command,'path':self.path},ensure_ascii=False)+'\n')
  if p.path=='/__fixture/fault':
   with fault_lock:faults['mode']=q.get('mode',[''])[0]
   return self.response(200,{'code':0})
  if p.path=='/x/web-interface/popular':
   with fault_lock:mode=faults['mode'];faults['mode']=''
   if mode=='http':
    self.send_response(503);self.send_header('X-Bili-Trace-Id','bl-fixture-503');self.send_header('Content-Type','text/html');self.send_header('Content-Length','0');self.end_headers();return
   if mode=='json':return self.response(200,b'<html>unavailable</html>','text/html')
   if mode=='business':return self.response(200,{'code':-352,'message':'站点要求验证 token=fixture-secret-token','data':{}})
  if p.path in ['/x/player/playurl','/x/player/wbi/playurl']:
   with fault_lock:media_fault=faults['mode']=='media';faults['mode']='' if media_fault else faults['mode']
   if media_fault:return self.response(200,{'code':0,'data':{'quality':32,'durl':[{'url':base+'/video.mp4?fail=1&token=fixture-secret-token','length':40000}]}})
  if p.path=='/sub':
   key=self.headers.get('Sec-WebSocket-Key','');accept=base64.b64encode(hashlib.sha1((key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
   self.connection.sendall(('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+'\r\n\r\n').encode())
   self.connection.settimeout(6)
   def exact(size):
    data=b''
    while len(data)<size:
     part=self.rfile.read(size-len(data))
     if not part:raise ConnectionResetError()
     data+=part
    return data
   head=exact(2);length=head[1]&127
   if length==126:length=struct.unpack('>H',exact(2))[0]
   if length==127:length=struct.unpack('>Q',exact(8))[0]
   if length>4096 or not head[1]&128:raise ConnectionResetError()
   mask=exact(4);data=exact(length);data=bytes(b^mask[i%4] for i,b in enumerate(data));auth=json.loads(data[16:]);valid=struct.unpack_from('>I',data,8)[0]==7 and auth.get('roomid')==42 and auth.get('protover')==2 and auth.get('key')=='fixture'
   if not valid:raise ConnectionResetError()
   with (work/'requests.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps({'time':time.time(),'event':'ws_auth','room':42,'protover':2})+'\n')
   def frame(data):return b'\x82'+(bytes([len(data)]) if len(data)<126 else b'\x7e'+struct.pack('>H',len(data)))+data
   self.connection.sendall(frame(live_packet(8,b'{"code":0}')))
   for i in range(60):
    time.sleep(1);body=json.dumps({'cmd':'DANMU_MSG','info':[[0,1,25,0xffffff],f'直播弹幕测试 {i}',[1,'fixture']]},ensure_ascii=False).encode();self.connection.sendall(frame(live_packet(5,zlib.compress(live_packet(5,body)),2)))
   self.close_connection=True;return
  if p.path=='/bl.jar':return self.response(200,(ROOT/'plugins/bl/dist/bl.jar').read_bytes(),'application/java-archive')
  if p.path=='/cover.png':return self.response(200,(ROOT/'plugins/bl/assets/background.png').read_bytes(),'image/png')
  if p.path in ['/video.mp4','/video-only.mp4','/audio-only.m4a','/hevc.mp4','/hi-res.m4a','/dolby.m4a']:
   if q.get('fail',['0'])[0]=='1':return self.response(403,b'fixture CDN failure','text/plain')
   file=options.video if p.path=='/video.mp4' else options.video.with_name(p.path[1:]);size=file.stat().st_size;a=0;b=size-1;match=re.match(r'bytes=(\d+)-(\d*)',self.headers.get('Range',''))
   if match:a=int(match[1]);b=min(size-1,int(match[2])) if match[2] else size-1
   if match and q.get('window'):b=min(b,a+524287)
   if a>=size:return self.response(416,b'')
   if options.dash_resume and p.path!='/video.mp4':
    if not match:return self.response(403,b'Range required','text/plain')
    if 'Windows NT 10.0' not in self.headers.get('User-Agent','') or self.headers.get('Referer')!='https://www.bilibili.com/':
     return self.response(403,b'Web DASH headers required','text/plain')
   self.send_response(206 if match else 200);self.send_header('Content-Type','video/mp4');self.send_header('Accept-Ranges','bytes');self.send_header('Content-Length',b-a+1)
   if match:self.send_header('Content-Range',f'bytes {a+1 if q.get("badRange") else a}-{b}/{size}')
   self.end_headers()
   if self.command!='HEAD':
    with file.open('rb') as f:
     f.seek(a);left=min(131072,b-a+1) if q.get('cut') else b-a+1
     while left:
      data=f.read(min(left,32768))
      if not data:break
      self.wfile.write(data);left-=len(data)
   return
  data={};code=0
  if p.path=='/x/frontend/finger/spi':data={'b_3':'native-device-fixture'}
  elif p.path=='/x/web-interface/nav':code=-101;data={'isLogin':False,'wbi_img':{'img_url':'https://i0.hdslb.com/bfs/wbi/'+'a'*32+'.png','sub_url':'https://i0.hdslb.com/bfs/wbi/'+'b'*32+'.png'}}
  elif p.path=='/x/web-interface/region/feed/rcmd':
   data={'items':[dict(video(0),title='分区视频 '+q.get('from_region',[''])[0])]}
  elif p.path.endswith('/feed/rcmd'):data={'item':[video(i) for i in range(offset,min(offset+24,72))]}
  elif p.path=='/x/web-interface/popular':data={'list':[video(i) for i in range(offset,min(offset+24,72))]}
  elif p.path=='/x/web-interface/view':data=video(0)
  elif p.path.endswith('/search/type'):
   kind=q.get('search_type',['video'])[0]
   if kind=='live_room':data={'result':[dict(roomid=42,title='直播搜索结果',uname='fixture',cover=base+'/cover.png')]}
   else:data={'result':[video(i+4) for i in range(12)]}
  elif p.path=='/x/web-interface/wbi/search/square':data={'trending':{'list':[{'keyword':'BL 热搜示例'}]}}
  elif p.path=='/pgc/season/index/result':
   name={'2':'电影','3':'纪录片','5':'电视剧'}.get(q.get('season_type',['1'])[0],'番剧')
   data={'list':[dict(season_id=101,title=name+'分类测试',cover=base+'/cover.png')]}
  elif p.path=='/x/space/bangumi/follow/list':
   name='追番' if q.get('type',['1'])[0]=='1' else '追剧'
   data={'list':[dict(season_id=101,title=name+'测试剧集',cover=base+'/cover.png')]}
  elif p.path=='/x/web-interface/archive/related':return self.response(200,dict(code=0,data=[video(i+1) for i in range(8)]))
  elif p.path in ['/x/player/playurl','/x/player/wbi/playurl','/pgc/player/web/playurl','/pgc/player/web/v2/playurl']:
   quality=int(q.get('qn',['32'])[0]);data={'quality':quality,'format':'mp4','accept_quality':[16,32,64],'durl':[{'url':base+'/video.mp4?cid='+q.get('cid',['0'])[0],'backup_url':[base+'/video.mp4?backup=1'],'length':40000,'size':(options.video).stat().st_size}]}
   fnval=q.get('fnval',['1'])[0]
   if fnval in ('16','4048'):
    if options.dash_resume and 'Windows NT 10.0' not in self.headers.get('User-Agent',''):
     return self.response(403,b'Web playurl headers required','text/plain')
    data.pop('durl')
    dash={'duration':40,'video':[dict(dash_track(i,'video-only.mp4','avc1.42c01e',600000),codecid=7) for i in (16,32,64)],
          'audio':[dash_track(30216,'audio-only.m4a','mp4a.40.2',64000,snake_case=True)]}
    data['dash']=dash
    if options.dash_resume:
     for track in dash['video']:
      mirror=track['backupUrl'][0]
      track['baseUrl']+='&cut=1'
      track['backupUrl']=[mirror+'&badRange=1']
      track['backup_url']=[mirror+'&window=1']
     dash['audio'][0]['baseUrl']='file:///invalid-audio'
     dash['audio'][0].pop('base_url')
     dash['audio'].insert(0,dash_track(30280,'audio-only.m4a','mp4a.40.2',192000,reject=True))
    with fault_lock:mode=faults['mode']
    if options.premium_dash and fnval=='4048' and quality==127 and q.get('fourk')==['1']:
     data['accept_quality'].append(127)
     if mode!='no_premium':
      dash['video'].append(dict(dash_track(127,'hevc.mp4','hvc1.1.6.L120.90',2000000,reject=mode=='reject_8k'),codecid=12))
      dash['dolby']={'type':2,'audio':[dash_track(i,'dolby.m4a','ec-3',384000,snake_case=True) for i in (30250,30255)]}
      dash['flac']={'display':True,'audio':dash_track(30251,'hi-res.m4a','fLaC',2000000,snake_case=True)}
    if p.path=='/pgc/player/web/v2/playurl':
     if mode=='pgc_v1':return self.response(200,dict(code=-404,message='fixture v2 unavailable'))
     return self.response(200,dict(code=0,result={'video_info':data}))
  elif p.path=='/x/v2/dm/web/view':return self.response(200,field(34,field(8,10000)+field(16,4)),'application/octet-stream')
  elif p.path=='/x/v2/dm/wbi/web/seg.so':return self.response(200,danmaku_segment(int(q['segment_index'][0])),'application/octet-stream')
  elif p.path=='/x/v1/dm/list.so':return self.response(200,b'<i><d p="2,1,25,16777215">Native View danmaku</d><d p="5,1,25,123456">API14 + OkHttp</d></i>','text/xml')
  elif p.path=='/x/player/wbi/v2':data={'subtitle':{'subtitles':[{'lan':'zh-CN','lan_doc':'中文测试字幕','subtitle_url':base+'/subtitle.json'}]}}
  elif p.path=='/subtitle.json':return self.response(200,{'body':[{'from':0,'to':20,'content':'BL 字幕测试'}]})
  elif p.path=='/x/passport-login/web/qrcode/generate':data={'url':'https://passport.bilibili.com/qrcode/h5/login?qrcode_key=fixture','qrcode_key':'fixture'}
  elif p.path=='/x/passport-login/web/qrcode/poll':data={'code':86101,'message':'等待扫码'}
  elif p.path=='/x/v2/reply':data={'replies':[{'member':{'uname':'测试用户'},'content':{'message':'原生评论界面'},'like':12}]}
  elif p.path=='/xlive/web-interface/v1/webMain/getMoreRecList':data={'recommend_room_list':[{'roomid':42,'title':'直播弹幕测试','uname':'fixture','cover':base+'/cover.png','user_cover':base+'/cover.png'}]}
  elif p.path=='/room/v1/area/getList':data=[{'id':2,'name':'网游'},{'id':3,'name':'手游'}]
  elif p.path=='/room/v1/Area/getRoomList':data=[{'roomid':42,'title':'直播分区 '+q.get('parent_area_id',[''])[0],'uname':'fixture','cover':base+'/cover.png'}]
  elif p.path=='/room/v1/Room/playUrl':data={'durl':[{'url':base+'/video.mp4','length':40000}]}
  elif p.path=='/xlive/web-room/v2/index/getRoomPlayInfo':
   quality=int(q.get('qn',['0'])[0]) or 80;codec={'codec_name':'avc','current_qn':quality,'accept_qn':[80,10000],'base_url':'/video.mp4','url_info':[{'host':base,'extra':'?live=1&fail=1'},{'host':base,'extra':'?live=1&backup=1'}]};data={'playurl_info':{'playurl':{'g_qn_desc':[{'qn':80,'desc':'流畅'},{'qn':10000,'desc':'原画'}],'stream':[{'protocol_name':'http_stream','format':[{'format_name':'flv','codec':[codec]}]},{'protocol_name':'http_hls','format':[{'format_name':'ts','codec':[codec]}]}]}}}
  elif p.path=='/xlive/web-room/v1/index/getDanmuInfo':data={'token':'fixture','host_list':[{'host':host,'wss_port':39627}]}
  elif p.path=='/xlive/web-room/v1/dM/gethistory':data={'room':[{'text':'直播兼容弹幕 '+str(i),'uid':i,'timeline':str(int(time.time())//3)} for i in range(2)]}
  else:code=-404;data={}
  self.response(200,dict(code=code,message='fixture',data=data))
 def do_POST(self):
  length=int(self.headers.get('Content-Length','0'));self.rfile.read(length);self.response(200,dict(code=0,data={}))
http_server=http.server.ThreadingHTTPServer(('0.0.0.0',39626),Handler)
https_server=http.server.ThreadingHTTPServer(('0.0.0.0',39627),Handler)
tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.minimum_version=ssl.TLSVersion.TLSv1;tls.set_ciphers('ALL:@SECLEVEL=0');tls.load_cert_chain(options.cert,options.key);https_server.socket=tls.wrap_socket(https_server.socket,server_side=True)
threading.Thread(target=http_server.serve_forever,daemon=True).start();print(json.dumps({'host':host,'api':base,'jar':f'https://{host}:39627/bl.jar'}),flush=True);https_server.serve_forever()
