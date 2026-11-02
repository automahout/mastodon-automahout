"""Step 4 on its own: does ELEPHANT learn a frame from a few annotated cells?

Talks to the ELEPHANT server over HTTP only. It creates a fresh model from random weights, trains it in rounds
on fixed annotations (the frame's truth cells, or a sample of them), and after every round predicts the WHOLE frame
and counts how many annotated cells are found, how many predictions there are, and their size. Nothing from the
Automahout pipeline is involved, so this measures the training step alone.

usage: train_probe.py SERVER XML TRA_DIR t name [rounds=8] [epochs=10] [crops=5] [radius=4.3] [lr=0.001] [fp=0]
                      [rmin=] [rmax=] [cells=all|N] [proofread=0] [every=0] [fpshare=1] [autobg=0] [crop=256] [memmap=1]
  SERVER  ELEPHANT URL, e.g. http://<gpu-server>:8080
  XML     the BDV XML (image size and voxel size are read from it; the dataset name is its file stem)
  TRA_DIR CTC TRA folder; cell centres are the markers' centroids
  name    model name prefix on the server (a new model each call)
  fp      background annotations to add, each 2.5 radii from every cell, drawn at random
  proofread  after a round in which every annotated cell is found, mark up to this many of its predictions on no
             cell as background (fp), as a person corrects false hits, and train on
  memmap     0: the server reads images and labels without its .dat cache (use_memmap false), as the GUI's run did
  crop       training crop size in plane, voxels (ELEPHANT's default 256; z stays 16)
  autobg     ELEPHANT's auto_bg_thresh: voxels below it (the frame normalised to 0-1) are labelled background
  fpshare    background (fp) at most this share of all annotations: marks stop once it is reached (1: no limit)
  every      1: proofread after every round, not only once all annotated cells are found, and re-tag each
             annotated cell the round missed as fn (a missed cell, weighted as a correction), once
"""
import hashlib
import json
import math
import random
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

import numpy as np
import tifffile


def post(url, body, timeout=3600):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        text = r.read().decode()
    return json.loads(text) if text.strip().startswith("{") else text


def geometry(xml):
    root = ET.parse(xml).getroot()
    size = [int(v) for v in root.find(".//ViewSetup/size").text.split()]
    vox = [float(v) for v in root.find(".//ViewSetup/voxelSize/size").text.split()]
    return size, vox


def n_keep_axials(anisotropy):
    if anisotropy <= 1:
        return 0
    a = int(anisotropy)
    lo = a.bit_length() - 1
    hi = (a - 1).bit_length()
    return lo if anisotropy - 2 ** lo < 2 ** hi - anisotropy else hi


def centres(tra_dir, t, vox):
    m = tifffile.imread(str(Path(tra_dir) / f"man_track{t:03d}.tif"))  # z, y, x
    out = []
    for lab in np.unique(m):
        if lab == 0:
            continue
        z, y, x = np.nonzero(m == lab)
        out.append([x.mean() * vox[0], y.mean() * vox[1], z.mean() * vox[2]])
    return out


