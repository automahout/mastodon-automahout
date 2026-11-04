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
package org.mastodon.mamut.automahout.plugin;

import java.util.prefs.Preferences;

/**
 * User-configurable settings for the Automahout plugin.
 *
 * <p>Settings are automatically loaded from the Java user {@link Preferences}
 * on construction and can be persisted at any time by calling {@link #save()}.
 * All parameters have sensible defaults that match the original hard-coded
 * values.</p>
 */
public class AutomahoutSettings
{

	private static final Preferences PREFS =
			org.mastodon.mamut.automahout.tool.AutomahoutPrefs.node( AutomahoutSettings.class );

	// ---- preference keys --------------------------------------------------

	private static final String KEY_CORRECT_LONG_LINK_FACTOR = "correctLongLinkFactor";

	/**
	 * Bumped so a preference saved before this stays saved but stops applying. The default was already
	 * off; a stale ticked box turned it on for a whole-movie auto.correct, which refit 10475 spots and
	 * took 75 minutes of the run's 115 to do something the user reports as not worth doing.
	 */
	private static final String KEY_CORRECT_REFIT_ANISOTROPIC = "correctRefitAnisotropic.v2";

	private static final String KEY_CORRECT_ANISOTROPY_THRESHOLD = "correctAnisotropyThreshold";

	private static final String KEY_CORRECT_MIN_INTENSITY_RATIO = "correctMinIntensityRatio";

	private static final String KEY_CORRECT_QUALITY_THRESHOLD = "correctQualityThreshold";

	private static final String KEY_CORRECT_MIN_SIZE_RATIO = "correctMinSizeRatio";

	private static final String KEY_START_TIMEPOINT = "startTimepoint";

	private static final String KEY_END_TIMEPOINT = "endTimepoint";

	private static final String KEY_CORRECT_MERGE_OVERLAP_RATIO = "correctMergeOverlapRatio";

	private static final String KEY_CORRECT_FILL_GAPS = "correctFillGaps";

	private static final String KEY_CORRECT_GAP_CROP_FACTOR = "correctGapCropFactor";

	private static final String KEY_CORRECT_MIN_TRACKLET_LENGTH = "correctMinTrackletLength";


	private static final String KEY_CORRECT_DIVISION_LINK_FACTOR = "correctDivisionLinkFactor";

	private static final String KEY_VLM_BACKEND = "vlmBackend";

	private static final String KEY_VLM_BASE_URL = "vlmBaseUrl";

	private static final String KEY_VLM_AWS_REGION = "vlmAwsRegion";

	private static final String KEY_VLM_MODEL = "vlmModel";

	private static final String KEY_VLM_API_KEY = "vlmApiKey";

	private static final String KEY_VLM_MAX_SNAPSHOTS = "vlmMaxSnapshots";

	private static final String KEY_VLM_OUTPUT_FILE = "vlmOutputFile";

	private static final String KEY_VLM_Z_SLICES = "vlmZSlicesPerTimepoint";

	private static final String KEY_CONSTRAINTS_PROFILE_PATH = "constraintsProfilePath";

	private static final String KEY_GT_ANNOTATIONS_PATH = "gtAnnotationsPath";

	/**
	 * The ELEPHANT dataset the GT annotation store was built from.
	 *
	 * <p>These settings live in {@code Preferences.userNodeForPackage}, which is one node per USER, not
	 * per project. Opening a different dataset therefore inherits the previous one's GT path, and the
	 * store loads without complaint because a path is just a path — spots from one movie were being
	 * used as training labels for another. Recording which dataset a store belongs to is what lets the
	 * load refuse.</p>
	 *
	 * <p>The same shape of bug as the acceptance profile's single global path, which had to be scoped
	 * per dataset for exactly this reason.</p>
	 */
	private static final String KEY_GT_ANNOTATIONS_DATASET = "gtAnnotationsDataset";

	private static final String KEY_ACCEPTANCE_PROFILE_PATH = "acceptanceProfilePath";

	/** Next timepoint an interrupted adaptive-tracking sweep should resume from ({@code -1} = none). */
	private static final String KEY_ADAPTIVE_RESUME_TP = "adaptiveResumeTimepoint";

