# Seed → object, instead of "is there a valley between these two points?"

## The request

> Segment Anything Model のように一部のアノテーションから全体構造を推定するような機構

Infer the whole structure from a partial annotation, then two annotations landing
in the same structure are trivially one cell. This measures the **classical**
version of exactly that — seed → region → "same region?" — so it can be measured
today and shipped without a new model dependency.

## Populations

31 frames of Fluo-N2DL-HeLa with silver truth. Both populations come from the
truth, never from a detector:

* **SAME** — two seeds inside *one* truth cell, at that cell's two brightest
  local maxima: what an over-segmenting detector actually produces.
* **DIFF** — two seeds in *different* truth cells, close enough that the merge
  would consider them at all (< 2.2 radii).

A good arbiter merges SAME and refuses DIFF.

## Result

| arbiter | merges one-cell pairs | wrongly merges two-cell pairs |
|---|---|---|
| **valley along the segment (shipped)** | **9.0%** | 11.1% |
| connected components, 3σ | 89.9% | 79.8% |
| connected components, 8σ | 79.8% | 59.0% |
| intensity saddle ≥ 0.80 | 76.7% | 9.5% |
| intensity saddle ≥ 0.85 | 57.1% | 7.9% |
| watershed, 3σ | 81.9% | 10.0% |
| **local watershed, 3σ, stride 2** | **86.1%** | 10.2% |

**The shipped test merges 9% of the pairs it exists to merge, and still gets
11.1% of the distinct ones wrong.** It is not a weak arbiter, it is an inert one
— which is why the one-cell-two-detections problem survived every round of
threshold tuning. Tuning a test that does nothing cannot help.

Three things the measurement settles:

* **The straight segment is the wrong path.** Two lobes of one cell are joined
  by whatever path the cell's shape provides; the segment crosses background the
  cell goes around.
* **Connectivity alone is not enough.** Thresholding fuses touching cells —
  59–80% of distinct pairs wrongly merged, useless at every threshold tried.
* **Shape beats intensity.** The intensity saddle (the same criterion computed
  per pair) is far better than the segment but still loses to the watershed at
  matched error: 57.1% against 77.0% at ~8%. Two touching round cells of equal
  brightness have a high intensity saddle and an obvious neck; only the distance
  transform sees the neck.

## Cost

The window is read through a `Sampler` and costs per read:

| stride | reads/pair | merges | wrongly merges |
|---|---|---|---|
| 1 | ~15900 | 89.6% | 10.0% |
| **2** | **~4000** | **86.1%** | 10.2% |
| 3 | ~1800 | 75.8% | 10.3% |

The error rate is flat across all three, so the stride buys recall, not safety.

## The Java did not match until it was checked against the same data

A first implementation scored **61.0% / 3.4%** on a fixture of 400 real windows,
against the 86.1% / 10.2% measured here. It was not a porting slip: the marker
distance for the watershed was 3 px, and the distance transform of one cell has
several shallow maxima, so each one started its own basin and split the cell.
Swept on the fixture:

| min marker distance | merges | wrongly merges |
|---|---|---|
| 3 | 61.0% | 3.4% |
| 4 | 74.8% | 4.9% |
| **5** | **85.5%** | **6.8%** |
| 6 | 91.8% | 9.7% |
| 8 | 97.5% | 16.0% |

5 reaches the Python measurement's recall at a third of its error. A prominence
floor on the maxima was tried at every distance and was strictly worse — it
suppresses real maxima, not ripples — so it is not a knob.

`SeedToObjectFixtureTest` runs the shipped Java over those 400 windows and pins
85.5% / 6.8%. Synthetic hard-edged discs were **not** used to tune anything:
their distance transforms have two sharp maxima where a real cell's has one, and
a probe on them disagreed with the real data badly enough to look like a defect.

## Known limitation

A watershed separates by shape, so a cell that really is two lobes joined by a
thin neck is split whether or not it is one cell. That is the missing 14%, and
`SeedToObjectTest.aStronglyBiLobedCellIsSplit` asserts it rather than hiding it.
This is where a learned segmenter — the actual SAM — would win, because it knows
what a nucleus looks like and a distance transform does not.

