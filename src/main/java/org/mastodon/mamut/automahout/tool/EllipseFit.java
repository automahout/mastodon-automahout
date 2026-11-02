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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fits an ellipse to edge points, robustly, in the plane.
 *
 * <p>Why this exists rather than {@code mastodon-ellipsoid-fitting}: that library solves for a NINE
 * degree-of-freedom ellipsoid and needs a stack to do it. Measured
 * ({@code EllipsoidFittingViabilityTest}), it fits a 32-plane blob and refuses a 5-plane one, and on
 * a single plane its edgel detection returns nothing at all. A plane is a FIVE degree-of-freedom
 * problem, which is a different and much better conditioned one.</p>
 *
 * <p>Why fit a contour at all: measured on Fluo-N2DL-HeLa_01 against CTC silver truth, the circular
 * detections this pipeline trains on overlap the cell at an IoU of 0.56 — they contain it whole
 * (recall 1.00) at 1.31x its radius, so 42% of what is taught as "cell" is not cell, and between two
 * touching cells that margin reaches into the neck. An ellipse on the cell's own axes scores 0.92,
 * and it scores 0.92 left at the DETECTOR's centre against 0.94 perfectly centred — the shape is
 * what matters, not the position. The cells are elongated 1.40x at the median, which no circle
 * follows at any radius. See {@code benchmark/annotation-extent}.</p>
 *
 * <p>Pure geometry: it takes points and normals and knows nothing about images, so it can be tested
 * against ellipses whose answer is known exactly. {@code ContourFit} is what reads an image.</p>
 *
 * <h2>Method</h2>
 *
 * <p>RANSAC over minimal samples of five points. The general conic
 * {@code A x^2 + B xy + C y^2 + D x + E y + F = 0} has five free parameters once scaled, and with
 * the seed translated to the origin — which is INSIDE the cell, or there would be nothing to fit —
 * {@code F} cannot be zero, so fixing {@code F = -1} is safe and turns each sample into a 5x5 linear
 * solve. Candidates that are not ellipses, or whose centre has wandered, or whose axes are
 * implausible, are rejected before scoring. The winner is refitted by least squares on its inliers,
 * which is where most of the accuracy comes from.</p>
 *
 * <p>Scoring uses BOTH the distance from the curve and the agreement between the edge point's own
 * normal and the curve's. The normal is half the evidence an edge carries and the reason a
 * neighbouring cell's edge does not quietly join the fit.</p>
 */
public final class EllipseFit
{

	/** A fitted ellipse, in the coordinate frame the points were given in. */
	public static final class Ellipse
	{

		/** Centre. */
		public final double x, y;

		/** Semi-axes, {@code a >= b}. */
		public final double a, b;

		/** Angle of the major axis, radians. */
		public final double theta;

		/** How many of the points offered supported this fit. */
		public final int inliers;

		Ellipse( final double x, final double y, final double a, final double b, final double theta,
				final int inliers )
		{
			this.x = x;
			this.y = y;
			this.a = a;
			this.b = b;
			this.theta = theta;
			this.inliers = inliers;
		}

		/**
		 * The ellipse whose Mastodon covariance in plane is {@code cxx, cxy, cyy} (semi-axes the square roots of its
		 * eigenvalues), centred at {@code x, y}: for a shape measured elsewhere, such as a mask's moments.
		 */
		public static Ellipse ofCovariance( final double x, final double y, final double cxx, final double cxy,
				final double cyy, final int inliers )
		{
			final double tr = cxx + cyy, det = cxx * cyy - cxy * cxy;
			final double d = Math.sqrt( Math.max( 0, tr * tr / 4 - det ) );
			final double l1 = tr / 2 + d, l2 = Math.max( 0, tr / 2 - d );
			return new Ellipse( x, y, Math.sqrt( l1 ), Math.sqrt( l2 ), 0.5 * Math.atan2( 2 * cxy, cxx - cyy ), inliers );
		}

