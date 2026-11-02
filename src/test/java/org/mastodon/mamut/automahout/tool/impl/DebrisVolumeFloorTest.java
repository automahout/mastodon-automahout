package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The volume floor that separates debris from cells is taken from the volumes of the spots the USER
 * confirmed, so these check the two things that decide whether it is safe: that it measures volume
 * rather than a linear size, and that it declines to cut when it has too little to measure.
 */
public class DebrisVolumeFloorTest
{
	private static double[][] diag( final double a, final double b, final double c )
	{
		return new double[][] { { a, 0, 0 }, { 0, b, 0 }, { 0, 0, c } };
	}

	@Test
	public void volumeScalesWithTheCubeOfLinearSizeNotWithIt()
	{
		// Why the user asked for volume: the flagged detections run 0.77x the LINEAR size of their
		// neighbours, which reads as a near miss, and 0.77^3 = 0.46 of the volume, which does not.
		final double unit = ElephantTool.spotVolume( diag( 1, 1, 1 ) );
		final double doubled = ElephantTool.spotVolume( diag( 4, 4, 4 ) ); // each semi-axis x2
		assertEquals( 8.0, doubled / unit, 1e-9 );
		assertEquals( Math.pow( 0.77, 3 ),
				ElephantTool.spotVolume( diag( 0.77 * 0.77, 0.77 * 0.77, 0.77 * 0.77 ) ) / unit, 1e-9 );
	}

	@Test
	public void aFlatOrDegenerateEllipsoidHasNoNegativeVolume()
	{
		assertEquals( 0.0, ElephantTool.spotVolume( diag( 1, 1, 0 ) ), 1e-12 );
		assertEquals( 0.0, ElephantTool.spotVolume( diag( 1, -1, 1 ) ), 1e-12 );
	}

	@Test
	public void tooFewConfirmedCellsLeavesTheFilterOff()
	{
		// Cutting against a percentile of a dozen examples is guessing with extra steps; 0 disables it.
		final List< Double > few = new ArrayList<>();
		for ( int i = 0; i < 49; i++ )
			few.add( 100.0 + i );
		assertEquals( 0.0, ElephantTool.volumePercentile( few, 5 ), 1e-12 );
	}

	@Test
	public void theHeterogeneityCeilingSitsAboveNearlyEveryConfirmedCell()
	{
		// The deletion criterion: uneven brightness inside the spot. Calibrated so 5% of confirmed
		// cells sit above the ceiling, which is what keeps a dim real cell — whose small mean inflates
		// the ratio — from being cut, since the dim confirmed cells raise the ceiling by that much.
		final List< Double > confirmed = new ArrayList<>();
		for ( int i = 0; i < 1000; i++ )
			confirmed.add( i / 1000.0 );
		final double ceiling = ElephantTool.volumePercentile( confirmed, 95 );
		assertEquals( 0.95, ceiling, 1e-9 );
		int above = 0;
		for ( final double h : confirmed )
			if ( h > ceiling )
				above++;
		assertTrue( above <= 50, "at most 5% of confirmed cells exceed the ceiling, got " + above );
	}

	@Test
	public void thePercentileSitsBelowNearlyEveryConfirmedCell()
	{
		// 5% of confirmed cells fall below the floor, so the daughter cells among them survive it —
		// which is the whole reason the threshold comes from confirmed cells and not from the
		// detections, whose small mode IS where daughters sit.
		final List< Double > volumes = new ArrayList<>();
		for ( int i = 0; i < 1000; i++ )
			volumes.add( ( double ) i );
		final double floor = ElephantTool.volumePercentile( volumes, 5 );
		assertEquals( 50.0, floor, 1e-9 );
		int below = 0;
		for ( final double v : volumes )
			if ( v < floor )
				below++;
		assertTrue( below <= 50, "at most the requested percentile falls below the floor, got " + below );
	}
}
