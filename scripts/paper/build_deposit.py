"""The data deposit of a preprint version: the reported runs' outputs, the embryo's trained model, the baseline's results
and the code, in one folder ready to upload, with a README and a SHA-256 manifest.

No Cell Tracking Challenge data go in (its conditions of use forbid redistributing them). Run records are cleaned
of the GPU server's host name (every non-local host named in a run's run-info.json) and of the home folder; the
Mastodon projects are repointed to <dataset>_01.xml beside them; every file, and every text entry inside the zips,
is checked for those names before the manifest is written.

    uv run python build_deposit.py --out ~/automahout-deposit/preprint-v1 [--runs $BENCH_OUT] [--tag public/preprint-v1]
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path
from urllib.parse import urlparse

# (dataset, the reported run, its baseline), relative to the benchmark runs folder.
RUNS = [
    ("Fluo-N2DL-HeLa", "drift/Fluo-N2DL-HeLa/01/new/repfull_cpsam", "Fluo-N2DL-HeLa/01/T/rep1"),
    ("PhC-C2DL-PSC", "drift/PhC-C2DL-PSC/01/new/repfull_cpsam", "PhC-C2DL-PSC/01/T/rep1"),
    ("Fluo-N3DH-CE", "drift/Fluo-N3DH-CE/01/new/repfull_zfix", "Fluo-N3DH-CE/01/T/rep1"),
]
MODEL = ("Fluo-N3DH-CE", "drift/Fluo-N3DH-CE/01/new/repfull_zfix/final_detection_model.pth")
RUN_FILES = ["run-info.json", "run.log", "metrics.json", "frames.csv", "windows.json"]
BASELINE_FILES = ["run-info.json", "metrics.json"]
SCORES = ["DET", "TRA", "LNK", "BC(0)", "CCA"]
REPO = Path(__file__).resolve().parents[2]


def hidden_hosts(run_dirs):
    """Every host named in a URL of the runs' run-info.json, except this machine."""
    hosts = set()
    for d in run_dirs:
        text = (d / "run-info.json").read_text(encoding="utf-8")
        for url in re.findall(r"https?://[^\s\"',)]+", text):
            h = urlparse(url).hostname
            if h and h not in ("localhost", "127.0.0.1", "::1", "<gpu-server>"):
                hosts.add(h)
    return hosts


class Cleaner:
    def __init__(self, hosts):
        self.hosts = sorted(hosts, key=len, reverse=True)
        self.short = sorted({h.split(".")[0] for h in hosts}, key=len, reverse=True)
        self.home = str(Path.home())
        self.user = Path.home().name

    def __call__(self, text):
        for h in self.hosts + self.short:
            text = text.replace(h, "<gpu-server>")
        return text.replace(self.home + "/", "~/").replace(self.home, "~")

    def leaks(self, text):
        names = self.hosts + self.short + [self.home, "/" + self.user + "/"]
        return [n for n in names if n in text]


def copy_clean(src, dst, clean):
    dst.write_text(clean(src.read_text(encoding="utf-8")), encoding="utf-8")


def zip_dir(src, dst, arcroot):
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for f in sorted(src.rglob("*")):
            if f.is_file():
                z.write(f, Path(arcroot) / f.relative_to(src))


def repoint_project(src, dst, xml_name):
    """The project with its dataset path set to xml_name beside it; every other entry as it was."""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "project.xml":
                text, n = re.subn(r"<SpimDataFile[^>]*>[^<]*</SpimDataFile>",
                                  f'<SpimDataFile type="relative">{xml_name}</SpimDataFile>', data.decode("utf-8"))
                assert n == 1, f"{src}: no SpimDataFile"
                data = text.encode("utf-8")
            zout.writestr(item, data)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def check(out, clean):
    """Every file, and every text entry of every zip, free of the hidden names."""
    bad = []
    for f in sorted(out.rglob("*")):
        if not f.is_file():
            continue
        if f.suffix == ".zip" or f.suffix == ".mastodon":
            with zipfile.ZipFile(f) as z:
                for name in z.namelist():
                    if name.endswith((".xml", ".txt", ".json", ".md", ".py", ".sh", ".csv", ".log", ".backup", ".java")):
                        hit = clean.leaks(z.read(name).decode("utf-8", "replace"))
                        if hit:
                            bad.append(f"{f.relative_to(out)}!{name}: {hit}")
        elif f.suffix in (".json", ".log", ".csv", ".md", ".txt"):
            hit = clean.leaks(f.read_text(encoding="utf-8", errors="replace"))
            if hit:
                bad.append(f"{f.relative_to(out)}: {hit}")
    return bad


