"""Figure 2: autonomous tracking on three datasets (training sequence 01 of each).

2a: one frame per dataset, the final result checked against the ground truth. A result label covering exactly one
    ground-truth marker is a match, one covering several a merge, one covering none a false detection; a ground-truth cell
    no label covers is missed. The ground-truth cells are drawn as solid green circles. On a 3D stack the frame is a
    maximum-intensity projection of a z slab, with the objects centred within one cell radius of it.
(The scores and the operations each dataset needed are in Table 2, figures/table1.py.)
Under each 2a image: one ground-truth lineage from t = 0 (the one with about `leaves` cells at its ends), and beside it the
Automahout tracks that hold its cells, laid out on the ground-truth tree (CTC man_track.txt, res_track.txt and masks).
Each panel is an editable PowerPoint slide with a PNG of it beside (400 dpi).

    uv run python figures/fig2.py --config fig2.json --out-dir <dir> [--data DIR] [--runs DIR] [--no-png]

The config lists the datasets: name, image, res (CTC RES mask of that frame), truth (CTC TRA marker image),
voxel [x, y, z] µm, t, metrics (metrics.json), detector, detector_label, short (a short name for the table), and
for a stack z and dz (µm). image and truth are relative to --data, the Cell Tracking Challenge datasets ($BENCH_DATA,
else ~/Repositories/CTC_dataset); res to --runs, the benchmark runs ($BENCH_OUT, else ~/automahout-bench-runs);
metrics to the config's folder. An absolute path is used as it is.
"""

import argparse
import io
import json
import os
from pathlib import Path

import numpy as np
import tifffile
from PIL import Image
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from pptx.util import Mm, Pt
from scipy import ndimage

from render import render_png

FONT = "Arial"
INK = RGBColor(0x26, 0x26, 0x26)
GREY = RGBColor(0x6E, 0x6E, 0x6E)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
DARK = RGBColor(0x26, 0x26, 0x26)
AGENT = RGBColor(0x00, 0x72, 0xB2)
LIGHT = RGBColor(0x56, 0xB4, 0xE9)
from palette import (FALSE, FOUND as MATCH, GROUND_TRUTH as TRUTH, GROUND_TRUTH_TREE, MERGED as MERGE,  # noqa: E402
                     MISSED, THIN)


def text(slide, x, y, w, h, runs, size=6.5, align=PP_ALIGN.LEFT):
    tb = slide.shapes.add_textbox(Mm(x), Mm(y), Mm(w), Mm(h))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    p = tf.paragraphs[0]
    p.alignment = align
    for t, colour, bold in runs:
        r = p.add_run()
        r.text, r.font.size, r.font.name, r.font.bold = t, Pt(size), FONT, bold
        r.font.color.rgb = colour


def circle(slide, x0, y0, scale, c, r, colour, width=0.75, dash=False, box=None):
    x, y, d = x0 + c[0] * scale, y0 + c[1] * scale, max(2 * r * scale, 0.6)
    if box is not None:  # keep the circle inside the image panel (x0, y0, w, h)
        bx, by, bw, bh = box
        d = min(d, bw, bh)
        x = min(max(x, bx + d / 2), bx + bw - d / 2)
        y = min(max(y, by + d / 2), by + bh - d / 2)
    s = slide.shapes.add_shape(MSO_SHAPE.OVAL, Mm(x - d / 2), Mm(y - d / 2), Mm(d), Mm(d))
    s.fill.background()
    s.line.color.rgb = colour
    s.line.width = Pt(width)
    if dash:
        s.line.dash_style = 4
    s.shadow.inherit = False


def dark_strip(slide, x, y, w, h):
    s = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Mm(x), Mm(y), Mm(w), Mm(h))
    s.fill.solid()
    s.fill.fore_color.rgb = DARK
    s.line.fill.background()
    s.shadow.inherit = False


