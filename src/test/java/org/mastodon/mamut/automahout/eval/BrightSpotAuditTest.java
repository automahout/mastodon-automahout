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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.automahout.eval.BrightSpotAudit.Watchpoint;

/** Tests for the per-location verdict that says whether a bright cell was missed or deleted. */
public class BrightSpotAuditTest
{
	private static Watchpoint wp()
	{
		return new Watchpoint( 3, new double[] { 10, 20, 30 } );
	}

	@Test
	public void aCallerWithASmallerAppetiteGetsACoarserStride()
	{
		// The intensity-seed scan only needs a few dozen bright clusters to centre a tuning crop on, and
		// at this class's own half-million budget its walk cost 94 seconds per window. A fifth of the
		// samples still leaves tens of thousands to find a handful of clusters in.
		final long voxels = 680L * 678L * 920L;
		final int shared = BrightSpotAudit.strideFor( voxels, 2 );
		final int smaller = BrightSpotAudit.strideFor( voxels, 2, 100_000L );
		assertTrue( smaller > shared, "expected a coarser stride for a smaller budget, got "
				+ smaller + " against " + shared );
		// Fewer samples, but not at any price: MAX_SCAN_STRIDE still caps how coarse the walk may get,
		// because past it the maxima found stop being the image's brightest. On this volume the cap
		// binds, so the budget is approached rather than met — which is the intended order of priority.
		assertTrue( voxels / ( ( long ) smaller * smaller * smaller )
				< voxels / ( ( long ) shared * shared * shared ) );
		// Where the cap does not bind, the budget is met outright.
		final long smallVolume = 300L * 300L * 300L;
		final int stride = BrightSpotAudit.strideFor( smallVolume, 2, 100_000L );
		assertTrue( smallVolume / ( ( long ) stride * stride * stride ) <= 100_000L );
	}

	@Test
	public void aLocationNoPassEverCoveredIsADetectionMissNotADeletion()
	{
		final Watchpoint w = wp();
		BrightSpotAudit.note( w, false, "[3/6] predict" );
		BrightSpotAudit.note( w, false, "[3/6] flat-contrast deletion" );
		BrightSpotAudit.note( w, false, "[4/6] link" );
		final String v = BrightSpotAudit.verdict( w );
		assertTrue( v.contains( "NEVER DETECTED" ), v );
		// The distinction the whole class exists for: never blame a pass for a spot that was not there.
		assertTrue( !v.contains( "REMOVED" ), v );
	}

	@Test
	public void aLocationStillCoveredNeedsNoReport()
	{
		final Watchpoint w = wp();
		BrightSpotAudit.note( w, true, "[3/6] predict" );
		BrightSpotAudit.note( w, true, "[5c/6] topology consistency" );
		assertNull( BrightSpotAudit.verdict( w ) );
	}

	@Test
	public void theNamedPassIsTheOneThatRemovedIt()
	{
		final Watchpoint w = wp();
		BrightSpotAudit.note( w, true, "[3/6] predict" );
		BrightSpotAudit.note( w, false, "[3/6] flat-contrast deletion" );
		BrightSpotAudit.note( w, false, "[4/6] link" );
		final String v = BrightSpotAudit.verdict( w );
		assertTrue( v.contains( "detected at [[3/6] predict]" ), v );
		assertTrue( v.contains( "REMOVED by [[3/6] flat-contrast deletion]" ), v );
		// Not the last pass that merely observed it missing.
		assertTrue( !v.contains( "REMOVED by [[4/6] link]" ), v );
	}

	@Test
	public void aSpotRecoveredByALaterPassIsNotReportedAsRemoved()
	{
		// Refinement's whole job is putting back cells an earlier pass dropped; counting that as a
		// deletion would make the report accuse the pipeline of the thing it just fixed.
		final Watchpoint w = wp();
		BrightSpotAudit.note( w, true, "[3/6] predict" );
		BrightSpotAudit.note( w, false, "[3/6] flat-contrast deletion" );
		BrightSpotAudit.note( w, true, "[5b/6] refinement" );
		assertNull( BrightSpotAudit.verdict( w ) );
	}

