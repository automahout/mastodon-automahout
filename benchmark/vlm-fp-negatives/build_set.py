#!/usr/bin/env python3
"""Builds the frozen VLM fp-negative benchmark from ~/.automahout-vlm-debug dumps.

Ground truth is MEASURED, not judged: the red crosshair is located by its own pixels (it is not
always at the centre — a crop clamped at the image border puts it anywhere), and the image
intensity in a small disc around it is compared with the crop's own frame. Bright at the crosshair
means a cell is there; dark means background. Anything in between is left out rather than guessed.

This exists because the verdicts we were getting could not be checked. Re-run it to grow or refresh
the set; commit the result so a change to prompting, batching or rendering is measured against a
fixed target.

    python3 build_set.py [--dumps N] [--per-class N]
"""
import argparse, glob, json, os, re, shutil, statistics
from PIL import Image

DEBUG_DIR = os.path.expanduser("~/.automahout-vlm-debug")
HERE = os.path.dirname(os.path.abspath(__file__))
CELL_MIN = 200.0        # intensity at the crosshair at or above this: a cell is there
BACKGROUND_MAX = 60.0   # at or below this: nothing is there
FRAME_FLAT = (1.0, 254.0)  # a crop rendered entirely black or entirely white is unanswerable


def measure(path):
    """(intensity at the crosshair, frame median, crosshair position as a fraction of the frame)."""
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    red = lambda p: p[0] > p[1] + 60 and p[0] > p[2] + 60
    marks = [(x, y) for y in range(h) for x in range(w) if red(px[x, y])]
    if not marks:
        return None
    cx = sum(p[0] for p in marks) / len(marks)
    cy = sum(p[1] for p in marks) / len(marks)
    r = int(w * 0.10)
    grey = lambda x, y: sum(px[x, y]) / 3.0
    at = [grey(x, y)
          for y in range(max(0, int(cy - r)), min(h, int(cy + r) + 1))
          for x in range(max(0, int(cx - r)), min(w, int(cx + r) + 1))
          if not red(px[x, y]) and (x - cx) ** 2 + (y - cy) ** 2 <= r * r]
    if not at:
        return None
    frame = [grey(x, y) for y in range(0, h, 3) for x in range(0, w, 3)]
    return statistics.mean(at), statistics.median(frame), (cx / w, cy / h)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dumps", type=int, default=250, help="most recent 2D dumps to scan")
    ap.add_argument("--per-class", type=int, default=30)
    args = ap.parse_args()

    dumps = [d for d in sorted(glob.glob(DEBUG_DIR + "/synthesize_*"))[-args.dumps:]
             if os.path.exists(d + "/reply.txt") and not glob.glob(d + "/candidate_1_xz.png")]
    cells, background, skipped_flat, offcentre = [], [], 0, 0
    for d in dumps:
        verdicts = {int(m.group(1)): m.group(2) for m in
                    re.finditer(r"VERDICT_(\d+):\s*(fp|real)", open(d + "/reply.txt").read())}
        for f in sorted(glob.glob(d + "/candidate_*_xy.png")):
            m = measure(f)
            if m is None:
                continue
            at, frame, pos = m
            if not (FRAME_FLAT[0] < frame < FRAME_FLAT[1]):
                skipped_flat += 1
                continue
            if abs(pos[0] - 0.5) > 0.08 or abs(pos[1] - 0.5) > 0.08:
                offcentre += 1
            i = int(re.search(r"_(\d+)_xy", f).group(1))
            rec = {"src": f, "dump": os.path.basename(d), "candidate": i, "at": round(at, 1),
                   "frame": round(frame, 1), "vlm": verdicts.get(i, "?"),
                   "offcentre": abs(pos[0] - 0.5) > 0.08 or abs(pos[1] - 0.5) > 0.08}
            if at >= CELL_MIN:
                cells.append(rec)
            elif at <= BACKGROUND_MAX:
                background.append(rec)

    cells.sort(key=lambda r: -r["at"])
    background.sort(key=lambda r: r["at"])
    chosen = cells[:args.per_class] + background[:args.per_class]

    images = os.path.join(HERE, "images")
    shutil.rmtree(images, ignore_errors=True)
    os.makedirs(images)
    rows = []
    for rec in chosen:
        name = "%s_c%03d.png" % (rec["dump"].replace("synthesize_", "s"), rec["candidate"])
        shutil.copy(rec["src"], os.path.join(images, name))
        rows.append((name, "cell" if rec["at"] >= CELL_MIN else "background", rec))

    with open(os.path.join(HERE, "labels.tsv"), "w") as fh:
        fh.write("# image\ttruth\tintensity_at_crosshair\tframe_median\tcrosshair_off_centre\t"
                 "verdict_when_captured\n")
        fh.write("# truth is measured, not judged: see build_set.py. cell >= %.0f, background <= %.0f\n"
                 % (CELL_MIN, BACKGROUND_MAX))
        for name, truth, rec in sorted(rows):
            fh.write("%s\t%s\t%.1f\t%.1f\t%s\t%s\n"
                     % (name, truth, rec["at"], rec["frame"],
                        "yes" if rec["offcentre"] else "no", rec["vlm"]))

    scanned = len(cells) + len(background) + skipped_flat
    print("dumps scanned          : %d" % len(dumps))
    print("crops measured         : %d" % scanned)
    print("  unanswerable (all black / all white): %d (%.1f%%)"
          % (skipped_flat, 100.0 * skipped_flat / max(1, scanned)))
    print("  crosshair off-centre by >8%% of the frame: %d" % offcentre)
    print("cells found            : %d (kept %d)" % (len(cells), min(len(cells), args.per_class)))
    print("background found       : %d (kept %d)" % (len(background), min(len(background), args.per_class)))
    print("wrote labels.tsv and images/ (%d crops)" % len(rows))


if __name__ == "__main__":
    main()