def check(d):
    """The result at one frame against the ground-truth markers: matched, merged and false labels; missed markers."""
    res = tifffile.imread(d["res"])
    tra = tifffile.imread(d["truth"])
    vox = d["voxel"]
    stack = res.ndim == 3

    def world(c):  # array index (z, y, x) or (y, x) -> µm (x, y, z)
        return [c[-1] * vox[0], c[-2] * vox[1], (c[0] * vox[2]) if stack else 0.0]

    markers = [m for m in np.unique(tra) if m]
    mcent = ndimage.center_of_mass(tra > 0, tra, markers)
    hit = {}
    missed = []
    for c in mcent:
        idx = tuple(int(round(v)) for v in c)
        lab = int(res[idx])
        if lab:
            hit[lab] = hit.get(lab, 0) + 1
        else:
            missed.append(world(c))
    labels = [l for l in np.unique(res) if l]
    lcent = ndimage.center_of_mass(res > 0, res, labels)
    counts = np.bincount(res.ravel())
    unit = vox[0] * vox[1] * (vox[2] if stack else 1)
    objs = []
    for l, c in zip(labels, lcent):
        v = counts[l] * unit
        r = (3 * v / (4 * np.pi)) ** (1 / 3) if stack else (v / np.pi) ** 0.5
        n = hit.get(int(l), 0)
        objs.append(dict(pos=world(c), r=r, kind="match" if n == 1 else "merge" if n > 1 else "false"))
    truth = [world(c) for c in mcent]
    return objs, missed, truth


def image(d):
    img = tifffile.imread(d["image"]).astype(np.float32)
    if img.ndim == 3:
        # The planes that lie in the slab, as the objects drawn are those centred in it.
        z0 = max(0, int(np.ceil((d["z"] - d["dz"]) / d["voxel"][2])))
        z1 = int(np.floor((d["z"] + d["dz"]) / d["voxel"][2]))
        img = img[z0:z1 + 1].max(axis=0)
    lo, hi = np.percentile(img, [1, 99.8])
    return np.clip((img - lo) / (hi - lo), 0, 1), (float(lo), float(hi))


def read_tracks(path):
    """CTC track file: label -> (begin, end, parent)."""
    tracks = {}
    for line in Path(path).read_text().split("\n"):
        if line.strip():
            label, b, e, parent = map(int, line.split())
            tracks[label] = (b, e, parent)
    return tracks


def children_of(tracks):
    ch = {}
    for label, (_, _, parent) in tracks.items():
        ch.setdefault(parent, []).append(label)
    for v in ch.values():
        v.sort(key=lambda l: tracks[l][0])
    return ch


def n_leaves(label, ch):
    return 1 if not ch.get(label) else sum(n_leaves(c, ch) for c in ch[label])


def lineages(d):
    """One ground-truth lineage from t = 0 (the one whose number of leaves is nearest d['leaves']), and for every result
    track that holds one of its cells, the ground-truth track it holds most often (frame by frame, the result label under
    the ground-truth marker's centre)."""
    gt_dir, res_dir = Path(d["truth"]).parent, Path(d["res"]).parent
    gt, res = read_tracks(gt_dir / "man_track.txt"), read_tracks(res_dir / "res_track.txt")
    gch = children_of(gt)
    roots = [l for l, (b, _, p) in gt.items() if p == 0 and b == 0]
    root = min(roots, key=lambda l: (abs(n_leaves(l, gch) - d.get("leaves", 10)), l))
    members, stack = [], [root]
    while stack:
        l = stack.pop()
        members.append(l)
        stack.extend(gch.get(l, []))
    t_end = max(gt[l][1] for l in members)
    overlap, held = {}, {}  # held: (t, result track) -> the ground-truth track whose marker it holds at t
    for t in range(0, t_end + 1):
        alive = [l for l in members if gt[l][0] <= t <= gt[l][1]]
        if not alive:
            continue
        tra = tifffile.imread(gt_dir / f"man_track{t:03d}.tif")
        mask = tifffile.imread(res_dir / f"mask{t:03d}.tif")
        cs = ndimage.center_of_mass(tra > 0, tra, alive)
        for l, c in zip(alive, cs):
            if any(np.isnan(c)):
                continue
            r = int(mask[tuple(int(round(v)) for v in c)])
            if r:
                overlap.setdefault(r, {}).setdefault(l, 0)
                overlap[r][l] += 1
                held[(t, r)] = l
    assigned = {r: max(o, key=o.get) for r, o in overlap.items() if r in res}
    return (gt, gch, root), (res, assigned, held)


