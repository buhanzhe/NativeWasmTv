"""Sequential actual Android MainActivity/core/decoder sweep, one case per process."""
import argparse,base64,collections,datetime,json,subprocess,time,re
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--selection',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--adb',default='adb');p.add_argument('--serial',required=True);p.add_argument('--seconds',type=int,default=10);p.add_argument('--progress-name',default='report.json');p.add_argument('--direct-lite',action='store_true');p.add_argument('--trace-script',type=Path,help='Optional diagnostic document-start script');p.add_argument('--no-audit',action='store_true',help='Measure normal playback without verbose runtime/network diagnostics');a=p.parse_args()
    manifest=json.loads(a.selection.read_text('utf8'));assert manifest['kind']=='site-sweep';a.output.mkdir(parents=True,exist_ok=True)
    trace=a.trace_script
    def adb(*cmd,**kw):return subprocess.run([a.adb,'-s',a.serial,*cmd],capture_output=True,**kw)
    trace_path='/sdcard/Android/data/xiao.bu.tv.litebrowser/files/benchmark-trace.user.js'
    trace_args=['-e','traceScriptPath',trace_path] if trace else []
    if trace:
        pushed=adb('push',str(trace),trace_path,timeout=15)
        if pushed.returncode:raise RuntimeError('Cannot upload benchmark trace script')
    info={key:adb('shell','getprop',prop).stdout.decode('utf8','replace').strip() for key,prop in [('android','ro.build.version.release'),('abi','ro.product.cpu.abi'),('model','ro.product.model')]}
    assert info['android'].startswith('4.4') and info['abi']=='x86',info
    started=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat();rows=[]
    def save():
        counts=dict(collections.Counter(r['status'] for r in rows));report={'kind':'site-sweep','platform':'Android 4.4 x86','device':info,'serial':a.serial,'started_at':started,'planned':len(manifest['channels']),'completed':len(rows),'planned_sites':manifest['planned_sites'],'completed_sites':len({r['site'] for r in rows}),'seconds_required':a.seconds,'decoder':'IJK software; emulator framework decoder SIGILL recorded separately','counts':counts,'channels':rows}
        target=a.output/a.progress_name;temporary=target.with_suffix('.tmp');temporary.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8');temporary.replace(target)
    for item in manifest['channels']:
        folder=a.output/item['id'];folder.mkdir(exist_ok=True);result_file=folder/'result.json'
        if result_file.exists():
            prior=json.loads(result_file.read_text('utf8'))
            if prior['status']!='delegated':rows.append(prior);save();continue
        row={k:item.get(k) for k in ['id','site','name','url','source_line','source']};row['device_serial']=a.serial;row['status']='running';begin=time.monotonic();raw=None
        try:
            adb('shell','am','force-stop','xiao.bu.tv.litebrowser',timeout=15)
            source=base64.b64encode(item['url'].encode()).decode()
            result=adb('shell','am','instrument','-w','-e','mode','images','-e','siteBase64',source,'-e','decode','software','-e','directLite',str(a.direct_lite).lower(),'-e','imageOrder','true','-e','auditSites',str(not a.no_audit).lower(),'-e','strictPlayback','true','-e','captureFrames','true','-e','imageTimeoutMs','50000','-e','imageHoldMs',str(a.seconds*1000),'-e','stopOnFailure','true',*trace_args,'xiao.bu.tv.litebrowser.test/xiao.bu.tv.HealthBalanceInstrumentation',timeout=max(115,a.seconds+85))
            text=result.stdout.decode('utf8','replace');(folder/'instrumentation.txt').write_text(text,encoding='utf8');(folder/'instrumentation-stderr.txt').write_bytes(result.stderr)
            for line in text.splitlines():
                if line.startswith('{'):
                    try:raw=json.loads(line);break
                    except json.JSONDecodeError:pass
            if raw is None:
                row.update(status='crashed',reason='Instrumentation ended without a result');raise RuntimeError(row['reason'])
            (folder/'evidence.json').write_text(json.dumps(raw,ensure_ascii=False,indent=2),encoding='utf8')
            trials=raw.get('imageTrials',[]);trial=trials[0] if trials else {}
            for key in ['firstFrameMs','holdMs','playingAtEnd','advancedMs','outputFramesAdvanced','maxStallMs','videoWidth','videoHeight','videoOutputFps','backend']:row[key]=trial.get(key)
            row['directLite']=a.direct_lite
            row['builtinScriptsMatchAssets']=raw.get('builtinScriptsMatchAssets',{})
            row.update(status='passed' if raw.get('passed') and trial.get('playingAtEnd') else 'failed',reason=trial.get('error') or raw.get('cleanupFailure') or raw.get('failure',''),preferencesRestored=raw.get('preferencesRestored'),systemVolumeRestored=raw.get('systemVolumeRestored'))
            audit=trial.get('audit',{});row['documentStatus']=audit.get('documentStatus');row['documentReadyMs']=audit.get('documentReadyMs');row['mediaReadyMs']=audit.get('mediaReadyMs');row['scriptDiagnostics']=[m for m in audit.get('messages',[]) if m.startswith('UserScript ')]
            row['scriptErrors']=[m for m in row['scriptDiagnostics'] if re.search(r'Error|Exception',m)]
            row['frames']=[]
            for name in trial.get('frames',[]):
                if '/' in name or '\\' in name:continue
                local=folder/name;pull=adb('pull','/sdcard/Android/data/xiao.bu.tv.litebrowser/files/'+name,str(local),timeout=15)
                if pull.returncode==0:row['frames'].append(name)
            if row['status']=='passed' and not row['frames']:row.update(status='failed',reason='Actual screen evidence missing')
        except (subprocess.TimeoutExpired,RuntimeError) as error:
            row.update(status=row['status'] if row['status']=='crashed' else 'timeout',reason=str(error));adb('shell','am','force-stop','xiao.bu.tv.litebrowser',timeout=15)
        finally:
            if row['status']!='passed':
                try:(folder/'logcat.txt').write_bytes(adb('logcat','-d','-v','brief','-t','1200',timeout=10).stdout)
                except subprocess.TimeoutExpired:pass
            row['elapsed_ms']=round((time.monotonic()-begin)*1000);result_file.write_text(json.dumps(row,ensure_ascii=False,indent=2),encoding='utf8');rows.append(row);save()
            print(str(len(rows))+'/'+str(len(manifest['channels']))+' '+item['site']+' '+row['status'],flush=True)
    print(json.dumps(dict(collections.Counter(r['status'] for r in rows))),flush=True)

if __name__=='__main__':main()
