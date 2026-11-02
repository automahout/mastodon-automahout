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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.json.JSONObject;

/**
 * A learned rule for deciding whether a detected spot is a real cell (accept, {@code tp}) or background
 * (reject, {@code fp}), from human-provided {@code tp}/{@code fp} labels. The decisive feature is local
 * {@code contrast} (spot minus surrounding background), NOT absolute image {@code intensity}: a real
 * cell — even a dim one — is a local intensity peak (positive contrast), whereas background, halos and
 * out-of-focus Z-ghosts are flat (low contrast). Absolute intensity varies with photobleaching, depth
 * and cell-to-cell brightness, so rejecting on dimness wrongly discards dim real cells; it is recorded
 * for reporting only.
 *
 * <p>Classification is nearest-centroid on standardised contrast: a spot is accepted if its contrast is
 * closer to the {@code tp} centroid than to the {@code fp} centroid. {@link #separation} measures how
 * cleanly the two classes are divided by contrast (centroid distance in pooled-std units); a low value
 * means contrast does not separate them and the autonomous decision is unreliable — the pipeline should
 * then ask a human rather than auto-classify.</p>
 */
public final class DetectionAcceptanceProfile
{

	public static final int SCHEMA_VERSION = 1;

	public String sourcePath = "";

	public int nTp;

	public int nFp;

	public double tpIntensityMean = Double.NaN;

	public double tpIntensityStd = Double.NaN;

	public double fpIntensityMean = Double.NaN;

	public double fpIntensityStd = Double.NaN;

	public double tpContrastMean = Double.NaN;

	public double tpContrastStd = Double.NaN;

	public double fpContrastMean = Double.NaN;

	public double fpContrastStd = Double.NaN;

	/**
	 * 10th percentile of tp contrast — a robust "low end of real cells" anchor used by {@link #accept}
	 * in preference to nearest-centroid-on-the-mean (see that method's docs for why the mean is
	 * unreliable here). NaN for profiles saved before this field existed, or when no tp examples were
	 * recorded; {@link #accept} falls back to nearest-centroid in that case.
	 */
	public double tpContrastP10 = Double.NaN;

	/** 90th percentile of fp contrast — the matching "high end of background" anchor for {@link #accept}. */
	public double fpContrastP90 = Double.NaN;

	/** Standardised centroid distance between tp and fp; higher = cleaner separation = more reliable. */
	public double separation = Double.NaN;

	/**
	 * Minimum labelled examples required PER CLASS before the rule is trusted. Below this, that class's
	 * standard deviation cannot be meaningfully estimated (n=1 has zero variance by definition, which
	 * {@link #pooledStd} floors to 1.0), so the resulting {@link #separation} is a statistical artifact
	 * — often huge — not a real signal. Using such a profile to auto-label {@code fp} negatives can
	 * train the model to suppress real, lower-contrast cells that simply weren't in the tiny sample.
	 */
	private static final int MIN_SAMPLES_PER_CLASS = 5;

	/** True once both classes have ENOUGH samples that centroids/separation are a real, trustworthy signal. */
	public boolean usable()
	{
		return nTp >= MIN_SAMPLES_PER_CLASS && nFp >= MIN_SAMPLES_PER_CLASS
				&& !Double.isNaN( tpIntensityMean ) && !Double.isNaN( fpIntensityMean );
	}

	private double pooledStd( final double a, final double b )
	{
		final double sa = Double.isNaN( a ) ? 0 : a;
		final double sb = Double.isNaN( b ) ? 0 : b;
		final double s = Math.sqrt( 0.5 * ( sa * sa + sb * sb ) );
		return s > 1e-9 ? s : 1.0; // guard against zero spread
	}

