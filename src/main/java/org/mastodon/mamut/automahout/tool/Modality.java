package org.mastodon.mamut.automahout.tool;

import java.util.Locale;

/**
 * The imaging modality, as the user states it in the pre-flight. It decides whether brightness means signal, which
 * is what every intensity-based step of the pipeline rests on (see {@link ImageProfile#forRun}).
 */
public enum Modality
{
	FLUORESCENCE( "Fluorescence", "bright objects on a dark background; brightness means signal" ),
	PHASE_CONTRAST( "Phase contrast", "cells as dark/bright halos on a grey background; brightness is not signal" ),
	BRIGHTFIELD( "Brightfield", "transmitted light; cells by texture and edges, brightness is not signal" ),
	OTHER( "Other", "anything else; brightness is not assumed to mean signal" );

	public final String label;

	public final String description;

	Modality( final String label, final String description )
	{
		this.label = label;
		this.description = description;
	}

	/** Whether brightness means signal: the one property of the modality the pipeline uses. */
	public boolean fluorescent()
	{
		return this == FLUORESCENCE;
	}

	/** The pre-selected answer and why: the movie's description names a modality, or the pixels say. */
	public static final class Guess
	{
		public final Modality modality;

		public final String reason;

		Guess( final Modality modality, final String reason )
		{
			this.modality = modality;
			this.reason = reason;
		}
	}

	/**
	 * What to pre-select. A modality named in the vision model's description of the movie comes first (it saw the
	 * images); then the pixels: a fluorescence-like profile, or a background of exactly one grey level under sparse
	 * bright objects, which is what a fluorescence stack with its background subtracted looks like.
	 */
	public static Guess guess( final ImageProfile profile, final String description )
	{
		final String d = description == null ? "" : description.toLowerCase( Locale.ROOT );
		if ( d.contains( "phase contrast" ) || d.contains( "phase-contrast" ) )
			return new Guess( PHASE_CONTRAST, "the movie's description says phase contrast" );
		if ( d.contains( "brightfield" ) || d.contains( "bright-field" ) || d.contains( "bright field" ) )
			return new Guess( BRIGHTFIELD, "the movie's description says brightfield" );
		if ( d.contains( "fluoresc" ) )
			return new Guess( FLUORESCENCE, "the movie's description says fluorescence" );
		if ( profile != null && !profile.undetermined )
			return profile.fluorescenceLike
					? new Guess( FLUORESCENCE, "the pixels read as fluorescence (bright objects well above the background)" )
					: new Guess( OTHER, "the pixels do not read as fluorescence" );
		if ( profile != null && profile.samples >= ImageProfile.MIN_SAMPLES && profile.atMedianFraction >= 0.9
				&& profile.backgroundMedian == 0 )
			return new Guess( FLUORESCENCE, String.format( "%.1f%% of the image is exactly 0 with sparse bright objects, "
					+ "as in a fluorescence stack with its background removed", profile.atMedianFraction * 100 ) );
		return new Guess( OTHER, "neither the description nor the pixels settled it" );
	}
}
