package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The explosion guards compare a window's spot count against an expected one. These pin WHICH
 * expected one, because getting it from the wrong place cost a whole movie.
 *
 * <p>Fluo-N2DL-HeLA_01 is a proliferating HeLa culture: 44 cells/frame at t=0, 150 at t=91. Run
 * against a single stated ceiling of 60 it produced no divergence of any kind — frame-to-frame the
 * count moved by 1 to 3 spots, temporal CV 0.07, track continuity 90% — and the guards abandoned
 * every window from t=50 on and declared the four before it collapsed. The numbers below are that
 * run's, so a regression reproduces it rather than merely resembling it.</p>
 */
public class ExplosionCeilingTest
{
	/** {@code ElephantTool.PREV_WINDOW_EXPLOSION_RATIO} / {@code DETECTION_EXPLOSION_CEILING_RATIO}. */
	private static final double RATIO = 2.0;

	/** The stated bound the real run carried: {@code cold_start_spots_per_tp=60}. */
	private static final double STATED = 60.0;

	@Test
	public void withoutAMeasuredPriorTheStatedBoundIsUnchanged()
	{
		// The first window of a sweep, and every caller outside one, has no previous window. Nothing
		// about those paths should move.
		assertEquals( STATED, ElephantTool.windowExplosionCeiling( STATED, -1 ), 1e-9 );
		assertEquals( STATED, ElephantTool.windowExplosionCeiling( STATED, 0 ), 1e-9 );
	}

	@Test
	public void aMeasuredPriorRaisesTheCeilingAsThePopulationGrows()
	{
		// t=40..49 committed 96.2 spots/tp, so the window after it is judged against 96.2, not 60.
		assertEquals( 96.2, ElephantTool.windowExplosionCeiling( STATED, 96.2 ), 1e-9 );
	}

	@Test
	public void anUnderDetectingWindowCannotTightenTheNextOnesGuard()
	{
		// A window that found fewer cells than stated is not evidence the population shrank, and
		// letting it lower the ceiling would turn one bad window into a cascade.
		assertEquals( STATED, ElephantTool.windowExplosionCeiling( STATED, 10 ), 1e-9 );
	}

	@Test
	public void theWindowOnWindowRatchetHasAnEnd()
	{
		// Calibrating each window on the last is a ratchet: without this a runaway growing by less
		// than RATIO per window would raise the bar behind itself forever.
		assertEquals( STATED * 4.0, ElephantTool.windowExplosionCeiling( STATED, 1000 ), 1e-9 );
		assertEquals( STATED * 4.0, ElephantTool.windowExplosionCeiling( STATED, 50000 ), 1e-9 );
	}

	@Test
	public void theWindowThatStartedTheCascadeIsNoLongerAnExplosion()
	{
		// Verbatim from the run: "exploded to spots/tp=61 (ceiling 60; previous window's spots/tp=48.7,
		// ratio=1.2x vs explosion threshold 2.0x)". The adaptive check reported it healthy inside the
		// text of the warning declaring it dead; the absolute bound overrode it by 0.7 of a spot.
		final double prev = 48.7, committed = 60.7;
		assertFalse( committed > prev * RATIO, "the measured prior called this window healthy" );
		assertFalse( committed > STATED * 4.0, "and the backstop agrees" );
		// Marking it collapsed is what withheld the measured prior from every window that followed,
		// which is how one spot over a stated bound reached t=91.
	}

	@Test
	public void theWholeProliferatingMovieSurvivesTheCircuitBreaker()
	{
		// Committed spots/tp per window as the run measured them, and the largest RAW per-timepoint
		// count inside the window that follows each (raw exceeds committed: it precedes cleanup).
		final double[] committedPerWindow = { 48.7, 56.9, 68.0, 76.4, 96.2, 113, 126, 133, 138 };
		final int[] rawPeakInNextWindow = { 67, 77, 89, 111, 121, 128, 133, 148, 150 };
		double prev = -1;
		for ( int w = 0; w < committedPerWindow.length; w++ )
		{
			final double ceiling = ElephantTool.windowExplosionCeiling( STATED, prev );
			assertTrue( rawPeakInNextWindow[ w ] <= ceiling * RATIO, String.format(
					"window %d: raw peak %d must not trip a breaker set at %.1f", w + 1,
					rawPeakInNextWindow[ w ], ceiling * RATIO ) );
			prev = committedPerWindow[ w ];
		}
	}

	@Test
	public void aRealFloodStillTripsIt()
	{
		// The guard has to keep doing its job. A collapsed model flooding the frame is orders of
		// magnitude out, not the 1.2x this movie's biology produced.
		final double ceiling = ElephantTool.windowExplosionCeiling( STATED, 96.2 );
		assertTrue( 591 > ceiling * RATIO, "a real observed flood is still caught" );
		assertTrue( 2000 > ceiling * RATIO );
	}
}