	/** Dataset size the resume marker was recorded for; one half of the guard below. */
	private static final String KEY_ADAPTIVE_RESUME_NTP = "adaptiveResumeNumTimepoints";

	/**
	 * The ELEPHANT dataset the resume marker was recorded for.
	 *
	 * <p>These preferences live in one node per USER, not per project, and the timepoint count alone is
	 * not a dataset identity: any other movie of the same length inherits the marker. Same guard as
	 * {@link #KEY_GT_ANNOTATIONS_DATASET} and {@code LineageConstraintProfile.dataset}.</p>
	 */
	private static final String KEY_ADAPTIVE_RESUME_DATASET = "adaptiveResumeDataset";

	// ---- defaults ---------------------------------------------------------

	/**
	 * Default long-link factor: prune links whose distance exceeds
	 * this multiple of {@code max(r_source, r_target)}.
	 */
	static final double DEFAULT_CORRECT_LONG_LINK_FACTOR = 3.0;

	/** Default: shape refinement via ELEPHANT is off by default. */
	static final boolean DEFAULT_CORRECT_REFIT_ANISOTROPIC = false;

	/**
	 * Default minimum intensity ratio for spot deletion: {@code 0.0} = disabled.
	 * When &gt; 0, spots whose sampled voxel intensity is below
	 * {@code ratio × median_intensity_at_timepoint} are deleted.
	 */
	static final double DEFAULT_CORRECT_MIN_INTENSITY_RATIO = 0.0;

	/**
	 * Default ELEPHANT-quality threshold for spot deletion: {@code 0.5}, matching ELEPHANT's own
	 * {@code prob_threshold} request default. Absolute cutoff (no median involved, unlike
	 * {@link #DEFAULT_CORRECT_MIN_INTENSITY_RATIO}): quality is already a calibrated model probability.
	 * Applies only to spots with a recorded {@link org.mastodon.mamut.automahout.elephant.DetectionQuality};
	 * quality-less spots keep falling back to the intensity-ratio rule above.
	 */
	static final double DEFAULT_CORRECT_QUALITY_THRESHOLD = 0.5;

	/**
	 * Default minimum size ratio for spot deletion: {@code 0.0} = disabled.
	 * When &gt; 0, spots whose bounding-sphere radius is below
	 * {@code ratio × median_radius_at_timepoint} are deleted.
	 */
	static final double DEFAULT_CORRECT_MIN_SIZE_RATIO = 0.0;

	/**
	 * Default anisotropy ratio threshold for shape refinement.
	 * A spot is considered anisotropic when
	 * {@code max(diag(cov)) / min(diag(cov)) > threshold}.
	 */
	static final double DEFAULT_CORRECT_ANISOTROPY_THRESHOLD = 10.0;

	/**
	 * Default start timepoint: {@code -1} = use the viewer's current timepoint
	 * when the loop is started.
	 */
	static final int DEFAULT_START_TIMEPOINT = -1;

	/**
	 * Default end timepoint: {@code -1} = use the last timepoint in the dataset.
	 */
	static final int DEFAULT_END_TIMEPOINT = -1;

	/**
	 * Default merge overlap ratio: {@code 0.0} = disabled.
	 * When &gt; 0, two spots are merged when their centre distance is less than
	 * {@code ratio × max(r1, r2)}.
	 */
	static final double DEFAULT_CORRECT_MERGE_OVERLAP_RATIO = 0.0;

	/** Default: gap filling is disabled. */
	static final boolean DEFAULT_CORRECT_FILL_GAPS = false;

	/**
	 * Default crop factor for gap filling: the ELEPHANT crop box around a dangling
	 * end spot is {@code cropFactor × radius} on each side.
	 */
	static final double DEFAULT_CORRECT_GAP_CROP_FACTOR = 3.0;

	/**
	 * Default minimum tracklet length for gap filling.
	 * Dangling-end tracklets shorter than this are deleted instead of extended.
	 * {@code 0} disables the check.
	 */
	static final int DEFAULT_CORRECT_MIN_TRACKLET_LENGTH = 5;

