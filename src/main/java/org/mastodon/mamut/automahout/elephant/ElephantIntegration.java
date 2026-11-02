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
package org.mastodon.mamut.automahout.elephant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mastodon.kdtree.IncrementalNearestNeighborSearch;
import org.mastodon.mamut.automahout.tool.GraphWatch;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.ExpectedCellCount;
import org.mastodon.mamut.automahout.tool.PhaseTimings;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.automahout.tool.CleanupToggles;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.model.tag.TagSetStructure.TagSet;
import org.mastodon.spatial.SpatialIndex;
import org.mastodon.spatial.SpatioTemporalIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RealPoint;
import org.elephant.setting.main.ElephantMainSettings;
import org.elephant.setting.main.ElephantMainSettingsManager;
import org.elephant.setting.server.ElephantServerSettings;
import org.elephant.setting.server.ElephantServerSettingsManager;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestInstance;

import bdv.viewer.ViewerPanel;

/**
 * Integration with ELEPHANT deep learning server for spot detection and linking.
 *
 * <p>
 * Communicates with ELEPHANT server via HTTP REST API to:
 * <ul>
 *   <li>Send timepoint images for spot detection (POST /seg/predict)</li>
 *   <li>Receive detected spot coordinates as JSON</li>
 *   <li>Add detected spots to the Mastodon graph</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Configuration:</b> ELEPHANT server URL and credentials must be configured
 * in Mastodon preferences (Edit → Preferences → ELEPHANT Server Settings).
 * </p>
 */
public class ElephantIntegration {

	private static final Logger log = LoggerFactory.getLogger( ElephantIntegration.class );

	private final ToolContext context;

	private String elephantServerUrl;

	private boolean initialized = false;

	/**
	 * Unirest's default socket timeout is 60 s, which silently cuts long-running requests — most
	 * importantly {@code /seg/train}, whose blocking call routinely exceeds 60 s. The severed socket
	 * makes the server abort training and return {@code completed:false}, so the model never adapts. We
	 * therefore route all ELEPHANT requests through a DEDICATED {@link UnirestInstance} with a 24 h
	 * socket timeout (the per-call {@code future.get(timeoutMs)} in {@link #postBlocking} still enforces
	 * the real, shorter bound). A dedicated instance is used rather than the global {@code Unirest}
	 * singleton because the global config freezes after its first request — and the separate ELEPHANT
	 * plugin may have already used it — so a global setter would silently no-op, leaving the 60 s default.
	 */
	private static final int SOCKET_TIMEOUT_MS = 24 * 60 * 60 * 1000;

	private static final int CONNECT_TIMEOUT_MS = 30 * 1000;

	/**
	 * When {@code /seg/train}'s HTTP response is severed by the nginx 60 s gateway timeout, training
	 * completion is awaited via RabbitMQ. If the terminal "DONE" marker is missed (e.g. the broker
	 * connection drops during the long tail), this much continuous silence in the training-log stream —
	 * after progress had been streaming — is treated as completion, rather than hanging until the full
	 * training timeout. ELEPHANT streams per-epoch / per-log-interval, so a gap this long means the job
	 * finished. Conservative on purpose (epoch-to-epoch gaps here are seconds).
	 */
	private static final long TRAIN_LOG_QUIET_MS = 120_000L;

	/**
	 * Bounded wait for a single {@code /seg/predict} attempt — just past the nginx 60 s gateway
	 * timeout, so the common case (server idle, prediction fast) returns without ever opening a
	 * RabbitMQ connection. {@code /seg/predict} is called once per timepoint (often many times per
	 * window), so — unlike training — a monitor is not started eagerly on every call.
	 */
	private static final long PREDICT_TIMEOUT_MS = 65_000L;

	/**
	 * Cap on how long {@code /seg/predict} will wait for the server to leave {@code TrainState.WAIT}
	 * (i.e. finish a still-running {@code /seg/train}/{@code /seg/update} request) before giving up.
	 * {@code /seg/predict} polls-and-blocks server-side exactly like {@code /seg/update} while the
	 * server is busy, logging {@code "waiting @/seg/predict"} once per second — invisible to the
	 * client without the monitor relay below.
	 */
	private static final long PREDICT_WAIT_TIMEOUT_MS = 10 * 60 * 1000L;

	/** Bound on an abort's acknowledgement: the server answers {@code /state/process} at once. */
	private static final long STATE_TIMEOUT_MS = 30_000L;

	/**
	 * Socket timeout (longest silence between packets, not the whole transfer) for a model download or
	 * reset: under the client's 24 h, so a connection dropped mid-transfer fails in minutes instead of a day.
	 */
	private static final int TRANSFER_SOCKET_TIMEOUT_MS = 5 * 60 * 1000;

	private static volatile UnirestInstance httpInstance;

	/** Lazily creates the dedicated, long-timeout Unirest instance for all ELEPHANT requests. */
	private static UnirestInstance http()
	{
		UnirestInstance instance = httpInstance;
		if ( instance == null )
		{
			synchronized ( ElephantIntegration.class )
			{
				instance = httpInstance;
				if ( instance == null )
				{
					instance = Unirest.spawnInstance();
					instance.config().socketTimeout( SOCKET_TIMEOUT_MS ).connectTimeout( CONNECT_TIMEOUT_MS );
					httpInstance = instance;
					log.info( "ELEPHANT Unirest instance created: socket={} ms, connect={} ms",
							SOCKET_TIMEOUT_MS, CONNECT_TIMEOUT_MS );
				}
			}
		}
		return instance;
	}

	// -----------------------------------------------------------------------

	/**
	 * Creates a new ELEPHANT integration with the given tool context.
	 *
	 * @param toolContext the tool context providing model, graph, and viewer access
	 */
	public ElephantIntegration( final ToolContext toolContext )
	{
		this.context = toolContext;
	}

	// -----------------------------------------------------------------------

	/**
	 * Initializes the ELEPHANT integration by reading server settings.
	 *
	 * @return {@code true} if initialization succeeded
	 */
	public boolean init()
	{
		if ( initialized )
			return true;

		try
		{
			final ElephantServerSettings serverSettings =
					ElephantServerSettingsManager.getInstance().getForwardDefaultStyle();
			elephantServerUrl = serverSettings.getServerURL();

			log.info( "ELEPHANT integration initialized (server: {})", elephantServerUrl );
			initialized = true;
			return true;
		}
		catch ( final Exception e )
		{
			log.error( "Failed to initialize ELEPHANT integration", e );
			return false;
		}
	}

	// -----------------------------------------------------------------------

	/**
	 * Runs ELEPHANT spot detection on whatever timepoint the shared BDV viewer currently displays.
	 * Detected spots are added directly to the Mastodon graph.
	 *
	 * <p>Only safe for genuinely interactive, single-shot use (the user invokes this while looking at a
	 * specific frame). Automated per-timepoint loops must call {@link #predictSpots(int)} with the
	 * timepoint they just set instead — see that method's docs for why.</p>
	 *
	 * @return {@code true} if prediction succeeded and spots were processed
	 */
	public boolean predictSpots()
	{
		return predictSpots( context.requireViewer().state().getCurrentTimepoint() );
	}

	/**
	 * As {@link #predictSpots()}, but for an explicit {@code timepoint} rather than re-reading the shared
	 * BDV viewer's current timepoint.
	 *
	 * <p>Automated per-window/per-timepoint loops call {@code setTimepoint(viewer, t, ...)} then predict
	 * immediately after. The BDV viewer is typically in a navigation group together with an open
	 * TrackScheme (or another BDV), so if the user manually navigates while a long-running autonomous
	 * pipeline is executing (e.g. clicking a spot in TrackScheme to inspect progress), the viewer's
	 * "current" timepoint can silently change between those two calls — {@link #predictSpots()} would
	 * then detect into whatever timepoint the user happens to be looking at instead of the one the loop
	 * intended, corrupting an already-processed timepoint. Passing the timepoint the caller already
	 * decided on removes the dependency on that shared, externally-mutable state entirely.</p>
	 *
	 * <p>Sends HTTP POST request to ELEPHANT server's /api/seg/predict endpoint,
	 * parses the JSON response, and adds detected spots to the Mastodon graph.</p>
	 *
	 * @return {@code true} if prediction succeeded and spots were processed
	 */
	public boolean predictSpots( final int timepoint )
	{
		if ( !initialized )
		{
			log.warn( "Call init() before predictSpots()" );
			return false;
		}

		try
		{
			log.info( "Requesting ELEPHANT spot prediction for timepoint {}", timepoint );
			// Diagnostic only: if the shared viewer has since drifted to a different timepoint (e.g. the
			// user navigated TrackScheme/BDV while this automated loop was running), note it so a future
			// "why did t=X get detected into again" is traceable — the request below always uses the
			// explicit timepoint argument, never this reading, so drift here cannot corrupt the result.
			if ( context.hasViewer() )
			{
				final int liveTp = context.requireViewer().state().getCurrentTimepoint();
				if ( liveTp != timepoint )
					context.log( String.format(
							"    predict: t=%d — note: viewer is currently showing t=%d (user navigated?); "
									+ "using t=%d regardless.",
							timepoint, liveTp, timepoint ) );
			}

			// Build request JSON
			final JSONObject requestBody = buildPredictRequest( timepoint );

			log.debug( "Request body: {}", requestBody.toString( 2 ) );

			// Send HTTP POST request to ELEPHANT server
			final String endpoint = elephantServerUrl + "/seg/predict";
			log.debug( "Sending POST request to: {}", endpoint );

			final HttpResponse< String > response = postPredictBlocking( endpoint, requestBody.toString() );
			if ( response == null )
			{
				context.log( String.format(
						"    predict: t=%d — no response (interrupted or server never cleared).", timepoint ) );
				return false; // interrupted
			}

			// Check response status
			if ( !response.isSuccess() )
			{
				log.error( "ELEPHANT server returned status {}: {}", response.getStatus(),
						response.getBody() );
				context.log( String.format( "    predict: t=%d — HTTP %d: %s", timepoint,
						response.getStatus(), snippet( response.getBody() ) ) );
				return false;
			}

			// Parse response JSON
			final JSONObject responseBody = new JSONObject( response.getBody() );

			if ( !responseBody.optBoolean( "completed", false ) )
			{
				log.warn( "ELEPHANT prediction not completed. Response: {}", response.getBody() );
				context.log( String.format( "    predict: t=%d — not completed: %s", timepoint,
						snippet( response.getBody() ) ) );
				return false;
			}

			// Extract and add detected spots
			final JSONArray spotsArray = responseBody.optJSONArray( "spots" );
			if ( spotsArray == null )
			{
				log.warn( "No spots array in ELEPHANT response" );
				context.log( String.format( "    predict: t=%d — completed but no spots array in response.",
						timepoint ) );
				return true;
			}

			int spotCount = 0;
			String afterFirst = null;
			for ( int i = 0; i < spotsArray.length(); i++ )
			{
				final JSONObject spotJson = spotsArray.getJSONObject( i );
				if ( addSpotFromJson( spotJson, timepoint ) )
				{
					spotCount++;
					// One step further into the ('tp', 0), ('fn', N) anomaly. The tally printed below
					// already says fn=all on a window that has only been predicted into, and NOTHING in
					// this project tags a detection: the one fn writer is tagAsMissedCell, whose three
					// callers are all correction passes, and neither addSpotFromJson nor
					// GraphEdits.addSpot nor DetectionQuality touches a tag. Two readings were suspected
					// and both ruled out by test (TagGhostTest): a tag does not survive its spot through
					// pool-index reuse, and an untagged spot reads as untagged against ELEPHANT's own
					// seven-tag set too. So the tag is real and its writer is not in this file.
					//
					// This is the smallest remaining question: is the FIRST spot already tagged the
					// instant it is added? If it is, the tag arrives with the add and the writer is
					// inside Mastodon or ELEPHANT's plugin; if it is untagged here but fn below, the
					// writer runs during the rest of this loop.
					if ( afterFirst == null )
						afterFirst = String.valueOf( DetectionTagLabels.tally( context.getModel(),
								context.getGraph(), new int[] { timepoint } ) );
				}
			}

			log.info( "ELEPHANT prediction complete: {} spots detected and added", spotCount );
			// The last step of bisecting the ('tp', 0), ('fn', N) anomaly. The [1/6] tally already said
			// fn=all and untagged=0 on a window that had only been predicted into, so whatever tags them
			// is at or before this point. addSpotFromJson does not tag; if this line says fn=N the tags
			// arrive with the detections, and if it says untagged=N they are applied between here and
			// the end of [1/6].
			context.log( String.format( "    predict: t=%d — %s%d spot(s) detected and added. [%s]%s",
					timepoint, spotCount == 0 ? "WARNING: 0 detections — " : "", spotCount,
					DetectionTagLabels.tally( context.getModel(), context.getGraph(),
							new int[] { timepoint } ),
					afterFirst == null ? ""
							: " — after the FIRST add it was [" + afterFirst + "]"
									+ ( context.getModel().getGraph() == context.getGraph() ? ""
											: "; AND getModel().getGraph() IS NOT getGraph(), so the tally "
													+ "was reading another model's tags by pool index" ) ) );
			return true;
		}
		catch ( final Exception e )
		{
			log.error( "Failed to predict spots", e );
			context.log( "    predict: EXCEPTION " + e.getClass().getSimpleName() + ": " + e.getMessage() );
			return false;
		}
	}

