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
package org.mastodon.mamut.automahout.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.mastodon.mamut.automahout.eval.DetectionAcceptanceProfile;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.eval.LineageConstraintProfile;
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.GraphIntegrity;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.SpotOriginDump;
import org.mastodon.mamut.automahout.tool.ToolRegistry;
import org.mastodon.mamut.automahout.tool.impl.AnalyzeVideoTool;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.mamut.automahout.vision.LlmMessage;
import org.mastodon.mamut.automahout.vision.VlmBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agentic loop that drives autonomous cell tracking via an LLM.
 *
 * <p>The orchestrator:</p>
 * <ol>
 *   <li>Builds a system prompt containing the video analysis report, available
 *       tools, and current tracking state.</li>
 *   <li>Enters a conversation loop: asks the LLM for the next action, executes
 *       the chosen tool, feeds the result back.</li>
 *   <li>Exits when the LLM responds with {@code "action": "done"} or the
 *       iteration limit is reached.</li>
 * </ol>
 *
 * <p>The LLM communicates exclusively via JSON:</p>
 * <pre>
 * Tool call: {"reasoning": "...", "action": "tool_call", "tool": "...", "params": {...}}
 * Ask user:  {"reasoning": "...", "action": "ask_user", "question": "...", "options": [...]}
 * Done:      {"reasoning": "...", "action": "done", "summary": "..."}
 * </pre>
 *
 * <p>Only {@code adaptive_track} (a wrapper that always runs ELEPHANT's integrated adaptive
 * pipeline) and {@code auto.correct} are exposed to the agent. The raw {@code elephant} tool and
 * fine-grained manual tools (spot.add, link.edit, …) are excluded.</p>
 */
public class LlmOrchestrator
{

	private static final Logger log = LoggerFactory.getLogger( LlmOrchestrator.class );

	/**
	 * Tool IDs exposed to the agent. The detection/tracking entry point is the
	 * {@code adaptive_track} wrapper (not the raw {@code elephant} tool), so the agent cannot
	 * select a degenerate {@code mode} and bypass the adaptive pipeline.
	 */
	private static final Set< String > EXPOSED_TOOLS = new HashSet<>(
			Arrays.asList( "adaptive_track", "auto.correct" ) );

	/** Default maximum number of LLM turns before giving up. */
	public static final int DEFAULT_MAX_ITERATIONS = 20;

	private final ToolContext context;

	private final ToolRegistry registry;

	private final VlmBackend backend;

	private final String model;

	private final int maxIterations;

	// -----------------------------------------------------------------------

	/**
	 * Creates an orchestrator.
	 *
	 * <p>Progress messages are forwarded to {@link ToolContext#log(String)}.
	 * Set a callback on {@code context} via
	 * {@link ToolContext#setLogCallback(java.util.function.Consumer)} before
	 * calling {@link #run(String)} if UI feedback is desired.</p>
	 *
	 * @param context       shared Mastodon context
	 * @param registry      tool registry
	 * @param backend       LLM backend (Anthropic or Ollama)
	 * @param model         model name / tag
	 * @param maxIterations maximum LLM conversation turns
	 */
	public LlmOrchestrator(
			final ToolContext context,
			final ToolRegistry registry,
			final VlmBackend backend,
			final String model,
			final int maxIterations )
	{
		this.context = context;
		this.registry = registry;
		this.backend = backend;
		this.model = model;
		this.maxIterations = maxIterations;
	}

	// -----------------------------------------------------------------------

