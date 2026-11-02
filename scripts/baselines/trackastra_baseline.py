#!/usr/bin/env python
"""Baseline T of the benchmark (docs/paper/benchmark-protocol.md): pretrained masks tracked with Trackastra.

usage (from the repository root):
    uv run --project scripts/baselines python scripts/baselines/trackastra_baseline.py \
        <dataset> <seq> <out dir> [--data ~/Repositories/CTC_dataset] [--mode ilp]

Masks, each pretrained model used as it is, with its own automatic settings:
    Fluo-N2DL-HeLa  StarDist 2D_versatile_fluo (percentile normalisation 1-99.8, its default thresholds)
    PhC-C2DL-PSC    Cellpose cyto3 (diameter estimated by Cellpose's own size model, per frame)
    Fluo-N3DH-CE    Cellpose nuclei on each plane in 2D, the planes' masks stitched into 3D objects by Cellpose
                    (stitch_threshold 0.25, IoU; the default of Cellpose's own stitch3D). Diameter: Cellpose's
                    size model fails here (3-13 px on frames 0/97/194 of 01, against ~40 px for the truth's
                    median nucleus cross-section), so it is given the image-only cell-scale estimate
                    (benchmark/cell-scale/cell_scale.py: median over the movie's first, middle and last frames),
                    the same scale information Automahout gets -- no ground truth (decided 2026-10-16). Cellpose's
                    3D mode with the failed estimate took 13 min per frame on the GPU server.
Tracking: Trackastra general_2d (2D) or ctc (3D) pretrained models, ILP mode with the open-source SCIP solver
(no Gurobi). Both Trackastra models were trained on data that include Cell Tracking Challenge sequences: T's
scores are context, not held-out results.

Writes <out>/masks (the pretrained masks, cached: a rerun only re-tracks), <out>/RES (CTC result format) and
<out>/run-info.json (versions, models, settings, timings). Score with scripts/paper/evaluate_ctc.py.
"""
import argparse
import json
import os
import platform
import sys
import time
from importlib.metadata import version
from pathlib import Path

import numpy as np
import tifffile

# voxel size (x/y, z) in micrometres, from the CTC dataset descriptions
DATASETS = {
    "Fluo-N2DL-HeLa": dict(detector="stardist:2D_versatile_fluo", vx=0.645, vz=0.645, trackastra="general_2d"),
    "PhC-C2DL-PSC": dict(detector="cellpose:cyto3", vx=1.6, vz=1.6, trackastra="general_2d"),
    "Fluo-N3DH-CE": dict(detector="cellpose_stitch:nuclei", vx=0.09, vz=1.0, trackastra="ctc"),
}


def load_stardist2d(name):
    """StarDist2D.from_pretrained(name), but working with Keras 3: its get_file(extract=True) unpacks into
    <folder>/<name>_extracted/, while csbdeep 0.8.0 reads <folder>/config.json, so a first download on a fresh
    machine fails ("config file doesn't exist"). Download (hash-checked) without unpacking, then unpack into
    the folder csbdeep reads."""
    import zipfile
    from pathlib import Path
    from csbdeep.models.pretrained import get_model_details, get_file
    from stardist.models import StarDist2D
    key, _, m = get_model_details(StarDist2D, name)
    zpath = Path(get_file(fname=key + '.zip', origin=m['url'], file_hash=m['hash'],
                          cache_subdir=str(Path('models') / 'StarDist2D' / key), extract=False))
    folder = zpath.parent
    if not (folder / 'config.json').exists():
        with zipfile.ZipFile(zpath) as z:
            z.extractall(folder)
    model = StarDist2D(None, name=key, basedir=str(folder.parent))
    model.basedir = None  # read-only, as from_pretrained leaves it
    return model


def frames_to_track(data, dataset, seq):
    """The movie's frames, up to the last one the truth annotates (the frames that are scored)."""
    gt = Path(data, dataset, f"{seq}_GT", "TRA")
    last = max(int(f.stem[len("man_track"):]) for f in gt.glob("man_track*.tif"))
    frames = sorted(Path(data, dataset, seq).glob("t*.tif"))
    return [f for f in frames if int(f.stem[1:]) <= last]


