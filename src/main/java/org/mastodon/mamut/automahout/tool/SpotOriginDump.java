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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.mastodon.mamut.automahout.elephant.DetectionTagLabels;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes every spot in the graph, with where it came from, after each tool call.
 *
 * <p>The split dump records what the DETECTOR produced, before any pass acts on it. That cannot
 * answer the question this exists for: a double detection photographed on one nucleus in the FINAL
 * result, after Phase 2 had interpolated 294 spots and restored others. What is needed is the graph
 * as the person sees it, each spot labelled with the {@link GraphEdits.Reason} and the calling
 * methods that added it ({@link SpotOrigin}).</p>
 *
 * <p>One snapshot per tool call, numbered, so a benchmark compares spots only within one snapshot:
 * pairing across snapshots matches a spot with itself, which is exactly the mistake that made the
 * first suppression measurement report duplicates 0.1 µm apart.</p>
 *
 * <p>Off unless {@code -Dautomahout.originDump=/path/to/file.tsv} or {@code AUTOMAHOUT_ORIGIN_DUMP} is
 * set. Appends; writes the header once; never throws — a benchmark dump that can fail a run is worse
 * than no dump.</p>
 */
public final class SpotOriginDump
{
	private static final Logger log = LoggerFactory.getLogger( SpotOriginDump.class );

	public static final String PROPERTY = "automahout.originDump";

	public static final String ENV = "AUTOMAHOUT_ORIGIN_DUMP";

	/**
	 * Written on every row, as in the split dump: the file is appended to across runs and builds.
	 * 2 = adds {@code id} (the spot's pool index within its snapshot) and the companion links file.
	 */
	public static final int REVISION = 2;

	static final String HEADER = "rev\tsnapshot\tlabel\tt\tx\ty\tz\tradius\ta\tb\ttheta\ttag\tprotected"
			+ "\treason\tsite\tid\n";

	/**
	 * The links of the same snapshot, beside it as {@code <name>.links.tsv}: every link whose SOURCE
	 * is in the snapshot's timepoints, by the {@code id}s of the spot rows, with the
	 * {@link LinkOrigin} reason and site that made it. Both ends' positions are on the row: a window's
	 * last frame links into the next window, whose spots are not in this snapshot.
	 */
	static final String LINK_HEADER = "rev\tsnapshot\tlabel\tsource\ttarget\ttSource\ttTarget"
			+ "\txSource\tySource\txTarget\tyTarget\treason\tsite\n";

	private static int snapshot;

	/**
	 * Links removed since the last snapshot, written with the NEXT one -- so each row is filed under
	 * the step that removed it. {@code <name>.removed.tsv}.
	 */
	static final String REMOVED_HEADER = "rev\tsnapshot\tlabel\ttSource\ttTarget\txSource\tySource"
			+ "\txTarget\tyTarget\tmadeBy\tmadeAt\tremovedFor\tremovedAt\n";

	private static final StringBuilder removed = new StringBuilder();

	/**
	 * Called by {@link GraphEdits#removeLink} before the link goes: what made it, and why and where it
	 * is being removed. Finding a real division branch cut by a cleanup pass needs exactly this -- the
	 * branch was linked at [4/6] and was gone by [5c/6], with nothing saying which of the passes in
	 * between took it.
	 */
	static synchronized void recordRemovedLink( final ModelGraph graph, final Link link,
			final GraphEdits.Reason why, final String site )
	{
		final Spot ref = graph.vertexRef();
		try
		{
			final Spot source = link.getSource( ref );
			final int ts = source.getTimepoint();
			final double xs = source.getDoublePosition( 0 ), ys = source.getDoublePosition( 1 );
			final Spot target = link.getTarget( ref );
			removed.append( ts ).append( '\t' ).append( target.getTimepoint() )
					.append( '\t' ).append( xs ).append( '\t' ).append( ys )
					.append( '\t' ).append( target.getDoublePosition( 0 ) ).append( '\t' )
					.append( target.getDoublePosition( 1 ) )
					.append( '\t' ).append( LinkOrigin.reasonOf( graph, link ) )
					.append( '\t' ).append( LinkOrigin.siteOf( graph, link ) )
					.append( '\t' ).append( why ).append( '\t' ).append( site ).append( '\n' );
		}
		finally
		{
			graph.releaseRef( ref );
		}
	}

	/** {@code <name>.removed_spots.tsv}: every spot removed, written as it goes. */
	static final String REMOVED_SPOTS_HEADER = "rev\tt\tx\ty\tz\tradius\treason\tsite\tquality\n";

