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
import java.util.function.Consumer;

import org.json.JSONObject;

/**
 * A set of cell-lineage-graph constraints learned from a ground-truth {@code .geff} store by
 * {@link LineageConstraintLearner}. It captures both <em>structural invariants</em> (what shapes a
 * valid lineage graph can take — a cell has at most one parent, divides into at most two daughters,
 * does not appear or vanish mid-movie) and <em>statistical bounds</em> learned from the GT
 * distribution (plausible per-frame displacement, minimum division interval, spot-radius range,
 * daughter split geometry, track length).
 *
 * <p>The profile is serialised to JSON so it can be learned once from GT and then reused on new
 * data that has no ground truth — fed into {@code auto.correct} as numeric thresholds and into the
 * LLM prompt as a "grammar" of valid lineages, so the agent can recognise an impossible graph shape
 * and know how to repair it.</p>
 *
 * <p>All distances/radii are stored in world units (the {@code scale} applied at learning time has
 * already been folded in). Percentile fields are named {@code *P<nn>} where {@code nn} is the
 * percentile (e.g. {@code displacementP99}).</p>
 */
public final class LineageConstraintProfile
{

	/** Schema version of this profile format (bump when fields change incompatibly). */
	public static final int SCHEMA_VERSION = 1;

	// ---- provenance ----
	public String sourcePath = "";

	/**
	 * The ELEPHANT dataset this profile was learned from, or empty for a profile written before the
	 * field existed.
	 *
	 * <p>The path that activates a profile lives in one preferences node per USER, not per project, so
	 * opening a different movie inherits the previous one's profile, and a path carries no evidence
	 * about which lineage it describes. Observed on Fluo-N2DL-HeLA_01: a profile learned from
	 * {@code mouse_embryo_240220_sub50} stayed active, its displacement P99 of 6.6 um replaced the
	 * measurement this dataset would have made, and its low division rate made
	 * {@code dissolveExcessDivisions} cut real mitoses out of a HeLa culture. The GT annotation store
	 * already records its dataset for exactly this reason; this is the same guard for the profile.</p>
	 */
	public String dataset = "";

	public String geffVersion = "unknown";

	public double scale = 1.0;

	/**
	 * True when the source lineage is sparsely annotated (only some cells labelled). Under sparse GT
	 * the density- and rate-dependent statistics (cells per timepoint, division rate, track-segment
	 * length, founders, mid-movie births/deaths) are NOT representative of the real data and must not
	 * be used as bounds; only the relational rules (structural invariants, per-frame displacement,
	 * division geometry) transfer.
	 */
	public boolean sparseAnnotation = false;

	// ---- graph summary ----
	public int numNodes;

	public int numEdges;

	/** Lineage founders = nodes with in-degree 0. */
	public int numFounders;

	public int numDivisions;

	/** Fraction of cells that are division points ({@code numDivisions / numNodes}); how often cells divide. */
	public double divisionRate;

	/** Mean divisions per lineage founder ({@code numDivisions / numFounders}). */
	public double divisionsPerFounder;

	/** Median number of cells present per timepoint — the expected detection density. */
	public double spotsPerTimepointP50 = Double.NaN;

	/** Maximum number of cells present in any single timepoint — an over-detection ceiling. */
	public double spotsPerTimepointMax = Double.NaN;

	public int tMin;

	public int tMax;

	// ---- structural invariants (violations should be ~0 in clean GT) ----
	public int maxOutDegreeObserved;

	/** Nodes with in-degree > 1 (a cell with two parents = impossible merge). */
	public int inDegreeViolations;

	/** Nodes with out-degree > 2 (a cell splitting into >2 daughters = impossible). */
	public int outDegreeViolations;

	/** In-degree-0 nodes at t &gt; tMin (mid-movie appearances; expected ~0 away from FOV borders). */
	public int midMovieAppearances;

	/** Out-degree-0 nodes at t &lt; tMax (mid-movie terminations; expected ~0 away from FOV borders). */
	public int midMovieTerminations;

	// ---- statistical bounds (world units / frames) ----
	/** Per-frame displacement of continuing cells (out-degree-1 edges). */
	public double displacementP50 = Double.NaN;

	public double displacementP95 = Double.NaN;

	public double displacementP99 = Double.NaN;

	public double displacementMax = Double.NaN;

	/** Frames between successive divisions along a lineage (minimum cell-cycle length). */
	public double divisionIntervalMin = Double.NaN;

	public double divisionIntervalP05 = Double.NaN;

	public double divisionIntervalP50 = Double.NaN;

	/** Spot radius distribution. */
	public double radiusP01 = Double.NaN;

