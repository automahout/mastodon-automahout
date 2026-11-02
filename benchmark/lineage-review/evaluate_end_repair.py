"""Does the seeded image fit make the end-side merge rules usable?

measure_merge_ends.py: flagged from where the cells part, the rules are right 37-54% of the time.
Here every candidate gets the fit evaluate_convergence_repair.py uses, seeded at the two cells after
the merge (carried back by S's own motion), and is scored by OUTCOME against silver truth: two pieces
on two different cells. Refused = the fit found no two cells at least MIN_SEP_FRAME_R apart.

    python evaluate_end_repair.py [stamp] [snapshot-label-prefix]
"""
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import evaluate_convergence_repair as ev  # noqa: E402
import measure_convergence as mc  # noqa: E402
import measure_links as ml  # noqa: E402


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
    frame_r = {t: float(np.median([spots[k]['r'] for k in ks])) for t, ks in by_t.items()}
    print(f'{stamp} snapshot {snap} "{label}"')

    cands = defaultdict(list)   # rule -> [(S, before-pair or after-pair)]
    for k, s in spots.items():
        if pred[k]:
            for q in by_t[s['t'] - 1]:
                if q not in pred[k] and not succ[q] and mc.inside(s, spots[q]['x'], spots[q]['y']):
                    cands['ONSET'].append((k, (pred[k][0], q)))
                    break
        if len(succ[k]) == 1:
            for n in by_t[s['t'] + 1]:
                if n != succ[k][0] and not pred[n] and mc.inside(s, spots[n]['x'], spots[n]['y']):
                    cands['END-START'].append((k, (succ[k][0], n)))
                    break
        if len(succ[k]) == 2 and all(mc.inside(s, spots[n]['x'], spots[n]['y']) for n in succ[k]):
            cands['END-DIVIDE'].append((k, tuple(succ[k])))

    print('| rule | candidates | refused | split | right | one cell cut | piece on background |')
    print('|---|---|---|---|---|---|---|')
    for rule in ('ONSET', 'END-START', 'END-DIVIDE'):
        c = Counter()
        for k, (a, b) in cands[rule]:
            S, A, B = spots[k], spots[a], spots[b]
            dx, dy = S['x'] - (A['x'] + B['x']) / 2, S['y'] - (A['y'] + B['y']) / 2
            got = ev.seeded_fit(S['t'], S, [(A['x'] + dx, A['y'] + dy), (B['x'] + dx, B['y'] + dy)],
                                frame_r[S['t']])
            c[ev.outcome(S['t'], got)] += 1
        n = sum(c.values())
        made = n - c['refused']
        print(f"| {rule} | {n} | {c['refused']} | {made} | {c['RIGHT']} ({c['RIGHT'] / max(made, 1):.0%}) "
              f"| {c['both on one cell']} | {c['a piece on background']} |")
    return 0


if __name__ == '__main__':
    sys.exit(main())
