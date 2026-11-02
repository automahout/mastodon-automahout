# Benchmark protocol (paper)

Decided with the user on 2026-10-12. The paper demonstrates and benchmarks Automahout on Cell Tracking
Challenge (CTC) data in three categories, and passes the QUAREP-LiMi checklists of Schmied et al.,
"Community-developed checklists for publishing images and image analyses", *Nature Methods* 21, 170-181
(2024), doi:10.1038/s41592-023-01987-9 -- at the **minimal** level everywhere and the **recommended** level
wherever it applies (see the checklist mapping below).

## Data

| Category | CTC dataset | Development | Held-out |
|---|---|---|---|
| 2D fluorescence | Fluo-N2DL-HeLa | 01 | 02 (see caveat) |
| 2D non-fluorescence | PhC-C2DL-PSC | 01 | 02 |
| 3D fluorescence | Fluo-N3DH-CE | 01 | 02 |

Ground truth: the CTC gold-standard tracking annotation (`0x_GT/TRA`) for every metric. Silver truth
(`0x_ST`) is never used for evaluation.

**Contamination record (measured from the repository, 2026-10-12).** The held-out rule is that no constant
was set by looking at a held-out sequence. That holds for PSC 02 and for both CE sequences (neither appears
in `benchmark/` or in code comments). It does **not** hold for HeLa 02: its truth was used, next to 01's, to
set at least these constants:

| Constant | Where | Evidence |
|---|---|---|
| border-fragment minimum track length | `BORDER_FRAGMENT_MIN_TP` | `benchmark/border-fragments/RESULTS.md` |
| minimum division interval divisor (2.5) | `estimateMinDivisionInterval` | `benchmark/division-interval/RESULTS*.md` |
| division displacement cap factor (3.0) | `DIVISION_DISPLACEMENT_FACTOR` | `benchmark/division-displacement/RESULTS.md` |
| seed-to-object / split rules | `SeedToObject`, `ShapeSplit` | `benchmark/seed-to-object/RESULTS.md` |
| daughter/mother radius rule | division plausibility | `AutoCorrectOrchestrator` comments |

HeLa 02 is therefore reported as held-out **with this caveat stated in the paper**. A clean 2D fluorescence
sequence (e.g. Fluo-N2DH-GOWT1 or Fluo-N2DH-SIM+) can be added as an extra held-out set if a reviewer asks.

**Code freeze.** Before the first held-out run the code is tagged (`paper-freeze-<date>`); every held-out
result names the tag. No change after the tag may be motivated by a held-out result. A fix found then gets a
new tag and every held-out condition is re-run.

## Software environments

No conda (decided 2026-10-12): Anaconda's `defaults` channel needs a paid licence at organisations of 200+
people. Every Python environment is a uv project with a committed lock file, all packages from PyPI:
`scripts/paper` (conversion, scoring, cell-scale measurement), the ELEPHANT server
(`scripts/elephant/launch_local.sh`; redis from the system or `redislite`) and the remote tool server
(`examples/remote-tools`). Java: Maven, from the repository's `pom.xml`.

**One-command reproduction** (2026-10-13): `scripts/reproduce/run_all.sh` runs the steps in order. They check
the requirements, download the CTC data (SHA-256 checked), convert them, generate the ELEPHANT datasets, run
baseline T and conditions A/AO/E/P, and summarise. Each step is its own script (`00_` ... `06_`) and skips
finished work, so a rerun resumes. The language model is set by `AUTOMAHOUT_VLM_*` environment variables.
README: `scripts/reproduce/README.md`.

## Conditions

