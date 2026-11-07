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
import java.util.Map;

import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator.CorrectionResult;
import org.mastodon.mamut.automahout.eval.LineageConstraintProfile;
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ToolContext;

import bdv.viewer.ViewerPanel;

/**
 * Autonomous correction tool that analyses the current tracking result and
 * selects the appropriate fix strategy for each timepoint.
 *
 * <p>Runs three strategies in order:</p>
 * <ol>
 *   <li>Prune long links (distance &gt; {@code long_link_factor} × max spot radius)</li>
 *   <li>Re-link orphaned spots via ELEPHANT NN linking</li>
 *   <li>Delete isolated spots (non-terminal timepoints only)</li>
 * </ol>
 *
 * <p>Optionally refits anisotropic spots via ELEPHANT when {@code refit=true}.</p>
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_T}</td><td>int</td><td>current viewer timepoint</td>
 *       <td>Timepoint to correct; {@code -1} corrects all timepoints</td></tr>
 *   <tr><td>{@value #PARAM_LONG_LINK_FACTOR}</td><td>double</td><td>from settings</td>
 *       <td>Override the long-link factor from Automahout settings</td></tr>
 *   <tr><td>{@value #PARAM_REFIT}</td><td>boolean</td><td>from settings</td>
 *       <td>Override the refit-anisotropic flag from Automahout settings</td></tr>
 *   <tr><td>{@value #PARAM_ANISOTROPY_THRESHOLD}</td><td>double</td><td>from settings</td>
 *       <td>Override the anisotropy threshold from Automahout settings</td></tr>
 * </table>
 */
