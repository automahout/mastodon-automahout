# SAM shape fit for Automahout

An optional server that gives every detection the shape of a micro-SAM mask under its centre. Automahout's
shape-fit pass (`detected.contour_fit`) uses it when it answers, and falls back to the contour fit when it does
not.

```bash
cd examples/sam-fit
uv run python server.py --port 8766
```

Then add `http://127.0.0.1:8766` to the remote tool servers (Automahout > Run tool, or
`-Dautomahout.remote.servers=http://127.0.0.1:8766`). The first start downloads micro-SAM's `vit_b_lm` model
(about 410 MB) into micro_sam's cache. `--device` picks `mps`, `cuda` or `cpu`.

## What it measured (2026-10-24, IoU against CTC silver truth, median / 10th percentile)

| data | shapes before | SAM |
|---|---|---|
| Fluo-N2DL-HeLa 01 (2D fluorescence, contour-fitted) | 0.79 / 0.65 | 0.91 / 0.78 |
| Fluo-N3DH-CE 01, t=140-185 (oversized detections) | 0.35-0.50 / 0.11-0.24 | 0.79-0.85 / 0.36-0.47 |
| PhC-C2DL-PSC 01 (phase contrast) | 0.67 / 0.50 | 0.71 / 0.51 |

Each detection is prompted with its centre, and its four nearest other detections as background points. On an
Apple M2 a plane costs about 1.3 s, and a CE frame about 30 s.
