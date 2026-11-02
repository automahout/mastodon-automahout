"""Fig. 1 as one image (400 dpi, 174 mm wide) from its panel PNGs: a and b across the width, c, d and e side by side
in one row; each panel lettered at its top left.

    uv run python figures/fig1_compose.py ../../paper/figures/fig1 --out ../../paper/figures/fig1/fig1.png
"""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W = 2740  # px: 174 mm at 400 dpi, the width of the full-width panels
GAP = 40  # px between panels
LETTER_PT = 9
FONTS = ["/System/Library/Fonts/Supplemental/Arial Bold.ttf", "/Library/Fonts/Arial Bold.ttf",
         "/usr/share/fonts/truetype/msttcorefonts/Arial_Bold.ttf"]


def font(px):
    for f in FONTS:
        if Path(f).exists():
            return ImageFont.truetype(f, px)
    return ImageFont.load_default()


def compose(folder, out):
    p = {k: Image.open(Path(folder) / f"fig1{k}.png").convert("RGB") for k in "abcde"}
    for k in "ab":
        assert p[k].width == W, f"fig1{k}.png is {p[k].width} px wide, expected {W}"
    third = (W - 2 * GAP) // 3
    for k in "cde":
        p[k] = p[k].resize((third, round(p[k].height * third / p[k].width)), Image.LANCZOS)
    letter_px = round(LETTER_PT / 72 * 400)
    pad = letter_px + 10  # room above each panel for its letter
    rows = [[("a", 0)], [("b", 0)], [("c", 0), ("d", third + GAP), ("e", 2 * (third + GAP))]]
    heights = [max(p[k].height for k, _ in row) for row in rows]
    canvas = Image.new("RGB", (W, sum(h + pad for h in heights) + GAP * (len(rows) - 1)), "white")
    draw, f, y = ImageDraw.Draw(canvas), font(letter_px), 0
    for row, h in zip(rows, heights):
        for k, x in row:
            draw.text((x, y), k, fill=(0, 0, 0), font=f)
            canvas.paste(p[k], (x, y + pad))
        y += pad + h + GAP
    canvas.save(out, dpi=(400, 400))
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("folder", help="the folder holding fig1a.png ... fig1e.png")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    print(compose(a.folder, a.out))
