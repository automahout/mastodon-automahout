#!/usr/bin/env bash
# One drift-comparison run: Automahout's adaptive-tracking window loop on a CTC sequence, by the current
# version or by good-20260930, scored window by window (per_window.py) as well as for the whole movie.
#
#   scripts/paper/run_drift.sh <new|good> <dataset> <seq> <rep>
#   e.g. scripts/paper/run_drift.sh new Fluo-N2DL-HeLa 01 1
#
# Both versions run the ELEPHANT window loop only (no pretrained detectors: good-20260930 has none), from the
# code's default settings plus the language-model connection, with a model of the run's own created from random
# weights, and nobody answering questions. good-20260930 runs through its LegacyRunner (branch
# bench/good-20260930), which presses Start on its pre-flight dialog; it does not measure ELEPHANT's radii and
# linking distance from the image, so it is given the values the current version measured for the same
# sequence and rep (run `new` first), or ELEPHANT_MIN_RADIUS / ELEPHANT_MAX_RADIUS / ELEPHANT_NN_LINKING.
#
# Environment: as run_benchmark.sh (BENCH_DATA, BENCH_OUT, ELEPHANT_URL, JAVA, MVN_OFFLINE); NEW_COMMIT (HEAD),
# GOOD_COMMIT (bench/good-20260930), WINDOW (10), PASSES_DISABLE (for `new`: pass ids to switch off, comma-separated,
# e.g. lineage.converged_tracks; recorded in run-info.json), ORIGIN_DUMP (set: write the origin dump, and judge
# every removed spot against the truth with judge_removed.py). FROM/START/END: a short run, see below. The language model for good-20260930: AUTOMAHOUT_VLM_* or the
# user's Automahout settings.
set -euo pipefail
[ $# -eq 4 ] || { sed -n 2,17p "$0"; exit 1; }
VERSION=$1; DATASET=$2; SEQ=$3; REP=$4
REPO=$(cd "$(dirname "$0")/../.." && pwd)
. "$REPO/scripts/uv_project.sh"
BENCH_DATA=${BENCH_DATA:-$HOME/Repositories/CTC_dataset}
BENCH_OUT=${BENCH_OUT:-$HOME/automahout-bench-runs}
ELEPHANT_URL=${ELEPHANT_URL:-http://localhost:8080/}
JAVA=${JAVA:-java}
WINDOW=${WINDOW:-10}
case $VERSION in
  new) COMMIT=$(git -C "$REPO" rev-parse --short "${NEW_COMMIT:-HEAD}") ;;
  good) COMMIT=$(git -C "$REPO" rev-parse --short "${GOOD_COMMIT:-bench/good-20260930}") ;;
  *) echo "version $VERSION is not one of new, good" >&2; exit 1 ;;
esac
RUN=drift_${DATASET}_${SEQ}_${VERSION}_rep${REP}
OUT=$BENCH_OUT/drift/$DATASET/$SEQ/$VERSION/rep$REP
XML=$BENCH_DATA/bdv/${DATASET}_${SEQ}.xml
GT=$BENCH_DATA/$DATASET/${SEQ}_GT
[ -f "$XML" ] || { echo "no $XML: convert it with scripts/paper/convert_ctc_to_bdv.py" >&2; exit 1; }
[ -d "$GT/TRA" ] || { echo "no CTC truth in $GT" >&2; exit 1; }
curl -fs -m 5 "${ELEPHANT_URL}state/process" > /dev/null || { echo "ELEPHANT server not reachable at $ELEPHANT_URL" >&2; exit 1; }
if [ "$VERSION" = new ] && [ -z "${NEW_COMMIT:-}" ] && [ -n "$(git -C "$REPO" status --porcelain -- src pom.xml)" ]; then
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

# A short run (current version only): FROM=<a saved run's directory> START=<t> END=<t> continues that run --
# its project, with t < START as history, and a copy of its model -- and tracks only START..END, by default with
# fixed steps and parameters (STEPS=agent lets the agent choose). START and END without FROM track START..END of a
# new project from a fresh model, with fixed steps too: for a model that has not seen the rest of the movie.
# SAMPLES=t1,t2,... (new project, fresh model) tracks only those frames, each on its own, for judging detection.
SHORT=()
if [ -n "${SAMPLES:-}" ]; then
  [ "$VERSION" = new ] && [ -z "${FROM:-}" ] || { echo "SAMPLES needs version new and no FROM" >&2; exit 1; }
  SHORT=(samples="$SAMPLES" steps=fixed)
elif [ -z "${FROM:-}" ] && [ -n "${START:-}" ] && [ -n "${END:-}" ]; then
  [ "$VERSION" = new ] || { echo "START and END need version new" >&2; exit 1; }
  SHORT=(start="$START" end="$END" steps="${STEPS:-fixed}")
