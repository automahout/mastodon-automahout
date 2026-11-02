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
package org.mastodon.mamut.automahout.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.automahout.eval.PipelineInvariants.Limits;
import org.mastodon.mamut.automahout.eval.PipelineInvariants.Severity;
import org.mastodon.mamut.automahout.eval.PipelineInvariants.Snapshot;
import org.mastodon.mamut.automahout.eval.PipelineInvariants.Violation;

/**
 * Regression tests for the between-phase structural checks.
 *
 * <p>Each of the first two tests reproduces a bug that actually shipped in this pipeline and was
 * found only by eyeballing a finished run: a deletion pass that ignored the protected tag, and a
 * window-scoped pass that reached back into already-finalized timepoints. They are here so those two
 * failure modes are caught by {@code mvn test} rather than by a multi-hour tracking run.</p>
 */
public class PipelineInvariantsTest
{
	/** 6 timepoints, 10 spots each, all continuous, none protected, none isolated. */
	private static Snapshot flat( final int spotsPerTp )
	{
		final int n = 6;
		final int[] spots = new int[ n ], prot = new int[ n ], cont = new int[ n ], iso = new int[ n ];
		for ( int t = 0; t < n; t++ )
		{
			spots[ t ] = spotsPerTp;
			cont[ t ] = spotsPerTp;
		}
		return PipelineInvariants.of( spots, prot, cont, iso );
	}

	private static Snapshot build( final int[] spots, final int[] prot, final int[] cont )
	{
		return PipelineInvariants.of( spots, prot, cont, new int[ spots.length ] );
	}

	private static boolean hasError( final List< Violation > vs )
	{
		return vs.stream().anyMatch( v -> v.severity() == Severity.ERROR );
	}

	private static String messages( final List< Violation > vs )
	{
		final StringBuilder sb = new StringBuilder();
		for ( final Violation v : vs )
			sb.append( v ).append( '\n' );
		return sb.toString();
	}

	// -----------------------------------------------------------------------
	// 1. Protected spots

	@Test
	public void protectedSpotDeletedIsAnError()
	{
		final int[] spots = { 10, 10, 10, 10, 10, 10 };
		final int[] before = { 0, 0, 3, 0, 0, 0 };
		final int[] after = { 0, 0, 1, 0, 0, 0 };
		final List< Violation > vs = PipelineInvariants.check(
				build( spots, before, spots ), build( spots, after, spots ),
				0, 5, PipelineInvariants.limits() );
		assertTrue( hasError( vs ), "losing a protected spot must be an ERROR: " + messages( vs ) );
		assertTrue( messages( vs ).contains( "t=2" ), "violation should name the timepoint" );
	}

	@Test
	public void protectedSpotLossIsCaughtEvenOutsideTheWindow()
	{
		// The phase claims to be scoped to t=4..5 but untagged a protected spot at t=0. Protection is
		// checked movie-wide precisely because the buggy passes were the ones reaching outside.
		final int[] spots = { 10, 10, 10, 10, 10, 10 };
		final List< Violation > vs = PipelineInvariants.check(
				build( spots, new int[] { 2, 0, 0, 0, 0, 0 }, spots ),
				build( spots, new int[] { 0, 0, 0, 0, 0, 0 }, spots ),
				4, 5, PipelineInvariants.limits() );
		assertTrue( hasError( vs ), messages( vs ) );
	}

	@Test
	public void addingProtectedSpotsIsNotAViolation()
	{
		final int[] spots = { 10, 10, 10, 10, 10, 10 };
		final List< Violation > vs = PipelineInvariants.check(
				build( spots, new int[ 6 ], spots ),
				build( spots, new int[] { 0, 0, 5, 0, 0, 0 }, spots ),
				0, 5, PipelineInvariants.limits() );
		assertTrue( vs.isEmpty(), messages( vs ) );
	}

	// -----------------------------------------------------------------------
	// 2. Window scoping

