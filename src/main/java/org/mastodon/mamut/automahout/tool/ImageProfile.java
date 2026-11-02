package org.mastodon.mamut.automahout.tool;

import java.util.Arrays;

/**
 * What the image itself says about its own modality, measured rather than assumed.
 *
 * <p>The pre-flight dialog used to open with the same answers every time — flat-contrast deletion on,
 * dim-signal filter off, and so on — regardless of what was being tracked. Those switches are not
 * matters of taste: the flat-contrast test is described in its own log line as one that "cannot be
 * made to work" where a cell is no brighter than its surroundings, and the dim-total-signal filter is
 * only meaningful when brightness means signal. Which of those a dataset is, is a property of the
 * pixels, so it is read off the pixels.</p>
 *
 * <p>The discriminator is stated in units of the background's own noise, not in absolute intensity.
 * Fluo-N2DL-HeLA_01 is plainly fluorescent, yet its cells read about 33727 against a background of
 * 33044 — a 2% difference, and any absolute threshold tuned on one detector would be wrong on the
 * next. In units of the background spread the same cells stand far clear of it, which is what
 * "fluorescent" actually means here.</p>
 */
public final class ImageProfile
{
	/** Above this many background-sigma between the bright tail and the median, brightness is signal. */
	static final double FLUORESCENCE_MIN_SNR_SIGMA = 8.0;

	/**
	 * A frame is only read as fluorescent if SOME of it is bright and MOST of it is not. Below the
	 * lower bound there is nothing to detect; above the upper one the bright tail is the frame itself
	 * (a saturated or uniformly lit image), and the ratio that produced it means nothing.
	 */
	static final double MIN_BRIGHT_FRACTION = 0.002;

	static final double MAX_BRIGHT_FRACTION = 0.50;

	/** Fewer samples than this cannot support a percentile, so the profile declines to classify. */
	static final int MIN_SAMPLES = 200;

	/** Median intensity — the background, since background is most of any frame worth tracking. */
	public final double backgroundMedian;

	/** Median absolute deviation scaled to a standard deviation; robust to the bright tail itself. */
	public final double backgroundStd;

	/** The bright tail: the 99.5th percentile of sampled intensity. */
	public final double brightTail;

	/** How far the bright tail stands above the background, in units of the background's own spread. */
	public final double snrSigma;

	/** Fraction of samples more than 5 sigma above the background. */
	public final double brightFraction;

	/** Whether brightness means signal here, or is just what the field looks like. */
	public final boolean fluorescenceLike;

	/** True when too few samples were available to say anything; every flag then reads conservatively. */
	public final boolean undetermined;

	/** Intensities the profile was measured from. */
	public final int samples;

	/** Share of {@link #samples} equal to the median: near 1 for a frame that is mostly one grey level. */
	public final double atMedianFraction;

	/** The modality the user stated in the pre-flight, which overrides the reading; {@code null} when none was. */
	public final Modality stated;

	/**
	 * Shared-data key, {@link Modality}: the user's answer to the pre-flight's image-type question. When set, every
	 * step that asks {@link #forRun} whether brightness means signal gets that answer, not the pixels' reading.
	 */
	public static final String SHARED_STATED_MODALITY = "run.stated_modality";

	private ImageProfile( final double median, final double std, final double tail, final double snr,
			final double brightFraction, final boolean fluorescenceLike, final boolean undetermined )
	{
		this( median, std, tail, snr, brightFraction, fluorescenceLike, undetermined, 0, Double.NaN );
	}

	private ImageProfile( final double median, final double std, final double tail, final double snr,
			final double brightFraction, final boolean fluorescenceLike, final boolean undetermined,
			final int samples, final double atMedianFraction )
	{
		this( median, std, tail, snr, brightFraction, fluorescenceLike, undetermined, samples, atMedianFraction, null );
	}

	private ImageProfile( final double median, final double std, final double tail, final double snr,
			final double brightFraction, final boolean fluorescenceLike, final boolean undetermined,
			final int samples, final double atMedianFraction, final Modality stated )
	{
		this.stated = stated;
		this.samples = samples;
		this.atMedianFraction = atMedianFraction;
		this.backgroundMedian = median;
		this.backgroundStd = std;
		this.brightTail = tail;
		this.snrSigma = snr;
		this.brightFraction = brightFraction;
		this.fluorescenceLike = fluorescenceLike;
		this.undetermined = undetermined;
	}

