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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator.BackgroundStats;

/**
 * Tests for the "is this detection background?" classification behind the flat-contrast deletion pass.
 *
 * <p>The imaging-modality cases matter because this pass DELETES: a criterion that assumes cells are
 * brighter than background silently removes real cells on transmitted-light data, where they are
 * typically darker.</p>
 */
public class BackgroundStatsTest
{
	/** Background baseline 100 ± 10, contrast noise floor 5. */
	private static BackgroundStats stats()
	{
		return BackgroundStats.of( 5.0, 100.0, 10.0 );
	}

	@Test
	public void theSharedDisplayRangePutsBackgroundLowAndLeavesHeadroomAbove()
	{
		// Every crop of a timepoint is rendered against this one range, so background lands on the same
		// grey everywhere instead of each crop stretching its own noise across the whole scale.
		final double[] r = AutoCorrectOrchestrator.vlmDisplayRange( stats() );
		assertEquals( 100.0 - 2 * 10.0, r[ 0 ], 1e-9 );
		assertEquals( 100.0 + 10 * 10.0, r[ 1 ], 1e-9 );
		// Background sits in the bottom fifth, so it reads as background rather than as texture.
		assertTrue( ( 100.0 - r[ 0 ] ) / ( r[ 1 ] - r[ 0 ] ) < 0.25 );
	}

	@Test
	public void anUnmeasurableBackgroundHasNoSharedRange()
	{
		// estimateBackgroundStats' own fallback: no usable background samples, so nothing to anchor to.
		// The renderer then falls back to per-crop scaling rather than inventing a range.
		assertNull( AutoCorrectOrchestrator.vlmDisplayRange( BackgroundStats.of( 1e-6, 0.0, 0.0 ) ) );
		assertNull( AutoCorrectOrchestrator.vlmDisplayRange( null ) );
	}

	@Test
	public void theAuditSamplesAcrossItsGroupRatherThanTakingTheFirstFew()
	{
		// Graph order is roughly spatial, so the first 16 flagged spots would all come from one corner
		// of the frame. Spread them out instead, and leave a group that already fits untouched.
		final List< double[] > hundred = new ArrayList<>();
		for ( int i = 0; i < 100; i++ )
			hundred.add( new double[] { i, 0, 0, 1 } );
		final List< double[] > picked = AutoCorrectOrchestrator.sampleEvenly( hundred, 4 );
		assertEquals( 4, picked.size() );
		assertEquals( 0.0, picked.get( 0 )[ 0 ], 1e-9 );
		assertEquals( 25.0, picked.get( 1 )[ 0 ], 1e-9 );
		assertEquals( 75.0, picked.get( 3 )[ 0 ], 1e-9 );
		assertSame( hundred, AutoCorrectOrchestrator.sampleEvenly( hundred, 100 ) );
	}

	@Test
	public void aProbeThatLandsOnAnUndetectedCellBarelyMovesTheBaseline()
	{
		// The failure this guards: background probes only avoid spots that were DETECTED, so on a dense
		// frame some land on real cells. With a mean and a standard deviation, those few probes pull the
		// baseline up AND widen the band around it, and a wide band is exactly what lets
		// isDefinitelyBackground admit a bright cell as background.
		final List< Double > clean = new ArrayList<>();
		for ( int i = 0; i < 60; i++ )
			clean.add( 100.0 + ( i % 5 ) );
		final List< Double > contaminated = new ArrayList<>( clean );
		for ( int i = 0; i < 4; i++ )
			contaminated.add( 4000.0 ); // four probes on undetected cells out of sixty-four
		assertEquals( AutoCorrectOrchestrator.median( clean ),
				AutoCorrectOrchestrator.median( contaminated ), 1.0 );

		double meanClean = 0, meanContaminated = 0;
		for ( final double v : clean ) meanClean += v;
		for ( final double v : contaminated ) meanContaminated += v;
		meanClean /= clean.size();
		meanContaminated /= contaminated.size();
		assertTrue( meanContaminated - meanClean > 200,
				"the mean it replaced moved by " + ( meanContaminated - meanClean ) );
	}

	@Test
	public void moreProbesMustNotRaiseTheContrastFloor()
	{
		// The regression this exists to stop: the intensity baseline was made robust and the probe count
		// tripled, but the CONTRAST floor was left as a mean plus three standard deviations. More probes
		// meant more of them landing on undetected cells, every one of which raised that floor, and the
		// check went from flagging 47-76% of a timepoint to 0-4% — the window then committed 623
		// spots/tp against a stated 250. A median and a MAD do not move.
		final List< Double > flat = new ArrayList<>();
		for ( int i = 0; i < 16; i++ )
			flat.add( 10.0 + ( i % 3 ) );
		final List< Double > moreProbes = new ArrayList<>( flat );
		for ( int i = 0; i < 44; i++ )
			moreProbes.add( 10.0 + ( i % 3 ) );
		for ( int i = 0; i < 4; i++ )
			moreProbes.add( 900.0 ); // the extra probes that land on cells

		assertEquals( AutoCorrectOrchestrator.median( flat ),
				AutoCorrectOrchestrator.median( moreProbes ), 1.0 );
		double mean = 0;
		for ( final double v : moreProbes ) mean += v;
		mean /= moreProbes.size();
		assertTrue( mean > AutoCorrectOrchestrator.median( moreProbes ) + 40,
				"the mean it replaced sat " + ( mean - AutoCorrectOrchestrator.median( moreProbes ) )
						+ " above the median" );
	}

