"""Check the published native-channel ABI and reject accidental host/library duplication."""
import hashlib,json,pathlib,struct,zipfile
folder=pathlib.Path(__file__).resolve().parents[1];jar=folder/'dist/bl.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()==(folder/'dist/bl.sha256').read_text().strip()
with zipfile.ZipFile(jar) as z:
    info=json.loads(z.read('channel-plugin.json'));assert info['minSdk']==14 and info['native'] and info['entry']=='xiao.bu.channel.bl.BlPlugin'
    names=z.namelist();assert not any(n.endswith(('.so','.class','.html')) for n in names)
    reference=json.loads((folder/'reference/source.json').read_text(encoding='utf-8'))
    for icon in reference['icons']:
        asset='assets/'+icon+'.png'
        assert asset in names, 'Missing BBLL icon: '+icon
        assert z.read(asset)==(folder/asset).read_bytes(), 'Unexpected bundled icon: '+icon
    for license in ['NOTICE','LICENSE-newBV','LICENSE-ZXing','LICENSE-ExoPlayer','LICENSE-jFLAC']:assert license in names
    dex=z.read('classes.dex')
def word(offset):return struct.unpack_from('<I',dex,offset)[0]
def string(index):
    at=word(word(60)+4*index)
    while dex[at]&128:at+=1
    at+=1;return dex[at:dex.index(b'\0',at)].decode('utf-8',errors='replace')
types=[string(word(word(68)+4*i)) for i in range(word(64))]
defined=[]
for i in range(word(96)):
    at=word(100)+32*i;name=types[word(at)];defined.append(name)
    assert name.startswith(('Lxiao/bu/channel/bl/','Lcom/google/zxing/','Lcom/google/android/exoplayer2/','Lorg/jflac/')),name
    if word(at+8)!=0xffffffff:assert types[word(at+8)] not in ['Landroid/app/Activity;','Landroid/app/Fragment;','Landroid/app/Service;','Landroid/webkit/WebView;']
assert not any(t in types for t in ['Landroid/app/Fragment;','Landroid/webkit/WebView;','Landroid/media/MediaPlayer$OnTimedTextListener;'])
assert 'Lorg/jflac/FLACDecoder;' in defined and not any(t.startswith('Ljavax/sound/') for t in types)
# JDK 9 added covariant Buffer methods absent on Android <=25. -target 8 does not prevent them.
for i in range(word(88)):
    owner,proto,name=struct.unpack_from('<HHI',dex,word(92)+8*i)
    if types[owner].startswith('Ljava/nio/') and string(name) in ['position','limit','mark','reset','clear','flip','rewind']:
        result=types[word(word(76)+12*proto+4)]
        assert result in ['I','Ljava/nio/Buffer;'], 'Newer JDK Buffer ABI leaked into API-14 JAR: '+types[owner]+'.'+string(name)+' -> '+result
# Exo core has optional platform helpers with Activity-typed arguments; no Activity is defined/instantiated by the channel.
sources=(folder/'src').rglob('*.java')
for file in sources:
    text=file.read_text(encoding='utf-8');assert 'extends Activity' not in text and 'startActivity(' not in text
print('PASS:',len(defined),'DEX classes; API 14 entry, API 16+ DASH; no Activity subclasses/WebView/native libraries or bundled host ABI;',jar.stat().st_size,'bytes')
