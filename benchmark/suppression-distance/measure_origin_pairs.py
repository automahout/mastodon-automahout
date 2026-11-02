"""Which add paths put two spots on one cell, and how far apart they are.

Reads the origin dump (SpotOriginDump: -Dautomahout.originDump=... or
AUTOMAHOUT_ORIGIN_DUMP=...), which writes the whole graph after every tool call
with the Reason and the calling methods that added each spot.

A pair is a DUPLICATE when both centres fall in the same silver-truth cell at
the same timepoint. Pairs are only ever formed inside one snapshot -- across
snapshots a spot pairs with itself.

The question it answers: does ELEPHANT's suppression rule, which guards only
predict, cover the duplicates this project produces? If most pairs have one
member from interpolation, gap filling or GT restore, suppressing at predict
fixes nothing.

    python measure_origin_pairs.py [/tmp/origin-dump-<stamp>.tsv [snapshot]]

Without a snapshot number, the LAST snapshot is reported in detail and every
snapshot gets one summary line.
"""
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
SEQ = '01'
VOXEL_UM = 0.645
REVISION = 1
DISTANCES = (3.0, 5.0, 7.0, 10.0)

_seg_cache = {}


def truth_map(t):
    if t not in _seg_cache:
        f = ROOT / f'{SEQ}_ST' / 'SEG' / f'man_seg{t:03d}.tif'
        _seg_cache[t] = np.array(Image.open(f)) if f.exists() else None
    return _seg_cache[t]


def label_at(seg, x_um, y_um, flip):
    c, r = (y_um, x_um) if flip else (x_um, y_um)
    col, row = int(round(c / VOXEL_UM)), int(round(r / VOXEL_UM))
    if row < 0 or col < 0 or row >= seg.shape[0] or col >= seg.shape[1]:
        return -1
    return int(seg[row, col])


def load(path):
    snaps = defaultdict(list)
    labels = {}
    with open(path) as fh:
        head = fh.readline().rstrip('\n').split('\t')
        ix = {n: i for i, n in enumerate(head)}
        for line in fh:
            p = line.rstrip('\n').split('\t')
            if p[0] == 'rev' or int(p[ix['rev']]) < REVISION:
                continue            # a header from a later append, or a stale build
            s = int(p[ix['snapshot']])
            labels[s] = p[ix['label']]
            snaps[s].append(dict(
                t=int(p[ix['t']]), x=float(p[ix['x']]), y=float(p[ix['y']]),
                tag=p[ix['tag']], prot=p[ix['protected']] == '1',
                reason=p[ix['reason']], site=p[ix['site']]))
    return snaps, labels


def origin(s):
    """Reason plus the innermost calling method: specific enough to name the pass."""
    site = s['site'].split('<-')[0] if s['site'] else ''
    return f"{s['reason']}:{site}" if site else s['reason']


def choose_flip(spots):
    best = None
    for flip in (False, True):
        on = tot = 0
        for s in spots:
            seg = truth_map(s['t'])
            if seg is None:
                continue
            tot += 1
            on += label_at(seg, s['x'], s['y'], flip) > 0
        frac = on / tot if tot else 0.0
        if best is None or frac > best[1]:
            best = (flip, frac)
    return best


def same_cell_pairs(spots, flip):
    by_cell = defaultdict(list)
    for s in spots:
        if s['tag'] == 'fp':
            continue                # a training negative, not a detection
        seg = truth_map(s['t'])
        if seg is None:
            continue
        lab = label_at(seg, s['x'], s['y'], flip)
        if lab > 0:
            by_cell[(s['t'], lab)].append(s)
    pairs = []
    for members in by_cell.values():
        for a in range(len(members)):
            for b in range(a + 1, len(members)):
                one, two = members[a], members[b]
                d = float(np.hypot(one['x'] - two['x'], one['y'] - two['y']))
                pairs.append((one, two, d))
    return pairs, len(by_cell)


def newest(directory, pattern):
    files = sorted(Path(directory).glob(pattern), key=lambda f: f.stat().st_mtime)
    return files[-1] if files else None


