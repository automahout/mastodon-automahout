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
package org.mastodon.mamut.automahout.autocorrect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

/**
 * A cell the detector misses for two frames must not cost its lineage.
 *
 * <p>Nothing in this pipeline could join a track across a gap. Every linking path works between
 * adjacent timepoints — ELEPHANT's own linker goes {@code t -> t-1}, so do {@code linkOrphanStarts}
 * and {@code reconnectOrphanTracklets}, and {@code fillGaps} bridges exactly one frame by asking the
 * detector to look again — and {@code pruneLongLinks} then deleted any edge spanning more than one
 * timepoint outright, whatever had created it. So a two-frame miss split one cell's lineage in two
 * permanently. That is what the user reported from the viewer: the same nucleus ending a track at
 * t=44 and starting a new one at t=47.</p>
 *
 * <p>These run on a real {@link ReplayGraph}, not on the source text, because the gap rule is one
 * line and this project has twice shipped a ratchet that read that line and proved nothing.</p>
 */
public class GapClosingTest
{
	/** A per-frame displacement cap in the fixture's units; spots here have radius 5. */
	private static final double MAX_DISP = 7.0;

	private static final double SEARCH_FACTOR = 3.0;

	/** Whether {@code to} is reachable from {@code from} by following links forward. */
	private static boolean reaches( final ReplayGraph g, final Spot from, final Spot to )
	{
		final java.util.Deque< Integer > todo = new java.util.ArrayDeque<>();
		final java.util.Set< Integer > seen = new java.util.HashSet<>();
		todo.add( from.getInternalPoolIndex() );
		final Spot cur = g.graph().vertexRef();
		final Spot tgt = g.graph().vertexRef();
		try
		{
			while ( !todo.isEmpty() )
			{
				final int id = todo.poll();
				if ( id == to.getInternalPoolIndex() )
					return true;
				if ( !seen.add( id ) )
					continue;
				for ( final Spot v : g.graph().vertices() )
					if ( v.getInternalPoolIndex() == id )
					{
						cur.refTo( v );
						for ( final org.mastodon.mamut.model.Link e : cur.outgoingEdges() )
							todo.add( e.getTarget( tgt ).getInternalPoolIndex() );
						break;
					}
			}
			return false;
		}
		finally
		{
			g.graph().releaseRef( cur );
			g.graph().releaseRef( tgt );
		}
	}

	/** Whether {@code from} has an edge to {@code to}, checked on the graph rather than by counting. */
	private static boolean linked( final ReplayGraph g, final Spot from, final Spot to )
	{
		final Spot tgt = g.graph().vertexRef();
		try
		{
			for ( final org.mastodon.mamut.model.Link e : from.outgoingEdges() )
				if ( e.getTarget( tgt ).getInternalPoolIndex() == to.getInternalPoolIndex() )
					return true;
			return false;
		}
		finally
		{
			g.graph().releaseRef( tgt );
		}
	}

	@Test
	public void aCellMissedForTwoFramesIsRejoined()
	{
		// The reported case: a track ends, the cell is undetected for two frames, and the same cell
		// starts a fresh track. 3 timepoints apart, 2 units of movement.
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > before = g.track( 40, 5, 0, 1 );   // t=40..44, ends at x=4
		final List< Spot > after = g.track( 47, 5, 6, 1 );    // t=47..51, starts at x=6
		final ToolContext ctx = g.context();

		final int linked = AutoCorrectOrchestrator.closeTrackGaps( ctx, 40, 51, SEARCH_FACTOR, MAX_DISP,
				AutoCorrectOrchestrator.MAX_LINK_GAP );

		assertEquals( 1, linked );
		// Filled, not spanned: the gap now holds a spot at every timepoint, and the join is a chain of
		// one-frame links. A link that skipped a timepoint would draw across the viewer with no cell
		// under it and could not be judged by eye.
		assertEquals( "the two missing timepoints must now hold a cell each", 12, g.spotCount() );
		assertFalse( "no edge may span the gap", linked( g, before.get( before.size() - 1 ),
				after.get( 0 ) ) );
		assertEquals( "every timepoint from 40 to 51 is occupied", 12, occupiedTimepoints( g, 40, 51 ) );
		assertTrue( "and the whole thing is one lineage", oneComponent( g ) );
	}

