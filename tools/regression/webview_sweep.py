"""Resume a frozen, per-entry API19 playback sweep; preserve each failed attempt."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import queue
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[2]

def write(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), 'utf-8')
    temporary.replace(path)

def inventory(folder):
    path = folder / 'inventory.json'
    if path.exists():
        return json.loads(path.read_text('utf-8'))
    rows, group = [], ''
    raw = (folder / 'webview.txt').read_bytes()
    for line, text in enumerate(raw.decode('utf-8-sig').splitlines(), 1):
        if text.endswith(',#genre#'):
            group = text.split(',', 1)[0]
        elif ',webview://' in text:
            name, source = text.split(',', 1)
            rows.append(dict(index=len(rows), line=line, name=name, source=source, group=group))
    write(path, rows)
    write(folder / 'inventory-identity.json', dict(sha256=hashlib.sha256(raw).hexdigest(), entries=len(rows),
        uniqueSources=len({r['source'] for r in rows}), source='https://github.com/buhanzhe/webSourceM3U8/blob/main/webview.txt',
        scope='Every webview entry, preserving duplicates, original URLs, fragments and embedded selection scripts'))
    return rows

def run(args):
    assert re.fullmatch(r'[A-Za-z0-9_-]+', args.run), 'Invalid run ID'
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    cases = inventory(folder)
    selected = set(map(int, args.indices.split(','))) if args.indices else set(range(args.start, min(args.end or len(cases), len(cases))))
    run_dir = folder / args.run
    run_dir.mkdir(exist_ok=True)
    adb = ['adb', '-s', args.serial]
    def command(*parts, timeout=45, check=True):
        result = subprocess.run(adb + list(map(str, parts)), capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', 'replace') + result.stderr.decode('utf-8', 'replace'))
        return result.stdout.decode('utf-8', 'replace').strip()
    assert command('shell', 'getprop', 'ro.build.version.sdk').strip() == '19', 'Refusing a device other than Android API19'
    baseline_path = command('shell', 'pm', 'path', 'xiao.bu.tv')
    installed = run_dir / 'installed.apk'
    identity = run_dir / 'device.json'
    previous = {}
    if identity.exists():
        previous = json.loads(identity.read_text('utf-8'))
        assert previous['serial'] == args.serial, 'A run cannot change devices'
        assert previous.get('decode', args.decode) == args.decode, 'A run cannot change decoder mode'
        assert previous['stableSeconds'] == args.hold and previous['startupLimitSeconds'] == args.startup, 'A run cannot change its playback thresholds'
        current_apk = run_dir / 'installed-current.apk'
        command('pull', baseline_path.split('package:', 1)[1].strip(), current_apk)
        current_hash = hashlib.sha256(current_apk.read_bytes()).hexdigest()
        current_apk.unlink()
        assert installed.exists() and current_hash == hashlib.sha256(installed.read_bytes()).hexdigest(), 'Installed APK changed; preserve this run and use a new run ID'
    if not installed.exists():
        command('pull', baseline_path.split('package:', 1)[1].strip(), installed)
    write(identity, dict(previous, serial=args.serial, sdk=19, model=command('shell', 'getprop', 'ro.product.model'),
          abi=command('shell', 'getprop', 'ro.product.cpu.abi'), apkPath=baseline_path, decode=args.decode,
          apkSha256=hashlib.sha256(installed.read_bytes()).hexdigest(), stableSeconds=args.hold, startupLimitSeconds=args.startup))
    terminal = {}
    ledger = run_dir / 'results.jsonl'
    if ledger.exists():
        for line in ledger.read_text('utf-8').splitlines():
            row = json.loads(line)
            terminal[row['index']] = row
    pending = [c for c in cases if c['index'] in selected and c['index'] not in terminal]
    def record(row):
        if row['index'] in terminal:
            return
        row['serial'], row['run'] = args.serial, args.run
        with ledger.open('a', encoding='utf-8') as output:
            output.write(json.dumps(row, ensure_ascii=False) + '\n')
            output.flush()
        terminal[row['index']] = row
        counts = collections.Counter(r['status'] for i, r in terminal.items() if i in selected)
        write(run_dir / 'progress.json', dict(planned=len(selected), completed=sum(counts.values()), counts=counts,
              remaining=len(selected)-sum(counts.values()), last=dict(index=row['index'], name=row.get('name'), status=row['status']),
              complete=sum(counts.values()) == len(selected)))
        print(json.dumps(dict(index=row['index'], name=row.get('name'), status=row['status'],
              completed=sum(counts.values()), planned=len(selected), error=row.get('error', '')), ensure_ascii=False), flush=True)
    def collect(index):
        remote = f'/sdcard/Android/data/xiao.bu.tv/files/webview-sweep/{args.run}/{index:04d}.json'
        local = run_dir / f'{index:04d}.json'
        result = subprocess.run(adb + ['pull', remote, str(local)], capture_output=True, timeout=20)
        if result.returncode:
            return None
        try:
            row = json.loads(local.read_text('utf-8'))
        except (ValueError, OSError):
            return None
        if row.get('status') in ('PASS', 'FAIL'):
            record(row)
            archived = True
            remote_files = [remote]
            if row.get('screenshot'):
                screenshot = row['screenshot']
                assert re.fullmatch(r'[0-9]+\.png', screenshot), 'Unexpected screenshot path'
                remote_picture = remote.rsplit('/', 1)[0] + '/' + screenshot
                copied = subprocess.run(adb + ['pull', remote_picture, str(run_dir / screenshot)], capture_output=True, timeout=20)
                archived = copied.returncode == 0 and (run_dir / screenshot).stat().st_size > 0
                remote_files.append(remote_picture)
            # The API19 emulator has a 64 MiB SD card. Retain full local evidence
            # and ledger before removing only these copied probe outputs.
            if archived:
                command('shell', 'rm', *remote_files, check=False)
        return row
    batch_number = len(list(run_dir.glob('batch-*.txt')))
    setup_attempts = collections.Counter()
    while pending:
        if (folder / 'STOP').exists() or (run_dir / 'STOP').exists():
            break
        batch_number += 1
        batch, pending = pending[:args.batch], pending[args.batch:]
        assert command('shell', 'pm', 'path', 'xiao.bu.tv') == baseline_path, 'Installed APK changed during frozen first sweep'
        local_input = run_dir / f'batch-{batch_number:04d}.json'
        write(local_input, batch)
        remote_input = f'/sdcard/Download/webview-sweep-{args.run}.json'
        command('push', local_input, remote_input)
        invocation = adb + ['shell', 'am', 'instrument', '-w', '-e', 'mode', 'webview-sweep', '-e', 'sweepRun', args.run,
            '-e', 'sweepCases', remote_input, '-e', 'sweepHoldMs', str(args.hold * 1000), '-e', 'sweepStartupMs', str(args.startup * 1000),
            '-e', 'decode', args.decode,
            '-e', 'sweepAudit', str(args.audit).lower(),
            'xiao.bu.tv.test/xiao.bu.tv.HealthBalanceInstrumentation']
        started, current, output_lines = time.monotonic(), None, []
        process = subprocess.Popen(invocation, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        messages = queue.Queue()
        def read_output():
            for line in iter(process.stdout.readline, b''):
                messages.put(line)
        threading.Thread(target=read_output, daemon=True).start()
        try:
            while process.poll() is None or not messages.empty():
                if time.monotonic() - started > (args.startup + args.hold + 20) * len(batch) + 60:
                    command('shell', 'am', 'force-stop', 'xiao.bu.tv', check=False)
                    process.terminate()
                    break
                try:
                    raw = messages.get(timeout=1)
                except queue.Empty:
                    continue
                text = raw.decode('utf-8', 'replace')
                output_lines.append(text)
                match = re.search(r'INSTRUMENTATION_STATUS: index=(\d+)', text)
                if match:
                    if current is not None:
                        collect(current)
                    current = int(match.group(1))
                    print(f'START {current}/{len(cases)} batch={batch_number}', flush=True)
            process.wait(timeout=30)
        finally:
            if process.poll() is None:
                process.terminate()
            (run_dir / f'batch-{batch_number:04d}.txt').write_text(''.join(output_lines), 'utf-8')
        crashed = 'Process crashed' in ''.join(output_lines) or process.returncode != 0
        for case in batch:
            if case['index'] in terminal:
                continue
            row = collect(case['index'])
            if case['index'] in terminal:
                continue
            if row and row.get('status') == 'RUNNING':
                environment_error = 'ENOSPC' in ''.join(output_lines)
                row.update(status='FAIL', error='TEST_STORAGE_FULL' if environment_error else 'PROCESS_CRASH_OR_HANG' if crashed else 'INSTRUMENTATION_INTERRUPTED', batch=batch_number)
                record(row)
            else:
                setup_attempts[case['index']] += 1
                if setup_attempts[case['index']] >= 3:
                    raise RuntimeError(f'Repeated instrumentation setup failure before case {case["index"]}; case remains untested')
                pending.insert(0, case)
        command('pull', '/sdcard/Android/data/xiao.bu.tv/files/health-balance-result.json', run_dir / f'batch-{batch_number:04d}-summary.json', check=False)
        # Restore the durable original snapshot even if the app died before finally.
        restore = command('shell', 'am', 'instrument', '-w', '-e', 'mode', 'webview-sweep-restore', '-e', 'sweepRun', args.run,
                'xiao.bu.tv.test/xiao.bu.tv.HealthBalanceInstrumentation', timeout=45)
        (run_dir / f'batch-{batch_number:04d}-restore.txt').write_text(restore, 'utf-8')
        if 'Sweep baseline restored' not in restore:
            raise RuntimeError('Original preference recovery failed; stopping before further mutation')
    counts = collections.Counter(r['status'] for i, r in terminal.items() if i in selected)
    summary = dict(planned=len(selected), completed=sum(counts.values()), counts=counts, complete=sum(counts.values()) == len(selected))
    write(run_dir / 'summary.json', summary)
    print(json.dumps(summary), flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--start', type=int, default=0)
    parser.add_argument('--end', type=int)
    parser.add_argument('--indices')
    parser.add_argument('--batch', type=int, default=8)
    parser.add_argument('--hold', type=int, default=10)
    parser.add_argument('--startup', type=int, default=45)
    parser.add_argument('--decode', choices=['auto', 'software', 'hardware'], default='auto')
    parser.add_argument('--audit', action='store_true')
    run(parser.parse_args())
