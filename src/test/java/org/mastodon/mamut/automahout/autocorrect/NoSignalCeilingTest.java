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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * The threshold below which a detection has no signal at all. What made this necessary: the user
 * pointed at detections sitting on near-black image data, and the pass meant to remove them had
 * switched itself off — its baseline was a background median of zero, which it read as "no estimate".
 */
public class NoSignalCeilingTest
{
	@Test
	public void aBlackBackgroundIsAnAnswerNotAnAbsenceOfOne()
	{
		// The bug, in one assertion. A frame of mostly empty space has a background median of 0, and
		// that is the estimate's STRONGEST statement, not its weakest. Only the number of probes that
		// produced it can say whether it means anything.
		assertTrue( AutoCorrectOrchestrator.BackgroundStats.of( 1.0, 0.0, 0.0, 32 ).measured() );
		assertFalse( AutoCorrectOrchestrator.BackgroundStats.of( 1e-6, 0.0, 0.0, 3 ).measured() );
	}

	@Test
	public void theCeilingSitsWellBelowTheDimmestConfirmedCell()
	{
		// Background 0, dimmest confirmed cell 200: a detection has to reach 30 to count as anything.
		assertEquals( 30.0, AutoCorrectOrchestrator.noSignalCeiling( 0.0, 200.0 ), 1e-9 );
		// And the confirmed cell itself is nowhere near being deleted.
		assertTrue( 200.0 > AutoCorrectOrchestrator.noSignalCeiling( 0.0, 200.0 ) );
	}

	@Test
	public void aNearBlackDetectionIsBelowItAndAFaintCellIsNot()
	{
		final double ceiling = AutoCorrectOrchestrator.noSignalCeiling( 0.0, 200.0 );
		assertTrue( 4.0 <= ceiling, "a detection reading 4 against cells at 200 has nothing in it" );
		// A genuinely faint cell — half the dimmest confirmed one — must survive. This pass is not
		// allowed to be a dim-cell filter; that judgement belongs to the debris pass, under the floor.
		assertFalse( 100.0 <= ceiling );
	}

	@Test
	public void aNonZeroBackgroundShiftsItRatherThanScalingIt()
	{
		// Brightfield or a fluorescence frame with real offset: the climb is measured from the
		// background, not from zero, so a detection merely AT the background still has no signal.
		final double ceiling = AutoCorrectOrchestrator.noSignalCeiling( 100.0, 300.0 );
		assertEquals( 130.0, ceiling, 1e-9 );
		assertTrue( 100.0 <= ceiling );
	}

	@Test
	public void withoutConfirmedCellsItFallsBackToTheBackgroundAlone()
	{
		// Too few confirmed spots to describe a distribution: the percentile arrives as 0 and the
		// ceiling must not end up BELOW the background, which would delete nothing at all, nor above
		// it on the strength of a number that means nothing.
		assertEquals( 100.0, AutoCorrectOrchestrator.noSignalCeiling( 100.0, 0.0 ), 1e-9 );
		assertEquals( 0.0, AutoCorrectOrchestrator.noSignalCeiling( 0.0, 0.0 ), 1e-9 );
	}

	@Test
	public void aConfirmedCellDimmerThanTheBackgroundCannotRaiseTheCeiling()
	{
		// Possible on transmitted light, where cells are DARKER than their surroundings. The gap would
		// be negative and the ceiling would rise above the background, deleting real cells; it has to
		// degrade to the background instead.
		assertEquals( 100.0, AutoCorrectOrchestrator.noSignalCeiling( 100.0, 40.0 ), 1e-9 );
	}

