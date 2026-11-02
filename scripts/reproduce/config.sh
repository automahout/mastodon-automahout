# Shared settings of the reproduction scripts (sourced by each step, not run). Every value can be overridden
# from the environment; docs: scripts/reproduce/README.md.

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
. "$REPO/scripts/uv_project.sh"
. "$REPO/scripts/commit_id.sh"
export BENCH_VENV_ROOT=${BENCH_VENV_ROOT:-}  # local disk for the Python environments (see uv_project.sh)
export BENCH_DATA=${BENCH_DATA:-$HOME/automahout-ctc-data}    # CTC zips, sequences, truth, bdv/ conversions
export BENCH_OUT=${BENCH_OUT:-$HOME/automahout-bench-runs}    # one folder per run; summary/
export ELEPHANT_URL=${ELEPHANT_URL:-http://localhost:8080/}
export REMOTE_TOOLS=${REMOTE_TOOLS:-http://127.0.0.1:8765}
export ELEPHANT_HOME=${ELEPHANT_HOME:-$HOME/.automahout/elephant}
export ELEPHANT_WORKSPACE=${ELEPHANT_WORKSPACE:-$ELEPHANT_HOME/workspace}
DATASETS=${DATASETS:-"Fluo-N2DL-HeLa PhC-C2DL-PSC Fluo-N3DH-CE"}
SEQS=${SEQS:-"01 02"}                 # 01 development, 02 held-out (docs/paper/benchmark-protocol.md)
CONDITIONS=${CONDITIONS:-"A AO E P"}  # scripts/paper/run_benchmark.sh; baseline T is step 04
REPS=${REPS:-3}
CTC_URL=${CTC_URL:-https://data.celltrackingchallenge.net/training-datasets}

# Per dataset: voxel size in µm (x y z; a 2D movie's z is its x), the converter's and ELEPHANT's dataset
# generator's flags, the training zip's SHA-256 (as downloaded on 2026-10-13; the server's file is dated
# 2024-01-16) and ELEPHANT's dataset shape per sequence (t [z] y x).
voxel_size() {
  case $1 in
    Fluo-N2DL-HeLa) echo "0.645 0.645 0.645" ;;
    PhC-C2DL-PSC) echo "1.6 1.6 1.6" ;;
    Fluo-N3DH-CE) echo "0.09 0.09 1.0" ;;
    *) echo "unknown dataset $1" >&2; return 1 ;;
  esac
}
convert_flags() { case $1 in Fluo-N3DH-CE) echo "--gzip" ;; *) echo "" ;; esac; }  # 3D: ~9 GB uncompressed
elephant_flags() {
  case $1 in
    Fluo-N2DL-HeLa) echo "--2d --uint16" ;;  # values ~33000-36000 do not survive 8 bits
    PhC-C2DL-PSC) echo "--2d" ;;
    *) echo "" ;;
  esac
}
zip_sha256() {
  case $1 in
    Fluo-N2DL-HeLa) echo "35dd99d58e071aba0b03880128d920bd1c063783cc280f9531fbdc5be614c82e" ;;
    PhC-C2DL-PSC) echo "9d54bb8febc8798934a21bf92e05d92f5e8557c87e28834b2832591cdda78422" ;;
    Fluo-N3DH-CE) echo "eb3d37cacb3b51d3a427a0c43deaaf1d60cf2eba009f12569e720c2c2fd1e04c" ;;
  esac
}
elephant_shape() {
  case $1_$2 in
    Fluo-N2DL-HeLa_0[12]) echo "92,700,1100" ;;
    PhC-C2DL-PSC_0[12]) echo "300,576,720" ;;
    Fluo-N3DH-CE_01) echo "250,35,512,708" ;;
    Fluo-N3DH-CE_02) echo "250,31,512,712" ;;
  esac
}

step() { printf '\n== %s ==\n' "$*"; }
sha256() { if command -v sha256sum > /dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
