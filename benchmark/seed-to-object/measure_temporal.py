"""Does the neighbouring timepoint agree that there are two cells here?

Three single-frame criteria are already stacked on this decision -- the core's
unevenness, the distance-transform basin count, the intensity saddle -- and all
three read the same frame. This measures an independent one: a spurious split is
unstable in time, a real pair persists.

What the pipeline can actually do. splitMergedDetections runs inside
predictWindow, BEFORE [4/6] links anything, so there is no track to follow. It
knows a detection's POSITION at t and can sample t-1 and t+1 there. Ordinary
motion on this dataset has a median of 1.5 um against a nuclear radius near 9.5,
so the same position is the right model for one frame either way.
"""
import functools
from pathlib import Path

import numpy as np
import scipy.ndimage as nd
from PIL import Image
from skimage.feature import peak_local_max

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
STRIDE, LEVEL_SIGMA, MIN_SEP_PX, MAX_SADDLE, MIN_CORE_CV = 2, 3.0, 8, 0.80, 0.25
SEPARATION_TOO_FAR = 2.2
MAX_PIECES = 3


@functools.lru_cache(maxsize=8)
def load(seq, t):
    f = ROOT / seq / f't{t:03d}.tif'
    s = ROOT / f'{seq}_ST' / 'SEG' / f'man_seg{t:03d}.tif'
    if not (f.exists() and s.exists()):
        return None
    return (np.array(Image.open(f)).astype('float32'),
            np.array(Image.open(s)))


def cells_of(seg):
    out = []
    objs = nd.find_objects(seg)
    for lab in np.unique(seg):
        if lab == 0:
            continue
        sl = objs[lab - 1]
        if sl is None:
            continue
        m = seg[sl] == lab
        area = int(m.sum())
        if area < 30:
            continue
        cy, cx = nd.center_of_mass(m)
        out.append((sl[0].start + cy, sl[1].start + cx, np.sqrt(area / np.pi)))
    return out


@functools.lru_cache(maxsize=8)
def stats(seq, t):
    img = load(seq, t)[0]
    med = float(np.median(img))
    return med, max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)


def says_two(img, med, sigma, cy, cx, radius):
    """The shipped single-frame verdict, evaluated at one position in one frame."""
    r = radius / STRIDE
    margin = int(radius * 2) + 8
    y0, y1 = max(0, int(cy) - margin), min(img.shape[0], int(cy) + margin + 1)
    x0, x1 = max(0, int(cx) - margin), min(img.shape[1], int(cx) + margin + 1)
    win = img[y0:y1:STRIDE, x0:x1:STRIDE].astype(float) - med
    if win.size == 0:
        return None
    py, px = (cy - y0) / STRIDE, (cx - x0) / STRIDE
    yy, xx = np.mgrid[0:win.shape[0], 0:win.shape[1]]
    d2 = (yy - py) ** 2 + (xx - px) ** 2
    core = win[d2 <= (0.5 * r) ** 2]
    if len(core) < 4 or core.mean() <= 0:
        return None
    if core.std() / core.mean() <= MIN_CORE_CV:
        return False
    mask = win > LEVEL_SIGMA * sigma
    if not mask.any():
        return False
    dist = nd.distance_transform_edt(mask)
    pk = peak_local_max(dist, min_distance=max(2, MIN_SEP_PX // STRIDE), labels=mask)
    ins = [(y, x) for y, x in pk if (y - py) ** 2 + (x - px) ** 2 <= r * r]
    if not 2 <= len(ins) <= MAX_PIECES:
        return False
    ins.sort(key=lambda p: -win[p[0], p[1]])
    a, b = ins[0], ins[1]
    n = max(2, abs(a[0] - b[0]) + abs(a[1] - b[1]))
    ys = np.linspace(a[0], b[0], n + 1).round().astype(int)
    xs = np.linspace(a[1], b[1], n + 1).round().astype(int)
    peak = min(win[a[0], a[1]], win[b[0], b[1]])
    return peak > 0 and win[ys, xs].min() / peak <= MAX_SADDLE


def populations(seq, first, last):
    """(cy, cx, radius, t, is_pair) over consecutive frames."""
    one, two = [], []
    for t in range(first, last + 1):
        cur = load(seq, t)
        if cur is None or load(seq, t - 1) is None or load(seq, t + 1) is None:
            continue
        img, seg = cur
        cells = cells_of(seg)
        for cy, cx, r in cells:
            one.append((cy, cx, r * 1.2, t))
        cen = np.array([[c[0], c[1]] for c in cells])
        rad = np.array([c[2] for c in cells])
        for i in range(len(cells)):
            d = np.hypot(cen[:, 0] - cen[i, 0], cen[:, 1] - cen[i, 1])
            d[i] = np.inf
            j = int(np.argmin(d))
            if j <= i or d[j] > SEPARATION_TOO_FAR * max(rad[i], rad[j]):
                continue
            two.append(((cen[i, 0] + cen[j, 0]) / 2, (cen[i, 1] + cen[j, 1]) / 2,
                        d[j] / 2 + (rad[i] + rad[j]) / 2, t))
    return one, two


def votes(seq, e):
    cy, cx, r, t = e
    out = []
    for dt in (-1, 0, 1):
        f = load(seq, t + dt)
        if f is None:
            continue
        med, sigma = stats(seq, t + dt)
        v = says_two(f[0], med, sigma, cy, cx, r)
        if v is not None:
            out.append(v)
    return out


def main(seq='01', first=1, last=40):
    one, two = populations(seq, first, last)
    print(f"seq {seq}, frames {first}-{last}: {len(one)} single cells, {len(two)} pairs\n")
    res = {}
    for name, pop in (('one', one), ('two', two)):
        res[name] = [votes(seq, e) for e in sorted(pop, key=lambda e: e[3])]
    print(f"{'rule':<40}{'true splits':>13}{'false splits':>14}")
    for label, rule in (
            ('this frame alone (shipped)', lambda v: v[1] if len(v) == 3 else any(v)),
            ('this frame AND either neighbour', lambda v: (v[1] if len(v) == 3 else any(v))
                and sum(v) >= 2),
            ('at least 2 of the 3 frames', lambda v: sum(v) >= 2),
            ('all 3 frames', lambda v: len(v) == 3 and all(v)),
    ):
        t = 100 * np.mean([rule(v) for v in res['two'] if v])
        f = 100 * np.mean([rule(v) for v in res['one'] if v])
        print(f"{label:<40}{t:>12.1f}%{f:>13.1f}%")


if __name__ == '__main__':
    main()
