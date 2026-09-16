"""Cut the Rampart brand assets. Text is converted to outlines so the files
do not depend on Archivo being installed anywhere."""
import os
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform

RED = "#DB2D54"
INK = "#141413"
OUT = "/root/platform/rampart/branding"
os.makedirs(OUT, exist_ok=True)

# The mark: a crenellated band over a shield, separated by a gap, the way
# Bulwark's shapes are separated. Drawn in a 200x200 grid, cropped to content.
TOP = ("M26,56 L26,30 L50,30 L50,56 L58,56 L58,30 L82,30 L82,56 L90,56 L90,30 "
       "L114,30 L114,56 L122,56 L122,30 L146,30 L146,56 L154,56 L154,30 "
       "L174,30 L174,56 L100,116 Z")
BOTTOM = ("M26,74 L26,112 C26,150 60,172 100,184 C140,172 174,150 174,112 "
          "L174,74 L100,134 Z")
MARK_X, MARK_Y, MARK_W, MARK_H = 26, 30, 148, 154


def text_path(word, size, tracking_em=0.04):
    """Outline `word` at `size` px, baseline at y=0, starting at x=0."""
    font = instantiateVariableFont(TTFont(os.environ.get("ARCHIVO_TTF", "archivo.ttf")), {"wght": 800, "wdth": 100})
    upem = font["head"].unitsPerEm
    cmap = font.getBestCmap()
    glyphs = font.getGlyphSet()
    hmtx = font["hmtx"]
    scale = size / upem
    tracking = tracking_em * upem
    parts, pen_x = [], 0.0
    for ch in word:
        name = cmap[ord(ch)]
        pen = SVGPathPen(glyphs)
        # Font space is y-up, SVG is y-down: flip while placing on the baseline.
        glyphs[name].draw(TransformPen(pen, Transform(scale, 0, 0, -scale, pen_x * scale, 0)))
        d = pen.getCommands()
        if d:
            parts.append(d)
        pen_x += hmtx[name][0] + tracking
    return " ".join(parts), (pen_x - tracking) * scale


def svg(w, h, body, view=None):
    vb = view or f"0 0 {w} {h}"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" '
            f'width="{w}" height="{h}">\n{body}\n</svg>\n')


def mark(colour, x=MARK_X, y=MARK_Y, scale=1.0):
    g = f'<g transform="translate({x},{y}) scale({scale}) translate({-MARK_X},{-MARK_Y})">' if (x, y, scale) != (MARK_X, MARK_Y, 1.0) else "<g>"
    return (f'{g}<path fill="{colour}" d="{TOP}"/>'
            f'<path fill="{colour}" d="{BOTTOM}"/></g>')


written = []

# The mark alone, three colourways, cropped tight to the artwork.
for name, colour in (("Color", RED), ("Dark", INK), ("White", "#FFFFFF")):
    body = f'<path fill="{colour}" d="{TOP}"/>\n<path fill="{colour}" d="{BOTTOM}"/>'
    p = f"{OUT}/Rampart_Logo_{name}.svg"
    open(p, "w").write(svg(MARK_W, MARK_H, body, view=f"{MARK_X} {MARK_Y} {MARK_W} {MARK_H}"))
    written.append(p)

# App icon: a red tile with the mark knocked out of it.
pad = 46
s = (512 - pad * 2) / MARK_H
mx = (512 - MARK_W * s) / 2
icon = (f'<rect width="512" height="512" rx="112" fill="{RED}"/>'
        f'<g transform="translate({mx:.2f},{pad}) scale({s:.4f}) translate({-MARK_X},{-MARK_Y})">'
        f'<path fill="#FFFFFF" d="{TOP}"/><path fill="#FFFFFF" d="{BOTTOM}"/></g>')
open(f"{OUT}/Rampart_Icon_App.svg", "w").write(svg(512, 512, icon))
written.append(f"{OUT}/Rampart_Icon_App.svg")

# Favicon: the mark alone on a square, red, with a little breathing room.
fs = (256 - 24 * 2) / MARK_H
fx = (256 - MARK_W * fs) / 2
fav = (f'<g transform="translate({fx:.2f},24) scale({fs:.4f}) translate({-MARK_X},{-MARK_Y})">'
       f'<path fill="{RED}" d="{TOP}"/><path fill="{RED}" d="{BOTTOM}"/></g>')
open(f"{OUT}/Rampart_Favicon.svg", "w").write(svg(256, 256, fav))
written.append(f"{OUT}/Rampart_Favicon.svg")

# Lockups: mark on the left, RAMPART in outlined Archivo ExtraBold beside it.
CAP = 0.73  # Archivo cap height as a fraction of em
mark_h = 150.0
type_size = mark_h * 0.60 / CAP
d, text_w = text_path("RAMPART", type_size)
cap_h = type_size * CAP
gap = 34.0
scale = mark_h / MARK_H
mark_w = MARK_W * scale
pad_x, pad_y = 0.0, 0.0
total_w = mark_w + gap + text_w
baseline = pad_y + mark_h / 2 + cap_h / 2

for name, text_colour in (("Color", RED), ("Dark_and_Color", INK), ("White_and_Color", "#FFFFFF")):
    body = (f'<g transform="translate(0,0) scale({scale:.4f}) translate({-MARK_X},{-MARK_Y})">'
            f'<path fill="{RED}" d="{TOP}"/><path fill="{RED}" d="{BOTTOM}"/></g>\n'
            f'<g transform="translate({mark_w + gap:.2f},{baseline:.2f})">'
            f'<path fill="{text_colour}" d="{d}"/></g>')
    p = f"{OUT}/Rampart_Logo_with_Lettering_{name}.svg"
    open(p, "w").write(svg(round(total_w), round(mark_h), body))
    written.append(p)

for p in written:
    print(os.path.basename(p), os.path.getsize(p))
