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
 * Which code path made each link, and for what {@link GraphEdits.Reason} -- {@link SpotOrigin} for
 * edges.
 *
 * <p>Exists to find where unnatural lineage links come from: the linker, gap closing, orphan
 * joining, division rescue and topology surgery all add links through {@link GraphEdits#addLink},
 * and a wrong link in the final graph says nothing about which of them made it. Recorded at that
 * one chokepoint, the reason always, the two calling methods only while {@link SpotOriginDump} is
 * on.</p>
 *
 * <p>A link added outside {@link GraphEdits} reads {@link SpotOrigin#UNRECORDED} -- ELEPHANT's own
 * linking action, for one, if it is ever invoked directly. The registry follows {@link SpotOrigin}:
 * weak key, weak value kept alive by the listener the graph holds, and that listener clears the entry
 * on every add so a reused edge pool index never inherits an origin.</p>
 */
public final class LinkOrigin
{
	private static final int NONE = -1;

	private static final class State
	{
		final IntPropertyMap< Link > codes;

		final List< String > names = new ArrayList<>();

		final Map< String, Integer > index = new HashMap<>();

		State( final ModelGraph graph )
		{
			codes = new IntPropertyMap<>( graph.edges().getRefPool(), NONE );
			graph.addGraphListener( new GraphListener< Spot, Link >()
			{
				@Override
				public void edgeAdded( final Link edge )
				{
					codes.remove( edge );
				}

				@Override
				public void edgeRemoved( final Link edge )
				{
					codes.remove( edge );
				}

				@Override
				public void graphRebuilt()
				{}

				@Override
				public void vertexAdded( final Spot vertex )
				{}

				@Override
				public void vertexRemoved( final Spot vertex )
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

	private static final WeakHashMap< ModelGraph, WeakReference< State > > REGISTRY = new WeakHashMap<>();

	private LinkOrigin()
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

	/** Called by {@link GraphEdits#addLink} for every link it makes. */
	static void record( final ModelGraph graph, final Link link, final GraphEdits.Reason why )
	{
		final String site = SpotOriginDump.enabled() ? SpotOrigin.callerOutside( new Throwable().getStackTrace() ) : "";
		final State s = stateFor( graph );
		synchronized ( s )
		{
			s.codes.set( link, s.codeOf( why + "\t" + site ) );
		}
	}

	/** The reason this link was made, or {@link SpotOrigin#UNRECORDED}. */
	public static String reasonOf( final ModelGraph graph, final Link link )
	{
		final String both = entry( graph, link );
		return both == null ? SpotOrigin.UNRECORDED : both.substring( 0, both.indexOf( '\t' ) );
	}

	/** The two innermost calling methods outside the chokepoint, or empty. */
	public static String siteOf( final ModelGraph graph, final Link link )
	{
		final String both = entry( graph, link );
		return both == null ? "" : both.substring( both.indexOf( '\t' ) + 1 );
	}

	private static String entry( final ModelGraph graph, final Link link )
	{
		final State s = stateFor( graph );
		synchronized ( s )
		{
			final int c = s.codes.getInt( link );
			return c == NONE ? null : s.names.get( c );
		}
	}
}
