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

/**
 * Whether a tool suits the data, how well, and why -- the rule half of the selection. The planner keeps
 * the tools that apply; among them the LLM chooses. The reason is shown to the LLM and in the log, so
 * a tool that is left out says why ("fluorescence only; this movie reads as brightfield").
 */
public final class Applicability
{
	public final boolean applies;

	/** In [0, 1], higher = better suited; meaningful only among tools that apply. */
	public final double score;

	public final String reason;

	private Applicability( final boolean applies, final double score, final String reason )
	{
		this.applies = applies;
		this.score = score;
		this.reason = reason;
	}

	/** Applies to any data; nothing particular to say. */
	public static Applicability always()
	{
		return new Applicability( true, 0.5, "applies to any data" );
	}

	public static Applicability suits( final double score, final String reason )
	{
		return new Applicability( true, Math.max( 0, Math.min( 1, score ) ), reason );
	}

	public static Applicability notFor( final String reason )
	{
		return new Applicability( false, 0, reason );
	}

	@Override
	public String toString()
	{
		return applies ? String.format( "applies (%.2f): %s", score, reason ) : "does not apply: " + reason;
	}
}
