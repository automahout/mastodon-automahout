"""What the split gate's floor should be, when it cannot be a constant.

The gate was max(0.25, frame median + k MAD). 0.25 is a fact about one culture
under one objective: a core CV is dimensionless, but how uneven a smooth nucleus
reads depends on the optics and the label. On a cleaner movie the frame's own
bar falls below the constant and the constant becomes the ONLY threshold in the
pass — splitting nothing, with nothing in the log saying why.

The replacement is the spread that measurement noise alone produces on a uniform
body, background sigma / (signal - background), which follows the images.

This measures what that evaluates to, and what swapping it costs. Run a window
with -Dautomahout.splitDump=/tmp/split-fixture.tsv first.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from label_dump import load, covered, ROOT  # noqa: E402

# AutoCorrectOrchestrator.MAD_TO_STD
MAD_TO_STD = 1.4826
# The constant being replaced.
OLD_FLOOR = 0.25
# AutoCorrectOrchestrator.SPLIT_CORE_CV_MADS
MADS = 1.0


def frame_background(seq, t):
    """Median and MAD-scaled spread of a whole frame. Mostly background by area."""
    f = ROOT / seq / f't{t:03d}.tif'
    if not f.exists():
        return None
    a = np.array(Image.open(f)).astype(np.float64).ravel()
    med = np.median(a)
    return med, MAD_TO_STD * np.median(np.abs(a - med))


def by_timepoint(labelled):
    out = {}
    for r, n in labelled:
        out.setdefault(int(r['t']), []).append((r, n))
    return out


def main(path='/tmp/split-fixture.tsv', seq='01'):
    rows = load(path)
    if not rows:
        return
    lab = covered(rows, seq, False)
    if np.mean([n >= 1 for _, n in lab]) < 0.5:
        lab = covered(rows, seq, True)
    print(f"{len(lab)} detections\n")

    floors, bars = [], []
    clear = {'const': {1: 0, 2: 0}, 'noise': {1: 0, 2: 0}, 'none': {1: 0, 2: 0}}
    total = {1: 0, 2: 0}
    for t, rs in sorted(by_timepoint(lab).items()):
        bg = frame_background(seq, t)
        if bg is None:
            continue
        bg_med, bg_std = bg
        cvs = np.array([float(r['coreCv']) for r, _ in rs if r['coreCv'] != ''])
        # profile element 3, the core's own level, is what the core CV divides by
        lvl = np.array([float(r['coreMedian']) for r, _ in rs if r['coreMedian'] != ''])
        if len(cvs) < 10 or len(lvl) < 10:
            continue
        above = np.median(lvl) - bg_med
        if not above > 0:
            continue
        noise = bg_std / above
        m = np.median(cvs)
        bar = m + MADS * MAD_TO_STD * np.median(np.abs(cvs - m))
        floors.append(noise)
        bars.append(bar)
        for r, n in rs:
            if n not in (1, 2) or r['coreCv'] == '':
                continue
            c = float(r['coreCv'])
            total[n] += 1
            clear['const'][n] += c > max(OLD_FLOOR, bar)
            clear['noise'][n] += c > max(noise, bar)
            clear['none'][n] += c > bar

    floors, bars = np.array(floors), np.array(bars)
    print(f"over {len(floors)} frames")
    print(f"  noise-implied floor : p10 {np.percentile(floors,10):.4f}  "
          f"p50 {np.median(floors):.4f}  p90 {np.percentile(floors,90):.4f}")
    print(f"  adaptive bar        : p10 {np.percentile(bars,10):.3f}  "
          f"p50 {np.median(bars):.3f}  p90 {np.percentile(bars,90):.3f}")
    print(f"  frames where the {OLD_FLOOR} constant was the operative bar : "
          f"{int((OLD_FLOOR > bars).sum())} / {len(bars)}")
    print(f"  frames where the noise floor would be operative      : "
          f"{int((floors > bars).sum())} / {len(bars)}\n")

    print(f"{'floor':<30}{'pairs clear':>13}{'singles clear':>15}")
    for k, name in [('const', f'constant {OLD_FLOOR} (removed)'),
                    ('noise', 'noise-implied (per frame)'),
                    ('none', 'no floor at all')]:
        print(f"{name:<30}{100*clear[k][2]/total[2]:>12.1f}%"
              f"{100*clear[k][1]/total[1]:>14.1f}%")
    print(f"\npopulation: {total[2]} pairs, {total[1]} singles")


if __name__ == '__main__':
    main(*sys.argv[1:])
