#!/usr/bin/env python
"""Score a CTC-format result window by window, by error type: where a run starts to go wrong, and how.

usage: per_window.py RES_DIR GT_DIR [--window 10] [--start 0] [--csv FRAMES.csv] [--json OUT.json]
       per_window.py --compare RUN_A.json RUN_B.json [...]

The official metrics (evaluate_ctc.py) give one number for the whole movie, which cannot show an error made in
one window and carried into the next. This counts, per frame and per window of Automahout's adaptive tracking:

  matched     truth markers matched by a result label (the CTC DET rule: overlap > half the marker)
  missed      truth markers matched by no result label
  duplicate   result labels matching no marker but overlapping one that another label already matches
              (a second detection of one cell, or a fragment of it)
  background  result labels touching no truth marker at all
  merged      result labels matching two or more markers
  size        median over matched pairs of result volume / marker volume (only a cell size where the markers
              are full cell extents; HeLa's are, CE's are fixed small spheres: see sizeSEG, against the SEG truth,
              which for CE is single planes, so there it is an area ratio)
  link recall / precision
              truth links t -> t+1 (same track or parent -> daughter) whose matched result labels are linked
              too; and result links between two matched labels that the truth also links

RES_DIR holds res_track.txt + maskTTT.tif (CtcExport); GT_DIR is a CTC "0x_GT" folder (TRA/, optional SEG/).
Run with uv: uv run --project scripts/paper python scripts/paper/per_window.py ...
"""
import argparse
import json
import os
import re
import sys

import numpy as np
import tifffile


def read_tracks(path):
    """label -> (begin, end, parent), from a CTC res_track.txt / man_track.txt."""
    tracks = {}
    with open(path) as f:
        for line in f:
            p = line.split()
            if len(p) == 4:
                tracks[int(p[0])] = (int(p[1]), int(p[2]), int(p[3]))
    return tracks


def frames(folder, pattern):
    rx = re.compile(pattern)
    out = {}
    for name in os.listdir(folder):
        m = rx.fullmatch(name)
        if m:
            out[int(m.group(1))] = os.path.join(folder, name)
    return out


def overlaps(res, gt):
    """(res label, gt label) -> pixel count of their overlap, plus each label's volume."""
    both = (res > 0) & (gt > 0)
    pairs = {}
    if both.any():
        r = res[both].astype(np.int64)
        g = gt[both].astype(np.int64)
        key = r * (1 << 32) + g
        uniq, counts = np.unique(key, return_counts=True)
        for k, c in zip(uniq, counts):
            pairs[(int(k >> 32), int(k & 0xFFFFFFFF))] = int(c)
    rl, rc = np.unique(res[res > 0], return_counts=True)
    gl, gc = np.unique(gt[gt > 0], return_counts=True)
    return pairs, dict(zip(rl.tolist(), rc.tolist())), dict(zip(gl.tolist(), gc.tolist()))


def score_frame(res, gt):
    pairs, rvol, gvol = overlaps(res, gt)
    # The CTC DET rule: a result label matches a marker when it covers more than half of it.
    match = {}  # gt -> res
    res_matches = {}  # res -> [gt]
    for (r, g), c in pairs.items():
        if c > 0.5 * gvol[g]:
            match[g] = r
            res_matches.setdefault(r, []).append(g)
    touched = {r for (r, _g) in pairs}
    duplicate = sum(1 for r in rvol if r not in res_matches and r in touched)
    background = sum(1 for r in rvol if r not in touched)
    merged = sum(1 for gs in res_matches.values() if len(gs) > 1)
    ratios = [rvol[r] / gvol[g] for g, r in match.items()]
    return {
        "gt": len(gvol), "res": len(rvol), "matched": len(match), "missed": len(gvol) - len(match),
        "duplicate": duplicate, "background": background, "merged": merged,
        "size": float(np.median(ratios)) if ratios else None,
        "res_volume": float(np.median(list(rvol.values()))) if rvol else None,
    }, match


