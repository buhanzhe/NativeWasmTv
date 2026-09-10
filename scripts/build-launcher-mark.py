"""Trace the original TV letters and construct an exact rounded television frame.

Development-only dependencies: pillow, numpy, potracer. No tracing runs in the app.
The original PNG stays unchanged for legacy launchers.
"""
from pathlib import Path
import runpy
import xml.etree.ElementTree as ET
import numpy as np
from PIL import Image
import potrace

ROOT = Path(__file__).resolve().parents[1]
NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", NS)


def point(p):
    return f"{p.x:.2f},{p.y:.2f}"


pixels = np.array(Image.open(ROOT / "app/src/main/res/mipmap-hdpi/ic_launcher.png").convert("RGBA"))
white = (pixels[:, :, :3].min(axis=2) > 215) & (pixels[:, :, 3] > 128)
paths = potrace.Bitmap(~white).trace(turdsize=4, alphamax=1.0, opttolerance=0.15)
commands = []
for curve in paths:
    # Only trace the lettering. Pixel tracing can turn the frame's inner round
    # corners into chamfers, so that geometry is constructed explicitly below.
    points = curve.decomposition_points
    if min(p.x for p in points) < 50 or max(p.y for p in points) > 190:
        continue
    commands.append("M" + point(curve.start_point))
    for segment in curve:
        if segment.is_corner:
            commands.append("L" + point(segment.c) + " " + point(segment.end_point))
        else:
            commands.append("C" + point(segment.c1) + " " + point(segment.c2) + " " + point(segment.end_point))
    commands.append("Z")

target = ROOT / "scripts/artwork/ic_launcher_foreground.xml"
tree = ET.parse(target)
group = tree.getroot().find("group")
for child in list(group):
    group.remove(child)
ET.SubElement(group, "path", {
    f"{{{NS}}}fillColor": "#00000000", f"{{{NS}}}strokeColor": "#FFFFFFFF",
    f"{{{NS}}}strokeWidth": "18", f"{{{NS}}}strokeLineCap": "round",
    f"{{{NS}}}pathData": "M85,79 L63,38 M170,79 L191,38"})
ET.SubElement(group, "path", {
    f"{{{NS}}}fillColor": "#FFFFFFFF",
    f"{{{NS}}}pathData": "M45,73 H211 Q227,73 227,89 V201 Q227,217 211,217 H186 "
    "C180.48,217 176,212.75 176,207.5 C176,202.25 180.48,198 186,198 H198 "
    "Q208,198 208,188 V102 Q208,92 198,92 H58 Q48,92 48,102 V188 Q48,198 58,198 "
    "H132 C137.52,198 142,202.25 142,207.5 C142,212.75 137.52,217 132,217 "
    "H45 Q29,217 29,201 V89 Q29,73 45,73 Z"})
if not commands:
    raise RuntimeError("Original TV lettering contour was not found")
ET.SubElement(group, "path", {f"{{{NS}}}fillColor": "#FFFFFFFF", f"{{{NS}}}pathData": " ".join(commands)})
ET.indent(tree, space="    ")
target.write_text('<?xml version="1.0" encoding="utf-8"?>\n'
                  '<!-- Exact rounded frame + original TV lettering; scripts/build-launcher-mark.py. -->\n'
                  + ET.tostring(tree.getroot(), encoding="unicode") + '\n', encoding="utf-8")
print(len(paths), "contours;", target.stat().st_size, "bytes")
runpy.run_path(str(ROOT / "scripts/build-launcher-compat.py"))
