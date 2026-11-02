#!/usr/bin/env python3
"""Measures whether "these two stay together" can identify one over-segmented cell safely.

Within a single frame the merge decision cannot be made safely at the separation the reported
over-segmentation sits at (1.82 r) -- see RESULTS.md. Over time there is more to go on: two
detections tiling ONE cell hold a constant separation and move as one, while division daughters
separate.

A rule of that shape can only delete cells when it is wrong, so what has to be measured is its
SPECIFICITY: how often would it fire on a pair that is genuinely two cells? Ground truth supplies
both kinds of real pair, and neither needs a detector:

  daughters   the two children of a division, from the lineage file
  neighbours  two unrelated tracks that happen to sit close and both persist

Any rule that essentially never fires on those is safe to apply to co-located detections.

    python3 measure_temporal.py
"""
import glob, math, os, sys
import numpy as np
from PIL import Image

DATA = os.path.expanduser("~/Repositories/CTC_dataset/Fluo-N2DL-HeLa")
TRA = DATA + "/01_GT/TRA"


def load():
    tracks = {}
    for line in open(TRA + "/man_track.txt"):
        f = line.split()
        if len(f) >= 4:
            tracks[int(f[0])] = (int(f[1]), int(f[2]), int(f[3]))
    frames = {}
    scale = []
    for path in sorted(glob.glob(TRA + "/man_track*.tif")):
        t = int(os.path.basename(path)[len("man_track"):-len(".tif")])
        lab = np.array(Image.open(path))
        cents = {}
        for cid in np.unique(lab):
            if cid == 0:
                continue
            ys, xs = np.nonzero(lab == cid)
            cents[int(cid)] = (xs.mean(), ys.mean())
        frames[t] = cents
        seg = DATA + "/01_ST/SEG/man_seg%03d.tif" % t
        if os.path.exists(seg):
            m = np.array(Image.open(seg))
            areas = [int((m == c).sum()) for c in np.unique(m) if c != 0]
            areas = [a for a in areas if a >= 20]
            if areas:
                scale.append(math.sqrt(float(np.median(areas)) / math.pi))
    return tracks, frames, float(np.median(scale))


def series(frames, a, b, t0, t1):
    """Separation of two labels over [t0, t1] while both exist."""
    out = []
    for t in range(t0, t1 + 1):
        c = frames.get(t, {})
        if a in c and b in c:
            out.append(math.hypot(c[a][0] - c[b][0], c[a][1] - c[b][1]))
        else:
            break
    return out


def main():
    tracks, frames, r = load()
    print("median cell radius: %.1f px   tracks: %d   frames: %d\n" % (r, len(tracks), len(frames)))

    kids = {}
    for lab, (b, e, p) in tracks.items():
        if p:
            kids.setdefault(p, []).append(lab)
    daughters = []
    for p, cs in kids.items():
        if len(cs) != 2:
            continue
        b = max(tracks[c][0] for c in cs)
        e = min(tracks[c][1] for c in cs)
        s = series(frames, cs[0], cs[1], b, e)
        if len(s) >= 3:
            daughters.append([v / r for v in s])

    lives = {lab: (b, e) for lab, (b, e, _) in tracks.items()}
    related = set()
    for lab, (b, e, p) in tracks.items():
        if p:
            related.add(frozenset((lab, p)))
            for sib in kids.get(p, []):
                if sib != lab:
                    related.add(frozenset((lab, sib)))
    neighbours = []
    labs = sorted(lives)
    for i in range(len(labs)):
        for j in range(i + 1, len(labs)):
            a, b_ = labs[i], labs[j]
            if frozenset((a, b_)) in related:
                continue
            t0 = max(lives[a][0], lives[b_][0])
            t1 = min(lives[a][1], lives[b_][1])
            if t1 - t0 < 4:
                continue
            s = series(frames, a, b_, t0, t1)
            if len(s) >= 5:
                neighbours.append([v / r for v in s])

    print("division daughter pairs: %d      unrelated co-existing pairs: %d\n"
          % (len(daughters), len(neighbours)))

    def fires(sep, maxSep, runLen, drift):
        """The candidate rule: separation stays under maxSep, and varies by < drift, for runLen frames."""
        n = 0
        for k in range(len(sep)):
            if sep[k] < maxSep:
                n += 1
                if n >= runLen:
                    w = sep[k - runLen + 1: k + 1]
                    if max(w) - min(w) < drift:
                        return True
            else:
                n = 0
        return False

    print("rule: separation < maxSep AND varying by < drift, for runLen consecutive frames")
    print("%8s %8s %8s | %-24s %-24s" % ("maxSep", "runLen", "drift", "fires on DAUGHTERS", "fires on NEIGHBOURS"))
    for maxSep in (1.6, 1.8, 1.9, 2.0):
        for runLen in (8, 12, 16):
            for drift in (0.15, 0.25):
                d = sum(1 for s in daughters if fires(s, maxSep, runLen, drift))
                nb = sum(1 for s in neighbours if fires(s, maxSep, runLen, drift))
                print("%8.2f %8d %8.2f | %6d/%-5d (%5.1f%%)   %6d/%-5d (%5.1f%%)"
                      % (maxSep, runLen, drift, d, len(daughters), 100.0 * d / max(1, len(daughters)),
                         nb, len(neighbours), 100.0 * nb / max(1, len(neighbours))))

    first = [s[0] for s in daughters if s]
    first.sort()
    if first:
        q = lambda p: first[min(len(first) - 1, int(p * (len(first) - 1)))]
        print("\ndaughter separation in the FIRST frame after division (units of r): "
              "p05=%.2f p50=%.2f p95=%.2f" % (q(0.05), q(0.50), q(0.95)))


if __name__ == "__main__":
    main()
