# Lineage review

Fluo-N2DL-HeLa 01, tracking truth `01_GT/TRA` and silver truth `01_ST/SEG`.
Runs: `20261002-015311` (whole movie, final `auto.correct` snapshot) and
`20261004-095107` (t=0..19, `[5c/6]` snapshot), split pass off in both.

## Two tracks into one detection

Up to frame t two neighbouring cells carry two spots and two tracks; at t+1
ELEPHANT itself returns ONE large spot over both (origin `DETECTION`, not a merge
pass: in `20261004` t=15..17, r=17 against ~9 for its neighbours). A spot takes
one incoming link, so one track runs on into it and the other ends; when the
cells part, the survivor "divides".

### Finding the onset (`measure_convergence.py`)

S at t+1 has a predecessor P; another spot Q at t has no successor and its
centre lies inside S's ellipse.

| run | flagged | on 2+ truth cells | merge episodes | caught at onset |
|---|---|---|---|---|
| 015311 | 24 | 22 (92%) | 46 | 21 |
| 095107 | 5 | 3 | 4 | 3 |

Of the 25 episodes missed in 015311: 9 already had a second detection inside S
(Q went on into it), 9 never had the second cell detected before the merge, 4
had Q's track continue elsewhere, 3 had Q's centre just outside S. Widening the
ellipse (1.25x, 1.5x) lowered precision to 83% and 74%.

### Repairing it (`evaluate_convergence_repair.py`)

S is split by the two-component fit of `ShapeSplit`, started at P and Q
carried by S's own motion, and the split is carried along S's successors while
the single successor still holds both pieces and no other detection appears on
either. A frame is right when its two pieces sit on two different silver-truth
cells.

Seeded and unseeded placement score the same (19 vs 20 right of 24 onsets);
the seeds matter for IDENTITY: piece k is the continuation of the cell it grew
from, so P and Q each keep their own track.

Carrying forward needs a test that the merge is still two cells. The distance
between the two fitted centres, in the frame's median radius: one-cell fits at
0.81-0.92 (p10-p90), two-cell fits at 0.76-1.67 (median 1.23).

| min separation | frames split | right | one cell cut in two | piece on background |
|---|---|---|---|---|
| 0.6 (split pass) | 71 | 48 | 21 | 2 |
| **0.9** | **42** | **39 (93%)** | **1** | **2** |
| 1.0 | 34 | 32 | 0 | 2 |

`20261004`, every setting from 0.8 up: 10 of 10 right.

In Java: `AutoCorrectOrchestrator.repairConvergedTracks`, first step of
`runTopologyConsistency` (so per window at [5c/6] and in Phase 2), before any
pass judges divisions or branches. Fluorescence and 2D only.

### From where the merge ends (`measure_merge_ends.py`, `evaluate_end_repair.py`)

9 of the 25 missed episodes never had the second cell detected before the
merge, so nothing ENDS next to it. When the cells part, the detector sees both
again. Two rules on that side: END-START (one successor, and a new track starts
inside S) and END-DIVIDE (S's two successors both inside it, which is also
what a real division looks like).

By the lineage alone (S on 2+ TRA cells):

| run | ONSET | END-START | END-DIVIDE |
|---|---|---|---|
| 015311 | 22/24 (92%) | 11/29 (38%) | 8/18 (44%) |
| 095107 (whole run) | 15/20 (75%) | 10/27 (37%) | 7/13 (54%) |

Of 47 real episodes in 015311, the end rules add 4 the onset rule misses
(7 of 33 in 095107).

After the seeded fit at 0.9 radii, scored by outcome (right / splits made):

| run | ONSET | END-START | END-DIVIDE |
|---|---|---|---|
| 015311 | 17/20 (85%) | 10/13 (77%) | 4/10 (40%) |
| 095107 | 13/15 (87%) | 11/16 (69%) | 6/10 (60%) |

The end side is not good enough to act on alone, so it is ASKED: the user is
shown the frames t-1, t, t+1 with the detection kept (A) and split (B). On B
the split is carried backward to where the merge began. With no UI, or after
"stop asking", the end side changes nothing.

