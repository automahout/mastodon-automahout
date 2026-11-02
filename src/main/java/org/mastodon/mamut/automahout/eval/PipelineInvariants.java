/*-
 * #%L
 * mastodon-automahout
 * %%
 * Copyright (C) 2025 Ko Sugawara
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.mastodon.mamut.automahout.eval;

import java.util.ArrayList;
import java.util.List;

import org.mastodon.mamut.automahout.tool.GraphIntegrity;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.automahout.tool.SpotOriginDump;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cheap, always-on structural checks run between pipeline phases, so a phase that corrupts the graph
 * is reported <em>at the moment it happens</em> rather than being inferred hours later from a bad
 * final result.
 *
 * <p>This exists because of an observed, repeating failure mode in this codebase: a new safety rule
 * (never delete a protected spot; stay inside the current window; respect the expected cell-count
 * ceiling) is added to <em>some</em> of the ~39 graph-mutation sites spread across
 * {@code AutoCorrectOrchestrator} and {@code ElephantTool}, and the sites it was not added to keep
 * violating it silently. Confirmed instances: {@code runTopologyConsistency} being handed the
 * movie-wide timepoint count instead of {@code wEnd + 1}; {@code deleteUnstableSpots} hardcoding
 * {@code startTp = 0}; two deletion passes that ignored the protected tag. All three are invisible in
 * the logs and only surface as "the final track count is lower than it should be".</p>
 *
 * <p>Rather than auditing every call site again after each change, the checks here observe the
 * <em>result</em>: take a {@link Snapshot} before a phase, compare after it, and report any change
 * that the phase had no business making. A mutation site added tomorrow is covered automatically.</p>
 *
 * <h2>Cost</h2>
 * <p>A snapshot is a single O(V) pass over the graph's vertices with no image access, so it is safe
 * to take after every phase even on large movies.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * Snapshot snap = PipelineInvariants.snapshot( ctx );
 * // ... run phase [4/6] ...
 * snap = PipelineInvariants.checkAndLog( ctx, "[4/6] link", snap, wStart, wEnd,
 *         PipelineInvariants.limits() );
 * </pre>
 *
 * <p>By default violations are logged and execution continues — aborting a multi-hour tracking run on
 * a single bad timepoint would be worse than the bug. Set {@link #STRICT_MODE_KEY} in the context's
 * shared data to {@code Boolean.TRUE} (tests, headless replay) to have {@code ERROR}-severity
 * violations throw instead.</p>
 */
public final class PipelineInvariants
{
	private static final Logger log = LoggerFactory.getLogger( PipelineInvariants.class );

	/**
	 * {@link ToolContext#getSharedData} key: set to {@code Boolean.TRUE} to make {@code ERROR}-severity
	 * violations throw {@link InvariantViolationException} instead of only being logged. Off by default
	 * so an interactive run reports and carries on.
	 */
	public static final String STRICT_MODE_KEY = "invariants.strict";

	private PipelineInvariants()
	{}

	/** Thrown from {@link #checkAndLog} on an {@code ERROR} violation when strict mode is enabled. */
	public static class InvariantViolationException extends IllegalStateException
	{
		private static final long serialVersionUID = 1L;

		public InvariantViolationException( final String message )
		{
			super( message );
		}
	}

	// =======================================================================
	// Snapshot

	/**
	 * Per-timepoint counts of everything the invariants below need, captured in one pass. Immutable
	 * once returned.
	 */
	public static final class Snapshot
	{
		/** Spots at each timepoint, indexed by timepoint. */
		private final int[] spots;

		/** Spots at each timepoint carrying the user's protected tag (all zero when protection is off). */
		private final int[] protectedSpots;

		/** Spots at each timepoint having BOTH an incoming and an outgoing edge. */
		private final int[] continuous;

		/** Spots at each timepoint having NEITHER an incoming nor an outgoing edge. */
		private final int[] isolated;

		private Snapshot( final int[] spots, final int[] protectedSpots, final int[] continuous,
				final int[] isolated )
		{
			this.spots = spots;
			this.protectedSpots = protectedSpots;
			this.continuous = continuous;
			this.isolated = isolated;
		}

		/** Number of timepoints covered (array length, i.e. {@code maxTimepoint + 1}). */
		public int numTimepoints()
		{
			return spots.length;
		}

