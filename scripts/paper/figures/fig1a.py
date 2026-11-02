"""Figure 1a: the steps of tracking, each with how a person does it and how Automahout does it.

Three columns without a grid: the step (its purpose), then how a person does it and how Automahout does it, each a
noun phrase.
Boxes across the columns mark the training loop and the steps repeated for each time window; a last column gives
each step's phase (detection or linking). Writes an editable PowerPoint slide (full
page width, 174 mm) and a PNG of the same slide beside it for review (rendered by LibreOffice at 400 dpi; skipped
with a note when LibreOffice is not found, or with --no-png).

    uv run python figures/fig1a.py [--out fig1a.pptx] [--no-png]
"""

import argparse
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from lxml import etree
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Mm, Pt

WIDTH, HEIGHT = 174, 104  # mm
FONT = "Arial"
INK = RGBColor(0x26, 0x26, 0x26)
PERSON = RGBColor(0x6E, 0x6E, 0x6E)  # neutral grey: the person
AGENT = RGBColor(0x00, 0x72, 0xB2)  # one accent colour: Automahout (Okabe-Ito blue)
PERSON_FILL = RGBColor(0xF2, 0xF2, 0xF2)
AGENT_FILL = RGBColor(0xE6, 0xF1, 0xF8)

# One row per step: (purpose, how a person does it, how Automahout does it), each "how" a noun phrase.
STEPS = [
    ("Look at the data", "prior knowledge about the data and visual inspection of the stack",
     "image metadata, intensity histogram, the cell size measured in the image (blobs at several sizes) and a VLM description"),
    ("Try existing models", "a pretrained model, judged by eye",
     "a detector trial (Cellpose, Cellpose-SAM, StarDist, micro-SAM) by image agreement or frame-to-frame persistence"),
    ("Annotate a few cells", "manual annotation of obvious cells", "bright blobs that stand out clearly, at the measured cell size"),
    ("Train", "ELEPHANT training on the annotations",
     "ELEPHANT training through its HTTP API, parameters from the cell size"),
    ("Predict and inspect", "visual comparison of prediction and image",
     "whole-frame prediction compared with the references"),
    ("Look closer when unsure", "inspection of neighbouring frames", "a VLM check on image crops"),
    ("Proofread", "manual missed-cell (fn) and false-positive (fp) annotation", "property-based fn / fp labelling"),
    ("Link", "Mastodon/ELEPHANT linking and manual curation", "nearest-neighbour linking with distance-based filtering"),
    ("Check the lineage", "inspection in Mastodon TrackScheme",
     "lineage checks: implausible divisions, breaks, short and doubled tracks"),
    ("Train on the corrected result", "retraining on the proofread window",
     "fine-tuning on the linked detections, shapes fitted by micro-SAM"),
    ("Move on", "the judgement that nothing is left to fix", "go-next / completion decisions over frame windows"),
]
TOP, ROW_H, PITCH = 11, 6.4, 8.2  # mm
STEP_X, STEP_W = 16, 37
LEFT_X, LEFT_W = 54, 42
RIGHT_X, RIGHT_W = 97, 60
PHASE_X, PHASE_W = 157.5, 11.5
LOOP = (3, 6)  # rows 4-7 (0-based 3..6): train, predict, look closer, proofread
WINDOW = (2, 10)  # rows 3-11: done for every time window
SPAN_RIGHT = 171  # the boxes end here
# The phase each step belongs to, by row (0-based); a row not listed has none.
PHASES = {**{i: "Detection" for i in (1, 2, 3, 4, 5, 6, 9)}, 7: "Linking", 8: "Linking"}
PHASE_FILL = {"Detection": RGBColor(0xFC, 0xEB, 0xC8), "Linking": RGBColor(0xCF, 0xEE, 0xE3)}  # light Okabe-Ito orange, bluish green


def row_y(i):
    return TOP + i * PITCH


def text_into(tf, paras, align=PP_ALIGN.LEFT):
    tf.word_wrap = True
    for side in ("margin_left", "margin_right", "margin_top", "margin_bottom"):
        setattr(tf, side, Mm(0.9))
    for i, para in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        for text, size, colour, bold in para:
            r = p.add_run()
            r.text = text
            r.font.name = FONT
            r.font.size = Pt(size)
            r.font.bold = bold
            r.font.color.rgb = colour


def text_box(slide, x, y, w, h, paras, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP):
    tb = slide.shapes.add_textbox(Mm(x), Mm(y), Mm(w), Mm(h))
    tb.text_frame.vertical_anchor = anchor
    text_into(tb.text_frame, paras, align)
    return tb


def box(slide, x, y, w, h, fill, line, paras):
    s = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Mm(x), Mm(y), Mm(w), Mm(h))
    s.adjustments[0] = 0.18
    s.fill.solid()
    s.fill.fore_color.rgb = fill
    s.line.color.rgb = line
    s.line.width = Pt(0.75)
    s.shadow.inherit = False
    s.text_frame.vertical_anchor = MSO_ANCHOR.MIDDLE
    text_into(s.text_frame, paras)
    return s


