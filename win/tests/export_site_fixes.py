"""Combine an immutable site sweep with targeted, visually reviewed follow-ups."""
import argparse,collections,datetime,html,json,pathlib,shutil

METRICS=['firstFrameMs','holdMs','playingAtEnd','advancedMs','outputFramesAdvanced','maxStallMs',
         'videoWidth','videoHeight','videoOutputFps','backend','directLite','builtinScriptsMatchAssets','preferencesRestored','systemVolumeRestored','decoded_seconds','video_frames','handoff_ms','max_video_stall_ms','source_changes','decoder_generations','actual_player','peak_decoder_rss_bytes']
NAMES={'passed':'通过','recovered':'本轮恢复','restricted':'内容受限','failed':'仍失败','unstable':'不稳定','upstream':'源站/网络异常','excluded':'按要求排除'}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline',type=pathlib.Path,required=True)
    p.add_argument('--root',type=pathlib.Path,required=True)
    p.add_argument('--output',type=pathlib.Path,required=True)
    p.add_argument('--round-config',type=pathlib.Path,help='Explicit runs, exclusions, reviews and notes for a later round')
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    config=json.loads(a.round_config.read_text('utf8')) if a.round_config else {}
    baseline=json.loads((a.baseline/'report.json').read_text('utf8'))
    cases={(x['platform'],x['id']):x for x in baseline['channels']}
    history=collections.defaultdict(list)
    runs_by_platform=config.get('runs',{'windows':['windows-final','windows-runtime-check'],'android':['android-final','android-live-refresh','android-stability']})
    for platform,runs in runs_by_platform.items():
        for run in runs:
            report=json.loads((a.root/run/'report.json').read_text('utf8'))
            for item in report['channels']:
                history[platform,item['id']].append({'run':run,'at':report['started_at'],
                    'seconds':report['seconds_required'],'result':item,'folder':a.root/run/item['id']})
    rows=[]
    for key,old in cases.items():
        row={k:old[k] for k in ['platform','id','name','site','source_line','url'] if k in old}
        row['before']=old['status'];row['tested_this_round']=key in history
        tests=history.get(key,[])
        # A short success cannot override a failed longer observation.
        tests.sort(key=lambda x:(x['seconds'],x['at']))
        chosen=tests[-1] if tests else None
        latest=max(tests,key=lambda x:x['at']) if tests else None
        if latest and chosen and latest['at']>chosen['at'] and latest['result']['status'] not in ['passed','passed_after_retry','recovered']:chosen=latest
        if latest:row['latest_verification']={'run':latest['run'],'at':latest['at'],'seconds':latest['seconds'],'status':latest['result']['status'],**{k:latest['result'][k] for k in METRICS if k in latest['result']}}
        row['longest_passed_seconds_this_round']=max((x['seconds'] for x in tests if x['result']['status'] in ['passed','passed_after_retry','recovered']),default=0)
        result=chosen['result'] if chosen else old
        row.update({k:result[k] for k in METRICS if k in result})
        row['technical_status']=result['status'];passed=result['status'] in ['passed','passed_after_retry']
        was_passed=old['status'] in ['passed','passed_after_retry','recovered']
        passed=result['status'] in ['passed','passed_after_retry','recovered']
        row['status']=('recovered' if chosen and not was_passed else 'passed') if passed else (result['status'] if result['status'] in ['restricted','excluded','upstream'] else 'failed')
        row['reason']=result.get('reason','')
        row['entry']='lightweight core / Android directLite' if row['platform']=='android' and chosen else (old.get('entry','default-app'))
        row['observation_seconds']=chosen['seconds'] if chosen else old.get('observation_seconds',10)
        row['tested_at']=chosen['at'] if chosen else old.get('tested_at',baseline['at'])
        row['history']=old.get('history',[])+[{'run':x['run'],'at':x['at'],'seconds':x['seconds'],'status':x['result']['status'],
                         'reason':x['result'].get('reason',''),**{k:x['result'][k] for k in METRICS if k in x['result']}} for x in tests]
        if chosen and passed:
            row['reason']='本轮实际解码与节目画面通过；未单独隔离验证的首轮失败原因不作推断。'
            if old['status']=='restricted':row['reason']='本次为真实节目画面；原限制可能随节目时段变化，未绕过网站权限。'
            if row['platform']=='windows' and row['site'] in ['www.csztv.com','www.0515yc.cn']:
                row['reason']='补齐站点漏发的中间证书后实播通过；继续校验可信根、有效期与主机名。'
            if row['id']=='site-0506b68decbf':row['reason']='等待异步频道列表后重试原选台动作；所选经济生活频道实际解码通过。'
        if chosen and row['site'] in ['live.fjtv.net','www.zohi.tv']:
            row['status']='restricted';row['reason']='官网同样显示版权限制，未计入正常播放。' if row['site']=='live.fjtv.net' else '源视频提示播出安排限制，不支持播放该时段内容；未计入正常播放。'
        if row['site']=='www.sctv.com':
            row['status']='restricted';row['issue_type']='login_required';row['reason']='官方 Chrome 在同一频道页显示“请登录后观看”；需要网站有效登录。'
        if chosen and not passed:
            details={
                'www.youtube.com':'当前网络连接超时，无法验证播放。',
                'www.btime.com':'依赖的四个 volccdn 播放脚本返回 HTTP 504，页面无法建立播放器。',
                'web.guangdianyun.tv':'官网为当前频道选中的签名 HLS 返回 HTTP 404。',
                'www.huya.com':'原 SDK 连接结束后的恢复仍未通过本轮连续播放验收。',
            }
            if row['platform']=='windows' and row['site']=='www.jzrt.com':details['www.jzrt.com']='此 HTTPS 频道入口证书已于 2026-10-19 过期，需要源站续期。'
            if row['site'] in details:row['reason']=details[row['site']]+' 本次记录：'+str(result.get('reason',''))
            if any(t['result']['status']=='passed' for t in tests):
                row['status']='unstable';row['reason']='此前实播曾通过，后续复测失败：'+str(result.get('reason',''))
        decision=config.get('reviews',{}).get(row['platform']+':'+row['id'])
        if decision and passed and decision.get('passed_reason'):row['reason']=decision['passed_reason']
        if decision and decision.get('category') and (not passed or decision.get('category') in ['restricted','unstable']):
            row['status']=decision['category'];row['reason']=decision['reason'];row['review_evidence']=decision.get('evidence',[])
        if decision and decision.get('qualification'):row['reason']+=' '+decision['qualification']
        exclusion=config.get('exclusions',{}).get(row['id'])
        if exclusion:row['status']='excluded';row['reason']=exclusion
        row['pictures']=[];destination=a.output/row['platform']/row['id'];destination.mkdir(parents=True,exist_ok=True)
        if chosen:
            names=['frame.png',*[p.name for p in sorted(chosen['folder'].glob('frame-generation-*.png'))],*[p.name for p in sorted(chosen['folder'].glob('frame-end-*.png'))]] if row['platform']=='windows' else result.get('frames',[])
            originals=[chosen['folder']/name for name in names if pathlib.Path(name).name==name]
            # Preserve the successful short-run frame when the long cold start failed.
            if not any(x.is_file() for x in originals):
                for trial in reversed(tests[:-1]):
                    names=['frame.png'] if row['platform']=='windows' else trial['result'].get('frames',[])
                    originals=[trial['folder']/name for name in names if pathlib.Path(name).name==name]
                    if any(x.is_file() for x in originals):row['picture_note']='此前测试画面；当前复测仍失败。';break
        else:originals=[a.baseline/name for name in old.get('pictures',[])]
        for index,source in enumerate(originals):
            if not source.is_file():continue
            target=destination/('frame-'+str(index)+source.suffix);shutil.copy2(source,target)
            row['pictures'].append(target.relative_to(a.output).as_posix())
        (destination/'result.json').write_text(json.dumps(row,ensure_ascii=False,indent=2),'utf8');rows.append(row)
    summary={}
    for platform in ['windows','android']:
        all_group=[r for r in rows if r['platform']==platform];group=[r for r in all_group if r['status']!='excluded'];counts=collections.Counter(r['status'] for r in group)
        summary[platform]={'cases':len(group),'sites':len({x['site'] for x in group}),'tested_this_round':sum(x['tested_this_round'] for x in group),
            'passed':counts['passed']+counts['recovered'],'recovered':counts['recovered'],'restricted':counts['restricted'],
            'failed':counts['failed']+counts['unstable'],'upstream':counts['upstream'],'excluded':len(all_group)-len(group),'unstable':counts['unstable']}
    notes=['本轮针对旧报告中的 Windows 23 个、Android 42 个失败/受限用例复测，另含已通过频道的持续播放对照。其余旧通过项沿用原记录，不表示本轮重新跑完全部 352 例。',
           'Android 为 4.4.2 x86、64 MiB Dalvik 堆；轻量内核直接网页路径与 IJK 软解。模拟器系统硬解存在 SIGILL，本轮不代表系统 WebView 或系统播放器实测。',
           'Windows 最终便携包使用随包精简 Java 运行时，盐城、苏州、宁波、咸宁经济生活四个真实频道另行通过 30 秒实播；不是只在开发机完整 JDK 中验证。',
           '短测 10 秒；部分频道追加 30 秒。必须有真实解码时钟、视频帧及画面证据，版权提示视频不算通过。30 秒通过不能证明长时间稳定。',
           '复测间存在站点、网络和节目时段变化；除证书链、异步选台与有限媒体切换到直播的缓存错误等有对照验证的问题外，不将所有恢复归因于代码。',
           '修复包括：小 Dalvik 堆使用有边界的原生 Worker；跨线程消息序列化前限额；Windows 系统证书库及中间证书补齐；异步选台重试；私有播放器当前媒体观察；Document 标准类型标记；直播播放列表不再复用旧有限媒体缓存。',
           '虎牙实验方案未进入测试包。未绕过登录、版权、区域或过期证书要求。原始完整日志留在本机测试目录；公开报告不包含 Cookie、签名媒体 URL 或授权请求头。']
    notes=config.get('notes',notes)
    data={'at':datetime.datetime.now().astimezone().isoformat(),'baseline_at':baseline['at'],'scope':config.get('scope',baseline['scope']),'notes':notes,'summary':summary,'channels':rows}
    (a.output/'report.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf8')
    esc=lambda s:html.escape(str(s if s is not None else ''),quote=True)
    cards=''.join('<div><b>'+esc(p)+'</b><p>通过 '+str(s['passed'])+' · 受限 '+str(s['restricted'])+' · 待适配 '+str(s['failed'])+' · 源站异常 '+str(s['upstream'])+'</p><small>本轮恢复 '+str(s['recovered'])+'；复测 '+str(s['tested_this_round'])+'；按要求排除 '+str(s['excluded'])+'</small></div>' for p,s in summary.items())
    table=[]
    for row in sorted(rows,key=lambda r:(not r['tested_this_round'],r['platform'],r['site'],r['name'])):
        pictures=''.join('<a href="'+esc(p)+'"><img loading="lazy" src="'+esc(p)+'"></a>' for p in row['pictures'])
        official=' '.join('<a href="'+esc(p)+'">官网核验</a>' for p in row.get('review_evidence',[]))
        timing=str(round(row.get('decoded_seconds',row.get('advancedMs',0)/1000),2))+' 秒'
        recent=row.get('latest_verification',{});latest_note=''
        if recent and recent['at']!=row['tested_at']:
            progress=round(recent.get('decoded_seconds',recent.get('advancedMs',0)/1000),2)
            latest_note='<small>最近：'+str(recent['seconds'])+' 秒 / 实进 '+str(progress)+' 秒</small>'
        table.append('<tr data-platform="'+row['platform']+'" data-status="'+row['status']+'" data-current="'+str(int(row['tested_this_round']))+'"><td>'+row['platform']+'</td><td>'+esc(row['site'])+'<br>'+esc(row['name'])+'</td><td class="'+row['status']+'">'+NAMES[row['status']]+'<br><small>原：'+esc(row['before'])+'</small></td><td>'+str(row['observation_seconds'])+' 秒<br>'+timing+latest_note+'</td><td>'+esc(row['reason'])+'<br><a href="'+row['platform']+'/'+row['id']+'/result.json">记录与指标</a> '+official+'</td><td>'+pictures+'<small>'+esc(row.get('picture_note',''))+'</small></td></tr>')
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>网页站点修复复测</title><style>body{font:15px/1.6 system-ui,sans-serif;margin:24px;background:#f4f6f9;color:#172336}h1{margin-bottom:4px}.cards{display:flex;gap:16px;flex-wrap:wrap}.cards>div{background:white;padding:16px 24px;border-radius:12px}table{border-collapse:collapse;width:100%;background:white;margin-top:20px}td,th{padding:10px;text-align:left;border-bottom:1px solid #dbe1e8;vertical-align:top}th{position:sticky;top:0;background:#e8eef7}td:nth-child(5){max-width:450px}img{width:200px;height:112px;object-fit:contain;background:#111}.passed,.recovered{color:#09773b}.restricted,.unstable{color:#956300}.failed{color:#b22030}a{color:#1266b2}small{display:block;color:#59677b}select,input{padding:9px;margin:8px;border:1px solid #c2cbd7;border-radius:6px}details{max-width:1100px;margin:20px 0}</style><h1>网页站点修复复测</h1><p>2026-11-09 · Windows / Android 4.4.2 x86</p><div class="cards">CARDS</div><p>保留原 160 个站点、每端 176 个用例的总表；本轮集中处理失败和受限项。尚有失败，未全部修复。</p><details open><summary>测试范围与结论边界</summary><ul>NOTES</ul><a href="report.json">完整数据</a> · <a href="build-and-restoration.json">构建与设备恢复</a> · <a href="official/sctv-login.png">四川官网登录提示</a> · <a href="official/fjtv-restriction.png">福建官网版权提示</a></details><label><input id="current" type="checkbox" checked>仅本轮复测</label><select id="platform"><option value="">全部平台</option><option>windows</option><option>android</option></select><select id="status"><option value="">全部结果</option>OPTIONS</select><input id="query" placeholder="搜索站点、频道或原因"><span id="count"></span><table><thead><tr><th>平台</th><th>站点 / 频道</th><th>当前结果</th><th>观察 / 时钟</th><th>原因与证据</th><th>实际画面</th></tr></thead><tbody>ROWS</tbody></table><script>const rows=[...document.querySelectorAll('tbody tr')],current=document.getElementById('current'),platform=document.getElementById('platform'),status=document.getElementById('status'),query=document.getElementById('query'),count=document.getElementById('count');function filter(){let n=0;for(const r of rows){r.hidden=(current.checked&&r.dataset.current!=='1')||(platform.value&&r.dataset.platform!==platform.value)||(status.value&&r.dataset.status!==status.value)||(query.value&&!r.textContent.toLowerCase().includes(query.value.toLowerCase()));if(!r.hidden)n++;}count.textContent='显示 '+n+' / '+rows.length;}for(const e of [current,platform,status,query])e.addEventListener('input',filter);filter();</script></html>'''
    page=page.replace('CARDS',cards).replace('NOTES',''.join('<li>'+esc(n)+'</li>' for n in notes)).replace('OPTIONS',''.join('<option value="'+k+'">'+v+'</option>' for k,v in NAMES.items())).replace('ROWS','\n'.join(table))
    if config:
        page=page.replace('2026-11-09 · Windows',config.get('date','2026-11-10')+' · Windows').replace('保留原 160 个站点、每端 176 个用例的总表；本轮集中处理失败和受限项。尚有失败，未全部修复。','本轮排除已确认的播出安排及登录限制节目；原列表保留，排除项不计入兼容性验收。其余项继续区分通过、内核待适配、版权限制和源站异常。')
        downloads=config.get('downloads',{})
        if downloads:page=page.replace('<details open>','<p>测试包：'+' · '.join('<a href="'+esc(name)+'">'+esc(label)+'</a>' for name,label in downloads.items())+' · <a href="checksums.json">SHA-256</a></p><details open>',1)
    page=page.replace('<details open>','<details>').replace('<title>','<link rel="icon" href="data:,"><title>',1)
    page=page.replace('</style>','th:nth-child(3),td:nth-child(3){min-width:72px}th:nth-child(4),td:nth-child(4){min-width:100px}td:nth-child(6){width:420px;max-width:420px}td:nth-child(6)>a{display:inline-block}</style>',1)
    (a.output/'report.html').write_text(page,'utf8');print(json.dumps(summary,ensure_ascii=False))

if __name__=='__main__':main()
