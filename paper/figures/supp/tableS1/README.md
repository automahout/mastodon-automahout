# Supplementary Table 1: Other conditions and a baseline

`tableS1.pptx`, `tableS1.png`, made from `tableS1.md` beside it (the same table as in the manuscript). Automahout runs: `drift/<dataset>/01/new/repfull_cpsam` (reported, cultures),
`repfull_preprint` (Cellpose-SAM not yet a candidate), `repfull_zfix` (embryo). Baseline: `<dataset>/01/T/rep1`
(`scripts/baselines/run_trackastra.sh`, ILP). The embryo's ILP took 103 min to solve (PSC's 2 min); greedy
mode on the same masks (`Fluo-N3DH-CE/01/T/greedy`) scored about the same (DET 0.219, TRA 0.207).

```
uv run python figures/table1.py ../../paper/figures/supp/tableS1/tableS1.md ../../paper/figures/supp/tableS1/tableS1.pptx --table "Supplementary Table 1" --cols 22,28,28,29,13,13,13,13,13 --merge-first --bold-best
```

The dataset cells of the first column are merged, and in each dataset the best value of each score is bold.
