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
package org.mastodon.mamut.automahout.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class DetectionAcceptanceProfileTest
{

	private static DetectionAcceptanceProfile wellSeparated()
	{
		final DetectionAcceptanceProfile p = new DetectionAcceptanceProfile();
		p.nTp = 20;
		p.nFp = 20;
		// tp: bright, high-contrast; fp: dim, low-contrast; small spreads → clean separation.
		p.tpIntensityMean = 1000;
		p.tpIntensityStd = 100;
		p.fpIntensityMean = 200;
		p.fpIntensityStd = 80;
		p.tpContrastMean = 500;
		p.tpContrastStd = 80;
		p.fpContrastMean = 20;
		p.fpContrastStd = 40;
		p.computeSeparation();
		return p;
	}

	@Test
	public void acceptsCellLikeRejectsBackground()
	{
		final DetectionAcceptanceProfile p = wellSeparated();
		assertTrue( p.usable() );
		assertTrue( p.accept( 1050, 480 ), "bright high-contrast spot is a cell" );
		assertFalse( p.accept( 180, 10 ), "dim low-contrast spot is background" );
	}

	@Test
	public void acceptsDimButHighContrastCell()
	{
		// A dim cell (intensity near the fp/background mean) that still has a clear local peak
		// (contrast near the tp mean) must be ACCEPTED — contrast decides, dimness never rejects.
		final DetectionAcceptanceProfile p = wellSeparated();
		assertTrue( p.accept( 220, 470 ), "dim but high-contrast local peak is a real cell" );
	}

	@Test
	public void rejectsBrightButFlatBlob()
	{
		// A bright but flat detection (high intensity, low local contrast — e.g. a halo/glare region)
		// is background: contrast is the discriminator, so it must be REJECTED despite being bright.
		final DetectionAcceptanceProfile p = wellSeparated();
		assertFalse( p.accept( 1000, 15 ), "bright but flat (low-contrast) blob is background" );
	}

	@Test
	public void separationIsHighForCleanClasses()
	{
		final DetectionAcceptanceProfile p = wellSeparated();
		assertTrue( p.separation >= 2.0, "well-separated classes give high separation, got " + p.separation );
		assertTrue( p.confidenceNote().contains( "HIGH" ) );
	}

	@Test
	public void unusableWithoutBothClasses()
	{
		final DetectionAcceptanceProfile p = new DetectionAcceptanceProfile();
		p.nTp = 5;
		p.nFp = 0;
		assertFalse( p.usable() );
		assertTrue( p.accept( 0, 0 ), "no basis to reject → accept" );
	}

	@Test
	public void jsonRoundTrips()
	{
		final DetectionAcceptanceProfile p = wellSeparated();
		final DetectionAcceptanceProfile back =
				DetectionAcceptanceProfile.fromJson( new org.json.JSONObject( p.toJson().toString() ) );
		assertEquals( p.nTp, back.nTp );
		assertEquals( p.tpIntensityMean, back.tpIntensityMean, 1e-9 );
		assertEquals( p.separation, back.separation, 1e-9 );
		assertTrue( back.accept( 1050, 480 ) );
		assertFalse( back.accept( 180, 10 ) );
	}
}
