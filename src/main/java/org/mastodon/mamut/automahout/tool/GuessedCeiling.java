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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the user before a cell-count ceiling that nobody stated throws a window away.
 *
 * <p>Every explosion guard measures detections against a ceiling. When the user entered an expected
 * cell count, that ceiling is theirs and a window over it is rolled back without asking. When they
 * did not, the ceiling is the agent's {@code cold_start_spots_per_tp}, which is the vision model's
 * guess at the cell count, and that guess can be wrong. Fluo-N2DL-HeLa 01 holds 43 cells at t=0 and
 * 137 at t=91 (tracking truth). The agent guessed about 30 and asked for a ceiling of 55. Every window
 * was rolled back, the detector was rebuilt from seeds twice, and the run ended with no spots. The
 * detections that were thrown away matched the truth to within a few cells per frame.</p>
 *
 * <p>The guard cannot tell a flood from a guess that was too low. The user can, by looking at the
 * frame, so the run asks. It asks once per run. Whatever the answer, the ceiling stops being a guess:
 * either it now comes from the user, or the user has said the guess stands.</p>
 */
public final class GuessedCeiling
{
	private GuessedCeiling()
	{}

	/**
	 * {@link ToolContext#getSharedData} key, {@link Boolean}: the user was asked and said the ceiling
	 * stands. Set so the question is not asked again for every window.
	 */
	public static final String SHARED_GUESS_UPHELD = "adaptive_track.guessed_ceiling_upheld";

	/** Shown first, so an Enter keeps the detections: the user is looking at them. */
	static final String KEEP = "Keep: these are real cells (use the detected count as the expectation)";

	static final String ROLL_BACK = "Roll back: detection has exploded into background";

	/** What the user decided. */
	public enum Decision
	{
		/** The detections stand. The expected count is now the user's, so retry or carry on. */
		KEEP,
		/** The ceiling stands: roll back as before. Also the answer when nobody could be asked. */
		ROLL_BACK
	}

	/**
	 * {@code true} when the ceiling the guards use came from neither the user nor an earlier answer
	 * to this question.
	 *
	 * @param ceilingFromUser whether a user-stated ceiling override is set (see
	 *        {@code ElephantTool.SHARED_COLD_START_CEILING_OVERRIDE})
	 */
	public static boolean isUnconfirmedGuess( final ToolContext ctx, final boolean ceilingFromUser )
	{
		return !ceilingFromUser
				&& ExpectedCellCount.statedCellsPerFrame( ctx ) <= 0
				&& !Boolean.TRUE.equals( ctx.getSharedData( SHARED_GUESS_UPHELD ) );
	}

