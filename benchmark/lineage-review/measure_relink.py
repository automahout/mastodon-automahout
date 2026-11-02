"""A link A->B where an unlinked detection S at t+1 (no predecessor) lies nearer A than B does.

In the case the user showed (run 235510, t=13->14) and 9 of 55 jumps between cells in that run, A's
own cell was detected at t+1 with no predecessor, while A was linked to a neighbour's detection --
because S was added after A had been linked (recoverMissingPredecessors), or because the orphan linker
chose by a tie. Relink A->S when d(A,S) < F * d(A,B) and d(A,S) <= K frame radii; then, if a track end
E at t (no successor) lies within K radii of B, link E->B.

Scored against the tracking markers: FIXED (A->B was a jump between cells, A->S is on one cell),
BROKEN (A->B was on one cell, A->S is not), and the same for E->B.

    python measure_relink.py stamp [snapshot-label-prefix]
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
    succ, pred = defaultdict(list), defaultdict(list)
    for l in links:
        succ[l['src']].append(l['dst'])
        pred[l['dst']].append(l['src'])
    by_t = defaultdict(list)
    for k, s in spots.items():
        if s['tag'] != 'fp':
            by_t[s['t']].append(k)
    fr = {t: float(np.median([spots[k]['r'] for k in ks])) for t, ks in by_t.items()}
    on = {k: ml.track_at(s['t'], s['x'], s['y']) for k, s in spots.items()}
    d = lambda a, b: math.hypot(spots[a]['x'] - spots[b]['x'], spots[a]['y'] - spots[b]['y'])
    print(f'{stamp} "{label}"')
    for K in (1.0, 1.5, 2.0):
        for F in (1.0, 0.8, 0.6):
            c = Counter()
            for A in list(spots):
                if len(succ[A]) != 1 or spots[A]['tag'] == 'fp':
                    continue
                B = succ[A][0]
                if B not in spots or spots[B]['t'] != spots[A]['t'] + 1:
                    continue
                t = spots[A]['t']
                S = [s for s in by_t[t + 1] if s != B and not pred[s] and d(A, s) <= K * fr[t]
                     and d(A, s) < F * d(A, B)]
                if not S:
                    continue
                S = min(S, key=lambda s: d(A, s))
                was = on[A] and on[A] == on[B]
                now = on[A] and on[A] == on[S]
                c['relinked'] += 1
                c['FIXED' if now and not was else 'BROKEN' if was and not now else 'both right' if now
                  else 'both wrong'] += 1
                E = [e for e in by_t[t] if e != A and not succ[e] and d(e, B) <= K * fr[t]]
                if E:
                    E = min(E, key=lambda e: d(e, B))
                    c['E->B ' + ('right' if on[E] and on[E] == on[B] else 'wrong')] += 1
            print(f'  K={K} F={F}: {dict(c)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