def draw_mapped(slide, res, assigned, held, xs, y, h, t_max, colour, root_begin, gch, wrong, unjudged_colour):
    """The result tracks holding cells of the ground-truth lineage, at the x of the ground-truth track each holds most: a
    vertical line where it followed the cell; a horizontal line for a division it linked, in `wrong` where the daughter's
    first cell does not follow the mother's last one in the ground truth (a sister counts: a division found late), in
    `unjudged_colour` where either holds no cell of the lineage there, so the ground truth cannot judge it; an open circle where it starts without a parent (a
    break), filled in `wrong` where its parent holds no cell of this lineage (linked to a cell from elsewhere).
    Returns (tracks, breaks, divisions linked right, division links wrong, links from outside, links unjudged)."""
    ty = lambda t: y + h * t / t_max  # noqa: E731

    def circle(x, t, fill, line):
        o = slide.shapes.add_shape(MSO_SHAPE.OVAL, Mm(x - 0.6), Mm(ty(t) - 0.6), Mm(1.2), Mm(1.2))
        o.fill.solid()
        o.fill.fore_color.rgb = fill
        o.line.color.rgb, o.line.width = line, Pt(0.5)
        o.shadow.inherit = False

    breaks = outside = unjudged = 0
    right, wrong_links = set(), 0
    for r, g in assigned.items():
        b, e, parent = res[r]
        x = xs[g]
        c = slide.shapes.add_connector(1, Mm(x), Mm(ty(b)), Mm(x), Mm(ty(e + 1)))
        c.line.color.rgb, c.line.width = colour, Pt(0.6)
        if parent in assigned:
            pg = assigned[parent]
            px = xs[pg]
            if abs(px - x) > 1e-6:
                # judged where it happened: the daughter's first cell follows the mother's last one in the ground
                # truth -- the same cell, a daughter of it, or its sister (the division found a few frames late);
                # unjudged (drawn right) when either holds no marker there
                pm, dm = held.get((res[parent][1], parent)), held.get((b, r))
                judged = pm is not None and dm is not None
                ok = judged and (dm == pm or dm in gch.get(pm, []) or any(pm in kids and dm in kids for kids in gch.values()))
                c2 = slide.shapes.add_connector(1, Mm(min(px, x)), Mm(ty(b)), Mm(max(px, x)), Mm(ty(b)))
                c2.line.color.rgb, c2.line.width = (colour if ok else wrong if judged else unjudged_colour), Pt(0.6)
                if ok:
                    right.add(parent)
                elif judged:
                    wrong_links += 1
                else:
                    unjudged += 1
        elif b > root_begin:
            if parent:  # linked, but to a cell outside this lineage
                circle(x, b, wrong, wrong)
                outside += 1
            else:
                circle(x, b, WHITE, colour)
                breaks += 1
    return len(assigned), breaks, len(right), wrong_links, outside, unjudged


def draw_tree(slide, tracks, ch, root, x, y, w, h, t_max, colour):
    """A lineage tree, time downwards: a vertical line per track, a horizontal one joining a division's daughters."""
    order = []

    def leaves(l):
        if not ch.get(l):
            order.append(l)
        for c in ch.get(l, []):
            leaves(c)

    leaves(root)
    step = w / max(len(order), 1)
    xs = {l: x + (i + 0.5) * step for i, l in enumerate(order)}

    def place(l):
        if l not in xs:
            xs[l] = sum(place(c) for c in ch[l]) / len(ch[l])
        return xs[l]

    place(root)
    ty = lambda t: y + h * t / t_max  # noqa: E731

    def seg(x1, y1, x2, y2):
        c = slide.shapes.add_connector(1, Mm(x1), Mm(y1), Mm(x2), Mm(y2))
        c.line.color.rgb = colour
        c.line.width = Pt(0.6)

    def walk(l):
        b, e, _ = tracks[l]
        seg(xs[l], ty(b), xs[l], ty(e + 1))
        kids = ch.get(l, [])
        if kids:
            seg(min(xs[k] for k in kids), ty(e + 1), max(xs[k] for k in kids), ty(e + 1))
            for k in kids:
                walk(k)

    walk(root)
    return len(order), xs