		/** {@code a / b}, at least 1. */
		public double elongation()
		{
			return b > 0 ? a / b : Double.POSITIVE_INFINITY;
		}

		/**
		 * The 3x3 covariance a Mastodon spot carries, with {@code zRadius} out of plane.
		 *
		 * <p>Mastodon stores a spot as the covariance of an ellipsoid, where the semi-axes are the
		 * square roots of the eigenvalues. In plane that is this ellipse rotated by {@link #theta}; out
		 * of plane there is nothing to fit on one z slice, so the caller says what to use — normally
		 * the radius the detection already had, which keeps a 2-D fit from asserting anything about a
		 * third dimension it never saw.</p>
		 */
		public double[][] covariance( final double zRadius )
		{
			final double c = Math.cos( theta ), s = Math.sin( theta );
			final double aa = a * a, bb = b * b;
			final double zz = zRadius * zRadius;
			return new double[][] {
					{ aa * c * c + bb * s * s, ( aa - bb ) * c * s, 0 },
					{ ( aa - bb ) * c * s, aa * s * s + bb * c * c, 0 },
					{ 0, 0, zz } };
		}

		/**
		 * This ellipse in plane, with the out-of-plane shape of {@code original} kept.
		 *
		 * <p>For volumetric data. {@link #covariance(double)} sets the z radius to whatever it is
		 * given and the tilt out of the plane to zero, and both callers gave it the detection's
		 * BOUNDING radius -- its longest axis -- although their comments said the out-of-plane radius
		 * was the one the detection already had. On one plane that is harmless; on a z stack it
		 * stretched every flat or tilted nucleus to its longest axis in z and taught that shape. Here
		 * the z variance and the xz / yz terms are the detection's own. When the new in-plane block
		 * and the old tilt no longer make a positive-definite matrix the tilt is dropped, never the
		 * fit.</p>
		 */
		public double[][] covarianceKeepingDepth( final double[][] original )
		{
			final double[][] cov = covariance( Math.sqrt( Math.max( 0.0, original[ 2 ][ 2 ] ) ) );
			cov[ 0 ][ 2 ] = cov[ 2 ][ 0 ] = original[ 0 ][ 2 ];
			cov[ 1 ][ 2 ] = cov[ 2 ][ 1 ] = original[ 1 ][ 2 ];
			if ( !positiveDefinite( cov ) )
			{
				cov[ 0 ][ 2 ] = cov[ 2 ][ 0 ] = 0;
				cov[ 1 ][ 2 ] = cov[ 2 ][ 1 ] = 0;
			}
			return cov;
		}

		@Override
		public String toString()
		{
			return String.format( "Ellipse[centre=(%.2f, %.2f) axes=%.2f/%.2f theta=%.1f deg n=%d]",
					x, y, a, b, Math.toDegrees( theta ), inliers );
		}
	}

	/** What a fit will accept, so a caller can say it rather than discover it. */
	public static final class Limits
	{

		/** How far the fitted centre may sit from the seed. */
		public double maxCentreDistance = 6.0;

		/** Smallest and largest semi-axis entertained. */
		public double minRadius = 1.0, maxRadius = 60.0;

		/** Largest major/minor ratio; above this the "ellipse" is a sliver through noise. */
		public double maxElongation = 4.0;

		/** How far off the curve a point may sit and still support it. */
		public double inlierDistance = 1.5;

		/** How far the point's own normal may point away from the curve's, in radians. */
		public double inlierAngle = Math.toRadians( 30 );

		/** Fewest supporting points for a fit to be returned at all. */
		public int minInliers = 12;

		/** Minimal samples drawn. */
		public int iterations = 200;
	}

	/** Fewest points from which anything can be said. */
	public static final int MIN_POINTS = 5;

