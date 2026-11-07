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

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntSupplier;

import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import org.elephant.setting.main.ElephantMainSettings;
import org.elephant.setting.main.ElephantMainSettingsManager;
import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.automahout.tool.RunRange;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.CellScale;
import org.mastodon.mamut.automahout.tool.CleanupToggles;
import org.mastodon.mamut.automahout.tool.ElephantDataSettings;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.TrainingBalance;
import org.mastodon.mamut.automahout.tool.impl.AnalyzeVideoTool;
import org.mastodon.mamut.automahout.tool.impl.AutonomousTrackingTool;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.tool.ImageProfile;
import org.mastodon.mamut.automahout.tool.Modality;
import org.mastodon.mamut.automahout.tool.impl.ElephantTool;
import org.mastodon.mamut.automahout.vision.LlmMessage;
import org.mastodon.mamut.automahout.vision.VlmBackend;
import org.mastodon.mamut.automahout.tool.ExpectedCellCount;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.mamut.views.MamutView;
import org.mastodon.mamut.views.MamutViewI;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.mastodon.model.tag.TagSetStructure;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.model.tag.TagSetStructure.TagSet;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.viewer.ViewerPanel;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import net.imglib2.Dimensions;

/**
 * Menu action that runs {@link AutonomousTrackingTool} in a background thread.
 *
 * <p>Opens (or reuses) a BDV window in group 1, then launches the autonomous
 * tracking agent. Progress is shown in the Automahout log window.</p>
 */
class AutonomousTrackingAction extends AbstractNamedAction
{
	/** Remembers the pre-flight choice of asking about merges and divisions, across sessions. */
	private static final java.util.prefs.Preferences ASK_PREFS =
			org.mastodon.mamut.automahout.tool.AutomahoutPrefs.node( AutonomousTrackingAction.class );

	private static final String KEY_ASK_MERGES_AND_DIVISIONS = "ask_merges_and_divisions";

	private static final String KEY_DETECTOR_TRIAL = "detector_trial_dialog";

	/**
	 * Whether the GUI pre-flight measures the image (intensity profile, cell size) before its dialog opens. The
	 * user's call, remembered: on a large stack read over a network the two cost over a minute, and on an
	 * ultra-sparse movie with a background of exactly 0 neither can be measured. A batch run always measures.
	 */
	private static final String KEY_MEASURE_IMAGE = "preflight_measure_image";

	/** The user's training balance (cells at the weight of a correction), remembered; see CleanupToggles. */
	private static final String KEY_DETECTIONS_AS_CORRECTIONS = "preflight_detections_as_corrections";

	/** The user's "Fit detections to the cell's shape" switch, remembered; ON until they untick it. */
	private static final String KEY_SHAPE_FIT = "preflight_shape_fit";


	private static final Logger log = LoggerFactory.getLogger( AutonomousTrackingAction.class );

	private static final long serialVersionUID = 1L;

	private final ProjectModel projectModel;

	private final AutomahoutLogWindow logWindow;

	private volatile ToolContext currentContext;

	private volatile SwingWorker< ?, ? > currentWorker;

	AutonomousTrackingAction( final ProjectModel projectModel,
			final AutomahoutLogWindow logWindow )
	{
		super( MastodonAutomahoutPlugin.ACTION_AUTONOMOUS_TRACKING_NAME );
		this.projectModel = projectModel;
		this.logWindow = logWindow;
		// Cmd-Q asks first while a run is in progress, pre-flight included: currentContext is set when
		// "Run Automahout" is pressed and cleared on every path out. See QuitGuard.
		QuitGuard.watch( () -> currentContext != null );
	}

	/**
	 * Requests cancellation of any running autonomous tracking.
	 * Safe to call even when nothing is running.
	 */
	void cancel()
	{
		final ToolContext ctx = currentContext;
		if ( ctx != null )
			ctx.requestCancel();
		final SwingWorker< ?, ? > w = currentWorker;
		if ( w != null )
			w.cancel( true );
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		logWindow.show();
		logWindow.enableStop();
		logWindow.onLogMessage( "=== Autonomous tracking: analyzing dataset for pre-flight guidance... ===" );

		final int group1 = 0;
		final Map< String, Object > guiState = new HashMap<>();
		guiState.put( MamutView.GROUP_HANDLE_ID_KEY, group1 );

		MamutViewBdv bdvView = findExistingView( MamutViewBdv.class, group1 );
		if ( bdvView == null )
			bdvView = projectModel.getWindowManager().createBigDataViewer( guiState );
		final ViewerPanel viewer = bdvView.getViewerPanelMamut();

		final ToolContext context = new ToolContext( projectModel, viewer );
		context.setLogCallback( logWindow::onLogMessage );
		// Human-in-the-loop: let the agent consult the user (decision / approval) via a dialog.
		context.setAskCallback( AutonomousTrackingAction::promptUser );
		// Human-in-the-loop: let the user place annotations directly in BDV/TrackScheme instead of
		// describing them in text (e.g. cold-start bootstrap manual annotation).
		context.setAnnotationWaitCallback( AutonomousTrackingAction::waitForAnnotations );
		// Human-in-the-loop: a choice between pictures, for what a person settles by looking.
		context.setImageChoiceCallback( AutonomousTrackingAction::promptImageChoice );
		// Human-in-the-loop: wait while the user fixes something by hand in BDV / TrackScheme.
		context.setEditWaitCallback( AutonomousTrackingAction::waitForHandEdit );
		currentContext = context;

		runPreflightGuidance( context );
	}

	/** Everything the pre-flight background step produces for the review dialog. */
	/**
	 * Intensity readings drawn for the pre-flight {@link ImageProfile}. Enough to support a 99.5th
	 * percentile with room to spare, and cheap: they are read at the shared sampling level, once, on a
	 * dialog the user is about to spend longer reading than this takes.
	 */
	private static final int IMAGE_PROFILE_SAMPLES = 3000;

	private static final class PreflightSuggestion
	{
		/** Non-null only when video.analyze itself failed — callers must abort in that case. */
		String videoAnalyzeError;

		/** Whether the image profile and the cell size were measured ({@link #KEY_MEASURE_IMAGE}). */
		boolean imageMeasured = true;

		String guidanceText = "";

		/** Current ELEPHANT Main Settings patch size, or {@code null} if unreadable. */
		int[] currentPatchSize;

		/** VLM-suggested patch size, or {@code null} if not suggested/parseable — falls back to current. */
		int[] suggestedPatchSize;

		/** VLM-suggested MAXIMUM refine_iterations, or {@code null} if not suggested/parseable. */
		Integer suggestedRefineIterations;

		/** VLM-suggested MAXIMUM completeness_target, or {@code null} if not suggested/parseable. */
		Double suggestedCompletenessTarget;

		/**
		 * Current ELEPHANT Main Settings n_crops, or {@code null} if unreadable. Unlike patch size /
		 * refine caps, this is never VLM-suggested — the user already deliberately tuned it in ELEPHANT's
		 * own settings for their GPU/time budget, so the dialog just offers to enforce that value.
		 */
		Integer currentNCrops;

		/**
		 * What the pixels say about this dataset, measured at pre-flight. Decides the opening state of
		 * the switches below that are properties of the data rather than of the user's taste — see
		 * {@link ImageProfile}. Never {@code null}; an unmeasurable frame yields an undetermined
		 * profile, which leaves every switch where it was before this existed.
		 */
		ImageProfile imageProfile = ImageProfile.of( null );

		/**
		 * The cell radius measured in the image (first, middle and last frame; {@link CellScale}), from which
		 * ELEPHANT's detection radius range and linking distance are set. NaN when it could not be measured.
		 */
		CellScale.Estimate cellScale;
	}

	/** What the user confirmed in the review dialog, or {@code null} if they cancelled. */
	private static final class PreflightResult
	{
		final String guidance;

		final int[] patchSize; // may be null: leave ELEPHANT's patch size setting untouched

		final Integer maxRefineIterations; // may be null: no hard cap, trust the agent's choice

		final Double completenessTargetCap; // may be null: no hard cap, trust the agent's choice

		final Integer nCropsOverride; // may be null: no override, trust whatever the agent requests

		/**
		 * If true, the run starts from a totally clean slate: every spot in the project is deleted, the
		 * persisted adaptive-track resume marker is cleared, and this dataset's auto-learned acceptance
		 * profile is deleted — instead of resuming on top of (possibly corrupted) leftovers from an
		 * earlier interrupted/aborted run.
		 */
		final boolean startFresh;

		/**
		 * Existing Mastodon tag set name whose {@link #protectedTagLabel} tag marks a spot as an
		 * already human-confirmed result — {@code null} means protection is off. Set here (not left to
		 * the agent's own tool-call parameters) so it is enforced regardless of what the agent requests,
		 * the same way {@link #maxRefineIterations}/{@link #nCropsOverride} are.
		 */
		final String protectedTagSet;

		/** Tag label within {@link #protectedTagSet} that marks a spot as protected — see its doc. */
		final String protectedTagLabel;

		/**
		 * User-entered "expected cells per frame" (may be null: no override, trust the dataset
		 * analysis' own visual guess/the agent's request). Deliberately NOT pre-filled from the VLM's
		 * own estimate — the whole point is to let the user correct it when that guess is wrong (e.g.
		 * off by a large factor from a downsampled/pyramid screenshot), not to rubber-stamp it.
		 */
		final Integer expectedCellsPerFrame;

		/**
		 * Cells per frame expected at the LAST timepoint of the run, when the population changes over
		 * it. Equal to {@link #expectedCellsPerFrame} for a steady one.
		 */
		final Integer expectedCellsAtEnd;

		/**
		 * Timepoint to begin at, or {@code null} to decide as before (resume inference, or the agent's
		 * own {@code start_timepoint}).
		 */
		final Integer startTimepoint;

		/**
		 * Whether the flat-contrast background test may delete detections this run ({@code true} =
		 * normal). Together with {@link #resolveDuplicates} this is the diagnostic pair: they are the
		 * two passes that remove the bulk of a window's detections, and the only way to tell which of
		 * them is responsible for an unexplained drop is to run the same window with one of them off.
		 * See {@link CleanupToggles}.
		 */
		final boolean flatContrastFilter;

		/** Whether over-segmentation duplicate deletion may delete this run — see {@link #flatContrastFilter}. */
		final boolean resolveDuplicates;

		/**
		 * Whether the user has said these are FLUORESCENCE images, which switches on removal of
		 * detections whose total integrated brightness is far below their confirmed cells.
		 *
		 * <p>The opposite kind of switch from the two above: those turn a pass off for a diagnostic run,
		 * this turns an extra one on. Total brightness stands for how much labelled material is present
		 * only under fluorescence; under brightfield or phase contrast it means nothing of the sort and
		 * the filter would delete real cells. A checkbox for now — the modality should eventually come
		 * from the image metadata or the pre-flight VLM's reading of the dataset.</p>
		 */
		final boolean fluorescenceDimFilter;

		/**
		 * Whether the warm-up and the per-window tuning loop restrict their predicts to a small
		 * auto-selected region ({@code true} = normal, and much faster).
		 *
		 * <p>Untick it when the tuning phase's numbers stop describing the frame. On a large, sparsely
		 * populated volume the auto-selected region held about five cells, so every count that phase
		 * reported was a count of those five.</p>
		 */
		final boolean cropTuning;

		/**
		 * Whether the flat-contrast check's verdict is audited against a VLM — a DIAGNOSTIC that deletes
		 * nothing, off by default because it has already been run on this project's crops and separated
		 * nothing (77% of flagged called background against 71% of kept), while costing a multi-image
		 * VLM round trip up to three times per window.
		 */
		final boolean flatContrastAudit;

		final boolean vlmCropCheck;

		/**
		 * Whether the automatic split pass may run — OFF by default. See
		 * {@link CleanupToggles#SHARED_SPLIT_MERGED_OVERRIDE}: measured, it split single nuclei far more
		 * often than merged pairs. Ticked only to measure the pass, or for data where it has been shown
		 * to work.
		 */
		final boolean splitMerged;

		/** Whether to balance the taught set across cell types. See {@link TrainingBalance}. */
		final boolean balanceTraining;

		/** Whether to ask about merged spots and divisions while tracking. */
		final boolean askMergesAndDivisions;

		/** Whether to show the pretrained detector trial; false = choose from the image. */
		final boolean detectorTrialDialog;

		/**
		 * The cell radius (µm) to set ELEPHANT's radius range and linking distance from
		 * ({@link ElephantDataSettings}); {@code null} leaves ELEPHANT's settings as they are.
		 */
		final Double elephantCellRadius;

		/** The image type the user stated; {@code null} leaves it to the pixels (a batch run). */
		final Modality modality;

		/** The training balance the user chose (see CleanupToggles); {@code null} leaves it as it was. */
		final Boolean detectionsAsCorrections;

		PreflightResult( final String guidance, final int[] patchSize, final Integer maxRefineIterations,
				final Double completenessTargetCap, final Integer nCropsOverride, final boolean startFresh,
				final String protectedTagSet, final String protectedTagLabel,
				final Integer expectedCellsPerFrame, final Integer expectedCellsAtEnd,
				final Integer startTimepoint, final boolean flatContrastFilter,
				final boolean resolveDuplicates, final boolean fluorescenceDimFilter,
			final boolean cropTuning, final boolean flatContrastAudit, final boolean vlmCropCheck,
			final boolean splitMerged, final boolean balanceTraining, final boolean askMergesAndDivisions,
			final boolean detectorTrialDialog, final Double elephantCellRadius )
		{
			this( guidance, patchSize, maxRefineIterations, completenessTargetCap, nCropsOverride, startFresh,
					protectedTagSet, protectedTagLabel, expectedCellsPerFrame, expectedCellsAtEnd, startTimepoint,
					flatContrastFilter, resolveDuplicates, fluorescenceDimFilter, cropTuning, flatContrastAudit,
					vlmCropCheck, splitMerged, balanceTraining, askMergesAndDivisions, detectorTrialDialog,
					elephantCellRadius, null, null );
		}

