"""Separating the two 3D failures found by measure_3d.py.

  1. The sigma estimator collapses. Fluo-N3DH-CE is uint8 with a flat background,
     so the MAD of the volume is literally 0 and `median + 3*sigma` degenerates
     to `> median`. That is a defect in the threshold, not in the approach.
  2. 356 of 360 nuclei touch another nucleus directly, and cells fill 10.5% of
     the volume against 3.5% of the 2D frame. Splitting is then the WHOLE
     problem, and splitting is what the anchor is weakest at.

This re-runs with a threshold that cannot degenerate, so the two can be told
apart: if a sound threshold recovers most of the gap, the approach carries to 3D
and only the estimator needed fixing.

Run: <venv>/bin/python measure_3d_robust.py
"""
import numpy as np
import scipy.ndimage as nd
from skimage.feature import peak_local_max
from skimage.segmentation import watershed

from measure_3d import RAW, SEG, VOXEL, score, volume


def robust_sigma(vol):
    """Sigma from the lower half of the distribution, which is background only.

    MAD over the whole volume is 0 here because most voxels share one value. The
    spread of the background BELOW its own median cannot be contaminated by
    cells, and for a half-normal it is sigma * 0.6745.
    """
    med = float(np.median(vol))
    lower = vol[vol <= med]
    if lower.size < 10:
        return 1.0
    mad = float(np.median(np.abs(lower - med)))
    return max(mad / 0.6745, 0.5)


def detect3d(vol, level, min_vox, peak_um):
    mask = vol > level
    mask = nd.binary_opening(mask, np.ones((1, 3, 3)))
    dist = nd.distance_transform_edt(mask, sampling=VOXEL)
    fz = max(1, int(round(peak_um / VOXEL[0])))
    fy = max(1, int(round(peak_um / VOXEL[1])))
    pk = peak_local_max(dist, footprint=np.ones((2 * fz + 1, 2 * fy + 1, 2 * fy + 1)),
                        labels=mask)
    markers = np.zeros(dist.shape, dtype=np.int32)
    for i, p in enumerate(pk, start=1):
        markers[tuple(p)] = i
    lab = watershed(-dist, markers, mask=mask)
    out = []
    for i, sl in enumerate(nd.find_objects(lab), start=1):
        if sl is None:
            continue
        m = lab[sl] == i
        if m.sum() < min_vox:
            continue
        cz, cy, cx = nd.center_of_mass(m)
        out.append((sl[0].start + cz, sl[1].start + cy, sl[2].start + cx))
    return out


def main():
    frames = []
    for t in (50, 100, 150, 190):
        f, s = RAW / f't{t:03d}.tif', SEG / f'man_seg{t:03d}.tif'
        if f.exists() and s.exists():
            frames.append((volume(f).astype('float32'), volume(s)))
    v0 = frames[0][0]
    print(f'whole-volume MAD sigma = {1.4826 * np.median(np.abs(v0 - np.median(v0))):.3f} '
          f'(degenerate), lower-half sigma = {robust_sigma(v0):.2f}\n')

    print('  threshold          | peak |  recall  precision      F1 |  fp  over-seg')
    for name, lvl in [('median + 3 robust s', None), ('75th percentile', 75),
                      ('90th percentile', 90), ('95th percentile', 95)]:
        for peak_um in (2.0, 3.0):
            def det(v, name=name, lvl=lvl, p=peak_um):
                if lvl is None:
                    level = float(np.median(v)) + 3 * robust_sigma(v)
                else:
                    level = float(np.percentile(v, lvl))
                return detect3d(v, level, 50, p)
            rec, prec, f1, fp, extra, _ = score(frames, det)
            print(f'  {name:18s} | {peak_um:3.1f} | {rec:7.1%} {prec:10.1%} {f1:7.1%} | '
                  f'{fp:3d} {extra:9d}')


main()
