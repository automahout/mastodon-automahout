# Supplementary Fig. 2: Errors per frame on the embryo

`figS2.pptx`, `figS2.png`: missed cells, duplicate detections, detections on no cell and merged
detections per frame, Fluo-N3DH-CE 01, final run `repfull_zfix`, from
`data/Fluo-N3DH-CE_01_frames.csv` (scored against the ground truth, CTC TRA markers). Errors rise with
crowding after t ≈ 160; misses and duplicates move together.

```
uv run python figures/figsupp.py --vlm-dir ../../paper/figures/supp/figS1/data/vlm --truth-dir <CTC>/Fluo-N3DH-CE/01_GT/TRA --voxel 0.09,0.09,1.0 --radius 3.81 --frames ../../paper/figures/supp/figS2/data/Fluo-N3DH-CE_01_frames.csv --log ../../paper/figures/supp/figS3/Fluo-N3DH-CE_01_run.log --window 80 --out-dir ../../paper/figures/supp
```
