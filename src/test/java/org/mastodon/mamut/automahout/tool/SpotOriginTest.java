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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * {@link SpotOrigin} and {@link SpotOriginDump}: where each spot came from, written out per snapshot.
 *
 * <p>Built to find which of fourteen add sites put a second spot on one nucleus. A record that is
 * wrong for a reused pool index, or a dump that pairs a spot with itself across snapshots, would give
 * a confident wrong answer, so those are what these tests pin.</p>
 */
public class SpotOriginTest
{
	private Path dump;

	@Before
	public void enableDump() throws IOException
	{
		dump = Files.createTempFile( "origin-dump", ".tsv" );
		Files.delete( dump );
		System.setProperty( SpotOriginDump.PROPERTY, dump.toString() );
	}

	@After
	public void disableDump() throws IOException
	{
		System.clearProperty( SpotOriginDump.PROPERTY );
		Files.deleteIfExists( written( dump ) );
		Files.deleteIfExists( SpotOriginDump.linksFile( written( dump ) ) );
		Files.deleteIfExists( SpotOriginDump.companion( written( dump ), ".removed" ) );
		Files.deleteIfExists( java.nio.file.Paths.get( written( dump ) + ".removed_spots.tsv" ) );
	}

	@Test
	public void aRemovedSpotIsWrittenWithWhereItWasAndWhoRemovedIt() throws IOException
	{
		final Model model = new Model();
		final ToolContext ctx = ToolContext.forReplay( model );
		addThroughChokepoint( ctx, model.getGraph(), 0, GraphEdits.Reason.DETECTION );
		final Spot s = addThroughChokepoint( ctx, model.getGraph(), 30, GraphEdits.Reason.DETECTION );
		assertTrue( GraphEdits.removeSpot( ctx, model.getGraph(), s, GraphEdits.Reason.NOT_A_CELL ) );
		final List< String > rows = Files.readAllLines( java.nio.file.Paths.get( written( dump ) + ".removed_spots.tsv" ),
				StandardCharsets.UTF_8 );
		assertEquals( SpotOriginDump.REMOVED_SPOTS_HEADER.trim(), rows.get( 0 ) );
		assertEquals( 2, rows.size() );
		final String[] f = rows.get( 1 ).split( "\t", -1 );
		assertEquals( "0", f[ 1 ] );
		assertEquals( 30.0, Double.parseDouble( f[ 2 ] ), 1e-9 );
		assertEquals( 5.0, Double.parseDouble( f[ 5 ] ), 1e-9 );
		assertEquals( "NOT_A_CELL", f[ 6 ] );
		assertTrue( f[ 7 ], f[ 7 ].startsWith( "SpotOriginTest.aRemovedSpotIsWrittenWithWhereItWasAndWhoRemovedIt" ) );
		assertEquals( "no ELEPHANT quality recorded for it", "", f[ 8 ] );
	}

	/** Where a dump configured as {@code configured} is actually written: one file per launch. */
	private static Path written( final Path configured )
	{
		return java.nio.file.Paths.get( DumpFiles.perRun( configured.toString() ) );
	}

	@Test
	public void theConfiguredPathGetsThisLaunchesStamp()
	{
		assertEquals( "/tmp/origin-dump-20261001-141530.tsv",
				DumpFiles.perRun( "/tmp/origin-dump.tsv", "20261001-141530" ) );
		assertEquals( "a dot in a DIRECTORY is not an extension", "/tmp/dumps.d/origin-20261001-141530",
				DumpFiles.perRun( "/tmp/dumps.d/origin", "20261001-141530" ) );
		assertEquals( "/tmp/.hidden-20261001-141530",
				DumpFiles.perRun( "/tmp/.hidden", "20261001-141530" ) );
		assertEquals( "every dump of one launch shares the stamp", DumpFiles.perRun( "/a/x.tsv" ).substring( 5 ),
				DumpFiles.perRun( "/a/y.tsv" ).substring( 5 ) );
	}

	private static Spot addThroughChokepoint( final ToolContext ctx, final ModelGraph g, final double x,
			final GraphEdits.Reason why )
	{
		final Spot ref = g.vertexRef();
		return GraphEdits.addSpot( ctx, g, ref, 0, new double[] { x, 0, 0 }, 5.0, why );
	}

