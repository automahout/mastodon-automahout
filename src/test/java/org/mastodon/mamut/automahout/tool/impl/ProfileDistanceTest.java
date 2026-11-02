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
package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The ranking that decides which detections are taught: how far each sits from the mean confirmed
 * cell in size, roundness and brightness. The arithmetic is reproduced here because the method itself
 * needs an image source; what matters is that the ordering it produces is the intended one.
 */
public class ProfileDistanceTest
{
	/** Mirrors ElephantTool.profileDistance: relative differences, summed. */
	private static double distance( final double size, final double aspect, final double intensity,
			final double[] reference )
	{
		return Math.abs( size - reference[ 0 ] ) / reference[ 0 ]
				+ Math.abs( aspect - reference[ 1 ] ) / reference[ 1 ]
				+ Math.abs( intensity - reference[ 2 ] ) / reference[ 2 ];
	}

	@Test
	public void theMeasuredDetectionPopulationRanksBelowSomethingCellLike()
	{
		// The real numbers from one window: confirmed cells 11.1 µm / 1.21 / 207, the detections kept
		// 9.3 µm / 1.33 / 140, the ones flat-contrast flagged 5.6 µm / 1.86 / 113. A cap that keeps the
		// closest must prefer the kept population over the flagged one, and prefer a confirmed-looking
		// cell over both.
		final double[] reference = { 11.1, 1.21, 207 };
		final double cellLike = distance( 11.0, 1.22, 205, reference );
		final double kept = distance( 9.3, 1.33, 140, reference );
		final double flagged = distance( 5.6, 1.86, 113, reference );
		assertTrue( cellLike < kept, "a confirmed-looking spot ranks best" );
		assertTrue( kept < flagged, "the flagged population ranks worst" );
	}

	@Test
	public void noSingleFeatureCanDominateTheRanking()
	{
		// Relative rather than absolute differences, so intensity (hundreds) cannot swamp size and
		// roundness (single digits) purely by unit scale.
		final double[] reference = { 11.1, 1.21, 207 };
		final double halfSize = distance( 5.55, 1.21, 207, reference );
		final double halfIntensity = distance( 11.1, 1.21, 103.5, reference );
		assertTrue( Math.abs( halfSize - halfIntensity ) < 1e-6,
				"halving either feature costs the same, got " + halfSize + " vs " + halfIntensity );
	}

	/** Mirrors the GT-consolidation settle test: round-to-round change under 10%. */
	private static boolean settled( final double raw, final double prev )
	{
		return Math.abs( raw - prev ) <= 0.10 * prev;
	}

	@Test
	public void gtRoundsKeepGoingWhileTheModelIsStillMoving()
	{
		// The run that prompted the loop: one GT pass left the model detecting ~700 spots/tp. A single
		// pass is "settled" only by never having anything to compare against, which is why the first
		// round can never end the loop on its own.
		assertTrue( settled( 700, 700 ), "a model that has stopped moving is settled" );
		assertTrue( !settled( 700, 300 ), "a 133% jump is not settled" );
		assertTrue( !settled( 300, 700 ), "a 57% fall is not settled" );
		assertTrue( settled( 315, 300 ), "5% is inside the probe's own noise" );
		assertTrue( !settled( 400, 300 ), "33% is not" );
	}

	/** Mirrors pickSeedTimepoints: n frames spread evenly from wStart to wEnd inclusive. */
	private static int[] seedTimepoints( final int wStart, final int wEnd, final int n )
	{
		final int span = wEnd - wStart;
		final int count = Math.max( 1, Math.min( n, span + 1 ) );
		final int[] tps = new int[ count ];
		if ( count == 1 )
		{
			tps[ 0 ] = wStart + span / 2;
			return tps;
		}
		for ( int i = 0; i < count; i++ )
			tps[ i ] = wStart + ( int ) Math.round( ( double ) i * span / ( count - 1 ) );
		return tps;
	}

	@Test
	public void theWarmUpTakesItsFramesFromNearTheStart()
	{
		// Unbounded, a 500-frame range gave the first and last frame of a developing embryo — two
		// different-looking problems trained into one model, which then detected 72 spots at one end and
		// 4 at the other.
		final int[] whole = seedTimepoints( 2, 499, 2 );
		assertTrue( whole[ 1 ] - whole[ 0 ] > 400, "the old span was the whole range: " + whole[ 1 ] );
		// Bounded to twenty timepoints past the start, both frames show the same stage.
		final int[] bounded = seedTimepoints( 2, Math.min( 499, 2 + 20 ), 2 );
		assertTrue( bounded[ 1 ] - bounded[ 0 ] <= 20, "bounded span: " + ( bounded[ 1 ] - bounded[ 0 ] ) );
		// A range shorter than the bound is unaffected.
		final int[] shortRange = seedTimepoints( 2, 9, 2 );
		assertTrue( shortRange[ 1 ] == 9, "a short range still uses its own end" );
	}

	/** Mirrors restoreAnnotationExtent's factor: clamped, with a dead zone, applied to covariance. */
	private static double covarianceScale( final double detectedSize, final double confirmedSize )
	{
		final double k = Math.max( 1.0, Math.min( 2.0, confirmedSize / detectedSize ) );
		return k <= 1.05 ? 1.0 : k * k;
	}

	@Test
	public void shrinkingDetectionsAreSentForTrainingAtTheConfirmedExtent()
	{
		// The drift: prediction fits ellipsoids to the CENTER region of an annotation, so training on a
		// detection unchanged makes the next round's centre a fraction of a fraction. Measured across
		// runs of one dataset: 10.5, 9.5, 8.3, 8.2 µm against confirmed cells at 8.5; on the other,
		// detections reached 8.2 against confirmed 11.1.
		final double scale = covarianceScale( 8.2, 11.1 );
		assertTrue( scale > 1.8 && scale < 1.9, "covariance scaled by k*k for a linear k, got " + scale );
		// Size is sqrt(trace(covariance)), so the linear size comes back to the confirmed value.
		assertTrue( Math.abs( 8.2 * Math.sqrt( scale ) - 11.1 ) < 0.01 );
	}

	@Test
	public void anExtentThatMatchesIsLeftAlone()
	{
		// 8.2 against 8.5 is inside the dead zone: real populations are not uniform, and a correction
		// that fires on every small mismatch would be chasing noise.
		assertEquals( 1.0, covarianceScale( 8.2, 8.5 ), 1e-9 );
		assertEquals( 1.0, covarianceScale( 11.1, 11.1 ), 1e-9 );
		// Detections LARGER than the confirmed cells are never shrunk — this corrects one drift, in one
		// direction, and shrinking annotations is what caused it.
		assertEquals( 1.0, covarianceScale( 15.0, 11.1 ), 1e-9 );
	}
}