def linked(tracks, a, b, t):
    """Whether label a at t and label b at t+1 are linked in a CTC track table."""
    if a == b:
        return a in tracks and tracks[a][0] <= t and tracks[a][1] >= t + 1
    return b in tracks and tracks[b][0] == t + 1 and tracks[b][2] == a


def links_between(tracks, t, present_t, present_t1):
    """Every link t -> t+1 among the labels present in the two frames."""
    out = set()
    for a in present_t:
        if a in present_t1 and linked(tracks, a, a, t):
            out.add((a, a))
    for b in present_t1:
        if b in tracks and tracks[b][0] == t + 1 and tracks[b][2] in present_t:
            out.add((tracks[b][2], b))
    return out


def evaluate(res_dir, gt_dir, window, start):
    rfr = frames(res_dir, r"mask(\d+)\.tif")
    gfr = frames(os.path.join(gt_dir, "TRA"), r"man_track(\d+)\.tif")
    rtr = read_tracks(os.path.join(res_dir, "res_track.txt"))
    gtr = read_tracks(os.path.join(gt_dir, "TRA", "man_track.txt"))
    seg_dir = os.path.join(gt_dir, "SEG")
    sfr = frames(seg_dir, r"man_seg(\d+)\.tif") if os.path.isdir(seg_dir) else {}
    # 3-D truth may be segmented in single planes only (CE: man_seg_TTT_ZZZ.tif).
    planes = {}
    if os.path.isdir(seg_dir):
        for name in os.listdir(seg_dir):
            m = re.fullmatch(r"man_seg_(\d+)_(\d+)\.tif", name)
            if m:
                planes.setdefault(int(m.group(1)), []).append((int(m.group(2)), os.path.join(seg_dir, name)))
    rows = []
    prev = None
    for t in sorted(gfr):
        if t not in rfr:
            continue
        res = tifffile.imread(rfr[t])
        gt = tifffile.imread(gfr[t])
        row, match = score_frame(res, gt)
        row["t"] = t
        row["window"] = (t - start) // window if t >= start else -1
        if t in sfr:
            srow, _ = score_frame(res, tifffile.imread(sfr[t]))
            row["size_seg"] = srow["size"]
        elif t in planes and res.ndim == 3:
            # In-plane areas, so the ratio is a squared length ratio here, not a volume ratio.
            ratios = []
            for z, path in planes[t]:
                srow, _ = score_frame(res[z], tifffile.imread(path))
                if srow["size"] is not None:
                    ratios.append(srow["size"])
            row["size_seg"] = float(np.median(ratios)) if ratios else None
        rpresent = set(np.unique(res[res > 0]).tolist())
        gpresent = set(np.unique(gt[gt > 0]).tolist())
        if prev is not None and prev["t"] == t - 1:
            pm, pr, pg = prev["match"], prev["rpresent"], prev["gpresent"]
            gl = links_between(gtr, t - 1, pg, gpresent)
            rl = links_between(rtr, t - 1, pr, rpresent)
            # A truth link is found when both its ends are matched and the result links those two labels.
            found = sum(1 for (a, b) in gl if a in pm and b in match and (pm[a], match[b]) in rl)
            # A result link between two matched labels is right when the truth links their markers.
            inv_p = {r: g for g, r in pm.items()}
            inv = {r: g for g, r in match.items()}
            judged = [(ra, rb) for (ra, rb) in rl if ra in inv_p and rb in inv]
            right = sum(1 for (ra, rb) in judged if (inv_p[ra], inv[rb]) in gl)
            row.update(gt_links=len(gl), links_found=found, res_links_judged=len(judged), res_links_right=right)
        prev = {"t": t, "match": match, "rpresent": rpresent, "gpresent": gpresent}
        rows.append(row)
    return rows


