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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * A timepoint slightly over the detection threshold must not throw the window away.
 *
 * <p>Two questions, and they were being answered by one count. "Is this timepoint usable?" is
 * answered by the threshold. "Has the model diverged?" is answered by abandoning the window, which
 * costs ten timepoints of work. The tally counted every overshoot however small, so two marginal
 * ones abandoned the window: observed at 233 raw against a threshold of 200 and 226 against 208 —
 * 17% and 9% over — in a run whose genuine divergences were 16609 and 4954 against 200, that is 83
 * times and 24 times over.</p>
 */
public class DivergenceCountRatioTest
{
	private static final Path SRC = Paths.get( "src", "main", "java", "org", "mastodon", "mamut",
			"automahout", "tool", "impl", "ElephantTool.java" );

	/** The observed numbers, as multiples of the threshold they were measured against. */
	private static final double FALSE_ALARM_LOW = 226.0 / 208.0;   // 1.09

	private static final double FALSE_ALARM_HIGH = 233.0 / 200.0;  // 1.17

	private static final double REAL_DIVERGENCE_LOW = 4954.0 / 200.0;  // 24.8

	private static double ratioFromSource() throws IOException
	{
		final String text = new String( Files.readAllBytes( SRC ), StandardCharsets.UTF_8 );
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
				"DIVERGENCE_COUNT_RATIO\\s*=\\s*([0-9.]+)" ).matcher( text );
		assertTrue( "DIVERGENCE_COUNT_RATIO not found", m.find() );
		return Double.parseDouble( m.group( 1 ) );
	}

	@Test
	public void theObservedFalseAlarmsDoNotCountAsDivergence() throws IOException
	{
		final double ratio = ratioFromSource();
		assertFalse( "226 raw against a threshold of 208 would still abandon the window",
				FALSE_ALARM_LOW > ratio );
		assertFalse( "233 raw against a threshold of 200 would still abandon the window",
				FALSE_ALARM_HIGH > ratio );
	}

	@Test
	public void theObservedDivergencesStillDo() throws IOException
	{
		final double ratio = ratioFromSource();
		assertTrue( "4954 raw against a threshold of 200 must still abandon the window",
				REAL_DIVERGENCE_LOW > ratio );
	}

	@Test
	public void theTallyOnlyCountsWhatIsMeaningfullyOver() throws IOException
	{
		// The shape of the bug: explodedTps++ ran on every overshoot, unconditionally.
		final String text = new String( Files.readAllBytes( SRC ), StandardCharsets.UTF_8 );
		final int inc = text.indexOf( "explodedTps++" );
		assertTrue( "explodedTps++ not found", inc > 0 );
		final String before = text.substring( Math.max( 0, inc - 600 ), inc );
		assertTrue( "the tally must be gated on DIVERGENCE_COUNT_RATIO, or a 9% overshoot still "
				+ "abandons the window", before.contains( "countsAsDivergence" ) );
	}
}
