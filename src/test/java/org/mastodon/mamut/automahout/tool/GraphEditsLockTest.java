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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * The chokepoint's own guards: every edit runs under the write lock, a link that does not go forward
 * in time is refused, and an edit under only the read lock fails loudly instead of waiting forever.
 */
public class GraphEditsLockTest
{
	@Test
	public void aLinkFromASpotToItselfIsRefused()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot s = g.spot( 3, 0 );
		final ModelGraph graph = g.graph();
		assertNull( GraphEdits.addLink( g.context(), graph, s, s, graph.edgeRef(), GraphEdits.Reason.TOPOLOGY ) );
		assertNull( GraphEdits.addLink( g.context(), graph, s, g.spot( 2, 0 ), graph.edgeRef(), GraphEdits.Reason.TOPOLOGY ) );
		assertEquals( 0, g.linkCount() );
		assertNull( GraphIntegrity.check( graph ) );
	}

	@Test
	public void anEditWithoutTheLockTakesItAndGivesItBack()
	{
		final ReplayGraph g = new ReplayGraph();
		final ModelGraph graph = g.graph();
		assertNotNull( GraphEdits.addLink( g.context(), graph, g.spot( 0, 0 ), g.spot( 1, 0 ), graph.edgeRef(),
				GraphEdits.Reason.TOPOLOGY ) );
		assertEquals( 1, g.linkCount() );
		assertFalse( graph.getLock().isWriteLockedByCurrentThread() );
		assertEquals( 0, graph.getLock().getReadHoldCount() );
	}

	@Test( timeout = 10_000 )
	public void anEditUnderOnlyTheReadLockFailsInsteadOfHanging()
	{
		final ReplayGraph g = new ReplayGraph();
		final ModelGraph graph = g.graph();
		final Spot a = g.spot( 0, 0 ), b = g.spot( 1, 0 );
		graph.getLock().readLock().lock();
		try
		{
			GraphEdits.addLink( g.context(), graph, a, b, graph.edgeRef(), GraphEdits.Reason.TOPOLOGY );
			fail( "an edit under only the read lock must not go through" );
		}
		catch ( final IllegalStateException expected )
		{
			// thrown, not deadlocked
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		assertEquals( 0, g.linkCount() );
	}
}
