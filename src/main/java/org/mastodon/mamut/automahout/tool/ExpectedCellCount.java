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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The floor every count-reducing cleanup pass must respect: how few spots a timepoint may be left
 * with.
 *
 * <p>It exists because the passes that delete detections are statistical tests against estimates far
 * weaker than what the user already told the pipeline. The flat-contrast test, for instance, builds a
 * background baseline from at most a few dozen samples drawn from whatever space is left between
 * DETECTED spots — undetected real cells included — and was observed flagging 60-70% of a timepoint's
 * detections and taking a frame to 194 spots on a dataset stated to hold ~250 cells. The stated count
 * is the more reliable number, so it bounds the tests rather than the other way round.</p>
 *
 * <p>Lives in its own class, not in whichever pass happened to need it first, so that every pass
 * that should honour it measures against ONE definition.</p>
 *
 * <p>It applies to passes that delete a spot because they judge it NOT TO BE A CELL, and to those
 * only. Deduplication is the counterexample: a duplicate is a second spot on a cell that is still
 * there, so removing it lowers the spot count without lowering the cell count. Bounding duplicate
 * removal by this floor was tried and was a mistake — one window kept 2069 overlapping duplicates,
 * and since the surviving spots become the next refinement round's training positives, each was then
 * taught to the model as a separate cell.</p>
 *
 * <p><b>Order matters, and it is the caller's job.</b> This floor is a CELL count, so whatever is
 * counted against it must be cells too — which means duplicate resolution has to have run already.
 * It used to run afterwards, and the arithmetic quietly came apart: with ~28% of a frame being a
 * second spot on an already-detected cell, the flat-contrast pass stopped at exactly 250 spots and
 * the duplicate pass then took the same frame to ~180 cells, so the guard protecting the stated count
 * delivered under three quarters of it, every frame. {@code ElephantTool.predictWindow} now resolves
 * duplicates per timepoint BEFORE the flat-contrast check for that reason; a new count-reducing pass
 * that consults this floor must sit on the same side of that line.</p>
 */
public final class ExpectedCellCount
{
	private ExpectedCellCount()
	{}

	/**
	 * {@link ToolContext#getSharedData} key for the pre-flight dialog's "expected cells per frame" as
	 * the user typed it — the count expected at the FIRST timepoint of the run.
	 *
	 * <p>Deliberately NOT the doubled value stored as the cold-start ceiling: that is an upper BOUND,
	 * set three different ways, and only one of them is this count doubled. A bound flags explosions; an
	 * expectation protects against over-deletion. Conflating them has already cost a round here.</p>
	 */
	public static final String SHARED_EXPECTED_CELLS_PER_FRAME = "adaptive_track.expected_cells_per_frame";

	/**
	 * {@link ToolContext#getSharedData} key for the cells per frame expected at the LAST timepoint of
	 * the run, when the user gave one — {@link Number}, absent meaning "same as the first".
	 *
	 * <p>One number cannot describe a developing specimen. A mouse embryo run from t=160 to t=499 holds
	 * about sixteen cells at the start and close to a thousand at the end, and a user told to state
	 * "expected cells per frame" reasonably enters the thousand. The floor is then sixty times the
	 * truth at the start and refuses every deletion, which is exactly what happened. Entering sixteen
	 * would have been no better: the growth this class is willing to infer on its own is bounded, so
	 * the floor could never have followed the population up to a thousand.</p>
	 *
	 * <p>With both ends given the curve is known rather than inferred, and {@link #expectedAt} reads off
	 * it. When they are equal, or the second is absent, everything behaves as it did with one number.</p>
	 */
	public static final String SHARED_EXPECTED_CELLS_AT_END = "adaptive_track.expected_cells_at_end";

	/** {@link ToolContext#getSharedData} key for the run's {@code [startTp, endTp]} — {@code int[]}. */
	public static final String SHARED_EXPECTED_RANGE = "adaptive_track.expected_range";

