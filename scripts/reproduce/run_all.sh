#!/usr/bin/env bash
# Reproduce the benchmark end to end: requirements, CTC download, conversion, ELEPHANT datasets, baseline T,
# the Automahout conditions, summary. Every step skips work already done, so rerunning resumes.
#
#   scripts/reproduce/run_all.sh            # steps 00..06
#   scripts/reproduce/run_all.sh 03         # from step 03 on
#   scripts/reproduce/run_all.sh 01 02      # steps 01..02 only
#
# Settings (environment): scripts/reproduce/config.sh and README.md. A log of everything goes to
# $BENCH_OUT/logs/run_all-<time>.log.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/config.sh"
FROM=${1:-00}; TO=${2:-06}
mkdir -p "$BENCH_OUT/logs"
LOG=$BENCH_OUT/logs/run_all-$(date +%Y%m%d-%H%M%S).log
exec > >(tee -a "$LOG") 2>&1
echo "automahout $(automahout_commit "$REPO" || echo "(commit unknown)"), $(date -u +%Y-%m-%dT%H:%M:%SZ), $(uname -sm)"
echo "BENCH_DATA=$BENCH_DATA BENCH_OUT=$BENCH_OUT ELEPHANT_URL=$ELEPHANT_URL"
echo "DATASETS=$DATASETS SEQS=$SEQS CONDITIONS=$CONDITIONS REPS=$REPS"
for S in "$HERE"/[0-9][0-9]_*.sh; do
  N=$(basename "$S" | cut -c1-2)
  [[ 10#$N -lt 10#$FROM || 10#$N -gt 10#$TO ]] && continue
  printf '\n######## %s ########\n' "$(basename "$S")"
  START=$(date +%s)
  "$S"
  echo "-- $(basename "$S") done in $(( $(date +%s) - START )) s"
done
echo; echo "all done; log: $LOG"
