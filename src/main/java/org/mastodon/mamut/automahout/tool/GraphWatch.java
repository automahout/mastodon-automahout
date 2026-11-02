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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.mastodon.graph.GraphListener;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Which spots and links the run must not edit through a ref or an id it collected earlier in this step.
 *
 * <p>The user edits the graph in the viewer while a run goes on. A pass that collects spots (refs, or
 * their pool indices) under one lock and edits them under a later one -- after an image read, a server
 * call, a question -- acts on whatever holds that slot by then: Mastodon gives a deleted spot's slot to
 * the next spot added. The pass then deletes, moves, tags or links an unrelated spot, or links a spot to
 * itself, which makes every walk along the track loop forever and freezes the application. Three
 * reviews of src/main/java found about 35 passes shaped like that.</p>
 *
 * <p>Instead of re-finding at every one of them, this listens to the graph: a slot is <em>dirty</em>
 * from the moment its spot or link is removed, or a spot or link is added there by anyone but the run
 * itself (an edit in the viewer, an undo), until the next {@link #mark}. A slot the run fills itself
 * through {@link GraphEdits} is clean again: a ref to it was taken after it was filled. Every edit the
 * run makes checks {@link #dirty} first and leaves a dirty spot or link alone. {@link #mark} is called
 * at each step boundary, where no collected ref or id survives.</p>
 *
 * <p>What it cannot see: a slot the user freed and the run itself refilled, used through a ref taken
 * before both. {@link GraphEdits#addLink} refuses the one form of that which freezes the application,
 * a link that does not go forward in time.</p>
 */
public final class GraphWatch
{
	private GraphWatch()
	{}

	private static final Map< ModelGraph, State > STATES = new WeakHashMap<>();

	/** True on a thread while {@link GraphEdits} is adding for the run, so its adds read as the run's own. */
	private static final ThreadLocal< Boolean > RUNS_OWN_ADD = ThreadLocal.withInitial( () -> Boolean.FALSE );

	private static final class State implements GraphListener< Spot, Link >
	{
		final Set< Integer > spots = new HashSet<>();

		final Set< Integer > links = new HashSet<>();

		boolean everything;

		int refused;

		@Override
		public synchronized void graphRebuilt()
		{
			everything = true;
		}

		@Override
		public synchronized void vertexAdded( final Spot v )
		{
			if ( RUNS_OWN_ADD.get() )
				spots.remove( v.getInternalPoolIndex() );
			else
				spots.add( v.getInternalPoolIndex() );
		}

		@Override
		public synchronized void vertexRemoved( final Spot v )
		{
			spots.add( v.getInternalPoolIndex() );
		}

		@Override
		public synchronized void edgeAdded( final Link e )
		{
			if ( RUNS_OWN_ADD.get() )
				links.remove( e.getInternalPoolIndex() );
			else
				links.add( e.getInternalPoolIndex() );
		}

		@Override
		public synchronized void edgeRemoved( final Link e )
		{
			links.add( e.getInternalPoolIndex() );
		}

		synchronized int clear()
		{
			spots.clear();
			links.clear();
			everything = false;
			final int r = refused;
			refused = 0;
			return r;
		}
	}

	private static State state( final ModelGraph graph )
	{
		synchronized ( STATES )
		{
			State s = STATES.get( graph );
			if ( s == null )
			{
				s = new State();
				graph.addGraphListener( s );
				STATES.put( graph, s );
			}
			return s;
		}
	}

	/** Starts watching {@code graph}, once; a null graph is ignored. */
	public static void watch( final ModelGraph graph )
	{
		if ( graph != null )
			state( graph );
	}

	/**
	 * Starts watching the context's graph (once) and forgets what was dirty: a step boundary. Logs how
	 * many of the step's edits were left undone because their spot or link had changed in the viewer.
	 */
	public static void mark( final ToolContext ctx, final String step )
	{
		final ModelGraph graph = ctx == null ? null : ctx.getGraph();
		if ( graph == null )
			return;
		final int refused = state( graph ).clear();
		if ( refused > 0 )
			ctx.log( String.format( "  %d edit(s) of %s left undone: the spot or link they were for had been deleted "
					+ "or added in the viewer meanwhile, and the run does not act on a spot it can no longer be "
					+ "sure of.", refused, step ) );
	}

	/** Counts one edit left undone because {@link #dirty} said so; reported at the next {@link #mark}. */
	public static void refused( final ModelGraph graph )
	{
		final State s = state( graph );
		synchronized ( s )
		{
			s.refused++;
		}
	}

	/** Whether {@code spot}'s slot changed hands since the last {@link #mark}: never edit through it. */
	public static boolean dirty( final ModelGraph graph, final Spot spot )
	{
		final State s = state( graph );
		synchronized ( s )
		{
			return s.everything || s.spots.contains( spot.getInternalPoolIndex() );
		}
	}

	/** As {@link #dirty(ModelGraph, Spot)}, for a link. */
	public static boolean dirty( final ModelGraph graph, final Link link )
	{
		final State s = state( graph );
		synchronized ( s )
		{
			return s.everything || s.links.contains( link.getInternalPoolIndex() );
		}
	}

	/** Runs {@code add} as the run's own: the slot it fills is clean. */
	public static < T > T asRunsOwn( final java.util.function.Supplier< T > add )
	{
		final boolean was = RUNS_OWN_ADD.get();
		RUNS_OWN_ADD.set( Boolean.TRUE );
		try
		{
			return add.get();
		}
		finally
		{
			RUNS_OWN_ADD.set( was );
		}
	}
}