def line(slide, x1, y1, x2, y2, colour=INK, head=True, width=0.75):
    c = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Mm(x1), Mm(y1), Mm(x2), Mm(y2))
    c.line.color.rgb = colour
    c.line.width = Pt(width)
    if head:
        tail = etree.SubElement(c.line._get_or_add_ln(), qn("a:tailEnd"))
        tail.set("type", "triangle")
        tail.set("w", "sm")
        tail.set("len", "sm")
    return c


def span(slide, rows, x, w, margin, label, colour, fill=None):
    """A box across both columns around rows (first, last), with its label rotated at the left edge."""
    y1, y2 = row_y(rows[0]) - margin, row_y(rows[1]) + ROW_H + margin
    r = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Mm(x), Mm(y1), Mm(w), Mm(y2 - y1))
    r.adjustments[0] = 0.03
    if fill is None:
        r.fill.background()
    else:
        r.fill.solid()
        r.fill.fore_color.rgb = fill
    r.line.color.rgb = colour
    r.line.width = Pt(0.75)
    r.line.dash_style = 7  # long dash
    r.shadow.inherit = False
    h = y2 - y1
    tb = text_box(slide, x + 2.6 - h / 2, (y1 + y2) / 2 - 2, h, 4, [[(label, 6.5, colour, True)]],
                  align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    tb.rotation = 270


def build(out):
    prs = Presentation()
    prs.slide_width, prs.slide_height = Mm(WIDTH), Mm(HEIGHT)
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    span(slide, WINDOW, 3, SPAN_RIGHT - 3, 0.9, "for each time window", PERSON)
    span(slide, LOOP, 9, SPAN_RIGHT - 9, 0.5, "repeat until good enough", INK, RGBColor(0xF4, 0xF4, 0xF4))
    for x, w, title, colour in ((STEP_X, STEP_W, "Step", INK), (LEFT_X, LEFT_W, "Human (Mastodon/ELEPHANT operator)", PERSON),
                                (RIGHT_X, RIGHT_W, "Automahout (AI agent)", AGENT), (PHASE_X, PHASE_W, "Phase", INK)):
        text_box(slide, x, 3, w, 6, [[(title, 7.5, colour, True)]])
    for i, (step, person, agent) in enumerate(STEPS):
        y = row_y(i)
        text_box(slide, STEP_X, y, STEP_W, ROW_H, [[(f"{i + 1}  {step}", 6.5, INK, True)]], anchor=MSO_ANCHOR.MIDDLE)
        text_box(slide, LEFT_X, y, LEFT_W, ROW_H, [[(person, 6.5, PERSON, False)]], anchor=MSO_ANCHOR.MIDDLE)
        text_box(slide, RIGHT_X, y, RIGHT_W, ROW_H, [[(agent, 6.5, AGENT, False)]], anchor=MSO_ANCHOR.MIDDLE)
        if i in PHASES:
            tag = box(slide, PHASE_X, y + 1.2, PHASE_W, ROW_H - 2.4, PHASE_FILL[PHASES[i]], PHASE_FILL[PHASES[i]],
                      [[(PHASES[i], 6.5, INK, False)]])
            tag.text_frame.paragraphs[0].alignment = PP_ALIGN.CENTER
            tag.text_frame.margin_left = tag.text_frame.margin_right = Mm(0.2)
            style = tag._element.find(qn("p:style"))  # LibreOffice draws the theme's shadow despite shadow.inherit
            if style is not None:
                tag._element.remove(style)
    prs.save(out)


def soffice():
    """LibreOffice's command line: $SOFFICE, then the PATH, then the macOS application."""
    for c in (os.environ.get("SOFFICE"), shutil.which("soffice"),
              "/Applications/LibreOffice.app/Contents/MacOS/soffice"):
        if c and Path(c).exists():
            return c
    return None


def render_png(pptx, dpi=400):
    """The slide as a PNG beside the pptx, at the slide's own size in mm."""
    exe = soffice()
    if exe is None:
        print("LibreOffice not found (set SOFFICE): PNG skipped")
        return None
    pptx = Path(pptx).resolve()
    size = {"PixelWidth": {"type": "long", "value": str(round(WIDTH / 25.4 * dpi))},
            "PixelHeight": {"type": "long", "value": str(round(HEIGHT / 25.4 * dpi))}}
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run([exe, "--headless", "--convert-to", "png:impress_png_Export:" + json.dumps(size),
                        "--outdir", tmp, str(pptx)], check=True, capture_output=True)
        png = pptx.with_suffix(".png")
        shutil.move(str(Path(tmp) / png.name), png)
    return png


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default="fig1a.pptx")
    ap.add_argument("--no-png", action="store_true", help="write the pptx only")
    a = ap.parse_args()
    build(a.out)
    print(a.out)
    if not a.no_png:
        png = render_png(a.out)
        if png:
            print(png)
