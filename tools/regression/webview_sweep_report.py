"""Render API19 coverage with explicit physical and emulator verdicts."""
import argparse
import collections
import csv
from datetime import datetime
import html
import json
from pathlib import Path
import time
from zoneinfo import ZoneInfo

def failure_reason(row):
    if row['status'] != 'FAIL':
        return ''
    if row.get('emulatorStatus') == 'PASS' and row.get('phoneStatus') == 'FAIL':
        return '真机未达标；Android 4.4 x86 复测已通过'
    messages = row.get('audit', {}).get('messages', [])
    for message in reversed(messages):
        if 'Original player API status ' in message and '{' in message:
            try:
                data = json.loads(message[message.index('{'):])
                if data.get('array') is True and data.get('items') == 0:
                    return '原网页频道接口返回空列表，没有提供视频地址'
                if any(p.get('is_shield') == 1 and p.get('is_review') == 0 for p in data.get('programmeFlags', [])):
                    return '原网页限制当前节目播放（is_shield=1）'
            except (ValueError, TypeError):
                pass
        if '候选校验 HTTP 404 ' in message:
            return '原网页选中的直播清单返回 HTTP 404'
        if '候选校验 HTTP 403 ' in message:
            return '原网页选中的直播清单返回 HTTP 403'
    if any('HTTP 504 ' in m and '.js' in m for m in messages):
        return '页面脚本服务器返回 HTTP 504'
    if '内存不足' in row.get('lastLoadError', '') and (row.get('loadErrorRequestMatches') or row['index'] == 107):
        return '真机报告内存不足，无法解码此分辨率'
    if any('getCutvHlsLiveKey' in m and 'Timeout' in m for m in messages):
        return '原网页取流接口超时，未取得媒体地址'
    if row.get('firstFrameMs'):
        return '已出首帧，但未持续播放满 10 秒'
    if any(m.startswith('JS exception:') for m in messages):
        return '未出首帧，伴随网页脚本错误；原因待定位'
    return '观察期内没有首帧，原因待定位'