def main():
    # The dumps write one file per launch (DumpFiles): /tmp/origin-dump-<stamp>.tsv. With no
    # argument, the newest one.
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else newest('/tmp', 'origin-dump-*.tsv')
    if path is None:
        print('no /tmp/origin-dump-*.tsv -- pass a path')
        return 1
    print(f'reading {path}')
    snaps, labels = load(path)
    if not snaps:
        print(f'no rows at revision >= {REVISION} in {path}')
        return 1
    flip, frac = choose_flip(snaps[max(snaps)])
    print(f'{len(snaps)} snapshot(s) in {path}; axis flip={flip} lands {frac:.1%} on a cell')
    if frac < 0.5:
        print('Neither axis mapping lands half the spots -- stopping.')
        return 1

    print('\n| snapshot | after | spots | cells covered | same-cell pairs |')
    print('|---|---|---|---|---|')
    for s in sorted(snaps):
        pairs, cells = same_cell_pairs(snaps[s], flip)
        print(f'| {s} | {labels[s]} | {len(snaps[s])} | {cells} | {len(pairs)} |')

    # Snapshots taken inside adaptive_track cover one window, so "the last snapshot" is not the
    # result. What localises a duplicate is the FIRST step at which its (timepoint, truth cell) holds
    # two spots, and which add paths the two came from at that moment.
    first = {}
    for s in sorted(snaps):
        for one, two, d in same_cell_pairs(snaps[s], flip)[0]:
            seg = truth_map(one['t'])
            key = (one['t'], label_at(seg, one['x'], one['y'], flip))
            if key not in first:
                first[key] = (s, one, two, d)
    if first:
        print(f'\n## Where each duplicate FIRST appears ({len(first)} timepoint/cell pair(s))\n')
        print('| step | origin of the pair | pairs | distance um p50 |')
        print('|---|---|---|---|')
        grouped = defaultdict(list)
        for s, one, two, d in first.values():
            step = labels[s].split(' t=')[0]
            grouped[(step, ' + '.join(sorted((origin(one), origin(two)))))].append(d)
        for (step, combo), ds in sorted(grouped.items(), key=lambda kv: -len(kv[1])):
            print(f'| {step} | {combo} | {len(ds)} | {np.median(ds):.1f} |')
        print('\nFirst ten, to find in the viewer (t, x, y in world units):')
        for (t, lab), (s, one, two, d) in sorted(first.items())[:10]:
            print(f'  t={t} ({one["x"]:.1f},{one["y"]:.1f}) {origin(one)}  +  '
                  f'({two["x"]:.1f},{two["y"]:.1f}) {origin(two)}  d={d:.1f}  first in {labels[s]}')

    pick = int(sys.argv[2]) if len(sys.argv) > 2 else max(snaps)
    spots = snaps[pick]
    pairs, cells = same_cell_pairs(spots, flip)
    print(f'\n## Snapshot {pick} (after {labels[pick]})\n')
    print('Where the spots came from:')
    for o, n in Counter(origin(s) for s in spots if s['tag'] != 'fp').most_common():
        print(f'  {n:6d}  {o}')
    if not pairs:
        print('\nNo two spots share a truth cell.')
        return 0

    print(f'\n{len(pairs)} same-cell pair(s) over {cells} covered cell(s)\n')
    print('| origin of the pair | pairs | distance um p10 / p50 / p90 |')
    print('|---|---|---|')
    combos = defaultdict(list)
    for one, two, d in pairs:
        combos[' + '.join(sorted((origin(one), origin(two))))].append(d)
    for combo, ds in sorted(combos.items(), key=lambda kv: -len(kv[1])):
        ds = np.array(ds)
        print(f'| {combo} | {len(ds)} | {np.percentile(ds, 10):.1f} / '
              f'{np.percentile(ds, 50):.1f} / {np.percentile(ds, 90):.1f} |')

    both_predict = [d for one, two, d in pairs
                    if one['reason'] == 'DETECTION' and two['reason'] == 'DETECTION']
    print(f'\nBoth members from DETECTION (what predict-time suppression can reach): '
          f'{len(both_predict)}/{len(pairs)} ({len(both_predict) / len(pairs):.1%})')
    all_d = np.array([d for _, _, d in pairs])
    print('\n| suppression distance | all same-cell pairs within | DETECTION-only pairs within |')
    print('|---|---|---|')
    bp = np.array(both_predict) if both_predict else np.array([np.inf])
    for d in DISTANCES:
        print(f'| {d:.1f} um | {(all_d < d).mean():.1%} | '
              f'{(bp < d).sum() if both_predict else 0}/{len(both_predict)} |')
    return 0


if __name__ == '__main__':
    sys.exit(main())
