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
package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The review's size check reads a predicted spot's radius from ELEPHANT's covariance. On a single plane ELEPHANT
 * gives the spot a z semi-axis of 1 (its 2D covariance is embedded in an identity); the radius must not count it.
 */
public class PredictedRadiusTest
{
	/** ELEPHANT's JSON covariance of a circle of radius r on a plane: x, y variances r², z variance 1. */
	private static JSONObject planeSpot( final double r )
	{
		return new JSONObject().put( "covariance",
				new JSONArray( new double[] { r * r, 0, 0, 0, r * r, 0, 0, 0, 1 } ) );
	}

	@Test
	public void onAPlaneTheRadiusIsTheCirclesNotAVolumes()
	{
		// Measured on Fluo-N2DL-HeLa: an 8.3 µm detection read as (8.3 * 8.3 * 1)^(1/3) = 4.1 µm, 0.55x the 7.45 µm
		// reference, below the review's 0.67 floor in every round.
		assertEquals( 8.3, ElephantTool.radiusOf( planeSpot( 8.3 ), false ), 1e-9 );
		assertEquals( Math.cbrt( 8.3 * 8.3 ), ElephantTool.radiusOf( planeSpot( 8.3 ), true ), 1e-9 );
	}

	@Test
	public void onAStackItIsStillTheSphereOfTheVolume()
	{
		final JSONObject s = new JSONObject().put( "covariance",
				new JSONArray( new double[] { 16, 0, 0, 0, 16, 0, 0, 0, 16 } ) );
		assertEquals( 4.0, ElephantTool.radiusOf( s, true ), 1e-9 );
	}

	@Test
	public void noCovarianceIsNoRadius()
	{
		assertEquals( Double.NaN, ElephantTool.radiusOf( new JSONObject(), false ), 0 );
	}
}
