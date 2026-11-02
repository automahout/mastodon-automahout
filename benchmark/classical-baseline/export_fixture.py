"""Exports real crops so the Java detector is checked against the same pixels.

Image values are stored as standout above background in quarter-sigma steps,
clipped to [0,255], so the 3-sigma level is exactly 12 in stored units. The truth
label mask is stored alongside, relabelled 1..255 within the crop, so the Java
test can score detections the same way the Python did.
"""
import base64
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
OUT = Path(__file__).resolve().parents[2] / 'src/test/resources/classical-baseline/crops.tsv'
SIDE = 256
# Picked for density rather than by hand: a sparse crop tests almost nothing.
FRAMES = ['t030', 't060', 't091']


def main():
    rows = []
    for stem in FRAMES:
        img = np.array(Image.open(ROOT / '01' / f'{stem}.tif')).astype('float32')
        seg = np.array(Image.open(ROOT / '01_ST' / 'SEG' / f'man_seg{stem[1:]}.tif'))
        # densest SIDE x SIDE window, on a coarse grid
        best = None
        for yy in range(0, seg.shape[0] - SIDE, 32):
            for xx in range(0, seg.shape[1] - SIDE, 32):
                n = len(np.unique(seg[yy:yy + SIDE, xx:xx + SIDE])) - 1
                if best is None or n > best[0]:
                    best = (n, yy, xx)
        _, y0, x0 = best
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        sub = img[y0:y0 + SIDE, x0:x0 + SIDE]
        sseg = seg[y0:y0 + SIDE, x0:x0 + SIDE]
        q = np.clip(np.round((sub - med) / sigma * 4.0), 0, 255).astype(np.uint8)
        labs = [l for l in np.unique(sseg) if l != 0]
        # Keep only cells lying WHOLLY inside the crop; a clipped cell is neither
        # a fair miss nor a fair hit.
        remap = np.zeros(SIDE * SIDE, dtype=np.uint8).reshape(SIDE, SIDE)
        kept = 0
        for l in labs:
            m = sseg == l
            ys, xs = np.nonzero(m)
            if ys.min() == 0 or xs.min() == 0 or ys.max() == SIDE - 1 or xs.max() == SIDE - 1:
                continue
            kept += 1
            remap[m] = kept
        rows.append((stem, kept,
                     base64.b64encode(q.tobytes()).decode('ascii'),
                     base64.b64encode(remap.tobytes()).decode('ascii')))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open('w') as f:
        f.write('# Real crops from Fluo-N2DL-HeLa 01 with silver truth.\n')
        f.write(f'# side={SIDE}; image units = quarter sigma above background '
                f'(so 3 sigma = 12); truth = label mask, cells touching the edge dropped\n')
        f.write('# frame\tn_cells\tbase64(image uint8)\tbase64(truth uint8)\n')
        for r in rows:
            f.write('\t'.join(map(str, r)) + '\n')
    print(OUT, OUT.stat().st_size, 'bytes,', sum(r[1] for r in rows), 'cells')


main()
