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
 * What a run knows about its data, in one place: the input every tool's {@link
 * AutomahoutTool#applicability} is judged on. Gathered from what the run already measures -- the image's
 * own dimensions, {@link ImageProfile}'s modality reading, the cell count the user stated -- rather than
 * measured again. A field that has not been measured yet is null or NaN, and a tool that needs it should
 * say "not known yet" rather than guess.
 *
 * <p>Built by {@link #of(ToolContext)}, which is cheap: it reads only what is already there.</p>
 */
public final class DataProfile
{
	/** True when the image has more than one z plane. */
	public final boolean volumetric;

	/** Number of timepoints, or -1 when there is no project. */
	public final int timepoints;

	/** Bright cells on a dark background; null when not measured or undetermined. */
	public final Boolean fluorescenceLike;

	/** Signal over background noise, in background spreads; NaN when not measured. */
	public final double snrSigma;

	/** The cell count per frame the user stated; NaN when none. */
	public final double expectedCellsPerFrame;

	/**
	 * The pretrained detector this run detects with (a remote tool id, see
	 * {@link org.mastodon.mamut.automahout.tool.remote.DetectorTrial}); {@code null} when ELEPHANT detects or
	 * nothing is chosen yet.
	 */
	public final String pretrainedDetector;

	DataProfile( final boolean volumetric, final int timepoints, final Boolean fluorescenceLike, final double snrSigma,
			final double expectedCellsPerFrame, final String pretrainedDetector )
	{
		this.volumetric = volumetric;
		this.timepoints = timepoints;
		this.fluorescenceLike = fluorescenceLike;
		this.snrSigma = snrSigma;
		this.expectedCellsPerFrame = expectedCellsPerFrame;
		this.pretrainedDetector = pretrainedDetector;
	}

	/** The profile of the context's data, from what has been measured so far. */
	public static DataProfile of( final ToolContext ctx )
	{
		final Object ip = ctx.getSharedData( ImageProfile.SHARED_KEY );
		final ImageProfile image = ip instanceof ImageProfile ? ( ImageProfile ) ip : null;
		int tps = -1;
		try
		{
			if ( ctx.getProjectModel() != null )
				tps = ctx.getProjectModel().getMaxTimepoint() - ctx.getProjectModel().getMinTimepoint() + 1;
		}
		catch ( final RuntimeException e )
		{
			// no project: replay
		}
		final double stated = ExpectedCellCount.statedCellsPerFrame( ctx );
		return new DataProfile( ctx.getProjectModel() == null ? false : ctx.isVolumetric(), tps,
				image == null || image.undetermined ? null : image.fluorescenceLike,
				image == null ? Double.NaN : image.snrSigma, stated > 0 ? stated : Double.NaN,
				org.mastodon.mamut.automahout.tool.remote.DetectorTrial.pretrainedChosen( ctx )
						? String.valueOf( ctx.getSharedData( org.mastodon.mamut.automahout.tool.remote.DetectorTrial.SHARED_CHOICE ) )
						: null );
	}

	/** A profile given field by field, for tests and for describing a dataset that is not open. */
	public static DataProfile of( final boolean volumetric, final int timepoints, final Boolean fluorescenceLike,
			final double snrSigma, final double expectedCellsPerFrame )
	{
		return new DataProfile( volumetric, timepoints, fluorescenceLike, snrSigma, expectedCellsPerFrame, null );
	}

	/** This profile, detected by the given pretrained detector ({@code null}: ELEPHANT). */
	public DataProfile detectedBy( final String pretrainedDetector )
	{
		return new DataProfile( volumetric, timepoints, fluorescenceLike, snrSigma, expectedCellsPerFrame,
				pretrainedDetector );
	}

	@Override
	public String toString()
	{
		return String.format( "%s, %s timepoints, %s, SNR %s, %s cells/frame stated%s", volumetric ? "3D" : "2D",
				timepoints < 0 ? "?" : String.valueOf( timepoints ),
				fluorescenceLike == null ? "modality not determined"
						: fluorescenceLike ? "fluorescence-like" : "not fluorescence-like",
				Double.isNaN( snrSigma ) ? "?" : String.format( "%.1f", snrSigma ),
				Double.isNaN( expectedCellsPerFrame ) ? "no count" : String.format( "%.0f", expectedCellsPerFrame ),
				pretrainedDetector == null ? "" : ", detected by " + pretrainedDetector );
	}
}
