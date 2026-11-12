"""Bounded local seven-day XMLTV/HLS fixture; archive pictures identify the day."""
import argparse,datetime,functools,http.server,json,pathlib,subprocess,time,urllib.parse,zoneinfo,os
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--epoch',type=int,required=True);a=p.parse_args()
root=a.output.resolve();root.mkdir(parents=True,exist_ok=True)
zone=zoneinfo.ZoneInfo('Asia/Shanghai');today=datetime.datetime.fromtimestamp(a.epoch,zone).replace(hour=0,minute=0,second=0,microsecond=0)
font=pathlib.Path('C:/Windows/Fonts/arial.ttf');(root/'font.ttf').write_bytes(font.read_bytes())
def encode(folder,label,offset):
 folder.mkdir(exist_ok=True)
 subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-f','lavfi','-i','testsrc2=size=320x180:rate=25','-f','lavfi','-i','sine=frequency='+str(440+offset*35)+':sample_rate=44100','-t','48','-vf',f"hue=h={offset*32},drawtext=fontfile=font.ttf:text='{label}':x=12:y=12:fontsize=24:fontcolor=white:box=1:boxcolor=black@0.8",'-c:v','libx264','-preset','ultrafast','-profile:v','baseline','-pix_fmt','yuv420p','-threads','1','-g','50','-c:a','aac','-ac','1','-b:a','48k','-f','hls','-hls_time','2','-hls_list_size','0','-hls_playlist_type','vod',str(folder/'index.m3u8')],cwd=root,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
for offset in range(7):
 if not (root/('archive-'+str(offset))/'index.m3u8').is_file():encode(root/('archive-'+str(offset)),f'ARCHIVE DAY -{offset}',offset)
live_folder=root/'live';live_folder.mkdir(exist_ok=True)
live=subprocess.Popen(['ffmpeg','-v','error','-nostdin','-y','-re','-f','lavfi','-i','testsrc2=size=320x180:rate=25','-f','lavfi','-i','sine=frequency=1000:sample_rate=44100','-t','900','-vf',"drawtext=fontfile=font.ttf:text='LIVE':x=12:y=12:fontsize=24:fontcolor=white:box=1:boxcolor=black@0.8",'-c:v','libx264','-preset','ultrafast','-profile:v','baseline','-pix_fmt','yuv420p','-threads','1','-g','50','-c:a','aac','-ac','1','-b:a','48k','-f','hls','-hls_time','2','-hls_list_size','8','-hls_flags','delete_segments+independent_segments',str(live_folder/'index.m3u8')],cwd=root,stdout=subprocess.DEVNULL,stderr=(root/'live-encoder.log').open('w'))
start=time.monotonic()
def xmlstamp(value):return value.astimezone(datetime.timezone.utc).strftime('%Y%m%d%H%M%S +0000')
def guide(kind):
 rows=['<?xml version="1.0" encoding="UTF-8"?><tv><channel id="seven"><display-name>七日回看测试</display-name></channel><channel id="broken"><display-name>失败保护测试</display-name></channel>']
 for offset in range(7):
  if kind=='single' and offset!=0 or kind=='sparse' and offset in [2,4]:continue
  day=today-datetime.timedelta(days=offset)
  for hour in range(24):
   begin=day+datetime.timedelta(hours=hour);end=begin+datetime.timedelta(hours=1);pid=f'd{offset}-h{hour}'
   rows.append(f'<programme channel="seven" start="{xmlstamp(begin)}" stop="{xmlstamp(end)}" catchup-id="{pid}"><title>第{offset}日前 {hour:02d}点节目</title></programme>')
 old=today-datetime.timedelta(days=9);rows.append(f'<programme channel="seven" start="{xmlstamp(old)}" stop="{xmlstamp(old+datetime.timedelta(hours=1))}"><title>过期节目</title></programme>')
 begin=today-datetime.timedelta(days=1)+datetime.timedelta(hours=1)
 rows.append(f'<programme channel="broken" start="{xmlstamp(begin)}" stop="{xmlstamp(begin+datetime.timedelta(hours=1))}"><title>失败保护节目</title></programme></tv>')
 return ''.join(rows).encode()
