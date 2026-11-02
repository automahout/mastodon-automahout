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

import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.automahout.eval.LineageConstraintLearner;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.eval.LineageConstraintProfile;
import org.mastodon.mamut.automahout.eval.LineageGraphData;
import org.mastodon.mamut.automahout.eval.ModelGraphAdapter;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

/**
 * Menu action that learns a {@link LineageConstraintProfile} from the spots and links currently in
 * the Mastodon project (rather than from a {@code .geff} file). This lets any lineage that can be
 * imported into Mastodon serve as the ground truth for constraint learning, so multiple GT formats
 * are supported without a dedicated reader for each.
 *
 * <p>The snapshot is taken in the background; the user then picks where to save the resulting
 * {@code .constraints.json}, and its summary is printed to the Automahout log window.</p>
 */
class LearnConstraintsFromModelAction extends AbstractNamedAction
{

	private static final long serialVersionUID = 1L;

	private final ProjectModel projectModel;

	private final AutomahoutLogWindow logWindow;

	private final AutomahoutSettings settings;

	LearnConstraintsFromModelAction( final ProjectModel projectModel, final AutomahoutLogWindow logWindow,
			final AutomahoutSettings settings )
	{
		super( MastodonAutomahoutPlugin.ACTION_LEARN_CONSTRAINTS_MODEL_NAME );
		this.projectModel = projectModel;
		this.logWindow = logWindow;
		this.settings = settings;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		// Snapshot the graph on the EDT first (it is the current model the user sees), then compute
		// and save off the EDT.
		final LineageGraphData data = ModelGraphAdapter.snapshot( projectModel.getModel().getGraph() );
		if ( data.nodes.isEmpty() )
		{
			logWindow.show();
			logWindow.onLogMessage( "Error: the current model has no spots to learn from." );
			return;
		}

		final JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle( "Save learned constraints as..." );
		chooser.setSelectedFile( new File( "model.constraints.json" ) );
		if ( chooser.showSaveDialog( null ) != JFileChooser.APPROVE_OPTION )
			return;
		final File out = chooser.getSelectedFile();
		if ( out == null )
			return;
		final String outPath = out.getAbsolutePath();

		// Sparse GT (only some cells annotated) invalidates density/rate statistics; ask so the profile
		// can suppress those and keep only the relational (motion/topology/division-geometry) rules.
		final boolean sparse = JOptionPane.showConfirmDialog( null,
				"Is this annotation SPARSE (only some cells labelled, not every cell)?\n"
						+ "Choose Yes for sparse GT: cell-count and division-rate statistics will be treated "
						+ "as non-representative and not enforced.",
				"Sparse annotation?", JOptionPane.YES_NO_OPTION ) == JOptionPane.YES_OPTION;

		logWindow.show();
		logWindow.onLogMessage( "=== Learning lineage constraints from current model ("
				+ data.nodes.size() + " spots, " + data.edges.size() + " links) ===" );

		new SwingWorker< LineageConstraintProfile, Void >()
		{
			@Override
			protected LineageConstraintProfile doInBackground() throws Exception
			{
				// Mastodon spots are already in world units, so scale = 1.0.
				final LineageConstraintProfile profile = LineageConstraintLearner.learn( data, 1.0 );
				profile.sparseAnnotation = sparse;
				// Scope it to this movie — see LineageConstraintProfile.dataset.
				profile.dataset = AutoCorrectOrchestrator.currentElephantDataset();
				profile.save( outPath );
				// Also persist the GT spots themselves as a trusted annotation store, so adaptive_track
				// trains on real GT even across re-runs (it never mistakes its own detections for GT).
				final String gtPath = outPath + ".gt.json";
				data.save( gtPath );
				// Activate both so auto.correct / autonomous tracking pick them up automatically.
				settings.setConstraintsProfilePath( outPath );
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
					logWindow.onLogMessage( "Saved and activated profile: " + outPath );
					logWindow.onLogMessage( "Saved and activated GT annotation store: " + outPath + ".gt.json" );
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
