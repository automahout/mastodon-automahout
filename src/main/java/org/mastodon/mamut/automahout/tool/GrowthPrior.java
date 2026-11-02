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
import java.util.Arrays;
import java.util.List;

/**
 * How many cells a window should hold, from the growth the run has measured itself: a robust (Theil-Sen)
 * line through the log of the accepted spots per timepoint, extrapolated to the window. Never below zero
 * growth: a population that is not growing reads as flat, which is what the previous-window prior assumed
 * all along.
 *
 * <p>The explosion guards compared a window with the previous window's count alone, times 2. A dividing
 * embryo outgrows that: on Fluo-N3DH-CE 01 the window at t=160..169 detected 194-239 cells per frame
 * against about 242 in the truth, was read as an explosion against the previous window's 108, rolled back,
 * and every window after it fell to the agent's guessed 160 and was rolled back too. The run's own counts
 * had doubled about every 25 frames by then.</p>
 */
public final class GrowthPrior
{
	/** Fewest accepted timepoints to read a growth rate from. */
	public static final int MIN_POINTS = 5;

	/**
	 * The growth rate is shrunk toward none by {@code span / (span + SHRINK_SPAN)}, {@code span} the timepoints
	 * between the first and last accepted count: a rate read off one window of small counts is mostly noise (CE
	 * 01's first ten frames, 1-5 cells, read as doubling every 5 frames; the truth doubles about every 25), and with
	 * no evidence the prior is the previous window's count, as it always was.
	 *
	 * <p>By the span, not the number of counts: the evidence for a rate is how far apart the counts are. It was
	 * {@code n / (n + 10)} for {@code n} counts, the same for consecutive frames (ten: 0.50 against 0.47 now), but
	 * five counts 30 frames apart (CE 01 tracked at t=0, 30, ..., 120) were shrunk to a third: doubling every 126
	 * frames for a truth of about 30, an expected 71 at t=150 against 192 cells, and the explosion guard threw away
	 * the frames detected right. By the span: 190.</p>
	 */
	public static final double SHRINK_SPAN = 10;

	/** Most points the pairwise slopes are taken over (evenly thinned beyond). */
	public static final int MAX_POINTS = 200;

	private GrowthPrior()
	{}

	/** The fitted line: log(count) = intercept + slope * t. */
	public static final class Fit
	{
		public final double intercept, slope;

		public final int points;

		Fit( final double intercept, final double slope, final int points )
		{
			this.intercept = intercept;
			this.slope = slope;
			this.points = points;
		}

		/** Expected spots per timepoint at {@code t}. */
		public double expectedAt( final int t )
		{
			return Math.exp( intercept + slope * t );
		}

		/** Timepoints for the count to double; infinite when it is not growing. */
		public double doublingTime()
		{
			return slope > 0 ? Math.log( 2 ) / slope : Double.POSITIVE_INFINITY;
		}
	}

	/**
	 * @param ts     timepoints
	 * @param counts spots at each; zeros (timepoints with nothing accepted) are left out
	 * @return null with fewer than {@link #MIN_POINTS} non-zero counts
	 */
	public static Fit fit( final int[] ts, final double[] counts )
	{
		final List< double[] > pts = new ArrayList<>();
		for ( int i = 0; i < ts.length; i++ )
			if ( counts[ i ] > 0 )
				pts.add( new double[] { ts[ i ], Math.log( counts[ i ] ) } );
		if ( pts.size() < MIN_POINTS )
			return null;
		final List< double[] > use;
		if ( pts.size() > MAX_POINTS )
		{
			use = new ArrayList<>();
			for ( int k = 0; k < MAX_POINTS; k++ )
				use.add( pts.get( ( int ) ( ( long ) k * ( pts.size() - 1 ) / ( MAX_POINTS - 1 ) ) ) );
		}
		else
			use = pts;
		final List< Double > slopes = new ArrayList<>();
		for ( int i = 0; i < use.size(); i++ )
			for ( int j = i + 1; j < use.size(); j++ )
			{
				final double dt = use.get( j )[ 0 ] - use.get( i )[ 0 ];
				if ( dt != 0 )
					slopes.add( ( use.get( j )[ 1 ] - use.get( i )[ 1 ] ) / dt );
			}
		if ( slopes.isEmpty() )
			return null;
		double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY;
		for ( final double[] p : pts )
		{
			first = Math.min( first, p[ 0 ] );
			last = Math.max( last, p[ 0 ] );
		}
		final double span = last - first;
		final double slope = Math.max( 0, median( slopes ) ) * span / ( span + SHRINK_SPAN );
		final List< Double > icpt = new ArrayList<>();
		for ( final double[] p : use )
			icpt.add( p[ 1 ] - slope * p[ 0 ] );
		return new Fit( median( icpt ), slope, pts.size() );
	}

	private static double median( final List< Double > v )
	{
		final double[] a = new double[ v.size() ];
		for ( int i = 0; i < a.length; i++ )
			a[ i ] = v.get( i );
		Arrays.sort( a );
		final int n = a.length;
		return n % 2 == 1 ? a[ n / 2 ] : ( a[ n / 2 - 1 ] + a[ n / 2 ] ) / 2;
	}
}
