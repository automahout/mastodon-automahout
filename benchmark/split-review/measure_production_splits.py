"""Score the splits a GUI run actually made, by outcome, from its origin dump.

The two pieces of one split are the two SPLIT spots of a timepoint that are each other's nearest
SPLIT spot in the [3/6] snapshot. A split is RIGHT when its pieces sit on two different silver-truth
cells, neither on background, and neither on a cell that another (non-fp) spot already sits on --
the same outcome evaluate_decision_rule.py predicts offline.

    python measure_production_splits.py [stamp]
"""
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import measure_split_decisions as m  # noqa: E402
import prototype_splitter as p  # noqa: E402


def main():
    stamp = sys.argv[1] if len(sys.argv) > 1 else m.newest_stamp()
    path = Path(f'/tmp/origin-dump-{stamp}.tsv')
    snaps = defaultdict(list)
    head = None
    with open(path) as fh:
        head = fh.readline().rstrip('\n').split('\t')
        ix = {n: i for i, n in enumerate(head)}
        for line in fh:
            q = line.rstrip('\n').split('\t')
            if q[0] == 'rev' or not q[ix['label']].startswith('[3/6]'):
                continue
            snaps[int(q[ix['snapshot']])].append(
                (int(q[ix['t']]), float(q[ix['x']]), float(q[ix['y']]), q[ix['reason']], q[ix['tag']]))
    per_t = {}
    for s in sorted(snaps):
        by_t = defaultdict(list)
        for row in snaps[s]:
            by_t[row[0]].append(row)
        per_t.update(by_t)
    out = Counter()
    split_ts = Counter()
    for t, spots in sorted(per_t.items()):
        if p.seg(t) is None:
            continue
        pieces = [(x, y) for _, x, y, w, _ in spots if w == 'SPLIT']
        rest = [(x, y) for _, x, y, w, tag in spots if w != 'SPLIT' and tag != 'fp']
        if len(pieces) < 2:
            continue
        P = np.array(pieces)
        D = np.hypot(P[:, None, 0] - P[None, :, 0], P[:, None, 1] - P[None, :, 1])
        np.fill_diagonal(D, np.inf)
        nearest = D.argmin(1)
        taken = {p.truth_at(t, x, y) for x, y in rest}
        for a in range(len(P)):
            b = nearest[a]
            if b < a or nearest[b] != a:
                continue                      # each mutual pair once
            la, lb = p.truth_at(t, *P[a]), p.truth_at(t, *P[b])
            if la == 0 or lb == 0:
                kind = 'a piece on background'
            elif la == lb:
                kind = 'both pieces on one cell'
            elif la in taken or lb in taken:
                kind = 'a piece duplicates another detection'
            else:
                kind = 'RIGHT'
            out[kind] += 1
            split_ts[t] += 1
    n = sum(out.values())
    print(f'{path}: {n} split(s) over {len(split_ts)} timepoint(s)')
    for k, v in out.most_common():
        print(f'  {v:5d}  {k}')
    print(f'precision by outcome: {out["RIGHT"] / max(n, 1):.0%}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
