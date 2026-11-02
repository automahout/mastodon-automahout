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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.GraphEdits.Reason;
import org.mastodon.mamut.automahout.tool.ExpectedCellCount;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

/**
 * The guard the facade exists for, exercised against a real graph.
 *
 * <p>Four separate things can happen to a spot a user has confirmed: it can be deleted, it can be
 * relabelled, it can gain an edge it never had, and a brand-new duplicate can be created on top of
 * it. Each has its own call sites and none of the fixes implies another — the last one, a seeding
 * scan re-discovering an already-tracked bright cell, was the one that kept being reported after the
 * first three were fixed. These tests cover deletion and duplication, the two the facade owns.</p>
 */
public class GraphEditsGuardTest
{
	@Test
	public void aProtectedSpotIsNotDeleted()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot keep = g.spot( 0, 0 );
		final ToolContext ctx = g.protect( keep );

		final boolean removed = GraphEdits.removeSpot( ctx, g.graph(), keep, Reason.NOT_A_CELL );

		assertFalse( "a confirmed cell was deleted", removed );
		assertEquals( 1, g.spotCount() );
	}

	/** Every automatic reason is refused, not just the one that happens to be tested first. */
	@Test
	public void everyAutomaticReasonIsRefused()
	{
		for ( final Reason why : Reason.values() )
		{
			if ( why == Reason.USER )
				continue;
			final ReplayGraph g = new ReplayGraph();
			final Spot keep = g.spot( 0, 0 );
			final ToolContext ctx = g.protect( keep );

			assertFalse( "deleted a confirmed cell for " + why,
					GraphEdits.removeSpot( ctx, g.graph(), keep, why ) );
			assertEquals( "spot count changed for " + why, 1, g.spotCount() );
		}
	}

	/** The person who marked the spot is still allowed to remove it. */
	@Test
	public void aUserMayDeleteTheirOwnProtectedSpot()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot keep = g.spot( 0, 0 );
		final ToolContext ctx = g.protect( keep );

		assertTrue( GraphEdits.removeSpot( ctx, g.graph(), keep, Reason.USER ) );
		assertEquals( 0, g.spotCount() );
	}

	/**
	 * A new spot landing on a confirmed cell is withdrawn, and leaves nothing behind.
	 *
	 * <p>The withdrawal matters as much as the refusal: the old pattern created the spot, then checked,
	 * then deleted it, so a pass that forgot the second half left a duplicate sitting on the user's own
	 * cell — which is how it was reported.</p>
	 */
	@Test
	public void aNewSpotOnAProtectedCellIsWithdrawn()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot keep = g.spot( 0, 0, 0, 0, 10 );
		final ToolContext ctx = g.protect( keep );
		final Spot ref = g.graph().vertexRef();

		final Spot made = GraphEdits.addSpot( ctx, g.graph(), ref, 0, new double[] { 1, 0, 0 }, 10,
				Reason.TRAINING );

		assertNull( "a duplicate was created on a confirmed cell", made );
		assertEquals( "the withdrawn spot was left in the graph", 1, g.spotCount() );
	}

	/** Far enough away, the same creation goes through. */
	@Test
	public void aNewSpotElsewhereIsCreated()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot keep = g.spot( 0, 0, 0, 0, 5 );
		final ToolContext ctx = g.protect( keep );
		final Spot ref = g.graph().vertexRef();

		final Spot made = GraphEdits.addSpot( ctx, g.graph(), ref, 0, new double[] { 500, 0, 0 }, 5,
				Reason.TRAINING );

		assertNotNull( made );
		assertEquals( 2, g.spotCount() );
	}

	/** The tally is what makes a run legible afterwards, so it has to count refusals too. */
	@Test
	public void refusalsAreCounted()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot keep = g.spot( 0, 0 );
		final Spot other = g.spot( 1, 100 );
		g.spot( 1, 200 ); // so `other` is not its frame's last spot, which is a different refusal
		final ToolContext ctx = g.protect( keep );

		GraphEdits.removeSpot( ctx, g.graph(), keep, Reason.NOT_A_CELL );
		GraphEdits.removeSpot( ctx, g.graph(), other, Reason.NOT_A_CELL );

		final int[] c = GraphEdits.tally( ctx ).get( Reason.NOT_A_CELL );
		assertEquals( "spots removed", 1, c[ 0 ] );
		assertEquals( "refusals", 1, c[ 4 ] );
	}

	// ------------------------------------------------------------------ the floor

	/**
	 * A weak judgement stops at the fewest cells the user's stated count still allows — not at the
	 * number itself, which is an estimate and is given a margin for being one (the same margin the
	 * ceiling takes on the other side). See {@link ExpectedCellCount#toleratedFloor}.
	 */
	@Test
	public void aStatisticalDeletionStopsAtTheFloor()
	{
		final ReplayGraph g = new ReplayGraph();
		for ( int i = 0; i < 6; i++ )
			g.spot( 0, i * 20 );
		final ToolContext ctx = g.context();
		ctx.setSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_PER_FRAME, 4 );

		int removed = 0;
		for ( final Spot s : new java.util.ArrayList<>( spotsAt( g, 0 ) ) )
			if ( GraphEdits.removeSpot( ctx, g.graph(), s, Reason.PROBABLY_NOT_A_CELL ) )
				removed++;

		final int floor = ExpectedCellCount.toleratedFloor( 4 );
		assertEquals( "deleted past what the estimate allows", 6 - floor, removed );
		assertEquals( floor, g.spotCount() );
	}

	/**
	 * A direct measurement does not, and that asymmetry is the whole reason the two reasons are
	 * separate: the floor was observed protecting detections reading literally zero intensity.
	 */
	@Test
	public void aMeasuredDeletionIgnoresTheFloor()
	{
		final ReplayGraph g = new ReplayGraph();
		for ( int i = 0; i < 6; i++ )
			g.spot( 0, i * 20 );
		final ToolContext ctx = g.context();
		ctx.setSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_PER_FRAME, 4 );

		int removed = 0;
		for ( final Spot s : new java.util.ArrayList<>( spotsAt( g, 0 ) ) )
			if ( GraphEdits.removeSpot( ctx, g.graph(), s, Reason.NOT_A_CELL ) )
				removed++;

		// Five, not six: the floor did not bound this, but the last-spot guard still holds one back,
		// and that the two are separately observable is the point. A statistical deletion on the same
		// fixture stops at two.
		assertEquals( "a measured deletion was bounded by the floor", 5, removed );
		assertEquals( 1, g.spotCount() );
	}

	/** Resolving a duplicate leaves the cell behind, so the floor has nothing to say about it. */
	@Test
	public void duplicateResolutionIgnoresTheFloor()
	{
		final ReplayGraph g = new ReplayGraph();
		for ( int i = 0; i < 6; i++ )
			g.spot( 0, i * 20 );
		final ToolContext ctx = g.context();
		ctx.setSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_PER_FRAME, 4 );

		int removed = 0;
		for ( final Spot s : new java.util.ArrayList<>( spotsAt( g, 0 ) ) )
			if ( GraphEdits.removeSpot( ctx, g.graph(), s, Reason.DUPLICATE ) )
				removed++;

		// Five for the same reason as above: the floor is irrelevant to a duplicate, the last-spot
		// guard is not.
		assertEquals( 5, removed );
	}

	/** What the floor kept is reported separately from what protection kept. */
	@Test
	public void floorRefusalsAreCountedApartFromProtection()
	{
		final ReplayGraph g = new ReplayGraph();
		for ( int i = 0; i < 6; i++ )
			g.spot( 0, i * 20 );
		final ToolContext ctx = g.context();
		ctx.setSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_PER_FRAME, 4 );

		for ( final Spot s : new java.util.ArrayList<>( spotsAt( g, 0 ) ) )
			GraphEdits.removeSpot( ctx, g.graph(), s, Reason.PROBABLY_NOT_A_CELL );

		final int floor = ExpectedCellCount.toleratedFloor( 4 );
		final int[] c = GraphEdits.tally( ctx ).get( Reason.PROBABLY_NOT_A_CELL );
		assertEquals( "removed", 6 - floor, c[ 0 ] );
		assertEquals( "kept as protected", 0, c[ 4 ] );
		assertEquals( "kept by the floor", floor, c[ 5 ] );
	}

	private static java.util.List< Spot > spotsAt( final ReplayGraph g, final int t )
	{
		final java.util.List< Spot > out = new java.util.ArrayList<>();
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == t )
				out.add( g.graph().vertices().getRefPool().createRef().refTo( s ) );
		return out;
	}

	// ------------------------------------------------------------------ never empty a frame

	/**
	 * A judgement about one detection may not conclude that a frame holds no cells.
	 *
	 * <p>This is the shape of the failure that produced it: a single-anchor intensity ceiling landed
	 * above every detection in a phase-contrast window and removed all 931 of them, each found by the
	 * predictor moments earlier. The pass was not measuring the specimen any more, it was measuring its
	 * own threshold, and nothing in the pipeline disagreed.</p>
	 */
	@Test
	public void aJudgementMayNotEmptyAFrame()
	{
		for ( final Reason why : new Reason[] { Reason.PROBABLY_NOT_A_CELL, Reason.NOT_A_CELL,
				Reason.DUPLICATE, Reason.TOPOLOGY } )
		{
			final ReplayGraph g = new ReplayGraph();
			g.spot( 0, 0 );
			g.spot( 0, 40 );
			g.spot( 0, 80 );
			final ToolContext ctx = g.context();

			int removed = 0;
			for ( final Spot s : new java.util.ArrayList<>( spotsAt( g, 0 ) ) )
				if ( GraphEdits.removeSpot( ctx, g.graph(), s, why ) )
					removed++;

			assertEquals( "emptied the frame for " + why, 2, removed );
			assertEquals( "no spot left standing for " + why, 1, g.spotCount() );
		}
	}

	/** Callers that mean to clear a frame still may: those three are decisions, not measurements. */
	@Test
	public void bulkTrainingAndUserMayEmptyAFrame()
	{
		for ( final Reason why : new Reason[] { Reason.BULK, Reason.TRAINING, Reason.USER } )
		{
			final ReplayGraph g = new ReplayGraph();
			g.spot( 0, 0 );
			g.spot( 0, 40 );
			final ToolContext ctx = g.context();

			for ( final Spot s : new java.util.ArrayList<>( spotsAt( g, 0 ) ) )
				GraphEdits.removeSpot( ctx, g.graph(), s, why );

			assertEquals( "refused a deliberate clear for " + why, 0, g.spotCount() );
		}
	}

	/** Emptying one frame is refused without stopping the pass from working on the next. */
	@Test
	public void theGuardIsPerTimepoint()
	{
		final ReplayGraph g = new ReplayGraph();
		g.spot( 0, 0 );
		g.spot( 1, 0 );
		g.spot( 1, 40 );
		final ToolContext ctx = g.context();

		for ( final Spot s : new java.util.ArrayList<>( spotsAt( g, 0 ) ) )
			GraphEdits.removeSpot( ctx, g.graph(), s, Reason.NOT_A_CELL );
		for ( final Spot s : new java.util.ArrayList<>( spotsAt( g, 1 ) ) )
			GraphEdits.removeSpot( ctx, g.graph(), s, Reason.NOT_A_CELL );

		assertEquals( "t=0 should keep its only spot", 1, spotsAt( g, 0 ).size() );
		assertEquals( "t=1 should keep one of two", 1, spotsAt( g, 1 ).size() );
	}

	/** What the guard kept is reported, so a run says the pass misfired rather than hiding it. */
	@Test
	public void lastSpotRefusalsAreCounted()
	{
		final ReplayGraph g = new ReplayGraph();
		g.spot( 0, 0 );
		final ToolContext ctx = g.context();

		GraphEdits.removeSpot( ctx, g.graph(), spotsAt( g, 0 ).get( 0 ), Reason.NOT_A_CELL );

		assertEquals( 1, GraphEdits.tally( ctx ).get( Reason.NOT_A_CELL )[ 6 ] );
	}
}
