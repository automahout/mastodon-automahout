"""Are the over-segmented pairs even being PROPOSED?

overlapsAsFragments gates pair candidates at `distance < 2.2 * max(DETECTION
radius)`. The 2.2 was measured in CELL radii, but the pipeline applies it to the
detection's own radius, and the run log reports detections at 6.6-7.0 um against
a true cell median of 8.3 um -- about 0.82x. A pair tiling one cell at 1.82 cell
radii therefore reads as 1.82/0.82 = 2.2 DETECTION radii, exactly at the gate,
and anything wider is never even offered to the arbiter.

Sweeps the reach, with radii shrunk to detection size, and reports both sides.

Run: <venv>/bin/python measure_reach.py
"""
import numpy as np

import measure_arbiter as M
from measure_grouping import components, detections

DETECTION_RATIO = 0.82   # detections are this fraction of the true cell radius
MERGE_MAX_GROUP = 2


def evaluate(reach):
    removed = over = groups = 0
    for img, seg in M.frames():
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        dets = [[y, x, r * DETECTION_RATIO, {lab}]
                for y, x, r, lab in detections(img, seg)]
        n = len(dets)
        edges = {}
        for i in range(n):
            for j in range(i + 1, n):
                d = np.hypot(dets[i][0] - dets[j][0], dets[i][1] - dets[j][1])
                if not (0 < d < reach * max(dets[i][2], dets[j][2])):
                    continue
                v = M.local_watershed(img, (int(dets[i][0]), int(dets[i][1])),
                                      (int(dets[j][0]), int(dets[j][1])),
                                      med, sigma, 3.0, 2)
                edges[(i, j)] = bool(v) if v is not None else False
        for g in components(n, edges):
            if len(g) > MERGE_MAX_GROUP:
                continue
            labs = set()
            for i in g:
                labs |= dets[i][3]
            groups += 1
            removed += len(g) - 1
            if len(labs) > 1:
                over += 1
    return removed, over, groups


print(f'detections simulated at {DETECTION_RATIO}x the true cell radius, '
      f'groups capped at {MERGE_MAX_GROUP}')
print('326 of 3202 detections are genuine duplicates\n')
print('  reach (x detection radius) | duplicates removed | groups swallowing >1 cell')
for reach in (2.2, 2.5, 2.75, 3.0, 3.5):
    rem, over, grp = evaluate(reach)
    print(f'  {reach:26.2f} | {rem:4d} / 326 ({rem / 326:5.1%}) | '
          f'{over:3d} / {grp:3d} ({over / max(grp, 1):5.1%})')
