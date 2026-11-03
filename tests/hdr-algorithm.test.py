"""Run production GLSL against the frozen reference, without a decoder or playback.

Uses the SDK emulator's host SwiftShader EGL/GLES libraries and NumPy. Set
NTV_SWIFTSHADER to another directory containing libEGL.dll/libGLESv2.dll.
This checks shader compilation and colour equivalence, not device frame rate.
"""
import ctypes as c
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import base64
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
LIB = Path(os.environ.get('NTV_SWIFTSHADER', 'D:/android/sdk/emulator/lib64/gles_swiftshader'))
dll_path = os.add_dll_directory(str(LIB))
egl, gl = c.CDLL(str(LIB/'libEGL.dll')), c.CDLL(str(LIB/'libGLESv2.dll'))

def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, args
    return fn

P, I, U, F = c.c_void_p, c.c_int, c.c_uint, c.c_float
get_display = bind(egl, 'eglGetDisplay', P, P)
initialize = bind(egl, 'eglInitialize', U, P, P, P)
choose = bind(egl, 'eglChooseConfig', U, P, P, P, I, P)
surface = bind(egl, 'eglCreatePbufferSurface', P, P, P, P)
context = bind(egl, 'eglCreateContext', P, P, P, P, P)
current = bind(egl, 'eglMakeCurrent', U, P, P, P, P)
display = get_display(None)
assert initialize(display, None, None), 'eglInitialize failed'
config, count = P(), I()
attrs = (I*11)(0x3033,1,0x3040,4,0x3024,8,0x3023,8,0x3022,8,0x3038)
assert choose(display, attrs, c.byref(config), 1, c.byref(count)) and count.value
size = 256
target = surface(display, config, (I*5)(0x3057,size,0x3056,size,0x3038))
# This host driver exposes 3D textures through ES3 core, not the ES2 extension.
# Reference/2D shaders still use GLSL100; actual ES2/OES is covered on devices.
ctx = context(display, config, None, (I*3)(0x3098,3,0x3038))
assert target and ctx and current(display, target, target, ctx)

create_shader = bind(gl,'glCreateShader',U,U)
source = bind(gl,'glShaderSource',None,U,I,P,P)
compile_shader = bind(gl,'glCompileShader',None,U)
shader_iv = bind(gl,'glGetShaderiv',None,U,U,P)
shader_log = bind(gl,'glGetShaderInfoLog',None,U,I,P,P)
create_program = bind(gl,'glCreateProgram',U)
attach = bind(gl,'glAttachShader',None,U,U)
link = bind(gl,'glLinkProgram',None,U)
program_iv = bind(gl,'glGetProgramiv',None,U,U,P)
program_log = bind(gl,'glGetProgramInfoLog',None,U,I,P,P)
use = bind(gl,'glUseProgram',None,U)
uniform = bind(gl,'glGetUniformLocation',I,U,c.c_char_p)
uniform1 = bind(gl,'glUniform1f',None,I,F)
uniform_i = bind(gl,'glUniform1i',None,I,I)
uniform_matrix = bind(gl,'glUniformMatrix3fv',None,I,I,U,P)
attrib = bind(gl,'glGetAttribLocation',I,U,c.c_char_p)
enable_attrib = bind(gl,'glEnableVertexAttribArray',None,U)
attrib_ptr = bind(gl,'glVertexAttribPointer',None,U,I,U,U,I,P)
gen_texture = bind(gl,'glGenTextures',None,I,P)
bind_texture = bind(gl,'glBindTexture',None,U,U)
texture_param = bind(gl,'glTexParameteri',None,U,U,I)
texture_image = bind(gl,'glTexImage2D',None,U,I,I,I,I,I,U,U,P)
viewport = bind(gl,'glViewport',None,I,I,I,I)
draw = bind(gl,'glDrawArrays',None,U,I,I)
read = bind(gl,'glReadPixels',None,I,I,I,I,U,U,P)
error = bind(gl,'glGetError',U)
delete_program = bind(gl,'glDeleteProgram',None,U)
delete_shader = bind(gl,'glDeleteShader',None,U)
disable = bind(gl,'glDisable',None,U)
disable(0x0BD0)  # Dithering would hide/reorder sub-code-value differences.

def shader(kind, text):
    shader_id = create_shader(kind)
    data = c.c_char_p(text.encode())
    source(shader_id,1,c.byref(data),None)
    compile_shader(shader_id)
    valid = I()
    shader_iv(shader_id,0x8B81,c.byref(valid))
    if not valid.value:
        log = c.create_string_buffer(16384)
        shader_log(shader_id,len(log),None,log)
        raise AssertionError(log.value.decode())
    return shader_id

