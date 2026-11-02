"""Figure 1b: the references and the prediction of one review round on the embryo, beside the ground truth.

The review's reference cells at one frame (the confident blobs, written by PassReplay refsout=), the whole-frame
prediction of the run's final ELEPHANT model (over ELEPHANT's HTTP API, which does not change the model), and the
review's verdicts by its own rules: a reference is found when a detection lies within one cell radius of it, missed
otherwise; a detection is a false hit when it lies more than two radii from every reference and more than one from
every blob (printed, not drawn). Panels on a maximum-intensity projection of the frame, written as an editable PowerPoint slide
(image + shapes) with a PNG beside it. The prediction is saved as JSON next to the references.

    uv run python figures/fig1b.py --server URL --model NAME --dataset NAME --xml BDV.xml --image t085.tif \
        --refs references.csv --t 85 --radius 2.52 --out fig1b.pptx
"""

import argparse
import csv
import io
import json
import math
from pathlib import Path

import numpy as np
import tifffile
from PIL import Image
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from pptx.util import Mm, Pt

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # scripts/paper, for train_probe

from render import render_png  # noqa: E402
from train_probe import geometry, n_keep_axials, post  # noqa: E402

FONT = "Arial"
INK = RGBColor(0x26, 0x26, 0x26)
from palette import FOUND as REF, GROUND_TRUTH as TRUTH, PREDICTION as PRED, THIN  # noqa: E402
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
GAP, TOP = 2.5, 7  # mm


def predict(server, model, dataset, xml, t, radius):
    size, vox = geometry(xml)
    body = dict(dataset_name=dataset, model_name=model, timepoint=t, p_thresh=0.5, r_min=0.5 * radius,
                r_max=2.5 * radius, c_ratio=0.4, use_2d=False, batch_size=1, debug=False, output_prediction=False,
                use_median=False, is_pad=True, use_memmap=True, cache_maxbytes=1073741824000, is_3d=True,
                input_size=size, scales=vox, n_keep_axials=n_keep_axials(vox[2] / vox[0]), patch=[384, 384, 24])
    out = post(f"{server}/seg/predict", body)
    spots = out.get("spots", []) if isinstance(out, dict) else []
    return [dict(pos=s["pos"], radius=abs(np.linalg.det(np.reshape(s["covariance"], (3, 3)))) ** (1 / 6)) for s in spots]


def review(refs, blobs, preds, r):
    """The review's verdicts (FrameReview): found and missed references, and false hits."""
    found, missed = [], []
    for c in refs:
        (found if any(math.dist(p["pos"], c) <= r for p in preds) else missed).append(c)
    false = [p for p in preds
             if all(math.dist(p["pos"], c) > 2 * r for c in refs) and all(math.dist(p["pos"], b) > r for b in blobs)]
    return found, missed, false


def truth_centres(path, vox):
    """The ground-truth cells' centres (µm) from a CTC TRA marker image (z, y, x)."""
    from scipy import ndimage
    m = tifffile.imread(path)
    labels = [l for l in np.unique(m) if l]
    cs = ndimage.center_of_mass(m > 0, m, labels)
    return [[c[2] * vox[0], c[1] * vox[1], c[0] * vox[2]] for c in cs]


def projection(path, z_range=None):
    img = tifffile.imread(path).astype(np.float32)
    if img.ndim == 3 and z_range is not None:
        img = img[z_range[0]:z_range[1] + 1]
    mip = img.max(axis=0) if img.ndim == 3 else img
    lo, hi = np.percentile(mip, [1, 99.8])
    return np.clip((mip - lo) / (hi - lo), 0, 1), (float(lo), float(hi))


def circle(slide, x0, y0, scale, c, r, colour, width=0.75, dash=False, fill=None):
    x, y, d = x0 + c[0] * scale, y0 + c[1] * scale, 2 * r * scale
    s = slide.shapes.add_shape(MSO_SHAPE.OVAL, Mm(x - d / 2), Mm(y - d / 2), Mm(d), Mm(d))
    if fill is None:
        s.fill.background()
    else:
        s.fill.solid()
        s.fill.fore_color.rgb = fill
    s.line.color.rgb = colour
    s.line.width = Pt(width)
    if dash:
        s.line.dash_style = 4
    s.shadow.inherit = False


def text(slide, x, y, w, h, runs, size=6.5, align=PP_ALIGN.LEFT):
    tb = slide.shapes.add_textbox(Mm(x), Mm(y), Mm(w), Mm(h))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    p = tf.paragraphs[0]
    p.alignment = align
    for t, colour, bold in runs:
        run = p.add_run()
        run.text, run.font.size, run.font.name, run.font.bold = t, Pt(size), FONT, bold
        run.font.color.rgb = colour


