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
package org.mastodon.mamut.automahout.tool;

/**
 * On/off switches for the two count-reducing cleanup passes that delete a detection outright: the
 * flat-contrast background test ({@code ElephantTool.deleteFlatContrastSpots}) and over-segmentation
 * duplicate resolution ({@code AutoCorrectOrchestrator.resolveOverlappingDuplicates}).
 *
 * <p>They exist to make a run's spot loss ATTRIBUTABLE. Between them these two passes routinely
 * remove more spots than every other pass combined, and both are heuristics measured against
 * estimates far weaker than the detection they overrule — the flat-contrast test builds a background
 * baseline from a few dozen samples drawn from the space left between DETECTED spots (undetected real
 * cells included), and duplicate resolution calls two spots one over-segmented blob whenever their
 * bounding spheres touch with no dark valley between them, which in a confluent culture is also true
 * of two adjacent real cells. Observed on a 74-tp culture: a window committed ~451 detections/frame,
 * the flat-contrast pass deleted ~296 of them and duplicate resolution ~59, and the window finished
 * at 184 spots/tp — the remaining passes (linking, correction, topology consistency) together
 * accounted for ~20. Deciding whether either pass is over-deleting means re-running the same window
 * with one of them off, which is otherwise impossible without editing and rebuilding the plugin.</p>
 *
 * <p>Both default to ON: with nothing set anywhere, the pipeline behaves exactly as it did before
 * these switches existed.</p>
 *
 * <p>Deliberately NOT advertised in the tool description the LLM agent reads. Every metric the agent
 * judges itself on (spots/tp, completeness, retention) goes UP when these passes are off, so an agent
 * that knew about them could improve its own scorecard by disabling a precision guard. They are set
 * by the pre-flight dialog (the user, for a diagnostic run) or by an explicit {@code adaptive_track}
 * parameter (a scripted or test run), and a pre-flight choice wins over a parameter — the same
 * precedence as the other pre-flight overrides.</p>
 */
public final class CleanupToggles
{
	private CleanupToggles()
	{}

	/** Pre-flight dialog's flat-contrast choice — {@link Boolean}; wins over the per-call parameter. */
	public static final String SHARED_FLAT_CONTRAST_OVERRIDE = "adaptive_track.flat_contrast_filter.override";

	/** {@code adaptive_track}'s own {@code flat_contrast_filter} parameter — {@link Boolean}. */
	public static final String SHARED_FLAT_CONTRAST_REQUESTED = "adaptive_track.flat_contrast_filter";

	/**
	 * Pre-flight dialog's duplicate-merge choice — {@link Boolean}, default OFF; wins over the
	 * parameter.
	 *
	 * <p>Was default ON, and merged detections the detector had already separated. The evidence is a
	 * frame the user looked at: two clearly resolved nuclei, each with its own detection, replaced by
	 * one ellipse drawn around both of them and the dark gap between. Every merge pass decides "these
	 * are one over-segmented cell" from geometry alone — bounding spheres that touch, a local watershed
	 * that finds no neck, a pair that holds a steady sub-cell offset — and in a culture where cells sit
	 * against each other all three are also true of two real neighbours. The detector's answer is the
	 * measured one; a geometric rule overruling it is a guess overruling a measurement.</p>
	 *
	 * <p>Off unless asked for. Turn it back on for data where the detector genuinely tiles one nucleus
	 * with several detections, and read the merge counts in the log when you do.</p>
	 */
	public static final String SHARED_RESOLVE_DUPLICATES_OVERRIDE =
			"adaptive_track.resolve_duplicates.override";

	/** {@code adaptive_track}'s own {@code resolve_duplicates} parameter — {@link Boolean}, default OFF. */
	public static final String SHARED_RESOLVE_DUPLICATES_REQUESTED = "adaptive_track.resolve_duplicates";

