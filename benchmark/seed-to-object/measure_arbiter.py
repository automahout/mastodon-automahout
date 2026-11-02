"""Is "do these two seeds resolve to the same OBJECT?" a better arbiter than
"is there a valley between them?"

The pipeline decides whether two detections are one over-segmented cell from the
two points alone -- their separation in radii, and the intensity profile along
the segment joining them (isSameBlob / valleyThresholdFor). It never looks at
the object. The request is for something SAM-like: infer the whole structure
from a partial annotation, then two annotations landing in the same structure
are trivially one cell.

This measures the classical version of exactly that -- seed -> region -> "same
region?" -- against the current point-pair test, on CTC silver truth. No model,
so it can be measured today and shipped without a new dependency; if it closes
the gap, a learned segmenter is not needed for this decision.

Two populations, both taken from the truth:

  SAME  two seeds inside ONE truth cell, placed at that cell's two brightest
        local maxima -- what an over-segmenting detector actually produces
  DIFF  two seeds in DIFFERENT truth cells that are close enough for the merge
        to consider them at all

A good arbiter merges SAME and refuses DIFF.

Run: <venv>/bin/python measure_arbiter.py
"""
from pathlib import Path

import numpy as np
import scipy.ndimage as nd
from PIL import Image
from skimage.feature import peak_local_max
from skimage.segmentation import watershed

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
RAW = ROOT / '01'
SEG = ROOT / '01_ST' / 'SEG'

# The separation band the pipeline's merge considers at all.
SEPARATION_TOO_FAR = 2.2
# Measured valley thresholds now in AutoCorrectOrchestrator.valleyThresholdFor.
VALLEY_NEAR = 0.89       # < 1.10 r
VALLEY_BAND = 0.85       # 1.70 - 2.20 r
VALLEY_SCAN_SAMPLES = 7


def frames():
    for f in sorted(RAW.glob('t*.tif'))[::3]:
        segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
        if segf.exists():
            yield (np.array(Image.open(f)).astype('float32'),
                   np.array(Image.open(segf)))


def cells_of(seg):
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
        if area < 30:
            continue
        cy, cx = nd.center_of_mass(m)
        out.append((lab, sl, m, sl[0].start + cy, sl[1].start + cx,
                    np.sqrt(area / np.pi)))
    return out


def pairs_for(img, seg):
    """(seed_a, seed_b, radius, same_cell) for both populations."""
    sm = nd.gaussian_filter(img, 1.5)
    cells = cells_of(seg)
    same, diff = [], []
    for lab, sl, m, cy, cx, r in cells:
        # SAME: the two brightest local maxima inside this one cell.
        local = np.where(m, sm[sl], 0.0)
        pk = peak_local_max(local, min_distance=max(3, int(r * 0.6)),
                            labels=m.astype(int), num_peaks=2)
        if len(pk) == 2:
            a = (sl[0].start + pk[0][0], sl[1].start + pk[0][1])
            b = (sl[0].start + pk[1][0], sl[1].start + pk[1][1])
            d = np.hypot(a[0] - b[0], a[1] - b[1])
            if 0 < d < SEPARATION_TOO_FAR * r:
                same.append((a, b, r, True))
    # DIFF: distinct cells close enough that the merge would consider them.
    cen = np.array([[c[3], c[4]] for c in cells])
    rad = np.array([c[5] for c in cells])
    for i in range(len(cells)):
        for j in range(i + 1, len(cells)):
            d = np.hypot(*(cen[i] - cen[j]))
            rmax = max(rad[i], rad[j])
            if not (0 < d < SEPARATION_TOO_FAR * rmax):
                continue
            a = brightest(img, cells[i])
            b = brightest(img, cells[j])
            diff.append((a, b, rmax, False))
    return same, diff


def brightest(img, cell):
    _, sl, m, _, _, _ = cell
    sub = np.where(m, img[sl], -np.inf)
    dy, dx = np.unravel_index(np.argmax(sub), sub.shape)
    return (sl[0].start + dy, sl[1].start + dx)


# ---------------------------------------------------------------- arbiters