	/**
	 * Default division-link search factor: {@code 0.0} = disabled.
	 * When &gt; 0, track-start spots (no incoming edge) are linked to the nearest
	 * candidate mother cell at t-1 within {@code factor × max(r_orphan, r_mother)}.
	 */
	static final double DEFAULT_CORRECT_DIVISION_LINK_FACTOR = 0.0;

	/** Default VLM backend: Ollama (runs locally, no API key required). */
	static final String DEFAULT_VLM_BACKEND = "ollama";

	/** Default Ollama server URL. */
	static final String DEFAULT_VLM_BASE_URL = "http://localhost:11434";

	static final String DEFAULT_VLM_AWS_REGION = "ap-northeast-1";

	/** Default vision model for Ollama. */
	static final String DEFAULT_VLM_MODEL = "llava";

	/** Default Anthropic API key: empty = fall back to {@code ANTHROPIC_API_KEY} env var. */
	static final String DEFAULT_VLM_API_KEY = "";

	/** Default number of BDV snapshots to capture per analysis run. */
	static final int DEFAULT_VLM_MAX_SNAPSHOTS = 8;

	/** Default path for the Markdown output file. */
	static final String DEFAULT_VLM_OUTPUT_FILE = "docs/video_description.md";

	/** Empty = no learned lineage-constraint profile active. */
	static final String DEFAULT_CONSTRAINTS_PROFILE_PATH = "";

	/** Empty = no trusted GT annotation store active (adaptive_track falls back to a graph snapshot). */
	static final String DEFAULT_GT_ANNOTATIONS_PATH = "";

	/** Empty = no learned detection-acceptance profile active. */
	static final String DEFAULT_ACCEPTANCE_PROFILE_PATH = "";

	/** Default number of Z slices captured per timepoint. */
	static final int DEFAULT_VLM_Z_SLICES = 3;

	// ---- fields -----------------------------------------------------------

	private double correctLongLinkFactor;

	/**
	 * Absolute per-frame displacement cap in world units (0 = disabled). Not persisted: it is derived
	 * per run from the active learned lineage-constraint profile (displacement P99) and used as an
	 * extra bound on link pruning and orphan reconnection.
	 */
	private double correctMaxDisplacement = 0.0;

	private boolean correctRefitAnisotropic;

	private double correctAnisotropyThreshold;

	private double correctMinIntensityRatio;

	private double correctQualityThreshold;

	private double correctMinSizeRatio;

	private int startTimepoint;

	private int endTimepoint;

	private double correctMergeOverlapRatio;

	private boolean correctFillGaps;

	private double correctGapCropFactor;

	private int correctMinTrackletLength;

	private double correctDivisionLinkFactor;

	private String vlmBackend;

	private String vlmBaseUrl;

	private String vlmAwsRegion;

	private String vlmModel;

	private String vlmApiKey;

	private int vlmMaxSnapshots;

	private String vlmOutputFile;

	private String constraintsProfilePath;

	private String gtAnnotationsPath;

	private String gtAnnotationsDataset;

	private String acceptanceProfilePath;

	private int vlmZSlicesPerTimepoint;

	// -----------------------------------------------------------------------

	/**
	 * Creates a new settings instance and loads persisted values.
	 * Falls back to defaults for any key not yet stored.
	 */
	public AutomahoutSettings()
	{
		load();
	}

