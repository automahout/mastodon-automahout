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
 * The one place a detection's shape is turned into an aspect ratio.
 *
 * <p>A detection is "elongated" when it is stretched along one axis relative to another, which is
 * what a non-cell artifact looks like and a nucleus does not. The ratio was measured across all
 * three covariance diagonals, which is right for a volume and <b>wrong for a single image
 * plane</b>: there the z diagonal is not a measurement of the cell at all, it is whatever
 * placeholder extent a 2D detector reports for its one plane. On Fluo-N2DL-HeLa (one z plane,
 * 0.645 µm pixels) a perfectly round nucleus of radius ~6.4 µm therefore read as aspect ~10 —
 * comfortably past the 6.0 cut — and the run's own log said so twice per window:
 * {@code elongated=98%}, and a flat-contrast shape report whose KEPT spots averaged aspect 9.88.
 * Every detection in the movie was elongated by definition, so the quality gate could never be
 * satisfied and the malformed-spot filter had every spot in the frame in its sights.</p>
 *
 * <p>On a single plane the ratio is therefore taken <b>in-plane only</b>. That keeps the check
 * measuring what it claims to — a detection stretched within the image — and leaves the volumetric
 * behaviour exactly as it was.</p>
 */
public final class SpotShape
{
	private SpotShape()
	{}

	/**
	 * Longest radius over shortest radius for {@code cov}, taken over all three axes on a volume and
	 * in-plane only on a single plane.
	 *
	 * <p>Returns {@code 1.0} (perfectly round, so never elongated) when the shortest radius is not
	 * positive. A degenerate covariance says nothing about shape, and the check it feeds deletes
	 * spots — so it fails open, exactly as the per-site code it replaces did.</p>
	 *
	 * @param cov         3x3 covariance; only the diagonal is read
	 * @param volumetric  whether the dataset has a real z axis ({@code ToolContext.isVolumetric()})
	 */
	public static double aspectRatio( final double[][] cov, final boolean volumetric )
	{
		final double d0 = cov[ 0 ][ 0 ], d1 = cov[ 1 ][ 1 ], d2 = cov[ 2 ][ 2 ];
		final double maxD = volumetric ? Math.max( d0, Math.max( d1, d2 ) ) : Math.max( d0, d1 );
		final double minD = volumetric ? Math.min( d0, Math.min( d1, d2 ) ) : Math.min( d0, d1 );
		if ( !( minD > 0.0 ) )
			return 1.0;
		return Math.sqrt( maxD / minD );
	}

	/**
	 * Whether {@code cov} is elongated past {@code maxAspectRatio}. A ratio of {@code 0} or less
	 * switches the check off, as the {@code max_aspect_ratio} parameter documents.
	 */
	public static boolean isElongated( final double[][] cov, final double maxAspectRatio,
			final boolean volumetric )
	{
		return maxAspectRatio > 0.0 && aspectRatio( cov, volumetric ) > maxAspectRatio;
	}

	/** Size of a detection: the root of its covariance trace, in world units. */
	/**
	 * The radius of the circle or sphere of the SAME AREA or VOLUME as this spot.
	 *
	 * <p>What a radius should mean when something samples a spot's INTERIOR. The bounding-sphere
	 * radius Mastodon reports is the LARGEST semi-axis, so on an elongated spot the interior offsets
	 * — which are laid out on a sphere — land outside the body along the short axis and read
	 * background. That does not matter while every detection is a circle, and stops being true the
	 * moment {@code fit.ellipse} runs: these cells are elongated 1.40x at the median.</p>
	 *
	 * <p>Exactly equal to the radius for a circle or sphere, so this is not a change of behaviour for
	 * anything round. Closed form: the semi-axes are the square roots of the eigenvalues, so their
	 * product is the square root of the determinant and no decomposition is needed.</p>
	 */
	public static double interiorRadius( final double[][] cov, final boolean volumetric )
	{
		if ( cov == null )
			return Double.NaN;
		if ( volumetric )
		{
			final double det = cov[ 0 ][ 0 ] * ( cov[ 1 ][ 1 ] * cov[ 2 ][ 2 ] - cov[ 1 ][ 2 ] * cov[ 2 ][ 1 ] )
					- cov[ 0 ][ 1 ] * ( cov[ 1 ][ 0 ] * cov[ 2 ][ 2 ] - cov[ 1 ][ 2 ] * cov[ 2 ][ 0 ] )
					+ cov[ 0 ][ 2 ] * ( cov[ 1 ][ 0 ] * cov[ 2 ][ 1 ] - cov[ 1 ][ 1 ] * cov[ 2 ][ 0 ] );
			return det > 0 ? Math.pow( det, 1.0 / 6.0 ) : Double.NaN;
		}
		final double det = cov[ 0 ][ 0 ] * cov[ 1 ][ 1 ] - cov[ 0 ][ 1 ] * cov[ 1 ][ 0 ];
		return det > 0 ? Math.pow( det, 0.25 ) : Double.NaN;
	}

	/** As above, falling back to {@code fallback} when the covariance says nothing usable. */
	public static double interiorRadius( final double[][] cov, final boolean volumetric,
			final double fallback )
	{
		final double r = interiorRadius( cov, volumetric );
		return Double.isFinite( r ) && r > 0 ? r : fallback;
	}

	/**
	 * The in-plane ellipse a spot's covariance describes: {@code { a, b, theta }}, {@code a >= b}.
	 *
	 * <p>The semi-axes are the square roots of the eigenvalues of the 2x2 in-plane block, and the
	 * angle is the direction of the larger one. What {@code EllipseFit.covariance} put in, taken back
	 * out, so two spots can be compared as the shapes they are rather than as the spheres that
	 * contain them.</p>
	 */
	public static double[] inPlaneEllipse( final double[][] cov )
	{
		if ( cov == null )
			return null;
		final double a = cov[ 0 ][ 0 ], b = cov[ 0 ][ 1 ], c = cov[ 1 ][ 1 ];
		final double tr = a + c, det = a * c - b * cov[ 1 ][ 0 ];
		final double root = Math.sqrt( Math.max( 0.0, tr * tr / 4.0 - det ) );
		final double big = tr / 2.0 + root, small = tr / 2.0 - root;
		if ( !( big > 0 ) || !( small > 0 ) )
			return null;
		final double theta = Math.abs( b ) < 1e-12 && Math.abs( a - c ) < 1e-12
				? 0.0
				: 0.5 * Math.atan2( 2 * b, a - c );
		return new double[] { Math.sqrt( big ), Math.sqrt( small ), theta };
	}

	public static double size( final double[][] cov )
	{
		return Math.sqrt( cov[ 0 ][ 0 ] + cov[ 1 ][ 1 ] + cov[ 2 ][ 2 ] );
	}
}
