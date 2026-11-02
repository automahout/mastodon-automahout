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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.remote.Frame;

/**
 * {@link CellScale} against its Python reference ({@code benchmark/cell-scale/cell_scale.py}) on images defined
 * by formula, so both implementations see the same pixels. The expected values are the reference's output.
 */
public class CellScaleTest
{
	/** 6 x 5 disks of radius 8 px (4 µm at 0.5 µm/px) on a 260 x 220 frame. Reference: 3.73729534939745 µm, 30 blobs. */
	@Test
	public void disksMatchTheReference()
	{
		final int nx = 260, ny = 220;
		final float[] d = new float[ nx * ny ];
		for ( int y = 0; y < ny; y++ )
			for ( int x = 0; x < nx; x++ )
			{
				float v = 10;
				for ( int i = 0; i < 6; i++ )
					for ( int j = 0; j < 5; j++ )
					{
						final int cx = 30 + 40 * i, cy = 30 + 40 * j;
						if ( ( x - cx ) * ( x - cx ) + ( y - cy ) * ( y - cy ) <= 64 )
							v = 100;
					}
				d[ y * nx + x ] = v;
			}
		final CellScale.Estimate e = CellScale.estimate(
				new Frame( 0, new long[] { nx, ny, 1 }, d, new double[] { 0.5, 0, 0, 0, 0, 0.5, 0, 0, 0, 0, 0.5, 0 } ) );
		assertEquals( 3.73729534939745, e.radius, 1e-6 );
		assertEquals( 30, e.blobs );
	}