		/** Spots at {@code t}, or 0 if {@code t} is out of range. */
		public int spotsAt( final int t )
		{
			return t >= 0 && t < spots.length ? spots[ t ] : 0;
		}

		/** Protected spots at {@code t}, or 0 if {@code t} is out of range. */
		public int protectedAt( final int t )
		{
			return t >= 0 && t < protectedSpots.length ? protectedSpots[ t ] : 0;
		}

		/** Total spots across all timepoints. */
		public int totalSpots()
		{
			int n = 0;
			for ( final int c : spots )
				n += c;
			return n;
		}

		/** Total spots across all timepoints carrying the user's protected tag. */
		public int totalProtected()
		{
			int n = 0;
			for ( final int c : protectedSpots )
				n += c;
			return n;
		}

		/** Total spots in {@code [from, to]} inclusive. */
		public int spotsIn( final int from, final int to )
		{
			int n = 0;
			for ( int t = Math.max( 0, from ); t <= Math.min( spots.length - 1, to ); t++ )
				n += spots[ t ];
			return n;
		}

		/** Total isolated spots in {@code [from, to]} inclusive. */
		public int isolatedIn( final int from, final int to )
		{
			int n = 0;
			for ( int t = Math.max( 0, from ); t <= Math.min( isolated.length - 1, to ); t++ )
				n += isolated[ t ];
			return n;
		}

		/**
		 * Fraction of INTERIOR spots in {@code (from, to)} — exclusive at both ends — that have both an
		 * incoming and an outgoing edge. Deliberately identical in definition to
		 * {@code ElephantTool.computeContinuity}'s first return value, so the two never disagree.
		 *
		 * @return 0..1, or {@code Double.NaN} when the range has no interior timepoints (a window
		 *         shorter than 3 tp), in which case continuity is undefined rather than zero
		 */
		public double continuity( final int from, final int to )
		{
			int total = 0, ok = 0;
			for ( int t = Math.max( 0, from + 1 ); t <= Math.min( spots.length - 1, to - 1 ); t++ )
			{
				total += spots[ t ];
				ok += continuous[ t ];
			}
			return total > 0 ? ( double ) ok / total : Double.NaN;
		}
	}

	/**
	 * Builds a snapshot directly from per-timepoint counts instead of from a graph. Exists so the
	 * comparison logic in {@link #check} is unit-testable without standing up a Mastodon model — that
	 * logic is the part that has to be right, and requiring a live graph to exercise it is exactly what
	 * kept this class's checks from existing earlier.
	 *
	 * @throws IllegalArgumentException if the four arrays differ in length
	 */
	public static Snapshot of( final int[] spots, final int[] protectedSpots, final int[] continuous,
			final int[] isolated )
	{
		if ( spots.length != protectedSpots.length || spots.length != continuous.length
				|| spots.length != isolated.length )
			throw new IllegalArgumentException( "all four per-timepoint arrays must have the same length" );
		return new Snapshot( spots.clone(), protectedSpots.clone(), continuous.clone(), isolated.clone() );
	}

	/**
	 * Captures the current state of the whole movie in one O(V) pass over the graph.
	 *
	 * <p>Scoped to the WHOLE movie, not to the current window, on purpose: the out-of-window check
	 * below is only able to catch a phase that reaches outside its window if the snapshot covers the
	 * region it is not supposed to touch.</p>
	 */
	public static Snapshot snapshot( final ToolContext ctx )
	{
		final int n = ctx.getProjectModel().getMaxTimepoint() + 1;
		final int[] spots = new int[ n ];
		final int[] prot = new int[ n ];
		final int[] cont = new int[ n ];
		final int[] iso = new int[ n ];

		final Model model = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();
		final ProtectedSpots.Config protectedConfig = ProtectedSpots.fromContext( ctx );

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				if ( t < 0 || t >= n )
					continue; // defensive: a spot outside the declared range is not this check's business
				spots[ t ]++;
				if ( protectedConfig != null && ProtectedSpots.isProtected( model, spot, protectedConfig ) )
					prot[ t ]++;
				final boolean hasIn = spot.incomingEdges().size() > 0;
				final boolean hasOut = spot.outgoingEdges().size() > 0;
				if ( hasIn && hasOut )
					cont[ t ]++;
				else if ( !hasIn && !hasOut )
					iso[ t ]++;
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return new Snapshot( spots, prot, cont, iso );
	}

