# Closing a track gap: how far is it safe to link?

## The question

Nothing in this pipeline could join a track across a gap. Every linking path works between adjacent
timepoints — ELEPHANT's own linker goes `t -> t-1`, so do `linkOrphanStarts` and
`reconnectOrphanTracklets`, and `fillGaps` bridges exactly one frame by asking the detector to look
again — and `pruneLongLinks` then deleted any edge spanning more than one timepoint outright,
whatever had created it:

```java
if ( gap > 1 )
{
    // Gap link: spans ≥2 timepoints → always prune
```

So a cell the detector missed for two consecutive frames did not have a gap in its track. It had two
lineages, permanently. Reported from the viewer as the same nucleus ending a track at t=44 and
starting a new one at t=47.

Before allowing such a link, how often is the nearest object `g` frames later actually the same cell?

## Method

`measure.py`, on the CTC tracking ground truth for `Fluo-N2DL-HeLa` (1100 × 700 px at 0.645 µm,
92 frames, two sequences). For every ground-truth track and every `t` where it exists at both `t` and
`t + g`, the cell is treated as missing in between and the nearest object at `t + g` by centroid is
compared with the truth.

Two candidate pools. **All** is every object at `t + g`. **Orphans only** restricts it to objects that
are themselves orphaned there — no object of their own track at `t + g - 1` — which is the population
`closeTrackGaps` actually chooses among, since it only considers track starts with no incoming edge.

Centroids come from the TRA label images (`ctc_centroids.py`, reproduced in the header of
`measure.py`'s usage line); run it first to produce `/tmp/ctc_centroids.pkl`.

## Result

| gap | n (01 / 02) | nearest is right (all) | nearest is right (orphans only) | displacement p50 | displacement p99 |
|---|---|---|---|---|---|
| 1 | 8374 / 24746 | 99.7% / 99.6% | **100.0% / 100.0%** | 1.49 / 0.65 µm | 9.05 / 9.68 µm |
| 2 | 8123 / 24088 | 99.5% / 99.0% | **99.9% / 100.0%** | 2.24 / 1.82 µm | 11.63 / 13.36 µm |
| 3 | 7877 / 23439 | 99.1% / 98.2% | **99.9% / 99.9%** | 2.66 / 2.58 µm | 13.79 / 16.52 µm |
| 4 | 7638 / 22800 | 98.7% / 97.3% | **99.9% / 99.9%** | 3.23 / 3.23 µm | 16.12 / 19.36 µm |

## What it decided

- **Gap closing is safe at all four gaps**, and markedly safer in the pool this pass actually uses.
  Restricting to orphans removes almost every error the unrestricted nearest-neighbour makes: a
  mis-assignment is nearly always to a cell that is *not* orphaned, i.e. one whose own track already
  continues.
- **There is no cliff**, so `MAX_LINK_GAP` is a policy choice, not a threshold. It is set to **3**:
  the reported case (t=44 to t=47) is a gap of three, and 3 keeps the search radius inside twice the
  per-frame cap's own p99.
- **`g × maxDisplacement` is a valid bound.** The measured p99 at gap 3 is 13.8 / 16.5 µm against a
  bound of 3 × 7.0 = 21 µm — generous, but the right shape, and the radius-scaled bound scales with
  the gap for the same reason.

The 99.9% figure is not a claim about this pipeline's accuracy. It says that when a gap exists and
both its ends are known, choosing the nearest orphan is essentially always right — so the pass is
bounded by how well the gap's two ends are found, not by the choice between candidates.
