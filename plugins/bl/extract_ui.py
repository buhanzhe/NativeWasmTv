"""Re-extract the user-supplied BBLL UI after `apktool d -s -f ...`.

Requires Node and sharp for old-Android PNG conversion. Does not copy APK DEX code.
"""
import argparse,json,pathlib,shutil,subprocess,xml.etree.ElementTree as ET
p=argparse.ArgumentParser()
p.add_argument('decoded',type=pathlib.Path,help='apktool decoded BBLL1.5.2 directory')
p.add_argument('--sharp',default='sharp',help='Node module name or absolute module directory')
p.add_argument('--icons-only',nargs='+',help='Only regenerate the named reference icons and PNG assets')
a=p.parse_args();here=pathlib.Path(__file__).resolve().parent
info=json.loads((here/'reference/source.json').read_text(encoding='utf-8'))
res=a.decoded/'res';ns='{http://schemas.android.com/apk/res/android}'
icons=a.icons_only or info['icons']
if any(name not in info['icons'] for name in icons):p.error('--icons-only names must be listed in reference/source.json')
if not a.icons_only:
    for name in info['layouts']:shutil.copyfile(res/'layout'/(name+'.xml'),here/'reference'/(name+'.xml'))
for name in icons:
    vector=ET.parse(res/'drawable'/(name+'.xml')).getroot()
    svg=ET.Element('svg',xmlns='http://www.w3.org/2000/svg',width='64',height='64',viewBox='0 0 '+vector.attrib[ns+'viewportWidth']+' '+vector.attrib[ns+'viewportHeight'])
    for path in vector.findall('path'):
        # Store a monochrome mask for the plugin's theme tinting.
        shape=ET.SubElement(svg,'path',fill='white',d=path.attrib[ns+'pathData'])
        if path.get(ns+'fillType')=='evenOdd':
            shape.set('fill-rule','evenodd')
        if ns+'strokeColor' in path.attrib and ns+'strokeWidth' in path.attrib:
            shape.set('stroke','white')
            shape.set('stroke-width',path.attrib[ns+'strokeWidth'])
            if ns+'strokeLineCap' in path.attrib:
                shape.set('stroke-linecap',path.attrib[ns+'strokeLineCap'])
            if ns+'strokeLineJoin' in path.attrib:
                shape.set('stroke-linejoin',path.attrib[ns+'strokeLineJoin'])
    reference=here/'reference'/(name+'.svg')
    encoded=ET.tostring(svg,encoding='utf-8')
    if not reference.exists() or reference.read_bytes().replace(b' />',b'/>')!=encoded.replace(b' />',b'/>'):
        reference.write_bytes(encoded)
if not a.icons_only:
    for name in ['default_avatar.png','default_video.png','background_image.webp']:
        source=next(res.rglob(name));shutil.copyfile(source,here/'assets'/name)
script='''const p=require('path'),sharp=require(process.argv[1]),root=process.argv[2];
(async()=>{for(const name of process.argv.slice(4))
await sharp(p.join(root,'reference',name+'.svg')).png().toFile(p.join(root,'assets',name+'.png'));
if(process.argv[3]==='all')
await sharp(p.join(root,'assets/background_image.webp')).resize(1280,720,{fit:'cover'}).png().toFile(p.join(root,'assets/background.png'));})();'''
subprocess.run(['node','-e',script,a.sharp,str(here),'icons' if a.icons_only else 'all',*icons],check=True)
print(f'Extracted {len(icons)} BBLL vector references and PNG assets.')
