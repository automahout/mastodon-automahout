# Writing an Automahout tool

Automahout chooses tools for each stage of tracking from the data's properties. A tool from another
jar takes part exactly like a built-in one: put the jar on Fiji's class path (the `jars` folder, or
an update site) and it is found at the start of a run.

## The contract

Implement `org.mastodon.mamut.automahout.tool.AutomahoutTool` and annotate the class:

```java
@Plugin( type = AutomahoutTool.class )
public class MyDetector implements AutomahoutTool
{
	public String getId() { return "mylab.detect"; }          // unique; prefix it with your lab/project
	public String getDescription() { return "..."; }          // shown to the LLM and in the log
	public ToolRole getRole() { return ToolRole.DETECT; }      // the stage it serves

	public List< ToolParameter > getParameters()              // typed, so the LLM and dialogs can use them
	{
		return Arrays.asList( ToolParameter.number( "threshold", "in background spreads", 3.0 ).range( 0, 50 ) );
	}

	public Applicability applicability( DataProfile data )    // the rule half of the choice
	{
		if ( Boolean.FALSE.equals( data.fluorescenceLike ) )
			return Applicability.notFor( "fluorescence only" );
		return Applicability.suits( 0.8, "bright nuclei on a dark background" );
	}

	public String execute( ToolContext ctx, Map< String, Object > params ) { ... }
}
```

The class needs a public no-argument constructor. `ExampleExternalTools` in the tests shows the
contract in a package of its own; it is left unannotated on purpose, because TestDrive runs with the
test classes on its class path and the test registers those dummies itself.

## Rules a tool must follow

The user edits the graph in BigDataViewer and TrackScheme while a run goes on.

- Change the graph only through `GraphEdits` (add/remove spots and links). It takes the write lock,
  refuses an edit through a spot that was deleted or replaced in the viewer meanwhile (`GraphWatch`),
  and refuses a link that does not go forward in time.
- Never hold a spot or link ref, or a pool index, across a stretch without the lock -- an image read, a
  server call, a question. Record the position instead, or check `GraphWatch.dirty` before acting.
- Never read the image while holding the graph lock: collect under the read lock, read with none,
  write under the write lock (`scripts/audit_graph_locks.py src/main/java` checks this).
- Ask the user through `ToolContext` (`askUser`, `chooseImage`, `waitForEdit`) and remember the answer
  so it is not asked twice.

## Replacing a built-in tool

Use the built-in tool's id and a higher priority:
`@Plugin( type = AutomahoutTool.class, priority = Priority.HIGH )`. At equal priority the built-in
tool is kept, so a plugin never replaces one by accident. Every tool added from outside, and every
replacement, is written to the run's log.

## Correction passes

A tool is a whole stage; a correction pass is one rule inside a correction step of every window.
Three steps are plans of passes now: `DETECTED` runs on each frame as ELEPHANT detects it (shape fits and
the checks that delete what is not a cell -- `tool/impl/DetectedPasses.java`), `FRAME` runs once per
timepoint (pre-filters, link pruning, gap
bridging, isolated-spot deletion -- `autocorrect/FramePasses.java`), `LINEAGE` once over the window's
finished tracks (merge repair, division checks, reconnection, flicker deletion --
`autocorrect/LineagePasses.java`). The run logs each plan when it first runs ("Correction plan, per frame:
...").

A pass from another jar implements `CorrectionPass` and is a SciJava plugin:

```java
@Plugin( type = CorrectionPass.class )
public class DropBorderDebris implements CorrectionPass
{
	public String getId() { return "mylab.border_debris"; }
	public String getDescription() { return "Deletes small spots touching the image border."; }
	public Stage getStage() { return Stage.FRAME; }
	public ToolRole getRole() { return ToolRole.CORRECT_DETECTIONS; }
	public Effect getEffect() { return Effect.SPOTS_DELETED; }
	public String after() { return "frame.sandwiched"; }          // its place in the plan
	public Applicability applicability( DataProfile d )             // the data it suits
	{
		return d.volumetric ? Applicability.notFor( "2D only" ) : Applicability.always();
	}
	public boolean runsAt( PassScope s ) { return s.timepoint > 0; } // the moment: settings, position
	public int run( PassScope s ) { /* edit s.ctx's graph through GraphEdits */ return 0; }
}
```

- **Place:** a new id goes after the pass `after()` names (at the end if it names none, or one not in
  the plan). The same id as a built-in pass with a priority above normal replaces that pass.
- **Two kinds of condition.** `applicability` is about the data -- 2D or 3D, modality, which detector
  this run uses (`DataProfile.pretrainedDetector`) -- and when it rules a pass out the run logs the
  reason once. `runsAt` is about the moment: a setting, the frame's position, what an earlier pass
  counted (`scope.count( id )`). It is silent.
- **What earlier passes measured** is in the scope: the lineage plan's `lineage.thresholds` step leaves
  the displacement cap, the minimum branch length and division interval there for the passes after it.
- **Integrity:** `integrityCheckAfter()` names a check of the graph's link lists after the pass's place;
  a damaged graph then stops the run instead of hanging the viewer.
- The rules above apply to passes as to tools.

## Tools in other languages

Tools in Python or any other language plug in as server tools over HTTP/JSON: see
`docs/remote-tools.md` for the protocol and `examples/remote-tools/` for a working server offering
Cellpose and StarDist. Their tools appear in the registry beside the Java ones and are chosen the same
way; *Plugins > Automahout > Run a Tool...* lists them all with their applicability.
