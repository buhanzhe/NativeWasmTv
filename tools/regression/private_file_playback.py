"""Real-device regression for private-cache media passed to Android MediaPlayer.

Run against an already installed app, e.g.:
  python tools/regression/private_file_playback.py --base-url http://127.0.0.1:19961 --video path/to/sample.mp4 --report output/private-files.json
The MP4 must be at least 12 seconds long. No APK is installed by this check.
"""
import argparse
import io
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request
import wave


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--video', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    base = args.base_url.rstrip('/')

    def api(path, value=None, raw=None):
        body = raw if raw is not None else None if value is None else json.dumps(value).encode('utf8')
        request = urllib.request.Request(base + path, data=body, headers={
            'Content-Type': 'application/octet-stream' if raw is not None else 'application/json'})
        with urllib.request.urlopen(request, timeout=25) as response:
            return json.load(response)

    saved = api('/api/state')
    results = {'cases': [], 'restored': False}
    failed = False
    try:
        # Isolate private-file I/O from restarting a live network channel.
        api('/api/quick/media?name=private-prime.mp4', raw=args.video.read_bytes())
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            media = api('/api/media')
            if media.get('name') == 'private-prime.mp4' and media.get('prepared') and media.get('playing'):
                break
            time.sleep(.5)
        else:
            raise AssertionError('Local video setup did not become ready')
        api('/api/settings', {'playerEngine': 'system'})
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            media = api('/api/media')
            if media.get('prepared') and media.get('playing') and '系统' in media.get('currentSourceStats', {}).get('videoDecoderName', ''):
                break
            time.sleep(.5)
        else:
            raise AssertionError('System-player setup did not become ready')
        for name, channels, rate in [('private-mono.wav', 1, 22050),
                                     ('private-stereo.wav', 2, 44100),
                                     ('private-video.mp4', None, None)]:
            row = {'name': name, 'pass': False, 'samples': []}
            results['cases'].append(row)
            try:
                if channels is None:
                    data = args.video.read_bytes()
                else:
                    buffer = io.BytesIO()
                    with wave.open(buffer, 'wb') as output:
                        output.setnchannels(channels)
                        output.setsampwidth(2)
                        output.setframerate(rate)
                        output.writeframes(bytes(rate * channels * 2 * 20))
                    data = buffer.getvalue()
                api('/api/quick/media?name=' + urllib.parse.quote(name), raw=data)
                deadline = time.monotonic() + 35
                previous = None
                while time.monotonic() < deadline:
                    time.sleep(1)
                    media = api('/api/media')
                    row['samples'].append(media)
                    position = media.get('positionMs', 0)
                    if media.get('prepared') and media.get('playing') and previous is not None and position > previous:
                        break
                    previous = position
                else:
                    raise AssertionError('No advancing playback within 35 seconds')
                assert media['name'] == name, 'Temporary file was replaced'
                assert bool(media['audioOnly']) == (channels is not None), 'Incorrect media type'
                assert '系统' in media.get('currentSourceStats', {}).get('videoDecoderName', ''), 'System player was not used'
                api('/api/media/control', {'action': 'pause'})
                time.sleep(.3)
                assert not api('/api/media')['playing'], 'Pause failed'
                api('/api/media/control', {'action': 'seek', 'positionMs': 8000, 'sourceKey': media['sourceKey']})
                api('/api/media/control', {'action': 'play'})
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    if api('/api/media').get('positionMs', 0) >= 7500:
                        break
                    time.sleep(.5)
                else:
                    raise AssertionError('Seek/resume failed')
                row['pass'] = True
            except Exception as error:
                row['error'] = str(error)
                failed = True
            print(name, 'PASS' if row['pass'] else 'FAIL: ' + row['error'], flush=True)
    finally:
        try:
            api('/api/settings', {'playerEngine': saved['settings']['playerEngine']})
            selected = saved['current']
            api('/api/control', {'action': 'play', 'group': selected['groupIndex'],
                                'channel': selected['channelIndex'], 'source': selected['sourceIndex']})
            results['restored'] = True
        except Exception as error:
            results['restoreError'] = str(error)
            failed = True
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf8')
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
