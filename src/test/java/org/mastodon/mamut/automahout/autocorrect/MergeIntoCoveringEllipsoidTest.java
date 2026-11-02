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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.model.Spot;

/** Tests for the one place a duplicate is resolved: {@code mergeIntoCoveringEllipsoid}. */
public class MergeIntoCoveringEllipsoidTest
{
	private static final double R = 9.0;

	private static Set< Integer > groupOf( final Spot... spots )
	{
		final Set< Integer > g = new HashSet<>();
		for ( final Spot s : spots )
			g.add( s.getInternalPoolIndex() );
		return g;
	}

	@Test
	public void aPairBecomesOneSpotCoveringBoth()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, R );
		final Spot b = g.spot( 0, 1.5 * R, 0, 0, R );
		final int removed = AutoCorrectOrchestrator.mergeIntoCoveringEllipsoid(
				g.context(), g.graph(), 0, groupOf( a, b ) );
		assertEquals( 1, removed, "two spots must become one" );
		assertEquals( 1, g.spotCount() );

		// The survivor is the pair, not either member: centred between them and long enough to reach
		// both far edges.
		final Spot merged = g.graph().vertices().iterator().next();
		final double[] pos = new double[ 3 ];
		merged.localize( pos );
		assertEquals( 0.75 * R, pos[ 0 ], 1e-6 );
		final double[][] cov = new double[ 3 ][ 3 ];
		merged.getCovariance( cov );
		assertEquals( 0.75 * R + R, Math.sqrt( cov[ 0 ][ 0 ] ), 1e-6 );
		assertEquals( R, Math.sqrt( cov[ 1 ][ 1 ] ), 1e-6 );
	}

	@Test
	public void theLastSpotGuardDoesNotTurnAMergeIntoADuplicate()
	{
		// The ordering that matters. Deleting the members first walks the frame down to one spot, the
		// second removal is refused as the frame's last, and the merge then adds a THIRD spot beside
		// the survivor it could not remove — the pass that exists to remove duplicates making one.
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 3, 0, 0, 0, R );
		final Spot b = g.spot( 3, 1.2 * R, 0, 0, R );
		AutoCorrectOrchestrator.mergeIntoCoveringEllipsoid( g.context(), g.graph(), 3, groupOf( a, b ) );
		assertEquals( 1, g.spotCount(), "a frame holding only the pair must end up holding one spot" );
	}

	@Test
	public void linksOutsideTheGroupSurviveOnTheMergedSpot()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot before = g.spot( 0, 0 );
		final Spot a = g.spot( 1, 0, 0, 0, R );
		final Spot b = g.spot( 1, 1.2 * R, 0, 0, R );
		final Spot after = g.spot( 2, 0 );
		g.link( before, a );
		g.link( b, after );
		AutoCorrectOrchestrator.mergeIntoCoveringEllipsoid( g.context(), g.graph(), 1, groupOf( a, b ) );
		assertEquals( 3, g.spotCount() );
		assertEquals( 2, g.linkCount(), "the track must still run through the merged spot" );
	}

	@Test
	public void aMergeThatWouldBalloonIsRefused()
	{
		// Bounded by construction for a real pair, but the cap is what stops a caller that proposes a
		// group the valley test never saw.
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, 1.0 );
		final Spot b = g.spot( 0, 20.0, 0, 0, 1.0 );
		assertEquals( 0, AutoCorrectOrchestrator.mergeIntoCoveringEllipsoid(
				g.context(), g.graph(), 0, groupOf( a, b ) ) );
		assertEquals( 2, g.spotCount() );
	}

	@Test
	public void aProtectedMemberIsNeverMergedAway()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, R );
		final Spot b = g.spot( 0, 1.2 * R, 0, 0, R );
		final Spot c = g.spot( 0, 2.0 * R, 0, 0, R );
		assertTrue( AutoCorrectOrchestrator.mergeIntoCoveringEllipsoid(
				g.protect( a ), g.graph(), 0, groupOf( a, b, c ) ) >= 0 );
		// a survives as itself; b and c may merge with each other, but nothing may delete a.
		boolean aStillThere = false;
		for ( final Spot s : g.graph().vertices() )
			if ( s.getInternalPoolIndex() == a.getInternalPoolIndex() )
				aStillThere = true;
		assertTrue( aStillThere, "a confirmed cell must not be merged away" );
	}
}
