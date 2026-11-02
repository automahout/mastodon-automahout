"""Two tracks running into one spot: how often, and can a rule see it without catching single cells.

The pattern the user keeps pointing at: up to frame t two cells carry two spots and two tracks; at
t+1 the detector puts ONE large spot over both. A spot takes one incoming link, so one track goes
on into the large spot and the other simply ends at t.

Candidate (model-free, from the graph alone):
  S at t+1 has a predecessor P at t, and another spot Q at t has NO successor,
  Q's centre lies inside S's ellipse (scaled by REACH),
  and no other spot at t+1 lies within Q's own radius of Q (Q's cell did not go elsewhere).

Scored against tracking truth (01_GT/TRA): S is a real merge when two or more TRA markers sit
inside its ellipse. The base: every spot with a predecessor.

    python measure_convergence.py [stamp] [snapshot-label-prefix]
"""
import math
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import measure_links as ml  # noqa: E402

REACH = 1.0
EXCLUDE = 'free'   # 'any', 'free' or 'none'; see the candidate rule


def load_spots(path, snap):
    out = {}
    with open(path) as fh:
        fh.readline()
        for line in fh:
            q = line.rstrip('\n').split('\t')
            # rev, snapshot, label, t, x, y, z, radius, a, b, theta, tag, protected, reason, site, id
            if q[0] == 'rev' or int(q[1]) != snap:
                continue
            out[int(q[15])] = dict(t=int(q[3]), x=float(q[4]), y=float(q[5]), r=float(q[7]),
                                   a=float(q[8]), b=float(q[9]), th=float(q[10]), tag=q[11])
    return out


def inside(s, x, y, reach=REACH):
    dx, dy = x - s['x'], y - s['y']
    c, sn = math.cos(s['th']), math.sin(s['th'])
    u, v = dx * c + dy * sn, -dx * sn + dy * c
    a, b = max(s['a'], 1e-9) * reach, max(s['b'], 1e-9) * reach
    return (u / a) ** 2 + (v / b) ** 2 <= 1.0


def truth_inside(s):
    m = ml.tra(s['t'])
    if m is None:
        return None
    labs, cents = ml._cent[s['t']]
    return sum(inside(s, cx, cy) for cx, cy in cents)


def main():
    stamp = sys.argv[1] if len(sys.argv) > 1 else ml.newest_stamp()
    prefix = sys.argv[2] if len(sys.argv) > 2 else None
    lpath = Path(f'/tmp/origin-dump-{stamp}.links.tsv')
    links, label = ml.load_links(lpath, prefix)
    snap = None
    with open(lpath) as fh:
        fh.readline()
        for line in fh:
            q = line.split('\t')
            if q[2] == label:
                snap = int(q[1])
    spots = load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), snap)
    spots = {k: v for k, v in spots.items() if v['tag'] != 'fp'}
    succ, pred = defaultdict(list), defaultdict(list)
    for l in links:
        if l['src'] in spots and l['dst'] in spots and l['tt'] == l['ts'] + 1:
            succ[l['src']].append(l['dst'])
            pred[l['dst']].append(l['src'])
    by_t = defaultdict(list)
    for k, s in spots.items():
        by_t[s['t']].append(k)
    print(f'{stamp} snapshot {snap} "{label}": {len(spots)} spots, {len(links)} links')

    cands = {}
    for k, s in spots.items():
        if not pred[k]:
            continue
        t0 = s['t'] - 1
        for q in by_t[t0]:
            if q in pred[k] or succ[q]:
                continue
            Q = spots[q]
            if not inside(s, Q['x'], Q['y']):
                continue
            # Q's cell went elsewhere only if a spot at t+1 sits on it AND nothing claimed that
            # spot; one that already has a predecessor is another cell's continuation.
            if EXCLUDE == 'any' and any(
                    o != k and math.hypot(spots[o]['x'] - Q['x'], spots[o]['y'] - Q['y']) < Q['r']
                    for o in by_t[s['t']]):
                continue
            if EXCLUDE == 'free' and any(
                    o != k and not pred[o]
                    and math.hypot(spots[o]['x'] - Q['x'], spots[o]['y'] - Q['y']) < Q['r']
                    for o in by_t[s['t']]):
                continue
            P = spots[pred[k][0]]
            cands[k] = dict(q=q, p=pred[k][0],
                            area=s['a'] * s['b'] / (P['a'] * P['b'] + Q['a'] * Q['b']),
                            sep=math.hypot(P['x'] - Q['x'], P['y'] - Q['y']) / (P['r'] + Q['r']))
    with_pred = [k for k in spots if pred[k]]
    tin = {k: truth_inside(spots[k]) for k in with_pred}
    tin = {k: v for k, v in tin.items() if v is not None}
    real = {k for k, v in tin.items() if v >= 2}
    flagged = [k for k in cands if k in tin]
    hit = [k for k in flagged if k in real]
    print(f'spots with a predecessor: {len(tin)}, covering 2+ truth cells: {len(real)} '
          f'({len(real) / max(len(tin), 1):.1%})')
    print(f'flagged: {len(flagged)}, of which cover 2+ truth cells: {len(hit)} '
          f'({len(hit) / max(len(flagged), 1):.0%}); recall {len(hit)}/{len(real)}')
    for name, keep in (('flagged & real', hit), ('flagged & single', [k for k in flagged if k not in real])):
        if keep:
            ar = np.array([cands[k]['area'] for k in keep])
            sp = np.array([cands[k]['sep'] for k in keep])
            print(f'  {name}: area(S)/(area P+Q) p10/p50/p90 {np.percentile(ar, [10, 50, 90]).round(2)}, '
                  f'|P-Q|/(rP+rQ) {np.percentile(sp, [10, 50, 90]).round(2)}')
    # How the real merges that were NOT flagged look.
    why = defaultdict(int)
    for k in real - set(flagged):
        if not pred[k]:
            why['no predecessor'] += 1
            continue
        t0 = spots[k]['t'] - 1
        ends = [q for q in by_t[t0] if not succ[q] and q not in pred[k]]
        near = [q for q in ends if inside(spots[k], spots[q]['x'], spots[q]['y'], 1.5)]
        why['an ending track within 1.5x the ellipse' if near else 'no track ends next to it'] += 1
    print('  real merges not flagged:', dict(why))
    # A merge lasting several frames is flagged at its first frame only; the frames after it are
    # repaired by carrying the repair forward. Count EPISODES: a real merge whose predecessor is also
    # a real merge continues one.
    onsets = [k for k in real if not any(p in real for p in pred[k])]
    caught = [k for k in onsets if k in cands]
    cont = defaultdict(int)
    for k in onsets:
        if k in cands:
            continue
        t0 = spots[k]['t'] - 1
        under = [q for q in by_t[t0] if inside(spots[k], spots[q]['x'], spots[q]['y'], 1.5)]
        cont[f'{len(under)} spot(s) at t-1 under it'] += 1
    print(f'  merge episodes (onsets): {len(onsets)}, flagged at onset: {len(caught)}; '
          f'missed onsets by spots under them at t-1: {dict(cont)}')
    chain = []
    for k in caught:
        n, cur = 1, k
        while len(succ[cur]) == 1 and succ[cur][0] in real:
            cur = succ[cur][0]
            n += 1
        chain.append(n)
    if chain:
        print(f'  flagged episodes last {np.percentile(chain, [10, 50, 90])} frame(s); '
              f'{sum(chain)} merged spots covered by carrying forward')
    return 0


if __name__ == '__main__':
    sys.exit(main())
