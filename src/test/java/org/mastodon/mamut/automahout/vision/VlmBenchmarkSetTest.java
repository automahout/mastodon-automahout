package org.mastodon.mamut.automahout.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Keeps the frozen VLM benchmark honest. It is worth nothing the moment its labels drift from its
 * images, or the moment one class quietly dominates — a set of only empty crops is passed by
 * answering "empty" to everything, which is close to what the thing being measured already does.
 *
 * <p>The measurement itself is {@link VlmFpBenchmark}, which costs money and needs credentials.
 * This runs in CI and costs nothing.</p>
 */
public class VlmBenchmarkSetTest
{
	private static final Path DIR = Paths.get( "benchmark", "vlm-fp-negatives" );

	private static List< String[] > rows() throws Exception
	{
		final List< String[] > out = new ArrayList<>();
		for ( final String line : Files.readAllLines( DIR.resolve( "labels.tsv" ), StandardCharsets.UTF_8 ) )
			if ( !line.startsWith( "#" ) && !line.trim().isEmpty() )
				out.add( line.split( "\t" ) );
		return out;
	}

	/** The crops are our own unpublished data, kept out of the repository: these checks run where they are. */
	private static void assumeCropsPresent()
	{
		assumeTrue( Files.isDirectory( DIR.resolve( "images" ) ), "no images/ (not in the public repository)" );
	}

	@Test
	public void everyLabelledCropIsOnDisk() throws Exception
	{
		assumeCropsPresent();
		for ( final String[] r : rows() )
			assertTrue( Files.isRegularFile( DIR.resolve( "images" ).resolve( r[ 0 ] ) ),
					"labels.tsv names a crop that is not in images/: " + r[ 0 ] );
	}

	@Test
	public void everyCropOnDiskIsLabelled() throws Exception
	{
		final Set< String > labelled = new HashSet<>();
		assumeCropsPresent();
		for ( final String[] r : rows() )
			labelled.add( r[ 0 ] );
		try ( Stream< Path > files = Files.list( DIR.resolve( "images" ) ) )
		{
			files.forEach( f -> assertTrue( labelled.contains( f.getFileName().toString() ),
					"images/ holds a crop labels.tsv does not mention: " + f.getFileName() ) );
		}
	}

	@Test
	public void bothAnswersAreRepresented() throws Exception
	{
		int cells = 0, background = 0;
		for ( final String[] r : rows() )
		{
			if ( "cell".equals( r[ 1 ] ) )
				cells++;
			else if ( "background".equals( r[ 1 ] ) )
				background++;
			else
				throw new AssertionError( "unknown truth label: " + r[ 1 ] );
		}
		assertTrue( cells >= 10, "too few cell cases to measure the safety side: " + cells );
		assertTrue( background >= 10, "too few background cases: " + background );
		// Neither class may dominate, or a constant answer scores well.
		final double share = ( double ) Math.max( cells, background ) / ( cells + background );
		assertTrue( share <= 0.7, "the set is lopsided (" + cells + " cell / " + background
				+ " background); a constant answer would score " + Math.round( share * 100 ) + "%" );
	}

	@Test
	public void theLabelsAgreeWithTheMeasurementTheyCameFrom() throws Exception
	{
		// Ground truth here is measured, not judged — intensity at the crosshair, see build_set.py.
		// If a label and its own recorded measurement disagree, the file has been hand-edited and the
		// benchmark no longer means what its README says.
		for ( final String[] r : rows() )
		{
			final double at = Double.parseDouble( r[ 2 ] );
			if ( "cell".equals( r[ 1 ] ) )
				assertTrue( at >= 200.0, r[ 0 ] + " is labelled cell but reads " + at + " at the crosshair" );
			else
				assertTrue( at <= 60.0, r[ 0 ] + " is labelled background but reads " + at );
		}
	}

	@Test
	public void theSetIsBigEnoughToSeparateSeventeenPercentFromChance() throws Exception
	{
		// Production measured 17% recall on empty crops. Distinguishing that from 50% at a glance needs
		// a few tens of cases per class, not a handful.
		assertTrue( rows().size() >= 40, "only " + rows().size() + " case(s)" );
	}
}
