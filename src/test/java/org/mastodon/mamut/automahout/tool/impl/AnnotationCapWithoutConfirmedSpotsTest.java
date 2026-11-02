package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.automahout.tool.ExpectedCellCount;
import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * The cap on how many detections a window may TEACH reads the cell count the user stated. It used to
 * return 0 whenever nothing had been confirmed in the project, because it wanted to rank detections
 * by closeness to the user's own cells — but the ranking needs a reference and the cap does not, and
 * tying the two together meant no cap at all in exactly the case that needs one most.
 *
 * <p>Observed on Fluo-N2DL-HeLA_01, stated at ~26 cells/frame with nothing confirmed: every timepoint
 * of window 1 taught the model all ~700 of its raw detections as cells. That is the detector being
 * told this frame's background is what a cell looks like.</p>
 */
public class AnnotationCapWithoutConfirmedSpotsTest
{
	private static ToolContext withStatedCount( final ReplayGraph g, final int cells )
	{
		final ToolContext ctx = g.context();
		ctx.setSharedData( ElephantTool.SHARED_EXPECTED_CELLS_PER_FRAME, cells );
		return ctx;
	}

	@Test
	public void aStatedCountCapsTeachingEvenWithNothingConfirmed()
	{
		final ReplayGraph g = new ReplayGraph();
		for ( int i = 0; i < 60; i++ )
			g.spot( 0, i * 10 );
		final ToolContext ctx = withStatedCount( g, 10 );

		final int withheld = ElephantTool.capAnnotationsToStatedCount( ctx, new int[] { 0 } );
		assertTrue( withheld > 0,
				"nothing confirmed must not mean no cap: 60 detections against a stated 10" );
		assertEquals( 60 - ExpectedCellCount.annotationCapAt( ctx, 0 ), withheld );
	}

	@Test
	public void theWithheldSpotsStayInTheGraphForLinking()
	{
		// Withholding is about what is TAUGHT. Deleting them instead would be a different, worse change.
		final ReplayGraph g = new ReplayGraph();
		for ( int i = 0; i < 60; i++ )
			g.spot( 0, i * 10 );
		final ToolContext ctx = withStatedCount( g, 10 );
		ElephantTool.capAnnotationsToStatedCount( ctx, new int[] { 0 } );
		assertEquals( 60, g.spotCount() );
	}

	@Test
	public void noStatedCountStillMeansNoCap()
	{
		// The cap is the user's number. Without one there is nothing to cap against, and inventing a
		// bound here would be the pipeline deciding how many cells the movie holds.
		final ReplayGraph g = new ReplayGraph();
		for ( int i = 0; i < 60; i++ )
			g.spot( 0, i * 10 );
		assertEquals( 0, ElephantTool.capAnnotationsToStatedCount( g.context(), new int[] { 0 } ) );
	}
}