	/**
	 * {@link ToolContext#getSharedData} key set while a CROP-RESTRICTED detection's cleanup is running —
	 * {@link Boolean}.
	 *
	 * <p>The stated count describes a whole frame. During crop tuning a timepoint holds only one small
	 * region's detections, so comparing the two is a category error, and the log said so out loud
	 * without anyone noticing: "flagged 1 of 5 spot(s) but deleting only 0 — removing them all would
	 * leave 4, below the 1000 cell(s)/frame you stated". Five spots in a crop against a thousand in a
	 * frame. Whatever number is entered, the floor blocks every deletion during tuning and then those
	 * detections are what the round trains on.</p>
	 *
	 * <p>This class already has company in getting that wrong: the tuning loop's own comment notes that
	 * {@code coldStartCeiling}, {@code minSpotsPerTp} and {@code prevWindowBaseline} are all
	 * full-frame numbers that it therefore must not act on. The floor is one of those and was not
	 * exempt.</p>
	 */
	public static final String SHARED_CROP_SCOPED = "adaptive_track.crop_scoped";

	/** Whether a crop-restricted pass is running, so full-frame counts must not be compared against. */
	public static boolean cropScoped( final ToolContext ctx )
	{
		return Boolean.TRUE.equals( ctx.getSharedData( SHARED_CROP_SCOPED ) );
	}

	/**
	 * {@link ToolContext#getSharedData} key for the world-space bounds of the crop in force, as
	 * {@code [xMin, yMin, zMin, xMax, yMax, zMax]} in the dataset's physical units — published by
	 * {@code ElephantIntegration.predictSpotsInRegion} for whoever needs to know WHERE the pass is
	 * looking, not merely that it is crop-restricted. Read it through {@link #cropWorldBounds}.
	 */
	public static final String SHARED_CROP_WORLD_BOUNDS = "adaptive_track.crop_world_bounds";

	/**
	 * The crop the pass in flight is restricted to, or {@code null} when it is looking at whole frames.
	 *
	 * <p>Gated on {@link #cropScoped} as well as on the bounds themselves, so a crop's bounds left
	 * behind by an earlier pass can never be mistaken for the current one's: the caller that sets the
	 * scope flag is the one that owns the crop.</p>
	 */
	public static double[] cropWorldBounds( final ToolContext ctx )
	{
		if ( ctx == null || !cropScoped( ctx ) )
			return null;
		final Object v = ctx.getSharedData( SHARED_CROP_WORLD_BOUNDS );
		return v instanceof double[] && ( ( double[] ) v ).length == 6 ? ( double[] ) v : null;
	}

	/**
	 * {@link ToolContext#getSharedData} key for the timepoints whose cleanup was cut short by the floor.
	 * They are excluded from the growth measurement below: a timepoint HELD at its floor measures the
	 * floor, not the population, and feeding that back in would let the floor raise itself.
	 */
	public static final String SHARED_FLOOR_BOUND_TIMEPOINTS = "adaptive_track.floor_bound_timepoints";

	/**
	 * {@link ToolContext#getSharedData} key for the timepoints a count-reducing pass declined to run on
	 * at all, so that what they hold is a RAW detection count nothing has vetted.
	 *
	 * <p>Distinct from {@link #SHARED_FLOOR_BOUND_TIMEPOINTS}, which marks a timepoint whose cleanup ran
	 * and was cut short: that one's count is the floor, which is a known quantity and a sound basis for
	 * the next timepoint's. A skipped timepoint's is neither. Observed: the flat-contrast check skips a
	 * timepoint whose raw count is already under the detection ceiling, t=2 was left holding 452 raw
	 * detections on a movie stated to hold ~250 cells, and t=3's floor was computed from it as 475 —
	 * above t=3's own raw count, so 282 of the 312 spots that check flagged there could not be
	 * removed. One skipped frame disabled the cleanup on the next.</p>
	 */
	public static final String SHARED_UNVETTED_TIMEPOINTS = "adaptive_track.unvetted_timepoints";