	@Test
	public void repeatedLossNamesTheLastPassAndCountsTheRest()
	{
		final Watchpoint w = wp();
		BrightSpotAudit.note( w, true, "[3/6] predict" );
		BrightSpotAudit.note( w, false, "[3/6] flat-contrast deletion" );
		BrightSpotAudit.note( w, true, "[5b/6] refinement" );
		BrightSpotAudit.note( w, false, "[5c/6] topology consistency" );
		final String v = BrightSpotAudit.verdict( w );
		assertTrue( v.contains( "REMOVED by [[5c/6] topology consistency]" ), v );
		assertTrue( v.contains( "lost 2 times" ), v );
	}

	@Test
	public void theSampledTimepointsSpanTheWindowAndNeverRepeat()
	{
		assertEquals( Arrays.asList( 0, 5, 9 ), BrightSpotAudit.sampledTimepoints( 0, 9 ) );
		assertEquals( Arrays.asList( 70, 72, 73 ), BrightSpotAudit.sampledTimepoints( 70, 73 ) );
		// A one- or two-timepoint window must not ask for the same scan twice — each one is a whole
		// volume read.
		assertEquals( Arrays.asList( 20 ), BrightSpotAudit.sampledTimepoints( 20, 20 ) );
		assertEquals( Arrays.asList( 20, 21 ), BrightSpotAudit.sampledTimepoints( 20, 21 ) );
	}

	@Test
	public void theScanStrideGrowsWithTheVolumeSoArmingStaysCheap()
	{
		// The stride was fixed at 4, so the cost of arming scaled with the image. 312x312x284 was about
		// 430 thousand reads per timepoint; 680x678x920 is 6.6 million, each a random access into a
		// chunked store, and three timepoints of that is twenty million — a run was left sitting at
		// exactly this point with nothing after it in the log.
		final long small = 312L * 312 * 284;
		final long large = 680L * 678 * 920;
		assertEquals( 4, BrightSpotAudit.strideFor( small ), "the smaller volume is unaffected" );
		final int coarse = BrightSpotAudit.strideFor( large );
		assertTrue( coarse > 4, "the larger volume gets a coarser stride, got " + coarse );
		assertTrue( large / ( ( long ) coarse * coarse * coarse ) <= 500_000L,
				"and lands inside the budget: " + large / ( ( long ) coarse * coarse * coarse ) );
	}

	@Test
	public void theStrideNeverGetsSoCoarseThatTheMaximaAreMeaningless()
	{
		// A cap, because past some point the brightest sampled position stops being the image's
		// brightest at all, and a watchpoint on nothing in particular is worse than no watchpoint.
		assertTrue( BrightSpotAudit.strideFor( Long.MAX_VALUE / 8 ) <= 16 );
		assertEquals( 4, BrightSpotAudit.strideFor( 0 ), "an unmeasurable volume keeps the default" );
	}

	@Test
	public void theSeedScanGetsTheSameTreatmentFromItsOwnFinerBase()
	{
		// The intensity-seed scan walks the same image for a different purpose at a stride of 2, so a
		// 680x678x920 volume was 53 million samples — nearly four minutes to pick a tuning region,
		// before a single predict had run.
		final long large = 680L * 678 * 920;
		final long small = 312L * 312 * 284;
		// The budget is the same for both, so the base only says where the search starts. Even the
		// smaller volume was 3.4 million samples a scan at stride 2 and is coarsened to 4.
		assertEquals( 4, BrightSpotAudit.strideFor( small, 2 ) );
		final int coarse = BrightSpotAudit.strideFor( large, 2 );
		assertTrue( coarse > 4, "the larger volume goes further, got " + coarse );
		assertTrue( large / ( ( long ) coarse * coarse * coarse ) <= 500_000L );
		// A volume small enough to scan finely keeps the finer base rather than being raised to the
		// audit's own — that is what passing a base is for.
		assertEquals( 2, BrightSpotAudit.strideFor( 1000L, 2 ) );
	}
}
