"""What suppression distance takes a DUPLICATE without taking a NEIGHBOUR.

ELEPHANT's own client suppresses at add time: PredictSpotsAction asks the
timepoint's SpatialIndex for the nearest spot already added and drops the
candidate when that distance is below ElephantMainSettings.getSuppressionDistance()
(default 3.0, world units). This project never applied it, so every spot the
server returned was added.

The default is a number, not a measurement. Two questions, answered separately:

1. Model-free: how close do two REAL cells come? Nearest-neighbour distance
   between truth centroids over every silver-truth frame. Any suppression
   distance above the low tail of this deletes real cells whatever the detector
   does.

2. On the detector's own output: replay ELEPHANT's greedy suppression over each
   snapshot and count what it takes -- duplicates (a second detection on a truth
   cell already covered) against real cells (a truth cell whose ONLY detection is
   dropped).

The dump (SplitFixtureDump) appends one block of rows per pass per timepoint.
A block is one snapshot of what coexisted in the graph; distances are only ever
taken inside one block. Taking them across blocks pairs a detection with ITSELF
from another pass -- the first version of this script did that and reported
duplicates 0.1 um apart at AUC 1.000.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.spatial import cKDTree

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
VOXEL_UM = 0.645
REVISION = 2
def _newest(pattern):
    files = sorted(Path('/tmp').glob(pattern), key=lambda f: f.stat().st_mtime)
    return files[-1] if files else Path('/tmp/split-fixture.tsv')


# One file per launch (DumpFiles): /tmp/split-fixture-<stamp>.tsv. With no argument, the newest.
DUMP = Path(sys.argv[1]) if len(sys.argv) > 1 else _newest('split-fixture*.tsv')
DISTANCES = (2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0)


def truth_map(seq, t):
    f = ROOT / f'{seq}_ST' / 'SEG' / f'man_seg{t:03d}.tif'
    if not f.exists():
        return None
    return np.array(Image.open(f))


def load_snapshots(path):
    """Contiguous (rev, t) blocks, identical repeats removed. Keeps dump order."""
    blocks, prev = [], None
    with open(path) as fh:
        head = fh.readline().rstrip('\n').split('\t')
        ix = {n: i for i, n in enumerate(head)}
        for line in fh:
            p = line.rstrip('\n').split('\t')
            rev, t = int(p[ix['rev']]), int(p[ix['t']])
            if (rev, t) != prev:
                blocks.append((t, []))
                prev = (rev, t)
            if rev < REVISION:
                continue
            blocks[-1][1].append((float(p[ix['x']]), float(p[ix['y']])))
    seen, out = set(), []
    for t, dets in blocks:
        if not dets:
            continue
        key = (t, tuple((round(x, 3), round(y, 3)) for x, y in dets))
        if key in seen:
            continue
        seen.add(key)
        out.append((t, np.array(dets)))
    return len(blocks), out


def label_at(seg, x_um, y_um, flip):
    c, r = (y_um, x_um) if flip else (x_um, y_um)
    col, row = int(round(c / VOXEL_UM)), int(round(r / VOXEL_UM))
    if row < 0 or col < 0 or row >= seg.shape[0] or col >= seg.shape[1]:
        return -1
    return int(seg[row, col])


def truth_nn_distances():
    """Per truth cell, distance to the nearest OTHER truth cell centroid (um)."""
    out = []
    for f in sorted((ROOT / '01_ST' / 'SEG').glob('man_seg*.tif')):
        seg = np.array(Image.open(f))
        labs = np.unique(seg)
        labs = labs[labs > 0]
        if len(labs) < 2:
            continue
        rows, cols = np.nonzero(seg)
        vals = seg[rows, cols]
        cents = np.array([[cols[vals == l].mean(), rows[vals == l].mean()] for l in labs])
        d, _ = cKDTree(cents * VOXEL_UM).query(cents * VOXEL_UM, k=2)
        out.extend(d[:, 1])
    return np.array(out)


def suppress(dets, dist):
    """ELEPHANT's rule, in arrival order: keep a candidate unless a KEPT one is closer than dist."""
    kept = []
    for i, p in enumerate(dets):
        if kept and np.min(np.hypot(*(dets[kept] - p).T)) < dist:
            continue
        kept.append(i)
    return kept


