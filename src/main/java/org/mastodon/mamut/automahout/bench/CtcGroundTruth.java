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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Reads CTC tracking ground truth ({@code TRA/man_track.txt} + {@code man_trackTTT.tif}) into a Mastodon model:
 * one spot per label per frame, the ellipsoid with the object's second moments (a solid ellipse has moments
 * a^2/4 in 2D, a^2/5 in 3D, so the shape matrix is 4x or 5x the covariance), linked along each track and from a
 * parent's last spot to each child's first.
 *
 * <p>Used to validate {@link CtcExport} end to end, and to measure the ceiling of the ellipsoid representation:
 * {@code java ... CtcGroundTruth <TRA dir> <out dir> <vx> <vy> [<vz>]} writes the GT back out through the
 * exporter, for {@code scripts/paper/evaluate_ctc.py} to score against the original.</p>
 */
public final class CtcGroundTruth
{
	private CtcGroundTruth()
	{}

	/** Frames and image size found while reading. */
	public static final class Read
	{
		public final Model model;

		public final long[] dims;

		public final int tMin, tMax;

		Read( final Model model, final long[] dims, final int tMin, final int tMax )
		{
			this.model = model;
			this.dims = dims;
			this.tMin = tMin;
			this.tMax = tMax;
		}
	}