## The other direction: one detection over two cells

`measure_split.py`. The arbiter above answers "are these two seeds one object?",
which is what a MERGE needs. Nothing answered the inverse — "does this one
detection cover more than one object?" — and that is the case left standing: the
neighbour cap in `resizeToMeasuredExtent` needs a second detection to protect,
so it cannot help when the model produced only one.

Populations from the truth, never from a detector. **ONE**: a detection centred
on a truth cell, radius inflated 1.2x. **TWO**: a detection centred on the
midpoint of two adjacent truth cells (centroids within 2.2 r) and wide enough to
cover both. 2876 and 385 respectively over 31 frames.

Same primitives as the arbiter — threshold at 3σ, distance transform, peak
markers — counting the markers inside the detection.

| min marker separation | ONE kept whole | TWO split |
|---|---|---|
| 5 px (the arbiter's `MIN_MARKER_DISTANCE`) | 71.7% | 95.3% |
| 6 px | 88.1% | 90.4% |
| **8 px** | **94.3%** | **86.8%** |
| 10 px | 96.7% | 81.8% |
| 12 px | 98.0% | 72.7% |

The split wants more separation than the merge arbiter, and it should: a false
merge loses a cell the detector found, a false split invents one that is then
taught back.

### Gating on size, which costs almost nothing

5.7% false splits over every detection is still too much exposure. A merged pair
is by construction large for its frame, so only detections above `g x` the
frame's own median radius are examined (8 px separation throughout; percentages
over the FULL population, so a detection never examined counts as kept whole):

| gate `g` | ONE examined | TWO examined | ONE kept whole | TWO split |
|---|---|---|---|---|
| 1.0 | 2379 | 384 | 95.8% | 86.8% |
| 1.2 | 1429 | 384 | 97.8% | 86.8% |
| 1.4 | 244 | 371 | 99.4% | 86.0% |
| 1.6 | 39 | 318 | 99.7% | 75.6% |

**This table is measured against the wrong median and 1.4 shipped on it.** The
`g x median` above is `g x` the median radius of the TRUTH cells, which the
pipeline cannot see. What it computes is the median of its own DETECTIONS — and
the merged detections are in that population, pulling the median up, as does the
detector's own inflation and `resizeToMeasuredExtent`.

### The gate the pipeline actually applies

One realistic detection set per frame: that frame's single-cell detections plus
its merged ones, with the gate read off the mixture.

| gate | ONE kept whole | TWO split |
|---|---|---|
| 1.2 x frame median | 99.5% | 85.2% |
| **1.4 x frame median (as shipped)** | 99.8% | **68.6%** |
| 1.6 x frame median | 100.0% | 43.9% |
| above the frame p70 radius | 98.9% | 86.5% |
| **above the frame p75 radius** | **99.1%** | **85.7%** |
| above the frame p90 radius | 99.7% | 67.8% |
| no gate at all | 94.3% | 86.8% |

The shipped gate splits 68.6%, not the 86.0% claimed for it. A percentile asks
whether a detection is large *for its frame*, which is scale-free, so it does
not move when everything is inflated together. At p75 the split rate is within
a point of no gate at all while false splits fall from 5.7% to 0.9%.

Shipped as `AutoCorrectOrchestrator.splitMergedDetections` with
`SPLIT_SIZE_PERCENTILE = 0.75`, and `SeedToObject.objectCentresWithin` doing the
counting.

### The object level does not explain the misses

Sweeping the threshold the mask is built at, with the bright half of the pairs
reported separately (both cells above 34σ — the saturated pairs):

| level | ONE kept | TWO split | TWO split, bright half |
|---|---|---|---|
| 3σ | 94.3% | 86.8% | 88.6% |
| 5σ | 93.8% | 84.8% | 91.7% |
| 8σ | 92.3% | 88.2% | 95.9% |
| 20σ | 89.4% | 77.6% | 96.4% |

