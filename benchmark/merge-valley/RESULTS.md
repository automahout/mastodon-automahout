# Does the valley test actually separate one cell from two?

`isSameBlob` is the only thing in the pipeline that can tell an over-segmented cell (merge) from two
touching cells (keep both). The geometric gate in front of it was tightened at some point to protect
division daughters, which left the discriminator never being asked. Before widening that gate, this
measured what the discriminator is worth.

Ground truth: CTC **silver truth** segmentation for Fluo-N2DL-HeLa 01, all 92 frames. No detector and
no model is involved — both populations come from the labelled masks.

* **same-cell** — two core points inside ONE labelled cell. The pass must merge these.
* **cross-cell** — the centroids of two DIFFERENT labelled cells. The pass must not.

Reproduce with `python3 measure_valley.py --frames 92` (needs `pillow`, `numpy`).

## Finding 1 — the test was inert

`isSameBlob` compared a ratio of **raw** intensities. This camera's background is 33044 and its cells
read about 33727, so the deepest valley between two distinct cells still measures 0.93 of the core
and the statistic never comes near any usable threshold.

| statistic | at threshold 0.75 |
|---|---|
| raw ratio (as written) | 0.0% of one-cell pairs left split, **100% of two-cell pairs fused** |
| above background | 5.0% left split, 34.2% fused |

It answered "same blob" to everything it was asked. Fixed: the valley is now measured above the
frame's own background, which is the same correction `ImageProfile` needed for the same reason.

## Finding 2 — the best threshold, and what it still costs

Above-background statistic, 75020 same-cell and 652 cross-cell pairs:

| threshold | one-cell left split | two-cell FUSED |
|---|---|---|
| 0.750 | 5.0% | 34.2% |
| **0.890** (best) | 11.6% | **22.1%** |

Fusing two cells deletes one of them, so 22% is not a rate this test can be trusted with alone.

## Finding 3 — how close different cells actually get

Centroid separation between genuinely different cells, in units of the larger cell's radius
(9741 neighbour pairs):

| percentile | separation |
|---|---|
| 0.1% | 1.10 r |
| 1% | 1.46 r |
| 5% | 1.78 r |
| 10% | 2.08 r |

So a gate admits this fraction of distinct-cell pairs for the valley test to arbitrate:

| gate | distinct pairs admitted | with 22% fused, net cells destroyed |
|---|---|---|
| 0.70 r (before) | **0.00%** | 0 — safe, and it never merges anything |
| **1.00 r (now)** | 0.04% | ~0.01% |
| 1.50 r | 1.41% | ~0.31% |
| 1.82 r | 5.48% | ~1.2% |

## What this does and does not fix

Applied: the statistic measures above background, the threshold is 0.89, the gate is 1.0 r, and an
unreadable endpoint now answers "not the same blob" instead of merging — the unknown case must not
delete a cell.

**It does not fix the reported case.** That pair sat at 117 px apart with 64 px radii — **1.82 r**,
3% circle overlap — two detections tiling one elongated cell rather than overlapping it. Reaching it
needs a gate of 1.82, which the table above prices at ~1.2% of neighbour pairs destroyed per frame.
On a dividing culture that is a real cell deleted every few frames, to fix a cosmetic split.

A connected-region test (are both centres in one region above half height?) was measured as the
alternative and is worse in the direction that matters: 99.0% of one-cell pairs merged, but
**68.9% of two-cell pairs fused**.

What is left is the temporal signal: two detections that hold a constant separation over many frames
and move together are one cell, while division daughters separate. That needs linking to have run,
so it belongs in Phase 2 rather than in the per-frame duplicate pass.

---

# The temporal route (measured second, then implemented)

Within one frame the decision cannot be made safely at 1.8 r. Over time it can. `measure_temporal.py`
measures the two populations that a "these two stay together" rule can destroy, from the CTC
**tracking** ground truth — 90 real divisions and 16621 pairs of unrelated co-existing tracks.

## Divisions do not start close

Separation of the two daughters in the **first frame after the division**, in units of cell radius:

| p05 | p50 | p95 |
|---|---|---|
| **1.87 r** | 2.41 r | 3.04 r |

The reported over-segmentation sits at **1.82 r** — just under the 5th percentile of where a real
division begins. That is the gap the rule lives in.

## What the rule costs

Rule: separation stays below `maxSep` and varies by less than `drift`, for `runLen` consecutive
frames.

