"""Run BlPluginInstrumentation on an explicitly selected device and test package."""
import argparse,hashlib,pathlib,shlex,socket,subprocess,time,urllib.parse,threading,re
p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--serial',required=True)
p.add_argument('--package',required=True,help='Explicit host package; plugin tests use separate preferences even in xiao.bu.tv')
p.add_argument('--host-apk',required=True);p.add_argument('--test-apk',required=True)
p.add_argument('--real',action='store_true',help='Use official Bilibili APIs (JAR still comes from local HTTPS fixture)')
p.add_argument('--system',action='store_true',help='Exercise the API-14 platform player adapter')
p.add_argument('--dash',action='store_true',help='Exercise separate DASH video/audio merge on API 16+')
p.add_argument('--premium-dash',action='store_true',help='Exercise 8K, Dolby and Hi-Res selection against a premium DASH fixture')
p.add_argument('--dash-resume',action='store_true',help='Check that DASH recovers across byte ranges and audio representations')
p.add_argument('--seek',action='store_true',help='Check that playback advances after dragging and rewinding')
p.add_argument('--vod',action='store_true',help='Check video URL refresh, portrait layout and saved progress using a portrait DASH fixture')
p.add_argument('--order',action='store_true',help='Check the repeat-one icon, looping, and sequential playback of the next video')
p.add_argument('--hires',action='store_true',help='Exercise repeated Hi-Res track switches and verify decoded audio output')
p.add_argument('--cache',action='store_true',help='Check disk reuse and cleanup, using a high-bitrate fixture for the DASH cache limits')
p.add_argument('--playback-integration',action='store_true',help='Check the shared HUD, web stats, media traffic and fresh video surfaces')
p.add_argument('--dolby-direct',action='store_true',help='Verify unchanged E-AC-3 frames and fallback on a PCM-only emulator')
p.add_argument('--video',help='Run a focused playback check on a public BV identifier with --real')
p.add_argument('--smoke',action='store_true',help='Initial playback, navigation and live danmaku; skip decoder-switch stress tests')
p.add_argument('--jar-url',help='Optional published HTTPS JAR instead of the local HTTPS fixture')
p.add_argument('--cert',type=pathlib.Path,help='Public fixture certificate, trusted only inside the isolated instrumentation process')
p.add_argument('--fixture-host',help='Advertised fixture host, e.g. 127.0.0.1 with adb reverse')
p.add_argument('--skip-install',action='store_true',help='Reuse already installed host/test APKs for repeated runs')
p.add_argument('--skip-host-install',action='store_true',help='Keep the installed isolated host and install only instrumentation')
p.add_argument('--embedded-runner',action='store_true',help='Runner is temporarily embedded in the explicitly selected host APK')
p.add_argument('--errors',action='store_true',help='Inject HTTP/business/JSON/media faults and exercise the diagnostics UI')
p.add_argument('--interactions',action='store_true',help='Exercise real player taps, overlays, remote focus and transient playback recovery')
p.add_argument('--account-audit',action='store_true',help='Read-only audit using the existing production login; never clears production preferences')
p.add_argument('--local-jar',type=pathlib.Path,help='Seed the hash-checked plugin cache from a local development JAR through instrumentation')
p.add_argument('--account-probe',action='store_true',help='Limit the read-only account audit to endpoint parameter diagnosis')
p.add_argument('--soak-seconds',type=int,default=0,help='Additional initial playback observation (fixture video is 40 seconds)')
p.add_argument('--run-label',default='',help='Optional output suffix to preserve separate regression rounds')
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[3];adb=[a.adb,'-s',a.serial]
if a.account_audit and (not a.real or not a.jar_url or a.errors):p.error('--account-audit requires --real --jar-url and cannot inject faults')
if a.premium_dash and (not a.dash or a.real or a.system):p.error('--premium-dash requires local fixtures and --dash')
if a.dash_resume and (not a.dash or a.real or a.system):p.error('--dash-resume requires local fixtures and --dash')
if a.vod and (not a.dash or a.real or a.system):p.error('--vod requires a local portrait fixture and --dash')
if a.cache and a.real:p.error('--cache requires local fixtures')
if a.dolby_direct and (a.real or not a.dash):p.error('--dolby-direct requires the local premium fixture and --dash')
if a.video and (not a.real or a.account_audit or not re.fullmatch(r'BV[A-Za-z0-9]{10}',a.video)):
    p.error('--video requires --real and a BV identifier')
