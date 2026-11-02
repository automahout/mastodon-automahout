"""Repairing a 2->1 merge from the frames before it, scored frame by frame against silver truth.

measure_convergence.py finds the onset: a spot S at t+1 that one track runs into while a second
track ends at t with its centre inside S. Here S is split in two, and the split is carried forward
along S's successors while the merge lasts.

The two pieces are placed by a two-component fit over the object's pixels, seeded at the two cells'
last known positions (shifted by S's own motion) rather than along the object's main axis. The
neighbouring frames say there are two cells and roughly where; the fit only has to say where they
are now. Compared against the unseeded fit (the split pass's placement).

Carrying forward stops when the merge is over: S has no single successor, the successor no longer
holds both pieces' predicted positions, or another spot appears on one of them (the cells have
parted and the detector sees both again).

A repaired frame is RIGHT when its two pieces sit on two different silver-truth cells.

    python evaluate_convergence_repair.py [stamp] [snapshot-label-prefix]
"""
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'split-review'))
import measure_convergence as mc  # noqa: E402
import measure_links as ml  # noqa: E402
import prototype_splitter as ps  # noqa: E402

SIGMA_LEVEL = 5.0
MIN_SEP_FRAME_R = 0.9     # = AutoCorrectOrchestrator.CONVERGENCE_MIN_SEPARATION; two centres closer than this many frame-median radii are one cell
ITERS = 40


def seeded_fit(t, s, seeds, frame_r):
    """Two components over the object under s, started at seeds (um). None when it does not hold."""
    d = dict(x=s['x'], y=s['y'], r=max(s['a'], s['r']), frameR=frame_r)
    got = ps.object_mask(t, d, SIGMA_LEVEL)
    if got is None:
        return None
    obj, x0, y0, _, _, _ = got
    ys, xs = np.nonzero(obj)
    X = np.stack([xs, ys], 1).astype(float)
    if len(X) < 20:
        return None
    c1 = np.cov(X.T) + 0.25 * np.eye(2)
    mu = np.array([[sx / ps.VOXEL_UM - x0, sy / ps.VOXEL_UM - y0] for sx, sy in seeds])
    cov = np.array([c1 / 2, c1 / 2])
    pi = np.array([0.5, 0.5])
    for _ in range(ITERS):
        lp = np.stack([np.log(pi[k]) + ps._gauss_ll(X, mu[k], cov[k]) for k in range(2)], 1)
        mx = lp.max(1, keepdims=True)
        resp = np.exp(lp - mx)
        resp /= resp.sum(1, keepdims=True)
        nk = resp.sum(0)
        if (nk < 5).any():
            return None
        pi = nk / nk.sum()
        mu = (resp.T @ X) / nk[:, None]
        cov = np.array([((resp[:, k:k + 1] * (X - mu[k])).T @ (X - mu[k])) / nk[k] + 0.25 * np.eye(2)
                        for k in range(2)])
    if np.hypot(*(mu[0] - mu[1])) < MIN_SEP_FRAME_R * frame_r / ps.VOXEL_UM:
        return None
    return [((mu[k][0] + x0) * ps.VOXEL_UM, (mu[k][1] + y0) * ps.VOXEL_UM) for k in range(2)]


def unseeded_fit(t, s, frame_r):
    out = ps.split_gmm(t, dict(x=s['x'], y=s['y'], r=max(s['a'], s['r']), frameR=frame_r),
                       sigma_level=SIGMA_LEVEL, bic_margin=-1e9, min_sep_radii=MIN_SEP_FRAME_R)
    return out if len(out) == 2 else None


def outcome(t, pieces):
    if pieces is None:
        return 'refused'
    labs = [ps.truth_at(t, x, y) for x, y in pieces]
    if 0 in labs:
        return 'a piece on background'
    if labs[0] == labs[1]:
        return 'both on one cell'
    return 'RIGHT'


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
    spots = {k: v for k, v in mc.load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), snap).items()
             if v['tag'] != 'fp'}
    succ, pred = defaultdict(list), defaultdict(list)
    for l in links:
        if l['src'] in spots and l['dst'] in spots and l['tt'] == l['ts'] + 1:
            succ[l['src']].append(l['dst'])
            pred[l['dst']].append(l['src'])
    by_t = defaultdict(list)
    for k, s in spots.items():
        by_t[s['t']].append(k)
    frame_r = {t: float(np.median([spots[k]['r'] for k in ks])) for t, ks in by_t.items()}
    print(f'{stamp} snapshot {snap} "{label}"')

    onsets = []
    for k, s in spots.items():
        if not pred[k]:
            continue
        for q in by_t[s['t'] - 1]:
            if q in pred[k] or succ[q]:
                continue
            if mc.inside(s, spots[q]['x'], spots[q]['y']):
                onsets.append((k, pred[k][0], q))
                break

    res = {'seeded': Counter(), 'unseeded': Counter()}
    onset_res = {'seeded': Counter(), 'unseeded': Counter()}
    lengths, stops = [], Counter()
    for k, p, q in onsets:
        P, Q, S = spots[p], spots[q], spots[k]
        # S's own motion carries the pair: shift both by S's centre minus the pair's midpoint.
        dx, dy = S['x'] - (P['x'] + Q['x']) / 2, S['y'] - (P['y'] + Q['y']) / 2
        seeds = [(P['x'] + dx, P['y'] + dy), (Q['x'] + dx, Q['y'] + dy)]
        cur, n = k, 0
        while True:
            s = spots[cur]
            t = s['t']
            got = seeded_fit(t, s, seeds, frame_r[t])
            o = outcome(t, got)
            res['seeded'][o] += 1
            ou = outcome(t, unseeded_fit(t, s, frame_r[t]))
            res['unseeded'][ou] += 1
            if n == 0:
                onset_res['seeded'][o] += 1
                onset_res['unseeded'][ou] += 1
            n += 1
            if got is None:
                stops['the fit refused'] += 1
                break
            nxt = succ[cur]
            if len(nxt) != 1:
                stops['no single successor'] += 1
                break
            S2 = spots[nxt[0]]
            mx, my = S2['x'] - s['x'], S2['y'] - s['y']
            pred_pos = [(x + mx, y + my) for x, y in got]
            if not all(mc.inside(S2, x, y) for x, y in pred_pos):
                stops['successor no longer holds both'] += 1
                break
            if any(o2 != nxt[0] and math.hypot(spots[o2]['x'] - x, spots[o2]['y'] - y) < spots[o2]['r']
                   for o2 in by_t[S2['t']] for x, y in pred_pos):
                stops['another spot appears on a piece'] += 1
                break
            seeds, cur = pred_pos, nxt[0]
        lengths.append(n)
    print(f'onsets: {len(onsets)}; carried {sum(lengths)} frame(s), '
          f'per episode p50 {np.median(lengths) if lengths else 0:.0f}, max {max(lengths, default=0)}')
    print('carrying stopped because:', dict(stops))
    print('| placement | frames | right | both on one cell | a piece on background | refused |')
    print('|---|---|---|---|---|---|')
    for name in ('seeded', 'unseeded'):
        for tag, c in ((f'{name}, onset frame', onset_res[name]), (f'{name}, every carried frame', res[name])):
            tot = sum(c.values())
            print(f"| {tag} | {tot} | {c['RIGHT']} ({c['RIGHT'] / max(tot, 1):.0%}) | {c['both on one cell']} "
                  f"| {c['a piece on background']} | {c['refused']} |")
    return 0


if __name__ == '__main__':
    sys.exit(main())