| maxSep | runLen | drift | fires on real DIVISIONS | fires on unrelated NEIGHBOURS |
|---|---|---|---|---|
| 1.9 r | 8 | 0.25 | 5 / 90 (5.6%) | 4 / 16621 (0.02%) |
| 1.9 r | 12 | 0.15 | **1 / 90 (1.1%)** | **1 / 16621 (0.006%)** |
| 1.9 r | 16 | 0.15 | 1 / 90 (1.1%) | 0 / 16621 (0.0%) |
| 2.0 r | 8 | 0.25 | 6 / 90 (6.7%) | 4 / 16621 (0.02%) |

**1.9 r / 12 frames / 0.15 r** is what shipped. Sixteen frames buys almost nothing and costs a sixth
of a 92-frame movie.

Compare the two routes at the separation the reported case needs:

| route | cost |
|---|---|
| within-frame gate at 1.82 r | ~1.2% of all neighbour pairs destroyed, **every frame** |
| temporal rule at 1.9 r | 1.1% of divisions, 0.006% of neighbour pairs, **once per track pair** |

## Implementation

`AutoCorrectOrchestrator.mergePersistentCoLocatedPairs`, run at the end of the Phase 2 topology
consistency step because it needs finished tracks. Chains are followed only while each side has
exactly one successor — a branch means something else is happening and the series stops rather than
guessing which way to continue. Reported in the consistency line as `co-located-merged=N`.

The rule itself (`holdsTogether`) is pure arithmetic over a separation series and needs no pixels,
which is the whole reason this decision moved out of the frame — so it is tested against real graphs
with no image at all.

---

# Correction: the valley works at the separations I excluded

The first pass of this measurement pooled all separations and concluded the valley test fuses 22% of
genuinely distinct pairs, so the geometric gate was set narrow — `dist < 1.0 r` — and the reported
over-segmentation at 1.8 r was declared unreachable within a frame.

Pooling hid the shape. Fraction of genuinely DIFFERENT cells reading as one blob (above background,
threshold 0.95), **by centre separation**:

| separation | pairs | fused |
|---|---|---|
| ~1.0 r | 22 | **86.4%** |
| ~1.5 r | 435 | 21.4% |
| ~2.0 r | 963 | **3.5%** |
| ~2.5 r | 1215 | 0.2% |

The valley is **least** informative where two detections nearly coincide — two cells overlapping that
much have no gap between their centres to find — and **most** informative where they barely touch.
The old gate used the test only in the band where it cannot be trusted, and excluded the band where
it can. That is why it merged 6 to 8 spots out of ~94 on a frame where nearly every cell was
duplicated.

## What replaced it

Three regimes, in `valleyThresholdFor`:

| separation | decision |
|---|---|
| < 1.10 r | judged at 0.89 — no genuinely different cells have ever been observed this close |
| 1.10 – 1.70 r | **not judged**; the valley is wrong too often. Left to the temporal pass |
| 1.70 – 2.20 r | judged at 0.95 |
| > 2.20 r | two cells |

Cost of the informative band: **38 of 9741 neighbour pairs, 0.39%** — against ~1.2% for a blanket
gate reaching the same distance.

Six over-segmented pairs measured from a real run sit at **1.71–1.98 r with valleys of 1.00–1.92**,
all inside the band and all merged by it.

---

# Setting the threshold from both sides

Ground truth contains no over-segmentation, so it can only price the COST of a threshold — how many
genuinely different cells get fused. The first choice inside the informative band, 0.95, was made
from that side alone, and it left real over-segmented pairs in the graph.

Nine over-segmented pairs measured off two real runs (separations 1.71–1.98 r) have valleys of:

```
0.85  0.87  1.00  1.00  1.01  1.02  1.11  1.27  1.92
```

| threshold | of the nine, merged | different cells fused (1.7–2.2 r band) | of all 9741 neighbour pairs |
|---|---|---|---|
| 0.95 | 7 / 9 | 4.3% | 0.39% |
| **0.85** | **9 / 9** | 6.9% | **0.62%** |
| 0.80 | 9 / 9 | 7.8% | 0.70% |

0.85 is where every observed case merges. A quarter of a percentage point of extra false merges buys
the other two thirds of the real cases.

**Caveat on the nine.** They were measured from screenshots, which are 8-bit and contrast-stretched.
The statistic is a ratio and so is invariant under a linear stretch, but not under clipping — several
of the values at or above 1.00 come from frames where the cell cores saturate, and those are upper
bounds rather than exact. The two that set the threshold, 0.85 and 0.87, come from a frame whose
cores read 215 and 187 out of 255 and are not clipped.