Correction: `evaluate_convergence_repair.py` was committed with
`MIN_SEP_FRAME_R = 1.1`, left over from the sweep above; the Java pass and the
table above use 0.9. Restored.

### Guards on the split, and a third answer (`evaluate_split_guards.py`)

The first question shown in a GUI run (20261004-110215, t=10) was a real
division: both cells at t=9 divide by t=11, and the upper one's daughters lie
inside its ellipse (END-DIVIDE). B's second piece reached into the dividing
neighbour, which HAD a detection (r=10) -- the dialog did not draw it, so it
read as undetected. Fixed: the middle frame now shows every detection.

Right/wrong of the splits the fit makes, and what each guard keeps:

| run | rule | made | IN-S | ON-OBJ | NEIGHBOUR |
|---|---|---|---|---|---|
| 015311 | ONSET | 17/3 | 16/3 | 17/3 | 14/1 |
| 015311 | END-START | 10/3 | 10/2 | 10/3 | 10/2 |
| 015311 | END-DIVIDE | 4/6 | 4/6 | 4/6 | 4/4 |
| 095107 | ONSET | 13/2 | 13/1 | 13/2 | 13/1 |
| 095107 | END-START | 11/5 | 10/5 | 11/5 | 11/4 |
| 095107 | END-DIVIDE | 6/4 | 6/3 | 6/4 | 6/3 |

IN-S: both pieces inside the merged detection. ON-OBJ: both on the thresholded
object. NEIGHBOUR: neither inside another detection's own radius (the split
pass's condition 3). NEIGHBOUR is adopted: 8 of 23 wrong for 3 of 61 right, and
on the end side (asked) 5 of 18 wrong for none of the right.

A third answer, "one cell here plus an undetected neighbour", was NOT built: a
seed lies outside S near an undetected silver-truth cell 2-4 times per run, and
placing a detection on the object at that seed lands on it 0-2 times.

### A detection suddenly much larger (`measure_size_jump.py`)

The lineage rules reach 26 of 47 real merge episodes in 015311 and 12 of 35 in
113539. The rest leave no trace in the tracks: the second cell already has a
detection inside S, or was never detected. The user's observation: such a
detection is clearly bigger than its predecessor.

| feature (merged vs single) | AUC 015311 | AUC 113539 |
|---|---|---|
| growth: area over the predecessor's | 0.619 | 0.635 |
| size: area over the frame's median | 0.912 | 0.905 |

Growth alone is weak because a merge that lasts has a merged predecessor; size
alone flags 100+ detections per run at a usable precision. Both together, with
each bar the run's own median + K MAD, counted per QUESTION (a chain of flagged
spots is asked once), and only where no lineage rule already applies:

| run | K growth / size | questions | on a merge | missed merges now reached |
|---|---|---|---|---|
| 015311 | 4 / 1 | 38 | 20 (53%) | 11 of 21 |
| 113539 | 4 / 1 | 32 | 12 (38%) | 9 of 23 |

Adopted as a third reason to ASK (never acted on alone). B is the unseeded
two-ellipse fit along the object's main axis; the piece nearer the predecessor
continues its track.

### Correction: duplicates were scored as right

The outcome above calls a split right when its two pieces sit on two different
truth cells. A piece on a cell ANOTHER detection already covers is a
duplicate, and that scoring counted it as right. Re-scored
(`evaluate_split_guards.py`, section 1c), splits by outcome, all / those whose
piece lands inside another detection:

| run | rule | right | duplicate | wrong |
|---|---|---|---|---|
| 015311 | ONSET | 15/1 | 2/2 | 3/2 |
| 015311 | END-START | 10/0 | 0/0 | 3/1 |
| 015311 | END-DIVIDE | 4/0 | 0/0 | 6/2 |
| 095107 | ONSET | 13/0 | 0/0 | 2/1 |
| 095107 | END-START | 11/0 | 0/0 | 5/1 |
| 095107 | END-DIVIDE | 6/0 | 0/0 | 4/1 |
| 113539* | ONSET | 3/1 | 7/7 | 5/4 |
| 113539* | END-START | 2/0 | 4/4 | 3/2 |
| 113539* | END-DIVIDE | 0/0 | 1/1 | 3/0 |

