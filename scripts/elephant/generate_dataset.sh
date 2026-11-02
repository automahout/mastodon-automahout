#!/usr/bin/env bash
# Generate an ELEPHANT dataset (zarr) from a BigDataViewer .h5, with elephant-server's own
# script/dataset_generator.py, into the local server's workspace (see launch_local.sh).
#
#   scripts/elephant/generate_dataset.sh <file.h5> <dataset name> [--2d] [--uint16] [--divisor N]
#
# The dataset name is what ELEPHANT's main settings name as "dataset name". Use --2d for a 2D movie and --uint16
# for 16-bit data whose range would not survive 8-bit (e.g. Fluo-N2DL-HeLa: ~33000-36000).
set -euo pipefail
[ $# -ge 2 ] || { sed -n 2,8p "$0"; exit 1; }
H5=$1; NAME=$2; shift 2
ELEPHANT_HOME=${ELEPHANT_HOME:-$HOME/.automahout/elephant}
ELEPHANT_WORKSPACE=${ELEPHANT_WORKSPACE:-$ELEPHANT_HOME/workspace}
PY=$ELEPHANT_HOME/venv/bin/python
[ -x "$PY" ] || { echo "run scripts/elephant/launch_local.sh --setup first" >&2; exit 1; }
mkdir -p "$ELEPHANT_WORKSPACE/datasets"
"$PY" "$ELEPHANT_HOME/elephant-server/script/dataset_generator.py" "$H5" "$ELEPHANT_WORKSPACE/datasets/$NAME" "$@"
echo "dataset '$NAME' in $ELEPHANT_WORKSPACE/datasets/$NAME"
