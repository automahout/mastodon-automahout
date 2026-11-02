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
import java.awt.Component;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.eval.DetectionAcceptanceProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.mamut.views.MamutView;
import org.mastodon.mamut.views.MamutViewI;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.mastodon.model.SelectionModel;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import bdv.viewer.ViewerPanel;

/**
 * Menu action for example-based detection filtering over {@code t=0..9}. Two ways to classify:
 *
 * <ol>
 * <li><b>By quality (no examples needed):</b> "Auto-label (by quality)" tags every spot directly from
 * ELEPHANT's own per-spot detection confidence — model-based and modality-agnostic — see
 * {@link AutoCorrectOrchestrator#applyQualityThreshold}. Spots lacking a recorded quality (older server,
 * or added via a non-prediction path) are left untagged.</li>
 * <li><b>By contrast example (fallback):</b> the user selects one or more GOOD detections in BDV and
 * clicks "Add OK = selected" for each, then one or more BAD (e.g. background) detections and clicks
 * "Add NG = selected"; "Label TP/FP" tags every spot as tp/fp by comparing its LOCAL CONTRAST (not
 * absolute intensity) to a threshold placed between the dimmest OK example and the brightest NG example
 * — see {@link AutoCorrectOrchestrator#labelByContrastExample}. Targets background over-detections in
 * no-signal regions while keeping dim-but-real cells, which are a local contrast peak even when their
 * absolute brightness is low.</li>
 * </ol>
 *
 * <p>The dialog is modeless so BDV stays interactive for selecting spots.</p>
 */
class FilterByExampleAction extends AbstractNamedAction
{

	private static final long serialVersionUID = 1L;

	private static final int WINDOW_SIZE = 10;

	private final ProjectModel projectModel;

	private final AutomahoutLogWindow logWindow;

	// OK/NG are ACCUMULATED, not overwritten: each "Add OK/NG = selected" press adds one more example to
	// its list, so a single unrepresentative spot can't dominate the way one overwritten example could.
	// Per-example values (not just a running sum) are kept because classification needs each class's
	// SPREAD, not just its mean — see AutoCorrectOrchestrator#labelByContrastExample. "Label TP/FP" always
	// re-classifies from the CURRENT examples, so pressing it again after adding more (or after Reset +
	// re-picking) is the normal, repeatable check-and-correct workflow.
	private final List< Double > okContrasts = new ArrayList<>();

	private final List< Double > ngContrasts = new ArrayList<>();

	/** Window operated on, snapped to WINDOW_SIZE from the current BDV timepoint (same as FocusWindow). */
	private int startTp;

	private int endTp;

	private final AutomahoutSettings settings;

