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
import java.util.ArrayList;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.mastodon.mamut.ProjectModel;
import org.elephant.setting.main.ElephantMainSettingsManager;
import org.mastodon.mamut.automahout.elephant.DetectionTagLabels;
import org.mastodon.mamut.automahout.eval.LineageGraphData;
import org.mastodon.mamut.automahout.eval.ModelGraphAdapter;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.TagSetStructure;
import org.mastodon.model.tag.TagSetStructure.TagSet;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

/**
 * Menu action that writes the spots a HUMAN has confirmed — those carrying a chosen tag, e.g.
 * {@code Status > Completed} — to a GT annotation store and activates it, so that adaptive tracking
 * trains on those annotations instead of on its own detections.
 *
 * <p>This is the one lever that breaks the self-training loop. Without an active store,
 * {@code ElephantTool} trains each window on whatever the model just detected: runs have been
 * observed teaching it 569 positives per frame on a movie stated to hold 250 cells, with the
 * detections it kept measuring 9.3 µm against 11.1 µm for the confirmed cells, and the learned
 * feature separation falling run after run (0.77, 0.68, 0.64, 0.41, 0.34). No filter downstream of
 * detection can fix that, because every one of them only removes spots — none of them changes what
 * the model is taught a cell looks like.
 *
 * <p>Deliberately separate from {@code LearnConstraintsFromModelAction}, which snapshots the WHOLE
 * graph. After a tracking run the graph holds that run's detections alongside anything confirmed, and
 * writing those into the store would make the mechanism that is supposed to break the loop another
 * turn of it. Here the tag is the filter, and the count of what was written is reported so a
 * mis-chosen tag is visible immediately rather than after a training round.</p>
 */
class BuildGtStoreAction extends AbstractNamedAction
{

	private static final long serialVersionUID = 1L;

	private final ProjectModel projectModel;

	private final AutomahoutLogWindow logWindow;

	private final AutomahoutSettings settings;

	BuildGtStoreAction( final ProjectModel projectModel, final AutomahoutLogWindow logWindow,
			final AutomahoutSettings settings )
	{
		super( MastodonAutomahoutPlugin.ACTION_BUILD_GT_STORE_NAME );
		this.projectModel = projectModel;
		this.logWindow = logWindow;
		this.settings = settings;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		final Model model = projectModel.getModel();
		final ProtectedSpots.Config config = askForTag( model );
		if ( config == null )
			return;

		// Confirmed cells AND anything the user marked as background. An fp mark is a human saying "this
		// is not a cell", which is the most reliable negative in this pipeline and the one thing that
		// could never be recovered later — the synthesised negatives have sat at 2-3% of annotations and
		// depend on a VLM whose verdicts on these crops showed no discrimination at all.
		final LineageGraphData data = ModelGraphAdapter.snapshot( model.getGraph(),
				spot -> ProtectedSpots.isProtected( model, spot, config )
						|| DetectionTagLabels.FP.equals( DetectionTagLabels.labelOf( model, spot ) ),
				model );
		logWindow.show();
		if ( data.nodes.isEmpty() )
		{
			logWindow.onLogMessage( String.format( "No spot carries %s > %s — nothing written. The GT "
					+ "annotation store is unchanged.", config.tagSetName, config.tagLabel ) );
			return;
		}

		final JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle( "Save GT annotation store as..." );
		chooser.setSelectedFile( new File( "confirmed.gt.json" ) );
		if ( chooser.showSaveDialog( null ) != JFileChooser.APPROVE_OPTION )
			return;
		final File out = chooser.getSelectedFile();
		if ( out == null )
			return;
		final String outPath = out.getAbsolutePath();

		new SwingWorker< Integer, Void >()
		{
			@Override
			protected Integer doInBackground() throws Exception
			{
				data.save( outPath );
				settings.setGtAnnotationsPath( outPath );
				// Which dataset these annotations describe. The settings node is per USER, so without
				// this the next dataset opened inherits this store and trains on another movie's cells.
				settings.setGtAnnotationsDataset( ElephantMainSettingsManager.getInstance()
						.getSelectedStyle().getDatasetName() );
				settings.save();
				return data.nodes.size();
			}

			@Override
			protected void done()
			{
				try
				{
					final int n = get();
					int negatives = 0;
					for ( final LineageGraphData.Node node : data.nodes )
						if ( DetectionTagLabels.FP.equals( node.label ) )
							negatives++;
					logWindow.onLogMessage( String.format(
							"=== GT annotation store written and activated: %d spot(s) and %d link(s) "
									+ "(%d confirmed cells carrying %s > %s, %d marked Detection > fp) ===",
							n, data.edges.size(), n - negatives, config.tagSetName, config.tagLabel,
							negatives ) );
					if ( negatives == 0 )
						logWindow.onLogMessage( "  No spot is tagged Detection > fp. Marking a few obvious "
								+ "background detections that way and rebuilding gives training real "
								+ "negatives instead of only synthesised ones." );
					logWindow.onLogMessage( "  " + outPath );
					logWindow.onLogMessage( "  Adaptive tracking will now train on these annotations "
							+ "instead of its own detections, in every window that contains any. Windows "
							+ "with none fall back to guarded self-training as before — the run log says "
							+ "which, per window." );
					logWindow.onLogMessage( "  Re-run this action after confirming more spots; the store is "
							+ "a snapshot, not a live view of the tags." );
					logWindow.onLogMessage( "  Scoped to dataset '" + ElephantMainSettingsManager.getInstance()
							.getSelectedStyle().getDatasetName() + "' — opening a different dataset will "
							+ "ignore this store rather than train on it." );
				}
				catch ( final Exception ex )
				{
					logWindow.onLogMessage( "Error writing the GT annotation store: " + ex.getMessage() );
				}
			}
		}.execute();
	}

