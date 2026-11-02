"""How much of the lineage damage sits next to a detection that covers two cells.

The hypothesis (the user's): two cells come together, are detected as ONE spot for a frame or
two, and separate again. A spot cannot take two incoming links, so one track breaks there, and
when the cells part the survivor either "divides" or a link jumps to the wrong cell.

For one snapshot this marks MERGED spots -- two or more truth (TRA) markers within the frame's
median spot radius of the centre, independent of the spot's own radius -- and, for each kind of
damage measure_links.py finds, counts how much lies within WINDOW frames and REACH_RADII median
radii of a merged spot. The base rate is the same test applied to correct same-track links.

    python measure_merge_events.py [stamp] [snapshot-label-prefix]
"""
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import measure_links as ml  # noqa: E402

WINDOW = 2
REACH_RADII = 3.0


def load_spots(path, snap):
    out = []
    with open(path) as fh:
        head = fh.readline().rstrip('\n').split('\t')
        ix = {n: i for i, n in enumerate(head)}
        for line in fh:
            q = line.rstrip('\n').split('\t')
            if q[0] == 'rev' or int(q[ix['snapshot']]) != snap:
                continue
            out.append((int(q[ix['t']]), float(q[ix['x']]), float(q[ix['y']]), float(q[ix['radius']]),
                        q[ix['tag']]))
    return out


def main():
    stamp = sys.argv[1] if len(sys.argv) > 1 else ml.newest_stamp()
    prefix = sys.argv[2] if len(sys.argv) > 2 else None
    lpath = Path(f'/tmp/origin-dump-{stamp}.links.tsv')
    links, label = ml.load_links(lpath, prefix)
    # the snapshot number of that label
    snap = None
    with open(lpath) as fh:
        fh.readline()
        for line in fh:
            q = line.split('\t')
            if q[2] == label:
                snap = int(q[1])
    spots = load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), snap)
    print(f'snapshot {snap} "{label}": {len(spots)} spots, {len(links)} links')
    by_t = defaultdict(list)
    for t, x, y, r, tag in spots:
        if tag != 'fp':
            by_t[t].append((x, y, r))
    med_r = {t: np.median([r for _, _, r in v]) for t, v in by_t.items()}
    merged = defaultdict(list)
    n_spots = n_merged = 0
    for t, v in by_t.items():
        ml.tra(t)
        if t not in ml._cent:
            continue
        labs, cents = ml._cent[t]
        for x, y, r in v:
            n_spots += 1
            if (np.hypot(cents[:, 0] - x, cents[:, 1] - y) < med_r[t]).sum() >= 2:
                merged[t].append((x, y))
                n_merged += 1
    print(f'merged spots (two truth cells within the median radius): {n_merged} of {n_spots} '
          f'({n_merged / max(n_spots, 1):.1%})\n')

    def near_merge(t, x, y):
        for dt in range(-WINDOW, WINDOW + 1):
            for mx, my in merged.get(t + dt, ()):
                if np.hypot(mx - x, my - y) < REACH_RADII * med_r.get(t + dt, med_r.get(t, 10.0)):
                    return True
        return False

    tr = ml.tracks()
    kinds = defaultdict(list)
    out_of = defaultdict(list)
    for l in links:
        a, b = ml.track_at(l['ts'], l['xs'], l['ys']), ml.track_at(l['tt'], l['xt'], l['yt'])
        l['a'], l['b'] = a, b
        out_of[(l['ts'], l['src'])].append(l)
        if a is None or b is None or a == 0 or b == 0:
            continue
        if a == b:
            k = 'same track (base rate)'
        elif tr.get(b, (0, 0, 0))[2] == a or ml.is_descendant(tr, b, a):
            continue
        else:
            k = 'unnatural link'
        kinds[k].append(near_merge(l['ts'], l['xs'], l['ys']))
    for key, ls in out_of.items():
        if len(ls) < 2:
            continue
        a = ls[0].get('a')
        daughters = {d for d, (bb, ee, p) in tr.items() if p == a}
        if not (a and len(daughters) >= 2 and len({l.get('b') for l in ls} & daughters) >= 2):
            kinds['false division'].append(near_merge(ls[0]['ts'], ls[0]['xs'], ls[0]['ys']))
    # truth divisions followed into one daughter only
    children = defaultdict(list)
    for d, (bb, ee, p) in tr.items():
        if p:
            children[p].append(d)
    ts_present = {l['ts'] for l in links}
    for m, ds in children.items():
        end = tr[m][1]
        if len(ds) < 2 or end not in ts_present:
            continue
        hit = {l.get('b') for l in links if l['ts'] == end and l.get('a') == m} & set(ds)
        if len(hit) == 1:
            ml.tra(end)
            labs, cents = ml._cent[end]
            k = np.nonzero(labs == m)[0]
            if len(k):
                kinds['division followed into one daughter only'].append(near_merge(end, *cents[k[0]]))
    print(f'| damage | count | within {WINDOW} frames of a merged spot |')
    print('|---|---|---|')
    for k in ('unnatural link', 'false division', 'division followed into one daughter only',
              'same track (base rate)'):
        v = kinds.get(k, [])
        print(f'| {k} | {len(v)} | {sum(v)} ({sum(v) / max(len(v), 1):.0%}) |')
    return 0


if __name__ == '__main__':
    sys.exit(main())
