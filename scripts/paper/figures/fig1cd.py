"""Figures 1c and 1d from the per-window CSV (fig1_windows.py, fig1_curated_size.py), as editable PowerPoint charts.

1c: the cell radius the review measured per time window, beside the curated (silver-truth) radius.
1d: the training rounds each window's review needed: Go after n rounds, or passed before any training.
1e: cells per frame, the ground truth (TRA markers) beside Automahout's final result (the run's frames.csv).
The ground truth of 1c is the challenge's silver truth (01_ST), the only segmentation of every frame.
Each panel is one slide of 85 x 60 mm (single column), written with a PNG of it beside (400 dpi).

    uv run python figures/fig1cd.py <windows.csv> <out dir> [--frames frames.csv] [--no-png]
"""

import argparse
import csv
from pathlib import Path

from pptx import Presentation
from pptx.chart.data import XyChartData
from pptx.dml.color import RGBColor
from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION, XL_MARKER_STYLE
from pptx.util import Mm, Pt

from palette import GROUND_TRUTH_TREE  # noqa: E402
from render import render_png

W, H = 56, 44  # mm: three across Fig. 1's 174 mm (figures/fig1_compose.py), at the type size of the other panels
FONT = "Arial"
AGENT = RGBColor(0x00, 0x72, 0xB2)  # Automahout (Okabe-Ito blue)
REFERENCE = RGBColor(0x6E, 0x6E, 0x6E)  # curated reference (grey)
LIGHT = RGBColor(0x56, 0xB4, 0xE9)  # Okabe-Ito sky blue


def style_axis(axis, title, lo=None, hi=None, major=None):
    axis.has_title = True
    axis.axis_title.text_frame.text = title
    run = axis.axis_title.text_frame.paragraphs[0].runs[0]
    run.font.size, run.font.name, run.font.bold = Pt(7), FONT, False
    axis.tick_labels.font.size, axis.tick_labels.font.name = Pt(6), FONT
    axis.has_major_gridlines = False
    if lo is not None:
        axis.minimum_scale = lo
    if hi is not None:
        axis.maximum_scale = hi
    if major is not None:
        axis.major_unit = major
    axis.format.line.color.rgb = REFERENCE


def series_style(s, colour, marker, line):
    s.marker.style = marker
    s.marker.size = 5
    s.marker.format.fill.solid()
    s.marker.format.fill.fore_color.rgb = colour
    s.marker.format.line.color.rgb = colour
    if line:
        s.format.line.color.rgb = colour
        s.format.line.width = Pt(1.25)
    else:
        s.format.line.fill.background()


def slide_with_chart(data, kind, top=1.0):
    prs = Presentation()
    prs.slide_width, prs.slide_height = Mm(W), Mm(H)
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    frame = slide.shapes.add_chart(kind, Mm(1), Mm(top), Mm(W - 2), Mm(H - top - 1), data)
    chart = frame.chart
    chart.font.size, chart.font.name = Pt(6), FONT
    chart.has_legend = True
    chart.legend.position = XL_LEGEND_POSITION.TOP
    chart.legend.include_in_layout = False
    chart.legend.font.size = Pt(6)
    return prs, chart


def fig1c(rows, out):
    data = XyChartData()
    measured = data.add_series("measured by Automahout")
    curated = data.add_series("ground truth")
    for r in rows:
        mid = (int(r["start"]) + int(r["end"])) / 2
        measured.add_data_point(mid, float(r["radius_um"]))
        if r.get("curated_radius_um"):
            curated.add_data_point(mid, float(r["curated_radius_um"]))
    prs, chart = slide_with_chart(data, XL_CHART_TYPE.XY_SCATTER_LINES)
    series_style(chart.series[0], AGENT, XL_MARKER_STYLE.CIRCLE, True)
    series_style(chart.series[1], GROUND_TRUTH_TREE, XL_MARKER_STYLE.SQUARE, True)
    style_axis(chart.category_axis, "time point (window centre)", 0, 200, 50)
    style_axis(chart.value_axis, "cell radius (µm)", 0, 5, 1)
    prs.save(out)


OUTCOMES = {  # outcome in the CSV -> label, colour, marker
    "go": ("Go after training", AGENT, XL_MARKER_STYLE.DIAMOND),
    "passed before training": ("passed before training", LIGHT, XL_MARKER_STYLE.TRIANGLE),
    "best round kept": ("best round kept", REFERENCE, XL_MARKER_STYLE.X),
}


