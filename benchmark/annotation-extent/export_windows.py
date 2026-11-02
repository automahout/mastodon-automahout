"""Exports the windows EllipseFit will see, so the REAL Java fitter can be scored.

The 0.92 IoU in RESULTS.md is an UPPER BOUND: it uses the truth cell's own axes,
which a fit from the image cannot know. This harness measures what the shipped
code actually achieves.

  1. this script writes one window per detection, exactly as
     AutoCorrectOrchestrator.fitEllipseToContour samples it
  2. ContourFitHarness (a JUnit test) runs EllipseFit over them
  3. score_fits.py scores the result against CTC silver truth

Run a window with -Dautomahout.splitDump=/tmp/split-fixture.tsv first.
"""
import struct
import sys
import pathlib

import numpy as np
from PIL import Image

_d = pathlib.Path(__file__).parent
sys.path.insert(0, str(_d.parent / 'seed-to-object'))
sys.path.insert(0, str(_d.parent / 'cell-extent'))
from label_dump import load, VOXEL_UM            # noqa: E402
from measure_extent import RAW, SEG, ray          # noqa: E402

# AutoCorrectOrchestrator: CONTOUR_WINDOW_SIDE, CONTOUR_REACH, CELL_EXTENT_SIGMA
SIDE, REACH, SIGMA = 64, 1.6, 5.0
# and resizeToMeasuredExtent, which runs before this
MIN_F, MAX_F, DEAD = 0.5, 1.4, 0.1
OUT = '/tmp/contour-windows.bin'
MAX_DETECTIONS = 600


def extent_factor(meas, seed):
    if not np.isfinite(meas) or meas <= 0 or seed <= 0:
        return 0.0
    f = max(MIN_F, min(MAX_F, meas / seed))
    return 0.0 if abs(f - 1.0) < DEAD else f


def bilinear(img, y, x):
    H, W = img.shape
    y = np.clip(y, 0, H - 1.001)
    x = np.clip(x, 0, W - 1.001)
    y0, x0 = y.astype(int), x.astype(int)
    fy, fx = y - y0, x - x0
    return (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x0 + 1] * fx * (1 - fy)
            + img[y0 + 1, x0] * (1 - fx) * fy + img[y0 + 1, x0 + 1] * fx * fy)


def main(path='/tmp/split-fixture.tsv', seq='01'):
    rows = load(path)
    seen, uniq = set(), []
    for r in rows:
        k = (r['t'], r['x'], r['y'], r['radius'])
        if k not in seen:
            seen.add(k)
            uniq.append(r)
    by_t = {}
    for r in uniq:
        by_t.setdefault(int(r['t']), []).append(r)

    written = 0
    with open(OUT, 'wb') as fh:
        fh.write(struct.pack('<ii', SIDE, 0))   # side, count patched at the end
        for t, rs in sorted(by_t.items()):
            f = RAW / f't{t:03d}.tif'
            if not f.exists() or written >= MAX_DETECTIONS:
                continue
            img = np.array(Image.open(f)).astype('float64')
            med = float(np.median(img))
            sig = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
            level = med + SIGMA * sig
            gy, gx = np.mgrid[0:SIDE, 0:SIDE]
            for r in rs:
                if written >= MAX_DETECTIONS:
                    break
                x, y = float(r['x']) / VOXEL_UM, float(r['y']) / VOXEL_UM
                seed_raw = float(r['radius']) / VOXEL_UM
                # the radius the extent pass now produces
                e = ray(img, int(round(y)), int(round(x)), med, sig, SIGMA, n_rays=8)
                fac = extent_factor(e, seed_raw)
                seed = seed_raw if fac == 0.0 else fac * seed_raw
                if not np.isfinite(seed) or seed <= 0:
                    continue
                step = 2.0 * REACH * seed / SIDE
                w = bilinear(img, y + (gy - SIDE / 2.0) * step, x + (gx - SIDE / 2.0) * step)
                fh.write(struct.pack('<5d', t, x, y, seed, level))
                fh.write(w.astype('<f8').tobytes())
                written += 1
        fh.seek(4)
        fh.write(struct.pack('<i', written))
    print(f"wrote {written} windows of {SIDE}x{SIDE} to {OUT}")
    print("now run:  mvn -o test -Dtest=ContourFitHarness -DfailIfNoSpecifiedTests=false")


if __name__ == '__main__':
    main(*sys.argv[1:])
