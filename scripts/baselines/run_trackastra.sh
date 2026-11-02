#!/usr/bin/env bash
# Baseline T for one CTC sequence: pretrained masks + Trackastra, then scored. Results go where
# scripts/paper/run_benchmark.sh puts the other conditions: $BENCH_OUT/<dataset>/<seq>/T/rep1.
#
#   scripts/baselines/run_trackastra.sh <dataset> <seq>
#
# Environment: BENCH_DATA (~/Repositories/CTC_dataset), BENCH_OUT (~/automahout-bench-runs), DEVICE (auto:
# CUDA when available; Fluo-N3DH-CE's masks take ~1 min per frame on an M2 CPU, far less on a GPU). Needs uv.
# Deterministic, so one repetition. Masks are cached in the output folder: a rerun only re-tracks.
set -euo pipefail
[ $# -eq 2 ] || { sed -n 2,9p "$0"; exit 1; }
DATASET=$1; SEQ=$2
REPO=$(cd "$(dirname "$0")/../.." && pwd)
. "$REPO/scripts/uv_project.sh"
BENCH_DATA=${BENCH_DATA:-$HOME/Repositories/CTC_dataset}
BENCH_OUT=${BENCH_OUT:-$HOME/automahout-bench-runs}
OUT=$BENCH_OUT/$DATASET/$SEQ/T/rep1
mkdir -p "$OUT"
STATUS=0
# The baseline's own output goes to the log, not here: say where, and how far the masks have got.
echo "log: $OUT/stdout.log (masks so far: ls $OUT/masks | wc -l; the first run also installs the environment)"
uvp "$REPO/scripts/baselines" run --quiet python "$REPO/scripts/baselines/trackastra_baseline.py" \
  "$DATASET" "$SEQ" "$OUT" --data "$BENCH_DATA" --device "${DEVICE:-auto}" > "$OUT/stdout.log" 2>&1 || STATUS=$?
if [ "$STATUS" -ne 0 ]; then
  echo "baseline exited with $STATUS (see $OUT/stdout.log)" >&2
  [ -f "$OUT/RES/res_track.txt" ] || exit "$STATUS"
fi
uvp "$REPO/scripts/paper" run --quiet python "$REPO/scripts/paper/evaluate_ctc.py" "$OUT/RES" \
  "$BENCH_DATA/$DATASET/${SEQ}_GT" --json "$OUT/metrics.json"
