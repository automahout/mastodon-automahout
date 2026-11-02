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
import java.util.Collections;
import java.util.List;

/**
 * Reviews one frame's detections the way a person proofreading ELEPHANT does: which reference cells the detections
 * found, which they missed, and which detections are false hits. Pure arithmetic on positions, so it can be called and
 * tested on its own; where the detections and the reference cells come from is the caller's business (the warm-up's
 * seeds, blobs at the frame's cell size, a user's annotations).
 *
 * <ul>
 * <li>A reference cell is <b>found</b> when a detection's centre lies within one cell radius of it; otherwise it is
 * <b>missed</b> (to be annotated again, fn).</li>
 * <li>A detection is a <b>false hit</b> when it lies more than two radii from every reference cell and more than one
 * radius from every candidate (a blob that is not a reference may be a cell the references left out, so a detection
 * on it is not called false).</li>
 * <li>The <b>size ratio</b> is the median radius of the detections on a reference cell over the cell radius.</li>
 * </ul>
 */
public final class FrameReview
{
	private FrameReview()
	{}

	/** What one review found. Positions are {x, y, z} in world units. */
	public static final class Result
	{
		public final int references, found, predicted;

		public final List< double[] > missed, falseHits;

		public final double sizeRatio;

		Result( final int references, final int found, final int predicted, final List< double[] > missed,
				final List< double[] > falseHits, final double sizeRatio )
		{
			this.references = references;
			this.found = found;
			this.predicted = predicted;
			this.missed = missed;
			this.falseHits = falseHits;
			this.sizeRatio = sizeRatio;
		}

		@Override
		public String toString()
		{
			return String.format( "%d of %d reference cells found, %d detections, %d false hit(s), size %.2fx", found,
					references, predicted, falseHits.size(), sizeRatio );
		}
	}

	/**
	 * @param detections {x, y, z, radius} per detection (radius may be NaN when unknown)
	 * @param references the cells the detections should find
	 * @param candidates other positions that may be cells (may be empty)
	 * @param radius the cell radius, world units
	 */
	public static Result review( final List< double[] > detections, final List< double[] > references,
			final List< double[] > candidates, final double radius )
	{
		final boolean[] hit = new boolean[ references.size() ];
		final List< Double > radii = new ArrayList<>();
		final List< double[] > falseHits = new ArrayList<>();
		for ( final double[] d : detections )
		{
			double nearest = Double.POSITIVE_INFINITY;
			for ( int k = 0; k < references.size(); k++ )
			{
				final double dist = distance( d, references.get( k ) );
				nearest = Math.min( nearest, dist );
				if ( dist <= radius )
					hit[ k ] = true;
			}
			if ( nearest <= radius )
			{
				if ( d.length > 3 && d[ 3 ] > 0 )
					radii.add( d[ 3 ] );
			}
			else if ( nearest > 2 * radius && nearestDistance( d, candidates ) > radius )
				falseHits.add( new double[] { d[ 0 ], d[ 1 ], d[ 2 ] } );
		}
		final List< double[] > missed = new ArrayList<>();
		int found = 0;
		for ( int k = 0; k < hit.length; k++ )
			if ( hit[ k ] )
				found++;
			else
				missed.add( references.get( k ) );
		Collections.sort( radii );
		final double sizeRatio = radii.isEmpty() ? Double.NaN : radii.get( radii.size() / 2 ) / radius;
		return new Result( references.size(), found, detections.size(), missed, falseHits, sizeRatio );
	}

	public static double distance( final double[] a, final double[] b )
	{
		return Math.sqrt( Math.pow( a[ 0 ] - b[ 0 ], 2 ) + Math.pow( a[ 1 ] - b[ 1 ], 2 ) + Math.pow( a[ 2 ] - b[ 2 ], 2 ) );
	}

	public static double nearestDistance( final double[] q, final List< double[] > points )
	{
		double nearest = Double.POSITIVE_INFINITY;
		for ( final double[] p : points )
			nearest = Math.min( nearest, distance( q, p ) );
		return nearest;
	}
}
