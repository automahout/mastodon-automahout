"""Can a cell's real extent be recovered from the IMAGE at a detection's centre?

Why it is needed. ELEPHANT reports a core, not the cell, and the annotation sent
for training is that core -- so the next round's core is smaller again, which is
what ElephantTool.restoreAnnotationExtent's own comment says. That correction
exists, but it measures the target from the user's CONFIRMED cells and returns
immediately when there are none, which is every autonomous run.

A detection that covers half a nucleus is also exactly what tiles a nucleus with
two detections, so the undersized annotation and the duplicate pair are one
problem.

Two model-free estimators, scored against the silver truth's own area:

  grow(k)   flood the connected region above background + k*sigma from the seed
  halfmax   walk outward along many rays to where intensity falls to
            (peak + background) / 2, take the median over rays

Flooding merges touching cells; the ray median does not, because a neighbour
corrupts only the rays pointing at it. That is the hypothesis being tested.

Run: <venv>/bin/python measure_extent.py
"""
from pathlib import Path

import numpy as np
import scipy.ndimage as nd
from PIL import Image

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
RAW = ROOT / '01'
SEG = ROOT / '01_ST' / 'SEG'

N_RAYS = 32
MAX_R = 40


def seeds_and_truth(img, seg):
    """(seed y, seed x, true equivalent radius) per truth cell."""
    labels = np.unique(seg)
    labels = labels[labels != 0]
    objs = nd.find_objects(seg)
    out = []
    for lab in labels:
        sl = objs[lab - 1]
        if sl is None:
            continue
        m = seg[sl] == lab
        area = int(m.sum())
        if area < 16:
            continue
        sub = np.where(m, img[sl], -np.inf)
        dy, dx = np.unravel_index(np.argmax(sub), sub.shape)
        out.append((sl[0].start + dy, sl[1].start + dx, np.sqrt(area / np.pi)))
    return out


def grow(img, y, x, med, sigma, k):
    r = MAX_R
    y0, y1 = max(0, y - r), min(img.shape[0], y + r + 1)
    x0, x1 = max(0, x - r), min(img.shape[1], x + r + 1)
    win = img[y0:y1, x0:x1]
    lab, _ = nd.label(win > med + k * sigma)
    seed = lab[y - y0, x - x0]
    if seed == 0:
        return 0.5
    return float(np.sqrt(int((lab == seed).sum()) / np.pi))


def halfmax(img, y, x, med):
    peak = float(img[max(0, y - 1):y + 2, max(0, x - 1):x + 2].max())
    level = (peak + med) / 2.0
    if not (peak > med):
        return 0.5
    rs = []
    for a in np.linspace(0, 2 * np.pi, N_RAYS, endpoint=False):
        dy, dx = np.sin(a), np.cos(a)
        hit = MAX_R
        for step in range(1, MAX_R):
            yy, xx = int(round(y + dy * step)), int(round(x + dx * step))
            if not (0 <= yy < img.shape[0] and 0 <= xx < img.shape[1]):
                hit = step
                break
            if img[yy, xx] < level:
                hit = step
                break
        rs.append(hit)
    return float(np.median(rs))


def report(name, est, true):
    est, true = np.array(est), np.array(true)
    ratio = est / true
    err = np.abs(ratio - 1.0)
    print(f'  {name:14s} ratio p05/p50/p95 '
          f'{np.quantile(ratio, .05):5.2f}/{np.quantile(ratio, .5):5.2f}/'
          f'{np.quantile(ratio, .95):5.2f}   '
          f'within 25%: {np.mean(err < 0.25):5.1%}   '
          f'median |err| {np.median(err):5.1%}')