	/** 3 x 3 x 2 balls of radius 6 µm, voxels 1 x 1 x 2 µm. Reference: 5.872374364321751 µm, 18 blobs. */
	@Test
	public void ballsInAnisotropicVoxelsMatchTheReference()
	{
		final int nx = 90, ny = 90, nz = 24;
		final float[] d = new float[ nx * ny * nz ];
		for ( int z = 0; z < nz; z++ )
			for ( int y = 0; y < ny; y++ )
				for ( int x = 0; x < nx; x++ )
				{
					float v = 5;
					for ( int i = 0; i < 3; i++ )
						for ( int j = 0; j < 3; j++ )
							for ( int k = 0; k < 2; k++ )
							{
								final int cx = 15 + 30 * i, cy = 15 + 30 * j, cz = 6 + 12 * k;
								if ( ( x - cx ) * ( x - cx ) + ( y - cy ) * ( y - cy ) + 4 * ( z - cz ) * ( z - cz ) <= 36 )
									v = 80;
							}
					d[ ( z * ny + y ) * nx + x ] = v;
				}
		final CellScale.Estimate e = CellScale.estimate(
				new Frame( 0, new long[] { nx, ny, nz }, d, new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 2, 0 } ) );
		assertEquals( 5.872374364321751, e.radius, 1e-6 );
		assertEquals( 18, e.blobs );
	}

	/** The factors measured on the development sequences (see ElephantDataSettings). */
	@Test
	public void elephantSettingsFollowTheRadius()
	{
		assertArrayEquals( new double[] { 3.0, 15.0, 24.0 }, ElephantDataSettings.forRadius( 6.0 ), 1e-12 );
	}

	/** scipy's kernels: normalised Gaussian, second derivative (x^2/s^4 - 1/s^2) times it, radius int(4s + 0.5). */
	@Test
	public void kernelsFollowScipy()
	{
		final double[] g = CellScale.kernel( 1.0, 0 );
		assertEquals( 9, g.length );
		double sum = 0;
		for ( final double v : g )
			sum += v;
		assertEquals( 1.0, sum, 1e-12 );
		final double[] g2 = CellScale.kernel( 1.0, 2 );
		assertEquals( -g[ 4 ], g2[ 4 ], 1e-12 ); // x = 0: -1/s^2 * g
		assertEquals( 0.0, g2[ 3 ], 1e-12 ); // x = 1: (1 - 1) * g
		assertEquals( 3 * g[ 2 ], g2[ 2 ], 1e-12 ); // x = 2: (4 - 1) * g
		assertEquals( 1, CellScale.reflect( -2, 5 ) );
		assertEquals( 3, CellScale.reflect( 6, 5 ) );
	}

	/** A frame the size the estimate was measured on is measured exactly as before: same object, not coarsened. */
	@Test
	public void aFrameWithinTheBudgetIsNotTouched()
	{
		final Frame f = new Frame( 0, new long[] { 700, 1100, 1 }, new float[ 700 * 1100 ],
				new double[] { 0.645, 0, 0, 0, 0, 0.645, 0, 0, 0, 0, 1, 0 } );
		final CellScale.Sample s = CellScale.coarsen( f );
		assertSame( f, s.frame );
		assertFalse( s.coarsened );
	}

	/** 600^3 at 1 µm: the scale space would have run on 216 M voxels; 5 x 5 x 5 blocks bring it to 1.73 M. */
	@Test
	public void aLargeStackIsCoarsenedToTheBudget()
	{
		final double s = CellScale.coarseVoxel( new long[] { 600, 600, 600 }, 1, 1 );
		assertEquals( Math.cbrt( 108 ), s, 1e-9 );
		assertArrayEquals( new int[] { 5, 5, 5 }, CellScale.blockFactors( new double[] { 1, 1, 1 }, s ) );
		// Coarse z already: only x and y are averaged.
		assertArrayEquals( new int[] { 4, 4, 1 }, CellScale.blockFactors( new double[] { 0.5, 0.5, 2 }, 1.9 ) );
	}

	/** Blocks are averaged, a partial edge block over what it has, and the transform follows the blocks. */
	@Test
	public void binningAveragesAndMovesTheTransform()
	{
		final CellScale.Binner b = new CellScale.Binner( new long[] { 3, 2, 1 }, new int[] { 2, 2, 1 } );
		for ( final float v : new float[] { 1, 2, 3, 5, 6, 7 } )
			b.add( v );
		final Frame f = b.frame( 4, new double[] { 0.5, 0, 0, 10, 0, 0.5, 0, 20, 0, 0, 2, 30 } );
		assertArrayEquals( new long[] { 2, 1, 1 }, f.dims );
		assertArrayEquals( new float[] { ( 1 + 2 + 5 + 6 ) / 4f, ( 3 + 7 ) / 2f }, f.data, 1e-6f );
		// voxel 1 x 1 x 2 µm, block (0, 0) centred where voxels (0..1, 0..1) are: (10.25, 20.25, 30)
		assertArrayEquals( new double[] { 1, 0, 0, 10.25, 0, 1, 0, 20.25, 0, 0, 2, 30 }, f.voxelToWorld, 1e-12 );
		assertEquals( 4, f.t );
	}

	/**
	 * Disks of radius 8 µm on a frame too large to measure as it is (coarsened 2 x 2) read within one step of the
	 * scale ladder of the same disks on a frame small enough to measure at full resolution.
	 */
	@Test
	public void cellsAreMeasuredOnACoarsenedFrame()
	{
		final CellScale.Estimate coarse = ElephantDataSettings.measure( t -> disks( 2200, 2000 ), 0, 0 );
		assertEquals( 0.5 * Math.sqrt( 2.2 ), coarse.coarseVoxel, 1e-9 );
		final CellScale.Estimate full = ElephantDataSettings.measure( t -> disks( 1400, 1400 ), 0, 0 );
		assertTrue( Double.isNaN( full.coarseVoxel ) );
		final double step = CellScale.sigmas()[ 1 ] / CellScale.sigmas()[ 0 ];
		assertEquals( 0, Math.log( coarse.radius / full.radius ), Math.log( step ) + 1e-9 );
	}

	/** Disks of radius 16 px (8 µm at 0.5 µm/px), one per 100 x 100 px. */
	private static Frame disks( final int nx, final int ny )
	{
		final float[] d = new float[ nx * ny ];
		for ( int y = 0; y < ny; y++ )
			for ( int x = 0; x < nx; x++ )
			{
				final int cx = 50 + 100 * ( x / 100 ), cy = 50 + 100 * ( y / 100 );
				d[ y * nx + x ] = ( x - cx ) * ( x - cx ) + ( y - cy ) * ( y - cy ) <= 256 ? 100 : 10;
			}
		return new Frame( 0, new long[] { nx, ny, 1 }, d, new double[] { 0.5, 0, 0, 0, 0, 0.5, 0, 0, 0, 0, 0.5, 0 } );
	}

	/** Balls of 1.5 µm in a stack coarsened to 2 µm voxels: not measured, rather than measured as a voxel. */
	@Test
	public void cellsBelowTwoCoarseVoxelsAreNotMeasured()
	{
		final int n = 130;
		final float[] d = new float[ n * n * n ];
		for ( int z = 0; z < n; z++ )
			for ( int y = 0; y < n; y++ )
				for ( int x = 0; x < n; x++ )
				{
					final int cx = 5 + 10 * ( x / 10 ), cy = 5 + 10 * ( y / 10 ), cz = 5 + 10 * ( z / 10 );
					d[ ( z * n + y ) * n + x ] = ( x - cx ) * ( x - cx ) + ( y - cy ) * ( y - cy ) + ( z - cz ) * ( z - cz ) <= 2 ? 100 : 10;
				}
		final Frame f = new Frame( 0, new long[] { n, n, n }, d, new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } );
		final CellScale.Sample s = CellScale.coarsen( f );
		assertTrue( s.coarsened );
		assertArrayEquals( new long[] { 65, 65, 65 }, s.frame.dims );
		final CellScale.Estimate e = ElephantDataSettings.measure( t -> f, 0, 0 );
		assertTrue( Double.isNaN( e.radius ) );
		assertEquals( Math.cbrt( n * n * n / 2e6 ), e.coarseVoxel, 1e-9 );
	}
}
