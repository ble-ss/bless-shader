#!/usr/bin/env python3
"""the readme art for bless, drawn to the daemon print grammar.

    python3 design/readme/build.py

writes design/readme/*.svg, both prints of each: the banner and six
section plates. the style file is ~/.claude/skills/aesthetic/styles/daemon.md
(the ghostprint laws with pure prints), and the frame grammar is ported
from ~/dev/daemon/design/readme/kit.py: the same tokens, the same grain
hash, the same block wordmark idea.

fonts are subset to the characters a file actually sets and inlined as
base64 woff2. github renders an svg inside an img tag, which fetches
nothing off the page, so a font that is not in the file does not exist.
the two woff2 sources live in the daemon repo (FONTS below); nothing at
render time needs them, only a rebuild does.

needs fonttools and brotli.
"""

import base64
import io
import math
from pathlib import Path

OUT = Path(__file__).resolve().parent
FONTS = Path.home() / "dev" / "daemon" / "static" / "fonts"

# ── the prints ──────────────────────────────────────────────────────
# daemon's pure prints, verbatim: white on black and the exact inverse.
PRINTS = {
    "light": {
        "bg": "#ffffff",
        "fg": "#000000",
        "field": "#f2f2f2",
        "acc": "#ff4400",
        "grain": ("#000000", 0.26),
        "rule": ("#000000", 0.16),
        "dim": ("#000000", 0.62),
        "faint": ("#000000", 0.46),
    },
    "dark": {
        "bg": "#000000",
        "fg": "#ffffff",
        "field": "#0a0a0a",
        "acc": "#ff4400",
        "grain": ("#ffffff", 0.20),
        "rule": ("#ffffff", 0.14),
        "dim": ("#ffffff", 0.56),
        "faint": ("#ffffff", 0.40),
    },
}

DISPLAY = "Daemon Display"
MONO = "JetBrains Mono"

FONT_FILES = {
    (DISPLAY, 900): "daemon-display-900.woff2",
    (MONO, 400): "jetbrains-mono-400.woff2",
    (MONO, 500): "jetbrains-mono-500.woff2",
}

_FONT_CACHE: dict[tuple[str, int, str], str] = {}


def font_b64(family: str, weight: int, chars: str) -> str:
    """one weight, subset to the characters this file sets, as woff2."""
    key = (family, weight, chars)
    if key in _FONT_CACHE:
        return _FONT_CACHE[key]
    from fontTools.subset import Options, Subsetter
    from fontTools.ttLib import TTFont

    font = TTFont(str(FONTS / FONT_FILES[(family, weight)]))
    opts = Options()
    opts.layout_features = ["kern", "liga"]
    opts.notdef_outline = True
    opts.name_IDs = ["*"]
    sub = Subsetter(options=opts)
    sub.populate(text=chars)
    sub.subset(font)
    font.flavor = "woff2"
    buf = io.BytesIO()
    font.save(buf)
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    _FONT_CACHE[key] = b64
    return b64


_METRICS: dict[tuple[str, int], tuple[dict[str, int], int]] = {}


