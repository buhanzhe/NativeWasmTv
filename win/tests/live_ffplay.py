"""Actual bundled FFplay/SDL output, with native decoder clocks and owned-window capture."""
import ctypes,json,queue,re,struct,subprocess,threading,time,psutil
from ctypes import wintypes as W


def capture(pid,path):
    from PIL import Image,ImageStat
    user=ctypes.WinDLL('user32',use_last_error=True);gdi=ctypes.WinDLL('gdi32',use_last_error=True)
    callback=ctypes.WINFUNCTYPE(W.BOOL,W.HWND,W.LPARAM);found=[]
    @callback
    def each(hwnd,_):
        owner=W.DWORD();user.GetWindowThreadProcessId(hwnd,ctypes.byref(owner))
        if owner.value==pid and user.IsWindowVisible(hwnd):found.append(hwnd)
        return True
    user.EnumWindows(each,0)
    if len(found)!=1:return False
    hwnd=found[0];rect=W.RECT();user.GetClientRect(hwnd,ctypes.byref(rect));width,height=rect.right,rect.bottom
    if width<100 or height<100 or width*height>12000000:return False
    user.GetDC.argtypes=[W.HWND];user.GetDC.restype=W.HDC;user.ReleaseDC.argtypes=[W.HWND,W.HDC]
    gdi.CreateCompatibleDC.argtypes=[W.HDC];gdi.CreateCompatibleDC.restype=W.HDC
    gdi.CreateCompatibleBitmap.argtypes=[W.HDC,ctypes.c_int,ctypes.c_int];gdi.CreateCompatibleBitmap.restype=W.HBITMAP
    gdi.SelectObject.argtypes=[W.HDC,W.HANDLE];gdi.SelectObject.restype=W.HANDLE
    gdi.DeleteObject.argtypes=[W.HANDLE];gdi.DeleteDC.argtypes=[W.HDC]
    user.PrintWindow.argtypes=[W.HWND,W.HDC,W.UINT];user.PrintWindow.restype=W.BOOL
    gdi.GetDIBits.argtypes=[W.HDC,W.HBITMAP,W.UINT,W.UINT,ctypes.c_void_p,ctypes.c_void_p,W.UINT]
    dc=user.GetDC(hwnd);memory=gdi.CreateCompatibleDC(dc);bitmap=gdi.CreateCompatibleBitmap(dc,width,height);old=gdi.SelectObject(memory,bitmap)
    try:
        if not user.PrintWindow(hwnd,memory,3):return False
        info=struct.pack('<IiiHHIIiiII',40,width,-height,1,32,0,width*height*4,0,0,0,0);buffer=ctypes.create_string_buffer(width*height*4)
        if not gdi.GetDIBits(memory,bitmap,0,height,buffer,ctypes.c_char_p(info),0):return False
        image=Image.frombytes('RGB',(width,height),buffer.raw,'raw','BGRX');stats=ImageStat.Stat(image)
        if max(stats.stddev)<3 and (max(stats.mean)<8 or min(stats.mean)>247):return False
        image.save(path);return True
    finally:gdi.SelectObject(memory,old);gdi.DeleteObject(bitmap);gdi.DeleteDC(memory);user.ReleaseDC(hwnd,dc)