	/**
	 * {@link ToolContext#getSharedData} key for this run's measured profile, so the pre-flight dialog
	 * and the tracking passes read one measurement rather than two.
	 */
	public static final String SHARED_KEY = "run.image_profile";

	/** Intensity readings drawn per measurement — enough to support a 99.5th percentile with room. */
	public static final int SAMPLES = 3000;

	/**
	 * This run's profile, measured once and remembered.
	 *
	 * <p>{@code sampler} supplies the readings; it is passed in rather than called directly so this
	 * class stays free of the image stack and testable without one. A supplier that returns nothing
	 * yields an undetermined profile, which every caller reads as "behave as before".</p>
	 */
	/** This reading with the user's stated modality in place of its own verdict. */
	public ImageProfile withStatedModality( final Modality modality )
	{
		return new ImageProfile( backgroundMedian, backgroundStd, brightTail, snrSigma, brightFraction,
				modality.fluorescent(), false, samples, atMedianFraction, modality );
	}

	public static ImageProfile forRun( final ToolContext ctx,
			final java.util.function.Supplier< double[] > sampler )
	{
		if ( ctx == null )
			return of( null );
		final Object cached = ctx.getSharedData( SHARED_KEY );
		if ( cached instanceof ImageProfile )
			return ( ImageProfile ) cached;
		// The user said what these images are: nothing to read off the pixels, and no sampling to pay for.
		final Object stated = ctx.getSharedData( SHARED_STATED_MODALITY );
		if ( stated instanceof Modality )
		{
			final ImageProfile p = of( null ).withStatedModality( ( Modality ) stated );
			ctx.setSharedData( SHARED_KEY, p );
			return p;
		}
		ImageProfile measured;
		try
		{
			measured = of( sampler.get() );
		}
		catch ( final RuntimeException e )
		{
			measured = of( null );
		}
		ctx.setSharedData( SHARED_KEY, measured );
		return measured;
	}

	/**
	 * Reads the profile off a set of intensity samples drawn from one frame.
	 *
	 * @param samples intensities, in any order; not modified
	 */
	public static ImageProfile of( final double[] samples )
	{
		if ( samples == null || samples.length < MIN_SAMPLES )
			return new ImageProfile( Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
					false, true, samples == null ? 0 : samples.length, Double.NaN );
		final double[] sorted = samples.clone();
		Arrays.sort( sorted );
		final double median = percentile( sorted, 50 );
		// MAD rather than the plain standard deviation: the bright tail is exactly what is being
		// measured against, so it must not be allowed to widen the ruler it is measured with.
		final double[] dev = new double[ sorted.length ];
		for ( int i = 0; i < sorted.length; i++ )
			dev[ i ] = Math.abs( sorted[ i ] - median );
		Arrays.sort( dev );
		double std = 1.4826 * percentile( dev, 50 );
		if ( !( std > 0 ) )
			// Quantised data with a flat background: more than half the samples share one grey level,
			// so the MAD is exactly 0 and every intensity test that divides by it switches off. Measured
			// on Fluo-N3DH-CE, an 8-bit 3-D fluorescence movie with excellent signal, where the whole
			// profile came back "undetermined" and the checks that would have worked were skipped.
			//
			// The noise there is not absent, it is smaller than one grey level, which is the most that
			// can honestly be said about it. Half a quantisation step is that statement.
			std = 0.5 * quantisationStep( sorted, median );
		final double tail = percentile( sorted, 99.5 );
		if ( !( std > 0 ) )
		{
			// A frame with no measurable variation at all — flat, saturated, or empty. Nothing to divide
			// by, and nothing about it says "fluorescence".
			int same = 0;
			for ( final double v : sorted )
				if ( v == median )
					same++;
			return new ImageProfile( median, std, tail, Double.NaN, Double.NaN, false, true, sorted.length,
					( double ) same / sorted.length );
		}
		final double snr = ( tail - median ) / std;
		int bright = 0;
		for ( final double v : sorted )
			if ( v > median + 5.0 * std )
				bright++;
		final double brightFraction = ( double ) bright / sorted.length;
		final boolean fluorescent = snr >= FLUORESCENCE_MIN_SNR_SIGMA
				&& brightFraction >= MIN_BRIGHT_FRACTION
				&& brightFraction <= MAX_BRIGHT_FRACTION;
		return new ImageProfile( median, std, tail, snr, brightFraction, fluorescent, false );
	}

