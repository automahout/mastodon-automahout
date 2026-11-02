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
 * Counting what the division passes do, instead of arguing about it.
 *
 * <p>One whole-movie run created division links and then dissolved 276 of them by the refractory rule
 * and cut 192 more by de-branching, finishing with 54 divisions where population growth needs about
 * 156. Two readings fit those numbers exactly — the passes are removing fabrications, or they are
 * removing real mitoses — and nothing in the run said which. The user's own description of the
 * remaining error is the better measurement: "there are tracks that start partway through because a
 * division was not caught; a person would go and look, see the division, and join them to the
 * parent."</p>
 */
public class DivisionChurnReportTest
{
	private static String source() throws IOException
	{
		final String text = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				StandardCharsets.UTF_8 );
		return text.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
	}

	/**
	 * A mid-movie track start whose mother is right there, one timepoint back, with a free slot. This
	 * is the case the user says a person fixes in seconds, and the report has to see it.
	 */
	@Test
	public void aStartWithAMotherWithinReachIsCountedAsReachable() throws Exception
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 10, 0, 1 );                 // a mother running the whole range, one child at most
		g.track( 5, 5, 3, 1 );                  // starts at t=5, 2 units from the mother at t=4
		final ToolContext ctx = g.context();

		final String line = report( ctx, 0, 9, 7.0 );

		assertTrue( line, line.contains( "mid-movie starts=1" ) );
		assertTrue( "the mother is 2 µm away with room and passes every gate the joiner applies, so "
				+ "this is its bug rather than its policy: " + line,
				line.contains( "of which 1 would pass" ) );
	}

	@Test
	public void aStartWithNoMotherInReachIsNotCountedAsReachable() throws Exception
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 10, 0, 1 );
		g.track( 5, 5, 400, 1 );                // far away: nothing within the cap
		final ToolContext ctx = g.context();

		final String line = report( ctx, 0, 9, 7.0 );

		assertTrue( line, line.contains( "mid-movie starts=1" ) );
		assertTrue( "no mother exists to find, so no link factor would produce one: " + line,
				line.contains( "of which 0 would pass" ) );
	}

	@Test
	public void aTrackStartingAtTheRangeStartIsNotAMidMovieStart() throws Exception
	{
		// Everything begins somewhere. Only a start INSIDE the range is evidence of a missed join.
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 10, 0, 1 );
		g.track( 0, 10, 40, 1 );
		assertTrue( report( g.context(), 0, 9, 7.0 ).contains( "mid-movie starts=0" ) );
	}

	@Test
	public void aMotherAlreadyCarryingTwoChildrenIsNotAvailable() throws Exception
	{
		// Lineages divide in two. A spot already at the binary maximum cannot explain another start,
		// and counting it would report a join that is not allowed to be made.
		final ReplayGraph g = new ReplayGraph();
		final List< Spot > mother = g.track( 0, 5, 0, 1 );   // t=0..4, then it divides
		final List< Spot > d1 = g.track( 5, 5, 2, 1 );
		final List< Spot > d2 = g.track( 5, 5, 4, 1 );
		g.link( mother.get( 4 ), d1.get( 0 ) );
		g.link( mother.get( 4 ), d2.get( 0 ) );
		final List< Spot > third = g.track( 5, 5, 6, 1 );
		assertEquals( "fixture: the mother is at the binary maximum", 2,
				mother.get( 4 ).outgoingEdges().size() );

		final String line = report( g.context(), 0, 9, 7.0 );

		assertTrue( line, line.contains( "mid-movie starts=1" ) );
		assertTrue( "the only spot in reach is full, so this start is not explainable as a division: "
				+ line, line.contains( "of which 0 would pass" ) );
		assertEquals( 5, third.size() );
	}

	@Test
	public void withNoCapTheReportSaysSoRatherThanGuessing() throws Exception
	{
		// "Within reach" has no meaning without a distance. Printing a number anyway is how max_disp=0.0
		// went unnoticed for a whole run.
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 10, 0, 1 );
		g.track( 5, 5, 3, 1 );
		assertTrue( report( g.context(), 0, 9, 0.0 ).contains( "n/a" ) );
	}

	/** The refractory rule has to say how recent "too recent" actually was. */
	@Test
	public void theRefractoryRuleReportsHowFarBackTheAncestorWas() throws IOException
	{
		// The RECORDING, not just a variable that still exists: a declaration and a log line survive a
		// deleted add() perfectly happily, which is how a ratchet ends up proving nothing.
		assertTrue( "the refractory rule must record how many timepoints back the ancestor division "
				+ "was, or a run cannot tell fabrications from real mitoses it is cutting",
				source().contains( "ancestorSteps.add(" ) );
	}

	private static String report( final ToolContext ctx, final int startTp, final int endTp,
			final double cap ) throws Exception
	{
		final java.lang.reflect.Method mm = AutoCorrectOrchestrator.class.getDeclaredMethod(
				"reportMidMovieStarts", ToolContext.class, int.class, int.class, double.class,
				double.class );
		mm.setAccessible( true );
		return ( String ) mm.invoke( null, ctx, startTp, endTp, cap, 3.0 );
	}
}
