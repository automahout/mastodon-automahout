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

/**
 * Two ellipsoids fitted to the voxels of the object under a detection, the 3-D counterpart of
 * {@link ShapeSplit}'s seeded fit. Pure geometry, no image access: the caller passes the world positions of
 * the voxels above its brightness level inside (a little beyond) the detection.
 *
 * <p>Plain EM for two Gaussian components, each started at its seed: the merge repair knows from the frame
 * before where the two cells are, so the shape does not have to say it alone. Each component is read as a
 * UNIFORM ellipsoid with its second moments, whose squared semi-axes are 5 times the variances along its
 * axes -- the factor that makes a Mastodon spot covariance (squared semi-axes as eigenvalues).</p>
 */
public final class ShapeSplit3D
{
	/** EM iterations, as {@link ShapeSplit}. */
	public static final int ITERATIONS = 40;

	/** Fewest voxels an object may have to be fitted at all. */
	public static final int MIN_VOXELS = 40;

	/** A component left with less than this share of the voxels is not a piece; the fit is refused. */
	public static final double MIN_COMPONENT_SHARE = 0.15;

	/** A uniform ellipsoid's squared semi-axis over its variance along that axis. */
	public static final double UNIFORM_ELLIPSOID = 5.0;

	private ShapeSplit3D()
	{}

	/** Two pieces, as spot centres and covariances in world units, plus why a fit was refused. */
	public static final class Result
	{
		/** Centres {x, y, z}; null when refused. */
		public final double[][] means;

		/** Spot covariances (squared semi-axes); null when refused. */
		public final double[][][] covs;

		/** Why the fit was refused; null when it was not. */
		public final String refusal;

		Result( final double[][] means, final double[][][] covs, final String refusal )
		{
			this.means = means;
			this.covs = covs;
			this.refusal = refusal;
		}

		public boolean twoFitted()
		{
			return means != null;
		}

		/** Distance between the two centres; NaN when refused. */
		public double separation()
		{
			if ( means == null )
				return Double.NaN;
			double d = 0;
			for ( int k = 0; k < 3; k++ )
				d += Math.pow( means[ 0 ][ k ] - means[ 1 ][ k ], 2 );
			return Math.sqrt( d );
		}
	}

	/**
	 * @param points   world positions of the object's voxels, {@code n x 3}
	 * @param seeds    where the two components start, {@code {{x, y, z}, {x, y, z}}}
	 * @param variance each component's starting variance along every axis (world units squared)
	 * @param floor    added to every covariance diagonal, so a one-voxel-thin component stays invertible
	 */
	public static Result analyse( final double[][] points, final double[][] seeds, final double variance,
			final double floor )
	{
		final int n = points.length;
		if ( n < MIN_VOXELS )
			return new Result( null, null, "no object (" + n + " voxels above the level)" );
		final double[][] mean = { seeds[ 0 ].clone(), seeds[ 1 ].clone() };
		final double[][][] cov = new double[ 2 ][ 3 ][ 3 ];
		for ( int c = 0; c < 2; c++ )
			for ( int k = 0; k < 3; k++ )
				cov[ c ][ k ][ k ] = variance;
		final double[] weight = { 0.5, 0.5 };
		final double[][] resp = new double[ n ][ 2 ];
		for ( int it = 0; it < ITERATIONS; it++ )
		{
			// E: each voxel's responsibility to each component, in log space against underflow.
			final double[][][] inv = new double[ 2 ][][];
			final double[] logNorm = new double[ 2 ];
			for ( int c = 0; c < 2; c++ )
			{
				inv[ c ] = invert3( cov[ c ] );
				final double det = det3( cov[ c ] );
				if ( inv[ c ] == null || !( det > 0 ) )
					return new Result( null, null, "one component collapsed" );
				logNorm[ c ] = Math.log( weight[ c ] ) - 0.5 * Math.log( det );
			}
			for ( int i = 0; i < n; i++ )
			{
				final double l0 = logNorm[ 0 ] - 0.5 * mahalanobis2( points[ i ], mean[ 0 ], inv[ 0 ] );
				final double l1 = logNorm[ 1 ] - 0.5 * mahalanobis2( points[ i ], mean[ 1 ], inv[ 1 ] );
				final double m = Math.max( l0, l1 );
				final double e0 = Math.exp( l0 - m ), e1 = Math.exp( l1 - m );
				resp[ i ][ 0 ] = e0 / ( e0 + e1 );
				resp[ i ][ 1 ] = 1 - resp[ i ][ 0 ];
			}
			// M: weights, means, covariances.
			for ( int c = 0; c < 2; c++ )
			{
				double w = 0;
				final double[] mu = new double[ 3 ];
				for ( int i = 0; i < n; i++ )
				{
					w += resp[ i ][ c ];
					for ( int k = 0; k < 3; k++ )
						mu[ k ] += resp[ i ][ c ] * points[ i ][ k ];
				}
				if ( w < MIN_COMPONENT_SHARE * n )
					return new Result( null, null, "one component collapsed" );
				for ( int k = 0; k < 3; k++ )
					mu[ k ] /= w;
				final double[][] s = new double[ 3 ][ 3 ];
				for ( int i = 0; i < n; i++ )
					for ( int a = 0; a < 3; a++ )
						for ( int b = a; b < 3; b++ )
							s[ a ][ b ] += resp[ i ][ c ] * ( points[ i ][ a ] - mu[ a ] ) * ( points[ i ][ b ] - mu[ b ] );
				for ( int a = 0; a < 3; a++ )
					for ( int b = a; b < 3; b++ )
						s[ b ][ a ] = s[ a ][ b ] = s[ a ][ b ] / w + ( a == b ? floor : 0 );
				weight[ c ] = w / n;
				mean[ c ] = mu;
				cov[ c ] = s;
			}
		}
		final double[][][] spot = new double[ 2 ][ 3 ][ 3 ];
		for ( int c = 0; c < 2; c++ )
			for ( int a = 0; a < 3; a++ )
				for ( int b = 0; b < 3; b++ )
					spot[ c ][ a ][ b ] = UNIFORM_ELLIPSOID * cov[ c ][ a ][ b ];
		return new Result( mean, spot, null );
	}