def observe(media,vm,messages,folder,row,seconds,player,fresh_hls=False):
    deadline=time.monotonic()+seconds+60;decoded=frames=0;last_advance=None;max_gap=0;row['decoder_generations']=[];row['actual_player']='bundled FFplay/SDL';first_output=None
    for index in range(64):
        generation=media.get('generation',index+1);frame=folder/('frame.png' if index==0 else 'frame-generation-'+str(index+1)+'.png')
        command=[str(player),'-hide_banner','-loglevel','debug','-stats','-autoexit','-window_title','NTV compatibility playback '+row['id'],'-volume','0','-x','960','-y','540','-vf','showinfo=checksum=0']
        headers=''.join(key+': '+value+'\r\n' for key,value in media['headers'].items())
        if headers:command+=['-headers',headers]
        if media.get('liveHls'):command+=['-infbuf']
        if media.get('virtualMse'):command+=['-live_start_index','0']
        elif fresh_hls:command+=['-http_persistent','0']
        # Match production's unbounded live input. FFplay's -t includes packets
        # buffered before the first displayed frame, so it can exhaust the
        # decoder several seconds before the requested observation completes.
        # The outer deadline and owned-process cleanup already bound this run.
        command+=['-i',media['url']]
        pending=None;stage_time=0;stage_frames=0;last_clock=None;metadata=False;last_feedback=last_capture=last_memory=0;previous_frame=-1;frame_offset=0
        decoder=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf8',errors='replace');output=queue.Queue()
        def reader(process=decoder):
            for line in process.stdout:output.put(line.rstrip())
            output.put(None)
        threading.Thread(target=reader,daemon=True).start()
        try:
            with (folder/'ffplay-private.log').open('a',encoding='utf8') as log:
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
                    if now-last_memory>=1:
                        last_memory=now
                        try:row['peak_decoder_rss_bytes']=max(row.get('peak_decoder_rss_bytes',0),psutil.Process(decoder.pid).memory_info().rss)
                        except psutil.Error:pass
                    if last_advance is not None:
                        max_gap=max(max_gap,round((now-last_advance)*1000));row['max_video_stall_ms']=max_gap
                        if now-last_advance>=6:raise RuntimeError('Actual FFplay video decoder stalled for at least 6000 ms')
                    try:line=output.get(timeout=.1)
                    except queue.Empty:continue
                    if line is None:break
                    log.write(line+'\n')
                    match=re.search(r'Parsed_showinfo.*?n:\s*(\d+).*?s:(\d+)x(\d+)',line)
                    if match:
                        count,width,height=map(int,match.groups())
                        if count<previous_frame:frame_offset+=previous_frame+1
                        previous_frame=count;value=frame_offset+count+1
                        row['videoWidth']=width;row['videoHeight']=height
                        if value>stage_frames:
                            stage_frames=value;last_advance=now;first_output=first_output or now
                            row['video_frames']=frames+stage_frames
                        if not metadata:
                            vm.stdin.write(f'SIZE {generation} {width} {height}\n');vm.stdin.flush();metadata=True
                        if stage_frames>=25 and not frame.exists() and now-last_capture>=2:
                            last_capture=now;capture(decoder.pid,frame)
                    match=re.match(r'^\s*([0-9]+(?:\.[0-9]+)?)\s+(?:A-V|M-V|M-A):',line)
                    if match:
                        clock=float(match.group(1))
                        if last_clock is not None:stage_time+=max(0,min(1.5,clock-last_clock))
                        last_clock=clock;row['decoded_seconds']=decoded+stage_time
                        if now-last_feedback>=.25:
                            vm.stdin.write(f'TIME {generation} {stage_time}\n');vm.stdin.flush();last_feedback=now
                    if decoded+stage_time>=seconds-.5 and first_output and now-first_output>=seconds-.5:break
                else:raise RuntimeError('Actual FFplay playback observation timed out')
                capture(decoder.pid,folder/('frame-end-'+str(index+1)+'.png'))
                row['decoder_generations'].append({'generation':generation,'decoded_seconds':stage_time,'video_frames':stage_frames,'replaced':bool(pending)})
        finally:
            if decoder.poll() is None:
                decoder.terminate()
                try:decoder.wait(timeout=3)
                except subprocess.TimeoutExpired:decoder.kill();decoder.wait()
            if decoder.stdout:decoder.stdout.close()
        decoded+=stage_time;frames+=stage_frames
        if not pending:return
        media=pending;row['source_changes']=index+1
    raise RuntimeError('Excessive actual FFplay source replacements')
