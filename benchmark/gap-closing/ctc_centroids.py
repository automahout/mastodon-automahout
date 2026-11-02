import sys, pickle
from pathlib import Path
import numpy as np, tifffile

root = Path.home() / "Repositories/CTC_dataset/Fluo-N2DL-HeLa"
out = {}
for seq in ("01", "02"):
    tra = root / f"{seq}_GT/TRA"
    frames = sorted(tra.glob("man_track*.tif"))
    per_t = {}
    shape = None
    for f in frames:
        t = int(f.stem[len("man_track"):])
        img = tifffile.imread(f)
        shape = img.shape
        labs = np.unique(img)
        labs = labs[labs > 0]
        d = {}
        for l in labs:
            ys, xs = np.nonzero(img == l)
            d[int(l)] = (float(xs.mean()), float(ys.mean()),
                         int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max()))
        per_t[t] = d
    out[seq] = {"shape": shape, "frames": per_t}
    print(seq, shape, len(per_t), "frames", file=sys.stderr)
pickle.dump(out, open("/tmp/ctc_centroids.pkl", "wb"))