	/**
	 * How far back {@link #floorAt(ToolContext, int)} will look for a vetted basis before giving up and
	 * using the stated count. Each step back compounds the growth rate, so a distant basis is mostly
	 * extrapolation; past this many timepoints the stated count is the more honest answer.
	 */
	private static final int MAX_BASIS_LOOKBACK = 5;

	/**
	 * Free timepoints needed before the measured growth rate is trusted. Below this the rate is 1.0,
	 * i.e. "a timepoint may not fall below the one before it" and nothing more.
	 */
	private static final int MIN_FREE_TIMEPOINTS_FOR_GROWTH = 3;

	/**
	 * Sanity bound on the measured per-timepoint growth rate.
	 *
	 * <p>Not a biological claim — the frame interval is unknown here — but a guard on the consequence:
	 * the rate only ever RAISES a floor, and a floor above the raw detection count leaves nothing to
	 * delete, so an over-estimated rate silently disables the cleanup it is supposed to bound. 5% per
	 * timepoint already doubles the population in about fourteen frames.</p>
	 */
	private static final double MAX_GROWTH_RATE_PER_TIMEPOINT = 1.05;

	/**
	 * Most the floor may exceed the stated count by, however far into the movie a timepoint sits.
	 *
	 * <p>Without this the cap compounds without limit and stops being a cap: 5% per timepoint is 1.55x
	 * by t=9, which is the case it was written against, but 80x by t=90. On a 961-timepoint movie every
	 * window past the first few had a ceiling so far above the stated count that the chained value —
	 * the previous timepoint's own count — was always the smaller of the two, which is precisely the
	 * self-quoting ratchet the cap exists to break. Observed there: the debris filter asked to remove
	 * 171, 172 and 189 detections in three consecutive windows and removed none of them.</p>
	 *
	 * <p>Four times the stated count is already past any population the user's own number could
	 * describe; beyond that the number is wrong rather than the floor.</p>
	 */
	private static final double MAX_TOTAL_GROWTH_MULTIPLE = 4.0;

	/**
	 * How far BELOW the stated count a frame may sit before the floor calls it over-deletion.
	 *
	 * <p>The user's number is an estimate of a movie they eyeballed, not a measurement, and they have
	 * said so: it is expected to run somewhat high or somewhat low. Treated as exact it made the floor
	 * fire on ordinary error — Fluo-N2DL-HeLA_01 was stated at 100 cells/frame and a frame at 62 was
	 * reported as "deleting real cells", which is a statement about the estimate, not about the pass.</p>
	 *
	 * <p>This is the SAME number the ceiling takes on the other side (see {@link #statedCeiling}), so
	 * "about N" means one thing rather than two and the two cannot drift apart. The cost is real and is
	 * the point: a pass may now take a frame stated at 100 down to 50 without the floor objecting. The
	 * floor still catches a collapse, which is the failure it was written for; it no longer arbitrates
	 * an estimate.</p>
	 */
	public static final double STATED_COUNT_MARGIN = 2.0;

	/**
	 * Most spots a frame may hold before a detection flood is declared, given the user's stated count.
	 *
	 * <p>Taken against the LARGER end when both ends of the population are stated: a ceiling built on
	 * the first timepoint's cells would read a grown population as a flood.</p>
	 *
	 * @param peak the larger of the stated counts
	 */
	public static double statedCeiling( final int peak )
	{
		return peak * STATED_COUNT_MARGIN;
	}

	/**
	 * The stated count relaxed downwards by {@link #STATED_COUNT_MARGIN}, which is what
	 * every floor and floor-shaped check compares against. Never below 1: a floor of 0 is no floor.
	 *
	 * @param stated the exact count, as stated or interpolated
	 * @return the fewest spots that is still consistent with that estimate
	 */
	public static int toleratedFloor( final int stated )
	{
		return stated <= 0 ? stated
				: Math.max( 1, ( int ) Math.floor( stated / STATED_COUNT_MARGIN ) );
	}

