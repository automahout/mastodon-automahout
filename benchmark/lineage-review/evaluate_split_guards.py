"""Two additions to the merge repair, measured before they are built.

1. Refusing a split the image does not stand behind. The user was asked about a split whose second
   piece spanned the merged detection AND an undetected dividing neighbour beside it. Two guards:
     IN-S    both pieces' centres inside the merged detection's own ellipse
     ON-OBJ  both pieces' centres on the thresholded object
   For each rule (ONSET, END-START, END-DIVIDE): the splits made, and how many each guard keeps,
   right against wrong (silver truth: two pieces on two different cells).

2. A third answer, "one cell here, plus an undetected neighbour". The case: the lineage says two
   cells, the detection covers one, and the other is beside it undetected. Opportunity: a seed
   (a neighbouring-frame cell carried onto t) lies outside S and within one frame radius of a
   silver-truth cell no detection's centre is on. Placement: the thresholded object at that seed,
   minus S's ellipse, its centroid. RIGHT when that lands on the undetected cell.

    python evaluate_split_guards.py [stamp] [snapshot-label-prefix]
"""
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from scipy import ndimage as ndi

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'split-review'))
import evaluate_convergence_repair as ev  # noqa: E402
import measure_convergence as mc  # noqa: E402
import measure_links as ml  # noqa: E402
import prototype_splitter as ps  # noqa: E402

_labels = {}


def objects(t):
    """Thresholded objects of frame t (median + 5 spreads), labelled."""
    if t not in _labels:
        im, med, sd = ps.image(t)
        _labels[t] = ndi.label(im > med + ev.SIGMA_LEVEL * sd)[0]
    return _labels[t]


def on_object(t, x, y, d):
    got = ps.object_mask(t, d, ev.SIGMA_LEVEL)
    if got is None:
        return False
    obj, x0, y0 = got[0], got[1], got[2]
    c, r = int(round(x / ps.VOXEL_UM)) - x0, int(round(y / ps.VOXEL_UM)) - y0
    return 0 <= r < obj.shape[0] and 0 <= c < obj.shape[1] and bool(obj[r, c])


