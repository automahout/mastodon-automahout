package org.mastodon.mamut.automahout.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate that decides whether auto.correct (Phase 2) may run without adaptive_track (Phase 1)
 * running first in this session.
 * <p>
 * The gate used to be a plain "did adaptive_track run this session?" flag, which made it impossible
 * for the user to say "tracking is already done, only clean it up": the agent was pushed back into
 * Phase 1, and Phase 1 clears each window's existing spots before re-detecting. The gate now asks
 * whether there is a tracking result to clean up at all.
 */
class FinishedTrackingGateTest
{
	@Test
	@DisplayName( "a finished run from a previous session opens the gate" )
	void aFinishedRunFromAPreviousSessionOpensTheGate()
	{
		// The case that prompted this: 49361 spots over t=0..499, 96% continuity.
		assertTrue( LlmOrchestrator.isFinishedTracking( 49361, 500, 48000, 46080 ) );
	}

	@Test
	@DisplayName( "an empty project keeps the gate shut" )
	void anEmptyProjectKeepsTheGateShut()
	{
		assertFalse( LlmOrchestrator.isFinishedTracking( 0, 0, 0, 0 ) );
	}

	@Test
	@DisplayName( "a handful of spots surviving a reset is not a tracking result" )
	void aHandfulOfSpotsSurvivingAResetIsNotATrackingResult()
	{
		// Protected spots kept by "Start completely fresh": few, but perfectly linked.
		assertFalse( LlmOrchestrator.isFinishedTracking( 40, 40, 38, 38 ) );
	}

	@Test
	@DisplayName( "one densely detected frame is not a tracking result" )
	void oneDenselyDetectedFrameIsNotATrackingResult()
	{
		// Plenty of spots, but they sit in a single timepoint — nothing was tracked.
		assertFalse( LlmOrchestrator.isFinishedTracking( 5000, 1, 0, 0 ) );
	}

	@Test
	@DisplayName( "a large but fragmented graph is sent back to Phase 1" )
	void aLargeButFragmentedGraphIsSentBackToPhase1()
	{
		// Detection ran wide, linking failed: cleaning fragmented tracks does not fix fragmentation.
		assertFalse( LlmOrchestrator.isFinishedTracking( 40000, 500, 38000, 11400 ) );
	}

	@Test
	@DisplayName( "the continuity threshold is inclusive" )
	void theContinuityThresholdIsInclusive()
	{
		assertTrue( LlmOrchestrator.isFinishedTracking( 1000, 100, 1000, 700 ) );
		assertFalse( LlmOrchestrator.isFinishedTracking( 1000, 100, 1000, 699 ) );
	}

	@Test
	@DisplayName( "spots spanning too few timepoints are not a tracking result" )
	void spotsSpanningTooFewTimepointsAreNotATrackingResult()
	{
		// A stub of a run: many spots, high continuity, but only a few frames deep.
		assertFalse( LlmOrchestrator.isFinishedTracking( 2000, 10, 1800, 1750 ) );
	}
}
