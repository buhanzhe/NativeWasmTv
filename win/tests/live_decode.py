"""Observe real video across source handoffs from one production page session."""
import json,queue,subprocess,threading,time,struct

def observe(media,vm,messages,folder,row,seconds,command):
    deadline=time.monotonic()+seconds+45
    decoded=frames=0;last_advance=None;max_gap=0
    row['decoder_generations']=[]
    for index in range(64):
        frame=folder/('frame.png' if index==0 else 'frame-generation-'+str(index+1)+'.png')
        stage_time=stage_frames=0;metadata_sent=False;pending=None;ended=None;decoder=None
        generation=media.get('generation',index+1)
        try:
            with (folder/'decode.log').open('a',encoding='utf8') as log:
                log.write('Generation '+str(generation)+'\n');log.flush()
                decoder=subprocess.Popen(command(media,frame,max(.01,seconds-decoded)),stdout=subprocess.PIPE,stderr=log,text=True)
                progress=queue.Queue()
                def reader(process=decoder,output=progress):
                    for line in process.stdout:output.put(line.strip())
                    output.put(None)
                threading.Thread(target=reader,daemon=True).start()
                while time.monotonic()<deadline:
                    while True:
                        try:message=messages.get_nowait()
                        except queue.Empty:break
                        if message.startswith('FAILED_JSON '):raise RuntimeError(json.loads(message[12:])['message'])
                        if message.startswith('MEDIA_JSON '):
                            candidate=json.loads(message[11:])
                            if candidate['url']!=media['url']:pending=candidate
                    if pending:break
                    now=time.monotonic()
                    if last_advance is not None:
                        max_gap=max(max_gap,round((now-last_advance)*1000));row['max_video_stall_ms']=max_gap
                        if seconds>=30 and now-last_advance>=6:raise RuntimeError('Video decoder stalled for at least 6000 ms')
                    if ended is not None:
                        if decoded+stage_time>=seconds-.5 or now-ended>=6:break
                        time.sleep(.05);continue
                    try:line=progress.get(timeout=.2)
                    except queue.Empty:continue
                    if line is None:ended=time.monotonic();continue
                    if not metadata_sent and frame.exists():
                        raw=frame.read_bytes()[:24]
                        if len(raw)==24 and raw[:8]==b'\x89PNG\r\n\x1a\n':
                            width,height=struct.unpack('!II',raw[16:24]);vm.stdin.write('SIZE '+str(generation)+' '+str(width)+' '+str(height)+'\n');vm.stdin.flush();metadata_sent=True
                    if line.startswith('out_time_us='):
                        try:
                            stage_time=max(stage_time,max(0,int(line.split('=',1)[1])/1e6));row['decoded_seconds']=decoded+stage_time
                            vm.stdin.write('TIME '+str(generation)+' '+str(stage_time)+'\n');vm.stdin.flush()
                        except (ValueError,BrokenPipeError):pass
                    elif line.startswith('frame='):
                        try:
                            value=int(line.split('=',1)[1])
                            if value>stage_frames:stage_frames=value;last_advance=time.monotonic()
                            row['video_frames']=frames+stage_frames
                        except ValueError:pass
                else:raise RuntimeError('Decoder timeout')
                row['decoder_generations'].append({'generation':generation,'decoded_seconds':stage_time,'video_frames':stage_frames,'replaced':bool(pending)})
                if not pending:
                    decoder.wait(timeout=5)
                    if decoder.returncode!=0:raise RuntimeError('Decoder failed with code '+str(decoder.returncode)+'; see decode.log')
        finally:
            if decoder is not None and decoder.poll() is None:
                decoder.terminate()
                try:decoder.wait(timeout=3)
                except subprocess.TimeoutExpired:decoder.kill();decoder.wait()
            if decoder is not None and decoder.stdout is not None:decoder.stdout.close()
        decoded+=stage_time;frames+=stage_frames
        if not pending:return
        media=pending;row['source_changes']=index+1
    raise RuntimeError('Excessive media source replacements')
