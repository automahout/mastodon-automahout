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
 * A detection's interior must be sampled in the axes the dataset has.
 *
 * <p>Twenty-one points are read inside every detection: the centre, twelve along the three axes at
 * 0.5r and 0.8r, and eight diagonals. On a single-plane movie, four of the axial points and all
 * eight diagonals are displaced along z — an axis with one voxel in it. With a nucleus radius near
 * 9.5 µm and a 0.645 µm z voxel, an offset of 0.5r is seven planes outside an image that has one.</p>
 *
 * <p>That is 57% of the reads, and it pulls a real cell's interior mean down towards the background,
 * which is the very thing {@code deleteSpotsWithNoSignal} compares it against. On this dataset that
 * pass has printed "REFUSED on 10 timepoint(s): the ceiling would have taken more than 50% of the
 * frame" at every timepoint of every run, and refusing is all it has ever done — which is why
 * detections sitting on empty background are never removed. The same reads are the debris check's
 * coefficient of variation, so that measurement was a third image and two thirds nothing.</p>
 *
 * <p>Third instance of the family: {@code SpotShape} counted the missing z axis in an aspect ratio,
 * {@code ImageBounds} counted a one-voxel z face as a border.</p>
 */
public class InteriorSamplingAxesTest
{
	@Test
	public void volumetricSamplingIsUnchanged()
	{
		final double[][] o = AutoCorrectOrchestrator.spotInteriorOffsets( true );
		assertEquals( "centre + 3 axes x 2 radii x 2 signs + 8 diagonals", 21, o.length );
		int withZ = 0;
		for ( final double[] p : o )
			if ( p[ 2 ] != 0.0 )
				withZ++;
		assertEquals( "on real volumetric data every one of those z reads is a measurement", 12, withZ );
	}

	@Test
	public void onOnePlaneNothingIsReadOffThatPlane()
	{
		final double[][] o = AutoCorrectOrchestrator.spotInteriorOffsets( false );
		for ( final double[] p : o )
			assertEquals( "a sample displaced in z reads an image plane that does not exist", 0.0,
					p[ 2 ], 0.0 );
		assertEquals( "centre + 2 axes x 2 radii x 2 signs + 4 diagonals", 13, o.length );
	}

	@Test
	public void theInPlanePatternStillCoversTheCellRatherThanJustItsCentre()
	{
		// Removing z must not leave a pattern that only reads the middle: an interior mean taken from
		// one voxel is not an interior mean, and the debris check's variation would be identically zero.
		final double[][] o = AutoCorrectOrchestrator.spotInteriorOffsets( false );
		int offCentre = 0;
		double furthest = 0.0;
		for ( final double[] p : o )
		{
			final double d = Math.sqrt( p[ 0 ] * p[ 0 ] + p[ 1 ] * p[ 1 ] );
			if ( d > 0.0 )
				offCentre++;
			furthest = Math.max( furthest, d );
		}
		assertEquals( 12, offCentre );
		assertTrue( "the pattern has to reach out towards the rim, not huddle at the centre: " + furthest,
				furthest > 0.75 && furthest <= 1.0 );
	}

	@Test
	public void theDiagonalsStayInsideTheDetection()
	{
		// The diagonal step is 0.6/sqrt(axes) per axis, so its length is 0.6 whatever the axis count.
		// Getting that wrong when z was dropped would push the corner samples outside the ellipsoid.
		for ( final boolean vol : new boolean[] { true, false } )
			for ( final double[] p : AutoCorrectOrchestrator.spotInteriorOffsets( vol ) )
			{
				final double d = Math.sqrt( p[ 0 ] * p[ 0 ] + p[ 1 ] * p[ 1 ] + p[ 2 ] * p[ 2 ] );
				assertTrue( "a sample at " + d + " r is outside the detection it is measuring", d <= 1.0 );
			}
	}

	/** Ratchet: nothing may go back to one fixed pattern for both geometries. */
	@Test
	public void theInteriorPatternIsChosenByTheDatasetsGeometry() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 )
						.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		assertTrue( "the interior reads must ask which axes exist before displacing along them",
				src.contains( "spotInteriorOffsets( ctx )" ) );
		assertFalse( "measureInteriorIntensityProfile must not reach for the volumetric pattern directly",
				src.contains( "for ( final double[] o : SPOT_INTERIOR_OFFSETS )" ) );
	}

}
