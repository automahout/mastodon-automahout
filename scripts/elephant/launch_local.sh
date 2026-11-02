#!/usr/bin/env bash
# Start an ELEPHANT server on this machine, natively: no Docker, no RabbitMQ. Linux, macOS (CPU only: ELEPHANT
# uses CUDA or the CPU) or WSL. Needs uv (https://docs.astral.sh/uv/) and git -- no conda: every package comes
# from PyPI. Redis comes from the system (redis-server on PATH) or, if there is none, from the redislite package,
# which builds the redis server when installed (a C compiler is needed then: Xcode Command Line Tools on macOS).
# The first start creates the environment and takes a while; later starts take seconds. Training without an
# NVIDIA GPU is slow -- see docs/elephant-local.md.
#
#   scripts/elephant/launch_local.sh            # start; Ctrl-C stops everything
#   scripts/elephant/launch_local.sh --setup    # only create / update the environment
#
# Then in Mastodon: Plugins > ELEPHANT > Preferences, server URL http://localhost:8080 (the default), and
# generate each dataset with scripts/elephant/generate_dataset.sh.
#
# Overridable: ELEPHANT_HOME (~/.automahout/elephant), ELEPHANT_HTTP_PORT (8080), ELEPHANT_REDIS_PORT (6379),
# ELEPHANT_SERVER_REF (pinned commit below).
set -euo pipefail

ELEPHANT_HOME=${ELEPHANT_HOME:-$HOME/.automahout/elephant}
export ELEPHANT_HTTP_PORT=${ELEPHANT_HTTP_PORT:-8080}
export ELEPHANT_REDIS_PORT=${ELEPHANT_REDIS_PORT:-6379}
# elephant-server v0.7.1 (its commit, as tags can move): trains on correction labels (before, any fp/fn label made
# training a silent no-op), cropped predictions no longer read or poison the image cache, cache names are versioned,
# a model can be created from random weights, and training reads the label attributes once per run. All needed by
# the benchmark. A commit, not the tag: the check below compares the first 7 characters with HEAD.
ELEPHANT_SERVER_REF=${ELEPHANT_SERVER_REF:-0627a10}
export ELEPHANT_SERVER_DIR=$ELEPHANT_HOME/elephant-server
export ELEPHANT_WORKSPACE=${ELEPHANT_WORKSPACE:-$ELEPHANT_HOME/workspace}
VENV=$ELEPHANT_HOME/venv
PY=$VENV/bin/python
HERE=$(cd "$(dirname "$0")" && pwd)

command -v uv > /dev/null || { echo "uv is required: https://docs.astral.sh/uv/getting-started/installation/" >&2; exit 1; }

setup() {
  mkdir -p "$ELEPHANT_HOME"
  if [ ! -d "$ELEPHANT_SERVER_DIR/.git" ]; then
    git clone https://github.com/elephant-track/elephant-server.git "$ELEPHANT_SERVER_DIR"
  fi
  git -C "$ELEPHANT_SERVER_DIR" fetch --quiet origin
  git -C "$ELEPHANT_SERVER_DIR" checkout --quiet "$ELEPHANT_SERVER_REF" || {
    echo "elephant-server commit $ELEPHANT_SERVER_REF is not on GitHub (yet): push it, or set ELEPHANT_SERVER_REF" >&2; exit 1; }
  [ -x "$PY" ] || uv venv --quiet --python 3.10 "$VENV"
  # torch first (the PyPI wheel: CUDA on Linux x86-64, CPU elsewhere), then the server and its bundled core.
  grep -vE '^(pyuwsgi|nvidia-ml-py)' "$ELEPHANT_SERVER_DIR/requirements.txt" > "$ELEPHANT_HOME/requirements-local.txt"
  VIRTUAL_ENV=$VENV uv pip install --quiet "torch==2.4.*" torchvision -r "$ELEPHANT_HOME/requirements-local.txt" nvidia-ml-py
  VIRTUAL_ENV=$VENV uv pip install --quiet -e "$ELEPHANT_SERVER_DIR/elephant-core"
  command -v redis-server > /dev/null || VIRTUAL_ENV=$VENV uv pip install --quiet redislite
  # The exact versions installed, for the record (the server's requirements.txt pins only some of them).
  { echo "# elephant-server $ELEPHANT_SERVER_REF, $(date -u +%Y-%m-%dT%H:%M:%SZ)"; VIRTUAL_ENV=$VENV uv pip freeze; } \
    > "$ELEPHANT_HOME/environment.txt"
  echo "ELEPHANT environment ready: $VENV ($("$PY" -c 'import torch; print("torch", torch.__version__, "CUDA" if torch.cuda.is_available() else "CPU only")'))"
}

if [ ! -x "$PY" ] || [ "${1:-}" = "--setup" ] || [ "$(git -C "$ELEPHANT_SERVER_DIR" rev-parse --short HEAD 2>/dev/null)" != "${ELEPHANT_SERVER_REF:0:7}" ]; then
  setup
fi
[ "${1:-}" = "--setup" ] && exit 0

mkdir -p "$ELEPHANT_WORKSPACE"/{datasets,models,logs,memmaps}
export RUN_ON_FLASK=1 ELEPHANT_REDIS_HOST=localhost
LOGS=$ELEPHANT_HOME/logs; mkdir -p "$LOGS"
pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; wait 2>/dev/null || true; }
# A signal ends the script, and ending it stops the three processes (a handler alone would let it continue).
trap cleanup EXIT
trap 'exit 130' INT TERM
REDIS=$(command -v redis-server || "$PY" -c 'import redislite; print(redislite.__redis_executable__)')
"$REDIS" --port "$ELEPHANT_REDIS_PORT" --save "" > "$LOGS/redis.log" 2>&1 & pids+=($!)
"$PY" "$HERE/serve_local.py" worker > "$LOGS/worker.log" 2>&1 & pids+=($!)
"$PY" "$HERE/serve_local.py" api > "$LOGS/api.log" 2>&1 & pids+=($!)
for _ in $(seq 60); do
  if curl -fs "http://localhost:$ELEPHANT_HTTP_PORT/state/process" > /dev/null 2>&1; then
    echo "ELEPHANT server running at http://localhost:$ELEPHANT_HTTP_PORT (workspace $ELEPHANT_WORKSPACE, logs $LOGS). Ctrl-C stops it."
    wait
    exit 0
  fi
  sleep 1
done
echo "the server did not answer within 60 s; see $LOGS/api.log" >&2
exit 1
