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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link ElephantTool#goDecision}: when the cold start may move on from its seeds. */
public class GoDecisionTest
{
	@Test
	public void everySeedFoundWithoutFalseHitsAtTheirSizeIsGo()
	{
		assertTrue( ElephantTool.goDecision( 5, 5, 0, 6, 9, 1.1 ) );
	}

	@Test
	public void aModelThatDetectsNothingTrainsOn()
	{
		assertFalse( ElephantTool.goDecision( 0, 5, 0, 0, 9, Double.NaN ) );
	}

	@Test
	public void aMissedSeedOrAFalseHitTrainsOn()
	{
		assertFalse( ElephantTool.goDecision( 4, 5, 0, 5, 9, 1.0 ) ); // a seed missed: corrected as fn next round
		assertFalse( ElephantTool.goDecision( 5, 5, 1, 6, 9, 1.0 ) ); // one false hit: marked background next round
	}

	@Test
	public void floodingOrTheWrongSizeTrainsOn()
	{
		assertFalse( ElephantTool.goDecision( 5, 5, 0, 30, 9, 1.0 ) ); // duplicates on the seeds and blobs
		assertFalse( ElephantTool.goDecision( 5, 5, 0, 6, 9, 2.0 ) ); // twice the seeds' size: covering neighbours
	}

	@Test
	public void manyReferencesTolerateTheBlobsThatAreNotCells()
	{
		// Blobs at the frame's size: up to 3% on no cell, so a right model cannot reproduce every one.
		assertTrue( ElephantTool.goDecision( 194, 200, 4, 200, 300, 1.0 ) ); // 3% missed, 2% false
		assertFalse( ElephantTool.goDecision( 193, 200, 0, 200, 300, 1.0 ) ); // 7 missed
		assertFalse( ElephantTool.goDecision( 200, 200, 5, 205, 300, 1.0 ) ); // 5 false hits
	}
}
