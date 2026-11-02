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
package org.mastodon.mamut.automahout.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.SwingUtilities;

import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.impl.FindMaximaTool;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

import bdv.viewer.ViewerPanel;

/**
 * Answers one question the aggregate counts cannot: a cell that is plainly bright in the image but
 * absent from the final result — was it <em>never detected</em>, or was it detected and then deleted
 * by one of the cleanup passes?
 *
 * <p>Every count in the log is a total. A window that commits 440 detections/frame and validates at
 * 343 tells you 97 went away; it does not tell you whether the bright blob you are looking at in
 * BigDataViewer is one of them, and no amount of re-reading the totals will. Answering it by
 * reasoning over the code has repeatedly failed here — the pipeline has ~39 graph-mutation sites and
 * the deletions are spread across two classes and five phases.</p>
 *
 * <p>So this observes instead, the same way {@link PipelineInvariants} does. Just before the
 * full-frame commit it picks the brightest model-free intensity maxima (the same
 * {@code FindMaximaTool.scanImageSpaceMaxima} scan the completeness metric already trusts, so a
 * watchpoint is bright by the pipeline's own definition, not by eye), then after each subsequent
 * pass it records whether a spot still sits within a cell radius of each one. What comes out is a
 * per-location verdict naming the pass that removed it — or saying no pass ever put a spot there,
 * which is a detection failure and not a deletion at all.</p>
 *
 * <p>Armed at commit rather than at the window's start on purpose: the tuning phase creates and
 * wholesale-deletes detections several times over, so transitions recorded there would say nothing
 * about the result and would drown the ones that do.</p>
 */
public final class BrightSpotAudit
{
	/** {@link ToolContext#getSharedData} key holding the armed {@link Watchpoint} list. */
	public static final String SHARED_KEY = "audit.bright_spots";

	/** Brightest maxima watched per sampled timepoint. */
	private static final int WATCHPOINTS_PER_TIMEPOINT = 12;

	/** Timepoints sampled per window — the same bound, and the same reason, as the completeness scan. */
	private static final int SAMPLED_TIMEPOINTS = 3;

	/** Voxel stride for the maxima scan. Matches the completeness scan's stride. */
	private static final int SCAN_STRIDE = 4;

	/**
	 * Most positions this audit will read per timepoint, whatever the volume's size.
	 *
	 * <p>The stride was fixed, so the cost of arming grew with the volume: at stride 4 a 312x312x284
	 * image is about 430 thousand reads and a 680x678x920 one is 6.6 million, each a random access into
	 * a chunked store. Three timepoints of the latter is twenty million, which is not a diagnostic aid
	 * any more — a run was left sitting at exactly this point with nothing after it in the log. The
	 * budget is set at what the smaller volume already cost, and the stride is derived from it.</p>
	 */
	private static final long MAX_SCAN_SAMPLES_PER_TIMEPOINT = 500_000L;

	/** Longest {@link #arm} may spend before giving up and watching whatever it has. */
	private static final long ARM_BUDGET_MS = 120_000L;

	/** Longest to wait for the viewer thread to hand back the image state for one timepoint. */
	private static final long CAPTURE_TIMEOUT_MS = 30_000L;

	/** Coarsest stride worth using: past this the maxima found stop being the image's brightest. */
	private static final int MAX_SCAN_STRIDE = 16;

	/**
	 * Stride that keeps a scan of {@code voxels} within {@link #MAX_SCAN_SAMPLES_PER_TIMEPOINT}, never
	 * finer than {@link #SCAN_STRIDE} and never coarser than {@link #MAX_SCAN_STRIDE}.
	 */
	static int strideFor( final long voxels )
	{
		return strideFor( voxels, SCAN_STRIDE );
	}

	/**
	 * As {@link #strideFor(long)}, starting from {@code baseStride} instead of this class's own. Shared
	 * with the intensity-seed scan, which runs the same walk over the same image for a different
	 * purpose and had the same problem: at its stride of 2 a 680x678x920 volume is 53 million samples,
	 * and picking the tuning region took nearly four minutes before a single predict had run.
	 */
	public static int strideFor( final long voxels, final int baseStride )
	{
		return strideFor( voxels, baseStride, MAX_SCAN_SAMPLES_PER_TIMEPOINT );
	}

