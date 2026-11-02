"""Crude static audit of graph-lock use: python scripts/audit_graph_locks.py src/main/java

1. Methods: split each file into methods (signature line at class-member indent + brace matching).
2. For each method: does it take the write lock / read lock directly; which simple method names it calls.
3. Transitive: a method "writes" if it, or anything it calls (by simple name within the project), takes
   the write lock; "blocks" if it (transitively) waits on a person, the EDT or the server.
4. Regions: from X.lock() to its matching X.unlock() (counted per lock kind). Inside each region:
   - READ region calling a method that takes the WRITE lock -> upgrade = permanent deadlock
   - any region calling something that blocks -> the UI (and the user's edits) freeze until it returns
"""
import re, sys, os
from collections import defaultdict

ROOT = sys.argv[1]
BLOCK_PRIMS = {
    'chooseImage': 'asks the user', 'waitForEdit': 'waits for a hand edit', 'letUserEdit': 'waits for a hand edit',
    'askUser': 'asks the user', 'ask': 'asks the user?', 'waitForAnnotations': 'waits for annotations',
    'invokeAndWait': 'waits for the EDT', 'postBlocking': 'waits for the ELEPHANT server', 'sleep': 'sleeps',
    'showConfirmDialog': 'modal dialog', 'showMessageDialog': 'modal dialog', 'showOptionDialog': 'modal dialog',
    'showInputDialog': 'modal dialog', 'join': 'joins a thread', 'get': None,
}
SLOW_PRIMS = {'fullResolutionSampler': 'reads the image', 'standoutAboveBackground': 'reads the image',
              'sampleContrast': 'reads the image', 'sampleIntensity': 'reads the image',
              'sampleSpotIntensity': 'reads the image', 'measuredCellRadius': 'reads the image',
              'fitEllipseToContour': 'reads the image', 'interiorIntensityProfile': 'reads the image',
              'estimateBackgroundStats': 'reads the image', 'isSameBlob': 'reads the image',
              'predictSpots': 'ELEPHANT predict', 'trainDetection': 'ELEPHANT train'}
sig = re.compile(r'^\t(?:\t)?(?:(?:public|private|protected|static|final|synchronized|abstract)\s+)*[\w<>\[\],\s?]+\s+(\w+)\s*\(')
call = re.compile(r'\b(\w+)\s*\(')
KEYW = {'if', 'for', 'while', 'switch', 'catch', 'synchronized', 'return', 'new', 'super', 'this', 'try'}

methods = {}  # (file, name, startline) -> lines
for dp, _, fs in os.walk(ROOT):
    for f in fs:
        if not f.endswith('.java'):
            continue
        p = os.path.join(dp, f)
        L = open(p, encoding='utf-8', errors='replace').read().split('\n')
        i = 0
        while i < len(L):
            m = sig.match(L[i])
            if m and not L[i].strip().startswith(('return', 'new ', 'else')) and m.group(1) not in KEYW:
                # find opening brace
                j = i
                while j < len(L) and '{' not in L[j] and ';' not in L[j]:
                    j += 1
                if j < len(L) and '{' in L[j]:
                    depth = 0
                    k = j
                    while k < len(L):
                        depth += L[k].count('{') - L[k].count('}')
                        if depth == 0 and k >= j:
                            break
                        k += 1
                    methods[(p, m.group(1), i + 1)] = L[i:k + 1]
                    i = k + 1
                    continue
            i += 1

by_name = defaultdict(list)
for key in methods:
    by_name[key[1]].append(key)

def strip(line):
    line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
    return line.split('//')[0]

direct_write, direct_block, direct_slow, calls = {}, {}, {}, {}
for key, body in methods.items():
    text = '\n'.join(strip(l) for l in body[1:])
    direct_write[key] = 'writeLock().lock()' in text
    b = set()
    for name in call.findall(text):
        if name in BLOCK_PRIMS and BLOCK_PRIMS[name]:
            b.add(BLOCK_PRIMS[name])
    direct_block[key] = b
    direct_slow[key] = {SLOW_PRIMS[n] for n in call.findall(text) if n in SLOW_PRIMS}
    calls[key] = {n for n in call.findall(text) if n in by_name and n not in KEYW}

def closure(direct, combine):
    res = {k: (set(v) if isinstance(v, set) else v) for k, v in direct.items()}
    changed = True
    while changed:
        changed = False
        for k in methods:
            for n in calls[k]:
                for k2 in by_name[n]:
                    if k2 == k:
                        continue
                    new = combine(res[k], res[k2])
                    if new != res[k]:
                        res[k] = new
                        changed = True
    return res

writes = closure(direct_write, lambda a, b: a or b)
blocks = closure(direct_block, lambda a, b: a | b)
slows = closure(direct_slow, lambda a, b: a | b)

