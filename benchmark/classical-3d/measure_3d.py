"""Does the classical anchor work in 3D, and what does anisotropy do to it?

Fluo-N3DH-CE: C. elegans embryo nuclei, 35 x 512 x 708 voxels, and a voxel that
is 0.09 um in xy against 1.0 um in z -- about 11x anisotropic. That is the thing
that makes 3D different, not the extra dimension: a nucleus spanning 30 voxels
in xy spans 3 in z, so a distance transform computed in VOXELS thinks every
nucleus is a thin plate, and the watershed splits it along z.

Compares:
  isotropic   distance transform in voxel units, as the 2D code does
  anisotropic distance transform in PHYSICAL units (scipy `sampling`)
  per-plane   the 2D detector run on each z plane independently, for reference

Run: <venv>/bin/python measure_3d.py
"""
from pathlib import Path

import numpy as np
import scipy.ndimage as nd
from PIL import Image, ImageSequence
from skimage.feature import peak_local_max
from skimage.segmentation import watershed

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N3DH-CE'
RAW = ROOT / '01'
SEG = ROOT / '01_ST' / 'SEG'
VOXEL = (1.0, 0.09, 0.09)     # z, y, x in um -- CTC's published spacing


def volume(path):
    im = Image.open(path)
    return np.stack([np.array(p) for p in ImageSequence.Iterator(im)])


def detect3d(vol, k, min_vox, peak_um, sampling):
    med = float(np.median(vol))
    sigma = max(1.4826 * float(np.median(np.abs(vol - med))), 1e-9)
    mask = vol > med + k * sigma
    mask = nd.binary_opening(mask, np.ones((1, 3, 3)))
    dist = nd.distance_transform_edt(mask, sampling=sampling)
    # Peak spacing expressed in physical units, converted to a voxel footprint.
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


def score(frames, detector):
    found = missed = fp = extra = truth_total = det_total = 0
    for vol, seg in frames:
        dets = detector(vol)
        det_total += len(dets)
        labels = np.unique(seg)
        labels = labels[labels != 0]
        truth_total += len(labels)
        hits = {}
        for cz, cy, cx in dets:
            z, y, x = int(round(cz)), int(round(cy)), int(round(cx))
            lab = seg[z, y, x] if (0 <= z < seg.shape[0] and 0 <= y < seg.shape[1]
                                   and 0 <= x < seg.shape[2]) else 0
            if lab == 0:
                fp += 1
            else:
                hits[lab] = hits.get(lab, 0) + 1
        for lab in labels:
            n = hits.get(lab, 0)
            if n == 0:
                missed += 1
            else:
                found += 1
                extra += n - 1
    prec = found / max(det_total, 1)
    rec = found / max(truth_total, 1)
    f1 = 2 * prec * rec / max(prec + rec, 1e-9)
    return rec, prec, f1, fp, extra, truth_total


def main():
    frames = []
    for t in (50, 100, 150, 190):
        f = RAW / f't{t:03d}.tif'
        s = SEG / f'man_seg{t:03d}.tif'
        if f.exists() and s.exists():
            frames.append((volume(f).astype('float32'), volume(s)))
    counts = [len(np.unique(s)) - 1 for _, s in frames]
    print(f'{len(frames)} volumes, truth cells per volume: {counts} '
          f'(total {sum(counts)})')
    print(f'voxel z/y/x = {VOXEL} um, anisotropy {VOXEL[0] / VOXEL[1]:.0f}x\n')

    print('  distance transform | peak spacing |  recall  precision      F1 |  fp  over-seg')
    for name, sampling in (('voxels (isotropic)', None),
                           ('physical (correct)', VOXEL)):
        for peak_um in (2.0, 3.0, 4.0):
            rec, prec, f1, fp, extra, _ = score(
                frames, lambda v, s=sampling, p=peak_um: detect3d(v, 3.0, 50, p, s))
            print(f'  {name:18s} | {peak_um:9.1f} um | {rec:7.1%} {prec:10.1%} {f1:7.1%} | '
                  f'{fp:3d} {extra:9d}')


main()


def diagnose():
    """Why 3D fails: is the image too dim to threshold, or the nuclei too crowded
    to split? The two have completely different answers."""
    print('\n  --- why ---')
    for t in (50, 150, 190):
        vol = volume(RAW / f't{t:03d}.tif').astype('float32')
        seg = volume(SEG / f'man_seg{t:03d}.tif')
        med = float(np.median(vol))
        sigma = max(1.4826 * float(np.median(np.abs(vol - med))), 1e-9)
        labels = np.unique(seg)
        labels = labels[labels != 0]
        # Per-cell standout, and per-cell size in voxels and in z planes.
        so, zspan, vox = [], [], []
        for lab in labels:
            m = seg == lab
            so.append((float(vol[m].mean()) - med) / sigma)
            zs = np.nonzero(m.any(axis=(1, 2)))[0]
            zspan.append(zs.max() - zs.min() + 1)
            vox.append(int(m.sum()))
        mask = vol > med + 3.0 * sigma
        covered = [float(mask[seg == lab].mean()) for lab in labels]
        print(f'  t={t:3d}  {len(labels):3d} cells | standout p05/p50 '
              f'{np.quantile(so, .05):5.1f}/{np.quantile(so, .5):5.1f} sigma | '
              f'z planes p05/p50 {np.quantile(zspan, .05):.0f}/{np.quantile(zspan, .5):.0f} | '
              f'voxels p50 {np.median(vox):6.0f} | above 3 sigma p05/p50 '
              f'{np.quantile(covered, .05):5.1%}/{np.quantile(covered, .5):5.1%}')
    # How much of the frame is cell at all -- crowding.
    seg = volume(SEG / 'man_seg190.tif')
    print(f'\n  at t=190, cells occupy {(seg > 0).mean():.2%} of the volume; '
          f'the 2D dataset has 3.5% of the frame above background')
    # Do the truth cells TOUCH each other?
    lab = seg
    touching = 0
    for l in np.unique(lab)[1:]:
        m = lab == l
        d = nd.binary_dilation(m, np.ones((1, 3, 3)))
        nb = set(np.unique(lab[d & ~m])) - {0}
        if nb:
            touching += 1
    print(f'  {touching} of {len(np.unique(lab)) - 1} nuclei touch another nucleus directly')


diagnose()