Bright pairs already split 88.6% of the time at 3σ, so the classifier was not
what was failing. Kept at `SeedToObject.OBJECT_LEVEL_SIGMA`.

### How many pieces, which nothing was asking

The tables above ask only whether a detection split into **two or more**. They
never asked how many, and the answer matters: the two-cell population holds
exactly two cells by construction, so anything above two is already
over-segmentation there.

| pieces | one real cell | a genuine pair |
|---|---|---|
| 1 | 92.4% | 13.2% |
| 2 | 3.8% | **76.4%** |
| 3 | 1.0% | 7.0% |
| **4 or more** | **0.9%** | **3.4%** |

A genuine merged pair comes back as 4 to 8 pieces 3.4% of the time, and a single
real cell 0.9%. Those are not splits; the detection is shattered. On a run of
sequence 01 the user's t=9 frame showed one cell carrying a dozen overlapping
circles, and window 1 alone reported `Split: 26` where earlier runs reported 1
to 11 — the p75 gate examines a quarter of every frame instead of the 8% the
1.4x form did, so the shattering became visible.

Refusing above **three** keeps 83.4% of the genuine merges against 85.7% with no
ceiling. Three rather than two because a real pair reports 3 pieces a further
7.0% of the time and three cells can genuinely touch; above that the count is
not describing cells. Shipped as `MAX_SPLIT_PIECES = 3`.

`SeedToObject.objectCentresWithin` still reports every centre it finds — the
refusal is the caller's, because a counter that capped silently would hide the
case the ceiling exists for.

### Shape said two; the intensity was never asked

Over-splitting survived the piece ceiling: the user's t=9 frame still showed a
mottled nucleus wearing three circles. Two hypotheses, both measured.

**Wrong: that the gate selects a harder population.** Restricting the one-cell
population to the top quartile by radius within its frame — exactly what
`SPLIT_SIZE_PERCENTILE` selects — makes it very slightly *easier*, not harder:

| population | 1 piece | 2 | 3 | 4+ |
|---|---|---|---|---|
| every single cell | 92.4% | 3.8% | 1.0% | 0.9% |
| single cells the gate selects | 93.6% | 4.2% | 0.8% | 0.3% |

**Right: the criterion is about shape alone.** `objectCentresWithin` counts
maxima of the DISTANCE TRANSFORM. A HeLa nucleus has visible internal texture,
and a threshold mask over one can be pinched enough to carry two distance maxima
while the brightness between them never dips.

Over the 163 single cells and 334 genuine pairs that produce two or more centres
at all, requiring the intensity to fall between the two brightest centres, as a
fraction of the dimmer of them:

| require ratio ≤ | false splits kept | true splits kept |
|---|---|---|
| none | 100.0% | 100.0% |
| 0.85 | 58.3% | 91.3% |
| **0.80** | **53.4%** | **89.2%** |
| 0.70 | 43.6% | 84.4% |

At 0.80 this removes **47% of the false splits for 11% of the true ones**.
Tighter costs true splits faster than it saves false ones. Shipped as
`SPLIT_MAX_SADDLE = 0.80`, measured by `SeedToObject.saddleRatio`, which
subtracts the background first — on this dataset's 33000 offset an unsubtracted
ratio reads 0.998 and no valley is ever found.

### Selecting by core unevenness instead of by size

The user's proposal: under a fluorescence assumption, only test detections whose
intensity varies near the centre. A nucleus is brightest at its middle and
smooth there; a detection covering two of them has the valley between them
running through its middle, so its core reads bright–dark–bright.

Coefficient of variation of the samples within half a radius of the centre,
background subtracted, over 2868 single cells and 384 genuine pairs:

| | single cell | genuine pair |
|---|---|---|
| median | 0.129 | **0.636** |
| p90 (single) / p10 (pair) | 0.220 | **0.314** |

The populations barely overlap. As a selector it reaches **96.6% of the pairs
while touching 7.8% of the single cells**, where the radius percentile it
replaces had to examine a quarter of every frame to reach the same pairs.