def render(folder):
    inventory = json.loads((folder / 'inventory.json').read_text('utf-8'))
    curation_path = folder / 'curation.json'
    curation = json.loads(curation_path.read_text('utf-8')) if curation_path.exists() else {}
    removed = {entry['index']: entry for entry in curation.get('removedEntries', [])}
    published = (folder / 'upstream-update.json').exists()
    attempts = collections.defaultdict(list)
    emulator_attempts = collections.defaultdict(list)
    runs = ['phone-initial', 'phone-stable'] + sorted(p.name for p in folder.glob('phone-audit-*') if p.is_dir()) + sorted(p.name for p in folder.glob('phone-fix-*') if p.is_dir())
    emulator_runs = sorted((p for p in folder.glob('emu-*') if (p / 'results.jsonl').exists()), key=lambda p: (p / 'results.jsonl').stat().st_mtime)
    for run in runs + [p.name for p in emulator_runs]:
        ledger = folder / run / 'results.jsonl'
        if not ledger.exists():
            continue
        identity = folder / run / 'device.json'
        device = json.loads(identity.read_text('utf-8')) if identity.exists() else {}
        for line in ledger.read_text('utf-8').splitlines():
            try:
                row = json.loads(line)
            except ValueError:
                continue
            superseded = row['index'] in device.get('supersededIndices', [])
            row.update(decode=device.get('decode', 'auto'), apkSha256=device.get('apkSha256', ''), deviceSerial=device.get('serial', ''), verdictEligible=device.get('verdictEligible', True) and not superseded, experimentNote=device.get('supersededNote', '') if superseded else device.get('experimentNote', ''))
            (emulator_attempts if run.startswith('emu-') else attempts)[row['index']].append(row)
    rows = []
    for entry in inventory:
        phone, emulator = attempts.get(entry['index'], []), emulator_attempts.get(entry['index'], [])
        phone_current = [a for a in phone if a['verdictEligible']]
        emulator_current = [a for a in emulator if a['verdictEligible']]
        history = phone_current or emulator_current
        row = dict(entry, status='PENDING', attempts=phone+emulator, phoneStatus=phone_current[-1]['status'] if phone_current else 'PENDING', emulatorStatus=emulator_current[-1]['status'] if emulator_current else 'PENDING')
        if history:
            row.update({k: v for k, v in history[-1].items() if k not in ('samples', 'attempts')})
            row['verificationDevice'] = 'SM-G3608 / auto' if phone_current else 'API19 x86 / ' + history[-1]['decode']
            if history[-1]['decode'] == 'auto' and row.get('decoder') in ('hardware', 'software'):
                row['verificationDevice'] += ' → ' + row['decoder']
        row['deviceDifference'] = bool(phone_current and emulator_current and phone_current[-1]['status'] != emulator_current[-1]['status'])
        row['api19Pass'] = row['phoneStatus'] == 'PASS' or row['emulatorStatus'] == 'PASS'
        row['observedReason'] = failure_reason(row)
        row['sourceAction'] = ('REMOVED' if published else 'REMOVE_PLANNED') if row['index'] in removed else 'KEEP'
        row['removalReason'] = removed.get(row['index'], {}).get('reason', '')
        rows.append(row)
    counts = collections.Counter(row['status'] for row in rows)
    summary = dict(updatedAt=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds'),
        planned=len(rows), completed=len(rows)-counts['PENDING'], counts=dict(counts), complete=not counts['PENDING'],
        scope='Android API19 physical device and x86 emulator: every original webview entry, ten continuous seconds of real playback; device and decoder mode are explicit',
        phoneCompleted=len(attempts), emulatorCompleted=len(emulator_attempts),
        passedOnAtLeastOneDevice=sum(row['api19Pass'] for row in rows),
        sourceMaintenance=dict(removed=len(removed), published=published, retained=len(rows)-len(removed),
            retainedPassed=sum(row['status']=='PASS' and row['index'] not in removed for row in rows),
            retainedPassedOnAtLeastOneDevice=sum(row['api19Pass'] and row['index'] not in removed for row in rows),
            retainedFailed=sum(row['status']=='FAIL' and row['index'] not in removed for row in rows)),
        emulator='Requested decoder mode and actual backend are recorded. API19 x86 auto mode selects software only with confirmed absent hardware. Where both devices were tested, physical verdict controls overall status and both remain visible.',
        rows=rows)
    temporary = folder / 'report.json.tmp'
    temporary.write_text(json.dumps(summary, ensure_ascii=False, indent=2), 'utf-8')
    # Windows readers may briefly hold the previous JSON open during refresh.
    for attempt in range(20):
        try:
            temporary.replace(folder / 'report.json')
            break
        except PermissionError:
            if attempt == 19:
                raise
            time.sleep(0.05)
    with (folder / 'report.csv').open('w', encoding='utf-8-sig', newline='') as output:
        writer = csv.writer(output)
        writer.writerow(['index', 'line', 'group', 'name', 'status', 'phoneStatus', 'emulatorStatus', 'verificationDevice', 'firstFrameMs', 'stableMs', 'clockAdvancedMs', 'framesAdvanced', 'error', 'observedReason', 'lastLoadError', 'source', 'attempts', 'sourceAction', 'removalReason'])
        for row in rows:
            writer.writerow([row.get(k, '') for k in ('index', 'line', 'group', 'name', 'status', 'phoneStatus', 'emulatorStatus', 'verificationDevice', 'firstFrameMs', 'observedMs', 'clockAdvancedMs', 'framesAdvanced', 'error', 'observedReason', 'lastLoadError', 'source')] + [len(row['attempts']), row['sourceAction'], row['removalReason']])
    data = json.dumps([dict(index=r['index'], name=r['name'], group=r['group'], status=r['status'],
        seconds=round(r.get('observedMs', 0)/1000, 1), frames=r.get('framesAdvanced', 0), error=(r.get('observedReason') or r.get('lastLoadError') or r.get('error', '')),
        attempts=len(r['attempts']), source=r['source'], removal=r['sourceAction'], removalReason=r['removalReason'], phone=r['phoneStatus'], emulator=r['emulatorStatus'], device=r.get('verificationDevice',''), difference=r['deviceDifference']) for r in rows], ensure_ascii=False).replace('<', r'\u003c')
    document = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Android 4.4 WebView 全量测试</title>
