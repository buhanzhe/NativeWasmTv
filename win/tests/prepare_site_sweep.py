"""Freeze the complete bundled WebView inventory and one/two cases per site."""
from pathlib import Path
from urllib.parse import urlsplit,parse_qs
import argparse,collections,hashlib,json

root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
source=root/'win/playlists/webview.txt';raw=source.read_bytes();group='';rows=[]
for number,line in enumerate(raw.decode('utf-8-sig').splitlines(),1):
    if line.endswith(',#genre#'):group=line.rsplit(',',1)[0];continue
    name,sep,url=line.partition(',')
    if not sep or not url.startswith('webview://'):continue
    page=urlsplit(url[10:]);rows.append({'id':'site-'+hashlib.sha256(url.encode()).hexdigest()[:12],'name':name,'group':group,'source_line':number,'url':url,'site':page.netloc,'source':'bundled-playlist','ujs':bool(parse_qs(page.query).get('ujs'))})
sites=collections.defaultdict(list)
for row in rows:
    if all(r['url']!=row['url'] for r in sites[row['site']]):sites[row['site']].append(row)
selected=[]
for site,entries in sites.items():
    chosen=[entries[0]]
    if len(entries)>1 and any(e['ujs'] for e in entries):chosen.append(entries[-1])
    selected.extend(chosen)
for name,url in [('虎牙11342386','webview://https://www.huya.com/11342386'),('虎牙30995188','webview://https://www.huya.com/30995188'),('YouTube用户示例','webview://https://www.youtube.com/watch?v=FQvIbwJts94')]:
    selected.append({'id':'user-'+hashlib.sha256(url.encode()).hexdigest()[:12],'name':name,'group':'用户反馈','source_line':None,'url':url,'site':urlsplit(url[10:]).netloc,'source':'user-example','ujs':False})
priority=['live.fjtv.net','www.gdtv.cn','www.cztv.com','tv.cctv.com','yangshipin.cn','www.mgtv.com','tv.gxtv.cn','www.huya.com','www.youtube.com']
selected.sort(key=lambda e:priority.index(e['site']) if e['site'] in priority else len(priority))
manifest={'kind':'site-sweep','playlist_sha256':hashlib.sha256(raw).hexdigest(),'source_commit':json.loads((root/'win/playlists/webview.source.json').read_text('utf8'))['commit'],'playlist_webview_entries':len(rows),'playlist_unique_urls':len({r['url'] for r in rows}),'playlist_sites':len(sites),'planned_cases':len(selected),'planned_sites':len({r['site'] for r in selected}),'scope':'每站点全部覆盖；有UJS且多频道的站点追加另一个频道；保留完整原始URL。未选中的同站频道不计为已经实播。','channels':selected}
(a.output/'inventory.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf8');(a.output/'selection.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps({k:v for k,v in manifest.items() if k!='channels'},ensure_ascii=False))
