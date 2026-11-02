package org.mastodon.mamut.automahout.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The pre-flight dialog used to open with the same answers whatever was being tracked. These hold
 * the measurement that now sets them, and the two failure modes that matter: calling phase contrast
 * fluorescent turns on deletion passes the run log itself says cannot work there, and calling
 * fluorescence phase contrast spends a VLM round trip per window on a question the pixels answer.
 */
public class ImageProfileTest
{
	/** Fluo-N2DL-HeLA_01's own numbers: background ~33044, cells ~33727, a 2% difference. */
	private static double[] fluorescence( final double cellFraction )
	{
		final Random rnd = new Random( 7 );
		final double[] v = new double[ 4000 ];
		for ( int i = 0; i < v.length; i++ )
			v[ i ] = i < v.length * cellFraction
					? 33727 + rnd.nextGaussian() * 40
					: 33044 + rnd.nextGaussian() * 25;
		return v;
	}

	/** Phase contrast: cells are not brighter, the whole frame is one texture around a mid grey. */
	private static double[] phaseContrast()
	{
		final Random rnd = new Random( 11 );
		final double[] v = new double[ 4000 ];
		for ( int i = 0; i < v.length; i++ )
			v[ i ] = 20000 + rnd.nextGaussian() * 1500;
		return v;
	}

	@Test
	public void aTwoPercentIntensityDifferenceIsStillFluorescence()
	{
		// The reason the test is in sigma and not in intensity: on this dataset cells are 2% brighter
		// than background, and any absolute threshold tuned on one detector is wrong on the next.
		final ImageProfile p = ImageProfile.of( fluorescence( 0.05 ) );
		assertTrue( p.fluorescenceLike, p.describe() );
		assertTrue( p.snrSigma > ImageProfile.FLUORESCENCE_MIN_SNR_SIGMA, p.describe() );
	}

	@Test
	public void phaseContrastIsNotMistakenForFluorescence()
	{
		final ImageProfile p = ImageProfile.of( phaseContrast() );
		assertFalse( p.fluorescenceLike, p.describe() );
	}

	@Test
	public void fluorescenceGetsTheIntensityPassesAndNoVlmRoundTrip()
	{
		final ImageProfile p = ImageProfile.of( fluorescence( 0.05 ) );
		assertTrue( p.flatContrastDeletionApplies() );
		assertTrue( p.dimSignalFilterApplies() );
		assertFalse( p.vlmCropCheckApplies(), "measuring the crop answers this, and is checkable" );
	}

	@Test
	public void phaseContrastGetsNeitherIntensityPassAndKeepsTheVlm()
	{
		final ImageProfile p = ImageProfile.of( phaseContrast() );
		assertFalse( p.flatContrastDeletionApplies(), "the run log calls this unworkable there" );
		assertFalse( p.dimSignalFilterApplies(), "a dim cell here is just a cell" );
		assertTrue( p.vlmCropCheckApplies(), "nothing for a measurement to stand on" );
	}

	@Test
	public void anEmptyFrameIsNotFluorescence()
	{
		// All one value: no variation to divide by. Not "infinitely fluorescent".
		final double[] flat = new double[ 1000 ];
		java.util.Arrays.fill( flat, 900.0 );
		final ImageProfile p = ImageProfile.of( flat );
		assertFalse( p.fluorescenceLike );
		assertTrue( p.undetermined );
	}

	@Test
	public void aFrameThatIsMostlyBrightIsNotFluorescenceEither()
	{
		// Saturated or uniformly lit: the bright tail IS the frame, so the ratio means nothing.
		final ImageProfile p = ImageProfile.of( fluorescence( 0.95 ) );
		assertFalse( p.fluorescenceLike, p.describe() );
	}

	@Test
	public void tooFewSamplesDeclinesToClassifyAndChangesNothing()
	{
		final ImageProfile p = ImageProfile.of( new double[ 10 ] );
		assertTrue( p.undetermined );
		// Undetermined must read as the behaviour that predates this class, not as a third mode.
		assertTrue( p.flatContrastDeletionApplies() );
		assertFalse( p.dimSignalFilterApplies() );
		assertTrue( p.vlmCropCheckApplies() );
	}

	/**
	 * A sparse sample on an exactly-zero background (most voxels 0, a few cells): the spread is measured below
	 * the median, where every value is 0, so the profile is undetermined. The line says so in numbers, so a
	 * run log tells this case from too few samples.
	 */
	@Test
	public void anUndeterminedProfileSaysWhatWasMeasured()
	{
		final double[] v = new double[ 3000 ];
		final Random rnd = new Random( 1 );
		for ( int i = 0; i < 300; i++ )
			v[ i ] = 100 + rnd.nextInt( 400 );
		final ImageProfile p = ImageProfile.of( v );
		assertTrue( p.undetermined );
		assertTrue( p.describe().contains( "3000 samples, median 0.0, 90.0% of them equal to it" ), p.describe() );
		assertTrue( ImageProfile.of( new double[ 10 ] ).describe().contains( "10 intensity sample(s) read, 200 needed" ) );
	}

	@Test
	public void nullSamplesDoNotThrow()
	{
		assertTrue( ImageProfile.of( null ).undetermined );
	}

	@Test
	public void quantisedDataWithAFlatBackgroundIsStillMeasurable()
	{
		// Fluo-N3DH-CE: 8-bit, background sitting on one grey level, cells far above it. More than
		// half the samples are identical, so the MAD is exactly 0 and the whole profile used to come
		// back "undetermined" -- switching off every intensity test on a 3-D fluorescence movie with
		// excellent signal. The noise is not absent there, it is below one grey level.
		// Background noise below one grey level, so most samples share a value and the MAD is 0, but
		// a few neighbouring levels exist -- which is what 8-bit background actually looks like.
		final double[] samples = new double[ 1000 ];
		for ( int i = 0; i < 950; i++ )
			samples[ i ] = i % 40 == 0 ? 22.0 : ( i % 17 == 0 ? 24.0 : 23.0 );
		for ( int i = 950; i < 1000; i++ )
			samples[ i ] = 200.0;                  // nuclei
		final ImageProfile p = ImageProfile.of( samples );
		assertFalse( p.undetermined, "a movie with obvious signal must not read as undetermined" );
		assertTrue( p.fluorescenceLike, "bright objects on a flat background are fluorescence" );
		assertEquals( 0.5, p.backgroundStd, 1e-9,
				"sigma floors at half the quantisation step, which is all that can be claimed" );
	}

	@Test
	public void theQuantisationStepIsTheSmallestRealDifference()
	{
		assertEquals( 1.0, ImageProfile.quantisationStep( new double[] { 3, 3, 4, 4, 9 }, 4 ), 1e-9 );
		assertEquals( 0.25, ImageProfile.quantisationStep( new double[] { 1.0, 1.25, 2.0 }, 1.25 ), 1e-9 );
		// all identical: nothing to measure
		assertEquals( 0.0, ImageProfile.quantisationStep( new double[] { 7, 7, 7 }, 7 ), 1e-9 );
		// the gap to the bright tail must not become the ruler
		assertEquals( 1.0, ImageProfile.quantisationStep( new double[] { 22, 23, 24, 200 }, 23 ), 1e-9 );
	}
}
