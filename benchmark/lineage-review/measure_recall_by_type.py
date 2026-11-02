"""Does self-training favour the typical cell? Recall by cell type, before and after a training round.

The user's hypothesis: nearly every detection is taught, so the many cells near the middle of the
distribution dominate training, and cells off it -- dim, small, large -- are learned less well.

Each window detects at [3/6] and, after one more round of training on its own detections, again at
[5b/6]. If training on the typical cell over-fits, recall of the atypical should FALL from [3/6] to
[5b/6] while the typical holds.

Cells are the silver-truth masks (01_ST/SEG): area, and mean intensity in spreads above the frame
median. Bins are quintiles over every truth cell of the run's frames. A cell is detected when a
detection centre (fp negatives excluded) lies inside its mask.

    python measure_recall_by_type.py stamp
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from scipy import ndimage as ndi

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'split-review'))
import measure_convergence as mc  # noqa: E402
import prototype_splitter as ps  # noqa: E402


def snapshots(stamp):
    out = {}
    with open(f'/tmp/origin-dump-{stamp}.tsv') as fh:
        fh.readline()
        for line in fh:
            q = line.split('\t', 4)
            if q[0] != 'rev':
                out[int(q[1])] = q[2]
    return out


def cells(t):
    seg = ps.seg(t)
    if seg is None:
        return []
    im, med, sd = ps.image(t)
    labs = np.unique(seg)
    labs = labs[labs > 0]
    area = ndi.sum( np.ones_like(seg), seg, labs ) * ps.VOXEL_UM ** 2
    mean = ( ndi.mean(im, seg, labs) - med ) / sd
    return list(zip(labs, area, mean))


def main():
    stamp = sys.argv[1]
    snaps = snapshots(stamp)
    pairs = []
    for s, lab in snaps.items():
        m = re.match(r'\[3/6\] commit detection t=(\d+)\.\.(\d+)', lab)
        if m:
            t0, t1 = int(m.group(1)), int(m.group(2))
            after = [k for k, v in snaps.items() if v == f'[5b/6] refinement t={t0}..{t1}']
            if after:
                pairs.append((t0, t1, s, after[0]))
    rows = []   # (area, brightness, detected_before, detected_after)
    for t0, t1, sb, sa in sorted(pairs):
        for snap, idx in ((sb, 0), (sa, 1)):
            pass
        b = mc.load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), sb)
        a = mc.load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), sa)
        for t in range(t0, t1 + 1):
            hit_b = {ps.truth_at(t, s['x'], s['y']) for s in b.values() if s['t'] == t and s['tag'] != 'fp'}
            hit_a = {ps.truth_at(t, s['x'], s['y']) for s in a.values() if s['t'] == t and s['tag'] != 'fp'}
            for lab, area, mean in cells(t):
                rows.append((area, mean, lab in hit_b, lab in hit_a))
    if not rows:
        print('no [3/6]/[5b/6] pairs')
        return 1
    R = np.array(rows, dtype=float)
    print(f'{stamp}: {len(pairs)} windows, {len(R)} truth cells; recall [3/6] -> [5b/6]')
    for name, col in (('brightness (spreads)', 1), ('area (um^2)', 0)):
        edges = np.percentile(R[:, col], [0, 20, 40, 60, 80, 100])
        print(f'  by {name}:')
        for i in range(5):
            m = (R[:, col] >= edges[i]) & (R[:, col] <= edges[i + 1] if i == 4 else R[:, col] < edges[i + 1])
            print(f'    q{i + 1} {edges[i]:8.1f}..{edges[i + 1]:8.1f}  n={m.sum():5d}  '
                  f'{R[m, 2].mean():.3f} -> {R[m, 3].mean():.3f}  ({R[m, 3].mean() - R[m, 2].mean():+.3f})')
    return 0


if __name__ == '__main__':
    sys.exit(main())
