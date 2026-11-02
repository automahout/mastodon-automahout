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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * The graph surgery of {@link AutoCorrectOrchestrator#repairConvergedTracks}: two tracks run into one
 * detection, and come out as two tracks again. The image read is replaced by a fitter that puts each
 * piece exactly on its seed, which is what a fit on a clean image does.
 */
public class ConvergenceRepairTest
{
	/** Puts piece k on seed k, radius 5. */
	private static final AutoCorrectOrchestrator.PieceFitter ON_THE_SEEDS = ( t, c, r, cov, seeds, fr ) -> {
		final double[][] cv = { { 25, 0, 0 }, { 0, 25, 0 }, { 0, 0, 25 } };
		return new AutoCorrectOrchestrator.Pieces( new double[][] { seeds[ 0 ].clone(), seeds[ 1 ].clone() },
				new double[][][] { cv, cv } );
	};

	private static final AutoCorrectOrchestrator.PieceFitter REFUSES = ( t, c, r, cov, seeds, fr ) -> null;

	/**
	 * Cells at x=0 and x=20 for t=0..2, one detection at x=10 (radius 15) over both for t=3..4, and the
	 * two again at t=5: the merge ran on from the x=0 track, which also took the x=0 cell at t=5.
	 */
	private static ReplayGraph merge( final boolean falseDivisionAtTheEnd )
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > a = g.track( 0, 3, 0, 0 );
		g.track( 0, 3, 20, 0 );
		final Spot m3 = g.spot( 3, 10, 0, 0, 15 );
		final Spot m4 = g.spot( 4, 10, 0, 0, 15 );
		g.link( a.get( 2 ), m3 );
		g.link( m3, m4 );
		final Spot x0 = g.spot( 5, 0, 0, 0, 5 );
		final Spot x20 = g.spot( 5, 20, 0, 0, 5 );
		g.link( m4, x0 );
		if ( falseDivisionAtTheEnd )
			g.link( m4, x20 );
		return g;
	}

	private static int spotsAt( final ModelGraph graph, final int t )
	{
		int n = 0;
		for ( final Spot s : graph.vertices() )
			if ( s.getTimepoint() == t )
				n++;
		return n;
	}

	/** Every spot has at most one predecessor and one successor, and t=0..5 is two unbroken tracks. */
	private static void assertTwoUnbrokenTracks( final ModelGraph graph )
	{
		final Spot ref = graph.vertexRef();
		int starts = 0, ends = 0;
		for ( final Spot s : graph.vertices() )
		{
			assertTrue( s.incomingEdges().size() <= 1 );
			assertTrue( "no division is left", s.outgoingEdges().size() <= 1 );
			if ( s.incomingEdges().isEmpty() )
			{
				assertEquals( 0, s.getTimepoint() );
				starts++;
			}
			if ( s.outgoingEdges().isEmpty() )
			{
				assertEquals( 5, s.getTimepoint() );
				ends++;
			}
			// Each track keeps to its own cell: no link jumps between x=0 and x=20.
			for ( final Link e : s.outgoingEdges() )
				assertEquals( s.getDoublePosition( 0 ), e.getTarget( ref ).getDoublePosition( 0 ), 1e-9 );
		}
		graph.releaseRef( ref );
		assertEquals( 2, starts );
		assertEquals( 2, ends );
	}

	@Test
	public void twoTracksIntoOneDetectionComeOutAsTwoTracks()
	{
		final ReplayGraph g = merge( false );
		final ToolContext ctx = g.context();
		assertArrayEquals( new int[] { 2, 1 }, AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 6, ON_THE_SEEDS, null ) );
		assertEquals( 2, spotsAt( g.graph(), 3 ) );
		assertEquals( 2, spotsAt( g.graph(), 4 ) );
		assertTwoUnbrokenTracks( g.graph() );
	}

	@Test
	public void theDivisionTheMergeWouldHaveBecomeGoesOneDaughterToEachCell()
	{
		final ReplayGraph g = merge( true );
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 6, ON_THE_SEEDS, null );
		assertTwoUnbrokenTracks( g.graph() );
	}

	@Test
	public void anImageThatDoesNotSupportTwoCellsChangesNothing()
	{
		final ReplayGraph g = merge( false );
		final String before = g.canonical();
		assertArrayEquals( new int[] { 0, 0 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 6, REFUSES, null ) );
		assertEquals( before, g.canonical() );
	}

	@Test
	public void aTrackEndingBesideASpotItIsNotInsideIsLeftAlone()
	{
		// The same, but the detection at t=3 is an ordinary cell on the x=0 track: x=20 is outside it.
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > a = g.track( 0, 4, 0, 0 );
		g.track( 0, 3, 20, 0 );
		final String before = g.canonical();
		assertArrayEquals( new int[] { 0, 0 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 4, ON_THE_SEEDS, null ) );
		assertEquals( before, g.canonical() );
		assertEquals( 4, a.size() );
	}

	@Test
	public void aProtectedDetectionIsNeverReplaced()
	{
		final ReplayGraph g = merge( false );
		Spot m3 = null;
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 3 )
				m3 = g.graph().vertexRef().refTo( s );
		final ToolContext ctx = g.protect( m3 );
		final String before = g.canonical();
		AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 6, ON_THE_SEEDS, null );
		assertEquals( before, g.canonical() );
	}

	@Test
	public void aWindowIsNeverCarriedPastItsLastTimepoint()
	{
		final ReplayGraph g = merge( false );
		// The window ends at t=3: t=4's merged detection belongs to the next window.
		assertArrayEquals( new int[] { 1, 1 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 4, ON_THE_SEEDS, null ) );
		assertEquals( 2, spotsAt( g.graph(), 3 ) );
		assertEquals( 1, spotsAt( g.graph(), 4 ) );
		// ...and the merged detection beyond it still has a predecessor, the nearer piece.
		final List< Spot > at4 = new ArrayList<>();
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 4 )
				at4.add( s );
		assertFalse( at4.get( 0 ).incomingEdges().isEmpty() );
	}
	/**
	 * As {@link #merge}, but the x=20 cell is missed for {@code missed} frames before the merge at t=4:
	 * its track ends at t=3-missed. At t=6 the two part again.
	 */
	private static ReplayGraph mergeAfterAMiss( final int missed, final boolean coveredInBetween )
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > a = g.track( 0, 4, 0, 0 );
		g.track( 0, 4 - missed, 20, 0 );
		if ( coveredInBetween )
		{
			// Something sits on the x=20 cell in the missed frame and goes on elsewhere: not a miss.
			final Spot c = g.spot( 3, 20, 0, 0, 5 );
			g.link( c, g.spot( 4, 60, 0, 0, 5 ) );
		}
		final Spot m4 = g.spot( 4, 10, 0, 0, 15 );
		final Spot m5 = g.spot( 5, 10, 0, 0, 15 );
		g.link( a.get( 3 ), m4 );
		g.link( m4, m5 );
		g.link( m5, g.spot( 6, 0, 0, 0, 5 ) );
		g.spot( 6, 20, 0, 0, 5 );
		return g;
	}

	@Test
	public void aTrackThatEndedAFrameOrTwoBeforeTheMergeIsStillRepaired()
	{
		for ( final int missed : new int[] { 1, 2 } )
		{
			final ReplayGraph g = mergeAfterAMiss( missed, false );
			assertArrayEquals( "missed " + missed, new int[] { 2, 1 },
					AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 7, ON_THE_SEEDS, null ) );
			assertEquals( 2, spotsAt( g.graph(), 4 ) );
			assertEquals( 2, spotsAt( g.graph(), 5 ) );
			// The x=20 track runs on into its own piece across the missed frames.
			final Spot ref = g.graph().vertexRef();
			int gapLinks = 0;
			for ( final Spot s : g.graph().vertices() )
				for ( final Link e : s.outgoingEdges() )
				{
					e.getTarget( ref );
					assertEquals( s.getDoublePosition( 0 ), ref.getDoublePosition( 0 ), 1e-9 );
					if ( ref.getTimepoint() - s.getTimepoint() > 1 )
						gapLinks++;
				}
			g.graph().releaseRef( ref );
			assertEquals( 1, gapLinks );
		}
	}

	@Test
	public void aTrackEndedLongerAgoThanTheGapLinkingReachIsLeftAlone()
	{
		final ReplayGraph g = mergeAfterAMiss( AutoCorrectOrchestrator.CONVERGENCE_MAX_GAP, false );
		final String before = g.canonical();
		assertArrayEquals( new int[] { 0, 0 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 7, ON_THE_SEEDS, null ) );
		assertEquals( before, g.canonical() );
	}

	@Test
	public void aCellCoveredByAnotherDetectionInBetweenWasNotMissed()
	{
		// The x=20 track that ended at t=2 is never linked across t=3: something was on its cell there.
		// (That something, sent on to x=60, is the track the repair gives the x=20 piece instead.)
		final ReplayGraph g = mergeAfterAMiss( 1, true );
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 7, ON_THE_SEEDS, null );
		final Spot ref = g.graph().vertexRef();
		for ( final Spot s : g.graph().vertices() )
			for ( final Link e : s.outgoingEdges() )
				assertEquals( "no link across a frame", s.getTimepoint() + 1, e.getTarget( ref ).getTimepoint() );
		g.graph().releaseRef( ref );
	}

	@Test
	public void cellsThatMovedAreFoundWhereTheMergedDetectionCarriedThem()
	{
		// Two cells moving +10 per frame: x=0 and x=20 at t=2, x=30 and x=50 at t=3 under one detection at
		// x=40 (radius 13). The second cell's last position, x=20, is outside it; carried, it is at x=50.
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > a = g.track( 0, 3, -20, 10 );
		g.track( 0, 3, 0, 10 );
		final Spot m3 = g.spot( 3, 40, 0, 0, 13 );
		g.link( a.get( 2 ), m3 );
		assertArrayEquals( new int[] { 1, 1 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 4, ON_THE_SEEDS, null ) );
		assertEquals( 2, spotsAt( g.graph(), 3 ) );
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 3 )
				assertEquals( "each piece has its own track", 1, s.incomingEdges().size() );
	}

	/**
	 * Cells at x=0 and x=20 for t=0..2, one detection at x=10 (radius 15) over both at t=3; the x=20 track
	 * was linked to a detection far away at x=60.
	 */
	private static ReplayGraph mergeWithTheSecondTrackSentElsewhere()
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > a = g.track( 0, 3, 0, 0 );
		final List< Spot > b = g.track( 0, 3, 20, 0 );
		final Spot m3 = g.spot( 3, 10, 0, 0, 15 );
		g.link( a.get( 2 ), m3 );
		g.link( b.get( 2 ), g.spot( 3, 60, 0, 0, 5 ) );
		return g;
	}

	@Test
	public void aTrackTheLinkerSentElsewhereIsGivenItsPieceOfTheMerge()
	{
		final ReplayGraph g = mergeWithTheSecondTrackSentElsewhere();
		assertArrayEquals( new int[] { 1, 1 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 4, ON_THE_SEEDS, null ) );
		final Spot ref = g.graph().vertexRef();
		for ( final Spot s : g.graph().vertices() )
		{
			assertTrue( s.outgoingEdges().size() <= 1 );
			for ( final Link e : s.outgoingEdges() )
				assertEquals( "no link jumps cells", s.getDoublePosition( 0 ), e.getTarget( ref ).getDoublePosition( 0 ), 1e-9 );
			if ( s.getTimepoint() == 3 && s.getDoublePosition( 0 ) == 60 )
				assertTrue( "the far detection starts a track of its own", s.incomingEdges().isEmpty() );
		}
		g.graph().releaseRef( ref );
	}

	@Test
	public void aTrackSentElsewhereKeepsItsLinkWhenTheImageShowsNoTwoCells()
	{
		final ReplayGraph g = mergeWithTheSecondTrackSentElsewhere();
		final String before = g.canonical();
		assertArrayEquals( new int[] { 0, 0 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 4, REFUSES, null ) );
		assertEquals( before, g.canonical() );
	}

	/** Two cells stacked in z at one (x, y) for t=0..2, at z=0 and z={@code secondZ}; at t=3 one detection at z=0's track. */
	private static ReplayGraph stacked( final double secondZ, final double mergedZ, final double mergedRadius )
	{
		final ReplayGraph g = new ReplayGraph();
		Spot a = null, b = null;
		for ( int t = 0; t < 3; t++ )
		{
			final Spot na = g.spot( t, 0, 0, 0, 3 ), nb = g.spot( t, 0, 0, secondZ, 3 );
			if ( a != null )
			{
				g.link( a, na );
				g.link( b, nb );
			}
			a = na;
			b = nb;
		}
		g.link( a, g.spot( 3, 0, 0, mergedZ, mergedRadius ) );
		return g;
	}

	@Test
	public void onAStackTwoCellsMergedInZAreSplitInZ()
	{
		final ReplayGraph g = stacked( 8, 4, 8 );
		final int[] r = AutoCorrectOrchestrator.withGeometry( true,
				() -> AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 4, ON_THE_SEEDS, null ) );
		assertArrayEquals( new int[] { 1, 1 }, r );
		final List< Double > zs = new ArrayList<>();
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 3 )
			{
				zs.add( s.getDoublePosition( 2 ) );
				assertEquals( "each piece has its own track", 1, s.incomingEdges().size() );
			}
		java.util.Collections.sort( zs );
		assertEquals( 0, zs.get( 0 ), 1e-9 );
		assertEquals( 8, zs.get( 1 ), 1e-9 );
	}

	@Test
	public void onAStackASplitIsCarriedForwardWhileTheMergeLastsMovingInZ()
	{
		// As above, and the merged detection goes on at t=4, 2 further along z: the split follows it.
		final ReplayGraph g = stacked( 8, 4, 8 );
		Spot m3 = null;
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 3 )
				m3 = g.graph().vertexRef().refTo( s );
		g.link( m3, g.spot( 4, 0, 0, 6, 8 ) );
		final int[] r = AutoCorrectOrchestrator.withGeometry( true,
				() -> AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 5, ON_THE_SEEDS, null ) );
		assertArrayEquals( new int[] { 2, 1 }, r );
		final List< Double > zs = new ArrayList<>();
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 4 )
				zs.add( s.getDoublePosition( 2 ) );
		java.util.Collections.sort( zs );
		assertEquals( 2, zs.get( 0 ), 1e-9 );
		assertEquals( 10, zs.get( 1 ), 1e-9 );
	}

	@Test
	public void onAStackACellFarAwayInZIsNotTheSecondCell()
	{
		// Same (x, y), but 20 apart in z: in plane it is inside the detection, in 3-D it is not.
		final ReplayGraph g = stacked( 20, 0, 5 );
		final String before = g.canonical();
		final int[] r = AutoCorrectOrchestrator.withGeometry( true,
				() -> AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 4, ON_THE_SEEDS, null ) );
		assertArrayEquals( new int[] { 0, 0 }, r );
		assertEquals( before, g.canonical() );
	}

	/**
	 * Cell x=0 tracked from t=0; one detection at x=10 (radius 15) over it and an undetected
	 * neighbour at t=2..3; at t=4 the two part: x=0 continues the track, x=20 starts a new one -- or,
	 * with {@code divides}, both hang off the merged detection as a "division".
	 */
	private static ReplayGraph mergeSeenOnlyAtItsEnd( final boolean divides )
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > a = g.track( 0, 2, 0, 0 );
		final Spot m2 = g.spot( 2, 10, 0, 0, 15 );
		final Spot m3 = g.spot( 3, 10, 0, 0, 15 );
		g.link( a.get( 1 ), m2 );
		g.link( m2, m3 );
		final Spot x0 = g.spot( 4, 0, 0, 0, 5 );
		final Spot x20 = g.spot( 4, 20, 0, 0, 5 );
		g.link( m3, x0 );
		if ( divides )
			g.link( m3, x20 );
		return g;
	}

	private static AutoCorrectOrchestrator.SplitQuestion answering( final Boolean answer, final int[] asked )
	{
		return ( t, c, r, cov, pieces, why ) -> {
			asked[ 0 ]++;
			return answer == null ? AutoCorrectOrchestrator.SplitAnswer.NONE
					: answer ? AutoCorrectOrchestrator.SplitAnswer.TWO : AutoCorrectOrchestrator.SplitAnswer.ONE;
		};
	}

	@Test
	public void aMergeSeenOnlyWhereItEndsIsSplitBackToWhereItBeganWhenTheUserSaysTwo()
	{
		for ( final boolean divides : new boolean[] { false, true } )
		{
			final ReplayGraph g = mergeSeenOnlyAtItsEnd( divides );
			final int[] asked = { 0 };
			assertArrayEquals( new int[] { 2, 1 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 5,
					ON_THE_SEEDS, answering( Boolean.TRUE, asked ) ) );
			assertEquals( "asked once, at the end; carried back without asking again", 1, asked[ 0 ] );
			assertEquals( 2, spotsAt( g.graph(), 2 ) );
			assertEquals( 2, spotsAt( g.graph(), 3 ) );
			final Spot ref = g.graph().vertexRef();
			int starts = 0;
			for ( final Spot s : g.graph().vertices() )
			{
				assertTrue( "no division is left", s.outgoingEdges().size() <= 1 );
				for ( final Link e : s.outgoingEdges() )
					assertEquals( s.getDoublePosition( 0 ), e.getTarget( ref ).getDoublePosition( 0 ), 1e-9 );
				if ( s.incomingEdges().isEmpty() )
					starts++;
			}
			// x=0 from t=0; x=20 from where it was first seen, inside the merge.
			assertEquals( 2, starts );
		}
	}

	@Test
	public void oneCellIsRememberedAndNotAskedAgain()
	{
		final ReplayGraph g = mergeSeenOnlyAtItsEnd( false );
		final ToolContext ctx = g.context();
		final String before = g.canonical();
		final int[] asked = { 0 };
		assertArrayEquals( new int[] { 0, 0 }, AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 5,
				ON_THE_SEEDS, answering( Boolean.FALSE, asked ) ) );
		assertEquals( before, g.canonical() );
		// The consistency pass runs again over the same timepoints in the next window and in Phase 2.
		AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 5, ON_THE_SEEDS, answering( Boolean.FALSE, asked ) );
		assertEquals( 1, asked[ 0 ] );
	}

	@Test
	public void noAnswerLeavesItAndStopsAsking()
	{
		final ReplayGraph g = mergeSeenOnlyAtItsEnd( true );
		final ToolContext ctx = g.context();
		final String before = g.canonical();
		final int[] asked = { 0 };
		AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 5, ON_THE_SEEDS, answering( null, asked ) );
		AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 5, ON_THE_SEEDS, answering( Boolean.TRUE, asked ) );
		assertEquals( before, g.canonical() );
		assertEquals( 1, asked[ 0 ] );
	}

	@Test
	public void withNobodyToAskTheEndOfAMergeIsLeftAlone()
	{
		final ReplayGraph g = mergeSeenOnlyAtItsEnd( true );
		final String before = g.canonical();
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 5, ON_THE_SEEDS, null );
		assertEquals( before, g.canonical() );
	}

	@Test
	public void whenTheImageFindsNoTwoCellsTheUserIsStillAskedAndNothingIsSplit()
	{
		final ReplayGraph g = mergeSeenOnlyAtItsEnd( true );
		final String before = g.canonical();
		final int[] asked = { 0 };
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 5, REFUSES, answering( Boolean.TRUE, asked ) );
		assertEquals( "asked, with no B to choose", 1, asked[ 0 ] );
		assertEquals( before, g.canonical() );
	}

	@Test
	public void aPieceLandingOnAnotherDetectionIsRefused()
	{
		// As the merge above, but the x=20 cell at t=3 already has a detection of its own: the merged
		// detection is the x=0 cell's, and splitting it would put a second spot on x=20.
		final ReplayGraph g = merge( false );
		final Spot x3 = g.spot( 3, 20, 0, 0, 5 );
		final Spot x4 = g.spot( 4, 20, 0, 0, 5 );
		g.link( x3, x4 ); // a track of its own, so it does not read as a merge's ended neighbour
		final String before = g.canonical();
		assertArrayEquals( new int[] { 0, 0 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 6,
				ON_THE_SEEDS, null ) );
		assertEquals( before, g.canonical() );
	}

	@Test
	public void aPieceLandingOnAnotherDetectionIsNotAskedAboutEither()
	{
		// It is a duplicate far more often than a right split, so the user is not shown it.
		final ReplayGraph g = merge( false );
		final Spot x3 = g.spot( 3, 20, 0, 0, 5 );
		final Spot x4 = g.spot( 4, 20, 0, 0, 5 );
		g.link( x3, x4 );
		final int[] asked = { 0 };
		final boolean[] askedAt3 = { false };
		final AutoCorrectOrchestrator.SplitQuestion yes = ( t, c, r, cov, pieces, w ) -> {
			asked[ 0 ]++;
			askedAt3[ 0 ] |= t == 3;
			return AutoCorrectOrchestrator.SplitAnswer.TWO;
		};
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 6, ON_THE_SEEDS, yes );
		assertFalse( askedAt3[ 0 ] );
		assertEquals( 2, spotsAt( g.graph(), 3 ) );
	}

	@Test
	public void aMergeSeenWhereItStartsIsAskedTooAndOneCellIsKept()
	{
		final ReplayGraph g = merge( false );
		final String before = g.canonical();
		final int[] asked = { 0 };
		assertArrayEquals( new int[] { 0, 0 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 6,
				ON_THE_SEEDS, answering( Boolean.FALSE, asked ) ) );
		assertEquals( before, g.canonical() );
		assertEquals( "one question for the onset; the end it leads to is the same detection kept", 1, asked[ 0 ] );
	}

	@Test
	public void aMergeSeenWhereItStartsIsSplitOnTwoCellsAndOnNoAnswer()
	{
		for ( final Boolean answer : new Boolean[] { Boolean.TRUE, null } )
		{
			final ReplayGraph g = merge( false );
			final int[] asked = { 0 };
			assertArrayEquals( new int[] { 2, 1 }, AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 6,
					ON_THE_SEEDS, answering( answer, asked ) ) );
			assertEquals( "asked once, then carried without asking", 1, asked[ 0 ] );
			assertTwoUnbrokenTracks( g.graph() );
		}
	}

	@Test
	public void aHandEditIsLeftAlone()
	{
		// The user deletes the merged detection at t=3 and places the two cells there themselves; asked
		// again later (the merge still runs on at t=4), they keep what is there.
		final ReplayGraph g = merge( false );
		final ToolContext ctx = g.context();
		final int[] asked = { 0 };
		final AutoCorrectOrchestrator.SplitQuestion byHand = ( t, c, r, cov, pieces, why ) -> {
			if ( asked[ 0 ]++ > 0 )
				return AutoCorrectOrchestrator.SplitAnswer.ONE;
			final ModelGraph graph = g.graph();
			final List< Spot > at3 = new ArrayList<>();
			for ( final Spot s : graph.vertices() )
				if ( s.getTimepoint() == 3 )
					at3.add( graph.vertexRef().refTo( s ) );
			for ( final Spot s : at3 )
				graph.remove( s );
			g.spot( 3, 1, 0, 0, 5 );
			g.spot( 3, 19, 0, 0, 5 );
			return AutoCorrectOrchestrator.SplitAnswer.EDITED;
		};
		AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 6, ON_THE_SEEDS, byHand );
		final List< Double > xs = new ArrayList<>();
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 3 )
				xs.add( s.getDoublePosition( 0 ) );
		xs.sort( null );
		assertEquals( "exactly the user's two spots", java.util.Arrays.asList( 1.0, 19.0 ), xs );
	}

	/** Unseeded: two pieces either side of the centre along x, radius 5. */
	private static final AutoCorrectOrchestrator.PieceFitter ALONG_X = ( t, c, r, cov, seeds, fr ) -> {
		final double[][] cv = { { 25, 0, 0 }, { 0, 25, 0 }, { 0, 0, 25 } };
		return new AutoCorrectOrchestrator.Pieces(
				new double[][] { { c[ 0 ] - 6, c[ 1 ], c[ 2 ] }, { c[ 0 ] + 6, c[ 1 ], c[ 2 ] } },
				new double[][][] { cv, cv } );
	};

	/** Forty two-frame tracks of radius 5 far apart, and one whose second spot is suddenly radius 12. */
	private static ReplayGraph suddenlyLarge()
	{
		final ReplayGraph g = new ReplayGraph();
		for ( int i = 0; i < 40; i++ )
			g.link( g.spot( 0, 100 + 30 * i, 0, 0, 5 ), g.spot( 1, 100 + 30 * i, 0, 0, ( i % 3 ) + 4.5 ) );
		g.link( g.spot( 0, -6, 0, 0, 5 ), g.spot( 1, 0, 0, 0, 12 ) );
		return g;
	}

	@Test
	public void aDetectionSuddenlyMuchLargerIsAskedAboutAndSplitOnTwoCells()
	{
		final ReplayGraph g = suddenlyLarge();
		final int[] asked = { 0 };
		final String[] why = { null };
		final AutoCorrectOrchestrator.SplitQuestion yes = ( t, c, r, cov, pieces, w ) -> {
			asked[ 0 ]++;
			why[ 0 ] = w;
			return AutoCorrectOrchestrator.SplitAnswer.TWO;
		};
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 2, ALONG_X, yes );
		assertEquals( 1, asked[ 0 ] );
		assertTrue( why[ 0 ].startsWith( AutoCorrectOrchestrator.WHY_SIZE ) );
		assertEquals( "41 tracks of two, one split in two", 83, g.spotCount() );
		// The piece nearer the predecessor (at x=-6) continues its track; the other starts one.
		final Spot ref = g.graph().vertexRef();
		for ( final Spot s : g.graph().vertices() )
		{
			if ( s.getTimepoint() != 1 || s.getDoublePosition( 0 ) > 50 )
				continue;
			if ( s.getDoublePosition( 0 ) < 0 )
				assertEquals( -6, s.incomingEdges().iterator().next().getSource( ref ).getDoublePosition( 0 ), 1e-9 );
			else
				assertTrue( s.incomingEdges().isEmpty() );
		}
	}

	@Test
	public void sizeAloneIsNeverActedOnWithoutSomeoneToAsk()
	{
		final ReplayGraph g = suddenlyLarge();
		final String before = g.canonical();
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 2, ALONG_X, null );
		assertEquals( before, g.canonical() );
		final int[] asked = { 0 };
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 2, ALONG_X, answering( null, asked ) );
		assertEquals( "no answer leaves it too", before, g.canonical() );
	}

	/**
	 * The question window does not block the viewer, so the user can edit while it is open. Here they
	 * delete the spot at {@code tDelete} (and add one elsewhere, which takes its freed slot) and then
	 * answer B. Run 20261008-000342 stopped with damaged link lists "after the merge repair from t=76".
	 */
	private static void editWhileAsked( final int tDelete, final boolean reuse )
	{
		final ReplayGraph g = merge( true );
		final int[] asked = { 0 };
		final AutoCorrectOrchestrator.SplitQuestion editsThenSaysTwo = ( t, c, r, cov, pieces, why ) -> {
			if ( asked[ 0 ]++ > 0 )
				return AutoCorrectOrchestrator.SplitAnswer.ONE;
			final ModelGraph graph = g.graph();
			graph.getLock().writeLock().lock();
			try
			{
				Spot victim = null;
				for ( final Spot s : graph.vertices() )
					if ( s.getTimepoint() == tDelete && victim == null && s.getDoublePosition( 0 ) < 15 )
						victim = graph.vertexRef().refTo( s );
				graph.remove( victim );
				if ( !reuse )
					return AutoCorrectOrchestrator.SplitAnswer.TWO;
				final Spot n = graph.addVertex( graph.vertexRef() ).init( 1, new double[] { 300, 0, 0 }, 5 );
				final Spot m = graph.addVertex( graph.vertexRef() ).init( 2, new double[] { 300, 0, 0 }, 5 );
				graph.addEdge( n, m, graph.edgeRef() ).init();
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			return AutoCorrectOrchestrator.SplitAnswer.TWO;
		};
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 6, ON_THE_SEEDS, editsThenSaysTwo );
		assertEquals( null, org.mastodon.mamut.automahout.tool.GraphIntegrity.check( g.graph() ) );
		// Nothing the user did not delete is gone: the spots they added are still there.
		if ( reuse )
			assertTrue( g.canonical(), g.canonical().contains( "1@300.0,0.0,0.0\n" ) && g.canonical().contains( "2@300.0,0.0,0.0\n" ) );
		// ...and every link joins consecutive frames.
		final Spot x = g.graph().vertexRef(), y = g.graph().vertexRef();
		for ( final Link e : g.graph().edges() )
			assertEquals( g.canonical(), e.getSource( x ).getTimepoint() + 1, e.getTarget( y ).getTimepoint() );
	}

	@Test
	public void deletingTheMergedSpotWhileAskedLeavesTheGraphWhole()
	{
		editWhileAsked( 3, false );
		editWhileAsked( 3, true );
	}

	@Test
	public void deletingThePredecessorWhileAskedLeavesTheGraphWhole()
	{
		editWhileAsked( 2, false );
		editWhileAsked( 2, true );
	}

	@Test
	public void deletingTheNextMergedSpotWhileAskedLeavesTheGraphWhole()
	{
		editWhileAsked( 4, false );
		editWhileAsked( 4, true );
	}

	/** The image fit runs without the lock too: the user deletes the next merged spot meanwhile. */
	@Test
	public void deletingASpotWhileTheImageIsReadLeavesTheGraphWhole()
	{
		final ReplayGraph g = merge( true );
		final int[] fits = { 0 };
		final AutoCorrectOrchestrator.PieceFitter editsWhileFitting = ( t, c, r, cov, seeds, fr ) -> {
			if ( fits[ 0 ]++ == 0 )
			{
				final ModelGraph graph = g.graph();
				graph.getLock().writeLock().lock();
				try
				{
					Spot victim = null;
					for ( final Spot s : graph.vertices() )
						if ( s.getTimepoint() == 2 && s.getDoublePosition( 0 ) < 15 && victim == null )
							victim = graph.vertexRef().refTo( s );
					graph.remove( victim );
				}
				finally
				{
					graph.getLock().writeLock().unlock();
				}
			}
			return ON_THE_SEEDS.fit( t, c, r, cov, seeds, fr );
		};
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 6, editsWhileFitting, null );
		assertEquals( null, org.mastodon.mamut.automahout.tool.GraphIntegrity.check( g.graph() ) );
	}

	/** A merge whose spots change in the viewer while the image is read is not asked about, nor touched. */
	@Test
	public void aMergeChangedWhileTheImageIsReadIsNotAskedAbout()
	{
		final ReplayGraph g = merge( false );
		final int[] asked = { 0 };
		final AutoCorrectOrchestrator.PieceFitter deletesTheMerge = ( t, c, r, cov, seeds, fr ) -> {
			final ModelGraph graph = g.graph();
			graph.getLock().writeLock().lock();
			try
			{
				Spot victim = null;
				for ( final Spot s : graph.vertices() )
					if ( s.getTimepoint() == t && s.getDoublePosition( 0 ) == 10 && victim == null )
						victim = graph.vertexRef().refTo( s );
				if ( victim != null )
					graph.remove( victim );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			return ON_THE_SEEDS.fit( t, c, r, cov, seeds, fr );
		};
		AutoCorrectOrchestrator.repairConvergedTracks( g.context(), 1, 6, deletesTheMerge, ( t, c, r, cov, p, why ) -> {
			asked[ 0 ]++;
			return AutoCorrectOrchestrator.SplitAnswer.TWO;
		} );
		assertEquals( 0, asked[ 0 ] );
		assertEquals( null, org.mastodon.mamut.automahout.tool.GraphIntegrity.check( g.graph() ) );
	}
}
