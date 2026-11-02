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

import static org.mastodon.mamut.automahout.tool.CorrectionPass.Effect.NONE;
import static org.mastodon.mamut.automahout.tool.CorrectionPass.Stage.LINEAGE;
import static org.mastodon.mamut.automahout.tool.ToolRole.CORRECT_DETECTIONS;
import static org.mastodon.mamut.automahout.tool.ToolRole.CORRECT_LINEAGE;
import static org.mastodon.mamut.automahout.tool.ToolRole.PROFILE;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.mastodon.mamut.automahout.eval.LineageConstraintProfile;
import org.mastodon.mamut.automahout.tool.Applicability;
import org.mastodon.mamut.automahout.tool.BuiltinPass;
import org.mastodon.mamut.automahout.tool.CorrectionPass;
import org.mastodon.mamut.automahout.tool.PassScope;

/**
 * The lineage correction plan ({@link CorrectionPass.Stage#LINEAGE}), in the order
 * {@link AutoCorrectOrchestrator#runTopologyConsistency} has always run it, with the reasons for that order.
 * {@link #THRESHOLDS} is a measuring step, not a correction: it reads the graph AFTER the merge repair, as it
 * always has, and leaves the caps the passes after it use in the scope.
 */
final class LineagePasses
{
	private LineagePasses()
	{}

	static final String UNLINK_FP = "lineage.unlink_fp";

	static final String CONVERGED = "lineage.converged_tracks";

	static final String THRESHOLDS = "lineage.thresholds";

	static final String RAPID_DIVISIONS = "lineage.rapid_divisions";

	static final String SHORT_BRANCHES = "lineage.short_branches";

	static final String RELINK_FREE = "lineage.relink_free";

	static final String RECONNECT = "lineage.reconnect_orphans";

	static final String CLOSE_GAPS = "lineage.close_gaps";

	static final String EXCESS_DIVISIONS = "lineage.excess_divisions";

	static final String STAYING_DIVISIONS = "lineage.staying_divisions";

	static final String IMPLAUSIBLE_DIVISIONS = "lineage.implausible_divisions";

	static final String UNSTABLE = "lineage.unstable_spots";

	static final String CO_LOCATED = "lineage.co_located_pairs";

	/** Scope values {@link #THRESHOLDS} measures. */
	static final String MAX_DISPLACEMENT = "lineage.max_displacement";

	static final String MIN_BRANCH_LENGTH = "lineage.min_branch_length";

	static final String MIN_DIVISION_INTERVAL = "lineage.min_division_interval";

	static final String DIVISIONS_RARE = "lineage.divisions_rare";

	/** {@link #CONVERGED}'s second number: merge episodes (its count is the frames repaired). */
	static final String CONVERGED_EPISODES = "lineage.converged_episodes";

	private static boolean divisionsRare( final PassScope s )
	{
		return Boolean.TRUE.equals( s.get( DIVISIONS_RARE ) );
	}