<style>body{font:15px system-ui,sans-serif;margin:30px;color:#223047;background:#f6f8fb}h1{font-size:25px}.cards{display:flex;gap:15px;margin:20px 0}.card{background:white;border:1px solid #dce2ec;border-radius:10px;padding:16px;min-width:100px}.card b{font-size:25px;display:block}.meta{color:#667085}input,select{font:inherit;padding:9px;border:1px solid #bbc6d6;border-radius:6px;margin:0 8px 14px 0}table{width:100%;border-collapse:collapse;background:white}td,th{text-align:left;padding:10px;border-bottom:1px solid #e5e9f0}th{background:#eaf0f8;position:sticky;top:0}.PASS{color:#177643}.FAIL{color:#be3030}.PENDING{color:#727b8e}details{max-width:45vw;overflow-wrap:anywhere}small{color:#667085}</style>
<h1>Android 4.4 WebView 全量测试</h1><p class="meta">__SURFACES__ · 每台实际连续播放 10 秒 · 记录解码设置与实际后端</p>
<div class="cards"><div class="card"><b>__DONE__ / __TOTAL__</b>已测</div><div class="card"><b>__PASS__</b>通过</div><div class="card"><b>__FAIL__</b>失败 / 受限</div><div class="card"><b>__PENDING__</b>未测</div></div>
<p class="meta">更新时间：__TIME__。全部试验保留在 JSON 的 attempts 中；撤回方案已标注，不作为当前方案结论；同时展示真机与模拟器结果；两端均测时以真机结果为总状态。自动模式实际采用的软件解码会单独标明。刷新查看新进度。</p>
<p class="meta">频道列表维护：__MAINTENANCE__。移除项保留原始测试状态和证据，绝不计为播放通过。</p>
<input id="search" placeholder="搜索频道、地区、网址"><select id="status"><option value="">所有状态</option><option>FAIL</option><option>PASS</option><option>PENDING</option></select><small id="count"></small>
<table><thead><tr><th>序号</th><th>分组 / 频道</th><th>状态</th><th>验证设备</th><th>真机 / x86</th><th>连续秒数</th><th>新增帧</th><th>记录</th></tr></thead><tbody id="body"></tbody></table>
<script>const rows=__DATA__;const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));function draw(){const q=document.querySelector('#search').value.toLowerCase(),s=document.querySelector('#status').value,items=rows.filter(r=>(!s||r.status===s)&&(!q||(r.name+' '+r.group+' '+r.source).toLowerCase().includes(q)));document.querySelector('#count').textContent=items.length+' 条';document.querySelector('#body').innerHTML=items.map(r=>`<tr><td>${r.index+1}</td><td><small>${esc(r.group)}</small><br>${esc(r.name)}</td><td class="${r.status}">${r.status}${r.removal!=='KEEP'?'<br><small>'+(r.removal==='REMOVED'?'已移除':'待移除')+'</small>':''}</td><td>${esc(r.device)}</td><td>${esc(r.phone)} / ${esc(r.emulator)}${r.difference?' ⚠':''}</td><td>${r.seconds||''}</td><td>${r.frames||''}</td><td><details><summary>${r.error?esc(r.error):r.attempts?'采样 '+r.attempts+' 次':'等待测试'}</summary>${r.removalReason?esc(r.removalReason)+'<br>':''}${esc(r.source)}</details></td></tr>`).join('')}document.querySelector('#search').oninput=draw;document.querySelector('#status').onchange=draw;draw();</script></html>'''
    surfaces = ('Android 4.4 真机 + API19 x86 模拟器' if any(r['phoneStatus'] != 'PENDING' for r in rows) else 'Android 4.4 API19 x86 模拟器')
    for key, value in dict(SURFACES=surfaces, DONE=summary['completed'], TOTAL=len(rows), PASS=counts['PASS'], FAIL=counts['FAIL'], PENDING=counts['PENDING'], TIME=html.escape(summary['updatedAt']), MAINTENANCE=('已从上游移除 ' if published else '已整理待移除 ')+str(len(removed))+' 条，保留 '+str(len(rows)-len(removed))+' 条', DATA=data).items():
        document = document.replace('__' + key + '__', str(value))
    (folder / 'index.html').write_text(document, 'utf-8')
    print(json.dumps({k: v for k, v in summary.items() if k != 'rows'}, ensure_ascii=False), flush=True)
    return summary

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--watch', action='store_true')
    args = parser.parse_args()
    while True:
        result = render(args.folder)
        if not args.watch or result['complete']:
            break
        time.sleep(20)