	/** Loads all values from persistent storage. */
	public void load()
	{
		correctLongLinkFactor = PREFS.getDouble( KEY_CORRECT_LONG_LINK_FACTOR, DEFAULT_CORRECT_LONG_LINK_FACTOR );
		correctRefitAnisotropic = PREFS.getBoolean( KEY_CORRECT_REFIT_ANISOTROPIC, DEFAULT_CORRECT_REFIT_ANISOTROPIC );
		correctAnisotropyThreshold = PREFS.getDouble( KEY_CORRECT_ANISOTROPY_THRESHOLD, DEFAULT_CORRECT_ANISOTROPY_THRESHOLD );
		correctMinIntensityRatio = PREFS.getDouble( KEY_CORRECT_MIN_INTENSITY_RATIO, DEFAULT_CORRECT_MIN_INTENSITY_RATIO );
		correctQualityThreshold = PREFS.getDouble( KEY_CORRECT_QUALITY_THRESHOLD, DEFAULT_CORRECT_QUALITY_THRESHOLD );
		correctMinSizeRatio = PREFS.getDouble( KEY_CORRECT_MIN_SIZE_RATIO, DEFAULT_CORRECT_MIN_SIZE_RATIO );
		startTimepoint = PREFS.getInt( KEY_START_TIMEPOINT, DEFAULT_START_TIMEPOINT );
		endTimepoint = PREFS.getInt( KEY_END_TIMEPOINT, DEFAULT_END_TIMEPOINT );
		correctMergeOverlapRatio = PREFS.getDouble( KEY_CORRECT_MERGE_OVERLAP_RATIO, DEFAULT_CORRECT_MERGE_OVERLAP_RATIO );
		correctFillGaps = PREFS.getBoolean( KEY_CORRECT_FILL_GAPS, DEFAULT_CORRECT_FILL_GAPS );
		correctGapCropFactor = PREFS.getDouble( KEY_CORRECT_GAP_CROP_FACTOR, DEFAULT_CORRECT_GAP_CROP_FACTOR );
		correctMinTrackletLength = PREFS.getInt( KEY_CORRECT_MIN_TRACKLET_LENGTH, DEFAULT_CORRECT_MIN_TRACKLET_LENGTH );
		correctDivisionLinkFactor = PREFS.getDouble( KEY_CORRECT_DIVISION_LINK_FACTOR, DEFAULT_CORRECT_DIVISION_LINK_FACTOR );
		vlmBackend = PREFS.get( KEY_VLM_BACKEND, DEFAULT_VLM_BACKEND );
		vlmBaseUrl = PREFS.get( KEY_VLM_BASE_URL, DEFAULT_VLM_BASE_URL );
		vlmAwsRegion = PREFS.get( KEY_VLM_AWS_REGION, DEFAULT_VLM_AWS_REGION );
		vlmModel = PREFS.get( KEY_VLM_MODEL, DEFAULT_VLM_MODEL );
		vlmApiKey = PREFS.get( KEY_VLM_API_KEY, DEFAULT_VLM_API_KEY );
		vlmMaxSnapshots = PREFS.getInt( KEY_VLM_MAX_SNAPSHOTS, DEFAULT_VLM_MAX_SNAPSHOTS );
		vlmOutputFile = PREFS.get( KEY_VLM_OUTPUT_FILE, DEFAULT_VLM_OUTPUT_FILE );
		constraintsProfilePath = PREFS.get( KEY_CONSTRAINTS_PROFILE_PATH, DEFAULT_CONSTRAINTS_PROFILE_PATH );
		gtAnnotationsPath = PREFS.get( KEY_GT_ANNOTATIONS_PATH, DEFAULT_GT_ANNOTATIONS_PATH );
		gtAnnotationsDataset = PREFS.get( KEY_GT_ANNOTATIONS_DATASET, "" );
		acceptanceProfilePath = PREFS.get( KEY_ACCEPTANCE_PROFILE_PATH, DEFAULT_ACCEPTANCE_PROFILE_PATH );
		vlmZSlicesPerTimepoint = PREFS.getInt( KEY_VLM_Z_SLICES, DEFAULT_VLM_Z_SLICES );
	}

