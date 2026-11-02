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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Keeps {@code mergeIntoCoveringEllipsoid} the only place a duplicate detection is resolved.
 *
 * <p>Four passes used to decide independently what to do about two detections on one cell, and three
 * of them threw one of the two away. That is wrong in the same way at all of them: the detections
 * TILE the cell, so neither is the cell, and whichever survives sits off centre and is smaller than
 * the cell — which is then what linking follows and what the next refinement round teaches the model
 * a cell looks like. The covering-ellipsoid rule was written once and, for a long time, applied at
 * exactly one of the four.</p>
 *
 * <p>A source-text test, like {@link org.mastodon.mamut.automahout.tool.GraphEditsChokepointTest},
 * because the rule is about where code may be written and a compiler cannot express it. It reads
 * every {@code Reason.DUPLICATE} in the main sources and requires it to be inside the one method.</p>
 */
public class DuplicateMergeChokepointTest
{
	private static final Path SRC = Paths.get( "src", "main", "java" );

	private static final Pattern DUPLICATE_REASON =
			Pattern.compile( "Reason\\s*\\.\\s*DUPLICATE" );

	/** The one method allowed to contain them, and the file it lives in. */
	private static final String OWNER_FILE = "AutoCorrectOrchestrator.java";

	private static final String OWNER_METHOD = "mergeIntoCoveringEllipsoid";

	/**
	 * The one exception, the user's choice (2026-10-23): two detections stacked in z on one nucleus, where the
	 * weaker is removed and the one kept is refitted to the whole nucleus with the 3-D ellipsoid fit -- which
	 * answers this rule's reason (a survivor off centre and too small) from the image instead of by merging,
	 * merging being opt-in.
	 */
	private static final String STACKED_METHOD = "removeStackedDuplicates";

	@Test
	public void everyDuplicateResolutionGoesThroughTheOneMerge() throws IOException
	{
		final List< String > offenders = new ArrayList<>();
		try ( Stream< Path > files = Files.walk( SRC ) )
		{
			for ( final Path f : ( Iterable< Path > ) files.filter( p -> p.toString().endsWith( ".java" ) )::iterator )
			{
				final String text = new String( Files.readAllBytes( f ), StandardCharsets.UTF_8 );
				final Matcher m = DUPLICATE_REASON.matcher( text );
				while ( m.find() )
				{
					if ( f.getFileName().toString().equals( OWNER_FILE )
							&& ( enclosingMethodOf( text, m.start() ).equals( OWNER_METHOD )
									|| enclosingMethodOf( text, m.start() ).equals( STACKED_METHOD ) ) )
						continue;
					offenders.add( f.getFileName() + ":" + lineOf( text, m.start() )
							+ " in " + enclosingMethodOf( text, m.start() ) );
				}
			}
		}
		assertEquals( "A duplicate must be resolved by merging the members into one covering "
				+ "ellipsoid, via " + OWNER_METHOD + " — never by deleting one of them. Offending "
				+ "site(s): " + offenders, 0, offenders.size() );
	}

	/** And the owner really does contain them, so the test above cannot pass by the rule vanishing. */
	@Test
	public void theOneMergeStillResolvesDuplicates() throws IOException
	{
		final String text = new String( Files.readAllBytes(
				SRC.resolve( "org/mastodon/mamut/automahout/autocorrect/" + OWNER_FILE ) ),
				StandardCharsets.UTF_8 );
		final Matcher m = DUPLICATE_REASON.matcher( text );
		int inOwner = 0;
		while ( m.find() )
		{
			if ( enclosingMethodOf( text, m.start() ).equals( OWNER_METHOD ) )
				inOwner++;
		}
		assertTrue( "expected the merge to both delete the members and add the merged spot",
				inOwner >= 2 );
	}

	/**
	 * Every pass that can merge must be behind the one switch, and that switch is OFF by default.
	 *
	 * <p>It gated one of the four. Turning "duplicate resolution" off therefore left {@code
	 * mergeDuplicateSpots}, {@code mergePersistentCoLocatedPairs} and ELEPHANT's NMS still merging —
	 * and all three end at the same covering ellipsoid. What the user saw was two clearly separated
	 * nuclei, each with its own detection, replaced by one ellipse around both.</p>
	 */
	@Test
	public void everyMergingPassIsBehindTheOneSwitch() throws IOException
	{
		final List< String > ungated = new ArrayList<>();
		try ( Stream< Path > files = Files.walk( SRC ) )
		{
			for ( final Path f : ( Iterable< Path > ) files
					.filter( p -> p.toString().endsWith( ".java" ) )::iterator )
			{
				final String text = new String( Files.readAllBytes( f ), StandardCharsets.UTF_8 );
				final Matcher m = Pattern.compile( "mergeIntoCoveringEllipsoid\\s*\\(" ).matcher( text );
				while ( m.find() )
				{
					final String method = enclosingMethodOf( text, m.start() );
					if ( method.equals( OWNER_METHOD ) )
						continue; // the chokepoint itself; its callers are what must be gated
					// Comments stripped first: three of these passes carry a comment NAMING the switch, and
					// a check that reads prose passes while the call is gone. Caught by deleting a real
					// gate and watching this test stay green.
					final String body = stripComments( bodyOf( text, method ) );
					if ( !body.contains( "duplicateResolutionEnabled" ) )
						ungated.add( f.getFileName() + ":" + lineOf( text, m.start() ) + " in " + method );
				}
			}
		}
		assertEquals( "a pass may merge two detections into one only behind "
				+ "CleanupToggles.duplicateResolutionEnabled — the detector has already decided where "
				+ "one cell ends. Ungated site(s): " + ungated, 0, ungated.size() );
	}

	/** {@code source} with line and block comments removed, so prose cannot satisfy a code check. */
	private static String stripComments( final String source )
	{
		return source.replaceAll( "(?s)/\\*.*?\\*/", " " ).replaceAll( "(?m)//.*$", " " );
	}

	/** Source of the method named {@code method}, from its signature to the next one. */
	private static String bodyOf( final String text, final String method )
	{
		final Matcher sig = Pattern.compile( "(?m)^\\t(?:public |private |protected |static |final )+"
				+ "[\\w<>\\[\\]., ]+?\\b" + Pattern.quote( method ) + "\\s*\\(" ).matcher( text );
		if ( !sig.find() )
			return "";
		final int from = sig.start();
		final Matcher next = Pattern.compile( "(?m)^\\t\\}" ).matcher( text );
		return next.find( from ) ? text.substring( from, next.end() ) : text.substring( from );
	}

	/** Name of the last method signature declared before {@code offset}. */
	private static String enclosingMethodOf( final String text, final int offset )
	{
		final Matcher m = Pattern.compile(
				"(?m)^\\t(?:public |private |protected |static |final |synchronized )+[\\w<>\\[\\]., ]+?"
						+ "\\b(\\w+)\\s*\\(" ).matcher( text );
		String last = "<none>";
		while ( m.find() && m.start() < offset )
			last = m.group( 1 );
		return last;
	}

	private static int lineOf( final String text, final int offset )
	{
		int line = 1;
		for ( int i = 0; i < offset; i++ )
			if ( text.charAt( i ) == '\n' )
				line++;
		return line;
	}
}
