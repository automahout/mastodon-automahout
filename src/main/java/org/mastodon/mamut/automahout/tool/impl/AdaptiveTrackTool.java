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
package org.mastodon.mamut.automahout.tool.impl;

import org.mastodon.mamut.automahout.tool.ToolRole;
import org.scijava.plugin.Plugin;
import java.util.HashMap;
import java.util.Map;

import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * Autonomous-tracking-facing wrapper around {@link ElephantTool} that exposes ONLY the integrated
 * {@code adaptive_track} pipeline.
 *
 * <p>The raw {@link ElephantTool} accepts a {@code mode} parameter and defaults to
 * {@code predict_and_link} — a single-timepoint raw prediction that skips training, the cold-start
 * bootstrap, and all quality (continuity/recall) evaluation. When that tool is driven by a weak LLM
 * that omits or mis-sets {@code mode}, the whole adaptive pipeline is silently bypassed (an
 * untrained model's false positives flow straight into {@code auto.correct}, which then deletes
 * everything). This wrapper removes that footgun by hiding {@code mode} entirely: it always runs
 * {@code adaptive_track}, so the agent cannot select a degenerate path.</p>
 *
 * <p>All other parameters are forwarded verbatim to {@link ElephantTool}.</p>
 */
@Plugin( type = AutomahoutTool.class )
public class AdaptiveTrackTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.PIPELINE;
	}


	public static final String ID = "adaptive_track";

	/** Stateless delegate that performs the actual work once {@code mode} is fixed. */
	private final ElephantTool delegate = new ElephantTool();

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Run the integrated adaptive cell-tracking pipeline over a time range. This is the "
				+ "only detection/tracking tool for autonomous tracking — it always performs the full "
				+ "adaptive pipeline (there is no 'mode' to choose).\n"
				+ "Per 10-timepoint window it: tests detection (with NMS dedup) → adapts the model "
				+ "(always trains at least once; density alone is not a correctness guarantee) → "
				+ "re-detects → links → corrects → validates. It reports track continuity (the primary "
				+ "quality signal) plus recall signals (detection retention, isolated spots).\n"
				+ "COLD START: if the model is untrained and floods the image (spots/tp above "
				+ "cold_start_spots_per_tp), it discards the inference and bootstraps from model-free "
				+ "intensity seeds, then trains; if it cannot find enough confident seeds it returns "
				+ "'MANUAL ANNOTATION REQUIRED' — stop and ask the user for a few hand-placed spots.\n"
				+ "CROP-TUNING WARM-UP: on a genuinely fresh run (no spots anywhere yet), the model is "
				+ "first adapted on a small, auto-selected region (densest cell cluster) before window 1 "
				+ "touches the full frame — much faster than full-frame cold start. Runs once, not per "
				+ "window; logged as '  Crop-tuning warm-up: ...'.\n"
				+ "Params (all optional): window_size (default 10), n_annot_per_window (2), "
				+ "n_iterations (2), min_intensity_ratio (0.25: LOW to keep dim cells; raise only if "
				+ "many background false positives), track_intensity_ratio (0 = OFF by default: deletes "
				+ "any detection dimmer than this fraction of the timepoint's median, which can discard "
				+ "real cells that are simply dimming over time rather than noise; raise only if the "
				+ "visual clearly shows background noise being detected as spots), quality_threshold "
				+ "(0.5 default, matching ELEPHANT's own prob_threshold: an ABSOLUTE cutoff — unlike the "
				+ "two ratios above — on ELEPHANT's own per-spot detection confidence; applies only to "
				+ "spots that carry a recorded quality value, automatically falling back to "
				+ "min_intensity_ratio/track_intensity_ratio for spots without one, e.g. an older server), "
				+ "min_spots_per_tp (5), "
				+ "correct_per_window (true), consistency_per_batch (true: after EACH window run a "
				+ "whole-graph topology consistency pass over everything tracked so far — dissolve rapid "
				+ "divisions, delete spurious short branches, reconnect broken tracklets — using the learned "
				+ "lineage constraints; frontier-protected so in-progress tracks are never pruned), "
				+ "dedup_factor (0.4), border_margin (0), max_aspect_ratio "
				+ "(6.0), min_size (0=OFF by default to protect small/dim real cells; a positive value "
				+ "sets a fixed floor to reject sub-cellular noise; a negative value enables AUTO "
				+ "Otsu noise/cell split. UNITS: min_size is a spot SIZE in world µm (sqrt of the "
				+ "covariance trace, ~cell size), NOT a voxel/volume count — typical cells here are "
				+ "single-digit µm, so a fixed min_size above ~the reported 'size=' metric deletes "
				+ "EVERY spot; prefer AUTO (min_size<0) over guessing a fixed value), "
				+ "long_link_factor (1.5), merge_overlap_ratio "
				+ "(0.3), n_crops (10: crops/timepoint for training — lower = faster training, "
				+ "0 = ELEPHANT default), n_epochs (0 = ELEPHANT default), refine_pass (default true: "
				+ "iterate interpolate→train→re-detect until detection count converges, to bootstrap "
				+ "recall from scratch until detection is COMPLETE), refine_iterations (max rounds, "
				+ "default 3), completeness_target (default 0.8: detected÷estimated cell count from "
				+ "image maxima — refinement continues / detection flagged INCOMPLETE below this; "
				+ "0 disables), train_timeout_sec (900), "
				+ "cold_start_spots_per_tp (300), n_seeds_per_tp (20), start_timepoint, end_timepoint.\n"
				+ "RESUME: if start_timepoint is OMITTED, tracking automatically resumes from the timepoint "
				+ "AFTER the last one that already has spots (e.g. t=0..49 tracked → starts at t=50), so "
				+ "repeated calls march forward through the movie without redoing earlier windows. If the "
				+ "whole range is already tracked, an omitted start_timepoint re-runs from t=0 to improve "
				+ "quality. Set start_timepoint explicitly only to re-process a specific earlier range.\n"
				+ "A persisted progress marker (updated after each window, survives a crash) additionally lets "
				+ "an interrupted SECOND pass continue where it stopped even though every timepoint already has "
				+ "spots from the first pass (e.g. pass 1 filled t=0..499, pass 2 stopped at t=199 → an omitted "
				+ "start_timepoint resumes at t=200). The marker is cleared once a full sweep completes.\n"
				+ "Requires the ELEPHANT server to be running.";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		// Force the adaptive pipeline regardless of what (if anything) the caller passed for mode.
		final Map< String, Object > adaptiveParams = new HashMap<>( params );
		adaptiveParams.put( ElephantTool.PARAM_MODE, "adaptive_track" );
		return delegate.execute( context, adaptiveParams );
	}
}
