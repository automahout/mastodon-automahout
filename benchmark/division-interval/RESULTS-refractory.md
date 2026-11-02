# What the refractory rule is actually cutting

## Why

A 92-frame run of sequence 01 reported cutting **147 of the 147** divisions it
examined — 3/3, 4/4, 2/2, 10/10, 5/5, 11/11, 4/4, 11/11, 12/12, 4/4 across the
windows and 81/81 in Phase 2. A rule that refuses everything it sees is either
set far too high or is looking at fabrications. Nothing said which.

## Method

`measure_refractory.py`. CTC **tracking** truth `man_track.txt`
(`label start end parent`). A division event is a track with a parent; the
quantity this rule thresholds is that track's start minus its parent's own
start, for parents themselves born by division.

## Result

| | seq 01 | seq 02 |
|---|---|---|
| division events | 188 | 422 |
| with a measurable ancestor | 112 | 191 |
| shortest real interval | **29 tp** | **3 tp** |
| p05 / median | 30 / 38 | 25 / 35 |

| threshold | real divisions cut, seq 01 | seq 02 |
|---|---|---|
| 20 | **0.0%** | **3.7%** |
| 25 | 0.0% | 3.7% |
| 29 | 0.0% | 14.1% |
| **31 (what the run used)** | **8.9%** | **26.7%** |
| 33 | 16.1% | 39.3% |

## What this settles

**The refractory rule was not the cause of the missed divisions on this run.**
On sequence 01 the shortest real interval is 29 frames, so a threshold of 31
cuts 8.9% — it cannot account for cutting 100%. The divisions it cut had
ancestors 1 to 10 frames back (the run printed the median as 10), which is below
anything real in this sequence. Those were fabrications, and cutting them is
correct.

**The threshold is still too high, for a different reason.** The divisor 2.5 was
measured at a threshold near 22; this run reached 31 because the doubling time
it divides is estimated from two quartile counts and drifts — 12, 16, 24, 24,
23, 26, 29, 31, 31 as the windows advanced. And sequence 02's shortest real
interval is 3 frames, so a value that is nearly free on one sequence costs a
quarter of the real divisions on the other, and the estimate cannot tell them
apart.

Capped at **20** (`MAX_AUTO_MIN_INTERVAL`): 0.0% and 3.7%, against 8.9% and
26.7% at 31.