def valley_says_same(img, a, b, r, med, sigma):
    """AutoCorrectOrchestrator.isSameBlob, as measured and shipped."""
    d = np.hypot(a[0] - b[0], a[1] - b[1])
    sep = d / max(r, 1e-9)
    if sep < 1.10:
        keep = VALLEY_NEAR
    elif sep < 1.70:
        return None          # the band the measurement said not to judge
    elif sep < 2.20:
        keep = VALLEY_BAND
    else:
        return None
    core = min(img[a] - med, img[b] - med)
    if core <= 0:
        return None
    for k in range(1, VALLEY_SCAN_SAMPLES + 1):
        f = k / (VALLEY_SCAN_SAMPLES + 1)
        y = int(round(a[0] + f * (b[0] - a[0])))
        x = int(round(a[1] + f * (b[1] - a[1])))
        if (img[y, x] - med) < keep * core:
            return False
    return True


def component_says_same(labels, a, b):
    """Seed -> connected region above a threshold; same region = same cell."""
    la, lb = labels[a], labels[b]
    return bool(la != 0 and la == lb)


def build_components(img, med, sigma, k):
    lab, _ = nd.label(img > med + k * sigma)
    return lab


def build_watershed(img, med, sigma, k):
    """Seed -> object, but with touching cells split, which is the whole
    difficulty: a plain threshold fuses them into one region."""
    mask = img > med + k * sigma
    dist = nd.distance_transform_edt(mask)
    markers = np.zeros_like(dist, dtype=int)
    pk = peak_local_max(dist, min_distance=6, labels=mask)
    for i, (y, x) in enumerate(pk, start=1):
        markers[y, x] = i
    return watershed(-dist, markers, mask=mask)


def score(name, decisions):
    same = [d for d, t in decisions if t]
    diff = [d for d, t in decisions if not t]
    def rate(xs, want):
        judged = [x for x in xs if x is not None]
        if not judged:
            return float('nan'), 0
        return sum(1 for x in judged if x == want) / len(judged), len(judged)
    tpr, n_s = rate(same, True)
    tnr, n_d = rate(diff, True)
    tnr = 1 - tnr if n_d else float('nan')
    abstain_s = 1 - n_s / max(len(same), 1)
    abstain_d = 1 - n_d / max(len(diff), 1)
    print(f'  {name:22s} merges {tpr:6.1%} of one-cell pairs ({n_s:5d}), '
          f'wrongly merges {1 - tnr:6.1%} of two-cell pairs ({n_d:5d}), '
          f'abstains {abstain_s:5.1%}/{abstain_d:5.1%}')


def main():
    results = {n: [] for n in
               ('valley (current)', 'component 3s', 'component 5s',
                'component 8s', 'watershed 3s', 'watershed 5s')}
    n_frames = 0
    for img, seg in frames():
        n_frames += 1
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        same, diff = pairs_for(img, seg)
        comps = {k: build_components(img, med, sigma, k) for k in (3.0, 5.0, 8.0)}
        wss = {k: build_watershed(img, med, sigma, k) for k in (3.0, 5.0)}
        for a, b, r, truth in same + diff:
            results['valley (current)'].append(
                (valley_says_same(img, a, b, r, med, sigma), truth))
            for k in (3.0, 5.0, 8.0):
                results[f'component {int(k)}s'].append(
                    (component_says_same(comps[k], a, b), truth))
            for k in (3.0, 5.0):
                results[f'watershed {int(k)}s'].append(
                    (component_says_same(wss[k], a, b), truth))
    print(f'{n_frames} frames\n')
    for name, dec in results.items():
        score(name, dec)


main()


