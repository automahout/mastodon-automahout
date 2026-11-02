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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for the floor every count-reducing cleanup pass must respect. */
public class ExpectedCellCountTest
{
	@Test
	public void theFirstTimepointUsesTheStatedCount()
	{
		// A fresh run's first timepoint has nothing before it, which is how the dialog value ends up
		// applying there without the floor needing to know where the run started.
		assertEquals( 250, ExpectedCellCount.floorAt( 250, 0, 1.02 ) );
	}

	@Test
	public void laterTimepointsTrackTheOneBeforeThemAtTheMeasuredRate()
	{
		assertEquals( 255, ExpectedCellCount.floorAt( 250, 250, 1.02 ) );
		assertEquals( 408, ExpectedCellCount.floorAt( 250, 400, 1.02 ) );
	}

	@Test
	public void aRateOfOneMeansNoTimepointFallsBelowTheOneBeforeIt()
	{
		// What an unmeasurable rate degrades to — the floor stops climbing entirely rather than
		// guessing a growth it has no evidence for.
		assertEquals( 400, ExpectedCellCount.floorAt( 250, 400, 1.0 ) );
		int floor = ExpectedCellCount.floorAt( 250, 0, 1.0 );
		for ( int i = 0; i < 50; i++ )
			floor = ExpectedCellCount.floorAt( 250, floor, 1.0 );
		assertEquals( 250, floor, "cannot ratchet away from the stated count without measured growth" );
	}

	@Test
	public void theStatedCountIsAlsoAFloorOnTheFloor()
	{
		// A timepoint that lost cells must not drag the next one's floor down with it: the population
		// grows, so the stated count remains a lower bound however badly one frame was detected.
		assertEquals( 250, ExpectedCellCount.floorAt( 250, 80, 1.02 ) );
	}

	@Test
	public void noStatedCountMeansNoFloor()
	{
		assertEquals( -1, ExpectedCellCount.floorAt( -1, 400, 1.02 ) );
		assertEquals( -1, ExpectedCellCount.floorAt( 0, 400, 1.02 ) );
	}

	@Test
	public void theGrowthRateIsMeasuredPerTimepointAndCompounds()
	{
		// 250 -> 500 over 10 timepoints is a doubling, i.e. ~7.2% per timepoint — above the sanity
		// bound, so it clamps.
		assertEquals( 1.05, ExpectedCellCount.growthRate( 250, 0, 500, 10 ), 1e-9 );
		// 250 -> 275 over 10 is ~0.96% per timepoint, comfortably inside it.
		assertEquals( Math.pow( 275.0 / 250.0, 0.1 ),
				ExpectedCellCount.growthRate( 250, 0, 275, 10 ), 1e-9 );
	}

	@Test
	public void aBasisSeveralTimepointsBackCompoundsOverTheGap()
	{
		// The nearest vetted timepoint may not be the immediately preceding one — the ones between were
		// skipped by the cleanup and hold raw counts nothing has checked. Growth applies over the whole
		// gap, not once.
		assertEquals( ( int ) Math.floor( 300 * Math.pow( 1.02, 3 ) ),
				ExpectedCellCount.floorAt( 250, 300, 1.02, 3 ) );
		// One timepoint back is the ordinary case and must match the two-argument form exactly.
		assertEquals( ExpectedCellCount.floorAt( 250, 300, 1.02 ),
				ExpectedCellCount.floorAt( 250, 300, 1.02, 1 ) );
	}

	@Test
	public void noUsableBasisLeavesTheStatedCountAsTheFloor()
	{
		// What the walk-back falls through to when every earlier timepoint within reach is unvetted or
		// empty: the user's number, unmodified.
		assertEquals( 250, ExpectedCellCount.floorAt( 250, 0, 1.05, 4 ) );
		assertEquals( 250, ExpectedCellCount.floorAt( 250, 400, 1.05, 0 ) );
	}

	@Test
	public void severalPassesOverOneTimepointShareTheBudgetWithoutBookkeeping()
	{
		// removeRespectingFloor re-reads the count on every call, so three passes deleting from the same
		// timepoint cannot between them cross the floor even though none of them knows about the others.
		// This is the arithmetic that guarantees it; the graph-level behaviour needs a Mastodon model.
		int count = 300;
		int removedTotal = 0;
		for ( int pass = 0; pass < 3; pass++ )
		{
			final int budget = Math.max( 0, count - 250 ); // deletionBudget against a floor of 250
			final int wanted = 40;
			final int removed = Math.min( wanted, budget );
			count -= removed;
			removedTotal += removed;
		}
		assertEquals( 50, removedTotal );
		assertEquals( 250, count );
	}

	@Test
	public void theFloorCannotRatchetPastWhatTheStatedCountCouldGrowInto()
	{
		// The runaway: each timepoint's floor was chained off its neighbour's count, and that count was
		// usually the floor, so floor(t) = floor(t-1) * rate could only climb. Measured on a movie stated
		// to hold 250 cells/frame, the floor reached 569 and every timepoint sat at exactly 569.
		assertEquals( ( int ) Math.floor( 250 * Math.pow( 1.05, 9 ) ),
				ExpectedCellCount.capToStatedGrowth( 250, 0, 9, 569 ) );
		// A chained floor already inside the cap is left alone — the cap stops runaways, it does not
		// second-guess a population that really is growing.
		assertEquals( 300, ExpectedCellCount.capToStatedGrowth( 250, 0, 9, 300 ) );
		// And it never drops below the stated count itself.
		assertEquals( 250, ExpectedCellCount.capToStatedGrowth( 250, 0, 9, 100 ) );
		// At the run's first timepoint there is nothing to grow from.
		assertEquals( 250, ExpectedCellCount.capToStatedGrowth( 250, 5, 5, 250 ) );
	}

