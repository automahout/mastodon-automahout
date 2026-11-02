"""What does a classical detector get on this dataset, with no model at all?

The question behind it: the pipeline trains ELEPHANT on its own detections and
then spends a long chain of heuristic filters damping that loop. If a direct
measurement of the image already answers the detection problem on this class of
data, the loop is not the hard part being solved -- it is the problem being
created.

Fluo-N2DL-HeLa: background 33044 +/- 7, cells at +948, i.e. 127 sigma. Detector:

  1. threshold at background + k*sigma
  2. split touching nuclei by watershed on the distance transform
  3. drop regions smaller than a minimum area

Scored against the silver truth as a DETECTION task: a truth cell is found if
exactly one detected centre falls inside it; extra centres inside it are
over-segmentation; centres in no truth cell are false positives.

Run: <venv>/bin/python measure_baseline.py
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


def detect(img, k, min_area, min_peak_distance):
    med = float(np.median(img))
    sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
    mask = img > med + k * sigma
    mask = nd.binary_opening(mask, np.ones((3, 3)))
    dist = nd.distance_transform_edt(mask)
    pk = peak_local_max(dist, min_distance=min_peak_distance, labels=mask)
    markers = np.zeros(dist.shape, dtype=int)
    for i, (y, x) in enumerate(pk, start=1):
        markers[y, x] = i
    lab = watershed(-dist, markers, mask=mask)
    out = []
    for i, sl in enumerate(nd.find_objects(lab), start=1):
        if sl is None:
            continue
        m = lab[sl] == i
        if m.sum() < min_area:
            continue
        cy, cx = nd.center_of_mass(m)
        out.append((sl[0].start + cy, sl[1].start + cx))
    return out


def score(frames, k, min_area, min_peak_distance):
    found = extra = missed = fp = truth_total = det_total = 0
    for img, seg in frames:
        dets = detect(img, k, min_area, min_peak_distance)
        det_total += len(dets)
        labels = np.unique(seg)
        labels = labels[labels != 0]
        truth_total += len(labels)
        hits = {}
        for cy, cx in dets:
            y, x = int(round(cy)), int(round(cx))
            lab = seg[y, x] if (0 <= y < seg.shape[0] and 0 <= x < seg.shape[1]) else 0
            if lab == 0:
                fp += 1
            else:
                hits.setdefault(lab, 0)
                hits[lab] += 1
        for lab in labels:
            n = hits.get(lab, 0)
            if n == 0:
                missed += 1
            else:
                found += 1
                extra += n - 1
    prec = found / max(det_total, 1)
    rec = found / max(truth_total, 1)
    f1 = 2 * prec * rec / max(prec + rec, 1e-9)
    return dict(found=found, missed=missed, fp=fp, extra=extra,
                truth=truth_total, det=det_total, prec=prec, rec=rec, f1=f1)


def main():
    frames = []
    for f in sorted(RAW.glob('t*.tif'))[::3]:
        segf = SEG / ('man_seg' + f.stem[1:] + '.tif')
        if segf.exists():
            frames.append((np.array(Image.open(f)).astype('float32'),
                           np.array(Image.open(segf))))
    print(f'{len(frames)} frames\n')
    print('   k  min_area  peak_d |  recall  precision      F1 | missed  fp  over-seg')
    best = None
    for k in (3.0, 5.0, 8.0):
        for min_area in (50, 100):
            for pd in (6, 9, 12):
                r = score(frames, k, min_area, pd)
                print(f'  {k:3.0f}  {min_area:8d}  {pd:6d} | {r["rec"]:7.1%} {r["prec"]:10.1%} '
                      f'{r["f1"]:7.1%} | {r["missed"]:6d} {r["fp"]:3d} {r["extra"]:9d}')
                if best is None or r['f1'] > best[0]['f1']:
                    best = (r, k, min_area, pd)
    r, k, a, pd = best
    print(f'\n  best: k={k}, min_area={a}, peak_distance={pd} -> F1 {r["f1"]:.1%} '
          f'({r["found"]} of {r["truth"]} truth cells, {r["fp"]} false positives, '
          f'{r["extra"]} over-segmentations)')


main()