def program(tone, entry='ntvToSdr'):
    volume='sampler3D' in tone
    vertex=('#version 300 es\nin' if volume else 'attribute')+' vec2 pos;void main(){gl_Position=vec4(pos,0.,1.);}'
    vs = shader(0x8B31,vertex)
    prefix='#version 300 es\nprecision highp float;out vec4 pixel;' if volume else 'precision highp float;'
    if volume:tone=tone.replace('texture3D(','texture(')
    main='' if entry is None else ('\nvoid main(){'+('pixel' if volume else 'gl_FragColor')+'=vec4('+entry
                +'('+('texture' if volume else 'texture2D')+'(inputColor,gl_FragCoord.xy/256.).rgb),1.);}')
    fs = shader(0x8B30,prefix+'uniform sampler2D inputColor;\n'+tone+main)
    p = create_program()
    attach(p,vs); attach(p,fs); link(p)
    valid = I()
    program_iv(p,0x8B82,c.byref(valid))
    if not valid.value:
        log = c.create_string_buffer(16384)
        program_log(p,len(log),None,log)
        raise AssertionError(log.value.decode())
    delete_shader(vs); delete_shader(fs)
    return p

rng = np.random.default_rng(20261022)
pixels = np.ones((size*size,4),dtype=np.float32)
# 10-bit RGB inputs plus grey ramps, primaries, near-black and clipped inputs.
pixels[:,:3] = rng.integers(0,1024,(size*size,3))/1023
pixels[:1024,:3] = np.linspace(0,1,1024)[:,None]
pixels[1024:1032,:3] = np.array([[0,0,0],[1,1,1],[1,0,0],[0,1,0],
                               [0,0,1],[1,1,0],[0,1,1],[1,0,1]])
pixels[1032:2056,:3] = rng.random((1024,3))*1e-4
pixels[2056:2064,:3] = np.array([[.25,.25,.25],[.5,.5,.5],[.75,.75,.75],
                               [.58068888]*3,[.70853657,.26654934,.12982403],
                               [.52480767,.74436791,.27190927],
                               [.23091570,.11827064,.81335211],[1,1,1]])
pixels[-4096:,:3] = rng.random((4096,3))*1.4-.2
pixels[-12:,:3]=np.array([[-1e-7,.5,1+1e-7],[-2,.25,3],[3,-2,.25],
                        [.25,3,-2],[-1e-5,0,0],[1+1e-5,1,1],
                        [0,-1e-5,0],[1,1+1e-5,1],[0,0,-1e-5],
                        [1,1,1+1e-5],[-.01,-.02,-.03],[1.01,1.02,1.03]])
texture = U()
gen_texture(1,c.byref(texture)); bind_texture(0x0DE1,texture)
for key,value in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]:
    texture_param(0x0DE1,key,value)
texture_image(0x0DE1,0,0x1908,size,size,0,0x1908,0x1406,pixels.ctypes.data)
assert error()==0, 'Float input texture unsupported'
vertices = np.array([-1,-1,1,-1,-1,1,1,1],dtype=np.float32)
viewport(0,0,size,size)

def render(p,trc=0,peak=1000,standard=0,matrix=None):
    use(p)
    for name,value in [('ntvTransfer',trc),('ntvPeak',peak),('ntvBt709Source',standard)]:
        uniform1(uniform(p,name.encode()),value)
    if matrix is not None:
        values=np.asarray(matrix,dtype=np.float32)
        uniform_matrix(uniform(p,b'ntvBt709Matrix'),1,0,values.ctypes.data)
    uniform_i(uniform(p,b'inputColor'),0)
    location=attrib(p,b'pos'); enable_attrib(location)
    attrib_ptr(location,2,0x1406,0,0,vertices.ctypes.data)
    draw(0x0005,0,4)
    output=np.empty((size*size,4),dtype=np.uint8)
    read(0,0,size,size,0x1908,0x1401,output.ctypes.data)
    assert error()==0,'GL draw/read failed'
    return output[:,:3].astype(np.int16)

