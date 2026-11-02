#!/usr/bin/env bash
# Step 1: download the Cell Tracking Challenge training datasets, check their SHA-256 and unpack them into
# $BENCH_DATA/<dataset>/{01,01_GT,02,02_GT,...}. A dataset already unpacked is skipped; an interrupted
# download resumes.
set -euo pipefail
. "$(dirname "$0")/config.sh"
mkdir -p "$BENCH_DATA"
for DS in $DATASETS; do
  step "download $DS"
  if [ -d "$BENCH_DATA/$DS/01_GT/TRA" ] && [ -d "$BENCH_DATA/$DS/02_GT/TRA" ]; then echo "already unpacked"; continue; fi
  ZIP=$BENCH_DATA/$DS.zip
  if [ ! -f "$ZIP" ]; then
    SIZE=$(curl -fsIL "$CTC_URL/$DS.zip" | tr -d '\r' | awk 'tolower($1) == "content-length:" {n = $2} END {print int(n / 1048576)}')
    echo "downloading $CTC_URL/$DS.zip ($SIZE MB; the server can be slow -- an interrupted download resumes)"
    # A progress meter only when someone is watching (run_all.sh logs through tee).
    if [ -t 1 ]; then P=--progress-bar; else P=-sS; fi
    curl -fL -C - --retry 3 $P -o "$ZIP.part" "$CTC_URL/$DS.zip"
    mv "$ZIP.part" "$ZIP"
  fi
  GOT=$(sha256 "$ZIP"); WANT=$(zip_sha256 "$DS")
  if [ "$GOT" != "$WANT" ]; then
    echo "$ZIP: SHA-256 $GOT, expected $WANT -- the published dataset has changed, or the download is corrupt" >&2
    [ "${BENCH_ALLOW_CHECKSUM_MISMATCH:-0}" = 1 ] || exit 1
  fi
  unzip -q -o "$ZIP" -d "$BENCH_DATA"
  chmod -R u+w "$BENCH_DATA/$DS"  # the archives' folders are read-only: a re-unpack or cleanup would fail
  echo "$DS: $(ls "$BENCH_DATA/$DS" | tr '\n' ' ')"
done