End to end, with the piece ceiling and the saddle also applied:

| gate | true splits | false splits |
|---|---|---|
| none | 73.7% | 2.2% |
| radius > 1.4 × the frame median | 73.2% | 0.0% |
| **core CV > 0.25** | **73.7%** | 1.2% |
| **core CV > 0.25 and radius > the median** | **73.7%** | **0.6%** |

A size gate on its own costs true splits; this one does not. Both together beat
either, so the radius floor stays, loosened from the 75th percentile to the
median — a detection smaller than a typical cell is not covering two of them
whatever its texture says. Shipped as `SPLIT_MIN_CORE_CV = 0.25`, read from
`interiorIntensityProfile` element 4, which is memoised and already computed for
these spots by the other passes.

## Temporal agreement: measured, and it does not pay

Three single-frame criteria are stacked on the split decision — the core's
unevenness, the distance-transform basin count, the intensity saddle — and all
three read the same frame. The obvious next move is an independent one: a
spurious split should be unstable in time where a real pair persists.

`measure_temporal.py`. What the pipeline can actually do: `splitMergedDetections`
runs inside `predictWindow`, **before `[4/6]` links anything**, so there is no
track to follow. It knows a detection's position at `t` and can sample `t±1`
there. Ordinary motion has a median of 1.5 µm against a nuclear radius near 9.5,
so the same position is the right model for one frame either way.

Consecutive frames 1–40, the shipped single-frame verdict evaluated at each of
`t−1`, `t`, `t+1`:

| rule | seq 01 true / false | seq 02 true / false |
|---|---|---|
| **this frame alone (shipped)** | **71.9% / 3.1%** | **71.1% / 0.8%** |
| this frame and either neighbour | 67.0% / 2.1% | 66.0% / 0.5% |
| at least 2 of the 3 | 71.5% / 2.8% | 69.4% / 0.6% |
| all 3 frames | 53.8% / 1.0% | 53.1% / 0.2% |

**It costs 5 to 17 points of true split per point of false split saved.** The
saddle, for comparison, removed 47% of the false splits for 11% of the true. Not
shipped.

### Why the premise was wrong

A spurious split is not unstable in time. It comes from persistent internal
structure — a nucleolus, a patch of condensed chromatin — which is in the same
place at `t−1` and `t+1`. And the test at `t±1` reads a nearly identical image,
so its verdict is strongly correlated with the one at `t`: it is a fourth look at
the same evidence, not an independent one.

The same argument applies to asking the DETECTOR's verdict at `t±1` instead of
re-running the shape test: it is the same model on a nearly identical image.

### Where a temporal check could still work

Not on the image, but on the LINEAGE, and therefore not here. If a region is one
cell at `t−1` and the two spots a split produced at `t` both continue to `t+1`,
that is a division and genuinely independent evidence. It needs tracks, which
exist only after `[4/6]`, so that check belongs in the correction phase and
would be a revision of a split rather than a gate on one.

## The whole funnel, and what the saddle is worth behind the new gate

Where genuine pairs are lost, end to end, with everything shipped:

| outcome | genuine pairs | single cells |
|---|---|---|
| **SPLIT** | **73.8%** | **0.6%** |
| only one basin | 10.1% | 2.2% |
| saddle too shallow | 9.1% | 0.2% |
| shattered (>3 pieces) | 3.4% | 0.4% |
| core CV too even | 3.4% | 79.3% |
| radius ≤ frame median | 0.3% | 17.3% |

Two hypotheses about the remaining 26% were tested and both were wrong.

**Not the detection being off-centre.** Rebuilding the pair population so the
detection is centred on ONE of the two cells rather than their midpoint — which
is what a detector actually produces — gives the same funnel: 73.8% split, 11.7%
one basin. Widening the basin search past the detection's own radius makes it far
worse, because it pulls in unrelated neighbours: at 1.5× the radius 35.1% come
back shattered, at 2.0× it is 72.2%.

**The saddle, measured again behind the gate that now precedes it:**

