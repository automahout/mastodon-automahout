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
package org.mastodon.mamut.automahout.elephant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for the background class weight that balances the segmentation loss by labelled volume.
 *
 * <p>Per voxel the two sides already weigh the same — a cell voxel carries the centre weight, a
 * background voxel carries {@code bg × falseWeight}, 10 against 1×10 by default. What is unbalanced is
 * how much of each the annotations contain.</p>
 */
public class BackgroundClassWeightTest
{
	private static final double CENTRE = 10.0;

	private static final double FALSE_WEIGHT = 10.0;

	@Test
	public void anEqualVolumeSplitNeedsNoExtraBackgroundPressure()
	{
		// tp == fp in volume: bg would be 10*1/(1*10) = 1, i.e. exactly the default, so nothing changes.
		assertEquals( 1.0,
				ElephantIntegration.balancedBackgroundWeight( 1000, 1000, 1.0, CENTRE, FALSE_WEIGHT ), 1e-9 );
	}

	@Test
	public void theWeightScalesWithHowFarTheVolumesAreApart()
	{
		// The observed regime: cell annotations outweigh background by ~30x in volume.
		assertEquals( 30.0,
				ElephantIntegration.balancedBackgroundWeight( 30000, 1000, 1.0, CENTRE, FALSE_WEIGHT ), 1e-9 );
	}

	@Test
	public void itNeverLowersTheConfiguredWeight()
	{
		// May only ADD background pressure. A user who deliberately raised the setting keeps their value
		// even when the volumes happen to be balanced.
		assertEquals( 5.0,
				ElephantIntegration.balancedBackgroundWeight( 1000, 1000, 5.0, CENTRE, FALSE_WEIGHT ), 1e-9 );
	}

	@Test
	public void aSeverelyStarvedWindowIsCappedRatherThanTakingOverTheLoss()
	{
		// A window whose synthetic negatives nearly all failed VLM confirmation leaves a handful of tiny
		// fp ellipsoids against thousands of cells; without a ceiling the loss becomes only about those.
		final double w = ElephantIntegration.balancedBackgroundWeight( 1_000_000, 1, 1.0, CENTRE,
				FALSE_WEIGHT );
		assertEquals( 200.0, w, 1e-9 );
	}

	@Test
	public void noNegativesAtAllLeavesTheSettingAlone()
	{
		// Nothing to balance against — and dividing by zero volume must not produce an infinite weight.
		assertEquals( 1.0,
				ElephantIntegration.balancedBackgroundWeight( 1000, 0, 1.0, CENTRE, FALSE_WEIGHT ), 1e-9 );
		assertEquals( 1.0,
				ElephantIntegration.balancedBackgroundWeight( 0, 0, 1.0, CENTRE, FALSE_WEIGHT ), 1e-9 );
	}

	@Test
	public void aZeroFalseWeightCannotDivideByZero()
	{
		assertTrue( Double.isFinite(
				ElephantIntegration.balancedBackgroundWeight( 1000, 10, 1.0, CENTRE, 0.0 ) ) );
	}
}
