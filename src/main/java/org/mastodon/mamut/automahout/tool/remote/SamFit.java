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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mastodon.mamut.automahout.tool.EllipseFit;
import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * The shape of each detection from a SAM mask: a remote {@code sam.fit} fitter (examples/sam-fit) is given the
 * plane under each detection's centre with the centres as point prompts, and returns each mask's centroid and
 * second moments. Measured against silver truth on 2026-10-24 (IoU median / p10): HeLa 0.91 / 0.78 against
 * 0.79 / 0.65 for the shapes the contour fit gave; CE 0.84-0.90 against 0.35-0.71 (where the contour fit could
 * not run: a background of one grey value has no spread to set an edge level with); PhC-C2DL-PSC 0.71 / 0.51
 * against 0.67 / 0.50. The size follows the cell as it shrinks: 0.97-1.07x the truth throughout.
 *
 * <p>Optional: without a server offering {@code sam.fit} (configured as for remote tools, see
 * {@link RemoteToolServer#configured()}) {@link #find} is null and the caller fits as before.</p>
 */
public final class SamFit
{
	public static final String ID = "sam.fit";

	/** Background prompts per detection: its nearest other detections (PhC-C2DL-PSC 0.65 -> 0.71 IoU). */
	static final int NEGATIVES = 4;

	/** A fit larger than this many times the detection's own in-plane radius is a cluster, not its cell. */
	static final double MAX_GROWTH = 2.0;

	private static final String SHARED_SERVER = "automahout.samfit.server";

	private SamFit()
	{}

	/** The first configured server offering {@link #ID}, asked once per run; null when none does. */
	public static RemoteToolServer find( final ToolContext ctx )
	{
		final Object cached = ctx.getSharedData( SHARED_SERVER );
		if ( cached instanceof RemoteToolServer )
			return ( RemoteToolServer ) cached;
		if ( cached != null )
			return null;
		RemoteToolServer found = null;
		for ( final String url : RemoteToolServer.configured() )
		{
			try
			{
				final RemoteToolServer s = new RemoteToolServer( url );
				final JSONArray fitters = s.get( "/fit" ).optJSONArray( "fitters" );
				for ( int i = 0; fitters != null && i < fitters.length() && found == null; i++ )
					if ( ID.equals( fitters.getJSONObject( i ).optString( "id" ) ) )
						found = s;
			}
			catch ( final IOException | RuntimeException e )
			{
				// not a fit server, or not up: the next one, or none
			}
			if ( found != null )
				break;
		}
		ctx.setSharedData( SHARED_SERVER, found != null ? found : Boolean.FALSE );
		if ( found != null )
			ctx.log( "  Shape fit: SAM masks from " + found.baseUrl + " (the contour fit where SAM gives none)." );
		return found;
	}

	/**
	 * The SAM ellipse of each detection at {@code t}, in world units, or null where SAM gave no mask, a mask not
	 * under the detection's centre, or one over {@link #MAX_GROWTH} times its radius.
	 *
	 * @param centres world positions of the detections
	 * @param radii each detection's in-plane radius, world units
	 */
	public static EllipseFit.Ellipse[] fit( final ToolContext ctx, final RemoteToolServer server, final int t,
			final List< double[] > centres, final double[] radii ) throws IOException
	{
		final EllipseFit.Ellipse[] out = new EllipseFit.Ellipse[ centres.size() ];
		final Frame frame = RemoteTool.readers.apply( ctx ).read( t );
		if ( frame == null )
			return out;
		final double[] inv = invert( frame.voxelToWorld );
		final int nx = ( int ) frame.dims[ 0 ], ny = ( int ) frame.dims[ 1 ], nz = ( int ) frame.dims[ 2 ];
		// The detections on each plane, by the plane under their centre.
		final Map< Integer, List< Integer > > byPlane = new LinkedHashMap<>();
		final double[][] voxel = new double[ centres.size() ][];
		for ( int i = 0; i < centres.size(); i++ )
		{
			voxel[ i ] = apply( inv, centres.get( i ) );
			final int z = ( int ) Math.max( 0, Math.min( nz - 1, Math.round( voxel[ i ][ 2 ] ) ) );
			byPlane.computeIfAbsent( z, k -> new ArrayList<>() ).add( i );
		}
		for ( final Map.Entry< Integer, List< Integer > > e : byPlane.entrySet() )
		{
			final int z = e.getKey();
			final ByteBuffer buf = ByteBuffer.allocate( nx * ny * 4 ).order( ByteOrder.LITTLE_ENDIAN );
			buf.asFloatBuffer().put( frame.data, z * nx * ny, nx * ny );
			final JSONArray points = new JSONArray();
			for ( final int i : e.getValue() )
				points.put( new JSONArray().put( voxel[ i ][ 0 ] ).put( voxel[ i ][ 1 ] ) );
			final JSONObject body = new JSONObject()
					.put( "frame", new JSONObject().put( "t", t ).put( "shape", new JSONArray().put( ny ).put( nx ) )
							.put( "dtype", "float32" ).put( "data", Base64.getEncoder().encodeToString( buf.array() ) ) )
					.put( "points", points ).put( "negatives", NEGATIVES );
			final JSONArray fits = server.post( "/fit/" + ID + "/run", body ).getJSONArray( "fits" );
			for ( int k = 0; k < e.getValue().size() && k < fits.length(); k++ )
			{
				final int i = e.getValue().get( k );
				if ( fits.isNull( k ) )
					continue;
				out[ i ] = toWorld( frame, fits.getJSONObject( k ), z, radii[ i ] );
			}
		}
		return out;
	}

	/** One returned mask as a world ellipse; null when it is larger than {@link #MAX_GROWTH} allows. */
	static EllipseFit.Ellipse toWorld( final Frame frame, final JSONObject fit, final int z, final double radius )
	{
		final JSONArray c = fit.getJSONArray( "centroid" ), m = fit.getJSONArray( "moments" );
		final double[] centre = frame.toWorld( new double[] { c.getDouble( 0 ), c.getDouble( 1 ), z } );
		// A uniform ellipse's variance along an axis is a^2 / 4; Mastodon's covariance holds a^2.
		final double[][] px = { { 4 * m.getJSONArray( 0 ).getDouble( 0 ), 4 * m.getJSONArray( 0 ).getDouble( 1 ), 0 },
				{ 4 * m.getJSONArray( 1 ).getDouble( 0 ), 4 * m.getJSONArray( 1 ).getDouble( 1 ), 0 }, { 0, 0, 0 } };
		final double[][] w = frame.covarianceToWorld( px );
		final EllipseFit.Ellipse e = EllipseFit.Ellipse.ofCovariance( centre[ 0 ], centre[ 1 ], w[ 0 ][ 0 ], w[ 0 ][ 1 ],
				w[ 1 ][ 1 ], fit.optInt( "area", 1 ) );
		if ( !( e.b > 0 ) || ( radius > 0 && Math.sqrt( e.a * e.b ) > MAX_GROWTH * radius ) )
			return null;
		return e;
	}

	private static double[] apply( final double[] m, final double[] p )
	{
		return new double[] { m[ 0 ] * p[ 0 ] + m[ 1 ] * p[ 1 ] + m[ 2 ] * p[ 2 ] + m[ 3 ],
				m[ 4 ] * p[ 0 ] + m[ 5 ] * p[ 1 ] + m[ 6 ] * p[ 2 ] + m[ 7 ],
				m[ 8 ] * p[ 0 ] + m[ 9 ] * p[ 1 ] + m[ 10 ] * p[ 2 ] + m[ 11 ] };
	}

	/** The inverse of a row-packed 3x4 affine. */
	static double[] invert( final double[] a )
	{
		final double[][] l = { { a[ 0 ], a[ 1 ], a[ 2 ] }, { a[ 4 ], a[ 5 ], a[ 6 ] }, { a[ 8 ], a[ 9 ], a[ 10 ] } };
		final double det = l[ 0 ][ 0 ] * ( l[ 1 ][ 1 ] * l[ 2 ][ 2 ] - l[ 1 ][ 2 ] * l[ 2 ][ 1 ] )
				- l[ 0 ][ 1 ] * ( l[ 1 ][ 0 ] * l[ 2 ][ 2 ] - l[ 1 ][ 2 ] * l[ 2 ][ 0 ] )
				+ l[ 0 ][ 2 ] * ( l[ 1 ][ 0 ] * l[ 2 ][ 1 ] - l[ 1 ][ 1 ] * l[ 2 ][ 0 ] );
		final double[][] iv = new double[ 3 ][ 3 ];
		iv[ 0 ][ 0 ] = ( l[ 1 ][ 1 ] * l[ 2 ][ 2 ] - l[ 1 ][ 2 ] * l[ 2 ][ 1 ] ) / det;
		iv[ 0 ][ 1 ] = ( l[ 0 ][ 2 ] * l[ 2 ][ 1 ] - l[ 0 ][ 1 ] * l[ 2 ][ 2 ] ) / det;
		iv[ 0 ][ 2 ] = ( l[ 0 ][ 1 ] * l[ 1 ][ 2 ] - l[ 0 ][ 2 ] * l[ 1 ][ 1 ] ) / det;
		iv[ 1 ][ 0 ] = ( l[ 1 ][ 2 ] * l[ 2 ][ 0 ] - l[ 1 ][ 0 ] * l[ 2 ][ 2 ] ) / det;
		iv[ 1 ][ 1 ] = ( l[ 0 ][ 0 ] * l[ 2 ][ 2 ] - l[ 0 ][ 2 ] * l[ 2 ][ 0 ] ) / det;
		iv[ 1 ][ 2 ] = ( l[ 0 ][ 2 ] * l[ 1 ][ 0 ] - l[ 0 ][ 0 ] * l[ 1 ][ 2 ] ) / det;
		iv[ 2 ][ 0 ] = ( l[ 1 ][ 0 ] * l[ 2 ][ 1 ] - l[ 1 ][ 1 ] * l[ 2 ][ 0 ] ) / det;
		iv[ 2 ][ 1 ] = ( l[ 0 ][ 1 ] * l[ 2 ][ 0 ] - l[ 0 ][ 0 ] * l[ 2 ][ 1 ] ) / det;
		iv[ 2 ][ 2 ] = ( l[ 0 ][ 0 ] * l[ 1 ][ 1 ] - l[ 0 ][ 1 ] * l[ 1 ][ 0 ] ) / det;
		final double[] t = { a[ 3 ], a[ 7 ], a[ 11 ] };
		final double[] out = new double[ 12 ];
		for ( int r = 0; r < 3; r++ )
		{
			for ( int k = 0; k < 3; k++ )
				out[ 4 * r + k ] = iv[ r ][ k ];
			out[ 4 * r + 3 ] = -( iv[ r ][ 0 ] * t[ 0 ] + iv[ r ][ 1 ] * t[ 1 ] + iv[ r ][ 2 ] * t[ 2 ] );
		}
		return out;
	}
}
