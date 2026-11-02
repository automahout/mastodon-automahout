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

import java.util.Arrays;

/**
 * Separates detections that are cells from detections that are background, in the two features that
 * distinguish them: how far the IMAGE stands above its own noise floor at the detection's position,
 * and how big the detection is.
 *
 * <p>Why this exists. {@code ElephantIntegration} chooses a training label in two places and both
 * default to {@code tp}: a spot is a positive unless something tagged it otherwise. So every
 * background detection the cleanup passes miss is taught to the model as a cell, and the next round
 * finds more of them. The passes that could catch it are independent one-dimensional thresholds,
 * each with its own gate and its own escape hatch.</p>
 *
 * <p>Measured on 92 frames of Fluo-N2DL-HeLa silver truth, with both populations measured by the
 * same procedure so the separation is not an artefact of the measurement (see
 * {@code benchmark/detection-gating}). The two populations barely touch: cells stand 4.05 sigma
 * above background at the 5th percentile, background peaks 1.75 sigma at the 95th. The features
 * were never the problem -- nothing was asking this question where the label is chosen.</p>
 *
 * <h2>Anchored, not clustered</h2>
 *
 * <p>The obvious estimator, two-cluster k-means on the unlabelled cloud, rejects 100% of background
 * at every contamination level from 5% to 75% -- and throws away 41-53% of the real cells. The cell
 * population's standout spans 4-49 sigma, so a variance-minimising split cuts THE CELLS in half
 * rather than separating them from the background. This is not two comparable clusters. It is one
 * tight cluster at the noise floor and everything else, so the gate anchors on the noise floor.
 * Anchored: 94.5% of cells kept, 100% of background rejected, flat from 5% to 90% contamination.</p>
 *
 * <h2>Why the brightness axis must come from the image</h2>
 *
 * <p>A detector that is wrong about a position is also wrong about the size it reports there. With
 * background radii inflated to cell size, the two axes together still reject 99.9%, while the
 * radius axis alone collapses to 57.7%. The size axis is the detector's own output and degrades
 * with it; the brightness axis is read from the image and does not. A gate built only from what the
 * detector said about itself can be talked into anything.</p>
 *
 * <p>Pure arithmetic, deliberately: the rule needs no pixels once the features are measured, so it
 * can be tested against real distributions without an image.</p>
 */
public final class DetectionGate
{
	/**
	 * Standout floor, in sigma above the frame's own background.
	 *
	 * <p>Three sigma is where a measurement stops being consistent with noise. Not delicate: 1.5
	 * sigma keeps 98.3% of cells, 3 sigma 94.5%, 6 sigma 91.7%, and background rejection is 100% at
	 * all of them. Set at the classical threshold rather than the one that scores best here, because
	 * what scores best here is a property of this dataset's noise and the threshold is not.</p>
	 */
	public static final double MIN_STANDOUT_SIGMA = 3.0;

	/**
	 * Standout below which a detection is not merely unsupported but measurably background.
	 *
	 * <p>The gate withholds what it rejects rather than tagging it {@code fp}, because a dim real
	 * cell taught as background is the worse error. That is right at the boundary and wrong deep
	 * below it, and it left the pipeline withholding measured background while the fp synthesis
	 * failed to manufacture negatives — 77% of its offset candidates rejected as holding signal —
	 * with verified background sitting unused in the graph.</p>
	 *
	 * <p>Measured over 8602 truth cells and 3680 background peaks: the faintest real cell in the set
	 * stands at 1.75 sigma, so at 1.5 sigma <b>no</b> real cell is caught (0.00%) while 89.4% of the
	 * background is. Restricted to what the gate already withholds, the catch is 3290 background
	 * detections and zero cells — 100% pure. At 2.0 sigma it starts to cost cells (5 of them), which
	 * is why it is not set there even though it would catch 97.4%.</p>
	 */
	public static final double DEEP_BACKGROUND_SIGMA = 1.5;