	/**
	 * Most spots {@code timepoint} may hold before it reads as over-detection.
	 *
	 * <p><b>Use this rather than a run-wide bound wherever one timepoint or one window is being
	 * judged.</b> The run-wide ceiling is built on the population's LARGEST end: on a movie stated to
	 * grow 26 to 300 cells it permits 600 at t=0 as readily as at t=91, so at the start of the movie it
	 * is twenty-three times what the start of the movie should hold. Window 1 of Fluo-N2DL-HeLA_01
	 * detected 885 spots at t=0 against a stated 26 and four separate guards let it through, each
	 * comparing against that same run-wide number.</p>
	 *
	 * <p>A ceiling MEASURED on recent windows always wins where one exists: it was measured on this
	 * data, and an estimate must not tighten it.</p>
	 *
	 * @param ctx            run context
	 * @param timepoint      the timepoint being judged
	 * @param measuredPrior  ceiling calibrated on recent windows, or {@code <= 0} when none
	 * @param runWideFallback what to use when the user stated no count at all
	 */
	public static double ceilingAt( final ToolContext ctx, final int timepoint,
			final double measuredPrior, final double runWideFallback )
	{
		final double stated = ctx == null ? -1 : statedCeiling( expectedAt( ctx, timepoint ) );
		if ( stated <= 0 )
			// Nothing stated, or no context to ask: the caller's own bound is all there is, and a
			// measured prior still outranks it.
			return Math.max( runWideFallback, Math.max( 0.0, measuredPrior ) );
		return Math.max( stated, Math.max( 0.0, measuredPrior ) );
	}

	/**
	 * As {@link #ceilingAt}, for a phase judged over a whole window.
	 *
	 * <p>Taken at the window's LAST timepoint, which is its largest expectation: a window legitimately
	 * contains growth, and a ceiling set at its first timepoint would call the end of the window an
	 * explosion for having grown across it.</p>
	 */
	public static double ceilingOver( final ToolContext ctx, final int wStart, final int wEnd,
			final double measuredPrior, final double runWideFallback )
	{
		return ceilingAt( ctx, Math.max( wStart, wEnd ), measuredPrior, runWideFallback );
	}

	/** The user's stated cells/frame, or {@code -1} if they did not state one. */
	public static int statedCellsPerFrame( final ToolContext ctx )
	{
		final Object v = ctx.getSharedData( SHARED_EXPECTED_CELLS_PER_FRAME );
		return v instanceof Number ? ( ( Number ) v ).intValue() : -1;
	}

	/**
	 * Cells per frame the user expects at {@code timepoint}: their first-timepoint number, or a
	 * geometric interpolation toward their last-timepoint number when they gave one.
	 *
	 * <p>Geometric rather than linear because a dividing population is. Outside the stated range the
	 * nearer end is used rather than extrapolated — the user described a range, not a trend.</p>
	 */
	public static int expectedAt( final ToolContext ctx, final int timepoint )
	{
		final int start = statedCellsPerFrame( ctx );
		if ( start <= 0 )
			return start;
		final Object endObj = ctx.getSharedData( SHARED_EXPECTED_CELLS_AT_END );
		final Object rangeObj = ctx.getSharedData( SHARED_EXPECTED_RANGE );
		if ( !( endObj instanceof Number ) || !( rangeObj instanceof int[] ) )
			return start;
		final int end = ( ( Number ) endObj ).intValue();
		final int[] range = ( int[] ) rangeObj;
		if ( end <= 0 || range.length < 2 || range[ 1 ] <= range[ 0 ] )
			return start;
		if ( timepoint <= range[ 0 ] )
			return start;
		if ( timepoint >= range[ 1 ] )
			return end;
		final double f = ( double ) ( timepoint - range[ 0 ] ) / ( range[ 1 ] - range[ 0 ] );
		return Math.max( 1, ( int ) Math.round( start * Math.pow( ( double ) end / start, f ) ) );
	}

