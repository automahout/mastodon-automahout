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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class TrainingBalanceTest
{
	/** 200 typical cells around (10, 10), and 10 each of dim, small and large ones. */
	private static double[][] population()
	{
		final Random r = new Random( 1 );
		final int n = 230;
		final double[] b = new double[ n ], s = new double[ n ];
		for ( int i = 0; i < 200; i++ )
		{
			b[ i ] = 10 + r.nextGaussian();
			s[ i ] = 10 + r.nextGaussian();
		}
		for ( int i = 200; i < 210; i++ )
		{
			b[ i ] = 2;
			s[ i ] = 10;
		}
		for ( int i = 210; i < 220; i++ )
		{
			b[ i ] = 10;
			s[ i ] = 4;
		}
		for ( int i = 220; i < 230; i++ )
		{
			b[ i ] = 10;
			s[ i ] = 18;
		}
		return new double[][] { b, s };
	}

	@Test
	public void onlyTheCrowdedTypicalGroupsAreThinned()
	{
		final double[][] p = population();
		final int[] drop = TrainingBalance.toWithhold( p[ 0 ], p[ 1 ], 7 );
		assertTrue( "something is thinned", drop.length > 0 );
		for ( final int i : drop )
			assertTrue( "an atypical cell was withheld: " + i, i < 200 );
		assertTrue( "most of the typical cells are still taught: " + drop.length, drop.length < 150 );
	}

	@Test
	public void theChoiceIsReproducible()
	{
		final double[][] p = population();
		assertArrayEquals( TrainingBalance.toWithhold( p[ 0 ], p[ 1 ], 7 ), TrainingBalance.toWithhold( p[ 0 ], p[ 1 ], 7 ) );
	}

	@Test
	public void tooFewToGroupWithholdsNothing()
	{
		final double[] v = new double[ TrainingBalance.MIN_DETECTIONS - 1 ];
		assertEquals( 0, TrainingBalance.toWithhold( v, v, 1 ).length );
	}

	@Test
	public void binsAreMadWideAroundTheMedianNotQuantiles()
	{
		// Quantile bins would put a third in each whatever the spread; MAD-wide bins put the crowd in the middle.
		final double[] v = { 0, 10, 10, 10, 10, 10, 10, 10, 10, 20 };
		final int[] b = TrainingBalance.bins( v );
		assertEquals( 2, b[ 1 ] );
	}
}