	static final List< CorrectionPass > BUILT_IN = Collections.unmodifiableList( Arrays.asList(

			// fp spots are detection-training negatives and must NEVER be part of a track. Strip any edges they
			// carry (from a graph built before this rule, or any un-guarded linking path) up front, so the rest
			// of the topology cleanup operates on a clean, fp-free link structure.
			new BuiltinPass( UNLINK_FP, LINEAGE, CORRECT_LINEAGE, NONE,
					"Strips every link from spots tagged fp (training negatives).",
					s -> AutoCorrectOrchestrator.unlinkFpSpots( s.ctx, s.numTimepoints ) ),

			// Before anything below judges divisions or branches: a merge of two cells into one detection
			// is what ENDS one track and later makes the survivor "divide". Repaired here, the passes below
			// see two tracks instead of a break and a false division.
			// The repair is the newest graph surgery here, and the user may have edited by hand in it; the
			// division passes below are what hung on damaged link lists in run 20261004-133403.
			new BuiltinPass( CONVERGED, LINEAGE, CORRECT_DETECTIONS, NONE,
					"Splits one detection that two tracks ran into, from the frame before the merge.", s -> {
						final int[] converged = AutoCorrectOrchestrator.repairConvergedTracks( s.ctx, s.scanStartTp,
								s.numTimepoints );
						s.put( CONVERGED_EPISODES, converged[ 1 ] );
						return converged[ 0 ];
					} ).onlyFor( d -> Boolean.FALSE.equals( d.fluorescenceLike )
							? Applicability.notFor( "the outline is a brightness threshold; this movie does not read as fluorescence" )
							: Applicability.suits( 0.5, d.volumetric
									? "3D: the same lineage rules with a 3-D fit, never asked (a question shows one plane)"
									: "2D, fluorescence (or not measured yet: the pass measures it)" ) )
					.thenCheck( "the merge repair" ),

			new BuiltinPass( THRESHOLDS, LINEAGE, PROFILE, NONE,
					"Measures the caps the passes below use: per-frame displacement, branch length, division interval.",
					LineagePasses::measureThresholds ),

			new BuiltinPass( RAPID_DIVISIONS, LINEAGE, CORRECT_LINEAGE, NONE,
					"Dissolves a division that follows the previous one sooner than the minimum interval.",
					s -> AutoCorrectOrchestrator.removeRapidSuccessiveDivisions( s.ctx, s.scanStartTp, s.numTimepoints,
							s.getInt( MIN_DIVISION_INTERVAL, 0 ) ) )
					.when( s -> s.getInt( MIN_DIVISION_INTERVAL, 0 ) > 0 )
					.thenCheck( "removing rapid successive divisions" ),

			new BuiltinPass( SHORT_BRANCHES, LINEAGE, CORRECT_LINEAGE, NONE,
					"Deletes a post-division branch shorter than the minimum branch length.",
					s -> AutoCorrectOrchestrator.deleteShortPostDivisionBranches( s.ctx, s.scanStartTp, s.numTimepoints,
							s.getInt( MIN_BRANCH_LENGTH, 0 ) ) )
					.when( s -> s.getInt( MIN_BRANCH_LENGTH, 0 ) > 0 )
					.thenCheck( "deleting short post-division branches" ),

			// Before the reconnection below takes the free ends and starts for itself: a link that went to a
			// neighbour while the cell's own detection sat free beside it.
			new BuiltinPass( RELINK_FREE, LINEAGE, CORRECT_LINEAGE, NONE,
					"Moves a link that went to a neighbour onto the cell's own free detection.",
					s -> AutoCorrectOrchestrator.relinkToFreeDetections( s.ctx, s.scanStartTp, s.numTimepoints ) )
					.thenCheck( "moving links to free detections" ),

			// Reconnect breaks, after pruning, so it only bridges survivors. Generous radius (3x spot size).
			new BuiltinPass( RECONNECT, LINEAGE, CORRECT_LINEAGE, NONE,
					"Joins a track end to a track start in the next frame within 3 radii.",
					s -> AutoCorrectOrchestrator.reconnectOrphanTracklets( s.ctx, s.scanStartTp, s.numTimepoints, 3.0,
							s.getDouble( MAX_DISPLACEMENT, 0.0 ) ) )
					.thenCheck( "reconnecting orphan tracklets" ),

			// Then the breaks adjacent-timepoint reconnection cannot reach at all: a cell the detector
			// missed for two or more consecutive frames. Runs AFTER the pass above so a one-frame join is
			// always preferred over a gap-spanning one.
			new BuiltinPass( CLOSE_GAPS, LINEAGE, CORRECT_LINEAGE, NONE,
					"Joins a track end to a start up to 3 frames later.",
					s -> AutoCorrectOrchestrator.closeTrackGaps( s.ctx, s.scanStartTp, s.numTimepoints - 1, 3.0,
							s.getDouble( MAX_DISPLACEMENT, 0.0 ), AutoCorrectOrchestrator.MAX_LINK_GAP ) )
					.thenCheck( "closing track gaps" ),

			// De-branch LAST: when divisions are learned-rare, force every remaining branching (including any
			// created by reconnection, and long spurious ones that short-branch deletion misses) into a single
			// track. Skipped when the profile lacks a rarity signal, so genuinely dividing lineages are safe.
			new BuiltinPass( EXCESS_DIVISIONS, LINEAGE, CORRECT_LINEAGE, NONE,
					"Forces every branching into one track (only when divisions are learned to be rare).",
					s -> AutoCorrectOrchestrator.dissolveExcessDivisions( s.ctx, s.scanStartTp, s.numTimepoints ) )
					.when( LineagePasses::divisionsRare ),

			// No rarity signal (no profile, or the lineage genuinely divides): force-cutting every
			// branch would destroy real mitoses, so instead judge each division on its own merits —
			// same criteria as countImplausibleDivisions (refractory interval + morphology) — and cut
			// only the ones that fail. This is what catches false divisions that linkDivisionDaughters/
			// linkOrphanStarts fabricate when an orphan's own true predecessor went undetected, without
			// touching a genuinely dividing lineage.
			// First the ones whose daughters do not move apart, asked about where a dialog can be shown.
			new BuiltinPass( STAYING_DIVISIONS, LINEAGE, CORRECT_LINEAGE, NONE,
					"Cuts a division whose daughters do not move apart (asks where it can).",
					s -> AutoCorrectOrchestrator.dissolveStayingDivisions( s.ctx, s.scanStartTp, s.numTimepoints ) )
					.when( s -> !divisionsRare( s ) )
					.thenCheck( "the division check" ),

			new BuiltinPass( IMPLAUSIBLE_DIVISIONS, LINEAGE, CORRECT_LINEAGE, NONE,
					"Cuts a division that fails the refractory interval or the daughters' morphology.",
					s -> AutoCorrectOrchestrator.dissolveImplausibleDivisions( s.ctx, s.scanStartTp, s.numTimepoints,
							s.getInt( MIN_DIVISION_INTERVAL, 0 ) ) )
					.when( s -> !divisionsRare( s ) ),

			// Temporal-stability cleanup (runs LAST, once tracks are finalised): a detection whose whole track
			// spans fewer than STABLE_TRACK_MIN_TP timepoints is an unstable flicker, not a cell — deleted
			// outright (never tagged fp — see deleteUnstableSpots's doc for why an actual detection result
			// must never be turned into a declared background negative).
			// ...and a stricter span for a lineage that reaches the edge of the field of view: an interior
			// fragment is a linking failure worth repairing, one at the image edge is a cell walking out of
			// frame. See BORDER_FRAGMENT_MIN_TP for what that costs on ground truth.
			new BuiltinPass( UNSTABLE, LINEAGE, CORRECT_DETECTIONS, NONE,
					"Deletes tracks shorter than 3 timepoints, or 10 at the image edge.",
					s -> AutoCorrectOrchestrator.deleteUnstableSpots( s.ctx, s.scanStartTp, s.numTimepoints - 1,
							AutoCorrectOrchestrator.STABLE_TRACK_MIN_TP, AutoCorrectOrchestrator.BORDER_FRAGMENT_MIN_TP ) ),

			// Over-segmentation that no single frame can diagnose: two detections tiling ONE cell, about
			// 1.8 cell-radii apart, holding that offset while a real division's daughters drift apart.
			// Runs here because it needs finished tracks — see mergePersistentCoLocatedPairs.
			new BuiltinPass( CO_LOCATED, LINEAGE, CORRECT_DETECTIONS, NONE,
					"Merges two tracks that hold a steady sub-cell offset (one cell tiled twice).",
					s -> AutoCorrectOrchestrator.mergePersistentCoLocatedPairs( s.ctx, s.scanStartTp,
							s.numTimepoints - 1 ) ) ) );

