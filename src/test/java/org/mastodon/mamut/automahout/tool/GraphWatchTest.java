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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * {@link GraphWatch}: the run holds a ref it collected earlier; meanwhile the user deletes that spot in
 * the viewer and adds another, which takes the freed slot. The run's edit through the old ref must not
 * land on the user's new spot.
 */
public class GraphWatchTest
{
	/** What an edit in the viewer does: straight on the graph, under the write lock, not through GraphEdits. */
	private static void viewer( final ModelGraph graph, final Runnable edit )
	{
		graph.getLock().writeLock().lock();
		try
		{
			edit.run();
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
	}

	private static boolean hasSpotAt( final ModelGraph graph, final double x )
	{
		for ( final Spot s : graph.vertices() )
			if ( s.getDoublePosition( 0 ) == x )
				return true;
		return false;
	}

	@Test
	public void aDeleteThroughARefWhoseSpotTheUserReplacedIsRefused()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot collected = g.spot( 1, 0 );
		g.spot( 1, 50 );
		final ToolContext ctx = g.context();
		final ModelGraph graph = g.graph();
		final int slot = collected.getInternalPoolIndex();
		final Spot[] added = new Spot[ 1 ];
		viewer( graph, () -> {
			final Spot victim = graph.vertexRef().refTo( collected );
			graph.remove( victim );
			added[ 0 ] = graph.addVertex( graph.vertexRef() ).init( 1, new double[] { 300, 0, 0 }, 5 );
		} );
		// The case that matters: the old ref now names the user's new spot.
		assertEquals( slot, added[ 0 ].getInternalPoolIndex() );
		assertEquals( 300, collected.getDoublePosition( 0 ), 0 );
		assertFalse( GraphEdits.removeSpot( ctx, graph, collected, GraphEdits.Reason.TOPOLOGY ) );
		assertTrue( "the user's new spot survives", hasSpotAt( graph, 300 ) );
		assertNull( GraphIntegrity.check( graph ) );
	}

	@Test
	public void aLinkToASpotTheUserJustAddedIsNotMadeByTheRun()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0 );
		final ToolContext ctx = g.context();
		final ModelGraph graph = g.graph();
		final Spot[] added = new Spot[ 1 ];
		viewer( graph, () -> added[ 0 ] = graph.addVertex( graph.vertexRef() ).init( 1, new double[] { 0, 0, 0 }, 5 ) );
		assertNull( GraphEdits.addLink( ctx, graph, a, added[ 0 ], graph.edgeRef(), GraphEdits.Reason.TOPOLOGY ) );
		assertEquals( 0, g.linkCount() );
	}

	@Test
	public void aLinkTheUserDeletedIsNotRemovedAgain()
	{
		final ReplayGraph g = new ReplayGraph();
		final Link collected = g.link( g.spot( 0, 0 ), g.spot( 1, 0 ) );
		final Spot c = g.spot( 0, 50 ), d = g.spot( 1, 50 );
		final ToolContext ctx = g.context();
		final ModelGraph graph = g.graph();
		viewer( graph, () -> {
			graph.remove( graph.edgeRef().refTo( collected ) );
			graph.addEdge( c, d, graph.edgeRef() ).init(); // the user's own link takes the slot
		} );
		assertFalse( GraphEdits.removeLink( ctx, graph, collected, GraphEdits.Reason.TOPOLOGY ) );
		assertEquals( "the user's link stays", 1, g.linkCount() );
		assertNull( GraphIntegrity.check( graph ) );
	}

	@Test
	public void theRunsOwnSpotInAFreedSlotIsItsToEdit()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot gone = g.spot( 1, 0 );
		final ToolContext ctx = g.context();
		final ModelGraph graph = g.graph();
		viewer( graph, () -> graph.remove( graph.vertexRef().refTo( gone ) ) );
		final Spot mine = GraphEdits.addSpot( ctx, graph, graph.vertexRef(), 1, new double[] { 7, 0, 0 }, 5,
				GraphEdits.Reason.TOPOLOGY );
		assertFalse( GraphWatch.dirty( graph, mine ) );
	}

	@Test
	public void aStepBoundaryForgetsWhatTheViewerChanged()
	{
		final ReplayGraph g = new ReplayGraph();
		final ToolContext ctx = g.context();
		final ModelGraph graph = g.graph();
		final Spot[] added = new Spot[ 1 ];
		viewer( graph, () -> added[ 0 ] = graph.addVertex( graph.vertexRef() ).init( 1, new double[] { 0, 0, 0 }, 5 ) );
		assertTrue( GraphWatch.dirty( graph, added[ 0 ] ) );
		GraphWatch.mark( ctx, "test" );
		assertFalse( GraphWatch.dirty( graph, added[ 0 ] ) );
	}
}
