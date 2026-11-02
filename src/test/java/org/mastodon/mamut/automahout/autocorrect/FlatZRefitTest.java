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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** {@link AutoCorrectOrchestrator#zRun}: where a one-plane detection's nucleus lies along its z profile. */
public class FlatZRefitTest
{
	@Test
	public void aDetectionOnePlaneOffClimbsToTheNucleusAndTakesItsHalfMaximumRun()
	{
		// Floor 10, a nucleus peaking at plane 5 (100), above half (55) on planes 4..6; detected on plane 4.
		final double[] prof = { 10, 10, 12, 30, 80, 100, 80, 30, 10 };
		final double[] run = AutoCorrectOrchestrator.zRun( prof, 4 );
		assertEquals( 5.0, run[ 0 ], 1e-9 );
		assertEquals( 3.0, run[ 1 ], 0 );
	}

	@Test
	public void theCentreIsTheBrightnessWeightedMeanOfTheRun()
	{
		final double[] prof = { 0, 0, 60, 100, 0 };
		final double[] run = AutoCorrectOrchestrator.zRun( prof, 2 );
		assertEquals( ( 60.0 * 2 + 100.0 * 3 ) / 160.0, run[ 0 ], 1e-9 );
		assertEquals( 2.0, run[ 1 ], 0 );
	}

	@Test
	public void itClimbsToTheNearestPeakNotTheBrightestOne()
	{
		// A dimmer nucleus at plane 1 under the detection, a brighter neighbour at plane 6 past a valley.
		final double[] prof = { 40, 60, 40, 5, 5, 50, 200, 50 };
		final double[] run = AutoCorrectOrchestrator.zRun( prof, 2 );
		assertEquals( 1.0, run[ 0 ], 0.5 );
	}

	@Test
	public void aFlatProfileGivesNoReading()
	{
		assertNull( AutoCorrectOrchestrator.zRun( new double[] { 7, 7, 7, 7 }, 1 ) );
	}
}