def fig2a(datasets, out):
    W, GAP, TOP = 174, 2.5, 7
    n = len(datasets)
    pw = (W - 2 - (n - 1) * GAP) / n
    prepared = []
    for d in datasets:
        img, rng = image(d)
        objs, missed, truth = check(d)
        h_px, w_px = img.shape
        scale = pw / (w_px * d["voxel"][0])
        prepared.append((d, img, rng, objs, missed, truth, scale, h_px * d["voxel"][1] * scale))
    ph = max(p[7] for p in prepared)
    TREE_H = 42
    KEY_TOP = TOP + ph + 1.2  # the detection key, right under the images it explains
    tree_top = KEY_TOP + 4.2 + 7
    H = tree_top + TREE_H + 9.5  # the trees, their time label and the trees' key; the caption is the manuscript's legend
    prs = Presentation()
    prs.slide_width, prs.slide_height = Mm(W), Mm(H)
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    notes = []
    for k, (d, img, rng, objs, missed, truth, scale, h) in enumerate(prepared):
        x0, y0 = 1 + k * (pw + GAP), TOP
        buf = io.BytesIO()
        Image.fromarray((img * 255).astype(np.uint8)).save(buf, format="PNG")
        buf.seek(0)
        slide.shapes.add_picture(buf, Mm(x0), Mm(y0), Mm(pw), Mm(h))
        text(slide, x0, 1.5, pw, 5, [(d["name"], INK, True)], size=7)
        box = (x0, y0, pw, h)
        stack = "z" in d
        cell_r = float(np.median([o["r"] for o in objs]))
        # Objects that reach into the slab: centre within one cell radius of it, as such a cell shows in the projection.
        in_slab = (lambda c: abs(c[2] - d["z"]) <= d["dz"] + cell_r) if stack else (lambda c: True)
        for c in filter(in_slab, truth):
            circle(slide, x0, y0, scale, c, 0.6 * np.median([o["r"] for o in objs]), TRUTH, width=THIN, box=box)
        colour = dict(match=MATCH, merge=MERGE, false=FALSE)
        for o in objs:
            if in_slab(o["pos"]):
                circle(slide, x0, y0, scale, o["pos"], o["r"], colour[o["kind"]], width=THIN,
                       box=box)
        for c in filter(in_slab, missed):
            circle(slide, x0, y0, scale, c, np.median([o["r"] for o in objs]), MISSED, width=THIN, box=box)
        bar_um = 10 if stack else (50 if d["voxel"][0] < 1 else 100)
        bar = bar_um * scale
        sb = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Mm(x0 + pw - bar - 2), Mm(y0 + h - 2.5), Mm(bar), Mm(0.7))
        sb.fill.solid()
        sb.fill.fore_color.rgb = WHITE
        sb.line.fill.background()
        sb.shadow.inherit = False
        text(slide, x0 + 1.5, y0 + 1.2, 25, 4, [(f"t = {d['t']}", WHITE, False)], size=6)
        kinds = {k2: sum(1 for o in objs if o["kind"] == k2) for k2 in ("match", "merge", "false")}
        notes.append(f"{d['name']}: t = {d['t']}" + (f", z = {d['z'] - d['dz']:g}–{d['z'] + d['dz']:g} µm (objects centred within {cell_r:.1f} µm of it)" if stack else "")
                     + f", display {rng[0]:.0f}–{rng[1]:.0f}, scale bar {bar_um} µm; {len(truth)} ground truth, "
                     f"{kinds['match']} matched, {kinds['merge']} merged, {kinds['false']} false, {len(missed)} missed")
    tree_notes = []
    for k, (d, *_rest) in enumerate(prepared):
        x0 = 1 + k * (pw + GAP)
        (gt, gch, groot), (res, assigned, held) = lineages(d)
        t_max = max(e for _, e, _ in gt.values()) + 1
        half = (pw - 3) / 2
        text(slide, x0, tree_top - 4.5, pw, 4, [("Lineage: ground truth ", GROUND_TRUTH_TREE, True), ("| Automahout", AGENT, True)], size=6)
        ng, xs = draw_tree(slide, gt, gch, groot, x0, tree_top, half, TREE_H, t_max, GROUND_TRUTH_TREE)
        shifted = {g: v + half + 3 for g, v in xs.items()}
        nr, nb, nl, nw, no, nu = draw_mapped(slide, res, assigned, held, shifted, tree_top, TREE_H, t_max, AGENT,
                                             gt[groot][0], gch, FALSE, GREY)
        text(slide, x0, tree_top + TREE_H + 0.5, pw, 3, [(f"t = 0 (top) to {t_max - 1}", GREY, False)], size=5.5)
        ndiv = sum(1 for l in xs if gch.get(l))
        tree_notes.append(f"{d['name']}: ground-truth lineage of track {groot} ({ng} cells at its ends, {ndiv} divisions); "
                          f"{nr} Automahout tracks hold its cells, with {nl} divisions linked right, {nw} division "
                          f"links wrong, {nu} division links not judged, {no} links from a cell outside the lineage "
                          f"and {nb} breaks")
    # The detection key under the images, on a dark strip so its colours read as they do on the images ...
    dark_strip(slide, 1, KEY_TOP, W - 2, 4.2)
    text(slide, 2, KEY_TOP + 0.8, W - 4, 4, [("○ ground truth  ", TRUTH, False), ("● matched  ", MATCH, False),
                                          ("● merged (two or more cells)  ", MERGE, False),
                                          ("● false detection  ", FALSE, False), ("● missed cell", MISSED, False)], size=6)
    # ... and the trees' own key under the trees, on white as they are.
    text(slide, 1, tree_top + TREE_H + 4.5, W - 2, 4,
         [("│ ground-truth lineage   ", GROUND_TRUTH_TREE, False), ("│ Automahout track   ", AGENT, False),
          ("— division linked   ", AGENT, False), ("— wrong division link   ", FALSE, False),
          ("— not judged   ", GREY, False), ("○ break   ", AGENT, False),
          ("● linked to a cell outside the lineage", FALSE, False)], size=6)
    # The numbers the legend cites, printed rather than drawn: the figure's caption is the manuscript's legend.
    print("Frames: " + "; ".join(notes) + ".")
    print("Lineages: " + "; ".join(tree_notes) + ".")
    prs.save(out)
    return W, H


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--config", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--data", default=os.environ.get("BENCH_DATA", "~/Repositories/CTC_dataset"),
                    help="the Cell Tracking Challenge datasets, which image and truth are relative to")
    ap.add_argument("--runs", default=os.environ.get("BENCH_OUT", "~/automahout-bench-runs"),
                    help="the benchmark runs, which res is relative to")
    ap.add_argument("--no-png", action="store_true")
    a = ap.parse_args()
    cfg = json.loads(Path(a.config).read_text())
    roots = {"image": Path(a.data).expanduser(), "truth": Path(a.data).expanduser(),
             "res": Path(a.runs).expanduser(), "metrics": Path(a.config).resolve().parent}
    for d in cfg["datasets"]:
        for key, root in roots.items():
            if key in d:
                d[key] = str(root / d[key])  # an absolute d[key] replaces root
    out = Path(a.out_dir)
    for name, make in (("fig2a.pptx", fig2a),):
        w, h = make(cfg["datasets"], out / name)
        print(out / name)
        if not a.no_png:
            print(render_png(out / name, w, h))