	/**
	 * The half that was missing: without confirmed cells the ceiling was the background MEAN.
	 *
	 * <p>A detection sitting on empty background reads the background. Placed at the mean, the test
	 * deletes the half of them that happen to read below it and keeps the half that read above — which
	 * is what the user kept seeing, a detection on a completely dark region surviving every pass. The
	 * threshold was not slightly too low; it was in the middle of the distribution it was meant to
	 * exclude.</p>
	 */
	@Test
	public void withoutConfirmedCellsTheCeilingStandsAboveTheBackgroundNotInTheMiddleOfIt()
	{
		// This dataset: background 33044 with a spread of 7, and cells reading 33700.
		final double ceiling = AutoCorrectOrchestrator.noSignalCeiling( 33044.0, 7.0, 0.0 );

		assertTrue( ceiling > 33044.0,
				"half of a background population reads above its own mean, and all of it survived: "
						+ ceiling );
		assertEquals( 33044.0 + AutoCorrectOrchestrator.NO_SIGNAL_SIGMA * 7.0, ceiling, 1e-9 );
		assertTrue( ceiling < 33700.0,
				"and it stays far below a cell, which is what makes the claim safe" );
	}

	@Test
	public void theConfirmedCellsStillOutrankTheSpreadWhenThereAreSome()
	{
		// Two anchors is the measurement; one is a guess. A spread-based ceiling must not displace it.
		assertEquals( AutoCorrectOrchestrator.noSignalCeiling( 100.0, 300.0 ),
				AutoCorrectOrchestrator.noSignalCeiling( 100.0, 50.0, 300.0 ), 1e-9 );
	}

	@Test
	public void anUnmeasurableSpreadFallsBackToWhatItDidBefore()
	{
		// A frame with too few probes to describe a spread gets the old, conservative ceiling rather
		// than a made-up one.
		assertEquals( 100.0, AutoCorrectOrchestrator.noSignalCeiling( 100.0, 0.0, 0.0 ), 1e-9 );
		assertEquals( 100.0, AutoCorrectOrchestrator.noSignalCeiling( 100.0, -1.0, 0.0 ), 1e-9 );
	}

	/**
	 * The coefficient, measured on the population the pass actually sees.
	 *
	 * <p>This test used to assert {@code background + 1.9 sigma <= ceiling} and
	 * {@code background + 3.04 sigma > ceiling}, from a fixture of 8602 truth cells and 3680
	 * synthetic background PEAKS. Those numbers describe a population the detector does not produce.
	 * Re-measured on 3245 of the detector's own detections labelled against CTC silver truth, and
	 * anchored on the frame rather than on its emptiest regions
	 * ({@code benchmark/detection-gating/measure_no_signal_anchor.py}):</p>
	 *
	 * <pre>
	 *   k      caught (covers no cell)   lost (covers a cell)
	 *   1.00          60.6%                    0.19%
	 *   1.25          70.7%                    0.19%
	 *   1.50          76.6%                    0.57%
	 *   2.00          78.9%                    2.22%
	 *   2.50          80.2%                    3.98%
	 * </pre>
	 *
	 * <p>1.5 is the knee: 1.25 to 1.5 buys 5.9 points of catch for 0.38 of loss, about 15:1 in
	 * favour, and 1.5 to 2.0 buys 2.3 for 1.65, about 1.4:1 against.</p>
	 *
	 * <p>Rewritten rather than deleted, because it was a deliberate decision with a stated reason and
	 * a reversal of one should read as a reversal.</p>
	 */
	@Test
	public void theCeilingSitsWhereTheTwoPopulationsSeparate()
	{
		final double bg = 33044.0, sd = 7.0;
		final double ceiling = AutoCorrectOrchestrator.noSignalCeiling( bg, sd, 0.0 );
		assertEquals( bg + AutoCorrectOrchestrator.NO_SIGNAL_SIGMA * sd, ceiling, 1e-9 );
		assertTrue( ceiling >= bg + 1.0 * sd,
				"below one sigma the catch falls off a cliff: 22.5% at 0.5" );
		assertTrue( ceiling <= bg + 2.0 * sd,
				"at two sigma the loss is 2.22% for 2.3 more points of catch: " + ceiling );
	}