	/**
	 * Runs nearest-neighbor linking for the current timepoint, matching ELEPHANT's
	 * {@code NearestNeighborLinkingAction}.
	 *
	 * <p>When {@code useOpticalflow} is enabled in ELEPHANT main settings, spot
	 * positions are first refined via a {@code /flow/predict} call to the server.
	 * The server returns flow-adjusted positions and per-spot squared displacement
	 * ({@code sqdisp}), which are used as query centres for the KD-tree search.
	 * {@code sqdisp > 1.0} permits up to {@code maxEdges} outgoing edges from the
	 * source spot; otherwise only 1 edge is allowed.</p>
	 *
	 * @return {@code true} if linking succeeded
	 */
	/**
	 * Max larger/smaller bounding-sphere-radius ratio for a 1:1 continuation link: the same cell across
	 * frames should look similar in size, so a normal spot is never linked to a hugely different blob
	 * (distance is not the gate — cells may move far — appearance is). Mirrors AutoCorrectOrchestrator's
	 * CONTINUATION_SIZE_RATIO_MAX.
	 */
	private static final double LINK_CONTINUATION_SIZE_RATIO_MAX = 2.5;

	/** One candidate→source linking option, used by {@link #linkSpots()}'s global shortest-first assignment. */
	private static final class LinkOption
	{
		final int candIdx;

		/** Held reference to the source spot (released after the write phase or if unused). */
		final Spot src;

		final int srcPoolIdx;

		/** The source's outgoing-edge count sampled while collecting (under the read lock). */
		final int baseOutgoing;

		/** The source spot's bounding-sphere radius (for the appearance-similarity gate). */
		final double srcRadius;

		final double sqDist;

		LinkOption( final int candIdx, final Spot src, final int srcPoolIdx, final int baseOutgoing,
				final double srcRadius, final double sqDist )
		{
			this.candIdx = candIdx;
			this.src = src;
			this.srcPoolIdx = srcPoolIdx;
			this.baseOutgoing = baseOutgoing;
			this.srcRadius = srcRadius;
			this.sqDist = sqDist;
		}
	}

	public boolean linkSpots()
	{
		if ( !initialized )
		{
			log.warn( "Call init() before linkSpots()" );
			return false;
		}

		try
		{
			final ViewerPanel viewer = context.requireViewer();
			final int timepoint = viewer.state().getCurrentTimepoint();

			if ( timepoint == 0 )
			{
				log.info( "t=0 — no previous timepoint to link from" );
				return true;
			}

			final ElephantMainSettings mainSettings =
					ElephantMainSettingsManager.getInstance().getSelectedStyle();
			final double distThreshold = mainSettings.getNNLinkingThreshold();
			final double squaredDistThreshold = distThreshold * distThreshold;
			final int maxEdges = mainSettings.getNNMaxEdges();
			final int searchDepth = mainSettings.getNNSearchDepth();
			final int searchNeighbors = mainSettings.getNNSearchNeighbors();
			final boolean useOpticalflow = mainSettings.getUseOpticalflow();

			log.info( "t={} — NN linking (threshold={}, maxEdges={}, searchDepth={}, opticalflow={})",
					timepoint, distThreshold, maxEdges, searchDepth, useOpticalflow );

			final Model model = context.getModel();
			final ModelGraph graph = context.getGraph();
			final SpatioTemporalIndex< Spot > index = model.getSpatioTemporalIndex();

			// Phase 1: collect unlinked spots at current timepoint with their
			// positions and covariances (covariances needed for flow request).
			final List< double[] > candidatePositions = new ArrayList<>();
			final List< double[][] > candidateCovariances = new ArrayList<>();
			final List< Integer > candidatePoolIndices = new ArrayList<>();
			final List< Spot > candidateRefs = new ArrayList<>();
			final List< Double > candidateRadii = new ArrayList<>();

			graph.getLock().readLock().lock();
			try
			{
				for ( final Spot spot : graph.vertices() )
				{
					if ( spot.getTimepoint() != timepoint ) continue;
					if ( !spot.incomingEdges().isEmpty() ) continue;
					// fp spots are DETECTION-TRAINING negatives, never tracking objects — never link them.
					if ( DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, spot ) ) ) continue;
					final Spot ref = graph.vertexRef();
					ref.refTo( spot );
					candidateRefs.add( ref );

					final double[] pos = new double[ 3 ];
					spot.localize( pos );
					candidatePositions.add( pos );

					candidatePoolIndices.add( spot.getInternalPoolIndex() );

					final double[][] cov = new double[ 3 ][ 3 ];
					spot.getCovariance( cov );
					candidateCovariances.add( cov );

					candidateRadii.add( Math.sqrt( spot.getBoundingSphereRadiusSquared() ) );
				}
			}
			finally
			{
				graph.getLock().readLock().unlock();
			}

			log.debug( "t={} — {} unlinked candidate spots", timepoint, candidateRefs.size() );

			// Phase 2 (optional): call /flow/predict to get server-adjusted positions.
			// Map: pool index → [x, y, z, sqdisp]
			final Map< Integer, double[] > flowData = new HashMap<>();
			if ( useOpticalflow && !candidateRefs.isEmpty() )
			{
				final JSONArray spotsJson = new JSONArray();
				for ( int ci = 0; ci < candidatePoolIndices.size(); ci++ )
				{
					final double[] pos = candidatePositions.get( ci );
					final double[][] cov = candidateCovariances.get( ci );
					final double[] cov1d = new double[ 9 ];
					for ( int r = 0; r < 3; r++ )
						for ( int c = 0; c < 3; c++ )
							cov1d[ r * 3 + c ] = cov[ r ][ c ];

					spotsJson.put( new JSONObject()
							.put( "pos", new JSONArray().put( pos[ 0 ] ).put( pos[ 1 ] ).put( pos[ 2 ] ) )
							.put( "covariance", new JSONArray( cov1d ) )
							.put( "id", candidatePoolIndices.get( ci ) ) );
				}

				final String endpoint = elephantServerUrl + "/flow/predict";
				log.debug( "Requesting optical flow prediction from: {}", endpoint );
				try
				{
					final HttpResponse< String > response = postPredictBlocking( endpoint,
							buildFlowRequest( timepoint, spotsJson ).toString() );
					if ( response == null )
					{
						for ( final Spot ref : candidateRefs )
							graph.releaseRef( ref );
						return false; // interrupted
					}

					if ( response.isSuccess() )
					{
						final JSONObject body = new JSONObject( response.getBody() );
						if ( body.optBoolean( "completed", false ) )
						{
							final JSONArray spots = body.optJSONArray( "spots" );
							if ( spots != null )
							{
								for ( int i = 0; i < spots.length(); i++ )
								{
									final JSONObject s = spots.getJSONObject( i );
									final JSONArray p = s.getJSONArray( "pos" );
									flowData.put( s.getInt( "id" ), new double[] {
											p.getDouble( 0 ), p.getDouble( 1 ), p.getDouble( 2 ),
											s.optDouble( "sqdisp", 0.0 )
									} );
								}
							}
							log.debug( "Flow prediction: {} adjusted positions received",
									flowData.size() );
						}
					}
					else
					{
						log.warn( "Flow prediction returned status {}, using original positions",
								response.getStatus() );
					}
				}
				catch ( final Exception e )
				{
					log.warn( "Flow prediction request failed, using original positions: {}",
							e.getMessage() );
				}
			}

			// Phase 3: GLOBAL shortest-first assignment. Across depths (consecutive t-1 first, then gaps for
			// missed detections), collect every candidate→source option within the distance threshold, sort
			// by distance, and assign shortest-first: each candidate gets exactly one predecessor and each
			// source at most maxEdges successors. This GUARANTEES that when several candidates compete for a
			// source, the SHORTEST link wins (the source keeps its nearest candidate) — a longer competitor
			// cannot steal it, and no candidate is forced onto a far source just because a closer candidate
			// happened to be processed first. (maxEdges = ELEPHANT NNMaxEdges; set it to 1 for strictly
			// single-successor linking / no divisions.)
			final List< Spot[] > linkPairs = new ArrayList<>();
			final boolean[] assigned = new boolean[ candidateRefs.size() ];
			final Map< Integer, Integer > srcAssignedCount = new HashMap<>(); // source poolIdx -> links added here

