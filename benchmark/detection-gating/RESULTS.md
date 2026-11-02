# Gating detections in feature space, instead of chaining 1-D thresholds

## Why

`ElephantIntegration` decides a training label in two places, and both read the
same way: a spot is `fp` if it was tagged `fp`, `unlabeled` if it was tagged
`unlabeled`, and otherwise **`tp`**. Positive is the default. So every
background detection the cleanup passes fail to catch is taught to the model as
a cell, and the next round detects more of them.

The cleanup passes that could catch them are independent one-dimensional
thresholds (flat contrast, standout, the stated-count cap), each with its own
gate and its own escape hatch. The reported screenshot shows the result: small
dim ellipses sitting on flat background, while several bright nuclei in the same
crop carry no detection at all.

## Method

Fluo-N2DL-HeLa `01`, 92 frames, silver truth `01_ST/SEG` (8602 cells).

Both populations are measured **the same way** — a region grown from a seed
while pixels exceed `median + 3σ`, σ from the MAD — so the separation cannot be
an artefact of measuring cells from a truth mask and background from a probe.
Truth says only *which seeds are cells*; it never measures them.

Background seeds are local maxima of the smoothed frame outside every truth cell
(dilated by 8 px) — the noise peaks a detector actually fires on, not uniform
random positions, which would make the problem look easier than it is.

`measure_gate.py` collects the features; `measure_anchored.py` compares
estimators.

## What the features look like

|                  | p05 | p50 | p95 |
|------------------|-----|-----|-----|
| cells, standout (σ) | 4.05 | 23.74 | 48.98 |
| background, standout (σ) | −0.88 | 0.61 | 1.75 |
| cells, radius (px) | 7.29 | 15.13 | 20.54 |
| background, radius (px) | 0.50 | 0.50 | 0.50 |

The populations barely touch. **The features were never the problem** — nothing
in the pipeline was asking this question at the point where the label is chosen.

## 2-cluster k-means is the wrong estimator

Run on the unlabelled cloud, at background shares from 5% to 75%:

| features | cells kept | background rejected |
|---|---|---|
| standout alone | 46.7 – 58.8% | 100% |
| radius alone | 94.1 – 94.5% | 100% |
| both | 70.2 – 92.6% | 100% |

Rejection is perfect everywhere. **Recall is not**: on standout, k-means throws
away 41–53% of real cells, and on both features it is unstable at low
contamination (70.2% at 5%). The reason is structural — the cell population's
standout spans 4–49 σ, so a variance-minimising split cuts *the cells* in half
rather than separating them from a tight background cluster at the noise floor.
This is not two comparable clusters. It is one tight cluster at the noise floor
and everything else.

## The anchored gate

`standout ≥ 3σ AND radius ≥ Otsu(radii)`, with the radius cut capped at the p05
of the above-noise population so a numerous background cannot gate out cells on
size. The σ anchor comes from the **image**, not from the detections.

| background share | cells kept | background rejected |
|---|---|---|
| 5% | 94.1% | 100% |
| 25% | 94.5% | 100% |
| 75% | 94.5% | 100% |
| 90% | 94.5% | 100% |

Flat from 5% to 90% contamination. `s_min` is not delicate: 1.5σ → 98.3%,
3σ → 94.5%, 6σ → 91.7%, with 100% rejection throughout.

## Why both axes, and which one has to come from the image

The background seeds here grow to radius 0.5 px — they never rise above 3σ at
all, so on this data the radius cut alone does the rejecting. That is the data
being kind. The spurious detections in the screenshot are 3–5 px across, i.e.
ELEPHANT assigns them an extent the image does not support. So the stress test
inflates the background radii and re-measures:

| background radius | both axes | radius alone |
|---|---|---|
| 1–4 px | 100% rejected | 100% rejected |
| 1–9 px | 100% rejected | 77.4% rejected |
| 1–12 px | 99.9% rejected | 57.7% rejected |

**A detector that is wrong about a position is also wrong about the size it
reports there.** The size axis is the detector's own output and degrades with
it; the brightness axis is read from the image at that position and does not.
Any gate that uses only what the detector said about itself can be talked into
anything.

## When to ask the user

Not "when confidence is low" — the ambiguous-band share barely moves (6.0% on
cells only, 10.6% on background only). The discriminating case is when **the
gate empties the population**: run on background alone it admits 0 of 3680. A
gate that rejects everything is not making a gating decision, it is reporting
that the detector produced nothing cell-like, and that is a question for a
person. Run on cells alone it admits 94.1% and says nothing.

## Conclusion

Gate on (standout above background, radius), anchored on the noise floor rather
than clustered; withhold what it rejects from training rather than tagging it
`fp`, since 5.5% of real cells fall below the gate and teaching those as
background is the failure mode that removed the previous reclassification
passes; and ask the user only when the gate admits nothing.

## What the radius floor costs, and who pays it

`measure_small_cells.py`, same 8602 cells and 3680 background peaks. The gate
ANDs a standout floor with a radius floor learned as
`min(otsu(radii), p05(brightRadii))`.

| rule | cells kept | bg rejected | smallest decile kept |
|---|---|---|---|
| standout only | 99.1% | 99.9% | 90.8% |
| **standout AND radius (as shipped)** | **94.5%** | **100.0%** | **45.0%** |
| sub-cellular floor (1 px) | 97.5% | 100.0% | 74.9% |

The learned cut of 8.13 px withheld **396 cells, and all 396 were in the
smallest decile** — 396 of 866. It is not a size filter with a side effect on
small cells; it is a filter on small cells, exclusively. What it bought was
**3 background detections out of 3680**.

