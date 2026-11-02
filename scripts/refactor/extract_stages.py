"""Cut the window loop of ElephantTool.runAdaptiveTrack into stage methods run from a plan.

Run after hoist_apply.py: every variable the stages share is then a WindowRun field, so a block can be
moved as it is. The only rewrites: 'continue windowLoop;' -> 'return Next.ABANDON;', 'return fallback;'
-> stop the run with that message, one tab less indentation.

    python extract_stages.py ElephantTool.java
"""
import sys

p = sys.argv[1]
lines = open(p).read().split('\n')


def find(text, after=0):
    for i in range(after, len(lines)):
        if text in lines[i]:
            return i
    raise SystemExit('not found: ' + text)


loop = find('\t\twindowLoop:')
b_detect = find('// Clear any existing spots in this window before fresh detection.', loop)
b_link = find('// Step 4: Tracking', b_detect)
b_correct = find('// The per-frame displacement cap, re-measured', b_link)
b_refine = find('// Step 5b: ITERATIVE', b_correct)
b_consist = find('// Step 5c: Topology', b_refine)
b_report = find('// Step 6: Validate', b_consist)

STAGES = [
    ('detectStage', b_detect, b_link,
     '[1/6]..[3/6]: test detection, tuning (train / re-detect rounds, or GT rounds), the one full-frame\n'
     '\t * commit, and its invariant check. Built-in DETECT stage.'),
    ('linkStage', b_link, b_correct, '[4/6]: ELEPHANT nearest-neighbour linking, and its check. Built-in LINK stage.'),
    ('correctStage', b_correct, b_refine,
     'The per-frame displacement cap, then [5/6]: per-timepoint correction, and its check. Built-in\n'
     '\t * CORRECT_DETECTIONS stage.'),
    ('refineStage', b_refine, b_consist,
     '[5b/6]: enrich, retrain, re-detect, re-link, re-correct while recall grows, and its check. Built-in\n'
     '\t * TRAIN stage.'),
    ('consistencyStage', b_consist, b_report,
     '[5c/6]: topology consistency over this window and one before it, and its check. Built-in\n'
     '\t * CORRECT_LINEAGE stage.'),
]

methods = []
for name, a, b, doc in STAGES:
    body = []
    for l in lines[a:b]:
        assert l == '' or l.startswith('\t\t\t'), (name, l)
        l = l[1:] if l else l
        if l.strip() == 'continue windowLoop;':
            l = l.replace('continue windowLoop;', 'return Next.ABANDON;')
        elif l.strip() == 'return fallback;':
            ind = l[:len(l) - len(l.lstrip())]
            body.append(ind + '{')
            body.append(ind + '\tw.stopMessage = fallback;')
            body.append(ind + '\treturn Next.STOP;')
            l = ind + '}'
        assert 'windowLoop' not in l, (name, l)
        body.append(l)
    while body and body[-1] == '':
        body.pop()
    m = ['\t/**', '\t * ' + doc, '\t */', '\tprivate static Next %s( final WindowRun w )' % name, '\t{']
    m += body
    m += ['\t\treturn Next.CONTINUE;', '\t}', '']
    methods.append('\n'.join(m))

driver = [
    '\t\t\t// The window\'s stages, in plan order (see WINDOW_PLAN). A stage that gives the window up has',
    '\t\t\t// already put it back as its own check required; one that ends the run has said why.',
    '\t\t\tfor ( final WindowStage stage : WINDOW_PLAN )',
    '\t\t\t{',
    '\t\t\t\tfinal Next next = stage.run( w );',
    '\t\t\t\tif ( next == Next.ABANDON )',
    '\t\t\t\t\tcontinue windowLoop;',
    '\t\t\t\tif ( next == Next.STOP )',
    '\t\t\t\t\treturn w.stopMessage;',
    '\t\t\t}',
    '',
]
lines[b_detect:b_report] = driver

# plan infrastructure + the stage methods, before the WindowRun class
infra = '''	/** What the window loop does after a stage. */
	private enum Next
	{
		/** Go on to the next stage. */
		CONTINUE,
		/** Give this window up (the stage has already put it back, and set a retry if it wants one). */
		ABANDON,
		/** End the run, returning {@link WindowRun#stopMessage}. */
		STOP
	}

	/**
	 * One stage of the window loop: an id, the {@link ToolRole} it serves, and what it does to a window.
	 * The built-in plan below is the order the loop always ran in; a planner choosing per stage from the
	 * tools that suit the data is the next step (docs/architecture.md, section 1.6).
	 */
	private static final class WindowStage
	{
		final String id;

		final ToolRole role;

		private final java.util.function.Function< WindowRun, Next > body;

		WindowStage( final String id, final ToolRole role, final java.util.function.Function< WindowRun, Next > body )
		{
			this.id = id;
			this.role = role;
			this.body = body;
		}

		Next run( final WindowRun w )
		{
			return body.apply( w );
		}

		@Override
		public String toString()
		{
			return id + " (" + role + ")";
		}
	}

	/** The built-in plan: the stages in the order the window loop has always run them. */
	private static final List< WindowStage > WINDOW_PLAN = java.util.Collections.unmodifiableList( Arrays.asList(
			new WindowStage( "elephant.detect", ToolRole.DETECT, ElephantTool::detectStage ),
			new WindowStage( "elephant.link", ToolRole.LINK, ElephantTool::linkStage ),
			new WindowStage( "autocorrect.window", ToolRole.CORRECT_DETECTIONS, ElephantTool::correctStage ),
			new WindowStage( "elephant.refine", ToolRole.TRAIN, ElephantTool::refineStage ),
			new WindowStage( "topology.consistency", ToolRole.CORRECT_LINEAGE, ElephantTool::consistencyStage ) ) );

	/** The plan as one line for the log: {@code a (ROLE) -> b (ROLE) -> ...}. */
	static String describePlan()
	{
		final StringBuilder sb = new StringBuilder();
		for ( final WindowStage s : WINDOW_PLAN )
			sb.append( sb.length() == 0 ? "" : " -> " ).append( s );
		return sb.toString();
	}

'''
wr = find('\tprivate static final class WindowRun')
# back up over its javadoc line
if lines[wr - 1].strip().startswith('/**'):
    wr -= 1
lines[wr:wr] = (infra + '\n'.join(methods) + '\n').split('\n')
src = '\n'.join(lines)
# the stop message field
src = src.replace('\tprivate static final class WindowRun\n\t{\n',
                  '\tprivate static final class WindowRun\n\t{\n\t\t/** Set by a stage that ends the run; what runAdaptiveTrack returns. */\n\t\tString stopMessage;\n\n', 1)
# the plan in the run's log, right after the run header
hdr = '"=== Adaptive tracking: %d windows of %d tp, t=%d..%d ===",'
i = src.index(hdr)
j = src.index(';', i) + 1
src = src[:j] + '\n\t\tw.context.log( "  Window plan: " + describePlan() );' + src[j:]
open(p, 'w').write(src)
print('stages', [s[0] for s in STAGES], 'lines', [s[2] - s[1] for s in STAGES])