| Id | Condition | What it shows |
|---|---|---|
| A | Automahout, fully autonomous: no questions, no detector trial dialog (the detector is chosen from the image) | the main result |
| A+O | Automahout with a ground-truth oracle answering its questions | what interaction buys, against the number of questions |
| E | ELEPHANT's adaptive tracking, the pretrained detectors used only to seed its training: the detector A's choice would use (or, when none passes, the best that persists on every pair) gives its persisting detections as seeds; it never detects. Redefined with the user, 2026-10-21: it was ELEPHANT from intensity maxima, which are cells only in fluorescence (seeds on a real cell: PSC 32%, CE 23%). ELEPHANT alone needs a person to annotate, so it has no autonomous form | using a pretrained model directly (A) against using it to start a model trained on the movie (E) |
| P | A with every correction pass switched off (`-Dautomahout.passes.disable=all`): detection + linking only | what the correction passes add |
| T | Trackastra on pretrained masks: StarDist 2D_versatile_fluo (HeLa), Cellpose cyto3 (PSC), Cellpose nuclei per plane, stitched into 3D (CE; diameter from the image-only cell-scale estimate, since Cellpose's own fails there) | an established external tracker |
| R | Published CTC leaderboard values | context only: those are on the CTC test sequences, not on 02 |

Every stochastic condition (A, A+O, E, P: LLM agent, network training -- P only switches the correction passes
off) is run **n = 3** times; reported as mean and range. T is deterministic: run once and said to be so.

**Detector selection in A, AO, P** (decided with the user, 2026-10-19/20). The remote tool server offers the
pretrained models of T, with T's settings: StarDist fluo and H&E, Cellpose nuclei and cyto3 (2D), Cellpose nuclei
per plane stitched (stacks, diameter from the measured cell radius). Before 2026-10-19 it offered nothing for
phase contrast or stacks, so A was ELEPHANT from intensity seeds on PSC and CE. Which applicable model is used, if any, is decided without
the truth, by `DetectorTrial`:

- 2D fluorescence: image agreement with the frame's bright regions, F1 >= 0.90 (calibrated on HeLa 01).
- Otherwise (phase contrast; stacks, which it reads as a maximum projection): persistence -- the share of a
  detector's detections that pair up one to one with its detections in the next frame, within one measured cell
  radius -- at least 0.80 on each of three pairs (t, t+1) at the first, middle and last frame. The image
  agreement measured nothing there (development sequences, three frames each):

| Sequence | detector | image agreement F1 | persistence (3 pairs) | true F1 vs TRA markers |
|---|---|---|---|---|
| HeLa 01 | StarDist fluo | 0.91 / 0.99 / 0.97 | 1.00 / 0.92 / 0.93 | 0.93 / 0.92 / 0.91 |
| HeLa 01 | Cellpose cyto3 | 0.89 / 0.99 / 0.96 | 0.99 / 0.92 / 0.90 | 0.92 / 0.93 / 0.92 |
| HeLa 01 | Cellpose nuclei | 0.76 / 0.88 / 0.93 | 0.97 / 0.88 / 0.91 | 0.77 / 0.81 / 0.84 |
| PSC 01 | Cellpose cyto3 | 0.51 / 0.21 / 0.05 | 1.00 / 0.95 / 0.93 | 0.86 / 0.85 / 0.75 |
| PSC 01 | StarDist H&E | 0.00 / 0.00 / 0.00 | 0.00 / 0.67 / 0.88 | 0.00 / 0.00 / 0.00 |
| CE 01 | Cellpose nuclei per plane | 0.86 / 0.60 / 0.00 | 0.13 / 0.44 / 0.62 | 0.00 / 0.44 / 0.60 |

  When none passes but one applies and persists on every pair (now CE), ELEPHANT detects, and that detector's
  persisting detections seed ELEPHANT's training (warm-up and any cold start) instead of the image's brightest
  maxima (decided with the user, 2026-10-21). Seeds on a real cell, development sequences, three frames each:
  CE 63% (Cellpose nuclei per plane, persisting) against 23% (intensity maxima); PSC 78% against 32%; HeLa 89%
  against 87%. In E the pretrained detectors only ever seed (`-Dautomahout.detector.seedsOnly=true`).

  True F1: one-to-one matching of detection centroids to TRA marker centroids within one measured cell radius
  (it reproduces the HeLa calibration recorded in the code). The outcome: HeLa StarDist (unchanged), PSC Cellpose
  cyto3, CE ELEPHANT. Persistence is blind to cells missed consistently (Cellpose nuclei on HeLa scores almost
  like StarDist), which is why 2D fluorescence keeps the image agreement.

**Correction passes changed on the development sequences** (each measured against the 01 truth):
`frame.sandwiched`, written for phantoms between nuclei adjacent along z, now runs on stacks only. On 2D it took
ordinary neighbouring cells: on Cellpose cyto3's masks of PSC 01 (every 10th frame) it deleted 6.4% of the
detections, 82% of them on a real cell (76% of all detections are), 5150 spots in the first A run on PSC; on
HeLa 01 it deleted 1 of 963 (2026-10-20).

**Divisions on PSC 01, measured (2026-10-21; A rep1, every true division followed in the result).** A finds 119 of
569 divisions (BC(0) 0.23; T 0.20), and the division questions are not the bottleneck: of 1842 asked in A+O, the
truth called 140 real (8%), 1359 not, and left 343 without a truth cell near the mother. Of 587 true divisions
(truth parents with daughters): 35% found (on time or late, which BC(1-3) partly forgives), 17% merged into one
detection at birth and then lost or still merged, 14% a daughter undetected at birth, 24% one daughter starting
a new track (at birth, or after merged daughters separate), 11% other. The last is not recoverable by a simple
rule: linking each parentless track start to the nearest track one frame earlier would be 8-20% real divisions
(within 1-3 cell radii), 22% with size evidence too (both daughters 30-80% of the mother, within 2x of each
other). No rule change; reported as a limitation on dense phase contrast.

**Pre-flight settings, the same in every Automahout run** (the batch pre-flight's defaults, `BatchPreflight`;
decided with the user, 2026-10-17): crop-restricted tuning **on** (self-training rounds on crops, not the full
frame); balanced training across cell types **on** -- an experiment that withholds the most typical cells from
training so dim, small and large ones count for more; it is part of the method as evaluated and is described
in Methods as such. Merging overlapping detections and splitting merged ones stay off (measured: they did more
harm than good on HeLa), questions are off except in A+O. Refitting anisotropic spots is off in the settings,
but the agent may switch it on for an `auto.correct` call (it did on HeLa 01, refitting 274 spots): decided with
the user, 2026-10-18, that the agent's choice stays part of the method, so it varies between runs; each run's
log records it.

The oracle (A+O) answers each question from the GT: a "one cell or two?" question by the number of GT
cells whose marker lies inside the spot, a division question by whether the GT has a division within the
spot's reach at that timepoint. It is part of the benchmark code and reported as such.

## ELEPHANT settings

Set by Automahout from the data, not by hand (decided 2026-10-12): the detection radius range (0.5-2.5 r) and the
nearest-neighbour linking distance (4 r) from the cell radius r measured in the image (`tool/CellScale`,
`tool/ElephantDataSettings`; measured on 01 only: `benchmark/cell-scale/RESULTS.md`). Whether a movie is 2D or 3D
reaches ELEPHANT from the image (`is_3d`). Every other ELEPHANT setting is ELEPHANT's default for a new style,
listed in the Supplement from `run-info.json`. Each run trains its own model, created from **random weights**
(`ElephantIntegration.initializeDetectionModelFromRandomWeights`, `seg/reset` with `url` null): ELEPHANT's
pretrained "versatile" model, which a new model otherwise starts from, was trained on data that include CTC
sequences (the user, 2026-10-12), so starting from it would break the held-out rule.

ELEPHANT runs on a GPU server (decided 2026-10-12); the datasets are generated there with
elephant-server's `script/dataset_generator.py`. Random initialisation needed an elephant-server fix: prior
training failed (`img_label` unassigned in the `is_ae` branch of `datasets.py`). Fixed in elephant-server
`6353dab` (branch `fix-prior-training`, with a regression test); checked end to end on the local server: the
model is created (prior training, 3 epochs), and training and prediction then run without loading any
pretrained model. Deployed on the GPU server.

**Training speed (elephant-server `edab353`, branch `cache-label-attrs`, deployed on the GPU server 2026-10-21).** In
PSC 01's condition E (old definition), ELEPHANT training took 6.6 h of an 8.1 h run, about 190 s per call with
the GPU near 0%. Measured in the GPU server's container: the network step took 11.5 ms, a log message 3.5 ms, and
reading a timepoint 1–4 ms. One epoch of the server's own training function (50 steps) took 7.9 s, of which
5.6 s went to parsing the label attributes, a JSON file holding every labelled frame's crop index pool (2.47 MB
on PSC), twice per training item. The file grows as frames are labelled, so a window's training took 8 m 22 s
in window 1 and 10 m 13 s in window 20. `edab353` reads it once per training call outside ELEPHANT's live mode.
The training is unchanged: with the same seeds the crops equal those of the old code (test). Runs made before
the deployment (HeLa 01 A, AO, E, P; PSC 01 A, AO) used the old server. `run-info.json` records the server's URL
and GPUs but not its commit, so a run's `started` time against this deployment is the boundary.

