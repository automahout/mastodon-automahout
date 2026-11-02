# Fig. 1: Automahout carries out the operations of tracking

| Panel | Status | Files |
|---|---|---|
| all | done | `fig1.png`: the five panels assembled into one image, lettered (`scripts/paper/figures/fig1_compose.py`) |
| a | done | `fig1a.pptx` (editable) and `fig1a.png` (400 dpi), made by `scripts/paper/figures/fig1a.py` |
| b | done | `fig1b.pptx`, `fig1b.png`: one review round reconstructed offline at t = 192 (the last window's middle review frame), with the run's final model: the ground truth (CTC TRA markers) for comparison, the references (`data/Fluo-N3DH-CE_01_t192_references.csv`) and the whole-frame prediction (`data/Fluo-N3DH-CE_01_t192_prediction.json`), on one z slab, 21.5–26.5 µm. The review's verdicts are printed by the script, not drawn: they are against the references, and beside the ground truth they read as errors |
| c | done | `fig1c.pptx`, `fig1c.png`: the radius the review measured per window (`radius_um`) beside the ground truth's median equivalent-sphere radius (the challenge's silver truth) on the same frames (`curated_radius_um`, from `01_ST/SEG`, voxel 0.09 × 0.09 × 1.0 µm) |
| d | done | `fig1d.pptx`, `fig1d.png`: training rounds the review needed per window, by outcome |
| e | done | `fig1e.pptx`, `fig1e.png`: cells per frame, ground truth (CTC TRA markers) and Automahout (`data/Fluo-N3DH-CE_01_frames.csv`) |

**Commands** (from `scripts/paper`):

```
uv run python figures/fig1a.py --out ../../paper/figures/fig1/fig1a.pptx
uv run python figures/fig1_windows.py ../../paper/figures/fig1/data/Fluo-N3DH-CE_01_run.log ../../paper/figures/fig1/data/Fluo-N3DH-CE_01_windows.csv
uv run python figures/fig1_curated_size.py ../../paper/figures/fig1/data/Fluo-N3DH-CE_01_windows.csv <CTC>/Fluo-N3DH-CE/01_ST/SEG 0.09,0.09,1.0 ../../paper/figures/fig1/data/Fluo-N3DH-CE_01_windows.csv
# Fig. 1b: references from the final project (PassReplay, Java), then the prediction over HTTP and the drawing
# java ... PassReplay project=<run>/project.mastodon gt=<CTC>/Fluo-N3DH-CE/01_GT/TRA from=192 to=192 blobs=3.12 refsout=../../paper/figures/fig1/data/Fluo-N3DH-CE_01_t192_references.csv
uv run python figures/fig1b.py --server <ELEPHANT URL> --model automahout-bench-drift_Fluo-N3DH-CE_01_new_repfull_zfix_detection.pth --dataset Fluo-N3DH-CE_01 --xml <CTC>/bdv/Fluo-N3DH-CE_01.xml --image <CTC>/Fluo-N3DH-CE/01/t192.tif --truth <CTC>/Fluo-N3DH-CE/01_GT/TRA/man_track192.tif --refs ../../paper/figures/fig1/data/Fluo-N3DH-CE_01_t192_references.csv --t 192 --radius 1.56 --z 24 --dz 2.5 --out ../../paper/figures/fig1/fig1b.pptx
uv run python figures/fig1_compose.py ../../paper/figures/fig1 --out ../../paper/figures/fig1/fig1.png
uv run python figures/fig1cd.py ../../paper/figures/fig1/data/Fluo-N3DH-CE_01_windows.csv ../../paper/figures/fig1 --frames ../../paper/figures/fig1/data/Fluo-N3DH-CE_01_frames.csv
```

**Source:** `data/Fluo-N3DH-CE_01_run.log` is the log of run `drift/Fluo-N3DH-CE/01/new/repfull_zfix` (commit
dcd7f71, t = 0–194).

**Why t = 192.** The final model is the one the last window's review and fine-tuning produced, so the
reconstruction is faithful only on that window's frames. At t = 85 the same model found 43 of 60 references,
whereas that window reached Go with the model it had then.
