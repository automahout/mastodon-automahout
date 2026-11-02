"""What should bound the merged spot's size?

Three candidates, on truth-seeded detections merged once:

  members   <= 3x the GROUP's own largest member -- as shipped. Bounds the step,
            not the result: the quantity it measures is the one that just grew.
  absolute  a fixed radius in px
  measured  <= k x the CELL radius measured from the image at the merged centre,
            using the ray(5 sigma, 8 rays) estimator from benchmark/cell-extent

Scored on both sides: duplicates actually removed, and groups that swallow more
than one truth cell.

Run: <venv>/bin/python measure_cap.py
"""
import numpy as np
import scipy.ndimage as nd

import measure_arbiter as M
from measure_grouping import components, detections

SEPARATION_TOO_FAR = 2.2


def ray_radius(img, y, x, med, sigma, k=5.0, n_rays=8, max_r=40):
    level = med + k * sigma
    if img[int(y), int(x)] < level:
        return np.nan
    rs = []
    for a in np.linspace(0, 2 * np.pi, n_rays, endpoint=False):
        dy, dx = np.sin(a), np.cos(a)
        hit = max_r
        for step in range(1, max_r):
            yy, xx = int(round(y + dy * step)), int(round(x + dx * step))
            if not (0 <= yy < img.shape[0] and 0 <= xx < img.shape[1]):
                hit = step
                break
            if img[yy, xx] < level:
                hit = step
                break
        rs.append(hit)
    return float(np.median(rs))


def evaluate(rule, param):
    removed = over = groups = 0
    maxr = 0.0
    for img, seg in M.frames():
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        dets = [[y, x, r, {lab}] for y, x, r, lab in detections(img, seg)]
        n = len(dets)
        edges = {}
        for i in range(n):
            for j in range(i + 1, n):
                d = np.hypot(dets[i][0] - dets[j][0], dets[i][1] - dets[j][1])
                if not (0 < d < SEPARATION_TOO_FAR * max(dets[i][2], dets[j][2])):
                    continue
                v = M.local_watershed(img, (int(dets[i][0]), int(dets[i][1])),
                                      (int(dets[j][0]), int(dets[j][1])),
                                      med, sigma, 3.0, 2)
                edges[(i, j)] = bool(v) if v is not None else False
        for g in components(n, edges):
            ys = np.mean([dets[i][0] for i in g])
            xs = np.mean([dets[i][1] for i in g])
            rmax = max(dets[i][2] for i in g)
            span = max(np.hypot(dets[i][0] - dets[j][0], dets[i][1] - dets[j][1])
                       for i in g for j in g)
            new_r = span / 2 + rmax
            if rule == 'pairs' and len(g) > param:
                continue
            if rule == 'pair+cap' and (len(g) > 2 or new_r > param * rmax):
                continue
            if rule == 'members' and new_r > param * rmax:
                continue
            if rule == 'absolute' and new_r > param:
                continue
            if rule == 'measured':
                cell = ray_radius(img, ys, xs, med, sigma)
                if np.isnan(cell) or new_r > param * cell:
                    continue
            labs = set()
            for i in g:
                labs |= dets[i][3]
            groups += 1
            removed += len(g) - 1
            maxr = max(maxr, new_r)
            if len(labs) > 1:
                over += 1
    return removed, over, groups, maxr


print('326 of 3202 detections are genuine duplicates; true cell radius p50 12.9 px\n')
print('  rule                    | duplicates removed | groups swallowing >1 cell | max radius')
for rule, params in (('pair+cap', (2.0, 1.8, 1.6)),
                     ('pairs', (2, 3)),
                     ('members', (3.0, 2.0, 1.6)),
                     ('absolute', (30.0, 25.0, 20.0)),
                     ('measured', (2.0, 1.6, 1.3, 1.1))):
    for p in params:
        rem, over, grp, mx = evaluate(rule, p)
        print(f'  {rule:8s} {float(p):5.1f}          | {rem:4d} / 326 ({rem / 326:5.1%}) | '
              f'{over:3d} / {grp:3d} ({over / max(grp,1):5.1%})        | {mx:5.1f} px')
