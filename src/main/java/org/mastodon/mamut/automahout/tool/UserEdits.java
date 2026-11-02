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
package org.mastodon.mamut.automahout.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.mastodon.mamut.automahout.tool.impl.ElephantTool;
import org.mastodon.mamut.automahout.tool.impl.NavigationTool;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.TagSetStructure;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.model.tag.TagSetStructure.TagSet;

/**
 * Lets the user fix something by hand in the middle of a run, and keeps the run from undoing it.
 *
 * <p>When neither answer the run offers is right, the user edits the graph in BDV or TrackScheme
 * while the run waits. Every spot they added, moved, reshaped or relinked is then tagged with the
 * run's protected tag, so no later pass deletes, merges or splits it: the duplicate and short-track
 * passes would otherwise take a cell the user just placed. If the user chose a protected tag before
 * the run, that one is used; otherwise {@value #TAG_SET} &gt; {@value #TAG} is created and made the
 * run's protected tag.</p>
 */
public final class UserEdits
{
	private UserEdits()
	{}

	/** The tag set created when the user has not chosen a protected tag of their own. */
	public static final String TAG_SET = "Automahout";

	/** Its one tag. */
	public static final String TAG = "User edited";

	/**
	 * One string per spot describing everything a hand edit can change: timepoint, position, shape,
	 * and where its links go. A spot whose string is not in the "before" set was added or edited.
	 * Positions to 0.01 so the comparison is not defeated by rounding.
	 */
	public static Set< String > snapshot( final ModelGraph graph )
	{
		final Set< String > out = new HashSet<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
				out.add( describe( graph, s ) );
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return out;
	}

	private static String describe( final ModelGraph graph, final Spot s )
	{
		final double[][] c = new double[ 3 ][ 3 ];
		s.getCovariance( c );
		final StringBuilder sb = new StringBuilder();
		sb.append( s.getTimepoint() ).append( '@' ).append( where( s ) ).append( String.format( "|%.2f,%.2f,%.2f,%.2f",
				c[ 0 ][ 0 ], c[ 0 ][ 1 ], c[ 1 ][ 1 ], c[ 2 ][ 2 ] ) );
		final List< String > links = new ArrayList<>();
		final Spot ref = graph.vertexRef();
		for ( final Link e : s.incomingEdges() )
			links.add( "<" + where( e.getSource( ref ) ) );
		for ( final Link e : s.outgoingEdges() )
			links.add( ">" + where( e.getTarget( ref ) ) );
		graph.releaseRef( ref );
		links.sort( null );
		for ( final String l : links )
			sb.append( l );
		return sb.toString();
	}

	private static String where( final Spot s )
	{
		return String.format( "%d:%.2f,%.2f,%.2f", s.getTimepoint(), s.getDoublePosition( 0 ),
				s.getDoublePosition( 1 ), s.getDoublePosition( 2 ) );
	}

	/**
	 * Tags every spot not described in {@code before} with the run's protected tag, creating it if
	 * needed, and makes it the run's protected tag.
	 *
	 * @return how many spots were protected
	 */
	public static int protectChanged( final ToolContext ctx, final Set< String > before )
	{
		final Model model = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();
		// The tag first: creating it changes the tag-set structure, which must not happen under the
		// vertex lock. Then find and tag in ONE write lock -- found under one lock and tagged under the
		// next, a spot the user deleted in between would hand its slot, and the protection, to another.
		boolean any = false;
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
				if ( !before.contains( describe( graph, s ) ) )
				{
					any = true;
					break;
				}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( !any )
			return 0; // and no tag is created for nothing
		final ProtectedSpots.Config config = ensureProtectedTag( ctx );
		final Tag tag = liveTag( model, config );
		int changed = 0;
		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
				if ( !before.contains( describe( graph, s ) ) )
				{
					model.getTagSetModel().getVertexTags().set( s, tag );
					changed++;
				}
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( changed == 0 )
			return 0;
		model.setUndoPoint();
		ctx.notifyGraphChanged();
		return changed;
	}