@Plugin( type = AutomahoutTool.class )
public class AutoCorrectTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.CORRECT_LINEAGE;
	}


	public static final String ID = "auto.correct";

	public static final String PARAM_T = "t";

	public static final String PARAM_LONG_LINK_FACTOR = "long_link_factor";

	public static final String PARAM_REFIT = "refit";

	public static final String PARAM_ANISOTROPY_THRESHOLD = "anisotropy_threshold";

	public static final String PARAM_MIN_INTENSITY_RATIO = "min_intensity_ratio";

	public static final String PARAM_MIN_SIZE_RATIO = "min_size_ratio";

	public static final String PARAM_MERGE_OVERLAP_RATIO = "merge_overlap_ratio";

	public static final String PARAM_FILL_GAPS = "fill_gaps";

	public static final String PARAM_GAP_CROP_FACTOR = "gap_crop_factor";

	public static final String PARAM_MIN_TRACKLET_LENGTH = "min_tracklet_length";

	public static final String PARAM_DIVISION_LINK_FACTOR = "division_link_factor";

	public static final String PARAM_MIN_BRANCH_LENGTH = "min_branch_length";

	public static final String PARAM_MIN_DIVISION_INTERVAL = "min_division_interval";

	/**
	 * Absolute pre-flight sanity ceiling on instantaneous division rate (fraction of all spots that are
	 * division points), checked regardless of whether a learned profile is active: no real cell
	 * population plausibly has this large a fraction of cells simultaneously mid-division, so exceeding
	 * it almost certainly means Phase 1 (adaptive_track) produced spurious divisions from linking errors.
	 */
	private static final double ABSOLUTE_SUSPICIOUS_DIVISION_RATE = 0.20;

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Autonomously correct tracking errors: prune long links, re-link orphaned spots, "
				+ "delete isolated false positives. "
				+ "Parameters: t (int, -1 = all timepoints, default = current viewer timepoint), "
				+ "long_link_factor (double, prunes spatially long links), "
				+ "merge_overlap_ratio (double, > 0 merges overlapping duplicate spots), "
				+ "min_tracklet_length (int, > 0 deletes short dangling tracklets; "
				+ "CAUTION: use only after full linking across all timepoints), "
				+ "min_branch_length (int, > 0 deletes short branches that originate from a "
				+ "division point — SAFE to use; standalone short tracks are preserved), "
				+ "division_link_factor (double, > 0 links orphan daughter cells to nearby "
				+ "dividing mothers; set 0 if no division expected), "
				+ "fill_gaps (boolean, default TRUE: reconstruct cells missing from a track's interior "
				+ "using neighbouring timepoints via ELEPHANT — extends dangling ends forward AND "
				+ "recovers missing predecessors backward, which dissolves false divisions and "
				+ "connects isolated spots), "
				+ "refit (boolean), anisotropy_threshold (double).";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		// Build a settings object, optionally overriding values from params
		final AutomahoutSettings settings = new AutomahoutSettings();
		if ( params.containsKey( PARAM_LONG_LINK_FACTOR ) )
			settings.setCorrectLongLinkFactor(
					( ( Number ) params.get( PARAM_LONG_LINK_FACTOR ) ).doubleValue() );
		if ( params.containsKey( PARAM_REFIT ) )
			settings.setCorrectRefitAnisotropic( parseBool( params.get( PARAM_REFIT ) ) );
		if ( params.containsKey( PARAM_ANISOTROPY_THRESHOLD ) )
			settings.setCorrectAnisotropyThreshold(
					( ( Number ) params.get( PARAM_ANISOTROPY_THRESHOLD ) ).doubleValue() );
		if ( params.containsKey( PARAM_MIN_INTENSITY_RATIO ) )
			settings.setCorrectMinIntensityRatio(
					( ( Number ) params.get( PARAM_MIN_INTENSITY_RATIO ) ).doubleValue() );
		if ( params.containsKey( PARAM_MIN_SIZE_RATIO ) )
			settings.setCorrectMinSizeRatio(
					( ( Number ) params.get( PARAM_MIN_SIZE_RATIO ) ).doubleValue() );
		if ( params.containsKey( PARAM_MERGE_OVERLAP_RATIO ) )
			settings.setCorrectMergeOverlapRatio(
					( ( Number ) params.get( PARAM_MERGE_OVERLAP_RATIO ) ).doubleValue() );
		// Autonomous-path default that OVERRIDES the Preferences-loaded value (this tool drives the
		// automated pipeline, so behaviour must be deterministic, not dependent on saved prefs):
		// fill gaps — reconstruct cells missing from a track's interior using neighbouring timepoints
		// (predict/relink at t+1), recovering broken lineages.
		settings.setCorrectFillGaps( params.containsKey( PARAM_FILL_GAPS )
				? parseBool( params.get( PARAM_FILL_GAPS ) ) : true );
		if ( params.containsKey( PARAM_GAP_CROP_FACTOR ) )
			settings.setCorrectGapCropFactor(
					( ( Number ) params.get( PARAM_GAP_CROP_FACTOR ) ).doubleValue() );
		// Default min_tracklet_length to 0 (disabled) when not explicitly provided.
		// The AutomahoutSettings default (5) is intended for GUI-based loops where
		// the full dataset has been linked; in LLM-driven tool calls the user must opt
		// in explicitly to avoid deleting real short tracklets near division events.
		settings.setCorrectMinTrackletLength(
				params.containsKey( PARAM_MIN_TRACKLET_LENGTH )
						? ( ( Number ) params.get( PARAM_MIN_TRACKLET_LENGTH ) ).intValue()
						: 0 );
		if ( params.containsKey( PARAM_DIVISION_LINK_FACTOR ) )
			settings.setCorrectDivisionLinkFactor(
					( ( Number ) params.get( PARAM_DIVISION_LINK_FACTOR ) ).doubleValue() );

		// Load the active learned lineage-constraint profile (if any). It supplies GT-derived defaults
		// for thresholds the caller did not set explicitly, so learned biology transfers to new data.
		final LineageConstraintProfile profile = loadActiveProfile( settings );
		final StringBuilder constraintsNote = new StringBuilder();

		// Absolute per-frame displacement cap for link pruning + orphan reconnection: the learned P99
		// (fallback to the observed max). A cell should not move farther than this between frames.
		double maxDisplacement = 0.0;
		if ( profile != null )
		{
			if ( !Double.isNaN( profile.displacementP99 ) && profile.displacementP99 > 0.0 )
				maxDisplacement = profile.displacementP99;
			else if ( !Double.isNaN( profile.displacementMax ) && profile.displacementMax > 0.0 )
				maxDisplacement = profile.displacementMax;
		}
		if ( maxDisplacement > 0.0 )
		{
			settings.setCorrectMaxDisplacement( maxDisplacement );
			constraintsNote.append( String.format( " max_displacement<-P99=%.1f", maxDisplacement ) );
		}

		final ViewerPanel viewer = context.requireViewer();
		final int numTimepoints = viewer.state().getNumTimepoints();

		// Determine target timepoint(s)
		final int tParam = params.containsKey( PARAM_T )
				? ( ( Number ) params.get( PARAM_T ) ).intValue()
				: viewer.state().getCurrentTimepoint();

		int minBranchLength = params.containsKey( PARAM_MIN_BRANCH_LENGTH )
				? ( ( Number ) params.get( PARAM_MIN_BRANCH_LENGTH ) ).intValue()
				: 0;
		// A post-division branch shorter than the shortest real GT track segments is implausible: use
		// the learned 5th-percentile track-segment length as a safe floor when the caller didn't set one.
		// Skipped for sparse GT, where track-segment length reflects annotation gaps, not real biology.
		if ( !params.containsKey( PARAM_MIN_BRANCH_LENGTH ) && profile != null && !profile.sparseAnnotation
				&& !Double.isNaN( profile.trackSegmentLengthP05 ) && profile.trackSegmentLengthP05 >= 2.0 )
		{
			minBranchLength = ( int ) Math.round( profile.trackSegmentLengthP05 );
			constraintsNote.append( " min_branch_length<-P05_track=" ).append( minBranchLength );
		}

		if ( tParam == -1 )
		{
			// Determine min_division_interval up front: used both by the pre-flight sanity check below
			// and by the final rapid-division cleanup phase. explicit override → learned constraint →
			// auto-estimate from observed cell-count growth.
			final int minDivisionInterval;
			final String divIntervalNote;
			if ( params.containsKey( PARAM_MIN_DIVISION_INTERVAL ) )
			{
				minDivisionInterval = ( ( Number ) params.get( PARAM_MIN_DIVISION_INTERVAL ) ).intValue();
				divIntervalNote = "explicit=" + minDivisionInterval;
			}
			else if ( profile != null && !Double.isNaN( profile.divisionIntervalMin )
					&& profile.divisionIntervalMin >= 1.0 )
			{
				// Learned: any division occurring faster than the minimum GT inter-division interval
				// is biologically implausible. This is more principled than the count-growth heuristic.
				minDivisionInterval = ( int ) Math.round( profile.divisionIntervalMin );
				divIntervalNote = "constraints=" + minDivisionInterval;
			}
			else
			{
				// Auto-estimate from observed cell-count growth rate across the full timepoint range.
				// Returns 0 when no growth is detected (disables removal).
				minDivisionInterval = AutoCorrectOrchestrator.estimateMinDivisionInterval(
						context, 0, numTimepoints - 1 );
				divIntervalNote = minDivisionInterval > 0 ? "auto=" + minDivisionInterval : null;
			}

			// Pre-flight sanity check: if the EXISTING graph (produced by Phase 1 / adaptive_track) shows
			// implausibly heavy branching, Phase 1 almost certainly produced spurious divisions from
			// linking errors, not real biology. Catch this BEFORE the full per-timepoint correction pass
			// below — grinding through every timepoint only to discover the problem at the very end (in
			// the topology report) is wasteful; re-running Phase 1 is the actual fix, not correcting the
			// bad result. "Implausible" divisions (see countImplausibleDivisions) are ones that ALSO fail
			// basic division biology — too soon after the mother's own division, or no daughter is
			// meaningfully smaller than the mother — so a genuinely fast-but-plausible-looking dividing
			// lineage does not trip this check. Two independent triggers, checked regardless of each
			// other:
			//  (a) a learned profile explicitly says divisions should be RARE, yet observed implausible
			//      branching is far (3x) above that learned rate;
			//  (b) REGARDLESS of any profile, implausible branching is above a rate no real cell
			//      population plausibly sustains — this catches profile-less / cold-start datasets too,
			//      where dissolveExcessDivisions itself stays off by default to protect genuinely-dividing
			//      lineages.
			{
				final int totalSpots = AutoCorrectOrchestrator.countAllSpots( context );
				final int implausibleDivisions =
						AutoCorrectOrchestrator.countImplausibleDivisions( context, minDivisionInterval );
				final double observedRate = totalSpots > 0 ? ( double ) implausibleDivisions / totalSpots : 0.0;

				String reason = null;
				if ( AutoCorrectOrchestrator.divisionsAreRare( profile ) )
				{
					final double expectedRate = !Double.isNaN( profile.divisionRate ) ? profile.divisionRate : 0.05;
					if ( implausibleDivisions >= 10 && observedRate > 3 * Math.max( expectedRate, 0.01 ) )
						reason = String.format(
								"the learned profile says divisions should be RARE (expected ~%.0f%%)",
								expectedRate * 100 );
				}
				else if ( implausibleDivisions >= 10 && observedRate > ABSOLUTE_SUSPICIOUS_DIVISION_RATE )
				{
					reason = String.format(
							"no real cell population plausibly has %.0f%% of cells simultaneously dividing",
							ABSOLUTE_SUSPICIOUS_DIVISION_RATE * 100 );
				}

				if ( reason != null )
				{
					return String.format(
							"auto.correct ABORTED before processing: found %d implausible division(s) "
									+ "(too soon after the mother's own division, or no daughter smaller than "
									+ "the mother) among %d spots (%.0f%% branching), but %s. This almost "
									+ "certainly means Phase 1 (adaptive_track) produced spurious divisions "
									+ "from linking errors, not real biology — running auto.correct now would "
									+ "spend time correcting a bad graph end-to-end instead of fixing the root "
									+ "cause. Re-run adaptive_track (Phase 1) first.",
							implausibleDivisions, totalSpots, observedRate * 100, reason );
				}
			}

			// Correct all timepoints of the run's range (RunRange): adaptive_track holds itself to it as a hard
			// bound, and this pass did not -- a short benchmark run over t=120..159 spent most of its time
			// correcting the 210 frames outside, and changed them. The whole-movie measurements above still read
			// every frame.
			final int[] range = RunRange.bounds( context, numTimepoints );
			final int minTp = range[ 0 ], maxTp = range[ 1 ];
			if ( RunRange.restricts( range, numTimepoints ) )
				context.log( String.format( "  auto.correct: the run's range t=%d..%d (dataset is t=0..%d).", minTp,
						maxTp, numTimepoints - 1 ) );
			int totalMerged = 0, totalFilled = 0, totalDeletedLinks = 0, totalDeletedSpots = 0, totalRefit = 0;
			for ( int t = minTp; t <= maxTp; t++ )
			{
				viewer.state().setCurrentTimepoint( t );
				final CorrectionResult r = AutoCorrectOrchestrator.correctTimepoint(
						context, t, numTimepoints, settings, minTp );
				totalMerged += r.mergedSpots;
				totalFilled += r.filledGaps;
				totalDeletedLinks += r.deletedLinks;
				totalDeletedSpots += r.deletedSpots;
				totalRefit += r.refitSpots;
			}
			// Global post-processing: remove spots isolated by cross-timepoint effects
			totalDeletedSpots += AutoCorrectOrchestrator.globalCleanup( context, numTimepoints );

			// Topology, over the whole movie, through the SAME sequence the windowed pass uses.
			//
			// This used to be an open-coded partial copy of it, and the copy had drifted badly. It ran
			// the rapid-division check BEFORE reconnectOrphanTracklets -- which is the pass that CREATES
			// divisions, by giving a predecessor that already has one child a second one -- so every
			// division reconnection made went unchecked. It then de-branched only when the profile said
			// divisions were RARE, which with no profile is never, so nothing judged them afterwards
			// either. And it never ran deleteUnstableSpots at all. A run reported exactly that:
			// "divisions=167, min_div_interval=1 (threshold=auto=29)" -- divisions one timepoint apart,
			// against a threshold of 29, after Phase 1 had finished with a clean lineage.
			//
			// So the short noise tracks, the implausible branchings and the border fragments that
			// Phase 1 removes were all being re-created here and never removed again. runTopologyConsistency
			// is the sequence that has those passes, in the order that works; there is no reason for a
			// second one.
			final String consistency =
					AutoCorrectOrchestrator.runTopologyConsistency( context, minTp, maxTp + 1, settings );

			// Report topology quality
			final int[] topo = AutoCorrectOrchestrator.computeTopologyMetrics( context );
			final int trifurcations = AutoCorrectOrchestrator.countTrifurcations( context );
			final String topoStr = topo[ 0 ] == 0 ? "no divisions"
					: String.format( "divisions=%d, min_div_interval=%s%s",
							topo[ 0 ],
							topo[ 1 ] == Integer.MAX_VALUE ? "n/a" : String.valueOf( topo[ 1 ] ),
							divIntervalNote != null ? " (threshold=" + divIntervalNote + ")" : "" );
			final String trifStr = trifurcations > 0
					? "; WARNING: " + trifurcations + " trifurcation(s) remain (spots with ≥3 daughters — linking errors)"
					: "";
			final String constraintsStr = constraintsNote.length() > 0
					? "; applied learned constraints:" + constraintsNote : "";
			return String.format(
					"auto.correct all %d timepoints: merged=%d, filled=%d, deleted links=%d, "
							+ "deleted spots=%d, refit=%d; consistency: %s; topology: %s%s%s",
					numTimepoints, totalMerged, totalFilled, totalDeletedLinks,
					totalDeletedSpots, totalRefit, consistency, topoStr, trifStr, constraintsStr );
		}
		else
		{
			viewer.state().setCurrentTimepoint( tParam );
			final CorrectionResult r = AutoCorrectOrchestrator.correctTimepoint(
					context, tParam, numTimepoints, settings );
			return "auto.correct t=" + tParam + ": " + r;
		}
	}

	private static boolean parseBool( final Object val )
	{
		if ( val instanceof Boolean ) return ( Boolean ) val;
		return Boolean.parseBoolean( val.toString() );
	}

	/**
	 * Loads the learned lineage-constraint profile pointed to by
	 * {@link AutomahoutSettings#getConstraintsProfilePath()}, or {@code null} if none is configured or
	 * it cannot be read (in which case correction proceeds with its built-in defaults/heuristics).
	 */
	private static LineageConstraintProfile loadActiveProfile( final AutomahoutSettings settings )
	{
		return LineageConstraintProfile.loadForDataset(
				settings.getConstraintsProfilePath(),
				AutoCorrectOrchestrator.currentElephantDataset(), null );
	}
}
