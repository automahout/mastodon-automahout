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

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.swing.SwingUtilities;

import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.automahout.bench.CtcGroundTruth;
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.Applicability;
import org.mastodon.mamut.automahout.tool.CorrectionPass;
import org.mastodon.mamut.automahout.tool.DataProfile;
import org.mastodon.mamut.automahout.tool.PassPlan;
import org.mastodon.mamut.automahout.tool.PassScope;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.io.ProjectLoader;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.scijava.Context;

import bdv.viewer.Source;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Runs the per-frame correction passes one at a time over frames of a finished run's saved project, and says
 * of every detection each pass deletes whether it was the only one on a truth cell (a cell lost), or not (a
 * duplicate, or on no cell). For finding which pass removes real cells; changes nothing on disk.
 *
 * <pre>
 * java ... PassReplay project=&lt;project.mastodon&gt; gt=&lt;0x_GT/TRA&gt; from=&lt;t&gt; to=&lt;t&gt;
 *      [runinfo=&lt;run-info.json&gt; [server=&lt;url&gt;]]   re-detect the slice first, with the run's model
 *      [quality=true]                                   report ELEPHANT quality per ten frames instead
 *      [stayjudge=true]                                 judge staying_divisions' cut, in-plane vs 3-D rule, instead
 *      [refitflat=true] [export=&lt;dir&gt;]             re-place one-plane detections in z; write CTC RES
 *      [recover=true]                                   add a detection at each uncovered bright nucleus
 *      [shapefit=true]                                  run the detection-stage shape fit (SAM when a server is set)
 *      [fitbench=true]                                  the contour fit seeded by the bounding vs the in-plane radius
 *      [missed=true]                                    describe every truth cell no detection holds
 *      [duplicates=true]                                report the geometry of the extra detections instead
 *      [blobs=&lt;diameter&gt; refsout=&lt;csv&gt;]           write every blob and the review's confident references
 * </pre>
 */
public final class PassReplay
{
	static
	{
		net.imagej.patcher.LegacyInjector.preinit();
	}

