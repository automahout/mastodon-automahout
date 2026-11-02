"""What surrounds each link that jumps between two cells (UNNATURAL in measure_links.py).

For a wrong link A(t, on cell a) -> B(t+1, on cell b), b != a, not a daughter of a:
  a's own detection at t+1     missing / present with no predecessor / present, linked from elsewhere
  b's own detection at t       missing / present with no successor / present, linked to elsewhere
  CROSSED                      both present and linked to each other's cell: a full swap
and the link's length in frame radii, the site that made it.

    python characterise_jumps.py stamp [snapshot-label-prefix]
"""
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import measure_convergence as mc  # noqa: E402
import measure_errors as me  # noqa: E402
import measure_links as ml  # noqa: E402


def main():
    stamp = sys.argv[1]
    prefix = sys.argv[2] if len(sys.argv) > 2 else 'auto.correct'
    links, label = ml.load_links(Path(f'/tmp/origin-dump-{stamp}.links.tsv'), prefix)
    snap, _ = me.snapshot_of(stamp, prefix)
    spots = mc.load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), snap)
    tr = ml.tracks()
    succ, pred = defaultdict(list), defaultdict(list)
    for l in links:
        succ[l['src']].append(l['dst'])
        pred[l['dst']].append(l['src'])
    on = {k: ml.track_at(s['t'], s['x'], s['y']) for k, s in spots.items()}
    by_cell = defaultdict(list)
    for k, s in spots.items():
        if on[k]:
            by_cell[(on[k], s['t'])].append(k)
    fr = defaultdict(list)
    for s in spots.values():
        fr[s['t']].append(s['r'])
    fr = {t: float(np.median(v)) for t, v in fr.items()}
    c = Counter()
    rows = []
    for l in links:
        A, B = l['src'], l['dst']
        if A not in spots or B not in spots or l['tt'] != l['ts'] + 1:
            continue
        a, b = on[A], on[B]
        if not a or not b or a == b or tr.get(b, (0, 0, 0))[2] == a or ml.is_descendant(tr, b, a):
            continue
        t = l['ts']
        own_a = [k for k in by_cell[(a, t + 1)]]
        own_b = [k for k in by_cell[(b, t)]]
        fa = 'a@t+1 from A too (false division)' if any(A in pred[k] for k in own_a) else \
            'a@t+1 missing' if not own_a else 'a@t+1 no pred' if all(not pred[k] for k in own_a) \
            else 'a@t+1 linked elsewhere'
        fb = 'b@t missing' if not own_b else 'b@t no succ' if all(not succ[k] for k in own_b) \
            else 'b@t linked elsewhere'
        crossed = any(on.get(x) == a for k in own_b for x in succ[k])
        two = (mc.truth_inside(spots[A]) or 0) >= 2 or (mc.truth_inside(spots[B]) or 0) >= 2
        key = 'CROSSED (full swap)' if crossed else f'{fa} | {fb}' + (' | A or B over two cells' if two else '')
        c[key] += 1
        rows.append((key, t, math.hypot(l['xt'] - l['xs'], l['yt'] - l['ys']) / fr[t],
                     l['site'].split('<-')[0], l['xs'], l['ys']))
    print(f'{stamp} "{label}": {sum(c.values())} jump(s) between cells')
    for k, v in c.most_common():
        L = [r[2] for r in rows if r[0] == k]
        S = Counter(r[3] for r in rows if r[0] == k).most_common(2)
        print(f'  {v:3d}  {k:50s} length p50 {np.median(L):.2f} r   {S}')
    if '-v' in sys.argv:
        for r in sorted(rows):
            print(f'    {r[0]:50s} t={r[1]} ({r[4]:.0f},{r[5]:.0f}) {r[2]:.2f} r {r[3]}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
