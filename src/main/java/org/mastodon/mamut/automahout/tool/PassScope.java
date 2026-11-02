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

import java.util.HashMap;
import java.util.Map;

import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;

/**
 * What a {@link CorrectionPass} runs on: the run, its settings, the timepoints it may change, and what the
 * passes before it in the same plan run counted or measured.
 */
public final class PassScope
{
	public final ToolContext ctx;

	/** The run's correction thresholds; may be {@code null} in a test. */
	public final AutomahoutSettings settings;

	/** FRAME and DETECTED: the timepoint being corrected; LINEAGE (and a DETECTED window scope): -1. */
	public final int timepoint;

	/** LINEAGE: the first timepoint a pass may change (a window's start); FRAME: 0. */
	public final int scanStartTp;

	/** One past the last timepoint a pass may change. */
	public final int numTimepoints;

	/** FRAME: the first timepoint a pass may create a spot at (a gap bridge reaches back); LINEAGE: as scanStartTp. */
	public final int earliestWritableTp;

	private final Map< String, Integer > counts = new HashMap<>();

	private final Map< String, Object > values;

	private final Map< String, Integer > totals;

	private PassScope( final ToolContext ctx, final AutomahoutSettings settings, final int timepoint,
			final int scanStartTp, final int numTimepoints, final int earliestWritableTp )
	{
		this( ctx, settings, timepoint, scanStartTp, numTimepoints, earliestWritableTp, new HashMap<>(),
				new HashMap<>() );
	}

	private PassScope( final ToolContext ctx, final AutomahoutSettings settings, final int timepoint,
			final int scanStartTp, final int numTimepoints, final int earliestWritableTp,
			final Map< String, Object > values, final Map< String, Integer > totals )
	{
		this.values = values;
		this.totals = totals;
		this.ctx = ctx;
		this.settings = settings;
		this.timepoint = timepoint;
		this.scanStartTp = scanStartTp;
		this.numTimepoints = numTimepoints;
		this.earliestWritableTp = earliestWritableTp;
	}

	public static PassScope frame( final ToolContext ctx, final AutomahoutSettings settings, final int timepoint,
			final int numTimepoints, final int earliestWritableTp )
	{
		return new PassScope( ctx, settings, timepoint, 0, numTimepoints, earliestWritableTp );
	}

	public static PassScope lineage( final ToolContext ctx, final AutomahoutSettings settings,
			final int scanStartTp, final int numTimepoints )
	{
		return new PassScope( ctx, settings, -1, scanStartTp, numTimepoints, scanStartTp );
	}

	/**
	 * A DETECTED window: the frames {@code wStart..wEnd} as one detector run adds them. Its values (what the
	 * caller set up, what the passes accumulate) and totals are shared by every {@link #atFrame} scope.
	 */
	public static PassScope detectedWindow( final ToolContext ctx, final AutomahoutSettings settings,
			final int wStart, final int wEnd )
	{
		return new PassScope( ctx, settings, -1, wStart, wEnd + 1, wStart );
	}

	/** This window's scope at one of its frames: counts of its own, values and totals shared. */
	public PassScope atFrame( final int t )
	{
		return new PassScope( ctx, settings, t, scanStartTp, numTimepoints, earliestWritableTp, values, totals );
	}

	/** The sum of a pass's counts over every frame scope of this window (or this one plan run). */
	public int total( final String passId )
	{
		final Integer n = totals.get( passId );
		return n == null ? 0 : n;
	}

	/** What the pass with this id counted in this plan run; 0 if it did not run. */
	public int count( final String passId )
	{
		final Integer n = counts.get( passId );
		return n == null ? 0 : n;
	}

	/** Whether the pass with this id ran in this plan run. */
	public boolean ran( final String passId )
	{
		return counts.containsKey( passId );
	}

	void counted( final String passId, final int n )
	{
		counts.put( passId, n );
		totals.merge( passId, n, Integer::sum );
	}

	/** A value an earlier pass measured for the ones after it ({@code null} when none did). */
	@SuppressWarnings( "unchecked" )
	public < T > T get( final String key )
	{
		return ( T ) values.get( key );
	}

	public double getDouble( final String key, final double ifAbsent )
	{
		final Object v = values.get( key );
		return v instanceof Number ? ( ( Number ) v ).doubleValue() : ifAbsent;
	}

	public int getInt( final String key, final int ifAbsent )
	{
		final Object v = values.get( key );
		return v instanceof Number ? ( ( Number ) v ).intValue() : ifAbsent;
	}

	public void put( final String key, final Object value )
	{
		values.put( key, value );
	}
}
