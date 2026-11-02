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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.json.JSONObject;
import org.mastodon.mamut.automahout.vision.VlmBackend;

import bdv.viewer.Source;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

/**
 * The start of tracking as a person does it: look at the whole first frame, see how many nuclei there are and how
 * big, then mark them. A VLM is shown the frame's maximum projection (pixel grid, scale bar), z-planes and the
 * frames before and after, and says how many nuclei it sees and how large; a blob detector at that scale then finds
 * where they are. The seeds train the model before it predicts anything, instead of its own noise.
 *
 * <p>Measured on Fluo-N3DH-CE 01 (2026-10-24): the intensity seeds found nothing at t=0 (4 dim nuclei, 8.6 um
 * across) and the untrained model's noise was taught. Claude Haiku 4.5 said 5 nuclei of 12 um at t=0, 6 of 8.5 at
 * t=10, 12 of 8.5 at t=30 (truth 4, 7, 15); the blob detector at those scales found 4 of 4, 7 of 7 and 14 of 15
 * nuclei with no detection on background. Later frames it cannot count (47 against 348 at t=180) and its size does
 * not follow the nuclei (8.5 um throughout), so this is for the cold start only; later windows take their scale
 * from the run's own fitted detections.</p>
 *
 * <p>Fluorescence only: a blob is a bright spot on a darker background.</p>
 */
public final class VisualColdStart
{
	/** A blob is a seed when its filter response is this many MADs above the frame's median response. */
	static final double BLOB_MADS = 8.0;

	/** In-plane voxel the blob filter runs on, at most, as a fraction of the nucleus radius. */
	static final double BLOB_VOXEL_PER_RADIUS = 0.1;

	/** Frames before and after shown for context. */
	static final int NEIGHBOUR_OFFSET = 5;

	private VisualColdStart()
	{}

	/** What the VLM saw: how many nuclei, and their diameter in world units. */
	public static final class Understanding
	{
		public final int count;

		public final double diameter;

		public final String reasoning;

		Understanding( final int count, final double diameter, final String reasoning )
		{
			this.count = count;
			this.diameter = diameter;
			this.reasoning = reasoning;
		}
	}

	/**
	 * Asks the VLM what frame {@code t} holds; null when it gives no usable answer.
	 *
	 * @param lastT the movie's last timepoint (the neighbours shown stay within it)
	 */
	public static Understanding understand( final Source< ? > source, final int t, final int lastT, final VlmBackend backend,
			final String model ) throws IOException
	{
		final Frame f = Frame.read( source, t );
		if ( f == null )
			return null;
		final List< String > images = new ArrayList<>();
		final double scale = Math.min( 1.0, 712.0 / f.nx );
		images.add( png( projectionPanel( f, scale, "t=" + t, true ) ) );
		if ( f.nz > 1 )
			images.add( png( zStrip( f ) ) );
		final List< Integer > neighbours = new ArrayList<>();
		for ( final int dt : new int[] { -NEIGHBOUR_OFFSET, NEIGHBOUR_OFFSET } )
		{
			final Frame n = t + dt >= 0 && t + dt <= lastT ? Frame.read( source, t + dt ) : null;
			if ( n != null )
			{
				images.add( png( projectionPanel( n, 360.0 / n.nx, "t=" + ( t + dt ), false ) ) );
				neighbours.add( t + dt );
			}
		}
		final String answer = backend.analyze( model, prompt( f, t, neighbours ), images );
		return parse( answer );
	}

