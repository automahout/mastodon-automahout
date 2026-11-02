"""How far below the gate is it safe to call a detection background OUTRIGHT?

DetectionGate currently withholds what it rejects (UNLABELED) and never tags it
fp, because 5.5% of real cells fall below the gate and teaching a dim real cell
as background is the failure that removed the earlier reclassification passes.

But that leaves the pipeline with three problems at once, all visible in the
report: detections sitting on background survive in the result, they are not
taught as negatives, and the fp synthesis meanwhile cannot manufacture negatives
("77% of candidates rejected as holding signal") while measured background sits
unused in the graph.

The real cells that fall below the gate should be near the boundary, not deep in
the noise. This measures where "deep" starts: the standout at which calling a
detection background costs essentially no real cells.

Run: <venv>/bin/python measure_deep.py
"""
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
CELLS = np.load(HERE / 'cells.npy')
BG = np.load(HERE / 'background.npy')

GATE_SIGMA = 3.0


def main():
    cs, bs = CELLS[:, 0], BG[:, 0]
    print(f'{len(cs)} truth cells, {len(bs)} background peaks')
    print(f'cells standout p01/p05 {np.quantile(cs, .01):.2f}/{np.quantile(cs, .05):.2f}, '
          f'background p95/p99 {np.quantile(bs, .95):.2f}/{np.quantile(bs, .99):.2f}\n')

    below = cs < GATE_SIGMA
    print(f'real cells the gate already withholds (standout < {GATE_SIGMA}): '
          f'{below.mean():.2%} ({below.sum()} of {len(cs)})')
    if below.any():
        print(f'  their standout: min {cs[below].min():.2f}, '
              f'median {np.median(cs[below]):.2f}, max {cs[below].max():.2f}')

    print('\n  calling a detection background outright, by threshold:')
    print('    standout < | real cells caught | background caught | cells lost per 100 bg gained')
    for thr in (0.5, 1.0, 1.5, 2.0, 2.5, 3.0):
        c = float((cs < thr).mean())
        b = float((bs < thr).mean())
        ratio = (c * len(cs)) / max(b * len(bs), 1e-9) * 100
        print(f'    {thr:9.1f} | {c:16.2%} | {b:16.1%} | {ratio:27.2f}')

    print('\n  and the same, restricted to what the gate ALREADY withholds')
    print('  (so this only changes the label on spots that are already not taught as cells):')
    wc = cs[cs < GATE_SIGMA]
    wb = bs[bs < GATE_SIGMA]
    for thr in (0.5, 1.0, 1.5, 2.0):
        c = float((wc < thr).sum())
        b = float((wb < thr).sum())
        print(f'    {thr:9.1f} | {c:6.0f} real cell(s) of {len(wc)} withheld | '
              f'{b:6.0f} background of {len(wb)} | purity {b / max(b + c, 1):.2%}')


main()
