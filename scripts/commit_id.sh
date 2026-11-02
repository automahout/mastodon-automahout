# Sourced, not run: automahout_commit <repository> prints the short commit hash the code is from. A clone
# answers through git. A copy made with `git archive` (no .git) answers through .archive-commit, which git
# archive fills in (export-subst, .gitattributes). Fails for anything else.
automahout_commit() {
  local top
  top=$(git -C "$1" rev-parse --show-toplevel 2>/dev/null) || top=""
  if [ -n "$top" ] && [ "$(cd "$top" && pwd -P)" = "$(cd "$1" && pwd -P)" ]; then
    git -C "$1" rev-parse --short HEAD; return
  fi
  local c; c=$(cat "$1/.archive-commit" 2>/dev/null) || return 1
  case $c in *Format*|"") return 1 ;; esac
  echo "${c:0:7}"
}
# Whether <repository> is a git clone of its own (not a copy, nor a folder inside another clone).
is_clone() {
  local top; top=$(git -C "$1" rev-parse --show-toplevel 2>/dev/null) || return 1
  [ "$(cd "$top" && pwd -P)" = "$(cd "$1" && pwd -P)" ]
}
