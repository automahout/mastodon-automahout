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

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.FrameReview;

/** {@link ReviewFrameTool#describe}: what the review.frame tool reports. */
public class ReviewFrameToolTest
{
	private static final List< double[] > CELLS = Arrays.asList( new double[] { 10, 10, 10 }, new double[] { 30, 10, 10 } );

	@Test
	public void aFrameWithEveryCellAndNoFalseHitPasses()
	{
		final FrameReview.Result r = FrameReview.review(
				Arrays.asList( new double[] { 10, 10, 10, 4 }, new double[] { 30, 10, 10, 4 } ), CELLS,
				Collections.emptyList(), 4 );
		final String s = ReviewFrameTool.describe( 7, 4, "given", false, r );
		assertTrue( s, s.startsWith( "t=7: cell radius 4.00 (given); 2 reference cells; 2 of 2 reference cells found" ) );
		assertTrue( s, s.endsWith( "-> passes the review" ) );
	}

	@Test
	public void missedCellsAndFalseHitsAreListedWithTheirPositions()
	{
		final FrameReview.Result r = FrameReview.review( Collections.singletonList( new double[] { 60, 40, 10, 4 } ), CELLS,
				Collections.emptyList(), 4 );
		final String s = ReviewFrameTool.describe( 7, 4, "given", true, r );
		assertTrue( s, s.contains( "a neighbouring frame confirms" ) );
		assertTrue( s, s.contains( "does not pass the review" ) );
		assertTrue( s, s.contains( "\n  missed: (10.0, 10.0, 10.0) (30.0, 10.0, 10.0)" ) );
		assertTrue( s, s.contains( "\n  false hits: (60.0, 40.0, 10.0)" ) );
	}
}
