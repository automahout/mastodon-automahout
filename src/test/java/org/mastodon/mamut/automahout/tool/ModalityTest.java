package org.mastodon.mamut.automahout.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The pre-flight's image-type question: what it pre-selects, and that the answer overrides the pixels. */
public class ModalityTest
{
	@Test
	public void theMoviesDescriptionComesFirst()
	{
		assertEquals( Modality.PHASE_CONTRAST, Modality.guess( null, "A phase-contrast movie of migrating cells" ).modality );
		assertEquals( Modality.BRIGHTFIELD, Modality.guess( null, "Brightfield images, low contrast" ).modality );
		assertEquals( Modality.FLUORESCENCE, Modality.guess( null, "Fluorescently labelled nuclei" ).modality );
	}

	@Test
	public void aZeroBackgroundUnderSparseObjectsReadsAsFluorescence()
	{
		// The mouse embryo: 3000 samples, 99.9% exactly 0 -- undetermined as a profile, fluorescence as a guess.
		final double[] v = new double[ 3000 ];
		v[ 0 ] = 400;
		v[ 1 ] = 500;
		v[ 2 ] = 300;
		final ImageProfile p = ImageProfile.of( v );
		assertTrue( p.undetermined );
		assertEquals( Modality.FLUORESCENCE, Modality.guess( p, "3-D light-sheet stack of an embryo" ).modality );
	}

	@Test
	public void nothingToGoOnIsOther()
	{
		assertEquals( Modality.OTHER, Modality.guess( ImageProfile.of( null ), "" ).modality );
	}

	/** A stated modality decides every step that asks forRun, and nothing is sampled to find out. */
	@Test
	public void aStatedModalityOverridesTheReadingWithoutSampling()
	{
		final ToolContext ctx = new ToolContext( null );
		ctx.setSharedData( ImageProfile.SHARED_STATED_MODALITY, Modality.FLUORESCENCE );
		final ImageProfile p = ImageProfile.forRun( ctx, () -> {
			throw new AssertionError( "sampled although the modality was stated" );
		} );
		assertTrue( p.fluorescenceLike );
		assertFalse( p.undetermined );
		assertSame( Modality.FLUORESCENCE, p.stated );
		assertTrue( p.dimSignalFilterApplies() );
		assertFalse( p.vlmCropCheckApplies() );

		final ToolContext phase = new ToolContext( null );
		phase.setSharedData( ImageProfile.SHARED_STATED_MODALITY, Modality.PHASE_CONTRAST );
		final ImageProfile q = ImageProfile.forRun( phase, () -> new double[ 0 ] );
		assertFalse( q.fluorescenceLike );
		assertFalse( q.flatContrastDeletionApplies() );
		assertTrue( q.vlmCropCheckApplies() );
	}

	/** Cells at the weight of a correction only when chosen, so a run with no dialog (the benchmark) is unchanged. */
	@Test
	public void theTrainingBalanceIsUnchangedUnlessChosen()
	{
		final ToolContext ctx = new ToolContext( null );
		assertFalse( CleanupToggles.detectionsWeightedAsCorrections( ctx ) );
		ctx.setSharedData( CleanupToggles.SHARED_DETECTIONS_AS_CORRECTIONS, Boolean.TRUE );
		assertTrue( CleanupToggles.detectionsWeightedAsCorrections( ctx ) );
	}
}
