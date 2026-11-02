"""Can true and false divisions be told apart by their geometry, and why does a neighbour get stolen?

For every division in a snapshot (a spot with two links out), true or false as in measure_links.py:
  near, far      each branch's jump from the mother, in the frame's median radii
  angle          between the two branches' jumps, degrees (a real division throws them apart: ~180)
  area ratio     smaller child over larger (ellipse areas)
  child/mother   mean child area over mother area
For each STEALS NEIGHBOUR (characterise_false_divisions.py), what the neighbour's own detection at
the mother's frame did: none there (missed), there and linked onward elsewhere, or there and ended.

    python division_features.py stamp [snapshot-label-prefix]
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
import characterise_errors as ce  # noqa: E402


def main():
    stamp = sys.argv[1]
    prefix = sys.argv[2] if len(sys.argv) > 2 else 'auto.correct'
    links, label = ml.load_links(Path(f'/tmp/origin-dump-{stamp}.links.tsv'), prefix)
    snap, _ = me.snapshot_of(stamp, prefix)
    spots = mc.load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), snap)
    tr = ml.tracks()
    succ = defaultdict(list)
    for l in links:
        succ[l['src']].append(l['dst'])
    med_r = defaultdict(list)
    for s in spots.values():
        med_r[s['t']].append(s['r'])
    med_r = {t: float(np.median(v)) for t, v in med_r.items()}
    on = {}
    for k, s in spots.items():
        on[k] = ml.track_at(s['t'], s['x'], s['y'])
    by_track_t = defaultdict(list)
    for k, s in spots.items():
        if on[k]:
            by_track_t[(on[k], s['t'])].append(k)
    area = lambda s: math.pi * s['a'] * s['b']
    rows = []
    fate = Counter()
    for m, kids in succ.items():
        if len(kids) != 2 or m not in spots or any(k not in spots for k in kids):
            continue
        M = spots[m]
        a = on[m]
        bs = [on[k] for k in kids]
        daughters = {d for d, (bb, ee, p) in tr.items() if p == a}
        true = bool(a) and len(set(bs) & daughters) >= 2
        v = [(spots[k]['x'] - M['x'], spots[k]['y'] - M['y']) for k in kids]
        d = sorted(math.hypot(*u) / med_r[M['t']] for u in v)
        ang = math.degrees(math.acos(max(-1, min(1, (v[0][0] * v[1][0] + v[0][1] * v[1][1])
                                                  / max(1e-9, math.hypot(*v[0]) * math.hypot(*v[1]))))))
        ar = [area(spots[k]) for k in kids]
        kind = 'TRUE' if true else 'FALSE'
        if not true and a and a in bs and any(b and b != a for b in bs):
            kind = 'STEAL'
            nb = [b for b in bs if b and b != a][0]
            mine = by_track_t.get((nb, M['t']), [])
            if not mine:
                fate['neighbour missed at the mother\'s frame'] += 1
            elif any(succ.get(k) for k in mine):
                fate['neighbour detected, linked onward elsewhere'] += 1
            else:
                fate['neighbour detected, its track ends there'] += 1
        br = ce.interior(M['t'], M['x'], M['y'], M['r'])
        rows.append((kind, d[0], d[1], ang, min(ar) / max(ar), np.mean(ar) / area(M), br))
        if '-b' in sys.argv:
            print(f'  B {kind:5s} t={M["t"]} near {d[0]:.2f} angle {ang:.0f} mother bright {br:.1f}')
        if '-v' in sys.argv and ((kind == 'TRUE') != (d[0] >= 0.4 and ang >= 90)):
            print(f'  {kind:5s} t={M["t"]} ({M["x"]:.0f},{M["y"]:.0f}) near {d[0]:.2f} far {d[1]:.2f} angle {ang:.0f}')
    R = defaultdict(list)
    for r in rows:
        R[r[0]].append(r[1:])
        if r[0] == 'STEAL':
            R['FALSE'].append(r[1:])
    print(f'{stamp} "{label}"')
    print('kind   n   near(r) p50   far(r) p10/50/90     angle p10/50/90     area ratio p10/50   child/mother p50')
    for k in ('TRUE', 'FALSE', 'STEAL'):
        X = np.array(R[k])
        if not len(X):
            continue
        p = lambda c, q: np.percentile(X[:, c], q).round(2)
        print(f'{k:6s} {len(X):3d}  {p(0, 50)}   {p(1, [10, 50, 90])}   {p(2, [10, 50, 90]).round(0)}   '
              f'{p(3, [10, 50])}   {p(4, 50)}')
    print('\nstolen neighbour, at the mother\'s frame:')
    for k, v in fate.most_common():
        print(f'  {v:3d}  {k}')
    # simple rules: how many true kept / false caught
    T, F = np.array(R['TRUE']), np.array(R['FALSE'])
    print('\nrule                               true kept   false caught')
    for name, f in (('angle >= 90', lambda X: X[:, 2] >= 90), ('angle >= 120', lambda X: X[:, 2] >= 120),
                    ('far/near <= 2', lambda X: X[:, 1] <= 2 * np.maximum(X[:, 0], 0.3)),
                    ('far/near <= 3', lambda X: X[:, 1] <= 3 * np.maximum(X[:, 0], 0.3)),
                    ('angle>=90 & far/near<=3', lambda X: (X[:, 2] >= 90) & (X[:, 1] <= 3 * np.maximum(X[:, 0], 0.3))),
                    ('near >= 0.3', lambda X: X[:, 0] >= 0.3), ('near >= 0.4', lambda X: X[:, 0] >= 0.4),
                    ('near >= 0.5', lambda X: X[:, 0] >= 0.5),
                    ('near >= 0.4 & angle >= 90', lambda X: (X[:, 0] >= 0.4) & (X[:, 2] >= 90)),
                    ('near >= 0.3 & angle >= 120', lambda X: (X[:, 0] >= 0.3) & (X[:, 2] >= 120)),
                    ('child/mother <= 0.8', lambda X: X[:, 4] <= 0.8),
                    ('near>=0.4 & angle>=90 & c/m<=0.8', lambda X: (X[:, 0] >= 0.4) & (X[:, 2] >= 90) & (X[:, 4] <= 0.8))):
        print(f'  {name:32s} {f(T).sum():3d}/{len(T)}      {(~f(F)).sum():3d}/{len(F)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
