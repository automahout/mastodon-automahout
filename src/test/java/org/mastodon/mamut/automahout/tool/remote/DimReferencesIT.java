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
package org.mastodon.mamut.automahout.tool.remote;

import static org.junit.Assume.assumeTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.VisualColdStart;

import bdv.BigDataViewer;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import ij.ImagePlus;
import ij.io.Opener;

/**
 * What the review's reference cells are on frames of a 2D BigDataViewer movie: the confident blobs, and the dim nuclei
 * added where the image reads as a cell (ElephantTool.addDimReferences). Prints the counts; with a CTC TRA folder,
 * how many of each land on no marker; with an output folder, draws them (cyan confident, yellow dim) on the frame.
 *
 * <pre>mvn test -Dtest=DimReferencesIT -Dautomahout.it.xml=movie.xml [-Dautomahout.it.frames=0,4,9]
 *     [-Dautomahout.it.tra=.../01_GT/TRA] [-Dautomahout.it.png=/some/dir]</pre>
 */
public class DimReferencesIT
{
	@Test
	public void theReferenceCellsOfAMovie() throws Exception
	{
		final String xml = System.getProperty( "automahout.it.xml" );
		assumeTrue( "set -Dautomahout.it.xml", xml != null );
		final SpimDataMinimal data = new XmlIoSpimDataMinimal().load( xml );
		final List< SourceAndConverter< ? > > sources = new ArrayList<>();
		BigDataViewer.initSetups( data, new ArrayList<>(), sources );
		final Source< ? > src = sources.get( 0 ).getSpimSource();
		final String tra = System.getProperty( "automahout.it.tra" ), png = System.getProperty( "automahout.it.png" );
		for ( final String ts : System.getProperty( "automahout.it.frames", "0" ).split( "," ) )
		{
			final int t = Integer.parseInt( ts.trim() );
			final double r = VisualColdStart.cellRadius( src, t, VisualColdStart.scaleLadder( 1.0, 10.0 ) )[ 0 ];
			final List< double[] > confident = new ArrayList<>( VisualColdStart.confidentBlobs( src, t, r ) );
			final Frame f = BdvFrameReader.read( src, t );
			final double[] stats = DetectorTrial.frameStats( f );
			final List< double[] > dim = new ArrayList<>();
			for ( final double[] p : VisualColdStart.dimBlobs( src, t, r, confident ) )
				if ( DetectorTrial.onSignal( f, stats, p, r ) )
					dim.add( p );
			String truth = "";
			if ( tra != null )
			{
				final ij.process.ImageProcessor m = new Opener().openImage( new File( tra, String.format( "man_track%03d.tif", t ) ).getPath() ).getProcessor();
				final java.util.Set< Integer > cells = new java.util.HashSet<>(), byConfident = labels( m, f, confident );
				for ( int i = 0; i < m.getPixelCount(); i++ )
					if ( m.get( i ) > 0 )
						cells.add( m.get( i ) );
				final java.util.Set< Integer > byDim = labels( m, f, dim );
				int again = 0;
				for ( final Integer l : byDim )
					if ( byConfident.contains( l ) )
						again++;
				final java.util.Set< Integer > all = new java.util.HashSet<>( byConfident );
				all.addAll( byDim );
				truth = String.format( "; on no marker: confident %d, dim %d; truth cells %d, covered %d -> %d; dim on a cell "
						+ "a confident one already has: %d", offMarker( m, f, confident ), offMarker( m, f, dim ), cells.size(),
						byConfident.size(), all.size(), again );
			}
			System.out.println( String.format( "RESULT t=%d radius %.2f: confident %d, dim added %d%s", t, r, confident.size(), dim.size(), truth ) );
			if ( png != null )
				draw( f, confident, dim, r, new File( png, String.format( "references_t%03d.png", t ) ) );
		}
	}

	/** The TRA labels under the places. */
	private static java.util.Set< Integer > labels( final ij.process.ImageProcessor m, final Frame f, final List< double[] > at )
	{
		final java.util.Set< Integer > out = new java.util.HashSet<>();
		for ( final double[] p : at )
		{
			final int[] v = voxel( f, p );
			if ( v[ 0 ] >= 0 && v[ 1 ] >= 0 && v[ 0 ] < m.getWidth() && v[ 1 ] < m.getHeight() && m.get( v[ 0 ], v[ 1 ] ) > 0 )
				out.add( m.get( v[ 0 ], v[ 1 ] ) );
		}
		return out;
	}

	/** How many places fall on no TRA marker (label 0 within the voxel under them). */
	private static int offMarker( final ij.process.ImageProcessor m, final Frame f, final List< double[] > at )
	{
		int off = 0;
		for ( final double[] p : at )
		{
			final int[] v = voxel( f, p );
			if ( v[ 0 ] < 0 || v[ 1 ] < 0 || v[ 0 ] >= m.getWidth() || v[ 1 ] >= m.getHeight() || m.get( v[ 0 ], v[ 1 ] ) == 0 )
				off++;
		}
		return off;
	}

	private static int[] voxel( final Frame f, final double[] world )
	{
		final net.imglib2.realtransform.AffineTransform3D a = new net.imglib2.realtransform.AffineTransform3D();
		a.set( f.voxelToWorld );
		final double[] v = new double[ 3 ];
		a.applyInverse( v, new double[] { world[ 0 ], world[ 1 ], world[ 2 ] } );
		return new int[] { ( int ) Math.round( v[ 0 ] ), ( int ) Math.round( v[ 1 ] ) };
	}

	private static void draw( final Frame f, final List< double[] > confident, final List< double[] > dim, final double r,
			final File out ) throws java.io.IOException
	{
		final int w = ( int ) f.dims[ 0 ], h = ( int ) f.dims[ 1 ];
		final float[] s = f.data.clone();
		java.util.Arrays.sort( s );
		final double lo = s[ ( int ) ( 0.01 * s.length ) ], hi = s[ ( int ) ( 0.995 * s.length ) ];
		final BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
		for ( int y = 0; y < h; y++ )
			for ( int x = 0; x < w; x++ )
			{
				final int g = ( int ) Math.max( 0, Math.min( 255, 255 * ( f.data[ y * w + x ] - lo ) / ( hi - lo ) ) );
				img.setRGB( x, y, ( g << 16 ) | ( g << 8 ) | g );
			}
		final Graphics2D g = img.createGraphics();
		g.setStroke( new BasicStroke( 1f ) );
		final double rv = r / Math.hypot( f.voxelToWorld[ 0 ], f.voxelToWorld[ 4 ] );
		for ( final Object[] set : new Object[][] { { confident, Color.CYAN }, { dim, Color.YELLOW } } )
		{
			g.setColor( ( Color ) set[ 1 ] );
			@SuppressWarnings( "unchecked" )
			final List< double[] > ps = ( List< double[] > ) set[ 0 ];
			for ( final double[] p : ps )
			{
				final int[] v = voxel( f, p );
				g.drawOval( ( int ) ( v[ 0 ] - rv ), ( int ) ( v[ 1 ] - rv ), ( int ) ( 2 * rv ), ( int ) ( 2 * rv ) );
			}
		}
		g.dispose();
		out.getParentFile().mkdirs();
		ImageIO.write( img, "png", out );
	}
}