def log(msg):
    """One line to the run's log (stdout.log), at once: a redirected stdout is otherwise block-buffered, and a
    3D frame can take minutes, so a silent log looked like a hang."""
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def frame_done(n_done, n_todo, t0):
    """Progress: the first frame, every 10th and the last; every frame when one takes over 30 s (3D)."""
    per = (time.time() - t0) / n_done
    if n_done in (1, n_todo) or n_done % 10 == 0 or per > 30:
        log(f"masks {n_done}/{n_todo}: {per:.1f} s per frame, about {per * (n_todo - n_done) / 60:.0f} min left")


def cellpose_model(model_name, gpu, info):
    """Cellpose on the requested device. Cellpose falls back to the CPU without a word when CUDA does not work
    (e.g. a driver older than the CUDA torch was built for): refuse that instead of running for days."""
    from cellpose import models
    import torch
    model = models.Cellpose(model_type=model_name, gpu=gpu)
    info["detector"]["device"] = str(model.device)
    log(f"Cellpose {model_name} on {model.device} (torch {torch.__version__}, CUDA available: "
        f"{torch.cuda.is_available()}{', ' + torch.cuda.get_device_name(0) if torch.cuda.is_available() else ''})")
    if gpu and model.device.type != "cuda":
        raise SystemExit("--device cuda, but Cellpose cannot use CUDA here (it would run on the CPU): check "
                         "nvidia-smi and that the driver supports torch's CUDA build")
    return model


STITCH_THRESHOLD = 0.25  # cellpose.utils.stitch3D's default


