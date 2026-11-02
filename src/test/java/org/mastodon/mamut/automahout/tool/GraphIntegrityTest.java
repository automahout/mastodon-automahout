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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.model.Spot;

public class GraphIntegrityTest
{
	@Test
	public void aSoundGraphPasses()
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > a = g.track( 0, 5, 0, 1 );
		final Spot daughter = g.spot( 5, 9 );
		g.link( a.get( 4 ), daughter );
		g.link( a.get( 4 ), g.spot( 5, -9 ) );
		g.graph().remove( a.get( 2 ) );
		assertNull( GraphIntegrity.check( g.graph() ) );
		GraphIntegrity.require( g.context(), g.graph(), "test" );
	}

	@Test
	public void aLinkFromASpotToItselfIsDamage()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot s = g.spot( 3, 0 );
		g.link( g.spot( 2, 0 ), s );
		g.link( s, s );
		final String fault = GraphIntegrity.check( g.graph() );
		assertTrue( fault, fault != null && fault.contains( "itself" ) );
	}

	@Test
	public void aLinkBackInTimeIsDamage()
	{
		final ReplayGraph g = new ReplayGraph();
		g.link( g.spot( 3, 0 ), g.spot( 2, 0 ) );
		assertTrue( GraphIntegrity.check( g.graph() ) != null );
	}

	@Test
	public void theStopIsNotAnOrdinaryToolError()
	{
		// LlmOrchestrator rethrows it rather than handing it to the agent as a tool error.
		assertTrue( RuntimeException.class.isAssignableFrom( GraphIntegrity.DamagedGraphException.class ) );
		try
		{
			throw new GraphIntegrity.DamagedGraphException( "x" );
		}
		catch ( final GraphIntegrity.DamagedGraphException e )
		{
			return;
		}
		catch ( final RuntimeException e )
		{
			fail();
		}
	}
}