	/** Persists all current values to storage. */
	public void save()
	{
		PREFS.putDouble( KEY_CORRECT_LONG_LINK_FACTOR, correctLongLinkFactor );
		PREFS.putBoolean( KEY_CORRECT_REFIT_ANISOTROPIC, correctRefitAnisotropic );
		PREFS.putDouble( KEY_CORRECT_ANISOTROPY_THRESHOLD, correctAnisotropyThreshold );
		PREFS.putDouble( KEY_CORRECT_MIN_INTENSITY_RATIO, correctMinIntensityRatio );
		PREFS.putDouble( KEY_CORRECT_QUALITY_THRESHOLD, correctQualityThreshold );
		PREFS.putDouble( KEY_CORRECT_MIN_SIZE_RATIO, correctMinSizeRatio );
		PREFS.putInt( KEY_START_TIMEPOINT, startTimepoint );
		PREFS.putInt( KEY_END_TIMEPOINT, endTimepoint );
		PREFS.putDouble( KEY_CORRECT_MERGE_OVERLAP_RATIO, correctMergeOverlapRatio );
		PREFS.putBoolean( KEY_CORRECT_FILL_GAPS, correctFillGaps );
		PREFS.putDouble( KEY_CORRECT_GAP_CROP_FACTOR, correctGapCropFactor );
		PREFS.putInt( KEY_CORRECT_MIN_TRACKLET_LENGTH, correctMinTrackletLength );
		PREFS.putDouble( KEY_CORRECT_DIVISION_LINK_FACTOR, correctDivisionLinkFactor );
		PREFS.put( KEY_VLM_BACKEND, vlmBackend );
		PREFS.put( KEY_VLM_BASE_URL, vlmBaseUrl );
		PREFS.put( KEY_VLM_AWS_REGION, vlmAwsRegion );
		PREFS.put( KEY_VLM_MODEL, vlmModel );
		PREFS.put( KEY_VLM_API_KEY, vlmApiKey );
		PREFS.putInt( KEY_VLM_MAX_SNAPSHOTS, vlmMaxSnapshots );
		PREFS.put( KEY_VLM_OUTPUT_FILE, vlmOutputFile );
		PREFS.put( KEY_CONSTRAINTS_PROFILE_PATH, constraintsProfilePath );
		PREFS.put( KEY_GT_ANNOTATIONS_PATH, gtAnnotationsPath );
		PREFS.put( KEY_GT_ANNOTATIONS_DATASET, gtAnnotationsDataset == null ? "" : gtAnnotationsDataset );
		PREFS.put( KEY_ACCEPTANCE_PROFILE_PATH, acceptanceProfilePath );
		PREFS.putInt( KEY_VLM_Z_SLICES, vlmZSlicesPerTimepoint );
	}

	// ---- getters and setters ----------------------------------------------

	/**
	 * Factor for long-link detection: a link is pruned when its Euclidean
	 * distance exceeds {@code factor × max(r_source, r_target)}.
	 */
	public double getCorrectLongLinkFactor()
	{
		return correctLongLinkFactor;
	}

	public void setCorrectLongLinkFactor( final double correctLongLinkFactor )
	{
		this.correctLongLinkFactor = correctLongLinkFactor;
	}

	/** Absolute per-frame displacement cap in world units; 0 disables it. */
	public double getCorrectMaxDisplacement()
	{
		return correctMaxDisplacement;
	}

	public void setCorrectMaxDisplacement( final double correctMaxDisplacement )
	{
		this.correctMaxDisplacement = correctMaxDisplacement;
	}

	/** Whether to refit anisotropic spots via ELEPHANT during auto-correction. */
	public boolean isCorrectRefitAnisotropic()
	{
		return correctRefitAnisotropic;
	}

	public void setCorrectRefitAnisotropic( final boolean correctRefitAnisotropic )
	{
		this.correctRefitAnisotropic = correctRefitAnisotropic;
	}

	/**
	 * Anisotropy ratio threshold for shape refinement:
	 * {@code max(diag(cov)) / min(diag(cov)) > threshold} triggers a refit.
	 */
	public double getCorrectAnisotropyThreshold()
	{
		return correctAnisotropyThreshold;
	}

	public void setCorrectAnisotropyThreshold( final double correctAnisotropyThreshold )
	{
		this.correctAnisotropyThreshold = correctAnisotropyThreshold;
	}

	/**
	 * Minimum intensity ratio for weak-spot deletion.
	 * Spots whose sampled voxel intensity is below
	 * {@code ratio × median_intensity_at_timepoint} are deleted.
	 * {@code 0.0} disables this check (default).
	 */
	public double getCorrectMinIntensityRatio()
	{
		return correctMinIntensityRatio;
	}

	public void setCorrectMinIntensityRatio( final double ratio )
	{
		this.correctMinIntensityRatio = ratio;
	}

	/**
	 * Absolute ELEPHANT-quality cutoff for weak-spot deletion. Applies only to spots with a recorded
	 * {@link org.mastodon.mamut.automahout.elephant.DetectionQuality} (no median involved, unlike
	 * {@link #getCorrectMinIntensityRatio()}); spots lacking a recorded quality keep falling back to the
	 * intensity-ratio rule. {@code 0.0} disables this check.
	 */
	public double getCorrectQualityThreshold()
	{
		return correctQualityThreshold;
	}

