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
# DETECTOR: the detector to use instead of the automatic choice -- a tool id from a tool server in REMOTE_SERVERS
# (e.g. cellpose.cpsam), or elephant. The automatic choice judges 2D fluorescence by its bright regions, calibrated
# on one sparse culture; on dense tissue it can refuse a detector you would take.
#
# Progress shows in Automahout's log window, as when run from Fiji, beside the image; its Stop button stops the run.
#
# RECORD=<file.mp4> records the screen to a video with ffmpeg while the run lasts (RECORD_FPS, default 5). Without a
# display (a Linux server), the run gets a virtual one (Xvfb, RECORD_SIZE, default 1920x1080) and the video shows
# it. On macOS the terminal needs the Screen Recording permission (System Settings > Privacy & Security).
#
# Results in $DEMO_OUT/<run name>/: project.mastodon (open it in Mastodon), RES/ (the tracks in the Cell Tracking
# Challenge format), run.log (what Automahout measured, decided and corrected), run-info.json, stdout.log.
set -euo pipefail
[ $# -ge 2 ] && [ $# -le 3 ] || { sed -n 2,30p "$0"; exit 1; }
[ -f "$1" ] || { echo "no $1" >&2; exit 1; }
XML=$(cd "$(dirname "$1")" && pwd)/$(basename "$1"); DATASET=$2
NAME=${3:-${DATASET}_$(date +%Y%m%d-%H%M%S)}
REPO=$(cd "$(dirname "$0")/../.." && pwd)
DEMO_OUT=${DEMO_OUT:-$HOME/automahout-demo-runs}
ELEPHANT_URL=${ELEPHANT_URL:-http://localhost:8080/}
JAVA=${JAVA:-java}
OUT=$DEMO_OUT/$NAME
[ -f "$XML" ] || { echo "no $XML" >&2; exit 1; }
# Readable by this process: macOS guards cloud-storage folders (Box, Dropbox, iCloud under ~/Library/CloudStorage)
# per app, and a refusal surfaced only later, inside Java, as "FileNotFoundException ... (Operation not permitted)".
head -c 1 "$XML" > /dev/null 2>&1 || { echo "cannot read $XML (macOS may be blocking access to a cloud-storage" \
  "folder): copy the .xml and .h5 to a local folder, or allow the terminal access to it" >&2; exit 1; }
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
if [ -n "${RECORD:-}" ]; then
  command -v ffmpeg > /dev/null || { echo "RECORD needs ffmpeg" >&2; exit 1; }
  case "$RECORD" in /*) ;; *) RECORD=$PWD/$RECORD ;; esac
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

# The recording, and a virtual display when there is none: started here, stopped (and the video closed) on exit.
XVFB_PID=; FFMPEG_PID=
stop_recording() {
  if [ -n "$FFMPEG_PID" ]; then
    # SIGINT lets ffmpeg close the file; a capture that has stalled (the screen asleep, say) may not answer it, and
    # a bare wait then kept the script running after the run had ended. 10 s, then TERM, then KILL.
    kill -INT "$FFMPEG_PID" 2> /dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$FFMPEG_PID" 2> /dev/null || break; sleep 1; done
    if kill -0 "$FFMPEG_PID" 2> /dev/null; then
      kill -TERM "$FFMPEG_PID" 2> /dev/null || true; sleep 3
      kill -KILL "$FFMPEG_PID" 2> /dev/null || true
      echo "ffmpeg did not stop on its own: $RECORD may be incomplete" >&2
    fi
    wait "$FFMPEG_PID" 2> /dev/null || true
    echo "video: $RECORD"; fi
  [ -z "$XVFB_PID" ] || kill "$XVFB_PID" 2> /dev/null || true
}
trap stop_recording EXIT
if [ -n "${RECORD:-}" ]; then
  FPS=${RECORD_FPS:-5}
  if [ "$(uname)" = Darwin ]; then
    # Listing the devices always ends in an error status (there is no input to open): only its text is read.
    SCREEN=$( { ffmpeg -hide_banner -f avfoundation -list_devices true -i "" 2>&1 || true; } \
      | sed -n 's/.*\[\([0-9]*\)\] Capture screen 0.*/\1/p' | head -1)
    [ -n "$SCREEN" ] || { echo "RECORD: ffmpeg finds no screen to capture" >&2; exit 1; }
    ffmpeg -hide_banner -loglevel error -f avfoundation -pixel_format uyvy422 -framerate 30 -capture_cursor 1 -i "$SCREEN:none" -r "$FPS" \
      -vf "scale=1920:-2" -c:v libx264 -preset ultrafast -pix_fmt yuv420p -y "$RECORD" < /dev/null > "$OUT/ffmpeg.log" 2>&1 &
    FFMPEG_PID=$!
  else
    SIZE=${RECORD_SIZE:-1920x1080}
    if [ -z "${DISPLAY:-}" ]; then
      command -v Xvfb > /dev/null || { echo "RECORD without a display needs Xvfb (e.g. apt install xvfb)" >&2; exit 1; }
      N=99; while [ -e /tmp/.X11-unix/X$N ] || [ -e /tmp/.X$N-lock ]; do N=$((N + 1)); done
      Xvfb ":$N" -screen 0 "${SIZE}x24" -nolisten tcp > "$OUT/xvfb.log" 2>&1 & XVFB_PID=$!
      export DISPLAY=":$N"; sleep 2
    fi
    ffmpeg -hide_banner -loglevel error -f x11grab -video_size "$SIZE" -framerate "$FPS" -i "$DISPLAY" \
      -c:v libx264 -preset ultrafast -pix_fmt yuv420p -y "$RECORD" < /dev/null > "$OUT/ffmpeg.log" 2>&1 &
    FFMPEG_PID=$!
  fi
  sleep 2
  kill -0 "$FFMPEG_PID" 2> /dev/null || { FFMPEG_PID=; echo "RECORD: ffmpeg did not start (see $OUT/ffmpeg.log)" >&2; exit 1; }
  echo "recording the screen to $RECORD"
fi

echo "tracking $XML (ELEPHANT dataset $DATASET) into $OUT; log: $OUT/stdout.log"
"$JAVA" "${JOPTS[@]}" -Dautomahout.prefs.root="automahout-demo/$NAME" -Dautomahout.remote.servers="${REMOTE_SERVERS:-}" \
  -Dautomahout.logWindow=true -Dautomahout.detector="${DETECTOR:-}" \
  -cp "$CP" org.mastodon.mamut.automahout.bench.BenchmarkRunner \
  xml="$XML" out="$OUT" dataset="$DATASET" run="demo_$NAME" condition=A server="$ELEPHANT_URL" commit="$COMMIT" \
  ${SHORT[@]+"${SHORT[@]}"} > "$OUT/stdout.log" 2>&1 \
  && echo "done: open $OUT/project.mastodon in Mastodon" \
  || { echo "the run exited with $? (see $OUT/stdout.log)" >&2; exit 1; }
