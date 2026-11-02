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
package org.mastodon.mamut.automahout.tool;

import java.util.EnumMap;
import java.util.Map;

import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.automahout.elephant.DetectionTagLabels;
import org.mastodon.mamut.model.Model;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * The single place where this pipeline's automated passes mutate the {@link ModelGraph}, and the
 * single place where the rules about such mutations live.
 *
 * <p>This exists because of a measured, repeating failure mode. A safety rule — never delete a
 * protected spot, never take a timepoint below the stated cell count, stay inside the current
 * window — used to be hand-applied at each of the ~40 mutation sites spread across
 * {@code AutoCorrectOrchestrator} and {@code ElephantTool}, and the sites it was not applied to kept
 * violating it silently. Before this class, protection reached 35 of 41 sites and the cell-count
 * floor reached 3; nothing in the code said which, and finding out took a grep sweep each time. A
 * rule added here reaches every caller at once, and a site added tomorrow is covered by
 * construction.</p>
 *
 * <h2>Why a reason is required</h2>
 * <p>A bare {@code graph.remove(spot)} cannot be judged, because the correct rule depends on why the
 * spot is going. Deleting a detection that is <em>not a cell</em> must respect the cell-count floor;
 * deleting one of two detections on a cell that <em>is</em> there must not, because the cell remains
 * either way. The two calls are identical at the call site and opposite in policy, which is how the
 * floor came to be applied to one pass and not its neighbour. {@link Reason} makes the caller state
 * which it means, so the policy can be decided here rather than remembered there.</p>
 *
 * <p>The reason is also what makes a run legible afterwards: {@link #tally(ToolContext)} reports how
 * many spots and links each reason accounted for, which is the breakdown the logs previously could
 * not produce.</p>
 *
 * <h2>What is enforced today</h2>
 * <ul>
 *   <li><b>Protection</b> — uniformly, for every reason but {@link Reason#USER}. A protected spot is
 *       never deleted, and a new spot is never left sitting on top of one.</li>
 *   <li><b>A frame is never emptied one judgement at a time</b> — a deletion that would take a
 *       timepoint to zero spots is refused for every reason that judges an individual detection. No
 *       measurement of one spot can conclude that a frame holds no cells; a pass that reaches that
 *       answer is reporting its own broken threshold. {@link Reason#BULK}, {@link Reason#TRAINING}
 *       and {@link Reason#USER} may still empty a frame, because those callers mean to.</li>
 *   <li><b>The cell-count floor</b> — for {@link Reason#PROBABLY_NOT_A_CELL}, which is now every
 *       statistical "this is probably background" deletion in the pipeline rather than the three that
 *       remembered to ask. Turning it on required splitting the reason first: the floor bounds weak
 *       judgements, and applying it to a direct measurement was already known to be wrong, which is
 *       why {@link Reason#NOT_A_CELL} exists beside it.</li>
 * </ul>
 *
 * <p>Callers get a {@code boolean} or {@code null} back rather than an exception: a refused edit is a
 * normal outcome here, and a multi-hour tracking run should not die because one pass reached for a
 * spot it was not allowed to touch. {@link org.mastodon.mamut.automahout.eval.PipelineInvariants}
 * remains the backstop that notices when something slipped through anyway.</p>
 */
public final class GraphEdits
{
	/**
	 * Why a mutation is being made. The rules applied depend on this, so it is a required argument
	 * rather than an optional annotation.
	 */
	public enum Reason
	{
		/**
		 * A statistical judgement that this is probably not a cell: a contrast test against a sampled
		 * background baseline, a size or shape threshold, a track too short to be stable. **Bounded by
		 * the stated cell-count floor.**
		 *
		 * <p>This is the case the floor was built for. These tests rest on estimates weaker than the
		 * count the user gave — a baseline from a few dozen samples deciding a detection is "probably"
		 * background — so where they disagree with the user's own number, the number wins.</p>
		 */
		PROBABLY_NOT_A_CELL,

		/**
		 * A direct measurement that this is not a cell: its centre lies outside the imaged volume, its
		 * interior carries no more signal than the space around it. **Not bounded by the floor**, and
		 * the exemption is the point.
		 *
		 * <p>{@code deleteSpotsWithNoSignal} argues this case in full: a detection averaging no more
		 * signal than its surroundings is not a marginal call, and the floor was observed protecting
		 * detections whose measured intensity was literally zero while refusing 189 deletions in a
		 * single window. Bounding a certainty by a stated approximation gets the worse answer.</p>
		 */
		NOT_A_CELL,

		/**
		 * A second detection on a cell that is still there. Never floor-bounded: resolving a duplicate
		 * lowers the spot count without lowering the cell count, so bounding it would leave
		 * over-segmentation in place and teach it back to the model on the next round.
		 */
		DUPLICATE,

		/**
		 * One detection covering more than one cell, replaced by one detection per cell. The mirror
		 * image of {@link #DUPLICATE}, and it needs its own reason because it is the one case that both
		 * of that reason's guarantees get wrong.
		 *
		 * <p>A duplicate must be MERGED and never resolved by deleting a member, because its members
		 * are two detections of one cell; here the single detection is of two cells, so the members are
		 * what is being created and the original is what goes. And {@link #DETECTION} may only ADD, so
		 * the removal cannot be filed there either. Both chokepoint tests refuse those, correctly.</p>
		 *
		 * <p>Never floor-bounded: a split raises the cell count by construction -- the replacement
		 * spots are added before the original is removed, so the frame never dips.</p>
		 */
		SPLIT,

		/**
		 * Track surgery — pruning a link, dissolving a false division, removing a fragment, filling a
		 * gap. Acts on the lineage's shape rather than on whether an object is a cell.
		 */
		TOPOLOGY,

		/**
		 * A spot the DETECTOR produced. Not a judgement and not surgery — just the model's output
		 * arriving in the graph.
		 *
		 * <p>Exists because every one of these was filed under {@link #TOPOLOGY}, which the edit tally
		 * at the end of a window reports as one number. A window that committed ~78 spots/tp therefore
		 * printed {@code TOPOLOGY spots -68/+6095}: five predicts' worth of detections summed with the
		 * handful of spots that track surgery actually added, in the one line whose whole purpose is to
		 * make a window's spot accounting attributable. It cannot answer "how much did correction
		 * change" while it also counts detection.</p>
		 */
		DETECTION,

		/**
		 * Wholesale reset of a timepoint or a window: discarding an untrained model's output before
		 * re-seeding, restoring a saved state. Not a judgement about any individual spot.
		 */
		BULK,

		/**
		 * A training annotation that does NOT assert a cell — a synthesized {@code fp} negative, or an
		 * annotation being cleared. It is a statement to the model about what to learn, not a claim
		 * that a cell is or is not there, so the cell-count floor has nothing to say about it.
		 */
		TRAINING,

		/**
		 * A training annotation that DOES assert a cell: a cold-start intensity seed, or a position
		 * restored from a trusted GT store. Taught as a plain positive.
		 *
		 * <p>Separate from {@link #TRAINING} because the two make opposite claims about the detector
		 * and {@link #claimForDetector} has nothing but the reason to tell them apart. While they
		 * shared one constant, both went to the server carrying the graph listener's {@code fn} — a
		 * correction at ten times the weight of a positive. A GT-trained window's payload read
		 * {@code fp=45, fn=440, tp=0}, and ELEPHANT's own per-frame tally agreed: {@code ('tp', 0)}.
		 * The model was taught that every cell a person had confirmed was a cell it had MISSED.</p>
		 */
		TRAINING_POSITIVE,

		/**
		 * An explicit human action through one of the manual tools. No automatic guard applies — the
		 * person is the authority, including over protected spots, which they are the ones who marked.
		 */
		USER
	}

	/** {@link ToolContext#getSharedData} key holding the per-run {@link Tally}. */
	private static final String TALLY_KEY = "graph.edits.tally";

	/** {@link ToolContext#getSharedData} key holding the memoised per-timepoint spot count. */
	private static final String COUNT_KEY = "graph.edits.timepoint.count";

	private GraphEdits()
	{}

	// ------------------------------------------------------------------ removal

	/**
	 * Deletes {@code spot} unless it is protected.
	 *
	 * @return {@code true} if the spot was removed, {@code false} if protection refused it
	 */
	public static boolean removeSpot( final ToolContext ctx, final ModelGraph graph, final Spot spot,
			final Reason why )
	{
		return underWriteLock( graph, () -> removeSpotLocked( ctx, graph, spot, why ) );
	}

	private static boolean removeSpotLocked( final ToolContext ctx, final ModelGraph graph, final Spot spot,
			final Reason why )
	{
		if ( GraphWatch.dirty( graph, spot ) )
			return refusedAsChanged( ctx, graph );
		if ( why != Reason.USER && ctx != null && isProtected( ctx, spot ) )
		{
			tallyOf( ctx ).refused( why );
			return false;
		}
		final int t = spot.getTimepoint();
		if ( floorBounded( why ) && ctx != null && atTheFloor( ctx, t ) )
		{
			tallyOf( ctx ).refusedByFloor( why );
			return false;
		}
		if ( emptiesTheTimepoint( ctx, t, why ) )
		{
			tallyOf( ctx ).refusedAsLastSpot( why );
			return false;
		}
		if ( saysThereIsNoCellHere( why ) && ctx != null )
		{
			// A pass concluding that a detection should not be there is exactly the finding a person
			// turns into an fp annotation. Recorded here, at the one place every removal passes through,
			// so it covers the passes that exist and the ones added later — and recorded BEFORE the spot
			// is gone, because afterwards there is nothing left to read a position off.
			// See CorrectionEvidence.
			final double[] pos = new double[ 3 ];
			spot.localize( pos );
			CorrectionEvidence.spurious( ctx, t, pos,
					Math.sqrt( spot.getBoundingSphereRadiusSquared() ) );
		}
		if ( SpotOriginDump.enabled() )
			SpotOriginDump.recordRemovedSpot( graph, spot, why, SpotOrigin.callerOutside( new Throwable().getStackTrace() ) );
		graph.remove( spot );
		if ( ctx != null )
		{
			tallyOf( ctx ).spotRemoved( why );
			counts( ctx ).changed( t, -1 );
		}
		return true;
	}

	/**
	 * Whether this removal is a statement about DETECTION -- that there is no cell in this place.
	 *
	 * <p>Only such a removal is evidence for a training negative. It was {@link Reason#TOPOLOGY}
	 * alone, which is track surgery in [5c/6] and a small fraction of what the pipeline refuses; the
	 * cleanup chain in [3/6] makes the same judgement dozens of times per frame and every one of them
	 * was discarded. Measured against CTC truth, the detector produced 5.4x more detections than there
	 * are cells in its first window while the only negatives it ever trained on were SYNTHESIZED at
	 * offsets from real cells -- in places it had never actually fired.</p>
	 *
	 * <p>{@link Reason#DUPLICATE} is deliberately NOT here. A duplicate sits ON a real cell, so
	 * teaching that position as background would teach the detector that a cell is not one, which is
	 * the most expensive thing this could get wrong. {@link Reason#SPLIT} replaces a detection with
	 * pieces of itself, and the rest are bookkeeping.</p>
	 *
	 * <p>A record is a candidate, not a verdict. Be aware, though, that for THIS pool the confirmation
	 * step is not independent evidence: {@code confirmSyntheticFpCandidatesByMeasurement} asks
	 * {@code isDefinitelyBackground}, which is close to the test that deleted the spot in the first
	 * place. What actually bounds the damage is the precision of the filters themselves -- the
	 * no-signal pass at 2.5 sigma costs 0.14% of real cells (benchmark/detection-gating) -- and the
	 * per-window negative budget.</p>
	 */
	static boolean saysThereIsNoCellHere( final Reason why )
	{
		return why == Reason.TOPOLOGY || why == Reason.NOT_A_CELL
				|| why == Reason.PROBABLY_NOT_A_CELL;
	}

	/**
	 * Whether {@code timepoint} is already down to the count the user stated, grown at the measured
	 * rate.
	 *
	 * <p>Counting the spots at a timepoint is a pass over every vertex in the graph, and a deleting
	 * pass calls this once per candidate, so the count is memoised for the timepoint currently being
	 * worked on and maintained by this class as spots come and go. A pass works one timepoint at a
	 * time, so the real count is taken once per pass per timepoint rather than once per spot — the
	 * difference on a movie-length graph is minutes.</p>
	 *
	 * <p>The memo is safe because every mutation in this project now goes through this class. It is
	 * re-seeded whenever the timepoint changes, which bounds any drift from an edit made elsewhere —
	 * a Mastodon undo, a user moving a spot in the UI — to the pass currently running.</p>
	 */
	private static boolean atTheFloor( final ToolContext ctx, final int timepoint )
	{
		final int floor = ExpectedCellCount.floorAt( ctx, timepoint );
		return floor > 0 && counts( ctx ).at( ctx, timepoint ) <= floor;
	}

	/**
	 * Whether this would take {@code timepoint} to zero spots on a judgement about one detection.
	 *
	 * <p>No measurement of an individual spot can conclude that a frame contains no cells. A pass that
	 * reaches that answer has stopped measuring the specimen and started measuring its own broken
	 * threshold, so the last spot is kept and the pass is reported rather than believed. Observed on a
	 * phase-contrast movie: a single-anchor intensity ceiling landed above every detection in the
	 * window and removed all 931 of them, then 1559 in another, each one found by the predictor moments
	 * earlier. The window validated at zero continuity and the run carried the hole forward.</p>
	 *
	 * <p>Emptying a timepoint deliberately is still allowed, because some callers mean it:
	 * {@link Reason#BULK} discards an untrained model's output before re-seeding,
	 * {@link Reason#TRAINING} clears annotations, and {@link Reason#USER} is a person. What is refused
	 * is arriving at an empty frame one judgement at a time without ever deciding to.</p>
	 */
	private static boolean emptiesTheTimepoint( final ToolContext ctx, final int timepoint,
			final Reason why )
	{
		if ( ctx == null || why == Reason.BULK || why == Reason.TRAINING
				|| why == Reason.TRAINING_POSITIVE || why == Reason.USER )
			return false;
		return counts( ctx ).at( ctx, timepoint ) <= 1;
	}

	/** Memoised spot count for the timepoint a deleting pass is currently working on. */
	private static final class TimepointCount
	{
		private int timepoint = Integer.MIN_VALUE;

		private int count;

		int at( final ToolContext ctx, final int t )
		{
			if ( t != timepoint )
			{
				timepoint = t;
				count = ExpectedCellCount.countCellsAt( ctx, t );
			}
			return count;
		}

		void changed( final int t, final int delta )
		{
			if ( t == timepoint )
				count += delta;
		}
	}

	private static TimepointCount counts( final ToolContext ctx )
	{
		final Object o = ctx.getSharedData( COUNT_KEY );
		if ( o instanceof TimepointCount )
			return ( TimepointCount ) o;
		final TimepointCount c = new TimepointCount();
		ctx.setSharedData( COUNT_KEY, c );
		return c;
	}

	/**
	 * Deletes {@code link}. Links carry no protection of their own — a protected spot keeps its
	 * identity, not its particular edges, and correcting a wrong edge on a confirmed cell is exactly
	 * what the correction passes are for.
	 */
	public static boolean removeLink( final ToolContext ctx, final ModelGraph graph, final Link link,
			final Reason why )
	{
		final StackTraceElement[] stack = SpotOriginDump.enabled() ? new Throwable().getStackTrace() : null;
		return underWriteLock( graph, () -> {
			if ( GraphWatch.dirty( graph, link ) )
				return refusedAsChanged( ctx, graph );
			if ( stack != null )
				SpotOriginDump.recordRemovedLink( graph, link, why, SpotOrigin.callerOutside( stack ) );
			graph.remove( link );
			if ( ctx != null )
				tallyOf( ctx ).linkRemoved( why );
			return true;
		} );
	}

	// ------------------------------------------------------------------ creation

	/**
	 * Creates a spot at {@code pos} with covariance {@code cov}, then withdraws it if it landed on a
	 * protected spot.
	 *
	 * <p>Checking after creation rather than before is deliberate and matches the overlap test's own
	 * shape: {@link ProtectedSpots#overlapsProtected} compares two spots in the graph, so the
	 * candidate has to exist to be compared. The window between the two is inside a single write lock
	 * held by the caller.</p>
	 *
	 * @return {@code ref}, initialised, or {@code null} if the spot was withdrawn
	 */
	public static Spot addSpot( final ToolContext ctx, final ModelGraph graph, final Spot ref,
			final int timepoint, final double[] pos, final double[][] cov, final Reason why )
	{
		return underWriteLock( graph, () -> {
			GraphWatch.asRunsOwn( () -> graph.addVertex( ref ).init( timepoint, pos, cov ) );
			return acceptOrWithdraw( ctx, graph, ref, timepoint, why );
		} );
	}

	/** Spherical variant of {@link #addSpot(ToolContext, ModelGraph, Spot, int, double[], double[][], Reason)}. */
	public static Spot addSpot( final ToolContext ctx, final ModelGraph graph, final Spot ref,
			final int timepoint, final double[] pos, final double radius, final Reason why )
	{
		return underWriteLock( graph, () -> {
			GraphWatch.asRunsOwn( () -> graph.addVertex( ref ).init( timepoint, pos, radius ) );
			return acceptOrWithdraw( ctx, graph, ref, timepoint, why );
		} );
	}

	private static Spot acceptOrWithdraw( final ToolContext ctx, final ModelGraph graph, final Spot ref,
			final int timepoint, final Reason why )
	{
		if ( why != Reason.USER && ctx != null && overlapsProtected( ctx, graph, ref, timepoint ) )
		{
			graph.remove( ref );
			tallyOf( ctx ).refused( why );
			return null;
		}
		SpotOrigin.record( graph, ref, why );
		if ( ctx != null )
		{
			claimForDetector( ctx, ref, why );
			tallyOf( ctx ).spotAdded( why );
			counts( ctx ).changed( timepoint, 1 );
		}
		return ref;
	}

	/**
	 * Says what a spot created for this reason claims about the DETECTOR, correcting the graph
	 * listener where it cannot know.
	 *
	 * <p>ELEPHANT's {@code GraphListenerService} tags EVERY vertex added to the graph as {@code fn}.
	 * That is right for the case it was written for — a PERSON adding a spot is saying the detector
	 * missed a cell, and {@code fn} is a correction, weighted ten times a plain positive by the
	 * server's {@code false_weight}. A listener cannot tell that apart from this project adding spots
	 * through the same graph API, and this project adds them for six different reasons.</p>
	 *
	 * <p>Set here rather than at the fourteen call sites, for the reason every other rule in this
	 * class is here: {@link Reason} is exactly the information the decision needs, and a site added
	 * later gets the right answer without anyone remembering. Found by reading a run where the
	 * per-predict tallies all said {@code fn=0} and the window's said {@code fn=31} — the split pass
	 * had replaced 15 detections with about 30, and every one of them went to the model as an error it
	 * had made, at ten times the weight.</p>
	 *
	 * <p>Only {@code tp} is ever written. The reasons that really are corrections say so themselves
	 * and are left alone: {@link Reason#TOPOLOGY} through {@code tagAsMissedCell}, whose claim is
	 * corroborated by TIME — a cell exists before and after the gap — and {@link Reason#TRAINING},
	 * whose synthesis tags {@code fp}. {@link Reason#TRAINING_POSITIVE} exists because that sentence
	 * was once true of every training annotation and is not: a seed and a restored GT position assert
	 * a cell. {@link Reason#USER} is the listener's own case and is exactly right.</p>
	 */
	private static void claimForDetector( final ToolContext ctx, final Spot ref, final Reason why )
	{
		if ( !claimsTheDetectorWasRight( why ) )
			return;
		final Model model = ctx.getModel();
		// The EXISTING tag: creating one modifies the tag-set structure, which must not happen while
		// the vertex write lock this runs under is held. No set means the listener tagged nothing.
		final Tag tp = DetectionTagLabels.existingTag( model, DetectionTagLabels.TP );
		if ( tp != null )
			model.getTagSetModel().getVertexTags().set( ref, tp );
	}

	/**
	 * Whether a spot added for this reason is the detector being RIGHT rather than corrected.
	 *
	 * <p>{@link Reason#SPLIT} and {@link Reason#DUPLICATE} are this project's best reading of what the
	 * model should have produced, not a claim it missed a cell. The split pass separates its two
	 * classes at an AUC of 0.663; that is not confidence worth ten times a plain positive, and
	 * teaching its mistakes at that weight amplifies exactly the pass with the least evidence behind
	 * it.</p>
	 */
	static boolean claimsTheDetectorWasRight( final Reason why )
	{
		switch ( why )
		{
		case DETECTION:
		case SPLIT:
		case DUPLICATE:
		case BULK:
		case TRAINING_POSITIVE:
			return true;
		default:
			return false;
		}
	}

	/**
	 * Creates an edge from {@code from} to {@code to}; refuses (null, logged) one that does not go
	 * forward in time. A link from a spot to itself, or back in time, is never a tracking decision: it is
	 * a spot ref that went stale -- the spot was deleted, by a hand edit in the viewer, and its slot taken
	 * by the next spot added -- and once made it sits in both of a spot's lists, so a walk along the
	 * track never ends and the whole application freezes.
	 */
	public static Link addLink( final ToolContext ctx, final ModelGraph graph, final Spot from,
			final Spot to, final Link edgeRef, final Reason why )
	{
		return underWriteLock( graph, () -> {
			if ( to.getTimepoint() <= from.getTimepoint() )
			{
				if ( ctx != null )
					ctx.log( String.format( "  !! Refused a link from t=%d (%.1f, %.1f) to t=%d (%.1f, %.1f): it does not "
							+ "go forward in time, so a spot it was meant for is gone (most likely deleted in the viewer "
							+ "meanwhile). Nothing was linked; please report this line.", from.getTimepoint(),
							from.getDoublePosition( 0 ), from.getDoublePosition( 1 ), to.getTimepoint(),
							to.getDoublePosition( 0 ), to.getDoublePosition( 1 ) ) );
				return null;
			}
			if ( GraphWatch.dirty( graph, from ) || GraphWatch.dirty( graph, to ) )
			{
				refusedAsChanged( ctx, graph );
				return null;
			}
			final Link l = GraphWatch.asRunsOwn( () -> graph.addEdge( from, to, edgeRef ).init() );
			LinkOrigin.record( graph, l, why );
			if ( ctx != null )
				tallyOf( ctx ).linkAdded( why );
			return l;
		} );
	}

	/**
	 * Runs {@code edit} under the graph's write lock: the caller's when it holds it, else taken here and
	 * released after. The user edits the graph in the viewer while a run goes on, so an edit without the
	 * write lock races theirs. Holding only the READ lock is a programming error: the write lock cannot
	 * be taken then (a read lock is never upgraded, the thread would wait for itself forever), so that
	 * throws rather than freezes.
	 */
	/**
	 * Counts and refuses an edit through a spot or link that changed hands in the viewer this step (see
	 * {@link GraphWatch}); the count is reported at the step boundary.
	 */
	private static boolean refusedAsChanged( final ToolContext ctx, final ModelGraph graph )
	{
		GraphWatch.refused( graph );
		return false;
	}

	public static < T > T underWriteLock( final ModelGraph graph, final java.util.function.Supplier< T > edit )
	{
		final java.util.concurrent.locks.ReentrantReadWriteLock lock = graph.getLock();
		if ( lock.isWriteLockedByCurrentThread() )
			return edit.get();
		if ( lock.getReadHoldCount() > 0 )
			throw new IllegalStateException( "A graph edit was attempted while holding only the read lock; taking "
					+ "the write lock here would wait forever. This is a bug in the calling pass." );
		lock.writeLock().lock();
		try
		{
			return edit.get();
		}
		finally
		{
			lock.writeLock().unlock();
		}
	}

	// ------------------------------------------------------------------ policy

	/**
	 * Whether deletions made for {@code why} are bounded below by the stated cell-count floor.
	 *
	 * <p>Only {@link Reason#PROBABLY_NOT_A_CELL}. The floor bounds weak judgements, not certain ones:
	 * see the two reasons' own documentation for why splitting them was necessary before this could be
	 * switched on at all. {@link Reason#DUPLICATE} is exempt because a duplicate's cell is still
	 * there, {@link Reason#TOPOLOGY} because track surgery is not a claim about whether an object is a
	 * cell, {@link Reason#TRAINING} and {@link Reason#BULK} because neither is a judgement about one,
	 * and {@link Reason#USER} because the person outranks their own estimate.</p>
	 */
	static boolean floorBounded( final Reason why )
	{
		return why == Reason.PROBABLY_NOT_A_CELL;
	}

	// ------------------------------------------------------------------ tally

	/** Per-run counts of what each reason accounted for. */
	public static final class Tally
	{
		private final Map< Reason, int[] > counts = new EnumMap<>( Reason.class );

		private int[] row( final Reason r )
		{
			return counts.computeIfAbsent( r, k -> new int[ 7 ] );
		}

		void spotRemoved( final Reason r )
		{
			row( r )[ 0 ]++;
		}

		void linkRemoved( final Reason r )
		{
			row( r )[ 1 ]++;
		}

		void spotAdded( final Reason r )
		{
			row( r )[ 2 ]++;
		}

		void linkAdded( final Reason r )
		{
			row( r )[ 3 ]++;
		}

		void refused( final Reason r )
		{
			row( r )[ 4 ]++;
		}

		void refusedByFloor( final Reason r )
		{
			row( r )[ 5 ]++;
		}

		void refusedAsLastSpot( final Reason r )
		{
			row( r )[ 6 ]++;
		}

		/**
		 * {@code {spots removed, links removed, spots added, links added, refused as protected,
		 * refused by the floor, refused as a timepoint's last spot}} for {@code r}.
		 */
		public int[] get( final Reason r )
		{
			return row( r ).clone();
		}

		/** One line per reason that saw any activity; empty string when nothing happened. */
		public String summary()
		{
			return since( null );
		}

		/**
		 * A copy of the current counts, to be handed back to {@link #since} later.
		 *
		 * <p>A run's totals answer "what did this pipeline do"; a window's answer "what did THIS window
		 * do", which is the question a log is read to settle. Taking a mark at the start of a window and
		 * differencing at the end costs one small array.</p>
		 */
		public int[][] mark()
		{
			final int[][] m = new int[ Reason.values().length ][];
			for ( final Reason r : Reason.values() )
				m[ r.ordinal() ] = row( r ).clone();
			return m;
		}

		/**
		 * What changed since {@code mark}, one line per reason that did anything. Pass {@code null} for
		 * the totals since the run began. Empty when nothing happened, so a caller can skip the log line
		 * entirely rather than printing a header over nothing.
		 */
		public String since( final int[][] mark )
		{
			final StringBuilder sb = new StringBuilder();
			for ( final Reason r : Reason.values() )
			{
				final int[] now = counts.get( r );
				if ( now == null )
					continue;
				final int[] was = mark == null ? new int[ 7 ] : mark[ r.ordinal() ];
				final int sr = now[ 0 ] - was[ 0 ], lr = now[ 1 ] - was[ 1 ];
				final int sa = now[ 2 ] - was[ 2 ], la = now[ 3 ] - was[ 3 ];
				final int rp = now[ 4 ] - was[ 4 ], rf = now[ 5 ] - was[ 5 ], rl = now[ 6 ] - was[ 6 ];
				if ( ( sr | lr | sa | la | rp | rf | rl ) == 0 )
					continue;
				sb.append( sb.length() == 0 ? "" : "; " );
				sb.append( String.format( "%s spots -%d/+%d links -%d/+%d", r, sr, sa, lr, la ) );
				if ( rp > 0 )
					sb.append( String.format( " kept %d protected", rp ) );
				if ( rf > 0 )
					sb.append( String.format( " kept %d at floor", rf ) );
				if ( rl > 0 )
					sb.append( String.format( " kept %d as a frame's LAST spot", rl ) );
			}
			return sb.toString();
		}
	}

	/** The run's tally, created on first use. */
	public static Tally tally( final ToolContext ctx )
	{
		return tallyOf( ctx );
	}

	private static Tally tallyOf( final ToolContext ctx )
	{
		final Object o = ctx.getSharedData( TALLY_KEY );
		if ( o instanceof Tally )
			return ( Tally ) o;
		final Tally t = new Tally();
		ctx.setSharedData( TALLY_KEY, t );
		return t;
	}

	// ------------------------------------------------------------------ guards

	private static boolean isProtected( final ToolContext ctx, final Spot spot )
	{
		final Model model = ctx.getModel();
		return model != null && ProtectedSpots.isProtected( ctx, model, spot );
	}

	private static boolean overlapsProtected( final ToolContext ctx, final ModelGraph graph, final Spot ref,
			final int timepoint )
	{
		final Model model = ctx.getModel();
		return model != null && ProtectedSpots.overlapsProtected( ctx, model, graph, ref, timepoint );
	}
}
