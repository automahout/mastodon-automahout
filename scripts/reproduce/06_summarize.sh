#!/usr/bin/env bash
# Step 6: collect every run's metrics.json into $BENCH_OUT/summary/{runs.csv,summary.md}: one row per run, and
# mean, SD and n per dataset x sequence x condition.
set -euo pipefail
. "$(dirname "$0")/config.sh"
step "summary"
uvp "$REPO/scripts/paper" run --quiet --frozen python "$REPO/scripts/paper/summarize.py" "$BENCH_OUT" \
  --out "$BENCH_OUT/summary"
