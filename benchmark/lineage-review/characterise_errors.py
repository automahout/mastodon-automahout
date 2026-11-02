"""What the two largest error classes look like, to know which pass could remove them.

For one snapshot, against the tracking markers (01_GT/TRA, every cell):

  background detections (no marker within MATCH_UM): distance to the nearest marker in the frame's
  median radii; interior median brightness in spreads above the frame median (the no-signal pass's
  own scale); radius over the frame median; length of the track it sits on
  missed cells (no detection centre within MATCH_UM of the marker): brightness at the marker,
  distance to the nearest detection in median radii (is a neighbour's detection covering it?)

Compared with correctly matched detections, so a threshold can be read off rather than guessed.

    python characterise_errors.py stamp snapshot-label-prefix [t_from t_to]
"""
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'split-review'))
import measure_convergence as mc  # noqa: E402
import measure_errors as me  # noqa: E402
import measure_links as ml  # noqa: E402
import prototype_splitter as ps  # noqa: E402


def interior(t, x, y, r):
    im, med, sd = ps.image(t)
    cx, cy, rp = x / ps.VOXEL_UM, y / ps.VOXEL_UM, max(1.0, 0.5 * r / ps.VOXEL_UM)
    y0, y1 = int(max(0, cy - rp)), int(min(im.shape[0], cy + rp + 1))
    x0, x1 = int(max(0, cx - rp)), int(min(im.shape[1], cx + rp + 1))
    win = im[y0:y1, x0:x1]
    yy, xx = np.mgrid[y0:y1, x0:x1]
    m = (xx - cx) ** 2 + (yy - cy) ** 2 <= rp * rp
    return (np.median(win[m]) - med) / sd if m.any() else np.nan


def q(v):
    v = np.asarray([x for x in v if not np.isnan(x)])
    return np.percentile(v, [10, 50, 90]).round(2) if len(v) else '-'


def main():
    stamp, prefix = sys.argv[1], sys.argv[2]
    snap, label = me.snapshot_of(stamp, prefix)
    lpath = Path(f'/tmp/origin-dump-{stamp}.links.tsv')
    spots = mc.load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), snap)
    succ, pred = defaultdict(list), defaultdict(list)
    with open(lpath) as fh:
        fh.readline()
        for line in fh:
            r = line.split('\t')
            if r[0] != 'rev' and int(r[1]) == snap:
                succ[int(r[3])].append(int(r[4]))
                pred[int(r[4])].append(int(r[3]))

    def track_len(k):
        n, a = 1, k
        while len(pred[a]) == 1:
            a = pred[a][0]
            n += 1
        b = k
        while len(succ[b]) == 1:
            b = succ[b][0]
            n += 1
        return n

    by_t = defaultdict(list)
    for k, s in spots.items():
        if s['tag'] != 'fp':
            by_t[s['t']].append(k)
    ts = sorted(by_t)
    if len(sys.argv) > 4:
        ts = [t for t in ts if int(sys.argv[3]) <= t <= int(sys.argv[4])]
    good, bg, missed = defaultdict(list), defaultdict(list), defaultdict(list)
    for t in ts:
        if ml.tra(t) is None:
            continue
        labs, cents = ml._cent[t]
        fr = float(np.median([spots[k]['r'] for k in by_t[t]]))
        used = set()
        for k in by_t[t]:
            s = spots[k]
            d = np.hypot(cents[:, 0] - s['x'], cents[:, 1] - s['y'])
            j = int(d.argmin())
            row = good if d[j] <= ml.MATCH_UM and labs[j] not in used else bg if d[j] > ml.MATCH_UM else None
            if d[j] <= ml.MATCH_UM:
                used.add(labs[j])
            if row is None:
                continue
            row['dist'].append(d[j] / fr)
            row['bright'].append(interior(t, s['x'], s['y'], s['r']))
            row['size'].append(s['r'] / fr)
            row['track'].append(track_len(k))
        for j, lab in enumerate(labs):
            if lab in used:
                continue
            x, y = cents[j]
            dd = [np.hypot(spots[k]['x'] - x, spots[k]['y'] - y) for k in by_t[t]]
            missed['nearest detection'].append(min(dd) / fr)
            missed['bright'].append(interior(t, x, y, fr))
    print(f'{stamp} snapshot {snap} "{label}" t={ts[0]}..{ts[-1]}')
    print(f'                 matched (n={len(good["dist"])})        background (n={len(bg["dist"])})')
    for k, name in (('dist', 'to nearest cell, radii'), ('bright', 'interior, spreads'),
                    ('size', 'radius / frame median'), ('track', 'track length, frames')):
        print(f'  {name:24s} {str(q(good[k])):22s} {q(bg[k])}')
    print(f'  background with track length 1-2: {sum(1 for v in bg["track"] if v <= 2)}, '
          f'within 1.5 radii of a cell: {sum(1 for v in bg["dist"] if v <= 1.5)}')
    print(f'missed cells (n={len(missed["bright"])}): brightness at the marker {q(missed["bright"])} '
          f'(matched detections {q(good["bright"])}); nearest detection {q(missed["nearest detection"])} radii; '
          f'within 1 radius of a detection: {sum(1 for v in missed["nearest detection"] if v <= 1)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
