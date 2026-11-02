#!/usr/bin/env python
"""Score a CTC-format result against CTC ground truth (benchmark protocol, docs/paper/benchmark-protocol.md).

usage: evaluate_ctc.py RES_DIR GT_DIR [--json OUT.json]

RES_DIR holds res_track.txt + maskTTT.tif (as written by CtcExport); GT_DIR is a CTC "0x_GT" folder (TRA/ inside).
Metrics: Valid, DET, TRA, LNK and the biological measures CT, TF, BC(i), CCA, computed by py-ctcmetrics
(version recorded in the output). Run with uv: uv run --project scripts/paper python scripts/paper/evaluate_ctc.py ...
"""
import argparse
import json
import sys
from importlib.metadata import version

from ctc_metrics import evaluate_sequence

METRICS = ["Valid", "DET", "TRA", "LNK", "CT", "TF", "BC", "CCA"]


def trim_to_truth(res, gt):
    """A copy of RES cut to the frames the truth covers (CE 01: 250 frames, truth for 0..194), or None.

    py-ctcmetrics refuses a result with more masks than the truth. Tracks are clipped to the last truth
    frame; one starting after it is dropped, and a parent that was dropped becomes 0.
    """
    import os
    import re
    import tempfile
    gt_frames = sorted(int(m.group(1)) for f in os.listdir(os.path.join(gt, "TRA"))
                       if (m := re.fullmatch(r"man_track(\d+)\.tif", f)))
    res_frames = sorted(int(m.group(1)) for f in os.listdir(res) if (m := re.fullmatch(r"mask(\d+)\.tif", f)))
    if len(res_frames) <= len(gt_frames):
        return None
    last = gt_frames[-1]
    tmp = tempfile.TemporaryDirectory(prefix="ctc_trim_")
    for t in res_frames:
        if t <= last:
            name = f"mask{t:03d}.tif" if os.path.exists(os.path.join(res, f"mask{t:03d}.tif")) else f"mask{t:04d}.tif"
            os.symlink(os.path.join(os.path.abspath(res), name), os.path.join(tmp.name, name))
    kept = {}
    with open(os.path.join(res, "res_track.txt")) as f:
        for line in f:
            p = line.split()
            if len(p) == 4 and int(p[1]) <= last:
                kept[int(p[0])] = [int(p[0]), int(p[1]), min(int(p[2]), last), int(p[3])]
    with open(os.path.join(tmp.name, "res_track.txt"), "w") as f:
        for lab, b, e, par in kept.values():
            f.write(f"{lab} {b} {e} {par if par in kept else 0}\n")
    print(f"(scored on frames 0..{last}: the truth ends there, the result has {len(res_frames)} frames)")
    return tmp


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("res")
    ap.add_argument("gt")
    ap.add_argument("--json")
    a = ap.parse_args()
    res = a.res
    tmp = trim_to_truth(a.res, a.gt)
    if tmp is not None:
        res = tmp.name
    r = evaluate_sequence(res, a.gt, metrics=METRICS)
    out = {k: (None if v is None else float(v)) for k, v in r.items()
           if k in METRICS or k.startswith("BC(") or k.startswith("AOGM")}
    out["py-ctcmetrics"] = version("py-ctcmetrics")
    out["res"] = a.res
    out["gt"] = a.gt
    if a.json:
        with open(a.json, "w") as f:
            json.dump(out, f, indent=1)
    print(" ".join(f"{k}={out[k]:.4f}" if isinstance(out[k], float) else f"{k}={out[k]}"
                   for k in ["Valid", "DET", "TRA", "LNK", "CT", "TF", "BC(0)", "CCA"] if k in out))
    return 0 if out.get("Valid") == 1.0 else 1


if __name__ == "__main__":
    sys.exit(main())
