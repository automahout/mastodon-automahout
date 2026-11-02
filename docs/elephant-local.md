# Running ELEPHANT locally

Automahout's ELEPHANT detector needs an ELEPHANT server. `scripts/elephant/launch_local.sh` starts one on
this machine, natively: no Docker, no RabbitMQ, no conda. It needs [uv](https://docs.astral.sh/uv/) and git;
every Python package comes from PyPI. The first start creates a virtual environment
(`~/.automahout/elephant/venv`, Python 3.10) and a checkout of elephant-server pinned to release v0.7.1
(commit `0627a10`: training on correction labels works, cropped predictions no longer read or poison the image
cache, a model can be created from random weights, and training no longer re-parses the label attributes for
every item). Later starts take seconds. Redis is the
system's `redis-server` if there is one; otherwise the `redislite` package, which builds redis 6.2 (BSD
licensed) when it is installed -- that needs a C compiler (Xcode Command Line Tools on macOS).

```bash
scripts/elephant/launch_local.sh
```

It serves `http://localhost:8080` (ELEPHANT's default server URL) until Ctrl-C. Data, models and logs are
under `~/.automahout/elephant/`; the exact package versions installed are in
`~/.automahout/elephant/environment.txt`. Override with `ELEPHANT_HOME`, `ELEPHANT_HTTP_PORT`, `ELEPHANT_REDIS_PORT`.

**Hardware.** ELEPHANT trains on an NVIDIA GPU (CUDA) or on the CPU; it does not use Apple GPUs. Without
an NVIDIA GPU, training is slow: an autonomous run takes far longer than on a GPU workstation. On Linux with
an NVIDIA GPU the environment gets the CUDA build of PyTorch automatically. The official Docker image
(elephant-server `make launch`) remains the option for Linux servers.

## Datasets

Each movie must exist on the server as a dataset, generated from its BigDataViewer `.h5` with
elephant-server's own `script/dataset_generator.py`:

```bash
scripts/elephant/generate_dataset.sh <movie.h5> <dataset name> [--2d] [--uint16]
```

`--2d` for a 2D movie; `--uint16` for 16-bit data whose range would not survive 8 bits (Fluo-N2DL-HeLa's
values lie between about 33000 and 36000). The dataset name is the one set in ELEPHANT's main settings.

## What differs from the Docker server

- No RabbitMQ: the server's log messages are not sent to the Mastodon client's log window (they are in
  `~/.automahout/elephant/logs/api.log` and `worker.log`). Without the queue, the server's logger looped
  forever trying to report its own failed connection, so `serve_local.py` turns publishing off.
- The bundled elephant-core keeps its data under `/workspace`; `serve_local.py` points it at
  `~/.automahout/elephant/workspace` instead.
- Flask's own server instead of nginx + uWSGI: single machine, single user.
