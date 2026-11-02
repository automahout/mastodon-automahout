# Over-merging: what was and was not the cause

The first screenshot after the seed-to-object arbiter landed showed one ellipse
swallowing three or four nuclei, tilted diagonally, plus thin vertical ellipses
on the duplicate pairs.

## Two hypotheses, both wrong

**"Transitive grouping runs away once the arbiter works."** Union-find groups the
merged pairs, and the arbiter went from 9.0% to 85.5% recall, so chains should
now form. Measured (`measure_grouping.py`): requiring every pair in a group to
agree — a clique instead of a component — gives **byte-identical results**.
Groups never exceed three members either way.

**"A merged spot grows across passes until it swallows a neighbourhood."** The
pairing gate is `distance < 2.2 × max(radius)` and a merge produces a semi-major
axis of `d/2 + max(r)`, so a merge at the edge of the gate yields 2.1 r and its
reach next pass would be 4.6 r. Measured (`measure_cascade.py`) over four rounds:
rounds 2–4 are identical to round 1. The watershed refuses the next merge, so it
converges immediately.

## The actual cause

The size cap read the **largest diagonal entry** of the merged covariance, not
the largest semi-axis. Those are equal only when the ellipsoid is axis-aligned.
For a pair of r = 6 spots separated by 2.1 r:

| angle of the join | cap sees | true semi-axis | cap too permissive by |
|---|---|---|---|
| 0° | 12.30 | 12.30 | 0% |
| 15° | 11.98 | 12.30 | 3% |
| 30° | 11.07 | 12.30 | 11% |
| **45°** | **9.68** | **12.30** | **27%** |
| 60° | 11.07 | 12.30 | 11% |
| 90° | 12.30 | 12.30 | 0% |

Which is exactly what the screenshot shows: the diagonal merge is the giant one,
the vertical pairs are sized correctly. Fixed by taking the largest eigenvalue.

## The grouping limit, chosen by measurement

With the cap correct, the remaining lever is group size. Measured on 3202
detections over 2876 truth cells, 326 of them genuine duplicates:

| rule | duplicates removed | groups swallowing >1 real cell | max radius |
|---|---|---|---|
| ≤3 members, cap 3× (as shipped) | 89.9% | 13.2% | 31.7 px |
| **≤2 members** | **81.9%** | **9.0%** | 31.7 px |
| ≤2 members, cap 1.8× | 80.7% | 7.6% | 31.7 px |
| ≤3, absolute cap 25 px | 81.6% | 9.9% | 25.0 px |
| ≤3, absolute cap 20 px | 47.9% | 7.8% | 20.0 px |
| ≤3, cap 1.6× members | 77.0% | 6.4% | 31.7 px |

`MERGE_MAX_GROUP = 2` costs eight points of duplicate removal for a third of the
over-merging. A hard absolute cap is worse at every matched point — 20 px halves
duplicate removal.

## A third hypothesis that also failed

Capping the merged spot against the **cell radius measured from the image at the
merged centre** looked principled and measured *worse* than every alternative
(82.8% removed / 14.0% over-merge at 1.6×). At a merged centre, which sits
between two cells, the ray estimator reads the fused region, so it bounds nothing.

## A fourth hypothesis, also wrong: the pairs are not being proposed

`overlapsAsFragments` gates candidates at `distance < 2.2 × max(DETECTION
radius)`. The 2.2 was measured in **cell** radii, and the run log reports
detections at 6.6–7.0 µm against a true cell median of 8.3 µm — about 0.82×. So
a pair tiling one cell at 1.82 cell radii reads as 2.2 *detection* radii, exactly
at the gate, and anything wider should never reach the arbiter at all.

Measured with radii shrunk to detection scale (`measure_reach.py`):

| reach (× detection radius) | duplicates removed | groups swallowing >1 cell |
|---|---|---|
| 2.20 | 81.0% | 8.0% |
| 2.50 | 81.9% | 9.0% |
| 3.00 | 82.2% | 9.3% |
| 3.50 | 82.2% | 9.3% |

Widening the reach by 60% buys 1.2 points. The gate is not what limits the
merge, so the remaining duplicates are not being missed for want of being
proposed. Four hypotheses have now been measured and refuted for this symptom
(transitive grouping, cross-pass cascade, image-measured size cap, proposal
reach); the one that was true was the covariance-diagonal cap, and it only
explained the oversized merges, not the un-merged pairs.

The constructed SAME population here — the two brightest local maxima inside one
truth cell — removes 81% under the shipped rules, so it does not reproduce what
the reported frames show. Diagnosing the rest needs the detector's own positions,
not a population built from the truth.