	public double radiusP50 = Double.NaN;

	public double radiusP99 = Double.NaN;

	/** Distance between the two daughters immediately after a division. */
	public double daughterSplitDistanceP50 = Double.NaN;

	public double daughterSplitDistanceP95 = Double.NaN;

	/** Radius ratio (larger/smaller) of the two daughters at division. */
	public double daughterSizeRatioP50 = Double.NaN;

	public double daughterSizeRatioP95 = Double.NaN;

	/** Length in frames of maximal non-branching track segments. */
	public double trackSegmentLengthP05 = Double.NaN;

	public double trackSegmentLengthP50 = Double.NaN;

	public double trackSegmentLengthP95 = Double.NaN;

	// -----------------------------------------------------------------------

	public JSONObject toJson()
	{
		final JSONObject o = new JSONObject();
		o.put( "schemaVersion", SCHEMA_VERSION );
		o.put( "sourcePath", sourcePath );
		o.put( "dataset", dataset );
		o.put( "geffVersion", geffVersion );
		o.put( "scale", scale );
		o.put( "sparseAnnotation", sparseAnnotation );

		o.put( "numNodes", numNodes );
		o.put( "numEdges", numEdges );
		o.put( "numFounders", numFounders );
		o.put( "numDivisions", numDivisions );
		putNum( o, "divisionRate", divisionRate );
		putNum( o, "divisionsPerFounder", divisionsPerFounder );
		putNum( o, "spotsPerTimepointP50", spotsPerTimepointP50 );
		putNum( o, "spotsPerTimepointMax", spotsPerTimepointMax );
		o.put( "tMin", tMin );
		o.put( "tMax", tMax );

		o.put( "maxOutDegreeObserved", maxOutDegreeObserved );
		o.put( "inDegreeViolations", inDegreeViolations );
		o.put( "outDegreeViolations", outDegreeViolations );
		o.put( "midMovieAppearances", midMovieAppearances );
		o.put( "midMovieTerminations", midMovieTerminations );

		putNum( o, "displacementP50", displacementP50 );
		putNum( o, "displacementP95", displacementP95 );
		putNum( o, "displacementP99", displacementP99 );
		putNum( o, "displacementMax", displacementMax );

		putNum( o, "divisionIntervalMin", divisionIntervalMin );
		putNum( o, "divisionIntervalP05", divisionIntervalP05 );
		putNum( o, "divisionIntervalP50", divisionIntervalP50 );

		putNum( o, "radiusP01", radiusP01 );
		putNum( o, "radiusP50", radiusP50 );
		putNum( o, "radiusP99", radiusP99 );

		putNum( o, "daughterSplitDistanceP50", daughterSplitDistanceP50 );
		putNum( o, "daughterSplitDistanceP95", daughterSplitDistanceP95 );
		putNum( o, "daughterSizeRatioP50", daughterSizeRatioP50 );
		putNum( o, "daughterSizeRatioP95", daughterSizeRatioP95 );

		putNum( o, "trackSegmentLengthP05", trackSegmentLengthP05 );
		putNum( o, "trackSegmentLengthP50", trackSegmentLengthP50 );
		putNum( o, "trackSegmentLengthP95", trackSegmentLengthP95 );
		return o;
	}

	/**
	 * Puts a double, mapping a non-finite value (NaN/Infinity — used when a statistic has no samples,
	 * e.g. no divisions) to {@link JSONObject#NULL}, since {@code org.json} rejects non-finite numbers.
	 * {@link #fromJson} reads these back as {@link Double#NaN} via {@code optDouble(key, NaN)}.
	 */
	private static void putNum( final JSONObject o, final String key, final double value )
	{
		o.put( key, Double.isFinite( value ) ? ( Object ) Double.valueOf( value ) : JSONObject.NULL );
	}

