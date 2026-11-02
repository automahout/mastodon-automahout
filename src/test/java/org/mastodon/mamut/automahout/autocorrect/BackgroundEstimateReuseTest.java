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
package org.mastodon.mamut.automahout.autocorrect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for when a background estimate may stand in for another timepoint's — the rule that decides
 * whether a timepoint pays ~90 seconds to measure its own background or reuses the one measured a few
 * timepoints ago.
 */
public class BackgroundEstimateReuseTest
{
	@Test
	public void aTimepointReusesItsOwnEstimateUntilItGainsSpots()
	{
		// Measured with 20 spots excluded; nothing has been added since, so it still describes this
		// timepoint's background.
		assertTrue( AutoCorrectOrchestrator.backgroundEstimateReusable( 20, 20, 0 ) );
		// A pass DELETED spots. Fewer to exclude only means the exclusion was more generous than it
		// needed to be — the estimate is still valid, and this is what keeps the three cleanup passes
		// that all ask for it from paying three times.
		assertTrue( AutoCorrectOrchestrator.backgroundEstimateReusable( 20, 8, 0 ) );
		// A fresh predict landed. Now there are spots the probes did not know to avoid, so some of them
		// were measuring a cell rather than the background.
		assertFalse( AutoCorrectOrchestrator.backgroundEstimateReusable( 20, 40, 0 ) );
	}

	@Test
	public void aNeighbouringTimepointsEstimateIsGoodEnough()
	{
		// The whole point of the reuse: t=161 does not re-measure what t=160 just measured.
		assertTrue( AutoCorrectOrchestrator.backgroundEstimateReusable( 16, 13, 1 ) );
		assertTrue( AutoCorrectOrchestrator.backgroundEstimateReusable( 16, 13, 9 ) );
	}

	@Test
	public void theReuseDoesNotWalkForwardForever()
	{
		// A window's worth, and no further: past that the estimate is old enough that illumination
		// drift over the acquisition is worth paying to re-measure.
		assertFalse( AutoCorrectOrchestrator.backgroundEstimateReusable( 16, 13, 11 ) );
		assertFalse( AutoCorrectOrchestrator.backgroundEstimateReusable( 16, 13, 340 ) );
	}

	@Test
	public void anEstimateMeasuredOnANearEmptyTimepointIsNotReusedForAFullOne()
	{
		// The first density probe measures a timepoint holding nothing, so every probe position was
		// accepted — including the ones sitting on cells nobody had detected yet. Handing that to a
		// timepoint now holding 40 detections would lock in the worst estimate of the run.
		assertFalse( AutoCorrectOrchestrator.backgroundEstimateReusable( 0, 40, 1 ) );
		assertFalse( AutoCorrectOrchestrator.backgroundEstimateReusable( 5, 40, 3 ) );
		// Comparably informed is enough; it does not have to be better.
		assertTrue( AutoCorrectOrchestrator.backgroundEstimateReusable( 20, 40, 3 ) );
	}

	@Test
	public void anEmptyTimepointTakesWhateverIsAround()
	{
		// Nothing to exclude means nothing an older estimate could have got wrong by not excluding it.
		assertTrue( AutoCorrectOrchestrator.backgroundEstimateReusable( 0, 0, 5 ) );
	}

	@Test
	public void aCropsBackgroundIsNeverHandedToAFullFrameCommit()
	{
		// The crop-restricted tuning pass measures the background INSIDE its crop. The full-frame
		// commit that follows it at the same timepoint asks the same question about the whole image, and
		// must not be answered with one region's noise floor.
		assertEquals( "crop@160", AutoCorrectOrchestrator.backgroundCacheKey( "crop", 160 ) );
		assertEquals( "full@160", AutoCorrectOrchestrator.backgroundCacheKey( "full", 160 ) );
		assertFalse( AutoCorrectOrchestrator.backgroundCacheKey( "crop", 160 )
				.equals( AutoCorrectOrchestrator.backgroundCacheKey( "full", 160 ) ) );
	}
}
