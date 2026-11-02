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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * A pass whose only job is to stop a deletion must say why the thing is worth keeping.
 *
 * <p>Step 3c of {@code correctTimepoint} rescues fully-isolated spots by linking them to a nearby
 * dividing mother, and it runs immediately before {@code deleteIsolatedSpots} precisely so that they
 * survive it. Every gate it applied was geometric — a free successor slot, a radius-scaled distance,
 * similar size — so a detection sitting on background, near a cell with room, was linked into a
 * track and thereby made permanent. The user saw it as a circle on empty black with a long link,
 * across consecutive timepoints.</p>
 *
 * <p>Nothing downstream catches it: {@code deleteSpotsWithNoSignal} needs a second anchor from the
 * user's confirmed cells, and with none its ceiling collapses to just below the frame's own
 * background median — it deleted 0 in every window of the run this came from.
 * {@code deleteUnstableSpots} needs the track to span under three timepoints, which a rescued spot
 * no longer does.</p>
 */
public class IsolatedRescueSignalTest
{
	private static final Path SRC = Paths.get( "src", "main", "java", "org", "mastodon", "mamut",
			"automahout", "autocorrect", "AutoCorrectOrchestrator.java" );

	/**
	 * The body of the 5-argument {@code linkOrphanStarts}, not one of its two short overloads — which
	 * is what the first version of this test read, and why it passed on a body that could not contain
	 * the check it was asserting.
	 */
	private static String linkOrphanStartsBody() throws IOException
	{
		final String text = new String( Files.readAllBytes( SRC ), StandardCharsets.UTF_8 );
		final Matcher sig = Pattern.compile(
				"(?m)^\\t(private )?static int linkOrphanStarts\\(\\s*$" ).matcher( text );
		final Matcher end = Pattern.compile( "(?m)^\\t\\}" ).matcher( text );
		while ( sig.find() )
		{
			assertTrue( end.find( sig.start() ) );
			final String body = text.substring( sig.start(), end.end() );
			if ( body.contains( "requireFullyIsolated" ) && body.contains( "maxDisplacement" ) )
				return body;
		}
		throw new AssertionError( "the 5-argument linkOrphanStarts has moved" );
	}

	/** Comments stripped, so prose naming the check cannot satisfy a check for the code. */
	private static String code( final String source )
	{
		return source.replaceAll( "(?s)/\\*.*?\\*/", " " ).replaceAll( "(?m)//.*$", " " );
	}

	@Test
	public void theRescueMeasuresTheSpotAgainstTheFramesOwnNoiseFloor() throws IOException
	{
		final String body = code( linkOrphanStartsBody() );
		assertTrue( "the rescue must measure the orphan, not only its geometry",
				body.contains( "standoutAboveBackground(" ) );
		assertTrue( "and compare it against the background sigma the rest of the pipeline uses",
				body.contains( "DetectionGate.DEEP_BACKGROUND_SIGMA" ) );
	}

	@Test
	public void onlyTheRescueIsGatedThisWay() throws IOException
	{
		// Ordinary orphan linking must NOT require signal: a dim real cell the detector only just
		// found still deserves its link, and that path is not overriding a deletion.
		final String body = code( linkOrphanStartsBody() );
		final int measure = body.indexOf( "standoutAboveBackground(" );
		assertTrue( measure > 0 );
		final String before = body.substring( Math.max( 0, measure - 400 ), measure );
		assertTrue( "the measurement must be inside the requireFullyIsolated branch: " + before,
				before.contains( "requireFullyIsolated" ) );
	}

	@Test
	public void anUnmeasurablePositionIsNotTreatedAsBackground() throws IOException
	{
		// standoutAboveBackground returns 0.0 when it could not measure — no background estimate, no
		// sampler. That is absence of evidence, and refusing a rescue on it would delete cells on any
		// frame the estimate fails on.
		final String body = code( linkOrphanStartsBody() );
		assertTrue( "an unmeasurable orphan must keep its rescue",
				body.contains( "standout != 0.0" ) );
	}

	@Test
	public void theRefusalIsReportedRatherThanSilent() throws IOException
	{
		final String body = linkOrphanStartsBody();
		assertTrue( "a rescue withheld is a decision the log has to carry",
				body.contains( "left unrescued" ) );
	}

	@Test
	public void theRescueStillRunsBeforeTheDeletionItOverrides() throws IOException
	{
		// If the order ever flips, the gate above becomes pointless: the spot is deleted first.
		final java.util.List< String > plan = new java.util.ArrayList<>();
		for ( final org.mastodon.mamut.automahout.tool.CorrectionPass p : FramePasses.BUILT_IN )
			plan.add( p.getId() );
		final int rescue = plan.indexOf( "frame.isolated_rescue" );
		final int delete = plan.indexOf( "frame.isolated_spots" );
		assertTrue( "the rescue pass has moved", rescue >= 0 );
		assertTrue( "the isolated-spot deletion has moved", delete >= 0 );
		assertTrue( "the rescue must still precede the deletion it exists to override",
				rescue < delete );
	}

	@Test
	public void theTwoCallSitesStillDifferInMode() throws IOException
	{
		final String text = new String( Files.readAllBytes( SRC.resolveSibling( "FramePasses.java" ) ),
				StandardCharsets.UTF_8 );
		final Matcher m = Pattern.compile(
				"linkOrphanStarts\\( s\\.ctx, s\\.timepoint, [0-9.]+, (true|false)," ).matcher( text );
		int rescue = 0, ordinary = 0;
		while ( m.find() )
		{
			if ( "true".equals( m.group( 1 ) ) )
				rescue++;
			else
				ordinary++;
		}
		assertEquals( "one rescue call", 1, rescue );
		assertTrue( "and at least one ordinary orphan-linking call", ordinary >= 1 );
	}
}