	/**
	 * One line, logged once per run, stating that these checks are live and what they currently have to
	 * work with.
	 *
	 * <p>Needed because the checks are silent when they pass, which makes a clean log indistinguishable
	 * from a build in which they are not running at all — and this codebase is exercised almost entirely
	 * by launching the GUI against a separately-installed jar. It also surfaces the one setup mistake
	 * that would quietly neuter the most important check: protection reporting zero spots because the
	 * tag set/label was not configured for this project.</p>
	 */
	public static String activeChecksSummary( final Snapshot baseline )
	{
		return String.format(
				"Structural invariants active — every phase is checked for protected-spot loss (%d spot(s) "
						+ "currently under guard%s) and for changes outside its declared timepoint range; "
						+ "per-phase limits add detection-ceiling, churn and continuity checks. Violations "
						+ "appear inline as \"!! INVARIANT\"; silence means every check passed.",
				baseline.totalProtected(),
				baseline.totalProtected() == 0
						? " — no protected tag configured, so that check is inert"
						: "" );
	}

	// =======================================================================
	// Limits

	/** Returns a {@link Limits} with only the two zero-configuration checks enabled. */
	public static Limits limits()
	{
		return new Limits();
	}

	/**
	 * Tunable thresholds for the optional checks. The two checks that need no configuration —
	 * protected-spot loss and out-of-window mutation — are always on and cannot be disabled here,
	 * because every confirmed instance of the recurring bug class was one of those two.
	 */
	public static final class Limits
	{
		private double expectedCeiling = 0.0;

		private double ceilingRatio = 2.0;

		private int statedCellsPerFrame = 0;

		private double maxAddedFraction = Double.POSITIVE_INFINITY;

		private double maxDeletedFraction = Double.POSITIVE_INFINITY;

		private double continuityDropTolerance = Double.POSITIVE_INFINITY;

		private int outOfWindowMargin = 0;

		private Limits()
		{}

		/**
		 * Expected spots per timepoint for this dataset (the caller's {@code coldStartCeiling}). A
		 * timepoint exceeding {@code ceiling * ratio} after the phase is reported as a detection
		 * explosion. {@code 0} (the default) disables the check.
		 */
		public Limits expectedCeiling( final double spotsPerTp )
		{
			this.expectedCeiling = spotsPerTp;
			return this;
		}

		/** Multiple of {@link #expectedCeiling} above which a timepoint counts as exploded. Default 2.0. */
		public Limits ceilingRatio( final double ratio )
		{
			this.ceilingRatio = ratio;
			return this;
		}

		/**
		 * Fewest cells per frame the user's stated count still allows — that is
		 * {@code ExpectedCellCount.toleratedFloor} of it, NOT the raw number, so this agrees with the
		 * passes that honour the floor about what the estimate permits. A timepoint this phase takes
		 * from at-or-above that count to below it is reported.
		 *
		 * <p>The mirror image of {@link #expectedCeiling}, and added because its absence was asymmetric
		 * in a way that mattered: an exploding phase was caught inline while a collapsing one was not,
		 * so every over-deletion had to be found by reading a run log afterwards. The floor itself is
		 * defined in {@code ExpectedCellCount} and honoured by the passes that consult it; this check
		 * exists for the ones that do not — the pass that took a frame held at 250 down to 193 honoured
		 * no floor and reported nothing. {@code 0} (the default) disables the check.</p>
		 */
		public Limits statedCellsPerFrame( final int cellsPerTp )
		{
			this.statedCellsPerFrame = cellsPerTp;
			return this;
		}

		/**
		 * Largest share of a timepoint's spots a phase may ADD, as a fraction of the count it started
		 * with. Meant for correction/gap-filling phases, where adding a quarter of a timepoint from
		 * interpolation already indicates the underlying detection failed. Leave unset for detection
		 * phases, whose whole job is to add spots.
		 */
		public Limits maxAddedFraction( final double fraction )
		{
			this.maxAddedFraction = fraction;
			return this;
		}

		/** Largest share of a timepoint's spots a phase may DELETE, as a fraction of the starting count. */
		public Limits maxDeletedFraction( final double fraction )
		{
			this.maxDeletedFraction = fraction;
			return this;
		}