	/**
	 * Asks the VLM whether the doubtful spot at {@code pos} is a nucleus, as a person looks closer at one spot before
	 * annotating it: the spot in its own plane and projected over the depth around it, each with a circle of the cell
	 * radius, and the same views of {@code example}, a spot the model does find, for what a nucleus looks like in this
	 * data. {@code null} when the answer cannot be read.
	 */
	public static Boolean isNucleus( final Source< ? > source, final int t, final double[] pos, final double[] example,
			final double radius, final VlmBackend backend, final String model ) throws IOException
	{
		final Frame f = Frame.read( source, t );
		if ( f == null )
			return null;
		final List< String > images = new ArrayList<>();
		images.add( png( spotPanel( f, pos, radius, false ) ) );
		images.add( png( spotPanel( f, pos, radius, true ) ) );
		if ( example != null )
		{
			images.add( png( spotPanel( f, example, radius, false ) ) );
			images.add( png( spotPanel( f, example, radius, true ) ) );
		}
		final String answer = backend.analyze( model, nucleusPrompt( f, radius, example != null ), images );
		// -Dautomahout.vlm.dump=<dir>: the images and the answer, to see what the VLM was shown and said.
		final String dump = System.getProperty( "automahout.vlm.dump" );
		if ( dump != null )
		{
			final java.nio.file.Path dir = java.nio.file.Paths.get( dump );
			java.nio.file.Files.createDirectories( dir );
			final String stem = String.format( "t%d_%.1f_%.1f_%.1f", t, pos[ 0 ], pos[ 1 ], pos[ 2 ] );
			for ( int i = 0; i < images.size(); i++ )
				java.nio.file.Files.write( dir.resolve( stem + "_" + ( i + 1 ) + ".png" ), java.util.Base64.getDecoder().decode( images.get( i ) ) );
			java.nio.file.Files.write( dir.resolve( stem + ".txt" ), String.valueOf( answer ).getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
		}
		return parseNucleus( answer );
	}

	static String nucleusPrompt( final Frame f, final double radius, final boolean withExample )
	{
		return String.format( "This is a %s fluorescence image of cells, their nuclei labelled; voxels %.3g um in plane%s. "
				+ "A detector proposes the spot in the centre of images 1-2 as a cell nucleus (yellow circle: the typical "
				+ "nucleus radius here, %.2f um). Image 1: the plane through it; image 2: projection over the planes around "
				+ "it.%s Look as a microscopist would: is the spot a cell nucleus, or something else (debris, a polar body, "
				+ "a fragment of a neighbouring nucleus, noise)? Answer ONLY with JSON: {\"nucleus\": true or false, "
				+ "\"reasoning\": one sentence}", f.nz > 1 ? "3D" : "2D", f.vx,
				f.nz > 1 ? String.format( " and %.3g um between planes", f.vz ) : "", radius,
				withExample ? " Images 3-4: the same views of a spot that IS a nucleus in this image, for comparison." : "" );
	}

	static Boolean parseNucleus( final String answer )
	{
		if ( answer == null )
			return null;
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile( "\"nucleus\"\\s*:\\s*(true|false)" ).matcher( answer );
		return m.find() ? Boolean.valueOf( m.group( 1 ) ) : null;
	}

	/** A square of 8 radii around {@code w}, in its plane or projected over +-2 radii in z, upscaled, with a circle. */
	private static BufferedImage spotPanel( final Frame f, final double[] w, final double radius, final boolean projected )
	{
		final int[] v = f.toVoxel( w );
		final int half = Math.max( 4, ( int ) Math.round( 4 * radius / f.vx ) );
		final int dz = f.nz > 1 ? ( projected ? Math.max( 1, ( int ) Math.round( 2 * radius / f.vz ) ) : 0 ) : 0;
		final int n = 2 * half + 1;
		final float[] crop = new float[ n * n ];
		Arrays.fill( crop, Float.NEGATIVE_INFINITY );
		for ( int z = Math.max( 0, v[ 2 ] - dz ); z <= Math.min( f.nz - 1, v[ 2 ] + dz ); z++ )
		{
			final float[] plane = f.smoothedPlane( z, 1.0 );
			for ( int y = -half; y <= half; y++ )
				for ( int x = -half; x <= half; x++ )
				{
					final int xx = v[ 0 ] + x, yy = v[ 1 ] + y;
					final float val = xx < 0 || yy < 0 || xx >= f.nx || yy >= f.ny ? 0 : plane[ yy * f.nx + xx ];
					final int i = ( y + half ) * n + x + half;
					crop[ i ] = Math.max( crop[ i ], val );
				}
		}
		final BufferedImage src = grey( crop, n, n );
		final int size = 256;
		final BufferedImage img = new BufferedImage( size, size, BufferedImage.TYPE_INT_RGB );
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR );
		g.drawImage( src, 0, 0, size, size, null );
		final double px = size / ( double ) n * radius / f.vx;
		g.setColor( Color.YELLOW );
		g.drawOval( ( int ) Math.round( size / 2.0 - px ), ( int ) Math.round( size / 2.0 - px ), ( int ) Math.round( 2 * px ),
				( int ) Math.round( 2 * px ) );
		g.drawString( projected ? "projection" : "plane", 4, 14 );
		g.dispose();
		return img;
	}