def main(a):
    server, xml, tra, t, name = a[0].rstrip("/"), a[1], a[2], int(a[3]), a[4]
    kw = dict(x.split("=", 1) for x in a[5:])
    rounds, epochs, crops = int(kw.get("rounds", 8)), int(kw.get("epochs", 10)), int(kw.get("crops", 5))
    r, lr, nfp = float(kw.get("radius", 4.3)), float(kw.get("lr", 0.001)), int(kw.get("fp", 0))
    rmin, rmax = float(kw.get("rmin", r / 2)), float(kw.get("rmax", 2.5 * r))
    size, vox = geometry(xml)
    dataset = Path(xml).stem
    cells = centres(tra, t, vox)
    random.seed(1)
    if kw.get("cells", "all") != "all":
        cells = random.sample(cells, min(len(cells), int(kw["cells"])))
    allc = centres(tra, t, vox)
    extent = [size[d] * vox[d] for d in range(3)]
    fps = []
    while len(fps) < nfp:
        q = [random.uniform(0, extent[d]) for d in range(3)]
        if all(math.dist(q, c) > 2.5 * r for c in allc):
            fps.append(q)
    cov = [r * r, 0, 0, 0, r * r, 0, 0, 0, r * r]
    spots = [dict(t=t, pos=c, covariance=cov, label=f"c{i}", id=i, tag="tp") for i, c in enumerate(cells)]
    spots += [dict(t=t, pos=q, covariance=cov, label=f"b{i}", id=1000 + i, tag="fp") for i, q in enumerate(fps)]
    nka = n_keep_axials(vox[2] / vox[0])
    model = f"{name}_detection.pth"
    geo = dict(dataset_name=dataset, scales=vox, crop_size=[int(kw.get("crop", 256)), int(kw.get("crop", 256)), 16], model_name=model, n_keep_axials=nka,
               is_3d=True, cache_maxbytes=1073741824000, n_crops=crops, input_size=size)
    print(f"{dataset} t={t}: {len(cells)} annotated cells of {len(allc)}, {len(fps)} background; radius {r} um, "
          f"range {rmin:.2f}..{rmax:.2f}; {rounds} rounds x {epochs} epochs x {crops} crops, lr {lr}", flush=True)
    t0 = time.time()
    post(f"{server}/seg/reset", dict(geo, url=None))
    print(f"reset (random weights + the server's prior training): {time.time() - t0:.0f}s", flush=True)
    train = dict(geo, spots=spots, debug=False, lr=lr, batch_size=1, n_epochs=epochs, is_livemode=False,
                 aug_scale_factor_base=0.0, aug_rotation_angle=0.0, aug_contrast=0.0, timepoint=t,
                 class_weights=[1.0, 10.0, 10.0], false_weight=10.0, auto_bg_thresh=float(kw.get("autobg", 0)), c_ratio=0.4, log_interval=1,
                 log_dir="detection_log", use_memmap=kw.get("memmap", "1") == "1")
    pred = dict(dataset_name=dataset, model_name=model, timepoint=t, p_thresh=0.5, r_min=rmin, r_max=rmax, c_ratio=0.4,
                use_2d=False, batch_size=1, debug=False, output_prediction=False, use_median=False, is_pad=True,
                use_memmap=kw.get("memmap", "1") == "1", cache_maxbytes=1073741824000, is_3d=True, input_size=size, scales=vox, n_keep_axials=nka,
                patch=[384, 384, 24])
    nproof = int(kw.get("proofread", 0))
    fpshare = float(kw.get("fpshare", 1))
    every = kw.get("every", "0") == "1"
    for k in range(1, rounds + 1):
        t0 = time.time()
        res = post(f"{server}/seg/train", train)
        t1 = time.time()
        req = urllib.request.Request(f"{server}/download/model", data=json.dumps(dict(model_name=model)).encode(),
                                     headers={"Content-Type": "application/json"})
        try:  # only a fingerprint of the weights; a dropped download must not end the probe
            with urllib.request.urlopen(req, timeout=600) as rr:
                weights = hashlib.md5(rr.read()).hexdigest()[:8]
        except Exception as e:
            weights = f"? ({type(e).__name__})"
        out = post(f"{server}/seg/predict", pred)
        ps = out.get("spots", []) if isinstance(out, dict) else []
        found = sum(1 for c in cells if any(math.dist(p["pos"], c) <= r for p in ps))
        found_all = sum(1 for c in allc if any(math.dist(p["pos"], c) <= r for p in ps))
        false = sum(1 for p in ps if all(math.dist(p["pos"], c) > r for c in allc))
        radii = sorted(abs(np.linalg.det(np.reshape(p["covariance"], (3, 3)))) ** (1 / 6) for p in ps)
        med = radii[len(radii) // 2] / r if radii else float("nan")
        ok = res.get("completed") if isinstance(res, dict) else res
        marked = 0
        missed = 0
        if every:
            # A missed cell's annotation becomes a correction (fn) once, as a person re-tags it; it is not added again.
            for sp in spots:
                if sp["tag"] == "tp" and not any(math.dist(p["pos"], sp["pos"]) <= r for p in ps):
                    sp["tag"] = "fn"
                    missed += 1
        if nproof and (every or found == len(cells)):
            for p in ps:
                nfp = sum(1 for sp in spots if sp["tag"] == "fp")
                if fpshare < 1 and nfp + 1 > fpshare * (len(spots) + 1):
                    break
                if marked < nproof and all(math.dist(p["pos"], c) > r for c in allc) \
                        and not any(sp["tag"] == "fp" and math.dist(p["pos"], sp["pos"]) <= r / 2 for sp in spots):
                    spots.append(dict(t=t, pos=p["pos"], covariance=cov, label=f"m{len(spots)}", id=2000 + len(spots),
                                      tag="fp"))
                    marked += 1
        print(f"round {k}: train {t1 - t0:.0f}s ({ok}), weights {weights}; predicted {len(ps)}: annotated found {found}/{len(cells)}, "
              f"all cells found {found_all}/{len(allc)}, on no cell {false}, size {med:.2f}x"
              + (f"; {marked} false hit(s) marked background" if marked else "")
              + (f"; {missed} missed cell(s) annotated fn" if missed else ""), flush=True)


if __name__ == "__main__":
    main(sys.argv[1:])
