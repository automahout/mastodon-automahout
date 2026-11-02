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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.mastodon.graph.GraphListener;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.properties.IntPropertyMap;

/**
 * Which code path put each spot in the graph, and for what {@link GraphEdits.Reason}.
 *
 * <p>Exists because a double detection on one cell could not be traced. ELEPHANT's own client drops a
 * predicted spot within its suppression distance of one already there, and this project never did —
 * but predict is only one of fourteen places that add spots. Interpolation, gap filling, GT restore,
 * splitting and merging all add through {@link GraphEdits}, and a pair photographed on one nucleus
 * could have come from any two of them. A suppression rule on predict would fix nothing if the second
 * spot came from gap filling.</p>
 *
 * <p>Recorded at the one chokepoint every addition passes through, so a site added later is covered
 * without anyone remembering. The REASON is always recorded (an int write). The calling SITE costs a
 * stack trace, so it is only taken while {@link SpotOriginDump} is on.</p>
 *
 * <p><b>A spot added outside {@link GraphEdits} reads {@link #UNRECORDED}</b> — a person, or ELEPHANT's
 * own predict action. The map lives on the vertex pool, whose indices are reused after a removal, so a
 * graph listener clears the entry on every add; the chokepoint then writes it straight after. Without
 * that, a spot a person added would read as whatever the pool index last held. While listeners are
 * paused an outside add is not cleared, which leaves that one case unreliable.</p>
 */
public final class SpotOrigin
{
	/** What a spot reads when it did not come through {@link GraphEdits}. */
	public static final String UNRECORDED = "unrecorded";

	private static final int NONE = -1;

	private static final class State
	{
		final IntPropertyMap< Spot > codes;

		final List< String > names = new ArrayList<>();

		final Map< String, Integer > index = new HashMap<>();

		State( final ModelGraph graph )
		{
			codes = new IntPropertyMap<>( graph.vertices().getRefPool(), NONE );
			graph.addGraphListener( new GraphListener< Spot, Link >()
			{
				@Override
				public void vertexAdded( final Spot vertex )
				{
					codes.remove( vertex );
				}

				@Override
				public void vertexRemoved( final Spot vertex )
				{
					codes.remove( vertex );
				}

				@Override
				public void graphRebuilt()
				{}

				@Override
				public void edgeAdded( final Link edge )
				{}

				@Override
				public void edgeRemoved( final Link edge )
				{}
			} );
		}

		int codeOf( final String name )
		{
			final Integer c = index.get( name );
			if ( c != null )
				return c;
			names.add( name );
			index.put( name, names.size() - 1 );
			return names.size() - 1;
		}
	}

	/*
	 * Keyed by ModelGraph identity, and the VALUE is weak too. The State reaches its own key -- the
	 * property map holds the vertex pool, whose Spot refs hold the graph -- so a strong value would
	 * pin every graph ever seen. That is not hypothetical: with a strong value the test suite ran out
	 * of heap, one `new Model()` at a time. What keeps the State alive is the graph listener it
	 * registers, which the graph holds, so it lives exactly as long as the graph.
	 */
	private static final WeakHashMap< ModelGraph, WeakReference< State > > REGISTRY = new WeakHashMap<>();

	private SpotOrigin()
	{}

	private static synchronized State stateFor( final ModelGraph graph )
	{
		final WeakReference< State > ref = REGISTRY.get( graph );
		State s = ref == null ? null : ref.get();
		if ( s == null )
		{
			s = new State( graph );
			REGISTRY.put( graph, new WeakReference<>( s ) );
		}
		return s;
	}

	/** Called by {@link GraphEdits} for every spot it keeps. */
	static void record( final ModelGraph graph, final Spot spot, final GraphEdits.Reason why )
	{
		final String site = SpotOriginDump.enabled() ? callerOutside( new Throwable().getStackTrace() ) : "";
		final State s = stateFor( graph );
		synchronized ( s )
		{
			s.codes.set( spot, s.codeOf( why + "\t" + site ) );
		}
	}

	/** The reason this spot was added, or {@link #UNRECORDED}. */
	public static String reasonOf( final ModelGraph graph, final Spot spot )
	{
		final String both = entry( graph, spot );
		return both == null ? UNRECORDED : both.substring( 0, both.indexOf( '\t' ) );
	}

	/**
	 * The two innermost calling methods outside the chokepoint, {@code "Inner.method<-Outer.method"},
	 * or empty when the dump was off at the time of the add. Two, because the inner one is often a
	 * shared helper that several passes call.
	 */
	public static String siteOf( final ModelGraph graph, final Spot spot )
	{
		final String both = entry( graph, spot );
		return both == null ? "" : both.substring( both.indexOf( '\t' ) + 1 );
	}

	private static String entry( final ModelGraph graph, final Spot spot )
	{
		final State s = stateFor( graph );
		synchronized ( s )
		{
			final int c = s.codes.getInt( spot );
			return c == NONE ? null : s.names.get( c );
		}
	}

	static String callerOutside( final StackTraceElement[] trace )
	{
		final StringBuilder sb = new StringBuilder();
		int taken = 0;
		for ( final StackTraceElement e : trace )
		{
			final String cls = e.getClassName();
			if ( cls.equals( SpotOrigin.class.getName() ) || cls.equals( GraphEdits.class.getName() )
					|| cls.equals( LinkOrigin.class.getName() ) )
				continue;
			if ( taken > 0 )
				sb.append( "<-" );
			final String simple = cls.substring( cls.lastIndexOf( '.' ) + 1 );
			sb.append( simple ).append( '.' ).append( e.getMethodName() );
			if ( ++taken == 2 )
				break;
		}
		return sb.toString();
	}
}