	/**
	 * Fewest spots {@code timepoint} may be left with, or {@code -1} when the user stated no count and
	 * the floor is therefore not in force.
	 */
	public static int floorAt( final ToolContext ctx, final int timepoint )
	{
		if ( cropScoped( ctx ) )
			return -1; // a crop's spot count is not comparable with a whole frame's — see SHARED_CROP_SCOPED
		// The stated count is an estimate, so every RETURN below relaxes it by
		// STATED_COUNT_MARGIN. It stays exact inside the chain: capToStatedGrowth bounds
		// the floor against the user's own number, and relaxing its input would tighten that bound
		// instead of loosening this one.
		final int stated = expectedAt( ctx, timepoint );
		if ( stated <= 0 )
			return stated;
		if ( timepoint <= 0 )
			return toleratedFloor( stated );
		// Walk back to the nearest timepoint a cleanup pass actually vetted, rather than taking whatever
		// timepoint - 1 happens to hold. See SHARED_UNVETTED_TIMEPOINTS: an unvetted neighbour's raw
		// count can put this timepoint's floor above its own detection count, which does not protect
		// cells, it just switches the cleanup off.
		final Map< Integer, Integer > counts = countsByTimepoint( ctx );
		final Set< Integer > unvetted = unvettedTimepoints( ctx );
		for ( int basisTp = timepoint - 1; basisTp >= 0 && timepoint - basisTp <= MAX_BASIS_LOOKBACK;
				basisTp-- )
		{
			if ( unvetted.contains( basisTp ) )
				continue;
			final Integer basisCount = counts.get( basisTp );
			if ( basisCount != null && basisCount > 0 )
				return toleratedFloor( capToStatedGrowth( stated, firstTimepointWithSpots( counts ),
						timepoint, floorAt( stated, basisCount, measuredGrowthRate( ctx ),
								timepoint - basisTp ) ) );
		}
		return toleratedFloor( stated );
	}

	/**
	 * How many spots a timepoint may TEACH the model as cells: the stated count carried forward by the
	 * MEASURED growth rate from the run's first timepoint.
	 *
	 * <p>Deliberately not {@link #floorAt}, which is what this used at first and which was wrong for the
	 * job. That one chains off the neighbour's actual spot count and is then bounded by the MAXIMUM
	 * growth rate — a generous runaway guard, correct for a floor that must not fight a dividing
	 * population. Used as a teaching cap it made the taught count climb 378, 410, 434, 466, 501 across
	 * ONE ten-timepoint window: that maximum rate compounded over nine timepoints is a 55% rise, and
	 * since the chained value was always far larger it was the only thing binding. The measured rate is 1.0
	 * unless the population is actually observed to grow, which is what a flat cap at the stated count
	 * needs to be.</p>
	 */
	public static int annotationCapAt( final ToolContext ctx, final int timepoint )
	{
		if ( cropScoped( ctx ) )
			return -1;
		final int stated = expectedAt( ctx, timepoint );
		if ( stated <= 0 )
			return -1;
		// With both ends of the range stated the curve is known, so the measured rate has nothing to
		// add — expectedAt has already placed this timepoint on it.
		if ( ctx.getSharedData( SHARED_EXPECTED_CELLS_AT_END ) instanceof Number )
			return stated;
		final Map< Integer, Integer > counts = countsByTimepoint( ctx );
		final int firstTp = firstTimepointWithSpots( counts );
		if ( timepoint <= firstTp )
			return stated;
		return Math.max( stated, ( int ) Math.floor(
				stated * Math.pow( measuredGrowthRate( ctx ), timepoint - firstTp ) ) );
	}

