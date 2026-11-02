"""Fig. 1c data: the curated cell radius for each time window, beside the radius the review measured.

For each window in a windows CSV (fig1_windows.py), the median equivalent-sphere radius of the CTC silver-truth
segmentation (01_ST/SEG) on the frames the review measured (the window's first, middle and last frame), median over
the three. Adds a `curated_radius_um` column.

    uv run python figures/fig1_curated_size.py <windows.csv> <ST SEG dir> <voxel x,y,z µm> <out.csv>
"""

import csv
import math
import sys
from pathlib import Path

import numpy as np
import tifffile


def frame_radius(path, voxel):
    """Median equivalent-sphere radius (µm) of the labelled objects in one segmentation volume."""
    labels = tifffile.imread(path)
    counts = np.bincount(labels.ravel())[1:]
    counts = counts[counts > 0]
    if counts.size == 0:
        return float("nan")
    volumes = counts * voxel[0] * voxel[1] * voxel[2]
    return float(np.median((3 * volumes / (4 * math.pi)) ** (1 / 3)))


def review_frames(start, end):
    """The frames the review measures in a window: first, middle and last (ElephantTool.pickSeedTimepoints)."""
    return sorted({start, (start + end + 1) // 2, end})


if __name__ == "__main__":
    windows_csv, seg_dir, voxel_arg, out_csv = sys.argv[1:5]
    voxel = [float(v) for v in voxel_arg.split(",")]
    seg = Path(seg_dir)
    with open(windows_csv, newline="") as f:
        rows = list(csv.DictReader(f))
    for r in rows:
        radii = []
        for t in review_frames(int(r["start"]), int(r["end"])):
            p = seg / f"man_seg{t:03d}.tif"
            if p.exists():
                radii.append(frame_radius(p, voxel))
        r["curated_radius_um"] = f"{np.median(radii):.2f}" if radii else ""
    with open(out_csv, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(f"{len(rows)} windows -> {out_csv}")
