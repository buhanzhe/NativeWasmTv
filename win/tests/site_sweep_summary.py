"""Collect immutable per-case evidence; delegated/running cases never count as tested."""
from pathlib import Path
import argparse,collections,json,datetime,hashlib

def collect(folder,plan):
    rows=[]
    for item in plan['channels']:
        f=folder/item['id']/'result.json'
        if not f.exists():continue
        try:row=json.loads(f.read_text('utf8'))
        except json.JSONDecodeError:continue
        if row.get('status') in ['running','delegated']:continue
        rows.append({**item,**row})
    return {'planned':len(plan['channels']),'completed':len(rows),'sites_completed':len({r['site'] for r in rows}),'counts':dict(collections.Counter(r['status'] for r in rows)),'channels':rows}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--gallery',action='store_true');a=p.parse_args()
    plan=json.loads((a.root/'plan/selection.json').read_text('utf8'));report={'at':datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),'scope':plan['scope'],'planned_sites':plan['planned_sites'],'planned_cases':len(plan['channels'])}
    for platform in ['windows','android']:
        report[platform]=collect(a.root/platform,plan)
        print(platform,json.dumps({k:v for k,v in report[platform].items() if k!='channels'}))
    (a.root/'progress-summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    if not a.gallery:return
    from PIL import Image,ImageDraw,ImageFont
    gallery=a.root/'gallery';gallery.mkdir(exist_ok=True);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',13)
    for platform in ['windows','android']:
        frames=[]
        for row in report[platform]['channels']:
            f=a.root/platform/row['id']/('frame.png' if platform=='windows' else (row.get('frames') or ['missing'])[0])
            if f.exists():frames.append((row,f))
        for offset in range(0,len(frames),20):
            chunk=frames[offset:offset+20];sheet=Image.new('RGB',(1200,760),'#eeeeee');draw=ImageDraw.Draw(sheet)
            for i,(row,path) in enumerate(chunk):
                picture=Image.open(path).convert('RGB');picture.thumbnail((232,130));x=i%5*240;y=i//5*190
                sheet.paste(picture,(x+(232-picture.width)//2,y));draw.text((x+3,y+132),row['name'][:18],font=font,fill='black');draw.text((x+3,y+152),row['site'][:29],font=font,fill='black');draw.text((x+3,y+170),row['status']+' '+row['id'][-6:],font=font,fill='#007e32' if row['status']=='passed' else '#b00020')
            sheet.save(gallery/(platform+'-'+str(offset//20+1)+'.jpg'),quality=88)

if __name__=='__main__':main()
