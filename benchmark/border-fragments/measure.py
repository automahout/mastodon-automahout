#!/usr/bin/env python3
"""What would "delete short lineages that touch the image border" cost if tracking were perfect?

The pipeline's unit here is a weakly-connected component of the link graph -- a whole lineage tree,
not a single cell cycle. With correct linking a lineage tree spans essentially the whole movie, so a
rule that deletes SHORT border-touching components should cost almost nothing on ground truth; what
it removes in a real run is fragments that exist only because linking failed. This measures the
first half of that claim, which is the safety property: the share of ground-truth objects a given
threshold would delete when the tracking is right.

A lineage touches the border when any of its objects' pixels reach column 0 / W-1 or row 0 / H-1.

usage: ctc_centroids.py first (it writes /tmp/ctc_centroids.pkl), then measure.py [path-to-pkl]
"""
import pickle
import sys
from pathlib import Path

PIXEL_UM = 0.645
MARGINS_PX = (0, 8, 16, 31)

pkl = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/ctc_centroids.pkl")
data = pickle.load(open(pkl, "rb"))
root = Path.home() / "Repositories/CTC_dataset/Fluo-N2DL-HeLa"

summary = {}
for seq in ("01", "02"):
    h, w = data[seq]["shape"]
    frames = data[seq]["frames"]
    rows = [tuple(int(x) for x in l.split())
            for l in open(root / f"{seq}_GT/TRA/man_track.txt") if len(l.split()) == 4]
    # lineage tree = union of a founder and all its descendants
    parent = {label: (par if par > 0 else label) for label, _, _, par in rows}

    def founder(label):
        seen = []
        while parent[label] != label:
            seen.append(label)
            label = parent[label]
        for s in seen:
            parent[s] = label
        return label

    span, border, count = {}, {}, {}
    for t, objs in frames.items():
        for label, (_, _, x0, x1, y0, y1) in objs.items():
            f = founder(label)
            count[f] = count.get(f, 0) + 1
            lo, hi = span.get(f, (t, t))
            span[f] = (min(lo, t), max(hi, t))
            for m in MARGINS_PX:
                if x0 <= m or y0 <= m or x1 >= w - 1 - m or y1 >= h - 1 - m:
                    border.setdefault(m, {})[f] = True
    total = sum(count.values())
    summary[seq] = (span, border, count, total, len(count))
    for m in MARGINS_PX:
        b = border.get(m, {})
        touching = sum(count[f] for f in count if b.get(f))
        print(f"seq {seq}: margin {m:>2} px ({m * PIXEL_UM:>4.1f} um) -> "
              f"{sum(1 for f in count if b.get(f)):>3} of {len(count)} lineages "
              f"({100.0 * touching / total:>5.1f}% of {total} objects)")

for m in MARGINS_PX:
    print(f"\nborder margin {m} px ({m * PIXEL_UM:.1f} um)")
    print(f"{'min tp':>7} " + " ".join(f"{'seq ' + s + ' deleted':>17}" for s in ("01", "02")))
    for thr in (3, 5, 8, 10, 15, 20, 30):
        cells = [f"{thr:>7}"]
        for seq in ("01", "02"):
            span, border, count, total, _ = summary[seq]
            b = border.get(m, {})
            hit = [f for f in count if b.get(f) and (span[f][1] - span[f][0] + 1) < thr]
            cells.append(f"{len(hit):>4} lin {100.0 * sum(count[f] for f in hit) / total:>6.2f}%")
        print(" ".join(cells))
