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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.mastodon.mamut.automahout.tool.DumpFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the detector's own detections to a file so a benchmark can be built from them.
 *
 * <p>Every fixture used to tune the split pass so far was constructed from ground truth: a position
 * taken from a truth centroid and a radius chosen by whoever wrote the script. Five conclusions drawn
 * that way were wrong, and the last one was wrong in a way the fixture could not show -- it gave
 * every detection the same radius, so the radius appeared to carry no information and the danger in
 * a wide search reach did not exist. Production detections have a 34% spread in size.</p>
 *
 * <p>What is missing is not a better guess at the geometry. It is the geometry: what radius does the
 * detector actually assign to a detection straddling two cells, against one sitting on a single
 * cell. That cannot be derived from truth, only recorded.</p>
 *
 * <p>Off unless {@code -Dautomahout.splitDump=/path/to/file.tsv} is set, so an ordinary run neither
 * pays for it nor risks it. Appends; writes the header once; never throws -- a benchmark dump that
 * can fail a run is worse than no dump.</p>
 */
public final class SplitFixtureDump
{
	private static final Logger log = LoggerFactory.getLogger( SplitFixtureDump.class );

	/** System property naming the file to append to. Absent means the dump is off. */
	public static final String PROPERTY = "automahout.splitDump";

	/**
	 * Environment variable with the same meaning, checked when the property is unset.
	 *
	 * <p>Both, because the two ways this runs need different ones: a JVM property is natural from an
	 * IDE or a command line, and awkward to add to an already-running Fiji.</p>
	 */
	public static final String ENV = "AUTOMAHOUT_SPLIT_DUMP";

	/**
	 * Bumped whenever the MEANING of a column changes, and written on every ROW rather than only in
	 * the header.
	 *
	 * <p>The file is appended to, so a run after a fix leaves its rows next to the old ones and the
	 * header says nothing about either. That happened: a dump taken before the interior spreads were
	 * corrected was read back afterwards and looked like the fix had not worked. A per-row marker
	 * makes a stale row visible instead of silently averaged in.</p>
	 *
	 * <p>2 = interior spreads taken about the background rather than about zero.
	 * 3 = adds {@code extentRatio}, the extent walk's measured radius over the detection's, before the
	 * clamp (see {@link ExtentReading}); blank when the walk abstained.</p>
	 */
	public static final int REVISION = 3;

	private static final String HEADER = "rev\tt\tx\ty\tz\tradius\tfullCv\tmean\tmedian"
			+ "\tcoreMedian\tcoreCv\tframeMedianRadius\textentRatio\n";

	private static Path resolved;

	private static boolean headerWritten;

	private SplitFixtureDump()
	{}

	/** Whether a dump file is configured. Cheap enough to call per detection. */
	public static boolean enabled()
	{
		return target() != null;
	}

	/** The configured path, property first, or {@code null}. */
	static String target()
	{
		final String p = System.getProperty( PROPERTY );
		if ( p != null && !p.trim().isEmpty() )
			return DumpFiles.perRun( p.trim() );
		final String e = System.getenv( ENV );
		return e != null && !e.trim().isEmpty() ? DumpFiles.perRun( e.trim() ) : null;
	}

	/**
	 * Appends one detection.
	 *
	 * @param seed    {@code {x, y, z, radius}} in world units, as the pass holds it
	 * @param profile {@code interiorIntensityProfile}'s reading, or {@code null} if it had none
	 */
	public static void record( final int timepoint, final double[] seed, final double[] profile,
			final double frameMedianRadius )
	{
		record( timepoint, seed, profile, frameMedianRadius, Double.NaN );
	}

	/**
	 * As above, with the extent walk's unclamped reading.
	 *
	 * @param extentRatio measured radius / detection radius before the clamp, or NaN
	 */
	public static synchronized void record( final int timepoint, final double[] seed,
			final double[] profile, final double frameMedianRadius, final double extentRatio )
	{
		final String path = target();
		if ( path == null || seed == null || seed.length < 4 )
			return;
		try
		{
			if ( resolved == null || !resolved.toString().equals( path ) )
			{
				resolved = Paths.get( path );
				headerWritten = Files.exists( resolved ) && Files.size( resolved ) > 0;
				// Only when missing: JDK 11 throws for a symlinked directory such as macOS's /tmp. Here
				// that cost the first row of every run, since `resolved` was already set and the next
				// call skipped this block.
				if ( resolved.getParent() != null && !Files.isDirectory( resolved.getParent() ) )
					Files.createDirectories( resolved.getParent() );
				log.info( "split dump: appending to {}", resolved );
			}
			final StringBuilder sb = new StringBuilder();
			if ( !headerWritten )
			{
				sb.append( HEADER );
				headerWritten = true;
			}
			sb.append( REVISION ).append( '\t' ).append( timepoint );
			for ( int i = 0; i < 4; i++ )
				sb.append( '\t' ).append( seed[ i ] );
			// A detection the image could not be read at is still a detection: it is written with
			// blanks rather than dropped, because "the profile was unavailable" is itself a reading
			// the benchmark needs to see the frequency of.
			for ( int i = 0; i < 5; i++ )
				sb.append( '\t' ).append( profile != null && profile.length > i
						? String.valueOf( profile[ i ] ) : "" );
			sb.append( '\t' ).append( frameMedianRadius )
					.append( '\t' ).append( Double.isNaN( extentRatio ) ? "" : String.valueOf( extentRatio ) )
					.append( '\n' );
			Files.write( resolved, sb.toString().getBytes( StandardCharsets.UTF_8 ),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND );
		}
		catch ( final IOException | RuntimeException e )
		{
			log.debug( "split fixture dump unavailable", e );
		}
	}
}
