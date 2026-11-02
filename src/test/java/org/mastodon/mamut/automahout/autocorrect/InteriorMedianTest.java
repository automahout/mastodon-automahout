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
import java.nio.file.Paths;

import org.junit.Test;

/**
 * "Is this detection on a cell" is a question about MOST of it.
 *
 * <p>The no-signal test compared a detection's interior MEAN against a background-anchored ceiling.
 * A detection half-overlapping its neighbour has several interior reads on that nucleus and the rest
 * on background, and those few drag the mean above any such ceiling — which is the circle sitting on
 * empty space with one edge touching a cell, surviving every pass. The median does not move for
 * them.</p>
 *
 * <p>The background half of the same comparison has been a median and a MAD since
 * {@code computeBackgroundStats} was made robust, and for the same stated reason: a few probes
 * landing on undetected cells move a mean and barely move a median. The two halves of one test were
 * not the same statistic.</p>
 */
public class InteriorMedianTest
{
	/** This dataset: background 33044 with a spread of 7, nuclei at 33700, so a ceiling of 33054.5. */
	private static final double BACKGROUND = 33044.0, SPREAD = 7.0, CELL = 33700.0;

	private static double ceiling()
	{
		return AutoCorrectOrchestrator.noSignalCeiling( BACKGROUND, SPREAD, 0.0 );
	}

	/** Mean and median of {@code n} interior reads of which {@code onCell} land on a nucleus. */
	private static double[] profile( final int n, final int onCell )
	{
		final double[] v = new double[ n ];
		for ( int i = 0; i < n; i++ )
			v[ i ] = i < onCell ? CELL : BACKGROUND;
		double sum = 0;
		for ( final double x : v )
			sum += x;
		final double[] sorted = v.clone();
		java.util.Arrays.sort( sorted );
		return new double[] { sum / n, sorted[ n / 2 ] };
	}

	@Test
	public void aDetectionWithOneEdgeOnANucleusSurvivesTheMeanAndFailsTheMedian()
	{
		// 13 in-plane reads, 4 of them on the neighbour — an overlap of well under half.
		final double[] p = profile( 13, 4 );

		assertTrue( "the mean clears the ceiling on four reads out of thirteen: " + p[ 0 ],
				p[ 0 ] > ceiling() );
		assertTrue( "and the median says what the picture says — this is on background: " + p[ 1 ],
				p[ 1 ] <= ceiling() );
	}

	@Test
	public void aDetectionActuallyOnACellPassesEitherWay()
	{
		// The rule has to keep real cells, which is the whole risk of tightening a deletion test.
		final double[] p = profile( 13, 10 );
		assertTrue( p[ 0 ] > ceiling() );
		assertTrue( "a detection mostly on a nucleus must not be deleted: " + p[ 1 ], p[ 1 ] > ceiling() );
	}

	@Test
	public void halfAndHalfIsKept()
	{
		// The boundary case is decided in favour of keeping: with 13 reads, 7 on the cell is a
		// majority and the median is the cell. A detection the picture would call ambiguous survives.
		assertTrue( profile( 13, 7 )[ 1 ] > ceiling() );
		assertTrue( profile( 13, 6 )[ 1 ] <= ceiling() );
	}

	@Test
	public void theProfileCarriesBothStatisticsSoEachCallerCanAskItsOwnQuestion() throws IOException
	{
		// The dim/total-brightness filters want "how much signal does this hold", which is the mean
		// times the volume. Replacing the mean rather than adding the median would have changed them
		// silently.
		final String src = source();
		assertTrue( "the profile must still carry the mean for the total-brightness filters",
				src.contains( "profile[ 1 ] * " ) );
	}

	@Test
	public void theNoSignalTestAsksForTheMedian() throws IOException
	{
		assertTrue( "the no-signal deletion must compare the interior MEDIAN against the ceiling",
				source().contains( "profile[ 2 ] <= ceiling" ) );
		assertFalse( "and must not have been left on the mean",
				source().contains( "profile[ 1 ] <= ceiling" ) );
	}

	/**
	 * The test only means anything under fluorescence, and now says so rather than relying on a
	 * 50%-of-the-frame backstop to notice.
	 */
	@Test
	public void theNoSignalTestOnlyRunsWhereBrightnessMeansSignal() throws IOException
	{
		assertTrue( "brighter-than-background is a fact about fluorescence; on phase contrast this "
				+ "pass once described 84% of a frame", source().contains( "!imageProfile.fluorescenceLike" ) );
	}

	private static String source() throws IOException
	{
		return new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org", "mastodon",
				"mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ), StandardCharsets.UTF_8 )
						.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
	}
}
