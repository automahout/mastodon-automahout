package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A tuning round that has started amplifying does so on EVERY timepoint. The round that took
 * Fluo-N2DL-HeLA_01 to 6.5x read 532, 534, 514, 516, 514, 541, 568, 592, 580, 628 against 84 before
 * it — the first frame said everything the tenth did. Until this was asked of the first frame, the
 * window paid for all ten, and each one dragged the per-timepoint cleanup along: a model already
 * condemned deleted 524 spots and fed the flat-contrast statistics on its way out.
 */
public class TuningRoundFloodTest
{
	/** The run's own numbers: raw spots/tp before the round, and at its first timepoint. */
	private static final double BEFORE = 84.3;

	private static final double FIRST_TIMEPOINT = 532;

	@Test
	public void theFirstTimepointIsEnoughToCondemnTheRound()
	{
		assertTrue( ElephantTool.tuningRoundIsFlooding( true, BEFORE, FIRST_TIMEPOINT ) );
	}

	@Test
	public void theSameQuestionDecidesTheRestOfTheWindow()
	{
		// One predicate, asked twice, so the probe cannot condemn a round the end-of-round check would
		// have kept, or vice versa. 551.9 is what the full round averaged.
		assertTrue( ElephantTool.tuningRoundIsFlooding( true, BEFORE, 551.9 ) );
	}

	@Test
	public void ordinaryRecoveryIsNotAFlood()
	{
		// The loop exists to let detection improve; growth up to the limit is the point of running it.
		assertFalse( ElephantTool.tuningRoundIsFlooding( true, 84.3, 84.3 * 1.29 ) );
		assertTrue( ElephantTool.tuningRoundIsFlooding( true, 84.3, 84.3 * 1.31 ) );
	}

	@Test
	public void aRoundThatDidNotTrainHasNothingToAnswerFor()
	{
		assertFalse( ElephantTool.tuningRoundIsFlooding( false, BEFORE, FIRST_TIMEPOINT ) );
	}

	@Test
	public void withNoBaselineThereIsNothingToCompareAgainst()
	{
		// First round of a cold window: no prior raw count, so growth is not measurable and the round is
		// not condemned on a number that does not exist.
		assertFalse( ElephantTool.tuningRoundIsFlooding( true, 0.0, FIRST_TIMEPOINT ) );
		assertFalse( ElephantTool.tuningRoundIsFlooding( true, -1.0, FIRST_TIMEPOINT ) );
	}

	@Test
	public void aShrinkingRoundIsNotCaughtHere()
	{
		// Collapse is a different failure with its own guard; this one only answers over-detection.
		assertFalse( ElephantTool.tuningRoundIsFlooding( true, BEFORE, 12.0 ) );
	}
}