		/**
		 * Largest drop in window continuity (absolute, 0..1) the phase may cause. A correction phase
		 * that lowers continuity is undoing tracking rather than improving it.
		 */
		public Limits continuityDropTolerance( final double drop )
		{
			this.continuityDropTolerance = drop;
			return this;
		}

		/**
		 * Timepoints of slack on each side of the window before an out-of-window change is reported.
		 * Use for phases that legitimately reach one timepoint back to link across a window boundary.
		 * Default 0.
		 */
		public Limits outOfWindowMargin( final int margin )
		{
			this.outOfWindowMargin = margin;
			return this;
		}
	}

	// =======================================================================
	// Checks

	/** Severity of a single violation. */
	public enum Severity
	{
		/** A rule that should never be broken; throws in strict mode. */
		ERROR,
		/** Suspicious but potentially legitimate; always advisory. */
		WARN
	}

	/** One violated invariant. */
	public static final class Violation
	{
		private final Severity severity;

		private final String message;

		/** Whether this is the explosion-ceiling check, the one rule a ceiling nobody stated can trip. */
		private final boolean ceiling;

		Violation( final Severity severity, final String message )
		{
			this( severity, message, false );
		}

		Violation( final Severity severity, final String message, final boolean ceiling )
		{
			this.severity = severity;
			this.message = message;
			this.ceiling = ceiling;
		}

		public boolean isCeiling()
		{
			return ceiling;
		}

		public Severity severity()
		{
			return severity;
		}

		public String message()
		{
			return message;
		}

		@Override
		public String toString()
		{
			return severity + ": " + message;
		}
	}

