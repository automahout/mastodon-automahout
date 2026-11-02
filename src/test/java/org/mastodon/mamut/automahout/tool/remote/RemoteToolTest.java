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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.DataProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.ToolRegistry;
import org.mastodon.mamut.automahout.tool.ToolRole;
import org.mastodon.mamut.model.Spot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The remote adapter against an in-process server speaking automahout-remote/1: what it offers, when
 * it applies, and that its pixel detections land at the right world positions.
 */
public class RemoteToolTest
{
	private HttpServer server;

	private String url;

	private final List< JSONObject > requests = new ArrayList<>();

	private Function< ToolContext, Frame.Reader > savedReaders;

	private static final String MANIFEST = "{\"protocol\":\"automahout-remote/1\",\"tools\":["
			+ "{\"id\":\"fake.detect\",\"description\":\"two blobs\",\"role\":\"DETECT\","
			+ "\"parameters\":[{\"name\":\"threshold\",\"type\":\"NUMBER\",\"description\":\"cut\",\"default\":2.5,\"min\":0,\"max\":10}],"
			+ "\"applicability\":{\"requires\":{\"fluorescenceLike\":true,\"volumetric\":false},\"score\":0.8,\"reason\":\"fluorescent nuclei\"}},"
			+ "{\"id\":\"nav\",\"description\":\"tries to take a built-in id\",\"role\":\"VIEW\"}]}";

	/** Pixels (x=10, y=20) and (x=30, y=5), one with a covariance, one with a radius. */
	private static final String DETECTIONS = "{\"detections\":["
			+ "{\"centroid\":[10,20],\"covariance\":[[4,0],[0,9]]},"
			+ "{\"centroid\":[30,5],\"radius\":2}],\"message\":\"two\"}";

	@Before
	public void start() throws IOException
	{
		server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		server.createContext( "/tools", ex -> {
			if ( "GET".equals( ex.getRequestMethod() ) )
				reply( ex, 200, MANIFEST );
			else
			{
				final byte[] body = readAll( ex );
				requests.add( new JSONObject( new String( body, StandardCharsets.UTF_8 ) ) );
				if ( ex.getRequestURI().getPath().equals( "/tools/fake.detect/run" ) )
					reply( ex, 200, DETECTIONS );
				else
					reply( ex, 404, "{\"error\":\"no such tool\"}" );
			}
		} );
		server.start();
		url = "http://127.0.0.1:" + server.getAddress().getPort();
		savedReaders = RemoteTool.readers;
		// A 40 x 30 plane, 0.645 um pixels, the image's corner at world (100, 200).
		RemoteTool.readers = ctx -> t -> new Frame( t, new long[] { 40, 30, 1 }, new float[ 40 * 30 ],
				new double[] { 0.645, 0, 0, 100, 0, 0.645, 0, 200, 0, 0, 1, 0 } );
	}

	@After
	public void stop()
	{
		RemoteTool.readers = savedReaders;
		server.stop( 0 );
	}

	private static byte[] readAll( final HttpExchange ex ) throws IOException
	{
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		final byte[] buf = new byte[ 8192 ];
		int n;
		while ( ( n = ex.getRequestBody().read( buf ) ) > 0 )
			out.write( buf, 0, n );
		return out.toByteArray();
	}

	private static void reply( final HttpExchange ex, final int status, final String body ) throws IOException
	{
		final byte[] b = body.getBytes( StandardCharsets.UTF_8 );
		ex.sendResponseHeaders( status, b.length );
		try (OutputStream os = ex.getResponseBody())
		{
			os.write( b );
		}
	}

	private RemoteTool fake( final List< String > log )
	{
		for ( final RemoteTool t : new RemoteToolServer( url ).tools( log::add ) )
			if ( t.getId().equals( "fake.detect" ) )
				return t;
		throw new AssertionError( "not offered; log " + log );
	}

