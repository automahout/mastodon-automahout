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
package org.mastodon.mamut.automahout.bench;

import org.mastodon.mamut.automahout.tool.CellScale;
import org.mastodon.mamut.automahout.tool.remote.Frame;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;

/**
 * Prints {@link CellScale}'s estimate for TIFF frames, to compare with the Python reference
 * ({@code benchmark/cell-scale/cell_scale.py}) on the same files:
 * {@code java ... CellScaleCheck <vx> <vz> <frame.tif>...}
 */
public final class CellScaleCheck
{
	private CellScaleCheck()
	{}

	public static Frame read( final String path, final double vx, final double vz )
	{
		final ImagePlus imp = IJ.openImage( path );
		final int nx = imp.getWidth(), ny = imp.getHeight(), nz = imp.getStackSize();
		final float[] data = new float[ nx * ny * nz ];
		final ImageStack st = imp.getStack();
		for ( int z = 0; z < nz; z++ )
		{
			final ij.process.ImageProcessor ip = st.getProcessor( z + 1 );
			for ( int y = 0; y < ny; y++ )
				for ( int x = 0; x < nx; x++ )
					data[ ( z * ny + y ) * nx + x ] = ip.getf( x, y );
		}
		return new Frame( 0, new long[] { nx, ny, nz }, data,
				new double[] { vx, 0, 0, 0, 0, vx, 0, 0, 0, 0, nz > 1 ? vz : vx, 0 } );
	}

	public static void main( final String[] args )
	{
		final double vx = Double.parseDouble( args[ 0 ] ), vz = Double.parseDouble( args[ 1 ] );
		for ( int i = 2; i < args.length; i++ )
		{
			final long t0 = System.nanoTime();
			final CellScale.Estimate e = CellScale.estimate( read( args[ i ], vx, vz ) );
			System.out.printf( "%s %.4f %d %.1fs%n", args[ i ].substring( args[ i ].lastIndexOf( '/' ) + 1 ), e.radius,
					e.blobs, ( System.nanoTime() - t0 ) / 1e9 );
		}
	}
}
