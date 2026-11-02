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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * A detection must be sized from the image, and must not be deleted for being wider than its cell.
 *
 * <p>Two halves of one regression. Raising the no-signal ceiling to where it can actually see
 * background made it delete the small bright bodies either side of a division: a mitotic figure or a
 * newborn daughter inside a normally-sized ellipsoid puts most of the thirteen interior samples on
 * background, so the full-extent median reads background however bright the body is.</p>
 *
 * <p>The guard is the core reading. The cause is that nothing ever checked a detection's geometry
 * against the image -- {@code restoreAnnotationExtent} rescales what is SENT FOR TRAINING, grow-only
 * and capped at 1.3x, and never touches the graph.</p>
 */
public class DetectionExtentTest
{
	@Test
	public void noReadingMeansLeaveTheDetectionExactlyAsItWas()
	{
		assertEquals( "NaN is 'no reading', never 'small'", 0.0,
				AutoCorrectOrchestrator.extentFactor( Double.NaN, 5.0 ), 0.0 );
		assertEquals( 0.0, AutoCorrectOrchestrator.extentFactor( 0.0, 5.0 ), 0.0 );
		assertEquals( "a detection with no radius cannot be rescaled", 0.0,
				AutoCorrectOrchestrator.extentFactor( 5.0, 0.0 ), 0.0 );
	}

	@Test
	public void aDetectionAlreadyAgreeingWithTheImageIsNotRewritten()
	{
		assertEquals( 0.0, AutoCorrectOrchestrator.extentFactor( 5.0, 5.0 ), 0.0 );
		assertEquals( "within 10% is agreement", 0.0,
				AutoCorrectOrchestrator.extentFactor( 5.4, 5.0 ), 0.0 );
		assertEquals( 0.0, AutoCorrectOrchestrator.extentFactor( 4.6, 5.0 ), 0.0 );
	}

	/**
	 * The estimator's p05 ratio is 0.11 over 2117 CTC silver-truth cells -- rays that all escaped
	 * through a dim rim and stopped at once. Acting on that would call a nucleus a ninth of its size.
	 */
	@Test
	public void aReadingThatSaysANucleusIsANinthOfItsSizeIsRefused()
	{
		assertEquals( 0.5, AutoCorrectOrchestrator.extentFactor( 0.11 * 5.0, 5.0 ), 1e-9 );
		assertEquals( 1.4, AutoCorrectOrchestrator.extentFactor( 3.0 * 5.0, 5.0 ), 1e-9 );
	}

	/** The p95 is 1.32, so the ordinary spread of real readings must pass through untouched. */
	@Test
	public void theMeasuredSpreadOfRealCellsIsNotClamped()
	{
		assertEquals( 1.32, AutoCorrectOrchestrator.extentFactor( 1.32 * 5.0, 5.0 ), 1e-9 );
		assertEquals( 0.76, AutoCorrectOrchestrator.extentFactor( 0.76 * 5.0, 5.0 ), 1e-9 );
	}

	/** Shrinking is the direction that was impossible before: the only resizer was grow-only. */
	@Test
	public void itShrinksAsWellAsGrows()
	{
		assertTrue( "a detection twice its cell must be halved",
				AutoCorrectOrchestrator.extentFactor( 5.0, 10.0 ) < 1.0 );
		assertTrue( AutoCorrectOrchestrator.extentFactor( 10.0, 5.0 ) > 1.0 );
	}

	/** The core is the samples within half a radius, and it exists in both geometries. */
	@Test
	public void theCoreIsASubsetOfTheInteriorAndCoversMoreThanTheCentre()
	{
		for ( final boolean volumetric : new boolean[] { true, false } )
		{
			int core = 0;
			for ( final double[] o : AutoCorrectOrchestrator.spotInteriorOffsets( volumetric ) )
				if ( o[ 0 ] * o[ 0 ] + o[ 1 ] * o[ 1 ] + o[ 2 ] * o[ 2 ] <= 0.25 + 1e-9 )
					core++;
			assertTrue( "volumetric=" + volumetric + ": a core of " + core
					+ " cannot describe a body, and its median would be one voxel", core >= 5 );
			assertTrue( "the core must be a strict subset, or it is just the extent again", core
					< AutoCorrectOrchestrator.spotInteriorOffsets( volumetric ).length );
		}
	}

