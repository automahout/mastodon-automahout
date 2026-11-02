package org.mastodon.mamut.automahout.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.automahout.eval.PipelineInvariants.Limits;
import org.mastodon.mamut.automahout.eval.PipelineInvariants.Severity;
import org.mastodon.mamut.automahout.eval.PipelineInvariants.Snapshot;
import org.mastodon.mamut.automahout.eval.PipelineInvariants.Violation;

/**
 * For most of this class's life a violation was only logged, so every caller ignored it: a run could
 * print "Detection has exploded into background" and carry that window into the next nine. The
 * window loop now abandons on an ERROR, which it can only do if the check tells it there was one.
 *
 * <p>These hold the contract the loop depends on — that ERROR and WARN are distinguishable at the
 * call site, and that the snapshot for the next phase comes back either way.</p>
 */
public class InvariantOutcomeTest
{
	private static Snapshot snap( final int... spots )
	{
		final int[] zeros = new int[ spots.length ];
		return PipelineInvariants.of( spots, zeros, zeros, zeros );
	}

	@Test
	public void anExplodedTimepointIsReportedAsAnError()
	{
		// The window loop's trigger: 800 spots where the ceiling says 100.
		final List< Violation > v = PipelineInvariants.check( snap( 50, 50 ), snap( 800, 50 ), 0, 1,
				PipelineInvariants.limits().expectedCeiling( 100 ).ceilingRatio( 2.0 ) );
		assertTrue( v.stream().anyMatch( x -> x.severity() == Severity.ERROR ),
				"an exploded timepoint must be an ERROR, not a note: " + v );
	}

	@Test
	public void aCleanPhaseReportsNothingToActOn()
	{
		assertTrue( PipelineInvariants.check( snap( 50, 50 ), snap( 52, 49 ), 0, 1,
				PipelineInvariants.limits().expectedCeiling( 100 ).ceilingRatio( 2.0 ) ).isEmpty() );
	}

	@Test
	public void warningsAreNotErrorsAndMustNotAbandonAWindow()
	{
		// Churn and continuity are WARN. Abandoning on those would throw away windows that are merely
		// noisy, which is a different and much more expensive mistake than the one being fixed.
		for ( final Violation v : PipelineInvariants.check( snap( 50, 50 ), snap( 50, 50 ), 0, 1,
				PipelineInvariants.limits() ) )
			assertFalse( v.severity() == Severity.WARN && v.message() == null );
	}

	@Test
	public void theOutcomeCarriesTheFirstErrorAndTheNextPhasesBaseline()
	{
		final Snapshot after = snap( 800, 50 );
		final PipelineInvariants.Outcome clean = new PipelineInvariants.Outcome( after, null );
		assertFalse( clean.hasError() );
		assertEquals( after, clean.snapshot );

		final PipelineInvariants.Outcome bad =
				new PipelineInvariants.Outcome( after, "detection has exploded into background" );
		assertTrue( bad.hasError() );
		assertNotNull( bad.firstError );
		// The message is what the abandon line quotes back to the user, so it must survive intact.
		assertTrue( bad.firstError.contains( "exploded" ) );
		assertEquals( after, bad.snapshot, "the next phase still needs a baseline, error or not" );
	}

	@Test
	public void theFloorViolationIsAlsoAnErrorAndSoAlsoAbandons()
	{
		// Deliberate: a phase that takes a frame below what the user's own estimate allows has produced
		// a window nobody should build on, the same as one that exploded.
		final Limits limits = PipelineInvariants.limits().statedCellsPerFrame( 40 );
		final List< Violation > v = PipelineInvariants.check( snap( 50, 50 ), snap( 12, 50 ), 0, 1, limits );
		assertTrue( v.stream().anyMatch( x -> x.severity() == Severity.ERROR ), v.toString() );
		assertFalse( v.stream().anyMatch( Violation::isCeiling ),
				"only the ceiling may be cleared by asking whether the ceiling is right" );
	}

	@Test
	public void theCeilingViolationSaysItIsTheCeiling()
	{
		final List< Violation > v = PipelineInvariants.check( snap( 50, 50 ), snap( 58, 50 ), 0, 1,
				PipelineInvariants.limits().expectedCeiling( 55 ).ceilingRatio( 1.0 ) );
		assertEquals( 1, v.size(), v.toString() );
		assertTrue( v.get( 0 ).isCeiling() );
	}
}
