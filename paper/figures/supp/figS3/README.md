# Supplementary Fig. 3: The run log of one window

`figS3.pptx`, `figS3.png`: the lines of `Fluo-N3DH-CE_01_run.log` (final run) for the window t = 80–89
that a person would read: the cell size measured, the references, each review round to Go, the shape fit,
the committed detection, the lineage corrections, the validation and the end-of-window fine-tuning. Lines
are selected by pattern (LOG_KEEP in the script), not edited.

```
uv run python figures/figsupp.py --vlm-dir ../../paper/figures/supp/figS1/data/vlm --truth-dir <CTC>/Fluo-N3DH-CE/01_GT/TRA --voxel 0.09,0.09,1.0 --radius 3.81 --frames ../../paper/figures/supp/figS2/data/Fluo-N3DH-CE_01_frames.csv --log ../../paper/figures/supp/figS3/Fluo-N3DH-CE_01_run.log --window 80 --out-dir ../../paper/figures/supp
```
