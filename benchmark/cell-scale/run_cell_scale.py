#!/usr/bin/env python
"""Cell-scale estimate vs CTC SEG truth on the development sequences: writes the table in RESULTS.md."""
import os
import sys

import numpy as np
import tifffile

from cell_scale import estimate_radius

CTC = os.path.expanduser("~/Repositories/CTC_dataset")
DATA = [("Fluo-N2DL-HeLa", 0.645, 0.645), ("PhC-C2DL-PSC", 1.6, 1.6), ("Fluo-N3DH-CE", 0.09, 1.0)]


def truth(ds, seq, vx):
    rs = []
    gt = os.path.join(CTC, ds, f"{seq}_GT", "SEG")
    for f in os.listdir(gt):
        m = tifffile.imread(os.path.join(gt, f))
        _, cnt = np.unique(m[m > 0], return_counts=True)
        rs += list(np.sqrt(cnt / np.pi) * vx)  # in-plane radius (CE truth is annotated per slice)
    return np.percentile(rs, [1, 50, 99]), len(rs)


seq = sys.argv[1] if len(sys.argv) > 1 else "01"
for ds, vx, vz in DATA:
    frames = sorted(f for f in os.listdir(os.path.join(CTC, ds, seq)) if f.endswith(".tif"))
    est = [estimate_radius(tifffile.imread(os.path.join(CTC, ds, seq, f)), vx, vz)
           for f in (frames[0], frames[len(frames) // 2], frames[-1])]
    r = float(np.median([e[0] for e in est]))
    (p1, p50, p99), n = truth(ds, seq, vx)
    print(f"| {ds} {seq} | {', '.join(f'{e[0]:.2f}' for e in est)} | **{r:.2f}** | {p50:.2f} ({n}) | {r / p50:.2f} "
          f"| {p1 / r:.2f} | {p99 / r:.2f} |")
