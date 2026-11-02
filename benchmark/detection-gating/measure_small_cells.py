"""What the gate's radius floor costs, and who pays it.

The gate ANDs a standout floor with a radius floor. On this data the standout
floor alone already rejects 100% of background, so every cell the radius floor
removes is paid for nothing -- and the cells it removes are the smallest, which
is the post-division population the user is watching go undetected.
"""
import numpy as np

STANDOUT_SIGMA = 3.0
CELL_P05_SIGMA = 4.05  # where 95% of real cells stand; from RESULTS.md


def otsu(xs, bins=256):
    lo, hi = float(xs.min()), float(xs.max())
    if not hi > lo:
        return lo
    w = (hi - lo) / bins
    h, _ = np.histogram(xs, bins=bins, range=(lo, hi))
    total, best, thr = len(xs), -1.0, lo
    centres = lo + (np.arange(bins) + 0.5) * w
    sum_all = float((h * centres).sum())
    w0 = 0.0, 
    w0 = 0.0
    s0 = 0.0
    for bi in range(bins - 1):
        w0 += h[bi]
        s0 += h[bi] * centres[bi]
        w1 = total - w0
        if w0 == 0 or w1 == 0:
            continue
        m0, m1 = s0 / w0, (sum_all - s0) / w1
        v = w0 * w1 * (m0 - m1) ** 2
        if v > best:
            best, thr = v, lo + (bi + 1) * w
    return thr


def learn_cut(standouts, radii):
    bright = radii[standouts >= STANDOUT_SIGMA]
    return min(otsu(radii), float(np.percentile(bright, 5)))


def report(name, cells, bg):
    s = np.concatenate([cells[:, 0], bg[:, 0]])
    r = np.concatenate([cells[:, 1], bg[:, 1]])
    cut = learn_cut(s, r)

    def admits(a, rule):
        st, rad = a[:, 0], a[:, 1]
        base = st >= STANDOUT_SIGMA
        if rule == "standout only":
            return base
        if rule == "standout AND radius":
            return base & (rad >= cut)
        return base & ((rad >= cut) | (st >= CELL_P05_SIGMA))

    # the smallest decile of real cells: post-division daughters and mitotic figures
    small = cells[cells[:, 1] <= np.percentile(cells[:, 1], 10)]
    print(f"\n{name}  (radius cut {cut:.2f} px)")
    print(f"{'rule':<26}{'cells kept':>12}{'bg rejected':>13}{'smallest decile kept':>22}")
    for rule in ("standout only", "standout AND radius", "radius OR bright"):
        ck = admits(cells, rule).mean() * 100
        br = (~admits(bg, rule)).mean() * 100
        sk = admits(small, rule).mean() * 100
        print(f"{rule:<26}{ck:>11.1f}%{br:>12.1f}%{sk:>21.1f}%")


report("as measured", np.load("cells.npy"), np.load("background.npy"))

# The adversarial case the radius axis exists for: a detector wrong about a
# position is also wrong about the size it reports there.
bg = np.load("background.npy").copy()
cells = np.load("cells.npy")
rng = np.random.default_rng(0)
bg[:, 1] = rng.choice(cells[:, 1], size=len(bg))
report("background radii inflated to cell size", cells, bg)