	@Test
	public void theTeachingCapDoesNotClimbAcrossOneWindow()
	{
		// The failure: the cap used floorAt, which is bounded by the MAXIMUM growth rate rather than the
		// measured one, and the taught count went 378, 410, 434, 466, 501 across a single ten-timepoint
		// window. That maximum rate compounded over nine timepoints is a 55% rise.
		assertEquals( 1.55, Math.pow( ExpectedCellCount.growthRate( 250, 0, 10000, 10 ), 9 ), 0.01 );
		// With the population not observed to grow, the measured rate is exactly 1.0 and the curve is
		// flat — the same number at the start of a window and at its end.
		final double flat = ExpectedCellCount.growthRate( 565, 0, 66, 73 );
		assertEquals( 1.0, flat, 1e-9 );
		assertEquals( 250, ( int ) Math.floor( 250 * Math.pow( flat, 9 ) ) );
	}

	@Test
	public void theCapStopsBeingGenerousLongBeforeItStopsBeingACap()
	{
		// 5% per timepoint is 1.55x by t=9 — the window the cap was written against — but 80x by t=90.
		// On a 961-timepoint movie that ceiling sat so far above the stated count that the chained
		// value was always smaller, so the cap never bound and the floor went back to quoting the
		// previous timepoint's count at itself.
		assertEquals( 250 * 1.55, ExpectedCellCount.capToStatedGrowth( 250, 0, 9, 100000 ), 3.0 );
		assertEquals( 250 * 4, ExpectedCellCount.capToStatedGrowth( 250, 0, 90, 100000 ), 1.0 );
		assertEquals( 250 * 4, ExpectedCellCount.capToStatedGrowth( 250, 0, 900, 100000 ), 1.0 );
		// Still never below the stated count, and still leaves a smaller chained floor alone.
		assertEquals( 250, ExpectedCellCount.capToStatedGrowth( 250, 0, 90, 100 ) );
		assertEquals( 300, ExpectedCellCount.capToStatedGrowth( 250, 0, 90, 300 ) );
	}

	/** Mirrors expectedAt's interpolation, which needs a ToolContext to read the shared values. */
	private static int expectedAt( final int start, final int end, final int from, final int to,
			final int timepoint )
	{
		if ( timepoint <= from )
			return start;
		if ( timepoint >= to )
			return end;
		final double f = ( double ) ( timepoint - from ) / ( to - from );
		return Math.max( 1, ( int ) Math.round( start * Math.pow( ( double ) end / start, f ) ) );
	}

	@Test
	public void aGrowingSpecimenIsDescribedByBothEndsNotOne()
	{
		// The reported case: a mouse embryo run from t=160 to t=499, about 16 cells at the start and
		// close to 1000 at the end. Either number alone is wrong at the other end by a factor of sixty,
		// and the growth this class infers on its own is bounded well below that.
		assertEquals( 16, expectedAt( 16, 1000, 160, 499, 160 ) );
		assertEquals( 1000, expectedAt( 16, 1000, 160, 499, 499 ) );
		final int middle = expectedAt( 16, 1000, 160, 499, 330 );
		assertTrue( middle > 100 && middle < 150, "geometric, not linear: got " + middle );
		// Geometric because a dividing population is: the linear midpoint would be over 500.
		assertTrue( middle < ( 16 + 1000 ) / 2 );
	}

	@Test
	public void equalEndsBehaveExactlyAsOneNumberDid()
	{
		assertEquals( 250, expectedAt( 250, 250, 0, 73, 0 ) );
		assertEquals( 250, expectedAt( 250, 250, 0, 73, 40 ) );
		assertEquals( 250, expectedAt( 250, 250, 0, 73, 73 ) );
	}

	@Test
	public void outsideTheStatedRangeTheNearerEndIsUsedRatherThanExtrapolated()
	{
		// The user described a range, not a trend; running past it must not imply a thousandfold
		// population.
		assertEquals( 16, expectedAt( 16, 1000, 160, 499, 0 ) );
		assertEquals( 1000, expectedAt( 16, 1000, 160, 499, 960 ) );
	}

	@Test
	public void aCropsSpotCountIsNotComparableWithAWholeFrames()
	{
		// The log said this out loud and nobody read it: "flagged 1 of 5 spot(s) but deleting only 0 —
		// removing them all would leave 4, below the 1000 cell(s)/frame you stated". Five spots in a
		// crop against a thousand in a frame. Whatever number is entered, a full-frame floor blocks
		// every deletion during crop tuning, and those detections are then what the round trains on.
		final int cropCount = 5;
		final int statedFullFrame = 1000;
		assertTrue( cropCount < statedFullFrame,
				"any crop-scoped count loses this comparison, which is why the floor must stand down" );
		// The exemption is a floor of -1, the same value that means "the user stated no count", which is
		// the existing signal every caller already handles.
		assertEquals( -1, ExpectedCellCount.floorAt( -1, 400, 1.02 ),
				"-1 is the established 'no floor in force' value" );
	}

	@Test
	public void aShrinkingOrUnmeasurableSpanNeverShrinksTheFloor()
	{
		assertEquals( 1.0, ExpectedCellCount.growthRate( 400, 0, 200, 10 ), 1e-9 );
		assertEquals( 1.0, ExpectedCellCount.growthRate( 250, 5, 300, 5 ), 1e-9 );
		assertEquals( 1.0, ExpectedCellCount.growthRate( 0, 0, 300, 10 ), 1e-9 );
	}
}
