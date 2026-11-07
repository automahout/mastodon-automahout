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
 * The timepoints one run tracks and corrects, stated in the run's set-up: a bound every stage holds to, so
 * neither the agent's own start/end nor a resumed run can reach outside it.
 *
 * <p>It belongs to the run, not to the user's preferences: a range kept there carried over to the next project,
 * where it quietly tracked only part of a different movie. Nothing stated means the whole movie.</p>
 */
public final class RunRange
{
	/** Shared-data key of the stated range, an {@code int[] { start, end }}; {@code end < 0} = the last timepoint. */
	public static final String SHARED_KEY = "run.range";

	private RunRange()
	{}

	/**
	 * States the run's range.
	 *
	 * @param start the first timepoint, 0 or more
	 * @param end   the last timepoint (inclusive), or a negative value for the movie's last
	 */
	public static void set( final ToolContext ctx, final int start, final int end )
	{
		if ( start < 0 )
			throw new IllegalArgumentException( "start timepoint must be 0 or more, not " + start );
		ctx.setSharedData( SHARED_KEY, new int[] { start, end } );
	}

	/**
	 * {@code [minTp, maxTp]}: the movie's {@code [0, numTimepoints - 1]} narrowed by the stated range, if any. An
	 * end past the movie is the movie's last timepoint, and a start past the end is the end.
	 */
	public static int[] bounds( final ToolContext ctx, final int numTimepoints )
	{
		final int last = Math.max( 0, numTimepoints - 1 );
		final Object stated = ctx.getSharedData( SHARED_KEY );
		if ( !( stated instanceof int[] ) )
			return new int[] { 0, last };
		final int[] r = ( int[] ) stated;
		final int maxTp = r[ 1 ] < 0 ? last : Math.min( last, r[ 1 ] );
		final int minTp = Math.min( maxTp, Math.max( 0, r[ 0 ] ) );
		return new int[] { minTp, maxTp };
	}

	/** Whether {@link #bounds} leaves out part of the movie. */
	public static boolean restricts( final int[] bounds, final int numTimepoints )
	{
		return bounds[ 0 ] > 0 || bounds[ 1 ] < numTimepoints - 1;
	}
}
