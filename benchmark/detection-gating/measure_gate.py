"""Can a (brightness, size) gate separate real cells from background detections,
and can it be found WITHOUT ground truth?

Why this matters: the pipeline sends every spot that is not explicitly tagged fp
to ELEPHANT as a training POSITIVE (ElephantIntegration.buildAnnotationSpots),
so every background detection the cleanup passes miss is taught to the model as
a cell. The cleanup passes are independent one-dimensional thresholds.

Both populations are measured the SAME way -- region grown from a seed above a
local threshold -- so the separation cannot be an artefact of measuring cells
from a truth mask and background from a fixed probe. Truth is used only to say
which seeds are cells, never to measure them.

Run: <venv>/bin/python measure_gate.py
"""
from pathlib import Path

import numpy as np
import scipy.ndimage as nd
from PIL import Image

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
RAW = ROOT / '01'
SEG = ROOT / '01_ST' / 'SEG'
HERE = Path(__file__).parent

GROW_K = 3.0      # region grows while pixels exceed median + GROW_K * sigma
GROW_MAX_R = 24   # px; bounds the flood so one cell cannot swallow the frame


def disc(r):
    y, x = np.ogrid[-r:r + 1, -r:r + 1]
    return (y * y + x * x) <= r * r


def measure_seed(img, y, x, med, sigma):
    """(standout, radius) of whatever sits at (y, x), measured like a detection.

    Grows the connected region above med + GROW_K*sigma containing the seed,
    inside a bounded window. This is the same procedure for a cell and for a
    noise peak -- neither gets a measurement the other could not have had.
    """
    r = GROW_MAX_R
    y0, y1 = max(0, y - r), min(img.shape[0], y + r + 1)
    x0, x1 = max(0, x - r), min(img.shape[1], x + r + 1)
    win = img[y0:y1, x0:x1]
    mask = win > (med + GROW_K * sigma)
    lab, _ = nd.label(mask)
    seed = lab[y - y0, x - x0]
    if seed == 0:
        # Nothing rises above the noise here: a 1-px object, at the seed value.
        return (float(img[y, x]) - med) / sigma, 0.5
    region = lab == seed
    area = int(region.sum())
    standout = (float(win[region].mean()) - med) / sigma
    return standout, float(np.sqrt(area / np.pi))


def seeds_for_frame(img, seg, rng, n_bg):
    """Cell seeds (one per truth cell) and background seeds (noise peaks)."""
    cell_seeds = []
    labels = np.unique(seg)
    labels = labels[labels != 0]
    objs = nd.find_objects(seg)
    for lab in labels:
        sl = objs[lab - 1]
        if sl is None:
            continue
        m = seg[sl] == lab
        if m.sum() < 4:
            continue
        # brightest pixel of the cell: where a detector would centre
        sub = np.where(m, img[sl], -np.inf)
        dy, dx = np.unravel_index(np.argmax(sub), sub.shape)
        cell_seeds.append((sl[0].start + dy, sl[1].start + dx))

    sm = nd.uniform_filter(img.astype('float32'), size=3)
    peaks = (sm == nd.maximum_filter(sm, size=9))
    occupied = nd.binary_dilation(seg > 0, disc(8))
    ys, xs = np.nonzero(peaks & ~occupied)
    keep = (ys > GROW_MAX_R) & (ys < img.shape[0] - GROW_MAX_R) \
        & (xs > GROW_MAX_R) & (xs < img.shape[1] - GROW_MAX_R)
    ys, xs = ys[keep], xs[keep]
    if len(ys) > n_bg:
        idx = rng.choice(len(ys), n_bg, replace=False)
        ys, xs = ys[idx], xs[idx]
    return cell_seeds, list(zip(ys, xs))