## Pretrained models and their training data (checklist, ML minimal)

| Model | Used in | Training data | Overlap with the benchmark's CTC data |
|---|---|---|---|
| ELEPHANT versatile2d/3d | not used (each run starts from random weights) | includes CTC (the user, 2026-10-12) | yes -- hence not used |
| StarDist 2D_versatile_fluo | A, AO, P (via the remote tool server); T (HeLa) | subset of the DSB 2018 nuclei challenge (StarDist README) | none known |
| Cellpose nuclei 3.0.10 | A, AO, P (remote tool server: 2D, and per plane stitched for stacks); T (CE, per plane, stitched) | not listed in the Cellpose docs | **to verify** (Cellpose paper, Stringer et al. 2021) |
| Cellpose cyto3 3.0.10 | A, AO, P (remote tool server, any 2D modality); T (PSC) | "9 datasets" (Cellpose3 paper) | **to verify** (Stringer & Pachitariu 2025) |
| Trackastra general_2d 0.5.6 | T (HeLa, PSC) | lists "Subset of Cell Tracking Challenge 2d datasets" (model card in the package) | **yes** -- T is context, not held-out |
| Trackastra ctc 0.5.6 | T (CE) | Cell Tracking Challenge data | **yes** -- T is context, not held-out |

Baseline T runs with `scripts/baselines/run_trackastra.sh <dataset> <seq>` (a uv project; Trackastra's ILP on the
open-source SCIP solver, Gurobi removed); deterministic, so one repetition (re-tracking PSC 01 from the same masks
reproduced `res_track.txt` byte for byte). Development sequences so far (M2 CPU, 2026-10-13):

