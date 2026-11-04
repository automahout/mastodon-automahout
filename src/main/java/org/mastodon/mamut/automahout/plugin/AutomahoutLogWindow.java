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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;

/**
 * Non-modal log window of Automahout's runs: the log (also written to a file in the temporary directory), a Stop
 * button and a status panel.
 *
 * <p>The window remains open after a run finishes so the user can review the log. It can be re-used across
 * multiple runs.</p>
 */
public class AutomahoutLogWindow
{

	// ---- colours ----------------------------------------------------------

	private static final Color COLOR_RUNNING = new Color( 0x2E7D32 );  // green

	private static final Color COLOR_STOPPED = new Color( 0xB71C1C );  // red

	private static final Color COLOR_IDLE = Color.DARK_GRAY;

	// ---- timing -----------------------------------------------------------

	private static final DateTimeFormatter TIME_FMT =
			DateTimeFormatter.ofPattern( "HH:mm:ss" );

	// ---- UI components ----------------------------------------------------

	private final JFrame frame;

	private final JLabel phaseLabel;

	private final JLabel timepointLabel;

	private final JProgressBar progressBar;

	private final JTextArea logArea;

	private final JButton stopButton;

	/** What the Stop button does; set by the plugin with {@link #setStopCallback}. */
	private volatile Runnable stopCallback;

	// -----------------------------------------------------------------------

	public AutomahoutLogWindow()
	{
		frame = new JFrame( "Automahout" );
		frame.setDefaultCloseOperation( JFrame.HIDE_ON_CLOSE );
		frame.setPreferredSize( new Dimension( 520, 420 ) );

		// ---- status panel -------------------------------------------------
		final JPanel statusPanel = new JPanel( new GridBagLayout() );
		statusPanel.setBorder( BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder( 0, 0, 1, 0, Color.LIGHT_GRAY ),
				BorderFactory.createEmptyBorder( 6, 10, 6, 10 ) ) );

		final GridBagConstraints gc = new GridBagConstraints();
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.insets = new Insets( 2, 0, 2, 8 );

		final JLabel phaseHeading = new JLabel( "Phase:" );
		phaseHeading.setFont( phaseHeading.getFont().deriveFont( Font.BOLD ) );
		gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
		statusPanel.add( phaseHeading, gc );

		phaseLabel = new JLabel( "Idle" );
		phaseLabel.setForeground( COLOR_IDLE );
		gc.gridx = 1; gc.weightx = 1;
		statusPanel.add( phaseLabel, gc );

		final JLabel tpHeading = new JLabel( "Timepoint:" );
		tpHeading.setFont( tpHeading.getFont().deriveFont( Font.BOLD ) );
		gc.gridx = 0; gc.gridy = 1; gc.weightx = 0;
		statusPanel.add( tpHeading, gc );

		timepointLabel = new JLabel( "—" );
		timepointLabel.setForeground( COLOR_IDLE );
		gc.gridx = 1; gc.weightx = 1;
		statusPanel.add( timepointLabel, gc );

		progressBar = new JProgressBar( 0, 100 );
		progressBar.setStringPainted( true );
		progressBar.setString( "" );
		gc.gridx = 0; gc.gridy = 2; gc.gridwidth = 2; gc.weightx = 1;
		statusPanel.add( progressBar, gc );

		// ---- log area -----------------------------------------------------
		logArea = new JTextArea();
		logArea.setEditable( false );
		logArea.setFont( new Font( Font.MONOSPACED, Font.PLAIN, 12 ) );
		logArea.setLineWrap( true );
		logArea.setWrapStyleWord( true );
		logArea.setMargin( new Insets( 4, 6, 4, 6 ) );

		// Auto-scroll to bottom on each append
		final DefaultCaret caret = ( DefaultCaret ) logArea.getCaret();
		caret.setUpdatePolicy( DefaultCaret.ALWAYS_UPDATE );

		final JScrollPane scrollPane = new JScrollPane( logArea );
		scrollPane.setBorder( BorderFactory.createEmptyBorder() );

