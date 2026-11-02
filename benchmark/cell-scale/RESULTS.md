# Cell scale from the image (development sequences only)

Question: can Automahout set ELEPHANT's data-dependent settings (detection radius range, nearest-neighbour
linking distance) from the image instead of by hand? Decided with the user, 2026-10-12, for the paper's
benchmark: yes, measured on the development sequences (01) only; the held-out sequences (02) are not looked at.

Estimator: `cell_scale.py` (reference) and `tool/CellScale.java` (port; equal to 1e-6 on formula-defined
images, `CellScaleTest`, and on all nine real frames below). Run: `uv run --project ../../scripts/paper python run_cell_scale.py 01` (from this folder).

## Estimate vs SEG truth (first / middle / last frame; truth = equivalent radius of every SEG object)

| Sequence | per frame (µm) | estimate r | truth median (n) | r / truth | p1 / r | p99 / r |
|---|---|---|---|---|---|---|
| Fluo-N2DL-HeLa 01 | 7.52, 6.02, 6.37 | **6.37** | 7.84 (491) | 0.81 | 0.62 | 2.11 |
| PhC-C2DL-PSC 01 | 7.67, 7.67, 7.67 | **7.67** | 11.02 (242) | 0.70 | 0.96 | 2.06 |
| Fluo-N3DH-CE 01 | 0.87, 1.51, 1.21 | **1.21** | 1.82 (102) | 0.66 | 0.86 | 2.23 |

CE's truth is annotated per slice, so its radii are cross-sections, smaller than the nuclei. The estimate runs
0.66-0.81 of the truth median; what matters for ELEPHANT is the range: **r/2 .. 2.5 r contains the truth's
1st..99th percentile radius in all three.**

What it took: without the 3x3(x3) median, CE's strongest normalised responses sat at the smallest scale (shot
noise; 1.3 µm from thousands of blobs); averaging the top responses per scale instead of taking scale-space
maxima read texture (HeLa 0.40 of truth, unstable across frames).

## Linking distance vs truth motion (01; centroids of TRA markers, µm)

| Sequence | 2 r | ordinary step p50 / p99 / max | > 2 r | mother -> daughter p50 / p99 / max | > 2 r | > 4 r |
|---|---|---|---|---|---|---|
| HeLa | 12.7 | 1.5 / 9.1 / 22.6 | 0.26% | 10.6 / 20.7 / 22.6 | 28.7% | < 1% |
| PSC | 15.3 | 0.8 / 8.0 / 331.7 | 0.09% | 8.2 / 18.2 / 24.8 | 3.8% | < 1% |
| CE | 2.4 | 0.6 / 2.1 / 5.2 | 0.22% | 2.3 / 4.3 / 5.3 | 41.3% | < 1% |

4 r (two cell diameters) is above the 99th percentile of the division step in all three (25.5, 30.7, 4.8 µm).

## Rule (`tool/ElephantDataSettings`)

ELEPHANT detection radius **0.5 r .. 2.5 r**, linking distance **4 r**; every other ELEPHANT setting is
ELEPHANT's default. Applied in the pre-flight (a ticked checkbox in the dialog; always in a batch run).
