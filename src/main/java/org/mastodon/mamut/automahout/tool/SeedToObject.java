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
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Decides whether two detections sit on the SAME object, by segmenting the object around them.
 *
 * <h2>Why</h2>
 *
 * <p>The pipeline used to answer this from the two points alone: their separation in radii, and
 * whether the intensity dips along the straight segment joining them. Measured against CTC silver
 * truth (see {@code benchmark/seed-to-object}), that test merges <b>9.0%</b> of the pairs it exists
 * to merge, while still wrongly merging 11.1% of genuinely distinct pairs -- it does almost nothing
 * right and is not even safe while failing. That is why one cell detected twice kept surviving every
 * round of tuning: the arbiter was not weak, it was inert.</p>
 *
 * <p>The straight segment is the wrong path. Two lobes of one cell are joined by whatever path the
 * cell's shape provides, which is rarely a straight line, so the segment crosses background that the
 * cell goes around. Asking instead "do these two seeds belong to the same segmented object" merges
 * <b>86.1%</b> at the same 10% error rate -- nine times as many, for no extra mistakes.</p>
 *
 * <h2>Why a watershed and not just connectivity</h2>
 *
 * <p>Thresholding and taking connected components fuses touching cells: measured, it wrongly merges
 * 59-80% of distinct pairs, useless at every threshold tried. Asking for the highest level at which
 * the two seeds stay connected -- the intensity saddle, the same criterion per pair -- is far better
 * but still loses to the watershed at matched error (57.1% against 77.0% at ~8%). The difference is
 * <b>shape</b>: two touching round cells of equal brightness have a high intensity saddle and an
 * obvious neck. The distance transform sees the neck; intensity alone does not.</p>
 *
 * <p>Pure arithmetic on a window of samples, deliberately, so the rule can be tested against
 * constructed shapes without an image.</p>
 */
public final class SeedToObject
{
	/**
	 * Level above background at which the object is taken to end, in sigma.
	 *
	 * <p>3 sigma measured best: 86.1% merged at 10.2% wrong, against 81.7%/8.3% at 5 sigma. The
	 * higher level abstains more often (12.9% of one-cell pairs, against 9.2%) because a seed can
	 * fall below it.</p>
	 */
	public static final double OBJECT_LEVEL_SIGMA = 3.0;

	/**
	 * Sampling stride, in voxels, for the window.
	 *
	 * <p>The window is read through a {@code Sampler} and costs per read, so this is the price of the
	 * decision: stride 1 is ~15900 reads per pair for 89.6%, stride 2 ~4000 for 86.1%, stride 3
	 * ~1800 for 75.8%. The error rate is flat at ~10% across all three, so the stride buys recall,
	 * not safety, and 2 is where the curve turns.</p>
	 */
	public static final int WINDOW_STRIDE = 2;

	/** Samples along each side of the window read around a pair. */
	public static final int WINDOW_SIDE = 48;

	/**
	 * Closest two watershed markers may be, in window pixels.
	 *
	 * <p>The parameter that decides everything, and the one a first implementation got wrong: at 3 it
	 * merges 61.0% of one-cell pairs, at 5 85.5%, at 8 97.5% -- but the error rate climbs with it
	 * (3.4%, 6.8%, 16.0%). 5 was chosen by sweeping the real fixture, because it reaches the recall
	 * the Python measurement reported (86.1%) at a third of its error (6.8% against 10.2%).</p>
	 *
	 * <p>Too small a value is not conservative, it is broken: the distance transform of one cell has
	 * several shallow maxima, and each one that starts its own basin splits the cell.</p>
	 */
	public static final int MIN_MARKER_DISTANCE = 5;

	private SeedToObject()
	{}

