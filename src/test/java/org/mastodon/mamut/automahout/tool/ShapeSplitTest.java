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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link ShapeSplit} on shapes whose answer is known. */
public class ShapeSplitTest
{
	private static final int W = 60, H = 40;

	private static double[] paint( final double[][] discs )
	{
		final double[] img = new double[ W * H ];
		for ( int y = 0; y < H; y++ )
			for ( int x = 0; x < W; x++ )
				for ( final double[] d : discs )
				{
					final double dx = ( x - d[ 0 ] ) / d[ 2 ], dy = ( y - d[ 1 ] ) / d[ 3 ];
					if ( dx * dx + dy * dy <= 1 )
						img[ y * W + x ] = 100;
				}
		return img;
	}

	@Test
	public void oneEllipseIsCoveredByOneEllipse()
	{
		final ShapeSplit.Result r = ShapeSplit.analyse( paint( new double[][] { { 30, 20, 14, 8 } } ), W, H,
				50, 30, 20, 14 );
		assertNotNull( r );
		assertTrue( "a uniform ellipse IS its moment ellipse: " + r.iou1, r.iou1 > 0.9 );
		assertTrue( "and a second ellipse adds little: " + r.gain(), r.gain() < 0.05 );
	}

	@Test
	public void twoTouchingNucleiAreCoveredByTwoAndSplitAtTheirCentres()
	{
		final ShapeSplit.Result r = ShapeSplit.analyse(
				paint( new double[][] { { 21, 20, 9, 9 }, { 38, 20, 9, 9 } } ), W, H, 50, 30, 20, 18 );
		assertNotNull( r );
		assertTrue( r.twoFitted() );
		assertTrue( "one ellipse over a pair leaves the waist uncovered: " + r.iou1, r.iou1 < 0.85 );
		assertTrue( "two cover it: " + r.iou2, r.iou2 > 0.9 );
		final double lo = Math.min( r.means[ 0 ][ 0 ], r.means[ 1 ][ 0 ] );
		final double hi = Math.max( r.means[ 0 ][ 0 ], r.means[ 1 ][ 0 ] );
		assertEquals( 21, lo, 1.0 );
		assertEquals( 38, hi, 1.0 );
	}

	@Test
	public void seededComponentsKeepTheirSeedsIdentity()
	{
		// The cell each piece belongs to comes from the frame before; the fit must not swap them.
		final double[] img = paint( new double[][] { { 21, 20, 9, 9 }, { 38, 20, 9, 9 } } );
		for ( final boolean swap : new boolean[] { false, true } )
		{
			final double[][] seeds = swap ? new double[][] { { 40, 21 }, { 19, 19 } }
					: new double[][] { { 19, 19 }, { 40, 21 } };
			final ShapeSplit.Result r = ShapeSplit.analyse( img, W, H, 50, 30, 20, 18, seeds );
			assertNotNull( r );
			assertTrue( r.twoFitted() );
			assertEquals( seeds[ 0 ][ 0 ] < 30 ? 21 : 38, r.means[ 0 ][ 0 ], 1.0 );
			assertEquals( seeds[ 1 ][ 0 ] < 30 ? 21 : 38, r.means[ 1 ][ 0 ], 1.0 );
		}
	}

	@Test
	public void theObjectIsTheOneUnderTheDetectionNotTheBrightestInTheWindow()
	{
		// A big neighbour at the window edge must not be what gets fitted.
		final double[] img = paint( new double[][] { { 20, 20, 6, 6 }, { 50, 20, 9, 15 } } );
		final ShapeSplit.Result r = ShapeSplit.analyse( img, W, H, 50, 20, 20, 6 );
		assertNotNull( r );
		assertEquals( 20, r.mean1[ 0 ], 0.5 );
	}

	@Test
	public void nothingAboveTheLevelIsNoObject()
	{
		assertNull( ShapeSplit.analyse( new double[ W * H ], W, H, 50, 30, 20, 10 ) );
	}
}