\* 113539's final snapshot is after the repair ran, so what is left there is
what it refused or the user kept.

Of the splits landing inside another detection, 2 of 29 are right and 14 are
duplicates. The neighbour guard therefore refuses silently, and is not turned
into a question (that was tried in 2cf4690 and showed the user a duplicate).
The "3 right splits the guard costs" above were duplicates.

## Which errors are left (`measure_errors.py`, `characterise_errors.py`)

Run 20261004-201116, window t=50..69 after [5c/6], against the tracking markers
(every cell, match 8 um): 4.6% of cells missed, 0.6% duplicated, 6.1% of
detections on no marker, 1.0% of detections over two cells. Run 015311 (older
pipeline): 4.5%, 0.1%, 5.8%, 1.7%. Most of it is already in the [3/6] detection.

* The "no marker" detections are bright (median 30 spreads, as matched ones),
  cell-sized and tracked through the window -- and 125 of 143 lie within 15 um
  of the image edge: cells cut by the frame, the marker at the visible part's
  centre. 15 are in empty space (0.6%).
* The missed cells are dim: 1.1 spreads at the marker (matched: 31). Of 106,
  73 have no detection near them, 26 sit inside another detection. Crops of the
  isolated ones (scratch `missed_alone.png`) are nearly invisible: the tracking
  truth follows cells through phases the image barely shows.
* A smoothed blob finder for them (`measure_dim_candidates.py`) finds 3-8% of
  the missed cells, the rest of its candidates on no cell. Not pursued.

## Recall by cell type, and the user's balancing hypothesis (`measure_recall_by_type.py`)

Recall of silver-truth cells at [3/6] and after one more training round
([5b/6]), by quintile of the run's cells:

| run | dimmest fifth | middle three fifths | smallest fifth | largest fifth |
|---|---|---|---|---|
| 201116 | 0.80 -> 0.85 | 0.97-0.99, flat | 0.87 -> 0.90 | 0.99, flat |
| 133403 | 0.83 -> 0.88 | 0.96-0.98, flat | 0.87 -> 0.90 | 0.97, flat |
| 113539 | 0.76 -> 0.79 | 0.98-0.99, flat | 0.85 -> 0.86 | 0.98, flat |

Atypical cells are recalled much less, as the user said; but a training round
raises them and leaves the typical flat, so there is no sign of over-fitting
to the typical cell lowering the tails. The proposal survives in another form:
the typical cells gain nothing from more examples and the atypical still gain
from each round, so thin the typical from what is taught. Grouping by bins one
MAD wide (quantile bins give every group the same count) and capping groups at
the 75th percentile of group counts withholds 17-22% in a simulation on two
windows. Built as TrainingBalance, on by default in the pre-flight dialog;
compare with this table on the next run.

### First run with the balance on (20261004-235510)

| run | dimmest fifth | middle three fifths | smallest fifth | largest fifth |
|---|---|---|---|---|
| 235510 (balanced) | 0.76 -> 0.80 | 0.98-0.99, flat | 0.85 -> 0.86 | 0.98, flat |

No effect: the dim fifth's level and its gain per round (+0.04) sit inside the
spread of the three unbalanced runs (0.76-0.83, +0.035-0.049), and the small
fifth gains less than in two of them. One run each way, so it does not rule out
a small effect, but it rules out the large one the dim fifth needs. The dim
cells are missed at [3/6], before anything is taught; what is taught after
does not reach them. The pre-flight checkbox stays, default to be decided.

## Whole-movie result, run 20261004-235510 (`measure_errors.py`, `measure_links.py`)

First run with the contour centre written back (309b715), the size-jump
question and the A/B/C dialog. Final snapshot (auto.correct), all 92 frames:

| run | missed | duplicate | two-in-one | background | TRA duplicate | TRA background | false divisions | true divisions |
|---|---|---|---|---|---|---|---|---|
| 235510 | 4.9% | 0.1% (9) | 0.8% | 6.4% | 0.1% | 6.4% | 40 | 77 |
| 201116 | 3.7% | 1.2% (103) | 0.8% | 6.5% | 0.3% | 7.9% | 50 | 73 |
| 113539 | 5.0% | 0.4% (36) | 0.8% | 5.9% | 0.2% | 6.3% | 31 | 77 |

Detection centre to the silver-truth cell's centroid, in cell radii
(scratch `centre_offset.py`): 235510 median 0.031 / p90 0.099 / 72 over 0.5;
201116 0.072 / 0.269 / 259; 113539 0.028 / 0.081 / 88. The offset the user saw
in 201116 is gone. Duplicates fall 103 -> 9 with it (unverified cause: an off-centre detection
left part of its cell uncovered for a second one). Links: same-track 90.9%,
unnatural 0.6%, truth divisions recovered 83%, as before.

## False divisions (`characterise_false_divisions.py`, `division_features.py`)

Run 235510, final graph: 40 false divisions against 77 true.

| kind | n |
|---|---|
| one branch continues the mother, the other lands on a neighbouring cell | 26 |
| one branch continues the mother, the other on no cell | 8 |
| two branches on the mother's own cell, mother on no cell, other | 6 |

Of the 26 stolen neighbours, 18 had no detection at the mother's frame and 8
had one whose track ended there: the neighbour's own track had a gap and the
mother was the nearest thing to link it to. The extra branches come from
ELEPHANT's NN linker (14) and linkOrphanStarts (10).

What tells them apart is geometry. The nearer daughter's jump from the mother,
in the frame's median radii, is 0.77 for true divisions and 0.21 for false
ones; the angle between the two jumps is 159 vs 110 degrees. Brightness of
the mother does not separate them (true p50 70 spreads, false 42-47, with a
wide overlap), nor do child/mother area ratios.

| rule (kept = true divisions passing, caught = false failing) | 235510 | 201116 | 113539 |
|---|---|---|---|
| near >= 0.4 radii and angle >= 90 | 74/77, 33/40 | 70/73, 42/50 | 73/77, 25/31 |
| near >= 0.5 | 65/77, 33/40 | 59/73, 42/50 | 64/77, 28/31 |
| angle >= 120 | 74/77, 26/40 | 70/73, 26/50 | 74/77, 15/31 |

The 3-4 true divisions the first rule fails are the same ones in every run
(t=14 at (79,304), t=37 at (69,352), t=79 at (356,332)): the mother sits on
one daughter. So the rule asks (A real / B one cell / C fix by hand / stop
asking) and cuts the branch that jumped on its own only when nobody can be
asked. Built as dissolveStayingDivisions, before dissolveImplausibleDivisions
in the consistency step. About 30-40 questions per run if every one is asked.

## Jumps between cells (`characterise_jumps.py`, `measure_relink.py`, `measure_swaps.py`)

The user's example, run 235510 t=13->14: two sisters; at t=14 the detector
found only the left one, linkOrphanStarts gave it to the right sister (9.4 um
against 9.9), recoverMissingPredecessors then added the right sister's
detection with no predecessor, and the left sister's track ended. One track
jumps between the cells, the other breaks.

Of the 55 links between two cells in 235510: 26 are the second branch of a
false division (the division check above), about 13 sit on a detection
covering two cells, 9 have the cell's own detection at t+1 free beside them.

Moving such a link (final graphs, against the tracking markers):

| rule | 235510 | 201116 | 113539 |
|---|---|---|---|
| free S within 1 radius and < 0.6 x d(A,B): fixed / broken / wrong either way | 12 / 0 / 1 | 5 / 0 / 6 | 9 / 0 / 3 |
| ...at 0.8 x d(A,B) | 13 / 0 / 2 | 6 / 2 / 6 | 9 / 0 / 4 |
| swap with a free end E, both within 1 radius: fixed / broken | 2 / 0 (incl. the user's) | 0 / 0 | 0 / 0 |
| ...within 1.5 radii | 2 / 1 | 1 / 0 | 0 / 1 |

Built as relinkToFreeDetections (1 radius, 0.6), before reconnectOrphanTracklets.