	/**
	 * Whether {@code a} and {@code b} lie in the same segmented object.
	 *
	 * @param window row-major samples, {@code w} by {@code h}
	 * @param level  intensity at which the object ends
	 * @return {@code true}/{@code false}, or {@code null} when the window cannot answer -- either seed
	 *         outside it, or either seed below {@code level}
	 */
	public static Boolean sameObject( final double[] window, final int w, final int h,
			final double level, final int ax, final int ay, final int bx, final int by )
	{
		return sameObject( window, w, h, level, ax, ay, bx, by, MIN_MARKER_DISTANCE );
	}

	/**
	 * As above, with the marker distance exposed so it can be chosen by measurement rather than
	 * guessed. A prominence floor on the maxima was tried alongside it and measured strictly worse at
	 * every distance -- it suppresses real maxima, not ripples -- so there is no knob for it.
	 */
	public static Boolean sameObject( final double[] window, final int w, final int h,
			final double level, final int ax, final int ay, final int bx, final int by,
			final int minMarkerDistance )
	{
		if ( w <= 0 || h <= 0 || window.length < w * h )
			return null;
		if ( !inside( ax, ay, w, h ) || !inside( bx, by, w, h ) )
			return null;
		final boolean[] mask = new boolean[ w * h ];
		for ( int i = 0; i < w * h; i++ )
			mask[ i ] = window[ i ] > level;
		final int ia = ay * w + ax;
		final int ib = by * w + bx;
		if ( !mask[ ia ] || !mask[ ib ] )
			return null; // a seed that is not on the object cannot be said to share one

		final double[] dist = distanceTransform( mask, w, h );
		final int[] labels = watershed( dist, mask, w, h,
				markers( dist, mask, w, h, minMarkerDistance ) );
		return labels[ ia ] != 0 && labels[ ia ] == labels[ ib ];
	}

	/**
	 * Centres of the distinct objects lying inside a circle in {@code window}.
	 *
	 * <p>The inverse of {@link #sameObject}. That asks whether two seeds are one object, which is the
	 * question a MERGE needs; this asks how many objects lie under one detection, which is the
	 * question a SPLIT needs — and nothing was asking it. The pipeline's only protection against one
	 * detection covering two cells was the neighbour cap in {@code resizeToMeasuredExtent}, which
	 * needs a second detection to protect and so cannot help when the model produced only one.</p>
	 *
	 * <p>Same primitives as the arbiter: threshold at {@code level}, distance transform, peak markers
	 * no closer than {@code minMarkerDistance}. Measured over 2876 one-cell and 385 two-cell
	 * detections built from CTC silver truth (see {@code benchmark/seed-to-object/measure_split.py}):
	 * at a marker separation of 0.53 cell radii, 94.3% of single cells report one centre and 86.8% of
	 * merged pairs report two or more. Applied only to detections large for their frame, which is
	 * where a merged pair must be, 99.4% of single cells are left whole for 86.0% of pairs split.</p>
	 *
	 * @return {@code {x0, y0, x1, y1, ...}} in window coordinates, or {@code null} when the window
	 *         holds nothing above {@code level} — which is not the same as holding one object
	 */
	public static int[] objectCentresWithin( final double[] window, final int w, final int h,
			final double level, final double cx, final double cy, final double radius,
			final int minMarkerDistance )
	{
		if ( window == null || window.length != w * h || !( radius > 0 ) )
			return null;
		final boolean[] mask = new boolean[ window.length ];
		boolean any = false;
		for ( int i = 0; i < window.length; i++ )
		{
			mask[ i ] = window[ i ] > level;
			any |= mask[ i ];
		}
		if ( !any )
			return null;
		final double[] dist = distanceTransform( mask, w, h );
		final int[] marks = markers( dist, mask, w, h, Math.max( 1, minMarkerDistance ) );
		final List< Integer > out = new ArrayList<>();
		for ( int i = 0; i < marks.length; i++ )
		{
			if ( marks[ i ] == 0 )
				continue;
			final int x = i % w, y = i / w;
			final double dx = x - cx, dy = y - cy;
			if ( dx * dx + dy * dy <= radius * radius )
			{
				out.add( x );
				out.add( y );
			}
		}
		final int[] arr = new int[ out.size() ];
		for ( int i = 0; i < arr.length; i++ )
			arr[ i ] = out.get( i );
		return arr;
	}