def pct(a, q):
    return float(np.percentile(a, q))


def main():
    print('## 1. How close real cells come (silver truth, every frame, model-free)\n')
    nn = truth_nn_distances()
    print(f'{len(nn)} truth cells')
    print('| nearest other cell, um | p0.1 | p0.5 | p1 | p5 | p50 |')
    print('|---|---|---|---|---|---|')
    print(f'| centre-to-centre | {pct(nn, .1):.1f} | {pct(nn, .5):.1f} | {pct(nn, 1):.1f} '
          f'| {pct(nn, 5):.1f} | {pct(nn, 50):.1f} |')
    print(f'min {nn.min():.1f} um')
    print('\n| suppression distance | real cells with a neighbour closer |')
    print('|---|---|')
    for d in DISTANCES:
        print(f'| {d:.1f} um | {(nn < d).mean():.2%} |')

    nblocks, snaps = load_snapshots(DUMP)
    print(f'\n## 2. Replaying the rule on the detector\'s own output\n')
    print(f'{nblocks} block(s) in the dump, {len(snaps)} distinct snapshot(s), '
          f'{sum(len(d) for _, d in snaps)} detection(s)')

    best = None
    for flip in (False, True):
        on = tot = 0
        for t, dets in snaps:
            seg = truth_map('01', t)
            if seg is None:
                continue
            for x, y in dets:
                tot += 1
                on += label_at(seg, x, y, flip) > 0
        frac = on / tot if tot else 0.0
        print(f'  axis mapping flip={flip}: {frac:.1%} of detections land on a cell')
        if best is None or frac > best[1]:
            best = (flip, frac)
    flip, frac = best
    if frac < 0.5:
        print('Neither mapping lands half the detections -- stopping.')
        return 1

    # The same-cell pair distance: what a duplicate actually looks like.
    same, labelled = [], []
    for t, dets in snaps:
        seg = truth_map('01', t)
        if seg is None:
            continue
        labs = np.array([label_at(seg, x, y, flip) for x, y in dets])
        labelled.append((dets, labs))
        for l in np.unique(labs[labs > 0]):
            idx = np.nonzero(labs == l)[0]
            for a in range(len(idx)):
                for b in range(a + 1, len(idx)):
                    same.append(float(np.hypot(*(dets[idx[a]] - dets[idx[b]]))))
    same = np.array(same)
    ndet = sum(len(l) for _, l in labelled)
    ncells = sum(len(np.unique(l[l > 0])) for _, l in labelled)
    extra = sum(int((l > 0).sum()) - len(np.unique(l[l > 0])) for _, l in labelled)
    print(f'\n{len(labelled)} snapshot(s) with truth: {ndet} detections, {ncells} truth cells '
          f'covered, {extra} surplus detection(s) on an already-covered cell')
    if len(same):
        print(f'same-cell pairs: {len(same)}, distance um p10/p50/p90 = '
              f'{pct(same, 10):.1f} / {pct(same, 50):.1f} / {pct(same, 90):.1f}, '
              f'max {same.max():.1f}')

    print('\n| suppression distance | surplus taken | real cells lost | on-no-cell taken |')
    print('|---|---|---|---|')
    for d in DISTANCES:
        taken_extra = lost = bg_taken = bg_total = 0
        for dets, labs in labelled:
            kept = np.zeros(len(dets), bool)
            kept[suppress(dets, d)] = True
            covered = set(labs[labs > 0])
            still = set(labs[kept & (labs > 0)])
            lost += len(covered - still)
            before = int((labs > 0).sum()) - len(covered)
            after = int((kept & (labs > 0)).sum()) - len(still)
            taken_extra += before - after
            bg_total += int((labs <= 0).sum())
            bg_taken += int(((labs <= 0) & ~kept).sum())
        print(f'| {d:.1f} um | {taken_extra}/{extra} ({taken_extra / max(extra, 1):.1%}) '
              f'| {lost}/{ncells} ({lost / max(ncells, 1):.2%}) '
              f'| {bg_taken}/{bg_total} |')
    return 0


if __name__ == '__main__':
    sys.exit(main())
