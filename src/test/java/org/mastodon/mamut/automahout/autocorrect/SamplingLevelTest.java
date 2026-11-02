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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.imglib2.realtransform.AffineTransform3D;

/**
 * Which mipmap level a run measures intensity on. The measured cost of getting this wrong: a voxel
 * read at full resolution took 150-400 ms on the dataset that prompted it, because each one fetched
 * its own block, and a timepoint's cleanup on fifteen detections took a minute and a half against
 * two to eight seconds for the detection itself.
 */
public class SamplingLevelTest
{
	/** A BDV source transform for one level: voxel size on the diagonal. */
	private static AffineTransform3D level( final double vx, final double vy, final double vz )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.set( vx, 0, 0, 0, 0, vy, 0, 0, 0, 0, vz, 0 );
		return t;
	}

	/** Mirrors chooseSamplingLevel, which needs a BDV Source it cannot be handed in a unit test. */
	private static int choose( final double radius, final AffineTransform3D... levels )
	{
		int best = 0;
		for ( int i = 0; i < levels.length; i++ )
			if ( radius / AutoCorrectOrchestrator.largestVoxelEdge( levels[ i ] ) >= 3.0 )
				best = i;
		return best;
	}

	@Test
	public void theLongestVoxelEdgeIsWhatHasToFitTheCell()
	{
		// Anisotropic Z is the usual case here: 0.57 um in XY against 2 um in Z. A cell that spans
		// plenty of XY voxels can still span only one in Z, and the interior probes would then all read
		// the same plane — so the constraint is the LARGEST edge, not the mean or the smallest.
		assertEquals( 2.0, AutoCorrectOrchestrator.largestVoxelEdge( level( 0.57, 0.57, 2.0 ) ), 1e-9 );
	}

	@Test
	public void aCoarseLevelIsTakenWhenTheCellIsStillSeveralVoxelsWide()
	{
		// 10 um radius, 0.575 um voxels: 17 voxels at level 0, 8.7 at level 1, 4.3 at level 2, 2.2 at
		// level 3. Level 2 is the coarsest that still gives a radius of three voxels.
		assertEquals( 2, choose( 10.0, level( 0.575, 0.575, 0.575 ), level( 1.15, 1.15, 1.15 ),
				level( 2.3, 2.3, 2.3 ), level( 4.6, 4.6, 4.6 ) ) );
	}

	@Test
	public void aCellThatIsAlreadySmallInVoxelsStaysAtFullResolution()
	{
		// Nothing coarser than level 0 can be used when the cell is only four voxels across there —
		// the answer has to be full resolution, however expensive that is.
		assertEquals( 0, choose( 2.0, level( 0.575, 0.575, 2.0 ), level( 1.15, 1.15, 4.0 ) ) );
	}

	@Test
	public void aSingleLevelSourceHasOnlyOneAnswer()
	{
		assertEquals( 0, choose( 100.0, level( 1.0, 1.0, 1.0 ) ) );
	}

	@Test
	public void theChosenLevelKeepsTheCellWideEnoughToMeasureEvenness()
	{
		// The point of the floor: the twenty-one interior probes span +-0.8 of the radius, so a radius
		// of three voxels keeps them landing on distinct voxels inside the cell rather than all on one.
		final AffineTransform3D[] levels = { level( 0.5, 0.5, 0.5 ), level( 1.0, 1.0, 1.0 ),
				level( 2.0, 2.0, 2.0 ), level( 4.0, 4.0, 4.0 ) };
		final int chosen = choose( 9.0, levels );
		assertTrue( 9.0 / AutoCorrectOrchestrator.largestVoxelEdge( levels[ chosen ] ) >= 3.0 );
	}
}
