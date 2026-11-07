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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RunRangeTest
{
	@Test
	public void nothingStatedIsTheWholeMovie()
	{
		final ToolContext ctx = new ToolContext( null );
		assertArrayEquals( new int[] { 0, 91 }, RunRange.bounds( ctx, 92 ) );
		assertFalse( RunRange.restricts( RunRange.bounds( ctx, 92 ), 92 ) );
	}

	@Test
	public void aNegativeEndIsTheLastTimepoint()
	{
		final ToolContext ctx = new ToolContext( null );
		RunRange.set( ctx, 10, -1 );
		assertArrayEquals( new int[] { 10, 91 }, RunRange.bounds( ctx, 92 ) );
		assertTrue( RunRange.restricts( RunRange.bounds( ctx, 92 ), 92 ) );
	}

	@Test
	public void anEndPastTheMovieIsClampedAndAStartPastTheEndIsTheEnd()
	{
		final ToolContext ctx = new ToolContext( null );
		RunRange.set( ctx, 5, 500 );
		assertArrayEquals( new int[] { 5, 91 }, RunRange.bounds( ctx, 92 ) );
		RunRange.set( ctx, 80, 40 );
		assertArrayEquals( new int[] { 40, 40 }, RunRange.bounds( ctx, 92 ) );
	}

	@Test( expected = IllegalArgumentException.class )
	public void aNegativeStartIsRefused()
	{
		RunRange.set( new ToolContext( null ), -1, -1 );
	}
}
