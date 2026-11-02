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

import org.junit.Test;

/**
 * A minimum must sit below the mean, not on it.
 *
 * <p>{@code estimateMinDivisionInterval} returned the population's doubling time as the shortest
 * credible cell cycle. Measured on the CTC tracking ground truth for Fluo-N2DL-HeLa, that threshold
 * cuts <b>91%</b> of real divisions in both sequences — it sits above the median cycle. A 92-frame
 * run ended with 10 divisions where the growth from 45 to 158 cells needs at least 113.</p>
 *
 * <p>Numbers from {@code benchmark/division-interval/measure.py}.</p>
 */
public class DivisionIntervalDivisorTest
{
	/** Doubling time measured from counts, per sequence. */
	private static final double DOUBLING_01 = 54.4, DOUBLING_02 = 59.2;

	/** Shortest and median REAL completed cycle, per sequence. */
	private static final int SHORTEST_01 = 29, MEDIAN_01 = 39;

	private static final int P05_02 = 25, MEDIAN_02 = 35;

	private static int threshold( final double doublingTime )
	{
		return Math.max( 2, ( int ) Math.round(
				doublingTime / AutoCorrectOrchestrator.DOUBLING_TIME_TO_MIN_INTERVAL ) );
	}

	@Test
	public void theOldRuleSatAboveTheMedianRealCycle()
	{
		// Kept as the measurement it is: this is why nine real divisions in ten were dissolved.
		assertTrue( "the doubling time is above the median cycle in 01", DOUBLING_01 > MEDIAN_01 );
		assertTrue( "and in 02", DOUBLING_02 > MEDIAN_02 );
	}

	@Test
	public void theThresholdNowClearsEveryRealCycleInSequenceOne()
	{
		assertTrue( "threshold " + threshold( DOUBLING_01 ) + " must be under the shortest real cycle "
				+ SHORTEST_01, threshold( DOUBLING_01 ) < SHORTEST_01 );
	}

	@Test
	public void andClearsTheFifthPercentileInSequenceTwo()
	{
		assertTrue( "threshold " + threshold( DOUBLING_02 ) + " must be at or under the 5th percentile "
				+ P05_02, threshold( DOUBLING_02 ) <= P05_02 );
	}

	@Test
	public void theDivisorIsTheMeasuredKneeNotARoundNumber()
	{
		// 2.0 still cuts 18.9% of sequence 02; 3.0 buys nothing over 2.5.
		assertEquals( 2.5, AutoCorrectOrchestrator.DOUBLING_TIME_TO_MIN_INTERVAL, 1e-9 );
	}

	@Test
	public void aMinimumOfAtLeastTwoFramesSurvives()
	{
		// Consecutive-frame divisions are never credible however slowly the population grows.
		assertTrue( threshold( 1.0 ) >= 2 );
		assertTrue( threshold( 0.0 ) >= 2 );
	}
}
