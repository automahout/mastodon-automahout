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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Every training call either prepares the annotations first or says why it does not.
 *
 * <p>The bug this exists for. {@code [2/6]} sized the annotations, withheld what the image does not
 * support as a cell, withheld the co-located pairs and capped the count. {@code [5b/6]} refinement,
 * which retrains a round later on detections ENRICHED by gap-filling and predecessor recovery, did
 * none of it — and nothing said so. A run held 53 spots/tp steady through detection, linking and
 * correction, then refinement retrained ungated and the next prediction reached 146 at t=0, tripped
 * the ceiling, and the whole window was discarded.</p>
 *
 * <p>Four of the nine training sites had the preparation; five did not, and the five were not a
 * decision anyone had recorded. A site may now be unprepared only with an explicit
 * {@code UNPREPARED-TRAINING-OK} marker giving the reason, so the next one added has to choose.</p>
 */
public class TrainingPreparationTest
{
	private static final Path SRC = Paths.get( "src", "main", "java", "org", "mastodon", "mamut",
			"automahout", "tool", "impl", "ElephantTool.java" );

	private static final Pattern TRAIN = Pattern.compile( "trainDetectionModel\\s*\\(" );

	@Test
	public void everyTrainingSiteIsPreparedOrExplicitlyExempt() throws IOException
	{
		final String text = new String( Files.readAllBytes( SRC ), StandardCharsets.UTF_8 );
		final Matcher m = TRAIN.matcher( text );
		final List< String > bare = new ArrayList<>();
		int prepared = 0, exempt = 0;
		while ( m.find() )
		{
			// Whichever of the three markers appears LAST before this call decides it. No lookback
			// distance to tune: another trainDetectionModel in between means this one has nothing of
			// its own, however much logging sits above it.
			final String before = text.substring( 0, m.start() );
			final int prep = before.lastIndexOf( "prepareAnnotationsForTraining" );
			final int ok = before.lastIndexOf( "UNPREPARED-TRAINING-OK" );
			final int prev = before.lastIndexOf( "trainDetectionModel" );
			if ( ok > prep && ok > prev )
				exempt++;
			else if ( prep > prev )
				prepared++;
			else
				bare.add( "line " + before.split( "\n", -1 ).length );
		}
		assertEquals( "a training call that neither prepares the graph's own detections nor says why "
				+ "it need not — this is how [5b/6] refinement came to retrain ungated and throw away "
				+ "a good window. Offending site(s): " + bare, 0, bare.size() );
		assertTrue( "expected several prepared sites, found " + prepared, prepared >= 4 );
		assertTrue( "expected several exempt sites, found " + exempt, exempt >= 4 );
	}

	@Test
	public void thePreparationStillDoesAllFourThings() throws IOException
	{
		// So the test above cannot pass by prepareAnnotationsForTraining quietly becoming a no-op.
		final String text = new String( Files.readAllBytes( SRC ), StandardCharsets.UTF_8 );
		final int start = text.indexOf( " prepareAnnotationsForTraining( final ToolContext" );
		assertTrue( "prepareAnnotationsForTraining not found", start > 0 );
		final String body = text.substring( start, text.indexOf( "\n\t}", start ) );
		for ( final String step : new String[] { "restoreAnnotationExtent",
				"withholdBelowDetectionGate", "withholdCoLocatedFromTraining",
				"capAnnotationsToStatedCount" } )
		{
			assertTrue( "prepareAnnotationsForTraining no longer calls " + step, body.contains( step ) );
		}
	}
}