	@Test
	public void changingATimepointOutsideTheWindowIsAnError()
	{
		// The shipped bug: a pass scoped to t=4..5 deleting spots back at t=0..1.
		final Snapshot before = flat( 10 );
		final Snapshot after = build( new int[] { 2, 3, 10, 10, 10, 10 }, new int[ 6 ],
				new int[] { 2, 3, 10, 10, 10, 10 } );
		final List< Violation > vs = PipelineInvariants.check( before, after, 4, 5,
				PipelineInvariants.limits() );
		assertTrue( hasError( vs ), messages( vs ) );
		assertTrue( messages( vs ).contains( "scoped to t=4..5" ), messages( vs ) );
	}

	@Test
	public void changesInsideTheWindowAreFine()
	{
		final Snapshot before = flat( 10 );
		final Snapshot after = build( new int[] { 10, 10, 10, 10, 4, 4 }, new int[ 6 ],
				new int[] { 10, 10, 10, 10, 4, 4 } );
		final List< Violation > vs = PipelineInvariants.check( before, after, 4, 5,
				PipelineInvariants.limits() );
		assertTrue( vs.isEmpty(), messages( vs ) );
	}

	@Test
	public void marginAllowsTheDeclaredBoundaryReach()
	{
		final Snapshot before = flat( 10 );
		final Snapshot after = build( new int[] { 10, 10, 10, 9, 10, 10 }, new int[ 6 ],
				new int[] { 10, 10, 10, 9, 10, 10 } );
		assertTrue( hasError( PipelineInvariants.check( before, after, 4, 5,
				PipelineInvariants.limits() ) ), "t=3 is outside t=4..5 with no margin" );
		assertTrue( PipelineInvariants.check( before, after, 4, 5,
				PipelineInvariants.limits().outOfWindowMargin( 1 ) ).isEmpty(),
				"t=3 is inside t=4..5 with one timepoint of margin" );
	}

	// -----------------------------------------------------------------------
	// 3. Detection explosion

	@Test
	public void exceedingTheExpectedCeilingIsAnError()
	{
		final Snapshot before = flat( 10 );
		final Snapshot after = build( new int[] { 10, 10, 10, 10, 400, 10 }, new int[ 6 ],
				new int[] { 10, 10, 10, 10, 400, 10 } );
		final List< Violation > vs = PipelineInvariants.check( before, after, 0, 5,
				PipelineInvariants.limits().expectedCeiling( 20 ) );
		assertTrue( hasError( vs ), messages( vs ) );
		assertTrue( messages( vs ).contains( "t=4" ), messages( vs ) );
	}

	@Test
	public void aTimepointAlreadyOverTheCeilingIsNotReReported()
	{
		// Observed in a real run: one exploded timepoint was reported four times — once by the phase that
		// caused it and again after [5/6], [5b/6] and [5c/6], including after a refinement phase that had
		// logged "Skipping refinement" and never ran. Only the phase that pushes a timepoint over reports.
		final Snapshot exploded = build( new int[] { 10, 10, 10, 10, 400, 10 }, new int[ 6 ],
				new int[] { 10, 10, 10, 10, 400, 10 } );
		final Snapshot stillExploded = build( new int[] { 10, 10, 10, 10, 380, 10 }, new int[ 6 ],
				new int[] { 10, 10, 10, 10, 380, 10 } );
		final Limits l = PipelineInvariants.limits().expectedCeiling( 20 );
		assertTrue( hasError( PipelineInvariants.check( flat( 10 ), exploded, 0, 5, l ) ),
				"the phase that causes the explosion reports it" );
		assertTrue( PipelineInvariants.check( exploded, stillExploded, 0, 5, l ).isEmpty(),
				"a later phase that inherits the same exploded timepoint stays silent" );
		assertTrue( PipelineInvariants.check( exploded, exploded, 0, 5, l ).isEmpty(),
				"a phase that changed nothing at all stays silent" );
	}

