"""Exact removals preserve URL scripts, duplicate entries, grouping and byte formatting."""
import importlib.util
from pathlib import Path

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('curate',root/'tools/regression/webview_sweep_curate.py')
module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
raw = b'\xef\xbb\xbfGroup,#genre#\r\nA,webview://https://example.test/?ujs=YWJj%2B#x\r\nB,webview://https://example.test/?ujs=YWJj%2B#x\r\nC,webview://https://example.test/live\r\n'
inventory = [dict(index=0,line=2,name='A',source='webview://https://example.test/?ujs=YWJj%2B#x'),dict(index=1,line=3,name='B',source='webview://https://example.test/?ujs=YWJj%2B#x')]
decision = dict(inventory[0],action='REMOVE',reason='Verified missing stream',evidence=['actual-original-player.json'])
assert module.curate(raw,inventory,[decision]) == raw.replace(b'A,webview://https://example.test/?ujs=YWJj%2B#x\r\n',b'')
assert module.curate(raw,inventory,[]) == raw
def rejected(body, decisions):
    try: module.curate(body,inventory,decisions)
    except ValueError: return
    raise AssertionError('Invalid or stale removal accepted')
rejected(raw,[decision,decision]);rejected(raw,[dict(decision,source='webview://https://other.test/')])
rejected(raw,[dict(decision,reason='')]);rejected(raw,[dict(decision,evidence=[])])
rejected(raw,[dict(decision,action='PASS')]);rejected(raw.replace(b'A,webview',b'Changed,webview'),[decision])
print('PASS exact evidenced removals, preserved duplicates/URL fragments/scripts/BOM/CRLF and stale upstream protection')
