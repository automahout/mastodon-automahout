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
package org.mastodon.mamut.automahout.autocorrect;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.swing.SwingUtilities;

import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.automahout.bench.CtcExport;
import org.mastodon.mamut.automahout.bench.CtcGroundTruth;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.io.ProjectLoader;

import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.scijava.Context;

import bdv.viewer.Source;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Replays the merge repair on a finished run's saved project, against the CTC truth, so a change to it is
 * judged on the same detections every time rather than on a new run's (which differ from run to run).
 *
 * <pre>
 * java ... MergeReplay project=&lt;project.mastodon&gt; gt=&lt;0x_GT/TRA&gt; out=&lt;dir&gt; [repair=true]
 * </pre>
 *
 * <p>A merge is a detection whose ellipse holds the centres of two truth cells. Its onset is a frame where
 * those two cells' truth predecessors were held by two different detections the frame before. For every
 * onset it writes why the repair leaves it ({@link AutoCorrectOrchestrator#convergenceVerdict}) to
 * {@code verdicts.tsv}, and a count per reason. With {@code repair=true} it then runs the repair over the
 * whole movie, counts again, and writes the result's CTC files to {@code out/RES} for per_window.py.</p>
 */
public final class MergeReplay
{
	static
	{
		net.imagej.patcher.LegacyInjector.preinit();
	}

	private MergeReplay()
	{}

	public static void main( final String[] args ) throws Exception
	{
		final Map< String, String > a = new HashMap<>();
		for ( final String arg : args )
		{
			final int eq = arg.indexOf( '=' );
			if ( eq > 0 )
				a.put( arg.substring( 0, eq ), arg.substring( eq + 1 ) );
		}
		final Path out = Paths.get( a.get( "out" ) );
		Files.createDirectories( out );
		int exit = 0;
		try
		{
			final ProjectModel pm = ProjectLoader.open( a.get( "project" ), new Context() );
			final MamutViewBdv[] view = new MamutViewBdv[ 1 ];
			SwingUtilities.invokeAndWait( () -> view[ 0 ] = pm.getWindowManager().createBigDataViewer() );
			final ToolContext ctx = new ToolContext( pm, view[ 0 ].getViewerPanelMamut() );
			ctx.setLogCallback( System.out::println );
			final Source< ? > src = pm.getSharedBdvData().getSources().get( 0 ).getSpimSource();
			final AffineTransform3D toWorld = new AffineTransform3D();
			src.getSourceTransform( pm.getMinTimepoint(), 0, toWorld );
			final CtcGroundTruth.Read truth = CtcGroundTruth.read( Paths.get( a.get( "gt" ) ), toWorld );

			// Geometry as the repair has it: in plane on single-plane data, in 3-D on a stack.
			final boolean stack = ctx.isVolumetric();
			AutoCorrectOrchestrator.withGeometry( stack, () -> {
				try
				{
					overlaps( ctx, truth, out.resolve( "overlaps.tsv" ) );
					return null;
				}
				catch ( final Exception e )
				{
					throw new RuntimeException( e );
				}
			} );
			final int[] before = AutoCorrectOrchestrator.withGeometry( stack, () -> reportUnchecked( ctx, truth, out.resolve( "verdicts.tsv" ) ) );
			if ( Boolean.parseBoolean( a.getOrDefault( "repair", "false" ) ) )
			{
				final int n = pm.getMaxTimepoint() + 1;
				final int[] r = AutoCorrectOrchestrator.repairConvergedTracks( ctx, 1, n );
				System.out.println( "repair: " + r[ 0 ] + " frame(s) in " + r[ 1 ] + " merge(s)" );
				final int[] after = AutoCorrectOrchestrator.withGeometry( stack,
						() -> reportUnchecked( ctx, truth, out.resolve( "verdicts_after.tsv" ) ) );
				System.out.println( String.format( "merged detections: %d -> %d; onsets: %d -> %d", before[ 0 ],
						after[ 0 ], before[ 1 ], after[ 1 ] ) );
				final RandomAccessibleInterval< ? > rai = src.getSource( pm.getMinTimepoint(), 0 );
				final long[] dims = rai.numDimensions() > 2 && rai.dimension( 2 ) > 1
						? new long[] { rai.dimension( 0 ), rai.dimension( 1 ), rai.dimension( 2 ) }
						: new long[] { rai.dimension( 0 ), rai.dimension( 1 ) };
				CtcExport.export( pm.getModel(), dims, toWorld, pm.getMinTimepoint(), pm.getMaxTimepoint(),
						out.resolve( "RES" ) );
			}
		}
		catch ( final Throwable e )
		{
			e.printStackTrace();
			exit = 1;
		}
		System.exit( exit );
	}

	private static int[] reportUnchecked( final ToolContext ctx, final CtcGroundTruth.Read truth, final Path tsv )
	{
		try
		{
			return report( ctx, truth, tsv );
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
	}

	/** Writes one line per merge onset and prints the count per reason; returns {merged detections, onsets}. */
	static int[] report( final ToolContext ctx, final CtcGroundTruth.Read truth, final Path tsv ) throws Exception
	{
		final ModelGraph graph = ctx.getGraph();
		final ModelGraph gt = truth.model.getGraph();
		final Map< Integer, List< Spot > > res = byFrame( graph );
		final Map< Integer, List< Spot > > cells = byFrame( gt );
		final TreeMap< String, Integer > reasons = new TreeMap<>();
		int merged = 0, onsets = 0;
		final Spot gref = gt.vertexRef();
		try (PrintWriter w = new PrintWriter( Files.newBufferedWriter( tsv, StandardCharsets.UTF_8 ) ))
		{
			w.println( "t\tx\ty\treason" );
			for ( final Map.Entry< Integer, List< Spot > > e : res.entrySet() )
			{
				final int t = e.getKey();
				for ( final Spot s : e.getValue() )
				{
					final List< Spot > held = new ArrayList<>();
					for ( final Spot c : cells.getOrDefault( t, new ArrayList<>() ) )
						if ( holds( s, c ) )
							held.add( c );
					if ( held.size() < 2 )
						continue;
					merged++;
					// The two cells' truth predecessors, and the detections holding them the frame before.
					final Spot[] before = new Spot[ 2 ];
					for ( int k = 0; k < 2; k++ )
					{
						final Spot c = held.get( k );
						if ( c.incomingEdges().size() != 1 )
							break;
						c.incomingEdges().iterator().next().getSource( gref );
						if ( gref.getTimepoint() != t - 1 )
							break;
						before[ k ] = holder( res.getOrDefault( t - 1, new ArrayList<>() ), gref );
					}
					if ( before[ 0 ] == null || before[ 1 ] == null || before[ 0 ] == before[ 1 ] )
						continue;
					onsets++;
					// The repair's Q is whichever of the two is not the merged detection's predecessor.
					Spot q = null;
					final Spot p = graph.vertexRef();
					if ( s.incomingEdges().size() == 1 )
					{
						s.incomingEdges().iterator().next().getSource( p );
						q = p.equals( before[ 0 ] ) ? before[ 1 ] : p.equals( before[ 1 ] ) ? before[ 0 ] : null;
					}
					final String reason = q == null ? "merged detection's predecessor holds neither cell"
							: AutoCorrectOrchestrator.convergenceVerdict( ctx, s, q );
					graph.releaseRef( p );
					reasons.merge( reason.split( " \\| " )[ 0 ].replaceAll( "[0-9.]+", "#" ), 1, Integer::sum );
					w.println( t + "\t" + Math.round( s.getDoublePosition( 0 ) ) + "\t" + Math.round( s.getDoublePosition( 1 ) )
							+ "\t" + reason );
				}
			}
		}
		gt.releaseRef( gref );
		System.out.println( "merged detections: " + merged + ", onsets from two separate detections: " + onsets );
		reasons.forEach( ( k, v ) -> System.out.println( String.format( "  %4d  %s", v, k ) ) );
		return new int[] { merged, onsets };
	}

	/**
	 * Every pair of detections in one frame where one holds the other's centre, judged against the truth: is
	 * the inner one on another cell than the outer one's own (the outer one grew over a neighbour), or on the
	 * same cell (a duplicate)? And can the lineage tell the two apart: did both have their own predecessor
	 * the frame before, and were those two predecessors apart (neither holding the other's centre)?
	 */
	static void overlaps( final ToolContext ctx, final CtcGroundTruth.Read truth, final Path tsv ) throws Exception
	{
		final ModelGraph graph = ctx.getGraph();
		final Map< Integer, List< Spot > > res = byFrame( graph );
		final Map< Integer, List< Spot > > cells = byFrame( truth.model.getGraph() );
		final int[][] table = new int[ 2 ][ 3 ]; // [separate histories?][neighbour, same cell, no cell]
		final Spot pa = graph.vertexRef(), pb = graph.vertexRef();
		try (PrintWriter w = new PrintWriter( Files.newBufferedWriter( tsv, StandardCharsets.UTF_8 ) ))
		{
			w.println( "t\tx\ty\tseparate_histories\ttruth\trOuter/rInner" );
			for ( final Map.Entry< Integer, List< Spot > > e : res.entrySet() )
			{
				final int t = e.getKey();
				final List< Spot > frame = e.getValue();
				final List< Spot > here = cells.getOrDefault( t, new ArrayList<>() );
				for ( final Spot outer : frame )
					for ( final Spot inner : frame )
					{
						if ( outer.equals( inner ) || !holds( outer, inner ) )
							continue;
						final Spot cOuter = nearest( here, outer ), cInner = nearest( here, inner );
						final int truthKind = cInner == null || !holds( inner, cInner ) ? 2
								: cOuter != null && cOuter.equals( cInner ) ? 1 : 0;
						boolean separate = outer.incomingEdges().size() == 1 && inner.incomingEdges().size() == 1;
						if ( separate )
						{
							outer.incomingEdges().iterator().next().getSource( pa );
							inner.incomingEdges().iterator().next().getSource( pb );
							separate = !pa.equals( pb ) && !holds( pa, pb ) && !holds( pb, pa );
						}
						table[ separate ? 1 : 0 ][ truthKind ]++;
						w.println( t + "\t" + Math.round( outer.getDoublePosition( 0 ) ) + "\t"
								+ Math.round( outer.getDoublePosition( 1 ) ) + "\t" + separate + "\t"
								+ new String[] { "neighbour", "same cell", "no cell" }[ truthKind ] + "\t"
								+ String.format( "%.2f", Math.sqrt( outer.getBoundingSphereRadiusSquared()
										/ inner.getBoundingSphereRadiusSquared() ) ) );
					}
			}
		}
		graph.releaseRef( pa );
		graph.releaseRef( pb );
		System.out.println( "detections holding another detection's centre (inner one on: neighbour / same cell / no cell):" );
		System.out.println( String.format( "  separate histories:     %4d / %4d / %4d", table[ 1 ][ 0 ], table[ 1 ][ 1 ], table[ 1 ][ 2 ] ) );
		System.out.println( String.format( "  not separate histories: %4d / %4d / %4d", table[ 0 ][ 0 ], table[ 0 ][ 1 ], table[ 0 ][ 2 ] ) );
	}

	/** The truth cell nearest a detection's centre, in plane; null in an empty frame. */
	private static Spot nearest( final List< Spot > cells, final Spot s )
	{
		Spot best = null;
		double bd = Double.POSITIVE_INFINITY;
		for ( final Spot c : cells )
		{
			double d = 0;
			for ( int k = 0; k < 2; k++ )
				d += Math.pow( c.getDoublePosition( k ) - s.getDoublePosition( k ), 2 );
			if ( d < bd )
			{
				bd = d;
				best = c;
			}
		}
		return best;
	}

	/** Spots by frame, as copies (the vertex iterator hands back one reused object). */
	private static Map< Integer, List< Spot > > byFrame( final ModelGraph g )
	{
		final Map< Integer, List< Spot > > m = new TreeMap<>();
		for ( final Spot s : g.vertices() )
			m.computeIfAbsent( s.getTimepoint(), k -> new ArrayList<>() ).add( g.vertexRef().refTo( s ) );
		return m;
	}

	/** Whether a detection's in-plane ellipse holds a truth cell's centre. */
	private static boolean holds( final Spot s, final Spot cell )
	{
		final double[] sp = new double[ 3 ], cp = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		s.localize( sp );
		s.getCovariance( cov );
		cell.localize( cp );
		return AutoCorrectOrchestrator.insideSpot( sp, cov, cp );
	}

	/** The detection holding this cell's centre, nearest first; null when none does. */
	private static Spot holder( final List< Spot > frame, final Spot cell )
	{
		Spot best = null;
		double bd = Double.POSITIVE_INFINITY;
		for ( final Spot s : frame )
			if ( holds( s, cell ) )
			{
				double d = 0;
				for ( int k = 0; k < 2; k++ )
					d += Math.pow( s.getDoublePosition( k ) - cell.getDoublePosition( k ), 2 );
				if ( d < bd )
				{
					bd = d;
					best = s;
				}
			}
		return best;
	}

}
