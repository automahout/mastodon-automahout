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

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

/**
 * A lineage that walks out of the field of view is real, and useless.
 *
 * <p>The user's report: the fragments left in the lineage view are mostly at the edges of the image,
 * and such incomplete tracks should simply be deleted. An interior fragment is a linking failure and
 * the pipeline tries to repair it; a fragment at the image edge is a cell entering or leaving frame,
 * which no amount of repair will complete. So a component that reaches an image face is held to a
 * longer minimum span than one that does not.</p>
 *
 * <p>What that costs when the tracking is RIGHT is measured on ground truth — see
 * {@code AutoCorrectOrchestrator.BORDER_FRAGMENT_MIN_TP}, which carries the table. These tests are
 * about the rule being applied to the right components, on a real graph.</p>
 */
public class BorderFragmentTest
{
	/** A 100 x 100 µm image, one z plane — the shape of the dataset this was reported from. */
	private static final double[] FLAT = { 100.0, 100.0, 0.645 };

	private static final int STABLE_MIN = 3;

	private static final int BORDER_MIN = 10;

	/** A straight track at a fixed (x, y), one spot per timepoint, radius 5. */
	private static java.util.List< Spot > trackAt( final ReplayGraph g, final int t0, final int length,
			final double x, final double y )
	{
		final java.util.List< Spot > out = new java.util.ArrayList<>();
		Spot prev = null;
		for ( int i = 0; i < length; i++ )
		{
			final Spot s = g.spot( t0 + i, x, y, 0.0, 5.0 );
			if ( prev != null )
				g.link( prev, s );
			out.add( s );
			prev = s;
		}
		return out;
	}

	@Test
	public void aShortLineageThatReachesAnImageEdgeIsDeletedAndAnInteriorOneIsNot()
	{
		final ReplayGraph g = new ReplayGraph();
		trackAt( g, 5, 5, 3.0, 50.0 );    // radius 5 at x=3: its ellipsoid crosses the x=0 face
		trackAt( g, 5, 5, 50.0, 50.0 );   // the same five-timepoint fragment, in the middle
		final ToolContext ctx = g.context();

		final int removed = AutoCorrectOrchestrator.deleteUnstableSpots( ctx, 0, 40, STABLE_MIN,
				BORDER_MIN, FLAT, false );

		assertEquals( "only the border fragment goes", 5, removed );
		assertEquals( 5, g.spotCount() );
		for ( final Spot s : g.graph().vertices() )
			assertTrue( "the surviving track is the interior one", s.getDoublePosition( 0 ) > 40.0 );
	}

	@Test
	public void aLineageAtTheEdgeThatEstablishesItselfIsKept()
	{
		// A cell that entered the frame and stayed is not a fragment. The rule is about span, not
		// about being near the edge.
		final ReplayGraph g = new ReplayGraph();
		trackAt( g, 5, 12, 3.0, 50.0 );
		final ToolContext ctx = g.context();

		assertEquals( 0, AutoCorrectOrchestrator.deleteUnstableSpots( ctx, 0, 40, STABLE_MIN,
				BORDER_MIN, FLAT, false ) );
		assertEquals( 12, g.spotCount() );
	}

	@Test
	public void theFrontierProtectsABorderLineageThatMayStillGrow()
	{
		// The longer span brings a longer frontier with it: a border fragment inside the last
		// BORDER_MIN timepoints of the scanned range has not finished happening yet, and the next
		// window's pass will judge it once it has.
		final ReplayGraph g = new ReplayGraph();
		trackAt( g, 36, 5, 3.0, 50.0 );   // t=36..40, ending exactly at the frontier
		final ToolContext ctx = g.context();

		assertEquals( 0, AutoCorrectOrchestrator.deleteUnstableSpots( ctx, 0, 40, STABLE_MIN,
				BORDER_MIN, FLAT, false ) );
		// ...and it is deleted once the range it is judged in has moved on past it.
		assertEquals( 5, AutoCorrectOrchestrator.deleteUnstableSpots( ctx, 0, 60, STABLE_MIN,
				BORDER_MIN, FLAT, false ) );
	}

	@Test
	public void onOnePlaneTheZFaceIsNotAnEdge()
	{
		// Every spot in a 2D movie sits at z=0, one voxel from both z faces. Judged as volumetric,
		// EVERY lineage is a border lineage and the stricter span applies to the whole movie.
		final ReplayGraph flat = new ReplayGraph();
		trackAt( flat, 5, 5, 50.0, 50.0 );
		assertEquals( 0, AutoCorrectOrchestrator.deleteUnstableSpots( flat.context(), 0, 40, STABLE_MIN,
				BORDER_MIN, FLAT, false ) );

		final ReplayGraph asVolume = new ReplayGraph();
		trackAt( asVolume, 5, 5, 50.0, 50.0 );
		assertEquals( "this is the bug, kept as the measurement it is", 5,
				AutoCorrectOrchestrator.deleteUnstableSpots( asVolume.context(), 0, 40, STABLE_MIN,
						BORDER_MIN, FLAT, true ) );
	}

	@Test
	public void withNoImageToReadNothingChangesAboutTheOldBehaviour()
	{
		// A project whose sequence description cannot be read still gets the plain stability rule,
		// rather than every lineage being treated as a border one or none of them being judged.
		final ReplayGraph g = new ReplayGraph();
		trackAt( g, 5, 5, 3.0, 50.0 );    // at the edge, but there is no edge to know about
		trackAt( g, 5, 2, 50.0, 50.0 );   // a two-timepoint flicker: still unstable, still goes
		final ToolContext ctx = g.context();

		assertEquals( 2, AutoCorrectOrchestrator.deleteUnstableSpots( ctx, 0, 40, STABLE_MIN,
				BORDER_MIN, null, false ) );
		assertEquals( 5, g.spotCount() );
	}

	@Test
	public void aConfirmedSpotAtTheEdgeIsNeverDeletedForIt()
	{
		final ReplayGraph g = new ReplayGraph();
		final java.util.List< Spot > t = trackAt( g, 5, 5, 3.0, 50.0 );
		final ToolContext ctx = g.protect( t.toArray( new Spot[ 0 ] ) );

		assertEquals( 0, AutoCorrectOrchestrator.deleteUnstableSpots( ctx, 0, 40, STABLE_MIN,
				BORDER_MIN, FLAT, false ) );
	}
}
