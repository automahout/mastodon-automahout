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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * The one settings dialog of the Automahout plugin, in two tabs: <em>VLM / LLM</em> (the vision-language model's
 * backend, model, connection and the pre-flight's snapshots) and <em>Tracking</em> (the correction passes'
 * parameters; docs/settings.md says where each takes effect). The timepoint range belongs to one run and is set
 * in the run's set-up, not here.
 *
 * <p>Edits an {@link AutomahoutSettings} instance in place. Changes are applied and persisted only when the user
 * clicks <em>OK</em>; <em>Cancel</em> leaves the settings unchanged. The URL, API-key and region rows show only for
 * the backend that uses them.</p>
 *
 * <p>Use the static {@link #show(AutomahoutSettings)} factory method rather than constructing directly.</p>
 */
class AutomahoutSettingsDialog extends JDialog
{

	private static final long serialVersionUID = 1L;

	private static final String BACKEND_OLLAMA = "ollama";

	private static final String BACKEND_ANTHROPIC = "anthropic";

	private static final String BACKEND_BEDROCK = "bedrock";

	// ---- VLM / LLM ------------------------------------------------------------

	private final JComboBox< String > cbBackend;

	private final JTextField tfModel;

	private final JTextField tfBaseUrl;

	private final JLabel lbBaseUrl;

	private final JPasswordField pfApiKey;

	private final JLabel lbApiKey;

	private final JTextField tfAwsRegion;

	private final JLabel lbAwsRegion;

	private final JSpinner spMaxSnapshots;

	private final JSpinner spZSlices;

	private final JTextField tfOutputFile;

	// ---- Tracking -------------------------------------------------------------

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

	// -----------------------------------------------------------------------

	private AutomahoutSettingsDialog( final Frame owner, final AutomahoutSettings settings )
	{
		super( owner, "Automahout Settings", true );

		// ---- VLM / LLM controls ---------------------------------------------

		cbBackend = new JComboBox<>( new String[] { BACKEND_OLLAMA, BACKEND_ANTHROPIC, BACKEND_BEDROCK } );
		cbBackend.setSelectedItem( settings.getVlmBackend() );
		cbBackend.setToolTipText(
				"'ollama' — local Ollama server (no API key needed); "
						+ "'anthropic' — Anthropic cloud API (requires API key); "
						+ "'bedrock' — Claude via Amazon Bedrock (AWS credentials + region)" );

		tfModel = new JTextField( settings.getVlmModel(), 24 );
		tfModel.setToolTipText(
				"Ollama: e.g. llava, llava:13b, minicpm-v  |  "
						+ "Anthropic: e.g. claude-opus-4-8, claude-sonnet-5  |  "
						+ "Bedrock: model/inference-profile id, e.g. "
						+ "anthropic.claude-3-5-sonnet-20241022-v2:0 or us.anthropic.claude-opus-4-...-v1:0" );

		tfBaseUrl = new JTextField( settings.getVlmBaseUrl(), 24 );
		tfBaseUrl.setToolTipText( "Base URL of the Ollama server (default: http://localhost:11434)" );
		lbBaseUrl = new JLabel( "Ollama base URL:" );

		pfApiKey = new JPasswordField( settings.getVlmApiKey(), 24 );
		pfApiKey.setToolTipText(
				"Anthropic API key.  Leave empty to use the ANTHROPIC_API_KEY environment variable." );
		lbApiKey = new JLabel( "Anthropic API key:" );

		tfAwsRegion = new JTextField( settings.getVlmAwsRegion(), 24 );
		tfAwsRegion.setToolTipText(
				"AWS region hosting the Bedrock model (e.g. ap-northeast-1). Auth: set a Bedrock API key "
						+ "via the AWS_BEARER_TOKEN_BEDROCK env var, or use IAM credentials from the SDK "
						+ "default chain (env vars, ~/.aws/credentials, IAM role)." );
		lbAwsRegion = new JLabel( "AWS region:" );

		spMaxSnapshots = new JSpinner( new SpinnerNumberModel( settings.getVlmMaxSnapshots(), 1, 64, 1 ) );
		spMaxSnapshots.setToolTipText( "Number of BDV screenshots captured and sent to the model by the pre-flight" );

		spZSlices = new JSpinner( new SpinnerNumberModel( settings.getVlmZSlicesPerTimepoint(), 1, 16, 1 ) );
		spZSlices.setToolTipText(
				"Number of Z slices captured per timepoint (evenly spaced across the Z stack); "
						+ "1 = current slice only" );

		tfOutputFile = new JTextField( settings.getVlmOutputFile(), 28 );
		tfOutputFile.setToolTipText(
				"Path of the Markdown file written by the video.analyze tool "
						+ "(relative to the working directory or absolute)" );

		// ---- Tracking controls ----------------------------------------------

		spMergeOverlapRatio = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectMergeOverlapRatio(), 0.0, 5.0, 0.1 ) );
		spMergeOverlapRatio.setToolTipText( "Merge spots when distance < ratio × max(r1,r2).  0.0 = disabled" );

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
		spLongLinkFactor.setToolTipText( "Prune links whose distance exceeds this factor × max(r_source, r_target)" );

		spMinIntensityRatio = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectMinIntensityRatio(), 0.0, 1.0, 0.05 ) );
		spMinIntensityRatio.setToolTipText(
				"Delete spots whose intensity < ratio × median intensity at timepoint.  0.0 = disabled" );

		cbRefitAnisotropic = new JCheckBox( "", settings.isCorrectRefitAnisotropic() );
		cbRefitAnisotropic.setToolTipText( "Refit spots with anisotropic covariance via ELEPHANT /seg/predict" );

		spAnisotropyThreshold = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectAnisotropyThreshold(), 1.0, 1000.0, 1.0 ) );
		spAnisotropyThreshold.setToolTipText( "Refit when max(diag(cov)) / min(diag(cov)) exceeds this threshold" );

		spMinSizeRatio = new JSpinner( new SpinnerNumberModel( settings.getCorrectMinSizeRatio(), 0.0, 1.0, 0.05 ) );
		spMinSizeRatio.setToolTipText(
				"Delete spots whose bounding-sphere radius < ratio × median radius at timepoint.  0.0 = disabled" );

		spDivisionLinkFactor = new JSpinner( new SpinnerNumberModel(
				settings.getCorrectDivisionLinkFactor(), 0.0, 20.0, 0.5 ) );
		spDivisionLinkFactor.setToolTipText(
				"Link orphan track starts to a nearby dividing mother at t-1 within factor × max(r_orphan, r_mother).  0.0 = disabled" );

		// ---- lay out ---------------------------------------------------------

		final Form vlm = new Form();
		vlm.section( "Backend" );
		vlm.row( "Backend:", cbBackend );
		vlm.section( "Model" );
		vlm.row( "Model name / tag:", tfModel );
		vlm.section( "Connection" );
		vlm.row( lbBaseUrl, tfBaseUrl );
		vlm.row( lbApiKey, pfApiKey );
		vlm.row( lbAwsRegion, tfAwsRegion );
		vlm.section( "Pre-flight analysis" );
		vlm.row( "Max snapshots:", spMaxSnapshots );
		vlm.row( "Z slices / timepoint:", spZSlices );
		vlm.row( "Output file:", tfOutputFile );
		vlm.fill();

		final Form tracking = new Form();
		tracking.section( "Correction" );
		tracking.row( "Merge overlap ratio (0 = off):", spMergeOverlapRatio );
		tracking.row( "Fill track gaps:", cbFillGaps );
		tracking.row( "Gap crop factor:", spGapCropFactor );
		tracking.row( "Min tracklet length (0=off):", spMinTrackletLength );
		tracking.row( "Long-link factor:", spLongLinkFactor );
		tracking.row( "Min intensity ratio (0 = off):", spMinIntensityRatio );
		tracking.row( "Min size ratio (0 = off):", spMinSizeRatio );
		tracking.row( "Refit anisotropic spots:", cbRefitAnisotropic );
		tracking.row( "Anisotropy threshold:", spAnisotropyThreshold );
		tracking.row( "Division link factor (0 = off):", spDivisionLinkFactor );
		tracking.fill();

		final JTabbedPane tabs = new JTabbedPane();
		tabs.addTab( "VLM / LLM", vlm.panel );
		tabs.addTab( "Tracking", tracking.panel );

		final JButton okButton = new JButton( "OK" );
		final JButton cancelButton = new JButton( "Cancel" );
		okButton.addActionListener( e -> {
			applyToSettings( settings );
			settings.save();
			dispose();
		} );
		cancelButton.addActionListener( e -> dispose() );
		final JPanel buttons = new JPanel();
		buttons.add( okButton );
		buttons.add( cancelButton );

		final JPanel content = new JPanel( new BorderLayout() );
		content.setBorder( BorderFactory.createEmptyBorder( 8, 8, 4, 8 ) );
		content.add( tabs, BorderLayout.CENTER );
		content.add( buttons, BorderLayout.SOUTH );

		cbBackend.addActionListener( e -> updateVisibility() );
		updateVisibility();

		getContentPane().add( content );
		pack();
		setLocationRelativeTo( owner );
		getRootPane().setDefaultButton( okButton );
	}

	// -----------------------------------------------------------------------

	/**
	 * Opens the settings dialog.
	 *
	 * @param settings the settings object to edit; updated in-place on OK
	 */
	static void show( final AutomahoutSettings settings )
	{
		final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		final Frame owner = ( active instanceof Frame ) ? ( Frame ) active : null;
		SwingUtilities.invokeLater( () -> new AutomahoutSettingsDialog( owner, settings ).setVisible( true ) );
	}

	// -----------------------------------------------------------------------

	private void updateVisibility()
	{
		final Object sel = cbBackend.getSelectedItem();
		final boolean isOllama = BACKEND_OLLAMA.equals( sel );
		final boolean isAnthropic = BACKEND_ANTHROPIC.equals( sel );
		final boolean isBedrock = BACKEND_BEDROCK.equals( sel );
		lbBaseUrl.setVisible( isOllama );
		tfBaseUrl.setVisible( isOllama );
		lbApiKey.setVisible( isAnthropic );
		pfApiKey.setVisible( isAnthropic );
		lbAwsRegion.setVisible( isBedrock );
		tfAwsRegion.setVisible( isBedrock );
	}

	private void applyToSettings( final AutomahoutSettings settings )
	{
		settings.setVlmBackend( ( String ) cbBackend.getSelectedItem() );
		settings.setVlmModel( tfModel.getText().trim() );
		settings.setVlmBaseUrl( tfBaseUrl.getText().trim() );
		settings.setVlmApiKey( new String( pfApiKey.getPassword() ) );
		settings.setVlmAwsRegion( tfAwsRegion.getText().trim() );
		settings.setVlmMaxSnapshots( ( Integer ) spMaxSnapshots.getValue() );
		settings.setVlmZSlicesPerTimepoint( ( Integer ) spZSlices.getValue() );
		settings.setVlmOutputFile( tfOutputFile.getText().trim() );

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

	/** One tab's label/field grid, with bold section headers. */
	private static final class Form
	{
		final JPanel panel = new JPanel( new GridBagLayout() );

		private int y = 0;

		Form()
		{
			panel.setBorder( BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) );
		}

		void section( final String title )
		{
			final JLabel header = new JLabel( "<html><b>" + title + "</b></html>" );
			header.setBorder( BorderFactory.createMatteBorder( 0, 0, 1, 0, header.getForeground() ) );
			final GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = y++;
			c.gridwidth = 2;
			c.anchor = GridBagConstraints.LINE_START;
			c.fill = GridBagConstraints.HORIZONTAL;
			c.insets = new Insets( 10, 4, 2, 4 );
			panel.add( header, c );
		}

		void row( final String label, final Component field )
		{
			row( new JLabel( label ), field );
		}

		void row( final JLabel label, final Component field )
		{
			final GridBagConstraints lc = new GridBagConstraints();
			lc.gridx = 0;
			lc.gridy = y;
			lc.anchor = GridBagConstraints.LINE_END;
			lc.insets = new Insets( 3, 4, 3, 6 );
			panel.add( label, lc );
			final GridBagConstraints fc = new GridBagConstraints();
			fc.gridx = 1;
			fc.gridy = y++;
			fc.anchor = GridBagConstraints.LINE_START;
			fc.fill = GridBagConstraints.HORIZONTAL;
			fc.weightx = 1.0;
			fc.insets = new Insets( 3, 0, 3, 4 );
			panel.add( field, fc );
		}

		/** Pushes the rows to the top when the other tab is taller. */
		void fill()
		{
			final GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = y++;
			c.weighty = 1.0;
			panel.add( new JPanel(), c );
		}
	}
}
