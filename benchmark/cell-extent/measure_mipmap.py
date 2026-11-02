"""Does sampling at a coarser mipmap level inflate the measured cell extent?

The benchmark measured ray(5 sigma, 8 rays) at full resolution and found the
per-frame population median within 2.2% of truth. The pipeline's Sampler reads
mipmap level 1 -- the run log says so: "Intensity, contrast and evenness are
measured on mipmap level 1 of 3 (voxel up to 1.29 um)". Downsampling averages,
which smears a cell's edge OUTWARD, so the level crossing moves out.

The run reports 10.6-11.3 um against a truth median of 12.9 px * 0.645 = 8.3 um
-- about 1.3x. This tests whether the mipmap level accounts for it.

Run: <venv>/bin/python measure_mipmap.py
"""
from pathlib import Path

import numpy as np
import scipy.ndimage as nd
from PIL import Image

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
RAW = ROOT / '01'
SEG = ROOT / '01_ST' / 'SEG'
PIXEL_UM = 0.645


def seeds_and_truth(img, seg):
    labels = np.unique(seg)
    labels = labels[labels != 0]
    objs = nd.find_objects(seg)
    out = []
    for lab in labels:
        sl = objs[lab - 1]
        if sl is None:
            continue
        m = seg[sl] == lab
        if m.sum() < 16:
            continue
        sub = np.where(m, img[sl], -np.inf)
        dy, dx = np.unravel_index(np.argmax(sub), sub.shape)
        out.append((sl[0].start + dy, sl[1].start + dx, np.sqrt(m.sum() / np.pi)))
    return out


def ray(img, y, x, med, sigma, k, n_rays=8, step=1.0, max_r=40):
    level = med + k * sigma
    rs = []
    for a in np.linspace(0, 2 * np.pi, n_rays, endpoint=False):
        dy, dx = np.sin(a), np.cos(a)
        hit = max_r
        for i in range(1, int(max_r / step)):
            d = i * step
            yy, xx = int(round(y + dy * d)), int(round(x + dx * d))
            if not (0 <= yy < img.shape[0] and 0 <= xx < img.shape[1]):
                hit = d
                break
            if img[yy, xx] < level:
                hit = d
                break
        rs.append(hit)
    return float(np.median(rs))


def main():
    print('  level             | median estimate px | vs truth | in um')
    for lvl in (0, 1, 2):
        est, tru = [], []
        for f in sorted(RAW.glob('t*.tif'))[::6]:
            segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
            if not segf.exists():
                continue
            full = np.array(Image.open(f)).astype('float32')
            seg = np.array(Image.open(segf))
            # A mipmap level is a box-average by 2^lvl, sampled back in full coords.
            if lvl == 0:
                img = full
            else:
                s = 2 ** lvl
                small = full[:full.shape[0] // s * s, :full.shape[1] // s * s]
                small = small.reshape(small.shape[0] // s, s,
                                      small.shape[1] // s, s).mean(axis=(1, 3))
                img = np.kron(small, np.ones((s, s), dtype='float32'))
                img = img[:full.shape[0], :full.shape[1]]
            med = float(np.median(img))
            sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
            for y, x, r in seeds_and_truth(full, seg):
                if y >= img.shape[0] or x >= img.shape[1]:
                    continue
                est.append(ray(img, y, x, med, sigma, 5.0))
                tru.append(r)
        est, tru = np.array(est), np.array(tru)
        m = float(np.median(est))
        print(f'  mipmap level {lvl}    | {m:18.1f} | {m / np.median(tru):7.2f}x | '
              f'{m * PIXEL_UM:5.1f}')
    print(f'\n  truth median {np.median(tru):.1f} px = {np.median(tru) * PIXEL_UM:.1f} um')
    print('  the run reported 10.6-11.3 um for this quantity')


main()