	/**
	 * Asks whether the detections over the guessed ceiling are real cells.
	 *
	 * <p>On {@link Decision#KEEP} the expected count is written where the pre-flight dialog writes it,
	 * so every guard that reads {@link ExpectedCellCount} picks it up: the count the user typed, or
	 * else the count detected at the window's first timepoint. The ceiling override is set to the
	 * stated margin over the larger end, as the dialog does.</p>
	 *
	 * @param ceilingOverrideKey the shared-data key of the ceiling override to set on KEEP
	 * @param whatHappened one sentence: what was detected where, ending in a full stop
	 * @param ceiling the ceiling that was crossed
	 * @param detectedCount the count to take as the expectation when the user keeps without typing one
	 * @param countTp the timepoint {@code detectedCount} was taken at
	 */
	public static Decision ask( final ToolContext ctx, final String ceilingOverrideKey,
			final String whatHappened, final double ceiling, final int detectedCount, final int countTp )
	{
		if ( !ctx.canAskUser() )
		{
			ctx.log( String.format( "  The ceiling of %.0f spots/tp is the agent's guess, not a count you "
					+ "stated, but no UI is attached to ask whether these detections are real. Rolling back "
					+ "as before. State an expected cell count to avoid this.", ceiling ) );
			return Decision.ROLL_BACK;
		}
		final String question = String.format(
				"%s That is over the ceiling of %.0f spots per frame.%n%n"
						+ "That ceiling is the agent's guess at the cell count. You did not state one, and the "
						+ "guess can be wrong: on Fluo-N2DL-HeLa it was about half the real count, and every "
						+ "window was thrown away.%n%n"
						+ "Look at the frame. If the spots sit on cells, keep them. If they sit on background, "
						+ "roll back.%n%n"
						+ "You can also type the expected cells per frame: one number, or two for the first and "
						+ "last timepoint of the run (for example \"43 137\").",
				whatHappened, ceiling );
		final String answer = ctx.askUser( question, Arrays.asList( KEEP, ROLL_BACK ) );
		final Decision decision = decide( answer );
		if ( decision == Decision.ROLL_BACK )
		{
			ctx.setSharedData( SHARED_GUESS_UPHELD, Boolean.TRUE );
			ctx.log( "  Ceiling — you said the detections are background"
					+ ( answer == null ? " (no answer)" : "" ) + "; rolling back, and not asking again." );
			return decision;
		}
		final int[] typed = parseCounts( answer );
		final int start = typed.length > 0 ? typed[ 0 ] : Math.max( 1, detectedCount );
		final int end = typed.length > 1 ? typed[ 1 ] : start;
		takeCounts( ctx, ceilingOverrideKey, start, end, "you kept the detections",
				typed.length > 0 ? "as you typed it" : "the count detected at t=" + countTp );
		return decision;
	}

	/**
	 * Takes {@code start} (first timepoint) and {@code end} (last) as the expected cells per frame, written
	 * where the pre-flight dialog writes them, and sets the ceiling override to the stated margin over the
	 * larger -- so every guard reading {@link ExpectedCellCount} uses them from here on.
	 *
	 * @param what what happened, for the log ("you kept the detections")
	 * @param from where the numbers come from, for the log
	 */
	public static void takeCounts( final ToolContext ctx, final String ceilingOverrideKey, final int start,
			final int end, final String what, final String from )
	{
		ctx.setSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_PER_FRAME, start );
		if ( end != start )
			ctx.setSharedData( ExpectedCellCount.SHARED_EXPECTED_CELLS_AT_END, end );
		ctx.setSharedData( ceilingOverrideKey, ExpectedCellCount.statedCeiling( Math.max( start, end ) ) );
		ctx.log( String.format( "  Ceiling — %s. Expected cells per frame is now %s (%s), and the ceiling %.0f, as "
				+ "if you had entered it in the pre-flight dialog.", what,
				end != start ? start + " -> " + end : String.valueOf( start ), from,
				ExpectedCellCount.statedCeiling( Math.max( start, end ) ) ) );
	}

	/**
	 * Reads the answer. {@code null} (cancelled) and the roll-back option mean roll back; the keep
	 * option, or any answer holding a positive count, means keep. Anything else rolls back, because
	 * keeping a flood costs a window of training on background while rolling back costs a retry.
	 */
	static Decision decide( final String answer )
	{
		if ( answer == null )
			return Decision.ROLL_BACK;
		if ( answer.trim().equals( KEEP ) || parseCounts( answer ).length > 0 )
			return Decision.KEEP;
		return Decision.ROLL_BACK;
	}

	private static final Pattern COUNT = Pattern.compile( "\\d+" );

	/**
	 * The positive counts typed in the answer, at most two. The option texts hold no digits, so
	 * picking an option yields none.
	 */
	static int[] parseCounts( final String answer )
	{
		if ( answer == null )
			return new int[ 0 ];
		final List< Integer > found = new ArrayList<>();
		final Matcher m = COUNT.matcher( answer );
		while ( m.find() && found.size() < 2 )
		{
			try
			{
				final int v = Integer.parseInt( m.group() );
				if ( v > 0 )
					found.add( v );
			}
			catch ( final NumberFormatException e )
			{
				// Too long to be a count; ignore it.
			}
		}
		return found.stream().mapToInt( Integer::intValue ).toArray();
	}
}
