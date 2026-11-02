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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * Runs {@link SeedToObject} over real sampled windows and pins what it achieves.
 *
 * <p>The fixture is 400 pairs taken from Fluo-N2DL-HeLa with silver truth saying which are one cell
 * seen twice and which are two cells -- the same data, sampled the same way (stride 2), that
 * {@code benchmark/seed-to-object} measured. Without this the Java would only ever have been checked
 * against constructed shapes and my expectations about them, and a first probe on synthetic discs
 * disagreed with the measurement badly enough to look like a defect.</p>
 */
public class SeedToObjectFixtureTest
{
	private static final int SIDE = 48;

	/** Values are tenths of a background sigma, so the 3-sigma object level is exactly 30. */
	private static final double LEVEL = 10.0 * SeedToObject.OBJECT_LEVEL_SIGMA;

	@Test
	public void itReproducesTheMeasuredRates() throws IOException
	{
		int sameTotal = 0, sameMerged = 0, diffTotal = 0, diffMerged = 0, abstained = 0;
		try ( InputStream in = getClass().getResourceAsStream( "/seed-to-object/windows.tsv" );
				BufferedReader r = new BufferedReader(
						new InputStreamReader( in, StandardCharsets.UTF_8 ) ) )
		{
			String line;
			while ( ( line = r.readLine() ) != null )
			{
				if ( line.isEmpty() || line.charAt( 0 ) == '#' )
					continue;
				final String[] f = line.split( "\\t" );
				final boolean oneCell = "1".equals( f[ 0 ] );
				final int ax = Integer.parseInt( f[ 1 ] ), ay = Integer.parseInt( f[ 2 ] );
				final int bx = Integer.parseInt( f[ 3 ] ), by = Integer.parseInt( f[ 4 ] );
				final byte[] raw = Base64.getDecoder().decode( f[ 5 ] );
				final double[] win = new double[ raw.length ];
				for ( int i = 0; i < raw.length; i++ )
					win[ i ] = raw[ i ] & 0xFF;
				final Boolean verdict =
						SeedToObject.sameObject( win, SIDE, SIDE, LEVEL, ax, ay, bx, by );
				if ( verdict == null )
				{
					abstained++;
					continue;
				}
				if ( oneCell )
				{
					sameTotal++;
					if ( verdict )
						sameMerged++;
				}
				else
				{
					diffTotal++;
					if ( verdict )
						diffMerged++;
				}
			}
		}
		final double recall = ( double ) sameMerged / Math.max( sameTotal, 1 );
		final double wrong = ( double ) diffMerged / Math.max( diffTotal, 1 );
		System.out.printf( "SeedToObject: merges %.1f%% of %d one-cell pairs, wrongly merges "
				+ "%.1f%% of %d two-cell pairs, abstains on %d%n",
				100 * recall, sameTotal, 100 * wrong, diffTotal, abstained );
		assertTrue( sameTotal > 100 && diffTotal > 100, "fixture did not load" );
		// Achieved 85.5% / 6.8% at MIN_MARKER_DISTANCE = 5. The bounds leave room for an
		// implementation detail to move without silently losing the property.
		assertTrue( recall > 0.80, String.format(
				"merges only %.1f%% of one-cell pairs; the straight-segment test this replaces managed "
						+ "9.0%%, and this one achieved 85.5%%", 100 * recall ) );
		assertTrue( wrong < 0.10, String.format(
				"wrongly merges %.1f%% of two-cell pairs, against 6.8%% achieved and 11.1%% for the "
						+ "test this replaces", 100 * wrong ) );
	}
}