	/**
	 * Called by {@link GraphEdits#removeSpot} before the spot goes: where it was, its size, why and by
	 * which method it is removed, and ELEPHANT's quality for it when there was one. Written at once, not
	 * with a snapshot: on Fluo-N3DH-CE 01 the correction after linking took about 56 detections per frame
	 * at t=140..149 and said nothing of which pass took them, and judging each against the truth needs
	 * its position, which is gone a moment later.
	 */
	static synchronized void recordRemovedSpot( final ModelGraph graph, final Spot spot, final GraphEdits.Reason why,
			final String site )
	{
		final String path = target();
		if ( path == null )
			return;
		try
		{
			final Path file = Paths.get( path + ".removed_spots.tsv" );
			final boolean fresh = !Files.exists( file );
			final java.util.OptionalDouble q = org.mastodon.mamut.automahout.elephant.DetectionQuality.get( graph, spot );
			final String row = REVISION + "\t" + spot.getTimepoint() + "\t" + spot.getDoublePosition( 0 ) + "\t"
					+ spot.getDoublePosition( 1 ) + "\t" + spot.getDoublePosition( 2 ) + "\t"
					+ Math.sqrt( spot.getBoundingSphereRadiusSquared() ) + "\t" + why + "\t" + site + "\t"
					+ ( q.isPresent() ? String.valueOf( q.getAsDouble() ) : "" ) + "\n";
			Files.write( file, ( ( fresh ? REMOVED_SPOTS_HEADER : "" ) + row ).getBytes( java.nio.charset.StandardCharsets.UTF_8 ),
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND );
		}
		catch ( final Exception e )
		{
			log.debug( "removed-spot dump failed", e );
		}
	}

	private SpotOriginDump()
	{}

	public static boolean enabled()
	{
		return target() != null;
	}

	static String target()
	{
		final String p = System.getProperty( PROPERTY );
		if ( p != null && !p.trim().isEmpty() )
			return DumpFiles.perRun( p.trim() );
		final String e = System.getenv( ENV );
		return e != null && !e.trim().isEmpty() ? DumpFiles.perRun( e.trim() ) : null;
	}

	/**
	 * Appends one snapshot of the whole graph.
	 *
	 * @param label what just ran, e.g. the tool id
	 */
	public static void snapshot( final ToolContext ctx, final String label )
	{
		snapshot( ctx, label, Integer.MIN_VALUE, Integer.MAX_VALUE );
	}

	/**
	 * Appends one snapshot of the spots at timepoints {@code tFrom..tTo} only.
	 *
	 * <p>For the checkpoints inside {@code adaptive_track}, which is ONE tool call covering the whole
	 * movie: a dump written only between tool calls said nothing until Phase 1 had finished, while the
	 * duplicate to be traced was already on screen in window 1. Scoped to the window being checked,
	 * because the whole graph at every step of every window is tens of megabytes for no information.</p>
	 */
	public static synchronized void snapshot( final ToolContext ctx, final String label, final int tFrom,
			final int tTo )
	{
		final String path = target();
		if ( path == null || ctx == null )
			return;
		try
		{
			final Path file = Paths.get( path );
			// Only when it is missing. On macOS /tmp is a symlink, and JDK 11's createDirectories
			// throws FileAlreadyExistsException for a symlinked directory: every snapshot of the first
			// run with this dump failed that way and wrote nothing.
			if ( file.getParent() != null && !Files.isDirectory( file.getParent() ) )
				Files.createDirectories( file.getParent() );
			final boolean headerWritten = Files.exists( file ) && Files.size( file ) > 0;
			final String[] text = rowsAndLinks( ctx, ++snapshot, label, tFrom, tTo );
			Files.write( file, ( headerWritten ? text[ 0 ] : HEADER + text[ 0 ] ).getBytes( StandardCharsets.UTF_8 ),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND );
			final Path gone = companion( file, ".removed" );
			final String goneRows = drainRemoved( snapshot, label );
			if ( !goneRows.isEmpty() || Files.exists( gone ) )
			{
				final boolean goneHeader = Files.exists( gone ) && Files.size( gone ) > 0;
				Files.write( gone, ( goneHeader ? goneRows : REMOVED_HEADER + goneRows ).getBytes( StandardCharsets.UTF_8 ),
						StandardOpenOption.CREATE, StandardOpenOption.APPEND );
			}
			final Path links = linksFile( file );
			final boolean linkHeader = Files.exists( links ) && Files.size( links ) > 0;
			Files.write( links, ( linkHeader ? text[ 1 ] : LINK_HEADER + text[ 1 ] ).getBytes( StandardCharsets.UTF_8 ),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND );
			log.info( "origin dump: snapshot {} ({}) appended to {}", snapshot, label, file );
		}
		catch ( final IOException | RuntimeException e )
		{
			log.warn( "origin dump unavailable", e );
		}
	}

