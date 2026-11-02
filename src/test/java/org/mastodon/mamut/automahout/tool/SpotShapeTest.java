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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * On one image plane the aspect ratio must be measured in-plane, or every nucleus is "elongated".
 *
 * <p>The numbers here are the ones the user's own run printed on Fluo-N2DL-HeLa_01 (one z plane,
 * 0.645 µm pixels): a flat-contrast shape report of {@code kept 13.9 µm / aspect 9.88} against a
 * {@code max_aspect_ratio} of 6.0, and {@code elongated=98%} every window. Those are detections the
 * pipeline was keeping and training on — round nuclei — read as elongated artifacts purely because
 * the movie has no z axis to be round in.</p>
 */
public class SpotShapeTest
{
	/** Diagonal covariance for semi-axes {@code (rx, ry, rz)} in world units. */
	private static double[][] cov( final double rx, final double ry, final double rz )
	{
		return new double[][] { { rx * rx, 0, 0 }, { 0, ry * ry, 0 }, { 0, 0, rz * rz } };
	}

	/**
	 * The shape behind the run's own "kept ... aspect 9.88 / size 13.9 µm": a round nucleus on one
	 * plane. Solving {@code 2R² + r² = 13.9²} with {@code R/r = 9.88} gives these radii.
	 */
	private static final double[][] ROUND_NUCLEUS_2D = cov( 9.82, 9.82, 0.994 );

	private static final double MAX_ASPECT_RATIO = 6.0;

	@Test
	public void aRoundNucleusOnOnePlaneReadsAsElongatedWhenZIsCounted()
	{
		// The old behaviour, kept as the measurement it is: this is why elongated=98%.
		final double asIfVolumetric = SpotShape.aspectRatio( ROUND_NUCLEUS_2D, true );
		assertTrue( "the run reported aspect 9.88 for the spots it KEPT; got " + asIfVolumetric,
				asIfVolumetric > 9.5 && asIfVolumetric < 10.3 );
		assertTrue( "and 9.88 > 6.0, so every one of them was up for deletion",
				SpotShape.isElongated( ROUND_NUCLEUS_2D, MAX_ASPECT_RATIO, true ) );
	}

	@Test
	public void andIsRoundWhenItIsMeasuredInThePlaneItLivesIn()
	{
		assertEquals( 1.0, SpotShape.aspectRatio( ROUND_NUCLEUS_2D, false ), 1e-9 );
		assertFalse( "a round nucleus must survive the malformed-spot filter",
				SpotShape.isElongated( ROUND_NUCLEUS_2D, MAX_ASPECT_RATIO, false ) );
	}

	@Test
	public void aGenuinelyStretchedDetectionIsStillCaughtOnOnePlane()
	{
		// The artifact the check exists for: stretched WITHIN the image, 20 µm by 2 µm.
		final double[][] streak = cov( 20.0, 2.0, 0.994 );
		assertEquals( 10.0, SpotShape.aspectRatio( streak, false ), 1e-9 );
		assertTrue( "the in-plane check must keep deleting what it was written for",
				SpotShape.isElongated( streak, MAX_ASPECT_RATIO, false ) );
	}

	@Test
	public void volumetricDataIsUnchanged()
	{
		// Fluo-N3DH-CE: 0.09/0.09/1.0 µm voxels, a nucleus spanning several planes. Still judged on
		// all three axes, because there z is a measurement.
		final double[][] nucleus3d = cov( 5.0, 5.0, 4.0 );
		assertEquals( 1.25, SpotShape.aspectRatio( nucleus3d, true ), 1e-9 );
		assertFalse( SpotShape.isElongated( nucleus3d, MAX_ASPECT_RATIO, true ) );
		final double[][] needle3d = cov( 30.0, 3.0, 3.0 );
		assertTrue( SpotShape.isElongated( needle3d, MAX_ASPECT_RATIO, true ) );
	}

	@Test
	public void aDegenerateCovarianceFailsOpen()
	{
		// It deletes spots, so "no shape information" must mean "keep", as every site it replaced did.
		assertEquals( 1.0, SpotShape.aspectRatio( cov( 5.0, 0.0, 1.0 ), false ), 1e-9 );
		assertFalse( SpotShape.isElongated( cov( 5.0, 0.0, 1.0 ), MAX_ASPECT_RATIO, false ) );
		assertEquals( 1.0, SpotShape.aspectRatio( cov( 5.0, 5.0, 0.0 ), true ), 1e-9 );
	}

	@Test
	public void aRatioOfZeroSwitchesTheCheckOff()
	{
		assertFalse( SpotShape.isElongated( cov( 30.0, 1.0, 1.0 ), 0.0, false ) );
	}

	/**
	 * Ratchet: nothing outside {@link SpotShape} may derive an aspect ratio from the covariance
	 * diagonal. Six sites did, none of them asking whether the dataset had a z axis; a seventh added
	 * later would reintroduce exactly this bug in a place nobody is looking.
	 */
	@Test
	public void spotShapeIsTheOnlyPlaceAnAspectRatioIsComputed() throws IOException
	{
		final Path main = Paths.get( "src", "main", "java" );
		final List< String > offenders = new ArrayList<>();
		try ( Stream< Path > files = Files.walk( main ) )
		{
			for ( final Path f : files.filter( p -> p.toString().endsWith( ".java" ) )
					.collect( Collectors.toList() ) )
			{
				if ( f.getFileName().toString().equals( "SpotShape.java" ) )
					continue;
				final String text = new String( Files.readAllBytes( f ), StandardCharsets.UTF_8 );
				for ( final String line : text.split( "\n" ) )
				{
					final String t = line.trim();
					if ( t.startsWith( "*" ) || t.startsWith( "//" ) )
						continue; // prose may describe the ratio
					if ( t.contains( "maxD / minD" ) || t.contains( "maxD/minD" ) )
						offenders.add( f.getFileName() + ": " + t );
				}
			}
		}
		assertTrue( "compute the aspect ratio via SpotShape.aspectRatio, which knows whether the "
				+ "dataset has a z axis: " + offenders, offenders.isEmpty() );
	}
}
