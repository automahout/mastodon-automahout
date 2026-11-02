"""Proof that extract_stages.py moved code and changed nothing: re-inline the stage methods (one tab
more, 'return Next.ABANDON;' -> 'continue windowLoop;', the STOP block -> 'return fallback;', no trailing
'return Next.CONTINUE;') and compare, non-blank line by line, with the loop before the extraction.

    python check_stages_reinline.py <ElephantTool.java before> <ElephantTool.java after>
"""
import sys

new = open(sys.argv[2]).read().split('\n')
old = open(sys.argv[1]).read().split('\n')


def find(lines, t, after=0):
    for i in range(after, len(lines)):
        if t in lines[i]:
            return i
    raise SystemExit('not found: ' + t)


a = find(old, '// Clear any existing spots in this window before fresh detection.')
orig = old[a:find(old, '// Step 6: Validate', a)]
rebuilt = []
for name in ['detectStage', 'linkStage', 'correctStage', 'refineStage', 'consistencyStage']:
    s = find(new, 'private static Next %s( final WindowRun w )' % name)
    i, body = s + 2, []
    while new[i] != '\t}':
        body.append(new[i])
        i += 1
    assert body[-1].strip() == 'return Next.CONTINUE;'
    body, k = body[:-1], 0
    while k < len(body):
        l = body[k]
        if l.strip() == '{' and k + 3 < len(body) and body[k + 1].strip() == 'w.stopMessage = fallback;':
            rebuilt.append('\t' + l.replace('{', 'return fallback;'))
            k += 4
            continue
        rebuilt.append(('\t' + l if l else l).replace('return Next.ABANDON;', 'continue windowLoop;'))
        k += 1
f = lambda L: [l for l in L if l.strip()]
same = f(orig) == f(rebuilt)
print('%d lines; %s' % (len(f(orig)), 'identical' if same else 'DIFFERENT'))
sys.exit(0 if same else 1)
