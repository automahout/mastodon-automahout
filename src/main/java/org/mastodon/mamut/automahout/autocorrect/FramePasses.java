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

import static org.mastodon.mamut.automahout.tool.CorrectionPass.Effect.GAPS_FILLED;
import static org.mastodon.mamut.automahout.tool.CorrectionPass.Effect.LINKS_DELETED;
import static org.mastodon.mamut.automahout.tool.CorrectionPass.Effect.RELINKED;
import static org.mastodon.mamut.automahout.tool.CorrectionPass.Effect.SPOTS_DELETED;
import static org.mastodon.mamut.automahout.tool.CorrectionPass.Effect.SPOTS_MERGED;
import static org.mastodon.mamut.automahout.tool.CorrectionPass.Effect.SPOTS_REFIT;
import static org.mastodon.mamut.automahout.tool.CorrectionPass.Stage.FRAME;
import static org.mastodon.mamut.automahout.tool.ToolRole.CORRECT_DETECTIONS;
import static org.mastodon.mamut.automahout.tool.ToolRole.CORRECT_LINEAGE;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.mastodon.mamut.automahout.tool.BuiltinPass;
import org.mastodon.mamut.automahout.tool.CorrectionPass;
import org.mastodon.mamut.automahout.tool.PassScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-timepoint correction plan ({@link CorrectionPass.Stage#FRAME}), in the order
 * {@link AutoCorrectOrchestrator#correctTimepoint} has always run it. Each pass is one of the orchestrator's
 * rules; the comments that explained each step there are here, with the step.
 */
final class FramePasses
{
	private FramePasses()
	{}

	private static final Logger log = LoggerFactory.getLogger( AutoCorrectOrchestrator.class );

	/** Neither the first nor the last timepoint: the pre-filters judge a spot by its neighbours in time. */
	private static boolean interior( final PassScope s )
	{
		return s.timepoint > 0 && s.timepoint < s.numTimepoints - 1;
	}

	/**
	 * The last timepoint this call covers, where a spot cannot have a forward link yet because nothing past it
	 * has been detected or linked. A cell that first appears there is indistinguishable from a flicker until the
	 * next window runs, and the two are not equally costly to get wrong: keeping one leaves a spot the next
	 * window can link, deleting one removes a cell that was about to be tracked.
	 */
	private static boolean atFrontier( final PassScope s )
	{
		// Also when nothing at this timepoint is linked forward: the window loop passes the movie's length
		// as numTimepoints, so the end of a window -- where nothing past it has been detected yet -- was never
		// the frontier, and every track there read as ended (Fluo-N3DH-CE 01 t=149: 8 of the 14 real cells
		// short_tracklets removed in a ten-frame slice).
		return s.timepoint >= s.numTimepoints - 1 || !AutoCorrectOrchestrator.anyLinkedForward( s.ctx, s.timepoint );
	}

	/** The earliest timepoint a gap bridge ending here may write to; see the bridge passes. */
	static int bridgeFrom( final PassScope s )
	{
		return Math.max( Math.max( 0, s.earliestWritableTp ), s.timepoint - AutoCorrectOrchestrator.MAX_LINK_GAP );
	}

	static final String LONG_LINKS = "frame.long_links";

	static final String REFIT = "elephant.refit_anisotropic";

	static final List< CorrectionPass > BUILT_IN = Collections.unmodifiableList( Arrays.asList(

			// 0. Pre-filter: remove weak and small (noise) spots before merging/linking
			new BuiltinPass( "frame.weak_spots", FRAME, CORRECT_DETECTIONS, SPOTS_DELETED,
					"Deletes spots far dimmer than the frame's cells (pre-filter).", s -> {
						final int weak = AutoCorrectOrchestrator.deleteWeakSpots( s.ctx, s.timepoint,
								s.settings.getCorrectMinIntensityRatio(), s.settings.getCorrectQualityThreshold() );
						log.info( "t={} — pre-filter: deleted {} weak spot(s)", s.timepoint, weak );
						return weak;
					} ).when( s -> ( s.settings.getCorrectMinIntensityRatio() > 0.0
							|| s.settings.getCorrectQualityThreshold() > 0.0 ) && interior( s ) ),

			new BuiltinPass( "frame.small_spots", FRAME, CORRECT_DETECTIONS, SPOTS_DELETED,
					"Deletes spots far smaller than the frame's cells (pre-filter).", s -> {
						final int small = AutoCorrectOrchestrator.deleteSmallSpots( s.ctx, s.timepoint,
								s.settings.getCorrectMinSizeRatio() );
						log.info( "t={} — pre-filter: deleted {} small spot(s)", s.timepoint, small );
						return small;
					} ).when( s -> s.settings.getCorrectMinSizeRatio() > 0.0 && interior( s ) ),

			// 0b. Remove "sandwiched" spurious spots — a detection wedged between two other spots on
			// opposite sides (e.g. a phantom cell detected between two cells that are adjacent along Z).
			// Removing it (with its edges) before merging/linking lets the true neighbours reconnect.
			new BuiltinPass( "frame.sandwiched", FRAME, CORRECT_DETECTIONS, SPOTS_DELETED,
					"Deletes a detection wedged between two spots on opposite sides.", s -> {
						final int sandwiched = AutoCorrectOrchestrator.removeSandwichedSpots( s.ctx, s.timepoint, 1.0 );
						if ( sandwiched > 0 )
							log.info( "t={} — removed {} sandwiched spurious spot(s)", s.timepoint, sandwiched );
						return sandwiched;
					} ).onlyFor( BuiltinPass::stacksOnly ),

			// 0c. Two detections on one nucleus, one inside the other (on a stack: stacked in z). The weaker goes.
			new BuiltinPass( "frame.stacked_duplicates", FRAME, CORRECT_DETECTIONS, SPOTS_DELETED,
					"Deletes the weaker of two detections where one's centre lies inside the other.", s -> {
						final int dup = AutoCorrectOrchestrator.removeStackedDuplicates( s.ctx, s.timepoint );
						if ( dup > 0 )
							log.info( "t={} — removed {} stacked duplicate(s)", s.timepoint, dup );
						return dup;
					} ).onlyFor( BuiltinPass::stacksOnly ),

			// 1. Merge overlapping duplicate spots
			new BuiltinPass( "frame.merge_duplicates", FRAME, CORRECT_DETECTIONS, SPOTS_MERGED,
					"Merges spots that overlap by more than the merge ratio.", s -> {
						final int merged = AutoCorrectOrchestrator.mergeDuplicateSpots( s.ctx, s.timepoint,
								s.settings.getCorrectMergeOverlapRatio() );
						log.info( "t={} — merged {} duplicate spot(s)", s.timepoint, merged );
						return merged;
					} ).when( s -> s.settings.getCorrectMergeOverlapRatio() > 0.0 ),

			// 2. Prune long links (only timepoints that have predecessors)
			new BuiltinPass( LONG_LINKS, FRAME, CORRECT_LINEAGE, LINKS_DELETED,
					"Cuts links into this frame longer than a cell moves.", s -> {
						final int pruned = AutoCorrectOrchestrator.pruneLongLinks( s.ctx, s.timepoint,
								s.settings.getCorrectLongLinkFactor(), s.settings.getCorrectMaxDisplacement() );
						log.info( "t={} — pruned {} long link(s)", s.timepoint, pruned );
						return pruned;
					} ).when( s -> s.timepoint > 0 ),

			// 2. Re-link orphaned spots
			new BuiltinPass( "frame.relink", FRAME, CORRECT_LINEAGE, RELINKED,
					"Re-links the spots the long-link cut left without a predecessor.", s -> {
						final int relinked = AutoCorrectOrchestrator.relink( s.ctx, s.timepoint ) ? 1 : 0;
						log.info( "t={} — re-linking {}", s.timepoint, relinked > 0 ? "succeeded" : "failed" );
						return relinked;
					} ).when( s -> s.count( LONG_LINKS ) > 0 ),

			// 2.5: resolve convergences (spots with >=2 incoming edges — illegal, cell lineages never merge
			//      across time, only divide) by keeping the nearest-predecessor link and cutting the rest.
			//      Never fuses the predecessor spots themselves — see pruneConvergenceLinks.
			new BuiltinPass( "frame.convergence_links", FRAME, CORRECT_LINEAGE, LINKS_DELETED,
					"Keeps one incoming link per spot, the nearest; lineages never merge.", s -> {
						final int pruned = AutoCorrectOrchestrator.pruneConvergenceLinks( s.ctx, s.timepoint );
						if ( pruned > 0 )
							log.info( "t={} — pruned {} extra convergence link(s)", s.timepoint, pruned );
						return pruned;
					} ).when( s -> s.timepoint > 0 ),

			// 2.5c: prune trifurcations — spots with ≥3 outgoing edges are biologically
			//       impossible (cell division is binary). Keep only the 2 nearest daughters.
			new BuiltinPass( "frame.trifurcations", FRAME, CORRECT_LINEAGE, LINKS_DELETED,
					"Keeps the two nearest daughters of a spot with three or more.", s -> {
						final int triPruned = AutoCorrectOrchestrator.pruneTrifurcations( s.ctx, s.timepoint );
						if ( triPruned > 0 )
							log.info( "t={} — pruned {} trifurcation edge(s)", s.timepoint, triPruned );
						return triPruned;
					} ),

			// 2.55: recover missing predecessors at t-1 (backward gap-fill). A cell missed at t-1 leaves
			// its successor at t with no true predecessor, which then either forms a FALSE division
			// (force-linked to a neighbour's mother) or stays isolated. Here we locally re-detect the
			// missing cell at t-1 and relink, dissolving false divisions and connecting isolated spots.
			// Runs under the same gate as gap-filling (whole-track context of the global auto.correct).
			new BuiltinPass( "elephant.recover_predecessors", FRAME, CORRECT_DETECTIONS, GAPS_FILLED,
					"Re-detects a cell missed at t-1 with ELEPHANT, around a successor that has no predecessor.", s -> {
						final int recovered = AutoCorrectOrchestrator.recoverMissingPredecessors( s.ctx, s.timepoint,
								s.settings.getCorrectGapCropFactor() );
						if ( recovered > 0 )
							log.info( "t={} — recovered {} missing predecessor(s) (dissolved false divisions / "
									+ "connected isolated spots)", s.timepoint, recovered );
						return recovered;
					} ).when( s -> s.settings.isCorrectFillGaps() && s.timepoint > 0 )
					.onlyFor( BuiltinPass::elephantDetects ),

			// 2.6: link track-start spots to a nearby dividing mother cell at t-1
			new BuiltinPass( "frame.division_daughters", FRAME, CORRECT_LINEAGE, GAPS_FILLED,
					"Links a track start to a nearby dividing mother at t-1.", s -> {
						final int linked = AutoCorrectOrchestrator.linkDivisionDaughters( s.ctx, s.timepoint,
								s.settings.getCorrectDivisionLinkFactor() );
						if ( linked > 0 )
							log.info( "t={} — linked {} division daughter(s)", s.timepoint, linked );
						return linked;
					} ).when( s -> s.settings.getCorrectDivisionLinkFactor() > 0.0 && s.timepoint > 0 ),

			// 2.7: link remaining orphan starts to any available predecessor at t-1.
			// Covers detection gaps and window-boundary orphans that linkDivisionDaughters
			// misses (it only handles the 2nd-daughter case for dividing cells).
			// Search radius 2.0× ensures only spatially plausible links are added.
			// The learned per-frame displacement cap is passed: without it, the search radius is only
			// searchFactor × cell radius, so a big/bright cell's large radius admits a wrong link to a far
			// spot (the cap is an absolute bound on how far a cell can move between frames).
			new BuiltinPass( "frame.orphan_starts", FRAME, CORRECT_LINEAGE, GAPS_FILLED,
					"Links a remaining track start to a free predecessor at t-1 within 2 radii.", s -> {
						final int orphanLinked = AutoCorrectOrchestrator.linkOrphanStarts( s.ctx, s.timepoint, 2.0, false,
								s.settings.getCorrectMaxDisplacement() );
						if ( orphanLinked > 0 )
							log.info( "t={} — linked {} orphan start(s) to predecessors", s.timepoint, orphanLinked );
						return orphanLinked;
					} ).when( s -> s.timepoint > 0 ),

			// 3a. Delete short dangling tracklets (independent of gap-filling)
			new BuiltinPass( "frame.short_tracklets", FRAME, CORRECT_DETECTIONS, SPOTS_DELETED,
					"Deletes dangling tracklets shorter than the minimum length.", s -> {
						final int shortDeleted = AutoCorrectOrchestrator.deleteShortDanglingTracklets( s.ctx, s.timepoint,
								s.settings.getCorrectMinTrackletLength() );
						log.info( "t={} — deleted {} short-tracklet spot(s)", s.timepoint, shortDeleted );
						return shortDeleted;
					} ).when( s -> s.settings.getCorrectMinTrackletLength() > 0 && !atFrontier( s ) ),

			// 3b. Fill remaining dangling ends via ELEPHANT at t+1 (only when enabled)
			new BuiltinPass( "elephant.fill_gaps", FRAME, CORRECT_DETECTIONS, GAPS_FILLED,
					"Re-detects with ELEPHANT at t+1 where a track ends here.", s -> {
						final int filled = AutoCorrectOrchestrator.fillGaps( s.ctx, s.timepoint, s.numTimepoints,
								s.settings.getCorrectGapCropFactor() );
						log.info( "t={} — filled {} gap(s)", s.timepoint, filled );
						return filled;
					} ).when( s -> s.settings.isCorrectFillGaps() && s.timepoint < s.numTimepoints - 1 )
					.onlyFor( BuiltinPass::elephantDetects ),

			// 3c. Rescue fully-isolated spots (no links at all) by linking them to a nearby dividing
			// mother at t-1 with room (<2 outgoing), using a more generous radius. This connects
			// left-behind post-division daughters (e.g. at the last timepoint) to their origin instead
			// of deleting them below. Runs before the isolated-spot deletion.
			new BuiltinPass( "frame.isolated_rescue", FRAME, CORRECT_LINEAGE, GAPS_FILLED,
					"Links a spot with no links to a mother at t-1 with room, within 3 radii.", s -> {
						final int rescued = AutoCorrectOrchestrator.linkOrphanStarts( s.ctx, s.timepoint, 3.0, true,
								s.settings.getCorrectMaxDisplacement() );
						if ( rescued > 0 )
							log.info( "t={} — rescued {} isolated spot(s) → division mother", s.timepoint, rescued );
						return rescued;
					} ).when( s -> s.timepoint > 0 ),

			// 3d. ...and then the break neither of the passes above can reach at all. Both link to t-1 only,
			// so a cell the detector missed for two or three consecutive frames leaves a start here whose
			// predecessor is at t-2 or t-3 — and step 4 is about to delete it as isolated, before [5c/6]'s
			// whole-window gap closing ever sees it. This is that pass, scoped to the timepoints a link
			// from here may reach back to, so it costs one graph scan like every other per-timepoint pass.
			new BuiltinPass( "frame.bridge_gaps", FRAME, CORRECT_LINEAGE, GAPS_FILLED,
					"Bridges a break of 2-3 frames ending here, before isolated spots are deleted.", s -> {
						final int bridged = AutoCorrectOrchestrator.closeTrackGaps( s.ctx, bridgeFrom( s ), s.timepoint, 3.0,
								s.settings.getCorrectMaxDisplacement(), AutoCorrectOrchestrator.MAX_LINK_GAP );
						if ( bridged > 0 )
							log.info( "t={} — bridged {} multi-frame gap(s) before the isolated-spot deletion",
									s.timepoint, bridged );
						return bridged;
					} ).when( s -> s.timepoint - bridgeFrom( s ) >= 2 ),

			// 4. Delete isolated spots — except at the frontier (see atFrontier).
			new BuiltinPass( "frame.isolated_spots", FRAME, CORRECT_DETECTIONS, SPOTS_DELETED,
					"Deletes spots with no links, except at the last timepoint covered.", s -> {
						final int isolated = AutoCorrectOrchestrator.deleteIsolatedSpots( s.ctx, s.timepoint );
						log.info( "t={} — deleted {} isolated spot(s)", s.timepoint, isolated );
						return isolated;
					} ).when( s -> !atFrontier( s ) ),

			// 5. Refit anisotropic spots (optional)
			new BuiltinPass( REFIT, FRAME, CORRECT_DETECTIONS, SPOTS_REFIT,
					"Re-detects elongated spots with ELEPHANT and replaces them.", s -> {
						final int refit = AutoCorrectOrchestrator.refitAnisotropicSpots( s.ctx, s.timepoint,
								s.settings.getCorrectAnisotropyThreshold() );
						log.info( "t={} — refit {} anisotropic spot(s)", s.timepoint, refit );
						return refit;
					} ).when( s -> s.settings.isCorrectRefitAnisotropic() )
					.onlyFor( BuiltinPass::elephantDetects ),

			// 6. Final cleanup: remove spots isolated by cross-timepoint side-effects
			//    (e.g. a neighbour timepoint's correction removed a link to this spot). Same frontier
			//    exemption as step 4.
			//    Refitting replaces a spot and re-links it, so it can leave one isolated that step 3d
			//    never saw. Give those the same chance before deleting them — only when refit actually
			//    did something, so the common path still pays for one gap scan per timepoint, not two.
			new BuiltinPass( "frame.bridge_after_refit", FRAME, CORRECT_LINEAGE, GAPS_FILLED,
					"Bridges the multi-frame gaps refitting opened.", s -> {
						final int bridged = AutoCorrectOrchestrator.closeTrackGaps( s.ctx, bridgeFrom( s ), s.timepoint, 3.0,
								s.settings.getCorrectMaxDisplacement(), AutoCorrectOrchestrator.MAX_LINK_GAP );
						if ( bridged > 0 )
							log.info( "t={} — bridged {} multi-frame gap(s) opened by refitting", s.timepoint, bridged );
						return bridged;
					} ).when( s -> s.count( REFIT ) > 0 && s.timepoint - bridgeFrom( s ) >= 2 ),

			new BuiltinPass( "frame.isolated_final", FRAME, CORRECT_DETECTIONS, SPOTS_DELETED,
					"Deletes spots the passes above left with no links (final cleanup).", s -> {
						final int isolated = AutoCorrectOrchestrator.deleteIsolatedSpots( s.ctx, s.timepoint );
						if ( isolated > 0 )
							log.info( "t={} — final cleanup: deleted {} isolated spot(s)", s.timepoint, isolated );
						return isolated;
					} ).when( s -> !atFrontier( s ) ) ) );
}