old=program((ROOT/'tests/fixtures/hdr-tonemap-reference.glsl').read_text())
new=program((ROOT/'app/src/main/res/raw/hdr_tonemap.glsl').read_text())
cases=[]
for trc in [0,1,3,6,7]:
    for peak in ([100,400,1000,4000,10000] if trc==6 else [1000]):
        before,after=render(old,trc,peak),render(new,trc,peak)
        diff=np.abs(before-after)
        result=dict(transfer=trc,peak=peak,max_codes=int(diff.max()),mean_codes=float(diff.mean()))
        cases.append(result)
        assert diff.max()<=1,result
        assert np.all(np.diff(after[:1024].astype(int),axis=0)>=0),'Non-monotonic grey ramp'
        assert np.max(np.ptp(after[:1024],axis=1))<=1,'Neutral grey tint'
        assert np.all(after[1024]==0),'Black changed'

# Independently derived reference whites and 709 primaries encoded in HLG/2020.
hlg=render(new,7)
for index,expected in [(2056,76),(2057,137),(2058,219),(2063,255)]:
    assert np.max(np.abs(hlg[index]-expected))<=3,('HLG reference grey',index,hlg[index])
for index,primary in [(2060,[1,0,0]),(2061,[0,1,0]),(2062,[0,0,1])]:
    rgb=hlg[index];largest=int(rgb.max())
    assert largest>=160 and np.max(np.abs(rgb-largest*np.array(primary)))<=3,('Primary hue',rgb)
assert np.max(np.abs(render(new,6)[2059]-219))<=3,'PQ 203-nit reference white'

# Compile the actual Java production shader string (not a duplicate).
java=(ROOT/'app/src/main/java/xiao/bu/tv/HdrDisplayPolicy.java').read_text()
# Execute the production Java specialization, then compile its actual output.
with tempfile.TemporaryDirectory() as directory:
    folder=Path(directory)
    (folder/'HdrDisplayPolicy.java').write_text(java,encoding='utf8')
    (folder/'SdrShaderCheck.java').write_text('''package xiao.bu.tv;
import java.nio.file.*;import java.util.Base64;
class SdrShaderCheck { public static void main(String[] args) throws Exception {
 String reference=new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8");
 for(int trc:new int[]{1,3})System.out.println(Base64.getEncoder().encodeToString(
  HdrDisplayPolicy.sdrShader(reference,trc).getBytes("UTF-8")));
 try {HdrDisplayPolicy.sdrShader(reference,7);throw new AssertionError("HDR specialized as SDR");}
 catch(IllegalArgumentException expected) {}
}}''',encoding='utf8')
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(folder),
                    str(folder/'HdrDisplayPolicy.java'),str(folder/'SdrShaderCheck.java')],check=True)
    sources=subprocess.check_output([str(jdk/'java.exe'),'-cp',str(folder),'xiao.bu.tv.SdrShaderCheck',
                                    str(ROOT/'app/src/main/res/raw/hdr_tonemap.glsl')]).splitlines()
    for trc,encoded in zip([1,3],sources):
        specialized=program(base64.b64decode(encoded).decode())
        diff=np.abs(render(new,trc)-render(specialized))
        assert diff.max()<=1,('Specialized SDR gamut changed pixels',trc,int(diff.max()))
        cases.append(dict(specialized_sdr_transfer=trc,max_codes=int(diff.max()),mean_codes=float(diff.mean())))
literal=re.search(r'BT709_SHADER\s*=\s*(.*?);\s*\n',java,re.S).group(1)
tiny=''.join(json.loads(s) for s in re.findall(r'"(?:\\.|[^"\\])*"',literal))
fast=program(tiny,'ntvFastToSdr')
for standard in [0,1,2,4,6]:
    matrix=np.eye(3)
    if standard>1:
        kr,kb=(.2627,.0593) if standard==6 else (.299,.114)
        y=np.array([kr,1-kr-kb,kb]);u=(np.array([0,0,1])-y)/(2*(1-kb))
        v=(np.array([1,0,0])-y)/(2*(1-kr))
        r=y+1.5748*v;b=y+1.8556*u
        matrix=np.array([r,(y-.2126*r-.0722*b)/.7152,b])
    before=render(old,standard=standard)
    after=render(fast,matrix=matrix.T.flatten())
    diff=np.abs(before-after)
    assert diff.max()<=1,(standard,int(diff.max()))
    cases.append(dict(bt709_standard=standard,max_codes=int(diff.max()),mean_codes=float(diff.mean())))
