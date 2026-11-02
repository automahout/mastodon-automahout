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
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;

/** {@link AutoCorrectOrchestrator#removeSandwichedSpots}: overlap judged along the line between two spots. */
public class SandwichedSpotsTest
{
	/** Semi-axes {@code rx, ry, rz} as a spot covariance (squared semi-axes on the diagonal). */
	private static void add( final ToolContext ctx, final ModelGraph g, final double x, final double z, final double rxy,
			final double rz )
	{
		final double[][] cov = { { rxy * rxy, 0, 0 }, { 0, rxy * rxy, 0 }, { 0, 0, rz * rz } };
		GraphEdits.addSpot( ctx, g, g.vertexRef(), 0, new double[] { x, 0, z }, cov, GraphEdits.Reason.DETECTION );
	}

	private static int spots( final ModelGraph g )
	{
		return g.vertices().size();
	}

	@Test
	public void threeCellsSideBySideLongInZAreAllKept()
	{
		// Fluo-N3DH-CE: nuclei 1.8 um across in plane, detections 5 um long in z (coarse z). 4 um apart side
		// by side they do not overlap -- but their bounding spheres (5 um) did, and the middle one went.
		final Model model = new Model();
		final ToolContext ctx = ToolContext.forReplay( model );
		final ModelGraph g = model.getGraph();
		add( ctx, g, -4, 0, 1.8, 5 );
		add( ctx, g, 0, 0, 1.8, 5 );
		add( ctx, g, 4, 0, 1.8, 5 );
		assertEquals( 0, AutoCorrectOrchestrator.removeSandwichedSpots( ctx, 0, 1.0 ) );
		assertEquals( 3, spots( g ) );
	}

	@Test
	public void aPhantomBetweenTwoCellsStackedInZStillGoes()
	{
		final Model model = new Model();
		final ToolContext ctx = ToolContext.forReplay( model );
		final ModelGraph g = model.getGraph();
		add( ctx, g, 0, 0, 2, 4 );
		add( ctx, g, 0, 3, 2, 4 ); // wedged between the two along z
		add( ctx, g, 0, 6, 2, 4 );
		assertEquals( 1, AutoCorrectOrchestrator.removeSandwichedSpots( ctx, 0, 1.0 ) );
		assertEquals( 2, spots( g ) );
	}

	@Test
	public void theRadiusAlongADirectionIsTheEllipsoidsOwn()
	{
		final double[][] inv = { { 1 / 4.0, 0, 0 }, { 0, 1 / 4.0, 0 }, { 0, 0, 1 / 25.0 } };
		assertEquals( 2, AutoCorrectOrchestrator.radiusAlong( inv, new double[] { 1, 0, 0 } ), 1e-12 );
		assertEquals( 5, AutoCorrectOrchestrator.radiusAlong( inv, new double[] { 0, 0, 1 } ), 1e-12 );
	}
}