def main():
    frames = sorted(RAW.glob('t*.tif'))[::4]   # every 4th frame is plenty
    true = []
    ests = {f'grow({k})': [] for k in (2.0, 3.0, 5.0, 8.0)}
    ests['halfmax'] = []
    for f in frames:
        segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
        if not segf.exists():
            continue
        img = np.array(Image.open(f)).astype('float32')
        seg = np.array(Image.open(segf))
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        for y, x, r in seeds_and_truth(img, seg):
            true.append(r)
            for k in (2.0, 3.0, 5.0, 8.0):
                ests[f'grow({k})'].append(grow(img, y, x, med, sigma, k))
            ests['halfmax'].append(halfmax(img, y, x, med))

    print(f'{len(frames)} frames, {len(true)} cells, '
          f'true radius p05/p50/p95 {np.quantile(true, .05):.1f}/'
          f'{np.quantile(true, .5):.1f}/{np.quantile(true, .95):.1f} px\n')
    print('  estimator vs the truth\'s own equivalent radius:')
    for name, est in ests.items():
        report(name, est, true)


main()


def population():
    """The quantity restoreAnnotationExtent actually needs: ONE scale factor.

    Per-cell accuracy is not the target -- the correction is a single multiplier
    applied to every annotation, so what matters is how well a population
    statistic tracks the population's true size, and how badly the tails of the
    per-cell estimator distort it.
    """
    print('\n  per-FRAME population median, which is what the correction uses:')
    for k in (3.0, 5.0, 8.0):
        errs = []
        covered = []
        for f in sorted(RAW.glob('t*.tif'))[::4]:
            segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
            if not segf.exists():
                continue
            img = np.array(Image.open(f)).astype('float32')
            seg = np.array(Image.open(segf))
            med = float(np.median(img))
            sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
            est, tru = [], []
            for y, x, r in seeds_and_truth(img, seg):
                est.append(grow(img, y, x, med, sigma, k))
                tru.append(r)
            est, tru = np.array(est), np.array(tru)
            reached = est > 0.5
            covered.append(reached.mean())
            if reached.sum() < 5:
                continue
            errs.append(np.median(est[reached]) / np.median(tru) - 1.0)
        errs = np.array(errs)
        print(f'    grow({k}): median frame error {np.median(np.abs(errs)):5.1%}, '
              f'worst {np.max(np.abs(errs)):5.1%}, '
              f'cells reaching the threshold {np.mean(covered):5.1%}')

    print('\n  and what the annotation is corrected FROM -- ELEPHANT\'s core.')
    print('  The pipeline has no core measurement here, so this reports the')
    print('  correction a 0.5x core would receive: it should come out near 2.0x.')
    for k in (5.0, 8.0):
        ratios = []
        for f in sorted(RAW.glob('t*.tif'))[::4]:
            segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
            if not segf.exists():
                continue
            img = np.array(Image.open(f)).astype('float32')
            seg = np.array(Image.open(segf))
            med = float(np.median(img))
            sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
            est = np.array([grow(img, y, x, med, sigma, k)
                            for y, x, _ in seeds_and_truth(img, seg)])
            tru = np.array([r for _, _, r in seeds_and_truth(img, seg)])
            est = est[est > 0.5]
            if len(est) < 5:
                continue
            ratios.append(np.median(est) / (0.5 * np.median(tru)))
        ratios = np.array(ratios)
        print(f'    grow({k}): correction p05/p50/p95 '
              f'{np.quantile(ratios, .05):5.2f}/{np.quantile(ratios, .5):5.2f}/'
              f'{np.quantile(ratios, .95):5.2f}')


population()


def ray(img, y, x, med, sigma, k, n_rays=N_RAYS):
    """Same LEVEL as grow(k), but walked along rays instead of flood filled.

    Flood filling ~40x40 px per detection is not affordable in the pipeline --
    level-0 random access costs 150-400 ms per voxel, so the codebase samples
    through a Sampler along fixed offsets. This is what that can compute:
    n_rays * MAX_R samples per detection instead of a region.
    """
    level = med + k * sigma
    rs = []
    for a in np.linspace(0, 2 * np.pi, n_rays, endpoint=False):
        dy, dx = np.sin(a), np.cos(a)
        hit = MAX_R
        for step in range(1, MAX_R):
            yy, xx = int(round(y + dy * step)), int(round(x + dx * step))
            if not (0 <= yy < img.shape[0] and 0 <= xx < img.shape[1]):
                hit = step
                break
            if img[yy, xx] < level:
                hit = step
                break
        rs.append(hit)
    return float(np.median(rs))


