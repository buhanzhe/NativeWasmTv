"""Extract the actual production shader constants for the isolated GLES benchmark."""
from pathlib import Path
import hashlib,json,re,sys
root=Path(__file__).resolve().parents[2]
output=Path(sys.argv[1]) if len(sys.argv)>1 else root/'.codex-tmp/hdr-algorithm-bench/shaders'
output.mkdir(parents=True,exist_ok=True)
def constant(source,name):
    match=re.search(r'\b'+name+r'\s*=\s*((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+);',source,re.S)
    if not match:raise ValueError('Not a literal shader constant: '+name)
    return ''.join(json.loads(s) for s in re.findall(r'"(?:\\.|[^"\\])*"',match.group(1)))
tone=(root/'app/src/main/res/raw/hdr_tonemap.glsl').read_text(encoding='utf-8')
lut=(root/'app/src/main/java/xiao/bu/tv/HdrToneMapLut.java').read_text(encoding='utf-8')
policy=(root/'app/src/main/java/xiao/bu/tv/HdrDisplayPolicy.java').read_text(encoding='utf-8')
shaders={
    'before.glsl':(root/'tests/fixtures/hdr-tonemap-reference.glsl').read_text(encoding='utf-8'),
    'after.glsl':tone,
    'bt709.glsl':constant(policy,'BT709_SHADER'),
    'lookup-combined.glsl':tone+constant(lut,'LOOKUP'),
    'lookup-only.glsl':constant(lut,'LOOKUP_ONLY'),
    'lookup-3d.glsl':constant(lut,'LOOKUP_3D'),
    'lut-generator.glsl':constant(lut,'GENERATOR'),
}
hashes={}
for name,source in shaders.items():
    (output/name).write_bytes(source.encode('utf-8'))
    hashes[name]=hashlib.sha256(source.encode('utf-8')).hexdigest()
(output/'shader-sha256.json').write_text(json.dumps(hashes,indent=2),encoding='utf-8')
print('Extracted production/reference shaders to '+str(output))