	/**
	 * Pre-flight override for splitting one detection into one per cell — {@link Boolean}, default
	 * OFF; wins over the parameter.
	 *
	 * <p>Was default ON. Measured on window 1 of Fluo-N2DL-HeLa 01 with the origin dump (2026-10-01):
	 * the pass produced 30 spots, 22 of them two-to-a-cell on a single silver-truth nucleus (11 wrong
	 * splits) against at most 2 splits that could have been right. Of the 13 same-cell double spots
	 * in that window, 12 came from this pass; predict produced one. The user's screenshots of one
	 * nucleus drawn as two overlapping ellipses, while the frames either side show one, were this.</p>
	 *
	 * <p>What it cost beyond the picture: every product is claimed for the detector as {@code tp}, so
	 * the wrong splits were taught. And the case it exists for is rare with the current detector — a
	 * detection covering two truth cells was 0.7% of the first dump.</p>
	 *
	 * <p>Off until the pass is rebuilt and re-measured. The other merge/split decision,
	 * {@link #SHARED_RESOLVE_DUPLICATES_OVERRIDE}, is off for the mirror-image reason.</p>
	 */
	public static final String SHARED_SPLIT_MERGED_OVERRIDE = "adaptive_track.split_merged.override";

	/** {@code adaptive_track}'s own {@code split_merged} parameter — {@link Boolean}, default OFF. */
	public static final String SHARED_SPLIT_MERGED_REQUESTED = "adaptive_track.split_merged";

	/**
	 * Pre-flight dialog's "these are fluorescence images" choice — {@link Boolean}, default OFF.
	 *
	 * <p>The odd one out among these switches: the other two turn a pass OFF for a diagnostic run, this
	 * one turns an extra pass ON. It gates removal of detections whose TOTAL integrated brightness —
	 * mean interior intensity times volume — falls far below the user's confirmed cells. In a
	 * fluorescence image that total is roughly how much labelled material is present, so a small dim
	 * speck and a real cell separate on it even when neither size nor local contrast can tell them
	 * apart. In brightfield or phase contrast it means nothing of the kind, which is why this is a
	 * choice and not a default.</p>
	 *
	 * <p>Off unless the user ticks the box. Eventually the modality should come from the image metadata
	 * or the pre-flight VLM's own reading of the dataset rather than from a checkbox; until then, an
	 * explicit answer beats a guess that would silently delete cells in the wrong modality.</p>
	 */
	public static final String SHARED_DIM_TOTAL_SIGNAL_OVERRIDE =
			"adaptive_track.dim_total_signal_filter.override";

	/**
	 * Pre-flight dialog's crop-tuning choice — {@link Boolean}, default ON.
	 *
	 * <p>Every predict in the warm-up and in the per-window tuning loop is normally restricted to a
	 * small auto-selected region, so a training round costs a fraction of a full-frame pass. The cost of
	 * that is what the region contains: on a large, sparsely populated volume the auto-selected crop
	 * held about five cells, so every count the tuning phase reported described those five and the model
	 * was adapted on them alone.</p>
	 *
	 * <p>Turning it off makes every one of those predicts full-frame. Much slower — that is the whole
	 * reason the crop exists — but the tuning phase then sees what the commit will see.</p>
	 */
	public static final String SHARED_CROP_TUNING_OVERRIDE = "adaptive_track.crop_tuning.override";

	/**
	 * Whether the flat-contrast background test may delete anything. When false it is a no-op
	 * everywhere it runs, including the density probe that uses it to estimate full-frame cell density.
	 */
	public static boolean flatContrastFilterEnabled( final ToolContext ctx )
	{
		return enabled( ctx, SHARED_FLAT_CONTRAST_OVERRIDE, SHARED_FLAT_CONTRAST_REQUESTED );
	}

	/**
	 * Whether the flat-contrast check's verdict is audited against a VLM — {@link Boolean}, default OFF.
	 *
	 * <p>Opt-in, and off by default, because it is a DIAGNOSTIC that deletes nothing and because this
	 * project has already run it: across 48 flagged and 48 kept crops the VLM called 77% of one group
	 * and 71% of the other background, which is no separation at all. It cannot read these crops, so
	 * paying for it every run buys a line of log that says so again.</p>
	 *
	 * <p>What it costs: each audit renders up to 32 multi-plane crops and sends them to the VLM, and it
	 * runs up to three times per window. On the dataset that prompted this a single VLM call on this
	 * data took over nine minutes. Turn it back on when the crops or the model change enough that the
	 * question is open again.</p>
	 */
	public static final String SHARED_FLAT_CONTRAST_AUDIT_OVERRIDE =
			"adaptive_track.flat_contrast_audit.override";

