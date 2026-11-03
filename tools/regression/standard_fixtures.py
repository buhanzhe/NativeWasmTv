"""Deterministic media and HTTP fault fixtures for standard_regression.py."""
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import subprocess
import threading
import time
from urllib.parse import urlsplit
import wave


def prepare(directory, ffmpeg, hevc=False):
    from PIL import Image, ImageDraw
    directory.mkdir(parents=True, exist_ok=True)
    video = directory / 'video.mp4'
    command = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-y',
               '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25',
               '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100',
               '-t', '40', '-c:v', 'libx264', '-profile:v', 'baseline', '-level', '3.0',
               '-pix_fmt', 'yuv420p', '-preset', 'ultrafast', '-g', '50', '-b:v', '550k',
               '-c:a', 'aac', '-ac', '2', '-b:a', '96k', '-movflags', '+faststart', str(video)]
    subprocess.run(command, check=True, timeout=120, capture_output=True)
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-i', str(video),
                    '-c', 'copy', '-hls_time', '2', '-hls_list_size', '0',
                    '-hls_segment_filename', str(directory / 'seg%02d.ts'),
                    str(directory / 'video.m3u8')], check=True, timeout=60, capture_output=True)
    for ext in ('jpg', 'png'):
        image = Image.new('RGB', (1280, 720), (20, 70, 120))
        ImageDraw.Draw(image).rectangle((100, 100, 1180, 620), fill=(240, 180, 40))
        image.save(directory / ('image.' + ext))
    for channels, rate, name in [(1, 22050, 'mono.wav'), (2, 44100, 'stereo.wav')]:
        with wave.open(str(directory / name), 'wb') as output:
            output.setnchannels(channels)
            output.setsampwidth(2)
            output.setframerate(rate)
            output.writeframes(bytes(rate * channels * 2 * 20))
    (directory / 'page.html').write_text(
        '<!doctype html><meta charset="utf-8"><style>html,body,video{margin:0;'
        'width:100%;height:100%;background:black}</style><video autoplay muted '
        'playsinline controls src="video.mp4"></video>', encoding='utf8')
    if hevc:
        subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-y','-f','lavfi','-i',
                        'testsrc2=size=3840x2160:rate=1','-f','lavfi','-i','sine=sample_rate=44100',
                        '-t','8','-c:v','libx265','-preset','ultrafast','-x265-params',
                        'pools=1:frame-threads=1:log-level=error','-pix_fmt','yuv420p',
                        '-c:a','aac','-ac','2','-hls_time','2','-hls_list_size','0',
                        '-hls_segment_filename',str(directory/'hevc%02d.ts'),str(directory/'hevc.m3u8')],
                       check=True,timeout=120,capture_output=True)


class FixtureServer:
    def __init__(self, directory, host, port):
        self.directory = Path(directory).resolve()
        self.requests = []
        self.healthy_epg = set()
        self.started = time.monotonic()
        self.lock = threading.Lock()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                path = urlsplit(self.path).path
                with owner.lock:
                    owner.requests.append({'time': time.time(), 'path': self.path,
                                           'peer': self.client_address[0]})
                if path == '/health':
                    return self.reply(200, b'ntv-standard-fixture', 'text/plain')
                if path == '/live.m3u8':
                    seq=int((time.monotonic()-owner.started)/2)
                    rows=['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-TARGETDURATION:2',
                          '#EXT-X-MEDIA-SEQUENCE:'+str(seq)]
                    for n in range(seq,seq+6):
                        if n%20==0:rows.append('#EXT-X-DISCONTINUITY')
                        rows += ['#EXTINF:2.000,',f'/seg{n%20:02}.ts?sequence={n}']
                    return self.reply(200,('\n'.join(rows)+'\n').encode(),'application/vnd.apple.mpegurl')
                if path == '/corrupt.m3u8':
                    body=b'#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\n/missing.ts\n#EXTINF:5,\n/corrupt.ts\n#EXT-X-ENDLIST\n'
                    return self.reply(200,body,'application/vnd.apple.mpegurl')
                if path == '/corrupt.ts':
                    return self.reply(200,b'corrupt TS segment'*1024,'video/mp2t')
                if path.startswith('/epg/'):
                    token = path.rsplit('/', 1)[-1]
                    with owner.lock:
                        healthy = token in owner.healthy_epg
                    if not healthy:
                        return self.reply(503, b'Injected EPG failure', 'text/plain')
                    now = datetime.now(timezone.utc)
                    a = (now - timedelta(hours=1)).strftime('%Y%m%d%H%M%S +0000')
                    b = (now + timedelta(hours=2)).strftime('%Y%m%d%H%M%S +0000')
                    xml = ('<?xml version="1.0" encoding="UTF-8"?><tv>'
                           '<channel id="CCTV1"><display-name>CCTV-1</display-name></channel>'
                           f'<programme channel="CCTV1" start="{a}" stop="{b}">'
                           '<title>Standard regression</title></programme></tv>')
                    return self.reply(200, xml.encode(), 'application/xml')
                if path == '/slow.m3u8':
                    time.sleep(30)
                    return self.reply(504, b'Injected timeout', 'text/plain')
                file = (owner.directory / path.lstrip('/')).resolve()
                if not file.is_relative_to(owner.directory) or not file.is_file():
                    return self.reply(404, b'Not found', 'text/plain')
                types = {'.mp4': 'video/mp4', '.m3u8': 'application/vnd.apple.mpegurl',
                         '.ts': 'video/mp2t', '.html': 'text/html; charset=utf-8'}
                body = file.read_bytes()
                status, headers = 200, {}
                range_header = self.headers.get('Range', '')
                if range_header.startswith('bytes='):
                    try:
                        a, b = range_header[6:].split('-', 1)
                        if a:
                            start = int(a)
                            end = min(int(b) if b else len(body) - 1, len(body) - 1)
                        else:
                            start, end = max(0, len(body) - int(b)), len(body) - 1
                        if start < 0 or start > end or start >= len(body):
                            raise ValueError('Invalid range')
                    except ValueError:
                        return self.reply(416, b'', 'text/plain',
                                          {'Content-Range': f'bytes */{len(body)}'})
                    headers['Content-Range'] = f'bytes {start}-{end}/{len(body)}'
                    body, status = body[start:end + 1], 206
                return self.reply(status, body, types.get(file.suffix, 'application/octet-stream'), headers)

            def reply(self, status, body, content_type, headers=None):
                self.send_response(status)
                for key, value in {'Content-Type': content_type, 'Content-Length': str(len(body)),
                                   'Cache-Control': 'no-store', 'Accept-Ranges': 'bytes',
                                   **(headers or {})}.items():
                    self.send_header(key, value)
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer((host, port), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def save(self, path):
        with self.lock:
            Path(path).write_text(json.dumps(self.requests, indent=2), encoding='utf8')
