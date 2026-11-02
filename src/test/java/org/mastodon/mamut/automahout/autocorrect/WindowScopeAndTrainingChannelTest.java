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

import org.junit.Test;

/**
 * Two things a re-run over an already-tracked project proved were not true.
 *
 * <p>Both were invisible on a fresh project and both fired the first time the pipeline was pointed at
 * a movie it had already finished — the case a person actually hits, because the second run is the
 * one you do after looking at the first.</p>
 */
public class WindowScopeAndTrainingChannelTest
{
	private static final Path ORCHESTRATOR = Paths.get( "src", "main", "java", "org", "mastodon",
			"mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" );

	private static final Path ELEPHANT_TOOL = Paths.get( "src", "main", "java", "org", "mastodon",
			"mamut", "automahout", "tool", "impl", "ElephantTool.java" );

	/** Prose may name any of these; only code counts. Same lesson as the merge-switch ratchet. */
	private static String source( final Path p ) throws IOException
	{
		final String text = new String( Files.readAllBytes( p ), StandardCharsets.UTF_8 );
		return text.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
	}

	/**
	 * The short-branch pass is bounded by its own range, not by what the graph happens to hold.
	 *
	 * <p>It asked the whole graph for the last timepoint that holds a spot, to anchor the zone it
	 * protects near the end of the tracked data. That is the same number as the window's end only
	 * while nothing above the window exists. Re-running over a finished 92-timepoint movie reversed
	 * it: for the window at t=0..9 the protection zone moved to t=89, every dangling end below it
	 * became eligible, and the pass deleted branches as far out as t=14. The structural invariant
	 * caught it and rolled the window back whole — which is why that run reported 9 of 10 windows and
	 * 82 of 92 timepoints, having silently dropped t=0..9 from its coverage.</p>
	 */
	@Test
	public void theShortBranchPassIsBoundedByItsOwnRange() throws IOException
	{
		final String src = source( ORCHESTRATOR );
		final int at = src.lastIndexOf( "int deleteShortPostDivisionBranches(" );
		assertTrue( "deleteShortPostDivisionBranches is still the name to bound", at >= 0 );
		final String body = src.substring( at, Math.min( src.length(), at + 4000 ) );
		assertTrue( "the last timepoint is taken within this call's range, not from the whole graph",
				body.contains( "lastTpInRange" ) );
		assertTrue( "and a vertex above the range is not judged here",
				body.contains( "> lastTpInRange" ) );
	}

	/**
	 * A training annotation that asserts a cell goes on the wire as a positive.
	 *
	 * <p>A cold-start seed and a restored GT position both say "a cell IS here". Both were filed under
	 * the same reason as a synthesized {@code fp} negative, so the chokepoint could not tell them
	 * apart and left ELEPHANT's graph listener's {@code fn} in place. The GT-trained payload read
	 * {@code fp=45, fn=440, tp=0} and the server agreed: {@code ('tp', 0), ('fn', 56)}.</p>
	 */
	@Test
	public void seedsAndRestoredGtGoOnTheWireAsPositives() throws IOException
	{
		final String src = source( ELEPHANT_TOOL );
		for ( final String site : new String[] { "placeSeedSpots", "restoreSpots" } )
		{
			final int at = src.indexOf( site + "( final ToolContext context," );
			assertTrue( site + " is still the name to check", at >= 0 );
			final String body = src.substring( at, Math.min( src.length(), at + 3500 ) );
			assertTrue( site + " asserts a cell, so it must say so at the chokepoint",
					body.contains( "Reason.TRAINING_POSITIVE" ) );
			assertTrue( site + " must ensure the tag set exists before taking the write lock, or the "
					+ "tp write is a silent no-op and the listener's fn stands",
					body.contains( "DetectionTagLabels.ensure( model )" ) );
		}
	}
}
