#!/usr/bin/env python3
"""How far does a daughter sit from its mother, compared with ordinary motion?

The per-frame displacement cap in this pipeline is measured from CONSECUTIVE LINKS -- the ordinary
motion of a cell that is not dividing. Every pass that tries to attach a daughter to its mother is
bounded by that same cap. But a mitosis separates two daughters by something like a nuclear diameter
within one frame, so if the division step is larger than ordinary motion, the cap forbids exactly the
join it is being asked to make.

Measured on the CTC tracking ground truth: for every track with a parent, the distance from the
parent's last centroid to the daughter's first, against the distribution of ordinary
consecutive-frame displacements.

usage: ctc_centroids.py first (it writes /tmp/ctc_centroids.pkl), then measure.py [path-to-pkl]
"""
import pickle
import sys
from pathlib import Path

PIXEL_UM = 0.645
CAP_FACTOR = 4.0  # ESTIMATED_DISPLACEMENT_CAP_FACTOR

pkl = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/ctc_centroids.pkl")
data = pickle.load(open(pkl, "rb"))
root = Path.home() / "Repositories/CTC_dataset/Fluo-N2DL-HeLa"


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))]


for seq in ("01", "02"):
    frames = data[seq]["frames"]
    rows = [tuple(int(x) for x in l.split())
            for l in open(root / f"{seq}_GT/TRA/man_track.txt") if len(l.split()) == 4]
    ends = {label: end for label, _, end, _ in rows}

    ordinary = []
    for t in sorted(frames):
        nxt = frames.get(t + 1)
        if not nxt:
            continue
        for label, v in frames[t].items():
            if label in nxt:
                w = nxt[label]
                ordinary.append((((w[0] - v[0]) ** 2 + (w[1] - v[1]) ** 2) ** 0.5) * PIXEL_UM)

    division = []
    for label, begin, _, parent in rows:
        if parent <= 0:
            continue
        pend = ends.get(parent)
        if pend is None or pend not in frames or begin not in frames:
            continue
        m = frames[pend].get(parent)
        d = frames[begin].get(label)
        if m is None or d is None:
            continue
        division.append((((d[0] - m[0]) ** 2 + (d[1] - m[1]) ** 2) ** 0.5) * PIXEL_UM)

    cap = pct(ordinary, 0.5) * CAP_FACTOR
    refused = 100.0 * sum(1 for v in division if v > cap) / len(division)
    print(f"seq {seq}: {len(ordinary)} ordinary steps, {len(division)} mother->daughter steps")
    print(f"  ordinary  median {pct(ordinary,0.5):5.2f}  p95 {pct(ordinary,0.95):5.2f}  "
          f"p99 {pct(ordinary,0.99):5.2f} µm")
    print(f"  division  median {pct(division,0.5):5.2f}  p95 {pct(division,0.95):5.2f}  "
          f"p99 {pct(division,0.99):5.2f} µm")
    print(f"  cap = {CAP_FACTOR:.0f} x ordinary median = {cap:5.2f} µm "
          f"-> REFUSES {refused:.1f}% of real mother->daughter joins")
    for f in (4, 6, 8, 10, 12):
        c = pct(ordinary, 0.5) * f
        r = 100.0 * sum(1 for v in division if v > c) / len(division)
        print(f"     factor {f:>2} -> cap {c:5.2f} µm -> refuses {r:5.1f}%")