def place_neighbour(t, S, seed, frame_r):
    """Centroid of the object at seed outside S's ellipse, in um; None if there is none."""
    lab = objects(t)
    c, r = int(round(seed[0] / ps.VOXEL_UM)), int(round(seed[1] / ps.VOXEL_UM))
    if not (0 <= r < lab.shape[0] and 0 <= c < lab.shape[1]):
        return None
    k = lab[r, c]
    if k == 0:
        # nearest object pixel within one frame radius
        rad = int(math.ceil(frame_r / ps.VOXEL_UM))
        win = lab[max(0, r - rad):r + rad + 1, max(0, c - rad):c + rad + 1]
        ys, xs = np.nonzero(win)
        if len(ys) == 0:
            return None
        j = int(np.argmin((ys - min(r, rad)) ** 2 + (xs - min(c, rad)) ** 2))
        k = win[ys[j], xs[j]]
    ys, xs = np.nonzero(lab == k)
    keep = [not mc.inside(S, x * ps.VOXEL_UM, y * ps.VOXEL_UM) for x, y in zip(xs, ys)]
    xs, ys = xs[keep], ys[keep]
    if len(xs) < 20:
        return None
    return xs.mean() * ps.VOXEL_UM, ys.mean() * ps.VOXEL_UM


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

    def undetected_cells(t):
        s = ps.seg(t)
        if s is None:
            return set()
        hit = set()
        for k in by_t[t]:
            hit.add(ps.truth_at(t, spots[k]['x'], spots[k]['y']))
        return set(np.unique(s)) - hit - {0}

    cands = []
    for k, s in spots.items():
        if pred[k]:
            for q in by_t[s['t'] - 1]:
                if q not in pred[k] and not succ[q] and mc.inside(s, spots[q]['x'], spots[q]['y']):
                    cands.append(('ONSET', k, pred[k][0], q))
                    break
        if len(succ[k]) == 1:
            for n in by_t[s['t'] + 1]:
                if n != succ[k][0] and not pred[n] and mc.inside(s, spots[n]['x'], spots[n]['y']):
                    cands.append(('END-START', k, succ[k][0], n))
                    break
        if len(succ[k]) == 2 and all(mc.inside(s, spots[n]['x'], spots[n]['y']) for n in succ[k]):
            cands.append(('END-DIVIDE', k, succ[k][0], succ[k][1]))

    guard = defaultdict(Counter)
    depths = defaultdict(list)
    opp = defaultdict(Counter)
    undet_cache = {}
    for rule, k, a, b in cands:
        S, A, B = spots[k], spots[a], spots[b]
        t = S['t']
        dx, dy = S['x'] - (A['x'] + B['x']) / 2, S['y'] - (A['y'] + B['y']) / 2
        seeds = [(A['x'] + dx, A['y'] + dy), (B['x'] + dx, B['y'] + dy)]
        got = ev.seeded_fit(t, S, seeds, frame_r[t])
        if got is not None:
            right = ev.outcome(t, got) == 'RIGHT'
            d = dict(x=S['x'], y=S['y'], r=max(S['a'], S['r']), frameR=frame_r[t])
            in_s = all(mc.inside(S, x, y) for x, y in got)
            # NEIGHBOUR: no piece centre inside another detection's own radius at t (the split
            # pass's condition 3) -- the piece has reached into a cell that is already detected.
            clear = not any(math.hypot(spots[o]['x'] - x, spots[o]['y'] - y) < spots[o]['r']
                            for o in by_t[t] if o != k for x, y in got)
            on_obj = all(on_object(t, x, y, d) for x, y in got)
            tag = 'right' if right else 'wrong'
            guard[rule][f'made {tag}'] += 1
            if in_s:
                guard[rule][f'IN-S {tag}'] += 1
            if on_obj:
                guard[rule][f'ON-OBJ {tag}'] += 1
            if in_s and on_obj:
                guard[rule][f'both {tag}'] += 1
            if clear:
                guard[rule][f'NEIGHBOUR {tag}'] += 1
            # How deep inside a neighbour: the smallest distance from a piece to another detection's
            # centre, in that detection's radii. A duplicate sits on it; a right piece in a crowded
            # frame only grazes it.
            depth = min([math.hypot(spots[o]['x'] - x, spots[o]['y'] - y) / spots[o]['r']
                         for o in by_t[t] if o != k for x, y in got] or [9.9])
            depths[tag].append(depth)
            # DUPLICATE: a piece on a truth cell some other detection's centre is already on.
            others = {ps.truth_at(t, spots[o]['x'], spots[o]['y']) for o in by_t[t] if o != k} - {0}
            dup = any(ps.truth_at(t, x, y) in others for x, y in got)
            kind = 'duplicate' if dup else tag
            guard[rule][f'kind {kind}'] += 1
            if not clear:
                guard[rule][f'inside-neighbour {kind}'] += 1
        # The third answer: a seed outside S near an undetected truth cell.
        if t not in undet_cache:
            undet_cache[t] = undetected_cells(t)
        und = undet_cache[t]
        for sx, sy in seeds:
            if mc.inside(S, sx, sy):
                continue
            lab = ps.truth_at(t, sx, sy)
            near = lab in und
            if not near:
                # within one frame radius of an undetected cell?
                sg = ps.seg(t)
                if sg is not None:
                    rr = int(math.ceil(frame_r[t] / ps.VOXEL_UM))
                    c, r = int(round(sx / ps.VOXEL_UM)), int(round(sy / ps.VOXEL_UM))
                    win = sg[max(0, r - rr):r + rr + 1, max(0, c - rr):c + rr + 1]
                    near = bool(set(np.unique(win)) & und)
            opp[rule]['seed outside S'] += 1
            if near:
                opp[rule]['near an undetected cell'] += 1
            p = place_neighbour(t, S, (sx, sy), frame_r[t])
            if p is None:
                opp[rule]['placement: no object'] += 1
                continue
            lab = ps.truth_at(t, *p)
            opp[rule]['placement: on an undetected cell' if lab in und
                      else 'placement: on background' if lab == 0
                      else 'placement: on an already detected cell'] += 1

    print('\n1. guards on the splits the fit makes')
    print('| rule | made (right/wrong) | IN-S keeps | ON-OBJ keeps | both keep | NEIGHBOUR keeps |')
    print('|---|---|---|---|---|---|')
    for rule in ('ONSET', 'END-START', 'END-DIVIDE'):
        g = guard[rule]
        f = lambda n: f"{g[n + ' right']}/{g[n + ' wrong']}"  # noqa: E731
        print(f"| {rule} | {f('made')} | {f('IN-S')} | {f('ON-OBJ')} | {f('both')} | {f('NEIGHBOUR')} |")
    print('\n1c. splits by outcome, duplicates counted apart (all / those landing inside a neighbour):')
    for rule in ('ONSET', 'END-START', 'END-DIVIDE'):
        g = guard[rule]
        print(f"  {rule}: right {g['kind right']}/{g['inside-neighbour right']}, duplicate {g['kind duplicate']}/"
              f"{g['inside-neighbour duplicate']}, wrong {g['kind wrong']}/{g['inside-neighbour wrong']}")
    print('\n1b. depth of the deepest piece inside another detection (distance / its radius), splits '
          'that land inside one (< 1):')
    for tag in ('right', 'wrong'):
        d = sorted(v for v in depths[tag] if v < 1)
        print(f'  {tag}: {len(d)}: {[round(v, 2) for v in d]}')
    print('\n2. the third answer: a seed outside S')
    for rule in ('ONSET', 'END-START', 'END-DIVIDE'):
        print(f'  {rule}: {dict(opp[rule])}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
