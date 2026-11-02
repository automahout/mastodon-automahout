# Example remote tool server: Cellpose and StarDist

Offers five detectors to Automahout over `automahout-remote/1` (docs/remote-tools.md):

| id | model | declared for |
|---|---|---|
| `cellpose.nuclei` | Cellpose `nuclei` | fluorescence, 2D |
| `cellpose.cyto3` | Cellpose `cyto3` (generalist: whole cells) | any modality, 2D |
| `cellpose.nuclei3d` | Cellpose `nuclei` on each plane, stitched into 3D objects (IoU 0.25), diameter from the cell size Automahout measured | fluorescence, 3D |
| `stardist.fluo` | StarDist `2D_versatile_fluo` | fluorescence, 2D |
| `stardist.he` | StarDist `2D_versatile_he` | stained brightfield (not fluorescence), 2D |

These include the models of the benchmark's baseline T (StarDist fluo for HeLa, Cellpose cyto3 for PSC, Cellpose
nuclei per plane for CE), with the same settings, so Automahout's condition A can choose among the same
pretrained models (decided 2026-10-19). Cellpose runs on CUDA when there is one. A tool whose package is not
installed is not offered.

## Run

```bash
cd examples/remote-tools
uv run python server.py --port 8765
```

With [uv](https://docs.astral.sh/uv/): the first run creates the environment from `pyproject.toml` and
`uv.lock` (cellpose 3.0.10, stardist 0.9.1, tensorflow 2.16.2 on the CPU, torch 2.4.0; all from PyPI), so the
detectors give the same results on any machine. Any other Python environment with numpy, scikit-image and
cellpose and/or stardist works too. Then in Mastodon: *Plugins > Automahout > Run a Tool...*, put `http://127.0.0.1:8765` in "Remote tool
servers", press Refresh. The three tools appear under DETECT with whether each suits the open movie.

## In a tracking run

With this server configured (Run a Tool... > Remote tool servers), *Run Autonomous Tracking* first tries
the detectors that suit the movie -- StarDist, then Cellpose -- on its first, middle and last frame and
asks you whether to use one. Accept one and every frame is detected with it, then linked and corrected
as usual; decline both and ELEPHANT's adaptive tracking runs as before. Untick "Show me pretrained
detectors..." in the pre-flight dialog and the run chooses from the image instead: on HeLa 01 it takes
StarDist (image agreement 0.96; Cellpose 0.86; at least 0.90 needed), after about 1.5 minutes of trying
all three on three frames.

## Measured on HeLa 01 (Fluo-N2DL-HeLa), against the tracking markers within 8 um

| tool | t=10: detections on a cell / recall | t=60 | seconds per frame (CPU) |
|---|---|---|---|
| `stardist.fluo` | 50/56 (89%) / 50 of 54 (93%) | 110/118 (93%) / 110 of 116 (95%) | 0.3 (after the first) |
| `cellpose.nuclei` | 34/37 (92%) / 34 of 54 (63%) | 94/102 (92%) / 94 of 116 (81%) | 24-30 |
| `stardist.he` | 0 detections | 11, none on a cell | 0.1 |

`stardist.he` declares itself for brightfield, so on this movie it is listed as not applicable -- which
the numbers bear out. The same t=10 frame sent through the Java adapter (`RemoteHeLaIT`) gives the
same 56 detections, 50 on a cell.

## Writing your own

Implement `GET /tools` and `POST /tools/{id}/run` as in `server.py`: report detections in pixels
(`centroid` as x, y[, z]; `covariance` or `radius`) and Automahout converts them.