| saddle ≤ | true splits | false splits |
|---|---|---|
| 0.70 | 71.2% | 0.42% |
| **0.80 (what shipped)** | **73.8%** | **0.56%** |
| 0.90 | 76.4% | 0.63% |
| 0.95 | 78.7% | 0.73% |
| **none** | **82.9%** | **0.76%** |

Dropping it is **+9.1 points of true split for +0.20 of false, a 45:1 trade**,
and every step between 0.80 and none is at least 25:1 in favour of loosening.

The saddle was measured behind the RADIUS-percentile gate, which let a quarter of
every frame's single cells through, and there it earned its place: 47% of the
false splits for 11% of the true. Replacing that gate with the core-unevenness
test changed what reaches the saddle — only 0.2% of single cells now do — so it
can no longer buy specificity and only costs real splits. **When one gate in a
chain is replaced, re-measure the ones behind it.** Removed.

## The bar read off the frame, not from a constant

`SPLIT_MIN_CORE_CV = 0.25` was measured on one specimen. A run that has to work
autonomously, with nobody confirming a cell, cannot rely on that holding for the
next movie — nuclei that are smoother or more textured move the whole
distribution under it.

Measured on a realistic per-frame mixture (the frame's single-cell detections
together with its merged ones, so the statistic is read off what the pipeline
actually sees):

| rule | true splits | false splits |
|---|---|---|
| absolute CV > 0.25 | 82.9% | 0.76% |
| median + 2 MAD | 83.1% | 0.73% |
| **median + 3 MAD** | **81.8%** | **0.52%** |
| median + 4 MAD | 77.7% | 0.38% |
| median + 5 MAD | 73.8% | 0.17% |

Three MADs costs 1.1 points of true split against the constant and saves 0.24 of
false — and unlike the constant it does not have to be right about the next
specimen. Shipped as `max(SPLIT_MIN_CORE_CV, median + 3·MAD)`, the constant
becoming a floor rather than the answer.

**Median and MAD, not mean and sigma.** The outliers being hunted are in the
population the threshold is read from; a mean and a sigma let them drag the bar
out to meet themselves. There is a test that four extreme values move the MAD
form by less than 0.02 while the mean form goes to 4.77.

### The caveat, measured rather than assumed

Run on single cells ALONE the rule still flags 8.6% of them, 14.3% on the worst
frame. A distributional tail is not empty just because nothing is merged — the
same property that made a percentile floor keep cutting the smallest decile of
real cells elsewhere in this pipeline.

It does not matter here because this gate only decides which detections are
EXAMINED; the basin count and the piece ceiling are what refuse them, and end to
end the false-split rate goes *down*, 0.76% to 0.52%. It would matter if this
reading were ever used to delete something directly.

## The core-sized measurement above is invalid — reverted

Shipped and reverted the same day. Production numbers, window 1:

| | before | after |
|---|---|---|
| splits per window | 2–8 | **23–48** |
| refused as "more bodies than a detection can be covering" | 1–2 | **46–137** |
| graph edits under SPLIT | −19/+48 | **−160/+390** |
| continuity | 99% | **86%** |
| CV_temporal | 0.09 | **0.18** |
| mean track length | 10.6 | **7.5** |

**The inference "radius carries no information" was circular.** That table gave
*both* classes the same radius — the frame's median — by construction. A
constant cannot carry information; I had built the answer into the fixture and
then read it back out.

**And flattening the radius is what hid the danger in the reach.** Production
detections have a 34% spread in size (`CV_size=0.34`). A search radius of 1.5 ×
the *frame's* median is a modest reach for a median-sized detection and two to
three times its own radius for a small one, which reaches into the neighbouring
cells — so small detections shattered into spots placed on their neighbours.
With every detection the same size in the fixture, that variance did not exist
and could not be seen.

The earlier table that found reach 1.5× catastrophic (35% shattered) was right;
re-expressing it against the frame's radius did not make it safe, it only made
the fixture unable to show it.

