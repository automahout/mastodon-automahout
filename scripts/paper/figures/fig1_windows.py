"""Fig. 1c and 1d data: per time window, the cell radius the review measured and how the review ended.

Reads an Automahout run.log and writes a CSV with one row per window: start, end, cell radius (µm), rounds to Go,
and the outcome (go, passed before training, or best round kept).

    uv run python figures/fig1_windows.py <run.log> <out.csv>
"""

import csv
import re
import sys

WINDOW = re.compile(r"^=== Window \[t=(\d+)\.\.(\d+)\]")
RADIUS = re.compile(r"Review: cell size by scale selection .* cell radius ([0-9.]+) µm")
GO = re.compile(r"Review, round (\d+)/\d+: .* -> Go")
BEFORE = re.compile(r"Review: the model already reproduces them before any training")
NO_GO = re.compile(r"Review: no Go, best round kept")


def windows(lines):
    rows, cur = [], None
    for line in lines:
        m = WINDOW.match(line)
        if m:
            cur = {"start": int(m.group(1)), "end": int(m.group(2)), "radius_um": "", "rounds": "", "outcome": ""}
            rows.append(cur)
            continue
        if cur is None:
            continue
        if (m := RADIUS.search(line)) and cur["radius_um"] == "":
            cur["radius_um"] = float(m.group(1))
        elif (m := GO.search(line)) and cur["outcome"] == "":
            cur["rounds"], cur["outcome"] = int(m.group(1)), "go"
        elif BEFORE.search(line) and cur["outcome"] == "":
            cur["rounds"], cur["outcome"] = 0, "passed before training"
        elif NO_GO.search(line) and cur["outcome"] == "":
            cur["rounds"], cur["outcome"] = 20, "best round kept"
    return rows


if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as f:
        rows = windows(f)
    with open(sys.argv[2], "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["start", "end", "radius_um", "rounds", "outcome"])
        w.writeheader()
        w.writerows(rows)
    print(f"{len(rows)} windows -> {sys.argv[2]}")
