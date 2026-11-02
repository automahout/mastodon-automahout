"""Where do the synthesized fp negatives actually land?

AutoCorrectOrchestrator.buildSyntheticFpCandidates offsets a negative away from
a detected cell by max(3 * THE DETECTION'S radius, 5 um), rejecting candidates
that collide with another spot IN THE GRAPH. Two things follow that the code
cannot see:

  * the distance is a multiple of the DETECTION's radius, not the cell's, so
    when detections are smaller than the cells the negative is placed inside
    the cell it came from, or in its neighbour;
  * the collision check only knows about detections, so an UNDETECTED cell is
    invisible to it -- and in the reported frame 27 spots cover ~43 cells.

This replays the exact procedure against the CTC silver truth and asks how
often the negative lands on a real cell, i.e. how often the pipeline teaches
ELEPHANT that cell pixels are background.

Run: <venv>/bin/python measure_offset.py
"""
from pathlib import Path

import numpy as np
import scipy.ndimage as nd
from PIL import Image

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
SEG = ROOT / '01_ST' / 'SEG'

PIXEL_UM = 0.645                      # Fluo-N2DL-HeLa
OFFSET_FACTOR = 3.0                   # SYNTHETIC_FP_OFFSET_FACTOR
MIN_OFFSET_PX = 5.0 / PIXEL_UM        # SYNTHETIC_FP_MIN_OFFSET, in px
MAX_ATTEMPTS = 8                      # SYNTHETIC_FP_MAX_ATTEMPTS_PER_SOURCE


def cells_of(seg):
    """(cy, cx, equivalent radius px) per truth cell."""
    labels = np.unique(seg)
    labels = labels[labels != 0]
    out = []
    objs = nd.find_objects(seg)
    for lab in labels:
        sl = objs[lab - 1]
        if sl is None:
            continue
        m = seg[sl] == lab
        area = int(m.sum())
        if area < 4:
            continue
        cy, cx = nd.center_of_mass(m)
        out.append((sl[0].start + cy, sl[1].start + cx, np.sqrt(area / np.pi)))
    return np.array(out)


def collides(spots, pos, radius):
    """AutoCorrectOrchestrator.collidesWithAny, on the DETECTED spots only."""
    if len(spots) == 0:
        return False
    d2 = (spots[:, 0] - pos[0]) ** 2 + (spots[:, 1] - pos[1]) ** 2
    return bool(np.any(d2 < (spots[:, 2] + radius) ** 2))


def replay(seg, cells, size_ratio, detected_share, rng, in_plane):
    """Returns (landed_on_a_cell, placed) for one frame."""
    n = len(cells)
    det_idx = rng.choice(n, max(1, int(round(n * detected_share))), replace=False)
    spots = np.column_stack([cells[det_idx, 0], cells[det_idx, 1],
                             cells[det_idx, 2] * size_ratio])
    on_cell = placed = 0
    for i in range(len(spots)):
        cy, cx, r = spots[i]
        min_d = max(r * OFFSET_FACTOR, MIN_OFFSET_PX)
        pos = None
        for attempt in range(MAX_ATTEMPTS):
            scale = 1.0 + attempt / (MAX_ATTEMPTS - 1)
            d = min_d * scale
            while True:
                v = rng.uniform(-1, 1, 3)
                if in_plane:
                    v[2] = 0.0          # a 2D movie has no z to offset into
                nrm = np.linalg.norm(v)
                if nrm > 1e-6:
                    break
            cand = np.array([cy + v[0] / nrm * d, cx + v[1] / nrm * d])
            if not collides(spots, cand, r):
                pos = cand
                break
        if pos is None:
            continue
        placed += 1
        y, x = int(round(pos[0])), int(round(pos[1]))
        if 0 <= y < seg.shape[0] and 0 <= x < seg.shape[1] and seg[y, x] != 0:
            on_cell += 1
    return on_cell, placed


def main():
    frames = sorted(SEG.glob('man_seg*.tif'))
    segs = []
    for f in frames:
        s = np.array(Image.open(f))
        c = cells_of(s)
        if len(c):
            segs.append((s, c))
    radii = np.concatenate([c[:, 2] for _, c in segs])
    print(f'{len(segs)} frames, {len(radii)} truth cells, '
          f'radius p05/p50/p95 {np.quantile(radii, .05):.1f}/'
          f'{np.quantile(radii, .5):.1f}/{np.quantile(radii, .95):.1f} px')
    print(f'offset = max({OFFSET_FACTOR} * detection radius, '
          f'{MIN_OFFSET_PX:.1f} px), {MAX_ATTEMPTS} attempts, '
          'collision checked against DETECTED spots only\n')

    print('  negatives that land ON a real cell (i.e. teach a cell as background)')
    print('  detection/cell  |  all cells detected  |  63% detected (the reported frame)')
    for ratio in (0.4, 0.5, 0.6, 0.8, 1.0):
        row = []
        for share in (1.0, 0.63):
            rng = np.random.default_rng(11)
            on = pl = 0
            for s, c in segs:
                a, b = replay(s, c, ratio, share, rng, in_plane=True)
                on += a
                pl += b
            row.append(on / max(pl, 1))
        print(f'       {ratio:4.1f}x      |        {row[0]:6.1%}        |'
              f'          {row[1]:6.1%}')

    print('\n  the z component, on a movie with one z plane:')
    for ratio in (0.5, 1.0):
        for in_plane in (True, False):
            rng = np.random.default_rng(11)
            on = pl = 0
            for s, c in segs:
                a, b = replay(s, c, ratio, 0.63, rng, in_plane=in_plane)
                on += a
                pl += b
            how = 'offset kept in plane' if in_plane else 'z sampled as the code does'
            print(f'    {ratio:3.1f}x, {how:28s} -> {on / max(pl, 1):6.1%} on a cell')


main()
