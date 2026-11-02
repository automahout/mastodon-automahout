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

import java.util.List;
import java.util.Random;

import org.junit.Test;

import net.imglib2.realtransform.AffineTransform3D;

/** {@link VisualColdStart}: blobs at the size the VLM saw, and its answer read. */
public class VisualColdStartTest
{
	/** A faint blob (under half the median response) is not a reference cell; the median and above are kept. */
	@Test
	public void confidentKeepsBlobsAtHalfTheMedianOrMore()
	{
		final List< double[] > rs = java.util.Arrays.asList(
				new double[] { 10, 1, 0, 0 }, new double[] { 8, 2, 0, 0 }, new double[] { 6, 3, 0, 0 },
				new double[] { 3, 4, 0, 0 }, new double[] { 1, 5, 0, 0 } );
		final List< double[] > kept = VisualColdStart.confident( rs );
		assertEquals( 4, kept.size() ); // median 6, bar 3: only the response-1 blob goes
		assertEquals( 4.0, kept.get( 3 )[ 0 ], 0 );
		assertTrue( VisualColdStart.confident( java.util.Collections.emptyList() ).isEmpty() );
	}

	/** Two dim spheres of radius 6 (voxels 1 um) in noise: both found, nothing else. */
	@Test
	public void blobsAtTheSeenSizeAreTheNucleiAndNothingElse()
	{
		final int nx = 80, ny = 60, nz = 24;
		final float[] d = new float[ nx * ny * nz ];
		final Random r = new Random( 1 );
		final double[][] centres = { { 20, 20, 10 }, { 55, 35, 14 } };
		for ( int z = 0; z < nz; z++ )
			for ( int y = 0; y < ny; y++ )
				for ( int x = 0; x < nx; x++ )
				{
					double v = 23 + 4 * r.nextGaussian();
					for ( final double[] c : centres )
						if ( Math.pow( x - c[ 0 ], 2 ) + Math.pow( y - c[ 1 ], 2 ) + Math.pow( z - c[ 2 ], 2 ) <= 36 )
							v += 6;
					d[ ( z * ny + y ) * nx + x ] = ( float ) v;
				}
		final VisualColdStart.Frame f = new VisualColdStart.Frame( d, nx, ny, nz, 1, 1, new AffineTransform3D() );
		final List< double[] > b = VisualColdStart.blobs( f, 6 );
		assertEquals( 2, b.size() );
		for ( final double[] p : b )
		{
			double best = Double.POSITIVE_INFINITY;
			for ( final double[] c : centres )
				best = Math.min( best, Math.sqrt( Math.pow( p[ 0 ] - c[ 0 ], 2 ) + Math.pow( p[ 1 ] - c[ 1 ], 2 ) + Math.pow( p[ 2 ] - c[ 2 ], 2 ) ) );
			assertTrue( "off by " + best, best < 3 );
		}
	}

	@Test
	public void theAnswerIsReadFromItsJson()
	{
		final VisualColdStart.Understanding u = VisualColdStart.parse(
				"Looking at the stack...\n{\"count\": 4, \"diameter_um\": 8.5, \"reasoning\": \"four large nuclei\"}" );
		assertEquals( 4, u.count );
		assertEquals( 8.5, u.diameter, 0 );
		assertNull( VisualColdStart.parse( "I cannot tell." ) );
		assertNull( VisualColdStart.parse( "{\"count\": 0, \"diameter_um\": 8}" ) );
	}

	@Test
	public void theNucleusAnswerIsReadFromItsJson()
	{
		assertEquals( Boolean.FALSE, VisualColdStart.parseNucleus(
				"```json\n{\"nucleus\": false, \"reasoning\": \"a small fixed bright body at the eggshell\"}\n```" ) );
		assertEquals( Boolean.TRUE, VisualColdStart.parseNucleus( "{\"nucleus\":true}" ) );
		assertNull( VisualColdStart.parseNucleus( "It might be a nucleus." ) );
		assertNull( VisualColdStart.parseNucleus( null ) );
	}
}