The adversarial case the radius axis was introduced for — a detector wrong about
a position is also wrong about the size it reports there — does not rescue it:

| rule (background radii inflated to cell size) | cells kept | bg rejected |
|---|---|---|
| standout only | 99.1% | 99.9% |
| standout AND radius | 94.1% | 99.9% |

Identical rejection. The earlier "radius alone collapses to 57.7%" figure is
about the radius axis *on its own*; against the standout axis it adds nothing,
because the standout axis has already rejected those detections.

### Why a percentile can never stop

`min(otsu, p05)` is read off the detections, so it always cuts something. On a
frame whose detections are every one of them real it still withholds the
smallest slice — and again the next round. The smallest cells in a dividing
culture are the ones either side of a division, so this is a ratchet against
exactly the population that goes missing.

Background peaks measure **0.5 px**, and the three that clear the standout floor
are all under one voxel. The floor's real job is "a detection with less extent
than the sampling can resolve is not a body", which is a statement about the
sampling and does not move with the specimen. Shipped as
`SUB_CELLULAR_VOXELS = 2.0` in-plane voxels.

## Where the no-signal deletion ceiling belongs

`measure_no_signal.py`, same two populations. The pass deletes a detection whose
interior reads no more than the ceiling.

| delete at | cells lost | background caught | grey level (bg 33044, σ 7) |
|---|---|---|---|
| 1.50σ | 0.00% | 89.4% | 33054 |
| 2.00σ | 0.06% | 97.4% | 33058 |
| **2.50σ** | **0.35%** | **99.6%** | **33062** |
| 3.00σ | 0.93% | 99.9% | 33065 |
| 4.00σ | 4.82% | 100.0% | 33072 |
| 20.30σ | **41.49%** | 100.0% | 33186 |

Two wrong answers preceded the measured one and both are in the table.

**1.5σ** is `DEEP_BACKGROUND_SIGMA`, chosen because no real cell in the set
stands below 1.75σ — true, and it costs nothing, but it catches only 89.4% of
background. The detections the user kept seeing on blank space read 0.3–1.9σ,
i.e. inside the 10.6% it misses.

**The bright tail** was the correction for that: `background + 15% of the climb
to ImageProfile.brightTail`. On this dataset that is 20.3σ, and it deletes
**41.5% of the real cells** — the user watched plainly visible nuclei vanish.
The argument for it was that sigma measures the background's noise rather than
the distance to a cell. The table says otherwise: the two populations separate
cleanly *in units of that noise*, cells at 3.04σ (p01) against background at
1.75σ (p95). The unit was right; the coefficient was off by an order of
magnitude, and replacing the unit hid that rather than fixing it.

**2.5σ** catches 99.6% of background for 0.35% of cells (30 of 8602), and sits
below the 1st percentile of real cells. Shipped as
`AutoCorrectOrchestrator.NO_SIGNAL_SIGMA`.

## The no-signal pass deleted nothing (measure_no_signal_anchor.py)

Two errors, and the second hid the first.

`NO_SIGNAL_SIGMA = 2.5` was chosen on a fixture of 8602 truth cells and 3680
synthetic background peaks, where it read "0.35% of cells lost, 99.6% of
background caught" and looked free. Then the ceiling was anchored on
`estimateBackgroundStats`, which avoids positions near existing spots and so
reports the frame's **emptiest regions** — about **3.8σ below its own median**.
A ceiling meant to sit 2.5σ above the background sat roughly a sigma **below**
it, and nothing could ever fall under it.

Re-measured on the detector's own 3245 detections labelled against CTC silver
truth, at the radii the extent fix now produces, reproducing the pass exactly
(13 planar interior offsets, 5 core offsets, BOTH medians must fall under):

| k | caught (covers no cell) | lost (covers a cell) | deleted overall |
|---|---|---|---|
| **anchored where it used to look** | | | |
| 1.5 | 0.0% | 0.00% | 0.0% |
| 2.5 | **0.0%** | 0.00% | 0.0% |
| 4.0 | 12.3% | 0.00% | 3.3% |
| 5.0 | 63.7% | 0.30% | 17.5% |
| **anchored on the frame's own median** | | | |
| 0.5 | 22.5% | 0.00% | 6.1% |
| 1.0 | 60.6% | 0.19% | 16.5% |
| 1.25 | 70.7% | 0.19% | 19.3% |
| **1.50** | **76.6%** | **0.57%** | 21.2% |
| 2.0 | 78.9% | 2.22% | 23.0% |
| 2.5 | 80.2% | 3.98% | 24.7% |

**1.5 is the knee.** 1.25 → 1.5 buys 5.9 points of catch for 0.38 of loss, about
15:1 in favour; 1.5 → 2.0 buys 2.3 for 1.65, about 1.4:1 against. Almost the
whole catch is available before the loss starts, which is what a threshold on
the right anchor looks like.

The extent fix helps this pass too: at the same k the loss falls (5.3% → 4.0% at
k=2.5), because a radius fitted to the cell puts the interior samples inside it.

### What this does and does not solve

It removes about 21% of all detections, which is 76.6% of the 27% that cover no
truth cell. It does **not** touch the over-detection: the first window still runs
5.4x the truth count, and what remains is duplicates — several detections on one
cell — which is a different problem.

The reason recorded is `PROBABLY_NOT_A_CELL`, so the cell-count floor bounds it,
and since `a84f5c8` these deletions also become `fp` training negatives.
