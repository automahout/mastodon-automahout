package org.mastodon.mamut.automahout.tool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The run-wide explosion ceiling is built on the population's LARGEST end, so on a movie stated to
 * grow 26 -> 300 cells it permits the same count at t=0 as at t=91. Window 1 of Fluo-N2DL-HeLA_01
 * detected 885 spots at t=0 against a stated 26 — thirty-four times the expectation, before a single
 * round of training — and nothing stopped it, because 885 sits comfortably under a bound calibrated
 * for the end of the movie.
 *
 * <p>The interpolation that fixes this was already written and already used by the floor. These hold
 * the arithmetic the per-timepoint guard now asks it for.</p>
 */
public class PerTimepointExpectationTest
{
	/** The run's own numbers, and the ratio the circuit breaker allows on top of the ceiling. */
	private static final int STATED_FIRST = 26;

	private static final int STATED_LAST = 300;

	private static final double BREAKER_RATIO = 2.0;

	@Test
	public void theStatedCeilingIsTwiceWhateverIsExpectedThere()
	{
		assertTrue( ExpectedCellCount.statedCeiling( STATED_FIRST ) == 2.0 * STATED_FIRST );
		assertTrue( ExpectedCellCount.statedCeiling( STATED_LAST ) == 2.0 * STATED_LAST );
	}

	@Test
	public void eightHundredAndEightyFiveAtTheStartIsNowAnExplosion()
	{
		// 26 expected -> ceiling 52 -> breaker at 104. The run detected 885.
		final double threshold = ExpectedCellCount.statedCeiling( STATED_FIRST ) * BREAKER_RATIO;
		assertTrue( 885 > threshold,
				"885 spots where 26 cells are expected must trip the breaker, threshold was " + threshold );
	}

	@Test
	public void theSameCountAtTheEndOfTheMovieIsNotOne()
	{
		// The point of interpolating rather than tightening everywhere: by t=91 the culture really does
		// hold ~300, and 885 there is within what the guard has always allowed.
		final double threshold = ExpectedCellCount.statedCeiling( STATED_LAST ) * BREAKER_RATIO;
		assertTrue( 885 < threshold,
				"a grown population must not be called an explosion, threshold was " + threshold );
	}

	@Test
	public void theOldRunWideBoundWouldHaveLetItThrough()
	{
		// Why this was invisible: the bound in force at t=0 was built on the LAST timepoint's count.
		final double runWide = ExpectedCellCount.statedCeiling( STATED_LAST ) * BREAKER_RATIO;
		assertTrue( 885 < runWide, "this is the behaviour being changed" );
	}

	@Test
	public void noStatedCountLeavesTheGuardExactlyAsItWas()
	{
		// statedCeiling of a non-count is a non-ceiling, and the caller then falls back to the run-wide
		// bound. A user who stated nothing must see no change.
		assertTrue( ExpectedCellCount.statedCeiling( 0 ) <= 0 );
		assertTrue( ExpectedCellCount.statedCeiling( -1 ) <= 0 );
	}
}
