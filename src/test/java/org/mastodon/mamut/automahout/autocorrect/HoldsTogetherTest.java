package org.mastodon.mamut.automahout.autocorrect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Within one frame, two detections 1.9 cell-radii apart cannot be told from two touching cells: the
 * valley test fuses 22% of genuinely distinct pairs at that range, and a connected-region test fuses
 * 69%. Over time they can be: two tiles of one cell hold a near-constant offset, and division
 * daughters drift apart even while they stay close.
 *
 * <p>The three constants were measured against CTC tracking ground truth for Fluo-N2DL-HeLa — 90
 * real divisions and 16621 pairs of unrelated co-existing tracks — and together fire on 1 of 90
 * divisions and 1 of 16621 neighbour pairs. See benchmark/merge-valley. These hold the arithmetic
 * that uses them.</p>
 */
public class HoldsTogetherTest
{
	private static final double SEP = AutoCorrectOrchestrator.COLOCATED_MAX_SEPARATION;

	private static final int FRAMES = AutoCorrectOrchestrator.COLOCATED_MIN_FRAMES;

	private static final double DRIFT = AutoCorrectOrchestrator.COLOCATED_MAX_DRIFT;

	private static boolean holds( final double... sep )
	{
		return AutoCorrectOrchestrator.holdsTogether( sep, SEP, FRAMES, DRIFT );
	}

	private static double[] constant( final int n, final double v )
	{
		final double[] out = new double[ n ];
		java.util.Arrays.fill( out, v );
		return out;
	}

	@Test
	public void theReportedOverSegmentationHoldsTogether()
	{
		// The measured case: two detections 117 px apart with 64 px radii, holding that offset.
		assertTrue( holds( constant( FRAMES, 1.82 ) ) );
	}

	@Test
	public void daughtersThatDriftApartDoNot()
	{
		// Real daughters start at 1.87 r (p05) and separate. Even a slow one exceeds the drift budget.
		final double[] sep = new double[ FRAMES ];
		for ( int i = 0; i < sep.length; i++ )
			sep[ i ] = 1.50 + 0.04 * i; // 0.44 r of drift across the window
		assertFalse( holds( sep ) );
	}

	@Test
	public void twoCellsSittingSideBySideAreNotMergedJustForBeingClose()
	{
		// Closeness alone is not the signal — 16621 unrelated pairs in the ground truth spend time
		// near each other. Steadiness at close range is.
		assertFalse( holds( constant( FRAMES, 2.4 ) ) );
	}

	@Test
	public void aShortEncounterIsNotEnough()
	{
		assertFalse( holds( constant( FRAMES - 1, 1.5 ) ) );
		assertTrue( holds( constant( FRAMES, 1.5 ) ) );
	}

	@Test
	public void theRunMustBeConsecutive()
	{
		// Together, apart, together: two stretches of FRAMES-1, so neither is long enough on its own,
		// and the rule must not add them up.
		final double[] sep = new double[ 2 * ( FRAMES - 1 ) + 1 ];
		for ( int i = 0; i < sep.length; i++ )
			sep[ i ] = i == FRAMES - 1 ? 3.0 : 1.5;
		assertFalse( holds( sep ) );
		// One frame longer after the gap and it does qualify — the boundary is where it is claimed.
		final double[] longer = new double[ sep.length + 1 ];
		for ( int i = 0; i < longer.length; i++ )
			longer[ i ] = i == FRAMES - 1 ? 3.0 : 1.5;
		assertTrue( holds( longer ) );
	}

	@Test
	public void aLongSeriesFiresOnAnyQualifyingWindowInsideIt()
	{
		// A cell may be over-segmented only for part of its life.
		final double[] sep = new double[ 40 ];
		for ( int i = 0; i < sep.length; i++ )
			sep[ i ] = i >= 20 && i < 20 + FRAMES ? 1.5 : 3.0;
		assertTrue( holds( sep ) );
	}

	@Test
	public void nothingToJudgeIsNotAMerge()
	{
		assertFalse( AutoCorrectOrchestrator.holdsTogether( null, SEP, FRAMES, DRIFT ) );
		assertFalse( holds( new double[ 0 ] ) );
	}

	@Test
	public void theConstantsAreTheOnesThatWereMeasured()
	{
		// Changing any of them changes the false-merge rate on real divisions, which is measured, not
		// chosen. Re-run benchmark/merge-valley/measure_temporal.py before touching these.
		org.junit.jupiter.api.Assertions.assertEquals( 1.9, SEP, 1e-9 );
		org.junit.jupiter.api.Assertions.assertEquals( 12, FRAMES );
		org.junit.jupiter.api.Assertions.assertEquals( 0.15, DRIFT, 1e-9 );
	}
}