	/** Ratchet: a detection is kept when EITHER reading holds signal, and the extent pass runs first. */
	@Test
	public void bothReadingsMustAgreeBeforeADetectionIsDeleted() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
						.replaceAll( "//[^\\n]*", "" );
		final int at = src.indexOf( "int deleteSpotsWithNoSignal(" );
		assertTrue( "deleteSpotsWithNoSignal not found", at > 0 );
		final String body = src.substring( at, src.indexOf( "\n\tprivate static", at + 1 ) );
		assertTrue( "the core reading must be consulted before deleting",
				body.contains( "profile[ 3 ] <= ceiling" ) );
		assertFalse( "the full-extent median must not decide alone",
				body.contains( "|| profile[ 2 ] <= ceiling )" ) );
		assertTrue( "the extent must be measured from the image before the no-signal test",
				detectedPlan().indexOf( org.mastodon.mamut.automahout.tool.impl.DetectedPasses.EXTENT )
						< detectedPlan().indexOf( org.mastodon.mamut.automahout.tool.impl.DetectedPasses.NO_SIGNAL ) );
	}

	/**
	 * A detection may never grow over another detection's centre.
	 *
	 * <p>The user's t=61 frame showed single detections covering two clearly separate nuclei. The ray
	 * walk stops where the intensity drops below background + 5 sigma, and between two touching nuclei
	 * it never does: the rays pointing at the neighbour abstain, and the median of the outward ones
	 * measures the PAIR. Where the model put two detections it separated two cells, and a geometric
	 * rule read off the image does not get to overrule that -- which is why duplicate merging is
	 * opt-in and off by default.</p>
	 */
	@Test
	public void aDetectionMayNotGrowOverItsNeighboursCentre()
	{
		final List< double[] > seeds = new ArrayList<>( Arrays.asList(
				new double[] { 0.0, 0.0, 0.0, 5.0 },
				new double[] { 12.0, 0.0, 0.0, 5.0 } ) );
		assertEquals( "the limit is the distance to the nearest other detection", 12.0,
				AutoCorrectOrchestrator.neighbourLimit( seeds, 0 ), 1e-9 );
		assertEquals( 12.0, AutoCorrectOrchestrator.neighbourLimit( seeds, 1 ), 1e-9 );
	}

	@Test
	public void aLoneDetectionIsNotLimitedByANeighbourItDoesNotHave()
	{
		final List< double[] > seeds = new ArrayList<>();
		seeds.add( new double[] { 0.0, 0.0, 0.0, 5.0 } );
		assertTrue( "a lone detection must not be capped at zero",
				AutoCorrectOrchestrator.neighbourLimit( seeds, 0 ) > 1e6 );
	}

	@Test
	public void theNearestNeighbourIsTheOneThatBinds()
	{
		final List< double[] > seeds = new ArrayList<>( Arrays.asList(
				new double[] { 0.0, 0.0, 0.0, 5.0 },
				new double[] { 40.0, 0.0, 0.0, 5.0 },
				new double[] { 0.0, 9.0, 0.0, 5.0 } ) );
		assertEquals( 9.0, AutoCorrectOrchestrator.neighbourLimit( seeds, 0 ), 1e-9 );
	}

	/** The measured p95 is 1.32, so the ordinary spread must still pass under the tightened cap. */
	@Test
	public void theTightenedCapStillAdmitsTheMeasuredSpread()
	{
		assertEquals( 1.32, AutoCorrectOrchestrator.extentFactor( 1.32 * 5.0, 5.0 ), 1e-9 );
		assertTrue( "1.4 is exactly the growth that turns one nucleus into a touching pair",
				AutoCorrectOrchestrator.extentFactor( 9.0 * 5.0, 5.0 ) < 2.0 );
	}

	/** Ratchet: the neighbour cap must actually be applied, not merely available. */
	@Test
	public void theNeighbourCapIsApplied() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
						.replaceAll( "//[^\\n]*", "" );
		assertTrue( "resizeToMeasuredExtent must bound growth by the nearest neighbour",
				src.contains( "neighbourLimit( seeds, i )" ) );
	}

	/** Ratchet: the split must run, and before linking. */
	@Test
	public void theSplitRunsInsidePredictWindow() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
						.replaceAll( "//[^\\n]*", "" );
		assertTrue( "ELEPHANT's predict must run the detected-frame plan",
				src.contains( "PassPlan.forRun( context, CorrectionPass.Stage.DETECTED, DetectedPasses.BUILT_IN )" ) );
		final java.util.List< String > plan = detectedPlan();
		assertTrue( "nothing splits a detection that covers two cells",
				plan.contains( org.mastodon.mamut.automahout.tool.impl.DetectedPasses.SPLIT ) );
		assertTrue( "the split must follow the resize, so it judges image-fitted geometry",
				plan.indexOf( org.mastodon.mamut.automahout.tool.impl.DetectedPasses.EXTENT )
						< plan.indexOf( org.mastodon.mamut.automahout.tool.impl.DetectedPasses.SPLIT ) );
	}

	/**
	 * A split that shatters a detection must be refused outright.
	 *
	 * <p>Nothing bounded the pieces. The benchmark asked only whether a detection split into two or
	 * more, never how many. Measured on the same populations, a genuine merged pair — two cells by
	 * construction — comes back as 4 to 8 pieces 3.4% of the time and a single real cell 0.9%, and the
	 * user watched one cell collect a dozen overlapping circles.</p>
	 */
	@Test
	public void aSplitThatShattersADetectionIsRefused() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
						.replaceAll( "//[^\\n]*", "" );
		// The pieces are bounded by construction now: the shape fit yields exactly two. Counting
		// intensity basins made three pieces of 10 of 19 real pairs it split, and must not return
		// to the pass without being re-measured.
		final int at = src.indexOf( "public static int splitMergedDetections( final ToolContext ctx, final int timepoint,\n" );
		final String pass = src.substring( at, src.indexOf( "static double neighbourLimit(", at ) );
		assertTrue( "pieces come from the shape fit", pass.contains( "ShapeSplit.analyse(" ) );
		assertFalse( "not from counting intensity basins", pass.contains( "objectCentresWithin" ) );
		assertTrue( "and there are exactly two", src.contains( "final double[][] centres = new double[ 2 ][];" ) );
	}

	/**
	 * Ratchet: the saddle gate is gone and must not come back without being re-measured.
	 *
	 * <p>It was measured behind the RADIUS-percentile gate, where it took 47% of the false splits for
	 * 11% of the true. Behind the core-unevenness gate only 0.2% of single cells reach it, so it buys
	 * nothing and costs real splits: dropping it is +9.1 points of true split for +0.20 of false.</p>
	 */
	@Test
	public void theSaddleGateIsGoneAndTheMeasurementSaysWhy() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 );
		assertFalse( "gating on the saddle costs 9 points of true split behind the core-CV gate",
				src.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" )
						.contains( "SPLIT_MAX_SADDLE" ) );
		assertTrue( "the numbers that removed it must stay where the constant was",
				src.contains( "45:1 trade" ) );
	}

	/**
	 * The split pass must count its own refusals.
	 *
	 * <p>Five rounds of this pass were tuned by guessing which gate had refused a case in a
	 * screenshot, and the guess was wrong four times: the population was not the one the pipeline
	 * sees, the detection was not off-centre, the failure was not temporal instability, and the gate
	 * that mattered had stopped mattering when the one in front of it was replaced. The gates are
	 * cheap to count, so a screenshot should never again need a guess.</p>
	 */
	@Test
	public void theSplitPassCountsWhyItRefused() throws IOException
	{
		final String orch = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 );
		for ( final String slot : new String[] { "FUNNEL_CONSIDERED", "FUNNEL_EXTENT_ORDINARY",
				"FUNNEL_NO_EXTENT", "FUNNEL_AT_EDGE", "FUNNEL_ONE_ELLIPSE", "FUNNEL_PIECE_ON_NEIGHBOUR",
				"FUNNEL_SPLIT" } )
			assertTrue( "every refusal needs its own count: " + slot,
					orch.contains( "tally( funnelOut, " + slot + " )" ) );

		final String tool = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 );
		assertTrue( "counting without printing tells nobody anything",
				tool.contains( "describeSplitFunnel( splitFunnel )" ) );
		assertTrue( "and the line itself must be a method a test can call, not an inline format",
				orch.contains( "public static String describeSplitFunnel(" ) );
	}

	/**
	 * The bar for "uneven" is read off the frame, with the constant as a floor under it.
	 *
	 * <p>A constant measured on one specimen cannot know what a nucleus looks like on the next, and a
	 * run that has to work without anybody confirming a cell cannot rely on it. Measured on a
	 * realistic per-frame mixture: absolute 82.9%/0.76%, median+3 MAD 81.8%/0.52%.</p>
	 */
	@Test
	public void theUnevennessBarIsReadOffTheFrameNotFromAConstant()
	{
		final List< Double > even = new ArrayList<>();
		for ( int i = 0; i < 40; i++ )
			even.add( 0.10 + ( i % 5 ) * 0.01 );
		final double tight = AutoCorrectOrchestrator.medianPlusMads( even, 3.0 );
		assertTrue( "a tight frame must put the bar near its own cells: " + tight,
				tight > 0.10 && tight < 0.25 );

		final List< Double > textured = new ArrayList<>();
		for ( int i = 0; i < 40; i++ )
			textured.add( 0.40 + ( i % 5 ) * 0.02 );
		assertTrue( "a textured specimen must raise it, or the constant flags every cell",
				AutoCorrectOrchestrator.medianPlusMads( textured, 3.0 ) > 0.40 );
	}

	/** The outliers being hunted must not be able to drag the bar out to meet them. */
	@Test
	public void aFewExtremeValuesDoNotMoveTheBar()
	{
		final List< Double > base = new ArrayList<>();
		for ( int i = 0; i < 40; i++ )
			base.add( 0.10 + ( i % 5 ) * 0.01 );
		final double clean = AutoCorrectOrchestrator.medianPlusMads( base, 3.0 );
		final List< Double > contaminated = new ArrayList<>( base );
		for ( int i = 0; i < 4; i++ )
			contaminated.add( 5.0 );
		assertEquals( "a mean and a sigma would have moved here; a median and a MAD do not",
				clean, AutoCorrectOrchestrator.medianPlusMads( contaminated, 3.0 ), 0.02 );
	}

	/** Too few detections to describe a spread means no opinion, not a guessed one. */
	@Test
	public void aFrameTooSparseToHaveASpreadSaysNothing()
	{
		final List< Double > few = new ArrayList<>( Arrays.asList( 0.1, 0.2, 0.9 ) );
		assertEquals( 0.0, AutoCorrectOrchestrator.medianPlusMads( few, 3.0 ), 0.0 );
		assertEquals( 0.0, AutoCorrectOrchestrator.medianPlusMads( null, 3.0 ), 0.0 );
	}

	/** A short or absent funnel says nothing rather than throwing. */
	@Test
	public void aMalformedFunnelIsSilent()
	{
		assertEquals( "", AutoCorrectOrchestrator.describeSplitFunnel( null ) );
		assertEquals( "", AutoCorrectOrchestrator.describeSplitFunnel( new int[ 2 ] ) );
	}

	/**
	 * The unevenness reading has to be taken about the background.
	 *
	 * <p>It was taken about zero. A reading inside a detection is an absolute 16-bit intensity on
	 * this dataset's 33072 offset, so a nucleus whose interior varies by 500 grey levels read as a CV
	 * of 0.015 — against a bar of 0.25. The dump of the detector's own output measured every class at
	 * 0.00 to 0.01: the gate was arithmetically incapable of firing, which is why only 2 to 8
	 * detections per window were ever examined.</p>
	 *
	 * <p>Third time this 33000 offset has made a test inert. The valley test divided a raw ratio on
	 * it; {@code saddleRatio} subtracts it for exactly this reason.</p>
	 */
	@Test
	public void unevennessIsMeasuredAgainstTheBackgroundNotAgainstZero()
	{
		// Five samples 500 apart, sitting on a 33072 offset: 33200 .. 33700, mean 33450.
		final double[] v = { 33200, 33300, 33450, 33600, 33700 };
		double sum = 0, sumSq = 0;
		for ( final double x : v )
		{
			sum += x;
			sumSq += x * x;
		}
		final double aboutZero = Math.sqrt( sumSq / v.length - ( sum / v.length ) * ( sum / v.length ) )
				/ ( sum / v.length );
		assertTrue( "about zero this reads as nothing: " + aboutZero, aboutZero < 0.01 );

		final double about = AutoCorrectOrchestrator.relativeSpread( sum, sumSq, v.length, 33072.0 );
		assertTrue( "about the background it is the real unevenness: " + about,
				about > 0.4 && about < 0.5 );
	}

	/** Samples that do not stand above the background have no unevenness to report. */
	@Test
	public void samplesAtTheBackgroundReportNothingRatherThanAHugeRatio()
	{
		final double[] v = { 33070, 33071, 33072, 33073, 33074 };
		double sum = 0, sumSq = 0;
		for ( final double x : v )
		{
			sum += x;
			sumSq += x * x;
		}
		assertEquals( "a tiny denominator must not manufacture a large spread", 0.0,
				AutoCorrectOrchestrator.relativeSpread( sum, sumSq, v.length, 33072.0 ), 1e-9 );
		assertEquals( 0.0, AutoCorrectOrchestrator.relativeSpread( 0, 0, 0, 0.0 ), 0.0 );
	}

	/** Ratchet: the profile must fetch the background, or the reading is about zero again. */
	@Test
	public void theProfileTakesItsSpreadsAboutTheBackground() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
						.replaceAll( "//[^\\n]*", "" );
		assertTrue( "both spreads go through the one helper that subtracts the background",
				src.contains( "relativeSpread( sum, sumSq, n, base )" )
						&& src.contains( "relativeSpread( coreSum, coreSumSq, core.size(), base )" ) );
		assertFalse( "and neither divides by its own mean any more",
				src.contains( "mean * mean ) ) / mean" ) );
		assertTrue( "the background has to be fetched for this timepoint",
				src.contains( "estimateBackgroundStats( ctx, timepoint )" ) );
	}

	/**
	 * Ratchet: a frame with no measured background is refused outright.
	 *
	 * <p>{@code interiorIntensityProfile} falls back to a base of zero, so every spread is taken about
	 * the intensity offset: the whole frame reads about 0.005 and its own median-plus-MADs bar lands
	 * at about 0.005 too, which about half the detections clear. The only thing that had ever stopped
	 * that was the 0.25 constant, by accident. {@code measured()} is a sample COUNT and is not implied
	 * by {@code intensityStd > 0}, so the existing guard above it does not cover this.</p>
	 */
	@Test
	public void aFrameWithNoMeasuredBackgroundIsNotSplit() throws IOException
	{
		final String split = splitPassSource();
		assertTrue( "an unmeasured background makes every core reading inert, not merely noisy",
				split.contains( "if ( !stats.measured() )" ) );
	}

	/**
	 * Ratchet: the pass says out loud that it only works on fluorescence.
	 *
	 * <p>It always did — {@code relativeSpread} returns 0 for anything not brighter than its
	 * background, so on phase contrast the gate could never fire. That is the right behaviour and the
	 * wrong way to express it: nothing in the source or the log said so, and an assumption that holds
	 * only by arithmetic is one nobody can find when it breaks.</p>
	 */
	@Test
	public void theSplitStatesItsFluorescenceAssumption() throws IOException
	{
		final String split = splitPassSource();
		assertTrue( "the modality the reading depends on must be checked, not merely relied on",
				split.contains( "fluorescenceLike" ) );
	}

	/** The body of {@code splitMergedDetections}, comments stripped. */
	private static String splitPassSource() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		final int from = src.indexOf( "public static int splitMergedDetections( final ToolContext ctx,"
				+ " final int timepoint,\n\t\t\tfinal int[] funnelOut )" );
		assertTrue( "the split pass must still be findable by its signature", from >= 0 );
		final int to = src.indexOf( "\n\tpublic static ", from + 40 );
		return src.substring( from, to > from ? to : src.length() );
	}

	/**
	 * A timepoint the pass declines outright has to reach the log.
	 *
	 * <p>Two guards can refuse a frame before a single detection is examined, and both return zero.
	 * The funnel was only printed when something had been considered, so those frames produced no line
	 * at all — the same invisible assumption the guards were added to remove, sitting one level
	 * further up.</p>
	 */
	@Test
	public void aFrameDeclinedOutrightSaysSoInTheLog()
	{
		final int[] funnel = new int[ AutoCorrectOrchestrator.FUNNEL_SIZE ];
		funnel[ AutoCorrectOrchestrator.FUNNEL_FRAME_NO_BACKGROUND ] = 2;
		funnel[ AutoCorrectOrchestrator.FUNNEL_FRAME_NOT_FLUORESCENT ] = 3;
		final String line = AutoCorrectOrchestrator.describeSplitFunnel( funnel );
		assertTrue( "nothing was considered, but something was decided: " + line,
				line.contains( "Refused 5 timepoint(s)" ) );
		assertTrue( "and which reason, or the line cannot be acted on: " + line,
				line.contains( "2 with no measured background" )
						&& line.contains( "3 that do not read as fluorescence" ) );
	}

	/** With nothing considered and nothing refused the pass simply did not run: no line. */
	@Test
	public void anUntouchedFunnelIsStillSilent()
	{
		assertEquals( "", AutoCorrectOrchestrator.describeSplitFunnel(
				new int[ AutoCorrectOrchestrator.FUNNEL_SIZE ] ) );
	}

	/** Ratchet: the caller must print the line when a frame was refused, not only when it looked. */
	@Test
	public void theCallerPrintsTheLineForARefusedFrame() throws IOException
	{
		final String tool = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 );
		assertTrue( "a frame declined before any detection was examined must still be reported",
				tool.contains( "FUNNEL_FRAME_NO_BACKGROUND ] > 0" )
						&& tool.contains( "FUNNEL_FRAME_NOT_FLUORESCENT ] > 0" )
						&& tool.contains( "FUNNEL_FRAME_NO_SPREAD ] > 0" ) );
	}

	/**
	 * Ratchet: the cell-edge level is anchored on the FRAME, not on the spot-avoiding background.
	 *
	 * <p>{@code CELL_EXTENT_SIGMA = 5} was calibrated in {@code benchmark/cell-extent} against the
	 * frame's own median, where it gives a radius 1.02x the truth. {@code measuredCellRadius} was
	 * handed {@code estimateBackgroundStats} instead, which deliberately avoids positions near existing
	 * spots and so reports the EMPTIEST regions -- about 50 grey levels, 3.8 sigma, below the frame
	 * median on Fluo-N2DL-HeLa_01. Every ray then walked that much further and production delivered
	 * 1.31x.</p>
	 *
	 * <p>Neither the 0.5 clamp nor the 10% dead zone was the cause: they move 1.18 to 1.20. Anchored on
	 * the frame, the annotation's overlap with the cell goes from an IoU of 0.56 to 0.73.</p>
	 *
	 * <p>The two estimates answer different questions and are both right for theirs.
	 * {@code estimateBackgroundStats} answers "does this spot stand above the background", which wants
	 * the emptiest regions. A level calibrated against the frame cannot be handed it.</p>
	 */
	@Test
	public void theCellEdgeLevelIsAnchoredOnTheFrameNotOnTheEmptiestPartOfIt() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 );
		final int from = src.indexOf( "public static double measuredCellRadius(" );
		assertTrue( "measuredCellRadius must still be findable", from >= 0 );
		final String body = src.substring( from, src.indexOf( "\n\t/**", from ) );
		assertTrue( "the level must come from the frame's own median and spread",
				body.contains( "frameLevelStats( ctx, timepoint )" )
						&& body.contains( "frame[ 0 ] + CELL_EXTENT_SIGMA * frame[ 1 ]" ) );
		assertFalse( "estimateBackgroundStats reports the emptiest regions, not the frame — it lands "
				+ "3.8 sigma low here and sends every ray too far",
				body.contains( "estimateBackgroundStats" ) );
	}

	/**
	 * Ratchet: a division branch is not held to a cap measured from ordinary motion.
	 *
	 * <p>The run learns the per-frame displacement cap as about four times the median consecutive step.
	 * A division is not a consecutive step — the daughters spring apart. Measured on 94 real divisions
	 * and 8374 ordinary steps in CTC tracking truth: an ordinary step runs 1.5 µm at the median, a
	 * mother-to-daughter step 10.6, and <b>79.3% of real division branches are longer than the cap</b>.
	 * {@code pruneLongLinks} deletes existing links, so the linker could make the join and this would
	 * cut it again — which is what a daughter cell that is detected but not tracked looks like.</p>
	 *
	 * <p>{@code orphanJoinRefusal} already had the exemption; this was the second of two sites and did
	 * not. The same one-of-N shape as the cell-count floor, the merge switch and the refinement
	 * guards.</p>
	 */
	@Test
	public void aDivisionBranchIsNotJudgedAsOrdinaryMotion() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		final int from = src.indexOf( "static int pruneLongLinks(" );
		assertTrue( "pruneLongLinks must still be findable", from >= 0 );
		final String body = src.substring( from, src.indexOf( "\n\tstatic ", from + 40 ) );
		assertTrue( "a link out of a spot with two children is a division branch",
				body.contains( "srcRef.outgoingEdges().size() > 1" ) );
		assertTrue( "and is held to the division cap instead",
				body.contains( "maxDisplacement * DIVISION_DISPLACEMENT_FACTOR" ) );
		assertTrue( "an ordinary link is judged exactly as before",
				body.contains( "dist > radiusThreshold\n\t\t\t\t\t\t\t\t\t|| ( maxDisplacement > 0.0 && dist > maxDisplacement )" ) );
	}

	/**
	 * The factor is the one the measurement chose, and it is not a loosening of ordinary linking.
	 *
	 * <pre>
	 *   division cap    real divisions cut    ordinary steps it makes reachable
	 *     x2.0                15.4%                        2.01%
	 *     x2.5                 4.3%                        2.11%
	 *     x3.0                 0.5%                        2.14%
	 * </pre>
	 */
	@Test
	public void theDivisionFactorIsTheMeasuredOne()
	{
		assertEquals( 3.0, AutoCorrectOrchestrator.DIVISION_DISPLACEMENT_FACTOR, 1e-9 );
		assertTrue( "below 2.5 the measurement says real divisions start being cut in numbers",
				AutoCorrectOrchestrator.DIVISION_DISPLACEMENT_FACTOR >= 2.5 );
	}

	/** The funnel line names every refusal, the bar the frame used, and has no unconsumed specifier. */
	@Test
	public void theFunnelLineFormats()
	{
		final int[] funnel = new int[ AutoCorrectOrchestrator.FUNNEL_SIZE ];
		funnel[ AutoCorrectOrchestrator.FUNNEL_CONSIDERED ] = 50;
		funnel[ AutoCorrectOrchestrator.FUNNEL_EXTENT_ORDINARY ] = 40;
		funnel[ AutoCorrectOrchestrator.FUNNEL_NO_EXTENT ] = 3;
		funnel[ AutoCorrectOrchestrator.FUNNEL_ONE_ELLIPSE ] = 2;
		funnel[ AutoCorrectOrchestrator.FUNNEL_PIECE_ON_NEIGHBOUR ] = 3;
		funnel[ AutoCorrectOrchestrator.FUNNEL_AT_EDGE ] = 4;
		funnel[ AutoCorrectOrchestrator.FUNNEL_ABSTAINED ] = 1;
		funnel[ AutoCorrectOrchestrator.FUNNEL_SPLIT ] = 1;
		funnel[ AutoCorrectOrchestrator.FUNNEL_BAR ] = ( int ) Math.round( 1.277 * AutoCorrectOrchestrator.BAR_SCALE );
		final String line = AutoCorrectOrchestrator.describeSplitFunnel( funnel );
		assertTrue( line, line.startsWith( "  Split: 1 of 50 detection" ) );
		for ( final String frag : new String[] { "other 49 were refused", "40 whose extent walk",
				"bar 1.277", "3 with no extent reading", "4 whose extent reaches an image edge",
				"2 that one ellipse", "3 where a piece would land", "1 unreadable" } )
			assertTrue( frag + " missing from: " + line, line.contains( frag ) );
		assertFalse( "an unconsumed specifier means an argument is missing: " + line,
				line.contains( "%d" ) || line.contains( "%." ) || line.contains( "%s" ) );
	}

	/** Ratchet: the decision is the measured rule -- extent, shape, pieces -- and nothing else. */
	@Test
	public void theSplitIsDecidedByTheMeasuredRule() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		final int at = src.indexOf( "public static int splitMergedDetections( final ToolContext ctx, final int timepoint,\n" );
		final String pass = src.substring( at, src.indexOf( "static double neighbourLimit(", at ) );
		assertTrue( "1. the extent walk escaped past the run's bar", pass.contains( "ratios[ i ] > extentBar" ) );
		assertTrue( "   and the bar is the run's, not the frame's", pass.contains( "runExtentBar( ctx, ratios )" ) );
		assertTrue( "2. two ellipses explain the outline better", pass.contains( "shape.gain() > 0" ) );
		assertTrue( "4. the measured extent clear of the image faces, failing closed",
				pass.contains( "imageExtent == null || ImageBounds.reachesBorder( imageExtent, c, ratios[ i ] * c[ 3 ], volumetric )" ) );
		assertTrue( "3. no piece on another detection",
				pass.contains( "pieceLandsOnAnotherDetection( plan.centres, everySpot, c )" ) );
		assertFalse( "core CV separated the classes at 0.73-0.83 and no longer decides",
				pass.contains( "coreCvGate" ) || pass.contains( "prof[ 4 ]" ) );
		assertFalse( "nor does a size floor", pass.contains( "c[ 3 ] > medianRadius" ) );
		assertTrue( "and the measured K", src.contains( "SPLIT_EXTENT_MADS = 1.5" ) );
	}

	/** With too few readings for a spread the bar is zero, which must refuse rather than pass all. */
	@Test
	public void theExtentBarPoolsAcrossTheRunAndRefusesWithoutASpread()
	{
		final org.mastodon.mamut.automahout.tool.ToolContext ctx =
				new org.mastodon.mamut.automahout.tool.ToolContext( null );
		assertEquals( 0.0, AutoCorrectOrchestrator.runExtentBar( ctx, new double[] { 1.0, 1.1, Double.NaN } ), 0.0 );
		final double[] frame = new double[ 20 ];
		for ( int i = 0; i < frame.length; i++ )
			frame[ i ] = 0.9 + 0.01 * i;
		final double bar = AutoCorrectOrchestrator.runExtentBar( ctx, frame );
		assertTrue( "the pool now holds both frames' 22 readings and has a spread: " + bar, bar > 1.0 );
		final double again = AutoCorrectOrchestrator.runExtentBar( ctx, new double[] { 5.0 } );
		assertEquals( "one outlier does not move a median-and-MAD bar", bar, again, 0.02 );
	}

	/** A piece is refused on any other spot's disc, never on the detection being split itself. */
	@Test
	public void aPieceOnAnotherDetectionIsDetected()
	{
		final double[] self = { 0, 0, 0, 10 };
		final double[] neighbour = { 15, 0, 0, 6 };
		final java.util.List< double[] > spots = java.util.Arrays.asList( self, neighbour );
		assertFalse( "inside only itself: fine",
				AutoCorrectOrchestrator.pieceLandsOnAnotherDetection( new double[][] { { -4, 0, 0 }, { 4, 0, 0 } }, spots, self ) );
		assertTrue( "a piece within the neighbour's radius",
				AutoCorrectOrchestrator.pieceLandsOnAnotherDetection( new double[][] { { -4, 0, 0 }, { 11, 0, 0 } }, spots, self ) );
	}

	/**
	 * The grouped bar agrees with the Python reference it was measured with
	 * ({@code evaluate_decision_rule.grouped_bar}), on a pool and on one whose median falls exactly on
	 * the boundary between two grid cells -- the case that made the plain median flip.
	 */
	@Test
	public void theGroupedBarMatchesThePythonReference()
	{
		final java.util.TreeMap< Long, Long > pool = new java.util.TreeMap<>();
		pool.put( 14L, 3L );
		pool.put( 15L, 7L );
		pool.put( 16L, 5L );
		pool.put( 17L, 4L );
		pool.put( 18L, 2L );
		pool.put( 20L, 1L );
		assertEquals( 1.118506328125, AutoCorrectOrchestrator.groupedMedianPlusMads( pool, 1 / 16.0, 1.5 ), 1e-12 );
		final java.util.TreeMap< Long, Long > boundary = new java.util.TreeMap<>();
		boundary.put( 15L, 6L );
		boundary.put( 16L, 6L );
		assertEquals( 1.038246875, AutoCorrectOrchestrator.groupedMedianPlusMads( boundary, 1 / 16.0, 1.5 ), 1e-12 );
		assertEquals( "the grid is half a walk step", 1 / 16.0, AutoCorrectOrchestrator.EXTENT_RATIO_QUANTUM, 0.0 );
	}

	/** One more reading must move a grouped bar by a little, not by a whole grid cell. */
	@Test
	public void theGroupedBarDoesNotJumpWhenTheMedianCrossesACell()
	{
		final java.util.TreeMap< Long, Long > pool = new java.util.TreeMap<>();
		pool.put( 15L, 50L );
		pool.put( 16L, 50L );
		pool.put( 18L, 20L );
		final double before = AutoCorrectOrchestrator.groupedMedianPlusMads( pool, 1 / 16.0, 1.5 );
		pool.put( 15L, 51L );
		final double after = AutoCorrectOrchestrator.groupedMedianPlusMads( pool, 1 / 16.0, 1.5 );
		assertTrue( before + " -> " + after, Math.abs( before - after ) < 0.01 );
	}
	/** The ids of the plan each ELEPHANT-detected frame goes through, in order. */
	private static java.util.List< String > detectedPlan()
	{
		final java.util.List< String > ids = new java.util.ArrayList<>();
		for ( final org.mastodon.mamut.automahout.tool.CorrectionPass p
				: org.mastodon.mamut.automahout.tool.impl.DetectedPasses.BUILT_IN )
			ids.add( p.getId() );
		return ids;
	}
}