	/**
	 * How deep the intensity valley between two centres is, as a fraction of the dimmer of them.
	 *
	 * <p>{@link #objectCentresWithin} counts maxima of the DISTANCE TRANSFORM, which is a statement
	 * about shape. A HeLa nucleus has visible internal texture -- nucleoli, condensed chromatin -- and
	 * a threshold mask over one can be pinched enough to carry two distance maxima while the intensity
	 * between them never dips. Those are the splits the user kept seeing: one mottled nucleus wearing
	 * two or three circles.</p>
	 *
	 * <p>Measured on the same populations ({@code benchmark/seed-to-object/measure_split.py}), over the
	 * 163 single cells and 334 genuine pairs that produce two or more centres at all:</p>
	 *
	 * <pre>
	 *   require ratio &lt;=   false splits kept   true splits kept
	 *        none                 100.0%              100.0%
	 *        0.85                  58.3%               91.3%
	 *        0.80                  53.4%               89.2%
	 *        0.70                  43.6%               84.4%
	 * </pre>
	 *
	 * <p>1.0 means no valley at all -- the path between them never drops below the dimmer endpoint.</p>
	 *
	 * @param background subtracted from every reading, so the ratio is of signal and not of offset
	 * @return the ratio, or 1.0 when there is nothing to compare
	 */
	public static double saddleRatio( final double[] window, final int w, final int h,
			final double background, final int ax, final int ay, final int bx, final int by )
	{
		if ( window == null || window.length != w * h )
			return 1.0;
		if ( !inside( ax, ay, w, h ) || !inside( bx, by, w, h ) )
			return 1.0;
		final double a = window[ ay * w + ax ] - background;
		final double b = window[ by * w + bx ] - background;
		final double peak = Math.min( a, b );
		if ( !( peak > 0 ) )
			return 1.0;
		final int steps = Math.max( 2, Math.abs( ax - bx ) + Math.abs( ay - by ) );
		double lowest = Double.MAX_VALUE;
		for ( int i = 0; i <= steps; i++ )
		{
			final double f = ( double ) i / steps;
			final int x = ( int ) Math.round( ax + f * ( bx - ax ) );
			final int y = ( int ) Math.round( ay + f * ( by - ay ) );
			lowest = Math.min( lowest, window[ y * w + x ] - background );
		}
		return lowest / peak;
	}

	private static boolean inside( final int x, final int y, final int w, final int h )
	{
		return x >= 0 && x < w && y >= 0 && y < h;
	}

	/**
	 * Chamfer (3,4) distance to the nearest background pixel, in units of one pixel.
	 *
	 * <p>Two passes, forward then backward. The exact Euclidean transform is not worth its complexity
	 * here: the distance only has to rank the interior of an object above its neck, and (3,4)/3
	 * approximates Euclidean to within about 5%.</p>
	 */
	static double[] distanceTransform( final boolean[] mask, final int w, final int h )
	{
		final int big = Integer.MAX_VALUE / 4;
		final int[] d = new int[ w * h ];
		for ( int i = 0; i < d.length; i++ )
			d[ i ] = mask[ i ] ? big : 0;
		for ( int y = 0; y < h; y++ )
		{
			for ( int x = 0; x < w; x++ )
			{
				final int i = y * w + x;
				if ( d[ i ] == 0 )
					continue;
				int best = d[ i ];
				best = Math.min( best, at( d, w, h, x - 1, y, big ) + 3 );
				best = Math.min( best, at( d, w, h, x, y - 1, big ) + 3 );
				best = Math.min( best, at( d, w, h, x - 1, y - 1, big ) + 4 );
				best = Math.min( best, at( d, w, h, x + 1, y - 1, big ) + 4 );
				d[ i ] = best;
			}
		}
		for ( int y = h - 1; y >= 0; y-- )
		{
			for ( int x = w - 1; x >= 0; x-- )
			{
				final int i = y * w + x;
				if ( d[ i ] == 0 )
					continue;
				int best = d[ i ];
				best = Math.min( best, at( d, w, h, x + 1, y, big ) + 3 );
				best = Math.min( best, at( d, w, h, x, y + 1, big ) + 3 );
				best = Math.min( best, at( d, w, h, x + 1, y + 1, big ) + 4 );
				best = Math.min( best, at( d, w, h, x - 1, y + 1, big ) + 4 );
				d[ i ] = best;
			}
		}
		final double[] out = new double[ d.length ];
		for ( int i = 0; i < d.length; i++ )
			out[ i ] = d[ i ] / 3.0;
		return out;
	}

