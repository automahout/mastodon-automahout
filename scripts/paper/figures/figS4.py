"""Supplementary figure S4: Automahout in Fiji, from screenshots of a GUI run, as an editable PowerPoint slide with a
PNG of it beside (400 dpi).

The shots are taken by hand during a run on Fluo-N3DH-CE 01, t = 0-19 (two windows), and saved in one folder as
the stems named in ROWS below (.png or .jpg); panels are lettered in reading order. A shot not yet taken is drawn as a grey box with its
description, so the layout can be reviewed before the run.

    uv run python figures/figS4.py --shots DIR --out figS4.pptx [--no-png]
"""

import argparse
from pathlib import Path

from PIL import Image
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Mm, Pt

from render import render_png

FONT = "Arial"
INK = RGBColor(0x26, 0x26, 0x26)
GREY = RGBColor(0x6E, 0x6E, 0x6E)
PLACEHOLDER = RGBColor(0xEE, 0xEE, 0xEE)

# The panels in reading order, row by row: (screenshot file stem, caption under the panel, what to capture).
ROWS = [
    [("a", "Started: the pre-flight reads the data",
      "the Automahout log as the pre-flight starts, beside the movie in BigDataViewer"),
     ("g", "First window: reference cells",
      "the log at the first window's review, and the reference cells it annotated in BigDataViewer"),
     ("h", "After the first window",
      "the log as the first window ends (fine-tune done), and the tracks in BigDataViewer")],
    [("b", "Set-up 1: the data", "the set-up, step 1 of 4"),
     ("c", "Set-up 2: image type", "the set-up, step 2 of 4"),
     ("d", "Set-up 3: options", "the set-up, step 3 of 4"),
     ("f", "Set-up 4: instructions", "the set-up, step 4 of 4")],
]
W = 174  # mm
GAP, CAPTION_H, PLACEHOLDER_ASPECT = 2.5, 4.5, 0.73  # height / width of a panel without a shot


def text(slide, x, y, w, h, runs, size=6.5, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP):
    tb = slide.shapes.add_textbox(Mm(x), Mm(y), Mm(w), Mm(h))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    for side in ("margin_left", "margin_right", "margin_top", "margin_bottom"):
        setattr(tf, side, Mm(0.5))
    p = tf.paragraphs[0]
    p.alignment = align
    for t, colour, bold in runs:
        r = p.add_run()
        r.text, r.font.name, r.font.size, r.font.bold, r.font.color.rgb = t, FONT, Pt(size), bold, colour
    return tb


def shot_file(folder, stem):
    for ext in (".png", ".jpg", ".jpeg"):
        f = Path(folder) / (stem + ext)
        if f.exists():
            return f
    return None


def build(shots, out):
    rows = []
    for row in ROWS:
        pw = (W - 2 - GAP * (len(row) - 1)) / len(row)
        files = [shot_file(shots, stem) if shots else None for stem, _, _ in row]
        aspects = []
        for f in files:
            if f is not None:
                with Image.open(f) as im:
                    aspects.append(im.size[1] / im.size[0])
        rows.append((row, files, pw, pw * (max(aspects) if aspects else PLACEHOLDER_ASPECT)))
    h = 1 + sum(ph + CAPTION_H + GAP for *_, ph in rows)
    prs = Presentation()
    prs.slide_width, prs.slide_height = Mm(W), Mm(h)
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    missing, letter, y = [], 0, 1
    for row, files, pw, ph in rows:
        for k, ((stem, caption, what), f) in enumerate(zip(row, files)):
            x = 1 + k * (pw + GAP)
            if f is None:
                missing.append(stem)
                box = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Mm(x), Mm(y), Mm(pw), Mm(ph))
                box.fill.solid()
                box.fill.fore_color.rgb = PLACEHOLDER
                box.line.fill.background()
                box._element.remove(box._element.find(qn("p:style")))  # no theme shadow in LibreOffice
                text(slide, x + 2, y, pw - 4, ph, [("To capture: " + what, GREY, False)], size=6,
                     align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
            else:
                # Fit inside the panel, keeping the screenshot's aspect, centred.
                with Image.open(f) as im:
                    iw, ih = im.size
                sc = min(pw / iw, ph / ih)
                slide.shapes.add_picture(str(f), Mm(x + (pw - iw * sc) / 2), Mm(y + (ph - ih * sc) / 2),
                                         Mm(iw * sc), Mm(ih * sc))
            text(slide, x, y + ph + 0.3, pw, CAPTION_H,
                 [("abcdefghij"[letter] + "  ", INK, True), (caption, INK, False)], size=6.5)
            letter += 1
        y += ph + CAPTION_H + GAP
    prs.save(out)
    return h, missing


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--shots", help="folder with the screenshots named in ROWS; omit for the layout with placeholders")
    ap.add_argument("--out", default="figS4.pptx")
    ap.add_argument("--no-png", action="store_true")
    a = ap.parse_args()
    h, missing = build(a.shots, a.out)
    print(a.out)
    if missing:
        print("not yet captured: " + ", ".join(missing))
    if not a.no_png:
        png = render_png(a.out, W, h)
        if png:
            print(png)
