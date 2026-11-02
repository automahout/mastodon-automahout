"""One row per detection: shape features of the thresholded object under it, and the truth.

Computed once (slow), analysed many times (fast). Features:
  iou1   IoU of the object with its moment ellipse (one uniform ellipse, same 2nd moments)
  iou2   IoU of the object with the union of the two ellipses from a 2-component fit
  solid  area / convex-hull area
  sep    distance between the two components' centres / frame median radius
  right2 whether the 2-component centres land on two different truth cells
plus radius, coreCv, extentRatio (if the dump has it), and cover (truth cells within the frame
median radius of the centre -- independent of the detection's own radius).

    python split_features.py [stamp] > /tmp/split-features-<stamp>.tsv
"""
import sys
from pathlib import Path

import numpy as np
from scipy import ndimage as ndi
from skimage.morphology import convex_hull_image

sys.path.insert(0, str(Path(__file__).parent))
import measure_split_decisions as m  # noqa: E402
import prototype_splitter as p  # noqa: E402

LEVEL_SIGMA = 5.0


def ellipse_mask(shape, mu, cov):
    """Uniform ellipse with the second moments of `cov`: semi-axes 2*sqrt(eigenvalues)."""
    yy, xx = np.mgrid[0:shape[0], 0:shape[1]]
    X = np.stack([xx - mu[0], yy - mu[1]], -1)
    inv = np.linalg.inv(cov * 4.0)
    return np.einsum('...i,ij,...j->...', X, inv, X) <= 1.0


def two_components(X, iters=40):
    mu1, c1 = X.mean(0), np.cov(X.T) + 0.25 * np.eye(2)
    w, v = np.linalg.eigh(c1)
    ax = v[:, -1] * np.sqrt(w[-1])
    mu = np.array([mu1 - ax, mu1 + ax])
    cov = np.array([c1 / 2, c1 / 2])
    pi = np.array([0.5, 0.5])
    for _ in range(iters):
        lp = np.stack([np.log(pi[k]) + p._gauss_ll(X, mu[k], cov[k]) for k in range(2)], 1)
        resp = np.exp(lp - lp.max(1, keepdims=True))
        resp /= resp.sum(1, keepdims=True)
        nk = resp.sum(0)
        if (nk < 5).any():
            return None
        pi = nk / nk.sum()
        mu = (resp.T @ X) / nk[:, None]
        cov = np.array([((resp[:, k:k + 1] * (X - mu[k])).T @ (X - mu[k])) / nk[k] + 0.25 * np.eye(2)
                        for k in range(2)])
    return mu1, c1, mu, cov


def iou(a, b):
    return (a & b).sum() / max((a | b).sum(), 1)


def main():
    stamp = sys.argv[1] if len(sys.argv) > 1 else m.newest_stamp()
    split_path = next(Path('/tmp').glob(f'split-fixture*-{stamp}.tsv'))
    head = open(split_path).readline().rstrip('\n').split('\t')
    has_extent = 'extentRatio' in head
    print('t\tx\ty\tr\tframeR\tcoreCv\textentRatio\tcover\tarea\tiou1\tiou2\tsolid\tsep\tright2')
    for t, dets in sorted(m.last_block_per_t(split_path).items()):
        c = m.centroids(t)
        if c is None or p.seg(t) is None:
            continue
        for d in dets:
            here = np.array([d['x'], d['y']])
            cover = int((np.hypot(*(c - here).T) < d['frameR']).sum())
            ext = d.get('extentRatio', float('nan')) if has_extent else float('nan')
            row = [t, d['x'], d['y'], d['r'], d['frameR'], d['coreCv'], ext, cover]
            got = p.object_mask(t, d, LEVEL_SIGMA)
            feats = [0, float('nan'), float('nan'), float('nan'), float('nan'), 0]
            if got is not None:
                obj, x0, y0, cx, cy, r = got
                ys, xs = np.nonzero(obj)
                X = np.stack([xs, ys], 1).astype(float)
                if len(X) >= 20:
                    fit = two_components(X)
                    hull = convex_hull_image(obj)
                    solid = obj.sum() / max(hull.sum(), 1)
                    if fit is not None:
                        mu1, c1, mu, cov = fit
                        one = ellipse_mask(obj.shape, mu1, c1)
                        two = ellipse_mask(obj.shape, mu[0], cov[0]) | ellipse_mask(obj.shape, mu[1], cov[1])
                        sep = np.hypot(*(mu[0] - mu[1])) * p.VOXEL_UM / d['frameR']
                        labs = [p.truth_at(t, (mu[k][0] + x0) * p.VOXEL_UM, (mu[k][1] + y0) * p.VOXEL_UM)
                                for k in range(2)]
                        right = int(all(l > 0 for l in labs) and labs[0] != labs[1])
                        feats = [len(X), iou(obj, one), iou(obj, two), solid, sep, right]
            print('\t'.join(str(v) for v in row + feats), flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