def rays_vs_grow():
    print('\n  ray-walked at the same level, which is what Sampler can afford:')
    true, ests = [], {}
    for k in (5.0, 8.0):
        for n in (8, 16, 32):
            ests[(k, n)] = []
    for f in sorted(RAW.glob('t*.tif'))[::4]:
        segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
        if not segf.exists():
            continue
        img = np.array(Image.open(f)).astype('float32')
        seg = np.array(Image.open(segf))
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        for y, x, r in seeds_and_truth(img, seg):
            true.append(r)
            for (k, n) in ests:
                ests[(k, n)].append(ray(img, y, x, med, sigma, k, n))
    for (k, n), est in ests.items():
        report(f'ray({k},{n}r)', est, true)

    print('\n  per-FRAME population median for the ray estimator:')
    for k in (5.0, 8.0):
        for n in (8, 16):
            errs = []
            for f in sorted(RAW.glob('t*.tif'))[::4]:
                segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
                if not segf.exists():
                    continue
                img = np.array(Image.open(f)).astype('float32')
                seg = np.array(Image.open(segf))
                med = float(np.median(img))
                sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
                e, t = [], []
                for y, xx, r in seeds_and_truth(img, seg):
                    e.append(ray(img, y, xx, med, sigma, k, n))
                    t.append(r)
                if len(e) < 5:
                    continue
                errs.append(np.median(e) / np.median(t) - 1.0)
            errs = np.array(errs)
            print(f'    ray({k}, {n} rays): median frame error '
                  f'{np.median(np.abs(errs)):5.1%}, worst {np.max(np.abs(errs)):5.1%}, '
                  f'bias {np.median(errs):+5.1%}')


rays_vs_grow()


def ray_step(img, y, x, med, sigma, k, n_rays, step_px):
    """ray(), walked at a coarser step -- what world-coordinate sampling gives.

    The pipeline steps in world units derived from the spot's own radius, not in
    whole pixels, so the estimator has to survive a coarser walk than the one
    measured above or the implementation is not the thing that was measured.
    """
    level = med + k * sigma
    rs = []
    n_steps = int(MAX_R / step_px)
    for a in np.linspace(0, 2 * np.pi, n_rays, endpoint=False):
        dy, dx = np.sin(a), np.cos(a)
        hit = MAX_R
        for i in range(1, n_steps):
            d = i * step_px
            yy, xx = int(round(y + dy * d)), int(round(x + dx * d))
            if not (0 <= yy < img.shape[0] and 0 <= xx < img.shape[1]):
                hit = d
                break
            if img[yy, xx] < level:
                hit = d
                break
        rs.append(hit)
    return float(np.median(rs))


def step_sensitivity():
    print('\n  step-size sensitivity of ray(5.0, 8 rays), per-FRAME population median:')
    for step_px in (1.0, 1.7, 2.5, 3.5):
        errs = []
        for f in sorted(RAW.glob('t*.tif'))[::4]:
            segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
            if not segf.exists():
                continue
            img = np.array(Image.open(f)).astype('float32')
            seg = np.array(Image.open(segf))
            med = float(np.median(img))
            sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
            e, t = [], []
            for y, xx, r in seeds_and_truth(img, seg):
                e.append(ray_step(img, y, xx, med, sigma, 5.0, 8, step_px))
                t.append(r)
            if len(e) < 5:
                continue
            errs.append(np.median(e) / np.median(t) - 1.0)
        errs = np.array(errs)
        print(f'    step {step_px:3.1f} px: median frame error '
              f'{np.median(np.abs(errs)):5.1%}, worst {np.max(np.abs(errs)):5.1%}, '
              f'bias {np.median(errs):+5.1%}')


step_sensitivity()
