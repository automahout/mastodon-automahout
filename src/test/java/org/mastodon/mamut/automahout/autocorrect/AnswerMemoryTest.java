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
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.AnswerMemory.Said;
import org.mastodon.mamut.automahout.autocorrect.AnswerMemory.Topic;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Answers are found again where the same cell is re-detected a little elsewhere -- a second lap, the
 * consistency step's overlap -- and not on a neighbour.
 */
public class AnswerMemoryTest
{
	@Test
	public void anAnswerIsFoundAgainNearbyButNotOnANeighbour()
	{
		final AnswerMemory m = new AnswerMemory();
		m.remember( Topic.CELLS, 5, new double[] { 100, 100 }, Said.KEEP, 10 );
		assertEquals( Said.KEEP, m.recall( Topic.CELLS, 5, new double[] { 101, 100.7 }, 10 ) );
		assertNull( "a neighbour 5 um away is another cell", m.recall( Topic.CELLS, 5, new double[] { 105, 100 }, 10 ) );
		assertNull( "another frame", m.recall( Topic.CELLS, 6, new double[] { 100, 100 }, 10 ) );
		assertNull( "another topic", m.recall( Topic.DIVISION, 5, new double[] { 100, 100 }, 10 ) );
	}

	@Test
	public void aLaterAnswerAtTheSamePlaceReplacesTheEarlierOne()
	{
		final AnswerMemory m = new AnswerMemory();
		m.remember( Topic.DIVISION, 5, new double[] { 100, 100 }, Said.KEEP, 10 );
		m.remember( Topic.DIVISION, 5, new double[] { 100.5, 100 }, Said.CUT, 10 );
		assertEquals( Said.CUT, m.recall( Topic.DIVISION, 5, new double[] { 100, 100 }, 10 ) );
	}

	/**
	 * The user's report: the second adaptive_track lap asked again what the first had asked. Lap two
	 * re-detects, so the same merge comes back a fraction of a micron away.
	 */
	@Test
	public void aSecondLapDoesNotAskAgain()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot a2 = g.track( 0, 3, 0, 0 ).get( 2 );
		g.track( 0, 3, 20, 0 );
		final Spot m3 = g.spot( 3, 10, 0, 0, 15 );
		g.link( a2, m3 );
		final ToolContext ctx = g.context();
		final int[] asked = { 0 };
		final AutoCorrectOrchestrator.SplitQuestion saysOne = ( t, c, r, cov, p, why ) -> {
			asked[ 0 ]++;
			return AutoCorrectOrchestrator.SplitAnswer.ONE;
		};
		final AutoCorrectOrchestrator.PieceFitter onTheSeeds = ( t, c, r, cov, seeds, fr ) -> {
			final double[][] cv = { { 25, 0, 0 }, { 0, 25, 0 }, { 0, 0, 25 } };
			return new AutoCorrectOrchestrator.Pieces( new double[][] { seeds[ 0 ].clone(), seeds[ 1 ].clone() },
					new double[][][] { cv, cv } );
		};
		AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 4, onTheSeeds, saysOne );
		assertEquals( 1, asked[ 0 ] );
		// Lap two: the merged detection re-detected 0.7 um along.
		final ModelGraph graph = g.graph();
		graph.getLock().writeLock().lock();
		try
		{
			m3.setPosition( 10.7, 0 );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, 4, onTheSeeds, saysOne );
		assertEquals( "not asked again", 1, asked[ 0 ] );
	}
}
