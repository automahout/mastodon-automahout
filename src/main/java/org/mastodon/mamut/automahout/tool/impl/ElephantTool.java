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
import org.mastodon.mamut.automahout.tool.RunRange;
import org.scijava.plugin.Plugin;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntSupplier;

import javax.swing.SwingUtilities;

import org.elephant.setting.main.ElephantMainSettingsManager;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.elephant.ContourSupport;
import org.mastodon.mamut.automahout.elephant.DetectionTagLabels;
import org.mastodon.mamut.automahout.elephant.ElephantIntegration;
import org.mastodon.mamut.automahout.eval.LineageGraphData;
import org.mastodon.mamut.automahout.eval.BrightSpotAudit;
import org.mastodon.mamut.automahout.eval.PipelineInvariants;
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.GraphWatch;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.CleanupToggles;
import org.mastodon.mamut.automahout.tool.DetectionGate;
import org.mastodon.mamut.automahout.tool.ExpectedCellCount;
import org.mastodon.mamut.automahout.tool.GrowthPrior;
import org.mastodon.mamut.automahout.tool.GuessedCeiling;
import org.mastodon.mamut.automahout.tool.EllipseFit;
import org.mastodon.mamut.automahout.tool.EllipsoidFit3D;
import org.mastodon.mamut.automahout.tool.ImageProfile;
import org.mastodon.mamut.automahout.tool.PhaseTimings;
import org.mastodon.mamut.automahout.tool.CorrectionPass;
import org.mastodon.mamut.automahout.tool.PassPlan;
import org.mastodon.mamut.automahout.tool.PassScope;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.automahout.tool.ImageBounds;
import org.mastodon.mamut.automahout.tool.SpotShape;
import org.mastodon.mamut.automahout.eval.LineageConstraintProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.TrainingBalance;
import org.mastodon.mamut.automahout.vision.VlmBackend;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.ObjTags;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.model.tag.TagSetStructure.TagSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.viewer.ViewerPanel;

/**
 * Tool for invoking ELEPHANT deep learning prediction and linking workflows.
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_MODE}</td><td>String</td><td>{@code "predict_and_link"}</td>
 *       <td>{@code "predict"}, {@code "link"}, or {@code "predict_and_link"}</td></tr>
 *   <tr><td>{@value #PARAM_ALL_TIMEPOINTS}</td><td>boolean</td><td>{@code false}</td>
 *       <td>If {@code true}, iterate over all timepoints (predict forward, link
 *           backward). Overrides start/end defaults to cover the full range.</td></tr>
 *   <tr><td>{@value #PARAM_START_TIMEPOINT}</td><td>int</td><td>0</td>
 *       <td>First timepoint to process (only used when all_timepoints=true)</td></tr>
 *   <tr><td>{@value #PARAM_END_TIMEPOINT}</td><td>int</td><td>last timepoint</td>
 *       <td>Last timepoint to process (only used when all_timepoints=true)</td></tr>
 *   <tr><td>{@value #PARAM_LOAD_DELAY_MS}</td><td>int</td><td>200</td>
 *       <td>Milliseconds to wait after advancing viewer to a new timepoint</td></tr>
 * </table>
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li><b>predict</b> — Run ELEPHANT spot detection</li>
 *   <li><b>link</b> — Run nearest-neighbor spot linking</li>
 *   <li><b>predict_and_link</b> — Predict forward across timepoints, then link
 *       in reverse so each timepoint can reference already-detected spots at
 *       the preceding timepoint.</li>
 * </ul>
 *
 * <p>Requires ELEPHANT server to be running and configured in Mastodon.</p>
 */
@Plugin( type = AutomahoutTool.class )
public class ElephantTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.PIPELINE;
	}


	private static final Logger log = LoggerFactory.getLogger( ElephantTool.class );

	public static final String ID = "elephant";

	public static final String PARAM_MODE = "mode";

	public static final String PARAM_ALL_TIMEPOINTS = "all_timepoints";

	public static final String PARAM_START_TIMEPOINT = "start_timepoint";

	public static final String PARAM_END_TIMEPOINT = "end_timepoint";

	/**
	 * Name of an existing Mastodon tag set whose {@link #PARAM_PROTECTED_TAG_LABEL} tag marks a spot as
	 * an already human-confirmed result (e.g. a manually applied {@code Tracking > Approved} or
	 * {@code Status > Completed} tag from a prior curation pass). A protected spot is NEVER deleted,
	 * merged, refit, or duplicated by a fresh detection anywhere in {@code adaptive_track} — it is used
	 * only as trusted {@code tp} training signal, kept exactly as-is in the final result. Both this and
	 * {@link #PARAM_PROTECTED_TAG_LABEL} must be set for protection to apply; tag names are entirely
	 * user-defined in Mastodon's tagging UI, so this is explicit configuration, not a guessed convention.
	 */
	public static final String PARAM_PROTECTED_TAG_SET = "protected_tag_set";

	/** Tag label within {@link #PARAM_PROTECTED_TAG_SET} that marks a spot as protected — see its doc. */
	public static final String PARAM_PROTECTED_TAG_LABEL = "protected_tag_label";

	public static final String PARAM_LOAD_DELAY_MS = "load_delay_ms";

	/** Fraction of the per-timepoint median intensity below which spots are deleted. */
	public static final String PARAM_MIN_INTENSITY_RATIO = "min_intensity_ratio";

	/** Number of timepoints processed per logged chunk (0 = no chunking). */
	public static final String PARAM_CHUNK_SIZE = "chunk_size";

	/**
	 * When {@code true} (and {@code all_timepoints=true}): after each chunk's
	 * predict + link phase, immediately run autocorrection on that chunk before
	 * advancing to the next one.
	 */
	public static final String PARAM_CORRECT_PER_CHUNK = "correct_per_chunk";

	/** long_link_factor forwarded to autocorrection when correct_per_chunk=true. */
	public static final String PARAM_LONG_LINK_FACTOR = "long_link_factor";

	/** merge_overlap_ratio forwarded to autocorrection when correct_per_chunk=true. */
	public static final String PARAM_MERGE_OVERLAP_RATIO = "merge_overlap_ratio";

	/** Number of evenly-spaced timepoints to annotate for sparse training. */
	public static final String PARAM_N_ANNOTATION_TIMEPOINTS = "n_annotation_timepoints";

	/** When false (default), annotation spots are deleted after training. */
	public static final String PARAM_KEEP_ANNOTATIONS = "keep_annotations";

	/** Training epochs (0 = use ELEPHANT settings value). */
	public static final String PARAM_N_EPOCHS = "n_epochs";

	/** Maximum number of detect→annotate→train cycles in iterative_train mode. */
	public static final String PARAM_N_ITERATIONS = "n_iterations";

	/** Quality threshold: minimum average spots per annotation timepoint to stop iterating. */
	public static final String PARAM_MIN_SPOTS_PER_TP = "min_spots_per_tp";

	/** Number of timepoints per training window in windowed_train mode. */
	public static final String PARAM_WINDOW_SIZE = "window_size";

	/** Number of annotation timepoints per window in windowed_train mode. */
	public static final String PARAM_N_ANNOT_PER_WINDOW = "n_annot_per_window";

	/**
	 * Maximum seconds to wait for each asynchronous training job to finish before the server is
	 * aborted and the pipeline continues with whatever model exists. Guards against runaway epochs.
	 */
	public static final String PARAM_TRAIN_TIMEOUT_SEC = "train_timeout_sec";

	/**
	 * spots/tp above which detection is treated as a cold-start / untrained-model regime
	 * (the model floods the image with noise). Self-training on such output only amplifies it,
	 * so the pipeline instead bootstraps from intensity-based seed annotations. See
	 * {@code bootstrapColdStart}.
	 */
	public static final String PARAM_COLD_START_SPOTS_PER_TP = "cold_start_spots_per_tp";

	/** Number of intensity-based seed annotations to place per seeded timepoint at cold start. */
	public static final String PARAM_N_SEEDS_PER_TP = "n_seeds_per_tp";

	/**
	 * Crops per timepoint used for ELEPHANT detection training. Training iterations per epoch =
	 * n_crops x (#annotation timepoints), so this directly controls training time. Overrides the
	 * ELEPHANT Preferences value; 0 = use the Preferences value. (Training epochs are controlled by
	 * the existing {@link #PARAM_N_EPOCHS}.)
	 */
	public static final String PARAM_N_CROPS = "n_crops";

	/**
	 * When true (default), each window runs one extra "provisional-annotation" refinement round after
	 * linking+correction: it enriches the spots (recovers missed cells via gap-fill), retrains on
	 * that enriched set, and re-detects — so the model learns previously-missed cells (improves recall).
	 */
	public static final String PARAM_REFINE_PASS = "refine_pass";

	/** Maximum interpolate→train→re-detect refinement rounds per window (stops early on convergence). */
	public static final String PARAM_REFINE_ITERATIONS = "refine_iterations";

	/**
	 * Detection-completeness (recall) target in [0,1]: detected spots/tp ÷ estimated cell count from
	 * image intensity maxima. Refinement continues and the pipeline flags detection as INCOMPLETE
	 * while below this. 0 disables the completeness gate.
	 */
	public static final String PARAM_COMPLETENESS_TARGET = "completeness_target";

	/**
	 * {@link ToolContext#getSharedData} key for a hard, run-scoped CEILING on {@link #PARAM_REFINE_ITERATIONS}
	 * (an {@code Integer}), set by {@link org.mastodon.mamut.automahout.plugin.AutonomousTrackingAction}'s
	 * pre-flight review dialog. Unlike {@code AutomahoutSettings} fields (persisted to Mastodon
	 * Preferences and therefore leaking across projects/datasets — see the {@code refitAnisotropic}
	 * incident this exact mechanism was designed to avoid repeating), shared data lives only on this run's
	 * {@link ToolContext}, so a cap chosen for one troublesome dataset can never silently affect another.
	 * The LLM agent has no visibility into this cap and may request a higher value; it is clamped down
	 * here regardless, mirroring how {@link #runTimepointBounds} hard-enforces the timepoint range
	 * the agent cannot see. Absent (no key set) means "no cap — trust the agent's choice."
	 */
	public static final String SHARED_MAX_REFINE_ITERATIONS = "adaptive_track.max_refine_iterations";

	/**
	 * As {@link #SHARED_MAX_REFINE_ITERATIONS}, but a hard CEILING (a {@code Double}) on
	 * {@link #PARAM_COMPLETENESS_TARGET} — caps how hard the refinement loop is allowed to chase recall,
	 * which is what turns into wasted (and, before the self-training-collapse guard existed, dangerous)
	 * rounds on a dataset whose true achievable completeness is far below a generic default target.
	 */
	public static final String SHARED_COMPLETENESS_TARGET_CAP = "adaptive_track.completeness_target_cap";

	/**
	 * As {@link #SHARED_MAX_REFINE_ITERATIONS}, but a hard, EXACT override (an {@code Integer}) for
	 * {@link #PARAM_N_CROPS}. Unlike {@code refine_iterations}/{@code completeness_target}, {@code n_crops}
	 * already has a "defer to the user's own ELEPHANT Main Settings" fallback in
	 * {@code ElephantIntegration.buildTrainRequest} ({@code nCrops > 0 ? nCrops : mainSettings.getNumCrops()})
	 * — but that fallback only ever triggers when the caller passes {@code nCrops <= 0}, and both
	 * {@link #DEFAULT_N_CROPS} and the LLM's own tool calls (observed to always specify {@code n_crops}
	 * explicitly) are positive, so the user's ELEPHANT-configured value was silently never actually used.
	 * This override forces the pre-flight-confirmed value regardless of what the agent requests, the same
	 * way the timepoint range/refine caps are enforced.
	 */
	public static final String SHARED_N_CROPS_OVERRIDE = "adaptive_track.n_crops_override";

	/**
	 * {@link ToolContext#getSharedData} key for a {@link ProtectedSpots.Config} set by the pre-flight
	 * guidance dialog ({@code AutonomousTrackingAction}), enforced regardless of what the agent's own
	 * {@code adaptive_track} tool call requests — the same pattern as {@link #SHARED_N_CROPS_OVERRIDE}.
	 * When present, this takes priority over {@link #PARAM_PROTECTED_TAG_SET}/
	 * {@link #PARAM_PROTECTED_TAG_LABEL} in {@code params} (see {@code execute}'s adaptive_track setup);
	 * when absent, the agent's own per-call params are used instead (a standalone {@code adaptive_track}
	 * call outside the autonomous agent, e.g. in a test, has no pre-flight dialog to set this).
	 */
	public static final String SHARED_PROTECTED_TAG_CONFIG = "adaptive_track.protected_tag_config";

	/**
	 * As {@link #SHARED_MAX_REFINE_ITERATIONS}, but a hard, EXACT override (a {@code Double}) for
	 * {@link #PARAM_COLD_START_SPOTS_PER_TP}, set from the pre-flight dialog's user-entered "expected
	 * cells per frame" — NOT the VLM's own visual guess at cell count (from {@code video.analyze}),
	 * which this exists specifically to correct: the VLM's estimate from a downsampled/pyramid
	 * screenshot can be off by a large factor (observed: guessed ~23, the agent's own detections
	 * settled around ~21/tp against the user's actual, much higher true count), and every cold-start/
	 * flood/plausible-density decision in {@code runAdaptiveTrack} is calibrated against
	 * {@code coldStartCeiling} — so an inaccurate VLM guess silently propagating into that ceiling
	 * miscalibrates the whole run's "is this dataset's cell count plausible" judgement. When the user
	 * leaves the dialog field blank, this key is not set and the agent's own request (or
	 * {@link #DEFAULT_COLD_START_CEILING}) is used as before.
	 */
	public static final String SHARED_COLD_START_CEILING_OVERRIDE = "adaptive_track.cold_start_ceiling_override";

	/**
	 * {@link ToolContext#getSharedData} key for the pre-flight dialog's "expected cells per frame" as the
	 * user typed it — NOT the doubled ceiling stored under {@link #SHARED_COLD_START_CEILING_OVERRIDE}.
	 *
	 * <p>Kept separate because the two are different KINDS of number and mixing them up has already cost
	 * a round: a ceiling is an upper bound to flag explosions against, while this is an expectation to
	 * protect against over-deletion. Only the raw count can serve as a floor — recovering it by halving
	 * the ceiling would assume the 2x convention, which holds only when the dialog set it.</p>
	 */
	public static final String SHARED_EXPECTED_CELLS_PER_FRAME =
			ExpectedCellCount.SHARED_EXPECTED_CELLS_PER_FRAME;

	/**
	 * {@link ToolContext#getSharedData} key for the mean spots per timepoint the last
	 * {@link #predictWindow} call detected, counted BEFORE any cleanup pass ran on them.
	 *
	 * <p>The tuning loop's divergence guard compares rounds against this rather than against the
	 * post-cleanup {@code computeQualityMetrics} count, because the cleanup is not applied uniformly:
	 * {@link #deleteFlatContrastSpots} declines to run on a timepoint whose raw count is already under
	 * the detection ceiling, so how much of a round survives depends on which side of that ceiling each
	 * timepoint happened to land. Measured on one window: the round's model detected 384 raw spots/tp
	 * against the previous round's 419 — it had improved — but most of its timepoints fell under the
	 * ceiling and went uncleaned, so the post-cleanup figure read 440 against 262 and the guard
	 * discarded the better model as diverged. Raw counts are the same measurement every round.</p>
	 */
	/**
	 * {@link ToolContext#getSharedData} key for the fraction of this window's tp annotations that the
	 * synthetic fp negatives actually came to, against {@link #SYNTHETIC_FP_TARGET_FRACTION}.
	 *
	 * <p>Read where a training round is DISCARDED for over-detecting. Both numbers were already in the
	 * log and neither pointed at the other: the round that flooded Fluo-N2DL-HeLA_01 to 6.5x was
	 * trained on 830 positives and 17 negatives — 2.0% against a stated target of 10% — and the discard
	 * message blamed "self-training on its own detections" in general terms, naming nothing the reader
	 * could act on.</p>
	 */
	public static final String SHARED_LAST_FP_NEGATIVE_FRACTION =
			"adaptive_track.last_fp_negative_fraction";

	public static final String SHARED_LAST_PREDICT_RAW_SPOTS_PER_TP =
			"adaptive_track.last_predict_raw_spots_per_tp";

	/**
	 * {@link ToolContext#getSharedData} key for the expected cells/frame the current window's explosion
	 * guards compare against — {@link Number}, absent meaning "no window-scoped figure, use the run-wide
	 * {@code coldStartCeiling}".
	 *
	 * <p>Exists because {@code coldStartCeiling} is stated ONCE for a movie whose population does not
	 * stay still. Observed on Fluo-N2DL-HeLA_01, a proliferating culture running 44 cells/frame at t=0
	 * to 150 at t=91 against a ceiling of 60: every window past the first was declared exploded for
	 * exceeding a bound calibrated on the first, and from t=50 on the per-timepoint breaker abandoned
	 * every remaining window. Nothing had diverged — the counts climbed 1 to 3 spots per frame at a
	 * temporal CV of 0.07, and continuity held at 90%. Cells had divided, which is what cells do.</p>
	 *
	 * <p>Written by {@code runAdaptiveTrack} once per window and read by {@link #predictWindow}, rather
	 * than threaded as a parameter, so that it cannot be confused with the {@code expectedCeiling}
	 * argument already on that method: that one gates {@link #deleteFlatContrastSpots} and must keep
	 * meaning the run-wide bound.</p>
	 */
	/**
	 * {@link ToolContext#getSharedData} key for the explosion ceiling MEASURED on recent windows, absent
	 * until one has been. See {@code prevWindowCalibrated}.
	 */
	public static final String SHARED_WINDOW_CALIBRATED_CEILING =
			"adaptive_track.window_calibrated_ceiling";

	public static final String SHARED_WINDOW_EXPLOSION_CEILING =
			"adaptive_track.window_explosion_ceiling";

	/**
	 * {@link ToolContext#getSharedData} key set at the start of each window to ask the next
	 * flat-contrast check to audit its own verdict against the VLM (see
	 * {@link AutoCorrectOrchestrator#auditBackgroundVerdictViaVlm}), counted down as it does.
	 *
	 * <p>{@link #FLAT_CONTRAST_AUDITS_PER_WINDOW} calls per window rather than one, because one call
	 * compares 16 spots against 16 and the first real measurement came back 10/16 against 6/16 — the
	 * right direction and nowhere near enough to act on. Pooled over a whole run it is a few hundred
	 * per group, which can settle the question.</p>
	 */
	public static final String SHARED_FLAT_CONTRAST_AUDIT_PENDING =
			"adaptive_track.flat_contrast_audit_pending";

	/** {@link ToolContext#getSharedData} key for the run-wide audit tally, an {@code int[4]}. */
	public static final String SHARED_FLAT_CONTRAST_AUDIT_TALLY =
			"adaptive_track.flat_contrast_audit_tally";

	/**
	 * {@link ToolContext#getSharedData} key for the {@link VlmBackend} instance to use for per-window
	 * near-cell negative synthesis (see {@link #DEFAULT_SYNTHETIC_FP_PER_WINDOW}), set once by
	 * {@link AutonomousTrackingTool#execute} so the whole run reuses the same client instead of rebuilding
	 * one per window. Absent (no key set, e.g. a standalone {@code adaptive_track} call outside the
	 * autonomous agent, or no VLM backend configured) means no explicit fp negatives are synthesized this
	 * run — training then relies on contrast/noise-floor-based cleanup alone (see
	 * {@code deleteFlatContrastSpots}/{@code estimateContrastNoiseFloor}) instead of any explicit negative
	 * example.
	 */
	public static final String SHARED_VLM_BACKEND = "adaptive_track.vlm_backend";

	/** As {@link #SHARED_VLM_BACKEND}, the model name/tag to pass to {@link VlmBackend#analyze}. */
	public static final String SHARED_VLM_MODEL = "adaptive_track.vlm_model";

	/**
	 * Per-window count of synthetic "near-cell" fp negatives generated from the window's own tp spots —
	 * see {@link AutoCorrectOrchestrator#synthesizeNearCellFpNegatives}. This is now the SOLE source of
	 * explicit fp training negatives (raised from 10 when it was only a supplement to
	 * re-classifying the window's own ambiguous detections — see that removal in {@code runAdaptiveTrack}),
	 * so it needs to cover more ground on its own: training on tp alone leaves the model with no negative
	 * signal for the space immediately around a real cell, and it drifts into detecting fragments there.
	 */
	public static final int DEFAULT_SYNTHETIC_FP_PER_WINDOW = 20;

	/**
	 * Minimum fraction of a window's tp count to target for synthetic fp negatives (see
	 * {@link #syntheticFpBudget}) — a fixed count regardless of window size (the historical
	 * {@link #DEFAULT_SYNTHETIC_FP_PER_WINDOW}) stayed under 1% of tp on a dense dataset (e.g. ~2500 tp
	 * annotations/window, ~20 fp requested and often fewer actually confirmed) and correlated with
	 * ELEPHANT's own per-frame training stats showing {@code fp=0} on most frames — an extreme class
	 * imbalance that gives the model no negative signal to push back against over-detection, which
	 * self-training then amplifies window over window into a runaway explosion.
	 */
	private static final double SYNTHETIC_FP_TARGET_FRACTION = 0.10;

	/**
	 * Upper bound on synthetic negatives requested per window, regardless of {@link
	 * #SYNTHETIC_FP_TARGET_FRACTION}.
	 *
	 * <p>This used to be 30, chosen so a whole window's candidates fitted into ONE VLM call: every
	 * candidate needs three rendered planes (xy/xz/yz) and the backing API rejects a call outright above
	 * ~100 images ("Too much media: 0 document pages + N images > 100"), which caps a single call at 33
	 * candidates. That transport limit was holding the entire mechanism down — a window with ~4000 tp
	 * annotations asked for 30 negatives and confirmed fewer, well under 1% of tp, and ELEPHANT's own
	 * per-frame stats reported {@code fp=0} accordingly. {@code confirmSyntheticFpCandidatesViaVlm} now
	 * splits the candidates across as many calls as needed, so the limit no longer belongs here.</p>
	 *
	 * <p>What bounds this now is wall-clock and API cost: at 33 candidates per call this is ~12 calls
	 * per round, ~24 per window at {@code SYNTHETIC_FP_MAX_ROUNDS}=2, a few seconds each — noticeable
	 * against a ~200 s training call but not dominant. Raise it if a dataset is dense enough that 10% of
	 * its tp count is routinely clipped here; the log says when it is.</p>
	 */
	private static final int MAX_SYNTHETIC_FP_PER_WINDOW = 400;

	/**
	 * Scales the synthetic-fp-negative request to the window's own tp count (see {@link
	 * #SYNTHETIC_FP_TARGET_FRACTION}), floored at {@link #DEFAULT_SYNTHETIC_FP_PER_WINDOW} and capped at
	 * {@link #MAX_SYNTHETIC_FP_PER_WINDOW}.
	 */
	static int syntheticFpBudget( final int tpCountInWindow )
	{
		return Math.min( MAX_SYNTHETIC_FP_PER_WINDOW, Math.max( DEFAULT_SYNTHETIC_FP_PER_WINDOW,
				( int ) Math.ceil( tpCountInWindow * SYNTHETIC_FP_TARGET_FRACTION ) ) );
	}

	/**
	 * Asks for this window's synthetic fp negatives and reports the result AGAINST THE TP COUNT it is
	 * meant to be a fraction of.
	 *
	 * <p>The old log line said only "Synthesized 9 near-cell background negative(s)". With no
	 * denominator, 9 against ~4000 tp annotations reads the same as 9 against 90, so the mechanism
	 * sitting two orders of magnitude below its target was invisible in every run it appeared in.</p>
	 */
	private static void requestSyntheticFpNegatives( final ToolContext context, final int wStart,
			final int wEnd, final VlmBackend vlmBackend, final String vlmModel, final int tpCountInWindow )
	{
		final int budget = syntheticFpBudget( tpCountInWindow );
		final int untruncatedTarget = ( int ) Math.ceil( tpCountInWindow * SYNTHETIC_FP_TARGET_FRACTION );
		final Integer synthFp = AutoCorrectOrchestrator.synthesizeNearCellFpNegatives(
				context, wStart, wEnd, vlmBackend, vlmModel, budget );
		context.setSharedData( SHARED_LAST_FP_NEGATIVE_FRACTION,
				tpCountInWindow > 0 && synthFp != null
						? ( double ) synthFp / tpCountInWindow
						: null );
		if ( synthFp != null && synthFp > 0 )
			context.log( String.format(
					"  Synthesized %d near-cell background negative(s) via VLM = %.1f%% of this window's "
							+ "%d tp annotation(s) (asked for %d; target %.0f%%%s).",
					synthFp, 100.0 * synthFp / Math.max( 1, tpCountInWindow ), tpCountInWindow, budget,
					SYNTHETIC_FP_TARGET_FRACTION * 100,
					budget < untruncatedTarget
							? String.format( " would be %d, clipped by the per-window cap of %d",
									untruncatedTarget, MAX_SYNTHETIC_FP_PER_WINDOW )
							: "" ) );
		// synthFp == null or 0: synthesizeNearCellFpNegatives already logged the specific reason (no
		// sources, all candidates collided, VLM call failed, every candidate judged a real cell).
	}

	/**
	 * Minimum spot size (sqrt of covariance trace, world units) below which a detection is discarded
	 * as sub-cellular background noise. Unlike min_intensity_ratio (which cannot separate dim cells
	 * from bright noise), this rejects specks that are simply too small to be a cell.
	 * <b>0 = OFF (default)</b>; &gt;0 = fixed floor; &lt;0 = AUTO (Otsu noise/cell split, opt-in).
	 */
	public static final String PARAM_MIN_SIZE = "min_size";

	/** Intensity filter ratio applied during tracking (looser than training filter). */
	public static final String PARAM_TRACK_INTENSITY_RATIO = "track_intensity_ratio";

	/**
	 * ELEPHANT's own per-spot quality (0..1, mean detection-confidence probability) below which a
	 * detection is rejected. Absolute cutoff (unlike min_intensity_ratio/track_intensity_ratio, which are
	 * relative to a per-timepoint median) — principled because it reuses the server's own prob_threshold
	 * semantics (default 0.5). Falls back to the intensity heuristics automatically for any spot lacking
	 * a recorded quality value (older server, or a spot added via a non-prediction path).
	 */
	public static final String PARAM_QUALITY_THRESHOLD = "quality_threshold";

	/** When true, run autocorrect after linking in each window of adaptive_track. */
	public static final String PARAM_CORRECT_PER_WINDOW = "correct_per_window";

	public static final String PARAM_CONSISTENCY_PER_BATCH = "consistency_per_batch";

	/**
	 * NMS overlap factor for duplicate spot removal in adaptive_track.
	 * Two spots are considered duplicates if their centre-to-centre distance is
	 * less than {@code dedup_factor * (r_i + r_j)}.  Default 0.4 (conservative:
	 * only removes spots whose centres are within 40% of their combined radii).
	 * Set to 0 to disable.
	 */
	public static final String PARAM_DEDUP_FACTOR = "dedup_factor";

	/**
	 * Multiple of the LARGER of two spot radii within which they count as fragments of one
	 * over-segmented cell. Default {@code 0.7}; raise toward 1.0 to merge more aggressively.
	 *
	 * <p>Exposed so this can be A/B'd on a real dataset: it decides how much of a frame the
	 * over-segmentation pass removes, which on the measured run was ~40% of every timepoint. See
	 * {@link AutoCorrectOrchestrator#DUPLICATE_OVERLAP_FACTOR_DEFAULT} for why the old criterion
	 * (spheres merely touching) was deleting adjacent CELLS rather than fragments.</p>
	 */
	public static final String PARAM_DUPLICATE_OVERLAP_FACTOR = "duplicate_overlap_factor";

	/**
	 * Diagnostic switch for the flat-contrast background test (default true = delete as before).
	 *
	 * <p>Not described in the tool description the agent reads, on purpose — see
	 * {@link CleanupToggles} for why, and for the pre-flight dialog checkbox that overrides it.</p>
	 */
	public static final String PARAM_FLAT_CONTRAST_FILTER = "flat_contrast_filter";

	/**
	 * Diagnostic switch for over-segmentation duplicate deletion (default true = delete as before).
	 * Distinct from {@link #PARAM_DEDUP_FACTOR}, which gates the separate NMS pass. Unadvertised for
	 * the same reason as {@link #PARAM_FLAT_CONTRAST_FILTER} — see {@link CleanupToggles}.
	 */
	/**
	 * Whether post-processing may MERGE two detections into one covering ellipsoid. Default {@code
	 * false} — see {@code CleanupToggles.SHARED_RESOLVE_DUPLICATES_OVERRIDE}.
	 */
	public static final String PARAM_RESOLVE_DUPLICATES = "resolve_duplicates";

	/**
	 * Whether the automatic split pass may replace one detection with several. Default {@code false} —
	 * see {@code CleanupToggles.SHARED_SPLIT_MERGED_OVERRIDE}. Unadvertised, as the other two.
	 */
	public static final String PARAM_SPLIT_MERGED = "split_merged";

	/**
	 * Distance in world units from any image face within which detected spots
	 * are removed as border artifacts.  Default 20.0.  Set to 0 to disable.
	 */
	public static final String PARAM_BORDER_MARGIN = "border_margin";

	/**
	 * Maximum allowed aspect ratio (longest radius / shortest radius) for a spot.
	 * Spots with a higher ratio are treated as elongated non-cell artifacts and
	 * removed.  Default 6.0.  Set to 0 to disable.
	 */
	public static final String PARAM_MAX_ASPECT_RATIO = "max_aspect_ratio";

	private static final int DEFAULT_LOAD_DELAY_MS = 200;

	private static final int DEFAULT_CHUNK_SIZE = 10;

	private static final int DEFAULT_N_ANNOTATION_TIMEPOINTS = 5;

	private static final int DEFAULT_N_ITERATIONS = 3;

	private static final int DEFAULT_MIN_SPOTS_PER_TP = 5;

	private static final int DEFAULT_WINDOW_SIZE = 10;

	private static final int DEFAULT_N_ANNOT_PER_WINDOW = 2;

	/** Default per-training-job timeout (15 minutes) before the server is aborted. */
	private static final long DEFAULT_TRAIN_TIMEOUT_MS = 15L * 60L * 1000L;

	/** Default spots/tp ceiling above which the cold-start bootstrap is triggered. */
	private static final double DEFAULT_COLD_START_CEILING = 300.0;

	/** Default number of intensity seeds placed per seeded timepoint. */
	private static final int DEFAULT_N_SEEDS_PER_TP = 20;

	/** Default max refinement rounds; the loop stops early once detection count stops growing. */
	public static final int DEFAULT_REFINE_ITERATIONS = 3;

	/** Minimum relative growth in spots/tp to consider a refinement round "still improving". */
	private static final double REFINE_MIN_GROWTH = 0.02;

	/**
	 * Over-segmentation merge test (see {@code deduplicateSpotsAtTimepoint}): two overlapping spots are
	 * merged as fragments of one cell when the image intensity at their midpoint is at least this
	 * fraction of the dimmer spot's core intensity (no dark valley between them). Lower = more
	 * aggressive merging.
	 */
	private static final double NMS_VALLEY_KEEP_RATIO = 0.75;

	/**
	 * Default crops/timepoint for adaptive training — much smaller than ELEPHANT's default (100) so
	 * each per-window training finishes quickly (fewer iterations/epoch). ELEPHANT training is
	 * generally fast, so a small value suffices; tune up for harder data.
	 */
	private static final int DEFAULT_N_CROPS = 10;

	/** Intensity percentile a voxel must exceed to be a seed candidate (only the brightest cells). */
	private static final double SEED_INTENSITY_PERCENTILE = 0.99;

	/**
	 * Minimum total auto-generated seeds required to bootstrap unattended. Below this the pipeline
	 * cannot trust the seeds and falls back to requesting manual annotation.
	 */
	private static final int MIN_SEEDS_FOR_AUTO_BOOTSTRAP = 8;

	/** Voxel stride for the seed intensity scan (coarse — seeds only need approximate centres). */
	private static final int SEED_SCAN_STRIDE = 2;

	/**
	 * Voxel stride for {@link #estimateExpectedCellCount}'s whole-volume maxima scan — coarser than
	 * {@link #SEED_SCAN_STRIDE} because this is an aggregate statistical estimate (not exact seed
	 * placement), and {@code scanImageSpaceMaxima}'s greedy NMS is O(candidates²): halving the stride in
	 * each of 3 axes multiplies the raw sample count (and therefore the NMS cost) by ~8x, so this matters
	 * a lot for a large volume.
	 */
	private static final int COMPLETENESS_SCAN_STRIDE = 4;

	/**
	 * Number of evenly-spaced timepoints {@link #computeDetectionCompleteness} samples from a window,
	 * instead of scanning every one — each timepoint costs one whole-volume maxima scan, so this bounds
	 * the total cost regardless of window_size. Completeness is already an ADVISORY estimate, not an
	 * exact metric, so trading a few sample points for a large constant-factor speedup is a good trade.
	 */
	private static final int COMPLETENESS_SAMPLE_TIMEPOINTS = 3;

	/** World-unit radius assigned to bootstrap seed spots (approximate cell radius). */
	private static final double DEFAULT_SEED_RADIUS_WORLD = 5.0;

	/**
	 * Default min_size. 0 = OFF (no size filtering — the default, to avoid clipping small/dim real
	 * cells); &gt;0 = fixed floor; &lt;0 = AUTO (opt-in: derive a noise/cell size split from the data).
	 */
	private static final double DEFAULT_MIN_SIZE = 0.0;

	/**
	 * In AUTO min_size mode, the lower size mode is treated as background noise (and removed) only if
	 * its mean is below this fraction of the upper (cell) mode's mean. Daughter cells right after
	 * division are ~0.79x the mother's linear size, so a 0.5 cutoff keeps them while still catching
	 * noise specks (~0.3-0.4x). If the two modes are closer than this, NOTHING is removed — protecting
	 * legitimately small cells over aggressive noise removal.
	 */
	private static final double NOISE_CELL_SIZE_RATIO = 0.5;

	/** Minimum spot count needed before AUTO min_size attempts to separate noise from cells. */
	private static final int MIN_SPOTS_FOR_SIZE_OTSU = 20;

	/**
	 * OFF by default: {@link AutoCorrectOrchestrator#filterWeakSpots} deletes any detection dimmer than
	 * this fraction of the current timepoint's median intensity, on top of whatever ELEPHANT's own
	 * detection confidence already filtered out. A cell that dims over time (e.g. photobleaching) can
	 * fall below a NON-zero ratio relative to its (also dimming) peers even though the detector still
	 * found it — deleting real cells, not noise. Only raise this if the visual clearly shows background
	 * noise being detected as spots.
	 */
	private static final double DEFAULT_TRACK_INTENSITY_RATIO = 0.0;

	/** Default absolute quality cutoff — matches ELEPHANT's own prob_threshold request default. */
	private static final double DEFAULT_QUALITY_THRESHOLD = 0.5;

	/** CV of per-timepoint spot counts below which temporal consistency is considered good. */
	private static final double CV_TEMPORAL_GOOD = 0.5;

	/** CV of spot sizes (covariance trace) below which size uniformity is considered good. */
	private static final double CV_SIZE_GOOD = 0.4;

	/**
	 * Fraction of interior spots that must have both an incoming and an outgoing link for
	 * a window's tracking to be considered continuous (i.e. not fragmented). Unlike density
	 * metrics, this is a proxy for tracking correctness — see {@code computeContinuity}.
	 */
	private static final double CONTINUITY_GOOD = 0.7;

	/**
	 * When no GT store is configured, only pre-existing spots on an <em>unbranched</em> track that
	 * persists at least this many timepoints are trusted as training GT. An unbranched, long-lived
	 * track is very likely a real cell; branchy or short tracks are typically broken or force-connected
	 * false detections, so they are excluded. Topology alone is not sufficient, though — see the
	 * plausible-density check alongside this heuristic's use, which compares against {@code coldStartCeiling}
	 * (the same dataset-specific density ceiling used for cold-start/flood detection, NOT a hardcoded
	 * constant — a confluent culture imaged over a large field of view can genuinely have thousands of
	 * real cells per timepoint, so any fixed absolute number here would misfire on such datasets).
	 */
	private static final int RELIABLE_TRACK_MIN_LEN = 10;

	/**
	 * A trusted-GT spot whose size ({@code sqrt(trace)} in world µm) is below this is treated as
	 * point-like and given an image-derived spatial extent before training (see
	 * {@link AutoCorrectOrchestrator#inflatePointLabels}). Real cells here measure ~5–9 µm, so a
	 * near-point annotation falls well below this threshold while a genuine ellipsoid is left untouched.
	 */
	private static final double GT_POINT_SIZE_MAX_WORLD = 4.0;

	/** Lower clamp for an image-derived GT semi-axis (world µm) — a minimal plausible cell radius. */
	private static final double GT_EXTENT_MIN_RADIUS_WORLD = 3.0;

	/** Upper clamp for an image-derived GT semi-axis (world µm) — keeps one blob from ballooning. */
	private static final double GT_EXTENT_MAX_RADIUS_WORLD = 8.0;

	/**
	 * Detection completeness (recall) target: detected spots/tp divided by the model-free estimate of
	 * cell-scale bright blobs (intensity maxima). Below this, detection is treated as INCOMPLETE — the
	 * refinement loop keeps going and the agent must not declare done. Tunable via completeness_target.
	 */
	public static final double DEFAULT_COMPLETENESS_TARGET = 0.8;

	/**
	 * Intensity percentile used when estimating the "expected" cell count from image maxima. Higher =
	 * only brighter blobs count (fewer false cells from noise); lower = includes dimmer candidates.
	 */
	private static final double COMPLETENESS_PERCENTILE = 0.97;

	/** Default dedup_factor for NMS duplicate removal after prediction. */
	private static final double DEFAULT_DEDUP_FACTOR = 0.4;

	/** Default border_margin in world units. */
	private static final double DEFAULT_BORDER_MARGIN = 20.0;

	/** Default max_aspect_ratio for elongated-artifact removal. */
	private static final double DEFAULT_MAX_ASPECT_RATIO = 6.0;


	/**
	 * Radius (world units) a random background sample must stay outside of every existing detection to
	 * count as an automatic "empty background" fp example for {@code learnAcceptanceProfileAuto} — a
	 * multiple of the mean detected spot size so a sample cannot land on a real (even mis-fit) cell.
	 */
	private static final double AUTO_FP_EXCLUSION_RADIUS_FACTOR = 4.0;

	/** Random background fp samples drawn per timepoint when auto-learning the acceptance profile. */
	private static final int AUTO_FP_SAMPLES_PER_TP = 20;

	/**
	 * Fraction of the smaller image dimension used as the neighbourhood radius when picking the
	 * densest intensity-maxima cluster for the crop-tuning warm-up (see
	 * {@link #pickTuningRoiCenter}) — a proxy for "roughly one training crop's footprint" without
	 * needing extra voxel-size plumbing.
	 */
	private static final double TUNING_ROI_CLUSTER_RADIUS_FRACTION = 0.05;

	/**
	 * Max distinct local regions rotated through across a window's training iterations (see
	 * {@link #pickTuningRoiCenters}). A single fixed tuning crop only ever teaches the model that ONE
	 * region's background/density/illumination — on a spatially heterogeneous frame, a model tuned on
	 * just that region can transfer poorly the moment the mandatory commit switches to full-frame
	 * prediction (large post-commit error). Rotating iterations across several spatially distinct
	 * regions exposes the model to more of the frame's real variability before that jump, at no extra
	 * predict cost per iteration — still exactly one crop-restricted predict per iteration, just a
	 * DIFFERENT crop each time. Only helps when {@code nTrainIterations > 1}; with a single iteration
	 * there is nothing to rotate through.
	 */
	private static final int TUNING_ROI_BATCH_COUNT = 3;

	/** Timepoints used for the crop-tuning warm-up pre-pass (see {@link #warmUpModelOnCrop}). */
	private static final int TUNING_WARMUP_TIMEPOINTS = 2;

	/**
	 * How far past the run's first timepoint the warm-up may take its seed frames from.
	 *
	 * <p>The warm-up exists to make the model usable for the FIRST window, and its seed timepoints used
	 * to be spread across the entire requested range. On a movie where the specimen develops, that means
	 * training a single model on two different stages at once: on a 500-frame mouse embryo it took t=2
	 * and t=499, and the model came out detecting 72 spots at t=499 and 4 at t=2. Two frames a few tens
	 * apart still give it more than one view of the data without asking it to be two detectors.</p>
	 */
	private static final int TUNING_WARMUP_SPAN_TIMEPOINTS = 20;

	/** {@code "170 and 190"} / {@code "170, 180 and 190"} — for a log line that names the frames used. */
	private static String joinTimepoints( final int[] timepoints )
	{
		final StringBuilder sb = new StringBuilder();
		for ( int i = 0; i < timepoints.length; i++ )
		{
			if ( i > 0 )
				sb.append( i == timepoints.length - 1 ? " and " : ", " );
			sb.append( timepoints[ i ] );
		}
		return sb.toString();
	}

	/** Max bootstrap/retrain rounds in the crop-tuning warm-up — bounded so it stays fast. */
	private static final int TUNING_WARMUP_MAX_ITERATIONS = 2;

	/**
	 * Grid size (N x N) of small crop-restricted probes {@link #estimateGridDensity} samples at ONE
	 * timepoint to cheaply estimate a window's whole-frame density before paying for a real full-frame
	 * predict. Multiple samples (not a single crop) guard against a spatially non-uniform cell density
	 * skewing the estimate from one lucky/unlucky patch — the same reasoning as
	 * {@code AnalyzeVideoTool}'s pre-flight assessment pyramid.
	 */
	private static final int DENSITY_PROBE_GRID_SIZE = 2;

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Run ELEPHANT deep learning workflows.\n"
				+ "Modes:\n"
				+ "  'windowed_train' — Fine-tune the ELEPHANT detection model in local time windows. "
				+ "Sweeps through all timepoints in windows of window_size, running iterative training "
				+ "within each window to adapt the model to local imaging properties. "
				+ "Use this as Phase 1 before full tracking on any new dataset. "
				+ "Params: window_size (int, default " + DEFAULT_WINDOW_SIZE + "), "
				+ "n_annot_per_window (int, default " + DEFAULT_N_ANNOT_PER_WINDOW + "), "
				+ "n_iterations (int, default " + DEFAULT_N_ITERATIONS + "), "
				+ "min_spots_per_tp (double, default " + DEFAULT_MIN_SPOTS_PER_TP + "), "
				+ "min_intensity_ratio (double, default 0.4), "
				+ "quality_threshold (default " + DEFAULT_QUALITY_THRESHOLD + ": absolute ELEPHANT-confidence "
				+ "cutoff, falls back to min_intensity_ratio for spots without a recorded quality), "
				+ "start_timepoint, end_timepoint.\n"
				+ "  Result includes per-window quality metrics: spots/tp mean±CV (temporal consistency) "
				+ "and size mean±CV (size uniformity). "
				+ "Good quality: CV_temporal<0.5, CV_size<0.4, spots/tp>=" + DEFAULT_MIN_SPOTS_PER_TP + ". "
				+ "Poor quality: spots/tp<3, OR CV_temporal>0.7, OR CV_size>0.6.\n"
				+ "  'iterative_train' — Global iterative training on evenly-spaced timepoints. "
				+ "Params: n_annotation_timepoints (int, default " + DEFAULT_N_ANNOTATION_TIMEPOINTS + "), "
				+ "n_iterations, min_spots_per_tp, min_intensity_ratio, quality_threshold, "
				+ "start_timepoint, end_timepoint.\n"
				+ "  'predict' — detect spots at current timepoint.\n"
				+ "  'link' — run NN linking at current timepoint.\n"
				+ "  'predict_and_link' — detect forward then link backward. "
				+ "With all_timepoints=true processes the full dataset. "
				+ "Params: all_timepoints (bool), start_timepoint, end_timepoint, "
				+ "load_delay_ms (default " + DEFAULT_LOAD_DELAY_MS + "), "
				+ "chunk_size (default " + DEFAULT_CHUNK_SIZE + "), "
				+ "min_intensity_ratio (double, default 0.0), "
				+ "quality_threshold (default " + DEFAULT_QUALITY_THRESHOLD + "), "
				+ "correct_per_chunk (bool, default false: run autocorrect after each chunk — RECOMMENDED), "
				+ "long_link_factor, merge_overlap_ratio.\n"
				+ "  'adaptive_track' — Per-window integrated pipeline: test detection → adapt model "
				+ "(always trains at least once) → re-detect → link → evaluate and correct → validate. "
				+ "Replaces running windowed_train and predict_and_link separately. "
				+ "Reports continuity (primary quality signal) plus recall signals (detection "
				+ "retention, isolated spots). "
				+ "COLD START: if the model is untrained and floods the image (spots/tp above "
				+ "cold_start_spots_per_tp), it discards the inference and bootstraps from model-free "
				+ "intensity seeds, then trains; if it cannot find enough confident seeds it returns a "
				+ "'MANUAL ANNOTATION REQUIRED' message asking for a few hand-placed spots. "
				+ "CROP-TUNING WARM-UP: on a genuinely fresh run (no spots anywhere in the requested range "
				+ "yet), before window 1's own pipeline starts, the model is first adapted on a small, "
				+ "auto-selected region (the densest intensity-maxima cluster) instead of the full frame — "
				+ "every predict/train call in this warm-up is crop-restricted, so it is much faster than "
				+ "full-frame cold start and avoids training on tens of thousands of noise-dominated "
				+ "detections. Logged as '  Crop-tuning warm-up: ...'; runs once, not per window. "
				+ "Params: window_size (default " + DEFAULT_WINDOW_SIZE + "), "
				+ "n_annot_per_window (default " + DEFAULT_N_ANNOT_PER_WINDOW + "), "
				+ "n_iterations (default " + DEFAULT_N_ITERATIONS + "), "
				+ "min_intensity_ratio (default 0.25: LOW to keep dim cells; raise only if many "
				+ "background false positives), "
				+ "track_intensity_ratio (default " + DEFAULT_TRACK_INTENSITY_RATIO + ": OFF — deletes "
				+ "any detection dimmer than this fraction of the timepoint's median, which can discard "
				+ "real dimming cells (e.g. photobleaching); raise only if the visual clearly shows "
				+ "background noise being detected as spots), "
				+ "quality_threshold (default " + DEFAULT_QUALITY_THRESHOLD + ": ABSOLUTE cutoff on "
				+ "ELEPHANT's own per-spot detection confidence — unlike the two ratios above, no median "
				+ "involved, since quality is already a calibrated model probability; applies only to spots "
				+ "with a recorded quality, automatically falling back to min_intensity_ratio/"
				+ "track_intensity_ratio for spots without one, e.g. an older server), "
				+ "min_spots_per_tp (default " + DEFAULT_MIN_SPOTS_PER_TP + "), "
				+ "correct_per_window (bool, default true), "
				+ "n_crops (default " + DEFAULT_N_CROPS + ": crops/timepoint for training — training "
				+ "time scales with n_crops x annotation-timepoints x n_epochs; lower = faster, "
				+ "0 = ELEPHANT Preferences value), n_epochs (0 = ELEPHANT Preferences value), "
				+ "refine_pass (bool, default true: ITERATE 'interpolate missed cells → train on them → "
				+ "re-detect' until DETECTION IS COMPLETE (see completeness_target) — bootstraps recall "
				+ "from a randomly-initialised model), refine_iterations (max rounds, default "
				+ DEFAULT_REFINE_ITERATIONS + "), completeness_target (default "
				+ DEFAULT_COMPLETENESS_TARGET + ": detected÷estimated cell count from image maxima; "
				+ "refinement continues and detection is flagged INCOMPLETE below this; 0 disables), "
				+ "train_timeout_sec (default " + ( DEFAULT_TRAIN_TIMEOUT_MS / 1000 ) + ": safety cap "
				+ "per training job; if exceeded the server job is aborted), "
				+ "cold_start_spots_per_tp (default " + ( int ) DEFAULT_COLD_START_CEILING
				+ ": spots/tp above this triggers intensity-seed bootstrap), "
				+ "n_seeds_per_tp (default " + DEFAULT_N_SEEDS_PER_TP + "), "
				+ "long_link_factor, merge_overlap_ratio, start_timepoint, end_timepoint, "
				+ "protected_tag_set / protected_tag_label (both optional, off by default: name an existing "
				+ "Mastodon tag set/tag, e.g. protected_tag_set=\"Tracking\", protected_tag_label=\"Approved\", "
				+ "to mark spots as an already human-confirmed result — PROTECTED spots are never deleted, "
				+ "merged, refit, or duplicated by a fresh detection anywhere in this run; they are used only "
				+ "as trusted tp training signal and are kept exactly as-is in the final result).\n"
				+ "Requires ELEPHANT server to be running.";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final ElephantIntegration elephant = new ElephantIntegration( context );
		if ( !elephant.init() )
			return "Failed to initialize ELEPHANT. Is the server running?";

		// Register abort hook so that requestCancel() immediately signals the
		// ELEPHANT server to stop any running inference or training.
		context.setElephantAbortHook( elephant::abortProcessing );

		final String mode = ( String ) params.getOrDefault( PARAM_MODE, "predict_and_link" );

		// ---- adaptive_track mode ----
		if ( "adaptive_track".equals( mode ) )
		{
			final ViewerPanel viewer = context.requireViewer();
			final int numTimepoints = viewer.state().getNumTimepoints();
			// [minTp, maxTp]: dataset range narrowed by the run's stated range (RunRange), if any.
			final int[] tpBounds = runTimepointBounds( context, numTimepoints );
			final int minTp = tpBounds[ 0 ], maxTp = tpBounds[ 1 ];
			// Clamp to [minTp, maxTp]. A weak agent sometimes passes end_timepoint=numTimepoints (off-by-one),
			// which would spawn a spurious empty window (e.g. [t=10..10] on a 10-timepoint t=0..9 dataset)
			// that pollutes the results.
			final int endTp = Math.min( maxTp, params.containsKey( PARAM_END_TIMEPOINT )
					? ( ( Number ) params.get( PARAM_END_TIMEPOINT ) ).intValue() : maxTp );
			final int startTp;
			final Object startOverride = context.getSharedData( SHARED_START_TIMEPOINT_OVERRIDE );
			if ( startOverride instanceof Number )
			{
				// The user said where to begin, so neither the agent's parameter nor the resume
				// inference gets a say. Same precedence as every other pre-flight override.
				startTp = Math.max( minTp,
						Math.min( endTp, ( ( Number ) startOverride ).intValue() ) );
				context.log( String.format( "  Starting at t=%d — you set that in the pre-flight dialog, "
						+ "so this run neither resumes from what is already tracked nor takes the agent's "
						+ "own start_timepoint.", startTp ) );
			}
			else if ( params.containsKey( PARAM_START_TIMEPOINT ) )
			{
				startTp = Math.max( minTp, ( ( Number ) params.get( PARAM_START_TIMEPOINT ) ).intValue() );
			}
			else
			{
				// No explicit start: RESUME from where a previous run left off. If spots already exist
				// through some timepoint < end, continue forward from the next one (the natural "t=49 done →
				// start t=50"); if nothing is tracked yet — or the whole range already is (a re-run to
				// improve quality) — start from minTp. Pass start_timepoint to override (e.g. re-do a range).
				// "Start fresh" is an instruction about where to begin, not only about what to delete.
				// It cannot be expressed by clearing state, because a fresh start deliberately KEEPS the
				// user's confirmed spots and the resume path reads spots as progress.
				final boolean startFresh =
						Boolean.TRUE.equals( context.getSharedData( SHARED_START_FRESH_REQUESTED ) );
				final int lastDone = startFresh ? -1 : maxTimepointWithSpots( context, endTp );
				if ( startFresh )
					context.log( String.format(
							"  Starting at t=%d — you asked to start fresh, so this run does not resume from "
									+ "anything already in the project.", minTp ) );
				if ( lastDone >= 0 && lastDone < endTp && lastDone + 1 >= minTp )
				{
					startTp = lastDone + 1;
					context.log( String.format( "  Resuming from t=%d — t=0..%d already have spots. "
							+ "Pass start_timepoint to re-process an earlier range instead.", startTp, lastDone ) );
				}
				else
				{
					// FULLY POPULATED only: spot presence cannot reveal how far the CURRENT pass got when an
					// earlier pass already filled the range, so consult the persisted progress marker. This
					// is what lets an interrupted SECOND pass continue — e.g. pass 1 filled t=0..499, pass 2
					// stopped at t=199 → resume at t=200 even though spots reach 499.
					//
					// NOT on an empty project, which this used to treat as the same case. A marker says
					// "everything below me is done"; with no spots anywhere that claim is simply false, and
					// acting on it silently drops the timepoints below it from the run. Observed: a marker
					// left at t=10 by an interrupted run sent the next run — over a project reporting
					// total_spots=0 — straight to t=10, and t=0..9 were never tracked at all. The marker
					// outlived the data it described.
					//
					// Nor when the user asked to start fresh. That is an instruction about where to BEGIN,
					// and it set lastDone to -1 above, which used to route it into exactly this branch: the
					// run would log "does not resume from anything already in the project" and resume anyway.
					final int marker = AutomahoutSettings.getAdaptiveResumeTimepoint();
					final String refusal = resumeMarkerRefusal( marker,
							AutomahoutSettings.getAdaptiveResumeNumTimepoints(),
							AutomahoutSettings.getAdaptiveResumeDataset(), minTp, endTp, numTimepoints,
							AutoCorrectOrchestrator.currentElephantDataset(), startFresh, lastDone );
					if ( refusal == null )
					{
						startTp = marker;
						context.log( String.format( "  Resuming interrupted pass from t=%d (saved progress "
								+ "marker). Pass start_timepoint to override.", startTp ) );
					}
					else if ( !refusal.isEmpty() )
					{
						startTp = minTp;
						// Say why it was NOT used. A marker quietly ignored is as surprising as one quietly
						// obeyed, and the run is about to start somewhere the reader did not ask for either.
						context.log( String.format(
								"  Ignoring the saved progress marker at t=%d: %s. Starting at t=%d.",
								marker, refusal, minTp ) );
					}
					else
					{
						startTp = minTp;
						if ( lastDone == endTp )
							context.log( String.format( "  Whole range t=%d..%d already has spots — re-running "
									+ "from t=%d to improve quality (pass start_timepoint to target a sub-range).",
									minTp, endTp, minTp ) );
					}
				}
			}
			final int windowSize = params.containsKey( PARAM_WINDOW_SIZE )
					? ( ( Number ) params.get( PARAM_WINDOW_SIZE ) ).intValue() : DEFAULT_WINDOW_SIZE;
			final int nAnnotPerWindow = params.containsKey( PARAM_N_ANNOT_PER_WINDOW )
					? ( ( Number ) params.get( PARAM_N_ANNOT_PER_WINDOW ) ).intValue() : DEFAULT_N_ANNOT_PER_WINDOW;
			final int nIterations = params.containsKey( PARAM_N_ITERATIONS )
					? ( ( Number ) params.get( PARAM_N_ITERATIONS ) ).intValue() : DEFAULT_N_ITERATIONS;
			// Default lowered from 0.4 to 0.25: a strict intensity filter removes dim cells from
			// the training set, teaching the model to miss them (recall loss). auto.correct still
			// prunes any false positives downstream, so a looser filter here favours recall.
			final double minIntensityRatio = params.containsKey( PARAM_MIN_INTENSITY_RATIO )
					? ( ( Number ) params.get( PARAM_MIN_INTENSITY_RATIO ) ).doubleValue() : 0.25;
			final double trackIntensityRatio = params.containsKey( PARAM_TRACK_INTENSITY_RATIO )
					? ( ( Number ) params.get( PARAM_TRACK_INTENSITY_RATIO ) ).doubleValue()
					: DEFAULT_TRACK_INTENSITY_RATIO;
			final double qualityThreshold = params.containsKey( PARAM_QUALITY_THRESHOLD )
					? ( ( Number ) params.get( PARAM_QUALITY_THRESHOLD ) ).doubleValue()
					: DEFAULT_QUALITY_THRESHOLD;
			final long trainTimeoutMs = params.containsKey( PARAM_TRAIN_TIMEOUT_SEC )
					? ( long ) ( ( ( Number ) params.get( PARAM_TRAIN_TIMEOUT_SEC ) ).doubleValue() * 1000 )
					: DEFAULT_TRAIN_TIMEOUT_MS;
			final Object coldStartCeilingOverride = context.getSharedData( SHARED_COLD_START_CEILING_OVERRIDE );
			final double coldStartCeiling = coldStartCeilingOverride instanceof Number
					? ( ( Number ) coldStartCeilingOverride ).doubleValue()
					: params.containsKey( PARAM_COLD_START_SPOTS_PER_TP )
							? ( ( Number ) params.get( PARAM_COLD_START_SPOTS_PER_TP ) ).doubleValue()
							: DEFAULT_COLD_START_CEILING;
			final int nSeedsPerTp = params.containsKey( PARAM_N_SEEDS_PER_TP )
					? ( ( Number ) params.get( PARAM_N_SEEDS_PER_TP ) ).intValue() : DEFAULT_N_SEEDS_PER_TP;
			final double minSize = params.containsKey( PARAM_MIN_SIZE )
					? ( ( Number ) params.get( PARAM_MIN_SIZE ) ).doubleValue() : DEFAULT_MIN_SIZE;
			int nCropsRequested = params.containsKey( PARAM_N_CROPS )
					? ( ( Number ) params.get( PARAM_N_CROPS ) ).intValue() : DEFAULT_N_CROPS;
			final Object nCropsOverride = context.getSharedData( SHARED_N_CROPS_OVERRIDE );
			if ( nCropsOverride instanceof Number && ( ( Number ) nCropsOverride ).intValue() != nCropsRequested )
			{
				context.log( String.format( "  Overriding n_crops %d -> %d (pre-flight ELEPHANT setting).",
						nCropsRequested, ( ( Number ) nCropsOverride ).intValue() ) );
				nCropsRequested = ( ( Number ) nCropsOverride ).intValue();
			}
			final int nCrops = nCropsRequested;
			final int nEpochs = params.containsKey( PARAM_N_EPOCHS )
					? ( ( Number ) params.get( PARAM_N_EPOCHS ) ).intValue() : 0;
			// Default ON: the "interpolate missed cells → train on them → re-detect" loop is the key
			// to bootstrapping recall from a randomly-initialised model. It ITERATES (see below) until
			// the detection count stops growing. Disable with refine_pass=false.
			final boolean refinePass = !( Boolean.FALSE.equals( params.get( PARAM_REFINE_PASS ) )
					|| "false".equalsIgnoreCase( String.valueOf( params.get( PARAM_REFINE_PASS ) ) ) );
			int refineIterationsRequested = params.containsKey( PARAM_REFINE_ITERATIONS )
					? ( ( Number ) params.get( PARAM_REFINE_ITERATIONS ) ).intValue()
					: DEFAULT_REFINE_ITERATIONS;
			double completenessTargetRequested = params.containsKey( PARAM_COMPLETENESS_TARGET )
					? ( ( Number ) params.get( PARAM_COMPLETENESS_TARGET ) ).doubleValue()
					: DEFAULT_COMPLETENESS_TARGET;
			// Hard, run-scoped ceilings from the pre-flight review dialog (see SHARED_MAX_REFINE_ITERATIONS)
			// — the agent cannot see these and may request more; clamp down regardless, the same way
			// runTimepointBounds() enforces a range the agent also cannot see.
			final Object refineCap = context.getSharedData( SHARED_MAX_REFINE_ITERATIONS );
			if ( refineCap instanceof Number && refineIterationsRequested > ( ( Number ) refineCap ).intValue() )
			{
				context.log( String.format(
						"  Capping refine_iterations %d -> %d (pre-flight limit).",
						refineIterationsRequested, ( ( Number ) refineCap ).intValue() ) );
				refineIterationsRequested = ( ( Number ) refineCap ).intValue();
			}
			final Object completenessCap = context.getSharedData( SHARED_COMPLETENESS_TARGET_CAP );
			if ( completenessCap instanceof Number
					&& completenessTargetRequested > ( ( Number ) completenessCap ).doubleValue() )
			{
				context.log( String.format(
						"  Capping completeness_target %.2f -> %.2f (pre-flight limit).",
						completenessTargetRequested, ( ( Number ) completenessCap ).doubleValue() ) );
				completenessTargetRequested = ( ( Number ) completenessCap ).doubleValue();
			}
			final int refineIterations = refineIterationsRequested;
			final double completenessTarget = completenessTargetRequested;
			final double minSpotsPerTp = params.containsKey( PARAM_MIN_SPOTS_PER_TP )
					? ( ( Number ) params.get( PARAM_MIN_SPOTS_PER_TP ) ).doubleValue() : DEFAULT_MIN_SPOTS_PER_TP;
			final boolean correctPerWindow =
					!( Boolean.FALSE.equals( params.get( PARAM_CORRECT_PER_WINDOW ) )
							|| "false".equalsIgnoreCase(
									String.valueOf( params.get( PARAM_CORRECT_PER_WINDOW ) ) ) );
			// Default ON: after each window, run a whole-graph topology consistency pass over the
			// tracked-so-far graph (dissolve rapid divisions, delete spurious short branches, reconnect
			// broken tracklets) so intermediate results are already de-branched/de-fragmented, not only
			// at the very end. Frontier-protected. Disable with consistency_per_batch=false.
			final boolean consistencyPerBatch =
					!( Boolean.FALSE.equals( params.get( PARAM_CONSISTENCY_PER_BATCH ) )
							|| "false".equalsIgnoreCase(
									String.valueOf( params.get( PARAM_CONSISTENCY_PER_BATCH ) ) ) );
			// Diagnostic cleanup switches. Recorded on the context rather than threaded through the
			// pipeline: the two passes they gate are called from a dozen sites across this class and
			// AutoCorrectOrchestrator, and a flag that reached only some of them would produce a run
			// that is neither the old behaviour nor the requested one — the exact ambiguity these
			// switches exist to remove. A pre-flight dialog choice still wins over these; see
			// CleanupToggles.
			if ( params.containsKey( PARAM_DUPLICATE_OVERLAP_FACTOR ) )
			{
				final double f = ( ( Number ) params.get( PARAM_DUPLICATE_OVERLAP_FACTOR ) ).doubleValue();
				context.setSharedData( AutoCorrectOrchestrator.SHARED_DUPLICATE_OVERLAP_FACTOR, f );
				context.log( String.format( "  Over-segmentation reach set to %.2f x the larger spot radius "
					+ "(default %.2f).", f, AutoCorrectOrchestrator.DUPLICATE_OVERLAP_FACTOR_DEFAULT ) );
			}
			CleanupToggles.setRequested( context,
					!( Boolean.FALSE.equals( params.get( PARAM_FLAT_CONTRAST_FILTER ) )
							|| "false".equalsIgnoreCase(
									String.valueOf( params.get( PARAM_FLAT_CONTRAST_FILTER ) ) ) ),
					// Merging is opt-IN, so the test is the other way round from the flat-contrast one
					// above: only an explicit yes turns it on. See CleanupToggles.
					Boolean.TRUE.equals( params.get( PARAM_RESOLVE_DUPLICATES ) )
							|| "true".equalsIgnoreCase(
									String.valueOf( params.get( PARAM_RESOLVE_DUPLICATES ) ) ),
					// Splitting is opt-in for the same reason.
					Boolean.TRUE.equals( params.get( PARAM_SPLIT_MERGED ) )
							|| "true".equalsIgnoreCase(
									String.valueOf( params.get( PARAM_SPLIT_MERGED ) ) ) );
			// The pre-flight dialog's choice (SHARED_PROTECTED_TAG_CONFIG) is enforced regardless of what
			// the agent requests, same as SHARED_N_CROPS_OVERRIDE; only a standalone adaptive_track call
			// with no pre-flight dialog (e.g. a test) falls back to the agent's own per-call params.
			final Object protectedTagOverride = context.getSharedData( SHARED_PROTECTED_TAG_CONFIG );
			final ProtectedSpots.Config protectedSpots = protectedTagOverride instanceof ProtectedSpots.Config
					? ( ProtectedSpots.Config ) protectedTagOverride
					: ProtectedSpots.Config.of( ( String ) params.get( PARAM_PROTECTED_TAG_SET ),
							( String ) params.get( PARAM_PROTECTED_TAG_LABEL ) );
			// Stored on the context (not passed as a parameter) so every deletion/mutation helper across
			// this class and AutoCorrectOrchestrator can consult it — see ProtectedSpots.SHARED_CONFIG_KEY.
			context.setSharedData( ProtectedSpots.SHARED_CONFIG_KEY, protectedSpots );
			if ( protectedSpots != null )
				context.log( String.format(
						"  Spots tagged %s > %s are PROTECTED — never deleted/merged/refit/duplicated by "
								+ "this run; used only as trusted tp training signal.",
						protectedSpots.tagSetName, protectedSpots.tagLabel ) );
			final double dedupFactor = params.containsKey( PARAM_DEDUP_FACTOR )
					? ( ( Number ) params.get( PARAM_DEDUP_FACTOR ) ).doubleValue() : DEFAULT_DEDUP_FACTOR;
			final double borderMargin = params.containsKey( PARAM_BORDER_MARGIN )
					? ( ( Number ) params.get( PARAM_BORDER_MARGIN ) ).doubleValue() : DEFAULT_BORDER_MARGIN;
			final double maxAspectRatio = params.containsKey( PARAM_MAX_ASPECT_RATIO )
					? ( ( Number ) params.get( PARAM_MAX_ASPECT_RATIO ) ).doubleValue()
					: DEFAULT_MAX_ASPECT_RATIO;
			// Safe per-window settings: no tracklet length pruning or gap filling
			// (these would incorrectly delete boundary spots that have no future links yet;
			// gap filling is left to the global Phase-2 auto.correct which sees whole tracks).
			final AutomahoutSettings corrSettings = new AutomahoutSettings();
			corrSettings.setCorrectMinTrackletLength( 0 );
			corrSettings.setCorrectFillGaps( false );
			corrSettings.setCorrectMinIntensityRatio( 0.0 );
			corrSettings.setCorrectQualityThreshold( 0.0 );
			corrSettings.setCorrectMinSizeRatio( 0.0 );
			corrSettings.setCorrectDivisionLinkFactor( 0.0 );
			// Also force off regardless of whatever a PREVIOUS session/dataset left persisted in Mastodon
			// Preferences (new AutomahoutSettings() loads it, and it carries across projects): refit calls
			// ELEPHANT ONCE PER anisotropic spot with no candidate cap (unlike fillGaps' 64-candidate
			// limit), so if a window's detection is a bad/untrained-model flood, this alone can queue
			// hundreds of wasted individual server requests before this round's result is even validated.
			corrSettings.setCorrectRefitAnisotropic( false );
			if ( params.containsKey( PARAM_LONG_LINK_FACTOR ) )
				corrSettings.setCorrectLongLinkFactor(
						( ( Number ) params.get( PARAM_LONG_LINK_FACTOR ) ).doubleValue() );
			if ( params.containsKey( PARAM_MERGE_OVERLAP_RATIO ) )
				corrSettings.setCorrectMergeOverlapRatio(
						( ( Number ) params.get( PARAM_MERGE_OVERLAP_RATIO ) ).doubleValue() );
			// Per-frame displacement cap for per-window link pruning. Without it, long-link pruning only
			// uses long_link_factor × cell radius, so a BIG/bright cell's large radius lets a wrong link to
			// a far, dim spot survive (the NN linker, esp. with optical flow, can pick a far source). The cap
			// is the learned P99 if a constraint profile is active, else ~4× the median consecutive-link
			// displacement in this range (robust to the few wrong long links we want to remove).
			// Measured over the WHOLE movie, not over startTp..endTp. adaptive_track detects INTO its
			// range, so at this moment that range is empty by construction and the estimate — which
			// walks consecutive links — had nothing to walk and returned 0. A resumed run holds tracked
			// timepoints outside the requested range and those are exactly the ones worth measuring.
			// Confirmed from a run resuming at t=30: the "caps per-frame displacement" line below never
			// printed, and [5c/6] reported max_disp=0.0 for every window of the run.
			final double maxDisp = AutoCorrectOrchestrator.estimateMaxDisplacement(
					context, corrSettings, 0, numTimepoints - 1 );
			if ( maxDisp > 0.0 )
			{
				corrSettings.setCorrectMaxDisplacement( maxDisp );
				context.log( String.format( "  Per-window link pruning caps per-frame displacement at %.1f µm "
						+ "(%s) — prevents a large cell linking to a far, dim spot.", maxDisp,
						AutoCorrectOrchestrator.learnedMaxDisplacement( corrSettings ) > 0.0
								? "learned P99" : "≈4× median link length" ) );
			}
			return runAdaptiveTrack( context, elephant, viewer, startTp, endTp, numTimepoints,
					windowSize, nAnnotPerWindow, nIterations,
					minIntensityRatio, trackIntensityRatio, qualityThreshold, minSpotsPerTp,
					correctPerWindow, consistencyPerBatch, dedupFactor, borderMargin, maxAspectRatio, trainTimeoutMs,
					coldStartCeiling, nSeedsPerTp, minSize, nCrops, nEpochs, refinePass, refineIterations,
					completenessTarget, corrSettings );
		}

		// ---- windowed_train mode ----
		if ( "windowed_train".equals( mode ) )
		{
			final ViewerPanel viewer = context.requireViewer();
			final int numTimepoints = viewer.state().getNumTimepoints();
			final int[] tpBounds = runTimepointBounds( context, numTimepoints );
			final int minTp = tpBounds[ 0 ], maxTp = tpBounds[ 1 ];
			final int startTp = params.containsKey( PARAM_START_TIMEPOINT )
					? Math.max( minTp, Math.min( maxTp, ( ( Number ) params.get( PARAM_START_TIMEPOINT ) ).intValue() ) )
					: minTp;
			final int endTp = params.containsKey( PARAM_END_TIMEPOINT )
					? Math.min( maxTp, Math.max( minTp, ( ( Number ) params.get( PARAM_END_TIMEPOINT ) ).intValue() ) )
					: maxTp;
			final int windowSize = params.containsKey( PARAM_WINDOW_SIZE )
					? ( ( Number ) params.get( PARAM_WINDOW_SIZE ) ).intValue() : DEFAULT_WINDOW_SIZE;
			final int nAnnotPerWindow = params.containsKey( PARAM_N_ANNOT_PER_WINDOW )
					? ( ( Number ) params.get( PARAM_N_ANNOT_PER_WINDOW ) ).intValue() : DEFAULT_N_ANNOT_PER_WINDOW;
			final int nIterations = params.containsKey( PARAM_N_ITERATIONS )
					? ( ( Number ) params.get( PARAM_N_ITERATIONS ) ).intValue() : DEFAULT_N_ITERATIONS;
			final double minIntensityRatio = params.containsKey( PARAM_MIN_INTENSITY_RATIO )
					? ( ( Number ) params.get( PARAM_MIN_INTENSITY_RATIO ) ).doubleValue() : 0.4;
			final double qualityThreshold = params.containsKey( PARAM_QUALITY_THRESHOLD )
					? ( ( Number ) params.get( PARAM_QUALITY_THRESHOLD ) ).doubleValue()
					: DEFAULT_QUALITY_THRESHOLD;
			final double minSpotsPerTp = params.containsKey( PARAM_MIN_SPOTS_PER_TP )
					? ( ( Number ) params.get( PARAM_MIN_SPOTS_PER_TP ) ).doubleValue() : DEFAULT_MIN_SPOTS_PER_TP;
			return runWindowedTrain( context, elephant, viewer, startTp, endTp,
					windowSize, nAnnotPerWindow, nIterations, minIntensityRatio, qualityThreshold, minSpotsPerTp );
		}

		// ---- iterative_train mode ----
		if ( "iterative_train".equals( mode ) )
		{
			final ViewerPanel viewer = context.requireViewer();
			final int numTimepoints = viewer.state().getNumTimepoints();
			final int[] tpBounds = runTimepointBounds( context, numTimepoints );
			final int minTp = tpBounds[ 0 ], maxTp = tpBounds[ 1 ];
			final int startTp = params.containsKey( PARAM_START_TIMEPOINT )
					? Math.max( minTp, Math.min( maxTp, ( ( Number ) params.get( PARAM_START_TIMEPOINT ) ).intValue() ) )
					: minTp;
			final int endTp = params.containsKey( PARAM_END_TIMEPOINT )
					? Math.min( maxTp, Math.max( minTp, ( ( Number ) params.get( PARAM_END_TIMEPOINT ) ).intValue() ) )
					: maxTp;
			final int loadDelayMs = params.containsKey( PARAM_LOAD_DELAY_MS )
					? ( ( Number ) params.get( PARAM_LOAD_DELAY_MS ) ).intValue() : DEFAULT_LOAD_DELAY_MS;
			final double minIntensityRatio = params.containsKey( PARAM_MIN_INTENSITY_RATIO )
					? ( ( Number ) params.get( PARAM_MIN_INTENSITY_RATIO ) ).doubleValue() : 0.4;
			final double qualityThreshold = params.containsKey( PARAM_QUALITY_THRESHOLD )
					? ( ( Number ) params.get( PARAM_QUALITY_THRESHOLD ) ).doubleValue()
					: DEFAULT_QUALITY_THRESHOLD;
			final int nAnnotationTp = params.containsKey( PARAM_N_ANNOTATION_TIMEPOINTS )
					? ( ( Number ) params.get( PARAM_N_ANNOTATION_TIMEPOINTS ) ).intValue()
					: DEFAULT_N_ANNOTATION_TIMEPOINTS;
			final int nIterations = params.containsKey( PARAM_N_ITERATIONS )
					? ( ( Number ) params.get( PARAM_N_ITERATIONS ) ).intValue() : DEFAULT_N_ITERATIONS;
			final double minSpotsPerTp = params.containsKey( PARAM_MIN_SPOTS_PER_TP )
					? ( ( Number ) params.get( PARAM_MIN_SPOTS_PER_TP ) ).doubleValue()
					: DEFAULT_MIN_SPOTS_PER_TP;
			return runIterativeTrain( context, elephant, viewer, startTp, endTp,
					loadDelayMs, minIntensityRatio, qualityThreshold, nAnnotationTp, nIterations, minSpotsPerTp );
		}

		// ---- annotate_and_train mode ----
		if ( "annotate_and_train".equals( mode ) )
		{
			final ViewerPanel viewer = context.requireViewer();
			final int numTimepoints = viewer.state().getNumTimepoints();
			final int[] tpBounds = runTimepointBounds( context, numTimepoints );
			final int minTp = tpBounds[ 0 ], maxTp = tpBounds[ 1 ];
			final int startTp = params.containsKey( PARAM_START_TIMEPOINT )
					? Math.max( minTp, Math.min( maxTp, ( ( Number ) params.get( PARAM_START_TIMEPOINT ) ).intValue() ) )
					: minTp;
			final int endTp = params.containsKey( PARAM_END_TIMEPOINT )
					? Math.min( maxTp, Math.max( minTp, ( ( Number ) params.get( PARAM_END_TIMEPOINT ) ).intValue() ) )
					: maxTp;
			final int loadDelayMs = params.containsKey( PARAM_LOAD_DELAY_MS )
					? ( ( Number ) params.get( PARAM_LOAD_DELAY_MS ) ).intValue()
					: DEFAULT_LOAD_DELAY_MS;
			final double minIntensityRatio = params.containsKey( PARAM_MIN_INTENSITY_RATIO )
					? ( ( Number ) params.get( PARAM_MIN_INTENSITY_RATIO ) ).doubleValue()
					: 0.4;
			final double qualityThreshold = params.containsKey( PARAM_QUALITY_THRESHOLD )
					? ( ( Number ) params.get( PARAM_QUALITY_THRESHOLD ) ).doubleValue()
					: DEFAULT_QUALITY_THRESHOLD;
			final int nAnnotationTp = params.containsKey( PARAM_N_ANNOTATION_TIMEPOINTS )
					? ( ( Number ) params.get( PARAM_N_ANNOTATION_TIMEPOINTS ) ).intValue()
					: DEFAULT_N_ANNOTATION_TIMEPOINTS;
			final boolean keepAnnotations = Boolean.TRUE.equals( params.get( PARAM_KEEP_ANNOTATIONS ) )
					|| "true".equalsIgnoreCase( String.valueOf( params.get( PARAM_KEEP_ANNOTATIONS ) ) );
			final int nEpochs = params.containsKey( PARAM_N_EPOCHS )
					? ( ( Number ) params.get( PARAM_N_EPOCHS ) ).intValue()
					: 0;
			return runAnnotateAndTrain( context, elephant, viewer, startTp, endTp,
					loadDelayMs, minIntensityRatio, qualityThreshold, nAnnotationTp, keepAnnotations, nEpochs );
		}

		final boolean allTimepoints = Boolean.TRUE.equals( params.get( PARAM_ALL_TIMEPOINTS ) )
				|| "true".equalsIgnoreCase( String.valueOf( params.get( PARAM_ALL_TIMEPOINTS ) ) );

		if ( !allTimepoints )
			return runSingleTimepoint( elephant, mode );

		// ---- multi-timepoint mode ----
		final ViewerPanel viewer = context.requireViewer();
		final int numTimepoints = viewer.state().getNumTimepoints();
		final int[] tpBounds = runTimepointBounds( context, numTimepoints );
		final int minTp = tpBounds[ 0 ], maxTp = tpBounds[ 1 ];

		final int startTp = params.containsKey( PARAM_START_TIMEPOINT )
				? Math.max( minTp, Math.min( maxTp, ( ( Number ) params.get( PARAM_START_TIMEPOINT ) ).intValue() ) )
				: minTp;
		final int endTp = params.containsKey( PARAM_END_TIMEPOINT )
				? Math.min( maxTp, Math.max( minTp, ( ( Number ) params.get( PARAM_END_TIMEPOINT ) ).intValue() ) )
				: maxTp;
		final int loadDelayMs = params.containsKey( PARAM_LOAD_DELAY_MS )
				? ( ( Number ) params.get( PARAM_LOAD_DELAY_MS ) ).intValue()
				: DEFAULT_LOAD_DELAY_MS;
		final double minIntensityRatio = params.containsKey( PARAM_MIN_INTENSITY_RATIO )
				? ( ( Number ) params.get( PARAM_MIN_INTENSITY_RATIO ) ).doubleValue()
				: 0.0;
		final double qualityThreshold = params.containsKey( PARAM_QUALITY_THRESHOLD )
				? ( ( Number ) params.get( PARAM_QUALITY_THRESHOLD ) ).doubleValue()
				: DEFAULT_QUALITY_THRESHOLD;
		final int chunkSize = params.containsKey( PARAM_CHUNK_SIZE )
				? ( ( Number ) params.get( PARAM_CHUNK_SIZE ) ).intValue()
				: DEFAULT_CHUNK_SIZE;
		final boolean correctPerChunk = Boolean.TRUE.equals( params.get( PARAM_CORRECT_PER_CHUNK ) )
				|| "true".equalsIgnoreCase( String.valueOf( params.get( PARAM_CORRECT_PER_CHUNK ) ) );

		final AutomahoutSettings corrSettings = new AutomahoutSettings();
		if ( params.containsKey( PARAM_LONG_LINK_FACTOR ) )
			corrSettings.setCorrectLongLinkFactor(
					( ( Number ) params.get( PARAM_LONG_LINK_FACTOR ) ).doubleValue() );
		if ( params.containsKey( PARAM_MERGE_OVERLAP_RATIO ) )
			corrSettings.setCorrectMergeOverlapRatio(
					( ( Number ) params.get( PARAM_MERGE_OVERLAP_RATIO ) ).doubleValue() );

		return runAllTimepoints( context, elephant, mode, startTp, endTp, numTimepoints,
				loadDelayMs, minIntensityRatio, qualityThreshold, chunkSize, correctPerChunk, corrSettings );
	}

	// -----------------------------------------------------------------------

	private static String runSingleTimepoint( final ElephantIntegration elephant, final String mode )
	{
		switch ( mode )
		{
		case "predict":
			return elephant.predictSpots()
					? "Spot prediction completed for current timepoint"
					: "Failed to run spot prediction";

		case "link":
			return elephant.linkSpots()
					? "NN linking completed for current timepoint"
					: "Failed to run NN linking";

		case "predict_and_link":
			return elephant.predictAndLink()
					? "Prediction + linking completed for current timepoint"
					: "Failed to run prediction and linking";

		default:
			throw new IllegalArgumentException(
					"Unknown mode: '" + mode
							+ "'. Use 'predict', 'link', or 'predict_and_link'." );
		}
	}

	/**
	 * Runs sparse annotation training: detects spots on a few representative
	 * timepoints, uses them as TP annotations, fine-tunes the ELEPHANT model,
	 * then optionally removes the annotation spots from the graph.
	 */
	private static String runAnnotateAndTrain(
			final ToolContext context,
			final ElephantIntegration elephant,
			final ViewerPanel viewer,
			final int startTp,
			final int endTp,
			final int loadDelayMs,
			final double minIntensityRatio,
			final double qualityThreshold,
			final int nAnnotationTp,
			final boolean keepAnnotations,
			final int nEpochs )
	{
		// Select evenly-spaced annotation timepoints
		final int n = Math.max( 1, Math.min( nAnnotationTp, endTp - startTp + 1 ) );
		final int[] annotationTimepoints = selectEvenlySpaced( startTp, endTp, n );

		context.log( String.format( "=== Sparse annotation: detecting on %d timepoints ===", n ) );
		int detected = 0, darkDeleted = 0;
		for ( final int t : annotationTimepoints )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				return "Cancelled during annotation.";
			context.log( "  Predicting t=" + t + "..." );
			if ( !setTimepoint( viewer, t, loadDelayMs ) )
				return "Interrupted while setting timepoint.";
			final ElephantIntegration e = new ElephantIntegration( context );
			if ( !e.init() ) continue;
			if ( e.predictSpots( t ) )
			{
				detected++;
				if ( minIntensityRatio > 0.0 || qualityThreshold > 0.0 )
					darkDeleted += AutoCorrectOrchestrator.filterWeakSpots(
							context, t, minIntensityRatio, qualityThreshold );
			}
		}

		// Count annotation spots
		final int spotCount = countAnnotationsAtTimepoints( context, annotationTimepoints );
		context.log( String.format(
				"  Annotation: %d spots from %d/%d timepoints (%d dark removed).",
				spotCount, detected, n, darkDeleted ) );

		if ( spotCount == 0 )
			return "No annotation spots found — training skipped. Check ELEPHANT server and detection settings.";

		// Train — /seg/train writes the annotation labels itself before training, so a separate
		// /seg/update call first would just repeat that (expensive) write for nothing.
		prepareAnnotationsForTraining( context, annotationTimepoints );
		context.log( "=== Training ELEPHANT model (this may take several minutes)... ===" );
		final int lastAnnotationTp = annotationTimepoints[ annotationTimepoints.length - 1 ];
		final boolean trained = elephant.trainDetectionModel( annotationTimepoints, lastAnnotationTp, nEpochs, 0, DEFAULT_TRAIN_TIMEOUT_MS );

		// Remove annotation spots from graph unless kept
		if ( !keepAnnotations )
		{
			final int removed = deleteAllSpotsAtTimepoints( context, annotationTimepoints );
			context.log( "  Removed " + removed + " annotation spots from graph." );
		}

		if ( trained )
			return String.format( "ELEPHANT model training complete. Used %d spots from %d timepoints as annotations.", spotCount, n );
		else
			return "Training was aborted or failed — check ELEPHANT server logs.";
	}

	/**
	 * Integrated per-window pipeline: for each window of timepoints, runs
	 * detection quality test → incremental training if needed → re-detection →
	 * linking → evaluation and correction → validation before advancing.
	 */
	private static String runAdaptiveTrack(
			final ToolContext context,
			final ElephantIntegration elephant,
			final ViewerPanel viewer,
			final int startTp,
			final int endTp,
			final int numTimepoints,
			final int windowSize,
			final int nAnnotPerWindow,
			final int nTrainIterations,
			final double minIntensityRatio,
			final double trackIntensityRatio,
			final double qualityThreshold,
			final double minSpotsPerTp,
			final boolean correctPerWindow,
			final boolean consistencyPerBatch,
			final double dedupFactor,
			final double borderMargin,
			final double maxAspectRatio,
			final long trainTimeoutMs,
			final double requestedColdStartCeiling,
			final int nSeedsPerTp,
			final double minSize,
			final int nCrops,
			final int nEpochs,
			final boolean refinePass,
			final int refineIterations,
			final double completenessTarget,
			final AutomahoutSettings corrSettings )
	{
		// Every variable the window loop's stages share lives here, so a stage can be a method of its own.
		final WindowRun w = new WindowRun();
		w.context = context;
		w.elephant = elephant;
		w.viewer = viewer;
		w.startTp = startTp;
		w.endTp = endTp;
		w.numTimepoints = numTimepoints;
		w.windowSize = windowSize;
		w.nAnnotPerWindow = nAnnotPerWindow;
		w.nTrainIterations = nTrainIterations;
		w.minIntensityRatio = minIntensityRatio;
		w.trackIntensityRatio = trackIntensityRatio;
		w.qualityThreshold = qualityThreshold;
		w.minSpotsPerTp = minSpotsPerTp;
		w.correctPerWindow = correctPerWindow;
		w.consistencyPerBatch = consistencyPerBatch;
		w.dedupFactor = dedupFactor;
		w.borderMargin = borderMargin;
		w.maxAspectRatio = maxAspectRatio;
		w.trainTimeoutMs = trainTimeoutMs;
		w.requestedColdStartCeiling = requestedColdStartCeiling;
		w.nSeedsPerTp = nSeedsPerTp;
		w.minSize = minSize;
		w.nCrops = nCrops;
		w.nEpochs = nEpochs;
		w.refinePass = refinePass;
		w.refineIterations = refineIterations;
		w.completenessTarget = completenessTarget;
		w.corrSettings = corrSettings;

		w.effectiveWindow = Math.max( 1, w.windowSize );
		w.totalWindows = ( int ) Math.ceil( ( double ) ( w.endTp - w.startTp + 1 ) / w.effectiveWindow );
		w.context.log( String.format(
				"=== Adaptive tracking: %d windows of %d tp, t=%d..%d ===",
				w.totalWindows, w.effectiveWindow, w.startTp, w.endTp ) );
		w.context.log( "  " + PipelineInvariants.activeChecksSummary( PipelineInvariants.snapshot( w.context ) ) );
		w.cleanupsOff = CleanupToggles.disabledSummary( w.context );
		if ( w.cleanupsOff != null )
			w.context.log( "  " + w.cleanupsOff );

		// One-time upfront sweep, across the WHOLE requested range: cleans up any detection that
		// already overlaps a protected spot from BEFORE this run started (e.g. a human picked one of
		// several overlapping candidates as the confirmed result but never deleted the others) — a
		// no-op when no protection is configured. predictWindow's own per-timepoint call covers new
		// duplicates created DURING this run; this covers ones that already existed going in.
		if ( ProtectedSpots.fromContext( w.context ) != null )
		{
			int preExistingOverlaps = 0;
			for ( int t = w.startTp; t <= w.endTp; t++ )
				preExistingOverlaps += ProtectedSpots.deleteOverlappingNonProtected( w.context, t );
			if ( preExistingOverlaps > 0 )
				w.context.log( String.format(
						"  Deleted %d pre-existing detection(s) overlapping a protected spot.",
						preExistingOverlaps ) );
		}

		// Compute image physical dimensions once and clamp border margin to 25% of smallest dim.
		// A border_margin larger than 25% of the smallest dimension would exclude all spots on
		// that axis (e.g. a thin Z stack with Z < 2 * border_margin).
		w.physDims = getPhysicalDimensions( w.context );
		// Over the axes the border check actually judges. Counting z on a single-plane movie made the
		// smallest dimension one voxel, so ANY border_margin was clamped to a quarter of a voxel and
		// the run printed a WARNING about it — see ImageBounds.
		w.minDim = isVolumetric( w.context )
				? Math.min( w.physDims[ 0 ], Math.min( w.physDims[ 1 ], w.physDims[ 2 ] ) )
				: Math.min( w.physDims[ 0 ], w.physDims[ 1 ] );
		w.context.log( String.format( "  Image physical size: %.1f x %.1f x %.1f µm",
				w.physDims[ 0 ], w.physDims[ 1 ], w.physDims[ 2 ] ) );
		/* effectiveBorderMargin: a WindowRun field */
		if ( w.borderMargin > 0.0 && w.borderMargin > w.minDim * 0.25 )
		{
			w.effectiveBorderMargin = w.minDim * 0.25;
			w.context.log( String.format(
					"  WARNING: border_margin=%.1f exceeds 25%% of smallest image dimension (%.1f µm)."
							+ " Clamped to %.1f. Set border_margin=0 to disable.",
					w.borderMargin, w.minDim, w.effectiveBorderMargin ) );
		}
		else
		{
			w.effectiveBorderMargin = w.borderMargin;
		}

		// Trusted GT annotations drive training (instead of the model's own detections) to break the
		// self-training loop that reinforces over-detection. Prefer the persistent GT annotation store
		// (set by the "Learn Lineage Constraints" actions): it holds the REAL GT and is immune to
		// re-runs mistaking the previous run's detections for GT. Only if no store is active do we fall
		// back to snapshotting whatever spots are currently in the graph.
		// Learned detection-acceptance profile (intensity+contrast). When active + usable, self-training
		// windows auto-tag detections tp/fp with it so background is trained as an explicit negative —
		// the autonomous version of the manual "Label TP/FP" step. Scoped to THIS dataset (see
		// autoAcceptanceProfilePath) and learned automatically as reliable tracks accumulate (below) — no
		// manual "Filter by Example" labelling required, and no risk of a profile learned on one dataset
		// leaking onto another (the old corrSettings.getAcceptanceProfilePath() was a single global path
		// shared across every dataset ever opened, see acceptance-profile-cross-dataset-contamination).
		w.acceptanceDatasetName = ElephantMainSettingsManager.getInstance().getSelectedStyle().getDatasetName();
		// Decided (and logged) before anything measures an intensity, so every number in this run's log
		// comes from one level and the log says which.
		w.samplingLevel = AutoCorrectOrchestrator.samplingLevel( w.context );
		w.samplingNote = AutoCorrectOrchestrator.samplingLevelNote( w.context );
		if ( w.samplingNote != null )
			w.context.log( w.samplingNote );
		// A learned acceptance profile stores CONTRAST values, and contrast is measured on the sampling
		// level, so a profile learned on another level describes a different scale. Scoping the file to
		// the level means such a profile is simply not found and a fresh one is learned, rather than
		// silently applying thresholds off by the ratio between two levels.
		w.autoAcceptanceProfilePath = autoAcceptanceProfilePath( w.acceptanceDatasetName + "-lvl" + w.samplingLevel );
		w.acceptanceProfile = loadAcceptanceProfile( w.autoAcceptanceProfilePath );
		if ( w.acceptanceProfile != null && w.acceptanceProfile.usable() )
			w.context.log( String.format(
					"  Detection-acceptance profile active (dataset=%s, separation=%.2f) — "
							+ "self-training windows will auto-label tp/fp. %s",
					w.acceptanceDatasetName, w.acceptanceProfile.separation, w.acceptanceProfile.confidenceNote() ) );
		else
			w.context.log( "  No acceptance profile yet for dataset '" + w.acceptanceDatasetName + "' — will "
					+ "auto-learn one from reliable tracks as they accumulate (no manual labelling needed)." );

		// The range the user's two cell counts describe, so ExpectedCellCount can place a timepoint on
		// the curve between them.
		w.context.setSharedData( ExpectedCellCount.SHARED_EXPECTED_RANGE, new int[] { w.startTp, w.endTp } );
		logConfirmedSpotDependentGuards( w.context );
		w.gtStoreUsable = gtStoreMatchesDataset( w.context, w.corrSettings, w.acceptanceDatasetName );
		w.trustedAnnotations = w.gtStoreUsable
				? loadGtAnnotations( w.corrSettings.getGtAnnotationsPath(), w.startTp, w.endTp )
				: new HashMap<>();
		w.trustedTotal = 0;
		for ( final List< double[] > l : w.trustedAnnotations.values() )
			w.trustedTotal += l.size();
		// GT store = hand annotations (typically sparse, near-points → need extent inflation). Reliable-track
		// fallback spots are prior DETECTIONS that already carry a covariance ellipsoid, so they must NOT be
		// inflated (both unnecessary and — over tens of thousands of spots — catastrophically slow).
		w.trustedFromGtStore = w.trustedTotal > 0;
		if ( w.trustedFromGtStore )
		{
			w.context.log( String.format( "  Loaded %d trusted GT annotation spot(s) from the active GT "
					+ "store — used as training labels in windows where present, instead of self-detections.",
					w.trustedTotal ) );
		}
		else
		{
			// "No GT store active" is only true when there is no usable store. A store the user built,
			// scoped to this dataset, whose annotations happen to sit OUTSIDE the range being tracked is
			// a different situation entirely, and reporting it as absent sends them off to rebuild
			// something that is already there. Observed on the run that prompted this: 88 annotations at
			// t=0..1, a run starting at t=160, and a log line saying the store was not active.
			final int[] storeExtent = w.gtStoreUsable
					? gtStoreExtent( w.corrSettings.getGtAnnotationsPath() )
					: null;
			final String gtStoreNote = storeExtent == null
					? "No GT store active"
					: String.format(
							"The active GT store holds %d annotation(s), but at t=%d..%d — none inside the "
									+ "t=%d..%d this run tracks, so it cannot teach this range",
							storeExtent[ 2 ], storeExtent[ 0 ], storeExtent[ 1 ], w.startTp, w.endTp );
			// No GT for this range, whichever of the two reasons above. Pre-existing spots may be a
			// prior run's tracking, so do NOT blindly self-train on
			// all of them (that re-inflates over-detection and reinforces force-connected branches).
			// Trust ONLY spots on unbranched tracks that persist >= RELIABLE_TRACK_MIN_LEN
			// timepoints: an unbranched, long-lived track is very likely a real cell, whereas branchy or
			// short tracks are typically broken/force-connected false detections. This is the topology-based
			// reliable-GT criterion — see the plausible-density check below for why topology alone is not
			// sufficient.
			final Map< Integer, List< double[] > > reliable =
					snapshotReliableSpots( w.context, w.startTp, w.endTp, RELIABLE_TRACK_MIN_LEN );
			int reliableTotal = 0;
			for ( final List< double[] > l : reliable.values() )
				reliableTotal += l.size();
			final int snapTotal = countAllSpots( w.context, w.startTp, w.endTp );
			final double reliablePerTp = reliableTotal / ( double ) Math.max( 1, w.endTp - w.startTp + 1 );
			// Topology alone (unbranched, long-lived) does NOT prove correctness: a deterministic
			// detection ARTIFACT that reproduces identically every frame (e.g. a systematic false-positive
			// pattern near real cells) tracks just as "reliably" as a genuine cell, and this fallback would
			// otherwise trust it as GT — which then bypasses every cold-start/flood/explosion guard below
			// (they only apply when gtInWindow==0) and retrains on it window after window, entrenching the
			// artifact instead of correcting it. Require plausible DENSITY too, using the SAME
			// coldStartCeiling the caller already calibrated for this dataset's expected scale — NOT a
			// hardcoded constant: a confluent culture over a large field of view can genuinely have
			// thousands of real cells per timepoint, so any fixed absolute number here would misfire.
			if ( reliableTotal > 0 && reliablePerTp <= w.requestedColdStartCeiling )
			{
				w.trustedAnnotations.putAll( reliable );
				w.trustedTotal = reliableTotal;
				w.context.log( String.format( "  %s; of %d pre-existing spot(s), trusting %d on "
						+ "unbranched tracks >= %d tp as training GT (branchy/short tracks excluded as likely "
						+ "errors).", gtStoreNote, snapTotal, reliableTotal, RELIABLE_TRACK_MIN_LEN ) );
			}
			else if ( reliableTotal > 0 )
			{
				w.context.log( String.format(
						"  %s; %d pre-existing spot(s) sit on unbranched tracks >= %d tp, but "
								+ "that is %.1f/tp — implausibly high vs. this dataset's own cold_start_spots_per_tp "
								+ "ceiling (%.1f/tp). A long, unbranched track does not guarantee correctness: a "
								+ "stable, self-consistent detection ARTIFACT tracks just as reliably as a genuine "
								+ "cell. Treating none as trusted GT — proceeding with normal (guarded) per-window "
								+ "self-training instead, so cold-start/flood detection can catch and correct it. "
								+ "If this dataset genuinely has this many real cells per timepoint, raise "
								+ "cold_start_spots_per_tp accordingly and re-run.",
						gtStoreNote, reliableTotal, RELIABLE_TRACK_MIN_LEN, reliablePerTp, w.requestedColdStartCeiling ) );
				// Leave trustedAnnotations empty -> gtInWindow==0 -> normal self-training path.
			}
			else if ( snapTotal > 0 )
			{
				w.context.log( String.format( "  %s; none of the %d pre-existing spot(s) lie "
						+ "on an unbranched track >= %d tp either — treating none as trusted GT "
						+ "(self-training on unverified detections drifts quality). Proceeding with normal "
						+ "per-window self-training.", gtStoreNote, snapTotal, RELIABLE_TRACK_MIN_LEN ) );
				// Leave trustedAnnotations empty -> gtInWindow==0 -> normal self-training path.
			}
		}

		// VLM-confirmed tp/fp labelling (see DEFAULT_VLM_CONFIRM_CANDIDATES): mirrors a human triaging
		// detections before training — obvious cases decided cheaply, only genuinely ambiguous ones
		// (either direction) shown to the VLM — instead of trusting the contrast-based acceptanceProfile
		// for the whole window. Built once and reused across all windows; falls back to null
		// (contrast-based labelling) if unavailable.
		w.vlmFpBackend = ( VlmBackend ) w.context.getSharedData( SHARED_VLM_BACKEND );
		w.vlmFpModel = ( String ) w.context.getSharedData( SHARED_VLM_MODEL );
		if ( w.vlmFpBackend == null )
		{
			try
			{
				w.vlmFpBackend = AutonomousTrackingTool.buildVlmBackend( w.corrSettings );
				w.vlmFpModel = w.corrSettings.getVlmModel();
			}
			catch ( final AutonomousTrackingTool.BackendConfigException e )
			{
				w.context.log( "  No VLM backend available for fp confirmation (" + e.getMessage()
						+ ") — falling back to contrast-based fp labelling where a learned acceptance "
						+ "profile is active." );
			}
		}
		w.vlmBackend = w.vlmFpBackend;
		w.vlmModel = w.vlmFpModel;

		// Hand-annotation GT (from a GT store) is typically placed as near-points; training on points teaches
		// ELEPHANT a sub-cell detector that splits one large cell into several, so give each point-like GT
		// spot an image-derived spatial extent first. ONLY for GT-store annotations — reliable-track fallback
		// spots are prior detections that already have a covariance ellipsoid (inflating tens of thousands of
		// them would grind for a very long time and is pointless).
		if ( w.trustedFromGtStore )
		{
			final int inflated = AutoCorrectOrchestrator.inflatePointLabels( w.context, w.trustedAnnotations,
					GT_POINT_SIZE_MAX_WORLD, GT_EXTENT_MIN_RADIUS_WORLD, GT_EXTENT_MAX_RADIUS_WORLD );
			if ( inflated > 0 )
				w.context.log( String.format( "  Estimated an image-derived spatial extent for %d point-like "
						+ "trusted GT spot(s) (prevents training a sub-cell detector that splits large cells).",
						inflated ) );
		}

		// Pretrained detectors first, as the user asked: StarDist, then Cellpose, each tried on a few frames
		// and shown to the user. One that is accepted detects the whole movie as it is, and ELEPHANT's
		// training is not needed; when none is, ELEPHANT's adaptive tracking runs exactly as before. See
		// DetectorTrial.
		w.externalDetector = choosePretrainedDetector( w );
		w.plan = w.externalDetector == null ? WINDOW_PLAN : pretrainedPlan();
		w.context.log( "  Window plan: " + describePlan( w.plan ) );

		// Crop-tuning warm-up: only on a genuinely fresh run (no spots anywhere in the requested range
		// yet) — a resume into an already partially-tracked movie skips this and relies on the
		// already-adapted model. Runs ONCE, before the (unmodified) per-window loop below, so window 1's
		// own "[1/6] Testing detection" full-frame predict starts from a reasonably adapted model instead
		// of random weights flooding the whole frame with noise.
		if ( w.externalDetector == null && countAllSpots( w.context, w.startTp, w.endTp ) == 0 )
		{
			if ( !CleanupToggles.cropTuningEnabled( w.context ) )
				w.context.log( "  Crop-tuning is OFF for this run: skipping the warm-up, and every tuning "
						+ "predict runs on the full frame. Slower, but the tuning phase then measures the "
						+ "same thing the commit does." );
			else if ( reviewApplies( w.context, w.startTp ) )
				// The review (reviewWindow) proofreads every window from its first, against blobs at the frame's size;
				// the warm-up ran the same loop on the same frame first. On CE 01 (repsampled18) t=0 took 10 warm-up
				// rounds and then 2 review rounds; t=150 and t=180 re-ran the warm-up (the model found few in its small
				// tuning region) before the review reached Go in 2 rounds each.
				w.context.log( "  Crop-tuning warm-up: not needed -- the review proofreads each window, this one first, "
						+ "against the blobs at the frame's cell size." );
			else
				warmUpModelOnCrop( w.context, w.elephant, w.viewer, w.startTp, w.endTp, w.physDims, w.nSeedsPerTp,
						w.nCrops, w.nEpochs, w.trainTimeoutMs );
		}
		// The seeds the VLM accounted for become trusted annotations, as a user's would.
		{
			final Object seeds = w.context.getSharedData( SHARED_COLD_START_SEEDS );
			if ( seeds instanceof Map )
			{
				int n = 0;
				for ( final Map.Entry< ?, ? > e : ( ( Map< ?, ? > ) seeds ).entrySet() )
				{
					@SuppressWarnings( "unchecked" )
					final List< double[] > l = ( List< double[] > ) e.getValue();
					w.trustedAnnotations.computeIfAbsent( ( Integer ) e.getKey(), k -> new ArrayList<>() ).addAll( l );
					n += l.size();
				}
				w.trustedTotal += n;
				w.context.setSharedData( SHARED_COLD_START_SEEDS, null );
				if ( n > 0 )
					w.context.log( String.format( "  Trusting the warm-up's %d seed nucle%s (VLM-accounted or a pretrained detector's) as training annotations "
							+ "(the window trains on them, as on a user's, not on its own detections).", n, n == 1 ? "us" : "i" ) );
			}
		}

		// A constraint profile learned from ANOTHER movie silently replaces this one's measured
		// displacement cap and can authorise cutting real division links, so say once, up front, that it
		// is being ignored — the same announcement the GT annotation store already makes.
		LineageConstraintProfile.loadForDataset(
				new AutomahoutSettings().getConstraintsProfilePath(),
				AutoCorrectOrchestrator.currentElephantDataset(),
				reason -> w.context.log( "  " + reason ) );

		// Say it BEFORE the sweep, not only in the summary an hour later: at NN max edges 1 no window
		// can produce a division, so a dividing culture is mis-tracked from the first link onward and
		// every knob the caller might reach for afterwards is the wrong one.
		{
			final String nnWarning = nnMaxEdgesWarning(
					ElephantMainSettingsManager.getInstance().getSelectedStyle().getNNMaxEdges() );
			if ( !nnWarning.isEmpty() )
				w.context.log( " " + nnWarning.trim() );
		}

		w.completedWindows = 0;
		// The tuning regions in force, and the timepoint they were picked at — see
		// TUNING_ROI_REUSE_TIMEPOINTS.
		w.cachedRoiCenters = null;
		w.cachedRoiCentersTp = Integer.MIN_VALUE;
		// Timepoints belonging to a window that actually ran to the end. Abandoned windows contribute
		// nothing: their timepoints keep whatever they held before this run, and every quality figure
		// below is computed only over the windows that completed.
		w.coveredTimepoints = 0;
		// Windows that stopped early but kept a checked detection. They are NOT completed windows — no
		// quality figure below is measured on them — but saying their timepoints were "left exactly as
		// they were" would be false, and the difference matters to whoever decides what to re-run.
		w.windowsKeptDetection = 0;
		w.grandTotalPredict = 0;
		w.grandTotalLink = 0;
		w.grandTotalCorrected = 0;
		w.explodedWindows = 0;
		w.worstContinuity = 1.0;
		w.sumContinuity = 0.0;
		w.nWindowsWithInterior = 0;
		w.totalIsolated = 0;
		w.worstRetention = 1.0;
		w.anyRetentionMeasured = false;
		w.worstCompleteness = 1.0;
		w.lastWindowEnd = w.startTp - 1; // highest wEnd fully processed; drives the resume marker
		// Highest wEnd that finished WITHOUT the pipeline declaring the window collapsed. Distinct from
		// lastWindowEnd, which only records that the window ran to the end: a window can complete and
		// still be garbage (self-training collapse on the commit, a rolled-back refinement round), and a
		// window abandoned mid-way never reaches the end at all. Only a window on this marker is a valid
		// prior for the next one — see prevWindowBaseline.
		w.lastPlausibleWindowEnd = w.startTp - 1;

		windowLoop:
		for ( w.wStart = w.startTp; w.wStart <= w.endTp; w.wStart += w.effectiveWindow )
		{
			if ( w.context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				return String.format(
						"STOPPED after %d/%d windows. predict_ok=%d, link_ok=%d, corrected=%d tp.%s",
						w.completedWindows, w.totalWindows,
						w.grandTotalPredict, w.grandTotalLink, w.grandTotalCorrected,
						w.explodedWindows > 0
								? String.format( " WARNING: refinement self-training collapse was rolled "
										+ "back in %d window(s).", w.explodedWindows )
								: "" );

			w.wEnd = Math.min( w.wStart + w.effectiveWindow - 1, w.endTp );
			w.windowLen = w.wEnd - w.wStart + 1;
			w.allTps = new int[ w.windowLen ];
			for ( int i = 0; i < w.windowLen; i++ ) w.allTps[ i ] = w.wStart + i;

			// Re-read every window rather than fixed for the run: when the guess below is answered by the
			// user (see GuessedCeiling), their count replaces it from the next window on, everywhere.
			w.ceilingOverride = w.context.getSharedData( SHARED_COLD_START_CEILING_OVERRIDE );
			w.ceilingFromUser = w.ceilingOverride instanceof Number;
			w.coldStartCeiling = w.ceilingFromUser
					? ( ( Number ) w.ceilingOverride ).doubleValue() : w.requestedColdStartCeiling;

			w.context.log( String.format( "\n=== Window [t=%d..%d] (%d/%d) ===",
					w.wStart, w.wEnd, w.completedWindows + 1, w.totalWindows ) );

			// Structural invariants for this window (see PipelineInvariants). Every phase below is
			// scoped to t=wStart..wEnd, so a change it makes OUTSIDE that range, or a protected spot it
			// loses, is a bug in that phase — reported here, at the phase that caused it, instead of
			// surfacing much later as an unexplained shortfall in the final track count.
			PhaseTimings.reset( w.context );
			w.preambleMark = PhaseTimings.mark( w.context );
			w.inv = PipelineInvariants.snapshot( w.context );
			// Paired with the [6/6] report below: totals since the run began answer "what does this
			// pipeline do", a window's delta answers "what did THIS window do", which is the question a
			// log gets read to settle.
			w.editsMark = GraphEdits.tally( w.context ).mark();
			// Copy the model BEFORE anything in this window can train it. Everything below — the
			// cold-start bootstrap, the tuning loop, refinement — writes to the server's single copy with
			// no undo, so this is the only thing that makes "abandon the window" mean "undo the window".
			if ( w.externalDetector == null ) // a pretrained detector does not train the server's model
			{
				w.snapshotStart = System.nanoTime();
				w.elephant.snapshotDetectionModel( modelSnapshotFile() );
				PhaseTimings.record( w.context, "download model snapshot", w.snapshotStart );
			}
			// ...and the window's spots, for the same reason. The commit clears every timepoint in the
			// window before re-detecting, so by the time an explosion is thrown the window has already
			// been emptied and partially refilled. Abandoning at that point used to leave it in a WORSE
			// state than it started: observed on t=30..39, where t=30..34 kept fresh unlinked detections,
			// t=35..39 were left empty, and no linking ran at all for the whole window.
			w.spotSnapshotStart = System.nanoTime();
			w.windowSpotsBefore = snapshotSpots( w.context, w.wStart, w.wEnd );
			PhaseTimings.record( w.context, "snapshot window spots", w.spotSnapshotStart );
			// The last state of this window that passed its own invariant check, and what a later phase
			// is put back to when IT fails. Until the detection is committed and checked there is no such
			// state, so it starts as "before the window ran" and the behaviour is unchanged: a window
			// whose DETECTION is at fault still reverts completely. Once the detection has been checked,
			// a cleanup phase failing no longer costs the user the detection — a floor violation says the
			// cleanup pass is broken, not that the detector is, and four windows of one run were emptied
			// on shortfalls of one and two cells.
			w.lastChecked = w.windowSpotsBefore;
			w.detectionChecked = false;
			// Every "the pipeline declared this window collapsed" path bumps explodedWindows, so comparing
			// it against this snapshot at the end of the window covers all of them at once — rather than
			// setting a flag at each of the sites that can declare a collapse, which is exactly the
			// per-site bookkeeping that keeps going out of sync in this class.
			w.explodedWindowsAtWindowStart = w.explodedWindows;
			// Linking creates edges only, so ANY change in spot count during [4/6] is worth reporting.
			w.linkInvariants = PipelineInvariants.limits()
					.maxAddedFraction( 0.0 )
					.maxDeletedFraction( 0.0 );

			// Review and proofread before anything trains here: the reference cells are the blobs at the size the frames
			// read now, and the model is trained against them until it reproduces them (or its best round is kept).
			if ( w.externalDetector == null )
				reviewWindow( w );

			// The window's stages, in plan order (WINDOW_PLAN, or pretrainedPlan when one was chosen). A stage that gives the window up has
			// already put it back as its own check required; one that ends the run has said why.
			for ( final WindowStage stage : w.plan )
			{
				final Next next = stage.run( w );
				if ( next == Next.ABANDON )
					continue windowLoop;
				if ( next == Next.STOP )
					return w.stopMessage;
			}

			// Step 6: Validate — current window + boundary with previous window
			w.finalMetrics = computeQualityMetrics( w.context, w.allTps );
			w.linkDensity = computeLinkDensity( w.context, w.wStart, w.wEnd );
			w.cont = computeContinuity( w.context, w.wStart, w.wEnd );
			w.continuity = w.cont[ 0 ];
			w.meanTrackLen = w.cont[ 1 ];
			w.isolatedSpots = ( int ) w.cont[ 2 ];
			// Recall signal: how much of the raw model's detections survived training + correction.
			// A large drop means training taught the model to miss cells the raw model had found.
			// NaN when there is nothing to compare against: the GT path logs "Skipping crop test
			// detection — trusted GT annotations drive training below" and leaves initialSpotsPerTp at 0.
			// This used to fall back to 1.0 and report "retention=100% of initial 0.0" — a perfect recall
			// score for a window where recall was never measured. It is not cosmetic: the value reaches
			// the agent as "worst detection retention=100% of initial", and the agent quoted it back as
			// evidence of "a dramatic improvement in structure and linking quality".
			w.retention = w.initialSpotsPerTp > 0
					? w.finalMetrics[ 0 ] / w.initialSpotsPerTp : Double.NaN;
			w.retentionReport = Double.isNaN( w.retention )
					? "retention=n/a (no pre-training baseline)"
					: String.format( "retention=%.0f%% of initial %.1f", w.retention * 100, w.initialSpotsPerTp );
			// Continuity is the primary tracking-quality signal. link_density is reported only as
			// a secondary count, since a single stray link inflates it to 100% (see computeContinuity).
			// isolated + retention are RECALL signals; continuity alone is blind to missed cells.
			w.context.log( String.format(
					"  [6/6] Validation: continuity=%s (interior spots with both in+out links), "
							+ "mean_track_len=%.1f, isolated=%d, spots/tp=%.1f (%s), CV_temporal=%.2f, "
							+ "link_density=%.0f%% (%d/%d tp linked)",
					continuityDefined( w.windowLen ) ? String.format( "%.0f%%", w.continuity * 100 )
							: "n/a (a " + w.windowLen + "-tp window has no interior timepoint)",
					w.meanTrackLen, w.isolatedSpots,
					w.finalMetrics[ 0 ], w.retentionReport, w.finalMetrics[ 1 ],
					w.linkDensity * 100, w.linkOk, w.windowLen ) );

			// Reported next to the validation figures, which are the numbers this contradicts or
			// corroborates: a window can validate at 86% continuity and still have deleted the brightest
			// cell in the frame, and only this line says so.
			BrightSpotAudit.report( w.context );

			// What each kind of edit accounted for in THIS window. The validation line above says where
			// the window landed; this says how it got there — which pass-kind spent the spots, and
			// whether a guard stopped anything. Without it the only way to attribute a drop was to
			// switch passes off one at a time and compare runs (§2.5 of the architecture notes).
			w.edits = GraphEdits.tally( w.context ).since( w.editsMark );
			if ( !w.edits.isEmpty() )
				w.context.log( "  [6/6] Graph edits: " + w.edits );

			// Only windows that have interior timepoints: a short last window (HeLa's t=90..91) scored 0%,
			// reported "worst=0% ... re-run adaptive_track", and the agent re-tracked the whole movie.
			if ( continuityDefined( w.windowLen ) )
				w.worstContinuity = Math.min( w.worstContinuity, w.continuity );
			w.totalIsolated += w.isolatedSpots;
			if ( !Double.isNaN( w.retention ) )
			{
				w.worstRetention = Math.min( w.worstRetention, w.retention );
				w.anyRetentionMeasured = true;
			}
			// Detection completeness (recall vs the image's cell-scale intensity maxima). This is the
			// signal that stops the pipeline from proceeding when cells are simply not detected.
			if ( w.completenessTarget > 0 )
			{
				final double completeness = computeDetectionCompleteness( w.context, w.viewer, w.wStart, w.wEnd );
				w.worstCompleteness = Math.min( w.worstCompleteness, completeness );
				// The expected-cell estimate comes from image intensity maxima, which OVER-count on
				// low-contrast / phase-halo / out-of-focus / anisotropic data (each halo or Z-ghost
				// spawns a maximum). So a low completeness is only trustworthy when tracks are ALSO
				// fragmented; when continuity is good the cells are being found and tracked, and a low
				// completeness here is a measurement artefact — advisory only, NOT a reason to re-run.
				final boolean continuityGood = continuityDefined( w.windowLen ) && w.continuity >= CONTINUITY_GOOD;
				w.context.log( String.format(
						"  [6/6] Detection completeness: %.0f%% (detected vs estimated cell count).%s",
						completeness * 100,
						completeness >= w.completenessTarget
								? ""
								: continuityGood
										? " (advisory: the estimate over-counts on low-contrast/halo/"
												+ "anisotropic data; tracks are continuous, so this is not a "
												+ "reason to re-run)"
										: String.format( " WARNING: INCOMPLETE (< %.0f%% target) — cells are "
												+ "being missed; do NOT declare done. Re-run adaptive_track "
												+ "(higher refine_iterations, lower min_intensity_ratio) or "
												+ "add annotations.", w.completenessTarget * 100 ) ) );
			}
			// A window shorter than 3 tp has no interior timepoints, so its continuity is
			// undefined (0) and must not drag the aggregate down.
			if ( continuityDefined( w.windowLen ) )
			{
				w.sumContinuity += w.continuity;
				w.nWindowsWithInterior++;
			}

			if ( w.finalMetrics[ 0 ] < w.minSpotsPerTp * 0.5 )
				w.context.log( "  WARNING: Very low spot count after full pipeline — "
						+ "possible detection failure in this window." );
			if ( continuityDefined( w.windowLen ) && w.continuity < CONTINUITY_GOOD )
				w.context.log( String.format(
						"  WARNING: Low continuity (%.0f%% < %.0f%%) — tracks are fragmented "
								+ "(cells appearing/disappearing mid-window). Re-run adaptive_track on "
								+ "t=%d..%d with higher n_iterations.",
						w.continuity * 100, CONTINUITY_GOOD * 100, w.wStart, w.wEnd ) );
			// Precision (noise) signal — the counterweight to the recall signal below. Size filtering
			// is OFF by default (min_size=0) to protect small/dim real cells; a wide size spread with
			// high count may indicate background-noise specks mixed in.
			w.noiseSuspected = w.minSize == 0.0 && w.finalMetrics[ 3 ] > CV_SIZE_GOOD
					&& w.finalMetrics[ 0 ] > 2.0 * w.minSpotsPerTp;
			if ( w.noiseSuspected )
				w.context.log( String.format(
						"  NOTE: wide size spread (CV_size=%.2f, mean=%.1f) at %.0f spots/tp with size "
								+ "filtering OFF — if the visual shows background-noise specks, set a "
								+ "positive min_size (e.g. ~%.1f) or min_size<0 (AUTO); do NOT lower "
								+ "min_intensity_ratio (that adds more noise).",
						w.finalMetrics[ 3 ], w.finalMetrics[ 2 ], w.finalMetrics[ 0 ], 0.6 * w.finalMetrics[ 2 ] ) );
			// NaN (no pre-training baseline) compares false here, which is the intent: a window whose
			// recall was never measured must not raise a recall warning either way.
			if ( w.retention < 0.85 )
				w.context.log( String.format(
						"  WARNING: Detection recall dropped to %.0f%% of the initial %.1f spots/tp — "
								+ "training/filtering discarded detections. ONLY if the visual shows clear "
								+ "cell bodies WITHOUT ellipsoids, lower min_intensity_ratio to recover "
								+ "them%s.",
						w.retention * 100, w.initialSpotsPerTp,
						w.noiseSuspected
								? " — but NOT while background noise is suspected (raise min_size first, "
										+ "the missing 'cells' may be filtered noise, not real cells)"
								: "" ) );
			if ( w.isolatedSpots > 0 )
				w.context.log( String.format(
						"  NOTE: %d isolated spot(s) (no links at all) — likely missed neighbours or "
								+ "false positives at the window edges.", w.isolatedSpots ) );
			if ( w.linkDensity < 0.5 && w.wStart > w.startTp )
				w.context.log( "  WARNING: Low link density (<50%) — tracking may be fragmented." );
			if ( w.wStart > w.startTp )
			{
				// Boundary consistency: compare spot count at boundary timepoints
				final int prevCount = countCellsAtTimepoints( w.context, new int[] { w.wStart - 1 } );
				final int currCount = countCellsAtTimepoints( w.context, new int[] { w.wStart } );
				if ( prevCount > 0 )
				{
					final double ratio = ( double ) currCount / prevCount;
					if ( ratio < 0.5 || ratio > 2.0 )
						w.context.log( String.format(
								"  WARNING: Boundary inconsistency — t=%d: %d spots, t=%d: %d spots "
										+ "(%.0f%% change across window boundary).",
								w.wStart - 1, prevCount, w.wStart, currCount,
								Math.abs( ratio - 1.0 ) * 100 ) );
				}
			}
			if ( w.finalMetrics[ 1 ] > CV_TEMPORAL_GOOD || w.finalMetrics[ 3 ] > CV_SIZE_GOOD )
				w.context.log( String.format(
						"  NOTE: CV_temporal=%.2f or CV_size=%.2f still above threshold — "
								+ "consider re-running adaptive_track on t=%d..%d with higher n_iterations.",
						w.finalMetrics[ 1 ], w.finalMetrics[ 3 ], w.wStart, w.wEnd ) );

			// Auto-learn (or refresh) the per-dataset acceptance profile from data intrinsic to THIS run —
			// no manual Filter-by-Example labelling needed. TP signal = spots on long, unbranched tracks
			// (trustworthy without human input, same criterion as the GT fallback above); FP signal = local
			// contrast sampled at random empty positions far from every current detection. Re-learned after
			// every window so it keeps improving (more reliable tracks) and stays current (contrast can
			// drift over a long movie, e.g. photobleaching).
			{
				final Map< Integer, List< double[] > > reliableForProfile =
						snapshotReliableSpots( w.context, w.startTp, w.wEnd, RELIABLE_TRACK_MIN_LEN );
				int reliableCountForProfile = 0;
				for ( final List< double[] > l : reliableForProfile.values() )
					reliableCountForProfile += l.size();
				if ( reliableCountForProfile > 0 )
				{
					final int[] tpsForProfile = allTimepointsArray( w.startTp, w.wEnd );
					final double meanSizeForProfile = computeQualityMetrics( w.context, tpsForProfile )[ 2 ];
					final double exclusionRadius =
							Math.max( 1.0, meanSizeForProfile * AUTO_FP_EXCLUSION_RADIUS_FACTOR );
					final org.mastodon.mamut.automahout.eval.DetectionAcceptanceProfile learned =
							AutoCorrectOrchestrator.learnAcceptanceProfileAuto( w.context, reliableForProfile,
									tpsForProfile, w.physDims, AUTO_FP_SAMPLES_PER_TP, exclusionRadius );
					if ( learned.usable() )
					{
						try
						{
							learned.sourcePath = "auto (dataset=" + w.acceptanceDatasetName + "), t="
									+ w.startTp + ".." + w.wEnd;
							learned.save( w.autoAcceptanceProfilePath );
							w.acceptanceProfile = learned;
							w.context.log( String.format(
									"  Auto-learned acceptance profile from %d reliable track spot(s) "
											+ "(dataset=%s, separation=%.2f). %s",
									reliableCountForProfile, w.acceptanceDatasetName, learned.separation,
									learned.confidenceNote() ) );
						}
						catch ( final Exception ex )
						{
							w.context.log( "  WARNING: failed to save auto-learned acceptance profile: "
									+ ex.getMessage() );
						}
					}
				}
			}

			fineTuneOnFinishedWindow( w );
			{
				// The whole window, itemised — the per-timepoint and per-pass lines above cover detection
				// and cleanup, and this is the only place training, linking and correction appear.
				final String windowTiming = PhaseTimings.summary( w.context );
				if ( !windowTiming.isEmpty() )
					w.context.log( "  Window total." + windowTiming.substring(
							windowTiming.indexOf( "Time:" ) - 1 ) );
			}
			w.completedWindows++;
			w.coveredTimepoints += w.windowLen;
			// Persist forward progress so an interrupted sweep — including one killed by a crash before the
			// project is saved — can resume from the next window. Survives even a second pass over an
			// already-fully-tracked movie, where spot presence alone cannot reveal how far we got.
			w.lastWindowEnd = w.wEnd;
			if ( w.explodedWindows == w.explodedWindowsAtWindowStart )
				w.lastPlausibleWindowEnd = w.wEnd;
			else
				// The window ran to the end but the pipeline declared it collapsed (self-training collapse
				// on the commit, or a rolled-back refinement round). Its SPOTS are kept — they are the best
				// this window managed — but the model that produced them must not calibrate the next
				// window. Same reasoning as the abandon path above.
				w.elephant.restoreDetectionModel( modelSnapshotFile() );
			AutomahoutSettings.persistAdaptiveResume( w.wEnd + 1, w.numTimepoints,
					AutoCorrectOrchestrator.currentElephantDataset() );
		}

		// The ceiling above is scoped to one window of one sweep. Shared data outlives the call, and a
		// single-window or cold-start caller running afterwards would otherwise judge itself against
		// whatever the last window of the last sweep happened to measure.
		w.context.setSharedData( SHARED_WINDOW_EXPLOSION_CEILING, null );
		w.context.setSharedData( SHARED_WINDOW_CALIBRATED_CEILING, null );

		// Clear the marker only on a genuinely COMPLETE sweep (reached endTp). If the loop stopped early
		// (cancel, interrupt, or an aborted window), leave the marker so the next run resumes there.
		if ( w.lastWindowEnd >= w.endTp )
			AutomahoutSettings.clearAdaptiveResume();

		w.meanContinuity = w.nWindowsWithInterior > 0
				? w.sumContinuity / w.nWindowsWithInterior : 0.0;
		// Coverage has to be stated alongside the metrics, not left to be inferred from the window count:
		// continuity, recall and completeness are all aggregated over COMPLETED windows only, so a run
		// that abandons most of the movie reports the quality of the fraction that worked — and looks
		// better for it. Observed: a run that processed 2 of 8 windows reported "Tracks are continuous"
		// and "Detection looks complete", and the agent reading it concluded, and told the user, that it
		// had "processed all 74 timepoints".
		w.gt = ( int[] ) w.context.getSharedData( SHARED_FLAT_CONTRAST_GROUND_TRUTH );
		if ( w.gt != null && w.gt[ 1 ] > 0 )
			w.context.log( String.format(
					"  Flat-contrast vs YOUR confirmed spots: it called %d of %d (%.0f%%) background. %s",
					w.gt[ 0 ], w.gt[ 1 ], 100.0 * w.gt[ 0 ] / w.gt[ 1 ],
					100.0 * w.gt[ 0 ] / w.gt[ 1 ] > FLAT_CONTRAST_MAX_TOLERABLE_FP_RATE
							? "Those are known cells, so that is the check's own false-positive rate — it is "
									+ "deleting real cells and the cell-count floor is all that is stopping it."
							: "It clears the cells you confirmed, so the detections it flags are not simply "
									+ "dim cells." ) );
		w.auditSummary = AutoCorrectOrchestrator.auditSummary(
				( int[] ) w.context.getSharedData( SHARED_FLAT_CONTRAST_AUDIT_TALLY ) );
		if ( w.auditSummary != null )
			w.context.log( w.auditSummary );
		w.requestedTimepoints = w.endTp - w.startTp + 1;
		w.fullCoverage = w.coveredTimepoints >= w.requestedTimepoints;
		w.unfinishedWindows = w.totalWindows - w.completedWindows;
		w.coverageReport = w.fullCoverage
				? ""
				: String.format(
						" COVERAGE: only %d of %d requested timepoint(s) were processed; %d window(s) did "
								+ "not finish%s. Every quality figure below is measured ONLY on the processed "
								+ "timepoints and says nothing about the rest — do NOT read it as a verdict "
								+ "on the movie, and do NOT declare done. Re-run adaptive_track over the "
								+ "unprocessed range.",
						w.coveredTimepoints, w.requestedTimepoints, w.unfinishedWindows,
						w.windowsKeptDetection > 0
								? String.format( " — %d of them kept and re-linked the last state that passed "
										+ "its own checks (so those timepoints DO hold detections, they are just "
										+ "not finished)%s", w.windowsKeptDetection,
										w.unfinishedWindows > w.windowsKeptDetection
												? String.format( ", and %d were rolled back to exactly what they "
														+ "held before", w.unfinishedWindows - w.windowsKeptDetection )
												: "" )
								: " and their timepoints were left exactly as they were" );
		w.contReport = w.nWindowsWithInterior > 0
				? String.format( " continuity: mean=%.0f%%, worst=%.0f%% (threshold=%.0f%%).%s",
						w.meanContinuity * 100, w.worstContinuity * 100, CONTINUITY_GOOD * 100,
						w.worstContinuity < CONTINUITY_GOOD
								? " Some windows are FRAGMENTED — tracking is NOT yet good enough; "
										+ "re-run adaptive_track (higher n_iterations, lower "
										+ "min_intensity_ratio) before declaring done."
								: scopedToCoverage( " Tracks are continuous.", w.coveredTimepoints,
										w.endTp - w.startTp + 1 ) )
				: " continuity: n/a (windows too short to measure interior continuity).";
		// Recall summary — continuity says nothing about MISSED cells, so report it separately.
		// A run in which no window ever measured retention must not report a recall verdict at all — see
		// the retention NaN above. isolated_spots is measured either way and still carries the caveat.
		w.recallSuspect = w.anyRetentionMeasured && w.worstRetention < 0.85 || w.totalIsolated > 0;
		w.recallReport = String.format(
				" recall: worst detection retention=%s, isolated_spots=%d.%s",
				w.anyRetentionMeasured
						? String.format( "%.0f%% of initial", w.worstRetention * 100 )
						: "n/a (no pre-training baseline in any window)",
				w.totalIsolated,
				w.recallSuspect
						? " Cells may be MISSING (low retention / isolated spots) — continuity being "
								+ "high does NOT mean all cells were found; consider lowering "
								+ "min_intensity_ratio and re-running before declaring done."
						: " Detection recall looks stable." );
		// Completeness is advisory when tracks are continuous: the expected-cell estimate (intensity
		// maxima) over-counts on low-contrast/halo/anisotropic data, so a low value with good continuity
		// is a measurement artefact — re-running on it only self-trains on prior detections and drifts.
		w.tracksContinuous = w.nWindowsWithInterior > 0 && w.worstContinuity >= CONTINUITY_GOOD;
		w.completenessReport = w.completenessTarget > 0
				? String.format( " completeness: worst=%.0f%% (target=%.0f%%).%s",
						w.worstCompleteness * 100, w.completenessTarget * 100,
						w.worstCompleteness >= w.completenessTarget
								? scopedToCoverage( " Detection looks complete.", w.coveredTimepoints,
										w.requestedTimepoints )
								: w.tracksContinuous
										? " ADVISORY ONLY: completeness = detected ÷ an intensity-maxima "
												+ "estimate of cell count, which OVER-counts on low-contrast / "
												+ "halo / out-of-focus / anisotropic data, so a low value here "
												+ "is unreliable. Tracks are continuous, so do NOT re-run on "
												+ "completeness alone — re-run only if the BDV image clearly "
												+ "shows real cell bodies without ellipsoids."
										: " DETECTION INCOMPLETE — cells are being missed. Do NOT declare done; "
												+ "re-run adaptive_track (higher refine_iterations, lower "
												+ "min_intensity_ratio) or add manual annotations." )
				: "";
		w.explodedReport = w.explodedWindows > 0
				? String.format( " WARNING: refinement self-training collapsed into over-detection "
						+ "(rolled back) in %d window(s) — this dataset is pushing refine_iterations/"
						+ "n_epochs past what its detection quality can sustain; consider lowering them "
						+ "or setting refine_pass=false.", w.explodedWindows )
				: "";
		// Divisions — nothing above measures them. Continuity is division-neutral by design, so without
		// this line a run can report continuous tracks while every mitosis in the movie is missing, and
		// the growth that mitosis causes reads as a false-positive flood. See divisionReport.
		w.topo = AutoCorrectOrchestrator.computeTopologyMetrics( w.context );
		// The growth yardstick is only meaningful end to end: with windows abandoned, the last
		// timepoint's count is whatever was there before the run, not a population size. Pass 0 and the
		// report drops the comparison rather than drawing a conclusion from a number it does not have.
		w.firstTpSpots = w.fullCoverage
				? AutoCorrectOrchestrator.countCellsAt( w.context, w.startTp ) : 0;
		w.lastTpSpots = w.fullCoverage
				? AutoCorrectOrchestrator.countCellsAt( w.context, w.endTp ) : 0;
		w.divisionReport = nnMaxEdgesWarning(
				ElephantMainSettingsManager.getInstance().getSelectedStyle().getNNMaxEdges() )
				+ divisionReport( w.topo[ 0 ], w.topo[ 1 ],
						AutoCorrectOrchestrator.countTrifurcations( w.context ),
						w.firstTpSpots, w.lastTpSpots );

		return String.format(
				"Adaptive tracking complete. %d/%d windows processed. "
						+ "predict_ok=%d, link_ok=%d, corrected=%d tp.%s%s%s%s%s%s",
				w.completedWindows, w.totalWindows,
				w.grandTotalPredict, w.grandTotalLink, w.grandTotalCorrected,
				w.coverageReport, w.contReport, w.recallReport, w.completenessReport, w.explodedReport,
				w.divisionReport );
	}

	/**
	 * Below this fraction of the divisions the population's own growth requires, the lineage is being
	 * under-branched: the daughters exist as spots but are not linked to their mother.
	 */
	static final double DIVISION_RECALL_FLOOR = 0.5;

	/**
	 * Above this multiple of the divisions the growth requires, most branchings are linking errors
	 * rather than mitoses — a population cannot divide more often than it grows (net of death).
	 */
	static final double DIVISION_EXCESS_CEILING = 2.0;

	/**
	 * What adaptive_track says about DIVISIONS, which none of its other figures can see.
	 *
	 * <p>Continuity is deliberately division-neutral — a mother with two out-links and a daughter with
	 * one in-link both score as continuous — so a run can report 89% continuity while every mitosis in
	 * the movie is missing. On Fluo-N2DL-HeLA_01 that blindness cost three runs: the population grew
	 * 44 to 150 cells/frame, nothing reported a division, and the agent read the growth as a
	 * false-positive flood and spent every attempt moving detection thresholds.</p>
	 *
	 * <p>The yardstick is arithmetic, not a model: in a closed population each division adds exactly one
	 * cell, so going from {@code firstTpSpots} to {@code lastTpSpots} takes about their difference in
	 * division events. Deaths and cells leaving the field make the true number higher, never lower, so
	 * the count is a FLOOR and only a shortfall against it is evidence.</p>
	 *
	 * @param divisions     spots with >= 2 outgoing edges
	 * @param minInterval   fewest timepoints between a division and its nearest ancestor division,
	 *                      {@link Integer#MAX_VALUE} when no division has one
	 * @param trifurcations spots with >= 3 outgoing edges, which are always linking errors
	 * @param firstTpSpots  spots at the first timepoint of the measured range, or 0 when not measurable
	 * @param lastTpSpots   spots at the last timepoint of the measured range, or 0 when not measurable
	 */
	static String divisionReport( final int divisions, final int minInterval, final int trifurcations,
			final int firstTpSpots, final int lastTpSpots )
	{
		final StringBuilder sb = new StringBuilder();
		sb.append( String.format( " divisions=%d", divisions ) );
		if ( divisions > 0 && minInterval != Integer.MAX_VALUE )
			sb.append( String.format( ", closest two on one lineage %d tp apart", minInterval ) );
		sb.append( "." );

		final int growth = firstTpSpots > 0 && lastTpSpots > 0 ? lastTpSpots - firstTpSpots : 0;
		if ( growth > 0 )
		{
			sb.append( String.format(
					" The population went %d -> %d cells across the measured range, which takes at least"
							+ " %d division events.",
					firstTpSpots, lastTpSpots, growth ) );
			if ( divisions < DIVISION_RECALL_FLOOR * growth )
				sb.append( " DIVISIONS ARE BEING MISSED — the count is far below that. This is NOT a"
						+ " detection problem and lowering min_intensity_ratio will not fix it: the"
						+ " daughters are already detected (that is what made the count grow), they are"
						+ " just not linked to their mother, and continuity cannot see that because a"
						+ " daughter whose track starts on its own still has an out-link. Check that"
						+ " ELEPHANT's 'NN max edges' is at least 2, then run auto.correct with"
						+ " division_link_factor (it is 0.0 = OFF unless you pass it)." );
			else if ( divisions > DIVISION_EXCESS_CEILING * growth )
				sb.append( " That is far more branchings than the growth accounts for, so most of them"
						+ " are linking errors rather than mitoses — raise long_link_factor scrutiny or"
						+ " let auto.correct's rapid-successive-division pass run." );
			else
				sb.append( " The divisions track that growth, so the rising cell count is PROLIFERATION,"
						+ " not over-detection — do not treat it as a false-positive flood." );
		}
		else if ( divisions == 0 )
			sb.append( " No division was recorded. If this culture divides, the lineage is being"
					+ " tracked as unbranched single cells." );

		if ( trifurcations > 0 )
			sb.append( String.format( " WARNING: %d spot(s) have >= 3 daughters — division is binary,"
					+ " so these are linking errors.", trifurcations ) );
		return sb.toString();
	}

	/**
	 * ELEPHANT's nearest-neighbour linker gives one spot at most {@code NN max edges} successors, so at
	 * 1 a mitosis cannot be represented at all — no amount of detection tuning or division-linking
	 * repair can produce a branch the linker is forbidden to make. It is a persisted ELEPHANT style
	 * setting this project never writes, so it carries in from whatever a previous session left, and
	 * nothing downstream would name it as the cause.
	 *
	 * @param maxEdges the configured {@code NNMaxEdges}
	 * @return the warning to surface, or an empty string when the setting permits division
	 */
	static String nnMaxEdgesWarning( final int maxEdges )
	{
		return maxEdges >= 2
				? ""
				: String.format(
						" WARNING: ELEPHANT's 'NN max edges' is %d, so the linker may give a cell at most"
								+ " %d successor and DIVISION CANNOT BE REPRESENTED. Every division"
								+ " figure below is 0 by construction, not by measurement. Raise it to 2"
								+ " in ELEPHANT's main settings before reading anything into these"
								+ " numbers.",
						maxEdges, maxEdges );
	}

	/**
	 * Why the saved adaptive-tracking progress marker must not be used, or {@code null} when it may be.
	 *
	 * <p>A marker claims "everything below me has been processed". It is written to preferences that
	 * live in one node per USER, not per project, and it outlives the graph it describes, so that claim
	 * has to be checked against the project actually open rather than taken at face value.</p>
	 *
	 * <p>Observed: a marker left at t=10 by an interrupted run sent the next run — over a project
	 * reporting {@code total_spots=0} — straight to t=10, and t=0..9 were never tracked at all. The
	 * spot-presence heuristic above had already answered "nothing is tracked here"; this branch treated
	 * that identically to "the whole range is tracked", which is the one other way it can be reached.</p>
	 *
	 * @param marker         the saved next timepoint
	 * @param markerNtp      movie length the marker was recorded for
	 * @param markerDataset  ELEPHANT dataset the marker was recorded for, {@code ""} if it records none
	 * @param minTp          first timepoint of the requested range
	 * @param endTp          last timepoint of the requested range
	 * @param numTimepoints  length of the movie now open
	 * @param currentDataset ELEPHANT dataset now open
	 * @param startFresh     whether the user asked to start fresh
	 * @param lastDone       highest timepoint that already holds spots, or {@code -1} for none
	 * @return {@code null} to use the marker, {@code ""} when there is no applicable marker to speak of,
	 *         or a reason to report
	 */
	static String resumeMarkerRefusal( final int marker, final int markerNtp, final String markerDataset,
			final int minTp, final int endTp, final int numTimepoints, final String currentDataset,
			final boolean startFresh, final int lastDone )
	{
		if ( marker <= minTp || marker > endTp || markerNtp != numTimepoints )
			return ""; // no marker, or one that does not describe this range at all
		if ( startFresh )
			return ""; // already announced; "start fresh" is an instruction about where to BEGIN
		if ( lastDone < 0 )
			return "it claims everything below it is done, but this project has no spots";
		if ( markerDataset == null || markerDataset.isEmpty() )
			return "it does not record which dataset it was written for";
		if ( !markerDataset.equals( currentDataset ) )
			return "it was written for '" + markerDataset + "', not '" + currentDataset + "'";
		return null;
	}

	/**
	 * The explosion ceiling calibrated on RECENT WINDOWS, or {@code 0} when none has been measured yet.
	 *
	 * <p>Distinct from the value published under {@link #SHARED_WINDOW_EXPLOSION_CEILING}, which falls
	 * back to the run-wide stated bound when there is no measurement. Here the absence of a measurement
	 * has to stay visible, so that a per-timepoint bound taken from the user's own numbers is not
	 * widened back out to the whole movie's peak by a value that was never measured.</p>
	 */
	private static double prevWindowCalibrated( final ToolContext context )
	{
		final Object v = context.getSharedData( SHARED_WINDOW_CALIBRATED_CEILING );
		return v instanceof Number && ( ( Number ) v ).doubleValue() > 0.0
				? ( ( Number ) v ).doubleValue() : 0.0;
	}

	/**
	 * Whether a training round has started amplifying rather than recovering: its raw detection count
	 * is more than {@link #MAX_TUNING_ROUND_GROWTH} times what the model produced before the round.
	 *
	 * <p>Judged on RAW detections, not on what survives cleanup: the cleanup passes are not applied
	 * uniformly across timepoints, so their output is not comparable round to round.</p>
	 *
	 * <p>Asked TWICE per round — once of the first timepoint, before the rest of the window is
	 * re-detected, and once of the rest — so that the same question decides both, and a round condemned
	 * by its first frame does not go on to produce nine more frames of detections nothing will use.</p>
	 *
	 * @param trained            false when the round did not train, in which case it has nothing to answer for
	 * @param preRoundRawPerTp   raw spots/tp the model produced before this round, or {@code <= 0} when unknown
	 * @param observedRawPerTp   raw spots/tp this round has produced so far
	 */
	static boolean tuningRoundIsFlooding( final boolean trained, final double preRoundRawPerTp,
			final double observedRawPerTp )
	{
		return trained && preRoundRawPerTp > 0
				&& observedRawPerTp > preRoundRawPerTp * MAX_TUNING_ROUND_GROWTH;
	}

	/**
	 * Undoes a tuning round that came back over-detecting: restore the model this round started from,
	 * drop the spots it produced, and say what is known about why.
	 *
	 * <p>The spots on screen came from the diverged model, so they are not a usable starting point
	 * either; the window's mandatory full-frame commit re-detects from scratch with the restored
	 * model.</p>
	 *
	 * <p>The message names the synthetic-negative shortfall when there was one. It is not a proof of
	 * cause, and it does not claim to be — but a round trained on 830 positives and 17 negatives against
	 * a stated 10% target is a precondition this pipeline set for itself and did not meet, and it is the
	 * one thing in the log a reader can act on. Saying "self-training has started amplifying" and
	 * nothing else sent three consecutive runs to the detection thresholds instead.</p>
	 */
	private static void discardTuningRound( final ToolContext context, final ElephantIntegration elephant,
			final int[] allTps, final int trainIter, final double preRoundRawPerTp,
			final double roundRawPerTp )
	{
		final Object fpFraction = context.getSharedData( SHARED_LAST_FP_NEGATIVE_FRACTION );
		final String negativeNote = fpFraction instanceof Number
				&& ( ( Number ) fpFraction ).doubleValue() < SYNTHETIC_FP_TARGET_FRACTION / 2.0
						? String.format(
								" This round was trained on %.1f%% background negatives against a target of"
										+ " %.0f%%, so it was shown far more of what a cell looks like than of"
										+ " what one does not; that is the condition under which this failure"
										+ " is expected, and it is visible above before the round runs.",
								( ( Number ) fpFraction ).doubleValue() * 100,
								SYNTHETIC_FP_TARGET_FRACTION * 100 )
						: "";
		context.log( String.format(
				"  [3/6] Round %d DISCARDED: raw detection grew %.1f -> %.1f spots/tp (%.1fx, over the "
						+ "%.1fx limit). Self-training on its own detections has started amplifying rather "
						+ "than recovering — reverting to the model from before this round and ending the "
						+ "tuning loop, so this window commits with an UNADAPTED model.%s",
				trainIter, preRoundRawPerTp, roundRawPerTp,
				roundRawPerTp / Math.max( 1e-9, preRoundRawPerTp ), MAX_TUNING_ROUND_GROWTH,
				negativeNote ) );
		elephant.restoreDetectionModel( roundModelSnapshotFile() );
		deleteAllSpotsAtTimepoints( context, allTps );
	}

	/**
	 * Qualifies a positive quality verdict that was measured on only part of the requested range.
	 * "Tracks are continuous" is a claim about the movie; when most of the movie was never processed it
	 * is a claim about the fraction that was, and has to say so in the same sentence rather than relying
	 * on the reader to combine it with a window count printed elsewhere.
	 */
	static String scopedToCoverage( final String verdict, final int covered, final int requested )
	{
		return covered >= requested
				? verdict
				: verdict.replace( ".",
						String.format( " WHERE PROCESSED (%d/%d tp).", covered, requested ) );
	}

	/**
	 * Focused single-window tracking, for building a reliable baseline before scaling to the whole
	 * movie: train the detection model on the spots CURRENTLY in the window, detect the WHOLE image at
	 * each timepoint, deduplicate, and link — then stop for human review. Deliberately omits the
	 * mechanisms that destabilise the full pipeline: the per-spot local re-prediction
	 * ({@code recoverMissingPredecessors}/{@code fillGaps}), the refinement self-training loop, and the
	 * cold-start flood bootstrap.
	 *
	 * <p>Each call trains on whatever the user has curated in the window: the ground-truth annotations
	 * on the first pass, and — after the user removes false detections (e.g. via the example filter) —
	 * the cleaned detections on later passes. This human-in-the-loop curation is how the user's
	 * corrections feed back into training. (Filter out false detections BEFORE retraining, or they will
	 * be learned as positives.)</p>
	 *
	 * @return a human-readable one-line report of the pass
	 */
	public static String trackWindowGtGuided( final ToolContext context, final ViewerPanel viewer,
			final int startTp, final int endTp,
			final int nEpochs, final int nCrops, final long trainTimeoutMs,
			final double dedupFactor, final double minSize, final double trackIntensityRatio,
			final double qualityThreshold )
	{
		final int windowLen = endTp - startTp + 1;
		final int[] allTps = new int[ windowLen ];
		for ( int i = 0; i < windowLen; i++ )
			allTps[ i ] = startTp + i;

		// Train directly on the spots CURRENTLY in the window — the user's curated set (GT on the first
		// pass; after filtering, the cleaned detections). Training in place (no snapshot/restore) keeps
		// each spot's covariance AND its tp/fp detection tag, so background labelled fp is trained as an
		// explicit negative.
		final int gtCount = countAnnotationsAtTimepoints( context, allTps );

		final ElephantIntegration elephant = new ElephantIntegration( context );
		if ( !elephant.init() )
			return "Error: ELEPHANT server not available.";

		// 1. Train on the current curated spots (filter/label false detections before retraining).
		boolean trained = false;
		if ( gtCount > 0 )
		{
			context.log( String.format( "  [1/4] Training on %d spot(s) currently in the window "
					+ "(curated set: tp positives + any fp negatives)...", gtCount ) );
			// UNPREPARED-TRAINING-OK: the user's curated set. The preparation exists to second-guess
			// the MODEL's detections; applying it here would overrule the person's own annotations,
			// which is the one thing that outranks every measurement in this pipeline.
			trained = elephant.trainDetectionModel(
					allTps, allTps[ allTps.length / 2 ], nEpochs, nCrops, trainTimeoutMs );
			context.log( trained ? "  Training completed."
					: "  WARNING: training did not complete — detecting with the current model." );
		}
		else
		{
			context.log( "  [1/4] No spots in this window — detecting with the current model." );
		}

		// 2. Whole-image detection at each timepoint (NO per-crop local re-prediction).
		deleteAllSpotsAtTimepoints( context, allTps );
		context.log( "  [2/4] Detecting (whole image) across the window..." );
		predictWindow( context, viewer, startTp, endTp, trackIntensityRatio, qualityThreshold, minSize, 0.0 );
		if ( dedupFactor > 0.0 )
		{
			final int dup = deduplicateSpots( context, allTps, dedupFactor );
			if ( dup > 0 )
				context.log( "  NMS: removed " + dup + " duplicate spots." );
		}
		final double[] m = computeQualityMetrics( context, allTps );
		context.log( String.format( "  Detection: spots/tp=%.1f (CV_temporal=%.2f), size=%.1f",
				m[ 0 ], m[ 1 ], m[ 2 ] ) );

		// 3. Link backward within the window.
		context.log( "  [3/4] Linking..." );
		int linkOk = 0;
		for ( int t = endTp; t >= startTp; t-- )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				break;
			if ( !setTimepoint( viewer, t, DEFAULT_LOAD_DELAY_MS ) )
				break;
			final ElephantIntegration e = new ElephantIntegration( context );
			if ( !e.init() )
				continue;
			if ( e.linkSpots() )
				linkOk++;
		}

		context.log( "  [4/4] Done — review the detections in BDV." );
		return String.format(
				"Single-window t=%d..%d: GT=%d (trained=%s), spots/tp=%.1f, timepoints linked=%d/%d.",
				startTp, endTp, gtCount, trained, m[ 0 ], linkOk, windowLen );
	}

	/**
	 * Predicts spots forward through [wStart, wEnd] and applies the intensity filter. {@code
	 * expectedCeiling} of 0 disables the {@link #deleteFlatContrastSpots} count gate (see there) — used
	 * by callers with no calibrated expected-count signal in scope.
	 */
	private static int predictWindow( final ToolContext context, final ViewerPanel viewer,
			final int wStart, final int wEnd, final double intensityRatio, final double qualityThreshold,
			final double minSize, final double expectedCeiling )
	{
		return predictWindow( context, viewer, wStart, wEnd, intensityRatio, qualityThreshold, minSize, null,
				expectedCeiling );
	}

	/**
	 * As {@link #predictWindow(ToolContext, ViewerPanel, int, int, double, double, double, double)}, but
	 * when {@code cropBox} is non-null, every timepoint's prediction is restricted to that small region
	 * (see {@link ElephantIntegration#predictSpotsInRegionAndAdd}) instead of the full frame. Used by the
	 * iterative training loop's NON-FINAL iterations, where the re-detection only exists to produce
	 * fresher self-training annotations for the NEXT round — the full-frame commit that actually feeds
	 * linking/correction/validation only ever comes from the FINAL iteration, so paying full-frame
	 * predict cost on every intermediate round wastes time re-detecting data that gets retrained over
	 * anyway. {@code cropBox} world coords: {@code [x_min,y_min,z_min,x_max,y_max,z_max]}.
	 */
	private static int predictWindow( final ToolContext context, final ViewerPanel viewer,
			final int wStart, final int wEnd, final double intensityRatio, final double qualityThreshold,
			final double minSize, final double[] cropBox, final double expectedCeiling )
	{
		int predictOk = 0;
		final int[] contourSeen = new int[ 3 ]; // [0] looked at, [1] fitted in 3-D (EllipsoidFit3D), [2] by SAM
		final int[] splitFunnel = new int[ AutoCorrectOrchestrator.FUNNEL_SIZE ];
		int explodedTps = 0;
		final double[] sizeFloor = new double[ 2 ];
		double rawSum = 0;
		int rawN = 0;
		// Every count-vs-stated-count comparison in the cleanup passes below has to stand down while
		// this predict is restricted to a crop — see ExpectedCellCount.SHARED_CROP_SCOPED.
		context.setSharedData( ExpectedCellCount.SHARED_CROP_SCOPED, cropBox != null );
		final double[] flatShape = new double[ 12 ];
		final int[] flatWithheld = new int[ 1 ];
		// Anchored to the user's own confirmed cells, so it is computed once for the whole run.
		final double[] debrisThresholds = confirmedDebrisThresholds( context );
		final int[] debrisBlocked = new int[ 3 ];
		// [sumVolume, n] for what the filter flagged, then the same for what it cleared: volume no
		// longer deletes anything, but it is reported beside the criterion that does.
		final double[] debrisVolumeStats = new double[ 4 ];
		// [flaggedTooFaint, flaggedUneven] — flagged, not removed; the floor decides how many go.
		// [tooFaintInTotal, smallAndUneven, tooDimAbsolutely] — flagged, not removed; the floor decides.
		final int[] debrisFaint = new int[ 3 ];
		// [blockedCount, timepointsBlocked, withheldFromTraining]
		// [sumCeiling, timepointsJudged, dimmestConfirmed] — reported so the threshold that decided
		// "nothing there" is in the log next to the count it deleted.
		// [3] counts timepoints the pass refused, [4] the typical-detection anchor it last used
		final double[] noSignalCeiling = new double[ 5 ];
		final PassPlan detectedPlan = PassPlan.forRun( context, CorrectionPass.Stage.DETECTED, DetectedPasses.BUILT_IN );
		final PassScope detected = PassScope.detectedWindow( context, null, wStart, wEnd );
		detected.put( DetectedPasses.EXPECTED_CEILING, expectedCeiling );
		detected.put( DetectedPasses.INTENSITY_RATIO, intensityRatio );
		detected.put( DetectedPasses.QUALITY_THRESHOLD, qualityThreshold );
		detected.put( DetectedPasses.MIN_SIZE, minSize );
		detected.put( DetectedPasses.SPLIT_FUNNEL, splitFunnel );
		detected.put( DetectedPasses.FLAT_SHAPE, flatShape );
		detected.put( DetectedPasses.FLAT_WITHHELD, flatWithheld );
		detected.put( DetectedPasses.NO_SIGNAL_CEILING, noSignalCeiling );
		detected.put( DetectedPasses.DEBRIS_THRESHOLDS, debrisThresholds );
		detected.put( DetectedPasses.DEBRIS_BLOCKED, debrisBlocked );
		detected.put( DetectedPasses.DEBRIS_VOLUME_STATS, debrisVolumeStats );
		detected.put( DetectedPasses.DEBRIS_FAINT, debrisFaint );
		detected.put( DetectedPasses.SIZE_FLOOR, sizeFloor );
		detected.put( DetectedPasses.CONTOUR_SEEN, contourSeen );
		for ( int t = wStart; t <= wEnd; t++ )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() ) break;
			// Reported per TIMEPOINT, not only per window: a window takes twenty minutes, and someone
			// watching a run crawl wants the breakdown before the first one ends. Only a timepoint over
			// SLOW_TIMEPOINT_REPORT_NANOS says anything, so a run that is not slow stays quiet.
			final Map< String, long[] > timingMark = PhaseTimings.mark( context );
			long stepStart = System.nanoTime();
			if ( !setTimepoint( viewer, t, DEFAULT_LOAD_DELAY_MS ) ) break;
			PhaseTimings.record( context, "load timepoint", stepStart );
			final ElephantIntegration e = new ElephantIntegration( context );
			if ( !e.init() ) continue;
			stepStart = System.nanoTime();
			final boolean ok = cropBox == null
					? e.predictSpots( t )
					: e.predictSpotsInRegionAndAdd( t, cropBox );
			PhaseTimings.record( context, "ELEPHANT predict", stepStart );
			if ( ok )
			{
				predictOk++;
				final int rawCount = countCellsAtTimepoints( context, new int[] { t } );
				// Recorded BEFORE any cleanup runs, and including a timepoint about to be discarded as
				// exploded: this is the model's own output, which is what the tuning loop's divergence
				// guard needs to judge. See SHARED_LAST_PREDICT_RAW_SPOTS_PER_TP.
				rawSum += rawCount;
				rawN++;
				// The window's own ceiling where runAdaptiveTrack published one (see
				// SHARED_WINDOW_EXPLOSION_CEILING), the caller's run-wide bound otherwise — callers
				// outside the windowed sweep, and the sweep's own first window, have no measured prior.
				final Object windowCeiling = context.getSharedData( SHARED_WINDOW_EXPLOSION_CEILING );
				final double calibrated = windowCeiling instanceof Number
						&& ( ( Number ) windowCeiling ).doubleValue() > 0.0
								? ( ( Number ) windowCeiling ).doubleValue()
								: expectedCeiling;
				// What the user's own number says THIS timepoint should hold, which is not what the run
				// as a whole should: the run-wide ceiling is built on the population's LARGEST end, so on
				// a movie stated to grow 26 -> 300 it permits 600 at t=0 as readily as at t=91. Window 1
				// of Fluo-N2DL-HeLA_01 detected 885 spots at t=0 against a stated 26 — thirty-four times
				// the expectation, before a single round of training, and nothing stopped it, because
				// 885 sits under a bound calibrated for the end of the movie. ExpectedCellCount.expectedAt
				// already interpolates between the two ends the user gave; this is the one guard that
				// was not asking it.
				final double breakerCeiling = ExpectedCellCount.ceilingAt( context, t,
						prevWindowCalibrated( context ), calibrated );
				final double explosionThreshold = breakerCeiling > 0.0
						? breakerCeiling * DETECTION_EXPLOSION_CEILING_RATIO
						: DETECTION_EXPLOSION_FALLBACK_CAP;
				if ( rawCount > explosionThreshold )
				{
					// Over the threshold means this timepoint is not usable. It does not mean the model
					// has diverged, and only the second question may throw the window away. A timepoint
					// counts toward that tally only when it is meaningfully over — see
					// DIVERGENCE_COUNT_RATIO.
					final boolean countsAsDivergence =
							rawCount > explosionThreshold * DIVERGENCE_COUNT_RATIO;
					if ( countsAsDivergence )
						explodedTps++;
					final boolean severe = rawCount > explosionThreshold * SEVERE_EXPLOSION_RATIO;
					final boolean abandon = severe || explodedTps > MAX_EXPLODED_TIMEPOINTS_PER_WINDOW;
					context.log( String.format(
							"  DETECTION EXPLOSION at t=%d: %d raw spot(s), %.0f%% over the threshold of %.0f%s"
									+ " — discarding this timepoint. %s",
							t, rawCount, ( rawCount / explosionThreshold - 1.0 ) * 100, explosionThreshold,
							breakerCeiling > 0.0
									? String.format( " (%.0fx the expected-cell ceiling of %.0f%s)",
											DETECTION_EXPLOSION_CEILING_RATIO, breakerCeiling,
											breakerCeiling != expectedCeiling
													? String.format( ", calibrated on recent windows rather "
															+ "than on the stated %.0f", expectedCeiling )
													: "" )
									: " (no calibrated ceiling in scope, using the absolute fallback)",
							abandon
									? ( severe
											? "That far over, the model has diverged — abandoning the window."
											: String.format( "%d timepoint(s) in this window have now exploded — "
													+ "the model has diverged, abandoning the window.",
													explodedTps ) )
											+ " If the counts leading here were STABLE and rose only gradually,"
											+ " this is a growing population rather than a divergence: raise"
											+ " cold_start_spots_per_tp (it sets the ceiling above) to what the"
											+ " movie holds at its LAST timepoint, not its first."
									: "Continuing with the rest of the window: one timepoint this far over "
											+ "reads as a dense frame, not a diverged model." ) );
					deleteAllSpotsAtTimepoints( context, new int[] { t } );
					if ( abandon )
						throw new DetectionExplosionException( t, rawCount, explosionThreshold );
					predictOk--; // its output was discarded, so it is not a usable predict
					continue;
				}
				// One observation per sub-pass below, so a bright location that vanishes during the
				// commit is attributed to the pass that actually removed it rather than to "the commit
				// step". Telling "the model never found it" apart from "a filter deleted it" is the
				// whole point, and phase-level granularity cannot do it — from outside this loop the
				// two are identical. A no-op until BrightSpotAudit.arm runs, i.e. throughout tuning.
				BrightSpotAudit.observe( context, "[3/6] predict" );
				// The cleanup every detected frame goes through, in its load-bearing order: DetectedPasses.
				detectedPlan.run( detected.atFrame( t ) );
			}
			final String tpTiming =
					PhaseTimings.summarySince( context, timingMark, SLOW_TIMEPOINT_REPORT_NANOS );
			if ( !tpTiming.isEmpty() )
				context.log( "  t=" + t + tpTiming.substring( tpTiming.indexOf( "Time:" ) - 1 ) );
		}
		final int dupRemoved = detected.total( DetectedPasses.DUPLICATES );
		final int flatContrastRemoved = detected.total( DetectedPasses.FLAT_CONTRAST );
		final int smallRemoved = detected.total( DetectedPasses.SMALL );
		final int extentResized = detected.total( DetectedPasses.EXTENT );
		final int noSignalRemoved = detected.total( DetectedPasses.NO_SIGNAL );
		final int contourFitted = detected.total( DetectedPasses.CONTOUR );
		final int debrisRemoved = detected.total( DetectedPasses.DEBRIS );
		if ( dupRemoved > 0 )
			context.log( "  Deleted " + dupRemoved + " over-segmented duplicate(s) before the cell-count "
					+ "floor was applied (so the floor counts cells, not spots)." );
		if ( flatContrastRemoved > 0 )
			context.log( "  Deleted " + flatContrastRemoved
					+ " detection(s) with no real local contrast peak (flat vs. surroundings — not real cells)." );
		if ( smallRemoved > 0 )
		{
			if ( minSize > 0.0 )
				context.log( "  min_size: removed " + smallRemoved
						+ " sub-cellular noise detection(s) (size < " + minSize + ")." );
			else
				context.log( String.format( "  min_size (AUTO ~%.1f): removed %d sub-cellular noise "
						+ "detection(s); small daughter cells above the noise mode are kept.",
						sizeFloor[ 1 ] > 0 ? sizeFloor[ 0 ] / sizeFloor[ 1 ] : 0.0, smallRemoved ) );
		}
		if ( extentResized > 0 )
			context.log( String.format(
					"  Extent: resized %d detection(s) to the cell measured around them in the image "
							+ "(8 rays to the frame's own median plus 5 sigma). Measured at the DETECTOR's "
							+ "own positions against CTC silver truth, this lands at 1.09x the cell's "
							+ "radius — the benchmark's 1.02x is measured from each cell's brightest pixel, "
							+ "which is not where a detection sits, and that difference is worth 6 points. "
							+ "A detection wider than the body under it reads as background however bright "
							+ "that body is, which is what took the small detections either side of a "
							+ "division.", extentResized ) );
		if ( noSignalRemoved > 0 || noSignalCeiling[ 1 ] > 0 )
		{
			final double noSignalMean =
					noSignalCeiling[ 1 ] > 0 ? noSignalCeiling[ 0 ] / noSignalCeiling[ 1 ] : 0.0;
			// Two anchors iff the confirmed-cell percentile sits above the ceiling: with only the
			// background the ceiling IS the background, so the percentile cannot be above it.
			final boolean twoAnchors = noSignalCeiling[ 2 ] > noSignalMean;
			final int refused = ( int ) noSignalCeiling[ 3 ];
			final int floorAtFirst = ExpectedCellCount.floorAt( context, wStart + 1 );
			context.log( String.format(
					"  No signal: deleted %d detection(s) whose interior MEDIAN reads at or below %.1f "
							+ "— %s. %s%s",
					noSignalRemoved, noSignalMean,
					twoAnchors
							? String.format( "this window's own background plus %.0f%% of the climb to %.1f, "
									+ "the %dth percentile of your confirmed cells",
									AutoCorrectOrchestrator.NO_SIGNAL_MARGIN_FRACTION * 100,
									noSignalCeiling[ 2 ], DEBRIS_INTENSITY_PERCENTILE )
							: String.format( "this FRAME's own median plus %.1f sigma of its spread — "
									+ "measured on 3245 of the detector's own detections against CTC silver "
									+ "truth, this catches 76.6%% of the detections covering no cell for "
									+ "0.57%% of the real ones",
									AutoCorrectOrchestrator.NO_SIGNAL_SIGMA ),
					twoAnchors
							? "Both anchors present, so this is a measurement and the cell-count floor "
									+ "does not bound it."
							: ( "You have confirmed no cells, so this rests on a threshold measured on "
									+ "ground truth rather than on your own cells — so the cell-count floor "
									+ "applies" )
									+ ( floorAtFirst > 0
											? " (currently " + floorAtFirst + " cells/frame)."
											: " — BUT IT IS INERT: you stated no expected cell count in the "
													+ "pre-flight dialog, so there is no floor to apply. "
													+ "State one, or this pass is unbounded." ),
					refused > 0
							? String.format( " REFUSED on %d timepoint(s): the ceiling would have taken more "
									+ "than %.0f%% of the frame, which means it is not describing these "
									+ "cells — on phase contrast or brightfield a cell is often no brighter "
									+ "than its surroundings and this test cannot be made to work.",
									refused, NO_SIGNAL_MAX_FRACTION * 100 )
							: "" ) );
		}
		// Reported where it RUNS: last, after every pass above it. The order of these lines is
		// the only account a reader has of the order of the passes.
		if ( !CleanupToggles.shapeFitEnabled( context ) )
			context.log( "  Shape fitting: OFF (pre-flight dialog) — detections keep ELEPHANT's own shape." );
		else if ( contourSeen[ 0 ] > 0 )
		{
			if ( contourSeen[ 2 ] > 0 )
				context.log( "  Shape fit: SAM masks fitted " + contourSeen[ 2 ] + " of " + contourSeen[ 0 ]
						+ " detection(s) (moved onto the mask's centre, in-plane shape from the mask); the contour fit "
						+ "below counts these too." );
			context.log( String.format(
					"  Contour: fitted %d of %d detection(s) to an ellipse on the cell's own outline; "
							+ "the other %d kept the circle they had. Measured against CTC silver truth "
							+ "on 600 real windows, a circle overlaps its cell at IoU 0.74 and a fitted "
							+ "ellipse at 0.81, with the 10th percentile moving 0.58 to 0.75 — the worst "
							+ "annotations improve most, which is what a training set is made of. "
							+ "Refusing is normal: 17%% of detections on a real cell have their outline "
							+ "hidden by a neighbour, against 93%% refused of those on no cell at all, "
							+ "so a HIGH refusal rate here is a statement about the detections, not "
							+ "about the fit. In plane only; the out-of-plane radius is untouched.%s",
					contourFitted - contourSeen[ 1 ], contourSeen[ 0 ], contourSeen[ 0 ] - contourFitted,
					contourSeen[ 1 ] > 0 || isVolumetric( context )
							? String.format( " Of those the plane fit refused, %d were fitted in 3-D with Mastodon's "
									+ "ellipsoid fitting (edges, not a level above the background).", contourSeen[ 1 ] )
							: "" ) );
		}
		// Also when nothing was considered: the pass can decline a whole timepoint, and a decision
		// not to look is a decision the log has to carry.
		if ( !CleanupToggles.splitMergedEnabled( context ) )
			context.log( "  Split merged detections: off (opt-in; see CleanupToggles.SHARED_SPLIT_MERGED_OVERRIDE)." );
		else if ( splitFunnel[ AutoCorrectOrchestrator.FUNNEL_CONSIDERED ] > 0
				|| splitFunnel[ AutoCorrectOrchestrator.FUNNEL_FRAME_NO_BACKGROUND ] > 0
				|| splitFunnel[ AutoCorrectOrchestrator.FUNNEL_FRAME_NOT_FLUORESCENT ] > 0
				|| splitFunnel[ AutoCorrectOrchestrator.FUNNEL_FRAME_NO_SPREAD ] > 0 )
			context.log( AutoCorrectOrchestrator.describeSplitFunnel( splitFunnel ) );
		if ( debrisRemoved > 0 || debrisBlocked[ 0 ] > 0 )
		{
			final StringBuilder sb = new StringBuilder( String.format(
					"  Debris: REMOVED %d. Flagged %d as too faint in total (below %.0f, the %dth "
							+ "percentile of your confirmed cells' mean intensity x volume%s) and %d as "
							+ "small AND uneven (variation over %.2f, under %.0f µm³).",
					debrisRemoved, debrisFaint[ 0 ], debrisThresholds[ 3 ],
					DEBRIS_TOTAL_SIGNAL_PERCENTILE,
					CleanupToggles.dimTotalSignalFilterEnabled( context ) ? ", fluorescence option ON"
							: ", OFF — not applied",
					debrisFaint[ 1 ], debrisThresholds[ 2 ], debrisThresholds[ 4 ] ) );
			if ( debrisFaint[ 2 ] > 0 )
				sb.append( String.format(
						" %d more were flagged on ABSOLUTE brightness alone: under %.0f, the %dth "
								+ "percentile of your confirmed cells — dim enough that no amount of local "
								+ "contrast makes them a cell.",
						debrisFaint[ 2 ], debrisThresholds[ 5 ], DEBRIS_INTENSITY_PERCENTILE ) );
			if ( debrisVolumeStats[ 1 ] > 0 && debrisVolumeStats[ 3 ] > 0 )
				sb.append( String.format(
						" Flagged spots average %.0f µm³ against %.0f µm³ for the ones cleared (%.2fx); "
								+ "your confirmed cells' %dth percentile is %.0f µm³.",
						debrisVolumeStats[ 0 ] / debrisVolumeStats[ 1 ],
						debrisVolumeStats[ 2 ] / debrisVolumeStats[ 3 ],
						( debrisVolumeStats[ 0 ] / debrisVolumeStats[ 1 ] )
								/ ( debrisVolumeStats[ 2 ] / debrisVolumeStats[ 3 ] ),
						DEBRIS_VOLUME_PERCENTILE, debrisThresholds[ 0 ] ) );
			if ( debrisBlocked[ 0 ] > 0 )
				sb.append( String.format( " The cell-count floor stopped %d further deletion(s) across %d "
						+ "timepoint(s); %d of those were withheld from training instead, so they stay in "
						+ "the graph without teaching the model to find more like them. With the floor "
						+ "binding on most timepoints their counts are decided by it rather than by the "
						+ "image, which is what a CV_temporal near 0.00 below means — read the spot count "
						+ "as the floor, not as a measurement.",
						debrisBlocked[ 0 ], debrisBlocked[ 1 ], debrisBlocked[ 2 ] ) );
			context.log( sb.toString() );
		}
		if ( flatWithheld[ 0 ] > 0 )
			context.log( String.format(
					"  Withheld %d flagged detection(s) from the next training round — kept for linking, "
							+ "but neither taught as cells nor declared background.", flatWithheld[ 0 ] ) );
		logShapeComparison( context, flatShape );
		// Cumulative for this window (reset where the window opens), so the ranking is stable and the
		// numbers keep growing across the tuning rounds rather than restarting every pass.
		final String timings = PhaseTimings.summary( context );
		if ( !timings.isEmpty() )
			context.log( timings );
		context.setSharedData( ExpectedCellCount.SHARED_CROP_SCOPED, Boolean.FALSE );
		context.setSharedData( SHARED_LAST_PREDICT_RAW_SPOTS_PER_TP, rawN > 0 ? rawSum / rawN : 0.0 );
		return predictOk;
	}

	/**
	 * Deletes malformed-shape spots (elongated beyond {@code maxAspectRatio}, or — when
	 * {@code borderMargin > 0} — sitting outside the border margin) at {@code timepoints}. This is a
	 * genuine POST-DETECTION cleanup of the tracked result, distinct from the identical-looking
	 * {@link #filterBorderSpots}/{@link #filterElongatedSpots} calls made just before training (which
	 * only ever clean up the CURRENT detections about to become training labels, never the fresh
	 * detections a just-retrained model produces afterwards). Without this second pass, a thin sliver
	 * artifact next to a real bright cell — a common halo/edge-of-blob segmentation artifact — can pass
	 * quality_threshold/min_intensity_ratio (its sampled intensity/quality is often still high due to
	 * bleed-through from the neighbouring real cell) and then survive untouched in the final result,
	 * because shape was never checked on it.
	 *
	 * @return {@code [borderDeleted, elongatedDeleted]}
	 */
	private static int[] filterMalformedSpots( final ToolContext context, final int[] timepoints,
			final double borderMargin, final double maxAspectRatio )
	{
		int borderDeleted = 0, elongatedDeleted = 0;
		for ( final int t : timepoints )
		{
			if ( borderMargin > 0.0 )
				borderDeleted += filterBorderSpots( context, t, borderMargin );
			if ( maxAspectRatio > 0.0 )
				elongatedDeleted += filterElongatedSpots( context, t, maxAspectRatio );
		}
		return new int[] { borderDeleted, elongatedDeleted };
	}

	/**
	 * Removes spots at {@code timepoint} whose centre falls within
	 * {@code borderMargin} world units of any image face.
	 */
	private static int filterBorderSpots( final ToolContext context, final int timepoint,
			final double borderMargin )
	{
		// The z face counts only where z is a measurement — see ImageBounds. Judging it on one plane
		// put every spot in the movie inside the margin.
		final double[] extent = ImageBounds.physicalExtent( context );
		final boolean volumetric = isVolumetric( context );

		final ModelGraph graph = context.getGraph();
		final List< Spot > toRemove = new ArrayList<>();
		final double[] pos = new double[ 3 ];

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint ) continue;
				if ( ProtectedSpots.isProtected( context, context.getModel(), spot ) ) continue;
				spot.localize( pos );
				if ( ImageBounds.reachesBorder( extent, pos, borderMargin, volumetric ) )
				{
					final Spot ref = graph.vertexRef();
					ref.refTo( spot );
					toRemove.add( ref );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( toRemove.isEmpty() ) return 0;

		int removed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			// Count what GraphEdits actually removed, not what this pass asked it to. It refuses at the
			// cell-count floor, so the two differ by everything the floor saved — and reporting the
			// request as the result is what let "Deleted 1270 elongated malformed spot(s)" print on a
			// window that still held 1112 of them.
			for ( final Spot spot : toRemove )
				if ( GraphEdits.removeSpot( context, graph, spot, GraphEdits.Reason.NOT_A_CELL ) )
					removed++;
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		for ( final Spot spot : toRemove ) graph.releaseRef( spot );
		context.getModel().setUndoPoint();
		return removed;
	}

	/**
	 * Removes spots at {@code timepoint} whose covariance matrix aspect ratio
	 * (sqrt of max diagonal / sqrt of min diagonal) exceeds {@code maxAspectRatio}.
	 * High ratios indicate elongated non-cell artifacts.
	 */
	/** Collects the size (sqrt of covariance trace) of every spot at {@code timepoint}. */
	private static double[] collectSpotSizes( final ToolContext context, final int timepoint )
	{
		final ModelGraph graph = context.getGraph();
		final List< Double > sizes = new ArrayList<>();
		final double[][] cov = new double[ 3 ][ 3 ];
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint ) continue;
				spot.getCovariance( cov );
				sizes.add( Math.sqrt( cov[ 0 ][ 0 ] + cov[ 1 ][ 1 ] + cov[ 2 ][ 2 ] ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		final double[] out = new double[ sizes.size() ];
		for ( int i = 0; i < out.length; i++ ) out[ i ] = sizes.get( i );
		return out;
	}

	/**
	 * Derives an automatic size floor separating a background-noise size mode from the cell size mode,
	 * using Otsu's method on the spot-size distribution.
	 *
	 * <p>Returns {@code 0} (i.e. "do not filter") unless there are clearly TWO size populations AND
	 * the lower one is much smaller than the upper one ({@code meanLow/meanHigh < }
	 * {@link #NOISE_CELL_SIZE_RATIO}). This is the key protection for small daughter cells produced by
	 * division: a mother/daughter size split (ratio ~0.8) does NOT trigger removal, whereas a
	 * noise/cell split (ratio ~0.4) does. When in doubt, nothing is removed.</p>
	 */
	private static double deriveNoiseSizeFloor( final double[] sizes )
	{
		final int n = sizes.length;
		if ( n < MIN_SPOTS_FOR_SIZE_OTSU )
			return 0.0;
		double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
		for ( final double v : sizes ) { if ( v < lo ) lo = v; if ( v > hi ) hi = v; }
		if ( hi - lo < 1e-9 )
			return 0.0;

		// Histogram + Otsu threshold (treat sizes like a 1-D intensity distribution).
		final int bins = 64;
		final int[] hist = new int[ bins ];
		for ( final double v : sizes )
		{
			int b = ( int ) ( ( v - lo ) / ( hi - lo ) * ( bins - 1 ) );
			if ( b < 0 ) b = 0; else if ( b >= bins ) b = bins - 1;
			hist[ b ]++;
		}
		double sumAll = 0;
		for ( int b = 0; b < bins; b++ ) sumAll += ( double ) b * hist[ b ];
		double wB = 0, sumB = 0, maxVar = -1;
		int thrBin = -1;
		for ( int b = 0; b < bins; b++ )
		{
			wB += hist[ b ];
			if ( wB == 0 ) continue;
			final double wF = n - wB;
			if ( wF == 0 ) break;
			sumB += ( double ) b * hist[ b ];
			final double mB = sumB / wB;
			final double mF = ( sumAll - sumB ) / wF;
			final double var = wB * wF * ( mB - mF ) * ( mB - mF );
			if ( var > maxVar ) { maxVar = var; thrBin = b; }
		}
		if ( thrBin < 0 )
			return 0.0;
		final double thr = lo + ( ( thrBin + 0.5 ) / bins ) * ( hi - lo );

		// Classify around the threshold and apply the noise-vs-daughter guard.
		double sumLow = 0, sumHigh = 0;
		int nLow = 0, nHigh = 0;
		for ( final double v : sizes )
		{
			if ( v < thr ) { sumLow += v; nLow++; }
			else { sumHigh += v; nHigh++; }
		}
		if ( nLow == 0 || nHigh == 0 )
			return 0.0;
		final double meanLow = sumLow / nLow;
		final double meanHigh = sumHigh / nHigh;
		if ( meanHigh <= 0.0 || meanLow / meanHigh > NOISE_CELL_SIZE_RATIO )
			return 0.0; // modes too close — likely mother/daughter or unimodal; keep everything
		return thr;
	}

	/**
	 * Resolves and applies the size floor at {@code timepoint}: fixed if {@code minSizeParam>0},
	 * disabled if {@code <0}, else AUTO-derived from the timepoint's size distribution
	 * ({@link #deriveNoiseSizeFloor}).
	 *
	 * @return {@code [0]} = spots removed, {@code [1]} = the size floor actually applied (0 if none)
	 */
	static double[] filterNoiseBySize( final ToolContext context, final int timepoint,
			final double minSizeParam )
	{
		final double floor;
		if ( minSizeParam == 0.0 )
			floor = 0.0; // OFF (default) — no size filtering
		else if ( minSizeParam > 0.0 )
		{
			// Guard against a mis-scaled fixed floor. min_size is a spot SIZE in world µm
			// (sqrt of covariance trace, ~cell size), NOT a voxel/volume count. A floor above every
			// spot's size would delete the whole window — as happened when an agent set min_size=20 on
			// cells of size ~5-9, which cascaded to zero spots and zero links. Skip + warn instead of
			// emptying the graph.
			final double[] sizes = collectSpotSizes( context, timepoint );
			double maxSize = 0.0;
			for ( final double s : sizes )
				maxSize = Math.max( maxSize, s );
			if ( sizes.length > 0 && minSizeParam > maxSize )
			{
				context.log( String.format(
						"  WARNING: min_size=%.1f exceeds EVERY detected spot's size (max=%.1f µm at t=%d) — "
								+ "min_size is a spot size in µm (sqrt covariance trace, ~cell size), NOT a "
								+ "voxel count. Skipping the size filter to avoid deleting all detections; "
								+ "use a value below %.1f, or 0 to disable.",
						minSizeParam, maxSize, timepoint, maxSize ) );
				return new double[] { 0, 0.0 };
			}
			floor = minSizeParam; // fixed floor
		}
		else
			floor = deriveNoiseSizeFloor( collectSpotSizes( context, timepoint ) ); // AUTO (opt-in, <0)
		if ( floor <= 0.0 )
			return new double[] { 0, 0.0 };
		return new double[] { filterSmallSpots( context, timepoint, floor ), floor };
	}

	/**
	 * Removes spots at {@code timepoint} whose size (sqrt of covariance trace, world units) is below
	 * {@code minSize} — i.e. sub-cellular background-noise detections. No-op when {@code minSize<=0}.
	 */
	private static int filterSmallSpots( final ToolContext context, final int timepoint,
			final double minSize )
	{
		if ( minSize <= 0.0 )
			return 0;
		final ModelGraph graph = context.getGraph();
		final List< Spot > toRemove = new ArrayList<>();
		final double[][] cov = new double[ 3 ][ 3 ];
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint ) continue;
				if ( ProtectedSpots.isProtected( context, context.getModel(), spot ) ) continue;
				spot.getCovariance( cov );
				final double size = Math.sqrt( cov[ 0 ][ 0 ] + cov[ 1 ][ 1 ] + cov[ 2 ][ 2 ] );
				if ( size < minSize )
				{
					final Spot ref = graph.vertexRef();
					ref.refTo( spot );
					toRemove.add( ref );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( toRemove.isEmpty() ) return 0;
		int removed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			// See the identical count in deleteSmallSpots: GraphEdits refuses at the cell-count floor,
			// so only what it actually removed may be reported.
			for ( final Spot spot : toRemove )
				if ( GraphEdits.removeSpot( context, graph, spot, GraphEdits.Reason.PROBABLY_NOT_A_CELL ) )
					removed++;
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		for ( final Spot spot : toRemove ) graph.releaseRef( spot );
		context.getModel().setUndoPoint();
		return removed;
	}

	private static int filterElongatedSpots( final ToolContext context, final int timepoint,
			final double maxAspectRatio )
	{
		final ModelGraph graph = context.getGraph();
		final List< Spot > toRemove = new ArrayList<>();
		final double[][] cov = new double[ 3 ][ 3 ];
		// In-plane only on a single-plane dataset — see SpotShape, which is why this check deleted
		// every round nucleus in a 2D movie.
		final boolean volumetric = isVolumetric( context );

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint ) continue;
				if ( ProtectedSpots.isProtected( context, context.getModel(), spot ) ) continue;
				spot.getCovariance( cov );
				if ( SpotShape.isElongated( cov, maxAspectRatio, volumetric ) )
				{
					final Spot ref = graph.vertexRef();
					ref.refTo( spot );
					toRemove.add( ref );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( toRemove.isEmpty() ) return 0;

		int removed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			// See the identical count in deleteSmallSpots: GraphEdits refuses at the cell-count floor,
			// so only what it actually removed may be reported.
			for ( final Spot spot : toRemove )
				if ( GraphEdits.removeSpot( context, graph, spot, GraphEdits.Reason.PROBABLY_NOT_A_CELL ) )
					removed++;
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		for ( final Spot spot : toRemove ) graph.releaseRef( spot );
		context.getModel().setUndoPoint();
		return removed;
	}

	/** Returns the fraction of spots in [wStart, wEnd] that have at least one incoming or outgoing link. */
	private static double computeLinkDensity( final ToolContext context, final int wStart, final int wEnd )
	{
		final ModelGraph graph = context.getGraph();
		int total = 0, linked = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				if ( t >= wStart && t <= wEnd )
				{
					total++;
					if ( spot.incomingEdges().size() > 0 || spot.outgoingEdges().size() > 0 )
						linked++;
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return total > 0 ? ( double ) linked / total : 0.0;
	}

	/**
	 * Minimum currently-detected spots needed to calibrate {@link #computeMeanDetectedContrast} — below
	 * this, the sample is too small/noisy to anchor a threshold on, so callers fall back to the
	 * uncalibrated (global-percentile-only) estimate.
	 */
	private static final int MIN_SPOTS_FOR_CONTRAST_CALIBRATION = 3;

	/**
	 * A candidate maximum must reach at least this fraction of {@link #computeMeanDetectedContrast}'s
	 * result to be accepted as cell-like (see {@link #estimateExpectedCellCount}). Generous enough that
	 * a real cell dimmer than the dataset's average still counts, while a smooth, low-contrast region
	 * (halo, out-of-focus haze, autofluorescence) — which is often globally bright enough to clear a raw
	 * intensity percentile despite not standing out locally at all — does not.
	 */
	private static final double CONTRAST_ACCEPT_FRACTION = 0.3;

	/**
	 * Mean local contrast of currently-detected spots over {@code [wStart, wEnd]}, sampled at each spot's
	 * own radius. Calibrates what counts as a "real" bright peak for {@link #estimateExpectedCellCount}
	 * against THIS dataset's own already-found cells, instead of an arbitrary universal constant —
	 * contrast/brightness scale wildly across imaging modalities and specimens, so no fixed number would
	 * transfer between them.
	 *
	 * <p>Deliberately samples from the same coarse-mipmap {@link FindMaximaTool.ScanState} that the
	 * candidates it will be compared against use (grouping spots by timepoint so each timepoint's state
	 * is captured only once) — mixing a full-resolution calibration value with coarse-resolution
	 * candidate readings would systematically miscalibrate the threshold, since mipmap downsampling
	 * smooths out peaks and lowers measured contrast.</p>
	 *
	 * @return mean contrast, or {@link Double#NaN} if fewer than {@link #MIN_SPOTS_FOR_CONTRAST_CALIBRATION}
	 *         spots exist to calibrate from
	 */
	private static double computeMeanDetectedContrast( final ToolContext context, final ViewerPanel viewer,
			final int wStart, final int wEnd )
	{
		final ModelGraph graph = context.getGraph();
		final Map< Integer, List< double[] > > byTimepoint = new HashMap<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				if ( t < wStart || t > wEnd )
					continue;
				final double[] pos = new double[ 3 ];
				spot.localize( pos );
				final double r = Math.sqrt( spot.getBoundingSphereRadiusSquared() );
				byTimepoint.computeIfAbsent( t, k -> new ArrayList<>() ).add( new double[] {
						pos[ 0 ], pos[ 1 ], pos[ 2 ], r } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		final List< Double > contrasts = new ArrayList<>();
		for ( final Map.Entry< Integer, List< double[] > > e : byTimepoint.entrySet() )
		{
			final FindMaximaTool.ScanState[] holder = new FindMaximaTool.ScanState[ 1 ];
			try
			{
				if ( SwingUtilities.isEventDispatchThread() )
					holder[ 0 ] = FindMaximaTool.captureViewerState( viewer, e.getKey() );
				else
					SwingUtilities.invokeAndWait(
							() -> holder[ 0 ] = FindMaximaTool.captureViewerState( viewer, e.getKey() ) );
			}
			catch ( final Exception ex )
			{
				continue;
			}
			final FindMaximaTool.ScanState state = holder[ 0 ];
			if ( state == null || !state.isValid() )
				continue;
			for ( final double[] rec : e.getValue() )
				contrasts.add( FindMaximaTool.sampleContrast( state,
						new double[] { rec[ 0 ], rec[ 1 ], rec[ 2 ] }, rec[ 3 ] ) );
		}
		if ( contrasts.size() < MIN_SPOTS_FOR_CONTRAST_CALIBRATION )
			return Double.NaN;
		double sum = 0;
		for ( final double c : contrasts )
			sum += c;
		return sum / contrasts.size();
	}

	/**
	 * Estimates the number of cell-scale bright blobs at {@code timepoint} using model-free 3-D
	 * intensity maxima — a ground-truth-free proxy for the "expected" cell count. The maxima are
	 * suppressed at roughly one cell spacing ({@code worldNmsRadius}) so each cell counts once.
	 *
	 * <p>A raw global-percentile threshold alone (see {@link #COMPLETENESS_PERCENTILE}) assumes cells
	 * occupy roughly that top fraction of the volume's voxels — true for a dense/crowded specimen, but
	 * badly wrong for a sparse one (e.g. a handful of small cells in a large embryo volume), where it
	 * lets through vast amounts of non-cell bright tissue/halo/noise as "candidates", NMS-thins them to a
	 * still-inflated count, and can saturate at the hard cap (1000) — turning "detected ÷ estimated"
	 * into a near-zero number even when detection is actually fine. When {@code contrastThreshold} is
	 * finite (see {@link #computeMeanDetectedContrast}), every surviving candidate is additionally
	 * required to be a genuine LOCAL peak above that threshold — the same "local contrast, not absolute
	 * intensity" principle {@code AutoCorrectOrchestrator}/{@code DetectionAcceptanceProfile} already use
	 * to tell real cells from background — which rejects smooth halos/haze regardless of how globally
	 * bright they read. {@code Double.NaN} (no calibration data yet) falls back to the raw, uncorrected
	 * count, matching prior behaviour.
	 *
	 * @return estimated cell count, or {@code -1} if the image could not be read
	 */
	private static int estimateExpectedCellCount( final ToolContext context, final ViewerPanel viewer,
			final int timepoint, final double worldNmsRadius, final double contrastThreshold )
	{
		final FindMaximaTool.ScanState[] holder = new FindMaximaTool.ScanState[ 1 ];
		try
		{
			if ( SwingUtilities.isEventDispatchThread() )
				holder[ 0 ] = FindMaximaTool.captureViewerState( viewer, timepoint );
			else
				SwingUtilities.invokeAndWait(
						() -> holder[ 0 ] = FindMaximaTool.captureViewerState( viewer, timepoint ) );
		}
		catch ( final Exception e )
		{
			return -1;
		}
		final FindMaximaTool.ScanState state = holder[ 0 ];
		if ( state == null || !state.isValid() )
			return -1;
		final List< double[] > candidates = FindMaximaTool.scanImageSpaceMaxima(
				state, 1000, COMPLETENESS_SCAN_STRIDE, worldNmsRadius, COMPLETENESS_PERCENTILE );
		if ( Double.isNaN( contrastThreshold ) )
			return candidates.size();
		int accepted = 0;
		for ( final double[] pos : candidates )
			if ( FindMaximaTool.sampleContrast( state, pos, worldNmsRadius ) >= contrastThreshold )
				accepted++;
		return accepted;
	}

	/**
	 * Detection completeness (recall proxy) over [wStart, wEnd]: the mean over timepoints of
	 * min(1, detected / expected), where "expected" is the intensity-maxima cell-count estimate.
	 * Uses the current mean spot size as the maxima suppression radius so the reference matches the
	 * detected cell scale, and — when enough spots exist to calibrate from — the current mean detected
	 * contrast as the local-peak acceptance threshold (see {@link #estimateExpectedCellCount}).
	 *
	 * @return completeness in [0,1], or 1.0 if it cannot be estimated (so it never falsely blocks)
	 */
	private static double computeDetectionCompleteness( final ToolContext context,
			final ViewerPanel viewer, final int wStart, final int wEnd )
	{
		final double meanSize = computeQualityMetrics( context, allTimepointsArray( wStart, wEnd ) )[ 2 ];
		final double nmsRadius = meanSize > 0.5 ? meanSize : DEFAULT_SEED_RADIUS_WORLD;
		final double meanContrast = computeMeanDetectedContrast( context, viewer, wStart, wEnd );
		final double contrastThreshold =
				Double.isNaN( meanContrast ) ? Double.NaN : CONTRAST_ACCEPT_FRACTION * meanContrast;
		double sum = 0;
		int n = 0;
		// Sample a few evenly-spaced timepoints rather than every one in the window: each timepoint costs
		// one whole-volume maxima scan, and this is an ADVISORY estimate, not an exact metric, so trading
		// sample density for a large constant-factor speedup is a good trade (see COMPLETENESS_SAMPLE_TIMEPOINTS).
		final int nSamples = Math.min( wEnd - wStart + 1, COMPLETENESS_SAMPLE_TIMEPOINTS );
		for ( final int t : selectEvenlySpaced( wStart, wEnd, nSamples ) )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				break;
			final int expected = estimateExpectedCellCount( context, viewer, t, nmsRadius, contrastThreshold );
			if ( expected <= 0 )
				continue;
			final int detected = countCellsAtTimepoints( context, new int[] { t } );
			sum += Math.min( 1.0, ( double ) detected / expected );
			n++;
		}
		if ( n > 0 )
			return sum / n;
		// No timepoint yielded an estimate. Falling through to "complete" is right when the window holds
		// detections and the maxima scan simply could not corroborate them — the estimate over-counts on
		// halo and out-of-focus data, so it is advisory and must not block a window it cannot measure.
		//
		// It is wrong when the window holds NOTHING. Zero detected cells are zero per cent of any cell
		// count, and reporting an empty window as fully complete is the single most dangerous thing this
		// number can say: completeness is a `done` gate, and the agent reads it. Observed on a window
		// whose every detection had just been deleted — "spots/tp=0.0" and "completeness: 100%" on
		// consecutive lines.
		return countCellsAtTimepoints( context, allTimepointsArray( wStart, wEnd ) ) > 0 ? 1.0 : 0.0;
	}

	/** Builds the inclusive timepoint array [wStart..wEnd]. */
	private static int[] allTimepointsArray( final int wStart, final int wEnd )
	{
		final int[] tps = new int[ Math.max( 0, wEnd - wStart + 1 ) ];
		for ( int i = 0; i < tps.length; i++ )
			tps[ i ] = wStart + i;
		return tps;
	}

	/**
	 * Returns {@code true} if detection looks like a cold-start / untrained-model regime:
	 * an implausibly high spot count means the model is flooding the image with noise, which
	 * self-training only amplifies. The remedy is intensity-based seeding, not training.
	 */
	private static boolean isColdStartRegime( final double spotsPerTp, final double ceiling )
	{
		return spotsPerTp > ceiling;
	}

	/**
	 * Above this fraction of zero-intensity spots (see {@link #computeZeroIntensityFraction}), the
	 * detection is treated as garbage regardless of its raw count: most "cells" sitting on bare
	 * background is never a real biological result, only a collapsed/untrained model.
	 */
	private static final double ZERO_INTENSITY_FLOOD_FRACTION = 0.5;

	/**
	 * Raw (pre-cleanup) spots/tp above this multiple of {@code coldStartCeiling} skips the O(n²)
	 * NMS/over-segmentation-resolution cleanup step entirely (see the "[1/6]" flood pre-check in
	 * {@code runAdaptiveTrack}): dedup/duplicate-resolution only ever trims a few percent in practice
	 * (see the logged counts across real runs), so at this multiple no plausible cleanup would bring
	 * the count back under the ceiling anyway — the data is getting discarded by
	 * {@link #bootstrapColdStart} regardless, so pairwise-comparing tens of thousands of spots/tp
	 * first (which can take minutes) just wastes time on data nobody keeps.
	 */
	private static final double EXTREME_FLOOD_RATIO = 3.0;

	/**
	 * Per-timepoint hard circuit breaker for a single {@code predict()} call, expressed as a multiple of
	 * the caller's {@code expectedCeiling} (usually {@code coldStartCeiling}, itself already
	 * user-calibrated — see {@link #SHARED_COLD_START_CEILING_OVERRIDE}) rather than an absolute count: a
	 * dataset the user has told us genuinely has thousands of cells/frame must not be flagged just
	 * because some OTHER dataset's explosion happened to land in that range. Deliberately more generous
	 * than {@link #EXTREME_FLOOD_RATIO} (which only decides "skip straight to cold-start bootstrap," a
	 * much cheaper thing to be wrong about) — this instead aborts the whole window, so it should only
	 * fire on an unambiguous divergence, not a merely-dense-but-plausible frame.
	 */
	private static final double DETECTION_EXPLOSION_CEILING_RATIO = 2.0;

	/**
	 * Most the measured, self-calibrating explosion ceiling may exceed the run-wide stated bound by.
	 *
	 * <p>Each window's ceiling is calibrated on the previous window's own count, which is a ratchet: a
	 * runaway that grows by less than {@link #PREV_WINDOW_EXPLOSION_RATIO} every window would raise the
	 * bar behind itself forever and never be caught. This is where that stops. Deliberately the same
	 * multiple {@code ExpectedCellCount.MAX_TOTAL_GROWTH_MULTIPLE} puts on the FLOOR, because it answers
	 * the same question from the other side — how far past the number the user gave can the population
	 * plausibly have grown — and the two guards disagreeing about that would be its own bug.</p>
	 */
	private static final double MAX_CEILING_GROWTH_MULTIPLE = 4.0;

	/**
	 * Expected cells/frame the explosion guards should compare a window against: the previous window's
	 * MEASURED count where there is one, the run-wide stated bound where there is not.
	 *
	 * <p>The preference is not new — the invariant ceiling has read this way since it was written, and
	 * its comment gives the reason: {@code coldStartCeiling} is a bound stated once, {@code
	 * prevWindowBaseline} is a measurement taken ten timepoints ago, and the measurement is the tighter
	 * prior. What was new is applying it at the two sites that actually abort a window. Until then they
	 * consulted the stated bound alone, and one line of a real run shows what that costs: "exploded to
	 * spots/tp=61 (ceiling 60; previous window's spots/tp=48.7, ratio=1.2x vs explosion threshold 2.0x)"
	 * — the adaptive check reporting the window healthy, in the text of the warning declaring it dead.
	 * The absolute bound then vetoed it, the window was marked collapsed, and marking it collapsed is
	 * what withheld {@code prevWindowBaseline} from every window that followed. The guard that cannot
	 * adapt disabled the guard that can.</p>
	 *
	 * <p>Never below {@code coldStartCeiling}: a window that merely under-detected must not be allowed
	 * to tighten the guard on the next one. Never above {@link #MAX_CEILING_GROWTH_MULTIPLE} times it,
	 * so the ratchet has an end.</p>
	 *
	 * @param prevWindowBaseline the previous window's spots/tp, or {@code <= 0} when there is none or
	 *                           the pipeline declared that window collapsed
	 */
	static double windowExplosionCeiling( final double coldStartCeiling,
			final double prevWindowBaseline )
	{
		if ( prevWindowBaseline <= 0 || coldStartCeiling <= 0 )
			return coldStartCeiling;
		return Math.min( coldStartCeiling * MAX_CEILING_GROWTH_MULTIPLE,
				Math.max( coldStartCeiling, prevWindowBaseline ) );
	}

	/**
	 * Invariant threshold (see {@code PipelineInvariants}): the largest share of a timepoint's spots a
	 * CORRECTION phase may add on top of what it started with. A correction pass exists to repair
	 * links and prune bad detections; when it instead has to invent a quarter of a timepoint from
	 * interpolation, the detection it is patching has failed and the interpolated result is not
	 * trustworthy — the same reasoning (and the same fraction) as
	 * {@code AutoCorrectOrchestrator.MAX_GAP_FILL_FRACTION}, checked here from the outside so it also
	 * covers any other phase that starts adding spots.
	 */
	private static final double MAX_INVENTED_SPOT_FRACTION = 0.25;

	/**
	 * Invariant threshold (see {@code PipelineInvariants}): the largest drop in window continuity
	 * (absolute, 0..1) a correction phase may cause. Continuity is this pipeline's primary
	 * tracking-quality signal, so a correction phase that lowers it is fragmenting tracks rather than
	 * repairing them, however good its own per-step statistics look.
	 */
	private static final double MAX_CONTINUITY_DROP = 0.10;

	/**
	 * A tuning round whose re-detection exceeds this multiple of what the model produced BEFORE that
	 * round's training is treated as divergence, not as recovered recall.
	 *
	 * <p>The tuning loop trains on the model's own detections, and ELEPHANT's loss ignores unlabelled
	 * voxels — by design, so a human annotating a handful of cells is not obliged to label the rest of
	 * the volume. Used in a fully automatic loop that property has no counterweight: this round's
	 * predictions become next round's positives, the decision boundary moves outward, and nothing pushes
	 * back except the few synthetic negatives a confluent field allows. Measured across four windows of
	 * one run: 274 -> 330 -> 350 -> 419, 227 -> 277 -> 388 -> 410, 267 -> 393 -> 470 -> 507, and
	 * 266 -> 338 -> 495 -> 2114 raw at a single timepoint, which took the window with it.
	 *
	 * <p>1.3 leaves room for a round that genuinely recovers missed cells — the largest single healthy
	 * step observed was about 1.2x — while catching the runaway steps, which were 1.4x and above.
	 */
	private static final double MAX_TUNING_ROUND_GROWTH = 1.3;

	/**
	 * Share of the user's confirmed spots the flat-contrast check may call background before it is
	 * reported as over-flagging. A statistical background test misfiring on one confirmed cell in
	 * twenty is a threshold effect; on one in five it is not measuring what it claims to.
	 */
	private static final double FLAT_CONTRAST_MAX_TOLERABLE_FP_RATE = 10.0;

	/**
	 * Reports size and roundness for the spots the flat-contrast check flagged, the ones it kept, and
	 * the ones the user confirmed — see {@code deleteFlatContrastSpots}'s {@code shape} parameter for
	 * why the third group decides how to read the first two.
	 */
	private static void logShapeComparison( final ToolContext context, final double[] shape )
	{
		if ( shape[ 3 ] == 0 || shape[ 7 ] == 0 )
			return;
		final double sizeFlagged = shape[ 0 ] / shape[ 3 ], aspectFlagged = shape[ 1 ] / shape[ 3 ];
		final double brightFlagged = shape[ 2 ] / shape[ 3 ];
		final double sizeKept = shape[ 4 ] / shape[ 7 ], aspectKept = shape[ 5 ] / shape[ 7 ];
		final double brightKept = shape[ 6 ] / shape[ 7 ];
		final StringBuilder sb = new StringBuilder( String.format(
				"  Flat-contrast shape: flagged %.1f µm / aspect %.2f / intensity %.0f, "
						+ "kept %.1f µm / aspect %.2f / intensity %.0f",
				sizeFlagged, aspectFlagged, brightFlagged, sizeKept, aspectKept, brightKept ) );
		if ( shape[ 11 ] > 0 )
			sb.append( String.format( ", YOUR confirmed cells %.1f µm / aspect %.2f / intensity %.0f",
					shape[ 8 ] / shape[ 11 ], shape[ 9 ] / shape[ 11 ], shape[ 10 ] / shape[ 11 ] ) );
		sb.append( " — " );
		if ( brightFlagged >= BRIGHT_FLAG_RATIO * brightKept )
			sb.append( String.format(
					"the flagged detections are %.0f%% as bright as the ones kept, so this check IS "
							+ "deleting bright spots, not only dim ones. In a dense frame a uniformly bright "
							+ "cell has bright neighbours, its local contrast reads as flat, and only the "
							+ "absolute-intensity test stands between it and deletion.",
					100 * brightFlagged / brightKept ) );
		else if ( aspectFlagged - aspectKept < MIN_INFORMATIVE_ASPECT_GAP )
			sb.append( "the flagged detections are no less round than the kept ones, so these ellipsoids "
					+ "carry no shape signal — ELEPHANT fits them to its own predicted blob and it was "
					+ "trained on spheres, so roundness here may describe the model, not the image." );
		else
			sb.append( "the flagged detections are measurably less round, so size and roundness together "
					+ "separate what this check flags — which is what you described debris as." );
		context.log( sb.toString() );
	}

	/**
	 * Share of the kept detections' mean intensity at which the flagged ones stop looking dim. Above
	 * this the flat-contrast check is removing spots that are as bright as the cells it keeps, which is
	 * a different failure from removing dim ones and has a different cause — see the message.
	 */
	private static final double BRIGHT_FLAG_RATIO = 0.8;

	/**
	 * Smallest gap in mean aspect ratio between flagged and kept detections that would mean the
	 * ellipsoid shape is describing the image rather than the model that drew it.
	 */
	private static final double MIN_INFORMATIVE_ASPECT_GAP = 0.15;

	/**
	 * Percentile of the user's CONFIRMED cell volumes below which a detection is treated as debris.
	 *
	 * <p>Not a threshold in µm³, because nobody knows that number for a given dataset and guessing it
	 * has already emptied a window once. The user's confirmed spots are real cells by definition, so
	 * their volume distribution states what a real cell's volume is HERE — daughter cells right after
	 * division included, which is what makes this safe where a size cutoff derived from the detections
	 * themselves is not: those run about 0.77x the linear size of their neighbours, which is exactly
	 * where a daughter sits.</p>
	 *
	 * <p>Low rather than aggressive on purpose: this is the first cut, and the log reports both what it
	 * removed and the threshold it used, so the number can be moved once there is a run to read.</p>
	 */
	private static final int DEBRIS_VOLUME_PERCENTILE = 5;

	/**
	 * Percentile of the user's CONFIRMED cells' interior heterogeneity above which a detection is
	 * treated as debris — see {@link AutoCorrectOrchestrator#intensityHeterogeneity}.
	 *
	 * <p>This, not volume, is what the deletion acts on. The user's account is that debris mixes bright
	 * and dark regions while a cell is evenly bright throughout, and unlike every criterion tried before
	 * it that one does not describe a daughter cell too: a daughter is small, and it is the same even
	 * brightness as its mother. Volume is still measured and reported beside it so the two can be
	 * compared on a real run, but it does not delete anything on its own.</p>
	 *
	 * <p>Calibrating on confirmed cells rather than on a fixed number also absorbs the systematic
	 * quirks. A dim cell has a small mean and so a noise-inflated ratio — and the dim cells among the
	 * confirmed spots raise this threshold by exactly that much.</p>
	 */
	private static final int DEBRIS_HETEROGENEITY_PERCENTILE = 95;

	/**
	 * Percentile of the user's CONFIRMED cells' TOTAL integrated brightness below which a detection is
	 * debris, when the user has said the images are fluorescence.
	 *
	 * <p>Total, not mean and not volume: mean intensity misses a small speck that is locally bright,
	 * volume misses a large faint smear, and their product is the quantity that actually corresponds to
	 * how much labelled material sits there. That is a fact about fluorescence, so this only applies
	 * when {@code CleanupToggles.dimTotalSignalFilterEnabled} says so.</p>
	 *
	 * <p>Lower than the heterogeneity percentile is deliberate. Evenness has a natural ceiling that
	 * real cells respect; total brightness varies enormously between a bright cell and a dim one, and
	 * this run's own bright-spot audit already reports real cells the detector never found at all, so
	 * cutting close to the confirmed cells' lower tail would remove the dim ones the pipeline is
	 * already struggling to keep.</p>
	 */
	private static final int DEBRIS_TOTAL_SIGNAL_PERCENTILE = 2;

	/**
	 * Percentile of the confirmed cells' volume ABOVE WHICH a detection is too big to be debris, whatever
	 * its interior looks like.
	 *
	 * <p>The size half of "debris is small and mixes bright and dark". Generous on purpose — it is not
	 * trying to identify debris, only to rule out the spots that unevenness alone kept mistaking for it.
	 * A big cell has internal texture; a big cell is not debris.</p>
	 */
	private static final int DEBRIS_VOLUME_CEILING_PERCENTILE = 50;

	/**
	 * Percentile of the confirmed cells' ABSOLUTE interior brightness below which a detection is too
	 * dim to be a cell, whatever else is true of it.
	 *
	 * <p>The one criterion here that does not consult local contrast, and it exists because that is
	 * exactly what was getting through: {@code isDefinitelyBackground} returns false the moment a
	 * detection stands out from its surroundings, so something very dark sitting in something darker
	 * still was never flagged, however far below a real cell its brightness was. Volume does not catch
	 * it, and neither does the total-signal test once the thing is large enough.</p>
	 *
	 * <p>Absolute, but read off the confirmed cells rather than fixed: this is the brightness the
	 * dimmest few percent of known cells have, so a detection under it is dimmer than almost every cell
	 * anyone has confirmed in this dataset.</p>
	 */
	private static final int DEBRIS_INTENSITY_PERCENTILE = 2;

	/** {@link ToolContext#getSharedData} key caching the confirmed-cell volume floor and sample size. */
	private static final String SHARED_DEBRIS_VOLUME_FLOOR = "adaptive_track.debris_volume_floor";

	/** Ellipsoid volume in µm³ from a spot's covariance, using its diagonal as the rest of this class does. */
	static double spotVolume( final double[][] cov )
	{
		final double product = Math.max( 0.0, cov[ 0 ][ 0 ] ) * Math.max( 0.0, cov[ 1 ][ 1 ] )
				* Math.max( 0.0, cov[ 2 ][ 2 ] );
		return 4.0 / 3.0 * Math.PI * Math.sqrt( product );
	}

	/**
	 * Volume below which a detection is debris, taken as {@link #DEBRIS_VOLUME_PERCENTILE} of the
	 * volumes of every spot the user has confirmed, or {@code 0} (filter off) when none are confirmed.
	 * Pooled over the whole movie and computed once per run — a per-timepoint estimate would be drawn
	 * from a handful of spots and would move with the detection it is supposed to judge.
	 */
	private static double[] confirmedDebrisThresholds( final ToolContext context )
	{
		final Object cached = context.getSharedData( SHARED_DEBRIS_VOLUME_FLOOR );
		if ( cached instanceof double[] )
			return ( double[] ) cached;
		final List< Double > volumes = new ArrayList<>();
		final List< Double > heterogeneity = new ArrayList<>();
		final List< double[] > confirmed = new ArrayList<>();
		final ModelGraph graph = context.getGraph();
		graph.getLock().readLock().lock();
		try
		{
			final double[][] cov = new double[ 3 ][ 3 ];
			final double[] pos = new double[ 3 ];
			for ( final Spot spot : graph.vertices() )
				if ( ProtectedSpots.isProtected( context, context.getModel(), spot ) )
				{
					spot.getCovariance( cov );
					volumes.add( spotVolume( cov ) );
					spot.localize( pos );
					confirmed.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ],
							SpotShape.interiorRadius( cov, context.isVolumetric(),
									Math.sqrt( spot.getBoundingSphereRadiusSquared() ) ),
							spot.getTimepoint() } );
				}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		// Sampled OUTSIDE the graph lock: each reads the image, which is slow and must not block edits.
		final List< Double > totalSignal = new ArrayList<>();
		final List< Double > intensities = new ArrayList<>();
		for ( int i = 0; i < confirmed.size(); i++ )
		{
			final double[] c = confirmed.get( i );
			final double[] profile = AutoCorrectOrchestrator.interiorIntensityProfile( context,
					( int ) c[ 4 ], new double[] { c[ 0 ], c[ 1 ], c[ 2 ] }, c[ 3 ] );
			if ( profile == null )
				continue;
			heterogeneity.add( profile[ 0 ] );
			totalSignal.add( profile[ 1 ] * volumes.get( i ) );
			intensities.add( profile[ 1 ] );
		}
		final double[] out = {
				volumePercentile( volumes, DEBRIS_VOLUME_PERCENTILE ),
				volumes.size(),
				volumePercentile( heterogeneity, DEBRIS_HETEROGENEITY_PERCENTILE ),
				volumePercentile( totalSignal, DEBRIS_TOTAL_SIGNAL_PERCENTILE ),
				volumePercentile( volumes, DEBRIS_VOLUME_CEILING_PERCENTILE ),
				volumePercentile( intensities, DEBRIS_INTENSITY_PERCENTILE ) };
		context.setSharedData( SHARED_DEBRIS_VOLUME_FLOOR, out );
		return out;
	}

	/**
	 * The {@code p}th percentile of {@code volumes}, or {@code 0} — the filter off — when there are too
	 * few to describe a distribution.
	 */
	static double volumePercentile( final java.util.List< Double > volumes, final int p )
	{
		if ( volumes.size() < MIN_CONFIRMED_SPOTS_FOR_DEBRIS_FLOOR )
			return 0.0;
		final java.util.List< Double > sorted = new ArrayList<>( volumes );
		java.util.Collections.sort( sorted );
		return sorted.get( Math.min( sorted.size() - 1, sorted.size() * p / 100 ) );
	}

	/**
	 * Confirmed spots needed before their volumes describe a distribution rather than a handful of
	 * examples. Below this the filter stays off rather than cutting against a percentile of nothing.
	 */
	private static final int MIN_CONFIRMED_SPOTS_FOR_DEBRIS_FLOOR = 50;

	/**
	 * Deletes detections whose ellipsoid volume is below {@code volumeFloor} — debris, which in this
	 * dataset is small and misshapen where cells are large and round.
	 *
	 * <p>Bounded by {@link ExpectedCellCount}, smallest first, and what the bound refuses is counted
	 * into {@code blocked} rather than dropped silently: a filter anchored to confirmed cells and a
	 * floor anchored to a stated cell count are two statements from the same person, and where they
	 * disagree that is worth saying out loud instead of letting whichever runs last win.</p>
	 *
	 * @param blocked {@code [spotsRefused, timepointsWhereRefused]}, accumulated
	 * @return number of spots deleted
	 */
	/**
	 * Tags every spot in {@code flagged} that is NOT in {@code deleted} as
	 * {@link DetectionTagLabels#UNLABELED}, so the next training round neither teaches it as a cell nor
	 * declares it background.
	 *
	 * @return how many were tagged
	 */
	/**
	 * Fewest frames a co-located pair must recur in before its weaker member is kept out of training.
	 *
	 * <p>Lower than {@code COLOCATED_MIN_FRAMES}, deliberately, because this is a different decision
	 * with a different cost. Merging deletes a cell if it is wrong; withholding costs one training
	 * example and leaves both spots in the graph for linking. The bar can therefore sit where the
	 * evidence is merely good, and it has to: a window is ten timepoints and the merge rule needs
	 * twelve.</p>
	 */
	private static final int COLOCATED_WITHHOLD_MIN_FRAMES = 5;

	/**
	 * Keeps the weaker member of a persistently co-located pair out of the training annotations.
	 *
	 * <p>Over-segmentation is not only a cosmetic problem in the result: a cell detected twice and then
	 * TAUGHT as two cells teaches the detector to split cells, and the next round produces more of
	 * them. The merge pass that removes such pairs needs finished tracks and twelve timepoints, so it
	 * runs in Phase 2 and cannot help here — by then the model has already learned from them. This
	 * catches the same pairs early enough to keep them out of the labels.</p>
	 *
	 * <p>There are no links yet: annotation is {@code [2/6]} and linking is {@code [4/6]}. So pairs are
	 * followed across frames by their own midpoint — a pair at {@code t+1} continues a pair at
	 * {@code t} when their midpoints are within one radius and their separations agree to within the
	 * measured drift. Cells move far less than a radius between frames, so this is a weaker version of
	 * the same evidence, which is all that is needed for a decision that deletes nothing.</p>
	 *
	 * @return spots withheld
	 */
	/**
	 * Withholds from training every detection that the image does not support as a cell.
	 *
	 * <p>The one that mattered. {@code ElephantIntegration} treats any spot not tagged otherwise as a
	 * training POSITIVE, so a background detection that survives the cleanup passes is taught to the
	 * model as a cell and the next round finds more of them. Reported repeatedly as small dim
	 * detections sitting on flat background while bright nuclei in the same crop carried none.</p>
	 *
	 * <p>Two features decide it -- how far the IMAGE stands above its own noise at the detection's
	 * position, and how big the detection is -- gated jointly rather than by a chain of independent
	 * one-dimensional thresholds. See {@link DetectionGate} for the measurement, including why the
	 * brightness axis has to be read from the image rather than from the spot's own covariance.</p>
	 *
	 * <p>Rejected spots are withheld ({@link DetectionTagLabels#UNLABELED}), never tagged {@code fp}.
	 * 5.5% of real cells fall below the gate, and teaching those as background is precisely the
	 * failure that removed the previous reclassification passes. Withholding asserts neither.</p>
	 *
	 * <p>Protected spots are excluded: a cell the user confirmed is evidence about the specimen, not a
	 * candidate for a threshold to overrule.</p>
	 *
	 * @return spots withheld
	 */
	/**
	 * Everything that must happen to the graph's own detections before they are taught to the model.
	 *
	 * <p>This exists because it was applied at ONE of the training sites. {@code [2/6]} sized the
	 * annotations, withheld what the image does not support as a cell, withheld the co-located pairs
	 * and capped the count; {@code [5b/6]} refinement, which retrains on the ENRICHED detections a
	 * round later, did none of it. Observed: a window stable at 53 spots/tp through detection,
	 * linking and correction, then refinement retrained ungated and the next prediction went to 146
	 * at t=0 and 69.4 spots/tp overall, tripping the ceiling and throwing the whole window away.
	 * Refinement is the MORE dangerous of the two, not the less — it trains on detections that have
	 * already been enriched by gap-filling and predecessor recovery.</p>
	 *
	 * <p>Order matters. The extent correction sizes what is taught; the detection gate then decides
	 * what is a cell at all; the co-located pass removes one cell taught as two; the cap decides how
	 * many of what is left to teach.</p>
	 *
	 * @return detections withheld by the stated-count cap, for the caller's log line
	 */
	/**
	 * Fewest of a window's detections that must carry a contour before this gate is allowed to act.
	 *
	 * <p>Below it the fit is not describing this movie — phase contrast, a badly wrong extent, a frame
	 * the sampler could not read — and withholding what it does not support would withhold nearly
	 * everything. The gate stands down and says so, which is the difference between a guard and a
	 * cliff.</p>
	 */
	private static final double CONTOUR_SUPPORT_MIN_FRACTION = 0.5;

	/**
	 * Withholds from training every detection the contour fit did not support.
	 *
	 * <p>The user's observation, and the measurement that backs it. A person annotating does not label
	 * every detection; they label the ones they can see are cells. Measured against CTC silver truth
	 * on 600 real windows, the fit succeeds on 83% of detections covering a real cell and 7% of those
	 * covering none — a detection on background has no outline, and refusing is the fit SAYING so:</p>
	 *
	 * <pre>
	 *   taught set                        on a cell   on nothing   false share
	 *   every detection                        416         184        30.7%
	 *   only where the contour fitted          346          12         3.4%
	 * </pre>
	 *
	 * <p>83.2% of the real cells are still taught and 93.5% of the false labels are gone. The share of
	 * training labels sitting on nothing falls ninefold. Withheld, never tagged {@code fp}: an outline
	 * hidden by a neighbour is not evidence of background, and {@code fp} carries ten times the weight
	 * of a positive.</p>
	 *
	 * <p>Protected spots are the user's own and are never withheld, whatever the fit thinks.</p>
	 *
	 * @return how many were withheld
	 */
	private static int withholdWithoutContourSupport( final ToolContext context,
			final int[] timepoints )
	{
		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();
		// Modifies the tag-set STRUCTURE, so before the vertex write lock.
		final TagSet tagSet = DetectionTagLabels.ensure( model );
		final Tag unlabeled = DetectionTagLabels.unlabeledTag( tagSet );
		if ( unlabeled == null )
			return 0;
		final List< Spot > unsupported = new ArrayList<>();
		int examined = 0, supported = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				boolean inRange = false;
				for ( final int t : timepoints )
					if ( t == spot.getTimepoint() ) { inRange = true; break; }
				if ( !inRange || !ContourSupport.has( graph, spot ) )
					continue;
				examined++;
				if ( ContourSupport.supported( graph, spot ) )
				{
					supported++;
					continue;
				}
				if ( ProtectedSpots.isProtected( context, model, spot ) )
					continue;
				final Spot ref = graph.vertexRef();
				ref.refTo( spot );
				unsupported.add( ref );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		try
		{
			if ( examined == 0 )
				return 0;
			final double fraction = ( double ) supported / examined;
			if ( fraction < CONTOUR_SUPPORT_MIN_FRACTION )
			{
				context.log( String.format(
						"  Contour support: NOT withholding — only %.0f%% of %d detection(s) carried an "
								+ "outline the fit could follow, under the %.0f%% this gate needs. Below "
								+ "that the fit is not describing this movie and withholding what it does "
								+ "not support would withhold nearly everything.",
						fraction * 100, examined, CONTOUR_SUPPORT_MIN_FRACTION * 100 ) );
				return 0;
			}
			if ( unsupported.isEmpty() )
				return 0;
			final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot spot : unsupported )
					if ( !GraphWatch.dirty( graph, spot ) ) // still the spot collected; see GraphWatch
						vertexTags.set( spot, unlabeled );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			context.log( String.format(
					"  Withheld %d of %d detection(s) from training because the image's own outline did "
							+ "not stand behind them — a person labels the cells they can SEE, not every "
							+ "detection. Measured against CTC silver truth, teaching only what the "
							+ "contour supports keeps 83.2%% of the real cells and removes 93.5%% of the "
							+ "labels sitting on nothing (30.7%% of the taught set to 3.4%%). Withheld, "
							+ "NOT tagged fp: an outline hidden by a neighbour is not evidence of "
							+ "background, and fp carries ten times the weight of a positive. They stay "
							+ "in the graph for linking.",
					unsupported.size(), examined ) );
			return unsupported.size();
		}
		finally
		{
			for ( final Spot ref : unsupported )
				graph.releaseRef( ref );
		}
	}

	/**
	 * Overlap above which two annotations are one cell labelled twice.
	 *
	 * <p>Measured over every overlapping pair in a frame, labelled by whether both sit on the same CTC
	 * truth cell ({@code benchmark/annotation-extent}):</p>
	 *
	 * <pre>
	 *   overlapping pair          IoU p10    p50    p90
	 *   same cell (444)              0.87   0.98   1.00
	 *   different cells (29)         0.00   0.02   0.53
	 *
	 *   merge when IoU >    same-cell pairs caught    different-cell pairs caught
	 *        0.15                   100.0%                     13.8%
	 *        0.50                   100.0%                     10.3%
	 *        0.65                    99.5%                     10.3%
	 * </pre>
	 *
	 * <p>0.5 is where the same-cell pairs are all caught and the count of wrongly-caught ones stops
	 * falling. Only 29 different-cell pairs were available, so the 10.3% is three of them and its
	 * error bar is wide.</p>
	 */
	private static final double DUPLICATE_ANNOTATION_OVERLAP = 0.5;

	/**
	 * Withholds the second label when two annotations describe one cell.
	 *
	 * <p>The duplicates in the overlay are made by the training set, not cleaned up by post-processing.
	 * ELEPHANT learns CENTRES — the loss has a Center Dice term — so two overlapping labels on one
	 * nucleus teach it that this nucleus has two centres, and every round compounds it. A person
	 * annotating puts ONE label on one cell; that is the whole of it.</p>
	 *
	 * <p><b>This does not merge anything.</b> Post-processing merges are off on the user's own
	 * instruction and stay off: both spots remain in the graph and both are linked. Only the label is
	 * withheld. The two actions carry very different risks for the same decision — a wrong merge
	 * destroys a cell the detector found, a wrong withhold means one real cell goes untaught this
	 * round — which is why a rule whose false rate is too high to merge on is fine to withhold on.</p>
	 *
	 * <p>Whichever of the pair the image vouches for more is kept, by contour support. Greedy from the
	 * best downward, so three labels on one cell leave one rather than two.</p>
	 *
	 * @return how many were withheld
	 */
	private static int withholdOverlappingAnnotations( final ToolContext context,
			final int[] timepoints )
	{
		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();
		final TagSet tagSet = DetectionTagLabels.ensure( model );
		final Tag unlabeled = DetectionTagLabels.unlabeledTag( tagSet );
		if ( unlabeled == null )
			return 0;
		final List< Spot > withhold = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final int t : timepoints )
			{
				// { x, y, a, b, theta, support }, best-supported first.
				final List< double[] > kept = new ArrayList<>();
				final List< Spot > candidates = new ArrayList<>();
				final List< double[] > shapes = new ArrayList<>();
				for ( final Spot spot : graph.vertices() )
				{
					if ( spot.getTimepoint() != t )
						continue;
					final String label = DetectionTagLabels.labelOf( model, spot );
					if ( DetectionTagLabels.UNLABELED.equals( label )
							|| DetectionTagLabels.FP.equals( label ) )
						continue; // already withheld, or a negative — not a cell label
					spot.getCovariance( cov );
					final double[] e = SpotShape.inPlaneEllipse( cov );
					if ( e == null )
						continue;
					spot.localize( pos );
					final Spot ref = graph.vertexRef();
					ref.refTo( spot );
					candidates.add( ref );
					shapes.add( new double[] { pos[ 0 ], pos[ 1 ], e[ 0 ], e[ 1 ], e[ 2 ],
							ContourSupport.supportOf( graph, spot ) } );
				}
				final Integer[] order = new Integer[ candidates.size() ];
				for ( int i = 0; i < order.length; i++ )
					order[ i ] = i;
				java.util.Arrays.sort( order,
						( x, y ) -> Double.compare( shapes.get( y )[ 5 ], shapes.get( x )[ 5 ] ) );
				for ( final int i : order )
				{
					final double[] mine = shapes.get( i );
					boolean duplicate = false;
					for ( final double[] other : kept )
					{
						if ( Math.hypot( mine[ 0 ] - other[ 0 ], mine[ 1 ] - other[ 1 ] )
								> mine[ 2 ] + other[ 2 ] )
							continue; // cannot overlap at all
						if ( EllipseFit.overlap( mine, other ) > DUPLICATE_ANNOTATION_OVERLAP )
						{
							duplicate = true;
							break;
						}
					}
					if ( !duplicate )
					{
						kept.add( mine );
						continue;
					}
					final Spot spot = candidates.get( i );
					if ( !ProtectedSpots.isProtected( context, model, spot ) )
						withhold.add( spot );
				}
				for ( int i = 0; i < candidates.size(); i++ )
					if ( !withhold.contains( candidates.get( i ) ) )
						graph.releaseRef( candidates.get( i ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		try
		{
			if ( withhold.isEmpty() )
				return 0;
			final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot spot : withhold )
					if ( !GraphWatch.dirty( graph, spot ) ) // still the spot collected; see GraphWatch
						vertexTags.set( spot, unlabeled );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			context.log( String.format(
					"  Withheld %d annotation(s) that describe a cell another annotation already "
							+ "describes — one label per cell, as a person would put it. ELEPHANT learns "
							+ "CENTRES, so two overlapping labels on one nucleus teach it that nucleus "
							+ "has two, and every round compounds it. Measured on fitted ellipses "
							+ "against CTC silver truth, a pair on the SAME cell overlaps at 0.98 and a "
							+ "pair on two cells at 0.02, so above %.2f catches all of the first for "
							+ "about 10%% of the second. NOTHING IS MERGED: both spots stay in the graph "
							+ "and both are linked, only the label is withheld — a wrong merge destroys a "
							+ "cell, a wrong withhold costs one round's teaching of it.",
					withhold.size(), DUPLICATE_ANNOTATION_OVERLAP ) );
			return withhold.size();
		}
		finally
		{
			for ( final Spot spot : withhold )
				graph.releaseRef( spot );
		}
	}

	/**
	 * Withholds detections from the most crowded brightness x size groups of the window's taught set;
	 * see {@link TrainingBalance}. fn corrections, fp negatives and protected spots are never touched.
	 *
	 * @return how many were withheld
	 */
	private static int balanceTrainingByType( final ToolContext context, final int[] timepoints )
	{
		if ( !TrainingBalance.enabled( context ) || timepoints.length == 0 )
			return 0;
		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();
		final List< Spot > taught = new ArrayList<>();
		final List< double[] > where = new ArrayList<>();
		final List< Double > sizes = new ArrayList<>();
		final Set< Integer > window = new HashSet<>();
		for ( final int t : timepoints )
			window.add( t );
		graph.getLock().readLock().lock();
		try
		{
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( !window.contains( spot.getTimepoint() ) )
					continue;
				final String label = DetectionTagLabels.labelOf( model, spot );
				if ( DetectionTagLabels.UNLABELED.equals( label ) || DetectionTagLabels.FP.equals( label )
						|| DetectionTagLabels.FN.equals( label ) || ProtectedSpots.isProtected( context, model, spot ) )
					continue;
				spot.getCovariance( cov );
				final double[] e = SpotShape.inPlaneEllipse( cov );
				if ( e == null )
					continue;
				final double[] p = new double[ 4 ];
				spot.localize( p );
				p[ 3 ] = spot.getTimepoint();
				taught.add( graph.vertexRef().refTo( spot ) );
				where.add( p );
				sizes.add( Math.sqrt( e[ 0 ] * e[ 1 ] ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		try
		{
			final int n = taught.size();
			final double[] brightness = new double[ n ], size = new double[ n ];
			for ( int i = 0; i < n; i++ )
			{
				final double[] p = where.get( i );
				brightness[ i ] = AutoCorrectOrchestrator.standoutAboveBackground( context, ( int ) p[ 3 ],
						new double[] { p[ 0 ], p[ 1 ], p[ 2 ] } );
				size[ i ] = sizes.get( i );
			}
			final int[] drop = TrainingBalance.toWithhold( brightness, size, timepoints[ 0 ] );
			if ( drop.length == 0 )
				return 0;
			final List< Spot > flagged = new ArrayList<>( drop.length );
			for ( final int i : drop )
				flagged.add( taught.get( i ) );
			final int withheld = withholdFromTraining( context, flagged, java.util.Collections.emptyList() );
			context.log( String.format( "  Balanced training across cell types: withheld %d of %d taught "
					+ "detection(s), all from the most crowded brightness x size groups, so dim, small and large "
					+ "cells are a larger share of what is taught (an experiment -- untick 'Balance training "
					+ "across cell types' in the pre-flight dialog to compare). They stay in the graph and "
					+ "are linked.", withheld, n ) );
			return withheld;
		}
		finally
		{
			for ( final Spot spot : taught )
				graph.releaseRef( spot );
		}
	}

	private static int prepareAnnotationsForTraining( final ToolContext context, final int[] timepoints )
	{
		// What the graph says BEFORE any of this touches it. Every phase that trains comes through
		// here, so one line covers [2/6], [5b/6] and the crop warm-up. Paired with the line
		// buildSpotsJson prints at send time, it brackets the preparation: the two agreeing means the
		// tags were already like that, and disagreeing names this method as where they changed. Added
		// because a run reported ('tp', 0), ('fn', 59) on every training frame with no way to tell
		// which side of this boundary it happened on.
		context.log( "    annotations in: "
				+ DetectionTagLabels.tally( context.getModel(), context.getGraph(), timepoints ) );
		restoreAnnotationExtent( context, timepoints );
		// Before everything else that withholds: this is the one that decides whether a detection is a
		// cell at all. The passes after it refine WHICH cells to teach.
		withholdBelowDetectionGate( context, timepoints );
		// Then: teach only what the image's own outline stood behind. A person annotating this movie
		// does not label every detection — they keep the ones they can SEE are cells. The contour fit
		// already makes that judgement and, until now, nothing used it.
		withholdWithoutContourSupport( context, timepoints );
		// And never two labels on one cell. This is the one that makes the duplicates in the overlay
		// stop being produced rather than be cleaned up afterwards.
		withholdOverlappingAnnotations( context, timepoints );
		// Before the cap: a cell detected twice and taught as two cells teaches the detector to split
		// cells, and every later round compounds it.
		final int coLocatedWithheld = withholdCoLocatedFromTraining( context, timepoints );
		if ( coLocatedWithheld > 0 )
			context.log( String.format(
					"  Withheld %d detection(s) from training that recur as a steady sub-cell-spaced "
							+ "pair across %d+ frames — one cell detected twice. They stay in the graph "
							+ "for linking; Phase 2's whole-movie pass is what actually merges them.",
					coLocatedWithheld, COLOCATED_WITHHOLD_MIN_FRAMES ) );
		// The user's experiment: thin the most crowded kinds of cell so dim, small and large ones are a
		// larger share of what is taught. See TrainingBalance.
		balanceTrainingByType( context, timepoints );
		final int overTaught = capAnnotationsToStatedCount( context, timepoints );
		if ( overTaught > 0 )
			context.log( String.format(
					"  Withheld %d detection(s) from training so the model is taught about %d cell(s)/"
							+ "frame, as you stated, rather than every detection. The ones kept are %s; "
							+ "the rest stay in the graph for linking.",
					overTaught, ExpectedCellCount.statedCellsPerFrame( context ),
					confirmedCellProfile( context ) != null
							? "those closest to your confirmed cells in size, roundness and brightness"
							: "those standing furthest above this frame's background — nothing is "
									+ "confirmed in this project, so that is the evidence available" ) );
		context.log( "    annotations out: "
				+ DetectionTagLabels.tally( context.getModel(), context.getGraph(), timepoints ) );
		return overTaught;
	}

	/**
	 * Tags detections {@code fp}, so they are trained as background, and strips their links.
	 *
	 * <p>An fp spot is a training negative, not a detection, and must never be part of a track —
	 * {@code runTopologyConsistency} strips such edges movie-wide, but that runs later and this can
	 * be called from the refinement round, where links already exist.</p>
	 */
	private static int tagAsBackgroundNegative( final ToolContext context, final List< Spot > spots )
	{
		if ( spots.isEmpty() )
			return 0;
		final Model model = context.getModel();
		// Modifies the tag-set STRUCTURE, so it happens before the vertex write lock.
		final TagSet tagSet = DetectionTagLabels.ensure( model );
		final Tag fp = DetectionTagLabels.fpTag( tagSet );
		if ( fp == null )
			return 0;
		final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();
		final ModelGraph graph = context.getGraph();
		int tagged = 0;
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot spot : spots )
			{
				if ( ProtectedSpots.isProtected( context, model, spot ) )
					continue;
				if ( GraphWatch.dirty( graph, spot ) ) // collected under an earlier lock; see GraphWatch
				{
					GraphWatch.refused( graph );
					continue;
				}
				final List< Link > links = new ArrayList<>();
				for ( final Link e : spot.incomingEdges() )
					links.add( graph.edgeRef().refTo( e ) );
				for ( final Link e : spot.outgoingEdges() )
					links.add( graph.edgeRef().refTo( e ) );
				for ( final Link e : links )
				{
					GraphEdits.removeLink( context, graph, e, GraphEdits.Reason.TOPOLOGY );
					graph.releaseRef( e );
				}
				vertexTags.set( spot, fp );
				tagged++;
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( tagged > 0 )
			context.notifyGraphChanged();
		return tagged;
	}

	/** Detections smaller than this many in-plane voxels have no extent to describe. */
	private static final double SUB_CELLULAR_VOXELS = 2.0;

	/** Shared-data key: the detection gate has said once this run that it cannot measure standout. */
	private static final String SHARED_GATE_UNMEASURABLE_LOGGED = "detection_gate.unmeasurable_logged";

	private static int withholdBelowDetectionGate( final ToolContext context, final int[] timepoints )
	{
		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();
		final List< Spot > candidates = new ArrayList<>();
		final List< double[] > where = new ArrayList<>(); // x, y, z, t
		final List< Double > radii = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final int t : timepoints )
			{
				for ( final Spot spot : graph.vertices() )
				{
					if ( spot.getTimepoint() != t )
						continue;
					final String label = DetectionTagLabels.labelOf( model, spot );
					if ( DetectionTagLabels.FP.equals( label )
							|| DetectionTagLabels.UNLABELED.equals( label ) )
						continue; // already not being taught as a cell
					if ( ProtectedSpots.isProtected( context, model, spot ) )
						continue;
					final double[] p = new double[ 4 ];
					spot.localize( p );
					p[ 3 ] = t;
					final Spot ref = graph.vertexRef();
					ref.refTo( spot );
					candidates.add( ref );
					where.add( p );
					radii.add( Math.sqrt( spot.getBoundingSphereRadiusSquared() ) );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( candidates.isEmpty() )
			return 0;
		// How far a detection stands above the background is measured in the background's own spread. Where
		// the background has none (exactly 0 around a sparse mouse embryo), every detection reads 0, and the
		// gate used to report "the detector is not producing cells" and ask the user -- about cells it could
		// not see, not about the detector. The detections are taught as they are, as before the gate existed.
		boolean measurable = false;
		for ( final int t : timepoints )
			measurable |= AutoCorrectOrchestrator.standoutMeasurable( context, t );
		if ( !measurable )
		{
			for ( final Spot ref : candidates )
				graph.releaseRef( ref );
			if ( context.getSharedData( SHARED_GATE_UNMEASURABLE_LOGGED ) == null )
			{
				context.setSharedData( SHARED_GATE_UNMEASURABLE_LOGGED, Boolean.TRUE );
				context.log( "  Detection gate OFF: the background around the detections has no spread (it is exactly "
						+ "constant, e.g. 0), so how far a detection stands above it cannot be measured. Detections are "
						+ "taught as they are, as before the gate existed; said once per run." );
			}
			return 0;
		}

		// The image is read with NO lock held: one read per detection, and under the lock every edit in
		// the viewer waited for all of them. The verdicts land through withholdFromTraining and
		// tagAsBackgroundNegative, which leave alone a spot changed in the viewer meanwhile (GraphWatch).
		final double[] s = new double[ candidates.size() ];
		final double[] r = new double[ candidates.size() ];
		for ( int i = 0; i < candidates.size(); i++ )
		{
			final double[] p = where.get( i );
			s[ i ] = AutoCorrectOrchestrator.standoutAboveBackground( context, ( int ) p[ 3 ],
					new double[] { p[ 0 ], p[ 1 ], p[ 2 ] } );
			r[ i ] = radii.get( i );
		}
		// A detection with less extent than a voxel is a noise peak, not a body. Two voxels rather
		// than one so a single bright pixel and its immediate neighbour cannot pass; measured
		// background peaks here sit at 0.5 px, so this rejects all of them.
		final double voxel = ImageBounds.inPlaneVoxelSize( context );
		final DetectionGate.Gate gate = DetectionGate.learn( s, r,
				Double.isNaN( voxel ) ? 0.0 : SUB_CELLULAR_VOXELS * voxel );
		int withheld = 0;
		try
		{
			if ( !gate.decided )
			{
				// Not a threshold's decision to make. Say what was seen and let a person look.
				context.log( "  Detection gate could not decide: " + gate.reason + "." );
				askAboutUngatableDetections( context, gate, candidates.size() );
				return 0;
			}
			final List< Spot > rejected = new ArrayList<>();
			final List< Spot > background = new ArrayList<>();
			for ( int i = 0; i < candidates.size(); i++ )
			{
				if ( !gate.admits( s[ i ], r[ i ] ) )
				{
					// Two different verdicts, and they were being collapsed into one. A detection just
					// under the gate is UNSUPPORTED — it might be a dim cell, so it is withheld and
					// nothing is claimed about it. One deep below the gate is BACKGROUND, and saying so
					// is the negative the model needs: measured over 8602 truth cells, none stands
					// below 1.75 sigma, so at 1.5 sigma this claims background and catches no cell.
					( gate.isBackground( s[ i ] ) ? background : rejected ).add( candidates.get( i ) );
				}
			}
			if ( rejected.isEmpty() && background.isEmpty() )
				return 0;
			withheld = withholdFromTraining( context, rejected, Collections.emptyList() );
			final int negatives = tagAsBackgroundNegative( context, background );
			context.log( String.format(
					"  Of %d detection(s), %d are below %.0f sigma above this frame's background or "
							+ "smaller than radius %.2f. %d of those read below %.1f sigma — background by "
							+ "measurement, not merely unsupported — and are tagged fp so the model is "
							+ "taught to stop finding them. The other %d are withheld without a claim, "
							+ "because a dim real cell taught as background is the worse error. %s",
					candidates.size(), withheld + negatives, gate.minStandout, gate.minRadius,
					negatives, DetectionGate.DEEP_BACKGROUND_SIGMA, withheld, gate.reason ) );
			return withheld + negatives;
		}
		finally
		{
			for ( final Spot ref : candidates )
				graph.releaseRef( ref );
		}
	}

	/**
	 * Asks the user what to do when the detection gate has nothing to separate.
	 *
	 * <p>Only reached when the population gives no signal at all, which means the detector is not
	 * producing cells rather than that the gate picked a bad threshold. Silent in headless runs.</p>
	 */
	private static void askAboutUngatableDetections( final ToolContext context,
			final DetectionGate.Gate gate, final int population )
	{
		if ( !context.canAskUser() )
		{
			context.log( "  (No UI attached — continuing without gating this round. The detections "
					+ "will be taught as cells, which is what to look at if the result degrades.)" );
			return;
		}
		final String answer = context.askUser( String.format(
				"The detection gate found nothing cell-like among %d detection(s): %s.%n%n"
						+ "Teaching these to the model would train it on background. How should this "
						+ "round proceed?",
				population, gate.reason ),
				java.util.Arrays.asList(
						"Skip training this round (recommended)",
						"Withhold all of them from training",
						"Train on them anyway" ) );
		context.log( "  Detection gate — user chose: " + ( answer == null ? "(no answer)" : answer ) );
	}

	private static int withholdCoLocatedFromTraining( final ToolContext context, final int[] timepoints )
	{
		if ( timepoints.length < COLOCATED_WITHHOLD_MIN_FRAMES )
			return 0;
		final ModelGraph graph = context.getGraph();
		// Per timepoint: every pair close enough to be one cell, as { midX, midY, midZ, sepInRadii }
		// alongside the two spots.
		final List< List< Object[] > > perTp = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final int t : timepoints )
			{
				final List< Spot > here = new ArrayList<>();
				for ( final Spot spot : graph.vertices() )
					if ( spot.getTimepoint() == t )
						here.add( graph.vertexRef().refTo( spot ) );
				final List< Object[] > pairs = new ArrayList<>();
				final double[] pa = new double[ 3 ], pb = new double[ 3 ];
				for ( int i = 0; i < here.size(); i++ )
					for ( int j = i + 1; j < here.size(); j++ )
					{
						final Spot a = here.get( i ), b = here.get( j );
						a.localize( pa );
						b.localize( pb );
						final double rA = spotRadiusOf( a ), rB = spotRadiusOf( b );
						final double r = Math.max( 1e-9, Math.max( rA, rB ) );
						final double d = Math.sqrt( ( pa[ 0 ] - pb[ 0 ] ) * ( pa[ 0 ] - pb[ 0 ] )
								+ ( pa[ 1 ] - pb[ 1 ] ) * ( pa[ 1 ] - pb[ 1 ] )
								+ ( pa[ 2 ] - pb[ 2 ] ) * ( pa[ 2 ] - pb[ 2 ] ) ) / r;
						if ( d >= AutoCorrectOrchestrator.COLOCATED_MAX_SEPARATION )
							continue;
						pairs.add( new Object[] {
								new double[] { ( pa[ 0 ] + pb[ 0 ] ) / 2, ( pa[ 1 ] + pb[ 1 ] ) / 2,
										( pa[ 2 ] + pb[ 2 ] ) / 2, d, r },
								rA >= rB ? b : a } ); // the weaker one, which is what gets withheld
					}
				perTp.add( pairs );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		// Chain pairs across consecutive frames by midpoint, and collect the weaker member of every
		// chain that recurs for long enough.
		final List< Spot > toWithhold = new ArrayList<>();
		final boolean[][] used = new boolean[ perTp.size() ][];
		for ( int i = 0; i < perTp.size(); i++ )
			used[ i ] = new boolean[ perTp.get( i ).size() ];
		for ( int t0 = 0; t0 + COLOCATED_WITHHOLD_MIN_FRAMES <= perTp.size(); t0++ )
			for ( int p0 = 0; p0 < perTp.get( t0 ).size(); p0++ )
			{
				if ( used[ t0 ][ p0 ] )
					continue;
				final List< Spot > chain = new ArrayList<>();
				final List< Integer > idx = new ArrayList<>();
				double[] cur = ( double[] ) perTp.get( t0 ).get( p0 )[ 0 ];
				chain.add( ( Spot ) perTp.get( t0 ).get( p0 )[ 1 ] );
				idx.add( p0 );
				int t = t0;
				while ( t + 1 < perTp.size() )
				{
					int best = -1;
					double bestD = Double.MAX_VALUE;
					for ( int q = 0; q < perTp.get( t + 1 ).size(); q++ )
					{
						if ( used[ t + 1 ][ q ] )
							continue;
						final double[] nxt = ( double[] ) perTp.get( t + 1 ).get( q )[ 0 ];
						if ( Math.abs( nxt[ 3 ] - cur[ 3 ] ) >= AutoCorrectOrchestrator.COLOCATED_MAX_DRIFT )
							continue;
						final double move = Math.sqrt( ( nxt[ 0 ] - cur[ 0 ] ) * ( nxt[ 0 ] - cur[ 0 ] )
								+ ( nxt[ 1 ] - cur[ 1 ] ) * ( nxt[ 1 ] - cur[ 1 ] )
								+ ( nxt[ 2 ] - cur[ 2 ] ) * ( nxt[ 2 ] - cur[ 2 ] ) );
						if ( move < cur[ 4 ] && move < bestD )
						{
							bestD = move;
							best = q;
						}
					}
					if ( best < 0 )
						break;
					t++;
					cur = ( double[] ) perTp.get( t ).get( best )[ 0 ];
					chain.add( ( Spot ) perTp.get( t ).get( best )[ 1 ] );
					idx.add( best );
				}
				if ( chain.size() >= COLOCATED_WITHHOLD_MIN_FRAMES )
				{
					for ( int k = 0; k < chain.size(); k++ )
						used[ t0 + k ][ idx.get( k ) ] = true;
					toWithhold.addAll( chain );
				}
			}
		return toWithhold.isEmpty() ? 0
				: withholdFromTraining( context, toWithhold, java.util.Collections.emptyList() );
	}

	/** Radius from a spot's covariance, as the merge pass measures it. */
	private static double spotRadiusOf( final Spot spot )
	{
		final double[][] cov = new double[ 3 ][ 3 ];
		spot.getCovariance( cov );
		return Math.sqrt( Math.max( 0.0, ( cov[ 0 ][ 0 ] + cov[ 1 ][ 1 ] + cov[ 2 ][ 2 ] ) / 3.0 ) );
	}

	private static int withholdFromTraining( final ToolContext context, final List< Spot > flagged,
			final List< Spot > deleted )
	{
		if ( flagged.isEmpty() )
			return 0;
		final Model model = context.getModel();
		// Modifies the tag-set STRUCTURE, so it has to happen before the vertex write lock.
		final TagSet tagSet = DetectionTagLabels.ensure( model );
		final Tag unlabeled = DetectionTagLabels.unlabeledTag( tagSet );
		if ( unlabeled == null )
			return 0;
		final Set< Integer > deletedIds = new HashSet<>();
		for ( final Spot s : deleted )
			deletedIds.add( s.getInternalPoolIndex() );
		final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();
		final ModelGraph graph = context.getGraph();
		int tagged = 0;
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot s : flagged )
			{
				if ( deletedIds.contains( s.getInternalPoolIndex() ) )
					continue;
				if ( ProtectedSpots.isProtected( context, model, s ) )
					continue; // a confirmed cell is trusted training signal whatever this test thinks
				// Every caller collected these under an earlier lock, most with image reads since: one
				// deleted or replaced in the viewer meanwhile is not the spot that was judged.
				if ( GraphWatch.dirty( graph, s ) )
				{
					GraphWatch.refused( graph );
					continue;
				}
				vertexTags.set( s, unlabeled );
				tagged++;
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		return tagged;
	}

	static int deleteDebrisSpots( final ToolContext context, final int timepoint,
			final double[] thresholds, final int[] blocked, final double[] volumeStats,
			final int[] faintCount )
	{
		final double heterogeneityCeiling = thresholds[ 2 ];
		final double debrisVolumeCeiling = thresholds[ 4 ];
		final double intensityFloor = thresholds[ 5 ];
		// Opt-in and fluorescence-only — see DEBRIS_TOTAL_SIGNAL_PERCENTILE.
		final double totalSignalFloor = CleanupToggles.dimTotalSignalFilterEnabled( context )
				? thresholds[ 3 ] : 0.0;
		if ( !( heterogeneityCeiling > 0.0 ) && !( totalSignalFloor > 0.0 )
				&& !( intensityFloor > 0.0 ) )
			return 0;
		final ModelGraph graph = context.getGraph();
		final List< Spot > candidates = new ArrayList<>();
		final List< double[] > candidatePos = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[][] cov = new double[ 3 ][ 3 ];
			final double[] pos = new double[ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				if ( ProtectedSpots.isProtected( context, context.getModel(), spot ) )
					continue;
				spot.localize( pos );
				spot.getCovariance( cov );
				final Spot ref = graph.vertexRef();
				ref.refTo( spot );
				candidates.add( ref );
				candidatePos.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ],
						SpotShape.interiorRadius( cov, context.isVolumetric(),
								Math.sqrt( spot.getBoundingSphereRadiusSquared() ) ),
						spotVolume( cov ) } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		// Image sampling happens with no lock held, then only the over-threshold spots are kept.
		final List< Spot > flagged = new ArrayList<>();
		final List< Double > flaggedHeterogeneity = new ArrayList<>();
		for ( int i = 0; i < candidates.size(); i++ )
		{
			final double[] c = candidatePos.get( i );
			final double[] profile = AutoCorrectOrchestrator.interiorIntensityProfile( context, timepoint,
					new double[] { c[ 0 ], c[ 1 ], c[ 2 ] }, c[ 3 ] );
			if ( profile == null )
				continue;
			final double h = profile[ 0 ];
			// BOTH halves of the user's description, not either one. "Debris is small AND mixes bright
			// and dark" — on its own, unevenness picked out the LARGEST spots (removed detections
			// averaged up to 2.00x the volume of the ones it cleared) and took two of the frame's
			// thirty-six brightest locations with it, because a big bright cell has visible internal
			// texture and a small blob does not. Requiring the spot to be no bigger than a real cell
			// first is what stops that; it costs nothing on genuine debris, which is small by
			// definition.
			final boolean uneven = heterogeneityCeiling > 0.0 && h > heterogeneityCeiling
					&& c[ 4 ] < debrisVolumeCeiling;
			final boolean tooFaint = totalSignalFloor > 0.0 && profile[ 1 ] * c[ 4 ] < totalSignalFloor;
			// Absolute brightness, independent of contrast, volume and total signal.
			final boolean tooDim = intensityFloor > 0.0 && profile[ 1 ] < intensityFloor;
			if ( uneven || tooFaint || tooDim )
			{
				flagged.add( candidates.get( i ) );
				// Ranked by how far over the evenness ceiling it is; a spot caught only by the total-signal
				// test sorts above every merely-uneven one, since that test is the more direct statement.
				flaggedHeterogeneity.add( tooFaint || tooDim ? Double.MAX_VALUE : h );
				// Flagged, not removed — the floor decides how many of these actually go, so these two
				// counts are reported separately rather than one being described as part of the other.
				if ( tooDim )
					faintCount[ 2 ]++;
				else if ( tooFaint )
					faintCount[ 0 ]++;
				else
					faintCount[ 1 ]++;
				volumeStats[ 0 ] += c[ 4 ];
				volumeStats[ 1 ]++;
			}
			else
			{
				volumeStats[ 2 ] += c[ 4 ];
				volumeStats[ 3 ]++;
			}
		}
		if ( flagged.isEmpty() )
		{
			for ( final Spot s : candidates )
				graph.releaseRef( s );
			return 0;
		}
		// Most heterogeneous first, so a binding floor spends its budget on the least cell-like spots.
		final Integer[] order = new Integer[ flagged.size() ];
		for ( int i = 0; i < order.length; i++ )
			order[ i ] = i;
		java.util.Arrays.sort( order,
				( a, b ) -> Double.compare( flaggedHeterogeneity.get( b ), flaggedHeterogeneity.get( a ) ) );
		final List< Spot > ranked = new ArrayList<>( flagged.size() );
		for ( final int i : order )
			ranked.add( flagged.get( i ) );
		int removed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			removed = ExpectedCellCount.removeRespectingFloor( context, timepoint, ranked, graph );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( removed < ranked.size() )
		{
			blocked[ 0 ] += ranked.size() - removed;
			blocked[ 1 ]++;
			ExpectedCellCount.noteFloorBound( context, timepoint );
			// Kept in the graph because the floor said so, but not taught. The floor governs how many
			// spots a timepoint may LOSE; it has never had anything to say about what the model should
			// be shown, and teaching the model a detection this pass just identified as debris is how
			// the next round comes back with more of it.
			blocked[ 2 ] += withholdFromTraining( context, ranked.subList( removed, ranked.size() ),
					java.util.Collections.emptyList() );
		}
		for ( final Spot s : candidates )
			graph.releaseRef( s );
		if ( removed > 0 )
		{
			context.getModel().setUndoPoint();
			context.notifyGraphChanged();
		}
		return removed;
	}

	/**
	 * Fraction of a timepoint's detections above which {@link #deleteSpotsWithNoSignal} refuses to act.
	 *
	 * <p>Half. The pass asks "does this detection carry any signal at all", and on an image its anchor
	 * fits, the answer is no for a minority — a handful of predictions on empty space. When it is the
	 * majority, the anchor is describing something other than this image's cells, and deleting on it
	 * destroys the window. Deliberately not a tunable: a value that let the pass through at 84% would be
	 * a value that lets it empty a frame, which is the thing being prevented.</p>
	 */
	private static final double NO_SIGNAL_MAX_FRACTION = 0.5;

	/**
	 * Deletes detections whose interior is, on average, no brighter than the background around them.
	 *
	 * <p>Not bounded by the cell-count floor, and that exemption is the point. The floor exists because
	 * the passes that delete are statistical tests against estimates weaker than the count the user
	 * stated — a background baseline from a few dozen samples deciding a detection is "probably" not a
	 * cell. This is not one of those. A detection averaging no more signal than the space around it is
	 * not a marginal call, and the floor was observed protecting detections whose measured intensity
	 * was literally 0 while refusing 189 deletions in a single window.</p>
	 *
	 * <p>Anchored at BOTH ends: this timepoint's own background, and a low percentile of the user's
	 * confirmed cells (see {@link AutoCorrectOrchestrator#noSignalCeiling}). A movie that dims as it
	 * goes is still judged against what its own frames look like, and the confirmed cells supply the
	 * scale that the background alone cannot. That second anchor is why this pass now works on a black
	 * frame: with a background median of zero, "no brighter than the background" only ever caught a
	 * detection reading LITERALLY zero, and everything a grey level above it survived — which is
	 * precisely the near-black detection the user asked to stop seeing.</p>
	 *
	 * @return number deleted
	 */
	/**
	 * Replaces each detection's circle with an ellipse fitted to the cell's contour in the image.
	 *
	 * <p>Measured against CTC silver truth on 600 real windows, running the shipped fitter
	 * ({@code benchmark/annotation-extent}): a circle overlaps its cell at an IoU of 0.74 after the
	 * extent anchor fix, a fitted ellipse at 0.81, and the 10th percentile moves 0.58 to 0.75 — the
	 * worst annotations improve most, which is what matters for a training set. The chain from where
	 * this started is 0.56 to 0.73 to 0.81.</p>
	 *
	 * <p>A detection whose contour does not support an ellipse keeps the circle it had. That is the
	 * normal outcome for one whose cell is hidden by a neighbour, and it is not a failure: measured,
	 * 17% of detections on a real cell are refused, against 93% of those on no cell at all.</p>
	 *
	 * <p>Protected spots are the user's and are never reshaped. The position is left ALONE even
	 * though the fit has an opinion about it: the measurement says an ellipse at the detector's own
	 * centre scores 0.92 against 0.94 perfectly centred, so moving detections buys almost nothing and
	 * would silently invalidate every displacement the linker has already reasoned about.</p>
	 *
	 * @return how many were reshaped; {@code countOut} receives how many were looked at
	 */
	public static int fitDetectionsToContour( final ToolContext context, final int timepoint,
			final int[] countOut )
	{
		if ( !CleanupToggles.shapeFitEnabled( context ) )
			return 0; // the pre-flight switch: detections keep ELEPHANT's own shape
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();
		final List< double[] > seeds = new ArrayList<>();
		final List< Spot > refs = new ArrayList<>();
		final List< Double > inPlane = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				if ( ProtectedSpots.isProtected( context, model, spot ) )
					continue;
				spot.localize( pos );
				final Spot ref = graph.vertexRef();
				ref.refTo( spot );
				refs.add( ref );
				seeds.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ],
						Math.sqrt( spot.getBoundingSphereRadiusSquared() ) } );
				spot.getCovariance( cov );
				final double tr = cov[ 0 ][ 0 ] + cov[ 1 ][ 1 ], det = cov[ 0 ][ 0 ] * cov[ 1 ][ 1 ] - cov[ 0 ][ 1 ] * cov[ 0 ][ 1 ];
				inPlane.add( Math.sqrt( tr / 2 + Math.sqrt( Math.max( 0, tr * tr / 4 - det ) ) ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( countOut != null && countOut.length > 0 )
			countOut[ 0 ] += refs.size();
		// The image reads happen with NO lock held: a window read is by far the slowest thing here and
		// holding a write lock across one would stall every other reader for as long as it takes.
		final boolean volumetricFit = isVolumetric( context );
		// A SAM mask under each detection's centre first, when a fit server is configured (the user's choice,
		// 2026-10-24): against silver truth it beat the contour fit on HeLa (IoU 0.91 vs 0.79), CE (0.84-0.90 where
		// the contour fit could not run at all) and phase contrast (0.71 vs 0.67), and its size follows the cells
		// as they shrink. The contour fit, then the 3-D fit, for every detection SAM gives nothing for.
		EllipseFit.Ellipse[] sam = null;
		final org.mastodon.mamut.automahout.tool.remote.RemoteToolServer samServer =
				refs.isEmpty() ? null : org.mastodon.mamut.automahout.tool.remote.SamFit.find( context );
		if ( samServer != null )
		{
			final double[] radii = new double[ inPlane.size() ];
			for ( int i = 0; i < radii.length; i++ )
				radii[ i ] = inPlane.get( i );
			try
			{
				sam = org.mastodon.mamut.automahout.tool.remote.SamFit.fit( context, samServer, timepoint, seeds, radii );
			}
			catch ( final java.io.IOException | RuntimeException e )
			{
				context.log( "  Shape fit: SAM failed at t=" + timepoint + " (" + e.getMessage() + "); the contour fit instead." );
			}
		}
		final List< EllipseFit.Ellipse > fits = new ArrayList<>( seeds.size() );
		for ( int i = 0; i < seeds.size(); i++ )
		{
			final double[] c = seeds.get( i );
			if ( sam != null && sam[ i ] != null )
			{
				fits.add( sam[ i ] );
				if ( countOut != null && countOut.length > 2 )
					countOut[ 2 ]++;
			}
			else
				fits.add( AutoCorrectOrchestrator.fitEllipseToContour( context, timepoint,
						new double[] { c[ 0 ], c[ 1 ], c[ 2 ] }, c[ 3 ] ) );
		}
		// On a stack, a detection whose outline the plane fit could not trace is fitted in 3-D instead, with
		// Mastodon's ellipsoid fitting: it reads edges, not a level above the background, so it works where
		// the plane fit cannot -- on a background of exactly 0 that was every detection, and they kept
		// ELEPHANT's core size, which training taught back until the spots shrank window after window.
		// Independent per detection, so in parallel; reads go through the source's own cache.
		final EllipsoidFit3D.Fit[] fits3d = new EllipsoidFit3D.Fit[ seeds.size() ];
		if ( volumetricFit )
		{
			final bdv.viewer.Source< ? > source = firstSource( context );
			if ( source != null )
				java.util.stream.IntStream.range( 0, seeds.size() ).parallel().filter( i -> fits.get( i ) == null )
						.forEach( i -> fits3d[ i ] = fit3d( source, timepoint, seeds.get( i ) ) );
			// A detection may never grow over another detection's CENTRE -- the user's standing rule, which the
			// extent pass keeps (neighbourLimit) and this fit did not. Two detections ELEPHANT put on one cell were
			// both fitted to the whole cell and became one spot twice; taught back, they multiplied: on the mouse
			// embryo the same-cell annotations withheld per window went from 2 to 114 by t=140 and to 388 by t=220,
			// and the committed count from 48 to 123 per frame. Such a fit is refused and the detection keeps its
			// shape, as before the 3-D fit existed.
			for ( int i = 0; i < fits3d.length; i++ )
				if ( fits3d[ i ] != null && containsAnotherCentre( fits3d[ i ], seeds, i ) )
					fits3d[ i ] = null;
		}
		int fitted = 0;
		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( int i = 0; i < refs.size(); i++ )
				{
					// The image was read without the lock: a spot deleted or replaced in the viewer
					// meanwhile is not the one fitted, and is left exactly as it is. See GraphWatch.
					if ( GraphWatch.dirty( graph, refs.get( i ) ) )
					{
						GraphWatch.refused( graph );
						continue;
					}
					final EllipseFit.Ellipse e = fits.get( i );
					// EVERY spot examined, refused ones included, because the map is not cleared when a
					// spot is removed and a reused pool index would otherwise read the last occupant.
					ContourSupport.set( graph, refs.get( i ), e == null ? 0 : e.inliers );
					if ( e == null )
					{
						final EllipsoidFit3D.Fit f = fits3d[ i ];
						if ( f == null )
							continue;
						refs.get( i ).setPosition( f.centre );
						refs.get( i ).setCovariance( f.covariance );
						fitted++;
						if ( countOut != null && countOut.length > 1 )
							countOut[ 1 ]++;
						continue;
					}
					// The outline's own centre, too: ELEPHANT's centre sits off its cell more often
					// than the median shows. Measured offline on 544 fitted detections of run
					// 20261004-223428 against each cell's silver-truth centroid, the detection centre is
					// off by more than half the minor semi-axis for 26 and the fit's centre for 9 (20
					// fixed, 3 made worse); p90 0.35 -> 0.12. This runs inside detection, before any
					// link exists, so no displacement the linker reasoned about is moved.
					refs.get( i ).setPosition( e.x, 0 );
					refs.get( i ).setPosition( e.y, 1 );
					// One plane says nothing about a third dimension, so on a z stack the detection's
					// own out-of-plane shape is kept; see EllipseFit.Ellipse.covarianceKeepingDepth. On a
					// single plane the old behaviour is unchanged (z is not a real axis there).
					if ( volumetricFit )
					{
						final double[][] original = new double[ 3 ][ 3 ];
						refs.get( i ).getCovariance( original );
						refs.get( i ).setCovariance( e.covarianceKeepingDepth( original ) );
					}
					else
						refs.get( i ).setCovariance( e.covariance( seeds.get( i )[ 3 ] ) );
					fitted++;
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
		}
		finally
		{
			for ( final Spot ref : refs )
				graph.releaseRef( ref );
		}
		return fitted;
	}

	/** Whether {@code f} contains the centre of any detection in {@code seeds} other than the {@code self}-th. */
	static boolean containsAnotherCentre( final EllipsoidFit3D.Fit f, final List< double[] > seeds, final int self )
	{
		final double[][] precision = invert3( f.covariance );
		if ( precision == null )
			return true; // a degenerate ellipsoid describes no cell; refuse it
		for ( int j = 0; j < seeds.size(); j++ )
		{
			if ( j == self )
				continue;
			final double[] d = { seeds.get( j )[ 0 ] - f.centre[ 0 ], seeds.get( j )[ 1 ] - f.centre[ 1 ],
					seeds.get( j )[ 2 ] - f.centre[ 2 ] };
			double m = 0;
			for ( int p = 0; p < 3; p++ )
				for ( int q = 0; q < 3; q++ )
					m += d[ p ] * precision[ p ][ q ] * d[ q ];
			if ( m <= 1.0 )
				return true;
		}
		return false;
	}

	private static double[][] invert3( final double[][] a )
	{
		final double det = a[ 0 ][ 0 ] * ( a[ 1 ][ 1 ] * a[ 2 ][ 2 ] - a[ 1 ][ 2 ] * a[ 2 ][ 1 ] )
				- a[ 0 ][ 1 ] * ( a[ 1 ][ 0 ] * a[ 2 ][ 2 ] - a[ 1 ][ 2 ] * a[ 2 ][ 0 ] )
				+ a[ 0 ][ 2 ] * ( a[ 1 ][ 0 ] * a[ 2 ][ 1 ] - a[ 1 ][ 1 ] * a[ 2 ][ 0 ] );
		if ( !( Math.abs( det ) > 1e-12 ) )
			return null;
		final double[][] r = new double[ 3 ][ 3 ];
		for ( int i = 0; i < 3; i++ )
			for ( int j = 0; j < 3; j++ )
			{
				final int i1 = ( j + 1 ) % 3, i2 = ( j + 2 ) % 3, j1 = ( i + 1 ) % 3, j2 = ( i + 2 ) % 3;
				r[ i ][ j ] = ( a[ i1 ][ j1 ] * a[ i2 ][ j2 ] - a[ i1 ][ j2 ] * a[ i2 ][ j1 ] ) / det;
			}
		return r;
	}

	private static bdv.viewer.Source< ? > firstSource( final ToolContext context )
	{
		try
		{
			final List< bdv.viewer.SourceAndConverter< ? > > sources = context.requireViewer().state().getSources();
			return sources.isEmpty() ? null : sources.get( 0 ).getSpimSource();
		}
		catch ( final RuntimeException e )
		{
			return null;
		}
	}

	@SuppressWarnings( { "unchecked", "rawtypes" } )
	private static EllipsoidFit3D.Fit fit3d( final bdv.viewer.Source< ? > source, final int timepoint,
			final double[] seed )
	{
		try
		{
			return EllipsoidFit3D.fit( ( bdv.viewer.Source ) source, timepoint,
					new double[] { seed[ 0 ], seed[ 1 ], seed[ 2 ] }, seed[ 3 ] );
		}
		catch ( final RuntimeException e )
		{
			return null;
		}
	}

	static int deleteSpotsWithNoSignal( final ToolContext context, final int timepoint,
			final double[] ceilingOut )
	{
		if ( ExpectedCellCount.countCellsAt( context, timepoint ) == 0 )
			return 0; // see deleteFlatContrastSpots: an empty timepoint must not cost a background estimate
		// "Brighter than the background" is a fact about FLUORESCENCE. Under phase contrast or
		// brightfield a cell is routinely no brighter than its surroundings, and this pass's own
		// history records what happens then: on PhC-C2DL-PSC the baseline settled at 141 while real
		// detections ran 142-239, so "dimmer than this frame's background" described 84% of the frame.
		// NO_SIGNAL_MAX_FRACTION caught that, but a 50% backstop is a symptom guard, not a statement of
		// where the test applies. ImageProfile already decides the modality from the pixels and the
		// run already prints its verdict; this asks it.
		final ImageProfile imageProfile = ImageProfile.forRun( context,
				() -> AutoCorrectOrchestrator.sampleIntensities( context, timepoint, ImageProfile.SAMPLES ) );
		if ( !imageProfile.fluorescenceLike )
			return 0;
		// The FRAME's own level, not estimateBackgroundStats. That estimate avoids positions near
		// existing spots, so it reports the emptiest REGIONS of the frame rather than the frame: about
		// 3.8 sigma below the frame's own median here. A ceiling meant to sit 2.5 sigma above the
		// background therefore sat a sigma BELOW it, and this pass deleted nothing at all -- measured
		// 0.0% of the detections covering no truth cell, on 3245 of the detector's own detections.
		// Anchored here it catches 76.6% of them for 0.57% of the real ones.
		final double[] frame = AutoCorrectOrchestrator.frameLevelStats( context, timepoint );
		if ( frame == null || !( frame[ 1 ] > 0 ) )
			return 0; // the frame could not be sampled; nothing to compare anything against
		// [0]=volume pct, [1]=confirmed count, ..., [5]=intensity percentile of the confirmed cells.
		final double dimmestConfirmed = confirmedDebrisThresholds( context )[ 5 ];
		final double ceiling = AutoCorrectOrchestrator.noSignalCeiling( frame[ 0 ],
				frame[ 1 ], dimmestConfirmed );
		// Which reason — and so whether the cell-count floor applies — depends on whether the ceiling
		// actually had two anchors to sit between.
		//
		// With the user's confirmed cells supplying the upper one, this is the measurement its own
		// javadoc argues should outrank the user's stated count: a detection below it carries no more
		// signal than the space around it, and the floor was observed protecting detections reading
		// literally zero. With only the background, it is not a measurement but a single-anchor guess —
		// "is this detection dimmer than the average of this frame" — and on a phase-contrast frame,
		// where the background is itself mid-grey, the answer is yes for real cells too. Observed on
		// PhC-C2DL-PSC: the ceiling swung 45 -> 130 -> 0 -> 127 across consecutive windows and emptied
		// two of them completely, 931 and 1559 detections, every one of which the predictor had just
		// found. A guess weaker than the user's own count is exactly what the floor is for.
		final boolean twoAnchors = dimmestConfirmed > frame[ 0 ];
		final GraphEdits.Reason why =
				twoAnchors ? GraphEdits.Reason.NOT_A_CELL : GraphEdits.Reason.PROBABLY_NOT_A_CELL;
		if ( ceilingOut != null && ceilingOut.length >= 3 )
		{
			ceilingOut[ 0 ] += ceiling;
			ceilingOut[ 1 ]++;
			ceilingOut[ 2 ] = dimmestConfirmed;
		}
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();
		final List< Spot > candidates = new ArrayList<>();
		final List< double[] > positions = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				if ( ProtectedSpots.isProtected( context, model, spot ) )
					continue;
				spot.localize( pos );
				final Spot ref = graph.vertexRef();
				ref.refTo( spot );
				candidates.add( ref );
				spot.getCovariance( cov );
				positions.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ],
						SpotShape.interiorRadius( cov, context.isVolumetric(),
								Math.sqrt( spot.getBoundingSphereRadiusSquared() ) ) } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		// Image sampling with no lock held.
		final List< Spot > toDelete = new ArrayList<>();
		for ( int i = 0; i < candidates.size(); i++ )
		{
			final double[] c = positions.get( i );
			final double[] profile = AutoCorrectOrchestrator.interiorIntensityProfile( context, timepoint,
					new double[] { c[ 0 ], c[ 1 ], c[ 2 ] }, c[ 3 ] );
			// The MEDIAN of the interior reads, not their mean. The question this pass asks is whether
			// the detection is ON a cell, which is a question about most of it; the mean answers
			// whether it contains any signal at all. A detection half-overlapping its neighbour has
			// several reads on that nucleus and the rest on background, and those few drag the mean
			// above any background-anchored ceiling — the circle sitting on empty space with one edge
			// touching a cell. The background half of this comparison has been a median and a MAD
			// since computeBackgroundStats was made robust; this is the other half.
			//
			// A null profile means the interior averaged zero, which is the clearest case of all.
			//
			// BOTH the full extent and the core have to read background. The full-extent median is the
			// right question for a detection that has drifted off its cell; it is the wrong one for a
			// detection merely WIDER than the object under it, and a mitotic figure or a newborn
			// daughter is exactly that. Raising this ceiling to where it can see background made those
			// disappear from both sides of a division, which is a worse error than the one being fixed.
			// The core does not rescue the case this pass exists for: a circle on empty space touching
			// a neighbour has its CENTRE on the empty space.
			if ( profile == null || ( profile[ 2 ] <= ceiling && profile[ 3 ] <= ceiling ) )
				toDelete.add( candidates.get( i ) );
		}
		// A pass premised on "these carry no signal at all" describing MOST of a trained detector's
		// output is not describing the specimen; it is reporting an anchor that does not fit this image.
		// Observed on phase contrast, where the background is itself mid-grey: the baseline settled
		// stably at 141 while real detections ran from 142 to 239, so "dimmer than this frame's average"
		// caught 84% of the frame — 2034 of 2432 — every one of which the predictor had just found. The
		// threshold was not wrong by a little, the question was wrong: these cells are not brighter than
		// their surroundings, and no choice of ceiling makes that test meaningful.
		if ( !candidates.isEmpty() && toDelete.size() > NO_SIGNAL_MAX_FRACTION * candidates.size() )
		{
			if ( ceilingOut != null && ceilingOut.length >= 4 )
				ceilingOut[ 3 ]++;
			for ( final Spot s : candidates )
				graph.releaseRef( s );
			return 0;
		}
		if ( !toDelete.isEmpty() )
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot spot : toDelete )
					GraphEdits.removeSpot( context, graph, spot, why );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			model.setUndoPoint();
			context.notifyGraphChanged();
		}
		for ( final Spot spot : candidates )
			graph.releaseRef( spot );
		return toDelete.size();
	}

	/**
	 * Withholds the least cell-like detections from training until each timepoint teaches about as many
	 * cells as the user said there are.
	 *
	 * <p>The floor bounds how far the cleanup passes may cut the GRAPH. Nothing bounded what the model is
	 * TAUGHT, and the two are not the same question: a detection can be worth keeping for linking while
	 * being a bad example of a cell. Measured on a movie stated to hold 250 cells/frame, ELEPHANT was
	 * being handed 517-545 positives per frame, and it re-detected against what it had been taught — the
	 * loop that has driven every symptom in this pipeline.</p>
	 *
	 * <p>Which ones to keep is decided against the user's CONFIRMED spots rather than against a
	 * threshold: each detection's size, roundness and brightness are compared to the mean of the
	 * confirmed cells, and the closest survive as training positives. That is the only reference here
	 * that is not another estimate. The rest are tagged {@code unlabeled} — kept in the graph, taught
	 * neither as cells nor as background.</p>
	 *
	 * <p>Protected spots are never withheld, and a dataset with no confirmed spots is left alone: with no
	 * reference, ranking detections by "cell-likeness" would just be the detector's own opinion again.</p>
	 *
	 * @return how many were withheld
	 */
	static int capAnnotationsToStatedCount( final ToolContext context, final int[] timepoints )
	{
		if ( ExpectedCellCount.statedCellsPerFrame( context ) <= 0 )
			return 0;
		// A null reference means nothing has been confirmed in this project yet, so there is no profile
		// of the user's own cells to rank against. It used to mean NO CAP AT ALL, which is a different
		// thing and the wrong one: the ranking needs a reference, the cap does not. Observed on
		// Fluo-N2DL-HeLA_01 stated at ~26 cells/frame — every timepoint of window 1 taught the model
		// all ~700 of its raw detections as cells, which is the detector being told that this frame's
		// background is what a cell looks like. capAnnotationsAt falls back to ranking by how far each
		// detection stands above the background, which needs nothing confirmed.
		final double[] reference = confirmedCellProfile( context );
		int withheld = 0;
		for ( final int t : timepoints )
			withheld += capAnnotationsAt( context, t, reference );
		return withheld;
	}

	private static int capAnnotationsAt( final ToolContext context, final int timepoint,
			final double[] reference )
	{
		final int cap = ExpectedCellCount.annotationCapAt( context, timepoint );
		if ( cap <= 0 )
			return 0;
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();
		final List< Spot > candidates = new ArrayList<>();
		final List< double[][] > shapes = new ArrayList<>(); // { position, covariance rows }
		int confirmedHere = 0;
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				if ( ProtectedSpots.isProtected( context, model, spot ) )
				{
					// Always taught, and counted INSIDE the cap: the stated number is a count of cells,
					// and a confirmed cell is one. Adding them on top of the cap instead put ~115 extra
					// positives on every timepoint of a window.
					confirmedHere++;
					continue;
				}
				if ( DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, spot ) ) )
					continue; // already an explicit negative
				spot.localize( pos );
				spot.getCovariance( cov );
				final Spot ref = graph.vertexRef();
				ref.refTo( spot );
				candidates.add( ref );
				shapes.add( new double[][] { pos.clone(), cov[ 0 ].clone(), cov[ 1 ].clone(), cov[ 2 ].clone() } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		// The image is read with NO lock held (under it, every edit in the viewer waited for all the
		// reads). With a reference, distance from the user's own cells. Without one, the negated standout
		// above background — same ordering convention (smaller is more cell-like), and on fluorescence
		// standing above the background is exactly the evidence available. withholdFromTraining leaves
		// alone a spot changed in the viewer meanwhile (GraphWatch).
		// Where the background has no spread (exactly 0 around a sparse embryo) every standout reads 0 and the
		// cut fell in graph order, on cells as readily as on anything else; the raw intensity orders them by the
		// same evidence, unscaled.
		final boolean byStandout = reference == null && AutoCorrectOrchestrator.standoutMeasurable( context, timepoint );
		final List< Double > distance = new ArrayList<>();
		for ( final double[][] sh : shapes )
		{
			final double[][] c = { sh[ 1 ], sh[ 2 ], sh[ 3 ] };
			distance.add( reference != null
					? profileDistance( context, timepoint, sh[ 0 ], c, reference )
					: byStandout ? -AutoCorrectOrchestrator.standoutAboveBackground( context, timepoint, sh[ 0 ] )
							: -AutoCorrectOrchestrator.intensityAt( context, timepoint, sh[ 0 ] ) );
		}
		final int budget = Math.max( 0, cap - confirmedHere );
		if ( candidates.size() <= budget )
		{
			for ( final Spot s : candidates )
				graph.releaseRef( s );
			return 0;
		}
		final Integer[] order = new Integer[ candidates.size() ];
		for ( int i = 0; i < order.length; i++ )
			order[ i ] = i;
		// Least cell-like LAST, so the ones withheld are the ones furthest from a confirmed cell — or,
		// with nothing confirmed, the ones standing least above the background.
		java.util.Arrays.sort( order, ( a, b ) -> Double.compare( distance.get( a ), distance.get( b ) ) );
		final List< Spot > toWithhold = new ArrayList<>();
		for ( int i = budget; i < order.length; i++ )
			toWithhold.add( candidates.get( order[ i ] ) );
		final int n = withholdFromTraining( context, toWithhold, java.util.Collections.emptyList() );
		for ( final Spot s : candidates )
			graph.releaseRef( s );
		return n;
	}

	/**
	 * How far a detection sits from the mean confirmed cell, as the sum of relative differences in size,
	 * roundness and brightness. Relative so the three contribute comparably without needing units.
	 */
	private static double profileDistance( final ToolContext context, final int timepoint,
			final double[] pos, final double[][] cov, final double[] reference )
	{
		final double size = SpotShape.size( cov );
		final double aspect = SpotShape.aspectRatio( cov, isVolumetric( context ) );
		final double intensity = AutoCorrectOrchestrator.sampleIntensity( context, timepoint, pos );
		return Math.abs( size - reference[ 0 ] ) / Math.max( 1e-9, reference[ 0 ] )
				+ Math.abs( aspect - reference[ 1 ] ) / Math.max( 1e-9, reference[ 1 ] )
				+ Math.abs( intensity - reference[ 2 ] ) / Math.max( 1e-9, reference[ 2 ] );
	}

	/**
	 * Mean size, roundness and brightness of the user's confirmed spots, or {@code null} when there are
	 * too few to average. Computed once per run.
	 */
	private static double[] confirmedCellProfile( final ToolContext context )
	{
		final Object cached = context.getSharedData( SHARED_CONFIRMED_CELL_PROFILE );
		if ( cached instanceof double[] )
			return ( ( double[] ) cached )[ 3 ] > 0 ? ( double[] ) cached : null;
		final ModelGraph graph = context.getGraph();
		final List< double[] > confirmed = new ArrayList<>();
		final boolean volumetric = isVolumetric( context );
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( !ProtectedSpots.isProtected( context, context.getModel(), spot ) )
					continue;
				spot.localize( pos );
				spot.getCovariance( cov );
				confirmed.add( new double[] {
						SpotShape.size( cov ),
						SpotShape.aspectRatio( cov, volumetric ),
						pos[ 0 ], pos[ 1 ], pos[ 2 ], spot.getTimepoint() } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		double sumSize = 0, sumAspect = 0, sumIntensity = 0;
		int n = 0;
		// Image sampling with no lock held.
		for ( final double[] c : confirmed )
		{
			final double intensity = AutoCorrectOrchestrator.sampleIntensity( context, ( int ) c[ 5 ],
					new double[] { c[ 2 ], c[ 3 ], c[ 4 ] } );
			if ( !( intensity > 0 ) )
				continue;
			sumSize += c[ 0 ];
			sumAspect += c[ 1 ];
			sumIntensity += intensity;
			n++;
		}
		final double[] profile = n >= MIN_CONFIRMED_FOR_PROFILE
				? new double[] { sumSize / n, sumAspect / n, sumIntensity / n, n }
				: new double[] { 0, 0, 0, 0 };
		context.setSharedData( SHARED_CONFIRMED_CELL_PROFILE, profile );
		if ( profile[ 3 ] > 0 )
			context.log( String.format(
					"  Training will be capped against your %d confirmed cell(s): %.1f µm / aspect %.2f / "
							+ "intensity %.0f is what a cell looks like here.",
					n, profile[ 0 ], profile[ 1 ], profile[ 2 ] ) );
		return profile[ 3 ] > 0 ? profile : null;
	}

	/**
	 * Sets the factor that brings the annotations' mean extent back to the confirmed cells' extent
	 * before they are sent for training.
	 *
	 * <p>ELEPHANT paints an annotation as a "center" core covering {@code c_ratio} of its ellipsoid and
	 * a "border" shell around that, and prediction fits ellipsoids to the CENTER regions. A detection
	 * therefore measures the core, not the cell — and using it unchanged as the next round's annotation
	 * makes the following round's core a fraction of a fraction. The extent compounds downwards with
	 * every round: across successive runs of one dataset the mean spot size went 10.5, 9.5, 8.3, 8.2 µm
	 * against confirmed cells at 8.5, and on the other it reached 8.2 against confirmed 11.1. What that
	 * looks like from outside is a detector that finds only the middle of a cell, and then misses it.</p>
	 *
	 * <p>The correction is measured, not derived from {@code c_ratio}: it is whatever brings the
	 * population's mean size back to the confirmed cells' mean. That makes it 1.0 when nothing is
	 * drifting, and it cannot be wrong about a geometry this code does not own. It scales the
	 * annotation sent for training only — the spots in the graph, and every count and measurement taken
	 * from them, are untouched.</p>
	 */
	private static void restoreAnnotationExtent( final ToolContext context, final int[] timepoints )
	{
		context.setSharedData( ElephantIntegration.SHARED_ANNOTATION_COVARIANCE_SCALE, 1.0 );
		final double[] metrics = computeQualityMetrics( context, timepoints );
		final double detectedSize = metrics[ 2 ];
		if ( !( detectedSize > 0 ) )
			return;

		// Where the target size comes from. Confirmed cells are the better evidence when they exist,
		// because they are the user's own measurement of this specimen. They usually do not exist: an
		// autonomous run confirms nothing, so this method used to return here and the correction it
		// exists to apply never ran in the case it was most needed. The image can answer the same
		// question -- see AutoCorrectOrchestrator.measuredCellRadius, whose per-frame population
		// median lands within 2.2% of CTC silver truth over 2117 cells.
		final double[] confirmed = confirmedCellProfile( context );
		final double referenceSize;
		final String evidence;
		if ( confirmed != null )
		{
			referenceSize = confirmed[ 0 ];
			evidence = "the cells you confirmed";
		}
		else
		{
			referenceSize = measuredCellExtent( context, timepoints );
			evidence = "the cells measured in the image around them";
		}
		if ( !( referenceSize > 0 ) )
			return;

		final double sizeFactor = Math.max( 1.0,
				Math.min( MAX_ANNOTATION_EXTENT_FACTOR, referenceSize / detectedSize ) );
		if ( sizeFactor <= 1.0 + ANNOTATION_EXTENT_DEAD_ZONE )
			return;
		// size is sqrt(trace(covariance)), so a linear factor k needs the covariance scaled by k*k.
		context.setSharedData( ElephantIntegration.SHARED_ANNOTATION_COVARIANCE_SCALE,
				sizeFactor * sizeFactor );
		context.log( String.format(
				"  Annotation extent: detections average %.1f µm against %.1f µm for %s, so they are "
						+ "sent for training at %.2fx their measured size. Training on the core ELEPHANT "
						+ "detects, unchanged, shrinks the next round's core again — and a detection "
						+ "covering half a nucleus is what lets two of them tile one nucleus.",
				detectedSize, referenceSize, evidence, sizeFactor ) );
	}

	/** Detections sampled when measuring the cell size from the image. A population median needs a
	 * sample, not a census, and each one costs a few hundred image reads. */
	private static final int CELL_EXTENT_SAMPLE = 120;

	/**
	 * Median radius of the CELLS around the current detections, measured from the image.
	 *
	 * <p>The replacement for {@link #confirmedCellProfile} when nothing is confirmed. Protected spots
	 * are excluded because when they exist the caller uses them directly, and fp spots because they
	 * are deliberately not on cells.</p>
	 *
	 * @return median cell radius in world units, or {@code NaN} if too little could be measured
	 */
	private static double measuredCellExtent( final ToolContext context, final int[] timepoints )
	{
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();
		final List< double[] > seeds = new ArrayList<>(); // {x,y,z,t,radius}
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			for ( final int t : timepoints )
			{
				for ( final Spot spot : graph.vertices() )
				{
					if ( spot.getTimepoint() != t )
						continue;
					if ( DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, spot ) ) )
						continue;
					spot.localize( pos );
					seeds.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ], t,
							Math.sqrt( spot.getBoundingSphereRadiusSquared() ) } );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( seeds.isEmpty() )
			return Double.NaN;

		// Evenly spaced through the list rather than randomly, so the estimate is the same on a rerun
		// of the same graph.
		final int stride = Math.max( 1, seeds.size() / CELL_EXTENT_SAMPLE );
		final List< Double > measured = new ArrayList<>();
		for ( int i = 0; i < seeds.size(); i += stride )
		{
			final double[] seed = seeds.get( i );
			final double r = AutoCorrectOrchestrator.measuredCellRadius( context, ( int ) seed[ 3 ],
					new double[] { seed[ 0 ], seed[ 1 ], seed[ 2 ] }, seed[ 4 ] );
			if ( !Double.isNaN( r ) && r > 0 )
				measured.add( r );
		}
		if ( measured.size() < 5 )
			return Double.NaN; // too few detections sit on anything measurable to call it a population
		Collections.sort( measured );
		final int n = measured.size();
		return n % 2 == 1 ? measured.get( n / 2 )
				: ( measured.get( n / 2 - 1 ) + measured.get( n / 2 ) ) / 2.0;
	}

	/**
	 * Largest the annotation extent may be stretched.
	 *
	 * <p>Was 2.0, on the reasoning that two doublings of linear size is far more than the drift this
	 * corrects. A run then reported 1.71x and 1.53x and the very next prediction exploded, twice, on
	 * exactly the two trainings where this fired — 81 and 108 raw spots at t=0 for the two trainings
	 * without it, 312 and 254 for the two with it. Teaching a cell half again too large is not a
	 * gentler version of the right answer.</p>
	 *
	 * <p>1.3 is what the measurement supports and no more: on CTC silver truth the detections in that
	 * run averaged 6.6-6.9 µm against a true median of 8.3 µm, i.e. 0.80-0.83x, so the correction
	 * they justify is at most about 1.25x. The reference read 10.6-11.3 µm instead — about 1.3x the
	 * truth — and the mipmap level the Sampler reads accounts for only 5-8% of that, so the estimator
	 * still reads high in situ for a reason not yet isolated. Until it is, the cap is the guard.</p>
	 */
	private static final double MAX_ANNOTATION_EXTENT_FACTOR = 1.3;

	/** Mismatch below which the extent is left alone — real populations are not uniform. */
	private static final double ANNOTATION_EXTENT_DEAD_ZONE = 0.05;

	/** Confirmed spots needed before their mean is used as the reference for what a cell looks like. */
	private static final int MIN_CONFIRMED_FOR_PROFILE = 50;

	/**
	 * {@link ToolContext#getSharedData} key set when the user ticked "start fresh" in the pre-flight
	 * dialog — {@link Boolean}.
	 *
	 * <p>That choice could not be expressed by clearing state alone. It deletes this dataset's spots and
	 * the saved progress marker, but it deliberately keeps the user's CONFIRMED spots, and the resume
	 * path infers how far a previous pass got from spots being present. So a run that had just been told
	 * to start fresh saw 88 confirmed annotations at t=0..1, concluded those timepoints were done, and
	 * began at t=2 — skipping the only frames in the project with ground truth on them.</p>
	 *
	 * <p>Where to begin is part of the instruction, so it is carried as one rather than reconstructed
	 * from what the graph happens to hold afterwards.</p>
	 */
	public static final String SHARED_START_FRESH_REQUESTED = "adaptive_track.start_fresh_requested";

	/**
	 * {@link ToolContext#getSharedData} key for the timepoint the user chose to begin at —
	 * {@link Number}, absent meaning "decide as before".
	 *
	 * <p>Resuming a part-tracked movie worked only by inference or by the agent's own parameter: the
	 * resume path reads how far a previous pass got from which timepoints hold spots, and the agent
	 * passes {@code start_timepoint} when it happens to read that out of the guidance text. Neither is
	 * something to rely on when the answer is known — "t=0..159 is done, carry on from 160" is a fact
	 * the user has, not something to be deduced from the graph or hoped for from a language model.</p>
	 */
	public static final String SHARED_START_TIMEPOINT_OVERRIDE = "adaptive_track.start_timepoint.override";

	/** {@link ToolContext#getSharedData} key for {@link #confirmedCellProfile}'s cached result. */
	public static final String SHARED_CONFIRMED_CELL_PROFILE = "adaptive_track.confirmed_cell_profile";

	/**
	 * How many times a window retrains on the same trusted GT before giving up on the model settling.
	 *
	 * <p>Not one. The GT labels are identical every round, which is why a single pass looked sufficient,
	 * but the model is not: it arrives at a GT window already pulled toward whatever it was detecting
	 * before — seven hundred spots a frame in the run that prompted this — and one pass of a few hundred
	 * annotations does not undo that. Each round costs a training call, so the loop stops the moment the
	 * count stops moving rather than always running to this bound; later windows inherit a model that has
	 * already settled and normally finish in one.</p>
	 */
	private static final int GT_CONSOLIDATION_MAX_ROUNDS = 4;

	/**
	 * Round-to-round change in detected spots/tp below which GT training counts as settled. Ten percent:
	 * the probe is a single crop-restricted re-detect, so smaller differences than that are as likely to
	 * be the region as the model.
	 */
	private static final double GT_SETTLED_CHANGE_FRACTION = 0.10;

	/**
	 * A timepoint slower than this reports where its time went, right after it finishes.
	 *
	 * <p>Thirty seconds on a handful of crop-restricted detections is already far outside what the work
	 * costs, so anything above it is worth a line; below it, the per-window summary is enough and a line
	 * per timepoint would only be noise.</p>
	 */
	private static final long SLOW_TIMEPOINT_REPORT_NANOS = 30_000_000_000L;

	/**
	 * Samples the intensity-seed scan may take, against {@link BrightSpotAudit}'s own budget of half a
	 * million.
	 *
	 * <p>This scan exists to pick a few dozen bright clusters to centre a tuning crop on, not to
	 * measure the image. At the shared budget it took 94 seconds per window — an hour and a half across
	 * a 34-window run, spent deciding where to look before a single prediction had run. A fifth of the
	 * samples still leaves tens of thousands of them to find a handful of clusters in.</p>
	 */
	private static final long SEED_SCAN_SAMPLE_BUDGET = 100_000L;

	/**
	 * How far the tuning regions picked for one window may be reused by later ones.
	 *
	 * <p>The scan that picks them costs more than the predictions it is choosing a crop for, and what
	 * it produces — a few dense, bright neighbourhoods — does not move meaningfully over a few dozen
	 * timepoints of a developing specimen. Re-picking every window paid the scan 34 times for an answer
	 * that changes slowly.</p>
	 */
	private static final int TUNING_ROI_REUSE_TIMEPOINTS = 30;

	/** VLM audits of the flat-contrast verdict per window — see SHARED_FLAT_CONTRAST_AUDIT_PENDING. */
	private static final int FLAT_CONTRAST_AUDITS_PER_WINDOW = 3;

	/** Only every Nth timepoint is audited, so a window's samples span it rather than cluster. */
	private static final int FLAT_CONTRAST_AUDIT_TIMEPOINT_STRIDE = 4;

	/**
	 * {@link ToolContext#getSharedData} key for how the flat-contrast check scores against the user's
	 * OWN confirmed spots, as {@code [calledBackground, checked]}.
	 *
	 * <p>The only measurement in this pipeline that is not one estimate judging another. The check
	 * flags 47-70% of a timepoint and the floor refuses most of those deletions, and every attempt to
	 * settle that has gone through a model — the VLM, whose verdicts on these crops have turned out to
	 * be unreliable. A spot the user tagged Status &gt; Completed is a cell because a person said so, so
	 * the share of those the check calls background is its false-positive rate outright.</p>
	 */
	public static final String SHARED_FLAT_CONTRAST_GROUND_TRUTH =
			"adaptive_track.flat_contrast_ground_truth";

	/** How the check scores against the user's confirmed spots, created on first use. */
	private static int[] groundTruthTally( final ToolContext context )
	{
		Object v = context.getSharedData( SHARED_FLAT_CONTRAST_GROUND_TRUTH );
		if ( !( v instanceof int[] ) )
		{
			v = new int[ 2 ];
			context.setSharedData( SHARED_FLAT_CONTRAST_GROUND_TRUTH, v );
		}
		return ( int[] ) v;
	}

	/** The run-wide audit tally, created on first use. */
	private static int[] auditTally( final ToolContext context )
	{
		Object v = context.getSharedData( SHARED_FLAT_CONTRAST_AUDIT_TALLY );
		if ( !( v instanceof int[] ) )
		{
			v = new int[ 4 ];
			context.setSharedData( SHARED_FLAT_CONTRAST_AUDIT_TALLY, v );
		}
		return ( int[] ) v;
	}

	/** Mean raw spots/tp from the last {@link #predictWindow}, or 0 if none has run. */
	private static double lastPredictRawSpotsPerTp( final ToolContext context )
	{
		final Object v = context.getSharedData( SHARED_LAST_PREDICT_RAW_SPOTS_PER_TP );
		return v instanceof Number ? ( ( Number ) v ).doubleValue() : 0.0;
	}

	/**
	 * Model snapshot taken before EACH tuning round, distinct from {@link #modelSnapshotFile}'s
	 * per-window one: the window snapshot undoes the whole window (what abandonment needs), this one
	 * undoes a single diverged round and leaves the rounds before it intact.
	 */
	private static File roundModelSnapshotFile()
	{
		return new File( new File( System.getProperty( "user.home" ), MODEL_SNAPSHOT_DIR ),
				"round_" + modelSnapshotFileName( ElephantMainSettingsManager.getInstance()
						.getSelectedStyle().getDatasetName() ) );
	}

	/**
	 * Directory holding one detection-model snapshot per dataset (see
	 * {@link #modelSnapshotFile}). Kept deliberately: a snapshot from an earlier run is the only way
	 * back to a working model once a later run has trained the server's copy into divergence, and
	 * ELEPHANT keeps no history of its own.
	 */
	private static final String MODEL_SNAPSHOT_DIR = ".automahout-model-snapshots";

	/**
	 * Where this dataset's model snapshot lives. One generation per dataset, overwritten at the top of
	 * every window (see the snapshot call there) so a restore always returns to the state the CURRENT
	 * window started from rather than to some arbitrary earlier point.
	 */
	private static File modelSnapshotFile()
	{
		return new File( new File( System.getProperty( "user.home" ), MODEL_SNAPSHOT_DIR ),
				modelSnapshotFileName( ElephantMainSettingsManager.getInstance()
						.getSelectedStyle().getDatasetName() ) );
	}

	/**
	 * Turns a dataset name into a snapshot filename. ELEPHANT dataset names are paths
	 * ("hiiragi/BlockA_stack0_ch1_..."), so they cannot be used as filenames unchanged — a separator
	 * left in would silently write the snapshot into a subdirectory that does not exist, or escape the
	 * snapshot directory entirely.
	 */
	static String modelSnapshotFileName( final String datasetName )
	{
		final String base = datasetName == null || datasetName.trim().isEmpty()
				? "dataset" : datasetName.trim();
		return base.replaceAll( "[^A-Za-z0-9._-]", "_" ) + "_detection.pth";
	}

	/**
	 * Fallback ABSOLUTE cap used only when no calibrated ceiling is in scope at all (callers passing
	 * {@code expectedCeiling <= 0}, e.g. {@link #trackWindowGtGuided}) — still better than no circuit
	 * breaker whatsoever for those paths.
	 */
	private static final int DETECTION_EXPLOSION_FALLBACK_CAP = 5000;

	/**
	 * Timepoints that may exceed the explosion threshold before the WINDOW is abandoned rather than just
	 * those timepoints discarded.
	 *
	 * <p>The threshold answers "is this timepoint usable?"; abandoning the window answers "has the model
	 * diverged?", and one is not the other. Observed twice in a single run: t=23 at 1028 raw against a
	 * threshold of 1000, and t=38 at 1014 — overshoots of 2.8% and 1.4%, each discarding ten timepoints
	 * of work and leaving that stretch of the movie with nothing but its pre-existing spots. Both were
	 * timepoints that were already the densest in their window before any training. A genuinely diverged
	 * model does not overshoot by 2%; it overshoots by the 2114-against-1000 seen elsewhere, which
	 * {@link #SEVERE_EXPLOSION_RATIO} still catches on the first timepoint.
	 */
	private static final int MAX_EXPLODED_TIMEPOINTS_PER_WINDOW = 1;

	/**
	 * How far over the threshold a timepoint must be before it counts toward abandoning the window.
	 *
	 * <p>{@link #MAX_EXPLODED_TIMEPOINTS_PER_WINDOW} was meant to stop a marginal overshoot throwing
	 * ten timepoints away, and it counted every overshoot however small — so two marginal ones still
	 * did it. Observed: t=1 at 233 raw against a threshold of 200 and t=2 at 226 against 208, i.e. 17%
	 * and 9% over, and the window was abandoned as "the model has diverged". In the same run the
	 * genuine divergences were 16609 and 4954 against 200 — 83 times and 24 times over.</p>
	 *
	 * <p>Anything between those separates them. 1.5 is picked near the low end because the threshold
	 * is already twice the expected ceiling, so this is three times what the frame is expected to
	 * hold: a model at that level is over-detecting, which the cleanup passes are for, not diverging,
	 * which nothing downstream can fix.</p>
	 */
	private static final double DIVERGENCE_COUNT_RATIO = 1.5;

	/**
	 * Multiple of the explosion threshold at which a SINGLE timepoint is enough to abandon the window,
	 * without waiting for a second. At this much over, continuing to train on the window is pointless
	 * whatever the other timepoints look like.
	 */
	private static final double SEVERE_EXPLOSION_RATIO = 1.5;

	/**
	 * Thrown by {@link #predictWindow} when a single timepoint's raw detection count exceeds the
	 * explosion threshold ({@link #DETECTION_EXPLOSION_CEILING_RATIO} times the caller's
	 * {@code expectedCeiling}, or {@link #DETECTION_EXPLOSION_FALLBACK_CAP} with no ceiling in scope) —
	 * signals "the model has diverged, abandon this window's current attempt" to the caller, which should
	 * log and move on (to cold-start bootstrap, or the next window) rather than continue
	 * training/refining on data that is already known to be garbage.
	 */
	private static final class DetectionExplosionException extends RuntimeException
	{
		final int timepoint;
		final int count;
		/** The spots/tp the timepoint went over. */
		final double threshold;

		DetectionExplosionException( final int timepoint, final int count, final double threshold )
		{
			super( "Detection explosion at t=" + timepoint + ": " + count + " raw spot(s)" );
			this.timepoint = timepoint;
			this.count = count;
			this.threshold = threshold;
		}
	}

	/**
	 * Logs the window-level abandonment triggered by a {@link DetectionExplosionException} — caller
	 * must then {@code continue} the window loop; this deliberately does NOT retry within the same
	 * window (a diverged model rarely self-corrects on the very next crop) — the NEXT window's own
	 * "[1/6]" density probe/cold-start detection re-assesses the model from scratch.
	 */
	/**
	 * Abandons the current window AND puts the server's detection model back to where this window found
	 * it.
	 *
	 * <p>Abandoning used to discard only the window's spots. The weights that produced the explosion had
	 * already been written to the server by the training that preceded it, and there is no undo there,
	 * so the next window started from the diverged model and usually exploded on its very first predict
	 * — one bad window taking every remaining one with it. Observed across a full run: windows 3-8 all
	 * abandoned, two of them without ever reaching their own training loop.</p>
	 */
	/**
	 * Times per run the detector may be rebuilt from intensity seeds after over-detecting.
	 *
	 * <p>A rebuild that itself over-detects would otherwise rebuild on every window forever. Two is
	 * enough to recover a detector poisoned by one bad round and few enough that a detector which
	 * cannot be recovered says so quickly.</p>
	 */
	private static final int MAX_DETECTOR_REBUILDS_PER_RUN = 2;

	private static final String SHARED_DETECTOR_REBUILDS = "adaptive_track.detector_rebuilds";

	/**
	 * Rebuilds the detection model from intensity seeds after it was caught detecting background, and
	 * says whether the caller should retry the window with it.
	 *
	 * <p>Only on fluorescence. There, "where are the bright things" is answerable from the pixels
	 * without any model, so a detector can always be rebuilt from the image itself — which is the whole
	 * reason the cold-start bootstrap exists. Where brightness does not mean signal the seeds would be
	 * arbitrary, and abandoning is the honest outcome.</p>
	 *
	 * <p>Only for over-detection. A window abandoned because a cleanup pass took a frame below the
	 * user's floor, or wrote outside its range, has a working detector and a broken pass; retraining
	 * the detector would not touch the cause.</p>
	 *
	 * <p>The window has already been rolled back when this runs, so the seeds are placed on a clean
	 * window and the retry starts from the state the window had before any of this.</p>
	 *
	 * @return {@code true} when the model was rebuilt and the window is worth another attempt
	 */
	/**
	 * Why a model already trained on this movie must not be reset from intensity seeds, or {@code null} when it may.
	 * Seeds are the frame's brightest maxima, which are cells only in fluorescence ({@link #rebuildDetectorFromSeeds}
	 * refuses for the same reason). The first window is exempt: before any window is committed the model knows
	 * nothing of the movie, and seeds are how it starts (condition E's seeding). PSC 01, condition E (2026-10-20):
	 * a trained model was retrained from 20 intensity seeds per frame on phase contrast at t=230.
	 */
	static String seedResetRefusal( final ToolContext context, final ViewerPanel viewer, final int startTp,
			final int lastPlausibleWindowEnd )
	{
		if ( lastPlausibleWindowEnd < startTp )
			return null;
		final ImageProfile profile = ImageProfile.forRun( context,
				() -> AutoCorrectOrchestrator.sampleIntensities( context,
						viewer.state().getCurrentTimepoint(), ImageProfile.SAMPLES ) );
		return profile.fluorescenceLike ? null
				: "this dataset does not read as fluorescence, so intensity seeds would be arbitrary, and the model is "
						+ "already trained on this movie";
	}

	private static boolean rebuildDetectorFromSeeds( final ToolContext context,
			final ElephantIntegration elephant, final ViewerPanel viewer, final int wStart, final int wEnd,
			final int[] allTps, final int nSeedTimepoints, final int nSeedsPerTp, final int nCrops,
			final int nEpochs, final long trainTimeoutMs, final String reason )
	{
		if ( reason == null || !reason.contains( "exploded" ) )
			return false;
		final ImageProfile profile = ImageProfile.forRun( context,
				() -> AutoCorrectOrchestrator.sampleIntensities( context,
						viewer.state().getCurrentTimepoint(), ImageProfile.SAMPLES ) );
		if ( !profile.fluorescenceLike )
		{
			context.log( "  Not rebuilding the detector: this dataset does not read as fluorescence, so "
					+ "intensity seeds would be arbitrary. " + profile.describe() );
			return false;
		}
		final Object done = context.getSharedData( SHARED_DETECTOR_REBUILDS );
		final int used = done instanceof Number ? ( ( Number ) done ).intValue() : 0;
		if ( used >= MAX_DETECTOR_REBUILDS_PER_RUN )
		{
			context.log( String.format(
					"  Not rebuilding the detector again: it has already been rebuilt from seeds %d time(s) "
							+ "this run and is still detecting background. Something other than the model is "
							+ "wrong — check the expected cell count you stated, and the image itself.",
					used ) );
			return false;
		}
		context.setSharedData( SHARED_DETECTOR_REBUILDS, used + 1 );
		context.log( String.format(
				"  Rebuilding the detector from intensity seeds (%d of %d allowed this run) — on "
						+ "fluorescence the image itself says where the bright things are, so a detector "
						+ "caught detecting background can be rebuilt without it.",
				used + 1, MAX_DETECTOR_REBUILDS_PER_RUN ) );
		final String failure = bootstrapColdStart( context, elephant, viewer, wStart, wEnd, allTps,
				nSeedTimepoints, nSeedsPerTp, 0.0, 0.0, 0.0, 0.0, nCrops, nEpochs, trainTimeoutMs, null,
				0.0 );
		if ( failure != null )
		{
			context.log( "  Detector rebuild did not complete: " + failure );
			return false;
		}
		// The bootstrap leaves its own re-detection in the window; the retry re-detects from scratch.
		deleteAllSpotsAtTimepoints( context, allTps );
		return true;
	}

	/**
	 * Links the window's spots, backwards from its last timepoint.
	 *
	 * <p>Extracted because it is needed twice: once as step [4/6], and once when a later phase is
	 * abandoned and the window is put back to the last state that passed its invariant check. A spot
	 * snapshot carries no edges, so a restore that did not re-link would hand back a window of
	 * unlinked detections and call it the state it had at [4/6].</p>
	 *
	 * @return how many timepoints ELEPHANT linked
	 */
	// Public for PassReplay, which re-detects and re-links a slice of a saved run exactly as [4/6] does.
	public static int linkWindow( final ToolContext context, final ViewerPanel viewer,
			final int wStart, final int wEnd )
	{
		int linkOk = 0;
		for ( int t = wEnd; t >= wStart; t-- )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() ) break;
			if ( !setTimepoint( viewer, t, DEFAULT_LOAD_DELAY_MS ) ) break;
			final ElephantIntegration e = new ElephantIntegration( context );
			if ( !e.init() ) continue;
			if ( e.linkSpots() ) linkOk++;
		}
		return linkOk;
	}

	/**
	 * Abandons the window because a structural invariant was violated.
	 *
	 * <p>Every one of the five ERROR-severity invariants means the window's result cannot be trusted:
	 * a human-confirmed spot vanished, a window-scoped phase wrote outside its range, detection
	 * exploded into background, a frame was taken below what the user's own estimate allows, or a
	 * timepoint was emptied. Until now each was printed and the window carried on — a run could
	 * announce "Detection has exploded into background" and take that window into the next nine.</p>
	 *
	 * <p>Abandoning means what it means on the explosion path: the model that produced this is
	 * restored, the window's spots are put back as they were, and the sweep moves on. The run's
	 * coverage report then says which timepoints were left untouched, which is a far more useful thing
	 * to hand the caller than a movie tracked partly from background.</p>
	 */
	private static boolean abandonWindowOnInvariant( final ToolContext context,
			final ElephantIntegration elephant, final ViewerPanel viewer, final int wStart, final int wEnd,
			final Map< Integer, List< double[] > > restoreTo, final boolean keptDetection,
			final int nSeedTimepoints, final int nSeedsPerTp, final int nCrops, final int nEpochs,
			final long trainTimeoutMs, final String phase, final String reason )
	{
		context.log( keptDetection
				? String.format(
						"  Window [t=%d..%d] STOPPED after %s — %s The detection this window committed is "
								+ "KEPT and re-linked; every phase after %s was skipped. The coverage line at "
								+ "the end of this run says which windows these are.",
						wStart, wEnd, phase, reason, phase )
				: String.format(
						"  Window [t=%d..%d] ABANDONED after %s — %s This window's timepoints are left "
								+ "exactly as they were, and the coverage line at the end of this run says so.",
						wStart, wEnd, phase, reason ) );
		rollBackWindow( context, elephant, wStart, wEnd, restoreTo, keptDetection );
		// Asked here rather than at each call site, so that "this failure cannot be fixed by rebuilding
		// the detector" is a decision made once. rebuildDetectorFromSeeds answers no for every reason
		// but over-detection: a window abandoned because a cleanup pass took a frame below the user's
		// floor, or wrote outside its range, has a working detector and a broken pass.
		// Not when a pretrained detector detects: ELEPHANT is not this run's detector, so rebuilding and
		// retraining it changes nothing the retry would use -- the user saw ELEPHANT train in a run where
		// they had chosen StarDist.
		final boolean retry = !org.mastodon.mamut.automahout.tool.remote.DetectorTrial.pretrainedChosen( context )
				&& rebuildDetectorFromSeeds( context, elephant, viewer, wStart, wEnd,
						allTimepointsArray( wStart, wEnd ), nSeedTimepoints, nSeedsPerTp, nCrops, nEpochs,
						trainTimeoutMs, reason );
		// A spot snapshot carries no edges, so the restore above has left the kept detection unlinked.
		// Skipped when the window is about to be retried from scratch, which would throw the links away.
		if ( keptDetection && !retry )
		{
			final int linked = linkWindow( context, viewer, wStart, wEnd );
			context.log( String.format( "  Re-linked the kept detection over %d timepoint(s).", linked ) );
		}
		return retry;
	}

	/**
	 * Asks the user whether detections over the ceiling are real cells, when the ceiling is only the
	 * agent's guess. {@code false} (roll back as before) whenever it is not a guess, or was already
	 * upheld, or nobody could be asked.
	 *
	 * @param threshold the spots/tp the window went over
	 */
	private static boolean keepOverGuessedCeiling( final ToolContext context, final boolean ceilingFromUser,
			final int wStart, final int wEnd, final double threshold )
	{
		if ( !GuessedCeiling.isUnconfirmedGuess( context, ceilingFromUser ) )
			return false;
		int worstTp = wStart, worstCount = -1;
		for ( int t = wStart; t <= wEnd; t++ )
		{
			final int n = countCellsAtTimepoints( context, new int[] { t } );
			if ( n > worstCount )
			{
				worstCount = n;
				worstTp = t;
			}
		}
		final int first = countCellsAtTimepoints( context, new int[] { wStart } );
		return GuessedCeiling.ask( context, SHARED_COLD_START_CEILING_OVERRIDE, String.format(
				"Window t=%d..%d detected %d cells at t=%d and %d at t=%d; t=%d holds %d.", wStart, wEnd,
				first, wStart, countCellsAtTimepoints( context, new int[] { wEnd } ), wEnd, worstTp,
				worstCount ), threshold, first, wStart ) == GuessedCeiling.Decision.KEEP;
	}

	/**
	 * Restores the model and the window's spots, so that "abandoned" means the window was not touched.
	 *
	 * <p>Shared by the detection-explosion path and the invariant path, because the two have to leave
	 * the project in the same state: anything else makes a partially-processed window indistinguishable
	 * from a processed one in everything downstream.</p>
	 */
	private static void rollBackWindow( final ToolContext context, final ElephantIntegration elephant,
			final int wStart, final int wEnd, final Map< Integer, List< double[] > > restoreTo,
			final boolean keptDetection )
	{
		// No snapshot of ELEPHANT's model is taken when a pretrained detector detects, so the file holds an
		// older window's (or run's) model: restoring it would overwrite the server's model with that.
		if ( !org.mastodon.mamut.automahout.tool.remote.DetectorTrial.pretrainedChosen( context ) )
			elephant.restoreDetectionModel( modelSnapshotFile() );
		// Everything downstream of the abandon is skipped, so whatever the window happens to hold at
		// this moment is what the user is left with, and that is a half-processed, possibly unlinked
		// window. Restoring makes "abandoned" mean untouched, which is what the word has to mean for
		// the rest of the movie to stay interpretable.
		final int[] tps = allTimepointsArray( wStart, wEnd );
		deleteAllSpotsAtTimepoints( context, tps );
		restoreSpots( context, restoreTo, wStart, wEnd );
		// Report what the window HOLDS, not what restoreSpots re-added. Those differ whenever the
		// window's previous content was protected: deleteAllSpotsAtTimepoints spares protected spots, so
		// restoring them finds them already there and skips every one, and reporting its return value
		// said "rolled back to the 0 spot(s) it held" for a window correctly left holding several hundred.
		context.log( keptDetection
				? String.format( "  Window [t=%d..%d] put back to its last checked state — it now holds "
						+ "%d spot(s), the detection that passed its own checks, rather than the result of "
						+ "the phase that failed.", wStart, wEnd, countAnnotationsAtTimepoints( context, tps ) )
				: String.format( "  Window [t=%d..%d] rolled back — it now holds the %d spot(s) it held "
						+ "before this window ran, left exactly as it was rather than half-processed.",
						wStart, wEnd, countAnnotationsAtTimepoints( context, tps ) ) );
	}

	/**
	 * Abandons the window because detection exploded, and says whether to retry it.
	 *
	 * <p>Returns rather than logs-and-continues because for one release it did the latter: the retry
	 * was wired into the invariant abandon path and not into this one, which is the path the very first
	 * detection of a run actually takes. A run therefore detected 1185 spots at t=0 where 100 were
	 * expected, abandoned the window, and moved to the next one with the same detector — ten times,
	 * once per window. Both abandon paths now answer the same question in the same place.</p>
	 *
	 * @return {@code true} when the detector was rebuilt and this window is worth another attempt
	 */
	private static boolean abandonWindowOnExplosion( final ToolContext context,
			final ElephantIntegration elephant, final ViewerPanel viewer, final int wStart, final int wEnd,
			final Map< Integer, List< double[] > > spotsBefore, final int nSeedTimepoints,
			final int nSeedsPerTp, final int nCrops, final int nEpochs, final long trainTimeoutMs,
			final DetectionExplosionException ex )
	{
		context.log( String.format( "  Window [t=%d..%d] ABANDONED: %s.", wStart, wEnd, ex.getMessage() ) );
		// Detection itself is what failed here, so there is no checked state within this window to keep.
		rollBackWindow( context, elephant, wStart, wEnd, spotsBefore, false );
		// Over a ceiling nobody stated, the detector may be right and the guess wrong; the user decides.
		// Kept means retry the window with the user's count, on the same detector — rebuilding a
		// detector that was right would throw away its training for nothing.
		if ( GuessedCeiling.isUnconfirmedGuess( context,
				context.getSharedData( SHARED_COLD_START_CEILING_OVERRIDE ) instanceof Number )
				&& GuessedCeiling.ask( context, SHARED_COLD_START_CEILING_OVERRIDE, String.format(
						"Detection at t=%d found %d spots, and the window t=%d..%d was abandoned.",
						ex.timepoint, ex.count, wStart, wEnd ),
						ex.threshold, ex.count, ex.timepoint ) == GuessedCeiling.Decision.KEEP )
			return true;
		return rebuildDetectorFromSeeds( context, elephant, viewer, wStart, wEnd,
				allTimepointsArray( wStart, wEnd ), nSeedTimepoints, nSeedsPerTp, nCrops, nEpochs,
				trainTimeoutMs, "exploded" );
	}

	/**
	 * A refinement round's spots/tp at or below this multiple of the PREVIOUS window's own spots/tp
	 * reads as already-plausible — real cell counts rarely swing far across just one window_size worth
	 * of timepoints, so there is no need to keep chasing an absolute completeness_target once the count
	 * is this close to recent history.
	 */
	private static final double PREV_WINDOW_PLAUSIBLE_MAX = 1.3;

	/**
	 * A refinement round's spots/tp above this multiple of the PREVIOUS window's own spots/tp reads as a
	 * suspected explosion, regardless of what the absolute {@link #isColdStartRegime} ceiling or
	 * {@link #ZERO_INTENSITY_FLOOD_FRACTION} check say — those are absolute thresholds and can miss a
	 * runaway that stays under them but is still wildly inconsistent with recent, gradually-changing
	 * history (e.g. a dataset whose normal count is only ~20-40 spots/tp exploding to ~100 is nowhere
	 * near the 300 default cold-start ceiling, but is obviously not real recall improvement).
	 */
	private static final double PREV_WINDOW_EXPLOSION_RATIO = 2.0;

	/**
	 * Picks a world-coord centre for the crop-tuning warm-up: the intensity-maxima seed (see
	 * {@link #generateIntensitySeeds} — a cheap, ELEPHANT-free image scan, no server round trip) with the
	 * most OTHER seeds within {@code clusterRadius} — i.e. the densest cell cluster, so the tuning crop
	 * actually contains cells to learn from instead of empty background. Falls back to the image centre
	 * if no seeds are found (e.g. an extremely sparse/dim frame).
	 */
	private static double[] pickTuningRoiCenter( final ViewerPanel viewer, final int timepoint,
			final double[] physDims, final int nSeeds, final double clusterRadius )
	{
		final List< double[] > seeds = generateIntensitySeeds( viewer, timepoint, nSeeds,
				2.0 * DEFAULT_SEED_RADIUS_WORLD, SEED_INTENSITY_PERCENTILE );
		if ( seeds.isEmpty() )
			return new double[] { physDims[ 0 ] / 2, physDims[ 1 ] / 2, physDims[ 2 ] / 2 };
		double[] best = seeds.get( 0 );
		int bestCount = -1;
		for ( final double[] s : seeds )
		{
			int count = 0;
			for ( final double[] o : seeds )
			{
				final double dx = s[ 0 ] - o[ 0 ], dy = s[ 1 ] - o[ 1 ], dz = s[ 2 ] - o[ 2 ];
				if ( dx * dx + dy * dy + dz * dz <= clusterRadius * clusterRadius )
					count++;
			}
			if ( count > bestCount )
			{
				bestCount = count;
				best = s;
			}
		}
		return best;
	}

	/**
	 * As {@link #pickTuningRoiCenter}, but returns up to {@code nBatches} SPATIALLY DISTINCT densest
	 * clusters instead of just the single best — greedily picks the next-densest cluster centre that is
	 * at least {@code clusterRadius * 2} away from every centre already chosen, so the returned regions
	 * don't overlap and actually sample different parts of the frame (see {@link #TUNING_ROI_BATCH_COUNT}
	 * for why a single region generalises poorly). Reuses one {@link #generateIntensitySeeds} scan for
	 * all picks — no extra image-scan cost per batch.
	 *
	 * @return 1..{@code nBatches} centres (fewer if the frame doesn't have that many well-separated
	 *         dense clusters, e.g. very sparse/small data) — callers should cycle through however many
	 *         come back via modulo, never assume exactly {@code nBatches}
	 */
	private static List< double[] > pickTuningRoiCenters( final ViewerPanel viewer, final int timepoint,
			final double[] physDims, final int nSeeds, final double clusterRadius, final int nBatches )
	{
		final List< double[] > seeds = generateIntensitySeeds( viewer, timepoint, nSeeds,
				2.0 * DEFAULT_SEED_RADIUS_WORLD, SEED_INTENSITY_PERCENTILE );
		final List< double[] > centers = new ArrayList<>();
		if ( seeds.isEmpty() )
		{
			centers.add( new double[] { physDims[ 0 ] / 2, physDims[ 1 ] / 2, physDims[ 2 ] / 2 } );
			return centers;
		}
		final double minSeparationSq = ( clusterRadius * 2.0 ) * ( clusterRadius * 2.0 );
		for ( int pick = 0; pick < nBatches; pick++ )
		{
			double[] best = null;
			int bestCount = -1;
			for ( final double[] s : seeds )
			{
				boolean tooCloseToChosen = false;
				for ( final double[] c : centers )
				{
					final double dx = s[ 0 ] - c[ 0 ], dy = s[ 1 ] - c[ 1 ], dz = s[ 2 ] - c[ 2 ];
					if ( dx * dx + dy * dy + dz * dz < minSeparationSq )
					{
						tooCloseToChosen = true;
						break;
					}
				}
				if ( tooCloseToChosen )
					continue;
				int count = 0;
				for ( final double[] o : seeds )
				{
					final double dx = s[ 0 ] - o[ 0 ], dy = s[ 1 ] - o[ 1 ], dz = s[ 2 ] - o[ 2 ];
					if ( dx * dx + dy * dy + dz * dz <= clusterRadius * clusterRadius )
						count++;
				}
				if ( count > bestCount )
				{
					bestCount = count;
					best = s;
				}
			}
			if ( best == null )
				break; // no more well-separated dense clusters left
			centers.add( best );
		}
		if ( centers.isEmpty() )
			centers.add( pickTuningRoiCenter( viewer, timepoint, physDims, nSeeds, clusterRadius ) );
		return centers;
	}

	/**
	 * Cheaply estimates a window's expected FULL-FRAME spots/tp by sampling a
	 * {@link #DENSITY_PROBE_GRID_SIZE}x{@link #DENSITY_PROBE_GRID_SIZE} grid of small, crop-restricted
	 * predictions at ONE representative timepoint and extrapolating by area ratio — instead of paying
	 * full-frame predict cost just to discover a window is an obvious flood. Several grid samples (not a
	 * single crop) guard against a spatially non-uniform cell density skewing the estimate from one
	 * lucky/unlucky patch.
	 *
	 * <p>Every probe spot is deleted before returning — this exists PURELY to inform the cold-start
	 * decision below, never to produce usable detections.</p>
	 *
	 * @return estimated spots/tp for the WHOLE frame, or {@code 0.0} if no patch could be sampled
	 */
	private static double estimateGridDensity( final ToolContext context, final ViewerPanel viewer,
			final int probeTp, final double[] physDims, final int gridSize,
			final double intensityRatio, final double qualityThreshold, final double minSize )
	{
		if ( !setTimepoint( viewer, probeTp, DEFAULT_LOAD_DELAY_MS ) )
			return 0.0;
		deleteAllSpotsAtTimepoints( context, new int[] { probeTp } );

		final double patchWidth = physDims[ 0 ] / gridSize;
		final double patchHeight = physDims[ 1 ] / gridSize;
		final double patchArea = patchWidth * patchHeight;
		final double totalArea = physDims[ 0 ] * physDims[ 1 ];

		int totalCount = 0;
		int sampledPatches = 0;
		final int totalPatches = gridSize * gridSize;
		// Every probe below is crop-restricted, so the rule the tuning loop follows applies here too: no
		// full-frame count may be compared against a patch's, and the background these detections are
		// judged against is the patch's own. Cleared immediately after the loop — see
		// ExpectedCellCount.SHARED_CROP_SCOPED.
		context.setSharedData( ExpectedCellCount.SHARED_CROP_SCOPED, Boolean.TRUE );
		outer:
		for ( int gy = 0; gy < gridSize; gy++ )
		{
			for ( int gx = 0; gx < gridSize; gx++ )
			{
				if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
					break outer;
				// Labelled explicitly so this is not mistaken for the window's real test detection right
				// below it in the log — every spot here is discarded before this method returns.
				context.log( String.format(
						"    Density probe %d/%d (small throwaway patch, discarded after estimating "
								+ "density)...", gy * gridSize + gx + 1, totalPatches ) );
				final double cx = patchWidth * ( gx + 0.5 );
				final double cy = patchHeight * ( gy + 0.5 );
				final double[] cropBox = { cx, cy, 0, cx, cy, 0 };
				final Map< String, long[] > probeMark = PhaseTimings.mark( context );
				final ElephantIntegration e = new ElephantIntegration( context );
				long probeStep = System.nanoTime();
				if ( e.init() && e.predictSpotsInRegionAndAdd( probeTp, cropBox ) )
				{
					PhaseTimings.record( context, "ELEPHANT predict", probeStep );
					if ( intensityRatio > 0.0 || qualityThreshold > 0.0 )
					{
						probeStep = System.nanoTime();
						AutoCorrectOrchestrator.filterWeakSpots(
								context, probeTp, intensityRatio, qualityThreshold );
						PhaseTimings.record( context, "intensity/quality filter", probeStep );
					}
					probeStep = System.nanoTime();
					deleteFlatContrastSpots( context, probeTp, 0.0 );
					PhaseTimings.record( context, "flat-contrast check", probeStep );
					probeStep = System.nanoTime();
					filterNoiseBySize( context, probeTp, minSize );
					PhaseTimings.record( context, "size filter", probeStep );
					totalCount += countCellsAtTimepoints( context, new int[] { probeTp } );
					sampledPatches++;
				}
				deleteAllSpotsAtTimepoints( context, new int[] { probeTp } );
				final String probeTiming =
						PhaseTimings.summarySince( context, probeMark, SLOW_TIMEPOINT_REPORT_NANOS );
				if ( !probeTiming.isEmpty() )
					context.log( "  Density probe" + probeTiming.substring(
							probeTiming.indexOf( "Time:" ) - 1 ) );
			}
		}
		context.setSharedData( ExpectedCellCount.SHARED_CROP_SCOPED, Boolean.FALSE );
		if ( sampledPatches == 0 )
			return 0.0;
		final double densityPerArea = totalCount / ( patchArea * sampledPatches );
		final double estimate = densityPerArea * totalArea;
		context.log( String.format(
				"    Density probe estimate: ~%.0f spots/tp full-frame (from %d/%d patch(es)).",
				estimate, sampledPatches, totalPatches ) );
		return estimate;
	}

	/**
	 * Crop-tuning warm-up: before the (expensive, full-frame) per-window pipeline runs at all on a
	 * brand-new dataset, gets the model out of "totally untrained" state using a FAST, small region
	 * instead of the whole frame — the same bootstrap idea as {@link #bootstrapColdStart}, but every
	 * predict call is restricted to a small crop (see {@link ElephantIntegration#predictSpotsInRegionAndAdd})
	 * so each iteration costs a fraction of a full-frame predict, and training operates on a small, curated
	 * set of examples instead of tens of thousands of spots dominated by noise. Only ever called once, when
	 * the whole requested range has zero existing spots (a genuinely fresh run) — a partially-tracked resume
	 * skips this and relies on the already-trained model.
	 *
	 * <p>All spots this warm-up creates (seeds and crop-tuning detections) are deleted before returning —
	 * it exists purely to adapt the model's WEIGHTS; the graph itself is left exactly as the caller found
	 * it, so the normal per-window loop runs unmodified afterwards.</p>
	 */
	/**
	 * What a VLM sees at {@code t} (count, nucleus size), for the warm-up's seeds; null on non-fluorescence data, with
	 * no VLM configured, or when it gives no usable answer. Logged either way.
	 */
	/**
	 * Re-centres ELEPHANT's detection radius range on the cell radius read at {@code t} (the VLM's, or the warm-up seeds') when that radius lies
	 * outside the range. ELEPHANT drops every prediction outside r_min..r_max, so a range set from the movie's
	 * typical cell cannot hold cells far from it: CE 01's range 0.60..3.02 um (median radius 1.21 um over three
	 * frames) excluded the t=0 nuclei of radius ~4.3 um, and training on them found 0-1 of 5 at 0.35x their size.
	 */
	static void fitRadiusRangeToSeen( final ToolContext context, final double radius, final int t )
	{
		if ( !( radius > 0 ) )
			return;
		try
		{
			final org.elephant.setting.main.ElephantMainSettings s = ElephantMainSettingsManager.getInstance().getSelectedStyle();
			if ( radius >= s.getMinRadius() && radius <= s.getMaxRadius() )
				return;
			final String was = String.format( "%.2f..%.2f", s.getMinRadius(), s.getMaxRadius() );
			context.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, radius );
			context.log( String.format( "  Cell size at t=%d: the cells read a radius of %.2f µm, outside ELEPHANT's detection "
					+ "range %s µm, which drops anything outside it -> %s.", t, radius, was,
					org.mastodon.mamut.automahout.tool.ElephantDataSettings.apply( s, radius ) ) );
		}
		catch ( final RuntimeException e )
		{
			context.log( "  Cell size: could not re-centre ELEPHANT's radius range (" + e.getMessage() + ")." );
		}
	}

	private static org.mastodon.mamut.automahout.tool.VisualColdStart.Understanding visualUnderstanding(
			final ToolContext context, final ViewerPanel viewer, final int t )
	{
		final org.mastodon.mamut.automahout.vision.VlmBackend backend =
				( org.mastodon.mamut.automahout.vision.VlmBackend ) context.getSharedData( SHARED_VLM_BACKEND );
		final Object model = context.getSharedData( SHARED_VLM_MODEL );
		if ( backend == null || model == null )
			return null;
		final ImageProfile profile = ImageProfile.forRun( context,
				() -> AutoCorrectOrchestrator.sampleIntensities( context, t, ImageProfile.SAMPLES ) );
		if ( !profile.fluorescenceLike )
			return null;
		try
		{
			final org.mastodon.mamut.automahout.tool.VisualColdStart.Understanding u =
					org.mastodon.mamut.automahout.tool.VisualColdStart.understand( firstSource( context ), t,
							viewer.state().getNumTimepoints() - 1, backend, model.toString() );
			if ( u == null )
				context.log( "  Crop-tuning warm-up: the VLM gave no usable count and size for t=" + t + "; brightest maxima instead." );
			else
				context.log( String.format( "  Crop-tuning warm-up: the VLM sees %d nucle%s of about %.1f µm at t=%d (\"%s\").",
						u.count, u.count == 1 ? "us" : "i", u.diameter, t, u.reasoning ) );
			return u;
		}
		catch ( final java.io.IOException | RuntimeException e )
		{
			context.log( "  Crop-tuning warm-up: the VLM could not be asked about t=" + t + " (" + e.getMessage()
					+ "); brightest maxima instead." );
			return null;
		}
	}

	private static void warmUpModelOnCrop( final ToolContext context, final ElephantIntegration elephant,
			final ViewerPanel viewer, final int startTp, final int endTp, final double[] physDims,
			final int nSeedsPerTp, final int nCrops, final int nEpochs, final long trainTimeoutMs )
	{
		// Near the START of the range, not spread across all of it. On a 500-frame mouse embryo this
		// picked t=2 and t=499 — the first and last frame of a developing embryo, which are two
		// different-looking problems — trained one model on both at once, and left it fitted to
		// whichever contributed more seeds. Measured immediately afterwards: 72 detections at t=499 and
		// 4 at t=2, and the per-window loop then starts at t=2, where the model is at its worst.
		final int warmupEnd = Math.min( endTp, startTp + TUNING_WARMUP_SPAN_TIMEPOINTS );
		final int[] tuningTps = pickSeedTimepoints( startTp, warmupEnd, TUNING_WARMUP_TIMEPOINTS );

		// Look first, as a person does (the user's design, 2026-10-24): a VLM sees the first frame's whole stack and
		// says how many nuclei there are and how big; a blob detector at that size finds them. On CE 01 the
		// brightest-maxima seeds found nothing at t=0 and the untrained model's noise was taught; at the VLM's size
		// the blobs were 4 of 4, 7 of 7, 14 of 15 nuclei at t=0/10/30, none on background. See VisualColdStart.
		final org.mastodon.mamut.automahout.tool.VisualColdStart.Understanding seen = visualUnderstanding( context, viewer,
				tuningTps[ 0 ] );
		if ( seen != null )
			fitRadiusRangeToSeen( context, seen.diameter / 2, tuningTps[ 0 ] );
		// The region the tuning rounds re-detect and retrain on: where those nuclei are. Chosen from the densest
		// brightest maxima it sat on noise at CE 01 t=0, 6-11 um from every nucleus; the rounds retrained on what
		// they found there and the commit put its 4 detections on that noise.
		final List< double[] > firstBlobs = seen == null ? null
				: org.mastodon.mamut.automahout.tool.VisualColdStart.blobs( firstSource( context ), tuningTps[ 0 ], seen.diameter / 2 );
		final double[] roiCenter;
		if ( firstBlobs != null && !firstBlobs.isEmpty() )
		{
			roiCenter = medianPosition( firstBlobs.subList( 0, Math.min( nSeedsPerTp, firstBlobs.size() ) ) );
			context.log( String.format( "  Crop-tuning warm-up: region centred on the %d nucle%s the VLM's size finds at t=%d, "
					+ "around (%.0f, %.0f, %.0f) µm.", Math.min( nSeedsPerTp, firstBlobs.size() ), firstBlobs.size() == 1 ? "us" : "i",
					tuningTps[ 0 ], roiCenter[ 0 ], roiCenter[ 1 ], roiCenter[ 2 ] ) );
		}
		else
		{
			final double clusterRadius =
					TUNING_ROI_CLUSTER_RADIUS_FRACTION * Math.min( physDims[ 0 ], physDims[ 1 ] );
			roiCenter = pickTuningRoiCenter( viewer, startTp, physDims, Math.max( 20, nSeedsPerTp ), clusterRadius );
			context.log( String.format(
					"  Crop-tuning warm-up: auto-selected region around (%.0f, %.0f, %.0f) µm "
							+ "(densest intensity-maxima cluster) to adapt the model quickly before full-frame "
							+ "tracking.", roiCenter[ 0 ], roiCenter[ 1 ], roiCenter[ 2 ] ) );
		}
		final double[] cropBox = { roiCenter[ 0 ], roiCenter[ 1 ], roiCenter[ 2 ],
				roiCenter[ 0 ], roiCenter[ 1 ], roiCenter[ 2 ] };
		if ( warmupEnd < endTp )
			context.log( String.format(
					"  Crop-tuning warm-up: adapting the model's weights on t=%s before tracking starts — "
							+ "t=%d..%d rather than the whole range, because a dataset whose appearance "
							+ "changes over time cannot be warmed up on its first and last frame at once. "
							+ "This is NOT tracking: per-window tracking still begins at t=%d, and every "
							+ "spot these frames produce is deleted again before it does.",
					joinTimepoints( tuningTps ), startTp, warmupEnd, startTp ) );

		// Detect first. The warm-up was written for a model that knows nothing yet; the model the user's ELEPHANT
		// settings name may already be trained on this very data, and teaching it the brightest maxima first
		// (on a mouse embryo, puncta among them) overwrites what it knew with cruder labels. A model this run
		// created from random weights (the benchmark) is bootstrapped as before, without this check.
		if ( !Boolean.TRUE.equals( context.getSharedData( ElephantIntegration.SHARED_MODEL_FROM_RANDOM_WEIGHTS ) ) )
		{
			final double[] found = detectInTuningRegion( context, viewer, tuningTps, cropBox );
			deleteAllSpotsAtTimepoints( context, tuningTps );
			final double ceiling = ExpectedCellCount.ceilingAt( context, tuningTps[ tuningTps.length - 1 ], -1,
					Double.POSITIVE_INFINITY );
			if ( alreadyDetects( found[ 0 ], ceiling ) )
			{
				context.log( String.format( "  Crop-tuning warm-up: SKIPPED — the model already detects %.1f spot(s)/tp in "
						+ "the tuning region before any training here%s, so it is treated as trained and tracking starts "
						+ "from it as it is (no intensity seeds, no warm-up training).", found[ 0 ],
						Double.isInfinite( ceiling ) ? "" : String.format( " (at most %.0f expected)", ceiling ) ) );
				return;
			}
			context.log( String.format( "  Crop-tuning warm-up: the model detects %.1f spot(s)/tp in the tuning region "
					+ "before any training here%s — treated as untrained; bootstrapping it from intensity seeds.",
					found[ 0 ], found[ 0 ] > 0 ? String.format( ", over the %.0f expected", ceiling ) : "" ) );
		}

		// Round 1: model-free intensity seeds (tp, whole-frame scan, no ELEPHANT call) PLUS explicit
		// background negatives (fp) — identical signal to bootstrapColdStart's own seeding (see
		// placeBackgroundNegatives's doc for why tp-only seeding self-floods), just trained here instead
		// of inside the per-window loop.
		int totalSeeds = 0;
		int totalNegatives = 0;
		// Seeds something vouches for as cells -- the blobs the VLM accounted for, or a pretrained detector's persisting
		// detections -- and their radius: these are trained on until the model reproduces them. Intensity maxima are not.
		final java.util.Map< Integer, List< double[] > > blobSeeds = new java.util.LinkedHashMap<>();
		double annotatedRadius = Double.NaN;
		for ( final int t : tuningTps )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				return;
			if ( !setTimepoint( viewer, t, DEFAULT_LOAD_DELAY_MS ) )
				continue;
			// A pretrained detector's persisting detections when the run has a seed detector
			// (DetectorTrial.SHARED_SEED_DETECTOR); else blobs at the size the VLM saw; else the brightest maxima.
			List< double[] > pretrained =
					org.mastodon.mamut.automahout.tool.remote.DetectorTrial.persistentSeeds( context, t, nSeedsPerTp );
			// A seed detector that finds under half of what the VLM saw is not the better witness: on CE 01 t=0 the
			// rejected detectors' seed source gave 1 seed of 1.4 um where the VLM saw 6 of 10 um (truth 4 of 8.6).
			if ( pretrained != null && seen != null && pretrained.size() < 0.5 * Math.min( seen.count, nSeedsPerTp ) )
			{
				context.log( String.format( "  Crop-tuning warm-up: the pretrained seed detector found %d at t=%d, under half of "
						+ "the %d the VLM saw; blobs at the VLM's size instead.", pretrained.size(), t, seen.count ) );
				pretrained = null;
			}
			List< double[] > blobs = null;
			if ( pretrained == null && seen != null )
			{
				blobs = org.mastodon.mamut.automahout.tool.VisualColdStart.blobs( firstSource( context ), t, seen.diameter / 2 );
				if ( blobs.size() > nSeedsPerTp )
					blobs = new ArrayList<>( blobs.subList( 0, nSeedsPerTp ) );
				context.log( String.format( "  Crop-tuning warm-up: %d nucle%s at t=%d where the VLM saw %d of about %.1f µm "
						+ "(blobs at that size, brightest first).", blobs.size(), blobs.size() == 1 ? "us" : "i", t, seen.count,
						seen.diameter ) );
			}
			final List< double[] > seeds = pretrained != null ? positionsOf( pretrained )
					: blobs != null ? blobs
							: generateIntensitySeeds( viewer, t, nSeedsPerTp, 2.0 * DEFAULT_SEED_RADIUS_WORLD,
									SEED_INTENSITY_PERCENTILE );
			final double seedRadius = pretrained != null ? medianRadius( pretrained )
					: blobs != null ? seen.diameter / 2 : DEFAULT_SEED_RADIUS_WORLD;
			if ( pretrained != null )
				context.log( String.format( "  Crop-tuning warm-up: %d seed(s) at t=%d from the pretrained seed detector "
						+ "(median radius %.1f µm).", seeds.size(), t, seedRadius ) );
			final int placedTp = placeSeedSpots( context, seeds, t, seedRadius );
			totalSeeds += placedTp;
			if ( ( pretrained != null || blobs != null ) && !seeds.isEmpty() )
			{
				blobSeeds.put( t, seeds );
				annotatedRadius = seedRadius;
			}
			// Anchored on the SAME tp seeds (not scattered frame-wide) so the crop this warm-up tunes on
			// always contains both — see placeBackgroundNegatives's doc.
			final int negativeCount = ( int ) Math.round(
					placedTp * SEED_NEGATIVE_FRACTION / ( 1.0 - SEED_NEGATIVE_FRACTION ) );
			totalNegatives += placeBackgroundNegatives(
					context, t, seeds, negativeCount, 2.0 * seedRadius );
		}
		// Blobs the VLM accounted for need not reach the bar for anonymous maxima: a frame of four nuclei has four.
		final int minSeeds = seen != null
				? Math.max( 1, Math.min( MIN_SEEDS_FOR_AUTO_BOOTSTRAP, ( int ) Math.round( 0.5 * seen.count * tuningTps.length ) ) )
				: MIN_SEEDS_FOR_AUTO_BOOTSTRAP;
		if ( totalSeeds < minSeeds )
		{
			deleteAllSpotsAtTimepoints( context, tuningTps );
			context.log( "  Crop-tuning warm-up: too few confident intensity seeds — skipping warm-up "
					+ "(the normal per-window cold-start path will handle bootstrapping instead)." );
			return;
		}
		context.log( String.format(
				"  Crop-tuning warm-up: bootstrapping on %d seed spot(s) (tp) + %d background negative(s) "
						+ "(fp) across %d timepoint(s)...",
				totalSeeds, totalNegatives, tuningTps.length ) );
		// Seeds the VLM accounted for (or a pretrained detector's) are annotations, as a person's would be: trained on
		// until the model reproduces them (the user, 2026-10-24: a person who finds the training insufficient lets it run a while longer before
		// moving on), not trained on once and replaced by the model's own detections. On CE 01 t=0 one round on 5
		// such seeds left a model that detected nothing, and the frame stayed empty.
		if ( !blobSeeds.isEmpty() )
		{
			// The seeds are annotations the run can stand behind, as a person's would be: the window holding them trains
			// on them (the trusted-annotation path, as for a user's own annotations) instead of on the model's own
			// detections. On CE 01 t=0 the warm-up's model found 4 nuclei, then the window self-trained on those 4
			// against 13 synthesised background points and committed nothing.
			final double r = annotatedRadius;
			// The range must hold the cells being taught: CE 01 t=150's seeds (1.9 um) lay under the range set from the
			// VLM at t=0 (2.25..11.25 um), every prediction their size was dropped, and the model learned nothing.
			fitRadiusRangeToSeen( context, r, tuningTps[ 0 ] );
			final Map< Integer, List< double[] > > records = new HashMap<>();
			for ( final java.util.Map.Entry< Integer, List< double[] > > e : blobSeeds.entrySet() )
			{
				final List< double[] > l = new ArrayList<>();
				for ( final double[] p : e.getValue() )
				{
					final double[] rec = new double[ REC_LEN ];
					rec[ 0 ] = p[ 0 ];
					rec[ 1 ] = p[ 1 ];
					rec[ 2 ] = p[ 2 ];
					rec[ 3 ] = rec[ 7 ] = rec[ 11 ] = r * r;
					l.add( rec );
				}
				records.put( e.getKey(), l );
			}
			context.setSharedData( SHARED_COLD_START_SEEDS, records );
			if ( proofreadUntilReproduced( context, elephant, tuningTps, blobSeeds, annotatedRadius,
					seen != null ? seen.count : 0, false, nEpochs, nCrops, trainTimeoutMs, "Crop-tuning warm-up" ) )
				context.setSharedData( SHARED_GO_TIMEPOINTS, tuningTps.clone() );
			deleteAllSpotsAtTimepoints( context, tuningTps );
			return;
		}
		// UNPREPARED-TRAINING-OK: intensity seeds, not the model's own detections.
		if ( !elephant.trainDetectionModel( tuningTps, tuningTps[ tuningTps.length / 2 ], nEpochs, nCrops,
				trainTimeoutMs ) )
		{
			deleteAllSpotsAtTimepoints( context, tuningTps );
			context.log( "  Crop-tuning warm-up: bootstrap training did not complete — skipping warm-up." );
			return;
		}
		deleteAllSpotsAtTimepoints( context, tuningTps );

		// Rounds 2..N: region-restricted re-detect + retrain, small and fast, until the crop-local
		// detection looks plausible (not still flooding/elongated) or the round cap is reached.
		boolean collapsedToZero = false;
		boolean trainingFailed = false;
		int completedIterations = 0;
		for ( int iter = 1; iter <= TUNING_WARMUP_MAX_ITERATIONS; iter++ )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				break;
			final double[] found = detectInTuningRegion( context, viewer, tuningTps, cropBox );
			final double[] metrics = { found[ 0 ] };
			context.log( String.format(
					"  Crop-tuning warm-up (round %d/%d): spots/tp=%.1f in the tuning region (%d elongated "
							+ "excluded).", iter, TUNING_WARMUP_MAX_ITERATIONS, metrics[ 0 ], ( int ) found[ 1 ] ) );
			if ( metrics[ 0 ] <= 0 )
			{
				deleteAllSpotsAtTimepoints( context, tuningTps );
				collapsedToZero = true;
				break;
			}
			// UNPREPARED-TRAINING-OK: the warm-up's own seeded annotations.
			final boolean trained = elephant.trainDetectionModel(
					tuningTps, tuningTps[ tuningTps.length / 2 ], nEpochs, nCrops, trainTimeoutMs );
			deleteAllSpotsAtTimepoints( context, tuningTps );
			completedIterations = iter;
			if ( !trained )
			{
				trainingFailed = true;
				break;
			}
		}
		if ( collapsedToZero )
			context.log( "  WARNING: Crop-tuning warm-up collapsed to ZERO detections in the tuning "
					+ "region after re-detecting — this round did NOT converge, it FAILED (the model "
					+ "over-corrected toward suppressing everything, likely from the background-negative "
					+ "examples added this iteration). Proceeding to full-frame tracking with whatever "
					+ "state the model is actually in — the per-window cold-start check below will "
					+ "re-bootstrap if needed." );
		else if ( trainingFailed )
			context.log( "  WARNING: Crop-tuning warm-up training did not complete after "
					+ completedIterations + " round(s) — proceeding with whatever state the model is in." );
		else
			context.log( "  Crop-tuning warm-up complete — proceeding to full-frame tracking." );
	}

	/**
	 * The frames on which the warm-up reached Go. A state judged good is kept: on these frames the window does not
	 * self-train over it (the user, 2026-10-24: "it was going well, then suddenly went bad, and that got adopted"). On
	 * CE 01 t=0 the warm-up reached Go, the window's tuning then retrained on the model's own detections, the commit
	 * flooded to 90 and the refinement collapsed to 0; 78 were kept for 4 nuclei.
	 */
	static final String SHARED_GO_TIMEPOINTS = "adaptive_track.warmup_go_timepoints";

	/** The warm-up's VLM-accounted seeds as trusted-annotation records (REC_LEN, by timepoint), for the window to adopt. */
	static final String SHARED_COLD_START_SEEDS = "adaptive_track.cold_start_seeds";

	/**
	 * Whether the warm-up reached Go on any of {@code tps}: the window holding those frames starts from that model, and
	 * its self-training on its own detections is what undid it.
	 */
	static boolean goReachedOn( final ToolContext context, final int[] tps )
	{
		final Object o = context.getSharedData( SHARED_GO_TIMEPOINTS );
		if ( !( o instanceof int[] ) )
			return false;
		for ( final int t : tps )
			for ( final int g : ( int[] ) o )
				if ( g == t )
					return true;
		return false;
	}

	/**
	 * Before moving on, one training round on the window's finished result: its detections, shape-fitted, linked and
	 * corrected, as a person trains once more on the proofread window. Only where the review settled the model (the
	 * window's own self-training is otherwise skipped), so the model carries the cells' fitted shapes into the next
	 * window, whose review checks it before any training. The review's references stay spheres of the measured radius
	 * (fitted references stalled the first window on CE 01: 11-14 of 15 found for 20 rounds).
	 */
	private static void fineTuneOnFinishedWindow( final WindowRun w )
	{
		if ( !FINE_TUNE_AFTER_WINDOW || w.externalDetector != null || !goReachedOn( w.context, w.allTps )
				|| countAllSpots( w.context, w.wStart, w.wEnd ) == 0 )
			return;
		w.context.log( "  [6b/6] Fine-tune: one round of " + GO_EPOCHS + " epochs on this window's fitted, linked "
				+ "detections before moving on." );
		// Not prepareAnnotationsForTraining: its detection gate tags as fp every detection under 3 sigma of the
		// background, measured on a 2 µm mipmap -- in the first full run with this round it made 204 of 534 finished
		// detections at t=76..85 background (38%), the window's own small nuclei. The window is already proofread;
		// only one label per cell is enforced here, and nothing is claimed to be background.
		w.context.log( "    annotations in: " + DetectionTagLabels.tally( w.context.getModel(), w.context.getGraph(), w.allTps ) );
		withholdOverlappingAnnotations( w.context, w.allTps );
		withholdCoLocatedFromTraining( w.context, w.allTps );
		w.context.log( "    annotations out: " + DetectionTagLabels.tally( w.context.getModel(), w.context.getGraph(), w.allTps ) );
		final boolean ok = w.elephant.trainDetectionModel( w.allTps, w.allTps[ w.allTps.length / 2 ], GO_EPOCHS, w.nCrops,
				w.trainTimeoutMs );
		w.context.log( "  [6b/6] Fine-tune " + ( ok ? "done" : "did not complete; the model is the review's" ) + "." );
	}

	/** Whether {@link #fineTuneOnFinishedWindow} runs. */
	static final boolean FINE_TUNE_AFTER_WINDOW = true;

	/** Go: the model predicts at most this many times the larger of the VLM's count and the seeds. */
	static final double GO_MAX_FLOOD = 1.5;

	/** Go: the median predicted radius lies within these multiples of the seed radius. */
	static final double GO_SIZE_MIN = 0.67, GO_SIZE_MAX = 1.5;

	/**
	 * Training rounds before the warm-up moves on without a Go, and epochs per round. Measured on CE 01 t=0 with
	 * scripts/paper/train_probe.py, from random weights, on the 4 truth nuclei with every round's misses re-annotated
	 * (fn) and up to 5 false hits marked background: all 4 found and no false hit from round 15 of 20 epochs on, two
	 * rounds running; with 10-epoch rounds and 5 rounds (the earlier setting) the model was judged long before it
	 * could have learned them.
	 */
	static final int GO_MAX_ROUNDS = 20, GO_EPOCHS = 20;

	/** False hits marked background per frame and round, as a person corrects a few at a time (the probe's 5). */
	static final int GO_MARKS_PER_ROUND = 5;

	/** Go needs this many rounds running that pass {@link #goDecision}: a single round swings (2-4 of 4 late on). */
	static final int GO_CONSECUTIVE = 2;

	/**
	 * Go tolerates this share of the reference cells missed and this share of them in false hits: blobs at the
	 * frame's size are 94-100% of CE 01's cells with up to 3% on no cell, so a few references are not cells and a
	 * model that is right cannot reproduce them. Below 1/share references this is every one and none. The miss share
	 * is that 3%: at 5% CE 01 t=180 reached Go with 17 of 348 references missed (repsampled20), where blobs on no cell
	 * were 9 -- the rest were cells the model had not learned yet, and DET fell from 0.965 to 0.951 on them.
	 */
	static final double GO_MISS_SHARE = 0.03, GO_FALSE_SHARE = 0.02;

	/** Corrections per frame and round at most this share of the references (or {@link #GO_MARKS_PER_ROUND}). */
	static final double GO_MARK_SHARE = 0.1;

	/**
	 * Whether a round reproduces the references: all but {@link #GO_MISS_SHARE} of them found, at most
	 * {@link #GO_FALSE_SHARE} of them in false hits (a prediction on neither a reference nor a blob), no flood beyond
	 * {@code bound}, at the references' size. Judged only against what the run knows, never against truth.
	 */
	static boolean goDecision( final int found, final int refs, final int falseHits, final int predicted,
			final double bound, final double sizeRatio )
	{
		return refs > 0 && refs - found <= Math.floor( GO_MISS_SHARE * refs )
				&& falseHits <= Math.floor( GO_FALSE_SHARE * refs ) && predicted <= bound && sizeRatio >= GO_SIZE_MIN
				&& sizeRatio <= GO_SIZE_MAX;
	}

	/**
	 * Proofreads the model against reference cells round by round, as a person does in ELEPHANT, until
	 * {@link #goDecision} holds for {@link #GO_CONSECUTIVE} rounds running or {@link #GO_MAX_ROUNDS} rounds have run.
	 * The references must already be in the graph as annotations at {@code tps}. After each training round it predicts
	 * the whole frame (without touching the graph), reviews it ({@link FrameReview}) and corrects what it sees:
	 * <ul>
	 * <li>a reference the model missed is annotated again as a missed cell (fn);</li>
	 * <li>a false hit (more than two radii from every reference and more than one from every blob at that size) is
	 * marked background (fp).</li>
	 * </ul>
	 * At most {@link #GO_MARKS_PER_ROUND} or {@link #GO_MARK_SHARE} of the references of each kind per frame and round.
	 * With {@code checkFirst} the model is reviewed before any training, and one that already passes is kept as it is
	 * (a person moves on from a model that is right). Without a Go the best round's model is restored.
	 *
	 * @param label
	 *            what the log calls this ("Crop-tuning warm-up", "Review")
	 * @return whether it reached Go
	 */
	private static boolean proofreadUntilReproduced( final ToolContext context, final ElephantIntegration elephant,
			final int[] tps, final java.util.Map< Integer, List< double[] > > references, final double r,
			final int seenCount, final boolean checkFirst, final int nEpochs, final int nCrops, final long trainTimeoutMs,
			final String label )
	{
		final int epochs = Math.max( nEpochs, GO_EPOCHS );
		// The best round's model is kept: a round may be worse than an earlier one (single rounds swing), and the
		// state judged best is the one to move on with.
		final File best = new File( roundModelSnapshotFile().getParentFile(), "warmup_best_" + roundModelSnapshotFile().getName() );
		double bestScore = Double.NEGATIVE_INFINITY;
		int bestRound = -1, passed = 0;
		// Every blob at the cells' size per frame, not only the references taken from them: what a prediction may sit on.
		final java.util.Map< Integer, List< double[] > > blobsAt = new java.util.HashMap<>();
		for ( final Integer t : references.keySet() )
			blobsAt.put( t, org.mastodon.mamut.automahout.tool.VisualColdStart.blobs( firstSource( context ), t, r ) );
		// References the model keeps missing get a closer look (see askAboutDoubtfulReferences); how many rounds running
		// each has been missed, and which have been looked at.
		final java.util.Map< double[], Integer > missStreak = new java.util.IdentityHashMap<>();
		final java.util.Set< double[] > looked = java.util.Collections.newSetFromMap( new java.util.IdentityHashMap<>() );
		// Whether the VLM passed its check on a frame, asked once per frame per loop (see vlmTellsNucleiOn).
		final java.util.Map< Integer, Boolean > vlmTrusted = new java.util.HashMap<>();
		final java.util.Map< Integer, List< double[] > > refsNow = new java.util.LinkedHashMap<>();
		for ( final java.util.Map.Entry< Integer, List< double[] > > e : references.entrySet() )
			refsNow.put( e.getKey(), new ArrayList<>( e.getValue() ) );
		context.log( String.format( "  %s: proofreading until the model reproduces the reference cells on the whole frame, "
				+ "correcting after every round as a person does -- missed references annotated again (fn), false hits "
				+ "(far from every reference and every blob at the cells' size) marked background (fp). Go: all but %.0f%% "
				+ "found, false hits at most %.0f%% of them, at most %.1fx their count, size %.2f-%.1fx, %d rounds running; "
				+ "rounds of %d epochs, at most %d; without a Go the best round is kept.", label, 100 * GO_MISS_SHARE,
				100 * GO_FALSE_SHARE, GO_MAX_FLOOD, GO_SIZE_MIN, GO_SIZE_MAX, GO_CONSECUTIVE, epochs, GO_MAX_ROUNDS ) );
		for ( int round = checkFirst ? 0 : 1; round <= GO_MAX_ROUNDS; round++ )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				return false;
			// UNPREPARED-TRAINING-OK: the reference cells and the corrections made to them, not the model's own detections.
			if ( round > 0 && !elephant.trainDetectionModel( tps, tps[ tps.length / 2 ], epochs, nCrops, trainTimeoutMs ) )
			{
				context.log( "  " + label + ": training did not complete in round " + round + "; moving on without a Go." );
				break;
			}
			int found = 0, refs = 0, predicted = 0, falseHits = 0;
			double bound = 0;
			final List< Double > ratios = new ArrayList<>();
			final java.util.Map< Integer, List< double[] > > missedAt = new java.util.LinkedHashMap<>();
			final java.util.Map< Integer, List< double[] > > falseAt = new java.util.LinkedHashMap<>();
			final java.util.Map< Integer, List< double[] > > foundAt = new java.util.LinkedHashMap<>();
			for ( final java.util.Map.Entry< Integer, List< double[] > > e : refsNow.entrySet() )
			{
				final List< double[] > ss = e.getValue();
				// The whole frame, as the commit will predict it: a crop of the image's extent is capped at the patch size.
				final List< double[] > detections = detectionsOf( elephant.predictSpotsOnFrame( e.getKey() ),
						context.isVolumetric() );
				final org.mastodon.mamut.automahout.tool.FrameReview.Result review = org.mastodon.mamut.automahout.tool.FrameReview
						.review( detections, ss, blobsAt.getOrDefault( e.getKey(), java.util.Collections.emptyList() ), r );
				found += review.found;
				refs += review.references;
				predicted += review.predicted;
				falseHits += review.falseHits.size();
				bound += GO_MAX_FLOOD * Math.max( seenCount, ss.size() );
				if ( !Double.isNaN( review.sizeRatio ) )
					ratios.add( review.sizeRatio );
				final int cap = Math.max( GO_MARKS_PER_ROUND, ( int ) Math.ceil( GO_MARK_SHARE * ss.size() ) );
				missedAt.put( e.getKey(), review.missed.subList( 0, Math.min( review.missed.size(), cap ) ) );
				final java.util.Set< double[] > missedSet = java.util.Collections.newSetFromMap( new java.util.IdentityHashMap<>() );
				missedSet.addAll( review.missed );
				final List< double[] > hit = new ArrayList<>();
				for ( final double[] s : ss )
					if ( missedSet.contains( s ) )
						missStreak.merge( s, 1, Integer::sum );
					else
					{
						missStreak.remove( s );
						hit.add( s );
					}
				foundAt.put( e.getKey(), hit );
				falseAt.put( e.getKey(), review.falseHits.subList( 0, Math.min( review.falseHits.size(), cap ) ) );
			}
			java.util.Collections.sort( ratios );
			final double sizeRatio = ratios.isEmpty() ? Double.NaN : ratios.get( ratios.size() / 2 );
			// Only misses keep this round from passing: look closer at the references missed round after round.
			if ( round > 0 && !goDecision( found, refs, falseHits, predicted, bound, sizeRatio )
					&& goDecision( refs, refs, falseHits, predicted, bound, sizeRatio ) )
			{
				final int dropped = askAboutDoubtfulReferences( context, refsNow, foundAt, missedAt, missStreak, looked, vlmTrusted,
						r, label );
				if ( dropped > 0 )
				{
					found = 0;
					refs = 0;
					for ( final java.util.Map.Entry< Integer, List< double[] > > e : refsNow.entrySet() )
					{
						refs += e.getValue().size();
						found += foundAt.get( e.getKey() ).size();
					}
				}
			}
			final boolean pass = goDecision( found, refs, falseHits, predicted, bound, sizeRatio );
			if ( round == 0 && pass )
			{
				context.log( String.format( "  %s: the model already reproduces them before any training (%d of %d found, "
						+ "%d predicted, %d false hit(s), size %.2fx) -> kept as it is.", label, found, refs, predicted, falseHits,
						sizeRatio ) );
				return true;
			}
			passed = pass && round > 0 ? passed + 1 : 0;
			final boolean go = passed >= GO_CONSECUTIVE;
			final double score = found - falseHits - Math.max( 0, predicted - bound );
			if ( !go && score > bestScore && elephant.snapshotDetectionModel( best ) )
			{
				bestScore = score;
				bestRound = round;
			}
			int marked = 0, reannotated = 0;
			if ( !go && round < GO_MAX_ROUNDS )
			{
				for ( final java.util.Map.Entry< Integer, List< double[] > > e : missedAt.entrySet() )
					reannotated += markCorrection( context, e.getKey(), e.getValue(), r, false );
				for ( final java.util.Map.Entry< Integer, List< double[] > > e : falseAt.entrySet() )
				{
					// Background at most SEED_NEGATIVE_FRACTION of the frame's annotations, as the cold start seeds it
					// (the user: 20-30%); the probe's uncapped marks reached 35 fp for 4 nuclei.
					final int[] n = annotationCounts( context, e.getKey() );
					final int room = Math.max( 0, ( int ) Math.floor( SEED_NEGATIVE_FRACTION * n[ 0 ] / ( 1 - SEED_NEGATIVE_FRACTION ) ) - n[ 1 ] );
					final List< double[] > fs = e.getValue();
					marked += markCorrection( context, e.getKey(), fs.subList( 0, Math.min( fs.size(), room ) ), r, true );
				}
			}
			context.log( String.format( "  %s, round %d/%d: %d of %d references found, %d predicted on the whole frame "
					+ "(at most %.0f), %d false hit(s), size %.2fx -> %s", label, round, GO_MAX_ROUNDS, found, refs, predicted,
					bound, falseHits, sizeRatio, go ? "Go"
							: ( pass ? "passes (" + passed + " of " + GO_CONSECUTIVE + " rounds running)" : "train on" )
									+ ( reannotated > 0 ? ", " + reannotated + " missed reference(s) annotated again (fn)" : "" )
									+ ( marked > 0 ? ", " + marked + " false hit(s) marked background" : "" ) ) );
			if ( go )
				return true;
		}
		if ( bestRound >= 0 && elephant.restoreDetectionModel( best ) )
			context.log( "  " + label + ": no Go; moving on with the model of round " + bestRound
					+ ", the best of the rounds (most references found, false hits and flooding counted against it)." );
		else
			context.log( "  " + label + ": no Go; moving on with the model as it is." );
		return false;
	}

	/** A reference missed this many rounds running, while nothing else keeps the round from passing, is looked at. */
	static final int DOUBT_STREAK = 2;

	/** At most this many doubtful references are shown to the VLM per proofreading loop. */
	static final int DOUBT_ASKS = 5;

	/**
	 * Asks the VLM about each reference the model has missed {@link #DOUBT_STREAK} rounds running (each once, at most
	 * {@link #DOUBT_ASKS}), as a person looks closer at a spot the model will not learn before insisting on it: a
	 * reference the VLM says is not a nucleus is dropped, its annotation removed and the place marked background. On
	 * CE 01 t=0 one of 5 reference blobs was a fixed bright object 11-14 um from every nucleus (there again at t=30
	 * and t=60, not in the truth), and requiring it kept the review from Go for 20 rounds.
	 *
	 * @return how many references were dropped
	 */
	private static int askAboutDoubtfulReferences( final ToolContext context,
			final java.util.Map< Integer, List< double[] > > refs, final java.util.Map< Integer, List< double[] > > foundAt,
			final java.util.Map< Integer, List< double[] > > missedAt, final java.util.Map< double[], Integer > missStreak,
			final java.util.Set< double[] > looked, final java.util.Map< Integer, Boolean > vlmTrusted, final double r,
			final String label )
	{
		final VlmBackend backend = ( VlmBackend ) context.getSharedData( SHARED_VLM_BACKEND );
		final Object model = context.getSharedData( SHARED_VLM_MODEL );
		if ( backend == null || model == null )
			return 0;
		int dropped = 0;
		for ( final java.util.Map.Entry< Integer, List< double[] > > e : refs.entrySet() )
		{
			final int t = e.getKey();
			final List< double[] > found = foundAt.getOrDefault( t, java.util.Collections.emptyList() );
			Boolean trusted = vlmTrusted.get( t ); // asked once per frame and loop, only when something there is doubtful
			for ( final java.util.Iterator< double[] > it = e.getValue().iterator(); it.hasNext(); )
			{
				final double[] s = it.next();
				if ( missStreak.getOrDefault( s, 0 ) < DOUBT_STREAK || looked.contains( s ) || looked.size() >= DOUBT_ASKS )
					continue;
				looked.add( s );
				if ( trusted == null )
				{
					trusted = vlmTellsNucleiOn( context, t, found, r, backend, model.toString(), label );
					vlmTrusted.put( t, trusted );
				}
				if ( !trusted )
					continue;
				Boolean nucleus;
				String why = "";
				try
				{
					// Without a nucleus to compare with: shown one (CE 01 t=0), the VLM called a plain bright nucleus "dim,
					// poorly defined" next to it; asked about the spot alone it told the nucleus from the fixed bright object.
					nucleus = org.mastodon.mamut.automahout.tool.VisualColdStart.isNucleus( firstSource( context ), t, s, null, r,
							backend, model.toString() );
				}
				catch ( final java.io.IOException | RuntimeException x )
				{
					nucleus = null;
					why = " (" + x.getMessage() + ")";
				}
				context.log( String.format( "  %s: reference at (%.1f, %.1f, %.1f) t=%d missed %d rounds running; the VLM says %s%s.",
						label, s[ 0 ], s[ 1 ], s[ 2 ], t, missStreak.get( s ),
						nucleus == null ? "nothing usable -- kept" : nucleus ? "it is a nucleus -- kept" : "it is not a nucleus -- dropped from the references (not taught as background)",
						why ) );
				if ( Boolean.FALSE.equals( nucleus ) )
				{
					// Dropped, not marked background: the VLM's "no" is wrong often enough to be fatal as a label. On CE
					// 01 t=9 it passed its check on two known nuclei and then rejected two real nuclei (1.0 and 2.0 µm
					// from curated centres) in two runs running; marked fp, they taught the model those nuclei are
					// background. A dropped reference is only no longer required, and a detection on it is no false hit.
					it.remove();
					removeAnnotationsNear( context, t, s, 0.5 * r );
					final List< double[] > m = missedAt.get( t );
					if ( m != null )
						missedAt.put( t, without( m, s ) );
					dropped++;
				}
			}
		}
		return dropped;
	}

	/** Controls asked before the VLM's "not a nucleus" is believed on a frame. */
	static final int VLM_CONTROLS = 2;

	/**
	 * Whether the VLM's judgement can be trusted on frame {@code t}: it is asked about up to {@link #VLM_CONTROLS}
	 * references the model does find (reference and model agree), and must call every one a nucleus. A person checks
	 * their eye on spots they are sure of before rejecting a doubtful one. Measured on CE 01 (Claude Haiku 4.5, crops of
	 * 8 radii): at t=0 it called the 4 nuclei nuclei and the fixed bright object not; at t=60 and t=180 it called all 7
	 * nuclei asked about "not a nucleus" and the bright object at t=60 a nucleus.
	 */
	private static boolean vlmTellsNucleiOn( final ToolContext context, final int t, final List< double[] > found, final double r,
			final VlmBackend backend, final String model, final String label )
	{
		if ( found.isEmpty() )
		{
			context.log( "  " + label + ": nothing found at t=" + t + " to check the VLM's eye on -- its verdicts are not used." );
			return false;
		}
		int yes = 0, asked = 0;
		for ( int i = 0; i < Math.min( VLM_CONTROLS, found.size() ); i++ )
		{
			// Spread over the found references, not the first few next to each other.
			final double[] c = found.get( i * found.size() / Math.min( VLM_CONTROLS, found.size() ) );
			try
			{
				asked++;
				if ( Boolean.TRUE.equals( org.mastodon.mamut.automahout.tool.VisualColdStart.isNucleus( firstSource( context ), t,
						c, null, r, backend, model ) ) )
					yes++;
			}
			catch ( final java.io.IOException | RuntimeException x )
			{
				// counted as not confirmed
			}
		}
		final boolean ok = yes == asked;
		context.log( String.format( "  %s: the VLM calls %d of %d references the model finds at t=%d nuclei -> its verdicts %s.",
				label, yes, asked, t, ok ? "are used on this frame" : "are not used on this frame" ) );
		return ok;
	}

	/** {cells (tp, fn and untagged), background (fp)} annotated at {@code t}. */
	private static int[] annotationCounts( final ToolContext context, final int t )
	{
		final int[] n = new int[ 2 ];
		final Model model = context.getModel();
		context.getGraph().getLock().readLock().lock();
		try
		{
			for ( final Spot s : model.getSpatioTemporalIndex().getSpatialIndex( t ) )
				if ( DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, s ) ) )
					n[ 1 ]++;
				else
					n[ 0 ]++;
		}
		finally
		{
			context.getGraph().getLock().readLock().unlock();
		}
		return n;
	}

	private static List< double[] > without( final List< double[] > l, final double[] x )
	{
		final List< double[] > out = new ArrayList<>();
		for ( final double[] y : l )
			if ( y != x )
				out.add( y );
		return out;
	}

	/** Removes the annotations (any spot) within {@code d} of {@code p} at {@code t}. */
	private static void removeAnnotationsNear( final ToolContext context, final int t, final double[] p, final double d )
	{
		final ModelGraph graph = context.getGraph();
		final List< Spot > near = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : context.getModel().getSpatioTemporalIndex().getSpatialIndex( t ) )
				if ( Math.sqrt( Math.pow( s.getDoublePosition( 0 ) - p[ 0 ], 2 ) + Math.pow( s.getDoublePosition( 1 ) - p[ 1 ], 2 )
						+ Math.pow( s.getDoublePosition( 2 ) - p[ 2 ], 2 ) ) <= d )
				{
					final Spot ref = graph.vertexRef();
					ref.refTo( s );
					near.add( ref );
				}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		for ( final Spot s : near )
		{
			GraphEdits.removeSpot( context, graph, s, GraphEdits.Reason.TRAINING );
			graph.releaseRef( s );
		}
	}

	/** Frames of a window the review proofreads: first, middle and last (all of a shorter window). */
	static final int REVIEW_TIMEPOINTS = 3;

	/** {@link ToolContext#getSharedData} key, {@link Double}: the cell radius the last window's review read. */
	static final String SHARED_REVIEW_RADIUS = "adaptive_track.review_radius";

	/**
	 * Steps 5-7 of the human workflow for one window, before the window's own stages: read the cell size on a few of
	 * its frames by scale selection ({@link org.mastodon.mamut.automahout.tool.VisualColdStart#cellRadius}), re-centre
	 * ELEPHANT's radius range on it, take the blobs at that size as the reference cells, annotate them, and proofread
	 * the model against them ({@link #proofreadUntilReproduced}). The frames are then marked settled, so the window's
	 * self-training does not train over the state the review kept.
	 *
	 * <p>Fluorescence only: the blobs are bright on dark. Only on frames that hold no spots yet (a fresh window), since
	 * the annotations are removed again afterwards. Measured on CE 01: blobs at the scale-selected size were 94-100% of
	 * the cells with at most 3% on no cell, at every sampled frame from 4 to 348 cells.</p>
	 */
	private static void reviewWindow( final WindowRun w )
	{
		final ToolContext context = w.context;
		if ( countAllSpots( context, w.wStart, w.wEnd ) > 0 )
			return;
		if ( !reviewApplies( context, w.wStart ) )
		{
			context.log( "  Review: skipped -- the reference cells are blobs bright on dark, and this data does not read as "
					+ "fluorescence." );
			return;
		}
		final bdv.viewer.Source< ? > src = firstSource( context );
		if ( src == null )
			return;
		final int[] tps = pickSeedTimepoints( w.wStart, w.wEnd, Math.min( REVIEW_TIMEPOINTS, w.windowLen ) );
		// Around the last window's size when there is one (cells change gradually), else a wide ladder.
		final Object prev = context.getSharedData( SHARED_REVIEW_RADIUS );
		final double[] range = reviewRadiusRange( prev instanceof Number ? ( ( Number ) prev ).doubleValue() : Double.NaN );
		final double[] ladder = org.mastodon.mamut.automahout.tool.VisualColdStart.scaleLadder( range[ 0 ], range[ 1 ] );
		final List< Double > radii = new ArrayList<>();
		for ( final int t : tps )
		{
			final double[] est = org.mastodon.mamut.automahout.tool.VisualColdStart.cellRadius( src, t, ladder );
			if ( est[ 0 ] > 0 )
				radii.add( est[ 0 ] );
		}
		if ( radii.isEmpty() )
		{
			context.log( "  Review: no blobs at any scale on these frames -- skipped." );
			return;
		}
		java.util.Collections.sort( radii );
		final double r = radii.get( radii.size() / 2 );
		context.setSharedData( SHARED_REVIEW_RADIUS, r );
		context.setSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM, r );
		try
		{
			context.log( "  Review: cell size by scale selection on t=" + Arrays.toString( tps ) + " -> "
					+ org.mastodon.mamut.automahout.tool.ElephantDataSettings.apply(
							ElephantMainSettingsManager.getInstance().getSelectedStyle(), r ) + "." );
		}
		catch ( final RuntimeException e )
		{
			context.log( "  Review: could not set ELEPHANT's radius range (" + e.getMessage() + ")." );
		}
		final java.util.Map< Integer, List< double[] > > refs = new java.util.LinkedHashMap<>();
		int total = 0;
		for ( final int t : tps )
		{
			// Only blobs a person would annotate without hesitation; the faint rest still keep a detection on them from
			// being called false (blobsAt in the proofreading loop is every blob).
			final List< double[] > b = org.mastodon.mamut.automahout.tool.VisualColdStart.confidentBlobs( src, t, r );
			if ( b.isEmpty() )
				continue;
			refs.put( t, b );
			total += placeSeedSpots( context, b, t, r );
		}
		if ( refs.isEmpty() )
			return;
		final int[] refTps = refs.keySet().stream().mapToInt( Integer::intValue ).toArray();
		context.log( String.format( "  Review: %d reference cells (blobs at that size) on %d frame(s).", total, refTps.length ) );
		final boolean go = proofreadUntilReproduced( context, w.elephant, refTps, refs, r, 0, true, w.nEpochs, w.nCrops,
				w.trainTimeoutMs, "Review" );
		deleteAllSpotsAtTimepoints( context, refTps );
		context.setSharedData( SHARED_GO_TIMEPOINTS, w.allTps.clone() );
		context.log( "  Review: " + ( go ? "Go" : "no Go, best round kept" ) + " -- the window's own training will not "
				+ "train over this model." );
	}

	/**
	 * Whether {@link #reviewWindow} can review this data: its reference cells are blobs bright on dark, so fluorescence
	 * only (as the run's image profile reads it, measured at {@code t}).
	 */
	static boolean reviewApplies( final ToolContext context, final int t )
	{
		return ImageProfile.forRun( context, () -> AutoCorrectOrchestrator.sampleIntensities( context, t, ImageProfile.SAMPLES ) )
				.fluorescenceLike;
	}

	/** The first window's scale ladder, world units: from small nuclei to large ones. */
	static final double REVIEW_MIN_RADIUS = 1.0, REVIEW_MAX_RADIUS = 10.0;

	/**
	 * The radii (µm) a window's scale selection searches: half to twice the last window's radius, within
	 * [REVIEW_MIN_RADIUS, REVIEW_MAX_RADIUS]; the whole of that when there is no last window. Without the
	 * bounds a measurement on the bottom rung fed the next window's ladder, and on CE 01 the radius halved
	 * every window from t=190 (1.27, 0.64, 0.32, 0.16 µm) until the blobs were noise.
	 */
	static double[] reviewRadiusRange( final double previous )
	{
		if ( !( previous > 0 ) )
			return new double[] { REVIEW_MIN_RADIUS, REVIEW_MAX_RADIUS };
		final double lo = Math.min( REVIEW_MAX_RADIUS, Math.max( REVIEW_MIN_RADIUS, 0.5 * previous ) );
		final double hi = Math.max( lo, Math.min( REVIEW_MAX_RADIUS, 2 * previous ) );
		return new double[] { lo, hi };
	}

	/** {x, y, z, radius} of each predicted spot; empty when the prediction failed. */
	private static List< double[] > detectionsOf( final List< org.json.JSONObject > preds, final boolean volumetric )
	{
		final List< double[] > out = new ArrayList<>();
		if ( preds != null )
			for ( final org.json.JSONObject o : preds )
			{
				final org.json.JSONArray p = o.getJSONArray( "pos" );
				out.add( new double[] { p.getDouble( 0 ), p.getDouble( 1 ), p.getDouble( 2 ), radiusOf( o, volumetric ) } );
			}
		return out;
	}

	/**
	 * A predicted spot's radius from its JSON covariance (NaN without one): the sphere of its volume on a stack, the
	 * circle of its area on a single plane ({@link SpotShape#interiorRadius}). On a plane ELEPHANT gives every spot a
	 * z semi-axis of 1, which the volume counted: an 8.3 µm cell read as 4.1 µm, the review's size held at 0.55x of
	 * the reference cells on Fluo-N2DL-HeLa round after round, and its Go never came.
	 */
	static double radiusOf( final org.json.JSONObject o, final boolean volumetric )
	{
		final org.json.JSONArray c = o.optJSONArray( "covariance" );
		if ( c == null || c.length() != 9 )
			return Double.NaN;
		final double[][] m = new double[ 3 ][ 3 ];
		for ( int i = 0; i < 9; i++ )
			m[ i / 3 ][ i % 3 ] = c.getDouble( i );
		return SpotShape.interiorRadius( m, volumetric );
	}

	/**
	 * A correction at each of {@code positions}, made once, as a person makes it: background (fp) where a false hit is,
	 * unless that place is marked already; a missed cell (fn) by re-tagging the annotation already on it (its reference)
	 * as fn, or adding one when there is none. Re-annotating by adding a spot every round stacked duplicates -- the
	 * probe on CE 01 t=0 sent 33 fn for 4 nuclei -- which draw the same labels but inflate the annotation volume the
	 * background class weight is balanced on.
	 *
	 * @return how many corrections were made (marks added or annotations re-tagged)
	 */
	private static int markCorrection( final ToolContext context, final int timepoint, final List< double[] > positions,
			final double radius, final boolean background )
	{
		if ( positions.isEmpty() )
			return 0;
		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();
		final TagSet tagSet = DetectionTagLabels.ensure( model );
		final Tag tag = background ? DetectionTagLabels.fpTag( tagSet ) : DetectionTagLabels.fnTag( tagSet );
		final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();
		int placed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			final Spot ref = graph.vertexRef();
			try
			{
				for ( final double[] p : positions )
				{
					Spot onIt = null;
					for ( final Spot s : model.getSpatioTemporalIndex().getSpatialIndex( timepoint ) )
						if ( Math.sqrt( Math.pow( s.getDoublePosition( 0 ) - p[ 0 ], 2 ) + Math.pow( s.getDoublePosition( 1 ) - p[ 1 ], 2 )
								+ Math.pow( s.getDoublePosition( 2 ) - p[ 2 ], 2 ) ) <= 0.5 * radius )
						{
							onIt = s;
							break;
						}
					if ( onIt != null )
					{
						final String label = DetectionTagLabels.labelOf( model, onIt );
						if ( background || DetectionTagLabels.FN.equals( label ) )
							continue; // marked already: a false hit's place, or a missed cell already re-tagged
						vertexTags.set( onIt, tag );
						placed++;
					}
					else if ( GraphEdits.addSpot( context, graph, ref, timepoint, p, radius, GraphEdits.Reason.TRAINING ) != null )
					{
						vertexTags.set( ref, tag );
						placed++;
					}
				}
			}
			finally
			{
				graph.releaseRef( ref );
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		return placed;
	}

	/**
	 * Detects in the warm-up's tuning region on {@code tuningTps}, leaving the detections in the graph.
	 *
	 * @return {spots per timepoint in the region after elongated ones are removed, elongated removed}
	 */
	private static double[] detectInTuningRegion( final ToolContext context, final ViewerPanel viewer,
			final int[] tuningTps, final double[] cropBox )
	{
		for ( final int t : tuningTps )
		{
			if ( !setTimepoint( viewer, t, DEFAULT_LOAD_DELAY_MS ) )
				continue;
			final ElephantIntegration e = new ElephantIntegration( context );
			if ( e.init() )
				e.predictSpotsInRegionAndAdd( t, cropBox );
		}
		int elongated = 0;
		for ( final int t : tuningTps )
			elongated += filterElongatedSpots( context, t, DEFAULT_MAX_ASPECT_RATIO );
		return new double[] { computeQualityMetrics( context, tuningTps )[ 0 ], elongated };
	}

	/**
	 * Whether a model's detections before any training here say it is already trained: some, and no more than
	 * a frame may hold ({@code ceiling}, infinite when no cell count was stated). A model that knows nothing
	 * finds nothing or floods; when it floods past an unstated count, the per-window cold-start check still
	 * catches it.
	 */
	static boolean alreadyDetects( final double spotsPerTp, final double ceiling )
	{
		return spotsPerTp > 0 && spotsPerTp <= ceiling;
	}

	/**
	 * Bootstraps an untrained model from model-free intensity seeds (the automated stand-in for a
	 * human placing a few annotations by eye). Discards the garbage inference, seeds the brightest
	 * intensity maxima on a few timepoints, trains on them, and re-detects.
	 *
	 * @return {@code null} on success (window now holds re-detected spots, continue the pipeline);
	 *         otherwise a human-readable message explaining why autonomous bootstrap could not
	 *         proceed (manual annotation needed / training failed) — the caller should stop.
	 */
	private static String bootstrapColdStart(
			final ToolContext context,
			final ElephantIntegration elephant,
			final ViewerPanel viewer,
			final int wStart,
			final int wEnd,
			final int[] allTps,
			final int nSeedTimepoints,
			final int nSeedsPerTp,
			final double trackIntensityRatio,
			final double qualityThreshold,
			final double dedupFactor,
			final double minSize,
			final int nCrops,
			final int nEpochs,
			final long trainTimeoutMs,
			final double[] cropBox,
			final double coldStartCeiling )
	{
		// 1. Discard the untrained model's noise detections.
		deleteAllSpotsAtTimepoints( context, allTps );

		// 2. Place intensity seeds (tp) AND explicit background negatives (fp) on a few timepoints
		// spread across the window. Positive-only seeding teaches "bright thing = cell" with no
		// counter-example, which is exactly why bootstrapped detection kept flooding the whole frame
		// regardless of cold_start_spots_per_tp. Negatives are anchored on the SAME tp seeds (not
		// scattered frame-wide) so any later crop-restricted round still contains both — see
		// placeBackgroundNegatives's doc for the full reasoning.
		final int[] seedTps = pickSeedTimepoints( wStart, wEnd, nSeedTimepoints );
		int totalSeeds = 0;
		int totalNegatives = 0;
		for ( final int t : seedTps )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				return "Cold-start bootstrap cancelled.";
			if ( !setTimepoint( viewer, t, DEFAULT_LOAD_DELAY_MS ) )
				continue;
			// Measure the cells rather than take the brightest N voxels, where the image supports it.
			// The fixed count is the defect: on Fluo-N2DL-HeLa t=0 the truth holds 43 cells and this
			// seeded 20, so the model was shown half of them and nothing about the rest — and then
			// detected 171, which the explosion guard abandoned the window for. Measured against
			// silver truth the classical detector finds essentially all of them (F1 92.8%).
			final List< double[] > fromPretrained =
					org.mastodon.mamut.automahout.tool.remote.DetectorTrial.persistentSeeds( context, t, nSeedsPerTp );
			if ( fromPretrained != null )
				context.log( String.format( "    %d seed(s) at t=%d from the pretrained seed detector, not the image's "
						+ "maxima.", fromPretrained.size(), t ) );
			final List< double[] > measured = fromPretrained != null ? fromPretrained : classicalSeeds( viewer, t );
			final List< double[] > seeds;
			final double seedRadius;
			if ( measured.isEmpty() )
			{
				seeds = generateIntensitySeeds( viewer, t, nSeedsPerTp,
						2.0 * DEFAULT_SEED_RADIUS_WORLD, SEED_INTENSITY_PERCENTILE );
				seedRadius = DEFAULT_SEED_RADIUS_WORLD;
			}
			else
			{
				seeds = new ArrayList<>( measured.size() );
				final double[] radii = new double[ measured.size() ];
				for ( int i = 0; i < measured.size(); i++ )
				{
					final double[] d = measured.get( i );
					seeds.add( new double[] { d[ 0 ], d[ 1 ], d[ 2 ] } );
					radii[ i ] = d[ 3 ];
				}
				// The seeds' own measured size, not a constant: teaching a fixed radius is how the
				// annotation extent came to disagree with the cells in the first place.
				java.util.Arrays.sort( radii );
				seedRadius = radii[ radii.length / 2 ];
				if ( fromPretrained == null )
					context.log( String.format(
						"    Measured %d cell(s) in the image at t=%d (median radius %.1f µm) — seeding "
								+ "from those rather than the %d brightest points.",
						seeds.size(), t, seedRadius, nSeedsPerTp ) );
			}
			final int placed = placeSeedSpots( context, seeds, t, seedRadius );
			final int negativeCount = ( int ) Math.round(
					placed * SEED_NEGATIVE_FRACTION / ( 1.0 - SEED_NEGATIVE_FRACTION ) );
			final int negatives = placeBackgroundNegatives(
					context, t, seeds, negativeCount, 2.0 * seedRadius );
			context.log( String.format(
					"    Seeded %d %s (tp) + %d background negative(s) (fp) at t=%d.", placed,
					fromPretrained != null ? "pretrained detection(s)" : "intensity maxima", negatives, t ) );
			totalSeeds += placed;
			totalNegatives += negatives;
		}

		// 3. Human fallback: if seeding is not confident enough, let the user annotate.
		int[] trainTps = seedTps;
		if ( totalSeeds < MIN_SEEDS_FOR_AUTO_BOOTSTRAP )
		{
			deleteAllSpotsAtTimepoints( context, allTps );
			if ( !context.canWaitForAnnotations() )
			{
				return String.format(
						"COLD START — MANUAL ANNOTATION REQUIRED: the detection model is untrained and "
								+ "automatic intensity seeding found only %d confident cell(s) (need >=%d). "
								+ "Please add a few spot annotations on clear cells (e.g. at t=%d) in "
								+ "Mastodon, then re-run adaptive_track.",
						totalSeeds, MIN_SEEDS_FOR_AUTO_BOOTSTRAP, seedTps[ 0 ] );
			}
			// Interactive path: let the user place real annotations directly in BDV/TrackScheme —
			// used as-is as trusted training data below, instead of guessing coordinates from text.
			// Confirmation is explicit (Enter on the dialog confirms whatever is currently selected in
			// BDV/TrackScheme, see ManualAnnotationTracker) rather than an automatic timing heuristic.
			if ( !setTimepoint( viewer, seedTps[ 0 ], DEFAULT_LOAD_DELAY_MS ) )
				return "Cold-start bootstrap cancelled.";
			final int target = MIN_SEEDS_FOR_AUTO_BOOTSTRAP;
			final boolean proceeded;
			final int strays;
			try (final ManualAnnotationTracker tracker = new ManualAnnotationTracker( context, allTps, target ))
			{
				proceeded = context.waitForAnnotations( String.format(
						"COLD START — the detection model is untrained and automatic intensity seeding "
								+ "found only %d confident cell(s) (need >=%d).\n\n"
								+ "Please add spot annotations directly in BDV/TrackScheme on clear, "
								+ "well-isolated cell bodies across t=%d..%d (e.g. start at t=%d). After "
								+ "placing and resizing the ellipsoid to fit the cell, select it and press "
								+ "Enter to confirm it — this only progresses on your explicit confirmation. "
								+ "Tracking will resume automatically once %d are confirmed.",
						totalSeeds, target, wStart, wEnd, seedTps[ 0 ], target ),
						target, tracker, tracker::confirmSelected );
				if ( !proceeded )
					return "Cold-start bootstrap cancelled — manual annotation was not completed.";
				// What the user built during the wait is now the graph this step starts from: a step
				// boundary for GraphWatch, or every spot they added would read as changed under the run.
				GraphWatch.mark( context, "the manual annotation" );
				// Drop any straggler the user placed but never confirmed, so only deliberately confirmed
				// spots are used as trusted training data.
				strays = deleteUnconfirmedSpots( context, allTps, tracker.confirmedIds() );
			}
			if ( strays > 0 )
				context.log( String.format( "  Discarded %d unconfirmed annotation(s).", strays ) );
			totalSeeds = countAnnotationsAtTimepoints( context, allTps );
			trainTps = allTps;
			context.log( String.format( "  %d manual annotation(s) placed by the user — using them as "
					+ "trusted training data.", totalSeeds ) );
		}

		// 4. Train the model on the seeds (intensity-based, or the user's manual annotations).
		context.log( String.format(
				"  Bootstrapping model on %d seed/annotation spot(s)%s across %d timepoint(s)...",
				totalSeeds, totalNegatives > 0 ? " + " + totalNegatives + " background negative(s)" : "",
				trainTps.length ) );
		// UNPREPARED-TRAINING-OK: intensity seeds, or the user's manual annotations.
		final boolean trained = elephant.trainDetectionModel(
				trainTps, trainTps[ trainTps.length / 2 ], nEpochs, nCrops, trainTimeoutMs );
		if ( !trained )
			return "COLD START — bootstrap training did not complete (ELEPHANT server not idle "
					+ "within timeout, or aborted). Check the server and re-run adaptive_track.";

		// 5. Discard seeds, re-detect with the bootstrapped model — CROP-RESTRICTED (cropBox): this is
		// still the tuning phase, not the window's committed result (that happens once, after the
		// caller's crop-tuning loop concludes).
		deleteAllSpotsAtTimepoints( context, allTps );
		predictWindow( context, viewer, wStart, wEnd, trackIntensityRatio, qualityThreshold, minSize, cropBox,
				coldStartCeiling );
		if ( dedupFactor > 0.0 )
			deduplicateSpots( context, allTps, dedupFactor );
		context.log( "  Cold-start bootstrap complete — re-detected with the seeded model"
				+ ( cropBox != null ? " (crop-restricted)." : " (full frame)." ) );
		return null;
	}

	/** Picks up to {@code n} timepoints evenly spread across [wStart, wEnd]. */
	private static int[] pickSeedTimepoints( final int wStart, final int wEnd, final int n )
	{
		final int span = wEnd - wStart;
		final int count = Math.max( 1, Math.min( n, span + 1 ) );
		final int[] tps = new int[ count ];
		if ( count == 1 )
		{
			tps[ 0 ] = wStart + span / 2;
			return tps;
		}
		for ( int i = 0; i < count; i++ )
			tps[ i ] = wStart + ( int ) Math.round( ( double ) i * span / ( count - 1 ) );
		return tps;
	}

	/**
	 * Finds bright 3-D intensity maxima across the whole volume at {@code timepoint} to use as
	 * model-free seed annotations. Captures the image on the EDT, then scans off-EDT.
	 */
	/**
	 * Cells measured directly in the image at {@code timepoint}, or empty where that cannot be done.
	 *
	 * <p>Empty for a volume, and empty when the frame cannot be read — the caller then keeps the
	 * brightest-N seeding. See {@link FindMaximaTool#scanClassicalDetections} for why it is 2-D only.</p>
	 */
	/** {x, y, z} of each {x, y, z, radius} seed. */
	private static List< double[] > positionsOf( final List< double[] > seeds )
	{
		final List< double[] > out = new ArrayList<>( seeds.size() );
		for ( final double[] d : seeds )
			out.add( new double[] { d[ 0 ], d[ 1 ], d[ 2 ] } );
		return out;
	}

	/** The median radius of {x, y, z, radius} seeds. */
	/** The per-axis median of {@code {x, y, z}} positions. */
	private static double[] medianPosition( final List< double[] > positions )
	{
		final double[] out = new double[ 3 ];
		for ( int d = 0; d < 3; d++ )
		{
			final double[] v = new double[ positions.size() ];
			for ( int i = 0; i < v.length; i++ )
				v[ i ] = positions.get( i )[ d ];
			java.util.Arrays.sort( v );
			out[ d ] = v[ v.length / 2 ];
		}
		return out;
	}

	private static double medianRadius( final List< double[] > seeds )
	{
		final double[] r = new double[ seeds.size() ];
		for ( int i = 0; i < r.length; i++ )
			r[ i ] = seeds.get( i )[ 3 ];
		java.util.Arrays.sort( r );
		return r[ r.length / 2 ];
	}

	private static List< double[] > classicalSeeds( final ViewerPanel viewer, final int timepoint )
	{
		final FindMaximaTool.ScanState[] holder = new FindMaximaTool.ScanState[ 1 ];
		try
		{
			if ( SwingUtilities.isEventDispatchThread() )
				holder[ 0 ] = FindMaximaTool.captureViewerState( viewer, timepoint );
			else
				SwingUtilities.invokeAndWait(
						() -> holder[ 0 ] = FindMaximaTool.captureViewerState( viewer, timepoint ) );
		}
		catch ( final Exception e )
		{
			return java.util.Collections.emptyList();
		}
		if ( holder[ 0 ] == null || !holder[ 0 ].isValid() )
			return java.util.Collections.emptyList();
		return FindMaximaTool.scanClassicalDetections( holder[ 0 ], 0 );
	}

	private static List< double[] > generateIntensitySeeds(
			final ViewerPanel viewer,
			final int timepoint,
			final int nSeeds,
			final double worldNmsRadius,
			final double intensityPercentile )
	{
		final FindMaximaTool.ScanState[] holder = new FindMaximaTool.ScanState[ 1 ];
		try
		{
			if ( SwingUtilities.isEventDispatchThread() )
				holder[ 0 ] = FindMaximaTool.captureViewerState( viewer, timepoint );
			else
				SwingUtilities.invokeAndWait(
						() -> holder[ 0 ] = FindMaximaTool.captureViewerState( viewer, timepoint ) );
		}
		catch ( final Exception e )
		{
			return java.util.Collections.emptyList();
		}
		final FindMaximaTool.ScanState state = holder[ 0 ];
		if ( state == null || !state.isValid() )
			return java.util.Collections.emptyList();
		// Stride derived from the volume, not fixed — see BrightSpotAudit.strideFor. At the fixed
		// stride of 2 this walk was 53 million samples on a 680x678x920 image, and it runs to pick the
		// tuning region and again to seed a cold start.
		return FindMaximaTool.scanImageSpaceMaxima( state, nSeeds,
				org.mastodon.mamut.automahout.eval.BrightSpotAudit.strideFor( state.voxelCount(),
						SEED_SCAN_STRIDE, SEED_SCAN_SAMPLE_BUDGET ),
				worldNmsRadius, intensityPercentile );
	}

	/** Adds spherical seed spots at the given world positions and timepoint. Returns the count. */
	private static int placeSeedSpots( final ToolContext context, final List< double[] > positions,
			final int timepoint, final double radius )
	{
		if ( positions.isEmpty() )
			return 0;
		// The intensity-maxima scan (FindMaximaTool.scanImageSpaceMaxima) converts voxel -> world
		// coordinates via the BDV Source's per-mipmap-level transform, which can report a small nonzero
		// Z even for a genuinely 2D dataset (single Z-plane at full resolution) — e.g. a coarse-mipmap
		// pyramid half-voxel offset. Training on a seed at Z!=0 while ELEPHANT itself always predicts at
		// Z=0 for 2D data teaches the model a position that doesn't exist in the real detections it will
		// be compared against. Force Z to exactly 0 here, using the authoritative full-resolution
		// SpimData size (not the coarse-level scan) to decide 2D-ness.
		final boolean forceZZero = !isVolumetric( context );
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();
		// The tp/fp tag set must EXIST before the write lock: GraphEdits writes the tp tag under it,
		// and creating a tag set is a structure change that must not happen while a vertex write lock
		// is held. With no tag set, the write is a no-op and the listener's fn stands.
		DetectionTagLabels.ensure( model );

		final Spot ref = graph.vertexRef();
		int placed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			for ( final double[] pos : positions )
			{
				if ( forceZZero )
					pos[ 2 ] = 0.0;
				// The intensity-maxima scan has no notion of existing spots at all — a bright REAL cell
				// that is already tracked and protected is exactly the kind of thing it re-discovers as a
				// seed candidate, duplicating it. addSpot withdraws it rather than leaving the duplicate.
				if ( GraphEdits.addSpot( context, graph, ref, timepoint, pos, radius,
						GraphEdits.Reason.TRAINING_POSITIVE ) != null )
					placed++;
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
			graph.releaseRef( ref );
		}
		context.getModel().setUndoPoint();
		return placed;
	}

	/** Target fraction of fp among (tp+fp) cold-start seeds — see {@link #placeBackgroundNegatives}. */
	private static final double SEED_NEGATIVE_FRACTION = 0.25;

	/**
	 * Negatives are anchored between this multiple of the seed radius (never overlapping the anchor's
	 * own cell) and this-times-2 (stays in the same LOCAL neighbourhood a crop would capture) — see
	 * {@link #placeBackgroundNegatives}.
	 */
	private static final double SEED_NEGATIVE_MIN_RADIUS_FACTOR = 2.0;

	/**
	 * Places explicit background NEGATIVE ({@code fp}) examples ANCHORED NEAR each of
	 * {@code anchorPositions} (the tp seeds just placed) — not scattered uniformly across the whole
	 * frame — so that ANY subsequent crop drawn around the tp cluster (e.g. the crop-tuning warm-up's
	 * {@code cropBox}, or a manually-chosen ROI) still contains a realistic mix of tp AND fp. A negative
	 * placed uniformly at random anywhere in the image can easily land far from wherever a later
	 * crop-restricted round actually operates, producing a crop with ONLY tp — which teaches the model
	 * nothing about background within THAT specific region (the exact failure mode the user flagged:
	 * "a tp-only crop region can occur unless fp sits close to where tp clusters").
	 *
	 * <p>Each negative is placed at a random angle from a randomly-chosen anchor, at a distance in
	 * {@code [exclusionRadius, exclusionRadius * 2]} — far enough not to overlap that anchor's own cell,
	 * close enough to stay in the same local neighbourhood. Rejected (retried) if it lands within
	 * {@code exclusionRadius} of ANY existing spot (an anchor or an already-placed negative).</p>
	 *
	 * <p>Without this at all, intensity-maxima seeds ({@link #generateIntensitySeeds}/
	 * {@link #placeSeedSpots}) are ALL positive and, being untagged, default to {@code tp} when
	 * {@code buildSpotsJson} sends them to ELEPHANT — so the very first training round teaches the model
	 * "bright thing = cell" with ZERO negative signal about what background looks like. On a
	 * dense/noisy/confluent image this is exactly why detection floods the whole frame after even a
	 * single bootstrap round (density consistently converging to thousands/tp across independent
	 * bootstrap attempts, regardless of cold_start_spots_per_tp).</p>
	 *
	 * @return number of negative examples placed
	 */
	private static int placeBackgroundNegatives( final ToolContext context, final int timepoint,
			final List< double[] > anchorPositions, final int count, final double exclusionRadius )
	{
		if ( anchorPositions.isEmpty() || count <= 0 )
			return 0;
		final ModelGraph graph = context.getGraph();
		final List< double[] > existing = new ArrayList<>( anchorPositions );
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				final double[] pos = new double[ 3 ];
				spot.localize( pos );
				existing.add( pos );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		final Random rnd = new Random( 0 ); // deterministic — same window always samples the same background
		final Model model = context.getModel();
		final TagSet tagSet = DetectionTagLabels.ensure( model );
		final Tag fpTag = DetectionTagLabels.fpTag( tagSet );
		final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();
		final double maxRadius = exclusionRadius * SEED_NEGATIVE_MIN_RADIUS_FACTOR;

		int placed = 0;
		final int maxAttempts = Math.max( 50, count * 20 );
		graph.getLock().writeLock().lock();
		try
		{
			final Spot ref = graph.vertexRef();
			try
			{
				for ( int attempt = 0; placed < count && attempt < maxAttempts; attempt++ )
				{
					final double[] anchor = anchorPositions.get( rnd.nextInt( anchorPositions.size() ) );
					final double angle = rnd.nextDouble() * 2.0 * Math.PI;
					final double dist = exclusionRadius + rnd.nextDouble() * ( maxRadius - exclusionRadius );
					final double[] cand = {
							anchor[ 0 ] + dist * Math.cos( angle ),
							anchor[ 1 ] + dist * Math.sin( angle ),
							anchor[ 2 ]
					};
					boolean tooClose = false;
					for ( final double[] ex : existing )
					{
						final double dx = cand[ 0 ] - ex[ 0 ], dy = cand[ 1 ] - ex[ 1 ], dz = cand[ 2 ] - ex[ 2 ];
						if ( dx * dx + dy * dy + dz * dz < exclusionRadius * exclusionRadius )
						{
							tooClose = true;
							break;
						}
					}
					if ( tooClose )
						continue;
					// Same reason as synthesizeNearCellFpNegatives: a negative on a confirmed cell teaches
					// the model that a real cell is background.
					if ( GraphEdits.addSpot( context, graph, ref, timepoint, cand, DEFAULT_SEED_RADIUS_WORLD,
							GraphEdits.Reason.TRAINING ) == null )
						continue;
					vertexTags.set( ref, fpTag );
					placed++;
					existing.add( cand ); // so subsequent negatives don't cluster on top of this one
				}
			}
			finally
			{
				graph.releaseRef( ref );
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( placed > 0 )
			model.setUndoPoint();
		return placed;
	}

	/**
	 * Whether a window's continuity is defined: it needs a timepoint with a neighbour on both sides inside
	 * the window, so at least 3 timepoints. Every reader of a window's continuity checks this (ratchet:
	 * WindowContinuityTest).
	 */
	static boolean continuityDefined( final int windowLen )
	{
		return windowLen >= 3;
	}

	/**
	 * Measures track continuity in [wStart, wEnd] — a proxy for tracking correctness
	 * that, unlike {@link #computeLinkDensity}, is not satisfied by arbitrary single links.
	 *
	 * <p>Interior timepoints (wStart &lt; t &lt; wEnd) are the ones where a correctly tracked
	 * cell must have both a predecessor and a successor. A spot that appears or disappears
	 * mid-window (fragmentation) has only one side and is penalised. Divisions keep both an
	 * incoming and an outgoing edge, so they are NOT penalised.</p>
	 *
	 * @return {@code [0]} = fraction of interior spots with both an incoming and an outgoing
	 *         edge (0..1, higher is better); {@code [1]} = mean track length
	 *         (spots / number of track starts within the window, higher is better);
	 *         {@code [2]} = count of fully isolated spots (no incoming AND no outgoing edge) —
	 *         single detections belonging to no track, a symptom of detection/linking gaps.
	 */
	private static double[] computeContinuity( final ToolContext context, final int wStart, final int wEnd )
	{
		final ModelGraph graph = context.getGraph();
		int interiorTotal = 0, interiorContinuous = 0, windowSpots = 0, starts = 0, isolated = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				if ( t < wStart || t > wEnd )
					continue;
				windowSpots++;
				final boolean hasIn = spot.incomingEdges().size() > 0;
				final boolean hasOut = spot.outgoingEdges().size() > 0;
				if ( !hasIn )
					starts++;
				if ( !hasIn && !hasOut )
					isolated++;
				if ( t > wStart && t < wEnd )
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
		final double continuity = interiorTotal > 0
				? ( double ) interiorContinuous / interiorTotal : 0.0;
		final double meanTrackLen = starts > 0
				? ( double ) windowSpots / starts : windowSpots;
		return new double[] { continuity, meanTrackLen, isolated };
	}

	/**
	 * Fine-tunes the ELEPHANT detection model in local time windows, sweeping
	 * through the full time range. Each window runs iterative training so the
	 * model adapts to local imaging properties (intensity, cell size, density).
	 */
	private static String runWindowedTrain(
			final ToolContext context,
			final ElephantIntegration elephant,
			final ViewerPanel viewer,
			final int startTp,
			final int endTp,
			final int windowSize,
			final int nAnnotPerWindow,
			final int nIterations,
			final double minIntensityRatio,
			final double qualityThreshold,
			final double minSpotsPerTp )
	{
		final int totalWindows = ( int ) Math.ceil( ( double ) ( endTp - startTp + 1 ) / windowSize );
		context.log( String.format(
				"=== Windowed training: %d windows of size %d across t=%d..%d ===",
				totalWindows, windowSize, startTp, endTp ) );

		final List< String > windowReports = new ArrayList<>();
		int completedWindows = 0;

		for ( int wStart = startTp; wStart <= endTp; wStart += windowSize )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
			{
				return "Cancelled after " + completedWindows + "/" + totalWindows + " windows.\n"
						+ String.join( "\n", windowReports );
			}

			final int wEnd = Math.min( wStart + windowSize - 1, endTp );
			final int n = Math.max( 1, Math.min( nAnnotPerWindow, wEnd - wStart + 1 ) );
			context.log( String.format( "--- Window [t=%d..%d] (%d/%d) ---",
					wStart, wEnd, completedWindows + 1, totalWindows ) );

			final String result = runIterativeTrain( context, elephant, viewer,
					wStart, wEnd, DEFAULT_LOAD_DELAY_MS, minIntensityRatio, qualityThreshold,
					n, nIterations, minSpotsPerTp );
			completedWindows++;
			windowReports.add( String.format( "[t=%d..%d]: %s", wStart, wEnd, result ) );
		}

		return String.format( "Windowed training complete. %d/%d windows processed (window_size=%d).\n%s",
				completedWindows, totalWindows, windowSize, String.join( "\n", windowReports ) );
	}

	/**
	 * Iteratively fine-tunes the ELEPHANT detection model using high-confidence
	 * spots as sparse TP annotations. Each iteration:
	 * <ol>
	 *   <li>Clears any previous annotation spots at the representative timepoints.</li>
	 *   <li>Runs ELEPHANT prediction and removes dark false positives.</li>
	 *   <li>Checks quality; stops early if the average spot count per timepoint
	 *       meets {@code minSpotsPerTp}.</li>
	 *   <li>Sends spots as TP annotations and trains the model.</li>
	 * </ol>
	 * After the loop all annotation spots are removed from the graph.
	 */
	private static String runIterativeTrain(
			final ToolContext context,
			final ElephantIntegration elephant,
			final ViewerPanel viewer,
			final int startTp,
			final int endTp,
			final int loadDelayMs,
			final double minIntensityRatio,
			final double qualityThreshold,
			final int nAnnotationTp,
			final int nIterations,
			final double minSpotsPerTp )
	{
		final int n = Math.max( 1, Math.min( nAnnotationTp, endTp - startTp + 1 ) );
		final int[] annotationTimepoints = selectEvenlySpaced( startTp, endTp, n );

		context.log( String.format(
				"=== Iterative model training: %d annotation tp, up to %d iterations, "
						+ "quality thresholds: spots/tp>=%.0f, CV_temporal<%.1f, CV_size<%.1f ===",
				n, nIterations, minSpotsPerTp, CV_TEMPORAL_GOOD, CV_SIZE_GOOD ) );
		context.log( "  Annotation timepoints: " + java.util.Arrays.toString( annotationTimepoints ) );

		int lastSpotCount = 0;
		double prevAvgPerTp = -1;
		double prevCvSize = -1;

		for ( int iter = 1; iter <= nIterations; iter++ )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				return "Cancelled during iterative training.";

			context.log( String.format( "--- Iteration %d/%d ---", iter, nIterations ) );

			// Clear annotation spots from any previous iteration
			final int cleared = deleteAllSpotsAtTimepoints( context, annotationTimepoints );
			if ( cleared > 0 )
				context.log( "  Cleared " + cleared + " spots from previous iteration." );

			// Predict on each annotation timepoint and filter dark spots
			int detected = 0;
			int darkDeleted = 0;
			for ( final int t : annotationTimepoints )
			{
				if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
					return "Cancelled during detection.";
				context.log( "  Predicting t=" + t + "..." );
				if ( !setTimepoint( viewer, t, loadDelayMs ) )
					return "Interrupted while setting timepoint.";
				final ElephantIntegration e = new ElephantIntegration( context );
				if ( !e.init() ) continue;
				if ( e.predictSpots( t ) )
				{
					detected++;
					if ( minIntensityRatio > 0.0 || qualityThreshold > 0.0 )
						darkDeleted += AutoCorrectOrchestrator.filterWeakSpots(
								context, t, minIntensityRatio, qualityThreshold );
				}
			}

			// Evaluate all three quality metrics
			final double[] metrics = computeQualityMetrics( context, annotationTimepoints );
			final double avgPerTp = metrics[ 0 ];
			final double cvTemporal = metrics[ 1 ];
			final double cvSize = metrics[ 3 ];
			lastSpotCount = ( int ) Math.round( avgPerTp * n );
			context.log( String.format(
					"  → spots/tp=%.1f (CV_temporal=%.2f), size=%.1f (CV_size=%.2f), "
							+ "%d dark removed, %d/%d tp detected",
					avgPerTp, cvTemporal, metrics[ 2 ], cvSize, darkDeleted, detected, n ) );

			// Nothing to train on
			if ( avgPerTp == 0 )
			{
				context.log( "  No spots detected — cannot annotate. "
						+ "Check ELEPHANT server connection and model settings." );
				break;
			}

			// Stagnation: if metrics haven't changed, further training won't help
			if ( iter > 1
					&& Math.abs( avgPerTp - prevAvgPerTp ) < 0.5
					&& Math.abs( cvSize - prevCvSize ) < 0.01 )
			{
				context.log( "  Metrics unchanged from previous iteration — stopping early." );
				break;
			}
			prevAvgPerTp = avgPerTp;
			prevCvSize = cvSize;

			// All three criteria must be met to stop early
			final boolean countOk = avgPerTp >= minSpotsPerTp;
			final boolean temporalOk = cvTemporal < CV_TEMPORAL_GOOD;
			final boolean sizeOk = cvSize < CV_SIZE_GOOD;
			if ( countOk && temporalOk && sizeOk )
			{
				context.log( String.format(
						"  All quality thresholds met: spots/tp=%.1f, CV_temporal=%.2f, CV_size=%.2f.",
						avgPerTp, cvTemporal, cvSize ) );
				break;
			}
			if ( !countOk )
				context.log( String.format(
						"  Count insufficient (%.1f < %.0f/tp) — continue training.", avgPerTp, minSpotsPerTp ) );
			else if ( !temporalOk )
				context.log( String.format(
						"  CV_temporal=%.2f >= %.1f (inconsistent across timepoints) — continue training.",
						cvTemporal, CV_TEMPORAL_GOOD ) );
			else
				context.log( String.format(
						"  CV_size=%.2f >= %.1f (non-uniform ellipsoid sizes) — continue training.",
						cvSize, CV_SIZE_GOOD ) );

			// No more iterations after this
			if ( iter == nIterations )
				break;

			// Train — /seg/train writes the annotation labels itself, so no separate /seg/update call
			// is needed here.
			prepareAnnotationsForTraining( context, annotationTimepoints );
			context.log( "  Training model on " + lastSpotCount + " spots (may take several minutes)..." );
			if ( !elephant.trainDetectionModel(
					annotationTimepoints, annotationTimepoints[ annotationTimepoints.length / 2 ], 0, 0,
					DEFAULT_TRAIN_TIMEOUT_MS ) )
			{
				context.log( "  WARNING: Training failed or was aborted." );
				break;
			}
			context.log( "  Training complete — re-detecting with refined model..." );
		}

		// Clean up annotation spots before returning
		final int cleaned = deleteAllSpotsAtTimepoints( context, annotationTimepoints );
		if ( cleaned > 0 )
			context.log( "  Cleaned up " + cleaned + " annotation spots." );

		// Re-predict on annotation timepoints one final time to measure quality
		for ( final int t : annotationTimepoints )
		{
			if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() ) break;
			if ( !setTimepoint( viewer, t, loadDelayMs ) ) break;
			final ElephantIntegration e = new ElephantIntegration( context );
			if ( !e.init() ) continue;
			e.predictSpots( t );
			if ( minIntensityRatio > 0.0 || qualityThreshold > 0.0 )
				AutoCorrectOrchestrator.filterWeakSpots( context, t, minIntensityRatio, qualityThreshold );
		}
		final String qualityStats = computeSpotStatsString( context, annotationTimepoints );
		deleteAllSpotsAtTimepoints( context, annotationTimepoints );

		return String.format(
				"Iterative training complete [t=%d..%d]. Quality: %s. "
						+ "Good: CV_temporal<0.5, CV_size<0.4, spots/tp>=%.0f.",
				annotationTimepoints[ 0 ], annotationTimepoints[ n - 1 ],
				qualityStats, minSpotsPerTp );
	}

	// -----------------------------------------------------------------------
	// Shared utilities

	/** Returns the physical size of the first view setup as [maxX, maxY, maxZ] in world units. */
	private static double[] getPhysicalDimensions( final ToolContext context )
	{
		return ImageBounds.physicalExtent( context );
	}

	/**
	 * Whether the dataset has a real Z axis. Delegates to {@link ToolContext#isVolumetric()}, which is
	 * now the one place that answers this; it used to be a second copy of the same test documented as
	 * mirroring a third. Used to force seed Z coordinates to exactly 0 for a genuinely 2D dataset —
	 * see {@link #placeSeedSpots}.
	 */
	private static boolean isVolumetric( final ToolContext context )
	{
		return context.isVolumetric();
	}

	/**
	 * Computes the fraction of spots at the given timepoints that would be
	 * considered problematic: near the image border or with high aspect ratio.
	 * Does NOT modify the graph — purely for quality assessment.
	 *
	 * @return {@code double[2]}: [borderFraction, elongatedFraction]
	 */
	private static double[] computeProblematicFraction( final ToolContext context,
			final int[] timepoints, final double borderMargin, final double maxAspectRatio )
	{
		// Same extent, same in-plane rule as the filter this fraction decides whether to run. This
		// number is reported to the agent, which is instructed to raise border_margin when it is high:
		// counting the one-voxel z face made it 100% on any 2D dataset for any margin at all.
		final double[] extent = borderMargin > 0.0 ? ImageBounds.physicalExtent( context ) : null;

		final Set< Integer > tpSet = new HashSet<>();
		for ( final int t : timepoints ) tpSet.add( t );
		final ModelGraph graph = context.getGraph();
		final double[] pos = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		// Same in-plane rule as the filter this fraction decides whether to run — see SpotShape.
		final boolean volumetric = isVolumetric( context );

		int total = 0, borderCount = 0, elongatedCount = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( !tpSet.contains( spot.getTimepoint() ) ) continue;
				total++;

				if ( borderMargin > 0.0 )
				{
					spot.localize( pos );
					if ( ImageBounds.reachesBorder( extent, pos, borderMargin, volumetric ) )
						borderCount++;
				}

				if ( maxAspectRatio > 0.0 )
				{
					spot.getCovariance( cov );
					if ( SpotShape.isElongated( cov, maxAspectRatio, volumetric ) )
						elongatedCount++;
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( total == 0 ) return new double[] { 0.0, 0.0 };
		return new double[] { ( double ) borderCount / total, ( double ) elongatedCount / total };
	}

	/**
	 * Fraction of spots at {@code timepoints} whose local contrast magnitude reads as background noise
	 * (see {@link #estimateContrastNoiseFloor}) — i.e. the detector placed a "cell" on flat background
	 * with no real peak/trough there. A real detection always stands out locally from its surroundings
	 * in SOME direction, so a high fraction here is an unambiguous "this is not real cells" signal,
	 * independent of how plausible the spot count/size/shape statistics look — catching failures the
	 * count-based {@link #isColdStartRegime} ceiling and the border/aspect-ratio checks in
	 * {@link #computeProblematicFraction} can both miss (e.g. a REGULAR GRID of empty positions, which
	 * reads as a normal-looking spot count/shape but sits on nothing). Contrast (not absolute intensity)
	 * so this works regardless of modality/noise floor.
	 */
	private static double computeZeroIntensityFraction( final ToolContext context, final int[] timepoints )
	{
		final Set< Integer > tpSet = new HashSet<>();
		for ( final int t : timepoints ) tpSet.add( t );
		final ModelGraph graph = context.getGraph();
		final List< Integer > tps = new ArrayList<>();
		final List< double[] > positions = new ArrayList<>();
		final List< Double > radii = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				if ( !tpSet.contains( t ) )
					continue;
				final double[] pos = new double[ 3 ];
				spot.localize( pos );
				tps.add( t );
				positions.add( pos );
				radii.add( Math.sqrt( spot.getBoundingSphereRadiusSquared() ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( positions.isEmpty() )
			return 0.0;
		final Map< Integer, AutoCorrectOrchestrator.BackgroundStats > statsByTp = new HashMap<>();
		int zero = 0;
		for ( int i = 0; i < positions.size(); i++ )
		{
			final int t = tps.get( i );
			final AutoCorrectOrchestrator.BackgroundStats stats = statsByTp.computeIfAbsent( t,
					tp -> AutoCorrectOrchestrator.estimateBackgroundStats( context, tp ) );
			final double[] pos = positions.get( i );
			final double contrast = Math.abs( AutoCorrectOrchestrator.sampleContrast( context, t, pos, radii.get( i ) ) );
			final double intensity = AutoCorrectOrchestrator.sampleIntensity( context, t, pos );
			if ( stats.isDefinitelyBackground( contrast, intensity ) )
				zero++;
		}
		return ( double ) zero / positions.size();
	}

	/**
	 * Deletes every spot at {@code timepoint} that reads as background on BOTH local contrast AND
	 * absolute intensity (see {@link AutoCorrectOrchestrator.BackgroundStats#isDefinitelyBackground}) —
	 * i.e. it sits on flat background, not a dim-but-real cell. Local contrast ALONE over-rejects in a
	 * densely packed/confluent culture, where a real cell touching other real cells has near-zero
	 * contrast against its immediate shell simply because the shell is ALSO real tissue, not because the
	 * cell is background (observed: ~200 raw detections/tp collapsing to ~60-90 final, most of the loss
	 * from this check alone, on a dense 3D culture where cells visibly touch) — the absolute-intensity
	 * check rescues a spot that is clearly brighter than the background baseline even when it doesn't
	 * locally "stand out" from equally-bright neighbours.
	 *
	 * <p>Applied UNCONDITIONALLY in {@link #predictWindow}, regardless of ELEPHANT's own reported
	 * quality/confidence for that spot: an undertrained model can report high confidence for a spurious
	 * detection that isn't on any real signal at all, so this model-independent check still runs
	 * alongside the model's own quality score (which {@link AutoCorrectOrchestrator#filterWeakSpots}
	 * otherwise prioritises over intensity).</p>
	 *
	 * <p>Gated by {@code expectedCeiling} (the user-calibrated {@code coldStartCeiling} — see
	 * {@link #SHARED_COLD_START_CEILING_OVERRIDE}, 0 meaning "no known ceiling, always apply"): when the
	 * RAW count already sitting at {@code timepoint} is at or below that ceiling, this check is skipped
	 * entirely for the timepoint. The whole point of the statistical background test is to catch a flood
	 * of spurious detections when we have no other signal — once the user has told us the plausible count
	 * and we are already within it, further contrast/intensity second-guessing only trades real (often
	 * dim, densely-packed) cells for a marginal cleanup we didn't need. Observed: with the user's own
	 * expected-count signal in effect (ceiling correctly applied elsewhere), this check was STILL the
	 * dominant cause of a ~200-raw/tp to ~60-96-final/tp collapse, well under the user-specified plausible
	 * density — i.e. it kept "cleaning up" a count that was never actually flooded.</p>
	 *
	 * @return number of spots deleted
	 */
	private static int deleteFlatContrastSpots( final ToolContext context, final int timepoint,
			final double expectedCeiling )
	{
		return deleteFlatContrastSpots( context, timepoint, expectedCeiling, null, null );
	}

	/**
	 * As {@link #deleteFlatContrastSpots(ToolContext, int, double)}, but measuring the SIZE and the
	 * ROUNDNESS of what it flags, what it keeps, and what the user has confirmed, into {@code shape}
	 * ({@code [sumSize, sumAspect, n]} per group, may be null).
	 *
	 * <p>The user's description of this dataset is that debris is small and misshapen while cells are
	 * large and round, which is a discriminator the pipeline barely uses: {@code max_aspect_ratio}
	 * defaults to 6.0, and on a whole ten-timepoint window it removed one spot. Size on its own has
	 * already been measured and does not separate the groups — flagged detections run about 0.77x the
	 * kept ones, which is where daughter cells sit. Roundness is the other half of that description and
	 * has never been looked at.</p>
	 *
	 * <p>The confirmed group is the reference the other two are read against, and it also tests whether
	 * the question is answerable at all from these ellipsoids. ELEPHANT fits them to its OWN predicted
	 * blob, and it was trained on spherical annotations, so it may well emit near-spheres whatever the
	 * image holds — in which case all three groups come back with the same roundness and the covariance
	 * is reporting the model's prior rather than anything about debris.</p>
	 */
	static int deleteFlatContrastSpots( final ToolContext context, final int timepoint,
			final double expectedCeiling, final double[] shape, final int[] withheldOut )
	{
		int withheldFromTraining = 0;
		// Switched off for a diagnostic run (see CleanupToggles): the point of that run is to see what
		// this pass would have removed, so it must not remove it anywhere — including the density probe,
		// which calls this to estimate full-frame density from a small patch.
		if ( !CleanupToggles.flatContrastFilterEnabled( context ) )
			return 0;
		final ModelGraph graph = context.getGraph();
		// Nothing here to judge, so do not pay for a background estimate. That estimate is the most
		// expensive thing these passes do — hundreds of random image accesses — and the density probe
		// calls this on timepoints it has just emptied.
		if ( ExpectedCellCount.countCellsAt( context, timepoint ) == 0 )
			return 0;
		final AutoCorrectOrchestrator.BackgroundStats stats =
				AutoCorrectOrchestrator.estimateBackgroundStats( context, timepoint );
		final List< Spot > flagged = new ArrayList<>();
		final List< Double > flaggedContrast = new ArrayList<>();
		// Positions only, for the VLM audit below — Spot refs cannot outlive the read lock.
		final List< double[] > auditFlagged = new ArrayList<>();
		final List< double[] > auditKept = new ArrayList<>();
		final int[] groundTruth = groundTruthTally( context );
		int totalAtTp = 0;
		// Under the lock only what the graph says; the image is read after it is released. Under it,
		// two image reads per spot held every edit in the viewer waiting for the whole frame.
		final List< Spot > refs = new ArrayList<>();
		final List< double[] > rows = new ArrayList<>(); // x, y, z, r, protected, cov 9
		graph.getLock().readLock().lock();
		try
		{
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				totalAtTp++;
				final double[] row = new double[ 14 ];
				spot.localize( row );
				row[ 3 ] = Math.sqrt( spot.getBoundingSphereRadiusSquared() );
				row[ 4 ] = ProtectedSpots.isProtected( context, context.getModel(), spot ) ? 1 : 0;
				spot.getCovariance( cov );
				for ( int a = 0; a < 3; a++ )
					for ( int b = 0; b < 3; b++ )
						row[ 5 + 3 * a + b ] = cov[ a ][ b ];
				rows.add( row );
				refs.add( row[ 4 ] > 0 ? null : graph.vertexRef().refTo( spot ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		final double[][] cov = new double[ 3 ][ 3 ];
		for ( int k = 0; k < rows.size(); k++ )
		{
			final double[] row = rows.get( k );
			final double[] pos = { row[ 0 ], row[ 1 ], row[ 2 ] };
			final double r = row[ 3 ];
			final boolean isProtected = row[ 4 ] > 0;
			final double contrast =
					Math.abs( AutoCorrectOrchestrator.sampleContrast( context, timepoint, pos, r ) );
			final double intensity = AutoCorrectOrchestrator.sampleIntensity( context, timepoint, pos );
			final boolean background = stats.isDefinitelyBackground( contrast, intensity );
			if ( shape != null )
			{
				for ( int a = 0; a < 3; a++ )
					for ( int b = 0; b < 3; b++ )
						cov[ a ][ b ] = row[ 5 + 3 * a + b ];
				final int g = isProtected ? 2 : background ? 0 : 1;
				shape[ 4 * g ] += SpotShape.size( cov );
				shape[ 4 * g + 1 ] += SpotShape.aspectRatio( cov, isVolumetric( context ) );
				shape[ 4 * g + 2 ] += intensity;
				shape[ 4 * g + 3 ]++;
			}
			if ( isProtected )
			{
				// A spot the USER confirmed is a cell. It is never deleted, but running the test on
				// it anyway measures the test against ground truth: every protected spot this calls
				// background is a known false positive of the test itself. No model's opinion is
				// involved, which is what makes it worth more than the VLM audit beside it.
				groundTruth[ 1 ]++;
				if ( background )
					groundTruth[ 0 ]++;
				continue;
			}
			( background ? auditFlagged : auditKept ).add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ], r } );
			if ( background )
			{
				// Handed over to flagged, which releases it; withholdFromTraining and the removal leave
				// alone a spot changed in the viewer since (GraphWatch).
				flagged.add( refs.get( k ) );
				refs.set( k, null );
				flaggedContrast.add( contrast );
			}
		}
		for ( final Spot ref : refs )
			if ( ref != null )
				graph.releaseRef( ref );
		try
		{
			// Gated on the CEILING (2x the stated count), not on the floor, and deliberately so — do not
			// "fix" this to match the floor the deletion below respects. The stated count is the user's
			// approximate expectation, not a measurement: a dataset described as ~250 cells/frame genuinely
			// runs to ~350 at times, and within that tolerance a statistical background test second-guessing
			// the detector costs real cells (especially dim ones touching neighbours) for a cleanup nobody
			// asked for. The floor below says how far this pass may ever cut; this says when it is worth
			// running at all. Tightening it to the floor was proposed after duplicate resolution moved ahead
			// of this check and dropped counts under the ceiling — the user's answer was that the slack is
			// the point.
			// Before the gate, so a tuning round — where the gate skips every timepoint and the model is
			// trained on whatever this check WOULD have removed — is audited too.
			final Object auditsLeft = context.getSharedData( SHARED_FLAT_CONTRAST_AUDIT_PENDING );
			// Spread across the window instead of taking consecutive timepoints, so the samples span the
			// whole range rather than three views of nearly the same frame.
			if ( CleanupToggles.flatContrastAuditEnabled( context ) && auditsLeft instanceof Number
					&& ( ( Number ) auditsLeft ).intValue() > 0
					&& timepoint % FLAT_CONTRAST_AUDIT_TIMEPOINT_STRIDE == 0 )
			{
				final long auditStart = System.nanoTime();
				final String audit = AutoCorrectOrchestrator.auditBackgroundVerdictViaVlm( context,
						( VlmBackend ) context.getSharedData( SHARED_VLM_BACKEND ),
						( String ) context.getSharedData( SHARED_VLM_MODEL ),
						timepoint, auditFlagged, auditKept, auditTally( context ) );
				PhaseTimings.record( context, "flat-contrast VLM audit", auditStart );
				// The budget is spent on the ATTEMPT, not on the answer. Decrementing only on success meant
				// an audit that could not produce one — an unparseable reply, a backend that rejects the
				// batch — was retried at every eligible timepoint for the whole run, paying a full VLM round
				// trip each time and logging nothing at all to show for it.
				context.setSharedData( SHARED_FLAT_CONTRAST_AUDIT_PENDING,
						( ( Number ) auditsLeft ).intValue() - 1 );
				context.log( audit != null ? audit
						: "  Flat-contrast audit at t=" + timepoint + ": the VLM returned no usable verdict "
								+ "(unreadable crops or an unparseable reply) — not retrying it this window." );
			}
			if ( expectedCeiling > 0.0 && totalAtTp <= expectedCeiling )
			{
				// Nothing here has been vetted, so this count must not become the NEXT timepoint's floor —
				// a raw count used as a floor sits above the raw counts around it and switches the cleanup
				// off downstream. See ExpectedCellCount.SHARED_UNVETTED_TIMEPOINTS.
				ExpectedCellCount.noteUnvetted( context, timepoint );
				return 0;
			}
			ExpectedCellCount.noteVetted( context, timepoint );
			if ( flagged.isEmpty() )
				return 0;

			// Never take a timepoint below the cell count the USER stated. This is a statistical test against
			// a background estimate built from at most a few dozen samples, and in a dense frame those samples
			// are drawn from whatever space is left between DETECTED spots — undetected real cells included —
			// which pulls the background baseline up and starts classifying real cells as background. Observed:
			// a commit of 5007 detections lost 3792 of them (76%) and finished at 194 spots/tp on a dataset
			// the user had told the pipeline holds ~250 cells/frame. Past the stated count the test is no
			// longer removing false positives, so stop there and drop only the flattest candidates.
			final int floor = ExpectedCellCount.floorAt( context, timepoint );
			final int deletable = floor > 0 ? Math.max( 0, totalAtTp - floor ) : flagged.size();
			final List< Spot > toDelete;
			if ( deletable < flagged.size() )
			{
				// This timepoint's count was decided by the floor, not by the measurement, so it must not
				// feed the growth estimate the floor itself is derived from.
				ExpectedCellCount.noteFloorBound( context, timepoint );
				final Integer[] order = new Integer[ flagged.size() ];
				for ( int i = 0; i < order.length; i++ )
					order[ i ] = i;
				// Flattest first: of the spots the test flagged, these are the ones it is most confident about.
				java.util.Arrays.sort( order, ( a, b ) -> Double.compare( flaggedContrast.get( a ),
						flaggedContrast.get( b ) ) );
				toDelete = new ArrayList<>( deletable );
				for ( int i = 0; i < deletable; i++ )
					toDelete.add( flagged.get( order[ i ] ) );
				context.log( String.format(
						"    t=%d: flat-contrast check flagged %d of %d spot(s) (%.0f%%) but deleting only %d — "
								+ "removing them all would leave %d, below the %d cell(s)/frame your stated "
								+ "estimate still allows. The "
								+ "background estimate is over-flagging here; the rest are kept.",
						timepoint, flagged.size(), totalAtTp, 100.0 * flagged.size() / totalAtTp, deletable,
						totalAtTp - flagged.size(), floor ) );
			}
			else
			{
				toDelete = flagged;
			}
			// Everything this check flagged but did not delete is withheld from the next training round.
			// The floor governs how many spots may be REMOVED from a timepoint; it says nothing about what
			// may be taught, and teaching the model a detection our own background test calls background is
			// how the annotation set reached twice the stated cell count. Withholding asserts neither cell
			// nor background — see DetectionTagLabels.UNLABELED.
			withheldFromTraining += withholdFromTraining( context, flagged, toDelete );
			if ( withheldOut != null )
				withheldOut[ 0 ] += withheldFromTraining;
			if ( toDelete.isEmpty() )
				return 0;
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot spot : toDelete )
					GraphEdits.removeSpot( context, graph, spot, GraphEdits.Reason.PROBABLY_NOT_A_CELL );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			context.getModel().setUndoPoint();
			return toDelete.size();
		}
		finally
		{
			for ( final Spot f : flagged )
				graph.releaseRef( f );
		}
	}


	/**
	 * Removes duplicate spots at the given timepoints using Non-Maximum Suppression.
	 * Two spots are considered duplicates when their centre-to-centre distance is less
	 * than {@code overlapFactor * (r_i + r_j)}, where {@code r = sqrt(trace(cov)/3)}.
	 * The smaller spot (by covariance trace) is removed from each overlapping pair.
	 *
	 * @return total number of spots removed across all timepoints
	 */
	private static int deduplicateSpots( final ToolContext context, final int[] timepoints,
			final double overlapFactor )
	{
		int total = 0;
		for ( final int t : timepoints )
			total += deduplicateSpotsAtTimepoint( context, t, overlapFactor );
		return total;
	}

	private static int deduplicateSpotsAtTimepoint( final ToolContext context, final int timepoint,
			final double overlapFactor )
	{
		// NMS ends at the same mergeIntoCoveringEllipsoid as the other three passes, so it answers to the
		// same switch — see CleanupToggles.duplicateResolutionEnabled. It was not gated, which is how a
		// run with duplicate resolution off still reported "NMS: removed N duplicate spots".
		if ( !CleanupToggles.duplicateResolutionEnabled( context ) )
			return 0;
		final ModelGraph graph = context.getGraph();

		// Collect spots at this timepoint under read lock
		final List< Spot > refs = new ArrayList<>();
		final List< double[] > positions = new ArrayList<>();
		final List< Double > radii = new ArrayList<>();
		final double[][] cov = new double[ 3 ][ 3 ];

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint ) continue;
				// fp-tagged spots are detection-training NEGATIVES, not detections. Merging one into a
				// covering ellipsoid would turn a deliberate negative into a positive at a position the
				// pipeline decided is background.
				if ( "fp".equals( org.mastodon.mamut.automahout.elephant.DetectionTagLabels.labelOf(
						context.getModel(), spot ) ) )
					continue;
				// Protected (human-confirmed) spots never participate in NMS — neither as a removal
				// candidate nor as a comparison partner (a fresh detection duplicating one is already
				// rejected before it ever reaches the graph, see ElephantIntegration.addSpotFromJson).
				if ( ProtectedSpots.isProtected( context, context.getModel(), spot ) )
					continue;
				final Spot ref = graph.vertexRef();
				ref.refTo( spot );
				refs.add( ref );
				final double[] pos = new double[ 3 ];
				spot.localize( pos );
				positions.add( pos );
				spot.getCovariance( cov );
				// Approximate bounding-sphere radius: sqrt(trace / 3)
				radii.add( Math.sqrt( ( cov[ 0 ][ 0 ] + cov[ 1 ][ 1 ] + cov[ 2 ][ 2 ] ) / 3.0 ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		final int n = refs.size();
		// Core intensity per spot, for the valley test below (0 if unavailable).
		final double[] coreI = new double[ n ];
		for ( int i = 0; i < n; i++ )
			coreI[ i ] = AutoCorrectOrchestrator.sampleIntensity( context, timepoint, positions.get( i ) );

		// Group the overlapping spots transitively: three detections tiling one cell are one cell, not
		// two independent pairs. Same union-find as AutoCorrectOrchestrator.mergeDuplicateSpots, and
		// literally the same code, so the two cannot drift apart again.
		final int[] parent = new int[ n ];
		for ( int i = 0; i < n; i++ )
			parent[ i ] = i;
		for ( int i = 0; i < n; i++ )
		{
			final double[] pi = positions.get( i );
			final double ri = radii.get( i );
			for ( int j = i + 1; j < n; j++ )
			{
				final double[] pj = positions.get( j );
				final double dx = pi[ 0 ] - pj[ 0 ], dy = pi[ 1 ] - pj[ 1 ], dz = pi[ 2 ] - pj[ 2 ];
				final double dist = Math.sqrt( dx * dx + dy * dy + dz * dz );
				final double rj = radii.get( j );
				// (a) Classic NMS: near-coincident centres are duplicates.
				// (b) Over-segmentation guard: two spots that visibly OVERLAP (dist < ri+rj) are
				//     fragments of ONE cell only if the image stays bright BETWEEN them — a single
				//     continuous blob. If the midpoint is a dark valley they are two distinct touching
				//     cells and are kept. This merges the multi-spot splits on one large cell without
				//     fusing neighbouring cells separated by a gap.
				boolean merge = dist < overlapFactor * ( ri + rj );
				// Same rule, and now the same CODE, as AutoCorrectOrchestrator's over-segmentation guard: the
				// two used to agree only by a comment saying they did, and both carried the same two defects —
				// a reach of ri+rj (two spheres merely touching, i.e. two adjacent cells) and a valley read
				// from the single midpoint voxel. Fixing one and leaving the other would have restored exactly
				// the pairing this pass exists to avoid, one pass later.
				if ( !merge && AutoCorrectOrchestrator.overlapsAsFragments( dist * dist, ri, rj,
					AutoCorrectOrchestrator.duplicateOverlapFactor( context ) ) )
				{
					final double[] pjArr = { pj[ 0 ], pj[ 1 ], pj[ 2 ] };
					merge = AutoCorrectOrchestrator.isSameBlob( context, timepoint, pi, pjArr,
						coreI[ i ], coreI[ j ], dist / Math.max( 1e-9, Math.max( ri, rj ) ) );
				}
				if ( merge )
					AutoCorrectOrchestrator.ufUnion( parent, i, j );
			}
		}

		final Map< Integer, Set< Integer > > byRoot = new HashMap<>();
		for ( int i = 0; i < n; i++ )
			byRoot.computeIfAbsent( AutoCorrectOrchestrator.ufFind( parent, i ), k -> new HashSet<>() )
				.add( refs.get( i ).getInternalPoolIndex() );

		// Each group becomes ONE spot covering it. This pass used to delete the smaller of every
		// overlapping pair, which is wrong in the same way everywhere it was done: the two detections
		// TILE the cell, so the survivor sits off the cell's centre and is smaller than the cell, and
		// that is what the next refinement round then teaches the model a cell looks like.
		int removed = 0;
		final Set< Integer > created = new HashSet<>();
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Set< Integer > group : byRoot.values() )
			{
				if ( group.size() < 2 )
					continue;
				removed += AutoCorrectOrchestrator.mergeIntoCoveringEllipsoid( context, graph, timepoint,
					group, created );
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( removed > 0 )
			context.getModel().setUndoPoint();

		for ( final Spot ref : refs )
			graph.releaseRef( ref );

		return removed;
	}

	/**
	 * Computes quality metrics for spots in the graph at the given timepoints.
	 *
	 * @return {@code double[4]}: [meanCount, cvTemporal, meanSize, cvSize]
	 *         where CV_temporal is the coefficient of variation of per-timepoint spot counts
	 *         and CV_size is the coefficient of variation of spot sizes (sqrt of covariance trace).
	 */
	private static double[] computeQualityMetrics( final ToolContext context, final int[] timepoints )
	{
		final ModelGraph graph = context.getGraph();
		final int n = timepoints.length;
		final int[] countPerTp = new int[ n ];
		final List< Double > sizes = new ArrayList<>();
		final double[][] cov = new double[ 3 ][ 3 ];

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				for ( int i = 0; i < n; i++ )
				{
					if ( t == timepoints[ i ] )
					{
						countPerTp[ i ]++;
						spot.getCovariance( cov );
						sizes.add( Math.sqrt( cov[ 0 ][ 0 ] + cov[ 1 ][ 1 ] + cov[ 2 ][ 2 ] ) );
						break;
					}
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		double sumCount = 0;
		for ( final int c : countPerTp ) sumCount += c;
		final double meanCount = sumCount / n;
		double varCount = 0;
		for ( final int c : countPerTp ) varCount += ( c - meanCount ) * ( c - meanCount );
		final double cvCount = meanCount > 0 ? Math.sqrt( varCount / n ) / meanCount : 1.0;

		double sumSize = 0;
		for ( final double s : sizes ) sumSize += s;
		final double meanSize = sizes.isEmpty() ? 0 : sumSize / sizes.size();
		double varSize = 0;
		for ( final double s : sizes ) varSize += ( s - meanSize ) * ( s - meanSize );
		final double cvSize = meanSize > 0 && !sizes.isEmpty()
				? Math.sqrt( varSize / sizes.size() ) / meanSize : 1.0;

		return new double[] { meanCount, cvCount, meanSize, cvSize };
	}

	private static String computeSpotStatsString( final ToolContext context, final int[] timepoints )
	{
		final double[] m = computeQualityMetrics( context, timepoints );
		return String.format( "spots/tp=%.1f (CV_temporal=%.2f), size=%.1f (CV_size=%.2f)",
				m[ 0 ], m[ 1 ], m[ 2 ], m[ 3 ] );
	}

	/** Selects {@code n} evenly-spaced indices in [startTp, endTp]. */
	private static int[] selectEvenlySpaced( final int startTp, final int endTp, final int n )
	{
		final int[] result = new int[ n ];
		for ( int i = 0; i < n; i++ )
			result[ i ] = startTp + Math.round( ( float ) i * ( endTp - startTp ) / Math.max( 1, n - 1 ) );
		return result;
	}

	/**
	 * Cells at any of {@code timepoints} -- spots that are not {@code fp} training negatives.
	 *
	 * <p>See {@link ExpectedCellCount#countCellsAt}. Every caller of the old spot count that feeds a
	 * guard, a ceiling, a budget or a growth comparison is asking about POPULATION, and a label on a
	 * place that is not a cell is not population.</p>
	 */
	private static int countCellsAtTimepoints( final ToolContext context, final int[] timepoints )
	{
		return countAtTimepoints( context, timepoints, true );
	}

	/**
	 * Every annotation at any of {@code timepoints}, negatives included.
	 *
	 * <p>The other half of the same rename. Training reads {@code tp} AND {@code fp}: an {@code fp} is
	 * a label, not a missing cell, and the five callers here -- the annotation count that gates
	 * training, the GT count, the manual-annotation count and the rollback report -- are all counting
	 * labels. Counting cells there would skip training on a frame annotated entirely with negatives,
	 * which is a legitimate thing to want to train on.</p>
	 */
	private static int countAnnotationsAtTimepoints( final ToolContext context, final int[] timepoints )
	{
		return countAtTimepoints( context, timepoints, false );
	}

	private static int countAtTimepoints( final ToolContext context, final int[] timepoints,
			final boolean cellsOnly )
	{
		final ModelGraph graph = context.getGraph();
		final java.util.function.Predicate< Spot > isFp =
				DetectionTagLabels.fpTest( context.getModel() );
		int count = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( cellsOnly && isFp.test( spot ) )
					continue;
				for ( final int t : timepoints )
					if ( spot.getTimepoint() == t ) { count++; break; }
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return count;
	}

	/**
	 * Tracks which spots the user has explicitly confirmed as manual annotations during
	 * {@link ToolContext#waitForAnnotations}. Confirmation is deliberate (the dialog's Enter keystroke
	 * calls {@link #confirmSelected()}, which reads whatever is currently selected in the Mastodon UI) —
	 * NOT automatic — so a user unfamiliar with the flow is never carried forward by a timing heuristic
	 * they didn't realize they had triggered. {@link #confirmedIds()} lets the caller discard any spot
	 * the user placed but never confirmed before using the rest as trusted training data.
	 */
	static final class ManualAnnotationTracker implements IntSupplier, AutoCloseable
	{
		private final ToolContext context;

		private final int[] timepoints;

		private final int targetCount;

		/**
		 * Pool indices, kept honest by {@link #forgetDeleted}: the user edits throughout the wait, and a
		 * confirmed spot they delete would otherwise still count toward the target and hand its
		 * confirmation to whatever spot next takes its slot -- a stray kept as trusted training data.
		 */
		private final Set< Integer > confirmed = java.util.Collections.synchronizedSet( new HashSet<>() );

		private final org.mastodon.graph.GraphListener< Spot, org.mastodon.mamut.model.Link > forgetDeleted =
				new org.mastodon.graph.GraphListener< Spot, org.mastodon.mamut.model.Link >()
				{
					@Override
					public void vertexRemoved( final Spot v )
					{
						confirmed.remove( v.getInternalPoolIndex() );
					}

					@Override
					public void graphRebuilt()
					{
						confirmed.clear();
					}

					@Override
					public void vertexAdded( final Spot v )
					{}

					@Override
					public void edgeAdded( final org.mastodon.mamut.model.Link e )
					{}

					@Override
					public void edgeRemoved( final org.mastodon.mamut.model.Link e )
					{}
				};

		ManualAnnotationTracker( final ToolContext context, final int[] timepoints, final int targetCount )
		{
			this.context = context;
			this.timepoints = timepoints;
			this.targetCount = targetCount;
			context.getGraph().addGraphListener( forgetDeleted );
		}

		@Override
		public void close()
		{
			context.getGraph().removeGraphListener( forgetDeleted );
		}

		@Override
		public int getAsInt()
		{
			return confirmed.size();
		}

		/**
		 * Confirms whatever spot(s) are currently selected in the Mastodon UI (BDV/TrackScheme) that
		 * fall within the target timepoints. Safe to call repeatedly (already-confirmed spots are
		 * skipped) and safe to call from the EDT.
		 */
		void confirmSelected()
		{
			final ModelGraph graph = context.getGraph();
			graph.getLock().readLock().lock();
			try
			{
				for ( final Spot spot : context.getProjectModel().getSelectionModel().getSelectedVertices() )
				{
					boolean inRange = false;
					for ( final int t : timepoints )
						if ( spot.getTimepoint() == t ) { inRange = true; break; }
					if ( !inRange )
						continue;
					confirm( spot );
				}
			}
			finally
			{
				graph.getLock().readLock().unlock();
			}
		}

		/** Counts {@code spot} as confirmed, once. Caller holds a lock. */
		void confirm( final Spot spot )
		{
			if ( confirmed.add( spot.getInternalPoolIndex() ) )
				context.log( String.format( "    Confirmed annotation at t=%d (%d/%d).",
						spot.getTimepoint(), confirmed.size(), targetCount ) );
		}

		/** Pool indices of spots the user has explicitly confirmed. */
		Set< Integer > confirmedIds()
		{
			return confirmed;
		}
	}

	/**
	 * Trusted-annotation record layout in the snapshot maps: {@code {x, y, z, cov0..cov8}} — position
	 * plus the row-major 3×3 covariance (ellipsoid shape). Covariance is preserved so that GT used as
	 * ELEPHANT training labels keeps each cell's shape; training on isotropic spheres would teach the
	 * model to output uniform spheres.
	 */
	/**
	 * {@code {x, y, z, cov0..8, fpFlag}} — position, row-major 3x3 covariance, and 1.0 when the record
	 * is an explicit background NEGATIVE rather than a cell.
	 *
	 * <p>The last slot is why this grew from 12: a GT store used to describe only where cells are, so
	 * everything it restored was trained as a positive and the user's own {@code fp} marks were dropped
	 * on the way in. Records built anywhere else leave it 0, which reads as {@code tp}.</p>
	 */
	private static final int REC_LEN = 13;

	/** Builds a {@code {x,y,z,cov0..8}} record for an isotropic spot of the given radius (fallback). */
	private static double[] isotropicRecord( final double x, final double y, final double z, final double r )
	{
		final double[] rec = new double[ REC_LEN ];
		rec[ 0 ] = x;
		rec[ 1 ] = y;
		rec[ 2 ] = z;
		final double rr = r * r;
		rec[ 3 ] = rr; // c00
		rec[ 7 ] = rr; // c11
		rec[ 11 ] = rr; // c22
		return rec;
	}

	/**
	 * Loads trusted GT annotations (position + 3×3 covariance, grouped by timepoint, restricted to
	 * {@code [startTp, endTp]}) from a persistent GT store JSON written by the "Learn Lineage
	 * Constraints" actions. Returns an empty map if {@code path} is blank or unreadable — the caller then
	 * falls back to a live graph snapshot. Using the store (not the graph) keeps GT correct across
	 * re-runs, which would otherwise snapshot the previous run's detections as "GT".
	 */
	/** Loads the active detection-acceptance profile, or {@code null} if none/unreadable. */
	private static org.mastodon.mamut.automahout.eval.DetectionAcceptanceProfile loadAcceptanceProfile(
			final String path )
	{
		if ( path == null || path.trim().isEmpty() )
			return null;
		try
		{
			return org.mastodon.mamut.automahout.eval.DetectionAcceptanceProfile.load( path.trim() );
		}
		catch ( final Exception e )
		{
			return null;
		}
	}

	/**
	 * Per-dataset path for the auto-learned acceptance profile — deliberately NOT the fixed global path
	 * {@code corrSettings.getAcceptanceProfilePath()} used by the manual "Filter by Example" UI action,
	 * which is shared across every dataset ever opened and can silently poison an unrelated dataset (see
	 * the acceptance-profile-cross-dataset-contamination writeup). Keying by dataset name mirrors how
	 * ELEPHANT itself already scopes its own model/log files.
	 *
	 * <p>Public so a "start completely fresh" action (see {@code AutonomousTrackingAction}) can delete
	 * this dataset's stale profile before a re-run, instead of leaving it to silently carry over
	 * whatever it last learned from a corrupted prior run.</p>
	 */
	public static String autoAcceptanceProfilePath( final String datasetName )
	{
		return new java.io.File( System.getProperty( "user.home" ),
				".automahout-acceptance-" + safeProfileName( datasetName ) + ".json" ).getAbsolutePath();
	}

	static String safeProfileName( final String datasetName )
	{
		return ( datasetName == null || datasetName.trim().isEmpty() ? "default" : datasetName )
				.replaceAll( "[^a-zA-Z0-9_-]", "_" );
	}

	/**
	 * Deletes every auto-learned acceptance profile for {@code datasetName}, whichever sampling level it
	 * was learned on — the profile file is scoped to the level because it stores contrast values, so
	 * "start fresh" has to sweep all of them rather than the one this run happens to want.
	 *
	 * @return how many files were deleted
	 */
	public static int deleteAcceptanceProfiles( final String datasetName )
	{
		final String prefix = ".automahout-acceptance-" + safeProfileName( datasetName );
		final java.io.File[] found = new java.io.File( System.getProperty( "user.home" ) )
				.listFiles( ( dir, name ) -> name.startsWith( prefix ) && name.endsWith( ".json" ) );
		if ( found == null )
			return 0;
		int deleted = 0;
		for ( final java.io.File f : found )
			if ( f.delete() )
				deleted++;
		return deleted;
	}

	/**
	 * Says once per run how many spots the user has confirmed, and which guards are therefore inert.
	 *
	 * <p>Several of the strongest guards here are anchored on those spots and nothing else: the debris
	 * filter's thresholds are percentiles of them, and the cap on how many detections a window may teach
	 * ranks candidates by how close they are to their mean. On a dataset with none, every one of those
	 * quietly does nothing — which is the right behaviour, since without a reference "how cell-like is
	 * this" is only the detector's own opinion and that opinion is the loop they exist to break. But a
	 * guard that is silently absent reads exactly like a guard that ran and found nothing, and that is
	 * how a fresh dataset ends up looking inexplicably worse than the one before it.</p>
	 */
	private static void logConfirmedSpotDependentGuards( final ToolContext context )
	{
		final int confirmed = countProtectedSpots( context );
		if ( confirmed >= MIN_CONFIRMED_SPOTS_FOR_DEBRIS_FLOOR )
		{
			context.log( String.format( "  %d confirmed spot(s) available as a reference — the debris "
					+ "filter and the training cap are calibrated against them.", confirmed ) );
			return;
		}
		context.log( String.format(
				"  Only %d confirmed spot(s) in this project (need %d). OFF for this run: the debris "
						+ "filter (its size/evenness/brightness thresholds are percentiles of your "
						+ "confirmed cells). The cap on how many detections each window may teach still "
						+ "applies if you stated a cell count — it then keeps the ones standing furthest "
						+ "above the background instead of the ones closest to your confirmed cells. Tag "
						+ "some cells Status > Completed and run Plugins > Automahout > Advanced > Build GT "
						+ "Annotation Store to turn the rest on.",
				confirmed, MIN_CONFIRMED_SPOTS_FOR_DEBRIS_FLOOR ) );
	}

	/** How many spots carry the configured protection tag. */
	private static int countProtectedSpots( final ToolContext context )
	{
		final ModelGraph graph = context.getGraph();
		int n = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
				if ( ProtectedSpots.isProtected( context, context.getModel(), s ) )
					n++;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return n;
	}

	/**
	 * Whether the active GT annotation store was built from the dataset now open.
	 *
	 * <p>The settings these come from live in one preferences node per USER, not per project, so
	 * opening a different dataset inherits the previous one's GT path — and a path alone carries no
	 * evidence about which movie it describes. Observed: a run on a new dataset loaded the previous
	 * dataset's confirmed spots and used them as training labels. The store now records its dataset and
	 * this refuses anything else.</p>
	 *
	 * <p>A store with no dataset recorded predates that and cannot be checked, so it is refused too.
	 * Rebuilding is one menu action, and training a detector on another movie's cells is not a failure
	 * that announces itself — this one only surfaced because the user recognised the count.</p>
	 */
	private static boolean gtStoreMatchesDataset( final ToolContext context,
			final AutomahoutSettings corrSettings, final String currentDataset )
	{
		final String path = corrSettings.getGtAnnotationsPath();
		if ( path == null || path.trim().isEmpty() )
			return false;
		final String storeDataset = corrSettings.getGtAnnotationsDataset();
		if ( storeDataset != null && storeDataset.equals( currentDataset ) )
			return true;
		context.log( String.format(
				"  IGNORING the active GT annotation store: it was built from %s, but this project is "
						+ "'%s'. Training on another dataset's annotations would teach this detector "
						+ "another movie's cells. Rebuild it here with Plugins > Automahout > Advanced > Build GT "
						+ "Annotation Store from Confirmed Spots..., or clear the path in the settings. "
						+ "Proceeding without GT for this run.",
				storeDataset == null || storeDataset.trim().isEmpty()
						? "a dataset it did not record (a store written before stores were scoped)"
						: "'" + storeDataset + "'",
				currentDataset ) );
		return false;
	}

	/**
	 * What the GT store holds regardless of the range being tracked: {@code [minTp, maxTp, count]}, or
	 * {@code null} when there is no readable store or it is empty. Exists so a run whose range simply
	 * misses the annotations can say so — {@link #loadGtAnnotations} filters to the range and returns
	 * an empty map either way, which is what made "no store" and "no store COVERAGE" indistinguishable.
	 */
	private static int[] gtStoreExtent( final String path )
	{
		if ( path == null || path.trim().isEmpty() )
			return null;
		try
		{
			final LineageGraphData gt = LineageGraphData.load( path.trim() );
			if ( gt.nodes == null || gt.nodes.isEmpty() )
				return null;
			int min = Integer.MAX_VALUE;
			int max = Integer.MIN_VALUE;
			for ( final LineageGraphData.Node n : gt.nodes )
			{
				min = Math.min( min, n.t );
				max = Math.max( max, n.t );
			}
			return new int[] { min, max, gt.nodes.size() };
		}
		catch ( final Exception e )
		{
			return null; // unreadable store — indistinguishable from none, which is what the caller says
		}
	}

	private static Map< Integer, List< double[] > > loadGtAnnotations( final String path,
			final int startTp, final int endTp )
	{
		final Map< Integer, List< double[] > > map = new HashMap<>();
		if ( path == null || path.trim().isEmpty() )
			return map;
		try
		{
			final LineageGraphData gt = LineageGraphData.load( path.trim() );
			for ( final LineageGraphData.Node n : gt.nodes )
			{
				if ( n.t < startTp || n.t > endTp )
					continue;
				final double[] rec;
				if ( n.covariance != null && n.covariance.length == 9 )
				{
					rec = new double[ REC_LEN ];
					rec[ 0 ] = n.x;
					rec[ 1 ] = n.y;
					rec[ 2 ] = n.z;
					System.arraycopy( n.covariance, 0, rec, 3, 9 );
				}
				else
				{
					rec = isotropicRecord( n.x, n.y, n.z, n.radius );
				}
				if ( DetectionTagLabels.FP.equals( n.label ) )
					rec[ REC_LEN - 1 ] = 1.0;
				map.computeIfAbsent( n.t, k -> new ArrayList<>() ).add( rec );
			}
		}
		catch ( final Exception e )
		{
			// Missing/corrupt store → empty map; caller falls back to a graph snapshot.
		}
		return map;
	}

	/**
	 * Snapshots every spot in {@code [startTp, endTp]} as position + 3×3 covariance, grouped by timepoint.
	 * Used to capture trusted pre-existing annotations (e.g. sparse GT) before the adaptive loop clears the
	 * graph, so they can later be restored (with their true ellipsoid shape) and used as training labels.
	 */
	private static Map< Integer, List< double[] > > snapshotSpots( final ToolContext context,
			final int startTp, final int endTp )
	{
		final Map< Integer, List< double[] > > snap = new HashMap<>();
		final ModelGraph graph = context.getGraph();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < startTp || t > endTp )
					continue;
				s.localize( pos );
				s.getCovariance( cov );
				final double[] rec = new double[ REC_LEN ];
				rec[ 0 ] = pos[ 0 ];
				rec[ 1 ] = pos[ 1 ];
				rec[ 2 ] = pos[ 2 ];
				for ( int rr = 0; rr < 3; rr++ )
					for ( int cc = 0; cc < 3; cc++ )
						rec[ 3 + rr * 3 + cc ] = cov[ rr ][ cc ];
				snap.computeIfAbsent( t, k -> new ArrayList<>() ).add( rec );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return snap;
	}

	/**
	 * Returns {@code [minTp, maxTp]}: the dataset's {@code [0, numTimepoints-1]} range narrowed by the range stated
	 * in the run's set-up ({@link RunRange}). The autonomous-tracking LLM agent picks its own start/end per turn, so
	 * this is enforced here as a hard bound rather than left to the agent to request correctly.
	 */
	private static int[] runTimepointBounds( final ToolContext context, final int numTimepoints )
	{
		final int[] bounds = RunRange.bounds( context, numTimepoints );
		if ( RunRange.restricts( bounds, numTimepoints ) )
			context.log( String.format(
					"  The run's range restricts processing to t=%d..%d (dataset is t=0..%d).",
					bounds[ 0 ], bounds[ 1 ], numTimepoints - 1 ) );
		return bounds;
	}

	/**
	 * Highest timepoint in {@code [0, endTp]} that already has at least one spot, or {@code -1} if none.
	 * Used to RESUME autonomous tracking from where a previous run stopped rather than redoing the whole
	 * movie: the next window starts at {@code result + 1}.
	 */
	private static int maxTimepointWithSpots( final ToolContext context, final int endTp )
	{
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();
		int maxTp = -1;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				// A spot the USER confirmed is an annotation, not a tracking result, so a timepoint
				// holding only those has not been processed. Counting them made a run skip exactly the
				// frames the annotations are on: 88 confirmed spots at t=0..1 were read as "t=0..1 already
				// have spots" and the run resumed at t=2, leaving the only frames with ground truth
				// untouched — after the user had explicitly asked to start fresh.
				if ( ProtectedSpots.isProtected( context, model, s ) )
					continue;
				if ( t <= endTp && t > maxTp )
					maxTp = t;
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return maxTp;
	}

	/** Total number of spots in {@code [startTp, endTp]} (cheap count, no record allocation). */
	private static int countAllSpots( final ToolContext context, final int startTp, final int endTp )
	{
		final ModelGraph graph = context.getGraph();
		int n = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t >= startTp && t <= endTp )
					n++;
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return n;
	}

	/**
	 * Snapshots ONLY the spots that lie on an unbranched track spanning at least {@code minTrackLen}
	 * timepoints, with their full covariance ({@link #REC_LEN} layout). "Unbranched" is evaluated by
	 * union-find over <em>clean</em> links — an edge {@code a→b} where {@code a} has exactly one
	 * outgoing edge and {@code b} exactly one incoming edge (no division/merge at either end). Each
	 * connected component of clean links is one non-branching track segment; a segment whose timepoint
	 * span reaches {@code minTrackLen} is trusted, and all its spots are returned. Branch junctions and
	 * short/isolated spots are excluded, which is exactly the topology signal that a long, unbranched
	 * track is a real cell whereas branchy or short tracks are likely broken/force-connected errors.
	 */
	private static Map< Integer, List< double[] > > snapshotReliableSpots( final ToolContext context,
			final int startTp, final int endTp, final int minTrackLen )
	{
		final Map< Integer, List< double[] > > snap = new HashMap<>();
		final ModelGraph graph = context.getGraph();
		graph.getLock().readLock().lock();
		try
		{
			// 1. Union spots joined by a clean (non-branching) link.
			final Map< Integer, Integer > parent = new HashMap<>();
			final Spot tRef = graph.vertexRef();
			try
			{
				for ( final Spot s : graph.vertices() )
				{
					if ( s.outgoingEdges().size() != 1 )
						continue; // branching (or leaf) at the source end -> not a clean link
					final Link e = s.outgoingEdges().iterator().next();
					e.getTarget( tRef );
					if ( tRef.incomingEdges().size() != 1 )
						continue; // merge at the target end -> not a clean link
					union( parent, s.getInternalPoolIndex(), tRef.getInternalPoolIndex() );
				}
			}
			finally
			{
				graph.releaseRef( tRef );
			}

			// 2. Per component (union-find root), track the min/max timepoint of its members.
			final Map< Integer, int[] > spanByRoot = new HashMap<>();
			for ( final Spot s : graph.vertices() )
			{
				final int root = find( parent, s.getInternalPoolIndex() );
				final int t = s.getTimepoint();
				final int[] mm = spanByRoot.get( root );
				if ( mm == null )
					spanByRoot.put( root, new int[] { t, t } );
				else
				{
					if ( t < mm[ 0 ] ) mm[ 0 ] = t;
					if ( t > mm[ 1 ] ) mm[ 1 ] = t;
				}
			}

			// 3. Emit spots belonging to a reliable component (span >= minTrackLen) within the range.
			final double[] pos = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < startTp || t > endTp )
					continue;
				final int[] mm = spanByRoot.get( find( parent, s.getInternalPoolIndex() ) );
				if ( mm == null || ( mm[ 1 ] - mm[ 0 ] + 1 ) < minTrackLen )
					continue;
				// Protected spots already persist and train automatically (never deleted, see
				// deleteAllSpotsAtTimepoints) — capturing them here too would let the GT branch's
				// restoreSpots create a duplicate on top of the still-alive original.
				if ( ProtectedSpots.isProtected( context, context.getModel(), s ) )
					continue;
				s.localize( pos );
				s.getCovariance( cov );
				final double[] rec = new double[ REC_LEN ];
				rec[ 0 ] = pos[ 0 ];
				rec[ 1 ] = pos[ 1 ];
				rec[ 2 ] = pos[ 2 ];
				for ( int rr = 0; rr < 3; rr++ )
					for ( int cc = 0; cc < 3; cc++ )
						rec[ 3 + rr * 3 + cc ] = cov[ rr ][ cc ];
				snap.computeIfAbsent( t, k -> new ArrayList<>() ).add( rec );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return snap;
	}

	/** Union-find find with path compression; a key absent from {@code parent} is its own root. */
	private static int find( final Map< Integer, Integer > parent, final int x )
	{
		int root = x;
		Integer p;
		while ( ( p = parent.get( root ) ) != null && p != root )
			root = p;
		int cur = x;
		while ( ( p = parent.get( cur ) ) != null && p != cur )
		{
			parent.put( cur, root );
			cur = p;
		}
		return root;
	}

	/** Union-find union; both keys are materialised as their own roots first if absent. */
	private static void union( final Map< Integer, Integer > parent, final int a, final int b )
	{
		parent.putIfAbsent( a, a );
		parent.putIfAbsent( b, b );
		final int ra = find( parent, a );
		final int rb = find( parent, b );
		if ( ra != rb )
			parent.put( ra, rb );
	}

	/** Number of snapshot spots whose timepoint falls in {@code [wStart, wEnd]}. */
	private static int countSnapshotInWindow( final Map< Integer, List< double[] > > snap,
			final int wStart, final int wEnd )
	{
		int n = 0;
		for ( int t = wStart; t <= wEnd; t++ )
		{
			final List< double[] > l = snap.get( t );
			if ( l != null )
				n += l.size();
		}
		return n;
	}

	/**
	 * Re-adds the snapshot spots whose timepoint falls in {@code [wStart, wEnd]} back into the graph
	 * with their full 3×3 covariance (ellipsoid shape), so trusted annotations can be labelled and
	 * trained on WITHOUT collapsing them to uniform spheres. Records use the {@code {x,y,z,cov0..8}}
	 * layout ({@link #REC_LEN}).
	 *
	 * @return number of spots re-added
	 */
	/**
	 * Puts {@code snap}'s spots back into {@code [wStart, wEnd]}.
	 *
	 * @return how many of the snapshot's positions the graph now holds a spot at — those this call added
	 *         PLUS those a protected spot was already occupying. Not "how many were added": a GT store
	 *         built from the user's own confirmed spots coincides with a protected spot at every
	 *         position, and a caller asking "is there anything here to train on?" needs the answer yes.
	 */
	private static int restoreSpots( final ToolContext context,
			final Map< Integer, List< double[] > > snap, final int wStart, final int wEnd )
	{
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();
		// The tp/fp tag set must EXIST before the write lock: GraphEdits writes the tp tag under it,
		// and creating a tag set is a structure change that must not happen while a vertex write lock
		// is held. With no tag set, the write is a no-op and the listener's fn stands.
		DetectionTagLabels.ensure( model );
		final Spot ref = graph.vertexRef();
		int added = 0;
		int alreadyPresent = 0;
		graph.getLock().writeLock().lock();
		try
		{
			final double[] p = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( int t = wStart; t <= wEnd; t++ )
			{
				final List< double[] > l = snap.get( t );
				if ( l == null )
					continue;
				for ( final double[] a : l )
				{
					p[ 0 ] = a[ 0 ];
					p[ 1 ] = a[ 1 ];
					p[ 2 ] = a[ 2 ];
					for ( int rr = 0; rr < 3; rr++ )
						for ( int cc = 0; cc < 3; cc++ )
							cov[ rr ][ cc ] = a[ 3 + rr * 3 + cc ];
					// Reliable-track snapshot positions already exclude protected spots (see
					// snapshotReliableSpots), but an EXTERNAL GT store annotation has no such
					// exclusion and could coincide with one.
					if ( GraphEdits.addSpot( context, graph, ref, t, p, cov,
							GraphEdits.Reason.TRAINING_POSITIVE ) == null )
					{
						// A protected spot is already sitting there, so that GT position IS represented in
						// the graph — it just did not need adding. Counting it as present rather than as
						// nothing is the whole difference between training on this GT and skipping it: a GT
						// store built from the user's own confirmed spots overlaps a protected spot at
						// EVERY position, deleteAllSpotsAtTimepoints spares those, and the caller's
						// "anything to train on?" gate then read 0 and trained nothing at all.
						alreadyPresent++;
						continue;
					}
					if ( a.length >= REC_LEN && a[ REC_LEN - 1 ] > 0.0 )
					{
						// A negative the USER marked. Restored as an fp-tagged spot so buildSpotsJson sends
						// it as one, which is the whole point of carrying the label through the store.
						final TagSet ts = DetectionTagLabels.ensure( model );
						final Tag fp = DetectionTagLabels.fpTag( ts );
						if ( fp != null )
							model.getTagSetModel().getVertexTags().set( ref, fp );
					}
					added++;
				}
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
			graph.releaseRef( ref );
		}
		if ( added > 0 )
		{
			context.getModel().setUndoPoint();
			context.notifyGraphChanged();
		}
		return added + alreadyPresent;
	}

	/**
	 * Deletes all spots (and their edges) at the given timepoints from the graph.
	 *
	 * @return number of spots deleted
	 */
	private static int deleteAllSpotsAtTimepoints( final ToolContext context, final int[] timepoints )
	{
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();
		final ProtectedSpots.Config protectedConfig = ProtectedSpots.fromContext( context );
		final Set< Integer > tpSet = new HashSet<>();
		for ( final int t : timepoints ) tpSet.add( t );

		final List< Spot > toDelete = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( tpSet.contains( spot.getTimepoint() )
						&& !ProtectedSpots.isProtected( model, spot, protectedConfig ) )
				{
					final Spot ref = graph.vertexRef();
					ref.refTo( spot );
					toDelete.add( ref );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( toDelete.isEmpty() ) return 0;

		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot spot : toDelete ) GraphEdits.removeSpot( context, graph, spot, GraphEdits.Reason.BULK );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		for ( final Spot spot : toDelete ) graph.releaseRef( spot );
		context.getModel().setUndoPoint();
		return toDelete.size();
	}

	/**
	 * Deletes spots at the given timepoints whose pool index is NOT in {@code confirmedIds} — leftover
	 * annotations the user placed but never finished adjusting (see {@link ResizedSpotTracker}).
	 *
	 * @return number of spots deleted
	 */
	private static int deleteUnconfirmedSpots( final ToolContext context, final int[] timepoints,
			final Set< Integer > confirmedIds )
	{
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();
		final ProtectedSpots.Config protectedConfig = ProtectedSpots.fromContext( context );
		final Set< Integer > tpSet = new HashSet<>();
		for ( final int t : timepoints ) tpSet.add( t );

		final List< Spot > toDelete = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( tpSet.contains( spot.getTimepoint() ) && !confirmedIds.contains( spot.getInternalPoolIndex() )
						&& !ProtectedSpots.isProtected( model, spot, protectedConfig ) )
				{
					final Spot ref = graph.vertexRef();
					ref.refTo( spot );
					toDelete.add( ref );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( toDelete.isEmpty() ) return 0;

		graph.getLock().writeLock().lock();
		try
		{
			// BULK, not TRAINING: this clears a whole timepoint's annotations wholesale. Both are exempt
			// from the floor and from the empty-frame guard, so the behaviour is identical; the tally
			// line is what changes, and it now says reset rather than training annotation.
			for ( final Spot spot : toDelete ) GraphEdits.removeSpot( context, graph, spot, GraphEdits.Reason.BULK );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		for ( final Spot spot : toDelete ) graph.releaseRef( spot );
		context.getModel().setUndoPoint();
		return toDelete.size();
	}

	/**
	 * Iterates through timepoints in chunks. When {@code correctPerChunk} is
	 * {@code true} each chunk runs predict → link-backward → autocorrect before
	 * advancing to the next chunk. Otherwise the full predict pass and the full
	 * link pass are kept separate (original behaviour). Progress is logged via
	 * {@link ToolContext#log} after every chunk. Cancellation is checked at the
	 * start of each chunk.
	 */
	private static String runAllTimepoints(
			final ToolContext context,
			final ElephantIntegration elephant,
			final String mode,
			final int startTp,
			final int endTp,
			final int numTimepoints,
			final int loadDelayMs,
			final double minIntensityRatio,
			final double qualityThreshold,
			final int chunkSize,
			final boolean correctPerChunk,
			final AutomahoutSettings corrSettings )
	{
		final ViewerPanel viewer = context.requireViewer();
		int predictOk = 0, predictFail = 0, linkOk = 0, linkFail = 0;
		int darkDeleted = 0, corrected = 0;
		final int total = endTp - startTp + 1;
		final int effectiveChunk = chunkSize > 0 ? chunkSize : total;
		boolean cancelled = false;

		if ( correctPerChunk && "predict_and_link".equals( mode ) )
		{
			// ---- interleaved mode: per-chunk predict → link → correct ----
			outer:
			for ( int cs = startTp; cs <= endTp; cs += effectiveChunk )
			{
				if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				{
					cancelled = true;
					break;
				}
				final int ce = Math.min( cs + effectiveChunk - 1, endTp );
				context.log( String.format( "=== Chunk t=%d..%d (of %d..%d) ===",
						cs, ce, startTp, endTp ) );

				// 1. Predict forward
				context.log( "  [1/3] Predict..." );
				for ( int t = cs; t <= ce; t++ )
				{
					if ( !setTimepoint( viewer, t, loadDelayMs ) )
					{
						cancelled = true;
						break outer;
					}
					final ElephantIntegration e = new ElephantIntegration( context );
					if ( !e.init() ) { predictFail++; continue; }
					if ( e.predictSpots( t ) )
					{
						predictOk++;
						if ( minIntensityRatio > 0.0 || qualityThreshold > 0.0 )
							darkDeleted += AutoCorrectOrchestrator.filterWeakSpots(
									context, t, minIntensityRatio, qualityThreshold );
					}
					else
					{
						predictFail++;
					}
				}

				// 2. Link backward within chunk
				context.log( "  [2/3] Link (backward)..." );
				for ( int t = ce; t >= cs; t-- )
				{
					if ( !setTimepoint( viewer, t, loadDelayMs ) )
					{
						cancelled = true;
						break outer;
					}
					final ElephantIntegration e = new ElephantIntegration( context );
					if ( !e.init() ) { linkFail++; continue; }
					if ( e.linkSpots() ) linkOk++; else linkFail++;
				}

				// 3. Autocorrect the chunk
				context.log( "  [3/3] Autocorrect..." );
				for ( int t = cs; t <= ce; t++ )
				{
					AutoCorrectOrchestrator.correctTimepoint( context, t, numTimepoints,
							corrSettings, cs );
					corrected++;
				}

				context.log( String.format(
						"  → chunk done: predict ok=%d fail=%d, dark removed=%d, "
								+ "link ok=%d fail=%d, corrected=%d",
						predictOk, predictFail, darkDeleted, linkOk, linkFail, corrected ) );
			}
		}
		else
		{
			// ---- original mode: all predict forward, then all link backward ----
			if ( "predict".equals( mode ) || "predict_and_link".equals( mode ) )
			{
				predict:
				for ( int cs = startTp; cs <= endTp; cs += effectiveChunk )
				{
					if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
					{
						cancelled = true;
						break;
					}
					final int ce = Math.min( cs + effectiveChunk - 1, endTp );
					context.log( String.format( "ELEPHANT predict: t=%d..%d (of %d..%d)",
							cs, ce, startTp, endTp ) );
					for ( int t = cs; t <= ce; t++ )
					{
						if ( !setTimepoint( viewer, t, loadDelayMs ) )
						{
							cancelled = true;
							break predict;
						}
						final ElephantIntegration e = new ElephantIntegration( context );
						if ( !e.init() ) { predictFail++; continue; }
						if ( e.predictSpots( t ) )
						{
							predictOk++;
							if ( minIntensityRatio > 0.0 || qualityThreshold > 0.0 )
								darkDeleted += AutoCorrectOrchestrator.filterWeakSpots(
										context, t, minIntensityRatio, qualityThreshold );
						}
						else
						{
							predictFail++;
						}
					}
					context.log( String.format(
							"  → predict chunk done: ok=%d fail=%d, dark removed=%d",
							predictOk, predictFail, darkDeleted ) );
				}
			}

			if ( !cancelled && ( "link".equals( mode ) || "predict_and_link".equals( mode ) ) )
			{
				link:
				for ( int ce = endTp; ce >= startTp; ce -= effectiveChunk )
				{
					if ( context.isCancelRequested() || Thread.currentThread().isInterrupted() )
					{
						cancelled = true;
						break;
					}
					final int cs = Math.max( ce - effectiveChunk + 1, startTp );
					context.log( String.format( "ELEPHANT link: t=%d..%d (of %d..%d, backward)",
							cs, ce, startTp, endTp ) );
					for ( int t = ce; t >= cs; t-- )
					{
						if ( !setTimepoint( viewer, t, loadDelayMs ) )
						{
							cancelled = true;
							break link;
						}
						final ElephantIntegration e = new ElephantIntegration( context );
						if ( !e.init() ) { linkFail++; continue; }
						if ( e.linkSpots() ) linkOk++; else linkFail++;
					}
					context.log( String.format(
							"  → link chunk done: ok=%d fail=%d", linkOk, linkFail ) );
				}
			}
		}

		// Summary
		final StringBuilder sb = new StringBuilder();
		if ( cancelled )
			sb.append( "STOPPED by user. " );
		sb.append( String.format( "Processed %d timepoints (t=%d..%d). ", total, startTp, endTp ) );
		if ( predictOk + predictFail > 0 )
			sb.append( String.format( "Predict: %d ok, %d failed. ", predictOk, predictFail ) );
		if ( darkDeleted > 0 )
			sb.append( String.format( "Dark spots removed: %d. ", darkDeleted ) );
		if ( linkOk + linkFail > 0 )
			sb.append( String.format( "Link: %d ok, %d failed. ", linkOk, linkFail ) );
		if ( corrected > 0 )
			sb.append( String.format( "Autocorrected %d timepoints.", corrected ) );
		return sb.toString().trim();
	}

	/**
	 * Sets the viewer timepoint on the EDT and waits for the change to complete,
	 * then sleeps for {@code delayMs} to allow BDV to load the slice.
	 *
	 * @return {@code true} on success, {@code false} if interrupted
	 */
	private static boolean setTimepoint( final ViewerPanel viewer, final int t,
			final int delayMs )
	{
		try
		{
			SwingUtilities.invokeAndWait( () -> {
				viewer.state().setCurrentTimepoint( t );
				viewer.requestRepaint();
			} );
			if ( delayMs > 0 )
				Thread.sleep( delayMs );
			return true;
		}
		catch ( final InterruptedException e )
		{
			Thread.currentThread().interrupt();
			return false;
		}
		catch ( final InvocationTargetException e )
		{
			log.warn( "Failed to set viewer timepoint to {}", t, e );
			return false;
		}
	}

	/** What the window loop does after a stage. */
	private enum Next
	{
		/** Go on to the next stage. */
		CONTINUE,
		/** Give this window up (the stage has already put it back, and set a retry if it wants one). */
		ABANDON,
		/** End the run, returning {@link WindowRun#stopMessage}. */
		STOP
	}

	/**
	 * One stage of the window loop: an id, the {@link ToolRole} it serves, and what it does to a window.
	 * The built-in plan below is the order the loop always ran in; a planner choosing per stage from the
	 * tools that suit the data is the next step (docs/architecture.md, section 1.6).
	 */
	private static final class WindowStage
	{
		final String id;

		final ToolRole role;

		private final java.util.function.Function< WindowRun, Next > body;

		WindowStage( final String id, final ToolRole role, final java.util.function.Function< WindowRun, Next > body )
		{
			this.id = id;
			this.role = role;
			this.body = body;
		}

		Next run( final WindowRun w )
		{
			return body.apply( w );
		}

		@Override
		public String toString()
		{
			return id + " (" + role + ")";
		}
	}

	/** The built-in plan: the stages in the order the window loop has always run them. */
	private static final List< WindowStage > WINDOW_PLAN = java.util.Collections.unmodifiableList( Arrays.asList(
			new WindowStage( "elephant.detect", ToolRole.DETECT, ElephantTool::detectStage ),
			new WindowStage( "nn.link", ToolRole.LINK, ElephantTool::linkStage ),
			new WindowStage( "autocorrect.window", ToolRole.CORRECT_DETECTIONS, ElephantTool::correctStage ),
			new WindowStage( "elephant.refine", ToolRole.TRAIN, ElephantTool::refineStage ),
			new WindowStage( "topology.consistency", ToolRole.CORRECT_LINEAGE, ElephantTool::consistencyStage ) ) );

	/** The plan when a pretrained detector was chosen: it detects; ELEPHANT's training stage is left out. */
	private static List< WindowStage > pretrainedPlan()
	{
		return java.util.Collections.unmodifiableList( Arrays.asList(
				new WindowStage( "pretrained.detect", ToolRole.DETECT, ElephantTool::pretrainedDetectStage ),
				WINDOW_PLAN.get( 1 ), WINDOW_PLAN.get( 2 ), WINDOW_PLAN.get( 4 ) ) );
	}

	/** {@code plan} as one line for the log: {@code a (ROLE) -> b (ROLE) -> ...}. */
	static String describePlan( final List< WindowStage > plan )
	{
		final StringBuilder sb = new StringBuilder();
		for ( final WindowStage s : plan )
			sb.append( sb.length() == 0 ? "" : " -> " ).append( s );
		return sb.toString();
	}

	/** The built-in plan as one line. */
	static String describePlan()
	{
		return describePlan( WINDOW_PLAN );
	}

	/**
	 * Tries the pretrained detectors (see DetectorTrial); null for ELEPHANT. When no remote tool server
	 * offers one, nothing is measured or asked and the run goes on as it always did.
	 */
	private static org.mastodon.mamut.automahout.tool.remote.FrameDetector choosePretrainedDetector( final WindowRun w )
	{
		final ToolContext ctx = w.context;
		final org.mastodon.mamut.automahout.tool.ToolRegistry registry =
				org.mastodon.mamut.automahout.tool.ToolRegistry.forContext( ctx );
		boolean any = ctx.getSharedData( org.mastodon.mamut.automahout.tool.remote.DetectorTrial.SHARED_CHOICE ) != null;
		for ( final org.mastodon.mamut.automahout.tool.AutomahoutTool t : registry.getAll() )
			any |= t instanceof org.mastodon.mamut.automahout.tool.remote.FrameDetector;
		if ( !any )
			return null;
		// Modality decides which models apply (a brightfield model is not tried on fluorescence).
		ImageProfile.forRun( ctx, () -> AutoCorrectOrchestrator.sampleIntensities( ctx, w.startTp, ImageProfile.SAMPLES ) );
		final int[] frames = java.util.stream.IntStream.of( w.startTp, ( w.startTp + w.endTp ) / 2, w.endTp )
				.distinct().toArray();
		final org.mastodon.mamut.automahout.tool.remote.FrameDetector chosen =
				org.mastodon.mamut.automahout.tool.remote.DetectorTrial.choose( ctx, registry,
						org.mastodon.mamut.automahout.tool.DataProfile.of( ctx ), frames );
		// The counts the user accepted beat the agent's guess at the cell count, which is what the
		// ceilings are otherwise built on: run 20261012 had StarDist find 48 cells/frame against a guessed
		// ceiling of 45, and every check after the commit called that an explosion. A count the user
		// stated in the pre-flight dialog is left alone.
		final Object counts = ctx.getSharedData( org.mastodon.mamut.automahout.tool.remote.DetectorTrial.SHARED_ACCEPTED_COUNTS );
		if ( chosen != null && counts instanceof int[][]
				&& GuessedCeiling.isUnconfirmedGuess( ctx, ctx.getSharedData( SHARED_COLD_START_CEILING_OVERRIDE ) instanceof Number ) )
		{
			final int[][] c = ( int[][] ) counts;
			GuessedCeiling.takeCounts( ctx, SHARED_COLD_START_CEILING_OVERRIDE, Math.max( 1, c[ 1 ][ 0 ] ),
					Math.max( 1, c[ 1 ][ c[ 1 ].length - 1 ] ), "you accepted " + chosen.getId() + " in the trial",
					"its counts at t=" + c[ 0 ][ 0 ] + " and t=" + c[ 0 ][ c[ 0 ].length - 1 ] + ", replacing the agent's guess" );
		}
		return chosen;
	}

	/**
	 * [1/6]..[3/6] with a pretrained detector chosen in the trial: every frame of the window detected by
	 * it as it is, then the commit's check. Nothing is trained. A check that fails is reported and the
	 * detection kept -- the user accepted this detector, and rebuilding ELEPHANT's would not help it.
	 */
	private static Next pretrainedDetectStage( final WindowRun w )
	{
		final org.mastodon.mamut.automahout.tool.remote.FrameDetector d = w.externalDetector;
		w.priorSpots = deleteAllSpotsAtTimepoints( w.context, w.allTps );
		if ( w.priorSpots > 0 )
			w.context.log( "  Cleared " + w.priorSpots + " existing spots for fresh detection." );
		w.gtInWindow = 0;
		w.coldStart = false;
		w.committedFullFrame = true;
		w.commitAlreadyExploded = false;
		computeWindowLimits( w );
		w.context.log( "  [1/6] Detecting with " + d.getId() + " (pretrained, as it is)..." );
		final org.mastodon.mamut.automahout.tool.remote.Frame.Reader reader =
				org.mastodon.mamut.automahout.tool.remote.DetectorTrial.reader( w.context );
		int frames = 0;
		for ( int t = w.wStart; t <= w.wEnd; t++ )
		{
			if ( w.context.isCancelRequested() || Thread.currentThread().isInterrupted() )
				break;
			final org.mastodon.mamut.automahout.tool.remote.Frame f = reader.read( t ); // no graph lock
			if ( f == null )
				continue;
			try
			{
				org.mastodon.mamut.automahout.tool.remote.FrameDetector.addToGraph( w.context, f,
						d.detect( f, org.mastodon.mamut.automahout.tool.remote.FrameDetector.hints( w.context ) ),
						false );
				frames++;
			}
			catch ( final java.io.IOException e )
			{
				w.context.log( String.format( "  %s at t=%d: %s -- nothing detected for this frame.", d.getId(), t,
						e.getMessage() ) );
			}
		}
		w.grandTotalPredict += frames;
		w.metrics = computeQualityMetrics( w.context, w.allTps );
		w.pFrac = computeProblematicFraction( w.context, w.allTps, w.effectiveBorderMargin, w.maxAspectRatio );
		w.initialSpotsPerTp = w.metrics[ 0 ];
		w.context.log( String.format( "  Committed detection (%s): spots/tp=%.1f (CV_temporal=%.2f), size=%.1f, "
				+ "border=%.0f%%", d.getId(), w.metrics[ 0 ], w.metrics[ 1 ], w.metrics[ 2 ], w.pFrac[ 0 ] * 100 ) );
		w.invOutDetect = PipelineInvariants.checkAndLog( w.context, "[3/6] commit detection", w.inv,
				w.wStart, w.wEnd, w.detectInvariants );
		w.inv = w.invOutDetect.snapshot;
		if ( w.invOutDetect.hasError() )
			w.context.log( "  WARNING: the pretrained detection failed its check (see above); it is kept -- you "
					+ "accepted " + d.getId() + " in the trial. If this window looks wrong, re-run and choose ELEPHANT." );
		w.lastChecked = snapshotSpots( w.context, w.wStart, w.wEnd );
		w.detectionChecked = true;
		return Next.CONTINUE;
	}

	/**
	 * The window's priors and the limits its checks use: the previous window's spots/tp, the ceilings and
	 * floor, and the Limits of the detect, correct and refine checks. Every detection stage computes these,
	 * ELEPHANT's at the point it always did (after its test detection), a pretrained detector's first.
	 */
	private static void computeWindowLimits( final WindowRun w )
	{
		// The PREVIOUS window's own spots/tp is a strong, cheap prior for "how many cells are really
		// here": across only window_size timepoints, the true count rarely swings far from the
		// immediately preceding window's (see PREV_WINDOW_PLAUSIBLE_MAX/PREV_WINDOW_EXPLOSION_RATIO)
		// — unlike the image-derived completeness estimate, which — even after contrast calibration —
		// can still be wrong for an unusual frame. Used ONLY against the final full-frame commit
		// below (comparing it to a crop-scoped count would be meaningless). {@code <= 0} (no
		// previous window, e.g. the very first one) disables the check.
		// Only from a window the pipeline did NOT declare collapsed: a collapsed window's spots/tp is
		// not a measurement of anything. Observed: t=60..69 ended at 21.6 spots/tp after its detection
		// fell apart, and t=70..73 then inherited 21.6 as its "expected" count — an explosion threshold
		// of 43 on a dataset holding ~150-200 cells per frame, which is not a usable prior for either
		// the PREV_WINDOW_EXPLOSION_RATIO check or the invariant ceiling.
		// The first window of a run that starts mid-movie (resumed, or a benchmark continuing a saved run) has
		// the frames before it as history: tracked already, by an earlier run or the user. Without them its
		// only prior was the agent's guess, and CE 01 continued at t=120 rejected 81-92 detected cells per
		// frame (truth about 127) against a guess of 40.
		final boolean fromHistory = w.wStart == w.startTp && w.wStart > 0
				&& maxTimepointWithSpots( w.context, w.wStart - 1 ) == w.wStart - 1;
		w.prevWindowBaseline = w.wStart > w.startTp && w.lastPlausibleWindowEnd == w.wStart - 1 || fromHistory
				? computeQualityMetrics( w.context,
						allTimepointsArray( Math.max( fromHistory ? 0 : w.startTp, w.wStart - w.effectiveWindow ),
								w.wStart - 1 ) )[ 0 ]
				: -1;
		w.priorMeasured = fromHistory;
		// A dividing population outgrows the previous window's count: the user chose (2026-10-23) to judge a
		// window against the growth the run has MEASURED rather than a guess. The accepted windows' counts,
		// extrapolated to this window's end by their own growth rate, raise the prior when they say more;
		// with no growth measured it is the previous window's count, as before. Measured also when the
		// previous window was rolled back, so one rejected window no longer leaves every later one judged
		// against the agent's guess (Fluo-N3DH-CE 01: t=160..249 all rolled back against 160/tp).
		final double grown = growthExpected( w );
		if ( grown > 0 && grown > w.prevWindowBaseline )
		{
			final GrowthPrior.Fit fit = growthFit( w );
			w.context.log( String.format( "  Expected count: %.1f spots/tp by t=%d from the run's own growth (doubling "
					+ "every %.0f tp, over %d accepted timepoint(s)); %s.", grown, w.wEnd, fit.doublingTime(), fit.points,
					w.prevWindowBaseline > 0 ? String.format( "the previous window held %.1f", w.prevWindowBaseline )
							: "the previous window gave no count (rolled back or none)" ) );
			w.prevWindowBaseline = grown;
			w.priorMeasured = true;
		}

		// Ceiling for the invariant checks below. These are two different KINDS of number and each
		// needs its own margin, which is easy to get wrong: coldStartCeiling is already an upper
		// BOUND, not an expectation — AutonomousTrackingAction stores the user's stated cells/frame
		// multiplied by 2 before it ever reaches here (250 entered in the dialog arrives as 500), and
		// the parameter/DEFAULT_COLD_START_CEILING variants are likewise bounds — so applying the
		// invariant's own explosion margin on top would place the threshold at 4x what the user
		// actually said, far too loose to ever fire. prevWindowBaseline, in contrast, is a RAW
		// measured spots/tp, so the usual margin does apply to it. Prefer it wherever it exists: it
		// is the tighter, self-calibrating prior, for exactly the reason PREV_WINDOW_EXPLOSION_RATIO's
		// own comment gives about absolute thresholds missing runaways that stay under them.
		// Per-window, not run-wide: this is the check that reported "9 timepoint(s) pushed over 600.0
		// spots (1.0x the expected 600.0/tp)" on a window whose timepoints were stated to hold 26.
		w.invariantCeiling = ExpectedCellCount.ceilingOver( w.context, w.wStart, w.wEnd,
				w.prevWindowBaseline, w.prevWindowBaseline > 0 ? w.prevWindowBaseline : w.coldStartCeiling );
		w.invariantCeilingRatio = w.prevWindowBaseline > 0 ? PREV_WINDOW_EXPLOSION_RATIO : 1.0;
		// Relaxed by the same tolerance the deletion floor uses, so the inline check and the passes
		// that honour the floor agree on what the estimate permits — see
		// ExpectedCellCount.STATED_COUNT_MARGIN.
		w.invariantFloor = ExpectedCellCount.toleratedFloor( ExpectedCellCount.expectedAt( w.context, w.wStart ) );
		// The same preference, published for the per-timepoint circuit breaker inside predictWindow.
		// It aborts the window outright, so it is the one guard that most needs the tighter prior —
		// and was the last one still reading the run-wide bound alone.
		// A prior measured from the run's own accepted frames (their growth, or the history before a mid-movie
		// start) is not held to MAX_CEILING_GROWTH_MULTIPLE times the agent's guess: that cap exists so one
		// unchecked window cannot ratchet the guard up, and the growth fit is robust to one window. Kept at 4x
		// the guess of 40, CE 01 would have been cut at 160 cells per frame from about t=150 on.
		final double capBase = w.priorMeasured ? Math.max( w.coldStartCeiling, w.prevWindowBaseline ) : w.coldStartCeiling;
		w.context.setSharedData( SHARED_WINDOW_EXPLOSION_CEILING,
				windowExplosionCeiling( capBase, w.prevWindowBaseline ) );
		// The same number with the stated fallback removed — see prevWindowCalibrated.
		w.context.setSharedData( SHARED_WINDOW_CALIBRATED_CEILING,
				w.prevWindowBaseline > 0 ? windowExplosionCeiling( capBase, w.prevWindowBaseline )
						: null );
		w.context.setSharedData( SHARED_FLAT_CONTRAST_AUDIT_PENDING, FLAT_CONTRAST_AUDITS_PER_WINDOW );
		// A pure predict is strictly confined to its own timepoints, so no margin is allowed here.
		w.detectInvariants = PipelineInvariants.limits()
				.expectedCeiling( w.invariantCeiling )
				.ceilingRatio( w.invariantCeilingRatio );
		// Correction reaches exactly one timepoint past each end of the window by design:
		// AutoCorrectOrchestrator.recoverMissingPredecessors adds a spot at t-1 and fillGaps adds one
		// at t+1, so correcting t=wStart..wEnd legitimately writes to wStart-1 and wEnd+1. That reach
		// is declared here rather than left as noise, so anything BEYOND one timepoint still reports.
		// A correction phase that has to invent a quarter of a timepoint, or that lowers continuity,
		// is undoing detection rather than repairing it.
		w.correctInvariants = PipelineInvariants.limits()
				.expectedCeiling( w.invariantCeiling )
				.ceilingRatio( w.invariantCeilingRatio )
				.statedCellsPerFrame( w.invariantFloor )
				.maxAddedFraction( MAX_INVENTED_SPOT_FRACTION )
				.continuityDropTolerance( MAX_CONTINUITY_DROP )
				.outOfWindowMargin( 1 );
		// Refinement re-detects (so spot counts are expected to grow, and the added-spot limit above
		// would misfire) but also runs the same enrich/correct passes, hence the same 1-tp reach.
		w.refineInvariants = PipelineInvariants.limits()
				.expectedCeiling( w.invariantCeiling )
				.ceilingRatio( w.invariantCeilingRatio )
				.statedCellsPerFrame( w.invariantFloor )
				.outOfWindowMargin( 1 );
	}

	/** The run's accepted counts' growth fit, or null when there are too few (see {@link GrowthPrior}). */
	private static GrowthPrior.Fit growthFit( final WindowRun w )
	{
		// From the movie's start: frames before this run's start are history (tracked earlier), and count.
		final int from = 0, to = Math.min( w.lastPlausibleWindowEnd, w.wStart - 1 );
		if ( to < from )
			return null;
		final int n = to - from + 1;
		final int[] ts = new int[ n ];
		final double[] counts = new double[ n ];
		for ( int i = 0; i < n; i++ )
			ts[ i ] = from + i;
		final ModelGraph graph = w.context.getGraph();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t >= from && t <= to )
					counts[ t - from ]++;
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return GrowthPrior.fit( ts, counts );
	}

	/** Spots/tp the run's own growth expects by the end of this window; {@code -1} when not measurable. */
	private static double growthExpected( final WindowRun w )
	{
		final GrowthPrior.Fit fit = growthFit( w );
		return fit == null ? -1 : fit.expectedAt( w.wEnd );
	}

	/**
	 * After every whole-window ELEPHANT predict in [1/6]..[3/6]: duplicate resolution over the window (opt-in,
	 * see CleanupToggles), non-maximum suppression when a dedup factor is set, and the malformed-shape filter
	 * (border fragments, elongated spots) -- the same three steps, in this order, after the test detection, a
	 * GT re-detect, each tuning round and the commit.
	 */
	private static void cleanUpDetectedWindow( final WindowRun w )
	{
		final int resolvedDup = AutoCorrectOrchestrator.resolveOverlappingDuplicates( w.context, w.wStart, w.wEnd );
		if ( resolvedDup > 0 )
			w.context.log( "  Deleted " + resolvedDup + " over-segmented duplicate(s)." );
		if ( w.dedupFactor > 0.0 )
		{
			final int dup = deduplicateSpots( w.context, w.allTps, w.dedupFactor );
			if ( dup > 0 )
				w.context.log( "  NMS: removed " + dup + " duplicate spots." );
		}
		final int[] malformed = filterMalformedSpots( w.context, w.allTps, w.effectiveBorderMargin, w.maxAspectRatio );
		if ( malformed[ 0 ] + malformed[ 1 ] > 0 )
			w.context.log( String.format( "  Deleted %d border + %d elongated malformed spot(s).", malformed[ 0 ],
					malformed[ 1 ] ) );
	}

	/**
	 * [1/6]..[3/6]: test detection, tuning (train / re-detect rounds, or GT rounds), the one full-frame
	 * commit, and its invariant check. Built-in DETECT stage.
	 */
	private static Next detectStage( final WindowRun w )
	{
		// Clear any existing spots in this window before fresh detection.
		// Without this, re-running adaptive_track accumulates old spots on top of
		// new predictions, causing NMS to remove carefully corrected spots as "duplicates."
		w.priorSpots = deleteAllSpotsAtTimepoints( w.context, w.allTps );
		if ( w.priorSpots > 0 )
			w.context.log( "  Cleared " + w.priorSpots + " existing spots for fresh detection." );

		// Crop box(es) for this window's ENTIRE local-tuning phase — test detection, cold-start
		// bootstrap verification, and every training iteration. Full-frame prediction happens EXACTLY
		// ONCE per window: the single mandatory commit after this tuning phase concludes (see the
		// commit block after the training loop below) — NEVER during tuning, regardless of whether
		// density looks plausible or how many iterations it takes. (Repeated user request: local
		// optimisation must complete before any whole-image analysis, no exceptions.)
		//
		// Multiple BATCHES (TUNING_ROI_BATCH_COUNT), not one: tuning on a single fixed region only
		// teaches the model that one region's background/density/illumination, so a spatially
		// heterogeneous frame can still show a large error the moment the mandatory commit switches
		// to full-frame — the training loop below rotates through these across iterations instead of
		// hammering the same region every round.
		w.clusterRadiusForWindow = TUNING_ROI_CLUSTER_RADIUS_FRACTION * Math.min( w.physDims[ 0 ], w.physDims[ 1 ] );
		// Re-picked only every TUNING_ROI_REUSE_TIMEPOINTS, not every window — see there.
		w.roiStart = System.nanoTime();
		w.windowRoiCenters = w.cachedRoiCenters;
		if ( w.windowRoiCenters == null || w.wStart - w.cachedRoiCentersTp >= TUNING_ROI_REUSE_TIMEPOINTS )
		{
			w.windowRoiCenters = pickTuningRoiCenters( w.viewer, w.wStart, w.physDims,
					Math.max( 20, w.nSeedsPerTp ), w.clusterRadiusForWindow, TUNING_ROI_BATCH_COUNT );
			w.cachedRoiCenters = w.windowRoiCenters;
			w.cachedRoiCentersTp = w.wStart;
		}
		else
		{
			w.context.log( String.format( "  Reusing the %d tuning region(s) picked at t=%d (re-picked "
					+ "every %d timepoints — the image scan that finds them costs more than the "
					+ "predictions they scope).",
					w.windowRoiCenters.size(), w.cachedRoiCentersTp, TUNING_ROI_REUSE_TIMEPOINTS ) );
		}
		PhaseTimings.record( w.context, "pick tuning region (image scan)", w.roiStart );
		// null everywhere means full-frame — see CleanupToggles.SHARED_CROP_TUNING_OVERRIDE.
		w.cropTuning = CleanupToggles.cropTuningEnabled( w.context );
		w.windowCropBoxes = new ArrayList<>();
		for ( final double[] c : w.windowRoiCenters )
			w.windowCropBoxes.add( new double[] { c[ 0 ], c[ 1 ], c[ 2 ], c[ 0 ], c[ 1 ], c[ 2 ] } );
		w.windowCropBox = w.cropTuning ? w.windowCropBoxes.get( 0 ) : null;
		if ( w.cropTuning && w.windowCropBoxes.size() > 1 )
			w.context.log( "  Tuning will rotate across " + w.windowCropBoxes.size()
					+ " distinct local region(s) across training iterations." );

		// Step 1: Test detection with current model — CROP-RESTRICTED, never full-frame. First,
		// cheaply estimate whether this window is an obvious flood using a grid of small
		// crop-restricted probes at one representative timepoint (see estimateGridDensity) —
		// extrapolated to a whole-frame estimate by area ratio. Several samples (not one) guard
		// against a spatially non-uniform cell density skewing the estimate from a single
		// lucky/unlucky patch. Only when the probe does NOT already show a flood do we run a real
		// (but still crop-restricted) test detection, to get actual annotations for training.
		{
			// Everything between the window header and this line, itemised — the header-to-[1/6] gap
			// is otherwise a blank three minutes in the log with nothing named in it.
			final String preambleTiming =
					PhaseTimings.summarySince( w.context, w.preambleMark, SLOW_TIMEPOINT_REPORT_NANOS );
			if ( !preambleTiming.isEmpty() )
				w.context.log( "  Window set-up" + preambleTiming.substring(
						preambleTiming.indexOf( "Time:" ) - 1 ) );
		}
		w.context.log( w.cropTuning
				? "  [1/6] Testing detection with current model (crop-restricted)..."
				: "  [1/6] Testing detection with current model (full frame — crop-tuning off)..." );

		// Trusted annotations available for this window? If so, they drive training (below) and we
		// skip the intensity-seed cold-start bootstrap entirely — real GT is a better teacher than
		// seeds, and the GT branch commits its own single full-frame re-detect directly.
		w.gtInWindow = countSnapshotInWindow( w.trustedAnnotations, w.wStart, w.wEnd );

		/* metrics: a WindowRun field */
		/* pFrac: a WindowRun field */
		w.coldStart = false;
		if ( w.gtInWindow > 0 )
		{
			w.context.log( "  (Skipping crop test detection — trusted GT annotations drive training "
					+ "below.)" );
			w.metrics = new double[] { 0, 0, 0, 0 };
			w.pFrac = new double[] { 0, 0 };
		}
		else
		{
			// The probe is itself a set of crop-restricted predicts, so it belongs to the same toggle
			// as the rest of the crop machinery. It was not, which made "crop-tuning off" a promise
			// the run did not keep: on a server whose cropped /seg/predict fails, every window still
			// fired DENSITY_PROBE_GRID_SIZE^2 requests that could only fail. Skipping it costs a
			// shortcut, not a check — all it does is jump an obvious flood straight to the cold-start
			// bootstrap, and the full-frame test detection below reaches the same conclusion.
			final double gridDensityEstimate = CleanupToggles.cropTuningEnabled( w.context )
					? estimateGridDensity( w.context, w.viewer, w.wStart, w.physDims,
							DENSITY_PROBE_GRID_SIZE, w.trackIntensityRatio, w.qualityThreshold, w.minSize )
					: -1.0;
			// Against the previous window's committed count when there is one, as the other per-window
			// ceilings are (prevWindowBaseline): a growing culture outgrows the run-start guess. PSC 01,
			// condition E (GPU server, 2026-10-20): the agent guessed 250/tp, the windows before had committed
			// 459 and 538, the probe read 787 -- "3.1x the ceiling" -- and a healthy model was rebuilt from
			// intensity seeds on phase contrast. A real flood is still caught: against the measured count,
			// and by the full-frame detection's explosion breaker, which uses the same history.
			final double floodCeiling = ExpectedCellCount.ceilingOver( w.context, w.wStart, w.wEnd,
					w.prevWindowBaseline, w.coldStartCeiling );
			final boolean gridFlood = gridDensityEstimate > 0.0
					&& gridDensityEstimate > floodCeiling * EXTREME_FLOOD_RATIO;
			final String seedRefusal = gridFlood ? seedResetRefusal( w.context, w.viewer, w.startTp,
					w.lastPlausibleWindowEnd ) : null;
			if ( seedRefusal != null )
				w.context.log( String.format( "  Grid density probe estimates ~%.0f spots/tp (%.1fx the ceiling of %.0f), "
						+ "but %s -- detecting the window with the current model instead.", gridDensityEstimate,
						gridDensityEstimate / Math.max( 1.0, floodCeiling ), floodCeiling, seedRefusal ) );
			final boolean gridSuggestsFlood = gridFlood && seedRefusal == null;
			if ( gridSuggestsFlood )
			{
				w.context.log( String.format(
						"  Grid density probe (%d sampled patch(es)) estimates ~%.0f spots/tp "
								+ "full-frame (%.1fx the ceiling of %.0f: %s) — going straight to cold-start bootstrap.",
						DENSITY_PROBE_GRID_SIZE * DENSITY_PROBE_GRID_SIZE, gridDensityEstimate,
						gridDensityEstimate / Math.max( 1.0, floodCeiling ), floodCeiling,
						w.prevWindowBaseline > 0 ? "the previous window's committed count or the stated one"
								: "cold_start_spots_per_tp, no window committed yet" ) );
				w.metrics = new double[] { gridDensityEstimate, 0, 0, 0 };
				w.pFrac = new double[] { 0, 0 };
				w.coldStart = true;
			}
			else
			{
				try
				{
					predictWindow( w.context, w.viewer, w.wStart, w.wEnd, w.trackIntensityRatio, w.qualityThreshold,
							w.minSize, w.windowCropBox, w.coldStartCeiling );
				}
				catch ( final DetectionExplosionException ex )
				{
					if ( abandonWindowOnExplosion( w.context, w.elephant, w.viewer, w.wStart, w.wEnd,
							w.windowSpotsBefore, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs,
							w.trainTimeoutMs, ex ) )
						w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
					return Next.ABANDON;
				}
				cleanUpDetectedWindow( w );
				w.metrics = computeQualityMetrics( w.context, w.allTps );
				w.pFrac = computeProblematicFraction( w.context, w.allTps, w.effectiveBorderMargin, w.maxAspectRatio );
				// Scale-invariant collapse signal (a FRACTION, unlike absolute count) still applies to
				// a crop: a regular grid of zero-contrast "detections" is just as damning within a
				// small crop as it is full-frame.
				final double cropZeroFrac = computeZeroIntensityFraction( w.context, w.allTps );
				w.coldStart = cropZeroFrac > ZERO_INTENSITY_FLOOD_FRACTION;
				if ( w.coldStart )
				{
					final String refusal = seedResetRefusal( w.context, w.viewer, w.startTp, w.lastPlausibleWindowEnd );
					if ( refusal != null )
					{
						w.context.log( String.format( "  %.0f%% of the detections have no contrast, but %s -- keeping the "
								+ "current model.", cropZeroFrac * 100, refusal ) );
						w.coldStart = false;
					}
				}
				w.context.log( String.format(
						"  Initial quality (crop-scoped, NOT full-frame density): spots/tp=%.1f "
								+ "(CV_temporal=%.2f), size=%.1f (CV_size=%.2f), border=%.0f%%, "
								+ "elongated=%.0f%%, zero-contrast=%.0f%%",
						w.metrics[ 0 ], w.metrics[ 1 ], w.metrics[ 2 ], w.metrics[ 3 ], w.pFrac[ 0 ] * 100,
						w.pFrac[ 1 ] * 100, cropZeroFrac * 100 ) );
			}
		}
		// Bisecting the ('tp', 0), ('fn', N) anomaly. Here nothing has annotated anything yet: the
		// window has only been predicted into. The tally at [2/6] already said untagged=0, fn=all —
		// which cannot come from tagAsMissedCell (the one writer, and correction has not run). If
		// THIS line also says fn=N, the detections arrive tagged and the writer is in the predict
		// path; if it says untagged=N, the writer is between here and [2/6].
		w.context.log( "    [1/6] fresh detections: "
				+ DetectionTagLabels.tally( w.context.getModel(), w.context.getGraph(), w.allTps )
				+ " — " + DetectionTagLabels.describeTagSet( w.context.getModel() ) );
		w.initialSpotsPerTp = w.metrics[ 0 ];

		computeWindowLimits( w );

		// Cold-start / untrained-model regime: bootstrap from model-free intensity seeds (the
		// automated stand-in for a human annotating a few cells by eye) — crop-restricted throughout,
		// like everything else in this tuning phase.
		if ( w.coldStart && w.gtInWindow == 0 )
		{
			w.context.log( "  COLD START detected — model looks untrained/collapsed. Bootstrapping "
					+ "from intensity seeds"
					+ ( w.windowCropBox != null ? " (crop-restricted)." : " (full frame)." ) );
			final String fallback;
			try
			{
				fallback = bootstrapColdStart( w.context, w.elephant, w.viewer, w.wStart, w.wEnd,
						w.allTps, w.nAnnotPerWindow, w.nSeedsPerTp, w.trackIntensityRatio, w.qualityThreshold, w.dedupFactor,
						w.minSize, w.nCrops, w.nEpochs, w.trainTimeoutMs, w.windowCropBox, w.coldStartCeiling );
			}
			catch ( final DetectionExplosionException ex )
			{
				if ( abandonWindowOnExplosion( w.context, w.elephant, w.viewer, w.wStart, w.wEnd,
						w.windowSpotsBefore, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs,
						w.trainTimeoutMs, ex ) )
					w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
				return Next.ABANDON;
			}
			if ( fallback != null )
				{
					w.stopMessage = fallback;
					return Next.STOP;
				}
			// Bootstrap succeeded: refresh metrics from the re-detected (still crop-restricted)
			// spots and use the new count as the recall baseline (the discarded noise count is
			// meaningless).
			w.metrics = computeQualityMetrics( w.context, w.allTps );
			w.pFrac = computeProblematicFraction( w.context, w.allTps, w.effectiveBorderMargin, w.maxAspectRatio );
			w.initialSpotsPerTp = w.metrics[ 0 ];
			w.context.log( String.format(
					"  Post-bootstrap detection (crop-scoped): spots/tp=%.1f (CV_temporal=%.2f), "
							+ "size=%.1f, border=%.0f%%", w.metrics[ 0 ], w.metrics[ 1 ], w.metrics[ 2 ],
					w.pFrac[ 0 ] * 100 ) );
		}

		// CV_size often reflects biological size heterogeneity; use only count and
		// temporal consistency as training gates (not size uniformity).
		if ( w.metrics[ 3 ] >= CV_SIZE_GOOD )
			w.context.log( String.format(
					"  NOTE: CV_size=%.2f (biological size variation — not a training trigger).",
					w.metrics[ 3 ] ) );

		// Step 2+3: Iterative training loop — retrain up to nTrainIterations times. EVERY iteration
		// is crop-restricted, rotating across windowCropBoxes (see TUNING_ROI_BATCH_COUNT) instead of
		// hammering the same single region — this loop exists purely to refresh self-training
		// annotations round after round; the single full-frame commit that actually feeds
		// linking/correction/validation happens ONCE, in the mandatory commit block right after this
		// loop (or inside the GT branch below, which commits directly since GT needs no crop-tuning
		// phase first). Absolute-count thresholds (coldStartCeiling, minSpotsPerTp, prevWindowBaseline)
		// do not apply to crop-scoped counts, so this loop does not early-exit or roll back on them —
		// it simply runs nTrainIterations rounds and lets the final commit's own checks decide.
		w.committedFullFrame = false;
		// Set when the committed full-frame result itself already reads as exploded (see the
		// self-training collapse guard right after the commit, below) — gates OUT the [5b/6]
		// refinement loop entirely (see there): enrich+retrain on an already-exploded set only risks
		// compounding it further before that loop's own per-round guard can catch it.
		w.commitAlreadyExploded = false;
		{
			for ( int trainIter = 1; trainIter <= w.nTrainIterations; trainIter++ )
			{
				if ( w.context.isCancelRequested() || Thread.currentThread().isInterrupted() )
					break;
				if ( goReachedOn( w.context, w.allTps ) )
				{
					w.context.log( "  [2/6] Review settled the model on these frames (it reproduces the reference cells on the "
							+ "whole frame, or its best round was kept) — no self-training over that model in this window." );
					break;
				}

				// GT-first training: when trusted pre-existing annotations exist in this window, train
				// the model on THEM rather than on its own detections. This is the human workflow
				// (annotate a few correct cells, then train) and avoids reinforcing over-detection.
				//
				// Repeated until the model SETTLES, not once. The labels do not change between rounds
				// — that was the argument for a single pass — but the model does, and one pass of a
				// few hundred GT spots does not undo a model that has been reinforced toward
				// seven hundred detections a frame. Each round retrains on the same GT and then
				// re-detects to see where the model has moved; the loop ends when the count stops
				// falling, which is what "trained on GT until it settled" has to mean if it is to
				// mean anything.
				if ( w.gtInWindow > 0 )
				{
					// GT positives alone (all tp, zero fp) never teach the model what background looks
					// like, so ELEPHANT keeps over-detecting out-of-focus Z-ghosts above/below real
					// cells. Drop the model's current whole-image detections outright — mining fp
					// negatives from THEM, by re-classifying the window's own ambiguous existing
					// detections as tp/fp, risked confidently mislabelling a genuinely dim-but-real cell
					// as background, since its fp candidates are by construction the least-confident
					// detections in the window — on a
					// dataset with real dim cells (e.g. photobleaching), that is indistinguishable from
					// true background in a small cropped patch. Negatives are synthesized instead, right
					// after GT is restored below (see synthesizeNearCellFpNegatives): offsetting a few
					// radii from an ALREADY-TRUSTED GT position into presumed-empty space is a far more
					// reliably-answerable question for the VLM than judging an ambiguous detection.
					w.context.log( String.format( "  [2/6] Training on %d trusted annotation spot(s) "
						+ "(existing GT) instead of self-detections...", w.gtInWindow ) );
					boolean trainedGt = false;
					double prevRaw = Double.NaN;
					for ( int gtRound = 1; gtRound <= GT_CONSOLIDATION_MAX_ROUNDS; gtRound++ )
					{
						if ( w.context.isCancelRequested() || Thread.currentThread().isInterrupted() )
							break;
						deleteAllSpotsAtTimepoints( w.context, w.allTps ); // drop the model's own detections
						final int restored = restoreSpots( w.context, w.trustedAnnotations, w.wStart, w.wEnd );
						if ( restored == 0 )
						{
							w.context.log( String.format(
									"  WARNING: none of the %d GT annotation(s) for this window ended up "
											+ "in the graph, so there was nothing to train on — re-detecting "
											+ "with the model unchanged.", w.gtInWindow ) );
							break;
						}
						if ( w.vlmBackend != null )
							requestSyntheticFpNegatives( w.context, w.wStart, w.wEnd, w.vlmBackend, w.vlmModel,
									restored );
						// UNPREPARED-TRAINING-OK: the detections were just deleted and replaced by
						// intensity seeds, so there is nothing of the model's own to gate.
						if ( !w.elephant.trainDetectionModel( w.allTps, w.allTps[ w.allTps.length / 2 ], w.nEpochs,
								w.nCrops, w.trainTimeoutMs ) )
						{
							w.context.log( "  WARNING: GT training did not complete (server not idle within "
									+ "timeout, or was aborted) — stopping GT rounds here." );
							break;
						}
						trainedGt = true;
						// Where the model sits now. Crop-restricted, because this is a progress reading
						// taken once per round and the full-frame commit below is what the window keeps.
						deleteAllSpotsAtTimepoints( w.context, w.allTps );
						try
						{
							predictWindow( w.context, w.viewer, w.wStart, w.wEnd, w.trackIntensityRatio,
									w.qualityThreshold, w.minSize, w.windowCropBox, w.coldStartCeiling );
						}
						catch ( final DetectionExplosionException ex )
						{
							w.context.log( "  GT round " + gtRound + ": the probe re-detect exploded — "
									+ "stopping GT rounds and committing what the next full-frame pass "
									+ "produces." );
							break;
						}
						final double raw = lastPredictRawSpotsPerTp( w.context );
						final boolean settled = !Double.isNaN( prevRaw )
								&& Math.abs( raw - prevRaw ) <= GT_SETTLED_CHANGE_FRACTION * prevRaw;
						w.context.log( String.format(
								"  GT round %d/%d: the model now detects %.0f raw spot(s)/tp on the tuning "
										+ "region%s.%s",
								gtRound, GT_CONSOLIDATION_MAX_ROUNDS, raw,
								Double.isNaN( prevRaw ) ? "" : String.format( " (was %.0f)", prevRaw ),
								settled ? " Settled — moving on." : "" ) );
						prevRaw = raw;
						if ( settled )
							break;
					}
					// Clear the GT-materialised spots and re-detect with the updated model for tracking.
					deleteAllSpotsAtTimepoints( w.context, w.allTps );
					w.context.log( "  [3/6] Re-detecting after GT training..." );
					try
					{
						w.grandTotalPredict += predictWindow( w.context, w.viewer, w.wStart, w.wEnd,
								w.trackIntensityRatio, w.qualityThreshold, w.minSize, w.coldStartCeiling );
					}
					catch ( final DetectionExplosionException ex )
					{
						if ( abandonWindowOnExplosion( w.context, w.elephant, w.viewer, w.wStart, w.wEnd,
								w.windowSpotsBefore, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs,
								w.trainTimeoutMs, ex ) )
							w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
						return Next.ABANDON;
					}
					cleanUpDetectedWindow( w );
					w.metrics = computeQualityMetrics( w.context, w.allTps );
					w.pFrac = computeProblematicFraction( w.context, w.allTps, w.effectiveBorderMargin, w.maxAspectRatio );
					w.context.log( String.format(
							"  Post-GT-training: spots/tp=%.1f (CV_temporal=%.2f), size=%.1f, border=%.0f%%",
							w.metrics[ 0 ], w.metrics[ 1 ], w.metrics[ 2 ], w.pFrac[ 0 ] * 100 ) );
					w.committedFullFrame = true; // this full-frame re-detect IS the window's commit
					break; // GT is fixed; further iterations would not change the labels
				}

				if ( trainIter == 1 )
				{
					final StringBuilder reason = new StringBuilder();
					if ( w.metrics[ 0 ] < w.minSpotsPerTp )
						reason.append( String.format( "spots/tp=%.1f<%.0f ", w.metrics[ 0 ], w.minSpotsPerTp ) );
					// Every other clause here asks whether there are too FEW detections, or whether
					// their shape is wrong. None asked whether there are too MANY, so a window holding
					// 692 spots/tp against a stated ceiling of 600 was announced as "Detection density
					// OK" — the first thing a reader of that log disbelieves, and the pipeline's own
					// invariant check called the same number an explosion four lines later.
					final double tooMany = ExpectedCellCount.statedCeiling(
							ExpectedCellCount.expectedAt( w.context, w.wStart ) );
					if ( tooMany > 0 && w.metrics[ 0 ] > tooMany )
						reason.append( String.format( "spots/tp=%.1f>%.0f ", w.metrics[ 0 ], tooMany ) );
					if ( w.metrics[ 1 ] >= CV_TEMPORAL_GOOD )
						reason.append( String.format( "CV_temporal=%.2f ", w.metrics[ 1 ] ) );
					if ( w.pFrac[ 0 ] > 0.3 )
						reason.append( String.format( "border=%.0f%% ", w.pFrac[ 0 ] * 100 ) );
					if ( w.pFrac[ 1 ] > 0.3 )
						reason.append( String.format( "elongated=%.0f%% ", w.pFrac[ 1 ] * 100 ) );
					if ( reason.length() == 0 )
						w.context.log( "  [2/6] Detection density OK but adapting once anyway "
								+ "(density is not a correctness guarantee) — annotating and training "
								+ "(iter 1/" + w.nTrainIterations + ")..." );
					else
						w.context.log( "  [2/6] Quality insufficient (" + reason.toString().trim()
								+ ") — annotating and training (iter 1/" + w.nTrainIterations + ")..." );
				}
				else
				{
					w.context.log( String.format(
							"  [2/6] Quality still insufficient — retraining (iter %d/%d)...",
							trainIter, w.nTrainIterations ) );
				}

				// Apply stricter intensity/quality filter
				int strictFiltered = 0;
				for ( final int t : w.allTps )
					strictFiltered += AutoCorrectOrchestrator.filterWeakSpots(
							w.context, t, w.minIntensityRatio, w.qualityThreshold );
				if ( strictFiltered > 0 )
					w.context.log( "  Strict filter removed " + strictFiltered + " additional spots." );

				// Exclude border and elongated spots from training annotations.
				// These are partial-cell detections — training on them teaches the model to produce
				// edge-clipped ellipsoids. Excluding them biases the model toward clean, centered fits.
				int borderExcluded = 0, elongatedExcluded = 0;
				for ( final int t : w.allTps )
				{
					borderExcluded += filterBorderSpots( w.context, t, w.effectiveBorderMargin );
					elongatedExcluded += filterElongatedSpots( w.context, t, w.maxAspectRatio );
				}
				if ( borderExcluded > 0 )
					w.context.log( "  Excluded " + borderExcluded
							+ " border spots from annotation (partial cells at image edge)." );
				if ( elongatedExcluded > 0 )
					w.context.log( "  Excluded " + elongatedExcluded
							+ " elongated spots from annotation (aspect ratio > " + w.maxAspectRatio + ")." );

				// Explicit fp negatives so background trains as a real negative (autonomous version of
				// the manual "Label TP/FP"). Skipping this entirely was tried once (gating fp-labelling
				// to HIGH-confidence-only profiles) but proved net-harmful in practice: without ANY
				// negative-example training, the refinement loop's later all-tp retrains have nothing
				// pushing back against over-detection, so it drifts into detecting background/noise more
				// and more across windows instead.
				//
				// Negatives are SYNTHESIZED near existing tp (see synthesizeNearCellFpNegatives) rather
				// than mined by re-classifying the window's own ambiguous EXISTING detections as tp/fp
				// (the previous design, now removed): that
				// approach's fp candidates are, by construction, the LOWEST-ranked detections in the
				// window — on a dataset with genuinely dim/low-contrast real cells (e.g. photobleaching
				// late in a movie), the dimmest real cells and true background are visually similar in a
				// small cropped patch, so the VLM/statistical profile confidently mislabelled real tp as
				// fp there. Offsetting a few radii from an ALREADY-TRUSTED tp position into presumed-empty
				// space is a far more reliably-answerable question for the VLM to confirm than "is this
				// ambiguous, low-confidence detection real or not" — it is not asking the VLM to judge
				// the hard case at all, only to confirm the deliberately-chosen background side of it.
				if ( w.vlmBackend != null )
					requestSyntheticFpNegatives( w.context, w.wStart, w.wEnd, w.vlmBackend, w.vlmModel,
							countCellsAtTimepoints( w.context, w.allTps ) );
				else
				{
					w.context.log( "  (No VLM backend available — skipping fp negative synthesis this round; "
							+ "training on tp-only annotations.)" );
				}

				final int overTaught = prepareAnnotationsForTraining( w.context, w.allTps );
				final double[] annotMetrics = computeQualityMetrics( w.context, w.allTps );
				w.context.log( String.format( "  Annotation: %.0f spots/tp in the graph, %.0f taught.",
						annotMetrics[ 0 ], annotMetrics[ 0 ] - overTaught / ( double ) w.allTps.length ) );

				// What the model produced before this round trains, so the round can be judged on its
				// own effect and undone if it diverges.
				final double preRoundSpotsPerTp = w.metrics[ 0 ];
				final double preRoundRawPerTp = lastPredictRawSpotsPerTp( w.context );
				boolean trained = false;
				// Training on a handful of positives does not recover a window, it finishes it off.
				// The gate above reads "spots/tp below the minimum" as a reason to train HARDER, which
				// is the wrong direction when the shortage IS the annotations: measured on a window
				// that reached this point with one spot per frame, each round trained on 1 tp against
				// 3-16 synthesized negatives and the detector fell 217 -> 156 -> 145 -> 103 spots/tp
				// across three of them. The model learns that almost everything is background,
				// because that is what it was shown.
				//
				// Not a failure of this window alone: the server holds ONE model, so a round trained
				// on one cell degrades every window after it too.
				if ( annotMetrics[ 0 ] > 0 && annotMetrics[ 0 ] < w.minSpotsPerTp )
				{
					w.context.log( String.format(
							"  REFUSING to train on %.1f spot(s)/tp (below the %.0f minimum): too few "
									+ "positives to teach anything, and the negatives would teach the "
									+ "model that this frame is empty. The model is left as it is and "
									+ "the window reports what it found. Fix the detection first — "
									+ "lower min_intensity_ratio, or annotate a few cells and rebuild "
									+ "the GT store — rather than running more iterations.",
							annotMetrics[ 0 ], w.minSpotsPerTp ) );
				}
				else if ( annotMetrics[ 0 ] > 0 )
				{
					w.elephant.snapshotDetectionModel( roundModelSnapshotFile() );
					trained = w.elephant.trainDetectionModel(
							w.allTps, w.allTps[ w.allTps.length / 2 ], w.nEpochs, w.nCrops, w.trainTimeoutMs );
					if ( !trained )
						w.context.log( "  WARNING: Training did not complete (server not idle "
								+ "within timeout, or was aborted) — re-detecting with current model." );
				}
				else
				{
					w.context.log( "  No annotation spots remaining — skipping training." );
				}

				// Clear all spots before re-detection
				deleteAllSpotsAtTimepoints( w.context, w.allTps );

				// Re-detect with the updated model — crop-restricted unless the user turned that off,
				// and then ROTATED across windowCropBoxes each iteration (not the same region every
				// round): this loop exists
				// purely to refresh self-training annotations round after round, and cycling the region
				// exposes the model to more of the frame's real variability before the single mandatory
				// full-frame commit after this loop (which is where the window's committed result
				// actually comes from).
				final double[] iterCropBox = w.cropTuning
						? w.windowCropBoxes.get( ( trainIter - 1 ) % w.windowCropBoxes.size() ) : null;
				w.context.log( w.cropTuning
						? String.format( "  [3/6] Crop-restricted re-detecting (iter %d/%d, region %d/%d)...",
								trainIter, w.nTrainIterations,
								( trainIter - 1 ) % w.windowCropBoxes.size() + 1, w.windowCropBoxes.size() )
						: String.format( "  [3/6] Full-frame re-detecting (iter %d/%d) — crop-tuning is "
								+ "off for this run.", trainIter, w.nTrainIterations ) );
				// One timepoint first, and only the rest of the window if that one is not already a
				// flood. A round that has started amplifying does so on EVERY timepoint — the round
				// that took Fluo-N2DL-HeLA_01 to 6.5x read 532, 534, 514, ... — so the tenth predict
				// tells the discard below nothing the first did not. Paying for all ten was not only
				// wasted time: each one drags the whole per-timepoint cleanup along with it, so a
				// model already condemned got to delete 524 spots, feed the flat-contrast statistics
				// and consume bright-spot audits, all on detections about to be thrown away.
				boolean roundFlooded = false;
				try
				{
					predictWindow( w.context, w.viewer, w.wStart, w.wStart, w.trackIntensityRatio, w.qualityThreshold,
							w.minSize, iterCropBox, w.coldStartCeiling );
					final double probeRawPerTp = lastPredictRawSpotsPerTp( w.context );
					roundFlooded = tuningRoundIsFlooding( trained, preRoundRawPerTp, probeRawPerTp );
					if ( roundFlooded )
						w.context.log( String.format(
								"  [3/6] Round %d is over-detecting at its FIRST timepoint (t=%d: %.0f vs "
										+ "%.0f spots/tp before this round) — stopping here instead of "
										+ "re-detecting the other %d timepoint(s) of a model about to be "
										+ "discarded.",
								trainIter, w.wStart, probeRawPerTp, preRoundRawPerTp, w.wEnd - w.wStart ) );
					else
						predictWindow( w.context, w.viewer, w.wStart + 1, w.wEnd, w.trackIntensityRatio,
								w.qualityThreshold, w.minSize, iterCropBox, w.coldStartCeiling );
				}
				catch ( final DetectionExplosionException ex )
				{
					if ( abandonWindowOnExplosion( w.context, w.elephant, w.viewer, w.wStart, w.wEnd,
							w.windowSpotsBefore, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs,
							w.trainTimeoutMs, ex ) )
						w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
					return Next.ABANDON;
				}
				if ( roundFlooded )
				{
					discardTuningRound( w.context, w.elephant, w.allTps, trainIter, preRoundRawPerTp,
							lastPredictRawSpotsPerTp( w.context ) );
					w.metrics = new double[] { preRoundSpotsPerTp, w.metrics[ 1 ], w.metrics[ 2 ], w.metrics[ 3 ] };
					break;
				}
				cleanUpDetectedWindow( w );
				w.metrics = computeQualityMetrics( w.context, w.allTps );
				w.pFrac = computeProblematicFraction( w.context, w.allTps, w.effectiveBorderMargin, w.maxAspectRatio );
				w.context.log( String.format(
						"  Post-training (iter %d) [crop-scoped, not full-frame density]: spots/tp=%.1f "
								+ "(CV_temporal=%.2f), size=%.1f (CV_size=%.2f), border=%.0f%%",
						trainIter, w.metrics[ 0 ], w.metrics[ 1 ], w.metrics[ 2 ], w.metrics[ 3 ], w.pFrac[ 0 ] * 100 ) );
				// Judged on RAW detections, not on what survives cleanup: the cleanup passes are not
				// applied uniformly across timepoints, so their output is not comparable round to
				// round. See SHARED_LAST_PREDICT_RAW_SPOTS_PER_TP for the run where that difference
				// discarded an improving model. Both raw figures are crop-scoped and measured the
				// same way, so their ratio is meaningful even though neither absolute value is.
				final double roundRawPerTp = lastPredictRawSpotsPerTp( w.context );
				// Measured over the timepoints the probe above did NOT cover, which is the whole window
				// minus its first. A round that passed the probe and floods later is still caught; the
				// one-timepoint difference in denominator is far below this window's own CV_temporal.
				if ( tuningRoundIsFlooding( trained, preRoundRawPerTp, roundRawPerTp ) )
				{
					discardTuningRound( w.context, w.elephant, w.allTps, trainIter, preRoundRawPerTp,
							roundRawPerTp );
					w.metrics = new double[] { preRoundSpotsPerTp, w.metrics[ 1 ], w.metrics[ 2 ], w.metrics[ 3 ] };
					break;
				}
			}
		}

		// Mandatory single full-frame commit: exactly once per window (skipped only when the GT
		// branch above already committed its own full-frame re-detect directly). This is the ONLY
		// full-frame predict in the whole per-window pipeline — everything above (test detection,
		// cold-start bootstrap, every training iteration) stayed crop-restricted on purpose.
		if ( !w.committedFullFrame )
		{
			// Armed HERE, not at the window start: everything before this is crop-restricted tuning
			// whose detections are created and wholesale-deleted several times over, so transitions
			// recorded there say nothing about what ends up in the result.
			BrightSpotAudit.arm( w.context, w.viewer, w.wStart, w.wEnd );
			w.context.log( "  [3/6] Committing full-frame detection (tuning phase complete)..." );
			deleteAllSpotsAtTimepoints( w.context, w.allTps );
			try
			{
				w.grandTotalPredict += predictWindow( w.context, w.viewer, w.wStart, w.wEnd, w.trackIntensityRatio,
						w.qualityThreshold, w.minSize, w.coldStartCeiling );
			}
			catch ( final DetectionExplosionException ex )
			{
				if ( abandonWindowOnExplosion( w.context, w.elephant, w.viewer, w.wStart, w.wEnd,
						w.windowSpotsBefore, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs,
						w.trainTimeoutMs, ex ) )
					w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
				return Next.ABANDON;
			}
			cleanUpDetectedWindow( w );
			BrightSpotAudit.observe( w.context, "[3/6] duplicate + malformed removal" );
			w.metrics = computeQualityMetrics( w.context, w.allTps );
			w.pFrac = computeProblematicFraction( w.context, w.allTps, w.effectiveBorderMargin, w.maxAspectRatio );
			w.context.log( String.format(
					"  Committed detection: spots/tp=%.1f (CV_temporal=%.2f), size=%.1f (CV_size=%.2f), "
							+ "border=%.0f%%", w.metrics[ 0 ], w.metrics[ 1 ], w.metrics[ 2 ], w.metrics[ 3 ],
					w.pFrac[ 0 ] * 100 ) );

			// Self-training collapse guard on the REAL committed result — see the identical checks
			// in the [5b/6] refinement loop for why all three signals matter (absolute count,
			// zero-intensity fraction, relative-to-previous-window). Unlike before, there is no
			// crop-scoped "pre-iteration state" to roll back to (the whole tuning phase was
			// crop-restricted) — if the committed result itself still explodes, that means the
			// crop-tuning phase did not transfer to the full frame; this is surfaced as a loud
			// warning rather than a silent rollback, since there is nothing safe left to roll back to.
			final double commitZeroFrac = computeZeroIntensityFraction( w.context, w.allTps );
			final boolean commitExceedsPrevWindow = w.prevWindowBaseline > 0
					&& w.metrics[ 0 ] > w.prevWindowBaseline * PREV_WINDOW_EXPLOSION_RATIO;
			// The absolute bound stands down wherever a measured prior exists — see
			// windowExplosionCeiling. With one, the prev-window ratio above IS the explosion test
			// and this is only the backstop that keeps the window-on-window calibration from
			// ratcheting a slow runaway out of reach. Without one it is unchanged.
			final double commitBound =
					ExpectedCellCount.ceilingOver( w.context, w.wStart, w.wEnd, 0.0, w.coldStartCeiling );
			final boolean commitExceedsAbsolute = w.prevWindowBaseline > 0
					? w.metrics[ 0 ] > commitBound * MAX_CEILING_GROWTH_MULTIPLE
					: isColdStartRegime( w.metrics[ 0 ], commitBound );
			if ( commitExceedsAbsolute
					|| commitZeroFrac > ZERO_INTENSITY_FLOOD_FRACTION
					|| commitExceedsPrevWindow )
			{
				w.context.log( String.format(
						"  WARNING: the committed full-frame detection exploded to spots/tp=%.0f "
								+ "(ceiling %.0f%s), %.0f%% zero-contrast — the crop-tuning phase did NOT "
								+ "transfer to the full frame. Proceeding anyway (no safe crop-scoped state "
								+ "to roll back to) — re-run adaptive_track on this window if this persists.",
						w.metrics[ 0 ], w.coldStartCeiling,
						w.prevWindowBaseline > 0
								? String.format( "; previous window's spots/tp=%.1f, ratio=%.1fx vs "
										+ "explosion threshold %.1fx", w.prevWindowBaseline,
										w.metrics[ 0 ] / w.prevWindowBaseline, PREV_WINDOW_EXPLOSION_RATIO )
								: "",
						commitZeroFrac * 100 ) );
				w.explodedWindows++;
				w.commitAlreadyExploded = true;
			}
			else if ( w.metrics[ 0 ] < w.minSpotsPerTp )
			{
				w.context.log( String.format(
						"  WARNING: committed detection density looks low (spots/tp=%.1f < %.0f) — "
								+ "consider re-running adaptive_track on t=%d..%d with higher n_iterations.",
						w.metrics[ 0 ], w.minSpotsPerTp, w.wStart, w.wEnd ) );
			}
		}
		w.invOutDetect = PipelineInvariants.checkAndLog( w.context, "[3/6] commit detection", w.inv,
				w.wStart, w.wEnd, w.detectInvariants );
		w.inv = w.invOutDetect.snapshot;
		if ( w.invOutDetect.hasError() )
		{
			// Over a ceiling nobody stated, the user is asked first: the guard cannot tell a flood
			// from a cell-count guess that was too low, and the user can. See GuessedCeiling.
			if ( w.invOutDetect.onlyCeilingErrors && keepOverGuessedCeiling( w.context, w.ceilingFromUser,
					w.wStart, w.wEnd, w.invariantCeiling * w.invariantCeilingRatio ) )
			{
				// The rest of this window is checked against the user's count, not the guess.
				final double userCeiling = ExpectedCellCount.ceilingOver( w.context, w.wStart, w.wEnd,
						w.prevWindowBaseline, w.prevWindowBaseline > 0 ? w.prevWindowBaseline
								: ( ( Number ) w.context.getSharedData( SHARED_COLD_START_CEILING_OVERRIDE ) )
										.doubleValue() );
				w.correctInvariants.expectedCeiling( userCeiling );
				w.refineInvariants.expectedCeiling( userCeiling );
			}
			else
			{
				if ( abandonWindowOnInvariant( w.context, w.elephant, w.viewer, w.wStart, w.wEnd, w.lastChecked,
						w.detectionChecked, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs, w.trainTimeoutMs,
						"[3/6] commit detection", w.invOutDetect.firstError ) )
					w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
				return Next.ABANDON;
			}
		}
		// From here on the window has a detection that passed the ceiling and floor checks. Anything
		// later that fails is put back to this, not to nothing.
		w.lastChecked = snapshotSpots( w.context, w.wStart, w.wEnd );
		w.detectionChecked = true;
		return Next.CONTINUE;
	}

	/**
	 * [4/6]: ELEPHANT nearest-neighbour linking, and its check. Built-in LINK stage.
	 */
	private static Next linkStage( final WindowRun w )
	{
		// Step 4: Tracking — link backward within window
		w.context.log( "  [4/6] Linking..." );
		w.linkOk = linkWindow( w.context, w.viewer, w.wStart, w.wEnd );
		w.grandTotalLink += w.linkOk;
		BrightSpotAudit.observe( w.context, "[4/6] link" );
		w.invOutLink = PipelineInvariants.checkAndLog( w.context, "[4/6] link", w.inv,
				w.wStart, w.wEnd, w.linkInvariants );
		w.inv = w.invOutLink.snapshot;
		if ( w.invOutLink.hasError() )
		{
			if ( abandonWindowOnInvariant( w.context, w.elephant, w.viewer, w.wStart, w.wEnd, w.lastChecked,
					w.detectionChecked, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs, w.trainTimeoutMs,
					"[4/6] link", w.invOutLink.firstError ) )
				w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
			else if ( w.detectionChecked )
				w.windowsKeptDetection++; // not retried, so what it kept is what the user is left with
			return Next.ABANDON;
		}
		// Checked and kept: this is what a later phase is put back to if it fails.
		w.lastChecked = snapshotSpots( w.context, w.wStart, w.wEnd );
		return Next.CONTINUE;
	}

	/**
	 * The per-frame displacement cap, then [5/6]: per-timepoint correction, and its check. Built-in
	 * CORRECT_DETECTIONS stage.
	 */
	private static Next correctStage( final WindowRun w )
	{
		// The per-frame displacement cap, re-measured now that this window HAS links. The pre-run
		// estimate can only measure timepoints that already held tracks, so on a fresh project it is
		// 0 and stays 0 — while every consumer of it (pruneLongLinks, both linkOrphanStarts calls,
		// and [5c/6]'s reconnection) then runs with no absolute bound at all. Measured over
		// everything tracked so far rather than this window alone, so it is not a statistic about
		// the links it is used to judge.
		if ( AutoCorrectOrchestrator.learnedMaxDisplacement( w.corrSettings ) <= 0.0 )
		{
			final double windowDisp =
					AutoCorrectOrchestrator.estimateMaxDisplacement( w.context, w.corrSettings, 0, w.wEnd );
			if ( windowDisp > 0.0 && windowDisp != w.corrSettings.getCorrectMaxDisplacement() )
			{
				w.corrSettings.setCorrectMaxDisplacement( windowDisp );
				w.context.log( String.format( "  Per-frame displacement cap now %.1f µm "
						+ "(≈4x the median consecutive-link length over t=0..%d).", windowDisp, w.wEnd ) );
			}
		}

		// Step 5: Evaluate and correct
		w.context.log( "  [5/6] Evaluating and correcting..." );
		if ( w.correctPerWindow )
		{
			for ( int t = w.wStart; t <= w.wEnd; t++ )
			{
				AutoCorrectOrchestrator.correctTimepoint( w.context, t, w.numTimepoints, w.corrSettings,
						w.wStart );
				w.grandTotalCorrected++;
			}
			BrightSpotAudit.observe( w.context, "[5/6] autocorrect" );
			final PipelineInvariants.Outcome invOutCorrect =
					PipelineInvariants.checkAndLog( w.context, "[5/6] autocorrect", w.inv,
					w.wStart, w.wEnd, w.correctInvariants );
			w.inv = invOutCorrect.snapshot;
			if ( invOutCorrect.hasError() )
			{
				if ( abandonWindowOnInvariant( w.context, w.elephant, w.viewer, w.wStart, w.wEnd, w.lastChecked,
						w.detectionChecked, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs, w.trainTimeoutMs,
						"[5/6] autocorrect", invOutCorrect.firstError ) )
					w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
				else if ( w.detectionChecked )
					w.windowsKeptDetection++; // not retried, so what it kept is what the user is left with
				return Next.ABANDON;
			}
			// Checked and kept: this is what a later phase is put back to if it fails.
			w.lastChecked = snapshotSpots( w.context, w.wStart, w.wEnd );
		}
		return Next.CONTINUE;
	}

	/**
	 * [5b/6]: enrich, retrain, re-detect, re-link, re-correct while recall grows, and its check. Built-in
	 * TRAIN stage.
	 */
	private static Next refineStage( final WindowRun w )
	{
		// Step 5b: ITERATIVE provisional-annotation refinement — the "interpolate missed cells →
		// train on them → re-detect" loop that bootstraps recall from a randomly-initialised model.
		// Each round: enrich the current spots (recover missed cells via gap-fill / predecessor
		// recovery), retrain on that enriched "provisional ground truth", then re-detect / re-link /
		// re-correct. Repeat while the detection count keeps growing (recall climbing), capped at
		// refineIterations rounds.
		// Skip refinement when this window is taught by trusted GT: the "enrich → retrain on the
		// enriched detections" loop is SELF-TRAINING on the model's own (possibly false-positive)
		// detections, which amplifies over-detection and undoes the clean GT-trained result. Detection
		// still covers the whole image via the GT-trained model; refinement is only a recall booster
		// for the GT-free case. (Also frees the ELEPHANT server so later windows' GT training can run.)
		if ( w.externalDetector != null )
		{
			w.context.log( "  [5b/6] Skipping refinement -- detection is " + w.externalDetector.getId()
					+ ", a pretrained model used as it is; there is no ELEPHANT model to retrain." );
		}
		else if ( goReachedOn( w.context, w.allTps ) )
		{
			w.context.log( "  [5b/6] Skipping refinement — review settled the model on these frames; self-training on "
					+ "the model's own detections could only undo it." );
		}
		else if ( w.gtInWindow > 0 )
		{
			w.context.log( "  [5b/6] Skipping refinement — window trained on trusted GT; self-training "
					+ "on detections would reinflate false positives. Detection remains whole-image." );
		}
		else if ( w.commitAlreadyExploded )
		{
			w.context.log( "  [5b/6] Skipping refinement — the committed full-frame detection already "
					+ "exploded above (see the WARNING above); enrich+retrain on an already-exploded set "
					+ "would only compound it further. Detection remains whatever the commit produced — "
					+ "re-run adaptive_track on this window with different parameters if this persists." );
		}
		else if ( w.refinePass && w.correctPerWindow )
		{
			double prevCount = computeQualityMetrics( w.context, w.allTps )[ 0 ];
			for ( int rIter = 1; rIter <= w.refineIterations; rIter++ )
			{
				if ( w.context.isCancelRequested() || Thread.currentThread().isInterrupted() || prevCount <= 0 )
					break;

				// Snapshot the pre-round state so a self-training collapse (see the cold-start-regime
				// check below) can be rolled back instead of silently accepted as this window's result.
				final Map< Integer, List< double[] > > preRoundSnap = snapshotSpots( w.context, w.wStart, w.wEnd );

				// Enrich: correction pass with gap-fill / predecessor recovery enabled.
				w.corrSettings.setCorrectFillGaps( true );
				for ( int t = w.wStart; t <= w.wEnd; t++ )
					AutoCorrectOrchestrator.correctTimepoint( w.context, t, w.numTimepoints, w.corrSettings,
						w.wStart );
				w.corrSettings.setCorrectFillGaps( false );

				// This round trains on the enriched set with EVERYTHING labelled tp (see the class docs
				// on why fp-labelling is skipped here) — so anything malformed/weak that survived (or
				// was newly introduced by gap-fill/predecessor recovery above) would otherwise be
				// reinforced into the model as a positive example. Clean it out first, mirroring the
				// same strict/shape filters the initial training loop ([2/6]) already applies before
				// its own training call.
				int enrichFiltered = 0;
				for ( final int t : w.allTps )
					enrichFiltered += AutoCorrectOrchestrator.filterWeakSpots(
							w.context, t, w.minIntensityRatio, w.qualityThreshold );
				final int[] enrichMalformed =
						filterMalformedSpots( w.context, w.allTps, w.effectiveBorderMargin, w.maxAspectRatio );
				if ( enrichFiltered + enrichMalformed[ 0 ] + enrichMalformed[ 1 ] > 0 )
					w.context.log( String.format(
							"  [5b/6] Removed %d weak + %d border + %d elongated spot(s) from the "
									+ "enriched set before training all-tp.",
							enrichFiltered, enrichMalformed[ 0 ], enrichMalformed[ 1 ] ) );

				// "Everything tp" still means an over-segmented pair (two fragments of one blob) would
				// both be reinforced as positives. Delete the lower-scoring one of any such pair — no
				// VLM needed, purely the geometric+intensity valley test already trusted elsewhere in
				// this pipeline; never tagged fp (an actual detection result must not be turned into a
				// declared background negative).
				final int resolvedDup =
						AutoCorrectOrchestrator.resolveOverlappingDuplicates( w.context, w.wStart, w.wEnd );
				if ( resolvedDup > 0 )
					w.context.log( String.format(
							"  [5b/6] Deleted %d over-segmented duplicate(s) before training.",
							resolvedDup ) );

				final double enriched = computeQualityMetrics( w.context, w.allTps )[ 0 ];
				w.context.log( String.format(
						"  [5b/6] Refinement round %d/%d: retraining on %.1f spots/tp "
								+ "(enriched from %.1f)...", rIter, w.refineIterations, enriched, prevCount ) );
				// Negatives here too. This path used to train on the enriched detections alone: the
				// synthetic fp spots made during tuning are wiped by the re-detection that precedes
				// refinement, so every refinement round went out with fp=0 on every frame and the
				// background class weight back at its configured 1. One to three rounds per window
				// were therefore taught only what a cell looks like and never what one is not, which
				// is the training regime most likely to push a detector outward.
				if ( w.vlmBackend != null )
					requestSyntheticFpNegatives( w.context, w.wStart, w.wEnd, w.vlmBackend, w.vlmModel,
							countCellsAtTimepoints( w.context, w.allTps ) );
				// The same preparation [2/6] applies. Refinement retrains on detections that have
				// already been ENRICHED by gap-filling and predecessor recovery, so it needs this
				// more than [2/6] does, not less — and it had none of it.
				prepareAnnotationsForTraining( w.context, w.allTps );
				boolean refined = false;
				if ( enriched > 0 )
					refined = w.elephant.trainDetectionModel(
							w.allTps, w.allTps[ w.allTps.length / 2 ], w.nEpochs, w.nCrops, w.trainTimeoutMs );
				if ( !refined )
				{
					w.context.log( "  [5b/6] Refinement training did not complete — stopping refinement." );
					break;
				}

				// Re-detect with the refined model, then re-link + re-correct.
				deleteAllSpotsAtTimepoints( w.context, w.allTps );
				try
				{
					w.grandTotalPredict += predictWindow(
							w.context, w.viewer, w.wStart, w.wEnd, w.trackIntensityRatio, w.qualityThreshold, w.minSize,
							w.coldStartCeiling );
				}
				catch ( final DetectionExplosionException ex )
				{
					// Unlike the other predictWindow call sites in this method, a genuine rollback target
					// exists here (preRoundSnap, from just before this round's enrich+retrain) — restore it
					// instead of abandoning the whole window's earlier, perfectly good committed result.
					// Same recovery as the isColdStartRegime collapse guard below, just triggered by the
					// hard per-timepoint cap instead of the spots/tp-average check.
					w.context.log( String.format(
							"  [5b/6] WARNING: refinement round %d hit a detection explosion (%s) — rolling "
									+ "back to the pre-round detection and stopping refinement for this window.",
							rIter, ex.getMessage() ) );
					deleteAllSpotsAtTimepoints( w.context, w.allTps );
					restoreSpots( w.context, preRoundSnap, w.wStart, w.wEnd );
					w.linkOk = 0;
					for ( int t = w.wEnd; t >= w.wStart; t-- )
					{
						if ( w.context.isCancelRequested() || Thread.currentThread().isInterrupted() ) break;
						if ( !setTimepoint( w.viewer, t, DEFAULT_LOAD_DELAY_MS ) ) break;
						final ElephantIntegration e = new ElephantIntegration( w.context );
						if ( !e.init() ) continue;
						if ( e.linkSpots() ) w.linkOk++;
					}
					w.grandTotalLink += w.linkOk;
					for ( int t = w.wStart; t <= w.wEnd; t++ )
					{
						AutoCorrectOrchestrator.correctTimepoint( w.context, t, w.numTimepoints, w.corrSettings,
						w.wStart );
						w.grandTotalCorrected++;
					}
					w.explodedWindows++;
					break;
				}
				{
					final int resolvedDupRefine =
							AutoCorrectOrchestrator.resolveOverlappingDuplicates( w.context, w.wStart, w.wEnd );
					if ( resolvedDupRefine > 0 )
						w.context.log( "  [5b/6] Deleted " + resolvedDupRefine
								+ " over-segmented duplicate(s)." );
				}
				if ( w.dedupFactor > 0.0 )
					deduplicateSpots( w.context, w.allTps, w.dedupFactor );
				{
					final int[] malformed =
							filterMalformedSpots( w.context, w.allTps, w.effectiveBorderMargin, w.maxAspectRatio );
					if ( malformed[ 0 ] + malformed[ 1 ] > 0 )
						w.context.log( String.format(
								"  [5b/6] Deleted %d border + %d elongated malformed spot(s).",
								malformed[ 0 ], malformed[ 1 ] ) );
				}
				w.linkOk = 0;
				for ( int t = w.wEnd; t >= w.wStart; t-- )
				{
					if ( w.context.isCancelRequested() || Thread.currentThread().isInterrupted() ) break;
					if ( !setTimepoint( w.viewer, t, DEFAULT_LOAD_DELAY_MS ) ) break;
					final ElephantIntegration e = new ElephantIntegration( w.context );
					if ( !e.init() ) continue;
					if ( e.linkSpots() ) w.linkOk++;
				}
				w.grandTotalLink += w.linkOk;
				for ( int t = w.wStart; t <= w.wEnd; t++ )
				{
					AutoCorrectOrchestrator.correctTimepoint( w.context, t, w.numTimepoints, w.corrSettings,
						w.wStart );
					w.grandTotalCorrected++;
				}

				final double post = computeQualityMetrics( w.context, w.allTps )[ 0 ];

				// Self-training collapse guard: retraining on this round's "enriched" (self-labelled)
				// detections can destabilise the model into flooding the image with false positives —
				// the same failure mode isColdStartRegime() catches at bootstrap, just re-triggered
				// mid-refinement. A jump into cold-start-flood territory is never a real recall
				// improvement, so roll back to the pre-round detection and stop refining this window
				// rather than silently keep an exploded result (and, worse, feed it into the next
				// enrich → train round, or into the next window's starting model). Also checked
				// regardless of raw count: most spots sitting on literal zero image intensity is an
				// unambiguous "not real cells" signal even when the count/size/shape looks plausible
				// (e.g. a collapsed model flooding a regular GRID of empty positions).
				final double postZeroFrac = computeZeroIntensityFraction( w.context, w.allTps );
				final boolean exceedsPrevWindow = w.prevWindowBaseline > 0
						&& post > w.prevWindowBaseline * PREV_WINDOW_EXPLOSION_RATIO;
				// The absolute bound stands down wherever a measured prior exists — see
				// windowExplosionCeiling. With one, the prev-window ratio above IS the explosion test
				// and this is only the backstop that keeps the window-on-window calibration from
				// ratcheting a slow runaway out of reach. Without one it is unchanged.
				final double refineBound =
						ExpectedCellCount.ceilingOver( w.context, w.wStart, w.wEnd, 0.0, w.coldStartCeiling );
				final boolean exceedsAbsolute = w.prevWindowBaseline > 0
						? post > refineBound * MAX_CEILING_GROWTH_MULTIPLE
						: isColdStartRegime( post, refineBound );
				if ( exceedsAbsolute
						|| postZeroFrac > ZERO_INTENSITY_FLOOD_FRACTION
						|| exceedsPrevWindow )
				{
					w.context.log( String.format(
							"  [5b/6] WARNING: refinement round %d exploded to spots/tp=%.0f (ceiling "
									+ "%.0f%s), %.0f%% of spots sit on literal zero image intensity (flood "
									+ "threshold %.0f%%) — self-training on this round's enriched "
									+ "detections destabilised the model into over-detection. Rolling "
									+ "back to the pre-round detection and stopping refinement for this "
									+ "window (re-run with lower refine_iterations/n_epochs, or "
									+ "refine_pass=false, if this recurs).",
							rIter, post, w.coldStartCeiling,
							w.prevWindowBaseline > 0
									? String.format( "; previous window's spots/tp=%.1f, ratio=%.1fx "
											+ "vs explosion threshold %.1fx", w.prevWindowBaseline,
											post / w.prevWindowBaseline, PREV_WINDOW_EXPLOSION_RATIO )
									: "",
							postZeroFrac * 100, ZERO_INTENSITY_FLOOD_FRACTION * 100 ) );
					deleteAllSpotsAtTimepoints( w.context, w.allTps );
					restoreSpots( w.context, preRoundSnap, w.wStart, w.wEnd );
					w.linkOk = 0;
					for ( int t = w.wEnd; t >= w.wStart; t-- )
					{
						if ( w.context.isCancelRequested() || Thread.currentThread().isInterrupted() ) break;
						if ( !setTimepoint( w.viewer, t, DEFAULT_LOAD_DELAY_MS ) ) break;
						final ElephantIntegration e = new ElephantIntegration( w.context );
						if ( !e.init() ) continue;
						if ( e.linkSpots() ) w.linkOk++;
					}
					w.grandTotalLink += w.linkOk;
					for ( int t = w.wStart; t <= w.wEnd; t++ )
					{
						AutoCorrectOrchestrator.correctTimepoint( w.context, t, w.numTimepoints, w.corrSettings,
						w.wStart );
						w.grandTotalCorrected++;
					}
					w.explodedWindows++;
					break;
				}

				final double comp = w.completenessTarget > 0
						? computeDetectionCompleteness( w.context, w.viewer, w.wStart, w.wEnd ) : 1.0;
				w.context.log( String.format(
						"  [5b/6] Round %d: spots/tp %.1f → %.1f, completeness=%.0f%%.",
						rIter, prevCount, post, comp * 100 ) );
				// Already plausible relative to the PREVIOUS window's own spots/tp: real cell counts
				// rarely swing far across one window_size worth of timepoints, so there is little to
				// gain — and real waste/collapse risk — in continuing to chase an absolute
				// completeness_target once the count is this close to recent history (see
				// PREV_WINDOW_PLAUSIBLE_MAX). This can stop refinement even when the image-derived
				// completeness estimate alone would keep pushing for more.
				if ( w.prevWindowBaseline > 0 && post <= w.prevWindowBaseline * PREV_WINDOW_PLAUSIBLE_MAX )
				{
					w.context.log( String.format(
							"  [5b/6] spots/tp=%.1f is within %.0f%% of the previous window's %.1f — "
									+ "already plausible relative to recent history, refinement complete.",
							post, PREV_WINDOW_PLAUSIBLE_MAX * 100, w.prevWindowBaseline ) );
					break;
				}
				final boolean grew = post > prevCount * ( 1.0 + REFINE_MIN_GROWTH );
				if ( w.completenessTarget > 0 )
				{
					// Keep refining until detection is COMPLETE (recall target), not merely
					// until the count stops changing — this is what stops the pipeline from
					// proceeding on insufficient detection.
					if ( comp >= w.completenessTarget )
					{
						w.context.log( "  [5b/6] Completeness target reached — refinement complete." );
						break;
					}
					if ( !grew )
					{
						w.context.log( String.format(
								"  [5b/6] Detection plateaued below completeness target "
										+ "(%.0f%% < %.0f%%) — stopping (may need manual annotation / "
										+ "parameter change).", comp * 100, w.completenessTarget * 100 ) );
						break;
					}
				}
				else if ( !grew )
				{
					w.context.log( "  [5b/6] Detection count converged — refinement complete." );
					break;
				}
				prevCount = post;
			}
		}

		BrightSpotAudit.observe( w.context, "[5b/6] refinement" );
		w.invOutRefine = PipelineInvariants.checkAndLog( w.context, "[5b/6] refinement", w.inv,
				w.wStart, w.wEnd, w.refineInvariants );
		w.inv = w.invOutRefine.snapshot;
		if ( w.invOutRefine.hasError() )
		{
			if ( abandonWindowOnInvariant( w.context, w.elephant, w.viewer, w.wStart, w.wEnd, w.lastChecked,
					w.detectionChecked, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs, w.trainTimeoutMs,
					"[5b/6] refinement", w.invOutRefine.firstError ) )
				w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
			else if ( w.detectionChecked )
				w.windowsKeptDetection++; // not retried, so what it kept is what the user is left with
			return Next.ABANDON;
		}
		// Checked and kept: this is what a later phase is put back to if it fails.
		w.lastChecked = snapshotSpots( w.context, w.wStart, w.wEnd );
		return Next.CONTINUE;
	}

	/**
	 * [5c/6]: topology consistency over this window and one before it, and its check. Built-in
	 * CORRECT_LINEAGE stage.
	 */
	private static Next consistencyStage( final WindowRun w )
	{
		// Step 5c: Topology consistency near the frontier, so the running result stays de-branched and
		// de-fragmented every batch — not only at the very end. Scoped to the recently-processed region
		// (this window plus one window of margin): earlier timepoints were already reconciled by a
		// previous window's pass and are left untouched, so this doesn't re-scan the whole
		// tracked-so-far history every single window (that cost would grow with every window processed).
		// Frontier-protected: a daughter that has only just appeared at t=wEnd is never pruned.
		if ( w.consistencyPerBatch )
		{
			final int consistencyScanStart = Math.max( 0, w.wStart - w.effectiveWindow );
			w.context.log( String.format( "  [5c/6] Topology consistency check over t=%d..%d...",
					consistencyScanStart, w.wEnd ) );
			// BUG FIXED: this was passing the movie-WIDE numTimepoints (e.g. 74) as the scan upper
			// bound instead of this window's own wEnd+1, silently defeating the "scoped to the
			// recently-processed region" comment above — every window's pass actually re-scanned (and
			// re-deleted via deleteUnstableSpots, which candidate-filters by this exact range) the
			// ENTIRE tracked-so-far history from t=0 forward, not just this window ± margin. Confirmed
			// from a real run: window 1 alone showed "unstable-deleted=924" out of ~2200 committed
			// spots — with the bug, every later window re-evaluated (and could re-delete) that same
			// early region again, capping the eventual total regardless of how permissive detection
			// itself was made (min_intensity_ratio down to 0.08 made no difference to the final count).
			final String consistency = AutoCorrectOrchestrator.runTopologyConsistency(
					w.context, consistencyScanStart, w.wEnd + 1, w.corrSettings );
			w.context.log( "  [5c/6] Consistency: " + consistency );
			// Checked against consistencyScanStart..wEnd — the range this pass DECLARES it is scoped
			// to — not against the window, so the bug fixed above (silently rescanning and re-deleting
			// the entire tracked-so-far history) cannot reappear unnoticed if this call is edited again.
			BrightSpotAudit.observe( w.context, "[5c/6] topology consistency" );
			final PipelineInvariants.Outcome invOutTopology =
					PipelineInvariants.checkAndLog( w.context, "[5c/6] topology consistency", w.inv,
					consistencyScanStart, w.wEnd, w.correctInvariants );
			w.inv = invOutTopology.snapshot;
			if ( invOutTopology.hasError() )
			{
				if ( abandonWindowOnInvariant( w.context, w.elephant, w.viewer, w.wStart, w.wEnd, w.lastChecked,
						w.detectionChecked, w.nAnnotPerWindow, w.nSeedsPerTp, w.nCrops, w.nEpochs, w.trainTimeoutMs,
						"[5c/6] topology consistency", invOutTopology.firstError ) )
					w.wStart -= w.effectiveWindow; // retry this window with the rebuilt detector
				else if ( w.detectionChecked )
					w.windowsKeptDetection++; // not retried, so what it kept is what the user is left with
				return Next.ABANDON;
			}
		}
		return Next.CONTINUE;
	}


	/** The state the window loop of {@link #runAdaptiveTrack} shares across its stages. */
	private static final class WindowRun
	{
		/** Set by a stage that ends the run; what runAdaptiveTrack returns. */
		String stopMessage;

		/** The pretrained detector chosen in the trial; null = ELEPHANT's adaptive tracking. */
		org.mastodon.mamut.automahout.tool.remote.FrameDetector externalDetector;

		/** The stages each window runs. */
		List< WindowStage > plan;

		org.mastodon.mamut.automahout.tool.ToolContext context;

		org.mastodon.mamut.automahout.elephant.ElephantIntegration elephant;

		bdv.viewer.ViewerPanel viewer;

		int startTp;

		int endTp;

		int numTimepoints;

		int windowSize;

		int nAnnotPerWindow;

		int nTrainIterations;

		double minIntensityRatio;

		double trackIntensityRatio;

		double qualityThreshold;

		double minSpotsPerTp;

		boolean correctPerWindow;

		boolean consistencyPerBatch;

		double dedupFactor;

		double borderMargin;

		double maxAspectRatio;

		long trainTimeoutMs;

		double requestedColdStartCeiling;

		int nSeedsPerTp;

		double minSize;

		int nCrops;

		int nEpochs;

		boolean refinePass;

		int refineIterations;

		double completenessTarget;

		org.mastodon.mamut.automahout.plugin.AutomahoutSettings corrSettings;

		int effectiveWindow;

		int totalWindows;

		java.lang.String cleanupsOff;

		double[] physDims;

		double minDim;

		double effectiveBorderMargin;

		java.lang.String acceptanceDatasetName;

		int samplingLevel;

		java.lang.String samplingNote;

		java.lang.String autoAcceptanceProfilePath;

		org.mastodon.mamut.automahout.eval.DetectionAcceptanceProfile acceptanceProfile;

		boolean gtStoreUsable;

		java.util.Map<java.lang.Integer,java.util.List<double[]>> trustedAnnotations;

		int trustedTotal;

		boolean trustedFromGtStore;

		org.mastodon.mamut.automahout.vision.VlmBackend vlmFpBackend;

		java.lang.String vlmFpModel;

		org.mastodon.mamut.automahout.vision.VlmBackend vlmBackend;

		java.lang.String vlmModel;

		int completedWindows;

		java.util.List<double[]> cachedRoiCenters;

		int cachedRoiCentersTp;

		int coveredTimepoints;

		int windowsKeptDetection;

		int grandTotalPredict;

		int grandTotalLink;

		int grandTotalCorrected;

		int explodedWindows;

		double worstContinuity;

		double sumContinuity;

		int nWindowsWithInterior;

		int totalIsolated;

		double worstRetention;

		boolean anyRetentionMeasured;

		double worstCompleteness;

		int lastWindowEnd;

		int lastPlausibleWindowEnd;

		double meanContinuity;

		int[] gt;

		java.lang.String auditSummary;

		int requestedTimepoints;

		boolean fullCoverage;

		int unfinishedWindows;

		java.lang.String coverageReport;

		java.lang.String contReport;

		boolean recallSuspect;

		java.lang.String recallReport;

		boolean tracksContinuous;

		java.lang.String completenessReport;

		java.lang.String explodedReport;

		int[] topo;

		int firstTpSpots;

		int lastTpSpots;

		java.lang.String divisionReport;

		int wStart;

		int wEnd;

		int windowLen;

		int[] allTps;

		java.lang.Object ceilingOverride;

		boolean ceilingFromUser;

		double coldStartCeiling;

		java.util.Map<java.lang.String,long[]> preambleMark;

		org.mastodon.mamut.automahout.eval.PipelineInvariants.Snapshot inv;

		int[][] editsMark;

		long snapshotStart;

		long spotSnapshotStart;

		java.util.Map<java.lang.Integer,java.util.List<double[]>> windowSpotsBefore;

		java.util.Map<java.lang.Integer,java.util.List<double[]>> lastChecked;

		boolean detectionChecked;

		int explodedWindowsAtWindowStart;

		org.mastodon.mamut.automahout.eval.PipelineInvariants.Limits linkInvariants;

		int priorSpots;

		double clusterRadiusForWindow;

		long roiStart;

		java.util.List<double[]> windowRoiCenters;

		boolean cropTuning;

		java.util.List<double[]> windowCropBoxes;

		double[] windowCropBox;

		int gtInWindow;

		double[] metrics;

		double[] pFrac;

		boolean coldStart;

		double initialSpotsPerTp;

		double prevWindowBaseline;

		/** Whether prevWindowBaseline was measured from accepted frames (growth or pre-start history), not a guess. */
		boolean priorMeasured;

		double invariantCeiling;

		double invariantCeilingRatio;

		int invariantFloor;

		org.mastodon.mamut.automahout.eval.PipelineInvariants.Limits detectInvariants;

		org.mastodon.mamut.automahout.eval.PipelineInvariants.Limits correctInvariants;

		org.mastodon.mamut.automahout.eval.PipelineInvariants.Limits refineInvariants;

		boolean committedFullFrame;

		boolean commitAlreadyExploded;

		org.mastodon.mamut.automahout.eval.PipelineInvariants.Outcome invOutDetect;

		int linkOk;

		org.mastodon.mamut.automahout.eval.PipelineInvariants.Outcome invOutLink;

		org.mastodon.mamut.automahout.eval.PipelineInvariants.Outcome invOutRefine;

		double[] finalMetrics;

		double linkDensity;

		double[] cont;

		double continuity;

		double meanTrackLen;

		int isolatedSpots;

		double retention;

		java.lang.String retentionReport;

		java.lang.String edits;

		boolean noiseSuspected;
	}
}