	/** The question, with the data's own voxel size. */
	static String prompt( final Frame f, final int t, final List< Integer > neighbours )
	{
		final StringBuilder p = new StringBuilder();
		p.append( String.format( "This is a %s fluorescence image of cells, their nuclei labelled; voxels %.3g x %.3g um in plane%s. ",
				f.nz > 1 ? "3D" : "2D", f.vx, f.vx, f.nz > 1 ? String.format( " and %.3g um between planes", f.vz ) : "" ) );
		p.append( "Image 1: " ).append( f.nz > 1 ? "maximum-intensity projection over all z of " : "" )
				.append( "timepoint " ).append( t )
				.append( ", with a pixel grid (blue, pixel coordinates of this image) and a 10 um scale bar. " );
		int k = 2;
		if ( f.nz > 1 )
			p.append( "Image " ).append( k++ ).append( ": eight z-planes of the same timepoint. " );
		if ( !neighbours.isEmpty() )
			p.append( "Images " ).append( k ).append( "-" ).append( k + neighbours.size() - 1 )
					.append( ": the same view at timepoints " ).append( neighbours )
					.append( ", for context (the count changes slowly; cells divide). " );
		p.append( "Look at the whole " ).append( f.nz > 1 ? "stack" : "image" ).append( " as a microscopist would" )
				.append( f.nz > 1 ? "; nuclei stacked in z overlap in the projection, so use the z-planes to tell them apart" : "" )
				.append( ". Answer ONLY with JSON: {\"count\": number of nuclei in image 1, \"diameter_um\": typical "
						+ "nucleus diameter in um, \"reasoning\": one sentence}" );
		return p.toString();
	}

	private static final Pattern JSON = Pattern.compile( "\\{.*\\}", Pattern.DOTALL );

	/** The answer's JSON as an understanding; null without a count and a positive diameter. */
	static Understanding parse( final String answer )
	{
		if ( answer == null )
			return null;
		final Matcher m = JSON.matcher( answer );
		if ( !m.find() )
			return null;
		try
		{
			final JSONObject o = new JSONObject( m.group() );
			final int count = o.optInt( "count", -1 );
			final double d = o.optDouble( "diameter_um", Double.NaN );
			if ( count < 1 || !( d > 0 ) )
				return null;
			return new Understanding( count, d, o.optString( "reasoning", "" ) );
		}
		catch ( final RuntimeException e )
		{
			return null;
		}
	}

	/**
	 * The bright blobs of frame {@code t} at {@code radius} (world units): scale-normalised Laplacian of Gaussian
	 * (sigma = radius / sqrt(d)), maxima within one radius, above {@link #BLOB_MADS} MADs of the frame's
	 * response. Strongest first; {x, y, z} in world coordinates.
	 */
	public static List< double[] > blobs( final Source< ? > source, final int t, final double radius )
	{
		return blobs( Frame.read( source, t ), radius );
	}

	static List< double[] > blobs( final Frame f, final double radius )
	{
		if ( f == null || !( radius > 0 ) )
			return new ArrayList<>();
		final Response s = response( f, radius );
		final List< double[] > seeds = new ArrayList<>();
		for ( final double[] p : s.maxima() )
			seeds.add( new double[] { p[ 1 ], p[ 2 ], p[ 3 ] } );
		return seeds;
	}

	/** {@link #blobs} with each blob's scale-normalised LoG response first: {response, x, y, z}, strongest first. */
	public static List< double[] > blobResponses( final Source< ? > source, final int t, final double radius )
	{
		final Frame f = Frame.read( source, t );
		if ( f == null || !( radius > 0 ) )
			return new ArrayList<>();
		return response( f, radius ).maxima();
	}

