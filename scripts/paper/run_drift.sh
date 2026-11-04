#!/usr/bin/env bash
# One drift run: Automahout's adaptive-tracking window loop on a CTC sequence, scored window by window
# (per_window.py) as well as for the whole movie.
#
#   scripts/paper/run_drift.sh new <dataset> <seq> <rep>
#   e.g. scripts/paper/run_drift.sh new Fluo-N2DL-HeLa 01 1
#
# The first argument is always `new` (it names the output folder, drift/<dataset>/<seq>/new/rep<rep>). The run
# starts from the code's default settings plus the language-model connection, with a model of its own created
# from random weights, and nobody answering questions.
#
# Environment: as run_benchmark.sh (BENCH_DATA, BENCH_OUT, ELEPHANT_URL, JAVA, MVN_OFFLINE); NEW_COMMIT (HEAD),
# WINDOW (10), REMOTE_SERVERS (the tool servers, comma-separated), PASSES_DISABLE (pass ids to switch off,
# comma-separated, e.g. lineage.converged_tracks; recorded in run-info.json), ORIGIN_DUMP (set: write the origin
# dump, and judge every removed spot against the truth with judge_removed.py). FROM/START/END/SAMPLES: a short
# run, see below.
set -euo pipefail
[ $# -eq 4 ] || { sed -n 2,16p "$0"; exit 1; }
VERSION=$1; DATASET=$2; SEQ=$3; REP=$4
REPO=$(cd "$(dirname "$0")/../.." && pwd)
. "$REPO/scripts/uv_project.sh"
BENCH_DATA=${BENCH_DATA:-$HOME/Repositories/CTC_dataset}
BENCH_OUT=${BENCH_OUT:-$HOME/automahout-bench-runs}
ELEPHANT_URL=${ELEPHANT_URL:-http://localhost:8080/}
JAVA=${JAVA:-java}
WINDOW=${WINDOW:-10}
[ "$VERSION" = new ] || { echo "the first argument must be new, not $VERSION" >&2; exit 1; }
COMMIT=$(git -C "$REPO" rev-parse --short "${NEW_COMMIT:-HEAD}")
RUN=drift_${DATASET}_${SEQ}_${VERSION}_rep${REP}
OUT=$BENCH_OUT/drift/$DATASET/$SEQ/$VERSION/rep$REP
XML=$BENCH_DATA/bdv/${DATASET}_${SEQ}.xml
GT=$BENCH_DATA/$DATASET/${SEQ}_GT
[ -f "$XML" ] || { echo "no $XML: convert it with scripts/paper/convert_ctc_to_bdv.py" >&2; exit 1; }
[ -d "$GT/TRA" ] || { echo "no CTC truth in $GT" >&2; exit 1; }
curl -fs -m 5 "${ELEPHANT_URL}state/process" > /dev/null || { echo "ELEPHANT server not reachable at $ELEPHANT_URL" >&2; exit 1; }
if [ -z "${NEW_COMMIT:-}" ] && [ -n "$(git -C "$REPO" status --porcelain -- src pom.xml)" ]; then
  echo "uncommitted changes under src/ or pom.xml: commit them (a run must name its code)" >&2; exit 1
fi

# Built from the commit, never from a working tree (a running Mastodon may be using one's target/).
BUILD=$BENCH_OUT/.build/$COMMIT
if [ ! -f "$BUILD/target/cp.txt" ]; then
  rm -rf "$BUILD"; mkdir -p "$BUILD"
  git -C "$REPO" archive "$COMMIT" | tar -x -C "$BUILD"
  (cd "$BUILD" && mvn -q ${MVN_OFFLINE:+-o} -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/cp.txt)
fi
CP=$BUILD/target/classes:$(cat "$BUILD/target/cp.txt")

rm -rf "$OUT"; mkdir -p "$OUT/home"
export AWS_SHARED_CREDENTIALS_FILE=${AWS_SHARED_CREDENTIALS_FILE:-$HOME/.aws/credentials}
export AWS_CONFIG_FILE=${AWS_CONFIG_FILE:-$HOME/.aws/config}
JOPTS=(-Xmx12g --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/javax.swing=ALL-UNNAMED
  -Duser.home="$OUT/home")

# A short run: FROM=<a saved run's directory> START=<t> END=<t> continues that run --
# its project, with t < START as history, and a copy of its model -- and tracks only START..END, by default with
# fixed steps and parameters (STEPS=agent lets the agent choose). START and END without FROM track START..END of a
# new project from a fresh model, with fixed steps too: for a model that has not seen the rest of the movie.
# SAMPLES=t1,t2,... (new project, fresh model) tracks only those frames, each on its own, for judging detection.
SHORT=()
if [ -n "${SAMPLES:-}" ]; then
  [ -z "${FROM:-}" ] || { echo "SAMPLES needs no FROM" >&2; exit 1; }
  SHORT=(samples="$SAMPLES" steps=fixed)
elif [ -z "${FROM:-}" ] && [ -n "${START:-}" ] && [ -n "${END:-}" ]; then
  SHORT=(start="$START" end="$END" steps="${STEPS:-fixed}")
fi
if [ -n "${FROM:-}" ]; then
  [ -n "${START:-}" ] && [ -n "${END:-}" ] || { echo "FROM needs START and END" >&2; exit 1; }
  MODEL_FROM=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['elephant_settings']['getDetectionModelName'])" "$FROM/run-info.json")
  SHORT=(project="$FROM/project.mastodon" model_from="$MODEL_FROM" start="$START" end="$END" steps="${STEPS:-fixed}")
fi
"$JAVA" "${JOPTS[@]}" -Dautomahout.prefs.root="automahout-bench/$RUN" -Dautomahout.remote.servers="${REMOTE_SERVERS:-}" \
  -Dautomahout.passes.disable="${PASSES_DISABLE:-}" ${ORIGIN_DUMP:+-Dautomahout.originDump="$OUT/origin.tsv"} \
  -cp "$CP" org.mastodon.mamut.automahout.bench.BenchmarkRunner \
  xml="$XML" out="$OUT" dataset="${DATASET}_${SEQ}" run="$RUN" condition=A server="$ELEPHANT_URL" commit="$COMMIT" \
  ${SHORT[@]+"${SHORT[@]}"} > "$OUT/stdout.log" 2>&1 || echo "runner exited with $? (see $OUT/stdout.log)" >&2

uvp "$REPO/scripts/paper" run --quiet python "$REPO/scripts/paper/evaluate_ctc.py" "$OUT/RES" "$GT" \
  --json "$OUT/metrics.json" || true
uvp "$REPO/scripts/paper" run --quiet python "$REPO/scripts/paper/per_window.py" "$OUT/RES" "$GT" \
  --window "$WINDOW" --csv "$OUT/frames.csv" --json "$OUT/windows.json"
REMOVED=$(ls "$OUT"/origin*.removed_spots.tsv 2>/dev/null | head -1 || true)  # none without ORIGIN_DUMP: not a failure
if [ -n "$REMOVED" ]; then
  uvp "$REPO/scripts/paper" run --quiet python "$REPO/scripts/paper/judge_removed.py" "$REMOVED" "$GT" "$OUT/RES" "$XML" \
    --window "$WINDOW" > "$OUT/removed_judged.txt"
fi