	private PassReplay()
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
		int exit = 0;
		try
		{
			final ProjectModel pm = ProjectLoader.open( a.get( "project" ), new Context() );
			final MamutViewBdv[] view = new MamutViewBdv[ 1 ];
			SwingUtilities.invokeAndWait( () -> view[ 0 ] = pm.getWindowManager().createBigDataViewer() );
			final ToolContext ctx = new ToolContext( pm, view[ 0 ].getViewerPanelMamut() );
			ctx.setLogCallback( line -> {} );
			final Source< ? > src = pm.getSharedBdvData().getSources().get( 0 ).getSpimSource();
			final AffineTransform3D toWorld = new AffineTransform3D();
			src.getSourceTransform( pm.getMinTimepoint(), 0, toWorld );
			final CtcGroundTruth.Read truth = CtcGroundTruth.read( Paths.get( a.get( "gt" ) ), toWorld );
			final Map< Integer, List< Spot > > cells = new TreeMap<>();
			for ( final Spot c : truth.model.getGraph().vertices() )
				cells.computeIfAbsent( c.getTimepoint(), k -> new ArrayList<>() ).add( truth.model.getGraph().vertexRef().refTo( c ) );

			final int from = Integer.parseInt( a.get( "from" ) ), to = Integer.parseInt( a.get( "to" ) );
			if ( a.containsKey( "runinfo" ) )
				redetect( ctx, view[ 0 ].getViewerPanelMamut(), Paths.get( a.get( "runinfo" ) ), a.get( "server" ), from, to );
			if ( Boolean.parseBoolean( a.getOrDefault( "quality", "false" ) ) )
			{
				qualityByWindow( ctx, cells, from, to );
				System.exit( 0 );
			}
			if ( Boolean.parseBoolean( a.getOrDefault( "refitflat", "false" ) ) )
			{
				int moved = 0;
				for ( int t = from; t <= to; t++ )
				{
					final List< double[] > flats = new ArrayList<>();
					for ( final double[] p : positions( ctx.getGraph(), t ) )
						if ( p[ 12 ] <= 1.0001 )
							flats.add( p );
					moved += AutoCorrectOrchestrator.refitFlatDetectionsInZ( ctx, t );
					flatLandings( ctx, cells.getOrDefault( t, new ArrayList<>() ), t, flats );
				}
				System.out.println( "refitflat: " + moved + " detection(s) re-placed in z" );
			}
			if ( Boolean.parseBoolean( a.getOrDefault( "recover", "false" ) ) )
			{
				int added = 0;
				for ( int t = from; t <= to; t++ )
					added += AutoCorrectOrchestrator.recoverUncoveredNuclei( ctx, t );
				System.out.println( "recover: " + added + " detection(s) added" );
			}
			if ( Boolean.parseBoolean( a.getOrDefault( "stayjudge", "false" ) ) )
			{
				AutoCorrectOrchestrator.withGeometry( ctx.isVolumetric(), () -> {
					stayJudge( ctx, cells, from, to );
					return null;
				} );
				System.exit( 0 );
			}
			if ( Boolean.parseBoolean( a.getOrDefault( "stacked", "false" ) ) )
			{
				int removed = 0;
				for ( int t = from; t <= to; t++ )
				{
					stackedPairs( ctx, cells.getOrDefault( t, new ArrayList<>() ), t );
					removed += AutoCorrectOrchestrator.removeStackedDuplicates( ctx, t );
				}
				System.out.println( "stacked: " + removed + " duplicate(s) removed" );
			}
			if ( a.containsKey( "export" ) )
			{
				final net.imglib2.RandomAccessibleInterval< ? > rai = src.getSource( pm.getMinTimepoint(), 0 );
				org.mastodon.mamut.automahout.bench.CtcExport.export( pm.getModel(),
						new long[] { rai.dimension( 0 ), rai.dimension( 1 ), rai.dimension( 2 ) }, toWorld, pm.getMinTimepoint(),
						pm.getMaxTimepoint(), Paths.get( a.get( "export" ) ) );
			}
			if ( a.containsKey( "nucleus" ) )
			{
				// nucleus=x,y,z[;x,y,z...] radius=<r>: ask the VLM whether each position at t=from is a nucleus, as the
				// window review does for a doubtful reference.
				// How to reach the language model, from the environment or the user's settings, as a benchmark run takes it.
				final java.util.prefs.Preferences user = java.util.prefs.Preferences
						.userNodeForPackage( org.mastodon.mamut.automahout.plugin.AutomahoutSettings.class );
				final java.util.prefs.Preferences mine = org.mastodon.mamut.automahout.tool.AutomahoutPrefs
						.node( org.mastodon.mamut.automahout.plugin.AutomahoutSettings.class );
				for ( final String k : org.mastodon.mamut.automahout.bench.BenchmarkRunner.LLM_KEYS )
				{
					final String env = System.getenv( org.mastodon.mamut.automahout.tool.AutomahoutPrefs.llmEnvName( k ) );
					final String v = env != null && !env.isEmpty() ? env : user.get( k, null );
					if ( v != null )
						mine.put( k, v );
				}
				final org.mastodon.mamut.automahout.plugin.AutomahoutSettings st = new org.mastodon.mamut.automahout.plugin.AutomahoutSettings();
				final org.mastodon.mamut.automahout.vision.VlmBackend backend =
						org.mastodon.mamut.automahout.tool.impl.AutonomousTrackingTool.buildVlmBackend( st );
				final String[] ps = a.get( "nucleus" ).split( ";" );
				final double[][] xyz = new double[ ps.length ][];
				for ( int i = 0; i < ps.length; i++ )
					xyz[ i ] = Arrays.stream( ps[ i ].split( "," ) ).mapToDouble( Double::parseDouble ).toArray();
				for ( final double[] p : xyz )
				{
					final Boolean answer = org.mastodon.mamut.automahout.tool.VisualColdStart.isNucleus( src, from, p, null,
							Double.parseDouble( a.get( "radius" ) ), backend, st.getVlmModel() );
					System.out.println( String.format( "nucleus t=%d (%.1f, %.1f, %.1f): %s", from, p[ 0 ], p[ 1 ], p[ 2 ], answer ) );
				}
				System.exit( 0 );
			}
			if ( a.containsKey( "scale" ) )
			{
				// scale=<min>,<max>: the cell radius by scale selection, then the blobs at that radius, judged against truth.
				final String[] mm = a.get( "scale" ).split( "," );
				final double[] ladder = org.mastodon.mamut.automahout.tool.VisualColdStart.scaleLadder(
						Double.parseDouble( mm[ 0 ] ), Double.parseDouble( mm[ 1 ] ) );
				for ( int t = from; t <= to; t++ )
				{
					final double[] est = org.mastodon.mamut.automahout.tool.VisualColdStart.cellRadius( src, t, ladder );
					System.out.println( String.format( "scale t=%d: radius %.2f um from %d blobs", t, est[ 0 ], ( int ) est[ 1 ] ) );
					a.put( "blobs", String.valueOf( 2 * est[ 0 ] ) );
				}
			}
			if ( a.containsKey( "blobs" ) && a.containsKey( "refsout" ) )
			{
				// refsout=<csv>: every blob at that size and the confident ones the review takes as references, per frame,
				// for reconstructing a review round offline (Fig. 1b). Columns kind,t,x,y,z (world units).
				final double radius = Double.parseDouble( a.get( "blobs" ) ) / 2;
				try (java.io.PrintWriter w = new java.io.PrintWriter( a.get( "refsout" ), "UTF-8" ))
				{
					w.println( "kind,t,x,y,z" );
					for ( int t = from; t <= to; t++ )
					{
						for ( final double[] p : org.mastodon.mamut.automahout.tool.VisualColdStart.blobs( src, t, radius ) )
							w.println( String.format( java.util.Locale.ROOT, "blob,%d,%.4f,%.4f,%.4f", t, p[ 0 ], p[ 1 ], p[ 2 ] ) );
						for ( final double[] p : org.mastodon.mamut.automahout.tool.VisualColdStart.confidentBlobs( src, t, radius ) )
							w.println( String.format( java.util.Locale.ROOT, "reference,%d,%.4f,%.4f,%.4f", t, p[ 0 ], p[ 1 ], p[ 2 ] ) );
					}
				}
				System.out.println( "references written to " + a.get( "refsout" ) );
			}
			if ( a.containsKey( "blobs" ) )
			{
				// blobs=<diameter>: VisualColdStart's seeds at that size, judged against the truth cells.
				final double radius = Double.parseDouble( a.get( "blobs" ) ) / 2;
				for ( int t = from; t <= to; t++ )
				{
					final List< double[] > b = Boolean.parseBoolean( a.getOrDefault( "persist", "false" ) )
							? org.mastodon.mamut.automahout.tool.VisualColdStart.persistentBlobs( src, t, radius )
							: Boolean.parseBoolean( a.getOrDefault( "confident", "false" ) )
									? org.mastodon.mamut.automahout.tool.VisualColdStart.confidentBlobs( src, t, radius )
									: org.mastodon.mamut.automahout.tool.VisualColdStart.blobs( src, t, radius );
					final List< Spot > cs = cells.getOrDefault( t, new ArrayList<>() );
					final double[] cp = new double[ 3 ];
					if ( Boolean.parseBoolean( a.getOrDefault( "strength", "false" ) ) )
					{
						// strength=true: each kept blob's LoG response over the median of the kept blobs', on a cell or not.
						final List< double[] > rs = org.mastodon.mamut.automahout.tool.VisualColdStart.blobResponses( src, t, radius );
						final double[] v = new double[ b.size() ];
						for ( int i = 0; i < b.size(); i++ )
							for ( final double[] r : rs )
								if ( r[ 1 ] == b.get( i )[ 0 ] && r[ 2 ] == b.get( i )[ 1 ] && r[ 3 ] == b.get( i )[ 2 ] )
									v[ i ] = r[ 0 ];
						final double[] sorted = v.clone();
						java.util.Arrays.sort( sorted );
						final double med = sorted.length == 0 ? 1 : sorted[ sorted.length / 2 ];
						for ( int i = 0; i < b.size(); i++ )
						{
							boolean on = false;
							for ( final Spot c : cs )
							{
								c.localize( cp );
								on |= Math.sqrt( d2( b.get( i ), cp ) ) < radius;
							}
							System.out.println( String.format( "strength t=%d %s %.3f", t, on ? "cell" : "none", v[ i ] / med ) );
						}
					}
					int found = 0, none = 0;
					for ( final Spot c : cs )
					{
						c.localize( cp );
						for ( final double[] p : b )
							if ( Math.sqrt( d2( p, cp ) ) < radius )
							{
								found++;
								break;
							}
					}
					for ( final double[] p : b )
					{
						boolean on = false;
						for ( final Spot c : cs )
						{
							c.localize( cp );
							on |= Math.sqrt( d2( p, cp ) ) < radius;
						}
						if ( !on )
						{
							none++;
							double nearest = Double.POSITIVE_INFINITY;
							for ( final Spot c : cs )
							{
								c.localize( cp );
								nearest = Math.min( nearest, Math.sqrt( d2( p, cp ) ) );
							}
							System.out.println( String.format( "  blob on no cell at (%.1f, %.1f, %.1f): %.1f from the nearest "
									+ "cell centre (%.1f radii)", p[ 0 ], p[ 1 ], p[ 2 ], nearest, nearest / radius ) );
						}
					}
					System.out.println( String.format( "blobs t=%d diameter %.1f: %d blobs, %d of %d cells found, %d on no cell", t,
							2 * radius, b.size(), found, cs.size(), none ) );
				}
				System.exit( 0 );
			}
			if ( Boolean.parseBoolean( a.getOrDefault( "shapefit", "false" ) ) )
			{
				final int[] seen = new int[ 3 ];
				int fitted = 0;
				final long t0 = System.currentTimeMillis();
				for ( int t = from; t <= to; t++ )
					fitted += org.mastodon.mamut.automahout.tool.impl.ElephantTool.fitDetectionsToContour( ctx, t, seen );
				System.out.println( String.format( "shapefit: %d of %d fitted (%d by SAM, %d in 3-D), %d s", fitted, seen[ 0 ], seen[ 2 ],
						seen[ 1 ], ( System.currentTimeMillis() - t0 ) / 1000 ) );
			}
			if ( Boolean.parseBoolean( a.getOrDefault( "fitbench", "false" ) ) )
			{
				fitBench( ctx, from, to );
				System.exit( 0 );
			}
			if ( Boolean.parseBoolean( a.getOrDefault( "missed", "false" ) ) )
			{
				missedCells( ctx, cells, from, to );
				System.exit( 0 );
			}
			if ( Boolean.parseBoolean( a.getOrDefault( "duplicates", "false" ) ) )
			{
				extraDetections( ctx, cells, from, to );
				System.exit( 0 );
			}
			final int n = pm.getMaxTimepoint() + 1;
			final AutomahoutSettings settings = new AutomahoutSettings();
			final PassPlan plan = PassPlan.forRun( ctx, CorrectionPass.Stage.FRAME, FramePasses.BUILT_IN );
			final DataProfile data = DataProfile.of( ctx );
			final Map< String, int[] > tally = new java.util.LinkedHashMap<>(); // id -> {deleted, cell lost, duplicate, no cell}
			final Map< String, int[] > lostAt = new java.util.LinkedHashMap<>(); // id -> real cells lost per frame of the slice
			final boolean stack = ctx.isVolumetric();
			for ( int t = from; t <= to; t++ )
			{
				final PassScope scope = PassScope.frame( ctx, settings, t, n, from );
				for ( final CorrectionPass p : plan.passes() )
				{
					final Applicability ap = p.applicability( data );
					if ( !ap.applies || !p.runsAt( scope ) )
						continue;
					final List< double[] > before = positions( ctx.getGraph(), t );
					p.run( scope );
					final List< double[] > after = positions( ctx.getGraph(), t );
					final int[] c = tally.computeIfAbsent( p.getId(), k -> new int[ 4 ] );
					for ( final double[] b : before )
					{
						if ( contains( after, b ) )
							continue;
						c[ 0 ]++;
						// Judged against what is left: a truth cell this detection held and no remaining one does.
						final Spot cell = AutoCorrectOrchestrator.withGeometry( stack,
								() -> heldCell( cells.getOrDefault( b.length > 3 ? ( int ) b[ 3 ] : -1, new ArrayList<>() ), b ) );
						if ( cell == null )
							c[ 3 ]++;
						else if ( AutoCorrectOrchestrator.withGeometry( stack, () -> heldByAny( ctx.getGraph(), t( b ), cell ) ) )
							c[ 2 ]++;
						else
						{
							c[ 1 ]++;
							lostAt.computeIfAbsent( p.getId(), k -> new int[ to - from + 1 ] )[ t( b ) - from ]++;
						}
					}
				}
			}
			System.out.println( String.format( "per-frame passes over t=%d..%d: deleted / real cell lost / duplicate / on no cell", from, to ) );
			tally.forEach( ( id, c ) -> System.out.println( String.format( "  %-28s %5d / %5d / %5d / %5d", id, c[ 0 ], c[ 1 ], c[ 2 ], c[ 3 ] ) ) );
			System.out.println( "real cells lost, per frame t=" + from + ".." + to + ":" );
			lostAt.forEach( ( id, c ) -> System.out.println( String.format( "  %-28s %s", id, java.util.Arrays.toString( c ) ) ) );
		}
		catch ( final Throwable e )
		{
			e.printStackTrace();
			exit = 1;
		}
		System.exit( exit );
	}

	/**
	 * The slice as the run's own detection would make it now: ELEPHANT set up as the run had it (its dataset,
	 * its model on the server, the radii and linking distance it measured; from its run-info.json), the
	 * slice's spots cleared, every frame predicted and the slice linked as [4/6] links it. Detections made in
	 * this process carry ELEPHANT's quality, which a reopened project does not. Only predicts: the server's
	 * model is not trained or changed.
	 */
	private static void redetect( final ToolContext ctx, final bdv.viewer.ViewerPanel viewer, final java.nio.file.Path runInfo,
			final String server, final int from, final int to ) throws Exception
	{
		final org.json.JSONObject info = new org.json.JSONObject( new String( java.nio.file.Files.readAllBytes( runInfo ),
				java.nio.charset.StandardCharsets.UTF_8 ) );
		final org.json.JSONObject es = info.getJSONObject( "elephant_settings" );
		final String url = server != null ? server : info.getJSONObject( "elephant_server" ).getString( "url" );
		org.elephant.setting.server.ElephantServerSettingsManager.getInstance().getForwardDefaultStyle().setServerURL( url );
		final org.elephant.setting.main.ElephantMainSettingsManager styles = org.elephant.setting.main.ElephantMainSettingsManager.getInstance();
		final org.elephant.setting.main.ElephantMainSettings style = styles.duplicate( org.elephant.setting.main.ElephantMainSettings.defaultStyle() );
		styles.rename( style, "pass-replay-" + System.currentTimeMillis() );
		style.setDatasetName( es.getString( "getDatasetName" ) );
		style.setDetectionModelName( es.getString( "getDetectionModelName" ) );
		style.setMinRadius( es.getDouble( "getMinRadius" ) );
		style.setMaxRadius( es.getDouble( "getMaxRadius" ) );
		style.setNNLinkingThreshold( es.getDouble( "getNNLinkingThreshold" ) );
		styles.setSelectedStyle( style );
		final org.mastodon.mamut.automahout.elephant.ElephantIntegration elephant =
				new org.mastodon.mamut.automahout.elephant.ElephantIntegration( ctx );
		if ( !elephant.init() )
			throw new IllegalStateException( "ELEPHANT not reachable at " + url );
		final ModelGraph g = ctx.getGraph();
		final List< Spot > clear = new ArrayList<>();
		for ( final Spot s : g.vertices() )
			if ( s.getTimepoint() >= from && s.getTimepoint() <= to )
				clear.add( g.vertexRef().refTo( s ) );
		for ( final Spot s : clear )
			org.mastodon.mamut.automahout.tool.GraphEdits.removeSpot( ctx, g, s, org.mastodon.mamut.automahout.tool.GraphEdits.Reason.BULK );
		final long t0 = System.currentTimeMillis();
		int n = 0;
		for ( int t = from; t <= to; t++ )
		{
			elephant.predictSpots( t );
			for ( final Spot s : g.vertices() )
				if ( s.getTimepoint() == t )
					n++;
		}
		final int linked = org.mastodon.mamut.automahout.tool.impl.ElephantTool.linkWindow( ctx, viewer, from, to );
		System.out.println( String.format( "re-detected t=%d..%d with model %s: %d spot(s), %d frame(s) linked, %d s",
				from, to, es.getString( "getDetectionModelName" ), n, linked, ( System.currentTimeMillis() - t0 ) / 1000 ) );
	}

	/**
	 * ELEPHANT's stored quality (mean probability) of the detections, per ten frames: those holding a truth
	 * cell against the rest, with the median and the share under 0.5 (the weak-spot bar).
	 */
	private static void qualityByWindow( final ToolContext ctx, final Map< Integer, List< Spot > > cells, final int from,
			final int to )
	{
		final ModelGraph g = ctx.getGraph();
		final boolean stack = ctx.isVolumetric();
		System.out.println( "frames     real: n  median  <0.5    |   not on a cell: n  median  <0.5" );
		for ( int w = from; w <= to; w += 10 )
		{
			final List< Double > real = new ArrayList<>(), other = new ArrayList<>();
			for ( final Spot s : g.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < w || t > Math.min( to, w + 9 ) )
					continue;
				final java.util.OptionalDouble q = org.mastodon.mamut.automahout.elephant.DetectionQuality.get( g, s );
				if ( !q.isPresent() )
					continue;
				final List< double[] > one = new ArrayList<>();
				for ( final double[] p : positions( g, t ) )
					if ( Math.abs( p[ 0 ] - s.getDoublePosition( 0 ) ) < 1e-6 && Math.abs( p[ 1 ] - s.getDoublePosition( 1 ) ) < 1e-6
							&& Math.abs( p[ 2 ] - s.getDoublePosition( 2 ) ) < 1e-6 )
						one.add( p );
				final Spot cell = one.isEmpty() ? null : AutoCorrectOrchestrator.withGeometry( stack,
						() -> heldCell( cells.getOrDefault( t, new ArrayList<>() ), one.get( 0 ) ) );
				( cell != null ? real : other ).add( q.getAsDouble() );
			}
			System.out.println( String.format( "t=%3d-%3d  %5d  %.2f  %5.1f%%   |  %5d  %.2f  %5.1f%%", w, Math.min( to, w + 9 ),
					real.size(), median( real ), below( real, 0.5 ), other.size(), median( other ), below( other, 0.5 ) ) );
		}
	}

	/**
	 * Where the detections beyond one per truth cell sit, for the stacked-duplicate rule. Every detection is
	 * given the truth cell nearest its centre among those its ellipsoid holds; a cell held by two or more has
	 * extra detections, and each extra one is described against the one nearest the cell: the centre distance
	 * in plane and in z (µm), the smaller Mahalanobis² of either centre in the other's ellipsoid (the rule
	 * fires at <= 1), and both radii. A detection holding no cell is described by the nearest cell: its
	 * distance in units of the detection's own reach toward it, and whether another detection holds that cell.
	 * One line per detection, tab-separated, for analysis outside.
	 */
	private static void extraDetections( final ToolContext ctx, final Map< Integer, List< Spot > > cells, final int from,
			final int to )
	{
		final ModelGraph g = ctx.getGraph();
		final boolean stack = ctx.isVolumetric();
		System.out.println( "kind\tt\tdxy\tdz\tmaha2\tr_this\tr_other\tz_this\tz_other\tother_held\tlinks_this\tlinks_other\tsz_this\tsxy_this\treach" );
		final double[] cp = new double[ 3 ];
		// The detections' semi-axes, per ten frames: z (the stack axis) against the mean in-plane one.
		for ( int w = from; w <= to; w += 10 )
		{
			final List< Double > z = new ArrayList<>(), xy = new ArrayList<>();
			for ( int t = w; t <= Math.min( to, w + 9 ); t++ )
				for ( final double[] p : positions( g, t ) )
				{
					z.add( Math.sqrt( p[ 12 ] ) );
					xy.add( ( Math.sqrt( p[ 4 ] ) + Math.sqrt( p[ 8 ] ) ) / 2 );
				}
			System.out.println( String.format( "axes\t%d\t%d\tz %.2f\txy %.2f", w, z.size(), median( z ), median( xy ) ) );
		}
		for ( int t = from; t <= to; t++ )
		{
			final List< Spot > cs = cells.getOrDefault( t, new ArrayList<>() );
			final List< double[] > ps = positions( g, t );
			final List< Spot > held = new ArrayList<>();
			for ( final double[] p : ps )
				held.add( AutoCorrectOrchestrator.withGeometry( stack, () -> heldCell( cs, p ) ) );
			final int[] links = linkCounts( g, t, ps );
			for ( int i = 0; i < ps.size(); i++ )
			{
				final double[] p = ps.get( i );
				if ( held.get( i ) != null )
				{
					held.get( i ).localize( cp );
					// The detection nearest this cell is its "first"; every other one holding it is extra.
					int first = i;
					for ( int j = 0; j < ps.size(); j++ )
						if ( held.get( j ) == held.get( i ) && d2( ps.get( j ), cp ) < d2( ps.get( first ), cp ) )
							first = j;
					if ( first == i )
						continue;
					final double[] q = ps.get( first );
					System.out.println( row( "extra", t, p, q, true, links[ i ], links[ first ] ) );
					continue;
				}
				// Holding no cell: against the nearest cell, and the detection (if any) holding it.
				Spot near = null;
				for ( final Spot c : cs )
				{
					final double[] cc = new double[ 3 ];
					c.localize( cc );
					if ( near == null )
						near = c;
					else
					{
						near.localize( cp );
						if ( d2( p, cc ) < d2( p, cp ) )
							near = c;
					}
				}
				if ( near == null )
					continue;
				near.localize( cp );
				int holder = -1;
				for ( int j = 0; j < ps.size(); j++ )
					if ( held.get( j ) == near )
						holder = j;
				final double reach = Math.sqrt( maha2( p, cp ) );
				final double[] q = holder >= 0 ? ps.get( holder ) : new double[] { cp[ 0 ], cp[ 1 ], cp[ 2 ], t, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
				final String r = row( "none", t, p, q, holder >= 0, links[ i ], holder >= 0 ? links[ holder ] : -1 );
				System.out.println( r + "\t" + String.format( "%.2f", reach ) );
			}
		}
	}

	/**
	 * Every truth cell no detection holds, one tab-separated line each: its position, and the nearest
	 * detection's centre distance in plane and in z, the Mahalanobis distance of the cell in it, its z and
	 * in-plane semi-axes, its links, and whether it holds another cell (one detection over two cells).
	 */
	private static void missedCells( final ToolContext ctx, final Map< Integer, List< Spot > > cells, final int from,
			final int to )
	{
		final ModelGraph g = ctx.getGraph();
		final boolean stack = ctx.isVolumetric();
		System.out.println( "missed\tt\tx\ty\tz\tdxy\tdz\treach\tsz\tsxy\tlinks\tholds_other\tn_cells" );
		final double[] cp = new double[ 3 ];
		for ( int t = from; t <= to; t++ )
		{
			final List< Spot > cs = cells.getOrDefault( t, new ArrayList<>() );
			final List< double[] > ps = positions( g, t );
			final List< Spot > held = new ArrayList<>();
			for ( final double[] p : ps )
				held.add( AutoCorrectOrchestrator.withGeometry( stack, () -> heldCell( cs, p ) ) );
			final int[] links = linkCounts( g, t, ps );
			for ( final Spot c : cs )
			{
				c.localize( cp );
				boolean isHeld = false;
				for ( final double[] p : ps )
					if ( AutoCorrectOrchestrator.withGeometry( stack, () -> AutoCorrectOrchestrator.insideSpot( p, cov( p ), cp ) ) )
						isHeld = true;
				if ( isHeld )
					continue;
				int near = -1;
				for ( int j = 0; j < ps.size(); j++ )
					if ( near < 0 || d2( ps.get( j ), cp ) < d2( ps.get( near ), cp ) )
						near = j;
				if ( near < 0 )
				{
					System.out.println( String.format( "missed\t%d\t%.3f\t%.3f\t%.3f\tNaN\tNaN\tNaN\tNaN\tNaN\t-1\tfalse\t%d", t, cp[ 0 ],
							cp[ 1 ], cp[ 2 ], cs.size() ) );
					continue;
				}
				final double[] p = ps.get( near );
				System.out.println( String.format( "missed\t%d\t%.3f\t%.3f\t%.3f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%d\t%b\t%d", t, cp[ 0 ],
						cp[ 1 ], cp[ 2 ], Math.hypot( p[ 0 ] - cp[ 0 ], p[ 1 ] - cp[ 1 ] ), Math.abs( p[ 2 ] - cp[ 2 ] ),
						Math.sqrt( maha2( p, cp ) ), Math.sqrt( p[ 12 ] ), ( Math.sqrt( p[ 4 ] ) + Math.sqrt( p[ 8 ] ) ) / 2, links[ near ],
						held.get( near ) != null, cs.size() ) );
			}
		}
	}

	/**
	 * The pairs {@link AutoCorrectOrchestrator#removeStackedDuplicates} acts on at {@code t} (one centre inside
	 * the other's ellipsoid), one line each: distances, both Mahalanobis distances, both semi-axes, and the
	 * truth cell each holds -- "same" (a duplicate), "two" (two cells: removing either loses one), or less.
	 */
	private static void stackedPairs( final ToolContext ctx, final List< Spot > cs, final int t )
	{
		final boolean stack = ctx.isVolumetric();
		final List< double[] > ps = positions( ctx.getGraph(), t );
		for ( int i = 0; i < ps.size(); i++ )
			for ( int j = i + 1; j < ps.size(); j++ )
			{
				final double[] p = ps.get( i ), q = ps.get( j );
				final double mi = maha2( p, q ), mj = maha2( q, p );
				if ( mi > 1 && mj > 1 )
					continue;
				final Spot ci = AutoCorrectOrchestrator.withGeometry( stack, () -> heldCell( cs, p ) );
				final Spot cj = AutoCorrectOrchestrator.withGeometry( stack, () -> heldCell( cs, q ) );
				final String verdict = ci == null && cj == null ? "none" : ci == null || cj == null ? "one" : ci == cj ? "same" : "two";
				System.out.println( String.format( "pair\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%s", t,
						Math.hypot( p[ 0 ] - q[ 0 ], p[ 1 ] - q[ 1 ] ), Math.abs( p[ 2 ] - q[ 2 ] ), Math.sqrt( mi ), Math.sqrt( mj ),
						Math.sqrt( p[ 12 ] ), Math.sqrt( q[ 12 ] ), ( Math.sqrt( p[ 4 ] ) + Math.sqrt( p[ 8 ] ) ) / 2,
						( Math.sqrt( q[ 4 ] ) + Math.sqrt( q[ 8 ] ) ) / 2, verdict ) );
			}
	}

	/**
	 * Each detection the z refit re-placed (found by its unchanged x, y): whether it now lands on another
	 * detection (its centre inside the other's ellipsoid, or the other's inside its own), with the distance and
	 * the truth verdict -- "same" (a duplicate of that detection's cell), "two" (two cells), or less.
	 */
	private static void flatLandings( final ToolContext ctx, final List< Spot > cs, final int t, final List< double[] > flats )
	{
		final boolean stack = ctx.isVolumetric();
		final List< double[] > ps = positions( ctx.getGraph(), t );
		for ( final double[] f : flats )
		{
			int me = -1;
			for ( int i = 0; i < ps.size(); i++ )
				if ( Math.abs( ps.get( i )[ 0 ] - f[ 0 ] ) < 1e-6 && Math.abs( ps.get( i )[ 1 ] - f[ 1 ] ) < 1e-6 )
					me = i;
			if ( me < 0 )
				continue;
			final double[] p = ps.get( me );
			int other = -1;
			double best = Double.POSITIVE_INFINITY;
			for ( int j = 0; j < ps.size(); j++ )
			{
				if ( j == me )
					continue;
				final double[] q = ps.get( j );
				if ( maha2( q, p ) <= 1 || maha2( p, q ) <= 1 )
				{
					final double d = d2( p, q );
					if ( d < best )
					{
						best = d;
						other = j;
					}
				}
			}
			final Spot cp = AutoCorrectOrchestrator.withGeometry( stack, () -> heldCell( cs, p ) );
			if ( other < 0 )
			{
				System.out.println( String.format( "flat\t%d\talone\t%s", t, cp == null ? "none" : "cell" ) );
				continue;
			}
			final double[] q = ps.get( other );
			final Spot cq = AutoCorrectOrchestrator.withGeometry( stack, () -> heldCell( cs, q ) );
			final String verdict = cp == null && cq == null ? "none" : cp == null ? "dup_or_none" : cq == null ? "flat_only" : cp == cq ? "same" : "two";
			System.out.println( String.format( "flat\t%d\tlands\t%s\t%.2f\t%.2f\t%.2f\t%b", t, verdict, Math.hypot( p[ 0 ] - q[ 0 ], p[ 1 ] - q[ 1 ] ),
					Math.abs( p[ 2 ] - q[ 2 ] ), Math.sqrt( q[ 12 ] ), q[ 12 ] <= 1.0001 ) );
		}
	}

	/**
	 * For every detection, the in-plane contour fit seeded two ways: by the bounding-sphere radius (the largest
	 * semi-axis, as the pipeline does) and by the in-plane radius (the larger semi-axis of the covariance's x-y
	 * block). One tab-separated line each, for scoring against truth outside: position, the detection's x-y
	 * covariance, both seeds, and each fit's a, b, theta (NaN when refused).
	 */
	private static void fitBench( final ToolContext ctx, final int from, final int to )
	{
		System.out.println( "fit\tt\tx\ty\tz\tcxx\tcxy\tcyy\tseed_b\tseed_p\tab\tbb\ttb\tap\tbp\ttp\tabl\tbbl\ttbl\tapl\tbpl\ttpl" );
		for ( int t = from; t <= to; t++ )
			for ( final double[] p : positions( ctx.getGraph(), t ) )
			{
				final double cxx = p[ 4 ], cxy = p[ 5 ], cyy = p[ 8 ];
				final double tr = cxx + cyy, det = cxx * cyy - cxy * cxy;
				final double seedP = Math.sqrt( tr / 2 + Math.sqrt( Math.max( 0, tr * tr / 4 - det ) ) );
				double seedB = 0;
				for ( final double e : symmetricEigen( cov( p ) ) )
					seedB = Math.max( seedB, Math.sqrt( Math.max( 0, e ) ) );
				final double[] c = { p[ 0 ], p[ 1 ], p[ 2 ] };
				if ( Boolean.parseBoolean( System.getProperty( "fitbench.debug", "false" ) ) )
					System.out.println( "diag\t" + java.util.Arrays.toString( AutoCorrectOrchestrator.contourDiagnostics( ctx, t, c, seedP ) ) );
				final org.mastodon.mamut.automahout.tool.EllipseFit.Ellipse fb = AutoCorrectOrchestrator.fitEllipseToContour( ctx, t, c, seedB );
				final org.mastodon.mamut.automahout.tool.EllipseFit.Ellipse fp = AutoCorrectOrchestrator.fitEllipseToContour( ctx, t, c, seedP );
				final org.mastodon.mamut.automahout.tool.EllipseFit.Ellipse fbl = AutoCorrectOrchestrator.fitEllipseToContour( ctx, t, c, seedB, true );
				final org.mastodon.mamut.automahout.tool.EllipseFit.Ellipse fpl = AutoCorrectOrchestrator.fitEllipseToContour( ctx, t, c, seedP, true );
				System.out.println( String.format( "fit\t%d\t%.4f\t%.4f\t%.4f\t%.5f\t%.5f\t%.5f\t%.3f\t%.3f\t%s\t%s\t%s\t%s", t, p[ 0 ], p[ 1 ], p[ 2 ],
						cxx, cxy, cyy, seedB, seedP, abt( fb ), abt( fp ), abt( fbl ), abt( fpl ) ) );
			}
	}

	private static String abt( final org.mastodon.mamut.automahout.tool.EllipseFit.Ellipse e )
	{
		return e == null ? "NaN\tNaN\tNaN\tNaN\tNaN" : String.format( "%.4f\t%.4f\t%.4f\t%.4f\t%.4f", e.x, e.y, e.a, e.b, e.theta );
	}

	/** Eigenvalues of a symmetric 3x3 matrix (Jacobi). */
	private static double[] symmetricEigen( final double[][] m )
	{
		final double[][] a = { m[ 0 ].clone(), m[ 1 ].clone(), m[ 2 ].clone() };
		for ( int sweep = 0; sweep < 50; sweep++ )
			for ( int p = 0; p < 2; p++ )
				for ( int q = p + 1; q < 3; q++ )
				{
					if ( Math.abs( a[ p ][ q ] ) < 1e-12 )
						continue;
					final double th = 0.5 * Math.atan2( 2 * a[ p ][ q ], a[ q ][ q ] - a[ p ][ p ] );
					final double c = Math.cos( th ), s = Math.sin( th );
					for ( int k = 0; k < 3; k++ )
					{
						final double akp = a[ k ][ p ], akq = a[ k ][ q ];
						a[ k ][ p ] = c * akp - s * akq;
						a[ k ][ q ] = s * akp + c * akq;
					}
					for ( int k = 0; k < 3; k++ )
					{
						final double apk = a[ p ][ k ], aqk = a[ q ][ k ];
						a[ p ][ k ] = c * apk - s * aqk;
						a[ q ][ k ] = s * apk + c * aqk;
					}
				}
		return new double[] { a[ 0 ][ 0 ], a[ 1 ][ 1 ], a[ 2 ][ 2 ] };
	}

	private static String row( final String kind, final int t, final double[] p, final double[] q, final boolean otherHeld,
			final int lp, final int lq )
	{
		final double dxy = Math.hypot( p[ 0 ] - q[ 0 ], p[ 1 ] - q[ 1 ] ), dz = Math.abs( p[ 2 ] - q[ 2 ] );
		final double m = Math.min( maha2( p, new double[] { q[ 0 ], q[ 1 ], q[ 2 ] } ), q[ 4 ] == 0 && q[ 8 ] == 0 ? Double.NaN
				: maha2( q, new double[] { p[ 0 ], p[ 1 ], p[ 2 ] } ) );
		return String.format( "%s\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%b\t%d\t%d\t%.2f\t%.2f", kind, t, dxy, dz, m,
				radius( p ), radius( q ), p[ 2 ], q[ 2 ], otherHeld, lp, lq, Math.sqrt( p[ 12 ] ), ( Math.sqrt( p[ 4 ] ) + Math.sqrt( p[ 8 ] ) ) / 2 )
				+ String.format( "\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.4f\t%.4f\t%.4f", p[ 0 ], p[ 1 ], p[ 2 ], q[ 0 ], q[ 1 ], q[ 2 ], p[ 4 ], p[ 5 ], p[ 8 ] );
	}

	/** Mahalanobis² of point x in the ellipsoid of detection p. */
	private static double maha2( final double[] p, final double[] x )
	{
		final double[][] inv = org.mastodon.mamut.automahout.tool.ShapeSplit3D.invert3( cov( p ) );
		if ( inv == null )
			return Double.NaN;
		return org.mastodon.mamut.automahout.tool.ShapeSplit3D.mahalanobis2Public( x, new double[] { p[ 0 ], p[ 1 ], p[ 2 ] }, inv );
	}

	/** The radius of a sphere of the detection's volume. */
	private static double radius( final double[] p )
	{
		final double det = org.mastodon.mamut.automahout.tool.ShapeSplit3D.det3Public( cov( p ) );
		return det > 0 ? Math.pow( det, 1.0 / 6 ) : 0;
	}

	private static double d2( final double[] a, final double[] b )
	{
		return Math.pow( a[ 0 ] - b[ 0 ], 2 ) + Math.pow( a[ 1 ] - b[ 1 ], 2 ) + Math.pow( a[ 2 ] - b[ 2 ], 2 );
	}

	/** The number of links (in + out) of each detection in {@code ps}, matched by position. */
	private static int[] linkCounts( final ModelGraph g, final int t, final List< double[] > ps )
	{
		final int[] out = new int[ ps.size() ];
		for ( final Spot s : g.vertices() )
			if ( s.getTimepoint() == t )
				for ( int i = 0; i < ps.size(); i++ )
				{
					final double[] p = ps.get( i );
					if ( Math.abs( p[ 0 ] - s.getDoublePosition( 0 ) ) < 1e-6 && Math.abs( p[ 1 ] - s.getDoublePosition( 1 ) ) < 1e-6
							&& Math.abs( p[ 2 ] - s.getDoublePosition( 2 ) ) < 1e-6 )
						out[ i ] = s.incomingEdges().size() + s.outgoingEdges().size();
				}
		return out;
	}

	private static double median( final List< Double > v )
	{
		if ( v.isEmpty() )
			return Double.NaN;
		final List< Double > c = new ArrayList<>( v );
		java.util.Collections.sort( c );
		return c.get( c.size() / 2 );
	}

	private static double below( final List< Double > v, final double bar )
	{
		if ( v.isEmpty() )
			return Double.NaN;
		int n = 0;
		for ( final double x : v )
			if ( x < bar )
				n++;
		return 100.0 * n / v.size();
	}

	private static int t( final double[] b )
	{
		return ( int ) b[ 3 ];
	}

	/** {x, y, z, t, cov(9)} of every spot at t. */
	private static List< double[] > positions( final ModelGraph g, final int t )
	{
		final List< double[] > out = new ArrayList<>();
		final double[][] cov = new double[ 3 ][ 3 ];
		for ( final Spot s : g.vertices() )
			if ( s.getTimepoint() == t )
			{
				s.getCovariance( cov );
				final double[] p = new double[ 13 ];
				s.localize( p );
				p[ 3 ] = t;
				for ( int i = 0; i < 9; i++ )
					p[ 4 + i ] = cov[ i / 3 ][ i % 3 ];
				out.add( p );
			}
		return out;
	}

	private static boolean contains( final List< double[] > list, final double[] p )
	{
		for ( final double[] q : list )
			if ( Math.abs( q[ 0 ] - p[ 0 ] ) < 1e-6 && Math.abs( q[ 1 ] - p[ 1 ] ) < 1e-6 && Math.abs( q[ 2 ] - p[ 2 ] ) < 1e-6 )
				return true;
		return false;
	}

	private static double[][] cov( final double[] p )
	{
		final double[][] c = new double[ 3 ][ 3 ];
		for ( int i = 0; i < 9; i++ )
			c[ i / 3 ][ i % 3 ] = p[ 4 + i ];
		return c;
	}

	/**
	 * Every division staying_divisions flags (its own test, divisionGeometry), with the branch each rule keeps:
	 * the daughter nearer the mother in the plane (the rule before dcd7f71) or in 3-D (since). Judged against the
	 * truth: a daughter continues the mother when the truth cell it holds follows the mother's (same cell or a
	 * daughter). Changes nothing.
	 */
	private static void stayJudge( final ToolContext ctx, final Map< Integer, List< Spot > > cells, final int from,
			final int to )
	{
		final ModelGraph g = ctx.getGraph();
		final Spot ref = g.vertexRef(), tref = g.vertexRef();
		// counts: flagged, real division (both continue), one continues, neither/unjudged; per rule: right, wrong
		int noMother = 0, noDaughter = 0, flagged = 0, real = 0, one = 0, neither = 0, differ = 0, oldRight = 0, newRight = 0, differOld = 0, differNew = 0;
		for ( final Spot mother : g.vertices() )
		{
			final int t = mother.getTimepoint();
			if ( t < from || t >= to || mother.outgoingEdges().size() != 2 )
				continue;
			final double[] m = record( mother );
			final double[][] kid = new double[ 2 ][];
			int k = 0;
			for ( final org.mastodon.mamut.model.Link e : mother.outgoingEdges() )
				kid[ k++ ] = record( e.getTarget( ref ) );
			final List< Double > radii = new ArrayList<>();
			for ( final Spot s : g.vertices() )
				if ( s.getTimepoint() == t )
					radii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
			final double fr = median( radii );
			final double[] geo = AutoCorrectOrchestrator.divisionGeometry( m, kid[ 0 ], kid[ 1 ], fr );
			if ( geo[ 0 ] >= AutoCorrectOrchestrator.DIVISION_MIN_NEAR_JUMP
					&& geo[ 1 ] >= AutoCorrectOrchestrator.DIVISION_MIN_ANGLE_DEG )
				continue;
			flagged++;
			final double xy0 = Math.pow( kid[ 0 ][ 0 ] - m[ 0 ], 2 ) + Math.pow( kid[ 0 ][ 1 ] - m[ 1 ], 2 );
			final double xy1 = Math.pow( kid[ 1 ][ 0 ] - m[ 0 ], 2 ) + Math.pow( kid[ 1 ][ 1 ] - m[ 1 ], 2 );
			final int oldKeep = xy0 <= xy1 ? 0 : 1, newKeep = d2( kid[ 0 ], m ) <= d2( kid[ 1 ], m ) ? 0 : 1;
			final Spot tm = heldCell( cells.getOrDefault( t, new ArrayList<>() ), m );
			final boolean[] cont = new boolean[ 2 ];
			if ( tm == null )
				noMother++;
			for ( int i = 0; i < 2; i++ )
			{
				final Spot td = heldCell( cells.getOrDefault( t + 1, new ArrayList<>() ), kid[ i ] );
				if ( tm != null && td == null )
					noDaughter++;
				if ( tm != null && td != null )
					for ( final org.mastodon.mamut.model.Link e : td.incomingEdges() )
						cont[ i ] |= e.getSource( tref ).equals( tm );
			}
			if ( oldKeep != newKeep )
				differ++;
			final String verdict;
			if ( cont[ 0 ] && cont[ 1 ] )
			{
				real++;
				verdict = "real division: either cut is wrong";
			}
			else if ( cont[ 0 ] || cont[ 1 ] )
			{
				one++;
				final int right = cont[ 0 ] ? 0 : 1;
				if ( oldKeep == right )
					oldRight++;
				if ( newKeep == right )
					newRight++;
				if ( oldKeep != newKeep )
				{
					if ( oldKeep == right )
						differOld++;
					else
						differNew++;
				}
				verdict = oldKeep == newKeep ? ( oldKeep == right ? "both rules keep the right one" : "both rules keep the wrong one" )
						: oldKeep == right ? "IN-PLANE rule right" : "3-D rule right";
			}
			else
			{
				neither++;
				verdict = "neither continues the mother (or unmatched)";
			}
			if ( oldKeep != newKeep )
				System.out.println( String.format( java.util.Locale.ROOT,
						"  t=%d mother (%.1f, %.1f, %.1f) r=%.2f; kept in-plane (%.1f, %.1f, %.1f), kept 3-D (%.1f, %.1f, %.1f): %s",
						t, m[ 0 ], m[ 1 ], m[ 2 ], fr, kid[ oldKeep ][ 0 ], kid[ oldKeep ][ 1 ], kid[ oldKeep ][ 2 ],
						kid[ newKeep ][ 0 ], kid[ newKeep ][ 1 ], kid[ newKeep ][ 2 ], verdict ) );
		}
		g.releaseRef( ref );
		g.releaseRef( tref );
		System.out.println( String.format( "stayjudge t=%d..%d: %d flagged; %d real divisions, %d with one branch continuing, "
				+ "%d neither/unmatched. Of the %d with one continuing: in-plane rule keeps the right branch %d, 3-D rule %d. "
				+ "Rules disagree on %d: in-plane right %d, 3-D right %d. Mother on no truth cell: %d; daughters on no truth "
				+ "cell (mother matched): %d.", from, to, flagged, real, one, neither, one,
				oldRight, newRight, differ, differOld, differNew, noMother, noDaughter ) );
	}

	/** One detection as {@link #positions} gives it: x, y, z, t, then its covariance. */
	private static double[] record( final Spot s )
	{
		final double[][] cov = new double[ 3 ][ 3 ];
		s.getCovariance( cov );
		final double[] p = new double[ 13 ];
		s.localize( p );
		p[ 3 ] = s.getTimepoint();
		for ( int i = 0; i < 9; i++ )
			p[ 4 + i ] = cov[ i / 3 ][ i % 3 ];
		return p;
	}

	/** The truth cell nearest this detection's centre among those its ellipsoid holds; null when none. */
	private static Spot heldCell( final List< Spot > cells, final double[] p )
	{
		Spot best = null;
		double bd = Double.POSITIVE_INFINITY;
		final double[] cp = new double[ 3 ];
		for ( final Spot c : cells )
		{
			c.localize( cp );
			if ( !AutoCorrectOrchestrator.insideSpot( p, cov( p ), cp ) )
				continue;
			final double d = Math.pow( cp[ 0 ] - p[ 0 ], 2 ) + Math.pow( cp[ 1 ] - p[ 1 ], 2 ) + Math.pow( cp[ 2 ] - p[ 2 ], 2 );
			if ( d < bd )
			{
				bd = d;
				best = c;
			}
		}
		return best;
	}

	private static boolean heldByAny( final ModelGraph g, final int t, final Spot cell )
	{
		final double[] cp = new double[ 3 ], sp = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		cell.localize( cp );
		for ( final Spot s : g.vertices() )
			if ( s.getTimepoint() == t )
			{
				s.localize( sp );
				s.getCovariance( cov );
				if ( AutoCorrectOrchestrator.insideSpot( sp, cov, cp ) )
					return true;
			}
		return false;
	}
}