	/** Asks which tag marks a confirmed spot, pre-filled from the tag sets the project actually has. */
	private static ProtectedSpots.Config askForTag( final Model model )
	{
		final TagSetStructure tss = model.getTagSetModel().getTagSetStructure();
		final List< TagSet > tagSets = new ArrayList<>( tss.getTagSets() );
		if ( tagSets.isEmpty() )
		{
			JOptionPane.showMessageDialog( null,
					"This project has no tag sets, so there is no way to tell which spots you confirmed.\n"
							+ "Tag the confirmed spots first (e.g. Status > Completed), then run this again.",
					"No tag sets", JOptionPane.WARNING_MESSAGE );
			return null;
		}
		final JComboBox< String > setCombo = new JComboBox<>();
		for ( final TagSet ts : tagSets )
			setCombo.addItem( ts.getName() );
		final JComboBox< String > labelCombo = new JComboBox<>();
		final Runnable refreshLabels = () -> {
			labelCombo.removeAllItems();
			final Object selected = setCombo.getSelectedItem();
			for ( final TagSet ts : tagSets )
				if ( ts.getName().equals( selected ) )
					ts.getTags().forEach( tag -> labelCombo.addItem( tag.label() ) );
		};
		refreshLabels.run();
		setCombo.addActionListener( ev -> refreshLabels.run() );

		final JPanel panel = new JPanel();
		panel.setLayout( new BoxLayout( panel, BoxLayout.Y_AXIS ) );
		panel.add( new JLabel( "<html>Which tag marks a spot you have CONFIRMED is a cell?<br>"
				+ "Only those spots are written to the store, and adaptive tracking trains on them "
				+ "instead of on its own detections.</html>" ) );
		panel.add( Box.createVerticalStrut( 8 ) );
		panel.add( new JLabel( "Tag set:" ) );
		panel.add( setCombo );
		panel.add( new JLabel( "Tag label:" ) );
		panel.add( labelCombo );
		if ( JOptionPane.showConfirmDialog( null, panel, "Build GT annotation store",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE ) != JOptionPane.OK_OPTION )
			return null;
		return ProtectedSpots.Config.of( String.valueOf( setCombo.getSelectedItem() ),
				String.valueOf( labelCombo.getSelectedItem() ) );
	}
}