	/**
	 * As {@link #strideFor(long, int)}, against a caller's own sample budget.
	 *
	 * <p>Exists because the two callers want very different numbers of samples. This class is looking
	 * for the image's brightest locations and wants a dense walk. The intensity-seed scan only needs a
	 * few dozen bright clusters to centre a tuning crop on, and its walk was measured at 94 seconds per
	 * window on a large volume — 34 windows of an hour and a half spent choosing where to look.</p>
	 */
	public static int strideFor( final long voxels, final int baseStride, final long maxSamples )
	{
		final int base = Math.max( 1, baseStride );
		if ( voxels <= 0 )
			return base;
		final long budget = Math.max( 1L, maxSamples );
		int stride = base;
		while ( stride < MAX_SCAN_STRIDE
				&& voxels / ( ( long ) stride * stride * stride ) > budget )
			stride++;
		return stride;
	}

	/**
	 * Only voxels above this intensity percentile are considered. Deliberately stricter than the
	 * completeness scan's percentile: this is not trying to estimate a cell count, it is trying to pick
	 * a handful of locations nobody would dispute are bright.
	 */
	private static final double SCAN_PERCENTILE = 0.995;

	/** Fallback cell radius (µm) when the graph holds no spot to measure one from. */
	private static final double FALLBACK_RADIUS = 5.0;

	/** A watchpoint counts as covered while a spot's centre is within this multiple of a cell radius. */
	private static final double MATCH_RADIUS_FACTOR = 1.0;

	/** Verdict lines listed individually before the rest are summarised as a count. */
	private static final int MAX_LISTED = 5;

	private BrightSpotAudit()
	{}

	/** One watched bright image location and what the passes did to it. */
	public static final class Watchpoint
	{
		private final int timepoint;

		private final double[] pos;

		/**
		 * Distance within which a spot counts as covering this location, frozen when the watchpoint was
		 * armed.
		 *
		 * <p>Frozen, not recomputed per observation, because it was derived from the mean spot radius
		 * and every pass changes that mean. Recomputing produced verdicts that were not merely wrong but
		 * impossible — one run reported a location "detected at [flat-contrast deletion], REMOVED by
		 * [predict]", i.e. removed by a pass that had already run. Nothing had moved: duplicate removal
		 * had raised the mean radius, the location fell inside the wider radius, and a later timepoint's
		 * smaller detections shrank it back out again. A yardstick that changes length cannot measure
		 * what the passes did to a fixed point.</p>
		 */
		private double matchRadius;

		private boolean present;

		private boolean everSeen;

		private String firstSeenStage;

		private String lostStage;

		private int lostCount;

		public Watchpoint( final int timepoint, final double[] pos )
		{
			this.timepoint = timepoint;
			this.pos = pos;
		}

		/** @return the frozen match radius — see {@link #matchRadius} */
		public double matchRadius()
		{
			return matchRadius;
		}

		public int timepoint()
		{
			return timepoint;
		}

		public double[] position()
		{
			return pos;
		}

		/** True once a pass has been observed with a spot covering this location. */
		public boolean everSeen()
		{
			return everSeen;
		}

		/** True if a spot covers this location as of the last {@link #observe} call. */
		public boolean covered()
		{
			return present;
		}
	}

	/**
	 * Records what {@code stage} left at this watchpoint. Pure state transition, separated from the
	 * graph so it can be exercised directly.
	 *
	 * @param occupied whether a spot covers the location now
	 * @param stage    the pass that just ran
	 */
	public static void note( final Watchpoint w, final boolean occupied, final String stage )
	{
		if ( occupied && !w.everSeen )
		{
			w.everSeen = true;
			w.firstSeenStage = stage;
		}
		if ( !occupied && w.present )
		{
			w.lostStage = stage;
			w.lostCount++;
		}
		w.present = occupied;
	}