	@Test
	public void theReasonAndTheCallingMethodAreRecorded()
	{
		final Model model = new Model();
		final ToolContext ctx = ToolContext.forReplay( model );
		final Spot s = addThroughChokepoint( ctx, model.getGraph(), 0, GraphEdits.Reason.TOPOLOGY );
		assertEquals( "TOPOLOGY", SpotOrigin.reasonOf( model.getGraph(), s ) );
		final String site = SpotOrigin.siteOf( model.getGraph(), s );
		assertTrue( "the innermost caller outside the chokepoint comes first: " + site,
				site.startsWith( "SpotOriginTest.addThroughChokepoint<-" ) );
		assertTrue( "and the one that called it second: " + site,
				site.endsWith( "SpotOriginTest.theReasonAndTheCallingMethodAreRecorded" ) );
	}

	/**
	 * The map lives on the vertex pool and indices are reused. A spot added outside the chokepoint --
	 * by a person, or ELEPHANT's own predict action -- must not inherit the previous occupant's origin.
	 */
	@Test
	public void aReusedPoolIndexDoesNotInheritTheOldOrigin()
	{
		final Model model = new Model();
		final ModelGraph g = model.getGraph();
		final ToolContext ctx = ToolContext.forReplay( model );
		final Spot old = addThroughChokepoint( ctx, g, 0, GraphEdits.Reason.DETECTION );
		final int idx = old.getInternalPoolIndex();
		assertEquals( "DETECTION", SpotOrigin.reasonOf( g, old ) );
		g.remove( old );

		final Spot outside = g.addVertex( g.vertexRef() ).init( 0, new double[] { 50, 0, 0 }, 5.0 );
		assertEquals( "the test only means something if the index was reused", idx,
				outside.getInternalPoolIndex() );
		assertEquals( SpotOrigin.UNRECORDED, SpotOrigin.reasonOf( g, outside ) );
		assertEquals( "", SpotOrigin.siteOf( g, outside ) );
	}

	/**
	 * Ratchet: the registry must hold its values weakly.
	 *
	 * <p>The value reaches its own key -- property map, vertex pool, Spot refs, graph -- so a strong
	 * value pins every graph ever seen. With one, 17 tests across the suite failed with
	 * {@code OutOfMemoryError}. This is a source check rather than a GC check because a Mastodon graph
	 * that has had a vertex added is not collected by {@code System.gc()} alone even with no registry
	 * at all (measured), so "was it collected" cannot tell the two apart. Comments are stripped first.</p>
	 */
	@Test
	public void theRegistryHoldsItsValuesWeakly() throws IOException
	{
		final String src = new String( Files.readAllBytes( java.nio.file.Paths.get( "src", "main", "java",
				"org", "mastodon", "mamut", "automahout", "tool", "SpotOrigin.java" ) ), StandardCharsets.UTF_8 )
						.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		assertTrue( src.contains( "WeakHashMap< ModelGraph, WeakReference< State > >" ) );
	}

	/**
	 * The record lives as long as its graph even though the registry holds it weakly: the graph's
	 * listener is what keeps it.
	 */
	@Test
	public void theRecordSurvivesACollectionWhileTheGraphIsAlive() throws InterruptedException
	{
		final Model model = new Model();
		final Spot s = addThroughChokepoint( ToolContext.forReplay( model ), model.getGraph(), 0,
				GraphEdits.Reason.SPLIT );
		for ( int i = 0; i < 5; i++ )
		{
			System.gc();
			Thread.sleep( 20 );
		}
		assertEquals( "SPLIT", SpotOrigin.reasonOf( model.getGraph(), s ) );
	}

	/**
	 * The dump's directory may be a symlink -- macOS's /tmp is. JDK 11's createDirectories throws for
	 * one, and the first real run with this dump wrote nothing because of it.
	 */
	@Test
	public void aSymlinkedDirectoryIsWrittenInto() throws IOException
	{
		final Path real = Files.createTempDirectory( "origin-real" );
		final Path link = real.resolveSibling( real.getFileName() + "-link" );
		Files.createSymbolicLink( link, real );
		final Path target = link.resolve( "origin.tsv" );
		System.setProperty( SpotOriginDump.PROPERTY, target.toString() );
		try
		{
			final Model model = new Model();
			final ToolContext ctx = ToolContext.forReplay( model );
			addThroughChokepoint( ctx, model.getGraph(), 0, GraphEdits.Reason.DETECTION );
			SpotOriginDump.snapshot( ctx, "x" );
			assertEquals( 2, Files.readAllLines( written( real.resolve( "origin.tsv" ) ), StandardCharsets.UTF_8 ).size() );
		}
		finally
		{
			Files.deleteIfExists( written( real.resolve( "origin.tsv" ) ) );
			Files.deleteIfExists( SpotOriginDump.linksFile( written( real.resolve( "origin.tsv" ) ) ) );
			Files.deleteIfExists( SpotOriginDump.companion( written( real.resolve( "origin.tsv" ) ), ".removed" ) );
			Files.deleteIfExists( link );
			Files.deleteIfExists( real );
		}
	}

