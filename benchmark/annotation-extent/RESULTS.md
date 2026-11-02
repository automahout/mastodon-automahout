# What shape the pipeline teaches as "cell"

Fluo-N2DL-HeLa_01, 2350 detections sitting on a CTC silver-truth cell, radii
taken from the detector's own dump AFTER `resizeToMeasuredExtent`, i.e. what
training actually receives. `measure_annotation_shape.py`.

| annotation shape | precision | recall | IoU |
|---|---|---|---|
| **as-is** | 0.58 | **1.00** | **0.56** |
| right-size circle | 0.88 | 0.88 | 0.79 |
| **ellipse @ detection centre** | 0.95 | 0.96 | **0.92** |
| ellipse @ cell centre | 0.96 | 0.97 | 0.94 |

**Recall is 1.00.** The annotation always contains the whole cell and then some:
the radius is **1.31x** the cell's equivalent radius at the median (p10 1.09,
p90 1.59), so the area is 1.72x and **42% of what is taught as "cell" is not
one**. Between two touching cells that margin necessarily reaches into the neck,
which is where the dark structure is.

**The shape matters more than the position.** An ellipse left at the detector's
own centre scores 0.92 against 0.94 perfectly centred. Cells here are elongated
1.40x at the median (p90 1.78), which a circle cannot follow at any radius —
fixing only the size caps at 0.79.

The bottom two rows are **upper bounds**: they use the truth cell's own axes,
which a fit from the image cannot know. A real fit scores below them.

## A discrepancy worth chasing

`resizeToMeasuredExtent` claims "within 25% for 82.5% of 2117 CTC silver-truth
cells, 2.2% at the population level". Production gives a median of 1.31x and a
p90 of 1.59x. The benchmark and the pipeline are not measuring the same thing.

## Can mastodon-ellipsoid-fitting do it? Measured, not reasoned

`org.mastodon:mastodon-ellipsoid-fitting:0.7.2` resolves from the SciJava
repository and its core is public and static. `EllipsoidFittingViabilityTest`
runs its pipeline, assembled from its public parts, with a 3-D control:

| input | edgels | result |
|---|---|---|
| 32 planes (control) | 1984 | semi-axes 13.48 / 11.67 / 9.75 for a 14 / 10 / 12 blob |
| 5 planes | 80 | `NoEllipsoidFoundException` |
| **1 plane (this dataset)** | **0** | `NoEllipsoidFoundException` |
| genuinely 2-D image | **80**, each 2-D | `Edgels.filterEdgelsByDirection` throws |

Reading the source predicted failure and predicted the **wrong reason**: the
nine degree-of-freedom solver never runs, because at depth 1 the edgel detector
returns nothing at all. But imglib2's `SubpixelEdgelDetection` is
dimension-generic and works on a genuinely 2-D image — it is the 3-D image of
depth ONE that yields nothing, and it is the library's own helpers that are
hardcoded to three dimensions.

So a 2-D path needs its own direction/occlusion filtering and a 5 degree-of-
freedom RANSAC, and can reuse imglib2's edge detection. The library stays useful
for volumetric datasets — with the caveat that five planes is already too thin.

## What the shipped fitter actually achieves

The 0.92 above is an **upper bound**: it uses the truth cell's own axes, which a
fit from the image cannot know. `export_windows.py` → `ContourFitHarness` →
`score_fits.py` runs the **shipped Java** (`EllipseFit`) over 600 real windows,
sampled exactly as `AutoCorrectOrchestrator.fitEllipseToContour` samples them,
and scores it against CTC silver truth. Measuring across the two languages keeps
the Java under measurement — a Python reimplementation would measure the
reimplementation.

| shape | IoU p10 | p50 | p90 | n |
|---|---|---|---|---|
| circle, after the extent anchor fix | 0.58 | 0.74 | 0.81 | 346 |
| **ellipse, fitted from the image** | **0.75** | **0.81** | **0.87** | 345 |

So the whole chain on this dataset is **0.56 → 0.73 → 0.81**: the extent anchor
fix, then the contour fit. Not the 0.92 ceiling, and the gap is exactly the
information the ceiling had and the fitter does not.

The p10 moves furthest, 0.58 → 0.75. The worst annotations improve most, which
is what matters for a training set — a detection that was half background is
where the damage is.

### Refusing is most of the value

| | fitted | refused |
|---|---|---|
| detections covering a truth cell | 346 | 70 (17%) |
| detections covering **no** cell | 12 | **172 (93%)** |

A detection on background has no contour to fit, and the fitter says so. That it
declines **93%** of them while accepting 83% of the real ones makes this nearly a
false-positive test in its own right — measured after the fact, not designed for,
and not yet used for anything. The 17% it refuses on real cells keep the circle
they had, which is the honest fallback.