	public static LineageConstraintProfile fromJson( final JSONObject o )
	{
		final LineageConstraintProfile p = new LineageConstraintProfile();
		p.sourcePath = o.optString( "sourcePath", "" );
		p.dataset = o.optString( "dataset", "" );
		p.geffVersion = o.optString( "geffVersion", "unknown" );
		p.scale = o.optDouble( "scale", 1.0 );
		p.sparseAnnotation = o.optBoolean( "sparseAnnotation", false );

		p.numNodes = o.optInt( "numNodes" );
		p.numEdges = o.optInt( "numEdges" );
		p.numFounders = o.optInt( "numFounders" );
		p.numDivisions = o.optInt( "numDivisions" );
		p.divisionRate = o.optDouble( "divisionRate", Double.NaN );
		p.divisionsPerFounder = o.optDouble( "divisionsPerFounder", Double.NaN );
		p.spotsPerTimepointP50 = o.optDouble( "spotsPerTimepointP50", Double.NaN );
		p.spotsPerTimepointMax = o.optDouble( "spotsPerTimepointMax", Double.NaN );
		p.tMin = o.optInt( "tMin" );
		p.tMax = o.optInt( "tMax" );

		p.maxOutDegreeObserved = o.optInt( "maxOutDegreeObserved" );
		p.inDegreeViolations = o.optInt( "inDegreeViolations" );
		p.outDegreeViolations = o.optInt( "outDegreeViolations" );
		p.midMovieAppearances = o.optInt( "midMovieAppearances" );
		p.midMovieTerminations = o.optInt( "midMovieTerminations" );

		p.displacementP50 = o.optDouble( "displacementP50", Double.NaN );
		p.displacementP95 = o.optDouble( "displacementP95", Double.NaN );
		p.displacementP99 = o.optDouble( "displacementP99", Double.NaN );
		p.displacementMax = o.optDouble( "displacementMax", Double.NaN );

		p.divisionIntervalMin = o.optDouble( "divisionIntervalMin", Double.NaN );
		p.divisionIntervalP05 = o.optDouble( "divisionIntervalP05", Double.NaN );
		p.divisionIntervalP50 = o.optDouble( "divisionIntervalP50", Double.NaN );

		p.radiusP01 = o.optDouble( "radiusP01", Double.NaN );
		p.radiusP50 = o.optDouble( "radiusP50", Double.NaN );
		p.radiusP99 = o.optDouble( "radiusP99", Double.NaN );

		p.daughterSplitDistanceP50 = o.optDouble( "daughterSplitDistanceP50", Double.NaN );
		p.daughterSplitDistanceP95 = o.optDouble( "daughterSplitDistanceP95", Double.NaN );
		p.daughterSizeRatioP50 = o.optDouble( "daughterSizeRatioP50", Double.NaN );
		p.daughterSizeRatioP95 = o.optDouble( "daughterSizeRatioP95", Double.NaN );

		p.trackSegmentLengthP05 = o.optDouble( "trackSegmentLengthP05", Double.NaN );
		p.trackSegmentLengthP50 = o.optDouble( "trackSegmentLengthP50", Double.NaN );
		p.trackSegmentLengthP95 = o.optDouble( "trackSegmentLengthP95", Double.NaN );
		return p;
	}

	/** Writes the profile as pretty-printed JSON to {@code path}. */
	public void save( final String path ) throws IOException
	{
		Files.write( Paths.get( path ), toJson().toString( 2 ).getBytes( StandardCharsets.UTF_8 ) );
	}

	/** Loads a profile from a JSON file previously written by {@link #save(String)}. */
	public static LineageConstraintProfile load( final String path ) throws IOException
	{
		final String json = new String( Files.readAllBytes( Paths.get( path ) ), StandardCharsets.UTF_8 );
		return fromJson( new JSONObject( json ) );
	}

	/**
	 * Loads the profile at {@code path} only when it was learned from {@code currentDataset}.
	 *
	 * <p>A profile learned elsewhere is not a weaker prior, it is a different movie's biology stated as
	 * fact: {@code estimateMaxDisplacement} prefers a profile's P99 over measuring the dataset in front
	 * of it, and {@code divisionsAreRare} lets one cut division links. A profile that records no dataset
	 * predates the field and cannot be checked, so it is refused too — relearning is one menu action,
	 * and neither of those two effects announces itself in the output.</p>
	 *
	 * @param path           profile file, or null/blank for none
	 * @param currentDataset the ELEPHANT dataset now open
	 * @param onRefusal      given the reason when a profile exists but is not this dataset's; may be null
	 * @return the profile, or {@code null} when there is none, it cannot be read, or it is another
	 *         dataset's
	 */
	public static LineageConstraintProfile loadForDataset( final String path,
			final String currentDataset, final Consumer< String > onRefusal )
	{
		if ( path == null || path.trim().isEmpty() )
			return null;
		final LineageConstraintProfile p;
		try
		{
			p = load( path.trim() );
		}
		catch ( final Exception e )
		{
			return null; // missing/corrupt profile must not break correction
		}
		if ( currentDataset != null && !currentDataset.isEmpty()
				&& currentDataset.equals( p.dataset ) )
			return p;
		if ( onRefusal != null )
			onRefusal.accept( String.format(
					"IGNORING the active lineage-constraint profile: it was learned from %s, but this "
							+ "project is '%s'. Its per-frame displacement cap would REPLACE the one "
							+ "measured on this dataset, and its division rate can authorise cutting real "
							+ "division links. Relearn it here with Plugins > Automahout > Advanced > Learn Lineage "
							+ "Constraints, or clear the path in the settings. Proceeding without a "
							+ "profile for this run.",
					p.dataset == null || p.dataset.trim().isEmpty()
							? "a dataset it did not record (a profile written before profiles were scoped)"
							: "'" + p.dataset + "'",
					currentDataset ) );
		return null;
	}