		PreflightResult( final String guidance, final int[] patchSize, final Integer maxRefineIterations,
				final Double completenessTargetCap, final Integer nCropsOverride, final boolean startFresh,
				final String protectedTagSet, final String protectedTagLabel,
				final Integer expectedCellsPerFrame, final Integer expectedCellsAtEnd,
				final Integer startTimepoint, final boolean flatContrastFilter,
				final boolean resolveDuplicates, final boolean fluorescenceDimFilter,
			final boolean cropTuning, final boolean flatContrastAudit, final boolean vlmCropCheck,
			final boolean splitMerged, final boolean balanceTraining, final boolean askMergesAndDivisions,
			final boolean detectorTrialDialog, final Double elephantCellRadius, final Modality modality,
			final Boolean detectionsAsCorrections )
		{
			this.modality = modality;
			this.detectionsAsCorrections = detectionsAsCorrections;
			this.guidance = guidance;
			this.patchSize = patchSize;
			this.maxRefineIterations = maxRefineIterations;
			this.completenessTargetCap = completenessTargetCap;
			this.nCropsOverride = nCropsOverride;
			this.startFresh = startFresh;
			this.protectedTagSet = protectedTagSet;
			this.protectedTagLabel = protectedTagLabel;
			this.expectedCellsPerFrame = expectedCellsPerFrame;
			this.expectedCellsAtEnd = expectedCellsAtEnd;
			this.startTimepoint = startTimepoint;
			this.flatContrastFilter = flatContrastFilter;
			this.resolveDuplicates = resolveDuplicates;
			this.fluorescenceDimFilter = fluorescenceDimFilter;
			this.cropTuning = cropTuning;
			this.flatContrastAudit = flatContrastAudit;
			this.vlmCropCheck = vlmCropCheck;
			this.splitMerged = splitMerged;
			this.balanceTraining = balanceTraining;
			this.askMergesAndDivisions = askMergesAndDivisions;
			this.detectorTrialDialog = detectorTrialDialog;
			this.elephantCellRadius = elephantCellRadius;
		}
	}

	/** {@code X,Y,Z} (three positive integers) on its own line, as instructed in the guidance prompt. */
	/**
	 * Ranges the dialog's spinners accept, and therefore the only suggestions worth keeping.
	 *
	 * <p>These existed only inside the {@code SpinnerNumberModel} calls, and the parser kept whatever
	 * the model wrote. {@code SpinnerNumberModel} throws {@code IllegalArgumentException} when the
	 * value is outside its range, on the EDT, inside {@code SwingWorker.done()} — so a model that
	 * answered {@code COMPLETENESS_TARGET: 65} (meaning 65%) or {@code REFINE_ITERATIONS: 25} or a
	 * {@code PATCH_SIZE} containing 0 took the whole dialog out, with nothing logged. Reported as a
	 * freeze after the request had reached the model and been answered, and the reason it started
	 * happening without a code change is that the answer is different every run.</p>
	 */
	private static final int REFINE_ITERATIONS_MIN = 1, REFINE_ITERATIONS_MAX = 20;

	private static final double COMPLETENESS_TARGET_MIN = 0.0, COMPLETENESS_TARGET_MAX = 1.0;

	private static final int PATCH_SIZE_MIN = 1, PATCH_SIZE_MAX = 100_000;

	private static final int N_CROPS_MIN = 1, N_CROPS_MAX = 1000;

	/**
	 * A spinner that cannot throw, whatever the value came from.
	 *
	 * <p>{@code SpinnerNumberModel} throws {@code IllegalArgumentException} when the initial value is
	 * outside its range, and every one of these is built on the EDT inside {@code SwingWorker.done()},
	 * where the throw is swallowed and the dialog simply never appears. Values arrive here from three
	 * places that can all be wrong: the model's suggestions, the ELEPHANT settings, and this dialog's
	 * own defaults. Validating the first of those fixed one third of the problem and the dialog still
	 * would not open.</p>
	 *
	 * <p>So the range is enforced here, once, for every spinner. An out-of-range value is clamped and
	 * named, because a value that had to be clamped is worth knowing about — it is either a setting
	 * that needs fixing or a bug in whatever produced it.</p>
	 */
	static JSpinner boundedSpinner( final ToolContext context, final String what,
			final int value, final int min, final int max, final int step )
	{
		final int clamped = Math.max( min, Math.min( max, value ) );
		if ( clamped != value )
			context.log( String.format( "  %s was %d, outside %d..%d — showing %d instead.",
					what, value, min, max, clamped ) );
		return new JSpinner( new SpinnerNumberModel( clamped, min, max, step ) );
	}

	/** {@link #boundedSpinner(ToolContext, String, int, int, int, int)} for a fractional value. */
	static JSpinner boundedSpinner( final ToolContext context, final String what,
			final double value, final double min, final double max, final double step )
	{
		final double safe = Double.isNaN( value ) ? min : value;
		final double clamped = Math.max( min, Math.min( max, safe ) );
		if ( clamped != value )
			context.log( String.format( "  %s was %s, outside %s..%s — showing %s instead.",
					what, value, min, max, clamped ) );
		return new JSpinner( new SpinnerNumberModel( clamped, min, max, step ) );
	}

	private static int parseOrZero( final String s )
	{
		try
		{
			return Integer.parseInt( s );
		}
		catch ( final NumberFormatException e )
		{
			return 0;
		}
	}

	private static double parseOrNaN( final String s )
	{
		try
		{
			return Double.parseDouble( s );
		}
		catch ( final NumberFormatException e )
		{
			return Double.NaN;
		}
	}

	/** {@code true} if every axis of a suggested patch size is one the dialog can show. */
	static boolean patchSizeInRange( final int[] patch )
	{
		if ( patch == null || patch.length != 3 )
			return false;
		for ( final int v : patch )
		{
			if ( v < PATCH_SIZE_MIN || v > PATCH_SIZE_MAX )
				return false;
		}
		return true;
	}

	static boolean refineIterationsInRange( final int v )
	{
		return v >= REFINE_ITERATIONS_MIN && v <= REFINE_ITERATIONS_MAX;
	}

	static boolean completenessTargetInRange( final double v )
	{
		return v >= COMPLETENESS_TARGET_MIN && v <= COMPLETENESS_TARGET_MAX;
	}

	private static final java.util.regex.Pattern PATCH_SIZE_LINE =
			java.util.regex.Pattern.compile( "PATCH_SIZE:\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)" );

	/** A positive integer on its own line, as instructed in the guidance prompt. */
	private static final java.util.regex.Pattern REFINE_ITERATIONS_LINE =
			java.util.regex.Pattern.compile( "REFINE_ITERATIONS:\\s*(\\d+)" );

	/** A number in [0,1] on its own line, as instructed in the guidance prompt. */
	private static final java.util.regex.Pattern COMPLETENESS_TARGET_LINE =
			java.util.regex.Pattern.compile( "COMPLETENESS_TARGET:\\s*([0-9]*\\.?[0-9]+)" );

	/**
	 * Runs {@code video.analyze} (caching its report so {@link AutonomousTrackingTool} reuses it rather
	 * than re-running) and asks the configured VLM/LLM to suggest (a) dataset-specific guidance for the
	 * tracking agent — e.g. "this dataset's detection collapses under self-training refinement, avoid
	 * refine_pass" — and (b) an ELEPHANT patch size that reduces tiling-boundary detection artifacts,
	 * then shows both in an editable review dialog before the agent actually starts. This surfaces
	 * cautions to the agent from turn 1 instead of leaving the user to discover them reactively, deep
	 * into a run, through {@link #promptUser}/{@code ask_user}.
	 */
	/**
	 * How long the pre-flight guidance call may take before the dialog opens without it.
	 *
	 * <p>The SDK's own ceiling is eight minutes across retries, which is right for a pipeline step
	 * running unattended and wrong for the last thing between the user and a dialog: reported as a
	 * freeze, because nothing was logged and nothing appeared. This guidance is a suggestion the user
	 * reviews and edits anyway, and every field it fills has a current-or-default fallback, so waiting
	 * minutes for it buys nothing.</p>
	 */
	private static final int PREFLIGHT_GUIDANCE_TIMEOUT_SECONDS = 90;

	/**
	 * The guidance reply, or {@code null} if it did not arrive in time.
	 *
	 * <p>Run on its own thread rather than inline, because the AWS call is blocking and does not
	 * answer an interrupt — abandoning the thread is the only way to stop waiting for it.</p>
	 */
	private static String preflightGuidanceReply( final ToolContext context, final VlmBackend backend,
			final String model, final String prompt )
	{
		return preflightGuidanceReply( context, backend, model, prompt,
				PREFLIGHT_GUIDANCE_TIMEOUT_SECONDS );
	}

	/** As above, with the budget given, so the timeout path can be tested without waiting for it. */
	static String preflightGuidanceReply( final ToolContext context, final VlmBackend backend,
			final String model, final String prompt, final int timeoutSeconds )
	{
		context.log( String.format( "  Asking %s for pre-flight guidance (up to %ds; the dialog opens "
				+ "with the current settings if it takes longer)...", model, timeoutSeconds ) );
		final java.util.concurrent.ExecutorService exec =
				java.util.concurrent.Executors.newSingleThreadExecutor( r -> {
					final Thread t = new Thread( r, "preflight-guidance" );
					t.setDaemon( true );
					return t;
				} );
		try
		{
			final java.util.concurrent.Future< String > f = exec.submit(
					() -> backend.chat( model, Collections.singletonList( LlmMessage.user( prompt ) ) ) );
			return f.get( timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS );
		}
		catch ( final java.util.concurrent.TimeoutException te )
		{
			context.log( String.format( "  Pre-flight guidance did not answer within %ds — opening the "
					+ "dialog with the current settings. Nothing is lost: every field it would have "
					+ "filled has a current-or-default value, and you can edit the guidance text "
					+ "yourself. If this repeats, the model endpoint is the thing to look at — the SDK "
					+ "retries throttling and 5xx silently, so a rate limit looks exactly like this.",
					timeoutSeconds ) );
			return null;
		}
		catch ( final InterruptedException ie )
		{
			Thread.currentThread().interrupt();
			return null;
		}
		catch ( final Exception ex )
		{
			// To the log window, not only to the terminal logger. The window was silent through the
			// whole of pre-flight, which is what left a failure here indistinguishable from a freeze.
			final Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			context.log( "  Pre-flight guidance failed (" + cause.getClass().getSimpleName() + ": "
					+ cause.getMessage() + ") — opening the dialog with the current settings." );
			log.warn( "pre-flight guidance call failed", ex );
			return null;
		}
		finally
		{
			exec.shutdownNow();
		}
	}

	private void runPreflightGuidance( final ToolContext context )
	{
		final SwingWorker< PreflightSuggestion, Void > analysisWorker = new SwingWorker< PreflightSuggestion, Void >()
		{
			@Override
			protected PreflightSuggestion doInBackground()
			{
				return analysePreflight( context, ASK_PREFS.getBoolean( KEY_MEASURE_IMAGE, true ) );
			}

			@Override
			protected void done()
			{
				PreflightSuggestion suggestion;
				try
				{
					suggestion = get();
				}
				catch ( final Exception ex )
				{
					suggestion = new PreflightSuggestion();
				}
				if ( suggestion.videoAnalyzeError != null )
				{
					logWindow.onLogMessage(
							"tracking.auto aborted: video.analyze failed — " + suggestion.videoAnalyzeError );
					logWindow.disableStop();
					currentWorker = null;
					currentContext = null;
					return;
				}
				// Anything thrown here happens on the EDT inside done(), where it is swallowed: the
				// dialog simply never appears and the log says nothing, which is indistinguishable from
				// a hang. That is how an out-of-range spinner value read as a freeze.
				PreflightResult confirmed;
				try
				{
					confirmed = showGuidanceDialog( suggestion, context );
				}
				catch ( final RuntimeException | Error ex )
				{
					logWindow.onLogMessage( "=== Autonomous tracking aborted: the pre-flight dialog could "
							+ "not be built (" + ex.getClass().getSimpleName() + ": " + ex.getMessage()
							+ "). This is a bug — please report it with the line above. ===" );
					log.error( "pre-flight dialog failed to build", ex );
					logWindow.disableStop();
					currentWorker = null;
					currentContext = null;
					return;
				}
				if ( confirmed == null )
				{
					logWindow.onLogMessage( "=== Autonomous tracking cancelled before starting. ===" );
					logWindow.disableStop();
					currentWorker = null;
					currentContext = null;
					return;
				}
				final String guidance = applyPreflight( context, confirmed );
				// Remembered for the next dialog: the user's own taste, not a property of this movie.
				ASK_PREFS.putBoolean( KEY_ASK_MERGES_AND_DIVISIONS, confirmed.askMergesAndDivisions );
				ASK_PREFS.putBoolean( KEY_DETECTOR_TRIAL, confirmed.detectorTrialDialog );
				startTracking( context, guidance );
			}
		};
		currentWorker = analysisWorker;
		analysisWorker.execute();
	}

	/** The pre-flight's log line for the measured cell size. */
	static String describeCellScale( final CellScale.Estimate e )
	{
		final boolean coarse = !Double.isNaN( e.coarseVoxel );
		if ( Double.isNaN( e.radius ) )
			return coarse
					? String.format( "could not be measured. The frames are too large to measure at full resolution and "
							+ "were averaged to %.2f µm voxels, where the cells are too small to measure (under about two "
							+ "voxels across); ELEPHANT's radius settings are left as they are", e.coarseVoxel )
					: "could not be measured in the image";
		return ElephantDataSettings.describe( e.radius ) + " (" + e.blobs + " blobs"
				+ ( coarse ? String.format( "; measured on frames averaged to %.2f µm voxels, the full frames being too "
						+ "large to measure", e.coarseVoxel ) : "" ) + ")";
	}

	/**
	 * The pre-flight analysis: the image profile, the VLM's description of the movie, and its suggested guidance,
	 * patch size and refinement caps. Run off the EDT. See {@link #preflightDefaults} for what the dialog opens
	 * with and {@link #applyPreflight} for what a confirmed dialog does.
	 */
	static PreflightSuggestion analysePreflight( final ToolContext context, final boolean measureImage )
	{
		final PreflightSuggestion out = new PreflightSuggestion();
		out.imageMeasured = measureImage;
		if ( !measureImage )
			context.log( "  Image profile and cell size: not measured (switched off in the pre-flight dialog) — every "
					+ "pre-flight switch keeps its previous default, and ELEPHANT's radius settings are left as they are." );
		else
			measureImage( context, out );
		describeMovie( context, out );
		return out;
	}