def image_only_diameter_px(movie_frames, vx, vz):
    """Diameter in pixels from the image-only cell-scale estimate: radius per frame on the movie's first,
    middle and last frames, median (as benchmark/cell-scale/run_cell_scale.py and Automahout's CellScale)."""
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "benchmark" / "cell-scale"))
    from cell_scale import estimate_radius
    picks = [movie_frames[0], movie_frames[len(movie_frames) // 2], movie_frames[-1]]
    radii = [estimate_radius(tifffile.imread(f), vx, vz)[0] for f in picks]
    r = float(np.median(radii))
    return 2 * r / vx, radii
# What made the mask caches written before masks/detector.txt existed (2026-10-16).
UNRECORDED_DETECTOR = {"Fluo-N2DL-HeLa": "stardist:2D_versatile_fluo", "PhC-C2DL-PSC": "cellpose:cyto3",
                       "Fluo-N3DH-CE": "cellpose3d:nuclei"}


def segment(dataset, frames, cfg, mask_dir, info, gpu):
    mask_dir.mkdir(parents=True, exist_ok=True)
    kind, model_name = cfg["detector"].split(":")
    # Cached masks are reused only if the same detector made them: never a mix of two baselines.
    made_by = mask_dir / "detector.txt"
    cached = made_by.read_text().strip() if made_by.exists() else UNRECORDED_DETECTOR[dataset]
    if cached != cfg["detector"] and any(mask_dir.glob("mask*.tif")):
        for m in mask_dir.glob("mask*.tif"):
            m.unlink()
        log(f"removed the cached masks made by {cached}: this baseline uses {cfg['detector']}")
    made_by.write_text(cfg["detector"] + "\n")
    todo = [(i, f) for i, f in enumerate(frames) if not (mask_dir / f"mask{i:04d}.tif").exists()]
    info["detector"] = {"kind": kind, "model": model_name, "frames_segmented_now": len(todo)}
    if not todo:
        log(f"masks: all {len(frames)} cached")
        return
    log(f"segmenting {len(todo)} of {len(frames)} frames with {kind} {model_name}")
    t0 = time.time()
    if kind == "stardist":
        from csbdeep.utils import normalize
        model = load_stardist2d(model_name)
        for k, (i, f) in enumerate(todo, 1):
            labels, _ = model.predict_instances(normalize(tifffile.imread(f), 1, 99.8))
            tifffile.imwrite(mask_dir / f"mask{i:04d}.tif", labels.astype(np.uint16))
            frame_done(k, len(todo), t0)
    elif kind == "cellpose":
        model = cellpose_model(model_name, gpu, info)
        diameters = []
        for k, (i, f) in enumerate(todo, 1):
            masks, _, _, diam = model.eval(tifffile.imread(f), diameter=None, channels=[0, 0])
            diameters.append(float(diam))
            tifffile.imwrite(mask_dir / f"mask{i:04d}.tif", masks.astype(np.uint16))
            frame_done(k, len(todo), t0)
        info["detector"]["diameter_px_median"] = float(np.median(diameters))
    elif kind == "cellpose_stitch":
        model = cellpose_model(model_name, gpu, info)
        movie = sorted(Path(frames[0]).parent.glob("t*.tif"))
        diam, radii = image_only_diameter_px(movie, cfg["vx"], cfg["vz"])
        info["detector"].update(diameter_px=diam, diameter_source="image-only cell-scale estimate",
                                cell_scale_radii_um=radii, stitch_threshold=STITCH_THRESHOLD)
        log(f"diameter {diam:.1f} px (cell-scale radii {', '.join(f'{r:.2f}' for r in radii)} µm on the first, "
            f"middle and last frames), planes stitched at IoU {STITCH_THRESHOLD}")
        for k, (i, f) in enumerate(todo, 1):
            masks, _, _, _ = model.eval(tifffile.imread(f), diameter=float(diam), channels=[0, 0], do_3D=False,
                                        stitch_threshold=STITCH_THRESHOLD)
            tifffile.imwrite(mask_dir / f"mask{i:04d}.tif", masks.astype(np.uint16))
            frame_done(k, len(todo), t0)
    else:
        raise ValueError(kind)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset", choices=sorted(DATASETS))
    ap.add_argument("seq")
    ap.add_argument("out")
    ap.add_argument("--data", default=os.path.expanduser("~/Repositories/CTC_dataset"))
    ap.add_argument("--mode", default="ilp", choices=["ilp", "greedy"])
    ap.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"],
                    help="for Cellpose and Trackastra (StarDist runs on TensorFlow's default device); auto = cuda when "
                         "available. GPU and CPU results can differ slightly: the device is recorded.")
    a = ap.parse_args()
    if a.device == "auto":
        import torch
        a.device = "cuda" if torch.cuda.is_available() else "cpu"
    cfg = DATASETS[a.dataset]
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    info = {"condition": "T", "dataset": a.dataset, "seq": a.seq, "mode": a.mode, "device": a.device,
            "trackastra_model": cfg["trackastra"],
            "versions": {p: version(p) for p in ("trackastra", "cellpose", "stardist", "torch", "numpy", "motile",
                                                  "ilpy")},
            "platform": platform.platform(), "seconds": {}}
    frames = frames_to_track(a.data, a.dataset, a.seq)
    if [int(f.stem[1:]) for f in frames] != list(range(len(frames))):
        raise SystemExit("frames are not t000, t001, ... without gaps")
    info["frames"] = len(frames)

    t0 = time.time()
    segment(a.dataset, frames, cfg, out / "masks", info, a.device == "cuda")
    info["seconds"]["segmentation"] = time.time() - t0

    from trackastra.model import Trackastra
    from trackastra.tracking import graph_to_ctc
    t1 = time.time()
    imgs = np.stack([tifffile.imread(f) for f in frames]).astype(np.float32)
    masks = np.stack([tifffile.imread(out / "masks" / f"mask{i:04d}.tif") for i in range(len(frames))])
    log(f"tracking {len(frames)} frames with Trackastra {cfg['trackastra']} ({a.mode}) on {a.device}")
    model = Trackastra.from_pretrained(cfg["trackastra"], device=a.device)
    result = model.track(imgs, masks, mode=a.mode)
    graph = result[0] if isinstance(result, tuple) else result
    # graph_to_ctc writes the truth's file names (man_track.txt, man_trackTTTT.tif); the result format is
    # res_track.txt and maskTTT.tif. Frame i is t=i: the frames are tTTT.tif from t000 on.
    raw = out / "trackastra_ctc"
    graph_to_ctc(graph, masks, outdir=raw)
    res = out / "RES"
    res.mkdir(exist_ok=True)
    (raw / "man_track.txt").replace(res / "res_track.txt")
    digits = 4 if len(frames) > 1000 else 3
    for i in range(len(frames)):
        (raw / f"man_track{i:04d}.tif").replace(res / f"mask{i:0{digits}d}.tif")
    raw.rmdir()
    info["seconds"]["tracking"] = time.time() - t1
    info["tracks"] = sum(1 for _ in open(res / "res_track.txt"))
    (out / "run-info.json").write_text(json.dumps(info, indent=1))
    print(json.dumps({k: info[k] for k in ("dataset", "seq", "frames", "tracks", "seconds")}))


if __name__ == "__main__":
    main()
