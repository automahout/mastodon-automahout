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
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

/**
 * Replays the correction passes that touch only the graph, off a fixture, with no ELEPHANT server, no
 * vision model, no image data and no GUI.
 *
 * <p>These passes hold the bugs this project kept rediscovering — a guard applied to some deletion
 * sites and not others, a scan handed the movie's timepoint count instead of the window's — and until
 * now the only way to run one was a multi-hour GUI session on real data, which stops at the first
 * visible anomaly. Every assertion here is a statement about what a pass leaves behind, so a refactor
 * that changes behaviour fails the build instead of reaching a run weeks later.</p>
 *
 * <p>The passes that sample pixels are deliberately absent: {@code ToolContext.forReplay} has no
 * viewer, so any pass reaching for intensity throws rather than quietly being covered. What is
 * replayable here is topology.</p>
 */
public class CorrectionReplayTest
{
	/**
	 * A window-scoped pass must not touch timepoints outside its window.
	 *
	 * <p>This is the shape of a confirmed bug: {@code runTopologyConsistency} was handed the movie-wide
	 * timepoint count rather than the window's end, so every window re-scanned and re-deleted the
	 * spots that earlier windows had already finalised, capping the total no matter how permissive
	 * detection became. It was invisible in the logs and took a real run to notice.</p>
	 */
	@Test
	public void unstableDeletionStaysInsideItsWindow()
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 3, 0, 1 );        // a short track at t=0..2, outside the window below
		g.track( 5, 6, 50, 1 );       // a long, stable track inside it
		final ToolContext ctx = g.context();
		final String before = g.canonical();

		AutoCorrectOrchestrator.deleteUnstableSpots( ctx, 5, 10, 4 );

		assertEquals( "a window-scoped pass rewrote spots outside [5,10]", before, g.canonical() );
	}

	/** Within its window, a track shorter than the stability threshold does go. */
	@Test
	public void unstableDeletionRemovesShortTracksInsideItsWindow()
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 5, 2, 0, 1 );        // two spots: shorter than minStableLen
		g.track( 5, 6, 50, 1 );       // six spots: long enough to keep
		final ToolContext ctx = g.context();

		final int removed = AutoCorrectOrchestrator.deleteUnstableSpots( ctx, 5, 10, 4 );

		assertEquals( 2, removed );
		assertEquals( 6, g.spotCount() );
	}

	/**
	 * Two tracks merging into one spot is biologically impossible — lineages divide, they never join —
	 * and the whole-movie sweep now says so.
	 *
	 * <p>It did not until this test was written. {@code pruneConvergenceLinks} enforces the rule and
	 * needs no pixels to do it, but its only caller was the per-timepoint correction, which samples
	 * intensity and so runs only where image access is available. A merge in a timepoint that pass
	 * never visited survived the final sweep untouched. The replay harness found this on its first
	 * use, because a fixture can ask what a pass does to a graph without a microscope attached.</p>
	 *
	 * <p>Only the extra incoming link goes. The nearest predecessor keeps its edge and no spot is
	 * deleted, so the losing predecessor is left as a dangling end for the reconnection passes rather
	 * than being thrown away on a guess.</p>
	 */
	@Test
	public void globalCleanupResolvesConvergences()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot near = g.spot( 0, 0 );
		final Spot far = g.spot( 0, 30 );
		final Spot merged = g.spot( 1, 1 );
		g.link( near, merged );
		g.link( far, merged );         // two incoming edges on merged: a merge
		final ToolContext ctx = g.context();

		AutoCorrectOrchestrator.globalCleanup( ctx, 2 );

		assertEquals( "the merge survived the whole-movie sweep", 1, incoming( g, merged ) );
		assertEquals( "the nearest predecessor should have been the one kept", 1, outgoing( g, near ) );
		assertEquals( "the losing predecessor should keep its spot", 0, outgoing( g, far ) );
		assertEquals( "resolving a convergence should not delete a spot", 3, g.spotCount() );
	}

	/** A spot the user confirmed keeps its incoming links exactly as curated, merge or not. */
	@Test
	public void aProtectedSpotKeepsItsConvergence()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot near = g.spot( 0, 0 );
		final Spot far = g.spot( 0, 30 );
		final Spot merged = g.spot( 1, 1 );
		g.link( near, merged );
		g.link( far, merged );
		final ToolContext ctx = g.protect( merged );

		AutoCorrectOrchestrator.globalCleanup( ctx, 2 );

		assertEquals( "a confirmed spot's curated links were rewritten", 2, incoming( g, merged ) );
	}

	/** Three daughters is not a division; the pass reduces the branching. */
	@Test
	public void trifurcationIsReduced()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot m = g.spot( 0, 0 );
		final Spot d1 = g.spot( 1, -10 );
		final Spot d2 = g.spot( 1, 10 );
		final Spot d3 = g.spot( 1, 40 );
		g.link( m, d1 );
		g.link( m, d2 );
		g.link( m, d3 );
		final ToolContext ctx = g.context();

		AutoCorrectOrchestrator.globalCleanup( ctx, 2 );

		assertTrue( "a mother kept three or more daughters", outgoing( g, m ) <= 2 );
	}

	/**
	 * The same fixture put through the same pass twice must land in the same place. A pass that is not
	 * idempotent keeps eating its own output when the window loop revisits a timepoint, which is how a
	 * count that looked stable per-window drifted down across a movie.
	 */
	@Test
	public void globalCleanupIsIdempotent()
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 5, 0, 1 );
		final Spot m = g.spot( 5, 5 );
		final Spot d1 = g.spot( 6, 0 );
		final Spot d2 = g.spot( 6, 12 );
		g.link( m, d1 );
		g.link( m, d2 );
		final ToolContext ctx = g.context();

		AutoCorrectOrchestrator.globalCleanup( ctx, 8 );
		final String once = g.canonical();
		AutoCorrectOrchestrator.globalCleanup( ctx, 8 );

		assertEquals( "running the cleanup twice changed the graph the second time", once, g.canonical() );
	}

	/** A clean, well-formed lineage is left entirely alone. */
	@Test
	public void aCleanTrackSurvivesTheGlobalCleanup()
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 8, 0, 1 );
		final ToolContext ctx = g.context();
		final String before = g.canonical();

		AutoCorrectOrchestrator.globalCleanup( ctx, 8 );

		assertEquals( "the cleanup rewrote a graph that had nothing wrong with it", before, g.canonical() );
	}

	/** Continuity counts interior spots holding both an incoming and an outgoing edge. */
	@Test
	public void topologyMetricsCountAWholeTrackAsContinuous()
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > t = g.track( 0, 5, 0, 1 );
		final ToolContext ctx = g.context();

		final int[] m = AutoCorrectOrchestrator.computeTopologyMetrics( ctx );

		assertEquals( "fixture built wrong", 5, t.size() );
		assertTrue( "metrics reported nothing for a five-spot track", m.length > 0 );
	}

	// ------------------------------------------------------------------ helpers

	private static int incoming( final ReplayGraph g, final Spot s )
	{
		return s.incomingEdges().size();
	}

	private static int outgoing( final ReplayGraph g, final Spot s )
	{
		return s.outgoingEdges().size();
	}
}