	@Test
	public void theManifestBecomesAToolWithItsContract()
	{
		final RemoteTool t = fake( new ArrayList<>() );
		assertEquals( ToolRole.DETECT, t.getRole() );
		assertEquals( "threshold", t.getParameters().get( 0 ).name );
		assertEquals( 2.5, ( Double ) t.getParameters().get( 0 ).defaultValue, 0 );
		assertTrue( t.getDescription().contains( url ) );
	}

	@Test
	public void applicabilityFollowsTheServersConditions()
	{
		final RemoteTool t = fake( new ArrayList<>() );
		assertTrue( t.applicability( DataProfile.of( false, 10, true, 5, Double.NaN ) ).applies );
		assertFalse( "brightfield", t.applicability( DataProfile.of( false, 10, false, 5, Double.NaN ) ).applies );
		assertFalse( "3D", t.applicability( DataProfile.of( true, 10, true, 5, Double.NaN ) ).applies );
		final org.mastodon.mamut.automahout.tool.Applicability unknown =
				t.applicability( DataProfile.of( false, 10, null, Double.NaN, Double.NaN ) );
		assertTrue( "unknown modality does not exclude it", unknown.applies );
		assertEquals( 0.4, unknown.score, 1e-9 );
		assertTrue( unknown.reason, unknown.reason.contains( "not measured yet" ) );
	}

	@Test
	public void detectionsLandAtTheirWorldPositions()
	{
		final ReplayGraph g = new ReplayGraph();
		final ToolContext ctx = g.context();
		final Map< String, Object > params = new HashMap<>();
		params.put( RemoteTool.PARAM_T_START, 3 );
		params.put( RemoteTool.PARAM_T_END, 4 );
		final String result = fake( new ArrayList<>() ).execute( ctx, params );
		assertTrue( result, result.contains( "4 detection(s) added over 2 frame(s)" ) );
		final Map< String, double[] > at = new HashMap<>();
		final double[][] c = new double[ 3 ][ 3 ];
		for ( final Spot s : g.graph().vertices() )
			if ( s.getTimepoint() == 3 )
			{
				s.getCovariance( c );
				at.put( String.format( "%.3f,%.3f", s.getDoublePosition( 0 ), s.getDoublePosition( 1 ) ),
						new double[] { c[ 0 ][ 0 ], c[ 1 ][ 1 ] } );
			}
		// pixel (10, 20) -> world (100 + 6.45, 200 + 12.9); covariance 4, 9 px² -> x 0.645², y ...
		final double[] first = at.get( "106.450,212.900" );
		assertTrue( at.keySet().toString(), first != null );
		assertEquals( 4 * 0.645 * 0.645, first[ 0 ], 1e-9 );
		assertEquals( 9 * 0.645 * 0.645, first[ 1 ], 1e-9 );
		assertTrue( at.keySet().toString(), at.containsKey( "119.350,203.225" ) );
		// The default parameter went to the server, with the frame as declared.
		final JSONObject req = requests.get( 0 );
		assertEquals( 2.5, req.getJSONObject( "params" ).getDouble( "threshold" ), 0 );
		assertEquals( "[30,40]", req.getJSONObject( "frame" ).getJSONArray( "shape" ).toString() );
	}

	@Test
	public void aRemoteToolNeverTakesAnIdAlreadyRegistered()
	{
		final ToolRegistry r = ToolRegistry.createDefault();
		final AutomahoutTool builtInNav = r.get( "nav" ).get();
		final List< String > log = new ArrayList<>();
		r.addRemote( Collections.singletonList( url ), log::add );
		assertTrue( r.get( "fake.detect" ).isPresent() );
		assertEquals( builtInNav, r.get( "nav" ).get() );
		assertTrue( log.toString(), log.stream().anyMatch( l -> l.contains( "'nav'" ) && l.contains( "left out" ) ) );
	}

