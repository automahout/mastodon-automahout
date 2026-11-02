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
 * One ellipse or two, fitted to the SHAPE of the object under a detection. Pure geometry, no image
 * access -- the caller samples the window.
 *
 * <p>Replaces counting intensity basins ({@code SeedToObject}) as the way a split places its pieces.
 * Measured against Fluo-N2DL-HeLa 01 silver truth on the detector's own detections: of the
 * detections that genuinely covered two cells and that the old pass split, it put each piece on its
 * own cell for 5 of 19 -- the rest put two pieces on one cell or one on background, and 10 of the 19
 * came out in THREE pieces. A two-component fit to the thresholded object's pixels put the two
 * centres on two different truth cells for 228 of 257 (89%).</p>
 *
 * <p>The object is the thresholded component with the most pixels inside the detection's disc. The
 * fit is plain EM on its pixel coordinates, seeded either side of the centroid along the main axis.
 * Each component is then read as a UNIFORM ellipse with the same second moments (semi-axes 2 sqrt of
 * the eigenvalues), which is what a nucleus is; {@link Result#iou1} and {@link Result#iou2} are how
 * well one such ellipse, and the union of two, cover the object. A Gaussian mixture on its own
 * always prefers two components on a uniform blob, so its likelihood is not the evidence; the shape
 * is.</p>
 *
 * <p>Mirrors {@code benchmark/split-review/split_features.py} step for step, including
 * {@code numpy.cov}'s N-1 for the one-component covariance and the 0.25 px^2 regularisation; the
 * parity harness holds the two together.</p>
 */
public final class ShapeSplit
{
	/** EM iterations, as the prototype. */
	public static final int ITERATIONS = 40;

	/** Fewest pixels an object may have to be fitted at all. */
	public static final int MIN_PIXELS = 20;

	/** A component left with fewer pixels than this is not a piece; the fit is refused. */
	public static final double MIN_COMPONENT_PIXELS = 5.0;

	/** Added to every covariance diagonal, in px^2, so a one-pixel-wide component stays invertible. */
	public static final double REGULARISATION = 0.25;

	private ShapeSplit()
	{}

	/** What the window holds. Positions are in window pixels: x = column, y = row. */
	public static final class Result
	{
		/** Pixels in the object. */
		public final int area;

		/** IoU of the object with its one moment ellipse. */
		public final double iou1;

		/** IoU of the object with the union of the two component ellipses; NaN when the fit was refused. */
		public final double iou2;

		/** One-component centre {x, y} and covariance {xx, xy, yy}. */
		public final double[] mean1, cov1;

		/** Two-component centres {x, y} and covariances {xx, xy, yy}; null when refused. */
		public final double[][] means, covs;

		Result( final int area, final double iou1, final double iou2, final double[] mean1,
				final double[] cov1, final double[][] means, final double[][] covs )
		{
			this.area = area;
			this.iou1 = iou1;
			this.iou2 = iou2;
			this.mean1 = mean1;
			this.cov1 = cov1;
			this.means = means;
			this.covs = covs;
		}

		/** Whether two components were fitted. */
		public boolean twoFitted()
		{
			return means != null;
		}

		/** How much the second ellipse improves the cover of the object; NaN when refused. */
		public double gain()
		{
			return iou2 - iou1;
		}

		/** Distance between the two centres, in window pixels; NaN when refused. */
		public double separation()
		{
			return means == null ? Double.NaN
					: Math.hypot( means[ 0 ][ 0 ] - means[ 1 ][ 0 ], means[ 0 ][ 1 ] - means[ 1 ][ 1 ] );
		}
	}

	/**
	 * @param window row-major, {@code w * h}
	 * @param level  a pixel belongs to an object when STRICTLY above this
	 * @param cx     detection centre, window pixels (column)
	 * @param cy     detection centre, window pixels (row)
	 * @param r      detection radius, window pixels
	 * @return null when there is no object under the detection or it is too small to fit
	 */
	public static Result analyse( final double[] window, final int w, final int h, final double level,
			final double cx, final double cy, final double r )
	{
		return analyse( window, w, h, level, cx, cy, r, null );
	}

	/**
	 * As {@link #analyse(double[], int, int, double, double, double, double)}, with the two components
	 * started at {@code seeds} (window pixels, {@code {{x0, y0}, {x1, y1}}}) instead of either side of
	 * the centroid along the main axis. Component {@code k} of the result grew from seed {@code k}.
	 *
	 * <p>For when something other than the shape already says there are two cells and roughly where:
	 * the frames before a merge. The fit then only has to say where they are now. Measured on
	 * Fluo-N2DL-HeLa merges against silver truth, see
	 * {@code benchmark/lineage-review/evaluate_convergence_repair.py}.</p>
	 */
	public static Result analyse( final double[] window, final int w, final int h, final double level,
			final double cx, final double cy, final double r, final double[][] seeds )
	{
		final boolean[] obj = objectUnder( window, w, h, level, cx, cy, r );
		if ( obj == null )
			return null;
		int n = 0;
		for ( final boolean b : obj )
			if ( b )
				n++;
		if ( n < MIN_PIXELS )
			return null;
		final double[][] X = new double[ n ][ 2 ];
		for ( int i = 0, k = 0; i < obj.length; i++ )
			if ( obj[ i ] )
			{
				X[ k ][ 0 ] = i % w;
				X[ k ][ 1 ] = i / w;
				k++;
			}

		// One component: mean, and numpy.cov's unbiased covariance.
		final double[] mu1 = new double[ 2 ];
		for ( final double[] x : X )
		{
			mu1[ 0 ] += x[ 0 ];
			mu1[ 1 ] += x[ 1 ];
		}
		mu1[ 0 ] /= n;
		mu1[ 1 ] /= n;
		double sxx = 0, sxy = 0, syy = 0;
		for ( final double[] x : X )
		{
			final double dx = x[ 0 ] - mu1[ 0 ], dy = x[ 1 ] - mu1[ 1 ];
			sxx += dx * dx;
			sxy += dx * dy;
			syy += dy * dy;
		}
		final double[] c1 = { sxx / ( n - 1 ) + REGULARISATION, sxy / ( n - 1 ), syy / ( n - 1 ) + REGULARISATION };
		final double iou1 = iou( obj, w, h, new double[][] { mu1 }, new double[][] { c1 } );

		final double[][][] fit = twoComponents( X, mu1, c1, seeds );
		if ( fit == null )
			return new Result( n, iou1, Double.NaN, mu1, c1, null, null );
		final double iou2 = iou( obj, w, h, fit[ 0 ], fit[ 1 ] );
		return new Result( n, iou1, iou2, mu1, c1, fit[ 0 ], fit[ 1 ] );
	}

	/**
	 * The thresholded 4-connected component with the most pixels inside the disc, or null. Matches
	 * {@code scipy.ndimage.label}'s default structure.
	 */
	static boolean[] objectUnder( final double[] window, final int w, final int h, final double level,
			final double cx, final double cy, final double r )
	{
		final int[] label = new int[ w * h ];
		final int[] stack = new int[ w * h ];
		int labels = 0;
		int best = 0, bestInside = 0;
		for ( int start = 0; start < w * h; start++ )
		{
			if ( label[ start ] != 0 || !( window[ start ] > level ) )
				continue;
			final int id = ++labels;
			int inside = 0, top = 0;
			stack[ top++ ] = start;
			label[ start ] = id;
			while ( top > 0 )
			{
				final int p = stack[ --top ];
				final int x = p % w, y = p / w;
				final double dx = x - cx, dy = y - cy;
				if ( dx * dx + dy * dy < r * r )
					inside++;
				if ( x > 0 )
					top = visit( window, label, stack, top, p - 1, id, level );
				if ( x < w - 1 )
					top = visit( window, label, stack, top, p + 1, id, level );
				if ( y > 0 )
					top = visit( window, label, stack, top, p - w, id, level );
				if ( y < h - 1 )
					top = visit( window, label, stack, top, p + w, id, level );
			}
			// Strictly greater, so the lowest label wins a tie -- numpy's argmax.
			if ( inside > bestInside )
			{
				bestInside = inside;
				best = id;
			}
		}
		if ( best == 0 )
			return null;
		final boolean[] obj = new boolean[ w * h ];
		for ( int i = 0; i < obj.length; i++ )
			obj[ i ] = label[ i ] == best;
		return obj;
	}

	private static int visit( final double[] window, final int[] label, final int[] stack, int top,
			final int q, final int id, final double level )
	{
		if ( label[ q ] == 0 && window[ q ] > level )
		{
			label[ q ] = id;
			stack[ top++ ] = q;
		}
		return top;
	}

	/** {means, covs} after EM, or null when a component collapsed. */
	static double[][][] twoComponents( final double[][] X, final double[] mu1, final double[] c1,
			final double[][] seeds )
	{
		final int n = X.length;
		final double[][] mu;
		if ( seeds != null )
			mu = new double[][] { seeds[ 0 ].clone(), seeds[ 1 ].clone() };
		else
		{
			// Seeded either side of the centroid along the main axis, one standard deviation out.
			final double[] eig = eigen( c1 );
			final double ax = eig[ 2 ] * Math.sqrt( eig[ 0 ] ), ay = eig[ 3 ] * Math.sqrt( eig[ 0 ] );
			mu = new double[][] { { mu1[ 0 ] - ax, mu1[ 1 ] - ay }, { mu1[ 0 ] + ax, mu1[ 1 ] + ay } };
		}
		final double[][] cov = { { c1[ 0 ] / 2, c1[ 1 ] / 2, c1[ 2 ] / 2 }, { c1[ 0 ] / 2, c1[ 1 ] / 2, c1[ 2 ] / 2 } };
		final double[] pi = { 0.5, 0.5 };
		final double[][] resp = new double[ n ][ 2 ];
		for ( int it = 0; it < ITERATIONS; it++ )
		{
			for ( int i = 0; i < n; i++ )
			{
				final double l0 = Math.log( pi[ 0 ] ) + logGauss( X[ i ], mu[ 0 ], cov[ 0 ] );
				final double l1 = Math.log( pi[ 1 ] ) + logGauss( X[ i ], mu[ 1 ], cov[ 1 ] );
				final double m = Math.max( l0, l1 );
				final double e0 = Math.exp( l0 - m ), e1 = Math.exp( l1 - m );
				resp[ i ][ 0 ] = e0 / ( e0 + e1 );
				resp[ i ][ 1 ] = e1 / ( e0 + e1 );
			}
			final double[] nk = new double[ 2 ];
			for ( int i = 0; i < n; i++ )
			{
				nk[ 0 ] += resp[ i ][ 0 ];
				nk[ 1 ] += resp[ i ][ 1 ];
			}
			if ( nk[ 0 ] < MIN_COMPONENT_PIXELS || nk[ 1 ] < MIN_COMPONENT_PIXELS )
				return null;
			for ( int k = 0; k < 2; k++ )
			{
				pi[ k ] = nk[ k ] / ( nk[ 0 ] + nk[ 1 ] );
				double mx = 0, my = 0;
				for ( int i = 0; i < n; i++ )
				{
					mx += resp[ i ][ k ] * X[ i ][ 0 ];
					my += resp[ i ][ k ] * X[ i ][ 1 ];
				}
				mu[ k ][ 0 ] = mx / nk[ k ];
				mu[ k ][ 1 ] = my / nk[ k ];
				double xx = 0, xy = 0, yy = 0;
				for ( int i = 0; i < n; i++ )
				{
					final double dx = X[ i ][ 0 ] - mu[ k ][ 0 ], dy = X[ i ][ 1 ] - mu[ k ][ 1 ];
					xx += resp[ i ][ k ] * dx * dx;
					xy += resp[ i ][ k ] * dx * dy;
					yy += resp[ i ][ k ] * dy * dy;
				}
				cov[ k ][ 0 ] = xx / nk[ k ] + REGULARISATION;
				cov[ k ][ 1 ] = xy / nk[ k ];
				cov[ k ][ 2 ] = yy / nk[ k ] + REGULARISATION;
			}
		}
		return new double[][][] { mu, cov };
	}

	static double logGauss( final double[] x, final double[] mu, final double[] c )
	{
		final double det = c[ 0 ] * c[ 2 ] - c[ 1 ] * c[ 1 ];
		final double dx = x[ 0 ] - mu[ 0 ], dy = x[ 1 ] - mu[ 1 ];
		final double q = ( c[ 2 ] * dx * dx - 2 * c[ 1 ] * dx * dy + c[ 0 ] * dy * dy ) / det;
		return -0.5 * q - 0.5 * Math.log( det ) - Math.log( 2 * Math.PI );
	}

	/** {largest eigenvalue, smallest, x and y of the largest's unit eigenvector}. */
	static double[] eigen( final double[] c )
	{
		final double a = c[ 0 ], b = c[ 1 ], d = c[ 2 ];
		final double tr = a + d, det = a * d - b * b;
		final double root = Math.sqrt( Math.max( 0.0, tr * tr / 4 - det ) );
		final double big = tr / 2 + root, small = tr / 2 - root;
		double vx, vy;
		if ( Math.abs( b ) > 1e-12 )
		{
			vx = big - d;
			vy = b;
		}
		else if ( a >= d )
		{
			vx = 1;
			vy = 0;
		}
		else
		{
			vx = 0;
			vy = 1;
		}
		final double len = Math.hypot( vx, vy );
		return new double[] { big, small, vx / len, vy / len };
	}

	/**
	 * Whether pixel (x, y) is inside the uniform ellipse with these second moments: semi-axes
	 * 2 sqrt(eigenvalues), i.e. {@code d^T (4C)^-1 d <= 1}.
	 */
	static boolean insideEllipse( final double x, final double y, final double[] mu, final double[] c )
	{
		final double a = 4 * c[ 0 ], b = 4 * c[ 1 ], d = 4 * c[ 2 ];
		final double det = a * d - b * b;
		final double dx = x - mu[ 0 ], dy = y - mu[ 1 ];
		return ( d * dx * dx - 2 * b * dx * dy + a * dy * dy ) / det <= 1.0;
	}

	/** IoU of the object with the union of these ellipses, over the window grid. */
	static double iou( final boolean[] obj, final int w, final int h, final double[][] means,
			final double[][] covs )
	{
		int inter = 0, union = 0;
		for ( int i = 0; i < w * h; i++ )
		{
			final int x = i % w, y = i / w;
			boolean in = false;
			for ( int k = 0; k < means.length && !in; k++ )
				in = insideEllipse( x, y, means[ k ], covs[ k ] );
			if ( in && obj[ i ] )
				inter++;
			if ( in || obj[ i ] )
				union++;
		}
		return union == 0 ? 0.0 : inter / ( double ) union;
	}
}
