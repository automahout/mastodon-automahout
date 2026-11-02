#!/usr/bin/env python3
"""Can a track gap be closed by nearest-neighbour, and up to how many timepoints?

Nothing in this pipeline links across a gap: every linking path goes t -> t-1, and pruneLongLinks
deletes any edge spanning more than one timepoint outright. So a cell the detector misses for two
frames splits its lineage in two, permanently. Before allowing such a link, this measures how often
the nearest object g frames later is actually the same cell, on the CTC tracking ground truth.

For every ground-truth track and every t where it exists at both t and t+g, the cell is treated as
missing in between and the nearest object at t+g (by centroid) is compared with the truth. A second
column restricts the candidates the way the pipeline does -- only objects that are themselves
orphaned at t+g, i.e. whose track has no object at t+g-1 -- which is the population a gap-closing
pass actually chooses among.

usage: ctc_centroids.py first (it writes /tmp/ctc_centroids.pkl), then measure.py [path-to-pkl]
"""
import pickle
import sys
from pathlib import Path

PIXEL_UM = 0.645

pkl = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/ctc_centroids.pkl")
data = pickle.load(open(pkl, "rb"))

print(f"{'gap':>4} {'seq':>4} {'n':>7} {'NN right':>9} {'orphans only':>13} "
      f"{'disp p50':>9} {'disp p99':>9}")
for gap in (1, 2, 3, 4):
    for seq in ("01", "02"):
        frames = data[seq]["frames"]
        ts = sorted(frames)
        n = right = right_orphan = 0
        disps = []
        for t in ts:
            if t + gap not in frames:
                continue
            here, there = frames[t], frames[t + gap]
            prev_there = frames.get(t + gap - 1, {})
            # the population a gap-closing pass may link to: orphans at t+gap
            orphans = {l: v for l, v in there.items() if l not in prev_there}
            for label, v in here.items():
                if label not in there:
                    continue
                n += 1
                x, y = v[0], v[1]
                d = {l: (w[0] - x) ** 2 + (w[1] - y) ** 2 for l, w in there.items()}
                if min(d, key=d.get) == label:
                    right += 1
                disps.append((d[label] ** 0.5) * PIXEL_UM)
                pool = dict(orphans)
                pool[label] = there[label]  # the truth is the orphan we are trying to re-home
                do = {l: (w[0] - x) ** 2 + (w[1] - y) ** 2 for l, w in pool.items()}
                if min(do, key=do.get) == label:
                    right_orphan += 1
        disps.sort()
        p50 = disps[len(disps) // 2]
        p99 = disps[min(len(disps) - 1, int(0.99 * len(disps)))]
        print(f"{gap:>4} {seq:>4} {n:>7} {100.0 * right / n:>8.1f}% "
              f"{100.0 * right_orphan / n:>12.1f}% {p50:>8.2f} {p99:>8.2f}")
