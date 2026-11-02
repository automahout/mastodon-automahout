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

import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * Modal settings dialog for the Automahout plugin.
 *
 * <p>Edits an {@link AutomahoutSettings} instance in-place. Changes are
 * applied and persisted only when the user clicks <em>OK</em>; clicking
 * <em>Cancel</em> leaves the settings object unchanged.</p>
 *
 * <p>Use the static {@link #show(AutomahoutSettings)} factory method rather
 * than constructing directly.</p>
 */
class AutomahoutSettingsDialog extends JDialog
{

	private static final long serialVersionUID = 1L;

	// ---- spinners -------------------------------------------------------------

	private final JSpinner spStartTimepoint;

	private final JSpinner spEndTimepoint;

	private final JCheckBox cbOpenTrackScheme;

	private final JSpinner spMaxSpots;

	private final JSpinner spMipmapLevel;

	private final JSpinner spStrideXY;

	private final JSpinner spStrideZ;

	private final JSpinner spSuppressionPx;

	private final JSpinner spSpotRadius;

	private final JSpinner spNavigateAnimMs;

	private final JSpinner spMergeOverlapRatio;


	private final JCheckBox cbFillGaps;

	private final JSpinner spGapCropFactor;

	private final JSpinner spMinTrackletLength;

	private final JSpinner spLongLinkFactor;

	private final JSpinner spMinIntensityRatio;

	private final JCheckBox cbRefitAnisotropic;

	private final JSpinner spAnisotropyThreshold;

	private final JSpinner spMinSizeRatio;

	private final JSpinner spDivisionLinkFactor;

	// ---- state ----------------------------------------------------------------

	private boolean confirmed = false;

	// -----------------------------------------------------------------------

	private AutomahoutSettingsDialog( final Frame owner, final AutomahoutSettings settings )
	{
		super( owner, "Automahout Settings", true );

		// ---- build spinners ---------------------------------------------------

		spStartTimepoint = new JSpinner( new SpinnerNumberModel(
				settings.getStartTimepoint(), -1, 9999, 1 ) );
		spStartTimepoint.setToolTipText(
				"First timepoint to process.  -1 = use the viewer's current timepoint when Start is clicked" );

		spEndTimepoint = new JSpinner( new SpinnerNumberModel(
				settings.getEndTimepoint(), -1, 9999, 1 ) );
		spEndTimepoint.setToolTipText(
				"Last timepoint to process (inclusive).  -1 = use the last timepoint in the dataset" );

		cbOpenTrackScheme = new JCheckBox( "", settings.isOpenTrackScheme() );
		cbOpenTrackScheme.setToolTipText(
				"Also open a TrackScheme window in the same group when the loop starts" );

		spMaxSpots = new JSpinner( new SpinnerNumberModel(
				settings.getMaxSpots(), 1, 1000, 1 ) );
		spMaxSpots.setToolTipText( "Maximum number of spots added per timepoint" );

		spMipmapLevel = new JSpinner( new SpinnerNumberModel(
				settings.getMipmapLevel(), -1, 20, 1 ) );
		spMipmapLevel.setToolTipText(
				"Mipmap (resolution) level for scanning. -1 = auto (coarsest / fastest), 0 = full resolution" );

		spStrideXY = new JSpinner( new SpinnerNumberModel(
				settings.getStrideXY(), 1, 64, 1 ) );
		spStrideXY.setToolTipText(
				"Voxel sampling stride in X and Y. Higher values scan faster at lower spatial resolution" );

		spStrideZ = new JSpinner( new SpinnerNumberModel(
				settings.getStrideZ(), 1, 64, 1 ) );
		spStrideZ.setToolTipText(
				"Voxel sampling stride in Z. Set to 1 to sample every z-plane" );

		spSuppressionPx = new JSpinner( new SpinnerNumberModel(
				settings.getSuppressionRadiusPx(), 1.0, 2000.0, 1.0 ) );
		spSuppressionPx.setToolTipText(
				"Non-maximum suppression radius in screen pixels. Keeps detected maxima well separated" );

		spSpotRadius = new JSpinner( new SpinnerNumberModel(
				settings.getSpotRadius(), 0.1, 10000.0, 0.5 ) );
		spSpotRadius.setToolTipText( "Radius of newly created spots in world units" );

		spNavigateAnimMs = new JSpinner( new SpinnerNumberModel(
				( int ) settings.getNavigateAnimMs(), 0, 5000, 50 ) );
		spNavigateAnimMs.setToolTipText(
				"Duration of the navigate-to pan animation in milliseconds (0 = instant)" );

		spMergeOverlapRatio = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectMergeOverlapRatio(), 0.0, 5.0, 0.1 ) );
		spMergeOverlapRatio.setToolTipText(
				"Merge spots when distance < ratio × max(r1,r2).  0.0 = disabled" );

		cbFillGaps = new JCheckBox( "", settings.isCorrectFillGaps() );
		cbFillGaps.setToolTipText(
				"For each dangling track end, run ELEPHANT locally at t+1 to detect and link the missing spot" );

		spGapCropFactor = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectGapCropFactor(), 1.0, 20.0, 0.5 ) );
		spGapCropFactor.setToolTipText(
				"Crop box half-width = factor × spot radius. Larger values give ELEPHANT more context" );

		spMinTrackletLength = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectMinTrackletLength(), 0, 9999, 1 ) );
		spMinTrackletLength.setToolTipText(
				"Min tracklet length before gap fill. Dangling tracklets shorter than this are deleted instead. 0 = off" );

		spLongLinkFactor = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectLongLinkFactor(), 0.5, 50.0, 0.5 ) );
		spLongLinkFactor.setToolTipText(
				"Prune links whose distance exceeds this factor × max(r_source, r_target)" );

		spMinIntensityRatio = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectMinIntensityRatio(), 0.0, 1.0, 0.05 ) );
		spMinIntensityRatio.setToolTipText(
				"Delete spots whose intensity < ratio × median intensity at timepoint.  0.0 = disabled" );

		cbRefitAnisotropic = new JCheckBox( "", settings.isCorrectRefitAnisotropic() );
		cbRefitAnisotropic.setToolTipText(
				"Refit spots with anisotropic covariance via ELEPHANT /seg/predict" );

		spAnisotropyThreshold = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectAnisotropyThreshold(), 1.0, 1000.0, 1.0 ) );
		spAnisotropyThreshold.setToolTipText(
				"Refit when max(diag(cov)) / min(diag(cov)) exceeds this threshold" );

		spMinSizeRatio = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectMinSizeRatio(), 0.0, 1.0, 0.05 ) );
		spMinSizeRatio.setToolTipText(
				"Delete spots whose bounding-sphere radius < ratio × median radius at timepoint.  0.0 = disabled" );

		spDivisionLinkFactor = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectDivisionLinkFactor(), 0.0, 20.0, 0.5 ) );
		spDivisionLinkFactor.setToolTipText(
				"Link orphan track starts to a nearby dividing mother at t-1 within factor × max(r_orphan, r_mother).  0.0 = disabled" );

		// ---- lay out ---------------------------------------------------------

		final JPanel content = new JPanel( new GridBagLayout() );
		content.setBorder( BorderFactory.createEmptyBorder( 8, 12, 4, 12 ) );
		final GridBagConstraints lc = labelConstraints();
		final GridBagConstraints fc = fieldConstraints();

		// Loop section
		addSectionHeader( content, "Loop", lc, fc );
		addRow( content, "Start timepoint (-1 = current):", spStartTimepoint, lc, fc );
		addRow( content, "End timepoint (-1 = last):", spEndTimepoint, lc, fc );
		addCheckBoxRow( content, "Open TrackScheme window:", cbOpenTrackScheme, lc, fc );

		// Detection section
		addSectionHeader( content, "Detection", lc, fc );
		addRow( content, "Max spots per timepoint:", spMaxSpots, lc, fc );
		addRow( content, "Mipmap level (-1 = auto):", spMipmapLevel, lc, fc );
		addRow( content, "Stride XY (voxels):", spStrideXY, lc, fc );
		addRow( content, "Stride Z (voxels):", spStrideZ, lc, fc );
		addRow( content, "Suppression radius (px):", spSuppressionPx, lc, fc );

		// Spot section
		addSectionHeader( content, "Spot", lc, fc );
		addRow( content, "Spot radius (world units):", spSpotRadius, lc, fc );

		// Navigation section
		addSectionHeader( content, "Navigation", lc, fc );
		addRow( content, "Navigate animation (ms):", spNavigateAnimMs, lc, fc );

		// Correction section
		addSectionHeader( content, "Correction", lc, fc );
		addRow( content, "Merge overlap ratio (0 = off):", spMergeOverlapRatio, lc, fc );
		addCheckBoxRow( content, "Fill track gaps:", cbFillGaps, lc, fc );
		addRow( content, "Gap crop factor:", spGapCropFactor, lc, fc );
		addRow( content, "Min tracklet length (0=off):", spMinTrackletLength, lc, fc );
		addRow( content, "Long-link factor:", spLongLinkFactor, lc, fc );
		addRow( content, "Min intensity ratio (0 = off):", spMinIntensityRatio, lc, fc );
		addRow( content, "Min size ratio (0 = off):", spMinSizeRatio, lc, fc );
		addCheckBoxRow( content, "Refit anisotropic spots:", cbRefitAnisotropic, lc, fc );
		addRow( content, "Anisotropy threshold:", spAnisotropyThreshold, lc, fc );
		addRow( content, "Division link factor (0 = off):", spDivisionLinkFactor, lc, fc );

		// Buttons
		final JButton okButton = new JButton( "OK" );
		final JButton cancelButton = new JButton( "Cancel" );
		okButton.addActionListener( e -> {
			applyToSettings( settings );
			settings.save();
			confirmed = true;
			dispose();
		} );
		cancelButton.addActionListener( e -> dispose() );

		final JPanel buttons = new JPanel();
		buttons.add( okButton );
		buttons.add( cancelButton );

		lc.gridwidth = 2;
		lc.anchor = GridBagConstraints.CENTER;
		lc.fill = GridBagConstraints.NONE;
		lc.insets = new Insets( 8, 4, 4, 4 );
		content.add( buttons, lc );

		getContentPane().add( content );
		pack();
		setLocationRelativeTo( owner );
		getRootPane().setDefaultButton( okButton );
	}

	// -----------------------------------------------------------------------

	/**
	 * Opens the settings dialog and blocks until the user closes it.
	 *
	 * @param settings the settings object to edit; updated in-place on OK
	 */
	static void show( final AutomahoutSettings settings )
	{
		final Window active =
				KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		final Frame owner = ( active instanceof Frame ) ? ( Frame ) active : null;
		SwingUtilities.invokeLater( () -> {
			final AutomahoutSettingsDialog dlg =
					new AutomahoutSettingsDialog( owner, settings );
			dlg.setVisible( true );
		} );
	}

	// -----------------------------------------------------------------------

	private void applyToSettings( final AutomahoutSettings settings )
	{
		settings.setStartTimepoint( ( Integer ) spStartTimepoint.getValue() );
		settings.setEndTimepoint( ( Integer ) spEndTimepoint.getValue() );
		settings.setOpenTrackScheme( cbOpenTrackScheme.isSelected() );
		settings.setMaxSpots( ( Integer ) spMaxSpots.getValue() );
		settings.setMipmapLevel( ( Integer ) spMipmapLevel.getValue() );
		settings.setStrideXY( ( Integer ) spStrideXY.getValue() );
		settings.setStrideZ( ( Integer ) spStrideZ.getValue() );
		settings.setSuppressionRadiusPx( ( Double ) spSuppressionPx.getValue() );
		settings.setSpotRadius( ( Double ) spSpotRadius.getValue() );
		settings.setNavigateAnimMs( ( ( Integer ) spNavigateAnimMs.getValue() ).longValue() );
		settings.setCorrectMergeOverlapRatio( ( Double ) spMergeOverlapRatio.getValue() );
		settings.setCorrectFillGaps( cbFillGaps.isSelected() );
		settings.setCorrectGapCropFactor( ( Double ) spGapCropFactor.getValue() );
		settings.setCorrectMinTrackletLength( ( Integer ) spMinTrackletLength.getValue() );
		settings.setCorrectLongLinkFactor( ( Double ) spLongLinkFactor.getValue() );
		settings.setCorrectMinIntensityRatio( ( Double ) spMinIntensityRatio.getValue() );
		settings.setCorrectMinSizeRatio( ( Double ) spMinSizeRatio.getValue() );
		settings.setCorrectRefitAnisotropic( cbRefitAnisotropic.isSelected() );
		settings.setCorrectAnisotropyThreshold( ( Double ) spAnisotropyThreshold.getValue() );
		settings.setCorrectDivisionLinkFactor( ( Double ) spDivisionLinkFactor.getValue() );
	}

	// -----------------------------------------------------------------------
	// Layout helpers

	private static GridBagConstraints labelConstraints()
	{
		final GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.LINE_END;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets( 3, 4, 3, 6 );
		return c;
	}

	private static GridBagConstraints fieldConstraints()
	{
		final GridBagConstraints c = new GridBagConstraints();
		c.gridx = 1;
		c.gridy = 0;
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.insets = new Insets( 3, 0, 3, 4 );
		return c;
	}

	private static void addSectionHeader(
			final JPanel panel,
			final String title,
			final GridBagConstraints lc,
			final GridBagConstraints fc )
	{
		final JLabel header = new JLabel( "<html><b>" + title + "</b></html>" );
		header.setBorder( BorderFactory.createMatteBorder( 0, 0, 1, 0,
				header.getForeground() ) );
		final GridBagConstraints hc = ( GridBagConstraints ) lc.clone();
		hc.gridwidth = 2;
		hc.anchor = GridBagConstraints.LINE_START;
		hc.fill = GridBagConstraints.HORIZONTAL;
		hc.insets = new Insets( 10, 4, 2, 4 );
		panel.add( header, hc );
		lc.gridy++;
		fc.gridy = lc.gridy;
	}

	private static void addRow(
			final JPanel panel,
			final String label,
			final JSpinner spinner,
			final GridBagConstraints lc,
			final GridBagConstraints fc )
	{
		panel.add( new JLabel( label ), lc );
		panel.add( spinner, fc );
		lc.gridy++;
		fc.gridy = lc.gridy;
	}

	private static void addCheckBoxRow(
			final JPanel panel,
			final String label,
			final JCheckBox checkBox,
			final GridBagConstraints lc,
			final GridBagConstraints fc )
	{
		panel.add( new JLabel( label ), lc );
		panel.add( checkBox, fc );
		lc.gridy++;
		fc.gridy = lc.gridy;
	}
}
