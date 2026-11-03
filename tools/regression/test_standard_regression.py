"""Host-only tests for verdicts, failure recording, locks, restore and HTTP fixtures."""
import argparse
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import standard_regression as flow
from standard_fixtures import FixtureServer


class FlowTests(unittest.TestCase):
    def test_verdict_requires_every_case_and_restoration(self):
        passed = [{'case':'a','status':'PASS'}]
        self.assertEqual(flow.verdict(passed,['a'],True),'PASS')
        self.assertEqual(flow.verdict(passed,['a','b'],True),'INCOMPLETE')
        self.assertEqual(flow.verdict(passed,['a'],False),'FAIL')
        self.assertEqual(flow.verdict([{'case':'a','status':'FAIL'}],['a'],True),'FAIL')
        self.assertEqual(flow.verdict([{'case':'a','status':'FAIL'},*passed],['a'],True),'FAIL')
        self.assertEqual(flow.verdict(passed+passed,['a'],True),'INCOMPLETE')

    def test_clock_requires_same_source_positive_progress_and_video(self):
        a = {'sourceKey':'a','positionMs':100}
        b = {'sourceKey':'a','positionMs':200,'prepared':True,'playing':True,
             'durationMs':40000,'currentSourceStats':{'width':640}}
        self.assertTrue(flow.advancing(a,b))
        for change in [{'sourceKey':'b'},{'positionMs':50},{'positionMs':100},
                       {'playing':False},{'currentSourceStats':{}}]:
            self.assertFalse(flow.advancing(a,{**b,**change}))
        self.assertTrue(flow.advancing(a,{**b,'durationMs':0,'positionMs':50}))

    def worker(self, failure=None, restore_failure=False, setup_failure=False):
        class FakeDevice(flow.Device):
            def setup(self):
                if setup_failure:
                    raise AssertionError('wrong API/APK')
                self.saved = {'settings':{'configuredWebSourceEngine':'system'}}
            def start_log(self): pass
            def remember_pid(self): pass
            def settings(self, value): pass
            def kernel_settings(self):
                if failure:
                    raise AssertionError(failure)
            def crash_audit(self): pass
            def screenshot(self, name): raise RuntimeError('capture unavailable')
            def close(self): pass
            def restore(self, saved=None):
                self.report['restoreAttempted'] = True
                if restore_failure:
                    raise RuntimeError('disconnected during restore')
                self.report['restored'] = True
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = argparse.Namespace(package='xiao.bu.tv',run_id='unit',cases=['kernel-settings'],
                                      profile='smoke',restore_snapshot=None,fixture_url='http://fixture')
            with patch.object(flow,'ROOT',root), patch.object(flow,'Device',FakeDevice):
                report = flow.device_worker({'name':'unit','serial':'serial','sdk':15},args,root/'out',None)
                saved = json.loads((root/'out/unit/result.json').read_text(encoding='utf8'))
                self.assertEqual(saved['status'],report['status'])
                self.assertFalse(list(root.rglob('*.lock')))
                return report

    def test_failure_is_preserved_and_restore_runs(self):
        result = self.worker(failure='real playback failure')
        self.assertEqual(result['status'],'FAIL')
        self.assertTrue(result['restored'])
        self.assertEqual(result['cases'][0]['error'],'real playback failure')

    def test_restore_failure_blocks_pass(self):
        result = self.worker(restore_failure=True)
        self.assertEqual(result['status'],'FAIL')
        self.assertFalse(result['restored'])

    def test_setup_failure_never_mutates_or_restores(self):
        result = self.worker(setup_failure=True)
        self.assertEqual(result['status'],'FAIL')
        self.assertNotIn('restoreAttempted',result)

    def test_success(self):
        self.assertEqual(self.worker()['status'],'PASS')

    def test_restore_preserves_global_and_per_channel_preferences(self):
        s={'playlistSources':[],'decodeMode':'auto','currentDecodeMode':'hardware','playerEngine':'ijk',
           'autoSwitchSourceSeconds':0,'webViewAutoPlay':True,'webViewAutoPlaySniffed':True,
           'webViewMinimalMode':False,'epgEnabled':True,'epgUrls':[],
           'rememberChannelWebEngine':True,'rememberChannelDecode':True,'liteBrowserSupported':True,
           'configuredWebSourceEngine':'lite','webSourceEngine':'system'}
        saved={'settings':s,'current':{'groupIndex':0,'channelIndex':0,'sourceIndex':0},
               'groups':[{'name':'original','channels':[{'name':'channel','sourceCount':1}]}]}
        class Receiver(flow.Device):
            def api(self,path,value=None,**kwargs):
                if path=='/api/tv-ui':return {'selected':'ui2'}
                return copy.deepcopy(self.state)
            def settings(self,value):
                current=self.state['settings']
                # MainActivity processes the memory toggle BEFORE the engine value.
                if 'rememberChannelWebEngine' in value:
                    current['rememberChannelWebEngine']=value['rememberChannelWebEngine']
                    current['webSourceEngine']=self.memory_engine if value['rememberChannelWebEngine'] else current['configuredWebSourceEngine']
                if 'webSourceEngine' in value:
                    if current['rememberChannelWebEngine']:self.memory_engine=value['webSourceEngine']
                    else:current['configuredWebSourceEngine']=value['webSourceEngine']
                    current['webSourceEngine']=value['webSourceEngine']
                if 'channelDecodeMode' in value:
                    self.memory_decode=value['channelDecodeMode'];current['currentDecodeMode']=self.memory_decode
                current.update({k:v for k,v in value.items() if k not in ('webSourceEngine','rememberChannelWebEngine','channelDecodeMode')})
            def play(self,*args):
                current=self.state['settings'];current['currentDecodeMode']=self.memory_decode
                current['webSourceEngine']=self.memory_engine if current['rememberChannelWebEngine'] else current['configuredWebSourceEngine']
            def until(self,check,seconds=45):
                for _ in range(5):
                    value=check()
                    if value:return value
                raise AssertionError('Simulated state did not stabilize')
        with tempfile.TemporaryDirectory() as directory:
            receiver=Receiver({'name':'unit','serial':'unit'},argparse.Namespace(package='xiao.bu.tv'),Path(directory))
            receiver.state=copy.deepcopy(saved);receiver.memory_engine='system';receiver.memory_decode='hardware'
            receiver.kernel_settings()
            self.assertEqual(receiver.state['settings']['webSourceEngine'],'system')
            self.assertEqual(receiver.state['settings']['configuredWebSourceEngine'],'lite')
            receiver.memory_engine='lite';receiver.memory_decode='software'
            receiver.restore(copy.deepcopy(saved))
            self.assertTrue(receiver.report['restored'])
            self.assertEqual(receiver.state['settings']['webSourceEngine'],'system')
            self.assertEqual(receiver.state['settings']['configuredWebSourceEngine'],'lite')
            self.assertEqual(receiver.state['settings']['currentDecodeMode'],'hardware')

    def test_fixture_range_and_epg_failure_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory)/'video.mp4').write_bytes(b'0123456789')
            server = FixtureServer(directory,'127.0.0.1',0)
            server.start()
            base = 'http://127.0.0.1:'+str(server.server.server_port)
            try:
                with urlopen(Request(base+'/video.mp4',headers={'Range':'bytes=2-5'})) as response:
                    self.assertEqual(response.status,206)
                    self.assertEqual(response.read(),b'2345')
                    self.assertEqual(response.headers['Content-Range'],'bytes 2-5/10')
                with self.assertRaises(HTTPError) as error:
                    urlopen(base+'/epg/unit.xml')
                self.assertEqual(error.exception.code,503)
                error.exception.close()
                with server.lock:
                    server.healthy_epg.add('unit.xml')
                with urlopen(base+'/epg/unit.xml') as response:
                    self.assertIn(b'<programme ',response.read())
                with self.assertRaises(HTTPError) as error:
                    urlopen(Request(base+'/video.mp4',headers={'Range':'bytes=99-100'}))
                self.assertEqual(error.exception.code,416)
                error.exception.close()
            finally:
                server.close()


if __name__ == '__main__':
    unittest.main()
