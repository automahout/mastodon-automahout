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
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * The two thresholds the window-level topology check is steered by, when no profile supplies them.
 *
 * <p>A run on Fluo-N2DL-HeLa reported {@code min_branch_len=off, max_disp=0.0} on every window, and
 * both are exactly the checks a person performs by eye in the lineage: the short side branch hanging
 * off a main track, and the tracklet left unconnected across a large step. Both were sourced ONLY
 * from a learned lineage-constraint profile, which that run refused on purpose — it had been learned
 * from another dataset. No profile meant no check.</p>
 */
public class TopologyThresholdFallbackTest
{
	private static final int N = 12;

	/** A short tracked graph, enough for the pass to have something to walk. */
	private static ReplayGraph tracked()
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, N, 0.0, 1.0 );
		g.track( 0, N, 200.0, 1.0 );
		return g;
	}

	@Test
	public void theBranchCheckHasAFloorWhenNoProfileSuppliesOne()
	{
		final String report = AutoCorrectOrchestrator.runTopologyConsistency(
				tracked().context(), 0, N, new AutomahoutSettings() );
		assertTrue( "the short-branch check must not simply be off: " + report,
				report.contains( "min_branch_len=3" ) );
	}

	@Test
	public void theMeasuredDisplacementCapIsUsedWhenNoProfileSuppliesOne()
	{
		// The settings object handed to this pass has carried the measured cap all along — three other
		// consumers read it from there, and this one read the profile or nothing.
		final AutomahoutSettings settings = new AutomahoutSettings();
		settings.setCorrectMaxDisplacement( 12.5 );
		final String report =
				AutoCorrectOrchestrator.runTopologyConsistency( tracked().context(), 0, N, settings );
		assertTrue( "the measured cap must reach the topology pass: " + report,
				report.contains( "max_disp=12.5" ) );
	}

	@Test
	public void theCapCannotBeMeasuredOnARangeThatHasNoSpotsYet()
	{
		// The shape of the bug: adaptive_track detects INTO its range, so measuring the cap over that
		// range before the run walks consecutive links that do not exist yet. It returned 0 for a whole
		// run, and every consumer then ran with no absolute bound.
		final ReplayGraph g = tracked();
		final ToolContext ctx = g.context();
		final AutomahoutSettings settings = new AutomahoutSettings();
		assertEquals( "an empty range measures nothing", 0.0,
				AutoCorrectOrchestrator.estimateMaxDisplacement( ctx, settings, N + 5, N + 20 ), 1e-9 );
		assertTrue( "the range that HAS tracks does measure something",
				AutoCorrectOrchestrator.estimateMaxDisplacement( ctx, settings, 0, N ) > 0.0 );
	}

	@Test
	public void theMeasuredCapIsAMultipleOfATypicalStep()
	{
		// 1 µm per frame in the fixture, so ~4 µm: a bound on a wrong link, not on a normal one.
		final ReplayGraph g = tracked();
		final double cap = AutoCorrectOrchestrator.estimateMaxDisplacement(
				g.context(), new AutomahoutSettings(), 0, N );
		assertEquals( 4.0, cap, 1e-6 );
	}

	@Test
	public void aTrackedFixtureReallyHasTheLinksTheseTestsMeasure()
	{
		// So none of the above can pass by the graph being empty.
		final ReplayGraph g = tracked();
		assertEquals( 2 * N, g.spotCount() );
		assertEquals( 2 * ( N - 1 ), g.linkCount() );
	}
}
