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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GrowthPriorTest
{
	private static int[] ts( final int n )
	{
		final int[] t = new int[ n ];
		for ( int i = 0; i < n; i++ )
			t[ i ] = i;
		return t;
	}

	@Test
	public void aPopulationThatIsNotGrowingExpectsWhatItHas()
	{
		final double[] c = new double[ 40 ];
		for ( int i = 0; i < c.length; i++ )
			c[ i ] = 50 + ( i % 3 ) - 1;
		final GrowthPrior.Fit f = GrowthPrior.fit( ts( 40 ), c );
		assertEquals( 50, f.expectedAt( 49 ), 2 );
		assertTrue( Double.isInfinite( f.doublingTime() ) || f.doublingTime() > 500 );
	}

	@Test
	public void aShrinkingCountIsReadAsNoGrowthNotDecline()
	{
		final double[] c = new double[ 30 ];
		for ( int i = 0; i < c.length; i++ )
			c[ i ] = 100 - i;
		assertEquals( 0, GrowthPrior.fit( ts( 30 ), c ).slope, 0 );
	}

	@Test
	public void aDoublingPopulationIsExtrapolatedAtItsRateShrunkByTheEvidence()
	{
		// Doubling every 20 timepoints, 190 of them: the rate is read almost whole.
		final double[] c = new double[ 190 ];
		for ( int i = 0; i < c.length; i++ )
			c[ i ] = 4 * Math.pow( 2, i / 20.0 );
		final GrowthPrior.Fit f = GrowthPrior.fit( ts( 190 ), c );
		assertEquals( 20 * ( 189 + GrowthPrior.SHRINK_SPAN ) / 189, f.doublingTime(), 0.5 );
		// The shrunk rate lands below the truth ten frames out (by about a sixth here); the guards put twice
		// this on top, so that still leaves them well above the real count.
		final double truth = 4 * Math.pow( 2, 199 / 20.0 );
		assertTrue( f.expectedAt( 199 ) > 0.75 * truth && f.expectedAt( 199 ) <= truth );
	}

	@Test
	public void theFirstWindowsSmallCountsDoNotPromiseAnExplosion()
	{
		// Fluo-N3DH-CE 01, t=0..9 as the run accepted them; the truth holds 8-12 cells at t=10..19.
		final GrowthPrior.Fit f = GrowthPrior.fit( ts( 10 ), new double[] { 1, 2, 2, 2, 2, 2, 5, 5, 5, 4 } );
		final double e = f.expectedAt( 19 );
		assertTrue( "expected " + e, e > 5 && e < 12 );
	}

	@Test
	public void countsFarApartAreReadAsTheEvidenceTheyAre()
	{
		// CE 01 tracked only at t=0, 30, ..., 120 (the noise at t=0 included); the truth holds 192 cells at t=150.
		final GrowthPrior.Fit f = GrowthPrior.fit( new int[] { 0, 30, 60, 90, 120 }, new double[] { 20, 12, 43, 84, 120 } );
		final double e = f.expectedAt( 150 );
		assertTrue( "expected " + e, e > 160 && e < 230 );
	}

	@Test
	public void tooFewAcceptedTimepointsGiveNoFit()
	{
		assertNull( GrowthPrior.fit( ts( 6 ), new double[] { 0, 3, 0, 4, 5, 6 } ) );
	}
}
