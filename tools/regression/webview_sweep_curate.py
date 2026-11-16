"""Remove explicitly diagnosed original entries; preserve the immutable test inventory."""
import argparse
import hashlib
import json
from pathlib import Path


def curate(raw, inventory, decisions):
    lines = raw.decode('utf-8-sig').splitlines(keepends=True)
    removals = set()
    for decision in decisions:
        index = decision['index']
        if not isinstance(index, int) or index < 0 or index >= len(inventory):
            raise ValueError('Unknown original entry index')
        original = inventory[index]
        if decision.get('action') != 'REMOVE' or not decision.get('reason') or not decision.get('evidence'):
            raise ValueError('Removal requires explicit disposition, reason and evidence')
        if original['source'] != decision.get('source') or original['name'] != decision.get('name'):
            raise ValueError('Removal does not identify the exact original channel')
        line = original['line'] - 1
        if line in removals:
            raise ValueError('Duplicate removal decision')
        if line >= len(lines) or lines[line].rstrip('\r\n') != original['name'] + ',' + original['source']:
            raise ValueError('Upstream changed; refresh and reconcile before editing')
        removals.add(line)
    body = ''.join(text for line, text in enumerate(lines) if line not in removals).encode('utf-8')
    if raw.startswith(b'\xef\xbb\xbf'):
        body = b'\xef\xbb\xbf' + body
    return body


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    args = parser.parse_args()
    folder = args.folder
    inventory = json.loads((folder/'inventory.json').read_text('utf-8'))
    decisions = json.loads((folder/'source-removal-decisions.json').read_text('utf-8'))['entries']
    report = json.loads((folder/'report.json').read_text('utf-8'))
    for decision in decisions:
        row = report['rows'][decision['index']]
        if row.get('api19Pass') or any(a['status']=='PASS' and a.get('verdictEligible',True) for a in row.get('attempts',[])):
            raise ValueError('A source with an approved API19 playback PASS must be retained')
    raw = (folder/'upstream-current-webview.txt').read_bytes()
    body = curate(raw, inventory, decisions)
    target = folder/'webview-android44.txt'
    target.write_bytes(body)
    result = dict(originalEntries=len(inventory), removed=len(decisions),
        retained=len(inventory)-len(decisions), originalSha256=hashlib.sha256(raw).hexdigest(),
        curatedSha256=hashlib.sha256(body).hexdigest(), output=target.name,
        removedEntries=decisions, note='REMOVED is a source maintenance decision, never a playback PASS. Test inventory and attempt histories remain unchanged.')
    (folder/'curation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps({k:v for k,v in result.items() if k != 'removedEntries'}, ensure_ascii=False))