	/** {@code dir/origin-dump-<stamp>.tsv} -> {@code dir/origin-dump-<stamp>.links.tsv}. */
	static Path linksFile( final Path spots )
	{
		return companion( spots, ".links" );
	}

	/** {@code dir/name.tsv} -> {@code dir/name<infix>.tsv}. */
	static Path companion( final Path spots, final String infix )
	{
		final String name = spots.getFileName().toString();
		final int dot = name.lastIndexOf( '.' );
		return spots.resolveSibling( dot > 0 ? name.substring( 0, dot ) + infix + name.substring( dot ) : name + infix );
	}

	/** The buffered removals as rows of snapshot {@code snap}, emptying the buffer. */
	private static String drainRemoved( final int snap, final String label )
	{
		if ( removed.length() == 0 )
			return "";
		final String safe = label == null ? "" : label.replace( '\t', ' ' ).replace( '\n', ' ' );
		final StringBuilder out = new StringBuilder();
		for ( final String row : removed.toString().split( "\n" ) )
			out.append( REVISION ).append( '\t' ).append( snap ).append( '\t' ).append( safe ).append( '\t' )
					.append( row ).append( '\n' );
		removed.setLength( 0 );
		return out.toString();
	}

	static String rows( final ToolContext ctx, final int snap, final String label )
	{
		return rowsAndLinks( ctx, snap, label, Integer.MIN_VALUE, Integer.MAX_VALUE )[ 0 ];
	}

	/** {spot rows, link rows} of one snapshot. */
	static String[] rowsAndLinks( final ToolContext ctx, final int snap, final String label, final int tFrom,
			final int tTo )
	{
		final Model model = ctx.getModel();
		final ModelGraph graph = model.getGraph();
		final String safeLabel = label == null ? "" : label.replace( '\t', ' ' ).replace( '\n', ' ' );
		final StringBuilder sb = new StringBuilder();
		final StringBuilder links = new StringBuilder();
		final double[] pos = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() < tFrom || spot.getTimepoint() > tTo )
					continue;
				spot.localize( pos );
				spot.getCovariance( cov );
				final double[] e = SpotShape.inPlaneEllipse( cov );
				final String tag = DetectionTagLabels.labelOf( model, spot );
				sb.append( REVISION ).append( '\t' ).append( snap ).append( '\t' ).append( safeLabel )
						.append( '\t' ).append( spot.getTimepoint() )
						.append( '\t' ).append( pos[ 0 ] ).append( '\t' ).append( pos[ 1 ] )
						.append( '\t' ).append( pos[ 2 ] )
						.append( '\t' ).append( Math.sqrt( spot.getBoundingSphereRadiusSquared() ) )
						.append( '\t' ).append( e == null ? "" : String.valueOf( e[ 0 ] ) )
						.append( '\t' ).append( e == null ? "" : String.valueOf( e[ 1 ] ) )
						.append( '\t' ).append( e == null ? "" : String.valueOf( e[ 2 ] ) )
						.append( '\t' ).append( tag == null ? "" : tag )
						.append( '\t' ).append( ProtectedSpots.isProtected( ctx, model, spot ) ? 1 : 0 )
						.append( '\t' ).append( SpotOrigin.reasonOf( graph, spot ) )
						.append( '\t' ).append( SpotOrigin.siteOf( graph, spot ) )
						.append( '\t' ).append( spot.getInternalPoolIndex() )
						.append( '\n' );
			}
			final Spot ref = graph.vertexRef();
			for ( final Link link : graph.edges() )
			{
				final Spot source = link.getSource( ref );
				final int ts = source.getTimepoint();
				final int sourceId = source.getInternalPoolIndex();
				if ( ts < tFrom || ts > tTo )
					continue;
				final double xs = source.getDoublePosition( 0 ), ys = source.getDoublePosition( 1 );
				final Spot target = link.getTarget( ref );
				links.append( REVISION ).append( '\t' ).append( snap ).append( '\t' ).append( safeLabel )
						.append( '\t' ).append( sourceId ).append( '\t' ).append( target.getInternalPoolIndex() )
						.append( '\t' ).append( ts ).append( '\t' ).append( target.getTimepoint() )
						.append( '\t' ).append( xs ).append( '\t' ).append( ys )
						.append( '\t' ).append( target.getDoublePosition( 0 ) ).append( '\t' )
						.append( target.getDoublePosition( 1 ) )
						.append( '\t' ).append( LinkOrigin.reasonOf( graph, link ) )
						.append( '\t' ).append( LinkOrigin.siteOf( graph, link ) )
						.append( '\n' );
			}
			graph.releaseRef( ref );
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return new String[] { sb.toString(), links.toString() };
	}
}