	/**
	 * Fits an ellipse to {@code points} near {@code seed}.
	 *
	 * <p>Deterministic: the sampling is seeded from the point count and the seed position, so the same
	 * input gives the same answer. A pipeline whose output changes between runs cannot be debugged by
	 * comparing two runs, and this is called once per detection per timepoint.</p>
	 *
	 * @param points rows of {@code { x, y, nx, ny }} — position and unit normal of an edge point. The
	 *               normal's SIGN is not used, only its axis, because whether a gradient points into
	 *               or out of a cell depends on the labelling.
	 * @param seed   where the detection sits; the fit is anchored near it
	 * @param limits what to accept, or {@code null} for the defaults
	 * @return the ellipse, or {@code null} when nothing plausible was supported
	 */
	public static Ellipse fit( final double[][] points, final double[] seed, final Limits limits )
	{
		final Limits lim = limits == null ? new Limits() : limits;
		if ( points == null || points.length < MIN_POINTS || seed == null || seed.length < 2 )
			return null;
		// Translated so the seed is the origin, which is what makes F = -1 safe: the seed is inside
		// the cell, so the curve cannot pass through it.
		final double[][] p = new double[ points.length ][ 4 ];
		for ( int i = 0; i < points.length; i++ )
		{
			p[ i ][ 0 ] = points[ i ][ 0 ] - seed[ 0 ];
			p[ i ][ 1 ] = points[ i ][ 1 ] - seed[ 1 ];
			p[ i ][ 2 ] = points[ i ].length > 2 ? points[ i ][ 2 ] : 0.0;
			p[ i ][ 3 ] = points[ i ].length > 3 ? points[ i ][ 3 ] : 0.0;
		}
		final Random rnd = new Random( 31L * points.length
				+ Double.hashCode( seed[ 0 ] ) * 17L + Double.hashCode( seed[ 1 ] ) );
		double[] best = null;
		int bestCount = 0;
		final int[] pick = new int[ MIN_POINTS ];
		for ( int it = 0; it < lim.iterations; it++ )
		{
			if ( !distinct( rnd, pick, p.length ) )
				continue;
			final double[] conic = conicThrough( p, pick );
			if ( conic == null || !plausible( conic, lim ) )
				continue;
			final int count = countInliers( p, conic, lim, null );
			if ( count > bestCount )
			{
				bestCount = count;
				best = conic;
			}
		}
		if ( best == null || bestCount < lim.minInliers )
			return null;
		// Refit on everything that agreed. The minimal sample only has to find the right POINTS; the
		// accuracy comes from here, and without it the answer is whatever five points happened to win.
		final List< double[] > inliers = new ArrayList<>();
		countInliers( p, best, lim, inliers );
		final double[] refined = leastSquaresConic( inliers );
		final double[] conic = refined != null && plausible( refined, lim ) ? refined : best;
		final int finalCount = countInliers( p, conic, lim, null );
		if ( finalCount < lim.minInliers )
			return null;
		return toEllipse( conic, seed, finalCount );
	}

	// ----------------------------------------------------------------- the conic

	/** Solves {@code A x^2 + B xy + C y^2 + D x + E y = 1} through the five picked points. */
	private static double[] conicThrough( final double[][] p, final int[] pick )
	{
		final double[][] m = new double[ MIN_POINTS ][ MIN_POINTS + 1 ];
		for ( int r = 0; r < MIN_POINTS; r++ )
		{
			final double x = p[ pick[ r ] ][ 0 ], y = p[ pick[ r ] ][ 1 ];
			m[ r ][ 0 ] = x * x;
			m[ r ][ 1 ] = x * y;
			m[ r ][ 2 ] = y * y;
			m[ r ][ 3 ] = x;
			m[ r ][ 4 ] = y;
			m[ r ][ 5 ] = 1.0;
		}
		final double[] s = solve( m );
		// {A, B, C, D, E, F} with F = -1, so that the algebraic value is zero ON the curve.
		return s == null ? null : new double[] { s[ 0 ], s[ 1 ], s[ 2 ], s[ 3 ], s[ 4 ], -1.0 };
	}

