# Deleting incomplete tracks at the edge of the field of view

## The question

From the user, looking at the lineage view: the fragments are mostly at the edges of the image, and
such incomplete tracks should be deleted. That is the right call biologically — an interior fragment
is a linking failure worth repairing, a fragment at the image edge is a cell walking in or out of
frame and no repair will complete it — but it is a deletion rule, so it needs a cost.

The unit is a weakly-connected component of the link graph: a whole lineage tree, not one cell cycle.
With correct linking a lineage tree spans essentially the whole movie, so the safety question is
**what does this delete when the tracking is right?**

## Method

`measure.py`, on the CTC tracking ground truth for `Fluo-N2DL-HeLa` (1100 × 700 px at 0.645 µm,
92 frames). Lineage trees are rebuilt from `man_track.txt` by following `parent` to a founder; extents
come from the TRA label images. A lineage is border-touching when any of its objects' pixels come
within the margin of column 0 / W-1 or row 0 / H-1. Reported as the share of **all ground-truth
objects** a rule "delete border-touching lineages spanning fewer than N timepoints" would remove.

`01` has 77 lineages over 8639 objects; `02` has 252 over 25420.

## Result

Border-touching lineages, by margin:

| margin | seq 01 | seq 02 |
|---|---|---|
| 0 px (0.0 µm) | 2 of 77 (0.6% of objects) | 1 of 252 (0.0%) |
| 8 px (5.2 µm) | 4 of 77 (0.9%) | 8 of 252 (0.7%) |
| **16 px (10.3 µm)** | 16 of 77 (13.1%) | 30 of 252 (3.6%) |
| 31 px (20.0 µm) | 35 of 77 (25.8%) | 123 of 252 (23.3%) |

Deleted, at a margin of one nucleus radius (16 px / 10.3 µm), as a share of all objects:

| min tp | seq 01 | seq 02 |
|---|---|---|
| 3 | 0.06% | 0.02% |
| 5 | 0.06% | 0.06% |
| 8 | 0.12% | 0.13% |
| **10** | **0.12%** | **0.26%** |
| 15 | 0.58% | 0.52% |
| 20 | 0.58% | 0.65% |
| 30 | 0.91% | 0.84% |

## What it decided

- **`BORDER_FRAGMENT_MIN_TP = 10`**, the adaptive-track window: a lineage at the edge that does not
  survive one window of tracking never established itself. It costs a quarter of a percent of correct
  data to say so.
- **The margin is the spot's own radius**, not a fixed number — the cell's ellipsoid reaching an
  image face. That is the 16 px row for these nuclei.
- Note the gap between "touches the border" (3.6–13.1% of objects) and "deleted" (0.26%). Almost every
  border-touching lineage in ground truth is a long one: being near the edge is common, being near
  the edge *and* short is not. The rule separates the two, which is the whole point — a cell that
  entered the frame and stayed is not a fragment.
- The z face is excluded on single-plane data. On one plane every spot sits at z=0, one voxel from
  both z faces, so counting it would make every lineage in the movie a border lineage. See
  `ImageBounds`.
