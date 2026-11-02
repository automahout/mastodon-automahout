"""Compare ShapeSplit (Java) with the Python reference on the same windows."""
import csv
import sys

import numpy as np


def load(p):
    return {int(r['i']): r for r in csv.DictReader(open(p), delimiter='\t')}


def main():
    py, jv = load('/tmp/split-shape-py.tsv'), load('/tmp/split-shape-java.tsv')
    worst = {'area': 0, 'iou1': 0, 'iou2': 0, 'means': 0}
    mismatch_refusal = 0
    for i, a in py.items():
        b = jv[i]
        if int(a['area']) != int(b['area']):
            worst['area'] = max(worst['area'], abs(int(a['area']) - int(b['area'])))
        for k in ('iou1', 'iou2'):
            x, y = float(a[k]), float(b[k])
            if np.isnan(x) != np.isnan(y):
                mismatch_refusal += 1
            elif not np.isnan(x):
                worst[k] = max(worst[k], abs(x - y))
        ma = np.array([float(a[k]) for k in ('m0x', 'm0y', 'm1x', 'm1y')]).reshape(2, 2)
        mb = np.array([float(b[k]) for k in ('m0x', 'm0y', 'm1x', 'm1y')]).reshape(2, 2)
        if not np.isnan(ma).any() and not np.isnan(mb).any():
            d = min(np.abs(ma - mb).max(), np.abs(ma - mb[::-1]).max())   # component order is arbitrary
            worst['means'] = max(worst['means'], d)
    print(f'{len(py)} windows; refusals disagreeing: {mismatch_refusal}')
    for k, v in worst.items():
        print(f'  worst |python - java| {k:6s} {v:.2e}')
    ok = mismatch_refusal == 0 and worst['area'] == 0 and max(worst['iou1'], worst['iou2']) < 1e-9 \
        and worst['means'] < 1e-6
    print('PARITY OK' if ok else 'PARITY FAILED')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
