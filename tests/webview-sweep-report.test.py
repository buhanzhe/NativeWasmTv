from pathlib import Path
import importlib.util,json,tempfile
root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('report',root/'tools/regression/webview_sweep_report.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
def reason(messages, **fields):
 return module.failure_reason(dict(index=0,status='FAIL',audit=dict(messages=messages),**fields))
assert '空列表' in reason(['Original player API status {"array":true,"items":0}'])
assert '限制当前节目' in reason(['Original player API status {"programmeFlags":[{"is_shield":1,"is_review":0}]}'])
assert '404' in reason(['候选校验 HTTP 404 /selected/live.m3u8'])
assert '未持续' in reason([],firstFrameMs=1200)
assert '内存不足' not in reason([],lastLoadError='设备内存不足')
assert '内存不足' in reason([],lastLoadError='设备内存不足',loadErrorRequestMatches=True)
assert 'x86' in reason([],phoneStatus='FAIL',emulatorStatus='PASS')
with tempfile.TemporaryDirectory() as temporary:
 p=Path(temporary)
 entries=[dict(index=i,line=i+1,name='频道'+str(i),source='webview://https://example.test/?same=1',group='test') for i in range(4)]
 entries[3]['source']='webview://https://example.test/</script><script>alert(1)</script>'
 (p/'inventory.json').write_text(json.dumps(entries),'utf8')
 def run(name,rows,decode='auto'):
  d=p/name;d.mkdir();(d/'device.json').write_text(json.dumps(dict(decode=decode,apkSha256='tested-binary')),'utf8');(d/'results.jsonl').write_text('\n'.join(json.dumps(dict(index=i,status=s,run=name,observedMs=10020,framesAdvanced=240)) for i,s in rows)+'\n','utf8')
 run('phone-stable',[(0,'FAIL')]);run('phone-fix-001',[(0,'PASS')]);run('emu-upper-001',[(1,'PASS'),(2,'PASS')],'software')
 r=module.render(p)
 assert r['planned']==4 and r['completed']==3 and r['counts']==dict(PASS=3,PENDING=1)
 assert r['phoneCompleted']==1 and r['emulatorCompleted']==2
 assert len(r['rows'][0]['attempts'])==2 and r['rows'][1]['verificationDevice']=='API19 x86 / software'
 assert r['rows'][3]['status']=='PENDING' and r['rows'][1]['source']==r['rows'][2]['source']
 assert '</script><script>alert(1)' not in (p/'index.html').read_text('utf8')
 run('phone-audit-001',[(1,'FAIL')]);r=module.render(p)
 assert r['rows'][1]['status']=='FAIL' and r['rows'][1]['deviceDifference'] and r['rows'][1]['emulatorStatus']=='PASS'
 assert r['counts']['PENDING']==1 and r['phoneCompleted']==2
 assert r['passedOnAtLeastOneDevice']==3
 run('emu-auto-002',[(2,'PASS')],'auto');d=p/'emu-auto-002/results.jsonl';x=json.loads(d.read_text('utf8'));x['decoder']='software';d.write_text(json.dumps(x)+'\n','utf8');r=module.render(p);assert r['rows'][2]['verificationDevice']=='API19 x86 / auto → software' and r['emulatorCompleted']==2
 run('emu-withdrawn-003',[(2,'FAIL'),(3,'PASS')]);d=p/'emu-withdrawn-003/device.json';x=json.loads(d.read_text('utf8'));x['verdictEligible']=False;x['experimentNote']='Withdrawn prototype; original evidence retained';d.write_text(json.dumps(x),'utf8');r=module.render(p)
 assert r['rows'][2]['status']=='PASS' and r['rows'][3]['status']=='PENDING' and r['completed']==3
 assert r['rows'][2]['attempts'][-1]['status']=='FAIL' and not r['rows'][2]['attempts'][-1]['verdictEligible']
 (p/'curation.json').write_text(json.dumps(dict(removedEntries=[dict(index=3,reason='Unavailable original source')])), 'utf8');r=module.render(p)
 assert r['rows'][3]['status']=='PENDING' and r['rows'][3]['sourceAction']=='REMOVE_PLANNED'
 assert r['sourceMaintenance']['removed']==1 and r['completed']==3
 (p/'upstream-update.json').write_text('{}','utf8');r=module.render(p)
 assert r['rows'][3]['status']=='PENDING' and r['rows'][3]['sourceAction']=='REMOVED' and r['sourceMaintenance']['published']
 run('emu-old-binary-004',[(0,'FAIL'),(2,'FAIL')]);d=p/'emu-old-binary-004/device.json';x=json.loads(d.read_text('utf8'));x['supersededIndices']=[2];x['supersededNote']='Exact route replaced by verified newer binary; history retained';d.write_text(json.dumps(x),'utf8');r=module.render(p)
 assert r['rows'][2]['status']=='PASS' and r['rows'][0]['emulatorStatus']=='FAIL'
 assert r['rows'][2]['attempts'][-1]['status']=='FAIL' and not r['rows'][2]['attempts'][-1]['verdictEligible']
print('PASS distinct entry coverage, unfinished rows, binary/decoder metadata, retained retries, device differences and HTML escaping')
