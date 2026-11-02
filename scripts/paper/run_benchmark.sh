#!/usr/bin/env bash
# One benchmark run (docs/paper/benchmark-protocol.md): track one CTC sequence under one condition, export the
# CTC result files and score them.
#
#   scripts/paper/run_benchmark.sh <dataset> <seq> <condition> <rep>
#   e.g. scripts/paper/run_benchmark.sh Fluo-N2DL-HeLa 01 A 1
#
# Needs: uv (the scoring environment is scripts/paper/pyproject.toml + uv.lock), the local ELEPHANT server running with the dataset generated (scripts/elephant/), the remote tool
# server for conditions that use pretrained detectors (examples/remote-tools/server.py), the converted movie
# (scripts/paper/convert_ctc_to_bdv.py) and the CTC truth. Each run is isolated: its own home directory (no
# per-user state files) and its own settings node (the code's defaults; only the language-model connection is
# copied from the user's settings).
#
# Environment: BENCH_DATA (~/Repositories/CTC_dataset), BENCH_OUT (~/automahout-bench-runs),
# ELEPHANT_URL (http://localhost:8080/), REMOTE_TOOLS (http://127.0.0.1:8765), JAVA (java), MVN_OFFLINE (set: build
# offline from the local Maven repository). The language model: AUTOMAHOUT_VLM_* (scripts/reproduce/README.md).
set -euo pipefail
[ $# -eq 4 ] || { sed -n 2,16p "$0"; exit 1; }
DATASET=$1; SEQ=$2; CONDITION=$3; REP=$4
REPO=$(cd "$(dirname "$0")/../.." && pwd)
. "$REPO/scripts/uv_project.sh"
. "$REPO/scripts/commit_id.sh"
BENCH_DATA=${BENCH_DATA:-$HOME/Repositories/CTC_dataset}
BENCH_OUT=${BENCH_OUT:-$HOME/automahout-bench-runs}
ELEPHANT_URL=${ELEPHANT_URL:-http://localhost:8080/}
REMOTE_TOOLS=${REMOTE_TOOLS:-http://127.0.0.1:8765}
JAVA=${JAVA:-java}
RUN=${DATASET}_${SEQ}_${CONDITION}_rep${REP}
OUT=$BENCH_OUT/$DATASET/$SEQ/$CONDITION/rep$REP
XML=$BENCH_DATA/bdv/${DATASET}_${SEQ}.xml
GT=$BENCH_DATA/$DATASET/${SEQ}_GT
[ -f "$XML" ] || { echo "no $XML: convert it with scripts/paper/convert_ctc_to_bdv.py" >&2; exit 1; }
[ -d "$GT/TRA" ] || { echo "no CTC truth in $GT" >&2; exit 1; }
curl -fs -m 5 "${ELEPHANT_URL}state/process" > /dev/null || { echo "ELEPHANT server not reachable at $ELEPHANT_URL (scripts/elephant/launch_local.sh)" >&2; exit 1; }

# A: autonomous. AO: A, with the questions asked and the truth answering. E: ELEPHANT's adaptive tracking, the
# pretrained detectors used only to seed its training (their persisting detections, not intensity maxima).
# P: A with every correction pass off (detection + linking only).
EXTRA=(); DISABLE=""; SEEDS_ONLY=false
case $CONDITION in
  A) SERVERS=$REMOTE_TOOLS ;;
  AO) SERVERS=$REMOTE_TOOLS; EXTRA=(gt="$GT/TRA") ;;
  E) SERVERS=$REMOTE_TOOLS; SEEDS_ONLY=true ;;
  P) SERVERS=$REMOTE_TOOLS; DISABLE=all ;;
  *) echo "condition $CONDITION is not one of A, AO, E, P" >&2; exit 1 ;;
esac

