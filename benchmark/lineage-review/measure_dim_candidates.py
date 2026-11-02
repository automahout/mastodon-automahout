"""Can the image find the cells the detector misses, without flooding the user with candidates?

The missed cells are dim: at the tracking marker the interior sits ~1 spread above the frame median,
where matched detections sit at ~31 (characterise_errors.py). One pixel at 1 spread is noise; a
cell-sized disc of them is not. So: smooth the frame with a Gaussian about half a cell radius wide,
measure the smoothed frame's own noise (MAD of the smoothed background), and take local maxima K
smoothed spreads above the frame median that no detection already covers (centre further than one
median radius from every detection).

Scored per candidate against the tracking markers: on a missed cell, on a detected cell, or on none.
Reported per frame, since each candidate would be one question.

    python measure_dim_candidates.py stamp snapshot-label-prefix t_from t_to
"""
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from scipy import ndimage as ndi

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'split-review'))
import measure_convergence as mc  # noqa: E402
import measure_errors as me  # noqa: E402
import measure_links as ml  # noqa: E402
import prototype_splitter as ps  # noqa: E402

KS = (2.0, 3.0, 4.0, 6.0)


def main():
    stamp, prefix, t0, t1 = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    snap, label = me.snapshot_of(stamp, prefix)
    spots = mc.load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), snap)
    by_t = defaultdict(list)
    for k, s in spots.items():
        if s['tag'] != 'fp':
            by_t[s['t']].append(s)
    res = {K: Counter() for K in KS}
    frames = 0
    for t in range(t0, t1 + 1):
        if ml.tra(t) is None or not by_t[t]:
            continue
        frames += 1
        labs, cents = ml._cent[t]
        fr = float(np.median([s['r'] for s in by_t[t]]))
        im, med, _ = ps.image(t)
        sm = ndi.gaussian_filter(im, sigma=0.5 * fr / ps.VOXEL_UM)
        smed = np.median(sm)
        ssd = 1.4826 * np.median(np.abs(sm - smed))
        peaks = (sm == ndi.maximum_filter(sm, size=int(2 * fr / ps.VOXEL_UM) | 1))
        ys, xs = np.nonzero(peaks)
        det = np.array([[s['x'], s['y']] for s in by_t[t]])
        # which markers are missed by the detections
        detected = set()
        for s in by_t[t]:
            d = np.hypot(cents[:, 0] - s['x'], cents[:, 1] - s['y'])
            if d.min() <= ml.MATCH_UM:
                detected.add(labs[int(d.argmin())])
        missed = set(labs) - detected
        for K in KS:
            found = set()
            for y, x in zip(ys, xs):
                if sm[y, x] <= smed + K * ssd:
                    continue
                X, Y = x * ps.VOXEL_UM, y * ps.VOXEL_UM
                if np.hypot(det[:, 0] - X, det[:, 1] - Y).min() <= fr:
                    continue  # already covered by a detection
                d = np.hypot(cents[:, 0] - X, cents[:, 1] - Y)
                j = int(d.argmin())
                if d[j] > ml.MATCH_UM:
                    res[K]['on no cell'] += 1
                elif labs[j] in missed:
                    res[K]['on a missed cell'] += 1
                    found.add(labs[j])
                else:
                    res[K]['on a detected cell'] += 1
            res[K]['missed cells'] += len(missed)
            res[K]['missed cells found'] += len(found)
    print(f'{stamp} "{label}" t={t0}..{t1}, {frames} frames')
    print('| K | candidates/frame | on a missed cell | on a detected cell | on no cell | missed cells found |')
    print('|---|---|---|---|---|---|')
    for K in KS:
        c = res[K]
        n = c['on a missed cell'] + c['on a detected cell'] + c['on no cell']
        print(f"| {K} | {n / frames:.1f} | {c['on a missed cell']} | {c['on a detected cell']} | {c['on no cell']} | "
              f"{c['missed cells found']}/{c['missed cells']} ({c['missed cells found'] / max(c['missed cells'], 1):.0%}) |")
    return 0


if __name__ == '__main__':
    sys.exit(main())
