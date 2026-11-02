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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Keeps the annotation-extent correction alive in the autonomous path.
 *
 * <p>The correction existed for a long time and never ran where it was needed. It measured its
 * target from the user's CONFIRMED cells and returned immediately when there were none -- which is
 * every autonomous run. Meanwhile the detections it was meant to correct kept shrinking, because
 * training on ELEPHANT's core unchanged makes the next round's core smaller, and a detection
 * covering half a nucleus is exactly what lets two detections tile one nucleus.</p>
 *
 * <p>A source-text test, like the other ratchets in this codebase, because the rule is "this method
 * must not have a path that does nothing when nothing is confirmed" and a compiler cannot say it.
 * The class of bug is the one that keeps recurring here: a guard whose fallback is silence.</p>
 */
public class AnnotationExtentFallbackTest
{
	private static final Path SRC = Paths.get( "src", "main", "java",
			"org", "mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" );

	private static String bodyOf( final String method ) throws IOException
	{
		final String text = new String( Files.readAllBytes( SRC ), StandardCharsets.UTF_8 );
		final int start = text.indexOf( " " + method + "( final ToolContext" );
		assertTrue( method + " not found", start > 0 );
		// To the start of the next method declared at class level.
		final int end = text.indexOf( "\n\t}", start );
		assertTrue( "no end found for " + method, end > start );
		return text.substring( start, end );
	}

	@Test
	public void theExtentCorrectionHasAnEvidenceSourceWithoutConfirmedCells() throws IOException
	{
		final String body = bodyOf( "restoreAnnotationExtent" );
		assertTrue( "restoreAnnotationExtent must still prefer confirmed cells when they exist",
				body.contains( "confirmedCellProfile" ) );
		assertTrue( "restoreAnnotationExtent must fall back to measuring the cells in the image; "
				+ "without that it does nothing in an autonomous run, which is how it shipped",
				body.contains( "measuredCellExtent" ) );
	}

	@Test
	public void itDoesNotGiveUpWhenNothingIsConfirmed() throws IOException
	{
		final String body = bodyOf( "restoreAnnotationExtent" );
		// The exact shape of the bug: a null confirmed profile ending the method.
		final String[] giveUps = {
				"reference == null )\n\t\t\treturn",
				"confirmedCellProfile( context ) == null )\n\t\t\treturn",
				"confirmed == null )\n\t\t\treturn" };
		for ( final String giveUp : giveUps )
		{
			assertFalse( "restoreAnnotationExtent returns as soon as nothing is confirmed, so the "
					+ "correction is inert in exactly the runs that need it", body.contains( giveUp ) );
		}
	}

	@Test
	public void theImageFallbackIsMeasuredFromTheImageNotTheDetections() throws IOException
	{
		// The whole point: the detections are what is wrong, so they cannot be their own reference.
		final String body = bodyOf( "measuredCellExtent" );
		assertTrue( "measuredCellExtent must read the image, via measuredCellRadius",
				body.contains( "measuredCellRadius" ) );
		assertTrue( "fp spots are deliberately not on cells and must not size the annotation",
				body.contains( "DetectionTagLabels.FP" ) );
	}
}
