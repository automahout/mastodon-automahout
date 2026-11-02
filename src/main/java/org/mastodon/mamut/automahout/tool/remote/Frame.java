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

/**
 * One timepoint's pixels as a remote tool receives them, and the transform back to world coordinates.
 * {@code data} is in C order over {@code (z, y, x)} -- x fastest -- which is also imglib2's flat
 * iteration order over {@code (x, y, z)}.
 */
public final class Frame
{
	public final int t;

	/** Size along x, y, z (z = 1 for one plane). */
	public final long[] dims;

	public final float[] data;

	/** Row-major 3x4 voxel-to-world affine (imglib2's AffineTransform3D.getRowPackedCopy()). */
	public final double[] voxelToWorld;

	public Frame( final int t, final long[] dims, final float[] data, final double[] voxelToWorld )
	{
		this.t = t;
		this.dims = dims;
		this.data = data;
		this.voxelToWorld = voxelToWorld;
	}

	/** {@code (x, y, z)} in voxels to world. */
	public double[] toWorld( final double[] voxel )
	{
		final double[] w = new double[ 3 ];
		final double[] a = voxelToWorld;
		for ( int r = 0; r < 3; r++ )
			w[ r ] = a[ 4 * r ] * voxel[ 0 ] + a[ 4 * r + 1 ] * voxel[ 1 ] + a[ 4 * r + 2 ] * voxel[ 2 ] + a[ 4 * r + 3 ];
		return w;
	}

	/** A covariance in voxels² to world: A C Aᵀ with A the linear part. */
	public double[][] covarianceToWorld( final double[][] c )
	{
		final double[][] a = new double[ 3 ][ 3 ];
		for ( int r = 0; r < 3; r++ )
			for ( int k = 0; k < 3; k++ )
				a[ r ][ k ] = voxelToWorld[ 4 * r + k ];
		final double[][] out = new double[ 3 ][ 3 ];
		for ( int i = 0; i < 3; i++ )
			for ( int j = 0; j < 3; j++ )
			{
				double s = 0;
				for ( int k = 0; k < 3; k++ )
					for ( int l = 0; l < 3; l++ )
						s += a[ i ][ k ] * c[ k ][ l ] * a[ j ][ l ];
				out[ i ][ j ] = s;
			}
		return out;
	}

	/** Reads one timepoint; the image source behind it is the reader's business. */
	public interface Reader
	{
		/** @return the frame, or null when there is none at {@code t} */
		Frame read( int t );

		/**
		 * The frame at the resolution {@link org.mastodon.mamut.automahout.tool.CellScale} can measure: as
		 * {@link #read} when it fits, block-averaged when it is too large. A reader of a multi-resolution source
		 * overrides this to read a coarser level instead of the whole frame.
		 *
		 * @return the sample, or null when there is no frame at {@code t}
		 */
		default org.mastodon.mamut.automahout.tool.CellScale.Sample readForCellScale( final int t )
		{
			return org.mastodon.mamut.automahout.tool.CellScale.coarsen( read( t ) );
		}
	}
}
