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
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.util.HashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.impl.ElephantTool;
import org.mastodon.mamut.views.MamutView;
import org.mastodon.mamut.views.MamutViewI;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import bdv.viewer.ViewerPanel;

/**
 * Menu action that runs the focused single-window tracking pass
 * ({@link ElephantTool#trackWindowGtGuided}) on {@code t=0..9}: GT-train the detection model, detect
 * the whole image, link, then stop for the user to review the result in BDV. After each pass the user
 * is asked whether to retrain once more (human-in-the-loop) or finish.
 *
 * <p>This deliberately keeps the flow small and robust — no per-spot local re-prediction, no
 * refinement self-training, no cold-start flood — so a single window can be made reliable before the
 * full-movie pipeline is attempted.</p>
 */
class FocusWindowAction extends AbstractNamedAction
{

	private static final long serialVersionUID = 1L;

	private static final int WINDOW_SIZE = 10;

	private static final long TRAIN_TIMEOUT_MS = 15L * 60L * 1000L;

	/** Window processed by the current run, derived from the BDV timepoint (snapped to WINDOW_SIZE). */
	private int startTp;

	private int endTp;

	private final ProjectModel projectModel;

	private final AutomahoutLogWindow logWindow;

	private final AutomahoutSettings settings;

	/** Context and worker of the running pass, so the Stop button can cancel it. */
	private ToolContext currentContext;

	private SwingWorker< String, Void > currentWorker;

	FocusWindowAction( final ProjectModel projectModel, final AutomahoutLogWindow logWindow,
			final AutomahoutSettings settings )
	{
		super( MastodonAutomahoutPlugin.ACTION_FOCUS_WINDOW_NAME );
		this.projectModel = projectModel;
		this.logWindow = logWindow;
		this.settings = settings;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		logWindow.show();

		final int group1 = 0;
		final Map< String, Object > guiState = new HashMap<>();
		guiState.put( MamutView.GROUP_HANDLE_ID_KEY, group1 );
		MamutViewBdv bdvView = findExistingView( MamutViewBdv.class, group1 );
		if ( bdvView == null )
			bdvView = projectModel.getWindowManager().createBigDataViewer( guiState );
		final ViewerPanel viewer = bdvView.getViewerPanelMamut();

		// Window = the WINDOW_SIZE-aligned block containing the current BDV timepoint. Navigate BDV to a
		// timepoint in the window you want (e.g. go to t=10 for t=10..19), then run. The TP/FP-labelling
		// action snaps to the same block, so both operate on the same window.
		final int current = viewer.state().getCurrentTimepoint();
		final int maxTp = projectModel.getMaxTimepoint();
		startTp = ( current / WINDOW_SIZE ) * WINDOW_SIZE;
		endTp = Math.min( startTp + WINDOW_SIZE - 1, maxTp );
		logWindow.onLogMessage( String.format(
				"=== Focused single-window tracking t=%d..%d (from current timepoint %d) ===",
				startTp, endTp, current ) );

		final ToolContext context = new ToolContext( projectModel, viewer );
		context.setLogCallback( logWindow::onLogMessage ); // so the [1/4]..[4/4] progress lines are shown
		currentContext = context;
		logWindow.onLogMessage( "Each pass trains on the spots currently in the window (the GT/labels on "
				+ "pass 1; after you label TP/FP, the corrected set). To advance later, move BDV to the next "
				+ "window and run again — the model keeps improving. Use Stop to abort a running pass." );
		runPass( context, viewer, 1 );
	}

	/** Requests cancellation of a running pass (wired to the Stop button). */
	void cancel()
	{
		if ( currentContext != null )
			currentContext.requestCancel();
		if ( currentWorker != null )
			currentWorker.cancel( true );
	}

	/** Runs one train→detect→link pass off the EDT, then asks the user whether to retrain. */
	private void runPass( final ToolContext context, final ViewerPanel viewer, final int iteration )
	{
		logWindow.onLogMessage( "--- Pass " + iteration + " ---" );
		logWindow.enableStop(); // a pass is now running — allow Stop
		final SwingWorker< String, Void > worker = new SwingWorker< String, Void >()
		{
			@Override
			protected String doInBackground()
			{
				// nEpochs=0 → ELEPHANT Preferences value; nCrops=10; dedup=0.4; minSize=0; trackIntensity=0.3;
				// qualityThreshold=0.5 matches ELEPHANT's own prob_threshold request default.
				return ElephantTool.trackWindowGtGuided( context, viewer, startTp, endTp,
						0, 10, TRAIN_TIMEOUT_MS, 0.4, 0.0, 0.3, 0.5 );
			}

			@Override
			protected void done()
			{
				logWindow.disableStop(); // pass finished (or cancelled) — nothing to stop until next pass
				if ( isCancelled() )
				{
					logWindow.onLogMessage( "=== Focused single-window tracking stopped by user. ===" );
					return;
				}
				final String result;
				try
				{
					result = get();
				}
				catch ( final Exception ex )
				{
					final Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					logWindow.onLogMessage( "Focused tracking error: " + cause.getMessage() );
					return;
				}
				logWindow.onLogMessage( result );
				promptRetrain( context, viewer, iteration, result );
			}
		};
		currentWorker = worker;
		worker.execute();
	}

	/**
	 * Shows a MODELESS dialog with explicit Yes/No buttons so the user can freely pan/scroll the BDV
	 * window to inspect the detections before choosing (a modal dialog would block BDV). Explicit
	 * {@link JButton} handlers are used instead of a {@code JOptionPane}, whose modeless value-property
	 * listener races with the internal close handler. "Retrain" runs another pass; "Finish" ends.
	 */
	private void promptRetrain( final ToolContext context, final ViewerPanel viewer, final int iteration,
			final String result )
	{
		final JDialog dialog = new JDialog( ( Frame ) null, "Focused window — retrain?", false );
		dialog.setAlwaysOnTop( true );

		final JPanel content = new JPanel( new BorderLayout( 8, 8 ) );
		content.setBorder( BorderFactory.createEmptyBorder( 12, 12, 12, 12 ) );
		content.add( new JLabel( "<html>Pass " + iteration + " complete (t=" + startTp + ".." + endTp
				+ ").<br>" + result + "<br><br>Inspect the detections in BDV, then choose:</html>" ),
				BorderLayout.CENTER );

		final JButton retrain = new JButton( "Retrain & re-detect" );
		final JButton finish = new JButton( "Finish" );
		retrain.addActionListener( ev -> {
			dialog.dispose();
			runPass( context, viewer, iteration + 1 );
		} );
		finish.addActionListener( ev -> {
			dialog.dispose();
			logWindow.onLogMessage( "=== Focused single-window tracking finished. ===" );
		} );

		final JPanel buttons = new JPanel();
		buttons.add( retrain );
		buttons.add( finish );
		content.add( buttons, BorderLayout.SOUTH );

		dialog.setContentPane( content );
		dialog.pack();
		dialog.setLocation( 40, 40 );
		dialog.setVisible( true );
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