	/** How many of {@code [t0,t1]} hold at least one spot. */
	private static int occupiedTimepoints( final ReplayGraph g, final int t0, final int t1 )
	{
		final java.util.Set< Integer > seen = new java.util.HashSet<>();
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() >= t0 && s.getTimepoint() <= t1 )
				seen.add( s.getTimepoint() );
		return seen.size();
	}

	/** Whether every spot is reachable from every other through links (one weakly-connected track). */
	private static boolean oneComponent( final ReplayGraph g )
	{
		return g.linkCount() == g.spotCount() - 1;
	}

	/**
	 * The invariant the user asked for in so many words: a track connects at every timepoint it spans.
	 *
	 * <p>The first version of this pass added one edge across the gap, and the report was immediate —
	 * "tracks joining scattered timepoints, these have many errors, make tracks that connect at every
	 * timepoint". So nothing in the graph may carry an edge that skips one, whatever created it.</p>
	 */
	@Test
	public void noEdgeInTheGraphMaySkipATimepoint()
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > before = g.track( 40, 5, 0, 1 );
		final List< Spot > after = g.track( 47, 5, 6, 1 );
		final ToolContext ctx = g.context();
		AutoCorrectOrchestrator.closeTrackGaps( ctx, 40, 51, SEARCH_FACTOR, MAX_DISP,
				AutoCorrectOrchestrator.MAX_LINK_GAP );

		// Every timepoint's pruner gets a look; none of them finds anything to remove, because there
		// is nothing spanning a gap left to find.
		for ( int t = 41; t <= 51; t++ )
			assertEquals( "pruneLongLinks found a gap-spanning edge at t=" + t, 0,
					AutoCorrectOrchestrator.pruneLongLinks( ctx, t, SEARCH_FACTOR, MAX_DISP ) );
		assertEquals( 12, g.spotCount() );
		assertTrue( oneComponent( g ) );
		assertTrue( reaches( g, before.get( before.size() - 1 ), after.get( 0 ) ) );
	}

	@Test
	public void anEdgeThatSkipsATimepointIsRemovedWhateverMadeIt()
	{
		// A project carrying one from an earlier build, or any un-guarded path.
		final ReplayGraph g = new ReplayGraph();
		final Spot from = g.spot( 40, 0 );
		final Spot to = g.spot( 42, 1 );
		g.link( from, to );
		final ToolContext ctx = g.context();

		assertEquals( 1, AutoCorrectOrchestrator.pruneLongLinks( ctx, 42, SEARCH_FACTOR, MAX_DISP ) );
		assertEquals( 0, g.linkCount() );
	}

	@Test
	public void aGapLongerThanTheCapIsLeftAlone()
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 40, 5, 0, 1 );                                // ends at t=44
		g.track( 49, 5, 6, 1 );                                // starts at t=49: a gap of 5
		final ToolContext ctx = g.context();
		final String unchanged = g.canonical();

		assertEquals( 0, AutoCorrectOrchestrator.closeTrackGaps( ctx, 40, 53, SEARCH_FACTOR, MAX_DISP,
				AutoCorrectOrchestrator.MAX_LINK_GAP ) );
		assertEquals( unchanged, g.canonical() );
	}

	@Test
	public void anAdjacentBreakIsLeftToThePassThatOwnsIt()
	{
		// Gap 1 is linkOrphanStarts' job, and it applies an unscaled cap and a division rule this pass
		// deliberately does not have. Two passes writing the same link would double-count it.
		final ReplayGraph g = new ReplayGraph();
		g.track( 40, 5, 0, 1 );                                // ends at t=44
		g.track( 45, 5, 5, 1 );                                // starts at t=45
		final ToolContext ctx = g.context();

		assertEquals( 0, AutoCorrectOrchestrator.closeTrackGaps( ctx, 40, 49, SEARCH_FACTOR, MAX_DISP,
				AutoCorrectOrchestrator.MAX_LINK_GAP ) );
	}

	@Test
	public void theCandidateThatMovedLeastWinsAndTakesTheEndWithIt()
	{
		// Two orphans compete for one end. Assignment is global by cost, not graph order, so the near
		// one wins — and the far one is left for a later window rather than linked as a division.
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > before = g.track( 40, 5, 0, 1 );   // ends at t=44, x=4
		final List< Spot > near = g.track( 47, 4, 6, 1 );
		final List< Spot > far = g.track( 47, 4, 16, 1 );
		final ToolContext ctx = g.context();

		assertEquals( 1, AutoCorrectOrchestrator.closeTrackGaps( ctx, 40, 51, SEARCH_FACTOR, MAX_DISP,
				AutoCorrectOrchestrator.MAX_LINK_GAP ) );
		final Spot end = before.get( before.size() - 1 );
		assertTrue( reaches( g, end, near.get( 0 ) ) );
		assertFalse( "a second successor across a gap would be a fabricated division",
				reaches( g, end, far.get( 0 ) ) );
	}

	@Test
	public void aTrackThatAlreadyContinuesIsNeverGivenASecondFuture()
	{
		// The most expensive way this pass could be wrong is by inventing a division, so it only ever
		// makes 1:1 continuations: an end that already has a successor is not a candidate at all.
		final ReplayGraph g = new ReplayGraph();
		g.track( 40, 8, 0, 1 );                                // continuous t=40..47, nothing dangling
		g.track( 45, 4, 3, 1 );                                // an orphan start 5 tp into that track
		final ToolContext ctx = g.context();
		final int linksBefore = g.linkCount();

		assertEquals( 0, AutoCorrectOrchestrator.closeTrackGaps( ctx, 40, 49, SEARCH_FACTOR, MAX_DISP,
				AutoCorrectOrchestrator.MAX_LINK_GAP ) );
		assertEquals( linksBefore, g.linkCount() );
	}

	@Test
	public void theDisplacementCapScalesWithTheGapAndStillBinds()
	{
		// 3 frames at 7.0 per frame allows 21; this one moved 30. Distance is the gate that actually
		// decides here — see MAX_LINK_GAP for the ground-truth displacement percentiles it comes from.
		final ReplayGraph g = new ReplayGraph();
		g.track( 40, 5, 0, 1 );                                // ends at t=44, x=4
		g.track( 47, 4, 34, 1 );                               // 30 away
		final ToolContext ctx = g.context();

		assertEquals( 0, AutoCorrectOrchestrator.closeTrackGaps( ctx, 40, 51, SEARCH_FACTOR, MAX_DISP,
				AutoCorrectOrchestrator.MAX_LINK_GAP ) );
		// ...and the same pair is joined once the cap is raised, so it was the cap that refused it.
		assertEquals( 1, AutoCorrectOrchestrator.closeTrackGaps( ctx, 40, 51, SEARCH_FACTOR, 20.0,
				AutoCorrectOrchestrator.MAX_LINK_GAP ) );
	}

	@Test
	public void aConfirmedResultIsNotGivenASuccessorItNeverHad()
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > before = g.track( 40, 5, 0, 1 );
		g.track( 47, 4, 6, 1 );
		final ToolContext ctx = g.protect( before.get( before.size() - 1 ) );

		assertEquals( 0, AutoCorrectOrchestrator.closeTrackGaps( ctx, 40, 51, SEARCH_FACTOR, MAX_DISP,
				AutoCorrectOrchestrator.MAX_LINK_GAP ) );
	}


	@Test
	public void anIsolatedSpotIsOfferedItsGapBeforeItIsDeletedForBeingIsolated()
	{
		// Ordering, not arithmetic. Every reconnection pass in globalCleanup links to t-1 only, and the
		// isolated-spot deletion runs straight after them — so the far end of a two- or three-frame gap
		// was deleted before the whole-movie gap closing in [5c/6] could ever see it.
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > before = g.track( 0, 5, 0, 1 );     // t=0..4, ends at x=4
		g.track( 0, 10, 200, 1 );                              // far away, and gives t=6 a spot so the
		                                                       // isolated-spot deletion is not skipped
		final Spot stranded = g.spot( 7, 6 );                  // no links at all, 3 tp past that end
		final ToolContext ctx = g.context();

		AutoCorrectOrchestrator.globalCleanup( ctx, 10 );

		// 15 originals + the stranded spot + the two the bridge fills the gap with.
		assertEquals( "the far end of the gap was deleted before it could be joined", 18, g.spotCount() );
		assertTrue( reaches( g, before.get( before.size() - 1 ), stranded ) );
	}

	/**
	 * Ratchet on the same ordering inside {@code correctTimepoint}, which cannot be replayed: it
	 * samples pixels, and {@code ToolContext.forReplay} has no image. Comments are stripped first —
	 * this project has shipped a source ratchet that passed on a comment naming the thing it guarded.
	 */
	@Test
	public void gapClosingPrecedesEveryIsolatedSpotDeletionInTheCorrectionSequence() throws IOException
	{
		final List< String > plan = new java.util.ArrayList<>();
		for ( final org.mastodon.mamut.automahout.tool.CorrectionPass p : FramePasses.BUILT_IN )
			plan.add( p.getId() );
		final int firstClose = plan.indexOf( "frame.bridge_gaps" );
		final int firstDelete = plan.indexOf( "frame.isolated_spots" );
		assertTrue( "correctTimepoint no longer closes gaps at all", firstClose >= 0 );
		assertTrue( "correctTimepoint no longer deletes isolated spots", firstDelete >= 0 );
		assertTrue( "the isolated-spot deletion runs before anything can bridge a multi-frame gap, so "
				+ "the far end of the gap is gone by the time [5c/6] looks", firstClose < firstDelete
						&& firstClose < plan.indexOf( "frame.isolated_final" ) );
	}

	/** Everything outside a string literal that is a {@code //} or block comment, removed. */
	private static String stripComments( final String src )
	{
		return src.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\n]*", "" );
	}

	/**
	 * The source of the method named {@code name} that actually holds the logic.
	 *
	 * <p>Takes the LONGEST body among the overloads, because a one-line delegating overload declared
	 * first would otherwise be the one inspected -- and a ratchet that reads an empty body proves
	 * nothing, which this test suite has now been bitten by twice.</p>
	 */
	private static String bodyOf( final Path file, final String name ) throws IOException
	{
		final String text = new String( Files.readAllBytes( file ), StandardCharsets.UTF_8 );
		String best = null;
		int at = text.indexOf( " " + name + "(" );
		assertTrue( "no method named " + name + " in " + file, at >= 0 );
		while ( at >= 0 )
		{
			int depth = 0;
			int i = text.indexOf( '{', at );
			final int from = i;
			for ( ; i < text.length(); i++ )
			{
				if ( text.charAt( i ) == '{' ) depth++;
				else if ( text.charAt( i ) == '}' && --depth == 0 ) break;
			}
			final String body = text.substring( from, i );
			if ( best == null || body.length() > best.length() )
				best = body;
			at = text.indexOf( " " + name + "(", at + 1 );
		}
		return best;
	}

	/**
	 * A reconnection pass must never run without a distance bound.
	 *
	 * <p>{@code runTopologyConsistency} takes its per-frame cap from the learned profile, else from the
	 * settings object. The windowed caller writes a measured cap into its own settings every window; the
	 * whole-movie caller writes one only when a profile exists, and a profile learned on another dataset
	 * is refused — the normal case. So Phase 2 ran with {@code max_disp=0.0} while Phase 1 had 5.8-7.7
	 * µm, and a link was bounded only by a multiple of the spot's radius: up to nine radii once
	 * {@link AutoCorrectOrchestrator#closeTrackGaps} scales it by the gap. The run reported it —
	 * "reconnected tracklets=149, closed gaps=59 ... max_disp=0.0" — and the viewer showed a track at
	 * the image edge joining scattered timepoints across the frame.</p>
	 */
	@Test
	public void theWholeMovieConsistencyPassMeasuresItsOwnCapWhenNothingSuppliesOne()
	{
		final ReplayGraph g = new ReplayGraph();
		// A population moving ~1 unit per frame: the measured cap lands near 4 (see
		// ESTIMATED_DISPLACEMENT_CAP_FACTOR), so 12 over a gap of three -- nowhere near the 40 the
		// stray pair below is apart, but the radius-scaled bound alone allows 45.
		g.track( 0, 12, 0, 1 );
		g.track( 0, 12, 40, 1 );
		final Spot end = g.spot( 3, 200 );
		final Spot faraway = g.spot( 6, 240 );
		final ToolContext ctx = g.context();

		final double measured = AutoCorrectOrchestrator.measureMaxDisplacement( ctx, 0, 11 );
		assertTrue( "the cap has to be measurable from the graph itself, or this fix cannot work: "
				+ measured, measured > 0.0 && measured < 20.0 );
		// With that cap the stray pair is out of reach at every gap; with no cap the radius-scaled
		// bound alone (3 x 3 x 5 = 45 per gap) would have joined them.
		assertEquals( 0, AutoCorrectOrchestrator.closeTrackGaps( ctx, 0, 11, SEARCH_FACTOR, measured,
				AutoCorrectOrchestrator.MAX_LINK_GAP ) );
		assertEquals( "this is what running uncapped does", 1,
				AutoCorrectOrchestrator.closeTrackGaps( ctx, 0, 11, SEARCH_FACTOR, 0.0,
						AutoCorrectOrchestrator.MAX_LINK_GAP ) );
		assertTrue( reaches( g, end, faraway ) );
	}

	/** Ratchet: the measured fallback stays wired into the one sequence both phases now share. */
	@Test
	public void theConsistencyPassNeverAcceptsNoCapAtAll() throws IOException
	{
		final String body = stripComments( bodyOf( Paths.get( "src", "main", "java", "org", "mastodon",
				"mamut", "automahout", "autocorrect", "LineagePasses.java" ),
				"measureThresholds" ) );
		assertTrue( "with no profile and no settings value, runTopologyConsistency must measure the "
				+ "per-frame cap from the graph rather than reconnect with no distance bound at all",
				body.contains( "measureMaxDisplacement(" ) );
	}

	/**
	 * A gap bridge fills the timepoints INSIDE the gap, which are earlier ones — so from the first
	 * timepoints of a window it reaches behind the window.
	 *
	 * <p>Two windows of a run were stopped for exactly this: "[5/6] autocorrect was scoped to
	 * t=10..19 but changed 1 timepoint(s) outside it (net +2 spots, first at t=8)", and the same at
	 * t=28 from t=30..39. A stopped window loses every phase after the one that failed — in those two
	 * cases the refinement, the consistency pass and the validation — so the cost of writing two spots
	 * into a finished region was two windows of the run.</p>
	 *
	 * <p>That region has had its own correction, its own consistency pass and its own validation. A
	 * spot appearing there afterwards is judged by none of them.</p>
	 */
	@Test
	public void theBridgeDoesNotReachBehindTheWindowItIsCorrecting()
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 5, 3, 0, 1 );                   // ends at t=7, inside the previous window
		final Spot orphan = g.spot( 10, 5 );     // the first timepoint of this window
		final ToolContext ctx = g.context();
		final String before = g.canonical();

		// t=10 with the window starting at t=10: the only bridge available would fill t=8 and t=9.
		AutoCorrectOrchestrator.closeTrackGaps( ctx, Math.max( 10, 10 - AutoCorrectOrchestrator.MAX_LINK_GAP ),
				10, SEARCH_FACTOR, MAX_DISP, AutoCorrectOrchestrator.MAX_LINK_GAP );

		assertEquals( "a finished timepoint gained a spot from the window after it", before, g.canonical() );
		assertEquals( 4, g.spotCount() );
		assertTrue( orphan.incomingEdges().isEmpty() );
	}

	/** And inside the window it still bridges, which is the whole point of the pass. */
	@Test
	public void theSameBridgeIsStillMadeWhenTheGapFitsInsideTheWindow()
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > before = g.track( 10, 3, 0, 1 );   // ends at t=12, inside the window
		final Spot orphan = g.spot( 15, 5 );
		final ToolContext ctx = g.context();

		assertEquals( 1, AutoCorrectOrchestrator.closeTrackGaps(
				ctx, Math.max( 10, 15 - AutoCorrectOrchestrator.MAX_LINK_GAP ), 15, SEARCH_FACTOR,
				MAX_DISP, AutoCorrectOrchestrator.MAX_LINK_GAP ) );
		assertEquals( "t=13 and t=14 are inside the window and get their cells", 6, g.spotCount() );
		assertTrue( reaches( g, before.get( before.size() - 1 ), orphan ) );
	}

	/**
	 * Ratchet: the bound has to be applied where the range is built, not left to the caller to
	 * remember. Comments stripped — the rule is the {@code Math.max}, not a note about it.
	 */
	@Test
	public void theWindowBoundIsBakedIntoTheRangeTheBridgeIsGiven() throws IOException
	{
		final Path passes = Paths.get( "src", "main", "java", "org", "mastodon", "mamut", "automahout", "autocorrect",
				"FramePasses.java" );
		final String code = stripComments( new String( Files.readAllBytes( passes ), StandardCharsets.UTF_8 ) );
		final int decl = code.indexOf( "static int bridgeFrom(" );
		assertTrue( "bridgeFrom has moved", decl >= 0 );
		assertTrue( "the per-timepoint bridge must clamp its range to the first timepoint this "
				+ "correction run may write to", code.substring( decl, code.indexOf( "\n\t}", decl ) )
						.contains( "earliestWritableTp" ) );
		assertEquals( "and both bridge calls must use the clamped range", 2,
				code.split( "closeTrackGaps\\( s\\.ctx, bridgeFrom\\( s \\)", -1 ).length - 1 );
		assertEquals( "and nothing else in the frame plan bridges", 2, code.split( "closeTrackGaps\\(", -1 ).length - 1 );
	}
}
