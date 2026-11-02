"""Cellpose-SAM against Cellpose 3 cyto3 on sampled frames of a CTC sequence, each model as it is.

Writes each model's masks in CTC format (<out>/<model>/RES/maskTTT.tif) for the listed frames, to be scored with
scripts/paper/sampled_det.py. cyto3 is run as the Automahout tool server runs it (Cellpose 3, diameter from
Cellpose's own size model, channels [0, 0], flow threshold 0.4), from that server's environment; Cellpose-SAM
(Cellpose 4, model cpsam) runs here with its defaults (it needs no diameter).

    uv run python compare.py <image dir> <out dir> <t,t,...> [--model cpsam]
"""

import argparse
import time
from pathlib import Path

import numpy as np
import tifffile


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("images")
    ap.add_argument("out")
    ap.add_argument("frames")
    ap.add_argument("--model", default="cpsam", help="cpsam (Cellpose 4, this environment) or cyto3 (Cellpose 3)")
    a = ap.parse_args()
    from cellpose import models
    import torch
    gpu = torch.backends.mps.is_available() or torch.cuda.is_available()
    out = Path(a.out) / a.model / "RES"
    out.mkdir(parents=True, exist_ok=True)
    if a.model == "cpsam":
        model = models.CellposeModel(gpu=gpu)  # Cellpose 4: cpsam by default
    else:
        model = models.Cellpose(model_type=a.model, gpu=gpu)
    for t in (int(x) for x in a.frames.split(",")):
        img = tifffile.imread(Path(a.images) / f"t{t:03d}.tif")
        t0 = time.time()
        if a.model == "cpsam":
            masks, _, _ = model.eval(img)
        else:
            masks, _, _, _ = model.eval(img, diameter=None, channels=[0, 0], flow_threshold=0.4)
        tifffile.imwrite(out / f"mask{t:03d}.tif", masks.astype(np.uint16))
        print(f"{a.model} t={t}: {int(masks.max())} masks, {time.time() - t0:.1f}s", flush=True)


if __name__ == "__main__":
    main()
