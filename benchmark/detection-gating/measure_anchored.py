"""The anchored gate, against the 2-cluster one measured in measure_gate.py.

measure_gate.py found that 2-cluster k-means rejects 100% of background at every
contamination level but keeps only 47-59% of real cells on standout, because the
cell population's standout spans 4-49 sigma and k-means splits THAT rather than
separating it from a tight background cluster at the noise floor.

The situation is not two comparable clusters. It is one tight cluster at the
noise floor and everything else. So anchor on the noise floor -- which the image
supplies directly, with no detections involved -- and gate above it.

Run: <venv>/bin/python measure_anchored.py   (after measure_gate.py)
"""
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
CELLS = np.load(HERE / 'cells.npy')
BG = np.load(HERE / 'background.npy')


def otsu(x, bins=256):
    """The threshold that best splits x into two groups by variance. No truth."""
    hist, edges = np.histogram(x, bins=bins)
    centres = (edges[:-1] + edges[1:]) / 2
    w = hist.cumsum()
    total = w[-1]
    if total == 0:
        return float(np.median(x))
    s = (hist * centres).cumsum()
    w0 = w[:-1]
    w1 = total - w0
    ok = (w0 > 0) & (w1 > 0)
    if not ok.any():
        return float(np.median(x))
    m0 = s[:-1][ok] / w0[ok]
    m1 = (s[-1] - s[:-1][ok]) / w1[ok]
    var = w0[ok] * w1[ok] * (m0 - m1) ** 2
    return float(centres[:-1][ok][int(np.argmax(var))])


def anchored_gate(x, s_min):
    """standout >= s_min sigma AND radius >= an Otsu cut on the radii.

    s_min is a property of the noise, not of this dataset: 3 sigma above the
    background is where a measurement stops being consistent with noise. The
    radius cut is read off the detections themselves, because a cell's size is
    a property of the specimen and cannot be assumed.
    """
    bright = x[:, 0] >= s_min
    if bright.sum() < 10:
        return np.zeros(len(x), bool), np.nan
    r_cut = otsu(x[:, 1])
    # Never cut above the bright population's own bulk: that would gate out cells
    # on size because the background happened to be numerous.
    r_cut = min(r_cut, np.quantile(x[bright, 1], 0.05))
    return bright & (x[:, 1] >= r_cut), r_cut


def ambiguity(x, s_min):
    """Share of detections in the band where the noise floor cannot decide.

    This is the trigger for asking the user: not low confidence in general, but
    a population that actually sits in the undecidable range.
    """
    band = (x[:, 0] >= s_min / 2) & (x[:, 0] < s_min * 1.5)
    return float(band.mean())


def run(s_min=3.0):
    print(f'anchored gate: standout >= {s_min} sigma AND radius >= Otsu(radius)')
    print('   bg share | cells kept | bg rejected | r_cut | ambiguous')
    rng = np.random.default_rng(1)
    for share in (0.05, 0.10, 0.25, 0.50, 0.75, 0.90):
        n_bg = min(int(len(CELLS) * share / (1 - share)), len(BG))
        pick = rng.choice(len(BG), n_bg, replace=False)
        x = np.vstack([CELLS, BG[pick]])
        truth = np.r_[np.ones(len(CELLS), bool), np.zeros(n_bg, bool)]
        keep, r_cut = anchored_gate(x, s_min)
        print(f'      {share:4.0%} |     {keep[truth].mean():6.1%} |'
              f'      {(~keep[~truth]).mean():6.1%} | {r_cut:5.2f} |'
              f'    {ambiguity(x, s_min):5.1%}')

    print('\nthe case that must ask for help -- background only, no cells:')
    keep, r_cut = anchored_gate(BG, s_min)
    print(f'   kept {keep.mean():.1%} of {len(BG)} background detections, '
          f'ambiguous {ambiguity(BG, s_min):.1%}')
    print('   (the gate empties the population: nothing here is a cell, which is')
    print('    a detector failure, not a gating decision -- this is when to ask)')

    print('\nthe case that must NOT ask -- cells only:')
    keep, r_cut = anchored_gate(CELLS, s_min)
    print(f'   kept {keep.mean():.1%} of {len(CELLS)} cells, '
          f'ambiguous {ambiguity(CELLS, s_min):.1%}')

    print('\nsensitivity of s_min (at 25% background):')
    n_bg = min(int(len(CELLS) * 0.25 / 0.75), len(BG))
    pick = rng.choice(len(BG), n_bg, replace=False)
    x = np.vstack([CELLS, BG[pick]])
    truth = np.r_[np.ones(len(CELLS), bool), np.zeros(n_bg, bool)]
    for s in (1.5, 2.0, 3.0, 4.0, 6.0):
        keep, _ = anchored_gate(x, s)
        print(f'   s_min={s:4.1f} -> cells {keep[truth].mean():6.1%}, '
              f'bg rejected {(~keep[~truth]).mean():6.1%}')


run()


def stress():
    """Harder background than the data supplies, because the data may be kind.

    The background peaks measured here grow to radius 0.5 px -- they do not rise
    above 3 sigma at all, so the radius cut alone rejects them. The spurious
    detections in the reported screenshot look 3-5 px across, i.e. ELEPHANT
    assigns them a real extent even where the image does not support one. Since
    the gate reads the IMAGE at the spot's position rather than the spot's own
    covariance, that inflation should not reach the standout axis -- but the
    radius axis is the spot's own, so test it with the radius inflated.
    """
    rng = np.random.default_rng(7)
    print('\nstress: background radii inflated to what the screenshot shows')
    print('   inflated r | cells kept | bg rejected')
    n_bg = min(int(len(CELLS) * 0.25 / 0.75), len(BG))
    pick = rng.choice(len(BG), n_bg, replace=False)
    for rmax in (2.0, 4.0, 6.0, 9.0, 12.0):
        bg = BG[pick].copy()
        bg[:, 1] = rng.uniform(1.0, rmax, size=len(bg))
        x = np.vstack([CELLS, bg])
        truth = np.r_[np.ones(len(CELLS), bool), np.zeros(len(bg), bool)]
        keep, _ = anchored_gate(x, 3.0)
        print(f'     1-{rmax:4.1f} px |     {keep[truth].mean():6.1%} |'
              f'      {(~keep[~truth]).mean():6.1%}')
    print('\n   and with the standout axis removed (radius only), same inflation:')
    for rmax in (4.0, 9.0, 12.0):
        bg = BG[pick].copy()
        bg[:, 1] = rng.uniform(1.0, rmax, size=len(bg))
        x = np.vstack([CELLS, bg])
        truth = np.r_[np.ones(len(CELLS), bool), np.zeros(len(bg), bool)]
        r_cut = otsu(x[:, 1])
        keep = x[:, 1] >= min(r_cut, np.quantile(CELLS[:, 1], 0.05))
        print(f'     1-{rmax:4.1f} px |     {keep[truth].mean():6.1%} |'
              f'      {(~keep[~truth]).mean():6.1%}')


stress()
