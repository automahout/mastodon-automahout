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
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.views.MamutView;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.tool.Applicability;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.DataProfile;
import org.mastodon.mamut.automahout.tool.ImageProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.ToolParameter;
import org.mastodon.mamut.automahout.tool.ToolRegistry;
import org.mastodon.mamut.automahout.tool.remote.RemoteToolServer;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import bdv.viewer.ViewerPanel;

/**
 * Plugins &gt; Automahout &gt; Run a tool...: every tool the run could use -- built-in, from a plugin jar,
 * from a remote tool server -- by stage, with whether it suits this data and why, a form for its typed
 * parameters, and a Run button. The rule half of the selection, made visible; the LLM half chooses among
 * the same applicable tools.
 */
class RunToolAction extends AbstractNamedAction
{
	private static final long serialVersionUID = 1L;

	private final ProjectModel projectModel;

	RunToolAction( final ProjectModel projectModel )
	{
		super( MastodonAutomahoutPlugin.ACTION_RUN_TOOL_NAME );
		this.projectModel = projectModel;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		MamutViewBdv bdv = null;
		for ( final MamutViewBdv v : projectModel.getWindowManager().getViewList( MamutViewBdv.class ) )
			if ( v.getGroupHandle().getGroupId() == 0 )
				bdv = v;
		if ( bdv == null )
		{
			final Map< String, Object > gui = new HashMap<>();
			gui.put( MamutView.GROUP_HANDLE_ID_KEY, 0 );
			bdv = projectModel.getWindowManager().createBigDataViewer( gui );
		}
		new Dialog( projectModel, bdv.getViewerPanelMamut() ).setVisible( true );
	}

	private static final class Dialog extends JFrame
	{
		private static final long serialVersionUID = 1L;

		private final ToolContext ctx;

		private final JTextField servers = new JTextField( String.join( ", ", RemoteToolServer.configured() ), 40 );

		private final JLabel profileLabel = new JLabel( "Data: measuring..." );

		private final DefaultTableModel rows = new DefaultTableModel(
				new Object[] { "Stage", "Tool", "Applies", "Score", "Why" }, 0 )
		{
			private static final long serialVersionUID = 1L;

			@Override
			public boolean isCellEditable( final int r, final int c )
			{
				return false;
			}
		};

		private final JTable table = new JTable( rows );

		private final JTextArea description = new JTextArea( 4, 60 );

		private final JPanel form = new JPanel( new GridBagLayout() );

		private final JTextArea output = new JTextArea( 10, 60 );

		private final JButton run = new JButton( "Run" );

		private final List< AutomahoutTool > listed = new ArrayList<>();

		private final List< Applicability > verdicts = new ArrayList<>();

		private final Map< String, JComponent > fields = new LinkedHashMap<>();

		Dialog( final ProjectModel projectModel, final ViewerPanel viewer )
		{
			super( "Automahout -- Run a tool" );
			ctx = new ToolContext( projectModel, viewer );
			ctx.setLogCallback( this::append );

			final JPanel top = new JPanel( new BorderLayout( 6, 6 ) );
			final JPanel srv = new JPanel( new BorderLayout( 6, 0 ) );
			srv.add( new JLabel( "Remote tool servers:" ), BorderLayout.WEST );
			srv.add( servers, BorderLayout.CENTER );
			final JButton refresh = new JButton( "Refresh" );
			refresh.addActionListener( e -> refresh() );
			srv.add( refresh, BorderLayout.EAST );
			top.add( srv, BorderLayout.NORTH );
			top.add( profileLabel, BorderLayout.SOUTH );

			table.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
			table.getSelectionModel().addListSelectionListener( e -> {
				if ( !e.getValueIsAdjusting() )
					showSelected();
			} );
			table.getColumnModel().getColumn( 4 ).setPreferredWidth( 420 );
			description.setLineWrap( true );
			description.setWrapStyleWord( true );
			description.setEditable( false );
			output.setEditable( false );

			final JPanel detail = new JPanel( new BorderLayout( 6, 6 ) );
			detail.add( new JScrollPane( description ), BorderLayout.NORTH );
			detail.add( new JScrollPane( form ), BorderLayout.CENTER );
			run.addActionListener( e -> runSelected() );
			run.setEnabled( false );
			detail.add( run, BorderLayout.SOUTH );

			final JSplitPane middle = new JSplitPane( JSplitPane.VERTICAL_SPLIT, new JScrollPane( table ), detail );
			middle.setResizeWeight( 0.5 );
			final JSplitPane all = new JSplitPane( JSplitPane.VERTICAL_SPLIT, middle, new JScrollPane( output ) );
			all.setResizeWeight( 0.75 );

			final JPanel content = new JPanel( new BorderLayout( 6, 6 ) );
			content.setBorder( BorderFactory.createEmptyBorder( 8, 8, 8, 8 ) );
			content.add( top, BorderLayout.NORTH );
			content.add( all, BorderLayout.CENTER );
			setContentPane( content );
			setPreferredSize( new Dimension( 900, 760 ) );
			pack();
			setLocationRelativeTo( null );
			refresh();
		}

		private void append( final String line )
		{
			SwingUtilities.invokeLater( () -> {
				output.append( line + "\n" );
				output.setCaretPosition( output.getDocument().getLength() );
			} );
		}