	/**
	 * Caps a chained floor at what the STATED count could have grown into by {@code timepoint}, so the
	 * floor can follow the population without ever becoming a claim the user did not make.
	 *
	 * <p>Chaining each timepoint's floor off its neighbour's count is a ratchet, because that neighbour's
	 * count is usually the floor: {@code floor(t) = floor(t-1) * rate} with {@code rate >= 1} can only
	 * climb, and nothing brings it back. Observed on a movie stated to hold 250 cells/frame — the floor
	 * reached 569, every timepoint sat at exactly 569 (a temporal CV of 0.00, which no real population
	 * gives), and the cleanup passes were refused on eight timepoints out of ten. At that point the spot
	 * count is not a measurement of the images at all; it is the floor quoting itself.</p>
	 *
	 * <p>The cap is generous — the maximum growth rate compounded from the run's first timepoint, not
	 * the measured one — because its job is only to stop a runaway, not to second-guess a population
	 * that really is growing.</p>
	 */
	static int capToStatedGrowth( final int statedCount, final int firstTp, final int timepoint,
			final int chainedFloor )
	{
		if ( statedCount <= 0 || timepoint <= firstTp )
			return chainedFloor;
		// The multiple is only needed where the population's growth has to be guessed. With both ends of
		// the range stated, statedCount is already this timepoint's own expected value and the cap is a
		// small tolerance around it rather than a compounding allowance.
		final double compounded = Math.min( MAX_TOTAL_GROWTH_MULTIPLE,
				Math.pow( MAX_GROWTH_RATE_PER_TIMEPOINT, timepoint - firstTp ) );
		final double ceiling = statedCount * compounded;
		return Math.max( statedCount, Math.min( chainedFloor, ( int ) Math.floor( ceiling ) ) );
	}

	/** Earliest timepoint holding any spot, or 0 when none do. */
	private static int firstTimepointWithSpots( final Map< Integer, Integer > counts )
	{
		int first = Integer.MAX_VALUE;
		for ( final Map.Entry< Integer, Integer > e : counts.entrySet() )
			if ( e.getValue() > 0 && e.getKey() < first )
				first = e.getKey();
		return first == Integer.MAX_VALUE ? 0 : first;
	}

	/**
	 * The floor arithmetic on its own.
	 *
	 * @param statedCount            the user's cells/frame, expected at the run's first timepoint
	 * @param previousTimepointCount spots currently at {@code timepoint - 1}, or 0 if it holds none —
	 *                               which is how the FIRST timepoint of a fresh run falls back to the
	 *                               stated count without needing to know where the run started
	 * @param growthRate             measured multiplier per timepoint, {@code >= 1.0}
	 */
	public static int floorAt( final int statedCount, final int previousTimepointCount,
			final double growthRate )
	{
		return floorAt( statedCount, previousTimepointCount, growthRate, 1 );
	}

	/**
	 * As {@link #floorAt(int, int, double)}, but where the basis count is {@code timepointsSinceBasis}
	 * timepoints back rather than the immediately preceding one — the growth rate compounds over the
	 * gap. Used when the intervening timepoints hold nothing the cleanup vetted.
	 */
	public static int floorAt( final int statedCount, final int basisCount, final double growthRate,
			final int timepointsSinceBasis )
	{
		if ( statedCount <= 0 )
			return -1;
		if ( basisCount <= 0 || timepointsSinceBasis <= 0 )
			return statedCount;
		return Math.max( statedCount,
				( int ) Math.floor( basisCount * Math.pow( growthRate, timepointsSinceBasis ) ) );
	}