	/** Below this share of the frame's median blob response a blob is too faint to stand as a reference cell. */
	public static final double CONFIDENT_SHARE = 0.5;

	/**
	 * The blobs of frame {@code t} a person would annotate without hesitation: those whose LoG response is at least
	 * {@link #CONFIDENT_SHARE} of the median blob's in the same frame. Relative to the frame, so it carries across
	 * intensity scales; it assumes bright blobs on dark, as {@link #blobs} does (fluorescence).
	 * <p>Measured on Fluo-N3DH-CE 01 (frame-confirmed blobs against TRA truth): at t=0..19 the share of blobs on no
	 * cell falls from 18.3% to 3.0% for 5% of the blobs on cells; at t=60, 100 and 180 it keeps 98%, 93% and 99% of
	 * those on cells, and the few on no cell there are as bright as cells (it does not remove them).</p>
	 */
	public static List< double[] > confidentBlobs( final Source< ? > source, final int t, final double radius )
	{
		return confident( blobResponses( source, t, radius ) );
	}

	/** {x, y, z} of the responses {response, x, y, z} at or above {@link #CONFIDENT_SHARE} of their median. */
	static List< double[] > confident( final List< double[] > responses )
	{
		final List< double[] > out = new ArrayList<>();
		if ( responses.isEmpty() )
			return out;
		final double[] v = responses.stream().mapToDouble( r -> r[ 0 ] ).sorted().toArray();
		final double bar = CONFIDENT_SHARE * v[ v.length / 2 ];
		for ( final double[] r : responses )
			if ( r[ 0 ] >= bar )
				out.add( new double[] { r[ 1 ], r[ 2 ], r[ 3 ] } );
		return out;
	}

	/**
	 * The blobs at {@code t} that a neighbouring frame confirms, as a person looks at the frames before and after to be
	 * sure a spot is a nucleus: kept when a blob at the same radius lies within one radius of it at {@code t - 1} or
	 * {@code t + 1} (whichever exist). A nucleus is still there a frame later, a little moved; noise mostly is not.
	 */
	public static List< double[] > persistentBlobs( final Source< ? > source, final int t, final double radius )
	{
		final List< double[] > here = blobs( source, t, radius );
		final List< List< double[] > > around = new ArrayList<>();
		for ( final int u : new int[] { t - 1, t + 1 } )
			if ( u >= 0 && source.isPresent( u ) )
				around.add( blobs( source, u, radius ) );
		if ( around.isEmpty() )
			return here;
		final List< double[] > kept = new ArrayList<>();
		for ( final double[] b : here )
			for ( final List< double[] > other : around )
				if ( FrameReview.nearestDistance( b, other ) <= radius )
				{
					kept.add( b );
					break;
				}
		return kept;
	}

	/** The scale-normalised LoG response of a frame at one radius, on the grid binned for that radius. */
	static final class Response
	{
		final Frame c;

		final float[] lap;

		final double radius, bar;

		Response( final Frame c, final float[] lap, final double radius, final double bar )
		{
			this.c = c;
			this.lap = lap;
			this.radius = radius;
			this.bar = bar;
		}

		/** Local maxima within one radius above the bar, strongest first: {response, x, y, z} (world). */
		List< double[] > maxima()
		{
			final int hx = Math.max( 1, ( int ) Math.round( radius / c.vx ) ), hz = c.nz > 1 ? 1 : 0;
			final List< double[] > out = new ArrayList<>();
			for ( int z = 0; z < c.nz; z++ )
				for ( int y = 0; y < c.ny; y++ )
					for ( int x = 0; x < c.nx; x++ )
					{
						final float v = lap[ c.index( x, y, z ) ];
						if ( !( v > bar ) )
							continue;
						boolean peak = true;
						for ( int dz = -hz; dz <= hz && peak; dz++ )
							for ( int dy = -hx; dy <= hx && peak; dy++ )
								for ( int dx = -hx; dx <= hx; dx++ )
								{
									final int xx = x + dx, yy = y + dy, zz = z + dz;
									if ( xx < 0 || yy < 0 || zz < 0 || xx >= c.nx || yy >= c.ny || zz >= c.nz || ( dx == 0 && dy == 0 && dz == 0 ) )
										continue;
									if ( lap[ c.index( xx, yy, zz ) ] > v )
									{
										peak = false;
										break;
									}
								}
						if ( peak )
						{
							final double[] w = c.toWorld( x, y, z );
							out.add( new double[] { v, w[ 0 ], w[ 1 ], w[ 2 ] } );
						}
					}
			out.sort( ( p, q ) -> Double.compare( q[ 0 ], p[ 0 ] ) );
			return out;
		}

