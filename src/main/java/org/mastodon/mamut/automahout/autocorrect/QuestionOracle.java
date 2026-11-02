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

import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * Answers the run's "which is right?" questions instead of a person: the benchmark's ground-truth oracle
 * (condition A+O, docs/paper/benchmark-protocol.md). Put in the run's shared data under {@link #SHARED_KEY}; the
 * question sites then ask it, whether or not a dialog could be shown, and never the person. A {@code null}
 * answer means "cannot say", taken as the dialog's fallback (keep the detection; keep the division).
 */
public interface QuestionOracle
{
	String SHARED_KEY = "autocorrect.question_oracle";

	/** Whether the detection at {@code t} with this centre and shape (world units) covers two cells. */
	Boolean twoCells( int t, double[] centre, double[][] cov );

	/** Whether the cell at {@code mother} (time {@code t}) really divides into {@code stays} and {@code jumps} at t+1. */
	Boolean divides( int t, double[] mother, double[] stays, double[] jumps );

	static QuestionOracle of( final ToolContext ctx )
	{
		final Object o = ctx.getSharedData( SHARED_KEY );
		return o instanceof QuestionOracle ? ( QuestionOracle ) o : null;
	}
}
