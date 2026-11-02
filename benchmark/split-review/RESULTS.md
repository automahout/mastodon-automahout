# Split pass review

Fluo-N2DL-HeLa 01, CTC silver truth, two GUI runs with the split pass ON:
`20261001-144247` (whole movie, before the extent reading existed) and
`20261001-152441` (whole movie, with `extentRatio` in the split dump).

"Covering two cells" = two or more truth centroids within the frame's median
detection radius of the centre — deliberately independent of the detection's
own radius, which would otherwise make "radius separates them" circular. Two
other definitions (centroids inside the disc; >=50% of a cell's area inside the
disc) gave the same conclusions.

## What the old pass did

`measure_split_decisions.py`, whole movie of `144247`:

| truth cells under the detection | detections | split |
|---|---|---|
| 1 | 7911 | 280 (3.5%) |
| 2+ | 271 | 73 (27%) |

Precision 21%. 98% of the run's same-cell double spots came from it (see
`../suppression-distance/RESULTS.md`). It decided on interior unevenness (core
CV), which separates two cells from one at AUC 0.83 against a base rate of ~2%,
and placed pieces by counting intensity basins: of real pairs it split, each
piece landed on its own cell for 5 of 19, and 10 of 19 came out in three pieces.

## Placement: two ellipses fitted to the outline

`prototype_splitter.py`, `split_features.py`. Threshold at the frame's median +
5 spreads, keep the object under the detection, fit one and two components to
its pixels by EM, read each as a uniform ellipse. On 257 detections covering two
cells the two centres landed on two different truth cells for **228 (89%)**.
Distance-transform peaks and a neck test were tried first and did not separate
anything. Ported to Java as `ShapeSplit`; `export_shape_windows.py` +
`ShapeSplitParityHarness` + `compare_shape_parity.py` hold the two equal (858
windows, exact on area/IoU, 1.4e-10 px on centres).

## Decision

AUC, two-cell against one-cell, `152441` t=0..39:

| feature | AUC |
|---|---|
| **unclamped extentRatio** (`ExtentReading`) | **0.984** |
| two-ellipse gain (iou2 − iou1) | 0.879 |
| radius / frame median | 0.834 |
| core CV (what the old pass used) | 0.725 |

`evaluate_decision_rule.py`. The rule:

1. `extentRatio` > median + 1.5 MAD of every reading the run has seen so far,
   both taken as GROUPED statistics (see below);
2. two ellipses fit better than one (iou2 > iou1);
3. neither piece lands inside another detection's own radius;
4. the measured extent (extentRatio x radius) stays clear of every image face.

Scored by the OUTCOME, over EVERY detection: right when the two pieces sit on
two different truth cells, neither on background, neither on a cell another
detection already has.

| run | data | splits | right | background | one cell split | merged fixed |
|---|---|---|---|---|---|---|
| 152441 | t<40 (K chosen here) | 14 | 14 (100%) | 0 | 0 | 12/17 |
| 152441 | **t>=40 (held out)** | **108** | **97 (90%)** | 5 | 5 | 81/209 |
| 234301 | t<40 | 11 | 10 (91%) | 0 | 0 | 10/12 |
| 234301 | **t>=40** | **28** | **18 (64%)** | 2 | 7 | 17/111 |

`234301` is the first GUI run of the Java rule (conditions 1-3, plain
median/MAD). Its real splits, scored from the origin dump by
`measure_production_splits.py`, were 21% right; the offline reference on the
same run's detections gave 20%, so the port was faithful and the rule was not.

### What the first GUI run found

- **An evaluation hole.** The offline scoring kept only detections on a truth
  cell, so fragments cut by the image edge were never scored. In the GUI run 118
  of 188 pieces lay within 15 µm of an edge and 57 of 91 splits put a piece on
  background: at a face the extent walk runs out of image and reads large.
  Condition 4, and scoring every detection, close it.
- **A bar that jumped.** extentRatio is a multiple of 1/16 (eight rays, a step of
  radius/8, the middle two averaged). The run's median sat on the boundary
  between 0.9375 and 1.0, so as readings accumulated the plain median and MAD
  flipped between grid cells and the bar moved between 1.278 and 1.076. Every
  single nucleus that run split in two was split while the bar stood at 1.076.
  Grouped statistics (each reading spread over its grid cell) keep it at
  1.16-1.21.
- A minimum two-ellipse gain of 0.02 was measured too: +2 and +1 points on top
  of the grouped bar, for fewer merged detections fixed. Not adopted.

### Earlier corrections

- Condition 3 started life as "no other detection's centre inside the measured
  extent", which looked decisive on t<40. On held-out frames the commonest
  failure was a PIECE landing on an already-detected neighbour, which that test
  cannot see.
- Scoring by "truth cells under the disc" called a split that recovered an
  undetected neighbour wrong (`flagged_examples.png`, kept locally: crops of the challenge's images are not
  redistributed).
- The wrong splits are not dividing cells: 2 of 126 were within two frames of a
  division in the tracking truth, against 8.7% of all single-cell detections.
  The single nuclei that were split (`one_cell_splits.png`, kept locally) are elongated or
  bean-shaped ones, the same few cells over consecutive frames.

### Open

`234301` held out is 64%, against 90% for `152441`: fewer merged detections to
find (111 vs 209) and 7 single nuclei split. The two runs trained different
detectors; 28 splits are too few to say which number is typical.
