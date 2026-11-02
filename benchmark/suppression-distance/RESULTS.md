# Suppression distance, and where same-cell double spots come from

Fluo-N2DL-HeLa 01, CTC silver truth (`01_ST/SEG`), 2026-10-01.

The question started as "ELEPHANT avoids double detections with a suppression
distance; this project never applied one — what should it be?" It ended as a
different answer: the double spots the user photographed did not come from
predict at all.

## ELEPHANT's rule

`PredictSpotsAction` (elephant 0.8.0) asks the timepoint's `SpatialIndex` for
the nearest spot already added and drops the candidate when
`getDistance() < ElephantMainSettings.getSuppressionDistance()` (default
`3.0`, world units). Client-side, not a `/seg/predict` key.
`ElephantIntegration.addSpotFromJson` has no such check.

## 1. How close real cells come (model-free)

`measure_suppression.py`, every silver-truth frame, 8602 cells. Distance from
each cell centroid to the nearest other one:

| | min | p0.1 | p1 | p50 |
|---|---|---|---|---|
| µm | **4.9** | 8.1 | 10.6 | 22.0 |

| suppression distance | real cells with a closer neighbour |
|---|---|
| 3 µm | 0.00% |
| 5 µm | 0.02% |
| 7 µm | 0.05% |
| 10 µm | 0.59% |

## 2. The rule replayed on the detector's own output

Same script, on the split dump of the current build (740 snapshots, 71282
detections). A pair is a duplicate when both centres fall in one truth cell.
Pairs are formed only within one snapshot — the first version of this script
paired a detection with itself from another pass and reported "duplicates
0.1 µm apart, AUC 1.000". That number was wrong.

- 92.0% of detections land on a cell.
- **47 surplus detections (0.07%)**, centres 3.2 / 8.9 / 13.1 µm apart (p10/p50/p90).

| suppression distance | surplus taken | real cells lost |
|---|---|---|
| 3 µm | 4/47 | 0 |
| 5 µm | 6/47 | 0 |
| 7 µm | 8/47 | 6 (0.01%) |
| 10 µm | 29/47 | 182 (0.28%) |

Predict barely produces double detections, and the ones it does are too far
apart for ELEPHANT's default to take.

## 3. Where the photographed ones came from

`SpotOrigin` records, at the `GraphEdits` chokepoint every addition passes
through, the `Reason` and the two calling methods. `SpotOriginDump` writes the
spots of the window being checked at every `PipelineInvariants` checkpoint.
`measure_origin_pairs.py` reports the step at which each same-cell pair first
appears.

Window 1 (t=0..9):

| first appears | origin of the pair | pairs | distance p50 |
|---|---|---|---|
| [3/6] commit detection | SPLIT + SPLIT | **11** | 6.5 µm |
| [3/6] commit detection | DETECTION + SPLIT | 1 | 1.8 µm |
| [3/6] commit detection | DETECTION + DETECTION | 1 | 8.0 µm |

Of the 30 spots `splitMergedDetections` made, 22 sat two-to-a-cell on a single
nucleus (11 wrong splits), 4 were alone on their cell, 4 on no cell — at most 2
splits that could have been right. The split pass is now opt-in
(`CleanupToggles.SHARED_SPLIT_MERGED_OVERRIDE`).

## Running it

```
AUTOMAHOUT_ORIGIN_DUMP=/tmp/origin-dump.tsv     # or -Dautomahout.originDump=
AUTOMAHOUT_SPLIT_DUMP=/tmp/split-fixture.tsv    # or -Dautomahout.splitDump=
```

Both paths are name patterns: each launch writes `name-<yyyyMMdd-HHmmss>.tsv`,
with the same stamp on both (`DumpFiles`). With no argument the scripts read the
newest file in `/tmp`.

Two traps found on the way. TestDrive runs on JDK 11, whose
`Files.createDirectories("/tmp")` throws because macOS's `/tmp` is a symlink —
the first run with the origin dump wrote nothing; the suite (JDK 21) could not
see it. And a registry keyed weakly by `ModelGraph` must hold its value weakly
too: the value reaches the graph through the vertex pool, and a strong one ran
the test suite out of heap.
