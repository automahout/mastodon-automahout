"""How long a real lineage actually waits between successive divisions.

The refractory rule cuts a division it judges too soon after an ancestor's. The
run reported cutting 147 of the 147 it examined, which is either a threshold set
far too high or a pipeline fabricating divisions. This settles which.

CTC tracking truth, man_track.txt: "label start end parent". A division event is
a track with a parent; the interval this rule thresholds is that track's start
minus its parent's own start, for parents that were themselves born by division.
"""
from pathlib import Path

import numpy as np

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'


def intervals(seq):
    rows = [tuple(map(int, l.split()))
            for l in open(ROOT / f'{seq}_GT' / 'TRA' / 'man_track.txt') if l.strip()]
    start = {r[0]: r[1] for r in rows}
    parent = {r[0]: r[3] for r in rows}
    out = []
    for lab, p in parent.items():
        if p == 0 or parent.get(p, 0) == 0:
            continue          # no ancestor division to measure against
        out.append(start[lab] - start[p])
    return np.array(sorted(out)), sum(1 for p in parent.values() if p != 0)


def main():
    for seq in ('01', '02'):
        iv, divs = intervals(seq)
        if len(iv) == 0:
            continue
        print(f"\nseq {seq}: {divs} division events, {len(iv)} with a measurable ancestor")
        print(f"  interval (tp): min={iv.min()} p01={np.percentile(iv, 1):.0f} "
              f"p05={np.percentile(iv, 5):.0f} median={np.median(iv):.0f} max={iv.max()}")
        print(f"  {'threshold':<12}{'REAL divisions cut':>20}")
        for t in (10, 15, 20, 25, 29, 31, 33, 40):
            print(f"  {t:<12}{100 * (iv < t).mean():>19.1f}%")


if __name__ == '__main__':
    main()
