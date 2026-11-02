package org.mastodon.mamut.automahout.autocorrect;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * A movie whose frame is a flat floor (a background of exactly 0 under a sparse specimen) is sampled once, not
 * at every timepoint: each sample cost over half a minute per frame on a mouse embryo read over a network.
 */
public class FlatFloorStatsTest
{
	@Test
	public void aZeroBackgroundIsAFlatFloor()
	{
		// 99.9% zeros, three bright samples: the median is 0, the spread 0, nothing below it.
		final double[] sorted = new double[ 3000 ];
		sorted[ 2997 ] = 300;
		sorted[ 2998 ] = 400;
		sorted[ 2999 ] = 500;
		assertTrue( AutoCorrectOrchestrator.isFlatFloor( sorted, new double[] { 0, 0 } ) );
	}

	@Test
	public void aFrameWithSpreadOrValuesBelowTheMedianIsNot()
	{
		// HeLa-like: a measurable spread.
		assertFalse( AutoCorrectOrchestrator.isFlatFloor( new double[] { 33000, 33010, 33020 }, new double[] { 33010, 14.8 } ) );
		// Quantised (CE-like): MAD 0 but values below the median, so another frame may differ.
		assertFalse( AutoCorrectOrchestrator.isFlatFloor( new double[] { 3, 4, 4, 4, 5 }, new double[] { 4, 0 } ) );
	}

	@Test
	public void laterTimepointsAreGivenTheFloorWithoutReadingTheImage()
	{
		// No viewer: any attempt to sample would return null, so a result proves nothing was read.
		final ToolContext ctx = new ToolContext( null );
		final double[] floor = { 0, 0 };
		ctx.setSharedData( AutoCorrectOrchestrator.SHARED_FLAT_FLOOR_STATS, floor );
		assertSame( floor, AutoCorrectOrchestrator.frameLevelStats( ctx, 7 ) );
		assertArrayEquals( floor, AutoCorrectOrchestrator.frameLevelStats( ctx, 480 ), 0 );
	}

	/** Without a background spread to divide by, standing out above the background is not a measurement. */
	@Test
	public void standoutIsNotMeasurableWithoutABackgroundSpread()
	{
		assertFalse( AutoCorrectOrchestrator.standoutMeasurable( new ToolContext( null ), 0 ) );
	}
}
