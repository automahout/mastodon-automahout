<!-- The source of Table 1 (table1.py reads it); the manuscript holds the same table. -->

## Table 1 | Autonomous tracking on three datasets

| Dataset (training sequence 01) | Modality | Detector, and how it was chosen | Operations carried out | DET | TRA | LNK | BC(0) | Time |
|---|---|---|---|---|---|---|---|---|
| Fluo-N2DL-HeLa | 2D fluorescence | Cellpose-SAM: agreed best with the image | pretrained detector as it is; link; lineage | 0.954 | 0.954 | 0.953 | 0.83 | 31 min |
| PhC-C2DL-PSC | 2D phase contrast | Cellpose-SAM: persisted best from frame to frame | pretrained detector as it is; link; lineage | 0.956 | 0.954 | 0.938 | 0.39 | 49 min |
| Fluo-N3DH-CE | 3D fluorescence | ELEPHANT: no pretrained detector persisted | annotate; train from random weights; review and correct; link; lineage | 0.971 | 0.965 | 0.920 | 0.55 | 4.5 h |

Scores (on each dataset's training sequence 01) as defined in Methods.
