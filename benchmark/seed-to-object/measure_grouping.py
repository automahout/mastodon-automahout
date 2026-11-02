"""How far does transitive grouping over-reach once the arbiter actually works?

The duplicate passes union-find their pairs into groups, then replace each group
with one ellipsoid covering it. Transitivity is only sound if the arbiter is
exact: A~B and B~C does not make A~C when the arbiter is 85.5%/6.8%. With the
old inert arbiter (9.0% recall) chains could not form, so the grouping was never
under load. It is now.

Compares three grouping rules on the same pairwise verdicts:

  singleton   no grouping -- what it looks like if nothing merges
  component   union-find, as shipped
  clique      merge a group only if EVERY pair in it is merged by the arbiter

Scored by how many DISTINCT truth cells end up inside one group. A group
spanning more than one truth cell is the giant ellipse in the report.

Run: <venv>/bin/python measure_grouping.py
"""
from collections import defaultdict

import numpy as np
import scipy.ndimage as nd

import measure_arbiter as M

SEPARATION_TOO_FAR = 2.2


def detections(img, seg):
    """Seeds labelled by the truth cell they belong to, incl. over-segmentation."""
    sm = nd.gaussian_filter(img, 1.5)
    out = []
    for lab, sl, m, cy, cx, r in M.cells_of(seg):
        from skimage.feature import peak_local_max
        local = np.where(m, sm[sl], 0.0)
        pk = peak_local_max(local, min_distance=max(3, int(r * 0.6)),
                            labels=m.astype(int), num_peaks=2)
        if len(pk) == 0:
            out.append((cy, cx, r, lab))
        for p in pk:
            out.append((sl[0].start + p[0], sl[1].start + p[1], r, lab))
    return out


def pair_verdicts(img, dets, med, sigma):
    """Arbiter verdict for every pair close enough to be considered."""
    edges = {}
    for i in range(len(dets)):
        for j in range(i + 1, len(dets)):
            yi, xi, ri, _ = dets[i]
            yj, xj, rj, _ = dets[j]
            d = np.hypot(yi - yj, xi - xj)
            if not (0 < d < SEPARATION_TOO_FAR * max(ri, rj)):
                continue
            v = M.local_watershed(img, (yi, xi), (yj, xj), med, sigma, 3.0, 2)
            edges[(i, j)] = bool(v) if v is not None else False
    return edges


def components(n, edges):
    parent = list(range(n))
    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    for (i, j), v in edges.items():
        if v:
            ri, rj = find(i), find(j)
            if ri != rj:
                parent[ri] = rj
    g = defaultdict(list)
    for i in range(n):
        g[find(i)].append(i)
    return [v for v in g.values() if len(v) > 1]


def cliques(n, edges):
    """Greedy: grow a group only while every member agrees with every other."""
    adj = defaultdict(set)
    for (i, j), v in edges.items():
        if v:
            adj[i].add(j)
            adj[j].add(i)
    used, out = set(), []
    for i in sorted(adj, key=lambda k: -len(adj[k])):
        if i in used:
            continue
        group = [i]
        for j in sorted(adj[i], key=lambda k: -len(adj[k])):
            if j in used:
                continue
            if all(j in adj[g] for g in group):
                group.append(j)
        if len(group) > 1:
            out.append(group)
            used.update(group)
    return out


def score(name, groups_per_frame, dets_per_frame):
    spans, sizes, merged_pairs, wrong = [], [], 0, 0
    for groups, dets in zip(groups_per_frame, dets_per_frame):
        for g in groups:
            labs = {dets[i][3] for i in g}
            spans.append(len(labs))
            sizes.append(len(g))
            if len(labs) == 1:
                merged_pairs += len(g) - 1
            else:
                wrong += 1
    spans = np.array(spans) if spans else np.array([0])
    sizes = np.array(sizes) if sizes else np.array([0])
    print(f'  {name:10s} groups {len(spans):4d} | size p50/max {np.median(sizes):3.0f}/'
          f'{sizes.max():3.0f} | spanning >1 truth cell {np.mean(spans > 1):6.1%} '
          f'| worst span {spans.max():2d} cells | duplicates removed {merged_pairs:4d}')


def main():
    per_frame = {'component': [], 'clique': []}
    dets_per_frame = []
    for img, seg in M.frames():
        med = float(np.median(img))
        sigma = max(1.4826 * float(np.median(np.abs(img - med))), 1e-9)
        dets = detections(img, seg)
        dets_per_frame.append(dets)
        edges = pair_verdicts(img, dets, med, sigma)
        per_frame['component'].append(components(len(dets), edges))
        per_frame['clique'].append(cliques(len(dets), edges))
    total = sum(len(d) for d in dets_per_frame)
    cells = sum(len({x[3] for x in d}) for d in dets_per_frame)
    print(f'{len(dets_per_frame)} frames, {total} detections on {cells} truth cells '
          f'({total - cells} of them duplicates)\n')
    for name in ('component', 'clique'):
        score(name, per_frame[name], dets_per_frame)


main()
