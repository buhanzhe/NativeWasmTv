"""Shipping QuickJS/OkHttp: default Blob -> FileReader -> Blob send -> ArrayBuffer."""
import argparse,base64,hashlib,http.server,json,os,pathlib,socket,struct,subprocess,tempfile,threading

parser=argparse.ArgumentParser();parser.add_argument('--core',type=pathlib.Path,required=True)
args=parser.parse_args();core=args.core.resolve();received=[];failures=[]

def read_exact(stream,count):
    data=b''
    while len(data)<count:
        chunk=stream.read(count-len(data))
        if not chunk:raise EOFError()
        data+=chunk
    return data

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        if self.path!='/socket':
            body=b'''<!doctype html><script>
var workerCode="var canceled=false;var id=requestAnimationFrame(function(){canceled=true;});cancelAnimationFrame(id);var previous=-1,count=0;function frame(t){if(typeof document!=='undefined'||typeof window!=='undefined'||!isFinite(t)||t<previous||canceled)throw Error('Worker animation contract');previous=t;if(++count===2){postMessage({passed:true,count:count});close();}else requestAnimationFrame(frame);}requestAnimationFrame(frame);";
var worker=new Worker(URL.createObjectURL(new Blob([workerCode],{type:'text/javascript'})));
worker.onmessage=function(e){if(!e.data.passed||e.data.count!==2)throw Error('Worker animation failed');console.log('PASS worker-animation-clock');};
var ws=new WebSocket('ws://'+location.host+'/socket'),stage=0,readerChecks=false;
function check(value){if(!value)throw new Error('WebSocket binary contract');}
var canceled=new FileReader(),aborts=0,loads=0;
canceled.onabort=function(){aborts++;};canceled.onload=function(){loads++;};
canceled.readAsArrayBuffer(new Blob(['cancel']));
var busy=false;try{canceled.readAsText(new Blob(['busy']));}catch(error){busy=error.name==='InvalidStateError';}
canceled.abort();check(busy&&canceled.readyState===2&&canceled.result===null);
var sequential=new FileReader(),read=0,ends=0;
sequential.onloadend=function(){ends++;};sequential.onload=function(){
 if(read++===0){check(sequential.result==='hello');sequential.readAsDataURL(new Blob(['a'],{type:'text/plain'}));}
 else{check(sequential.result==='data:text/plain;base64,YQ==');setTimeout(function(){check(aborts===1&&loads===0&&ends===1);readerChecks=true;},0);}
};sequential.readAsText(new Blob(['hello']));
ws.onmessage=function(event){
 if(stage++===0){
  check(ws.binaryType==='blob'&&event.data instanceof Blob&&event.data.type==='');
  var reader=new FileReader();reader.onload=function(e){
   check(e.currentTarget===reader&&new TextDecoder().decode(reader.result)==='inbound');
   ws.binaryType='arraybuffer';ws.send(new Blob(['outbound']));
  };reader.readAsArrayBuffer(event.data);
 }else{check(event.data instanceof ArrayBuffer&&new TextDecoder().decode(event.data)==='ack');ws.close(1000,'done');}
};
ws.onclose=function(event){check(stage===2&&event.code===1000&&event.wasClean&&readerChecks);console.log('PASS socket-blob-roundtrip');};
ws.onerror=function(){console.error('WebSocket fixture transport failed');};
</script>'''
            self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
        try:
            self.connection.settimeout(5)
            accept=base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            self.send_response(101);self.send_header('Upgrade','websocket');self.send_header('Connection','Upgrade');self.send_header('Sec-WebSocket-Accept',accept);self.end_headers()
            self.wfile.write(b'\x82\x07inbound');self.wfile.flush()
            def frame():
                head=read_exact(self.rfile,2);length=head[1]&127
                if length==126:length=struct.unpack('!H',read_exact(self.rfile,2))[0]
                assert length<=1024 and head[1]&128
                mask=read_exact(self.rfile,4);payload=read_exact(self.rfile,length)
                return head[0]&15,bytes(v^mask[i%4] for i,v in enumerate(payload))
            opcode,payload=frame();assert opcode==2 and payload==b'outbound';received.append(payload)
            self.wfile.write(b'\x82\x03ack');self.wfile.flush()
            opcode,payload=frame();assert opcode==8 and payload==b'\x03\xe8done'
            self.wfile.write(bytes([0x88,len(payload)])+payload);self.wfile.flush()
        except Exception as error:failures.append(repr(error))

server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
try:
    with tempfile.TemporaryDirectory(prefix='ntv-websocket-') as directory:
        out=pathlib.Path(directory);cfg={'url':'http://127.0.0.1:'+str(server.server_port)+'/','seconds':4,'render':False,'headless':True,'memoryMiB':64,'captureDir':directory,'dns':'system'}
        (out/'config.json').write_text(json.dumps(cfg),'utf8')
        native=core/('ntvLiteDesktop.dll' if os.name=='nt' else 'libntvLiteDesktop.so')
        cp=os.pathsep.join(map(str,[core/'litecore.jar',*sorted((core/'lib').glob('*.jar'))]))
        run=subprocess.run(['java','-Xmx128m','-Dntv.library='+str(native),'-cp',cp,'xiao.bu.tv.DesktopMain',str(out/'config.json'),str(core/'classes/assets/bootstrap.js'),str(out/'report.json')],capture_output=True,timeout=15)
        report=json.loads((out/'report.json').read_text('utf8'))
        assert run.returncode==0 and not report.get('fatal') and report.get('javascriptErrors',0)==0,[(e.get('kind'),str(e.get('value'))[:500]) for e in report.get('events',[]) if e.get('kind') in ['error','hostError']]
        assert any('PASS socket-blob-roundtrip' in str(event.get('value')) for event in report['events']),report.get('events')
        assert any('PASS worker-animation-clock' in str(event.get('value')) for event in report['events']),report.get('events')
        assert received==[b'outbound'] and not failures,failures
    print('PASS real native WebSocket Blob/FileReader/send/ArrayBuffer round trip, independent Worker animation/cancel clocks and clean close')
finally:server.shutdown();server.server_close()
