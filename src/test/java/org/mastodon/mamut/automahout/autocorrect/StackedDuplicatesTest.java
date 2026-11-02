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

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/** {@link AutoCorrectOrchestrator#removeStackedDuplicates}: two detections on one nucleus, one inside the other. */
public class StackedDuplicatesTest
{
	private static Spot add( final ToolContext ctx, final ModelGraph g, final int t, final double x, final double z,
			final double rxy, final double rz )
	{
		final double[][] cov = { { rxy * rxy, 0, 0 }, { 0, rxy * rxy, 0 }, { 0, 0, rz * rz } };
		return GraphEdits.addSpot( ctx, g, g.vertexRef(), t, new double[] { x, 0, z }, cov, GraphEdits.Reason.DETECTION );
	}

	@Test
	public void theSmallerOfTwoStackedDetectionsGoesAndItsTrackRunsOnThroughTheOther()
	{
		final Model model = new Model();
		final ToolContext ctx = ToolContext.forReplay( model );
		final ModelGraph g = model.getGraph();
		final Spot before = add( ctx, g, 0, 0, 0, 2, 4 );
		final Spot big = add( ctx, g, 1, 0, 0, 2, 4 );
		final Spot small = add( ctx, g, 1, 0, 2, 1.5, 3 ); // its centre is inside the big one
		final Spot after = add( ctx, g, 2, 0, 2, 2, 4 );
		final Link e = g.edgeRef();
		GraphEdits.addLink( ctx, g, before, small, e, GraphEdits.Reason.TOPOLOGY );
		GraphEdits.addLink( ctx, g, small, after, e, GraphEdits.Reason.TOPOLOGY );
		assertEquals( 1, AutoCorrectOrchestrator.removeStackedDuplicates( ctx, 1 ) );
		assertEquals( 3, g.vertices().size() );
		assertEquals( "the kept one took the predecessor", 1, big.incomingEdges().size() );
		assertEquals( "and the successor", 1, big.outgoingEdges().size() );
	}

	@Test
	public void twoNeighboursTouchingButNotHoldingEachOtherAreBothKept()
	{
		final Model model = new Model();
		final ToolContext ctx = ToolContext.forReplay( model );
		final ModelGraph g = model.getGraph();
		add( ctx, g, 0, 0, 0, 2, 4 );
		add( ctx, g, 0, 3.5, 0, 2, 4 ); // overlapping a little, centres outside each other
		add( ctx, g, 0, 0, 5, 2, 4 ); // stacked in z, 5 apart: outside a 4 semi-axis
		assertEquals( 0, AutoCorrectOrchestrator.removeStackedDuplicates( ctx, 0 ) );
		assertEquals( 3, g.vertices().size() );
	}

	@Test
	public void twoNucleiStackedFartherApartThanTheFramesSpacingAllowsAreBothKept()
	{
		final Model model = new Model();
		final ToolContext ctx = ToolContext.forReplay( model );
		final ModelGraph g = model.getGraph();
		// A lattice of nuclei 4 apart sets the frame's spacing; two of them 2.3 apart in z, each z extent 3.
		for ( int k = 0; k < 10; k++ )
			add( ctx, g, 0, 20 + 4 * k, 0, 1.5, 3 );
		add( ctx, g, 0, 0, 0, 1.5, 3 );
		add( ctx, g, 0, 0, 2.3, 1.5, 3 ); // its centre is inside the first, but it is a second nucleus
		assertEquals( 0, AutoCorrectOrchestrator.removeStackedDuplicates( ctx, 0 ) );
	}

	@Test
	public void theMedianNeighbourDistanceIsTheFramesOwnSpacing()
	{
		final java.util.List< double[] > pos = new java.util.ArrayList<>();
		for ( int k = 0; k < 5; k++ )
			pos.add( new double[] { 4 * k, 0, 0 } );
		pos.add( new double[] { 0, 0, 1 } );
		assertEquals( 4.0, AutoCorrectOrchestrator.medianNeighbourDistance( pos ), 1e-9 );
	}
}