def fig1e(frames, out):
    """Cells per frame: the ground truth (CTC TRA markers) and Automahout's final result, frame by frame."""
    data = XyChartData()
    res = data.add_series("Automahout")
    gt = data.add_series("ground truth")
    top = 0
    for r in frames:
        if r.get("gt") in (None, ""):
            continue
        t = int(r["t"])
        gt.add_data_point(t, int(r["gt"]))
        res.add_data_point(t, int(r["res"]))
        top = max(top, int(r["gt"]), int(r["res"]))
    prs, chart = slide_with_chart(data, XL_CHART_TYPE.XY_SCATTER_LINES_NO_MARKERS)
    # The ground truth wide and underneath, Automahout narrow on top, so where they agree both stay visible.
    for s, colour, width in zip(chart.series, (AGENT, GROUND_TRUTH_TREE), (1.0, 2.5)):
        s.format.line.color.rgb = colour
        s.format.line.width = Pt(width)
        s.smooth = False
    style_axis(chart.category_axis, "time point", 0, 200, 50)
    style_axis(chart.value_axis, "cells per frame", 0, 100 * (top // 100 + 1), 100)
    prs.save(out)


def fig1d(rows, out):
    # One series, each point coloured by its outcome: LibreOffice drops the first of several XY series when it has
    # fewer points than the next, so the PNG would not show what PowerPoint shows. The legend is drawn as text.
    data = XyChartData()
    s = data.add_series("windows")
    for r in rows:
        s.add_data_point((int(r["start"]) + int(r["end"])) / 2, int(r["rounds"]))
    prs, chart = slide_with_chart(data, XL_CHART_TYPE.XY_SCATTER, top=5.0)  # room above it for the legend
    chart.has_legend = False
    series = chart.series[0]
    series.format.line.fill.background()
    for i, r in enumerate(rows):
        _, colour, marker = OUTCOMES[r["outcome"]]
        m = series.points[i].marker
        m.style, m.size = marker, 5
        m.format.fill.solid()
        m.format.fill.fore_color.rgb = colour
        m.format.line.color.rgb = colour
    top = max(int(r["rounds"]) for r in rows)
    style_axis(chart.category_axis, "time point (window centre)", 0, 200, 50)
    style_axis(chart.value_axis, "training rounds in the review", 0, max(6, top + 1), 1 if top < 10 else 5)
    slide = prs.slides[0]
    present = [k for k in OUTCOMES if any(r["outcome"] == k for r in rows)]
    x, step = 8.0, (W - 10) / max(1, len(present))  # the legend items spread over the chart's width
    for k in present:
        label, colour, marker = OUTCOMES[k]
        symbol = {XL_MARKER_STYLE.DIAMOND: "\u25c6", XL_MARKER_STYLE.TRIANGLE: "\u25b2", XL_MARKER_STYLE.X: "\u2715"}[marker]
        tb = slide.shapes.add_textbox(Mm(x), Mm(0.8), Mm(step), Mm(4))
        tf = tb.text_frame
        tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
        p = tf.paragraphs[0]
        for text, col in ((symbol + " ", colour), (label, RGBColor(0x26, 0x26, 0x26))):
            run = p.add_run()
            run.text = text
            run.font.size, run.font.name = Pt(6), FONT
            run.font.color.rgb = col
        x += step
    prs.save(out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("windows_csv")
    ap.add_argument("out_dir")
    ap.add_argument("--frames", help="the run's frames.csv (t, gt, res per frame), for Fig. 1e")
    ap.add_argument("--no-png", action="store_true")
    a = ap.parse_args()
    with open(a.windows_csv, newline="") as f:
        rows = list(csv.DictReader(f))
    out = Path(a.out_dir)
    jobs = [("fig1c.pptx", fig1c, rows), ("fig1d.pptx", fig1d, rows)]
    if a.frames:
        with open(a.frames, newline="") as f:
            jobs.append(("fig1e.pptx", fig1e, list(csv.DictReader(f))))
    for name, make, data in jobs:
        make(data, out / name)
        print(out / name)
        if not a.no_png:
            print(render_png(out / name, W, H))