	/**
	 * Runs the agentic tracking loop.
	 *
	 * @param videoDescription Markdown video analysis report (from
	 *                         {@code video.analyze}); used as context for the
	 *                         LLM's strategy decisions
	 * @return a final summary string describing what was accomplished
	 * @throws IOException if the LLM backend call fails
	 */
	public String run( final String videoDescription ) throws IOException
	{
		final String toolSchemas = buildToolSchemas();
		final String initialState = buildStateSnapshot();

		final List< LlmMessage > messages = new ArrayList<>();
		messages.add( LlmMessage.user( buildSystemPrompt( videoDescription, toolSchemas, initialState ) ) );

		notify( "=== Autonomous tracking agent started ===" );
		notify( "Using model: " + model );

		// Enforced regardless of the agent's own reasoning (see the auto.correct dispatch check
		// below): Phase 2 (auto.correct) must have something to correct. That is true once Phase 1
		// (adaptive_track) has run THIS session, and it is equally true when the project ALREADY
		// holds a finished tracking result from a previous session — the user's "tracking is done,
		// just clean it up" case. The gate exists to stop cleanup running on an empty or barely
		// populated graph (e.g. a handful of protected spots surviving a "Start completely fresh"
		// reset), NOT to force a re-run of detection the user explicitly does not want.
		boolean adaptiveTrackRanThisSession = false;
		final String preExistingTracking = describePreExistingTracking();
		if ( preExistingTracking != null )
			notify( "Phase 1 gate satisfied without running adaptive_track: " + preExistingTracking
					+ " — auto.correct (Phase 2) may run directly on it." );

		for ( int iteration = 1; iteration <= maxIterations; iteration++ )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
			{
				notify( "=== Stop requested — aborting agent loop. ===" );
				return "Autonomous tracking stopped by user.";
			}
			notify( String.format( "[Turn %d/%d] Querying LLM...", iteration, maxIterations ) );

			final String reply;
			try
			{
				reply = backend.chat( model, messages );
			}
			catch ( final IOException e )
			{
				notify( "LLM call failed: " + e.getMessage() );
				throw e;
			}

			log.debug( "LLM reply (turn {}): {}", iteration, reply );
			messages.add( LlmMessage.assistant( reply ) );

			// Parse the JSON response
			final JSONObject parsed = extractJson( reply );
			if ( parsed == null )
			{
				notify( "Warning: LLM response was not valid JSON — retrying." );
				messages.add( LlmMessage.user(
						"Your response was not valid JSON. "
								+ "Reply ONLY with a JSON object matching the specified format." ) );
				continue;
			}

			final String action = parsed.optString( "action", "" );
			final String reasoning = parsed.optString( "reasoning", "" );

			if ( !reasoning.isEmpty() )
				notify( "Reasoning: " + reasoning );

			if ( "done".equals( action ) )
			{
				final String summary = parsed.optString( "summary",
						"Tracking complete." );
				notify( "=== Agent finished: " + summary + " ===" );
				return summary;
			}

			// Human-in-the-loop: the agent may consult the user for a decision or approval when it
			// cannot resolve something autonomously.
			if ( "ask_user".equals( action ) )
			{
				final String question = parsed.optString( "question", "" );
				final List< String > options = jsonArrayToStringList( parsed.optJSONArray( "options" ) );
				notify( "Asking user: " + question
						+ ( options.isEmpty() ? "" : " options=" + options ) );
				final String answer = context.askUser( question, options );
				if ( answer == null || answer.trim().isEmpty() )
				{
					notify( "No user response available — instructing agent to proceed autonomously." );
					messages.add( LlmMessage.user(
							"No human response is available (headless run, or the user dismissed the "
									+ "prompt). Proceed autonomously using your best judgement; if you "
									+ "genuinely cannot, declare done with a summary explaining what "
									+ "manual step the user must take." ) );
				}
				else
				{
					notify( "User responded: " + answer );
					messages.add( LlmMessage.user(
							"The user responded to your question with: \"" + answer
									+ "\". Continue accordingly." ) );
				}
				continue;
			}

			if ( !"tool_call".equals( action ) )
			{
				notify( "Warning: unknown action '" + action + "' — asking LLM to clarify." );
				messages.add( LlmMessage.user(
						"Unrecognised action '" + action + "'. Use action=\"tool_call\", "
								+ "\"ask_user\", or \"done\"." ) );
				continue;
			}

			// Execute the requested tool
			final String toolId = parsed.optString( "tool", "" );
			if ( toolId.isEmpty() || !EXPOSED_TOOLS.contains( toolId ) )
			{
				notify( "Warning: LLM requested unavailable tool '" + toolId + "'." );
				messages.add( LlmMessage.user(
						"Tool '" + toolId + "' is not available. "
								+ "Choose from: " + EXPOSED_TOOLS + "." ) );
				continue;
			}

			if ( "auto.correct".equals( toolId ) && !adaptiveTrackRanThisSession
					&& preExistingTracking == null )
			{
				notify( "Blocked: auto.correct requested with no tracking to correct — "
						+ "redirecting the agent to Phase 1." );
				messages.add( LlmMessage.user(
						"auto.correct (Phase 2) was BLOCKED: there is no tracking result to clean up. "
								+ "adaptive_track (Phase 1) has not run this session, and the graph this "
								+ "run started from is too small or too fragmented to be a finished "
								+ "tracking result (it needs at least " + PREEXISTING_TRACKING_MIN_SPOTS
								+ " spots spanning " + PREEXISTING_TRACKING_MIN_TIMEPOINTS
								+ " timepoints at >=" + Math.round( PREEXISTING_TRACKING_MIN_CONTINUITY * 100 )
								+ "% continuity). A few spots left over from a reset are not a tracking "
								+ "result. Call adaptive_track first." ) );
				continue;
			}

			final JSONObject paramsJson = parsed.optJSONObject( "params" );
			final Map< String, Object > params = paramsJson != null
					? jsonObjectToMap( paramsJson )
					: new HashMap< String, Object >();

			notify( "Executing tool: " + toolId
					+ ( params.isEmpty() ? "" : " params=" + params ) );

			if ( "adaptive_track".equals( toolId ) )
				adaptiveTrackRanThisSession = true;

			String toolResult;
			try
			{
				final AutomahoutTool tool = registry.get( toolId )
						.orElseThrow( () -> new IllegalArgumentException(
								"Tool not found: " + toolId ) );
				toolResult = tool.execute( context, params );
			}
			catch ( final GraphIntegrity.DamagedGraphException e )
			{
				// Not a tool failure the agent can work around: every later edit could hang on the
				// damaged lists. End the run here.
				throw e;
			}
			catch ( final Exception e )
			{
				toolResult = "Error: " + e.getMessage();
				log.warn( "Tool '{}' threw an exception", toolId, e );
			}

			notify( "Tool result: " + toolResult );
			SpotOriginDump.snapshot( context, toolId );
			org.mastodon.mamut.automahout.tool.GraphWatch.mark( context, toolId );

			// Visual assessment via VLM after each action
			final String visualAssessment = assessVisually( toolId, toolResult );

			final String updatedState = buildStateSnapshot();
			final StringBuilder feedback = new StringBuilder();
			feedback.append( "Tool '" ).append( toolId ).append( "' result:\n" )
					.append( toolResult );
			feedback.append( "\n\nUpdated quantitative state:\n" ).append( updatedState );
			if ( visualAssessment != null )
			{
				feedback.append( "\n\nVisual assessment (VLM analysis of current BDV snapshots):\n" );
				feedback.append( visualAssessment );
			}
			feedback.append( "\n\nBased on the above observation, decide your next action." );
			messages.add( LlmMessage.user( feedback.toString() ) );
		}