def collect():
    rng = np.random.default_rng(0)
    cells, bg = [], []
    for f in sorted(RAW.glob('t*.tif')):
        segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
        if not segf.exists():
            continue
        img = np.array(Image.open(f)).astype('float32')
        seg = np.array(Image.open(segf))
        med = float(np.median(img))
        sigma = 1.4826 * float(np.median(np.abs(img - med)))
        sigma = max(sigma, 1e-9)
        cs, bs = seeds_for_frame(img, seg, rng, n_bg=40)
        for y, x in cs:
            cells.append(measure_seed(img, y, x, med, sigma))
        for y, x in bs:
            bg.append(measure_seed(img, y, x, med, sigma))
    return np.array(cells), np.array(bg)


def quant(name, a):
    print(f'  {name:26s} p05/p50/p95: '
          f'{np.quantile(a, .05):7.2f} {np.quantile(a, .5):7.2f} '
          f'{np.quantile(a, .95):7.2f}')


def gt_optimal(cells, bg):
    """What a gate could achieve if it knew the truth -- the ceiling."""
    print('\nWith ground truth (the ceiling):')
    for idx, name in ((0, 'standout'), (1, 'radius')):
        for recall in (0.99, 0.95):
            thr = np.quantile(cells[:, idx], 1 - recall)
            surv = float((bg[:, idx] >= thr).mean())
            print(f'  {name:9s} keep {recall:.0%} of cells (>= {thr:6.2f})'
                  f' -> {surv:6.2%} of background survives')


def kmeans2(x, iters=100, seed=0):
    """Two-cluster k-means on standardised features. No truth, no labels."""
    rng = np.random.default_rng(seed)
    z = (x - x.mean(0)) / (x.std(0) + 1e-9)
    c = z[rng.choice(len(z), 2, replace=False)]
    for _ in range(iters):
        d = ((z[:, None, :] - c[None, :, :]) ** 2).sum(-1)
        a = d.argmin(1)
        if len(np.unique(a)) < 2:
            return a, 0.0
        nc = np.array([z[a == k].mean(0) for k in range(2)])
        if np.allclose(nc, c):
            c = nc
            break
        c = nc
    # the cluster with the higher standout is the cells
    cells_k = int(np.argmax(c[:, 0]))
    sep = float(np.linalg.norm(c[0] - c[1]))
    spread = float(np.mean([z[a == k].std(0).mean() for k in range(2)]))
    return (a == cells_k), sep / max(spread, 1e-9)


def autonomous(cells, bg, features, name):
    """Recover the gate from the unlabelled cloud, at several contamination levels."""
    print(f'\nWithout ground truth -- 2-cluster k-means on {name}:')
    print('   bg share |  cells kept | background rejected | separation')
    rng = np.random.default_rng(1)
    for share in (0.05, 0.10, 0.25, 0.50, 0.75):
        n_bg = int(len(cells) * share / (1 - share))
        n_bg = min(n_bg, len(bg))
        pick = rng.choice(len(bg), n_bg, replace=False)
        x = np.vstack([cells[:, features], bg[pick][:, features]])
        truth = np.r_[np.ones(len(cells), bool), np.zeros(n_bg, bool)]
        keep, sep = kmeans2(x)
        recall = float(keep[truth].mean())
        rejected = float((~keep[~truth]).mean()) if n_bg else float('nan')
        print(f'      {share:4.0%} |      {recall:6.1%} |          {rejected:6.1%}'
              f'   |   {sep:5.2f}')


def main():
    cells, bg = collect()
    print(f'{len(cells)} truth cells, {len(bg)} background peaks, '
          'both measured by the same region growing')
    print('\nCells:')
    quant('standout (sigma)', cells[:, 0])
    quant('radius (px)', cells[:, 1])
    print('Background peaks:')
    quant('standout (sigma)', bg[:, 0])
    quant('radius (px)', bg[:, 1])

    gt_optimal(cells, bg)
    autonomous(cells, bg, [0], 'standout alone')
    autonomous(cells, bg, [1], 'radius alone')
    autonomous(cells, bg, [0, 1], 'standout + radius')

    np.save(HERE / 'cells.npy', cells)
    np.save(HERE / 'background.npy', bg)


main()