events=[]
class Handler(http.server.SimpleHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def __init__(self,*args,**kw):super().__init__(*args,directory=str(root),**kw)
 def log_message(self,*args):pass
 def reply(self,data,kind='application/vnd.apple.mpegurl',status=200):
  self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers()
  try:self.wfile.write(data)
  except (BrokenPipeError,ConnectionResetError):pass
 def do_GET(self):
  parsed=urllib.parse.urlsplit(self.path);query=urllib.parse.parse_qs(parsed.query);base='http://'+self.headers['Host']
  if parsed.path=='/channels.m3u':
   text=f'''#EXTM3U
#EXTINF:-1 tvg-id="seven" group-title="七日测试" catchup="default" catchup-days="7" catchup-source="{base}/archive.m3u8?line=1&start={{utc}}&end={{utcend}}&program={{catchup-id}}",七日回看测试
{base}/live.m3u8?line=1
#EXTINF:-1 tvg-id="seven" group-title="七日测试" catchup="append" catchup-days="7" catchup-source="?start={{utc}}&end={{utcend}}&program={{catchup-id}}",七日回看测试
{base}/live.m3u8?line=2
#EXTINF:-1 tvg-id="broken" group-title="七日测试" catchup="default" catchup-days="7" catchup-source="{base}/missing.m3u8?start={{utc}}&end={{utcend}}",失败保护测试
{base}/live.m3u8?line=3
''';return self.reply(text.encode(),'audio/x-mpegurl')
  if parsed.path.startswith('/guide'):
   kind='single' if 'single' in parsed.path else 'sparse' if 'sparse' in parsed.path else 'all';events.append({'kind':'guide','variant':kind});return self.reply(guide(kind),'application/xml')
  if parsed.path=='/config.json':return self.reply(json.dumps({'today':int(today.timestamp()*1000),'dates':[int((today-datetime.timedelta(days=i)).timestamp()*1000) for i in range(6,-1,-1)]}).encode(),'application/json')
  if parsed.path=='/events.json':return self.reply(json.dumps(events).encode(),'application/json')
  if parsed.path=='/missing.m3u8':events.append({'kind':'denied','status':404});return self.reply(b'','text/plain',404)
  if parsed.path in ['/live.m3u8','/archive.m3u8']:
   archive='start' in query
   if archive:
    requested=int(query['start'][0]);day=datetime.datetime.fromtimestamp(requested,zone).replace(hour=0,minute=0,second=0,microsecond=0);offset=(today-day).days
    hour=datetime.datetime.fromtimestamp(requested,zone).hour;pid=f'd{offset}-h{hour}'
    if not 0<=offset<7 or query.get('program',[''])[0]!=pid or int(query['end'][0])!=requested+3600:return self.reply(b'','text/plain',400)
    folder=f'archive-{offset}';body=(root/folder/'index.m3u8').read_text();pdt=datetime.datetime.fromtimestamp(requested,datetime.timezone.utc).isoformat().replace('+00:00','Z')
    body=body.replace('#EXTINF:',f'#EXT-X-PROGRAM-DATE-TIME:{pdt}\n#EXTINF:',1)
    events.append({'kind':'archive','offset':offset,'line':query.get('line',[''])[0],'start':requested,'program':pid})
   else:folder='live';body=(root/folder/'index.m3u8').read_text();events.append({'kind':'live','line':query.get('line',[''])[0]})
   lines=[base+'/'+folder+'/'+line if line and not line.startswith('#') else line for line in body.splitlines()]
   return self.reply(('\n'.join(lines)+'\n').encode())
  return super().do_GET()
server=http.server.ThreadingHTTPServer(('0.0.0.0',0),Handler)
meta={'port':server.server_port,'pid':os.getpid(),'ffmpegPid':live.pid,'today':int(today.timestamp()*1000),'controlledFixture':True}
(root/'server.json').write_text(json.dumps(meta),'utf8');print(json.dumps(meta),flush=True)
try:server.serve_forever()
finally:
 server.server_close();live.terminate();live.wait(timeout=5);(root/'events.json').write_text(json.dumps(events,indent=2),'utf8')
