#!/usr/bin/env bash
# Step 5: the Automahout runs -- every condition x sequence x repetition (scripts/paper/run_benchmark.sh), 01
# (development) before 02 (held-out). A run already scored is skipped, so an interrupted step resumes.
#
# Starts what is not already running, and stops it at the end: the local ELEPHANT server (only when
# ELEPHANT_URL is on this machine; a remote server must be running with step 03 done there) and the
# pretrained-detector server examples/remote-tools/server.py (all conditions; E uses it for seeds only).
set -euo pipefail
. "$(dirname "$0")/config.sh"
LOGS=$BENCH_OUT/logs; mkdir -p "$LOGS"
pids=()
cleanup() { [ ${#pids[@]} = 0 ] || { kill "${pids[@]}" 2>/dev/null || true; wait 2>/dev/null || true; }; }
trap cleanup EXIT
trap 'exit 130' INT TERM
wait_for() {  # url, seconds, what
  for _ in $(seq "$2"); do curl -fs -m 2 "$1" > /dev/null 2>&1 && return 0; sleep 1; done
  echo "$3 did not answer at $1 within $2 s" >&2; return 1
}

step "ELEPHANT server at $ELEPHANT_URL"
if curl -fs -m 5 "${ELEPHANT_URL}state/process" > /dev/null; then
  echo "running"
else
  case $ELEPHANT_URL in
    http://localhost:*|http://127.0.0.1:*)
      ELEPHANT_HTTP_PORT=$(echo "$ELEPHANT_URL" | sed -E 's#.*:([0-9]+).*#\1#') \
        "$REPO/scripts/elephant/launch_local.sh" > "$LOGS/elephant.log" 2>&1 & pids+=($!)
      wait_for "${ELEPHANT_URL}state/process" 120 "ELEPHANT (log: $LOGS/elephant.log)"
      echo "started (log: $LOGS/elephant.log)" ;;
    *) echo "not reachable, and not on this machine: start it there" >&2; exit 1 ;;
  esac
fi
for DS in $DATASETS; do for SEQ in $SEQS; do
  R=$(curl -s -X POST -H "Content-Type: application/json" \
      -d "{\"dataset_name\":\"${DS}_$SEQ\",\"shape\":[$(elephant_shape "$DS" "$SEQ")]}" "${ELEPHANT_URL}dataset/check")
  case $R in *ready*) ;; *) echo "ELEPHANT dataset ${DS}_$SEQ not ready ($R): run step 03" >&2; exit 1 ;; esac
done; done
echo "datasets ready"

case " $CONDITIONS " in *" A "*|*" AO "*|*" E "*|*" P "*)
  step "pretrained-detector server at $REMOTE_TOOLS"
  if curl -fs -m 5 "$REMOTE_TOOLS/tools" > /dev/null; then echo "running"; else
    PORT=$(echo "$REMOTE_TOOLS" | sed -E 's#.*:([0-9]+).*#\1#')
    uvp "$REPO/examples/remote-tools" run --quiet --frozen python "$REPO/examples/remote-tools/server.py" \
      --port "$PORT" > "$LOGS/remote-tools.log" 2>&1 & pids+=($!)
    wait_for "$REMOTE_TOOLS/tools" 300 "the detector server (log: $LOGS/remote-tools.log)"
    echo "started: $(curl -s "$REMOTE_TOOLS/tools" | head -c 300)"
  fi ;;
esac

FAILED=()
for SEQ in $SEQS; do for DS in $DATASETS; do for C in $CONDITIONS; do for R in $(seq "$REPS"); do
  OUT=$BENCH_OUT/$DS/$SEQ/$C/rep$R
  step "$DS $SEQ $C rep $R"
  if [ -f "$OUT/metrics.json" ]; then echo "already scored"; continue; fi
  "$REPO/scripts/paper/run_benchmark.sh" "$DS" "$SEQ" "$C" "$R" || FAILED+=("$DS $SEQ $C $R")
done; done; done; done
[ ${#FAILED[@]} = 0 ] || { printf 'failed or invalid: %s\n' "${FAILED[@]}" >&2; exit 1; }