		/** The strongest response within half a radius of world position {@code w}. */
		double peakNear( final double[] w )
		{
			final int[] v = c.toVoxel( w );
			final int hx = Math.max( 0, ( int ) Math.round( 0.5 * radius / c.vx ) ), hz = c.nz > 1 ? Math.max( 0, ( int ) Math.round( 0.5 * radius / c.vz ) ) : 0;
			double best = Double.NEGATIVE_INFINITY;
			for ( int z = Math.max( 0, v[ 2 ] - hz ); z <= Math.min( c.nz - 1, v[ 2 ] + hz ); z++ )
				for ( int y = Math.max( 0, v[ 1 ] - hx ); y <= Math.min( c.ny - 1, v[ 1 ] + hx ); y++ )
					for ( int x = Math.max( 0, v[ 0 ] - hx ); x <= Math.min( c.nx - 1, v[ 0 ] + hx ); x++ )
						best = Math.max( best, lap[ c.index( x, y, z ) ] );
			return best;
		}
	}

	/** Radii, in world units, between which {@link #cellRadius} looks: a factor of 1.25 apart. */
	public static double[] scaleLadder( final double min, final double max )
	{
		final List< Double > l = new ArrayList<>();
		for ( double r = min; r <= max * 1.0001; r *= 1.25 )
			l.add( r );
		final double[] out = new double[ l.size() ];
		for ( int i = 0; i < out.length; i++ )
			out[ i ] = l.get( i );
		return out;
	}

	/**
	 * The frame's typical cell radius by scale selection, without a VLM: the scale-normalised LoG response at each
	 * radius of {@code radii}; the blobs at every radius, pooled (one per cell, the strongest wins within a radius);
	 * each blob's radius is the one at which its response peaks; the median of those. NaN when there are no blobs.
	 *
	 * @return {median radius, blobs counted}
	 */
	public static double[] cellRadius( final Source< ? > source, final int t, final double[] radii )
	{
		return cellRadius( Frame.read( source, t ), radii );
	}

	static double[] cellRadius( final Frame f, final double[] radii )
	{
		if ( f == null || radii.length == 0 )
			return new double[] { Double.NaN, 0 };
		final Response[] rs = new Response[ radii.length ];
		for ( int k = 0; k < radii.length; k++ )
			rs[ k ] = response( f, radii[ k ] );
		// Every scale's blobs, strongest first by their own scale's response over its bar, one per place.
		final List< double[] > pool = new ArrayList<>(); // {strength, x, y, z, k}
		for ( int k = 0; k < radii.length; k++ )
			for ( final double[] m : rs[ k ].maxima() )
				pool.add( new double[] { m[ 0 ] / Math.max( rs[ k ].bar, 1e-9 ), m[ 1 ], m[ 2 ], m[ 3 ], k } );
		pool.sort( ( p, q ) -> Double.compare( q[ 0 ], p[ 0 ] ) );
		final List< double[] > kept = new ArrayList<>();
		final List< Double > sizes = new ArrayList<>();
		for ( final double[] p : pool )
		{
			final double[] w = { p[ 1 ], p[ 2 ], p[ 3 ] };
			final double own = radii[ ( int ) p[ 4 ] ];
			boolean taken = false;
			for ( final double[] q : kept )
				if ( Math.sqrt( Math.pow( q[ 0 ] - w[ 0 ], 2 ) + Math.pow( q[ 1 ] - w[ 1 ], 2 ) + Math.pow( q[ 2 ] - w[ 2 ], 2 ) ) < Math.min( own, q[ 3 ] ) )
				{
					taken = true;
					break;
				}
			if ( taken )
				continue;
			int best = 0;
			double bestV = Double.NEGATIVE_INFINITY;
			for ( int k = 0; k < radii.length; k++ )
			{
				final double v = rs[ k ].peakNear( w );
				if ( v > bestV )
				{
					bestV = v;
					best = k;
				}
			}
			kept.add( new double[] { w[ 0 ], w[ 1 ], w[ 2 ], radii[ best ] } );
			sizes.add( radii[ best ] );
		}
		if ( sizes.isEmpty() )
			return new double[] { Double.NaN, 0 };
		java.util.Collections.sort( sizes );
		return new double[] { sizes.get( sizes.size() / 2 ), sizes.size() };
	}