	/**
	 * Accept (real cell / tp) based on LOCAL CONTRAST alone — the brightness-invariant "is this a local
	 * peak" signal. A dim cell with tp-like contrast is accepted; only flat, low-contrast detections
	 * (like the fp examples: background, halos, Z-ghosts) are rejected. The {@code intensity} argument is
	 * intentionally ignored for the decision so dimness is never a reason to reject a real cell.
	 *
	 * <p>Prefers a threshold anchored between {@link #tpContrastP10} (a robust LOW end of real cells) and
	 * {@link #fpContrastP90} (a robust HIGH end of background), when both are available and cleanly
	 * separated ({@code tpContrastP10 > fpContrastP90}) — the same technique
	 * {@code AutoCorrectOrchestrator#labelByContrastExample} already uses, and for the identical reason:
	 * nearest-centroid-on-the-MEAN is fooled by a wide-brightness-range tp population, whose mean gets
	 * pulled toward the bright examples, making a legitimately dim real cell (even one drawn from the same
	 * tp population used to learn this profile) read as statistically closer to background. Falls back to
	 * nearest-centroid when the percentiles are unavailable (older saved profiles) or still overlap (no
	 * clean separation to anchor a threshold on).</p>
	 */
	public boolean accept( final double intensity, final double contrast )
	{
		if ( !usable() )
			return true; // no basis to reject
		if ( !Double.isNaN( tpContrastP10 ) && !Double.isNaN( fpContrastP90 ) && tpContrastP10 > fpContrastP90 )
			return contrast >= 0.5 * ( tpContrastP10 + fpContrastP90 );
		final double sc = pooledStd( tpContrastStd, fpContrastStd );
		final double dTp = Math.abs( ( contrast - tpContrastMean ) / sc );
		final double dFp = Math.abs( ( contrast - fpContrastMean ) / sc );
		return dTp <= dFp;
	}

	/**
	 * Recomputes {@link #separation} as the standardised distance between the tp and fp CONTRAST
	 * centroids — the feature the decision actually uses. If contrast alone does not separate the
	 * classes the rule is unreliable, which is exactly what a low value signals.
	 */
	public void computeSeparation()
	{
		if ( !usable() )
		{
			separation = Double.NaN;
			return;
		}
		final double sc = pooledStd( tpContrastStd, fpContrastStd );
		separation = Math.abs( ( tpContrastMean - fpContrastMean ) / sc );
	}

	// -----------------------------------------------------------------------

	public JSONObject toJson()
	{
		final JSONObject o = new JSONObject();
		o.put( "schemaVersion", SCHEMA_VERSION );
		o.put( "sourcePath", sourcePath );
		o.put( "nTp", nTp );
		o.put( "nFp", nFp );
		putNum( o, "tpIntensityMean", tpIntensityMean );
		putNum( o, "tpIntensityStd", tpIntensityStd );
		putNum( o, "fpIntensityMean", fpIntensityMean );
		putNum( o, "fpIntensityStd", fpIntensityStd );
		putNum( o, "tpContrastMean", tpContrastMean );
		putNum( o, "tpContrastStd", tpContrastStd );
		putNum( o, "fpContrastMean", fpContrastMean );
		putNum( o, "fpContrastStd", fpContrastStd );
		putNum( o, "tpContrastP10", tpContrastP10 );
		putNum( o, "fpContrastP90", fpContrastP90 );
		putNum( o, "separation", separation );
		return o;
	}

	public static DetectionAcceptanceProfile fromJson( final JSONObject o )
	{
		final DetectionAcceptanceProfile p = new DetectionAcceptanceProfile();
		p.sourcePath = o.optString( "sourcePath", "" );
		p.nTp = o.optInt( "nTp" );
		p.nFp = o.optInt( "nFp" );
		p.tpIntensityMean = o.optDouble( "tpIntensityMean", Double.NaN );
		p.tpIntensityStd = o.optDouble( "tpIntensityStd", Double.NaN );
		p.fpIntensityMean = o.optDouble( "fpIntensityMean", Double.NaN );
		p.fpIntensityStd = o.optDouble( "fpIntensityStd", Double.NaN );
		p.tpContrastMean = o.optDouble( "tpContrastMean", Double.NaN );
		p.tpContrastStd = o.optDouble( "tpContrastStd", Double.NaN );
		p.fpContrastMean = o.optDouble( "fpContrastMean", Double.NaN );
		p.fpContrastStd = o.optDouble( "fpContrastStd", Double.NaN );
		// Absent in profiles saved before these fields existed — NaN correctly falls back to
		// nearest-centroid in accept(), so no migration step is needed.
		p.tpContrastP10 = o.optDouble( "tpContrastP10", Double.NaN );
		p.fpContrastP90 = o.optDouble( "fpContrastP90", Double.NaN );
		p.separation = o.optDouble( "separation", Double.NaN );
		return p;
	}

