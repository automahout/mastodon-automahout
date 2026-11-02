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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * The three labels a human annotator produces, derived without one.
 *
 * <p>Distances are in µm on Fluo-N2DL-HeLa's scale: 0.645 µm pixels, nuclei of radius ~6.4 µm.</p>
 */
public class AnchorAgreementTest
{
	private static final double R = 6.4;

	private static double[] at( final double x, final double y )
	{
		return new double[] { x, y, 0.0, R };
	}

	private static List< double[] > list( final double[]... d )
	{
		return new ArrayList<>( Arrays.asList( d ) );
	}

	/** Nothing reads as background: the safest input, and the one that must never produce an fp. */
	private static boolean[] allSignal( final int n )
	{
		return new boolean[ n ];
	}

	private static boolean[] allBackground( final int n )
	{
		final boolean[] b = new boolean[ n ];
		Arrays.fill( b, true );
		return b;
	}

	@Test
	public void agreementIsConfirmedAndNothingElseIsSaid()
	{
		final List< double[] > d = list( at( 100, 100 ), at( 200, 200 ) );
		final List< double[] > a = list( at( 101, 99 ), at( 200, 203 ) );
		final AnchorAgreement.Verdict v = AnchorAgreement.match( d, a, allBackground( 2 ) );
		assertArrayEquals( new int[] { 0, 1 }, v.confirmed );
		assertEquals( 0, v.wrong.length );
		assertEquals( 0, v.missed.length );
	}

	@Test
	public void aCellTheDetectorMissedBecomesAnFn()
	{
		// The label the pipeline could not produce, and the only one that raises recall directly.
		final List< double[] > d = list( at( 100, 100 ) );
		final List< double[] > a = list( at( 100, 100 ), at( 400, 400 ) );
		final AnchorAgreement.Verdict v = AnchorAgreement.match( d, a, allSignal( 1 ) );
		assertArrayEquals( new int[] { 0 }, v.confirmed );
		assertArrayEquals( "the anchor's unmatched cell is the missed one", new int[] { 1 }, v.missed );
	}

	@Test
	public void aDetectionOnBackgroundBecomesAnFpOnlyWhenThePixelsAgree()
	{
		final List< double[] > d = list( at( 100, 100 ), at( 400, 400 ) );
		final List< double[] > a = list( at( 100, 100 ) );

		// Pixels say background at the unmatched detection: two independent measurements agree.
		final AnchorAgreement.Verdict agreed =
				AnchorAgreement.match( d, a, new boolean[] { false, true } );
		assertArrayEquals( new int[] { 1 }, agreed.wrong );
		assertEquals( 0, agreed.disputed.length );

		// Pixels say there IS something there. The anchor loses dim cells by construction, so this is
		// a disagreement between two estimates, not a false positive — and teaching it as background
		// would delete a real cell and then teach the model to keep deleting it.
		final AnchorAgreement.Verdict disagreed = AnchorAgreement.match( d, a, allSignal( 2 ) );
		assertEquals( 0, disagreed.wrong.length );
		assertArrayEquals( new int[] { 1 }, disagreed.disputed );
	}

	@Test
	public void twoDetectionsTilingOneCellLeaveOneUnmatched()
	{
		// An over-segmented pair: matching is one-to-one, so the anchor claims the nearer detection and
		// the other is reported rather than merged into a covering ellipsoid.
		final List< double[] > d = list( at( 98, 100 ), at( 106, 100 ) );
		final List< double[] > a = list( at( 100, 100 ) );
		final AnchorAgreement.Verdict v = AnchorAgreement.match( d, a, allBackground( 2 ) );
		assertArrayEquals( "the nearer detection is the match", new int[] { 0 }, v.confirmed );
		assertArrayEquals( new int[] { 1 }, v.wrong );
		assertEquals( 0, v.missed.length );
	}

	@Test
	public void twoRealNeighboursAreBothConfirmed()
	{
		// The case the merge passes got wrong: two cells 1.8 radii apart, each detected, each anchored.
		final List< double[] > d = list( at( 100, 100 ), at( 100 + 1.8 * R, 100 ) );
		final List< double[] > a = list( at( 100, 100 ), at( 100 + 1.8 * R, 100 ) );
		final AnchorAgreement.Verdict v = AnchorAgreement.match( d, a, allBackground( 2 ) );
		assertArrayEquals( new int[] { 0, 1 }, v.confirmed );
		assertEquals( 0, v.wrong.length );
		assertEquals( 0, v.missed.length );
	}

	@Test
	public void aDetectionJustOutsideTheAnchorIsNotItsMatch()
	{
		final List< double[] > a = list( at( 100, 100 ) );
		final AnchorAgreement.Verdict inside =
				AnchorAgreement.match( list( at( 100 + 0.99 * R, 100 ) ), a, allBackground( 1 ) );
		assertArrayEquals( new int[] { 0 }, inside.confirmed );
		final AnchorAgreement.Verdict outside =
				AnchorAgreement.match( list( at( 100 + 1.01 * R, 100 ) ), a, allBackground( 1 ) );
		assertEquals( 0, outside.confirmed.length );
		assertArrayEquals( new int[] { 0 }, outside.wrong );
		assertArrayEquals( new int[] { 0 }, outside.missed );
	}

	@Test
	public void anEmptyDetectionSetMakesEveryAnchorAMissedCell()
	{
		// A cold start: the model has found nothing, and every cell in the frame is a correction worth
		// ten confirmations. This is the case the pipeline handled by lowering thresholds instead.
		final List< double[] > a = list( at( 10, 10 ), at( 50, 50 ), at( 90, 90 ) );
		final AnchorAgreement.Verdict v =
				AnchorAgreement.match( new ArrayList<>(), a, new boolean[ 0 ] );
		assertArrayEquals( new int[] { 0, 1, 2 }, v.missed );
		assertEquals( 0, v.confirmed.length );
	}

	@Test
	public void anAnchorWithNoMeasuredRadiusClaimsNothing()
	{
		final List< double[] > d = list( at( 100, 100 ) );
		final List< double[] > a = list( new double[] { 100, 100, 0, 0 } );
		final AnchorAgreement.Verdict v = AnchorAgreement.match( d, a, allSignal( 1 ) );
		assertEquals( 0, v.confirmed.length );
		assertArrayEquals( "it is still a position the detector did not cover", new int[] { 0 },
				v.missed );
	}
}
