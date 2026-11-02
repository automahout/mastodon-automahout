# Fig. 2: Autonomous tracking on three datasets

Panel a only; the scores, detectors and operations per dataset are Table 1 (`../table1/`).

Each folder in `data/` holds one run's records:

| File | Contents |
|---|---|
| `metrics.json` | CTC scores, from py-ctcmetrics 1.3.3 |
| `run-info.json` | the settings, the detector chosen, and timings |
| `frames.csv` | per frame: truth, result, matched, missed, duplicates, background, merges |
| `windows.json` | per window |
| `run.log` | the full log |

| Dataset (training sequence 01) | Run | Commit | Detector |
|---|---|---|---|
| Fluo-N2DL-HeLa | `drift/Fluo-N2DL-HeLa/01/new/repfull_cpsam` | 0d69e63 | Cellpose-SAM, as it is |
| PhC-C2DL-PSC | `drift/PhC-C2DL-PSC/01/new/repfull_cpsam` | 0d69e63 | Cellpose-SAM, as it is |
| Fluo-N3DH-CE | `drift/Fluo-N3DH-CE/01/new/repfull_zfix` | dcd7f71 | ELEPHANT from random weights |

**Not in the repository** (in the data deposit, built by `scripts/paper/build_deposit.py`):
- the CTC results (`RES/`; 5.9 GB for CE);
- the Mastodon projects;
- the embryo's final model (`final_detection_model.pth`, SHA-256 `6a507a83…`).

| Panel | Status | Files |
|---|---|---|
| a | done | `fig2a.pptx`, `fig2a.png`: one frame per dataset (HeLa t = 91, PSC t = 150, CE t = 150 with z 14.5–19.5 µm), each result label classed against the ground truth (CTC TRA markers): matched, merged, false; missed cells. Under each, one ground-truth lineage and the Automahout tracks holding its cells on the same layout (linked divisions; breaks as open circles) |

**Command** (from this folder). In `fig2.json` the images and ground truth are relative to the Cell Tracking
Challenge datasets (`--data`, default `$BENCH_DATA` or `~/Repositories/CTC_dataset`) and the RES masks to the
benchmark runs (`--runs`, default `$BENCH_OUT` or `~/automahout-bench-runs`):

```
uv run --project <repo>/scripts/paper python <repo>/scripts/paper/figures/fig2.py --config fig2.json --out-dir . [--data DIR] [--runs DIR]
```

**Note on 2a.** Most false detections on HeLa and PSC lie at the image border: cells cut by the edge, which
the ground truth leaves unmarked.
