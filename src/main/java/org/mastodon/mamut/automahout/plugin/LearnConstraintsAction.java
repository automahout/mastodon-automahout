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
import java.io.File;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

import org.mastodon.mamut.automahout.eval.GeffGtLoader;
import org.mastodon.mamut.automahout.eval.LineageConstraintLearner;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.eval.LineageConstraintProfile;
import org.mastodon.mamut.automahout.eval.LineageGraphData;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

/**
 * Menu action that learns a {@link LineageConstraintProfile} from a ground-truth {@code .geff}
 * store. The user picks the {@code .geff} directory; the profile is computed in the background,
 * saved as {@code <geff>.constraints.json} next to it, and its summary is printed to the Automahout
 * log window.
 *
 * <p>This is the "give the ground truth" entry point: the learned profile is meant to be reused on
 * new, GT-free data (fed into {@code auto.correct} and the LLM prompt in a later step), so the GT is
 * only needed once, at learning time.</p>
 */
class LearnConstraintsAction extends AbstractNamedAction
{

	private static final long serialVersionUID = 1L;

	private final AutomahoutLogWindow logWindow;

	private final AutomahoutSettings settings;

	LearnConstraintsAction( final AutomahoutLogWindow logWindow, final AutomahoutSettings settings )
	{
		super( MastodonAutomahoutPlugin.ACTION_LEARN_CONSTRAINTS_NAME );
		this.logWindow = logWindow;
		this.settings = settings;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		// A .geff store is a Zarr directory, so choose a directory.
		final JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle( "Select ground-truth .geff store (directory)" );
		chooser.setFileSelectionMode( JFileChooser.DIRECTORIES_ONLY );
		if ( chooser.showOpenDialog( null ) != JFileChooser.APPROVE_OPTION )
			return;
		final File dir = chooser.getSelectedFile();
		if ( dir == null )
			return;
		final String geffPath = dir.getAbsolutePath();

		// Sparse GT (only some cells annotated) invalidates density/rate statistics; ask so the profile
		// can suppress those and keep only the relational (motion/topology/division-geometry) rules.
		final boolean sparse = JOptionPane.showConfirmDialog( null,
				"Is this annotation SPARSE (only some cells labelled, not every cell)?\n"
						+ "Choose Yes for sparse GT: cell-count and division-rate statistics will be treated "
						+ "as non-representative and not enforced.",
				"Sparse annotation?", JOptionPane.YES_NO_OPTION ) == JOptionPane.YES_OPTION;

		logWindow.show();
		logWindow.onLogMessage( "=== Learning lineage constraints from GT: " + geffPath + " ===" );

		new SwingWorker< LineageConstraintProfile, Void >()
		{
			@Override
			protected LineageConstraintProfile doInBackground() throws Exception
			{
				// scale defaults to 1.0; expose a scale prompt in a later iteration if GT units differ.
				final double scale = 1.0;
				final GeffGtLoader.GtGraph gt = GeffGtLoader.load( geffPath );
				final LineageGraphData data = LineageConstraintLearner.toLineageGraphData( gt, scale );
				final LineageConstraintProfile profile = LineageConstraintLearner.learn( data, 1.0 );
				profile.sourcePath = geffPath;
				profile.sparseAnnotation = sparse;
				// Scope it to this movie — see LineageConstraintProfile.dataset.
				profile.dataset = AutoCorrectOrchestrator.currentElephantDataset();
				final String out = geffPath + ".constraints.json";
				profile.save( out );
				// Persist the GT spots (world units) as a trusted annotation store for adaptive_track.
				final String gtPath = geffPath + ".gt.json";
				data.save( gtPath );
				// Activate both so auto.correct / autonomous tracking pick them up automatically.
				settings.setConstraintsProfilePath( out );
				settings.setGtAnnotationsPath( gtPath );
				settings.save();
				return profile;
			}

			@Override
			protected void done()
			{
				try
				{
					final LineageConstraintProfile profile = get();
					logWindow.onLogMessage( profile.report() );
					logWindow.onLogMessage( "Saved and activated profile: " + geffPath + ".constraints.json" );
					logWindow.onLogMessage( "Saved and activated GT annotation store: " + geffPath + ".gt.json" );
					logWindow.onLogMessage( "=== Lineage constraint learning complete. ===" );
				}
				catch ( final Exception ex )
				{
					final Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					logWindow.onLogMessage( "Constraint learning error: " + cause.getMessage() );
				}
			}
		}.execute();
	}
}