			for ( int depth = 0; depth < searchDepth; depth++ )
			{
				final int searchTp = timepoint - 1 - depth;
				if ( searchTp < 0 ) break;

				// 3a. Collect options (each unassigned candidate's nearby sources within threshold).
				final List< LinkOption > options = new ArrayList<>();
				index.readLock().lock();
				graph.getLock().readLock().lock();
				try
				{
					final SpatialIndex< Spot > prevSpatial = index.getSpatialIndex( searchTp );
					for ( int ci = 0; ci < candidateRefs.size(); ci++ )
					{
						if ( assigned[ ci ] )
							continue;
						// Collected before the flow request, under a lock since released: a candidate
						// deleted or replaced in the viewer meanwhile must not win a source a live one
						// should have had. See GraphWatch.
						if ( GraphWatch.dirty( graph, candidateRefs.get( ci ) ) )
							continue;
						final double[] fd = flowData.get( candidatePoolIndices.get( ci ) );
						final double[] queryPos = ( fd != null )
								? new double[] { fd[ 0 ], fd[ 1 ], fd[ 2 ] }
								: candidatePositions.get( ci );
						final IncrementalNearestNeighborSearch< Spot > search =
								prevSpatial.getIncrementalNearestNeighborSearch();
						search.search( new RealPoint( queryPos ) );
						int count = 0;
						while ( search.hasNext() && count < searchNeighbors )
						{
							search.fwd();
							final double sqd = search.getSquareDistance();
							if ( sqd > squaredDistThreshold )
								break;
							final Spot neighbor = search.get();
							// Never link TO an fp source: fp spots are detection-training negatives, not tracks.
							if ( DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, neighbor ) ) )
							{
								count++;
								continue;
							}
							// Never link TO a protected source: a spot tagged as an already-finished, trusted
							// result must not gain a new successor/division it never had.
							if ( ProtectedSpots.isProtected( context, model, neighbor ) )
							{
								count++;
								continue;
							}
							final Spot srcCopy = graph.vertexRef();
							srcCopy.refTo( neighbor );
							options.add( new LinkOption( ci, srcCopy, neighbor.getInternalPoolIndex(),
									neighbor.outgoingEdges().size(),
									Math.sqrt( neighbor.getBoundingSphereRadiusSquared() ), sqd ) );
							count++;
						}
					}
				}
				finally
				{
					graph.getLock().readLock().unlock();
					index.readLock().unlock();
				}

				// 3b. Assign shortest-first; a source accepts up to maxEdges successors.
				options.sort( ( a, b ) -> Double.compare( a.sqDist, b.sqDist ) );
				for ( final LinkOption opt : options )
				{
					final int already = srcAssignedCount.getOrDefault( opt.srcPoolIdx, 0 );
					if ( assigned[ opt.candIdx ] || opt.baseOutgoing + already >= maxEdges )
					{
						graph.releaseRef( opt.src ); // unused option
						continue;
					}
					// APPEARANCE gate for a 1:1 continuation (source gains its FIRST successor): the same cell
					// across frames should look similar in size. Skip a size-mismatched pairing (e.g. a normal
					// spot to a huge saturated blob) — a near, similar match will win instead, or the candidate
					// stays unlinked, which beats a nonsensical link. (Division cases — baseOutgoing≥1 — keep
					// their own smaller-daughter logic downstream and are not gated here.)
					if ( opt.baseOutgoing + already == 0 )
					{
						final double rc = candidateRadii.get( opt.candIdx );
						if ( Math.max( rc, opt.srcRadius ) > LINK_CONTINUATION_SIZE_RATIO_MAX
								* Math.min( rc, opt.srcRadius ) )
						{
							graph.releaseRef( opt.src );
							continue;
						}
					}
					final Spot targetCopy = graph.vertexRef();
					targetCopy.refTo( candidateRefs.get( opt.candIdx ) );
					linkPairs.add( new Spot[] { opt.src, targetCopy } );
					assigned[ opt.candIdx ] = true;
					srcAssignedCount.put( opt.srcPoolIdx, already + 1 );
				}
			}

			// Phase 4: add edges under write lock.
			int linkCount = 0;
			final Link edgeRef = graph.edgeRef();
			try
			{
				graph.getLock().writeLock().lock();
				try
				{
					for ( final Spot[] pair : linkPairs )
					{
						final Spot src = pair[ 0 ];
						final Spot tgt = pair[ 1 ];
						if ( tgt.incomingEdges().isEmpty() && src.outgoingEdges().size() < maxEdges
								&& GraphEdits.addLink( context, graph, src, tgt, edgeRef, GraphEdits.Reason.TOPOLOGY ) != null )
							linkCount++;
					}
				}
				finally
				{
					graph.getLock().writeLock().unlock();
				}
			}
			finally
			{
				graph.releaseRef( edgeRef );
				for ( final Spot[] pair : linkPairs )
				{
					graph.releaseRef( pair[ 0 ] );
					graph.releaseRef( pair[ 1 ] );
				}
				for ( final Spot ref : candidateRefs )
					graph.releaseRef( ref );
			}

			if ( linkCount > 0 ) model.setUndoPoint();
			log.info( "t={} — {} links created", timepoint, linkCount );
			return true;
		}
		catch ( final Exception e )
		{
			log.error( "Failed to link spots", e );
			return false;
		}
	}

	private JSONObject buildFlowRequest( final int timepoint, final JSONArray spotsArray )
	{
		final ElephantMainSettings mainSettings =
				ElephantMainSettingsManager.getInstance().getSelectedStyle();

		final JSONObject request = new JSONObject();
		request.put( "dataset_name", mainSettings.getDatasetName() );
		request.put( "model_name", mainSettings.getFlowModelName() );
		request.put( "timepoint", timepoint );
		request.put( "max_displacement", mainSettings.getMaxDisplacement() );
		request.put( "batch_size", mainSettings.getBatchSize() );
		request.put( "debug", false );
		request.put( "output_prediction", false );
		request.put( "use_memmap", mainSettings.getUseMemmap() );
		request.put( "cache_maxbytes", mainSettings.getCacheMaxbytes() );
		request.put( "spots", spotsArray );

		final AbstractSpimData< ? > spimData =
				context.getProjectModel().getSharedBdvData().getSpimData();
		final BasicViewSetup setup =
				( BasicViewSetup ) spimData.getSequenceDescription().getViewSetupsOrdered().get( 0 );

		final Dimensions dims = setup.getSize();
		final boolean is3d = isVolumetric( dims );
		request.put( "is_3d", is3d );

		final long rx = Math.round( dims.dimension( 0 ) * mainSettings.getRescaleX() );
		final long ry = Math.round( dims.dimension( 1 ) * mainSettings.getRescaleY() );
		final JSONArray inputSize = new JSONArray().put( rx ).put( ry );

		final VoxelDimensions voxelDims = setup.getVoxelSize();
		final JSONArray scales = new JSONArray()
				.put( voxelDims.dimension( 0 ) )
				.put( voxelDims.dimension( 1 ) );

		if ( is3d )
		{
			final long rz = Math.round( dims.dimension( 2 ) * mainSettings.getRescaleZ() );
			inputSize.put( rz );
			scales.put( voxelDims.dimension( 2 ) );
		}
		request.put( "input_size", inputSize );
		request.put( "scales", scales );

		return request;
	}

	/**
	 * Converts a world-space region into the voxel origin/size pair ELEPHANT's {@code crop_box}
	 * carries, capped to the configured patch size so a large spot radius cannot ask the GPU for an
	 * arbitrarily large crop.
	 *
	 * <p>Both arrays are of length 3 on 2D data as much as on 3D, and the caller must serialise all
	 * six numbers. ELEPHANT reads {@code crop_box} at FIXED offsets — Y is {@code crop_box[1]} and
	 * {@code crop_box[4]} — in both the 2D and the 3D branch of
	 * {@code datasets._get_memmap_or_load}, so a four-element box makes the server index past the end
	 * of the list and every {@code /seg/predict} on a crop fails with
	 * {@code "Exception: list index out of range"}. A 2D dataset is one voxel deep, so the third axis
	 * is a truthful origin 0 / size 1; the {@code is_3d} flag, not the length of this array, is what
	 * tells ELEPHANT how many spatial dims the image has. This matches the reference
	 * {@code PredictSpotsAction}, which fills a {@code long[ 3 ]} unconditionally.</p>
	 *
	 * @param dims        image size in voxels
	 * @param voxelDims   physical size of one voxel
	 * @param patchSizeXYZ configured ELEPHANT patch size, per axis
	 * @param cropBoxWorld requested region as {@code [xMin, yMin, zMin, xMax, yMax, zMax]} in world units
	 * @param cropOrigin  filled with the voxel origin (length 3)
	 * @param cropSize    filled with the voxel size (length 3)
	 */
	static void cropBoxVoxels( final Dimensions dims, final VoxelDimensions voxelDims,
			final int[] patchSizeXYZ, final double[] cropBoxWorld,
			final long[] cropOrigin, final long[] cropSize )
	{
		for ( int d = 0; d < 3; d++ )
		{
			// Crop size is the smaller of the configured patch size and the image dimension
			final long size = Math.min( dims.dimension( d ), patchSizeXYZ[ d ] );
			// Origin: centred on the region, clamped to [0, imageDim - size]. An axis with no room to
			// slide — the Z of a 2D dataset — starts at 0 without dividing by a voxel size that is
			// allowed to be 0 there.
			final long slack = dims.dimension( d ) - size;
			long origin = 0;
			if ( slack > 0 )
			{
				// Centre of the requested region in voxel coordinates
				final double centerVoxel =
						( cropBoxWorld[ d ] + cropBoxWorld[ d + 3 ] ) / 2.0 / voxelDims.dimension( d );
				origin = ( long ) Math.max( 0.0, Math.min( slack, centerVoxel - size / 2.0 ) );
			}
			cropOrigin[ d ] = origin;
			cropSize[ d ] = size;
		}
	}

	/**
	 * Runs ELEPHANT spot detection on a cropped region and returns the raw spot
	 * JSON objects without adding them to the graph. Used by {@code SpotRefitTool}.
	 *
	 * @param timepoint timepoint to predict
	 * @param cropBox   bounding box in world coordinates
	 *                  {@code [x_min, y_min, z_min, x_max, y_max, z_max]}
	 * @return list of spot JSON objects, or {@code null} on failure
	 */
	public List< JSONObject > predictSpotsInRegion( final int timepoint, final double[] cropBox )
	{
		return predictJson( timepoint, cropBox );
	}

	/**
	 * Runs ELEPHANT spot detection on the whole frame, exactly as {@link #predictSpots(int)} sends it, and returns
	 * the raw spot JSON objects without adding them to the graph. A crop is capped at the patch size (see
	 * {@link #cropBoxVoxels}), so asking {@link #predictSpotsInRegion} for the image's extent does NOT read the whole
	 * frame: on Fluo-N3DH-CE (708x512x35 voxels, patch 384x384x24) it read a centred block of about 40% of it, and a
	 * cold-start check judged Go on 4 detections there while the commit found 51 on the frame.
	 *
	 * @return list of spot JSON objects, or {@code null} on failure
	 */
	public List< JSONObject > predictSpotsOnFrame( final int timepoint )
	{
		return predictJson( timepoint, null );
	}

	/** The predict request on a crop ({@code cropBox} in world units) or, when it is null, on the whole frame. */
	private List< JSONObject > predictJson( final int timepoint, final double[] cropBox )
	{
		if ( !initialized )
		{
			log.warn( "Call init() before predicting" );
			return null;
		}

		try
		{
			final JSONObject requestBody = buildPredictRequest( timepoint );
			if ( cropBox != null )
			{

				// ELEPHANT expects crop_box as integer voxel coordinates in the format
				// [x_origin, y_origin, z_origin, x_size, y_size, z_size], matching
				// ElephantGraphActionMixin.calculateCropBoxAround().
				// cropBox is [x_min, y_min, z_min, x_max, y_max, z_max] in world coords.
				final AbstractSpimData< ? > spimData =
						context.getProjectModel().getSharedBdvData().getSpimData();
				final BasicViewSetup setup =
						( BasicViewSetup ) spimData.getSequenceDescription().getViewSetupsOrdered().get( 0 );
				final VoxelDimensions voxelDims = setup.getVoxelSize();
				final Dimensions dims = setup.getSize();

				// Cap crop size at the ELEPHANT patch size (same as calculateCropBoxAround).
				// Using the world bounding box directly would produce arbitrarily large
				// crops (and CUDA OOM) when the spot radius is large.
				final ElephantMainSettings mainSettings =
						ElephantMainSettingsManager.getInstance().getSelectedStyle();
				final int[] patchSizeXYZ = {
						mainSettings.getPatchSizeX(),
						mainSettings.getPatchSizeY(),
						mainSettings.getPatchSizeZ()
				};

				final long[] cropOrigin = new long[ 3 ];
				final long[] cropSize = new long[ 3 ];
				cropBoxVoxels( dims, voxelDims, patchSizeXYZ, cropBox, cropOrigin, cropSize );
				log.debug( "crop_box voxels: origin={} size={} (patch={},{},{})",
						cropOrigin, cropSize,
						patchSizeXYZ[ 0 ], patchSizeXYZ[ 1 ], patchSizeXYZ[ 2 ] );
				// Publish WHERE this prediction looked, in world units, so the cleanup passes that judge its
				// output can measure this region's background instead of the whole frame's — see
				// ExpectedCellCount.SHARED_CROP_WORLD_BOUNDS. On a 2D dataset the Z axis spans the single
				// plane the image has, which is its full extent.
				final double[] cropWorldBounds = new double[ 6 ];
				for ( int d = 0; d < 3; d++ )
				{
					cropWorldBounds[ d ] = cropOrigin[ d ] * voxelDims.dimension( d );
					cropWorldBounds[ d + 3 ] =
							( cropOrigin[ d ] + cropSize[ d ] ) * voxelDims.dimension( d );
				}
				context.setSharedData( ExpectedCellCount.SHARED_CROP_WORLD_BOUNDS, cropWorldBounds );
				final JSONArray cropBoxJson = new JSONArray();
				for ( final long origin : cropOrigin )
					cropBoxJson.put( origin );
				for ( final long size : cropSize )
					cropBoxJson.put( size );
				requestBody.put( "crop_box", cropBoxJson );
			}

			final String endpoint = elephantServerUrl + "/seg/predict";
			// Bounded and retried like the full-frame predict: unbounded, a connection dropped mid-request left
			// a benchmark run waiting on the dead socket for the 24 h socket timeout (Fluo-N3DH-CE 01, 07:53).
			final HttpResponse< String > response = postPredictBlocking( endpoint, requestBody.toString() );
			if ( response == null )
				return null; // interrupted, or no answer within the bounded wait

			if ( !response.isSuccess() )
			{
				log.error( "ELEPHANT server returned status {}: {}", response.getStatus(),
						response.getBody() );
				return null;
			}

			final JSONObject responseBody = new JSONObject( response.getBody() );
			if ( !responseBody.optBoolean( "completed", false ) )
			{
				log.warn( "ELEPHANT prediction not completed: {}", response.getBody() );
				return null;
			}

			final JSONArray spotsArray = responseBody.optJSONArray( "spots" );
			if ( spotsArray == null )
				return new ArrayList<>();

			final List< JSONObject > result = new ArrayList<>();
			for ( int i = 0; i < spotsArray.length(); i++ )
				result.add( spotsArray.getJSONObject( i ) );

			return result;
		}
		catch ( final Exception e )
		{
			log.error( "Failed to predict spots in region", e );
			return null;
		}
	}

	/**
	 * Runs ELEPHANT spot detection restricted to a small region around {@code cropBox}'s centre (see
	 * {@link #predictSpotsInRegion}, capped to the configured patch size) and adds the results to the
	 * Mastodon graph exactly like {@link #predictSpots(int)} — used to tune detection/training on a fast,
	 * small crop before committing to full-frame prediction.
	 *
	 * @return {@code true} if the request completed (even with zero detections), {@code false} on
	 *         failure/interrupt
	 */
	public boolean predictSpotsInRegionAndAdd( final int timepoint, final double[] cropBox )
	{
		final List< JSONObject > spots = predictSpotsInRegion( timepoint, cropBox );
		if ( spots == null )
			return false;
		int spotCount = 0;
		for ( final JSONObject spotJson : spots )
			if ( addSpotFromJson( spotJson, timepoint ) )
				spotCount++;
		context.log( String.format( "    predict (crop-tuning): t=%d — %d spot(s) detected and added.",
				timepoint, spotCount ) );
		return true;
	}

	/**
	 * Convenience method to run both prediction and linking.
	 *
	 * @return {@code true} if both operations succeeded
	 */
	public boolean predictAndLink()
	{
		final boolean predictOk = predictSpots();
		final boolean linkOk = linkSpots();
		return predictOk && linkOk;
	}

	// -----------------------------------------------------------------------
	// Training / annotation methods

	/**
	 * Fine-tunes the ELEPHANT segmentation model using spots at the given
	 * timepoints as sparse TP annotations ({@code /seg/train}).
	 *
	 * <p>The server runs training as an async Celery task, but the {@code /seg/train} request
	 * <b>blocks until that task finishes</b> and then returns {@code {"completed": true}}
	 * ({@code false} if it was aborted). So the HTTP response IS the completion signal — no state
	 * polling is needed. {@code /seg/train} writes the annotation labels itself before training, so
	 * there is no separate label-update step to call first (ELEPHANT's own {@code /seg/update} route
	 * would just repeat that write for nothing).</p>
	 *
	 * <p>Training time scales with {@code n_crops} × (number of annotation timepoints) ×
	 * {@code n_epochs}; keep {@code nCrops} modest for fast per-window adaptation.</p>
	 *
	 * @param annotationTimepoints timepoints whose spots define the training set
	 * @param currentTimepoint     timepoint anchor sent to the server
	 * @param nEpochs              training epochs (≤0 → use ELEPHANT settings value)
	 * @param nCrops               crops per timepoint (≤0 → use ELEPHANT settings value)
	 * @param timeoutMs            generous safety cap; if training has not returned by then the
	 *                             server job is aborted ({@code <=0} waits indefinitely)
	 * @return {@code true} if training completed; {@code false} on abort, timeout, interruption,
	 *         or a server error
	 */
	public boolean trainDetectionModel( final int[] annotationTimepoints,
			final int currentTimepoint, final int nEpochs, final int nCrops, final long timeoutMs )
	{
		// Timed here rather than at each of the callers, so every training round in the pipeline — the
		// cold-start bootstrap, the tuning loop, the GT consolidation, refinement — lands in the same
		// bucket of the per-window timing line.
		final long started = System.nanoTime();
		try
		{
			return trainDetectionModelTimed( annotationTimepoints, currentTimepoint, nEpochs, nCrops,
					timeoutMs );
		}
		finally
		{
			PhaseTimings.record( context, "ELEPHANT train", started );
		}
	}

	private boolean trainDetectionModelTimed( final int[] annotationTimepoints,
			final int currentTimepoint, final int nEpochs, final int nCrops, final long timeoutMs )
	{
		if ( !initialized )
		{
			log.warn( "Call init() before trainDetectionModel()" );
			return false;
		}
		// Listen to ELEPHANT's RabbitMQ notifications so completion is detected even when nginx severs the
		// blocking HTTP /seg/train response at its 60 s gateway timeout (the backend finishes regardless).
		// "DONE POST /seg/train" is the definitive request-completion line ELEPHANT publishes to the log
		// queue when training finishes (seen even after nginx has already 504'd the client).
		final ElephantRabbitMqMonitor monitor =
				new ElephantRabbitMqMonitor( context::log, "DONE POST /seg/train" );
		final boolean monitorOk = monitor.start(
				ElephantServerSettingsManager.getInstance().getForwardDefaultStyle() );
		try
		{
			final JSONObject request = buildTrainRequest( annotationTimepoints, currentTimepoint, nEpochs, nCrops );
			if ( request == null )
				return false;
			final String endpoint = elephantServerUrl + "/seg/train";
			final int nSpots = request.getJSONArray( "spots" ).length();
			context.log( String.format( "    train: POST /seg/train (%d epochs, %d crops/tp, %d spots)%s...",
					request.optInt( "n_epochs" ), request.optInt( "n_crops" ), nSpots,
					monitorOk ? " [RabbitMQ monitor active]" : "" ) );
			final long t0 = System.currentTimeMillis();
			// The reply or the server's own "done", whichever comes first. A connection that drops while the
			// server trains leaves the reply to never arrive: the run waited out the full training timeout
			// (15 min) although the completion had been reported over RabbitMQ 20 s after the request.
			// Without RabbitMQ (a benchmark run), the server's own state says the same over HTTP: idle again
			// while the reply is still pending means training ended and the reply was lost.
			final ServerIdleWatch idle = new ServerIdleWatch( elephantServerUrl, t0 );
			final HttpResponse< String > response = postBlocking( endpoint, request.toString(), timeoutMs,
					monitorOk ? monitor::completedThisRequest : idle::idleAgain );
			final long elapsed = System.currentTimeMillis() - t0;
			if ( response == null && monitorOk && monitor.completedThisRequest() )
				context.log( String.format( "    train: the server reported completion over RabbitMQ after %d ms, "
						+ "before its HTTP reply (the connection likely dropped) — not waiting for the reply.", elapsed ) );
			if ( response == null && !monitorOk && idle.sawIdle() )
			{
				context.log( String.format( "    train: the server was idle again after %d ms with no HTTP reply (the "
						+ "connection likely dropped) — training ended; not waiting for the reply.", elapsed ) );
				return true;
			}

			// Path 1: the HTTP call itself confirmed completion (nginx did not cut it).
			if ( response != null && response.isSuccess() )
			{
				final boolean completed = new JSONObject( response.getBody() ).optBoolean( "completed", false );
				context.log( String.format( "    train: HTTP 200 after %d ms, completed=%s", elapsed, completed ) );
				if ( completed )
					return true;
			}
			else if ( response != null )
			{
				context.log( String.format( "    train: HTTP %d after %d ms%s", response.getStatus(), elapsed,
						response.getStatus() == 504 || elapsed >= 55000 && elapsed <= 65000
								? " (nginx gateway timeout — backend still running; waiting for RabbitMQ notification)"
								: "" ) );
			}
			else
			{
				context.log( String.format( "    train: no HTTP response after %d ms — waiting for RabbitMQ "
						+ "notification.", elapsed ) );
			}

			// Path 2: HTTP did not confirm completion → wait for the RabbitMQ completion notification, with
			// a log-silence fallback so a missed terminal marker (e.g. broker connection dropped during the
			// long training tail) does not hang the run until the full timeout.
			if ( monitorOk )
			{
				final long remaining = Math.max( 30_000L, timeoutMs - elapsed );
				context.log( String.format( "    train: awaiting RabbitMQ completion notification (up to %d s; "
						+ "or %d s of training-log silence after activity → treated as done)...",
						remaining / 1000, TRAIN_LOG_QUIET_MS / 1000 ) );
				final ElephantRabbitMqMonitor.Result result = monitor.awaitCompletion( remaining, TRAIN_LOG_QUIET_MS );
				if ( result == ElephantRabbitMqMonitor.Result.COMPLETED )
				{
					context.log( "    train: COMPLETION notification received: " + monitor.getCompletionMessage() );
					return true;
				}
				if ( result == ElephantRabbitMqMonitor.Result.FAILED )
				{
					// The server finished and reported a failure. Returning true here would hand the caller
					// an UNCHANGED model while it believes it just trained one, silently consuming one of
					// its few training iterations.
					context.log( "    train: FAILED on the server: " + monitor.getCompletionMessage()
							+ " — the model was NOT updated; this training iteration achieved nothing." );
					return false;
				}
				if ( result == ElephantRabbitMqMonitor.Result.QUIET )
				{
					context.log( String.format( "    train: no DONE marker, but the training log has been silent "
							+ "for %d s after streaming progress — server-side epochs finished and the model was "
							+ "saved (per-epoch checkpoints), so treating training as COMPLETE (fallback).",
							TRAIN_LOG_QUIET_MS / 1000 ) );
					return true;
				}
				context.log( "    train: no completion notification within timeout." );
			}

			// Neither HTTP nor RabbitMQ confirmed completion: if the client never got a response, abort the
			// server job so it cannot keep running unnoticed.
			if ( response == null )
				stopProcessingBlocking();
			return false;
		}
		catch ( final Exception e )
		{
			context.log( "    train: EXCEPTION " + e.getClass().getSimpleName() + ": " + e.getMessage() );
			log.error( "Failed to train detection model", e );
			return false;
		}
		finally
		{
			monitor.close();
		}
	}

	/** First 200 chars of a response body, for diagnostics. */
	private static String snippet( final String body )
	{
		if ( body == null )
			return "(null)";
		final String s = body.replaceAll( "\\s+", " " ).trim();
		return s.length() > 200 ? s.substring( 0, 200 ) + "…" : s;
	}

	// -----------------------------------------------------------------------
	// Training helpers

	/** Serialises spots at the given timepoints as TP annotation JSON objects. */
	/**
	 * Total ellipsoid volume of the tp and fp annotations in {@code annotationTimepoints}, as
	 * {@code {tpVolume, fpVolume}}.
	 *
	 * <p>Volume, not spot count, because that is what the loss sees: it is computed per labelled VOXEL,
	 * and one tp spot paints a whole ellipsoid while one fp spot paints another. A negatives-to-positives
	 * ratio expressed in spots therefore overstates the supervision the model actually receives whenever
	 * the two differ in size. Units cancel in the ratio, so no voxel-size conversion is needed.</p>
	 */
	private double[] annotationVolumes( final int[] annotationTimepoints )
	{
		final ModelGraph graph = context.getGraph();
		double tpVolume = 0, fpVolume = 0;
		final double[][] cov = new double[ 3 ][ 3 ];
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				boolean inAnnotation = false;
				for ( final int at : annotationTimepoints )
				{
					if ( at == t ) { inAnnotation = true; break; }
				}
				if ( !inAnnotation ) continue;
				spot.getCovariance( cov );
				// Ellipsoid volume = 4/3 pi abc, and abc = sqrt(det(covariance)) for the semi-axes the
				// covariance describes.
				final double det = cov[ 0 ][ 0 ] * ( cov[ 1 ][ 1 ] * cov[ 2 ][ 2 ] - cov[ 1 ][ 2 ] * cov[ 2 ][ 1 ] )
						- cov[ 0 ][ 1 ] * ( cov[ 1 ][ 0 ] * cov[ 2 ][ 2 ] - cov[ 1 ][ 2 ] * cov[ 2 ][ 0 ] )
						+ cov[ 0 ][ 2 ] * ( cov[ 1 ][ 0 ] * cov[ 2 ][ 1 ] - cov[ 1 ][ 1 ] * cov[ 2 ][ 0 ] );
				if ( det <= 0 )
					continue; // degenerate covariance — contributes no measurable volume
				final double volume = 4.0 / 3.0 * Math.PI * Math.sqrt( det );
				final String annotationLabel = DetectionTagLabels.labelOf( context.getModel(), spot );
				if ( DetectionTagLabels.UNLABELED.equals( annotationLabel ) )
					continue; // withheld from training, so it is not in either class's labelled volume
				if ( DetectionTagLabels.FP.equals( annotationLabel ) )
					fpVolume += volume;
				else
					tpVolume += volume;
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return new double[] { tpVolume, fpVolume };
	}

	/**
	 * Background class weight that balances the two sides of the segmentation loss by LABELLED VOLUME.
	 *
	 * <p>Per voxel the two sides already weigh the same — a cell voxel carries {@code classWeightCenter}
	 * and a background voxel carries {@code classWeightBG × falseWeight}, 10 against 1×10 by default —
	 * so what is left unbalanced is how much of each there is. Cell annotations outnumber background
	 * annotations by roughly an order of magnitude in volume, which is the pressure the self-training
	 * loop then amplifies round after round.</p>
	 *
	 * <p>Solving {@code tpVolume × centre = fpVolume × bg × falseWeight} for {@code bg} makes the two
	 * contributions equal. Returns the configured weight unchanged when there is nothing to balance
	 * against (no fp annotations at all), and never goes below it — this may only add background
	 * pressure, never remove it.</p>
	 */
	static double balancedBackgroundWeight( final double tpVolume, final double fpVolume,
			final double configuredBg, final double centreWeight, final double falseWeight )
	{
		if ( fpVolume <= 0 || tpVolume <= 0 || falseWeight <= 0 )
			return configuredBg;
		final double balanced = centreWeight * tpVolume / ( fpVolume * falseWeight );
		return Math.min( MAX_BACKGROUND_CLASS_WEIGHT, Math.max( configuredBg, balanced ) );
	}

	/**
	 * Ceiling on the computed background weight. A window whose synthetic negatives nearly all failed
	 * VLM confirmation can leave a handful of tiny fp ellipsoids against thousands of cells, and the
	 * balancing ratio then asks for a weight large enough that the loss is effectively only about those
	 * few voxels. Past this point the imbalance is too severe to fix by weighting and the negatives
	 * themselves are what need attention — the log says when the ceiling binds.
	 */
	private static final double MAX_BACKGROUND_CLASS_WEIGHT = 200.0;

	/**
	 * {@link ToolContext#getSharedData} key for a factor applied to every non-protected annotation's
	 * COVARIANCE before it is sent for training — {@link Double}, 1.0 or absent meaning no change.
	 *
	 * <p>ELEPHANT paints an annotation as a "center" core covering {@code c_ratio} of the ellipsoid and
	 * a "border" shell around it, and prediction fits its ellipsoids to the CENTER regions it finds. A
	 * detection therefore describes the core, not the cell. Feeding that back as the next round's
	 * annotation makes the following round's core a fraction of a fraction, and the extent compounds
	 * downwards: measured across successive runs of one dataset, mean spot size went 10.5, 9.5, 8.3,
	 * 8.2 µm while the cells the user had confirmed sat at 8.5, and on the other dataset detections
	 * reached 8.2 against confirmed 11.1. Eventually only a cell's middle is detected, and then
	 * nothing.</p>
	 *
	 * <p>The correction is measured rather than derived from {@code c_ratio}: the factor is whatever
	 * brings the annotated population's mean size back to the confirmed cells' mean size, so it is 1.0
	 * when nothing is drifting and it cannot be wrong about a geometry this code does not own.</p>
	 */
	public static final String SHARED_ANNOTATION_COVARIANCE_SCALE =
			"adaptive_track.annotation_covariance_scale";

	private JSONArray buildSpotsJson( final int[] annotationTimepoints )
	{
		final ModelGraph graph = context.getGraph();
		final Object scaleObj = context.getSharedData( SHARED_ANNOTATION_COVARIANCE_SCALE );
		final double covScale = scaleObj instanceof Number ? ( ( Number ) scaleObj ).doubleValue() : 1.0;
		final JSONArray jsonSpots = new JSONArray();
		final double[] pos = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		// What actually goes on the wire, counted here rather than inferred from the server's own
		// per-frame counter. That counter reported ('tp', 0), ('fn', 59) for a whole run, and "tp" is
		// the DEFAULT branch below — anything neither fp nor fn — so the server's tp count conflates
		// "tagged tp" with "untagged". Splitting the two here is the one number that says whether the
		// fn tags are really on the graph. Paired with prepareAnnotationsForTraining's own tally, it
		// brackets everything that happens between the graph and the request.
		int sentFp = 0, sentFn = 0, sentTpTagged = 0, sentTpByDefault = 0, withheld = 0, sentTpAsFn = 0;
		// The pre-flight's training balance: cells at the weight of a correction (see CleanupToggles).
		final boolean cellsAsCorrections = CleanupToggles.detectionsWeightedAsCorrections( context );
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				boolean inAnnotation = false;
				for ( final int at : annotationTimepoints )
				{
					if ( at == t ) { inAnnotation = true; break; }
				}
				if ( !inAnnotation ) continue;
				spot.localize( pos );
				spot.getCovariance( cov );
				// Protected spots are the user's own measurements of a cell's extent and are sent as they
				// are; only detections, which describe a core, are rescaled.
				final double scale = covScale != 1.0
						&& !ProtectedSpots.isProtected( context, context.getModel(), spot ) ? covScale : 1.0;
				final JSONArray cov1d = new JSONArray();
				for ( int i = 0; i < 3; i++ )
					for ( int j = 0; j < 3; j++ )
						cov1d.put( cov[ i ][ j ] * scale );
				// Per-spot detection label, carried through verbatim. fn and fp are ELEPHANT's two
				// CORRECTION labels: on the server both paint with label_offset 3, and the loss
				// multiplies those voxels by false_weight (10 here). A correction of the model's own
				// error is worth ten confirmations.
				//
				// This has now been wrong in both directions. It was binary — anything not tagged fp
				// went as tp — which made FN unreachable and sent no corrections at all. Then FN became
				// reachable and a run showed ('tp', 0), ('fn', N) on every training frame: ELEPHANT's
				// GraphListenerService tags every vertex ADDED to the graph as fn, so the detector's own
				// output was going back to it as "you missed this" at ten times the weight. The predict
				// path now says tp for what the model produced; tagAsMissedCell still says fn for what
				// this project adds because the model really did miss it.
				final String label = DetectionTagLabels.labelOf( context.getModel(), spot );
				if ( DetectionTagLabels.UNLABELED.equals( label ) )
				{
					withheld++;
					continue; // kept as a detection, withheld from training — see DetectionTagLabels.UNLABELED
				}
				String tag = DetectionTagLabels.FP.equals( label ) ? "fp"
						: DetectionTagLabels.FN.equals( label ) ? "fn"
								: "tp";
				if ( "fp".equals( tag ) ) sentFp++;
				else if ( "fn".equals( tag ) ) sentFn++;
				else if ( DetectionTagLabels.TP.equals( label ) ) sentTpTagged++;
				else sentTpByDefault++;
				if ( cellsAsCorrections && "tp".equals( tag ) )
				{
					tag = "fn"; // same cell label on the server, at false_weight; the graph still says tp
					sentTpAsFn++;
				}
				jsonSpots.put( new JSONObject()
						.put( "t", t )
						.put( "pos", new JSONArray().put( pos[ 0 ] ).put( pos[ 1 ] ).put( pos[ 2 ] ) )
						.put( "covariance", cov1d )
						.put( "label", spot.getLabel() )
						.put( "id", spot.getInternalPoolIndex() )
						.put( "tag", tag ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		context.log( String.format(
				"    train payload over %d timepoint(s): %d spot(s) sent — fp=%d, fn=%d, tp=%d "
						+ "(%d tagged tp, %d untagged and sent as tp by default); %d withheld as "
						+ "unlabeled. fp and fn are corrections and carry false_weight (10x) on the "
						+ "server; tp is an ordinary positive. The %d correction(s) here therefore "
						+ "weigh about %.0f%% of the labelled signal against the %d plain positive(s). "
						+ "If detection thins out from here, that balance is the first thing to look "
						+ "at.",
				annotationTimepoints.length, jsonSpots.length(), sentFp, sentFn,
				sentTpTagged + sentTpByDefault, sentTpTagged, sentTpByDefault, withheld,
				sentFp + sentFn,
				100.0 * 10 * ( sentFp + sentFn )
						/ Math.max( 1, 10 * ( sentFp + sentFn ) + sentTpTagged + sentTpByDefault ),
				sentTpTagged + sentTpByDefault ) );
		if ( sentTpAsFn > 0 )
			context.log( String.format( "    train balance (pre-flight): the %d cell(s) above were sent at the weight of "
					+ "a correction (10x), so cells weigh %.0f%% of the labelled signal and the "
					+ "%d background negative(s) %.0f%%.", sentTpAsFn,
					100.0 * ( sentTpAsFn + sentFn ) / Math.max( 1, sentTpAsFn + sentFn + sentFp ), sentFp,
					100.0 * sentFp / Math.max( 1, sentTpAsFn + sentFn + sentFp ) ) );
		return jsonSpots;
	}

	// -----------------------------------------------------------------------
	// Model checkpoint / restore

	/**
	 * Saves the server's CURRENT detection model to {@code dest} ({@code POST download/model}).
	 *
	 * <p>Training mutates the model in place on the server and there is no undo there, so a caller that
	 * is about to train speculatively — the per-window tuning loop, whose self-training can and does
	 * diverge — must take a copy first if it wants to be able to put things back. Without one, a window
	 * that trains itself into a detection explosion and is then abandoned still leaves the ruined
	 * weights behind for the NEXT window to inherit, which is how a single diverged window cascades
	 * into every remaining one.</p>
	 *
	 * @return {@code true} if the model was written to {@code dest}
	 */
	public boolean snapshotDetectionModel( final File dest )
	{
		if ( !initialized )
		{
			log.warn( "Call init() before snapshotDetectionModel()" );
			return false;
		}
		try
		{
			final String modelName = ElephantMainSettingsManager.getInstance()
					.getSelectedStyle().getDetectionModelName();
			final File parent = dest.getAbsoluteFile().getParentFile();
			if ( parent != null )
				parent.mkdirs();
			final JSONObject body = new JSONObject().put( "model_name", modelName );
			// Download beside the destination and move it into place only once the transfer succeeded:
			// only one generation is kept, so a failed download must not destroy the last good snapshot.
			final File part = new File( dest.getAbsolutePath() + ".part" );
			Files.deleteIfExists( part.toPath() );
			final long t0 = System.currentTimeMillis();
			final HttpResponse< File > response = http().post( elephantServerUrl + "/download/model" )
					.socketTimeout( TRANSFER_SOCKET_TIMEOUT_MS )
					.header( "Content-Type", "application/json" )
					.body( body.toString() )
					.asFile( part.getAbsolutePath() );
			final long elapsed = System.currentTimeMillis() - t0;
			if ( response == null || !response.isSuccess() || part.length() == 0 )
			{
				Files.deleteIfExists( part.toPath() );
				context.log( String.format( "    model snapshot: FAILED (HTTP %s) — this window's "
						+ "training cannot be rolled back.",
						response == null ? "no response" : String.valueOf( response.getStatus() ) ) );
				return false;
			}
			Files.move( part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING );
			log.info( "Detection model snapshot saved to {} ({} bytes, {} ms)",
					dest, dest.length(), elapsed );
			return true;
		}
		catch ( final Exception e )
		{
			context.log( "    model snapshot: EXCEPTION " + e.getClass().getSimpleName() + ": "
					+ e.getMessage() );
			log.error( "Failed to snapshot the detection model", e );
			return false;
		}
	}

	/**
	 * Restores the detection model on the server from a local file previously written by
	 * {@link #snapshotDetectionModel} ({@code POST seg/reset} as multipart form data, the same call
	 * ELEPHANT's own "Reset Detection Model" dialog makes when given a file).
	 *
	 * <p>The geometry keys must describe the same dataset the snapshot was trained on — the server
	 * rebuilds the model around them — which is why they come from the single
	 * {@link #buildDatasetGeometryJson} used by training too.</p>
	 *
	 * @return {@code true} if the server accepted the restore
	 */
	public boolean restoreDetectionModel( final File src )
	{
		if ( !initialized )
		{
			log.warn( "Call init() before restoreDetectionModel()" );
			return false;
		}
		if ( src == null || !src.isFile() || src.length() == 0 )
		{
			log.info( "No usable detection-model snapshot to restore from ({})", src );
			return false;
		}
		try
		{
			final JSONObject data = buildDatasetGeometryJson();
			if ( data == null )
				return false;
			// ELEPHANT's own reset dialog sends this key; it selects a model to fetch from a URL when no
			// file is attached. A file IS attached here, so it must be explicitly empty rather than absent.
			data.put( "url", "" );
			final long t0 = System.currentTimeMillis();
			final HttpResponse< String > response = http().post( elephantServerUrl + "/seg/reset" )
					.socketTimeout( TRANSFER_SOCKET_TIMEOUT_MS )
					.field( "file", src )
					.field( "data", data.toString() )
					.asString();
			final long elapsed = System.currentTimeMillis() - t0;
			final boolean ok = response != null && response.isSuccess();
			context.log( ok
					? String.format( "    model restore: reverted to the snapshot taken before this "
							+ "window's training (%d ms).", elapsed )
					: String.format( "    model restore: FAILED (HTTP %s) — the next window inherits the "
							+ "diverged model.",
							response == null ? "no response" : String.valueOf( response.getStatus() ) ) );
			return ok;
		}
		catch ( final Exception e )
		{
			context.log( "    model restore: EXCEPTION " + e.getClass().getSimpleName() + ": "
					+ e.getMessage() );
			log.error( "Failed to restore the detection model", e );
			return false;
		}
	}

	/**
	 * Creates this run's detection model on the server from random weights ({@code POST seg/reset} with
	 * {@code url} null). Without it, the first training or prediction under a new model name starts from
	 * ELEPHANT's pretrained "versatile" model, whose training data include Cell Tracking Challenge sequences --
	 * which a benchmark on CTC data must not start from (docs/paper/benchmark-protocol.md).
	 *
	 * @return {@code true} if the server created the model
	 */
	/**
	 * {@link ToolContext#getSharedData} key, {@link Boolean}: this run created its detection model from random
	 * weights ({@link #initializeDetectionModelFromRandomWeights}), so the model knows nothing yet. Without it the
	 * model is whatever the user's ELEPHANT settings name, possibly already trained on this data.
	 */
	public static final String SHARED_MODEL_FROM_RANDOM_WEIGHTS = "elephant.model_from_random_weights";

	public boolean initializeDetectionModelFromRandomWeights()
	{
		if ( !initialized )
		{
			log.warn( "Call init() before initializeDetectionModelFromRandomWeights()" );
			return false;
		}
		try
		{
			final JSONObject data = buildDatasetGeometryJson();
			if ( data == null )
				return false;
			data.put( "url", JSONObject.NULL );
			final HttpResponse< String > response = http().post( elephantServerUrl + "/seg/reset" )
					.socketTimeout( TRANSFER_SOCKET_TIMEOUT_MS )
					.header( "Content-Type", "application/json" )
					.body( data.toString() )
					.asString();
			final boolean ok = response != null && response.isSuccess();
			if ( ok )
				context.setSharedData( SHARED_MODEL_FROM_RANDOM_WEIGHTS, Boolean.TRUE );
			context.log( ok
					? "  ELEPHANT detection model '" + data.optString( "model_name" ) + "' created from random weights."
					: "  ELEPHANT detection model: random initialisation FAILED (HTTP "
							+ ( response == null ? "no response" : response.getStatus() + " " + response.getBody() ) + ")." );
			return ok;
		}
		catch ( final Exception e )
		{
			context.log( "  ELEPHANT detection model: random initialisation EXCEPTION " + e );
			log.error( "Failed to initialise the detection model", e );
			return false;
		}
	}

	/**
	 * The dataset-geometry half of a request body — the keys {@code /seg/train} and {@code /seg/reset}
	 * both require and must agree on. Factored out so the two cannot drift apart: the reset endpoint
	 * rebuilds the model from these, so a mismatch would restore a model shaped differently from the one
	 * that was trained.
	 *
	 * @return the shared keys, or {@code null} if the dataset geometry could not be read
	 */
	private JSONObject buildDatasetGeometryJson()
	{
		try
		{
			final ElephantMainSettings mainSettings =
					ElephantMainSettingsManager.getInstance().getSelectedStyle();
			final AbstractSpimData< ? > spimData =
					context.getProjectModel().getSharedBdvData().getSpimData();
			final BasicViewSetup setup =
					( BasicViewSetup ) spimData.getSequenceDescription()
							.getViewSetupsOrdered().get( 0 );
			final Dimensions dims = setup.getSize();
			final VoxelDimensions voxelDims = setup.getVoxelSize();
			final boolean is3d = isVolumetric( dims );

			final long rx = Math.round( dims.dimension( 0 ) * mainSettings.getRescaleX() );
			final long ry = Math.round( dims.dimension( 1 ) * mainSettings.getRescaleY() );
			final JSONArray scales = new JSONArray()
					.put( voxelDims.dimension( 0 ) )
					.put( voxelDims.dimension( 1 ) );
			final JSONArray inputSize = new JSONArray().put( rx ).put( ry );
			final JSONArray cropSize = new JSONArray()
					.put( mainSettings.getTrainingCropSizeX() )
					.put( mainSettings.getTrainingCropSizeY() );
			int nKeepAxials = 0;
			if ( is3d )
			{
				final long rz = Math.round( dims.dimension( 2 ) * mainSettings.getRescaleZ() );
				scales.put( voxelDims.dimension( 2 ) );
				inputSize.put( rz );
				cropSize.put( mainSettings.getTrainingCropSizeZ() );
				final double rescaledX = voxelDims.dimension( 0 ) / mainSettings.getRescaleX();
				final double rescaledZ = voxelDims.dimension( 2 ) / mainSettings.getRescaleZ();
				nKeepAxials = computeNKeepAxials( rescaledZ / rescaledX );
			}
			return new JSONObject()
					.put( "dataset_name", mainSettings.getDatasetName() )
					.put( "scales", scales )
					.put( "crop_size", cropSize )
					.put( "model_name", mainSettings.getDetectionModelName() )
					.put( "n_keep_axials", nKeepAxials )
					.put( "is_3d", is3d )
					.put( "cache_maxbytes", mainSettings.getCacheMaxbytes() )
					.put( "n_crops", mainSettings.getNumCrops() )
					.put( "input_size", inputSize );
		}
		catch ( final Exception e )
		{
			log.error( "Failed to read dataset geometry", e );
			return null;
		}
	}

	private JSONObject buildTrainRequest( final int[] annotationTimepoints,
			final int currentTimepoint, final int nEpochs, final int nCrops )
	{
		try
		{
			final ElephantMainSettings mainSettings =
					ElephantMainSettingsManager.getInstance().getSelectedStyle();
			final JSONObject request = buildDatasetGeometryJson();
			if ( request == null )
				return null;
			final double[] volumes = annotationVolumes( annotationTimepoints );
			final double bgWeight = balancedBackgroundWeight( volumes[ 0 ], volumes[ 1 ],
					mainSettings.getClassWeightBG(), mainSettings.getClassWeightCenter(),
					mainSettings.getFalseWeight() );
			if ( bgWeight != mainSettings.getClassWeightBG() )
				context.log( String.format(
						"    train: background class weight %.1f -> %.1f — tp annotations outweigh fp by "
								+ "%.1fx in labelled volume%s.",
						mainSettings.getClassWeightBG(), bgWeight,
						volumes[ 1 ] > 0 ? volumes[ 0 ] / volumes[ 1 ] : Double.NaN,
						bgWeight >= MAX_BACKGROUND_CLASS_WEIGHT
								? String.format( ", capped at %.0f — the negatives themselves are too few to "
										+ "balance by weighting", MAX_BACKGROUND_CLASS_WEIGHT )
								: "" ) );
			final JSONArray classWeights = new JSONArray()
					.put( bgWeight )
					.put( mainSettings.getClassWeightBorder() )
					.put( mainSettings.getClassWeightCenter() );
			final int epochs = nEpochs > 0 ? nEpochs : mainSettings.getNumEpochs();
			final int crops = nCrops > 0 ? nCrops : mainSettings.getNumCrops();

			return request
					.put( "spots", buildSpotsJson( annotationTimepoints ) )
					.put( "debug", mainSettings.getDebug() )
					.put( "lr", mainSettings.getLearningRate() )
					.put( "batch_size", mainSettings.getBatchSize() )
					.put( "n_crops", crops )
					.put( "n_epochs", epochs )
					.put( "is_livemode", false )
					.put( "aug_scale_factor_base", mainSettings.getAugScaleFactorBase() )
					.put( "aug_rotation_angle", mainSettings.getAugRotationAngle() )
					.put( "aug_contrast", mainSettings.getAugContrast() )
					.put( "timepoint", currentTimepoint )
					.put( "class_weights", classWeights )
					.put( "false_weight", mainSettings.getFalseWeight() )
					.put( "auto_bg_thresh", mainSettings.getAutoBgThreshold() )
					.put( "c_ratio", mainSettings.getCenterRatio() )
					.put( "log_interval", mainSettings.getLogInterval() )
					.put( "log_dir", mainSettings.getDetectionLogName() )
					.put( "use_memmap", mainSettings.getUseMemmap() );
		}
		catch ( final Exception e )
		{
			log.error( "Failed to build train request", e );
			return null;
		}
	}

	// -----------------------------------------------------------------------
	// Abort

	/**
	 * Sends an abort signal to the ELEPHANT server, immediately stopping any
	 * running inference or training operation on the server side.
	 *
	 * <p>This is fire-and-forget: the call returns immediately without waiting
	 * for the server response. Safe to call concurrently with a blocking
	 * {@link #postBlocking} call in another thread.</p>
	 */
	public void abortProcessing()
	{
		if ( !initialized )
			return;
		try
		{
			final String endpoint = elephantServerUrl + "/state/process";
			http().post( endpoint )
					.header( "Content-Type", "application/json" )
					.body( "{\"state\":0}" )
					.asStringAsync();
			log.info( "Sent ELEPHANT abort signal to {}", endpoint );
		}
		catch ( final Exception e )
		{
			log.warn( "Failed to send ELEPHANT abort signal: {}", e.getMessage() );
		}
	}

	/**
	 * Sends an abort ({@code POST /state/process {"state":0}}) and BLOCKS until the server
	 * acknowledges it. Unlike {@link #abortProcessing} (fire-and-forget), this confirms the request
	 * was delivered, so callers can deterministically stop a still-running training job before
	 * proceeding — even when {@code GET /state/process} is not answerable during training and an
	 * idle poll therefore cannot confirm completion.
	 *
	 * @return {@code true} if the server acknowledged the abort
	 */
	public boolean stopProcessingBlocking()
	{
		if ( !initialized )
			return false;
		try
		{
			final HttpResponse< String > response =
					postBlocking( elephantServerUrl + "/state/process", "{\"state\":0}", STATE_TIMEOUT_MS );
			final boolean ok = response != null && response.isSuccess();
			log.info( "Blocking ELEPHANT abort: {}", ok ? "acknowledged" : "no/failed response" );
			return ok;
		}
		catch ( final Exception e )
		{
			log.warn( "Failed to send blocking ELEPHANT abort: {}", e.getMessage() );
			return false;
		}
	}


	/**
	 * As a plain {@code postBlocking(endpoint, body, PREDICT_TIMEOUT_MS)}, except that if the bounded
	 * attempt does not confirm completion (nginx severed it, most often because the server is still
	 * busy finishing a previous {@code /seg/train}/{@code /seg/update} request and is polling
	 * {@code "waiting @/seg/predict"} server-side), a RabbitMQ monitor is opened lazily to relay that
	 * wait to the caller and detect the {@code "DONE POST /seg/predict"} completion marker — otherwise
	 * this would look like a silent hang. The original request's response body (the detected spots)
	 * cannot be recovered once nginx has already answered the client, so once the server is confirmed
	 * clear, the request is simply retried.
	 *
	 * @return the server response, or {@code null} if the thread was interrupted or the server never
	 *         cleared within {@link #PREDICT_WAIT_TIMEOUT_MS}
	 */
	private HttpResponse< String > postPredictBlocking( final String endpoint, final String body )
	{
		final HttpResponse< String > first = postBlocking( endpoint, body, PREDICT_TIMEOUT_MS );
		if ( first != null && first.isSuccess() )
			return first;

		final long elapsed = PREDICT_TIMEOUT_MS; // first attempt ran to (approximately) this bound
		if ( first != null )
			context.log( String.format( "    predict: HTTP %d after ~%d ms%s", first.getStatus(), elapsed,
					first.getStatus() == 504
							? " (nginx gateway timeout — server likely still finishing a previous "
									+ "train/update request; waiting for it to clear)"
							: "" ) );
		else
			context.log( String.format(
					"    predict: no HTTP response after ~%d ms — waiting for the server to clear.", elapsed ) );

		final ElephantRabbitMqMonitor monitor =
				new ElephantRabbitMqMonitor( context::log, "DONE POST /seg/predict" );
		final boolean monitorOk = monitor.start(
				ElephantServerSettingsManager.getInstance().getForwardDefaultStyle() );
		try
		{
			if ( monitorOk )
			{
				context.log( String.format(
						"    predict: awaiting RabbitMQ completion notification (up to %d s)...",
						PREDICT_WAIT_TIMEOUT_MS / 1000 ) );
				monitor.awaitCompletion( PREDICT_WAIT_TIMEOUT_MS );
			}
			// Whether the marker arrived or not, the original response is unrecoverable — retry to get
			// the actual spots. If the server is genuinely still busy this will hit the same nginx cutoff
			// and return null/non-success, which the caller treats as a failed prediction for this call.
			return postBlocking( endpoint, body, PREDICT_TIMEOUT_MS );
		}
		finally
		{
			monitor.close();
		}
	}

	/**
	 * Whether the server is idle again while a long request is still pending, from {@code GET /state/process}:
	 * asked only after {@link #AFTER_MS} (the request must have started), at most every {@link #EVERY_MS},
	 * and believed after two idle answers in a row. Unanswered or busy reads as not idle.
	 */
	static final class ServerIdleWatch
	{
		static final long AFTER_MS = 60_000, EVERY_MS = 15_000;

		private final String url;

		private final long start, after, every;

		private long last;

		private int idleInARow;

		ServerIdleWatch( final String serverUrl, final long start )
		{
			this( serverUrl, start, AFTER_MS, EVERY_MS );
		}

		ServerIdleWatch( final String serverUrl, final long start, final long after, final long every )
		{
			this.url = serverUrl + "/state/process";
			this.start = start;
			this.after = after;
			this.every = every;
		}

		boolean idleAgain()
		{
			final long now = System.currentTimeMillis();
			if ( now - start < after || now - last < every )
				return sawIdle();
			last = now;
			try
			{
				final HttpResponse< String > r = http().get( url ).socketTimeout( 5_000 ).connectTimeout( 5_000 ).asString();
				final boolean idle = r != null && r.isSuccess() && new JSONObject( r.getBody() ).optInt( "state", -1 ) == 0;
				idleInARow = idle ? idleInARow + 1 : 0;
			}
			catch ( final Exception e )
			{
				idleInARow = 0;
			}
			return sawIdle();
		}

		boolean sawIdle()
		{
			return idleInARow >= 2;
		}
	}

	// -----------------------------------------------------------------------
	// HTTP helper

	/**
	 * Sends a blocking HTTP POST that responds to thread interruption.
	 *
	 * <p>Unlike {@code Unirest.post(...).asString()}, which ignores
	 * {@code Thread.interrupt()} for the full 24-hour Unirest socket timeout,
	 * this method uses {@code asStringAsync().get()} whose {@code get()} call
	 * throws {@link InterruptedException} as soon as the thread is interrupted.
	 * When that happens the underlying future is cancelled (which closes the
	 * socket), the interrupt flag is restored, and {@code null} is returned.</p>
	 *
	 * @return the server response, or {@code null} if the thread was interrupted
	 */
	private HttpResponse< String > postBlocking( final String endpoint, final String body )
	{
		return postBlocking( endpoint, body, 0 );
	}

	/**
	 * As {@link #postBlocking(String, String)}, but bounded by {@code timeoutMs}: if the server has
	 * not responded within that time the future is cancelled (closing the socket) and {@code null}
	 * is returned. {@code timeoutMs <= 0} waits indefinitely.
	 *
	 * @return the server response, or {@code null} if the thread was interrupted or the wait timed out
	 */
	private HttpResponse< String > postBlocking( final String endpoint, final String body,
			final long timeoutMs )
	{
		return postBlocking( endpoint, body, timeoutMs, () -> false );
	}

	/** Polled while the request is pending: if the request is known finished, stop waiting for the reply. */
	private static final long DONE_POLL_MS = 1000;

	/**
	 * As {@link #postBlocking(String, String, long)}, but also returns {@code null} as soon as {@code done} says the
	 * request has finished by other means (its completion reported over RabbitMQ), without waiting for a reply
	 * that a dropped connection will never deliver.
	 */
	private HttpResponse< String > postBlocking( final String endpoint, final String body,
			final long timeoutMs, final java.util.function.BooleanSupplier done )
	{
		final CompletableFuture< HttpResponse< String > > future =
				http().post( endpoint )
						.header( "Content-Type", "application/json" )
						.body( body )
						.asStringAsync();
		try
		{
			final long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
			while ( true )
			{
				final long left = deadline - System.currentTimeMillis();
				if ( left <= 0 )
					throw new java.util.concurrent.TimeoutException();
				try
				{
					return future.get( Math.min( left, DONE_POLL_MS ), java.util.concurrent.TimeUnit.MILLISECONDS );
				}
				catch ( final java.util.concurrent.TimeoutException pending )
				{
					if ( done.getAsBoolean() )
					{
						future.cancel( true );
						return null;
					}
				}
			}
		}
		catch ( final InterruptedException e )
		{
			future.cancel( true );
			Thread.currentThread().interrupt();
			log.info( "HTTP POST to {} was interrupted.", endpoint );
			return null;
		}
		catch ( final java.util.concurrent.TimeoutException e )
		{
			future.cancel( true );
			log.warn( "HTTP POST to {} exceeded {} ms.", endpoint, timeoutMs );
			return null;
		}
		catch ( final ExecutionException e )
		{
			final Throwable cause = e.getCause();
			if ( cause instanceof RuntimeException )
				throw ( RuntimeException ) cause;
			throw new RuntimeException( cause );
		}
	}

	// -----------------------------------------------------------------------
	// Helpers

	/**
	 * Builds a JSON request body for the ELEPHANT /seg/predict endpoint.
	 *
	 * @param timepoint the timepoint to predict
	 * @return JSON request object
	 */
	private JSONObject buildPredictRequest( final int timepoint )
	{
		final ElephantMainSettings mainSettings =
				ElephantMainSettingsManager.getInstance().getSelectedStyle();

		final JSONObject request = new JSONObject();
		request.put( "dataset_name", mainSettings.getDatasetName() );
		request.put( "model_name", mainSettings.getDetectionModelName() );
		request.put( "timepoint", timepoint );
		request.put( "p_thresh", mainSettings.getProbThreshold() );
		request.put( "r_min", mainSettings.getMinRadius() );
		request.put( "r_max", mainSettings.getMaxRadius() );
		request.put( "c_ratio", mainSettings.getCenterRatio() );
		request.put( "use_2d", mainSettings.getUse2dModel() );
		request.put( "batch_size", mainSettings.getBatchSize() );
		request.put( "debug", false );
		request.put( "output_prediction", false );
		request.put( "use_median", mainSettings.getMedianCorrection() );
		request.put( "is_pad", mainSettings.getPad() );
		request.put( "use_memmap", mainSettings.getUseMemmap() );
		request.put( "cache_maxbytes", mainSettings.getCacheMaxbytes() );

		final AbstractSpimData< ? > spimData =
				context.getProjectModel().getSharedBdvData().getSpimData();
		final BasicViewSetup setup =
				( BasicViewSetup ) spimData.getSequenceDescription().getViewSetupsOrdered().get( 0 );

		final Dimensions dims = setup.getSize();
		final boolean is3d = isVolumetric( dims );
		request.put( "is_3d", is3d );

		final long rx = Math.round( dims.dimension( 0 ) * mainSettings.getRescaleX() );
		final long ry = Math.round( dims.dimension( 1 ) * mainSettings.getRescaleY() );
		final JSONArray inputSize = new JSONArray().put( rx ).put( ry );

		final VoxelDimensions voxelDims = setup.getVoxelSize();
		final JSONArray scales = new JSONArray()
				.put( voxelDims.dimension( 0 ) )
				.put( voxelDims.dimension( 1 ) );

		int nKeepAxials = 0;
		if ( is3d )
		{
			final long rz = Math.round( dims.dimension( 2 ) * mainSettings.getRescaleZ() );
			inputSize.put( rz );
			scales.put( voxelDims.dimension( 2 ) );

			// n_keep_axials: number of pooling levels to keep in the Z axis.
			// Mirrors ElephantGraphActionMixin.calculateCropBoxAround() / BdvDataMixin.getNKeepAxials().
			// Rescaled voxel size: raw / rescale.  Anisotropy = rescaled_Z / rescaled_X.
			final double rescaledX = voxelDims.dimension( 0 ) / mainSettings.getRescaleX();
			final double rescaledZ = voxelDims.dimension( 2 ) / mainSettings.getRescaleZ();
			nKeepAxials = computeNKeepAxials( rescaledZ / rescaledX );
		}
		request.put( "input_size", inputSize );
		request.put( "scales", scales );
		request.put( "n_keep_axials", nKeepAxials );

		// patch: tile size for chunked inference — required to avoid CUDA OOM on large images.
		if ( mainSettings.getPatch() )
		{
			final JSONArray patch = new JSONArray()
					.put( mainSettings.getPatchSizeX() )
					.put( mainSettings.getPatchSizeY() );
			if ( is3d )
				patch.put( mainSettings.getPatchSizeZ() );
			request.put( "patch", patch );
		}

		return request;
	}

	/**
	 * Whether the dataset has a real Z axis. BDV/SpimData setups always report 3
	 * dimensions, even for genuinely 2D data (Z size 1) — so {@code is_3d} sent to
	 * ELEPHANT must be derived from the actual Z extent, not hardcoded, otherwise the
	 * server builds a 3D output shape for an image that only has 2 spatial dims.
	 *
	 * @param dims dataset size as reported by the BDV setup
	 * @return {@code true} if the Z dimension has more than one plane
	 */
	private static boolean isVolumetric( final Dimensions dims )
	{
		return dims.numDimensions() >= 3 && dims.dimension( 2 ) > 1;
	}

	/**
	 * Mirrors {@code BdvDataMixin.getNKeepAxials()}: returns the number of
	 * Z-axis pooling levels to preserve in the U-Net encoder based on the
	 * Z/X voxel-size anisotropy ratio.
	 */
	private static int computeNKeepAxials( final double anisotropy )
	{
		if ( anisotropy <= 1.0 )
			return 0;
		final int a = ( int ) anisotropy;
		final int floorLog2 = 31 - Integer.numberOfLeadingZeros( a );
		final int ceilLog2 = 32 - Integer.numberOfLeadingZeros( Math.max( 0, a - 1 ) );
		return ( anisotropy - Math.pow( 2, floorLog2 ) < Math.pow( 2, ceilLog2 ) - anisotropy )
				? floorLog2 : ceilLog2;
	}

	/**
	 * Adds a detected spot to the Mastodon graph.
	 *
	 * @param spotJson JSON object containing spot data
	 * @param timepoint the timepoint of the spot
	 * @return {@code true} if the spot was successfully added
	 */
	private boolean addSpotFromJson( final JSONObject spotJson, final int timepoint )
	{
		try
		{
			final JSONArray posArray = spotJson.getJSONArray( "pos" );
			final double[] worldPos = new double[] {
					posArray.getDouble( 0 ),
					posArray.getDouble( 1 ),
					posArray.getDouble( 2 )
			};

			// Parse the 3×3 covariance matrix (row-major, 9 values) from ELEPHANT response.
			// This defines the ellipsoid shape — same approach as PredictSpotsAction.
			final JSONArray covArray = spotJson.getJSONArray( "covariance" );
			final double[][] covariance = new double[ 3 ][ 3 ];
			for ( int row = 0; row < 3; row++ )
				for ( int col = 0; col < 3; col++ )
					covariance[ row ][ col ] = covArray.getDouble( row * 3 + col );

			final Model model = context.getModel();
			final ModelGraph graph = context.getGraph();
			// Modifies the tag-set STRUCTURE, so it has to happen before the vertex write lock — and it
			// is what lets GraphEdits find the tp tag from inside one.
			DetectionTagLabels.ensure( model );
			final Spot ref = graph.vertexRef();

			try
			{
				graph.getLock().writeLock().lock();
				try
				{
					// Reject a fresh detection that duplicates an already-tracked, human-confirmed
					// (protected) cell — never let ELEPHANT's own re-detection sit on top of / replace it.
					if ( GraphEdits.addSpot( context, graph, ref, timepoint, worldPos, covariance,
							GraphEdits.Reason.DETECTION ) == null )
					{
						log.debug( "Skipped detection at ({}, {}, {}) t={} — overlaps a protected spot",
								worldPos[ 0 ], worldPos[ 1 ], worldPos[ 2 ], timepoint );
						return false;
					}
					// "quality" is absent for older servers — has() guards against ever recording a
					// spurious value in that case, so callers can rely on DetectionQuality.get() being
					// empty for "no signal" rather than a stray NaN/0.0.
					if ( spotJson.has( "quality" ) )
						DetectionQuality.set( graph, ref, spotJson.optDouble( "quality", Double.NaN ) );
					// The tp tag that corrects ELEPHANT's graph listener is applied by
					// GraphEdits.addSpot above, for every reason this project adds a spot rather than
					// only this one. ensure() below stays because it is the one place with no vertex
					// write lock held, and the chokepoint can only use a tag set that already exists.
					log.debug( "Added ellipsoid spot at ({}, {}, {}) t={}", worldPos[ 0 ], worldPos[ 1 ],
							worldPos[ 2 ], timepoint );
				}
				finally
				{
					graph.getLock().writeLock().unlock();
				}

				model.setUndoPoint();
				return true;
			}
			finally
			{
				graph.releaseRef( ref );
			}
		}
		catch ( final Exception e )
		{
			log.error( "Failed to add spot from JSON", e );
			return false;
		}
	}
}
