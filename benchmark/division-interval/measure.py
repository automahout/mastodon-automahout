#!/usr/bin/env python3
"""How much of the real division rate a minimum-interval threshold would cut.

Reads the CTC tracking ground truth (man_track.txt: label, begin, end, parent) and, for every
COMPLETED cell cycle — a track that was born of a division and itself divides — measures its
length. That is the quantity `AutoCorrectOrchestrator.estimateMinDivisionInterval` is trying to
bound from below, and the doubling time it used instead is a mean, not a minimum.

usage: measure.py [path-to-Fluo-N2DL-HeLa]
"""
import math
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else
            Path.home() / "Repositories/CTC_dataset/Fluo-N2DL-HeLa")

data = {}
for seq in ("01", "02"):
    rows = [tuple(int(x) for x in l.split())
            for l in open(root / f"{seq}_GT/TRA/man_track.txt") if len(l.split()) == 4]
    kids = {}
    for label, _, _, parent in rows:
        kids.setdefault(parent, []).append(label)
    cycles = sorted(end - begin + 1 for label, begin, end, parent in rows
                    if parent > 0 and len(kids.get(label, [])) >= 2)
    first, last = min(r[1] for r in rows), max(r[2] for r in rows)
    n0 = sum(1 for r in rows if r[1] == first)
    ne = sum(1 for r in rows if r[2] == last)
    data[seq] = (cycles, (last - first) / math.log2(ne / n0))

print(f"{'divisor':>8} {'01 thr':>7} {'01 cut':>7} {'02 thr':>7} {'02 cut':>7}")
for divisor in (1.0, 1.25, 1.5, 2.0, 2.5, 3.0):
    cells = [f"{divisor:>8.2f}"]
    for seq in ("01", "02"):
        cycles, doubling = data[seq]
        thr = max(2, round(doubling / divisor))
        cut = 100.0 * sum(1 for v in cycles if v < thr) / len(cycles)
        cells.append(f"{thr:>7d} {cut:>6.1f}%")
    print(" ".join(cells))
print()
for seq in ("01", "02"):
    cycles, doubling = data[seq]
    print(f"seq{seq}: n={len(cycles)} completed cycles, min={cycles[0]}, "
          f"median={cycles[len(cycles) // 2]}, doubling={doubling:.1f}")
