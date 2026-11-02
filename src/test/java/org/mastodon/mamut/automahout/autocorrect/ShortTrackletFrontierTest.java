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

import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.CorrectionPass;
import org.mastodon.mamut.automahout.tool.PassScope;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Spot;

/** frame.short_tracklets and the frontier: a track is not "ended" where nothing has been linked onward yet. */
public class ShortTrackletFrontierTest
{
	private static CorrectionPass shortTracklets()
	{
		for ( final CorrectionPass p : FramePasses.BUILT_IN )
			if ( p.getId().equals( "frame.short_tracklets" ) )
				return p;
		throw new AssertionError( "no frame.short_tracklets" );
	}

	private static int runAt( final ReplayGraph g, final int t, final int numTimepoints )
	{
		final ToolContext ctx = g.context();
		final AutomahoutSettings settings = new AutomahoutSettings();
		settings.setCorrectMinTrackletLength( 5 );
		final PassScope scope = PassScope.frame( ctx, settings, t, numTimepoints, 0 );
		final CorrectionPass p = shortTracklets();
		return p.runsAt( scope ) ? p.run( scope ) : 0;
	}

	@Test
	public void aShortTrackAtTheEndOfAWindowIsKept()
	{
		// t=0..7 two long tracks; a daughter-like short track t=5..7. The window ends at t=7: nothing is linked
		// past it, though the movie goes on to t=99.
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 8, 0, 0 );
		g.track( 0, 8, 40, 0 );
		g.track( 5, 3, 80, 0 );
		assertEquals( 0, runAt( g, 7, 100 ) );
		assertEquals( 19, g.spotCount() );
	}

	@Test
	public void aShortTrackThatEndedInsideTheWindowStillGoes()
	{
		// The same short track, but ending at t=6 while the long ones go on to t=7: it ended.
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 8, 0, 0 );
		g.track( 0, 8, 40, 0 );
		final List< Spot > shortOne = g.track( 4, 3, 80, 0 );
		assertEquals( 3, runAt( g, 6, 100 ) );
		assertEquals( 16, g.spotCount() );
		assertEquals( 3, shortOne.size() );
	}
}
