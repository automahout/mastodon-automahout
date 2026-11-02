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
package org.mastodon.mamut.automahout.autocorrect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * What the user answered, and where, for the rest of the run -- so the same question is not asked twice.
 *
 * <p>It used to be a set of exact positions (to 0.1 um). That held only while nothing moved: the agent may
 * run the whole movie again (a second adaptive_track lap re-detects every frame), the consistency step
 * revisits the previous window, and each re-detection puts the same cell a fraction of a micron
 * elsewhere -- so the user was asked again what they had already answered. Now an answer is found again
 * within {@link #TOLERANCE_RADII} of the frame's median radius: on HeLa 01 two real cells are never
 * closer than 4.9 um, against ~4 um for that tolerance at a 10 um radius.</p>
 *
 * <p>One topic per question, not per rule: the onset, end and size-jump questions all ask "one cell or
 * two?" about a detection, so an answer to any of them answers the others.</p>
 */
final class AnswerMemory
{
	/** What the question was about. */
	enum Topic
	{
		/** One detection: one cell or two. */
		CELLS,
		/** A mother with two branches: a real division or one cell. */
		DIVISION
	}

	/** What the user said, or what the run decided on its own after "stop asking". */
	enum Said
	{
		/** One cell / a real division: leave it. */
		KEEP,
		/** Two cells: split it. */
		SPLIT,
		/** Not a division: cut the branch that jumped. */
		CUT,
		/** Fixed by hand in the viewer: leave whatever is there now. */
		HAND
	}

	/** An answer is found again within this many of the frame's median radius. */
	static final double TOLERANCE_RADII = 0.4;

	static final String KEY = "autocorrect.answer_memory";

	/** {x, y, said ordinal} per topic and timepoint. */
	private final Map< Topic, Map< Integer, List< double[] > > > byTopic = new HashMap<>();

	/** The run's memory, created on first use. */
	static AnswerMemory of( final ToolContext ctx )
	{
		synchronized ( ctx )
		{
			final Object o = ctx.getSharedData( KEY );
			if ( o instanceof AnswerMemory )
				return ( AnswerMemory ) o;
			final AnswerMemory m = new AnswerMemory();
			ctx.setSharedData( KEY, m );
			return m;
		}
	}

	/** Records {@code said} at {@code (t, p)}, replacing an earlier answer at the same place. */
	synchronized void remember( final Topic topic, final int t, final double[] p, final Said said,
			final double frameRadius )
	{
		final List< double[] > here = byTopic.computeIfAbsent( topic, k -> new HashMap<>() )
				.computeIfAbsent( t, k -> new ArrayList<>() );
		final double tol = TOLERANCE_RADII * frameRadius;
		here.removeIf( e -> Math.hypot( e[ 0 ] - p[ 0 ], e[ 1 ] - p[ 1 ] ) <= tol );
		here.add( new double[] { p[ 0 ], p[ 1 ], said.ordinal() } );
	}

	/** The answer nearest {@code (t, p)} within the tolerance, or null when there is none. */
	synchronized Said recall( final Topic topic, final int t, final double[] p, final double frameRadius )
	{
		final Map< Integer, List< double[] > > m = byTopic.get( topic );
		final List< double[] > here = m == null ? null : m.get( t );
		if ( here == null )
			return null;
		final double tol = TOLERANCE_RADII * frameRadius;
		double best = Double.POSITIVE_INFINITY;
		Said said = null;
		for ( final double[] e : here )
		{
			final double d = Math.hypot( e[ 0 ] - p[ 0 ], e[ 1 ] - p[ 1 ] );
			if ( d <= tol && d < best )
			{
				best = d;
				said = Said.values()[ ( int ) e[ 2 ] ];
			}
		}
		return said;
	}
}