**Lesson.** Making a fixture more realistic on one axis can make it *less*
realistic on another, and the axis flattened is the one that stops being
visible. The previous fixtures were wrong about the radius *value*; this one was
wrong about the radius *distribution*, which was worse.

What would settle it: the radius a detector actually assigns to a detection
straddling a pair, versus one on a single cell — which needs the detector's own
output, not truth-derived geometry.

## Recording what the detector actually produced

Every fixture above was built from ground truth: a position from a truth
centroid and a radius chosen by whoever wrote the script. Five conclusions drawn
that way were wrong, and the last was wrong in a way its fixture could not show.

`SplitFixtureDump` writes every detection the split pass sees — **before any
gate**, so the file is the population the gates act on — with its position,
radius and interior profile. Off unless asked for:

```
-Dautomahout.splitDump=/tmp/split-fixture.tsv     # JVM property
AUTOMAHOUT_SPLIT_DUMP=/tmp/split-fixture.tsv      # or the environment
```

`label_dump.py` joins it to CTC truth and labels each detection by how many
truth centroids its disc contains — 0, 1, 2, 3+. It does **not** assume the axis
order: both mappings are tried and the one that lands detections on cells is
reported, so a wrong guess about the coordinate convention appears as a number
instead of as a silent result. If neither mapping lands half the detections it
says so and stops.

Verified end to end against a synthetic dump built from truth (one detection per
cell plus eight straddling the closest pairs): the axis self-check reported
100% against 0%, and the two classes came back 83.3% "covering ONE" and 16.7%
"covering TWO" with the radii they were given.

**The question it exists to answer:** what radius does the detector actually
assign to a detection straddling two cells, against one on a single cell — and
how wide is the spread. That cannot be derived from truth, only recorded.

## What the detector's own detections said

First dump, 2938 detections over one window of Fluo-N2DL-HeLa 01:

| | share | radius p10/p50/p90 | core CV p10/p50/p90 |
|---|---|---|---|
| covering NO truth cell | **33.6%** | 2.79 / 5.68 / 10.86 | 0.00 / 0.00 / 0.50 |
| covering ONE | 65.7% | 8.34 / 11.25 / 14.00 | 0.00 / 0.00 / 0.01 |
| covering TWO | **0.7%** | 12.80 / 14.00 / 14.00 | 0.00 / 0.01 / 0.01 |

Three findings, and the second is a bug the benchmark could never have shown.

**The core CV reads 0.00–0.01 for every class**, against a bar of 0.25. The
reading was taken about ZERO: a sample inside a detection is an absolute 16-bit
intensity on this dataset's 33072 offset, so a nucleus whose interior varies by
500 grey levels divides 130 by 33500 and reads 0.004. **The gate was
arithmetically incapable of firing** — which is what "2 to 8 splits per window"
and "489 unremarkable" were reporting all along. The Python benchmark computed
the same statistic on `img − median` and got 0.118 and 0.569, so Java and Python
were measuring different quantities and the disagreement never surfaced.

Third time this offset has made a test inert: the valley test divided a raw
ratio on it, and `saddleRatio` was written to subtract it for the same reason.
Both CVs now go through `relativeSpread`, which divides by how far the samples
stand ABOVE the background. Elements 1–3 stay raw, because
`deleteSpotsWithNoSignal` compares them to an absolute ceiling.

**Merged detections are rare here — 0.7%, 20 of 2938.** The benchmark's pair
density (about 12 per frame) predicted a hundred per window. So the split pass
was never going to be the main lever, whatever its gates did.

**A third of detections cover no truth cell at all** (987 of 2938), and they are
small: median radius 5.68 against 11.25 for a real one. That is the larger
problem by two orders of magnitude, and it is a detection problem, not a
splitting one.

## With the reading live, the bar ran away

The core CV was inert until it was taken about the background. The moment it
became a real number, the adaptive bar — `max(0.25, median + 3·MAD)` — stopped
sitting on its floor and started landing at **0.51 to 0.85**.