	/**
	 * The run's protected tag: the user's own if they chose one, otherwise {@value #TAG_SET} &gt;
	 * {@value #TAG}, created in the project. Set as the run's choice too, so every later tool call
	 * keeps honouring it.
	 */
	static ProtectedSpots.Config ensureProtectedTag( final ToolContext ctx )
	{
		final Object chosen = ctx.getSharedData( ElephantTool.SHARED_PROTECTED_TAG_CONFIG );
		final ProtectedSpots.Config config = chosen instanceof ProtectedSpots.Config
				? ( ProtectedSpots.Config ) chosen : ProtectedSpots.Config.of( TAG_SET, TAG );
		final Model model = ctx.getModel();
		if ( liveTag( model, config ) == null )
		{
			// Copy, add, apply -- what Mastodon's own tag-set dialog does, so the tags already on spots
			// (ELEPHANT's tp/fp among them) are kept.
			final TagSetStructure tss = new TagSetStructure();
			tss.set( model.getTagSetModel().getTagSetStructure() );
			TagSet ts = null;
			for ( final TagSet s : tss.getTagSets() )
				if ( s.getName().equals( config.tagSetName ) )
					ts = s;
			if ( ts == null )
				ts = tss.createTagSet( config.tagSetName );
			ts.createTag( config.tagLabel, 0xffff8800 );
			model.getTagSetModel().setTagSetStructure( tss );
		}
		ctx.setSharedData( ElephantTool.SHARED_PROTECTED_TAG_CONFIG, config );
		ctx.setSharedData( ProtectedSpots.SHARED_CONFIG_KEY, config );
		return config;
	}

	private static Tag liveTag( final Model model, final ProtectedSpots.Config config )
	{
		for ( final TagSet ts : model.getTagSetModel().getTagSetStructure().getTagSets() )
			if ( ts.getName().equals( config.tagSetName ) )
				for ( final Tag t : ts.getTags() )
					if ( t.label().equals( config.tagLabel ) )
						return t;
		return null;
	}

	/**
	 * Takes the viewer to {@code t} centred on {@code world}, waits for the user to say they are done
	 * editing, and protects what they changed. Returns {@code false} when nobody can be asked.
	 */
	public static boolean letUserEdit( final ToolContext ctx, final int t, final double[] world, final String message )
	{
		if ( !ctx.canWaitForEdit() )
			return false;
		// Before as well as after: a damaged graph found after the edit says nothing about whether the
		// edit did it unless the graph was sound when the edit began.
		GraphIntegrity.require( ctx, ctx.getGraph(), "the run's own edits, found before your hand edit at t=" + t );
		try
		{
			final bdv.viewer.ViewerPanel viewer = ctx.requireViewer();
			SwingUtilities.invokeAndWait( () -> {
				viewer.state().setCurrentTimepoint( t );
				NavigationTool.navigateTo( viewer, world[ 0 ], world[ 1 ], world[ 2 ], 300 );
			} );
		}
		catch ( final Exception e )
		{
			ctx.log( "  Could not move the viewer there (" + e.getMessage() + "); edit at t=" + t + " yourself." );
		}
		final Set< String > before = snapshot( ctx.getGraph() );
		final boolean done = ctx.waitForEdit( message );
		// Protected even when the dialog was cancelled: whatever was changed by hand was meant.
		GraphIntegrity.require( ctx, ctx.getGraph(), "your hand edit at t=" + t );
		final int n = protectChanged( ctx, before );
		final ProtectedSpots.Config config = ProtectedSpots.fromContext( ctx );
		ctx.log( String.format( "  Hand edit at t=%d (%.1f, %.1f) %s: %d spot(s) added or changed%s.", t, world[ 0 ],
				world[ 1 ], done ? "finished" : "cancelled", n,
				n > 0 && config != null ? ", now protected as " + config.tagSetName + " > " + config.tagLabel : "" ) );
		return true;
	}
}
