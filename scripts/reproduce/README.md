# Reproducing the benchmark

These scripts rebuild the whole benchmark from public data, following
[docs/paper/benchmark-protocol.md](../../docs/paper/benchmark-protocol.md): the checks, the Cell Tracking
Challenge download, the conversions, the ELEPHANT datasets, baseline T, the Automahout conditions and the
summary table. One command runs all of it:

```bash
scripts/reproduce/run_all.sh
```

Each step skips work it has already done. After an interruption, run the same command again and it picks up
where it stopped. The results are in `$BENCH_OUT/summary/summary.md`, with one row per run in `runs.csv`. A log
of everything goes to `$BENCH_OUT/logs/`.

| step | script | what it does | output |
|---|---|---|---|
| 00 | `00_check_requirements.sh` | checks the commands, GPU, disk space, network access and language-model settings; installs the locked Python environments (`uv.lock`) | — |
| 01 | `01_download_ctc.sh` | downloads the CTC training datasets, checks their SHA-256 and unpacks them | `$BENCH_DATA/<dataset>/` |
| 02 | `02_convert_bdv.sh` | converts each sequence to BigDataViewer HDF5 + XML, in µm | `$BENCH_DATA/bdv/<dataset>_<seq>.{h5,xml}` |
| 03 | `03_elephant_datasets.sh` | sets up the ELEPHANT server's environment and generates its datasets | `$ELEPHANT_WORKSPACE/datasets/` |
| 04 | `04_baseline_trackastra.sh` | runs baseline T (pretrained masks + Trackastra) and scores it | `$BENCH_OUT/<dataset>/<seq>/T/rep1/` |
| 05 | `05_run_conditions.sh` | runs conditions A, AO, E and P × repetitions, scoring each; starts and stops the servers it needs | `$BENCH_OUT/<dataset>/<seq>/<cond>/rep<n>/` |
| 06 | `06_summarize.sh` | builds the summary table | `$BENCH_OUT/summary/` |

To run only some steps: `run_all.sh 03` runs from step 03 on, and `run_all.sh 01 02` runs steps 01 to 02.
Each step can also be run on its own.

## Requirements

