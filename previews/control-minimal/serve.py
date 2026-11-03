"""Local preview server with two fixed, read-only device endpoints."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.request import urlopen
from urllib.parse import urlsplit, parse_qs
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parent
class Handler(SimpleHTTPRequestHandler):
    def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(ROOT),**kwargs)
    def do_GET(self):
        u=urlsplit(self.path)
        if u.path.startswith('/device/') or u.path.startswith('/api/'):
            if u.path.startswith('/api/'):
                target=self.path
            elif u.path=='/device/system': target='/api/state?view=system'
            elif u.path=='/device/logs':
                q=parse_qs(u.query);f=q.get('filter',['all'])[0]
                if f not in ('all','error','crash'):self.send_error(400);return
                target='/api/logs?filter='+f+('&download=1' if q.get('download')==['1'] else '')
            else:self.send_error(404);return
            try:
                with urlopen('http://192.168.0.106:9966'+target,timeout=8) as r:
                    body=r.read();ctype=r.headers.get('Content-Type','application/json; charset=utf-8');attachment=r.headers.get('Content-Disposition')
                self.send_response(200);self.send_header('Content-Type',ctype)
                if attachment:self.send_header('Content-Disposition',attachment)
                self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
            except Exception:
                body=json.dumps({'message':'无法连接 192.168.0.106:9966，请确认设备在线。'},ensure_ascii=False).encode('utf-8')
                self.send_response(502);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        else:super().do_GET()
if __name__=='__main__':
    print('Preview: http://127.0.0.1:18763/ (device reads: 192.168.0.106:9966)',flush=True)
    ThreadingHTTPServer(('127.0.0.1',18763),Handler).serve_forever()