	public void setCorrectQualityThreshold( final double threshold )
	{
		this.correctQualityThreshold = threshold;
	}

	/**
	 * Minimum size ratio for small-spot deletion.
	 * Spots whose bounding-sphere radius is below
	 * {@code ratio × median_radius_at_timepoint} are deleted.
	 * {@code 0.0} disables this check (default).
	 */
	public double getCorrectMinSizeRatio()
	{
		return correctMinSizeRatio;
	}

	public void setCorrectMinSizeRatio( final double ratio )
	{
		this.correctMinSizeRatio = ratio;
	}

	/**
	 * Start timepoint for the processing loop.
	 * {@code -1} means "use the viewer's current timepoint when the loop starts".
	 */
	public int getStartTimepoint()
	{
		return startTimepoint;
	}

	public void setStartTimepoint( final int startTimepoint )
	{
		this.startTimepoint = startTimepoint;
	}

	/**
	 * End timepoint for the processing loop (inclusive).
	 * {@code -1} means "use the last timepoint in the dataset".
	 */
	public int getEndTimepoint()
	{
		return endTimepoint;
	}

	public void setEndTimepoint( final int endTimepoint )
	{
		this.endTimepoint = endTimepoint;
	}

	/**
	 * Overlap ratio for duplicate-spot merging.
	 * Two spots are merged when {@code distance(c1, c2) < ratio × max(r1, r2)}.
	 * {@code 0.0} disables merging (default).
	 */
	public double getCorrectMergeOverlapRatio()
	{
		return correctMergeOverlapRatio;
	}

	public void setCorrectMergeOverlapRatio( final double ratio )
	{
		this.correctMergeOverlapRatio = ratio;
	}

	public boolean isCorrectFillGaps() { return correctFillGaps; }
	public void setCorrectFillGaps( final boolean v ) { this.correctFillGaps = v; }

	public double getCorrectGapCropFactor() { return correctGapCropFactor; }
	public void setCorrectGapCropFactor( final double v ) { this.correctGapCropFactor = v; }

	/** Minimum tracklet length for gap filling; shorter dangling tracklets are deleted. {@code 0} = disabled. */
	public int getCorrectMinTrackletLength() { return correctMinTrackletLength; }
	public void setCorrectMinTrackletLength( final int v ) { this.correctMinTrackletLength = v; }

	/**
	 * Search radius factor for division-daughter linking.
	 * An orphan track start (no incoming edge) is linked to the nearest mother
	 * candidate at t-1 within {@code factor × max(r_orphan, r_mother)}.
	 * {@code 0.0} disables this step (default).
	 */
	public double getCorrectDivisionLinkFactor() { return correctDivisionLinkFactor; }
	public void setCorrectDivisionLinkFactor( final double v ) { this.correctDivisionLinkFactor = v; }

	// ---- VLM/LLM settings -------------------------------------------------

	/** VLM backend identifier: {@code "ollama"} or {@code "anthropic"}. */
	public String getVlmBackend() { return vlmBackend; }
	public void setVlmBackend( final String v ) { this.vlmBackend = v; }

	/** Ollama server base URL (ignored for Anthropic backend). */
	public String getVlmBaseUrl() { return vlmBaseUrl; }
	public void setVlmBaseUrl( final String v ) { this.vlmBaseUrl = v; }
	public String getVlmAwsRegion() { return vlmAwsRegion; }
	public void setVlmAwsRegion( final String v ) { this.vlmAwsRegion = v; }

	/** Model name / tag (e.g. {@code "llava"}, {@code "claude-opus-4-8"}). */
	public String getVlmModel() { return vlmModel; }
	public void setVlmModel( final String v ) { this.vlmModel = v; }

	/**
	 * Anthropic API key (ignored for Ollama).
	 * Empty string means fall back to the {@code ANTHROPIC_API_KEY} environment variable.
	 */
	public String getVlmApiKey() { return vlmApiKey; }
	public void setVlmApiKey( final String v ) { this.vlmApiKey = v; }

	/** Number of representative BDV snapshots to capture per analysis run. */
	public int getVlmMaxSnapshots() { return vlmMaxSnapshots; }
	public void setVlmMaxSnapshots( final int v ) { this.vlmMaxSnapshots = v; }

