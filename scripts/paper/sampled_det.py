#!/usr/bin/env python
"""DET on a few frames only, for runs that tracked just those frames (BenchmarkRunner samples=).

usage: sampled_det.py RES_DIR GT_DIR t1,t2,... [RES_DIR2 ...]

The CTC DET formula restricted to the listed frames: AOGM-D = 10 FN + 1 FP + 5 NS over those frames, against
10 x the truth markers in them (the empty-result cost); DET = 1 - min(AOGM-D, AOGM-D0) / AOGM-D0. A result label
matches a marker when it covers more than half of it (per_window.score_frame); a label matching k markers needs
k - 1 splits (NS); a label matching none is a false positive (FP).

Also per frame, the cells matched by a label of their own ("alone"): on data whose markers are small spheres (CE),
one oversized detection over three nuclei matches all three, and DET charges that less than three misses.
"""
import os
import sys

import numpy as np
import tifffile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from per_window import frames, score_frame  # noqa: E402


def score(res_dir, gt_dir, ts):
    gtf = frames(os.path.join(gt_dir, "TRA"), r"man_track(\d+)\.tif")
    rf = frames(res_dir, r"mask(\d+)\.tif")
    fn = fp = ns = n_gt = 0
    rows = []
    for t in ts:
        g = tifffile.imread(gtf[t])
        r = tifffile.imread(rf[t]) if t in rf else np.zeros_like(g)
        s, match = score_frame(r, g)
        per = {}
        for rl in match.values():
            per[rl] = per.get(rl, 0) + 1
        alone = sum(1 for rl in match.values() if per[rl] == 1)
        fn += s["missed"]
        fp += s["res"] - len(per)
        ns += sum(k - 1 for k in per.values())
        n_gt += s["gt"]
        rows.append((t, s["gt"], s["res"], s["matched"], alone, s["merged"], s["res"] - len(per), s["size"]))
    aogm, aogm0 = 10 * fn + fp + 5 * ns, 10 * n_gt
    return 1 - min(aogm, aogm0) / aogm0, rows


def main():
    ts = [int(x) for x in sys.argv[3].split(",")]
    for res in [sys.argv[1]] + sys.argv[4:]:
        det, rows = score(res, sys.argv[2], ts)
        print(f"{res}\n  DET over t={','.join(map(str, ts))}: {det:.3f}")
        print("     t  cells  labels  matched  alone  merged  false  size")
        for t, gt, res_n, m, alone, merged, false, size in rows:
            print(f"  {t:4d}  {gt:5d}  {res_n:6d}  {m:7d}  {alone:5d}  {merged:6d}  {false:5d}  {size if size is None else round(size, 2)}")


if __name__ == "__main__":
    main()