def by_window(rows):
    wins = {}
    for r in rows:
        wins.setdefault(r["window"], []).append(r)
    out = []
    for w in sorted(wins):
        rs = wins[w]
        s = lambda k: sum(r.get(k, 0) or 0 for r in rs)
        sizes = [r["size"] for r in rs if r["size"] is not None]
        segs = [r["size_seg"] for r in rs if r.get("size_seg") is not None]
        out.append({
            "window": w, "t0": rs[0]["t"], "t1": rs[-1]["t"],
            "gt": s("gt"), "res": s("res"),
            "recall": s("matched") / s("gt") if s("gt") else None,
            "precision": s("matched") / s("res") if s("res") else None,
            "missed": s("missed"), "duplicate": s("duplicate"), "background": s("background"),
            "merged": s("merged"),
            "size": float(np.median(sizes)) if sizes else None,
            "size_seg": float(np.median(segs)) if segs else None,
            "link_recall": s("links_found") / s("gt_links") if s("gt_links") else None,
            "link_precision": s("res_links_right") / s("res_links_judged") if s("res_links_judged") else None,
        })
    return out


def fmt(v, spec):
    return "-" if v is None else format(v, spec)


COLUMNS = [("window", "w", "d"), ("t0", "t0", "d"), ("t1", "t1", "d"), ("gt", "gt", "d"), ("res", "res", "d"),
           ("recall", "recall", ".3f"), ("precision", "prec", ".3f"), ("missed", "miss", "d"),
           ("duplicate", "dup", "d"), ("background", "bg", "d"), ("merged", "merge", "d"), ("size", "size", ".2f"),
           ("size_seg", "sizeSEG", ".2f"), ("link_recall", "lnkRec", ".3f"), ("link_precision", "lnkPrec", ".3f")]


def print_table(windows, file=sys.stdout):
    print(" ".join(f"{h:>7}" for _k, h, _f in COLUMNS), file=file)
    for w in windows:
        print(" ".join(f"{fmt(w.get(k), f):>7}" for k, _h, f in COLUMNS), file=file)


def compare(paths):
    runs = []
    for p in paths:
        with open(p) as f:
            runs.append(json.load(f))
    keys = [("recall", ".3f"), ("precision", ".3f"), ("duplicate", "d"), ("background", "d"), ("size", ".2f"),
            ("link_recall", ".3f")]
    head = ["w", "t0"] + [f"{k}[{i}]" for k, _f in keys for i in range(len(runs))]
    print("runs: " + "  ".join(f"[{i}] {r['res']}" for i, r in enumerate(runs)))
    print(" ".join(f"{h:>12}" for h in head))
    n = max(len(r["windows"]) for r in runs)
    for i in range(n):
        ws = [r["windows"][i] if i < len(r["windows"]) else {} for r in runs]
        base = next(w for w in ws if w)
        cells = [str(base["window"]), str(base["t0"])]
        cells += [fmt(w.get(k), f) for k, f in keys for w in ws]
        print(" ".join(f"{c:>12}" for c in cells))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("res", nargs="?")
    ap.add_argument("gt", nargs="?")
    ap.add_argument("--window", type=int, default=10)
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--csv")
    ap.add_argument("--json")
    ap.add_argument("--compare", nargs="+")
    a = ap.parse_args()
    if a.compare:
        compare(a.compare)
        return 0
    if not (a.res and a.gt):
        ap.error("RES_DIR and GT_DIR are needed (or --compare)")
    rows = evaluate(a.res, a.gt, a.window, a.start)
    windows = by_window(rows)
    print_table(windows)
    if a.csv:
        keys = ["t", "window", "gt", "res", "matched", "missed", "duplicate", "background", "merged", "size",
                "size_seg", "res_volume", "gt_links", "links_found", "res_links_judged", "res_links_right"]
        with open(a.csv, "w") as f:
            f.write(",".join(keys) + "\n")
            for r in rows:
                f.write(",".join("" if r.get(k) is None else str(r.get(k)) for k in keys) + "\n")
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"res": a.res, "gt": a.gt, "window": a.window, "start": a.start, "windows": windows}, f,
                      indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
