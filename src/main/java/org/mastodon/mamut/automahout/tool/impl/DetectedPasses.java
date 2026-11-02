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

import static org.mastodon.mamut.automahout.tool.CorrectionPass.Effect.NONE;
import static org.mastodon.mamut.automahout.tool.CorrectionPass.Stage.DETECTED;
import static org.mastodon.mamut.automahout.tool.ToolRole.CORRECT_DETECTIONS;
import static org.mastodon.mamut.automahout.tool.ToolRole.SHAPE;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.ToIntFunction;

import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.eval.BrightSpotAudit;
import org.mastodon.mamut.automahout.tool.Applicability;
import org.mastodon.mamut.automahout.tool.BuiltinPass;
import org.mastodon.mamut.automahout.tool.CorrectionPass;
import org.mastodon.mamut.automahout.tool.PassScope;
import org.mastodon.mamut.automahout.tool.PhaseTimings;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;

/**
 * The plan run on each frame as ELEPHANT detects it ({@link CorrectionPass.Stage#DETECTED}), in the order
 * {@code ElephantTool.predictWindow} has always run it: fit the extent, split, resolve duplicates, delete what
 * is not a cell, and fit the contour LAST. The reasons for the order are with each pass.
 *
 * <p>The window's parameters and the accumulators its end-of-window report reads are scope values, set up by
 * {@code predictWindow} before the first frame; each pass's time goes to {@link PhaseTimings} under the label
 * it always had, and a pass that can make a bright location vanish records a {@link BrightSpotAudit}
 * observation after it, under its old label.</p>
 */
public final class DetectedPasses
{
	private DetectedPasses()
	{}

	// Scope values predictWindow sets up for the window.
	static final String EXPECTED_CEILING = "detected.expected_ceiling";

	static final String INTENSITY_RATIO = "detected.intensity_ratio";

	static final String QUALITY_THRESHOLD = "detected.quality_threshold";

	static final String MIN_SIZE = "detected.min_size";

	// ...and the accumulators the window's report reads (arrays, as the passes always filled them).
	static final String SPLIT_FUNNEL = "detected.split_funnel";

	static final String FLAT_SHAPE = "detected.flat_shape";

	static final String FLAT_WITHHELD = "detected.flat_withheld";

	static final String NO_SIGNAL_CEILING = "detected.no_signal_ceiling";

	static final String DEBRIS_THRESHOLDS = "detected.debris_thresholds";

	static final String DEBRIS_BLOCKED = "detected.debris_blocked";

	static final String DEBRIS_VOLUME_STATS = "detected.debris_volume_stats";

	static final String DEBRIS_FAINT = "detected.debris_faint";

	/** {sum, n} of the automatic size floor over the frames that measured one. */
	static final String SIZE_FLOOR = "detected.size_floor";

	static final String CONTOUR_SEEN = "detected.contour_seen";

	// Pass ids the report reads totals of.
	public static final String EXTENT = "detected.extent";

	public static final String SPLIT = "detected.split_merged";

	public static final String DUPLICATES = "detected.duplicates";

	public static final String FLAT_CONTRAST = "detected.flat_contrast";

	public static final String NO_SIGNAL = "detected.no_signal";

	public static final String DEBRIS = "detected.debris";

	public static final String SMALL = "detected.min_size";

	public static final String CONTOUR = "detected.contour_fit";

	public static final String Z_REFIT = "detected.flat_z_refit";

	public static final String RECOVER = "detected.recover_missed";

	/** Times the body under {@code timing}, then records each audit observation, as the inline steps did. */
	private static ToIntFunction< PassScope > timed( final String timing, final ToIntFunction< PassScope > body,
			final String... audits )
	{
		return s -> {
			final long start = System.nanoTime();
			final int n = body.applyAsInt( s );
			PhaseTimings.record( s.ctx, timing, start );
			for ( final String audit : audits )
				BrightSpotAudit.observe( s.ctx, audit );
			return n;
		};
	}