	/**
	 * One line saying what happened to this location, or {@code null} when a spot is still there and
	 * nothing needs reporting.
	 */
	public static String verdict( final Watchpoint w )
	{
		if ( w.present )
			return null;
		if ( !w.everSeen )
			return String.format( "t=%d (%.0f, %.0f, %.0f): NEVER DETECTED — no pass ever produced a "
					+ "spot here, so this is a detection miss, not a deletion.",
					w.timepoint, w.pos[ 0 ], w.pos[ 1 ], w.pos[ 2 ] );
		return String.format( "t=%d (%.0f, %.0f, %.0f): detected at [%s], REMOVED by [%s]%s.",
				w.timepoint, w.pos[ 0 ], w.pos[ 1 ], w.pos[ 2 ], w.firstSeenStage, w.lostStage,
				w.lostCount > 1 ? String.format( " (lost %d times in this window)", w.lostCount ) : "" );
	}

	/**
	 * Picks the brightest image maxima across {@code [wStart, wEnd]} and starts watching them. Replaces
	 * any previously armed set — one window's verdicts are meaningless against another's detections.
	 */
	public static void arm( final ToolContext ctx, final ViewerPanel viewer, final int wStart,
			final int wEnd )
	{
		final List< Watchpoint > watch = new ArrayList<>();
		final double radius = meanSpotRadius( ctx, wStart, wEnd );
		// Bounded in wall-clock, not only in samples. How long a scan takes is set by how fast the image
		// store answers a random access, which no count of positions can predict: a run sat at this
		// point for over ten hours without producing the line below. This is a diagnostic and must never
		// be the reason a window does not finish.
		final long deadline = System.currentTimeMillis() + ARM_BUDGET_MS;
		boolean ranOut = false;
		for ( final int t : sampledTimepoints( wStart, wEnd ) )
		{
			if ( System.currentTimeMillis() > deadline )
			{
				ranOut = true;
				break;
			}
			for ( final double[] pos : brightestMaxima( viewer, t, radius ) )
			{
				final Watchpoint w = new Watchpoint( t, pos );
				w.matchRadius = MATCH_RADIUS_FACTOR * radius;
				watch.add( w );
			}
		}
		ctx.setSharedData( SHARED_KEY, watch );
		if ( ranOut )
			ctx.log( String.format( "  Bright-spot audit: gave up after %d s — this image is slow enough "
					+ "to sample that the audit would cost more than the tracking. Watching the %d "
					+ "location(s) found so far; nothing else about the window changes.",
					ARM_BUDGET_MS / 1000, watch.size() ) );
		else if ( !watch.isEmpty() )
			ctx.log( String.format( "  Bright-spot audit: watching the %d brightest image location(s) "
					+ "across t=%d..%d — every pass from here on reports whether it removed a spot from "
					+ "one of them.", watch.size(), wStart, wEnd ) );
	}