	/**
	 * Share of the population that must rise above {@link #MIN_STANDOUT_SIGMA} for the gate to be
	 * willing to decide at all.
	 *
	 * <p>Below this the gate is not separating cells from background, it is reporting that the
	 * detector produced nothing cell-like -- which is a question for a person, not a threshold. Run
	 * on background alone the gate admits 0 of 3680; run on cells alone, 94.1%.</p>
	 */
	public static final double MIN_SIGNAL_SHARE = 0.02;

	/** Fewest detections worth learning a gate from. */
	public static final int MIN_POPULATION = 10;

	private DetectionGate()
	{}

	/**
	 * As {@link #learn(double[], double[], double)} with the radius axis switched off, for a caller
	 * that cannot say what a voxel measures here.
	 */
	public static Gate learn( final double[] standouts, final double[] radii )
	{
		return learn( standouts, radii, 0.0 );
	}

	/** A learned gate, or a refusal to learn one. */
	public static final class Gate
	{
		/** Standout floor actually applied, in sigma. */
		public final double minStandout;

		/** Radius floor actually applied, in the spots' own units. */
		public final double minRadius;

		/**
		 * {@code false} when the population gives the gate nothing to separate. Callers must not
		 * withhold anything on an undecided gate -- they should say so and ask.
		 */
		public final boolean decided;

		/** Why, in the user's terms. */
		public final String reason;

		Gate( final double minStandout, final double minRadius, final boolean decided,
				final String reason )
		{
			this.minStandout = minStandout;
			this.minRadius = minRadius;
			this.decided = decided;
			this.reason = reason;
		}

		/** Whether a detection with these features is a cell this gate will teach. */
		public boolean admits( final double standout, final double radius )
		{
			return decided && standout >= minStandout && radius >= minRadius;
		}

		/**
		 * Whether a detection this gate rejected is background outright, rather than merely
		 * unsupported. Only meaningful for one the gate does not {@link #admits admit}.
		 */
		public boolean isBackground( final double standout )
		{
			return decided && standout < DEEP_BACKGROUND_SIGMA;
		}
	}

	/**
	 * Learns the gate from a population of detections.
	 *
	 * @param standouts        sigma above the frame background at each detection, read from the IMAGE
	 * @param radii            each detection's own radius
	 * @param subCellularRadius radius below which a detection has less extent than the sampling can
	 *                          resolve as a body, in the same units as {@code radii}; {@code <= 0}
	 *                          switches the radius axis off entirely
	 */
	public static Gate learn( final double[] standouts, final double[] radii,
			final double subCellularRadius )
	{
		if ( standouts == null || radii == null || standouts.length != radii.length )
			throw new IllegalArgumentException( "standouts and radii must be the same length" );
		if ( standouts.length < MIN_POPULATION )
			return new Gate( Double.NaN, Double.NaN, false,
					String.format( "only %d detection(s) — too few to tell a cell population from a "
							+ "background one", standouts.length ) );

		int bright = 0;
		for ( final double s : standouts )
		{
			if ( s >= MIN_STANDOUT_SIGMA )
				bright++;
		}
		final double share = ( double ) bright / standouts.length;
		if ( share < MIN_SIGNAL_SHARE )
			return new Gate( Double.NaN, Double.NaN, false, String.format(
					"only %d of %d detection(s) (%.1f%%) stand %.0f sigma above this frame\'s "
							+ "background — the detector is not producing cells here, which is not "
							+ "something a gate can fix",
					bright, standouts.length, 100.0 * share, MIN_STANDOUT_SIGMA ) );

		// The radius cut is read off the detections, because a cell's size is a property of the
		// specimen and cannot be assumed. Capped at the p05 of the population that already cleared
		// the standout floor, so a numerous background cannot drag the cut up into the cells: Otsu
		// splits whatever it is given, and given mostly background it splits background.
		// NOT a percentile of the detections. A percentile always cuts something: run on a frame whose
		// detections are every one of them real, min(otsu, p05) still withholds the smallest slice of
		// them, and it does so again on the next round, and the next. That is a ratchet against small
		// cells, and the smallest cells in a dividing culture are the ones either side of a division.
		//
		// Measured on the same 8602 cells and 3680 background peaks (benchmark/detection-gating,
		// measure_small_cells.py): the learned cut of 8.13 px withheld 396 cells, and ALL 396 were in
		// the smallest decile -- 396 of 866, so it removed nearly half that decile and touched nothing
		// else. It bought 3 background detections out of 3680, 99.9% rejection against 100.0%. Even in
		// the adversarial case the radius axis exists for, background radii inflated to cell size, it
		// bought nothing at all: 99.9% either way, because the standout axis had already rejected them.
		//
		// What the floor is actually for is a detection with no extent -- background peaks here measure
		// 0.5 px, and the three that clear the standout floor are all under one voxel. A floor of
		// subCellularRadius rejects the same 100% of background while keeping 97.5% of cells against
		// 94.5%, and 74.9% of the smallest decile against 45.0%. It is a statement about the sampling,
		// not about this specimen's cells, which is why it does not move with them.
		final double cut = subCellularRadius > 0.0 ? subCellularRadius : 0.0;
		return new Gate( MIN_STANDOUT_SIGMA, cut, true, String.format(
				"%d of %d detection(s) stand at least %.0f sigma above background; a detection smaller "
						+ "than radius %.2f has less extent than a voxel and is not a body",
				bright, standouts.length, MIN_STANDOUT_SIGMA, cut ) );
	}

