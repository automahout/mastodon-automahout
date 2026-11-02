"""What the split pass decided, per detection, against silver truth.

Joins two dumps of ONE launch (same DumpFiles stamp):
  - the split dump: every detection the pass saw, BEFORE any gate, with its
    radius and interior profile (the population it acts on);
  - the origin dump: the graph after [3/6] commit detection, each spot with the
    Reason that added it -- SPLIT products are the pass's output.

A detection is SPLIT when its own position is gone from the committed graph and
at least two SPLIT products sit inside its radius. Truth: how many silver-truth
cell centroids its disc contains (0, 1, 2+), the label_dump.py convention.

The question: does the pass split what covers two cells, and leave what covers
one? And which reading -- radius, interior spread -- would have told them apart.

    python measure_split_decisions.py [stamp]
"""
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
VOXEL_UM = 0.645


def newest_stamp():
    files = sorted(Path('/tmp').glob('origin-dump-*.tsv'), key=lambda f: f.stat().st_mtime)
    return files[-1].stem[len('origin-dump-'):] if files else None


def centroids(t, cache={}):
    if t not in cache:
        f = ROOT / '01_ST' / 'SEG' / f'man_seg{t:03d}.tif'
        if not f.exists():
            cache[t] = None
        else:
            seg = np.array(Image.open(f))
            rows, cols = np.nonzero(seg)
            vals = seg[rows, cols]
            labs = np.unique(vals)
            cache[t] = np.array([[cols[vals == l].mean() * VOXEL_UM, rows[vals == l].mean() * VOXEL_UM]
                                 for l in labs])
    return cache[t]


def last_block_per_t(path):
    """The commit predict is the last pass over a timepoint; earlier blocks are tuning rounds."""
    blocks, prev = [], None
    with open(path) as fh:
        head = fh.readline().rstrip('\n').split('\t')
        ix = {n: i for i, n in enumerate(head)}
        for line in fh:
            p = line.rstrip('\n').split('\t')
            if p[0] == 'rev':
                continue
            key = (p[0], p[1])
            if key != prev:
                blocks.append((int(p[1]), []))
                prev = key
            g = lambda n: float(p[ix[n]]) if p[ix[n]] != '' else float('nan')
            blocks[-1][1].append(dict(x=g('x'), y=g('y'), r=g('radius'), coreCv=g('coreCv'),
                                      fullCv=g('fullCv'), frameR=g('frameMedianRadius'),
                                      extentRatio=g('extentRatio') if 'extentRatio' in ix
                                      and len(p) > ix['extentRatio'] else float('nan')))
    out = {}
    for t, dets in blocks:
        out[t] = dets
    return out


def committed(path):
    """Per timepoint, the spots of the LAST [3/6] snapshot covering it."""
    snaps = defaultdict(list)
    label = {}
    with open(path) as fh:
        head = fh.readline().rstrip('\n').split('\t')
        ix = {n: i for i, n in enumerate(head)}
        for line in fh:
            p = line.rstrip('\n').split('\t')
            if p[0] == 'rev' or not p[ix['label']].startswith('[3/6]'):
                continue
            s = int(p[ix['snapshot']])
            label[s] = p[ix['label']]
            snaps[s].append((int(p[ix['t']]), float(p[ix['x']]), float(p[ix['y']]), p[ix['reason']]))
    per_t = {}
    for s in sorted(snaps):
        by_t = defaultdict(list)
        for t, x, y, why in snaps[s]:
            by_t[t].append((x, y, why))
        per_t.update(by_t)
    return per_t


def auc(pos, neg):
    """P(a positive reads higher than a negative)."""
    pos, neg = np.asarray(pos), np.asarray(neg)
    pos, neg = pos[~np.isnan(pos)], neg[~np.isnan(neg)]
    if len(pos) == 0 or len(neg) == 0:
        return float('nan')
    greater = (pos[:, None] > neg[None, :]).sum() + 0.5 * (pos[:, None] == neg[None, :]).sum()
    return greater / (len(pos) * len(neg))


def main():
    stamp = sys.argv[1] if len(sys.argv) > 1 else newest_stamp()
    split_path = next(Path('/tmp').glob(f'split-fixture*-{stamp}.tsv'), None)
    origin_path = Path(f'/tmp/origin-dump-{stamp}.tsv')
    if split_path is None or not origin_path.exists():
        print(f'need both dumps for stamp {stamp}')
        return 1
    print(f'split dump  {split_path}\norigin dump {origin_path}\n')
    parents = last_block_per_t(split_path)
    graph = committed(origin_path)

    rows = []
    for t, dets in sorted(parents.items()):
        if t not in graph:
            continue
        cents = centroids(t)
        if cents is None:
            continue
        spots = graph[t]
        kept = np.array([(x, y) for x, y, _ in spots]) if spots else np.zeros((0, 2))
        prods = np.array([(x, y) for x, y, w in spots if w == 'SPLIT']) if spots else np.zeros((0, 2))
        for d in dets:
            here = np.array([d['x'], d['y']])
            still = len(kept) and np.min(np.hypot(*(kept - here).T)) < 0.01
            near = int((np.hypot(*(prods - here).T) < d['r']).sum()) if len(prods) else 0
            cover = int((np.hypot(*(cents - here).T) < d['r']).sum())
            rows.append(dict(t=t, split=(not still) and near >= 2, cover=cover, **d))

    print(f'{len(rows)} detection(s) over {len({r["t"] for r in rows})} timepoint(s)\n')
    print('| truth cells under it | detections | split by the pass | left whole |')
    print('|---|---|---|---|')
    for label, test in (('0', lambda c: c == 0), ('1', lambda c: c == 1), ('2+', lambda c: c >= 2)):
        g = [r for r in rows if test(r['cover'])]
        n = sum(r['split'] for r in g)
        print(f'| {label} | {len(g)} | {n} ({n / max(len(g), 1):.1%}) | {len(g) - n} |')
    split = [r for r in rows if r['split']]
    right = sum(r['cover'] >= 2 for r in split)
    print(f'\nprecision: {right}/{len(split)} splits were on a detection covering 2+ cells')
    two = [r for r in rows if r['cover'] >= 2]
    print(f'recall:    {sum(r["split"] for r in two)}/{len(two)} detections covering 2+ cells were split')

    one = [r for r in rows if r['cover'] == 1]
    print('\nWhat separates covering-TWO from covering-ONE (AUC, 0.5 = nothing):')
    for name, f in (('radius', lambda r: r['r']), ('radius / frame median', lambda r: r['r'] / r['frameR']),
                    ('core CV', lambda r: r['coreCv']), ('full CV', lambda r: r['fullCv'])):
        a = auc([f(r) for r in two], [f(r) for r in one])
        print(f'  {name:24s} {a:.3f}')
    print('\nAnd what the pass actually used, on the ones it split vs left (covering ONE only):')
    for name, f in (('core CV', lambda r: r['coreCv']), ('radius / frame median', lambda r: r['r'] / r['frameR'])):
        a = auc([f(r) for r in one if r['split']], [f(r) for r in one if not r['split']])
        print(f'  {name:24s} {a:.3f}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
