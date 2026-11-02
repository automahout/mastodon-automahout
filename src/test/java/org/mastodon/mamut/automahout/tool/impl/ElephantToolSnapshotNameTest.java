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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Tests for the detection-model snapshot filename derivation and the coverage-scoped verdicts. */
public class ElephantToolSnapshotNameTest
{
	@Test
	public void aPositiveVerdictIsScopedWhenCoverageIsPartial()
	{
		// The shipped case: 2 of 8 windows processed, yet the result said "Tracks are continuous" and the
		// agent reported back that it had "processed all 74 timepoints".
		assertEquals( " Tracks are continuous WHERE PROCESSED (20/74 tp).",
				ElephantTool.scopedToCoverage( " Tracks are continuous.", 20, 74 ) );
		assertEquals( " Detection looks complete WHERE PROCESSED (20/74 tp).",
				ElephantTool.scopedToCoverage( " Detection looks complete.", 20, 74 ) );
	}

	@Test
	public void aPositiveVerdictIsLeftAloneOnFullCoverage()
	{
		assertEquals( " Tracks are continuous.",
				ElephantTool.scopedToCoverage( " Tracks are continuous.", 74, 74 ) );
		assertEquals( " Tracks are continuous.",
				ElephantTool.scopedToCoverage( " Tracks are continuous.", 80, 74 ) );
	}

	@Test
	public void theFpBudgetTracksTenPercentOfTheWindowsTpCount()
	{
		// The number that was missing in practice: a window holding ~4000 tp annotations used to ask for
		// 30 negatives, under 1%, because the cap was set by a VLM per-call media limit.
		assertEquals( 400, ElephantTool.syntheticFpBudget( 4000 ) );
		assertEquals( 100, ElephantTool.syntheticFpBudget( 1000 ) );
		assertEquals( 42, ElephantTool.syntheticFpBudget( 417 ) );
	}

	@Test
	public void theFpBudgetHasAFloorAndACeiling()
	{
		// Floor: a sparse window still needs enough negatives to be worth a call at all.
		assertEquals( 20, ElephantTool.syntheticFpBudget( 0 ) );
		assertEquals( 20, ElephantTool.syntheticFpBudget( 50 ) );
		// Ceiling: bounded by VLM calls, not by the per-call media limit.
		assertEquals( 400, ElephantTool.syntheticFpBudget( 100000 ) );
	}

	@Test
	public void theRoundSnapshotIsADistinctFileFromTheWindowOne()
	{
		// They undo different things — a window snapshot reverts the whole window on abandonment, a round
		// snapshot reverts one diverged tuning round — so one must never overwrite the other.
		final String window = ElephantTool.modelSnapshotFileName( "hiiragi/BlockA" );
		assertFalse( ( "round_" + window ).equals( window ) );
		assertEquals( "hiiragi_BlockA_detection.pth", window );
	}

	@Test
	public void aDatasetPathBecomesASingleFilename()
	{
		// The real name from a run: a separator left in would write outside the snapshot directory.
		final String name = ElephantTool.modelSnapshotFileName(
				"hiiragi/BlockA_stack0_ch1_binned_1x4x4_isotropic_upscale_reg_crop" );
		assertEquals( "hiiragi_BlockA_stack0_ch1_binned_1x4x4_isotropic_upscale_reg_crop_detection.pth",
				name );
		assertFalse( name.contains( "/" ) );
	}

	@Test
	public void separatorsAndTraversalAreNeutralised()
	{
		assertFalse( ElephantTool.modelSnapshotFileName( "../../etc/passwd" ).contains( "/" ) );
		assertFalse( ElephantTool.modelSnapshotFileName( "a\\b" ).contains( "\\" ) );
		assertEquals( "a_b_detection.pth", ElephantTool.modelSnapshotFileName( "a b" ) );
	}

	@Test
	public void plainNamesAreLeftAlone()
	{
		assertEquals( "sample-1.2_detection.pth", ElephantTool.modelSnapshotFileName( "sample-1.2" ) );
	}

	@Test
	public void anAbsentNameFallsBackRatherThanProducingABareSuffix()
	{
		assertEquals( "dataset_detection.pth", ElephantTool.modelSnapshotFileName( null ) );
		assertEquals( "dataset_detection.pth", ElephantTool.modelSnapshotFileName( "" ) );
		assertEquals( "dataset_detection.pth", ElephantTool.modelSnapshotFileName( "   " ) );
	}
}
