"""Export the completed cross-platform site audit, including retries and visual review."""
from pathlib import Path
import argparse,collections,datetime,hashlib,html,json,shutil

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    plan=json.loads((a.root/'plan/selection.json').read_text('utf8'));review_file=a.root/'visual-review.json';review=json.loads(review_file.read_text('utf8')) if review_file.exists() else {};all_rows=[]
    for platform in ['windows','android']:
        for item in plan['channels']:
            baseline=a.root/platform/item['id']/'result.json';assert baseline.exists(),baseline
            row=json.loads(baseline.read_text('utf8'));assert row['status'] not in ['running','delegated'],baseline
            attempts=[{'kind':'baseline','result':row}];chosen=baseline
            for suffix in ['-retry','-direct','-selected']:
                candidate=a.root/(platform+suffix)/item['id']/'result.json'
                if candidate.exists():
                    result=json.loads(candidate.read_text('utf8'));attempts.append({'kind':suffix[1:],'result':result})
                    if suffix in ['-direct','-selected'] or row['status']!='passed':row=result;chosen=candidate
            row={**item,**row,'platform':platform,'attempts':attempts,'technical_status':row['status'],'entry':'direct-core' if chosen.parent.parent.name.endswith('-direct') else 'default-app'}
            if row['status']=='passed' and len(attempts)>1 and attempts[0]['result']['status']!='passed':row['status']='passed_after_retry'
            if platform=='windows' and item['site']=='m.miguvideo.com' and attempts[-1]['kind']=='selected':
                row['reason']='已排除片头广告；最终交付所选频道的直播HLS并通过实播。'
                row['selectionCorrection']='Earlier retry decoded a preroll advertisement; final retest uses app policy to exclude prerolls and delivers the selected channel HLS.'
                for attempt in attempts:
                    if attempt['kind']=='retry':attempt['semantic_valid']=False;attempt['validation_reason']='Preroll source from ggv.cmvideo.cn, different from selected live HLS'
            decision=review.get(platform+':'+item['id']);row['visual_review']=decision
            if decision and decision['category'] in ['content_restricted','replay_only'] and row['status'] in ['passed','passed_after_retry']:
                row['status']='restricted' if decision['category']=='content_restricted' else decision['category'];row['reason']=decision['message']
            folder=a.output/platform/item['id'];folder.mkdir(parents=True,exist_ok=True)
            frames=['frame.png'] if platform=='windows' else row.get('frames',[])
            row['pictures']=[]
            for name in frames:
                original=chosen.parent/name
                if original.is_file() and Path(name).name==name:
                    shutil.copy2(original,folder/name);row['pictures'].append(str((folder/name).relative_to(a.output)).replace('\\','/'))
            for name in ['page-failure.json','page-handoff.json']:
                source=chosen.parent/name
                if source.exists():
                    raw=json.loads(source.read_text('utf8'));safe={k:v for k,v in raw.items() if k!='recentEvents'}
                    safe['recentEventTypes']=dict(collections.Counter(str(event.get('type','unknown')) for event in raw.get('recentEvents',[]) if isinstance(event,dict)))
                    (folder/name).write_text(json.dumps(safe,ensure_ascii=False,indent=2),encoding='utf8')
            (folder/'result.json').write_text(json.dumps(row,ensure_ascii=False,indent=2),encoding='utf8');all_rows.append(row)
    summary={platform:{'cases':len([r for r in all_rows if r['platform']==platform]),'sites':len({r['site'] for r in all_rows if r['platform']==platform}),'counts':dict(collections.Counter(r['status'] for r in all_rows if r['platform']==platform))} for platform in ['windows','android']}
    report={'at':datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),'scope':{k:v for k,v in plan.items() if k!='channels'},'method':{'windows':'Native Windows JVM/JNI QuickJS/litehtml/wasm3; actual FFmpeg decode; separate Swing/FFplay UI/stop test passed. Existing native DLL retained, current Java/assets rebuilt.','android':'Android 4.4.2 x86; actual MainActivity and IJK software decoder, one case per process. Framework decoder SIGILL logged separately.','required_observation_seconds':10,'android_gate':'first frame, at least 65% clock progress, at least20 output frames, no stall >=6 seconds, actual screen PNG','windows_gate':'at least9.5 decoded seconds, at least20 frames, actual decoded PNG, successful decoder and VM cleanup','limits':['每个站点覆盖，不等于767条频道逐条实播；完整清单保留，选择了176个用例覆盖160个站点。','本次为短时兼容性测试，不代表10分钟或更长稳定性，也不构成CPU/内存优化的量化结论。','模拟器关闭音频设备输出，未逐站进行扬声器验听。','映射到原生解析器的地址补测了直接网页路径；默认入口结果保存在attempts中，二者可能不同。','版权提示、仅电视端观看及明确提示回看不可用的画面，不计正常节目播放通过。','Windows媒体交付耗时与Android首帧耗时含义不同，不直接横向比较。']},'summary':summary,'channels':all_rows}
    (a.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8');shutil.copy2(a.root/'plan/inventory.json',a.output/'inventory.json');shutil.copy2(a.root/'plan/selection.json',a.output/'selection.json')
    names={'passed':'通过','passed_after_retry':'复测通过','restricted':'内容受限','replay_only':'仅回放','failed':'失败','timeout':'超时','crashed':'测试进程退出','blocked':'网络策略阻断'}
    esc=lambda v:html.escape(str(v if v is not None else ''),quote=True)
    table=[]
    for row in all_rows:
        color='ok' if row['status'] in ['passed','passed_after_retry'] else 'limited' if row['status'] in ['restricted','replay_only'] else 'bad'
        picture=row['pictures'][0] if row['pictures'] else '';image='<a href="'+esc(picture)+'"><img loading="lazy" src="'+esc(picture)+'"></a>' if picture else ''
        timing=row.get('handoff_ms') if row['platform']=='windows' else row.get('firstFrameMs');clock=row.get('decoded_seconds') if row['platform']=='windows' else (row.get('advancedMs') or 0)/1000;frames=row.get('video_frames') if row['platform']=='windows' else row.get('outputFramesAdvanced')
        table.append('<tr data-platform="'+row['platform']+'" data-status="'+row['status']+'"><td>'+esc(row['platform'])+'</td><td>'+esc(row['site'])+'<br>'+esc(row['name'])+'</td><td class="'+color+'">'+esc(names.get(row['status'],row['status']))+'</td><td>'+esc(timing)+'</td><td>'+esc(clock)+' 秒 / '+esc(frames)+' 帧</td><td>'+esc(('直接网页路径补测；默认入口首轮'+('通过。' if row['attempts'] and row['attempts'][0]['result']['status']=='passed' else '未通过。') if row['entry']=='direct-core' else '')+str(row.get('reason') or ''))+'<br><a href="'+row['platform']+'/'+row['id']+'/result.json">数据与各次记录</a></td><td>'+image+'</td></tr>')
    cards=''.join('<div class="card"><b>'+esc(platform)+'</b><p>160 个站点 · 176 个用例</p><p>'+' · '.join(esc(names.get(k,k))+' '+str(v) for k,v in value['counts'].items())+'</p></div>' for platform,value in summary.items())
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>WebView 全站测试 · 2026-11-09</title><style>body{font:15px/1.6 system-ui,sans-serif;margin:24px;color:#172336;background:#f4f6f9}h1{margin-bottom:4px}.cards{display:flex;gap:16px;flex-wrap:wrap}.card{background:white;padding:16px 24px;border-radius:12px;min-width:280px}.note{max-width:1100px;color:#495668}table{border-collapse:collapse;background:white;width:100%;margin-top:20px}td,th{padding:10px;text-align:left;border-bottom:1px solid #dbe1e8;vertical-align:top}th{position:sticky;top:0;background:#e8eef7}td:nth-child(6){max-width:420px;word-break:break-word}img{width:180px;height:102px;object-fit:contain;background:#111}.ok{color:#09773b}.limited{color:#956300}.bad{color:#b22030}input,select{padding:9px;margin:8px 8px 0 0;border:1px solid #c2cbd7;border-radius:6px}a{color:#1266b2}</style><h1>WebView 全站测试</h1><p>2026-11-09 · Windows 与 Android 4.4.2 x86</p><div class="cards">CARDS</div><p class="note">范围：内置列表158个站点，另含用户反馈的虎牙与YouTube，共160个站点、176个用例。有UJS且多频道的站点追加另一个频道，保留完整原URL。未选中的同站频道不计为已实播。</p><p class="note">每例观察10秒并核对实际解码帧。Android使用IJK软解，模拟器系统解码器另有SIGILL环境失败。Windows额外验证实际Swing界面、列表切换、FFplay画面和停止清理。版权或电视端限制提示单列，原始失败及复测均保留。详细方法、边界见<a href="report.json">完整JSON</a>；<a href="inventory.json">767条WebView清单</a>。</p><select id="platform"><option value="">全部平台</option><option>windows</option><option>android</option></select><select id="status"><option value="">全部结果</option>OPTIONS</select><input id="search" placeholder="搜索站点、频道、原因"><span id="count"></span><table><thead><tr><th>平台</th><th>站点 / 频道</th><th>结果</th><th>Windows交付 / Android首帧 ms</th><th>时钟 / 视频帧</th><th>原因与证据</th><th>实际画面</th></tr></thead><tbody>ROWS</tbody></table><script>const rows=Array.from(document.querySelectorAll('tbody tr')),platformSelect=document.getElementById('platform'),statusSelect=document.getElementById('status'),query=document.getElementById('search'),counter=document.getElementById('count');function filter(){let n=0;for(const row of rows){row.hidden=(platformSelect.value&&row.dataset.platform!==platformSelect.value)||(statusSelect.value&&row.dataset.status!==statusSelect.value)||(query.value&&!row.textContent.toLowerCase().includes(query.value.toLowerCase()));if(!row.hidden)n++;}counter.textContent='显示 '+n+' / '+rows.length;}for(const id of ['platform','status','search'])document.getElementById(id).addEventListener('input',filter);filter();</script></html>'''
    page=page.replace('CARDS',cards).replace('OPTIONS',''.join('<option value="'+key+'">'+value+'</option>' for key,value in names.items())).replace('ROWS','\n'.join(table));(a.output/'report.html').write_text(page,encoding='utf8');print(json.dumps(summary,ensure_ascii=False))

if __name__=='__main__':main()