	/**
	 * Grey-level step the BACKGROUND is quantised to: the smallest difference between two distinct
	 * values at or below {@code median}. {@code 0} when the background is a single value.
	 *
	 * <p>Measured on the background alone, never on the whole sample. The gap from the background to
	 * the bright tail is the largest difference in the data, and on a sample with few distinct values
	 * it would become the "step" — turning the objects being measured into the ruler that measures
	 * them, which is the same error the MAD was chosen to avoid.</p>
	 */
	public static double quantisationStep( final double[] sorted, final double median )
	{
		double step = Double.MAX_VALUE;
		for ( int i = 1; i < sorted.length && sorted[ i ] <= median; i++ )
		{
			final double d = sorted[ i ] - sorted[ i - 1 ];
			if ( d > 0 )
				step = Math.min( step, d );
		}
		return step == Double.MAX_VALUE ? 0.0 : step;
	}

	private static double percentile( final double[] sorted, final double p )
	{
		if ( sorted.length == 0 )
			return Double.NaN;
		final int idx = ( int ) Math.round( ( p / 100.0 ) * ( sorted.length - 1 ) );
		return sorted[ Math.max( 0, Math.min( sorted.length - 1, idx ) ) ];
	}

	/**
	 * Whether the flat-contrast deletion can work on this data at all.
	 *
	 * <p>It asks whether a detection stands out from its surroundings. Where a cell is no brighter than
	 * what is next to it, that question has no answer and the pass deletes real cells: the run log says
	 * as much on every phase-contrast timepoint it refuses. Undetermined leaves it ON, which is the
	 * behaviour that predates this class.</p>
	 */
	public boolean flatContrastDeletionApplies()
	{
		return undetermined || fluorescenceLike;
	}

	/**
	 * Whether a detection being dim in TOTAL is evidence against it being a cell.
	 *
	 * <p>Only where brightness means signal. Elsewhere a dim cell is just a cell.</p>
	 */
	public boolean dimSignalFilterApplies()
	{
		return !undetermined && fluorescenceLike;
	}

	/**
	 * Whether a VLM should be asked to confirm what is in a crop, rather than the intensity being
	 * measured directly.
	 *
	 * <p>On fluorescence it should not: "is anything here" is answerable from the pixels, against this
	 * frame's own background, and that answer is checkable. Measured on the crops this pipeline
	 * actually sends, the VLM's answer was not — of 2444 positions that are genuinely empty it called
	 * 17% empty, and of the ones holding a cell it called 35% empty. Where brightness does NOT mean
	 * signal the measurement has nothing to stand on, and a judge that looks at the picture is worth
	 * its cost.</p>
	 */
	public boolean vlmCropCheckApplies()
	{
		return undetermined || !fluorescenceLike;
	}

	/** One line for the pre-flight log, saying what was measured and what it decided. */
	public String describe()
	{
		if ( stated != null )
			return "Image type: " + stated.label + " (stated in the pre-flight) — " + stated.description + ".";
		if ( undetermined )
			return "Image profile: could not be measured (" + ( samples < MIN_SAMPLES
					? String.format( "%d intensity sample(s) read, %d needed", samples, MIN_SAMPLES )
					: String.format( "%d samples, median %s, %.1f%% of them equal to it and none below it to "
							+ "measure a spread with", samples, backgroundMedian, atMedianFraction * 100 ) )
					+ ") — every pre-flight switch keeps its previous default.";
		return String.format(
				"Image profile: background %.0f +/- %.0f, bright tail %.0f (%.1f sigma above it, %.1f%% "
						+ "of the frame) — reads as %s.",
				backgroundMedian, backgroundStd, brightTail, snrSigma, brightFraction * 100,
				fluorescenceLike
						? "FLUORESCENCE: brightness means signal, so the intensity checks apply and a "
								+ "crop can be judged by measuring it"
						: "NOT fluorescence: a cell here is not reliably brighter than what surrounds it, "
								+ "so intensity-based deletion is off and crops need a look, not a threshold" );
	}
}