	/** Human-readable multi-line summary, suitable for the log window. */
	public String report()
	{
		final StringBuilder sb = new StringBuilder();
		sb.append( String.format( "Lineage constraint profile (geff=%s, version=%s, scale=%.3g%s):%n",
				sourcePath, geffVersion, scale,
				sparseAnnotation ? ", SPARSE annotation — density/rate stats not representative" : "" ) );
		sb.append( String.format( "  Graph: %d nodes, %d edges, %d founders, %d divisions, t=[%d..%d]%n",
				numNodes, numEdges, numFounders, numDivisions, tMin, tMax ) );
		sb.append( String.format( "    division rate: %.2f%% of cells divide (%.2f per founder)%n",
				divisionRate * 100.0, divisionsPerFounder ) );
		sb.append( String.format( "    cells per timepoint: p50=%.0f max=%.0f%n",
				spotsPerTimepointP50, spotsPerTimepointMax ) );
		sb.append( "  Structural invariants (violations should be 0 in clean GT):" ).append( System.lineSeparator() );
		sb.append( String.format( "    max out-degree observed = %d%n", maxOutDegreeObserved ) );
		sb.append( String.format( "    in-degree>1 (merge): %d | out-degree>2: %d%n",
				inDegreeViolations, outDegreeViolations ) );
		sb.append( String.format( "    mid-movie appearances: %d | mid-movie terminations: %d%n",
				midMovieAppearances, midMovieTerminations ) );
		sb.append( "  Statistical bounds (world units / frames):" ).append( System.lineSeparator() );
		sb.append( String.format( "    displacement/frame: p50=%.2f p95=%.2f p99=%.2f max=%.2f%n",
				displacementP50, displacementP95, displacementP99, displacementMax ) );
		sb.append( String.format( "    division interval: min=%.0f p05=%.0f p50=%.0f frames%n",
				divisionIntervalMin, divisionIntervalP05, divisionIntervalP50 ) );
		sb.append( String.format( "    radius: p01=%.2f p50=%.2f p99=%.2f%n", radiusP01, radiusP50, radiusP99 ) );
		sb.append( String.format( "    daughter split distance: p50=%.2f p95=%.2f%n",
				daughterSplitDistanceP50, daughterSplitDistanceP95 ) );
		sb.append( String.format( "    daughter size ratio: p50=%.2f p95=%.2f%n",
				daughterSizeRatioP50, daughterSizeRatioP95 ) );
		sb.append( String.format( "    track segment length: p05=%.0f p50=%.0f p95=%.0f frames",
				trackSegmentLengthP05, trackSegmentLengthP50, trackSegmentLengthP95 ) );
		return sb.toString();
	}