	public static final List< CorrectionPass > BUILT_IN = Collections.unmodifiableList( Arrays.asList(

			// Over-segmentation duplicates are resolved BEFORE the flat-contrast check, not after it
			// as they used to be, because that check stops at the user's stated CELL count while the
			// number it compares against is a SPOT count. With ~28% of a frame's detections being a
			// second spot on a cell already detected, "stop deleting at 250 spots" delivered ~180
			// cells — the guard meant to protect the stated count systematically undershot it by a
			// quarter. Measured on t=0..9: 591 raw spots pinned to 250, then duplicate removal took
			// the same frame to ~180. Deduplicating first makes both sides of the comparison cells.
			// FIRST, because every filter below asks a question about a detection's geometry and
			// nothing had ever checked that geometry against the image. Overlap decides duplicates,
			// the interior samples are laid out on the radius, and a detection wider than the body
			// under it reads as background however bright that body is.
			new BuiltinPass( EXTENT, DETECTED, SHAPE, NONE,
					"Resizes each detection to the cell measured around it in the image (8 rays).",
					timed( "extent from image", s -> AutoCorrectOrchestrator.resizeToMeasuredExtent( s.ctx, s.timepoint ) ) ),

			// And the case the neighbour cap cannot reach: the model produced ONE detection over a
			// touching pair, so there is no second detection to protect. Before linking, so the
			// detections being replaced carry no edges yet. (Opt-in: CleanupToggles.)
			new BuiltinPass( SPLIT, DETECTED, CORRECT_DETECTIONS, NONE,
					"Splits one detection over two touching cells (opt-in).",
					timed( "split merged detections", s -> AutoCorrectOrchestrator.splitMergedDetections( s.ctx,
							s.timepoint, s.< int[] >get( SPLIT_FUNNEL ) ) ) ),

			new BuiltinPass( DUPLICATES, DETECTED, CORRECT_DETECTIONS, NONE,
					"Merges detections tiling one cell (opt-in).",
					timed( "duplicate resolution",
							s -> AutoCorrectOrchestrator.resolveOverlappingDuplicates( s.ctx, s.timepoint, s.timepoint ),
							"[3/6] duplicate resolution" ) ),

			// Unconditional, independent of quality/intensityRatio: a spot with no real local
			// contrast peak (flat vs. its surroundings) has no real signal at all regardless of how
			// confident ELEPHANT's own quality score is about it — an undertrained model can be
			// wrongly overconfident about a spurious detection, so "is there an actual peak here" is
			// a more direct, model-independent check than trusting the model's own confidence.
			// Contrast (not absolute intensity) so this works regardless of modality/noise floor —
			// e.g. a visibly noisy brightfield "background" is never exactly zero.
			new BuiltinPass( FLAT_CONTRAST, DETECTED, CORRECT_DETECTIONS, NONE,
					"Deletes detections with no local contrast peak, down to the cell-count floor.",
					timed( "flat-contrast check", s -> ElephantTool.deleteFlatContrastSpots( s.ctx, s.timepoint,
							s.getDouble( EXPECTED_CEILING, 0.0 ), s.< double[] >get( FLAT_SHAPE ),
							s.< int[] >get( FLAT_WITHHELD ) ), "[3/6] flat-contrast deletion" ) ),

			// "Brighter than the background" is a fact about fluorescence; the pass also asks ImageProfile
			// itself, for a run whose modality is not measured yet.
			new BuiltinPass( NO_SIGNAL, DETECTED, CORRECT_DETECTIONS, NONE,
					"Deletes detections whose interior median is no brighter than the background (fluorescence).",
					timed( "no-signal check", s -> ElephantTool.deleteSpotsWithNoSignal( s.ctx, s.timepoint,
							s.< double[] >get( NO_SIGNAL_CEILING ) ), "[3/6] no-signal deletion" ) )
					.onlyFor( BuiltinPass::fluorescenceOnly ),

			// Each deleting pass records its own bright-spot observation right after it. The inline chain
			// recorded the flat-contrast one after the debris check, so what flat contrast removed was
			// attributed to the no-signal deletion (or, with that pass off, to debris).
			new BuiltinPass( DEBRIS, DETECTED, CORRECT_DETECTIONS, NONE,
					"Deletes faint or small-and-uneven detections against the user's confirmed cells.",
					timed( "debris check", s -> ElephantTool.deleteDebrisSpots( s.ctx, s.timepoint,
							s.< double[] >get( DEBRIS_THRESHOLDS ), s.< int[] >get( DEBRIS_BLOCKED ),
							s.< double[] >get( DEBRIS_VOLUME_STATS ), s.< int[] >get( DEBRIS_FAINT ) ),
							"[3/6] debris (uneven brightness)" ) ),

			// Belt-and-suspenders against ElephantIntegration.addSpotFromJson's own overlap check
			// (which rejects a NEW detection before it is even added): this also cleans up a
			// duplicate that already existed BEFORE protection was configured/tagged (e.g. a human
			// picked one of several overlapping candidates as the confirmed result but never deleted
			// the others) — a no-op when no protection is configured or nothing overlaps.
			new BuiltinPass( "detected.protected_overlap", DETECTED, CORRECT_DETECTIONS, NONE,
					"Deletes unprotected detections overlapping a spot the user protected.",
					timed( "protected-overlap cleanup", s -> ProtectedSpots.deleteOverlappingNonProtected( s.ctx,
							s.timepoint ), "[3/6] protected-overlap cleanup" ) ),

			new BuiltinPass( "detected.weak", DETECTED, CORRECT_DETECTIONS, NONE,
					"Deletes detections below the intensity ratio or quality threshold (when one is set).",
					timed( "intensity/quality filter", s -> AutoCorrectOrchestrator.filterWeakSpots( s.ctx,
							s.timepoint, s.getDouble( INTENSITY_RATIO, 0.0 ), s.getDouble( QUALITY_THRESHOLD, 0.0 ) ),
							"[3/6] min_intensity_ratio / quality_threshold" ) )
					.when( s -> s.getDouble( INTENSITY_RATIO, 0.0 ) > 0.0 || s.getDouble( QUALITY_THRESHOLD, 0.0 ) > 0.0 ),

			// Reject sub-cellular noise specks directly from the detection used for linking
			// (min_intensity_ratio cannot separate dim cells from bright noise; size can).
			// minSize == 0 is OFF and is the DEFAULT, so unless a caller passes min_size this is a
			// no-op; AUTO (a per-timepoint Otsu split that preserves small daughter cells, see
			// deriveNoiseSizeFloor) is min_size < 0 and must be asked for. An earlier revision had
			// 0 meaning AUTO — docs/example_log.md still shows "min_size (AUTO ~3.0)" logged from a
			// run that passed min_size=0 — so do not read a 0 in an old log as the filter running.
			new BuiltinPass( SMALL, DETECTED, CORRECT_DETECTIONS, NONE,
					"Deletes sub-cellular specks below min_size (off unless asked for).",
					timed( "size filter", s -> {
						final double[] r = ElephantTool.filterNoiseBySize( s.ctx, s.timepoint, s.getDouble( MIN_SIZE, 0.0 ) );
						if ( r[ 1 ] > 0.0 )
						{
							final double[] floor = s.get( SIZE_FLOOR );
							floor[ 0 ] += r[ 1 ];
							floor[ 1 ]++;
						}
						return ( int ) r[ 0 ];
					}, "[3/6] min_size" ) ),

			// After every deleting pass: a re-placed detection is two to four planes tall, and the passes
			// above lay their interior samples on a sphere of the largest semi-axis, so earlier it would
			// read background in plane. Before the contour fit, which fits on the detection's own plane.
			new BuiltinPass( Z_REFIT, DETECTED, SHAPE, NONE,
					"Re-places one-plane-thick detections on their nucleus in z (stacks).",
					timed( "flat z refit", s -> AutoCorrectOrchestrator.refitFlatDetectionsInZ( s.ctx, s.timepoint ) ) )
					.onlyFor( d -> d.volumetric ? Applicability.always()
							: Applicability.notFor( "a single plane has no z to re-place a detection along" ) ),

			// After the z refit, so "covered" is judged against detections that sit on their nuclei. Adds each
			// bright nucleus no detection covers, tagged fn so it is trained on as a missed cell (the user's
			// choice). Fluorescence and stacks only: it looks for bright peaks, and was measured on 3-D data.
			new BuiltinPass( RECOVER, DETECTED, CORRECT_DETECTIONS, NONE,
					"Adds a detection at each bright nucleus no detection covers, as a missed cell (fluorescence, stacks).",
					timed( "missed-nucleus recovery", s -> AutoCorrectOrchestrator.recoverUncoveredNuclei( s.ctx, s.timepoint ) ) )
					.onlyFor( d -> !d.volumetric ? Applicability.notFor( "measured on stacks only; a 2-D frame is not measured yet" )
							: BuiltinPass.fluorescenceOnly( d ) ),

			// LAST, and the order is load-bearing. Every pass above reads a spot's radius as a
			// SCALAR — Mastodon reports the bounding sphere, which is the largest semi-axis — and
			// lays its interior samples out on a sphere of it. On a fitted ellipse those samples
			// land outside the body along the short axis and read background, so fitting earlier
			// would quietly degrade every intensity test in front of it. Fitting here changes only
			// what is committed, linked and TRAINED ON, which is the whole point: measured against
			// CTC silver truth, a circle overlaps its cell at IoU 0.74 after the extent fix and a
			// fitted ellipse at 0.81, with the 10th percentile moving 0.58 -> 0.75.
			//
			// (SpotShape.interiorRadius is what the three interior samplers use from now on, so a
			// spot fitted in an EARLIER window or phase is read correctly when it comes round
			// again. Equal to the radius for anything circular, so nothing changes until a fit has
			// actually run.)
			new BuiltinPass( CONTOUR, DETECTED, SHAPE, NONE,
					"Fits each detection to an ellipse on the cell's own outline (in plane); last.",
					timed( "contour fit", s -> ElephantTool.fitDetectionsToContour( s.ctx, s.timepoint,
							s.< int[] >get( CONTOUR_SEEN ) ) ) ) ) );
}