	/**
	 * Growth per timepoint as MEASURED on this run, from the timepoints the floor did not hold.
	 *
	 * <p>Replaces a fixed increment, which had no way of being right: too small and a dividing
	 * population is dragged back toward its starting count, too large and the floor climbs past the raw
	 * detection count and disables the cleanup entirely — at a fixed +10 per timepoint on a movie
	 * detecting ~480 spots/tp, that took about 23 timepoints.</p>
	 *
	 * <p>Only free timepoints count. One held AT its floor measures the floor rather than the
	 * population, and including it would let the floor drive its own growth estimate.</p>
	 *
	 * @return the multiplier, in {@code [1.0, MAX_GROWTH_RATE_PER_TIMEPOINT]}; exactly {@code 1.0} when
	 *         there is not yet enough free history to measure anything, which means "no timepoint may
	 *         fall below the one before it" and nothing more
	 */
	public static double measuredGrowthRate( final ToolContext ctx )
	{
		final Map< Integer, Integer > counts = countsByTimepoint( ctx );
		final Set< Integer > bound = floorBoundTimepoints( ctx );
		final Set< Integer > unvetted = unvettedTimepoints( ctx );
		final List< Integer > free = new ArrayList<>();
		for ( final Map.Entry< Integer, Integer > e : counts.entrySet() )
			if ( e.getValue() > 0 && !bound.contains( e.getKey() ) && !unvetted.contains( e.getKey() ) )
				free.add( e.getKey() );
		if ( free.size() < MIN_FREE_TIMEPOINTS_FOR_GROWTH )
			return 1.0;
		Collections.sort( free );
		final int firstTp = free.get( 0 );
		final int lastTp = free.get( free.size() - 1 );
		return growthRate( counts.get( firstTp ), firstTp, counts.get( lastTp ), lastTp );
	}

	/**
	 * Per-timepoint multiplier taking {@code firstCount} at {@code firstTp} to {@code lastCount} at
	 * {@code lastTp}, clamped to never shrink and never exceed
	 * {@link #MAX_GROWTH_RATE_PER_TIMEPOINT}. Exponential rather than linear because a dividing
	 * population is.
	 */
	public static double growthRate( final int firstCount, final int firstTp, final int lastCount,
			final int lastTp )
	{
		final int span = lastTp - firstTp;
		if ( span <= 0 || firstCount <= 0 || lastCount <= 0 )
			return 1.0;
		final double rate = Math.pow( ( double ) lastCount / firstCount, 1.0 / span );
		return Math.max( 1.0, Math.min( MAX_GROWTH_RATE_PER_TIMEPOINT, rate ) );
	}

	/** Records that the floor cut a cleanup pass short at {@code timepoint}. */
	public static void noteFloorBound( final ToolContext ctx, final int timepoint )
	{
		floorBoundTimepoints( ctx ).add( timepoint );
		unvettedTimepoints( ctx ).remove( timepoint ); // it ran; the floor is what stopped it
	}

	/** Records that a count-reducing pass declined to run on {@code timepoint} at all. */
	public static void noteUnvetted( final ToolContext ctx, final int timepoint )
	{
		unvettedTimepoints( ctx ).add( timepoint );
	}

	/** Records that a count-reducing pass ran to completion on {@code timepoint}. */
	public static void noteVetted( final ToolContext ctx, final int timepoint )
	{
		unvettedTimepoints( ctx ).remove( timepoint );
	}

	@SuppressWarnings( "unchecked" )
	private static Set< Integer > unvettedTimepoints( final ToolContext ctx )
	{
		Object v = ctx.getSharedData( SHARED_UNVETTED_TIMEPOINTS );
		if ( !( v instanceof Set ) )
		{
			v = new HashSet< Integer >();
			ctx.setSharedData( SHARED_UNVETTED_TIMEPOINTS, v );
		}
		return ( Set< Integer > ) v;
	}

	@SuppressWarnings( "unchecked" )
	private static Set< Integer > floorBoundTimepoints( final ToolContext ctx )
	{
		Object v = ctx.getSharedData( SHARED_FLOOR_BOUND_TIMEPOINTS );
		if ( !( v instanceof Set ) )
		{
			v = new HashSet< Integer >();
			ctx.setSharedData( SHARED_FLOOR_BOUND_TIMEPOINTS, v );
		}
		return ( Set< Integer > ) v;
	}