	/** Same equation, over every inlier, by normal equations. */
	static double[] leastSquaresConic( final List< double[] > inliers )
	{
		if ( inliers == null || inliers.size() < MIN_POINTS )
			return null;
		final double[][] n = new double[ MIN_POINTS ][ MIN_POINTS + 1 ];
		final double[] row = new double[ MIN_POINTS ];
		for ( final double[] q : inliers )
		{
			final double x = q[ 0 ], y = q[ 1 ];
			row[ 0 ] = x * x;
			row[ 1 ] = x * y;
			row[ 2 ] = y * y;
			row[ 3 ] = x;
			row[ 4 ] = y;
			for ( int i = 0; i < MIN_POINTS; i++ )
			{
				for ( int j = 0; j < MIN_POINTS; j++ )
					n[ i ][ j ] += row[ i ] * row[ j ];
				n[ i ][ MIN_POINTS ] += row[ i ];
			}
		}
		final double[] s = solve( n );
		return s == null ? null : new double[] { s[ 0 ], s[ 1 ], s[ 2 ], s[ 3 ], s[ 4 ], -1.0 };
	}

	/** Gaussian elimination with partial pivoting; {@code null} when singular. */
	static double[] solve( final double[][] m )
	{
		final int n = m.length;
		for ( int c = 0; c < n; c++ )
		{
			int pivot = c;
			for ( int r = c + 1; r < n; r++ )
				if ( Math.abs( m[ r ][ c ] ) > Math.abs( m[ pivot ][ c ] ) )
					pivot = r;
			if ( Math.abs( m[ pivot ][ c ] ) < 1e-12 )
				return null;
			final double[] t = m[ c ];
			m[ c ] = m[ pivot ];
			m[ pivot ] = t;
			for ( int r = 0; r < n; r++ )
			{
				if ( r == c )
					continue;
				final double f = m[ r ][ c ] / m[ c ][ c ];
				for ( int k = c; k <= n; k++ )
					m[ r ][ k ] -= f * m[ c ][ k ];
			}
		}
		final double[] out = new double[ n ];
		for ( int i = 0; i < n; i++ )
		{
			out[ i ] = m[ i ][ n ] / m[ i ][ i ];
			if ( !Double.isFinite( out[ i ] ) )
				return null;
		}
		return out;
	}

	// ----------------------------------------------------------------- scoring

	/**
	 * How many points support this conic, and optionally which.
	 *
	 * <p>Distance is the Sampson approximation — the algebraic value divided by the length of its own
	 * gradient — which is the standard first-order stand-in for the true distance to a conic and does
	 * not need the nearest point to be found. The normal test compares the point's own edge direction
	 * with the curve's gradient there, ignoring sign.</p>
	 */
	private static int countInliers( final double[][] p, final double[] q, final Limits lim,
			final List< double[] > out )
	{
		int n = 0;
		for ( final double[] pt : p )
		{
			final double x = pt[ 0 ], y = pt[ 1 ];
			final double value = q[ 0 ] * x * x + q[ 1 ] * x * y + q[ 2 ] * y * y
					+ q[ 3 ] * x + q[ 4 ] * y + q[ 5 ];
			final double gx = 2 * q[ 0 ] * x + q[ 1 ] * y + q[ 3 ];
			final double gy = q[ 1 ] * x + 2 * q[ 2 ] * y + q[ 4 ];
			final double g = Math.hypot( gx, gy );
			if ( !( g > 0 ) )
				continue;
			if ( Math.abs( value ) / g > lim.inlierDistance )
				continue;
			final double nl = Math.hypot( pt[ 2 ], pt[ 3 ] );
			if ( nl > 0 )
			{
				final double cos = Math.abs( ( pt[ 2 ] * gx + pt[ 3 ] * gy ) / ( nl * g ) );
				if ( cos < Math.cos( lim.inlierAngle ) )
					continue;
			}
			n++;
			if ( out != null )
				out.add( pt );
		}
		return n;
	}

