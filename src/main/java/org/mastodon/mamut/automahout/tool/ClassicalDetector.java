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

/**
 * Finds cells by measuring the image, with no model and no training.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The pipeline trained ELEPHANT on its own detections and then spent a long chain of independent
 * heuristic filters damping that loop. Measured on 31 frames of Fluo-N2DL-HeLa silver truth
 * (2876 cells), this — threshold, split by watershed, drop what is too small — scores
 * <b>F1 92.8%</b>, with 160 false positives and <b>six</b> over-segmentations, in milliseconds and
 * with nothing to train. See {@code benchmark/classical-baseline}.</p>
 *
 * <p>So on this class of image the detection problem is not what is hard. A self-training loop was
 * being created and then damped, and every defect found in that damping chain — a valley test that
 * merged 9% of what it existed to merge, guards applied at one site of five, synthesized negatives
 * landing on real cells — was a symptom of having no external anchor. This is the anchor: it
 * depends on the image alone, so it cannot drift with the model.</p>
 *
 * <p>It is not a replacement for a learned detector in general. It works where brightness means
 * signal. On phase contrast or brightfield a cell is often no brighter than its surroundings and
 * this returns nothing useful — which {@link ImageProfile} already decides, and which is exactly
 * where a trained model earns its place. What must not happen there either is training a model on
 * its own output.</p>
 *
 * <p>Pure arithmetic over a supplied plane, so it can be tested against real pixels without a
 * viewer.</p>
 */
public final class ClassicalDetector
{
	/**
	 * Level above the background, in sigma, at which a pixel belongs to a cell.
	 *
	 * <p>Measured: 3 sigma gives F1 92.8%, 5 sigma 92.5%, 8 sigma 91.2%. Higher thresholds lose
	 * recall (recall 94.0% -> 89.6%) faster than they gain precision, because a nucleus fades at its
	 * rim and a higher level eats into every cell rather than only into the dim ones.</p>
	 */
	public static final double LEVEL_SIGMA = 3.0;

	/** A detection found by {@link #detect}. */
	public static final class Detection
	{
		/** Centroid, in pixels of the plane that was passed in. */
		public final double x, y;

		/** Equivalent radius {@code sqrt(area/pi)}, in the same pixels. */
		public final double radius;

		/** Area in pixels. */
		public final int area;

		Detection( final double x, final double y, final double radius, final int area )
		{
			this.x = x;
			this.y = y;
			this.radius = radius;
			this.area = area;
		}
	}

	private ClassicalDetector()
	{}

	/**
	 * Cells in one plane.
	 *
	 * @param frame            row-major pixel values, {@code w} by {@code h}
	 * @param level            intensity at which a cell ends, i.e. background + {@link #LEVEL_SIGMA} sigma
	 * @param minArea          smallest region kept, in pixels
	 * @param minPeakDistance  closest two nuclei may be, in pixels; the watershed marker spacing
	 */
	public static List< Detection > detect( final double[] frame, final int w, final int h,
			final double level, final int minArea, final int minPeakDistance )
	{
		final List< Detection > out = new ArrayList<>();
		if ( w <= 0 || h <= 0 || frame.length < w * h )
			return out;

		final boolean[] mask = new boolean[ w * h ];
		for ( int i = 0; i < mask.length; i++ )
			mask[ i ] = frame[ i ] > level;
		open3x3( mask, w, h );

		final double[] dist = SeedToObject.distanceTransform( mask, w, h );
		final int[] labels = SeedToObject.watershed( dist, mask, w, h,
				SeedToObject.markers( dist, mask, w, h, minPeakDistance ) );

		int maxLabel = 0;
		for ( final int l : labels )
			maxLabel = Math.max( maxLabel, l );
		if ( maxLabel == 0 )
			return out;

		final int[] area = new int[ maxLabel + 1 ];
		final double[] sumX = new double[ maxLabel + 1 ];
		final double[] sumY = new double[ maxLabel + 1 ];
		for ( int y = 0; y < h; y++ )
		{
			for ( int x = 0; x < w; x++ )
			{
				final int l = labels[ y * w + x ];
				if ( l == 0 )
					continue;
				area[ l ]++;
				sumX[ l ] += x;
				sumY[ l ] += y;
			}
		}
		for ( int l = 1; l <= maxLabel; l++ )
		{
			if ( area[ l ] < minArea )
				continue;
			out.add( new Detection( sumX[ l ] / area[ l ], sumY[ l ] / area[ l ],
					Math.sqrt( area[ l ] / Math.PI ), area[ l ] ) );
		}
		return out;
	}

	/**
	 * Morphological opening with a 3x3 square: erode, then dilate.
	 *
	 * <p>Removes single-pixel noise that crossed the threshold without shrinking a cell, which is
	 * what keeps the false-positive count at 160 over 2876 cells rather than in the thousands.</p>
	 */
	static void open3x3( final boolean[] mask, final int w, final int h )
	{
		final boolean[] eroded = new boolean[ mask.length ];
		for ( int y = 0; y < h; y++ )
		{
			for ( int x = 0; x < w; x++ )
			{
				boolean all = true;
				for ( int dy = -1; dy <= 1 && all; dy++ )
				{
					for ( int dx = -1; dx <= 1; dx++ )
					{
						final int nx = x + dx, ny = y + dy;
						if ( nx < 0 || nx >= w || ny < 0 || ny >= h || !mask[ ny * w + nx ] )
						{
							all = false;
							break;
						}
					}
				}
				eroded[ y * w + x ] = all;
			}
		}
		for ( int y = 0; y < h; y++ )
		{
			for ( int x = 0; x < w; x++ )
			{
				boolean any = false;
				for ( int dy = -1; dy <= 1 && !any; dy++ )
				{
					for ( int dx = -1; dx <= 1; dx++ )
					{
						final int nx = x + dx, ny = y + dy;
						if ( nx >= 0 && nx < w && ny >= 0 && ny < h && eroded[ ny * w + nx ] )
						{
							any = true;
							break;
						}
					}
				}
				mask[ y * w + x ] = any;
			}
		}
	}
}