	static Response response( final Frame f, final double radius )
	{
		final int b = Math.max( 1, ( int ) Math.floor( BLOB_VOXEL_PER_RADIUS * radius / f.vx ) );
		final Frame c = f.binned( b );
		final double sx = radius / Math.sqrt( c.nz > 1 ? 3 : 2 ) / c.vx;
		final double sz = c.nz > 1 ? radius / Math.sqrt( 3 ) / c.vz : 0;
		final float[] g = new float[ c.data.length ];
		Gauss3.gauss( c.nz > 1 ? new double[] { sx, sx, sz } : new double[] { sx, sx },
				Views.extendMirrorSingle( c.nz > 1 ? ArrayImgs.floats( c.data, c.nx, c.ny, c.nz ) : ArrayImgs.floats( c.data, c.nx, c.ny ) ),
				c.nz > 1 ? ArrayImgs.floats( g, c.nx, c.ny, c.nz ) : ArrayImgs.floats( g, c.nx, c.ny ) );
		// -r^2 times the Laplacian: positive on a bright blob.
		final float[] lap = new float[ g.length ];
		for ( int z = 0; z < c.nz; z++ )
			for ( int y = 0; y < c.ny; y++ )
				for ( int x = 0; x < c.nx; x++ )
				{
					final int i = c.index( x, y, z );
					double l = ( g[ c.index( Math.max( 0, x - 1 ), y, z ) ] - 2 * g[ i ] + g[ c.index( Math.min( c.nx - 1, x + 1 ), y, z ) ] ) / ( c.vx * c.vx )
							+ ( g[ c.index( x, Math.max( 0, y - 1 ), z ) ] - 2 * g[ i ] + g[ c.index( x, Math.min( c.ny - 1, y + 1 ), z ) ] ) / ( c.vx * c.vx );
					if ( c.nz > 1 )
						l += ( g[ c.index( x, y, Math.max( 0, z - 1 ) ) ] - 2 * g[ i ] + g[ c.index( x, y, Math.min( c.nz - 1, z + 1 ) ) ] ) / ( c.vz * c.vz );
					lap[ i ] = ( float ) ( -radius * radius * l );
				}
		final float[] sorted = lap.clone();
		Arrays.sort( sorted );
		final double med = sorted[ sorted.length / 2 ];
		final float[] dev = new float[ lap.length ];
		for ( int i = 0; i < lap.length; i++ )
			dev[ i ] = ( float ) Math.abs( lap[ i ] - med );
		Arrays.sort( dev );
		final double mad = 1.4826 * dev[ dev.length / 2 ];
		final double bar = med + BLOB_MADS * mad;
		return new Response( c, lap, radius, bar );
	}

	// ---- images ----

