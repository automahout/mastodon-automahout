"""Labels the detector's OWN detections against CTC truth.

Every fixture used to tune the split pass was built from ground truth: a position
from a truth centroid and a radius chosen by whoever wrote the script. Five
conclusions drawn that way were wrong, and the last was wrong in a way its
fixture could not show -- it gave every detection the same radius, so the radius
appeared to carry no information.

This reads what the detector actually produced. Run a window with

    -Dautomahout.splitDump=/tmp/split-fixture.tsv

then point this at the file.

The dump is in world units; CTC truth is in pixels. The axis order is NOT assumed
-- both mappings are tried and the one that actually lands detections on cells is
reported, so a wrong guess shows up as a number rather than as a silent result.
"""
import sys
from pathlib import Path

import numpy as np
import scipy.ndimage as nd
from PIL import Image

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
VOXEL_UM = 0.645

# SplitFixtureDump.REVISION. The file is APPENDED to, so a run after a fix leaves
# its rows beside the old ones; rows below this are reported and dropped rather
# than averaged in, because that is how a pre-fix dump once looked like a fix
# that had not worked.
REVISION = 2


def truth_centroids(seq, t):
    f = ROOT / f'{seq}_ST' / 'SEG' / f'man_seg{t:03d}.tif'
    if not f.exists():
        return None
    seg = np.array(Image.open(f))
    objs = nd.find_objects(seg)
    out = []
    for lab in np.unique(seg):
        if lab == 0:
            continue
        sl = objs[lab - 1]
        if sl is None:
            continue
        m = seg[sl] == lab
        if int(m.sum()) < 30:
            continue
        cy, cx = nd.center_of_mass(m)
        out.append((sl[0].start + cy, sl[1].start + cx))
    return np.array(out) if out else np.zeros((0, 2))


def load(path):
    if not Path(path).exists():
        print(f"{path} does not exist — run a window with "
              f"AUTOMAHOUT_SPLIT_DUMP={path} first")
        return []
    rows, stale = [], 0
    with open(path) as fh:
        head = fh.readline().rstrip('\n').split('\t')
        if 'rev' not in head:
            print(f"{path} has no revision column, so every row predates it. Delete it and "
                  f"re-run: its readings do not mean what this script assumes.")
            return []
        for line in fh:
            p = line.rstrip('\n').split('\t')
            if len(p) != len(head):
                continue
            r = dict(zip(head, p))
            try:
                rev = int(r['rev'])
            except ValueError:
                continue
            if rev < REVISION:
                stale += 1
                continue
            rows.append(r)
    if stale:
        print(f"dropped {stale} row(s) written before revision {REVISION} — "
              f"the file is appended to, so delete it between runs")
    return rows


def covered(rows, seq, swap):
    """How many truth centroids each detection's disc contains."""
    out = []
    by_t = {}
    for r in rows:
        by_t.setdefault(int(r['t']), []).append(r)
    for t, rs in sorted(by_t.items()):
        cen = truth_centroids(seq, t)
        if cen is None:
            continue
        for r in rs:
            x, y = float(r['x']) / VOXEL_UM, float(r['y']) / VOXEL_UM
            row, col = (x, y) if swap else (y, x)
            rad = float(r['radius']) / VOXEL_UM
            d = np.hypot(cen[:, 0] - row, cen[:, 1] - col) if len(cen) else np.array([])
            out.append((r, int((d <= rad).sum())))
    return out


def inert(labelled):
    """Whether a reading is flat across every class, which means it cannot gate anything."""
    cs = np.array([float(r['coreCv']) for r, _ in labelled if r.get('coreCv', '') != ''])
    return len(cs) > 50 and np.percentile(cs, 90) < 0.05


def summarise(labelled, name):
    by = {}
    for r, n in labelled:
        by.setdefault(min(n, 3), []).append(r)
    total = len(labelled)
    print(f"\n{name}: {total} detections")
    for k in sorted(by):
        lbl = {0: 'covering NO truth cell', 1: 'covering ONE',
               2: 'covering TWO', 3: 'covering THREE or more'}[k]
        print(f"  {len(by[k]):>5} ({100*len(by[k])/total:4.1f}%)  {lbl}")
    print(f"\n  {'':<22}{'radius':>22}{'coreCv':>22}")
    print(f"  {'class':<22}{'p10 / p50 / p90':>22}{'p10 / p50 / p90':>22}")
    for k in sorted(by):
        rs = np.array([float(r['radius']) for r in by[k]])
        cs = np.array([float(r['coreCv']) for r in by[k] if r['coreCv'] != ''])
        def q(a):
            return (f"{np.percentile(a,10):.2f} / {np.median(a):.2f} / {np.percentile(a,90):.2f}"
                    if len(a) else "-")
        print(f"  {['none','one','two','three+'][k]:<22}{q(rs):>22}{q(cs):>22}")


def main(path, seq='01'):
    rows = load(path)
    if not rows:
        print(f"no rows in {path} — run a window with -D{'automahout.splitDump'}=<file> first")
        return
    best = None
    for swap in (False, True):
        lab = covered(rows, seq, swap)
        hit = np.mean([n >= 1 for _, n in lab]) if lab else 0.0
        print(f"axis mapping {'x->row' if swap else 'y->row'}: "
              f"{100*hit:.1f}% of detections land on at least one truth cell")
        if best is None or hit > best[0]:
            best = (hit, swap, lab)
    hit, swap, lab = best
    if hit < 0.5:
        print("\nNEITHER mapping lands even half the detections on a cell — the scale or the "
              "frame offset is wrong, so the labels below mean nothing. Fix that first.")
        return
    summarise(lab, f"detector output, axis mapping {'x->row' if swap else 'y->row'}")
    if inert(lab):
        print("\nWARNING: the core CV reads under 0.05 for 90% of detections. A gate at 0.25 "
              "cannot fire on that, so either these rows predate the fix or the reading is "
              "inert again. Check that the jar was rebuilt.")


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '/tmp/split-fixture.tsv',
         sys.argv[2] if len(sys.argv) > 2 else '01')
