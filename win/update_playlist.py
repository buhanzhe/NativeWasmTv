"""Refresh the exact main-branch webview.txt snapshot and its provenance."""
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.request

ROOT=Path(__file__).resolve().parent
REPO='https://github.com/buhanzhe/webSourceM3U8.git'


def main():
    tip=subprocess.check_output(['git','ls-remote',REPO,'refs/heads/main'],text=True).strip().split()
    if len(tip)!=2 or not re.fullmatch('[0-9a-f]{40}',tip[0]):
        raise RuntimeError('Cannot resolve playlist main commit')
    commit=tip[0]
    url=f'https://raw.githubusercontent.com/buhanzhe/webSourceM3U8/{commit}/webview.txt'
    with urllib.request.urlopen(url,timeout=30) as response:
        raw=response.read(8*1024*1024+1)
    if len(raw)>8*1024*1024:raise RuntimeError('Playlist exceeds 8 MiB')
    channels=groups=0
    for line in raw.decode('utf-8-sig').splitlines():
        if not line.strip():continue
        if line.endswith(',#genre#'):groups+=1
        elif ',' in line:channels+=1
        else:raise RuntimeError('Unexpected playlist format')
    if not channels:raise RuntimeError('Empty playlist')
    info={'source':'https://github.com/buhanzhe/webSourceM3U8/blob/main/webview.txt',
          'repository':'buhanzhe/webSourceM3U8','branch':'main','commit':commit,'raw_url':url,
          'sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw),'channel_count':channels,
          'group_count':groups,'fetched_at':datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat()}
    folder=ROOT/'playlists';temporary=folder/'webview.download'
    temporary.write_bytes(raw);temporary.replace(folder/'webview.txt')
    (folder/'webview.source.json').write_text(json.dumps(info,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(info,ensure_ascii=False))


if __name__=='__main__':main()
