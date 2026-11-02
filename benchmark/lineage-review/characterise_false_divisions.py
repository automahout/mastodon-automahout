"""What the false divisions of a snapshot are, so the pass that could remove them can be chosen.

A division in the graph (a spot with two or more links out) is false unless the truth divides the
mother's track into the targets' tracks (measure_links.py). Each false one is sorted by what its
branches land on, against the tracking markers (01_GT/TRA):

  MOTHER OFF CELL      the mother is on no truth track
  DUPLICATE CHILD      two branches on the mother's own track: one cell detected twice next frame
  SPUR TO BACKGROUND   one branch continues the mother, another lands on no truth track
  STEALS NEIGHBOUR     one branch continues the mother, another lands on a different cell
  EARLY/LATE           the branches land on the mother's real daughters, but at the wrong frame
  OTHER                anything else

For each: the site that made the extra branch, the branch length in frames after the split, its
distance from the mother in median radii, and for STEALS NEIGHBOUR whether the neighbour's own
detection at the mother's frame was linked onward (if not, the neighbour lost its own track there).

    python characterise_false_divisions.py stamp [snapshot-label-prefix]
"""
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import measure_links as ml  # noqa: E402


def main():
    stamp = sys.argv[1]
    prefix = sys.argv[2] if len(sys.argv) > 2 else None
    links, label = ml.load_links(Path(f'/tmp/origin-dump-{stamp}.links.tsv'), prefix)
    tr = ml.tracks()
    succ, pred = defaultdict(list), defaultdict(list)
    pos = {}
    for l in links:
        succ[l['src']].append(l)
        pred[l['dst']].append(l)
        pos[l['src']] = (l['ts'], l['xs'], l['ys'])
        pos[l['dst']] = (l['tt'], l['xt'], l['yt'])
        l['a'] = ml.track_at(l['ts'], l['xs'], l['ys'])
        l['b'] = ml.track_at(l['tt'], l['xt'], l['yt'])

    def run_len(k):
        n = 0
        while len(succ.get(k, ())) == 1:
            k = succ[k][0]['dst']
            n += 1
        return n + 1

    def is_desc(child, anc, depth=3):
        return ml.is_descendant(tr, child, anc, depth)

    kinds = Counter()
    rows = []
    for src, ls in list(succ.items()):
        if len(ls) < 2:
            continue
        a = ls[0]['a']
        kids = [l['b'] for l in ls]
        daughters = {d for d, (bb, ee, p) in tr.items() if p == a}
        if a and len(daughters) >= 2 and len(set(kids) & daughters) >= 2:
            continue
        if not a:
            k = 'MOTHER OFF CELL'
        elif all(b == a for b in kids):
            k = 'DUPLICATE CHILD'
        elif a in kids and any(b == 0 for b in kids):
            k = 'SPUR TO BACKGROUND'
        elif a in kids:
            k = 'STEALS NEIGHBOUR'
        elif all(b and (b == a or is_desc(b, a) or is_desc(a, b)) for b in kids):
            k = 'EARLY/LATE'
        else:
            k = 'OTHER'
        kinds[k] += 1
        t = ls[0]['ts']
        # the extra branch: the one not on the mother's track, else the shorter
        cont = [l for l in ls if l['b'] == a]
        extra = [l for l in ls if l not in cont[:1]]
        e = min(extra, key=lambda l: run_len(l['dst']))
        d = np.hypot(e['xt'] - e['xs'], e['yt'] - e['ys'])
        rows.append((k, t, e['why'] + ':' + e['site'].split('<-')[0], run_len(e['dst']), d, e['b'],
                     ls[0]['xs'], ls[0]['ys']))
    print(f'{stamp} "{label}": {sum(kinds.values())} false division(s)')
    for k, v in kinds.most_common():
        print(f'  {v:3d}  {k}')
    print('\nkind\tt\tx\ty\textra branch made by\tbranch frames\tjump um\tbranch truth')
    for r in sorted(rows):
        print(f'{r[0]}\t{r[1]}\t{r[6]:.0f}\t{r[7]:.0f}\t{r[2]}\t{r[3]}\t{r[4]:.1f}\t{r[5]}')
    by = defaultdict(list)
    for r in rows:
        by[r[0]].append(r)
    print('\nper kind: extra branch length (frames) p10/p50/p90, jump um p50, sites')
    for k, rs in by.items():
        L = np.array([r[3] for r in rs]); D = np.array([r[4] for r in rs])
        print(f'  {k:20s} {np.percentile(L, [10, 50, 90]).round(1)}  {np.median(D):.1f}  '
              f'{dict(Counter(r[2] for r in rs).most_common(3))}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
