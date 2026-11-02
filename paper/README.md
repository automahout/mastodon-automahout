# Paper figures and tables

One folder per figure or table under `figures/`: the editable PowerPoint slide, a PNG of it (400 dpi), the data it
was drawn from (`data/`), and a README with the command that rebuilt it. The scripts are in `scripts/paper/figures/`;
the commands run from `scripts/paper`.

The embryo (Fluo-N3DH-CE 01) was run with the code of this version: development commit dcd7f71, whose pipeline
source is this version's. The cultures (Fluo-N2DL-HeLa 01, PhC-C2DL-PSC 01) were run earlier, at development
commit 0d69e63, before two fixes: `lineage.relink_free` and `lineage.staying_divisions` now
measure distance in 3-D on a stack, where they measured it in the plane only. On a single plane, distance in the plane
and in 3-D are the same, so the fixes do not change those runs. The public repository is a snapshot without that history.
The input data are the Cell Tracking Challenge's training datasets Fluo-N2DL-HeLa, PhC-C2DL-PSC and
Fluo-N3DH-CE, downloaded from https://celltrackingchallenge.net/2d-datasets/ and
https://celltrackingchallenge.net/3d-datasets/; they are not redistributed here.
The commands of the runs are in Supplementary Note 1. Run outputs too large for the repository (RES, the
Mastodon projects, the embryo's final model) are in the archived deposit.

The manuscript as a Word document (figures above their legends; `--layout review` adds line numbers) and a PDF,
from `scripts/paper`:

```
uv run --with pypandoc-binary python manuscript_docx.py <manuscript.md> --figures ../../paper/figures \
    --out manuscript.docx [--layout review|preprint] [--pdf]
```