	/** The image profile and the cell size, into {@code out}. Best-effort: an unreadable source leaves both unset. */
	private static void measureImage( final ToolContext context, final PreflightSuggestion out )
	{
		// Read the dataset's own modality off its pixels, before anything is asked of a model.
		// This is what opens the intensity-based switches below in the right position instead of
		// the same position every time. Best-effort: an unreadable source leaves the profile
		// undetermined, and undetermined means "exactly as it was before this existed".
		try
		{
			out.imageProfile = ImageProfile.of( AutoCorrectOrchestrator.sampleIntensities(
					context, context.requireViewer().state().getCurrentTimepoint(),
					IMAGE_PROFILE_SAMPLES ) );
		}
		catch ( final Exception e )
		{
			log.debug( "pre-flight image profile could not be measured", e );
		}
		context.log( "  " + out.imageProfile.describe() );
		try
		{
			final int tMin = context.getProjectModel().getMinTimepoint(), tMax = context.getProjectModel().getMaxTimepoint();
			// Three whole frames at full resolution, then blob detection in each: on a large stack this is
			// the longest silent stretch of pre-flight, and it read as a freeze.
			context.log( "  Measuring the cell size in the image (first, middle and last frame)..." );
			out.cellScale = ElephantDataSettings.measure(
					org.mastodon.mamut.automahout.tool.remote.DetectorTrial.reader( context ), tMin, tMax );
			context.log( "  Cell size: " + describeCellScale( out.cellScale ) );
		}
		catch ( final Exception e )
		{
			log.debug( "pre-flight cell size could not be measured", e );
		}

	}

	/** The VLM's description of the movie, and its suggested guidance, patch size and refinement caps, into {@code out}. */
	private static void describeMovie( final ToolContext context, final PreflightSuggestion out )
	{
		String videoDescription = ( String ) context.getSharedData( AnalyzeVideoTool.SHARED_DATA_KEY );
		if ( videoDescription == null || videoDescription.trim().isEmpty() )
		{
			// The log window showed nothing at all between the image profile and the dialog, so a
			// slow model call read as a freeze. Every step of pre-flight now says it is running.
			context.log( "  Describing the movie with the vision model (this takes a moment)..." );
			videoDescription = new AnalyzeVideoTool().execute( context, Collections.emptyMap() );
		}
		if ( videoDescription == null || videoDescription.startsWith( "Error:" ) )
		{
			out.videoAnalyzeError = videoDescription;
			return;
		}

		out.currentPatchSize = readCurrentPatchSize();
		out.currentNCrops = readCurrentNCrops();

		try
		{
			final AutomahoutSettings settings = new AutomahoutSettings();
			final VlmBackend backend = AutonomousTrackingTool.buildVlmBackend( settings );
			final String model = settings.getVlmModel();
			final int[] imageSize = readImageSizeVoxels( context );
			final String patchContext = out.currentPatchSize == null || imageSize == null
					? ""
					: String.format( "%nImage size (voxels): %d x %d x %d. Current ELEPHANT patch "
							+ "size (voxels): %d x %d x %d.%n", imageSize[ 0 ], imageSize[ 1 ],
							imageSize[ 2 ], out.currentPatchSize[ 0 ], out.currentPatchSize[ 1 ],
							out.currentPatchSize[ 2 ] );
			final String prompt = "Given this dataset analysis, write SHORT (3-5 sentences), "
					+ "SPECIFIC guidance for an autonomous cell-tracking agent that is about to "
					+ "process this dataset: concrete parameter cautions and known risk patterns "
					+ "particular to THIS data, and anything it should avoid. Be concrete, not "
					+ "generic boilerplate — a human will review and may edit this before the "
					+ "agent starts, so phrase it AS instructions addressed to the agent (e.g. "
					+ "\"Avoid X because...\"), not as a report." + patchContext
					+ ( out.currentPatchSize == null ? "" : "\nAlso judge whether the patch size "
							+ "above is likely to cause tiling-boundary artifacts (a periodic grid "
							+ "of spurious detections aligned with patch edges, most visible in "
							+ "empty/background regions) given the image size, and suggest a patch "
							+ "size that reduces that risk while remaining a plausible GPU memory "
							+ "footprint (prefer multiples of 16; do not suggest exceeding the image "
							+ "size in any axis)." )
					+ "\nAlso judge the agent's self-training refinement loop (interpolate missed "
					+ "cells -> retrain -> re-detect, up to a max round count, chasing a detection-"
					+ "completeness recall target): each round costs several minutes and — if the "
					+ "true achievable completeness for THIS data (e.g. low-SNR, dim, or sparse "
					+ "cells) is far below the generic default target of "
					+ ElephantTool.DEFAULT_COMPLETENESS_TARGET + ", chasing it wastes rounds or risks "
					+ "the detector collapsing under self-training. If THIS dataset looks prone to "
					+ "that, suggest a LOWER max round count and/or a more realistic completeness "
					+ "target; otherwise repeat the defaults (max rounds="
					+ ElephantTool.DEFAULT_REFINE_ITERATIONS + ", target="
					+ ElephantTool.DEFAULT_COMPLETENESS_TARGET + ")."
					+ "\nEnd your response with EXACTLY these three lines (repeat the current/"
					+ "default value on any line you see no reason to change):\n"
					+ "PATCH_SIZE: X,Y,Z (three positive integers)\n"
					+ "REFINE_ITERATIONS: N (one positive integer — max self-training rounds)\n"
					+ "COMPLETENESS_TARGET: T (one number in [0,1] — recall target)"
					+ "\n\n" + videoDescription;
			final String reply = preflightGuidanceReply( context, backend, model, prompt );
			if ( reply == null )
				throw new java.util.concurrent.TimeoutException( "pre-flight guidance timed out" );

			final java.util.regex.Matcher patchM = PATCH_SIZE_LINE.matcher( reply );
			final java.util.regex.Matcher refineM = REFINE_ITERATIONS_LINE.matcher( reply );
			final java.util.regex.Matcher targetM = COMPLETENESS_TARGET_LINE.matcher( reply );
			int cut = reply.length();
			// A suggestion the dialog cannot show is not a suggestion. Dropping it here leaves
			// the field on its current-or-default value, which is what the user sees anyway when
			// the model has no opinion.
			if ( patchM.find() )
			{
				final int[] patch = {
						parseOrZero( patchM.group( 1 ) ), parseOrZero( patchM.group( 2 ) ),
						parseOrZero( patchM.group( 3 ) ) };
				if ( patchSizeInRange( patch ) )
					out.suggestedPatchSize = patch;
				else
					context.log( "  Ignoring the suggested patch size " + Arrays.toString( patch )
							+ " — outside the range this dialog accepts." );
				cut = Math.min( cut, patchM.start() );
			}
			if ( refineM.find() )
			{
				final int refine = parseOrZero( refineM.group( 1 ) );
				if ( refineIterationsInRange( refine ) )
					out.suggestedRefineIterations = refine;
				else
					context.log( "  Ignoring the suggested refine_iterations " + refine
							+ " — outside " + REFINE_ITERATIONS_MIN + ".." + REFINE_ITERATIONS_MAX
							+ "." );
				cut = Math.min( cut, refineM.start() );
			}
			if ( targetM.find() )
			{
				final double target = parseOrNaN( targetM.group( 1 ) );
				if ( completenessTargetInRange( target ) )
					out.suggestedCompletenessTarget = target;
				else
					context.log( "  Ignoring the suggested completeness_target " + target
							+ " — outside " + COMPLETENESS_TARGET_MIN + ".."
							+ COMPLETENESS_TARGET_MAX + "." );
				cut = Math.min( cut, targetM.start() );
			}
			out.guidanceText = reply.substring( 0, cut ).trim();
		}
		catch ( final Exception ex )
		{
			log.warn( "Pre-flight guidance suggestion failed; falling back to a blank suggestion",
					ex );
		}
		return;
	}

	/**
	 * What the pre-flight dialog opens with: the run a user gets by pressing Start without changing anything.
	 * The dialog initialises its controls from this, so a batch run (the benchmark) that takes these values is
	 * the run the dialog would have started. The two remembered switches are the caller's.
	 */
	static PreflightResult preflightDefaults( final PreflightSuggestion suggestion,
			final boolean askMergesAndDivisions, final boolean detectorTrialDialog )
	{
		final boolean patchAvailable = suggestion.currentPatchSize != null;
		final boolean suggestedDiffers = patchAvailable && suggestion.suggestedPatchSize != null
				&& ( suggestion.suggestedPatchSize[ 0 ] != suggestion.currentPatchSize[ 0 ]
						|| suggestion.suggestedPatchSize[ 1 ] != suggestion.currentPatchSize[ 1 ]
						|| suggestion.suggestedPatchSize[ 2 ] != suggestion.currentPatchSize[ 2 ] );
		final boolean refineSuggested = suggestion.suggestedRefineIterations != null
				&& suggestion.suggestedRefineIterations != ElephantTool.DEFAULT_REFINE_ITERATIONS;
		final boolean targetSuggested = suggestion.suggestedCompletenessTarget != null
				&& suggestion.suggestedCompletenessTarget != ElephantTool.DEFAULT_COMPLETENESS_TARGET;
		final boolean capsApplied = refineSuggested || targetSuggested;
		final ImageProfile profile = suggestion.imageProfile;
		return new PreflightResult( suggestion.guidanceText,
				suggestedDiffers ? suggestion.suggestedPatchSize.clone() : null,
				capsApplied ? ( suggestion.suggestedRefineIterations != null ? suggestion.suggestedRefineIterations
						: ElephantTool.DEFAULT_REFINE_ITERATIONS ) : null,
				capsApplied ? ( suggestion.suggestedCompletenessTarget != null ? suggestion.suggestedCompletenessTarget
						: ElephantTool.DEFAULT_COMPLETENESS_TARGET ) : null,
				// n_crops: enforced by default -- the user tuned it in ELEPHANT's own settings.
				suggestion.currentNCrops,
				false, // start fresh: destructive, off
				null, null, // no protected tag
				null, null, // expected cell count: blank, never pre-filled from the VLM's guess
				null, // start timepoint: decided as usual
				profile.flatContrastDeletionApplies(),
				false, // merge overlapping detections: off
				profile.dimSignalFilterApplies(),
				true, // crop-restricted tuning
				false, // flat-contrast VLM audit: diagnostic, off
				profile.vlmCropCheckApplies(),
				false, // split merged detections: off
				true, // balance training
				askMergesAndDivisions, detectorTrialDialog,
				// ELEPHANT's radius range and linking distance from the measured cell size, when measured
				suggestion.cellScale != null && !Double.isNaN( suggestion.cellScale.radius ) ? suggestion.cellScale.radius
						: null );
	}

	/**
	 * The pre-flight of a batch run (the benchmark): analysed as for the dialog, then exactly what the dialog
	 * opens with, except the switches given here. Returns the guidance to start {@link AutonomousTrackingTool}
	 * with; throws when the movie could not be described (the dialog aborts the run then too).
	 */
	static String batchPreflight( final ToolContext context, final boolean startFresh,
			final boolean askMergesAndDivisions, final boolean detectorTrialDialog )
	{
		final PreflightSuggestion suggestion = analysePreflight( context, true );
		if ( suggestion.videoAnalyzeError != null )
			throw new IllegalStateException( "video.analyze failed: " + suggestion.videoAnalyzeError );
		final PreflightResult d = preflightDefaults( suggestion, askMergesAndDivisions, detectorTrialDialog );
		return applyPreflight( context, new PreflightResult( d.guidance, d.patchSize, d.maxRefineIterations,
				d.completenessTargetCap, d.nCropsOverride, startFresh, d.protectedTagSet, d.protectedTagLabel,
				d.expectedCellsPerFrame, d.expectedCellsAtEnd, d.startTimepoint, d.flatContrastFilter,
				d.resolveDuplicates, d.fluorescenceDimFilter, d.cropTuning, d.flatContrastAudit, d.vlmCropCheck,
				d.splitMerged, d.balanceTraining, d.askMergesAndDivisions, d.detectorTrialDialog,
				d.elephantCellRadius ) );
	}