	/** Whether a conic is an ellipse this caller would accept, without extracting it twice. */
	private static boolean plausible( final double[] q, final Limits lim )
	{
		final Ellipse e = toEllipse( q, new double[] { 0, 0 }, 0 );
		if ( e == null )
			return false;
		if ( Math.hypot( e.x, e.y ) > lim.maxCentreDistance )
			return false;
		if ( e.b < lim.minRadius || e.a > lim.maxRadius )
			return false;
		return e.elongation() <= lim.maxElongation;
	}

	/**
	 * Centre, axes and angle of {@code A x^2 + B xy + C y^2 + D x + E y + F = 0}, or {@code null} when
	 * it is not an ellipse.
	 */
	static Ellipse toEllipse( final double[] q, final double[] origin, final int inliers )
	{
		final double a = q[ 0 ], b = q[ 1 ], c = q[ 2 ], d = q[ 3 ], e = q[ 4 ], f = q[ 5 ];
		final double disc = b * b - 4 * a * c;
		if ( !( disc < 0 ) )
			return null; // a parabola or a hyperbola is not a cell
		// 2A xc + B yc + D = 0, B xc + 2C yc + E = 0
		final double det = 4 * a * c - b * b;
		final double xc = ( b * e - 2 * c * d ) / det;
		final double yc = ( b * d - 2 * a * e ) / det;
		final double fc = f + ( d * xc + e * yc ) / 2.0;
		if ( !( Math.abs( fc ) > 0 ) )
			return null;
		// Eigenvalues of [[A, B/2], [B/2, C]]; the curve is lambda1 u^2 + lambda2 v^2 = -fc.
		final double tr = a + c, dd = a * c - b * b / 4.0;
		final double root = Math.sqrt( Math.max( 0.0, tr * tr / 4.0 - dd ) );
		final double l1 = tr / 2.0 - root, l2 = tr / 2.0 + root; // l1 <= l2
		final double s1 = -fc / l1, s2 = -fc / l2;
		if ( !( s1 > 0 ) || !( s2 > 0 ) )
			return null;
		// The SMALLER eigenvalue gives the LONGER axis.
		final double major = Math.sqrt( s1 ), minor = Math.sqrt( s2 );
		if ( !Double.isFinite( major ) || !Double.isFinite( minor ) )
			return null;
		final double theta = Math.abs( b ) < 1e-12 && Math.abs( a - c ) < 1e-12
				? 0.0
				: 0.5 * Math.atan2( b, a - c ) + Math.PI / 2.0;
		return new Ellipse( origin[ 0 ] + xc, origin[ 1 ] + yc, major, minor, normaliseAngle( theta ),
				inliers );
	}

	/** Into {@code [0, pi)}: an axis has no direction, so pi and 0 are the same angle. */
	static double normaliseAngle( final double theta )
	{
		double t = theta % Math.PI;
		if ( t < 0 )
			t += Math.PI;
		return t;
	}

	// ----------------------------------------------------------------- from an image window

	/** How far out a ray looks, as a multiple of the window's half-width. */
	private static final double RAY_REACH = 1.0;

	/** Radial step, in window pixels. */
	private static final double RAY_STEP = 0.5;