lut_edge=int(os.environ.get('NTV_HDR_LUT_EDGE','64'))
assert lut_edge in (64,128)
atlas_width,atlas_height=lut_edge*8,lut_edge*(lut_edge//8)
lut_java=(ROOT/'app/src/main/java/xiao/bu/tv/HdrToneMapLut.java').read_text(encoding='utf-8')
def lut_constant(name):
    literal=re.search(r'\b'+name+r'\s*=\s*((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+);',lut_java,re.S).group(1)
    return ''.join(json.loads(s) for s in re.findall(r'"(?:\\.|[^"\\])*"',literal))
lookup=lut_constant('LOOKUP_ONLY_128' if lut_edge==128 else 'LOOKUP_ONLY')
combined=lookup if lut_edge==128 else lut_constant('LOOKUP')
reference=(ROOT/'app/src/main/res/raw/hdr_tonemap.glsl').read_text(encoding='utf-8')
mixed=program(reference+combined,'ntvFastToSdr')
pure=program(lookup,'ntvFastToSdr')
volume_source=lut_constant('LOOKUP_3D_128' if lut_edge==128 else 'LOOKUP_3D')
volume_program=program(volume_source,'ntvFastToSdr')
generate=program(reference+lut_constant('GENERATOR_128' if lut_edge==128 else 'GENERATOR'),None)
active_texture=bind(gl,'glActiveTexture',None,U)
gen_fbo=bind(gl,'glGenFramebuffers',None,I,P)
bind_fbo=bind(gl,'glBindFramebuffer',None,U,U)
attach_texture=bind(gl,'glFramebufferTexture2D',None,U,U,U,U,I)
check_fbo=bind(gl,'glCheckFramebufferStatus',U,U)
lut,framebuffer=U(),U()
volume_texture=U()
gen_texture(1,c.byref(volume_texture));active_texture(0x84C2);bind_texture(0x806F,volume_texture)
for key,value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F),(0x8072,0x812F)]:
    texture_param(0x806F,key,value)
get_proc=bind(egl,'eglGetProcAddress',P,c.c_char_p)
upload3d=c.CFUNCTYPE(None,U,I,I,I,I,I,I,U,U,P)(get_proc(b'glTexImage3D'))
gen_texture(1,c.byref(lut));active_texture(0x84C1);bind_texture(0x0DE1,lut)
for key,value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:
    texture_param(0x0DE1,key,value)
