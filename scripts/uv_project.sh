# Sourced, not run: uvp <project dir> <uv command> [args...] runs `uv <command> --project <dir> [args...]`.
# Each project keeps its own environment: <dir>/.venv (uv's default), or, when BENCH_VENV_ROOT is set,
# $BENCH_VENV_ROOT/<project path with / as ->. Set it to a local disk when the repository is on a network
# filesystem (NFS): Python reads thousands of small files at import, and TensorFlow's import alone took
# minutes there. UV_CACHE_DIR (uv's own setting) moves uv's download cache the same way.
uvp() {
  local dir=$1 cmd=$2; shift 2
  local env=$dir/.venv
  if [ -n "${BENCH_VENV_ROOT:-}" ]; then
    local root; root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
    env=$BENCH_VENV_ROOT/$(cd "$dir" && pwd | sed "s#^$root/##" | tr / -)
  fi
  UV_PROJECT_ENVIRONMENT=$env uv "$cmd" --project "$dir" "$@"
}