- **Commands:** [uv](https://docs.astral.sh/uv/), git, curl, unzip, a JDK 21 and Maven 3.6 or later.
- **Redis:** `redis-server`, or a C compiler; without `redis-server`, the `redislite` package builds Redis
  from source.
- **A display for step 05:** each run opens a BigDataViewer window, from which the language model's screenshots
  are rendered. On a Linux server without one, step 05 starts it inside a virtual display; this needs Xvfb
  (`xvfb-run`, e.g. `apt install xvfb`).
- **No conda:** every Python package comes from PyPI, pinned by the `uv.lock` files.
- **Disk:** about 40 GB.
- **GPU:** an NVIDIA GPU is strongly recommended. Without one, ELEPHANT and Cellpose run on the CPU. That is
  workable for the 2D datasets. Fluo-N3DH-CE is the heavy one: its baseline-T masks take about a minute
  per frame on an Apple M2 CPU (195 + 190 frames), and ELEPHANT trains on 3D volumes.
- **A clone of this repository, or a copy made with `git archive`:** each run is built from a commit and
  records it. In a clone, uncommitted changes under `src/` are refused. A `git archive` copy has no `.git`,
  so `git archive` writes the commit's hash into `.archive-commit`, and runs are named by it. Don't edit such
  a copy: its runs would carry a commit name that no longer matches the code.

## Settings

Set any of these in the environment; the defaults are in `config.sh`.

| variable | default | meaning |
|---|---|---|
| `BENCH_DATA` | `~/automahout-ctc-data` | downloads, sequences, ground truth, conversions |
| `BENCH_OUT` | `~/automahout-bench-runs` | runs, logs, summary |
| `DATASETS` | `Fluo-N2DL-HeLa PhC-C2DL-PSC Fluo-N3DH-CE` | which datasets |
| `SEQS` | `01 02` | 01 is the development sequence, 02 the held-out one |
| `CONDITIONS` | `A AO E P` | see the protocol |
| `REPS` | `3` | repetitions per condition |
| `DEVICE` | `auto` | baseline T: `cuda`, `cpu` or `auto` |
| `ELEPHANT_URL` | `http://localhost:8080/` | the ELEPHANT server |
| `REMOTE_TOOLS` | `http://127.0.0.1:8765` | the pretrained-detector server (StarDist, Cellpose) |
| `BENCH_VENV_ROOT` | unset (`.venv` in each project) | where the Python environments go; set it to a local disk when the repository is on NFS |
| `MVN_OFFLINE` | unset | when set, builds from the local Maven repository only |

**The language model** is used by every condition. Set it in the shell before running, with one of the three
backends:

```bash
# Claude on Amazon Bedrock (the benchmark's runs so far). Credentials: a Bedrock API key in
# AWS_BEARER_TOKEN_BEDROCK, or the usual AWS credentials (~/.aws/credentials, AWS_PROFILE, an IAM role).
export AUTOMAHOUT_VLM_BACKEND=bedrock
export AUTOMAHOUT_VLM_AWS_REGION=ap-northeast-1
export AUTOMAHOUT_VLM_MODEL=jp.anthropic.claude-haiku-4-5-20251001-v1:0
```

```bash
# Claude through Anthropic's API. The key can also come from ANTHROPIC_API_KEY.
export AUTOMAHOUT_VLM_BACKEND=anthropic
export AUTOMAHOUT_VLM_MODEL=claude-haiku-4-5-20251001
export AUTOMAHOUT_VLM_API_KEY=...
```

```bash
# A local or remote Ollama server: no key.
export AUTOMAHOUT_VLM_BACKEND=ollama
export AUTOMAHOUT_VLM_BASE_URL=http://localhost:11434
export AUTOMAHOUT_VLM_MODEL=llava
```

| variable | used by | meaning |
|---|---|---|
| `AUTOMAHOUT_VLM_BACKEND` | all | `bedrock`, `anthropic` or `ollama` |
| `AUTOMAHOUT_VLM_MODEL` | all | Bedrock: model or inference-profile id; Anthropic: model name; Ollama: model tag |
| `AUTOMAHOUT_VLM_AWS_REGION` | `bedrock` | the region hosting the model |
| `AUTOMAHOUT_VLM_API_KEY` | `anthropic` | the API key (otherwise `ANTHROPIC_API_KEY`) |
| `AUTOMAHOUT_VLM_BASE_URL` | `ollama` | the Ollama server |

Step 00 shows the backend and model it will use. A variable that is not set falls back to what was saved in
the VLM / LLM tab of Mastodon's Automahout Settings dialog, then to the code's default (`ollama`, `llava`). Every other
setting is the code's default; a run never reads the user's tuned settings. Each run's `run-info.json`
records the backend, model, region and URL, and which environment variable each came from, but never the key.
To keep a key out of your shell history, put the `export` lines in a file only you can read and `source` it.

## ELEPHANT on a GPU server

ELEPHANT can run elsewhere, for example with elephant-server's Docker image (`make launch`) on a GPU machine.
In that case:

1. **On the server:** clone this repository and run steps 00–03 there. For step 03, name the container and
   the host folder mounted at `/workspace`:

   ```bash
   ELEPHANT_CONTAINER=<container> ELEPHANT_WORKSPACE=<host workspace folder> scripts/reproduce/run_all.sh 00 03
   ```

   The image must be elephant-server v0.7.1 or later: training on correction labels, the image-cache
   fixes, the prior-training fix, and training that does not re-read its label attributes for every item.
2. **On the server, with its GPU (optional):** run baseline T.

   ```bash
   DEVICE=cuda scripts/reproduce/run_all.sh 04 04
   ```

   Then copy its `$BENCH_OUT/<dataset>/<seq>/T` folders back.
3. **On the machine that runs Mastodon:** point the remaining steps at the server.

   ```bash
   ELEPHANT_URL=http://<server>:8080/ scripts/reproduce/run_all.sh
   ```

   Step 05 checks that every dataset is ready on the server before it starts.

## Repository on a network filesystem (NFS)

Python reads thousands of small files when it imports a package. With the environments on NFS, importing
TensorFlow alone took about two minutes on the GPU server. Keep the repository where it is, and put the environments
and uv's download cache on a local disk:

```bash
export BENCH_VENV_ROOT=/local/disk/automahout-venvs
export UV_CACHE_DIR=/local/disk/uv-cache
```

The first run then creates the environments there, once. `BENCH_DATA` and `BENCH_OUT` can stay on NFS, but a
local disk is faster for them too.

## Notes

- **The CTC files are checked against SHA-256 values recorded on 2026-10-13.** If the CTC re-publishes a
  dataset, step 01 stops. Set `BENCH_ALLOW_CHECKSUM_MISMATCH=1` to continue anyway, and report the new value.
- **CPU and GPU results can differ slightly.** Each run records its device and versions in `run-info.json`;
  compare runs made on the same machine.
- **Baseline T caches its masks.** On a rerun, its timing covers the tracking only.
- **Published CTC values (table R)** are not produced here; they are copied from the challenge's website.