	private static void putNum( final JSONObject o, final String key, final double v )
	{
		o.put( key, Double.isFinite( v ) ? ( Object ) Double.valueOf( v ) : JSONObject.NULL );
	}

	public void save( final String path ) throws IOException
	{
		Files.write( Paths.get( path ), toJson().toString( 2 ).getBytes( StandardCharsets.UTF_8 ) );
	}

	public static DetectionAcceptanceProfile load( final String path ) throws IOException
	{
		final String json = new String( Files.readAllBytes( Paths.get( path ) ), StandardCharsets.UTF_8 );
		return fromJson( new JSONObject( json ) );
	}

	public String report()
	{
		return String.format(
				"Detection acceptance profile (%d tp, %d fp, separation=%.2f):%n"
						+ "  intensity: tp=%.1f±%.1f  fp=%.1f±%.1f%n"
						+ "  contrast:  tp=%.1f±%.1f  fp=%.1f±%.1f%n"
						+ "  %s",
				nTp, nFp, separation,
				tpIntensityMean, tpIntensityStd, fpIntensityMean, fpIntensityStd,
				tpContrastMean, tpContrastStd, fpContrastMean, fpContrastStd,
				confidenceNote() );
	}

	/**
	 * Markdown rule for the LLM system prompt: what distinguishes a real cell (accept) from background
	 * (reject), plus a confidence note and an ask_user escalation hint when separation is low. Empty if
	 * the profile is not usable.
	 */
	public String promptSection()
	{
		if ( !usable() )
			return "";
		final StringBuilder sb = new StringBuilder();
		sb.append( "## Learned Detection-Acceptance Rule\n" );
		sb.append( String.format( "From %d human-labelled real cells (tp) and %d background detections (fp): "
				+ "the reliable signal is LOCAL CONTRAST (a real cell is a local intensity peak), NOT absolute "
				+ "brightness. Local contrast tp≈%.0f vs fp≈%.0f (for reference, image intensity tp≈%.0f vs "
				+ "fp≈%.0f). Reject only FLAT, low-contrast detections (like the fp examples: background, halos, "
				+ "out-of-focus Z-ghosts). Do NOT reject a detection just because it is DIM — dim cells with a "
				+ "clear local peak are real and must be kept.\n",
				nTp, nFp, tpContrastMean, fpContrastMean, tpIntensityMean, fpIntensityMean ) );
		sb.append( "Rule confidence: " ).append( confidenceNote() ).append( "\n" );
		if ( separation < 1.0 )
			sb.append( "Because the separation is LOW, automatic accept/reject is unreliable here — prefer "
					+ "to use ask_user to have a human label this window rather than trusting auto-detection.\n" );
		sb.append( "\n" );
		return sb.toString();
	}

	/** Human-readable confidence guidance based on class separation. */
	public String confidenceNote()
	{
		if ( nTp == 0 || nFp == 0 )
			return "NOT USABLE (need both tp and fp labels).";
		if ( !usable() )
			return String.format(
					"NOT USABLE YET (only %d tp / %d fp label(s); need >= %d of each — with this few "
							+ "examples the class std is unmeasurable and any computed separation is a "
							+ "statistical artifact, not a reliable signal). Label more examples before "
							+ "trusting this rule to auto-reject detections.",
					nTp, nFp, MIN_SAMPLES_PER_CLASS );
		if ( separation >= 2.0 )
			return "separation HIGH — auto-accept/reject is reliable.";
		if ( separation >= 1.0 )
			return "separation MEDIUM — auto-classify, but verify ambiguous spots.";
		return "separation LOW — features overlap; ask a human to label rather than auto-classify.";
	}
}
