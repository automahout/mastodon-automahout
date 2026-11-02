# Where the synthesized fp negatives actually land

## The procedure being measured

`AutoCorrectOrchestrator.buildSyntheticFpCandidates` places a training negative
by offsetting away from a *detected* cell:

* distance `max(3 × the DETECTION's radius, 5 µm)`, escalating to 2× over 8
  attempts;
* direction uniform in 3D;
* rejected if it collides with another spot **in the graph**.

Two things follow that the code cannot see. The distance is a multiple of the
detection's radius, not the cell's. And the collision check only knows about
detections — an *undetected* cell is invisible to it. In the reported frame,
27 spots cover ~43 cells.

`measure_offset.py` replays this exactly against the CTC silver truth (92
frames, 8602 cells, radius p05/p50/p95 = 9.1/12.9/15.5 px) and scores how often
the negative lands inside a real cell, i.e. teaches ELEPHANT that cell pixels
are background.

## Result

Share of negatives landing on a real cell:

| detection / cell size | all cells detected | 63% detected (the reported frame) |
|---|---|---|
| 0.4× | 8.7% | 9.9% |
| 0.5× | 0.7% | 3.0% |
| 0.6× | 0.2% | 3.4% |
| 0.8× | 0.0% | 6.1% |
| 1.0× | 0.0% | 7.1% |

**This corrected the hypothesis it was written to test.** "Detections are
smaller than the cells, so the offset lands inside the cell" is only true at the
extreme (0.4×, ~9%). The dominant term is the **undetected** cells: at full
detection the procedure is essentially clean (0.0–0.7% above 0.5×), and every
error at 63% detection comes from offsetting into a cell the graph does not know
about.

The relationship even inverts. With full detection, a *larger* detection is
safer — the offset is pushed further out and the collision check is more
conservative. With 63% detection, a larger detection is *worse* (7.1% vs 3.0%),
because the longer offset reaches into undetected neighbours. So enlarging the
annotations, on its own, would make this worse rather than better.

## The z axis on a 2D movie

The direction is sampled uniformly in 3D. On a movie with one z plane, the
fraction of the offset spent on z is simply lost, which shortens the in-plane
distance and puts the negative back near the cell it came from:

| detection / cell | offset kept in plane | z sampled, as the code did |
|---|---|---|
| 0.5× | 3.0% | **8.4%** |
| 1.0× | 7.1% | 7.4% |

Nearly three times the error rate at 0.5×. Three other random-position sites in
the same class already guard the z axis (`span[2] > 1e-9 ? … : 0.0`); this one
picks a *direction* rather than a position and did not. Fixed.

## What this does not explain

The negatives in the reported screenshot all hug the cells — every magenta ring
sits within ~30 px of a cyan one, while the large flat background areas, which
is where the detector actually misfires, carry none. That is by construction:
the near-cell pool only ever offsets *from* an existing cell, and the
supplementary pool is restricted to the image-border margin. It is a placement
question, not a correctness one, and is not what this benchmark measures.