texture_image(0x0DE1,0,0x1908,atlas_width,atlas_height,0,0x1908,0x1401,None)
gen_fbo(1,c.byref(framebuffer))
for trc,peak in [(6,100),(6,400),(6,1000),(6,4000),(6,10000),(7,1000)]:
    bind_fbo(0x8D40,framebuffer);attach_texture(0x8D40,0x8CE0,0x0DE1,lut,0)
    assert check_fbo(0x8D40)==0x8CD5,'LUT FBO unsupported'
    viewport(0,0,atlas_width,atlas_height);use(generate)
    uniform1(uniform(generate,b'ntvTransfer'),trc);uniform1(uniform(generate,b'ntvPeak'),peak)
    location=attrib(generate,b'pos');enable_attrib(location)
    attrib_ptr(location,2,0x1406,0,0,vertices.ctypes.data);draw(0x0005,0,4)
    atlas=np.empty((atlas_height,atlas_width,4),dtype=np.uint8)
    read(0,0,atlas_width,atlas_height,0x1908,0x1401,atlas.ctypes.data)
    if trc==6:
        def node(r,g,b):return atlas[(b//8)*lut_edge+g,(b%8)*lut_edge+r]
        for k in range(lut_edge-1):
            for n in [1,2]:
                base,d=(k,1) if n==1 else (k+1,-1)
                indices=[(base+d,base,base),(base,base+d,base),(base,base,base+d)]
                original=np.array([node(*ix)[:3] for ix in indices],dtype=int);values=original.copy()
                target=node(k,k,k)[:3].astype(int)*(3-n)+node(k+1,k+1,k+1)[:3].astype(int)*n
                for channel in range(3):
                    remaining=int(target[channel]-values[:,channel].sum());direction=1 if remaining>0 else -1
                    while remaining:
                        valid=[i for i in range(3) if 0<=values[i,channel]+direction<=255]
                        best=min(valid,key=lambda i:abs(values[i,channel]-original[i,channel]))
                        values[best,channel]+=direction;remaining-=direction
                for ix,value in zip(indices,values):node(*ix)[:3]=value
        active_texture(0x84C1);bind_texture(0x0DE1,lut)
        texture_image(0x0DE1,0,0x1908,atlas_width,atlas_height,0,0x1908,0x1401,atlas.ctypes.data)
    cube=np.empty((lut_edge,lut_edge,lut_edge,4),dtype=np.uint8)
    for z in range(lut_edge):cube[z]=atlas[(z//8)*lut_edge:(z//8+1)*lut_edge,(z%8)*lut_edge:(z%8+1)*lut_edge]
    active_texture(0x84C2);bind_texture(0x806F,volume_texture)
    upload3d(0x806F,0,0x1908,lut_edge,lut_edge,lut_edge,0,0x1908,0x1401,cube.ctypes.data)
    bind_fbo(0x8D40,0);viewport(0,0,size,size);active_texture(0x84C0)
    use(mixed);uniform1(uniform(mixed,b'ntvUseLut'),1);uniform_i(uniform(mixed,b'ntvLut'),1)
    use(pure);uniform_i(uniform(pure,b'ntvLut'),1)
    before,after=render(mixed,trc,peak),render(pure,trc,peak)
    delta=int(np.abs(before-after).max())
    assert delta==0,('Separate LUT program changed colours',trc,peak,delta)
    cases.append(dict(lut_transfer=trc,peak=peak,max_codes=delta))
    use(volume_program);uniform_i(uniform(volume_program,b'ntvLut'),2)
    volume_output=render(volume_program,trc,peak)
    delta=int(np.abs(volume_output-after).max())
    expected=render(new,trc,peak)
    before_error=np.abs(after-expected);volume_error=np.abs(volume_output-expected)
    assert delta<=2,('3D/2D interpolation difference',trc,peak,delta)
    assert volume_error.max()<=before_error.max()+1,('3D peak error increased',trc,peak)
    assert volume_error.mean()<=before_error.mean()+.05,('3D average error increased',trc,peak)
    assert np.max(np.ptp(volume_output[:1024],axis=1))<=max(1,np.max(np.ptp(after[:1024],axis=1))),('3D grey tint regression',trc,peak,int(np.max(np.ptp(volume_output[:1024],axis=1))),int(np.max(np.ptp(after[:1024],axis=1))))
    grey_steps=np.diff(volume_output[:1024],axis=0)
    old_grey_steps=np.diff(after[:1024],axis=0)
    assert grey_steps.min()>=min(0,old_grey_steps.min()),('3D grey reversal',trc,peak,int(grey_steps.min()),int(old_grey_steps.min()))
    if trc==6:
        # The previous RGB-only LUT dipped by nine codes in PQ highlights.
        # Check the full 10-bit ramp at every supported mastering peak.
        assert grey_steps.min()>=-1,('PQ grey reversal exceeds quantization',peak,int(grey_steps.min()))
        assert volume_error[:1024].max()<=2,('PQ grey brightness',peak,int(volume_error[:1024].max()))
        assert np.diff(after[:1024],axis=0).min()>=-1,('2D fallback PQ grey reversal',peak)
    assert np.all(volume_output[1024]==0),'3D black level'
    if trc==7:assert np.max(np.abs(volume_output[2058]-219))<=max(3,np.max(np.abs(after[2058]-219))),'3D HLG reference white regression'
    if trc==7:
        for index,primary in [(2060,[1,0,0]),(2061,[0,1,0]),(2062,[0,0,1])]:
            old_delta=np.max(np.abs(after[index]-int(after[index].max())*np.array(primary)))
            new_delta=np.max(np.abs(volume_output[index]-int(volume_output[index].max())*np.array(primary)))
            assert new_delta<=old_delta+1 and volume_output[index].max()>=160,'3D primary hue regression'
    if trc==6 and peak==1000:assert np.max(np.abs(volume_output[2059]-219))<=max(3,np.max(np.abs(after[2059]-219))),'3D PQ reference white regression'
    cases.append(dict(volume_transfer=trc,peak=peak,max_vs_2d=delta,mean_formula_error=float(volume_error.mean()),max_formula_error=int(volume_error.max())))
for p in [old,new,fast,mixed,pure,generate,volume_program]:delete_program(p)
bind(gl,'glDeleteFramebuffers',None,I,P)(1,c.byref(framebuffer))
bind(gl,'glDeleteTextures',None,I,P)(1,c.byref(lut))
bind(gl,'glDeleteTextures',None,I,P)(1,c.byref(volume_texture))
result=dict(lut_edge=lut_edge,samples_per_case=size*size,cases=cases,playback_test=False)
print(json.dumps(result,indent=2))
print('PASS formula/BT.709 <=1 code; split 2D lookup identical; 3D colour/grey/white regression checks passed')
