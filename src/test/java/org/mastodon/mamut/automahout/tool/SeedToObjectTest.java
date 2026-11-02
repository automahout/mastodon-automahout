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

/** Tests for {@link SeedToObject}, on constructed shapes rather than images. */
public class SeedToObjectTest
{
	private static final int W = 80;

	private static final int H = 80;

	/** A window of background with discs of the given centres and radii painted bright. */
	private static double[] discs( final double[]... cyr )
	{
		final double[] win = new double[ W * H ];
		for ( int y = 0; y < H; y++ )
		{
			for ( int x = 0; x < W; x++ )
			{
				double v = 0.0;
				for ( final double[] d : cyr )
				{
					final double dx = x - d[ 0 ], dy = y - d[ 1 ];
					if ( dx * dx + dy * dy <= d[ 2 ] * d[ 2 ] )
						v = Math.max( v, 10.0 );
				}
				win[ y * W + x ] = v;
			}
		}
		return win;
	}

	private static Boolean same( final double[] win, final int ax, final int ay, final int bx,
			final int by )
	{
		return SeedToObject.sameObject( win, W, H, 5.0, ax, ay, bx, by );
	}

	@Test
	public void twoSeedsInOneRoundObjectAreTheSameObject()
	{
		final double[] win = discs( new double[] { 40, 40, 14 } );
		assertEquals( Boolean.TRUE, same( win, 34, 40, 46, 40 ) );
	}

	@Test
	public void aBrightStraightLineBetweenTwoCellsDoesNotMakeThemOne()
	{
		// The case the straight-segment test gets wrong in the unsafe direction. Two discs overlapping
		// enough that the segment joining their centres never leaves the object: every sample along it
		// is bright, so a valley test sees one continuous blob and merges two cells. The neck between
		// them is only visible to the shape.
		final double[] win = discs(
				new double[] { 30, 40, 14 },
				new double[] { 50, 40, 14 } );
		for ( int x = 30; x <= 50; x++ )
			assertTrue( win[ 40 * W + x ] > 5.0, "the segment must stay inside the object at x=" + x );
		assertEquals( Boolean.FALSE, same( win, 30, 40, 50, 40 ) );
	}

	@Test
	public void aStronglyBiLobedCellIsSplit()
	{
		// The known limitation, stated rather than hidden. A watershed separates by shape, so a cell
		// that really is two lobes joined by a thin neck is split whether or not it is one cell. This
		// is the measured 14%: on real data the method merges 85.5% of one-cell pairs, not all of
		// them. A test that asserted otherwise would be asserting the failure mode away.
		final double[] win = discs(
				new double[] { 26, 40, 12 },
				new double[] { 54, 40, 12 },
				new double[] { 40, 40, 4 } );
		assertEquals( Boolean.FALSE, same( win, 26, 40, 54, 40 ) );
	}

	@Test
	public void twoTouchingDiscsAreTwoObjects()
	{
		// Connectivity alone cannot do this -- measured, plain components wrongly merge 59-80% of
		// distinct pairs. The neck between the discs is what the distance transform sees.
		final double[] win = discs(
				new double[] { 28, 40, 14 },
				new double[] { 53, 40, 14 } );
		assertEquals( Boolean.FALSE, same( win, 24, 40, 57, 40 ) );
	}

	@Test
	public void twoSeparateObjectsAreTwoObjects()
	{
		final double[] win = discs(
				new double[] { 20, 40, 9 },
				new double[] { 60, 40, 9 } );
		assertEquals( Boolean.FALSE, same( win, 20, 40, 60, 40 ) );
	}

	@Test
	public void aSeedOffTheObjectIsRefusedRatherThanGuessed()
	{
		final double[] win = discs( new double[] { 40, 40, 12 } );
		assertNull( same( win, 40, 40, 5, 5 ), "a seed on background cannot share an object" );
	}

	@Test
	public void aSeedOutsideTheWindowIsRefused()
	{
		final double[] win = discs( new double[] { 40, 40, 12 } );
		assertNull( same( win, 40, 40, W + 3, 40 ) );
	}

	@Test
	public void theDistanceTransformPeaksAtTheCentreOfADisc()
	{
		final double[] win = discs( new double[] { 40, 40, 12 } );
		final boolean[] mask = new boolean[ W * H ];
		for ( int i = 0; i < mask.length; i++ )
			mask[ i ] = win[ i ] > 5.0;
		final double[] d = SeedToObject.distanceTransform( mask, W, H );
		assertTrue( d[ 40 * W + 40 ] > d[ 40 * W + 48 ],
				"the centre must be further from background than a point near the rim" );
		assertEquals( 0.0, d[ 0 ], 1e-9, "background is at distance zero" );
		// Chamfer (3,4)/3 approximates the true radius to within a few percent.
		assertEquals( 12.0, d[ 40 * W + 40 ], 12.0 * 0.1 );
	}

	@Test
	public void aNeckGetsALowerDistanceThanEitherBody()
	{
		final double[] win = discs(
				new double[] { 28, 40, 14 },
				new double[] { 53, 40, 14 } );
		final boolean[] mask = new boolean[ W * H ];
		for ( int i = 0; i < mask.length; i++ )
			mask[ i ] = win[ i ] > 5.0;
		final double[] d = SeedToObject.distanceTransform( mask, W, H );
		final double neck = d[ 40 * W + 40 ];
		assertTrue( neck < d[ 40 * W + 28 ] && neck < d[ 40 * W + 53 ],
				"the neck between two touching discs must be the thinnest part" );
	}
}
