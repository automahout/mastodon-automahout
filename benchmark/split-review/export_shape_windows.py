"""Export the windows split_features.py fits, with its own results, for the Java parity harness.

Writes /tmp/split-windows.bin (little-endian: int count; per record int w, int h, double cx, cy, r,
level, then w*h doubles row-major) and /tmp/split-shape-py.tsv with what the Python reference
computed on exactly those windows. ShapeSplitParityHarness reads the first and writes
/tmp/split-shape-java.tsv; compare_shape_parity.py compares the two.

    python export_shape_windows.py [stamp] [max_one_cell]
"""
import struct
import sys
from pathlib import Path

import numpy as np
from scipy import ndimage as ndi

sys.path.insert(0, str(Path(__file__).parent))
import measure_split_decisions as m  # noqa: E402
import prototype_splitter as p  # noqa: E402
import split_features as f  # noqa: E402


def reference(win, cx, cy, r, level):
    """split_features' computation on a given window, without re-reading the image."""
    mask = win > level
    lab, n = ndi.label(mask)
    if n == 0:
        return None
    yy, xx = np.mgrid[0:win.shape[0], 0:win.shape[1]]
    inside = (xx - cx) ** 2 + (yy - cy) ** 2 < r * r
    counts = np.bincount(lab[inside].ravel(), minlength=n + 1)
    counts[0] = 0
    keep = int(counts.argmax())
    if keep == 0:
        return None
    obj = lab == keep
    ys, xs = np.nonzero(obj)
    X = np.stack([xs, ys], 1).astype(float)
    if len(X) < 20:
        return None
    mu1, c1 = X.mean(0), np.cov(X.T) + 0.25 * np.eye(2)
    one = f.ellipse_mask(obj.shape, mu1, c1)
    fit = f.two_components(X)
    if fit is None:
        return len(X), f.iou(obj, one), float('nan'), None
    _, _, mu, cov = fit
    two = f.ellipse_mask(obj.shape, mu[0], cov[0]) | f.ellipse_mask(obj.shape, mu[1], cov[1])
    return len(X), f.iou(obj, one), f.iou(obj, two), mu


def main():
    stamp = sys.argv[1] if len(sys.argv) > 1 else m.newest_stamp()
    max_one = int(sys.argv[2]) if len(sys.argv) > 2 else 600
    pop = [d for d in p.population(stamp) if p.seg(d['t']) is not None]
    rng = np.random.default_rng(0)
    two = [d for d in pop if d['cover'] >= 2]
    one = [d for d in pop if d['cover'] == 1]
    one = [one[i] for i in rng.choice(len(one), min(max_one, len(one)), replace=False)]
    recs = two + one
    with open('/tmp/split-windows.bin', 'wb') as out, open('/tmp/split-shape-py.tsv', 'w') as tsv:
        out.write(struct.pack('<i', len(recs)))
        tsv.write('i\tarea\tiou1\tiou2\tm0x\tm0y\tm1x\tm1y\n')
        for i, d in enumerate(recs):
            win, (x0, y0), (cx, cy, r), med, sd = p.window(d['t'], d['x'], d['y'], d['r'])
            level = med + f.LEVEL_SIGMA * sd
            h, w = win.shape
            out.write(struct.pack('<ii4d', w, h, cx, cy, r, level))
            out.write(win.astype('<f8').tobytes())
            res = reference(win, cx, cy, r, level)
            if res is None:
                tsv.write(f'{i}\t0\tnan\tnan\tnan\tnan\tnan\tnan\n')
            else:
                area, i1, i2, mu = res
                mm = ['nan'] * 4 if mu is None else [mu[0][0], mu[0][1], mu[1][0], mu[1][1]]
                tsv.write(f'{i}\t{area}\t{i1}\t{i2}\t' + '\t'.join(str(v) for v in mm) + '\n')
    print(f'{len(recs)} windows ({len(two)} covering two cells, {len(one)} one) -> /tmp/split-windows.bin')
    return 0


if __name__ == '__main__':
    sys.exit(main())