	/**
	 * {@link ToolContext#getSharedData} key for whether a VLM is asked to confirm what is in a crop.
	 *
	 * <p>The question is "is anything at this crosshair". On fluorescence the pixels answer it, against
	 * the frame's own background, and that answer can be checked — see
	 * {@code benchmark/vlm-fp-negatives}, where the VLM's could not: of 2444 positions that are
	 * genuinely empty it called 17% empty, and of the ones holding a cell it called 35% empty. Where
	 * brightness does NOT mean signal, no measurement stands on anything and a judge that looks at the
	 * picture earns its cost.</p>
	 *
	 * <p>Defaulted from {@code ImageProfile} at pre-flight rather than fixed, and overridable there.</p>
	 */
	public static final String SHARED_VLM_CROP_CHECK_OVERRIDE = "preflight.vlm_crop_check";

	/** Whether the flat-contrast check's verdict is audited against a VLM — see the key's note. */
	public static boolean flatContrastAuditEnabled( final ToolContext ctx )
	{
		return resolveOptIn( ctx.getSharedData( SHARED_FLAT_CONTRAST_AUDIT_OVERRIDE ) );
	}

	/**
	 * Whether detections far dimmer in TOTAL than the user's confirmed cells may be deleted as debris.
	 * Unlike the other two, this is opt-in: it is only meaningful for fluorescence, so it stays off
	 * until the user says so.
	 */
	public static boolean dimTotalSignalFilterEnabled( final ToolContext ctx )
	{
		return resolveOptIn( ctx.getSharedData( SHARED_DIM_TOTAL_SIGNAL_OVERRIDE ) );
	}

	/**
	 * The opt-in rule on its own, the mirror of {@link #resolve}: a pass nobody has an opinion about
	 * stays OFF. Only an explicit yes turns it on, because it deletes real cells in the wrong modality.
	 */
	public static boolean resolveOptIn( final Object override )
	{
		return Boolean.TRUE.equals( override );
	}

	/**
	 * Shared-data key, {@link Boolean}: the pre-flight dialog's "Fit detections to the cell's shape" switch. ON
	 * unless it is explicitly {@code false}, so a run with no dialog (the benchmark) fits as before.
	 */
	public static final String SHARED_SHAPE_FIT_OVERRIDE = "preflight.shape_fit";

	/**
	 * Whether each inference's detections are fitted to the cell's shape: the in-plane ellipse, and on a stack
	 * the 3-D ellipsoid (Mastodon's ellipsoid fitting) where the plane fit refuses. Off, they keep ELEPHANT's.
	 */
	public static boolean shapeFitEnabled( final ToolContext ctx )
	{
		return resolveDefaultOn( ctx.getSharedData( SHARED_SHAPE_FIT_OVERRIDE ) );
	}

	/**
	 * Shared-data key, {@link Boolean}: the pre-flight's training balance. When {@code true} the detections the
	 * model is taught are sent to ELEPHANT with the weight of a correction (false_weight, 10x), as every
	 * detection was until 30 September -- then because ELEPHANT tagged the detector's own output "missed" (fn),
	 * which was fixed; the balance it gave was not chosen, but runs trained under it (good-20260930) stayed
	 * stable, and after the fix the background negatives, still at 10x, outweigh the cells about 2:1. The graph
	 * keeps them as cells either way; only the training weight changes. OFF unless set, so a run with no dialog
	 * (the benchmark) trains as it has since the fix.
	 */
	public static final String SHARED_DETECTIONS_AS_CORRECTIONS = "preflight.detections_as_corrections";

	/** See {@link #SHARED_DETECTIONS_AS_CORRECTIONS}. */
	public static boolean detectionsWeightedAsCorrections( final ToolContext ctx )
	{
		return resolveOptIn( ctx.getSharedData( SHARED_DETECTIONS_AS_CORRECTIONS ) );
	}

	/** Whether the warm-up and the per-window tuning loop restrict their predicts to a small region. */
	public static boolean cropTuningEnabled( final ToolContext ctx )
	{
		return resolveDefaultOn( ctx.getSharedData( SHARED_CROP_TUNING_OVERRIDE ) );
	}