fi
if [ -n "${FROM:-}" ]; then
  [ "$VERSION" = new ] && [ -n "${START:-}" ] && [ -n "${END:-}" ] || { echo "FROM needs START, END and version new" >&2; exit 1; }
  MODEL_FROM=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['elephant_settings']['getDetectionModelName'])" "$FROM/run-info.json")
  SHORT=(project="$FROM/project.mastodon" model_from="$MODEL_FROM" start="$START" end="$END" steps="${STEPS:-fixed}")
fi
if [ "$VERSION" = new ]; then
  "$JAVA" "${JOPTS[@]}" -Dautomahout.prefs.root="automahout-bench/$RUN" -Dautomahout.remote.servers="${REMOTE_SERVERS:-}" \
    -Dautomahout.passes.disable="${PASSES_DISABLE:-}" ${ORIGIN_DUMP:+-Dautomahout.originDump="$OUT/origin.tsv"} \
    -cp "$CP" org.mastodon.mamut.automahout.bench.BenchmarkRunner \
    xml="$XML" out="$OUT" dataset="${DATASET}_${SEQ}" run="$RUN" condition=A server="$ELEPHANT_URL" commit="$COMMIT" \
    ${SHORT[@]+"${SHORT[@]}"} > "$OUT/stdout.log" 2>&1 || echo "runner exited with $? (see $OUT/stdout.log)" >&2
else
  # The language-model connection: the environment, or the user's own Automahout settings.
  PREFS=$(uvp "$REPO/scripts/paper" run --quiet python - <<'EOF'
import os, plistlib, re
keys = ["vlmBackend", "vlmBaseUrl", "vlmAwsRegion", "vlmModel", "vlmApiKey"]
stored = {}
path = os.path.expanduser("~/Library/Preferences/org.mastodon.mamut.plist")
if os.path.exists(path):
    with open(path, "rb") as f:
        stored = plistlib.load(f).get("/org/mastodon/mamut/", {}).get("automahout/", {}).get("plugin/", {})
for k in keys:
    v = os.environ.get("AUTOMAHOUT_" + re.sub(r"([a-z])([A-Z])", r"\1_\2", k).upper()) or stored.get(k)
    if v:
        print(f"pref.{k}={v}")
EOF
)
  NEWINFO=$BENCH_OUT/drift/$DATASET/$SEQ/new/rep$REP/run-info.json
  read -r MINR MAXR NNL < <(uvp "$REPO/scripts/paper" run --quiet python - "$NEWINFO" <<'EOF'
import json, os, sys
env = [os.environ.get(k, "") for k in ("ELEPHANT_MIN_RADIUS", "ELEPHANT_MAX_RADIUS", "ELEPHANT_NN_LINKING")]
if all(env):
    print(*env)
elif os.path.exists(sys.argv[1]):
    s = json.load(open(sys.argv[1]))["elephant_settings"]
    print(s["getMinRadius"], s["getMaxRadius"], s["getNNLinkingThreshold"])
else:
    print("- - -")
EOF
)
  [ "$MINR" != - ] || { echo "no ELEPHANT radii: run 'new' for $DATASET $SEQ rep$REP first, or set ELEPHANT_MIN_RADIUS etc." >&2; exit 1; }
  PREFARGS=()
  while IFS= read -r line; do [ -z "$line" ] || PREFARGS+=("$line"); done <<< "$PREFS"
  "$JAVA" "${JOPTS[@]}" -Djava.util.prefs.PreferencesFactory=org.mastodon.mamut.automahout.bench.MemoryPreferencesFactory \
    -cp "$CP" org.mastodon.mamut.automahout.plugin.LegacyRunner \
    xml="$XML" out="$OUT" dataset="${DATASET}_${SEQ}" run="$RUN" server="$ELEPHANT_URL" commit="$COMMIT" \
    elephant.minRadius="$MINR" elephant.maxRadius="$MAXR" elephant.nnLinkingThreshold="$NNL" \
    ${PREFARGS[@]+"${PREFARGS[@]}"} > "$OUT/stdout.log" 2>&1 || echo "runner exited with $? (see $OUT/stdout.log)" >&2
fi

uvp "$REPO/scripts/paper" run --quiet python "$REPO/scripts/paper/evaluate_ctc.py" "$OUT/RES" "$GT" \
  --json "$OUT/metrics.json" || true
uvp "$REPO/scripts/paper" run --quiet python "$REPO/scripts/paper/per_window.py" "$OUT/RES" "$GT" \
  --window "$WINDOW" --csv "$OUT/frames.csv" --json "$OUT/windows.json"
REMOVED=$(ls "$OUT"/origin*.removed_spots.tsv 2>/dev/null | head -1 || true)  # none without ORIGIN_DUMP: not a failure
if [ -n "$REMOVED" ]; then
  uvp "$REPO/scripts/paper" run --quiet python "$REPO/scripts/paper/judge_removed.py" "$REMOVED" "$GT" "$OUT/RES" "$XML" \
    --window "$WINDOW" > "$OUT/removed_judged.txt"
fi
