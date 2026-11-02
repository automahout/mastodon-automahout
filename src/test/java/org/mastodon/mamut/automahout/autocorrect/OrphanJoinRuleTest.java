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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * The report that says a join was possible must use the joiner's own rule.
 *
 * <p>A run printed "mid-movie starts=95, of which 24 had a mother within 5.8 µm one timepoint
 * earlier with room for another child — those are joins this run could have made and did not". That
 * verdict was computed from distance and a free successor slot ALONE: not the radius-scaled bound,
 * not the daughter-size gate, and without reading a radius at all. So the number could not be
 * checked against the thing it described, and there was no way to tell whether the joiner was
 * broken or the report was wrong about what was possible.</p>
 */
public class OrphanJoinRuleTest
{
	private static final double FACTOR = 3.0, CAP = 5.8;

	@Test
	public void aPredecessorWithTwoChildrenAlreadyHasNoRoom()
	{
		assertEquals( "no free successor slot",
				AutoCorrectOrchestrator.orphanJoinRefusal( 1.0, 5.0, 5.0, 2, FACTOR, CAP ) );
	}

	/**
	 * The exemption the division-displacement measurement bought: a mitosis is not ordinary motion,
	 * so the per-frame cap applies to a 1:1 continuation and not to a link that makes the
	 * predecessor divide.
	 */
	@Test
	public void theDisplacementCapBindsAContinuationAndNotADivision()
	{
		assertEquals( "beyond the per-frame displacement cap",
				AutoCorrectOrchestrator.orphanJoinRefusal( 9.0, 5.0, 5.0, 0, FACTOR, CAP ) );
		assertNull( "a division step of 9 µm is ordinary for a mitosis and must not be refused",
				AutoCorrectOrchestrator.orphanJoinRefusal( 9.0, 5.0, 5.0, 1, FACTOR, CAP ) );
	}

	@Test
	public void theRadiusScaledBoundStillAppliesToADivision()
	{
		assertNotNull( "3 radii is the bound a division keeps",
				AutoCorrectOrchestrator.orphanJoinRefusal( 16.0, 5.0, 5.0, 1, FACTOR, CAP ) );
		assertNull( AutoCorrectOrchestrator.orphanJoinRefusal( 14.0, 5.0, 5.0, 1, FACTOR, CAP ) );
	}

	/** A daughter meaningfully bigger than its mother is an unrelated cell, not a division. */
	@Test
	public void aDaughterMayNotBeMuchLargerThanItsMother()
	{
		assertNull( AutoCorrectOrchestrator.orphanJoinRefusal( 2.0, 5.0, 5.0, 1, FACTOR, CAP ) );
		assertNotNull( AutoCorrectOrchestrator.orphanJoinRefusal( 2.0, 9.0, 5.0, 1, FACTOR, CAP ) );
	}

	/** A 1:1 continuation is the same cell, so it gets the looser same-size gate, not the daughter one. */
	@Test
	public void aContinuationIsJudgedAsTheSameCellNotAsADaughter()
	{
		assertNull( "1.4x is fine for the same cell across one frame",
				AutoCorrectOrchestrator.orphanJoinRefusal( 2.0, 7.0, 5.0, 0, FACTOR, CAP ) );
		assertNotNull( AutoCorrectOrchestrator.orphanJoinRefusal( 2.0, 20.0, 5.0, 0, FACTOR, CAP ) );
	}

	/** Ratchet: one rule, used by both the joiner and the line that reports on it. */
	@Test
	public void theReportAndTheJoinerShareOneRule() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
						.replaceAll( "//[^\\n]*", "" );
		int uses = 0, from = 0;
		while ( ( from = src.indexOf( "orphanJoinRefusal(", from + 1 ) ) > 0 )
			uses++;
		assertTrue( "the joiner applies it twice and the report once: " + uses, uses >= 4 );
	}

	/**
	 * The refractory ceiling. Measured on the CTC TRACKING truth, the interval between a division and
	 * its ancestor's own: the shortest real one is 29 frames in sequence 01 but 3 in sequence 02, so
	 * an estimate that looks safe on one is not safe on the other.
	 */
	@Test
	public void theRefractoryThresholdIsCappedWhereNothingRealIsLost()
	{
		assertEquals( "20 costs 0.0% of seq 01 and 3.7% of seq 02; 31 costs 8.9% and 26.7%",
				20, AutoCorrectOrchestrator.MAX_AUTO_MIN_INTERVAL );
	}
}
