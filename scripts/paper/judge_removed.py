#!/usr/bin/env python
"""Judge every spot a run removed against the CTC truth, by the method that removed it.

usage: judge_removed.py REMOVED_SPOTS.tsv GT_DIR RES_DIR MOVIE.xml [--window 10]

REMOVED_SPOTS.tsv is the origin dump's companion (-Dautomahout.originDump=...; <dump>.removed_spots.tsv), one
row per removed spot: where it was (world units), its radius, why and by which method. Each removal is:

  lost        it held a truth cell's centre (the nearest marker within its radius), and the final result has
              no detection on that cell at that frame -- a real cell taken away and not given back
  duplicate   it held a truth cell that the final result still detects -- a second detection, or one
              replaced by another
  no cell     no truth cell within its radius

A spot removed and re-added at the same place (a window cleared before a fresh detection) is counted too;
the 'site' column tells those apart (deleteAllSpotsAtTimepoints is a clear, not a judgement).
Run with uv: uv run --project scripts/paper python scripts/paper/judge_removed.py ...
"""
import argparse
import collections
import csv
import os
import re
import sys
import xml.etree.ElementTree as ET

import numpy as np
import tifffile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from per_window import frames, score_frame  # noqa: E402


def world_to_voxel(xml_path):
    """The first view registration's affine, inverted: world -> voxel (x, y, z)."""
    root = ET.parse(xml_path).getroot()
    a = np.eye(4)
    for t in root.find(".//ViewRegistration").findall("ViewTransform"):
        m = np.eye(4)
        m[:3, :] = np.array([float(v) for v in t.find("affine").text.split()]).reshape(3, 4)
        a = a @ m
    return np.linalg.inv(a)


def centroids(mask):
    labels = np.unique(mask[mask > 0])
    out = {}
    for lab in labels:
        idx = np.argwhere(mask == lab)
        out[int(lab)] = idx.mean(axis=0)[::-1]  # (z, y, x) or (y, x) -> x, y[, z]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("removed")
    ap.add_argument("gt")
    ap.add_argument("res")
    ap.add_argument("xml")
    ap.add_argument("--window", type=int, default=10)
    a = ap.parse_args()
    inv = world_to_voxel(a.xml)
    vox = np.abs(np.diag(np.linalg.inv(inv))[:3])  # voxel size per axis
    gfr = frames(os.path.join(a.gt, "TRA"), r"man_track(\d+)\.tif")
    rfr = frames(a.res, r"mask(\d+)\.tif")
    by_t = collections.defaultdict(list)
    with open(a.removed) as f:
        for row in csv.DictReader(f, delimiter="\t"):
            by_t[int(row["t"])].append(row)
    tally = collections.defaultdict(lambda: collections.Counter())
    per_window = collections.defaultdict(lambda: collections.Counter())
    for t in sorted(by_t):
        if t not in gfr or t not in rfr:
            continue
        gt = tifffile.imread(gfr[t])
        res = tifffile.imread(rfr[t])
        cents = centroids(gt)
        _, match = score_frame(res, gt)
        for row in by_t[t]:
            w = np.array([float(row["x"]), float(row["y"]), float(row["z"]), 1.0])
            v = (inv @ w)[:3]
            r = float(row["radius"])
            best, bd = None, None
            for lab, c in cents.items():
                c3 = np.array([c[0], c[1], c[2] if len(c) > 2 else v[2]])
                d = np.linalg.norm((c3 - v) * vox)
                if d <= r and (bd is None or d < bd):
                    best, bd = lab, d
            kind = "no cell" if best is None else ("duplicate" if best in match else "lost")
            site = re.split(r"<-", row["site"])[0]
            tally[site][kind] += 1
            per_window[t // a.window][kind] += 1
    print(f"{'removed by':55s} {'lost':>7s} {'dup':>7s} {'nocell':>7s}")
    for site, c in sorted(tally.items(), key=lambda kv: -kv[1]["lost"]):
        print(f"{site[:55]:55s} {c['lost']:7d} {c['duplicate']:7d} {c['no cell']:7d}")
    print("\nper window (lost / duplicate / no cell):")
    for w in sorted(per_window):
        c = per_window[w]
        print(f"  t={w * a.window:3d}..  {c['lost']:6d} {c['duplicate']:6d} {c['no cell']:6d}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
