#!/usr/bin/env bash
# Step 0: check what the benchmark needs and prepare the locked Python environments. Changes nothing outside
# the repository's .venv folders; fails on a missing requirement, warns on a slow or partial setup.
set -euo pipefail
. "$(dirname "$0")/config.sh"
FAIL=0
need() { if command -v "$1" > /dev/null; then echo "ok    $1: $($2 2>&1 | head -1)"; else echo "MISSING $1 -- $3"; FAIL=1; fi; }
warn() { echo "warn  $*"; }

step "commands"
need uv "uv --version" "https://docs.astral.sh/uv/getting-started/installation/"
need git "git --version" "the runs are built from a commit"
need curl "curl --version" ""
need unzip "unzip -v" ""
need java "java -version" "a JDK 21 (the runs were measured on OpenJDK 21.0.4)"
need mvn "mvn -v" "Apache Maven 3.6 or later"
if C=$(automahout_commit "$REPO"); then echo "ok    code: commit $C ($(is_clone "$REPO" && echo "git clone" || echo "git archive copy"))"
else warn "$REPO is neither a git clone nor a git archive copy: steps 01-04 work, step 05 does not (a run names its commit)"; fi
if command -v java > /dev/null; then
  JV=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
  [ "$JV" = 21 ] || warn "Java $JV: the runs were measured on Java 21"
fi
if ! command -v redis-server > /dev/null; then
  if command -v cc > /dev/null || command -v gcc > /dev/null; then echo "ok    redis: built from source by redislite (C compiler found)"
  else echo "MISSING redis-server or a C compiler (redislite builds redis): install one"; FAIL=1; fi
fi

if [ "$(uname)" = Linux ] && [ -z "${DISPLAY:-}" ]; then
  if command -v xvfb-run > /dev/null; then echo "ok    no display: step 05 runs Mastodon inside Xvfb (xvfb-run)"
  else warn "no display and no xvfb-run: step 05 opens a BigDataViewer window -- install Xvfb (e.g. apt install xvfb)"; fi
fi

step "hardware"
if command -v nvidia-smi > /dev/null && nvidia-smi -L > /dev/null 2>&1; then
  echo "ok    NVIDIA GPU: $(nvidia-smi -L | head -1)"
else
  warn "no NVIDIA GPU: ELEPHANT and Cellpose run on the CPU. Fine for the 2D datasets; Fluo-N3DH-CE is slow"
  warn "(baseline T: ~1 min per frame on an M2 CPU; ELEPHANT trains in 3D). See README: a remote ELEPHANT server."
fi
mkdir -p "$BENCH_DATA" "$BENCH_OUT"
FREE=$(df -Pk "$BENCH_DATA" | awk 'NR==2 {print int($4 / 1048576)}')
if [ "$FREE" -lt 40 ]; then warn "$FREE GB free under $BENCH_DATA; the data need about 40 GB (zips, sequences, conversions)"
else echo "ok    disk: $FREE GB free under $BENCH_DATA"; fi

step "network"
if curl -fsI -m 20 "$CTC_URL/PhC-C2DL-PSC.zip" > /dev/null; then echo "ok    $CTC_URL"
else echo "MISSING access to $CTC_URL (step 01 downloads from it)"; FAIL=1; fi

step "language model (conditions A, AO, E, P)"
if [ -n "${AUTOMAHOUT_VLM_BACKEND:-}" ]; then
  case $AUTOMAHOUT_VLM_BACKEND in
    bedrock) WHERE="region ${AUTOMAHOUT_VLM_AWS_REGION:-(saved or default)}, credentials: $(
               [ -n "${AWS_BEARER_TOKEN_BEDROCK:-}" ] && echo "AWS_BEARER_TOKEN_BEDROCK" || echo "AWS default chain")" ;;
    anthropic) WHERE="api key set"; [ -n "${AUTOMAHOUT_VLM_API_KEY:-}${ANTHROPIC_API_KEY:-}" ] \
                 || { WHERE="api key from the saved settings"; warn "no AUTOMAHOUT_VLM_API_KEY or ANTHROPIC_API_KEY"; } ;;
    ollama) WHERE="server ${AUTOMAHOUT_VLM_BASE_URL:-(saved or default)}" ;;
    *) echo "MISSING a known backend: AUTOMAHOUT_VLM_BACKEND=$AUTOMAHOUT_VLM_BACKEND (bedrock, anthropic, ollama)"; FAIL=1 ;;
  esac
  echo "ok    backend $AUTOMAHOUT_VLM_BACKEND, model ${AUTOMAHOUT_VLM_MODEL:-(saved or default)}, ${WHERE:-}"
else
  warn "AUTOMAHOUT_VLM_BACKEND not set: a run uses the language model saved in Mastodon's Automahout settings,"
  warn "or the code's default (ollama at http://localhost:11434). See README."
fi

step "Python environments (uv.lock)"
for p in scripts/paper scripts/baselines examples/remote-tools; do
  if uvp "$REPO/$p" sync --quiet --frozen; then echo "ok    $p"; else echo "FAILED $p"; FAIL=1; fi
done

[ "$FAIL" = 0 ] || { echo; echo "requirements missing (above)" >&2; exit 1; }
echo; echo "requirements met"
