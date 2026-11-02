"""Prototype splitters, scored on the detector's own detections against silver truth.

The production split pass places its pieces by counting intensity basins
(SeedToObject). Measured on a live run it put each piece on its own cell for
5 of 19 detections that genuinely covered two cells, and split 174 single
nuclei. This tries replacements offline on the CTC images, at the positions and
radii the detector actually produced (the split dump's commit population).

Scoring, per detection, with the truth label independent of the detection's
own radius (truth centroids within the frame's median detection radius of the
centre):
  covering TWO+: right when it yields exactly one piece per covered cell, each
                 piece on a different truth cell (none on background);
  covering ONE:  right when it is left whole.

    python prototype_splitter.py [stamp]
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage as ndi
from skimage.morphology import h_maxima
from skimage.segmentation import watershed

sys.path.insert(0, str(Path(__file__).parent))
import measure_split_decisions as m  # noqa: E402

ROOT = m.ROOT
VOXEL_UM = m.VOXEL_UM
REACH = 1.4           # window half-side, in detection radii
_img = {}
_seg = {}


def image(t):
    if t not in _img:
        im = np.array(Image.open(ROOT / '01' / f't{t:03d}.tif')).astype(float)
        med = np.median(im)
        sd = 1.4826 * np.median(np.abs(im - med))
        _img[t] = (im, med, sd)
    return _img[t]


def seg(t):
    if t not in _seg:
        f = ROOT / '01_ST' / 'SEG' / f'man_seg{t:03d}.tif'
        _seg[t] = np.array(Image.open(f)) if f.exists() else None
    return _seg[t]


def window(t, x_um, y_um, r_um):
    im, med, sd = image(t)
    cx, cy, r = x_um / VOXEL_UM, y_um / VOXEL_UM, r_um / VOXEL_UM
    half = int(np.ceil(REACH * r)) + 1
    x0, y0 = max(0, int(cx) - half), max(0, int(cy) - half)
    x1, y1 = min(im.shape[1], int(cx) + half + 1), min(im.shape[0], int(cy) + half + 1)
    return im[y0:y1, x0:x1], (x0, y0), (cx - x0, cy - y0, r), med, sd


def split_shape(t, d, sigma_level=5.0, h_px=1.5, min_sep_radii=0.6):
    """Threshold at the frame's own level, keep the object under the centre, count the peaks of its
    distance transform. Returns piece centres in world units (one entry = left whole)."""
    win, (x0, y0), (cx, cy, r), med, sd = window(t, d['x'], d['y'], d['r'])
    mask = win > med + sigma_level * sd
    lab, n = ndi.label(mask)
    if n == 0:
        return [(d['x'], d['y'])]
    ix, iy = int(round(cx)), int(round(cy))
    ix = min(max(ix, 0), win.shape[1] - 1)
    iy = min(max(iy, 0), win.shape[0] - 1)
    keep = lab[iy, ix]
    if keep == 0:
        # centre on a dark pixel: the largest component inside the disc
        yy, xx = np.mgrid[0:win.shape[0], 0:win.shape[1]]
        inside = (xx - cx) ** 2 + (yy - cy) ** 2 < r * r
        counts = np.bincount(lab[inside].ravel(), minlength=n + 1)
        counts[0] = 0
        keep = int(counts.argmax())
        if keep == 0:
            return [(d['x'], d['y'])]
    obj = lab == keep
    edt = ndi.distance_transform_edt(obj)
    peaks = h_maxima(edt, h_px) & obj
    plab, npk = ndi.label(peaks)
    if npk < 2:
        return [(d['x'], d['y'])]
    # merge peaks closer than the minimum separation (a frame-relative length)
    cents = np.array(ndi.center_of_mass(peaks, plab, range(1, npk + 1)))
    min_sep = min_sep_radii * d['frameR'] / VOXEL_UM
    order = np.argsort([-edt[int(round(a)), int(round(b))] for a, b in cents])
    chosen = []
    for k in order:
        if all(np.hypot(*(cents[k] - cents[j])) >= min_sep for j in chosen):
            chosen.append(k)
    if len(chosen) < 2:
        return [(d['x'], d['y'])]
    markers = np.zeros_like(plab)
    for i, k in enumerate(chosen, 1):
        markers[plab == k + 1] = i
    regions = watershed(-edt, markers, mask=obj)
    out = []
    for i in range(1, len(chosen) + 1):
        ys, xs = np.nonzero(regions == i)
        if len(xs) == 0:
            continue
        out.append(((xs.mean() + x0) * VOXEL_UM, (ys.mean() + y0) * VOXEL_UM))
    return out if len(out) >= 2 else [(d['x'], d['y'])]


def object_mask(t, d, sigma_level):
    """The thresholded object under the detection, or None. Returns (mask, x0, y0, cx, cy, r)."""
    win, (x0, y0), (cx, cy, r), med, sd = window(t, d['x'], d['y'], d['r'])
    mask = win > med + sigma_level * sd
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
    return lab == keep, x0, y0, cx, cy, r


def split_neck(t, d, sigma_level=5.0, neck_max=0.6, min_sep_radii=0.6):
    """Two distance-transform peaks, split only when the object is pinched between them: the
    distance transform along the segment joining them drops below neck_max x the smaller peak."""
    got = object_mask(t, d, sigma_level)
    if got is None:
        return [(d['x'], d['y'])]
    obj, x0, y0, cx, cy, r = got
    edt = ndi.distance_transform_edt(obj)
    peaks = h_maxima(edt, 0.5) & obj
    plab, npk = ndi.label(peaks)
    if npk < 2:
        return [(d['x'], d['y'])]
    cents = np.array(ndi.center_of_mass(peaks, plab, range(1, npk + 1)))
    val = np.array([edt[int(round(a)), int(round(b))] for a, b in cents])
    order = np.argsort(-val)
    min_sep = min_sep_radii * d['frameR'] / VOXEL_UM
    a = order[0]
    b = next((k for k in order[1:] if np.hypot(*(cents[k] - cents[a])) >= min_sep), None)
    if b is None:
        return [(d['x'], d['y'])]
    n = int(np.ceil(np.hypot(*(cents[b] - cents[a])))) * 2 + 2
    line = np.linspace(cents[a], cents[b], n)
    along = ndi.map_coordinates(edt, line.T, order=1)
    if along.min() >= neck_max * min(val[a], val[b]):
        return [(d['x'], d['y'])]
    markers = np.zeros_like(plab)
    markers[plab == a + 1] = 1
    markers[plab == b + 1] = 2
    regions = watershed(-edt, markers, mask=obj)
    out = []
    for i in (1, 2):
        ys, xs = np.nonzero(regions == i)
        out.append(((xs.mean() + x0) * VOXEL_UM, (ys.mean() + y0) * VOXEL_UM))
    return out


def _gauss_ll(X, mu, cov):
    inv = np.linalg.inv(cov)
    dlt = X - mu
    return -0.5 * np.einsum('ij,jk,ik->i', dlt, inv, dlt) - 0.5 * np.log(np.linalg.det(cov)) - np.log(2 * np.pi)


def split_gmm(t, d, sigma_level=5.0, bic_margin=0.0, min_sep_radii=0.6, iters=40):
    """One ellipse or two over the object's pixels: split when two explain the SHAPE better by BIC
    (per-pixel log-likelihood, 5 extra parameters) and their centres are a cell apart."""
    got = object_mask(t, d, sigma_level)
    if got is None:
        return [(d['x'], d['y'])]
    obj, x0, y0, cx, cy, r = got
    ys, xs = np.nonzero(obj)
    X = np.stack([xs, ys], 1).astype(float)
    if len(X) < 20:
        return [(d['x'], d['y'])]
    mu1, c1 = X.mean(0), np.cov(X.T) + 0.25 * np.eye(2)
    ll1 = _gauss_ll(X, mu1, c1).sum()
    # EM for two components, seeded along the main axis
    w, v = np.linalg.eigh(c1)
    ax = v[:, -1] * np.sqrt(w[-1])
    mu = np.array([mu1 - ax, mu1 + ax])
    cov = np.array([c1 / 2, c1 / 2])
    pi = np.array([0.5, 0.5])
    for _ in range(iters):
        lp = np.stack([np.log(pi[k]) + _gauss_ll(X, mu[k], cov[k]) for k in range(2)], 1)
        mx = lp.max(1, keepdims=True)
        resp = np.exp(lp - mx)
        resp /= resp.sum(1, keepdims=True)
        nk = resp.sum(0)
        if (nk < 5).any():
            return [(d['x'], d['y'])]
        pi = nk / nk.sum()
        mu = (resp.T @ X) / nk[:, None]
        cov = np.array([((resp[:, k:k + 1] * (X - mu[k])).T @ (X - mu[k])) / nk[k] + 0.25 * np.eye(2)
                        for k in range(2)])
    lp = np.stack([np.log(pi[k]) + _gauss_ll(X, mu[k], cov[k]) for k in range(2)], 1)
    mx = lp.max(1)
    ll2 = (mx + np.log(np.exp(lp - mx[:, None]).sum(1))).sum()
    n = len(X)
    bic1 = -2 * ll1 + 5 * np.log(n)
    bic2 = -2 * ll2 + 11 * np.log(n)
    # per-pixel improvement, so the margin does not scale with object size
    gain = (bic1 - bic2) / n
    if gain <= bic_margin:
        return [(d['x'], d['y'])]
    if np.hypot(*(mu[0] - mu[1])) < min_sep_radii * d['frameR'] / VOXEL_UM:
        return [(d['x'], d['y'])]
    return [((mu[k][0] + x0) * VOXEL_UM, (mu[k][1] + y0) * VOXEL_UM) for k in range(2)]


def truth_at(t, x_um, y_um):
    s = seg(t)
    col, row = int(round(x_um / VOXEL_UM)), int(round(y_um / VOXEL_UM))
    if s is None or not (0 <= row < s.shape[0] and 0 <= col < s.shape[1]):
        return 0
    return int(s[row, col])


def population(stamp, only_big=False):
    split_path = next(Path('/tmp').glob(f'split-fixture*-{stamp}.tsv'))
    out = []
    for t, dets in sorted(m.last_block_per_t(split_path).items()):
        c = m.centroids(t)
        if c is None:
            continue
        for d in dets:
            here = np.array([d['x'], d['y']])
            near = np.hypot(*(c - here).T) < d['frameR']
            out.append(dict(t=t, cover=int(near.sum()), **d))
    return out


def score(pop, splitter, **kw):
    two_right = two_n = 0
    one_split = one_n = 0
    big_split = big_n = 0
    for d in pop:
        pieces = splitter(d['t'], d, **kw)
        if d['cover'] >= 2:
            two_n += 1
            labs = [truth_at(d['t'], x, y) for x, y in pieces]
            if len(pieces) >= 2 and all(l > 0 for l in labs) and len(set(labs)) == len(labs):
                two_right += 1
        elif d['cover'] == 1:
            one_n += 1
            one_split += len(pieces) >= 2
            if d['r'] >= 12.0:
                big_n += 1
                big_split += len(pieces) >= 2
    return two_right, two_n, one_split, one_n, big_split, big_n


def main():
    stamp = sys.argv[1] if len(sys.argv) > 1 else m.newest_stamp()
    pop = population(stamp)
    pop = [d for d in pop if seg(d['t']) is not None]
    print(f'{len(pop)} detections with truth; two-cell {sum(d["cover"] >= 2 for d in pop)}, '
          f'one-cell {sum(d["cover"] == 1 for d in pop)}\n')
    print('| splitter | two-cell split right | one-cell split (wrong) | one-cell r>=12 split (wrong) |')
    print('|---|---|---|---|')
    def show(name, **kw):
        fn = kw.pop('fn')
        tr, tn, os_, on, bs, bn = score(pop, fn, **kw)
        print(f'| {name} | {tr}/{tn} ({tr / max(tn, 1):.0%}) | {os_}/{on} ({os_ / max(on, 1):.1%}) '
              f'| {bs}/{bn} ({bs / max(bn, 1):.0%}) |')
    which = sys.argv[2] if len(sys.argv) > 2 else 'neck,gmm'
    if 'shape' in which:
        show('shape 8σ h=1 sep=0.6 (best above)', fn=split_shape, sigma_level=8.0, h_px=1.0, min_sep_radii=0.6)
    if 'neck' in which:
        for lvl in (5.0, 8.0):
            for nk in (0.4, 0.55, 0.7):
                show(f'neck level={lvl:g}σ neck<{nk:g}', fn=split_neck, sigma_level=lvl, neck_max=nk)
    if 'gmm' in which:
        for lvl in (5.0, 8.0):
            for mg in (0.0, 0.05, 0.1, 0.2):
                show(f'gmm level={lvl:g}σ gain>{mg:g}', fn=split_gmm, sigma_level=lvl, bic_margin=mg)
    return 0


if __name__ == '__main__':
    sys.exit(main())