	/**
	 * Ratchet for the same bug, because the test above cannot see it: the suite runs on JDK 21, whose
	 * createDirectories accepts a symlinked directory, while TestDrive runs on JDK 11, whose does not.
	 * Each dump may only create its directory when it is missing.
	 */
	@Test
	public void theDumpsCreateTheirDirectoryOnlyWhenItIsMissing() throws IOException
	{
		for ( final String f : new String[] { "tool/SpotOriginDump.java", "autocorrect/SplitFixtureDump.java" } )
		{
			final String src = new String( Files.readAllBytes( java.nio.file.Paths.get( "src", "main", "java",
					"org", "mastodon", "mamut", "automahout" ).resolve( f ) ), StandardCharsets.UTF_8 )
							.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
			final java.util.regex.Matcher m = java.util.regex.Pattern
					.compile( "if \\(([^\\n]*)\\)\\s*Files\\.createDirectories" ).matcher( src );
			int n = 0;
			while ( m.find() )
			{
				n++;
				assertTrue( f + ": guarded by isDirectory", m.group( 1 ).contains( "!Files.isDirectory(" ) );
			}
			assertEquals( f + ": every createDirectories is under such a guard", n,
					src.split( "Files\\.createDirectories", -1 ).length - 1 );
			assertTrue( f + ": and there is one", n > 0 );
		}
	}

	/** A checkpoint inside adaptive_track writes only the window it checks. */
	@Test
	public void aScopedSnapshotWritesOnlyItsTimepoints() throws IOException
	{
		final Model model = new Model();
		final ToolContext ctx = ToolContext.forReplay( model );
		final ModelGraph g = model.getGraph();
		GraphEdits.addSpot( ctx, g, g.vertexRef(), 3, new double[] { 0, 0, 0 }, 5.0, GraphEdits.Reason.DETECTION );
		GraphEdits.addSpot( ctx, g, g.vertexRef(), 12, new double[] { 0, 0, 0 }, 5.0, GraphEdits.Reason.DETECTION );
		SpotOriginDump.snapshot( ctx, "[3/6] commit detection t=0..9", 0, 9 );
		final List< String > lines = Files.readAllLines( written( dump ), StandardCharsets.UTF_8 );
		assertEquals( 2, lines.size() );
		assertEquals( "3", lines.get( 1 ).split( "\t", -1 )[ 3 ] );
	}