	/**
	 * What a confirmed pre-flight does to the run: the protected tag, the stated cell count, start timepoint,
	 * fresh start, patch size, caps, cleanup switches, questions and detector choice. Returns the guidance the
	 * agent starts with (the confirmed text plus the enforced settings it must know about).
	 */
	static String applyPreflight( final ToolContext context, final PreflightResult confirmed )
	{
		// The image type first: every step after this that asks whether brightness means signal gets the answer.
		if ( confirmed.modality != null )
		{
			context.setSharedData( ImageProfile.SHARED_STATED_MODALITY, confirmed.modality );
			context.setSharedData( ImageProfile.SHARED_KEY, null );
			context.log( "  " + ImageProfile.of( null ).withStatedModality( confirmed.modality ).describe() );
		}
		if ( confirmed.detectionsAsCorrections != null )
		{
			context.setSharedData( CleanupToggles.SHARED_DETECTIONS_AS_CORRECTIONS, confirmed.detectionsAsCorrections );
			context.log( confirmed.detectionsAsCorrections
					? "  Training balance (pre-flight): cells are taught at the weight of a correction (10x)."
					: "  Training balance (pre-flight): cells are taught at 1x, corrections and background negatives at 10x." );
		}
		// Protected-tag config is set FIRST, before startFresh runs — startFresh's own bulk
		// delete must see it too (a "start completely fresh" reset should still spare spots the
		// user explicitly marked as an already-finished, trusted result; those two intents are
		// independent, not mutually exclusive).
		String guidance = confirmed.guidance;
		if ( confirmed.protectedTagSet != null && confirmed.protectedTagLabel != null )
		{
			context.setSharedData( ElephantTool.SHARED_PROTECTED_TAG_CONFIG,
					ProtectedSpots.Config.of( confirmed.protectedTagSet, confirmed.protectedTagLabel ) );
			guidance += String.format( "%n(Enforced regardless of what you request: spots tagged "
					+ "%s > %s are PROTECTED — never deleted/merged/refit/duplicated, used only as "
					+ "trusted tp training signal.)", confirmed.protectedTagSet, confirmed.protectedTagLabel );
		}
		if ( confirmed.expectedCellsPerFrame != null )
		{
			// 2x margin (same rule of thumb AnalyzeVideoTool's own guidance already recommends):
			// the ceiling exists to catch genuine floods, not to fire on ordinary per-frame count
			// fluctuation (divisions, cells entering/leaving the field of view) around a known-good
			// true count.
			// Against the LARGER end when the population changes over the run: a ceiling built on
			// the first timepoint's sixteen cells would read a thousand real cells as a flood.
			final int peak = confirmed.expectedCellsAtEnd == null
					? confirmed.expectedCellsPerFrame
					: Math.max( confirmed.expectedCellsPerFrame, confirmed.expectedCellsAtEnd );
			final double ceiling = ExpectedCellCount.statedCeiling( peak );
			context.setSharedData( ElephantTool.SHARED_COLD_START_CEILING_OVERRIDE, ceiling );
			// The raw count as well, unmultiplied: a ceiling flags explosions, the stated count
			// protects against over-deletion, and only the latter can serve as a floor. See
			// ElephantTool.SHARED_EXPECTED_CELLS_PER_FRAME.
			context.setSharedData( ElephantTool.SHARED_EXPECTED_CELLS_PER_FRAME,
					confirmed.expectedCellsPerFrame );
			if ( confirmed.expectedCellsAtEnd != null
					&& !confirmed.expectedCellsAtEnd.equals( confirmed.expectedCellsPerFrame ) )
				context.setSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_AT_END,
						confirmed.expectedCellsAtEnd );
			guidance += confirmed.expectedCellsAtEnd == null
					|| confirmed.expectedCellsAtEnd.equals( confirmed.expectedCellsPerFrame )
							? String.format( "%n(Enforced regardless of what you request: the user "
									+ "knows this dataset has ~%d cell(s)/frame — IGNORE any different "
									+ "cell-count guess elsewhere in this guidance, and treat "
									+ "cold_start_spots_per_tp as fixed at %.0f.)",
									confirmed.expectedCellsPerFrame, ceiling )
							: String.format( "%n(Enforced regardless of what you request: the user "
									+ "knows this specimen GROWS across the run, from ~%d cell(s)/frame "
									+ "at its first timepoint to ~%d at its last — IGNORE any single "
									+ "cell-count guess elsewhere in this guidance, do NOT read a low "
									+ "early count as a detection failure, and treat "
									+ "cold_start_spots_per_tp as fixed at %.0f.)",
									confirmed.expectedCellsPerFrame, confirmed.expectedCellsAtEnd,
									ceiling );
		}

		if ( confirmed.startTimepoint != null )
			context.setSharedData( ElephantTool.SHARED_START_TIMEPOINT_OVERRIDE,
					confirmed.startTimepoint );
		if ( confirmed.startFresh )
			startFresh( context );

		if ( confirmed.patchSize != null )
			applyPatchSize( confirmed.patchSize );
		if ( confirmed.elephantCellRadius != null )
		{
			context.setSharedData( CellScale.SHARED_RADIUS_UM, confirmed.elephantCellRadius );
			try
			{
				context.log( "  ELEPHANT settings from the data: " + ElephantDataSettings.apply(
						ElephantMainSettingsManager.getInstance().getSelectedStyle(), confirmed.elephantCellRadius ) );
			}
			catch ( final Exception e )
			{
				log.warn( "Failed to set ELEPHANT's radius range from the measured cell size", e );
			}
		}