def text_w(family: str, weight: int, s: str, size: float, tracking: float = 0.0) -> float:
    """a run's drawn width, read off the face rather than guessed at."""
    key = (family, weight)
    if key not in _METRICS:
        from fontTools.ttLib import TTFont

        font = TTFont(str(FONTS / FONT_FILES[key]))
        cmap = font.getBestCmap()
        hmtx = font["hmtx"]
        _METRICS[key] = (
            {chr(c): hmtx[g][0] for c, g in cmap.items() if c < 0x3000},
            font["head"].unitsPerEm,
        )
    adv, upem = _METRICS[key]
    total = sum(adv.get(ch, upem // 2) for ch in s) / upem
    return total * size + tracking * size * max(len(s) - 1, 0)


def stack(family: str) -> str:
    if family == DISPLAY:
        return '"Daemon Display","Archivo","Helvetica Neue",Helvetica,Arial,sans-serif'
    return '"JetBrains Mono",ui-monospace,SFMono-Regular,Menlo,Consolas,monospace'


# ── xml ─────────────────────────────────────────────────────────────
def esc(text: str) -> str:
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def n(value: float) -> str:
    if abs(value - round(value)) < 1e-6:
        return str(int(round(value)))
    return f"{value:.3f}".rstrip("0").rstrip(".")


class Svg:
    """one file being drawn. append strings, join at the end."""

    def __init__(self, w: float, h: float, print_name: str, scale: float = 1.0):
        self.w = w
        self.h = h
        self.scale = scale
        self.tok = PRINTS[print_name]
        self.parts: list[str] = []
        self.chars: dict[tuple[str, int], set[str]] = {}

    def color(self, name: str) -> tuple[str, float]:
        v = self.tok[name]
        return v if isinstance(v, tuple) else (v, 1.0)

    def paint(self, name: str, prefix: str = "fill") -> str:
        c, a = self.color(name) if name in self.tok else (name, 1.0)
        out = f'{prefix}="{c}"'
        if a != 1.0:
            out += f' {prefix}-opacity="{n(a)}"'
        return out

    def add(self, markup: str) -> None:
        self.parts.append(markup)

    # -- primitives
    def rect(self, x, y, w, h, fill=None, stroke=None, sw=1.0, extra=""):
        bits = [f'x="{n(x)}" y="{n(y)}" width="{n(w)}" height="{n(h)}"']
        if stroke:
            bits = [
                f'x="{n(x + sw / 2)}" y="{n(y + sw / 2)}" '
                f'width="{n(w - sw)}" height="{n(h - sw)}"'
            ]
        bits.append(self.paint(fill) if fill else 'fill="none"')
        if stroke:
            bits.append(self.paint(stroke, "stroke") + f' stroke-width="{n(sw)}"')
        if extra:
            bits.append(extra)
        self.add(f"<rect {' '.join(bits)}/>")

    def line(self, x1, y1, x2, y2, stroke="rule", sw=1.0, crisp=True):
        edge = ' shape-rendering="crispEdges"' if crisp else ""
        self.add(
            f'<line x1="{n(x1)}" y1="{n(y1)}" x2="{n(x2)}" y2="{n(y2)}" '
            f'{self.paint(stroke, "stroke")} stroke-width="{n(sw)}"{edge}/>'
        )

    def poly(self, pts, fill=None, stroke=None, sw=1.0):
        d = " ".join(f"{n(px)},{n(py)}" for px, py in pts)
        bits = [f'points="{d}"', self.paint(fill) if fill else 'fill="none"']
        if stroke:
            bits.append(self.paint(stroke, "stroke") + f' stroke-width="{n(sw)}"')
        self.add(f"<polygon {' '.join(bits)}/>")

    def path(self, d, fill=None, stroke=None, sw=1.0, cap="butt"):
        bits = [f'd="{d}"', self.paint(fill) if fill else 'fill="none"']
        if stroke:
            bits.append(
                self.paint(stroke, "stroke")
                + f' stroke-width="{n(sw)}" stroke-linecap="{cap}" stroke-linejoin="miter"'
            )
        self.add(f"<path {' '.join(bits)}/>")

    def circle(self, cx, cy, r, fill=None, stroke=None, sw=1.0):
        bits = [f'cx="{n(cx)}" cy="{n(cy)}" r="{n(r)}"']
        bits.append(self.paint(fill) if fill else 'fill="none"')
        if stroke:
            bits.append(self.paint(stroke, "stroke") + f' stroke-width="{n(sw)}"')
        self.add(f"<circle {' '.join(bits)}/>")

    def ellipse(self, cx, cy, rx, ry, fill=None, stroke=None, sw=1.0):
        bits = [f'cx="{n(cx)}" cy="{n(cy)}" rx="{n(rx)}" ry="{n(ry)}"']
        bits.append(self.paint(fill) if fill else 'fill="none"')
        if stroke:
            bits.append(self.paint(stroke, "stroke") + f' stroke-width="{n(sw)}"')
        self.add(f"<ellipse {' '.join(bits)}/>")

    def text(self, x, y, s, family=MONO, size=11, weight=400, fill="fg",
             tracking=0.0, anchor=None, preserve=False):
        self.chars.setdefault((family, weight), set()).update(s)
        bits = [
            f'x="{n(x)}" y="{n(y)}"',
            f"font-family='{stack(family)}'",
            f'font-size="{n(size)}"',
            f'font-weight="{weight}"',
            self.paint(fill),
        ]
        if tracking:
            bits.append(f'letter-spacing="{n(tracking * size)}"')
        if anchor:
            bits.append(f'text-anchor="{anchor}"')
        if preserve:
            bits.append('xml:space="preserve"')
        self.add(f"<text {' '.join(bits)}>{esc(s)}</text>")

    def out(self) -> str:
        faces = []
        for (family, weight), chars in self.chars.items():
            b64 = font_b64(family, weight, "".join(sorted(chars)))
            faces.append(
                f'@font-face{{font-family:"{family}";font-weight:{weight};'
                f'src:url(data:font/woff2;base64,{b64}) format("woff2")}}'
            )
        head = (
            f'<svg xmlns="http://www.w3.org/2000/svg" '
            f'width="{n(self.w * self.scale)}" height="{n(self.h * self.scale)}" '
            f'viewBox="0 0 {n(self.w)} {n(self.h)}" role="img">'
        )
        return head + "<defs><style>" + "".join(faces) + "</style></defs>" + "".join(self.parts) + "</svg>\n"

    def write(self, path: Path) -> None:
        path.write_text(self.out(), encoding="utf-8")
        print(f"{path.name}  {path.stat().st_size / 1024:.1f} kb")


# ── the grain ───────────────────────────────────────────────────────
# daemon's ground, hash for hash: the same density field at t=0, so a
# bless plate and a daemon plate are printed on the same paper.
RAMP_CALM = "   ..··──="
GROUND_CELL = 11.0
GROUND_LH = 1.02
GROUND_INK = 0.62
DENSITY_BOOST = 1.2


def _hash(x: int, y: int) -> float:
    def imul(a: int, b: int) -> int:
        r = (a * b) & 0xFFFFFFFF
        return r - 0x100000000 if r >= 0x80000000 else r

    v = imul(x, 374761393) + imul(y, 668265263)
    v &= 0xFFFFFFFF
    v = imul(v ^ (v >> 13), 1274126177)
    v &= 0xFFFFFFFF
    return ((v ^ (v >> 16)) & 0xFFFFFFFF) / 4294967295


def base_density(x: int, y: int) -> float:
    a = math.sin(x * 0.052)
    b = math.sin(y * 0.135)
    c = math.sin(x * 0.031 + y * 0.071)
    v = 0.5 + (a * 0.2 + b * 0.15 + c * 0.26) * 0.62
    v += (_hash(x, y) - 0.5) * 0.13
    v *= DENSITY_BOOST
    return 0.0 if v < 0 else (1.0 if v > 1 else v)


def ground(svg: Svg, x: float, y: float, w: float, h: float, seed: int = 0) -> None:
    """the glyph field, drawn as real mono rows in --grain."""
    cw = GROUND_CELL * 0.6
    rh = GROUND_CELL * GROUND_LH
    cols = int(w / cw) + 1
    rows = int(h / rh) + 1
    top = len(RAMP_CALM) - 1
    spans = []
    used: set[str] = set()
    for ry in range(rows):
        chars = []
        for rx in range(cols):
            d = base_density(rx + seed, ry) * GROUND_INK
            i = int(d * top)
            chars.append(RAMP_CALM[0 if i < 0 else (top if i > top else i)])
        line = "".join(chars).rstrip()
        if line:
            used.update(line)
            spans.append(f'<tspan x="{n(x)}" y="{n(y + (ry + 1) * rh - 2)}">{esc(line)}</tspan>')
    svg.chars.setdefault((MONO, 400), set()).update(used | {" "})
    cid = f"g{len(svg.parts)}"
    svg.add(
        f'<clipPath id="{cid}"><rect x="{n(x)}" y="{n(y)}" '
        f'width="{n(w)}" height="{n(h)}"/></clipPath><g clip-path="url(#{cid})">'
        f'<text xml:space="preserve" font-family=\'{stack(MONO)}\' '
        f'font-size="{n(GROUND_CELL)}" font-weight="400" {svg.paint("grain")}>'
        + "".join(spans)
        + "</text></g>"
    )


# ── the block wordmark ──────────────────────────────────────────────
# bless, lowercase, cut on the same grid daemon's DAEMON is cut on: two
# cells to a stem, one row to a bar, one cell between letters, every edge
# on the grid. drawn as rectangles rather than set in a mono, so the word
# needs no font at all and holds at any size the banner wants.
#
# the cell is half a row high, which is what makes a two-cell stem and a
# one-row bar the same weight. ascenders take the top two rows; the five
# under them are the x-height.
GLYPHS = {
    "b": [
        "##     ",
        "##     ",
        "###### ",
        "##   ##",
        "##   ##",
        "##   ##",
        "###### ",
    ],
    "l": ["##"] * 7,
    "e": [
        "       ",
        "       ",
        " ##### ",
        "##   ##",
        "#######",
        "##     ",
        " ##### ",
    ],
    "s": [
        "       ",
        "       ",
        " ######",
        "##     ",
        " ##### ",
        "     ##",
        "###### ",
    ],
}


def word_rows(word: str, gap: int = 1) -> list[str]:
    rows = ["" for _ in range(7)]
    for i, ch in enumerate(word):
        glyph = GLYPHS[ch]
        for r in range(7):
            if i:
                rows[r] += " " * gap
            rows[r] += glyph[r]
    return rows


WORD_ROWS = word_rows("bless")


def wordmark(svg: Svg, x: float, y: float, cw: float, fill: str = "fg") -> tuple[float, float]:
    """the word, as rectangles. returns its drawn box. one row is 2*cw."""
    rh = cw * 2
    paint = svg.paint(fill)
    for ry, row in enumerate(WORD_ROWS):
        rx = 0
        while rx < len(row):
            if row[rx] != "#":
                rx += 1
                continue
            run = 0
            while rx + run < len(row) and row[rx + run] == "#":
                run += 1
            svg.add(
                f'<rect x="{n(x + rx * cw)}" y="{n(y + ry * rh)}" '
                f'width="{n(run * cw)}" height="{n(rh)}" {paint}/>'
            )
            rx += run
    return (max(len(r) for r in WORD_ROWS) * cw, len(WORD_ROWS) * rh)


# ── the banner ──────────────────────────────────────────────────────
BAN_W, BAN_H = 1200.0, 300.0
M = 64.0  # the margin the word is set to, inside the crop marks at 32
TAG = "a shader for minecraft 26.2, in fabric, on vulkan and opengl"
META = "two bounces ⁄ coloured light"


def crop_marks(svg: Svg, w: float, h: float, inset: float = 32.0, arm: float = 22.0) -> None:
    """the printer's own marks, at the safe square."""
    for d in (
        f"M{n(inset)},{n(inset + arm)} V{n(inset)} H{n(inset + arm)}",
        f"M{n(w - inset - arm)},{n(inset)} H{n(w - inset)} V{n(inset + arm)}",
        f"M{n(inset)},{n(h - inset - arm)} V{n(h - inset)} H{n(inset + arm)}",
        f"M{n(w - inset - arm)},{n(h - inset)} H{n(w - inset)} V{n(h - inset - arm)}",
    ):
        svg.add(
            f'<path d="{d}" fill="none" {svg.paint("faint", "stroke")} '
            f'stroke-width="1" shape-rendering="crispEdges"/>'
        )


def scene(svg: Svg, x0: float, y0: float, x1: float, y1: float) -> None:
    """what the shader does, in a few shapes: a low sun, a window throwing
    three bands on a floor, a lantern's glow standing in the air."""
    floor = y1 - 34
    svg.line(x0, floor + 0.5, x1, floor + 0.5, "fg", 1)

    # the low sun. the one orange on the print, because it is the one
    # light in the picture; everything else is what that light does.
    svg.circle(x0 + 76, floor - 30, 32, fill="acc")

    # the window: six lights on a stone mullion, high on the wall.
    wx, wy, ww, wh = x0 + 166, y0, 150.0, 100.0
    col = ww / 3
    svg.rect(wx, wy, ww, wh, stroke="fg", sw=2)
    for i in (1, 2):
        svg.line(wx + i * col, wy, wx + i * col, wy + wh, "fg", 2, crisp=False)
    svg.line(wx, wy + wh / 2, wx + ww, wy + wh / 2, "fg", 2, crisp=False)

    # the three bands the glass throws, one tone each, falling off with
    # the angle. the mullions stand in them: the gap is the shadow.
    drop = floor - (wy + wh)
    inset = 3.0
    for i, tone in enumerate(("faint", "grain", "rule")):
        lo = wx + i * col + inset
        hi = lo + col - 2 * inset
        svg.poly(
            [(lo, wy + wh), (hi, wy + wh), (hi + drop, floor), (lo + drop, floor)],
            fill=tone,
        )

    # the lantern, and the air around it taking the light. one tone laid
    # six times over itself: a march accumulates, so the step is small and
    # the falloff is the sum, never a ring.
    lx, ly = x1 - 58, y0 + 84
    for r in (54.0, 45.0, 36.0, 28.0, 20.0, 13.0):
        svg.circle(lx, ly, r, fill="rule")
    svg.rect(lx - 8, ly - 7, 16, 14, fill="fg")
    svg.line(lx - 13, ly - 10, lx + 13, ly - 10, "fg", 3)


def banner(print_name: str, path: Path) -> None:
    svg = Svg(BAN_W, BAN_H, print_name)
    svg.rect(0, 0, BAN_W, BAN_H, fill="bg")
    ground(svg, 0, 0, BAN_W, BAN_H, seed=5)
    crop_marks(svg, BAN_W, BAN_H)

    # the word, unplated: the blocks are solid, so the grain reads through
    # the counters and the letter gaps instead of under a paper band.
    cw = 9.0
    word_w, word_h = wordmark(svg, M, 52, cw)

    # the rule under the word. the orange runs the two cells of the l and
    # then hands the line to the ink: a state held for a short measure.
    rule_y = 52 + word_h + 26 + 0.5
    svg.line(M, rule_y, M + 10 * cw, rule_y, "acc", 3)
    svg.line(M + 10 * cw, rule_y, M + word_w, rule_y, "fg", 3)

    # the tagline and the meta line, both plated, both mono.
    tag_base = rule_y + 31.5
    tw = text_w(MONO, 500, TAG, 13, 0.08)
    svg.rect(M - 8, tag_base - 19, tw + 16, 29, fill="bg")
    svg.text(M, tag_base, TAG, MONO, 13, 500, "dim", tracking=0.08)

    meta_base = tag_base + 24
    mw = text_w(MONO, 400, META, 11, 0.08)
    svg.rect(M - 8, meta_base - 16, mw + 16, 24, fill="bg")
    svg.text(M, meta_base, META, MONO, 11, 400, "faint", tracking=0.08)

    scene(svg, 640, 56, BAN_W - M, 262)
    svg.write(path)


# ── the section plates ──────────────────────────────────────────────
PLATE_W, PLATE_H = 1200.0, 160.0
PM = 64.0
MARK_BOX = 112.0


def mark_light(svg: Svg, x: float, y: float, s: float) -> None:
    """a lantern: a cap, a body, and the flame that is the whole point."""
    bw, bh = 0.46 * s, 0.44 * s
    bx, by = x + (s - bw) / 2, y + 0.34 * s
    svg.line(x + s / 2, y + 0.14 * s, x + s / 2, y + 0.34 * s, "fg", 2, crisp=False)
    svg.line(bx - 6, y + 0.34 * s, bx + bw + 6, y + 0.34 * s, "fg", 4)
    svg.rect(bx, by, bw, bh, stroke="fg", sw=2)
    svg.rect(x + s / 2 - 0.08 * s, y + 0.44 * s, 0.16 * s, 0.24 * s, fill="acc")
    for dx in (-1, 1):
        svg.line(x + s / 2 + dx * (bw / 2 + 6), y + 0.56 * s,
                 x + s / 2 + dx * (bw / 2 + 22), y + 0.56 * s, "dim", 2)


def mark_bounce(svg: Svg, x: float, y: float, s: float) -> None:
    """a ray in, the floor, the ceiling, and the ray still going: two
    bounces is the budget, and the second one is where it stops."""
    floor = y + 0.84 * s
    roof = y + 0.14 * s
    svg.line(x, floor + 0.5, x + s, floor + 0.5, "faint", 1)
    svg.line(x, roof - 0.5, x + s, roof - 0.5, "rule", 1)
    a = (x + 0.02 * s, y + 0.30 * s)
    b = (x + 0.36 * s, floor)
    c = (x + 0.70 * s, roof)
    d = (x + 0.98 * s, y + 0.50 * s)
    svg.path(
        f"M{n(a[0])},{n(a[1])} L{n(b[0])},{n(b[1])} L{n(c[0])},{n(c[1])} L{n(d[0])},{n(d[1])}",
        stroke="fg", sw=2,
    )
    for px, py in (b, c):
        svg.rect(px - 5, py - 5, 10, 10, fill="acc")


def mark_glass(svg: Svg, x: float, y: float, s: float) -> None:
    """a pane in six lights, one of them coloured."""
    pw, ph = 0.78 * s, 0.80 * s
    px, py = x + (s - pw) / 2, y + 0.10 * s
    col, row = pw / 3, ph / 2
    svg.rect(px + col, py, col, row, fill="acc")
    svg.rect(px + 2 * col, py + row, col, row, fill="grain")
    svg.rect(px, py, pw, ph, stroke="fg", sw=2)
    for i in (1, 2):
        svg.line(px + i * col, py, px + i * col, py + ph, "fg", 2, crisp=False)
    svg.line(px, py + row, px + pw, py + row, "fg", 2, crisp=False)


def mark_water(svg: Svg, x: float, y: float, s: float) -> None:
    """a ripple, read flat: a drop, and three rings off where it landed."""
    cx, cy = x + s / 2, y + 0.60 * s
    for rx, tone in ((0.48 * s, "grain"), (0.33 * s, "faint"), (0.18 * s, "dim")):
        svg.ellipse(cx, cy, rx, rx * 0.36, stroke=tone, sw=2)
    svg.rect(cx - 5, cy - 5, 10, 10, fill="acc")
    svg.line(cx, y + 0.10 * s, cx, y + 0.46 * s, "fg", 3, crisp=False)


def mark_air(svg: Svg, x: float, y: float, s: float) -> None:
    """a shaft: an aperture, and the air under it holding the light."""
    ax0, ax1 = x + 0.34 * s, x + 0.58 * s
    top, bot = y + 0.14 * s, y + 0.90 * s
    svg.poly([(ax0, top), (ax1, top), (x + 0.94 * s, bot), (x + 0.06 * s, bot)], fill="rule")
    svg.poly([(ax0 + 4, top), (ax1 - 4, top), (x + 0.74 * s, bot), (x + 0.26 * s, bot)], fill="grain")
    svg.line(ax0, top - 2, ax1, top - 2, "acc", 4)


def mark_bench(svg: Svg, x: float, y: float, s: float) -> None:
    """a ruler: even ticks, and the one that is being read."""
    base = y + 0.70 * s
    svg.line(x + 0.04 * s, base + 1, x + 0.96 * s, base + 1, "fg", 2)
    step = 0.115 * s
    for i in range(9):
        tx = x + 0.04 * s + i * step
        long = i % 2 == 0
        h = 0.20 * s if long else 0.11 * s
        svg.line(tx, base - h, tx, base, "fg" if long else "dim", 2)
    tx = x + 0.04 * s + 5 * step
    svg.line(tx, base - 0.34 * s, tx, base, "acc", 2)
    svg.rect(tx - 4, base - 0.34 * s - 8, 8, 8, fill="acc")


MARKS = {
    "light": mark_light,
    "bounce": mark_bounce,
    "glass": mark_glass,
    "water": mark_water,
    "air": mark_air,
    "bench": mark_bench,
}

PLATES = [
    ("01", "light", "coloured light, and the sun that casts it"),
    ("02", "bounce", "two bounces of it, traced through the voxels"),
    ("03", "glass", "what stained glass does to a beam"),
    ("04", "water", "wet stone, and what it gives back"),
    ("05", "air", "the shaft standing in the air"),
    ("06", "bench", "frame cost, measured before it ships"),
]


def plate(print_name: str, num: str, word: str, note: str, path: Path) -> None:
    svg = Svg(PLATE_W, PLATE_H, print_name)
    svg.rect(0, 0, PLATE_W, PLATE_H, fill="bg")
    ground(svg, 0, 0, PLATE_W, PLATE_H, seed=int(num) * 7)

    # the island: one cutout under the slug and the word, no bleed.
    title_size = 48.0
    slug = f"// {num}"
    slug_w = text_w(MONO, 400, slug, 11, 0.08)
    word_w = text_w(DISPLAY, 900, word, title_size)
    isle_w = max(slug_w, word_w) + 28
    svg.rect(PM - 14, 30, isle_w, 100, fill="bg")
    svg.text(PM, 58, slug, MONO, 11, 400, "faint", tracking=0.08)
    svg.text(PM, 112, word, DISPLAY, title_size, 900, "fg")

    # the note, right of the word on the same baseline band.
    nw = text_w(MONO, 400, note, 11, 0.08)
    svg.rect(PM + isle_w - 6, 96, nw + 24, 24, fill="bg")
    svg.text(PM + isle_w + 6, 112, note, MONO, 11, 400, "dim", tracking=0.08)

    mx = PLATE_W - PM - MARK_BOX
    svg.rect(mx - 10, (PLATE_H - MARK_BOX) / 2 - 8, MARK_BOX + 20, MARK_BOX + 16, fill="bg")
    MARKS[word](svg, mx, (PLATE_H - MARK_BOX) / 2, MARK_BOX)

    svg.line(0, PLATE_H - 0.5, PLATE_W, PLATE_H - 0.5, "rule", 1)
    svg.write(path)


def main() -> None:
    for name in ("dark", "light"):
        banner(name, OUT / f"header-{name}.svg")
    for num, word, note in PLATES:
        for name in ("dark", "light"):
            plate(name, num, word, note, OUT / f"{num}-{word}-{name}.svg")


if __name__ == "__main__":
    main()