	private static int at( final int[] d, final int w, final int h, final int x, final int y,
			final int big )
	{
		return inside( x, y, w, h ) ? d[ y * w + x ] : big;
	}

	/**
	 * One marker per local maximum of the distance transform, thinned so two markers are never within
	 * {@link #MIN_MARKER_DISTANCE}. Strongest first, so a plateau yields one marker rather than many.
	 */
	static int[] markers( final double[] dist, final boolean[] mask, final int w, final int h,
			final int minMarkerDistance )
	{
		final List< Integer > cands = new ArrayList<>();
		for ( int i = 0; i < dist.length; i++ )
		{
			if ( mask[ i ] && dist[ i ] > 0 )
				cands.add( i );
		}
		cands.sort( Comparator.comparingDouble( ( final Integer i ) -> dist[ i ] ).reversed() );
		final int[] out = new int[ dist.length ];
		final List< int[] > taken = new ArrayList<>();
		int next = 1;
		for ( final int i : cands )
		{
			final int x = i % w, y = i / w;
			boolean near = false;
			for ( final int[] t : taken )
			{
				final int dx = t[ 0 ] - x, dy = t[ 1 ] - y;
				if ( dx * dx + dy * dy < minMarkerDistance * minMarkerDistance )
				{
					near = true;
					break;
				}
			}
			if ( near )
				continue;
			// A maximum, not merely un-taken: no neighbour in the mask rises above it.
			boolean isMax = true;
			for ( int dy = -1; dy <= 1 && isMax; dy++ )
			{
				for ( int dx = -1; dx <= 1; dx++ )
				{
					if ( !inside( x + dx, y + dy, w, h ) )
						continue;
					if ( dist[ ( y + dy ) * w + x + dx ] > dist[ i ] )
					{
						isMax = false;
						break;
					}
				}
			}
			if ( !isMax )
				continue;
			out[ i ] = next++;
			taken.add( new int[] { x, y } );
		}
		return out;
	}

	/** Priority flooding of {@code markers} downhill through {@code -dist}, inside {@code mask}. */
	static int[] watershed( final double[] dist, final boolean[] mask, final int w, final int h,
			final int[] markers )
	{
		final int[] labels = markers.clone();
		final PriorityQueue< double[] > pq =
				new PriorityQueue<>( Comparator.comparingDouble( p -> p[ 0 ] ) );
		for ( int i = 0; i < labels.length; i++ )
		{
			if ( labels[ i ] != 0 )
				pq.add( new double[] { -dist[ i ], i } );
		}
		final int[] dxs = { -1, 1, 0, 0 };
		final int[] dys = { 0, 0, -1, 1 };
		while ( !pq.isEmpty() )
		{
			final int i = ( int ) pq.poll()[ 1 ];
			final int x = i % w, y = i / w;
			for ( int k = 0; k < 4; k++ )
			{
				final int nx = x + dxs[ k ], ny = y + dys[ k ];
				if ( !inside( nx, ny, w, h ) )
					continue;
				final int j = ny * w + nx;
				if ( !mask[ j ] || labels[ j ] != 0 )
					continue;
				labels[ j ] = labels[ i ];
				pq.add( new double[] { -dist[ j ], j } );
			}
		}
		return labels;
	}
}
