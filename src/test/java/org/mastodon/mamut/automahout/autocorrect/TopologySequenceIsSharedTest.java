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

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Phase 2 must not carry a second, weaker copy of the topology sequence.
 *
 * <p>It did, and the copy had drifted until it undid Phase 1's work. {@code auto.correct}'s
 * whole-movie path ran the rapid-division check BEFORE {@code reconnectOrphanTracklets} — the pass
 * that CREATES divisions, by giving a predecessor that already has one child a second one — so every
 * division reconnection made went unchecked. It de-branched only when the profile said divisions were
 * rare, which with no profile is never, so nothing judged them afterwards either. And it never called
 * {@code deleteUnstableSpots}, so the short noise tracks and border fragments Phase 1 deletes came
 * back and stayed.</p>
 *
 * <p>The run this was found in reported it exactly: Phase 1 finished ten clean windows, then Phase 2
 * returned {@code divisions=167, min_div_interval=1 (threshold=auto=29)} — divisions one timepoint
 * apart against a threshold of 29 — and the lineage view filled with short branches again.</p>
 */
public class TopologySequenceIsSharedTest
{
	private static final Path AUTO_CORRECT_TOOL = Paths.get( "src", "main", "java", "org", "mastodon",
			"mamut", "automahout", "tool", "impl", "AutoCorrectTool.java" );

	private static String source( final Path p ) throws IOException
	{
		final String text = new String( Files.readAllBytes( p ), StandardCharsets.UTF_8 );
		// Prose may name any of these; only code counts. Same lesson as the merge-switch ratchet.
		return text.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
	}

	@Test
	public void theWholeMovieCorrectionGoesThroughTheOneTopologySequence() throws IOException
	{
		assertTrue( "auto.correct's whole-movie path must end in runTopologyConsistency, not in its own "
				+ "arrangement of the same passes",
				source( AUTO_CORRECT_TOOL ).contains( "runTopologyConsistency" ) );
	}

	@Test
	public void itDoesNotReassembleTheSequenceForItself() throws IOException
	{
		// Each of these is a step runTopologyConsistency owns. Calling one directly from here is how the
		// copy started: a pass added in the right place in one sequence and the wrong place in the other.
		final String src = source( AUTO_CORRECT_TOOL );
		final List< String > offenders = new ArrayList<>();
		for ( final String step : new String[] { "removeRapidSuccessiveDivisions",
				"deleteShortPostDivisionBranches", "reconnectOrphanTracklets", "closeTrackGaps",
				"dissolveExcessDivisions", "dissolveImplausibleDivisions", "deleteUnstableSpots",
				"mergePersistentCoLocatedPairs" } )
			if ( src.contains( step + "(" ) || src.contains( step + " (" ) )
				offenders.add( step );
		assertTrue( "these belong to runTopologyConsistency, which orders them; calling them from "
				+ "auto.correct rebuilds the divergent copy this test exists to prevent: " + offenders,
				offenders.isEmpty() );
	}

	/**
	 * And the ordering inside the one sequence: whatever judges a division has to run after whatever
	 * can make one. {@code reconnectOrphanTracklets} makes them; the de-branching pass judges them.
	 */
	@Test
	public void divisionsAreJudgedAfterThePassThatCreatesThem() throws IOException
	{
		final java.util.List< String > body = new java.util.ArrayList<>();
		for ( final org.mastodon.mamut.automahout.tool.CorrectionPass p : LineagePasses.BUILT_IN )
			body.add( p.getId() );
		final int creates = body.indexOf( LineagePasses.RECONNECT );
		final int judgesRare = body.indexOf( LineagePasses.EXCESS_DIVISIONS );
		final int judgesPlausible = body.indexOf( LineagePasses.IMPLAUSIBLE_DIVISIONS );
		final int deletesUnstable = body.indexOf( LineagePasses.UNSTABLE );
		assertTrue( "reconnection no longer runs here", creates >= 0 );
		assertTrue( "nothing judges a division after reconnection creates one",
				judgesRare > creates && judgesPlausible > creates );
		assertTrue( "the stability pass must see the finished tracks, so it runs last of the three",
				deletesUnstable > judgesRare && deletesUnstable > judgesPlausible );
	}
}