	/** Path of the Markdown output file produced by {@code video.analyze}. */
	public String getVlmOutputFile() { return vlmOutputFile; }
	public void setVlmOutputFile( final String v ) { this.vlmOutputFile = v; }

	/** Path to the active learned lineage-constraint profile JSON, or empty if none. */
	public String getConstraintsProfilePath() { return constraintsProfilePath; }
	public void setConstraintsProfilePath( final String v ) { this.constraintsProfilePath = v; }

	/** Path to the active trusted GT annotation store (JSON), or empty if none. */
	public String getGtAnnotationsPath() { return gtAnnotationsPath; }
	public void setGtAnnotationsPath( final String v ) { this.gtAnnotationsPath = v; }

	/** Dataset the active GT store was built from — see {@link #KEY_GT_ANNOTATIONS_DATASET}. */
	public String getGtAnnotationsDataset() { return gtAnnotationsDataset; }
	public void setGtAnnotationsDataset( final String v ) { this.gtAnnotationsDataset = v; }

	/** Path to the active learned detection-acceptance profile (JSON), or empty if none. */
	public String getAcceptanceProfilePath() { return acceptanceProfilePath; }
	public void setAcceptanceProfilePath( final String v ) { this.acceptanceProfilePath = v; }

	/** Number of Z slices captured per timepoint (evenly distributed across the Z stack). */
	public int getVlmZSlicesPerTimepoint() { return vlmZSlicesPerTimepoint; }
	public void setVlmZSlicesPerTimepoint( final int v ) { this.vlmZSlicesPerTimepoint = v; }

	// ---- adaptive-tracking resume marker ----------------------------------
	// Persisted immediately (independent of load()/save()) so an interrupted adaptive sweep — even one
	// killed by a crash before anything is saved — can resume from where it stopped. This is needed on
	// top of the spot-presence heuristic because a SECOND pass over an already-fully-tracked movie leaves
	// spots everywhere, so "last timepoint with spots" cannot reveal how far the current pass got.

	/**
	 * Records that an adaptive sweep has completed everything up to (but not including) {@code nextTp}
	 * for a dataset of {@code numTimepoints}, so a later run can resume from {@code nextTp}.
	 */
	public static void persistAdaptiveResume( final int nextTp, final int numTimepoints,
			final String dataset )
	{
		PREFS.putInt( KEY_ADAPTIVE_RESUME_TP, nextTp );
		PREFS.putInt( KEY_ADAPTIVE_RESUME_NTP, numTimepoints );
		PREFS.put( KEY_ADAPTIVE_RESUME_DATASET, dataset == null ? "" : dataset );
		try
		{
			PREFS.flush();
		}
		catch ( final java.util.prefs.BackingStoreException e )
		{
			// Non-fatal: the marker just won't survive a crash; the in-memory value is still set.
		}
	}

	/** Clears the resume marker (call on natural completion of a full sweep). */
	public static void clearAdaptiveResume()
	{
		PREFS.remove( KEY_ADAPTIVE_RESUME_TP );
		PREFS.remove( KEY_ADAPTIVE_RESUME_NTP );
		PREFS.remove( KEY_ADAPTIVE_RESUME_DATASET );
		try
		{
			PREFS.flush();
		}
		catch ( final java.util.prefs.BackingStoreException e )
		{
			// Non-fatal.
		}
	}

	/** Next timepoint an interrupted adaptive sweep should resume from, or {@code -1} if none recorded. */
	public static int getAdaptiveResumeTimepoint()
	{
		return PREFS.getInt( KEY_ADAPTIVE_RESUME_TP, -1 );
	}

	/** Dataset size the resume marker was recorded for, or {@code -1} if none recorded. */
	/** The dataset the marker was recorded for, or {@code ""} for a marker written before it was scoped. */
	public static String getAdaptiveResumeDataset()
	{
		return PREFS.get( KEY_ADAPTIVE_RESUME_DATASET, "" );
	}

	public static int getAdaptiveResumeNumTimepoints()
	{
		return PREFS.getInt( KEY_ADAPTIVE_RESUME_NTP, -1 );
	}
}