	@Test
	public void aServerThatDoesNotAnswerIsSkippedAndSaysSo()
	{
		final ToolRegistry r = ToolRegistry.createDefault();
		final int before = r.getAll().size();
		final List< String > log = new ArrayList<>();
		r.addRemote( Arrays.asList( "http://127.0.0.1:1" ), log::add );
		assertEquals( before, r.getAll().size() );
		assertTrue( log.toString(), log.stream().anyMatch( l -> l.contains( "did not answer" ) ) );
	}

	/** The second moments of a filled disk of radius 10 px, measured on its pixels. */
	private static double diskMoment()
	{
		double sum = 0;
		int n = 0;
		for ( int y = -10; y <= 10; y++ )
			for ( int x = -10; x <= 10; x++ )
				if ( x * x + y * y <= 100 )
				{
					sum += x * x;
					n++;
				}
		return sum / n;
	}

	@Test
	public void aMasksMomentsBecomeTheSpotThatFillsIt()
	{
		// The user saw StarDist's spots at about half their size: the moments had been taken for
		// Mastodon's shape matrix. A disk of radius 10 must come back as a spot of radius 10.
		final double m = diskMoment();
		final List< FrameDetector.Detection > d = RemoteTool.parse( new org.json.JSONArray(
				"[{\"centroid\":[0,0],\"moments\":[[" + m + ",0],[0," + m + "]]}]" ) );
		assertEquals( 10, Math.sqrt( d.get( 0 ).covariance[ 0 ][ 0 ] ), 0.3 );
		assertEquals( 10, Math.sqrt( d.get( 0 ).covariance[ 1 ][ 1 ] ), 0.3 );
	}

	@Test
	public void aShapeMatrixIsTakenAsItIsAndAStacksMomentsByFive()
	{
		final List< FrameDetector.Detection > d = RemoteTool.parse( new org.json.JSONArray(
				"[{\"centroid\":[0,0],\"covariance\":[[25,0],[0,16]]},"
						+ "{\"centroid\":[0,0,0],\"moments\":[[1,0,0],[0,2,0],[0,0,3]]}]" ) );
		assertEquals( 25, d.get( 0 ).covariance[ 0 ][ 0 ], 0 );
		assertEquals( 16, d.get( 0 ).covariance[ 1 ][ 1 ], 0 );
		assertEquals( 15, d.get( 1 ).covariance[ 2 ][ 2 ], 1e-12 );
	}

	/**
	 * The frame carries its voxel size and, when the pre-flight measured one, the cell radius: a 3D Cellpose needs
	 * a diameter its own size model cannot give (it put CE's nuclei at 3-13 px, against ~40).
	 */
	@Test
	public void theRequestCarriesVoxelSizeAndTheMeasuredCellRadius()
	{
		// x, y 0.09 per voxel, z 1.0: Fluo-N3DH-CE
		final double[] a = { 0.09, 0, 0, 0, 0, 0.09, 0, 0, 0, 0, 1.0, 0 };
		final Frame f = new Frame( 3, new long[] { 2, 2, 2 }, new float[ 8 ], a );
		final JSONObject with = RemoteTool.request( f, new JSONObject(), 1.21 ).getJSONObject( "frame" );
		assertEquals( 0.09, with.getJSONArray( "voxel_size" ).getDouble( 0 ), 1e-12 );
		assertEquals( 0.09, with.getJSONArray( "voxel_size" ).getDouble( 1 ), 1e-12 );
		assertEquals( 1.0, with.getJSONArray( "voxel_size" ).getDouble( 2 ), 1e-12 );
		assertEquals( 1.21, with.getDouble( "cell_radius_um" ), 1e-12 );
		assertFalse( "no radius is sent when none was measured",
				RemoteTool.request( f, new JSONObject() ).getJSONObject( "frame" ).has( "cell_radius_um" ) );

		final ToolContext ctx = new ToolContext( null );
		assertTrue( FrameDetector.hints( ctx ).isEmpty() );
		ctx.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, 1.21 );
		assertEquals( 1.21, ( Double ) FrameDetector.hints( ctx ).get( FrameDetector.HINT_CELL_RADIUS_UM ), 1e-12 );
	}
}
