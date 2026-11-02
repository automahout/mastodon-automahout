#!/usr/bin/env python
"""Collect the benchmark's scores: every <runs>/<dataset>/<seq>/<condition>/rep<n>/metrics.json.

usage: summarize.py RUNS_DIR --out SUMMARY_DIR

Writes SUMMARY_DIR/runs.csv (one row per run: scores, wall-clock seconds, commit) and SUMMARY_DIR/summary.md
(mean, sample SD and n per dataset x sequence x condition). A run whose result is not valid CTC output
(Valid != 1) is listed and left out of the means. Run with uv: uv run --project scripts/paper python ...
"""
import argparse
import csv
import json
import statistics
from pathlib import Path

METRICS = ["DET", "TRA", "LNK", "CT", "TF", "BC(0)", "CCA"]
CONDITION_ORDER = ["A", "AO", "E", "P", "T"]


def runs(root):
    for m in sorted(root.glob("*/*/*/rep*/metrics.json")):
        rep_dir = m.parent
        cond_dir = rep_dir.parent
        seq_dir = cond_dir.parent
        info_path = rep_dir / "run-info.json"
        info = json.loads(info_path.read_text()) if info_path.exists() else {}
        scores = json.loads(m.read_text())
        row = {"dataset": seq_dir.parent.name, "seq": seq_dir.name, "condition": cond_dir.name,
               "rep": rep_dir.name[3:], "Valid": scores.get("Valid")}
        row.update({k: scores.get(k) for k in METRICS})
        row["seconds"] = round(sum(v for v in info.get("seconds", {}).values() if isinstance(v, (int, float))), 1)
        row["commit"] = info.get("versions", {}).get("automahout_commit", "")
        row["device"] = info.get("device", "")
        yield row


def fmt(values):
    if not values:
        return ""
    if len(values) == 1:
        return f"{values[0]:.3f}"
    return f"{statistics.mean(values):.3f} ± {statistics.stdev(values):.3f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    rows = list(runs(Path(a.runs)))
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    fields = ["dataset", "seq", "condition", "rep", "Valid"] + METRICS + ["seconds", "commit", "device"]
    with open(out / "runs.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

    groups = {}
    for r in rows:
        groups.setdefault((r["dataset"], r["seq"], r["condition"]), []).append(r)
    order = lambda k: (k[0], k[1], CONDITION_ORDER.index(k[2]) if k[2] in CONDITION_ORDER else 99, k[2])
    lines = ["# Benchmark summary", "",
             "Mean ± sample SD over repetitions; n counts valid runs (Valid = 1). From `" + a.runs + "`.", "",
             "| dataset | seq | condition | n | " + " | ".join(METRICS) + " |",
             "|---|---|---|---|" + "---|" * len(METRICS)]
    invalid = []
    for k in sorted(groups, key=order):
        valid = [r for r in groups[k] if r["Valid"] == 1.0]
        invalid += [r for r in groups[k] if r["Valid"] != 1.0]
        cells = [fmt([r[m] for r in valid if r[m] is not None]) for m in METRICS]
        lines.append(f"| {k[0]} | {k[1]} | {k[2]} | {len(valid)} | " + " | ".join(cells) + " |")
    if invalid:
        lines += ["", "Invalid results (left out): "
                  + ", ".join(f"{r['dataset']} {r['seq']} {r['condition']} rep{r['rep']}" for r in invalid)]
    (out / "summary.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"\n{len(rows)} runs -> {out / 'runs.csv'}, {out / 'summary.md'}")


if __name__ == "__main__":
    main()
