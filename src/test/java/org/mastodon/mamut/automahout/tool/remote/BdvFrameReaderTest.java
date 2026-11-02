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
package org.mastodon.mamut.automahout.tool.remote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.CellScale;

import bdv.util.RandomAccessibleIntervalMipmapSource;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/** {@link BdvFrameReader#readForCellScale}: which resolution level a frame too large to measure is read from. */
public class BdvFrameReaderTest
{
	/**
	 * Two levels, 260^3 and 130^3 (1 and 2 µm voxels). Coarsened to 2.06 µm, the frame comes from level 1 and is
	 * averaged 2 x 2 x 2 there: 65^3. Read from level 0 it would have been averaged 3 x 3 x 3 (87^3).
	 */
	@Test
	public void aLargeFrameIsReadFromACoarserLevel()
	{
		final RandomAccessibleIntervalMipmapSource< UnsignedShortType > source = pyramid( 260 );
		final CellScale.Sample s = BdvFrameReader.readForCellScale( source, 0 );
		assertTrue( s.coarsened );
		assertEquals( Math.cbrt( 260.0 * 260 * 260 / CellScale.MAX_VOXELS ), s.voxel, 1e-9 );
		assertArrayEquals( new long[] { 65, 65, 65 }, s.frame.dims );
		assertEquals( 4, s.frame.voxelToWorld[ 0 ], 1e-12 );
		assertEquals( 7, s.frame.data[ 0 ], 0 );
	}

	/** A frame within the budget is read from level 0 as {@link BdvFrameReader#read} reads it. */
	@Test
	public void aFrameWithinTheBudgetIsReadAtFullResolution()
	{
		final CellScale.Sample s = BdvFrameReader.readForCellScale( pyramid( 120 ), 0 );
		assertFalse( s.coarsened );
		assertArrayEquals( new long[] { 120, 120, 120 }, s.frame.dims );
		assertEquals( 1, s.frame.voxelToWorld[ 0 ], 1e-12 );
	}

	@SuppressWarnings( "unchecked" )
	private static RandomAccessibleIntervalMipmapSource< UnsignedShortType > pyramid( final int n )
	{
		final RandomAccessibleInterval< UnsignedShortType > l0 = ArrayImgs.unsignedShorts( n, n, n ),
				l1 = ArrayImgs.unsignedShorts( n / 2, n / 2, n / 2 );
		net.imglib2.view.Views.iterable( l0 ).forEach( v -> v.set( 3 ) );
		net.imglib2.view.Views.iterable( l1 ).forEach( v -> v.set( 7 ) );
		return new RandomAccessibleIntervalMipmapSource<>( new RandomAccessibleInterval[] { l0, l1 },
				new UnsignedShortType(), new double[][] { { 1, 1, 1 }, { 2, 2, 2 } },
				new FinalVoxelDimensions( "µm", 1, 1, 1 ), "test" );
	}
}