def build(args):
    rows = list(csv.DictReader(open(args.refs)))
    refs = [[float(r["x"]), float(r["y"]), float(r["z"])] for r in rows if r["kind"] == "reference" and int(r["t"]) == args.t]
    blobs = [[float(r["x"]), float(r["y"]), float(r["z"])] for r in rows if r["kind"] == "blob" and int(r["t"]) == args.t]
    pred_json = Path(args.refs).with_name(Path(args.refs).stem.replace("references", "prediction") + ".json")
    if pred_json.exists() and not args.repredict:
        preds = json.loads(pred_json.read_text())["spots"]
    else:
        preds = predict(args.server, args.model, args.dataset, args.xml, args.t, args.radius)
        pred_json.write_text(json.dumps(dict(model=args.model, t=args.t, radius_um=args.radius, spots=preds), indent=1))
    found, missed, false = review(refs, blobs, preds, args.radius)
    # The review is over the whole volume; the figure shows a slab of it, with the spots that reach into it: centre
    # within one cell radius of the slab, as a cell that close shows in the projection.
    _, vox = geometry(args.xml)
    z_range, in_slab = None, (lambda c: True)
    if args.z is not None:
        lo_um, hi_um = args.z - args.dz, args.z + args.dz
        z_range = (max(0, int(math.ceil(lo_um / vox[2]))), int(math.floor(hi_um / vox[2])))  # the planes in the slab
        in_slab = lambda c: lo_um - args.radius <= c[2] <= hi_um + args.radius  # noqa: E731
    print(f"t={args.t}: {len(refs)} references, {len(found)} found, {len(missed)} missed; "
          f"{len(preds)} predicted, {len(false)} false hits")

    img, (lo, hi) = projection(args.image, z_range)
    truth = truth_centres(args.truth, vox) if args.truth else None
    n = 3 if truth is not None else 2
    W = 174
    panel_w = (W - 2 - (n - 1) * GAP) / n
    h_px, w_px = img.shape
    scale = panel_w / (w_px * vox[0])  # mm per µm
    panel_h = h_px * vox[1] * scale
    H = TOP + panel_h + 6.5  # the panels and the colour key; the caption is the manuscript's legend
    buf = io.BytesIO()
    Image.fromarray((img * 255).astype(np.uint8)).save(buf, format="PNG")

    prs = Presentation()
    prs.slide_width, prs.slide_height = Mm(W), Mm(H)
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    # No panel of the review's verdicts: they are against the references, not the ground truth, and beside the ground
    # truth a reference missed or a hit far from every reference read as errors of the model.
    titles = ["Ground truth (for comparison)", "Reference cells (annotated)", "Prediction (whole frame)"]
    if truth is None:
        titles = titles[1:]
    for k, title in enumerate(titles):
        x0, y0 = 1 + k * (panel_w + GAP), TOP
        buf.seek(0)
        slide.shapes.add_picture(buf, Mm(x0), Mm(y0), Mm(panel_w), Mm(panel_h))
        text(slide, x0, 1.5, panel_w, 5, [(title, INK, True)], size=7)
        if title.startswith("Ground"):
            for c in filter(in_slab, truth):
                circle(slide, x0, y0, scale, c, args.radius, TRUTH, width=THIN)
        elif title.startswith("Reference"):
            for c in filter(in_slab, refs):
                circle(slide, x0, y0, scale, c, args.radius, REF)
        elif title.startswith("Prediction"):
            for p in preds:
                if in_slab(p["pos"]):
                    circle(slide, x0, y0, scale, p["pos"], p["radius"], PRED)
        # 10 µm scale bar, bottom right
        bar = 10 * scale
        sb = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Mm(x0 + panel_w - bar - 2), Mm(y0 + panel_h - 2.5), Mm(bar), Mm(0.7))
        sb.fill.solid()
        sb.fill.fore_color.rgb = WHITE
        sb.line.fill.background()
        sb.shadow.inherit = False
        text(slide, x0 + 1.5, y0 + 1.2, 25, 4, [(f"t = {args.t}", WHITE, False)], size=6)
    legend = ([("○ ground truth  ", TRUTH)] if truth is not None else []) + [("○ reference cell  ", REF),
                                                                            ("○ prediction", PRED)]
    # The legend on a dark strip, so the bright colours read as they do on the image.
    strip = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Mm(1), Mm(TOP + panel_h + 1.2), Mm(W - 2), Mm(4.2))
    strip.fill.solid()
    strip.fill.fore_color.rgb = RGBColor(0x26, 0x26, 0x26)
    strip.line.fill.background()
    strip.shadow.inherit = False
    text(slide, 2, TOP + panel_h + 2, W - 4, 4, [(t, c, False) for t, c in legend], size=6)
    # The numbers the legend cites, printed rather than drawn: the figure's caption is the manuscript's legend.
    print(f"Fluo-N3DH-CE 01, t = {args.t}; maximum-intensity projection over "
           + (f"z = {args.z - args.dz:g}–{args.z + args.dz:g} µm (spots whose centre is within one cell radius of it)" if args.z is not None else "z") + f", display range {lo:.0f}–{hi:.0f} "
           f"(1st–99.8th percentile), grayscale; scale bar 10 µm. Circles: cell radius {args.radius} µm (references) or "
           f"the prediction's own radius. Whole frame: "
           + (f"{len(truth)} ground-truth cells, " if truth is not None else "")
           + f"{len(refs)} references, {len(preds)} predicted. Drawn: "
           + (f"{sum(map(in_slab, truth))} ground truth, " if truth is not None else "")
           + f"{sum(map(in_slab, refs))} references, {sum(1 for p in preds if in_slab(p['pos']))} predicted.")
    prs.save(args.out)
    print(args.out)
    if not args.no_png:
        print(render_png(args.out, W, H))


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    for k in ("server", "model", "dataset", "xml", "image", "refs", "out"):
        ap.add_argument("--" + k, required=k not in ("server", "model", "dataset") )
    ap.add_argument("--t", type=int, required=True)
    ap.add_argument("--radius", type=float, required=True)
    ap.add_argument("--truth", help="CTC TRA marker image of the frame (man_trackTTT.tif), drawn as a first panel")
    ap.add_argument("--z", type=float, help="centre of the z slab shown (µm); the whole volume when omitted")
    ap.add_argument("--dz", type=float, default=2.0, help="half thickness of the slab (µm)")
    ap.add_argument("--repredict", action="store_true", help="predict again even if the prediction JSON exists")
    ap.add_argument("--no-png", action="store_true")
    build(ap.parse_args())