	@Test
	public void aPreExistingExplosionOutsideTheWindowIsNotAttributedToThisPhase()
	{
		// [5c/6] declares a range wider than the window, which made it re-report an over-ceiling
		// timepoint finalized by an earlier window and never touched here.
		final Snapshot s = build( new int[] { 10, 10, 400, 10, 10, 10 }, new int[ 6 ],
				new int[] { 10, 10, 400, 10, 10, 10 } );
		assertTrue( PipelineInvariants.check( s, s, 0, 5,
				PipelineInvariants.limits().expectedCeiling( 20 ) ).isEmpty() );
	}

	// 3b. Collapse below the stated cell count

	@Test
	public void fallingBelowTheStatedCellCountIsAnError()
	{
		// The run this came from: detection was held at the stated 250 cells/frame, then a later pass
		// judging short tracks to be noise took individual frames to 193 and reported nothing at all.
		final Snapshot before = flat( 250 );
		final Snapshot after = build( new int[] { 250, 250, 193, 250, 250, 250 }, new int[ 6 ],
				new int[] { 250, 250, 193, 250, 250, 250 } );
		final List< Violation > vs = PipelineInvariants.check( before, after, 0, 5,
				PipelineInvariants.limits().statedCellsPerFrame( 250 ) );
		assertTrue( hasError( vs ), messages( vs ) );
		assertTrue( messages( vs ).contains( "t=2" ), messages( vs ) );
		assertTrue( messages( vs ).contains( "193" ), messages( vs ) );
	}

	@Test
	public void aTimepointAlreadyBelowTheStatedCountIsNotReReported()
	{
		// Symmetric with the ceiling: the phase that pushes a timepoint through the floor owns it, so a
		// later phase inheriting the same collapsed timepoint does not report it again.
		final Snapshot collapsed = build( new int[] { 250, 250, 193, 250, 250, 250 }, new int[ 6 ],
				new int[] { 250, 250, 193, 250, 250, 250 } );
		final Snapshot stillCollapsed = build( new int[] { 250, 250, 190, 250, 250, 250 }, new int[ 6 ],
				new int[] { 250, 250, 190, 250, 250, 250 } );
		final Limits l = PipelineInvariants.limits().statedCellsPerFrame( 250 );
		assertTrue( hasError( PipelineInvariants.check( flat( 250 ), collapsed, 0, 5, l ) ) );
		assertTrue( PipelineInvariants.check( collapsed, stillCollapsed, 0, 5, l ).isEmpty() );
	}

	@Test
	public void aPhaseThatStaysAtTheStatedCountIsSilent()
	{
		// Exactly at the floor is what a pass that honours it looks like — not a violation.
		final Snapshot before = flat( 500 );
		final Snapshot after = flat( 250 );
		assertTrue( PipelineInvariants.check( before, after, 0, 5,
				PipelineInvariants.limits().statedCellsPerFrame( 250 ) ).isEmpty() );
	}

	@Test
	public void floorCheckIsOffByDefault()
	{
		assertTrue( PipelineInvariants.check( flat( 250 ), flat( 10 ), 0, 5,
				PipelineInvariants.limits() ).isEmpty() );
	}

	@Test
	public void ceilingCheckIsOffByDefault()
	{
		final Snapshot before = flat( 10 );
		final Snapshot after = build( new int[] { 10, 10, 10, 10, 400, 10 }, new int[ 6 ],
				new int[] { 10, 10, 10, 10, 400, 10 } );
		assertTrue( PipelineInvariants.check( before, after, 0, 5, PipelineInvariants.limits() ).isEmpty() );
	}

	// -----------------------------------------------------------------------
	// 4. Churn

	@Test
	public void emptyingATimepointIsAnError()
	{
		final Snapshot before = flat( 10 );
		final Snapshot after = build( new int[] { 10, 10, 0, 10, 10, 10 }, new int[ 6 ],
				new int[] { 10, 10, 0, 10, 10, 10 } );
		final List< Violation > vs = PipelineInvariants.check( before, after, 0, 5,
				PipelineInvariants.limits() );
		assertTrue( hasError( vs ), messages( vs ) );
	}