	FilterByExampleAction( final ProjectModel projectModel, final AutomahoutLogWindow logWindow,
			final AutomahoutSettings settings )
	{
		super( MastodonAutomahoutPlugin.ACTION_FILTER_BY_EXAMPLE_NAME );
		this.projectModel = projectModel;
		this.logWindow = logWindow;
		this.settings = settings;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		okContrasts.clear();
		ngContrasts.clear();

		final int group1 = 0;
		final Map< String, Object > guiState = new HashMap<>();
		guiState.put( MamutView.GROUP_HANDLE_ID_KEY, group1 );
		MamutViewBdv bdvView = findExistingView( MamutViewBdv.class, group1 );
		if ( bdvView == null )
			bdvView = projectModel.getWindowManager().createBigDataViewer( guiState );
		final ViewerPanel viewer = bdvView.getViewerPanelMamut();
		final ToolContext context = new ToolContext( projectModel, viewer );

		// Snap to the same WINDOW_SIZE-aligned block as FocusWindow, from the current BDV timepoint.
		final int current = viewer.state().getCurrentTimepoint();
		final int maxTp = projectModel.getMaxTimepoint();
		startTp = ( current / WINDOW_SIZE ) * WINDOW_SIZE;
		endTp = Math.min( startTp + WINDOW_SIZE - 1, maxTp );

		logWindow.show();
		logWindow.onLogMessage( "=== TP/FP labelling (t=" + startTp + ".." + endTp + ", from timepoint "
				+ current + ") ===" );

		final JDialog dialog = new JDialog( ( Frame ) null, "Label detections TP/FP by OK/NG example", false );
		dialog.setAlwaysOnTop( true );
		final JLabel status = new JLabel();
		final JPanel content = new JPanel( new BorderLayout( 8, 8 ) );
		content.setBorder( BorderFactory.createEmptyBorder( 12, 12, 12, 12 ) );

		// Wrap text at a fixed pixel width (plain HTML has no width limit and would otherwise stretch
		// the dialog as wide as the whole paragraph) and stack instructions above status vertically so
		// the two never overlap.
		final JLabel instructions = new JLabel( "<html><body style='width: 420px'>"
				+ "Select a spot in BDV, then click a button.<br>"
				+ "OK = a good cell (→ tp); NG = a background/false detection (→ fp).<br>"
				+ "Each click ADDS one more example (averaged) — pick several of each for a robust "
				+ "midpoint. \"Label TP/FP\" tags every spot from the current average; re-press it any "
				+ "time after adding/resetting examples to re-check. \"Reset\" clears both counts.<br>"
				+ "\"Auto-label (by quality)\" needs NO OK/NG examples — it tags spots directly from "
				+ "ELEPHANT's own detection confidence, where available."
				+ "</body></html>" );
		instructions.setAlignmentX( Component.LEFT_ALIGNMENT );
		status.setAlignmentX( Component.LEFT_ALIGNMENT );
		final JPanel top = new JPanel();
		top.setLayout( new BoxLayout( top, BoxLayout.Y_AXIS ) );
		top.add( instructions );
		top.add( javax.swing.Box.createVerticalStrut( 8 ) );
		top.add( status );
		content.add( top, BorderLayout.NORTH );

		final JButton setOk = new JButton( "Add OK = selected" );
		final JButton setNg = new JButton( "Add NG = selected" );
		final JButton reset = new JButton( "Reset OK/NG" );
		final JButton apply = new JButton( "Label TP/FP" );
		final JButton learn = new JButton( "Learn acceptance profile" );
		final JButton auto = new JButton( "Auto-label (profile)" );
		final JButton autoQuality = new JButton( "Auto-label (by quality)" );
		final JButton close = new JButton( "Close" );

		setOk.addActionListener( ev -> {
			final Double v = sampleSelectedContrast( context );
			if ( v != null )
			{
				okContrasts.add( v );
				updateStatus( status );
			}
		} );
		setNg.addActionListener( ev -> {
			final Double v = sampleSelectedContrast( context );
			if ( v != null )
			{
				ngContrasts.add( v );
				updateStatus( status );
			}
		} );
		reset.addActionListener( ev -> {
			okContrasts.clear();
			ngContrasts.clear();
			updateStatus( status );
		} );
		apply.addActionListener( ev -> {
			if ( okContrasts.isEmpty() || ngContrasts.isEmpty() )
			{
				logWindow.onLogMessage( "Add at least one OK and one NG example first." );
				return;
			}
			if ( AutoCorrectOrchestrator.examplesOverlap( okContrasts, ngContrasts ) )
			{
				logWindow.onLogMessage( "WARNING: the dimmest OK example's contrast is not brighter than the "
						+ "brightest NG example's — the two example sets overlap, so no single threshold can "
						+ "classify every example as intended. Consider Reset and re-picking (drop the "
						+ "ambiguous OK or NG spot)." );
			}
			final int[] counts = AutoCorrectOrchestrator.labelByContrastExample(
					context, startTp, endTp, okContrasts, ngContrasts );
			logWindow.onLogMessage( String.format(
					"Example labelling: %d spot(s) tagged tp (OK-like), %d tagged fp (background), "
							+ "from %d OK example(s) (avg=%.1f) and %d NG example(s) (avg=%.1f). Threshold is "
							+ "placed between the DIMMEST OK example and the BRIGHTEST NG example, so every "
							+ "registered example classifies as intended (unless the WARNING above fired). "
							+ "Retrain to learn these labels (fp spots are trained as explicit negatives).",
					counts[ 0 ], counts[ 1 ], okContrasts.size(), average( okContrasts ),
					ngContrasts.size(), average( ngContrasts ) ) );
		} );
		// Learn an autonomous accept/reject rule (intensity + contrast) from the tp/fp labels now present.
		learn.addActionListener( ev -> {
			final DetectionAcceptanceProfile prof =
					AutoCorrectOrchestrator.learnAcceptanceProfile( context, startTp, endTp );
			if ( !prof.usable() )
			{
				logWindow.onLogMessage( "Cannot learn: need both tp and fp labelled spots in this window "
						+ "(use Label TP/FP first)." );
				return;
			}
			try
			{
				final String path = new File( System.getProperty( "user.home" ),
						".automahout-acceptance.json" ).getAbsolutePath();
				prof.sourcePath = "t=" + startTp + ".." + endTp;
				prof.save( path );
				settings.setAcceptanceProfilePath( path );
				settings.save();
				logWindow.onLogMessage( prof.report() );
				logWindow.onLogMessage( "Saved and activated acceptance profile: " + path );
			}
			catch ( final Exception ex )
			{
				logWindow.onLogMessage( "Failed to save acceptance profile: " + ex.getMessage() );
			}
		} );
		// Autonomously tag the whole window using the active profile (no OK/NG picking).
		auto.addActionListener( ev -> {
			final String path = settings.getAcceptanceProfilePath();
			if ( path == null || path.trim().isEmpty() )
			{
				logWindow.onLogMessage( "No acceptance profile active — learn one first." );
				return;
			}
			try
			{
				final DetectionAcceptanceProfile prof = DetectionAcceptanceProfile.load( path.trim() );
				final int[] c = AutoCorrectOrchestrator.applyAcceptanceProfile( context, startTp, endTp, prof );
				logWindow.onLogMessage( String.format(
						"Auto-label (profile): %d accepted (tp), %d rejected (fp). %s",
						c[ 0 ], c[ 1 ], prof.confidenceNote() ) );
			}
			catch ( final Exception ex )
			{
				logWindow.onLogMessage( "Auto-label failed: " + ex.getMessage() );
			}
		} );
		// Autonomously tag from ELEPHANT's OWN per-spot quality — no OK/NG examples needed at all,
		// unlike every other button here. Spots without a recorded quality (older server response, or
		// added via a non-prediction path) are left untagged; use Label TP/FP for those.
		autoQuality.addActionListener( ev -> {
			final int[] c = AutoCorrectOrchestrator.applyQualityThreshold( context, startTp, endTp, 0.5 );
			logWindow.onLogMessage( String.format(
					"Auto-label (by quality): %d accepted (tp), %d rejected (fp), %d spot(s) had no "
							+ "recorded quality and were left untagged — use \"Label TP/FP\" for those.",
					c[ 0 ], c[ 1 ], c[ 2 ] ) );
		} );
		close.addActionListener( ev -> dialog.dispose() );

		final JPanel buttons = new JPanel( new GridLayout( 0, 2, 6, 6 ) );
		buttons.add( setOk );
		buttons.add( setNg );
		buttons.add( reset );
		buttons.add( apply );
		buttons.add( learn );
		buttons.add( auto );
		buttons.add( autoQuality );
		buttons.add( close );
		content.add( buttons, BorderLayout.SOUTH );

		updateStatus( status );
		dialog.setContentPane( content );
		dialog.pack();
		dialog.setLocation( 40, 40 );
		dialog.setVisible( true );
	}

