"""Scores what ContourFitHarness produced against CTC silver truth.

RESULTS.md's 0.92 is an upper bound computed from the truth cell's own axes. This
is what the shipped code achieves from the image alone.
"""
import pathlib

import numpy as np
from PIL import Image

# Not imported from measure_extent: that module runs its whole benchmark on import
# and would bury this one's output in its own.
SEG = pathlib.Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa' / '01_ST' / 'SEG'

FITS = '/tmp/contour-fits.tsv'


def main():
    rows = []
    with open(FITS) as fh:
        head = fh.readline().rstrip('\n').split('\t')
        for line in fh:
            p = line.rstrip('\n').split('\t')
            rows.append(dict(zip(head, p)))
    by_t = {}
    for r in rows:
        by_t.setdefault(int(float(r['t'])), []).append(r)

    res = {'circle (as-is)': [], 'ellipse (fitted)': []}
    refused = {'on a cell': 0, 'on no cell': 0}
    fitted_on = {'on a cell': 0, 'on no cell': 0}
    for t, rs in sorted(by_t.items()):
        segf = SEG / f'man_seg{t:03d}.tif'
        if not segf.exists():
            continue
        seg = np.array(Image.open(segf))
        H, W = seg.shape
        yy, xx = np.mgrid[0:H, 0:W]
        area = {int(l): int((seg == l).sum()) for l in np.unique(seg) if l}
        for r in rs:
            sx, sy = float(r['seedX']), float(r['seedY'])
            sr = float(r['seedR'])
            iy, ix = int(round(sy)), int(round(sx))
            if not (0 <= iy < H and 0 <= ix < W):
                continue
            lab = int(seg[iy, ix])
            on_cell = bool(lab) and area.get(lab, 0) >= 30
            key = 'on a cell' if on_cell else 'on no cell'
            if r['a'] == '':
                refused[key] += 1
                continue
            fitted_on[key] += 1
            if not on_cell:
                continue
            m = seg == lab
            a_, b_ = float(r['a']), float(r['b'])
            th = float(r['theta'])
            fx, fy = float(r['fitX']), float(r['fitY'])
            c, s = np.cos(th), np.sin(th)

            def score(mask, name):
                n = int(mask.sum())
                if n == 0:
                    return
                inter = int((mask & m).sum())
                p, rc = inter / n, inter / area[lab]
                res[name].append(p * rc / (p + rc - p * rc) if p + rc - p * rc > 0 else 0.0)
            score((yy - sy) ** 2 + (xx - sx) ** 2 <= sr * sr, 'circle (as-is)')
            dy, dx = yy - fy, xx - fx
            u = c * dx + s * dy
            v = -s * dx + c * dy
            score(u * u / (a_ * a_) + v * v / (b_ * b_) <= 1.0, 'ellipse (fitted)')

    print(f"{'shape':<22}{'IoU p10':>9}{'p50':>8}{'p90':>8}{'n':>7}")
    for k, v in res.items():
        a = np.array(v)
        if not len(a):
            continue
        print(f"{k:<22}{np.percentile(a,10):>9.2f}{np.median(a):>8.2f}"
              f"{np.percentile(a,90):>8.2f}{len(a):>7}")
    print()
    for k in ('on a cell', 'on no cell'):
        tot = fitted_on[k] + refused[k]
        if tot:
            print(f"detections {k:<12} fitted {fitted_on[k]:>4} / refused {refused[k]:>4}"
                  f"  ({100*refused[k]/tot:.0f}% refused)")
    print("\nA refusal on a detection covering NO cell is the right answer.")


if __name__ == '__main__':
    main()
