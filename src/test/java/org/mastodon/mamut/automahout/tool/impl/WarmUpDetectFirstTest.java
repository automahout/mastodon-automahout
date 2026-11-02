package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The warm-up's detect-first rule ({@link ElephantTool#alreadyDetects}): a model that already finds cells in the
 * tuning region, before any training in this run, is tracked with as it is; one that finds nothing, or floods
 * past what a frame may hold, is bootstrapped from intensity seeds as before.
 */
public class WarmUpDetectFirstTest
{
	@Test
	public void aModelThatFindsCellsIsTreatedAsTrained()
	{
		// A mouse embryo stated at 50 cells/frame (ceiling 100): 8 in the tuning region.
		assertTrue( ElephantTool.alreadyDetects( 8, 100 ) );
		// No count stated: any detections at all.
		assertTrue( ElephantTool.alreadyDetects( 8, Double.POSITIVE_INFINITY ) );
	}

	@Test
	public void aModelThatFindsNothingOrFloodsIsBootstrapped()
	{
		assertFalse( ElephantTool.alreadyDetects( 0, 100 ) );
		assertFalse( ElephantTool.alreadyDetects( 0, Double.POSITIVE_INFINITY ) );
		assertFalse( ElephantTool.alreadyDetects( 250, 100 ) );
	}
}
