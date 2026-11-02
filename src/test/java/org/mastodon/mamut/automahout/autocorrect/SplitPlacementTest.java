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

import org.junit.Test;

/** How a fitted shape component becomes a spot. */
public class SplitPlacementTest
{
	/**
	 * A component with pixel variances (9, 1) is a uniform ellipse with semi-axes 2*3 and 2*1 pixels.
	 * A Mastodon spot's covariance carries squared semi-axes, so in world units at 0.645 per pixel
	 * the spot must have radii 3.87 and 1.29 -- not the 1.94 and 0.65 a plain variance would give.
	 */
	@Test
	public void aPieceTakesTheFittedEllipseAtItsTrueSize()
	{
		final double[][] original = new double[ 3 ][ 3 ];
		original[ 2 ][ 2 ] = 7.0;
		final double[][] cov = AutoCorrectOrchestrator.pieceCovariance( new double[] { 9, 0, 1 }, 0.645, original );
		assertEquals( 2 * 3 * 0.645, Math.sqrt( cov[ 0 ][ 0 ] ), 1e-9 );
		assertEquals( 2 * 1 * 0.645, Math.sqrt( cov[ 1 ][ 1 ] ), 1e-9 );
		assertEquals( 0.0, cov[ 0 ][ 1 ], 0.0 );
		assertEquals( "out of plane the detection's own extent is kept", 7.0, cov[ 2 ][ 2 ], 0.0 );
	}
}