		// Reached iteration limit
		notify( "=== Iteration limit reached (" + maxIterations + "). Stopping. ===" );
		return "Autonomous tracking stopped after " + maxIterations
				+ " iterations. Check the tracking result in Mastodon.";
	}

	// -----------------------------------------------------------------------
	// Prompt construction

	/**
	 * Loads the active learned lineage-constraint profile (via the persisted
	 * {@link AutomahoutSettings#getConstraintsProfilePath()}), or {@code null} if none is configured or
	 * it cannot be read. When present, its grammar is injected into the system prompt.
	 */
	private LineageConstraintProfile loadActiveConstraintProfile()
	{
		// Scoped to the open dataset: the prompt states a profile's numbers to the agent as this movie's
		// grammar, so another movie's would be asserted as fact here too.
		return LineageConstraintProfile.loadForDataset(
				new AutomahoutSettings().getConstraintsProfilePath(),
				AutoCorrectOrchestrator.currentElephantDataset(), null );
	}

	/**
	 * Loads the active learned detection-acceptance profile (via
	 * {@link AutomahoutSettings#getAcceptanceProfilePath()}), or {@code null} if none/unreadable. When
	 * present, its cell-vs-background rule is injected into the system prompt.
	 */
	private DetectionAcceptanceProfile loadActiveAcceptanceProfile()
	{
		final String path = new AutomahoutSettings().getAcceptanceProfilePath();
		if ( path == null || path.trim().isEmpty() )
			return null;
		try
		{
			return DetectionAcceptanceProfile.load( path.trim() );
		}
		catch ( final Exception e )
		{
			return null;
		}
	}

	private String buildSystemPrompt(
			final String videoDescription,
			final String toolSchemas,
			final String initialState )
	{
		final StringBuilder sb = new StringBuilder();
		sb.append( "You are an autonomous cell tracking orchestrator for Mastodon, " );
		sb.append( "a large-scale cell tracking application integrated with ELEPHANT " );
		sb.append( "deep learning.\n\n" );

		sb.append( "## Your Goal\n" );
		sb.append( "Complete the full cell tracking pipeline adaptively:\n" );
		sb.append( "Phase 1 — Adaptive tracking: per-window integrated pipeline " );
		sb.append( "(detect → train if needed → re-detect → link → evaluate + correct → validate).\n" );
		sb.append( "Phase 2 — Global topology cleanup: auto.correct across all timepoints.\n\n" );

		sb.append( "## Dataset Video Analysis\n" );
		sb.append( videoDescription ).append( "\n\n" );

		sb.append( "## Available Tools\n" );
		sb.append( toolSchemas ).append( "\n\n" );

		sb.append( "## Current Tracking State\n" );
		sb.append( initialState ).append( "\n\n" );

		// If a learned lineage-constraint profile is active, inject its "valid lineage grammar" so the
		// agent recognises impossible graph shapes and knows the learned repair for each.
		final LineageConstraintProfile profile = loadActiveConstraintProfile();
		if ( profile != null )
			sb.append( profile.promptSection() );

		// If a learned detection-acceptance profile is active, inject the cell-vs-background rule (learned
		// from human tp/fp labels) plus its confidence + ask_user escalation hint.
		final DetectionAcceptanceProfile acceptance = loadActiveAcceptanceProfile();
		if ( acceptance != null )
			sb.append( acceptance.promptSection() );

		sb.append( "## Decision Framework\n" );
		sb.append( "After EVERY action you receive:\n" );
		sb.append( "  - Updated quantitative state (total spots, links, timepoints)\n" );
		sb.append( "  - Visual assessment: VLM analysis of the current BDV view snapshots\n\n" );
		sb.append( "Use BOTH signals to decide your next action:\n\n" );

		sb.append( "### Phase 1: Adaptive Tracking\n" );
		sb.append( "Run the integrated per-window pipeline that handles training and tracking together:\n" );
		sb.append( "  Tool: adaptive_track (runs the full adaptive pipeline; no mode to choose)\n" );
		sb.append( "  Params: window_size=10, n_annot_per_window=2, n_iterations=2, " );
		sb.append( "min_intensity_ratio=0.25, track_intensity_ratio=0, min_spots_per_tp=5, " );
		sb.append( "correct_per_window=true, dedup_factor=0.4, " );
		sb.append( "border_margin=0, max_aspect_ratio=6.0, " );
		sb.append( "long_link_factor=1.5, merge_overlap_ratio=0.3, n_crops=10\n" );
		sb.append( "  (min_intensity_ratio is deliberately LOW to keep dim cells — a high value "
				+ "makes the model miss faint cells.)\n" );
		sb.append( "  (track_intensity_ratio defaults to 0 = OFF: it deletes any detection dimmer than "
				+ "this fraction of the timepoint's median, which can discard real cells that are simply "
				+ "dimming over time (e.g. photobleaching), not just noise. Only raise it above 0 if the "
				+ "visual clearly shows background noise being detected as spots.)\n" );
		sb.append( "  (min_size defaults to 0 = OFF — no size filtering, to avoid clipping small/dim "
				+ "real cells. Only set a positive min_size if the visual clearly shows tiny "
				+ "background-noise specks being detected.)\n\n" );
		sb.append( "For each 10-timepoint window the tool automatically:\n" );
		sb.append( "  1. Tests detection quality (spots/tp, CV_temporal, CV_size)\n" );
		sb.append( "  1b. NMS deduplication: removes overlapping spots (dedup_factor controls aggressiveness)\n" );
		sb.append( "  2. Always fine-tunes the model at least once (density is not a correctness guarantee)\n" );
		sb.append( "  3. Re-detects with the updated model + NMS deduplication\n" );
		sb.append( "  4. Links spots backward within the window\n" );
		sb.append( "  5. Corrects tracking errors (merge duplicates, remove long links)\n" );
		sb.append( "  5b. Refinement (refine_pass, default ON): ITERATES 'interpolate missed cells → "
				+ "retrain → re-detect' until detection COMPLETENESS reaches its target — this is how "
				+ "recall is bootstrapped up from a randomly-initialised model. Leave it on.\n" );
		sb.append( "  6. Validates: continuity, detection completeness (recall vs image maxima), "
				+ "retention, isolated spots\n\n" );
		sb.append( "Quality thresholds per window: continuity>=70%%, completeness>=target (default "
				+ "80%%), recall stable.\n" );
		sb.append( "The tool logs per-window reports including WARNINGs for failed windows.\n\n" );
		sb.append( "After adaptive_track completes, use the tool result and visual assessment to decide:\n" );
		sb.append( "  - If continuity is GOOD and recall is stable: proceed to Phase 2.\n" );
		sb.append( "  - If continuity is LOW or many WARNINGs: re-run adaptive_track with " );
		sb.append( "n_iterations=3 and/or lower min_intensity_ratio=0.2.\n" );
		sb.append( "  - RECALL vs PRECISION (two opposite failures — diagnose which one you have "
				+ "BEFORE changing a threshold):\n" );
		sb.append( "      * MISSED CELLS (low recall): the visual shows clear cell bodies with NO "
				+ "ellipsoid. Fix: LOWER min_intensity_ratio (e.g. 0.15-0.2) to recover dim cells.\n" );
		sb.append( "      * BACKGROUND NOISE (low precision): many tiny spots on background specks. "
				+ "Fix: set a positive min_size (reject sub-cellular detections) and/or RAISE "
				+ "min_intensity_ratio. NEVER lower min_intensity_ratio to chase 'missing' cells when "
				+ "noise is present — the missing ones are likely noise, not cells.\n" );
		sb.append( "    High continuity compensates for neither; verify against the visual.\n" );
		sb.append( "  - If visual shows overlapping/duplicate spots: increase dedup_factor (e.g. 0.6).\n" );
		sb.append( "  - COLD START (untrained model): if the visual shows a dense mess of tiny "
				+ "ellipsoids covering the whole image (thousands of spots) the model is untrained. "
				+ "adaptive_track handles this automatically — it discards the noise and bootstraps "
				+ "from intensity seeds. If its result says 'MANUAL ANNOTATION REQUIRED', autonomous "
				+ "bootstrap failed: STOP and report to the user that they must place a few spot "
				+ "annotations on clear cells and re-run. Do NOT loop adaptive_track in that case.\n" );
		sb.append( "  - If border/elongated fraction is high (>30%% in logs): " );
		sb.append( "model is placing detections at cell edges rather than centers. " );
		sb.append( "Re-run adaptive_track — training will exclude those partial detections and " );
		sb.append( "teach the model to center ellipsoids. " );
		sb.append( "Set border_margin to less than image_min_dimension/4 (image physical size is " );
		sb.append( "logged in step 1 output, e.g. '100x80x15 µm' → border_margin<3.75). " );
		sb.append( "border_margin=0 disables border filtering. Lower max_aspect_ratio to exclude " );
		sb.append( "more elongated spots.\n" );
		sb.append( "  - If specific time ranges are bad (noted in logs): re-run adaptive_track " );
		sb.append( "with start_timepoint and end_timepoint set to just that range.\n\n" );
		sb.append( "Quality interpretation (from tool result and visual assessment):\n" );
		sb.append( "  The tool result reports 'continuity: mean=N%%, worst=M%%' — this is the PRIMARY "
				+ "signal. Continuity is the fraction of interior spots that have both a predecessor "
				+ "and a successor link (fragmented tracks score low; divisions are not penalised).\n" );
		sb.append( "  GOOD: continuity>=70%%, few WARNINGs, ellipsoids on cells, similar sizes, " );
		sb.append( "consistent counts across adjacent timepoints, no overlapping ellipsoids.\n" );
		sb.append( "  POOR: continuity<70%%, many WARNINGs, spots on background, wildly varying " );
		sb.append( "ellipsoid sizes, count swings between frames, multiple ellipsoids on one cell.\n" );
		sb.append( "  Spot count and link_density are NOT quality indicators — a stray single link "
				+ "makes link_density 100%% even when every track is fragmented.\n" );
		sb.append( "  The tool result also reports 'divisions=N', which continuity CANNOT see — read it "
				+ "before concluding anything about a changing cell count. A count that RISES across "
				+ "the movie while divisions keep pace with it is PROLIFERATION, and moving a detection "
				+ "threshold is the wrong response; the result says which case you are in. If it says "
				+ "divisions are being MISSED, the daughters are already detected and the problem is in "
				+ "LINKING — do not lower min_intensity_ratio for it.\n\n" );

		sb.append( "### Phase 2: Topology Cleanup\n" );
		sb.append( "Only run after Phase 1 produces spots (total_spots > 0) AND Phase 1 track "
				+ "continuity is GOOD (>=70%). Cleaning up fragmented tracks does not fix "
				+ "fragmentation — go back to Phase 1 first.\n" );
		sb.append( "  Tool: auto.correct\n" );
		sb.append( "  Params: t=-1, long_link_factor=1.5, merge_overlap_ratio=0.3\n" );
		sb.append( "  By default this pass keeps merged spots cell-sized (no ballooning ellipsoids) "
				+ "and fills gaps — reconstructing cells missing from a track's interior using the "
				+ "neighbouring timepoints. This also recovers missing predecessors (re-detects a cell "
				+ "at t-1), which dissolves FALSE divisions (a daughter force-linked because its real "
				+ "predecessor was missed) and connects isolated post-division cells to their origin. "
				+ "As a final step it reconnects any post-division daughter tracklets still left "
				+ "dangling (orphan starts near a dividing mother). "
				+ "Leave fill_gaps at its default.\n" );
		sb.append( "  DO NOT include min_branch_length unless you have a specific reason. " );
		sb.append( "min_branch_length deletes daughter cells whose forward links were broken by " );
		sb.append( "detection failure — in dividing cultures this causes massive false negatives.\n" );
		sb.append( "  min_division_interval: NOT needed — automatically estimated from cell count growth " );
		sb.append( "rate and applied to remove linking errors (rapid successive divisions).\n" );
		sb.append( "  Tool result reports: topology: divisions=N, min_div_interval=M (threshold=auto=K)\n" );
		sb.append( "    - K=auto=0: no growth detected, division removal skipped\n" );
		sb.append( "    - K=auto=N: divisions within N tp of an ancestor division are linking errors\n" );
		sb.append( "    - If min_div_interval < K: rapid divisions were removed\n" );
		sb.append( "    - If topology shows 'no divisions' but division is expected: " );
		sb.append( "linking failed — re-run adaptive_track with division_link_factor=1.5\n" );
		sb.append( "  MANDATORY: do NOT include min_intensity_ratio or min_tracklet_length here.\n\n" );

		sb.append( "### Done\n" );
		sb.append( "Declare done ONLY when ALL of these hold:\n" );
		sb.append( "  1. Track continuity is GOOD (>=70%%) — reported in the tool result and in the "
				+ "quantitative state ('Track continuity: N%% ...'). If it is POOR/FRAGMENTED, you are "
				+ "NOT done: re-run adaptive_track (higher n_iterations, e.g. 3; and/or lower "
				+ "min_intensity_ratio, e.g. 0.2) and only then Phase 2. Repeat until continuity is "
				+ "GOOD or it stops improving across two consecutive adaptive_track runs.\n" );
		sb.append( "  2. Detection is COMPLETE — the tool result's 'completeness: worst=N%%' is at or "
				+ "above target (default 80%%). Completeness = detected ÷ estimated cell count from the "
				+ "image; it is the ONLY signal that catches cells missed in EVERY frame (which "
				+ "continuity/recall cannot). If it says 'DETECTION INCOMPLETE', you are NOT done: "
				+ "re-run adaptive_track (higher refine_iterations, lower min_intensity_ratio). If it "
				+ "plateaus below target, ask_user (add annotations / adjust params).\n" );
		sb.append( "  3. Detection is clean — few/no ellipsoids on background specks (precision). If "
				+ "background noise is detected, set min_size and/or raise min_intensity_ratio.\n" );
		sb.append( "  4. Phase 2 (topology cleanup) has been run on the continuous tracks.\n" );
		sb.append( "You have up to 20 turns — do NOT stop early just because spot/link counts or "
				+ "continuity look high. High continuity with missing cells is still a FAILURE.\n" );
		sb.append( "Otherwise, declare done only when a genuine failure condition is reached "
				+ "(e.g. continuity/recall do not improve after repeated Phase 1 runs).\n\n" );

		sb.append( "## Critical Warnings\n" );
		sb.append( "- **total_spots == 0 is a FAILURE, never 'done'.** The quantitative state is "
				+ "authoritative: if it says 0 spots, detection failed or a step deleted everything — "
				+ "the visual assessment saying 'detection looks great' is a hallucination and must be "
				+ "IGNORED. Re-run adaptive_track; never declare done at 0 spots.\n" );
		sb.append( "- **Detection/tracking is done ONLY via the adaptive_track tool** (see Phase 1).\n" );
		sb.append( "- **If auto.correct reports deleted spots leaving ~0 (e.g. deleted spots≈total), "
				+ "detection was garbage (false-positive flood from an untrained model).** Do NOT keep "
				+ "cleaning — go back to Phase 1 adaptive_track, which bootstraps an untrained model.\n" );
		sb.append( "- **Track continuity is the primary quality signal, NOT spot/link counts.** "
				+ "A stray single link inflates link_density to 100%% on fully fragmented tracks.\n" );
		sb.append( "- For Phase 2 across the full dataset pass **t=-1** to auto.correct "
				+ "(there is no all_timepoints parameter).\n" );
		sb.append( "- **NEVER use min_intensity_ratio > 0 on auto.correct.** " );
		sb.append( "It destroys valid spots whose neighbors were not yet detected.\n" );
		sb.append( "- **NEVER use min_tracklet_length > 0 on the first auto.correct pass.** " );
		sb.append( "Newly detected spots are not yet in long tracklets.\n" );
		sb.append( "- **Do NOT run auto.correct if total_spots == 0.**\n" );
		sb.append( "- division_link_factor > 0 enables division detection; " );
		sb.append( "set to 0 if cell division is unlikely.\n\n" );

		sb.append( "## Consulting the User (human-in-the-loop)\n" );
		sb.append( "When you cannot resolve something autonomously, use action=ask_user to let the "
				+ "human decide or approve — do NOT silently guess or give up. Consult the user when:\n" );
		sb.append( "  - Cold-start bootstrap reports 'MANUAL ANNOTATION REQUIRED' (ask them to annotate "
				+ "and confirm when ready).\n" );
		sb.append( "  - Quality (continuity/recall) has plateaued below target after 2-3 attempts and "
				+ "you are out of parameter ideas (ask how to proceed or which parameter to change).\n" );
		sb.append( "  - A decision is genuinely ambiguous (e.g. whether apparent divisions are real).\n" );
		sb.append( "  - Before declaring done with a KNOWN imperfection, ask whether to accept the "
				+ "result or keep refining.\n" );
		sb.append( "Offer concrete options when you can. If the answer comes back empty (no UI), "
				+ "proceed with your best judgement. Do not ask more than necessary.\n\n" );

		sb.append( "## Response Format\n" );
		sb.append( "Respond ONLY with a JSON object — no text outside the JSON.\n" );
		sb.append( "Your reasoning field MUST reference the visual assessment and quantitative state " );
		sb.append( "that led to your decision.\n\n" );
		sb.append( "Tool call:\n" );
		sb.append( "{\"reasoning\": \"what I observed and why I chose this action\", " );
		sb.append( "\"action\": \"tool_call\", \"tool\": \"TOOL_ID\", \"params\": {...}}\n\n" );
		sb.append( "Ask the user (human-in-the-loop):\n" );
		sb.append( "{\"reasoning\": \"why I need the user\", \"action\": \"ask_user\", " );
		sb.append( "\"question\": \"the question\", \"options\": [\"option A\", \"option B\"]}\n" );
		sb.append( "(options is optional — omit for a free-text question. The user's answer is fed "
				+ "back to you.)\n\n" );
		sb.append( "Done:\n" );
		sb.append( "{\"reasoning\": \"what I observed\", \"action\": \"done\", " );
		sb.append( "\"summary\": \"description of what was accomplished\"}" );

		return sb.toString();
	}

	/**
	 * Captures BDV snapshots and asks the VLM to assess the current tracking quality.
	 * Returns null if snapshots could not be captured or the VLM call fails.
	 */
	private String assessVisually( final String toolId, final String toolResult )
	{
		final List< String > snapshots =
				AnalyzeVideoTool.captureAssessmentSnapshots( context, 3, 200 );
		if ( snapshots.isEmpty() )
			return null;

		final String prompt = "You are assessing the quality of automated cell tracking "
				+ "in a microscopy video viewed in BigDataViewer (BDV).\n"
				+ "The last action was: " + toolId + "\n"
				+ "Tool result summary: " + toolResult + "\n\n"
				+ "Based on the BDV snapshots provided, assess the following independently:\n"
				+ "1. DETECTION PRESENCE: Are ellipsoids visible overlaid on cells? "
				+ "Are there obvious missed cells (cell bodies with no ellipsoid)?\n"
				+ "2. SIZE UNIFORMITY: Are the ellipsoids similar in size within the same timepoint, "
				+ "or do sizes vary widely (small spots mixed with huge blobs)? "
				+ "High size variation suggests false positives or incorrect fitting.\n"
				+ "3. TEMPORAL CONSISTENCY: Do adjacent timepoints show similar numbers of detected cells "
				+ "and in similar positions? Large count swings between frames indicate poor consistency.\n"
				+ "4. FALSE POSITIVES: Are spots appearing on background, noise, or non-cell regions?\n"
				+ "5. OVERALL VERDICT: Is quality sufficient to proceed to full tracking "
				+ "(Phase 2), or does the model need more windowed fine-tuning (Phase 1)? "
				+ "Note: high spot count alone does NOT indicate good quality — "
				+ "assess size uniformity and temporal consistency as primary criteria.\n\n"
				+ "Give a concise assessment in 4-6 sentences, one per criterion.";

		try
		{
			return backend.analyze( model, prompt, snapshots );
		}
		catch ( final IOException e )
		{
			log.warn( "VLM visual assessment failed: {}", e.getMessage() );
			return null;
		}
	}

	private String buildToolSchemas()
	{
		final StringBuilder sb = new StringBuilder();
		for ( final AutomahoutTool tool : registry.getAll() )
		{
			if ( !EXPOSED_TOOLS.contains( tool.getId() ) )
				continue;
			sb.append( "### " ).append( tool.getId() ).append( "\n" );
			sb.append( tool.getDescription() ).append( "\n\n" );
		}
		return sb.toString().trim();
	}

	/**
	 * Minimum spots a graph must already hold, when the run starts, to count as a finished
	 * tracking result that Phase 2 may clean up without Phase 1 running again.
	 */
	static final int PREEXISTING_TRACKING_MIN_SPOTS = 500;

	/** Minimum interior track continuity for a pre-existing graph to count as finished tracking. */
	static final double PREEXISTING_TRACKING_MIN_CONTINUITY = 0.7;

	/** Minimum number of timepoints a pre-existing graph must span to count as finished tracking. */
	static final int PREEXISTING_TRACKING_MIN_TIMEPOINTS = 20;

	/**
	 * Decides whether the project already holds a finished tracking result — the user's "Phase 1 is
	 * done, only clean up" case — and describes it for the log.
	 * <p>
	 * Leftovers from a reset (a few protected spots, a stub of a run) must NOT qualify, so all three
	 * of spot count, timepoint span and continuity have to clear their thresholds together. Spot
	 * count alone would be fooled by a dense single frame; continuity alone by one short clean track.
	 *
	 * @return a human-readable description, or {@code null} if there is no such result
	 */
	private String describePreExistingTracking()
	{
		final ModelGraph graph = context.getGraph();
		int totalSpots = 0;
		int minTp = Integer.MAX_VALUE, maxTp = Integer.MIN_VALUE;
		int interiorTotal = 0, interiorContinuous = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				totalSpots++;
				final int t = s.getTimepoint();
				if ( t < minTp ) minTp = t;
				if ( t > maxTp ) maxTp = t;
			}
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t > minTp && t < maxTp )
				{
					interiorTotal++;
					if ( s.incomingEdges().size() > 0 && s.outgoingEdges().size() > 0 )
						interiorContinuous++;
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		final int span = totalSpots == 0 ? 0 : maxTp - minTp + 1;
		if ( !isFinishedTracking( totalSpots, span, interiorTotal, interiorContinuous ) )
			return null;

		return String.format(
				"this project already holds %d spot(s) across t=%d..%d at %.0f%% track continuity",
				totalSpots, minTp, maxTp,
				( double ) interiorContinuous / interiorTotal * 100 );
	}

	/**
	 * Whether these graph statistics describe a finished tracking result rather than leftovers.
	 * <p>
	 * All three conditions must hold together: spot count alone would be fooled by one densely
	 * detected frame, continuity alone by a single short clean track, and span alone by a sparse
	 * scattering of unlinked spots across the movie.
	 *
	 * @param totalSpots         spots in the graph
	 * @param timepointSpan      number of timepoints the spots cover (inclusive)
	 * @param interiorTotal      spots at interior timepoints (continuity denominator)
	 * @param interiorContinuous interior spots having both an in- and an out-link
	 */
	static boolean isFinishedTracking( final int totalSpots, final int timepointSpan,
			final int interiorTotal, final int interiorContinuous )
	{
		if ( totalSpots < PREEXISTING_TRACKING_MIN_SPOTS )
			return false;
		if ( timepointSpan < PREEXISTING_TRACKING_MIN_TIMEPOINTS )
			return false;
		if ( interiorTotal <= 0 )
			return false;
		return ( double ) interiorContinuous / interiorTotal >= PREEXISTING_TRACKING_MIN_CONTINUITY;
	}

	private String buildStateSnapshot()
	{
		final ModelGraph graph = context.getGraph();
		int totalSpots = 0;
		int totalLinks = 0;
		int minTp = Integer.MAX_VALUE, maxTp = Integer.MIN_VALUE;
		int interiorTotal = 0, interiorContinuous = 0, starts = 0, isolated = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( @SuppressWarnings( "unused" ) final Spot s : graph.vertices() )
				totalSpots++;
			for ( @SuppressWarnings( "unused" ) final Link l : graph.edges() )
				totalLinks++;
			// First pass: find the timepoint range so we can identify interior timepoints.
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < minTp ) minTp = t;
				if ( t > maxTp ) maxTp = t;
			}
			// Second pass: continuity — fraction of interior spots with both an in- and out-link.
			// This is a proxy for tracking correctness that (unlike link/spot counts) exposes
			// fragmented tracks; a stray single link does NOT satisfy it.
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				final boolean hasIn = s.incomingEdges().size() > 0;
				final boolean hasOut = s.outgoingEdges().size() > 0;
				if ( !hasIn ) starts++;
				if ( !hasIn && !hasOut ) isolated++;
				if ( t > minTp && t < maxTp )
				{
					interiorTotal++;
					if ( hasIn && hasOut )
						interiorContinuous++;
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		final String continuityNote;
		if ( totalSpots == 0 || interiorTotal == 0 )
			continuityNote = "";
		else
		{
			final double continuity = ( double ) interiorContinuous / interiorTotal;
			final double meanTrackLen = starts > 0 ? ( double ) totalSpots / starts : totalSpots;
			continuityNote = String.format(
					"\n- Track continuity: %.0f%% of interior spots have both in+out links "
							+ "(mean track length %.1f). %s"
							+ "\n- Isolated spots (no links at all): %d. %s",
					continuity * 100, meanTrackLen,
					continuity >= 0.7
							? "GOOD — tracks are continuous."
							: "POOR — tracks are FRAGMENTED; do NOT declare done, keep improving.",
					isolated,
					isolated > 0
							? "These are unlinked single detections — possible missed cells or false "
									+ "positives. Note: continuity is BLIND to cells that were never "
									+ "detected, so also check the visual for cell bodies without ellipsoids."
							: "None." );
		}

		final int numTimepoints;
		try
		{
			numTimepoints = context.requireViewer().state().getNumTimepoints();
		}
		catch ( final IllegalStateException e )
		{
			return "- Total spots: " + totalSpots + "\n- Total links: " + totalLinks + continuityNote;
		}

		if ( totalSpots == 0 )
			// A zero count after any processing is a hard failure — the agent must NOT declare done
			// and must NOT trust the VLM's "detection looks great" over this authoritative number.
			return "- Total spots: 0  <<< FAILURE: NO DETECTIONS. Either detection has not run, or a "
					+ "step (e.g. auto.correct on garbage over-detection) deleted everything. Do NOT "
					+ "declare done. Re-run the adaptive_track tool. The visual assessment "
					+ "is UNRELIABLE here and must not override this zero count.\n"
					+ "- Total links: " + totalLinks + "\n"
					+ "- Total timepoints: " + numTimepoints;
		return "- Total spots: " + totalSpots + "\n"
				+ "- Total links: " + totalLinks + "\n"
				+ "- Total timepoints: " + numTimepoints
				+ continuityNote;
	}

	// -----------------------------------------------------------------------
	// Helpers

	/** Logs at INFO level and forwards to the context log callback (if any). */
	private void notify( final String msg )
	{
		log.info( msg );
		context.log( msg );
	}

	/**
	 * Extracts the first valid JSON object from an LLM response string.
	 * Handles direct JSON, markdown code blocks, and JSON embedded in prose.
	 */
	static JSONObject extractJson( final String response )
	{
		if ( response == null )
			return null;

		// 1. Whole response is JSON
		final String trimmed = response.trim();
		if ( trimmed.startsWith( "{" ) )
		{
			try
			{
				return new JSONObject( trimmed );
			}
			catch ( final Exception e )
			{ /* fall through */ }
		}

		// 2. ```json ... ``` block
		final int jsonBlockStart = response.indexOf( "```json" );
		if ( jsonBlockStart >= 0 )
		{
			final int contentStart = response.indexOf( "\n", jsonBlockStart ) + 1;
			final int contentEnd = response.indexOf( "```", contentStart );
			if ( contentEnd > contentStart )
			{
				try
				{
					return new JSONObject(
							response.substring( contentStart, contentEnd ).trim() );
				}
				catch ( final Exception e )
				{ /* fall through */ }
			}
		}

		// 3. Any ``` block
		final int codeBlockStart = response.indexOf( "```" );
		if ( codeBlockStart >= 0 )
		{
			final int contentStart = response.indexOf( "\n", codeBlockStart ) + 1;
			final int contentEnd = response.indexOf( "```", contentStart );
			if ( contentEnd > contentStart )
			{
				try
				{
					return new JSONObject(
							response.substring( contentStart, contentEnd ).trim() );
				}
				catch ( final Exception e )
				{ /* fall through */ }
			}
		}

		// 4. First { ... } span in the response
		final int braceStart = response.indexOf( '{' );
		final int braceEnd = response.lastIndexOf( '}' );
		if ( braceStart >= 0 && braceEnd > braceStart )
		{
			try
			{
				return new JSONObject(
						response.substring( braceStart, braceEnd + 1 ) );
			}
			catch ( final Exception e )
			{ /* fall through */ }
		}

		return null;
	}

	/** Converts a {@link JSONObject} to a {@code Map<String, Object>}. */
	private static Map< String, Object > jsonObjectToMap( final JSONObject obj )
	{
		final Map< String, Object > map = new HashMap<>();
		for ( final String key : obj.keySet() )
			map.put( key, obj.get( key ) );
		return map;
	}

	/** Converts a {@link org.json.JSONArray} of strings to a {@code List<String>} (empty if null). */
	private static List< String > jsonArrayToStringList( final org.json.JSONArray arr )
	{
		final List< String > list = new ArrayList<>();
		if ( arr != null )
			for ( int i = 0; i < arr.length(); i++ )
				list.add( arr.optString( i, "" ) );
		return list;
	}
}