out = []
for key, body in methods.items():
    p, name, start = key
    stack = []  # (kind, line index)
    for idx, raw in enumerate(body):
        l = strip(raw)
        for kind in ('readLock', 'writeLock'):
            if f'{kind}().lock()' in l:
                stack.append((kind, idx))
            if f'{kind}().unlock()' in l:
                for s in range(len(stack) - 1, -1, -1):
                    if stack[s][0] == kind:
                        stack.pop(s)
                        break
        if not stack or any(f'{k}().lock()' in l or f'{k}().unlock()' in l for k in ('readLock', 'writeLock')):
            continue
        held = {k for k, _ in stack}
        only_read = 'readLock' in held and 'writeLock' not in held
        for n in call.findall(l):
            if n in KEYW:
                continue
            if n in BLOCK_PRIMS and BLOCK_PRIMS[n]:
                out.append((p, start + idx, name, 'BLOCKS', f'{n}() {BLOCK_PRIMS[n]}', raw.strip()))
            if n in SLOW_PRIMS:
                out.append((p, start + idx, name, 'SLOW', f'{n}() {SLOW_PRIMS[n]}', raw.strip()))
            for k2 in by_name.get(n, []):
                if only_read and writes[k2] and 'writeLock' not in held:
                    out.append((p, start + idx, name, 'UPGRADE', f'{n}() takes the write lock under a read lock', raw.strip()))
                    break
                if blocks[k2]:
                    out.append((p, start + idx, name, 'BLOCKS', f'{n}() -> ' + ', '.join(sorted(blocks[k2])), raw.strip()))
                    break
                if slows[k2] and n not in SLOW_PRIMS:
                    out.append((p, start + idx, name, 'SLOW', f'{n}() -> ' + ', '.join(sorted(slows[k2])), raw.strip()))
                    break
seen = set()
for r in sorted(out):
    k = (r[0], r[1], r[3])
    if k in seen:
        continue
    seen.add(k)
    print(f'{r[3]:8s} {os.path.relpath(r[0], ROOT)}:{r[1]} in {r[2]}(): {r[4]}\n           {r[5][:150]}')
print(f'\n{len(methods)} methods; {sum(direct_write.values())} take the write lock directly', file=sys.stderr)

# ---------------------------------------------------------------------------------------------------
# 5. Graph access outside any lock region of its own method, and refs that cross an unlock.
ACCESS = re.compile(r'\.(vertices|edges|incomingEdges|outgoingEdges|getTarget|getSource|addVertex|addEdge|'
                    r'getTimepoint|localize|getDoublePosition|getCovariance|getBoundingSphereRadiusSquared|refTo|'
                    r'setPosition|setCovariance|getInternalPoolIndex)\s*\(')
REFDECL = re.compile(r'\b(?:final\s+)?(Spot|Link|List<\s*Spot\s*>|List<\s*Link\s*>|Spot\[\])\s+(\w+)\s*[=;,)]')
def regions(body):
    """Per line: frozenset of held lock kinds."""
    stack, held = [], []
    for raw in body:
        l = strip(raw)
        opened = closed = False
        for kind in ('readLock', 'writeLock'):
            if f'{kind}().lock()' in l:
                stack.append(kind); opened = True
            if f'{kind}().unlock()' in l:
                for s in range(len(stack) - 1, -1, -1):
                    if stack[s] == kind:
                        stack.pop(s); break
                closed = True
        held.append(frozenset(stack) if not closed else frozenset())
    return held

unlocked_access = defaultdict(list)   # method -> lines
cross = []
for key, body in methods.items():
    p, name, start = key
    held = regions(body)
    has_lock = any(held)
    for idx, raw in enumerate(body[1:], 1):
        l = strip(raw)
        if not held[idx] and ACCESS.search(l) and 'Lock()' not in l:
            unlocked_access[key].append(start + idx)
    if not has_lock:
        continue
    # ref variables (declared anywhere in the method, or parameters on the signature line)
    sigtext = ' '.join(body[:3])
    names = {m.group(2) for m in REFDECL.finditer(sigtext)} | {m.group(2) for raw in body for m in REFDECL.finditer(strip(raw))}
    # region ids: consecutive locked stretches
    rid, cur, last = [], 0, False
    for h in held:
        if h and not last:
            cur += 1
        rid.append(cur if h else 0)
        last = bool(h)
    for v in names:
        pat = re.compile(r'\b' + re.escape(v) + r'\b')
        used = sorted({rid[i] for i, raw in enumerate(body) if i and pat.search(strip(raw))})
        locked = [r for r in used if r]
        # used in two different locked stretches => it crossed an unlock
        if len(locked) >= 2:
            lines = [start + i for i, raw in enumerate(body) if i and pat.search(strip(raw)) and rid[i]]
            cross.append((p, start, name, v, sorted(set(locked)), lines[:6]))

# Which methods with unlocked graph access are only ever called from inside a lock region?
called_locked, called_unlocked = defaultdict(int), defaultdict(int)
for key, body in methods.items():
    held = regions(body)
    for idx, raw in enumerate(body[1:], 1):
        for n in call.findall(strip(raw)):
            if n in by_name:
                (called_locked if held[idx] else called_unlocked)[n] += 1
print('\n==== UNLOCKED graph access (method never takes a lock itself)')
for key, lines in sorted(unlocked_access.items(), key=lambda kv: (kv[0][0], kv[0][2])):
    p, name, start = key
    if any(regions(methods[key])):
        tag = 'MIXED'   # has its own lock regions but touches the graph outside them too
    elif called_unlocked[name] == 0 and called_locked[name] > 0:
        continue        # only ever called under a caller's lock: fine
    else:
        tag = 'UNLOCKED' if called_locked[name] == 0 else 'SOMETIMES'
    print(f'{tag:9s} {os.path.relpath(p, ROOT)}:{start} {name}()  lines {lines[:5]}{"..." if len(lines) > 5 else ""}'
          f'  (called {called_locked[name]}x under a lock, {called_unlocked[name]}x not)')
print('\n==== REFS used in two separate lock regions of one method (crossed an unlock)')
for p, start, name, v, rs, lines in sorted(cross):
    print(f'CROSS     {os.path.relpath(p, ROOT)}:{start} {name}(): {v} in regions {rs}, lines {lines}')
