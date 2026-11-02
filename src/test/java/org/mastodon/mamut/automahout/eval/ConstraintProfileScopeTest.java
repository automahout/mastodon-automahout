package org.mastodon.mamut.automahout.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lineage-constraint profile is activated by a path stored per USER, not per project, so opening a
 * different movie inherits the last one's. Observed on Fluo-N2DL-HeLA_01: a profile learned from
 * mouse_embryo_240220_sub50 stayed active, its 6.6 um displacement P99 replaced the cap this dataset
 * would have measured, and its low division rate let dissolveExcessDivisions cut real mitoses out of a
 * HeLa culture. The GT annotation store already refuses a foreign store; these hold the same line for
 * the profile.
 */
public class ConstraintProfileScopeTest
{
	private static Path write( final Path dir, final String name, final String dataset ) throws Exception
	{
		final LineageConstraintProfile p = new LineageConstraintProfile();
		p.dataset = dataset;
		p.displacementP99 = 6.6;
		p.divisionRate = 0.01;
		final Path out = dir.resolve( name );
		p.save( out.toString() );
		return out;
	}

	@Test
	public void aProfileFromThisDatasetIsHonoured( @TempDir final Path dir ) throws Exception
	{
		final Path f = write( dir, "hela.json", "Fluo-N2DL-HeLA_01" );
		final LineageConstraintProfile p = LineageConstraintProfile.loadForDataset(
				f.toString(), "Fluo-N2DL-HeLA_01", r -> {} );
		assertNotNull( p );
		assertEquals( 6.6, p.displacementP99, 1e-9 );
	}

	@Test
	public void anotherMoviesProfileIsRefusedAndTheReasonNamesBothDatasets( @TempDir final Path dir )
			throws Exception
	{
		final Path f = write( dir, "mouse.json", "mouse_embryo_240220_sub50" );
		final List< String > reasons = new ArrayList<>();
		assertNull( LineageConstraintProfile.loadForDataset(
				f.toString(), "Fluo-N2DL-HeLA_01", reasons::add ) );
		assertEquals( 1, reasons.size() );
		assertTrue( reasons.get( 0 ).contains( "mouse_embryo_240220_sub50" ), reasons.get( 0 ) );
		assertTrue( reasons.get( 0 ).contains( "Fluo-N2DL-HeLA_01" ), reasons.get( 0 ) );
	}

	@Test
	public void theReasonNamesBothEffectsBecauseNeitherAnnouncesItself( @TempDir final Path dir )
			throws Exception
	{
		// Displacement: estimateMaxDisplacement PREFERS a profile's P99 over measuring this dataset.
		// Divisions: divisionsAreRare gates dissolveExcessDivisions, which cuts division links.
		final Path f = write( dir, "mouse.json", "mouse_embryo_240220_sub50" );
		final List< String > reasons = new ArrayList<>();
		LineageConstraintProfile.loadForDataset( f.toString(), "Fluo-N2DL-HeLA_01", reasons::add );
		assertTrue( reasons.get( 0 ).contains( "displacement" ), reasons.get( 0 ) );
		assertTrue( reasons.get( 0 ).contains( "division" ), reasons.get( 0 ) );
	}

	@Test
	public void aProfileThatRecordsNoDatasetCannotBeCheckedSoItIsRefused( @TempDir final Path dir )
			throws Exception
	{
		// Written before the field existed. Relearning is one menu action; honouring it is a guess about
		// which movie it describes.
		final Path f = write( dir, "legacy.json", "" );
		final List< String > reasons = new ArrayList<>();
		assertNull( LineageConstraintProfile.loadForDataset(
				f.toString(), "Fluo-N2DL-HeLA_01", reasons::add ) );
		assertTrue( reasons.get( 0 ).contains( "did not record" ), reasons.get( 0 ) );
	}

	@Test
	public void noPathAndUnreadablePathAreSilent( @TempDir final Path dir )
	{
		final List< String > reasons = new ArrayList<>();
		assertNull( LineageConstraintProfile.loadForDataset( null, "X", reasons::add ) );
		assertNull( LineageConstraintProfile.loadForDataset( "   ", "X", reasons::add ) );
		assertNull( LineageConstraintProfile.loadForDataset(
				dir.resolve( "absent.json" ).toString(), "X", reasons::add ) );
		assertTrue( reasons.isEmpty(), "nothing to refuse when there is no profile: " + reasons );
	}

	@Test
	public void theDatasetSurvivesARoundTrip( @TempDir final Path dir ) throws Exception
	{
		final Path f = write( dir, "rt.json", "Fluo-N2DL-HeLA_01" );
		assertEquals( "Fluo-N2DL-HeLA_01", LineageConstraintProfile.load( f.toString() ).dataset );
	}
}
