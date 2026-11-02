"""Supplementary figures S1-S3, each an editable PowerPoint slide with a PNG of it beside (400 dpi).

S1: the VLM check -- what the vision-language model was shown for a doubtful reference (the plane through it and a
    projection over the planes around it, with the cell circle) and what it answered, beside the ground truth's
    verdict (a ground-truth cell within one radius). From a PassReplay nucleus= run with -Dautomahout.vlm.dump.
S2: missed, duplicate, background and merged detections per frame on the embryo (the run's frames.csv).
S3: the log of one time window, the lines a person would read: what it measured, decided and corrected.

    uv run python figures/figsupp.py --vlm-dir DIR --truth-dir CTC/Fluo-N3DH-CE/01_GT/TRA --voxel 0.09,0.09,1.0 \
        --radius 3.81 --frames frames.csv --log run.log --window 80 --out-dir DIR [--no-png]
"""

import argparse
import csv
import json
import re
from pathlib import Path

import numpy as np
import tifffile
from pptx import Presentation
from pptx.chart.data import XyChartData
from pptx.dml.color import RGBColor
from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from pptx.util import Mm, Pt
from scipy import ndimage

from palette import FALSE, GROUND_TRUTH_TREE, MERGED, MISSED
from render import render_png

FONT, MONO = "Arial", "Menlo"
INK = RGBColor(0x26, 0x26, 0x26)
GREY = RGBColor(0x6E, 0x6E, 0x6E)
AGENT = RGBColor(0x00, 0x72, 0xB2)
DUPLICATE = RGBColor(0x00, 0x9E, 0x73)  # Okabe-Ito bluish green


def text(slide, x, y, w, h, runs, size=6.5, font=FONT, align=PP_ALIGN.LEFT, spacing=None):
    tb = slide.shapes.add_textbox(Mm(x), Mm(y), Mm(w), Mm(h))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    first = True
    for para in runs if isinstance(runs[0], list) else [runs]:
        p = tf.paragraphs[0] if first else tf.add_paragraph()
        first = False
        p.alignment = align
        if spacing:
            p.space_after = Pt(spacing)
        for t, colour, bold in para:
            r = p.add_run()
            r.text, r.font.size, r.font.name, r.font.bold = t, Pt(size), font, bold
            r.font.color.rgb = colour
    return tb


def slide(w, h):
    prs = Presentation()
    prs.slide_width, prs.slide_height = Mm(w), Mm(h)
    return prs, prs.slides.add_slide(prs.slide_layouts[6])


def scale_bar(s, x0, y0, size_mm, width_um, bar_um=10):
    """A white bar of bar_um at the bottom right of an image size_mm wide that spans width_um."""
    bar = bar_um * size_mm / width_um
    r = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Mm(x0 + size_mm - bar - 1.5), Mm(y0 + size_mm - 2), Mm(bar), Mm(0.6))
    r.fill.solid()
    r.fill.fore_color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
    r.line.fill.background()
    r.shadow.inherit = False


def figS1(a, out):
    """The VLM's view and answer for each dumped spot, with the ground truth's verdict."""
    vox = [float(v) for v in a.voxel.split(",")]
    spots = sorted(Path(a.vlm_dir).glob("t*.txt"), key=lambda p: (int(p.stem.split("_")[0][1:]), p.stem))
    W, row_h, img = 174, 34, 30
    # The crop the review shows: 2 x round(4 radii / voxel) + 1 voxels across (VisualColdStart.spotPanel).
    crop_um = (2 * round(4 * a.radius / vox[0]) + 1) * vox[0]
    prs, s = slide(W, 8 + row_h * len(spots) + 1)
    text(s, 1, 1, W - 2, 5, [("What the vision-language model was shown, and what it answered", INK, True)], size=7)
    for i, p in enumerate(spots):
        t = int(p.stem.split("_")[0][1:])
        pos = [float(v) for v in p.stem.split("_")[1:4]]
        m = re.search(r"\{.*\}", p.read_text(), re.S)
        answer = json.loads(m.group()) if m else {"nucleus": None, "reasoning": p.read_text().strip()}
        tra = tifffile.imread(Path(a.truth_dir) / f"man_track{t:03d}.tif")
        labels = [l for l in np.unique(tra) if l]
        cs = ndimage.center_of_mass(tra > 0, tra, labels)
        d = min(np.linalg.norm(np.array([c[2] * vox[0], c[1] * vox[1], c[0] * vox[2]]) - pos) for c in cs)
        truth = d <= a.radius
        y = 8 + i * row_h
        for k in (1, 2):
            png = p.with_name(f"{p.stem}_{k}.png")
            if png.exists():
                x0 = 1 + (k - 1) * (img + 2)
                s.shapes.add_picture(str(png), Mm(x0), Mm(y), Mm(img), Mm(img))
                scale_bar(s, x0, y, img, crop_um)
        said = answer.get("nucleus")
        right = said == truth
        x = 2 * (img + 2) + 2
        text(s, x, y, W - x - 1, row_h - 2, [
            [(f"t = {t}, ({pos[0]:.1f}, {pos[1]:.1f}, {pos[2]:.1f}) µm", INK, True)],
            [("Ground truth: ", GROUND_TRUTH_TREE, True),
             ((f"a nucleus ({d:.1f} µm from its centre)" if truth else f"no nucleus (nearest centre {d:.1f} µm)"),
              GROUND_TRUTH_TREE, False)],
            [("VLM: ", AGENT, True), ("a nucleus" if said else "not a nucleus" if said is False else "no answer", AGENT, False),
             (" — right" if right else " — wrong", INK if right else FALSE, True)],
            [("“" + answer.get("reasoning", "") + "”", GREY, False)],
        ], size=6, spacing=2)
    # The numbers the legend cites, printed rather than drawn: the figure's caption is the manuscript's legend.
    print(f"Supplementary Fig. 1: circle = the window's cell radius ({a.radius} um); each crop {crop_um:.1f} um across; "
          "ground truth = a CTC TRA marker centre within one radius.")
    prs.save(out)
    return W, 8 + row_h * len(spots) + 1


