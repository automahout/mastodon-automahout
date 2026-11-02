#!/usr/bin/env python3
"""Measures the valley between two spot positions, on ground truth, for both cases that matter.

isSameBlob decides whether two detections are fragments of ONE cell (merge) or two touching cells
(keep both). It is the only thing that can tell those apart, so before widening the geometric gate
that currently keeps it from ever being asked, its discriminating power has to be known rather than
assumed.

Two populations, both taken from CTC silver-truth segmentation, so neither depends on a detector:

  same-cell   two points inside ONE labelled cell        -> the pass MUST merge these
  cross-cell  the centroids of two DIFFERENT cells       -> the pass MUST NOT merge these

For each pair it computes the statistic isSameBlob uses (minimum along the segment, over the
intensity at the endpoints) and the same statistic measured ABOVE BACKGROUND, which is what the
detector's offset makes necessary.

    python3 measure_valley.py [--frames N]
"""
import argparse, glob, math, os, random, sys
import numpy as np
from PIL import Image

DATA = os.path.expanduser("~/Repositories/CTC_dataset/Fluo-N2DL-HeLa")
VALLEY_SCAN_SAMPLES = 5          # AutoCorrectOrchestrator.VALLEY_SCAN_SAMPLES


def valley(img, a, b, background):
    """(raw ratio, above-background ratio) for the segment a..b, as isSameBlob computes it."""
    core_a, core_b = img[int(a[1]), int(a[0])], img[int(b[1]), int(b[0])]
    ref_raw = min(core_a, core_b)
    ref_bg = max(1e-9, min(core_a, core_b) - background)
    lo_raw, lo_bg = ref_raw, ref_bg
    for k in range(1, VALLEY_SCAN_SAMPLES + 1):
        f = k / (VALLEY_SCAN_SAMPLES + 1)
        x = a[0] + f * (b[0] - a[0])
        y = a[1] + f * (b[1] - a[1])
        v = img[int(round(y)), int(round(x))]
        lo_raw = min(lo_raw, v)
        lo_bg = min(lo_bg, v - background)
    return lo_raw / ref_raw, lo_bg / ref_bg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", type=int, default=20)
    args = ap.parse_args()
    rnd = random.Random(20260925)

    masks = sorted(glob.glob(DATA + "/01_ST/SEG/*.tif"))[: args.frames]
    if not masks:
        sys.exit("no silver-truth segmentation under " + DATA + "/01_ST/SEG")

    same, cross = [], []
    for mpath in masks:
        t = int(os.path.basename(mpath)[len("man_seg"):-len(".tif")])
        raw = np.array(Image.open(DATA + "/01/t%03d.tif" % t)).astype(np.float64)
        lab = np.array(Image.open(mpath))
        background = float(np.median(raw[lab == 0]))

        cells = {}
        for cid in np.unique(lab):
            if cid == 0:
                continue
            ys, xs = np.nonzero(lab == cid)
            area = len(xs)
            if area < 20:
                continue
            cells[cid] = (xs.mean(), ys.mean(), math.sqrt(area / math.pi), xs, ys)

        # same-cell: two CORE points of one cell, separated like an over-segmented pair. Core, not
        # any interior pixel: a detection sits on the bright part of a cell, and a pair of boundary
        # pixels is not a pair this pass will ever be shown.
        for cid, (cx, cy, r, xs, ys) in cells.items():
            peak = float(raw[ys, xs].max())
            core = background + 0.5 * (peak - background)
            keep = [k for k in range(len(xs)) if raw[ys[k], xs[k]] >= core]
            if len(keep) < 2:
                continue
            for _ in range(12):
                i, j = rnd.choice(keep), rnd.choice(keep)
                a, b = (xs[i], ys[i]), (xs[j], ys[j])
                d = math.hypot(a[0] - b[0], a[1] - b[1])
                if d < 0.5 * r or d > 2.0 * r:
                    continue
                same.append((d / r,) + valley(raw, a, b, background))

        # cross-cell: centroids of two different cells the widened gate would admit
        ids = list(cells)
        for ii in range(len(ids)):
            for jj in range(ii + 1, len(ids)):
                ax, ay, ar, _, _ = cells[ids[ii]]
                bx, by, br, _, _ = cells[ids[jj]]
                d = math.hypot(ax - bx, ay - by)
                if d > (ar + br):        # the proposed gate: the circles intersect
                    continue
                cross.append((d / max(ar, br),) + valley(raw, (ax, ay), (bx, by), background))

    def report(name, rows, idx, label):
        vals = sorted(r[idx] for r in rows)
        if not vals:
            print("  %-10s none" % name)
            return vals
        q = lambda p: vals[min(len(vals) - 1, int(p * (len(vals) - 1)))]
        print("  %-10s n=%-5d  min=%.3f  p05=%.3f  p50=%.3f  p95=%.3f  max=%.3f"
              % (name, len(vals), vals[0], q(0.05), q(0.50), q(0.95), vals[-1]))
        return vals

    print("frames: %d   same-cell pairs: %d   cross-cell pairs: %d\n"
          % (len(masks), len(same), len(cross)))
    for idx, label in ((1, "RAW ratio  (min / core) — what isSameBlob measures today"),
                       (2, "ABOVE-BACKGROUND ratio ((min-bg) / (core-bg))")):
        print(label)
        s = report("same-cell", same, idx, label)
        c = report("cross-cell", cross, idx, label)
        if s and c:
            # A threshold merges when the statistic is >= it. We want same-cell above, cross-cell below.
            best, best_err = None, None
            for k in range(1, 1000):
                thr = k / 1000.0
                miss = sum(1 for v in s if v < thr)          # one cell left split
                fuse = sum(1 for v in c if v >= thr)         # two cells fused: the dangerous one
                err = fuse / len(c) + miss / len(s)
                if best_err is None or err < best_err:
                    best, best_err = thr, err
            miss = sum(1 for v in s if v < best) / len(s)
            fuse = sum(1 for v in c if v >= best) / len(c)
            print("  best threshold %.3f -> %.1f%% of one-cell pairs left split, "
                  "%.1f%% of two-cell pairs FUSED" % (best, 100 * miss, 100 * fuse))
            cur = 0.75
            miss = sum(1 for v in s if v < cur) / len(s)
            fuse = sum(1 for v in c if v >= cur) / len(c)
            print("  current  0.750 -> %.1f%% left split, %.1f%% FUSED" % (100 * miss, 100 * fuse))
        print()


if __name__ == "__main__":
    main()