		// ---- button panel -------------------------------------------------
		final JPanel buttonPanel = new JPanel( new BorderLayout() );
		buttonPanel.setBorder( BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder( 1, 0, 0, 0, Color.LIGHT_GRAY ),
				BorderFactory.createEmptyBorder( 6, 10, 6, 10 ) ) );

		stopButton = new JButton( "Stop" );
		stopButton.setEnabled( false );
		stopButton.addActionListener( e -> {
			final Runnable cb = stopCallback;
			if ( cb != null ) cb.run();
		} );

		final JButton clearButton = new JButton( "Clear Log" );
		clearButton.addActionListener( e -> logArea.setText( "" ) );

		buttonPanel.add( stopButton, BorderLayout.WEST );
		buttonPanel.add( clearButton, BorderLayout.EAST );

		// ---- assemble -----------------------------------------------------
		frame.getContentPane().setLayout( new BorderLayout() );
		frame.getContentPane().add( statusPanel, BorderLayout.NORTH );
		frame.getContentPane().add( scrollPane, BorderLayout.CENTER );
		frame.getContentPane().add( buttonPanel, BorderLayout.SOUTH );

		frame.pack();
	}

	// -----------------------------------------------------------------------
	// Public API

	/** Makes the window visible and brings it to the front. */
	public void show()
	{
		frame.setVisible( true );
		frame.toFront();
	}

	/**
	 * Replaces the Stop button's action with {@code callback}.
	 * The plugin sets it to cancel a running autonomous tracking.
	 */
	public void setStopCallback( final Runnable callback )
	{
		stopCallback = callback;
	}

	/** Enables the Stop button. Safe to call from any thread. */
	public void enableStop()
	{
		SwingUtilities.invokeLater( () -> stopButton.setEnabled( true ) );
	}

	/** Disables the Stop button. Safe to call from any thread. */
	public void disableStop()
	{
		SwingUtilities.invokeLater( () -> stopButton.setEnabled( false ) );
	}

	// -----------------------------------------------------------------------
	// Status and log

	public void onStatusUpdate( final String phase, final int currentT, final int totalT,
			final int progressPercent )
	{
		SwingUtilities.invokeLater( () -> {
			phaseLabel.setText( phase );
			phaseLabel.setForeground( COLOR_RUNNING );

			timepointLabel.setText( "t = " + currentT + "  (" + totalT + " total)" );
			timepointLabel.setForeground( COLOR_RUNNING );

			progressBar.setValue( progressPercent );
			progressBar.setString( progressPercent + "%" );

			stopButton.setEnabled( true );
		} );
	}

	public void onLogMessage( final String message )
	{
		final String timestamp = LocalTime.now().format( TIME_FMT );
		SwingUtilities.invokeLater( () -> logArea.append( timestamp + "  " + message + "\n" ) );
		toFile( timestamp + "  " + message + "\n" );
	}

	/**
	 * Where this window's log is also written, so a run can be read back after the window is closed --
	 * how many questions were asked, which were repeated -- rather than only from a screenshot. One file
	 * per window, in the temporary directory beside the origin dumps.
	 */
	private final java.nio.file.Path logFile = java.nio.file.Paths.get( System.getProperty( "java.io.tmpdir" ),
			"automahout-log-" + java.time.LocalDateTime.now().format( DateTimeFormatter.ofPattern( "yyyyMMdd-HHmmss" ) )
					+ ".txt" );

	private boolean logFileFailed;

	private synchronized void toFile( final String line )
	{
		if ( logFileFailed )
			return;
		try
		{
			java.nio.file.Files.write( logFile, line.getBytes( java.nio.charset.StandardCharsets.UTF_8 ),
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND );
		}
		catch ( final java.io.IOException | RuntimeException e )
		{
			logFileFailed = true; // the window still has it; never let a file problem stop a run
		}
	}

	public void onCompleted()
	{
		SwingUtilities.invokeLater( () -> {
			phaseLabel.setText( "Complete" );
			phaseLabel.setForeground( COLOR_RUNNING );
			timepointLabel.setForeground( COLOR_RUNNING );
			progressBar.setValue( 100 );
			progressBar.setString( "100%" );
			stopButton.setEnabled( false );
			logArea.append( LocalTime.now().format( TIME_FMT ) + "  === Complete ===\n" );
		} );
	}

	public void onStopped()
	{
		SwingUtilities.invokeLater( () -> {
			phaseLabel.setText( "Stopped" );
			phaseLabel.setForeground( COLOR_STOPPED );
			timepointLabel.setForeground( COLOR_STOPPED );
			stopButton.setEnabled( false );
			logArea.append( LocalTime.now().format( TIME_FMT ) + "  === Stopped ===\n" );
		} );
	}

}