def figS2(a, out):
    rows = [r for r in csv.DictReader(open(a.frames)) if r.get("gt") not in (None, "")]
    W, H = 174, 70
    data = XyChartData()
    kinds = [("missed", "missed cells", MISSED), ("duplicate", "duplicate detections", DUPLICATE),
             ("background", "detections on no cell", FALSE), ("merged", "merged detections (two or more cells)", MERGED)]
    for key, label, _ in kinds:
        ser = data.add_series(label)
        for r in rows:
            ser.add_data_point(int(r["t"]), int(r[key]))
    prs, s = slide(W, H)
    chart = s.shapes.add_chart(XL_CHART_TYPE.XY_SCATTER_LINES_NO_MARKERS, Mm(1), Mm(1), Mm(W - 2), Mm(H - 2), data).chart
    chart.font.size, chart.font.name = Pt(6), FONT
    chart.has_legend = True
    chart.legend.position = XL_LEGEND_POSITION.TOP
    chart.legend.include_in_layout = False
    for ser, (_, _, colour) in zip(chart.series, kinds):
        ser.format.line.color.rgb = colour
        ser.format.line.width = Pt(1.0)
        ser.smooth = False
    for axis, title, lo, hi, major in ((chart.category_axis, "time point", 0, 200, 25),
                                       (chart.value_axis, "detections or cells per frame", 0, None, None)):
        axis.has_title = True
        axis.axis_title.text_frame.text = title
        axis.axis_title.text_frame.paragraphs[0].runs[0].font.size = Pt(7)
        axis.axis_title.text_frame.paragraphs[0].runs[0].font.bold = False
        axis.has_major_gridlines = False
        axis.minimum_scale = lo
        if hi is not None:
            axis.maximum_scale, axis.major_unit = hi, major
    prs.save(out)
    return W, H


LOG_KEEP = [r"^=== Window", r"Review: cell size", r"reference cells", r"Review, round", r"already reproduces",
            r"Review: (Go|no Go)", r"VLM", r"\[1/6\] fresh detections", r"Committed detection", r"Shape fit: SAM masks fitted",
            r"\[5c/6\] Consistency:", r"\[6/6\] Validation", r"\[6b/6\]", r"Window total"]


def figS3(a, out):
    lines, inside = [], False
    for line in open(a.log, encoding="utf-8"):
        if line.startswith("=== Window"):
            inside = re.match(rf"=== Window \[t={a.window}\.\.", line) is not None
        if inside and any(re.search(p, line) for p in LOG_KEEP):
            line = line.rstrip().strip()
            lines.append(line if len(line) <= 400 else line[:399] + "…")
    W = 174
    per_line = 128  # characters of the monospaced 5 pt font across the slide
    H = 4 + 2.05 * sum(-(-len(l) // per_line) for l in lines)  # no title: the manuscript's legend says what this is
    prs, s = slide(W, H)
    text(s, 1, 1, W - 2, H - 2, [[(l, INK, False)] for l in lines], size=5, font=MONO)
    prs.save(out)
    return W, H


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    for k in ("vlm-dir", "truth-dir", "voxel", "frames", "log", "out-dir"):
        ap.add_argument("--" + k, required=True)
    ap.add_argument("--radius", type=float, required=True)
    ap.add_argument("--window", type=int, default=80)
    ap.add_argument("--no-png", action="store_true")
    a = ap.parse_args()
    out = Path(a.out_dir)
    for folder, name, make in (("figS1", "figS1.pptx", figS1), ("figS2", "figS2.pptx", figS2), ("figS3", "figS3.pptx", figS3)):
        (out / folder).mkdir(parents=True, exist_ok=True)
        w, h = make(a, out / folder / name)
        print(out / folder / name)
        if not a.no_png:
            print(render_png(out / folder / name, w, h))