	/**
	 * Compares {@code after} against {@code before} and returns every invariant the phase violated.
	 * Pure: logs nothing, throws nothing, mutates nothing — {@link #checkAndLog} is the reporting
	 * wrapper.
	 *
	 * @param before  state captured before the phase ran
	 * @param after   state captured after it ran
	 * @param wStart  first timepoint the phase was allowed to modify
	 * @param wEnd    last timepoint the phase was allowed to modify (inclusive)
	 * @param limits  thresholds for the optional checks
	 */
	public static List< Violation > check( final Snapshot before, final Snapshot after,
			final int wStart, final int wEnd, final Limits limits )
	{
		final List< Violation > out = new ArrayList<>();
		final int n = Math.max( before.numTimepoints(), after.numTimepoints() );

		// --- 1. Protected spots must never be deleted or untagged, anywhere, by any phase. ---
		for ( int t = 0; t < n; t++ )
		{
			final int lost = before.protectedAt( t ) - after.protectedAt( t );
			if ( lost > 0 )
				out.add( new Violation( Severity.ERROR, String.format(
						"t=%d: %d protected spot(s) disappeared (%d -> %d). A protected spot is a "
								+ "human-confirmed result and must survive every phase verbatim.",
						t, lost, before.protectedAt( t ), after.protectedAt( t ) ) ) );
		}

		// --- 2. A window-scoped phase must not change any timepoint outside its window. ---
		// This is the check that would have caught the topology-consistency pass rescanning (and
		// re-deleting from) the entire tracked-so-far history on every single window.
		final int loGuard = wStart - limits.outOfWindowMargin;
		final int hiGuard = wEnd + limits.outOfWindowMargin;
		int outsideTps = 0, outsideDelta = 0;
		int firstOutside = -1;
		for ( int t = 0; t < n; t++ )
		{
			if ( t >= loGuard && t <= hiGuard )
				continue;
			final int delta = after.spotsAt( t ) - before.spotsAt( t );
			if ( delta != 0 )
			{
				outsideTps++;
				outsideDelta += delta;
				if ( firstOutside < 0 )
					firstOutside = t;
			}
		}
		if ( outsideTps > 0 )
			out.add( new Violation( Severity.ERROR, String.format(
					"phase was scoped to t=%d..%d but changed %d timepoint(s) outside it "
							+ "(net %+d spots, first at t=%d). A window-scoped phase reaching into "
							+ "already-finalized timepoints silently caps the final track count.",
					wStart, wEnd, outsideTps, outsideDelta, firstOutside ) ) );

		// --- 3. Detection explosion against the dataset's expected cell count. ---
		// Reports only timepoints THIS phase pushed over the ceiling. A timepoint that was already over
		// before the phase ran is not this phase's doing: the phase that caused it already reported it,
		// and re-reporting the same unresolved state after every subsequent phase buried the one real
		// finding under three copies of itself — including one attributed to a refinement phase that had
		// logged "Skipping refinement" and never ran.
		if ( limits.expectedCeiling > 0.0 )
		{
			final double threshold = limits.expectedCeiling * limits.ceilingRatio;
			int explodedTps = 0, worstTp = -1, worstCount = 0;
			for ( int t = Math.max( 0, wStart ); t <= wEnd && t < n; t++ )
			{
				if ( after.spotsAt( t ) > threshold && before.spotsAt( t ) <= threshold )
				{
					explodedTps++;
					if ( after.spotsAt( t ) > worstCount )
					{
						worstCount = after.spotsAt( t );
						worstTp = t;
					}
				}
			}
			if ( explodedTps > 0 )
				out.add( new Violation( Severity.ERROR, String.format(
						"%d timepoint(s) pushed over %.1f spots (%.1fx the expected %.1f/tp); worst t=%d "
								+ "with %d. Detection has exploded into background.",
						explodedTps, threshold, limits.ceilingRatio, limits.expectedCeiling,
						worstTp, worstCount ), true ) );
		}

		// --- 3b. Collapse below the cell count the user stated. ---
		// As with the ceiling above, only timepoints THIS phase pushed through the floor are reported;
		// one already below it before the phase ran belongs to whichever phase put it there.
		if ( limits.statedCellsPerFrame > 0 )
		{
			int collapsedTps = 0, worstTp = -1, worstCount = Integer.MAX_VALUE;
			for ( int t = Math.max( 0, wStart ); t <= wEnd && t < n; t++ )
			{
				if ( after.spotsAt( t ) < limits.statedCellsPerFrame
						&& before.spotsAt( t ) >= limits.statedCellsPerFrame )
				{
					collapsedTps++;
					if ( after.spotsAt( t ) < worstCount )
					{
						worstCount = after.spotsAt( t );
						worstTp = t;
					}
				}
			}
			if ( collapsedTps > 0 )
				out.add( new Violation( Severity.ERROR, String.format(
						"%d timepoint(s) taken below %d cell(s)/frame — the fewest your stated estimate "
								+ "still allows, after the margin it is given for being an estimate. "
								+ "Worst t=%d with %d. This phase is deleting real cells, not false "
								+ "positives.",
						collapsedTps, limits.statedCellsPerFrame, worstTp, worstCount ) ) );
		}

		// --- 4./5. Per-timepoint churn inside the window. ---
		for ( int t = Math.max( 0, wStart ); t <= Math.min( n - 1, wEnd ); t++ )
		{
			final int b = before.spotsAt( t );
			final int a = after.spotsAt( t );
			if ( b == 0 )
				continue; // nothing to measure a fraction against; the ceiling check above covers growth
			if ( a == 0 )
			{
				out.add( new Violation( Severity.ERROR, String.format(
						"t=%d: every one of its %d spot(s) was removed, emptying the timepoint.", t, b ) ) );
				continue;
			}
			final double added = ( double ) Math.max( 0, a - b ) / b;
			if ( added > limits.maxAddedFraction )
				out.add( new Violation( Severity.WARN, String.format(
						"t=%d: phase added %d spot(s) on top of %d (%.0f%%, over the %.0f%% limit). "
								+ "For a correction phase this means the detection it is patching failed.",
						t, a - b, b, added * 100, limits.maxAddedFraction * 100 ) ) );
			final double deleted = ( double ) Math.max( 0, b - a ) / b;
			if ( deleted > limits.maxDeletedFraction )
				out.add( new Violation( Severity.WARN, String.format(
						"t=%d: phase deleted %d of %d spot(s) (%.0f%%, over the %.0f%% limit).",
						t, b - a, b, deleted * 100, limits.maxDeletedFraction * 100 ) ) );
		}

		// --- 6. Continuity must not regress. ---
		if ( limits.continuityDropTolerance < Double.POSITIVE_INFINITY )
		{
			final double cBefore = before.continuity( wStart, wEnd );
			final double cAfter = after.continuity( wStart, wEnd );
			// NaN means the window has no interior timepoints, so continuity is undefined, not zero —
			// comparing against it would report a phantom regression on every window shorter than 3 tp.
			if ( !Double.isNaN( cBefore ) && !Double.isNaN( cAfter )
					&& cBefore - cAfter > limits.continuityDropTolerance )
				out.add( new Violation( Severity.WARN, String.format(
						"continuity over t=%d..%d fell %.0f%% -> %.0f%% (drop of %.0f pp, over the "
								+ "%.0f pp limit). This phase is fragmenting tracks, not repairing them.",
						wStart, wEnd, cBefore * 100, cAfter * 100,
						( cBefore - cAfter ) * 100, limits.continuityDropTolerance * 100 ) ) );
		}

		return out;
	}

