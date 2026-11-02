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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mastodon.mamut.automahout.tool.EllipseFit;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Model;

import com.sun.net.httpserver.HttpServer;

/** {@link SamFit}: planes out, masks back as world ellipses, against a fake fit server. */
public class SamFitTest
{
	/** Voxels 0.5 x 0.5 x 2 um, offset (10, 20, 30). */
	private static final double[] AFFINE = { 0.5, 0, 0, 10, 0, 0.5, 0, 20, 0, 0, 2, 30 };

	@Test
	public void theInverseAffineTakesWorldBackToVoxels()
	{
		final double[] inv = SamFit.invert( AFFINE );
		assertEquals( 4.0, inv[ 0 ] * 12 + inv[ 3 ], 1e-12 ); // world x 12 -> voxel 4
		assertEquals( 2.0, inv[ 10 ] * 34 + inv[ 11 ], 1e-12 ); // world z 34 -> plane 2
	}

	@Test
	public void aMaskBecomesAWorldEllipseAtItsCentroid()
	{
		final Frame f = new Frame( 0, new long[] { 8, 8, 4 }, new float[ 256 ], AFFINE );
		// Pixel variance 4 along x, 1 along y: semi-axes 4 and 2 pixels, 2 and 1 um.
		final JSONObject fit = new JSONObject().put( "centroid", new JSONArray().put( 3.0 ).put( 5.0 ) )
				.put( "moments", new JSONArray().put( new JSONArray().put( 4.0 ).put( 0.0 ) ).put( new JSONArray().put( 0.0 ).put( 1.0 ) ) )
				.put( "area", 25 );
		final EllipseFit.Ellipse e = SamFit.toWorld( f, fit, 2, 1.5 );
		assertNotNull( e );
		assertEquals( 11.5, e.x, 1e-9 );
		assertEquals( 22.5, e.y, 1e-9 );
		assertEquals( 2.0, e.a, 1e-9 );
		assertEquals( 1.0, e.b, 1e-9 );
	}

	@Test
	public void aMaskOverTwiceTheDetectionsRadiusIsAClusterAndRefused()
	{
		final Frame f = new Frame( 0, new long[] { 8, 8, 4 }, new float[ 256 ], AFFINE );
		final JSONObject fit = new JSONObject().put( "centroid", new JSONArray().put( 3.0 ).put( 5.0 ) )
				.put( "moments", new JSONArray().put( new JSONArray().put( 64.0 ).put( 0.0 ) ).put( new JSONArray().put( 0.0 ).put( 64.0 ) ) );
		assertNull( SamFit.toWorld( f, fit, 2, 1.5 ) ); // 16 px = 8 um across, radius 1.5 um
	}

	@Test
	public void eachPlaneGoesOnceWithItsDetectionsAndANullFitStaysNull() throws Exception
	{
		final int[] posts = { 0 };
		final HttpServer http = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		http.createContext( "/fit", ex -> {
			final String path = ex.getRequestURI().getPath();
			String body;
			if ( path.equals( "/fit" ) )
				body = new JSONObject().put( "protocol", RemoteToolServer.PROTOCOL )
						.put( "fitters", new JSONArray().put( new JSONObject().put( "id", SamFit.ID ) ) ).toString();
			else
			{
				posts[ 0 ]++;
				final JSONObject req = new JSONObject( readAll( ex.getRequestBody() ) );
				final JSONArray pts = req.getJSONArray( "points" ), fits = new JSONArray();
				for ( int i = 0; i < pts.length(); i++ )
					fits.put( i == 1 ? JSONObject.NULL : new JSONObject().put( "centroid", pts.getJSONArray( i ) )
							.put( "moments", new JSONArray().put( new JSONArray().put( 1.0 ).put( 0.0 ) ).put( new JSONArray().put( 0.0 ).put( 1.0 ) ) ) );
				body = new JSONObject().put( "fits", fits ).toString();
			}
			final byte[] b = body.getBytes( StandardCharsets.UTF_8 );
			ex.sendResponseHeaders( 200, b.length );
			try (OutputStream os = ex.getResponseBody())
			{
				os.write( b );
			}
		} );
		http.start();
		final Function< ToolContext, Frame.Reader > saved = RemoteTool.readers;
		final String savedProp = System.getProperty( RemoteToolServer.PROPERTY_SERVERS );
		try
		{
			System.setProperty( RemoteToolServer.PROPERTY_SERVERS, "http://127.0.0.1:" + http.getAddress().getPort() );
			RemoteTool.readers = c -> t -> new Frame( t, new long[] { 8, 8, 4 }, new float[ 256 ], AFFINE );
			final ToolContext ctx = ToolContext.forReplay( new Model() );
			final RemoteToolServer server = SamFit.find( ctx );
			assertNotNull( server );
			// Two detections on plane 1 (z 32), one on plane 3 (z 36).
			final List< double[] > centres = Arrays.asList( new double[] { 11, 21, 32 }, new double[] { 12, 22, 32 },
					new double[] { 13, 23, 36 } );
			final EllipseFit.Ellipse[] out = SamFit.fit( ctx, server, 0, centres, new double[] { 2, 2, 2 } );
			assertEquals( 2, posts[ 0 ] );
			assertNotNull( out[ 0 ] );
			assertNull( out[ 1 ] );
			assertNotNull( out[ 2 ] );
			assertEquals( 13.0, out[ 2 ].x, 1e-9 );
			assertEquals( 23.0, out[ 2 ].y, 1e-9 );
		}
		finally
		{
			http.stop( 0 );
			RemoteTool.readers = saved;
			if ( savedProp == null )
				System.clearProperty( RemoteToolServer.PROPERTY_SERVERS );
			else
				System.setProperty( RemoteToolServer.PROPERTY_SERVERS, savedProp );
		}
	}

	private static String readAll( final java.io.InputStream in ) throws java.io.IOException
	{
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		final byte[] buf = new byte[ 8192 ];
		int n;
		while ( ( n = in.read( buf ) ) > 0 )
			out.write( buf, 0, n );
		return new String( out.toByteArray(), StandardCharsets.UTF_8 );
	}
}