## Teaching only what the contour supports

The refusals turned out to be the most useful thing the fit produces. Of the same
600 windows, labelled against CTC silver truth:

| taught set | on a cell | on nothing | false share |
|---|---|---|---|
| every detection | 416 | 184 | **30.7%** |
| only where the contour fitted | 346 | 12 | **3.4%** |

**83.2% of the real cells are still taught and 93.5% of the false labels are
gone** — the share of training labels sitting on nothing falls ninefold.

This is what a person does. They do not annotate every detection; they keep the
ones they can see are cells and leave the rest alone.

Withheld (`unlabeled`), never tagged `fp`. An outline hidden by a neighbour is
not evidence of background — that is 17% of the detections on real cells — and
`fp` carries ten times the weight of a positive on the wire.

The gate stands down below `CONTOUR_SUPPORT_MIN_FRACTION` (50%) of a window's
detections carrying an outline. Without that floor it is a cliff: on phase
contrast the fit refuses everything and the gate would empty the training set in
silence.

### What the contour gate does NOT do: duplicates

Both detections on a doubly-detected cell fit that cell's outline, so the gate
cannot see them. Measured on the same windows, as a share of the taught set:

| taught set | extra detections on already-detected cells |
|---|---|
| every detection | 69.2% |
| only where the contour fitted | **68.8%** |

Essentially unchanged. The 30.7% → 3.4% win above is entirely about detections
sitting on **nothing**; a duplicate counts as "on a cell" on both sides of that
comparison, so it never entered it.

**These percentages are from the OLD dump**, when the detector ran 5.4x over the
truth count. The detector now produces about 1.05x, so the rate today is far
lower — what carries over is the structural conclusion, not the number.

What the pipeline does about duplicates: `withholdCoLocatedFromTraining` finds
pairs that recur sub-cell-spaced across 5+ frames and withholds them from
training, which is the important half. It leaves them in the graph for linking,
and merging is opt-in and off by default, so **Phase 1 output will always show
them**. Any judgement of duplicates from a Phase 1 screenshot is judging a state
the pipeline does not claim is final.

## Do fitted ellipses tell a duplicate from a neighbour?

Merging was switched off on 2026-09-26 on the user's instruction, because it drew
one ellipse around two resolved nuclei and the dark gap between them. The reason
it could not tell those cases apart was the geometry it had: circles 1.31x too
big, where "the bounding spheres touch" is true of two real neighbours as well.

Re-measured with the fitted ellipses, over every overlapping pair in the same
frame, labelled by whether the two sit on the SAME CTC truth cell:

| overlapping pair | IoU p10 | p50 | p90 |
|---|---|---|---|
| same cell (444) | 0.87 | **0.98** | 1.00 |
| different cells (29) | 0.00 | **0.02** | 0.53 |

| merge when IoU > | same-cell pairs merged | different-cell pairs wrongly merged |
|---|---|---|
| 0.15 | 100.0% | 13.8% |
| **0.50** | **100.0%** | **10.3%** |
| 0.65 | 99.5% | 10.3% |

Median 0.98 against 0.02 — nearly binary, where before it was a guess.

**Two caveats.** Only 29 different-cell overlapping pairs, so the 10.3% is three
of them and its error bar is wide. And this is the OLD dump, where duplicates
were rampant (444 vs 29); the rate today is much lower, though the separability
is a property of the feature rather than of the prevalence.

**Not acted on.** Turning merging back on reverses a decision the user made for
a reason that was sound at the time. It needs their call, not mine.

## One label per cell

Not a merge. The duplicates are made by the training set: ELEPHANT learns
CENTRES — its loss has a Center Dice term — so two overlapping labels on one
nucleus teach it that nucleus has two, and every round compounds it. A person
annotating puts one label on one cell.

Withholding the second label, keeping whichever the contour supports better,
greedy from the best downward:

| taught set | cells | with more than one label | extra labels |
|---|---|---|---|
| contour-supported | 108 | 100 (92.6%) | 238 (68.8% of the set) |
| **one label per cell** | **107** | **0 (0.0%)** | **0** |

Every duplicate label gone; **one** cell lost entirely, out of 108.

**Nothing is merged.** Both spots stay in the graph and both stay linked; only
the training label is withheld. That is why a rule with a 10% false rate is
usable here and was not usable for merging: a wrong merge destroys a cell the
detector found, a wrong withhold costs one round's teaching of it.

As above, the population is the old dump where duplicates were rampant. Today's
rate is far lower, so the number withheld will be much smaller — the property
that matters is that no cell reaches training with two centres.
