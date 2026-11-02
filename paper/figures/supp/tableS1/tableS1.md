<!-- The source of Supplementary Table 1 (table1.py reads it); the manuscript holds the same table. -->

## Supplementary Table 1 | Other conditions and a baseline

| Dataset (training sequence 01) | Condition | Detection | Linking | DET | TRA | LNK | BC(0) | CCA |
|---|---|---|---|---|---|---|---|---|
| Fluo-N2DL-HeLa | Automahout (reported) | Cellpose-SAM, chosen | nearest neighbour; lineage passes | 0.954 | 0.954 | 0.953 | 0.83 | 0.92 |
| Fluo-N2DL-HeLa | Automahout, Cellpose-SAM not a candidate | micro-SAM, chosen | nearest neighbour; lineage passes | 0.945 | 0.945 | 0.942 | 0.84 | 0.95 |
| Fluo-N2DL-HeLa | Baseline: pretrained masks + Trackastra | StarDist 2D_versatile_fluo, as it is | Trackastra general_2d (ILP) | 0.930 | 0.929 | 0.920 | 0.52 | 0.57 |
| PhC-C2DL-PSC | Automahout (reported) | Cellpose-SAM, chosen | nearest neighbour; lineage passes | 0.956 | 0.954 | 0.938 | 0.39 | 0.89 |
| PhC-C2DL-PSC | Automahout, Cellpose-SAM not a candidate | Cellpose cyto3, chosen | nearest neighbour; lineage passes | 0.904 | 0.893 | 0.819 | 0.23 | 0.71 |
| PhC-C2DL-PSC | Baseline: pretrained masks + Trackastra | Cellpose cyto3, as it is | Trackastra general_2d (ILP) | 0.913 | 0.900 | 0.816 | 0.20 | 0.28 |
| Fluo-N3DH-CE | Automahout (reported) | ELEPHANT, trained from random weights | nearest neighbour; lineage passes | 0.971 | 0.965 | 0.920 | 0.55 | 0.86 |
| Fluo-N3DH-CE | Baseline: pretrained masks + Trackastra | Cellpose nuclei per plane, stitched | Trackastra ctc (ILP) | 0.213 | 0.202 | 0.124 | 0.02 | 0.04 |

The baseline (scripts/baselines/trackastra_baseline.py) applies one pretrained detector as it is and links its
masks with Trackastra 0.5.6 [17] (ILP; model general_2d on the cultures, ctc on the embryo), with no review,
correction or lineage passes. On the embryo the masks are the limit: at t = 50, 100 and 150 the masks cover more
than half of 0, 16 and 55 of the 26, 93 and 192 ground-truth cells. All scores are on each dataset's training
sequence 01 with py-ctcmetrics 1.3.3 [23]. In each dataset, the best value of each score is in bold. Scores as
defined in Methods.
