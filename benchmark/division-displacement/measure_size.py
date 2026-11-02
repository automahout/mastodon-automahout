import pickle
from pathlib import Path
data = pickle.load(open("/tmp/ctc_centroids.pkl", "rb"))
root = Path.home() / "Repositories/CTC_dataset/Fluo-N2DL-HeLa"

def req(v):           # equivalent radius from the bbox, in pixels
    return ((v[3] - v[2] + 1) * (v[5] - v[4] + 1)) ** 0.5 / 2.0

def pct(xs, p):
    xs = sorted(xs); return xs[min(len(xs) - 1, int(p * len(xs)))]

for seq in ("01", "02"):
    frames = data[seq]["frames"]
    rows = [tuple(int(x) for x in l.split())
            for l in open(root / f"{seq}_GT/TRA/man_track.txt") if len(l.split()) == 4]
    ends = {label: end for label, _, end, _ in rows}
    ratios = []
    for label, begin, _, parent in rows:
        if parent <= 0: continue
        pe = ends.get(parent)
        if pe is None or pe not in frames or begin not in frames: continue
        m, d = frames[pe].get(parent), frames[begin].get(label)
        if m and d: ratios.append(req(d) / req(m))
    print(f"seq {seq}: {len(ratios)} divisions — daughter/mother equivalent radius")
    print(f"   p05 {pct(ratios,.05):.2f}  median {pct(ratios,.5):.2f}  p95 {pct(ratios,.95):.2f}")
    for thr in (0.85, 1.0, 1.1, 1.25, 1.5):
        print(f"   rule 'daughter <= {thr:.2f} x mother' refuses "
              f"{100.0*sum(1 for r in ratios if r > thr)/len(ratios):5.1f}%")