	/** Ratchet: every pipeline checkpoint writes one, so a spot's first appearance names its step. */
	@Test
	public void everyInvariantCheckpointWritesASnapshot() throws IOException
	{
		final String src = new String( Files.readAllBytes( java.nio.file.Paths.get( "src", "main", "java",
				"org", "mastodon", "mamut", "automahout", "eval", "PipelineInvariants.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		final int at = src.indexOf( "public static Outcome checkAndLog(" );
		assertTrue( at >= 0 );
		final int snap = src.indexOf( "SpotOriginDump.snapshot( ctx, phase", at );
		assertTrue( "checkAndLog writes the snapshot", snap > at );
		assertTrue( "before anything can return early", snap < src.indexOf( "return", at ) );
	}

	@Test
	public void aRefusedAddLeavesNoRecord()
	{
		final Model model = new Model();
		final Spot s = model.getGraph().addVertex( model.getGraph().vertexRef() )
				.init( 0, new double[] { 0, 0, 0 }, 5.0 );
		assertEquals( SpotOrigin.UNRECORDED, SpotOrigin.reasonOf( model.getGraph(), s ) );
	}

	@Test
	public void theCallerSkipsEveryChokepointFrame()
	{
		final StackTraceElement[] trace = {
				new StackTraceElement( SpotOrigin.class.getName(), "record", "SpotOrigin.java", 1 ),
				new StackTraceElement( GraphEdits.class.getName(), "acceptOrWithdraw", "GraphEdits.java", 1 ),
				new StackTraceElement( GraphEdits.class.getName(), "addSpot", "GraphEdits.java", 1 ),
				new StackTraceElement( "a.b.AutoCorrectOrchestrator", "interpolate", "X.java", 1 ),
				new StackTraceElement( "a.b.AutoCorrectOrchestrator", "fillGaps", "X.java", 1 ),
				new StackTraceElement( "a.b.Other", "run", "Y.java", 1 ) };
		assertEquals( "AutoCorrectOrchestrator.interpolate<-AutoCorrectOrchestrator.fillGaps",
				SpotOrigin.callerOutside( trace ) );
	}

	/**
	 * Every spot on its own row, numbered by snapshot, so a benchmark can compare spots only within
	 * one. Pairing across snapshots matches a spot with itself.
	 */
	@Test
	public void eachSnapshotWritesEverySpotOnceUnderItsOwnNumber() throws IOException
	{
		final Model model = new Model();
		final ToolContext ctx = ToolContext.forReplay( model );
		addThroughChokepoint( ctx, model.getGraph(), 0, GraphEdits.Reason.DETECTION );
		addThroughChokepoint( ctx, model.getGraph(), 40, GraphEdits.Reason.TOPOLOGY );
		SpotOriginDump.snapshot( ctx, "adaptive_track" );
		SpotOriginDump.snapshot( ctx, "auto.correct" );

		final List< String > lines = Files.readAllLines( written( dump ), StandardCharsets.UTF_8 );
		assertEquals( "one header, then two rows per snapshot", 5, lines.size() );
		assertEquals( SpotOriginDump.HEADER.trim(), lines.get( 0 ) );
		final String[] cols = SpotOriginDump.HEADER.trim().split( "\t" );
		final String[] first = lines.get( 1 ).split( "\t", -1 );
		final String[] last = lines.get( 4 ).split( "\t", -1 );
		assertEquals( "every row has every column", cols.length, first.length );
		assertEquals( "adaptive_track", first[ 2 ] );
		assertEquals( "auto.correct", last[ 2 ] );
		assertTrue( "snapshots are numbered apart",
				Integer.parseInt( last[ 1 ] ) == Integer.parseInt( first[ 1 ] ) + 1 );
		final long topology = lines.stream().skip( 1 ).filter( l -> l.split( "\t", -1 )[ 13 ].equals( "TOPOLOGY" ) )
				.count();
		assertEquals( "the reason column carries the Reason, once per snapshot", 2, topology );
	}

	/**
	 * Each snapshot's links go beside it, by the ids on the spot rows, with the reason that made them
	 * and both ends' positions -- a window's last frame links into spots the window does not hold.
	 */
	@Test
	public void theLinksOfASnapshotAreWrittenWithTheirOrigin() throws IOException
	{
		final Model model = new Model();
		final ModelGraph g = model.getGraph();
		final ToolContext ctx = ToolContext.forReplay( model );
		final Spot a = GraphEdits.addSpot( ctx, g, g.vertexRef(), 0, new double[] { 1, 2, 0 }, 5.0, GraphEdits.Reason.DETECTION );
		final Spot b = GraphEdits.addSpot( ctx, g, g.vertexRef(), 1, new double[] { 3, 4, 0 }, 5.0, GraphEdits.Reason.DETECTION );
		GraphEdits.addLink( ctx, g, a, b, g.edgeRef(), GraphEdits.Reason.TOPOLOGY );
		SpotOriginDump.snapshot( ctx, "[4/6] link t=0..0", 0, 0 );

		final List< String > spots = Files.readAllLines( written( dump ), StandardCharsets.UTF_8 );
		assertEquals( "the window holds t=0 only", 2, spots.size() );
		final String[] spot = spots.get( 1 ).split( "\t", -1 );
		final List< String > links = Files.readAllLines( SpotOriginDump.linksFile( written( dump ) ), StandardCharsets.UTF_8 );
		assertEquals( SpotOriginDump.LINK_HEADER.trim(), links.get( 0 ) );
		assertEquals( 2, links.size() );
		final String[] link = links.get( 1 ).split( "\t", -1 );
		final String[] head = SpotOriginDump.LINK_HEADER.trim().split( "\t" );
		assertEquals( head.length, link.length );
		assertEquals( "the source is the spot row's id", spot[ spot.length - 1 ], link[ 3 ] );
		assertEquals( "1", link[ 6 ] );
		assertEquals( "the target's position is on the row although its spot is not", "3.0", link[ 9 ] );
		assertEquals( "TOPOLOGY", link[ 11 ] );
		assertTrue( "and the method that asked for it: " + link[ 12 ],
				link[ 12 ].contains( "SpotOriginTest.theLinksOfASnapshotAreWrittenWithTheirOrigin" ) );
	}

	/** A link added outside the chokepoint into a reused pool index must not inherit an origin. */
	@Test
	public void aReusedLinkIndexDoesNotInheritTheOldOrigin()
	{
		final Model model = new Model();
		final ModelGraph g = model.getGraph();
		final ToolContext ctx = ToolContext.forReplay( model );
		final Spot a = GraphEdits.addSpot( ctx, g, g.vertexRef(), 0, new double[] { 0, 0, 0 }, 5.0, GraphEdits.Reason.DETECTION );
		final Spot b = GraphEdits.addSpot( ctx, g, g.vertexRef(), 1, new double[] { 0, 0, 0 }, 5.0, GraphEdits.Reason.DETECTION );
		final org.mastodon.mamut.model.Link l = GraphEdits.addLink( ctx, g, a, b, g.edgeRef(), GraphEdits.Reason.TOPOLOGY );
		final int idx = l.getInternalPoolIndex();
		assertEquals( "TOPOLOGY", LinkOrigin.reasonOf( g, l ) );
		g.remove( l );
		final org.mastodon.mamut.model.Link outside = g.addEdge( a, b, g.edgeRef() ).init();
		assertEquals( "the test only means something if the index was reused", idx, outside.getInternalPoolIndex() );
		assertEquals( SpotOrigin.UNRECORDED, LinkOrigin.reasonOf( g, outside ) );
	}

	/**
	 * A removed link is filed under the next snapshot -- the step that removed it -- with what made
	 * it and why and where it went. A real division branch was linked at [4/6] and gone by [5c/6]
	 * with nothing saying which pass in between took it.
	 */
	@Test
	public void aRemovedLinkIsFiledUnderTheStepThatRemovedIt() throws IOException
	{
		final Model model = new Model();
		final ModelGraph g = model.getGraph();
		final ToolContext ctx = ToolContext.forReplay( model );
		final Spot a = GraphEdits.addSpot( ctx, g, g.vertexRef(), 0, new double[] { 1, 2, 0 }, 5.0, GraphEdits.Reason.DETECTION );
		final Spot b = GraphEdits.addSpot( ctx, g, g.vertexRef(), 1, new double[] { 3, 4, 0 }, 5.0, GraphEdits.Reason.DETECTION );
		final org.mastodon.mamut.model.Link l = GraphEdits.addLink( ctx, g, a, b, g.edgeRef(), GraphEdits.Reason.TOPOLOGY );
		SpotOriginDump.snapshot( ctx, "[4/6] link t=0..1", 0, 1 );
		GraphEdits.removeLink( ctx, g, l, GraphEdits.Reason.TOPOLOGY );
		SpotOriginDump.snapshot( ctx, "[5c/6] topology consistency t=0..1", 0, 1 );

		final List< String > rows = Files.readAllLines( SpotOriginDump.companion( written( dump ), ".removed" ),
				StandardCharsets.UTF_8 );
		assertEquals( SpotOriginDump.REMOVED_HEADER.trim(), rows.get( 0 ) );
		assertEquals( "one removal, filed once", 2, rows.size() );
		final String[] r = rows.get( 1 ).split( "\t", -1 );
		assertEquals( SpotOriginDump.REMOVED_HEADER.trim().split( "\t" ).length, r.length );
		assertEquals( "[5c/6] topology consistency t=0..1", r[ 2 ] );
		assertEquals( "0", r[ 3 ] );
		assertEquals( "what made it", "TOPOLOGY", r[ 9 ] );
		assertTrue( "and the method that removed it: " + r[ 12 ],
				r[ 12 ].contains( "SpotOriginTest.aRemovedLinkIsFiledUnderTheStepThatRemovedIt" ) );
	}
}
