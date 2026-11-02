#!/usr/bin/env python
"""Reference implementation of the cell-scale estimate (tool/CellScale.java ports it; test vectors compare them).

A cell's typical radius, read off one raw frame, whatever the modality:

1. resample to near-isotropic voxels of size iso = max(vx, vz / 2) (2D: iso = vx, no resampling);
2. 3x3(x3) median filter (suppresses shot noise, which otherwise wins at the smallest scale on 8-bit 3D data);
3. normalise: (v - p1) / (p99.8 - p1), clipped to [0, 1];
4. scale space: for sigma in a geometric grid 1..25 (iso units), R_sigma = -sigma^2 * LoG_sigma (bright blobs
   positive);
5. blobs: voxels that are the maximum of their 3^d spatial neighbourhood across the adjacent scales too (a
   scale-space maximum) with R > 0.05;
6. prune, strongest first: a blob whose centre lies inside the sphere (radius sqrt(d) * sigma) of a stronger
   blob already kept is part of it and is dropped;
7. the radius of the 50 strongest blobs kept, median, times iso: micrometres.

The radius of a disk (ball) whose normalised LoG peaks at sigma is sqrt(d) * sigma.
Measured against CTC SEG truth on development sequences (01): see RESULTS.md.
"""
import numpy as np
from scipy import ndimage as ndi

SIGMAS = np.geomspace(1.0, 25.0, 30)
THRESHOLD = 0.05
TOP = 50


def prepare(img, vx, vz):
    img = np.asarray(img, dtype=np.float32)
    iso = max(vx, vz / 2.0) if img.ndim == 3 else vx
    if img.ndim == 3:
        img = ndi.zoom(img, (vz / iso, vx / iso, vx / iso), order=1)
    img = ndi.median_filter(img, size=3)
    lo, hi = np.percentile(img, (1, 99.8))
    return np.clip((img - lo) / max(hi - lo, 1e-6), 0, 1), iso


def blobs(img):
    d = img.ndim
    stack = np.stack([-(s * s) * ndi.gaussian_laplace(img, s) for s in SIGMAS])
    mx = ndi.maximum_filter(stack, size=3, mode="nearest")
    peaks = np.argwhere((stack == mx) & (stack > THRESHOLD))
    resp = stack[tuple(peaks.T)]
    order = np.argsort(-resp)
    peaks, resp = peaks[order], resp[order]
    kept = []
    for p in peaks:
        rad = np.sqrt(d) * SIGMAS[p[0]]
        c = p[1:].astype(float)
        # a centre inside a stronger blob's sphere is part of that blob
        if any(np.sum((c - q) ** 2) < qrad * qrad for q, qrad in kept):
            continue
        kept.append((c, rad))
        if len(kept) >= TOP:
            break
    return kept


def estimate_radius(img, vx, vz):
    """(radius in micrometres, number of blobs used, at most TOP)."""
    im, iso = prepare(img, vx, vz)
    kept = blobs(im)
    if not kept:
        return float("nan"), 0
    radii = np.array([r for _, r in kept])
    return float(np.median(radii) * iso), len(kept)
