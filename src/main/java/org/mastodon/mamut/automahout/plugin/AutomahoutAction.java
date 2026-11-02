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

import java.awt.event.ActionEvent;
import java.util.HashMap;
import java.util.Map;

import javax.swing.SwingUtilities;

import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.views.MamutView;
import org.mastodon.mamut.views.MamutViewI;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.mastodon.mamut.views.trackscheme.MamutViewTrackScheme;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import bdv.viewer.ViewerPanel;

/**
 * Entry-point action for the Automahout plugin.
 *
 * <p>Opens a BDV window (if none is already open) and starts the
 * {@link AutomahoutLoop}, which iterates over every timepoint and adds up to
 * {@value AutomahoutLoop#MAX_SPOTS} spots per timepoint at the locations of
 * the brightest local intensity maxima in the visible region.</p>
 *
 * <p>The loop can be interrupted at any time by invoking
 * {@link AutomahoutStopAction} (menu: <em>Plugins &gt; Automahout &gt;
 * Stop Automahout</em>) or by calling {@link AutomahoutLoop#stop()} directly.
 * </p>
 */
public class AutomahoutAction extends AbstractNamedAction
{

	private static final long serialVersionUID = 1L;

	private final ProjectModel projectModel;

	private final AutomahoutLoop loop;

	private final AutomahoutLogWindow logWindow;

	private final AutomahoutSettings settings;

	/**
	 * Creates the run action.
	 *
	 * @param projectModel the active Mastodon project
	 * @param loop         the shared loop instance (also held by the stop action)
	 * @param logWindow    the progress window to show when the loop starts
	 * @param settings     plugin settings (read when the loop is started)
	 */
	public AutomahoutAction( final ProjectModel projectModel, final AutomahoutLoop loop,
			final AutomahoutLogWindow logWindow, final AutomahoutSettings settings )
	{
		super( MastodonAutomahoutPlugin.ACTION_NAME );
		this.projectModel = projectModel;
		this.loop = loop;
		this.logWindow = logWindow;
		this.settings = settings;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		// All UI and viewer access must happen on the EDT.
		SwingUtilities.invokeLater( this::startLoop );
	}

	// -----------------------------------------------------------------------

	private void startLoop()
	{
		logWindow.show();

		// Group 1 in Mastodon's UI = groupId 0.  Both BDV and TrackScheme
		// are assigned to the same group so they navigate in sync.
		final int group1 = 0;
		final Map< String, Object > guiState = new HashMap<>();
		guiState.put( MamutView.GROUP_HANDLE_ID_KEY, group1 );

		// Reuse an existing BDV window already in Group 1; open a new one if none.
		MamutViewBdv bdvView = findExistingView( MamutViewBdv.class, group1 );
		if ( bdvView == null )
			bdvView = projectModel.getWindowManager().createBigDataViewer( guiState );
		final ViewerPanel viewer = bdvView.getViewerPanelMamut();

		if ( settings.isOpenTrackScheme() )
		{
			// Same: reuse existing TrackScheme in Group 1 if one is already open.
			if ( findExistingView( MamutViewTrackScheme.class, group1 ) == null )
				projectModel.getWindowManager().createTrackScheme( guiState );
		}

		final ToolContext context = new ToolContext( projectModel, viewer );
		loop.start( context );
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