		if ( confirmed.maxRefineIterations != null )
		{
			context.setSharedData( ElephantTool.SHARED_MAX_REFINE_ITERATIONS,
					confirmed.maxRefineIterations );
			guidance += String.format( "%n(Enforced regardless of what you request: refine_iterations "
					+ "will be capped at %d.)", confirmed.maxRefineIterations );
		}
		if ( confirmed.completenessTargetCap != null )
		{
			context.setSharedData( ElephantTool.SHARED_COMPLETENESS_TARGET_CAP,
					confirmed.completenessTargetCap );
			guidance += String.format( "%n(Enforced regardless of what you request: "
					+ "completeness_target will be capped at %.2f.)", confirmed.completenessTargetCap );
		}
		// Enforced regardless of the agent's own parameters, like the caps above — but NOT mentioned
		// in the guidance text: the agent must not learn that a precision guard can be switched
		// off, since every metric it judges itself on improves when one is. See CleanupToggles.
		CleanupToggles.setPreflightOverrides( context, confirmed.flatContrastFilter,
				confirmed.resolveDuplicates, confirmed.fluorescenceDimFilter,
				confirmed.cropTuning, confirmed.flatContrastAudit, confirmed.vlmCropCheck,
				confirmed.splitMerged );
		context.setSharedData( TrainingBalance.SHARED_ENABLED, confirmed.balanceTraining );
		AutoCorrectOrchestrator.askAboutMergesAndDivisions( context, confirmed.askMergesAndDivisions );
		context.setSharedData( org.mastodon.mamut.automahout.tool.remote.DetectorTrial.SHARED_AUTOMATIC,
				!confirmed.detectorTrialDialog );
		if ( !confirmed.askMergesAndDivisions )
			context.log( "  Questions about merged spots and divisions are off for this run (pre-flight "
					+ "dialog): two tracks running into one detection are split, divisions whose daughters "
					+ "do not move apart are cut, without asking." );
		if ( confirmed.nCropsOverride != null )
		{
			context.setSharedData( ElephantTool.SHARED_N_CROPS_OVERRIDE, confirmed.nCropsOverride );
			guidance += String.format( "%n(Enforced regardless of what you request: "
					+ "n_crops will be fixed at %d.)", confirmed.nCropsOverride );
		}
		return guidance;
	}

	/** Current ELEPHANT Main Settings n_crops, or {@code null} if unreadable. */
	private static Integer readCurrentNCrops()
	{
		try
		{
			return ElephantMainSettingsManager.getInstance().getSelectedStyle().getNumCrops();
		}
		catch ( final Exception e )
		{
			return null;
		}
	}

	/** Current ELEPHANT Main Settings patch size {@code [x, y, z]}, or {@code null} if unreadable. */
	private static int[] readCurrentPatchSize()
	{
		try
		{
			final ElephantMainSettings s = ElephantMainSettingsManager.getInstance().getSelectedStyle();
			return new int[] { s.getPatchSizeX(), s.getPatchSizeY(), s.getPatchSizeZ() };
		}
		catch ( final Exception e )
		{
			return null;
		}
	}

	/** Image size in voxels {@code [x, y, z]} for the first view setup, or {@code null} if unreadable. */
	private static int[] readImageSizeVoxels( final ToolContext context )
	{
		try
		{
			final AbstractSpimData< ? > spimData = context.getProjectModel().getSharedBdvData().getSpimData();
			final BasicViewSetup setup =
					( BasicViewSetup ) spimData.getSequenceDescription().getViewSetupsOrdered().get( 0 );
			final Dimensions dims = setup.getSize();
			return new int[] { ( int ) dims.dimension( 0 ), ( int ) dims.dimension( 1 ),
					( int ) dims.dimension( 2 ) };
		}
		catch ( final Exception e )
		{
			return null;
		}
	}

	/** Writes {@code [x, y, z]} into the current ELEPHANT Main Settings patch size (in-memory for this session). */
	private static void applyPatchSize( final int[] xyz )
	{
		try
		{
			final ElephantMainSettings s = ElephantMainSettingsManager.getInstance().getSelectedStyle();
			s.setPatchSizeX( xyz[ 0 ] );
			s.setPatchSizeY( xyz[ 1 ] );
			s.setPatchSizeZ( xyz[ 2 ] );
			log.info( "Applied ELEPHANT patch size {}x{}x{} from pre-flight review", xyz[ 0 ], xyz[ 1 ],
					xyz[ 2 ] );
		}
		catch ( final Exception e )
		{
			log.warn( "Failed to apply suggested patch size", e );
		}
	}

	/**
	 * "Start completely fresh": deletes every spot in the project, clears the persisted adaptive-track
	 * resume marker ({@link AutomahoutSettings#clearAdaptiveResume()}), and deletes this dataset's
	 * auto-learned acceptance profile file — so the run below starts from a genuine clean slate instead
	 * of resuming on top of leftover state from a previous interrupted/aborted/corrupted run. Does NOT
	 * touch the ELEPHANT model checkpoint itself (that lives on the ELEPHANT server, outside this
	 * plugin's reach) — if the model's weights are themselves corrupted, that still needs a manual reset
	 * server-side.
	 */
	private static void startFresh( final ToolContext context )
	{
		final ModelGraph graph = context.getGraph();
		// "Start completely fresh" and "protect this tag" are independent, not mutually exclusive: a
		// spot the user has explicitly marked as an already-finished, trusted result must survive even
		// a full reset — only the caller's ordering (this config is set before startFresh runs) makes
		// this visible here.
		final ProtectedSpots.Config protectedConfig =
				( ProtectedSpots.Config ) context.getSharedData( ElephantTool.SHARED_PROTECTED_TAG_CONFIG );
		final Object startOverride = context.getSharedData( ElephantTool.SHARED_START_TIMEPOINT_OVERRIDE );
		final int freshFrom = startOverride instanceof Number
				? ( ( Number ) startOverride ).intValue() : 0;
		final List< Spot > toDelete = new ArrayList<>();
		int protectedSkipped = 0;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				// Fresh FROM the chosen start, not fresh over the whole movie. Ticking both boxes
				// otherwise erases the part the user just said was already finished.
				if ( spot.getTimepoint() < freshFrom )
					continue;
				if ( ProtectedSpots.isProtected( context.getModel(), spot, protectedConfig ) )
				{
					protectedSkipped++;
					continue;
				}
				final Spot ref = graph.vertexRef();
				ref.refTo( spot );
				toDelete.add( ref );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		int removed = 0;
		if ( !toDelete.isEmpty() )
		{
			graph.getLock().writeLock().lock();
			try
			{
				for ( final Spot spot : toDelete )
				{
					GraphEdits.removeSpot( context, graph, spot, GraphEdits.Reason.BULK );
					removed++;
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			context.getModel().setUndoPoint();
			context.notifyGraphChanged();
		}

		AutomahoutSettings.clearAdaptiveResume();
		// And say so for the rest of the session. Clearing the marker is not enough on its own: the
		// resume path also infers "already done" from spots being present, and a fresh start leaves the
		// user's confirmed spots behind by design — 88 of them at t=0..1 made a run that had just been
		// told to start fresh begin at t=2 instead.
		context.setSharedData( ElephantTool.SHARED_START_FRESH_REQUESTED, Boolean.TRUE );

		String profileNote = "";
		try
		{
			final String datasetName =
					ElephantMainSettingsManager.getInstance().getSelectedStyle().getDatasetName();
			final int deletedProfiles = ElephantTool.deleteAcceptanceProfiles( datasetName );
			if ( deletedProfiles > 0 )
				profileNote = ", deleted " + deletedProfiles + " auto-learned acceptance profile(s)";
		}
		catch ( final Exception e )
		{
			log.warn( "startFresh: could not delete the acceptance profile file", e );
		}

		context.log( String.format(
				"=== Starting fresh%s: deleted %d spot(s), cleared the saved resume position%s%s. ===",
				freshFrom > 0 ? " from t=" + freshFrom + " (earlier timepoints left alone)" : "",
				removed, profileNote,
				protectedSkipped > 0 ? String.format( ", spared %d protected spot(s)", protectedSkipped ) : "" ) );
	}

	/** Starts the actual tracking agent with the user-confirmed pre-flight {@code guidance} (may be empty). */
	private void startTracking( final ToolContext context, final String guidance )
	{
		logWindow.onLogMessage( "=== Autonomous tracking started. ===" );
		final Map< String, Object > params = new HashMap<>();
		if ( !guidance.trim().isEmpty() )
			params.put( AutonomousTrackingTool.PARAM_USER_GUIDANCE, guidance.trim() );

		final SwingWorker< String, Void > worker = new SwingWorker< String, Void >()
		{
			@Override
			protected String doInBackground()
			{
				return new AutonomousTrackingTool().execute( context, params );
			}

			@Override
			protected void done()
			{
				logWindow.disableStop();
				currentWorker = null;
				currentContext = null;
				try
				{
					final String result = get();
					if ( result != null && result.startsWith( "Error:" ) )
					{
						logWindow.onLogMessage( result );
					}
					else if ( isCancelled() )
					{
						logWindow.onLogMessage( "=== Autonomous tracking stopped by user. ===" );
					}
					else
					{
						logWindow.onLogMessage( result != null ? result : "" );
						logWindow.onLogMessage( "=== Autonomous tracking complete. ===" );
					}
				}
				catch ( final java.util.concurrent.CancellationException ex )
				{
					// What Stop does: get() on a cancelled worker throws this, with no message, before the
					// isCancelled() branch above is reached -- it was logged as "Autonomous tracking error: null".
					logWindow.onLogMessage( "=== Autonomous tracking stopped by user. ===" );
				}
				catch ( final Exception ex )
				{
					logWindow.onLogMessage(
							"Autonomous tracking error: " + ex.getMessage() );
				}
			}
		};
		currentWorker = worker;
		worker.execute();
	}

	/** Size for the pre-flight guidance review/edit dialog. */
	private static final Dimension GUIDANCE_AREA_SIZE = new Dimension( 800, 400 );

	/** Shrinks a packed window to the screen's usable area; its scroll panes take up the difference. */
	static void fitToScreen( final java.awt.Window w )
	{
		final java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		w.setSize( Math.min( w.getWidth(), screen.width ), Math.min( w.getHeight(), screen.height ) );
	}

	/**
	 * Shows an editable review dialog pre-filled with {@code suggestion}'s guidance text and (when
	 * available) a suggested ELEPHANT patch size. MODAL and driven by plain button
	 * {@code ActionListener}s rather than {@link JOptionPane}'s value-property mechanism — deliberately,
	 * unlike {@link #promptUser}: this dialog runs before the agent has started (no live BDV/TrackScheme
	 * state to keep watching), so blocking is fine, and a direct button-click handler sidesteps the
	 * listener-ordering pitfall documented in {@link #promptUser} entirely.
	 *
	 * @return the confirmed guidance/patch-size choice, or {@code null} if the user cancelled
	 */
	private static PreflightResult showGuidanceDialog( final PreflightSuggestion suggestion,
			final ToolContext context )
	{
		// SwingWorker#done() (our only current caller) always runs ON the EDT, unlike promptUser's
		// caller (the LlmOrchestrator background thread) — so, unlike promptUser, we must support being
		// invoked directly on the EDT: a modal dialog's setVisible(true) blocks the EDT's own nested
		// event loop until dismissed, which is exactly what we want and needs no invokeLater/latch at
		// all in that case. The invokeLater/latch path below exists only for a hypothetical non-EDT
		// caller.
		if ( SwingUtilities.isEventDispatchThread() )
			return buildAndShowGuidanceDialog( suggestion, context );

		final PreflightResult[] result = new PreflightResult[ 1 ];
		final CountDownLatch latch = new CountDownLatch( 1 );
		final Runnable show = () -> {
			result[ 0 ] = buildAndShowGuidanceDialog( suggestion, context );
			latch.countDown();
		};
		try
		{
			SwingUtilities.invokeLater( show );
			latch.await();
		}
		catch ( final Exception e )
		{
			return null;
		}
		return result[ 0 ];
	}

	/** Builds and shows the modal guidance dialog. Must be called ON the EDT; blocks it until dismissed. */
	private static PreflightResult buildAndShowGuidanceDialog( final PreflightSuggestion suggestion,
			final ToolContext context )
	{
		final PreflightResult[] result = new PreflightResult[ 1 ];
		// Every control opens on preflightDefaults, which is also what a batch run takes.
		final PreflightResult d = preflightDefaults( suggestion,
				ASK_PREFS.getBoolean( KEY_ASK_MERGES_AND_DIVISIONS, true ), ASK_PREFS.getBoolean( KEY_DETECTOR_TRIAL, true ) );
		final JTextArea textArea = new JTextArea( d.guidance );
		textArea.setLineWrap( true );
		textArea.setWrapStyleWord( true );
		textArea.setCaretPosition( 0 );
		final JScrollPane scrollPane = new JScrollPane( textArea );
		scrollPane.setPreferredSize( GUIDANCE_AREA_SIZE );


		// Start-fresh checkbox: OFF by default (destructive — deletes every spot in the project), so a
		// normal resume-or-continue run behaves exactly as before unless the user explicitly opts in.
		// Added after repeated reports of a project accumulating "weird" leftover state (spots + resume
		// marker + stale acceptance profile) from a previous interrupted/aborted/corrupted run that then
		// silently carries into the next one.
		final JCheckBox startFreshCheck = new JCheckBox(
				"<html><b>Start completely fresh</b> — delete ALL spots in this project, clear the saved "
						+ "resume position, and discard this dataset's auto-learned acceptance profile "
						+ "before starting (use this if the previous run's leftover state looks wrong)</html>",
				d.startFresh );

		// Patch size row — only shown when the current ELEPHANT setting was readable at all.
		final boolean patchAvailable = suggestion.currentPatchSize != null;
		final int[] initial = suggestion.suggestedPatchSize != null
				? suggestion.suggestedPatchSize : suggestion.currentPatchSize;
		final JSpinner spinX = boundedSpinner( context, "ELEPHANT patch size X",
				patchAvailable ? initial[ 0 ] : PATCH_SIZE_MIN, PATCH_SIZE_MIN, PATCH_SIZE_MAX, 1 );
		final JSpinner spinY = boundedSpinner( context, "ELEPHANT patch size Y",
				patchAvailable ? initial[ 1 ] : PATCH_SIZE_MIN, PATCH_SIZE_MIN, PATCH_SIZE_MAX, 1 );
		final JSpinner spinZ = boundedSpinner( context, "ELEPHANT patch size Z",
				patchAvailable ? initial[ 2 ] : PATCH_SIZE_MIN, PATCH_SIZE_MIN, PATCH_SIZE_MAX, 1 );
		final JCheckBox applyPatchCheck = new JCheckBox( "Apply this ELEPHANT patch size before starting",
				d.patchSize != null );
		final boolean cellScaleAvailable = suggestion.cellScale != null && !Double.isNaN( suggestion.cellScale.radius );
		final JCheckBox cellScaleCheck = new JCheckBox( "<html>Set ELEPHANT's detection radius range and linking distance "
				+ "from the cell size measured in the image" + ( cellScaleAvailable
						? " (" + ElephantDataSettings.describe( suggestion.cellScale.radius ) + ")" : "" ) + "</html>",
				d.elephantCellRadius != null );

		// Refinement-loop caps: always offered (no "readable setting" precondition — these are plain
		// tool-call parameters, not a persistent ELEPHANT setting), pre-filled with the VLM's suggestion
		// or ElephantTool's own defaults, and OFF by default so a run with no strong opinion behaves
		// exactly as before (agent picks freely).
		final int refineInitial = suggestion.suggestedRefineIterations != null
				? suggestion.suggestedRefineIterations : ElephantTool.DEFAULT_REFINE_ITERATIONS;
		final double targetInitial = suggestion.suggestedCompletenessTarget != null
				? suggestion.suggestedCompletenessTarget : ElephantTool.DEFAULT_COMPLETENESS_TARGET;
		final JSpinner spinRefineIterations = boundedSpinner( context, "refine_iterations",
				refineInitial, REFINE_ITERATIONS_MIN, REFINE_ITERATIONS_MAX, 1 );
		final JSpinner spinCompletenessTarget = boundedSpinner( context, "completeness_target",
				targetInitial, COMPLETENESS_TARGET_MIN, COMPLETENESS_TARGET_MAX, 0.05 );
		final JCheckBox applyRefineCapsCheck = new JCheckBox(
				"Cap the agent's refinement rounds/target at these values (enforced regardless of what "
						+ "the agent requests)",
				d.maxRefineIterations != null );

		// n_crops — only shown when ELEPHANT Main Settings was readable. Unlike the caps above, this
		// checkbox defaults to CHECKED: the user already deliberately tuned n_crops in ELEPHANT's own
		// settings (GPU/time budget), and the agent's tool calls were observed to always specify n_crops
		// explicitly — silently overriding that choice every run — so enforcing it is the point, not an
		// opt-in extra.
		final boolean nCropsAvailable = suggestion.currentNCrops != null;
		final JSpinner spinNCrops = boundedSpinner( context, "ELEPHANT n_crops",
				nCropsAvailable ? suggestion.currentNCrops : N_CROPS_MIN, N_CROPS_MIN, N_CROPS_MAX, 1 );
		final JCheckBox applyNCropsCheck = new JCheckBox(
				"Enforce this n_crops value (the agent's own choice is overridden regardless)",
				!nCropsAvailable || d.nCropsOverride != null );

		// Protected-tag selection: existing tag sets/tags in this project are offered for convenience
		// (editable combo — a tag set/label that doesn't exist yet can still be typed, since the user
		// may create/apply it after this dialog closes but before/while the agent runs). Empty selection
		// = protection off, the default: this is an opt-in safeguard for already-curated results, not a
		// requirement for a normal run.
		final TagSetStructure protectedTagStructure = context.getModel().getTagSetModel().getTagSetStructure();
		final JComboBox< String > protectedTagSetCombo = new JComboBox<>();
		protectedTagSetCombo.setEditable( true );
		protectedTagSetCombo.addItem( "" );
		for ( final TagSet ts : protectedTagStructure.getTagSets() )
			protectedTagSetCombo.addItem( ts.getName() );
		final JComboBox< String > protectedTagLabelCombo = new JComboBox<>();
		protectedTagLabelCombo.setEditable( true );
		protectedTagLabelCombo.addItem( "" );
		final Runnable refreshProtectedTagLabels = () -> {
			protectedTagLabelCombo.removeAllItems();
			protectedTagLabelCombo.addItem( "" );
			final Object selectedSet = protectedTagSetCombo.getSelectedItem();
			if ( selectedSet != null )
				for ( final TagSet ts : protectedTagStructure.getTagSets() )
					if ( ts.getName().equals( selectedSet ) )
					{
						for ( final Tag t : ts.getTags() )
							protectedTagLabelCombo.addItem( t.label() );
						break;
					}
		};
		protectedTagSetCombo.addActionListener( ev -> refreshProtectedTagLabels.run() );

		// Expected cell count: deliberately left BLANK by default, not pre-filled from the dataset
		// analysis' own VLM guess — that guess (from a downsampled/pyramid screenshot) can be off by a
		// large factor, and every cold-start/flood decision in adaptive_track is calibrated against it
		// via cold_start_spots_per_tp. When the user knows the real count, this overrides that guess
		// outright rather than leaving the agent to keep reasoning from a wrong number.
		final JCheckBox applyExpectedCellsCheck = new JCheckBox(
				"Override the expected cell count (enforced regardless of what the agent requests):",
				d.expectedCellsPerFrame != null );
		final JSpinner spinExpectedCells = boundedSpinner( context, "expected cells at the first "
				+ "timepoint", 50, 1, 1_000_000, 1 );
		// A second number, because one cannot describe a developing specimen. A mouse embryo run from
		// t=160 to t=499 holds about sixteen cells at the start and close to a thousand at the end; a
		// user asked for "expected cells per frame" reasonably enters the thousand, and the floor is
		// then sixty times the truth at the start and refuses every deletion. Entering sixteen is no
		// better — the growth the pipeline will infer on its own is bounded, so the floor could never
		// follow the population up to a thousand. Left at the same value as the first, everything
		// behaves as it did with one number.
		final JSpinner spinExpectedCellsEnd = boundedSpinner( context, "expected cells at the last "
				+ "timepoint", 50, 1, 1_000_000, 1 );

		// Where to begin, stated rather than inferred. The resume path reads how far a previous pass got
		// from which timepoints hold spots, and the agent passes start_timepoint when it happens to read
		// that out of the guidance text — neither is worth relying on when the user simply knows.
		final JCheckBox applyStartTpCheck = new JCheckBox(
				"Start from timepoint (enforced; overrides resume and the agent's own request):",
				d.startTimepoint != null );
		final JSpinner spinStartTp = boundedSpinner( context, "start timepoint", 0, 0, 1_000_000, 1 );

		// The timepoints this run tracks and corrects (RunRange). Stated per run and never remembered: kept in the
		// user's preferences, a range chosen for one movie carried over to the next project and quietly tracked
		// only part of it. Opens on the whole movie.
		final int lastTp = lastTimepoint( context );
		final JSpinner spinRangeStart = boundedSpinner( context, "first timepoint of the range", 0, 0, lastTp, 1 );
		final JSpinner spinRangeEnd = boundedSpinner( context, "last timepoint of the range", lastTp, 0, lastTp, 1 );
		final JCheckBox rangeToLastCheck = new JCheckBox( "the last", true );
		spinRangeEnd.setEnabled( false );
		rangeToLastCheck.addActionListener( ev -> spinRangeEnd.setEnabled( !rangeToLastCheck.isSelected() ) );
		spinRangeStart.addChangeListener( ev -> {
			if ( ( Integer ) spinRangeEnd.getValue() < ( Integer ) spinRangeStart.getValue() )
				spinRangeEnd.setValue( spinRangeStart.getValue() );
		} );
		spinRangeEnd.addChangeListener( ev -> {
			if ( ( Integer ) spinRangeEnd.getValue() < ( Integer ) spinRangeStart.getValue() )
				spinRangeStart.setValue( spinRangeEnd.getValue() );
		} );

		// Diagnostic cleanup switches — both CHECKED by default, i.e. the normal pipeline. They are here
		// rather than left to the agent's parameters because they are an experiment control: the user
		// turns one off, re-runs the same window, and compares the two logs. See CleanupToggles.
		// These four used to open the same way whatever was being tracked. Three of them are not matters
		// of taste: whether brightness means signal decides whether an intensity-based deletion can work
		// at all, and whether a crop can be judged by measuring it. That is a property of the pixels, so
		// it is read off the pixels (see ImageProfile) and the user overrides it here if the reading is
		// wrong. An unmeasurable frame leaves all of them exactly where they were before.
		final JCheckBox flatContrastCheck = new JCheckBox(
				"Flat-contrast deletion (removes detections with no local contrast peak)",
				d.flatContrastFilter );
		// Off by default: the detector has already decided where one cell ends, and every merge pass
		// overrules that from geometry alone — which in a dense field is also true of two real
		// neighbours. Observed on Fluo-N2DL-HeLa: two clearly separated nuclei, each detected, replaced
		// by one ellipse drawn around both and the dark gap between them.
		final JCheckBox resolveDuplicatesCheck = new JCheckBox(
				"Merge overlapping detections into one (OFF by default — the detector already separated "
						+ "them; tick only if it tiles one nucleus with several spots)", d.resolveDuplicates );
		// Off by default, the mirror of merging: measured on window 1 of Fluo-N2DL-HeLa, it split 11
		// single nuclei against at most 2 right splits, and every product is taught as a positive.
		final JCheckBox splitMergedCheck = new JCheckBox(
				"Split one detection into one per cell (OFF by default — it split single nuclei far more "
						+ "often than merged pairs; tick only to measure it)", d.splitMerged );
		// The user's experiment, on by default so the next run tests it; untick for the comparison run.
		final JCheckBox balanceTrainingCheck = new JCheckBox(
				"Balance training across cell types (experiment: thins the most typical cells from what is "
						+ "taught so dim, small and large ones count for more)", d.balanceTraining );
		// The user's call, and remembered: the questions are worth it on a movie being checked closely and a
		// nuisance on one that is not.
		final JCheckBox askMergesAndDivisionsCheck = new JCheckBox(
				"Ask me about merged spots and divisions while tracking (the \"which is right?\" dialog; untick to "
						+ "let the run decide them)", d.askMergesAndDivisions );
		final JCheckBox shapeFitCheck = new JCheckBox(
				"Fit detections to the cell's shape after each inference (in-plane ellipse; on a stack, a 3-D "
						+ "ellipsoid with Mastodon's ellipsoid fitting where the plane fit cannot; untick to keep "
						+ "ELEPHANT's own shape)", ASK_PREFS.getBoolean( KEY_SHAPE_FIT, true ) );
		// Takes effect from the next run: the measurements are taken before this dialog opens.
		final JCheckBox measureImageCheck = new JCheckBox(
				"Measure the image before this dialog on the next runs (intensity profile and cell size; untick to "
						+ "skip both — over a minute on a large stack read over a network, and neither can be "
						+ "measured on a sparse movie with a background of exactly 0)", suggestion.imageMeasured );
		final JCheckBox detectorTrialCheck = new JCheckBox(
				"Show me pretrained detectors (StarDist, Cellpose) on a few frames and let me choose (untick to "
						+ "choose automatically from the image)", d.detectorTrialDialog );
		// Still the user's call: a speed/accuracy trade, not something the pixels decide.
		final JCheckBox cropTuningCheck = new JCheckBox(
				"Crop-restricted tuning (much faster; untick to tune on the full frame)", d.cropTuning );
		final JCheckBox fluorescenceCheck = new JCheckBox(
				"These are fluorescence images — also remove detections far dimmer in TOTAL than my "
						+ "confirmed cells",
				d.fluorescenceDimFilter );
		// Off by default: it deletes nothing, and on this project's crops it already came back with no
		// separation at all. What it costs is a multi-image VLM round trip, up to three per window.
		final JCheckBox flatContrastAuditCheck = new JCheckBox(
				"Audit the flat-contrast verdict with the VLM (diagnostic only — slow, and it has not "
						+ "separated real cells from background on these crops)", d.flatContrastAudit );
		final JCheckBox vlmCropCheckBox = new JCheckBox(
				"<html><b>Ask the VLM what is in a crop</b> — untick to decide it by measuring the crop's "
						+ "intensity against this frame's own background instead. Measured on the crops this "
						+ "pipeline sends, the VLM called 17% of genuinely empty positions empty, and 35% of "
						+ "the ones holding a cell empty; on fluorescence the measurement answers the same "
						+ "question and can be recomputed. Where brightness does not mean signal, the "
						+ "measurement has nothing to stand on — leave it ticked.</html>",
				d.vlmCropCheck );

		// ---- The set-up as four steps, one decision each, in place of one screen of twenty controls: the data (what
		// the scan found, and what only the user knows), the image type, the options, the instructions. The controls
		// above are the same ones, and Start builds the same PreflightResult.

		// Step 2: the image type. Pre-selected from the movie's description or the pixels, and the user's answer
		// overrides the reading everywhere (ImageProfile.SHARED_STATED_MODALITY).
		final Modality.Guess guess = Modality.guess( suggestion.imageProfile,
				( String ) context.getSharedData( AnalyzeVideoTool.SHARED_DATA_KEY ) );
		final Modality[] chosenModality = { guess.modality };
		final javax.swing.ButtonGroup modalityGroup = new javax.swing.ButtonGroup();
		final JPanel modalityChoices = column();
		final Runnable followModality = () -> {
			// The switches that only make sense where brightness means signal follow the answer; the user can
			// still change each one under "Advanced" in step 3.
			final ImageProfile stated = suggestion.imageProfile.withStatedModality( chosenModality[ 0 ] );
			flatContrastCheck.setSelected( stated.flatContrastDeletionApplies() );
			fluorescenceCheck.setSelected( stated.dimSignalFilterApplies() );
			vlmCropCheckBox.setSelected( stated.vlmCropCheckApplies() );
		};
		for ( final Modality m : Modality.values() )
		{
			final javax.swing.JRadioButton b = new javax.swing.JRadioButton(
					"<html><b>" + m.label + "</b> — " + m.description + "</html>", m == guess.modality );
			b.addActionListener( ev -> {
				chosenModality[ 0 ] = m;
				followModality.run();
			} );
			modalityGroup.add( b );
			modalityChoices.add( leftAligned( b ) );
		}
		followModality.run();

		// Step 3: the training balance, the one option that changes what the model learns from every window.
		final boolean asCorrectionsInitial = ASK_PREFS.getBoolean( KEY_DETECTIONS_AS_CORRECTIONS, true );
		final javax.swing.JRadioButton balanceAsCorrections = new javax.swing.JRadioButton(
				"<html><b>Cells at the weight of a correction (10x)</b> — the cells outweigh the background "
						+ "negatives.</html>",
				asCorrectionsInitial );
		final javax.swing.JRadioButton balanceOrdinary = new javax.swing.JRadioButton(
				"<html><b>Cells at 1x</b>, corrections and background negatives at 10x — the setting of the "
						+ "published benchmark. The background then weighs about twice the cells.</html>",
				!asCorrectionsInitial );
		final javax.swing.ButtonGroup balanceGroup = new javax.swing.ButtonGroup();
		balanceGroup.add( balanceAsCorrections );
		balanceGroup.add( balanceOrdinary );

		// Step 4: the user's own instructions, added to the guidance.
		final JTextArea ownInstructions = new JTextArea( 5, 60 );
		ownInstructions.setLineWrap( true );
		ownInstructions.setWrapStyleWord( true );

		// ---- The four pages.
		final JPanel step1 = column();
		step1.add( heading( "What the scan found" ) );
		step1.add( leftAligned( new JLabel( dataSummaryHtml( context, suggestion ) ) ) );
		final String movie = ( String ) context.getSharedData( AnalyzeVideoTool.SHARED_DATA_KEY );
		if ( movie != null && !movie.trim().isEmpty() )
		{
			final JTextArea movieText = new JTextArea( movie, 7, 40 );
			movieText.setEditable( false );
			movieText.setLineWrap( true );
			movieText.setWrapStyleWord( true );
			movieText.setCaretPosition( 0 );
			step1.add( leftAligned( new JLabel( "What the vision model saw:" ) ) );
			step1.add( leftAligned( new JScrollPane( movieText ) ) );
		}
		step1.add( heading( "What only you know" ) );
		applyExpectedCellsCheck.setText( "Cells per frame —" );
		applyStartTpCheck.setText( "Begin at timepoint (instead of resuming):" );
		final JPanel expectedCellsRow = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
		expectedCellsRow.add( applyExpectedCellsCheck );
		expectedCellsRow.add( new JLabel( "first timepoint:" ) );
		expectedCellsRow.add( spinExpectedCells );
		expectedCellsRow.add( new JLabel( "last timepoint:" ) );
		expectedCellsRow.add( spinExpectedCellsEnd );
		step1.add( leftAligned( expectedCellsRow ) );
		step1.add( note( "Give both ends when the population grows; equal values mean a steady one. The run's flood and "
				+ "over-deletion checks are calibrated against it. Leave unticked to let the run estimate it." ) );
		final JPanel rangeRow = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
		rangeRow.add( new JLabel( "Timepoints to track: from" ) );
		rangeRow.add( spinRangeStart );
		rangeRow.add( new JLabel( "to" ) );
		rangeRow.add( spinRangeEnd );
		rangeRow.add( rangeToLastCheck );
		step1.add( leftAligned( rangeRow ) );
		step1.add( note( "Tracking and correction stay within these timepoints. The whole movie unless you change it; "
				+ "not remembered for the next run." ) );
		final JPanel startTpRow = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
		startTpRow.add( applyStartTpCheck );
		startTpRow.add( spinStartTp );
		step1.add( leftAligned( startTpRow ) );
		step1.add( leftAligned( startFreshCheck ) );
		step1.add( leftAligned( measureImageCheck ) );

		final JPanel step2 = column();
		step2.add( heading( "What kind of images are these?" ) );
		step2.add( note( "Pre-selected because " + guess.reason + "." ) );
		step2.add( leftAligned( modalityChoices ) );
		step2.add( note( "Every step that depends on whether brightness means signal follows this answer: flat-contrast "
				+ "deletion, the dim-signal filter, measuring a crop instead of asking the vision model, and rebuilding a "
				+ "flooded model from bright spots. Each can still be changed under Advanced in the next step." ) );

		final JPanel step3 = column();
		step3.add( heading( "Training balance" ) );
		step3.add( leftAligned( balanceAsCorrections ) );
		step3.add( leftAligned( balanceOrdinary ) );
		step3.add( heading( "After each inference" ) );
		step3.add( leftAligned( shapeFitCheck ) );
		step3.add( leftAligned( cropTuningCheck ) );
		step3.add( heading( "During the run" ) );
		step3.add( leftAligned( askMergesAndDivisionsCheck ) );
		step3.add( leftAligned( detectorTrialCheck ) );
		step3.add( leftAligned( balanceTrainingCheck ) );
		final JPanel advanced = column();
		if ( cellScaleAvailable )
			advanced.add( leftAligned( cellScaleCheck ) );
		if ( patchAvailable )
		{
			final JPanel patchRow = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
			patchRow.add( new JLabel( String.format( "Patch size (current %d x %d x %d) — X:",
					suggestion.currentPatchSize[ 0 ], suggestion.currentPatchSize[ 1 ],
					suggestion.currentPatchSize[ 2 ] ) ) );
			patchRow.add( spinX );
			patchRow.add( new JLabel( "Y:" ) );
			patchRow.add( spinY );
			patchRow.add( new JLabel( "Z:" ) );
			patchRow.add( spinZ );
			advanced.add( leftAligned( patchRow ) );
			advanced.add( leftAligned( applyPatchCheck ) );
		}
		final JPanel refineRow = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
		refineRow.add( new JLabel( String.format( "Max refine rounds (default %d):", ElephantTool.DEFAULT_REFINE_ITERATIONS ) ) );
		refineRow.add( spinRefineIterations );
		refineRow.add( new JLabel( String.format( "  Completeness target (default %.2f):",
				ElephantTool.DEFAULT_COMPLETENESS_TARGET ) ) );
		refineRow.add( spinCompletenessTarget );
		advanced.add( leftAligned( refineRow ) );
		advanced.add( leftAligned( applyRefineCapsCheck ) );
		if ( nCropsAvailable )
		{
			final JPanel nCropsRow = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
			nCropsRow.add( new JLabel( String.format( "n_crops (current ELEPHANT setting %d):", suggestion.currentNCrops ) ) );
			nCropsRow.add( spinNCrops );
			advanced.add( leftAligned( nCropsRow ) );
			advanced.add( leftAligned( applyNCropsCheck ) );
		}
		final JPanel protectedRow = new JPanel( new FlowLayout( FlowLayout.LEFT ) );
		protectedRow.add( new JLabel( "Protect spots tagged — tag set:" ) );
		protectedRow.add( protectedTagSetCombo );
		protectedRow.add( new JLabel( "tag:" ) );
		protectedRow.add( protectedTagLabelCombo );
		advanced.add( leftAligned( protectedRow ) );
		advanced.add( note( "Leave both blank to disable. Spots with this tag are never deleted, merged or reshaped." ) );
		advanced.add( leftAligned( flatContrastCheck ) );
		advanced.add( leftAligned( fluorescenceCheck ) );
		advanced.add( leftAligned( vlmCropCheckBox ) );
		advanced.add( leftAligned( resolveDuplicatesCheck ) );
		advanced.add( leftAligned( splitMergedCheck ) );
		advanced.add( leftAligned( flatContrastAuditCheck ) );
		advanced.setVisible( false );
		final JButton advancedToggle = new JButton( "Show advanced settings" );
		advancedToggle.addActionListener( ev -> {
			advanced.setVisible( !advanced.isVisible() );
			advancedToggle.setText( advanced.isVisible() ? "Hide advanced settings" : "Show advanced settings" );
			step3.revalidate();
		} );
		step3.add( leftAligned( advancedToggle ) );
		step3.add( leftAligned( advanced ) );

		final JPanel step4 = column();
		step4.add( heading( "Instructions for the tracking agent" ) );
		step4.add( note( "Suggested from the dataset analysis; edit freely." ) );
		scrollPane.setPreferredSize( new Dimension( WRAP_PX, 200 ) );
		step4.add( leftAligned( scrollPane ) );
		step4.add( leftAligned( new JLabel( "Your own instructions (optional, added to the above):" ) ) );
		ownInstructions.setRows( 4 );
		step4.add( leftAligned( new JScrollPane( ownInstructions ) ) );
		final JLabel summary = new JLabel();
		step4.add( heading( "Summary" ) );
		step4.add( leftAligned( summary ) );

		final String[] titles = { "The data", "Image type", "Options", "Instructions" };
		final JPanel[] steps = { step1, step2, step3, step4 };
		final java.awt.CardLayout cards = new java.awt.CardLayout();
		final JPanel deck = new JPanel( cards );
		for ( int i = 0; i < steps.length; i++ )
		{
			final JScrollPane page = new JScrollPane( steps[ i ], ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
					ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER );
			page.setBorder( javax.swing.BorderFactory.createEmptyBorder() );
			page.getViewport().setBackground( steps[ i ].getBackground() );
			page.getVerticalScrollBar().setUnitIncrement( 16 );
			deck.add( page, titles[ i ] );
		}
		final JLabel stepLabel = new JLabel();
		stepLabel.setFont( stepLabel.getFont().deriveFont( java.awt.Font.BOLD, stepLabel.getFont().getSize2D() + 3f ) );

		final JDialog dialog = new JDialog( ( java.awt.Frame ) null, "Autonomous tracking — set-up", true );
		final JButton startButton = new JButton( "Start" );
		final JButton cancelButton = new JButton( "Cancel" );
		final JButton backButton = new JButton( "Back" );
		final JButton nextButton = new JButton( "Next" );
		final int[] current = { 0 };
		final Runnable show = () -> {
			cards.show( deck, titles[ current[ 0 ] ] );
			stepLabel.setText( String.format( "Step %d of %d — %s", current[ 0 ] + 1, steps.length, titles[ current[ 0 ] ] ) );
			backButton.setEnabled( current[ 0 ] > 0 );
			nextButton.setVisible( current[ 0 ] < steps.length - 1 );
			startButton.setVisible( current[ 0 ] == steps.length - 1 );
			if ( current[ 0 ] == steps.length - 1 )
				summary.setText( summaryHtml( chosenModality[ 0 ],
						rangeText( ( Integer ) spinRangeStart.getValue(),
								rangeToLastCheck.isSelected() ? -1 : ( Integer ) spinRangeEnd.getValue(), lastTp ),
						balanceAsCorrections.isSelected(), startFreshCheck.isSelected(),
						applyStartTpCheck.isSelected() ? ( Integer ) spinStartTp.getValue() : null,
						applyExpectedCellsCheck.isSelected() ? spinExpectedCells.getValue() + " → " + spinExpectedCellsEnd.getValue() : null,
						shapeFitCheck.isSelected(), askMergesAndDivisionsCheck.isSelected() ) );
			dialog.getRootPane().setDefaultButton( current[ 0 ] == steps.length - 1 ? startButton : nextButton );
		};
		backButton.addActionListener( ev -> {
			current[ 0 ] = Math.max( 0, current[ 0 ] - 1 );
			show.run();
		} );
		nextButton.addActionListener( ev -> {
			current[ 0 ] = Math.min( steps.length - 1, current[ 0 ] + 1 );
			show.run();
		} );
		startButton.addActionListener( ev -> {
			final int[] patchSize = patchAvailable && applyPatchCheck.isSelected()
					? new int[] { ( Integer ) spinX.getValue(), ( Integer ) spinY.getValue(),
							( Integer ) spinZ.getValue() }
					: null;
			final Integer refineCap = applyRefineCapsCheck.isSelected()
					? ( Integer ) spinRefineIterations.getValue() : null;
			final Double targetCap = applyRefineCapsCheck.isSelected()
					? ( Double ) spinCompletenessTarget.getValue() : null;
			final Integer nCropsOverride = nCropsAvailable && applyNCropsCheck.isSelected()
					? ( Integer ) spinNCrops.getValue() : null;
			final String protectedTagSetText = String.valueOf( protectedTagSetCombo.getEditor().getItem() ).trim();
			final String protectedTagLabelText =
					String.valueOf( protectedTagLabelCombo.getEditor().getItem() ).trim();
			final String protectedTagSet = protectedTagSetText.isEmpty() ? null : protectedTagSetText;
			final String protectedTagLabel = protectedTagLabelText.isEmpty() ? null : protectedTagLabelText;
			final Integer expectedCellsPerFrame = applyExpectedCellsCheck.isSelected()
					? ( Integer ) spinExpectedCells.getValue() : null;
			final Integer expectedCellsAtEnd = applyExpectedCellsCheck.isSelected()
					? ( Integer ) spinExpectedCellsEnd.getValue() : null;
			final Integer startTimepoint = applyStartTpCheck.isSelected()
					? ( Integer ) spinStartTp.getValue() : null;
			final String own = ownInstructions.getText().trim();
			final String guidance = own.isEmpty() ? textArea.getText()
					: textArea.getText() + String.format( "%n%nThe user's own instructions (follow these):%n%s", own );
			result[ 0 ] = new PreflightResult( guidance, patchSize, refineCap, targetCap,
					nCropsOverride, startFreshCheck.isSelected(), protectedTagSet, protectedTagLabel,
					expectedCellsPerFrame, expectedCellsAtEnd, startTimepoint,
					flatContrastCheck.isSelected(),
					resolveDuplicatesCheck.isSelected(), fluorescenceCheck.isSelected(),
					cropTuningCheck.isSelected(), flatContrastAuditCheck.isSelected(),
					vlmCropCheckBox.isSelected(), splitMergedCheck.isSelected(),
					balanceTrainingCheck.isSelected(), askMergesAndDivisionsCheck.isSelected(),
					detectorTrialCheck.isSelected(),
					cellScaleAvailable && cellScaleCheck.isSelected() ? suggestion.cellScale.radius : null,
					chosenModality[ 0 ], balanceAsCorrections.isSelected() );
			ASK_PREFS.putBoolean( KEY_MEASURE_IMAGE, measureImageCheck.isSelected() );
			ASK_PREFS.putBoolean( KEY_SHAPE_FIT, shapeFitCheck.isSelected() );
			ASK_PREFS.putBoolean( KEY_DETECTIONS_AS_CORRECTIONS, balanceAsCorrections.isSelected() );
			context.setSharedData( CleanupToggles.SHARED_SHAPE_FIT_OVERRIDE, shapeFitCheck.isSelected() );
			RunRange.set( context, ( Integer ) spinRangeStart.getValue(),
					rangeToLastCheck.isSelected() ? -1 : ( Integer ) spinRangeEnd.getValue() );
			dialog.dispose();
		} );
		cancelButton.addActionListener( ev -> {
			result[ 0 ] = null;
			dialog.dispose();
		} );
		final JPanel buttons = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );
		buttons.add( cancelButton );
		buttons.add( backButton );
		buttons.add( nextButton );
		buttons.add( startButton );

		final JPanel content = new JPanel( new BorderLayout( 0, 10 ) );
		content.setBorder( javax.swing.BorderFactory.createEmptyBorder( 12, 16, 8, 16 ) );
		content.add( stepLabel, BorderLayout.NORTH );
		content.add( deck, BorderLayout.CENTER );
		content.add( buttons, BorderLayout.SOUTH );
		dialog.getContentPane().add( content );
		show.run();
		dialog.setSize( WIZARD_SIZE );
		fitToScreen( dialog );
		dialog.setLocationRelativeTo( null );
		dialog.setResizable( true );
		dialog.addWindowListener( new WindowAdapter()
		{
			@Override
			public void windowClosing( final WindowEvent we )
			{
				result[ 0 ] = null;
				dialog.dispose();
			}
		} );
		// MODAL: blocks here until dialog.dispose() is called by one of the listeners above.
		dialog.setVisible( true );
		return result[ 0 ];
	}

	/** The set-up wizard's opening size; fitted to the screen. */
	private static final Dimension WIZARD_SIZE = new Dimension( 960, 700 );

	/** Width, px, at which the wizard's texts wrap: the page width less its margins and scroll bar. */
	private static final int WRAP_PX = 680;

	/** A page column that is as wide as the window, so its texts wrap instead of running off the edge. */
	private static final class Page extends JPanel implements javax.swing.Scrollable
	{
		private static final long serialVersionUID = 1L;

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement( final java.awt.Rectangle r, final int o, final int d )
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement( final java.awt.Rectangle r, final int o, final int d )
		{
			return r.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private static JPanel column()
	{
		final JPanel p = new Page();
		p.setLayout( new BoxLayout( p, BoxLayout.Y_AXIS ) );
		p.setAlignmentX( java.awt.Component.LEFT_ALIGNMENT );
		return p;
	}

	/**
	 * Left-aligned, at its own height (a BoxLayout otherwise stretches rows into gaps), and a check box's or radio
	 * button's text wrapped at {@link #WRAP_PX}.
	 */
	private static < C extends JComponent > C leftAligned( final C c )
	{
		if ( c instanceof javax.swing.AbstractButton )
		{
			final javax.swing.AbstractButton b = ( javax.swing.AbstractButton ) c;
			final String t = b.getText();
			if ( t != null && t.length() > 60 && !t.contains( "<div style='width" ) )
			{
				final String body = t.startsWith( "<html>" ) ? t.substring( 6, t.length() - ( t.endsWith( "</html>" ) ? 7 : 0 ) )
						: t.replace( "&", "&amp;" ).replace( "<", "&lt;" );
				b.setText( "<html><div style='width:" + WRAP_PX + "px'>" + body + "</div></html>" );
			}
		}
		c.setAlignmentX( java.awt.Component.LEFT_ALIGNMENT );
		if ( c instanceof JButton )
			c.setMaximumSize( c.getPreferredSize() );
		else if ( !( c instanceof javax.swing.JScrollPane ) )
			c.setMaximumSize( new Dimension( Integer.MAX_VALUE, c.getPreferredSize().height ) );
		return c;
	}

	private static JLabel heading( final String text )
	{
		final JLabel l = new JLabel( text );
		l.setFont( l.getFont().deriveFont( java.awt.Font.BOLD ) );
		l.setBorder( javax.swing.BorderFactory.createEmptyBorder( 10, 0, 4, 0 ) );
		return leftAligned( l );
	}

	private static JLabel note( final String text )
	{
		final JLabel l = new JLabel( "<html><div style='width:" + WRAP_PX + "px'>" + text + "</div></html>" );
		l.setForeground( java.awt.Color.GRAY.darker() );
		return leftAligned( l );
	}

	/** Step 1's account of the data: size, voxel, timepoints, resolution levels, and what was measured. */
	static String dataSummaryHtml( final ToolContext context, final PreflightSuggestion suggestion )
	{
		final StringBuilder b = new StringBuilder( "<html><div style='width:" + WRAP_PX + "px'>" );
		try
		{
			final BasicViewSetup setup = ( BasicViewSetup ) context.getProjectModel().getSharedBdvData().getSpimData()
					.getSequenceDescription().getViewSetupsOrdered().get( 0 );
			final net.imglib2.Dimensions dims = setup.getSize();
			final mpicbg.spim.data.sequence.VoxelDimensions v = setup.getVoxelSize();
			b.append( String.format( "Image %d x %d x %d voxels of %.3g x %.3g x %.3g %s; timepoints %d..%d",
					dims.dimension( 0 ), dims.dimension( 1 ), dims.dimension( 2 ), v.dimension( 0 ), v.dimension( 1 ),
					v.dimension( 2 ), v.unit(), context.getProjectModel().getMinTimepoint(),
					context.getProjectModel().getMaxTimepoint() ) );
		}
		catch ( final RuntimeException e )
		{
			b.append( "Image size could not be read" );
		}
		try
		{
			final int levels = context.requireViewer().state().getSources().get( 0 ).getSpimSource().getNumMipmapLevels();
			b.append( String.format( "; %d resolution level(s).", levels ) );
		}
		catch ( final RuntimeException e )
		{
			b.append( "." );
		}
		b.append( "<br>" ).append( suggestion.imageMeasured ? suggestion.imageProfile.describe()
				: "Image intensity and cell size: not measured (see the last box on this page)." );
		if ( suggestion.cellScale != null )
			b.append( "<br>Cell size: " ).append( describeCellScale( suggestion.cellScale ) );
		return b.append( "</div></html>" ).toString();
	}

	/** The movie's last timepoint, from the viewer; a large bound when it cannot be read. */
	private static int lastTimepoint( final ToolContext context )
	{
		try
		{
			return Math.max( 0, context.requireViewer().state().getNumTimepoints() - 1 );
		}
		catch ( final RuntimeException e )
		{
			return 1_000_000;
		}
	}

	/** The run's range as the summary shows it; {@code end < 0} = the last timepoint. */
	static String rangeText( final int start, final int end, final int lastTp )
	{
		final int to = end < 0 ? lastTp : end;
		return String.format( "t=%d..%d", start, to ) + ( start == 0 && to == lastTp ? " (the whole movie)" : "" );
	}

	/** Step 4's summary of the choices that shape the run most. */
	static String summaryHtml( final Modality modality, final String range, final boolean cellsAsCorrections,
			final boolean startFresh, final Integer startTimepoint, final String expectedCells, final boolean shapeFit,
			final boolean ask )
	{
		return "<html><ul style='margin-left:12px'>"
				+ "<li>Image type: <b>" + modality.label + "</b></li>"
				+ "<li>Timepoints: <b>" + range + "</b></li>"
				+ "<li>Training balance: <b>" + ( cellsAsCorrections ? "cells at 10x"
						: "cells at 1x, negatives at 10x" ) + "</b></li>"
				+ "<li>Start: <b>" + ( startFresh ? "fresh (all spots deleted)" : "resume" )
				+ ( startTimepoint != null ? ", from t=" + startTimepoint : "" ) + "</b></li>"
				+ "<li>Cells per frame: <b>" + ( expectedCells != null ? expectedCells : "not stated" ) + "</b></li>"
				+ "<li>Shape fitting after each inference: <b>" + ( shapeFit ? "on" : "off" ) + "</b></li>"
				+ "<li>Questions about merges and divisions: <b>" + ( ask ? "ask me" : "decide automatically" ) + "</b></li>"
				+ "</ul></html>";
	}

	private < T extends MamutViewI > T findExistingView( final Class< T > klass,
			final int groupId )
	{
		for ( final T view : projectModel.getWindowManager().getViewList( klass ) )
		{
			if ( view.getGroupHandle().getGroupId() == groupId )
				return view;
		}
		return null;
	}

	/** Cap the question text area to this size; longer content scrolls instead of growing the dialog. */
	private static final Dimension QUESTION_AREA_SIZE = new Dimension( 800, 450 );

	/**
	 * Shows a dialog asking the user {@code question}. If {@code options} are given the user picks one
	 * (or cancels); otherwise a free-text prompt is shown. Called from the agent's background thread,
	 * so the dialog is shown on the EDT while this method blocks (via a latch) until answered.
	 *
	 * <p>Deliberately NON-modal ({@link Dialog.ModalityType#MODELESS}): the user routinely keeps
	 * watching/navigating BDV and TrackScheme while the autonomous run is in progress, and an
	 * application-modal dialog would deactivate/block those other windows for as long as the decision
	 * is pending. {@code alwaysOnTop} keeps it from getting lost behind those windows instead.</p>
	 *
	 * <p>The question is shown in a scrollable, resizable text area rather than as a plain label: the
	 * agent's reasoning can run to many lines, and a plain {@link JOptionPane} label neither wraps into a
	 * resizable dialog nor scrolls, so long questions previously pushed the OK/Cancel buttons off-screen
	 * with no way to reach them.</p>
	 *
	 * @return the chosen option / typed text, or {@code null} if the user cancelled
	 */
	private static String promptUser( final String question, final java.util.List< String > options )
	{
		if ( SwingUtilities.isEventDispatchThread() )
		{
			// Should never happen (always invoked from the agent's background thread) — blocking on the
			// latch below from the EDT would deadlock the dialog against itself.
			log.warn( "promptUser() called from the EDT; refusing to show a blocking dialog." );
			return null;
		}

		final String[] answer = new String[ 1 ];
		final boolean[] answered = new boolean[ 1 ];
		final CountDownLatch latch = new CountDownLatch( 1 );
		final boolean hasOptions = options != null && !options.isEmpty();
		final Runnable show = () -> {
			final JTextArea textArea = new JTextArea( question );
			textArea.setEditable( false );
			textArea.setLineWrap( true );
			textArea.setWrapStyleWord( true );
			textArea.setCaretPosition( 0 );
			final JScrollPane scrollPane = new JScrollPane( textArea );
			scrollPane.setPreferredSize( QUESTION_AREA_SIZE );

			// Input component built manually (NOT via JOptionPane#setWantsInput+setSelectionValues) so
			// the suggested options are editable — the agent's fixed multiple-choice list is a set of
			// SUGGESTIONS, not an exhaustive enumeration, and its premise can simply be wrong (e.g. it
			// asked "which cleanup phase?" when the real answer is "redo detection from scratch, none of
			// these apply"). JOptionPane's own selection-value combo hardcodes setEditable(false), which
			// silently boxed the user into one of the agent's guesses with no way to correct it.
			final JComboBox< String > answerCombo = hasOptions ? new JComboBox<>( options.toArray( new String[ 0 ] ) ) : null;
			if ( answerCombo != null )
			{
				answerCombo.setEditable( true );
				answerCombo.setSelectedItem( options.get( 0 ) );
			}
			final JTextField answerField = hasOptions ? null : new JTextField();
			final JPanel messagePanel = new JPanel( new BorderLayout( 0, 8 ) );
			messagePanel.add( scrollPane, BorderLayout.CENTER );
			if ( hasOptions )
			{
				final JPanel answerPanel = new JPanel( new BorderLayout( 4, 2 ) );
				answerPanel.add( new JLabel( "Pick a suggestion or type your own answer:" ), BorderLayout.NORTH );
				answerPanel.add( answerCombo, BorderLayout.CENTER );
				messagePanel.add( answerPanel, BorderLayout.SOUTH );
			}
			else
			{
				final JPanel answerPanel = new JPanel( new BorderLayout( 4, 2 ) );
				answerPanel.add( new JLabel( "Your answer:" ), BorderLayout.NORTH );
				answerPanel.add( answerField, BorderLayout.CENTER );
				messagePanel.add( answerPanel, BorderLayout.SOUTH );
			}

			final JOptionPane pane = new JOptionPane( messagePanel, JOptionPane.QUESTION_MESSAGE,
					JOptionPane.OK_CANCEL_OPTION );
			final JDialog dialog = pane.createDialog( null,
					hasOptions ? "Autonomous tracking — decision needed"
							: "Autonomous tracking — input needed" );
			dialog.setModalityType( Dialog.ModalityType.MODELESS );
			dialog.setAlwaysOnTop( true );
			dialog.setResizable( true );
			pane.addPropertyChangeListener( evt -> {
				// NOTE: do not gate on dialog.isVisible() here. JOptionPane.createDialog() installs its
				// own PropertyChangeListener BEFORE this one and reacts to the same VALUE_PROPERTY change
				// by calling dialog.setVisible(false) — which runs first (listeners fire in registration
				// order), so by the time this listener sees the event dialog.isVisible() is already false.
				// Gating on it here caused every real answer to be silently dropped: the dialog visibly
				// closed but answer[0] was never set and the latch never counted down, hanging the
				// background thread in latch.await() until the run was cancelled.
				if ( !JOptionPane.VALUE_PROPERTY.equals( evt.getPropertyName() ) || answered[ 0 ] )
					return;
				final Object value = pane.getValue();
				if ( value == JOptionPane.UNINITIALIZED_VALUE )
					return;
				answered[ 0 ] = true;
				if ( value instanceof Integer && ( Integer ) value == JOptionPane.OK_OPTION )
				{
					final String typed = hasOptions
							? String.valueOf( answerCombo.getEditor().getItem() )
							: answerField.getText();
					answer[ 0 ] = typed == null || typed.trim().isEmpty() ? null : typed.trim();
				}
				else
				{
					answer[ 0 ] = null;
				}
				dialog.dispose();
				latch.countDown();
			} );
			dialog.setVisible( true );
		};
		try
		{
			SwingUtilities.invokeLater( show );
			latch.await();
		}
		catch ( final Exception e )
		{
			return null;
		}
		return answer[ 0 ];
	}

	/**
	 * Shows {@code question} above the images side by side, each with its caption, and one button per
	 * entry of {@code buttons}; blocks until one is pressed. Non-modal and always on top, as
	 * {@link #promptUser} is, so the user can still look at BDV and TrackScheme while deciding.
	 *
	 * @return the index of the button pressed, or {@code -1} if the dialog was closed
	 */
	private static int promptImageChoice( final String question, final List< java.awt.image.BufferedImage > images,
			final List< String > captions, final List< String > buttons )
	{
		if ( SwingUtilities.isEventDispatchThread() )
		{
			log.warn( "promptImageChoice() called from the EDT; refusing to show a blocking dialog." );
			return -1;
		}
		final int[] answer = { -1 };
		final CountDownLatch latch = new CountDownLatch( 1 );
		final Runnable show = () -> {
			final JDialog dialog = new JDialog( ( java.awt.Frame ) null, "Automahout — which is right?",
					Dialog.ModalityType.MODELESS );
			dialog.setAlwaysOnTop( true );
			dialog.setDefaultCloseOperation( JDialog.DISPOSE_ON_CLOSE );
			final JTextArea text = new JTextArea( question );
			text.setEditable( false );
			text.setLineWrap( true );
			text.setWrapStyleWord( true );
			text.setRows( 4 );
			final JPanel pictures = new JPanel( new FlowLayout( FlowLayout.CENTER, 16, 8 ) );
			for ( int i = 0; i < images.size(); i++ )
			{
				final JPanel one = new JPanel( new BorderLayout( 0, 4 ) );
				final JLabel caption = new JLabel( i < captions.size() ? captions.get( i ) : "", JLabel.CENTER );
				caption.setFont( caption.getFont().deriveFont( Font.BOLD, 14f ) );
				one.add( caption, BorderLayout.NORTH );
				one.add( new JLabel( new javax.swing.ImageIcon( images.get( i ) ) ), BorderLayout.CENTER );
				pictures.add( one );
			}
			final JPanel row = new JPanel( new FlowLayout( FlowLayout.CENTER, 8, 8 ) );
			for ( int i = 0; i < buttons.size(); i++ )
			{
				final int index = i;
				final JButton b = new JButton( buttons.get( i ) );
				b.addActionListener( e -> {
					answer[ 0 ] = index;
					dialog.dispose();
				} );
				row.add( b );
			}
			dialog.addWindowListener( new WindowAdapter()
			{
				@Override
				public void windowClosed( final WindowEvent e )
				{
					latch.countDown();
				}
			} );
			final JPanel content = new JPanel( new BorderLayout( 0, 8 ) );
			content.add( new JScrollPane( text ), BorderLayout.NORTH );
			content.add( pictures, BorderLayout.CENTER );
			content.add( row, BorderLayout.SOUTH );
			dialog.setContentPane( content );
			dialog.pack();
			dialog.setLocationRelativeTo( null );
			dialog.setVisible( true );
		};
		try
		{
			SwingUtilities.invokeLater( show );
			latch.await();
		}
		catch ( final InterruptedException e )
		{
			Thread.currentThread().interrupt();
			return -1;
		}
		return answer[ 0 ];
	}

	/**
	 * Shows {@code message} with Done and Cancel while the user edits in BDV / TrackScheme, and blocks
	 * until one is pressed. Non-modal, so the viewers stay usable -- that is the point.
	 *
	 * @return {@code true} on Done
	 */
	private static boolean waitForHandEdit( final String message )
	{
		if ( SwingUtilities.isEventDispatchThread() )
		{
			log.warn( "waitForHandEdit() called from the EDT; refusing to show a blocking dialog." );
			return false;
		}
		final boolean[] done = { false };
		final CountDownLatch latch = new CountDownLatch( 1 );
		final Runnable show = () -> {
			final JDialog dialog = new JDialog( ( java.awt.Frame ) null, "Automahout — fix it by hand",
					Dialog.ModalityType.MODELESS );
			dialog.setAlwaysOnTop( true );
			dialog.setDefaultCloseOperation( JDialog.DISPOSE_ON_CLOSE );
			final JTextArea text = new JTextArea( message );
			text.setEditable( false );
			text.setLineWrap( true );
			text.setWrapStyleWord( true );
			text.setRows( 7 );
			text.setColumns( 60 );
			final JButton ok = new JButton( "Done" );
			ok.addActionListener( e -> {
				done[ 0 ] = true;
				dialog.dispose();
			} );
			final JButton cancel = new JButton( "Cancel" );
			cancel.addActionListener( e -> dialog.dispose() );
			dialog.addWindowListener( new WindowAdapter()
			{
				@Override
				public void windowClosed( final WindowEvent e )
				{
					latch.countDown();
				}
			} );
			final JPanel row = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );
			row.add( cancel );
			row.add( ok );
			final JPanel content = new JPanel( new BorderLayout( 0, 8 ) );
			content.add( new JScrollPane( text ), BorderLayout.CENTER );
			content.add( row, BorderLayout.SOUTH );
			dialog.setContentPane( content );
			dialog.pack();
			dialog.setLocationRelativeTo( null );
			dialog.setVisible( true );
		};
		try
		{
			SwingUtilities.invokeLater( show );
			latch.await();
		}
		catch ( final InterruptedException e )
		{
			Thread.currentThread().interrupt();
			return false;
		}
		return done[ 0 ];
	}

	/** How often to poll {@code currentCount} while a {@link #waitForAnnotations} dialog is open. */
	private static final int ANNOTATION_POLL_MS = 300;

	/**
	 * Shows a non-modal dialog asking the user to place {@code targetCount} annotations directly in
	 * BDV/TrackScheme (rather than describing them in text), with a live counter that polls
	 * {@code currentCount}. Auto-proceeds the instant the target is reached; Cancel aborts. See
	 * {@link #promptUser} for why this is non-modal.
	 *
	 * <p>Confirmation is an explicit Enter keystroke (bound on the dialog, active regardless of which
	 * component inside it has focus) that calls {@code confirmSelected} — NOT an automatic timing
	 * heuristic — so a user unfamiliar with the flow is never carried forward without realizing they
	 * triggered it. Bound on the dialog rather than globally so it cannot interfere with Mastodon's own
	 * keybindings outside this specific wait.</p>
	 *
	 * @return {@code true} once {@code targetCount} was reached, {@code false} if the user cancelled
	 */
	private static boolean waitForAnnotations( final String message, final int targetCount,
			final IntSupplier currentCount, final Runnable confirmSelected )
	{
		if ( SwingUtilities.isEventDispatchThread() )
		{
			// Should never happen (always invoked from the agent's background thread) — see promptUser.
			log.warn( "waitForAnnotations() called from the EDT; refusing to show a blocking dialog." );
			return false;
		}

		final boolean[] result = new boolean[ 1 ];
		final boolean[] done = new boolean[ 1 ];
		final CountDownLatch latch = new CountDownLatch( 1 );
		final Runnable show = () -> {
			final JTextArea textArea = new JTextArea( message );
			textArea.setEditable( false );
			textArea.setLineWrap( true );
			textArea.setWrapStyleWord( true );
			textArea.setCaretPosition( 0 );
			final JScrollPane scrollPane = new JScrollPane( textArea );
			scrollPane.setPreferredSize( QUESTION_AREA_SIZE );

			final JLabel progressLabel =
					new JLabel( String.format( "0 / %d annotation(s) confirmed.", targetCount ) );
			progressLabel.setFont( progressLabel.getFont().deriveFont( Font.BOLD, 14f ) );

			final JPanel content = new JPanel( new BorderLayout( 0, 10 ) );
			content.add( scrollPane, BorderLayout.CENTER );
			content.add( progressLabel, BorderLayout.SOUTH );

			final JOptionPane pane = new JOptionPane( content, JOptionPane.QUESTION_MESSAGE,
					JOptionPane.DEFAULT_OPTION, null, new Object[] { "Cancel" } );
			final JDialog dialog = pane.createDialog( null, "Autonomous tracking — manual annotation" );
			dialog.setModalityType( Dialog.ModalityType.MODELESS );
			dialog.setAlwaysOnTop( true );
			dialog.setResizable( true );

			final Timer timer = new Timer( ANNOTATION_POLL_MS, null );
			final Runnable[] refresh = new Runnable[ 1 ];
			refresh[ 0 ] = () -> {
				final int count = Math.max( 0, currentCount.getAsInt() );
				progressLabel.setText( String.format( "%d / %d annotation(s) confirmed.",
						Math.min( count, targetCount ), targetCount ) );
				if ( count >= targetCount )
				{
					done[ 0 ] = true;
					timer.stop();
					result[ 0 ] = true;
					dialog.dispose();
					latch.countDown();
				}
			};
			timer.addActionListener( e -> refresh[ 0 ].run() );

			// Enter confirms whatever is currently selected in BDV/TrackScheme. Bound on the dialog's
			// root pane (WHEN_IN_FOCUSED_WINDOW) so it fires as long as this window is frontmost,
			// regardless of which component inside it has focus.
			final JRootPane rootPane = dialog.getRootPane();
			rootPane.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW )
					.put( KeyStroke.getKeyStroke( "ENTER" ), "confirmAnnotation" );
			rootPane.getActionMap().put( "confirmAnnotation", new AbstractAction()
			{
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed( final ActionEvent e )
				{
					confirmSelected.run();
					refresh[ 0 ].run();
				}
			} );

			pane.addPropertyChangeListener( evt -> {
				// NOTE: do not gate on dialog.isVisible() — see the identical comment in promptUser();
				// JOptionPane.createDialog()'s own listener hides the dialog in reaction to the same
				// VALUE_PROPERTY change, before this listener runs, so isVisible() is already false here.
				if ( !JOptionPane.VALUE_PROPERTY.equals( evt.getPropertyName() ) || done[ 0 ] )
					return;
				if ( pane.getValue() == JOptionPane.UNINITIALIZED_VALUE )
					return;
				// Only the Cancel button (or window close) reaches here — reaching the target is
				// handled by refresh[0], which disposes the dialog directly without going through
				// pane.setValue(), so it never triggers this listener.
				done[ 0 ] = true;
				timer.stop();
				result[ 0 ] = false;
				dialog.dispose();
				latch.countDown();
			} );
			timer.start();
			dialog.setVisible( true );
		};
		try
		{
			SwingUtilities.invokeLater( show );
			latch.await();
		}
		catch ( final Exception e )
		{
			return false;
		}
		return result[ 0 ];
	}
}
