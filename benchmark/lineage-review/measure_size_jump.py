"""A detection clearly bigger than its predecessor: how well does that flag one covering two cells?

The user's observation: a merge usually shows as a detection that suddenly grows -- two cells at t,
one detection over both at t+1. Measured on every detection with a predecessor, against tracking
truth (merged = 2+ TRA markers inside its ellipse):

  growth    in-plane area (a*b) over its predecessor's
  size      in-plane area over the frame's median area

For each: AUC (merged against single), and the flags a per-run threshold of median + K MAD (the
run's own spread, no constant) would raise -- precision, and how many real merge EPISODES it reaches
that the lineage rules (ONSET, END-START, END-DIVIDE) do not.

    python measure_size_jump.py [stamp] [snapshot-label-prefix]
"""
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import measure_convergence as mc  # noqa: E402
import measure_links as ml  # noqa: E402

KS = (1.5, 2.0, 3.0, 4.0)


def auc(pos, neg):
    pos, neg = np.asarray(pos), np.asarray(neg)
    if len(pos) == 0 or len(neg) == 0:
        return float('nan')
    allv = np.concatenate([pos, neg])
    ranks = allv.argsort().argsort() + 1.0
    return (ranks[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))


def main():
    stamp = sys.argv[1] if len(sys.argv) > 1 else ml.newest_stamp()
    prefix = sys.argv[2] if len(sys.argv) > 2 else None
    lpath = Path(f'/tmp/origin-dump-{stamp}.links.tsv')
    links, label = ml.load_links(lpath, prefix)
    snap = None
    with open(lpath) as fh:
        fh.readline()
        for line in fh:
            q = line.split('\t')
            if q[2] == label:
                snap = int(q[1])
    spots = {k: v for k, v in mc.load_spots(Path(f'/tmp/origin-dump-{stamp}.tsv'), snap).items()
             if v['tag'] != 'fp'}
    succ, pred = defaultdict(list), defaultdict(list)
    for l in links:
        if l['src'] in spots and l['dst'] in spots and l['tt'] == l['ts'] + 1:
            succ[l['src']].append(l['dst'])
            pred[l['dst']].append(l['src'])
    by_t = defaultdict(list)
    for k, s in spots.items():
        by_t[s['t']].append(k)
    area = {k: s['a'] * s['b'] for k, s in spots.items()}
    med_area = {t: float(np.median([area[k] for k in ks])) for t, ks in by_t.items()}
    print(f'{stamp} snapshot {snap} "{label}"')

    truth = {k: mc.truth_inside(s) for k, s in spots.items()}
    real = {k for k, v in truth.items() if v is not None and v >= 2}

    # The lineage rules, as measure_merge_ends.py has them.
    lineage = set()
    for k, s in spots.items():
        if pred[k]:
            for q in by_t[s['t'] - 1]:
                if q not in pred[k] and not succ[q] and mc.inside(s, spots[q]['x'], spots[q]['y']):
                    lineage.add(k)
        if len(succ[k]) == 1:
            for n in by_t[s['t'] + 1]:
                if n != succ[k][0] and not pred[n] and mc.inside(s, spots[n]['x'], spots[n]['y']):
                    lineage.add(k)
        if len(succ[k]) == 2 and all(mc.inside(s, spots[n]['x'], spots[n]['y']) for n in succ[k]):
            lineage.add(k)

    # Episodes of real merges, chained by single links.
    seen, episodes = set(), []
    for k in sorted(real, key=lambda k: spots[k]['t']):
        if k in seen:
            continue
        chain, cur = [], k
        while True:
            chain.append(cur)
            seen.add(cur)
            if len(succ[cur]) == 1 and succ[cur][0] in real and succ[cur][0] not in seen:
                cur = succ[cur][0]
            else:
                break
        episodes.append(chain)
    missed = [ch for ch in episodes if not any(k in lineage for k in ch)]
    print(f'real merge episodes {len(episodes)}; the lineage rules reach {len(episodes) - len(missed)}, '
          f'miss {len(missed)}')

    feats = {
        'growth': {k: area[k] / area[pred[k][0]] for k in spots if pred[k] and truth[k] is not None},
        'size': {k: area[k] / med_area[spots[k]['t']] for k in spots if pred[k] and truth[k] is not None},
    }
    for name, f in feats.items():
        pos = [v for k, v in f.items() if k in real]
        neg = [v for k, v in f.items() if k not in real]
        allv = np.array(list(f.values()))
        med = np.median(allv)
        mad = 1.4826 * np.median(np.abs(allv - med))
        print(f'\n{name}: AUC {auc(pos, neg):.3f}; merged p10/50/90 {np.percentile(pos, [10, 50, 90]).round(2)}, '
              f'single {np.percentile(neg, [10, 50, 90]).round(2)}; run median {med:.2f} MAD {mad:.2f}')
        print('| K | bar | flagged | merged | precision | episodes reached | of them missed by lineage | '
              'new flags not already lineage-flagged |')
        print('|---|---|---|---|---|---|---|---|')
        for K in KS:
            bar = med + K * mad
            fl = {k for k, v in f.items() if v > bar}
            hit = fl & real
            reach = [ch for ch in episodes if any(k in fl for k in ch)]
            new = [ch for ch in missed if any(k in fl for k in ch)]
            print(f'| {K} | {bar:.2f} | {len(fl)} | {len(hit)} | {len(hit) / max(len(fl), 1):.0%} | '
                  f'{len(reach)} | {len(new)} | {len(fl - lineage)} |')

    # The user's rule: clearly bigger than the frame before AND big for its frame. Counted as
    # QUESTIONS -- the dialog asks once per merge, so flagged spots chained by single links are one.
    g, z = feats['growth'], feats['size']
    gv, zv = np.array(list(g.values())), np.array(list(z.values()))
    gmed, gmad = np.median(gv), 1.4826 * np.median(np.abs(gv - np.median(gv)))
    zmed, zmad = np.median(zv), 1.4826 * np.median(np.abs(zv - np.median(zv)))
    print('\ngrowth AND size, counted per question (a chain of flagged spots is one):')
    print('| K growth | K size | questions | on a merge | precision | episodes reached | missed by lineage, now reached |')
    print('|---|---|---|---|---|---|---|')
    for Kg in (2.0, 3.0, 4.0):
        for Kz in (1.0, 1.5, 2.0):
            fl = {k for k in g if g[k] > gmed + Kg * gmad and z[k] > zmed + Kz * zmad and k not in lineage}
            # chain: a flagged spot whose predecessor is flagged belongs to the same question
            starts = [k for k in fl if not (pred[k] and pred[k][0] in fl)]
            on = sum(1 for k in starts if k in real)
            reach = [ch for ch in episodes if any(k in fl or k in lineage for k in ch)]
            new = [ch for ch in missed if any(k in fl for k in ch)]
            print(f'| {Kg} | {Kz} | {len(starts)} | {on} | {on / max(len(starts), 1):.0%} | {len(reach)} | {len(new)} |')
    return 0


if __name__ == '__main__':
    sys.exit(main())