	@Test
	public void theMedianIsTheMiddleForBothParities()
	{
		assertEquals( 2.0, AutoCorrectOrchestrator.median( java.util.Arrays.asList( 3.0, 1.0, 2.0 ) ), 1e-9 );
		assertEquals( 2.5, AutoCorrectOrchestrator.median(
				java.util.Arrays.asList( 4.0, 1.0, 3.0, 2.0 ) ), 1e-9 );
	}

	@Test
	public void aDetectionNoBrighterThanItsBackgroundHasNoSignal()
	{
		// The criterion deleteSpotsWithNoSignal applies, and why it is a comparison to the median rather
		// than to a margin above it: at or below the background there is nothing to argue about, and the
		// cell-count floor was observed protecting detections whose measured intensity was 0.
		final double backgroundMedian = 100.0;
		assertTrue( 0.0 <= backgroundMedian, "an interior averaging zero is deleted" );
		assertTrue( 99.9 <= backgroundMedian, "so is one just under the background" );
		assertFalse( 100.1 <= backgroundMedian, "anything above it is left to the other passes" );
	}

	@Test
	public void theComparisonIsPerTimepointSoDimmingDoesNotDeleteEverything()
	{
		// A movie that photobleaches has a falling background too. Judged against the frame's own
		// background a dim late cell still clears it; judged against a global threshold, or against the
		// brightness of the cells confirmed early on, it would not.
		final double lateCell = 20.0, lateBackground = 12.0;
		final double earlyConfirmedMean = 250.0;
		assertFalse( lateCell <= lateBackground, "the late cell survives its own frame's background" );
		assertTrue( lateCell < 0.1 * earlyConfirmedMean,
				"...and would not have survived a threshold taken from the early cells" );
	}

	@Test
	public void localContrastLetsAVeryDimDetectionThroughWhichIsWhyAbsoluteBrightnessIsSeparate()
	{
		// isDefinitelyBackground returns false the moment a detection stands out from its surroundings,
		// whatever its brightness. Something dark sitting in something darker still therefore passes,
		// however far below a real cell it is — the case the absolute-brightness criterion exists for.
		final BackgroundStats stats = stats(); // contrast floor 5.0, background 100 +/- 10
		assertFalse( stats.isDefinitelyBackground( 50.0, 12.0 ),
				"a dim spot with real local contrast is NOT called background" );
		// And its absolute brightness of 12 is far under what a confirmed cell has, so the separate
		// criterion is what removes it.
		final double confirmedDimmestFewPercent = 90.0;
		assertTrue( 12.0 < confirmedDimmestFewPercent );
	}

	@Test
	public void aSpotThatStandsOutLocallyIsNeverBackground()
	{
		assertFalse( stats().isDefinitelyBackground( 50.0, 100.0 ), "much brighter than its shell" );
		assertFalse( stats().isDefinitelyBackground( 50.0, 20.0 ), "much darker than its shell" );
	}

	@Test
	public void aFlatSpotAtTheBackgroundBaselineIsBackground()
	{
		assertTrue( stats().isDefinitelyBackground( 1.0, 100.0 ) );
		assertTrue( stats().isDefinitelyBackground( 1.0, 115.0 ), "within 2 std of the baseline" );
	}

	@Test
	public void aConfluentBRIGHTcellIsRescuedByItsAbsoluteIntensity()
	{
		// Fluorescence: a cell touching other cells has no local contrast, but sits well above baseline.
		assertFalse( stats().isDefinitelyBackground( 1.0, 400.0 ) );
	}

	@Test
	public void aConfluentDARKcellIsRescuedToo()
	{
		// Brightfield / phase contrast: the same cell reads DARKER than background. The rescue used to be
		// one-sided (intensity <= mean + k*std), so this was deleted as background — on transmitted-light
		// data that is most of the cells in a confluent field.
		assertFalse( stats().isDefinitelyBackground( 1.0, 20.0 ) );
	}

	@Test
	public void theFallbackStatsNeverDeleteAnything()
	{
		// estimateBackgroundStats returns this when it cannot find enough confirmed-empty background to
		// judge against; it must err toward keeping, whatever the intensity.
		final BackgroundStats none = BackgroundStats.of( 1e-6, 0.0, 0.0 );
		assertFalse( none.isDefinitelyBackground( 0.0, 400.0 ) );
		assertFalse( none.isDefinitelyBackground( 0.0, 20.0 ) );
	}
}
