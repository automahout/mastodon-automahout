"""Can one detection be told to cover two cells?

The merge arbiter answers "are these two seeds one object?". Nothing answers the
inverse -- "does this one detection cover more than one object?" -- and that is
the case the user is looking at: the model produced a single detection over a
touching pair, so there is no second detection for the neighbour cap to protect.

Populations come from the truth, never from a detector:

  ONE  a detection centred on a truth cell, radius inflated by a realistic
       amount. Must NOT be split.
  TWO  a detection centred on the midpoint of two adjacent truth cells and wide
       enough to cover both -- the failure being measured. Must be split.

The classifier reuses the primitives already shipped in SeedToObject: threshold
at OBJECT_LEVEL_SIGMA, distance transform, peak markers. It counts the markers
lying inside the detection.
"""
import numpy as np
import scipy.ndimage as nd
from skimage.feature import peak_local_max

import measure_arbiter as M  # noqa: E402  (module prints on import)

OBJECT_LEVEL_SIGMA = 3.0   # SeedToObject.OBJECT_LEVEL_SIGMA (swept below)
STRIDE = 2                 # SeedToObject.WINDOW_STRIDE
INFLATE = 1.2              # a realistic slightly-wide detection over one cell
NEAR = 2.2                 # the separation band the pipeline considers at all


def basins_under(img, med, sigma, cy, cx, radius, min_distance_px, level_sigma=OBJECT_LEVEL_SIGMA):
    """Markers of the distance transform lying inside the detection."""
    margin = int(radius * 2) + 8
    y0, y1 = max(0, int(cy) - margin), min(img.shape[0], int(cy) + margin + 1)
    x0, x1 = max(0, int(cx) - margin), min(img.shape[1], int(cx) + margin + 1)
    win = img[y0:y1:STRIDE, x0:x1:STRIDE]
    if win.size == 0:
        return None
    mask = win > med + level_sigma * sigma
    if not mask.any():
        return None
    dist = nd.distance_transform_edt(mask)
    pk = peak_local_max(dist, min_distance=max(2, int(min_distance_px / STRIDE)),
                        labels=mask)
    if len(pk) == 0:
        return None
    py, px = (cy - y0) / STRIDE, (cx - x0) / STRIDE
    r = radius / STRIDE
    inside = [(y, x) for y, x in pk if (y - py) ** 2 + (x - px) ** 2 <= r * r]
    return len(inside)


def populations():
    """Each entry carries the frame's own median cell radius, so a detection can
    be judged large-for-its-frame without any truth."""
    one, two = [], []
    for img, seg in M.frames():
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        cells = M.cells_of(seg)
        cen = np.array([[c[3], c[4]] for c in cells])
        rad = np.array([c[5] for c in cells])
        for i, c in enumerate(cells):
            one.append((img, med, sigma, c[3], c[4], c[5] * INFLATE, float(np.median(rad)),
                        float(img[int(c[3]), int(c[4])] - med) / sigma))
        for i in range(len(cells)):
            d = np.hypot(cen[:, 0] - cen[i, 0], cen[:, 1] - cen[i, 1])
            d[i] = np.inf
            j = int(np.argmin(d))
            if j <= i or not np.isfinite(d[j]):
                continue
            if d[j] > NEAR * max(rad[i], rad[j]):
                continue
            my, mx = (cen[i, 0] + cen[j, 0]) / 2, (cen[i, 1] + cen[j, 1]) / 2
            r = d[j] / 2 + (rad[i] + rad[j]) / 2
            two.append((img, med, sigma, my, mx, r, float(np.median(rad)),
                        min(float(img[int(cen[i,0]), int(cen[i,1])] - med) / sigma,
                            float(img[int(cen[j,0]), int(cen[j,1])] - med) / sigma)))
    return one, two


def main():
    one, two = populations()
    print(f"\n{len(one)} one-cell detections, {len(two)} two-cell detections")

    print("\nEvery detection considered:")
    print(f"{'min marker sep (px)':<22}{'ONE kept whole':>17}{'TWO split':>12}{'abstain':>10}")
    for mind in (5, 6, 8, 10, 12):
        k = [v for v in (basins_under(*o[:6], mind) for o in one) if v is not None]
        sp = [v for v in (basins_under(*t[:6], mind) for t in two) if v is not None]
        ab = len(one) + len(two) - len(k) - len(sp)
        print(f"{mind:<22}{100 * np.mean([v <= 1 for v in k]):>16.1f}%"
              f"{100 * np.mean([v >= 2 for v in sp]):>11.1f}%{ab:>10}")

    print("\nOnly detections large for their frame (radius > g x the frame median):")
    print(f"{'gate g':<10}{'ONE considered':>16}{'TWO considered':>16}"
          f"{'ONE kept whole':>16}{'TWO split':>11}")
    mind = 8
    for g in (1.0, 1.2, 1.4, 1.6, 1.8):
        ko = [o for o in one if o[5] > g * o[6]]
        kt = [t for t in two if t[5] > g * t[6]]
        k = [v for v in (basins_under(*o[:6], mind) for o in ko) if v is not None]
        sp = [v for v in (basins_under(*t[:6], mind) for t in kt) if v is not None]
        # A detection the gate never looks at is left whole, which is the right answer
        # for ONE and the wrong one for TWO; both are counted over the FULL population.
        kept = (len(one) - len(k)) + sum(1 for v in k if v <= 1)
        split = sum(1 for v in sp if v >= 2)
        print(f"{g:<10.1f}{len(ko):>15}{len(kt):>16}"
              f"{100 * kept / len(one):>15.1f}%{100 * split / len(two):>10.1f}%")


if __name__ == '__main__':
    main()
