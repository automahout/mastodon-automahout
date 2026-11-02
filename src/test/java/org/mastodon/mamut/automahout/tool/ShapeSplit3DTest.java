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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** {@link ShapeSplit3D} on voxels of uniform balls, sampled on a 0.5 grid. */
public class ShapeSplit3DTest
{
	private static void ball( final List< double[] > out, final double cx, final double cy, final double cz, final double r )
	{
		for ( double x = -r; x <= r; x += 0.5 )
			for ( double y = -r; y <= r; y += 0.5 )
				for ( double z = -r; z <= r; z += 0.5 )
					if ( x * x + y * y + z * z <= r * r )
						out.add( new double[] { cx + x, cy + y, cz + z } );
	}

	@Test
	public void twoTouchingBallsComeOutAsTwoSpotsOnThem()
	{
		final List< double[] > pts = new ArrayList<>();
		ball( pts, 0, 0, 0, 3 );
		ball( pts, 0, 0, 6.5, 3 ); // stacked in z, touching
		final ShapeSplit3D.Result r = ShapeSplit3D.analyse( pts.toArray( new double[ 0 ][] ),
				new double[][] { { 0.5, 0, 1 }, { 0, 0.5, 5.5 } }, 9 / 5.0, 0.02 );
		assertTrue( r.twoFitted() );
		assertEquals( 0, r.means[ 0 ][ 2 ], 0.3 );
		assertEquals( 6.5, r.means[ 1 ][ 2 ], 0.3 );
		// A uniform ball of radius 3 is a spot of covariance 9 on its diagonal.
		for ( int c = 0; c < 2; c++ )
			for ( int k = 0; k < 3; k++ )
				assertEquals( 9, r.covs[ c ][ k ][ k ], 1.8 );
	}

	@Test
	public void oneBallSplitsIntoHalvesTooCloseToBeTwoCells()
	{
		// EM always finds two halves in one blob; what tells them apart is how far apart they end up. The
		// merge repair refuses below 0.9 radii, and the halves of one ball sit 0.75 radii apart.
		final List< double[] > pts = new ArrayList<>();
		ball( pts, 0, 0, 0, 3 );
		final ShapeSplit3D.Result r = ShapeSplit3D.analyse( pts.toArray( new double[ 0 ][] ),
				new double[][] { { 0, 0, -1 }, { 0, 0, 1 } }, 9 / 5.0, 0.02 );
		assertTrue( r.twoFitted() );
		assertTrue( r.separation() / 3 < 0.9 );
	}

	@Test
	public void tooFewVoxelsIsNoObject()
	{
		final ShapeSplit3D.Result r = ShapeSplit3D.analyse( new double[][] { { 0, 0, 0 }, { 1, 0, 0 } },
				new double[][] { { 0, 0, 0 }, { 1, 0, 0 } }, 1, 0.02 );
		assertFalse( r.twoFitted() );
		assertTrue( r.refusal.startsWith( "no object" ) );
	}
}