	/**
	 * Edge points with normals, read off a sampled window by walking rays from {@code (cx, cy)}.
	 *
	 * <p>One window read rather than one read per ray: level-0 random access costs 150-400 ms per
	 * voxel on a chunked store, and this project has already paid for learning that. Inside the window
	 * everything is arithmetic.</p>
	 *
	 * <p>A ray that reaches the edge of the window without crossing {@code level} contributes NOTHING.
	 * It has not found an edge -- the cell continues, or a neighbour is in the way -- and recording the
	 * window boundary as a contour point is how a confluent frame reports a square cell. The same
	 * reasoning as {@code measuredCellRadius}'s rays, and the same consequence if ignored.</p>
	 *
	 * <p>The normal comes from the image gradient at the crossing, not from the ray's own direction.
	 * A ray direction would agree with any shape centred on the seed, which would make
	 * {@link #fit}'s normal test vacuous exactly where it is needed.</p>
	 *
	 * @param window row-major {@code w x h} samples
	 * @param level  the intensity at which the cell ends
	 * @param rays   how many directions to look in
	 * @return rows of {@code { x, y, nx, ny }} in window pixels, possibly empty, never {@code null}
	 */
	public static double[][] contourPoints( final double[] window, final int w, final int h,
			final double level, final double cx, final double cy, final int rays )
	{
		final List< double[] > out = new ArrayList<>();
		if ( window == null || window.length < w * h || rays < MIN_POINTS )
			return new double[ 0 ][];
		if ( !( bilinear( window, w, h, cx, cy ) > level ) )
			return new double[ 0 ][]; // the seed is not on anything
		final double reach = RAY_REACH * Math.min( w, h ) / 2.0;
		for ( int i = 0; i < rays; i++ )
		{
			final double angle = 2.0 * Math.PI * i / rays;
			final double dx = Math.cos( angle ), dy = Math.sin( angle );
			double previous = bilinear( window, w, h, cx, cy );
			double hit = Double.NaN;
			for ( double r = RAY_STEP; r <= reach; r += RAY_STEP )
			{
				final double x = cx + dx * r, y = cy + dy * r;
				if ( x < 1 || y < 1 || x > w - 2 || y > h - 2 )
					break; // off the window: not an edge, so not evidence
				final double v = bilinear( window, w, h, x, y );
				if ( v < level )
				{
					// Where it actually crossed, between this step and the last.
					final double f = previous > v ? ( previous - level ) / ( previous - v ) : 0.0;
					hit = r - RAY_STEP + f * RAY_STEP;
					break;
				}
				previous = v;
			}
			if ( Double.isNaN( hit ) )
				continue;
			final double x = cx + dx * hit, y = cy + dy * hit;
			final double gx = bilinear( window, w, h, x + 1, y ) - bilinear( window, w, h, x - 1, y );
			final double gy = bilinear( window, w, h, x, y + 1 ) - bilinear( window, w, h, x, y - 1 );
			final double g = Math.hypot( gx, gy );
			if ( !( g > 0 ) )
				continue;
			out.add( new double[] { x, y, gx / g, gy / g } );
		}
		return out.toArray( new double[ 0 ][] );
	}

	/** Bilinear sample, clamped to the window. */
	static double bilinear( final double[] window, final int w, final int h, final double x,
			final double y )
	{
		final double cx = Math.max( 0, Math.min( w - 1.0001, x ) );
		final double cy = Math.max( 0, Math.min( h - 1.0001, y ) );
		final int x0 = ( int ) cx, y0 = ( int ) cy;
		final double fx = cx - x0, fy = cy - y0;
		final double v00 = window[ y0 * w + x0 ], v10 = window[ y0 * w + x0 + 1 ];
		final double v01 = window[ ( y0 + 1 ) * w + x0 ], v11 = window[ ( y0 + 1 ) * w + x0 + 1 ];
		return v00 * ( 1 - fx ) * ( 1 - fy ) + v10 * fx * ( 1 - fy )
				+ v01 * ( 1 - fx ) * fy + v11 * fx * fy;
	}

	// ----------------------------------------------------------------- comparing two of them

	/** Grid resolution for {@link #overlap}, per axis, over the union's bounding box. */
	private static final int OVERLAP_GRID = 48;

