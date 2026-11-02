#!/usr/bin/env bash
# Step 3: prepare the ELEPHANT server's environment and generate one ELEPHANT dataset per sequence (named
# <dataset>_<seq>), with elephant-server's own script/dataset_generator.py.
#
# Local server (default): the environment of scripts/elephant/launch_local.sh, datasets in $ELEPHANT_WORKSPACE.
# Docker server (elephant-server `make launch`, e.g. on a GPU machine): run this step ON that machine with
#   ELEPHANT_CONTAINER=<container name or id> ELEPHANT_WORKSPACE=<host folder mounted at /workspace>
# The HDF5 files are copied into $ELEPHANT_WORKSPACE/bdv so the container can read them.
# A generated dataset is skipped. If the server answers at $ELEPHANT_URL, each dataset is checked with it.
set -euo pipefail
. "$(dirname "$0")/config.sh"
CONTAINER=${ELEPHANT_CONTAINER:-}
if [ -z "$CONTAINER" ]; then
  step "ELEPHANT environment"
  "$REPO/scripts/elephant/launch_local.sh" --setup
fi
mkdir -p "$ELEPHANT_WORKSPACE/datasets"
for DS in $DATASETS; do for SEQ in $SEQS; do
  NAME=${DS}_$SEQ; DIR=$ELEPHANT_WORKSPACE/datasets/$NAME
  step "ELEPHANT dataset $NAME"
  H5=$BENCH_DATA/bdv/$NAME.h5
  [ -f "$H5" ] || { echo "no $H5: run step 02" >&2; exit 1; }
  # The marker is written after the generator succeeds: a dataset without it is regenerated.
  if [ -f "$DIR/.automahout-generated" ]; then echo "already generated"; continue; fi
  # shellcheck disable=SC2046
  if [ -z "$CONTAINER" ]; then
    "$REPO/scripts/elephant/generate_dataset.sh" "$H5" "$NAME" $(elephant_flags "$DS")
  else
    mkdir -p "$ELEPHANT_WORKSPACE/bdv"
    [ "$(wc -c < "$H5")" = "$(wc -c < "$ELEPHANT_WORKSPACE/bdv/$NAME.h5" 2>/dev/null || echo 0)" ] \
      || cp "$H5" "$ELEPHANT_WORKSPACE/bdv/$NAME.h5"
    docker exec -u "$(id -u):$(id -g)" "$CONTAINER" python /opt/elephant/script/dataset_generator.py \
      "/workspace/bdv/$NAME.h5" "/workspace/datasets/$NAME" $(elephant_flags "$DS")
  fi
  { echo "generated $(date -u +%Y-%m-%dT%H:%M:%SZ) from $NAME.h5 ($(sha256 "$H5")), flags: $(elephant_flags "$DS")"; } \
    > "$DIR/.automahout-generated"
done; done

step "check with the server at $ELEPHANT_URL"
if ! curl -fs -m 5 "${ELEPHANT_URL}state/process" > /dev/null; then
  echo "not running: skipped (step 05 starts a local server and checks again)"; exit 0
fi
BAD=0
for DS in $DATASETS; do for SEQ in $SEQS; do
  R=$(curl -s -X POST -H "Content-Type: application/json" \
      -d "{\"dataset_name\":\"${DS}_$SEQ\",\"shape\":[$(elephant_shape "$DS" "$SEQ")]}" "${ELEPHANT_URL}dataset/check")
  echo "${DS}_$SEQ: $R"; case $R in *ready*) ;; *) BAD=1 ;; esac
done; done
exit $BAD
