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
package org.mastodon.mamut.automahout.bench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.model.Spot;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.realtransform.AffineTransform3D;

public class CtcExportTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static int labelAt( final ImagePlus imp, final int x, final int y, final int z )
	{
		imp.setSliceWithoutUpdate( z + 1 );
		return imp.getProcessor().get( x, y );
	}

	/**
	 * A mother dividing into two daughters, one of which skips a frame, and a merge of two tracks into one
	 * spot: the CTC tracks, parents, begin/end frames and mask labels that must come out.
	 */
	@Test
	public void divisionsGapsAndMergesBecomeCtcTracks() throws Exception
	{
		final ReplayGraph g = new ReplayGraph();
		// mother t=0..2 at x=20
		final Spot m0 = g.spot( 0, 20, 20, 0, 4 ), m1 = g.spot( 1, 20, 20, 0, 4 ), m2 = g.spot( 2, 20, 20, 0, 4 );
		g.link( m0, m1 );
		g.link( m1, m2 );
		// daughters at t=3: B continues to t=4, C skips t=4 and reappears at t=5
		final Spot b3 = g.spot( 3, 12, 20, 0, 3 ), b4 = g.spot( 4, 11, 20, 0, 3 );
		final Spot c3 = g.spot( 3, 28, 20, 0, 3 ), c5 = g.spot( 5, 30, 20, 0, 3 );
		g.link( m2, b3 );
		g.link( m2, c3 );
		g.link( b3, b4 );
		g.link( c3, c5 );
		// a merge: two tracks at t=0 (x=50 and x=58) both link into one spot at t=1 (x=55)
		final Spot p = g.spot( 0, 50, 50, 0, 3 ), q = g.spot( 0, 58, 50, 0, 3 ), r = g.spot( 1, 55, 50, 0, 3 );
		g.link( p, r );
		g.link( q, r );

		final Path out = tmp.newFolder( "res" ).toPath();
		final CtcExport.Result res = CtcExport.export( g.model(), new long[] { 64, 64 }, new AffineTransform3D(), 0, 5,
				out );

		assertEquals( 1, res.mergesCut );
		assertEquals( 0, res.spotsWithoutPixels );
		final List< String > lines = Files.readAllLines( out.resolve( "res_track.txt" ), StandardCharsets.US_ASCII );
		// labels in time order: mother=1, p=2, q=3 (t=0); B=4, C=5 (t=3); r continues q, the nearer one; C's
		// spot after the gap is a new track, 6, whose parent is C.
		assertEquals( Arrays.asList( "1 0 2 0", "2 0 0 0", "3 0 1 0", "4 3 4 1", "5 3 3 1", "6 5 5 5" ), lines );
		for ( int t = 0; t <= 5; t++ )
			assertTrue( Files.exists( out.resolve( String.format( "mask%03d.tif", t ) ) ) );
		final ImagePlus t3 = IJ.openImage( out.resolve( "mask003.tif" ).toString() );
		assertEquals( 4, labelAt( t3, 12, 20, 0 ) );
		assertEquals( 5, labelAt( t3, 28, 20, 0 ) );
		assertEquals( 0, labelAt( t3, 20, 40, 0 ) );
		final ImagePlus t4 = IJ.openImage( out.resolve( "mask004.tif" ).toString() );
		assertEquals( "C has a gap at t=4", 0, labelAt( t4, 30, 20, 0 ) );
		assertEquals( 6, labelAt( IJ.openImage( out.resolve( "mask005.tif" ).toString() ), 30, 20, 0 ) );
		final ImagePlus t1 = IJ.openImage( out.resolve( "mask001.tif" ).toString() );
		assertEquals( 3, labelAt( t1, 55, 50, 0 ) );
	}

	/** World coordinates go through the transform: anisotropic voxels, 3D stacks. */
	@Test
	public void anisotropicThreeDimensionalMasksFollowTheTransform() throws Exception
	{
		final ReplayGraph g = new ReplayGraph();
		// world units: x,y 0.5 per pixel, z 2 per plane. A spot of radius 3 at world (10, 10, 8) covers
		// pixel (20, 20, 4) and reaches 6 pixels in x and 1.5 planes in z.
		g.spot( 0, 10, 10, 8, 3 );
		final AffineTransform3D t = new AffineTransform3D();
		t.set( 0.5, 0, 0, 0, 0, 0.5, 0, 0, 0, 0, 2.0, 0 );
		final Path out = tmp.newFolder( "res3d" ).toPath();
		CtcExport.export( g.model(), new long[] { 40, 40, 8 }, t, 0, 0, out );
		final ImagePlus m = IJ.openImage( out.resolve( "mask000.tif" ).toString() );
		assertEquals( 8, m.getStackSize() );
		assertEquals( 1, labelAt( m, 20, 20, 4 ) );
		assertEquals( 1, labelAt( m, 25, 20, 4 ) );
		assertEquals( 0, labelAt( m, 27, 20, 4 ) );
		assertEquals( 1, labelAt( m, 20, 20, 5 ) );
		assertEquals( 0, labelAt( m, 20, 20, 6 ) );
		assertEquals( Arrays.asList( "1 0 0 0" ),
				Files.readAllLines( out.resolve( "res_track.txt" ), StandardCharsets.US_ASCII ) );
	}

	/** Where two ellipses overlap, a pixel goes to the one it is deeper inside. */
	@Test
	public void overlapGoesToTheDeeperSpot() throws Exception
	{
		final ReplayGraph g = new ReplayGraph();
		g.spot( 0, 20, 20, 0, 6 );
		g.spot( 0, 28, 20, 0, 6 );
		final Path out = tmp.newFolder( "ov" ).toPath();
		CtcExport.export( g.model(), new long[] { 64, 64 }, new AffineTransform3D(), 0, 0, out );
		final ImagePlus m = IJ.openImage( out.resolve( "mask000.tif" ).toString() );
		assertEquals( 1, labelAt( m, 23, 20, 0 ) );
		assertEquals( 2, labelAt( m, 25, 20, 0 ) );
	}

	/**
	 * A track hidden mid-track by a second, larger detection lying over it (HeLa 01, condition E, GPU server,
	 * 2026-10-18: labels 210/215/216 claimed in frames 73-76 without a pixel there, and the whole result was
	 * invalid). It must be split there: the part after the hidden frame is a new track whose parent is the part
	 * before, and the track's daughters hang under that last part.
	 */
	@Test
	public void aTrackHiddenMidTrackIsSplitThere() throws Exception
	{
		final ReplayGraph g = new ReplayGraph();
		// A: t=0..2 at x=20.5 (between pixel centres), radius 2; at t=1 a radius-6 detection with the same centre
		// is deeper at every pixel of A, so A keeps none.
		final Spot a0 = g.spot( 0, 20.5, 20, 0, 2 ), a1 = g.spot( 1, 20.5, 20, 0, 2 ),
				a2 = g.spot( 2, 20.5, 20, 0, 2 );
		g.link( a0, a1 );
		g.link( a1, a2 );
		g.spot( 1, 20.5, 20, 0, 6 );
		// A divides after t=2
		final Spot d1 = g.spot( 3, 14, 20, 0, 2 ), d2 = g.spot( 3, 26, 20, 0, 2 );
		g.link( a2, d1 );
		g.link( a2, d2 );

		final Path out = tmp.newFolder( "hidden" ).toPath();
		final CtcExport.Result res = CtcExport.export( g.model(), new long[] { 64, 64 }, new AffineTransform3D(), 0,
				3, out );

		assertEquals( 1, res.hiddenSplits );
		assertEquals( 1, res.spotsWithoutPixels );
		final List< String > lines = Files.readAllLines( out.resolve( "res_track.txt" ), StandardCharsets.US_ASCII );
		// A = 1 (t=0 only), the covering spot = 2 (t=1), daughters 3 and 4 (t=3), A's part after the hidden frame
		// = 5 (t=2, parent A); the daughters' parent is that last part.
		assertEquals( Arrays.asList( "1 0 0 0", "2 1 1 0", "3 3 3 5", "4 3 3 5", "5 2 2 1" ), lines );
		assertEquals( 2, labelAt( IJ.openImage( out.resolve( "mask001.tif" ).toString() ), 20, 20, 0 ) );
		assertEquals( 5, labelAt( IJ.openImage( out.resolve( "mask002.tif" ).toString() ), 20, 20, 0 ) );
		// every track is present in each frame from its begin to its end (the CTC rule that failed)
		for ( final String line : lines )
		{
			final String[] f = line.split( " " );
			for ( int t = Integer.parseInt( f[ 1 ] ); t <= Integer.parseInt( f[ 2 ] ); t++ )
			{
				final ImagePlus m = IJ.openImage( out.resolve( String.format( "mask%03d.tif", t ) ).toString() );
				boolean found = false;
				for ( int y = 0; y < 64 && !found; y++ )
					for ( int x = 0; x < 64 && !found; x++ )
						found = labelAt( m, x, y, 0 ) == Integer.parseInt( f[ 0 ] );
				assertTrue( "track " + f[ 0 ] + " missing at t=" + t, found );
			}
		}
	}
}
