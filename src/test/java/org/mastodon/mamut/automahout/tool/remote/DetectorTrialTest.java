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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.automahout.tool.DataProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.ToolRegistry;

import com.sun.net.httpserver.HttpServer;

/**
 * The user's procedure: StarDist, then Cellpose, each tried on a few frames and shown; the first accepted
 * detects the movie, and with none accepted ELEPHANT runs.
 */
public class DetectorTrialTest
{
	private HttpServer server;

	private ToolRegistry registry;

	private Function< ToolContext, Frame.Reader > saved;

	private final List< String > asked = new ArrayList<>();

	private static final DataProfile FLUORESCENCE_2D = DataProfile.of( false, 92, true, 30, Double.NaN );

	@Before
	public void start() throws IOException
	{
		// Listed out of order, with a brightfield model that must not be tried on fluorescence.
		final String manifest = "{\"protocol\":\"automahout-remote/1\",\"tools\":["
				+ tool( "cellpose.nuclei", true ) + "," + tool( "stardist.he", false ) + "," + tool( "stardist.fluo", true ) + "]}";
		server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		server.createContext( "/tools", ex -> {
			final String body = "GET".equals( ex.getRequestMethod() ) ? manifest
					: "{\"detections\":[{\"centroid\":[5,5],\"covariance\":[[4,0],[0,4]]}],\"message\":\"one\"}";
			final byte[] b = body.getBytes( StandardCharsets.UTF_8 );
			ex.sendResponseHeaders( 200, b.length );
			try (OutputStream os = ex.getResponseBody())
			{
				os.write( b );
			}
		} );
		server.start();
		registry = new ToolRegistry();
		registry.addRemote( Collections.singletonList( "http://127.0.0.1:" + server.getAddress().getPort() ), s -> {} );
		saved = RemoteTool.readers;
		RemoteTool.readers = ctx -> t -> new Frame( t, new long[] { 20, 10, 1 }, new float[ 200 ],
				new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } );
	}

	private static String tool( final String id, final boolean fluo )
	{
		return "{\"id\":\"" + id + "\",\"role\":\"DETECT\",\"applicability\":{\"requires\":{\"fluorescenceLike\":" + fluo
				+ ",\"volumetric\":false},\"score\":0.7,\"reason\":\"r\"}}";
	}

	@After
	public void stop()
	{
		RemoteTool.readers = saved;
		server.stop( 0 );
	}

	/** A context whose dialog answers {@code answers} in turn and records which detector it was shown. */
	private ToolContext answering( final int... answers )
	{
		final ToolContext ctx = new ReplayGraph().context();
		final int[] k = { 0 };
		ctx.setImageChoiceCallback( ( q, images, captions, buttons ) -> {
			asked.add( captions.get( 0 ).split( ":" )[ 0 ] );
			assertEquals( 1, images.size() );
			return answers[ k[ 0 ]++ ];
		} );
		return ctx;
	}

	@Test
	public void stardistIsTriedFirstThenCellposeAndOnlyWhatSuitsTheData()
	{
		final List< String > ids = new ArrayList<>();
		for ( final FrameDetector d : DetectorTrial.candidates( registry, FLUORESCENCE_2D ) )
			ids.add( d.getId() );
		assertEquals( "[stardist.fluo, cellpose.nuclei]", ids.toString() );
	}

	@Test
	public void aNamedDetectorIsUsedWithoutTheTrialAndItsCountsAreKept()
	{
		System.setProperty( DetectorTrial.PROPERTY_DETECTOR, "stardist.he" ); // would not be tried on fluorescence
		try
		{
			final ToolContext ctx = answering();
			final FrameDetector d = DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0, 45, 91 } );
			assertEquals( "stardist.he", d.getId() );
			assertEquals( "[]", asked.toString() ); // nothing shown
			assertEquals( "stardist.he", ctx.getSharedData( DetectorTrial.SHARED_CHOICE ) );
			final int[][] counts = ( int[][] ) ctx.getSharedData( DetectorTrial.SHARED_ACCEPTED_COUNTS );
			assertEquals( "[0, 45, 91]", java.util.Arrays.toString( counts[ 0 ] ) );
			assertEquals( "[1, 1, 1]", java.util.Arrays.toString( counts[ 1 ] ) );
		}
		finally
		{
			System.clearProperty( DetectorTrial.PROPERTY_DETECTOR );
		}
	}

	@Test
	public void theLogSaysHowTheDetectorWasChosen()
	{
		assertEquals( "you accepted stardist.fluo in the trial", DetectorTrial.howChosen( "stardist.fluo" ) );
		System.setProperty( DetectorTrial.PROPERTY_DETECTOR, "stardist.fluo" );
		try
		{
			assertEquals( "you named stardist.fluo for this run", DetectorTrial.howChosen( "stardist.fluo" ) );
		}
		finally
		{
			System.clearProperty( DetectorTrial.PROPERTY_DETECTOR );
		}
	}

	@Test
	public void namingElephantSkipsThePretrainedDetectors()
	{
		System.setProperty( DetectorTrial.PROPERTY_DETECTOR, "elephant" );
		try
		{
			final ToolContext ctx = answering();
			assertEquals( null, DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0 } ) );
			assertEquals( DetectorTrial.ELEPHANT, ctx.getSharedData( DetectorTrial.SHARED_CHOICE ) );
		}
		finally
		{
			System.clearProperty( DetectorTrial.PROPERTY_DETECTOR );
		}
	}

	@Test( expected = IllegalStateException.class )
	public void aNameNoServerOffersIsRefused()
	{
		System.setProperty( DetectorTrial.PROPERTY_DETECTOR, "cellpose.cpsam" );
		try
		{
			DetectorTrial.choose( answering(), registry, FLUORESCENCE_2D, new int[] { 0 } );
		}
		finally
		{
			System.clearProperty( DetectorTrial.PROPERTY_DETECTOR );
		}
	}

	@Test
	public void theFirstAcceptedDetectorIsUsed()
	{
		final ToolContext ctx = answering( 1, 0 ); // StarDist: not good enough; Cellpose: use it
		final FrameDetector d = DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0, 45, 91 } );
		assertEquals( "cellpose.nuclei", d.getId() );
		assertEquals( "[stardist.fluo, cellpose.nuclei]", asked.toString() );
	}

	@Test
	public void noneAcceptedMeansElephantAndTheChoiceHoldsForTheRun()
	{
		final ToolContext ctx = answering( 1, 1 );
		assertNull( DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0, 45, 91 } ) );
		assertEquals( DetectorTrial.ELEPHANT, ctx.getSharedData( DetectorTrial.SHARED_CHOICE ) );
		// A second adaptive_track lap: not asked again.
		assertNull( DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0, 45, 91 } ) );
		assertEquals( 2, asked.size() );
	}

	@Test
	public void anAcceptedDetectorIsRememberedToo()
	{
		final ToolContext ctx = answering( 0 );
		assertEquals( "stardist.fluo", DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 3 } ).getId() );
		assertEquals( "stardist.fluo", DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 3 } ).getId() );
		assertEquals( 1, asked.size() );
	}

	@Test
	public void withNobodyToAskTheImageDecides()
	{
		// The fake frames are blank: no detector agrees with an image that has no bright region, so ELEPHANT.
		final ToolContext ctx = new ReplayGraph().context(); // no dialog
		assertNull( DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0 } ) );
		assertTrue( asked.isEmpty() );
	}

	/**
	 * Ten bright disks of radius 6, 30 px apart, on a 300 x 60 frame whose background has a spread of 30 --
	 * a camera's, roughly. (With almost no noise the frame's own cut sits just above the background and the
	 * blurred edges of neighbours join them into one region, which no real frame does.)
	 */
	private static Frame tenCells()
	{
		final float[] px = new float[ 300 * 60 ];
		final java.util.Random rnd = new java.util.Random( 1 );
		for ( int i = 0; i < px.length; i++ )
			px[ i ] = 100 + 30 * ( float ) rnd.nextGaussian();
		for ( int c = 0; c < 10; c++ )
			for ( int y = 0; y < 60; y++ )
				for ( int x = 0; x < 300; x++ )
					if ( Math.hypot( x - ( 15 + 30 * c ), y - 30 ) <= 6 )
						px[ y * 300 + x ] = 1000;
		return new Frame( 0, new long[] { 300, 60, 1 }, px, new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } );
	}

	/** A detector that reports the given centroids. */
	private static FrameDetector reporting( final String id, final double[][] at )
	{
		return new FrameDetector()
		{
			@Override
			public java.util.List< Detection > detect( final Frame f, final java.util.Map< String, Object > p )
			{
				final java.util.List< Detection > out = new java.util.ArrayList<>();
				for ( final double[] c : at )
					out.add( new Detection( new double[] { c[ 0 ], c[ 1 ], 0 }, new double[][] { { 36, 0, 0 }, { 0, 36, 0 }, { 0, 0, 36 } } ) );
				return out;
			}

			@Override
			public String getId()
			{
				return id;
			}

			@Override
			public String getDescription()
			{
				return id;
			}

			@Override
			public String execute( final ToolContext c, final java.util.Map< String, Object > p )
			{
				return "";
			}
		};
	}

	/** {@link FrameDetector#detect} for a fake that cannot fail. */
	private static java.util.List< FrameDetector.Detection > detectNow( final FrameDetector d, final Frame f )
	{
		try
		{
			return d.detect( f, java.util.Collections.emptyMap() );
		}
		catch ( final IOException e )
		{
			throw new AssertionError( e );
		}
	}

	private static double[][] cells( final int... which )
	{
		final double[][] out = new double[ which.length ][];
		for ( int i = 0; i < which.length; i++ )
			out[ i ] = new double[] { 15 + 30 * which[ i ], 30 };
		return out;
	}

	@Test
	public void theImageReadsTenCellsAndScoresDetectorsAgainstThem()
	{
		final Frame f = tenCells();
		final double[] all = DetectorTrial.imageAgreement( f, detectNow( reporting( "a", cells( 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 ) ), f ) );
		assertEquals( 1.0, all[ 0 ], 1e-9 );
		assertEquals( 1.0, all[ 1 ], 1e-9 );
		final double[] half = DetectorTrial.imageAgreement( f, detectNow( reporting( "b", cells( 0, 1, 2, 3, 4 ) ), f ) );
		assertEquals( 0.5, half[ 1 ], 1e-9 );
		final double[] background = DetectorTrial.imageAgreement( f,
				detectNow( reporting( "c", new double[][] { { 30, 5 }, { 150, 55 } } ), f ) );
		assertEquals( 0.0, background[ 0 ], 1e-9 );
	}

	/**
	 * A dim nucleus -- above the background, below the bright cut -- is a cell to the precision proxy, not a false
	 * detection; empty background still is one. The background has a broad texture (stripes 40 px wide) that the
	 * smoothing keeps, as real tissue backgrounds do: it holds the bright cut high, and that is where dim nuclei fell
	 * below it -- on a dense urothelial movie Cellpose-SAM and StarDist, which find them, scored precision ~0.5.
	 */
	@Test
	public void aDetectionOnADimNucleusIsNotCountedFalse()
	{
		final int w = 300, h = 60;
		final java.util.Random rnd = new java.util.Random( 2 );
		final float[] px = new float[ w * h ];
		for ( int y = 0; y < h; y++ )
			for ( int x = 0; x < w; x++ )
				px[ y * w + x ] = ( float ) ( 100 + 20 * Math.sin( 2 * Math.PI * x / 40 ) + 3 * rnd.nextGaussian() );
		for ( int c = 0; c < 10; c++ )
			for ( int y = 0; y < h; y++ )
				for ( int x = 0; x < w; x++ )
					if ( Math.hypot( x - ( 15 + 30 * c ), y - 30 ) <= 6 )
						px[ y * w + x ] += 60; // 1.5 spreads above the frame even in a trough, short of the bright cut on a crest
		final Frame f = new Frame( 0, new long[] { w, h, 1 }, px, new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } );
		assertEquals( "the dim nuclei are below the bright cut", 0,
				java.util.Arrays.stream( DetectorTrial.brightRegions( f ) ).max().getAsInt() );
		final double[] onDim = DetectorTrial.imageAgreement( f, detectNow( reporting( "dim", cells( 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 ) ), f ) );
		assertEquals( 1.0, onDim[ 0 ], 1e-9 );
		// Empty background, in the stripes' troughs and on their crests, away from the nuclei (y = 30).
		final double[] onEmpty = DetectorTrial.imageAgreement( f, detectNow( reporting( "empty",
				new double[][] { { 30, 6 }, { 110, 54 }, { 190, 8 }, { 50, 52 }, { 130, 7 } } ), f ) );
		assertEquals( 0.0, onEmpty[ 0 ], 1e-9 );
	}

	@Test
	public void theDetectorThatAgreesBestIsChosenAndAPoorFieldMeansElephant()
	{
		final java.util.List< Frame > frames = java.util.Collections.singletonList( tenCells() );
		final ToolContext ctx = new ReplayGraph().context();
		final FrameDetector good = reporting( "good", cells( 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 ) );
		final FrameDetector half = reporting( "half", cells( 0, 1, 2, 3, 4 ) );
		assertEquals( "good", DetectorTrial.chooseFromImage( ctx, java.util.Arrays.asList( half, good ), frames ).getId() );
		final ToolContext ctx2 = new ReplayGraph().context();
		assertNull( "F1 0.67 is below the bar", DetectorTrial.chooseFromImage( ctx2,
				java.util.Collections.singletonList( half ), frames ) );
		assertEquals( DetectorTrial.ELEPHANT, ctx2.getSharedData( DetectorTrial.SHARED_CHOICE ) );
	}

	@Test
	public void switchedToAutomaticTheTrialAsksNothing()
	{
		final ToolContext ctx = answering( 0 );
		ctx.setSharedData( DetectorTrial.SHARED_AUTOMATIC, Boolean.TRUE );
		DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0 } );
		assertTrue( asked.isEmpty() );
	}

	@Test
	public void closingTheDialogMeansElephant()
	{
		final ToolContext ctx = answering( -1 );
		assertNull( DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0 } ) );
		assertEquals( 1, asked.size() );
	}

	@Test
	public void anAcceptedDetectorsCountsAreKeptAndTheRunKnowsItIsPretrained()
	{
		final ToolContext ctx = answering( 0 );
		DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0, 45, 91 } );
		final int[][] c = ( int[][] ) ctx.getSharedData( DetectorTrial.SHARED_ACCEPTED_COUNTS );
		assertEquals( "[0, 45, 91]", java.util.Arrays.toString( c[ 0 ] ) );
		assertEquals( "[1, 1, 1]", java.util.Arrays.toString( c[ 1 ] ) ); // the fake server finds one each
		assertTrue( DetectorTrial.pretrainedChosen( ctx ) );
	}

	@Test
	public void elephantChosenIsNotPretrained()
	{
		final ToolContext ctx = answering( 2 );
		DetectorTrial.choose( ctx, registry, FLUORESCENCE_2D, new int[] { 0 } );
		assertTrue( !DetectorTrial.pretrainedChosen( ctx ) );
		assertNull( ctx.getSharedData( DetectorTrial.SHARED_ACCEPTED_COUNTS ) );
	}

	/** A detector whose detections jump to new places from frame to frame: noise, not cells. */
	private static FrameDetector jumping( final String id )
	{
		return new FrameDetector()
		{
			@Override
			public java.util.List< Detection > detect( final Frame f, final java.util.Map< String, Object > p )
			{
				final java.util.List< Detection > out = new java.util.ArrayList<>();
				for ( int c = 0; c < 10; c++ ) // 20 px further on every frame: never within one radius (6)
					out.add( new Detection( new double[] { ( 15 + 30 * c + 20 * f.t ) % 300, 30, 0 },
							new double[][] { { 36, 0, 0 }, { 0, 36, 0 }, { 0, 0, 36 } } ) );
				return out;
			}

			@Override
			public String getId()
			{
				return id;
			}

			@Override
			public String getDescription()
			{
				return id;
			}

			@Override
			public String execute( final ToolContext c, final java.util.Map< String, Object > p )
			{
				return "";
			}
		};
	}

	@Test
	public void persistenceCountsDetectionsThatReappearWithinOneRadius()
	{
		final Frame a = tenCells(), b = new Frame( 1, a.dims, a.data, a.voxelToWorld );
		final FrameDetector all = reporting( "all", cells( 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 ) );
		assertEquals( 1.0, DetectorTrial.persistence( a, detectNow( all, a ), b, detectNow( all, b ), 6 ), 1e-9 );
		final FrameDetector noise = jumping( "noise" );
		assertEquals( 0.0, DetectorTrial.persistence( a, detectNow( noise, a ), b, detectNow( noise, b ), 6 ), 1e-9 );
		// 10 in one frame, 5 of them in the next: 5 matched of a mean count of 7.5
		assertEquals( 5 / 7.5, DetectorTrial.persistence( a, detectNow( all, a ), b,
				detectNow( reporting( "half", cells( 0, 1, 2, 3, 4 ) ), b ), 6 ), 1e-9 );
		assertEquals( 0.0, DetectorTrial.persistence( a, java.util.Collections.emptyList(), b, detectNow( all, b ), 6 ),
				1e-9 );
	}

	/**
	 * Not fluorescence (PSC) or a stack (CE): the bright-region proxy measured nothing there, so the detector that
	 * persists is chosen; one that does not persist on every pair is not, and ELEPHANT is used.
	 */
	@Test
	public void whereTheImageProxyDoesNotApplyPersistenceDecides()
	{
		final DataProfile phase = DataProfile.of( false, 300, false, 30, Double.NaN );
		final DataProfile stack = DataProfile.of( true, 250, true, 30, Double.NaN );
		assertTrue( DetectorTrial.imageAgreementApplies( FLUORESCENCE_2D ) );
		assertFalse( DetectorTrial.imageAgreementApplies( phase ) );
		assertFalse( DetectorTrial.imageAgreementApplies( stack ) );

		final Frame.Reader reader = t -> t < 0 || t > 9 ? null : new Frame( t, new long[] { 300, 60, 1 },
				tenCells().data, new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } );
		final java.util.Map< Integer, Frame > read = new java.util.LinkedHashMap<>();
		for ( final int t : new int[] { 0, 5, 9 } )
			read.put( t, reader.read( t ) );
		final FrameDetector good = reporting( "good", cells( 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 ) );
		final ToolContext ctx = new ReplayGraph().context();
		assertNull( "without a measured cell size nothing can be checked",
				DetectorTrial.chooseByPersistence( ctx, java.util.Arrays.asList( good ), reader, read ) );
		final ToolContext ctx2 = new ReplayGraph().context();
		ctx2.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, 6.0 );
		assertEquals( "good", DetectorTrial.chooseByPersistence( ctx2,
				java.util.Arrays.asList( jumping( "noise" ), good ), reader, read ).getId() );
		final ToolContext ctx3 = new ReplayGraph().context();
		ctx3.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, 6.0 );
		assertNull( DetectorTrial.chooseByPersistence( ctx3, java.util.Arrays.asList( jumping( "noise" ) ), reader,
				read ) );
		assertEquals( DetectorTrial.ELEPHANT, ctx3.getSharedData( DetectorTrial.SHARED_CHOICE ) );
	}

	/**
	 * Where no rejected detector can seed (fluorescence: the review starts the training), a detector's trial stops at
	 * the first pair below the bar, and the decision is the full trial's; where it can seed, every pair is still run.
	 */
	@Test
	public void theTrialStopsAtThePairThatDecidesIt()
	{
		final Frame.Reader reader = t -> t < 0 || t > 9 ? null : new Frame( t, new long[] { 300, 60, 1 },
				tenCells().data, new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } );
		final java.util.Map< Integer, Frame > read = new java.util.LinkedHashMap<>();
		for ( final int t : new int[] { 0, 5, 9 } )
			read.put( t, reader.read( t ) );
		final FrameDetector half = halfSteady( "half" );
		final int[] calls = new int[ 1 ];
		final FrameDetector counted = new FrameDetector()
		{
			@Override
			public java.util.List< Detection > detect( final Frame f, final java.util.Map< String, Object > p )
					throws java.io.IOException
			{
				calls[ 0 ]++;
				return half.detect( f, p );
			}

			@Override
			public String getId()
			{
				return "half";
			}

			@Override
			public String getDescription()
			{
				return "half";
			}

			@Override
			public String execute( final ToolContext c, final java.util.Map< String, Object > p )
			{
				return "";
			}
		};
		final ToolContext fluo = new ReplayGraph().context();
		fluo.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, 6.0 );
		assertNull( DetectorTrial.chooseByPersistence( fluo, java.util.Arrays.asList( counted ), reader, read, false ) );
		assertEquals( "one pair (two frames), not three", 2, calls[ 0 ] );
		calls[ 0 ] = 0;
		final ToolContext phase = new ReplayGraph().context();
		phase.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, 6.0 );
		assertNull( DetectorTrial.chooseByPersistence( phase, java.util.Arrays.asList( counted ), reader, read, true ) );
		assertEquals( "it may seed, so every pair is run", 6, calls[ 0 ] );
		assertTrue( DetectorTrial.seedsNeeded( DataProfile.of( false, 300, false, 30, Double.NaN ) ) );
		assertFalse( DetectorTrial.seedsNeeded( DataProfile.of( true, 250, true, 30, Double.NaN ) ) );
	}

	/** Half of its detections stay put (cells 0-4), the other half jump: persistence 0.5 on every pair. */
	private static FrameDetector halfSteady( final String id )
	{
		return new FrameDetector()
		{
			@Override
			public java.util.List< Detection > detect( final Frame f, final java.util.Map< String, Object > p )
			{
				final java.util.List< Detection > out = new java.util.ArrayList<>();
				for ( int c = 0; c < 10; c++ )
					out.add( new Detection( new double[] { c < 5 ? 15 + 30 * c : ( 15 + 30 * c + 20 * f.t ) % 300, 30, 0 },
							new double[][] { { 16, 0, 0 }, { 0, 16, 0 }, { 0, 0, 16 } } ) );
				return out;
			}

			@Override
			public String getId()
			{
				return id;
			}

			@Override
			public String getDescription()
			{
				return id;
			}

			@Override
			public String execute( final ToolContext c, final java.util.Map< String, Object > p )
			{
				return "";
			}
		};
	}

	/**
	 * None passes, but one persists on every pair: ELEPHANT detects, seeded from that one's persisting detections
	 * (CE: 63% of them real cells, against 23% for intensity maxima). One that finds nothing on a pair is no seed
	 * source (PSC's StarDist H&E).
	 */
	@Test
	public void aDetectorThatDoesNotPassCanStillSeedElephant()
	{
		final Frame.Reader reader = t -> t < 0 || t > 9 ? null : new Frame( t, new long[] { 300, 60, 1 },
				tenCells().data, new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } );
		final java.util.Map< Integer, Frame > read = new java.util.LinkedHashMap<>();
		for ( final int t : new int[] { 0, 5, 9 } )
			read.put( t, reader.read( t ) );
		final java.util.function.Function< ToolContext, Frame.Reader > saved = RemoteTool.readers;
		RemoteTool.readers = c -> reader;
		try
		{
			final ToolContext ctx = new ReplayGraph().context();
			ctx.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, 6.0 );
			assertNull( DetectorTrial.chooseByPersistence( ctx, java.util.Arrays.asList( jumping( "noise" ),
					halfSteady( "half" ) ), reader, read ) );
			assertEquals( "half", ( ( FrameDetector ) ctx.getSharedData( DetectorTrial.SHARED_SEED_DETECTOR ) ).getId() );
			final java.util.List< double[] > seeds = DetectorTrial.persistentSeeds( ctx, 5, 3 );
			assertEquals( "capped at 3, all from the steady half", 3, seeds.size() );
			for ( final double[] sd : seeds )
			{
				assertTrue( sd[ 0 ] < 150 ); // cells 0-4 sit at x = 15..135
				assertEquals( 4.0, sd[ 3 ], 1e-9 ); // radius from the covariance (16 = 4²)
			}

			final ToolContext noSeed = new ReplayGraph().context();
			noSeed.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, 6.0 );
			assertNull( DetectorTrial.chooseByPersistence( noSeed, java.util.Arrays.asList( jumping( "noise" ) ), reader,
					read ) );
			assertNull( "nothing persisted on a pair: no seed detector",
					noSeed.getSharedData( DetectorTrial.SHARED_SEED_DETECTOR ) );
			assertNull( DetectorTrial.persistentSeeds( noSeed, 5, 3 ) );
		}
		finally
		{
			RemoteTool.readers = saved;
		}
	}

	/** Condition E (seeds only): the detector the choice would use seeds ELEPHANT instead of detecting. */
	@Test
	public void seedsOnlyMakesTheChosenDetectorASeedSource()
	{
		final Frame.Reader reader = t -> t < 0 || t > 9 ? null : new Frame( t, new long[] { 300, 60, 1 },
				tenCells().data, new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } );
		final java.util.Map< Integer, Frame > read = new java.util.LinkedHashMap<>();
		for ( final int t : new int[] { 0, 5, 9 } )
			read.put( t, reader.read( t ) );
		final FrameDetector good = reporting( "good", cells( 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 ) );
		System.setProperty( DetectorTrial.PROPERTY_SEEDS_ONLY, "true" );
		try
		{
			final ToolContext ctx = new ReplayGraph().context();
			ctx.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, 6.0 );
			assertNull( DetectorTrial.chooseByPersistence( ctx, java.util.Arrays.asList( good ), reader, read ) );
			assertEquals( DetectorTrial.ELEPHANT, ctx.getSharedData( DetectorTrial.SHARED_CHOICE ) );
			assertEquals( "good", ( ( FrameDetector ) ctx.getSharedData( DetectorTrial.SHARED_SEED_DETECTOR ) ).getId() );
			assertFalse( DetectorTrial.pretrainedChosen( ctx ) );
		}
		finally
		{
			System.clearProperty( DetectorTrial.PROPERTY_SEEDS_ONLY );
		}
	}
}
