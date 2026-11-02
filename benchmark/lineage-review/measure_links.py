"""Every link of a run, and every division of the truth, against CTC tracking truth.

Reads the links file the origin dump writes beside itself (SpotOriginDump, revision 2:
origin-dump-<stamp>.links.tsv) and the tracking truth (01_GT/TRA: man_track###.tif markers and
man_track.txt, "label begin end parent").

Each link end is matched to a truth track: the TRA label under it, else the nearest TRA marker
centroid within MATCH_UM. A link is then
  SAME TRACK         both ends on one truth track
  DIVISION BRANCH    the target's track is a daughter of the source's
  UNNATURAL          both ends on truth tracks, unrelated -- a jump between cells
  UNMATCHED          an end on no truth track (background, debris, a fragment)
Divisions in the graph (a source with two or more links out) are TRUE when the truth divides that
track into the targets' tracks. Truth divisions are RECOVERED when the mother's last spot links to
spots on both daughters.

Unnatural links and false divisions are counted by the Reason and site that made them.

    python measure_links.py [stamp] [snapshot-label-prefix]
Default snapshot: the last whole-graph snapshot (label without " t=").
"""
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path.home() / 'Repositories' / 'CTC_dataset' / 'Fluo-N2DL-HeLa'
VOXEL_UM = 0.645
MATCH_UM = 8.0          # about one nucleus radius on this dataset
_tra = {}
_cent = {}


def tracks():
    out = {}
    for line in open(ROOT / '01_GT' / 'TRA' / 'man_track.txt'):
        l, b, e, p = map(int, line.split())
        out[l] = (b, e, p)
    return out


def tra(t):
    if t not in _tra:
        f = ROOT / '01_GT' / 'TRA' / f'man_track{t:03d}.tif'
        _tra[t] = np.array(Image.open(f)) if f.exists() else None
        if _tra[t] is not None:
            m = _tra[t]
            rows, cols = np.nonzero(m)
            vals = m[rows, cols]
            labs = np.unique(vals)
            _cent[t] = (labs, np.array([[cols[vals == v].mean() * VOXEL_UM, rows[vals == v].mean() * VOXEL_UM]
                                        for v in labs]))
    return _tra[t]


def track_at(t, x, y):
    m = tra(t)
    if m is None:
        return None
    col, row = int(round(x / VOXEL_UM)), int(round(y / VOXEL_UM))
    if 0 <= row < m.shape[0] and 0 <= col < m.shape[1] and m[row, col] > 0:
        return int(m[row, col])
    labs, cents = _cent[t]
    if len(labs) == 0:
        return 0
    d = np.hypot(cents[:, 0] - x, cents[:, 1] - y)
    k = int(d.argmin())
    return int(labs[k]) if d[k] < MATCH_UM else 0


def newest_stamp():
    files = sorted(Path('/tmp').glob('origin-dump-*.links.tsv'), key=lambda f: f.stat().st_mtime)
    return files[-1].name[len('origin-dump-'):-len('.links.tsv')] if files else None


def load_links(path, prefix):
    by_snap = defaultdict(list)
    labels = {}
    with open(path) as fh:
        head = fh.readline().rstrip('\n').split('\t')
        ix = {n: i for i, n in enumerate(head)}
        for line in fh:
            q = line.rstrip('\n').split('\t')
            if q[0] == 'rev':
                continue
            s = int(q[ix['snapshot']])
            labels[s] = q[ix['label']]
            by_snap[s].append(dict(src=int(q[ix['source']]), dst=int(q[ix['target']]),
                                   ts=int(q[ix['tSource']]), tt=int(q[ix['tTarget']]),
                                   xs=float(q[ix['xSource']]), ys=float(q[ix['ySource']]),
                                   xt=float(q[ix['xTarget']]), yt=float(q[ix['yTarget']]),
                                   why=q[ix['reason']], site=q[ix['site']]))
    if prefix:
        picks = [s for s in labels if labels[s].startswith(prefix)]
    else:
        picks = [s for s in labels if ' t=' not in labels[s]]
    if not picks:
        return None, None
    s = max(picks)
    return by_snap[s], labels[s]


def origin(l):
    site = l['site'].split('<-')[0] if l['site'] else ''
    return f"{l['why']}:{site}" if site else l['why']


def is_descendant(tr, child, ancestor, depth=3):
    while depth and child in tr:
        p = tr[child][2]
        if p == ancestor:
            return True
        child = p
        depth -= 1
    return False


def main():
    stamp = sys.argv[1] if len(sys.argv) > 1 else newest_stamp()
    prefix = sys.argv[2] if len(sys.argv) > 2 else None
    path = Path(f'/tmp/origin-dump-{stamp}.links.tsv')
    links, label = load_links(path, prefix)
    if links is None:
        print(f'no matching snapshot in {path}')
        return 1
    tr = tracks()
    print(f'{path}\nsnapshot "{label}": {len(links)} link(s)\n')
    kinds = Counter()
    bad = Counter()
    out_of = defaultdict(list)
    for l in links:
        a, b = track_at(l['ts'], l['xs'], l['ys']), track_at(l['tt'], l['xt'], l['yt'])
        l['a'], l['b'] = a, b
        out_of[(l['ts'], l['src'])].append(l)
        if a is None or b is None:
            continue
        if a == 0 or b == 0:
            k = 'UNMATCHED'
        elif a == b:
            k = 'SAME TRACK'
        elif tr.get(b, (0, 0, 0))[2] == a or is_descendant(tr, b, a):
            k = 'DIVISION BRANCH'
        else:
            k = 'UNNATURAL'
        l['kind'] = k
        kinds[k] += 1
        if k == 'UNNATURAL':
            bad[origin(l)] += 1
    n = sum(kinds.values())
    print('| link | count | share |\n|---|---|---|')
    for k in ('SAME TRACK', 'DIVISION BRANCH', 'UNNATURAL', 'UNMATCHED'):
        print(f'| {k} | {kinds[k]} | {kinds[k] / max(n, 1):.1%} |')
    print('\nUnnatural links by what made them:')
    for k, v in bad.most_common():
        print(f'  {v:5d}  {k}')

    # divisions in the graph
    true_div = false_div = 0
    false_by = Counter()
    for key, ls in out_of.items():
        if len(ls) < 2:
            continue
        a = ls[0].get('a')
        kids = {l.get('b') for l in ls}
        daughters = {d for d, (bb, ee, p) in tr.items() if p == a}
        if a and len(daughters) >= 2 and len(kids & daughters) >= 2:
            true_div += 1
        else:
            false_div += 1
            for l in ls:
                false_by[origin(l)] += 1
    print(f'\nDivisions in the graph: {true_div + false_div}; true {true_div}, false {false_div}')
    for k, v in false_by.most_common(6):
        print(f'  {v:5d} branch link(s) of false divisions made by {k}')

    # truth divisions
    children = defaultdict(list)
    for d, (bb, ee, p) in tr.items():
        if p:
            children[p].append(d)
    rec = part = miss = 0
    ts_present = {l['ts'] for l in links}
    for m, ds in children.items():
        if len(ds) < 2:
            continue
        end = tr[m][1]
        if end not in ts_present:
            continue
        from_m = [l for l in links if l['ts'] == end and l.get('a') == m]
        hit = {l.get('b') for l in from_m} & set(ds)
        if len(hit) >= 2:
            rec += 1
        elif len(hit) == 1:
            part += 1
        else:
            miss += 1
    tot = rec + part + miss
    print(f'\nTruth divisions in range: {tot}; recovered {rec} ({rec / max(tot, 1):.0%}), one daughter only '
          f'{part}, neither {miss}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