	public static Read read( final Path traDir, final AffineTransform3D pixelToWorld ) throws IOException
	{
		final TreeMap< Integer, Path > frames = new TreeMap<>();
		try (java.util.stream.Stream< Path > s = Files.list( traDir ))
		{
			s.forEach( p -> {
				final String n = p.getFileName().toString();
				if ( n.startsWith( "man_track" ) && n.endsWith( ".tif" ) )
					frames.put( Integer.parseInt( n.substring( 9, n.length() - 4 ) ), p );
			} );
		}
		final Map< Integer, int[] > tracks = new HashMap<>(); // label -> {B, E, P}
		for ( final String line : Files.readAllLines( traDir.resolve( "man_track.txt" ), StandardCharsets.US_ASCII ) )
		{
			final String[] f = line.trim().split( "\\s+" );
			if ( f.length == 4 )
				tracks.put( Integer.parseInt( f[ 0 ] ), new int[] { Integer.parseInt( f[ 1 ] ), Integer.parseInt( f[ 2 ] ),
						Integer.parseInt( f[ 3 ] ) } );
		}
		final Model model = new Model();
		final ModelGraph graph = model.getGraph();
		final Map< Integer, Spot > last = new HashMap<>(); // label -> its latest spot so far
		final Map< Integer, Spot > first = new HashMap<>();
		long[] dims = null;
		final Spot ref = graph.vertexRef();
		for ( final Map.Entry< Integer, Path > e : frames.entrySet() )
		{
			final int t = e.getKey();
			final ImagePlus imp = IJ.openImage( e.getValue().toString() );
			final int nx = imp.getWidth(), ny = imp.getHeight(), nz = imp.getStackSize();
			dims = nz > 1 ? new long[] { nx, ny, nz } : new long[] { nx, ny };
			final Map< Integer, double[] > m = new HashMap<>(); // label -> n, sx, sy, sz, sxx, syy, szz, sxy, sxz, syz
			final ImageStack st = imp.getStack();
			final double[] p = new double[ 3 ], w = new double[ 3 ];
			for ( int z = 0; z < nz; z++ )
			{
				final ij.process.ImageProcessor ip = st.getProcessor( z + 1 );
				for ( int y = 0; y < ny; y++ )
					for ( int x = 0; x < nx; x++ )
					{
						final int label = ( int ) ip.getf( x, y );
						if ( label == 0 )
							continue;
						p[ 0 ] = x;
						p[ 1 ] = y;
						p[ 2 ] = z;
						pixelToWorld.apply( p, w );
						final double[] a = m.computeIfAbsent( label, k -> new double[ 10 ] );
						a[ 0 ]++;
						a[ 1 ] += w[ 0 ];
						a[ 2 ] += w[ 1 ];
						a[ 3 ] += w[ 2 ];
						a[ 4 ] += w[ 0 ] * w[ 0 ];
						a[ 5 ] += w[ 1 ] * w[ 1 ];
						a[ 6 ] += w[ 2 ] * w[ 2 ];
						a[ 7 ] += w[ 0 ] * w[ 1 ];
						a[ 8 ] += w[ 0 ] * w[ 2 ];
						a[ 9 ] += w[ 1 ] * w[ 2 ];
					}
			}
			// The smallest world step along each axis: a one-voxel extent must still give a shape of a voxel.
			final double[] voxel = new double[ 3 ];
			for ( int d = 0; d < 3; d++ )
			{
				final double[] u = new double[ 3 ], o = new double[ 3 ], wu = new double[ 3 ], wo = new double[ 3 ];
				u[ d ] = 1;
				pixelToWorld.apply( u, wu );
				pixelToWorld.apply( o, wo );
				double s = 0;
				for ( int i = 0; i < 3; i++ )
					s += ( wu[ i ] - wo[ i ] ) * ( wu[ i ] - wo[ i ] );
				voxel[ d ] = Math.sqrt( s );
			}
			final double k = nz > 1 ? 5.0 : 4.0;
			for ( final Map.Entry< Integer, double[] > le : new TreeMap<>( m ).entrySet() )
			{
				final double[] a = le.getValue();
				final double n = a[ 0 ];
				final double[] c = { a[ 1 ] / n, a[ 2 ] / n, a[ 3 ] / n };
				final double[][] cov = new double[ 3 ][ 3 ];
				cov[ 0 ][ 0 ] = a[ 4 ] / n - c[ 0 ] * c[ 0 ];
				cov[ 1 ][ 1 ] = a[ 5 ] / n - c[ 1 ] * c[ 1 ];
				cov[ 2 ][ 2 ] = a[ 6 ] / n - c[ 2 ] * c[ 2 ];
				cov[ 0 ][ 1 ] = cov[ 1 ][ 0 ] = a[ 7 ] / n - c[ 0 ] * c[ 1 ];
				cov[ 0 ][ 2 ] = cov[ 2 ][ 0 ] = a[ 8 ] / n - c[ 0 ] * c[ 2 ];
				cov[ 1 ][ 2 ] = cov[ 2 ][ 1 ] = a[ 9 ] / n - c[ 1 ] * c[ 2 ];
				for ( int i = 0; i < 3; i++ )
					for ( int j = 0; j < 3; j++ )
						cov[ i ][ j ] *= k;
				// A voxel's own extent: a pixel is a square, not a point (variance of a unit box is 1/12).
				for ( int d = 0; d < 3; d++ )
					cov[ d ][ d ] += k * voxel[ d ] * voxel[ d ] / 12.0;
				if ( nz == 1 )
				{
					cov[ 2 ][ 2 ] = Math.max( cov[ 0 ][ 0 ], cov[ 1 ][ 1 ] );
					cov[ 0 ][ 2 ] = cov[ 2 ][ 0 ] = cov[ 1 ][ 2 ] = cov[ 2 ][ 1 ] = 0;
				}
				final Spot s = graph.addVertex().init( t, c, cov );
				final int label = le.getKey();
				final Spot prev = last.get( label );
				if ( prev != null )
					graph.addEdge( prev, s ).init();
				else
				{
					first.put( label, s );
					final int[] tr = tracks.get( label );
					if ( tr != null && tr[ 2 ] != 0 && last.containsKey( tr[ 2 ] ) )
						graph.addEdge( last.get( tr[ 2 ] ), s ).init();
				}
				last.put( label, s );
			}
		}
		graph.releaseRef( ref );
		return new Read( model, dims, frames.firstKey(), frames.lastKey() );
	}

	public static void main( final String[] args ) throws IOException
	{
		final Path tra = Paths.get( args[ 0 ] ), out = Paths.get( args[ 1 ] );
		final AffineTransform3D t = new AffineTransform3D();
		t.set( Double.parseDouble( args[ 2 ] ), 0, 0, 0, 0, Double.parseDouble( args[ 3 ] ), 0, 0, 0, 0,
				args.length > 4 ? Double.parseDouble( args[ 4 ] ) : 1.0, 0 );
		final Read r = read( tra, t );
		final CtcExport.Result res = CtcExport.export( r.model, r.dims, t, r.tMin, r.tMax, out );
		System.out.println( "frames " + r.tMin + ".." + r.tMax + ", " + res );
	}
}