def saddle_ratio(img, a, b, med, margin=45):
    """Highest level at which the two seeds are still connected, over the LOWER
    peak. The watershed criterion, computed for one pair only.

    This is what the current test is reaching for and getting wrong. It asks
    whether the straight segment between the seeds dips; but the two lobes of
    one cell are joined by whatever path the cell's shape provides, which is
    rarely the straight line. Connectivity asks about every path at once.

    Implementable in the pipeline: one bounded window, a binary search, a flood
    fill. No distance transform, no full-frame watershed.
    """
    y0 = max(0, min(a[0], b[0]) - margin)
    y1 = min(img.shape[0], max(a[0], b[0]) + margin + 1)
    x0 = max(0, min(a[1], b[1]) - margin)
    x1 = min(img.shape[1], max(a[1], b[1]) + margin + 1)
    win = img[y0:y1, x0:x1]
    pa = (a[0] - y0, a[1] - x0)
    pb = (b[0] - y0, b[1] - x0)
    lo, hi = med, min(win[pa], win[pb])
    if not (hi > lo):
        return None
    for _ in range(12):
        mid = (lo + hi) / 2
        lab, _ = nd.label(win >= mid)
        if lab[pa] != 0 and lab[pa] == lab[pb]:
            lo = mid
        else:
            hi = mid
    peak = min(win[pa], win[pb]) - med
    return (lo - med) / peak if peak > 0 else None


def saddle_says_same(img, a, b, med, ratio):
    s = saddle_ratio(img, a, b, med)
    return None if s is None else bool(s >= ratio)


def saddle_sweep():
    print('\n  connectivity saddle, which is the watershed criterion per pair:')
    ratios = (0.70, 0.75, 0.80, 0.85, 0.90, 0.95)
    results = {r: [] for r in ratios}
    for img, seg in frames():
        med = float(np.median(img))
        same, diff = pairs_for(img, seg)
        for a, b, r, truth in same + diff:
            s = saddle_ratio(img, a, b, med)
            for ratio in ratios:
                results[ratio].append(
                    (None if s is None else bool(s >= ratio), truth))
    for ratio, dec in results.items():
        score(f'saddle >= {ratio:.2f}', dec)


saddle_sweep()


def local_watershed(img, a, b, med, sigma, k, stride, margin=48):
    """Watershed inside a bounded window, which is what the pipeline can afford.

    A full-frame watershed would need every voxel of the frame; the pipeline
    samples through a Sampler and pays per read. The pair is at most ~2.2 radii
    apart, so a window around the two seeds is enough -- and a stride lets the
    window cost fewer reads still.
    """
    y0 = max(0, min(a[0], b[0]) - margin)
    y1 = min(img.shape[0], max(a[0], b[0]) + margin + 1)
    x0 = max(0, min(a[1], b[1]) - margin)
    x1 = min(img.shape[1], max(a[1], b[1]) + margin + 1)
    win = img[y0:y1:stride, x0:x1:stride]
    pa = ((a[0] - y0) // stride, (a[1] - x0) // stride)
    pb = ((b[0] - y0) // stride, (b[1] - x0) // stride)
    if not (0 <= pa[0] < win.shape[0] and 0 <= pb[0] < win.shape[0]
            and 0 <= pa[1] < win.shape[1] and 0 <= pb[1] < win.shape[1]):
        return None
    mask = win > med + k * sigma
    if not (mask[pa] and mask[pb]):
        return None
    dist = nd.distance_transform_edt(mask)
    markers = np.zeros_like(dist, dtype=int)
    pk = peak_local_max(dist, min_distance=max(2, int(6 / stride)), labels=mask)
    if len(pk) == 0:
        return None
    for i, (y, x) in enumerate(pk, start=1):
        markers[y, x] = i
    lab = watershed(-dist, markers, mask=mask)
    return bool(lab[pa] != 0 and lab[pa] == lab[pb])


def stride_sweep():
    print('\n  local watershed in a bounded window, by sampling stride:')
    combos = [(3.0, 1), (3.0, 2), (3.0, 3), (5.0, 1), (5.0, 2)]
    results = {c: [] for c in combos}
    for img, seg in frames():
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        same, diff = pairs_for(img, seg)
        for a, b, r, truth in same + diff:
            for (k, st) in combos:
                results[(k, st)].append(
                    (local_watershed(img, a, b, med, sigma, k, st), truth))
    for (k, st), dec in results.items():
        reads = ((2 * 48 + 30) // st) ** 2
        score(f'local ws {int(k)}s stride {st} (~{reads} reads)', dec)


stride_sweep()