	/**
	 * Intersection over union of two ellipses, each {@code { x, y, a, b, theta }}.
	 *
	 * <p>What separates one cell detected twice from two cells detected once. Measured over every
	 * overlapping pair in a frame, labelled by whether both sit on the same CTC truth cell
	 * ({@code benchmark/annotation-extent}):</p>
	 *
	 * <pre>
	 *   overlapping pair          p10    p50    p90
	 *   same cell (444)          0.87   0.98   1.00
	 *   different cells (29)     0.00   0.02   0.53
	 * </pre>
	 *
	 * <p>Nearly binary. It was not, when the detections were circles 1.31x too big: "the bounding
	 * spheres touch" is equally true of two real neighbours, which is why merging on that evidence
	 * drew one ellipse around two resolved nuclei and had to be switched off.</p>
	 *
	 * <p>Sampled on a grid rather than solved: two conics intersect in up to four points and the
	 * exact area is a sum of circular-segment and polygon pieces with several degenerate cases. A
	 * {@value #OVERLAP_GRID}-square grid over the union's bounding box is accurate to about a percent,
	 * which is nothing against a decision boundary this wide, and it cannot be got subtly wrong.</p>
	 *
	 * @return the ratio in {@code [0, 1]}, or {@code 0} when either is degenerate
	 */
	public static double overlap( final double[] one, final double[] two )
	{
		if ( one == null || two == null || one.length < 5 || two.length < 5 )
			return 0.0;
		if ( !( one[ 2 ] > 0 ) || !( one[ 3 ] > 0 ) || !( two[ 2 ] > 0 ) || !( two[ 3 ] > 0 ) )
			return 0.0;
		final double reach = Math.max( one[ 2 ], two[ 2 ] );
		final double minX = Math.min( one[ 0 ], two[ 0 ] ) - reach;
		final double maxX = Math.max( one[ 0 ], two[ 0 ] ) + reach;
		final double minY = Math.min( one[ 1 ], two[ 1 ] ) - reach;
		final double maxY = Math.max( one[ 1 ], two[ 1 ] ) + reach;
		int inter = 0, union = 0;
		for ( int gy = 0; gy < OVERLAP_GRID; gy++ )
			for ( int gx = 0; gx < OVERLAP_GRID; gx++ )
			{
				final double x = minX + ( maxX - minX ) * ( gx + 0.5 ) / OVERLAP_GRID;
				final double y = minY + ( maxY - minY ) * ( gy + 0.5 ) / OVERLAP_GRID;
				final boolean a = inside( one, x, y ), b = inside( two, x, y );
				if ( a && b )
					inter++;
				if ( a || b )
					union++;
			}
		return union == 0 ? 0.0 : ( double ) inter / union;
	}

	private static boolean inside( final double[] e, final double x, final double y )
	{
		final double c = Math.cos( e[ 4 ] ), s = Math.sin( e[ 4 ] );
		final double dx = x - e[ 0 ], dy = y - e[ 1 ];
		final double u = c * dx + s * dy, v = -s * dx + c * dy;
		return u * u / ( e[ 2 ] * e[ 2 ] ) + v * v / ( e[ 3 ] * e[ 3 ] ) <= 1.0;
	}

	private static boolean distinct( final Random rnd, final int[] pick, final int n )
	{
		for ( int i = 0; i < pick.length; i++ )
		{
			pick[ i ] = rnd.nextInt( n );
			for ( int j = 0; j < i; j++ )
				if ( pick[ j ] == pick[ i ] )
					return false;
		}
		return true;
	}

	private EllipseFit()
	{}

	/** Sylvester's criterion on a symmetric 3x3 matrix. */
	static boolean positiveDefinite( final double[][] m )
	{
		final double d1 = m[ 0 ][ 0 ];
		final double d2 = m[ 0 ][ 0 ] * m[ 1 ][ 1 ] - m[ 0 ][ 1 ] * m[ 1 ][ 0 ];
		final double d3 = m[ 0 ][ 0 ] * ( m[ 1 ][ 1 ] * m[ 2 ][ 2 ] - m[ 1 ][ 2 ] * m[ 2 ][ 1 ] )
				- m[ 0 ][ 1 ] * ( m[ 1 ][ 0 ] * m[ 2 ][ 2 ] - m[ 1 ][ 2 ] * m[ 2 ][ 0 ] )
				+ m[ 0 ][ 2 ] * ( m[ 1 ][ 0 ] * m[ 2 ][ 1 ] - m[ 1 ][ 1 ] * m[ 2 ][ 0 ] );
		return d1 > 0 && d2 > 0 && d3 > 0;
	}
}
