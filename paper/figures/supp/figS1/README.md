# Supplementary Fig. 1: Taking a closer look (the VLM check)

`figS1.pptx`, `figS1.png`: what the vision-language model was shown for four spots of Fluo-N3DH-CE 01
(the plane through the spot and a projection over the planes around it, with the cell circle) and what it
answered, beside the ground truth's verdict. The polar body at t = 0 is rejected correctly, a real
nucleus at t = 0 is accepted, and two real nuclei at t = 9 are wrongly rejected: the two an earlier
run rejected after passing the check, one of which another earlier run rejected too.

In the final run the VLM was consulted once, at t = 5: it passed the check on the references the detector finds
and rejected one reference, also a real nucleus (0.24 µm from a ground-truth centre), which was dropped; the answers shown come from a replay
of the review's question: PassReplay `nucleus=` with `-Dautomahout.vlm.dump=data/vlm`, at radius 3.81 µm
(window t = 0–9), model Claude Haiku 4.5. `data/vlm/` holds the images shown and the answers as returned.

```
java ... -Dautomahout.vlm.dump=paper/figures/supp/figS1/data/vlm PassReplay project=<run>/project.mastodon gt=<CTC>/Fluo-N3DH-CE/01_GT/TRA from=0 to=0 "nucleus=14.5,30.7,6.0;17.0,22.6,17.0" radius=3.81
java ... (same) from=9 to=9 "nucleus=47.7,24.6,17.0;31.1,31.1,21.0" radius=3.81
uv run python figures/figsupp.py --vlm-dir ../../paper/figures/supp/figS1/data/vlm --truth-dir <CTC>/Fluo-N3DH-CE/01_GT/TRA --voxel 0.09,0.09,1.0 --radius 3.81 --frames ../../paper/figures/supp/figS2/data/Fluo-N3DH-CE_01_frames.csv --log ../../paper/figures/supp/figS3/Fluo-N3DH-CE_01_run.log --window 80 --out-dir ../../paper/figures/supp
```