Measured on the detector's own detections, labelled against CTC truth (3890
rows): a single cell's core reads **0.18** at the median, a detection covering
two reads **0.30** with a p90 of **0.52**. So the bar had risen above the p90 of
the very population it was looking for.

| k | bar | pairs above it | single cells above it |
|---|---|---|---|
| 0.0 | 0.250 | 55.3% | 35.8% |
| **1.0** | **0.294** | **50.0%** | **29.5%** |
| 2.0 | 0.420 | 28.9% | 17.7% |
| **3.0 (shipped)** | **0.548** | **7.9%** | **10.9%** |

**At three MADs a genuine pair clears the bar less often than a single cell.**
The gate was not tight, it was inverted, and nothing downstream of it could
matter. 3.0 had been chosen while the reading was inert, when the bar sat on its
floor at every frame and the coefficient never did anything.

One is where the ratio is best and the most pairs still pass. It is not a good
gate even there — this reading separates the classes at AUC **0.663**, where the
radius manages **0.771** — but it now points the right way.

### What the detector's own output says the features are worth

AUC over 3890 real detections (0.5 = the feature says nothing):

| feature | TWO vs ONE (split) | NONE vs real (false positive) |
|---|---|---|
| radius | **0.771** | 0.113 |
| interior mean | 0.693 | **0.009** |
| interior median | 0.685 | 0.073 |
| core CV | 0.663 | 0.354 |
| full CV | 0.421 | 0.153 |

Two things follow that this pass is not built on. The **radius** is the best
split feature, not the core CV — which is what the reverted "radius carries no
information" change got exactly backwards. And the dominant error is not
splitting at all: **26.9% of detections cover no truth cell**, and the interior
mean separates those at 0.009, near-perfectly.

## The floor under the bar was a constant (measure_floor.py)

`max(0.25, frame median + k·MAD)`. The adaptive half is read off the frame; the
floor was not. A core CV is dimensionless, but how uneven a smooth nucleus reads
depends on the optics and the label, so 0.25 is a fact about one culture. On a
cleaner movie the frame's own bar falls below it and **the constant becomes the
only threshold in the pass** — splitting nothing, with nothing in the log saying
why.

Replaced by the spread measurement noise alone produces on a uniform body,
`background σ / (core level − background)`, which follows the images: it falls
as the signal-to-noise rises, so a cleaner movie is allowed a finer distinction
instead of being held to a number measured on a noisier one.

| | p10 | p50 | p90 |
|---|---|---|---|
| noise-implied floor | 0.027 | 0.037 | 0.047 |
| adaptive bar (k=1) | 0.257 | 0.293 | 0.344 |

| floor | pairs clear | singles clear |
|---|---|---|
| constant 0.25 (removed) | 50.0% | 29.6% |
| noise-implied, per frame | 50.0% | 30.1% |
| no floor at all | 50.0% | 30.1% |

**The change buys nothing here — that is the point.** It removes a number that
could only ever be wrong somewhere else. Two corrections came out of measuring
it, though, and both matter more than the swap:

- The constant was **not** inert on this dataset as first reported. It was the
  operative bar on **2 of 19 frames**, where the frame's own reading fell to
  0.220 and 0.244 and the constant pushed it back up to 0.25.
- The floor has to divide by the **same signal the reading it bounds divides
  by** — the core's own level, profile element 3. Using the whole interior's
  mean instead put the floor at 0.049–0.095, nearly twice too high.

### What the constant was really doing

It was also, by accident, the only thing standing between the pass and mass
splitting. Without a measured background `interiorIntensityProfile` takes every
spread about **zero**, the whole frame reads ≈0.005, and the frame's own
median-plus-MADs bar lands at ≈0.005 too — **about half the detections clear
it**. `measured()` is a sample count and is not implied by the `intensityStd > 0`
guard that was already there. `splitMergedDetections` now refuses such a frame
on purpose.

And the pass is fluorescence-only. `relativeSpread` returns 0 for anything not
brighter than its background, so on phase contrast it already refused everything
— by arithmetic, with nothing in the source or the log saying so. The condition
is now stated, as its sibling passes state theirs.