		/** Measures the data (once per run of the dialog) and asks every source of tools, off the EDT. */
		private void refresh()
		{
			RemoteToolServer.setConfigured( servers.getText() );
			rows.setRowCount( 0 );
			run.setEnabled( false );
			new SwingWorker< DataProfile, Void >()
			{
				private ToolRegistry registry;

				@Override
				protected DataProfile doInBackground()
				{
					final int t = ctx.requireViewer().state().getCurrentTimepoint();
					// Modality is what most applicability rules ask about: read it from the image now.
					ImageProfile.forRun( ctx, () -> AutoCorrectOrchestrator.sampleIntensities( ctx, t, ImageProfile.SAMPLES ) );
					registry = ToolRegistry.forContext( ctx );
					return DataProfile.of( ctx );
				}

				@Override
				protected void done()
				{
					try
					{
						fill( registry, get() );
					}
					catch ( final Exception ex )
					{
						append( "Could not list the tools: " + ex.getMessage() );
					}
				}
			}.execute();
		}

		private void fill( final ToolRegistry registry, final DataProfile data )
		{
			profileLabel.setText( "Data: " + data );
			final List< AutomahoutTool > tools = new ArrayList<>( registry.getAll() );
			final Map< AutomahoutTool, Applicability > v = new HashMap<>();
			for ( final AutomahoutTool t : tools )
				v.put( t, t.applicability( data ) );
			tools.sort( Comparator.< AutomahoutTool >comparingInt( t -> t.getRole().ordinal() )
					.thenComparing( t -> !v.get( t ).applies ).thenComparing( t -> -v.get( t ).score ) );
			listed.clear();
			verdicts.clear();
			for ( final AutomahoutTool t : tools )
			{
				final Applicability a = v.get( t );
				listed.add( t );
				verdicts.add( a );
				rows.addRow( new Object[] { t.getRole(), t.getId(), a.applies ? "yes" : "no",
						a.applies ? String.format( "%.2f", a.score ) : "", a.reason } );
			}
		}

		private void showSelected()
		{
			final int i = table.getSelectedRow();
			form.removeAll();
			fields.clear();
			run.setEnabled( i >= 0 );
			if ( i < 0 )
			{
				form.revalidate();
				form.repaint();
				return;
			}
			final AutomahoutTool tool = listed.get( i );
			description.setText( tool.getDescription() );
			description.setCaretPosition( 0 );
			final GridBagConstraints c = new GridBagConstraints();
			c.insets = new Insets( 2, 4, 2, 4 );
			c.anchor = GridBagConstraints.WEST;
			int row = 0;
			if ( tool.getParameters().isEmpty() )
			{
				c.gridx = 0;
				c.gridy = 0;
				form.add( new JLabel( "No typed parameters declared; runs with its defaults." ), c );
			}
			for ( final ToolParameter p : tool.getParameters() )
			{
				final JComponent field;
				switch ( p.type )
				{
				case BOOLEAN:
					field = new JCheckBox( "", Boolean.TRUE.equals( p.defaultValue ) );
					break;
				case CHOICE:
					final JComboBox< String > box = new JComboBox<>( p.choices.toArray( new String[ 0 ] ) );
					if ( p.defaultValue != null )
						box.setSelectedItem( p.defaultValue );
					field = box;
					break;
				default:
					field = new JTextField( p.defaultValue == null ? "" : String.valueOf( p.defaultValue ), 12 );
				}
				fields.put( p.name, field );
				c.gridx = 0;
				c.gridy = row;
				form.add( new JLabel( p.name ), c );
				c.gridx = 1;
				form.add( field, c );
				c.gridx = 2;
				final String range = p.min != null || p.max != null
						? String.format( " [%s..%s]", p.min == null ? "" : p.min, p.max == null ? "" : p.max ) : "";
				form.add( new JLabel( "<html><i>" + p.description + range + "</i></html>" ), c );
				row++;
			}
			form.revalidate();
			form.repaint();
		}

		private void runSelected()
		{
			final int i = table.getSelectedRow();
			if ( i < 0 )
				return;
			final AutomahoutTool tool = listed.get( i );
			final Applicability a = verdicts.get( i );
			if ( !a.applies && JOptionPane.showConfirmDialog( this, tool.getId() + " does not suit this data:\n" + a.reason
					+ "\n\nRun it anyway?", "Not applicable", JOptionPane.YES_NO_OPTION ) != JOptionPane.YES_OPTION )
				return;
			final Map< String, Object > params = new HashMap<>();
			for ( final ToolParameter p : tool.getParameters() )
			{
				final Object v = value( p, fields.get( p.name ) );
				if ( v != null )
					params.put( p.name, v );
			}
			run.setEnabled( false );
			append( "Running " + tool.getId() + " " + params + " ..." );
			new SwingWorker< String, Void >()
			{
				@Override
				protected String doInBackground()
				{
					return tool.execute( ctx, params );
				}

				@Override
				protected void done()
				{
					try
					{
						append( get() );
					}
					catch ( final Exception ex )
					{
						append( tool.getId() + " failed: " + ex.getMessage() );
					}
					run.setEnabled( true );
				}
			}.execute();
		}

		private static Object value( final ToolParameter p, final JComponent f )
		{
			if ( f instanceof JCheckBox )
				return ( ( JCheckBox ) f ).isSelected();
			if ( f instanceof JComboBox )
				return ( ( JComboBox< ? > ) f ).getSelectedItem();
			final String s = ( ( JTextField ) f ).getText().trim();
			if ( s.isEmpty() )
				return null;
			switch ( p.type )
			{
			case INTEGER:
				return Integer.parseInt( s );
			case NUMBER:
				return Double.parseDouble( s );
			default:
				return s;
			}
		}
	}
}
