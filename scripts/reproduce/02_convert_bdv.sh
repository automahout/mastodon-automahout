#!/usr/bin/env bash
# Step 2: convert each sequence to a BigDataViewer HDF5 + XML (the Mastodon project's image and the input of
# ELEPHANT's dataset generator): $BENCH_DATA/bdv/<dataset>_<seq>.{h5,xml}, world coordinates in µm.
# A finished conversion is skipped; an interrupted one is redone.
set -euo pipefail
. "$(dirname "$0")/config.sh"
mkdir -p "$BENCH_DATA/bdv"
for DS in $DATASETS; do for SEQ in $SEQS; do
  NAME=${DS}_$SEQ
  step "convert $NAME"
  if [ -f "$BENCH_DATA/bdv/$NAME.xml" ] && [ -f "$BENCH_DATA/bdv/$NAME.h5" ]; then echo "already converted"; continue; fi
  # Written aside, then moved: a file in bdv/ is always a finished one (the XML names its HDF5 relatively).
  TMP=$BENCH_DATA/bdv/.partial; rm -rf "$TMP"; mkdir -p "$TMP"
  # shellcheck disable=SC2046
  uvp "$REPO/scripts/paper" run --quiet --frozen python "$REPO/scripts/paper/convert_ctc_to_bdv.py" \
    "$BENCH_DATA/$DS/$SEQ" "$TMP/$NAME" $(voxel_size "$DS") $(convert_flags "$DS")
  mv "$TMP/$NAME.h5" "$BENCH_DATA/bdv/"; mv "$TMP/$NAME.xml" "$BENCH_DATA/bdv/"; rmdir "$TMP"
done; done
