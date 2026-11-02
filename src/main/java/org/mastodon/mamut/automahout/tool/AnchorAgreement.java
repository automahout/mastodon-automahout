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
 * Turns "what the detector found" and "what the image says is there" into the three labels a human
 * annotator produces: confirm, wrong, missed.
 *
 * <p>This is the automated form of the step a person actually performs when they adapt a detector by
 * hand — mark the over-detections {@code fp}, mark the cells that were missed {@code fn}, keep the
 * rest as {@code tp} — and it is the step this pipeline never had. Its training set was every
 * detection the model produced, labelled {@code tp}: a confirmation of the model's own output, and
 * nothing anywhere telling it that it was wrong. ELEPHANT's loss weights a correction ten times a
 * confirmation ({@code false_weight}); the pipeline was spending none of that budget.</p>
 *
 * <p><b>The two directions are not symmetric, and must not be treated as such.</b> A detection the
 * anchor did not find may be a dim real cell the anchor missed — it threshold at a fixed number of
 * sigma above background, so dim cells are exactly what it loses. Teaching that as background
 * deletes a real cell and then teaches the model to keep deleting it. So {@code wrong} requires a
 * SECOND, independent measurement to agree: the caller passes, per detection, whether the pixels at
 * that position read as background. {@code missed} needs no such corroboration — the anchor only
 * reports what stands above the frame's own noise floor, and the cost of a wrong {@code fn} is a
 * detection the next round has to argue with, not a cell removed from the movie.</p>
 *
 * <p>Pure geometry over two lists of {@code {x, y, z, radius}}, so it can be measured against the
 * ground truth without a model, a server or a graph.</p>
 */
public final class AnchorAgreement
{
	private AnchorAgreement()
	{}

	/**
	 * How close a detection's centre must sit to an anchor's centre to be the same cell, as a multiple
	 * of the anchor's own measured radius.
	 *
	 * <p>1.0 means "inside the cell the anchor measured". Larger admits a detection sitting on the
	 * cell's shoulder as the same cell, which is what an over-segmented pair looks like; smaller calls
	 * a slightly off-centre detection a different object. The anchor measures the radius from the
	 * image, so this stays a shape-relative number rather than a length to tune per dataset.</p>
	 */
	public static final double MATCH_RADII = 1.0;

	/** The three label sets, as indices into the two input lists. */
	public static final class Verdict
	{
		/** Indices into {@code detections}: matched an anchor. Trained as {@code tp}. */
		public final int[] confirmed;

		/** Indices into {@code detections}: no anchor, and the pixels agree. Trained as {@code fp}. */
		public final int[] wrong;

		/** Indices into {@code anchors}: no detection. Added to the graph and trained as {@code fn}. */
		public final int[] missed;

		/**
		 * Indices into {@code detections}: no anchor, but the pixels do NOT read as background. Nothing
		 * is asserted about these — see {@code DetectionTagLabels.UNLABELED}. Two estimates disagree,
		 * which is a reason to say nothing, not a reason to pick one.
		 */
		public final int[] disputed;

		Verdict( final int[] confirmed, final int[] wrong, final int[] missed, final int[] disputed )
		{
			this.confirmed = confirmed;
			this.wrong = wrong;
			this.missed = missed;
			this.disputed = disputed;
		}

		@Override
		public String toString()
		{
			return String.format( "tp=%d fp=%d fn=%d withheld=%d",
					confirmed.length, wrong.length, missed.length, disputed.length );
		}
	}

	/**
	 * Matches {@code detections} against {@code anchors} and splits both into the label sets.
	 *
	 * <p>Matching is one-to-one and greedy by distance, so two detections tiling one anchor produce one
	 * {@code confirmed} and one unmatched — which is the correct reading of an over-segmented pair, and
	 * the one the merge passes used to answer by inventing a covering ellipsoid instead.</p>
	 *
	 * @param detections        {@code {x, y, z, radius}} per detection, world units
	 * @param anchors           {@code {x, y, z, radius}} per anchor detection, world units
	 * @param readsAsBackground per detection, whether the pixels at its position say background; only
	 *                          a detection BOTH unmatched and reading as background is called wrong
	 * @param matchRadii        multiple of the anchor radius within which a centre is the same cell
	 */
	public static Verdict match( final List< double[] > detections, final List< double[] > anchors,
			final boolean[] readsAsBackground, final double matchRadii )
	{
		final int nd = detections.size(), na = anchors.size();
		if ( readsAsBackground != null && readsAsBackground.length != nd )
			throw new IllegalArgumentException( "readsAsBackground must have one entry per detection: "
					+ readsAsBackground.length + " vs " + nd );

		// All admissible (detection, anchor) pairs, nearest first. Greedy over that order is the
		// standard one-to-one assignment for this shape of problem and needs no tuning.
		final List< double[] > pairs = new ArrayList<>(); // {distance, detectionIndex, anchorIndex}
		for ( int a = 0; a < na; a++ )
		{
			final double[] anchor = anchors.get( a );
			final double reach = matchRadii * anchor[ 3 ];
			if ( !( reach > 0 ) )
				continue; // an anchor with no measured radius cannot claim anything
			for ( int d = 0; d < nd; d++ )
			{
				final double dist = distance( detections.get( d ), anchor );
				if ( dist <= reach )
					pairs.add( new double[] { dist, d, a } );
			}
		}
		pairs.sort( ( p, q ) -> Double.compare( p[ 0 ], q[ 0 ] ) );

		final boolean[] detectionTaken = new boolean[ nd ];
		final boolean[] anchorTaken = new boolean[ na ];
		final List< Integer > confirmed = new ArrayList<>();
		for ( final double[] pair : pairs )
		{
			final int d = ( int ) pair[ 1 ], a = ( int ) pair[ 2 ];
			if ( detectionTaken[ d ] || anchorTaken[ a ] )
				continue;
			detectionTaken[ d ] = true;
			anchorTaken[ a ] = true;
			confirmed.add( d );
		}

		final List< Integer > wrong = new ArrayList<>();
		final List< Integer > disputed = new ArrayList<>();
		for ( int d = 0; d < nd; d++ )
		{
			if ( detectionTaken[ d ] )
				continue;
			// Unmatched is not enough. The anchor loses dim cells by construction, so the pixels have to
			// say background too before this becomes a negative the model is taught at ten times weight.
			if ( readsAsBackground != null && readsAsBackground[ d ] )
				wrong.add( d );
			else
				disputed.add( d );
		}

		final List< Integer > missed = new ArrayList<>();
		for ( int a = 0; a < na; a++ )
			if ( !anchorTaken[ a ] )
				missed.add( a );

		return new Verdict( toArray( confirmed ), toArray( wrong ), toArray( missed ),
				toArray( disputed ) );
	}

	/** As {@link #match(List, List, boolean[], double)} with {@link #MATCH_RADII}. */
	public static Verdict match( final List< double[] > detections, final List< double[] > anchors,
			final boolean[] readsAsBackground )
	{
		return match( detections, anchors, readsAsBackground, MATCH_RADII );
	}

	private static double distance( final double[] a, final double[] b )
	{
		final double dx = a[ 0 ] - b[ 0 ], dy = a[ 1 ] - b[ 1 ], dz = a[ 2 ] - b[ 2 ];
		return Math.sqrt( dx * dx + dy * dy + dz * dz );
	}

	private static int[] toArray( final List< Integer > l )
	{
		final int[] out = new int[ l.size() ];
		for ( int i = 0; i < out.length; i++ )
			out[ i ] = l.get( i );
		Arrays.sort( out );
		return out;
	}
}
