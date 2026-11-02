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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator.DivisionAnswer;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator.DivisionQuestion;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

/**
 * {@link AutoCorrectOrchestrator#dissolveStayingDivisions}: a division whose daughters do not move apart
 * from the mother loses the branch that jumped; one whose daughters do is left alone.
 */
public class StayingDivisionTest
{
	/** Records what it was asked and always gives {@code answer}. */
	private static final class Asked implements DivisionQuestion
	{
		final List< Integer > at = new ArrayList<>();

		final DivisionAnswer answer;

		Asked( final DivisionAnswer answer )
		{
			this.answer = answer;
		}

		@Override
		public DivisionAnswer ask( final int t, final double[] m, final double r, final double[] stays,
				final double[] jumps, final String why )
		{
			at.add( t );
			return answer;
		}
	}

	/** Radius-5 spots: a real division at x=0 (daughters at -4 and +4), and at x=100 a false one. */
	private static ReplayGraph realAndStaying()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot real = g.spot( 1, 0 );
		g.link( g.spot( 0, 0 ), real );
		g.link( real, g.spot( 2, -4 ) );
		g.link( real, g.spot( 2, 4 ) );
		// The mother's own cell carried on 1 um along (0.2 radii), and a neighbour 12 um off taken in.
		final Spot stays = g.spot( 1, 100 );
		g.link( g.spot( 0, 100 ), stays );
		g.link( stays, g.spot( 2, 101 ) );
		g.link( stays, g.spot( 2, 112 ) );
		return g;
	}

	private static int outgoing( final ReplayGraph g, final int t, final double x )
	{
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == t && Math.abs( s.getDoublePosition( 0 ) - x ) < 1e-9 )
				return s.outgoingEdges().size();
		throw new AssertionError( "no spot at t=" + t + " x=" + x );
	}

	@Test
	public void geometryReadsTheNearerJumpAndTheAngle()
	{
		final double[] g = AutoCorrectOrchestrator.divisionGeometry( new double[] { 0, 0, 0 },
				new double[] { -4, 0, 0 }, new double[] { 0, 6, 0 }, 5 );
		assertEquals( 0.8, g[ 0 ], 1e-9 );
		assertEquals( 90, g[ 1 ], 1e-9 );
		assertEquals( 0, AutoCorrectOrchestrator.divisionGeometry( new double[] { 0, 0, 0 }, new double[] { 0, 0, 0 },
				new double[] { 5, 0, 0 }, 5 )[ 1 ], 1e-9 );
	}

	@Test
	public void withNobodyToAskTheJumpIsCutAndTheRealDivisionKept()
	{
		final ReplayGraph g = realAndStaying();
		assertEquals( 1, AutoCorrectOrchestrator.dissolveStayingDivisions( g.context(), 0, 3, null ) );
		assertEquals( 2, outgoing( g, 1, 0 ) );
		assertEquals( 1, outgoing( g, 1, 100 ) );
		// ...and it is the jump that went, not the cell carried on.
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 2 && s.getDoublePosition( 0 ) == 112 )
				assertTrue( s.incomingEdges().isEmpty() );
	}

	@Test
	public void bothDaughtersTheSameWayIsCutToo()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot m = g.spot( 1, 0 );
		g.link( m, g.spot( 2, 3 ) );
		g.link( m, g.spot( 2, 8 ) );
		assertEquals( 1, AutoCorrectOrchestrator.dissolveStayingDivisions( g.context(), 0, 3, null ) );
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 2 )
				assertEquals( s.getDoublePosition( 0 ) == 3 ? 1 : 0, s.incomingEdges().size() );
	}

	/**
	 * On a stack, the branch that stays is the one nearer the mother in 3-D: here the cell carried on 3 um in the
	 * plane, and a cell 10 um below sits almost under the mother in the plane.
	 */
	@Test
	public void theBranchThatStaysIsNearestInThreeD()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot m = g.spot( 1, 0, 0, 0, 5 );
		final Spot carried = g.spot( 2, 3, 0, 0, 5 ), below = g.spot( 2, 1, 0, 10, 5 );
		g.link( m, carried );
		g.link( m, below );
		assertEquals( 1, AutoCorrectOrchestrator.dissolveStayingDivisions( g.context(), 0, 3, null ) );
		assertEquals( 1, carried.incomingEdges().size() );
		assertEquals( 0, below.incomingEdges().size() );
	}

	/** A cell 30 um below the flagged mother, at the same place in the plane, is not taken for it. */
	@Test
	public void aCellAboveTheMotherInZIsNotTakenForIt()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot other = g.spot( 1, 0, 0, 30, 5 );
		g.link( other, g.spot( 2, 0, 0, 30, 5 ) );
		final Spot m = g.spot( 1, 0, 0, 0, 5 );
		g.link( m, g.spot( 2, 1, 0, 0, 5 ) );
		g.link( m, g.spot( 2, 12, 0, 0, 5 ) );
		assertEquals( 1, AutoCorrectOrchestrator.dissolveStayingDivisions( g.context(), 0, 3, null ) );
		assertEquals( 1, m.outgoingEdges().size() );
		assertEquals( 1, other.outgoingEdges().size() );
	}

	@Test
	public void theUsersRealDivisionIsKeptAndNotAskedAgain()
	{
		final ReplayGraph g = realAndStaying();
		final ToolContext ctx = g.context();
		final Asked asked = new Asked( DivisionAnswer.DIVIDES );
		assertEquals( 0, AutoCorrectOrchestrator.dissolveStayingDivisions( ctx, 0, 3, asked ) );
		assertEquals( 0, AutoCorrectOrchestrator.dissolveStayingDivisions( ctx, 0, 3, asked ) );
		assertEquals( 1, asked.at.size() );
		assertEquals( 2, outgoing( g, 1, 100 ) );
	}

	@Test
	public void aCutIsNotAskedAgainWhenTheSameLinkComesBack()
	{
		final ReplayGraph g = realAndStaying();
		final ToolContext ctx = g.context();
		final Asked asked = new Asked( DivisionAnswer.ONE_CELL );
		assertEquals( 1, AutoCorrectOrchestrator.dissolveStayingDivisions( ctx, 0, 3, asked ) );
		Spot mother = null, jump = null;
		for ( final Spot s : g.graph().vertices() )
		{
			if ( s.getTimepoint() == 1 && s.getDoublePosition( 0 ) == 100 )
				mother = g.graph().vertexRef().refTo( s );
			if ( s.getTimepoint() == 2 && s.getDoublePosition( 0 ) == 112 )
				jump = g.graph().vertexRef().refTo( s );
		}
		g.link( mother, jump ); // a later pass links it in again
		assertEquals( 1, AutoCorrectOrchestrator.dissolveStayingDivisions( ctx, 0, 3, asked ) );
		assertEquals( 1, asked.at.size() );
		assertEquals( 1, outgoing( g, 1, 100 ) );
	}

	@Test
	public void noAnswerCutsAndStopsAsking()
	{
		final ReplayGraph g = realAndStaying();
		final Spot m2 = g.spot( 1, 200 );
		g.link( m2, g.spot( 2, 200.5 ) );
		g.link( m2, g.spot( 2, 215 ) );
		final Asked asked = new Asked( DivisionAnswer.NONE );
		assertEquals( 2, AutoCorrectOrchestrator.dissolveStayingDivisions( g.context(), 0, 3, asked ) );
		assertEquals( 1, asked.at.size() );
	}

	@Test
	public void aConfirmedDaughterIsNeverCut()
	{
		final ReplayGraph g = realAndStaying();
		Spot jump = null;
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 2 && s.getDoublePosition( 0 ) == 112 )
				jump = g.graph().vertexRef().refTo( s );
		final ToolContext ctx = g.protect( jump );
		assertEquals( 0, AutoCorrectOrchestrator.dissolveStayingDivisions( ctx, 0, 3, null ) );
		assertEquals( 2, outgoing( g, 1, 100 ) );
	}

	@Test
	public void timepointsBeforeTheScanAreLeftAlone()
	{
		final ReplayGraph g = realAndStaying();
		assertEquals( 0, AutoCorrectOrchestrator.dissolveStayingDivisions( g.context(), 2, 3, null ) );
		assertEquals( 2, outgoing( g, 1, 100 ) );
	}
}