| Sequence | masks | tracking | DET | TRA | LNK | CT | TF | BC(0) | CCA |
|---|---|---|---|---|---|---|---|---|---|
| HeLa 01 | 99 s | 16 s | 0.930 | 0.929 | 0.920 | 0.334 | 0.921 | 0.522 | 0.569 |
| PSC 01 | 91 min | 137 s | 0.913 | 0.900 | 0.816 | 0.063 | 0.660 | 0.197 | 0.275 |

**CE segmentation (decided with the user, 2026-10-16/17): per plane, stitched.** Cellpose's size model
estimated CE's nuclei at 3-13 px across (frames 0/97/194 of 01; the truth's median cross-section is ~40 px), so
Cellpose enlarged every plane up to 5x, and its 3D mode took 13 min per frame on the GPU server. CE therefore gets
the diameter from the image-only cell-scale estimate (26.8 px; the scale Automahout gets too, no truth), and
each plane is segmented in 2D with the planes' masks stitched into objects (Cellpose's stitch_threshold, at its
stitch3D default 0.25). Only CE: StarDist (HeLa) takes no diameter, and Cellpose's estimate works on PSC.

Per-plane vs 3D mode at the same diameter, CE 01, M2 CPU (matched = one object covers more than half of a truth
marker, CTC's DET rule):

| Frame | mode | time | objects | truth nuclei matched |
|---|---|---|---|---|
| t097 | per plane | ~1 min | 129 | 20 of 90 |
| t097 | 3D | 19 min | 364 | 32 of 90 |
| t194 | per plane | ~1 min | 355 | 127 of 362 |
| t194 | 3D | 19 min | 390 | 93 of 362 |

Neither mode suits CE: per plane, a nucleus spans 3-4 planes 1 µm apart and is often missed in some of them,
so it breaks into fragments (objects span a median of 1 plane, truth markers 3); in 3D, nuclei are over-split
(364 objects for 90 nuclei at t097). 3D is not clearly better and is ~20x slower, so per plane stays. CE's low T
scores are a property of the pretrained nuclei model on these data, not of the setup (masks, stitching and
scoring checked).

**Results, GPU server (NVIDIA GPU; 2026-10-17)**, `scripts/reproduce/run_all.sh 04 04` from a `git archive` copy,
one repetition (deterministic). Sequence 02 is held out: reported, never used to change a setting.

| Sequence | DET | TRA | LNK | CT | TF | BC(0) | CCA |
|---|---|---|---|---|---|---|---|
| HeLa 01 | 0.930 | 0.929 | 0.920 | 0.334 | 0.921 | 0.522 | 0.569 |
| HeLa 02 | 0.952 | 0.950 | 0.937 | 0.382 | 0.904 | 0.563 | 0.519 |
| PSC 01 | 0.909 | 0.897 | 0.814 | 0.060 | 0.658 | 0.197 | 0.279 |
| PSC 02 | 0.917 | 0.903 | 0.809 | 0.050 | 0.656 | 0.175 | 0.248 |
| CE 01 | 0.214 | 0.202 | 0.125 | 0.000 | 0.113 | 0.020 | 0.040 |
| CE 02 | 0.516 | 0.497 | 0.373 | 0.001 | 0.248 | 0.068 | 0.095 |

HeLa 01 is identical to the M2 CPU run above; PSC 01 differs slightly (GPU vs CPU arithmetic in Cellpose:
DET 0.909 vs 0.913). The paper reports the GPU server's values, the machine the ELEPHANT conditions also use.
Timings on the GPU server (`run-info.json`, `seconds`; masks = Cellpose/StarDist on the GPU, tracking = Trackastra +
the ILP on SCIP, mostly one CPU core):

| Sequence | frames | masks | tracking |
|---|---|---|---|
| HeLa 01 | 92 | 25 s | 10 s |
| HeLa 02 | 92 | 31 s | 28 s |
| PSC 01 | 300 | not timed (masks cached from an earlier run) | 81 s |
| PSC 02 | 300 | 180 s | 62 s |
| CE 01 | 195 | 943 s | 4661 s |
| CE 02 | 190 | 858 s | 383 s |

CE 01's tracking took 78 min, 12x CE 02's with a similar number of frames; the cause is not measured.

## Metrics

- CTC measures via `py-ctcmetrics` (version pinned): **DET, TRA, LNK**; biological measures **CT, TF,
  BC(i), CCA**. SEG is not a claim (spots are ellipsoids) and is reported as reference only.
- Per run: wall-clock time (and on what hardware), number of questions asked (A+O), the detector chosen.
- Results come from the CTC result format (`res_track.txt` + label TIFFs) written by the benchmark exporter
  from the Mastodon project. The exporter is validated first by exporting the GT itself: every measure must
  be 1.0.

## Checklist mapping (Schmied et al. 2024)

Status: **todo** until the artifact exists and is linked here.

### Image publication (Fig. 2 of the checklist)

| Level | Item | How this paper meets it | Status |
|---|---|---|---|
| minimal | images cropped/rotated without changing meaning; quantification before | figures rendered by script from the raw CTC data; no rotation except multiples of 90° | todo |
| minimal | adjust brightness/contrast linearly; same adjustment for compared images | one fixed linear range per dataset, stated in the legend | todo |
| minimal | channel information annotated; colors accessible to color-blind readers | single channel, grayscale; overlays in a color-blind-safe palette (Okabe-Ito), with legend | todo |
| minimal | scale bar (or image dimensions) | scale bar from the dataset's voxel size on every image panel | todo |
| minimal | symbols/ROIs explained | every overlay (spot, track, division mark) in the legend | todo |
| minimal | time interval for time-lapse | frame interval from the CTC dataset description in each legend | todo |
| minimal | image files shown / used for quantification available | CTC data are public (cite + URL); our result files deposited (below) | todo |
| recommended | intensity scale for pseudo-color | no pseudo-color planned; a calibration bar if one is used | todo |
| recommended | images in a public repository with an open license | result projects, CTC-format outputs and figure source images on Zenodo (CC-BY) | todo |

### Image-analysis workflows (Fig. 3) -- Automahout is a **new workflow** with **machine learning**

| Level | Item | How this paper meets it | Status |
|---|---|---|---|
| minimal (new) | every component cited, named, versioned (Mastodon, BigDataViewer, ELEPHANT, StarDist, Cellpose, Trackastra, LLM) | versions written by the runner into each run's `run-info.json`; table in Methods | todo |
| minimal (new) | sequence of components described | the plans (`Correction plan ...` lines) and stage order, from the run log, in Methods + Supplement | todo |
| minimal (new) | key settings documented | `run-info.json` holds every setting the run used; defaults table in Supplement | todo |
| minimal (new) | workflow shared as code | GitHub repository + Zenodo DOI of the frozen tag; the whole benchmark from public data in one command (`scripts/reproduce/run_all.sh`) | partial |
| minimal (new) | example input/output and manual inputs available | inputs: CTC; outputs: deposited; manual inputs: none in A (oracle answers logged in A+O) | todo |
| recommended (new) | all settings disclosed | as above, full | todo |
| recommended (new) | rationale and limitations | Methods + Discussion (modality assumptions, 2D-only passes, HeLa 02 caveat, LLM non-determinism) | todo |
| recommended (new) | evidence of adequacy; comparison to established workflows | conditions E, P, T, R | todo |
| minimal (ML) | ML methods cited; model accessible; pretrained models identifiable | StarDist `2D_versatile_fluo`, Cellpose `nuclei` (versions); ELEPHANT models trained per run, saved per run | todo |
| minimal (ML) | validation data not part of training/testing | held-out 02 (caveat for HeLa); ELEPHANT trains only on the run's own automatic annotations of the sequence it tracks -- stated explicitly (self-training, no GT) | todo |
| recommended (ML) | training data, metadata (time), training code available | per run: ELEPHANT training annotations + logs + timings deposited; code in repo | todo |
| recommended (ML) | performance and limitations discussed | per-dataset results, failure cases with figures | todo |
| recommended (ML) | containers / easy deployment | ELEPHANT server container; remote tool server environment file; Fiji/Mastodon update site | todo |
| ideal | screen recording / tutorial; bioimage.io model format | demo video (exists in draft); optional | optional |

## Work plan

1. Exporter Mastodon -> CTC result format, and the metric script; validated on GT (all 1.0). **Done
   (2026-10-12):** `bench/CtcExport` + `scripts/paper/evaluate_ctc.py` (py-ctcmetrics 1.3.3; Python environment
   `scripts/paper/pyproject.toml` + `uv.lock`, run with `uv run --project scripts/paper`). The GT of each dataset's 01, read as moment ellipsoids (`bench/CtcGroundTruth`),
   exported and scored against itself: Valid = DET = TRA = LNK = CT = TF = BC(0) = CCA = 1.0000 on PSC
   (300 frames, 71403 objects), HeLa (92, 8639) and CE (195, 23802; anisotropic 0.09 x 0.09 x 1.0 µm).
   (The TRA truth is markers, so this validates the exporter, not the ellipse as a segmentation.)
2. All six sequences converted to BDV/Mastodon projects (CE 01's current file holds one timepoint only).
   `scripts/paper/convert_ctc_to_bdv.py` -> `~/Repositories/CTC_dataset/bdv/<dataset>_<seq>.h5/.xml`: pixel
   values bit for bit (checked on HeLa 01 t=10), world coordinates in µm (HeLa 0.645, PSC 1.6, CE
   0.09 x 0.09 x 1.0), a three-level pyramid.
3. Headless batch runner: opens a project, runs condition A/A+O/E/P with fixed settings, saves the project,
   exports, writes `run-info.json` (versions, settings, git tag, timings, questions).
4. The oracle.
5. Baselines P and T; table R.
6. Development runs on 01; code freeze; held-out runs on 02 (n = 3).
7. Figures by script, checked against the image checklist; deposit; Methods text from `run-info.json`.