	/**
	 * Whether a VLM is asked to look at a crop, or its intensity is simply measured.
	 *
	 * <p>ON unless the pre-flight said otherwise, so a caller with no opinion behaves as before.</p>
	 */
	public static boolean vlmCropCheckEnabled( final ToolContext ctx )
	{
		return resolveDefaultOn( ctx.getSharedData( SHARED_VLM_CROP_CHECK_OVERRIDE ) );
	}

	/**
	 * The default-ON rule on its own, the mirror of {@link #resolveOptIn}: only an explicit {@code
	 * false} turns the pass off. Anything else — nobody asked, or a non-boolean left in shared data by
	 * some other caller — reads as ON, so a build carrying the switch behaves like the build before it.
	 */
	public static boolean resolveDefaultOn( final Object override )
	{
		return !( override instanceof Boolean ) || ( Boolean ) override;
	}

	/**
	 * Whether ANY post-processing pass may merge two detections into one covering ellipsoid.
	 *
	 * <p>Default OFF — see {@link #SHARED_RESOLVE_DUPLICATES_OVERRIDE}. This gates all four merge
	 * passes, not just the one it was named after: {@code resolveOverlappingDuplicates},
	 * {@code mergeDuplicateSpots}, {@code mergePersistentCoLocatedPairs} and ELEPHANT's NMS
	 * ({@code deduplicateSpotsAtTimepoint}). Three of them were never gated at all, so switching
	 * "duplicate resolution" off left three quarters of the merging running.</p>
	 */
	public static boolean duplicateResolutionEnabled( final ToolContext ctx )
	{
		return optIn( ctx, SHARED_RESOLVE_DUPLICATES_OVERRIDE, SHARED_RESOLVE_DUPLICATES_REQUESTED );
	}

	/**
	 * Whether the automatic pass may replace one detection with one per cell under it. Default OFF —
	 * see {@link #SHARED_SPLIT_MERGED_OVERRIDE}. Gates {@code splitMergedDetections} only; the agent's
	 * explicit {@code spot.split} tool is a decision about named positions, not this heuristic.
	 */
	public static boolean splitMergedEnabled( final ToolContext ctx )
	{
		return optIn( ctx, SHARED_SPLIT_MERGED_OVERRIDE, SHARED_SPLIT_MERGED_REQUESTED );
	}

	/** Records the pre-flight dialog's choices; either may be {@code null} to leave that pass alone. */
	public static void setPreflightOverrides( final ToolContext ctx, final Boolean flatContrastFilter,
			final Boolean resolveDuplicates, final Boolean dimTotalSignalFilter,
			final Boolean cropTuning, final Boolean flatContrastAudit, final Boolean vlmCropCheck,
			final Boolean splitMerged )
	{
		if ( splitMerged != null )
			ctx.setSharedData( SHARED_SPLIT_MERGED_OVERRIDE, splitMerged );
		if ( vlmCropCheck != null )
			ctx.setSharedData( SHARED_VLM_CROP_CHECK_OVERRIDE, vlmCropCheck );
		if ( cropTuning != null )
			ctx.setSharedData( SHARED_CROP_TUNING_OVERRIDE, cropTuning );
		if ( flatContrastFilter != null )
			ctx.setSharedData( SHARED_FLAT_CONTRAST_OVERRIDE, flatContrastFilter );
		if ( resolveDuplicates != null )
			ctx.setSharedData( SHARED_RESOLVE_DUPLICATES_OVERRIDE, resolveDuplicates );
		if ( dimTotalSignalFilter != null )
			ctx.setSharedData( SHARED_DIM_TOTAL_SIGNAL_OVERRIDE, dimTotalSignalFilter );
		if ( flatContrastAudit != null )
			ctx.setSharedData( SHARED_FLAT_CONTRAST_AUDIT_OVERRIDE, flatContrastAudit );
	}

