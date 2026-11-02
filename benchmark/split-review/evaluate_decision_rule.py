"""The split decision rule, scored by the OUTCOME of each split, on held-out timepoints.

Rule (K chosen on t<40 of 20261001-152441, then frozen):
  1. extentRatio > median + K MAD of every extentRatio the run has seen up to this timepoint, both
     taken as GROUPED statistics: the readings sit on a 1/16 grid and the plain median and MAD
     flipped between grid cells as the run went on, moving the bar between 1.278 and 1.076
  2. two ellipses cover the object better than one (iou2 > iou1)
  3. neither piece lands inside ANOTHER detection's own radius
  4. the measured extent (extentRatio x radius) stays clear of every image face

EVERY detection is scored, including those on no truth cell. The first version scored only
detections on a cell (cover >= 1), so fragments cut by the image edge -- on no cell, extent walk
running out of the image -- were never scored, and in the first GUI run 57 of 91 splits put a piece
on background. Condition 4 is what that hole hid.

A split is RIGHT when its two pieces sit on two different silver-truth cells, neither on background,
and neither on a cell some other detection already sits on. Scoring by "how many truth cells are
under the detection's disc" called a split that recovered an undetected neighbour wrong; the outcome
is what is taught.

    python evaluate_decision_rule.py /tmp/split-features-<stamp>.tsv [held_out_from_t]
"""
import csv
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import prototype_splitter as p  # noqa: E402

K = 1.5

# extentRatio sits on a 1/16 grid (half a walk step of radius/8); see AutoCorrectOrchestrator.
STEP = 1 / 16


def grouped_median(E, h=STEP):
    """Median of readings on a grid of step h, each spread uniformly over its grid cell."""
    E = np.sort(E)
    n = len(E)
    vals, counts = np.unique(np.round(E / h).astype(int), return_counts=True)
    cum = np.cumsum(counts)
    k = np.searchsorted(cum, n / 2)
    below = cum[k - 1] if k > 0 else 0
    return (vals[k] - 0.5) * h + ((n / 2 - below) / counts[k]) * h


def grouped_mad(E, med, h=STEP, sub=8):
    """MAD with each reading spread over its grid cell, by midpoint sampling."""
    off = (np.arange(sub) + 0.5) / sub - 0.5
    return np.median(np.abs((E[:, None] + off[None, :] * h) - med).ravel())


def grouped_bar(E, k=K):
    if len(E) < 10:
        return 0.0
    med = grouped_median(E)
    return med + k * 1.4826 * grouped_mad(E, med)


def main():
    path = sys.argv[1]
    held = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    rows = list(csv.DictReader(open(path), delimiter='\t'))
    f = lambda k: np.array([float(r[k]) for r in rows])  # noqa: E731
    t, x, y, cover, r, fr = f('t'), f('x'), f('y'), f('cover'), f('r'), f('frameR')
    ext, gain = f('extentRatio'), f('iou2') - f('iou1')
    by = defaultdict(list)
    for i, tt in enumerate(t):
        by[tt].append(i)
    thr, seen = {}, []
    for tt in sorted(by):
        seen.extend(v for v in ext[by[tt]] if not np.isnan(v))
        thr[tt] = grouped_bar(np.array(seen))
    lab = np.array([p.truth_at(int(t[i]), x[i], y[i]) for i in range(len(rows))])
    ok = ~np.isnan(ext)
    W, H = p.image(0)[0].shape[1] * p.VOXEL_UM, p.image(0)[0].shape[0] * p.VOXEL_UM
    reach = ext * r
    clear = (x - reach >= 0) & (x + reach <= W) & (y - reach >= 0) & (y + reach <= H)

    def outcome(i):
        tt = int(t[i])
        pieces = p.split_gmm(tt, dict(x=x[i], y=y[i], r=r[i], frameR=fr[i]), sigma_level=5.0,
                             bic_margin=-1e9, min_sep_radii=0.0)
        if len(pieces) < 2:
            return None
        for px, py in pieces:
            for j in by[t[i]]:
                if j != i and np.hypot(px - x[j], py - y[j]) < r[j]:
                    return None                       # condition 3 refuses
        labs = [p.truth_at(tt, px, py) for px, py in pieces]
        others = {lab[j] for j in by[t[i]] if j != i}
        if any(v == 0 for v in labs):
            return 'a piece on background'
        if labs[0] == labs[1]:
            return 'both pieces on one cell'
        if any(v in others for v in labs):
            return 'a piece duplicates another detection'
        return 'RIGHT'

    flag = ok & np.array([ext[i] > thr[t[i]] for i in range(len(rows))]) \
        & (gain > 0) & clear
    res = {i: outcome(i) for i in np.nonzero(flag)[0]}
    print('| data | splits | right | background | one cell split | duplicate | merged fixed |')
    print('|---|---|---|---|---|---|---|')
    for name, part in ((f't<{held} (K chosen on)', t < held), (f't>={held} (held out)', t >= held),
                       ('whole movie', t >= 0)):
        sp = [i for i, o in res.items() if o and part[i]]
        c = {k: sum(res[i] == k for i in sp) for k in ('RIGHT', 'a piece on background',
                                                     'both pieces on one cell',
                                                     'a piece duplicates another detection')}
        two = np.nonzero(ok & (cover >= 2) & part)[0]
        fixed = sum(res.get(i) == 'RIGHT' for i in two)
        print(f'| {name} | {len(sp)} | {c["RIGHT"]} ({c["RIGHT"] / max(len(sp), 1):.0%}) '
              f'| {c["a piece on background"]} | {c["both pieces on one cell"]} '
              f'| {c["a piece duplicates another detection"]} | {fixed}/{len(two)} |')
    return 0


if __name__ == '__main__':
    sys.exit(main())
