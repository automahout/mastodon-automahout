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
package org.mastodon.mamut.automahout.autocorrect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.CorrectionEvidence;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.GraphEdits.Reason;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

/**
 * Track surgery's findings must survive as training signal.
 *
 * <p>A person adapting a detector by hand reads the lineage, finds the places where it does not hold
 * together, and turns each one into an annotation — the spurious detection becomes {@code fp}, the
 * missed cell becomes {@code fn}. The finding and the supervision are one act. This pipeline deleted
 * the spurious spot and moved on, and synthesized its negatives somewhere else entirely: offset from
 * a real cell and rejected on collision with any detection, so every negative it ever taught sat
 * where the model had found nothing.</p>
 */
public class CorrectionEvidenceTest
{
	@Test
	public void aSpotRemovedByTrackSurgeryIsRememberedAsAnErrorOfTheModel()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot spurious = g.spot( 3, 120.0, 45.0, 0.0, 6.4 );
		final ToolContext ctx = g.context();
		// The timepoint must not be emptied, or the removal is refused for a different reason.
		g.spot( 3, 900.0, 900.0, 0.0, 6.4 );

		assertTrue( GraphEdits.removeSpot( ctx, g.graph(), spurious, Reason.TOPOLOGY ) );

		final List< double[] > evidence = CorrectionEvidence.drain( ctx );
		assertEquals( 1, evidence.size() );
		final double[] e = evidence.get( 0 );
		assertEquals( 120.0, e[ 0 ], 1e-9 );
		assertEquals( 45.0, e[ 1 ], 1e-9 );
		assertEquals( 3.0, e[ 3 ], 1e-9 );
		assertTrue( "the radius it was removed at is what a negative there should cover", e[ 4 ] > 0 );
	}

	/**
	 * The cleanup chain's own refusals are evidence, and they are where the volume is.
	 *
	 * <p>This test used to assert the opposite, on the argument that {@code NOT_A_CELL} and
	 * {@code PROBABLY_NOT_A_CELL} are blanket per-spot tests "the passes a person does NOT perform".
	 * That argument was wrong about what a person does. Shown a human-corrected frame of this very
	 * movie, the magenta {@code fp} annotations sit on background and on debris -- on detections the
	 * model made and should not have -- which is exactly what those per-spot tests find. Marking the
	 * bad detections IS the human pass.</p>
	 *
	 * <p>The part of the old argument that survives is about precision, not about kind: a blanket test
	 * refuses roughly fifty detections a frame where a person marks about ten. That is what the
	 * per-window negative budget and the background confirmation are for, not a reason to discard the
	 * finding.</p>
	 *
	 * <p>Left here rather than deleted because it was a deliberate decision with a stated reason, and
	 * a reversal of one should be readable as a reversal.</p>
	 */
	@Test
	public void theCleanupChainsOwnRefusalsAreEvidenceOfAModelError()
	{
		final ReplayGraph g = new ReplayGraph();
		final ToolContext ctx = g.context();
		// Room above the cell-count floor: PROBABLY_NOT_A_CELL is floor-bounded, and a removal the floor
		// refuses records nothing (correctly -- see aRefusedRemovalIsNotEvidenceOfAnything). Without the
		// headroom this test would pass or fail on the floor rather than on the rule it is about.
		for ( int i = 0; i < 40; i++ )
			g.spot( 1, 500.0 + 20.0 * i, 900.0, 0.0, 6.4 );

		assertTrue( "the removal itself must go through, or this tests the floor instead",
				GraphEdits.removeSpot( ctx, g.graph(), g.spot( 1, 10.0, 10.0, 0.0, 6.4 ),
						Reason.PROBABLY_NOT_A_CELL ) );
		assertTrue( GraphEdits.removeSpot( ctx, g.graph(), g.spot( 1, 20.0, 20.0, 0.0, 6.4 ),
				Reason.NOT_A_CELL ) );

		assertEquals( 2, CorrectionEvidence.size( ctx ) );
	}

	/**
	 * A duplicate sits ON a real cell, so it is never evidence that there is no cell there.
	 *
	 * <p>The one membership in the enum where being wrong is worse than having no negatives at all:
	 * ELEPHANT weights a negative at ten times a positive, so a negative on a cell teaches the
	 * detector that a cell is not one. Bookkeeping removals say nothing about detection either.</p>
	 */
	@Test
	public void aDuplicateOrABookkeepingRemovalIsNotEvidence()
	{
		final ReplayGraph g = new ReplayGraph();
		final ToolContext ctx = g.context();
		g.spot( 1, 900.0, 900.0, 0.0, 6.4 );
		GraphEdits.removeSpot( ctx, g.graph(), g.spot( 1, 10.0, 10.0, 0.0, 6.4 ), Reason.DUPLICATE );
		GraphEdits.removeSpot( ctx, g.graph(), g.spot( 1, 20.0, 20.0, 0.0, 6.4 ), Reason.SPLIT );
		GraphEdits.removeSpot( ctx, g.graph(), g.spot( 1, 30.0, 30.0, 0.0, 6.4 ), Reason.BULK );

		assertEquals( 0, CorrectionEvidence.size( ctx ) );
	}

	@Test
	public void aRefusedRemovalIsNotEvidenceOfAnything()
	{
		// The spot is still there, so nothing concluded anything about it.
		final ReplayGraph g = new ReplayGraph();
		final Spot only = g.spot( 0, 5.0, 5.0, 0.0, 6.4 );
		final ToolContext ctx = g.protect( only );

		assertEquals( false, GraphEdits.removeSpot( ctx, g.graph(), only, Reason.TOPOLOGY ) );
		assertEquals( 0, CorrectionEvidence.size( ctx ) );
	}

	@Test
	public void evidenceIsOfferedOnceAndDoesNotCarryIntoTheNextWindow()
	{
		// A position that was spurious in one window may hold a cell by the next, and the pool is
		// keyed on nothing but a timepoint — so it is drained rather than read.
		final ReplayGraph g = new ReplayGraph();
		final ToolContext ctx = g.context();
		g.spot( 2, 900.0, 900.0, 0.0, 6.4 );
		GraphEdits.removeSpot( ctx, g.graph(), g.spot( 2, 77.0, 88.0, 0.0, 6.4 ), Reason.TOPOLOGY );

		assertEquals( 1, CorrectionEvidence.drain( ctx ).size() );
		assertEquals( 0, CorrectionEvidence.drain( ctx ).size() );
	}

	@Test
	public void thePoolIsBounded()
	{
		final ReplayGraph g = new ReplayGraph();
		final ToolContext ctx = g.context();
		final double[] pos = { 1.0, 2.0, 3.0 };
		for ( int i = 0; i < CorrectionEvidence.MAX_RECORDS + 100; i++ )
			CorrectionEvidence.spurious( ctx, 0, pos, 6.4 );
		assertEquals( CorrectionEvidence.MAX_RECORDS, CorrectionEvidence.size( ctx ) );
	}
}
