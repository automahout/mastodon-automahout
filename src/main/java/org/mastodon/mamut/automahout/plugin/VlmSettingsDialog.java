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
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * Modal settings dialog for VLM / LLM backend configuration.
 *
 * <p>Edits the VLM-related fields of an {@link AutomahoutSettings} instance
 * in-place. Changes are applied and persisted only when the user clicks
 * <em>OK</em>; clicking <em>Cancel</em> leaves the settings object
 * unchanged.</p>
 *
 * <p>The URL and API-key rows are shown or hidden dynamically based on the
 * selected backend.</p>
 *
 * <p>Use the static {@link #show(AutomahoutSettings)} factory method.</p>
 */
class VlmSettingsDialog extends JDialog
{

	private static final long serialVersionUID = 1L;

	private static final String BACKEND_OLLAMA = "ollama";

	private static final String BACKEND_ANTHROPIC = "anthropic";

	private static final String BACKEND_BEDROCK = "bedrock";

	// ---- controls -------------------------------------------------------

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

	// ---- layout state ---------------------------------------------------

	private final JPanel content;

	private final GridBagConstraints lc;

	private final GridBagConstraints fc;

	// -----------------------------------------------------------------------

	private VlmSettingsDialog( final Frame owner, final AutomahoutSettings settings )
	{
		super( owner, "Automahout VLM / LLM Settings", true );

		// ---- controls ---------------------------------------------------

		cbBackend = new JComboBox<>(
				new String[] { BACKEND_OLLAMA, BACKEND_ANTHROPIC, BACKEND_BEDROCK } );
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

		spMaxSnapshots = new JSpinner( new SpinnerNumberModel(
				settings.getVlmMaxSnapshots(), 1, 64, 1 ) );
		spMaxSnapshots.setToolTipText(
				"Number of BDV screenshots captured and sent to the model per analysis run" );

		spZSlices = new JSpinner( new SpinnerNumberModel(
				settings.getVlmZSlicesPerTimepoint(), 1, 16, 1 ) );
		spZSlices.setToolTipText(
				"Number of Z slices captured per timepoint (evenly spaced across the Z stack); "
						+ "1 = current slice only" );

		tfOutputFile = new JTextField( settings.getVlmOutputFile(), 28 );
		tfOutputFile.setToolTipText(
				"Path of the Markdown file written by the video.analyze tool "
						+ "(relative to the working directory or absolute)" );

		// ---- layout -----------------------------------------------------

		content = new JPanel( new GridBagLayout() );
		content.setBorder( BorderFactory.createEmptyBorder( 8, 12, 4, 12 ) );
		lc = labelConstraints();
		fc = fieldConstraints();

		addSectionHeader( "Backend" );
		addRow( "Backend:", cbBackend );

		addSectionHeader( "Model" );
		addRow( "Model name / tag:", tfModel );

		addSectionHeader( "Connection" );
		// Dynamic rows added here so we can reference their labels later
		content.add( lbBaseUrl, lc );
		content.add( tfBaseUrl, fc );
		lc.gridy++;
		fc.gridy = lc.gridy;

		content.add( lbApiKey, lc );
		content.add( pfApiKey, fc );
		lc.gridy++;
		fc.gridy = lc.gridy;

		content.add( lbAwsRegion, lc );
		content.add( tfAwsRegion, fc );
		lc.gridy++;
		fc.gridy = lc.gridy;

		addSectionHeader( "Analysis" );
		addRow( "Max snapshots:", spMaxSnapshots );
		addRow( "Z slices / timepoint:", spZSlices );
		addRow( "Output file:", tfOutputFile );

		// ---- OK / Cancel ------------------------------------------------

		final JButton okButton = new JButton( "OK" );
		final JButton cancelButton = new JButton( "Cancel" );
		okButton.addActionListener( e -> {
			applyToSettings( settings );
			settings.save();
			dispose();
		} );
		cancelButton.addActionListener( e -> dispose() );

		final GridBagConstraints bc = ( GridBagConstraints ) lc.clone();
		bc.gridwidth = 2;
		bc.anchor = GridBagConstraints.CENTER;
		bc.fill = GridBagConstraints.NONE;
		bc.insets = new Insets( 10, 4, 4, 4 );
		final JPanel buttons = new JPanel();
		buttons.add( okButton );
		buttons.add( cancelButton );
		content.add( buttons, bc );

		// ---- dynamic visibility -----------------------------------------

		cbBackend.addActionListener( e -> updateVisibility() );
		updateVisibility(); // apply initial state

		// ---- finalize ---------------------------------------------------

		getContentPane().add( content );
		pack();
		setLocationRelativeTo( owner );
		getRootPane().setDefaultButton( okButton );
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
	}

	// -----------------------------------------------------------------------
	// Static factory

	/**
	 * Opens the VLM settings dialog.
	 *
	 * @param settings settings object to edit; updated in-place on OK
	 */
	static void show( final AutomahoutSettings settings )
	{
		final Window active =
				KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		final Frame owner = ( active instanceof Frame ) ? ( Frame ) active : null;
		SwingUtilities.invokeLater( () -> new VlmSettingsDialog( owner, settings ).setVisible( true ) );
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

	private void addSectionHeader( final String title )
	{
		final JLabel header = new JLabel( "<html><b>" + title + "</b></html>" );
		header.setBorder( BorderFactory.createMatteBorder( 0, 0, 1, 0, header.getForeground() ) );
		final GridBagConstraints hc = ( GridBagConstraints ) lc.clone();
		hc.gridwidth = 2;
		hc.anchor = GridBagConstraints.LINE_START;
		hc.fill = GridBagConstraints.HORIZONTAL;
		hc.insets = new Insets( 10, 4, 2, 4 );
		content.add( header, hc );
		lc.gridy++;
		fc.gridy = lc.gridy;
	}

	private void addRow( final String label, final java.awt.Component field )
	{
		content.add( new JLabel( label ), lc );
		content.add( field, fc );
		lc.gridy++;
		fc.gridy = lc.gridy;
	}
}
