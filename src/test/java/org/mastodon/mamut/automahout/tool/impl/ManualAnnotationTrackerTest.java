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
package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * The user edits throughout the annotation wait: a confirmed spot they delete stops counting, and the
 * spot that takes its slot is not confirmed by inheritance.
 */
public class ManualAnnotationTrackerTest
{
	@Test
	public void aDeletedConfirmationIsForgottenAndNotInherited()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0 ), b = g.spot( 0, 30 );
		final ModelGraph graph = g.graph();
		try (final ElephantTool.ManualAnnotationTracker tracker =
				new ElephantTool.ManualAnnotationTracker( g.context(), new int[] { 0 }, 2 ))
		{
			tracker.confirm( a );
			tracker.confirm( b );
			assertEquals( 2, tracker.getAsInt() );
			final int slot = a.getInternalPoolIndex();
			graph.getLock().writeLock().lock();
			final Spot added;
			try
			{
				graph.remove( graph.vertexRef().refTo( a ) );
				added = graph.addVertex( graph.vertexRef() ).init( 0, new double[] { 99, 0, 0 }, 5 );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			assertEquals( slot, added.getInternalPoolIndex() ); // the new spot took the slot
			assertEquals( 1, tracker.getAsInt() );
			assertFalse( tracker.confirmedIds().contains( slot ) );
		}
	}
}
