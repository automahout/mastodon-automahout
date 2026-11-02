# Recovering a cell's real extent from the image

## Why

ELEPHANT reports a **core**, not the cell. The annotation sent for training is
that core, so the next round's core is smaller again — `restoreAnnotationExtent`
says exactly this in its own log line. And a detection covering half a nucleus
is precisely what lets two detections tile one nucleus, so **the undersized
annotation and the duplicate pair are one problem**, not two.

The correction already existed. It measured the target from the user's
**confirmed** cells and returned immediately when there were none — which is
every autonomous run, i.e. the case it was most needed in.

## Method

Fluo-N2DL-HeLa `01`, every 4th frame, 2117 silver-truth cells. The seed is each
cell's brightest pixel (where a detector would centre). Scored against the
truth's own equivalent radius `sqrt(area/π)`. Two model-free estimators:

* `grow(k)` — flood the connected region above `background + k·σ`
* `ray(k)` — walk N rays outward to the same level, take the median

## Per-cell accuracy

| estimator | ratio p05/p50/p95 | within 25% | median abs err |
|---|---|---|---|
| grow(3σ) | 0.76 / 1.13 / 1.85 | 65.0% | 14.5% |
| grow(5σ) | 0.09 / 1.07 / 1.65 | 70.5% | 8.1% |
| grow(8σ) | 0.05 / 1.01 / 1.51 | 72.7% | 3.9% |
| half-max rays | 0.35 / 0.76 / 0.94 | 51.5% | 24.5% |
| **ray(5σ, 8 rays)** | **0.11 / 1.02 / 1.32** | **82.5%** | 9.8% |

**The hypothesis this was written to test was wrong.** The expectation was that
the ray median would beat flood filling because a touching neighbour corrupts
only the rays pointing at it. Against half-max it is the *worst* estimator —
half-max systematically undersizes (0.76). At the `background + 5σ` level the
ray median does win, and clearly: 82.5% within 25% against 72.7% for the best
flood fill, with a much tighter p95 (1.32 against 1.51).

## Population accuracy, which is what the correction uses

`restoreAnnotationExtent` applies **one** multiplier to every annotation, so
per-cell accuracy is not the target — how well a population statistic tracks the
population is.

| estimator | median frame error | worst frame | bias |
|---|---|---|---|
| grow(3σ) | 16.7% | 24.5% | — |
| grow(5σ) | 10.5% | 17.0% | — |
| grow(8σ) | 3.4% | 9.9% | — |
| ray(8σ, 8 rays) | 9.1% | 16.1% | −9.1% |
| **ray(5σ, 8 rays)** | **2.2%** | **8.5%** | −1.3% |

Ray count barely matters: 8, 16 and 32 rays agree to within 0.5%, so 8.

Sanity check — the correction a half-sized core *should* receive is 2.0×:
`grow(8σ)` returns 2.06 (p05 1.95, p95 2.18), `grow(5σ)` 2.21.

## Step size

The pipeline walks in world units, not whole pixels, so the estimator has to
survive a coarser walk than the one measured above:

| step | median frame error | worst | bias |
|---|---|---|---|
| 1.0 px | 2.2% | 8.5% | −1.3% |
| 1.7 px | 3.1% | 9.9% | +3.0% |
| 2.5 px | 4.1% | 14.9% | +3.5% |
| 3.5 px | 8.5% | 20.6% | +8.5% |

So the step stays near voxel resolution — implemented as `seedRadius / 8`, which
for this dataset's cores is about 1.3 px.

## Conclusion

`ray(5σ, 8 rays, step = seedRadius/8)`, median over rays, median over a sample of
detections. Accurate to 2.2% at the population level with no confirmed cells,
and costs a few hundred image reads per detection instead of a region.

## The benchmark said 1.02x and production delivered 1.31x

`measure_level_anchor.py`, scoring the real annotation against CTC silver truth
at the detector's **own** positions from the split dump.

Two things differed between this benchmark and the pipeline, and only one of
them was known:

* **The anchor.** `measure_extent.py` takes the level as the **frame's own
  median** + 5σ. `measuredCellRadius` took it from `estimateBackgroundStats`,
  which deliberately avoids positions near existing spots and so reports the
  **emptiest regions** — about **50 grey levels (3.8σ) below the frame median**
  on this movie. Every ray then walks that much further before it crosses.
* **The seed.** This benchmark seeds at each truth cell's brightest pixel.
  Production seeds where the detector put the spot, which costs about 6 points
  on its own (p50 1.02 → 1.08 at the same level).

Reproducing production by lowering the anchor:

| anchor | measure / truth | after clamp and dead zone |
|---|---|---|
| frame median | 1.08 | 1.09 |
| median − 39 | 1.18 | 1.20 |
| median − 50 | — | **≈1.31, which is production** |
| median − 80 | 2.62 | 1.80 |

The clamp (`EXTENT_MIN_FACTOR` 0.5) and the 10% dead zone were **not** the
cause: they move 1.18 to only 1.20.

### What it is worth, scored as IoU with the truth cell

| level for the extent measure | IoU p50 | IoU p10 |
|---|---|---|
| as-is (production today) | 0.56 | 0.39 |
| frame median + 2σ | 0.65 | 0.47 |
| frame median + 3σ | 0.69 | 0.43 |
| frame median + 4σ | 0.71 | 0.40 |
| **frame median + 5σ** | **0.73** | 0.38 |

Anchored on the frame's own median, keeping the calibrated 5σ: **0.56 → 0.73**.
That is about half of what a true ellipse fit would give (0.92, see
`benchmark/annotation-extent/`), for a change of one line.

The low tail is not the cost it looks like. At the frame median the raw measure
has a terrible p10 (0.10× truth, and 13.1% of readings under half the cell) but
the 0.5 clamp contains it, and the delivered p10 IoU is 0.38 against today's
0.39 — unchanged. Lower levels trade median for tail (2σ gives p10 0.47 and p50
0.65) and are not worth it.

**The estimator floors at 1.08 at detector positions**, whatever the level. The
remaining gap to 1.00 is the seed being off the cell's brightest point, which no
choice of threshold fixes — an ellipse fit does.

### The general lesson

`estimateBackgroundStats` answers "does this spot stand above the background".
It is not the frame's background, and anything calibrated against the frame
cannot be handed it. This is the second pass found reading the wrong one: the
no-signal ceiling lands 3.4σ low for the same reason and catches 9.6% of false
positives where the frame's own median would catch 81.4%.
