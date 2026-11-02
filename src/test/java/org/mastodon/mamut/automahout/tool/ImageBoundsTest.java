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
 * "At the border" has to mean the borders the dataset actually has.
 *
 * <p>On Fluo-N2DL-HeLa_01 the image is 709 x 452 x 0.645 µm — one z plane — and every spot sits at
 * {@code z = 0}. The two places that compared a position against the image extent tested all three
 * axes unconditionally, so for any {@code border_margin} at all, every detection in the movie was
 * "within the margin of an image face". Exactly the failure {@link SpotShape} was written for, in a
 * different measurement.</p>
 */
public class ImageBoundsTest
{
	/** Fluo-N2DL-HeLa: 1100 x 700 px at 0.645 µm, one plane. */
	private static final double[] FLAT = { 709.5, 451.5, 0.645 };

	private static final double[] VOLUME = { 100.0, 100.0, 50.0 };

	@Test
	public void aSpotInTheMiddleOfAOnePlaneMovieIsNotAtTheBorder()
	{
		final double[] middle = { 350.0, 225.0, 0.0 };
		assertTrue( "counting the one-voxel z face is what made this true for every spot",
				ImageBounds.reachesBorder( FLAT, middle, 20.0, true ) );
		assertFalse( "on one plane the border is the four edges of the image",
				ImageBounds.reachesBorder( FLAT, middle, 20.0, false ) );
	}

	@Test
	public void theFourEdgesAreStillBordersOnOnePlane()
	{
		assertTrue( ImageBounds.reachesBorder( FLAT, new double[] { 5.0, 225.0, 0.0 }, 20.0, false ) );
		assertTrue( ImageBounds.reachesBorder( FLAT, new double[] { 700.0, 225.0, 0.0 }, 20.0, false ) );
		assertTrue( ImageBounds.reachesBorder( FLAT, new double[] { 350.0, 5.0, 0.0 }, 20.0, false ) );
		assertTrue( ImageBounds.reachesBorder( FLAT, new double[] { 350.0, 445.0, 0.0 }, 20.0, false ) );
	}

	@Test
	public void volumetricDataStillJudgesAllThreeFaces()
	{
		assertTrue( ImageBounds.reachesBorder( VOLUME, new double[] { 50, 50, 2 }, 5.0, true ) );
		assertTrue( ImageBounds.reachesBorder( VOLUME, new double[] { 50, 50, 48 }, 5.0, true ) );
		assertFalse( ImageBounds.reachesBorder( VOLUME, new double[] { 50, 50, 25 }, 5.0, true ) );
	}

	@Test
	public void noMarginMeansNoBorderFiltering()
	{
		// Every caller reads border_margin=0 as "switched off"; a spot exactly on the edge is then not
		// at the border, because nothing is.
		assertFalse( ImageBounds.reachesBorder( FLAT, new double[] { 0, 0, 0 }, 0.0, false ) );
		assertFalse( ImageBounds.reachesBorder( FLAT, new double[] { 0, 0, 0 }, -1.0, false ) );
	}

	@Test
	public void anUnreadableExtentFailsOpen()
	{
		// Callers delete or flag what this returns true for, so "no information" must mean "keep".
		assertFalse( ImageBounds.reachesBorder( null, new double[] { 0, 0, 0 }, 20.0, false ) );
		assertFalse( ImageBounds.reachesBorder( FLAT, null, 20.0, false ) );
	}

	/**
	 * Ratchet: nothing outside {@link ImageBounds} may build the image's physical extent for itself.
	 * Five places did, and the two that went on to compare a position against it both counted the z
	 * face on data that has no z axis. A sixth added later would reintroduce that in a new place.
	 */
	@Test
	public void imageBoundsIsTheOnlyPlaceTheImageExtentIsComputed() throws IOException
	{
		final Path main = Paths.get( "src", "main", "java" );
		final List< String > offenders = new ArrayList<>();
		try ( Stream< Path > files = Files.walk( main ) )
		{
			for ( final Path f : files.filter( p -> p.toString().endsWith( ".java" ) )
					.collect( Collectors.toList() ) )
			{
				if ( f.getFileName().toString().equals( "ImageBounds.java" ) )
					continue;
				final String text = new String( Files.readAllBytes( f ), StandardCharsets.UTF_8 );
				for ( final String line : text.split( "\n" ) )
				{
					final String t = line.trim();
					if ( t.startsWith( "*" ) || t.startsWith( "//" ) || t.startsWith( "/*" ) )
						continue; // prose may describe it
					if ( t.replace( " ", "" ).contains( "dimension(2)*voxel" ) )
						offenders.add( f.getFileName() + ": " + t );
				}
			}
		}
		assertTrue( "read the image extent via ImageBounds.physicalExtent, and ask reachesBorder "
				+ "whether a spot is at an edge — it knows whether the dataset has a z axis: "
				+ offenders, offenders.isEmpty() );
	}
}
