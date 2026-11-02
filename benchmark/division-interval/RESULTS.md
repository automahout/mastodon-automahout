# The minimum division interval was the doubling time

`AutoCorrectOrchestrator.estimateMinDivisionInterval` estimated the population's doubling time
from per-timepoint spot counts and returned it as `min_div_interval` — the shortest interval
between two divisions in one lineage that is considered biologically possible. Anything faster is
dissolved by `removeRapidSuccessiveDivisions`, and judged by `dissolveImplausibleDivisions`.

A doubling time is not a minimum. It is roughly a mean, and higher than the mean cell cycle at
that: a population doubles more slowly than its cells cycle whenever some of them stop, die or
leave the field.

## Measurement

CTC tracking ground truth for Fluo-N2DL-HeLa. For every COMPLETED cycle — a track born of a
division that itself divides — the cycle length is `end - begin + 1`. 56 such cycles in sequence
01, 95 in sequence 02.

| divisor | 01 threshold | 01 real divisions cut | 02 threshold | 02 cut |
|---|---|---|---|---|
| **1.0 (as shipped)** | 54 | **91.1 %** | 59 | **91.6 %** |
| 1.25 | 44 | 73.2 % | 47 | 84.2 % |
| 1.5 | 36 | 35.7 % | 39 | 64.2 % |
| 2.0 | 27 | 0.0 % | 30 | 18.9 % |
| **2.5 (chosen)** | 22 | **0.0 %** | 24 | **3.2 %** |
| 3.0 | 18 | 0.0 % | 20 | 3.2 % |

seq 01: shortest real cycle 29 frames, median 39, doubling time 54.4.
seq 02: shortest 3 (an outlier), p05 25, median 35, doubling time 59.2.

The old threshold therefore sat **above the median cycle**, so more than half of all real
divisions were "too rapid" by construction — measured, nine in ten.

2.5 is the knee: 3.0 buys nothing more, and seq 02's residual 3.2 % is a 3-frame cycle and a
couple near 20.

## End to end

A 92-frame autonomous run on sequence 01, before this change: `divisions=10`, where the
population's own growth (45 → 158 cells) requires at least 113. The per-window topology pass
reported `dissolved rapid divisions` totalling 57 and `de-branched (single-tracked)` 129 across
the run — both driven by this one constant.

Reproduce: `python3 benchmark/division-interval/measure.py [path-to-Fluo-N2DL-HeLa]`
