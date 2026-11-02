"""Exports real sampled windows so the Java implementation can be checked
against the SAME data the Python measurement used, not against intuition.

Values are stored as standout above background in tenths of a sigma, clipped to
[0, 255], so the object level (3 sigma) is exactly 30 in stored units and the
fixture is self-describing.
"""
import base64
from pathlib import Path

import numpy as np

import measure_arbiter as M

WIN = 48          # stored window side, in strided pixels
STRIDE = M and 2
OUT = Path(__file__).resolve().parents[2] / 'src/test/resources/seed-to-object/windows.tsv'


def main():
    rows = []
    rng = np.random.default_rng(0)
    for img, seg in M.frames():
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        same, diff = M.pairs_for(img, seg)
        for a, b, r, truth in same + diff:
            cy, cx = (a[0] + b[0]) // 2, (a[1] + b[1]) // 2
            half = WIN * STRIDE // 2
            y0, x0 = cy - half, cx - half
            ys = y0 + np.arange(WIN) * STRIDE
            xs = x0 + np.arange(WIN) * STRIDE
            if ys[0] < 0 or xs[0] < 0 or ys[-1] >= img.shape[0] or xs[-1] >= img.shape[1]:
                continue
            win = img[np.ix_(ys, xs)]
            q = np.clip(np.round((win - med) / sigma * 10.0), 0, 255).astype(np.uint8)
            ax, ay = (a[1] - x0) // STRIDE, (a[0] - y0) // STRIDE
            bx, by = (b[1] - x0) // STRIDE, (b[0] - y0) // STRIDE
            if not (0 <= ax < WIN and 0 <= ay < WIN and 0 <= bx < WIN and 0 <= by < WIN):
                continue
            rows.append((int(truth), ax, ay, bx, by,
                         base64.b64encode(q.tobytes()).decode('ascii')))
    rng.shuffle(rows)
    rows = rows[:400]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open('w') as f:
        f.write('# Real sampled windows from Fluo-N2DL-HeLa 01 + silver truth.\n')
        f.write(f'# side={WIN} stride={STRIDE} units=tenths of a background sigma\n')
        f.write('# same_cell\tax\tay\tbx\tby\tbase64(uint8[side*side], row-major)\n')
        for r in rows:
            f.write('\t'.join(map(str, r)) + '\n')
    n_same = sum(r[0] for r in rows)
    print(f'{OUT} {OUT.stat().st_size} bytes, {len(rows)} pairs, '
          f'{n_same} one-cell / {len(rows) - n_same} two-cell')


main()
