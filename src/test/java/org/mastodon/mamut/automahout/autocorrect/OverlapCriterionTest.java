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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator.DUPLICATE_OVERLAP_FACTOR_DEFAULT;
import static org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator.overlapsAsFragments;

import org.junit.jupiter.api.Test;

/** Tests for the rule separating fragments of one over-segmented cell from two adjacent cells. */
public class OverlapCriterionTest
{
	/** Radius of a cell in the measured dataset: 17-21 µm across. */
	private static final double R = 9.0;

	private static boolean pairs( final double distance, final double rA, final double rB )
	{
		return overlapsAsFragments( distance * distance, rA, rB, DUPLICATE_OVERLAP_FACTOR_DEFAULT );
	}

	@Test
	public void theGateNowProposesAndTheBandDecides()
	{
		// The gate used to BE the safety, which is why it was set so tight that it admitted nothing:
		// in 92 frames of ground truth it let through zero genuinely distinct pairs, and merged
		// nothing. The safety now lives in SeedToObject, which segments the object around the pair, so
		// the gate's job is only to stop proposing pairs that are plainly far apart.
		assertTrue( pairs( 2 * R, R, R ) );      // proposed — the band then decides
		assertFalse( pairs( 3 * R, R, R ) );     // beyond any separation that could be one cell
	}

	@Test
	public void twoFragmentsOfOneCellAreAPair()
	{
		// Both centres inside the same cell — a few µm apart, not a diameter.
		assertTrue( pairs( 0.2 * R, R, R ) );
		assertTrue( pairs( 0.5 * R, R, R ) );
	}

	@Test
	public void theReachScalesWithTheLargerRadiusOnly()
	{
		// A big spot next to a small one: the reach is the big one's, not their sum.
		assertTrue( pairs( 0.6 * R, R, 1.0 ) );
		assertTrue( pairs( 2.0 * R, R, 1.0 ) );
		assertFalse( pairs( 2.5 * R, R, 1.0 ) );
		// And a pair of big cells gets no more licence than a pair of small ones, in proportion.
		assertFalse( pairs( 3 * R, R, R ) );
		assertFalse( pairs( 2 * 3 * R, 2 * R, 2 * R ) );
	}

	@Test
	public void coincidentCentresAlwaysPair()
	{
		assertTrue( pairs( 0.0, R, R ) );
	}

	@Test
	public void aLargerFactorMergesMore()
	{
		final double d = 0.9 * R;
		assertFalse( overlapsAsFragments( d * d, R, R, 0.7 ) );
		assertTrue( overlapsAsFragments( d * d, R, R, 1.0 ) );
	}
}