def readme(out, scores, model_sha):
    rows = "\n".join(f"| {d} | {kind} | " + " | ".join(f"{s.get(k):.3f}" if isinstance(s.get(k), (int, float)) else "-"
                                                    for k in SCORES) + " |" for d, kind, s in scores)
    text = f"""# Automahout, preprint v1: data deposit

SSBD:repository, `ssbd-repos-000552`, https://doi.org/10.24631/ssbd.repos.2026.11.552. Companion to the preprint "Automahout: an autonomous image analysis system for cell
tracking" and to the code at https://github.com/automahout/mastodon-automahout, tag `preprint-v1`.

## Contents

| Folder | What |
|---|---|
| `code/` | the public repository at tag `preprint-v1` (`git archive`), including the figure data and scripts |
| `runs/<dataset>_01/` | Automahout's reported run on each dataset's training sequence 01 |
| `models/` | the ELEPHANT detection model Automahout trained on Fluo-N3DH-CE 01, from random weights |
| `baselines/<dataset>_01/` | the baseline: one pretrained detector as it is, linked with Trackastra (ILP) |
| `MANIFEST.sha256` | the SHA-256 of every file (`shasum -a 256 -c MANIFEST.sha256`) |

Each run folder holds `RES.zip` (the tracking result in the Cell Tracking Challenge format: `mask*.tif` and
`res_track.txt`), `project.mastodon` (the result as a Mastodon project), `run-info.json` (settings, versions,
servers, timings), `run.log` (the full log of what Automahout measured, decided and corrected), `metrics.json`
(the scores, py-ctcmetrics 1.3.3), `frames.csv` and `windows.json` (errors per frame and per 10-frame window).
Each baseline folder holds `RES.zip`, `metrics.json` and `run-info.json`.

## Scores (training sequence 01)

| Dataset | Condition | {" | ".join(SCORES)} |
|---|---|{"---|" * len(SCORES)}
{rows}

The embryo was scored on t = 0-194, the frames with ground truth.

## Images and ground truth are not included

The input images and the ground truth are the Cell Tracking Challenge's datasets Fluo-N2DL-HeLa, PhC-C2DL-PSC and
Fluo-N3DH-CE (training sequence 01), which may not be redistributed. Download them from
https://celltrackingchallenge.net/2d-datasets/ and https://celltrackingchallenge.net/3d-datasets/, and cite
Maska et al., Nat. Methods 20, 1010-1020 (2023) and Ulman et al., Nat. Methods 14, 1141-1152 (2017).

To open a project in Mastodon, convert the sequence to BigDataViewer with the code's
`scripts/paper/convert_ctc_to_bdv.py`, and put the resulting `<dataset>_01.xml` and `.h5` beside
`project.mastodon` (or point Mastodon to them when it asks).

## The model

`models/Fluo-N3DH-CE_01_final_detection_model.pth`: ELEPHANT's detection model (a PyTorch state dict for
ELEPHANT server v0.7.1) as it was after the last window's fine-tuning, trained only on labels Automahout made from
the images; no ground truth was used. SHA-256 `{model_sha}`.

## Provenance

Built by `scripts/paper/build_deposit.py` (in `code/`) from the benchmark runs, at code tag `preprint-v1`. Run records were cleaned of the GPU server's host name (`<gpu-server>`) and of the home folder (`~/`);
nothing else was changed. The vision-language model's credentials were never recorded.
"""
    (out / "README.md").write_text(text, encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", required=True)
    ap.add_argument("--runs", default=os.environ.get("BENCH_OUT", str(Path.home() / "automahout-bench-runs")))
    ap.add_argument("--tag", default="public/preprint-v1", help="the git tag of the code archive")
    a = ap.parse_args()
    runs, out = Path(a.runs).expanduser(), Path(a.out).expanduser()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    run_dirs = [runs / r for _, r, _ in RUNS] + [runs / b for _, _, b in RUNS]
    clean = Cleaner(hidden_hosts(run_dirs))
    print(f"hiding {len(clean.hosts)} host name(s) and the home folder")

    (out / "code").mkdir()
    subprocess.run(["git", "-C", str(REPO), "archive", "--format=zip", "--prefix=mastodon-automahout-preprint-v1/",
                    "-o", str(out / "code" / "mastodon-automahout-preprint-v1.zip"), a.tag], check=True)

    scores = []
    for name, run, base in RUNS:
        for kind, src, files, folder in (("Automahout (reported)", runs / run, RUN_FILES, "runs"),
                                         ("Baseline: pretrained masks + Trackastra", runs / base, BASELINE_FILES,
                                          "baselines")):
            dst = out / folder / f"{name}_01"
            dst.mkdir(parents=True)
            for f in files:
                copy_clean(src / f, dst / f, clean)
            zip_dir(src / "RES", dst / "RES.zip", "RES")
            if folder == "runs":
                repoint_project(src / "project.mastodon", dst / "project.mastodon", f"{name}_01.xml")
            scores.append((name, kind, json.loads((dst / "metrics.json").read_text())))
            print(f"{folder}/{name}_01")

    (out / "models").mkdir()
    model = out / "models" / f"{MODEL[0]}_01_final_detection_model.pth"
    shutil.copy2(runs / MODEL[1], model)
    readme(out, scores, sha256(model))

    bad = check(out, clean)
    if bad:
        raise SystemExit("hidden names found:\n  " + "\n  ".join(bad))
    files = sorted(f for f in out.rglob("*") if f.is_file())
    (out / "MANIFEST.sha256").write_text("".join(f"{sha256(f)}  {f.relative_to(out)}\n" for f in files))
    total = sum(f.stat().st_size for f in files)
    print(f"{out}: {len(files)} files, {total / 1e6:.0f} MB; checked clean")


if __name__ == "__main__":
    main()