	/**
	 * The threshold that best splits {@code xs} into two groups by between-group variance.
	 *
	 * <p>Otsu's method, on a 256-bin histogram. Unsupervised by construction: it is given no labels
	 * and no target count.</p>
	 */
	public static double otsu( final double[] xs )
	{
		if ( xs.length == 0 )
			return Double.NaN;
		double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
		for ( final double x : xs )
		{
			lo = Math.min( lo, x );
			hi = Math.max( hi, x );
		}
		if ( !( hi > lo ) )
			return lo;
		final int bins = 256;
		final double width = ( hi - lo ) / bins;
		final long[] hist = new long[ bins ];
		for ( final double x : xs )
			hist[ Math.min( bins - 1, ( int ) ( ( x - lo ) / width ) ) ]++;

		final long total = xs.length;
		double sumAll = 0.0;
		for ( int b = 0; b < bins; b++ )
			sumAll += hist[ b ] * ( lo + ( b + 0.5 ) * width );

		long w0 = 0;
		double sum0 = 0.0, best = -1.0, bestThr = lo;
		for ( int b = 0; b < bins - 1; b++ )
		{
			w0 += hist[ b ];
			sum0 += hist[ b ] * ( lo + ( b + 0.5 ) * width );
			final long w1 = total - w0;
			if ( w0 == 0 || w1 == 0 )
				continue;
			final double m0 = sum0 / w0;
			final double m1 = ( sumAll - sum0 ) / w1;
			final double var = ( double ) w0 * w1 * ( m0 - m1 ) * ( m0 - m1 );
			// Strictly greater, so a run of equally-good thresholds across an empty gap resolves to
			// the LOWEST one. For a radius floor that is the conservative end: it withholds least.
			if ( var > best )
			{
				best = var;
				bestThr = lo + ( b + 1 ) * width;
			}
		}
		return bestThr;
	}

	/** Linear-interpolation percentile, {@code q} in [0,1]. */
	static double percentile( final double[] xs, final double q )
	{
		final double[] s = Arrays.copyOf( xs, xs.length );
		Arrays.sort( s );
		if ( s.length == 1 )
			return s[ 0 ];
		final double pos = q * ( s.length - 1 );
		final int i = ( int ) Math.floor( pos );
		final double f = pos - i;
		return i + 1 < s.length ? s[ i ] * ( 1 - f ) + s[ i + 1 ] * f : s[ i ];
	}
}
