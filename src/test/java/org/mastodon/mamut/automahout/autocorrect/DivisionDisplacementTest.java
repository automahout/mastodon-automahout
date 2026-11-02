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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

/**
 * A mitosis is not ordinary motion, and the cap that bounds ordinary motion must not bound it.
 *
 * <p>The run said it twice: 178 tracks begin in the middle of the movie — about the number of
 * divisions the population growth requires — and only 36 of them had a mother within the per-frame
 * displacement cap. Raising {@code division_link_factor} from 1.5 to 2.5 recovered one division.</p>
 *
 * <p>Measured on the CTC tracking ground truth (benchmark/division-displacement): the step from a
 * mother's last centroid to a daughter's first has a median of 10.6 / 9.8 µm against an ordinary
 * consecutive-step median of 1.5 / 0.7. The cap the run computed, 5.8 µm, refuses 87% and 98% of the
 * real mother-to-daughter joins.</p>
 */
public class DivisionDisplacementTest
{
	/** The run's own numbers: ordinary steps around 1.5 µm, so a cap of 5.8. */
	private static final double ORDINARY_CAP = 5.8;

	/** Spots in the fixture have radius 5, and division_link_factor is a multiple of that. */
	private static final double DIVISION_FACTOR = 2.5;

	private static boolean linked( final ReplayGraph g, final Spot from, final Spot to )
	{
		final Spot tgt = g.graph().vertexRef();
		try
		{
			for ( final org.mastodon.mamut.model.Link e : from.outgoingEdges() )
				if ( e.getTarget( tgt ).getInternalPoolIndex() == to.getInternalPoolIndex() )
					return true;
			return false;
		}
		finally
		{
			g.graph().releaseRef( tgt );
		}
	}

	/**
	 * A mother that already carries one child, and a daughter 9 µm away — a nuclear diameter, the
	 * ground-truth median. Beyond the ordinary cap, inside the radius-scaled bound.
	 */
	@Test
	public void aDaughterOneNuclearDiameterAwayIsJoinedToItsMother()
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > mother = g.track( 0, 3, 0, 0 );   // stationary, ends at t=2: it divides
		final List< Spot > first = g.track( 3, 5, 1, 0 );    // one daughter already, from t=3
		g.link( mother.get( 2 ), first.get( 0 ) );
		final Spot second = g.spot( 3, 9 );                  // the other daughter, 9 µm out
		assertEquals( "fixture: the mother has exactly one child, so it has room for a second", 1,
				mother.get( 2 ).outgoingEdges().size() );

		final int linked = AutoCorrectOrchestrator.reconnectOrphanTracklets( g.context(), 0, 8,
				DIVISION_FACTOR, ORDINARY_CAP );

		assertEquals( 1, linked );
		assertTrue( "the ordinary-motion cap was refusing 87% of real divisions",
				linked( g, mother.get( 2 ), second ) );
	}

	/**
	 * And the cap still does its job where it belongs. A 1:1 continuation is ordinary motion, so a
	 * predecessor with no child at all stays bounded by it — that is the bound that stops a track
	 * jumping to an unrelated cell across the frame.
	 */
	@Test
	public void aOneToOneContinuationIsStillBoundedByOrdinaryMotion()
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > before = g.track( 0, 3, 0, 0 );   // ends at t=2, no children
		final Spot faraway = g.spot( 3, 9 );                 // 9 µm: same distance as above

		assertEquals( "a cell that is not dividing does not move a nuclear diameter in one frame", 0,
				AutoCorrectOrchestrator.reconnectOrphanTracklets( g.context(), 0, 8, DIVISION_FACTOR,
						ORDINARY_CAP ) );
		assertTrue( !linked( g, before.get( 2 ), faraway ) );
	}

	/** The radius-scaled bound still binds the division case; it is not unbounded. */
	@Test
	public void aDaughterFarBeyondAnyNucleusIsStillRefused()
	{
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > mother = g.track( 0, 3, 0, 0 );
		final List< Spot > first = g.track( 3, 5, 1, 0 );
		g.link( mother.get( 2 ), first.get( 0 ) );
		g.spot( 3, 60 );                                     // 60 µm — six nuclear diameters

		assertEquals( 0, AutoCorrectOrchestrator.reconnectOrphanTracklets( g.context(), 0, 8,
				DIVISION_FACTOR, ORDINARY_CAP ) );
	}

	/**
	 * Ratchet: the exemption is one boolean and it is easy to lose in a refactor, so it is also
	 * asserted in the source. Comments stripped — a comment naming the rule is not the rule.
	 */
	@Test
	public void theExemptionIsConditionalOnTheLinkMakingADivision() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 )
						.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		assertTrue( "the per-frame cap must be skipped exactly when the link would make a division",
				src.contains( "!wouldDivide && maxDisplacement > 0.0" ) );
	}
}
