package org.mastodon.mamut.automahout.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The cell count the user types in the pre-flight is an estimate of a movie they eyeballed, and they
 * have said it is expected to run somewhat high or somewhat low. Treated as exact it made the floor
 * arbitrate that estimate: Fluo-N2DL-HeLA_01 was stated at 100 cells/frame and a frame at 62 was
 * reported as "deleting real cells". These pin the margin and, above all, that it is the SAME margin
 * the ceiling already takes on the same number.
 */
public class StatedCountToleranceTest
{
	@Test
	public void theFloorGivesAsMuchAsTheCeilingTakes()
	{
		// One number serves both directions, so "about N" cannot come to mean two different things.
		// The pre-flight's ceiling goes through statedCeiling; the floor through toleratedFloor.
		assertEquals( 200.0, ExpectedCellCount.statedCeiling( 100 ), 1e-9 );
		assertEquals( 50, ExpectedCellCount.toleratedFloor( 100 ) );
		assertEquals( 100.0,
				ExpectedCellCount.statedCeiling( 100 ) * ExpectedCellCount.toleratedFloor( 100 ) / 100.0,
				1e-9 );
	}

	@Test
	public void theRunsOwnFrameIsNoLongerCalledOverDeletion()
	{
		// t=1 held 62 against a stated 100.
		assertTrue( 62 >= ExpectedCellCount.toleratedFloor( 100 ),
				"62 must sit inside what a stated 100 allows, got floor "
						+ ExpectedCellCount.toleratedFloor( 100 ) );
	}

	@Test
	public void aCollapseStillTripsIt()
	{
		// Tolerating an estimate is not the same as tolerating a frame being gutted, which is the
		// failure the floor was written for.
		assertTrue( 12 < ExpectedCellCount.toleratedFloor( 100 ) );
		assertEquals( 50, ExpectedCellCount.toleratedFloor( 100 ) );
	}

	@Test
	public void aFloorOfZeroWouldBeNoFloorAtAll()
	{
		assertEquals( 1, ExpectedCellCount.toleratedFloor( 1 ) );
		assertEquals( 1, ExpectedCellCount.toleratedFloor( 2 ) );
	}

	@Test
	public void noStatedCountIsStillNoFloor()
	{
		assertEquals( -1, ExpectedCellCount.toleratedFloor( -1 ) );
		assertEquals( 0, ExpectedCellCount.toleratedFloor( 0 ) );
	}
}