	static double mahalanobis2( final double[] p, final double[] mu, final double[][] inv )
	{
		final double dx = p[ 0 ] - mu[ 0 ], dy = p[ 1 ] - mu[ 1 ], dz = p[ 2 ] - mu[ 2 ];
		return dx * ( inv[ 0 ][ 0 ] * dx + inv[ 0 ][ 1 ] * dy + inv[ 0 ][ 2 ] * dz )
				+ dy * ( inv[ 1 ][ 0 ] * dx + inv[ 1 ][ 1 ] * dy + inv[ 1 ][ 2 ] * dz )
				+ dz * ( inv[ 2 ][ 0 ] * dx + inv[ 2 ][ 1 ] * dy + inv[ 2 ][ 2 ] * dz );
	}

	static double det3( final double[][] m )
	{
		return m[ 0 ][ 0 ] * ( m[ 1 ][ 1 ] * m[ 2 ][ 2 ] - m[ 1 ][ 2 ] * m[ 2 ][ 1 ] )
				- m[ 0 ][ 1 ] * ( m[ 1 ][ 0 ] * m[ 2 ][ 2 ] - m[ 1 ][ 2 ] * m[ 2 ][ 0 ] )
				+ m[ 0 ][ 2 ] * ( m[ 1 ][ 0 ] * m[ 2 ][ 1 ] - m[ 1 ][ 1 ] * m[ 2 ][ 0 ] );
	}

	/** The squared Mahalanobis distance of {@code p} from {@code mu} under the inverse covariance {@code inv}. */
	public static double mahalanobis2Public( final double[] p, final double[] mu, final double[][] inv )
	{
		return mahalanobis2( p, mu, inv );
	}

	/** The determinant of a 3x3 matrix. */
	public static double det3Public( final double[][] m )
	{
		return det3( m );
	}

	/** The inverse of a 3x3 matrix; null when it is singular. */
	public static double[][] invert3( final double[][] m )
	{
		final double det = det3( m );
		if ( !( Math.abs( det ) > 0 ) )
			return null;
		final double[][] r = new double[ 3 ][ 3 ];
		r[ 0 ][ 0 ] = ( m[ 1 ][ 1 ] * m[ 2 ][ 2 ] - m[ 1 ][ 2 ] * m[ 2 ][ 1 ] ) / det;
		r[ 0 ][ 1 ] = ( m[ 0 ][ 2 ] * m[ 2 ][ 1 ] - m[ 0 ][ 1 ] * m[ 2 ][ 2 ] ) / det;
		r[ 0 ][ 2 ] = ( m[ 0 ][ 1 ] * m[ 1 ][ 2 ] - m[ 0 ][ 2 ] * m[ 1 ][ 1 ] ) / det;
		r[ 1 ][ 0 ] = ( m[ 1 ][ 2 ] * m[ 2 ][ 0 ] - m[ 1 ][ 0 ] * m[ 2 ][ 2 ] ) / det;
		r[ 1 ][ 1 ] = ( m[ 0 ][ 0 ] * m[ 2 ][ 2 ] - m[ 0 ][ 2 ] * m[ 2 ][ 0 ] ) / det;
		r[ 1 ][ 2 ] = ( m[ 0 ][ 2 ] * m[ 1 ][ 0 ] - m[ 0 ][ 0 ] * m[ 1 ][ 2 ] ) / det;
		r[ 2 ][ 0 ] = ( m[ 1 ][ 0 ] * m[ 2 ][ 1 ] - m[ 1 ][ 1 ] * m[ 2 ][ 0 ] ) / det;
		r[ 2 ][ 1 ] = ( m[ 0 ][ 1 ] * m[ 2 ][ 0 ] - m[ 0 ][ 0 ] * m[ 2 ][ 1 ] ) / det;
		r[ 2 ][ 2 ] = ( m[ 0 ][ 0 ] * m[ 1 ][ 1 ] - m[ 0 ][ 1 ] * m[ 1 ][ 0 ] ) / det;
		return r;
	}
}