def run(*args,**kw):return subprocess.run(adb+list(args),check=True,**kw)
if not a.skip_install:
    for apk in ([a.host_apk] if a.embedded_runner else [a.test_apk] if a.skip_host_install else [a.host_apk,a.test_apk]):run('install','-r',apk)
run('shell','input','keyevent','224')
subprocess.run(adb+['shell','wm','dismiss-keyguard'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(('1.1.1.1',80));host=s.getsockname()[0];s.close()
if a.fixture_host:host=a.fixture_host
sha=hashlib.sha256(a.local_jar.read_bytes()).hexdigest() if a.local_jar else (root/'plugins/bl/dist/bl.sha256').read_text().strip()
params={'sha256':sha}
if not a.account_audit:params['testPreferences']='true'
if not a.real:
    for key in ['apiOrigin','passportOrigin','liveOrigin']:params[key]='http://'+host+':39626'
source=(a.jar_url or 'https://'+host+':39627/bl.jar')+'?'+urllib.parse.urlencode(params)
command='am instrument -w -e source '+shlex.quote(source)
if a.local_jar:
    run('push',str(a.local_jar),'/data/local/tmp/ntv-bl-audit.jar')
    command+=' -e localJar /data/local/tmp/ntv-bl-audit.jar'
if a.cert:
    run('push',str(a.cert),'/data/local/tmp/ntv-bl-test-cert.pem')
    command+=' -e cert /data/local/tmp/ntv-bl-test-cert.pem -e fixtureHost '+shlex.quote(host)
if a.real:command+=' -e real true'
if a.system:command+=' -e system true'
if a.dash:command+=' -e dash true'
if a.premium_dash:command+=' -e premiumDash true'
if a.dash_resume:command+=' -e dashResume true'
if a.seek:command+=' -e seek true'
if a.vod:command+=' -e vod true'
if a.order:command+=' -e order true'
if a.hires:command+=' -e hires true'
if a.cache:command+=' -e cache true'
if a.playback_integration:command+=' -e playbackIntegration true'
if a.dolby_direct:command+=' -e dolbyDirect true'
if a.video:command+=' -e video '+shlex.quote(a.video)
if a.smoke:command+=' -e smoke true'
if a.errors:command+=' -e errors true'
if a.interactions:command+=' -e interactions true'
if a.account_audit:command+=' -e accountAudit true'
if a.account_probe:command+=' -e accountProbe true'
if a.soak_seconds:command+=' -e soakSeconds '+str(a.soak_seconds)
command+=' '+a.package+('' if a.embedded_runner else '.test')+'/xiao.bu.tv.BlPluginInstrumentation'
out=root/'output/bl/tests'/(re.sub(r'[^a-zA-Z0-9_.-]','_',a.serial)+('-real' if a.real else '-fixture')+('-system' if a.system else '-dash' if a.dash else '-ijk')+('-smoke' if a.smoke else ''));out.mkdir(parents=True,exist_ok=True)
if a.run_label or a.errors or a.interactions:
    out=out.with_name(out.name+('-errors' if a.errors else '-interactions' if a.interactions else '')+('-'+re.sub(r'[^a-zA-Z0-9_-]','_',a.run_label) if a.run_label else ''));out.mkdir(parents=True,exist_ok=True)
for name in ['bl-playing','bl-controls','bl-controls-screen','bl-home','bl-search','bl-history','bl-login','bl-settings','bl-episodes','bl-danmaku-settings','bl-live','bl-live-screen','bl-error']:(out/(name+'.png')).unlink(missing_ok=True)
process=subprocess.Popen(adb+['shell',command],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
lines=[]
def collect():
    for line in process.stdout:
        lines.append(line)
        if a.account_audit:print(line,end='',flush=True)
        if 'BL_PLAYING engine=' in line:
            try:
                remote='/sdcard/Android/data/'+a.package+'/files/bl-playing.png'
                subprocess.run(adb+['shell','screencap','-p',remote],check=True,timeout=20,stdout=subprocess.DEVNULL)
                subprocess.run(adb+['pull',remote,str(out/'bl-playing.png')],check=True,timeout=20,stdout=subprocess.DEVNULL)
            except subprocess.SubprocessError:pass
        if 'BL_CAPTURE bl-controls.png' in line:
            remote='/sdcard/Android/data/'+a.package+'/files/bl-controls-screen.png'
            subprocess.run(adb+['shell','screencap','-p',remote],timeout=20,stdout=subprocess.DEVNULL)
            subprocess.run(adb+['pull',remote,str(out/'bl-controls-screen.png')],timeout=20,stdout=subprocess.DEVNULL)
        if 'BL_CAPTURE bl-live.png' in line:
            remote='/sdcard/Android/data/'+a.package+'/files/bl-live-screen.png'
            subprocess.run(adb+['shell','screencap','-p',remote],timeout=20,stdout=subprocess.DEVNULL)
            subprocess.run(adb+['pull',remote,str(out/'bl-live-screen.png')],timeout=20,stdout=subprocess.DEVNULL)
reader=threading.Thread(target=collect,daemon=True);reader.start()
time.sleep(2);run('shell','am','start','-W','-n',a.package+'/xiao.bu.tv.MainActivity')
subprocess.run(adb+['shell','wm','dismiss-keyguard'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:process.wait(timeout=(1200 if a.account_audit else 240)+max(0,a.soak_seconds))
except subprocess.TimeoutExpired:process.kill();raise
reader.join(25);text=''.join(lines);print(text)
(out/'result.txt').write_text(text,encoding='utf-8')
logs=subprocess.run(adb+['logcat','-d','-v','threadtime'],capture_output=True,encoding='utf-8',errors='replace').stdout
pid=re.search(r'BL_TEST_PID=(\d+)',text)
if pid:logs='\n'.join(line for line in logs.splitlines() if re.match(r'^\d\d-\d\d\s+\S+\s+'+pid.group(1)+r'\s+',line))+'\n'
else:logs='No target PID was reported; unrelated process logs were omitted.\n'
(out/'logcat.txt').write_text(logs,encoding='utf-8')
if a.account_audit and not a.account_probe:subprocess.run(adb+['pull','/sdcard/Android/data/'+a.package+'/files/bl-account-audit.json',str(out/'account-audit.json')])
images=(['bl-account-player.png','bl-account-settings.png'] if a.account_audit else ['bl-interactions.png'] if a.interactions else ['bl-http-error.png','bl-media-error.png'] if a.errors else ['bl-home.png','bl-search.png','bl-history.png','bl-login.png','bl-settings.png','bl-live.png']+([] if a.real or a.smoke else ['bl-episodes.png','bl-danmaku-settings.png','bl-controls.png'])) if 'PASS:' in text or a.account_audit else ['bl-error.png']
if 'browseUi=' in text:images+=['bl-search-keyboard.png','bl-browse-settings.png','bl-theme-pink.png','bl-theme-classic.png','bl-mine.png']
if a.video:images=['bl-focused-player.png'] if 'PASS:' in text else ['bl-error.png']
if (a.seek and not a.video or a.vod or a.order or a.hires or a.cache or a.playback_integration or a.dolby_direct) and 'PASS:' in text:images=[]
if 'playerUi=' in text:images+=['bl-player-overlay.png','bl-player-related.png']
if a.account_probe:images=[]
for name in images:subprocess.run(adb+['pull','/sdcard/Android/data/'+a.package+'/files/'+name,str(out/name)])
raise SystemExit(0 if 'PASS:' in text else 1)
