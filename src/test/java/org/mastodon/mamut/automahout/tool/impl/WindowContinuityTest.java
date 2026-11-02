package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * A window shorter than 3 timepoints has no interior timepoint, so its continuity is undefined. HeLa's
 * 92 frames end in a 2-tp window (t=90..91): it scored 0%, the run reported "worst=0% ... re-run
 * adaptive_track", and the agent re-tracked the whole movie (about 35 minutes, on the GPU server, 2026-10-17).
 */
public class WindowContinuityTest
{
	@Test
	public void continuityNeedsAnInteriorTimepoint()
	{
		assertFalse( ElephantTool.continuityDefined( 1 ) );
		assertFalse( ElephantTool.continuityDefined( 2 ) );
		assertTrue( ElephantTool.continuityDefined( 3 ) );
		assertTrue( ElephantTool.continuityDefined( 10 ) );
	}

	/** Ratchet: every read of a window's continuity checks continuityDefined (at most 6 lines above). */
	@Test
	public void everyReaderChecksThatContinuityIsDefined() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org", "mastodon",
				"mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ), StandardCharsets.UTF_8 )
						.replaceAll( "(?s)/\\*.*?\\*/", " " ).replaceAll( "(?m)//.*$", " " );
		final String[] lines = src.split( "\n" );
		final List< String > unguarded = new ArrayList<>();
		int reads = 0;
		for ( int i = 0; i < lines.length; i++ )
		{
			if ( !lines[ i ].contains( "w.continuity" ) || lines[ i ].contains( "w.continuity =" ) )
				continue;
			reads++;
			boolean guarded = false;
			for ( int k = Math.max( 0, i - 6 ); k <= i; k++ )
				guarded |= lines[ k ].contains( "continuityDefined(" );
			if ( !guarded )
				unguarded.add( "line " + ( i + 1 ) + ": " + lines[ i ].trim() );
		}
		assertTrue( "no reads of w.continuity found: the ratchet no longer looks at anything", reads >= 5 );
		assertEquals( "a window's continuity read without checking continuityDefined", new ArrayList<>(), unguarded );
	}
}