	/** Records an {@code adaptive_track} call's own parameters. A pre-flight override still wins. */
	public static void setRequested( final ToolContext ctx, final boolean flatContrastFilter,
			final boolean resolveDuplicates, final boolean splitMerged )
	{
		ctx.setSharedData( SHARED_FLAT_CONTRAST_REQUESTED, flatContrastFilter );
		ctx.setSharedData( SHARED_RESOLVE_DUPLICATES_REQUESTED, resolveDuplicates );
		ctx.setSharedData( SHARED_SPLIT_MERGED_REQUESTED, splitMerged );
	}

	/**
	 * One line naming every cleanup pass currently switched off, or {@code null} when both are on.
	 *
	 * <p>Logged once per run next to the invariants summary, for the same reason that one is logged: a
	 * disabled pass is silent, so without this the log of a diagnostic run is indistinguishable from
	 * the log of a normal one — and these runs are precisely the ones whose numbers get compared
	 * against an earlier log days later.</p>
	 */
	public static String disabledSummary( final ToolContext ctx )
	{
		return disabledSummary( flatContrastFilterEnabled( ctx ), duplicateResolutionEnabled( ctx ),
				splitMergedEnabled( ctx ) );
	}

	/**
	 * The summary line on its own, given the two resolved states.
	 *
	 * <p>The two are no longer symmetric: flat-contrast deletion is on by default and switching it off
	 * makes a run diagnostic, while duplicate merging is off by default and switching it ON is the
	 * notable choice — it lets a geometric rule overrule the detector about where one cell ends.</p>
	 */
	public static String disabledSummary( final boolean flat, final boolean dup )
	{
		return disabledSummary( flat, dup, false );
	}

	/** As {@link #disabledSummary(boolean, boolean)}, plus the split pass, which is notable when ON. */
	public static String disabledSummary( final boolean flat, final boolean dup, final boolean split )
	{
		final String base = disabledSummaryWithoutSplit( flat, dup );
		if ( !split )
			return base;
		final String splitLine = "Automatic SPLITTING is switched on: one detection may be replaced by one "
				+ "spot per intensity basin under it. Off by default because, measured, it split single "
				+ "nuclei far more often than merged pairs (11 wrong against at most 2 right in one "
				+ "window), and every product is taught as a positive.";
		return base == null ? splitLine : base + " " + splitLine;
	}

	private static String disabledSummaryWithoutSplit( final boolean flat, final boolean dup )
	{
		if ( flat && !dup )
			return null;
		final StringBuilder sb = new StringBuilder();
		if ( !flat )
			sb.append( "DIAGNOSTIC RUN — flat-contrast deletion is switched off (detections with no "
					+ "local contrast peak are KEPT). Spot counts are not comparable with a normal run, "
					+ "and the kept spots become training positives for the next round." );
		if ( !flat && dup )
			sb.append( " " );
		if ( dup )
			sb.append( "Duplicate MERGING is switched on: overlapping detections may be replaced by one "
					+ "ellipsoid covering both. Off by default because the detector has already decided "
					+ "where one cell ends, and a geometric rule overruling it merges real neighbours in "
					+ "a dense field. Read the merge counts below." );
		return sb.toString();
	}

	private static boolean enabled( final ToolContext ctx, final String overrideKey,
			final String requestedKey )
	{
		return resolve( ctx.getSharedData( overrideKey ), ctx.getSharedData( requestedKey ) );
	}

	private static boolean optIn( final ToolContext ctx, final String overrideKey,
			final String requestedKey )
	{
		return resolveOptIn( ctx.getSharedData( overrideKey ), ctx.getSharedData( requestedKey ) );
	}

	/**
	 * {@link #resolve}'s precedence with {@link #resolveOptIn}'s default: a pre-flight override wins
	 * over a per-call parameter, and a pass nobody asked for stays OFF.
	 */
	public static boolean resolveOptIn( final Object override, final Object requested )
	{
		if ( override instanceof Boolean )
			return ( Boolean ) override;
		return Boolean.TRUE.equals( requested );
	}

	/**
	 * The precedence rule on its own: a pre-flight {@code override} wins over a per-call
	 * {@code requested} value, and a pass nobody has an opinion about stays ON.
	 */
	public static boolean resolve( final Object override, final Object requested )
	{
		if ( override instanceof Boolean )
			return ( Boolean ) override;
		return !( requested instanceof Boolean ) || ( Boolean ) requested;
	}
}
