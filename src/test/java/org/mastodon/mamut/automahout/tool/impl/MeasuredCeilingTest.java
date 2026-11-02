package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * A per-window ceiling judges a growing culture against what the previous window measured, not the run-start
 * guess alone. PSC 01, condition E (2026-10-20): the grid density probe compared 787/tp with the agent's guess of
 * 250 although the windows before had committed 459 and 538, and rebuilt a healthy model from intensity seeds.
 */
public class MeasuredCeilingTest
{
	/** Ratchet: a ceiling taken without a measured prior (0.0) is only a backstop behind MAX_CEILING_GROWTH_MULTIPLE. */
	@Test
	public void aCeilingWithoutAMeasuredPriorIsOnlyABackstop() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org", "mastodon", "mamut",
				"automahout", "tool", "impl", "ElephantTool.java" ) ), StandardCharsets.UTF_8 )
						.replaceAll( "(?s)/\\*.*?\\*/", " " ).replaceAll( "(?m)//.*$", " " );
		final Matcher m = Pattern.compile( "ceilingOver\\(\\s*w\\.context,\\s*w\\.wStart,\\s*w\\.wEnd,\\s*0\\.0," ).matcher( src );
		final List< String > unbacked = new ArrayList<>();
		int found = 0;
		while ( m.find() )
		{
			found++;
			final String after = src.substring( m.start(), Math.min( src.length(), m.start() + 400 ) );
			if ( !after.contains( "MAX_CEILING_GROWTH_MULTIPLE" ) )
				unbacked.add( after.substring( 0, Math.min( 120, after.length() ) ).replaceAll( "\\s+", " " ) );
		}
		assertTrue( "the ratchet no longer finds the backstops it describes", found >= 1 );
		assertEquals( "a per-window ceiling without the previous window's count", new ArrayList<>(), unbacked );
	}
}