	/** Spots per timepoint, in one pass over the graph. */
	private static Map< Integer, Integer > countsByTimepoint( final ToolContext ctx )
	{
		final org.mastodon.mamut.model.ModelGraph graph = ctx.getGraph();
		final Map< Integer, Integer > counts = new HashMap<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final org.mastodon.mamut.model.Spot s : graph.vertices() )
				counts.merge( s.getTimepoint(), 1, Integer::sum );
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return counts;
	}

	/**
	 * How many more spots a pass may remove from {@code timepoint}, given it currently holds
	 * {@code currentCount}. {@link Integer#MAX_VALUE} when no floor is in force.
	 */
	public static int deletionBudget( final ToolContext ctx, final int timepoint, final int currentCount )
	{
		final int floor = floorAt( ctx, timepoint );
		return floor <= 0 ? Integer.MAX_VALUE : Math.max( 0, currentCount - floor );
	}

	/**
	 * Removes as much of {@code toDelete} as the floor allows and stops there, leaving the rest in the
	 * graph. Safe to call with the graph's write lock held.
	 *
	 * <p>The one place the floor is applied, so that a pass honouring it is a one-line change rather
	 * than a re-derivation. Every confirmed instance of the recurring bug in this codebase was a guard
	 * applied to some deletion sites and not others, and the floor's first version was applied to one of
	 * thirty-two — the pass that took a window held at 250 cells/frame down to 193.</p>
	 *
	 * <p>Callers that can rank their candidates should pass the most confident first: what the budget
	 * cannot cover is dropped from the end. Callers that run several deleting passes over one timepoint
	 * need no extra bookkeeping — each call re-reads the count, so the budget is shared automatically.</p>
	 *
	 * @return how many were actually removed
	 */
	public static int removeRespectingFloor( final ToolContext ctx, final int timepoint,
			final java.util.List< org.mastodon.mamut.model.Spot > toDelete,
			final org.mastodon.mamut.model.ModelGraph graph )
	{
		final int budget = deletionBudget( ctx, timepoint, countCellsAt( ctx, timepoint ) );
		int removed = 0;
		for ( final org.mastodon.mamut.model.Spot s : toDelete )
		{
			if ( removed >= budget )
				break;
			// Callers collected these under an earlier lock: one deleted or replaced in the viewer since
			// is not the spot that was judged. See GraphWatch.
			if ( GraphWatch.dirty( graph, s ) )
			{
				GraphWatch.refused( graph );
				continue;
			}
			graph.remove( s );
			removed++;
		}
		return removed;
	}

	/**
	 * Cells currently at {@code timepoint} -- spots that are not {@code fp} training negatives.
	 *
	 * <p>Named for what it counts, because it used to count spots and every caller wanted cells. An
	 * {@code fp}-tagged spot is a label on a place that is NOT a cell; it lives in the graph so
	 * training can read it, is never linked ({@code unlinkFpSpots}), and must not be counted as
	 * population by the explosion guard, the expected-count ceiling, the deletion budget or the growth
	 * floor. Synthetic negatives already inflated all of them, and the error grows with every negative
	 * the pipeline learns to keep.</p>
	 *
	 * <p>If a caller genuinely wants every vertex at a timepoint, it has to say so and loop itself.
	 * That is why this was renamed rather than quietly corrected.</p>
	 */
	public static int countCellsAt( final ToolContext ctx, final int timepoint )
	{
		final org.mastodon.mamut.model.ModelGraph graph = ctx.getGraph();
		final java.util.function.Predicate< org.mastodon.mamut.model.Spot > isFp =
				org.mastodon.mamut.automahout.elephant.DetectionTagLabels.fpTest( ctx.getModel() );
		int n = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final org.mastodon.mamut.model.Spot s : graph.vertices() )
				if ( s.getTimepoint() == timepoint && !isFp.test( s ) )
					n++;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return n;
	}
}
