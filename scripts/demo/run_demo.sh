#!/usr/bin/env bash
# A demo: Automahout tracks a movie of your own, unattended, in a Mastodon window that opens by itself. No ground
# truth is needed or used; nothing is scored.
#
#   scripts/demo/run_demo.sh <movie.xml> <ELEPHANT dataset name> [run name]
#   e.g. START=0 END=30 scripts/demo/run_demo.sh ~/data/embryo.xml embryo
#
# <movie.xml> is a BigDataViewer XML (+ its .h5), with the voxel size in micrometres. The ELEPHANT server must hold
# the same movie as a dataset of that name (scripts/elephant/generate_dataset.sh, on the machine the server runs
# on). The run starts from the code's default settings plus your language-model connection (Automahout Settings,
# or the environment), with a detection model of its own created from random weights, and nobody answering
# questions.
#
# Environment: ELEPHANT_URL (http://localhost:8080/), DEMO_OUT (~/automahout-demo-runs), JAVA (java),
# REMOTE_SERVERS (the Cellpose/StarDist tool servers, comma-separated), MVN_OFFLINE (set: build offline).
# START and END (both): track only that range, from a fresh model, with fixed steps (STEPS=agent lets the agent
# choose); without them the agent tracks the whole movie, which can take hours.
#
# Progress shows in Automahout's log window, as when run from Fiji; its Stop button stops the run.
#
# Results in $DEMO_OUT/<run name>/: project.mastodon (open it in Mastodon), RES/ (the tracks in the Cell Tracking
# Challenge format), run.log (what Automahout measured, decided and corrected), run-info.json, stdout.log.
set -euo pipefail
[ $# -ge 2 ] && [ $# -le 3 ] || { sed -n 2,22p "$0"; exit 1; }
[ -f "$1" ] || { echo "no $1" >&2; exit 1; }
XML=$(cd "$(dirname "$1")" && pwd)/$(basename "$1"); DATASET=$2
NAME=${3:-${DATASET}_$(date +%Y%m%d-%H%M%S)}
REPO=$(cd "$(dirname "$0")/../.." && pwd)
DEMO_OUT=${DEMO_OUT:-$HOME/automahout-demo-runs}
ELEPHANT_URL=${ELEPHANT_URL:-http://localhost:8080/}
JAVA=${JAVA:-java}
OUT=$DEMO_OUT/$NAME
[ -f "$XML" ] || { echo "no $XML" >&2; exit 1; }
[ -f "${XML%.xml}.h5" ] || echo "note: no ${XML%.xml}.h5 beside the XML; the XML must name where its data is" >&2
curl -fs -m 5 "${ELEPHANT_URL}state/process" > /dev/null || { echo "ELEPHANT server not reachable at $ELEPHANT_URL" >&2; exit 1; }
# Whether the server holds the dataset: an empty shape never matches, so a dataset that exists answers "Invalid
# shape", one that does not "not found or broken".
if curl -fs -m 10 -H 'Content-Type: application/json' -d "{\"dataset_name\":\"$DATASET\",\"shape\":[0]}" \
    "${ELEPHANT_URL}dataset/check" | grep -q "not found or broken"; then
  echo "the ELEPHANT server at $ELEPHANT_URL has no dataset '$DATASET': generate it there from the .h5" \
    "(scripts/elephant/generate_dataset.sh), or give the name it was generated under" >&2; exit 1
fi
if [ -n "${START:-}${END:-}" ] && { [ -z "${START:-}" ] || [ -z "${END:-}" ]; }; then
  echo "give both START and END, or neither" >&2; exit 1
fi

# Built from the commit, never from a working tree (a running Mastodon may be using one's target/).
COMMIT=$(git -C "$REPO" rev-parse --short HEAD)
[ -z "$(git -C "$REPO" status --porcelain -- src pom.xml)" ] \
  || echo "note: uncommitted changes under src/ or pom.xml are not in this run (it builds $COMMIT)" >&2
BUILD=$DEMO_OUT/.build/$COMMIT
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
SHORT=()
[ -z "${START:-}" ] || SHORT=(start="$START" end="$END" steps="${STEPS:-fixed}")

echo "tracking $XML (ELEPHANT dataset $DATASET) into $OUT; log: $OUT/stdout.log"
"$JAVA" "${JOPTS[@]}" -Dautomahout.prefs.root="automahout-demo/$NAME" -Dautomahout.remote.servers="${REMOTE_SERVERS:-}" \
  -Dautomahout.logWindow=true \
  -cp "$CP" org.mastodon.mamut.automahout.bench.BenchmarkRunner \
  xml="$XML" out="$OUT" dataset="$DATASET" run="demo_$NAME" condition=A server="$ELEPHANT_URL" commit="$COMMIT" \
  ${SHORT[@]+"${SHORT[@]}"} > "$OUT/stdout.log" 2>&1 \
  && echo "done: open $OUT/project.mastodon in Mastodon" \
  || { echo "the run exited with $? (see $OUT/stdout.log)" >&2; exit 1; }