	private static BufferedImage projectionPanel( final Frame f, final double scale, final String title, final boolean grid )
	{
		final float[] mip = f.smoothedProjection( 2.0 );
		final BufferedImage src = grey( mip, f.nx, f.ny );
		final int w = ( int ) Math.round( f.nx * scale ), h = ( int ) Math.round( f.ny * scale );
		final BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR );
		g.drawImage( src, 0, 0, w, h, null );
		if ( grid )
		{
			g.setColor( new Color( 60, 60, 140 ) );
			for ( int x = 0; x < w; x += 100 )
				g.drawLine( x, 0, x, h );
			for ( int y = 0; y < h; y += 100 )
				g.drawLine( 0, y, w, y );
			g.setColor( new Color( 150, 150, 255 ) );
			for ( int x = 0; x < w; x += 100 )
				g.drawString( String.valueOf( x ), x + 2, 12 );
			for ( int y = 100; y < h; y += 100 )
				g.drawString( String.valueOf( y ), 2, y + 12 );
			final int bar = ( int ) Math.round( 10 / f.vx * scale );
			g.setColor( Color.YELLOW );
			g.setStroke( new BasicStroke( 3 ) );
			g.drawLine( 10, h - 15, 10 + bar, h - 15 );
			g.drawString( "10 um", 10, h - 22 );
		}
		g.setColor( Color.YELLOW );
		g.drawString( title, w - 80, 14 );
		g.dispose();
		return img;
	}

	private static BufferedImage zStrip( final Frame f )
	{
		final BufferedImage out = new BufferedImage( 178 * 4, 128 * 2, BufferedImage.TYPE_INT_RGB );
		final Graphics2D g = out.createGraphics();
		g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR );
		for ( int k = 0; k < 8; k++ )
		{
			final int z = ( int ) Math.round( k * ( f.nz - 1 ) / 7.0 );
			g.drawImage( grey( f.smoothedPlane( z, 2.0 ), f.nx, f.ny ), ( k % 4 ) * 178, ( k / 4 ) * 128, 178, 128, null );
			g.setColor( Color.YELLOW );
			g.drawString( "z=" + z, ( k % 4 ) * 178 + 3, ( k / 4 ) * 128 + 13 );
		}
		g.dispose();
		return out;
	}

	/** Grey levels between the 1st and 99.9th percentile. */
	private static BufferedImage grey( final float[] v, final int nx, final int ny )
	{
		final float[] s = v.clone();
		Arrays.sort( s );
		final double lo = s[ ( int ) ( 0.01 * ( s.length - 1 ) ) ], hi = s[ ( int ) ( 0.999 * ( s.length - 1 ) ) ];
		final BufferedImage img = new BufferedImage( nx, ny, BufferedImage.TYPE_BYTE_GRAY );
		for ( int y = 0; y < ny; y++ )
			for ( int x = 0; x < nx; x++ )
			{
				final double n = ( v[ y * nx + x ] - lo ) / Math.max( hi - lo, 1e-6 );
				final int b = ( int ) Math.round( 255 * Math.max( 0, Math.min( 1, n ) ) );
				img.getRaster().setSample( x, y, 0, b );
			}
		return img;
	}

	private static String png( final BufferedImage img ) throws IOException
	{
		final ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write( img, "png", bos );
		return Base64.getEncoder().encodeToString( bos.toByteArray() );
	}

	// ---- one frame's voxels ----

	/** A frame at full resolution: x fastest, then y, then z; with its voxel sizes and transform. */
	static final class Frame
	{
		final float[] data;

		final int nx, ny, nz;

		final double vx, vz;

		final AffineTransform3D toWorld;

		Frame( final float[] data, final int nx, final int ny, final int nz, final double vx, final double vz,
				final AffineTransform3D toWorld )
		{
			this.data = data;
			this.nx = nx;
			this.ny = ny;
			this.nz = nz;
			this.vx = vx;
			this.vz = vz;
			this.toWorld = toWorld;
		}

		@SuppressWarnings( { "unchecked", "rawtypes" } )
		static Frame read( final Source< ? > source, final int t )
		{
			if ( source == null || !source.isPresent( t ) )
				return null;
			final AffineTransform3D tr = new AffineTransform3D();
			source.getSourceTransform( t, 0, tr );
			final RandomAccessibleInterval< ? extends RealType< ? > > rai = ( RandomAccessibleInterval ) source.getSource( t, 0 );
			final int nx = ( int ) rai.dimension( 0 ), ny = ( int ) rai.dimension( 1 ),
					nz = rai.numDimensions() > 2 ? ( int ) rai.dimension( 2 ) : 1;
			final float[] d = new float[ nx * ny * nz ];
			final Cursor< ? extends RealType< ? > > c = Views.flatIterable( rai ).cursor();
			int i = 0;
			while ( c.hasNext() )
				d[ i++ ] = c.next().getRealFloat();
			final double vx = Math.sqrt( tr.get( 0, 0 ) * tr.get( 0, 0 ) + tr.get( 1, 0 ) * tr.get( 1, 0 ) + tr.get( 2, 0 ) * tr.get( 2, 0 ) );
			final double vz = Math.sqrt( tr.get( 0, 2 ) * tr.get( 0, 2 ) + tr.get( 1, 2 ) * tr.get( 1, 2 ) + tr.get( 2, 2 ) * tr.get( 2, 2 ) );
			final AffineTransform3D shifted = tr.copy();
			shifted.concatenate( new AffineTransform3D()
			{
				{
					set( 1, 0, 0, rai.min( 0 ), 0, 1, 0, rai.min( 1 ), 0, 0, 1, rai.numDimensions() > 2 ? rai.min( 2 ) : 0 );
				}
			} );
			return new Frame( d, nx, ny, nz, vx, vz, shifted );
		}

		int index( final int x, final int y, final int z )
		{
			return ( z * ny + y ) * nx + x;
		}

		/** Averaged over {@code b x b} blocks in plane (an edge block over what it has). */
		Frame binned( final int b )
		{
			if ( b <= 1 )
				return this;
			final int mx = ( nx + b - 1 ) / b, my = ( ny + b - 1 ) / b;
			final float[] out = new float[ mx * my * nz ];
			final int[] n = new int[ out.length ];
			for ( int z = 0; z < nz; z++ )
				for ( int y = 0; y < ny; y++ )
					for ( int x = 0; x < nx; x++ )
					{
						final int j = ( z * my + y / b ) * mx + x / b;
						out[ j ] += data[ index( x, y, z ) ];
						n[ j ]++;
					}
			for ( int j = 0; j < out.length; j++ )
				out[ j ] /= n[ j ];
			final AffineTransform3D t = toWorld.copy();
			// voxel (X, Y) of the binned frame sits at the centre of its block: b X + (b - 1) / 2 of this one
			t.concatenate( new AffineTransform3D()
			{
				{
					set( b, 0, 0, ( b - 1 ) / 2.0, 0, b, 0, ( b - 1 ) / 2.0, 0, 0, 1, 0 );
				}
			} );
			return new Frame( out, mx, my, nz, vx * b, vz, t );
		}

		double[] toWorld( final double x, final double y, final double z )
		{
			final double[] w = new double[ 3 ];
			toWorld.apply( new double[] { x, y, z }, w );
			return w;
		}

		/** The nearest voxel to world position {@code w}, clamped into the frame. */
		int[] toVoxel( final double[] w )
		{
			final double[] v = new double[ 3 ];
			toWorld.applyInverse( v, w );
			return new int[] { clamp( v[ 0 ], nx ), clamp( v[ 1 ], ny ), clamp( v[ 2 ], nz ) };
		}

		private static int clamp( final double v, final int n )
		{
			return Math.max( 0, Math.min( n - 1, ( int ) Math.round( v ) ) );
		}

		float[] smoothedPlane( final int z, final double sigma )
		{
			final float[] plane = Arrays.copyOfRange( data, z * nx * ny, ( z + 1 ) * nx * ny );
			final float[] out = new float[ plane.length ];
			Gauss3.gauss( new double[] { sigma, sigma }, Views.extendMirrorSingle( ArrayImgs.floats( plane, nx, ny ) ),
					ArrayImgs.floats( out, nx, ny ) );
			return out;
		}

		float[] smoothedProjection( final double sigma )
		{
			final float[] mip = new float[ nx * ny ];
			Arrays.fill( mip, Float.NEGATIVE_INFINITY );
			for ( int z = 0; z < nz; z++ )
			{
				final float[] p = smoothedPlane( z, sigma );
				for ( int i = 0; i < mip.length; i++ )
					mip[ i ] = Math.max( mip[ i ], p[ i ] );
			}
			return mip;
		}
	}
}
