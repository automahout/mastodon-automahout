"""What kind of error dominates a snapshot, per frame, against truth.

  missed       a silver-truth cell (01_ST/SEG) no detection centre lies on
  duplicate    a silver-truth cell two or more detection centres lie on (counted once per extra)
  two-in-one   a detection whose ellipse holds two or more tracking markers (01_GT/TRA)
  background   a detection whose centre lies on no silver-truth cell (fp-tagged negatives excluded)

    python measure_errors.py stamp snapshot-label-prefix [t_from t_to]
"""
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'split-review'))
import measure_convergence as mc  # noqa: E402
import prototype_splitter as ps  # noqa: E402


def snapshot_of(stamp, prefix):
    with open(f'/tmp/origin-dump-{stamp}.tsv') as fh:
        fh.readline()
        best = None
        for line in fh:
            q = line.split('\t', 4)
            if q[0] != 'rev' and q[2].startswith(prefix):
                best = (int(q[1]), q[2])
    return best


def main():
    stamp, prefix = sys.argv[1], sys.argv[2]
    snap, label = snapshot_of(stamp, prefix)
    spots = mc.load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), snap)
    by_t = defaultdict(list)
    for k, s in spots.items():
        if s['tag'] != 'fp':
            by_t[s['t']].append(s)
    ts = sorted(by_t)
    if len(sys.argv) > 4:
        ts = [t for t in ts if int(sys.argv[3]) <= t <= int(sys.argv[4])]
    c = Counter()
    for t in ts:
        seg = ps.seg(t)
        if seg is None:
            continue
        cells = set(np.unique(seg)) - {0}
        hits = Counter(ps.truth_at(t, s['x'], s['y']) for s in by_t[t])
        c['truth cells'] += len(cells)
        c['detections'] += len(by_t[t])
        c['missed'] += len(cells - set(hits))
        c['duplicate'] += sum(n - 1 for lab, n in hits.items() if lab != 0 and n > 1)
        c['background'] += hits.get(0, 0)
        c['two-in-one'] += sum(1 for s in by_t[t] if (mc.truth_inside(s) or 0) >= 2)
        c['frames'] += 1
        # The same against the tracking markers (01_GT/TRA), which mark every cell: a detection is on
        # a cell when a marker lies within MATCH_UM of its centre.
        import measure_links as ml
        if ml.tra(t) is not None:
            labs, cents = ml._cent[t]
            used = set()
            for s in by_t[t]:
                d = np.hypot(cents[:, 0] - s['x'], cents[:, 1] - s['y'])
                j = int(d.argmin()) if len(d) else -1
                if j < 0 or d[j] > ml.MATCH_UM:
                    c['TRA: background'] += 1
                elif labs[j] in used:
                    c['TRA: duplicate'] += 1
                else:
                    used.add(labs[j])
            c['TRA cells'] += len(labs)
            c['TRA: missed'] += len(labs) - len(used)
    n = max(c['truth cells'], 1)
    print(f'{stamp} snapshot {snap} "{label}", {c["frames"]} frames t={ts[0]}..{ts[-1]}: '
          f'{c["truth cells"]} truth cells, {c["detections"]} detections')
    for k in ('missed', 'duplicate', 'two-in-one', 'background'):
        print(f'  {k:11s} {c[k]:5d}  ({c[k] / n:.1%} of truth cells)')
    m = max(c['TRA cells'], 1)
    print(f'  against TRA markers ({c["TRA cells"]} cells, match {8.0} um): ' + ', '.join(
        f'{k[5:]} {c[k]} ({c[k] / m:.1%})' for k in ('TRA: missed', 'TRA: duplicate', 'TRA: background')))
    return 0


if __name__ == '__main__':
    sys.exit(main())