# Build from a commit, not from the working tree: the run then names exactly the code it ran (and never
# touches the repository's own target/, which a running Mastodon may be using). BENCH_COMMIT (HEAD) picks it;
# a working tree with uncommitted source changes is refused unless BENCH_ALLOW_DIRTY=1.
# A copy made with `git archive` (no .git) is that commit by construction: it is built as it is, named by
# the hash git archive wrote into .archive-commit. Editing such a copy would make the name wrong.
if is_clone "$REPO"; then
  COMMIT=$(git -C "$REPO" rev-parse --short "${BENCH_COMMIT:-HEAD}")
  if [ -n "$(git -C "$REPO" status --porcelain -- src pom.xml)" ] && [ "${BENCH_ALLOW_DIRTY:-0}" != 1 ] && [ -z "${BENCH_COMMIT:-}" ]; then
    echo "uncommitted changes under src/ or pom.xml: commit them (a benchmark run must name its code)" >&2; exit 1
  fi
else
  COMMIT=$(automahout_commit "$REPO") || {
    echo "$REPO is neither a git clone nor a git archive copy: a benchmark run must name its code" >&2; exit 1; }
  [ -z "${BENCH_COMMIT:-}" ] || { echo "BENCH_COMMIT needs a git clone" >&2; exit 1; }
fi
BUILD=$BENCH_OUT/.build/$COMMIT
if [ ! -f "$BUILD/target/cp.txt" ]; then
  rm -rf "$BUILD"; mkdir -p "$BUILD"
  if is_clone "$REPO"; then git -C "$REPO" archive "$COMMIT" | tar -x -C "$BUILD"
  else (cd "$REPO" && tar -cf - pom.xml src) | tar -x -C "$BUILD"; fi
  (cd "$BUILD" && mvn -q ${MVN_OFFLINE:+-o} -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/cp.txt)
fi
CP=$BUILD/target/classes:$(cat "$BUILD/target/cp.txt")

rm -rf "$OUT"; mkdir -p "$OUT/home"
# The real AWS configuration (the language model), since the run's home is its own.
export AWS_SHARED_CREDENTIALS_FILE=${AWS_SHARED_CREDENTIALS_FILE:-$HOME/.aws/credentials}
export AWS_CONFIG_FILE=${AWS_CONFIG_FILE:-$HOME/.aws/config}
# The runner opens a BigDataViewer window (the language model's screenshots are rendered from it): on a Linux
# server without a display, it runs inside a virtual one (Xvfb).
WRAP=()
if [ "$(uname)" = Linux ] && [ -z "${DISPLAY:-}" ]; then
  command -v xvfb-run > /dev/null || { echo "no display, and no xvfb-run to make one (install Xvfb)" >&2; exit 1; }
  WRAP=(xvfb-run -a -s "-screen 0 1920x1080x24")
fi
${WRAP[@]+"${WRAP[@]}"} "$JAVA" -Xmx12g \
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/javax.swing=ALL-UNNAMED \
  -Duser.home="$OUT/home" -Dautomahout.prefs.root="automahout-bench/$RUN" \
  -Dautomahout.remote.servers="$SERVERS" -Dautomahout.passes.disable="$DISABLE" \
  -Dautomahout.detector.seedsOnly="$SEEDS_ONLY" \
  -cp "$CP" org.mastodon.mamut.automahout.bench.BenchmarkRunner \
  xml="$XML" out="$OUT" dataset="${DATASET}_${SEQ}" run="$RUN" condition="$CONDITION" server="$ELEPHANT_URL" \
  commit="$COMMIT" ${EXTRA[@]+"${EXTRA[@]}"} \
  > "$OUT/stdout.log" 2>&1 || STATUS=$?
if [ "${STATUS:-0}" -ne 0 ]; then
  echo "runner exited with $STATUS (see $OUT/stdout.log, $OUT/run-info.json)" >&2
fi
uvp "$REPO/scripts/paper" run --quiet python "$REPO/scripts/paper/evaluate_ctc.py" "$OUT/RES" "$GT" \
  --json "$OUT/metrics.json"
