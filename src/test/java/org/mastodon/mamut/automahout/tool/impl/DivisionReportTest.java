package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * adaptive_track measured everything about Fluo-N2DL-HeLA_01 except the one thing that explained it.
 * Continuity is division-neutral by design, so three consecutive runs reported continuous tracks on a
 * culture that grew 44 to 150 cells/frame without ever saying whether a single mitosis had been
 * recorded — and the growth was read as a false-positive flood instead. These hold the line that
 * closes that blind spot.
 */
public class DivisionReportTest
{
	/** The run's own numbers: first and last timepoint of Fluo-N2DL-HeLA_01. */
	private static final int FIRST = 44;

	private static final int LAST = 150;

	@Test
	public void growthIsTheYardstickAndItIsArithmeticNotAModel()
	{
		// Each division adds exactly one cell, so 44 -> 150 needs at least 106 of them. Nothing here is
		// fitted or assumed about HeLa; deaths only push the true number up, so this stays a floor.
		final String r = ElephantTool.divisionReport( 104, 37, 0, FIRST, LAST );
		assertTrue( r.contains( "44 -> 150" ), r );
		assertTrue( r.contains( "106 division events" ), r );
	}

	@Test
	public void divisionsThatTrackTheGrowthNameTheGrowthAsProliferation()
	{
		// The sentence the run needed and never got.
		final String r = ElephantTool.divisionReport( 104, 37, 0, FIRST, LAST );
		assertTrue( r.contains( "PROLIFERATION" ), r );
		assertFalse( r.contains( "DIVISIONS ARE BEING MISSED" ), r );
	}

	@Test
	public void aShortfallSaysWhichKnobIsTheWrongOne()
	{
		// The actual failure mode: the daughters ARE detected -- that is what made the count grow -- so
		// the report has to send the reader to linking, not to the detection threshold they reached for.
		final String r = ElephantTool.divisionReport( 3, Integer.MAX_VALUE, 0, FIRST, LAST );
		assertTrue( r.contains( "DIVISIONS ARE BEING MISSED" ), r );
		assertTrue( r.contains( "min_intensity_ratio will not fix it" ), r );
		assertTrue( r.contains( "division_link_factor" ), r );
		assertTrue( r.contains( "NN max edges" ), r );
	}

	@Test
	public void branchingFarAboveTheGrowthIsCalledALinkingError()
	{
		final String r = ElephantTool.divisionReport( 400, 2, 0, FIRST, LAST );
		assertTrue( r.contains( "linking errors" ), r );
		assertFalse( r.contains( "PROLIFERATION" ), r );
	}

	@Test
	public void withoutFullCoverageNoConclusionIsDrawnFromTheEndpoints()
	{
		// An abandoned window leaves the last timepoint holding whatever preceded the run, which is not
		// a population size. Reporting the count is honest; comparing against it is not.
		final String r = ElephantTool.divisionReport( 12, 20, 0, 0, 0 );
		assertTrue( r.contains( "divisions=12" ), r );
		assertFalse( r.contains( "division events" ), r );
		assertFalse( r.contains( "PROLIFERATION" ), r );
	}

	@Test
	public void zeroDivisionsIsStatedEvenWhenGrowthCannotBeMeasured()
	{
		final String r = ElephantTool.divisionReport( 0, Integer.MAX_VALUE, 0, 0, 0 );
		assertTrue( r.contains( "No division was recorded" ), r );
	}

	@Test
	public void trifurcationsAreAlwaysFlagged()
	{
		final String r = ElephantTool.divisionReport( 104, 37, 5, FIRST, LAST );
		assertTrue( r.contains( ">= 3 daughters" ), r );
	}

	@Test
	public void aLinkerCappedAtOneSuccessorIsNamedBeforeAnyDivisionFigureIsBelieved()
	{
		// At NNMaxEdges=1 every division count is 0 by construction. Nothing downstream would say so:
		// the figure looks like a measurement, and the reader tunes detection forever.
		final String w = ElephantTool.nnMaxEdgesWarning( 1 );
		assertTrue( w.contains( "DIVISION CANNOT BE REPRESENTED" ), w );
		assertTrue( w.contains( "not by measurement" ), w );
	}

	@Test
	public void theElephantDefaultIsSilent()
	{
		// ElephantMainSettings.DEFAULT_NN_MAX_EDGES is 2; only a lowered setting is worth a line.
		assertEquals( "", ElephantTool.nnMaxEdgesWarning( 2 ) );
		assertEquals( "", ElephantTool.nnMaxEdgesWarning( 5 ) );
	}
}