	@Test
	public void inventingTooManySpotsIsAWarning()
	{
		final Snapshot before = flat( 10 );
		final Snapshot after = build( new int[] { 10, 10, 15, 10, 10, 10 }, new int[ 6 ],
				new int[] { 10, 10, 15, 10, 10, 10 } );
		final List< Violation > vs = PipelineInvariants.check( before, after, 0, 5,
				PipelineInvariants.limits().maxAddedFraction( 0.25 ) );
		assertEquals( 1, vs.size(), messages( vs ) );
		assertEquals( Severity.WARN, vs.get( 0 ).severity() );
		assertFalse( hasError( vs ) );
	}

	@Test
	public void aLinkPhaseMayNotChangeSpotCountsAtAll()
	{
		final Limits linkLimits = PipelineInvariants.limits()
				.maxAddedFraction( 0.0 ).maxDeletedFraction( 0.0 );
		final Snapshot before = flat( 10 );
		assertTrue( PipelineInvariants.check( before, flat( 10 ), 0, 5, linkLimits ).isEmpty(),
				"pure linking changes no counts" );
		final Snapshot after = build( new int[] { 10, 11, 10, 10, 10, 10 }, new int[ 6 ],
				new int[] { 10, 11, 10, 10, 10, 10 } );
		assertFalse( PipelineInvariants.check( before, after, 0, 5, linkLimits ).isEmpty(),
				"a single added spot during linking is worth reporting" );
	}

	// -----------------------------------------------------------------------
	// 5. Continuity

	@Test
	public void continuityRegressionIsAWarning()
	{
		final int[] spots = { 10, 10, 10, 10, 10, 10 };
		final Snapshot before = build( spots, new int[ 6 ], new int[] { 10, 10, 10, 10, 10, 10 } );
		final Snapshot after = build( spots, new int[ 6 ], new int[] { 10, 5, 5, 5, 5, 10 } );
		final List< Violation > vs = PipelineInvariants.check( before, after, 0, 5,
				PipelineInvariants.limits().continuityDropTolerance( 0.10 ) );
		assertEquals( 1, vs.size(), messages( vs ) );
		assertEquals( Severity.WARN, vs.get( 0 ).severity() );
	}

	@Test
	public void continuityImprovementIsNotAViolation()
	{
		final int[] spots = { 10, 10, 10, 10, 10, 10 };
		final Snapshot before = build( spots, new int[ 6 ], new int[] { 10, 5, 5, 5, 5, 10 } );
		final Snapshot after = build( spots, new int[ 6 ], new int[] { 10, 10, 10, 10, 10, 10 } );
		assertTrue( PipelineInvariants.check( before, after, 0, 5,
				PipelineInvariants.limits().continuityDropTolerance( 0.10 ) ).isEmpty() );
	}

	@Test
	public void aWindowWithNoInteriorHasUndefinedContinuityRatherThanZero()
	{
		// A 2-timepoint window has no interior spot, so continuity is undefined. Treating that as 0
		// would report a phantom regression on every short window — including the movie's last one.
		final Snapshot s = flat( 10 );
		assertTrue( Double.isNaN( s.continuity( 4, 5 ) ) );
		assertTrue( PipelineInvariants.check( s, flat( 10 ), 4, 5,
				PipelineInvariants.limits().continuityDropTolerance( 0.10 ) ).isEmpty() );
	}

	@Test
	public void continuityMatchesTheInteriorOnlyDefinition()
	{
		// Interior of 0..5 is t=1..4: 40 spots, 20 of them continuous.
		final Snapshot s = build( new int[] { 10, 10, 10, 10, 10, 10 }, new int[ 6 ],
				new int[] { 10, 10, 10, 0, 0, 10 } );
		assertEquals( 0.5, s.continuity( 0, 5 ), 1e-9 );
	}

	// -----------------------------------------------------------------------
	// 6. Snapshot accessors

	@Test
	public void snapshotCountsAreRangeSafe()
	{
		final Snapshot s = flat( 10 );
		assertEquals( 60, s.totalSpots() );
		assertEquals( 20, s.spotsIn( 4, 99 ), "an over-long range clamps instead of throwing" );
		assertEquals( 0, s.spotsAt( -1 ) );
		assertEquals( 0, s.spotsAt( 999 ) );
	}
}
