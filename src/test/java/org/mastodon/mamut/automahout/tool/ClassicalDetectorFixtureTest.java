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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Scores {@link ClassicalDetector} on real pixels, against the silver truth.
 *
 * <p>The fixture is three 256x256 crops of Fluo-N2DL-HeLa, chosen as the densest windows of their
 * frames because a sparse crop tests almost nothing, with the truth label mask alongside. Image
 * values are quarter-sigma steps above background, so the 3-sigma level is exactly 12.</p>
 *
 * <p>This exists because the last estimator ported from the Python benchmark scored 61% where the
 * benchmark said 86%, and nothing would have caught it but running the Java over the same pixels.
 * Python scores this fixture at recall 88.0%, precision 97.8%, F1 92.6%.</p>
 */
public class ClassicalDetectorFixtureTest
{
	private static final int SIDE = 256;

	/** Truth dropped the cells touching the crop edge, so detections there cannot be scored. */
	private static final int MARGIN = 24;

	private static final double LEVEL = 12.0;   // 3 sigma, in quarter-sigma units

	private static final int MIN_AREA = 50;

	private static final int MIN_PEAK_DISTANCE = 12;

	private static boolean scored( final double x, final double y )
	{
		return x >= MARGIN && x < SIDE - MARGIN && y >= MARGIN && y < SIDE - MARGIN;
	}

	@Test
	public void itFindsTheCellsOnRealPixels() throws IOException
	{
		int found = 0, truth = 0, detected = 0, falsePositives = 0, overSegmented = 0;
		try ( InputStream in = getClass().getResourceAsStream( "/classical-baseline/crops.tsv" );
				BufferedReader r = new BufferedReader(
						new InputStreamReader( in, StandardCharsets.UTF_8 ) ) )
		{
			String line;
			while ( ( line = r.readLine() ) != null )
			{
				if ( line.isEmpty() || line.charAt( 0 ) == '#' )
					continue;
				final String[] f = line.split( "\\t" );
				final byte[] img = Base64.getDecoder().decode( f[ 2 ] );
				final byte[] tru = Base64.getDecoder().decode( f[ 3 ] );
				final double[] frame = new double[ img.length ];
				for ( int i = 0; i < img.length; i++ )
					frame[ i ] = img[ i ] & 0xFF;

				final List< ClassicalDetector.Detection > dets = ClassicalDetector.detect(
						frame, SIDE, SIDE, LEVEL, MIN_AREA, MIN_PEAK_DISTANCE );
				final Map< Integer, Integer > hits = new HashMap<>();
				for ( final ClassicalDetector.Detection d : dets )
				{
					if ( !scored( d.x, d.y ) )
						continue;
					detected++;
					final int label = tru[ ( int ) Math.round( d.y ) * SIDE
							+ ( int ) Math.round( d.x ) ] & 0xFF;
					if ( label == 0 )
						falsePositives++;
					else
						hits.merge( label, 1, Integer::sum );
				}
				// Truth cells whose centroid is inside the scored region.
				final int nCells = Integer.parseInt( f[ 1 ] );
				for ( int label = 1; label <= nCells; label++ )
				{
					double sx = 0, sy = 0;
					int area = 0;
					for ( int y = 0; y < SIDE; y++ )
					{
						for ( int x = 0; x < SIDE; x++ )
						{
							if ( ( tru[ y * SIDE + x ] & 0xFF ) == label )
							{
								sx += x;
								sy += y;
								area++;
							}
						}
					}
					if ( area == 0 || !scored( sx / area, sy / area ) )
						continue;
					truth++;
					final int n = hits.getOrDefault( label, 0 );
					if ( n > 0 )
					{
						found++;
						overSegmented += n - 1;
					}
				}
			}
		}
		final double recall = ( double ) found / Math.max( truth, 1 );
		final double precision = ( double ) found / Math.max( detected, 1 );
		final double f1 = 2 * recall * precision / Math.max( recall + precision, 1e-9 );
		System.out.printf( "ClassicalDetector: recall %.1f%%, precision %.1f%%, F1 %.1f%% "
				+ "(%d of %d cells, %d false positives, %d over-segmentations)%n",
				100 * recall, 100 * precision, 100 * f1, found, truth, falsePositives,
				overSegmented );

		assertTrue( truth > 40, "fixture did not load: " + truth + " truth cells" );
		assertTrue( f1 > 0.85, String.format( "F1 %.1f%%; Python scores this fixture at 92.6%%, and "
				+ "a port that quietly scores lower is the failure this test exists to catch", 100 * f1 ) );
		assertTrue( recall > 0.80, String.format( "recall %.1f%%, Python 88.0%%", 100 * recall ) );
		assertTrue( precision > 0.90, String.format( "precision %.1f%%, Python 97.8%%", 100 * precision ) );
		assertEquals( 0, falsePositives < 4 ? 0 : falsePositives,
				"Python finds no false positives on this fixture" );
		assertTrue( overSegmented <= 3, "over-segmentations: " + overSegmented + ", Python finds 1" );
	}
}
