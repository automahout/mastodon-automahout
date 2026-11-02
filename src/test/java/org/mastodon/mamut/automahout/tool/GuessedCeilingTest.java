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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class GuessedCeilingTest
{
	private static final String OVERRIDE = "test.ceiling_override";

	private static ToolContext answering( final String answer, final List< List< String > > optionsSeen )
	{
		final ToolContext ctx = new ToolContext( null );
		ctx.setAskCallback( ( q, options ) -> {
			optionsSeen.add( options );
			return answer;
		} );
		return ctx;
	}

	@Test
	public void keepingWithoutANumberTakesTheDetectedCountAsTheUsersOwn()
	{
		final ToolContext ctx = answering( GuessedCeiling.KEEP, new java.util.ArrayList<>() );
		assertTrue( GuessedCeiling.isUnconfirmedGuess( ctx, false ) );
		assertEquals( GuessedCeiling.Decision.KEEP,
				GuessedCeiling.ask( ctx, OVERRIDE, "Detected 45.", 55.0, 45, 0 ) );
		assertEquals( 45, ExpectedCellCount.statedCellsPerFrame( ctx ) );
		assertEquals( ExpectedCellCount.statedCeiling( 45 ),
				( ( Number ) ctx.getSharedData( OVERRIDE ) ).doubleValue(), 0.0 );
		assertNull( ctx.getSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_AT_END ) );
		assertFalse( "answered once, never again", GuessedCeiling.isUnconfirmedGuess( ctx, false ) );
	}

	@Test
	public void typedCountsSetBothEndsAndTheCeilingOnTheLargerOne()
	{
		final ToolContext ctx = answering( "43 137", new java.util.ArrayList<>() );
		assertEquals( GuessedCeiling.Decision.KEEP,
				GuessedCeiling.ask( ctx, OVERRIDE, "Detected 45.", 55.0, 45, 0 ) );
		assertEquals( 43, ExpectedCellCount.statedCellsPerFrame( ctx ) );
		assertEquals( 137, ( ( Number ) ctx.getSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_AT_END ) )
				.intValue() );
		assertEquals( ExpectedCellCount.statedCeiling( 137 ),
				( ( Number ) ctx.getSharedData( OVERRIDE ) ).doubleValue(), 0.0 );
	}

	@Test
	public void rollingBackUpholdsTheGuessAndStopsAsking()
	{
		final ToolContext ctx = answering( GuessedCeiling.ROLL_BACK, new java.util.ArrayList<>() );
		assertEquals( GuessedCeiling.Decision.ROLL_BACK,
				GuessedCeiling.ask( ctx, OVERRIDE, "Detected 45.", 55.0, 45, 0 ) );
		assertEquals( -1, ExpectedCellCount.statedCellsPerFrame( ctx ) );
		assertNull( ctx.getSharedData( OVERRIDE ) );
		assertFalse( GuessedCeiling.isUnconfirmedGuess( ctx, false ) );
	}

	@Test
	public void cancelledOrHeadlessRollsBackAsBefore()
	{
		final ToolContext cancelled = answering( null, new java.util.ArrayList<>() );
		assertEquals( GuessedCeiling.Decision.ROLL_BACK,
				GuessedCeiling.ask( cancelled, OVERRIDE, "Detected 45.", 55.0, 45, 0 ) );
		final ToolContext headless = new ToolContext( null );
		assertEquals( GuessedCeiling.Decision.ROLL_BACK,
				GuessedCeiling.ask( headless, OVERRIDE, "Detected 45.", 55.0, 45, 0 ) );
		assertNull( headless.getSharedData( OVERRIDE ) );
	}

	@Test
	public void aCeilingTheUserStatedIsNeverQuestioned()
	{
		final ToolContext ctx = new ToolContext( null );
		assertFalse( GuessedCeiling.isUnconfirmedGuess( ctx, true ) );
		ctx.setSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_PER_FRAME, 40 );
		assertFalse( GuessedCeiling.isUnconfirmedGuess( ctx, false ) );
	}

	@Test
	public void optionTextsHoldNoDigitsSoPickingOneIsNeverReadAsACount()
	{
		assertArrayEquals( new int[ 0 ], GuessedCeiling.parseCounts( GuessedCeiling.KEEP ) );
		assertArrayEquals( new int[ 0 ], GuessedCeiling.parseCounts( GuessedCeiling.ROLL_BACK ) );
		assertArrayEquals( new int[] { 43, 137 }, GuessedCeiling.parseCounts( "43 -> 137 cells" ) );
		assertArrayEquals( new int[] { 50 }, GuessedCeiling.parseCounts( "0 then 50" ) );
		assertEquals( GuessedCeiling.Decision.ROLL_BACK, GuessedCeiling.decide( "no idea" ) );
	}

	@Test
	public void takenCountsAreWhatTheGuardsReadFromThenOn()
	{
		final ToolContext ctx = new org.mastodon.mamut.automahout.autocorrect.ReplayGraph().context();
		ctx.setSharedData( ExpectedCellCount.SHARED_EXPECTED_RANGE, new int[] { 0, 91 } );
		GuessedCeiling.takeCounts( ctx, "test.ceiling", 43, 158, "you accepted a detector", "its trial counts" );
		org.junit.Assert.assertEquals( 43, ExpectedCellCount.statedCellsPerFrame( ctx ) );
		org.junit.Assert.assertEquals( 158, ctx.getSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_AT_END ) );
		org.junit.Assert.assertEquals( ExpectedCellCount.statedCeiling( 158 ),
				( ( Number ) ctx.getSharedData( "test.ceiling" ) ).doubleValue(), 1e-9 );
		org.junit.Assert.assertFalse( GuessedCeiling.isUnconfirmedGuess( ctx, false ) );
	}
}
