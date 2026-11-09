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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.SwingUtilities;

import org.elephant.setting.main.ElephantMainSettings;
import org.elephant.setting.main.ElephantMainSettingsManager;
import org.elephant.setting.server.ElephantServerSettingsManager;
import org.json.JSONObject;
import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.automahout.autocorrect.QuestionOracle;
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.plugin.BatchPreflight;
import org.mastodon.mamut.automahout.tool.AutomahoutPrefs;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.impl.AutonomousTrackingTool;
import org.mastodon.mamut.automahout.tool.remote.DetectorTrial;
import org.mastodon.mamut.io.ProjectCreator;
import org.mastodon.mamut.io.ProjectSaver;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.mastodon.views.bdv.SharedBigDataViewerData;
import org.scijava.Context;

import bdv.viewer.Source;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * One benchmark run (docs/paper/benchmark-protocol.md): opens a BigDataViewer dataset as a new Mastodon project,
 * runs Automahout's autonomous tracking with the pre-flight a user gets by pressing Start unchanged, saves the
 * project, writes the CTC result files and {@code run-info.json} (settings, versions, timings) into the output
 * directory.
 *
 * <pre>
 * java ... BenchmarkRunner xml=&lt;movie.xml&gt; out=&lt;dir&gt; dataset=&lt;ELEPHANT dataset name&gt; run=&lt;id&gt;
 *          [condition=A] [server=http://localhost:8080/]
 * </pre>
 *
 * <p>Condition A: fully autonomous -- no questions, the detector chosen from the image. Condition AO: the same,
 * but the merge and division questions are asked and the CTC truth answers them ({@link GroundTruthOracle};
 * {@code gt=<TRA folder>}); how many were asked is in run-info.json. Conditions E and P are A run with something
 * taken away, set on the command line by {@code scripts/paper/run_benchmark.sh}: E without the pretrained
 * detectors (no remote tool server: ELEPHANT's adaptive tracking), P with every correction pass switched off
 * ({@code -Dautomahout.passes.disable=all}). Both are recorded in run-info.json. The ELEPHANT settings are
 * a fresh style made from ELEPHANT's defaults, with a model name of its own (so each run trains from ELEPHANT's
 * initial model), and the data-dependent values set by the pre-flight from the image. The user's own ELEPHANT
 * settings are not changed on disk: the style lives only in this process.</p>
 */
public final class BenchmarkRunner
{
	static
	{
		net.imagej.patcher.LegacyInjector.preinit();
	}

	private BenchmarkRunner()
	{}

	/**
	 * System property: the path of the person's ELEPHANT server settings ({@code ~/.mastodon/Plugins/Elephant/
	 * elephant_server_settings.yaml}). The entry whose server URL is this run's lends its RabbitMQ connection, so the
	 * server's progress messages reach the log. A run isolates the user's settings (user.home is its own); this takes
	 * only that connection, in memory, and records neither the user name nor the password.
	 */
	public static final String PROPERTY_ELEPHANT_SERVER_SETTINGS = "automahout.elephant.serverSettings";

	/** System property: {@code true} shows the plugin's log window (with Stop) during the run. */
	public static final String PROPERTY_LOG_WINDOW = "automahout.logWindow";

	public static void main( final String[] args ) throws Exception
	{
		final Map< String, String > a = new HashMap<>();
		for ( final String arg : args )
		{
			final int eq = arg.indexOf( '=' );
			if ( eq > 0 )
				a.put( arg.substring( 0, eq ), arg.substring( eq + 1 ) );
		}
		for ( final String required : new String[] { "out", "dataset", "run" } )
			if ( !a.containsKey( required ) )
				throw new IllegalArgumentException( "missing " + required + "=...; see the class documentation" );
		final String condition = a.getOrDefault( "condition", "A" );
		if ( !java.util.Arrays.asList( "A", "AO", "E", "P" ).contains( condition ) )
			throw new IllegalArgumentException( "condition " + condition + " is not one of A, AO, E, P" );
		if ( condition.equals( "AO" ) && !a.containsKey( "gt" ) )
			throw new IllegalArgumentException( "condition AO needs gt=<the CTC TRA truth folder>" );
		if ( !a.containsKey( "xml" ) && !a.containsKey( "project" ) )
			throw new IllegalArgumentException( "missing xml=... (a new project) or project=... (a saved one)" );
		final Path out = Paths.get( a.get( "out" ) );
		Files.createDirectories( out );
		// Isolation, before any Automahout settings class loads: the code's defaults, not the user's tuning.
		final JSONObject isolation = isolate( out );
		final String server = a.getOrDefault( "server", "http://localhost:8080/" );
		final JSONObject info = new JSONObject();
		info.put( "condition", condition ).put( "run", a.get( "run" ) ).put( "xml", a.get( "xml" ) ).put( "project", a.get( "project" ) )
				.put( "elephant_dataset", a.get( "dataset" ) ).put( "started", Instant.now().toString() );
		info.put( "versions", versions() );
		if ( a.containsKey( "commit" ) )
			info.getJSONObject( "versions" ).put( "automahout_commit", a.get( "commit" ) );
		info.put( "isolation", isolation );
		info.put( "elephant_server", new JSONObject().put( "url", server ).put( "gpus", httpGet( server + "state/gpus" ) ) );

		int exit = 0;
		final PrintWriter log = new PrintWriter( Files.newBufferedWriter( out.resolve( "run.log" ), StandardCharsets.UTF_8 ),
				true );
		final Context context = new Context();
		ProjectModel pm = null;
		try
		{
			// A short run continues a saved run: its project (the timepoints before start= are its history)
			// and a copy of its model (model_from=); only start..end are tracked. See scripts/paper/run_drift.sh.
			pm = a.containsKey( "project" ) ? org.mastodon.mamut.io.ProjectLoader.open( a.get( "project" ), context )
					: ProjectCreator.createProjectFromBdvFile( new File( a.get( "xml" ) ), context );
			final int start = Integer.parseInt( a.getOrDefault( "start", "-1" ) );
			final int end = Integer.parseInt( a.getOrDefault( "end", "-1" ) );
			final ProjectModel project = pm;
			final MamutViewBdv[] view = new MamutViewBdv[ 1 ];
			SwingUtilities.invokeAndWait( () -> view[ 0 ] = project.getWindowManager().createBigDataViewer() );
			final ViewerPanel viewer = view[ 0 ].getViewerPanelMamut();
			final ToolContext ctx = new ToolContext( pm, viewer );
			// The plugin's own log window, with its Stop button, when asked for (scripts/demo/run_demo.sh): a
			// demo is watched, a benchmark run is not.
			final org.mastodon.mamut.automahout.plugin.AutomahoutLogWindow[] window =
					new org.mastodon.mamut.automahout.plugin.AutomahoutLogWindow[ 1 ];
			if ( Boolean.getBoolean( PROPERTY_LOG_WINDOW ) )
			{
				SwingUtilities.invokeAndWait( () -> {
					window[ 0 ] = new org.mastodon.mamut.automahout.plugin.AutomahoutLogWindow();
					window[ 0 ].setStopCallback( ctx::requestCancel );
					window[ 0 ].show();
					// Side by side, the image left and the log right: on a virtual display without a window
					// manager every window opens at the corner, on top of the others, and a recording shows one.
					final java.awt.Rectangle screen =
							java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
					final int left = ( int ) ( screen.width * 0.68 );
					view[ 0 ].getFrame().setBounds( screen.x, screen.y, left, screen.height );
					window[ 0 ].setBounds( new java.awt.Rectangle( screen.x + left, screen.y, screen.width - left,
							screen.height ) );
				} );
				window[ 0 ].enableStop();
			}
			ctx.setLogCallback( line -> {
				log.println( line );
				System.out.println( line );
				if ( window[ 0 ] != null )
					window[ 0 ].onLogMessage( line );
			} );

			// ELEPHANT: this process's own server URL and a fresh style from ELEPHANT's defaults.
			ElephantServerSettingsManager.getInstance().getForwardDefaultStyle().setServerURL( server );
			// The server's RabbitMQ, from the person's own ELEPHANT settings when the script names them: its messages
			// (epochs, losses, frames) are relayed to the log, as the GUI shows them.
			final String serverSettings = System.getProperty( PROPERTY_ELEPHANT_SERVER_SETTINGS, "" ).trim();
			if ( !serverSettings.isEmpty() )
				info.put( "rabbitmq", org.mastodon.mamut.automahout.elephant.RabbitMqFromSettings.apply( Paths.get( serverSettings ), server,
						ElephantServerSettingsManager.getInstance().getForwardDefaultStyle(), log::println ) );
			final ElephantMainSettingsManager styles = ElephantMainSettingsManager.getInstance();
			final ElephantMainSettings style = styles.duplicate( ElephantMainSettings.defaultStyle() );
			final String id = "automahout-bench-" + a.get( "run" );
			styles.rename( style, id );
			style.setDatasetName( a.get( "dataset" ) );
			style.setDetectionModelName( id + "_detection.pth" );
			style.setDetectionLogName( id + "_detection_log" );
			style.setFlowModelName( id + "_flow.pth" );
			style.setFlowLogName( id + "_flow_log" );
			style.setLogFileName( id + ".log" );
			styles.setSelectedStyle( style );
			// ELEPHANT's pretrained "versatile" model was trained on CTC data: this run's model starts from
			// random weights instead (decided 2026-10-12). A run that cannot create it does not start.
			final org.mastodon.mamut.automahout.elephant.ElephantIntegration elephant =
					new org.mastodon.mamut.automahout.elephant.ElephantIntegration( ctx );
			if ( a.containsKey( "model_from" ) )
			{
				// The saved run's model, copied to this run's name so training here never changes it.
				final String own = style.getDetectionModelName();
				style.setDetectionModelName( a.get( "model_from" ) );
				final File copy = out.resolve( "model_from.pth" ).toFile();
				if ( !elephant.init() || !elephant.snapshotDetectionModel( copy ) )
					throw new IllegalStateException( "could not download the model " + a.get( "model_from" ) );
				style.setDetectionModelName( own );
				if ( !elephant.restoreDetectionModel( copy ) )
					throw new IllegalStateException( "could not create " + own + " from " + a.get( "model_from" ) );
				info.put( "elephant_model_init", "copy of " + a.get( "model_from" ) );
			}
			else
			{
				if ( !elephant.init() || !elephant.initializeDetectionModelFromRandomWeights() )
					throw new IllegalStateException( "could not create the run's ELEPHANT model from random weights" );
				info.put( "elephant_model_init", "random weights (seg/reset, url=null)" );
			}
			if ( start >= 0 )
			{
				// Everything from start on is redone; what lies before it is the saved run's history.
				final org.mastodon.mamut.model.ModelGraph g = pm.getModel().getGraph();
				final java.util.List< org.mastodon.mamut.model.Spot > clear = new java.util.ArrayList<>();
				for ( final org.mastodon.mamut.model.Spot sp : g.vertices() )
					if ( sp.getTimepoint() >= start )
						clear.add( g.vertexRef().refTo( sp ) );
				for ( final org.mastodon.mamut.model.Spot sp : clear )
					org.mastodon.mamut.automahout.tool.GraphEdits.removeSpot( ctx, g, sp,
							org.mastodon.mamut.automahout.tool.GraphEdits.Reason.BULK );
				org.mastodon.mamut.automahout.tool.RunRange.set( ctx, start, end );
				ctx.setSharedData( org.mastodon.mamut.automahout.tool.impl.ElephantTool.SHARED_START_TIMEPOINT_OVERRIDE, start );
				info.put( "range", new JSONObject().put( "start", start ).put( "end", end ).put( "cleared_spots", clear.size() ) );
			}

			// Condition AO: the questions are asked, and the truth answers them.
			GroundTruthOracle oracle = null;
			if ( condition.equals( "AO" ) )
			{
				final Source< ? > s0 = pm.getSharedBdvData().getSources().get( 0 ).getSpimSource();
				final AffineTransform3D w = new AffineTransform3D();
				s0.getSourceTransform( pm.getMinTimepoint(), 0, w );
				oracle = new GroundTruthOracle( Paths.get( a.get( "gt" ) ), w );
				ctx.setSharedData( QuestionOracle.SHARED_KEY, oracle );
			}

			final long t0 = System.nanoTime();
			final String guidance = BatchPreflight.run( ctx, start < 0, oracle != null, false );
			// What the vision model saw: the plugin shows it in the set-up dialog, which a demo does not have, so
			// it goes to the log window (and the file it was always written to).
			if ( window[ 0 ] != null )
			{
				final Object movie = ctx.getSharedData( org.mastodon.mamut.automahout.tool.impl.AnalyzeVideoTool.SHARED_DATA_KEY );
				if ( movie != null && !movie.toString().trim().isEmpty() )
					ctx.log( "  What the vision model saw:\n" + movie.toString().replaceAll( "(?s)<!--.*?-->\\s*", "" ).trim() );
			}
			final long t1 = System.nanoTime();
			final Map< String, Object > params = new HashMap<>();
			if ( !guidance.trim().isEmpty() )
				params.put( AutonomousTrackingTool.PARAM_USER_GUIDANCE, guidance.trim() );
			// steps=fixed: the window loop and the whole-movie correction with fixed parameters, no agent choosing
			// the steps -- a comparison then differs only in the code. The agent re-ran adaptive_track on its own in
			// one of three otherwise identical short runs.
			final String result = a.containsKey( "samples" ) ? runSampled( ctx, a.get( "samples" ) )
					: "fixed".equals( a.get( "steps" ) ) ? runFixedSteps( ctx, start, end )
					: new AutonomousTrackingTool().execute( ctx, params );
			info.put( "steps", a.getOrDefault( "steps", "agent" ) );
			final long t2 = System.nanoTime();
			info.put( "guidance", guidance ).put( "result", result );
			info.put( "seconds", new JSONObject().put( "preflight", ( t1 - t0 ) / 1e9 ).put( "tracking", ( t2 - t1 ) / 1e9 ) );
			info.put( "detector", String.valueOf( ctx.getSharedData( DetectorTrial.SHARED_CHOICE ) ) );
			if ( oracle != null )
				info.put( "oracle", new JSONObject().put( "split_questions", oracle.splitQuestions.get() )
						.put( "answered_two_cells", oracle.twoCellAnswers.get() )
						.put( "division_questions", oracle.divisionQuestions.get() )
						.put( "answered_real_division", oracle.divisionYes.get() )
						.put( "unanswered", oracle.unanswered.get() ) );
			info.put( "elephant_settings", gettersOf( styles.getSelectedStyle() ) );
			info.put( "automahout_settings", gettersOf( new AutomahoutSettings() ) );

			ProjectSaver.saveProject( out.resolve( "project.mastodon" ).toFile(), pm );
			final SharedBigDataViewerData bdv = pm.getSharedBdvData();
			final Source< ? > src = bdv.getSources().get( 0 ).getSpimSource();
			final RandomAccessibleInterval< ? > rai = src.getSource( pm.getMinTimepoint(), 0 );
			final long[] dims = rai.numDimensions() > 2 && rai.dimension( 2 ) > 1
					? new long[] { rai.dimension( 0 ), rai.dimension( 1 ), rai.dimension( 2 ) }
					: new long[] { rai.dimension( 0 ), rai.dimension( 1 ) };
			final AffineTransform3D toWorld = new AffineTransform3D();
			src.getSourceTransform( pm.getMinTimepoint(), 0, toWorld );
			final CtcExport.Result exported = CtcExport.export( pm.getModel(), dims, toWorld, pm.getMinTimepoint(),
					pm.getMaxTimepoint(), out.resolve( "RES" ) );
			info.put( "export", exported.toString() );
			if ( result != null && result.startsWith( "Error:" ) )
				exit = 2;
		}
		catch ( final Throwable e )
		{
			e.printStackTrace( log );
			e.printStackTrace();
			info.put( "error", e.toString() );
			exit = 1;
		}
		finally
		{
			info.put( "finished", Instant.now().toString() );
			Files.write( out.resolve( "run-info.json" ), info.toString( 1 ).getBytes( StandardCharsets.UTF_8 ) );
			log.close();
			System.exit( exit );
		}
	}

	/**
	 * adaptive_track over start..end (the whole movie when start is -1), then auto.correct over the movie, with
	 * the parameters the agent chose in the short Fluo-N3DH-CE 01 runs of 2026-10-23. The language model stays
	 * connected for what adaptive_track asks of it (the background negatives).
	 */
	/**
	 * samples=t1,t2,...: each listed frame tracked on its own, in order, with the fixed parameters and one model
	 * throughout -- detect, correct, fit, train, then the next frame -- and no whole-movie correction. For judging
	 * detection (its size and shape as the cells change) on a few frames far apart, in a fraction of a full run's
	 * time; linking is not judged.
	 */
	static String runSampled( final ToolContext ctx, final String samples ) throws Exception
	{
		final StringBuilder out = new StringBuilder();
		boolean first = true;
		for ( final String t : samples.split( "," ) )
		{
			final int tp = Integer.parseInt( t.trim() );
			ctx.log( "[sampled] frame t=" + tp );
			out.append( runFixedSteps( ctx, tp, tp, false ) ).append( '\n' );
			// After the first frame the model is trained: later frames are not a fresh start, so the warm-up asks
			// whether the model already detects, as it does on a model it did not create (continuous tracking warms
			// up once). Left set, every sampled frame re-seeded from scratch; on CE 01 from the VLM's reading of
			// t=60, which it cannot count (16 nuclei of 10 um where there are 43 of 5).
			if ( first )
				ctx.setSharedData( org.mastodon.mamut.automahout.elephant.ElephantIntegration.SHARED_MODEL_FROM_RANDOM_WEIGHTS,
						Boolean.FALSE );
			first = false;
		}
		return out.toString();
	}

	static String runFixedSteps( final ToolContext ctx, final int start, final int end ) throws Exception
	{
		return runFixedSteps( ctx, start, end, true );
	}

	static String runFixedSteps( final ToolContext ctx, final int start, final int end, final boolean correct ) throws Exception
	{
		final AutomahoutSettings st = new AutomahoutSettings();
		ctx.setSharedData( org.mastodon.mamut.automahout.tool.impl.ElephantTool.SHARED_VLM_BACKEND,
				AutonomousTrackingTool.buildVlmBackend( st ) );
		ctx.setSharedData( org.mastodon.mamut.automahout.tool.impl.ElephantTool.SHARED_VLM_MODEL, st.getVlmModel() );
		final org.mastodon.mamut.automahout.tool.ToolRegistry tools = org.mastodon.mamut.automahout.tool.ToolRegistry.createDefault();
		final Map< String, Object > track = new LinkedHashMap<>();
		track.put( "completeness_target", 0.65 );
		track.put( "refine_pass", true );
		track.put( "cold_start_spots_per_tp", 250 );
		track.put( "dedup_factor", 0.4 );
		track.put( "min_spots_per_tp", 5 );
		track.put( "window_size", 10 );
		track.put( "border_margin", 0 );
		track.put( "n_crops", 5 );
		track.put( "max_aspect_ratio", 6.0 );
		track.put( "merge_overlap_ratio", 0.5 );
		track.put( "refine_iterations", 2 );
		track.put( "n_iterations", 2 );
		track.put( "min_intensity_ratio", 0.25 );
		track.put( "correct_per_window", true );
		track.put( "n_seeds_per_tp", 20 );
		track.put( "long_link_factor", 3.0 );
		track.put( "track_intensity_ratio", 0 );
		track.put( "division_link_factor", 2.5 );
		track.put( "consistency_per_batch", true );
		track.put( "min_size", 0 );
		track.put( "train_timeout_sec", 900 );
		track.put( "n_annot_per_window", 2 );
		if ( start >= 0 )
			track.put( "start_timepoint", start );
		if ( end >= 0 )
			track.put( "end_timepoint", end );
		final Map< String, Object > correction = new LinkedHashMap<>();
		correction.put( "long_link_factor", 3.0 );
		correction.put( "min_branch_length", 0 );
		correction.put( "anisotropy_threshold", 0 );
		correction.put( "division_link_factor", 2.5 );
		correction.put( "t", -1 );
		correction.put( "merge_overlap_ratio", 0.5 );
		correction.put( "refit", false );
		correction.put( "fill_gaps", true );
		final String r1 = tools.get( "adaptive_track" ).get().execute( ctx, track );
		ctx.log( "[fixed steps] adaptive_track: " + r1 );
		if ( !correct )
			return r1;
		final String r2 = tools.get( "auto.correct" ).get().execute( ctx, correction );
		ctx.log( "[fixed steps] auto.correct: " + r2 );
		return r1 + "\n" + r2;
	}

	/** Preference keys copied from the user's own settings into the run's: how to reach the language model. */
	public static final String[] LLM_KEYS = { "vlmBackend", "vlmBaseUrl", "vlmAwsRegion", "vlmModel", "vlmApiKey" };

	/**
	 * Requires {@code -Dautomahout.prefs.root=...} (see {@link AutomahoutPrefs}): empties that node, then copies
	 * only {@link #LLM_KEYS} from the user's settings, or from the environment
	 * ({@link AutomahoutPrefs#llmEnvName}), which wins. Every other setting is the code's default, except that the
	 * movie description is written into {@code out}. Reports what it did, without the key's value.
	 */
	static JSONObject isolate( final Path out ) throws java.util.prefs.BackingStoreException
	{
		if ( !AutomahoutPrefs.isolated() )
			throw new IllegalStateException( "run with -D" + AutomahoutPrefs.PROPERTY_ROOT
					+ "=<a node of its own>: a benchmark run must not read the user's tuned settings" );
		final String root = System.getProperty( AutomahoutPrefs.PROPERTY_ROOT ).trim();
		if ( java.util.prefs.Preferences.userRoot().nodeExists( root ) )
			java.util.prefs.Preferences.userRoot().node( root ).removeNode();
		final java.util.prefs.Preferences user = java.util.prefs.Preferences.userNodeForPackage( AutomahoutSettings.class );
		final java.util.prefs.Preferences mine = AutomahoutPrefs.node( AutomahoutSettings.class );
		final JSONObject copied = new JSONObject();
		for ( final String k : LLM_KEYS )
		{
			final String env = System.getenv( AutomahoutPrefs.llmEnvName( k ) );
			final String v = env != null && !env.isEmpty() ? env : user.get( k, null );
			if ( v == null )
				continue;
			mine.put( k, v );
			copied.put( k, ( k.toLowerCase().contains( "key" ) ? "(set, not recorded)" : v )
					+ ( v == env ? " [" + AutomahoutPrefs.llmEnvName( k ) + "]" : "" ) );
		}
		// The movie description goes with the run's results, not to docs/video_description.md relative to the
		// working directory (a repository's own file there was overwritten by every run started from it).
		final String description = out.toAbsolutePath().resolve( "video_description.md" ).toString();
		mine.put( "vlmOutputFile", description );
		mine.flush();
		return new JSONObject().put( "prefs_root", root ).put( "copied_from_user", copied )
				.put( "video_description_file", description )
				.put( "user_home", System.getProperty( "user.home" ) )
				.put( "remote_tool_servers", System.getProperty( "automahout.remote.servers", "" ) )
				.put( "passes_disabled", System.getProperty( "automahout.passes.disable", "" ) )
				.put( "pretrained_seeds_only", Boolean.getBoolean(
						org.mastodon.mamut.automahout.tool.remote.DetectorTrial.PROPERTY_SEEDS_ONLY ) );
	}

	/** Every no-argument getter's value: the full settings record the checklist asks for. */
	static JSONObject gettersOf( final Object o )
	{
		final Map< String, Object > m = new LinkedHashMap<>();
		for ( final Method meth : o.getClass().getMethods() )
		{
			final String n = meth.getName();
			if ( meth.getParameterCount() != 0 || meth.getDeclaringClass() == Object.class
					|| !( n.startsWith( "get" ) || n.startsWith( "is" ) ) )
				continue;
			final Class< ? > r = meth.getReturnType();
			if ( !( r.isPrimitive() || r == String.class || Number.class.isAssignableFrom( r ) || r == Boolean.class ) )
				continue;
			try
			{
				m.put( n, meth.invoke( o ) );
			}
			catch ( final Exception e )
			{
				m.put( n, "unreadable: " + e );
			}
		}
		return new JSONObject( new java.util.TreeMap<>( m ) );
	}

	/** Implementation versions of the components, from their jars, and the Automahout source commit. */
	static JSONObject versions()
	{
		final JSONObject v = new JSONObject();
		v.put( "java", System.getProperty( "java.version" ) );
		v.put( "os", System.getProperty( "os.name" ) + " " + System.getProperty( "os.version" ) + " " + System.getProperty( "os.arch" ) );
		for ( final Class< ? > c : new Class< ? >[] { ProjectModel.class, ElephantMainSettings.class, Context.class,
				ViewerPanel.class } )
			v.put( c.getName(), jarOf( c ) );
		v.put( "automahout_commit", gitCommit() );
		return v;
	}

	private static String jarOf( final Class< ? > c )
	{
		try
		{
			final String p = c.getProtectionDomain().getCodeSource().getLocation().getPath();
			return p.substring( p.lastIndexOf( '/' ) + 1 );
		}
		catch ( final Exception e )
		{
			return "?";
		}
	}

	private static String gitCommit()
	{
		try
		{
			final Process p = new ProcessBuilder( "git", "describe", "--always", "--dirty", "--tags" ).redirectErrorStream( true ).start();
			final String s = readAll( p.getInputStream() ).trim();
			p.waitFor();
			return s;
		}
		catch ( final IOException | InterruptedException e )
		{
			return "unknown";
		}
	}

	private static String readAll( final java.io.InputStream in ) throws IOException
	{
		final java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
		final byte[] buf = new byte[ 8192 ];
		for ( int n; ( n = in.read( buf ) ) > 0; )
			b.write( buf, 0, n );
		in.close();
		return new String( b.toByteArray(), StandardCharsets.UTF_8 );
	}

	private static String httpGet( final String url )
	{
		try
		{
			final HttpURLConnection c = ( HttpURLConnection ) new URL( url ).openConnection();
			c.setConnectTimeout( 5000 );
			c.setReadTimeout( 10000 );
			return readAll( c.getInputStream() );
		}
		catch ( final IOException e )
		{
			return "unreachable: " + e.getMessage();
		}
	}
}
