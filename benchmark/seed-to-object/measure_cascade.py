"""Does a merged spot grow across passes until it swallows a neighbourhood?

The pairing gate is `distance < 2.2 * max(radius)`, and a merge replaces the
group with an ellipsoid covering it. Merging two spots d apart gives a
semi-major axis of d/2 + max(r), and the bounding-sphere radius every later pass
reads IS that semi-major axis. So a merge at the edge of the gate produces a
spot of 1.1r + r = 2.1r, whose reach next pass is 2.2 * 2.1r = 4.6r.

The pipeline runs this several times per window: [3/6] commit, [5/6] correct,
[5c/6] topology, and Phase 2's whole-movie globalCleanup. This simulates those
rounds on truth-seeded detections and reports how far one spot gets.

MERGE_MAX_RADIUS_FACTOR = 3.0 bounds each merge against the GROUP's own largest
member, which is the quantity that just grew -- so it bounds the step, not the
walk.

Run: <venv>/bin/python measure_cascade.py
"""
import numpy as np

import measure_arbiter as M
from measure_grouping import components, detections

SEPARATION_TOO_FAR = 2.2
MERGE_MAX_RADIUS_FACTOR = 3.0


def one_round(img, spots, med, sigma, cap_abs=None):
    """spots: list of [y, x, r, {truth labels}]. Returns the next round's spots."""
    n = len(spots)
    edges = {}
    for i in range(n):
        for j in range(i + 1, n):
            d = np.hypot(spots[i][0] - spots[j][0], spots[i][1] - spots[j][1])
            if not (0 < d < SEPARATION_TOO_FAR * max(spots[i][2], spots[j][2])):
                continue
            v = M.local_watershed(img, (int(spots[i][0]), int(spots[i][1])),
                                  (int(spots[j][0]), int(spots[j][1])),
                                  med, sigma, 3.0, 2)
            edges[(i, j)] = bool(v) if v is not None else False

    groups = components(n, edges)
    merged_idx = set()
    out = []
    for g in groups:
        ys = np.mean([spots[i][0] for i in g])
        xs = np.mean([spots[i][1] for i in g])
        rmax = max(spots[i][2] for i in g)
        span = max(np.hypot(spots[i][0] - spots[j][0], spots[i][1] - spots[j][1])
                   for i in g for j in g)
        new_r = span / 2 + rmax                      # the covering ellipsoid's semi-major axis
        if new_r > MERGE_MAX_RADIUS_FACTOR * rmax:   # the cap as shipped
            continue
        if cap_abs is not None and new_r > cap_abs:  # the proposed absolute cap
            continue
        labs = set()
        for i in g:
            labs |= spots[i][3]
        out.append([ys, xs, new_r, labs])
        merged_idx.update(g)
    for i in range(n):
        if i not in merged_idx:
            out.append(spots[i])
    return out


def run(cap_abs, label):
    print(f'\n  {label}')
    print('    round | spots | max radius px | groups spanning >1 cell | worst span')
    per_frame = []
    for img, seg in M.frames():
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        spots = [[y, x, r, {lab}] for y, x, r, lab in detections(img, seg)]
        per_frame.append((img, med, sigma, spots))
    for rnd in range(1, 5):
        maxr, spans, total = [], [], 0
        nxt = []
        for img, med, sigma, spots in per_frame:
            spots = one_round(img, spots, med, sigma, cap_abs)
            nxt.append((img, med, sigma, spots))
            total += len(spots)
            for s in spots:
                maxr.append(s[2])
                spans.append(len(s[3]))
        per_frame = nxt
        spans = np.array(spans)
        print(f'    {rnd:5d} | {total:5d} | {np.max(maxr):13.1f} | '
              f'{np.mean(spans > 1):22.1%} | {spans.max():10d}')


print('true cell radius p50 is 12.9 px, p95 15.5 px')
run(None, 'as shipped: cap = 3x the group\'s own largest member')
run(20.0, 'with an absolute cap of 20 px (~p99 of a real cell)')
