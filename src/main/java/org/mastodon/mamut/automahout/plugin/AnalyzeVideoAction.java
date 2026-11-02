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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.swing.SwingWorker;

import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.impl.AnalyzeVideoTool;
import org.mastodon.mamut.views.MamutView;
import org.mastodon.mamut.views.MamutViewI;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import bdv.viewer.ViewerPanel;

/**
 * Menu action that runs {@link AnalyzeVideoTool} in a background thread.
 *
 * <p>Opens (or reuses) a BDV window, captures representative snapshots, sends
 * them to the configured VLM backend, and writes the resulting Markdown
 * description to the output file configured in
 * {@link VlmSettingsDialog VLM/LLM Settings}. Progress is shown in the
 * Automahout log window.</p>
 */
class AnalyzeVideoAction extends AbstractNamedAction
{

	private static final long serialVersionUID = 1L;

	private final ProjectModel projectModel;

	private final AutomahoutLogWindow logWindow;

	AnalyzeVideoAction( final ProjectModel projectModel,
			final AutomahoutLogWindow logWindow )
	{
		super( MastodonAutomahoutPlugin.ACTION_ANALYZE_VIDEO_NAME );
		this.projectModel = projectModel;
		this.logWindow = logWindow;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		logWindow.show();
		logWindow.onLogMessage( "=== Video analysis started. ===" );

		// Get or open a BDV window in group 1 (same convention as other actions)
		final int group1 = 0;
		final Map< String, Object > guiState = new HashMap<>();
		guiState.put( MamutView.GROUP_HANDLE_ID_KEY, group1 );

		MamutViewBdv bdvView = findExistingView( MamutViewBdv.class, group1 );
		if ( bdvView == null )
			bdvView = projectModel.getWindowManager().createBigDataViewer( guiState );
		final ViewerPanel viewer = bdvView.getViewerPanelMamut();

		final ToolContext context = new ToolContext( projectModel, viewer );

		// Run the tool on a background thread: it blocks for screenshot capture
		// and the VLM HTTP request, but handles EDT synchronisation internally.
		new SwingWorker< String, Void >()
		{
			@Override
			protected String doInBackground()
			{
				return new AnalyzeVideoTool().execute( context, Collections.emptyMap() );
			}

			@Override
			protected void done()
			{
				try
				{
					final String result = get();
					if ( result != null && result.startsWith( "Error:" ) )
					{
						logWindow.onLogMessage( result );
					}
					else
					{
						// Log a brief summary; the full Markdown is saved to a file.
						final String preview = result == null ? ""
								: result.substring( 0, Math.min( 300, result.length() ) );
						logWindow.onLogMessage( preview
								+ ( result != null && result.length() > 300 ? "\n[...truncated — see output file]" : "" ) );
						logWindow.onLogMessage( "=== Video analysis complete. ===" );
					}
				}
				catch ( final Exception ex )
				{
					logWindow.onLogMessage( "Video analysis error: " + ex.getMessage() );
				}
			}
		}.execute();
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
}
