"""Compare field-rate BWDIF/YADIF against a known progressive motion source.

Requires ffmpeg with drawtext and numpy. Wrong-parity negative controls must
produce backwards steps; correct top/bottom-first output must remain monotonic.
This complements Android pixel equality tests; it does not measure display timing.
"""
import argparse
import json
from pathlib import Path
import subprocess
import numpy as np

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--steps', default='2,4,8,16',
                    help='Source pixels moved per progressive output frame (50 fields/s)')
args = parser.parse_args()
root = args.output.resolve()
root.mkdir(parents=True, exist_ok=True)
rows = []

steps = [int(value) for value in args.steps.split(',')]
if not steps or any(value < 1 or value > 32 for value in steps):
    parser.error('--steps must contain integers in 1..32')

for step, direction in [(step, direction) for step in steps for direction in ['up', 'down']]:
    # Limit travel so each glyph remains fully visible without a wrap/cutoff.
    count = min(50, (400 // step) // 2 * 2)
    start = 750 if direction == 'up' else 250
    end = start + (count - 1) * step * (-1 if direction == 'up' else 1)
    crop_y = min(start, end) // 2 * 2 - 6
    crop_h = (abs(end-start) + 90) // 2 * 2
    source = root / (direction + '-' + str(step) + 'px-50p.mkv')
    y = str(start) + ('-' if direction == 'up' else '+') + str(50*step) + '*t'
    subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i',
                    'color=c=0x303030:s=1920x1080:r=50:d=1','-vf',
                    "drawtext=text='1080i50 SCROLL 0123456789':fontcolor=white:fontsize=52:x=180:y='" + y + "'",
                    '-frames:v',str(count),'-c:v','ffv1',str(source)],check=True)

    def frames(vf):
        data = subprocess.check_output(['ffmpeg','-v','error','-i',str(source),
                                        '-vf',vf+f',crop=1200:{crop_h}:160:{crop_y},format=gray',
                                        '-fps_mode','passthrough','-f','rawvideo','-'])
        return np.frombuffer(data,np.uint8).reshape(-1,crop_h,1200)

    reference = frames('null')
    assert len(reference) == count
    for order in ['top', 'bottom']:
        for algorithm in ['bwdif', 'yadif']:
            wrong = 'bff' if order == 'top' else 'tff'
            for parity in ['auto', wrong]:
                actual = frames('tinterlace=mode=interleave_' + order + ',' + algorithm
                                + '=mode=send_field:parity=' + parity + ':deint=all')
                assert len(actual) == count, 'Must output every source field'
                # Exclude spatial-only startup/EOF from temporal quality scores.
                error = actual[2:-2].astype(np.float64) - reference[2:-2]
                mse = float(np.mean(error * error))
                centers = [float(np.where(frame > 190)[0].mean()) for frame in actual[2:-2]]
                assert all(np.isfinite(centers)), 'Text must remain visible'
                delta = np.diff(centers) * (1 if direction == 'down' else -1)
                row = dict(pixelsPerField=step,pixelsPerSecond=50*step,
                           direction=direction,order=order,algorithm=algorithm,parity=parity,
                           frames=len(actual),textRoiPsnr=round(float(10*np.log10(255**2/mse)),3),
                           reverseSteps=int(np.sum(delta < -.25)),
                           maxReversePixels=round(float(max(0,-np.min(delta))),3))
                rows.append(row)
                print(row,flush=True)
                (root/'quality.json').write_text(json.dumps(rows,indent=2))
                if parity == 'auto':
                    assert row['reverseSteps'] == 0 and row['textRoiPsnr'] > 25
                else:
                    assert row['reverseSteps'] >= (count-4)//3, 'Negative control must detect wrong field order'
print('PASS: field-rate output, all speeds/directions/field orders and wrong-parity controls')