	/** {@link #THRESHOLDS}: the caps, from the learned profile where one exists for this dataset, else measured. */
	private static int measureThresholds( final PassScope s )
	{
		final LineageConstraintProfile profile = AutoCorrectOrchestrator.loadConstraintProfile( s.settings );

		// Absolute per-frame displacement cap for reconnection (learned P99, fallback observed max).
		double maxDisplacement = 0.0;
		if ( profile != null )
		{
			if ( !Double.isNaN( profile.displacementP99 ) && profile.displacementP99 > 0.0 )
				maxDisplacement = profile.displacementP99;
			else if ( !Double.isNaN( profile.displacementMax ) && profile.displacementMax > 0.0 )
				maxDisplacement = profile.displacementMax;
		}
		// ...and, with no profile, the cap MEASURED on this dataset — which the settings object handed
		// to this method has been carrying all along. Three other consumers read it from there
		// (pruneLongLinks and both linkOrphanStarts calls); this one read the profile or nothing, so a
		// run without a profile for its dataset reported max_disp=0.0 while the rest of correction had
		// a measured number. See estimateMaxDisplacement.
		if ( maxDisplacement <= 0.0 && s.settings != null )
			maxDisplacement = Math.max( 0.0, s.settings.getCorrectMaxDisplacement() );
		// ...and if that is still nothing, MEASURE it here rather than running uncapped. The windowed
		// caller estimates a cap per window and writes it into its own settings object; the whole-movie
		// caller only does so when a learned profile exists, and a profile is refused unless it was
		// learned on this dataset — which is the normal case. So Phase 2 ran every reconnection with
		// max_disp=0.0 while Phase 1 had 5.8-7.7 µm, and a link was then bounded only by a multiple of
		// the spot's radius: up to nine radii once closeTrackGaps scales it by the gap. That is the
		// "obviously wrong track joining scattered timepoints at the image edge" in the viewer, and the
		// run printed the cause: "reconnected tracklets=149, closed gaps=59 ... max_disp=0.0".
		if ( maxDisplacement <= 0.0 )
			maxDisplacement = AutoCorrectOrchestrator.measureMaxDisplacement( s.ctx, Math.max( 0, s.scanStartTp ),
					s.numTimepoints - 1 );

		// A post-division branch shorter than the shortest real GT track segments is implausible.
		// Skipped under sparse GT, where segment length reflects annotation gaps, not real biology.
		int minBranchLength = 0;
		if ( profile != null && !profile.sparseAnnotation
				&& !Double.isNaN( profile.trackSegmentLengthP05 ) && profile.trackSegmentLengthP05 >= 2.0 )
			minBranchLength = ( int ) Math.round( profile.trackSegmentLengthP05 );
		else
			// No profile for this dataset, which is the normal case — one learned elsewhere is refused
			// on purpose. It used to mean this check simply did not run, and the log said so all run:
			// min_branch_len=off, while the short side-branches it exists to remove are the first thing
			// a person sees in the lineage. STABLE_TRACK_MIN_TP is the claim this project already makes
			// about a track too short to be a cell; a branch off a division is the same claim in the
			// same units, so it is a floor, not a guess. A learned profile still overrides it.
			minBranchLength = AutoCorrectOrchestrator.STABLE_TRACK_MIN_TP;

		// Minimum inter-division interval: learned value, else estimate from count growth. This estimate
		// is a global biological constant for the whole movie, so it always looks at the full range
		// regardless of scanStartTp (it is also cheap — a per-timepoint spot count, not a graph walk).
		final int minDivisionInterval;
		if ( profile != null && !Double.isNaN( profile.divisionIntervalMin )
				&& profile.divisionIntervalMin >= 1.0 )
			minDivisionInterval = ( int ) Math.round( profile.divisionIntervalMin );
		else
			minDivisionInterval = AutoCorrectOrchestrator.estimateMinDivisionInterval( s.ctx, 0, s.numTimepoints - 1 );

		s.put( MAX_DISPLACEMENT, maxDisplacement );
		s.put( MIN_BRANCH_LENGTH, minBranchLength );
		s.put( MIN_DIVISION_INTERVAL, minDivisionInterval );
		s.put( DIVISIONS_RARE, AutoCorrectOrchestrator.divisionsAreRare( profile ) );
		return 0;
	}
}
