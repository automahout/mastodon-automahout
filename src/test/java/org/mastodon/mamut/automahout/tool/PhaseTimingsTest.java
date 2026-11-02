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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Tests for the per-window timing line that says where a slow window's time actually went. */
public class PhaseTimingsTest
{
	private static final long SEC = 1_000_000_000L;

	private static Map< String, long[] > steps( final Object... nameSecondsCalls )
	{
		final Map< String, long[] > m = new HashMap<>();
		for ( int i = 0; i < nameSecondsCalls.length; i += 3 )
			m.put( ( String ) nameSecondsCalls[ i ],
					new long[] { ( long ) ( ( ( Number ) nameSecondsCalls[ i + 1 ] ).doubleValue() * SEC ),
							( ( Number ) nameSecondsCalls[ i + 2 ] ).longValue() } );
		return m;
	}

	@Test
	public void theStepThatTookTheTimeComesFirst()
	{
		final String out = PhaseTimings.summary(
				steps( "ELEPHANT predict", 40, 10, "flat-contrast check", 900, 10, "size filter", 2, 10 ),
				new HashMap<>() );
		assertTrue( out.indexOf( "flat-contrast check" ) < out.indexOf( "ELEPHANT predict" ), out );
		assertTrue( out.contains( "15m00s" ), out );
		assertTrue( out.contains( "(10x)" ), out );
	}

	@Test
	public void aSharedCostIsReportedSeparatelySoTheTopLevelStillAddsUp()
	{
		// The background estimate is paid INSIDE the flat-contrast check, so adding it to the total
		// would claim more time than the window actually took.
		final String out = PhaseTimings.summary(
				steps( "flat-contrast check", 900, 10 ),
				steps( "background estimate", 880, 10 ) );
		assertTrue( out.contains( "already counted above" ), out );
		assertTrue( out.contains( "background estimate" ), out );
		assertTrue( out.contains( "15m00s total" ), out );
	}

	@Test
	public void aFastSpanSaysNothingAtAll()
	{
		// One line per window is useful; one line per window on a run that is not slow is noise.
		assertEquals( "", PhaseTimings.summary( steps( "ELEPHANT predict", 1, 4 ), new HashMap<>() ) );
	}

	@Test
	public void theTailIsCollapsedRatherThanListed()
	{
		final Map< String, long[] > many = steps( "big", 600, 1 );
		for ( int i = 0; i < 12; i++ )
			many.put( "small" + i, new long[] { 3 * SEC, 1 } );
		final String out = PhaseTimings.summary( many, new HashMap<>() );
		assertTrue( out.contains( "everything else" ), out );
		assertFalse( out.contains( "small11" ), out );
	}

	@Test
	public void aDurationReadsAsADuration()
	{
		assertEquals( "0.4s", PhaseTimings.format( 400_000_000L ) );
		assertEquals( "9.5s", PhaseTimings.format( 9_500_000_000L ) );
		assertEquals( "22s", PhaseTimings.format( 22 * SEC ) );
		assertEquals( "5m12s", PhaseTimings.format( 312 * SEC ) );
		assertEquals( "1h02m", PhaseTimings.format( 3720 * SEC ) );
	}
}
