import sys
p, tsv = sys.argv[1], sys.argv[2]
src = open(p).read()
rows = [l.rstrip('\n').split('\t') for l in open(tsv)]
edits = []  # (start, end, replacement)
params = []
fields = []
for r in rows:
    kind, name, typ = r[0], r[1], r[2]
    st, en = int(r[3]), int(r[4])
    if kind == 'USE':
        edits.append((st, en, 'w.' + name))
    elif kind == 'PARAM':
        params.append(name)
        fields.append((typ, name))
    elif kind == 'FORVAR':
        fields.append((typ, name))
        i = src.index('=', st)
        assert src[st:i].split() [-1] == name, src[st:i]
        edits.append((st, i + 1, 'w.' + name + ' ='))
    elif kind == 'DECL':
        fields.append((typ, name))
        if r[5] == '1':
            ist = int(r[6])
            head = src[st:ist]
            assert head.rstrip().endswith('='), (name, head)
            assert name in head, (name, head)
            edits.append((st, ist, 'w.' + name + ' = '))
        else:
            stmt = src[st:en]
            assert '=' not in stmt, (name, stmt)
            # remove the declaration statement, including its semicolon
            j = en
            if src[j-1] != ';':
                j = src.index(';', en) + 1
            edits.append((st, j, '/* ' + name + ': a WindowRun field */'))
edits.sort()
for a, b in zip(edits, edits[1:]):
    assert a[1] <= b[0], ('overlap', a, b)
# insert the WindowRun set-up at the start of the method body
mpos = src.index('private static String runAdaptiveTrack(')
body = src.index('{', src.index(')', mpos + 30)) + 1
# find the real ')' closing the parameter list: the first ')' followed by whitespace and '{'
import re
m = re.compile(r'\)\s*\{').search(src, mpos)
body = m.end()
setup = '\n\t\t// Every variable the window loop\'s stages share lives here, so a stage can be a method of its own.\n'
setup += '\t\tfinal WindowRun w = new WindowRun();\n'
for n in params:
    setup += '\t\tw.%s = %s;\n' % (n, n)
out = src[:body] + setup
pos = body
for st, en, rep in edits:
    if st < body:
        continue
    out += src[pos:st] + rep
    pos = en
out += src[pos:]
# the WindowRun class, appended before the class's final brace
cls = '\n\t/** The state the window loop of {@link #runAdaptiveTrack} shares across its stages. */\n'
cls += '\tprivate static final class WindowRun\n\t{\n'
seen = set()
for typ, name in fields:
    if name in seen:
        continue
    seen.add(name)
    cls += '\t\t%s %s;\n\n' % (typ, name)
cls = cls.rstrip('\n') + '\n\t}\n'
last = out.rstrip().rindex('}')
out = out[:last] + cls + '}\n'
open(p, 'w').write(out)
print('edits', len(edits), 'fields', len(seen))