	/**
	 * Renders the profile as a Markdown "valid lineage grammar" for the LLM system prompt: hard
	 * structural invariants plus soft statistical bounds, each followed by the repair the agent should
	 * consider when the current graph violates it. Only fields that were actually learned (non-NaN)
	 * are emitted. Returns an empty string if the profile carries no usable graph.
	 */
	public String promptSection()
	{
		if ( numNodes == 0 )
			return "";
		final StringBuilder sb = new StringBuilder();
		sb.append( "## Learned Lineage-Graph Constraints\n" );
		sb.append( "These rules were learned from a ground-truth lineage (" ).append( numNodes )
				.append( " cells, " ).append( numDivisions ).append( " divisions). A valid cell-lineage " )
				.append( "graph obeys them; where the current tracking violates one, treat it as an ERROR " )
				.append( "and repair it (auto.correct already enforces the numeric thresholds below "
						+ "automatically, but use them to judge the visual state and decide follow-up actions).\n\n" );

		sb.append( "Hard structural invariants (a violation is always a linking/detection error):\n" );
		sb.append( "  - A cell has at most ONE parent. Two incoming links (a merge) is impossible — "
				+ "delete the wrong link.\n" );
		sb.append( "  - A cell divides into at most TWO daughters. Three+ outgoing links (a trifurcation) "
				+ "is impossible — delete the spurious link(s).\n" );
		sb.append( "  - A track does not appear from nothing mid-movie: a cell with no parent at a "
				+ "timepoint after the first (and not at an image border) should be linked back to a "
				+ "predecessor. A track does not vanish mid-movie without dividing.\n\n" );

		if ( sparseAnnotation )
			sb.append( "NOTE — the reference is SPARSELY annotated (only some cells labelled), so cell "
					+ "COUNTS and RATES are not representative: do NOT treat the number of spots per "
					+ "timepoint or the frequency of divisions as bounded by the reference, and do not judge "
					+ "over/under-detection from it. Only the relational rules here (topology, per-frame "
					+ "displacement, division geometry) transfer; use the image to judge density.\n\n" );

		// Density- and rate-dependent guidance is only valid for a densely-annotated reference.
		if ( !sparseAnnotation )
		{
		// Division rarity — the strongest anti-false-branching signal. Emitted prominently because a
		// low division rate means most branchings in the current tracking are likely errors.
		if ( !Double.isNaN( divisionRate ) )
		{
			if ( divisionRate < 0.05 )
				sb.append( String.format( "IMPORTANT — divisions are RARE in this biology: only %.1f%% of "
						+ "cells divide (%d divisions in %d cells). Treat almost every branching (a cell "
						+ "with two outgoing links) as a SUSPECTED FALSE division: verify it against the "
						+ "image, and prefer dissolving the branch (reconnect the spurious daughter to its "
						+ "mother's track, e.g. via auto.correct fill_gaps) over keeping it. Do not accept a "
						+ "division unless the image clearly shows one cell becoming two.\n\n",
						divisionRate * 100.0, numDivisions, numNodes ) );
			else
				sb.append( String.format( "Division rate: about %.1f%% of cells divide (%d in %d). Branchings "
						+ "well above this rate are likely false divisions to scrutinise.\n\n",
						divisionRate * 100.0, numDivisions, numNodes ) );
		}

		// Expected cell count per timepoint — the anchor against over-detection (the dominant failure
		// when a low-density dataset is detected with too-low thresholds).
		if ( !Double.isNaN( spotsPerTimepointMax ) )
			sb.append( String.format( "IMPORTANT — expected detection density: the reference has about %.0f "
					+ "cells per timepoint (at most %.0f). If detection produces MANY more than %.0f spots "
					+ "per timepoint, it is OVER-DETECTING (false positives that will link into spurious "
					+ "branches): RAISE min_intensity_ratio (e.g. 0.4-0.5) and set a positive min_size; do "
					+ "NOT lower thresholds to chase completeness. If the current spots/timepoint already "
					+ "matches this range and continuity is high, the tracking is essentially done — do NOT "
					+ "clear it and re-detect.\n\n",
					spotsPerTimepointP50, spotsPerTimepointMax, spotsPerTimepointMax ) );
		} // end !sparseAnnotation (density/rate-dependent guidance)

		// Relational bounds below transfer even under sparse annotation (they describe how a single
		// cell moves and how a division looks, independent of how many cells are labelled).
		sb.append( "Learned quantitative bounds (world units / frames):\n" );
		if ( !Double.isNaN( displacementP99 ) )
			sb.append( String.format( "  - A cell moves at most ~%.1f (typically %.1f) per frame. A link "
					+ "longer than that is likely a mis-link — prune it.\n", displacementP99, displacementP50 ) );
		if ( !Double.isNaN( divisionIntervalMin ) && divisionIntervalMin >= 1.0 )
			sb.append( String.format( "  - Successive divisions along a lineage are at least ~%.0f frames "
					+ "apart. A division sooner than that is likely FALSE — reconnect the spurious daughter "
					+ "to its mother's track instead of branching.\n", divisionIntervalMin ) );
		if ( !Double.isNaN( radiusP01 ) && !Double.isNaN( radiusP99 ) )
			sb.append( String.format( "  - Cell radius is typically %.1f–%.1f. Spots far larger (e.g. an "
					+ "oversized t=0 blob) or far smaller (background specks) are likely errors.\n",
					radiusP01, radiusP99 ) );
		if ( !Double.isNaN( daughterSplitDistanceP95 ) )
			sb.append( String.format( "  - At a division the two daughters are at most ~%.1f apart. Two "
					+ "distant spots are unlikely to be sisters.\n", daughterSplitDistanceP95 ) );
		if ( !sparseAnnotation && !Double.isNaN( trackSegmentLengthP05 ) && trackSegmentLengthP05 >= 2.0 )
			sb.append( String.format( "  - Real track segments last at least ~%.0f frames. A shorter "
					+ "isolated post-division tracklet is likely spurious — reconnect it to its mother or "
					+ "remove it.\n", trackSegmentLengthP05 ) );
		sb.append( "\n" );
		return sb.toString();
	}
}
