# A mitosis is not ordinary motion

## The question

The run reported it plainly, twice:

```
divisions=17 ... The population went 47 -> 168 cells, which takes at least 121 division events
mid-movie starts=178, of which 36 had a mother within 5.8 µm one timepoint earlier
```

178 tracks begin in the middle of the movie — about the number of divisions the growth requires — but
only 36 of them have a mother within reach. Raising `division_link_factor` from 1.5 to 2.5 recovered
**one** division (16 → 17). So the daughters exist, the mothers exist, and something was refusing the
join. The candidate: every pass that attaches a daughter is bounded by the per-frame displacement
cap, and that cap is measured as `4 x median consecutive-link length` — the ordinary motion of a cell
that is **not** dividing.

## Method

`measure.py`, on the CTC tracking ground truth for `Fluo-N2DL-HeLa`. For every track with a parent,
the distance from the parent's last centroid to the daughter's first, against the distribution of
ordinary consecutive-frame displacements of the same sequence. Centroids from `ctc_centroids.py`.

## Result

| | ordinary step | | | mother → daughter step | | |
|---|---|---|---|---|---|---|
| | median | p95 | p99 | median | p95 | p99 |
| seq 01 (8374 / 188) | 1.49 | 5.77 | 9.05 | **10.64** | 17.11 | 20.79 |
| seq 02 (24746 / 422) | 0.65 | 5.81 | 9.68 | **9.76** | 16.88 | 19.14 |

All in µm. The division step is **7x to 15x** the ordinary median.

What the cap does to real joins:

| cap factor | seq 01 cap / refused | seq 02 cap / refused |
|---|---|---|
| **4 (what the run used)** | 5.98 µm / **87.2%** | 2.58 µm / **98.3%** |
| 6 | 8.97 µm / 62.2% | 3.87 µm / 95.3% |
| 8 | 11.96 µm / 33.5% | 5.16 µm / 89.1% |
| 10 | 14.94 µm / 11.2% | 6.45 µm / 81.0% |
| 12 | 17.93 µm / 4.8% | 7.74 µm / 70.9% |

## What it decided

- **The ordinary per-frame cap must not bound a division join.** It was refusing 87% / 98% of the
  real ones. That is the missing divisions, and it is why raising `division_link_factor` did almost
  nothing: the radius gate was never the binding constraint.
- **Raising the factor cannot fix it.** The ordinary median differs by 2.3x between the two sequences
  while the division step barely differs at all, so no multiple of ordinary motion transfers: at
  factor 12 seq 02 still refuses 71%, and by then seq 01's cap is 18 µm, which is no bound at all.
- **The radius-scaled bound is the right basis.** The division step is about one nuclear diameter —
  p99 of 20.8 / 19.1 µm against a spot radius near 9.5 µm, just over two radii. That is exactly what
  `division_link_factor` already expresses, and `linkDivisionDaughters` was already using it alone.
  `linkOrphanStarts` was the pass applying both.

## The per-frame cap cut 79% of real divisions

Reported from the viewer: a daughter cell after a division is detected but not
tracked, "multiple times". CTC tracking truth (seq 01, `man_track.txt`), 94 real
divisions and 8374 ordinary steps:

| (micrometres) | p50 | p90 | p99 | max |
|---|---|---|---|---|
| ordinary step | 1.5 | 4.5 | 9.1 | 22.6 |
| **division step** | **10.6** | 15.4 | 20.7 | 22.6 |

The run learns its per-frame displacement cap as about **4x the median ordinary
step** — 7.3 µm in the observed run. **79.3% of real division branches are
longer than that.**

`pruneLongLinks` DELETES links above the cap, so the linker could make the
mother-daughter join and this cut it again. That is exactly what a daughter that
is detected but not tracked looks like.

`orphanJoinRefusal` already exempted divisions (`!wouldDivide`). This was the
second of two sites and did not — the same one-of-N shape as the cell-count
floor, the merge switch and the refinement guards.

| cap for a division branch | real divisions cut | ordinary steps it makes reachable |
|---|---|---|
| x1.0 = 7.3 µm | **79.3%** | 0.00% |
| x2.0 = 14.6 | 15.4% | 2.01% |
| x2.5 = 18.2 | 4.3% | 2.11% |
| **x3.0 = 21.9** | **0.5%** | 2.14% |

Three is where it stops mattering: 2.5 to 3.0 buys 3.8 points of real divisions
for 0.03 of exposure. It is also the value `division_link_factor` already
carries — the parameter meant to cover this, which never reached this pass.

Nothing else is loosened: the test reads the SOURCE spot's outgoing degree, so a
link out of a non-dividing spot is judged exactly as before.
