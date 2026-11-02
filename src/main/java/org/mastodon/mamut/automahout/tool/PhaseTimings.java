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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a phase's wall-clock time actually went, accumulated by step name.
 *
 * <p>Exists because a run log that only prints what each step DID cannot answer "why is this slow" —
 * the gaps between log lines are the only evidence, and reading minutes off timestamps by hand
 * attributes them to whichever line happens to come next rather than to the code that spent them.
 * Measured on the dataset that prompted this: two to three minutes per timepoint on thirteen to
 * sixteen crop-restricted detections, with no line in the log accounting for any of it.</p>
 *
 * <p>Two levels, because the expensive things are called from inside the steps that report: a
 * {@link #record top-level} step is one item of work in the pipeline, and a {@link #recordNested
 * nested} one is a shared cost incurred inside several of them (the background estimate being the
 * one that matters). Nested times are ALREADY INCLUDED in the top-level times they were spent
 * under, so the summary prints them separately, under "of which", instead of adding them in.</p>
 */
public final class PhaseTimings
{
	private PhaseTimings()
	{}

	/** {@link ToolContext#getSharedData} key for the top-level accumulator. */
	private static final String SHARED_STEPS = "timings.steps";

	/** {@link ToolContext#getSharedData} key for the nested accumulator. */
	private static final String SHARED_NESTED = "timings.nested";

	/** Total below which a step is not worth a place in the summary line. */
	private static final long REPORT_FLOOR_NANOS = 500_000_000L;

	/** Steps reported before the rest are collapsed into one "other" entry. */
	private static final int MAX_STEPS_REPORTED = 8;

	/** Total below which the whole summary is not worth printing. */
	private static final long SUMMARY_FLOOR_NANOS = 5_000_000_000L;

	/** Accumulates {@code System.nanoTime() - startNanos} against {@code step}. */
	public static void record( final ToolContext ctx, final String step, final long startNanos )
	{
		add( accumulator( ctx, SHARED_STEPS ), step, System.nanoTime() - startNanos );
	}

	/**
	 * As {@link #record}, for a cost incurred INSIDE one of the recorded steps — reported separately so
	 * the top-level numbers still add up to the elapsed time.
	 */
	public static void recordNested( final ToolContext ctx, final String step, final long startNanos )
	{
		add( accumulator( ctx, SHARED_NESTED ), step, System.nanoTime() - startNanos );
	}

	/**
	 * A copy of everything recorded so far, to pass to {@link #summarySince} once a shorter span
	 * finishes. Exists so one timepoint can be reported without disturbing the window's running totals:
	 * a per-window line only arrives after the window does, which is no use to someone watching a run
	 * that takes two minutes per timepoint and wanting to know why NOW.
	 */
	public static Map< String, long[] > mark( final ToolContext ctx )
	{
		final Map< String, long[] > out = new HashMap<>();
		copyInto( accumulator( ctx, SHARED_STEPS ), "", out );
		copyInto( accumulator( ctx, SHARED_NESTED ), NESTED_PREFIX, out );
		return out;
	}

	/**
	 * One line covering only what happened since {@code mark}, or {@code ""} when that span was short
	 * enough not to be worth a line.
	 */
	public static String summarySince( final ToolContext ctx, final Map< String, long[] > mark,
			final long floorNanos )
	{
		if ( ctx == null || mark == null )
			return "";
		final Map< String, long[] > steps = delta( accumulator( ctx, SHARED_STEPS ), mark, "" );
		final Map< String, long[] > nested = delta( accumulator( ctx, SHARED_NESTED ), mark, NESTED_PREFIX );
		long total = 0;
		for ( final long[] v : steps.values() )
			total += v[ 0 ];
		return total < floorNanos ? "" : line( total, steps, nested );
	}

	/**
	 * Marks nested entries in a {@link #mark} copy, so the two namespaces cannot collide there. Step
	 * names are human labels ("flat-contrast check"), so nothing can start with this by accident.
	 */
	private static final String NESTED_PREFIX = "nested/";

	private static void copyInto( final Map< String, long[] > from, final String prefix,
			final Map< String, long[] > into )
	{
		if ( from == null )
			return;
		for ( final Map.Entry< String, long[] > e : from.entrySet() )
			into.put( prefix + e.getKey(), new long[] { e.getValue()[ 0 ], e.getValue()[ 1 ] } );
	}

