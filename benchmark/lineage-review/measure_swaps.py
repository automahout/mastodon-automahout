"""Swapped links: a link A->B beside a track that ends (E at t, no successor) and one that starts
(S at t+1, no predecessor), where A->S and E->B would join both instead.

Seen by the user at t=13->14 of run 235510: two sister cells, the detector missed one at t=14,
linkOrphanStarts gave the one detection to the wrong sister (9.4 um against 9.9), and the missed one,
added afterwards by recoverMissingPredecessors, was left with no predecessor. One track jumped
between the cells and the other broke.

For every candidate quadruple, against the tracking markers: is the current link A->B wrong, and
would the swap be right (A and S on one truth track, E and B on one)? Both new links must be within
D, the frame median radius times K. Among several candidates for one link the swap with the smallest
d(A,S)+d(E,B) is taken.

    python measure_swaps.py stamp [snapshot-label-prefix]
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
    same_len = [d(l['src'], l['dst']) / fr[spots[l['src']]['t']] for l in links
                if l['tt'] == l['ts'] + 1 and on.get(l['src']) and on.get(l['src']) == on.get(l['dst'])
                and l['src'] in spots and l['dst'] in spots]
    print(f'{stamp} "{label}": same-track one-frame link length / frame radius p50 {np.median(same_len):.2f} '
          f'p95 {np.percentile(same_len, 95):.2f} p99 {np.percentile(same_len, 99):.2f}')
    for K in (1.0, 1.5, 2.0, 3.0):
        res = Counter()
        for A in list(spots):
            if len(succ[A]) != 1 or spots[A]['tag'] == 'fp':
                continue
            B = succ[A][0]
            if B not in spots or len(pred[B]) != 1 or spots[B]['t'] != spots[A]['t'] + 1:
                continue
            t = spots[A]['t']
            D = K * fr[t]
            ends = [E for E in by_t[t] if E != A and not succ[E] and d(E, B) <= D]
            starts = [S for S in by_t[t + 1] if S != B and not pred[S] and d(A, S) <= D]
            if not ends or not starts:
                continue
            best = min(((E, S) for E in ends for S in starts), key=lambda es: d(A, es[1]) + d(es[0], B))
            E, S = best
            # only when the swap is no longer in total than the link it replaces plus... nothing: it joins two
            if d(A, S) + d(E, B) > 2 * D:
                continue
            was_right = on[A] and on[A] == on[B]
            swap_right = on[A] and on[A] == on[S] and on[E] and on[E] == on[B]
            res['candidates'] += 1
            res['current link right' if was_right else 'current link wrong'] += 1
            res['swap right' if swap_right else 'swap wrong'] += 1
            if not was_right and swap_right:
                res['FIXED'] += 1
                if '-v' in sys.argv and K == 1.0:
                    print(f'    fixed at t={t}: A={A} B={B} E={E} S={S} d(A,B)={d(A, B):.1f} d(A,S)={d(A, S):.1f} '
                          f'd(E,B)={d(E, B):.1f} frame r={fr[t]:.1f}')
            if was_right and not swap_right:
                res['BROKEN'] += 1
        print(f'  K={K}: {dict(res)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
