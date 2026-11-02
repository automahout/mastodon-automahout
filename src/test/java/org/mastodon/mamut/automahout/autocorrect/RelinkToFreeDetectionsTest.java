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

import org.junit.Test;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Spot;

/**
 * {@link AutoCorrectOrchestrator#relinkToFreeDetections}: a link that went to a neighbour's detection
 * while the cell's own sat beside it with no predecessor is moved to the cell's own.
 */
public class RelinkToFreeDetectionsTest
{
	/** "t@x -> t@x" lines of every link, sorted. */
	private static String links( final ReplayGraph g )
	{
		final java.util.List< String > out = new java.util.ArrayList<>();
		final Spot a = g.graph().vertexRef(), b = g.graph().vertexRef();
		for ( final Link e : g.graph().edges() )
			out.add( String.format( "%d@%.1f,%.1f -> %d@%.1f,%.1f", e.getSource( a ).getTimepoint(),
					a.getDoublePosition( 0 ), a.getDoublePosition( 1 ), e.getTarget( b ).getTimepoint(),
					b.getDoublePosition( 0 ), b.getDoublePosition( 1 ) ) );
		out.sort( null );
		return String.join( "\n", out );
	}

	/**
	 * The user's example (run 235510, t=13->14), radius 10: sisters at (143.4,325.8) and (158.5,316.5).
	 * At t=14 the detector found only the left one, at (153.2,324.3), and gave it to the right sister
	 * (9.4 um against 9.9); the right sister's detection (168.3,315.3) was added afterwards with no
	 * predecessor, and the left sister's track ended.
	 */
	@Test
	public void theUsersSwappedSistersAreSwappedBack()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot left13 = g.spot( 13, 143.4, 325.8, 0, 10 ), right13 = g.spot( 13, 158.5, 316.5, 0, 10 );
		final Spot left14 = g.spot( 14, 153.2, 324.3, 0, 10 );
		g.spot( 14, 168.3, 315.3, 0, 10 );
		g.link( g.spot( 12, 146.1, 317.1, 0, 10 ), left13 );
		g.link( g.spot( 12, 157.3, 312.9, 0, 10 ), right13 );
		g.link( right13, left14 );
		assertEquals( 1, AutoCorrectOrchestrator.relinkToFreeDetections( g.context(), 0, 15 ) );
		assertEquals( String.join( "\n",
				"12@146.1,317.1 -> 13@143.4,325.8",
				"12@157.3,312.9 -> 13@158.5,316.5",
				"13@143.4,325.8 -> 14@153.2,324.3",
				"13@158.5,316.5 -> 14@168.3,315.3" ), links( g ) );
	}

	@Test
	public void aMuchNearerFreeDetectionTakesTheLink()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, 10 );
		g.link( a, g.spot( 1, 9, 0, 0, 10 ) ); // a neighbour 9 um off
		g.spot( 1, 1, 0, 0, 10 ); // its own cell, 1 um off, no predecessor
		assertEquals( 1, AutoCorrectOrchestrator.relinkToFreeDetections( g.context(), 0, 2 ) );
		assertEquals( "0@0.0,0.0 -> 1@1.0,0.0", links( g ) );
	}

	@Test
	public void aFreeDetectionNoNearerAndNoEndToTakeTheOtherIsLeft()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, 10 );
		g.link( a, g.spot( 1, 5, 0, 0, 10 ) );
		g.spot( 1, -4, 0, 0, 10 );
		assertEquals( 0, AutoCorrectOrchestrator.relinkToFreeDetections( g.context(), 0, 2 ) );
		assertEquals( "0@0.0,0.0 -> 1@5.0,0.0", links( g ) );
	}

	@Test
	public void nothingBeyondOneRadiusIsTaken()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, 10 );
		g.link( a, g.spot( 1, 40, 0, 0, 10 ) );
		g.spot( 1, 15, 0, 0, 10 );
		assertEquals( 0, AutoCorrectOrchestrator.relinkToFreeDetections( g.context(), 0, 2 ) );
	}

	@Test
	public void aProtectedTargetKeepsItsLink()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, 10 );
		final Spot b = g.spot( 1, 9, 0, 0, 10 );
		g.link( a, b );
		g.spot( 1, 1, 0, 0, 10 );
		assertEquals( 0, AutoCorrectOrchestrator.relinkToFreeDetections( g.protect( b ), 0, 2 ) );
		assertEquals( "0@0.0,0.0 -> 1@9.0,0.0", links( g ) );
	}

	/** A user's mouse embryo, t=478->479: a free detection under the cell in the plane but 40 um away in z. */
	@Test
	public void aDetectionFarAwayInZIsNotTheCellsOwn()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, 10 );
		g.link( a, g.spot( 1, 9, 0, 0, 10 ) ); // its own cell, 9 um on in the plane
		g.spot( 1, 1, 0, 40, 10 ); // another cell, under it in the plane, 40 um away in z
		assertEquals( 0, AutoCorrectOrchestrator.relinkToFreeDetections( g.context(), 0, 2 ) );
		assertEquals( "0@0.0,0.0 -> 1@9.0,0.0", links( g ) );
	}

	@Test
	public void twoCellsStackedInZAreNotSwapped()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, 10 ), e = g.spot( 0, 6, 0, 40, 10 );
		final Spot b = g.spot( 1, 6, 0, 0, 10 ), s = g.spot( 1, 1, 0, 40, 10 );
		g.link( a, b ); // right, 6 um
		g.link( g.spot( 0, 50, 50, 0, 10 ), g.spot( 1, 50, 50, 0, 10 ) ); // an unrelated track, for the median radius
		assertEquals( 0, AutoCorrectOrchestrator.relinkToFreeDetections( g.context(), 0, 2 ) );
		assertEquals( 0, e.outgoingEdges().size() );
		assertEquals( 0, s.incomingEdges().size() );
	}

	@Test
	public void aDivisionIsNotTouched()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a = g.spot( 0, 0, 0, 0, 10 );
		g.link( a, g.spot( 1, 9, 0, 0, 10 ) );
		g.link( a, g.spot( 1, -9, 0, 0, 10 ) );
		g.spot( 1, 1, 0, 0, 10 );
		assertEquals( 0, AutoCorrectOrchestrator.relinkToFreeDetections( g.context(), 0, 2 ) );
	}
}