	private static Map< String, long[] > delta( final Map< String, long[] > now,
			final Map< String, long[] > mark, final String prefix )
	{
		final Map< String, long[] > out = new HashMap<>();
		if ( now == null )
			return out;
		for ( final Map.Entry< String, long[] > e : now.entrySet() )
		{
			final long[] before = mark.get( prefix + e.getKey() );
			final long nanos = e.getValue()[ 0 ] - ( before == null ? 0 : before[ 0 ] );
			final long calls = e.getValue()[ 1 ] - ( before == null ? 0 : before[ 1 ] );
			if ( nanos > 0 || calls > 0 )
				out.put( e.getKey(), new long[] { nanos, calls } );
		}
		return out;
	}

	/** Forgets everything recorded so far — call at the start of the span you want measured. */
	public static void reset( final ToolContext ctx )
	{
		if ( ctx == null )
			return;
		ctx.setSharedData( SHARED_STEPS, new HashMap< String, long[] >() );
		ctx.setSharedData( SHARED_NESTED, new HashMap< String, long[] >() );
	}

	/**
	 * One line naming the steps that took the time, longest first, or {@code ""} when the span was too
	 * short to be worth reporting.
	 */
	public static String summary( final ToolContext ctx )
	{
		if ( ctx == null )
			return "";
		return summary( accumulator( ctx, SHARED_STEPS ), accumulator( ctx, SHARED_NESTED ) );
	}

	/** The formatting, separated from the shared-data plumbing so it can be tested directly. */
	static String summary( final Map< String, long[] > steps, final Map< String, long[] > nested )
	{
		long total = 0;
		for ( final long[] v : steps.values() )
			total += v[ 0 ];
		if ( total < SUMMARY_FLOOR_NANOS )
			return "";
		return line( total, steps, nested );
	}

	private static String line( final long total, final Map< String, long[] > steps,
			final Map< String, long[] > nested )
	{
		final StringBuilder sb = new StringBuilder( "  Time: " + format( total ) + " total — " );
		sb.append( list( steps ) );
		final String of = list( nested );
		if ( !of.isEmpty() )
			sb.append( "; of which (already counted above): " ).append( of );
		return sb.toString();
	}

	private static String list( final Map< String, long[] > by )
	{
		final List< Map.Entry< String, long[] > > entries = new ArrayList<>( by.entrySet() );
		entries.sort( Comparator.comparingLong( ( final Map.Entry< String, long[] > e ) -> e.getValue()[ 0 ] )
				.reversed() );
		final StringBuilder sb = new StringBuilder();
		long other = 0;
		int reported = 0;
		for ( final Map.Entry< String, long[] > e : entries )
		{
			final long nanos = e.getValue()[ 0 ];
			if ( nanos < REPORT_FLOOR_NANOS || reported >= MAX_STEPS_REPORTED )
			{
				other += nanos;
				continue;
			}
			if ( reported > 0 )
				sb.append( ", " );
			sb.append( e.getKey() ).append( ' ' ).append( format( nanos ) )
					.append( " (" ).append( e.getValue()[ 1 ] ).append( "x)" );
			reported++;
		}
		if ( other >= REPORT_FLOOR_NANOS )
		{
			if ( reported > 0 )
				sb.append( ", " );
			sb.append( "everything else " ).append( format( other ) );
		}
		return sb.toString();
	}

	/** {@code 1h02m}, {@code 5m12s}, {@code 22s} or {@code 0.4s} — never a bare count of milliseconds. */
	static String format( final long nanos )
	{
		final long ms = nanos / 1_000_000L;
		if ( ms < 10_000L )
			return String.format( "%.1fs", ms / 1000.0 );
		final long s = ms / 1000L;
		if ( s < 3600L )
			return s < 60L ? s + "s" : String.format( "%dm%02ds", s / 60L, s % 60L );
		return String.format( "%dh%02dm", s / 3600L, ( s % 3600L ) / 60L );
	}

	private static void add( final Map< String, long[] > acc, final String step, final long nanos )
	{
		if ( acc == null )
			return;
		final long[] v = acc.computeIfAbsent( step, k -> new long[ 2 ] );
		v[ 0 ] += nanos;
		v[ 1 ]++;
	}

	@SuppressWarnings( "unchecked" )
	private static Map< String, long[] > accumulator( final ToolContext ctx, final String key )
	{
		if ( ctx == null )
			return null;
		Object v = ctx.getSharedData( key );
		if ( !( v instanceof Map ) )
		{
			v = new HashMap< String, long[] >();
			ctx.setSharedData( key, v );
		}
		return ( Map< String, long[] > ) v;
	}
}
