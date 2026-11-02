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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Teaches every kind of cell, not mostly the typical one.
 *
 * <p>The user's hypothesis: nearly every detection is taught, so the many cells near the middle of
 * the brightness and size distribution dominate training while dim, small and large cells are
 * learned less. Measured on three HeLa 01 runs ({@code benchmark/lineage-review/
 * measure_recall_by_type.py}), recall of the dimmest fifth of cells is 0.76-0.83 and of the smallest
 * fifth 0.85-0.87, against 0.97-0.99 in the middle; and a training round raises the dim fifth by
 * 0.04-0.05 while the middle stays where it is. More examples of the typical cell buy nothing; the
 * atypical still gain from each round.</p>
 *
 * <p>So a window's taught detections are grouped by brightness and size -- bins one MAD wide around
 * the window's own median, not quantiles, which would give every group the same count by
 * construction -- and the most crowded groups are thinned down to the 75th percentile of the group
 * counts. Simulated on two windows that withholds 17-22% of them, all from the typical middle.
 * Withheld means unlabeled: the detection stays in the graph and is linked, it is only not taught
 * this round. Switchable from the pre-flight dialog; this is an experiment.</p>
 */
public final class TrainingBalance
{
	private TrainingBalance()
	{}

	/** {@link ToolContext#getSharedData} key, {@link Boolean}: balance the taught set. Absent = off. */
	public static final String SHARED_ENABLED = "training.balance_by_type";

	/** Bin edges in MADs from the window's median, for brightness and size alike. */
	static final double[] EDGES = { -1.5, -0.5, 0.5, 1.5 };

	/** Groups are cut down to this percentile of the non-empty groups' counts. */
	static final double CAP_PERCENTILE = 75.0;

	/** Fewer taught detections than this and the groups mean nothing. */
	static final int MIN_DETECTIONS = 30;

	public static boolean enabled( final ToolContext ctx )
	{
		return Boolean.TRUE.equals( ctx.getSharedData( SHARED_ENABLED ) );
	}

	/**
	 * Which detections to withhold.
	 *
	 * @param brightness per detection, any scale
	 * @param size per detection, any scale
	 * @param seed makes the choice within a group reproducible
	 * @return indices to withhold; empty with fewer than {@link #MIN_DETECTIONS}
	 */
	public static int[] toWithhold( final double[] brightness, final double[] size, final long seed )
	{
		final int n = brightness.length;
		if ( n < MIN_DETECTIONS )
			return new int[ 0 ];
		final int[] gb = bins( brightness ), gs = bins( size );
		final Map< Integer, List< Integer > > groups = new HashMap<>();
		for ( int i = 0; i < n; i++ )
			groups.computeIfAbsent( gb[ i ] * ( EDGES.length + 1 ) + gs[ i ], k -> new ArrayList<>() ).add( i );
		final double[] counts = new double[ groups.size() ];
		int k = 0;
		for ( final List< Integer > g : groups.values() )
			counts[ k++ ] = g.size();
		final int cap = ( int ) Math.floor( percentile( counts, CAP_PERCENTILE ) );
		final Random random = new Random( seed );
		final List< Integer > out = new ArrayList<>();
		final List< Integer > keys = new ArrayList<>( groups.keySet() );
		keys.sort( null );
		for ( final int key : keys )
		{
			final List< Integer > g = groups.get( key );
			if ( g.size() <= cap )
				continue;
			final List< Integer > shuffled = new ArrayList<>( g );
			java.util.Collections.shuffle( shuffled, random );
			out.addAll( shuffled.subList( cap, shuffled.size() ) );
		}
		return out.stream().mapToInt( Integer::intValue ).sorted().toArray();
	}

	/** Bin of each value: 0..EDGES.length, by MADs from the median. */
	static int[] bins( final double[] v )
	{
		final double med = median( v );
		final double[] dev = new double[ v.length ];
		for ( int i = 0; i < v.length; i++ )
			dev[ i ] = Math.abs( v[ i ] - med );
		final double mad = 1.4826 * median( dev );
		final int[] out = new int[ v.length ];
		for ( int i = 0; i < v.length; i++ )
		{
			final double z = mad > 0 ? ( v[ i ] - med ) / mad : 0.0;
			int b = 0;
			while ( b < EDGES.length && z >= EDGES[ b ] )
				b++;
			out[ i ] = b;
		}
		return out;
	}

	private static double median( final double[] v )
	{
		final double[] s = v.clone();
		Arrays.sort( s );
		return s.length == 0 ? 0 : s.length % 2 == 1 ? s[ s.length / 2 ] : 0.5 * ( s[ s.length / 2 - 1 ] + s[ s.length / 2 ] );
	}

	private static double percentile( final double[] v, final double p )
	{
		final double[] s = v.clone();
		Arrays.sort( s );
		if ( s.length == 1 )
			return s[ 0 ];
		final double pos = p / 100.0 * ( s.length - 1 );
		final int lo = ( int ) Math.floor( pos );
		final int hi = Math.min( s.length - 1, lo + 1 );
		return s[ lo ] + ( pos - lo ) * ( s[ hi ] - s[ lo ] );
	}
}
