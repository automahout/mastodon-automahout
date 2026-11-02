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

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.automahout.tool.DataProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;

/**
 * End to end against a real tool server (examples/remote-tools/server.py) and a real frame, HeLa 01
 * t=10: Java reads the frame, the server runs StarDist, the detections come back into the graph and are
 * scored against the tracking markers. Opt-in, since it needs the server and the dataset:
 *
 * <pre>mvn test -Dtest=RemoteHeLaIT -Dautomahout.remote.it=http://127.0.0.1:8765</pre>
 */
public class RemoteHeLaIT
{
	private static final File HELA = new File( System.getProperty( "user.home" ), "Repositories/CTC_dataset/Fluo-N2DL-HeLa" );

	private static final double VOXEL = 0.645, MATCH_UM = 8.0;

	private static float[] pixels( final File f, final int[] wh )
	{
		final ImagePlus imp = new Opener().openImage( f.getPath() );
		final ImageProcessor ip = imp.getProcessor();
		wh[ 0 ] = ip.getWidth();
		wh[ 1 ] = ip.getHeight();
		final float[] out = new float[ wh[ 0 ] * wh[ 1 ] ];
		for ( int i = 0; i < out.length; i++ )
			out[ i ] = ip.getf( i );
		return out;
	}

	@Test
	public void stardistFindsTheHeLaNucleiThroughTheAdapter()
	{
		final String url = System.getProperty( "automahout.remote.it" );
		assumeTrue( "set -Dautomahout.remote.it=<server url>", url != null );
		final int t = 10;
		final int[] wh = new int[ 2 ];
		final float[] img = pixels( new File( HELA, String.format( "01/t%03d.tif", t ) ), wh );
		final Function< ToolContext, Frame.Reader > saved = RemoteTool.readers;
		RemoteTool.readers = ctx -> tt -> new Frame( tt, new long[] { wh[ 0 ], wh[ 1 ], 1 }, img,
				new double[] { VOXEL, 0, 0, 0, 0, VOXEL, 0, 0, 0, 0, 1, 0 } );
		try
		{
			final List< String > log = new ArrayList<>();
			RemoteTool stardist = null;
			for ( final RemoteTool r : new RemoteToolServer( url ).tools( log::add ) )
				if ( r.getId().equals( "stardist.fluo" ) )
					stardist = r;
			assumeTrue( "server offers no stardist.fluo: " + log, stardist != null );
			System.out.println( "applicability on HeLa (2D fluorescence): "
					+ stardist.applicability( DataProfile.of( false, 92, true, 30, Double.NaN ) ) );
			final ReplayGraph g = new ReplayGraph();
			final Map< String, Object > params = new HashMap<>();
			params.put( RemoteTool.PARAM_T_START, t );
			System.out.println( stardist.execute( g.context(), params ) );
			// Score against the tracking markers.
			final int[] mwh = new int[ 2 ];
			final float[] tra = pixels( new File( HELA, String.format( "01_GT/TRA/man_track%03d.tif", t ) ), mwh );
			final Map< Integer, double[] > sum = new HashMap<>();
			for ( int i = 0; i < tra.length; i++ )
				if ( tra[ i ] > 0 )
				{
					final double[] s = sum.computeIfAbsent( ( int ) tra[ i ], k -> new double[ 3 ] );
					s[ 0 ] += ( i % mwh[ 0 ] ) * VOXEL;
					s[ 1 ] += ( i / mwh[ 0 ] ) * VOXEL;
					s[ 2 ]++;
				}
			int onCell = 0, spots = 0;
			final java.util.Set< Integer > hit = new java.util.HashSet<>();
			for ( final Spot s : g.graph().vertices() )
			{
				spots++;
				int best = -1;
				double bd = Double.POSITIVE_INFINITY;
				for ( final Map.Entry< Integer, double[] > e : sum.entrySet() )
				{
					final double d = Math.hypot( e.getValue()[ 0 ] / e.getValue()[ 2 ] - s.getDoublePosition( 0 ),
							e.getValue()[ 1 ] / e.getValue()[ 2 ] - s.getDoublePosition( 1 ) );
					if ( d < bd )
					{
						bd = d;
						best = e.getKey();
					}
				}
				if ( bd <= MATCH_UM && hit.add( best ) )
					onCell++;
			}
			System.out.printf( "t=%d: %d spots in the graph, %d on a cell, recall %d/%d%n", t, spots, onCell, hit.size(), sum.size() );
			assertTrue( "recall " + hit.size() + "/" + sum.size(), hit.size() >= 0.85 * sum.size() );
		}
		finally
		{
			RemoteTool.readers = saved;
		}
	}

	/** The automatic choice on HeLa: the image-agreement proxy per detector, as the run computes it. */
	@Test
	public void theImageAgreementOnHeLa() throws Exception
	{
		final String url = System.getProperty( "automahout.remote.it" );
		assumeTrue( "set -Dautomahout.remote.it=<server url>", url != null );
		final List< Frame > frames = new ArrayList<>();
		for ( final int t : new int[] { 0, 45, 91 } )
		{
			final int[] wh = new int[ 2 ];
			final float[] img = pixels( new File( HELA, String.format( "01/t%03d.tif", t ) ), wh );
			frames.add( new Frame( t, new long[] { wh[ 0 ], wh[ 1 ], 1 }, img,
					new double[] { VOXEL, 0, 0, 0, 0, VOXEL, 0, 0, 0, 0, 1, 0 } ) );
		}
		final List< FrameDetector > candidates = new ArrayList<>();
		for ( final RemoteTool r : new RemoteToolServer( url ).tools( s -> {} ) )
			candidates.add( r );
		final ReplayGraph g = new ReplayGraph();
		final ToolContext ctx = g.context();
		final List< String > log = new ArrayList<>();
		ctx.setLogCallback( log::add );
		final FrameDetector chosen = DetectorTrial.chooseFromImage( ctx, candidates, frames );
		log.forEach( System.out::println );
		assertTrue( "chosen: " + ( chosen == null ? "ELEPHANT" : chosen.getId() ),
				chosen != null && chosen.getId().equals( "stardist.fluo" ) );
	}
}
