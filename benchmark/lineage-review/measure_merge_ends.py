"""Finding a 2->1 merge from the side where the cells part again.

measure_convergence.py finds a merge where it STARTS: a track ends at t with its centre inside the
merged spot S at t+1. That needs the second cell detected before the merge; in 9 of 25 missed
episodes on 20261002-015311 it never was. When the merge ends the detector sees both cells again,
so the same evidence is there on the other side:

  END-START   S at t has one successor N1 at t+1, and another spot N2 at t+1 with NO predecessor
              has its centre inside S (the second cell's track starts where the merge ends)
  END-DIVIDE  S at t has two successors at t+1, both centres inside S (the survivor "divides")

END-DIVIDE is also what a real division looks like, so its precision is the question.

Scored against tracking truth: S is a real merge when 2+ TRA markers lie inside its ellipse.
Episodes: maximal chains of real merges joined by single links. An episode is CAUGHT when any of its
frames is flagged by the onset rule or by either end rule.

    python measure_merge_ends.py [stamp] [snapshot-label-prefix]
"""
import sys
from collections import defaultdict, Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
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
    print(f'{stamp} snapshot {snap} "{label}": {len(spots)} spots')

    truth = {}
    for k, s in spots.items():
        v = mc.truth_inside(s)
        if v is not None:
            truth[k] = v
    real = {k for k, v in truth.items() if v >= 2}

    onset, end_start, end_divide = set(), set(), set()
    for k, s in spots.items():
        if pred[k]:
            for q in by_t[s['t'] - 1]:
                if q not in pred[k] and not succ[q] and mc.inside(s, spots[q]['x'], spots[q]['y']):
                    onset.add(k)
                    break
        if len(succ[k]) == 1:
            for n in by_t[s['t'] + 1]:
                if n != succ[k][0] and not pred[n] and mc.inside(s, spots[n]['x'], spots[n]['y']):
                    end_start.add(k)
                    break
        if len(succ[k]) == 2 and all(mc.inside(s, spots[n]['x'], spots[n]['y']) for n in succ[k]):
            end_divide.add(k)

    print('| rule | flagged | on 2+ truth cells | precision |')
    print('|---|---|---|---|')
    for name, f in (('ONSET', onset), ('END-START', end_start), ('END-DIVIDE', end_divide),
                    ('END-START or END-DIVIDE', end_start | end_divide),
                    ('any of the three', onset | end_start | end_divide)):
        f = {k for k in f if k in truth}
        hit = len(f & real)
        print(f'| {name} | {len(f)} | {hit} | {hit / max(len(f), 1):.0%} |')

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
    caught = Counter()
    for ch in episodes:
        o = any(k in onset for k in ch)
        e = any(k in end_start or k in end_divide for k in ch)
        caught['onset and end' if o and e else 'onset only' if o else 'end only' if e else 'neither'] += 1
    print(f'\nreal merge episodes: {len(episodes)} ({sum(map(len, episodes))} spots): {dict(caught)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
