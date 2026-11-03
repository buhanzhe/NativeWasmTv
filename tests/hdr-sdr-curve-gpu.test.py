"""Pixel-test the actual production Java curve shaders/data on host GLES.

Uses the same SwiftShader/NumPy harness as hdr-algorithm.test.py. This tests
colour accuracy, including gamut clipping boundaries, not playback throughput.
"""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[1]
# Reuse the existing deterministic shader/input/render harness, stopping before
# its independent HDR/LUT cases. No copy of the production curve is generated.
harness=ROOT/'tests/hdr-algorithm.test.py'
scope={'__file__':str(harness)}
exec(compile(harness.read_text(encoding='utf8').split('old=program(',1)[0],str(harness),'exec'),scope)
globals().update({k:v for k,v in scope.items() if k!='__file__'})
with tempfile.TemporaryDirectory() as directory:
    environment=dict(os.environ,NTV_HDR_CURVE_EXPORT=directory)
    subprocess.run([sys.executable,str(ROOT/'tests/hdr-sdr-curve.test.py')],env=environment,check=True)
    files=Path(directory)
    reference=program((ROOT/'app/src/main/res/raw/hdr_tonemap.glsl').read_text(encoding='utf8'))
    active=bind(gl,'glActiveTexture',None,U)
    cases=[]
    initial_pixels=pixels.copy()
    for floating in [False,True]:
        pixels[:]=initial_pixels
        texture_image(0x0DE1,0,0x1908,size,size,0,0x1908,0x1406,pixels.ctypes.data)
        suffix='-float' if floating else ''
        dtype=np.float32 if floating else np.uint8
        curve_data=np.frombuffer((files/f'curve{suffix}.bin').read_bytes(),dtype).reshape(2,2048,4)
        curve=U();gen_texture(1,c.byref(curve));active(0x84C1);bind_texture(0x0DE1,curve)
        for key,value in [(0x2801,0x2601 if floating else 0x2600),(0x2800,0x2601 if floating else 0x2600),(0x2802,0x812F),(0x2803,0x812F)]:
            texture_param(0x0DE1,key,value)
        texture_image(0x0DE1,0,0x8814 if floating else 0x1908,2048,2,0,0x1908,0x1406 if floating else 0x1401,curve_data.ctypes.data)
        assert error()==0,'Curve texture upload'
        active(0x84C0)
        programs={}
        for trc in [1,3]:
            source=(files/f'curve{suffix}-{trc}.glsl').read_text(encoding='utf8')
            programs[trc]=program(source)
            use(programs[trc]);uniform_i(uniform(programs[trc],b'ntvLut'),1)
            after=render(programs[trc]);delta=np.abs(render(reference,trc)-after)
            assert delta.max()<=1,('Colour precision',floating,trc,int(delta.max()))
            assert np.diff(after[:1024],axis=0).min()>=0,'Grey ramp reversal'
            assert np.ptp(after[:1024],axis=1).max()<=1,'Grey tint'
        # Reset the seed for equivalent packed/float cases.
        stress_rng=np.random.default_rng(20261026)
        for batch in range(32):
            pixels[:,:3]=stress_rng.random((size*size,3),dtype=np.float32)
            if batch<16:
                matrix=np.array([[1.660491,-.587641,-.072850],[-.124550,1.132900,-.008349],[-.018151,-.100579,1.118730]])
                channel=batch%3;pivot=batch%3
                linear=pixels[:,:3].astype(np.float64)**2.4
                target=(stress_rng.random(size*size)-.5)*1e-5
                linear[:,pivot]=(target-linear@matrix[channel]+linear[:,pivot]*matrix[channel,pivot])/matrix[channel,pivot]
                pixels[:,:3]=np.maximum(linear,0)**(1/2.4)
            elif batch==16:
                pixels[:,:3]=np.linspace(0,1,size*size)[:,None]
            texture_image(0x0DE1,0,0x1908,size,size,0,0x1908,0x1406,pixels.ctypes.data)
            for trc in [1,3]:
                delta=np.abs(render(reference,trc)-render(programs[trc]))
                result=dict(floating=floating,transfer=trc,batch=batch,maxCodes=int(delta.max()),overOne=int((delta>1).sum()))
                assert result['maxCodes']<=1,result
                cases.append(result)
    result=dict(playbackTest=False,pixels=32*size*size*4,maxCodes=max(v['maxCodes'] for v in cases),overOne=sum(v['overOne'] for v in cases),cases=cases)
    output=ROOT/'.codex-tmp/hdr-gpu-generic/curve-gpu-validation.json'
    output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,indent=2),encoding='utf8')
    print('PASS production packed/float GPU curves:',result['pixels'],'colour samples; max error',result['maxCodes'],'SDR code; neutral/monotonic greys and clipping boundaries')
