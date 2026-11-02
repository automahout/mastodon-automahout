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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/**
 * Recording what the detector actually produced, so a fixture can stop being invented.
 *
 * <p>Five conclusions about the split pass were drawn from populations built out of ground truth,
 * and all five were wrong. The last was wrong in a way its fixture could not show: it gave every
 * detection the same radius, so the radius appeared to carry no information and a wide search reach
 * appeared safe. Production detections have a 34% spread in size.</p>
 */
public class SplitFixtureDumpTest
{
	private Path file;

	@After
	public void clear() throws IOException
	{
		System.clearProperty( SplitFixtureDump.PROPERTY );
		if ( file != null )
			Files.deleteIfExists( file );
	}

	private List< String > dump( final Runnable r ) throws IOException
	{
		file = Files.createTempFile( "split-fixture", ".tsv" );
		Files.delete( file );
		System.setProperty( SplitFixtureDump.PROPERTY, file.toString() );
		// The configured path is a name pattern: the rows land in this launch's stamped file.
		file = java.nio.file.Paths.get( org.mastodon.mamut.automahout.tool.DumpFiles.perRun( file.toString() ) );
		r.run();
		return Files.exists( file ) ? Files.readAllLines( file, StandardCharsets.UTF_8 )
				: java.util.Collections.emptyList();
	}

	@Test
	public void nothingIsWrittenUnlessTheDumpIsAskedFor() throws IOException
	{
		System.clearProperty( SplitFixtureDump.PROPERTY );
		assertFalse( "an ordinary run must not pay for this", SplitFixtureDump.enabled() );
		// Must not throw and must not create anything.
		SplitFixtureDump.record( 3, new double[] { 1, 2, 3, 4 }, new double[] { 5, 6, 7, 8, 9 }, 4.0 );
	}

	@Test
	public void eachDetectionIsOneRowUnderOneHeader() throws IOException
	{
		final List< String > lines = dump( () -> {
			SplitFixtureDump.record( 7, new double[] { 10, 20, 0, 6.5 },
					new double[] { 0.4, 100, 98, 97, 0.12 }, 6.0 );
			SplitFixtureDump.record( 7, new double[] { 30, 40, 0, 9.5 },
					new double[] { 0.7, 120, 90, 60, 0.61 }, 6.0 );
		} );
		assertEquals( 3, lines.size() );
		assertTrue( lines.get( 0 ), lines.get( 0 ).startsWith( "rev\tt\tx\ty\tz\tradius\tfullCv" ) );
		assertTrue( lines.get( 1 ), lines.get( 1 ).startsWith(
				SplitFixtureDump.REVISION + "\t7\t10.0\t20.0\t0.0\t6.5\t0.4" ) );
		assertTrue( "the frame's own median belongs on every row, or the rows cannot be compared",
				lines.get( 1 ).endsWith( "\t6.0\t" ) && lines.get( 2 ).endsWith( "\t6.0\t" ) );
		assertEquals( "every row must have the same arity", 13,
				lines.get( 1 ).split( "\t", -1 ).length );
	}

	/**
	 * A detection the image could not be read at is still a detection. Dropping it would understate
	 * how often that happens, which is one of the things the fixture is for.
	 */
	@Test
	public void adetectionWithNoProfileIsRecordedWithBlanks() throws IOException
	{
		final List< String > lines = dump(
				() -> SplitFixtureDump.record( 2, new double[] { 1, 1, 0, 5 }, null, 5.0 ) );
		assertEquals( 2, lines.size() );
		assertEquals( "blanks, not a dropped row", 13, lines.get( 1 ).split( "\t", -1 ).length );
		assertTrue( lines.get( 1 ), lines.get( 1 ).contains( "\t\t" ) );
	}

	/** A dump that can fail a run is worse than no dump. */
	@Test
	public void afailingDumpNeverThrows()
	{
		System.setProperty( SplitFixtureDump.PROPERTY,
				Paths.get( "/" ).resolve( "definitely-not-writable" ).resolve( "x.tsv" ).toString() );
		SplitFixtureDump.record( 1, new double[] { 1, 2, 3, 4 }, null, 4.0 );
		SplitFixtureDump.record( 1, null, null, 4.0 );
		SplitFixtureDump.record( 1, new double[] { 1 }, null, 4.0 );
	}

	/** Ratchet: the rows must be taken before any gate, or the fixture is not the gates' population. */
	@Test
	public void theDumpIsTakenBeforeAnyGate() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
						.replaceAll( "//[^\\n]*", "" );
		final int dumped = src.indexOf( "SplitFixtureDump.record(" );
		assertTrue( "the dump must happen", dumped > 0 );
		assertTrue( "and before the first thing that refuses a frame or a detection",
				dumped < src.indexOf( "tally( funnelOut, FUNNEL_FRAME_NO_SPREAD )" )
						&& dumped < src.indexOf( "tally( funnelOut, FUNNEL_NO_EXTENT )" ) );
	}

	/** The property wins over the variable, so a deliberate override is not silently ignored. */
	@Test
	public void thePropertyOverridesTheEnvironment()
	{
		System.setProperty( SplitFixtureDump.PROPERTY, "/tmp/from-the-property.tsv" );
		assertEquals( org.mastodon.mamut.automahout.tool.DumpFiles.perRun( "/tmp/from-the-property.tsv" ),
				SplitFixtureDump.target() );
		System.clearProperty( SplitFixtureDump.PROPERTY );
		assertTrue( "with neither set the dump must be off",
				System.getenv( SplitFixtureDump.ENV ) != null || SplitFixtureDump.target() == null );
	}

	/** Whitespace is not a path. */
	@Test
	public void ablankSettingIsOff()
	{
		System.setProperty( SplitFixtureDump.PROPERTY, "   " );
		assertTrue( System.getenv( SplitFixtureDump.ENV ) != null
				|| SplitFixtureDump.target() == null );
	}

	/**
	 * The revision goes on every row, not just in the header.
	 *
	 * <p>The file is appended to, so a run after a fix leaves its rows beside the old ones. A dump
	 * taken before the interior spreads were corrected was read back afterwards and looked like the
	 * fix had not worked; a per-row marker makes a stale row visible rather than silently averaged
	 * in.</p>
	 */
	@Test
	public void everyRowCarriesTheRevision() throws IOException
	{
		final List< String > lines = dump( () -> {
			SplitFixtureDump.record( 1, new double[] { 1, 1, 0, 5 }, null, 5.0 );
			SplitFixtureDump.record( 2, new double[] { 2, 2, 0, 6 }, null, 5.0 );
		} );
		assertEquals( 3, lines.size() );
		for ( int i = 1; i < lines.size(); i++ )
			assertTrue( "a row must say which meaning its columns have: " + lines.get( i ),
					lines.get( i ).startsWith( SplitFixtureDump.REVISION + "\t" ) );
	}

	/**
	 * The extent walk's reading goes on the row unclamped: the clamp at 1.4 is what hid the evidence
	 * that a detection covers two cells.
	 */
	@Test
	public void theUnclampedExtentReadingIsTheLastColumn() throws IOException
	{
		final List< String > lines = dump( () -> SplitFixtureDump.record( 2, new double[] { 1, 2, 0, 10 },
				new double[] { 0.4, 100, 98, 97, 0.12 }, 8.0, 1.83 ) );
		final String[] head = lines.get( 0 ).split( "\t", -1 );
		final String[] row = lines.get( 1 ).split( "\t", -1 );
		assertEquals( "extentRatio", head[ head.length - 1 ] );
		assertEquals( "1.83", row[ row.length - 1 ] );
	}
}
