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

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** {@link FrameReview}: found, missed and false hits against reference cells. */
public class FrameReviewTest
{
	private static final double R = 4;

	private static final List< double[] > CELLS = Arrays.asList( new double[] { 10, 10, 10 }, new double[] { 30, 10, 10 } );

	@Test
	public void aDetectionOnEachCellFindsThemAll()
	{
		final FrameReview.Result r = FrameReview.review(
				Arrays.asList( new double[] { 11, 10, 10, 4 }, new double[] { 30, 12, 10, 4.4 } ), CELLS,
				Collections.emptyList(), R );
		assertEquals( 2, r.found );
		assertEquals( 0, r.missed.size() );
		assertEquals( 0, r.falseHits.size() );
		assertEquals( 1.1, r.sizeRatio, 1e-9 ); // median of 1.0 and 1.1, upper of two
	}

	@Test
	public void anUncoveredCellIsMissedAndAFarDetectionIsFalse()
	{
		final FrameReview.Result r = FrameReview.review(
				Arrays.asList( new double[] { 10, 10, 10, 4 }, new double[] { 60, 40, 10, 4 } ), CELLS,
				Collections.emptyList(), R );
		assertEquals( 1, r.found );
		assertEquals( 30, r.missed.get( 0 )[ 0 ], 0 );
		assertEquals( 1, r.falseHits.size() );
	}

	@Test
	public void aDetectionOnACandidateIsNotFalse()
	{
		// A blob the references left out may be a cell: a detection there is not corrected as background.
		final FrameReview.Result r = FrameReview.review( Collections.singletonList( new double[] { 60, 40, 10, 4 } ), CELLS,
				Collections.singletonList( new double[] { 61, 40, 10 } ), R );
		assertEquals( 0, r.falseHits.size() );
	}

	@Test
	public void aDetectionJustBesideACellIsNeitherFoundNorFalse()
	{
		// Between one and two radii: off the cell, but too close to it to call background.
		final FrameReview.Result r = FrameReview.review( Collections.singletonList( new double[] { 16, 10, 10, 4 } ), CELLS,
				Collections.emptyList(), R );
		assertEquals( 0, r.found );
		assertEquals( 0, r.falseHits.size() );
	}
}
