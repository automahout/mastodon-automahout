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

import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Whether the graph's link lists are still sound.
 *
 * <p>Mastodon keeps each spot's incoming and outgoing links as linked lists inside its pools. When
 * those lists are damaged -- a link removed twice, or two threads editing at once -- nothing fails:
 * the next removal that walks a damaged list simply never returns. Run 20261004-133403 sat at 100%
 * CPU for half an hour inside {@code graph.remove(link)} in the [5c/6] division pass. This walks every
 * list with a bound, so it reports the damage instead of hanging on it, and the pipeline can stop at
 * the step that caused it.</p>
 */
public final class GraphIntegrity
{
	private GraphIntegrity()
	{}

	/** Thrown when the link lists are damaged; the run must stop, and the project must not be saved. */
	public static final class DamagedGraphException extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		public DamagedGraphException( final String message )
		{
			super( message );
		}
	}

	/**
	 * Walks every spot's incoming and outgoing links. Caller holds at least the read lock.
	 *
	 * @return what is wrong, or {@code null} when every list is sound
	 */
	public static String check( final ModelGraph graph )
	{
		final int nEdges = graph.edges().size();
		final Spot ref = graph.vertexRef();
		try
		{
			long in = 0, out = 0;
			for ( final Spot v : graph.vertices() )
			{
				final int id = v.getInternalPoolIndex();
				int k = 0;
				for ( final Link e : v.incomingEdges() )
				{
					if ( ++k > nEdges )
						return String.format( "the incoming links of the spot at t=%d (%.1f, %.1f) run in a circle",
								v.getTimepoint(), v.getDoublePosition( 0 ), v.getDoublePosition( 1 ) );
					if ( e.getTarget( ref ).getInternalPoolIndex() != id )
						return String.format( "a link listed as entering the spot at t=%d (%.1f, %.1f) points to the "
								+ "spot at t=%d (%.1f, %.1f)", v.getTimepoint(), v.getDoublePosition( 0 ), v.getDoublePosition( 1 ),
								ref.getTimepoint(), ref.getDoublePosition( 0 ), ref.getDoublePosition( 1 ) );
					in++;
				}
				k = 0;
				for ( final Link e : v.outgoingEdges() )
				{
					if ( ++k > nEdges )
						return String.format( "the outgoing links of the spot at t=%d (%.1f, %.1f) run in a circle",
								v.getTimepoint(), v.getDoublePosition( 0 ), v.getDoublePosition( 1 ) );
					if ( e.getSource( ref ).getInternalPoolIndex() != id )
						return String.format( "a link listed as leaving the spot at t=%d (%.1f, %.1f) starts at the "
								+ "spot at t=%d (%.1f, %.1f)", v.getTimepoint(), v.getDoublePosition( 0 ), v.getDoublePosition( 1 ),
								ref.getTimepoint(), ref.getDoublePosition( 0 ), ref.getDoublePosition( 1 ) );
					// A link must go forward in time. One from a spot to itself -- made when a ref held across
					// a hand edit was pointed at a freed slot the next new spot took -- sits in both of the
					// spot's lists, and a walk along the track never ends.
					e.getTarget( ref );
					if ( ref.getTimepoint() <= v.getTimepoint() )
						return String.format( "a link from the spot at t=%d (%.1f, %.1f) goes to %s at t=%d (%.1f, %.1f), not "
								+ "forward in time", v.getTimepoint(), v.getDoublePosition( 0 ), v.getDoublePosition( 1 ),
								ref.getInternalPoolIndex() == id ? "itself" : "the spot", ref.getTimepoint(),
								ref.getDoublePosition( 0 ), ref.getDoublePosition( 1 ) );
					out++;
				}
			}
			if ( in != nEdges || out != nEdges )
				return String.format( "the graph holds %d links but the spots' lists hold %d incoming and %d outgoing",
						nEdges, in, out );
			return null;
		}
		finally
		{
			graph.releaseRef( ref );
		}
	}

	/** {@link #check} under the read lock, throwing {@link DamagedGraphException} on damage. */
	public static void require( final ToolContext ctx, final ModelGraph graph, final String after )
	{
		final String fault;
		graph.getLock().readLock().lock();
		try
		{
			fault = check( graph );
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( fault == null )
			return;
		final String message = String.format( "STOP: the graph's link lists are damaged after %s (%s). Continuing "
				+ "would hang the next edit that touches them. The run is stopped; do NOT save the project -- "
				+ "reopen it from the last save.", after, fault );
		if ( ctx != null )
			ctx.log( "  !! " + message );
		throw new DamagedGraphException( message );
	}
}