	private void updateStatus( final JLabel status )
	{
		final String ok = okContrasts.isEmpty() ? "(none yet)"
				: String.format( "%d example(s), avg contrast=%.1f", okContrasts.size(), average( okContrasts ) );
		final String ng = ngContrasts.isEmpty() ? "(none yet)"
				: String.format( "%d example(s), avg contrast=%.1f", ngContrasts.size(), average( ngContrasts ) );
		status.setText( String.format( "<html>OK: %s<br>NG: %s</html>", ok, ng ) );
	}

	private static double average( final List< Double > values )
	{
		double sum = 0;
		for ( final double v : values )
			sum += v;
		return sum / values.size();
	}

	/**
	 * Samples the LOCAL CONTRAST (not absolute intensity — see class docs) of the currently-selected
	 * spot, or returns null (with a log note).
	 */
	private Double sampleSelectedContrast( final ToolContext context )
	{
		final SelectionModel< Spot, ? > selection = projectModel.getSelectionModel();
		final ModelGraph graph = projectModel.getModel().getGraph();
		final double[] pos = new double[ 3 ];
		double radius = 0.0;
		int timepoint = -1;
		graph.getLock().readLock().lock();
		try
		{
			final java.util.Iterator< Spot > it = selection.getSelectedVertices().iterator();
			if ( !it.hasNext() )
			{
				logWindow.onLogMessage( "No spot selected in BDV — click a spot first." );
				return null;
			}
			final Spot s = it.next();
			s.localize( pos );
			radius = Math.sqrt( s.getBoundingSphereRadiusSquared() );
			timepoint = s.getTimepoint();
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return AutoCorrectOrchestrator.sampleContrast( context, timepoint, pos, radius );
	}

	private < T extends MamutViewI > T findExistingView( final Class< T > klass, final int groupId )
	{
		for ( final T view : projectModel.getWindowManager().getViewList( klass ) )
		{
			if ( view.getGroupHandle().getGroupId() == groupId )
				return view;
		}
		return null;
	}
}
