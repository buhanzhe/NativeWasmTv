"""Repeatable regression of the installed nTv APK; never installs or clears data."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen
import uuid
import zipfile

from standard_fixtures import FixtureServer, prepare

ROOT = Path(__file__).resolve().parents[2]
PRINT_LOCK = threading.Lock()
STOP = threading.Event()
SETTINGS = ['playlistSources', 'decodeMode', 'playerEngine', 'autoSwitchSourceSeconds',
            'webViewAutoPlay', 'webViewAutoPlaySniffed', 'webViewMinimalMode',
            'epgEnabled', 'epgUrls']
SMOKE = ['kernel-settings', 'browser-ui', 'images', 'regional-import', 'async-import', 'native-files']
RELEASE = SMOKE + ['embedded-browser-ui', 'system-private-files', 'web-system', 'web-lite', 'source-failure',
                   'startup-failures', 'live-command-errors', 'unsupported-hevc',
                   'official-providers', 'rapid-tuning', 'epg-cold-retry', 'minimal-exit',
                   'background-resume', 'soak']


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf8')
    temporary.replace(path)


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def say(message):
    with PRINT_LOCK:
        print(message, flush=True)


def run(command, timeout=30, **kwargs):
    result = subprocess.run(list(map(str, command)), stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout, **kwargs)
    if result.returncode:
        raise RuntimeError(f'{command[0]} exited {result.returncode}: '
                           + result.stdout.decode('utf8', 'replace')[-3000:])
    return result.stdout


def advancing(previous, current):
    """A live HLS window may legitimately rebase its clock backwards."""
    return bool(previous and current.get('prepared') and current.get('playing')
                and previous.get('sourceKey') == current.get('sourceKey')
                and current.get('positionMs', 0) >= 0
                and (current.get('positionMs', 0) > previous.get('positionMs', 0)
                     or current.get('durationMs', 0) <= 0
                     and current.get('positionMs', 0) != previous.get('positionMs', 0))
                and (current.get('audioOnly') or current.get('outputFps', 0) > 0
                     or current.get('currentSourceStats', {}).get('width', 0) > 0))


def verdict(cases, planned, restored):
    by_name = {row['case']: row['status'] for row in cases}
    if not restored or any(row['status'] in ('FAIL', 'ERROR') for row in cases):
        return 'FAIL'
    if len(by_name) != len(cases):
        return 'INCOMPLETE'
    if any(by_name.get(name) not in ('PASS', 'NOT_APPLICABLE') for name in planned):
        return 'INCOMPLETE'
    return 'PASS'


class Device:
    def __init__(self, spec, args, directory, fixtures=None):
        self.spec, self.args, self.directory, self.fixtures = spec, args, directory, fixtures
        self.serial, self.package = spec['serial'], args.package
        self.base = None
        self.saved = None
        self.mutated = False
        self.log = self.logger = None
        self.log_offset = 0
        self.pids = set()
        self.group = None
        self.report = {'device': spec, 'cases': [], 'restored': False, 'status': 'INCOMPLETE'}
        self.directory.mkdir(parents=True, exist_ok=True)

    def adb(self, *parts, timeout=30):
        return run([self.args.adb, '-s', self.serial, *parts], timeout).decode('utf8', 'replace').strip()

    def api(self, path, value=None, raw=None, timeout=25):
        body = raw if raw is not None else None if value is None else json.dumps(value).encode('utf8')
        request = Request(self.base + path, data=body, headers={
            'Content-Type': 'application/octet-stream' if raw is not None else 'application/json'})
        with urlopen(request, timeout=timeout) as response:
            data = json.load(response)
        if data.get('ok') is False:
            raise AssertionError(data.get('message', 'API rejected request'))
        return data

    def until(self, check, seconds=45):
        end = time.monotonic() + seconds
        last = None
        while time.monotonic() < end:
            if STOP.is_set():
                raise RuntimeError('Interrupted; restoring device configuration')
            try:
                value = check()
                if value:
                    return value
            except (URLError, TimeoutError, ConnectionError) as error:
                last = str(error)
            time.sleep(.7)
        raise AssertionError(f'Condition not met within {seconds}s; last network error: {last}')

    def settings(self, value):
        return self.api('/api/settings', value, timeout=90)

    def play(self, channel, source=0, group=None):
        return self.api('/api/control', {'action': 'play', 'group': self.group if group is None else group,
                                         'channel': channel, 'source': source})

    def upload(self, file):
        return self.api('/api/quick/media?name=' + quote(file.name), raw=file.read_bytes())

    def foreground(self):
        text = self.adb('shell', 'dumpsys', 'activity', 'activities')
        lines = [line.strip() for line in text.splitlines()
                 if 'mResumedActivity' in line or 'mResumed=' in line]
        if not any(self.package + '/' in line for line in lines):
            raise AssertionError('Test interrupted: foreground changed: ' + repr(lines))
        return lines

    def screenshot(self, name):
        remote = '/sdcard/ntv-standard-' + self.args.run_id + '.png'
        target = self.directory / (name + '.png')
        self.adb('shell', 'screencap', '-p', remote)
        self.adb('pull', remote, str(target))
        self.adb('shell', 'rm', remote)
        return target

    def remember_pid(self):
        # ps works on Android 4.x, where pidof is absent.
        for line in self.adb('shell', 'ps').splitlines():
            columns = line.split()
            if len(columns) > 2 and columns[-1] == self.package and columns[1].isdigit():
                self.pids.add(columns[1])

    def ready(self, seconds=60, name=None):
        previous = None
        samples = []
        def check():
            nonlocal previous
            current = self.api('/api/media')
            samples.append(current)
            good = advancing(previous, current) and (name is None or current.get('name') == name)
            previous = current
            return current if good else None
        try:
            return self.until(check, seconds)
        finally:
            write_json(self.directory / ('samples-' + uuid.uuid4().hex[:8] + '.json'), samples)

    def observe(self, seconds, name=None, startup=90):
        begin = time.monotonic()
        last = self.ready(startup, name)
        started = time.monotonic()
        samples, pictures = [], []
        last_picture = self.screenshot('observe-' + uuid.uuid4().hex[:8])
        from PIL import Image, ImageChops, ImageStat
        def picture(path):
            with Image.open(path) as im:
                w, h = im.size
                return im.convert('RGB').crop((w*.2, h*.2, w*.8, h*.8)).resize((160, 90))
        prior_image = picture(last_picture)
        next_picture = 20
        try:
            while time.monotonic() - started < seconds:
                if STOP.is_set():
                    raise RuntimeError('Interrupted; restoring device configuration')
                time.sleep(min(3, max(.1, seconds - (time.monotonic() - started))))
                self.foreground()
                media = self.api('/api/media')
                samples.append({'elapsed': round(time.monotonic()-started, 2), 'media': media})
                if not advancing(last, media):
                    raise AssertionError('Playback stopped, reset, or clock failed to advance')
                last = media
                if time.monotonic() - started >= next_picture:
                    path = self.screenshot('observe-' + uuid.uuid4().hex[:8])
                    image = picture(path)
                    score = sum(ImageStat.Stat(ImageChops.difference(prior_image, image)).mean)/3
                    pictures.append(score)
                    prior_image, next_picture = image, next_picture + 20
                    with (self.directory/'memory.txt').open('a', encoding='utf8') as output:
                        output.write(self.adb('shell', 'dumpsys', 'meminfo', self.package) + '\n')
                    say(f'{self.spec["name"]}: playback {int(time.monotonic()-started)}/{seconds}s')
            # A ticking clock alone cannot establish moving pictures on old devices.
            if not last.get('audioOnly'):
                image = picture(self.screenshot('observe-end-' + uuid.uuid4().hex[:8]))
                pictures.append(sum(ImageStat.Stat(ImageChops.difference(prior_image, image)).mean)/3)
                if not any(value > .5 for value in pictures):
                    raise AssertionError('No picture change observed; moving video not established')
            return {'startupSeconds': round(started-begin, 2), 'observedSeconds': round(time.monotonic()-started, 2),
                    'pictureDifferences': pictures, 'decoder': last.get('currentSourceStats', {}).get('videoDecoderName')}
        finally:
            write_json(self.directory/('observe-'+uuid.uuid4().hex[:8]+'.json'), samples)

    def case(self, name, work):
        start = time.monotonic()
        row = {'case': name, 'status': 'ERROR'}
        self.report['cases'].append(row)
        say(self.spec['name'] + ': START ' + name)
        try:
            self.remember_pid()
            detail = work()
            row.update(status='NOT_APPLICABLE' if isinstance(detail,dict) and detail.get('notApplicable') else 'PASS', detail=detail)
        except Exception as error:
            row.update(status='FAIL', error=str(error), trace=traceback.format_exc())
            try:
                self.screenshot('failed-' + name)
                (self.directory/('failed-'+name+'-activity.txt')).write_text(
                    self.adb('shell', 'dumpsys', 'activity', 'activities'), encoding='utf8')
            except Exception as capture_error:
                row['captureError'] = str(capture_error)
        row['seconds'] = round(time.monotonic() - start, 2)
        self.save()
        say(f'{self.spec["name"]}: {row["status"]} {name}')

    def save(self):
        write_json(self.directory/'result.json', self.report)

    def setup(self):
        sdk = int(self.adb('shell', 'getprop', 'ro.build.version.sdk'))
        if sdk != self.spec['sdk']:
            raise AssertionError(f'Expected API {self.spec["sdk"]}, got {sdk}; refusing this device')
        self.report['identity'] = {'sdk': sdk, 'model': self.adb('shell', 'getprop', 'ro.product.model'),
                                   'release': self.adb('shell', 'getprop', 'ro.build.version.release')}
        package = self.adb('shell', 'dumpsys', 'package', self.package)
        (self.directory/'package.txt').write_text(package, encoding='utf8')
        paths = [line[8:] for line in self.adb('shell', 'pm', 'path', self.package).splitlines()
                 if line.startswith('package:')]
        if len(paths) != 1:
            raise AssertionError('Expected one installed APK; package missing or split installation')
        apk = self.directory/'installed.apk'
        self.adb('pull', paths[0], apk, timeout=90)
        self.report['installedSha256'] = digest(apk)
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            has_core = any(name.endswith('/libntvLitebro.so') for name in names)
            has_assets = 'assets/browser.js' in names
            self.report['bundledLiteCore'] = has_core and has_assets
        apk.unlink()
        if self.args.apk and self.report['installedSha256'] != digest(self.args.apk):
            raise AssertionError('Installed APK does not match --apk; install candidate manually first')
        port = self.adb('forward', 'tcp:0', 'tcp:9966')
        if not port.isdigit():
            raise RuntimeError('ADB did not return an allocated forward port')
        self.base = 'http://127.0.0.1:' + port
        self.forward = 'tcp:' + port
        # Do not silently launch, replace, or reset an app during discovery.
        self.saved = self.api('/api/state')
        self.saved['_regression'] = {'selectedTheme': self.api('/api/tv-ui')['selected']}
        write_json(self.directory/'before.json', self.saved)
        self.foreground()
        self.report['liteBrowserSupported'] = self.saved['settings'].get('liteBrowserSupported')
        if has_core != has_assets or self.report['bundledLiteCore'] != self.report['liteBrowserSupported']:
            raise AssertionError('APK contents and runtime Lite capability disagree')

    def start_log(self):
        self.log = (self.directory/'device.log').open('wb')
        self.logger = subprocess.Popen([self.args.adb, '-s', self.serial, 'logcat', '-v', 'threadtime',
                                        'AndroidRuntime:E', 'ActivityManager:I', 'EpgManager:V',
                                        'MainActivity:I', '*:S'], stdout=self.log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        time.sleep(1)
        self.log_offset = (self.directory/'device.log').stat().st_size

    def restore(self, saved=None):
        saved = saved or self.saved
        if not saved:
            return
        s = saved['settings']
        current=self.api('/api/state')
        patch={key:s[key] for key in SETTINGS if current['settings'].get(key)!=s[key]}
        self.settings({**patch,'rememberChannelWebEngine':False,
                       'webSourceEngine':s['configuredWebSourceEngine']})
        def catalog_shape(state):
            return [(g['name'],[(c['name'],c.get('sourceCount')) for c in g['channels']]) for g in state['groups']]
        expected_catalog=catalog_shape(saved)
        self.until(lambda:catalog_shape(self.api('/api/state'))==expected_catalog,30)
        c = saved['current']
        self.play(c['channelIndex'], c['sourceIndex'], c['groupIndex'])
        self.settings({'rememberChannelWebEngine': s['rememberChannelWebEngine']})
        theme = saved.get('_regression', {}).get('selectedTheme')
        if theme is not None:
            if self.api('/api/tv-ui')['selected']!=theme:self.api('/api/tv-ui', {'id': theme})
            if self.api('/api/tv-ui')['selected'] != theme:
                raise AssertionError('Theme restore failed')
        matches=0
        def selected():
            nonlocal matches
            state=self.api('/api/state')
            matches=matches+1 if all(state['current'].get(k)==c.get(k) for k in ('groupIndex','channelIndex','sourceIndex')) else 0
            return state if matches>=2 else None
        restored=self.until(selected,20)
        if restored['settings']['webSourceEngine']!=s['webSourceEngine']:
            self.settings({'webSourceEngine':s['webSourceEngine']})
        if s.get('rememberChannelDecode') and restored['settings'].get('currentDecodeMode')!=s.get('currentDecodeMode'):
            self.settings({'channelDecodeMode':s['currentDecodeMode']})
        restored=self.until(selected,20)
        keys = SETTINGS + ['rememberChannelWebEngine', 'configuredWebSourceEngine', 'webSourceEngine','currentDecodeMode']
        mismatch = [key for key in keys if restored['settings'].get(key) != s.get(key)]
        mismatch += ['current.'+key for key in ('groupIndex','channelIndex','sourceIndex')
                     if restored['current'].get(key) != c.get(key)]
        write_json(self.directory/'restored.json', restored)
        if mismatch:
            raise AssertionError('Restore verification differs: ' + ', '.join(mismatch))
        self.report['restored'] = True

    def close(self):
        if self.logger:
            self.logger.terminate()
            try:
                self.logger.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.logger.kill()
                self.logger.wait(timeout=5)
            self.log.close()
        if getattr(self, 'forward', None):
            self.adb('forward', '--remove', self.forward)

    def import_channels(self, channels):
        source_id = 'standard-' + self.args.run_id
        source = {'id': source_id, 'name': 'Standard regression',
                  'location': self.fixture_url + '/regression.txt', 'enabled': True}
        failed = {'id': source_id+'-failed', 'name': 'Injected failed source',
                  'location': self.fixture_url+'/slow.m3u8', 'enabled': True}
        result = self.api('/api/playlist/merge', {'async': True, 'sources': [source, failed],
            'playlist': '#EXTM3U\n', 'sourcePlaylists': [{'id': source_id, 'playlist': '#EXTM3U\n',
            'channels': channels}], 'failedSourceIds': [failed['id']], 'mergedSourceCount': 1})
        def complete():
            job = self.api('/api/playlist/merge/status?id='+quote(result['jobId']))
            if job['state'] == 'failed':
                raise AssertionError(job.get('message'))
            return job if job['state'] == 'done' else None
        return self.until(complete, 75)

    def playlist(self):
        channels = [
            {'name': 'local-video', 'urls': [self.fixture_url+'/video.mp4']},
            {'name': 'local-hls', 'urls': [self.fixture_url+'/video.m3u8']},
            {'name': 'missing-next', 'urls': [self.fixture_url+'/missing.m3u8', self.fixture_url+'/video.m3u8']},
            {'name': 'slow-next', 'urls': [self.fixture_url+'/slow.m3u8', self.fixture_url+'/video.m3u8']},
            {'name': 'all-failed', 'urls': [self.fixture_url+'/missing1.m3u8', self.fixture_url+'/missing2.m3u8']},
            {'name': 'web-video', 'urls': ['webview://'+self.fixture_url+'/page.html']},
        ] + [{'name': provider['name'], 'urls': [provider['url']]} for provider in self.args.providers]
        for channel in channels:
            channel['group'] = 'NTV standard ' + self.args.run_id
        return channels

    def ensure_playlist(self):
        self.import_channels(self.playlist())
        self.group = next(i for i, group in enumerate(self.api('/api/state')['groups'])
                          if group['name'] == 'NTV standard ' + self.args.run_id)

    def kernel_settings(self):
        s = self.api('/api/state')['settings']
        self.settings({'rememberChannelWebEngine': False})
        try:
            for engine in ['system', 'lite'] if s['liteBrowserSupported'] else ['system']:
                self.settings({'webSourceEngine': engine})
                if self.api('/api/state?view=browser')['settings']['webSourceEngine'] != engine:
                    raise AssertionError('Engine setting was not saved')
            if not s['liteBrowserSupported']:
                try:
                    self.settings({'webSourceEngine': 'lite'})
                except (HTTPError, AssertionError):
                    pass
                else:
                    raise AssertionError('Missing kernel accepted')
            return {'liteBrowserSupported': s['liteBrowserSupported']}
        finally:
            self.settings({'webSourceEngine':s['configuredWebSourceEngine']})
            self.settings({'rememberChannelWebEngine':s['rememberChannelWebEngine']})

    def images(self):
        from PIL import Image
        for ext in ('jpg', 'png'):
            file = self.args.fixture_dir / ('image.'+ext)
            self.upload(file)
            time.sleep(1)
            with Image.open(self.screenshot('image-'+ext)) as image:
                pixels = list(image.convert('RGB').resize((160, 90)).getdata())
            count = sum(abs(r-240)<20 and abs(g-180)<20 and abs(b-40)<20 for r,g,b in pixels)
            if count < 1500:
                raise AssertionError(f'{ext} not visible: {count} expected-color pixels')
            self.adb('shell', 'input', 'keyevent', '4')
            self.until(lambda: self.api('/api/media').get('name') != file.name, 10)
        before = self.api('/api/media')['sourceKey']
        try:
            self.api('/api/quick/media?name=corrupt.png', raw=b'not an image')
        except HTTPError as error:
            if error.code != 400:
                raise
        else:
            raise AssertionError('Corrupt image was accepted')
        if self.api('/api/media')['sourceKey'] != before:
            raise AssertionError('Corrupt image interrupted playback')

    def browser_ui(self):
        node = shutil.which('node')
        npx = Path(node).parent/'node_modules/npm/bin/npx-cli.js' if node else Path('missing')
        if not npx.is_file():
            raise RuntimeError('Node.js with npm/npx is required for the browser UI case')
        session = 'ntv-'+self.args.run_id+'-'+str(self.spec['sdk'])
        command = [node, str(npx), '--yes', '--package', '@playwright/cli', 'playwright-cli', '-s='+session]
        code = '''async page => {
 const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 await page.setViewportSize({width:390,height:844});
 await page.goto(BASE+'/pages/browser.html');
 await page.waitForFunction(()=>typeof state!=='undefined'&&state&&state.settings);
 const supported=await page.evaluate(()=>state.settings.liteBrowserSupported===true);
 const select=page.getByRole('combobox',{name:'网页内核',exact:true});
 if(supported){
  if(!await select.isVisible()||!await select.isEnabled())throw Error('Browser edition kernel selector missing or disabled');
  for(const engine of ['lite','system']){
   const label=engine==='lite'?'轻量嗅探':'系统';
   if(await select.inputValue()!==engine){
    await select.click();
    const saved=page.waitForResponse(r=>r.url()===BASE+'/api/settings'&&r.request().method()==='POST');
    await page.getByRole('listbox',{name:'网页内核',exact:true}).getByRole('option',{name:label,exact:true}).click();
    const response=await saved;if(!response.ok()||(await response.json()).ok===false)throw Error('Save rejected');
   }
   await page.reload();
   await page.waitForFunction(engine=>typeof state!=='undefined'&&state&&state.settings.webSourceEngine===engine,engine);
  }
 }else if(await select.isVisible())throw Error('Standard edition must hide the kernel selector');
 const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);
 if(overflow||errors.length)throw Error(JSON.stringify({overflow,errors}));
 await page.screenshot({path:SCREENSHOT,fullPage:true});
 return {supported,selectorVisible:await select.isVisible(),errors};
}'''.replace('BASE',json.dumps(self.base)).replace('SCREENSHOT',json.dumps(str(self.directory/'browser-ui.png')))
        script = self.directory/'browser-ui.js'
        script.write_text(code,encoding='utf8')
        self.settings({'rememberChannelWebEngine':False})
        try:
            run(command+['open',self.base+'/pages/browser.html','--headed'],timeout=90,cwd=ROOT)
            output = run(command+['run-code','--filename',str(script)],timeout=120,cwd=ROOT)
            (self.directory/'browser-ui.txt').write_bytes(output)
            if b'### Error' in output or b'### Result' not in output:
                raise AssertionError('Browser UI regression failed; see browser-ui.txt')
        finally:
            try:
                run(command+['close'],timeout=20,cwd=ROOT)
            finally:
                s=self.saved['settings']
                self.settings({'webSourceEngine':s['configuredWebSourceEngine']})
                self.settings({'rememberChannelWebEngine':s['rememberChannelWebEngine']})

    def embedded_browser_ui(self):
        component=self.package+'.test/xiao.bu.tv.BrowserKernelInstrumentation'
        try:
            output=self.adb('shell','am','instrument','-w',component,timeout=165)
            (self.directory/'embedded-browser-ui.txt').write_text(output,encoding='utf8')
            if 'PASS actual system WebView kernel selector:' not in output:
                raise AssertionError('Embedded WebView check failed; install the matching AndroidTest APK first. See embedded-browser-ui.txt')
        finally:
            self.adb('shell','am','start','-n',self.package+'/.MainActivity')
            self.until(lambda:self.api('/api/state'),60)
            self.remember_pid()

    def regional_import(self):
        data = (ROOT/'tests/fixtures/regional-channels.txt').read_bytes()
        result = self.api('/api/playlist/upload?id=standard-regional&name=regional.txt', raw=data)
        self.settings({'playlistSources': [{'id': 'standard-regional', 'name': 'Regional test',
                                           'location': result['location'], 'enabled': True}]})
        count = sum(len(group['channels']) for group in self.api('/api/state')['groups']
                    if group['name'] == '地区频道')
        if count != 5:
            raise AssertionError(f'Expected 5 regional channels, got {count}')
        return {'parsedChannels': count}

    def async_import(self):
        channels = [{'name': 'unique-'+str(n), 'group': 'NTV import count',
                     'urls': [self.fixture_url+'/video.m3u8?id='+str(n)]} for n in range(1000)]
        start = time.monotonic()
        self.import_channels(self.playlist()+channels)
        state = self.api('/api/state')
        count = sum(len(group['channels']) for group in state['groups'] if group['name']=='NTV import count')
        if count != 1000:
            raise AssertionError(f'Expected 1000 unique channels, got {count}')
        if len(state['settings']['playlistSources']) != 2:
            raise AssertionError('Failed source was not preserved')
        return {'channels': count, 'seconds': round(time.monotonic()-start, 3), 'failedSourceSkipped': True}

    def controls(self, file, system=False):
        self.upload(file)
        media = self.ready(60, file.name)
        if system and '系统' not in media.get('currentSourceStats', {}).get('videoDecoderName', ''):
            raise AssertionError('Expected Android system player')
        if not media.get('durationMs', 0):
            raise AssertionError('Finite file has no duration')
        self.api('/api/media/control', {'action': 'pause'})
        time.sleep(.5)
        a = self.api('/api/media')
        time.sleep(.5)
        b = self.api('/api/media')
        if b.get('playing') or abs(b['positionMs']-a['positionMs']) > 400:
            raise AssertionError('Pause failed')
        self.api('/api/media/control', {'action': 'seek', 'positionMs': 8000, 'sourceKey': media['sourceKey']})
        self.api('/api/media/control', {'action': 'play'})
        self.until(lambda: self.api('/api/media').get('positionMs', 0) >= 7500, 15)
        return self.ready(15, file.name)

    def native_files(self):
        self.settings({'playerEngine': 'ijk'})
        modes = ['software', 'hardware', 'auto'] if self.spec['sdk'] >= 16 else ['software', 'auto']
        result = []
        for mode in modes:
            self.settings({'decodeMode': mode})
            media = self.controls(self.args.fixture_dir/'video.mp4')
            result.append({'requestedMode': mode, 'decoder': media.get('currentSourceStats', {}).get('videoDecoderName')})
        return result

    def system_private_files(self):
        self.upload(self.args.fixture_dir/'video.mp4')
        self.ready()
        self.settings({'playerEngine': 'system'})
        result = []
        for _ in range(2):
            for name in ('mono.wav', 'stereo.wav', 'video.mp4'):
                media = self.controls(self.args.fixture_dir/name, system=True)
                result.append({'name': name, 'durationMs': media['durationMs']})
        return result

    def web(self, engine):
        if engine == 'lite' and not self.saved['settings']['liteBrowserSupported']:
            return {'notApplicable': 'APK does not bundle Lite'}
        self.ensure_playlist()
        self.settings({'playerEngine': 'ijk', 'rememberChannelWebEngine': False, 'webSourceEngine': engine,
                       'webViewAutoPlay': True, 'webViewAutoPlaySniffed': True, 'webViewMinimalMode': True,
                       'autoSwitchSourceSeconds': 0})
        self.play(5)
        return self.observe(20, 'web-video', startup=90)

    def source_failure(self):
        self.ensure_playlist()
        self.settings({'playerEngine':'ijk', 'autoSwitchSourceSeconds': 5})
        result = []
        for channel, name in [(2,'missing-next'), (3,'slow-next')]:
            self.play(channel)
            self.ready(75, name)
            media = self.api('/api/media')
            if media['sourceIndex'] != 1:
                raise AssertionError('Did not select the next configured source')
            result.append({'name': name, 'selectedSource': 1})
        self.play(4)
        self.until(lambda: (lambda m: m['sourceIndex']==1 and not m['playing'])(self.api('/api/media')), 60)
        time.sleep(6)
        media = self.api('/api/media')
        if media['name'] != 'all-failed' or media['sourceIndex'] != 1 or media['playing']:
            raise AssertionError('Exhausted sources did not stay on selected channel')
        return result

    def startup_failures(self):
        self.import_channels([{'name':'broken-segments','group':'NTV broken',
                               'urls':[self.fixture_url+'/corrupt.m3u8']}])
        self.group=next(i for i,g in enumerate(self.api('/api/state')['groups']) if g['name']=='NTV broken')
        self.settings({'playerEngine':'ijk','autoSwitchSourceSeconds':0})
        start=time.monotonic();self.play(0)
        media=self.until(lambda:(lambda m:m if m.get('loadError') else None)(self.api('/api/media')),32)
        return {'feedbackSeconds':round(time.monotonic()-start,2),'loadError':media['loadError']}

    def live_command_errors(self):
        self.import_channels([{'name':'live-controls','group':'NTV live controls',
                               'urls':[self.fixture_url+'/live.m3u8']}])
        self.group=next(i for i,g in enumerate(self.api('/api/state')['groups']) if g['name']=='NTV live controls')
        self.settings({'playerEngine':'ijk','autoSwitchSourceSeconds':0})
        self.play(0);before=self.ready(60,'live-controls')
        start=time.monotonic();responses=[]
        for _ in range(20):
            for action in ('seek','speed'):
                try:
                    self.api('/api/media/control',{'action':action,'positionMs':95361122,'speed':2,
                                                  'sourceKey':before['sourceKey']})
                except HTTPError as error:
                    try:data=json.load(error)
                    finally:error.close()
                    if error.code!=409 or '不支持' not in data.get('message',''):
                        raise AssertionError('Wrong live-control status/message: '+str(data))
                    responses.append(data)
                else:raise AssertionError('Live '+action+' accepted')
        after=self.ready(15,'live-controls')
        if after['positionMs']>before['positionMs']+(time.monotonic()-start)*1000+30000:
            raise AssertionError('Rejected seek corrupted the presentation clock')
        return {'rejected':len(responses),'beforeMs':before['positionMs'],'afterMs':after['positionMs'],
                'messages':list({r['message'] for r in responses})}

    def unsupported_hevc(self):
        self.import_channels([{'name':'hevc-check','group':'NTV HEVC',
                               'urls':[self.fixture_url+'/hevc.m3u8']}])
        self.group=next(i for i,g in enumerate(self.api('/api/state')['groups']) if g['name']=='NTV HEVC')
        attempts=[]
        for engine,mode in [('system','auto'),('ijk','hardware'),('ijk','auto')]:
            self.settings({'playerEngine':engine,'decodeMode':mode,'autoSwitchSourceSeconds':0})
            self.play(0);start=time.monotonic();samples=[]
            def done():
                media=self.api('/api/media');samples.append(media)
                if media.get('loadError'):
                    if not any(s in media['loadError'] for s in ('H.265','HEVC','不支持','无法','失败','超时')):
                        raise AssertionError('Unclear codec error')
                    return {'outcome':'explicit-error','message':media['loadError']}
                if len(samples)>1 and media.get('videoFrameConfirmed') and advancing(samples[-2],media):
                    self.observe(3,'hevc-check',10)
                    return {'outcome':'played-on-capable-device','decoder':media.get('currentSourceStats',{}).get('videoDecoderName')}
            try:
                result=self.until(done,35)
                attempts.append({'engine':engine,'mode':mode,**result,'seconds':round(time.monotonic()-start,2)})
            finally:
                write_json(self.directory/('hevc-'+engine+'-'+mode+'.json'),samples)
                write_json(self.directory/'hevc-startup.json',attempts)
        return attempts

    def official_providers(self):
        self.ensure_playlist()
        rows = []
        for index, provider in enumerate(self.args.providers, 6):
            engine = provider['engine']
            if engine == 'lite' and not self.saved['settings']['liteBrowserSupported']:
                rows.append({'name': provider['name'], 'status': 'NOT_APPLICABLE'})
                continue
            self.settings({'playerEngine': 'ijk', 'rememberChannelWebEngine': False,
                           'webSourceEngine': engine, 'autoSwitchSourceSeconds': 0,
                           'webViewAutoPlay': True, 'webViewAutoPlaySniffed': True})
            try:
                self.play(index)
                rows.append({'name': provider['name'], 'status': 'PASS', **self.observe(30, provider['name'], 120)})
            except Exception as error:
                rows.append({'name': provider['name'], 'status':'FAIL', 'error':str(error)})
            write_json(self.directory/'official-providers.json', rows)
        if any(row['status']=='FAIL' for row in rows):
            raise AssertionError('Official provider failed; see official-providers.json (no alternate provider used)')
        return rows

    def rapid_tuning(self):
        self.ensure_playlist()
        self.settings({'playerEngine': 'ijk', 'autoSwitchSourceSeconds': 0})
        for index in range(24):
            self.play(index % 2)
            time.sleep(.35)
        self.play(1)
        return self.observe(20, 'local-hls')

    def epg_cold_retry(self):
        token = self.args.run_id+'-'+self.spec['name']+'.xml'
        url = self.fixture_url+'/epg/'+token
        before = self.adb('logcat', '-d', '-v', 'time', '-s', 'EpgManager:V')
        self.settings({'epgEnabled': True, 'epgUrls': [url]})
        self.until(lambda: any('/epg/'+token in r['path'] for r in self.fixtures.requests), 30)
        self.until(lambda: 'HTTP 503' in self.adb('logcat','-d','-v','time','-s','EpgManager:V')[len(before):], 20)
        self.adb('shell', 'am', 'force-stop', self.package)
        with self.fixtures.lock:
            self.fixtures.healthy_epg.add(token)
        self.adb('shell', 'am', 'start', '-n', self.package+'/.MainActivity')
        self.until(lambda: self.api('/api/state'), 60)
        self.remember_pid()
        def published():
            log = self.adb('logcat','-d','-v','time','-s','EpgManager:V')
            (self.directory/'epg-retry.txt').write_text(log, encoding='utf8')
            return any(url in line and 'channels=1' in line for line in log.splitlines())
        self.until(published, 100)
        return {'sameUrl': url, 'retriedWithoutSavingSettings': True}

    def minimal_exit(self):
        self.ensure_playlist()
        self.settings({'webViewMinimalMode': True})
        original = self.api('/api/tv-ui')['selected']
        try:
            for theme in ('builtin','ui2','ui3','ui4','ui5','ui6'):
                self.api('/api/tv-ui', {'id': theme})
                self.play(0)
                self.ready()
                self.adb('shell','input','keyevent','4')
                time.sleep(.4)
                self.adb('shell','input','keyevent','4')
                time.sleep(1)
                try:
                    self.foreground()
                except AssertionError:
                    pass
                else:
                    raise AssertionError('Back did not exit theme '+theme)
                self.adb('shell','am','start','-n',self.package+'/.MainActivity')
                self.until(lambda: self.api('/api/state'), 30)
                self.remember_pid()
        finally:
            self.api('/api/tv-ui', {'id': original})

    def soak(self):
        self.ensure_playlist()
        self.settings({'playerEngine':'ijk', 'rememberChannelWebEngine':False,
                       'webSourceEngine':'system', 'autoSwitchSourceSeconds':0})
        self.play(6)  # Exact configured Yangshipin URL, no channel-list index assumption.
        return self.observe(self.args.soak_seconds, self.args.providers[0]['name'], 120)

    def background_resume(self):
        self.ensure_playlist()
        self.settings({'playerEngine':'ijk', 'rememberChannelWebEngine':False,
                       'webSourceEngine':'system', 'autoSwitchSourceSeconds':0})
        self.play(6)
        self.ready(120, self.args.providers[0]['name'])
        rows=[]
        for _ in range(3):
            self.adb('shell','input','keyevent','3')
            time.sleep(2)
            self.adb('shell','am','start','-n',self.package+'/.MainActivity')
            rows.append(self.observe(10,self.args.providers[0]['name'],60))
        return {'rounds':rows,'transientCorruptionVisuallyVerified':False}

    def crash_audit(self):
        text = (self.directory/'device.log').read_bytes()[self.log_offset:].decode('utf8','replace')
        failures = [line for line in text.splitlines() if
                    ('FATAL EXCEPTION' in line and any(re.search(r'\s'+pid+r'\s+\d+\s+E\s+AndroidRuntime',line) for pid in self.pids))
                    or ('ANR in '+self.package in line) or ('Fatal signal' in line and self.package in line)]
        if failures:
            raise AssertionError('\n'.join(failures))
        return {'appPids': sorted(self.pids), 'scope': 'new captured Java crash/ANR log lines'}


def device_worker(spec, args, out, fixtures):
    device = Device(spec, args, out/spec['name'], fixtures)
    lock = ROOT/'.codex-tmp/standard-regression-locks'/(spec['serial'].replace(':','_')+'.lock')
    lock.parent.mkdir(parents=True, exist_ok=True)
    owned = False
    planned = args.cases + ['crash-audit'] if args.profile != 'check' else ['discovery']
    device.report['planned'] = planned
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        owned = True
        with os.fdopen(descriptor, 'w') as output:
            json.dump({'pid':os.getpid(), 'run':args.run_id, 'directory':str(device.directory)}, output)
        device.setup()
        device.fixture_url = args.fixture_url
        if args.restore_snapshot:
            device.restore(json.loads(args.restore_snapshot.read_text(encoding='utf8')))
            device.report.update(status='PASS', operation='restore-only')
            return device.report
        if args.profile == 'check':
            device.report['cases'].append({'case':'discovery', 'status':'PASS'})
            device.report.update(status='PASS', restored=True, operation='read-only')
            return device.report
        device.start_log()
        if 'configuredWebSourceEngine' not in device.saved['settings']:
            raise AssertionError('Installed app lacks configuredWebSourceEngine snapshot; update the candidate before mutating tests')
        device.mutated = True
        device.settings({'autoSwitchSourceSeconds': 0})
        methods = {'kernel-settings': device.kernel_settings, 'browser-ui':device.browser_ui, 'images': device.images,
                   'regional-import': device.regional_import, 'async-import': device.async_import,
                   'native-files': device.native_files, 'system-private-files': device.system_private_files,
                   'web-system': lambda:device.web('system'), 'web-lite':lambda:device.web('lite'),
                   'source-failure':device.source_failure, 'official-providers':device.official_providers,
                   'rapid-tuning':device.rapid_tuning, 'epg-cold-retry':device.epg_cold_retry,
                   'minimal-exit':device.minimal_exit, 'soak':device.soak}
        methods['background-resume'] = device.background_resume
        methods['embedded-browser-ui'] = device.embedded_browser_ui
        methods.update({'startup-failures':device.startup_failures,'live-command-errors':device.live_command_errors,
                        'unsupported-hevc':device.unsupported_hevc})
        for name in args.cases:
            if STOP.is_set():
                break
            device.case(name, methods[name])
        device.case('crash-audit', device.crash_audit)
    except Exception as error:
        device.report['setupError'] = str(error)
        device.report['trace'] = traceback.format_exc()
    finally:
        if device.mutated and args.profile != 'check' and not args.restore_snapshot:
            try:
                device.restore()
            except Exception as error:
                device.report['restoreError'] = str(error)
        try:
            device.close()
        except Exception as error:
            device.report['cleanupError'] = str(error)
        if owned:
            lock.unlink()
        if not device.mutated and not args.restore_snapshot:
            device.report['restored'] = True  # No device settings were changed.
        if device.report.get('setupError') or device.report.get('cleanupError'):
            device.report['status'] = 'FAIL'
        elif not args.restore_snapshot:
            device.report['status'] = verdict(device.report['cases'], planned, device.report['restored'])
        device.save()
    return device.report


def host_checks(directory):
    directory.mkdir(parents=True, exist_ok=True)
    rows = []
    paths = sorted(path for path in (ROOT/'tests').iterdir()
                   if '.test.' in path.name and path.suffix in ('.py','.js','.cjs'))
    paths.append(ROOT/'tools/regression/test_standard_regression.py')
    for path in paths:
        command = [sys.executable, '-X', 'utf8', str(path)] if path.suffix=='.py' else ['node', str(path)]
        if path.name == 'live-buffer-native.test.py':
            command.append(str(directory/'native-buffer'))
        try:
            output = run(command, timeout=180, cwd=ROOT)
            status = 'PASS'
        except Exception as error:
            output, status = str(error).encode('utf8'), 'FAIL'
        (directory/(path.name+'.log')).write_bytes(output)
        rows.append({'case':path.name, 'status':status})
        write_json(directory/'result.json', rows)
        say('host: '+status+' '+path.name)
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('--config', type=Path, default=Path(__file__).with_name('standard_devices.json'))
    parser.add_argument('--profile', choices=['check','smoke','release'], default='smoke')
    parser.add_argument('--device', action='append', help='Configured name or serial; repeat to select several')
    parser.add_argument('--case', dest='case_names', action='append', choices=RELEASE, help='Diagnostic subset; never a complete release run')
    parser.add_argument('--apk', type=Path, help='Require byte-for-byte match with installed APK; no installation')
    parser.add_argument('--out', type=Path, help='New output directory; existing directories are refused')
    parser.add_argument('--fixture-host', help='PC IPv4 address reachable from devices; detected if omitted')
    parser.add_argument('--fixture-port', type=int, default=19891)
    parser.add_argument('--soak-seconds', type=int, default=900)
    parser.add_argument('--host-tests', action='store_true', help='Also run repository Python/JS host tests')
    parser.add_argument('--host-only', action='store_true')
    parser.add_argument('--list', action='store_true', help='Print plan without accessing devices')
    parser.add_argument('--restore-snapshot', type=Path, help='Restore a before.json from an interrupted run; select exactly one device')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--ffmpeg', default='ffmpeg')
    args = parser.parse_args(argv)
    if threading.current_thread() is threading.main_thread():
        signal.signal(signal.SIGINT, lambda *_: (STOP.set(), say('Interrupted: stopping tests and restoring snapshots…')))
    config = json.loads(args.config.read_text(encoding='utf8'))
    args.package, args.providers = config['package'], config['providers']
    args.cases = args.case_names or (RELEASE if args.profile=='release' else SMOKE)
    if len(args.cases) != len(set(args.cases)):
        parser.error('Do not repeat the same --case in one run; use a separate report directory for reruns')
    devices = [spec for spec in config['devices'] if not args.device
               or spec['name'] in args.device or spec['serial'] in args.device]
    if not devices or len({d['serial'] for d in devices}) != len(devices):
        parser.error('Device configuration must be nonempty with unique serials')
    if any(not re.fullmatch(r'[A-Za-z0-9_.-]+', d['name']) for d in devices):
        parser.error('Device names may contain only ASCII letters, digits, dots, dashes and underscores')
    matched = {value for value in args.device or [] if any(value in (d['name'],d['serial']) for d in devices)}
    if args.device and matched != set(args.device):
        parser.error('Unknown --device; inspect --config')
    if args.restore_snapshot and len(devices) != 1:
        parser.error('--restore-snapshot requires exactly one --device')
    if args.soak_seconds < 1:
        parser.error('--soak-seconds must be positive')
    if args.list:
        print(json.dumps({'profile':args.profile,'devices':devices,'cases':args.cases,
                          'soakSeconds':args.soak_seconds,'installsApk':False},ensure_ascii=False,indent=2))
        return 0
    if args.profile=='release' and not args.apk and not args.host_only:
        parser.error('Release testing requires --apk to identify the exact installed candidate')
    if args.apk and not args.apk.is_file():
        parser.error('--apk file is missing')
    args.run_id = datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]
    out = (args.out or ROOT/'output/standard-regression'/args.run_id).resolve()
    out.mkdir(parents=True, exist_ok=False)
    args.fixture_dir = out/'fixtures'
    args.fixture_url = None
    report = {'startedUtc':datetime.now(timezone.utc).isoformat(), 'profile':args.profile,
              'runId':args.run_id, 'command':sys.argv, 'plannedDevices':devices, 'plannedCases':args.cases,
              'deviceResults':[], 'hostTests':None, 'status':'INCOMPLETE',
              'scopeNotes':['Clock, decoder and screenshots are checked; audible sound needs listening.',
                            'Mobile browser scrolling and interactive website operation need separate UI review.',
                            'Java crash/ANR log audit does not replace native tombstone analysis.']}
    fixtures = None
    try:
        if args.apk:
            report['candidateSource']=str(args.apk.resolve())
            shutil.copyfile(args.apk,out/'candidate.apk')
            args.apk=out/'candidate.apk'
            report['candidateSha256']=digest(args.apk)
        report['gitHead'] = run(['git','rev-parse','HEAD'], cwd=ROOT).decode().strip()
        (out/'working-tree.txt').write_bytes(run(['git','status','--short'],cwd=ROOT))
        if args.host_tests or args.host_only or args.profile=='release':
            report['hostTests'] = host_checks(out/'host')
        if not args.host_only:
            if args.profile != 'check' and not args.restore_snapshot:
                # Fail setup before mutating devices if tools or fixtures are unavailable.
                prepare(args.fixture_dir, args.ffmpeg, hevc='unsupported-hevc' in args.cases)
                host = args.fixture_host
                if not host:
                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
                        connection.connect(('192.0.2.1',80))
                        host = connection.getsockname()[0]
                args.fixture_url = f'http://{host}:{args.fixture_port}'
                fixtures = FixtureServer(args.fixture_dir, '0.0.0.0', args.fixture_port)
                fixtures.start()
                report['fixtureUrl'] = args.fixture_url
            with ThreadPoolExecutor(max_workers=len(devices)) as pool:
                futures = {pool.submit(device_worker, spec, args, out, fixtures):spec for spec in devices}
                for future in as_completed(futures):
                    report['deviceResults'].append(future.result())
                    write_json(out/'report.json', report)
        statuses = [row['status'] for row in report['deviceResults']]
        statuses += [row['status'] for row in report['hostTests'] or []]
        report['status'] = 'PASS' if statuses and all(s=='PASS' for s in statuses) else 'FAIL'
        report['completeReleaseRun'] = bool(report['status']=='PASS' and args.profile=='release'
            and not args.host_only and not args.case_names and not args.restore_snapshot
            and len(devices)==len(config['devices']) and args.soak_seconds>=900
            and report['hostTests'])
    except Exception as error:
        report.update(status='FAIL',error=str(error),trace=traceback.format_exc())
    finally:
        if fixtures:
            fixtures.save(out/'fixture-requests.json')
            fixtures.close()
        report['finishedUtc'] = datetime.now(timezone.utc).isoformat()
        write_json(out/'report.json', report)
        lines = [f'Status: {report["status"]}', f'Profile: {args.profile}',
                 f'Complete release run: {report.get("completeReleaseRun",False)}', '']
        for device in report['deviceResults']:
            lines.append(device['device']['name']+': '+device['status'])
            for row in device['cases']:
                lines.append('  '+row['status']+' '+row['case']+(': '+row['error'] if row.get('error') else ''))
            for key in ('setupError','restoreError','cleanupError'):
                if key in device:
                    lines.append('  '+key+': '+device[key])
        if report['hostTests'] is not None:
            passed=sum(row['status']=='PASS' for row in report['hostTests'])
            lines.append(f'Host tests: {passed}/{len(report["hostTests"])} PASS')
            lines += ['  FAIL '+row['case'] for row in report['hostTests'] if row['status']!='PASS']
        if report.get('error'):
            lines.append(report['error'])
        (out/'report.txt').write_text('\n'.join(lines)+'\n',encoding='utf8')
        say('\n'.join(lines)+'\nReport: '+str(out/'report.json'))
    return 0 if report['status']=='PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
