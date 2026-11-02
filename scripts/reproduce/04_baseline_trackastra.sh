#!/usr/bin/env bash
# Step 4: baseline T (pretrained masks tracked with Trackastra) on every sequence, scored. Deterministic: one
# repetition. A sequence already scored is skipped. DEVICE=cpu|cuda|auto (auto). Independent of ELEPHANT.
set -euo pipefail
. "$(dirname "$0")/config.sh"
FAILED=()
for SEQ in $SEQS; do for DS in $DATASETS; do
  step "baseline T: $DS $SEQ"
  if [ -f "$BENCH_OUT/$DS/$SEQ/T/rep1/metrics.json" ]; then echo "already scored"; continue; fi
  "$REPO/scripts/baselines/run_trackastra.sh" "$DS" "$SEQ" || FAILED+=("$DS $SEQ")
done; done
[ ${#FAILED[@]} = 0 ] || { printf 'failed: %s\n' "${FAILED[@]}" >&2; exit 1; }