	/**
	 * Takes an "after" snapshot, compares it against {@code before}, reports any violations through
	 * {@code ctx}'s log (and SLF4J), and returns the new snapshot so it can be chained straight into
	 * the next phase's check.
	 *
	 * @return the "after" snapshot, for use as the next phase's {@code before}
	 * @return the snapshot for the next phase, and the first ERROR found — see {@link Outcome}
	 * @throws InvariantViolationException if an {@code ERROR} violation occurred AND
	 *         {@link #STRICT_MODE_KEY} is set in the context
	 */
	public static Outcome checkAndLog( final ToolContext ctx, final String phase, final Snapshot before,
			final int wStart, final int wEnd, final Limits limits )
	{
		// Every step of every window passes here, so this is where a spot's first appearance can be
		// pinned to the step that made it. Off unless the origin dump is configured.
		// First, whether the graph is still sound at all: a damaged link list hangs the next edit that
		// walks it, so this stops the run at the step that did it. See GraphIntegrity.
		if ( ctx != null && ctx.getGraph() != null )
			GraphIntegrity.require( ctx, ctx.getGraph(), phase + " t=" + wStart + ".." + wEnd );
		// A step boundary: no ref or id a pass collected outlives its step, so what the viewer changed
		// during it stops mattering here. See GraphWatch.
		org.mastodon.mamut.automahout.tool.GraphWatch.mark( ctx, phase + " t=" + wStart + ".." + wEnd );
		SpotOriginDump.snapshot( ctx, phase + " t=" + wStart + ".." + wEnd, wStart, wEnd );
		final Snapshot after = snapshot( ctx );
		if ( before == null )
			return new Outcome( after, null, false );

		final List< Violation > violations = check( before, after, wStart, wEnd, limits );
		if ( violations.isEmpty() )
			return new Outcome( after, null, false );

		String firstError = null;
		boolean ceilingOnly = true;
		for ( final Violation v : violations )
		{
			final String line = "  !! INVARIANT " + v.severity() + " after " + phase + " — " + v.message();
			ctx.log( line );
			if ( v.severity() == Severity.ERROR )
			{
				if ( firstError == null )
					firstError = v.message();
				ceilingOnly &= v.isCeiling();
				log.error( line );
			}
			else
				log.warn( line );
		}

		if ( firstError != null && Boolean.TRUE.equals( ctx.getSharedData( STRICT_MODE_KEY ) ) )
			throw new InvariantViolationException( "Invariant violated after " + phase + ": " + firstError );

		return new Outcome( after, firstError, firstError != null && ceilingOnly );
	}

	/**
	 * What a phase's check produced: the snapshot to compare the NEXT phase against, and whether an
	 * ERROR was found.
	 *
	 * <p>The error is returned rather than only logged because for most of this class's life it was
	 * only logged, and every caller therefore ignored it: a run could print "Detection has exploded
	 * into background" and carry that window into the next nine. Returning it does not force a caller
	 * to act, but it does make not acting a visible choice at the call site instead of an absence.</p>
	 */
	public static final class Outcome
	{
		/** Baseline for the next phase's comparison. Always present. */
		public final Snapshot snapshot;

		/** The first ERROR-severity violation's message, or {@code null} when the phase was clean. */
		public final String firstError;

		/**
		 * {@code true} when every ERROR was the explosion ceiling. Only then can the answer to "is the
		 * ceiling right?" clear the phase.
		 */
		public final boolean onlyCeilingErrors;

		Outcome( final Snapshot snapshot, final String firstError )
		{
			this( snapshot, firstError, false );
		}

		Outcome( final Snapshot snapshot, final String firstError, final boolean onlyCeilingErrors )
		{
			this.snapshot = snapshot;
			this.firstError = firstError;
			this.onlyCeilingErrors = onlyCeilingErrors;
		}

		public boolean hasError()
		{
			return firstError != null;
		}
	}
}
