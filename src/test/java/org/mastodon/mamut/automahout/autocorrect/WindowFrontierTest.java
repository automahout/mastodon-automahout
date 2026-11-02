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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A cell first detected in the LAST timepoint of a window has no forward link yet, because nothing
 * past that timepoint has been detected or linked. Until the next window runs it is indistinguishable
 * from a one-frame flicker, and the two are not equally costly to get wrong.
 */
public class WindowFrontierTest
{
	/** Mirrors deleteUnstableSpots: a track whose last timepoint reaches the frontier is spared. */
	private static boolean spared( final int trackLastTp, final int endTp, final int minStableLen )
	{
		return trackLastTp >= endTp - minStableLen + 1;
	}

	@Test
	public void aTrackReachingTheWindowEndIsSpared()
	{
		// The reported case: detection starts at t=9, the window ends at t=9, and the next window would
		// have carried it forward.
		assertTrue( spared( 9, 9, 3 ) );
		assertTrue( spared( 8, 9, 3 ) );
		assertTrue( spared( 7, 9, 3 ) );
		// A flicker that ended well inside the window had its chance to grow and did not.
		assertFalse( spared( 6, 9, 3 ) );
		assertFalse( spared( 2, 9, 3 ) );
	}

	@Test
	public void theFrontierIsTheRangeJudgedNotTheLastSpotAnywhere()
	{
		// What was broken: the threshold came from the last timepoint holding ANY spot, and the user's
		// confirmed spots spanned all 74 timepoints of the movie. Judging the window t=0..9 then used a
		// frontier of 73, which protects nothing in that window at all.
		final int windowEnd = 9;
		final int lastSpotAnywhere = 73;
		assertTrue( spared( 9, windowEnd, 3 ), "against the window end, t=9 is at the frontier" );
		assertFalse( spared( 9, lastSpotAnywhere, 3 ), "against the movie end it was not" );
	}
}
