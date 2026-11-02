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
package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class ReviewRadiusRangeTest
{
	@Test
	public void noPreviousWindowSearchesTheWholeRange()
	{
		assertArrayEquals( new double[] { 1.0, 10.0 }, ElephantTool.reviewRadiusRange( Double.NaN ), 1e-9 );
	}

	@Test
	public void followsThePreviousWindow()
	{
		assertArrayEquals( new double[] { 1.5, 6.0 }, ElephantTool.reviewRadiusRange( 3.0 ), 1e-9 );
	}

	@Test
	public void neverDescendsBelowTheFloor()
	{
		// CE 01 from t=190: each window measured the bottom rung and the radius halved, 1.27 -> 0.64 -> 0.32.
		assertArrayEquals( new double[] { 1.0, 2.54 }, ElephantTool.reviewRadiusRange( 1.27 ), 1e-9 );
		assertArrayEquals( new double[] { 1.0, 1.28 }, ElephantTool.reviewRadiusRange( 0.64 ), 1e-9 );
		assertArrayEquals( new double[] { 1.0, 1.0 }, ElephantTool.reviewRadiusRange( 0.16 ), 1e-9 );
	}

	@Test
	public void neverRisesAboveTheCeiling()
	{
		assertArrayEquals( new double[] { 4.5, 10.0 }, ElephantTool.reviewRadiusRange( 9.0 ), 1e-9 );
		assertArrayEquals( new double[] { 10.0, 10.0 }, ElephantTool.reviewRadiusRange( 40.0 ), 1e-9 );
	}
}
