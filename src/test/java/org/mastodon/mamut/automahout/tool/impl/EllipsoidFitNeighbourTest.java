package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.EllipsoidFit3D;
import org.mastodon.mamut.automahout.tool.EllipsoidFitTestAccess;

/**
 * A 3-D fit may not grow over another detection's centre (the user's standing rule). Two detections ELEPHANT put on
 * one cell were both fitted to the whole cell, became one spot twice, and multiplied once taught back.
 */
public class EllipsoidFitNeighbourTest
{
	private static EllipsoidFit3D.Fit fit( final double[] centre, final double[][] cov )
	{
		return EllipsoidFitTestAccess.fit( centre, cov );
	}

	@Test
	public void aFitOverAnotherDetectionsCentreIsRefused()
	{
		// Cell 8 x 5 x 5 µm (semi-axes) at the origin; two detections on it, 4 µm apart along x.
		final double[][] cov = { { 64, 0, 0 }, { 0, 25, 0 }, { 0, 0, 25 } };
		final List< double[] > seeds = Arrays.asList( new double[] { -2, 0, 0, 3 }, new double[] { 2, 0, 0, 3 } );
		assertTrue( ElephantTool.containsAnotherCentre( fit( new double[] { 0, 0, 0 }, cov ), seeds, 0 ) );
	}

	@Test
	public void aFitClearOfOtherCentresIsKept()
	{
		final double[][] cov = { { 64, 0, 0 }, { 0, 25, 0 }, { 0, 0, 25 } };
		// The neighbour is 9 µm away along x: outside the 8 µm semi-axis.
		final List< double[] > seeds = Arrays.asList( new double[] { 0, 0, 0, 3 }, new double[] { 9, 0, 0, 3 } );
		assertFalse( ElephantTool.containsAnotherCentre( fit( new double[] { 0, 0, 0 }, cov ), seeds, 0 ) );
		// ...but 6 µm along y is outside the 5 µm semi-axis, and 6 µm along x is inside the 8.
		assertFalse( ElephantTool.containsAnotherCentre( fit( new double[] { 0, 0, 0 }, cov ),
				Arrays.asList( new double[] { 0, 0, 0, 3 }, new double[] { 0, 6, 0, 3 } ), 0 ) );
		assertTrue( ElephantTool.containsAnotherCentre( fit( new double[] { 0, 0, 0 }, cov ),
				Arrays.asList( new double[] { 0, 0, 0, 3 }, new double[] { 6, 0, 0, 3 } ), 0 ) );
	}

	@Test
	public void aRotatedEllipsoidIsTestedOnItsOwnAxes()
	{
		// 8 x 4 x 4 µm rotated 45 degrees in xy: (5.5, 5.5, 0) lies along the long axis (7.8 from the centre),
		// (2.5, -2.5, 0) across it (3.5, inside 4), (3.5, -3.5, 0) across it (4.9, outside).
		final double c = Math.cos( Math.PI / 4 ), s = Math.sin( Math.PI / 4 );
		final double l = 64, w = 16;
		final double[][] cov = { { c * c * l + s * s * w, c * s * ( l - w ), 0 }, { c * s * ( l - w ), s * s * l + c * c * w, 0 },
				{ 0, 0, w } };
		final EllipsoidFit3D.Fit f = fit( new double[] { 0, 0, 0 }, cov );
		assertTrue( ElephantTool.containsAnotherCentre( f, Arrays.asList( new double[] { 0, 0, 0, 3 }, new double[] { 5.5, 5.5, 0, 3 } ), 0 ) );
		assertTrue( ElephantTool.containsAnotherCentre( f, Arrays.asList( new double[] { 0, 0, 0, 3 }, new double[] { 2.5, -2.5, 0, 3 } ), 0 ) );
		assertFalse( ElephantTool.containsAnotherCentre( f, Arrays.asList( new double[] { 0, 0, 0, 3 }, new double[] { 3.5, -3.5, 0, 3 } ), 0 ) );
	}
}
