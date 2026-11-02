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
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

/**
 * The pre-flight switch for the "which is right?" questions: off, nothing about merges or divisions is
 * asked, and each is decided as "Stop asking" decides it.
 */
public class QuestionSwitchTest
{
	@Test
	public void offNoMergeIsAskedAboutAndItIsSplitAsBefore()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a2 = g.track( 0, 3, 0, 0 ).get( 2 );
		g.track( 0, 3, 20, 0 );
		final Spot m3 = g.spot( 3, 10, 0, 0, 15 );
		g.link( a2, m3 );
		final ToolContext ctx = g.context();
		AutoCorrectOrchestrator.askAboutMergesAndDivisions( ctx, false );
		final int[] asked = { 0 };
		final AutoCorrectOrchestrator.PieceFitter onTheSeeds = ( t, c, r, cov, seeds, fr ) -> {
			final double[][] cv = { { 25, 0, 0 }, { 0, 25, 0 }, { 0, 0, 25 } };
			return new AutoCorrectOrchestrator.Pieces( new double[][] { seeds[ 0 ].clone(), seeds[ 1 ].clone() },
					new double[][][] { cv, cv } );
		};
		final int[] repaired = AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 4, onTheSeeds,
				( t, c, r, cov, p, why ) -> {
					asked[ 0 ]++;
					return AutoCorrectOrchestrator.SplitAnswer.ONE;
				} );
		assertEquals( 0, asked[ 0 ] );
		assertEquals( "split on its own, as 'stop asking' does", 1, repaired[ 0 ] );
	}

	@Test
	public void offNoDivisionIsAskedAboutAndTheJumpIsCut()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot stays = g.spot( 1, 100 );
		g.link( g.spot( 0, 100 ), stays );
		g.link( stays, g.spot( 2, 101 ) );
		g.link( stays, g.spot( 2, 112 ) );
		final ToolContext ctx = g.context();
		AutoCorrectOrchestrator.askAboutMergesAndDivisions( ctx, false );
		final int[] asked = { 0 };
		assertEquals( 1, AutoCorrectOrchestrator.dissolveStayingDivisions( ctx, 0, 3, ( t, m, r, s, j, why ) -> {
			asked[ 0 ]++;
			return AutoCorrectOrchestrator.DivisionAnswer.DIVIDES;
		} ) );
		assertEquals( 0, asked[ 0 ] );
	}

	@Test
	public void onTheQuestionsAreAsked()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot stays = g.spot( 1, 100 );
		g.link( stays, g.spot( 2, 101 ) );
		g.link( stays, g.spot( 2, 112 ) );
		final ToolContext ctx = g.context();
		AutoCorrectOrchestrator.askAboutMergesAndDivisions( ctx, true );
		final int[] asked = { 0 };
		AutoCorrectOrchestrator.dissolveStayingDivisions( ctx, 0, 3, ( t, m, r, s, j, why ) -> {
			asked[ 0 ]++;
			return AutoCorrectOrchestrator.DivisionAnswer.DIVIDES;
		} );
		assertEquals( 1, asked[ 0 ] );
	}
}