	/**
	 * The wrong answers this replaced, so none of them can come back quietly.
	 *
	 * <p>Three now. The bright-tail anchor abandoned sigma for a fraction of the climb to the image's
	 * bright tail -- 20.3 sigma on this dataset, 41.5% of the real cells, and the user watched plainly
	 * visible nuclei disappear. That one is still wrong and is still asserted against.</p>
	 *
	 * <p>The other two were coefficients, and this test used to assert {@code ceiling > bg + 1.5 sd}
	 * on the grounds that "1.5 sigma caught only 89.4% of background". That was measured on synthetic
	 * background peaks. On the detector's own output, anchored on the frame, 1.5 catches 76.6% of the
	 * detections covering no cell for 0.57% of the real ones, and 2.5 -- what shipped -- caught
	 * <em>nothing at all</em>, because it was anchored 3.8 sigma too low.</p>
	 */
	@Test
	public void neitherOfTheTwoWrongCoefficientsIsInUse()
	{
		final double bg = 33044.0, sd = 7.0;
		final double ceiling = AutoCorrectOrchestrator.noSignalCeiling( bg, sd, 0.0 );
		assertTrue( ceiling < bg + 2.5 * sd,
				"2.5 sigma was chosen on a fixture the detector does not produce" );
		assertTrue( ceiling < bg + AutoCorrectOrchestrator.NO_SIGNAL_MARGIN_FRACTION * ( 33992.0 - bg ),
				"the bright-tail anchor deleted 41.5% of real cells; it must not return" );
	}

	/**
	 * Ratchet: the ceiling is anchored on the FRAME, not on the emptiest part of it.
	 *
	 * <p>The pass deleted nothing in production, and the coefficient was only half of why.
	 * {@code estimateBackgroundStats} avoids positions near existing spots and reports the frame's
	 * emptiest regions, about 3.8 sigma below its own median here. A ceiling meant to sit 2.5 sigma
	 * above the background sat a sigma BELOW it, so no detection could fall under it: measured 0.0% of
	 * the detections covering no truth cell, on 3245 of the detector's own.</p>
	 *
	 * <p>Both estimates are right for their own question. That one answers "does this spot stand above
	 * the background", which wants the emptiest regions. This one asks "is this detection as dim as the
	 * frame", which wants the frame.</p>
	 */
	@Test
	public void theCeilingIsAnchoredOnTheFrameNotOnTheEmptiestPartOfIt() throws IOException
	{
		// Comments stripped first. Without that the assertion below matched the comment that EXPLAINS
		// why estimateBackgroundStats is not used here, which is the third time a ratchet in this project
		// has been fooled by its own prose.
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
						.replaceAll( "//[^\\n]*", "" );
		final int from = src.indexOf( "static int deleteSpotsWithNoSignal(" );
		assertTrue( from >= 0, "deleteSpotsWithNoSignal must still be findable" );
		final String body = src.substring( from, src.indexOf( "\n\tprivate static", from + 40 ) );
		assertTrue( body.contains( "AutoCorrectOrchestrator.frameLevelStats( context, timepoint )" )
				&& body.contains( "noSignalCeiling( frame[ 0 ],\n\t\t\t\tframe[ 1 ], dimmestConfirmed )" ),
				"the ceiling must be anchored on the frame's own level" );
		assertFalse( body.contains( "estimateBackgroundStats" ),
				"estimateBackgroundStats lands 3.8 sigma low; a ceiling on it can never fire" );
	}

	/**
	 * The coefficient is the one measured at THIS anchor, not the one measured at the old fixture.
	 *
	 * <p>2.5 came from a population the detector does not produce. On the detector's own output,
	 * anchored on the frame, 1.5 catches 76.6% of the detections covering no cell for 0.57% of the real
	 * ones, and 2.5 costs 3.98% for 3.6 points more catch.</p>
	 */
	@Test
	public void theCoefficientIsTheOneMeasuredAtThisAnchor()
	{
		assertEquals( 1.5, AutoCorrectOrchestrator.NO_SIGNAL_SIGMA, 1e-9 );
	}
}