	/** Records, for every armed watchpoint, whether a spot covers it after {@code stage}. A no-op when unarmed. */
	public static void observe( final ToolContext ctx, final String stage )
	{
		final List< Watchpoint > watch = armed( ctx );
		if ( watch.isEmpty() )
			return;
		final ModelGraph graph = ctx.getGraph();
		final boolean[] occupied = new boolean[ watch.size() ];
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				spot.localize( pos );
				for ( int i = 0; i < watch.size(); i++ )
				{
					if ( occupied[ i ] || watch.get( i ).timepoint != t )
						continue;
					final Watchpoint wp = watch.get( i );
					final double dx = pos[ 0 ] - wp.pos[ 0 ], dy = pos[ 1 ] - wp.pos[ 1 ],
							dz = pos[ 2 ] - wp.pos[ 2 ];
					if ( dx * dx + dy * dy + dz * dz <= wp.matchRadius * wp.matchRadius )
						occupied[ i ] = true;
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		for ( int i = 0; i < watch.size(); i++ )
			note( watch.get( i ), occupied[ i ], stage );
	}

	/** Logs the verdict for every watched location that no longer has a spot, then disarms. */
	public static void report( final ToolContext ctx )
	{
		final List< Watchpoint > watch = armed( ctx );
		ctx.setSharedData( SHARED_KEY, null );
		if ( watch.isEmpty() )
			return;
		final List< String > missed = new ArrayList<>();
		final List< String > removed = new ArrayList<>();
		for ( final Watchpoint w : watch )
		{
			final String v = verdict( w );
			if ( v == null )
				continue;
			( w.everSeen ? removed : missed ).add( v );
		}
		final int covered = watch.size() - missed.size() - removed.size();
		ctx.log( String.format(
				"  Bright-spot audit: of %d bright image location(s), %d still hold a spot, %d were "
						+ "DETECTED THEN REMOVED, %d were never detected at all.",
				watch.size(), covered, removed.size(), missed.size() ) );
		logAll( ctx, removed );
		logAll( ctx, missed );
	}

	private static void logAll( final ToolContext ctx, final List< String > lines )
	{
		for ( int i = 0; i < Math.min( MAX_LISTED, lines.size() ); i++ )
			ctx.log( "    " + lines.get( i ) );
		if ( lines.size() > MAX_LISTED )
			ctx.log( String.format( "    ... and %d more like the above.", lines.size() - MAX_LISTED ) );
	}

	@SuppressWarnings( "unchecked" )
	private static List< Watchpoint > armed( final ToolContext ctx )
	{
		final Object v = ctx.getSharedData( SHARED_KEY );
		return v instanceof List ? ( List< Watchpoint > ) v : Collections.emptyList();
	}

	/** Up to {@link #SAMPLED_TIMEPOINTS} evenly-spaced timepoints from the window, first and last included. */
	static List< Integer > sampledTimepoints( final int wStart, final int wEnd )
	{
		final List< Integer > out = new ArrayList<>();
		final int span = wEnd - wStart;
		if ( span <= 0 )
		{
			out.add( wStart );
			return out;
		}
		final int n = Math.min( SAMPLED_TIMEPOINTS, span + 1 );
		for ( int i = 0; i < n; i++ )
		{
			final int t = wStart + ( int ) Math.round( ( double ) i * span / ( n - 1 ) );
			if ( !out.contains( t ) )
				out.add( t );
		}
		return out;
	}

	private static List< double[] > brightestMaxima( final ViewerPanel viewer, final int timepoint,
			final double nmsRadius )
	{
		FindMaximaTool.ScanState state;
		try
		{
			if ( SwingUtilities.isEventDispatchThread() )
			{
				state = FindMaximaTool.captureViewerState( viewer, timepoint );
			}
			else
			{
				// Timed rather than invokeAndWait: that waits for the viewer thread forever, and this is
				// a diagnostic. If the viewer is busy, skipping the audit is the correct outcome.
				final java.util.concurrent.FutureTask< FindMaximaTool.ScanState > task =
						new java.util.concurrent.FutureTask<>(
								() -> FindMaximaTool.captureViewerState( viewer, timepoint ) );
				SwingUtilities.invokeLater( task );
				state = task.get( CAPTURE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS );
			}
		}
		catch ( final Exception e )
		{
			return Collections.emptyList();
		}
		if ( state == null || !state.isValid() )
			return Collections.emptyList();
		return FindMaximaTool.scanImageSpaceMaxima(
				state, WATCHPOINTS_PER_TIMEPOINT, strideFor( state.voxelCount() ), nmsRadius,
				SCAN_PERCENTILE );
	}

	private static double meanSpotRadius( final ToolContext ctx, final int from, final int to )
	{
		final ModelGraph graph = ctx.getGraph();
		double sum = 0.0;
		int n = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				final int t = spot.getTimepoint();
				if ( t < from || t > to )
					continue;
				sum += Math.sqrt( spot.getBoundingSphereRadiusSquared() );
				n++;
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return n > 0 ? sum / n : FALLBACK_RADIUS;
	}
}
