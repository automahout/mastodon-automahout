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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Counting the objects under one detection, which is the question a SPLIT needs.
 *
 * <p>{@link SeedToObject#sameObject} answers whether two seeds are one object -- the MERGE
 * direction. Nothing asked the inverse, so a single detection covering a touching pair survived
 * everything: the neighbour cap in {@code resizeToMeasuredExtent} needs a second detection to
 * protect, and when the model produced only one there is nothing to protect.</p>
 */
public class ObjectCountUnderDetectionTest
{
	private static final int W = 48;

	/** A filled disc of radius {@code r} centred at {@code (cx, cy)}, value 100 inside, 0 outside. */
	private static void disc( final double[] win, final double cx, final double cy, final double r )
	{
		for ( int y = 0; y < W; y++ )
			for ( int x = 0; x < W; x++ )
			{
				final double dx = x - cx, dy = y - cy;
				if ( dx * dx + dy * dy <= r * r )
					win[ y * W + x ] = 100.0;
			}
	}

	@Test
	public void oneCellUnderTheDetectionReportsOneCentre()
	{
		final double[] win = new double[ W * W ];
		disc( win, 24, 24, 8 );
		final int[] c = SeedToObject.objectCentresWithin( win, W, W, 50.0, 24, 24, 14, 5 );
		assertEquals( 2, c.length, "one object must report exactly one centre" );
	}

	@Test
	public void twoTouchingCellsUnderTheDetectionReportTwoCentres()
	{
		final double[] win = new double[ W * W ];
		disc( win, 17, 24, 8 );
		disc( win, 31, 24, 8 ); // overlapping, so a threshold alone sees one blob
		final int[] c = SeedToObject.objectCentresWithin( win, W, W, 50.0, 24, 24, 20, 5 );
		assertTrue( c.length >= 4, "two touching cells must report two centres, got " + c.length / 2 );
	}

	/** A neighbour outside the detection is not this detection's business. */
	@Test
	public void acentreOutsideTheDetectionDoesNotCount()
	{
		final double[] win = new double[ W * W ];
		disc( win, 14, 24, 6 );
		disc( win, 40, 24, 6 );
		final int[] c = SeedToObject.objectCentresWithin( win, W, W, 50.0, 14, 24, 9, 5 );
		assertEquals( 2, c.length, "only the object under the detection may be counted" );
	}

	/** An empty window is an abstention, which is not the same as a verdict of one. */
	@Test
	public void anEmptyWindowAbstains()
	{
		assertNull( SeedToObject.objectCentresWithin( new double[ W * W ], W, W, 50.0, 24, 24, 14, 5 ) );
		assertNull( SeedToObject.objectCentresWithin( new double[ 3 ], W, W, 50.0, 24, 24, 14, 5 ),
				"a malformed window cannot be judged" );
	}

	/** Asking for more separation than the cells have must merge them, not invent a split. */
	@Test
	public void raisingTheSeparationStopsTheSplit()
	{
		final double[] win = new double[ W * W ];
		disc( win, 21, 24, 8 );
		disc( win, 27, 24, 8 );
		assertTrue( SeedToObject.objectCentresWithin( win, W, W, 50.0, 24, 24, 20, 30 ).length <= 2,
				"a separation wider than the pair must not report two centres" );
	}

	/**
	 * Four bodies under one detection is over-segmentation, not a division.
	 *
	 * <p>The counter must still REPORT them — refusing is the caller's decision, and a counter that
	 * silently capped would hide the very case the ceiling exists for.</p>
	 */
	@Test
	public void theCounterReportsEveryCentreSoTheCallerCanRefuseTheShattered()
	{
		final double[] win = new double[ W * W ];
		disc( win, 16, 16, 5 );
		disc( win, 32, 16, 5 );
		disc( win, 16, 32, 5 );
		disc( win, 32, 32, 5 );
		final int[] c = SeedToObject.objectCentresWithin( win, W, W, 50.0, 24, 24, 24, 5 );
		assertTrue( c.length >= 8,
				"four separated bodies must all be reported, got " + c.length / 2 );
	}

	/** A flat bridge between two centres is no valley at all, whatever the shape says. */
	@Test
	public void twoPeaksJoinedByAFlatBridgeHaveNoValley()
	{
		final double[] win = new double[ W * W ];
		for ( int x = 10; x <= 38; x++ )
			for ( int y = 20; y <= 28; y++ )
				win[ y * W + x ] = 100.0;
		assertEquals( 1.0, SeedToObject.saddleRatio( win, W, W, 0.0, 12, 24, 36, 24 ), 1e-9 );
	}

	/** Two peaks with a real dip between them report how deep it goes. */
	@Test
	public void aRealValleyIsReportedAsAFractionOfTheDimmerPeak()
	{
		final double[] win = new double[ W * W ];
		for ( int x = 10; x <= 38; x++ )
			win[ 24 * W + x ] = 100.0;
		for ( int x = 22; x <= 26; x++ )
			win[ 24 * W + x ] = 40.0;   // the valley
		assertEquals( 0.4, SeedToObject.saddleRatio( win, W, W, 0.0, 12, 24, 36, 24 ), 1e-9 );
	}

	/** The background is subtracted, so the ratio measures signal rather than an offset. */
	@Test
	public void theOffsetDoesNotFlattenTheValley()
	{
		final double[] win = new double[ W * W ];
		for ( int x = 10; x <= 38; x++ )
			win[ 24 * W + x ] = 33100.0;
		for ( int x = 22; x <= 26; x++ )
			win[ 24 * W + x ] = 33050.0;
		assertEquals( 0.5, SeedToObject.saddleRatio( win, W, W, 33000.0, 12, 24, 36, 24 ), 1e-9,
				"without subtracting 33000 this reads 0.998 and no valley is ever found" );
	}

	/** Nothing above background cannot be compared; say so rather than inventing a valley. */
	@Test
	public void twoPointsAtBackgroundAbstain()
	{
		assertEquals( 1.0, SeedToObject.saddleRatio( new double[ W * W ], W, W, 0.0, 12, 24, 36, 24 ),
				1e-9 );
	}
}
