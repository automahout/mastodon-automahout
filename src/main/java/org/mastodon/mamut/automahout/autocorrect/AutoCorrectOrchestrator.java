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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mastodon.mamut.automahout.elephant.DetectionQuality;
import org.mastodon.mamut.automahout.elephant.DetectionTagLabels;
import org.mastodon.mamut.automahout.elephant.ElephantIntegration;
import org.mastodon.mamut.automahout.eval.DetectionAcceptanceProfile;
import org.mastodon.mamut.automahout.eval.LineageConstraintProfile;
import org.elephant.setting.main.ElephantMainSettingsManager;
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.GraphIntegrity;
import org.mastodon.mamut.automahout.tool.GraphWatch;
import org.mastodon.mamut.automahout.tool.CleanupToggles;
import org.mastodon.mamut.automahout.tool.CorrectionEvidence;
import org.mastodon.mamut.automahout.tool.CorrectionPass;
import org.mastodon.mamut.automahout.tool.PassPlan;
import org.mastodon.mamut.automahout.tool.PassScope;
import org.mastodon.mamut.automahout.tool.DetectionGate;
import org.mastodon.mamut.automahout.tool.ExpectedCellCount;
import org.mastodon.mamut.automahout.tool.ImageBounds;
import org.mastodon.mamut.automahout.tool.EllipseFit;
import org.mastodon.mamut.automahout.tool.ImageProfile;
import org.mastodon.mamut.automahout.tool.PhaseTimings;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.automahout.tool.SeedToObject;
import org.mastodon.mamut.automahout.tool.ShapeSplit;
import org.mastodon.mamut.automahout.tool.ShapeSplit3D;
import org.mastodon.mamut.automahout.tool.EllipsoidFit3D;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.UserEdits;
import org.mastodon.mamut.automahout.tool.impl.SpotRefitTool;
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

/**
 * Autonomous correction engine for Automahout tracking results.
 *
 * <p>Applies three correction strategies in order for each timepoint:</p>
 * <ol>
 *   <li><b>Prune long links</b> — deletes incoming links whose Euclidean
 *       distance exceeds {@code factor × max(r_source, r_target)}.</li>
 *   <li><b>Re-link orphaned spots</b> — after pruning, runs ELEPHANT NN
 *       linking to find a closer predecessor for spots that lost their
 *       incoming link.</li>
 *   <li><b>Delete isolated spots</b> — removes spots at non-terminal
 *       timepoints that have neither incoming nor outgoing edges (clear
 *       false positives).</li>
 *   <li><b>Refit anisotropic spots</b> (optional, off by default) — calls
 *       ELEPHANT {@code /seg/predict} with a crop box on spots whose
 *       covariance diagonal ratio exceeds the threshold.</li>
 * </ol>
 */
public class AutoCorrectOrchestrator
{

	private static final Logger log = LoggerFactory.getLogger( AutoCorrectOrchestrator.class );

	// -----------------------------------------------------------------------

	/**
	 * Runs all enabled correction strategies on a single timepoint.
	 *
	 * @param ctx           tool context
	 * @param timepoint     timepoint to correct
	 * @param numTimepoints total number of timepoints (for terminal-timepoint detection)
	 * @param settings      Automahout settings that carry correction thresholds
	 * @return summary of corrections applied
	 */
	public static CorrectionResult correctTimepoint(
			final ToolContext ctx,
			final int timepoint,
			final int numTimepoints,
			final AutomahoutSettings settings )
	{
		return correctTimepoint( ctx, timepoint, numTimepoints, settings, 0 );
	}

	/**
	 * As above, told the first timepoint this correction run is allowed to write to.
	 *
	 * <p>Only the gap bridge needs it, and it needs it badly. Bridging a gap that ends at
	 * {@code timepoint} means creating cells at the timepoints INSIDE the gap, which are earlier ones;
	 * left unbounded it reaches {@link #MAX_LINK_GAP} timepoints back, and from the first timepoints of
	 * a window that is behind the window. Two windows of a run were stopped for exactly that —
	 * "[5/6] autocorrect was scoped to t=10..19 but changed 1 timepoint(s) outside it (net +2 spots,
	 * first at t=8)" and the same at t=28 from t=30..39 — and a stopped window loses every phase after
	 * the one that failed.</p>
	 *
	 * <p>Writing into an already-finalised timepoint is not a formality: that region has had its own
	 * correction, its own consistency pass and its own validation, and a spot appearing there
	 * afterwards is judged by none of them. The whole-movie caller passes 0, because for it there is
	 * no such thing as behind the window.</p>
	 *
	 * @param earliestWritableTp the first timepoint this call may create a spot at
	 */
	public static CorrectionResult correctTimepoint(
			final ToolContext ctx,
			final int timepoint,
			final int numTimepoints,
			final AutomahoutSettings settings,
			final int earliestWritableTp )
	{
		// The rules, their order and their reasons are FramePasses.BUILT_IN; a CorrectionPass plugin can join
		// or replace one of them (PassPlan).
		final PassScope scope = PassScope.frame( ctx, settings, timepoint, numTimepoints, earliestWritableTp );
		final PassPlan plan = PassPlan.forRun( ctx, CorrectionPass.Stage.FRAME, FramePasses.BUILT_IN );
		plan.run( scope );
		return new CorrectionResult( plan.total( scope, CorrectionPass.Effect.SPOTS_MERGED ),
				plan.total( scope, CorrectionPass.Effect.GAPS_FILLED ),
				plan.total( scope, CorrectionPass.Effect.LINKS_DELETED ),
				plan.total( scope, CorrectionPass.Effect.RELINKED ),
				plan.total( scope, CorrectionPass.Effect.SPOTS_DELETED ),
				plan.total( scope, CorrectionPass.Effect.SPOTS_REFIT ) );
	}

	// -----------------------------------------------------------------------
	// Private strategies

	// Package-visible, not private: the gap rule below is the whole reason a two-frame detection miss
	// could never be repaired, and this project has been bitten twice by ratchets that only read the
	// source. CorrectionReplayTest calls it on a real graph instead.
	/**
	 * How much further than an ordinary step a division branch may reach.
	 *
	 * <p>Measured on 94 real divisions and 8374 ordinary steps in CTC tracking truth (seq 01,
	 * {@code benchmark/division-displacement}), against the cap the run learns from ordinary motion:</p>
	 *
	 * <pre>
	 *                    p50    p90    p99    max      (micrometres)
	 *   ordinary step    1.5    4.5    9.1   22.6
	 *   division step   10.6   15.4   20.7   22.6
	 *
	 *   division cap    real divisions cut    ordinary steps it makes reachable
	 *     x1.0                79.3%                        0.00%
	 *     x2.0                15.4%                        2.01%
	 *     x2.5                 4.3%                        2.11%
	 *     x3.0                 0.5%                        2.14%
	 * </pre>
	 *
	 * <p>Three is where it stops mattering: 2.5 to 3.0 buys 3.8 points of real divisions for 0.03 of
	 * exposure. It is also the value {@code division_link_factor} already carries, which is the
	 * parameter that was supposed to cover this and never reached here.</p>
	 */
	static final double DIVISION_DISPLACEMENT_FACTOR = 3.0;

	static int pruneLongLinks(
			final ToolContext ctx,
			final int timepoint,
			final double factor,
			final double maxDisplacement )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Link > toDelete = new ArrayList<>();
		final Spot srcRef = graph.vertexRef();

		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot spot : graph.vertices() )
				{
					if ( spot.getTimepoint() != timepoint )
						continue;
					final boolean targetProtected = ProtectedSpots.isProtected( ctx, model, spot );
					for ( final Link inEdge : spot.incomingEdges() )
					{
						inEdge.getSource( srcRef );
						final int gap = timepoint - srcRef.getTimepoint();
						if ( gap <= 0 )
							continue; // skip invalid links (source not before target)
						if ( targetProtected && ProtectedSpots.isProtected( ctx, model, srcRef ) )
							continue; // a confirmed link between two protected spots is never pruned

						if ( gap > 1 )
						{
							// A TRACK may cross a gap; a LINK may not. closeTrackGaps bridges a gap by
							// filling every timepoint in it with a spot and chaining one link per frame,
							// so a surviving edge that skips a timepoint is not a repair — it is an
							// unreviewable join, invisible in the viewer and stepped over by every pass
							// that walks the lineage one frame at a time. Reported from the viewer as
							// "obviously wrong tracks joining scattered timepoints at the image edge".
							final Link copy = graph.edgeRef();
							copy.refTo( inEdge );
							toDelete.add( copy );
							log.debug( "t={} — gap-spanning link pruned: source at t={} (gap={})",
									timepoint, srcRef.getTimepoint(), gap );
							continue;
						}

						// Apply the spatial distance check. gap is 1 by the time we get here, so these
						// bounds are per-frame, which is what they were measured as.
						final double dx = srcRef.getDoublePosition( 0 ) - spot.getDoublePosition( 0 );
						final double dy = srcRef.getDoublePosition( 1 ) - spot.getDoublePosition( 1 );
						final double dz = srcRef.getDoublePosition( 2 ) - spot.getDoublePosition( 2 );
						final double dist = Math.sqrt( dx * dx + dy * dy + dz * dz );

						final double rSrc = Math.sqrt( srcRef.getBoundingSphereRadiusSquared() );
						final double rTgt = Math.sqrt( spot.getBoundingSphereRadiusSquared() );

						// Prune if the link exceeds either the radius-scaled threshold OR the learned
						// absolute per-frame displacement cap (0 = cap disabled). The cap catches
						// implausibly long moves that a large radius would otherwise permit.
						final double radiusThreshold = factor * Math.max( rSrc, rTgt );
						// A DIVISION is not ordinary motion, and this cap is measured from ordinary
						// motion -- the run sets it to about four times the median consecutive step.
						// Measured on 94 real divisions in CTC tracking truth: an ordinary step runs
						// 1.5 um at the median, a mother-to-daughter step 10.6, and 79.3% of real
						// division branches are longer than the cap. The daughters spring apart; that
						// is what a division looks like.
						//
						// So the branch out of a dividing spot is held to the division cap instead,
						// and nothing else is loosened -- this reads the SOURCE's outgoing degree, so
						// an ordinary link is judged exactly as before. Measured cost at x3: 0.5% of
						// real divisions still cut, against 4.3% at x2.5, for 0.03 points more of the
						// ordinary steps this cap refuses becoming reachable at all.
						final boolean dividing = srcRef.outgoingEdges().size() > 1;
						final boolean over;
						if ( dividing )
							over = dist > Math.max( radiusThreshold, maxDisplacement > 0.0
									? maxDisplacement * DIVISION_DISPLACEMENT_FACTOR : 0.0 );
						else
							over = dist > radiusThreshold
									|| ( maxDisplacement > 0.0 && dist > maxDisplacement );
						if ( over )
						{
							final Link copy = graph.edgeRef();
							copy.refTo( inEdge );
							toDelete.add( copy );
							log.debug( "t={} — long link pruned: dist={} radiusThreshold={} maxDisp={}",
									timepoint, dist, radiusThreshold, maxDisplacement );
						}
					}
				}

				for ( final Link link : toDelete )
					GraphEdits.removeLink( ctx, graph, link, GraphEdits.Reason.TOPOLOGY );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			if ( !toDelete.isEmpty() )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return toDelete.size();
		}
		finally
		{
			graph.releaseRef( srcRef );
			for ( final Link link : toDelete )
				graph.releaseRef( link );
		}
	}

	/**
	 * Whether a spot at {@code timepoint} could have acquired a link by being linked BACKWARDS, which is
	 * the only direction {@code ElephantIntegration.linkSpots} works in: it links the current timepoint
	 * to {@code t - 1} and returns immediately at {@code t == 0}. So a spot here gets an edge only if
	 * some spot at {@code t + 1} picked it as a predecessor — it can never earn one itself.
	 *
	 * <p>False at {@code t = 0}, and also whenever {@code t - 1} holds no spots at all: a sub-range run
	 * (or a resumed one) starts at whatever timepoint it was told to, and its first timepoint is in
	 * exactly the same position as the movie's.</p>
	 */
	private static boolean canLinkBackward( final ToolContext ctx, final int timepoint )
	{
		if ( timepoint <= 0 )
			return false;
		final ModelGraph graph = ctx.getGraph();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
				if ( s.getTimepoint() == timepoint - 1 )
					return true;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return false;
	}

	/** Whether any spot at {@code timepoint} has a link to a later timepoint. */
	static boolean anyLinkedForward( final ToolContext ctx, final int timepoint )
	{
		final ModelGraph graph = ctx.getGraph();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
				if ( s.getTimepoint() == timepoint && !s.outgoingEdges().isEmpty() )
					return true;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return false;
	}

	static boolean relink( final ToolContext ctx, final int timepoint )
	{
		ctx.requireViewer().state().setCurrentTimepoint( timepoint );
		final ElephantIntegration elephant = new ElephantIntegration( ctx );
		if ( !elephant.init() )
		{
			log.warn( "t={} — re-link skipped: ELEPHANT not available", timepoint );
			return false;
		}
		return elephant.linkSpots();
	}

	static int deleteIsolatedSpots(
			final ToolContext ctx,
			final int timepoint )
	{
		final Model model = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();
		// A spot here can only be isolated because nothing at t+1 chose it, which says nothing about
		// whether it is a cell — see canLinkBackward. Skipped rather than rescued because the rescue
		// that would apply (linkOrphanStarts, step 3c above) is itself guarded to timepoint > 0: at the
		// one timepoint where isolation is structurally guaranteed, the deletion ran and the rescue did
		// not. Genuine noise here is still removed later by deleteUnstableSpots, which needs a track to
		// span >= STABLE_TRACK_MIN_TP timepoints and is frontier-protected. Measured on t=0..9: t=0 held
		// 155 spots against 166 at t=1 and 169 at t=2, and the deficit cascaded — a t=1 spot whose only
		// edge pointed at a deleted t=0 spot became isolated in turn.
		if ( !canLinkBackward( ctx, timepoint ) )
			return 0;
		final List< Spot > toDelete = new ArrayList<>();

		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot spot : graph.vertices() )
				{
					if ( spot.getTimepoint() != timepoint )
						continue;
					if ( ProtectedSpots.isProtected( ctx, model, spot ) )
						continue; // a protected spot with no links YET is still a confirmed result, not noise
					if ( spot.incomingEdges().isEmpty() && spot.outgoingEdges().isEmpty() )
					{
						final Spot copy = graph.vertexRef();
						copy.refTo( spot );
						toDelete.add( copy );
					}
				}
				for ( final Spot s : toDelete )
					GraphEdits.removeSpot( ctx, graph, s, GraphEdits.Reason.TOPOLOGY );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			if ( !toDelete.isEmpty() )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return toDelete.size();
		}
		finally
		{
			for ( final Spot s : toDelete )
				graph.releaseRef( s );
		}
	}

	static int deleteWeakSpots(
			final ToolContext ctx,
			final int timepoint,
			final double minIntensityRatio,
			final double qualityThreshold )
	{
		final ViewerPanel viewer = ctx.requireViewer();
		final List< SourceAndConverter< ? > > sources = viewer.state().getSources();
		if ( sources.isEmpty() )
			return 0;
		final SourceAndConverter< ? > sac = sources.get( 0 );

		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();

		// Collect under the READ lock, sample the image with NO lock, delete under the write lock. The
		// comment here used to say a single voxel per spot was fast enough to hold the write lock across
		// the whole timepoint; through this path one voxel cost 150-400 ms (see Sampler), so a frame of
		// detections held every reader -- the viewer's own drawing included -- for many seconds. A spot
		// deleted or replaced in the viewer meanwhile is left alone by removeRespectingFloor (GraphWatch).
		final List< Spot > allRefs = new ArrayList<>();
		// Only spots WITHOUT a recorded quality feed the intensity/median fallback — mixing the two
		// populations would let quality-scored spots (which no longer need this heuristic at all) skew
		// the median that quality-less spots are judged against.
		final List< Double > intensities = new ArrayList<>();
		final List< Spot > intensityRefs = new ArrayList<>();
		final List< double[] > intensityAt = new ArrayList<>();
		final List< Spot > toDelete = new ArrayList<>();
		// Bounded by the cell-count floor: "dimmer than a fraction of the timepoint median" is a judgement that this is not a
		// cell, which is exactly what that floor exists to bound. Observed unbounded: seven of a
		// window's ten timepoints taken below the stated count in one [5/6] pass.
		int removed = 0;

		try
		{
			graph.getLock().readLock().lock();
			try
			{
				for ( final Spot spot : graph.vertices() )
				{
					if ( spot.getTimepoint() != timepoint )
						continue;
					if ( ProtectedSpots.isProtected( ctx, model, spot ) )
						continue;
					final Spot ref = graph.vertexRef();
					ref.refTo( spot );
					allRefs.add( ref );

					final java.util.OptionalDouble quality = DetectionQuality.get( graph, spot );
					if ( quality.isPresent() )
					{
						if ( qualityThreshold > 0.0 && quality.getAsDouble() < qualityThreshold )
							toDelete.add( ref );
					}
					else
					{
						final double[] pos = new double[ 3 ];
						spot.localize( pos );
						intensityAt.add( pos );
						intensityRefs.add( ref );
					}
				}
			}
			finally
			{
				graph.getLock().readLock().unlock();
			}
			for ( final double[] pos : intensityAt )
				intensities.add( sampleSpotIntensity( sac, timepoint, pos ) );

			if ( minIntensityRatio > 0.0 && !intensities.isEmpty() )
			{
				// Median of sampled intensities (quality-less spots only)
				final double[] sorted = intensities.stream()
						.mapToDouble( Double::doubleValue ).sorted().toArray();
				final double median = sorted[ sorted.length / 2 ];
				final double threshold = minIntensityRatio * median;
				log.debug( "t={} — intensity threshold={} (ratio={} × median={})",
						timepoint, threshold, minIntensityRatio, median );

				for ( int i = 0; i < intensityRefs.size(); i++ )
				{
					if ( intensities.get( i ) < threshold )
						toDelete.add( intensityRefs.get( i ) );
				}
			}
			if ( !toDelete.isEmpty() )
			{
				graph.getLock().writeLock().lock();
				try
				{
					removed = ExpectedCellCount.removeRespectingFloor( ctx, timepoint, toDelete, graph );
				}
				finally
				{
					graph.getLock().writeLock().unlock();
				}
			}

			if ( !toDelete.isEmpty() )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return removed;
		}
		finally
		{
			for ( final Spot s : allRefs )
				graph.releaseRef( s );
		}
	}

	static int deleteSmallSpots(
			final ToolContext ctx,
			final int timepoint,
			final double minSizeRatio )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Spot > toDelete = new ArrayList<>();
		// Bounded by the cell-count floor: "smaller than a fraction of the timepoint median" is a judgement that this is not a
		// cell, which is exactly what that floor exists to bound. Observed unbounded: seven of a
		// window's ten timepoints taken below the stated count in one [5/6] pass.
		int removed = 0;

		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				// First pass: collect radii to compute median
				final List< Double > radii = new ArrayList<>();
				for ( final Spot spot : graph.vertices() )
				{
					if ( spot.getTimepoint() == timepoint )
						radii.add( Math.sqrt( spot.getBoundingSphereRadiusSquared() ) );
				}
				if ( radii.isEmpty() )
					return 0;

				final double[] sorted = radii.stream().mapToDouble( Double::doubleValue ).sorted().toArray();
				final double median = sorted[ sorted.length / 2 ];
				final double threshold = minSizeRatio * median;
				log.debug( "t={} — size threshold={} (ratio={} × median={})",
						timepoint, threshold, minSizeRatio, median );

				// Second pass: collect refs of spots below threshold
				for ( final Spot spot : graph.vertices() )
				{
					if ( spot.getTimepoint() != timepoint )
						continue;
					if ( ProtectedSpots.isProtected( ctx, model, spot ) )
						continue;
					if ( Math.sqrt( spot.getBoundingSphereRadiusSquared() ) < threshold )
					{
						final Spot copy = graph.vertexRef();
						copy.refTo( spot );
						toDelete.add( copy );
					}
				}
				removed = ExpectedCellCount.removeRespectingFloor( ctx, timepoint, toDelete, graph );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			if ( !toDelete.isEmpty() )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return removed;
		}
		finally
		{
			for ( final Spot s : toDelete )
				graph.releaseRef( s );
		}
	}

	/**
	 * One source, at one timepoint and one mipmap level, held open so many positions can be read from
	 * it without re-resolving the source each time.
	 *
	 * <p>Measured on the dataset that prompted this: a single voxel read through the
	 * fetch-the-source-per-sample path cost 150-400 ms, because each one resolved the level-0 image and
	 * then fetched that voxel's block from the store. A cell's twenty-one interior probes took 3.2
	 * seconds; a whole timepoint's cleanup on fifteen detections took a minute and a half, against two
	 * to eight seconds for the ELEPHANT prediction it was cleaning up. For contrast, the maxima scan —
	 * which walks the COARSEST level through one open image — reads a sample every 188 microseconds.
	 * The difference is not the number of voxels. It is how many times a block is fetched.</p>
	 */
	private static final class Sampler
	{
		private final AffineTransform3D transform;

		private final RandomAccessibleInterval< ? extends RealType< ? > > rai;

		private final RandomAccess< ? extends RealType< ? > > ra;

		private final double[] voxel = new double[ 3 ];

		private final long[] p = new long[ 3 ];

		private Sampler( final AffineTransform3D transform,
				final RandomAccessibleInterval< ? extends RealType< ? > > rai )
		{
			this.transform = transform;
			this.rai = rai;
			this.ra = rai.randomAccess();
		}

		/** {@code null} when the source has nothing at this timepoint. */
		@SuppressWarnings( { "unchecked", "rawtypes" } )
		static Sampler of( final SourceAndConverter< ? > sac, final int timepoint, final int level )
		{
			final Source source = sac.getSpimSource();
			if ( !source.isPresent( timepoint ) )
				return null;
			final int lvl = Math.max( 0, Math.min( level, source.getNumMipmapLevels() - 1 ) );
			final AffineTransform3D t = new AffineTransform3D();
			source.getSourceTransform( timepoint, lvl, t );
			return new Sampler( t, ( RandomAccessibleInterval ) source.getSource( timepoint, lvl ) );
		}

		/**
		 * Image value at a world position, or {@code 0} outside the image.
		 *
		 * <p>Callers that AVERAGE readings must use {@link #covers} first, because a zero here means
		 * either "this voxel is dark" or "there is no voxel here" and the two are not the same claim.
		 * See {@link #computeBackgroundStats}, where counting the second as the first put half the
		 * background probes of a single-plane dataset at zero.</p>
		 */
		double at( final double[] worldPos )
		{
			transform.applyInverse( voxel, worldPos );
			for ( int d = 0; d < 3; d++ )
			{
				p[ d ] = Math.round( voxel[ d ] );
				if ( p[ d ] < rai.min( d ) || p[ d ] > rai.max( d ) )
					return 0.0;
			}
			ra.setPosition( p );
			return ra.get().getRealDouble();
		}

		/** World position to voxel coordinates at this level. */
		void toVoxel( final double[] worldPos, final double[] out )
		{
			transform.applyInverse( out, worldPos );
		}

		/** World position of a voxel at this level. */
		void toWorld( final double[] voxelPos, final double[] out )
		{
			transform.apply( voxelPos, out );
		}

		/** World length of one voxel step along voxel axis {@code d}. */
		double voxelSize( final int d )
		{
			final double[] o0 = new double[ 3 ], o1 = new double[ 3 ], unit = new double[ 3 ];
			transform.apply( unit, o0 );
			unit[ d ] = 1;
			transform.apply( unit, o1 );
			return Math.sqrt( ( o1[ 0 ] - o0[ 0 ] ) * ( o1[ 0 ] - o0[ 0 ] )
					+ ( o1[ 1 ] - o0[ 1 ] ) * ( o1[ 1 ] - o0[ 1 ] ) + ( o1[ 2 ] - o0[ 2 ] ) * ( o1[ 2 ] - o0[ 2 ] ) );
		}

		/** Inclusive voxel bounds along axis {@code d}. */
		long min( final int d )
		{
			return rai.min( d );
		}

		long max( final int d )
		{
			return rai.max( d );
		}

		/** Value at voxel coordinates, or 0 outside the image. */
		double atVoxel( final long x, final long y, final long z )
		{
			if ( x < rai.min( 0 ) || x > rai.max( 0 ) || y < rai.min( 1 ) || y > rai.max( 1 )
					|| z < rai.min( 2 ) || z > rai.max( 2 ) )
				return 0.0;
			p[ 0 ] = x;
			p[ 1 ] = y;
			p[ 2 ] = z;
			ra.setPosition( p );
			return ra.get().getRealDouble();
		}

		/** Whether the image actually has a voxel at this world position. */
		boolean covers( final double[] worldPos )
		{
			transform.applyInverse( voxel, worldPos );
			for ( int d = 0; d < 3; d++ )
			{
				final long v = Math.round( voxel[ d ] );
				if ( v < rai.min( d ) || v > rai.max( d ) )
					return false;
			}
			return true;
		}
	}

	/**
	 * Voxels a typical cell's RADIUS must still span at the level intensity is measured on.
	 *
	 * <p>Two, so a cell is about five voxels across. The measurements taken here are a mean, a
	 * coefficient of variation over twenty-one points, and a centre-minus-shell contrast; none of them
	 * describes structure finer than the cell, and a coarser voxel is an AVERAGE of the finer ones, so
	 * it is a better estimator of all three than a single full-resolution voxel is.</p>
	 *
	 * <p>Was three, which on the dataset that prompted this bought exactly one level (voxel 1.28 µm
	 * against 0.64) and cut the cost of a read by a third — because what a read costs here is the
	 * LATENCY of fetching one block, not the bytes in it, and the number of blocks a scattered probe
	 * touches is one per probe at any level. Two levels coarser is 64x less data per block and, more to
	 * the point, a level small enough that the blocks stay cached between timepoints.</p>
	 */
	private static final double MIN_VOXELS_PER_RADIUS = 2.0;

	/** {@link ToolContext#getSharedData} key for {@link #samplingLevel}, decided once per run. */
	private static final String SHARED_SAMPLING_LEVEL = "autocorrect.sampling_level";

	/**
	 * The mipmap level every intensity, contrast and heterogeneity measurement in a run is taken on —
	 * the coarsest at which a typical cell still spans {@link #MIN_VOXELS_PER_RADIUS} voxels of radius.
	 *
	 * <p>ONE level for the whole run, not one per spot: every threshold these measurements are compared
	 * against is derived from the same measurements on the user's confirmed cells, so both sides of
	 * every comparison have to be read the same way. A per-spot choice would put a large cell and a
	 * small one on different levels and quietly make their numbers incomparable.</p>
	 */
	public static int samplingLevel( final ToolContext ctx )
	{
		if ( ctx == null )
			return 0;
		final Object cached = ctx.getSharedData( SHARED_SAMPLING_LEVEL );
		if ( cached instanceof Integer )
			return ( Integer ) cached;
		int level = 0;
		try
		{
			final List< SourceAndConverter< ? > > sources = ctx.requireViewer().state().getSources();
			if ( !sources.isEmpty() )
				level = chooseSamplingLevel( sources.get( 0 ).getSpimSource(), typicalSpotRadius( ctx ) );
		}
		catch ( final Exception e )
		{
			level = 0; // no viewer/source to ask — full resolution is always a valid answer
		}
		ctx.setSharedData( SHARED_SAMPLING_LEVEL, level );
		return level;
	}

	/**
	 * The coarsest level whose largest voxel edge still divides {@code radius} at least
	 * {@link #MIN_VOXELS_PER_RADIUS} times, or {@code 0} when even level 0 is coarser than that.
	 *
	 * @param radius a typical cell radius in world units
	 */
	private static int chooseSamplingLevel( final Source< ? > source, final double radius )
	{
		int best = 0;
		final AffineTransform3D t = new AffineTransform3D();
		for ( int level = 0; level < source.getNumMipmapLevels(); level++ )
		{
			source.getSourceTransform( 0, level, t );
			if ( radius / largestVoxelEdge( t ) >= MIN_VOXELS_PER_RADIUS )
				best = level;
		}
		return best;
	}

	/** World-unit length of the longest voxel edge under a source transform. */
	static double largestVoxelEdge( final AffineTransform3D transform )
	{
		double largest = 0.0;
		for ( int d = 0; d < 3; d++ )
		{
			final double a = transform.get( 0, d ), b = transform.get( 1, d ), c = transform.get( 2, d );
			largest = Math.max( largest, Math.sqrt( a * a + b * b + c * c ) );
		}
		return largest > 0 ? largest : 1.0;
	}

	/** Median spot radius in the graph, or a 5 world-unit fallback when there are no spots yet. */
	private static double typicalSpotRadius( final ToolContext ctx )
	{
		final ModelGraph graph = ctx.getGraph();
		final List< Double > radii = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
				radii.add( Math.sqrt( spot.getBoundingSphereRadiusSquared() ) );
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return radii.isEmpty() ? 5.0 : median( radii );
	}

	/**
	 * One line naming the level and what it means, for the run log — so a number that moved between
	 * runs can be traced to the level it was measured on rather than to the detector.
	 */
	public static String samplingLevelNote( final ToolContext ctx )
	{
		final int level = samplingLevel( ctx );
		try
		{
			final List< SourceAndConverter< ? > > sources = ctx.requireViewer().state().getSources();
			if ( sources.isEmpty() )
				return null;
			final Source< ? > source = sources.get( 0 ).getSpimSource();
			final AffineTransform3D t = new AffineTransform3D();
			source.getSourceTransform( 0, level, t );
			return String.format(
					"  Intensity, contrast and evenness are measured on mipmap level %d of %d (voxel up to "
							+ "%.2f µm, a typical cell spanning ~%.0f voxels). Full resolution costs a block "
							+ "fetch per probe — measured at 150-400 ms EACH on this dataset — and a coarser "
							+ "voxel is an average of the finer ones, so it estimates a cell's mean and "
							+ "evenness better, not worse.",
					level, source.getNumMipmapLevels() - 1, largestVoxelEdge( t ),
					2.0 * typicalSpotRadius( ctx ) / largestVoxelEdge( t ) );
		}
		catch ( final Exception e )
		{
			return null;
		}
	}

	private static double sampleSpotIntensity(
			final SourceAndConverter< ? > sac,
			final int timepoint,
			final double[] worldPos )
	{
		final Sampler sampler = Sampler.of( sac, timepoint, 0 );
		return sampler == null ? 0.0 : sampler.at( worldPos );
	}

	/** {@link ToolContext#getSharedData} key for {@link #sharedSampler}'s one open image. */
	private static final String SHARED_SAMPLER = "autocorrect.sampler";

	/**
	 * The one open image for {@code timepoint}, shared by every pass and every spot at it.
	 *
	 * <p>Each pass used to open its own — the flat-contrast check once per spot, the debris check once
	 * per spot, the no-signal check once more — so a timepoint holding fourteen detections resolved the
	 * source over forty times. Resolving it is not free (a level's image and a fresh random access), and
	 * more importantly a fresh random access starts with no current block, so two probes a micron apart
	 * in different passes each fetched the block they share.</p>
	 *
	 * <p>Single-threaded by construction: a Sampler carries a mutable position and one random access,
	 * and every caller is on the pipeline's own thread, sampling outside the graph lock. Do not hand one
	 * to another thread.</p>
	 */
	private static final String SHARED_SAMPLER_FULL = "autocorrect.sampler.full";

	/**
	 * Level 0, for the few reads that need the SHAPE of an object rather than an average over it. The
	 * shared sampler sits at a coarser level because averages are better estimated there; a nucleus's
	 * outline is not, and {@link ShapeSplit} was measured on level-0 pixels.
	 */
	private static Sampler fullResolutionSampler( final ToolContext ctx, final int timepoint )
	{
		final Object cached = ctx.getSharedData( SHARED_SAMPLER_FULL );
		if ( cached instanceof Object[] && ( Integer ) ( ( Object[] ) cached )[ 0 ] == timepoint )
			return ( Sampler ) ( ( Object[] ) cached )[ 1 ];
		final List< SourceAndConverter< ? > > sources = ctx.requireViewer().state().getSources();
		if ( sources.isEmpty() )
			return null;
		final Sampler sampler = Sampler.of( sources.get( 0 ), timepoint, 0 );
		ctx.setSharedData( SHARED_SAMPLER_FULL, new Object[] { timepoint, sampler } );
		return sampler;
	}

	private static Sampler sharedSampler( final ToolContext ctx, final int timepoint )
	{
		final int level = samplingLevel( ctx );
		final Object cached = ctx.getSharedData( SHARED_SAMPLER );
		if ( cached instanceof Object[] )
		{
			final Object[] entry = ( Object[] ) cached;
			if ( ( Integer ) entry[ 0 ] == timepoint && ( Integer ) entry[ 1 ] == level )
				return ( Sampler ) entry[ 2 ];
		}
		final List< SourceAndConverter< ? > > sources = ctx.requireViewer().state().getSources();
		if ( sources.isEmpty() )
			return null;
		final long started = System.nanoTime();
		final Sampler sampler = Sampler.of( sources.get( 0 ), timepoint, level );
		PhaseTimings.recordNested( ctx, "open image source", started );
		ctx.setSharedData( SHARED_SAMPLER, new Object[] { timepoint, level, sampler } );
		return sampler;
	}

	/**
	 * Scale factor from the intensity-weighted standard deviation of a blob to the rendered ellipsoid
	 * semi-axis. {@code ~2} makes the ellipsoid roughly enclose the visible cell body (≈2σ), which is
	 * what a detector should learn — a smaller value teaches a sub-cell detector that splits one large
	 * cell into several spots.
	 */
	private static final double EXTENT_SIGMA_SCALE = 2.0;

	/**
	 * Safety cap for {@link #inflatePointLabels}: each point-like spot triggers a local voxel-box scan, so
	 * inflating tens of thousands of spots would grind for a very long time. A trusted set this large is
	 * not hand-placed point GT (it's detections), so inflation is skipped entirely with a warning.
	 */
	private static final int INFLATE_MAX_CANDIDATES = 5000;

	/**
	 * Gives point-like trusted-GT spots a realistic, image-derived spatial extent BEFORE they are used
	 * as training labels. Ground truth is typically placed as near-points; training on points teaches
	 * ELEPHANT a tiny detector, so one large object gets split into several detections. For every record
	 * in {@code snap} whose current size ({@code sqrt(trace)}) is below {@code pointSizeMax}, this
	 * estimates an axis-aligned covariance from the local image (intensity-weighted second moments over
	 * the half-max region around the point) and overwrites the record's covariance, clamping each
	 * semi-axis to {@code [rMin, rMax]}. Records that already carry a real ellipsoid are left untouched.
	 *
	 * <p>Operates in-place on the snapshot records (the {@code {x,y,z,cov0..8}} layout); it does not
	 * touch the graph. Anisotropy is preserved per world axis; ellipsoid tilt is not (axis-aligned).</p>
	 *
	 * @return number of records whose extent was (re)estimated
	 */
	public static int inflatePointLabels( final ToolContext ctx,
			final Map< Integer, List< double[] > > snap,
			final double pointSizeMax, final double rMin, final double rMax )
	{
		final ViewerPanel viewer = ctx.requireViewer();
		final List< SourceAndConverter< ? > > sources = viewer.state().getSources();
		if ( sources.isEmpty() )
			return 0;
		final SourceAndConverter< ? > sac = sources.get( 0 );
		// Count point-like candidates first: each one triggers a costly voxel-box scan, so bail out on a set
		// far larger than any hand-placed GT (it's detections, which already carry a real extent).
		int candidates = 0;
		for ( final List< double[] > l : snap.values() )
			for ( final double[] rec : l )
				if ( Math.sqrt( Math.max( 0.0, rec[ 3 ] + rec[ 7 ] + rec[ 11 ] ) ) < pointSizeMax )
					candidates++;
		if ( candidates > INFLATE_MAX_CANDIDATES )
		{
			ctx.log( String.format( "  Skipping GT extent estimation: %d point-like spots exceed the cap of %d "
					+ "(this many are detections, not hand annotations — they already carry an extent).",
					candidates, INFLATE_MAX_CANDIDATES ) );
			return 0;
		}
		int inflated = 0;
		final double[] center = new double[ 3 ];
		for ( final Map.Entry< Integer, List< double[] > > e : snap.entrySet() )
		{
			final int t = e.getKey();
			for ( final double[] rec : e.getValue() )
			{
				// size = sqrt(trace) with the {x,y,z,cov0..8} layout: cov[0][0]=rec[3], [1][1]=rec[7], [2][2]=rec[11]
				final double size = Math.sqrt( Math.max( 0.0, rec[ 3 ] + rec[ 7 ] + rec[ 11 ] ) );
				if ( size >= pointSizeMax )
					continue; // already has a plausible extent — leave it
				center[ 0 ] = rec[ 0 ];
				center[ 1 ] = rec[ 1 ];
				center[ 2 ] = rec[ 2 ];
				double[][] cov = estimateExtent( sac, t, center, rMin, rMax );
				if ( cov == null )
				{
					// Degenerate/dim neighbourhood: fall back to a minimal isotropic cell.
					cov = new double[ 3 ][ 3 ];
					cov[ 0 ][ 0 ] = cov[ 1 ][ 1 ] = cov[ 2 ][ 2 ] = rMin * rMin;
				}
				for ( int rr = 0; rr < 3; rr++ )
					for ( int cc = 0; cc < 3; cc++ )
						rec[ 3 + rr * 3 + cc ] = cov[ rr ][ cc ];
				inflated++;
			}
		}
		return inflated;
	}

	private static double[][] estimateExtent( final SourceAndConverter< ? > sac, final int timepoint,
			final double[] center, final double rMin, final double rMax )
	{
		return estimateExtentTyped( ( SourceAndConverter ) sac, timepoint, center, rMin, rMax );
	}

	/**
	 * Estimates an axis-aligned ellipsoid covariance for the blob centred at {@code center} from the
	 * intensity-weighted second moments of voxels brighter than half the peak, within {@code rMax} of
	 * the centre. Returns {@code null} if the neighbourhood is dark or degenerate.
	 */
	private static < T extends RealType< T > > double[][] estimateExtentTyped(
			final SourceAndConverter< T > sac, final int timepoint,
			final double[] center, final double rMin, final double rMax )
	{
		final Source< T > source = sac.getSpimSource();
		if ( !source.isPresent( timepoint ) )
			return null;
		final AffineTransform3D transform = new AffineTransform3D();
		source.getSourceTransform( timepoint, 0, transform );
		final RandomAccessibleInterval< T > rai = source.getSource( timepoint, 0 );
		final RandomAccess< T > ra = rai.randomAccess();

		final double[] centerVox = new double[ 3 ];
		transform.applyInverse( centerVox, center );
		final long[] cVox = {
				Math.round( centerVox[ 0 ] ),
				Math.round( centerVox[ 1 ] ),
				Math.round( centerVox[ 2 ] )
		};

		// World µm per voxel step along each voxel axis (column norms of the source transform).
		final int[] half = new int[ 3 ];
		for ( int d = 0; d < 3; d++ )
		{
			final double s0 = transform.get( 0, d ), s1 = transform.get( 1, d ), s2 = transform.get( 2, d );
			double colNorm = Math.sqrt( s0 * s0 + s1 * s1 + s2 * s2 );
			if ( colNorm <= 0 )
				colNorm = 1.0;
			half[ d ] = Math.max( 1, ( int ) Math.ceil( rMax / colNorm ) );
		}

		final long[] p = new long[ 3 ];
		// Peak intensity at the centre voxel.
		if ( outOfBounds( rai, cVox ) )
			return null;
		ra.setPosition( cVox );
		final double peak = ra.get().getRealDouble();
		if ( peak <= 0 )
			return null;
		final double threshold = 0.5 * peak;

		final double[] voxel = new double[ 3 ];
		final double[] world = new double[ 3 ];
		final double rMax2 = rMax * rMax;
		double sumW = 0.0;
		final double[] m2 = new double[ 3 ];
		long nAbove = 0;
		for ( long dz = -half[ 2 ]; dz <= half[ 2 ]; dz++ )
			for ( long dy = -half[ 1 ]; dy <= half[ 1 ]; dy++ )
				for ( long dx = -half[ 0 ]; dx <= half[ 0 ]; dx++ )
				{
					p[ 0 ] = cVox[ 0 ] + dx;
					p[ 1 ] = cVox[ 1 ] + dy;
					p[ 2 ] = cVox[ 2 ] + dz;
					if ( outOfBounds( rai, p ) )
						continue;
					ra.setPosition( p );
					final double intensity = ra.get().getRealDouble();
					final double w = intensity - threshold;
					if ( w <= 0 )
						continue;
					voxel[ 0 ] = p[ 0 ];
					voxel[ 1 ] = p[ 1 ];
					voxel[ 2 ] = p[ 2 ];
					transform.apply( voxel, world );
					final double ox = world[ 0 ] - center[ 0 ];
					final double oy = world[ 1 ] - center[ 1 ];
					final double oz = world[ 2 ] - center[ 2 ];
					if ( ox * ox + oy * oy + oz * oz > rMax2 )
						continue;
					sumW += w;
					m2[ 0 ] += w * ox * ox;
					m2[ 1 ] += w * oy * oy;
					m2[ 2 ] += w * oz * oz;
					nAbove++;
				}
		if ( sumW <= 0 || nAbove < 3 )
			return null;
		final double[][] cov = new double[ 3 ][ 3 ];
		for ( int d = 0; d < 3; d++ )
		{
			final double var = m2[ d ] / sumW;
			double a = EXTENT_SIGMA_SCALE * Math.sqrt( Math.max( 0.0, var ) );
			if ( a < rMin )
				a = rMin;
			if ( a > rMax )
				a = rMax;
			cov[ d ][ d ] = a * a;
		}
		return cov;
	}

	private static boolean outOfBounds( final RandomAccessibleInterval< ? > rai, final long[] p )
	{
		for ( int d = 0; d < 3; d++ )
			if ( p[ d ] < rai.min( d ) || p[ d ] > rai.max( d ) )
				return true;
		return false;
	}

	/**
	 * Samples the image intensity at a world position and timepoint (first source). Public so the
	 * example-based detection filter can measure an OK/NG example spot's intensity.
	 */
	public static double sampleIntensity( final ToolContext ctx, final int timepoint,
			final double[] worldPos )
	{
		final ViewerPanel viewer = ctx.requireViewer();
		final List< SourceAndConverter< ? > > sources = viewer.state().getSources();
		if ( sources.isEmpty() )
			return 0.0;
		final Sampler sampler = sharedSampler( ctx, timepoint );
		if ( sampler == null )
			return 0.0;
		final long started = System.nanoTime();
		try
		{
			return sampler.at( worldPos );
		}
		finally
		{
			PhaseTimings.recordNested( ctx, "single-voxel reads", started );
		}
	}

	/**
	 * Samples LOCAL CONTRAST (spot intensity minus surrounding background shell) at a world position and
	 * timepoint (first source). Public so the example-based detection filter can measure an OK/NG example
	 * spot's contrast — the brightness-invariant signal that actually distinguishes a real cell (even a
	 * dim one) from background, see {@link #sampleContrast(SourceAndConverter, int, double[], double)}.
	 */
	public static double sampleContrast( final ToolContext ctx, final int timepoint,
			final double[] worldPos, final double radius )
	{
		final ViewerPanel viewer = ctx.requireViewer();
		final List< SourceAndConverter< ? > > sources = viewer.state().getSources();
		if ( sources.isEmpty() )
			return 0.0;
		final Sampler sampler = sharedSampler( ctx, timepoint );
		final long started = System.nanoTime();
		try
		{
			return sampleContrast( sampler, worldPos, radius );
		}
		finally
		{
			PhaseTimings.recordNested( ctx, "spot contrast reads", started );
		}
	}

	/**
	 * The unprotected spots in {@code [startTp, endTp]} as refs, with {x, y, z, radius, t} rows, read
	 * under the read lock only. The image is then read with no lock held -- under it, every edit in the
	 * viewer waited for the whole range -- and the verdicts go back through {@link #tagUnchanged}.
	 */
	private static List< Spot > unprotectedSpotsIn( final ToolContext ctx, final int startTp, final int endTp,
			final List< double[] > rows )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Spot > refs = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				if ( t < startTp || t > endTp )
					continue;
				if ( ProtectedSpots.isProtected( ctx, model, spot ) )
					continue; // never re-labelled tp/fp — it is trusted tp by definition
				final double[] row = new double[ 5 ];
				spot.localize( row );
				row[ 3 ] = Math.sqrt( spot.getBoundingSphereRadiusSquared() );
				row[ 4 ] = t;
				rows.add( row );
				refs.add( graph.vertexRef().refTo( spot ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return refs;
	}

	/**
	 * Sets {@code tags[i]} on {@code refs[i]} under the write lock, except on a spot deleted, replaced or
	 * protected since it was collected; releases the refs. @return {tp count, fp count} actually set
	 */
	private static int[] tagUnchanged( final ToolContext ctx, final List< Spot > refs, final Tag[] tags,
			final Tag tpTag )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();
		int nTp = 0, nFp = 0;
		graph.getLock().writeLock().lock();
		try
		{
			for ( int i = 0; i < refs.size(); i++ )
			{
				final Spot s = refs.get( i );
				if ( tags[ i ] == null || GraphWatch.dirty( graph, s ) || ProtectedSpots.isProtected( ctx, model, s ) )
					continue;
				vertexTags.set( s, tags[ i ] );
				if ( tags[ i ] == tpTag )
					nTp++;
				else
					nFp++;
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
			for ( final Spot s : refs )
				graph.releaseRef( s );
		}
		model.setUndoPoint();
		ctx.notifyGraphChanged();
		return new int[] { nTp, nFp };
	}

	/**
	 * Example-based contrast LABELLING: tags every spot in {@code [startTp, endTp]} as {@code tp}
	 * (OK-like, positive) or {@code fp} (NG-like, background negative) by comparing its LOCAL CONTRAST —
	 * NOT absolute intensity — to a single THRESHOLD placed at the midpoint of the dimmest OK example and
	 * the brightest NG example: {@code threshold = 0.5 * (min(okContrasts) + max(ngContrasts))}, accepting
	 * everything at or above it. Contrast is not a symmetric, per-class-Gaussian quantity: real cells span
	 * a huge range (a small dim cell and a large bright one are both "OK") while background sits near a
	 * comparatively low, narrow band. A centroid/mean-based decision (nearest-centroid, or a midpoint of
	 * MEANS) is fooled by that OK-side spread — the mean gets pulled toward the bright examples, and a
	 * legitimate dim OK example can then be judged statistically "far" from its own class and closer to
	 * background, flipping a just-picked OK example itself back to fp. Anchoring on the dimmest OK example
	 * instead guarantees every OK example registered so far (and everything brighter) classifies positive,
	 * and every NG example (and everything darker) classifies negative, as long as the two sets do not
	 * overlap (see {@link #examplesOverlap}). Keeping the background as explicit {@code fp} training labels
	 * lets ELEPHANT learn to SUPPRESS it, which is more effective than deletion. The tags persist on the
	 * spots for the next training pass.
	 *
	 * @return {@code [nTp, nFp]}
	 */
	public static int[] labelByContrastExample( final ToolContext ctx, final int startTp, final int endTp,
			final List< Double > okContrasts, final List< Double > ngContrasts )
	{
		final Model model = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();
		final ViewerPanel viewer = ctx.requireViewer();
		final List< SourceAndConverter< ? > > sources = viewer.state().getSources();
		if ( sources.isEmpty() )
			return new int[] { 0, 0 };
		final SourceAndConverter< ? > sac = sources.get( 0 );
		final double threshold = 0.5 * ( min( okContrasts ) + max( ngContrasts ) );

		// Ensure the tag set exists (modifies the tag-set structure) BEFORE taking the vertex write lock.
		final TagSet tagSet = DetectionTagLabels.ensure( model );
		final Tag tpTag = DetectionTagLabels.tpTag( tagSet );
		final Tag fpTag = DetectionTagLabels.fpTag( tagSet );
		final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();

		final List< double[] > rows = new ArrayList<>();
		final List< Spot > refs = unprotectedSpotsIn( ctx, startTp, endTp, rows );
		final Tag[] tags = new Tag[ refs.size() ];
		for ( int i = 0; i < tags.length; i++ )
		{
			final double[] r = rows.get( i );
			final double contrast = sampleContrast( sac, ( int ) r[ 4 ], new double[] { r[ 0 ], r[ 1 ], r[ 2 ] }, r[ 3 ] );
			tags[ i ] = contrast >= threshold ? tpTag : fpTag;
		}
		return tagUnchanged( ctx, refs, tags, tpTag );
	}

	/**
	 * True if the dimmest OK example is not brighter than the brightest NG example — i.e. the two example
	 * sets overlap in contrast, so no threshold can classify every registered example correctly. Callers
	 * should warn the user to reconsider their examples (e.g. drop the ambiguous one and re-pick) rather
	 * than silently trust {@link #labelByContrastExample} in this case.
	 */
	public static boolean examplesOverlap( final List< Double > okContrasts, final List< Double > ngContrasts )
	{
		return min( okContrasts ) <= max( ngContrasts );
	}

	private static double min( final List< Double > xs )
	{
		double m = Double.POSITIVE_INFINITY;
		for ( final double x : xs )
			m = Math.min( m, x );
		return m;
	}

	private static double max( final List< Double > xs )
	{
		double m = Double.NEGATIVE_INFINITY;
		for ( final double x : xs )
			m = Math.max( m, x );
		return m;
	}

	/**
	 * Tags spots in {@code [startTp, endTp]} as {@code tp}/{@code fp} purely from ELEPHANT's own
	 * per-spot {@link DetectionQuality} — no OK/NG example picking required, unlike
	 * {@link #labelByContrastExample}. Spots with no recorded quality (older server response, or added
	 * through a path other than {@link ElephantIntegration#predictSpots}) are left untagged and counted
	 * separately so the caller can report them (e.g. "use Label TP/FP for those").
	 *
	 * @return {@code [nAccepted, nRejected, nNoQuality]}
	 */
	public static int[] applyQualityThreshold( final ToolContext ctx, final int startTp, final int endTp,
			final double qualityThreshold )
	{
		final Model model = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();

		final TagSet tagSet = DetectionTagLabels.ensure( model );
		final Tag tpTag = DetectionTagLabels.tpTag( tagSet );
		final Tag fpTag = DetectionTagLabels.fpTag( tagSet );
		final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();

		int nAccepted = 0, nRejected = 0, nNoQuality = 0;
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				if ( t < startTp || t > endTp )
					continue;
				if ( ProtectedSpots.isProtected( ctx, model, spot ) )
					continue; // never re-labelled tp/fp — it is trusted tp by definition
				final java.util.OptionalDouble quality = DetectionQuality.get( graph, spot );
				if ( !quality.isPresent() )
				{
					nNoQuality++;
					continue;
				}
				final boolean accept = quality.getAsDouble() >= qualityThreshold;
				vertexTags.set( spot, accept ? tpTag : fpTag );
				if ( accept )
					nAccepted++;
				else
					nRejected++;
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		model.setUndoPoint();
		ctx.notifyGraphChanged();
		return new int[] { nAccepted, nRejected, nNoQuality };
	}

	/**
	 * How far a position stands above this frame's background, in units of the background's own spread.
	 *
	 * <p>A model-free stand-in for "how cell-like is this" where nothing has been confirmed yet, so
	 * there is no profile of the user's own cells to rank against. On fluorescence — which
	 * {@code ImageProfile} decides — standing above the background IS the evidence, and this is the
	 * same quantity {@link BackgroundStats#isDefinitelyBackground} already tests, just returned as a
	 * number instead of a verdict so detections can be ordered by it.</p>
	 *
	 * @return sigma above the background, or {@code 0} when the frame could not be measured
	 */
	/**
	 * Whether {@link #standoutAboveBackground} measures anything at {@code timepoint}: it divides by the
	 * background's spread, and a background of exactly 0 (a sparse specimen on a light-sheet stack) has none, so
	 * every detection there reads 0 whether it is a cell or not.
	 */
	public static boolean standoutMeasurable( final ToolContext ctx, final int timepoint )
	{
		try
		{
			final BackgroundStats stats = estimateBackgroundStats( ctx, timepoint );
			return stats != null && stats.intensityStd > 0;
		}
		catch ( final RuntimeException e )
		{
			return false;
		}
	}

	/**
	 * The image intensity at a world position, at the shared sampling level; 0 where it cannot be read. On a
	 * background of exactly 0 (where {@link #standoutMeasurable} is false) brightness itself is the evidence the
	 * standout would have scaled.
	 */
	public static double intensityAt( final ToolContext ctx, final int timepoint, final double[] worldPos )
	{
		try
		{
			final Sampler sampler = sharedSampler( ctx, timepoint );
			return sampler == null ? 0.0 : sampler.at( worldPos );
		}
		catch ( final RuntimeException e )
		{
			return 0.0;
		}
	}

	public static double standoutAboveBackground( final ToolContext ctx, final int timepoint,
			final double[] worldPos )
	{
		try
		{
			final BackgroundStats stats = estimateBackgroundStats( ctx, timepoint );
			final Sampler sampler = sharedSampler( ctx, timepoint );
			if ( stats == null || sampler == null || !( stats.intensityStd > 0 ) )
				return 0.0;
			return ( sampler.at( worldPos ) - stats.intensityMean ) / stats.intensityStd;
		}
		catch ( final RuntimeException e )
		{
			// This orders detections; it does not decide whether any of them survive. A frame whose
			// image cannot be read leaves every candidate tied, and the CAP still applies — which is the
			// part that matters, and the part whose absence taught a detector 700 cells against a stated
			// 26.
			log.debug( "standout above background unavailable at t={}", timepoint, e );
			return 0.0;
		}
	}

	/** Rays walked outward from a detection when measuring the cell around it. */
	private static final int CELL_EXTENT_RAYS = 8;

	/**
	 * Level, in background sigma, at which the cell is taken to end.
	 *
	 * <p>Measured against 2117 cells of CTC silver truth (see {@code benchmark/cell-extent}): at 5
	 * sigma the per-frame population median lands within 2.2% of the truth's own equivalent radius,
	 * against 9.1% at 8 sigma, which undersizes. More rays do not help -- 8, 16 and 32 agree to
	 * within 0.5%.</p>
	 */
	private static final double CELL_EXTENT_SIGMA = 5.0;

	/** Reach of the walk, as a multiple of the seed radius. */
	private static final double CELL_EXTENT_MAX_REACH = 6.0;

	/** Steps per seed radius. Granularity matters: the same estimator walked at 3.5 px instead of
	 * 1 px goes from 2.2% population error to 8.5%, so the step stays near voxel resolution. */
	private static final int CELL_EXTENT_STEPS_PER_RADIUS = 8;

	/**
	 * Radius of the CELL around a detection, measured from the image rather than from the detection.
	 *
	 * <p>ELEPHANT reports a core, not the cell. Training on that core unchanged makes the next round's
	 * core smaller again -- and a detection covering half a nucleus is also exactly what lets two
	 * detections tile one nucleus, so the undersized annotation and the duplicate pair are one
	 * problem. The correction for it already existed, but it measured the target from the user's
	 * CONFIRMED cells and did nothing when there were none, which is every autonomous run.</p>
	 *
	 * <p>Walks {@link #CELL_EXTENT_RAYS} rays outward until the intensity falls below
	 * {@code background + }{@link #CELL_EXTENT_SIGMA}{@code  sigma} and takes the median. The median
	 * over rays is what makes this usable in a confluent culture: a touching neighbour corrupts only
	 * the rays pointing at it, where a flood fill would swallow the neighbour whole. Measured, the ray
	 * walk also beats the flood fill per cell (83% within 25% against 73%) while costing a few hundred
	 * samples instead of a region.</p>
	 *
	 * <p>The rays stay in the XY plane. On a 2D movie there is no other option, and on a volumetric
	 * one this measures the IN-PLANE radius, which is the extent the annotation's in-plane semi-axes
	 * describe; a z extent would need the axial sampling this does not do.</p>
	 *
	 * @param seedRadius the detection's own radius, which sets the step and the reach only
	 * @return the cell radius in world units, or {@code NaN} if the image could not answer
	 */
	public static double measuredCellRadius( final ToolContext ctx, final int timepoint,
			final double[] worldPos, final double seedRadius )
	{
		try
		{
			final double[] frame = frameLevelStats( ctx, timepoint );
			final Sampler sampler = sharedSampler( ctx, timepoint );
			if ( frame == null || sampler == null || !( frame[ 1 ] > 0 ) )
				return Double.NaN;
			final double level = frame[ 0 ] + CELL_EXTENT_SIGMA * frame[ 1 ];
			if ( !( sampler.at( worldPos ) > level ) )
				return Double.NaN; // the seed itself is not above the floor: nothing to measure
			final double r = Math.max( seedRadius, 1e-6 );
			final double step = r / CELL_EXTENT_STEPS_PER_RADIUS;
			final int maxSteps = ( int ) Math.ceil( CELL_EXTENT_MAX_REACH * CELL_EXTENT_STEPS_PER_RADIUS );
			final double[] hits = new double[ CELL_EXTENT_RAYS ];
			final double[] probe = new double[ 3 ];
			for ( int i = 0; i < CELL_EXTENT_RAYS; i++ )
			{
				final double angle = 2.0 * Math.PI * i / CELL_EXTENT_RAYS;
				final double dx = Math.cos( angle );
				final double dy = Math.sin( angle );
				// A ray that reaches the end of its reach has not found an edge. Counting that as "the
				// cell is at least this big" is how a dense region reports a huge cell: in a confluent
				// frame several rays run into neighbours and never drop below the level, and the median
				// of eight then sits at the cap rather than on the cell. Such a ray is not evidence
				// about size, so it does not vote.
				double hit = Double.NaN;
				for ( int k = 1; k <= maxSteps; k++ )
				{
					final double d = k * step;
					probe[ 0 ] = worldPos[ 0 ] + dx * d;
					probe[ 1 ] = worldPos[ 1 ] + dy * d;
					probe[ 2 ] = worldPos[ 2 ];
					if ( sampler.at( probe ) < level )
					{
						hit = d;
						break;
					}
				}
				hits[ i ] = hit;
			}
			// Median over the rays that actually found an edge. Fewer than half of them finding one
			// means the seed is not sitting on a measurable object.
			final double[] found = new double[ CELL_EXTENT_RAYS ];
			int n = 0;
			for ( final double hit : hits )
			{
				if ( !Double.isNaN( hit ) )
					found[ n++ ] = hit;
			}
			if ( n < CELL_EXTENT_RAYS / 2 )
				return Double.NaN;
			java.util.Arrays.sort( found, 0, n );
			return n % 2 == 1 ? found[ n / 2 ] : ( found[ n / 2 - 1 ] + found[ n / 2 ] ) / 2.0;
		}
		catch ( final RuntimeException e )
		{
			log.debug( "cell extent unavailable at t={}", timepoint, e );
			return Double.NaN;
		}
	}

	/**
	 * Least and most a detection's radius may be changed by {@link #resizeToMeasuredExtent}.
	 *
	 * <p>The estimator is good at the population level -- 2.2% against CTC silver truth over 2117
	 * cells -- and good enough per cell, 82.5% within 25%. Its failures are one-sided and sharp: the
	 * p05 ratio is 0.11, a cell whose rays all escaped through a dim rim and stopped at once. Clamping
	 * is not tuning, it is refusing to act on a reading that says a nucleus is a ninth of its size.
	 * The p95 is 1.32, so the upper clamp is loose by comparison and rarely binds.</p>
	 */
	private static final double EXTENT_MIN_FACTOR = 0.5;

	/**
	 * Most a detection may grow.
	 *
	 * <p>Was 2.0, which is exactly the growth from one nucleus to a touching pair, and that is what it
	 * did: the user's t=61 frame showed single detections covering two clearly separate nuclei. The
	 * ray walk stops where the intensity drops below {@code background + 5 sigma}, and between two
	 * touching nuclei it never does -- the rays pointing at the neighbour find no edge and abstain, so
	 * the median is taken over the outward rays, which measure the PAIR's outer boundary.</p>
	 *
	 * <p>The measured p95 of the estimator over 2117 CTC silver-truth cells is 1.32, so 1.4 admits the
	 * whole ordinary spread of real readings. A reading above it is not a bigger cell, it is the walk
	 * escaping into a neighbour.</p>
	 */
	private static final double EXTENT_MAX_FACTOR = 1.4;

	/** Relative change below which a detection is left alone, so the pass does not churn geometry. */
	private static final double EXTENT_DEAD_ZONE = 0.1;

	/**
	 * How uneven a detection's CORE must read before it is worth testing for a split.
	 *
	 * <p>The coefficient of variation of the samples within half a radius of the centre, from
	 * {@code interiorIntensityProfile}. A fluorescent nucleus is brightest at its middle and smooth
	 * there; a detection covering two of them has the valley between them running through its middle,
	 * so its core reads bright-dark-bright. The populations barely overlap: over 2868 single cells and
	 * 384 genuine pairs the single cells sit at a median of 0.129 with a p90 of 0.220, the pairs at a
	 * median of 0.636 with a p10 of 0.314.</p>
	 *
	 * <p>This replaced a percentile of the frame's RADII, which was a proxy for the same thing and a
	 * much worse one -- it examined a quarter of every frame's single cells to reach 96% of the pairs,
	 * where this reaches 96.6% of the pairs while touching 7.8% of the single cells. End to end, with
	 * the piece ceiling and the saddle also applied:</p>
	 *
	 * <pre>
	 *   gate                                  true splits   false splits
	 *   none                                       73.7%          2.2%
	 *   radius &gt; 1.4 x the frame median            73.2%          0.0%
	 *   core CV &gt; 0.25                             73.7%          1.2%
	 *   core CV &gt; 0.25 and radius &gt; the median     73.7%          0.6%
	 * </pre>
	 *
	 * <p>A size gate on its own costs true splits; this one does not. Both together are better than
	 * either, so the radius floor stays, loosened from the 75th percentile to the median: a detection
	 * smaller than a typical cell is not covering two of them whatever its texture says.</p>
	 */

	/*
	 * There was a SPLIT_MAX_SADDLE here, requiring the intensity to dip between the two candidate
	 * centres. It is gone, and the reason is worth keeping so it is not re-added.
	 *
	 * It was measured behind the gate that preceded this one -- a percentile of the frame's RADII,
	 * which let a quarter of every frame's single cells through -- and there it did real work, taking
	 * 47% of the false splits for 11% of the true. Then the gate was replaced by the core-unevenness
	 * test, and the saddle was never re-measured behind it. It should have been: the two do the same
	 * job, and only 0.2% of single cells now reach the saddle at all.
	 *
	 *   saddle <=     true splits   false splits
	 *      0.70           71.2%         0.42%
	 *      0.80           73.8%         0.56%      <- what shipped
	 *      0.90           76.4%         0.63%
	 *      0.95           78.7%         0.73%
	 *      none           82.9%         0.76%
	 *
	 * Dropping it is +9.1 points of true split for +0.20 of false, a 45:1 trade. Nothing between
	 * 0.80 and "none" is worth keeping either: every step is at least 25:1 in favour of loosening.
	 * SeedToObject.saddleRatio stays, tested and documented, because the benchmark uses it -- but
	 * gating on it here is a measured mistake.
	 *
	 * The general lesson, which cost a round trip: when one gate in a chain is replaced, the value of
	 * the gates BEHIND it changes, because what reaches them changes. Re-measure them.
	 */

	/*
	 * There was a SPLIT_MIN_CORE_CV = 0.25 here, a constant floor under the adaptive bar. It is gone
	 * because 0.25 is a number about one specimen: a core CV is dimensionless, but how uneven a smooth
	 * nucleus reads depends on the optics and the label, and a movie whose nuclei are cleaner than
	 * these would have had the constant as its ONLY operative threshold -- splitting nothing, with no
	 * way to tell from the log.
	 *
	 * It was doing two separate jobs, and both are now written down where they belong:
	 *
	 *   - a lower bound on what a spread can mean, which is not a constant but the spread that
	 *     measurement noise alone produces: see noiseFloorCv;
	 *   - refusing the pass outright when the reading is invalid. Without a measured background
	 *     interiorIntensityProfile takes every spread about zero, the whole frame reads ~0.005, the
	 *     frame's own median-plus-MAD bar lands at ~0.005 too, and half the detections clear it. The
	 *     constant was the only thing standing in the way, by accident. splitMergedDetections now
	 *     refuses the frame, on purpose.
	 *
	 * Measured on 3890 detections from CTC silver truth (benchmark/seed-to-object/RESULTS.md): the
	 * noise-implied floor reads 0.027 to 0.047 here, five to nine times below the constant, and the
	 * swap moves 0.0 points of genuine pairs and 0.5 points of single cells. The change buys nothing on
	 * this dataset. That is the point -- it is removing a number that could only ever be wrong
	 * somewhere else.
	 */



	/**
	 * Standard deviations (as MADs) above the run's median extent reading at which a detection's
	 * extent walk counts as escaped into a second cell. Chosen on t&lt;40 of one run and held on the
	 * rest: 1.25 -> 40% of splits right, 1.5 -> 85% once the piece test is in, 2.5 -> 91% with fewer
	 * merged detections fixed. See benchmark/split-review/RESULTS.md.
	 */
	static final double SPLIT_EXTENT_MADS = 1.5;

	/** Shared-data key for every extent reading the split pass has seen this run, as a histogram. */
	private static final String SHARED_SPLIT_EXTENT_POOL = "autocorrect.split.extentPool";

	/**
	 * The grid extent readings fall on: the walk steps a radius in {@link #CELL_EXTENT_STEPS_PER_RADIUS}
	 * and an even count of rays averages the middle two, so a reading is a multiple of half a step.
	 */
	static final double EXTENT_RATIO_QUANTUM = 0.5 / CELL_EXTENT_STEPS_PER_RADIUS;

	/** Points each grid cell is spread over when the MAD is taken. */
	private static final int GROUPED_SUBSAMPLES = 8;

	/**
	 * Adds this frame's extent readings to the run's pool and returns the bar: the pool's median plus
	 * {@link #SPLIT_EXTENT_MADS} MADs, or 0 while the pool is too small to have a spread.
	 *
	 * <p>Grouped statistics, not a plain median and MAD, because the readings sit on a 1/16 grid and
	 * the run's median sat exactly on the boundary between two cells of it. Measured on a GUI run: as
	 * readings accumulated the plain median flipped 1.0 / 0.9375 and the MAD 0.125 / 0.0625, so the bar
	 * jumped between 1.278 and 1.076 -- and every one of that run's single nuclei split in two was split
	 * while it stood at 1.076. Read as a histogram with each reading spread over its grid cell the bar
	 * stays at 1.16-1.21, and the run's splits go from 61% to 72% right.</p>
	 */
	@SuppressWarnings( "unchecked" )
	static double runExtentBar( final ToolContext ctx, final double[] ratios )
	{
		final Object held = ctx.getSharedData( SHARED_SPLIT_EXTENT_POOL );
		final java.util.TreeMap< Long, Long > pool = held instanceof java.util.TreeMap
				? ( java.util.TreeMap< Long, Long > ) held : new java.util.TreeMap<>();
		for ( final double v : ratios )
			if ( !Double.isNaN( v ) )
				pool.merge( Math.round( v / EXTENT_RATIO_QUANTUM ), 1L, Long::sum );
		ctx.setSharedData( SHARED_SPLIT_EXTENT_POOL, pool );
		return groupedMedianPlusMads( pool, EXTENT_RATIO_QUANTUM, SPLIT_EXTENT_MADS );
	}

	/**
	 * Median plus {@code k} MADs of readings recorded on a grid of step {@code h}, each reading taken
	 * as spread uniformly over its grid cell; 0 below {@link #MIN_FOR_SPREAD} readings. Mirrors
	 * {@code grouped_median} / {@code grouped_mad} in benchmark/split-review/evaluate_decision_rule.py.
	 *
	 * @param bins grid index to count
	 */
	static double groupedMedianPlusMads( final java.util.SortedMap< Long, Long > bins, final double h,
			final double k )
	{
		long n = 0;
		for ( final long c : bins.values() )
			n += c;
		if ( n < MIN_FOR_SPREAD )
			return 0.0;
		// The grouped median: the cell holding the n/2-th reading, interpolated inside it.
		double med = Double.NaN;
		long below = 0;
		for ( final java.util.Map.Entry< Long, Long > e : bins.entrySet() )
		{
			if ( below + e.getValue() >= n / 2.0 )
			{
				med = ( e.getKey() - 0.5 ) * h + ( ( n / 2.0 - below ) / e.getValue() ) * h;
				break;
			}
			below += e.getValue();
		}
		// The MAD over each cell's spread, by midpoint sampling: a weighted median of |x - med|.
		final int m = bins.size() * GROUPED_SUBSAMPLES;
		final double[] dev = new double[ m ];
		final long[] weight = new long[ m ];
		int idx = 0;
		for ( final java.util.Map.Entry< Long, Long > e : bins.entrySet() )
			for ( int j = 0; j < GROUPED_SUBSAMPLES; j++ )
			{
				final double x = e.getKey() * h + ( ( j + 0.5 ) / GROUPED_SUBSAMPLES - 0.5 ) * h;
				dev[ idx ] = Math.abs( x - med );
				weight[ idx++ ] = e.getValue();
			}
		final Integer[] order = new Integer[ m ];
		for ( int i = 0; i < m; i++ )
			order[ i ] = i;
		java.util.Arrays.sort( order, ( a, b ) -> Double.compare( dev[ a ], dev[ b ] ) );
		final long total = n * GROUPED_SUBSAMPLES;
		long acc = 0;
		double mad = dev[ order[ m - 1 ] ];
		for ( int i = 0; i < m; i++ )
		{
			acc += weight[ order[ i ] ];
			if ( acc * 2 >= total )
			{
				// numpy's median averages the two middle values when the count is even
				if ( acc * 2 == total && i + 1 < m )
					mad = ( dev[ order[ i ] ] + dev[ order[ i + 1 ] ] ) / 2.0;
				else
					mad = dev[ order[ i ] ];
				break;
			}
		}
		return med + k * MAD_TO_STD * mad;
	}

	/** Whether any piece centre lies inside another spot's radius; {@code self} is skipped by identity. */
	static boolean pieceLandsOnAnotherDetection( final double[][] centres, final List< double[] > spots,
			final double[] self )
	{
		for ( final double[] c : centres )
			for ( final double[] s : spots )
			{
				if ( s == self )
					continue;
				final double dx = c[ 0 ] - s[ 0 ], dy = c[ 1 ] - s[ 1 ], dz = c[ 2 ] - s[ 2 ];
				if ( dx * dx + dy * dy + dz * dz < s[ 3 ] * s[ 3 ] )
					return true;
			}
		return false;
	}

	/**
	 * The split pass's funnel as one log line.
	 *
	 * <p>A method rather than an inline {@code String.format} because a format string with eleven
	 * arguments has no compile-time check and one shipped broken: every {@code adaptive_track} call
	 * died with {@code IllegalFormatConversionException} and the run reported it as a server fault.
	 * Here it can be called by a test.</p>
	 */
	public static String describeSplitFunnel( final int[] funnel )
	{
		if ( funnel == null || funnel.length < FUNNEL_SIZE )
			return "";
		final int noBackground = funnel[ FUNNEL_FRAME_NO_BACKGROUND ];
		final int notFluorescent = funnel[ FUNNEL_FRAME_NOT_FLUORESCENT ];
		final int noSpread = funnel[ FUNNEL_FRAME_NO_SPREAD ];
		final String refused = noBackground + notFluorescent + noSpread == 0 ? ""
				: String.format( " Refused %d timepoint(s) before looking at any detection: %d with no "
						+ "measured background, %d that do not read as fluorescence, %d before the run had "
						+ "seen enough extent readings to have a spread.",
						noBackground + notFluorescent + noSpread, noBackground, notFluorescent, noSpread );
		if ( funnel[ FUNNEL_CONSIDERED ] == 0 )
			return refused.isEmpty() ? "" : "  Split:" + refused;
		return String.format(
				"  Split: %d of %d detection(s) covered more than one cell and were replaced by two. The "
						+ "other %d were refused: %d whose extent walk stayed within the run's ordinary "
						+ "spread (bar %.3f = median + %.1f MADs), %d with no extent reading, %d whose "
						+ "extent reaches an image edge, %d that one ellipse covers as well as two, %d where "
						+ "a piece would land on a detection that already exists, %d unreadable.",
				funnel[ FUNNEL_SPLIT ],
				funnel[ FUNNEL_CONSIDERED ],
				funnel[ FUNNEL_CONSIDERED ] - funnel[ FUNNEL_SPLIT ],
				funnel[ FUNNEL_EXTENT_ORDINARY ],
				funnel[ FUNNEL_BAR ] / BAR_SCALE,
				SPLIT_EXTENT_MADS,
				funnel[ FUNNEL_NO_EXTENT ],
				funnel[ FUNNEL_AT_EDGE ],
				funnel[ FUNNEL_ONE_ELLIPSE ],
				funnel[ FUNNEL_PIECE_ON_NEIGHBOUR ],
				funnel[ FUNNEL_ABSTAINED ] ) + refused;
	}





	/**
	 * Replaces detections that cover more than one cell with one detection per cell.
	 *
	 * <p>{@code resizeToMeasuredExtent}'s neighbour cap stops a detection GROWING over its neighbour,
	 * but it needs a second detection to protect. When the model produced a single detection over a
	 * touching pair there is nothing to protect, and nothing in the pipeline split it -- the merge
	 * arbiter answers "are these two seeds one object?", which is the other direction.</p>
	 *
	 * <p>Splits only when three things hold, each measured against Fluo-N2DL-HeLa 01 silver truth in
	 * {@code benchmark/split-review}: the extent walk escaped further than this run's detections
	 * ordinarily do ({@link ExtentReading}, above the run's median + {@link #SPLIT_EXTENT_MADS} MADs);
	 * two ellipses cover the object's outline better than one ({@link ShapeSplit}); and neither piece
	 * lands inside another detection; and the measured extent stays clear of the image faces. See
	 * benchmark/split-review/RESULTS.md for what each condition bought. Protected spots are never split -- their
	 * geometry is the user's -- and a window that holds nothing above the level is an abstention, not
	 * a verdict of one.</p>
	 *
	 * <p>Runs before linking, so the detections being replaced carry no edges yet.</p>
	 *
	 * @return how many detections were replaced
	 */
	public static int splitMergedDetections( final ToolContext ctx, final int timepoint )
	{
		return splitMergedDetections( ctx, timepoint, null );
	}

	/** Index into {@link #splitMergedDetections}'s funnel: how many detections it looked at. */
	public static final int FUNNEL_CONSIDERED = 0;

	/** Refused: the extent walk did not escape further than this run's detections ordinarily do. */
	public static final int FUNNEL_EXTENT_ORDINARY = 1;

	/** Refused: the extent walk abstained on this detection, so there is no evidence either way. */
	public static final int FUNNEL_NO_EXTENT = 2;

	/** Refused: one ellipse covers the outline as well as two. */
	public static final int FUNNEL_ONE_ELLIPSE = 3;

	/** Refused: a piece would land inside another detection -- the neighbour is already found. */
	public static final int FUNNEL_PIECE_ON_NEIGHBOUR = 4;

	/** The image could not be read there at all. */
	public static final int FUNNEL_ABSTAINED = 5;

	/** Split. */
	public static final int FUNNEL_SPLIT = 6;

	/**
	 * The extent bar this frame actually used, times {@link #BAR_SCALE} so it fits the int array
	 * alongside the counts. A recipe is not a number: the line must say where the bar stood.
	 */
	public static final int FUNNEL_BAR = 7;

	/** Fixed-point scale for {@link #FUNNEL_BAR}. */
	public static final double BAR_SCALE = 1000.0;

	/**
	 * Timepoints the pass refused outright because it could not measure a background.
	 *
	 * <p>Counted, not merely returned from. A guard that silently declines a whole frame is the same
	 * invisible assumption as a gate that can never fire -- it just sits one level further up, where
	 * the funnel would not have been printed at all.</p>
	 */
	public static final int FUNNEL_FRAME_NO_BACKGROUND = 8;

	/** Timepoints refused outright because the images do not read as fluorescence. */
	public static final int FUNNEL_FRAME_NOT_FLUORESCENT = 9;

	/**
	 * Timepoints refused because the run had not yet seen enough extent readings to have a spread.
	 * Without one the bar is zero and every detection would pass condition 1.
	 */
	public static final int FUNNEL_FRAME_NO_SPREAD = 10;

	/**
	 * Refused: the measured extent reaches an image face, where the walk leaves the image and its
	 * reading says nothing about a second cell. Measured on the first GUI run of the rule: 118 of 188
	 * split pieces lay within 15 um of an edge, and 57 of 91 splits put a piece on background.
	 */
	public static final int FUNNEL_AT_EDGE = 11;

	/** Size of a funnel array. */
	public static final int FUNNEL_SIZE = 12;

	/**
	 * As above, tallying WHY each detection was not split.
	 *
	 * <p>Five rounds of this pass were tuned by guessing which gate had refused a case in a
	 * screenshot, and the guess was wrong four times. The gates are cheap to count, so they are
	 * counted: {@code funnelOut} is indexed by the {@code FUNNEL_*} constants and the caller prints
	 * it. A report about a decision has to be computed by the code that made it.</p>
	 */
	public static int splitMergedDetections( final ToolContext ctx, final int timepoint,
			final int[] funnelOut )
	{
		// Opt-in, and gated HERE rather than at the caller so that any later caller is gated too --
		// the merge switch reached one of four passes because it was gated at a call site.
		if ( !CleanupToggles.splitMergedEnabled( ctx ) )
			return 0;
		final BackgroundStats stats = estimateBackgroundStats( ctx, timepoint );
		final Sampler sampler = sharedSampler( ctx, timepoint );
		if ( stats == null || sampler == null || !( stats.intensityStd > 0 ) )
		{
			tally( funnelOut, FUNNEL_FRAME_NO_BACKGROUND );
			return 0;
		}
		// Not merely noisy without a measured background -- meaningless. interiorIntensityProfile
		// falls back to a base of zero, so every spread is taken about the intensity offset, the whole
		// frame reads ~0.005, and this frame's own median-plus-MADs bar lands at ~0.005 as well: about
		// half the detections clear it and get split. measured() is a sample COUNT, which the
		// intensityStd > 0 above does not imply. Until now only the 0.25 constant stood in the way, and
		// it did so by accident, which is why removing it meant writing this down.
		if ( !stats.measured() )
		{
			tally( funnelOut, FUNNEL_FRAME_NO_BACKGROUND );
			return 0;
		}
		// And this reading only exists on fluorescence. relativeSpread returns 0 for anything that is
		// not brighter than its background, so on phase contrast the pass already refused every
		// detection -- by arithmetic, with nothing in the source or the log saying so. The sibling
		// passes that depend on brightness meaning signal state the condition; this one now does too.
		if ( !ImageProfile.forRun( ctx,
				() -> sampleIntensities( ctx, timepoint, ImageProfile.SAMPLES ) ).fluorescenceLike )
		{
			tally( funnelOut, FUNNEL_FRAME_NOT_FLUORESCENT );
			return 0;
		}
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Spot > refs = new ArrayList<>();
		final List< double[] > seeds = new ArrayList<>();
		final List< double[][] > originalCovs = new ArrayList<>();
		final List< double[] > everySpot = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				spot.localize( pos );
				// Every spot, protected ones included: a piece landing on any of them is a duplicate.
				everySpot.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ],
						Math.sqrt( spot.getBoundingSphereRadiusSquared() ) } );
				if ( ProtectedSpots.isProtected( ctx, model, spot ) )
					continue;
				final Spot ref = graph.vertexRef();
				ref.refTo( spot );
				refs.add( ref );
				seeds.add( everySpot.get( everySpot.size() - 1 ) );
				final double[][] cov = new double[ 3 ][ 3 ];
				spot.getCovariance( cov );
				originalCovs.add( cov );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( refs.size() < 2 )
			return 0;
		final List< Double > allRadii = new ArrayList<>( refs.size() );
		for ( final double[] c : seeds )
			allRadii.add( c[ 3 ] );
		final double medianRadius = median( allRadii );
		if ( !( medianRadius > 0 ) )
			return 0;
		// The split dump records every detection before any gate, so the fixture holds the population
		// the gates see. The interior profile is only read for it now: no gate uses it.
		final double[] ratios = new double[ refs.size() ];
		for ( int i = 0; i < refs.size(); i++ )
		{
			ratios[ i ] = ExtentReading.get( graph, refs.get( i ) );
			if ( SplitFixtureDump.enabled() )
			{
				final double[] c = seeds.get( i );
				SplitFixtureDump.record( timepoint, c, interiorIntensityProfile( ctx, timepoint,
						new double[] { c[ 0 ], c[ 1 ], c[ 2 ] }, c[ 3 ] ), medianRadius, ratios[ i ] );
			}
		}
		// Condition 1: the extent walk escaped further than this RUN's detections ordinarily do. Pooled
		// over every reading the pass has seen, because one frame holds ~50 and its MAD swung 0.09 to
		// 0.28 between frames. See benchmark/split-review/RESULTS.md.
		final double extentBar = runExtentBar( ctx, ratios );
		if ( funnelOut != null && funnelOut.length > FUNNEL_BAR )
			funnelOut[ FUNNEL_BAR ] = ( int ) Math.round( extentBar * BAR_SCALE );
		if ( !( extentBar > 0 ) )
		{
			tally( funnelOut, FUNNEL_FRAME_NO_SPREAD );
			return 0;
		}
		// The object's outline is read against the FRAME's own level, as the prototype it was measured
		// on: median plus SHAPE_LEVEL_SIGMA spreads of every pixel, not the emptiest-regions background
		// (see frameLevelStats for why those two are not interchangeable).
		final double[] frame = frameLevelStats( ctx, timepoint );
		final Sampler full = fullResolutionSampler( ctx, timepoint );
		if ( frame == null || full == null )
		{
			tally( funnelOut, FUNNEL_FRAME_NO_BACKGROUND );
			return 0;
		}
		final double level = frame[ 0 ] + SHAPE_LEVEL_SIGMA * frame[ 1 ];

		// Fails CLOSED, unlike ImageBounds' other callers: they delete what reaches the border, so not
		// knowing must mean "not at the border"; here reaching it means "do not split", so not knowing
		// must mean the same.
		final double[] imageExtent = ImageBounds.physicalExtent( ctx );
		final boolean volumetric = ctx.isVolumetric();

		// Image reads with no lock held. Each entry is the two pieces the detection is replaced by.
		final List< SplitPlan > replacements = new ArrayList<>();
		for ( int i = 0; i < refs.size(); i++ )
		{
			final double[] c = seeds.get( i );
			tally( funnelOut, FUNNEL_CONSIDERED );
			if ( Double.isNaN( ratios[ i ] ) )
			{
				tally( funnelOut, FUNNEL_NO_EXTENT );
				continue;
			}
			if ( !( ratios[ i ] > extentBar ) )
			{
				tally( funnelOut, FUNNEL_EXTENT_ORDINARY );
				continue;
			}
			// The reading above is only evidence of a second cell where the walk stayed inside the
			// image. At a face the rays run out of image and the extent reads large for a fragment.
			if ( imageExtent == null || ImageBounds.reachesBorder( imageExtent, c, ratios[ i ] * c[ 3 ], volumetric ) )
			{
				tally( funnelOut, FUNNEL_AT_EDGE );
				continue;
			}
			// Where the pieces go, and their shape: one ellipse or two fitted to the outline of the
			// object under the detection. Replaces counting intensity basins, which put each piece on
			// its own cell for 5 of 19 real pairs it split and made three pieces of 10 of them; this put
			// the two centres on two different truth cells for 228 of 257 (89%). See ShapeSplit.
			final ShapeWindow win;
			final ShapeSplit.Result shape;
			try
			{
				win = ShapeWindow.read( full, c );
				shape = win == null ? null
						: ShapeSplit.analyse( win.pixels, win.w, win.h, level, win.cx, win.cy, c[ 3 ] / win.step );
			}
			catch ( final RuntimeException e )
			{
				log.debug( "split window unavailable at t={}", timepoint, e );
				tally( funnelOut, FUNNEL_ABSTAINED );
				continue;
			}
			if ( shape == null )
			{
				tally( funnelOut, FUNNEL_ABSTAINED );
				continue;
			}
			// Condition 2: two ellipses cover the outline better than one. A two-component fit succeeds
			// on almost any blob; whether it EXPLAINS the shape better is the evidence.
			if ( !shape.twoFitted() || !( shape.gain() > 0 ) )
			{
				tally( funnelOut, FUNNEL_ONE_ELLIPSE );
				continue; // one object: leave it exactly as it is
			}
			final SplitPlan plan = new SplitPlan( i );
			for ( int k = 0; k < 2; k++ )
			{
				plan.centres[ k ] = win.toWorld( full, shape.means[ k ][ 0 ], shape.means[ k ][ 1 ] );
				plan.covs[ k ] = pieceCovariance( shape.covs[ k ], win.step, originalCovs.get( i ) );
			}
			// Condition 3: neither piece may land inside another detection. Where the walk escaped into
			// a neighbour the detector already found, the second piece lands on THAT cell -- the
			// commonest failure on held-out frames (87 of 234) before this test existed.
			if ( pieceLandsOnAnotherDetection( plan.centres, everySpot, c ) )
			{
				tally( funnelOut, FUNNEL_PIECE_ON_NEIGHBOUR );
				continue;
			}
			replacements.add( plan );
		}
		if ( replacements.isEmpty() )
			return 0;
		int split = 0;
		graph.getLock().writeLock().lock();
		try
		{
			final Spot ref = graph.vertexRef();
			for ( final SplitPlan plan : replacements )
			{
				final int i = plan.index;
				// ADD before removing, so a frame never dips below the cell-count floor mid-edit and
				// a refused add leaves the original standing rather than losing the cell entirely.
				// Each piece takes its own fitted ellipse; they used to take the frame's median radius.
				int added = 0;
				for ( int k = 0; k < 2; k++ )
				{
					if ( GraphEdits.addSpot( ctx, graph, ref, timepoint, plan.centres[ k ], plan.covs[ k ],
							GraphEdits.Reason.SPLIT ) != null )
						added++;
				}
				if ( added >= 2 )
				{
					tally( funnelOut, FUNNEL_SPLIT );
					// SPLIT, not DUPLICATE and not DETECTION. A duplicate must be merged and never
					// resolved by deleting a member; DETECTION may only add. Both chokepoint tests
					// refuse those, correctly -- this is the mirror image of a duplicate and needs its
					// own reason. The replacements are already in the graph by this line.
					GraphEdits.removeSpot( ctx, graph, refs.get( i ), GraphEdits.Reason.SPLIT );
					split++;
				}
			}
			graph.releaseRef( ref );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( split > 0 )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		for ( final Spot ref : refs )
			graph.releaseRef( ref );
		return split;
	}

	/** Spreads above the frame's median at which a pixel belongs to an object, for {@link ShapeSplit}. */
	static final double SHAPE_LEVEL_SIGMA = 5.0;

	/** Window half-side, in detection radii, read around a split candidate. */
	static final double SHAPE_WINDOW_REACH = 1.4;

	/** A detection the split pass will replace, and what with. */
	private static final class SplitPlan
	{
		final int index;

		final double[][] centres = new double[ 2 ][];

		final double[][][] covs = new double[ 2 ][][];

		SplitPlan( final int index )
		{
			this.index = index;
		}
	}

	/**
	 * Level-0 pixels around a detection, laid out as {@code benchmark/split-review} reads them: whole
	 * voxels from {@code int(centre) - half} to {@code int(centre) + half}, clipped to the image, so
	 * the Java and Python numbers are taken on the same grid.
	 */
	static final class ShapeWindow
	{
		final double[] pixels;

		final int w, h;

		final long x0, y0, z;

		/** Detection centre in window pixels. */
		final double cx, cy;

		/** World length of one pixel, in plane. */
		final double step;

		private ShapeWindow( final double[] pixels, final int w, final int h, final long x0, final long y0,
				final long z, final double cx, final double cy, final double step )
		{
			this.pixels = pixels;
			this.w = w;
			this.h = h;
			this.x0 = x0;
			this.y0 = y0;
			this.z = z;
			this.cx = cx;
			this.cy = cy;
			this.step = step;
		}

		/** @param seed {x, y, z, radius} in world units; null when the window is empty */
		static ShapeWindow read( final Sampler s, final double[] seed )
		{
			final double[] v = new double[ 3 ];
			s.toVoxel( new double[] { seed[ 0 ], seed[ 1 ], seed[ 2 ] }, v );
			final double step = s.voxelSize( 0 );
			final double rPx = seed[ 3 ] / step;
			final long half = ( long ) Math.ceil( SHAPE_WINDOW_REACH * rPx ) + 1;
			final long ix = ( long ) v[ 0 ], iy = ( long ) v[ 1 ];
			final long x0 = Math.max( s.min( 0 ), ix - half ), y0 = Math.max( s.min( 1 ), iy - half );
			final long x1 = Math.min( s.max( 0 ), ix + half ), y1 = Math.min( s.max( 1 ), iy + half );
			final long z = Math.max( s.min( 2 ), Math.min( s.max( 2 ), Math.round( v[ 2 ] ) ) );
			final int w = ( int ) ( x1 - x0 + 1 ), h = ( int ) ( y1 - y0 + 1 );
			if ( w <= 0 || h <= 0 )
				return null;
			final double[] px = new double[ w * h ];
			for ( int y = 0; y < h; y++ )
				for ( int x = 0; x < w; x++ )
					px[ y * w + x ] = s.atVoxel( x0 + x, y0 + y, z );
			return new ShapeWindow( px, w, h, x0, y0, z, v[ 0 ] - x0, v[ 1 ] - y0, step );
		}

		/** A window pixel position back to world units. */
		double[] toWorld( final Sampler s, final double wx, final double wy )
		{
			final double[] out = new double[ 3 ];
			s.toWorld( new double[] { x0 + wx, y0 + wy, z }, out );
			return out;
		}
	}

	/**
	 * A piece's covariance in world units. The fitted component is read as a uniform ellipse with
	 * semi-axes 2 sqrt(eigenvalues); a Mastodon spot's covariance has the squared semi-axes as its
	 * eigenvalues, hence the 4. Out of plane the original detection's extent is kept: the fit is 2D.
	 */
	static double[][] pieceCovariance( final double[] comp, final double step, final double[][] original )
	{
		final double s2 = 4.0 * step * step;
		final double[][] cov = new double[ 3 ][ 3 ];
		cov[ 0 ][ 0 ] = s2 * comp[ 0 ];
		cov[ 0 ][ 1 ] = cov[ 1 ][ 0 ] = s2 * comp[ 1 ];
		cov[ 1 ][ 1 ] = s2 * comp[ 2 ];
		cov[ 2 ][ 2 ] = original[ 2 ][ 2 ];
		return cov;
	}

	/**
	 * Largest radius detection {@code i} may take without covering another detection's centre.
	 *
	 * <p>Unbounded ({@link Double#MAX_VALUE}) when it has no neighbour at this timepoint. Each seed is
	 * {@code {x, y, z, radius}}.</p>
	 */
	static double neighbourLimit( final List< double[] > seeds, final int i )
	{
		final double[] a = seeds.get( i );
		double nearest = Double.MAX_VALUE;
		for ( int j = 0; j < seeds.size(); j++ )
		{
			if ( j == i )
				continue;
			final double[] b = seeds.get( j );
			final double dx = a[ 0 ] - b[ 0 ], dy = a[ 1 ] - b[ 1 ], dz = a[ 2 ] - b[ 2 ];
			nearest = Math.min( nearest, Math.sqrt( dx * dx + dy * dy + dz * dz ) );
		}
		return nearest;
	}

	/**
	 * By how much to rescale a detection of radius {@code seedRadius} that the image measures at
	 * {@code measured}, or {@code 0} to leave it alone.
	 *
	 * <p>Left alone when the image gave no reading, when the seed radius is meaningless, and inside
	 * the dead zone -- a detection already within 10% of its measurement is not worth re-writing.
	 * Outside that, clamped: see {@link #EXTENT_MIN_FACTOR}.</p>
	 */
	static double extentFactor( final double measured, final double seedRadius )
	{
		if ( Double.isNaN( measured ) || !( measured > 0 ) || !( seedRadius > 0 ) )
			return 0.0;
		final double f = Math.max( EXTENT_MIN_FACTOR,
				Math.min( EXTENT_MAX_FACTOR, measured / seedRadius ) );
		return Math.abs( f - 1.0 ) < EXTENT_DEAD_ZONE ? 0.0 : f;
	}

	/**
	 * Resizes each detection at {@code timepoint} to the cell measured around it in the IMAGE.
	 *
	 * <p>Until now nothing resized a detection. {@code restoreAnnotationExtent} applies one population
	 * multiplier to what is SENT FOR TRAINING, clamped to at most 1.3x and grow-only; the spots in the
	 * graph keep whatever radius ELEPHANT returned. So every downstream question asked about a
	 * detection -- does it overlap its neighbour, is it the right size to be this cell's daughter, how
	 * bright is its interior -- was asked of an ellipsoid nobody had checked against the image.</p>
	 *
	 * <p>The interior readings are the sharpest case. A detection wider than the body under it puts
	 * most of its samples on background, so it reads as background however bright that body is, and
	 * the no-signal ceiling takes it. Small bright things are exactly what is at stake around a
	 * division. {@code interiorIntensityProfile}'s core reading guards that directly; this removes the
	 * cause.</p>
	 *
	 * <p>Protected spots are never resized -- their geometry is the user's. A detection the image
	 * cannot measure (fewer than half the rays find an edge, or the seed is not above the floor) is
	 * left exactly as it was: {@link #measuredCellRadius} returning NaN means "no reading", never
	 * "small".</p>
	 *
	 * @return how many detections were resized
	 */
	public static int resizeToMeasuredExtent( final ToolContext ctx, final int timepoint )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Spot > refs = new ArrayList<>();
		final List< double[] > seeds = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				if ( ProtectedSpots.isProtected( ctx, model, spot ) )
					continue;
				spot.localize( pos );
				final Spot ref = graph.vertexRef();
				ref.refTo( spot );
				refs.add( ref );
				seeds.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ],
						Math.sqrt( spot.getBoundingSphereRadiusSquared() ) } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		// Image reads with no lock held.
		final double[] factors = new double[ refs.size() ];
		int resized = 0;
		final double[] readings = new double[ refs.size() ];
		for ( int i = 0; i < refs.size(); i++ )
		{
			final double[] c = seeds.get( i );
			final double measured = measuredCellRadius( ctx, timepoint,
					new double[] { c[ 0 ], c[ 1 ], c[ 2 ] }, c[ 3 ] );
			// Kept before the clamp: a reading above EXTENT_MAX_FACTOR is the walk escaping into a
			// neighbour, which is what the split pass needs to know. See ExtentReading. Written below,
			// under the lock, onto the spot only if it is still the one measured.
			readings[ i ] = measured > 0 && c[ 3 ] > 0 ? measured / c[ 3 ] : Double.NaN;
			double f = extentFactor( measured, c[ 3 ] );
			if ( f == 0.0 )
				continue;
			// A detection may never grow over another detection's CENTRE. Where the model put two
			// detections it separated two cells, and a geometric rule read off the image does not get
			// to overrule that -- the user's standing instruction, and the reason duplicate merging is
			// opt-in. The ray walk cannot see the difference: between two touching nuclei there is no
			// drop below the level it stops at, so it measures the pair.
			f = Math.min( f, neighbourLimit( seeds, i ) / c[ 3 ] );
			if ( Math.abs( f - 1.0 ) < EXTENT_DEAD_ZONE || f <= 0.0 )
				continue;
			factors[ i ] = f;
			resized++;
		}
		graph.getLock().writeLock().lock();
		try
		{
			// The image was read without the lock: a spot deleted or replaced in the viewer meanwhile is
			// left alone (GraphWatch), the others get their reading and their new size.
			for ( int i = 0; i < refs.size(); i++ )
			{
				if ( GraphWatch.dirty( graph, refs.get( i ) ) )
				{
					GraphWatch.refused( graph );
					if ( factors[ i ] != 0.0 )
						resized--;
					factors[ i ] = 0.0;
				}
				else
					ExtentReading.set( graph, refs.get( i ), readings[ i ] );
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( resized > 0 )
		{
			graph.getLock().writeLock().lock();
			try
			{
				final double[][] cov = new double[ 3 ][ 3 ];
				for ( int i = 0; i < refs.size(); i++ )
				{
					if ( factors[ i ] == 0.0 || GraphWatch.dirty( graph, refs.get( i ) ) )
						continue;
					// size is sqrt(trace(covariance)), so a linear factor k scales the covariance by k*k.
					final Spot spot = refs.get( i );
					spot.getCovariance( cov );
					final double fSq = factors[ i ] * factors[ i ];
					for ( int a = 0; a < 3; a++ )
						for ( int b = 0; b < 3; b++ )
							cov[ a ][ b ] *= fSq;
					spot.setCovariance( cov );
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		for ( final Spot ref : refs )
			graph.releaseRef( ref );
		return resized;
	}

	/** Smoothing before the peak search, as a fraction of the frame's median in-plane detection semi-axis. */
	static final double RECOVERY_SIGMA_FACTOR = 0.7;

	/** A peak is a candidate at all only this many MADs above the smoothed frame's median. */
	static final double RECOVERY_NOISE_MADS = 3.0;

	/**
	 * An uncovered peak is added when it is at least as bright as this quantile of the peaks the frame's own
	 * detections cover. Measured on CE 01 t=120..159 (z-refit run): 96 of 331 missed cells added, 0 on no cell;
	 * at the 5th percentile 80. The scores of uncovered peaks on no cell never passed 9.2 MADs; this bar sat at
	 * about 16.5.
	 */
	static final double RECOVERY_QUANTILE = 0.01;

	/** Fewer covered peaks than this and the frame has no measured bar: nothing is added. */
	static final int RECOVERY_MIN_COVERED = 20;

	/**
	 * Adds a detection at each nucleus the image shows and no detection covers, at {@code timepoint}, tagged
	 * {@code fn} so the next training round learns it as a missed cell (the user's choice, 2026-10-24: on
	 * fluorescence the measured bar added no false cell). This is what a person does in ELEPHANT before going
	 * on to the next window.
	 *
	 * <p>Truth-free. The frame is block-averaged in plane, smoothed at {@link #RECOVERY_SIGMA_FACTOR} of the
	 * median detection's in-plane semi-axis (so the scale follows the cells as they shrink), and its local
	 * maxima within one such radius are scored in MADs above the frame's median. The bar is the
	 * {@link #RECOVERY_QUANTILE} of the scores of the maxima the frame's own detections cover: a peak at least
	 * as bright as the dimmest real cells detected. A peak inside no detection's ellipsoid, at or over the bar,
	 * with no detection's centre inside the new one, gets a detection with the frame's median shape. Brightest
	 * first, so two peaks on one nucleus add one.</p>
	 *
	 * @return detections added
	 */
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public static int recoverUncoveredNuclei( final ToolContext ctx, final int timepoint )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< double[] > pos = new ArrayList<>();
		final List< double[][] > inv = new ArrayList<>();
		final List< double[] > diag = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[][] c = new double[ 3 ][ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint )
					continue;
				s.getCovariance( c );
				final double[][] iv = ShapeSplit3D.invert3( c );
				if ( iv == null )
					continue;
				final double[] p = new double[ 3 ];
				s.localize( p );
				pos.add( p );
				inv.add( iv );
				diag.add( new double[] { c[ 0 ][ 0 ], c[ 1 ][ 1 ], c[ 2 ][ 2 ] } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( pos.size() < RECOVERY_MIN_COVERED )
			return 0;
		final double[] median = new double[ 3 ];
		for ( int d = 0; d < 3; d++ )
		{
			final double[] v = new double[ diag.size() ];
			for ( int i = 0; i < v.length; i++ )
				v[ i ] = diag.get( i )[ d ];
			java.util.Arrays.sort( v );
			median[ d ] = v[ v.length / 2 ];
		}
		final double rMed = Math.sqrt( ( median[ 0 ] + median[ 1 ] ) / 2 );
		final double[][] newCov = { { median[ 0 ], 0, 0 }, { 0, median[ 1 ], 0 }, { 0, 0, median[ 2 ] } };
		final double[][] newInv = ShapeSplit3D.invert3( newCov );
		if ( !( rMed > 0 ) || newInv == null )
			return 0;

		// The frame, block-averaged in plane so the smoothing is a few voxels wide.
		final Source source;
		try
		{
			source = ctx.requireViewer().state().getSources().get( 0 ).getSpimSource();
		}
		catch ( final RuntimeException noImage )
		{
			return 0;
		}
		if ( !source.isPresent( timepoint ) )
			return 0;
		final AffineTransform3D toWorld = new AffineTransform3D();
		source.getSourceTransform( timepoint, 0, toWorld );
		final RandomAccessibleInterval< ? extends RealType< ? > > rai = source.getSource( timepoint, 0 );
		if ( rai.numDimensions() < 3 )
			return 0;
		final double vx = Math.sqrt( Math.pow( toWorld.get( 0, 0 ), 2 ) + Math.pow( toWorld.get( 1, 0 ), 2 ) + Math.pow( toWorld.get( 2, 0 ), 2 ) );
		final double vz = Math.sqrt( Math.pow( toWorld.get( 0, 2 ), 2 ) + Math.pow( toWorld.get( 1, 2 ), 2 ) + Math.pow( toWorld.get( 2, 2 ), 2 ) );
		final double sigma = RECOVERY_SIGMA_FACTOR * rMed;
		final int f = Math.max( 1, ( int ) Math.round( sigma / 3 / vx ) );
		final long x0 = rai.min( 0 ), y0 = rai.min( 1 ), z0 = rai.min( 2 );
		final int nx = ( int ) ( rai.dimension( 0 ) / f ), ny = ( int ) ( rai.dimension( 1 ) / f ), nz = ( int ) rai.dimension( 2 );
		if ( nx < 3 || ny < 3 || nz < 3 )
			return 0;
		final float[] img = new float[ nx * ny * nz ];
		final net.imglib2.Cursor< ? extends RealType< ? > > cur = net.imglib2.view.Views.flatIterable( rai ).localizingCursor();
		final long[] at = new long[ 3 ];
		while ( cur.hasNext() )
		{
			final double v = cur.next().getRealDouble();
			cur.localize( at );
			final int bx = ( int ) ( ( at[ 0 ] - x0 ) / f ), by = ( int ) ( ( at[ 1 ] - y0 ) / f );
			if ( bx >= nx || by >= ny )
				continue;
			img[ ( ( int ) ( at[ 2 ] - z0 ) * ny + by ) * nx + bx ] += ( float ) ( v / ( f * f ) );
		}
		final net.imglib2.img.array.ArrayImg< net.imglib2.type.numeric.real.FloatType, ? > in =
				net.imglib2.img.array.ArrayImgs.floats( img, nx, ny, nz );
		final float[] sm = new float[ img.length ];
		final net.imglib2.img.array.ArrayImg< net.imglib2.type.numeric.real.FloatType, ? > out =
				net.imglib2.img.array.ArrayImgs.floats( sm, nx, ny, nz );
		net.imglib2.algorithm.gauss3.Gauss3.gauss( new double[] { sigma / ( vx * f ), sigma / ( vx * f ), sigma / vz },
				net.imglib2.view.Views.extendMirrorSingle( in ), out );
		final float[] sorted = sm.clone();
		java.util.Arrays.sort( sorted );
		final double med = sorted[ sorted.length / 2 ];
		final float[] dev = new float[ sm.length ];
		for ( int i = 0; i < sm.length; i++ )
			dev[ i ] = ( float ) Math.abs( sm[ i ] - med );
		java.util.Arrays.sort( dev );
		final double mad = dev[ dev.length / 2 ] * 1.4826;
		if ( !( mad > 0 ) )
			return 0;

		// Local maxima within one radius, scored; each covered by a detection or not.
		final int hx = Math.max( 1, ( int ) Math.round( rMed / ( vx * f ) ) ), hz = Math.max( 1, ( int ) Math.floor( rMed / vz ) );
		final double floor = med + RECOVERY_NOISE_MADS * mad;
		final List< Double > coveredScores = new ArrayList<>();
		final List< double[] > uncovered = new ArrayList<>(); // {score, x, y, z} world
		final double[] vox = new double[ 3 ], w = new double[ 3 ];
		for ( int z = 0; z < nz; z++ )
			for ( int y = 0; y < ny; y++ )
				for ( int x = 0; x < nx; x++ )
				{
					final float v = sm[ ( z * ny + y ) * nx + x ];
					if ( v <= floor )
						continue;
					boolean peak = true;
					for ( int dz = -hz; dz <= hz && peak; dz++ )
						for ( int dy = -hx; dy <= hx && peak; dy++ )
							for ( int dx = -hx; dx <= hx; dx++ )
							{
								final int zz = z + dz, yy = y + dy, xx = x + dx;
								if ( zz < 0 || zz >= nz || yy < 0 || yy >= ny || xx < 0 || xx >= nx || ( dx == 0 && dy == 0 && dz == 0 ) )
									continue;
								if ( sm[ ( zz * ny + yy ) * nx + xx ] > v )
								{
									peak = false;
									break;
								}
							}
					if ( !peak )
						continue;
					vox[ 0 ] = x0 + x * f + ( f - 1 ) / 2.0;
					vox[ 1 ] = y0 + y * f + ( f - 1 ) / 2.0;
					vox[ 2 ] = z0 + z;
					toWorld.apply( vox, w );
					final double score = ( v - med ) / mad;
					boolean covered = false;
					for ( int i = 0; i < pos.size() && !covered; i++ )
						covered = ShapeSplit3D.mahalanobis2Public( w, pos.get( i ), inv.get( i ) ) <= 1;
					if ( covered )
						coveredScores.add( score );
					else
						uncovered.add( new double[] { score, w[ 0 ], w[ 1 ], w[ 2 ] } );
				}
		if ( coveredScores.size() < RECOVERY_MIN_COVERED || uncovered.isEmpty() )
			return 0;
		java.util.Collections.sort( coveredScores );
		final double bar = coveredScores.get( ( int ) Math.floor( RECOVERY_QUANTILE * ( coveredScores.size() - 1 ) ) );
		uncovered.sort( ( a, b ) -> Double.compare( b[ 0 ], a[ 0 ] ) );
		final List< double[] > add = new ArrayList<>();
		for ( final double[] u : uncovered )
		{
			if ( u[ 0 ] < bar )
				break;
			final double[] c = { u[ 1 ], u[ 2 ], u[ 3 ] };
			boolean clash = false;
			for ( int i = 0; i < pos.size() && !clash; i++ )
				clash = ShapeSplit3D.mahalanobis2Public( pos.get( i ), c, newInv ) <= 1;
			for ( int i = 0; i < add.size() && !clash; i++ )
				clash = ShapeSplit3D.mahalanobis2Public( add.get( i ), c, newInv ) <= 1;
			if ( !clash )
				add.add( c );
		}
		if ( add.isEmpty() )
			return 0;
		// Modifies the tag-set STRUCTURE, so before the vertex write lock.
		final TagSet tagSet = org.mastodon.mamut.automahout.elephant.DetectionTagLabels.ensure( model );
		final Tag fn = org.mastodon.mamut.automahout.elephant.DetectionTagLabels.fnTag( tagSet );
		final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();
		int added = 0;
		final Spot ref = graph.vertexRef();
		try
		{
			for ( final double[] c : add )
			{
				final Spot s = GraphEdits.addSpot( ctx, graph, ref, timepoint, c, newCov, GraphEdits.Reason.DETECTION );
				if ( s == null )
					continue;
				added++;
				if ( fn != null )
				{
					graph.getLock().writeLock().lock();
					try
					{
						vertexTags.set( s, fn );
					}
					finally
					{
						graph.getLock().writeLock().unlock();
					}
				}
			}
		}
		finally
		{
			graph.releaseRef( ref );
		}
		if ( added > 0 )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		return added;
	}

	/** How many planes above and below a flat detection its z profile is read, at most. */
	static final int Z_PROFILE_REACH_PLANES = 4;

	/** In-plane samples per plane of a z profile, at most (a grid over the detection's in-plane ellipse). */
	private static final int Z_PROFILE_SAMPLES = 121;

	/**
	 * Re-places each one-plane-thick detection at {@code timepoint} on its nucleus in z. On a stack ELEPHANT
	 * returns many detections whose z semi-axis is one voxel, centred on a whole plane one or two planes off
	 * the nucleus's centre: measured on CE 01 t=120..159, 1107 detections were the only one on a truth cell and
	 * held none, all of them one plane thick (z semi-axis 1.00 µm, median in-plane 1.3 µm), 1-2 planes above
	 * or below it. They count as a missed cell and a stray detection both, and as training labels they teach
	 * the model a flat core.
	 *
	 * <p>Truth-free: the image's brightness inside the detection's in-plane ellipse, plane by plane (the
	 * median, so a neighbour's edge does not lift it). From the detection's own plane it climbs to the nearest
	 * peak, takes the run of planes around it above half the way from the profile's floor to that peak, and
	 * puts the centre at that run's brightness-weighted mean, the z semi-axis at half its length. In-plane
	 * shape and position are kept. Only a detection whose z semi-axis is at most one plane is touched; a
	 * protected one never is.</p>
	 *
	 * @return detections re-placed
	 */
	public static int refitFlatDetectionsInZ( final ToolContext ctx, final int timepoint )
	{
		if ( !ctx.isVolumetric() )
			return 0;
		final Sampler sampler;
		try
		{
			sampler = fullResolutionSampler( ctx, timepoint );
		}
		catch ( final RuntimeException noImage )
		{
			return 0;
		}
		if ( sampler == null )
			return 0;
		final double vz = sampler.voxelSize( 2 );
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Spot > refs = new ArrayList<>();
		final List< double[] > pos = new ArrayList<>();
		final List< double[][] > covs = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint || ProtectedSpots.isProtected( ctx, model, s ) )
					continue;
				final double[][] c = new double[ 3 ][ 3 ];
				s.getCovariance( c );
				if ( !( c[ 2 ][ 2 ] <= vz * vz * 1.0001 ) )
					continue;
				final double[] p = new double[ 3 ];
				s.localize( p );
				refs.add( graph.vertexRef().refTo( s ) );
				pos.add( p );
				covs.add( c );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		try
		{
			// Image reads with no lock held.
			final double[][] placed = new double[ refs.size() ][];
			for ( int i = 0; i < refs.size(); i++ )
				placed[ i ] = zProfileFit( sampler, pos.get( i ), covs.get( i ), vz );
			int moved = 0;
			graph.getLock().writeLock().lock();
			try
			{
				for ( int i = 0; i < refs.size(); i++ )
				{
					if ( placed[ i ] == null || GraphWatch.dirty( graph, refs.get( i ) ) )
						continue;
					final double[] p = pos.get( i ).clone();
					p[ 2 ] = placed[ i ][ 0 ];
					final double[][] c = covs.get( i );
					c[ 0 ][ 2 ] = c[ 2 ][ 0 ] = c[ 1 ][ 2 ] = c[ 2 ][ 1 ] = 0;
					c[ 2 ][ 2 ] = placed[ i ][ 1 ] * placed[ i ][ 1 ];
					refs.get( i ).setPosition( p );
					refs.get( i ).setCovariance( c );
					moved++;
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			if ( moved > 0 )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return moved;
		}
		finally
		{
			for ( final Spot s : refs )
				graph.releaseRef( s );
		}
	}

	/**
	 * {z centre, z semi-axis} (world) of the nucleus under a flat detection, from the median brightness inside
	 * its in-plane ellipse on each plane within {@link #Z_PROFILE_REACH_PLANES}; {@code null} when the image
	 * shows no peak (a flat profile) or the detection is outside it.
	 */
	static double[] zProfileFit( final Sampler sampler, final double[] centre, final double[][] cov, final double vz )
	{
		final double[] v = new double[ 3 ];
		sampler.toVoxel( centre, v );
		final long z0 = Math.round( v[ 2 ] );
		if ( z0 < sampler.min( 2 ) || z0 > sampler.max( 2 ) )
			return null;
		final long lo = Math.max( sampler.min( 2 ), z0 - Z_PROFILE_REACH_PLANES );
		final long hi = Math.min( sampler.max( 2 ), z0 + Z_PROFILE_REACH_PLANES );
		final int n = ( int ) ( hi - lo + 1 );
		if ( n < 3 )
			return null;
		// In-plane sample points: a grid over the ellipse's bounding box, kept where inside the ellipse.
		final double a = cov[ 0 ][ 0 ], b = cov[ 0 ][ 1 ], d = cov[ 1 ][ 1 ];
		final double det = a * d - b * b;
		if ( !( det > 0 ) )
			return null;
		final double rx = Math.sqrt( a ), ry = Math.sqrt( d );
		final int side = ( int ) Math.sqrt( Z_PROFILE_SAMPLES );
		final List< double[] > pts = new ArrayList<>();
		for ( int i = 0; i < side; i++ )
			for ( int j = 0; j < side; j++ )
			{
				final double dx = rx * ( 2.0 * ( i + 0.5 ) / side - 1 ), dy = ry * ( 2.0 * ( j + 0.5 ) / side - 1 );
				if ( ( d * dx * dx - 2 * b * dx * dy + a * dy * dy ) / det <= 1.0 )
					pts.add( new double[] { centre[ 0 ] + dx, centre[ 1 ] + dy } );
			}
		if ( pts.isEmpty() )
			return null;
		final double[] prof = new double[ n ];
		final double[] w = new double[ 3 ], vox = new double[ 3 ];
		final double[] vals = new double[ pts.size() ];
		for ( int k = 0; k < n; k++ )
		{
			for ( int q = 0; q < pts.size(); q++ )
			{
				w[ 0 ] = pts.get( q )[ 0 ];
				w[ 1 ] = pts.get( q )[ 1 ];
				w[ 2 ] = centre[ 2 ];
				sampler.toVoxel( w, vox );
				vals[ q ] = sampler.atVoxel( Math.round( vox[ 0 ] ), Math.round( vox[ 1 ] ), lo + k );
			}
			java.util.Arrays.sort( vals );
			prof[ k ] = vals[ vals.length / 2 ];
		}
		final double[] run = zRun( prof, ( int ) ( z0 - lo ) );
		if ( run == null )
			return null;
		// The plane index back to world z, through the sampler's own transform at the detection's x, y.
		final double[] out = new double[ 3 ];
		v[ 2 ] = lo + run[ 0 ];
		sampler.toWorld( v, out );
		return new double[] { out[ 2 ], Math.max( vz, run[ 1 ] * vz / 2 ) };
	}

	/**
	 * {brightness-weighted centre index, length in planes} of the run of {@code prof} above half the way from
	 * its floor to the peak climbed to from index {@code start}; {@code null} for a profile with no peak.
	 */
	static double[] zRun( final double[] prof, final int start )
	{
		int i = start;
		while ( true )
		{
			int best = i;
			if ( i > 0 && prof[ i - 1 ] > prof[ best ] )
				best = i - 1;
			if ( i + 1 < prof.length && prof[ i + 1 ] > prof[ best ] )
				best = i + 1;
			if ( best == i )
				break;
			i = best;
		}
		double floor = Double.POSITIVE_INFINITY;
		for ( final double x : prof )
			floor = Math.min( floor, x );
		if ( !( prof[ i ] > floor ) )
			return null;
		final double half = floor + 0.5 * ( prof[ i ] - floor );
		int a = i, b = i;
		while ( a > 0 && prof[ a - 1 ] >= half )
			a--;
		while ( b + 1 < prof.length && prof[ b + 1 ] >= half )
			b++;
		double sw = 0, sz = 0;
		for ( int k = a; k <= b; k++ )
		{
			sw += prof[ k ] - floor;
			sz += ( prof[ k ] - floor ) * k;
		}
		return new double[] { sz / sw, b - a + 1 };
	}

	/** Spot intensity minus the mean intensity of a background shell ~2.5×radius away (local contrast). */
	private static double sampleContrast( final SourceAndConverter< ? > sac, final int t,
			final double[] worldPos, final double radius )
	{
		return sampleContrast( Sampler.of( sac, t, 0 ), worldPos, radius );
	}

	/** The seven reads of a contrast measurement, through one already-open {@link Sampler}. */
	private static double sampleContrast( final Sampler sampler, final double[] worldPos,
			final double radius )
	{
		if ( sampler == null )
			return 0.0;
		final double spot = sampler.at( worldPos );
		final double d = Math.max( radius * 2.5, 2.0 );
		double sum = 0;
		int n = 0;
		final double[] p = new double[ 3 ];
		for ( int axis = 0; axis < 3; axis++ )
		{
			for ( int sign = -1; sign <= 1; sign += 2 )
			{
				p[ 0 ] = worldPos[ 0 ];
				p[ 1 ] = worldPos[ 1 ];
				p[ 2 ] = worldPos[ 2 ];
				p[ axis ] += sign * d;
				sum += sampler.at( p );
				n++;
			}
		}
		return spot - ( n > 0 ? sum / n : 0.0 );
	}

	/**
	 * Offsets, as fractions of the spot radius, at which {@link #intensityHeterogeneity} samples the
	 * inside of a detection: the centre, the six axis directions at half and at four-fifths of the
	 * radius, and the eight diagonals in between. Twenty-one points, which is enough to notice a dark
	 * region inside an otherwise bright body and cheap enough to run on every detection.
	 */
	private static final double[][] SPOT_INTERIOR_OFFSETS = buildSpotInteriorOffsets( true );

	/**
	 * The same pattern with the z displacements removed, for a dataset that has one image plane.
	 *
	 * <p>Twelve of the twenty-one offsets carry a z component — four along the z axis at 0.5r and 0.8r,
	 * and all eight diagonals. On one plane those land several planes outside an image that has none,
	 * and whatever the sampler returns there is not this cell. With a nucleus radius near 9.5 µm and a
	 * 0.645 µm z voxel, an offset of 0.5r is seven planes away from the only plane there is.</p>
	 *
	 * <p>That is 57% of the samples, and it drags the interior mean of a real cell down towards the
	 * background — which is exactly what {@code deleteSpotsWithNoSignal} compares it against. The pass
	 * reported "REFUSED: the ceiling would have taken more than 50% of the frame" on every timepoint of
	 * every run on this dataset, and refusing is all it has ever done here. The same twenty-one reads
	 * are also the debris check's coefficient of variation, so a third of that measurement was the
	 * image not existing.</p>
	 *
	 * <p>Third instance of this family: {@link org.mastodon.mamut.automahout.tool.SpotShape} counted the
	 * missing z axis in an aspect ratio, {@link org.mastodon.mamut.automahout.tool.ImageBounds} counted
	 * a one-voxel z face as a border. Ask {@code ToolContext.isVolumetric()} at every geometric site.</p>
	 */
	private static final double[][] SPOT_INTERIOR_OFFSETS_PLANAR = buildSpotInteriorOffsets( false );

	private static double[][] buildSpotInteriorOffsets( final boolean volumetric )
	{
		final int axes = volumetric ? 3 : 2;
		final List< double[] > out = new ArrayList<>();
		out.add( new double[] { 0, 0, 0 } );
		for ( final double r : new double[] { 0.5, 0.8 } )
			for ( int axis = 0; axis < axes; axis++ )
				for ( int sign = -1; sign <= 1; sign += 2 )
				{
					final double[] o = new double[ 3 ];
					o[ axis ] = sign * r;
					out.add( o );
				}
		final double d = 0.6 / Math.sqrt( axes );
		for ( int sx = -1; sx <= 1; sx += 2 )
			for ( int sy = -1; sy <= 1; sy += 2 )
				if ( volumetric )
					for ( int sz = -1; sz <= 1; sz += 2 )
						out.add( new double[] { sx * d, sy * d, sz * d } );
				else
					out.add( new double[] { sx * d, sy * d, 0 } );
		return out.toArray( new double[ 0 ][] );
	}

	/** The interior sampling pattern this dataset's geometry supports. */
	static double[][] spotInteriorOffsets( final ToolContext ctx )
	{
		return spotInteriorOffsets( ctx == null || ctx.isVolumetric() );
	}

	/**
	 * As above, said directly. {@code ToolContext.isVolumetric()} answers {@code true} when it cannot
	 * be determined -- which is right for production and useless for a test, since a replay fixture has
	 * no image to determine it from.
	 */
	static double[][] spotInteriorOffsets( final boolean volumetric )
	{
		return volumetric ? SPOT_INTERIOR_OFFSETS : SPOT_INTERIOR_OFFSETS_PLANAR;
	}

	/**
	 * How much the image intensity VARIES INSIDE a detection, as the coefficient of variation over
	 * {@link #SPOT_INTERIOR_OFFSETS}: near zero for a body of even brightness, large for one mixing
	 * bright and dark regions.
	 *
	 * <p>The user's account of this dataset is that a cell is uniformly bright throughout while debris
	 * mixes light and dark, so this is the measurement that states it. It reads the RAW IMAGE and
	 * nothing else, which is what makes it worth more than the alternatives tried so far: it does not
	 * depend on ELEPHANT's ellipsoid, which is fitted to the model's own prediction and may only ever
	 * describe the spherical shapes it was trained on, and it does not depend on the VLM, whose
	 * verdicts on these crops proved to be no better than a coin.</p>
	 *
	 * <p>It is also the one such measure that a small cell passes. Size, volume and the flat-contrast
	 * check all put a daughter cell right where the debris is; a daughter is small AND evenly bright,
	 * so heterogeneity separates them where the others cannot.</p>
	 *
	 * @return the coefficient of variation, or {@code -1} when it cannot be measured (no image source,
	 *         or a mean intensity of zero, where a ratio means nothing)
	 */
	public static double intensityHeterogeneity( final ToolContext ctx, final int timepoint,
			final double[] worldPos, final double radius )
	{
		final double[] p = interiorIntensityProfile( ctx, timepoint, worldPos, radius );
		return p == null ? -1 : p[ 0 ];
	}

	/**
	 * One pass over {@link #SPOT_INTERIOR_OFFSETS} yielding both statistics a debris test needs:
	 * {@code [heterogeneity, meanIntensity, medianIntensity]}, or {@code null} when none can be
	 * measured. Ask for the median when the question is "is this detection ON a cell" and the mean
	 * when it is "how much signal does it hold in total" -- see measureInteriorIntensityProfile.
	 *
	 * <p>Element 3 is the median of the CORE, the samples within half a radius of the centre. It
	 * answers the same question as element 2 for a detection that is WIDER than the object under it,
	 * where the full-extent median reads background however bright the object is.</p>
	 *
	 * <p>Element 4 is the coefficient of variation of that same core. It used to decide whether a
	 * detection was tested for a split; it separated two cells from one at AUC 0.73-0.83, too weak at a
	 * 2% base rate, and the split pass no longer uses it.</p>
	 *
	 * <p>Returned together because they answer different halves of the same description and cost the
	 * same twenty-one image reads. Heterogeneity says whether the brightness inside the detection is
	 * even; the mean, multiplied by the detection's volume, says how much signal is actually there —
	 * the quantity that separates a real cell from a small dim speck in a FLUORESCENCE image, where
	 * total emitted light scales with how much labelled material is present. That second reading is
	 * modality-specific and is only acted on when the user says the images are fluorescence; see
	 * {@code CleanupToggles.dimTotalSignalFilterEnabled}.</p>
	 */
	public static double[] interiorIntensityProfile( final ToolContext ctx, final int timepoint,
			final double[] worldPos, final double radius )
	{
		// Memoised, because the passes that use it ask the same question about the same detections one
		// after another: the debris check and the no-signal check both profile every spot at the
		// timepoint that was just predicted, which is the same twenty-one image reads twice. The answer
		// cannot change — the image is read-only for the length of a run and the key carries the
		// position and radius — so there is nothing to invalidate, only a size to bound.
		final String memoKey = profileMemoKey( timepoint, worldPos, radius );
		final Map< String, double[] > memo = profileMemo( ctx );
		if ( memo != null && memo.containsKey( memoKey ) )
			return memo.get( memoKey );
		final long started = System.nanoTime();
		final double[] profile = measureInteriorIntensityProfile( ctx, timepoint, worldPos, radius );
		PhaseTimings.recordNested( ctx, "spot interior reads", started );
		if ( memo != null )
		{
			if ( memo.size() >= PROFILE_MEMO_MAX_ENTRIES )
				memo.clear(); // a window's worth is what pays; keeping the whole run's is not worth the heap
			memo.put( memoKey, profile );
		}
		return profile;
	}

	/** {@link ToolContext#getSharedData} key for {@link #interiorIntensityProfile}'s memo. */
	private static final String SHARED_PROFILE_MEMO = "autocorrect.interior_profile_memo";

	/** Entries kept before the memo is dropped and rebuilt — a few windows' worth of detections. */
	private static final int PROFILE_MEMO_MAX_ENTRIES = 50_000;

	@SuppressWarnings( "unchecked" )
	private static Map< String, double[] > profileMemo( final ToolContext ctx )
	{
		if ( ctx == null )
			return null;
		Object v = ctx.getSharedData( SHARED_PROFILE_MEMO );
		if ( !( v instanceof Map ) )
		{
			v = new HashMap< String, double[] >();
			ctx.setSharedData( SHARED_PROFILE_MEMO, v );
		}
		return ( Map< String, double[] > ) v;
	}

	/**
	 * Memo key: the timepoint, the position and the radius, each to a hundredth of a physical unit.
	 * Rounding at all is deliberate — a spot re-read after a refit that moved it by a nanometre is the
	 * same measurement — but no coarser than the finest step any caller would mean to distinguish.
	 */
	static String profileMemoKey( final int timepoint, final double[] worldPos, final double radius )
	{
		return timepoint + ":" + Math.round( worldPos[ 0 ] * 100 ) + ":" + Math.round( worldPos[ 1 ] * 100 )
				+ ":" + Math.round( worldPos[ 2 ] * 100 ) + ":" + Math.round( radius * 100 );
	}

	private static double[] measureInteriorIntensityProfile( final ToolContext ctx, final int timepoint,
			final double[] worldPos, final double radius )
	{
		final ViewerPanel viewer = ctx.requireViewer();
		final List< SourceAndConverter< ? > > sources = viewer.state().getSources();
		if ( sources.isEmpty() )
			return null;
		final Sampler sampler = sharedSampler( ctx, timepoint );
		if ( sampler == null )
			return null;
		final double r = Math.max( radius, 1.0 );
		// Both coefficients of variation below are taken about the BACKGROUND, not about zero. A
		// reading here is an absolute 16-bit intensity sitting on this dataset's 33072 offset, so a
		// spread of 500 grey levels over a mean of 33500 reads as a CV of 0.015 however uneven the
		// cell is. The dump of the detector's own output measured every class at 0.00 to 0.01 against
		// a bar of 0.25: the gate could not fire, and the whole split pass was running on a reading
		// that was arithmetically incapable of saying anything.
		//
		// Third time this offset has made a test inert -- the valley test divided a raw ratio on it,
		// and saddleRatio was written to subtract it for the same reason.
		final BackgroundStats bg = estimateBackgroundStats( ctx, timepoint );
		final double base = bg != null && bg.measured() ? bg.intensityMean : 0.0;
		final double[] p = new double[ 3 ];
		double sum = 0, sumSq = 0;
		final double[][] offsets = spotInteriorOffsets( ctx );
		final List< Double > values = new ArrayList<>( offsets.length );
		for ( final double[] o : offsets )
		{
			p[ 0 ] = worldPos[ 0 ] + o[ 0 ] * r;
			p[ 1 ] = worldPos[ 1 ] + o[ 1 ] * r;
			p[ 2 ] = worldPos[ 2 ] + o[ 2 ] * r;
			final double v = sampler.at( p );
			sum += v;
			sumSq += v * v;
			values.add( v );
		}
		final int n = offsets.length;
		final double mean = sum / n;
		if ( !( mean > 0 ) )
			return null;
		// Elements 1, 2 and 3 stay RAW: deleteSpotsWithNoSignal compares them against an absolute
		// ceiling built from this frame's background, so subtracting it there would compare a
		// difference to an absolute. Only the spreads are taken about the background.
		// The MEDIAN as well as the mean, because they answer different questions and one pass has
		// been asking the wrong one. "Is this detection on a cell" is a question about MOST of it; the
		// mean answers "does it contain any signal". A detection half-overlapping its neighbour has
		// several of these reads on the cell and the rest on background, and the bright ones drag the
		// mean up far enough to clear any background-anchored ceiling — which is the circle sitting on
		// empty space with one edge touching a nucleus. The median does not move for them.
		//
		// The background side of the same comparison has been a median and a MAD for exactly this
		// reason since computeBackgroundStats was made robust; the detection side stayed a mean, so the
		// two halves of the test were not the same statistic.
		final List< Double > sorted = new ArrayList<>( values );
		Collections.sort( sorted );
		final double med = sorted.get( sorted.size() / 2 );
		// And the median of the CORE alone -- the samples within half a radius of the centre.
		//
		// The median over the whole extent asks "is most of this detection on a cell", which is the
		// right question for a detection that has drifted off its cell. It is the wrong question for a
		// detection that is simply wider than the object under it: a mitotic figure, a newborn
		// daughter, any small bright body inside a normally-sized ellipsoid puts most of the thirteen
		// samples on background, so the median reads background and the no-signal ceiling takes it.
		// That is a real regression -- the user watched divisions disappear from both sides of the
		// event after the ceiling was raised to where it can actually see background.
		//
		// The core cannot be fooled that way, and it does not weaken the test it guards: a detection
		// sitting on empty space with one edge touching a neighbour has its CENTRE on the empty space,
		// so its core reads background and it is still deleted. The two readings disagree only for a
		// detection centred on something smaller than itself, which is exactly the case to keep.
		final List< Double > core = new ArrayList<>();
		for ( int i = 0; i < offsets.length; i++ )
		{
			final double[] o = offsets[ i ];
			if ( o[ 0 ] * o[ 0 ] + o[ 1 ] * o[ 1 ] + o[ 2 ] * o[ 2 ] <= 0.25 + 1e-9 )
				core.add( values.get( i ) );
		}
		double coreSum = 0, coreSumSq = 0;
		for ( final double v : core )
		{
			coreSum += v;
			coreSumSq += v * v;
		}
		final double coreMean = core.isEmpty() ? mean : coreSum / core.size();
		// Element 4: how UNEVEN the core is, which is a different question from how bright it is.
		// A nucleus is brightest at its middle and smooth there; a detection covering two of them has
		// the valley between them running through its middle, so the core reads bright-dark-bright.
		final double coreCv = relativeSpread( coreSum, coreSumSq, core.size(), base );
		Collections.sort( core );
		final double coreMed = core.isEmpty() ? med : core.get( core.size() / 2 );
		return new double[] { relativeSpread( sum, sumSq, n, base ), mean, med, coreMed, coreCv };
	}

	/** Dataset physical (world-unit) size, mirroring {@code ElephantTool.getPhysicalDimensions}. */
	/**
	 * Draws {@code n} intensity readings from random positions in one frame, for
	 * {@link org.mastodon.mamut.automahout.tool.ImageProfile}.
	 *
	 * <p>Positions outside the image are discarded rather than counted as zero — "there is no voxel
	 * here" is not "this voxel is dark", and counting the first as the second once put half the frame
	 * into a background estimate (see {@code computeBackgroundStats}).</p>
	 *
	 * <p>Reads at {@link #samplingLevel}, like every other measurement in this class: a coarser voxel
	 * is an average of the finer ones, so it estimates the background better, not worse, and a level-0
	 * random access costs 150-400 ms on a chunked store.</p>
	 *
	 * @return the readings actually obtained, which may be fewer than {@code n}, or an empty array
	 */
	public static double[] sampleIntensities( final ToolContext ctx, final int timepoint, final int n )
	{
		final Sampler sampler = sharedSampler( ctx, timepoint );
		final double[] phys = physicalDimensions( ctx );
		if ( sampler == null || phys == null )
			return new double[ 0 ];
		// Fixed seed: the pre-flight dialog opens with switches decided by this, and two runs on the
		// same unchanged data should offer the same switches.
		final java.util.Random rnd = new java.util.Random( 20260925L );
		final double[] out = new double[ n ];
		final double[] pos = new double[ 3 ];
		int got = 0;
		for ( int attempt = 0; attempt < n * 4 && got < n; attempt++ )
		{
			for ( int d = 0; d < 3; d++ )
				pos[ d ] = rnd.nextDouble() * phys[ d ];
			if ( !sampler.covers( pos ) )
				continue;
			out[ got++ ] = sampler.at( pos );
		}
		return got == n ? out : java.util.Arrays.copyOf( out, got );
	}

	private static double[] physicalDimensions( final ToolContext ctx )
	{
		return ImageBounds.physicalExtent( ctx );
	}

	/**
	 * Random background samples used by {@link #estimateContrastNoiseFloor}.
	 *
	 * <p>Was 16, which is too few to describe a spread: on a dense frame a couple of probes landing on
	 * undetected cells was a large fraction of the sample. Then 64, which was too many — a probe costs a
	 * contrast reading of 7 image samples plus an intensity one, and on a 680x678x920 volume backed by a
	 * chunked store those are tens of milliseconds each, so one call cost the better part of a minute. A
	 * median and a MAD need far fewer samples than a mean and a standard deviation to be stable, which
	 * is what makes 32 enough.</p>
	 */
	private static final int CONTRAST_NOISE_FLOOR_SAMPLES = 32;

	/** Background probes below which an estimate says nothing — see {@link BackgroundStats#samples}. */
	private static final int MIN_BACKGROUND_SAMPLES = 4;

	/**
	 * How far a detection must have climbed from the background toward the dimmest cell the user
	 * confirmed before it counts as having any signal at all.
	 *
	 * <p>Expressed as a fraction of that gap rather than as an absolute value, so it works whether the
	 * background reads as zero or not and whether the images are bright or dim: fifteen per cent of the
	 * way from this frame's own background to the dimmest thing the user has called a cell. A detection
	 * below that is not a marginal call about a faint cell — there is nothing there.</p>
	 *
	 * <p>Anchored on the confirmed cells because the background alone cannot say it. On a mostly-black
	 * frame the background median is zero, so "no brighter than the background" only ever caught a
	 * detection reading LITERALLY zero, and everything one grey level above it survived.</p>
	 */
	public static final double NO_SIGNAL_MARGIN_FRACTION = 0.15;

	/**
	 * Standout, in the FRAME's own sigma, at which a detection is deleted for carrying no signal.
	 *
	 * <p>Was 2.5, and with it this pass deleted NOTHING. Two separate errors, and the second hid the
	 * first: the coefficient was chosen on a fixture of truth cells and synthetic background peaks,
	 * and the ceiling was then anchored on {@link #estimateBackgroundStats}, which avoids positions
	 * near existing spots and so reports the frame's EMPTIEST regions -- about 3.8 sigma below the
	 * frame's own median here. A ceiling meant to sit 2.5 sigma above the background sat a sigma
	 * BELOW it, and nothing could ever fall under it. See {@link #frameLevelStats}.</p>
	 *
	 * <p>Re-measured on the detector's OWN output ({@code SplitFixtureDump}, 3245 distinct detections
	 * labelled against CTC silver truth, at the radii the extent fix now produces --
	 * {@code benchmark/detection-gating/measure_no_signal_anchor.py}):</p>
	 *
	 * <pre>
	 *   anchored on the frame's own median      anchored where this used to look
	 *   k     caught    real cells lost         k     caught    real cells lost
	 *   0.5    22.5%          0.00%             1.5     0.0%         0.00%
	 *   1.0    60.6%          0.19%             2.5     0.0%         0.00%   &lt;- what shipped
	 *   1.25   70.7%          0.19%             4.0    12.3%         0.00%
	 *   1.50   76.6%          0.57%             5.0    63.7%         0.30%
	 *   2.00   78.9%          2.22%
	 *   2.50   80.2%          3.98%
	 * </pre>
	 *
	 * <p>1.5 is the knee. Going 1.25 to 1.5 buys 5.9 points of catch for 0.38 of loss, about 15:1 in
	 * favour; going 1.5 to 2.0 buys 2.3 for 1.65, about 1.4:1 against. Almost the whole catch is
	 * available before the loss starts, which is what a threshold on the right anchor looks like.</p>
	 *
	 * <p>The history is worth keeping because both earlier answers are still instructive. An earlier
	 * fixture put 1.5 sigma at "0.00% of cells lost, 89.4% of background caught" and 2.5 at "0.35% /
	 * 99.6%", which is why 2.5 looked free. It was measured on a population the detector does not
	 * produce. The correction before THAT abandoned sigma for a fraction of the climb to the image's
	 * bright tail, 20.3 sigma here: it deleted 41.5% of the real cells and the user watched plainly
	 * visible nuclei disappear. The unit was never the problem.</p>
	 */
	public static final double NO_SIGNAL_SIGMA = 1.5;

	/**
	 * The intensity at or below which a detection at this timepoint has no signal: this frame's own
	 * baseline, plus {@link #NO_SIGNAL_MARGIN_FRACTION} of the climb to {@code dimmestConfirmed}.
	 *
	 * <p>{@code baseline} must be the FRAME's own level -- {@link #frameLevelStats} -- and not
	 * {@link #estimateBackgroundStats}, which reports the emptiest regions and lands about 3.8 sigma
	 * below it. See {@link #NO_SIGNAL_SIGMA} for what that mistake cost here.</p>
	 *
	 * @param dimmestConfirmed a low percentile of the user's confirmed cells' interior mean, or
	 *                         {@code <= 0} when there are too few of them to describe a distribution —
	 *                         in which case this falls back to the baseline alone
	 */
	public static double noSignalCeiling( final double baseline, final double dimmestConfirmed )
	{
		return noSignalCeiling( baseline, 0.0, dimmestConfirmed );
	}

	/**
	 * As above, with the background's own spread, which is what the single-anchor case was missing.
	 *
	 * <p>Without confirmed cells the ceiling is {@code baseline + }{@link #NO_SIGNAL_SIGMA}{@code
	 * sigma}. With them it is a fraction of the climb to the user's own dimmest cell, which is the
	 * better evidence and is left alone.</p>
	 *
	 * @param baselineSpread this frame's own spread; {@code <= 0} falls back to the baseline alone
	 */
	public static double noSignalCeiling( final double baseline, final double baselineSpread,
			final double dimmestConfirmed )
	{
		if ( dimmestConfirmed > baseline )
			return baseline + NO_SIGNAL_MARGIN_FRACTION * ( dimmestConfirmed - baseline );
		if ( baselineSpread > 0.0 )
			return baseline + NO_SIGNAL_SIGMA * baselineSpread;
		return baseline;
	}

	/** Side of the window the contour fit reads, in samples. */
	private static final int CONTOUR_WINDOW_SIDE = 64;

	/** How far the window reaches past the detection, as a multiple of its radius. */
	private static final double CONTOUR_REACH = 1.6;

	/** Directions looked in. Enough that half a contour still over-determines five parameters. */
	private static final int CONTOUR_RAYS = 48;

	/**
	 * Fits an ellipse to the cell's contour in the plane, around an existing detection.
	 *
	 * <p>Measured on Fluo-N2DL-HeLa_01 against CTC silver truth: the circular detections this pipeline
	 * trains on overlap the cell at an IoU of 0.56 -- they contain it whole, recall 1.00, at 1.31x its
	 * radius -- while an ellipse on the cell's own axes reaches 0.92, and reaches 0.92 left at the
	 * DETECTOR's centre against 0.94 perfectly centred. The shape is what matters, not the position,
	 * and no circle follows a body elongated 1.40x at the median. See
	 * {@code benchmark/annotation-extent}.</p>
	 *
	 * <p>In plane only, deliberately. {@code mastodon-ellipsoid-fitting} solves the volumetric nine
	 * degree-of-freedom problem and is a dependency for datasets that have the planes to support it;
	 * measured, it fits a 32-plane blob, refuses a 5-plane one and returns no edgels at all on a single
	 * plane ({@code EllipsoidFittingViabilityTest}). A plane is a five degree-of-freedom problem, which
	 * is both a different one and a far better conditioned one, and it is the one this data poses.</p>
	 *
	 * <p>One window read, not one read per ray: level-0 random access costs 150-400 ms per voxel on a
	 * chunked store. The level is {@link #frameLevelStats} plus {@link #CELL_EXTENT_SIGMA} sigma -- the
	 * same level the extent measure is calibrated at, and the same anchor, because a level calibrated
	 * against the frame cannot be handed the estimate that reports its emptiest regions.</p>
	 *
	 * @return the ellipse in WORLD units, or {@code null} when the contour did not support one
	 */
	/** For a replay: {frame median, sigma, edge level, sampling level, contour points found} at one detection. */
	static double[] contourDiagnostics( final ToolContext ctx, final int timepoint, final double[] worldPos, final double seedRadius )
	{
		final double[] frame = frameLevelStats( ctx, timepoint );
		final Sampler sampler = sharedSampler( ctx, timepoint );
		if ( frame == null || sampler == null )
			return new double[] { frame == null ? Double.NaN : frame[ 0 ], frame == null ? Double.NaN : frame[ 1 ], Double.NaN,
					samplingLevel( ctx ), -1 };
		final double level = frame[ 0 ] + CELL_EXTENT_SIGMA * frame[ 1 ];
		final int side = CONTOUR_WINDOW_SIDE;
		final double step = 2.0 * CONTOUR_REACH * seedRadius / side;
		final double[] window = new double[ side * side ];
		final double[] probe = new double[ 3 ];
		for ( int gy = 0; gy < side; gy++ )
			for ( int gx = 0; gx < side; gx++ )
			{
				probe[ 0 ] = worldPos[ 0 ] + ( gx - side / 2.0 ) * step;
				probe[ 1 ] = worldPos[ 1 ] + ( gy - side / 2.0 ) * step;
				probe[ 2 ] = worldPos[ 2 ];
				window[ gy * side + gx ] = sampler.at( probe );
			}
		final double[][] pixels = EllipseFit.contourPoints( window, side, side, level, side / 2.0, side / 2.0, CONTOUR_RAYS );
		double max = 0;
		for ( final double v : window )
			max = Math.max( max, v );
		return new double[] { frame[ 0 ], frame[ 1 ], level, samplingLevel( ctx ), pixels.length, window[ ( side / 2 ) * side + side / 2 ], max };
	}

	public static EllipseFit.Ellipse fitEllipseToContour( final ToolContext ctx, final int timepoint,
			final double[] worldPos, final double seedRadius )
	{
		return fitEllipseToContour( ctx, timepoint, worldPos, seedRadius, false );
	}

	/**
	 * {@link #fitEllipseToContour(ToolContext, int, double[], double)}, with the edge level either the frame's
	 * ({@code localLevel} false: median plus {@link #CELL_EXTENT_SIGMA} sigma) or the detection's own: halfway
	 * from the frame median to the brightness at the detection's core. The frame's level does not exist on an
	 * image whose background is one value (CE: 57-77% of voxels at exactly 23, so sigma is 0).
	 */
	static EllipseFit.Ellipse fitEllipseToContour( final ToolContext ctx, final int timepoint,
			final double[] worldPos, final double seedRadius, final boolean localLevel )
	{
		if ( ctx == null || worldPos == null || worldPos.length < 3 || !( seedRadius > 0 ) )
			return null;
		final double[] frame = frameLevelStats( ctx, timepoint );
		final Sampler sampler = sharedSampler( ctx, timepoint );
		if ( frame == null || sampler == null || !( localLevel || frame[ 1 ] > 0 ) )
			return null;
		double level = frame[ 0 ] + CELL_EXTENT_SIGMA * frame[ 1 ];
		final int side = CONTOUR_WINDOW_SIDE;
		final double step = 2.0 * CONTOUR_REACH * seedRadius / side;
		final double[] window = new double[ side * side ];
		final double[] probe = new double[ 3 ];
		try
		{
			for ( int gy = 0; gy < side; gy++ )
				for ( int gx = 0; gx < side; gx++ )
				{
					probe[ 0 ] = worldPos[ 0 ] + ( gx - side / 2.0 ) * step;
					probe[ 1 ] = worldPos[ 1 ] + ( gy - side / 2.0 ) * step;
					probe[ 2 ] = worldPos[ 2 ];
					window[ gy * side + gx ] = sampler.at( probe );
				}
		}
		catch ( final RuntimeException e )
		{
			log.debug( "contour window unavailable at t={}", timepoint, e );
			return null;
		}
		if ( localLevel )
		{
			// The core: window samples within half the seed radius of the centre; its upper decile.
			final List< Double > core = new ArrayList<>();
			final double rc = 0.5 * seedRadius / step;
			for ( int gy = 0; gy < side; gy++ )
				for ( int gx = 0; gx < side; gx++ )
					if ( Math.pow( gx - side / 2.0, 2 ) + Math.pow( gy - side / 2.0, 2 ) <= rc * rc )
						core.add( window[ gy * side + gx ] );
			if ( core.isEmpty() )
				return null;
			java.util.Collections.sort( core );
			final double peak = core.get( ( int ) ( 0.9 * ( core.size() - 1 ) ) );
			if ( !( peak > frame[ 0 ] ) )
				return null;
			level = frame[ 0 ] + 0.5 * ( peak - frame[ 0 ] );
		}
		final double[][] pixels = EllipseFit.contourPoints( window, side, side, level,
				side / 2.0, side / 2.0, CONTOUR_RAYS );
		if ( pixels.length < EllipseFit.MIN_POINTS )
			return null;
		// Into world units. The step is the same on both axes, so a direction is unchanged and only
		// the positions move.
		final double[][] world = new double[ pixels.length ][ 4 ];
		for ( int i = 0; i < pixels.length; i++ )
		{
			world[ i ][ 0 ] = worldPos[ 0 ] + ( pixels[ i ][ 0 ] - side / 2.0 ) * step;
			world[ i ][ 1 ] = worldPos[ 1 ] + ( pixels[ i ][ 1 ] - side / 2.0 ) * step;
			world[ i ][ 2 ] = pixels[ i ][ 2 ];
			world[ i ][ 3 ] = pixels[ i ][ 3 ];
		}
		final EllipseFit.Limits lim = new EllipseFit.Limits();
		// Everything in units of the detection, so the fit says the same thing on any magnification.
		lim.maxCentreDistance = 0.6 * seedRadius;
		lim.minRadius = EXTENT_MIN_FACTOR * seedRadius;
		lim.maxRadius = 2.0 * EXTENT_MAX_FACTOR * seedRadius;
		lim.inlierDistance = 1.5 * step;
		lim.minInliers = Math.max( EllipseFit.MIN_POINTS + 1, CONTOUR_RAYS / 3 );
		return EllipseFit.fit( world, worldPos, lim );
	}

	/**
	 * The frame's own median and MAD-scaled spread, from a sample of its pixels.
	 *
	 * <p>NOT {@link #estimateBackgroundStats}, and the difference is the whole reason this exists.
	 * That estimate deliberately avoids positions near existing spots, so it reports the EMPTIEST
	 * regions of the frame rather than the frame: measured on Fluo-N2DL-HeLa_01 it lands about 50 grey
	 * levels, roughly 3.8 sigma, below the frame's own median. That is the right quantity for asking
	 * "does this spot stand above the background", and the wrong one for setting the level at which a
	 * cell's edge is declared, because {@link #CELL_EXTENT_SIGMA} was calibrated against the frame
	 * median and a level 50 levels lower sends every ray further out.</p>
	 *
	 * <p>Measured: the extent benchmark reported a radius 1.02x the truth and production delivered
	 * 1.31x, and this was the entire discrepancy. Anchored here instead, the annotation's overlap with
	 * the cell goes from an IoU of 0.56 to 0.73 (benchmark/cell-extent/RESULTS.md).</p>
	 *
	 * <p>Memoised per timepoint: this draws {@link ImageProfile#SAMPLES} random accesses, and the
	 * extent pass asks once per detection.</p>
	 *
	 * @return {@code { median, MAD-scaled sigma }}, or {@code null} when the frame cannot be sampled
	 */
	public static double[] frameLevelStats( final ToolContext ctx, final int timepoint )
	{
		if ( ctx == null )
			return null;
		final String key = "automahout.frameLevel." + timepoint;
		final Object cached = ctx.getSharedData( key );
		if ( cached instanceof double[] )
			return ( double[] ) cached;
		final Object floor = ctx.getSharedData( SHARED_FLAT_FLOOR_STATS );
		if ( floor instanceof double[] )
			return ( double[] ) floor;
		final double[] samples = sampleIntensities( ctx, timepoint, ImageProfile.SAMPLES );
		if ( samples == null || samples.length < MIN_FOR_SPREAD )
			return null;
		final double[] sorted = samples.clone();
		java.util.Arrays.sort( sorted );
		final double med = sorted[ sorted.length / 2 ];
		final double[] dev = new double[ sorted.length ];
		for ( int i = 0; i < sorted.length; i++ )
			dev[ i ] = Math.abs( sorted[ i ] - med );
		java.util.Arrays.sort( dev );
		final double[] out = new double[] { med, MAD_TO_STD * dev[ dev.length / 2 ] };
		ctx.setSharedData( key, out );
		if ( isFlatFloor( sorted, out ) )
		{
			ctx.setSharedData( SHARED_FLAT_FLOOR_STATS, out );
			int same = 0;
			for ( final double v : sorted )
				if ( v == med )
					same++;
			ctx.log( String.format( "  Frame statistics at t=%d: %.1f%% of %d samples sit exactly on the median %s and none "
					+ "below it — a flat floor (a background of exactly %s), so the frame has no spread to set an edge "
					+ "level with. Every later timepoint is given the same statistics instead of being re-read (over "
					+ "half a minute per frame on a large stack read over a network); the passes that need a spread "
					+ "skip as they would have.", timepoint, 100.0 * same / sorted.length, sorted.length, med, med ) );
		}
		return out;
	}

	/**
	 * {@link ToolContext#getSharedData} key, {@code double[]}: the statistics of a frame found to be a flat floor
	 * ({@link #isFlatFloor}), given to every timepoint measured after it.
	 */
	static final String SHARED_FLAT_FLOOR_STATS = "automahout.frameLevel.flatFloor";

	/**
	 * Whether a frame's sorted samples are a flat floor: no spread, and nothing below the median, as on a sparse
	 * specimen against a background of exactly 0 (a mouse embryo light-sheet stack: 99.9% of samples were 0).
	 * Re-sampling another frame of such a movie returns the same median and the same zero spread, so it is not
	 * re-read. A quantised background with values on both sides of the median is not a floor and is sampled per
	 * frame as before.
	 */
	static boolean isFlatFloor( final double[] sorted, final double[] stats )
	{
		return sorted.length > 0 && !( stats[ 1 ] > 0 ) && sorted[ 0 ] == stats[ 0 ];
	}

	/** Linear-interpolation percentile of {@code values}, {@code q} in [0,1]; sorts a copy. */
	/**
	 * Median of {@code values} plus {@code k} median absolute deviations, or {@code 0} when there is
	 * not enough to describe a spread. Robust by construction: the outliers being looked for cannot
	 * drag the threshold out to meet them, which is the whole reason for the MAD over a sigma.
	 */
	static double medianPlusMads( final List< Double > values, final double k )
	{
		if ( values == null || values.size() < MIN_FOR_SPREAD )
			return 0.0;
		final double m = median( values );
		final List< Double > devs = new ArrayList<>( values.size() );
		for ( final double v : values )
			devs.add( Math.abs( v - m ) );
		return m + k * MAD_TO_STD * median( devs );
	}

	/** Fewest detections a frame needs before its own spread means anything. */
	private static final int MIN_FOR_SPREAD = 10;

	private static void tally( final int[] funnel, final int slot )
	{
		if ( funnel != null && slot < funnel.length )
			funnel[ slot ]++;
	}

	static double percentileOf( final List< Double > values, final double q )
	{
		if ( values == null || values.isEmpty() )
			return Double.NaN;
		final double[] sorted = values.stream().mapToDouble( Double::doubleValue ).sorted().toArray();
		if ( sorted.length == 1 )
			return sorted[ 0 ];
		final double pos = q * ( sorted.length - 1 );
		final int i = ( int ) Math.floor( pos );
		if ( i >= sorted.length - 1 )
			return sorted[ sorted.length - 1 ];
		return sorted[ i ] + ( pos - i ) * ( sorted[ i + 1 ] - sorted[ i ] );
	}

	/**
	 * Spread of a sample set relative to how far it stands ABOVE {@code background}.
	 *
	 * <p>Not relative to its own mean. These are absolute 16-bit intensities on a 33072 offset, so
	 * dividing by the mean answers "how much does this vary against the offset", which is near zero
	 * for everything. Measured on the detector's own output every class read 0.00 to 0.01 against a
	 * bar of 0.25.</p>
	 *
	 * @return {@code 0} when the samples do not stand above the background at all, which is the
	 *         honest answer rather than a large ratio produced by a tiny denominator
	 */
	static double relativeSpread( final double sum, final double sumSq, final int n,
			final double background )
	{
		if ( n <= 0 )
			return 0.0;
		final double mean = sum / n;
		final double above = mean - background;
		if ( !( above > 0 ) )
			return 0.0;
		return Math.sqrt( Math.max( 0.0, sumSq / n - mean * mean ) ) / above;
	}


	/** Scales a median absolute deviation to the standard deviation it implies for normal data. */
	private static final double MAD_TO_STD = 1.4826;

	/** Median of {@code values}, which must be non-empty. Sorts a copy; the caller's list is untouched. */
	public static double median( final List< Double > values )
	{
		final List< Double > sorted = new ArrayList<>( values );
		java.util.Collections.sort( sorted );
		final int n = sorted.size();
		return n % 2 == 1 ? sorted.get( n / 2 )
				: 0.5 * ( sorted.get( n / 2 - 1 ) + sorted.get( n / 2 ) );
	}

	/**
	 * A candidate's own local-contrast MAGNITUDE must clear {@code mean + this many std-devs} of the
	 * background's OWN contrast-magnitude distribution to count as a real peak/trough rather than noise.
	 */
	private static final double CONTRAST_NOISE_FLOOR_STD_MULTIPLIER = 3.0;

	/**
	 * Multiple of the mean existing-spot radius (or a 5.0 world-unit fallback when none exist yet at
	 * this timepoint) a random background probe must stay outside of every current detection — a probe
	 * used to measure "what does empty background look like here" cannot itself land on a real cell.
	 */
	private static final double CONTRAST_NOISE_FLOOR_EXCLUSION_FACTOR = 3.0;

	/**
	 * How many std-devs above the background's OWN mean ABSOLUTE intensity a spot must clear to be
	 * rescued from the flat-contrast check by {@link BackgroundStats#isDefinitelyBackground} — see that
	 * method's doc for why absolute intensity is needed alongside local contrast. Deliberately smaller
	 * than {@link #CONTRAST_NOISE_FLOOR_STD_MULTIPLIER} (biased toward preserving recall): the cost of
	 * wrongly rescuing an actual noise speck here is bounded (other filters — quality threshold, size,
	 * NMS, correction — still apply to it), but the cost of wrongly discarding a real, densely-packed
	 * cell every window compounds across the whole run.
	 */
	private static final double BACKGROUND_INTENSITY_STD_MULTIPLIER = 2.0;

	/**
	 * Bundles the two background statistics {@link #estimateBackgroundStats} computes together from the
	 * SAME sampled background positions, so both a contrast-floor check and an absolute-intensity check
	 * can be made without sampling twice.
	 */
	public static final class BackgroundStats
	{
		/** See {@link #estimateContrastNoiseFloor}'s (now delegating) doc. */
		public final double contrastFloor;

		/** Mean absolute pixel intensity of the sampled background positions. */
		public final double intensityMean;

		/** Std-dev of the sampled background positions' absolute intensity. */
		public final double intensityStd;

		/**
		 * Background probes this estimate was actually built from.
		 *
		 * <p>Here because a baseline of ZERO has two completely different meanings and the callers were
		 * reading the wrong one. On a mostly-empty frame the background genuinely IS zero, which is the
		 * strongest statement the estimate can make; when too few background positions could be found,
		 * the estimate falls back to a zero baseline meaning "no idea". A caller that treats a zero
		 * baseline as "no idea" switches itself off on exactly the frames it was written for — see
		 * {@code ElephantTool.deleteSpotsWithNoSignal}, which is the pass that deletes detections
		 * sitting on nothing and which did nothing at all on a black frame.</p>
		 */
		public final int samples;

		/** Whether enough background positions were found for this to mean anything. */
		public boolean measured()
		{
			return samples >= MIN_BACKGROUND_SAMPLES;
		}

		/** Builds a {@link BackgroundStats} directly, so the classification above can be unit-tested. */
		public static BackgroundStats of( final double contrastFloor, final double intensityMean,
				final double intensityStd )
		{
			return of( contrastFloor, intensityMean, intensityStd, CONTRAST_NOISE_FLOOR_SAMPLES );
		}

		/** As {@link #of(double, double, double)}, stating how many probes produced it. */
		public static BackgroundStats of( final double contrastFloor, final double intensityMean,
				final double intensityStd, final int samples )
		{
			return new BackgroundStats( contrastFloor, intensityMean, intensityStd, samples );
		}

		private BackgroundStats( final double contrastFloor, final double intensityMean,
				final double intensityStd, final int samples )
		{
			this.contrastFloor = contrastFloor;
			this.intensityMean = intensityMean;
			this.intensityStd = intensityStd;
			this.samples = samples;
		}

		/**
		 * True only if {@code spot} reads as background on BOTH signals: its local contrast is within
		 * the noise floor of its immediate surroundings AND its own absolute intensity is
		 * indistinguishable from the background's own baseline.
		 *
		 * <p>Local contrast ALONE over-rejects in a densely packed/confluent culture: a real cell
		 * touching other real cells has near-zero contrast against its immediate shell simply because
		 * the shell also contains real tissue, not because the cell itself is background —
		 * {@code sampleContrast}'s "stands out from its shell" assumption only holds when the shell IS
		 * actual background. Absolute intensity breaks that ambiguity: a spot sitting on tissue that is
		 * clearly not background-valued is real regardless of how little it stands out from its
		 * neighbours, so it is rescued here even when the contrast check alone would have flagged it.</p>
		 *
		 * <p>Both signals are deliberately POLARITY-AGNOSTIC, so this works on transmitted-light data
		 * (brightfield, phase contrast) and not only on fluorescence. {@code sampleContrast} returns a
		 * signed {@code spot - shell} and every caller takes its magnitude, so a cell darker than its
		 * surroundings stands out exactly as much as a brighter one. The intensity rescue used to be
		 * one-sided ({@code intensity <= mean + k·std}), which assumed cells are BRIGHTER than
		 * background: on brightfield or phase, where a cell is typically darker, a confluent cell would
		 * fail the contrast check and then fail the rescue too, and be deleted as background. It is now
		 * a two-sided distance from the baseline — "clearly different from background", either
		 * direction.</p>
		 */
		public boolean isDefinitelyBackground( final double contrastMagnitude, final double intensity )
		{
			if ( contrastMagnitude > contrastFloor )
				return false; // stands out locally — real signal, brighter or darker
			return Math.abs( intensity - intensityMean )
					<= BACKGROUND_INTENSITY_STD_MULTIPLIER * intensityStd;
		}
	}

	/**
	 * Estimates, from real background pixels at {@code timepoint}, BOTH the local-contrast MAGNITUDE
	 * that reads as "just sensor noise, not a real peak/trough" AND the background's own absolute
	 * intensity baseline — real background (including a visibly grainy brightfield "background") is
	 * essentially NEVER exactly 0, so any fixed absolute/zero cutoff lets obviously-empty detections
	 * survive whenever there is any read/shot noise at all, however small; see
	 * {@link BackgroundStats#isDefinitelyBackground} for why the intensity baseline is needed too.
	 *
	 * <p>Samples random positions at least {@code meanSpotRadius * CONTRAST_NOISE_FLOOR_EXCLUSION_FACTOR}
	 * from every spot currently at {@code timepoint}. Falls back to a near-zero contrast floor and a
	 * zero/zero intensity baseline (so the intensity rescue never fires) if too few background positions
	 * could be found. Used both for per-window flat-spot cleanup
	 * ({@code ElephantTool.deleteFlatContrastSpots}) and to sanity-check a single locally re-detected
	 * candidate before it is accepted into the graph (see {@link #recoverMissingPredecessors},
	 * {@link #fillGaps}) — centralised here so all call sites share one tested implementation instead of
	 * separate definitions that can silently drift apart.</p>
	 */
	public static BackgroundStats estimateBackgroundStats( final ToolContext ctx, final int timepoint )
	{
		// Cached per timepoint, because this is by far the most expensive thing the cleanup passes do and
		// three of them ask for it on every timepoint. It draws 64 background probes, each costing a
		// contrast reading (7 image samples) plus an intensity one, so a single call is over 500 random
		// accesses — and on a large volume backed by a chunked store those cost tens of milliseconds
		// each. Measured on a 680x678x920 dataset: a crop-restricted timepoint that used to take 25
		// seconds took three minutes.
		//
		// Keyed by the spot count as well as the timepoint. The probes avoid positions near EXISTING
		// spots, so the estimate genuinely improves once a timepoint has detections to exclude, and
		// caching the version computed on an empty timepoint would lock in the worst one. A pass that
		// deletes changes the count and gets a fresh estimate; repeated calls within one pass share it.
		final String region = backgroundRegionTag( ctx );
		final BackgroundStats cached = cachedBackgroundStats( ctx, region, timepoint );
		if ( cached != null )
			return cached;
		// Reported under "of which" in the per-window timing line, because this is called from inside
		// three cleanup passes and would otherwise be invisible in a log that shows only what each pass
		// deleted. It is the single most expensive thing in the per-timepoint path; see PhaseTimings.
		final long started = System.nanoTime();
		final BackgroundStats fresh = computeBackgroundStats( ctx, timepoint );
		PhaseTimings.recordNested( ctx, "background estimate", started );
		cacheBackgroundStats( ctx, region, timepoint, fresh );
		return fresh;
	}

	/** {@link ToolContext#getSharedData} key for {@link #estimateBackgroundStats}'s per-timepoint cache. */
	private static final String SHARED_BACKGROUND_STATS_CACHE = "autocorrect.background_stats_cache";

	/** Change in a timepoint's spot count, as a fraction, that invalidates its cached estimate. */
	private static final double BACKGROUND_CACHE_COUNT_TOLERANCE = 0.05;

	/**
	 * Timepoints either side of a measured estimate that may reuse it instead of measuring again.
	 *
	 * <p>The background of a frame is the microscope's noise floor and its out-of-cell illumination —
	 * neither changes materially from one timepoint to the next, while the measurement costs 32 probes
	 * spread across the volume, each pulling its own chunk off a chunked store. Paying that per
	 * timepoint was the dominant cost of the whole pipeline: measured on the dataset that prompted this,
	 * ~90 seconds of the ~2 minutes a crop-restricted timepoint took, on thirteen to sixteen
	 * detections. Set to a tuning window's length so a window measures its background about once.</p>
	 *
	 * <p>What this deliberately gives up: a timepoint whose illumination genuinely jumped (a laser
	 * change, a refocus) is judged against its neighbour's background for up to this many timepoints.
	 * The flat-contrast and no-signal checks are the ones affected, and both compare a spot against the
	 * floor with a wide margin, so a small drift does not change their verdicts.</p>
	 */
	private static final int BACKGROUND_STATS_REUSE_SPAN = 10;

	/**
	 * How informed a neighbouring timepoint's estimate must have been, as a fraction of this
	 * timepoint's spot count, before it may be reused: the probes avoid positions near EXISTING spots,
	 * so an estimate measured on a near-empty timepoint had far less to exclude and is the one worth
	 * paying to replace.
	 */
	private static final double BACKGROUND_REUSE_MIN_COUNT_FRACTION = 0.5;

	@SuppressWarnings( "unchecked" )
	private static Map< String, Object[] > backgroundStatsCache( final ToolContext ctx )
	{
		Object v = ctx.getSharedData( SHARED_BACKGROUND_STATS_CACHE );
		if ( !( v instanceof Map ) )
		{
			v = new HashMap< String, Object[] >();
			ctx.setSharedData( SHARED_BACKGROUND_STATS_CACHE, v );
		}
		return ( Map< String, Object[] > ) v;
	}

	/**
	 * Which region an estimate describes — a crop-restricted pass probes only inside its crop (see
	 * {@link #computeBackgroundStats}), so its estimate must not be handed to the full-frame commit
	 * that follows it. Coarse on purpose: every crop of one frame shares an entry, which is what the
	 * density-probe grid needs (four crops at one timepoint, measured once) and is no worse than the
	 * single frame-wide estimate all of them shared before.
	 */
	static String backgroundRegionTag( final ToolContext ctx )
	{
		return ExpectedCellCount.cropWorldBounds( ctx ) != null ? "crop" : "full";
	}

	/** Cache key: the estimate is only valid for one region of one timepoint. */
	static String backgroundCacheKey( final String region, final int timepoint )
	{
		return region + "@" + timepoint;
	}

	/**
	 * Decides whether a cached estimate may stand in for {@code timepoint}'s — first its own, then a
	 * neighbour's. Split out from the arithmetic so the reuse rules can be tested directly.
	 *
	 * @return {@code true} when an estimate measured with {@code cachedCount} spots excluded, {@code
	 *         timepointDistance} timepoints away, is good enough for a timepoint now holding
	 *         {@code nowCount} spots
	 */
	static boolean backgroundEstimateReusable( final int cachedCount, final int nowCount,
			final int timepointDistance )
	{
		if ( timepointDistance > BACKGROUND_STATS_REUSE_SPAN )
			return false;
		if ( timepointDistance == 0 )
			// Only a HIGHER count invalidates. The probes avoid positions near existing spots, so more
			// spots to exclude makes the estimate better, while fewer only means the exclusion was more
			// generous than it needed to be. Invalidating on any change meant the density-probe phase —
			// which detects into a timepoint and clears it again, repeatedly — recomputed on every call,
			// and that is the phase that was slow to begin with.
			return nowCount <= cachedCount * ( 1.0 + BACKGROUND_CACHE_COUNT_TOLERANCE );
		return cachedCount >= nowCount * BACKGROUND_REUSE_MIN_COUNT_FRACTION;
	}

	private static BackgroundStats cachedBackgroundStats( final ToolContext ctx, final String region,
			final int timepoint )
	{
		final Map< String, Object[] > cache = backgroundStatsCache( ctx );
		final int nowCount = countCellsAt( ctx, timepoint );
		// This timepoint's own estimate first, then the nearest neighbour's — see
		// BACKGROUND_STATS_REUSE_SPAN for why a neighbour's will do. A reused estimate is NOT re-cached
		// under this timepoint's key: keeping only measurements in the cache means the reuse window
		// never walks forward off a single measurement made ten timepoints ago.
		for ( int d = 0; d <= BACKGROUND_STATS_REUSE_SPAN; d++ )
		{
			for ( final int near : d == 0 ? new int[] { timepoint }
					: new int[] { timepoint - d, timepoint + d } )
			{
				final Object[] entry = cache.get( backgroundCacheKey( region, near ) );
				if ( entry != null && backgroundEstimateReusable( ( Integer ) entry[ 0 ], nowCount, d ) )
					return ( BackgroundStats ) entry[ 1 ];
			}
		}
		return null;
	}

	private static void cacheBackgroundStats( final ToolContext ctx, final String region,
			final int timepoint, final BackgroundStats stats )
	{
		backgroundStatsCache( ctx ).put( backgroundCacheKey( region, timepoint ),
				new Object[] { countCellsAt( ctx, timepoint ), stats } );
	}

	/** Spots currently at {@code timepoint}. */
	/**
	 * Cells at {@code timepoint}; {@code fp} training negatives are not cells.
	 *
	 * <p>Delegates rather than repeating the loop: this was a second, character-identical copy of
	 * {@link ExpectedCellCount#countCellsAt}, and two copies of a rule drift the moment one is
	 * corrected — which is exactly what was about to happen here.</p>
	 */
	public static int countCellsAt( final ToolContext ctx, final int timepoint )
	{
		return ExpectedCellCount.countCellsAt( ctx, timepoint );
	}

	private static BackgroundStats computeBackgroundStats( final ToolContext ctx, final int timepoint )
	{
		final ModelGraph graph = ctx.getGraph();
		final List< double[] > existing = new ArrayList<>();
		double radiusSum = 0.0;
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				spot.localize( pos );
				existing.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ] } );
				radiusSum += Math.sqrt( spot.getBoundingSphereRadiusSquared() );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		final double meanRadius = existing.isEmpty() ? 5.0 : radiusSum / existing.size();
		final double exclusionRadius = meanRadius * CONTRAST_NOISE_FLOOR_EXCLUSION_FACTOR;
		final double sampleRadius = Math.max( 1.0, meanRadius );
		// Inside the crop when a crop-restricted pass is running, the whole frame otherwise. A crop's
		// detections are judged against this floor, so the floor has to describe the crop's own
		// background: an estimate drawn from the whole frame answers a question nobody asked of a pass
		// that only ever looks at one region — and probing 32 positions scattered across a 589 x 434 x
		// 435 µm volume pulls 32 unrelated chunks off the store, where the same probes inside one crop
		// land in the neighbourhood the predict just read.
		final double[] cropBounds = ExpectedCellCount.cropWorldBounds( ctx );
		final double[] lo = new double[ 3 ];
		final double[] span = new double[ 3 ];
		{
			final double[] physDims = physicalDimensions( ctx );
			for ( int d = 0; d < 3; d++ )
			{
				lo[ d ] = cropBounds == null ? 0.0 : cropBounds[ d ];
				final double hi = cropBounds == null ? physDims[ d ] : cropBounds[ d + 3 ];
				span[ d ] = Math.max( 0.0, hi - lo[ d ] );
			}
		}

		final Sampler bgSampler = sharedSampler( ctx, timepoint );

		final Random rnd = new Random( timepoint ); // deterministic — same timepoint samples the same background
		final List< Double > absContrasts = new ArrayList<>();
		final List< Double > intensities = new ArrayList<>();
		final int maxAttempts = CONTRAST_NOISE_FLOOR_SAMPLES * 20;
		for ( int attempt = 0; absContrasts.size() < CONTRAST_NOISE_FLOOR_SAMPLES && attempt < maxAttempts; attempt++ )
		{
			final double[] cand = {
					lo[ 0 ] + rnd.nextDouble() * span[ 0 ],
					lo[ 1 ] + rnd.nextDouble() * span[ 1 ],
					span[ 2 ] > 1e-9 ? lo[ 2 ] + rnd.nextDouble() * span[ 2 ] : 0.0
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
			// A position the image does not cover is not a dark background reading, and Sampler.at
			// cannot say which it is — it returns 0 for both. Averaging the two together is how this
			// estimate came to swing between 0 and the true background on consecutive windows of the
			// same movie: the probe box spans the volume's full z extent, a single-plane dataset is one
			// voxel thick, and rounding put about half the probes one voxel outside it. The baseline is
			// a median, so whichever side of half the run landed on decided whether the frame's
            // background read as ~130 or as 0 — and a 0 baseline makes the no-signal ceiling delete
			// nothing while a 130 one deletes the entire frame. Skip the probe instead; the attempt
			// budget below absorbs the misses.
			if ( bgSampler != null && !bgSampler.covers( cand ) )
				continue;
			absContrasts.add( Math.abs( sampleContrast( bgSampler, cand, sampleRadius ) ) );
			intensities.add( bgSampler == null ? 0.0 : bgSampler.at( cand ) );
		}
		if ( absContrasts.size() < MIN_BACKGROUND_SAMPLES )
			return new BackgroundStats( 1e-6, 0.0, 0.0, absContrasts.size() ); // too little confirmed-empty background to
			// estimate a floor — near-zero contrast fallback, and an intensity baseline of 0/0 means
			// isDefinitelyBackground's rescue never fires (any positive intensity "clears" it), erring
			// toward NOT deleting when there isn't enough background to judge against.

		// Robust, for the same reason the intensity baseline below is, and it has to be: leaving this as
		// a mean and a standard deviation while the sample count went from 16 to 64 made it WORSE, not
		// better. More probes means more of them land on undetected cells, and every one of those raises
		// a mean-and-standard-deviation floor. Measured: the floor rose until the check flagged 0-4% of
		// a timepoint where it had flagged 47-76%, the spots it had been holding back came through, and
		// the window committed 623 spots/tp against a stated 250.
		final double contrastMedian = median( absContrasts );
		final List< Double > contrastDeviations = new ArrayList<>( absContrasts.size() );
		for ( final double c : absContrasts )
			contrastDeviations.add( Math.abs( c - contrastMedian ) );
		final double contrastSpread = MAD_TO_STD * median( contrastDeviations );
		final double contrastFloor = Math.max( 1e-6,
				contrastMedian + CONTRAST_NOISE_FLOOR_STD_MULTIPLIER * contrastSpread );

		// Median and MAD, not mean and standard deviation. These probes are only guaranteed to avoid
		// spots that were DETECTED, so on a dense frame some of them land on real cells nobody detected.
		// With a mean and a standard deviation, one such probe in a few dozen moves the baseline UP
		// toward the cells and widens the band around it at the same time, and isDefinitelyBackground's
		// second test — "is this spot's brightness within the background's own spread" — is what admits
		// a bright cell as background once the band is wide enough. The whole point of that test is to
		// rescue bright cells from the contrast test in a dense culture, so it must not be the thing
		// that widens when the culture gets dense. A median and a MAD barely move for the same probes.
		final double intensityMedian = median( intensities );
		final List< Double > deviations = new ArrayList<>( intensities.size() );
		for ( final double v : intensities )
			deviations.add( Math.abs( v - intensityMedian ) );
		final double intensitySpread = MAD_TO_STD * median( deviations );

		return new BackgroundStats( contrastFloor, intensityMedian, intensitySpread, absContrasts.size() );
	}

	/**
	 * As {@link #estimateBackgroundStats}, but only the contrast floor — kept for the two call sites
	 * ({@link #recoverMissingPredecessors}, {@link #fillGaps}) that sanity-check a single freshly
	 * re-detected candidate rather than sweeping a whole timepoint's existing population, where the
	 * densely-packed-culture false-rejection risk {@link BackgroundStats#isDefinitelyBackground} exists
	 * to fix is less applicable (a single ad-hoc region re-detection, not a bulk sweep).
	 */
	public static double estimateContrastNoiseFloor( final ToolContext ctx, final int timepoint )
	{
		return estimateBackgroundStats( ctx, timepoint ).contrastFloor;
	}

	/**
	 * Learns a {@link DetectionAcceptanceProfile} (intensity + local contrast) from the {@code tp}/{@code fp}
	 * tags in {@code [startTp, endTp]}: the human's labels become the supervision for an autonomous
	 * accept/reject rule.
	 */
	public static DetectionAcceptanceProfile learnAcceptanceProfile( final ToolContext ctx,
			final int startTp, final int endTp )
	{
		final Model model = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();
		final ViewerPanel viewer = ctx.requireViewer();
		final List< SourceAndConverter< ? > > sources = viewer.state().getSources();
		final DetectionAcceptanceProfile p = new DetectionAcceptanceProfile();
		if ( sources.isEmpty() )
			return p;
		final SourceAndConverter< ? > sac = sources.get( 0 );
		final List< Double > tpI = new ArrayList<>(), tpC = new ArrayList<>();
		final List< Double > fpI = new ArrayList<>(), fpC = new ArrayList<>();
		// Positions and labels under the read lock; the image after it (see unprotectedSpotsIn).
		final List< double[] > rows = new ArrayList<>(); // x, y, z, radius, t, 1 = tp / 0 = fp
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < startTp || t > endTp )
					continue;
				final String label = DetectionTagLabels.labelOf( model, s );
				final boolean tp = DetectionTagLabels.TP.equals( label );
				if ( !tp && !DetectionTagLabels.FP.equals( label ) )
					continue;
				final double[] row = new double[ 6 ];
				s.localize( row );
				row[ 3 ] = Math.sqrt( s.getBoundingSphereRadiusSquared() );
				row[ 4 ] = t;
				row[ 5 ] = tp ? 1 : 0;
				rows.add( row );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		for ( final double[] row : rows )
		{
			final double[] pos = { row[ 0 ], row[ 1 ], row[ 2 ] };
			final double intensity = sampleSpotIntensity( sac, ( int ) row[ 4 ], pos );
			final double contrast = sampleContrast( sac, ( int ) row[ 4 ], pos, row[ 3 ] );
			( row[ 5 ] > 0 ? tpI : fpI ).add( intensity );
			( row[ 5 ] > 0 ? tpC : fpC ).add( contrast );
		}
		p.nTp = tpI.size();
		p.nFp = fpI.size();
		p.tpIntensityMean = mean( tpI );
		p.tpIntensityStd = std( tpI, p.tpIntensityMean );
		p.fpIntensityMean = mean( fpI );
		p.fpIntensityStd = std( fpI, p.fpIntensityMean );
		p.tpContrastMean = mean( tpC );
		p.tpContrastStd = std( tpC, p.tpContrastMean );
		p.fpContrastMean = mean( fpC );
		p.fpContrastStd = std( fpC, p.fpContrastMean );
		// Robust threshold anchors: a LOW percentile of tp (not the mean) and a HIGH percentile of fp —
		// see DetectionAcceptanceProfile#accept for why nearest-centroid-on-the-mean is fooled by TP-side
		// spread (a wide-brightness-range tp population pulls the mean up, making a legitimately dim real
		// cell read as statistically closer to background). Percentiles (not strict min/max) so a single
		// outlier example doesn't set the whole threshold.
		p.tpContrastP10 = percentile( tpC, 0.10 );
		p.fpContrastP90 = percentile( fpC, 0.90 );
		p.computeSeparation();
		return p;
	}

	/**
	 * Automatically (re)learns a detection-acceptance profile from data intrinsic to the CURRENT dataset —
	 * no manual "Filter by Example" labelling needed, unlike {@link #learnAcceptanceProfile}. The caller
	 * (autonomous tracking) is expected to persist the result under a path scoped to this dataset, so a
	 * profile learned here never leaks onto a different dataset opened later (see the discussion at
	 * {@link #applyAcceptanceProfile}).
	 *
	 * <p>TP examples come from {@code reliableTpByTp} (records in the {@code {x,y,z,cov0..8}} layout, e.g.
	 * from a long, unbranched-track snapshot) — trustworthy without a human labelling anything, the same
	 * signal already used as the GT fallback elsewhere in the pipeline. FP examples are LOCAL CONTRAST
	 * sampled at random positions at least {@code exclusionRadius} away from every spot currently detected
	 * at that timepoint: a position that far from anything detected cannot be a real cell if detection
	 * recall is even roughly reasonable, which is the automatic equivalent of a human clicking an obviously
	 * empty patch as an NG example.</p>
	 *
	 * @return a profile; {@link DetectionAcceptanceProfile#usable()} is false if too few of either class
	 *         were found — the caller should then skip using/saving it for now and try again once more
	 *         reliable tracks have accumulated
	 */
	public static DetectionAcceptanceProfile learnAcceptanceProfileAuto( final ToolContext ctx,
			final Map< Integer, List< double[] > > reliableTpByTp, final int[] timepoints,
			final double[] physicalSizeWorld, final int samplesPerTp, final double exclusionRadius )
	{
		final ModelGraph graph = ctx.getGraph();
		final List< Double > tpI = new ArrayList<>(), tpC = new ArrayList<>();
		final List< Double > fpI = new ArrayList<>(), fpC = new ArrayList<>();

		// TP: long, unbranched-track positions — already trustworthy, no labelling needed.
		for ( final Map.Entry< Integer, List< double[] > > e : reliableTpByTp.entrySet() )
		{
			final int t = e.getKey();
			for ( final double[] rec : e.getValue() )
			{
				final double[] pos = { rec[ 0 ], rec[ 1 ], rec[ 2 ] };
				final double r = Math.sqrt( Math.max( 0.0, ( rec[ 3 ] + rec[ 7 ] + rec[ 11 ] ) / 3.0 ) );
				tpI.add( sampleIntensity( ctx, t, pos ) );
				tpC.add( sampleContrast( ctx, t, pos, r ) );
			}
		}

		// FP: contrast at random EMPTY positions, far from every current detection at that timepoint.
		final Random rnd = new Random( 0 ); // deterministic — same window always samples the same background
		final double sampleRadius = Math.max( 1.0, exclusionRadius * 0.25 );
		for ( final int t : timepoints )
		{
			final List< double[] > existing = new ArrayList<>();
			graph.getLock().readLock().lock();
			try
			{
				final double[] pos = new double[ 3 ];
				for ( final Spot s : graph.vertices() )
				{
					if ( s.getTimepoint() != t )
						continue;
					s.localize( pos );
					existing.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ] } );
				}
			}
			finally
			{
				graph.getLock().readLock().unlock();
			}
			int found = 0;
			final int maxAttempts = Math.max( 50, samplesPerTp * 20 );
			for ( int attempt = 0; found < samplesPerTp && attempt < maxAttempts; attempt++ )
			{
				final double[] cand = {
						rnd.nextDouble() * physicalSizeWorld[ 0 ],
						rnd.nextDouble() * physicalSizeWorld[ 1 ],
						physicalSizeWorld[ 2 ] > 1e-9 ? rnd.nextDouble() * physicalSizeWorld[ 2 ] : 0.0
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
				found++;
				fpI.add( sampleIntensity( ctx, t, cand ) );
				fpC.add( sampleContrast( ctx, t, cand, sampleRadius ) );
			}
		}

		final DetectionAcceptanceProfile p = new DetectionAcceptanceProfile();
		p.nTp = tpI.size();
		p.nFp = fpI.size();
		p.tpIntensityMean = mean( tpI );
		p.tpIntensityStd = std( tpI, p.tpIntensityMean );
		p.fpIntensityMean = mean( fpI );
		p.fpIntensityStd = std( fpI, p.fpIntensityMean );
		p.tpContrastMean = mean( tpC );
		p.tpContrastStd = std( tpC, p.tpContrastMean );
		p.fpContrastMean = mean( fpC );
		p.fpContrastStd = std( fpC, p.fpContrastMean );
		p.tpContrastP10 = percentile( tpC, 0.10 );
		p.fpContrastP90 = percentile( fpC, 0.90 );
		p.computeSeparation();
		return p;
	}

	/** Linear-interpolated percentile ({@code q} in [0,1]) of {@code values}, or NaN if empty. */
	private static double percentile( final List< Double > values, final double q )
	{
		if ( values.isEmpty() )
			return Double.NaN;
		final double[] sorted = values.stream().mapToDouble( Double::doubleValue ).sorted().toArray();
		final double idx = q * ( sorted.length - 1 );
		final int lo = ( int ) Math.floor( idx );
		final int hi = ( int ) Math.ceil( idx );
		if ( lo == hi )
			return sorted[ lo ];
		return sorted[ lo ] + ( sorted[ hi ] - sorted[ lo ] ) * ( idx - lo );
	}

	/**
	 * Autonomously tags every spot in {@code [startTp, endTp]} as {@code tp} (accept) or {@code fp}
	 * (reject) using the learned {@code profile} — no human OK/NG picking needed.
	 *
	 * @return {@code [nAccepted, nRejected]}
	 */
	public static int[] applyAcceptanceProfile( final ToolContext ctx, final int startTp, final int endTp,
			final DetectionAcceptanceProfile profile )
	{
		final Model model = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();
		final ViewerPanel viewer = ctx.requireViewer();
		final List< SourceAndConverter< ? > > sources = viewer.state().getSources();
		if ( sources.isEmpty() || !profile.usable() )
			return new int[] { 0, 0 };
		final SourceAndConverter< ? > sac = sources.get( 0 );
		final TagSet tagSet = DetectionTagLabels.ensure( model );
		final Tag tpTag = DetectionTagLabels.tpTag( tagSet );
		final Tag fpTag = DetectionTagLabels.fpTag( tagSet );
		final ObjTags< Spot > vertexTags = model.getTagSetModel().getVertexTags();
		final List< double[] > rows = new ArrayList<>();
		final List< Spot > refs = unprotectedSpotsIn( ctx, startTp, endTp, rows );
		final Tag[] tags = new Tag[ refs.size() ];
		for ( int i = 0; i < tags.length; i++ )
		{
			final double[] r = rows.get( i );
			final double[] pos = { r[ 0 ], r[ 1 ], r[ 2 ] };
			final double intensity = sampleSpotIntensity( sac, ( int ) r[ 4 ], pos );
			final double contrast = sampleContrast( sac, ( int ) r[ 4 ], pos, r[ 3 ] );
			tags[ i ] = profile.accept( intensity, contrast ) ? tpTag : fpTag;
		}
		return tagUnchanged( ctx, refs, tags, tpTag );
	}

	/** True if {@code (t,pos)} matches one of {@code confirmedFp}'s {@code {x,y,z,t}} records within {@code radius}. */
	/**
	 * Hard limit on media items (images/documents) in a single VLM {@code analyze} call — the backing
	 * Bedrock API rejects the WHOLE request outright above this ("Too much media: 0 document pages + N
	 * images > 100"), so exceeding it is not a degraded response, it is a guaranteed hard failure of
	 * every candidate in the batch, not just the excess ones. Kept slightly under the observed 100 as a
	 * margin.
	 */
	private static final int VLM_MAX_IMAGES_PER_CALL = 99;

	/**
	 * Least of a crop's requested in-plane extent that must lie inside the image volume for the crop to
	 * be worth showing the VLM. Below this the picture is mostly out-of-bounds region with the crosshair
	 * pressed against the edge, which is unanswerable however it is rendered.
	 */
	private static final double VLM_CROP_MIN_INSIDE_FRACTION = 0.5;

	/**
	 * Upper end of the shared display range, in standard deviations above the background mean (the lower
	 * end is {@link #VLM_DISPLAY_LOW_STD} below it).
	 *
	 * <p>Chosen so background occupies the bottom fifth of the grey scale and anything meaningfully
	 * brighter is visibly separated from it. Cells brighter than this saturate, which is the intended
	 * trade: these images exist to answer "is the crosshair on background", not to measure intensity.</p>
	 */
	private static final double VLM_DISPLAY_HIGH_STD = 10.0;

	/** Lower end of the shared display range, in standard deviations below the background mean. */
	private static final double VLM_DISPLAY_LOW_STD = 2.0;

	/**
	 * Shared display range {@code {min, max}} for every crop rendered at {@code timepoint}, derived from
	 * that timepoint's own background statistics, or {@code null} when they could not be estimated (the
	 * renderer then falls back to per-crop scaling).
	 */
	private static double[] vlmDisplayRange( final ToolContext ctx, final int timepoint )
	{
		return vlmDisplayRange( estimateBackgroundStats( ctx, timepoint ) );
	}

	/** The range arithmetic on its own, so it can be exercised without an image source. */
	static double[] vlmDisplayRange( final BackgroundStats stats )
	{
		if ( stats == null || !( stats.intensityStd > 0 ) )
			return null; // nothing measured to anchor a shared range — fall back to per-crop scaling
		return new double[] {
				stats.intensityMean - VLM_DISPLAY_LOW_STD * stats.intensityStd,
				stats.intensityMean + VLM_DISPLAY_HIGH_STD * stats.intensityStd };
	}

	/**
	 * Images rendered per candidate by {@link #captureSpotCropPngMultiPlane}: xy/xz/yz on a volume, xy
	 * alone on a single-plane dataset, where the other two are one voxel tall and carry no information.
	 */
	private static int vlmImagesPerCandidate( final ToolContext ctx )
	{
		return ctx != null && ctx.isVolumetric() ? 3 : 1;
	}

	/**
	 * Max candidates a single multi-plane VLM batch call can include without risking
	 * {@link #VLM_MAX_IMAGES_PER_CALL} — every batched multi-plane VLM call (fp-candidate confirmation,
	 * synthetic-negative confirmation) must truncate to this regardless of its own per-feature budget,
	 * since those budgets are tuned for training-signal quality, not this transport-level constraint.
	 */
	private static int vlmMaxCandidatesPerCall( final ToolContext ctx )
	{
		return Math.max( 1, VLM_MAX_IMAGES_PER_CALL / vlmImagesPerCandidate( ctx ) );
	}

	/**
	 * Crop half-width as a multiple of the candidate's radius. At 4.0 the candidate spanned a quarter
	 * of the frame's width and about a sixteenth of its area, which left the judge reading a wide field
	 * of neighbours to answer a question about the middle of it — and on the crops from one audit it
	 * called two candidates real whose crosshair sat on plainly empty background. Tighter puts the
	 * candidate across half the frame while still showing the ring around it, which is what "is this a
	 * distinct body or part of its neighbour" needs.
	 */
	private static final double VLM_CROP_RADIUS_FACTOR = 2.5;

	private static final int VLM_CROP_MIN_HALF_PX = 12;

	private static final int VLM_CROP_MAX_HALF_PX = 60;

	/** Output image side length (px); crops are upscaled to this for VLM legibility. */
	private static final int VLM_CROP_OUTPUT_DIM = 200;

	public static final Pattern VLM_VERDICT_LINE =
			Pattern.compile( "VERDICT_(\\d+)\\s*:\\s*(fp|real)", Pattern.CASE_INSENSITIVE );

	/**
	 * Graph-driven over-segmentation demotion, applied to whatever is currently tracked — e.g. the
	 * refinement round in {@code ElephantTool}, which enriches (gap-fill / predecessor recovery) and
	 * retrains on "everything currently tracked" as {@code tp}. It needs no prior per-detection
	 * classification pass, which is what makes it usable from callers that only have the graph.
	 *
	 * <p>Every group of (not-already-{@code fp}) spots in {@code [startTp, endTp]} that geometrically
	 * overlaps with no dark intensity valley between them — i.e. fragments of one over-segmented blob,
	 * see {@link #isSameBlob} — is replaced by one spot covering the group, via
	 * {@link #mergeIntoCoveringEllipsoid}. Needs no VLM: purely the geometric+intensity rule already
	 * trusted elsewhere in this class.</p>
	 *
	 * @return net spots removed (each group of k becomes 1)
	 */
	public static int resolveOverlappingDuplicates( final ToolContext ctx, final int startTp, final int endTp )
	{
		// Switched off for a diagnostic run — see CleanupToggles for what that costs (the duplicates that
		// survive here are taught to the model as separate cells by the next refinement round).
		if ( !CleanupToggles.duplicateResolutionEnabled( ctx ) )
			return 0;
		final Model model = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();

		// Phase 1 (read lock): every not-already-fp, unprotected spot as {x,y,z,radius,poolIndex}.
		// Detection score used to be gathered here to pick which of a pair to throw away. Nothing picks
		// a loser any more, so the per-spot contrast sampling that cost is gone with it.
		final Map< Integer, List< double[] > > byTp = new HashMap<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < startTp || t > endTp )
					continue;
				if ( "fp".equals( DetectionTagLabels.labelOf( model, s ) ) )
					continue;
				if ( ProtectedSpots.isProtected( ctx, model, s ) )
					continue;
				s.localize( pos );
				byTp.computeIfAbsent( t, k -> new ArrayList<>() ).add( new double[] {
						pos[ 0 ], pos[ 1 ], pos[ 2 ],
						Math.sqrt( s.getBoundingSphereRadiusSquared() ),
						s.getInternalPoolIndex() } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		// Phase 2 (no lock): group overlapping-no-valley spots transitively, the same way
		// mergeDuplicateSpots does — three fragments tiling one cell are one cell, not two pairs.
		final double factor = duplicateOverlapFactor( ctx );
		final Map< Integer, List< Set< Integer > > > groupsByTp = new HashMap<>();
		for ( final Map.Entry< Integer, List< double[] > > e : byTp.entrySet() )
		{
			final int t = e.getKey();
			final List< double[] > list = e.getValue();
			final int n = list.size();
			final int[] parent = new int[ n ];
			for ( int i = 0; i < n; i++ )
				parent[ i ] = i;
			for ( int i = 0; i < n; i++ )
			{
				final double[] a = list.get( i );
				for ( int j = i + 1; j < n; j++ )
				{
					final double[] b = list.get( j );
					final double dx = a[ 0 ] - b[ 0 ], dy = a[ 1 ] - b[ 1 ], dz = a[ 2 ] - b[ 2 ];
					final double d2 = dx * dx + dy * dy + dz * dz;
					if ( !overlapsAsFragments( d2, a[ 3 ], b[ 3 ], factor ) )
						continue;
					final double[] posA = { a[ 0 ], a[ 1 ], a[ 2 ] };
					final double[] posB = { b[ 0 ], b[ 1 ], b[ 2 ] };
					final double sepRadii = Math.sqrt( d2 ) / Math.max( 1e-9, Math.max( a[ 3 ], b[ 3 ] ) );
					if ( !isSameBlob( ctx, t, posA, posB, sampleIntensity( ctx, t, posA ),
							sampleIntensity( ctx, t, posB ), sepRadii ) )
						continue;
					ufUnion( parent, i, j );
				}
			}
			final Map< Integer, Set< Integer > > byRoot = new HashMap<>();
			for ( int i = 0; i < n; i++ )
				byRoot.computeIfAbsent( ufFind( parent, i ), k -> new HashSet<>() )
						.add( ( int ) list.get( i )[ 4 ] );
			for ( final Set< Integer > g : byRoot.values() )
			{
				if ( g.size() >= 2 )
					groupsByTp.computeIfAbsent( t, k -> new ArrayList<>() ).add( g );
			}
		}
		if ( groupsByTp.isEmpty() )
			return 0;

		// Phase 3 (write lock): each group becomes ONE spot covering it.
		//
		// This used to delete the lower-scoring member of each pair — never tagging it fp, since that
		// would falsely confirm an occupied position as background. Deleting was still wrong in the
		// other direction: the survivor is one tile of the cell, so the result is a spot off the cell's
		// centre and smaller than it, which is then what linking follows and what the next refinement
		// round teaches the model a cell looks like.
		//
		// Deliberately NOT bounded by the expected-cell floor, unlike the flat-contrast pass. That floor
		// exists to stop a test deleting real CELLS; merging two spots on one cell lowers the spot count
		// without lowering the cell count, so the floor has no business blocking it. Bounding this pass
		// was a mistake: one window kept 2069 overlapping duplicates for that reason, and every one of
		// them was then taught to the model as a separate cell.
		int removed = 0;
		final Set< Integer > created = new HashSet<>();
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Map.Entry< Integer, List< Set< Integer > > > e : groupsByTp.entrySet() )
			{
				for ( final Set< Integer > g : e.getValue() )
					removed += mergeIntoCoveringEllipsoid( ctx, graph, e.getKey(), g, created );
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( removed == 0 )
			return 0;
		model.setUndoPoint();
		ctx.notifyGraphChanged();
		return removed;
	}

	/**
	 * Builds the shared prompt for a batch of {@code nCandidates}, each represented by THREE consecutive
	 * images (XY, XZ, YZ planes — see {@link #captureSpotCropPngMultiPlane}) in the image list passed to
	 * {@link VlmBackend#analyze}. Asks for exactly ONE verdict per candidate (not per image).
	 */
	public static StringBuilder buildMultiPlaneVerdictPrompt( final int nCandidates, final int planes )
	{
		final StringBuilder prompt = new StringBuilder();
		prompt.append( "There are " ).append( nCandidates ).append( " candidate cell detections. Each is shown " );
		if ( planes == 1 )
			prompt.append( "as ONE labelled image — candidate N is the image labelled \"Image N:\" — the XY " )
					.append( "plane through the candidate position, marked with a red crosshair. This dataset " )
					.append( "has a single Z plane, so there is no other view. " );
		else
			prompt.append( "as THREE consecutive labelled images — candidate N is the images labelled " )
					.append( "\"Image 3N-2:\", \"Image 3N-1:\" and \"Image 3N:\" — the XY, then XZ, then YZ orthogonal cross-section " )
					.append( "planes through the same 3D candidate position (marked with a red crosshair in each " )
					.append( "plane). Use all three views together (a single top-down XY view alone cannot reveal " )
					.append( "whether the crosshair is really a distinct 3D cell body, or just a Z-stacked " )
					.append( "duplicate/out-of-focus ghost of a neighbouring cell that only looks separate from " )
					.append( "directly above). " );
		prompt.append( "For EACH candidate, decide whether its crosshair sits on a real, " )
				.append( "in-focus cell body, or on background/noise/a duplicate artifact with no distinct " )
				.append( "cell there. Reply with exactly one line per candidate, in order, in this format and " )
				.append( "nothing else:\n" );
		for ( int i = 1; i <= nCandidates; i++ )
			prompt.append( "VERDICT_" ).append( i ).append( ": fp|real\n" );
		return prompt;
	}

	/**
	 * Saves each candidate's rendered crop(s) to disk under {@code ~/.automahout-vlm-debug/} along with the
	 * exact prompt and reply text, so a disputed tp/fp verdict can be inspected after the fact instead of
	 * re-diagnosed from screenshots. Best-effort: any failure here is logged but never propagated — this is
	 * a diagnostic aid, not part of the classification result.
	 */
	private static void saveVlmDiagnostics( final ToolContext ctx, final String callSiteLabel,
			final List< String[] > imagesPerCandidate, final String prompt, final String reply )
	{
		try
		{
			final java.nio.file.Path dir = java.nio.file.Paths.get( System.getProperty( "user.home" ),
					".automahout-vlm-debug", callSiteLabel + "_" + System.currentTimeMillis() );
			java.nio.file.Files.createDirectories( dir );
			final String[] planeNames = { "xy", "xz", "yz" };
			for ( int c = 0; c < imagesPerCandidate.size(); c++ )
			{
				final String[] planes = imagesPerCandidate.get( c );
				for ( int p = 0; p < planes.length && p < planeNames.length; p++ )
				{
					final byte[] bytes = Base64.getDecoder().decode( planes[ p ] );
					java.nio.file.Files.write(
							dir.resolve( String.format( "candidate_%d_%s.png", c + 1, planeNames[ p ] ) ), bytes );
				}
			}
			java.nio.file.Files.write( dir.resolve( "prompt.txt" ),
					prompt.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
			java.nio.file.Files.write( dir.resolve( "reply.txt" ),
					reply.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
			ctx.log( String.format( "  VLM diagnostics (%d candidate(s)) saved to %s",
					imagesPerCandidate.size(), dir ) );
		}
		catch ( final IOException e )
		{
			log.warn( "Failed to save VLM diagnostics: {}", e.getMessage() );
		}
	}

	/**
	 * Renders a small grayscale PNG crop of the image around {@code worldPos} at {@code timepoint}, with a
	 * red crosshair marking the exact candidate position, for VLM visual confirmation. Reads directly from
	 * the source's {@link RandomAccessibleInterval} — a small max-intensity projection over a few slices
	 * along the projected axis around {@code worldPos} (NOT a single slice: a spot's centroid along that
	 * axis, especially for the low-confidence detections this is used for, can easily be off by a slice or
	 * two, which made an otherwise clearly real, in-focus cell look dim/empty in a single-slice crop and
	 * get wrongly VLM-confirmed as background) — rather than the live {@link ViewerPanel}, so this is safe
	 * to call off the EDT and without touching the viewer's transform.
	 *
	 * @param axisU    world axis (0=x, 1=y, 2=z) mapped to the crop's horizontal direction
	 * @param axisV    world axis mapped to the crop's vertical direction
	 * @param axisProj the remaining world axis, max-intensity-projected over a small range
	 * @return base64-encoded PNG, or {@code null} if the source has no data at this position
	 */
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	private static String captureSpotCropPng( final SourceAndConverter< ? > sac, final int timepoint,
			final double[] worldPos, final double radius, final double[] display, final int axisU, final int axisV, final int axisProj )
	{
		return captureSpotCropPngTyped( ( SourceAndConverter ) sac, timepoint, worldPos, radius, display, axisU, axisV,
				axisProj );
	}

	/**
	 * Renders all three axis-aligned orthogonal-plane crops (XY, XZ, YZ) around {@code worldPos}, each
	 * independently max-projected and crosshair-marked (see {@link #captureSpotCropPng}) — a single XY
	 * slice cannot reveal Z-direction relationships (e.g. distinguishing a genuinely separate cell from a
	 * Z-stacked duplicate/ghost of a neighbouring one, invisible from directly above), so any VLM visual
	 * confirmation of "is there really a distinct cell here" needs all three views, exactly as a human
	 * would rotate the BigDataViewer volume to check.
	 *
	 * <p>On a dataset with no Z axis the reasoning above does not apply: there is no Z direction for a
	 * duplicate to hide in, and the XZ and YZ planes are one voxel tall. Demanding all three there
	 * disabled this whole path silently — on a 2D movie every candidate failed to render and every
	 * batch logged "none had renderable image data", so no synthetic negative was ever confirmed and
	 * ELEPHANT trained on positives alone for the entire run, which is the condition the calling code
	 * warns makes it over-detect. Such a dataset gets the XY plane, which is all it has.</p>
	 *
	 * @return {@code {xy, xz, yz}} on a volume and {@code {xy}} on a single-plane dataset, or
	 *         {@code null} if any plane it asked for has no image data (don't guess with a partial view)
	 */
	private static String[] captureSpotCropPngMultiPlane( final ToolContext ctx,
			final SourceAndConverter< ? > sac, final int timepoint,
			final double[] worldPos, final double radius, final double[] display )
	{
		final String xy = captureSpotCropPng( sac, timepoint, worldPos, radius, display, 0, 1, 2 );
		if ( xy == null )
			return null;
		if ( vlmImagesPerCandidate( ctx ) == 1 )
			return new String[] { xy };
		final String xz = captureSpotCropPng( sac, timepoint, worldPos, radius, display, 0, 2, 1 );
		final String yz = captureSpotCropPng( sac, timepoint, worldPos, radius, display, 1, 2, 0 );
		if ( xz == null || yz == null )
			return null;
		return new String[] { xy, xz, yz };
	}

	private static < T extends RealType< T > > String captureSpotCropPngTyped( final SourceAndConverter< T > sac,
			final int timepoint, final double[] worldPos, final double radius, final double[] display,
			final int axisU, final int axisV, final int axisProj )
	{
		final Source< T > source = sac.getSpimSource();
		if ( !source.isPresent( timepoint ) )
			return null;
		final AffineTransform3D transform = new AffineTransform3D();
		source.getSourceTransform( timepoint, 0, transform );
		final double[] voxel = new double[ 3 ];
		transform.applyInverse( voxel, worldPos );
		final RandomAccessibleInterval< T > rai = source.getSource( timepoint, 0 );

		// Approximate voxel size along each world axis (world units/voxel), to convert the spot radius
		// into crop pixels for the two in-plane axes, and to size the projected axis' max-intensity range.
		final double[] o0 = new double[ 3 ];
		transform.apply( new double[] { 0, 0, 0 }, o0 );
		final double[] voxelSize = new double[ 3 ];
		for ( int a = 0; a < 3; a++ )
		{
			final double[] unit = new double[ 3 ];
			unit[ a ] = 1;
			final double[] o = new double[ 3 ];
			transform.apply( unit, o );
			voxelSize[ a ] = Math.max( 1e-6, Math.sqrt(
					( o[ 0 ] - o0[ 0 ] ) * ( o[ 0 ] - o0[ 0 ] ) + ( o[ 1 ] - o0[ 1 ] ) * ( o[ 1 ] - o0[ 1 ] )
							+ ( o[ 2 ] - o0[ 2 ] ) * ( o[ 2 ] - o0[ 2 ] ) ) );
		}
		final int halfU = ( int ) Math.round( Math.max( VLM_CROP_MIN_HALF_PX,
				Math.min( VLM_CROP_MAX_HALF_PX, radius * VLM_CROP_RADIUS_FACTOR / voxelSize[ axisU ] ) ) );
		final int halfV = ( int ) Math.round( Math.max( VLM_CROP_MIN_HALF_PX,
				Math.min( VLM_CROP_MAX_HALF_PX, radius * VLM_CROP_RADIUS_FACTOR / voxelSize[ axisV ] ) ) );
		// Small projected-axis max-intensity-projection half-range: covers likely centroid-estimation error
		// along that axis without pulling in unrelated neighbours beyond it.
		final int projHalfRange = ( int ) Math.round( Math.max( 1, Math.min( 4, radius / voxelSize[ axisProj ] ) ) );

		final long[] centerVoxel =
				{ Math.round( voxel[ 0 ] ), Math.round( voxel[ 1 ] ), Math.round( voxel[ 2 ] ) };
		if ( centerVoxel[ axisProj ] < rai.min( axisProj ) || centerVoxel[ axisProj ] > rai.max( axisProj ) )
			return null;
		final long projLo = Math.max( rai.min( axisProj ), centerVoxel[ axisProj ] - projHalfRange );
		final long projHi = Math.min( rai.max( axisProj ), centerVoxel[ axisProj ] + projHalfRange );

		final long u0 = Math.max( rai.min( axisU ), centerVoxel[ axisU ] - halfU );
		final long u1 = Math.min( rai.max( axisU ), centerVoxel[ axisU ] + halfU );
		final long v0 = Math.max( rai.min( axisV ), centerVoxel[ axisV ] - halfV );
		final long v1 = Math.min( rai.max( axisV ), centerVoxel[ axisV ] + halfV );
		// A candidate close enough to the volume boundary that most of its crop would be outside cannot be
		// judged: the clipped image shows a sliver of tissue against the out-of-bounds region, and the
		// crosshair lands at its edge with almost nothing around it. Skip the candidate rather than send
		// an unanswerable picture — the caller drops candidates whose planes come back null.
		if ( u1 - u0 + 1 < VLM_CROP_MIN_INSIDE_FRACTION * ( 2 * halfU + 1 )
				|| v1 - v0 + 1 < VLM_CROP_MIN_INSIDE_FRACTION * ( 2 * halfV + 1 ) )
			return null;
		if ( u1 <= u0 || v1 <= v0 )
			return null;
		final int w = ( int ) ( u1 - u0 + 1 );
		final int h = ( int ) ( v1 - v0 + 1 );

		final RandomAccess< T > ra = rai.randomAccess();
		final double[] samples = new double[ w * h ];
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		// Only used when no shared range was supplied — see the display[] handling below.
		final long[] pos = new long[ 3 ];
		for ( int j = 0; j < h; j++ )
		{
			for ( int i = 0; i < w; i++ )
			{
				double best = Double.NEGATIVE_INFINITY;
				pos[ axisU ] = u0 + i;
				pos[ axisV ] = v0 + j;
				for ( long p = projLo; p <= projHi; p++ )
				{
					pos[ axisProj ] = p;
					ra.setPosition( pos );
					final double v = ra.get().getRealDouble();
					if ( v > best )
						best = v;
				}
				samples[ j * w + i ] = best;
				if ( best < min )
					min = best;
				if ( best > max )
					max = best;
			}
		}
		// Render against the TIMEPOINT's intensity range, not this crop's own extremes.
		//
		// Per-crop min/max is what this used to do, and it defeats the question being asked. A crop
		// holding nothing but background has its noise stretched across the full 0-255 range and comes
		// out looking like textured structure; a crop holding a cell looks much the same. The VLM is
		// then asked "is this background?" from images in which background has been made to look like
		// signal, and it answers "real" to almost everything — 30 of 33 in one saved batch, and 3-4%
		// confirmed negatives run after run. Sharing one range across every crop of a timepoint restores
		// the only thing that separates the two: absolute brightness. Background lands on the same dull
		// grey everywhere, and a cell stands out against it.
		//
		// Bright cells saturating at the top of the range is fine and intended: the question is "is this
		// background", not "how bright is this".
		if ( display != null && display.length == 2 && display[ 1 ] > display[ 0 ] )
		{
			min = display[ 0 ];
			max = display[ 1 ];
		}
		final double range = Math.max( 1e-9, max - min );

		final BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
		for ( int j = 0; j < h; j++ )
		{
			for ( int i = 0; i < w; i++ )
			{
				final int gray = ( int ) Math.round(
						255 * Math.max( 0.0, Math.min( 1.0, ( samples[ j * w + i ] - min ) / range ) ) );
				final int rgb = ( gray << 16 ) | ( gray << 8 ) | gray;
				img.setRGB( i, j, rgb );
			}
		}

		// Upscale for VLM legibility, then mark the exact candidate centre with a crosshair.
		final int outDim = Math.max( VLM_CROP_OUTPUT_DIM, Math.max( w, h ) );
		final BufferedImage scaled = new BufferedImage( outDim, outDim, BufferedImage.TYPE_INT_RGB );
		final Graphics2D g2 = scaled.createGraphics();
		g2.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR );
		g2.drawImage( img, 0, 0, outDim, outDim, null );
		final int ccx = ( int ) Math.round( outDim * ( centerVoxel[ axisU ] - u0 ) / ( double ) Math.max( 1, w - 1 ) );
		final int ccy = ( int ) Math.round( outDim * ( centerVoxel[ axisV ] - v0 ) / ( double ) Math.max( 1, h - 1 ) );
		g2.setColor( Color.RED );
		g2.setStroke( new BasicStroke( 2f ) );
		final int armLen = Math.max( 6, outDim / 10 );
		g2.drawLine( ccx - armLen, ccy, ccx - armLen / 3, ccy );
		g2.drawLine( ccx + armLen / 3, ccy, ccx + armLen, ccy );
		g2.drawLine( ccx, ccy - armLen, ccx, ccy - armLen / 3 );
		g2.drawLine( ccx, ccy + armLen / 3, ccx, ccy + armLen );
		g2.dispose();

		try
		{
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write( scaled, "png", baos );
			return Base64.getEncoder().encodeToString( baos.toByteArray() );
		}
		catch ( final IOException e )
		{
			log.warn( "Failed to encode VLM crop PNG: {}", e.getMessage() );
			return null;
		}
	}

	/**
	 * Offset distance from a source {@code tp} spot, as a multiple of its own radius, used to place a
	 * candidate synthetic negative — far enough that it cannot be mistaken for the same cell.
	 */
	private static final double SYNTHETIC_FP_OFFSET_FACTOR = 3.0;

	/** World-units floor on the offset distance, for very small spots. */
	private static final double SYNTHETIC_FP_MIN_OFFSET = 5.0;

	/**
	 * Independent VLM-confirmation batches tried per window when the previous round's confirmed count
	 * falls short of the requested budget: in a densely packed/confluent culture, a single batch's
	 * confirmation RATE can be very low (the VLM correctly rejecting most near-cell offsets as landing
	 * on another real, untracked cell rather than genuine background) — trying a second, independently
	 * sampled batch of sources gives the search another shot at finding real background elsewhere in the
	 * window, at the cost of one extra VLM call.
	 */
	private static final int SYNTHETIC_FP_MAX_ROUNDS = 2;

	/**
	 * Random directions tried per source before giving up on it as a synthetic-negative site. A single
	 * attempt collides too often in a densely packed/confluent culture (the offset lands on a
	 * neighbouring real cell), starving training of negatives regardless of how many sources are
	 * sampled.
	 */
	private static final int SYNTHETIC_FP_MAX_ATTEMPTS_PER_SOURCE = 8;

	/** True if any {@code {x,y,z,radius}} record in {@code group} overlaps the sphere at {@code pos,radius}. */
	private static boolean collidesWithAny( final List< double[] > group, final double[] pos, final double radius )
	{
		if ( group == null )
			return false;
		for ( final double[] g : group )
		{
			final double dx = g[ 0 ] - pos[ 0 ];
			final double dy = g[ 1 ] - pos[ 1 ];
			final double dz = g[ 2 ] - pos[ 2 ];
			final double sumR = g[ 3 ] + radius;
			if ( dx * dx + dy * dy + dz * dz < sumR * sumR )
				return true;
		}
		return false;
	}

	/** Fraction of the XY extent, from whichever face is nearest, that counts as an "edge region" for
	 * {@link #buildEdgeRegionFpCandidates}. */
	private static final double EDGE_REGION_MARGIN_FRACTION = 0.12;

	/** Collision-retry attempts per edge-region candidate slot before giving up on it. */
	private static final int EDGE_REGION_MAX_ATTEMPTS = 10;

	@SuppressWarnings( { "unchecked", "rawtypes" } )
	private static double[] computeWorldBoundsAtTimepoint( final SourceAndConverter< ? > sac, final int timepoint )
	{
		return computeWorldBoundsAtTimepointTyped( ( SourceAndConverter ) sac, timepoint );
	}

	/** @return {@code {xmin,ymin,zmin,xmax,ymax,zmax}} world bounds of the image at {@code timepoint}, or
	 *          {@code null} if there is no image data there */
	private static < T extends RealType< T > > double[] computeWorldBoundsAtTimepointTyped(
			final SourceAndConverter< T > sac, final int timepoint )
	{
		final Source< T > source = sac.getSpimSource();
		if ( !source.isPresent( timepoint ) )
			return null;
		final AffineTransform3D transform = new AffineTransform3D();
		source.getSourceTransform( timepoint, 0, transform );
		final RandomAccessibleInterval< T > rai = source.getSource( timepoint, 0 );
		final double[] a = new double[ 3 ];
		final double[] b = new double[ 3 ];
		transform.apply( new double[] { rai.min( 0 ), rai.min( 1 ), rai.min( 2 ) }, a );
		transform.apply( new double[] { rai.max( 0 ), rai.max( 1 ), rai.max( 2 ) }, b );
		return new double[] {
				Math.min( a[ 0 ], b[ 0 ] ), Math.min( a[ 1 ], b[ 1 ] ), Math.min( a[ 2 ], b[ 2 ] ),
				Math.max( a[ 0 ], b[ 0 ] ), Math.max( a[ 1 ], b[ 1 ] ), Math.max( a[ 2 ], b[ 2 ] ) };
	}

	/**
	 * Samples up to {@code count} candidate background positions near the image's XY periphery (see
	 * {@link #EDGE_REGION_MARGIN_FRACTION}) — a natural complement to
	 * {@link #buildSyntheticFpCandidates}'s near-cell offsets, added after the user pointed out that the
	 * sparser edges of a widefield/vignetted frame are a good source of stable fp signal that the
	 * near-cell strategy never visits at all (it only ever offsets FROM an existing cell). Sparse/edge
	 * regions are also far more likely to be genuine background than a near-cell offset in a dense
	 * culture, so this pool should see a substantially higher VLM confirmation rate.
	 *
	 * <p>Still passed through the SAME VLM confirmation step as near-cell candidates afterwards — never
	 * trusted purely geometrically. "Near the edge" is a good PRIOR that background is more likely there,
	 * not a guarantee (a cell can legitimately sit right at the border, e.g. one recently entering
	 * frame).</p>
	 *
	 * @return {@code {x,y,z,t,radius,cov0..8}} records, up to {@code count} (fewer if collisions or
	 *         missing image data exhaust the attempt budget)
	 */
	private static List< double[] > buildEdgeRegionFpCandidates( final SourceAndConverter< ? > sac,
			final Map< Integer, List< double[] > > allByTp, final int startTp, final int endTp,
			final double representativeRadius, final int count, final java.util.concurrent.ThreadLocalRandom rnd )
	{
		final List< double[] > candidates = new ArrayList<>();
		if ( count <= 0 )
			return candidates;
		final int windowLen = endTp - startTp + 1;
		final Map< Integer, double[] > boundsCache = new HashMap<>();
		final double[] identityCov = {
				representativeRadius * representativeRadius, 0, 0,
				0, representativeRadius * representativeRadius, 0,
				0, 0, representativeRadius * representativeRadius };
		for ( int i = 0; i < count; i++ )
		{
			final int t = startTp + rnd.nextInt( windowLen );
			final double[] bounds = boundsCache.computeIfAbsent( t, tp -> computeWorldBoundsAtTimepoint( sac, tp ) );
			if ( bounds == null )
				continue; // no image data at this timepoint
			final double xExtent = bounds[ 3 ] - bounds[ 0 ];
			final double yExtent = bounds[ 4 ] - bounds[ 1 ];
			final double mx = xExtent * EDGE_REGION_MARGIN_FRACTION;
			final double my = yExtent * EDGE_REGION_MARGIN_FRACTION;
			double[] pos = null;
			for ( int attempt = 0; attempt < EDGE_REGION_MAX_ATTEMPTS; attempt++ )
			{
				final int edge = rnd.nextInt( 4 ); // 0=left, 1=right, 2=bottom, 3=top (X/Y world axes)
				final double x, y;
				switch ( edge )
				{
					case 0:
						x = bounds[ 0 ] + rnd.nextDouble() * mx;
						y = bounds[ 1 ] + rnd.nextDouble() * yExtent;
						break;
					case 1:
						x = bounds[ 3 ] - rnd.nextDouble() * mx;
						y = bounds[ 1 ] + rnd.nextDouble() * yExtent;
						break;
					case 2:
						x = bounds[ 0 ] + rnd.nextDouble() * xExtent;
						y = bounds[ 1 ] + rnd.nextDouble() * my;
						break;
					default:
						x = bounds[ 0 ] + rnd.nextDouble() * xExtent;
						y = bounds[ 4 ] - rnd.nextDouble() * my;
						break;
				}
				final double z = bounds[ 2 ] + rnd.nextDouble() * ( bounds[ 5 ] - bounds[ 2 ] );
				final double[] cand = { x, y, z };
				if ( !collidesWithAny( allByTp.get( t ), cand, representativeRadius ) )
				{
					pos = cand;
					break;
				}
			}
			if ( pos == null )
				continue; // every attempted edge position landed on/near an existing spot
			final double[] rec = new double[ 14 ];
			rec[ 0 ] = pos[ 0 ];
			rec[ 1 ] = pos[ 1 ];
			rec[ 2 ] = pos[ 2 ];
			rec[ 3 ] = t;
			rec[ 4 ] = representativeRadius;
			System.arraycopy( identityCov, 0, rec, 5, 9 );
			candidates.add( rec );
		}
		return candidates;
	}

	/**
	 * Builds offset-from-source candidate positions for {@code sampled} sources (see
	 * {@link #synthesizeNearCellFpNegatives}): tries up to {@link #SYNTHETIC_FP_MAX_ATTEMPTS_PER_SOURCE}
	 * random directions at escalating distance per source, skipping any source where every attempt
	 * collided with an existing spot.
	 *
	 * @return {@code {x,y,z,t,radius,cov0..8}} records, one per source that found a collision-free spot
	 */
	private static List< double[] > buildSyntheticFpCandidates( final List< double[] > sampled,
			final Map< Integer, List< double[] > > allByTp, final java.util.concurrent.ThreadLocalRandom rnd,
			final boolean volumetric )
	{
		final List< double[] > candidates = new ArrayList<>();
		for ( final double[] src : sampled )
		{
			final int t = ( int ) src[ 3 ];
			final double radius = src[ 4 ];
			final double minOffsetDist = Math.max( radius * SYNTHETIC_FP_OFFSET_FACTOR, SYNTHETIC_FP_MIN_OFFSET );
			double[] offsetPos = null;
			for ( int attempt = 0; attempt < SYNTHETIC_FP_MAX_ATTEMPTS_PER_SOURCE; attempt++ )
			{
				// Escalate distance across attempts (not just direction): in a densely packed/confluent
				// culture, a collision-free spot right at minOffsetDist is often still close enough to an
				// UNTRACKED real cell that the VLM (correctly) rejects it as "real," not background — a
				// candidate a bit further out is more likely to sit in genuine gap space. Bounded at 2x
				// (same min/max ring pattern as placeBackgroundNegatives' cold-start seeding) so it never
				// drifts so far it stops being a meaningful NEAR-cell negative.
				final double distScale = 1.0 + ( double ) attempt / ( SYNTHETIC_FP_MAX_ATTEMPTS_PER_SOURCE - 1 );
				final double offsetDist = minOffsetDist * distScale;
				double dx, dy, dz, norm;
				do
				{
					dx = rnd.nextDouble( -1, 1 );
					dy = rnd.nextDouble( -1, 1 );
					// A movie with one z plane has no z to offset into. Spending part of the offset on
					// that axis shortens the IN-PLANE distance to whatever is left, which puts the
					// negative back on the cell it came from: measured on 92 frames of Fluo-N2DL-HeLa
					// silver truth, 8.4% of negatives land on a real cell with z sampled, 3.0% with the
					// offset kept in plane. The three other random-position sites in this class already
					// guard the z axis this way; this one, which picks a DIRECTION rather than a
					// position, did not.
					dz = volumetric ? rnd.nextDouble( -1, 1 ) : 0.0;
					norm = Math.sqrt( dx * dx + dy * dy + dz * dz );
				}
				while ( norm < 1e-6 );
				final double[] candidatePos = {
						src[ 0 ] + dx / norm * offsetDist,
						src[ 1 ] + dy / norm * offsetDist,
						src[ 2 ] + dz / norm * offsetDist
				};
				if ( !collidesWithAny( allByTp.get( t ), candidatePos, radius ) )
				{
					offsetPos = candidatePos;
					break;
				}
			}
			if ( offsetPos == null )
				continue; // every attempted direction landed on/near an existing spot — skip this source
			final double[] rec = new double[ 14 ];
			rec[ 0 ] = offsetPos[ 0 ];
			rec[ 1 ] = offsetPos[ 1 ];
			rec[ 2 ] = offsetPos[ 2 ];
			rec[ 3 ] = t;
			rec[ 4 ] = radius;
			System.arraycopy( src, 5, rec, 5, 9 ); // reuse the source spot's own covariance verbatim
			candidates.add( rec );
		}
		return candidates;
	}

	/**
	 * Renders multi-plane crops for {@code candidates} (truncated to {@link #vlmMaxCandidatesPerCall}
	 * first — see {@link #synthesizeNearCellFpNegatives}) and asks the VLM to confirm each is genuine
	 * background, not a real cell.
	 *
	 * @return confirmed candidates (possibly empty — a legitimate "the VLM reviewed these and none were
	 *         background" outcome), or {@code null} if no verdict could be obtained at all this round
	 *         (VLM call failed, or its reply was unparseable)
	 */
	/**
	 * Confirms every candidate, SPLITTING them across as many VLM calls as the backend's per-call media
	 * limit requires.
	 *
	 * <p>This used to truncate to one call's worth instead, which silently capped the whole mechanism:
	 * three rendered planes per candidate against a ~100-image limit allows only 33 candidates per call,
	 * so a window holding ~4000 tp annotations could never obtain more than a few dozen negatives no
	 * matter what budget the caller asked for. ELEPHANT's own training stats showed the consequence
	 * directly — {@code fp=0} on most frames — and a model given essentially no negative signal drifts
	 * into detecting background, which self-training then amplifies window over window.</p>
	 *
	 * <p>The per-call limit is a transport constraint, not a statement about how many negatives are
	 * useful, so it belongs here as a batch size rather than as a cap on the caller's budget.</p>
	 *
	 * @return every confirmed candidate across all batches; {@code null} only if NO batch produced a
	 *         usable verdict (a later batch failing keeps what earlier ones confirmed)
	 */
	private static List< double[] > confirmSyntheticFpCandidatesViaVlm( final ToolContext ctx,
			final SourceAndConverter< ? > sac, final VlmBackend backend, final String model,
			final List< double[] > candidatesIn )
	{
		if ( candidatesIn.size() <= vlmMaxCandidatesPerCall( ctx ) )
			return confirmSyntheticFpBatchViaVlm( ctx, sac, backend, model, candidatesIn );

		final int nBatches =
				( candidatesIn.size() + vlmMaxCandidatesPerCall( ctx ) - 1 ) / vlmMaxCandidatesPerCall( ctx );
		ctx.log( String.format( "  fp negative synthesis: %d candidate(s) across %d VLM call(s) "
				+ "(%d per call, the backend's per-call media limit).",
				candidatesIn.size(), nBatches, vlmMaxCandidatesPerCall( ctx ) ) );
		final List< double[] > confirmedAll = new ArrayList<>();
		boolean anyVerdict = false;
		for ( int from = 0; from < candidatesIn.size(); from += vlmMaxCandidatesPerCall( ctx ) )
		{
			final int to = Math.min( candidatesIn.size(), from + vlmMaxCandidatesPerCall( ctx ) );
			final List< double[] > batch =
					confirmSyntheticFpBatchViaVlm( ctx, sac, backend, model, candidatesIn.subList( from, to ) );
			if ( batch == null )
			{
				// One failed call must not discard the batches that already succeeded: the negatives they
				// confirmed are just as valid, and a partial set is worth far more than none.
				if ( !anyVerdict )
					return null;
				break;
			}
			anyVerdict = true;
			confirmedAll.addAll( batch );
		}
		return confirmedAll;
	}

	/** Spots per group in one {@link #auditBackgroundVerdictViaVlm} call — 2x16+1 stays inside the 33 cap. */
	private static final int AUDIT_SPOTS_PER_GROUP = 16;

	/**
	 * Minimum gap between the two groups' background rates for the audit to have shown anything. Below
	 * it the VLM is answering the same way for cells and for flagged detections, which is a statement
	 * about the VLM, not about the flags.
	 */
	private static final double AUDIT_MEANINGFUL_SEPARATION = 0.4;

	/**
	 * Asks the VLM whether the detections the flat-contrast check flagged really are background, and
	 * reports the answer without acting on it.
	 *
	 * <p>Read-only on purpose. The check flags 47-70% of a timepoint on this dataset and the floor then
	 * refuses most of those deletions, so the pipeline has been running for weeks on an unresolved
	 * disagreement between two of its own estimates — with no measurement of which one is right, because
	 * the VLM has only ever been shown SYNTHESIZED positions (deliberately-empty space offset from a
	 * cell), never an actual flagged detection.</p>
	 *
	 * <p>The {@code kept} group is what makes the answer readable, and is not optional. "The VLM called
	 * 80% of the flagged ones background" means nothing on its own — the same VLM may call 80% of the
	 * KEPT ones background too, in which case it is not separating the groups and the audit is silent
	 * rather than confirming. Both groups are sampled evenly across their timepoint and interleaved, so
	 * neither graph order nor position in the batch tracks the grouping.</p>
	 *
	 * @param flagged spots the check judged background, as {@code {x,y,z,radius}}
	 * @param kept    spots it judged real, same layout — the control
	 * @return a one-line summary to log, or {@code null} if the audit could not run
	 */
	public static String auditBackgroundVerdictViaVlm( final ToolContext ctx, final VlmBackend backend,
			final String model, final int timepoint, final List< double[] > flagged,
			final List< double[] > kept, final int[] tally )
	{
		if ( backend == null || flagged.isEmpty() || kept.isEmpty() )
			return null;
		final ViewerPanel viewer = ctx.requireViewer();
		if ( viewer.state().getSources().isEmpty() )
			return null;
		final SourceAndConverter< ? > sac = viewer.state().getSources().get( 0 );
		final List< double[] > flaggedSample = sampleEvenly( flagged, AUDIT_SPOTS_PER_GROUP );
		final List< double[] > keptSample = sampleEvenly( kept, AUDIT_SPOTS_PER_GROUP );

		// Interleave, remembering which group each rendered candidate came from.
		final double[] display = vlmDisplayRange( ctx, timepoint );
		final List< String[] > imagesPerCandidate = new ArrayList<>();
		final List< Boolean > wasFlagged = new ArrayList<>();
		for ( int i = 0; i < Math.max( flaggedSample.size(), keptSample.size() ); i++ )
			for ( int group = 0; group < 2; group++ )
			{
				final List< double[] > from = group == 0 ? flaggedSample : keptSample;
				if ( i >= from.size() )
					continue;
				final double[] c = from.get( i );
				final String[] planes = captureSpotCropPngMultiPlane( ctx, sac, timepoint,
						new double[] { c[ 0 ], c[ 1 ], c[ 2 ] }, c[ 3 ], display );
				if ( planes == null )
					continue;
				imagesPerCandidate.add( planes );
				wasFlagged.add( group == 0 );
			}
		if ( imagesPerCandidate.isEmpty() )
			return null;

		final List< String > images = new ArrayList<>();
		for ( final String[] planes : imagesPerCandidate )
			images.addAll( java.util.Arrays.asList( planes ) );
		final StringBuilder prompt = buildMultiPlaneVerdictPrompt( imagesPerCandidate.size(), vlmImagesPerCandidate( ctx ) );
		final String reply;
		try
		{
			reply = backend.analyze( model, prompt.toString(), images );
		}
		catch ( final IOException e )
		{
			log.warn( "flat-contrast audit VLM call failed: {}", e.getMessage() );
			return null;
		}
		saveVlmDiagnostics( ctx, "audit", imagesPerCandidate, prompt.toString(), reply );

		int flaggedTotal = 0, flaggedBackground = 0, keptTotal = 0, keptBackground = 0;
		final Matcher m = VLM_VERDICT_LINE.matcher( reply );
		while ( m.find() )
		{
			final int idx = Integer.parseInt( m.group( 1 ) ) - 1;
			if ( idx < 0 || idx >= wasFlagged.size() )
				continue;
			final boolean background = "fp".equalsIgnoreCase( m.group( 2 ) );
			if ( wasFlagged.get( idx ) )
			{
				flaggedTotal++;
				if ( background ) flaggedBackground++;
			}
			else
			{
				keptTotal++;
				if ( background ) keptBackground++;
			}
		}
		if ( flaggedTotal == 0 || keptTotal == 0 )
			return null;
		if ( tally != null )
		{
			// One call's 16-vs-16 cannot separate 62% from 38%; the run-wide total can.
			tally[ 0 ] += flaggedBackground;
			tally[ 1 ] += flaggedTotal;
			tally[ 2 ] += keptBackground;
			tally[ 3 ] += keptTotal;
		}
		final double flaggedRate = ( double ) flaggedBackground / flaggedTotal;
		final double keptRate = ( double ) keptBackground / keptTotal;
		final String verdict;
		if ( flaggedRate - keptRate >= AUDIT_MEANINGFUL_SEPARATION )
			verdict = "the two groups separate, so the check is finding real background and the floor is "
					+ "what is keeping it";
		else if ( keptRate - flaggedRate >= AUDIT_MEANINGFUL_SEPARATION )
			verdict = "INVERTED — the VLM calls the KEPT spots background more often than the flagged "
					+ "ones, so the check is selecting against real cells";
		else if ( flaggedRate > 0.5 && keptRate > 0.5 )
			verdict = "the VLM calls BOTH groups background, so either most detections really are "
					+ "background or the crops are unreadable — inconclusive about the flags themselves";
		else
			verdict = "the VLM does not separate the two groups, so this is no evidence either way about "
					+ "the flags";
		return String.format(
				"  Flat-contrast audit at t=%d (VLM): %d/%d flagged called background vs %d/%d kept "
						+ "(control) — %s.",
				timepoint, flaggedBackground, flaggedTotal, keptBackground, keptTotal, verdict );
	}

	/**
	 * The run-wide total of every {@link #auditBackgroundVerdictViaVlm} call, as a line to log at the
	 * end, or {@code null} if none ran. A single call compares 16 against 16, which cannot tell 62%
	 * apart from 38%; only the pooled total can say whether the flat-contrast check and the VLM agree.
	 */
	public static String auditSummary( final int[] tally )
	{
		if ( tally == null || tally[ 1 ] == 0 || tally[ 3 ] == 0 )
			return null;
		final double flaggedRate = ( double ) tally[ 0 ] / tally[ 1 ];
		final double keptRate = ( double ) tally[ 2 ] / tally[ 3 ];
		final String verdict;
		if ( flaggedRate - keptRate >= AUDIT_MEANINGFUL_SEPARATION )
			verdict = "the VLM agrees with the check — what it flags really is background, and the "
					+ "cell-count floor is what keeps it";
		else if ( keptRate - flaggedRate >= AUDIT_MEANINGFUL_SEPARATION )
			verdict = "INVERTED — the VLM calls the KEPT spots background more often, so the check is "
					+ "selecting against real cells";
		else if ( flaggedRate > 0.5 && keptRate > 0.5 )
			verdict = "the VLM calls most of BOTH groups background — either the detection really is "
					+ "mostly background or these crops cannot be read";
		else
			verdict = "the VLM does not separate the two groups, so it neither confirms nor refutes the "
					+ "check";
		return String.format(
				"  Flat-contrast audit, whole run (VLM): %.0f%% of %d flagged called background vs %.0f%% "
						+ "of %d kept (control) — %s.",
				flaggedRate * 100, tally[ 1 ], keptRate * 100, tally[ 3 ], verdict );
	}

	/** Up to {@code max} items spread evenly across {@code from}, rather than the first {@code max}. */
	static List< double[] > sampleEvenly( final List< double[] > from, final int max )
	{
		if ( from.size() <= max )
			return from;
		final List< double[] > out = new ArrayList<>( max );
		for ( int i = 0; i < max; i++ )
			out.add( from.get( ( int ) ( ( long ) i * from.size() / max ) ) );
		return out;
	}

	/**
	 * Confirms synthetic fp candidates by MEASURING each position instead of showing it to a VLM.
	 *
	 * <p>The question is the same one: is anything at this position, or is it background? Where
	 * brightness means signal that is answerable from the pixels — local contrast against this frame's
	 * own noise floor, and absolute intensity against its own background baseline, which is exactly
	 * what {@link BackgroundStats#isDefinitelyBackground} already decides everywhere else in this
	 * class. The candidates arrive already guaranteed not to overlap a detected spot, so what this adds
	 * is the check for an UNDETECTED cell sitting where the offset landed.</p>
	 *
	 * <p>Chosen for fluorescence over the VLM because the VLM's answer on these crops could not be
	 * checked and, once it was, did not survive: of 2444 positions that are genuinely empty it called
	 * 17% empty, and of the ones holding a cell it called 35% empty (see
	 * {@code benchmark/vlm-fp-negatives}). This answer is a number that can be recomputed. It is only
	 * offered where brightness means signal — {@code ImageProfile} decides that, and on phase contrast
	 * it does not hold, which is why the toggle exists rather than a replacement.</p>
	 *
	 * @return the candidates measured to be background; never {@code null}
	 */
	private static List< double[] > confirmSyntheticFpCandidatesByMeasurement( final ToolContext ctx,
			final List< double[] > candidates )
	{
		final List< double[] > confirmed = new ArrayList<>();
		final Map< Integer, BackgroundStats > statsByTp = new HashMap<>();
		int rejected = 0;
		for ( final double[] c : candidates )
		{
			final int t = ( int ) c[ 3 ];
			final BackgroundStats stats =
					statsByTp.computeIfAbsent( t, k -> estimateBackgroundStats( ctx, k ) );
			final Sampler sampler = sharedSampler( ctx, t );
			if ( stats == null || sampler == null )
				continue; // nothing measured here — declining is the conservative answer
			final double[] pos = { c[ 0 ], c[ 1 ], c[ 2 ] };
			if ( stats.isDefinitelyBackground( Math.abs( sampleContrast( sampler, pos, c[ 4 ] ) ),
					sampler.at( pos ) ) )
				confirmed.add( c );
			else
				rejected++;
		}
		ctx.log( String.format(
				"  fp negative synthesis: %d of %d candidate(s) measured as background (%d rejected as "
						+ "holding signal). No VLM call — this dataset reads as fluorescence, where the "
						+ "pixels answer this and the answer can be recomputed.",
				confirmed.size(), candidates.size(), rejected ) );
		return confirmed;
	}

	/** One VLM call's worth of candidates — see {@link #confirmSyntheticFpCandidatesViaVlm}. */
	private static List< double[] > confirmSyntheticFpBatchViaVlm( final ToolContext ctx,
			final SourceAndConverter< ? > sac, final VlmBackend backend, final String model,
			final List< double[] > candidatesIn )
	{
		final List< double[] > candidates = candidatesIn;

		// Render three orthogonal-plane crops per candidate (skipping any with no image data in all three
		// planes) — all three planes matter here, since an offset candidate could easily land right next
		// to (Z-adjacent to) the very cell it was offset from.
		final List< String[] > imagesPerCandidate = new ArrayList<>();
		final List< double[] > withImage = new ArrayList<>();
		// One display range per timepoint, shared by every crop from it — see vlmDisplayRange.
		final Map< Integer, double[] > displayByTp = new HashMap<>();
		for ( final double[] c : candidates )
		{
			final double[] wpos = { c[ 0 ], c[ 1 ], c[ 2 ] };
			final int t = ( int ) c[ 3 ];
			final String[] planes = captureSpotCropPngMultiPlane( ctx, sac, t, wpos, c[ 4 ],
					displayByTp.computeIfAbsent( t, k -> vlmDisplayRange( ctx, k ) ) );
			if ( planes == null )
				continue; // unrenderable or mostly out of bounds — skip this candidate, not the batch
			imagesPerCandidate.add( planes );
			withImage.add( c );
		}
		if ( imagesPerCandidate.isEmpty() )
		{
			ctx.log( String.format( "  fp negative synthesis: %d candidate(s) found but none had renderable "
					+ "image data (out of volume bounds?) this round.", candidates.size() ) );
			return java.util.Collections.emptyList();
		}
		final List< String > images = new ArrayList<>();
		for ( final String[] planes : imagesPerCandidate )
			images.addAll( java.util.Arrays.asList( planes ) );

		final StringBuilder prompt = buildMultiPlaneVerdictPrompt( imagesPerCandidate.size(), vlmImagesPerCandidate( ctx ) );

		final String reply;
		try
		{
			reply = backend.analyze( model, prompt.toString(), images );
		}
		catch ( final IOException e )
		{
			log.warn( "VLM synthetic-negative confirmation call failed: {}", e.getMessage() );
			ctx.log( "  fp negative synthesis: VLM call failed (" + e.getMessage() + ") this round." );
			return null;
		}
		saveVlmDiagnostics( ctx, "synthesize", imagesPerCandidate, prompt.toString(), reply );

		final List< double[] > confirmed = new ArrayList<>();
		final Matcher m = VLM_VERDICT_LINE.matcher( reply );
		boolean any = false;
		while ( m.find() )
		{
			any = true;
			final int idx = Integer.parseInt( m.group( 1 ) ) - 1;
			if ( idx < 0 || idx >= withImage.size() )
				continue;
			if ( "fp".equalsIgnoreCase( m.group( 2 ) ) )
				confirmed.add( withImage.get( idx ) );
			// "real" verdict: skip — the offset accidentally landed on/near an actual cell, don't teach it
			// as background.
		}
		if ( !any )
		{
			log.warn( "VLM synthetic-negative confirmation reply had no parseable VERDICT lines: {}", reply );
			ctx.log( "  fp negative synthesis: VLM reply had no parseable VERDICT line for "
					+ imagesPerCandidate.size() + " candidate(s) this round (see "
					+ "~/.automahout-vlm-debug for the raw reply)." );
			return null;
		}
		if ( confirmed.isEmpty() )
			ctx.log( String.format( "  fp negative synthesis: VLM judged all %d candidate(s) as real cells, "
					+ "not background, this round.", imagesPerCandidate.size() ) );
		return confirmed;
	}

	/**
	 * Synthesizes a small number of explicit {@code fp} negatives per window from TWO complementary
	 * candidate pools — near-cell offsets (see {@link #buildSyntheticFpCandidates}) and sparse image-edge
	 * positions (see {@link #buildEdgeRegionFpCandidates}), split roughly half/half of the per-round
	 * budget — each checked for collision with an existing spot, then confirmed by the VLM before
	 * becoming a real {@code fp}-tagged spot (so a genuinely close neighbouring cell, near a cell OR near
	 * the edge, is never mistaught as background).
	 *
	 * <p>The near-cell pool directly targets the recurring "small artifact stuck to the edge of a real
	 * cell" failure mode: training on {@code tp} alone gives the model no negative signal for the space
	 * immediately around a real cell, so self-training drifts into detecting fragments there. The
	 * edge-region pool complements it: the periphery of a widefield/vignetted frame is typically much
	 * sparser in real cells, so it (a) gives the VLM confirmation step a much higher hit rate than
	 * near-cell offsets alone can in a dense/confluent culture, and (b) is a region the near-cell pool
	 * never visits at all (it only ever offsets FROM an existing cell). This is a supplementary signal:
	 * callers do not need to fall back to anything if it returns {@code null}, they simply proceed
	 * without synthetic negatives for this window.</p>
	 *
	 * <p>Tries up to {@link #SYNTHETIC_FP_MAX_ROUNDS} independently-sampled batches (each a separate VLM
	 * call) if earlier rounds fall short of {@code maxPerWindow} confirmed — see
	 * {@link #confirmSyntheticFpCandidatesViaVlm} for why one batch's confirmation rate alone is often far
	 * below what a dense/confluent window's source pool could otherwise supply.</p>
	 *
	 * @return number of synthetic negatives created (possibly 0), or {@code null} only if NO round
	 *         produced a usable VLM verdict at all (a later round's failure after an earlier success
	 *         still returns whatever was already confirmed)
	 */
	public static Integer synthesizeNearCellFpNegatives( final ToolContext ctx, final int startTp, final int endTp,
			final VlmBackend backend, final String model, final int maxPerWindow )
	{
		final Model mastodonModel = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();
		final ViewerPanel viewer = ctx.requireViewer();
		final List< SourceAndConverter< ? > > sources = viewer.state().getSources();
		if ( sources.isEmpty() )
		{
			ctx.log( "  fp negative synthesis: no image source available — skipped." );
			return null;
		}
		final SourceAndConverter< ? > sac = sources.get( 0 );
		// Ensure the tag set exists (modifies the tag-set structure) BEFORE taking the vertex write lock.
		final TagSet tagSet = DetectionTagLabels.ensure( mastodonModel );
		final Tag fpTag = DetectionTagLabels.fpTag( tagSet );
		final ObjTags< Spot > vertexTags = mastodonModel.getTagSetModel().getVertexTags();

		// Phase 1 (read lock): gather every tp-worthy spot ({x,y,z,t,radius,cov0..8}) as a source, and every
		// spot regardless of tag ({x,y,z,radius}) per timepoint for collision avoidance.
		final List< double[] > sourceCandidates = new ArrayList<>();
		final Map< Integer, List< double[] > > allByTp = new HashMap<>();
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
				final double r = Math.sqrt( s.getBoundingSphereRadiusSquared() );
				allByTp.computeIfAbsent( t, k -> new ArrayList<>() ).add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ], r } );
				if ( "fp".equals( DetectionTagLabels.labelOf( mastodonModel, s ) ) )
					continue; // already background — not a valid source to offset from
				s.getCovariance( cov );
				final double[] rec = new double[ 14 ];
				rec[ 0 ] = pos[ 0 ];
				rec[ 1 ] = pos[ 1 ];
				rec[ 2 ] = pos[ 2 ];
				rec[ 3 ] = t;
				rec[ 4 ] = r;
				for ( int rr = 0; rr < 3; rr++ )
					for ( int cc = 0; cc < 3; cc++ )
						rec[ 5 + rr * 3 + cc ] = cov[ rr ][ cc ];
				sourceCandidates.add( rec );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( sourceCandidates.isEmpty() )
		{
			ctx.log( "  fp negative synthesis: no tp-worthy spot in t=" + startTp + ".." + endTp
					+ " to offset from — 0 synthesized." );
			return 0;
		}

		// Up to SYNTHETIC_FP_MAX_ROUNDS independent VLM-confirmation batches: in a densely packed/confluent
		// culture, a single batch's confirmation RATE can be very low (see buildSyntheticFpCandidates'
		// doc — the VLM correctly rejecting most near-cell offsets as landing on another real, untracked
		// cell) — observed on a real run as 3 confirmed out of 30 sent. A second, independently sampled
		// batch (different source spots, fresh random offsets) gives the search another shot at finding
		// real background elsewhere in the window, rather than accepting whatever the first batch found.
		final java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
		final int stride = Math.max( 1, sourceCandidates.size() / Math.max( 1, maxPerWindow ) );
		double radiusSum = 0;
		for ( final double[] src : sourceCandidates )
			radiusSum += src[ 4 ];
		final double representativeRadius = radiusSum / sourceCandidates.size();
		// Split the per-round budget between near-cell offsets and edge-region positions (see
		// buildEdgeRegionFpCandidates — added after the user pointed out the sparser image periphery is a
		// good, largely untapped source of stable fp signal that near-cell offsetting never visits).
		final int edgeBudgetPerRound = maxPerWindow / 2;
		final int nearCellBudgetPerRound = maxPerWindow - edgeBudgetPerRound;
		final List< double[] > confirmedAll = new ArrayList<>();
		boolean anyRoundGotVerdict = false;
		for ( int round = 0; round < SYNTHETIC_FP_MAX_ROUNDS && confirmedAll.size() < maxPerWindow; round++ )
		{
			// Round 0 starts at the front, spread across the whole source list; later rounds start at a
			// shifted phase so they sample DIFFERENT sources where possible (rather than re-deriving the
			// exact same offsets), falling back to re-trying the same (small) pool with fresh random
			// draws once the source list is smaller than maxPerWindow.
			final int startOffset = round * Math.max( 1, stride / 2 );
			final List< double[] > candidates = new ArrayList<>();
			// First: the positions where correction concluded the model was wrong. These are the only
			// candidates that are actually false positives OF THE MODEL — a near-cell offset and an
			// edge-region sample are both places the model never claimed anything, so a negative there
			// spends ten times the weight of a positive confirming empty space. Taken before the other
			// two pools so the budget goes to real errors while there are any. Still confirmed by the
			// same measurement as everything else: track surgery is evidence, not a verdict.
			if ( round == 0 )
			{
				final List< double[] > evidence = CorrectionEvidence.drain( ctx );
				for ( final double[] e : evidence )
				{
					if ( candidates.size() >= maxPerWindow )
						break;
					final int t = ( int ) e[ 3 ];
					if ( t < startTp || t > endTp )
						continue;
					final double r = e[ 4 ] > 0 ? e[ 4 ] : representativeRadius;
					final double[] rec = new double[ 14 ];
					rec[ 0 ] = e[ 0 ];
					rec[ 1 ] = e[ 1 ];
					rec[ 2 ] = e[ 2 ];
					rec[ 3 ] = t;
					rec[ 4 ] = r;
					rec[ 5 ] = rec[ 9 ] = rec[ 13 ] = r * r; // spherical, the radius it was removed at
					candidates.add( rec );
				}
				if ( !evidence.isEmpty() )
					ctx.log( String.format( "  fp negative synthesis: %d of %d candidate(s) come from "
							+ "positions where correction removed a detection — actual errors of the model, "
							+ "rather than background it never claimed.", candidates.size(),
							evidence.size() ) );
			}
			if ( startOffset < sourceCandidates.size() && candidates.size() < maxPerWindow )
			{
				final List< double[] > sampled = new ArrayList<>();
				final int nearCellLeft = Math.max( 0, nearCellBudgetPerRound - candidates.size() );
				for ( int i = startOffset; i < sourceCandidates.size() && sampled.size() < nearCellLeft;
						i += stride )
					sampled.add( sourceCandidates.get( i ) );
				candidates.addAll( buildSyntheticFpCandidates( sampled, allByTp, rnd,
						ctx.isVolumetric() ) );
			}
			if ( candidates.size() < maxPerWindow )
				candidates.addAll( buildEdgeRegionFpCandidates( sac, allByTp, startTp, endTp,
						representativeRadius, edgeBudgetPerRound, rnd ) );
			if ( candidates.isEmpty() )
			{
				ctx.log( String.format( "  fp negative synthesis round %d/%d: no viable candidate found "
						+ "(near-cell offsets all collided, and no edge-region position cleared collision "
						+ "either).", round + 1, SYNTHETIC_FP_MAX_ROUNDS ) );
				continue;
			}
			final List< double[] > confirmed = CleanupToggles.vlmCropCheckEnabled( ctx )
					? confirmSyntheticFpCandidatesViaVlm( ctx, sac, backend, model, candidates )
					: confirmSyntheticFpCandidatesByMeasurement( ctx, candidates );
			if ( confirmed == null )
			{
				if ( !anyRoundGotVerdict )
					return null; // total failure — not even one round produced a usable VLM verdict
				break; // keep whatever earlier round(s) already confirmed rather than discarding it
			}
			anyRoundGotVerdict = true;
			confirmedAll.addAll( confirmed );
		}
		if ( confirmedAll.isEmpty() )
			return 0;

		// Phase 3 (write lock): create the confirmed synthetic negatives.
		final Spot ref = graph.vertexRef();
		int created = 0;
		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				final double[] p = new double[ 3 ];
				final double[][] cov = new double[ 3 ][ 3 ];
				for ( final double[] c : confirmedAll )
				{
					p[ 0 ] = c[ 0 ];
					p[ 1 ] = c[ 1 ];
					p[ 2 ] = c[ 2 ];
					for ( int rr = 0; rr < 3; rr++ )
						for ( int cc = 0; cc < 3; cc++ )
							cov[ rr ][ cc ] = c[ 5 + rr * 3 + cc ];
					// A negative placed on top of a confirmed cell would teach the model that a real cell
					// is background, which is the most expensive thing this pass could get wrong — worse
					// than placing no negative at all. addSpot withdraws it.
					if ( GraphEdits.addSpot( ctx, graph, ref, ( int ) c[ 3 ], p, cov,
							GraphEdits.Reason.TRAINING ) == null )
						continue;
					vertexTags.set( ref, fpTag );
					created++;
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			if ( created > 0 )
			{
				mastodonModel.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return created;
		}
		finally
		{
			graph.releaseRef( ref );
		}
	}

	private static double mean( final List< Double > xs )
	{
		if ( xs.isEmpty() )
			return Double.NaN;
		double s = 0;
		for ( final double x : xs )
			s += x;
		return s / xs.size();
	}

	private static double std( final List< Double > xs, final double mean )
	{
		if ( xs.size() < 2 || Double.isNaN( mean ) )
			return 0.0;
		double s = 0;
		for ( final double x : xs )
			s += ( x - mean ) * ( x - mean );
		return Math.sqrt( s / xs.size() );
	}

	static int refitAnisotropicSpots(
			final ToolContext ctx,
			final int timepoint,
			final double threshold )
	{
		final ModelGraph graph = ctx.getGraph();

		// Collect positions of anisotropic spots (under read lock)
		final List< double[] > positions = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				if ( ProtectedSpots.isProtected( ctx, ctx.getModel(), spot ) )
					continue; // never reshaped — its confirmed ellipsoid is left exactly as curated
				final double[][] cov = new double[ 3 ][ 3 ];
				spot.getCovariance( cov );
				final double maxDiag = Math.max( cov[ 0 ][ 0 ], Math.max( cov[ 1 ][ 1 ], cov[ 2 ][ 2 ] ) );
				final double minDiag = Math.min( cov[ 0 ][ 0 ], Math.min( cov[ 1 ][ 1 ], cov[ 2 ][ 2 ] ) );
				if ( minDiag > 0 && maxDiag / minDiag > threshold )
				{
					final double[] pos = new double[ 3 ];
					spot.localize( pos );
					positions.add( pos );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( positions.isEmpty() )
			return 0;

		// Refit each anisotropic spot via SpotRefitTool
		final SpotRefitTool refitTool = new SpotRefitTool();
		int count = 0;
		for ( final double[] pos : positions )
		{
			final Map< String, Object > params = new HashMap<>();
			params.put( "x", pos[ 0 ] );
			params.put( "y", pos[ 1 ] );
			params.put( "z", pos[ 2 ] );
			params.put( "t", timepoint );
			try
			{
				final String result = refitTool.execute( ctx, params );
				if ( !result.startsWith( "spot not found" ) && !result.startsWith( "ELEPHANT" )
						&& !result.startsWith( "Failed" ) )
					count++;
			}
			catch ( final Exception e )
			{
				log.warn( "t={} — refit failed at ({}, {}, {}): {}", timepoint,
						pos[ 0 ], pos[ 1 ], pos[ 2 ], e.getMessage() );
			}
		}
		return count;
	}

	// -----------------------------------------------------------------------

	// -----------------------------------------------------------------------

	/**
	 * Over-segmentation guard used by {@link #mergeDuplicateSpots}: two spots at the SAME timepoint close
	 * enough to be geometric merge candidates are fragments of ONE cell only if the image stays bright
	 * BETWEEN them — a single continuous blob. If the midpoint is a dark valley they are two distinct
	 * touching (or nearby) cells and must NOT be merged. Mirrors {@code ElephantTool}'s NMS dedup valley
	 * test ({@code NMS_VALLEY_KEEP_RATIO}) so the two over-segmentation checks in the pipeline agree.
	 * (Cross-time convergences are never merged at all — see {@link #pruneConvergenceLinks}.)
	 */



	/** Past this, two detections are two cells whatever the pixels between them look like. */
	private static final double SEPARATION_TOO_FAR = 2.2;



	/**
	 * Default {@link #SHARED_DUPLICATE_OVERLAP_FACTOR}: a pair counts as fragments of one cell while
	 * their centres are closer than this multiple of the LARGER radius.
	 *
	 * <p>The criterion used to be {@code dist < rA + rB} — the two spheres merely touching. For spots
	 * whose radii are correct that is not over-segmentation, it is the geometry of two ADJACENT CELLS:
	 * with 17-21 µm cells it paired every neighbour within ~18 µm, and a pair always loses one member,
	 * so cells in a confluent cluster were deleted one per pair, over and over. Debris was never touched
	 * by the same pass, having no neighbour to pair with — the pass selected against exactly the large,
	 * clustered cells and for the isolated specks. Two fragments of ONE cell sit far closer than a full
	 * diameter apart: both centres are inside the cell.</p>
	 */
	public static final double DUPLICATE_OVERLAP_FACTOR_DEFAULT = SEPARATION_TOO_FAR;
	// 1.0, not the old 0.7 and not the 1.82 that the reported over-segmentation would need. Measured on
	// the same ground truth: no two genuinely different cells in 92 frames come closer than 1.10 r, and
	// a gate of 1.0 r admits 4 distinct-cell pairs out of 9741 neighbour pairs (0.04%) for the valley
	// test to arbitrate. At 0.70 it admitted ZERO — safe, and useless. At 1.82 it admits 5.5%, and with
	// the valley test fusing 22% of what it is given that destroys ~1.2% of neighbour pairs per frame,
	// which on a dividing culture is a real cell deleted every few frames.

	/** {@link ToolContext#getSharedData} key overriding {@link #DUPLICATE_OVERLAP_FACTOR_DEFAULT}. */
	public static final String SHARED_DUPLICATE_OVERLAP_FACTOR = "autocorrect.duplicate_overlap_factor";

	/** The active factor: {@code adaptive_track}'s {@code duplicate_overlap_factor}, else the default. */
	public static double duplicateOverlapFactor( final ToolContext ctx )
	{
		final Object v = ctx.getSharedData( SHARED_DUPLICATE_OVERLAP_FACTOR );
		return v instanceof Number && ( ( Number ) v ).doubleValue() > 0.0
			? ( ( Number ) v ).doubleValue() : DUPLICATE_OVERLAP_FACTOR_DEFAULT;
	}

	/**
	 * Whether two same-timepoint spots are close enough to be fragments of ONE cell, as opposed to two
	 * distinct cells that happen to be adjacent.
	 *
	 * <p>Measured against the LARGER radius, not the SUM: a sum grows with both spots, so a pair of big
	 * cells got a MORE permissive test than a pair of small ones — backwards, since a big cell's
	 * fragments are no further apart in proportion to its size, while adjacent big cells are.</p>
	 *
	 * @param dist2  squared centre-to-centre distance
	 * @param factor multiple of the larger radius; see {@link #DUPLICATE_OVERLAP_FACTOR_DEFAULT}
	 */
	public static boolean overlapsAsFragments( final double dist2, final double rA, final double rB,
		final double factor )
	{
		final double reach = factor * Math.max( rA, rB );
		return dist2 < reach * reach;
	}

	/**
	 * {@code true} if there is no dark intensity valley between two spot positions at {@code timepoint}
	 * — i.e. they read as one continuous bright blob rather than two distinct cells with a dim gap
	 * between them. When core intensity is unavailable ({@code minCore <= 0}, e.g. no image data at this
	 * position) this conservatively returns {@code true} so the geometric distance test alone still
	 * governs, unchanged from before this guard existed.
	 */
	/**
	 * Largest eigenvalue of a symmetric positive-definite 3x3 covariance, i.e. the square of the
	 * ellipsoid's longest semi-axis.
	 *
	 * <p>Power iteration, which converges quickly here and needs no eigen-decomposition. The naive
	 * alternative — the largest diagonal entry — is only correct when the ellipsoid happens to be
	 * axis-aligned, and is what let a tilted merge grow 27% past its cap.</p>
	 */
	public static double largestEigenvalue( final double[][] cov )
	{
		double[] v = { 1.0, 1.0, 1.0 };
		for ( int it = 0; it < 64; it++ )
		{
			final double[] w = new double[ 3 ];
			for ( int i = 0; i < 3; i++ )
			{
				for ( int j = 0; j < 3; j++ )
					w[ i ] += cov[ i ][ j ] * v[ j ];
			}
			final double n = Math.sqrt( w[ 0 ] * w[ 0 ] + w[ 1 ] * w[ 1 ] + w[ 2 ] * w[ 2 ] );
			if ( !( n > 0 ) )
				return Math.max( cov[ 0 ][ 0 ], Math.max( cov[ 1 ][ 1 ], cov[ 2 ][ 2 ] ) );
			for ( int i = 0; i < 3; i++ )
				v[ i ] = w[ i ] / n;
		}
		final double[] w = new double[ 3 ];
		for ( int i = 0; i < 3; i++ )
		{
			for ( int j = 0; j < 3; j++ )
				w[ i ] += cov[ i ][ j ] * v[ j ];
		}
		double lambda = 0.0;
		for ( int i = 0; i < 3; i++ )
			lambda += w[ i ] * v[ i ];
		return Math.max( lambda, 0.0 );
	}

	/**
	 * The ellipsoid that covers two detections found to be one cell.
	 *
	 * <p>Resolving such a pair by deleting one member leaves a spot that is off the cell's centre and
	 * smaller than the cell — the two detections tile it, so neither is it. What the cell is, is the
	 * pair together.</p>
	 *
	 * <p>Semi-axis along the line joining them is {@code d/2 + } the larger of their own extents in
	 * that direction, so the result reaches from one far edge to the other and no further. Across it,
	 * the larger of their extents. Each spot's extent is measured in the direction being asked about
	 * ({@code sqrt(uᵀ C u)}), so an elongated detection contributes its length along the pair and its
	 * width across it rather than an averaged radius.</p>
	 *
	 * <p>Bounded by construction: the merge only fires out to {@code SEPARATION_TOO_FAR}, so the
	 * result never exceeds about 2.1 times the larger input. The strategy this replaces used
	 * {@code Σ Ci + 2 Σ (ci−mean)(ci−mean)ᵀ}, which inflates even two IDENTICAL coincident spots by
	 * 41% and two at 1.85 r by a fifth — that is why it ballooned in dense frames and was left
	 * switched off rather than fixed.</p>
	 *
	 * @param centreOut receives the merged centre, the midpoint of the two
	 * @return the merged covariance
	 */
	public static double[][] coveringEllipsoid( final double[] pA, final double[][] cA,
			final double[] pB, final double[][] cB, final double[] centreOut )
	{
		final double[] u = new double[ 3 ];
		double d = 0;
		for ( int i = 0; i < 3; i++ )
		{
			u[ i ] = pB[ i ] - pA[ i ];
			d += u[ i ] * u[ i ];
			centreOut[ i ] = ( pA[ i ] + pB[ i ] ) / 2.0;
		}
		d = Math.sqrt( d );
		if ( !( d > 1e-9 ) )
		{
			// Coincident centres: the cover is simply the larger of the two, with no inflation at all.
			return traceOf( cA ) >= traceOf( cB ) ? copyOf( cA ) : copyOf( cB );
		}
		for ( int i = 0; i < 3; i++ )
			u[ i ] /= d;
		final double[] v = anyPerpendicular( u );
		final double[] w = cross( u, v );
		final double a = d / 2.0 + Math.max( extentAlong( cA, u ), extentAlong( cB, u ) );
		final double b = Math.max( extentAlong( cA, v ), extentAlong( cB, v ) );
		final double c = Math.max( extentAlong( cA, w ), extentAlong( cB, w ) );
		final double[][] out = new double[ 3 ][ 3 ];
		for ( int i = 0; i < 3; i++ )
			for ( int j = 0; j < 3; j++ )
				out[ i ][ j ] = a * a * u[ i ] * u[ j ] + b * b * v[ i ] * v[ j ] + c * c * w[ i ] * w[ j ];
		return out;
	}

	/** A spot's half-extent in direction {@code u}: {@code sqrt(uᵀ C u)}. */
	private static double extentAlong( final double[][] cov, final double[] u )
	{
		double q = 0;
		for ( int i = 0; i < 3; i++ )
			for ( int j = 0; j < 3; j++ )
				q += u[ i ] * cov[ i ][ j ] * u[ j ];
		return Math.sqrt( Math.max( 0.0, q ) );
	}

	private static double traceOf( final double[][] c )
	{
		return c[ 0 ][ 0 ] + c[ 1 ][ 1 ] + c[ 2 ][ 2 ];
	}

	private static double[][] copyOf( final double[][] c )
	{
		final double[][] out = new double[ 3 ][ 3 ];
		for ( int i = 0; i < 3; i++ )
			out[ i ] = c[ i ].clone();
		return out;
	}

	private static double[] anyPerpendicular( final double[] u )
	{
		final double[] seed = Math.abs( u[ 0 ] ) < 0.9 ? new double[] { 1, 0, 0 } : new double[] { 0, 1, 0 };
		final double[] v = cross( u, seed );
		double n = Math.sqrt( v[ 0 ] * v[ 0 ] + v[ 1 ] * v[ 1 ] + v[ 2 ] * v[ 2 ] );
		if ( !( n > 1e-12 ) )
			return new double[] { 0, 0, 1 };
		for ( int i = 0; i < 3; i++ )
			v[ i ] /= n;
		return v;
	}

	private static double[] cross( final double[] a, final double[] b )
	{
		return new double[] {
				a[ 1 ] * b[ 2 ] - a[ 2 ] * b[ 1 ],
				a[ 2 ] * b[ 0 ] - a[ 0 ] * b[ 2 ],
				a[ 0 ] * b[ 1 ] - a[ 1 ] * b[ 0 ] };
	}


	public static boolean isSameBlob( final ToolContext ctx, final int timepoint,
			final double[] pi, final double[] pj, final double coreI, final double coreJ,
			final double separationInRadii )
	{
		// Beyond this the pair is not a candidate at all, whatever the pixels say.
		if ( !( separationInRadii < SEPARATION_TOO_FAR ) )
			return false;

		// This used to ask whether the intensity dips along the straight segment joining the two
		// centres. Measured against CTC silver truth (benchmark/seed-to-object), that merged 9.0% of
		// the pairs it exists to merge while still wrongly merging 11.1% of genuinely distinct ones —
		// it was not a weak test, it was an inert one, which is why one cell detected twice survived
		// every round of tuning.
		//
		// The straight segment is the wrong path. Two lobes of one cell are joined by whatever path
		// the cell's shape provides, and the segment crosses background the cell goes around. Asking
		// instead whether the two seeds land in the same SEGMENTED OBJECT merges 85.5% at 6.8% wrong —
		// nine times the recall for fewer mistakes. See SeedToObject for why it is a watershed and not
		// merely connectivity: thresholding alone fuses touching cells 59–80% of the time.
		final double dist = Math.sqrt( ( pi[ 0 ] - pj[ 0 ] ) * ( pi[ 0 ] - pj[ 0 ] )
				+ ( pi[ 1 ] - pj[ 1 ] ) * ( pi[ 1 ] - pj[ 1 ] )
				+ ( pi[ 2 ] - pj[ 2 ] ) * ( pi[ 2 ] - pj[ 2 ] ) );
		final double radius = separationInRadii > 1e-9 ? dist / separationInRadii : dist;
		if ( !( radius > 0 ) )
			return false;

		final BackgroundStats stats = estimateBackgroundStats( ctx, timepoint );
		final Sampler sampler = sharedSampler( ctx, timepoint );
		if ( stats == null || sampler == null || !( stats.intensityStd > 0 ) )
			return false; // nothing measurable, and the unknown case must not delete a cell

		// Window geometry in units of the cell radius, so it is the same shape the benchmark measured
		// whatever the voxel size: 48 samples a side, one step every r/6.5, i.e. +/-3.7 r.
		final int side = SeedToObject.WINDOW_SIDE;
		final double step = radius / SEED_WINDOW_STEPS_PER_RADIUS;
		final double[] centre = {
				( pi[ 0 ] + pj[ 0 ] ) / 2.0, ( pi[ 1 ] + pj[ 1 ] ) / 2.0, ( pi[ 2 ] + pj[ 2 ] ) / 2.0 };
		final double[] window = new double[ side * side ];
		final double[] probe = new double[ 3 ];
		try
		{
			for ( int gy = 0; gy < side; gy++ )
			{
				for ( int gx = 0; gx < side; gx++ )
				{
					probe[ 0 ] = centre[ 0 ] + ( gx - side / 2.0 ) * step;
					probe[ 1 ] = centre[ 1 ] + ( gy - side / 2.0 ) * step;
					probe[ 2 ] = centre[ 2 ];
					window[ gy * side + gx ] = sampler.at( probe );
				}
			}
		}
		catch ( final RuntimeException e )
		{
			log.debug( "seed-to-object window unavailable at t={}", timepoint, e );
			return false;
		}

		final int ax = ( int ) Math.round( ( pi[ 0 ] - centre[ 0 ] ) / step + side / 2.0 );
		final int ay = ( int ) Math.round( ( pi[ 1 ] - centre[ 1 ] ) / step + side / 2.0 );
		final int bx = ( int ) Math.round( ( pj[ 0 ] - centre[ 0 ] ) / step + side / 2.0 );
		final int by = ( int ) Math.round( ( pj[ 1 ] - centre[ 1 ] ) / step + side / 2.0 );
		final double level =
				stats.intensityMean + SeedToObject.OBJECT_LEVEL_SIGMA * stats.intensityStd;
		final Boolean verdict = SeedToObject.sameObject( window, side, side, level, ax, ay, bx, by );
		// An abstention must not merge: merging replaces two spots with one, and the unknown case
		// cannot be allowed to delete a cell.
		return Boolean.TRUE.equals( verdict );
	}

	/**
	 * Samples per cell radius when reading the window for {@link SeedToObject}.
	 *
	 * <p>The benchmark sampled every 2 px on cells of median radius 12.9 px, so one step every
	 * {@code r/6.5} reproduces that geometry on any voxel size. Stride matters: measured, every 2 px
	 * merges 86.1% of one-cell pairs and every 3 px only 75.8%, at the same error.</p>
	 */
	private static final double SEED_WINDOW_STEPS_PER_RADIUS = 6.5;

	/**
	 * Merges duplicate spots at a single timepoint.
	 *
	 * <p>Two spots are considered duplicates when the Euclidean distance between
	 * their centres is less than {@code overlapRatio × max(r1, r2)} AND there is no dark intensity
	 * valley between them (see {@link #isSameBlob}) — otherwise they are two distinct, closely-spaced
	 * cells (e.g. fresh division daughters) and must not be fused. Overlapping
	 * spots are grouped transitively via union-find, and each group goes to
	 * {@link #mergeIntoCoveringEllipsoid}.</p>
	 *
	 * <p>There used to be a second strategy here, selected by a setting, that kept the largest member
	 * and deleted the rest. It is gone: a group is one cell tiled by several detections, so no member
	 * of it is the cell, and keeping one was never the right answer — it was only the safer-looking one
	 * while the covering formula was the ballooning {@code Σ Cᵢ + 2·Σ (cᵢ − c)(cᵢ − c)ᵀ}.</p>
	 */
	static int mergeDuplicateSpots(
			final ToolContext ctx,
			final int timepoint,
			final double overlapRatio )
	{
		// One switch for every merge pass — see CleanupToggles.duplicateResolutionEnabled. This one was
		// never behind it, so turning "duplicate resolution" off still left it merging.
		if ( !CleanupToggles.duplicateResolutionEnabled( ctx ) )
			return 0;
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();

		// Phase 1: collect spots at this timepoint (read lock)
		final List< Integer > poolIndices = new ArrayList<>();
		final List< double[] > positions = new ArrayList<>();
		final List< Double > radii = new ArrayList<>();

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				if ( spot.getTimepoint() != timepoint )
					continue;
				if ( ProtectedSpots.isProtected( ctx, model, spot ) )
					continue; // never merged away — a fresh duplicate near it is already rejected upstream
				final double[] pos = new double[ 3 ];
				spot.localize( pos );
				poolIndices.add( spot.getInternalPoolIndex() );
				positions.add( pos );
				radii.add( Math.sqrt( spot.getBoundingSphereRadiusSquared() ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		final int n = poolIndices.size();
		if ( n < 2 )
			return 0;

		// Core intensity per spot, for the over-segmentation valley test below (0 if unavailable).
		final double[] coreI = new double[ n ];
		for ( int i = 0; i < n; i++ )
			coreI[ i ] = sampleIntensity( ctx, timepoint, positions.get( i ) );

		// Phase 2: union-find to build transitive merge groups
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
				final double rj = radii.get( j );
				double distSq = 0;
				for ( int d = 0; d < 3; d++ )
				{
					final double diff = pi[ d ] - pj[ d ];
					distSq += diff * diff;
				}
				final double sepRadii = Math.sqrt( distSq ) / Math.max( 1e-9, Math.max( ri, rj ) );
				if ( sepRadii < overlapRatio
						&& isSameBlob( ctx, timepoint, pi, pj, coreI[ i ], coreI[ j ], sepRadii ) )
					ufUnion( parent, i, j );
			}
		}

		// Collect groups of size >= 2
		final Map< Integer, List< Integer > > groupMap = new HashMap<>();
		for ( int i = 0; i < n; i++ )
			groupMap.computeIfAbsent( ufFind( parent, i ), k -> new ArrayList<>() ).add( i );

		final List< List< Integer > > mergeGroups = new ArrayList<>();
		for ( final List< Integer > g : groupMap.values() )
		{
			if ( g.size() >= 2 )
				mergeGroups.add( g );
		}

		if ( mergeGroups.isEmpty() )
			return 0;

		// Phase 3: replace each group with one spot covering it (write lock)
		int mergeCount = 0;
		final Set< Integer > created = new HashSet<>();
		graph.getLock().writeLock().lock();
		try
		{
			for ( final List< Integer > group : mergeGroups )
			{
				final Set< Integer > groupSet = new HashSet<>();
				for ( final int idx : group )
					groupSet.add( poolIndices.get( idx ) );
				if ( mergeIntoCoveringEllipsoid( ctx, graph, timepoint, groupSet, created ) > 0 )
					mergeCount++;
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}

		if ( mergeCount > 0 )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		return mergeCount;
	}

	/**
	 * Adds incoming edges from {@code srcPoolIndices} and outgoing edges to
	 * {@code tgtPoolIndices} to {@code target}.  Pool-index lookups are O(n)
	 * scans; must be called while the write lock is held.
	 *
	 * @param skipSelf if {@code true}, skip any src/tgt whose pool index equals
	 *                 {@code target}'s own pool index (prevents self-loops)
	 */
	private static void reconnectLinks(
			final ToolContext ctx,
			final ModelGraph graph,
			final Spot target,
			final Set< Integer > srcPoolIndices,
			final Set< Integer > tgtPoolIndices,
			final Spot nbRef,
			final Link edgeRef,
			final boolean skipSelf )
	{
		final int targetId = target.getInternalPoolIndex();
		for ( final int src : srcPoolIndices )
		{
			if ( skipSelf && src == targetId )
				continue;
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getInternalPoolIndex() == src )
				{
					nbRef.refTo( s );
					GraphEdits.addLink( ctx, graph, nbRef, target, edgeRef, GraphEdits.Reason.TOPOLOGY );
					break;
				}
			}
		}
		for ( final int tgt : tgtPoolIndices )
		{
			if ( skipSelf && tgt == targetId )
				continue;
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getInternalPoolIndex() == tgt )
				{
					nbRef.refTo( s );
					GraphEdits.addLink( ctx, graph, target, nbRef, edgeRef, GraphEdits.Reason.TOPOLOGY );
					break;
				}
			}
		}
	}

	/**
	 * Largest the merged ellipsoid may be, as a multiple of the largest member's radius, before the
	 * merge is abandoned. A merge that would produce something this much bigger than anything that went
	 * into it is not describing one cell.
	 */
	public static final double MERGE_MAX_RADIUS_FACTOR = 3.0;

	/**
	 * Most detections that may be replaced by one spot.
	 *
	 * <p>Union-find groups the merged pairs transitively, which is only sound if the arbiter is
	 * exact. It is 85.5%/6.8%, so A~B and B~C does not make A~C — and the groups of three are where
	 * the over-merging lives. Measured on CTC silver truth: allowing three removes 89.9% of the
	 * duplicates and swallows more than one real cell in 13.2% of groups; stopping at two removes
	 * 81.9% and swallows in 9.0%. Eight points of duplicate removal for a third of the over-merging.
	 *
	 * <p>Transitivity itself was NOT the cause of the very large merged spots reported — measured,
	 * requiring every pair in a group to agree gives byte-identical results, and groups never exceed
	 * three. The size came from the cap reading the covariance diagonal; see
	 * {@link #largestEigenvalue}.</p>
	 */
	public static final int MERGE_MAX_GROUP = 2;

	/**
	 * Replaces a group of spots at one timepoint with a single spot whose ellipsoid covers all of them,
	 * reconnecting every link that pointed into or out of the group.
	 *
	 * <p>This is the ONE way this codebase resolves a duplicate. Every pass that finds two detections
	 * on one cell — within-frame NMS, the overlap/valley test, the persistent co-located rule — ends
	 * here, because deleting one member of the pair is wrong in the same way at all of them: the two
	 * detections TILE the cell, so neither of them is the cell, and whichever survives is off centre
	 * and too small. Four passes used to decide this independently (three by dropping a member, one by
	 * covering), which is how the covering rule came to be written once and applied nowhere that
	 * mattered.</p>
	 *
	 * <p>The caller must hold the write lock. Protected spots must already be excluded from
	 * {@code groupPoolIndices} by the caller — this method does not re-check, and would otherwise
	 * delete a human-confirmed spot.</p>
	 *
	 * @param groupPoolIndices internal pool indices of the members, all at {@code timepoint}
	 * @return net spots removed (members minus the one that replaces them), or 0 if nothing was merged
	 */
	public static int mergeIntoCoveringEllipsoid(
			final ToolContext ctx,
			final ModelGraph graph,
			final int timepoint,
			final Set< Integer > groupPoolIndices )
	{
		return mergeIntoCoveringEllipsoid( ctx, graph, timepoint, groupPoolIndices, null );
	}

	/**
	 * As {@link #mergeIntoCoveringEllipsoid(ToolContext, ModelGraph, int, Set)}, for a caller that
	 * merges several groups at one timepoint under one write lock.
	 *
	 * <p>{@code created} accumulates the pool indices of the spots this method adds. A deletion frees
	 * its pool index for immediate re-use, so without this a later group's recorded index can name a
	 * spot that a previous merge just created, and that merged spot would be pulled into a second
	 * merge. Members listed in {@code created} are left alone.</p>
	 */
	public static int mergeIntoCoveringEllipsoid(
			final ToolContext ctx,
			final ModelGraph graph,
			final int timepoint,
			final Set< Integer > groupPoolIndices,
			final Set< Integer > created )
	{
		if ( groupPoolIndices == null || groupPoolIndices.size() < 2 )
			return 0;

		final List< Spot > members = new ArrayList<>();
		final List< double[] > positions = new ArrayList<>();
		final List< double[][] > covs = new ArrayList<>();
		final List< Double > radii = new ArrayList<>();
		final Set< Integer > incomingSrc = new HashSet<>();
		final Set< Integer > outgoingTgt = new HashSet<>();

		final Spot spotRef = graph.vertexRef();
		final Spot newRef = graph.vertexRef();
		final Spot nbRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint )
					continue;
				if ( !groupPoolIndices.contains( s.getInternalPoolIndex() ) )
					continue;
				if ( created != null && created.contains( s.getInternalPoolIndex() ) )
					continue;
				if ( ProtectedSpots.isProtected( ctx, ctx.getModel(), s ) )
					continue; // a confirmed cell is never merged away; GraphEdits would refuse the
				              // removal anyway, and the merge would then leave a duplicate behind
				final Spot member = graph.vertexRef();
				member.refTo( s );
				members.add( member );
				final double[] pos = new double[ 3 ];
				s.localize( pos );
				positions.add( pos );
				final double[][] cov = new double[ 3 ][ 3 ];
				s.getCovariance( cov );
				covs.add( cov );
				radii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
				spotRef.refTo( s );
				for ( final Link e : spotRef.incomingEdges() )
				{
					final int src = e.getSource( nbRef ).getInternalPoolIndex();
					if ( !groupPoolIndices.contains( src ) )
						incomingSrc.add( src );
				}
				for ( final Link e : spotRef.outgoingEdges() )
				{
					final int tgt = e.getTarget( nbRef ).getInternalPoolIndex();
					if ( !groupPoolIndices.contains( tgt ) )
						outgoingTgt.add( tgt );
				}
			}

			if ( members.size() < 2 )
				return 0;
			if ( members.size() > MERGE_MAX_GROUP )
				return 0;

			// Fold the members into one ellipsoid, pairwise. coveringEllipsoid reaches from one far
			// edge to the other and no further, so the fold stays bounded by the members' own extent.
			final double[] newPos = new double[ 3 ];
			System.arraycopy( positions.get( 0 ), 0, newPos, 0, 3 );
			double[][] newCov = covs.get( 0 );
			final double[] merged = new double[ 3 ];
			for ( int i = 1; i < members.size(); i++ )
			{
				newCov = coveringEllipsoid( newPos, newCov, positions.get( i ), covs.get( i ), merged );
				System.arraycopy( merged, 0, newPos, 0, 3 );
			}

			double maxSrcR = 0.0;
			for ( final double r : radii )
				maxSrcR = Math.max( maxSrcR, r );
			// The LARGEST SEMI-AXIS, not the largest diagonal entry of the covariance. For an
			// axis-aligned ellipsoid those coincide; for a tilted one the diagonal entries are
			// mixtures of the semi-axes and read LOW, so the cap silently let tilted merges through
			// oversized — worst exactly on the diagonal, measured at 27% too permissive for a pair
			// joined at 45 degrees and 0% at 0 and 90 degrees. That is why the over-merged spots
			// reported were the diagonal ones while the vertical pairs looked correctly sized.
			final double newR = Math.sqrt( largestEigenvalue( newCov ) );
			if ( newR > MERGE_MAX_RADIUS_FACTOR * maxSrcR )
				return 0;

			// Add the covering spot BEFORE deleting the members, because a merge is a replacement and
			// must look like one to the guards in GraphEdits. Deleting first walks the timepoint down
			// through the count the covering spot is about to restore: with two spots on one cell in a
			// frame, the second removal is refused as the frame's last spot, and the merge then ADDS a
			// third spot beside the survivor it failed to remove — a duplicate manufactured by the pass
			// that exists to remove them. Measured: 16 frames, 0 spots removed, 16 merges reported.
			//
			// If the covering spot would land on a protected cell, GraphEdits withdraws it; nothing is
			// then deleted either, because that position is already represented by the confirmed spot.
			if ( GraphEdits.addSpot( ctx, graph, newRef, timepoint, newPos, newCov,
					GraphEdits.Reason.DUPLICATE ) == null )
				return 0;
			if ( created != null )
				created.add( newRef.getInternalPoolIndex() );

			int deleted = 0;
			for ( final Spot s : members )
			{
				if ( GraphEdits.removeSpot( ctx, graph, s, GraphEdits.Reason.DUPLICATE ) )
					deleted++;
			}
			reconnectLinks( ctx, graph, newRef, incomingSrc, outgoingTgt, nbRef, edgeRef, false );
			return deleted - 1;
		}
		finally
		{
			for ( final Spot s : members )
				graph.releaseRef( s );
			graph.releaseRef( spotRef );
			graph.releaseRef( newRef );
			graph.releaseRef( nbRef );
			graph.releaseRef( edgeRef );
		}
	}

	// Union-Find helpers used by the duplicate-group passes

	public static int ufFind( final int[] parent, int x )
	{
		while ( parent[ x ] != x )
		{
			parent[ x ] = parent[ parent[ x ] ]; // path halving
			x = parent[ x ];
		}
		return x;
	}

	public static void ufUnion( final int[] parent, final int a, final int b )
	{
		final int ra = ufFind( parent, a );
		final int rb = ufFind( parent, b );
		if ( ra != rb )
			parent[ ra ] = rb;
	}

	// -----------------------------------------------------------------------

	// -----------------------------------------------------------------------

	/**
	 * Deletes dangling-end tracklets at {@code timepoint} that are shorter than
	 * {@code minLength} spots.
	 *
	 * <p>A "dangling end" is a spot at {@code timepoint} with no outgoing edge.
	 * This method traces each dangling end backward through incoming edges,
	 * counting only linear-chain spots (predecessors with exactly one outgoing
	 * edge). If the resulting chain length is less than {@code minLength}, every
	 * spot in that chain is removed from the graph.</p>
	 *
	 * <p>Branching points (spots with more than one outgoing edge) mark the
	 * boundary of the chain — they are never deleted, and spots upstream of them
	 * are not counted or removed.</p>
	 */
	static int deleteShortDanglingTracklets(
			final ToolContext ctx,
			final int timepoint,
			final int minLength )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();

		// Phase 1: collect chains to delete (read lock)
		final List< List< Integer > > chainsToDelete = new ArrayList<>();
		final Spot cursorRef = graph.vertexRef();

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint || !s.outgoingEdges().isEmpty() )
					continue;

				if ( ProtectedSpots.isProtected( ctx, model, s ) )
					continue; // never removed, whatever chain it would otherwise belong to

				// Trace backward along the linear chain
				final List< Integer > chain = new ArrayList<>();
				chain.add( s.getInternalPoolIndex() );
				cursorRef.refTo( s );
				boolean chainProtected = false;

				while ( !cursorRef.incomingEdges().isEmpty() && chain.size() < minLength )
				{
					// Move to the predecessor along the first incoming edge
					for ( final Link inEdge : cursorRef.incomingEdges() )
					{
						inEdge.getSource( cursorRef );
						break;
					}
					// Stop at branching points — they belong to another track
					if ( cursorRef.outgoingEdges().size() > 1 )
						break;
					chain.add( cursorRef.getInternalPoolIndex() );
					chainProtected |= ProtectedSpots.isProtected( ctx, model, cursorRef );
				}

				// A protected spot anywhere in this chain vetoes deleting the WHOLE chain — a partial
				// delete would leave a dangling/incoherent tracklet.
				if ( chain.size() < minLength && !chainProtected )
					chainsToDelete.add( chain );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( cursorRef );
		}

		if ( chainsToDelete.isEmpty() )
			return 0;

		// Phase 2: delete all spots in short chains (write lock)
		final List< Spot > toDelete = new ArrayList<>();
		int deletedCount = 0;

		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final List< Integer > chain : chainsToDelete )
				{
					for ( final int spotId : chain )
					{
						for ( final Spot s : graph.vertices() )
						{
							if ( s.getInternalPoolIndex() == spotId )
							{
								final Spot copy = graph.vertexRef();
								copy.refTo( s );
								toDelete.add( copy );
								break;
							}
						}
					}
				}
				for ( final Spot s : toDelete )
				{
					GraphEdits.removeSpot( ctx, graph, s, GraphEdits.Reason.TOPOLOGY );
					deletedCount++;
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			if ( deletedCount > 0 )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return deletedCount;
		}
		finally
		{
			for ( final Spot s : toDelete )
				graph.releaseRef( s );
		}
	}

	/**
	 * Removes the weaker of two detections on one nucleus at {@code timepoint}: a pair where one's centre lies
	 * inside the other's full ellipsoid. On a stack the detector puts two spots on one nucleus, one above the
	 * other in z; two real nuclei never hold each other's centre. Kept: the higher ELEPHANT quality, else the
	 * brighter at its centre, else the larger. The one kept takes a predecessor or successor it lacks from the
	 * one removed, so the track runs on, and is refitted to the whole nucleus with the 3-D ellipsoid fit (the
	 * user's choice over a merge, 2026-10-23). Nothing is merged (merging stays the user's opt-in).
	 *
	 * <p>The user's choice (2026-10-23). The sandwiched pass used to remove these as a side effect (316 in a
	 * short CE 01 run) along with 964 real cells; judged by each spot's extent toward its neighbour it removes
	 * neither, so this does the duplicate half on purpose.</p>
	 *
	 * @return detections removed
	 */
	static int removeStackedDuplicates( final ToolContext ctx, final int timepoint )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Spot > spots = new ArrayList<>();
		final List< double[] > pos = new ArrayList<>();
		final List< double[][] > inv = new ArrayList<>();
		final List< Double > quality = new ArrayList<>();
		final List< Double > size = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint )
					continue;
				s.getCovariance( cov );
				final double[][] iv = ShapeSplit3D.invert3( cov );
				if ( iv == null )
					continue;
				final double[] p = new double[ 3 ];
				s.localize( p );
				spots.add( graph.vertexRef().refTo( s ) );
				pos.add( p );
				inv.add( iv );
				final java.util.OptionalDouble q = DetectionQuality.get( graph, s );
				quality.add( q.isPresent() ? q.getAsDouble() : Double.NaN );
				size.add( ShapeSplit3D.det3Public( cov ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		try
		{
			final int n = spots.size();
			Sampler sampler = null;
			try
			{
				sampler = n > 1 ? fullResolutionSampler( ctx, timepoint ) : null;
			}
			catch ( final RuntimeException noImage )
			{
				// No viewer (a replay of the graph alone): quality and size decide.
			}
			final boolean[] gone = new boolean[ n ];
			final List< int[] > pairs = new ArrayList<>(); // {removed, kept}
			final double maxSpacing = n >= STACKED_MIN_FOR_SPACING ? STACKED_MAX_SPACING * medianNeighbourDistance( pos )
					: Double.POSITIVE_INFINITY;
			for ( int i = 0; i < n; i++ )
				for ( int j = i + 1; j < n; j++ )
				{
					if ( gone[ i ] || gone[ j ] )
						continue;
					if ( ShapeSplit3D.mahalanobis2Public( pos.get( j ), pos.get( i ), inv.get( i ) ) > 1
							&& ShapeSplit3D.mahalanobis2Public( pos.get( i ), pos.get( j ), inv.get( j ) ) > 1 )
						continue;
					// Two nuclei stacked in z: a detection's z extent is longer than a nucleus's radius, so a
					// real neighbour's centre can lie inside it. Measured on CE 01 t=120..159: the pass's wrong
					// removals were 2.3 um apart, its right ones 0.8-1.2, and no two real nuclei closer than
					// 0.4x the frame's median neighbour distance in 99% of pairs even late in the movie.
					if ( !( Math.sqrt( dist2Full( pos.get( i ), pos.get( j ) ) ) < maxSpacing ) )
						continue;
					final boolean pi = ProtectedSpots.isProtected( ctx, model, spots.get( i ) );
					final boolean pj = ProtectedSpots.isProtected( ctx, model, spots.get( j ) );
					if ( pi && pj )
						continue;
					final int weaker = pi ? j : pj ? i : weaker( i, j, quality, size, pos, sampler );
					gone[ weaker ] = true;
					pairs.add( new int[] { weaker, weaker == i ? j : i } );
				}
			if ( pairs.isEmpty() )
				return 0;
			// The one kept is refitted to the whole nucleus before anything changes (the image is read with no
			// lock): two detections tile a nucleus, so either alone is off centre and too small -- the reason
			// duplicates used to be merged rather than thinned (DuplicateMergeChokepointTest). Started where the
			// pair's middle is, at a radius covering both, and dropped if it would hold a third detection's centre.
			final EllipsoidFit3D.Fit[] refit = new EllipsoidFit3D.Fit[ pairs.size() ];
			try
			{
				final bdv.viewer.Source< ? > src = ctx.requireViewer().state().getSources().get( 0 ).getSpimSource();
				for ( int k = 0; k < pairs.size(); k++ )
				{
					final double[] a = pos.get( pairs.get( k )[ 0 ] ), b = pos.get( pairs.get( k )[ 1 ] );
					final double[] mid = { ( a[ 0 ] + b[ 0 ] ) / 2, ( a[ 1 ] + b[ 1 ] ) / 2, ( a[ 2 ] + b[ 2 ] ) / 2 };
					final double r = Math.sqrt( dist2Full( a, b ) ) / 2 + Math.max( radiusOf( size.get( pairs.get( k )[ 0 ] ) ),
							radiusOf( size.get( pairs.get( k )[ 1 ] ) ) );
					final EllipsoidFit3D.Fit f = fitWholeNucleus( src, timepoint, mid, r );
					if ( f != null && !holdsAnotherCentre( f, pos, pairs.get( k ) ) )
						refit[ k ] = f;
				}
			}
			catch ( final RuntimeException noImage )
			{
				// No viewer (a replay of the graph alone): the kept detection stays as it is.
			}
			int removed = 0;
			final Link edge = graph.edgeRef();
			final Spot other = graph.vertexRef();
			graph.getLock().writeLock().lock();
			try
			{
				for ( int k = 0; k < pairs.size(); k++ )
				{
					final int[] pr = pairs.get( k );
					final Spot dup = spots.get( pr[ 0 ] ), keep = spots.get( pr[ 1 ] );
					if ( GraphWatch.dirty( graph, dup ) || GraphWatch.dirty( graph, keep ) )
						continue;
					// The track runs on through the one kept.
					Spot from = null, to = null;
					if ( keep.incomingEdges().isEmpty() && !dup.incomingEdges().isEmpty() )
						from = dup.incomingEdges().iterator().next().getSource( graph.vertexRef() );
					final List< Spot > tos = new ArrayList<>();
					if ( keep.outgoingEdges().isEmpty() )
						for ( final Link e : dup.outgoingEdges() )
							tos.add( e.getTarget( graph.vertexRef() ) );
					if ( GraphEdits.removeSpot( ctx, graph, dup, GraphEdits.Reason.DUPLICATE ) )
					{
						removed++;
						if ( from != null && from.getTimepoint() < keep.getTimepoint() )
							GraphEdits.addLink( ctx, graph, from, keep, edge, GraphEdits.Reason.TOPOLOGY );
						for ( final Spot t2 : tos )
							if ( t2.getTimepoint() > keep.getTimepoint() && t2.incomingEdges().isEmpty() )
								GraphEdits.addLink( ctx, graph, keep, t2, edge, GraphEdits.Reason.TOPOLOGY );
						if ( refit[ k ] != null )
						{
							keep.setPosition( refit[ k ].centre );
							keep.setCovariance( refit[ k ].covariance );
						}
					}
					if ( from != null )
						graph.releaseRef( from );
					for ( final Spot t2 : tos )
						graph.releaseRef( t2 );
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
				graph.releaseRef( edge );
				graph.releaseRef( other );
			}
			if ( removed > 0 )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return removed;
		}
		finally
		{
			for ( final Spot s : spots )
				graph.releaseRef( s );
		}
	}

	/** Closest two detections may be and still be taken for one nucleus, as a fraction of the frame's median neighbour distance. */
	static final double STACKED_MAX_SPACING = 0.4;

	/** Fewer detections than this in the frame and its median spacing means nothing: the guard is not applied. */
	static final int STACKED_MIN_FOR_SPACING = 10;

	/** The median over {@code pos} of each point's distance to its nearest other point; infinite with fewer than two. */
	static double medianNeighbourDistance( final List< double[] > pos )
	{
		if ( pos.size() < 2 )
			return Double.POSITIVE_INFINITY;
		final double[] nn = new double[ pos.size() ];
		for ( int i = 0; i < pos.size(); i++ )
		{
			double best = Double.POSITIVE_INFINITY;
			for ( int j = 0; j < pos.size(); j++ )
				if ( j != i )
					best = Math.min( best, dist2Full( pos.get( i ), pos.get( j ) ) );
			nn[ i ] = Math.sqrt( best );
		}
		java.util.Arrays.sort( nn );
		return nn[ nn.length / 2 ];
	}

	private static double dist2Full( final double[] a, final double[] b )
	{
		return Math.pow( a[ 0 ] - b[ 0 ], 2 ) + Math.pow( a[ 1 ] - b[ 1 ], 2 ) + Math.pow( a[ 2 ] - b[ 2 ], 2 );
	}

	/** The radius of a sphere with the volume of an ellipsoid whose covariance has determinant {@code det}. */
	private static double radiusOf( final double det )
	{
		return Math.cbrt( Math.sqrt( Math.max( 0, det ) ) );
	}

	@SuppressWarnings( { "unchecked", "rawtypes" } )
	private static EllipsoidFit3D.Fit fitWholeNucleus( final bdv.viewer.Source< ? > source, final int t,
			final double[] centre, final double radius )
	{
		try
		{
			return EllipsoidFit3D.fit( ( bdv.viewer.Source ) source, t, centre, radius );
		}
		catch ( final RuntimeException e )
		{
			return null;
		}
	}

	/** Whether a fit holds the centre of a detection other than the pair it was made for. */
	private static boolean holdsAnotherCentre( final EllipsoidFit3D.Fit f, final List< double[] > pos, final int[] pair )
	{
		final double[][] inv = ShapeSplit3D.invert3( f.covariance );
		if ( inv == null )
			return true;
		for ( int i = 0; i < pos.size(); i++ )
			if ( i != pair[ 0 ] && i != pair[ 1 ] && ShapeSplit3D.mahalanobis2Public( pos.get( i ), f.centre, inv ) <= 1 )
				return true;
		return false;
	}

	/** Which of two duplicates is the weaker: lower quality, else dimmer at its centre, else smaller. */
	private static int weaker( final int i, final int j, final List< Double > quality, final List< Double > size,
			final List< double[] > pos, final Sampler sampler )
	{
		final double qi = quality.get( i ), qj = quality.get( j );
		if ( !Double.isNaN( qi ) && !Double.isNaN( qj ) && qi != qj )
			return qi < qj ? i : j;
		if ( sampler != null )
		{
			final double vi = sampler.at( pos.get( i ) ), vj = sampler.at( pos.get( j ) );
			if ( vi != vj )
				return vi < vj ? i : j;
		}
		return size.get( i ) <= size.get( j ) ? i : j;
	}

	/**
	 * Whether two spots overlap along the line joining their centres: their distance is below {@code factor}
	 * times the sum of each one's radius in that direction (1 / sqrt(u' inv u), from its inverse covariance).
	 */
	static boolean overlapping( final double[] p, final double[][] invP, final double[] q, final double[][] invQ,
			final double factor )
	{
		final double d = Math.sqrt( Math.pow( q[ 0 ] - p[ 0 ], 2 ) + Math.pow( q[ 1 ] - p[ 1 ], 2 ) + Math.pow( q[ 2 ] - p[ 2 ], 2 ) );
		if ( d == 0 )
			return true;
		final double[] u = { ( q[ 0 ] - p[ 0 ] ) / d, ( q[ 1 ] - p[ 1 ] ) / d, ( q[ 2 ] - p[ 2 ] ) / d };
		return d < factor * ( radiusAlong( invP, u ) + radiusAlong( invQ, u ) );
	}

	/** A spot's radius along the unit direction {@code u}, from its inverse covariance. */
	static double radiusAlong( final double[][] inv, final double[] u )
	{
		double q = 0;
		for ( int a = 0; a < 3; a++ )
			for ( int b = 0; b < 3; b++ )
				q += u[ a ] * inv[ a ][ b ] * u[ b ];
		return q > 0 ? 1 / Math.sqrt( q ) : 0;
	}

	/**
	 * Removes "sandwiched" spurious spots at {@code timepoint}: a spot that overlaps two other spots
	 * lying on roughly OPPOSITE sides of it (e.g. a phantom detected between two cells adjacent along
	 * Z). Such a wedged spot is almost never a real cell. Removing it also removes its edges, so the
	 * two true neighbours can be reconnected by the later linking/gap-fill steps.
	 *
	 * @param factor overlap tolerance: A/B count as overlapping the candidate when their centre
	 *               distance is below {@code factor × (rCandidate + rNeighbour)}
	 * @return number of spots removed
	 */
	static int removeSandwichedSpots( final ToolContext ctx, final int timepoint,
			final double factor )
	{
		// Overlap is judged with each spot's extent TOWARD the other, not its bounding sphere (its longest
		// semi-axis). On a stack with coarse z the detections are long in z, and the longest axis made packed
		// nuclei overlap sideways: replayed on Fluo-N3DH-CE 01 t=140..149, 56 of the 90 spots this removed
		// were real cells.
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();

		final List< Integer > ids = new ArrayList<>();
		final List< double[] > pos = new ArrayList<>();
		final List< double[][] > inverses = new ArrayList<>();
		final List< Boolean > protectedFlags = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[][] cov = new double[ 3 ][ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint )
					continue;
				final double[] p = new double[ 3 ];
				s.localize( p );
				s.getCovariance( cov );
				final double[][] inv = ShapeSplit3D.invert3( cov );
				if ( inv == null )
					continue;
				ids.add( s.getInternalPoolIndex() );
				pos.add( p );
				inverses.add( inv );
				protectedFlags.add( ProtectedSpots.isProtected( ctx, model, s ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		final int n = ids.size();
		if ( n < 3 )
			return 0;
		final List< Integer > removeIds = new ArrayList<>();
		for ( int i = 0; i < n; i++ )
		{
			if ( protectedFlags.get( i ) )
				continue; // never removed as a "sandwiched" artifact — it's a confirmed real cell
			final double[] pi = pos.get( i );
			boolean sandwiched = false;
			for ( int a = 0; a < n && !sandwiched; a++ )
			{
				if ( a == i || !overlapping( pi, inverses.get( i ), pos.get( a ), inverses.get( a ), factor ) )
					continue;
				for ( int b = a + 1; b < n; b++ )
				{
					if ( b == i || !overlapping( pi, inverses.get( i ), pos.get( b ), inverses.get( b ), factor ) )
						continue;
					// Require A and B to overlap EACH OTHER too — i.e. i is squeezed into the overlap
					// zone of two already-touching cells (the Z-adjacent phantom case). Real cells in a
					// spaced line have non-overlapping flankers and are therefore NOT removed.
					if ( !overlapping( pos.get( a ), inverses.get( a ), pos.get( b ), inverses.get( b ), factor ) )
						continue;
					// A and B on opposite sides of i: (A-i)·(B-i) < 0.
					double dot = 0;
					for ( int d = 0; d < 3; d++ )
						dot += ( pos.get( a )[ d ] - pi[ d ] ) * ( pos.get( b )[ d ] - pi[ d ] );
					if ( dot < 0 )
					{
						sandwiched = true;
						break;
					}
				}
			}
			if ( sandwiched )
				removeIds.add( ids.get( i ) );
		}
		if ( removeIds.isEmpty() )
			return 0;

		final List< Spot > toRemove = new ArrayList<>();
		int removed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() == timepoint && removeIds.contains( s.getInternalPoolIndex() ) )
				{
					final Spot copy = graph.vertexRef();
					copy.refTo( s );
					toRemove.add( copy );
				}
			}
			// What GraphEdits actually removed, not what was asked of it: it refuses at the cell-count
			// floor, and reporting the request as the result overstates every deleting pass in the log.
			for ( final Spot s : toRemove )
				if ( GraphEdits.removeSpot( ctx, graph, s, GraphEdits.Reason.PROBABLY_NOT_A_CELL ) )
					removed++;
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		for ( final Spot s : toRemove )
			graph.releaseRef( s );
		if ( !toRemove.isEmpty() )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		return removed;
	}

	/** Euclidean distance between two 3-D points. */
	/**
	 * Tags a spot that track surgery had to invent as {@code fn}: a cell the detector missed.
	 *
	 * <p>This is the half of the human loop the pipeline threw away. A gap in a lineage, or a
	 * successor with no predecessor, is resolved by re-detecting locally — and the fact that the model
	 * had NOT found it at full frame is exactly the correction ELEPHANT weights at ten times a
	 * confirmation. It was being added as an ordinary spot, which the wire protocol then sent as
	 * {@code tp}: the same weight as confirming something the model already got right.</p>
	 *
	 * <p>No measurement here. Gap filling can be wrong — it is bounded to a quarter of a timepoint for
	 * that reason — and a wrong {@code fn} teaches background as a cell at ten times weight. The
	 * corroboration is applied where it already is for every other annotation, in
	 * {@code ElephantTool.withholdBelowDetectionGate}, which measures each one against the frame's own
	 * noise floor and demotes what does not stand above it. Measuring here would mean sampling the
	 * image under a graph write lock.</p>
	 */
	private static void tagAsMissedCell( final ToolContext ctx, final Spot spot )
	{
		final Model model = ctx.getModel();
		final TagSet ts = DetectionTagLabels.ensure( model );
		final Tag fn = DetectionTagLabels.fnTag( ts );
		if ( fn != null )
			model.getTagSetModel().getVertexTags().set( spot, fn );
	}

	private static double dist( final double[] a, final double[] b )
	{
		double s = 0;
		for ( int d = 0; d < 3; d++ )
		{
			final double x = a[ d ] - b[ d ];
			s += x * x;
		}
		return Math.sqrt( s );
	}

	/**
	 * Returns {@code true} if a candidate position at {@code timepoint} would be "sandwiched" between
	 * two existing spots on opposite sides (see {@link #removeSandwichedSpots}). Used to avoid
	 * creating a spurious spot during predecessor recovery.
	 */
	private static boolean positionSandwiched( final ToolContext ctx, final int timepoint,
			final double[] p, final double r, final double factor )
	{
		final ModelGraph graph = ctx.getGraph();
		final List< double[] > pos = new ArrayList<>();
		final List< Double > radii = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint )
					continue;
				final double[] q = new double[ 3 ];
				s.localize( q );
				final double d = dist( p, q );
				final double rq = Math.sqrt( s.getBoundingSphereRadiusSquared() );
				if ( d < factor * ( r + rq ) )
				{
					pos.add( q );
					radii.add( rq );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		for ( int a = 0; a < pos.size(); a++ )
			for ( int b = a + 1; b < pos.size(); b++ )
			{
				// Flankers must overlap each other (candidate squeezed into their overlap zone).
				if ( dist( pos.get( a ), pos.get( b ) ) >= factor * ( radii.get( a ) + radii.get( b ) ) )
					continue;
				double dot = 0;
				for ( int d = 0; d < 3; d++ )
					dot += ( pos.get( a )[ d ] - p[ d ] ) * ( pos.get( b )[ d ] - p[ d ] );
				if ( dot < 0 )
					return true;
			}
		return false;
	}

	/**
	 * Backward gap-fill: recovers a missing predecessor at {@code timepoint-1} for spots at
	 * {@code timepoint} that are either orphaned (no incoming edge) or a suspicious division
	 * daughter (single incoming from a mother that already has &ge;2 outgoing edges). It re-detects
	 * the missing cell locally at {@code timepoint-1} via ELEPHANT and relinks, which
	 * <b>dissolves FALSE divisions</b> (a neighbour's mother force-linked to a spot whose real
	 * predecessor was missed) and <b>connects isolated spots</b>.
	 *
	 * <p>Conservative: for a division daughter it only acts when the re-detected cell is clearly a
	 * SEPARATE cell (not the current mother) that is closer to the daughter than the mother is — so
	 * real divisions, where no separate cell exists near the daughter at t-1, are left untouched.</p>
	 *
	 * @return number of predecessors recovered (edges relinked)
	 */
	/**
	 * Safety cap on the number of per-spot region re-predictions ({@code /seg/predict} with a crop box)
	 * that {@link #recoverMissingPredecessors} and {@link #fillGaps} may issue for a single timepoint.
	 * A healthy frame has only a handful of orphans/dangling ends; a count far above this means the
	 * frame is over-detected/fragmented, where per-spot recovery is chasing noise and would flood the
	 * ELEPHANT server with hundreds of requests. In that regime we skip recovery entirely.
	 */
	private static final int MAX_REGION_RECOVERY_CANDIDATES = 64;

	/**
	 * Above this FRACTION of a timepoint's own spots needing recovery, the underlying tracking is too
	 * fragmented for interpolation-based gap-filling ({@link #recoverMissingPredecessors}/
	 * {@link #fillGaps}) to be trustworthy — fabricating that many ELEPHANT-detected positions
	 * destabilizes an already-fragile link structure more than it helps. Gap recovery is meant to patch
	 * the OCCASIONAL missed detection in an otherwise-solid frame, not reconstruct most of one; on a
	 * genuinely sparse/difficult dataset, a large fraction of dangling ends is itself evidence recovery
	 * will misfire more often than it succeeds, so skip the whole timepoint rather than partially
	 * recover (observed: filling 43% of all spots on one dataset collapsed continuity 76%->67% with
	 * isolated spots tripling — the "cure" was worse than the disease). Deliberately a FRACTION, not
	 * just {@link #MAX_REGION_RECOVERY_CANDIDATES}'s absolute count, since a dataset can be sparse
	 * enough that even a handful of candidates is already a large share of that timepoint's population.
	 */
	private static final double MAX_GAP_FILL_FRACTION = 0.25;

	static int recoverMissingPredecessors( final ToolContext ctx, final int timepoint,
			final double cropFactor )
	{
		if ( timepoint == 0 )
			return 0;
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final int prevTp = timepoint - 1;
		final double crop = cropFactor > 0.0 ? cropFactor : 1.5;

		// Phase 1 (read lock): collect candidate spots at t.
		final List< Integer > candIds = new ArrayList<>();
		final List< double[] > candPositions = new ArrayList<>();
		final List< Double > candRadii = new ArrayList<>();
		final List< Integer > motherIds = new ArrayList<>();   // mother pool index, or -1 for pure orphan
		final List< double[] > motherPositions = new ArrayList<>();
		int totalAtTp = 0;

		final Spot srcRef = graph.vertexRef();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint )
					continue;
				totalAtTp++;
				final int nIn = s.incomingEdges().size();
				if ( nIn == 0 )
				{
					final double[] p = new double[ 3 ];
					s.localize( p );
					candIds.add( s.getInternalPoolIndex() );
					candPositions.add( p );
					candRadii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
					motherIds.add( -1 );
					motherPositions.add( null );
				}
				else if ( nIn == 1 )
				{
					final Spot mother = s.incomingEdges().iterator().next().getSource( srcRef );
					if ( mother.outgoingEdges().size() >= 2 )
					{
						final double[] p = new double[ 3 ];
						s.localize( p );
						final double[] mp = new double[ 3 ];
						mother.localize( mp );
						candIds.add( s.getInternalPoolIndex() );
						candPositions.add( p );
						candRadii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
						motherIds.add( mother.getInternalPoolIndex() );
						motherPositions.add( mp );
					}
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( srcRef );
		}

		if ( candIds.isEmpty() )
			return 0;

		// Over-detection guard: one region re-prediction per candidate would flood the server.
		if ( candIds.size() > MAX_REGION_RECOVERY_CANDIDATES )
		{
			log.warn( "t={} — predecessor recovery skipped: {} candidates exceed cap {} "
					+ "(over-detected/fragmented frame; per-spot re-prediction would flood the server).",
					timepoint, candIds.size(), MAX_REGION_RECOVERY_CANDIDATES );
			return 0;
		}
		// Sanity ceiling: if a large FRACTION of this timepoint's own spots need recovery, the
		// underlying tracking is too fragmented for interpolation-based recovery to be trustworthy —
		// fabricating that many ELEPHANT-detected positions destabilizes an already-fragile link
		// structure more than it helps (observed on a sparse dataset: gap-filling touched 43% of all
		// spots and continuity COLLAPSED 76%->67% with isolated spots tripling, rather than improving).
		// Gap recovery is meant to patch the OCCASIONAL missed detection, not reconstruct most of a
		// frame; skip entirely rather than partially recover when the fraction alone says this frame's
		// fragmentation is too severe to trust.
		if ( totalAtTp > 0 && candIds.size() > MAX_GAP_FILL_FRACTION * totalAtTp )
		{
			log.warn( "t={} — predecessor recovery skipped: {} of {} spot(s) ({}%) would need recovery — "
					+ "too fragmented a frame to trust interpolation-based recovery here (ceiling {}%).",
					timepoint, candIds.size(), totalAtTp,
					Math.round( 100.0 * candIds.size() / totalAtTp ), Math.round( 100.0 * MAX_GAP_FILL_FRACTION ) );
			return 0;
		}

		final ElephantIntegration elephant = new ElephantIntegration( ctx );
		if ( !elephant.init() )
		{
			log.warn( "t={} — predecessor recovery skipped: ELEPHANT not available", timepoint );
			return 0;
		}

		// Same sanity check predictWindow already applies to every ordinary detection (see
		// estimateContrastNoiseFloor): this path calls ELEPHANT directly and injects whatever it
		// returns into the graph, so without this an undertrained model's overconfident background
		// re-detection would be accepted here even though the main pipeline would have rejected it.
		final double noiseFloor = estimateContrastNoiseFloor( ctx, prevTp );

		int recovered = 0;
		final Spot candRef = graph.vertexRef();
		final Spot scanRef = graph.vertexRef();
		final Spot newRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();
		try
		{
			for ( int ci = 0; ci < candIds.size(); ci++ )
			{
				final int candId = candIds.get( ci );
				final double[] pos = candPositions.get( ci );
				final double radius = candRadii.get( ci );
				final int motherId = motherIds.get( ci );
				final double[] mpos = motherPositions.get( ci );

				// Local re-detection at t-1 in a crop box around the candidate (no lock during HTTP).
				final double half = radius * crop;
				final double[] cropBox = {
						pos[ 0 ] - half, pos[ 1 ] - half, pos[ 2 ] - half,
						pos[ 0 ] + half, pos[ 1 ] + half, pos[ 2 ] + half
				};
				final List< JSONObject > detected = elephant.predictSpotsInRegion( prevTp, cropBox );
				if ( detected == null || detected.isEmpty() )
					continue;

				// Pick the detection nearest the candidate.
				double[] bestPos = null;
				double[][] bestCov = null;
				double bestDist = Double.MAX_VALUE;
				for ( final JSONObject sj : detected )
				{
					try
					{
						final JSONArray pa = sj.getJSONArray( "pos" );
						final double[] dp = { pa.getDouble( 0 ), pa.getDouble( 1 ), pa.getDouble( 2 ) };
						double dsq = 0;
						for ( int d = 0; d < 3; d++ ) { final double df = dp[ d ] - pos[ d ]; dsq += df * df; }
						final double dist = Math.sqrt( dsq );
						if ( dist < bestDist )
						{
							bestDist = dist;
							bestPos = dp;
							final JSONArray ca = sj.getJSONArray( "covariance" );
							bestCov = new double[ 3 ][ 3 ];
							for ( int r = 0; r < 3; r++ )
								for ( int c = 0; c < 3; c++ )
									bestCov[ r ][ c ] = ca.getDouble( r * 3 + c );
						}
					}
					catch ( final Exception ignore )
					{ /* skip malformed detection */ }
				}
				if ( bestPos == null || bestDist > crop * radius )
					continue;

				// Reject if the re-detected position reads as background noise rather than a real
				// peak/trough — ELEPHANT's own confidence for a single ad-hoc region call is not
				// trustworthy enough on its own (same reasoning as deleteFlatContrastSpots).
				if ( Math.abs( sampleContrast( ctx, prevTp, bestPos, radius ) ) <= noiseFloor )
					continue;

				// Division daughter: only act if the re-detected cell is a SEPARATE cell (not the
				// mother) and closer to the daughter than the mother — otherwise it is a real division.
				if ( motherId >= 0 && mpos != null )
				{
					double dMotherSq = 0, dDetMotherSq = 0;
					for ( int d = 0; d < 3; d++ )
					{
						final double a = pos[ d ] - mpos[ d ]; dMotherSq += a * a;
						final double b = bestPos[ d ] - mpos[ d ]; dDetMotherSq += b * b;
					}
					if ( Math.sqrt( dDetMotherSq ) < radius || bestDist >= Math.sqrt( dMotherSq ) )
						continue;
				}

				// Would creating a new spot here wedge a phantom between two existing t-1 cells?
				// (Checked before the write lock; only blocks CREATION, not reuse of an existing spot.)
				final boolean candSandwiched = positionSandwiched( ctx, prevTp, bestPos, radius, 1.0 );

				// Mutate under write lock.
				graph.getLock().writeLock().lock();
				try
				{
					// Re-locate the candidate.
					boolean found = false;
					for ( final Spot s : graph.vertices() )
					{
						if ( s.getInternalPoolIndex() == candId ) { candRef.refTo( s ); found = true; break; }
					}
					if ( !found )
						continue;

					// Resolve the recovered predecessor: reuse an existing spot at t-1 whose bounding
					// sphere contains the detected centre (avoid duplicates), else create a new spot.
					Spot pred = null;
					for ( final Spot s : graph.vertices() )
					{
						if ( s.getTimepoint() != prevTp )
							continue;
						double dsq = 0;
						for ( int d = 0; d < 3; d++ )
						{
							final double df = s.getDoublePosition( d ) - bestPos[ d ];
							dsq += df * df;
						}
						if ( Math.sqrt( dsq ) < Math.sqrt( s.getBoundingSphereRadiusSquared() ) )
						{ scanRef.refTo( s ); pred = scanRef; break; }
					}
					// A protected spot must not gain a new successor it never had, however plausible the
					// geometry looks — it is only reachable here via the "reuse an existing overlapping
					// spot" fallback above (a newly-created pred can never be protected).
					if ( pred != null && ProtectedSpots.isProtected( ctx, model, pred ) )
						continue;
					// If the resolved predecessor is the current mother, there is nothing to recover.
					if ( pred != null && motherId >= 0 && pred.getInternalPoolIndex() == motherId )
						continue;
					if ( pred == null )
					{
						if ( candSandwiched )
							continue; // would create a phantom wedged between two cells — skip
						// The "reuse" search above only rejects a candidate INSIDE an existing spot's own
						// radius, narrower than the standard sum-of-radii overlap test — a genuine duplicate
						// of a protected spot can still slip through as "no reuse found" and reach here, so
						// addSpot withdraws it.
						if ( GraphEdits.addSpot( ctx, graph, newRef, prevTp, bestPos, bestCov,
								GraphEdits.Reason.TOPOLOGY ) == null )
							continue;
						tagAsMissedCell( ctx, newRef );
						pred = newRef;
					}
					// Never give the recovered predecessor more than the binary-division maximum.
					if ( pred.outgoingEdges().size() >= 2 )
						continue;

					// Remove the candidate's current incoming edge(s) — for a division daughter this
					// is the spurious mother link (dissolving the false division); orphans have none.
					final List< Link > incoming = new ArrayList<>();
					for ( final Link e : candRef.incomingEdges() )
					{
						final Link copy = graph.edgeRef();
						copy.refTo( e );
						incoming.add( copy );
					}
					for ( final Link e : incoming )
					{
						GraphEdits.removeLink( ctx, graph, e, GraphEdits.Reason.TOPOLOGY );
						graph.releaseRef( e );
					}

					// Link recovered predecessor -> candidate.
					GraphEdits.addLink( ctx, graph, pred, candRef, edgeRef, GraphEdits.Reason.TOPOLOGY );
					recovered++;
				}
				finally
				{
					graph.getLock().writeLock().unlock();
				}
			}
		}
		finally
		{
			graph.releaseRef( candRef );
			graph.releaseRef( scanRef );
			graph.releaseRef( newRef );
			graph.releaseRef( edgeRef );
		}
		if ( recovered > 0 )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		return recovered;
	}

	/**
	 * Fills track gaps at {@code timepoint} by running ELEPHANT locally around
	 * each "dangling end" spot (outgoing-edge count = 0 at a non-terminal
	 * timepoint) and linking to the nearest spot found at {@code timepoint + 1}.
	 *
	 * <p>For each dangling end at position P with radius R:</p>
	 * <ol>
	 *   <li>Check for an existing unlinked spot at t+1 within
	 *       {@code cropFactor × R}; if found, link directly.</li>
	 *   <li>Otherwise call ELEPHANT {@code /seg/predict} with a crop box of
	 *       half-width {@code cropFactor × R} centred on P, add the detected
	 *       spots to the graph, and link to the nearest one.</li>
	 * </ol>
	 */
	static int fillGaps(
			final ToolContext ctx,
			final int timepoint,
			final int numTimepoints,
			final double cropFactor )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final int nextTp = timepoint + 1;

		// Phase 1: collect dangling ends at this timepoint (read lock)
		final List< Integer > endIds = new ArrayList<>();
		final List< double[] > endPositions = new ArrayList<>();
		final List< Double > endRadii = new ArrayList<>();
		int totalAtTp = 0;

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint )
					continue;
				totalAtTp++;
				if ( !s.outgoingEdges().isEmpty() )
					continue;
				final double[] pos = new double[ 3 ];
				s.localize( pos );
				endIds.add( s.getInternalPoolIndex() );
				endPositions.add( pos );
				endRadii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( endIds.isEmpty() )
			return 0;

		// Over-detection guard: one region re-prediction per dangling end would flood the server.
		if ( endIds.size() > MAX_REGION_RECOVERY_CANDIDATES )
		{
			log.warn( "t={} — gap fill skipped: {} dangling ends exceed cap {} "
					+ "(over-detected/fragmented frame; per-spot re-prediction would flood the server).",
					timepoint, endIds.size(), MAX_REGION_RECOVERY_CANDIDATES );
			return 0;
		}
		// Sanity ceiling: see MAX_GAP_FILL_FRACTION's doc — a large fraction of dangling ends is itself
		// evidence this frame is too fragmented to trust interpolation-based gap-filling.
		if ( totalAtTp > 0 && endIds.size() > MAX_GAP_FILL_FRACTION * totalAtTp )
		{
			log.warn( "t={} — gap fill skipped: {} of {} spot(s) ({}%) are dangling ends — too "
					+ "fragmented a frame to trust interpolation-based gap-filling here (ceiling {}%).",
					timepoint, endIds.size(), totalAtTp,
					Math.round( 100.0 * endIds.size() / totalAtTp ), Math.round( 100.0 * MAX_GAP_FILL_FRACTION ) );
			return 0;
		}

		final ElephantIntegration elephant = new ElephantIntegration( ctx );
		if ( !elephant.init() )
		{
			log.warn( "t={} — gap fill skipped: ELEPHANT not available", timepoint );
			return 0;
		}

		// Same sanity check predictWindow already applies to every ordinary detection (see
		// estimateContrastNoiseFloor): this path calls ELEPHANT directly and injects whatever it
		// returns into the graph, so without this an undertrained model's overconfident background
		// re-detection would be accepted here even though the main pipeline would have rejected it.
		final double noiseFloor = estimateContrastNoiseFloor( ctx, nextTp );

		int filled = 0;
		final Spot endRef = graph.vertexRef();
		final Spot targetRef = graph.vertexRef();
		final Spot scanRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();

		try
		{
			for ( int di = 0; di < endIds.size(); di++ )
			{
				final int endId = endIds.get( di );
				final double[] pos = endPositions.get( di );
				final double radius = endRadii.get( di );

				// Phase 2a: look for ANY existing spot at t+1 within cropFactor×R (read lock).
				// Linked spots are included — if a cell was already detected there, reuse it.
				Integer existingId = null;
				double existingDist = Double.MAX_VALUE;

				graph.getLock().readLock().lock();
				try
				{
					for ( final Spot s : graph.vertices() )
					{
						if ( s.getTimepoint() != nextTp )
							continue;
						if ( ProtectedSpots.isProtected( ctx, model, s ) )
							continue; // never given a new predecessor it never had
						double distSq = 0;
						for ( int d = 0; d < 3; d++ )
						{
							final double diff = s.getDoublePosition( d ) - pos[ d ];
							distSq += diff * diff;
						}
						final double dist = Math.sqrt( distSq );
						if ( dist < cropFactor * radius && dist < existingDist )
						{
							existingDist = dist;
							existingId = s.getInternalPoolIndex();
						}
					}
				}
				finally
				{
					graph.getLock().readLock().unlock();
				}

				// Phase 2b: if no existing candidate, call ELEPHANT (no lock held during HTTP)
				List< JSONObject > detected = null;
				if ( existingId == null )
				{
					final double half = radius * cropFactor;
					final double[] cropBox = {
							pos[ 0 ] - half, pos[ 1 ] - half, pos[ 2 ] - half,
							pos[ 0 ] + half, pos[ 1 ] + half, pos[ 2 ] + half
					};
					detected = elephant.predictSpotsInRegion( nextTp, cropBox );
					// Reject anything that reads as background noise rather than a real peak/trough —
					// ELEPHANT's own confidence for a single ad-hoc region call is not trustworthy enough
					// on its own (same reasoning as deleteFlatContrastSpots), and this path otherwise
					// injects its result straight into the graph with no further review.
					if ( detected != null )
					{
						final List< JSONObject > filtered = new ArrayList<>();
						for ( final JSONObject sj : detected )
						{
							try
							{
								final JSONArray pa = sj.getJSONArray( "pos" );
								final double[] dp = { pa.getDouble( 0 ), pa.getDouble( 1 ), pa.getDouble( 2 ) };
								if ( Math.abs( sampleContrast( ctx, nextTp, dp, radius ) ) > noiseFloor )
									filtered.add( sj );
							}
							catch ( final Exception ignore )
							{ /* skip malformed detection */ }
						}
						detected = filtered;
					}
				}

				// Phase 2c: write lock — optionally add new spots, then link
				graph.getLock().writeLock().lock();
				try
				{
					// Locate the dangling end (validate it's still unlinked)
					boolean endFound = false;
					for ( final Spot s : graph.vertices() )
					{
						if ( s.getInternalPoolIndex() == endId )
						{
							endRef.refTo( s );
							endFound = true;
							break;
						}
					}
					if ( !endFound || !endRef.outgoingEdges().isEmpty() )
						continue;

					boolean targetFound = false;
					double bestDist = existingId != null ? existingDist : Double.MAX_VALUE;

					// Locate existing candidate under write lock (no edge-count re-check needed)
					if ( existingId != null )
					{
						for ( final Spot s : graph.vertices() )
						{
							if ( s.getInternalPoolIndex() == existingId )
							{
								targetRef.refTo( s );
								targetFound = true;
								break;
							}
						}
					}

					// Add ELEPHANT-detected spots, merging into existing spots when they overlap.
					// "Overlap" = detected centre falls within the bounding sphere of an existing spot.
					if ( detected != null )
					{
						for ( final JSONObject spotJson : detected )
						{
							try
							{
								final JSONArray posArr = spotJson.getJSONArray( "pos" );
								final double[] newPos = {
										posArr.getDouble( 0 ),
										posArr.getDouble( 1 ),
										posArr.getDouble( 2 )
								};
								final JSONArray covArr = spotJson.getJSONArray( "covariance" );
								final double[][] cov = new double[ 3 ][ 3 ];
								for ( int r = 0; r < 3; r++ )
									for ( int c = 0; c < 3; c++ )
										cov[ r ][ c ] = covArr.getDouble( r * 3 + c );

								// Check whether the detected position falls inside any existing
								// spot at t+1 (distance < existing spot's bounding sphere radius).
								// If so, reuse that spot rather than creating a duplicate.
								boolean mergedIntoExisting = false;
								for ( final Spot s : graph.vertices() )
								{
									if ( s.getTimepoint() != nextTp )
										continue;
									if ( ProtectedSpots.isProtected( ctx, model, s ) )
										continue; // never given a new predecessor it never had
									final double rExisting =
											Math.sqrt( s.getBoundingSphereRadiusSquared() );
									double overlapDistSq = 0;
									for ( int d = 0; d < 3; d++ )
									{
										final double diff = s.getDoublePosition( d ) - newPos[ d ];
										overlapDistSq += diff * diff;
									}
									if ( Math.sqrt( overlapDistSq ) < rExisting )
									{
										// Reuse the existing spot as the target candidate
										double distFromEndSq = 0;
										for ( int d = 0; d < 3; d++ )
										{
											final double diff = s.getDoublePosition( d ) - pos[ d ];
											distFromEndSq += diff * diff;
										}
										final double distFromEnd = Math.sqrt( distFromEndSq );
										if ( distFromEnd < bestDist )
										{
											bestDist = distFromEnd;
											targetRef.refTo( s );
											targetFound = true;
										}
										mergedIntoExisting = true;
										break;
									}
								}

								if ( !mergedIntoExisting )
								{
									// No overlap — add as a new spot. The "reuse" search above only rejects a
									// candidate INSIDE an existing spot's own radius, narrower than the standard
									// sum-of-radii overlap test — a genuine duplicate of a protected spot can
									// still slip through as "no overlap found" and reach here.
									if ( GraphEdits.addSpot( ctx, graph, scanRef, nextTp, newPos, cov,
											GraphEdits.Reason.TOPOLOGY ) == null )
										continue;
									tagAsMissedCell( ctx, scanRef );

									double distSq = 0;
									for ( int d = 0; d < 3; d++ )
									{
										final double diff = newPos[ d ] - pos[ d ];
										distSq += diff * diff;
									}
									final double dist = Math.sqrt( distSq );
									if ( dist < bestDist )
									{
										bestDist = dist;
										targetRef.refTo( scanRef );
										targetFound = true;
									}
								}
							}
							catch ( final Exception e )
							{
								log.warn( "t={} — failed to add gap-fill spot: {}", nextTp,
										e.getMessage() );
							}
						}
					}

					if ( targetFound )
					{
						GraphEdits.addLink( ctx, graph, endRef, targetRef, edgeRef, GraphEdits.Reason.TOPOLOGY );
						filled++;
					}
				}
				finally
				{
					graph.getLock().writeLock().unlock();
				}
			}
		}
		finally
		{
			graph.releaseRef( endRef );
			graph.releaseRef( targetRef );
			graph.releaseRef( scanRef );
			graph.releaseRef( edgeRef );
		}

		if ( filled > 0 )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}

		return filled;
	}

	// -----------------------------------------------------------------------

	/**
	 * Resolves convergences (spots with ≥2 incoming edges — illegal, cell lineages never merge across
	 * time, only divide) by keeping only the nearest-predecessor link and deleting all others.
	 *
	 * <p>Never merges or deletes the predecessor spots themselves — only the extra incoming link(s) are
	 * removed, leaving the "losing" predecessor as an orphan track end (a later reconnection pass may
	 * re-attach it elsewhere if that turns out to be correct). A same-timepoint over-segmentation merge
	 * (see {@link #mergeDuplicateSpots}) is a different, legitimate situation; fusing two spots from two
	 * distinct timepoints never is.</p>
	 *
	 * @return number of links deleted
	 */
	static int pruneConvergenceLinks(
			final ToolContext ctx,
			final int timepoint )
	{
		if ( timepoint == 0 )
			return 0;

		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();

		// Phase 1: find extra incoming links to delete (read lock)
		final List< Link > toDelete = new ArrayList<>();
		final Spot srcRef = graph.vertexRef();

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint || s.incomingEdges().size() < 2 )
					continue;
				if ( ProtectedSpots.isProtected( ctx, model, s ) )
					continue; // its incoming links are left exactly as curated, whatever they are

				// Find the nearest incoming predecessor
				double minDist = Double.MAX_VALUE;
				int nearestEdgeId = -1;
				for ( final Link e : s.incomingEdges() )
				{
					e.getSource( srcRef );
					double distSq = 0;
					for ( int d = 0; d < 3; d++ )
					{
						final double diff = srcRef.getDoublePosition( d ) - s.getDoublePosition( d );
						distSq += diff * diff;
					}
					if ( distSq < minDist )
					{
						minDist = distSq;
						nearestEdgeId = e.getInternalPoolIndex();
					}
				}

				// Mark all other incoming links for deletion
				for ( final Link e : s.incomingEdges() )
				{
					if ( e.getInternalPoolIndex() != nearestEdgeId )
					{
						final Link copy = graph.edgeRef();
						copy.refTo( e );
						toDelete.add( copy );
					}
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( srcRef );
		}

		if ( toDelete.isEmpty() )
			return 0;

		// Phase 2: delete extra links (write lock)
		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Link e : toDelete )
					GraphEdits.removeLink( ctx, graph, e, GraphEdits.Reason.TOPOLOGY );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			model.setUndoPoint();
			ctx.notifyGraphChanged();
			return toDelete.size();
		}
		finally
		{
			for ( final Link e : toDelete )
				graph.releaseRef( e );
		}
	}

	// -----------------------------------------------------------------------

	/**
	 * Prunes spots with ≥3 outgoing edges (trifurcations) at the given timepoint.
	 *
	 * <p>Cell division is biologically binary — a spot with 3+ outgoing edges is a
	 * tracking error caused by multiple cells at t+1 all linking to the same parent.
	 * For each trifurcating spot, the 2 nearest daughter spots (by Euclidean distance)
	 * are kept; all additional outgoing edges are removed.</p>
	 *
	 * @param ctx       tool context
	 * @param timepoint spots at this timepoint with ≥3 outgoing edges are pruned
	 * @return number of outgoing edges removed
	 */
	static int pruneTrifurcations(
			final ToolContext ctx,
			final int timepoint )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();

		// Phase 1: collect pool indices of trifurcating spots (read lock)
		final List< Integer > triIds = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() == timepoint && s.outgoingEdges().size() >= 3 )
					triIds.add( s.getInternalPoolIndex() );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( triIds.isEmpty() )
			return 0;

		// Phase 2: keep the 2 closest daughters per trifurcating spot (write lock)
		final List< Link > toDelete = new ArrayList<>();
		final Spot tgtRef = graph.vertexRef();

		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot s : graph.vertices() )
				{
					if ( s.getTimepoint() != timepoint || !triIds.contains( s.getInternalPoolIndex() ) )
						continue;
					if ( s.outgoingEdges().size() < 3 )
						continue; // may have changed since phase 1
					if ( ProtectedSpots.isProtected( ctx, model, s ) )
						continue; // its outgoing links are left exactly as curated, whatever they are

					final double[] srcPos = {
							s.getDoublePosition( 0 ),
							s.getDoublePosition( 1 ),
							s.getDoublePosition( 2 )
					};

					// Collect edge refs and distance to each daughter
					final List< Link > spotEdgeRefs = new ArrayList<>();
					final List< Double > dists = new ArrayList<>();
					final List< Boolean > targetProtected = new ArrayList<>();
					for ( final Link e : s.outgoingEdges() )
					{
						final Link eRef = graph.edgeRef();
						eRef.refTo( e );
						spotEdgeRefs.add( eRef );
						e.getTarget( tgtRef );
						double distSq = 0;
						for ( int d = 0; d < 3; d++ )
						{
							final double diff = tgtRef.getDoublePosition( d ) - srcPos[ d ];
							distSq += diff * diff;
						}
						dists.add( Math.sqrt( distSq ) );
						targetProtected.add( ProtectedSpots.isProtected( ctx, model, tgtRef ) );
					}

					// Sort indices by ascending distance; keep the 2 closest
					final List< Integer > sortedIdx = new ArrayList<>();
					for ( int i = 0; i < spotEdgeRefs.size(); i++ )
						sortedIdx.add( i );
					sortedIdx.sort( ( a, b ) -> Double.compare( dists.get( a ), dists.get( b ) ) );

					final Set< Integer > keepIdx = new HashSet<>();
					keepIdx.add( sortedIdx.get( 0 ) );
					keepIdx.add( sortedIdx.get( 1 ) );
					// A protected daughter is never pruned, even if it isn't among the 2 closest — its
					// link is a curated result, not a candidate for this heuristic to second-guess.
					for ( int i = 0; i < targetProtected.size(); i++ )
						if ( targetProtected.get( i ) )
							keepIdx.add( i );

					for ( int i = 0; i < spotEdgeRefs.size(); i++ )
					{
						if ( keepIdx.contains( i ) )
							graph.releaseRef( spotEdgeRefs.get( i ) );
						else
							toDelete.add( spotEdgeRefs.get( i ) );
					}
				}

				for ( final Link e : toDelete )
					GraphEdits.removeLink( ctx, graph, e, GraphEdits.Reason.TOPOLOGY );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			if ( !toDelete.isEmpty() )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
				log.info( "pruneTrifurcations(t={}): removed {} extra outgoing edge(s)",
						timepoint, toDelete.size() );
			}
			return toDelete.size();
		}
		finally
		{
			graph.releaseRef( tgtRef );
			for ( final Link e : toDelete )
				graph.releaseRef( e );
		}
	}

	// -----------------------------------------------------------------------

	/**
	 * Counts spots with ≥3 outgoing edges (trifurcations: biologically impossible).
	 *
	 * <p>Used for topology reporting after correction. If trifurcations remain,
	 * they indicate persistent linking errors that correction could not resolve.</p>
	 *
	 * @return number of trifurcating spots
	 */
	public static int countTrifurcations( final ToolContext ctx )
	{
		final ModelGraph graph = ctx.getGraph();
		int count = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
				if ( s.outgoingEdges().size() >= 3 )
					count++;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return count;
	}

	// -----------------------------------------------------------------------

	/**
	 * Post-processing pass run once after all timepoints have been corrected.
	 *
	 * <p>First prunes any residual trifurcations (spots with ≥3 outgoing edges),
	 * then removes spots that became isolated as a cross-timepoint side-effect
	 * (e.g. processing t+1 deleted a spot that was the only link target of a
	 * spot at t, which was already past its own correction cycle).</p>
	 *
	 * @return total number of spots deleted
	 */
	/**
	 * Final reconciliation pass over all timepoints: connects any remaining orphan track starts
	 * (a spot with no incoming edge, typically the start of a post-division daughter tracklet) to a
	 * nearby predecessor at t-1 that still has room (&lt;2 outgoing edges), using a generous search
	 * radius. This catches post-division tracklets whose mother was slightly beyond the per-timepoint
	 * link radius and so were left dangling after the main cleanup.
	 *
	 * @param searchFactor link radius as a multiple of spot radius (larger = more permissive)
	 * @return number of tracklet starts reconnected
	 */
	public static int reconnectOrphanTracklets( final ToolContext ctx, final int numTimepoints,
			final double searchFactor )
	{
		return reconnectOrphanTracklets( ctx, 0, numTimepoints, searchFactor, 0.0 );
	}

	/**
	 * As {@link #reconnectOrphanTracklets(ToolContext, int, double)}, but additionally rejects any
	 * reconnection whose predecessor lies farther than {@code maxDisplacement} world units away (0 =
	 * no cap). This uses the learned per-frame displacement bound so a mid-movie orphan is only linked
	 * to a biologically reachable predecessor.
	 */
	public static int reconnectOrphanTracklets( final ToolContext ctx, final int numTimepoints,
			final double searchFactor, final double maxDisplacement )
	{
		return reconnectOrphanTracklets( ctx, 0, numTimepoints, searchFactor, maxDisplacement );
	}

	/**
	 * As {@link #reconnectOrphanTracklets(ToolContext, int, double, double)}, but only considers orphan
	 * starts at {@code t >= scanStartTp} (0 = scan the whole range). Timepoints before {@code scanStartTp}
	 * were already reconciled by an earlier pass and are left untouched — this lets a per-window
	 * incremental consistency check cost only the recently-processed region instead of redoing the whole
	 * tracked-so-far history every time.
	 */
	public static int reconnectOrphanTracklets( final ToolContext ctx, final int scanStartTp,
			final int numTimepoints, final double searchFactor, final double maxDisplacement )
	{
		int linked = 0;
		for ( int t = Math.max( 1, scanStartTp ); t < numTimepoints; t++ )
			linked += linkOrphanStarts( ctx, t, searchFactor, false, maxDisplacement );
		return linked;
	}

	/**
	 * How many timepoints a single link may span. {@code 1} was not a policy but an accident: every
	 * linking path in this pipeline goes {@code t -> t-1}, and {@link #pruneLongLinks} deleted any
	 * edge spanning more outright, so a cell the detector missed for two frames split its lineage in
	 * two and nothing could ever join it again. That is what the user saw: a track ending at t=44 and
	 * the same cell starting a fresh track at t=47.
	 *
	 * <p>Measured on the CTC tracking ground truth (benchmark/gap-closing), treating each cell as
	 * missing for {@code g} frames and re-homing it to the nearest object {@code g} frames later —
	 * restricted, as this pass is, to objects that are themselves orphaned there:</p>
	 *
	 * <table>
	 * <tr><th>gap</th><th>nearest orphan is the right cell</th><th>displacement p99</th></tr>
	 * <tr><td>1</td><td>100.0% / 100.0%</td><td>9.1 / 9.7 µm</td></tr>
	 * <tr><td>2</td><td>99.9% / 100.0%</td><td>11.6 / 13.4 µm</td></tr>
	 * <tr><td>3</td><td>99.9% / 99.9%</td><td>13.8 / 16.5 µm</td></tr>
	 * <tr><td>4</td><td>99.9% / 99.9%</td><td>16.1 / 19.4 µm</td></tr>
	 * </table>
	 *
	 * <p>(seq 01 / seq 02, 8k and 24k re-homings each.) There is no cliff — 4 measures the same as 2 —
	 * so this is a policy choice, not a threshold. 3 is what the reported case needs (t=44 to t=47 is
	 * a gap of three) and keeps the search radius inside twice the per-frame cap's own p99.</p>
	 */
	public static final int MAX_LINK_GAP = 3;

	/**
	 * How the bridge is built: by filling, never by spanning.
	 *
	 * <p>The first version of {@link #closeTrackGaps} added one edge across the gap. That worked, and
	 * it was wrong for the person looking at the result: a link that skips a timepoint draws as a long
	 * stroke across the viewer with no cell under it, cannot be judged by eye, and is stepped over by
	 * every pass that walks the lineage one frame at a time. Reported as "tracks joining scattered
	 * timepoints", and the errors were frequent because the same run was reconnecting with no distance
	 * cap at all.</p>
	 *
	 * <p>So the gap is FILLED — an interpolated, {@code fn}-tagged spot at every timepoint in it, one
	 * link per frame — and {@link #pruneLongLinks} deletes any edge that still spans more than one
	 * timepoint, whatever made it. Every track in the graph connects at every timepoint it spans.</p>
	 */
	public static final boolean GAPS_ARE_FILLED_NOT_SPANNED = true;

	/**
	 * Joins a dangling track end to an orphan track start {@code 2..maxGap} timepoints later — the one
	 * repair nothing in this pipeline could make.
	 *
	 * <p>Every other linking path works between adjacent timepoints only: ELEPHANT's linker goes
	 * {@code t -> t-1}, {@link #linkOrphanStarts} and {@link #reconnectOrphanTracklets} the same, and
	 * {@link #fillGaps} bridges one frame by asking the detector to look again. So a cell missed for
	 * two consecutive frames is not a gap this pipeline can close; it is two separate lineages. Those
	 * are the "long tracks left unconnected" in the lineage view, and they are also what
	 * {@link #deleteUnstableSpots} then sees as fragments.</p>
	 *
	 * <p>Deliberately narrow: only 1:1 continuations. An end already carrying a successor is not
	 * offered a second one across a gap, so this pass can never fabricate a division — the thing that
	 * costs most when it is wrong. Distance is bounded by the per-frame displacement cap multiplied by
	 * the gap, which the ground-truth p99 above sits inside at every gap, and matches are assigned
	 * globally by lowest cost (near, similar in size, smallest gap) rather than in graph order.</p>
	 *
	 * @param searchFactor   radius-scaled bound, as a multiple of spot radius PER timepoint skipped
	 * @param maxDisplacement per-frame displacement cap in world units; {@code 0} = no cap
	 * @param maxGap         largest number of timepoints a new link may span; {@code < 2} does nothing
	 * @return number of links added
	 */
	public static int closeTrackGaps( final ToolContext ctx, final int startTp, final int endTp,
			final double searchFactor, final double maxDisplacement, final int maxGap )
	{
		if ( maxGap < 2 || searchFactor <= 0.0 || endTp - startTp < 2 )
			return 0;
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();

		final List< Integer > endIds = new ArrayList<>();
		final List< double[] > endPos = new ArrayList<>();
		final List< Double > endRadii = new ArrayList<>();
		final List< Integer > endTps = new ArrayList<>();
		final List< Integer > startIds = new ArrayList<>();
		final List< double[] > startPos = new ArrayList<>();
		final List< Double > startRadii = new ArrayList<>();
		final List< Integer > startTps = new ArrayList<>();

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int tp = s.getTimepoint();
				if ( tp < startTp || tp > endTp )
					continue;
				// fp spots are training negatives, never tracking objects — neither end of a link.
				if ( DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, s ) ) )
					continue;
				if ( s.outgoingEdges().isEmpty() && tp <= endTp - 2
						&& !ProtectedSpots.isProtected( ctx, model, s ) )
				{
					// A confirmed, finished result must not gain a successor it never had, however
					// plausible the geometry — the same rule linkOrphanStarts applies to predecessors.
					final double[] p = new double[ 3 ];
					s.localize( p );
					endIds.add( s.getInternalPoolIndex() );
					endPos.add( p );
					endRadii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
					endTps.add( tp );
				}
				if ( s.incomingEdges().isEmpty() && tp >= startTp + 2 )
				{
					final double[] p = new double[ 3 ];
					s.localize( p );
					startIds.add( s.getInternalPoolIndex() );
					startPos.add( p );
					startRadii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
					startTps.add( tp );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( endIds.isEmpty() || startIds.isEmpty() )
			return 0;

		final List< double[] > pairs = new ArrayList<>();
		for ( int ei = 0; ei < endIds.size(); ei++ )
		{
			final double[] ep = endPos.get( ei );
			final double re = endRadii.get( ei );
			for ( int si = 0; si < startIds.size(); si++ )
			{
				final int gap = startTps.get( si ) - endTps.get( ei );
				if ( gap < 2 || gap > maxGap )
					continue;
				final double[] sp = startPos.get( si );
				final double rs = startRadii.get( si );
				double distSq = 0;
				for ( int d = 0; d < 3; d++ )
				{
					final double diff = ep[ d ] - sp[ d ];
					distSq += diff * diff;
				}
				final double dist = Math.sqrt( distSq );
				// Both bounds scale with the gap, because a cell moves further in more frames. The
				// displacement cap is the one that binds; see MAX_LINK_GAP for the measured p99s.
				if ( maxDisplacement > 0.0 && dist > gap * maxDisplacement )
					continue;
				if ( dist >= gap * searchFactor * Math.max( re, rs ) )
					continue;
				final double sizeRatio = Math.max( re, rs ) / Math.max( 1e-9, Math.min( re, rs ) );
				if ( sizeRatio > CONTINUATION_SIZE_RATIO_MAX )
					continue; // the same cell across a gap still has to look like the same cell
				pairs.add( new double[] { dist * sizeRatio * gap, ei, si } );
			}
		}
		if ( pairs.isEmpty() )
			return 0;
		pairs.sort( ( a, b ) -> Double.compare( a[ 0 ], b[ 0 ] ) );

		final Map< Integer, Integer > startToEnd = new HashMap<>();
		final boolean[] endTaken = new boolean[ endIds.size() ];
		final boolean[] startTaken = new boolean[ startIds.size() ];
		for ( final double[] pr : pairs )
		{
			final int ei = ( int ) pr[ 1 ];
			final int si = ( int ) pr[ 2 ];
			if ( endTaken[ ei ] || startTaken[ si ] )
				continue;
			endTaken[ ei ] = true;
			startTaken[ si ] = true;
			startToEnd.put( startIds.get( si ), endIds.get( ei ) );
		}
		if ( startToEnd.isEmpty() )
			return 0;

		int linked = 0;
		final Spot fromRef = graph.vertexRef();
		final Spot toRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();
		final Map< Integer, Spot > byId = new HashMap<>();
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int id = s.getInternalPoolIndex();
				if ( !startToEnd.containsKey( id ) && !startToEnd.containsValue( id ) )
					continue;
				final Spot copy = graph.vertexRef();
				copy.refTo( s );
				byId.put( id, copy );
			}
			for ( final Map.Entry< Integer, Integer > e : startToEnd.entrySet() )
			{
				final Spot to = byId.get( e.getKey() );
				final Spot from = byId.get( e.getValue() );
				if ( to == null || from == null )
					continue;
				// Re-validate under the write lock: an earlier entry in this same loop may have taken
				// either partner, and nothing here may overwrite an existing link.
				if ( !to.incomingEdges().isEmpty() || !from.outgoingEdges().isEmpty() )
					continue;
				// The join is expressed as SPOTS, not as one edge spanning the gap. A link that skips a
				// timepoint is invisible in the viewer, cannot be judged by eye, and every downstream
				// pass that walks the lineage one frame at a time steps straight over it. Filling the
				// gap instead means the track connects at every timepoint it spans, the interpolated
				// cells can be inspected and deleted like any other detection, and -- tagged fn -- they
				// tell the detector about the cells it missed, which is what actually stops the gap
				// happening again.
				//
				// All or nothing: if any timepoint in the gap cannot take a spot (the cell-count
				// ceiling, or a protected spot already sitting there), the join is abandoned and what
				// was added for it is removed. Half a bridge is worse than none.
				if ( !bridgeWithSpots( ctx, graph, from, to, fromRef, toRef, edgeRef ) )
					continue;
				linked++;
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
			for ( final Spot s : byId.values() )
				graph.releaseRef( s );
			graph.releaseRef( fromRef );
			graph.releaseRef( toRef );
			graph.releaseRef( edgeRef );
		}
		if ( linked > 0 )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		return linked;
	}

	/**
	 * Joins {@code from} to {@code to} by filling every timepoint between them with an interpolated
	 * spot, so the resulting track connects at every timepoint it spans.
	 *
	 * <p>Positions are linear between the two ends and the covariance is the nearer end's, which is
	 * the same thing {@code fillGaps} settles for when the detector cannot be asked again. Each new
	 * spot goes through {@link GraphEdits#addSpot} — so the cell-count ceiling and the protected-spot
	 * overlap check apply to it exactly as to a detection — and is tagged {@code fn}: a cell the model
	 * failed to find, which is what it is, and which ELEPHANT weights ten times a confirmation.</p>
	 *
	 * @return true when the whole bridge was built; false when nothing was left behind
	 */
	private static boolean bridgeWithSpots( final ToolContext ctx, final ModelGraph graph,
			final Spot from, final Spot to, final Spot fromRef, final Spot toRef, final Link edgeRef )
	{
		final int t0 = from.getTimepoint();
		final int t1 = to.getTimepoint();
		final int gap = t1 - t0;
		if ( gap < 1 )
			return false;
		final double[] p0 = new double[ 3 ];
		final double[] p1 = new double[ 3 ];
		from.localize( p0 );
		to.localize( p1 );
		final double[][] cov0 = new double[ 3 ][ 3 ];
		final double[][] cov1 = new double[ 3 ][ 3 ];
		from.getCovariance( cov0 );
		to.getCovariance( cov1 );

		final List< Spot > added = new ArrayList<>();
		final double[] pos = new double[ 3 ];
		try
		{
			for ( int k = 1; k < gap; k++ )
			{
				final double f = ( double ) k / gap;
				for ( int d = 0; d < 3; d++ )
					pos[ d ] = p0[ d ] + f * ( p1[ d ] - p0[ d ] );
				final Spot ref = graph.vertexRef();
				if ( GraphEdits.addSpot( ctx, graph, ref, t0 + k, pos, f < 0.5 ? cov0 : cov1,
						GraphEdits.Reason.TOPOLOGY ) == null )
				{
					graph.releaseRef( ref );
					for ( final Spot s : added )
						GraphEdits.removeSpot( ctx, graph, s, GraphEdits.Reason.TOPOLOGY );
					return false;
				}
				tagAsMissedCell( ctx, ref );
				added.add( ref );
			}
			// Chain them: from -> added[0] -> ... -> to, one link per timepoint.
			Spot prev = from;
			for ( final Spot mid : added )
			{
				fromRef.refTo( prev );
				toRef.refTo( mid );
				GraphEdits.addLink( ctx, graph, fromRef, toRef, edgeRef, GraphEdits.Reason.TOPOLOGY );
				prev = mid;
			}
			fromRef.refTo( prev );
			toRef.refTo( to );
			GraphEdits.addLink( ctx, graph, fromRef, toRef, edgeRef, GraphEdits.Reason.TOPOLOGY );
			return true;
		}
		finally
		{
			for ( final Spot s : added )
				graph.releaseRef( s );
		}
	}

	/**
	 * How many tracks begin in the middle of the movie, and how many of those had a mother available.
	 *
	 * <p>This is the user's report turned into a number: "there are tracks that start partway through
	 * because a division was not caught — a person would go and look at them, see the division, and
	 * join them to the parent." A spot at {@code t > startTp} with no incoming edge is exactly such a
	 * track start. Away from the image border and the first timepoint of the range, every one of them
	 * is either a missed division, a missed link, or a cell that walked into frame.</p>
	 *
	 * <p>The second number is what separates those cases without guessing: a start is <em>reachable</em>
	 * when some spot one timepoint earlier lies within the measured per-frame displacement cap and
	 * still has room for another child. A reachable start is one the pipeline could have joined and did
	 * not — either it never tried, or it tried and a later pass cut it again. An unreachable one has no
	 * mother to find, and no amount of link-factor will produce it.</p>
	 */
	/**
	 * Why {@code linkOrphanStarts} would refuse to join this orphan to this predecessor, or
	 * {@code null} if it would make the join.
	 *
	 * <p>Shared with {@link #reportMidMovieStarts} deliberately. That line claims "those are joins
	 * this run could have made and did not", and it used to decide that on distance and a free
	 * successor slot ALONE -- neither of the joiner's other gates, and without reading a radius at
	 * all. So the number could not be checked against the thing it was describing, and on a run
	 * reporting 24 such joins there was no way to tell whether the joiner was broken or the report
	 * was wrong about what was possible. A claim about another method's behaviour has to be computed
	 * by that method's own rule.</p>
	 *
	 * @param predOutgoing how many successors the predecessor already has: 1 means this link would
	 *                     make it DIVIDE, which is the case the displacement cap is exempted for
	 */
	static String orphanJoinRefusal( final double dist, final double orphanRadius,
			final double predRadius, final int predOutgoing, final double searchFactor,
			final double maxDisplacement )
	{
		if ( predOutgoing >= 2 )
			return "no free successor slot";
		final boolean wouldDivide = predOutgoing == 1;
		if ( !wouldDivide && maxDisplacement > 0.0 && dist > maxDisplacement )
			return "beyond the per-frame displacement cap";
		if ( dist >= searchFactor * Math.max( orphanRadius, predRadius ) )
			return String.format( "beyond %.1f radii", searchFactor );
		if ( wouldDivide )
		{
			if ( orphanRadius > DAUGHTER_SIZE_RATIO_MAX * predRadius )
				return String.format( "daughter %.2fx the mother, over %.2f",
						orphanRadius / Math.max( 1e-9, predRadius ), DAUGHTER_SIZE_RATIO_MAX );
		}
		else if ( Math.max( orphanRadius, predRadius )
				> CONTINUATION_SIZE_RATIO_MAX * Math.min( orphanRadius, predRadius ) )
		{
			return String.format( "sizes differ by more than %.2fx", CONTINUATION_SIZE_RATIO_MAX );
		}
		return null;
	}

	static String reportMidMovieStarts( final ToolContext ctx, final int startTp,
			final int endTp, final double maxDisplacement, final double searchFactor )
	{
		if ( !( maxDisplacement > 0.0 ) )
			return "mid-movie starts=n/a (no displacement cap)";
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final Map< Integer, List< double[] > > freeByTp = new HashMap<>();
		final List< double[] > starts = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int tp = s.getTimepoint();
				if ( tp < startTp || tp > endTp )
					continue;
				if ( DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, s ) ) )
					continue;
				final double[] pos = new double[ 3 ];
				s.localize( pos );
				final double r = Math.sqrt( s.getBoundingSphereRadiusSquared() );
				if ( s.outgoingEdges().size() < 2 )
					freeByTp.computeIfAbsent( tp, k -> new ArrayList<>() ).add( new double[] {
							pos[ 0 ], pos[ 1 ], pos[ 2 ], r, s.outgoingEdges().size() } );
				if ( tp > startTp && s.incomingEdges().isEmpty() )
					starts.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ], tp, r } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( starts.isEmpty() )
			return "mid-movie starts=0";
		int joinable = 0;
		final Map< String, Integer > refusedBy = new HashMap<>();
		for ( final double[] st : starts )
		{
			final List< double[] > free = freeByTp.get( ( int ) st[ 3 ] - 1 );
			if ( free == null )
				continue;
			// The nearest predecessor with room decides. Reporting the best refusal over every
			// candidate would flatter the joiner; reporting the nearest one describes what it saw.
			double bestD = Double.MAX_VALUE;
			double[] best = null;
			for ( final double[] m : free )
			{
				double d2 = 0;
				for ( int k = 0; k < 3; k++ )
				{
					final double diff = st[ k ] - m[ k ];
					d2 += diff * diff;
				}
				if ( d2 < bestD )
				{
					bestD = d2;
					best = m;
				}
			}
			if ( best == null )
				continue;
			final String why = orphanJoinRefusal( Math.sqrt( bestD ), st[ 4 ], best[ 3 ],
					( int ) best[ 4 ], searchFactor, maxDisplacement );
			if ( why == null )
				joinable++;
			else
				refusedBy.merge( why, 1, Integer::sum );
		}
		final StringBuilder why = new StringBuilder();
		refusedBy.entrySet().stream()
				.sorted( ( a, b ) -> Integer.compare( b.getValue(), a.getValue() ) )
				.limit( 3 )
				.forEach( e -> why.append( String.format( "; %d %s", e.getValue(), e.getKey() ) ) );
		return String.format( "mid-movie starts=%d, of which %d would pass every gate "
				+ "linkOrphanStarts applies and were still not joined — those are its bugs, not its "
				+ "policy%s", starts.size(), joinable, why );
	}

	public static int globalCleanup( final ToolContext ctx, final int numTimepoints )
	{
		// First: prune any residual trifurcations — may orphan daughter spots
		for ( int t = 0; t < numTimepoints; t++ )
		{
			final int tri = pruneTrifurcations( ctx, t );
			if ( tri > 0 )
				log.info( "Global cleanup t={} — pruned {} residual trifurcation(s)", t, tri );
		}
		// Then the dual: convergences, spots with 2+ incoming edges. Lineages divide and never merge,
		// so this is as illegal as a trifurcation and needs no pixels to see — but until this call the
		// only pass enforcing it was the per-timepoint correction, which samples intensity and so runs
		// only where image access is available. A merge in a timepoint that pass never visited survived
		// the whole-movie sweep. Placed before the orphan relink and the isolated-spot deletion because
		// it leaves the losing predecessor as a dangling end for them to consider.
		for ( int t = 1; t < numTimepoints; t++ )
		{
			final int conv = pruneConvergenceLinks( ctx, t );
			if ( conv > 0 )
				log.info( "Global cleanup t={} — pruned {} convergence link(s)", t, conv );
		}
		// Next: re-link orphan starts (may have been created by trifurcation pruning
		// or cross-timepoint correction side-effects)
		for ( int t = 1; t < numTimepoints; t++ )
		{
			final int orphanLinked = linkOrphanStarts( ctx, t, 2.0 );
			if ( orphanLinked > 0 )
				log.info( "Global cleanup t={} — linked {} orphan start(s)", t, orphanLinked );
		}
		// ...and the multi-frame breaks that pass cannot reach, for the same reason and in the same
		// place: an isolated spot two or three timepoints from its own track's end is about to be
		// deleted below. The cap is measured here rather than learned, because this entry point has no
		// settings to load a profile from — and every reconnection above it runs with no cap at all.
		final double cap = measureMaxDisplacement( ctx, 0, numTimepoints - 1 );
		final int bridged = closeTrackGaps( ctx, 0, numTimepoints - 1, 3.0, cap, MAX_LINK_GAP );
		if ( bridged > 0 )
			log.info( "Global cleanup — bridged {} multi-frame gap(s) (cap {} world units)", bridged, cap );
		// Then: delete all isolated spots, including those orphaned by trifurcation pruning
		int total = 0;
		for ( int t = 0; t < numTimepoints; t++ )
			total += deleteIsolatedSpots( ctx, t );
		// Over-segmentation that holds together across the movie. This pass needs COLOCATED_MIN_FRAMES
		// of unbranched history to decide anything, so the whole-movie sweep is where it belongs — a
		// per-window caller scoped to ten timepoints cannot supply twelve, and one wired only there
		// could never fire on the first window of a run.
		final int coLocated = mergePersistentCoLocatedPairs( ctx, 0, numTimepoints - 1 );
		if ( coLocated > 0 )
		{
			total += coLocated;
			ctx.log( String.format( "  Global cleanup — merged %d spot(s) of track pairs that held a "
					+ "steady sub-cell offset for %d+ timepoints: one cell detected twice.",
					coLocated, COLOCATED_MIN_FRAMES ) );
		}
		if ( total > 0 )
			log.info( "Global cleanup — deleted {} isolated spot(s) across all timepoints", total );
		return total;
	}

	// -----------------------------------------------------------------------

	/**
	 * Public wrapper for per-timepoint weak-spot filtering.
	 *
	 * <p>For each spot: if ELEPHANT recorded a {@link DetectionQuality} for it, the spot is deleted when
	 * its quality is below the absolute {@code qualityThreshold} (a calibrated model probability — no
	 * median involved). Otherwise (older server, or a spot added through a path with no quality), it
	 * falls back to the original heuristic: delete if intensity is below {@code minIntensityRatio ×
	 * median(intensities of quality-less spots at this timepoint)}. Useful for removing false positives
	 * that ELEPHANT placed in dark/low-confidence regions.</p>
	 *
	 * @param ctx             tool context
	 * @param timepoint       timepoint to filter
	 * @param minIntensityRatio ratio; quality-less spots below this fraction of the local median
	 *                        are removed (e.g. 0.3 removes spots darker than 30 % of
	 *                        the median cell brightness)
	 * @param qualityThreshold absolute cutoff; spots with a recorded quality below this are removed
	 *                        (0 = off)
	 * @return number of spots deleted
	 */
	public static int filterWeakSpots(
			final ToolContext ctx,
			final int timepoint,
			final double minIntensityRatio,
			final double qualityThreshold )
	{
		if ( minIntensityRatio <= 0.0 && qualityThreshold <= 0.0 )
			return 0;
		return deleteWeakSpots( ctx, timepoint, minIntensityRatio, qualityThreshold );
	}

	// -----------------------------------------------------------------------

	/**
	 * Deletes short branches that originate from a cell division.
	 *
	 * <p>Identifies dangling-end spots (no outgoing edges) at non-terminal
	 * timepoints, traces backward, and deletes the branch when:</p>
	 * <ol>
	 *   <li>The trace reaches a <em>division point</em> (a spot with ≥2 outgoing
	 *       edges) — meaning this branch started from a division.</li>
	 *   <li>The number of spots in the branch (from the dangling end up to, but
	 *       not including, the division point) is less than
	 *       {@code minBranchLength}.</li>
	 * </ol>
	 *
	 * <p>Standalone short tracklets that did not start from a division are
	 * <em>not</em> deleted, making this safer than the general
	 * {@link #deleteShortDanglingTracklets} for a first correction pass.</p>
	 *
	 * @param ctx             tool context
	 * @param numTimepoints   total number of timepoints (used to exclude the
	 *                        last timepoint from "dangling" detection)
	 * @param minBranchLength minimum acceptable branch length; branches shorter
	 *                        than this are removed (e.g. 5 removes branches of
	 *                        1–4 spots)
	 * @return number of spots deleted
	 */
	public static int deleteShortPostDivisionBranches(
			final ToolContext ctx,
			final int numTimepoints,
			final int minBranchLength )
	{
		return deleteShortPostDivisionBranches( ctx, 0, numTimepoints, minBranchLength );
	}

	/**
	 * As {@link #deleteShortPostDivisionBranches(ToolContext, int, int)}, but only considers dangling
	 * ends at {@code t >= scanStartTp} (0 = scan the whole range) — the backward trace to find the
	 * originating division point is unaffected and may still walk earlier than {@code scanStartTp}.
	 * Timepoints before {@code scanStartTp} were already checked by an earlier pass and are left alone.
	 */
	public static int deleteShortPostDivisionBranches(
			final ToolContext ctx,
			final int scanStartTp,
			final int numTimepoints,
			final int minBranchLength )
	{
		if ( minBranchLength <= 0 )
			return 0;

		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();

		// Determine the actual last timepoint that has spots.
		// numTimepoints is the TOTAL dataset size, but tracking may cover only a sub-range
		// (e.g. t=0..9 in a 100-timepoint dataset). The protection zone must be relative
		// to the ACTUAL data end, not the total dataset size, so that real daughter cells
		// near the end of the tracked range are always preserved.
		//
		// Bounded ABOVE by this call's own range, not by the graph. A windowed caller passes
		// wEnd+1, and a whole-graph scan answers with the last timepoint of the MOVIE — which is the
		// same number only while nothing above the window exists yet. Re-running over a project that
		// already holds a track reverses that: for a window at t=0..9 of a finished 92-tp movie the
		// protection zone moved to t=89, so every dangling end below it was eligible and the pass
		// deleted branches as far out as t=14. The structural invariant caught it and the window was
		// rolled back whole, which is why that run reported 9/10 windows and 82 of 92 timepoints.
		final int lastTpInRange = Math.min( numTimepoints - 1, Integer.MAX_VALUE - 1 );
		int actualLastTp = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t >= scanStartTp && t <= lastTpInRange )
					actualLastTp = Math.max( actualLastTp, t );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		// Protect the last (minBranchLength) timepoints of ACTUAL data.
		final int protectionThreshold = actualLastTp + 1 - minBranchLength;

		// Phase 1: find short post-division branches (read lock)
		final Set< Integer > toDeleteIds = new HashSet<>();
		final Spot cursorRef = graph.vertexRef();

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				// Only consider dangling ends outside the protected zone.
				// Real daughter cells near the end of the tracked range lack outgoing
				// edges simply because tracking stopped — they must not be deleted.
				if ( !s.outgoingEdges().isEmpty() )
					continue;
				if ( s.getTimepoint() >= protectionThreshold )
					continue;
				if ( s.getTimepoint() < scanStartTp || s.getTimepoint() > lastTpInRange )
					continue;

				// Trace backward along linear chain
				final List< Integer > chain = new ArrayList<>();
				chain.add( s.getInternalPoolIndex() );
				cursorRef.refTo( s );
				boolean reachedDivision = false;
				// A protected spot anywhere in this branch vetoes deleting the WHOLE chain — a partial
				// delete would leave a dangling/incoherent branch, and the confirmed spot itself must
				// never be removed regardless.
				boolean chainProtected = ProtectedSpots.isProtected( ctx, model, s );

				while ( chain.size() < minBranchLength )
				{
					if ( cursorRef.incomingEdges().isEmpty() )
						break; // track start, not from a division
					// Follow first incoming edge to predecessor
					for ( final Link e : cursorRef.incomingEdges() )
					{
						e.getSource( cursorRef );
						break;
					}
					// Stop when we hit a division point (belongs to the trunk)
					if ( cursorRef.outgoingEdges().size() > 1 )
					{
						reachedDivision = true;
						break;
					}
					// The chain has walked out of this call's range. Leave without reachedDivision, so
					// nothing is deleted: a branch that continues below the window is not this window's
					// to judge, and deleting part of it would change a timepoint it was not scoped to.
					if ( cursorRef.getTimepoint() < scanStartTp )
						break;
					chain.add( cursorRef.getInternalPoolIndex() );
					chainProtected |= ProtectedSpots.isProtected( ctx, model, cursorRef );
				}

				// Only mark for deletion if the branch came from a division
				if ( reachedDivision && chain.size() < minBranchLength && !chainProtected )
					toDeleteIds.addAll( chain );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( cursorRef );
		}

		if ( toDeleteIds.isEmpty() )
			return 0;

		// Phase 2: delete marked spots (write lock)
		final List< Spot > toDelete = new ArrayList<>();
		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot s : graph.vertices() )
				{
					if ( toDeleteIds.contains( s.getInternalPoolIndex() ) )
					{
						final Spot copy = graph.vertexRef();
						copy.refTo( s );
						toDelete.add( copy );
					}
				}
				for ( final Spot s : toDelete )
					GraphEdits.removeSpot( ctx, graph, s, GraphEdits.Reason.TOPOLOGY );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			if ( !toDelete.isEmpty() )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			log.info( "deleteShortPostDivisionBranches: deleted {} spot(s) from {} short post-division branches",
					toDelete.size(), toDeleteIds.size() );
			return toDelete.size();
		}
		finally
		{
			for ( final Spot s : toDelete )
				graph.releaseRef( s );
		}
	}

	// -----------------------------------------------------------------------

	/**
	 * Estimates the minimum biologically plausible division interval (in timepoints)
	 * from the observed cell-count growth rate between {@code startTp} and {@code endTp}.
	 *
	 * <p>Uses the exponential-growth model: N(t) = N₀ × 2^(t/D), so the population
	 * doubling time D = (endTp-startTp) × log(2) / log(N_late / N_early).
	 * The first and last quarters of the range are averaged to improve robustness.</p>
	 *
	 * <p>Returns the total timepoint range when no growth is detected (no division
	 * expected), or 2 at minimum (consecutive-timepoint division is always impossible).</p>
	 */
	public static int estimateMinDivisionInterval(
			final ToolContext ctx, final int startTp, final int endTp )
	{
		// Count spots per timepoint, restricting to the given hint range
		final Map< Integer, Integer > countByTp = new HashMap<>();
		final ModelGraph graph = ctx.getGraph();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t >= startTp && t <= endTp )
					countByTp.merge( t, 1, Integer::sum );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( countByTp.size() < 2 )
			return 0;

		// Use the ACTUAL data range rather than the hint range.
		// The hint may cover the full dataset (0..numTimepoints-1) but actual spots
		// may exist only for a sub-range (e.g. t=0..9 in a 100-tp dataset).
		// Using the hint range would inflate the doubling-time estimate proportionally.
		int actualStart = Integer.MAX_VALUE, actualEnd = 0;
		for ( final int t : countByTp.keySet() )
		{
			if ( t < actualStart ) actualStart = t;
			if ( t > actualEnd ) actualEnd = t;
		}
		final int actualRange = actualEnd - actualStart;
		if ( actualRange < 2 )
			return 0;

		// Average count in first quarter vs last quarter of ACTUAL data range
		final int qLen = Math.max( 1, actualRange / 4 );
		double sumEarly = 0.0; int nEarly = 0;
		double sumLate = 0.0; int nLate = 0;
		for ( int t = actualStart; t <= actualStart + qLen; t++ )
		{
			final Integer c = countByTp.get( t );
			if ( c != null ) { sumEarly += c; nEarly++; }
		}
		for ( int t = actualEnd - qLen; t <= actualEnd; t++ )
		{
			final Integer c = countByTp.get( t );
			if ( c != null ) { sumLate += c; nLate++; }
		}
		if ( nEarly == 0 || nLate == 0 )
			return 0;

		final double n0 = sumEarly / nEarly;
		final double nT = sumLate / nLate;
		if ( nT <= n0 * 1.05 )
			return 0; // no meaningful growth detected → division removal disabled

		// D = actualRange × log(2) / log(Nₜ/N₀)
		final double doublingTime = actualRange * Math.log( 2.0 ) / Math.log( nT / n0 );
		return Math.max( 2, Math.min( MAX_AUTO_MIN_INTERVAL,
				( int ) Math.round( doublingTime / DOUBLING_TIME_TO_MIN_INTERVAL ) ) );
	}

	/**
	 * How far BELOW the population's doubling time the shortest credible cell cycle sits.
	 *
	 * <p>The doubling time was used as the minimum interval directly, and it is not one — it is
	 * roughly the MEAN, and higher than the mean cycle at that, because a population doubles more
	 * slowly than its cells cycle whenever some of them stop, die or leave the field. Using it as a
	 * floor declares every lineage that divides faster than average biologically impossible.</p>
	 *
	 * <p>Measured on the CTC tracking ground truth for Fluo-N2DL-HeLa, over every completed cycle
	 * (a track born of a division that itself divides — 56 in sequence 01, 95 in 02), as the share of
	 * REAL divisions the threshold would cut:</p>
	 *
	 * <pre>
	 * divisor   01: thr / cut     02: thr / cut
	 *   1.0 (was)   54 / 91.1%        59 / 91.6%
	 *   1.5         36 / 35.7%        39 / 64.2%
	 *   2.0         27 /  0.0%        30 / 18.9%
	 *   2.5         22 /  0.0%        24 /  3.2%
	 *   3.0         18 /  0.0%        20 /  3.2%
	 * </pre>
	 *
	 * <p>2.5 is the knee: below it nothing more is bought. The shortest real cycle is 29 frames in
	 * sequence 01 and the median is 39, against a doubling time of 54 — so the old rule sat above the
	 * median and cut nine real divisions in ten. Observed end to end on a 92-frame run of sequence 01:
	 * 10 divisions survived where the population's own growth (45 to 158 cells) needs at least 113,
	 * while the topology passes dissolved 57 as "rapid" and de-branched 129 more.</p>
	 *
	 * <p>Reproduce with {@code benchmark/division-interval/measure.py}.</p>
	 */
	public static final double DOUBLING_TIME_TO_MIN_INTERVAL = 2.5;

	/**
	 * Ceiling on the auto-derived refractory threshold, whatever the doubling time says.
	 *
	 * <p>The divisor above is right; the doubling time it divides is an estimate from two quartile
	 * counts, and it drifts. A 92-frame run of sequence 01 produced 31 rather than the 22 the divisor
	 * was measured at, rising window by window as the estimate settled: 12, 16, 24, 24, 23, 26, 29,
	 * 31, 31.</p>
	 *
	 * <p>Measured against the CTC TRACKING truth -- the interval between a division and its ancestor's
	 * own, which is exactly what this rule thresholds (see
	 * {@code benchmark/division-interval/measure_refractory.py}, 112 and 191 measurable events):</p>
	 *
	 * <pre>
	 *   threshold   real divisions cut, seq 01 / seq 02
	 *      20              0.0%  /   3.7%
	 *      25              0.0%  /   3.7%
	 *      29              0.0%  /  14.1%
	 *      31              8.9%  /  26.7%
	 *      33             16.1%  /  39.3%
	 * </pre>
	 *
	 * <p>The shortest real interval is 29 frames in sequence 01 but 3 in sequence 02, so a threshold
	 * that looks safe on one is not safe on the other, and the estimate cannot tell them apart. At 20
	 * nothing real is lost in sequence 01 and 3.7% in sequence 02, against 8.9% and 26.7% at 31.</p>
	 */
	public static final int MAX_AUTO_MIN_INTERVAL = 20;

	/**
	 * Runs a whole-graph TOPOLOGY consistency pass over the tracked-so-far graph: dissolves
	 * biologically-implausible rapid successive divisions, deletes spurious short branches that
	 * originate from a division point, and reconnects broken/orphaned tracklets. Thresholds are
	 * derived from the active learned {@link LineageConstraintProfile} (same rules as the global
	 * {@code auto.correct} path), so per-batch consistency uses the same GT-learned biology.
	 *
	 * <p>Every step is frontier-safe: {@link #deleteShortPostDivisionBranches} protects the last
	 * timepoints of the ACTUAL data (so a daughter that has only just appeared at the frontier is
	 * never pruned), rapid-division removal only merges over-eager divisions, and reconnection only
	 * ADDS links. Intended to be called after each adaptive-tracking window so intermediate results
	 * are already de-branched and de-fragmented, not only at the very end.</p>
	 *
	 * @param scanStartTp only re-check {@code t >= scanStartTp} for NEW defects (0 = the whole tracked
	 *                    range). Timepoints before it were already reconciled by an earlier call and are
	 *                    left untouched — backward traces (e.g. to find a branch's originating division)
	 *                    are unaffected and may still walk earlier than this. Lets a per-window call cost
	 *                    only the recently-processed region instead of redoing the whole history every time.
	 * @return a short human-readable summary of what was changed
	 */
	/**
	 * Separation, as a multiple of cell radius, under which two spots may be one over-segmented cell.
	 *
	 * <p>Set just below what a real division starts at. Measured on the CTC tracking ground truth for
	 * Fluo-N2DL-HeLa (90 divisions): the two daughters are already 1.87 r apart in the first frame
	 * after the division at the 5th percentile, and 2.41 r at the median. The over-segmentation this
	 * was built for sat at 1.82 r. See benchmark/merge-valley.</p>
	 */
	public static final double COLOCATED_MAX_SEPARATION = 1.9;

	/**
	 * Consecutive timepoints a pair must hold together before it is called one cell.
	 *
	 * <p>This is what the within-frame tests could not supply. A pair at 1.9 r cannot be judged from
	 * one frame — the valley test fuses 22% of genuinely distinct pairs at that range — but daughters
	 * separate and two tiles of one cell do not. Twelve frames costs almost nothing in specificity and
	 * buys most of it: at 8 frames the rule fires on 3.3% of real divisions, at 12 on 1.1%.</p>
	 */
	public static final int COLOCATED_MIN_FRAMES = 12;

	/**
	 * How much the separation may vary across those frames, as a multiple of cell radius.
	 *
	 * <p>The discriminating half. Two tiles of one cell hold a near-constant offset; daughters drift
	 * apart even when they stay close. Measured against 90 real divisions and 16621 pairs of unrelated
	 * co-existing tracks, the three constants together fire on 1 of 90 divisions and 1 of 16621
	 * neighbour pairs.</p>
	 */
	public static final double COLOCATED_MAX_DRIFT = 0.15;

	/**
	 * Whether a separation series holds together long enough, and steadily enough, to be one cell seen
	 * twice rather than two cells that happen to be close.
	 *
	 * <p>Separations are in units of cell radius. Pure arithmetic, deliberately: the rule needs no
	 * pixels, so it can be tested against real tracks without an image.</p>
	 *
	 * @param separations separation at each consecutive timepoint the pair co-exists
	 */
	public static boolean holdsTogether( final double[] separations, final double maxSeparation,
			final int minFrames, final double maxDrift )
	{
		if ( separations == null || separations.length < minFrames )
			return false;
		int run = 0;
		for ( int i = 0; i < separations.length; i++ )
		{
			if ( !( separations[ i ] < maxSeparation ) )
			{
				run = 0;
				continue;
			}
			run++;
			if ( run < minFrames )
				continue;
			double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
			for ( int k = i - minFrames + 1; k <= i; k++ )
			{
				lo = Math.min( lo, separations[ k ] );
				hi = Math.max( hi, separations[ k ] );
			}
			if ( hi - lo < maxDrift )
				return true;
		}
		return false;
	}

	/**
	 * Merges pairs of tracks that are really one cell detected twice.
	 *
	 * <p>The case this exists for cannot be decided within a frame. Two detections tiling one
	 * elongated cell sit about 1.8 cell-radii apart — below where a real division starts (1.87 r at
	 * the 5th percentile of 90 measured divisions) but far enough that the within-frame tests are
	 * guessing: the valley test fuses 22% of genuinely distinct pairs at that range and a
	 * connected-region test 69%. Over time the two cases part company, because two tiles of one cell
	 * hold a near-constant offset and daughters drift apart.</p>
	 *
	 * <p>Only runs where a pair holds together for {@link #COLOCATED_MIN_FRAMES} consecutive
	 * timepoints — see {@link #holdsTogether}. Measured on CTC tracking ground truth, that fires on 1
	 * of 90 real divisions and 1 of 16621 pairs of unrelated co-existing tracks.</p>
	 *
	 * <p>Chains are followed only while each side has exactly one successor. A branch means something
	 * else is happening there, and the series stops rather than guessing which way to continue.</p>
	 *
	 * @return net spots removed (each merged frame turns two spots into one)
	 */
	public static int mergePersistentCoLocatedPairs( final ToolContext ctx, final int startTp,
			final int endTp )
	{
		// Same switch as every other merge pass — see CleanupToggles.duplicateResolutionEnabled.
		if ( !CleanupToggles.duplicateResolutionEnabled( ctx ) )
			return 0;
		if ( endTp - startTp + 1 < COLOCATED_MIN_FRAMES )
		{
			// Not a no-op worth hiding: wired into a per-window caller whose scope is ten timepoints,
			// this rule needs twelve and can never fire, which is exactly how it came to be shipped
			// looking like it worked. Say it.
			ctx.log( String.format(
					"  Co-located merge skipped on t=%d..%d: it needs %d consecutive timepoints to tell "
							+ "an over-segmented cell from a division, and this range has %d. The "
							+ "whole-movie pass in auto.correct is where it can decide.",
					startTp, endTp, COLOCATED_MIN_FRAMES, endTp - startTp + 1 ) );
			return 0;
		}
		final ModelGraph graph = ctx.getGraph();
		// One entry per timepoint of a qualifying stretch: {timepoint, poolA, poolB}.
		final List< int[] > toMerge = new ArrayList<>();
		final Set< Long > seen = new HashSet<>();
		graph.getLock().readLock().lock();
		try
		{
			final Map< Integer, List< Spot > > byTp = new HashMap<>();
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				if ( t >= startTp && t <= endTp )
					byTp.computeIfAbsent( t, k -> new ArrayList<>() ).add( graph.vertexRef().refTo( spot ) );
			}
			for ( final Map.Entry< Integer, List< Spot > > e : byTp.entrySet() )
			{
				final List< Spot > here = e.getValue();
				for ( int i = 0; i < here.size(); i++ )
					for ( int j = i + 1; j < here.size(); j++ )
					{
						final Spot a = here.get( i ), b = here.get( j );
						final long key = pairKey( a.getInternalPoolIndex(), b.getInternalPoolIndex() );
						if ( seen.contains( key ) )
							continue;
						final List< double[] > steps = new ArrayList<>();
						final double[] sep = coLocatedSeries( graph, a, b, endTp, seen, steps );
						if ( !holdsTogether( sep, COLOCATED_MAX_SEPARATION, COLOCATED_MIN_FRAMES,
								COLOCATED_MAX_DRIFT ) )
							continue;
						// Merge the pair frame by frame, over exactly the stretch that was measured, and
						// only where they are actually co-located: holdsTogether needs a run of
						// COLOCATED_MIN_FRAMES somewhere in the series, which says nothing about the
						// frames outside that run.
						//
						// This used to delete the smaller track's spots and keep the larger. But a pair
						// that holds a constant offset for twelve frames is one cell TILED by two
						// detections, so the larger of the two is not the cell either — it is the larger
						// tile, off centre and short. The cell is the pair together, every frame.
						for ( final double[] st : steps )
						{
							if ( st[ 3 ] < COLOCATED_MAX_SEPARATION )
								toMerge.add( new int[] { ( int ) st[ 0 ], ( int ) st[ 1 ],
										( int ) st[ 2 ] } );
						}
					}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( toMerge.isEmpty() )
			return 0;
		int removed = 0;
		final Set< Integer > created = new HashSet<>();
		graph.getLock().writeLock().lock();
		try
		{
			for ( final int[] step : toMerge )
			{
				final Set< Integer > pair = new HashSet<>();
				pair.add( step[ 1 ] );
				pair.add( step[ 2 ] );
				removed += mergeIntoCoveringEllipsoid( ctx, graph, step[ 0 ], pair, created );
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( removed > 0 )
			ctx.notifyGraphChanged();
		return removed;
	}

	private static long pairKey( final int a, final int b )
	{
		return ( ( long ) Math.min( a, b ) << 32 ) | ( Math.max( a, b ) & 0xFFFFFFFFL );
	}

	private static double spotRadius( final Spot spot )
	{
		final double[][] cov = new double[ 3 ][ 3 ];
		spot.getCovariance( cov );
		return Math.sqrt( Math.max( 0.0, ( cov[ 0 ][ 0 ] + cov[ 1 ][ 1 ] + cov[ 2 ][ 2 ] ) / 3.0 ) );
	}

	/**
	 * Least distance between the two cells of a repaired merge, in the frame's median detection radii.
	 * Closer than this, the two-component fit has cut ONE cell in half.
	 *
	 * <p>Measured on the frames a merge was carried through (Fluo-N2DL-HeLa 01 against silver truth,
	 * {@code benchmark/lineage-review/evaluate_convergence_repair.py}): fits that put the two pieces on
	 * one cell sat at 0.81-0.92 radii apart (p10-p90), fits on two cells at 0.76-1.67 (median 1.23). At
	 * 0.9, 39 of the 42 frames it repaired were right on run 20261002-015311 and 10 of 10 on 20261004;
	 * at the split pass's 0.6, 48 of 73.</p>
	 */
	static final double CONVERGENCE_MIN_SEPARATION = 0.9;

	/**
	 * Two tracks running into one detection: splits it back into the two cells the frames before it
	 * held, and carries the split forward while the merge lasts.
	 *
	 * <p>Up to frame t two neighbouring cells carry two spots and two tracks; at t+1 the detector puts
	 * one large spot over both. A spot takes one incoming link, so one track runs on into it and the
	 * other just ends -- and when the cells part again the survivor "divides". A person sees this at
	 * once because they look at the frame before. So does this: the onset is a spot S whose
	 * predecessor P is one cell while another spot Q at the same timepoint, with no successor at all,
	 * has its centre inside S. On Fluo-N2DL-HeLa 01 that flags 24 spots over the movie, 22 of them on
	 * two truth cells.</p>
	 *
	 * <p>S is replaced by two pieces fitted to the outline of the object under it, the fit started at
	 * P and Q's positions carried by S's own motion ({@link ShapeSplit#analyse} with seeds). P links to
	 * the piece grown from P, Q to the one grown from Q. The next frame is repaired the same way while
	 * S's single successor still holds both pieces and no other detection has appeared on either; when
	 * the merge is over the pieces are linked on to what the detector found. A repair the image does
	 * not support (no object, one component collapsed, the two centres closer than
	 * {@link #CONVERGENCE_MIN_SEPARATION} radii) leaves that frame and every later one untouched.</p>
	 *
	 * <p>Fluorescence and single-plane only, both stated: the outline is a threshold on brightness, and
	 * the fit is in plane and was only measured on 2D data.</p>
	 *
	 * @param scanStartTp first timepoint a merge may START at (its onset)
	 * @param numTimepoints one past the last timepoint this pass may change
	 * @return {repaired frames, merge episodes}
	 */
	public static int[] repairConvergedTracks( final ToolContext ctx, final int scanStartTp,
			final int numTimepoints )
	{
		final int first = Math.max( 1, scanStartTp );
		if ( first >= numTimepoints )
			return new int[] { 0, 0 };
		if ( !ImageProfile.forRun( ctx,
				() -> sampleIntensities( ctx, first, ImageProfile.SAMPLES ) ).fluorescenceLike )
			return new int[] { 0, 0 };
		// On a stack: the same lineage rules with 3-D geometry and a 3-D fit, and nobody is asked -- the
		// question shows one plane, which cannot show two cells stacked in z. The end-of-merge and size-jump
		// readings are asked only, so they stay 2-D.
		final boolean stack = ctx.isVolumetric();
		return withGeometry( stack, () -> repairConvergedTracks( ctx, first, numTimepoints, stack
				? ( t, centre, radius, cov, seeds, frameRadius ) -> fitPieces3D( ctx, t, centre, cov, seeds, frameRadius ).pieces
				: ( t, centre, radius, cov, seeds, frameRadius ) -> fitPieces( ctx, t, centre, radius, cov, seeds,
						frameRadius ),
				stack ? null : splitQuestion( ctx ) ) );
	}

	/** Runs {@code body} with the merge repair's geometry set for a stack or a single plane. */
	static < T > T withGeometry( final boolean stack, final java.util.function.Supplier< T > body )
	{
		final Boolean before = CONVERGENCE_VOLUMETRIC.get();
		CONVERGENCE_VOLUMETRIC.set( stack );
		try
		{
			return body.get();
		}
		finally
		{
			CONVERGENCE_VOLUMETRIC.set( before );
		}
	}

	/** Voxels read beyond the merged detection, in its own radii (Mahalanobis), for the 3-D fit. */
	static final double CONVERGENCE_3D_REACH = 1.2;

	/** Fewest voxels across the merged detection's radius at the level the 3-D fit reads. */
	static final double CONVERGENCE_3D_MIN_RADIUS_VOXELS = 4;

	/** Most voxels the 3-D fit reads; beyond this it reads every n-th voxel along each axis. */
	static final int CONVERGENCE_3D_MAX_VOXELS = 200_000;

	/** A 3-D fit's pieces, or why there are none. */
	static final class Fit3D
	{
		final Pieces pieces;

		final String refusal;

		Fit3D( final Pieces pieces, final String refusal )
		{
			this.pieces = pieces;
			this.refusal = refusal;
		}
	}

	/**
	 * The two cells under a merged detection on a stack: the voxels above the frame's object level within
	 * {@link #CONVERGENCE_3D_REACH} of its ellipsoid, fitted as two components started at {@code seeds}
	 * ({@link ShapeSplit3D}). Read at the coarsest level that still has
	 * {@link #CONVERGENCE_3D_MIN_RADIUS_VOXELS} voxels across the detection's smallest semi-axis, so a large
	 * stack read over the network costs a few thousand voxels, not millions. Refused, as in 2-D, when the
	 * two pieces end up closer than {@link #CONVERGENCE_MIN_SEPARATION} frame radii.
	 */
	static Fit3D fitPieces3D( final ToolContext ctx, final int t, final double[] centre, final double[][] cov,
			final double[][] seeds, final double frameRadius )
	{
		final double[] frame = frameLevelStats( ctx, t );
		final List< SourceAndConverter< ? > > sources = ctx.requireViewer().state().getSources();
		if ( frame == null || sources.isEmpty() || !( frameRadius > 0 ) )
			return new Fit3D( null, "no frame statistics" );
		final double level = frame[ 0 ] + SHAPE_LEVEL_SIGMA * frame[ 1 ];
		final double[][] inv = ShapeSplit3D.invert3( cov );
		if ( inv == null )
			return new Fit3D( null, "degenerate detection" );
		// The detection's smallest and largest semi-axes bound the box and choose the level.
		final double[] eig = symmetricEigenvalues( cov );
		final double rMin = Math.sqrt( Math.max( 0, eig[ 0 ] ) ), rMax = Math.sqrt( Math.max( 0, eig[ 2 ] ) );
		final Source< ? > source = sources.get( 0 ).getSpimSource();
		int lvl = 0;
		for ( int l = source.getNumMipmapLevels() - 1; l > 0; l-- )
		{
			final Sampler c = Sampler.of( sources.get( 0 ), t, l );
			if ( c != null && rMin / Math.max( c.voxelSize( 0 ), Math.max( c.voxelSize( 1 ), c.voxelSize( 2 ) ) )
					>= CONVERGENCE_3D_MIN_RADIUS_VOXELS )
			{
				lvl = l;
				break;
			}
		}
		final Sampler s = Sampler.of( sources.get( 0 ), t, lvl );
		if ( s == null )
			return new Fit3D( null, "no image at this timepoint" );
		final double reach = rMax * CONVERGENCE_3D_REACH;
		final long[] lo = new long[ 3 ], hi = new long[ 3 ];
		final double[] v = new double[ 3 ];
		s.toVoxel( centre, v );
		long count = 1;
		for ( int d = 0; d < 3; d++ )
		{
			final long half = ( long ) Math.ceil( reach / s.voxelSize( d ) ) + 1;
			lo[ d ] = Math.max( s.min( d ), Math.round( v[ d ] ) - half );
			hi[ d ] = Math.min( s.max( d ), Math.round( v[ d ] ) + half );
			if ( hi[ d ] < lo[ d ] )
				return new Fit3D( null, "outside the image" );
			count *= hi[ d ] - lo[ d ] + 1;
		}
		final int stride = ( int ) Math.max( 1, Math.ceil( Math.cbrt( ( double ) count / CONVERGENCE_3D_MAX_VOXELS ) ) );
		final List< double[] > pts = new ArrayList<>();
		final double[] vox = new double[ 3 ];
		final double r2 = CONVERGENCE_3D_REACH * CONVERGENCE_3D_REACH;
		for ( long z = lo[ 2 ]; z <= hi[ 2 ]; z += stride )
			for ( long y = lo[ 1 ]; y <= hi[ 1 ]; y += stride )
				for ( long x = lo[ 0 ]; x <= hi[ 0 ]; x += stride )
				{
					vox[ 0 ] = x;
					vox[ 1 ] = y;
					vox[ 2 ] = z;
					final double[] w = new double[ 3 ];
					s.toWorld( vox, w );
					if ( ShapeSplit3D.mahalanobis2Public( w, centre, inv ) > r2 )
						continue;
					if ( s.atVoxel( x, y, z ) > level )
						pts.add( w );
				}
		double step = 0;
		for ( int d = 0; d < 3; d++ )
			step = Math.max( step, s.voxelSize( d ) * stride );
		final ShapeSplit3D.Result r = ShapeSplit3D.analyse( pts.toArray( new double[ 0 ][] ), seeds,
				frameRadius * frameRadius / ShapeSplit3D.UNIFORM_ELLIPSOID, step * step / 12 );
		if ( !r.twoFitted() )
			return new Fit3D( null, r.refusal );
		final double sep = r.separation() / frameRadius;
		if ( sep < CONVERGENCE_MIN_SEPARATION )
			return new Fit3D( null, String.format( "pieces %.2f radii apart (< %.1f)", sep, CONVERGENCE_MIN_SEPARATION ) );
		return new Fit3D( new Pieces( r.means, r.covs ), null );
	}

	/** Eigenvalues of a symmetric 3x3 matrix, ascending (closed form). */
	static double[] symmetricEigenvalues( final double[][] a )
	{
		final double p1 = a[ 0 ][ 1 ] * a[ 0 ][ 1 ] + a[ 0 ][ 2 ] * a[ 0 ][ 2 ] + a[ 1 ][ 2 ] * a[ 1 ][ 2 ];
		final double q = ( a[ 0 ][ 0 ] + a[ 1 ][ 1 ] + a[ 2 ][ 2 ] ) / 3;
		if ( p1 == 0 )
		{
			final double[] e = { a[ 0 ][ 0 ], a[ 1 ][ 1 ], a[ 2 ][ 2 ] };
			java.util.Arrays.sort( e );
			return e;
		}
		final double p2 = Math.pow( a[ 0 ][ 0 ] - q, 2 ) + Math.pow( a[ 1 ][ 1 ] - q, 2 ) + Math.pow( a[ 2 ][ 2 ] - q, 2 ) + 2 * p1;
		final double p = Math.sqrt( p2 / 6 );
		final double[][] b = new double[ 3 ][ 3 ];
		for ( int i = 0; i < 3; i++ )
			for ( int j = 0; j < 3; j++ )
				b[ i ][ j ] = ( a[ i ][ j ] - ( i == j ? q : 0 ) ) / p;
		final double r = Math.max( -1, Math.min( 1, ShapeSplit3D.det3Public( b ) / 2 ) );
		final double phi = Math.acos( r ) / 3;
		final double e1 = q + 2 * p * Math.cos( phi ), e3 = q + 2 * p * Math.cos( phi + 2 * Math.PI / 3 );
		final double[] e = { e3, 3 * q - e1 - e3, e1 };
		java.util.Arrays.sort( e );
		return e;
	}

	/** Who answers a split question: the oracle (benchmark), else the user where a dialog can be shown, else nobody. */
	static SplitQuestion splitQuestion( final ToolContext ctx )
	{
		final QuestionOracle oracle = QuestionOracle.of( ctx );
		if ( oracle != null )
			return ( t, centre, radius, cov, pieces, why ) -> {
				final Boolean two = oracle.twoCells( t, centre, cov );
				ctx.log( String.format( "  Oracle at t=%d (%.1f, %.1f): %s", t, centre[ 0 ], centre[ 1 ],
						two == null ? "cannot say -- kept" : two ? "two cells" : "one cell" ) );
				return Boolean.TRUE.equals( two ) && pieces != null ? SplitAnswer.TWO : SplitAnswer.ONE;
			};
		return ctx.canChooseImages() ? ( t, centre, radius, cov, pieces, why ) -> askSplit( ctx, t, centre, radius, cov,
				pieces, why ) : null;
	}

	/** The user's answer to a split question. */
	enum SplitAnswer
	{
		/** A: one cell, keep the detection. */
		ONE,
		/** B: two cells, split it. */
		TWO,
		/** C: neither -- the user fixed it by hand, and their edits are protected. */
		EDITED,
		/** No answer: stop asking. */
		NONE
	}

	/** Whether to split a merged detection; see {@link SplitAnswer}. */
	interface SplitQuestion
	{
		SplitAnswer ask( int t, double[] centre, double radius, double[][] cov, Pieces pieces, String why );
	}

	/** {@link ToolContext#getSharedData} key, {@link Boolean}: the user said to stop asking. */
	static final String SHARED_SPLIT_STOP_ASKING = "autocorrect.split_stop_asking";

	/**
	 * Whether the run asks the user about merged spots and divisions (the "which is right?" dialog). Off is
	 * exactly what "Stop asking" does at the first question: a merge seen where it starts is split, one
	 * seen only where it ends is left, a size jump is left, and a division whose daughters do not move
	 * apart is cut -- all without asking. The pretrained detector trial is not affected.
	 */
	public static void askAboutMergesAndDivisions( final ToolContext ctx, final boolean ask )
	{
		ctx.setSharedData( SHARED_SPLIT_STOP_ASKING, !ask );
		ctx.setSharedData( SHARED_DIVISION_STOP_ASKING, !ask );
	}

	/** Where the two cells under a merged spot are; the image read, kept apart so the surgery can be tested. */
	interface PieceFitter
	{
		/** @return piece k grown from seed k, or null when the image does not support two cells */
		Pieces fit( int t, double[] centre, double radius, double[][] cov, double[][] seeds, double frameRadius );
	}

	static int[] repairConvergedTracks( final ToolContext ctx, final int first, final int numTimepoints,
			final PieceFitter fitter, final SplitQuestion question )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		int frames = 0, episodes = 0;
		final Set< Integer > refused = new HashSet<>();
		for ( int t = first; t < numTimepoints; t++ )
		{
			while ( true )
			{
				final Spot[] onset = findConvergence( ctx, graph, model, t, refused );
				if ( onset == null )
					break;
				// Asked as well when someone can be asked: the image fit is right for 93% of these, and
				// the user would rather look at the rest than find them in the lineage afterwards. With
				// nobody to ask, or after "stop asking", it splits on its own as it always did.
				final int tOnset = onset[ 0 ].getTimepoint();
				// Read now: askAndCarry may wait on a question, and the ref may name another spot after.
				final int onsetId = onset[ 0 ].getInternalPoolIndex();
				final int n = askAndCarry( ctx, graph, onset, 1, first, numTimepoints, fitter,
						question, WHY_ONSET );
				GraphIntegrity.require( ctx, graph, "the merge repair from t=" + tOnset );
				if ( n == 0 )
					refused.add( onsetId );
				else
				{
					frames += n;
					episodes++;
				}
				for ( final Spot s : onset )
					graph.releaseRef( s );
			}
		}
		// The same merge seen from where it ENDS: the cells part and the detector sees both again. The
		// lineage says less here -- a track starting inside the merged spot, or the merged spot
		// "dividing" -- and a real division looks the same. Measured on HeLa 01, the image fit then put
		// the pieces on two cells for 69-77% (a new track starts inside) and 40-60% (it divides) of the
		// splits it made, against 85-87% from the start. Not enough to act alone: the user is shown
		// both readings and decides. With nobody to ask, nothing changes.
		if ( question != null )
		{
			final Set< Integer > endRefused = new HashSet<>();
			for ( int t = first; t < numTimepoints
					&& !Boolean.TRUE.equals( ctx.getSharedData( SHARED_SPLIT_STOP_ASKING ) ); t++ )
			{
				while ( !Boolean.TRUE.equals( ctx.getSharedData( SHARED_SPLIT_STOP_ASKING ) ) )
				{
					final Spot[] end = findMergeEnd( ctx, graph, model, t, endRefused );
					if ( end == null )
						break;
					try
					{
						endRefused.add( end[ 0 ].getInternalPoolIndex() );
						final int tEnd = end[ 0 ].getTimepoint();
						final int n = askAndCarry( ctx, graph, end, -1, first, numTimepoints, fitter, question,
								WHY_END );
						GraphIntegrity.require( ctx, graph, "the merge repair back from t=" + tEnd );
						if ( n > 0 )
						{
							frames += n;
							episodes++;
						}
					}
					finally
					{
						for ( final Spot x : end )
							graph.releaseRef( x );
					}
				}
			}
		}
		// And a merge the lineage cannot see at all -- the second cell already has a detection inside
		// this one, or was never detected -- but the size can: a detection suddenly much larger than its
		// predecessor AND large for its frame. Measured on HeLa 01 (benchmark/lineage-review/
		// measure_size_jump.py) at the bars below, 38 and 32 questions per run, 53% and 38% of them on
		// a real merge, reaching 11 of 21 and 9 of 23 merges the lineage rules miss. Asked only; never
		// acted on alone.
		if ( question != null && !Boolean.TRUE.equals( ctx.getSharedData( SHARED_SPLIT_STOP_ASKING ) ) )
		{
			final double[] bars = sizeJumpBars( graph, model, first, numTimepoints );
			final Set< Integer > sizeRefused = new HashSet<>();
			for ( int t = first; bars != null && t < numTimepoints
					&& !Boolean.TRUE.equals( ctx.getSharedData( SHARED_SPLIT_STOP_ASKING ) ); t++ )
			{
				while ( !Boolean.TRUE.equals( ctx.getSharedData( SHARED_SPLIT_STOP_ASKING ) ) )
				{
					final Spot[] jump = findSizeJump( ctx, graph, model, t, sizeRefused, bars );
					if ( jump == null )
						break;
					try
					{
						sizeRefused.add( jump[ 0 ].getInternalPoolIndex() );
						final int tJump = jump[ 0 ].getTimepoint();
						final int n = askAndCarry( ctx, graph, new Spot[] { jump[ 0 ], jump[ 1 ], null }, 1, first,
								numTimepoints, fitter, question, WHY_SIZE );
						GraphIntegrity.require( ctx, graph, "the size-jump repair from t=" + tJump );
						if ( n > 0 )
						{
							frames += n;
							episodes++;
						}
					}
					finally
					{
						for ( final Spot x : jump )
							graph.releaseRef( x );
					}
				}
			}
		}
		if ( frames > 0 )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		return new int[] { frames, episodes };
	}

	/** Spreads above the run's median growth (area over its predecessor's) for a size jump. */
	static final double SIZE_JUMP_GROWTH_MADS = 4.0;

	/** Spreads above the run's median size (area over its frame's median) for a size jump. */
	static final double SIZE_JUMP_SIZE_MADS = 1.0;

	/** Fewest detections with a predecessor to read the two bars from. */
	private static final int SIZE_JUMP_MIN_SAMPLES = 30;

	/** In-plane area of a spot's ellipse, up to a constant. */
	private static double planeArea( final Spot s, final double[][] c )
	{
		s.getCovariance( c );
		return Math.sqrt( Math.max( 0, c[ 0 ][ 0 ] * c[ 1 ][ 1 ] - c[ 0 ][ 1 ] * c[ 0 ][ 1 ] ) );
	}

	/**
	 * {growth bar, size bar}: median plus {@link #SIZE_JUMP_GROWTH_MADS} / {@link #SIZE_JUMP_SIZE_MADS}
	 * MADs of the detections with a predecessor in the range. Null with too few to read them from.
	 */
	private static double[] sizeJumpBars( final ModelGraph graph, final Model model, final int first,
			final int numTimepoints )
	{
		final List< Double > growth = new ArrayList<>(), size = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final java.util.function.Predicate< Spot > fp = DetectionTagLabels.fpTest( model );
			final Map< Integer, Double > frameArea = medianAreas( graph, first, numTimepoints );
			final Spot src = graph.vertexRef();
			final double[][] c = new double[ 3 ][ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < first || t >= numTimepoints || s.incomingEdges().size() != 1 || fp.test( s ) )
					continue;
				s.incomingEdges().iterator().next().getSource( src );
				final double a = planeArea( s, c ), pa = planeArea( src, c );
				if ( pa > 0 && frameArea.get( t ) > 0 )
				{
					growth.add( a / pa );
					size.add( a / frameArea.get( t ) );
				}
			}
			graph.releaseRef( src );
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( growth.size() < SIZE_JUMP_MIN_SAMPLES )
			return null;
		return new double[] { medianPlusMads( growth, SIZE_JUMP_GROWTH_MADS ),
				medianPlusMads( size, SIZE_JUMP_SIZE_MADS ) };
	}

	/** Median in-plane area per timepoint in the range. Caller holds the read lock. */
	private static Map< Integer, Double > medianAreas( final ModelGraph graph, final int first, final int numTimepoints )
	{
		final Map< Integer, List< Double > > by = new HashMap<>();
		final double[][] c = new double[ 3 ][ 3 ];
		for ( final Spot s : graph.vertices() )
			if ( s.getTimepoint() >= first && s.getTimepoint() < numTimepoints )
				by.computeIfAbsent( s.getTimepoint(), k -> new ArrayList<>() ).add( planeArea( s, c ) );
		final Map< Integer, Double > out = new HashMap<>();
		for ( final Map.Entry< Integer, List< Double > > e : by.entrySet() )
			out.put( e.getKey(), median( e.getValue() ) );
		for ( int t = first; t < numTimepoints; t++ )
			out.putIfAbsent( t, 0.0 );
		return out;
	}

	/** {S, P} for the first detection at {@code t} over both bars not yet looked at; fresh refs or null. */
	private static Spot[] findSizeJump( final ToolContext ctx, final ModelGraph graph, final Model model,
			final int t, final Set< Integer > refused, final double[] bars )
	{
		final AnswerMemory memory = AnswerMemory.of( ctx );
		graph.getLock().readLock().lock();
		try
		{
			final java.util.function.Predicate< Spot > fp = DetectionTagLabels.fpTest( model );
			final double frameRadius = medianRadiusAt( graph, t );
			final double frame = medianAreas( graph, t, t + 1 ).get( t );
			if ( !( frame > 0 ) )
				return null;
			final Spot src = graph.vertexRef();
			final double[][] c = new double[ 3 ][ 3 ];
			final double[] p = new double[ 3 ];
			try
			{
				for ( final Spot s : graph.vertices() )
				{
					if ( s.getTimepoint() != t || s.incomingEdges().size() != 1 || fp.test( s )
							|| refused.contains( s.getInternalPoolIndex() ) || ProtectedSpots.isProtected( ctx, model, s ) )
						continue;
					s.incomingEdges().iterator().next().getSource( src );
					if ( src.getTimepoint() != t - 1 )
						continue;
					s.localize( p );
					if ( answeredOtherThanSplit( memory, t, p, frameRadius ) )
						continue;
					final double a = planeArea( s, c ), pa = planeArea( src, c );
					if ( pa > 0 && a / pa > bars[ 0 ] && a / frame > bars[ 1 ] )
						return new Spot[] { graph.vertexRef().refTo( s ), graph.vertexRef().refTo( src ) };
				}
			}
			finally
			{
				graph.releaseRef( src );
			}
			return null;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
	}

	/**
	 * Why the merge repair leaves the detection {@code s} alone, given {@code q}, the other cell's
	 * detection in an earlier frame: the first of its own conditions that fails, in its own order. For
	 * {@link MergeReplay}, which finds such merges against the truth after a run; nothing in the pipeline
	 * calls this.
	 */
	static String convergenceVerdict( final ToolContext ctx, final Spot s, final Spot q )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final java.util.function.Predicate< Spot > fp = DetectionTagLabels.fpTest( model );
		final int t = s.getTimepoint();
		final double[] sp = new double[ 3 ], qp = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		final Spot src = graph.vertexRef(), tmp = graph.vertexRef();
		final double radius, frameRadius;
		final double[][] seeds;
		graph.getLock().readLock().lock();
		try
		{
			if ( fp.test( s ) )
				return "merged detection tagged fp";
			if ( ProtectedSpots.isProtected( ctx, model, s ) )
				return "merged detection protected";
			if ( s.incomingEdges().size() != 1 )
				return "merged detection has " + s.incomingEdges().size() + " predecessors";
			s.incomingEdges().iterator().next().getSource( src );
			if ( src.getTimepoint() != t - 1 )
				return "merged detection's predecessor is " + ( t - src.getTimepoint() ) + " frames back";
			if ( fp.test( q ) )
				return "other cell's detection tagged fp";
			s.localize( sp );
			s.getCovariance( cov );
			q.localize( qp );
			final double[][] c2 = new double[ 3 ][ 3 ];
			final boolean grew = spotSize( s, c2 ) > spotSize( src, c2 );
			final double[] carried = grew && !volumetric() ? carriedSeeds( sp, src, q )[ 1 ] : qp;
			if ( !q.outgoingEdges().isEmpty() )
			{
				q.outgoingEdges().iterator().next().getTarget( tmp );
				if ( tmp.getTimepoint() == t && insideInPlaneOf( s, tmp ) )
				{
					// Sizes and offsets in the predecessor's radius: how far the merged detection grew, and
					// where its centre sits between its own cell (P) and the neighbour's detection (N).
					final double rs = Math.sqrt( s.getBoundingSphereRadiusSquared() );
					final double rp = Math.sqrt( src.getBoundingSphereRadiusSquared() );
					final double rn = Math.sqrt( tmp.getBoundingSphereRadiusSquared() );
					return String.format( "other cell's track goes on (to t=%d, inside the merged detection)"
							+ " | rS/rP=%.2f rN/rP=%.2f |S-P|/rP=%.2f |S-N|/rP=%.2f |P-N|/rP=%.2f", t, rs / rp, rn / rp,
							dist( s, src ) / rp, dist( s, tmp ) / rp, dist( src, tmp ) / rp );
				}
				if ( q.outgoingEdges().size() != 1 || tmp.getTimepoint() != t )
					return "other cell's track goes on (to t=" + tmp.getTimepoint() + ")";
				final double[] to = new double[ 3 ];
				tmp.localize( to );
				if ( !grew )
					return "other cell's track goes on (outside), and the merged detection is no larger than its predecessor";
				if ( !insideSpot( sp, cov, carried ) )
					return "other cell's track goes on (outside), and its carried position is outside the merged detection";
				if ( dist2( to, qp ) <= dist2( carried, qp ) )
					return "other cell's track goes on (outside), no further than its carried position";
			}
			if ( t - q.getTimepoint() > CONVERGENCE_MAX_GAP )
				return "other cell's track ended " + ( t - q.getTimepoint() ) + " frames back";
			if ( t - q.getTimepoint() > 1 && coveredBetween( graph, q, t ) )
				return "other cell's position covered in between";
			if ( !insideSpot( sp, cov, qp ) && !insideSpot( sp, cov, carried ) )
				return "other cell's centre, last or carried, lies outside the merged detection";
			radius = Math.sqrt( s.getBoundingSphereRadiusSquared() );
			frameRadius = medianRadiusAt( graph, t );
			seeds = carriedSeeds( sp, src, q );
		}
		finally
		{
			graph.releaseRef( tmp );
			graph.releaseRef( src );
			graph.getLock().readLock().unlock();
		}
		final Pieces pieces;
		if ( volumetric() )
		{
			final Fit3D f = fitPieces3D( ctx, t, sp, cov, seeds, frameRadius );
			if ( f.pieces == null )
				return "image fit: " + f.refusal;
			pieces = f.pieces;
		}
		else
		{
			final String fit = fitRefusal( ctx, t, sp, radius, seeds, frameRadius );
			if ( fit != null )
				return "image fit: " + fit;
			pieces = fitPieces( ctx, t, sp, radius, cov, seeds, frameRadius );
		}
		if ( pieces == null )
			return "image fit: refused";
		graph.getLock().readLock().lock();
		try
		{
			if ( landsOnAnotherDetection( graph, t, s, pieces ) == null )
				return "a piece lands on another detection";
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return "repairable now (the merge arose after the repair ran)";
	}

	private static double dist( final Spot a, final Spot b )
	{
		double d = 0;
		for ( int k = 0; k < 3; k++ )
			d += Math.pow( a.getDoublePosition( k ) - b.getDoublePosition( k ), 2 );
		return Math.sqrt( d );
	}

	private static boolean insideInPlaneOf( final Spot s, final Spot p )
	{
		final double[] sp = new double[ 3 ], pp = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		s.localize( sp );
		s.getCovariance( cov );
		p.localize( pp );
		return insideSpot( sp, cov, pp );
	}

	/** The reason {@link #fitPieces} would return null, or null when it would not. */
	private static String fitRefusal( final ToolContext ctx, final int t, final double[] centre, final double radius,
			final double[][] seeds, final double frameRadius )
	{
		final double[] frame = frameLevelStats( ctx, t );
		final Sampler full = fullResolutionSampler( ctx, t );
		if ( frame == null || full == null || !( frameRadius > 0 ) )
			return "no frame statistics";
		final double level = frame[ 0 ] + SHAPE_LEVEL_SIGMA * frame[ 1 ];
		try
		{
			final ShapeWindow win = ShapeWindow.read( full, new double[] { centre[ 0 ], centre[ 1 ], centre[ 2 ], radius } );
			if ( win == null )
				return "no image window";
			final double[][] seedPx = new double[ 2 ][];
			final double[] v = new double[ 3 ];
			for ( int k = 0; k < 2; k++ )
			{
				full.toVoxel( seeds[ k ], v );
				seedPx[ k ] = new double[] { v[ 0 ] - win.x0, v[ 1 ] - win.y0 };
			}
			final ShapeSplit.Result shape = ShapeSplit.analyse( win.pixels, win.w, win.h, level, win.cx, win.cy,
					radius / win.step, seedPx );
			if ( shape == null )
				return "no object above the level";
			if ( !shape.twoFitted() )
				return "one component collapsed";
			final double sep = shape.separation() * win.step / frameRadius;
			if ( sep < CONVERGENCE_MIN_SEPARATION )
				return String.format( "pieces %.2f radii apart (< %.1f)", sep, CONVERGENCE_MIN_SEPARATION );
			return null;
		}
		catch ( final RuntimeException e )
		{
			return "image window unavailable";
		}
	}

	/**
	 * How far back the second track may have ended before the merge: the gap linking reaches, measured
	 * 99.9% safe up to 3 frames (see the gap-link measurements).
	 */
	static final int CONVERGENCE_MAX_GAP = 3;

	/** The position of {@code q}'s single successor when that is at {@code t} and it has no other; else null. */
	private static double[] onlySuccessorAt( final ModelGraph graph, final Spot q, final int t )
	{
		if ( q.outgoingEdges().size() != 1 )
			return null;
		final Spot r = graph.vertexRef();
		try
		{
			q.outgoingEdges().iterator().next().getTarget( r );
			if ( r.getTimepoint() != t )
				return null;
			final double[] p = new double[ 3 ];
			r.localize( p );
			return p;
		}
		finally
		{
			graph.releaseRef( r );
		}
	}

	/** Whether any detection between {@code q}'s frame and {@code t} (both excluded) covers its position. */
	private static boolean coveredBetween( final ModelGraph graph, final Spot q, final int t )
	{
		final double[] qp = new double[ 3 ], sp = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		q.localize( qp );
		final int from = q.getTimepoint();
		for ( final Spot s : graph.vertices() )
		{
			final int ts = s.getTimepoint();
			if ( ts <= from || ts >= t )
				continue;
			s.localize( sp );
			s.getCovariance( cov );
			if ( insideSpot( sp, cov, qp ) )
				return true;
		}
		return false;
	}

	/** {S, P, Q} for the first unrefused onset at {@code t}, as fresh refs; null when there is none. */
	private static Spot[] findConvergence( final ToolContext ctx, final ModelGraph graph, final Model model,
			final int t, final Set< Integer > refused )
	{
		final AnswerMemory memory = AnswerMemory.of( ctx );
		graph.getLock().readLock().lock();
		try
		{
			final java.util.function.Predicate< Spot > fp = DetectionTagLabels.fpTest( model );
			final double frameRadius = medianRadiusAt( graph, t );
			// Copies: the vertex iterator hands back ONE reused object, so a list of what it returned
			// is a list of the last spot visited.
			// The second cell is often missed for a frame or two before the merge, so its track ended
			// further back: on HeLa 01, 13 of the 46 merges this pass did not repair began like that (the
			// user's t=40..42). Up to CONVERGENCE_MAX_GAP frames back, nearer first, and only when no
			// detection covers that cell's last position in between: the cell was missed, not taken over.
			final List< Spot > ended = new ArrayList<>();
			// Where each candidate's track went on to, for one whose link left the merge (null: it ended).
			final List< double[] > wentTo = new ArrayList<>();
			for ( int k = 1; k <= CONVERGENCE_MAX_GAP && t - k >= 0; k++ )
				for ( final Spot q : graph.vertices() )
					if ( q.getTimepoint() == t - k && !fp.test( q ) )
					{
						final double[] to = k == 1 ? onlySuccessorAt( graph, q, t ) : null;
						if ( !q.outgoingEdges().isEmpty() && to == null )
							continue;
						// Copied before coveredBetween iterates the vertices again.
						final Spot copy = graph.vertexRef().refTo( q );
						if ( k == 1 || !coveredBetween( graph, copy, t ) )
						{
							ended.add( copy );
							wentTo.add( to );
						}
						else
							graph.releaseRef( copy );
					}
			if ( ended.isEmpty() )
				return null;
			final Spot src = graph.vertexRef();
			final double[] sp = new double[ 3 ], qp = new double[ 3 ], pp = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ], cov2 = new double[ 3 ][ 3 ];
			try
			{
				for ( final Spot s : graph.vertices() )
				{
					if ( s.getTimepoint() != t || s.incomingEdges().size() != 1
							|| refused.contains( s.getInternalPoolIndex() ) || fp.test( s )
							|| ProtectedSpots.isProtected( ctx, model, s ) )
						continue;
					s.incomingEdges().iterator().next().getSource( src );
					if ( src.getTimepoint() != t - 1 )
						continue;
					s.localize( sp );
					if ( answeredOtherThanSplit( memory, t, sp, frameRadius ) )
						continue;
					s.getCovariance( cov );
					src.localize( pp );
					// The carried position assumes S holds two cells where P held one, so S is the larger. A
					// single cell following a merged detection is the smaller, and carrying from that merged P
					// lands the neighbour on it.
					final boolean grew = spotSize( s, cov ) > spotSize( src, cov2 );
					s.getCovariance( cov );
					for ( int i = 0; i < ended.size(); i++ )
					{
						final Spot q = ended.get( i );
						q.localize( qp );
						// Where the other cell is now: its last position, carried by the merged detection's
						// motion as the repair carries it (the pair's midpoint moved to S's centre). Cells move,
						// so their last position alone missed 9 of 41 merges on HeLa 01.
						// Not on a stack: replayed on Fluo-N3DH-CE 01, the carried position put pieces on cells that
						// had a detection (duplicates 595 -> 816, LNK 0.341 -> 0.333); from the last position alone
						// the repair added none of that (651, LNK 0.342). Thin planes make the merged centre's z
						// a poor guide to where the pair went.
						final double[] carried = grew && !volumetric() ? carriedSeeds( sp, pp, qp )[ 1 ] : qp;
						if ( !insideSpot( sp, cov, qp ) && !insideSpot( sp, cov, carried ) )
							continue;
						// A track that went on: only when the merged detection holds where the cell should be
						// and the link went somewhere outside it, further than that -- the detector put one
						// spot over both cells and the linker sent the second track to another detection
						// (10 of 41 merges on HeLa 01).
						final double[] to = wentTo.get( i );
						if ( to != null && ( !grew || !insideSpot( sp, cov, carried ) || insideSpot( sp, cov, to )
								|| dist2( to, qp ) <= dist2( carried, qp ) ) )
							continue;
						final Spot[] out = { graph.vertexRef(), graph.vertexRef(), graph.vertexRef() };
						out[ 0 ].refTo( s );
						out[ 1 ].refTo( src );
						out[ 2 ].refTo( q );
						return out;
					}
				}
			}
			finally
			{
				graph.releaseRef( src );
				for ( final Spot q : ended )
					graph.releaseRef( q );
			}
			return null;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
	}

	/**
	 * {S, N1, N2} for the first merge end at {@code t} not yet looked at: S has one successor N1 and
	 * another detection N2 with no predecessor starts inside it, or S's two successors both lie inside
	 * it. Fresh refs; null when there is none.
	 */
	private static Spot[] findMergeEnd( final ToolContext ctx, final ModelGraph graph, final Model model,
			final int t, final Set< Integer > refused )
	{
		final AnswerMemory memory = AnswerMemory.of( ctx );
		graph.getLock().readLock().lock();
		try
		{
			final java.util.function.Predicate< Spot > fp = DetectionTagLabels.fpTest( model );
			final double frameRadius = medianRadiusAt( graph, t );
			final List< Spot > starts = new ArrayList<>();
			for ( final Spot n : graph.vertices() )
				if ( n.getTimepoint() == t + 1 && n.incomingEdges().isEmpty() && !fp.test( n ) )
					starts.add( graph.vertexRef().refTo( n ) );
			final Spot n1 = graph.vertexRef(), n2 = graph.vertexRef();
			final double[] sp = new double[ 3 ], np = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			try
			{
				for ( final Spot s : graph.vertices() )
				{
					if ( s.getTimepoint() != t || refused.contains( s.getInternalPoolIndex() ) || fp.test( s )
							|| ProtectedSpots.isProtected( ctx, model, s ) )
						continue;
					final int out = s.outgoingEdges().size();
					if ( out != 1 && out != 2 )
						continue;
					s.localize( sp );
					if ( answeredOtherThanSplit( memory, t, sp, frameRadius ) )
						continue;
					s.getCovariance( cov );
					final java.util.Iterator< Link > it = s.outgoingEdges().iterator();
					it.next().getTarget( n1 );
					if ( n1.getTimepoint() != t + 1 )
						continue;
					boolean found = false;
					if ( out == 2 )
					{
						it.next().getTarget( n2 );
						n1.localize( np );
						final boolean in1 = insideSpot( sp, cov, np );
						n2.localize( np );
						found = n2.getTimepoint() == t + 1 && in1 && insideSpot( sp, cov, np );
					}
					else
						for ( final Spot n : starts )
						{
							n.localize( np );
							if ( insideSpot( sp, cov, np ) )
							{
								n2.refTo( n );
								found = true;
								break;
							}
						}
					if ( found )
						return new Spot[] { graph.vertexRef().refTo( s ), graph.vertexRef().refTo( n1 ),
								graph.vertexRef().refTo( n2 ) };
				}
			}
			finally
			{
				graph.releaseRef( n1 );
				graph.releaseRef( n2 );
				for ( final Spot n : starts )
					graph.releaseRef( n );
			}
			return null;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
	}

	/**
	 * Fits the merge {S, N1, N2} -- N1 and N2 the two cells the frame before ({@code dir} +1) or after
	 * (-1) -- asks, and on "two cells" repairs it from S in direction {@code dir}. Remembers "one cell"
	 * run-wide, and a closed dialog as "stop asking".
	 *
	 * @return frames repaired
	 */
	private static int askAndCarry( final ToolContext ctx, final ModelGraph graph, final Spot[] end,
			final int dir, final int first, final int numTimepoints, final PieceFitter fitter,
			final SplitQuestion question, final String why )
	{
		// Forward (a merge seen where it starts) acts on its own when nobody can be asked; backward (seen
		// only where it ends) never does, and neither does one flagged by size alone (end[2] null).
		final boolean lineage = end[ 2 ] != null;
		// find* collected these under a lock it has since released: a spot changed in the viewer in
		// between is not the merge that was found. See GraphWatch.
		if ( anyChanged( graph, end ) )
			return 0;
		final boolean asking = question != null
				&& !Boolean.TRUE.equals( ctx.getSharedData( SHARED_SPLIT_STOP_ASKING ) );
		if ( !asking )
			return dir > 0 && lineage
					? carryConvergence( ctx, graph, end[ 0 ], end[ 1 ], end[ 2 ], dir, first, numTimepoints, fitter,
							false )
					: 0;
		final int t;
		final double[] centre = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		final double radius, frameRadius;
		final double[][] seeds;
		final double[] predecessorAt = new double[ 3 ];
		final Where[] where = new Where[ end.length ];
		graph.getLock().readLock().lock();
		try
		{
			for ( int k = 0; k < end.length; k++ )
				where[ k ] = end[ k ] == null ? null : new Where( end[ k ] );
			t = end[ 0 ].getTimepoint();
			end[ 0 ].localize( centre );
			end[ 0 ].getCovariance( cov );
			radius = Math.sqrt( end[ 0 ].getBoundingSphereRadiusSquared() );
			frameRadius = medianRadiusAt( graph, t );
			seeds = lineage ? carriedSeeds( centre, end[ 1 ], end[ 2 ] ) : null;
			if ( !lineage )
				end[ 1 ].localize( predecessorAt );
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		// When the image fit finds no two cells the lineage still says two, so the user is asked with A
		// and C only. A piece landing inside another detection is NOT asked about: scored with
		// duplicates counted apart (benchmark/lineage-review/RESULTS.md), 2 of 29 such splits over three
		// runs were right and 14 put a second detection on a cell that had one. Asking about them for
		// one release showed the user exactly that, a split whose second piece sat on a detected cell.
		final Pieces fitted = nearerFirst( fitter.fit( t, centre, radius, cov, seeds, frameRadius ),
				lineage ? null : predecessorAt );
		// The fit read the image without the lock; the test below reads the graph through end[0].
		if ( anyChanged( graph, end ) )
			return 0;
		if ( fitted != null && landsOnAnotherDetection( graph, t, end[ 0 ], fitted ) == null )
			return 0;
		final String doubt = fitted == null ? " " + DOUBT_NO_FIT : "";
		final double[][] cells = fitted != null ? fitted.centres : seeds;
		final AnswerMemory memory = AnswerMemory.of( ctx );
		final AnswerMemory.Said before = memory.recall( AnswerMemory.Topic.CELLS, t, centre, frameRadius );
		final boolean remembered = before == AnswerMemory.Said.SPLIT && fitted != null;
		final SplitAnswer asked = remembered ? SplitAnswer.TWO
				: before != null ? SplitAnswer.EDITED // answered, and not with a split there is a fit for
						: question.ask( t, centre, radius, cov, fitted, why + doubt );
		if ( before != null )
		{
			ctx.log( String.format( "  Split question at t=%d (%.1f, %.1f): already answered (%s) -- %s.", t, centre[ 0 ],
					centre[ 1 ], before, remembered ? "split again without asking" : "left as it is" ) );
			if ( !remembered )
				return 0;
		}
		// The question does not block the viewer: whatever the user changed meanwhile, act only on the
		// spots that are still where they were, through refs pointed at them again.
		if ( asked != SplitAnswer.EDITED )
		{
			boolean gone = false;
			graph.getLock().readLock().lock();
			try
			{
				for ( int k = 0; k < end.length && !gone; k++ )
					gone = where[ k ] != null && !where[ k ].refind( graph, end[ k ] );
			}
			finally
			{
				graph.getLock().readLock().unlock();
			}
			if ( gone )
			{
				ctx.log( String.format( "  Split question at t=%d (%.1f, %.1f): the detections changed while the "
						+ "question was open (an edit in the viewer); this merge is left as it is now.", t, centre[ 0 ],
						centre[ 1 ] ) );
				if ( asked == SplitAnswer.NONE )
					ctx.setSharedData( SHARED_SPLIT_STOP_ASKING, Boolean.TRUE );
				return 0;
			}
		}
		// There is no split to apply without a fit, whatever came back.
		final SplitAnswer answer = asked == SplitAnswer.TWO && fitted == null ? SplitAnswer.ONE : asked;
		if ( answer == SplitAnswer.NONE )
		{
			ctx.setSharedData( SHARED_SPLIT_STOP_ASKING, Boolean.TRUE );
			ctx.log( String.format( "  Split question at t=%d (%.1f, %.1f): no answer -- no more questions this "
					+ "run; merges seen where they start are split automatically, those seen only where they "
					+ "end are left.", t, centre[ 0 ], centre[ 1 ] ) );
			return dir > 0 && lineage
					? carryConvergence( ctx, graph, end[ 0 ], end[ 1 ], end[ 2 ], dir, first, numTimepoints, fitter,
							false )
					: 0;
		}
		if ( !remembered )
			ctx.log( String.format( "  Split question at t=%d (%.1f, %.1f): you chose %s.", t, centre[ 0 ], centre[ 1 ],
					answer == SplitAnswer.TWO ? "B, two cells" : answer == SplitAnswer.ONE ? "A, one cell"
							: "C, fixed by hand" ) );
		if ( answer == SplitAnswer.EDITED )
		{
			// The graph is the user's now: the refs this was called with may point at spots they
			// deleted. Remember the place, and touch nothing else.
			memory.remember( AnswerMemory.Topic.CELLS, t, centre, AnswerMemory.Said.HAND, frameRadius );
			return 0;
		}
		if ( answer == SplitAnswer.ONE )
		{
			memory.remember( AnswerMemory.Topic.CELLS, t, centre, AnswerMemory.Said.KEEP, frameRadius );
			// ...and the rest of the same merge, so it is not asked about again from its other end.
			if ( cells != null )
				keepAlongMerge( graph, end[ 0 ], cells, dir, memory );
			return 0;
		}
		memory.remember( AnswerMemory.Topic.CELLS, t, centre, AnswerMemory.Said.SPLIT, frameRadius );
		// The user has seen this split and said yes, so the neighbour guard does not overrule them on
		// this frame; later frames are carried under it as usual.
		return lineage
				? carryConvergence( ctx, graph, end[ 0 ], end[ 1 ], end[ 2 ], dir, first, numTimepoints, fitter, true )
				: carryConvergence( ctx, graph, end[ 0 ], end[ 1 ], null, fitted.centres, dir, first, numTimepoints,
						fitter, true );
	}

	/**
	 * {@code pieces} with the one nearer {@code to} first, so it continues the track that came from
	 * there; unchanged when {@code to} is null.
	 */
	private static Pieces nearerFirst( final Pieces pieces, final double[] to )
	{
		if ( pieces == null || to == null || dist2( pieces.centres[ 0 ], to ) <= dist2( pieces.centres[ 1 ], to ) )
			return pieces;
		return new Pieces( new double[][] { pieces.centres[ 1 ], pieces.centres[ 0 ] },
				new double[][][] { pieces.covs[ 1 ], pieces.covs[ 0 ] } );
	}

	/** Why a detection flagged by its size alone is asked about. */
	static final String WHY_SIZE = "This detection is much larger than the one before it on its track, and "
			+ "large for this frame -- the usual look of two cells under one detection. Nothing in the lineage "
			+ "says so, so B splits it along its own outline.";

	/** Appended to the question when the image fit found no two cells. */
	static final String DOUBT_NO_FIT = "The image fit could not separate two cells under it, so there is "
			+ "no B to show: keep it (A), or fix it by hand (C).";



	/**
	 * Marks as kept the detections that continue the merge at {@code s} in direction {@code dir}:
	 * followed along single links while each still holds both cells' positions, carried by its own
	 * motion -- the same test a split is carried forward by.
	 */
	private static void keepAlongMerge( final ModelGraph graph, final Spot s, final double[][] cells, final int dir,
			final AnswerMemory memory )
	{
		graph.getLock().readLock().lock();
		final Spot cur = graph.vertexRef().refTo( s ), nxt = graph.vertexRef();
		try
		{
			double[][] at = cells;
			final double[] cp = new double[ 3 ], np = new double[ 3 ];
			final double[][] ncov = new double[ 3 ][ 3 ];
			while ( true )
			{
				final int links = dir > 0 ? cur.outgoingEdges().size() : cur.incomingEdges().size();
				if ( links != 1 )
					return;
				if ( dir > 0 )
					cur.outgoingEdges().iterator().next().getTarget( nxt );
				else
					cur.incomingEdges().iterator().next().getSource( nxt );
				cur.localize( cp );
				nxt.localize( np );
				nxt.getCovariance( ncov );
				at = shifted( at, new double[] { np[ 0 ] - cp[ 0 ], np[ 1 ] - cp[ 1 ], volumetric() ? np[ 2 ] - cp[ 2 ] : 0 } );
				if ( !insideSpot( np, ncov, at[ 0 ] ) || !insideSpot( np, ncov, at[ 1 ] ) )
					return;
				memory.remember( AnswerMemory.Topic.CELLS, nxt.getTimepoint(), np, AnswerMemory.Said.KEEP,
						medianRadiusAt( graph, nxt.getTimepoint() ) );
				cur.refTo( nxt );
			}
		}
		finally
		{
			graph.releaseRef( cur );
			graph.releaseRef( nxt );
			graph.getLock().readLock().unlock();
		}
	}

	/**
	 * Whether the user already answered about the detection at {@code (t, p)} with anything but "two
	 * cells" -- which is not skipped: it is applied again without asking (see askAndCarry).
	 */
	private static boolean answeredOtherThanSplit( final AnswerMemory memory, final int t, final double[] p,
			final double frameRadius )
	{
		final AnswerMemory.Said said = memory.recall( AnswerMemory.Topic.CELLS, t, p, frameRadius );
		return said != null && said != AnswerMemory.Said.SPLIT;
	}

	/** Why a merge seen where it starts is asked about, for the question's text. */
	static final String WHY_ONSET = "Two tracks ran into this one detection: a cell in the frame before "
			+ "ended next to it. B splits it into the two cells the frame before holds.";

	/** Why a merge seen only where it ends is asked about. */
	static final String WHY_END = "The cells part again after this detection, but nothing in the frames "
			+ "before shows two cells, and a real division looks the same. B splits it into the two cells "
			+ "the frame after holds.";

	/** The two cells' positions carried onto {@code centre}: both shift by it minus their midpoint. */
	private static double[][] carriedSeeds( final double[] centre, final Spot p, final Spot q )
	{
		final double[] pp = new double[ 3 ], pq = new double[ 3 ];
		p.localize( pp );
		q.localize( pq );
		return carriedSeeds( centre, pp, pq );
	}

	private static double[][] carriedSeeds( final double[] centre, final double[] pp, final double[] pq )
	{
		final double dx = centre[ 0 ] - ( pp[ 0 ] + pq[ 0 ] ) / 2, dy = centre[ 1 ] - ( pp[ 1 ] + pq[ 1 ] ) / 2;
		if ( volumetric() )
		{
			final double dz = centre[ 2 ] - ( pp[ 2 ] + pq[ 2 ] ) / 2;
			return new double[][] { { pp[ 0 ] + dx, pp[ 1 ] + dy, pp[ 2 ] + dz }, { pq[ 0 ] + dx, pq[ 1 ] + dy, pq[ 2 ] + dz } };
		}
		return new double[][] { { pp[ 0 ] + dx, pp[ 1 ] + dy, centre[ 2 ] }, { pq[ 0 ] + dx, pq[ 1 ] + dy, centre[ 2 ] } };
	}

	/**
	 * Whether the merge repair, {@code relink_free} or {@code staying_divisions} is working on a stack: set for
	 * the length of one call of it ({@link #withGeometry}). Their geometry is in
	 * plane on single-plane data (where it was measured) and in 3-D on a stack, where a detection's in-plane
	 * ellipse says nothing about which plane a cell is in.
	 */
	private static final ThreadLocal< Boolean > CONVERGENCE_VOLUMETRIC = ThreadLocal.withInitial( () -> Boolean.FALSE );

	private static boolean volumetric()
	{
		return CONVERGENCE_VOLUMETRIC.get();
	}

	/** {@link #insideInPlane} on single-plane data; inside the full ellipsoid on a stack. */
	static boolean insideSpot( final double[] c, final double[][] cov, final double[] p )
	{
		if ( !volumetric() )
			return insideInPlane( c, cov, p );
		final double[][] inv = ShapeSplit3D.invert3( cov );
		if ( inv == null )
			return false;
		final double dx = p[ 0 ] - c[ 0 ], dy = p[ 1 ] - c[ 1 ], dz = p[ 2 ] - c[ 2 ];
		return dx * ( inv[ 0 ][ 0 ] * dx + inv[ 0 ][ 1 ] * dy + inv[ 0 ][ 2 ] * dz )
				+ dy * ( inv[ 1 ][ 0 ] * dx + inv[ 1 ][ 1 ] * dy + inv[ 1 ][ 2 ] * dz )
				+ dz * ( inv[ 2 ][ 0 ] * dx + inv[ 2 ][ 1 ] * dy + inv[ 2 ][ 2 ] * dz ) <= 1.0;
	}

	/** A spot's size for comparing two: its in-plane area on single-plane data, its volume on a stack (both up to a constant). */
	private static double spotSize( final Spot s, final double[][] c )
	{
		if ( !volumetric() )
			return planeArea( s, c );
		s.getCovariance( c );
		return Math.sqrt( Math.max( 0, ShapeSplit3D.det3Public( c ) ) );
	}

	/** Whether {@code p} lies inside the in-plane ellipse of a spot at {@code c} with {@code cov}. */
	static boolean insideInPlane( final double[] c, final double[][] cov, final double[] p )
	{
		final double a = cov[ 0 ][ 0 ], b = cov[ 0 ][ 1 ], d = cov[ 1 ][ 1 ];
		final double det = a * d - b * b;
		if ( !( det > 0 ) )
			return false;
		final double dx = p[ 0 ] - c[ 0 ], dy = p[ 1 ] - c[ 1 ];
		return ( d * dx * dx - 2 * b * dx * dy + a * dy * dy ) / det <= 1.0;
	}

	/**
	 * Where a spot was, so a ref held across a stretch without the lock -- a question the user answers
	 * while editing in the viewer, an image fit -- is pointed at it again, or found gone. A ref to a
	 * deleted spot is not "empty": its slot goes to the next spot added, so a repair using it splits or
	 * links some other spot (a test made one link a piece to itself; run 20261008-000342 stopped on
	 * damaged links at t=45 during the repair from t=76).
	 */
	static final class Where
	{
		final int t;

		final double[] p = new double[ 3 ];

		Where( final Spot s )
		{
			t = s.getTimepoint();
			s.localize( p );
		}

		/** Points {@code ref} at the live spot at the same timepoint and position; false when none. Caller holds a lock. */
		boolean refind( final ModelGraph graph, final Spot ref )
		{
			final double[] q = new double[ 3 ];
			for ( final Spot s : graph.vertices() )
				if ( s.getTimepoint() == t )
				{
					s.localize( q );
					// All three coordinates, always: two cells stacked in z share x and y, and matching on
					// those alone swapped the two tracks of a merge split in z.
					if ( Math.abs( p[ 0 ] - q[ 0 ] ) <= 1e-6 && Math.abs( p[ 1 ] - q[ 1 ] ) <= 1e-6
							&& Math.abs( p[ 2 ] - q[ 2 ] ) <= 1e-6 )
					{
						ref.refTo( s );
						return true;
					}
				}
			return false;
		}
	}

	/** Whether any non-null ref in {@code refs} names a slot changed in the viewer this step (GraphWatch). */
	private static boolean anyChanged( final ModelGraph graph, final Spot... refs )
	{
		for ( final Spot r : refs )
			if ( r != null && GraphWatch.dirty( graph, r ) )
				return true;
		return false;
	}

	/** {@link Where#refind} for each (ref, where) pair; a null where is skipped. Takes the read lock (reentrant). */
	private static boolean refindAll( final ModelGraph graph, final Object... refAndWhere )
	{
		graph.getLock().readLock().lock();
		try
		{
			for ( int k = 0; k < refAndWhere.length; k += 2 )
				if ( refAndWhere[ k + 1 ] != null && !( ( Where ) refAndWhere[ k + 1 ] ).refind( graph, ( Spot ) refAndWhere[ k ] ) )
					return false;
			return true;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
	}

	/**
	 * Repairs the merge at {@code s0}, frame by frame in the direction {@code dir}: forward (+1) from
	 * where it starts, with {@code p0} and {@code q0} the two cells the frame before; or backward (-1)
	 * from where it ends, with them the two cells the frame after. Refs are borrowed, not released.
	 *
	 * @param lo first timepoint this may change
	 * @param numTimepoints one past the last
	 * @return frames repaired; 0 means the first frame was refused and nothing changed
	 */
	private static int carryConvergence( final ToolContext ctx, final ModelGraph graph, final Spot s0,
			final Spot p0, final Spot q0, final int dir, final int lo, final int numTimepoints,
			final PieceFitter fitter, final boolean trustFirst )
	{
		return carryConvergence( ctx, graph, s0, p0, q0, null, dir, lo, numTimepoints, fitter, trustFirst );
	}

	/**
	 * As above, with the first frame's seeds given rather than carried from {@code p0} and {@code q0},
	 * and {@code q0} allowed to be null: then only piece 0 is linked to the side the repair came from,
	 * and piece 1 starts (or ends) a track there.
	 */
	private static int carryConvergence( final ToolContext ctx, final ModelGraph graph, final Spot s0,
			final Spot p0, final Spot q0, final double[][] givenSeeds, final int dir, final int lo,
			final int numTimepoints, final PieceFitter fitter, final boolean trustFirst )
	{
		final Spot cur = graph.vertexRef(), a = graph.vertexRef(), b = graph.vertexRef();
		final Spot prevA = graph.vertexRef(), prevB = graph.vertexRef(), tmp = graph.vertexRef();
		final Link edge = graph.edgeRef();
		final List< Spot > succs = new ArrayList<>();
		int repaired = 0;
		boolean hasPrevB = q0 != null;
		try
		{
			double[][] seeds;
			graph.getLock().readLock().lock();
			try
			{
				cur.refTo( s0 );
				prevA.refTo( p0 );
				if ( q0 != null )
					prevB.refTo( q0 );
				final double[] ps = new double[ 3 ];
				s0.localize( ps );
				// S's own motion carries the pair: both shift by S's centre minus their midpoint.
				seeds = givenSeeds != null ? givenSeeds : carriedSeeds( ps, p0, q0 );
			}
			finally
			{
				graph.getLock().readLock().unlock();
			}
			while ( true )
			{
				// Read the merged spot and its frame, then fit with no lock held.
				final int t;
				final double[] centre = new double[ 3 ];
				final double[][] cov = new double[ 3 ][ 3 ];
				final double radius, frameRadius;
				final Where whereCur, wherePrevA, wherePrevB;
				graph.getLock().readLock().lock();
				try
				{
					t = cur.getTimepoint();
					cur.localize( centre );
					cur.getCovariance( cov );
					radius = Math.sqrt( cur.getBoundingSphereRadiusSquared() );
					frameRadius = medianRadiusAt( graph, t );
					whereCur = new Where( cur );
					wherePrevA = new Where( prevA );
					wherePrevB = hasPrevB ? new Where( prevB ) : null;
				}
				finally
				{
					graph.getLock().readLock().unlock();
				}
				final Pieces fit = fitter.fit( t, centre, radius, cov, seeds, frameRadius );
				// The fit ran without the lock, so the user may have edited meanwhile.
				if ( !refindAll( graph, cur, whereCur, prevA, wherePrevA, prevB, hasPrevB ? wherePrevB : null ) )
				{
					ctx.log( String.format( "  Merge repair at t=%d (%.1f, %.1f): the detections changed while the image "
							+ "was read (an edit in the viewer); stopped here.", t, centre[ 0 ], centre[ 1 ] ) );
					break;
				}
				final Pieces pieces = trustFirst && repaired == 0 ? fit : landsOnAnotherDetection( graph, t, cur, fit );
				if ( pieces == null )
					break;

				graph.getLock().writeLock().lock();
				final double[] motion;
				final boolean carry;
				try
				{
					if ( !refindAll( graph, cur, whereCur, prevA, wherePrevA, prevB, hasPrevB ? wherePrevB : null ) )
						break;
					// What S led to in the direction of travel, before S and its links go.
					for ( final Spot s : succs )
						graph.releaseRef( s );
					succs.clear();
					if ( dir > 0 )
						for ( final Link e : cur.outgoingEdges() )
							succs.add( e.getTarget( graph.vertexRef() ) );
					else
						for ( final Link e : cur.incomingEdges() )
							succs.add( e.getSource( graph.vertexRef() ) );
					// Either piece can be withdrawn (it landed on a protected spot), and then its slot is free
					// for the other: both refs would name one spot. Undo the one that stood, and stop here.
					if ( GraphEdits.addSpot( ctx, graph, a, t, pieces.centres[ 0 ], pieces.covs[ 0 ],
							GraphEdits.Reason.SPLIT ) == null )
						break;
					if ( GraphEdits.addSpot( ctx, graph, b, t, pieces.centres[ 1 ], pieces.covs[ 1 ],
							GraphEdits.Reason.SPLIT ) == null )
					{
						GraphEdits.removeSpot( ctx, graph, a, GraphEdits.Reason.SPLIT );
						break;
					}
					if ( !GraphEdits.removeSpot( ctx, graph, cur, GraphEdits.Reason.SPLIT ) )
					{
						// The merged detection could not go (protected since, or changed in the viewer):
						// leave it, and take the two pieces back out.
						GraphEdits.removeSpot( ctx, graph, b, GraphEdits.Reason.SPLIT );
						GraphEdits.removeSpot( ctx, graph, a, GraphEdits.Reason.SPLIT );
						break;
					}
					link( ctx, graph, prevA, a, dir, edge );
					if ( hasPrevB )
					{
						// A track the linker had sent elsewhere (findConvergence takes one only when the merged
						// detection holds where its cell should be): that link goes, the cell's piece is its
						// successor. What it led to starts a track of its own.
						if ( dir > 0 && repaired == 0 )
							for ( final Link e : new ArrayList<>( outgoingCopies( graph, prevB ) ) )
							{
								GraphEdits.removeLink( ctx, graph, e, GraphEdits.Reason.TOPOLOGY );
								graph.releaseRef( e );
							}
						link( ctx, graph, prevB, b, dir, edge );
					}
					repaired++;

					// Carry on while the single neighbour still holds both cells and nothing else has
					// appeared on either. Backward, only into a spot that led nowhere else: a mother
					// with two daughters is a division, not this merge.
					motion = new double[ 3 ];
					boolean c = false;
					final int tn = t + dir;
					if ( succs.size() == 1 && tn >= lo && tn < numTimepoints
							&& ( dir > 0 || succs.get( 0 ).outgoingEdges().isEmpty() ) )
					{
						final Spot next = succs.get( 0 );
						final double[] np = new double[ 3 ];
						final double[][] ncov = new double[ 3 ][ 3 ];
						next.localize( np );
						next.getCovariance( ncov );
						motion[ 0 ] = np[ 0 ] - centre[ 0 ];
						motion[ 1 ] = np[ 1 ] - centre[ 1 ];
						motion[ 2 ] = volumetric() ? np[ 2 ] - centre[ 2 ] : 0;
						final double[][] predicted = shifted( pieces.centres, motion );
						c = !ProtectedSpots.isProtected( ctx, ctx.getModel(), next )
								&& insideSpot( np, ncov, predicted[ 0 ] ) && insideSpot( np, ncov, predicted[ 1 ] )
								&& !otherSpotOn( graph, tn, next, predicted[ 0 ] )
								&& !otherSpotOn( graph, tn, next, predicted[ 1 ] );
						if ( c )
						{
							seeds = predicted;
							cur.refTo( next );
							prevA.refTo( a );
							prevB.refTo( b );
							hasPrevB = true;
						}
					}
					carry = c;
					if ( !carry )
						linkOnward( ctx, graph, a, b, succs, tn, dir, tmp, edge );
				}
				finally
				{
					graph.getLock().writeLock().unlock();
				}
				if ( !carry )
					break;
			}
		}
		finally
		{
			for ( final Spot s : succs )
				graph.releaseRef( s );
			graph.releaseRef( cur );
			graph.releaseRef( a );
			graph.releaseRef( b );
			graph.releaseRef( prevA );
			graph.releaseRef( prevB );
			graph.releaseRef( tmp );
			graph.releaseRef( edge );
		}
		return repaired;
	}

	/**
	 * {@code pieces}, or null when either piece's centre lies inside another detection's own radius
	 * at {@code t}: the fit has reached into a cell that is already detected (the split pass's
	 * condition 3). Measured on HeLa 01 over both runs it took 8 of 23 wrong splits for 3 of 61 right
	 * ones, none of those from the end side the user is asked about -- where it would have spared the
	 * question about a real division whose second piece reached into the dividing neighbour.
	 */
	private static Pieces landsOnAnotherDetection( final ModelGraph graph, final int t, final Spot self,
			final Pieces pieces )
	{
		if ( pieces == null )
			return null;
		graph.getLock().readLock().lock();
		try
		{
			for ( final double[] c : pieces.centres )
				if ( otherSpotOn( graph, t, self, c ) )
					return null;
			return pieces;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
	}

	/** Half-width of the view shown in a split question, in the merged detection's radii. */
	private static final double SPLIT_VIEW_REACH = 2.5;

	/** Side of one frame's panel in a split question, in screen pixels. */
	private static final int SPLIT_PANEL_PX = 220;

	/**
	 * Shows the merged detection as it stands (A) and split (B), each between the frame before and the
	 * frame after with their own detections, and asks which is right.
	 */
	private static SplitAnswer askSplit( final ToolContext ctx, final int t, final double[] centre, final double radius,
			final double[][] cov, final Pieces pieces, final String why )
	{
		final double half = SPLIT_VIEW_REACH * radius;
		final List< double[][] > before = ellipsesNear( ctx.getGraph(), t - 1, centre, half );
		final List< double[][] > after = ellipsesNear( ctx.getGraph(), t + 1, centre, half );
		// Every other detection at t as well: without them the middle frame shows only the one in
		// question, and a neighbour that IS detected reads as one that is missing.
		final List< double[][] > others = new ArrayList<>();
		for ( final double[][] e : ellipsesNear( ctx.getGraph(), t, centre, half ) )
			if ( Math.hypot( e[ 0 ][ 0 ] - centre[ 0 ], e[ 0 ][ 1 ] - centre[ 1 ] ) > 1e-6 )
				others.add( e );
		final double[][] range = new double[ 1 ][];
		final BufferedImage prev = framePanel( ctx, t - 1, centre, half, range );
		final BufferedImage mid = framePanel( ctx, t, centre, half, range );
		final BufferedImage next = framePanel( ctx, t + 1, centre, half, range );
		if ( mid == null )
			return SplitAnswer.NONE;
		final List< double[][] > one = java.util.Collections.singletonList( new double[][] { centre, flatCov( cov ) } );
		final BufferedImage a = strip( t, centre, half, prev, mid, next, before, one, others, after );
		final List< BufferedImage > images = new ArrayList<>( Arrays.asList( a ) );
		final List< String > captions = new ArrayList<>( Arrays.asList( "A: one cell" ) );
		final List< String > buttons = new ArrayList<>( Arrays.asList( "A: one cell" ) );
		if ( pieces != null )
		{
			final List< double[][] > two = Arrays.asList( new double[][] { pieces.centres[ 0 ], flatCov( pieces.covs[ 0 ] ) },
					new double[][] { pieces.centres[ 1 ], flatCov( pieces.covs[ 1 ] ) } );
			images.add( strip( t, centre, half, prev, mid, next, before, two, others, after ) );
			captions.add( "B: two cells" );
			buttons.add( "B: two cells" );
		}
		final int handIndex = buttons.size();
		buttons.add( "C: neither — I'll fix it in the viewer" );
		buttons.add( "Stop asking (decide the rest automatically)" );
		final int k = ctx.chooseImage( String.format(
				"t=%d at (%.0f, %.0f): is the magenta detection one cell or two?%n%s%n"
						+ "Each picture shows t=%d, t=%d and t=%d, left to right, every detection drawn on "
						+ "(cyan). In the middle frame A keeps the detection, B (when shown) splits it.",
				t, centre[ 0 ], centre[ 1 ], why, t - 1, t, t + 1 ),
				images, captions, buttons );
		if ( k == handIndex && UserEdits.letUserEdit( ctx, t, centre, String.format(
				"The viewer is at t=%d, centred on (%.0f, %.0f). Fix it there -- add, move, delete or link "
						+ "spots in BDV or TrackScheme, at this timepoint or any other -- and press Done.%n%n"
						+ "Everything you add or change is tagged as protected for the rest of this run, so no "
						+ "later pass deletes, merges or splits it. The run waits until you press Done or "
						+ "Cancel.", t, centre[ 0 ], centre[ 1 ] ) ) )
			return SplitAnswer.EDITED;
		if ( k == 0 || k == handIndex )
			return SplitAnswer.ONE; // C with nobody to wait for falls back to keeping it
		if ( pieces != null && k == 1 )
			return SplitAnswer.TWO;
		return SplitAnswer.NONE;
	}

	private static double[] flatCov( final double[][] c )
	{
		return new double[] { c[ 0 ][ 0 ], c[ 0 ][ 1 ], c[ 1 ][ 1 ] };
	}

	/** {centre, {cxx, cxy, cyy}} of every detection at {@code t} within {@code half} of {@code centre}. */
	private static List< double[][] > ellipsesNear( final ModelGraph graph, final int t, final double[] centre,
			final double half )
	{
		final List< double[][] > out = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[] p = new double[ 3 ];
			final double[][] c = new double[ 3 ][ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != t )
					continue;
				s.localize( p );
				if ( Math.abs( p[ 0 ] - centre[ 0 ] ) > half + 20 || Math.abs( p[ 1 ] - centre[ 1 ] ) > half + 20 )
					continue;
				s.getCovariance( c );
				out.add( new double[][] { p.clone(), flatCov( c ) } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return out;
	}

	/**
	 * Level-0 pixels of {@code t} around {@code centre} as a grey panel. The display range is taken from
	 * the first frame rendered with {@code range[0]} empty and reused for the others, so the three
	 * frames of a question are drawn alike. Null when the frame cannot be read.
	 */
	private static BufferedImage framePanel( final ToolContext ctx, final int t, final double[] centre,
			final double half, final double[][] range )
	{
		final Sampler s;
		try
		{
			s = fullResolutionSampler( ctx, t );
		}
		catch ( final RuntimeException e )
		{
			return null;
		}
		if ( s == null )
			return null;
		final double step = s.voxelSize( 0 );
		final double[] v = new double[ 3 ];
		s.toVoxel( centre, v );
		final long hp = ( long ) Math.ceil( half / step );
		final long z = Math.max( s.min( 2 ), Math.min( s.max( 2 ), Math.round( v[ 2 ] ) ) );
		final int n = ( int ) ( 2 * hp + 1 );
		final double[] px = new double[ n * n ];
		final List< Double > inside = new ArrayList<>();
		for ( int j = 0; j < n; j++ )
			for ( int i = 0; i < n; i++ )
			{
				final long x = ( long ) v[ 0 ] - hp + i, y = ( long ) v[ 1 ] - hp + j;
				if ( x < s.min( 0 ) || x > s.max( 0 ) || y < s.min( 1 ) || y > s.max( 1 ) )
				{
					px[ j * n + i ] = Double.NaN;
					continue;
				}
				px[ j * n + i ] = s.atVoxel( x, y, z );
				inside.add( px[ j * n + i ] );
			}
		if ( inside.isEmpty() )
			return null;
		if ( range[ 0 ] == null )
		{
			java.util.Collections.sort( inside );
			range[ 0 ] = new double[] { inside.get( ( int ) ( 0.01 * ( inside.size() - 1 ) ) ),
					inside.get( ( int ) ( 0.995 * ( inside.size() - 1 ) ) ) };
		}
		final double lo = range[ 0 ][ 0 ], span = Math.max( 1e-9, range[ 0 ][ 1 ] - lo );
		final BufferedImage img = new BufferedImage( n, n, BufferedImage.TYPE_INT_RGB );
		for ( int j = 0; j < n; j++ )
			for ( int i = 0; i < n; i++ )
			{
				final double val = px[ j * n + i ];
				final int g = Double.isNaN( val ) ? 0 : ( int ) Math.round( 255 * Math.max( 0, Math.min( 1, ( val - lo ) / span ) ) );
				img.setRGB( i, j, ( g << 16 ) | ( g << 8 ) | g );
			}
		return img;
	}

	/**
	 * The three frames side by side, each with its detections in cyan; in the middle one the detection
	 * in question is drawn as {@code middle} (one ellipse or two) in magenta.
	 */
	private static BufferedImage strip( final int t, final double[] centre, final double half,
			final BufferedImage prev, final BufferedImage mid, final BufferedImage next,
			final List< double[][] > before, final List< double[][] > middle, final List< double[][] > middleOthers,
			final List< double[][] > after )
	{
		return panels( t, centre, half, new BufferedImage[] { prev, mid, next },
				Arrays.asList( before, middleOthers, after ),
				Arrays.asList( java.util.Collections.emptyList(), middle, java.util.Collections.emptyList() ), null );
	}

	/**
	 * Frames t-1, t and t+1 side by side, centred on {@code centre}: in panel k the detections of
	 * {@code cyan.get(k)} in cyan and of {@code magenta.get(k)} in magenta, and in the last panel a
	 * magenta line from {@code centre} to each of {@code linkedTo} (null for none).
	 */
	private static BufferedImage panels( final int t, final double[] centre, final double half,
			final BufferedImage[] frames, final List< List< double[][] > > cyan,
			final List< List< double[][] > > magenta, final double[][] linkedTo )
	{
		final int gap = 6;
		final BufferedImage out = new BufferedImage( 3 * SPLIT_PANEL_PX + 2 * gap, SPLIT_PANEL_PX,
				BufferedImage.TYPE_INT_RGB );
		final Graphics2D g = out.createGraphics();
		g.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
		g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR );
		final double scale = SPLIT_PANEL_PX / ( 2 * half );
		for ( int k = 0; k < 3; k++ )
		{
			final int x0 = k * ( SPLIT_PANEL_PX + gap );
			if ( frames[ k ] != null )
				g.drawImage( frames[ k ], x0, 0, SPLIT_PANEL_PX, SPLIT_PANEL_PX, null );
			drawEllipses( g, cyan.get( k ), Color.CYAN, 1.5f, x0, centre, half, scale );
			drawEllipses( g, magenta.get( k ), Color.MAGENTA, 2.5f, x0, centre, half, scale );
			if ( k == 2 && linkedTo != null )
			{
				g.setClip( x0, 0, SPLIT_PANEL_PX, SPLIT_PANEL_PX );
				g.setStroke( new BasicStroke( 1.5f ) );
				g.setColor( Color.MAGENTA );
				final double cx = x0 + half * scale, cy = half * scale;
				for ( final double[] d : linkedTo )
					g.draw( new java.awt.geom.Line2D.Double( cx, cy, x0 + ( d[ 0 ] - centre[ 0 ] + half ) * scale,
							( d[ 1 ] - centre[ 1 ] + half ) * scale ) );
				g.setClip( null );
			}
			g.setColor( Color.WHITE );
			g.drawString( "t=" + ( t - 1 + k ), x0 + 6, 16 );
		}
		g.dispose();
		return out;
	}

	private static void drawEllipses( final Graphics2D g, final List< double[][] > ellipses, final Color colour,
			final float width, final int x0, final double[] centre, final double half, final double scale )
	{
		g.setStroke( new BasicStroke( width ) );
		g.setColor( colour );
		for ( final double[][] e : ellipses )
		{
			final double a = e[ 1 ][ 0 ], b = e[ 1 ][ 1 ], d = e[ 1 ][ 2 ];
			final double m = ( a + d ) / 2, r = Math.sqrt( ( a - d ) * ( a - d ) / 4 + b * b );
			final double major = Math.sqrt( Math.max( 0, m + r ) ) * scale;
			final double minor = Math.sqrt( Math.max( 0, m - r ) ) * scale;
			final double angle = 0.5 * Math.atan2( 2 * b, a - d );
			final double cx = x0 + ( e[ 0 ][ 0 ] - centre[ 0 ] + half ) * scale;
			final double cy = ( e[ 0 ][ 1 ] - centre[ 1 ] + half ) * scale;
			final java.awt.geom.AffineTransform saved = g.getTransform();
			g.setClip( x0, 0, SPLIT_PANEL_PX, SPLIT_PANEL_PX );
			g.rotate( angle, cx, cy );
			g.draw( new java.awt.geom.Ellipse2D.Double( cx - major, cy - minor, 2 * major, 2 * minor ) );
			g.setTransform( saved );
			g.setClip( null );
		}
	}

	/** The two cells a merged spot is replaced by; piece k grew from seed k. World units. */
	static final class Pieces
	{
		final double[][] centres;

		final double[][][] covs;

		Pieces( final double[][] centres, final double[][][] covs )
		{
			this.centres = centres;
			this.covs = covs;
		}
	}

	/** The two pieces under the merged spot, or null when the image does not support two cells. */
	private static Pieces fitPieces( final ToolContext ctx, final int t, final double[] centre,
			final double radius, final double[][] cov, final double[][] seeds, final double frameRadius )
	{
		final double[] frame = frameLevelStats( ctx, t );
		final Sampler full = fullResolutionSampler( ctx, t );
		if ( frame == null || full == null || !( frameRadius > 0 ) )
			return null;
		final double level = frame[ 0 ] + SHAPE_LEVEL_SIGMA * frame[ 1 ];
		final ShapeWindow win;
		final ShapeSplit.Result shape;
		try
		{
			win = ShapeWindow.read( full, new double[] { centre[ 0 ], centre[ 1 ], centre[ 2 ], radius } );
			if ( win == null )
				return null;
			final double[][] seedPx = seeds == null ? null : new double[ 2 ][];
			final double[] v = new double[ 3 ];
			for ( int k = 0; seeds != null && k < 2; k++ )
			{
				full.toVoxel( seeds[ k ], v );
				seedPx[ k ] = new double[] { v[ 0 ] - win.x0, v[ 1 ] - win.y0 };
			}
			shape = ShapeSplit.analyse( win.pixels, win.w, win.h, level, win.cx, win.cy, radius / win.step,
					seedPx );
		}
		catch ( final RuntimeException e )
		{
			log.debug( "convergence window unavailable at t={}", t, e );
			return null;
		}
		if ( shape == null || !shape.twoFitted()
				|| shape.separation() * win.step < CONVERGENCE_MIN_SEPARATION * frameRadius )
			return null;
		return new Pieces(
				new double[][] { win.toWorld( full, shape.means[ 0 ][ 0 ], shape.means[ 0 ][ 1 ] ),
						win.toWorld( full, shape.means[ 1 ][ 0 ], shape.means[ 1 ][ 1 ] ) },
				new double[][][] { pieceCovariance( shape.covs[ 0 ], win.step, cov ),
						pieceCovariance( shape.covs[ 1 ], win.step, cov ) } );
	}

	private static double[][] shifted( final double[][] centres, final double[] motion )
	{
		final double[][] out = new double[ centres.length ][];
		for ( int k = 0; k < centres.length; k++ )
			out[ k ] = new double[] { centres[ k ][ 0 ] + motion[ 0 ], centres[ k ][ 1 ] + motion[ 1 ],
					centres[ k ][ 2 ] + ( motion.length > 2 ? motion[ 2 ] : 0 ) };
		return out;
	}

	/** Median bounding radius of the cells at {@code t}. Caller holds the read lock. */
	private static double medianRadiusAt( final ModelGraph graph, final int t )
	{
		final List< Double > r = new ArrayList<>();
		for ( final Spot s : graph.vertices() )
			if ( s.getTimepoint() == t )
				r.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
		return r.isEmpty() ? 0.0 : median( r );
	}

	/**
	 * Whether a detection other than {@code except} at {@code t} has {@code p} within its own radius:
	 * the cell at {@code p} is detected on its own again. Caller holds a lock.
	 */
	private static boolean otherSpotOn( final ModelGraph graph, final int t, final Spot except, final double[] p )
	{
		return freeSpotOn( graph, t, except, p, null, 0 );
	}

	/**
	 * The detection at {@code t} other than {@code except} whose radius holds {@code p}, into
	 * {@code out}. {@code freeFrom} +1: only one with no incoming link (it can take a link from the
	 * frame before); -1: only one with no outgoing link; 0: any. Caller holds a lock.
	 */
	private static boolean freeSpotOn( final ModelGraph graph, final int t, final Spot except, final double[] p,
			final Spot out, final int freeFrom )
	{
		final int skip = except == null ? -1 : except.getInternalPoolIndex();
		final double[] q = new double[ 3 ];
		for ( final Spot o : graph.vertices() )
		{
			if ( o.getTimepoint() != t || o.getInternalPoolIndex() == skip )
				continue;
			if ( freeFrom > 0 && !o.incomingEdges().isEmpty() || freeFrom < 0 && !o.outgoingEdges().isEmpty() )
				continue;
			o.localize( q );
			final double dx = q[ 0 ] - p[ 0 ], dy = q[ 1 ] - p[ 1 ], dz = volumetric() ? q[ 2 ] - p[ 2 ] : 0;
			if ( dx * dx + dy * dy + dz * dz < o.getBoundingSphereRadiusSquared() )
			{
				if ( out != null )
					out.refTo( o );
				return true;
			}
		}
		return false;
	}

	/**
	 * The merge is over: links the two pieces on to what the detector found at {@code tNext}. Two
	 * successors of the merged spot (the false division the merge would have become) go one to each
	 * piece, nearest first; with one, it goes to the nearer piece; a piece left without one takes a
	 * detection with no predecessor that sits on it. Caller holds the write lock.
	 */
	private static void linkOnward( final ToolContext ctx, final ModelGraph graph, final Spot a, final Spot b,
			final List< Spot > succs, final int tNext, final int dir, final Spot tmp, final Link edge )
	{
		final double[] pa = new double[ 3 ], pb = new double[ 3 ];
		a.localize( pa );
		b.localize( pb );
		boolean aLinked = false, bLinked = false;
		if ( succs.size() == 2 )
		{
			final double[] s0 = new double[ 3 ], s1 = new double[ 3 ];
			succs.get( 0 ).localize( s0 );
			succs.get( 1 ).localize( s1 );
			final boolean straight = dist2( pa, s0 ) + dist2( pb, s1 ) <= dist2( pa, s1 ) + dist2( pb, s0 );
			link( ctx, graph, succs.get( straight ? 0 : 1 ), a, -dir, edge );
			link( ctx, graph, succs.get( straight ? 1 : 0 ), b, -dir, edge );
			return;
		}
		if ( succs.size() == 1 )
		{
			final double[] s0 = new double[ 3 ];
			succs.get( 0 ).localize( s0 );
			if ( dist2( pa, s0 ) <= dist2( pb, s0 ) )
			{
				link( ctx, graph, succs.get( 0 ), a, -dir, edge );
				aLinked = true;
			}
			else
			{
				link( ctx, graph, succs.get( 0 ), b, -dir, edge );
				bLinked = true;
			}
		}
		if ( !aLinked && freeSpotOn( graph, tNext, null, pa, tmp, dir ) )
			link( ctx, graph, tmp, a, -dir, edge );
		if ( !bLinked && freeSpotOn( graph, tNext, null, pb, tmp, dir ) )
			link( ctx, graph, tmp, b, -dir, edge );
	}

	/**
	 * Links {@code from} -> {@code to} when {@code dir} is forward (+1), {@code to} -> {@code from} when
	 * backward: {@code from} is always the side the repair came from. Caller holds the write lock.
	 */
	/** Copies of a spot's outgoing links, safe to remove while going through them. Release each. */
	private static List< Link > outgoingCopies( final ModelGraph graph, final Spot s )
	{
		final List< Link > out = new ArrayList<>();
		for ( final Link e : s.outgoingEdges() )
			out.add( graph.edgeRef().refTo( e ) );
		return out;
	}

	private static void link( final ToolContext ctx, final ModelGraph graph, final Spot from, final Spot to,
			final int dir, final Link edge )
	{
		if ( dir > 0 )
			GraphEdits.addLink( ctx, graph, from, to, edge, GraphEdits.Reason.TOPOLOGY );
		else
			GraphEdits.addLink( ctx, graph, to, from, edge, GraphEdits.Reason.TOPOLOGY );
	}

	/** Squared distance in plane, or in 3-D while the merge repair works on a stack (see {@link #volumetric}). */
	private static double dist2( final double[] p, final double[] q )
	{
		final double dx = p[ 0 ] - q[ 0 ], dy = p[ 1 ] - q[ 1 ], dz = volumetric() ? p[ 2 ] - q[ 2 ] : 0;
		return dx * dx + dy * dy + dz * dz;
	}

	/**
	 * Separation of two spots, in cell radii, for as long as both chains continue unbranched.
	 *
	 * <p>Every pair visited along the way is marked in {@code seen}, so a stretch is judged once from
	 * its first timepoint rather than once per timepoint it covers.</p>
	 *
	 * @param stepsOut if non-null, receives {@code {timepoint, poolA, poolB, separation}} per step, so
	 *                 the caller acts on exactly the stretch this measured
	 */
	private static double[] coLocatedSeries( final ModelGraph graph, final Spot a, final Spot b,
			final int endTp, final Set< Long > seen, final List< double[] > stepsOut )
	{
		final List< Double > out = new ArrayList<>();
		final Spot ca = graph.vertexRef(), cb = graph.vertexRef();
		try
		{
			ca.refTo( a );
			cb.refTo( b );
			final double[] pa = new double[ 3 ], pb = new double[ 3 ];
			while ( ca.getTimepoint() <= endTp && ca.getTimepoint() == cb.getTimepoint() )
			{
				seen.add( pairKey( ca.getInternalPoolIndex(), cb.getInternalPoolIndex() ) );
				ca.localize( pa );
				cb.localize( pb );
				final double r = Math.max( 1e-9, Math.max( spotRadius( ca ), spotRadius( cb ) ) );
				final double sep = Math.sqrt( ( pa[ 0 ] - pb[ 0 ] ) * ( pa[ 0 ] - pb[ 0 ] )
						+ ( pa[ 1 ] - pb[ 1 ] ) * ( pa[ 1 ] - pb[ 1 ] )
						+ ( pa[ 2 ] - pb[ 2 ] ) * ( pa[ 2 ] - pb[ 2 ] ) ) / r;
				out.add( sep );
				if ( stepsOut != null )
					stepsOut.add( new double[] { ca.getTimepoint(), ca.getInternalPoolIndex(),
							cb.getInternalPoolIndex(), sep } );
				if ( ca.outgoingEdges().size() != 1 || cb.outgoingEdges().size() != 1 )
					break; // a branch: stop rather than guess which way the pair continues
				ca.outgoingEdges().get( 0 ).getTarget( ca );
				cb.outgoingEdges().get( 0 ).getTarget( cb );
			}
		}
		finally
		{
			graph.releaseRef( ca );
			graph.releaseRef( cb );
		}
		final double[] arr = new double[ out.size() ];
		for ( int i = 0; i < arr.length; i++ )
			arr[ i ] = out.get( i );
		return arr;
	}

	public static String runTopologyConsistency( final ToolContext ctx, final int scanStartTp,
		final int numTimepoints, final AutomahoutSettings settings )
	{
		// The passes, their order and the reasons for it are LineagePasses.BUILT_IN; a CorrectionPass plugin
		// can join or replace one of them (PassPlan).
		final PassScope s = PassScope.lineage( ctx, settings, scanStartTp, numTimepoints );
		PassPlan.forRun( ctx, CorrectionPass.Stage.LINEAGE, LineagePasses.BUILT_IN ).run( s );
		final int minDivisionInterval = s.getInt( LineagePasses.MIN_DIVISION_INTERVAL, 0 );
		final int minBranchLength = s.getInt( LineagePasses.MIN_BRANCH_LENGTH, 0 );
		final double maxDisplacement = s.getDouble( LineagePasses.MAX_DISPLACEMENT, 0.0 );
		final int deBranched = s.count( LineagePasses.EXCESS_DIVISIONS ) + s.count( LineagePasses.STAYING_DIVISIONS )
				+ s.count( LineagePasses.IMPLAUSIBLE_DIVISIONS );

		// The same 3.0 reconnectOrphanTracklets was given, so the report describes the gates
		// that actually ran rather than a looser rule of its own.
		final String orphanStarts = reportMidMovieStarts( ctx, scanStartTp, numTimepoints - 1,
				maxDisplacement, 3.0 );
		return String.format(
			"unlinked fp spots=%d, dissolved rapid divisions=%d, deleted short-branch spots=%d, "
				+ "relinked to free detections=%d, reconnected tracklets=%d, closed gaps=%d, de-branched (single-tracked)=%d, "
				+ "unstable-deleted=%d, co-located-merged=%d, merged-cells-separated=%d frame(s) in %d merge(s) "
				+ "(min_div_interval=%s, min_branch_len=%s, max_disp=%.1f, divisions_rare=%s, stable_min=%d tp, "
				+ "border_min=%d tp, max_gap=%d tp)",
			s.count( LineagePasses.UNLINK_FP ), s.count( LineagePasses.RAPID_DIVISIONS ),
			s.count( LineagePasses.SHORT_BRANCHES ), s.count( LineagePasses.RELINK_FREE ),
			s.count( LineagePasses.RECONNECT ), s.count( LineagePasses.CLOSE_GAPS ), deBranched,
			s.count( LineagePasses.UNSTABLE ), s.count( LineagePasses.CO_LOCATED ), s.count( LineagePasses.CONVERGED ),
			s.getInt( LineagePasses.CONVERGED_EPISODES, 0 ),
			minDivisionInterval > 0 ? String.valueOf( minDivisionInterval ) : "off",
			minBranchLength > 0 ? String.valueOf( minBranchLength ) : "off", maxDisplacement,
			Boolean.TRUE.equals( s.get( LineagePasses.DIVISIONS_RARE ) ) ? "yes" : "no", STABLE_TRACK_MIN_TP,
			BORDER_FRAGMENT_MIN_TP, MAX_LINK_GAP )
			+ "; " + orphanStarts;
	}

	/**
	 * A detection whose entire track spans fewer than this many timepoints is treated as an unstable
	 * flicker (noise) and labelled {@code fp}. {@code 3} ⇒ isolated spots and 1–2 tp tracks are fp; a
	 * track that persists ≥3 consecutive tp is kept as a real cell.
	 */
	static final int STABLE_TRACK_MIN_TP = 3;

	/** Loads the active learned lineage-constraint profile, or {@code null} if none/unreadable. */
	/**
	 * The ELEPHANT dataset now open, which a constraint profile must match to be honoured.
	 * See {@link LineageConstraintProfile#loadForDataset}.
	 */
	public static String currentElephantDataset()
	{
		try
		{
			return ElephantMainSettingsManager.getInstance().getSelectedStyle().getDatasetName();
		}
		catch ( final Exception e )
		{
			return "";
		}
	}

	/**
	 * The active profile, but only if it was learned from the dataset now open. Called on every window
	 * (through {@link #estimateMaxDisplacement}), so the refusal is not announced here — the pre-flight
	 * says it once, via {@link LineageConstraintProfile#loadForDataset}.
	 */
	static LineageConstraintProfile loadConstraintProfile( final AutomahoutSettings settings )
	{
		return LineageConstraintProfile.loadForDataset(
				settings.getConstraintsProfilePath(), currentElephantDataset(), null );
	}

	/** Multiplier on the median consecutive-link displacement used as a fallback per-frame cap. */
	private static final double ESTIMATED_DISPLACEMENT_CAP_FACTOR = 4.0;

	/**
	 * Learned per-frame displacement cap (P99, fallback observed max) from the active lineage-constraint
	 * profile, or {@code 0} if no profile is active.
	 */
	public static double learnedMaxDisplacement( final AutomahoutSettings settings )
	{
		final LineageConstraintProfile p = loadConstraintProfile( settings );
		if ( p == null )
			return 0.0;
		if ( !Double.isNaN( p.displacementP99 ) && p.displacementP99 > 0.0 )
			return p.displacementP99;
		if ( !Double.isNaN( p.displacementMax ) && p.displacementMax > 0.0 )
			return p.displacementMax;
		return 0.0;
	}

	/**
	 * Robust per-frame displacement cap for link pruning. Prefers the learned P99 (see
	 * {@link #learnedMaxDisplacement}); when no constraint profile is active, falls back to
	 * {@value #ESTIMATED_DISPLACEMENT_CAP_FACTOR}x the MEDIAN consecutive-link displacement in
	 * {@code [startTp, endTp]}. The median is robust to the few wrong long links we want to prune, so a
	 * big/bright cell's large radius can no longer accept a link to a far, dim spot. Returns {@code 0}
	 * (cap disabled) when it cannot be determined (fewer than two consecutive links).
	 */
	public static double estimateMaxDisplacement( final ToolContext ctx, final AutomahoutSettings settings,
		final int startTp, final int endTp )
	{
		final double learned = learnedMaxDisplacement( settings );
		if ( learned > 0.0 )
			return learned;
		return measureMaxDisplacement( ctx, startTp, endTp );
	}

	/**
	 * The measured half of {@link #estimateMaxDisplacement}, without the learned profile — for callers
	 * that have no {@code AutomahoutSettings} to load one from. {@code globalCleanup} is the case: it
	 * ran every reconnection pass with NO displacement cap at all, so a link was bounded only by a
	 * multiple of the spot's radius.
	 */
	public static double measureMaxDisplacement( final ToolContext ctx, final int startTp,
		final int endTp )
	{
		final ModelGraph graph = ctx.getGraph();
		final List< Double > disps = new ArrayList<>();
		final Spot srcRef = graph.vertexRef();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < startTp || t > endTp )
					continue;
				for ( final Link e : s.incomingEdges() )
				{
					e.getSource( srcRef );
					if ( t - srcRef.getTimepoint() != 1 )
						continue; // consecutive links only
					final double dx = s.getDoublePosition( 0 ) - srcRef.getDoublePosition( 0 );
					final double dy = s.getDoublePosition( 1 ) - srcRef.getDoublePosition( 1 );
					final double dz = s.getDoublePosition( 2 ) - srcRef.getDoublePosition( 2 );
					disps.add( Math.sqrt( dx * dx + dy * dy + dz * dz ) );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( srcRef );
		}
		if ( disps.size() < 2 )
			return 0.0;
		Collections.sort( disps );
		final double median = disps.get( disps.size() / 2 );
		return median > 0.0 ? median * ESTIMATED_DISPLACEMENT_CAP_FACTOR : 0.0;
	}

	// -----------------------------------------------------------------------

	/** Counts every spot currently in the graph, across all timepoints. */
	public static int countAllSpots( final ToolContext ctx )
	{
		final ModelGraph graph = ctx.getGraph();
		int count = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( @SuppressWarnings( "unused" ) final Spot s : graph.vertices() )
				count++;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return count;
	}

	/**
	 * A daughter's bounding-sphere radius below this fraction of its mother's counts as "meaningfully
	 * smaller". Real division roughly halves cell VOLUME, i.e. radius shrinks to about
	 * {@code 0.5^(1/3) ≈ 0.79}; this is deliberately looser (0.85) to tolerate segmentation noise while
	 * still rejecting a "daughter" that is the same size as (or larger than) its mother.
	 */
	/**
	 * How much larger than its mother a daughter may be and still be accepted as one.
	 *
	 * <p>This was 0.85, on the premise that "a real daughter is meaningfully smaller than its
	 * mother". Measured on the CTC tracking ground truth (benchmark/division-displacement,
	 * measure_size.py), that premise is false for these data — the mother's LAST frame is already a
	 * condensed mitotic nucleus, so the daughter is not much smaller than it and often the same
	 * size:</p>
	 *
	 * <table>
	 * <tr><th>daughter / mother radius</th><th>seq 01 (188 divisions)</th><th>seq 02 (422)</th></tr>
	 * <tr><td>p05 / median / p95</td><td>0.30 / 0.64 / 1.40</td><td>0.60 / <b>1.00</b> / 1.10</td></tr>
	 * </table>
	 *
	 * <table>
	 * <tr><th>rule</th><th>refuses (seq 01)</th><th>refuses (seq 02)</th></tr>
	 * <tr><td><b>0.85 (was)</b></td><td><b>30.3%</b></td><td><b>78.4%</b></td></tr>
	 * <tr><td>1.00</td><td>18.1%</td><td>8.5%</td></tr>
	 * <tr><td>1.10</td><td>14.9%</td><td>4.5%</td></tr>
	 * <tr><td><b>1.25</b></td><td><b>8.0%</b></td><td><b>3.6%</b></td></tr>
	 * <tr><td>1.50</td><td>3.7%</td><td>0.9%</td></tr>
	 * </table>
	 *
	 * <p>1.25 keeps what the rule is for — refusing a daughter that is far BIGGER than its mother,
	 * which is an unrelated cell rather than a mitosis — at a cost of 3–8% of real divisions instead
	 * of 30–78%. Together with the per-frame cap no longer binding the division join, this is the
	 * other half of "divisions are barely recognised".</p>
	 */
	private static final double DAUGHTER_SIZE_RATIO_MAX = 1.25;

	/**
	 * For a 1:1 CONTINUATION link (the same cell across consecutive frames), the two spots should look
	 * similar in size. A pairing whose larger/smaller bounding-sphere-radius ratio exceeds this is
	 * rejected — e.g. a normal spot must NOT be linked to a huge saturated blob, even if it is the only
	 * predecessor within reach. Distance is NOT the gate here (a cell may move far); appearance is. Loose
	 * enough (2.5×) to tolerate focus/segmentation variation and moderate growth.
	 */
	private static final double CONTINUATION_SIZE_RATIO_MAX = 2.5;

	/**
	 * Counts division points (spots with ≥2 outgoing edges) that fail either of two basic biology
	 * checks, and are therefore likely a tracking/linking error rather than a real division:
	 *
	 * <ol>
	 *   <li><b>Refractory interval:</b> a cell that just divided does not immediately divide again — if
	 *       an ancestor within {@code minDivisionInterval} timepoints is itself a division, this one is
	 *       implausible (same walk-back test as {@link #removeRapidSuccessiveDivisions}).</li>
	 *   <li><b>Morphology:</b> real division roughly halves cell volume, so at least one daughter should
	 *       be meaningfully smaller than the mother (see {@link #DAUGHTER_SIZE_RATIO_MAX}). If NEITHER
	 *       daughter is smaller, this reads as two independent, similarly-sized cells that got linked to
	 *       a shared mother by mistake, not an actual division.</li>
	 * </ol>
	 *
	 * <p>{@code minDivisionInterval <= 0} skips check 1 (interval unknown/disabled); a mother or daughter
	 * with no measurable radius skips check 2 for that division (no basis to judge).</p>
	 *
	 * @return number of division points failing at least one check
	 */
	public static int countImplausibleDivisions( final ToolContext ctx, final int minDivisionInterval )
	{
		final ModelGraph graph = ctx.getGraph();
		int implausible = 0;
		final Spot cursorRef = graph.vertexRef();
		final Spot dRef = graph.vertexRef();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.outgoingEdges().size() < 2 )
					continue;

				boolean bad = false;

				// Check 1: refractory interval since the nearest ancestor division.
				if ( minDivisionInterval > 0 )
				{
					cursorRef.refTo( s );
					int steps = 0;
					while ( steps < minDivisionInterval && !cursorRef.incomingEdges().isEmpty() )
					{
						for ( final Link e : cursorRef.incomingEdges() )
						{
							e.getSource( cursorRef );
							break;
						}
						steps++;
						if ( cursorRef.outgoingEdges().size() >= 2 )
						{
							bad = true;
							break;
						}
						if ( cursorRef.outgoingEdges().size() != 1 )
							break; // non-linear chain
					}
				}

				// Check 2: at least one daughter should be meaningfully smaller than the mother.
				if ( !bad )
				{
					final double motherR = Math.sqrt( s.getBoundingSphereRadiusSquared() );
					if ( motherR > 0 )
					{
						boolean anySmaller = false;
						for ( final Link e : s.outgoingEdges() )
						{
							e.getTarget( dRef );
							final double dR = Math.sqrt( dRef.getBoundingSphereRadiusSquared() );
							if ( dR < DAUGHTER_SIZE_RATIO_MAX * motherR )
							{
								anySmaller = true;
								break;
							}
						}
						if ( !anySmaller )
							bad = true;
					}
				}

				if ( bad )
					implausible++;
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( cursorRef );
			graph.releaseRef( dRef );
		}
		return implausible;
	}

	/**
	 * Computes topology quality metrics for the current graph.
	 *
	 * @return int[3]: [divisionCount, minDivisionInterval, maxDivisionInterval]
	 *         where minDivisionInterval is Integer.MAX_VALUE when no consecutive
	 *         divisions exist, and divisionCount is the number of spots with ≥2
	 *         outgoing edges (i.e. division events).
	 */
	public static int[] computeTopologyMetrics( final ToolContext ctx )
	{
		final ModelGraph graph = ctx.getGraph();
		// Collect all division spots: pool index → timepoint
		final Map< Integer, Integer > divisionTp = new HashMap<>();
		final Spot cursorRef = graph.vertexRef();

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.outgoingEdges().size() >= 2 )
					divisionTp.put( s.getInternalPoolIndex(), s.getTimepoint() );
			}
			if ( divisionTp.isEmpty() )
				return new int[] { 0, Integer.MAX_VALUE, 0 };

			// For each division spot, walk backward to find the nearest ancestor division
			int minInterval = Integer.MAX_VALUE;
			int maxInterval = 0;
			for ( final Spot s : graph.vertices() )
			{
				if ( !divisionTp.containsKey( s.getInternalPoolIndex() ) )
					continue;
				// Trace backward through the single-parent chain
				cursorRef.refTo( s );
				int steps = 0;
				boolean found = false;
				while ( !cursorRef.incomingEdges().isEmpty() )
				{
					for ( final Link e : cursorRef.incomingEdges() )
					{
						e.getSource( cursorRef );
						break;
					}
					steps++;
					if ( divisionTp.containsKey( cursorRef.getInternalPoolIndex() ) )
					{
						found = true;
						break;
					}
					if ( cursorRef.outgoingEdges().size() != 1 )
						break; // diverged from linear chain
				}
				if ( found )
				{
					minInterval = Math.min( minInterval, steps );
					maxInterval = Math.max( maxInterval, steps );
				}
			}
			return new int[] { divisionTp.size(), minInterval, maxInterval };
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( cursorRef );
		}
	}

	/**
	 * Removes biologically implausible rapid successive divisions.
	 *
	 * <p>A "rapid successive division" is when a spot that is itself a daughter
	 * of a recent division (within {@code minDivisionInterval} timepoints) also
	 * appears to divide.  This is a topology error caused by mis-linking rather
	 * than a real biological event.</p>
	 *
	 * <p>For each such spot the shorter daughter branch (fewer downstream spots)
	 * is removed.  The orphaned spots are left for subsequent cleanup by
	 * {@link #deleteShortPostDivisionBranches} or {@link #globalCleanup}.</p>
	 *
	 * @return number of outgoing edges removed
	 */
	/**
	 * True when the learned profile indicates cell divisions are RARE, so most branchings in the
	 * graph are linking errors rather than real mitoses. Uses the learned division rate (fraction of
	 * nodes that divide; {@code < 5%} = rare, matching the prompt threshold), falling back to divisions
	 * per founder. Returns {@code false} when the profile lacks these stats (no basis to de-branch).
	 */
	/**
	 * Removes EVERY edge incident to an {@code fp}-tagged spot. fp spots are detection-training negatives
	 * and must never participate in a track, but a graph produced before that rule was enforced (or by any
	 * un-guarded path) may still contain fp spots with links. The fp spots themselves are kept (they are
	 * still valid training negatives); only their edges are cut.
	 *
	 * @return number of edges removed
	 */
	public static int unlinkFpSpots( final ToolContext ctx, final int numTimepoints )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Link > toRemove = new ArrayList<>();
		final Set< Integer > seen = new HashSet<>();
		int removed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( !DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, s ) ) )
					continue;
				for ( final Link e : s.incomingEdges() )
					if ( seen.add( e.getInternalPoolIndex() ) )
					{
						final Link copy = graph.edgeRef();
						copy.refTo( e );
						toRemove.add( copy );
					}
				for ( final Link e : s.outgoingEdges() )
					if ( seen.add( e.getInternalPoolIndex() ) )
					{
						final Link copy = graph.edgeRef();
						copy.refTo( e );
						toRemove.add( copy );
					}
			}
			// As with every deleting pass: report what GraphEdits removed, not what it was handed.
			for ( final Link e : toRemove )
				if ( GraphEdits.removeLink( ctx, graph, e, GraphEdits.Reason.TOPOLOGY ) )
					removed++;
		}
		finally
		{
			graph.getLock().writeLock().unlock();
			for ( final Link e : toRemove )
				graph.releaseRef( e );
		}
		if ( !toRemove.isEmpty() )
		{
			ctx.getModel().setUndoPoint();
			ctx.notifyGraphChanged();
		}
		return removed;
	}

	/**
	 * Deletes temporally-UNSTABLE detections: any spot whose entire connected track spans fewer than
	 * {@code minStableLen} timepoints (e.g. isolated spots and 1–2 tp flickers) is almost certainly noise
	 * rather than a real cell, which persists across many frames. Deleted outright rather than tagged
	 * {@code fp} — an actual detection result must never be turned into a declared background NEGATIVE
	 * (that falsely confirms the position as background and trains the model toward it); simply removing
	 * it from tracking, with no training label either way, is the safe action when a track is merely too
	 * short-lived to trust yet. The last {@code minStableLen} timepoints of the ACTUAL data are protected
	 * (a short track there may simply still be growing).
	 *
	 * <p>Bounded by {@link ExpectedCellCount}, because "this is noise rather than a cell" is exactly the
	 * kind of judgement that floor exists to bound. A short track is as often a real cell the LINKER
	 * failed on as a spurious detection, and this pass runs last, on top of everything the earlier
	 * passes already removed: it was measured deleting 187 spots from a ten-timepoint window whose
	 * detection had been held at the stated 250 cells/frame, finishing at 227 spots/tp with individual
	 * frames down at 193. Where the budget binds, the shortest-lived tracks go first — those are the
	 * ones this test is most confident about.</p>
	 *
	 * @return number of spots deleted
	 */
	public static int deleteUnstableSpots( final ToolContext ctx, final int startTp, final int endTp,
		final int minStableLen )
	{
		return deleteUnstableSpots( ctx, startTp, endTp, minStableLen, 0 );
	}

	/**
	 * A lineage that reaches the edge of the field of view has to live at least this long to be worth
	 * keeping. An interior fragment is a linking failure and the pipeline tries to repair it; a
	 * fragment at the image edge is a cell walking in or out of frame, which is real but unusable —
	 * and, as the user put it, the fragments are mostly at the edges and such incomplete tracks should
	 * simply be deleted.
	 *
	 * <p>The safety question is what this costs when the tracking is RIGHT, since a component here is
	 * a whole lineage tree and a correctly-linked one spans the movie. Measured on the CTC tracking
	 * ground truth (benchmark/border-fragments), as the share of all ground-truth objects deleted,
	 * with the border taken as one nucleus radius (10.3 µm) from an image face:</p>
	 *
	 * <table>
	 * <tr><th>min tp</th><th>seq 01</th><th>seq 02</th></tr>
	 * <tr><td>3</td><td>0.06%</td><td>0.02%</td></tr>
	 * <tr><td>8</td><td>0.12%</td><td>0.13%</td></tr>
	 * <tr><td>10</td><td>0.12%</td><td>0.26%</td></tr>
	 * <tr><td>15</td><td>0.58%</td><td>0.52%</td></tr>
	 * <tr><td>30</td><td>0.91%</td><td>0.84%</td></tr>
	 * </table>
	 *
	 * <p>10 is the adaptive-track window: a lineage at the edge that does not survive one window of
	 * tracking never established itself, and it costs a quarter of a percent of correct data to say
	 * so. Note the frontier protection applies at this length too, so a border lineage inside the last
	 * ten timepoints of the scanned range is never judged — it is still growing.</p>
	 */
	public static final int BORDER_FRAGMENT_MIN_TP = 10;

	/**
	 * As {@link #deleteUnstableSpots(ToolContext, int, int, int)}, but a lineage whose spots reach the
	 * edge of the image must span {@code borderMinLen} timepoints rather than {@code minStableLen}.
	 * {@code borderMinLen <= minStableLen} switches the distinction off.
	 */
	public static int deleteUnstableSpots( final ToolContext ctx, final int startTp, final int endTp,
		final int minStableLen, final int borderMinLen )
	{
		// The edge of the field of view, in the axes this dataset actually has — on one plane every
		// spot is at z=0 and would otherwise read as "at the border". See ImageBounds.
		return deleteUnstableSpots( ctx, startTp, endTp, minStableLen, borderMinLen,
				borderMinLen > minStableLen ? ImageBounds.physicalExtent( ctx ) : null,
				ctx != null && ctx.isVolumetric() );
	}

	/**
	 * As above, with the image extent supplied rather than read from the project. A replay fixture has
	 * a graph but no image, so this is the only way the border rule can be tested on a real graph
	 * instead of by reading the source.
	 */
	static int deleteUnstableSpots( final ToolContext ctx, final int startTp, final int endTp,
		final int minStableLen, final int borderMinLen, final double[] extent, final boolean volumetric )
	{
		if ( minStableLen <= 1 )
			return 0;
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		// Budgets first, before anything is removed: each is measured against what its timepoint holds
		// now, so they must all be read from the same pre-deletion state.
		final Map< Integer, Integer > budget = new HashMap<>();
		for ( int t = Math.max( 0, startTp ); t <= endTp; t++ )
			budget.put( t, ExpectedCellCount.deletionBudget( ctx, t, ExpectedCellCount.countCellsAt( ctx, t ) ) );
		final List< Spot > toDelete = new ArrayList<>();
		final List< Spot > candidates = new ArrayList<>();
		final List< Integer > candidateTp = new ArrayList<>();
		final List< Integer > candidateSpan = new ArrayList<>();
		graph.getLock().writeLock().lock();
		try
		{
			// 1. Union spots joined by any link -> weakly-connected track components.
			final Map< Integer, Integer > parent = new HashMap<>();
			final Spot tRef = graph.vertexRef();
			try
			{
				for ( final Spot s : graph.vertices() )
					for ( final Link e : s.outgoingEdges() )
					{
						e.getTarget( tRef );
						ufUnion( parent, s.getInternalPoolIndex(), tRef.getInternalPoolIndex() );
					}
			}
			finally
			{
				graph.releaseRef( tRef );
			}
			// 2. Per component: min/max timepoint.
			final Map< Integer, int[] > span = new HashMap<>();
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				final int root = ufFind( parent, s.getInternalPoolIndex() );
				final int[] mm = span.get( root );
				if ( mm == null )
					span.put( root, new int[] { t, t } );
				else
				{
					if ( t < mm[ 0 ] ) mm[ 0 ] = t;
					if ( t > mm[ 1 ] ) mm[ 1 ] = t;
				}
			}
			// The frontier is the END OF THE RANGE THIS CALL WAS GIVEN, not the last timepoint holding
			// any spot. Those differ whenever anything exists beyond the window — a previous run's
			// tracking, or the user's confirmed spots, which on one project sat across all 74
			// timepoints. The threshold then landed at the movie's end and protected nothing at the
			// window's, so a cell first detected in a window's LAST timepoint had a one-timepoint track,
			// counted as a flicker, and was deleted — before the next window could ever link it forward.
			// The threshold is per-component, because a border lineage is held to a longer span.
			// 2b. Which components reach the edge of the image. Judged over the WHOLE component, not
			// only the part inside the scanned range: a lineage that walked out of frame ten timepoints
			// ago is the same lineage here.
			final Set< Integer > atBorder = new HashSet<>();
			if ( extent != null )
			{
				final double[] pos = new double[ 3 ];
				for ( final Spot s : graph.vertices() )
				{
					s.localize( pos );
					// The margin is the spot's own radius: the cell's ellipsoid reaches an image face.
					if ( ImageBounds.reachesBorder( extent, pos,
							Math.sqrt( s.getBoundingSphereRadiusSquared() ), volumetric ) )
						atBorder.add( ufFind( parent, s.getInternalPoolIndex() ) );
				}
			}
			// 3. Delete spots on components spanning < minStableLen tp (unstable), unless at the frontier.
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < startTp || t > endTp )
					continue;
				if ( ProtectedSpots.isProtected( ctx, model, s ) )
					continue; // a confirmed result is never removed for merely lacking a long track YET
				final int root = ufFind( parent, s.getInternalPoolIndex() );
				final int[] mm = span.get( root );
				// A lineage at the edge of the field of view has to live longer to be worth keeping.
				final int required = atBorder.contains( root ) ? borderMinLen : minStableLen;
				if ( mm == null || ( mm[ 1 ] - mm[ 0 ] + 1 ) >= required )
					continue; // stable enough
				if ( mm[ 1 ] >= endTp - required + 1 )
					continue; // frontier — may still grow
				final Spot copy = graph.vertexRef();
				copy.refTo( s );
				candidates.add( copy );
				candidateTp.add( t );
				candidateSpan.add( mm[ 1 ] - mm[ 0 ] + 1 );
			}
			// Shortest-lived first, so a binding budget spends itself on the least defensible spots.
			final Integer[] order = new Integer[ candidates.size() ];
			for ( int i = 0; i < order.length; i++ )
				order[ i ] = i;
			Arrays.sort( order, ( a, b ) -> Integer.compare( candidateSpan.get( a ), candidateSpan.get( b ) ) );
			final Map< Integer, Integer > spent = new HashMap<>();
			for ( final int i : order )
			{
				final int t = candidateTp.get( i );
				final int allowed = budget.getOrDefault( t, Integer.MAX_VALUE );
				final int used = spent.getOrDefault( t, 0 );
				if ( used >= allowed )
					continue; // this timepoint is already down to the stated cell count
				spent.put( t, used + 1 );
				toDelete.add( candidates.get( i ) );
			}
			for ( final Spot s : toDelete )
				GraphEdits.removeSpot( ctx, graph, s, GraphEdits.Reason.PROBABLY_NOT_A_CELL );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
			for ( final Spot s : candidates )
				graph.releaseRef( s );
		}
		if ( !toDelete.isEmpty() )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		return toDelete.size();
	}

	/** Union-find find with path compression; a key absent from {@code parent} is its own root. */
	private static int ufFind( final Map< Integer, Integer > parent, final int x )
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
	private static void ufUnion( final Map< Integer, Integer > parent, final int a, final int b )
	{
		parent.putIfAbsent( a, a );
		parent.putIfAbsent( b, b );
		final int ra = ufFind( parent, a );
		final int rb = ufFind( parent, b );
		if ( ra != rb )
			parent.put( ra, rb );
	}

	public static boolean divisionsAreRare( final LineageConstraintProfile p )
	{
		if ( p == null )
			return false;
		if ( !Double.isNaN( p.divisionRate ) )
			return p.divisionRate < 0.05;
		if ( !Double.isNaN( p.divisionsPerFounder ) )
			return p.divisionsPerFounder < 1.0;
		return false;
	}

	/**
	 * Forces every branching (a spot with >=2 outgoing edges) into a SINGLE track: keeps the daughter
	 * that best continues the mother's motion (highest cosine similarity with the incoming direction;
	 * nearest daughter when the mother has no predecessor) and CUTS the other outgoing edge(s). The cut
	 * daughters become independent track starts (their spots are NOT deleted — only the false division
	 * link is removed). Intended for lineages where divisions are rare ({@link #divisionsAreRare}), so a
	 * branch is almost always a force-connected error; a genuinely dividing lineage must NOT call this.
	 * Handles long spurious branches that {@link #deleteShortPostDivisionBranches} (short only) misses,
	 * and works under sparse GT (uses no track-segment-length threshold).
	 *
	 * @return number of division links cut (branchings resolved may be fewer if a spot had >2 daughters)
	 */
	public static int dissolveExcessDivisions( final ToolContext ctx, final int numTimepoints )
	{
		return dissolveExcessDivisions( ctx, 0, numTimepoints );
	}

	/**
	 * As {@link #dissolveExcessDivisions(ToolContext, int)}, but only considers mothers at
	 * {@code t >= scanStartTp} (0 = scan the whole range). Timepoints before {@code scanStartTp} were
	 * already de-branched by an earlier pass and are left untouched.
	 */
	public static int dissolveExcessDivisions( final ToolContext ctx, final int scanStartTp,
			final int numTimepoints )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Link > toCut = new ArrayList<>();
		final Spot predRef = graph.vertexRef();
		final Spot dRef = graph.vertexRef();
		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot mother : graph.vertices() )
				{
					if ( mother.outgoingEdges().size() < 2 )
						continue;
					if ( mother.getTimepoint() < scanStartTp )
						continue;
					if ( ProtectedSpots.isProtected( ctx, model, mother ) )
						continue; // its outgoing links are left exactly as curated, whatever they are
					// Mother motion direction from its predecessor (if any), normalised.
					double vx = 0, vy = 0, vz = 0;
					boolean hasDir = false;
					if ( !mother.incomingEdges().isEmpty() )
					{
						mother.incomingEdges().iterator().next().getSource( predRef );
						vx = mother.getDoublePosition( 0 ) - predRef.getDoublePosition( 0 );
						vy = mother.getDoublePosition( 1 ) - predRef.getDoublePosition( 1 );
						vz = mother.getDoublePosition( 2 ) - predRef.getDoublePosition( 2 );
						final double vn = Math.sqrt( vx * vx + vy * vy + vz * vz );
						if ( vn > 1e-9 ) { vx /= vn; vy /= vn; vz /= vn; hasDir = true; }
					}
					// First pass: pick the best-continuing daughter edge.
					int bestEdgeIdx = -1;
					double bestScore = Double.NEGATIVE_INFINITY;
					for ( final Link e : mother.outgoingEdges() )
					{
						e.getTarget( dRef );
						final double dx = dRef.getDoublePosition( 0 ) - mother.getDoublePosition( 0 );
						final double dy = dRef.getDoublePosition( 1 ) - mother.getDoublePosition( 1 );
						final double dz = dRef.getDoublePosition( 2 ) - mother.getDoublePosition( 2 );
						final double dn = Math.sqrt( dx * dx + dy * dy + dz * dz );
						final double score;
						if ( hasDir && dn > 1e-9 )
							score = ( vx * dx + vy * dy + vz * dz ) / dn; // cosine with mother motion
						else
							score = dn > 1e-9 ? -dn : 0.0; // no direction: prefer the nearest daughter
						if ( score > bestScore )
						{
							bestScore = score;
							bestEdgeIdx = e.getInternalPoolIndex();
						}
					}
					// Second pass: cut every outgoing edge except the best one.
					for ( final Link e : mother.outgoingEdges() )
					{
						if ( e.getInternalPoolIndex() == bestEdgeIdx )
							continue;
						e.getTarget( dRef );
						if ( ProtectedSpots.isProtected( ctx, model, dRef ) )
							continue; // a confirmed daughter's link is never cut, whatever the heuristic says
						final Link copy = graph.edgeRef();
						copy.refTo( e );
						toCut.add( copy );
					}
				}
				for ( final Link link : toCut )
					GraphEdits.removeLink( ctx, graph, link, GraphEdits.Reason.TOPOLOGY );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			if ( !toCut.isEmpty() )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return toCut.size();
		}
		finally
		{
			graph.releaseRef( predRef );
			graph.releaseRef( dRef );
			for ( final Link link : toCut )
				graph.releaseRef( link );
		}
	}

	/**
	 * Selectively resolves divisions that fail the same plausibility checks as
	 * {@link #countImplausibleDivisions} — refractory interval since the nearest ancestor division, and
	 * morphology (at least one daughter must be meaningfully smaller than the mother, see
	 * {@link #DAUGHTER_SIZE_RATIO_MAX}). For each FAILING division, keeps the daughter that best
	 * continues the mother's motion (same selection rule as {@link #dissolveExcessDivisions}) and cuts
	 * the rest; divisions that PASS both checks are left untouched.
	 *
	 * <p>Unlike {@link #dissolveExcessDivisions} (which force-cuts every branch and therefore requires a
	 * learned profile confirming divisions are globally rare), this needs no such profile — it judges
	 * each division on its own — so it is safe to run on a lineage that genuinely divides: it targets
	 * only branches that read as mis-linking errors (e.g. an orphan spot force-linked as a "second
	 * daughter" by {@link #linkDivisionDaughters}/{@link #linkOrphanStarts} merely because it happened to
	 * sit nearby — most often because its OWN true predecessor at t-1 went undetected — not real
	 * mitoses), while leaving real divisions (a meaningfully smaller daughter, spaced out in time) alone.
	 *
	 * @return number of division links cut
	 */
	public static int dissolveImplausibleDivisions( final ToolContext ctx, final int scanStartTp,
			final int numTimepoints, final int minDivisionInterval )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final List< Link > toCut = new ArrayList<>();
		final Spot cursorRef = graph.vertexRef();
		final Spot dRef = graph.vertexRef();
		final Spot predRef = graph.vertexRef();
		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot mother : graph.vertices() )
				{
					if ( mother.outgoingEdges().size() < 2 )
						continue;
					if ( mother.getTimepoint() < scanStartTp )
						continue;
					if ( ProtectedSpots.isProtected( ctx, model, mother ) )
						continue; // its outgoing links are left exactly as curated, whatever they are

					boolean bad = false;

					// Check 1: refractory interval since the nearest ancestor division.
					if ( minDivisionInterval > 0 )
					{
						cursorRef.refTo( mother );
						int steps = 0;
						while ( steps < minDivisionInterval && !cursorRef.incomingEdges().isEmpty() )
						{
							for ( final Link e : cursorRef.incomingEdges() )
							{
								e.getSource( cursorRef );
								break;
							}
							steps++;
							if ( cursorRef.outgoingEdges().size() >= 2 )
							{
								bad = true;
								break;
							}
							if ( cursorRef.outgoingEdges().size() != 1 )
								break; // non-linear chain
						}
					}

					// Check 2: at least one daughter should be meaningfully smaller than the mother.
					if ( !bad )
					{
						final double motherR = Math.sqrt( mother.getBoundingSphereRadiusSquared() );
						if ( motherR > 0 )
						{
							boolean anySmaller = false;
							for ( final Link e : mother.outgoingEdges() )
							{
								e.getTarget( dRef );
								final double dR = Math.sqrt( dRef.getBoundingSphereRadiusSquared() );
								if ( dR < DAUGHTER_SIZE_RATIO_MAX * motherR )
								{
									anySmaller = true;
									break;
								}
							}
							if ( !anySmaller )
								bad = true;
						}
					}

					if ( !bad )
						continue; // plausible division (or no basis to judge) — leave untouched

					// Keep the daughter that best continues the mother's motion; cut the rest — same
					// selection rule as dissolveExcessDivisions.
					double vx = 0, vy = 0, vz = 0;
					boolean hasDir = false;
					if ( !mother.incomingEdges().isEmpty() )
					{
						mother.incomingEdges().iterator().next().getSource( predRef );
						vx = mother.getDoublePosition( 0 ) - predRef.getDoublePosition( 0 );
						vy = mother.getDoublePosition( 1 ) - predRef.getDoublePosition( 1 );
						vz = mother.getDoublePosition( 2 ) - predRef.getDoublePosition( 2 );
						final double vn = Math.sqrt( vx * vx + vy * vy + vz * vz );
						if ( vn > 1e-9 ) { vx /= vn; vy /= vn; vz /= vn; hasDir = true; }
					}
					int bestEdgeIdx = -1;
					double bestScore = Double.NEGATIVE_INFINITY;
					for ( final Link e : mother.outgoingEdges() )
					{
						e.getTarget( dRef );
						final double dx = dRef.getDoublePosition( 0 ) - mother.getDoublePosition( 0 );
						final double dy = dRef.getDoublePosition( 1 ) - mother.getDoublePosition( 1 );
						final double dz = dRef.getDoublePosition( 2 ) - mother.getDoublePosition( 2 );
						final double dn = Math.sqrt( dx * dx + dy * dy + dz * dz );
						final double score;
						if ( hasDir && dn > 1e-9 )
							score = ( vx * dx + vy * dy + vz * dz ) / dn; // cosine with mother motion
						else
							score = dn > 1e-9 ? -dn : 0.0; // no direction: prefer the nearest daughter
						if ( score > bestScore )
						{
							bestScore = score;
							bestEdgeIdx = e.getInternalPoolIndex();
						}
					}
					for ( final Link e : mother.outgoingEdges() )
					{
						if ( e.getInternalPoolIndex() == bestEdgeIdx )
							continue;
						e.getTarget( dRef );
						if ( ProtectedSpots.isProtected( ctx, model, dRef ) )
							continue; // a confirmed daughter's link is never cut, whatever the heuristic says
						final Link copy = graph.edgeRef();
						copy.refTo( e );
						toCut.add( copy );
					}
				}
				for ( final Link link : toCut )
					GraphEdits.removeLink( ctx, graph, link, GraphEdits.Reason.TOPOLOGY );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			if ( !toCut.isEmpty() )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return toCut.size();
		}
		finally
		{
			graph.releaseRef( cursorRef );
			graph.releaseRef( dRef );
			graph.releaseRef( predRef );
			for ( final Link link : toCut )
				graph.releaseRef( link );
		}
	}

	/**
	 * How far, in the frame's median radii, a link may be moved to a detection left without a
	 * predecessor. Same-cell one-frame links are 0.14 radii at the median and 0.75 at p99 on HeLa
	 * ({@code benchmark/lineage-review/measure_swaps.py}).
	 */
	static final double RELINK_REACH = 1.0;

	/** A free detection is taken over a linked one only when this much nearer: d(A,S) < this x d(A,B). */
	static final double RELINK_NEARER = 0.6;

	/**
	 * Moves links that went to a neighbour's detection while the cell's own detection at t+1 sat next
	 * to it with no predecessor. Both arise when a detection is added or linked after its neighbour was:
	 * recoverMissingPredecessors adds a cell's detection back after the orphan linker has already given
	 * the frame's one detection to the wrong sister, or the linker breaks a near tie the wrong way.
	 *
	 * <p>For a one-frame link A->B (A's only successor, B's only predecessor), with S a detection at t+1
	 * with no predecessor and E a detection at t with no successor:</p>
	 * <ul>
	 * <li>nearer: S within {@link #RELINK_REACH} radii of A and nearer than {@link #RELINK_NEARER} x
	 * d(A,B) -- relink A->S, and E->B when an E lies within reach of B;</li>
	 * <li>swap: otherwise, an E within reach of B and an S within reach of A -- relink A->S and E->B,
	 * which joins two broken tracks instead of one jump.</li>
	 * </ul>
	 * <p>Scored on the final graphs of three HeLa runs ({@code measure_relink.py}, {@code measure_swaps.py}):
	 * nearer fixed 12, 5 and 9 jumps between cells and broke none; swap fixed 2, 0 and 0 (one of them
	 * the user's t=13 example) and broke none. Protected spots and fp negatives are never touched.</p>
	 *
	 * <p>Distances are in 3-D on a stack: measured in the plane only, a detection far above or below a cell
	 * read as its own, and the pass swapped two cells stacked in z (a user's mouse embryo, t=478->479).</p>
	 *
	 * @return links moved
	 */
	public static int relinkToFreeDetections( final ToolContext ctx, final int scanStartTp, final int numTimepoints )
	{
		return withGeometry( ctx.isVolumetric(), () -> relinkToFreeDetectionsIn( ctx, scanStartTp, numTimepoints ) );
	}

	private static int relinkToFreeDetectionsIn( final ToolContext ctx, final int scanStartTp, final int numTimepoints )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		int moved = 0;
		final Map< Integer, List< Spot > > byT = new HashMap<>();
		final Spot bRef = graph.vertexRef();
		final Link eRef = graph.edgeRef();
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int t = s.getTimepoint();
				if ( t < scanStartTp || t >= numTimepoints
						|| DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, s ) )
						|| ProtectedSpots.isProtected( ctx, model, s ) )
					continue;
				byT.computeIfAbsent( t, k -> new ArrayList<>() ).add( graph.vertexRef().refTo( s ) );
			}
			final double[] pa = new double[ 3 ], pb = new double[ 3 ], q = new double[ 3 ];
			for ( int t = scanStartTp; t < numTimepoints - 1; t++ )
			{
				final List< Spot > now = byT.get( t ), next = byT.get( t + 1 );
				if ( now == null || next == null )
					continue;
				final double reach = RELINK_REACH * medianRadiusAt( graph, t );
				if ( reach <= 0 )
					continue;
				for ( final Spot a : now )
				{
					if ( a.outgoingEdges().size() != 1 )
						continue;
					final Link ab = a.outgoingEdges().iterator().next();
					ab.getTarget( bRef );
					if ( bRef.getTimepoint() != t + 1 || bRef.incomingEdges().size() != 1
							|| !next.contains( bRef ) )
						continue; // a gap link, or B is protected / fp / shared
					a.localize( pa );
					bRef.localize( pb );
					final double dab = Math.sqrt( dist2( pa, pb ) );
					Spot s = null, e = null;
					double ds = Double.POSITIVE_INFINITY;
					for ( final Spot c : next )
						if ( c.incomingEdges().isEmpty() && c.getInternalPoolIndex() != bRef.getInternalPoolIndex() )
						{
							c.localize( q );
							final double d = Math.sqrt( dist2( pa, q ) );
							if ( d <= reach && d < ds )
							{
								ds = d;
								s = c;
							}
						}
					if ( s == null )
						continue;
					double de = Double.POSITIVE_INFINITY;
					for ( final Spot c : now )
						if ( c.outgoingEdges().isEmpty() && c.getInternalPoolIndex() != a.getInternalPoolIndex() )
						{
							c.localize( q );
							final double d = Math.sqrt( dist2( pb, q ) );
							if ( d <= reach && d < de )
							{
								de = d;
								e = c;
							}
						}
					final boolean nearer = ds < RELINK_NEARER * dab;
					if ( !nearer && e == null )
						continue;
					final Spot b = graph.vertexRef().refTo( bRef );
					GraphEdits.removeLink( ctx, graph, ab, GraphEdits.Reason.TOPOLOGY );
					GraphEdits.addLink( ctx, graph, a, s, eRef, GraphEdits.Reason.TOPOLOGY );
					if ( e != null )
						GraphEdits.addLink( ctx, graph, e, b, eRef, GraphEdits.Reason.TOPOLOGY );
					graph.releaseRef( b );
					moved++;
				}
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
			for ( final List< Spot > l : byT.values() )
				for ( final Spot s : l )
					graph.releaseRef( s );
			graph.releaseRef( bRef );
			graph.releaseRef( eRef );
		}
		if ( moved > 0 )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
			ctx.log( String.format( "  Moved %d link(s) from a neighbour's detection to the cell's own, which had "
					+ "been left with no predecessor.", moved ) );
		}
		return moved;
	}

	/**
	 * The nearer daughter of a real division lies at least this far from where the mother was, in the
	 * frame's median radii: both daughters move apart from the mother's centre. Measured on the final
	 * graphs of three HeLa runs ({@code benchmark/lineage-review/division_features.py}): the nearer
	 * daughter of a true division at a median 0.77 radii, of a false one at 0.21 -- most false ones are
	 * the mother's own cell carried on in place plus a neighbour, or a detection on no cell, linked in
	 * as a second daughter because its own track had a gap.
	 */
	static final double DIVISION_MIN_NEAR_JUMP = 0.4;

	/** ...and the two daughters go apart, not the same way: the angle between their jumps, degrees. */
	static final double DIVISION_MIN_ANGLE_DEG = 90;

	/** The user's answer to a division question. */
	enum DivisionAnswer
	{
		/** A: a real division, keep both branches. */
		DIVIDES,
		/** B: one cell, cut the branch that jumped. */
		ONE_CELL,
		/** C: neither -- the user fixed it by hand, and their edits are protected. */
		EDITED,
		/** No answer: stop asking. */
		NONE
	}

	/** Whether a division whose geometry fails is real; see {@link DivisionAnswer}. */
	interface DivisionQuestion
	{
		DivisionAnswer ask( int t, double[] mother, double motherRadius, double[] stays, double[] jumps, String why );
	}

	/** {@link ToolContext#getSharedData} key, {@link Boolean}: the user said to stop asking about divisions. */
	static final String SHARED_DIVISION_STOP_ASKING = "autocorrect.division_stop_asking";

	/**
	 * Cuts the jumping branch of divisions whose daughters do not move apart from the mother -- one stays
	 * where the mother was ({@link #DIVISION_MIN_NEAR_JUMP}) or both go the same way
	 * ({@link #DIVISION_MIN_ANGLE_DEG}) -- asking the user first when a dialog can be shown. On three
	 * runs the rule flags 25-42 of 31-50 false divisions and 3-4 of 73-77 real ones, the same three
	 * real ones each time; so it asks, and decides alone only when nobody can be asked.
	 *
	 * @return links cut
	 */
	public static int dissolveStayingDivisions( final ToolContext ctx, final int scanStartTp, final int numTimepoints )
	{
		return dissolveStayingDivisions( ctx, scanStartTp, numTimepoints, divisionQuestion( ctx ) );
	}

	/** Who answers a division question: the oracle (benchmark), else the user where a dialog can be shown, else nobody. */
	static DivisionQuestion divisionQuestion( final ToolContext ctx )
	{
		final QuestionOracle oracle = QuestionOracle.of( ctx );
		if ( oracle != null )
			return ( t, m, r, stays, jumps, why ) -> {
				final Boolean divides = oracle.divides( t, m, stays, jumps );
				ctx.log( String.format( "  Oracle at t=%d (%.1f, %.1f): %s", t, m[ 0 ], m[ 1 ],
						divides == null ? "cannot say -- kept" : divides ? "a real division" : "one cell" ) );
				return Boolean.FALSE.equals( divides ) ? DivisionAnswer.ONE_CELL : DivisionAnswer.DIVIDES;
			};
		return ctx.canChooseImages() ? ( t, m, r, stays, jumps, why ) -> askDivision( ctx, t, m, r, stays, jumps, why )
				: null;
	}

	static int dissolveStayingDivisions( final ToolContext ctx, final int scanStartTp, final int numTimepoints,
			final DivisionQuestion question )
	{
		return withGeometry( ctx.isVolumetric(),
				() -> dissolveStayingDivisionsIn( ctx, scanStartTp, numTimepoints, question ) );
	}

	private static int dissolveStayingDivisionsIn( final ToolContext ctx, final int scanStartTp, final int numTimepoints,
			final DivisionQuestion question )
	{
		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		// {mother, stays, jumps, {t, mother radius, near jump in radii, angle}}, read under the lock and
		// acted on one by one after it: a question must not hold the lock, and after a hand edit the
		// spots are found again by position, never through a ref the user may have deleted.
		final List< double[][] > flagged = new ArrayList<>();
		final Spot dRef = graph.vertexRef();
		graph.getLock().readLock().lock();
		try
		{
			final Map< Integer, Double > frameRadius = new HashMap<>();
			for ( final Spot mother : graph.vertices() )
			{
				final int t = mother.getTimepoint();
				if ( mother.outgoingEdges().size() != 2 || t < scanStartTp || t >= numTimepoints - 1
						|| ProtectedSpots.isProtected( ctx, model, mother ) )
					continue;
				final double[] m = new double[ 3 ];
				mother.localize( m );
				final double[][] kid = new double[ 2 ][ 3 ];
				int k = 0;
				boolean confirmed = false;
				for ( final Link e : mother.outgoingEdges() )
				{
					e.getTarget( dRef );
					dRef.localize( kid[ k++ ] );
					confirmed |= ProtectedSpots.isProtected( ctx, model, dRef );
				}
				if ( confirmed )
					continue; // a confirmed daughter's link is never cut
				final double fr = frameRadius.computeIfAbsent( t, tt -> medianRadiusAt( graph, tt ) );
				if ( fr <= 0 )
					continue;
				final double[] geometry = divisionGeometry( m, kid[ 0 ], kid[ 1 ], fr );
				if ( geometry[ 0 ] >= DIVISION_MIN_NEAR_JUMP && geometry[ 1 ] >= DIVISION_MIN_ANGLE_DEG )
					continue;
				final boolean firstStays = dist2( kid[ 0 ], m ) <= dist2( kid[ 1 ], m );
				flagged.add( new double[][] { m, firstStays ? kid[ 0 ] : kid[ 1 ], firstStays ? kid[ 1 ] : kid[ 0 ],
						{ t, Math.sqrt( mother.getBoundingSphereRadiusSquared() ), geometry[ 0 ], geometry[ 1 ], fr } } );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( dRef );
		}
		final AnswerMemory memory = AnswerMemory.of( ctx );
		int cut = 0, asked = 0;
		for ( final double[][] f : flagged )
		{
			final int t = ( int ) f[ 3 ][ 0 ];
			final double fr = f[ 3 ][ 4 ];
			final AnswerMemory.Said before = memory.recall( AnswerMemory.Topic.DIVISION, t, f[ 0 ], fr );
			if ( before == AnswerMemory.Said.KEEP || before == AnswerMemory.Said.HAND )
				continue;
			if ( before == null && question != null
					&& !Boolean.TRUE.equals( ctx.getSharedData( SHARED_DIVISION_STOP_ASKING ) ) )
			{
				final DivisionAnswer answer = question.ask( t, f[ 0 ], f[ 3 ][ 1 ], f[ 1 ], f[ 2 ],
						whyDivisionAsked( f[ 3 ][ 2 ], f[ 3 ][ 3 ] ) );
				asked++;
				if ( answer == DivisionAnswer.NONE )
				{
					ctx.setSharedData( SHARED_DIVISION_STOP_ASKING, Boolean.TRUE );
					ctx.log( String.format( "  Division question at t=%d (%.1f, %.1f): no answer -- no more division "
							+ "questions this run; the rest are cut automatically.", t, f[ 0 ][ 0 ], f[ 0 ][ 1 ] ) );
				}
				else
				{
					ctx.log( String.format( "  Division question at t=%d (%.1f, %.1f): you chose %s.", t, f[ 0 ][ 0 ],
							f[ 0 ][ 1 ], answer == DivisionAnswer.DIVIDES ? "A, a real division"
									: answer == DivisionAnswer.ONE_CELL ? "B, one cell" : "C, fixed by hand" ) );
					if ( answer != DivisionAnswer.ONE_CELL )
					{
						memory.remember( AnswerMemory.Topic.DIVISION, t, f[ 0 ],
								answer == DivisionAnswer.DIVIDES ? AnswerMemory.Said.KEEP : AnswerMemory.Said.HAND, fr );
						continue;
					}
				}
			}
			memory.remember( AnswerMemory.Topic.DIVISION, t, f[ 0 ], AnswerMemory.Said.CUT, fr );
			if ( cutBranch( ctx, graph, t, f[ 0 ], f[ 2 ] ) )
				cut++;
		}
		if ( cut > 0 )
		{
			model.setUndoPoint();
			ctx.notifyGraphChanged();
		}
		if ( !flagged.isEmpty() )
			ctx.log( String.format( "  Divisions whose daughters did not move apart: %d, %d asked about, %d branch "
					+ "link(s) cut.", flagged.size(), asked, cut ) );
		return cut;
	}

	/**
	 * {nearer daughter's jump from the mother in {@code frameRadius}, angle between the two jumps in
	 * degrees}. Two daughters on the mother's own position read as angle 0.
	 */
	static double[] divisionGeometry( final double[] m, final double[] a, final double[] b, final double frameRadius )
	{
		double da = 0, db = 0, dot = 0;
		for ( int d = 0; d < 3; d++ )
		{
			final double u = a[ d ] - m[ d ], v = b[ d ] - m[ d ];
			da += u * u;
			db += v * v;
			dot += u * v;
		}
		da = Math.sqrt( da );
		db = Math.sqrt( db );
		final double angle = da < 1e-9 || db < 1e-9 ? 0.0
				: Math.toDegrees( Math.acos( Math.max( -1, Math.min( 1, dot / ( da * db ) ) ) ) );
		return new double[] { Math.min( da, db ) / frameRadius, angle };
	}

	private static String whyDivisionAsked( final double near, final double angle )
	{
		final String what = near < DIVISION_MIN_NEAR_JUMP
				? String.format( "One branch has hardly moved from where the cell was (%.2f of a cell radius); in a "
						+ "real division both daughters move apart from the mother's centre.", near )
				: String.format( "Both branches went the same way (%.0f degrees apart); real daughters move apart "
						+ "in opposite directions.", angle );
		return what + " Usually the other branch is a neighbouring cell, or a detection on no cell, linked in "
				+ "because its own track had a gap. (On three earlier runs this check flagged about 80% of the "
				+ "false divisions and about 4% of the real ones.)";
	}

	/** Removes the link from the spot at {@code t} on {@code mother} to the one on {@code jumps}, if both are still there. */
	private static boolean cutBranch( final ToolContext ctx, final ModelGraph graph, final int t, final double[] mother,
			final double[] jumps )
	{
		final Spot tRef = graph.vertexRef();
		final Link found = graph.edgeRef();
		final double[] p = new double[ 3 ];
		graph.getLock().writeLock().lock();
		try
		{
			boolean hit = false;
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != t )
					continue;
				s.localize( p );
				if ( dist2( p, mother ) > 1e-6 )
					continue;
				// Asked about, or decided, a while ago: only while it is still that division.
				if ( s.outgoingEdges().size() != 2 || ProtectedSpots.isProtected( ctx, ctx.getModel(), s ) )
					break;
				for ( final Link e : s.outgoingEdges() )
				{
					e.getTarget( tRef ).localize( p );
					if ( dist2( p, jumps ) <= 1e-6 )
					{
						found.refTo( e );
						hit = true;
						break;
					}
				}
				if ( hit )
					break;
			}
			return hit && GraphEdits.removeLink( ctx, graph, found, GraphEdits.Reason.TOPOLOGY );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
			graph.releaseRef( tRef );
			graph.releaseRef( found );
		}
	}


	/**
	 * The mother at t, the frames around it, and both daughters at t+1: A keeps both branches (both
	 * daughters magenta), B cuts the jump (only the one that stays magenta, the other back to cyan).
	 */
	private static DivisionAnswer askDivision( final ToolContext ctx, final int t, final double[] mother,
			final double motherRadius, final double[] stays, final double[] jumps, final String why )
	{
		final double half = Math.max( SPLIT_VIEW_REACH * motherRadius,
				Math.sqrt( dist2( mother, jumps ) ) + 1.5 * motherRadius );
		final List< double[][] > before = ellipsesNear( ctx.getGraph(), t - 1, mother, half );
		final List< double[][] > atT = ellipsesNear( ctx.getGraph(), t, mother, half );
		final List< double[][] > after = ellipsesNear( ctx.getGraph(), t + 1, mother, half );
		final List< double[][] > motherOnly = new ArrayList<>(), othersAtT = new ArrayList<>();
		for ( final double[][] e : atT )
			( dist2( e[ 0 ], mother ) <= 1e-6 ? motherOnly : othersAtT ).add( e );
		final List< double[][] > both = new ArrayList<>(), stayOnly = new ArrayList<>();
		final List< double[][] > othersAfterA = new ArrayList<>(), othersAfterB = new ArrayList<>();
		for ( final double[][] e : after )
		{
			final boolean isStay = dist2( e[ 0 ], stays ) <= 1e-6, isJump = dist2( e[ 0 ], jumps ) <= 1e-6;
			( isStay || isJump ? both : othersAfterA ).add( e );
			( isStay ? stayOnly : othersAfterB ).add( e );
		}
		final double[][] range = new double[ 1 ][];
		final BufferedImage prev = framePanel( ctx, t - 1, mother, half, range );
		final BufferedImage mid = framePanel( ctx, t, mother, half, range );
		final BufferedImage next = framePanel( ctx, t + 1, mother, half, range );
		if ( mid == null || next == null )
			return DivisionAnswer.NONE;
		final BufferedImage[] frames = { prev, mid, next };
		final BufferedImage a = panels( t, mother, half, frames, Arrays.asList( before, othersAtT, othersAfterA ),
				Arrays.asList( java.util.Collections.emptyList(), motherOnly, both ),
				new double[][] { stays, jumps } );
		final BufferedImage b = panels( t, mother, half, frames, Arrays.asList( before, othersAtT, othersAfterB ),
				Arrays.asList( java.util.Collections.emptyList(), motherOnly, stayOnly ), new double[][] { stays } );
		final List< String > buttons = new ArrayList<>( Arrays.asList( "A: a real division — keep both",
				"B: one cell — cut the jump", "C: neither — I'll fix it in the viewer",
				"Stop asking about divisions (cut the rest automatically)" ) );
		final int k = ctx.chooseImage( String.format(
				"t=%d at (%.0f, %.0f): is this a real division?%n%s%n"
						+ "Each picture shows t=%d, t=%d and t=%d, left to right, every detection drawn on (cyan). "
						+ "The cell at t=%d is magenta, and its daughters at t=%d magenta with a line from where it was.",
				t, mother[ 0 ], mother[ 1 ], why, t - 1, t, t + 1, t, t + 1 ),
				Arrays.asList( a, b ), Arrays.asList( "A: divides", "B: one cell" ), buttons );
		if ( k == 2 && UserEdits.letUserEdit( ctx, t, mother, String.format(
				"The viewer is at t=%d, centred on (%.0f, %.0f). Fix it there -- add, move, delete or link "
						+ "spots in BDV or TrackScheme, at this timepoint or any other -- and press Done.%n%n"
						+ "Everything you add or change is tagged as protected for the rest of this run, so no "
						+ "later pass deletes, merges, splits or cuts it. The run waits until you press Done or "
						+ "Cancel.", t, mother[ 0 ], mother[ 1 ] ) ) )
			return DivisionAnswer.EDITED;
		if ( k == 0 || k == 2 )
			return DivisionAnswer.DIVIDES; // C with nobody to wait for leaves it as it is
		if ( k == 1 )
			return DivisionAnswer.ONE_CELL;
		return DivisionAnswer.NONE;
	}

	public static int removeRapidSuccessiveDivisions(
			final ToolContext ctx,
			final int numTimepoints,
			final int minDivisionInterval )
	{
		return removeRapidSuccessiveDivisions( ctx, 0, numTimepoints, minDivisionInterval );
	}

	/**
	 * As {@link #removeRapidSuccessiveDivisions(ToolContext, int, int)}, but only considers division
	 * points at {@code t >= scanStartTp} (0 = scan the whole range) — the backward walk to find a recent
	 * ancestor division is unaffected and may still walk earlier than {@code scanStartTp}. Timepoints
	 * before {@code scanStartTp} were already checked by an earlier pass and are left untouched.
	 */
	public static int removeRapidSuccessiveDivisions(
			final ToolContext ctx,
			final int scanStartTp,
			final int numTimepoints,
			final int minDivisionInterval )
	{
		if ( minDivisionInterval <= 0 )
			return 0;

		final ModelGraph graph = ctx.getGraph();
		final Spot cursorRef = graph.vertexRef();
		final Spot tgtRef = graph.vertexRef();

		// Phase 1 (read): find division spots whose ancestor divided too recently
		final List< Integer > rapidDivisionIds = new ArrayList<>();
		final List< Integer > ancestorSteps = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.outgoingEdges().size() < 2 )
					continue; // not a division
				if ( s.getTimepoint() < scanStartTp )
					continue;
				if ( ProtectedSpots.isProtected( ctx, ctx.getModel(), s ) )
					continue; // its outgoing links are left exactly as curated, whatever they are
				// Walk backward to check for a recent ancestor division
				cursorRef.refTo( s );
				int steps = 0;
				boolean recentAncestor = false;
				while ( steps < minDivisionInterval && !cursorRef.incomingEdges().isEmpty() )
				{
					for ( final Link e : cursorRef.incomingEdges() )
					{
						e.getSource( cursorRef );
						break;
					}
					steps++;
					if ( cursorRef.outgoingEdges().size() >= 2 )
					{
						recentAncestor = true;
						break;
					}
					if ( cursorRef.outgoingEdges().size() != 1 )
						break; // non-linear chain
				}
				if ( recentAncestor )
				{
					rapidDivisionIds.add( s.getInternalPoolIndex() );
					// How recently. A run dissolved 276 divisions in one whole-movie pass and the
					// question nobody could answer was whether those were fabrications the refractory
					// rule is for, or real mitoses it was cutting. If the ancestors sit one or two
					// timepoints back they are fabrications; if they spread out towards the threshold
					// the threshold is what is wrong, and this is the number that says which.
					ancestorSteps.add( steps );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( rapidDivisionIds.isEmpty() )
		{
			graph.releaseRef( cursorRef );
			graph.releaseRef( tgtRef );
			return 0;
		}

		// Phase 2 (write): for each rapid-division spot, remove the shorter daughter edge
		int removed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( !rapidDivisionIds.contains( s.getInternalPoolIndex() ) )
					continue;
				if ( s.outgoingEdges().size() < 2 )
					continue; // may have been already cleaned up

				// Find the outgoing edge whose target has the fewest descendants
				Link weakestEdge = null;
				int minDescendants = Integer.MAX_VALUE;
				final List< Link > edgeRefs = new ArrayList<>();
				for ( final Link e : s.outgoingEdges() )
				{
					final Link eRef = graph.edgeRef();
					eRef.refTo( e );
					edgeRefs.add( eRef );
				}
				for ( final Link e : edgeRefs )
				{
					e.getTarget( tgtRef );
					if ( ProtectedSpots.isProtected( ctx, ctx.getModel(), tgtRef ) )
						continue; // a confirmed daughter's link is never cut, whatever the heuristic says
					final int desc = countLinearDescendants( graph, tgtRef, cursorRef );
					if ( desc < minDescendants )
					{
						minDescendants = desc;
						weakestEdge = e;
					}
				}
				if ( weakestEdge != null )
				{
					GraphEdits.removeLink( ctx, graph, weakestEdge, GraphEdits.Reason.TOPOLOGY );
					removed++;
				}
				for ( final Link e : edgeRefs )
					graph.releaseRef( e );
			}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
			graph.releaseRef( cursorRef );
			graph.releaseRef( tgtRef );
		}

		if ( removed > 0 )
		{
			ancestorSteps.sort( null );
			ctx.log( String.format(
					"    refractory rule cut %d of %d division(s) it judged too soon after an ancestor "
							+ "division (threshold %d tp). Ancestor was %d tp back at the median, %d at the "
							+ "earliest, %d at the latest — the closer these are to 1, the more of them "
							+ "were fabricated rather than real mitoses.",
					removed, rapidDivisionIds.size(), minDivisionInterval,
					ancestorSteps.get( ancestorSteps.size() / 2 ), ancestorSteps.get( 0 ),
					ancestorSteps.get( ancestorSteps.size() - 1 ) ) );
			ctx.getModel().setUndoPoint();
			ctx.notifyGraphChanged();
		}
		log.info( "removeRapidSuccessiveDivisions: removed {} edge(s) (minDivisionInterval={})",
				removed, minDivisionInterval );
		return removed;
	}

	/** Counts descendants reachable via a single outgoing edge (linear chain only). */
	private static int countLinearDescendants( final ModelGraph graph, final Spot start,
			final Spot cursorRef )
	{
		int count = 1;
		cursorRef.refTo( start );
		while ( cursorRef.outgoingEdges().size() == 1 )
		{
			for ( final Link e : cursorRef.outgoingEdges() )
			{
				e.getTarget( cursorRef );
				break;
			}
			count++;
		}
		return count;
	}

	// -----------------------------------------------------------------------

	/**
	 * Links orphan track-start spots at {@code timepoint} to the nearest available
	 * predecessor at {@code timepoint - 1}.
	 *
	 * <p>An "orphan start" is a spot at t &gt; 0 with no incoming edge. This arises
	 * from detection gaps (predecessor missed by ELEPHANT), link pruning (long link
	 * removed by correction), or window-boundary effects in adaptive_track.
	 * This method links each orphan to the nearest predecessor at t-1 that has not
	 * yet reached the binary division maximum (fewer than 2 outgoing edges).</p>
	 *
	 * <p>Unlike {@link #linkDivisionDaughters}, which only connects to cells that
	 * are already dividing (exactly 1 outgoing), this method also connects to
	 * non-dividing cells (0 outgoing), covering the general case where a cell's
	 * only child was missed.</p>
	 *
	 * @param ctx          tool context
	 * @param timepoint    timepoint at which to link orphan starts (must be &gt; 0)
	 * @param searchFactor links if nearest predecessor is within
	 *                     {@code searchFactor × max(r_orphan, r_pred)}
	 * @return number of links added
	 */
	static int linkOrphanStarts(
			final ToolContext ctx,
			final int timepoint,
			final double searchFactor )
	{
		return linkOrphanStarts( ctx, timepoint, searchFactor, false, 0.0 );
	}

	static int linkOrphanStarts(
			final ToolContext ctx,
			final int timepoint,
			final double searchFactor,
			final boolean requireFullyIsolated )
	{
		return linkOrphanStarts( ctx, timepoint, searchFactor, requireFullyIsolated, 0.0 );
	}

	/**
	 * Links track-start (no-incoming) spots at {@code timepoint} to a nearby predecessor at t-1 that
	 * still has room (&lt;2 outgoing edges). When {@code requireFullyIsolated} is true, only spots with
	 * NO links at all are considered — used for a generous, larger-radius rescue pass that connects
	 * left-behind post-division daughters (e.g. at the last timepoint) to their dividing mother
	 * instead of letting them be deleted as isolated.
	 */
	static int linkOrphanStarts(
			final ToolContext ctx,
			final int timepoint,
			final double searchFactor,
			final boolean requireFullyIsolated,
			final double maxDisplacement )
	{
		if ( timepoint == 0 || searchFactor <= 0.0 )
			return 0;

		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final int prevTp = timepoint - 1;

		// Phase 1: collect orphan starts at t and candidate predecessors at t-1 (read lock)
		final List< Integer > orphanIds = new ArrayList<>();
		final List< double[] > orphanPositions = new ArrayList<>();
		final List< Double > orphanRadii = new ArrayList<>();

		final List< Integer > predIds = new ArrayList<>();
		final List< double[] > predPositions = new ArrayList<>();
		final List< Double > predRadii = new ArrayList<>();
		final List< Integer > predOutgoing = new ArrayList<>();

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int tp = s.getTimepoint();
				// fp spots are detection-training negatives, never tracking objects — exclude them from BOTH
				// ends of any reconnection (never an orphan to re-home, never a predecessor to link to).
				if ( DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, s ) ) )
					continue;
				if ( tp == timepoint && s.incomingEdges().isEmpty()
						&& ( !requireFullyIsolated || s.outgoingEdges().isEmpty() ) )
				{
					final double[] pos = new double[ 3 ];
					s.localize( pos );
					orphanIds.add( s.getInternalPoolIndex() );
					orphanPositions.add( pos );
					orphanRadii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
				}
				else if ( tp == prevTp && s.outgoingEdges().size() < 2
						&& !ProtectedSpots.isProtected( ctx, model, s ) )
				{
					// Any spot at t-1 with fewer than 2 outgoing edges can accept a new link — EXCEPT a
					// protected one: a spot tagged as an already-finished, trusted result must not gain a
					// new successor/division it never had, however plausible the geometry looks.
					final double[] pos = new double[ 3 ];
					s.localize( pos );
					predIds.add( s.getInternalPoolIndex() );
					predPositions.add( pos );
					predRadii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
					predOutgoing.add( s.outgoingEdges().size() );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( orphanIds.isEmpty() || predIds.isEmpty() )
			return 0;

		// GLOBAL best-match assignment (NOT order-dependent greedy): build every (orphan, predecessor)
		// pair that passes the distance gates, sort by a cost that rewards being BOTH near AND similar in
		// size (cost = distance × size-ratio), then assign lowest-cost first. So when several orphans
		// compete for the same predecessor, the nearest + most similar one wins it — a later-processed or
		// farther/less-similar orphan can no longer steal it just by being handled first. Each orphan gets
		// at most one predecessor; each predecessor at most 2 successors (a division).
		final int[] reservedOutgoing = new int[ predIds.size() ];
		for ( int i = 0; i < predIds.size(); i++ )
			reservedOutgoing[ i ] = predOutgoing.get( i );

		// A rescue has to justify itself. requireFullyIsolated is the pass whose ONLY purpose is to stop
		// deleteIsolatedSpots removing a spot — it runs immediately before it, with a deliberately
		// generous radius — and it asked nothing about whether the thing it was saving is a cell. Its
		// gates are all geometric: a free successor slot, a radius-scaled distance, similar size. So a
		// detection sitting on background, near a cell with room, was linked into a track and thereby
		// made permanent, and then kept being tracked: exactly "it keeps tracking empty background".
		//
		// Nothing else in the pipeline catches it afterwards. deleteSpotsWithNoSignal needs a second
		// anchor from the user's confirmed cells and there are none in an autonomous run, so its
		// ceiling collapses to just below the frame's own background median and it deleted 0 in every
		// window of the run this was reported from. deleteUnstableSpots needs the track to span under
		// three timepoints, which a rescued spot no longer does.
		//
		// Measured here, between the read lock and the write lock, because it samples the image.
		// Ordinary orphan linking is NOT gated this way: a dim real cell that the detector only just
		// found still deserves its link, and that path is not overriding a deletion.
		final boolean[] orphanHasSignal = new boolean[ orphanIds.size() ];
		java.util.Arrays.fill( orphanHasSignal, true );
		int refusedAsBackground = 0;
		if ( requireFullyIsolated )
		{
			for ( int oi = 0; oi < orphanIds.size(); oi++ )
			{
				final double standout =
						standoutAboveBackground( ctx, timepoint, orphanPositions.get( oi ) );
				// 0 means nothing could be measured here — no background estimate, no sampler. That is
				// not evidence of background, so the rescue proceeds as it did before.
				if ( standout != 0.0 && standout < DetectionGate.DEEP_BACKGROUND_SIGMA )
				{
					orphanHasSignal[ oi ] = false;
					refusedAsBackground++;
				}
			}
			if ( refusedAsBackground > 0 )
				ctx.log( String.format( "  t=%d — %d isolated spot(s) left unrescued: they read below "
						+ "%.1f sigma above this frame's background, so linking them would only make a "
						+ "background detection permanent.",
						timepoint, refusedAsBackground, DetectionGate.DEEP_BACKGROUND_SIGMA ) );
		}

		// Candidate pairs as {cost, dist, oi, pi}, filtered by the STATIC distance gates only.
		final List< double[] > pairs = new ArrayList<>();
		for ( int oi = 0; oi < orphanIds.size(); oi++ )
		{
			if ( !orphanHasSignal[ oi ] )
				continue; // reads as background — see above; a rescue must not make that permanent
			final double[] op = orphanPositions.get( oi );
			final double ro = orphanRadii.get( oi );
			for ( int pi = 0; pi < predIds.size(); pi++ )
			{
				final double[] pp = predPositions.get( pi );
				final double rp = predRadii.get( pi );
				double distSq = 0;
				for ( int d = 0; d < 3; d++ )
				{
					final double diff = op[ d ] - pp[ d ];
					distSq += diff * diff;
				}
				final double dist = Math.sqrt( distSq );
				// The per-frame displacement cap bounds ORDINARY motion, and it is measured from
				// ordinary motion: four times the median consecutive-link length. A mitosis is not
				// ordinary motion. Measured on the CTC tracking ground truth
				// (benchmark/division-displacement), the step from a mother's last centroid to a
				// daughter's first has median 10.6 / 9.8 µm against an ordinary median of 1.5 / 0.7 —
				// seven to fifteen times larger — and the cap the run computed (5.8 µm) REFUSES 87%
				// and 98% of the real mother-to-daughter joins in the two sequences.
				//
				// So the cap applies to a 1:1 continuation, where it belongs, and not to a link that
				// would make the predecessor divide. That case keeps the radius-scaled bound, which is
				// the right basis for it: the division step is about one nuclear diameter, and the
				// ground-truth p99 is 20.8 / 19.1 µm against a spot radius near 9.5 — just over two
				// radii. Scaling the displacement cap instead cannot work, because the ordinary median
				// differs by 2.3x between the two sequences while the division step barely differs at
				// all: at factor 12 the cap still refuses 71% in seq 02.
				// One rule, shared with the report that claims these joins were possible. The size gate
				// is re-applied at assignment time below, where reservedOutgoing says whether this link
				// would actually be a division; here it can only be more permissive, so it drops nothing
				// the assignment would have taken.
				if ( orphanJoinRefusal( dist, ro, rp, predOutgoing.get( pi ), searchFactor,
						maxDisplacement ) != null )
					continue;
				final double sizeRatio = Math.max( ro, rp ) / Math.max( 1e-9, Math.min( ro, rp ) );
				pairs.add( new double[] { dist * sizeRatio, dist, oi, pi } );
			}
		}
		pairs.sort( ( a, b ) -> Double.compare( a[ 0 ], b[ 0 ] ) );

		final Map< Integer, Integer > orphanToPred = new HashMap<>();
		final boolean[] orphanAssigned = new boolean[ orphanIds.size() ];
		for ( final double[] pr : pairs )
		{
			final int oi = ( int ) pr[ 2 ];
			final int pi = ( int ) pr[ 3 ];
			if ( orphanAssigned[ oi ] || reservedOutgoing[ pi ] >= 2 )
				continue;
			final double ro = orphanRadii.get( oi );
			final double rp = predRadii.get( pi );
			// Now reservedOutgoing knows whether this link would make the predecessor DIVIDE, so the
			// shared rule picks the right size gate: a real daughter is meaningfully smaller than its
			// mother, while a 1:1 continuation is the same cell and must look the same size.
			if ( orphanJoinRefusal( pr[ 1 ], ro, rp, reservedOutgoing[ pi ], searchFactor,
					maxDisplacement ) != null )
				continue;
			orphanToPred.put( orphanIds.get( oi ), predIds.get( pi ) );
			orphanAssigned[ oi ] = true;
			reservedOutgoing[ pi ]++;
		}

		if ( orphanToPred.isEmpty() )
			return 0;

		// Phase 2: add edges (write lock)
		int linked = 0;
		final Spot orphanRef = graph.vertexRef();
		final Spot predRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();

		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot s : graph.vertices() )
				{
					final int id = s.getInternalPoolIndex();
					if ( !orphanToPred.containsKey( id ) )
						continue;
					if ( !s.incomingEdges().isEmpty() )
						continue; // re-validate
					orphanRef.refTo( s );
					final int predId = orphanToPred.get( id );
					for ( final Spot p : graph.vertices() )
					{
						if ( p.getInternalPoolIndex() == predId )
						{
							if ( p.outgoingEdges().size() >= 2 )
								break; // re-validate: already at max
							predRef.refTo( p );
							GraphEdits.addLink( ctx, graph, predRef, orphanRef, edgeRef, GraphEdits.Reason.TOPOLOGY );
							linked++;
							break;
						}
					}
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			if ( linked > 0 )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return linked;
		}
		finally
		{
			graph.releaseRef( orphanRef );
			graph.releaseRef( predRef );
			graph.releaseRef( edgeRef );
		}
	}

	// -----------------------------------------------------------------------

	/**
	 * Links orphan track-start spots at {@code timepoint} to a dividing mother
	 * cell at {@code timepoint - 1}.
	 *
	 * <p>A spot at {@code timepoint} is an "orphan daughter" when it has no
	 * incoming edges.  A spot at {@code timepoint - 1} is a valid mother
	 * candidate when it already has at least one outgoing edge (i.e. it is a
	 * dividing cell).</p>
	 *
	 * <p>For each orphan the nearest such mother within
	 * {@code searchFactor × max(r_orphan, r_mother)} is chosen and linked.</p>
	 *
	 * @return the number of edges added
	 */
	static int linkDivisionDaughters(
			final ToolContext ctx,
			final int timepoint,
			final double searchFactor )
	{
		if ( timepoint == 0 )
			return 0;

		final ModelGraph graph = ctx.getGraph();
		final Model model = ctx.getModel();
		final int prevTp = timepoint - 1;

		// Phase 1: collect orphan daughters at t and mother candidates at t-1 (read lock)
		final List< Integer > orphanIds = new ArrayList<>();
		final List< double[] > orphanPositions = new ArrayList<>();
		final List< Double > orphanRadii = new ArrayList<>();

		final List< Integer > motherIds = new ArrayList<>();
		final List< double[] > motherPositions = new ArrayList<>();
		final List< Double > motherRadii = new ArrayList<>();

		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				final int tp = s.getTimepoint();
				if ( tp == timepoint && s.incomingEdges().isEmpty() )
				{
					final double[] pos = new double[ 3 ];
					s.localize( pos );
					orphanIds.add( s.getInternalPoolIndex() );
					orphanPositions.add( pos );
					orphanRadii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
				}
				else if ( tp == prevTp && s.outgoingEdges().size() == 1
						&& !ProtectedSpots.isProtected( ctx, model, s ) )
				{
					// Only consider cells that currently have exactly 1 daughter —
					// they are dividing but missing a second daughter. Cells with 0
					// outgoing edges are not dividing, and cells with ≥2 outgoing
					// edges already have their maximum daughters (binary division).
					// A protected mother is excluded regardless: it must not gain a new daughter it
					// never had, however plausible the geometry looks.
					final double[] pos = new double[ 3 ];
					s.localize( pos );
					motherIds.add( s.getInternalPoolIndex() );
					motherPositions.add( pos );
					motherRadii.add( Math.sqrt( s.getBoundingSphereRadiusSquared() ) );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}

		if ( orphanIds.isEmpty() || motherIds.isEmpty() )
			return 0;

		// Match each orphan to its nearest mother within searchFactor × max(r_orphan, r_mother)
		final Map< Integer, Integer > orphanToMother = new HashMap<>();
		for ( int oi = 0; oi < orphanIds.size(); oi++ )
		{
			final double[] op = orphanPositions.get( oi );
			final double ro = orphanRadii.get( oi );
			double bestDist = Double.MAX_VALUE;
			int bestMotherId = -1;
			for ( int mi = 0; mi < motherIds.size(); mi++ )
			{
				final double[] mp = motherPositions.get( mi );
				final double rm = motherRadii.get( mi );
				// Every match here gives the mother its SECOND daughter — a division. Real division
				// daughters are meaningfully smaller than their mother (see DAUGHTER_SIZE_RATIO_MAX); an
				// orphan that is NOT smaller reads as an unrelated cell that merely happens to be nearby
				// — most often because ITS OWN true predecessor at t-1 was simply undetected — not a real
				// division, so reject it here rather than fabricate one.
				if ( ro > DAUGHTER_SIZE_RATIO_MAX * rm )
					continue;
				double distSq = 0;
				for ( int d = 0; d < 3; d++ )
				{
					final double diff = op[ d ] - mp[ d ];
					distSq += diff * diff;
				}
				final double dist = Math.sqrt( distSq );
				if ( dist < searchFactor * Math.max( ro, rm ) && dist < bestDist )
				{
					bestDist = dist;
					bestMotherId = motherIds.get( mi );
				}
			}
			if ( bestMotherId >= 0 )
				orphanToMother.put( orphanIds.get( oi ), bestMotherId );
		}

		if ( orphanToMother.isEmpty() )
			return 0;

		// Phase 2: add edges (write lock)
		int linked = 0;
		final Spot orphanRef = graph.vertexRef();
		final Spot motherRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();

		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot s : graph.vertices() )
				{
					final int id = s.getInternalPoolIndex();
					if ( !orphanToMother.containsKey( id ) )
						continue;
					if ( !s.incomingEdges().isEmpty() )
						continue; // re-validate: state may have changed
					orphanRef.refTo( s );
					final int motherId = orphanToMother.get( id );
					for ( final Spot m : graph.vertices() )
					{
						if ( m.getInternalPoolIndex() == motherId )
						{
							motherRef.refTo( m );
							GraphEdits.addLink( ctx, graph, motherRef, orphanRef, edgeRef, GraphEdits.Reason.TOPOLOGY );
							linked++;
							break;
						}
					}
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			if ( linked > 0 )
			{
				model.setUndoPoint();
				ctx.notifyGraphChanged();
			}
			return linked;
		}
		finally
		{
			graph.releaseRef( orphanRef );
			graph.releaseRef( motherRef );
			graph.releaseRef( edgeRef );
		}
	}

	// -----------------------------------------------------------------------

	/** Summary of corrections applied to a single timepoint. */
	public static class CorrectionResult
	{
		public final int mergedSpots;

		public final int filledGaps;

		public final int deletedLinks;

		public final int relinked;

		public final int deletedSpots;

		public final int refitSpots;

		public CorrectionResult( final int mergedSpots, final int filledGaps,
				final int deletedLinks, final int relinked,
				final int deletedSpots, final int refitSpots )
		{
			this.mergedSpots = mergedSpots;
			this.filledGaps = filledGaps;
			this.deletedLinks = deletedLinks;
			this.relinked = relinked;
			this.deletedSpots = deletedSpots;
			this.refitSpots = refitSpots;
		}

		public boolean hasChanges()
		{
			return mergedSpots > 0 || filledGaps > 0 || deletedLinks > 0
					|| deletedSpots > 0 || refitSpots > 0;
		}

		@Override
		public String toString()
		{
			return String.format(
					"merged=%d, filled=%d, deleted links=%d, relinked=%s, deleted spots=%d, refit=%d",
					mergedSpots, filledGaps, deletedLinks,
					relinked > 0 ? "yes" : "no", deletedSpots, refitSpots );
		}
	}
}
