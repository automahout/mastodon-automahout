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
package org.mastodon.mamut.automahout.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.automahout.elephant.DetectionTagLabels;
import org.mastodon.mamut.automahout.tool.impl.ElephantTool;
import org.mastodon.mamut.model.Spot;

public class UserEditsTest
{
	@Test
	public void exactlyWhatTheUserAddedMovedOrRelinkedIsProtected()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot untouched = g.spot( 0, 100, 0, 0, 5 );
		final Spot moved = g.spot( 0, 0, 0, 0, 5 );
		final Spot linkedFrom = g.spot( 0, 50, 0, 0, 5 );
		final Spot linkedTo = g.spot( 1, 50, 0, 0, 5 );
		final ToolContext ctx = g.context();
		final Set< String > before = UserEdits.snapshot( g.graph() );

		moved.setPosition( 3.0, 0 );
		g.link( linkedFrom, linkedTo );
		final Spot added = g.spot( 1, 20, 0, 0, 5 );

		assertEquals( 4, UserEdits.protectChanged( ctx, before ) );
		final ProtectedSpots.Config config = ProtectedSpots.fromContext( ctx );
		assertNotNull( "protection is switched on for the run", config );
		assertEquals( UserEdits.TAG_SET, config.tagSetName );
		assertTrue( ProtectedSpots.isProtected( ctx, g.model(), moved ) );
		assertTrue( ProtectedSpots.isProtected( ctx, g.model(), added ) );
		assertTrue( ProtectedSpots.isProtected( ctx, g.model(), linkedFrom ) );
		assertTrue( ProtectedSpots.isProtected( ctx, g.model(), linkedTo ) );
		assertFalse( ProtectedSpots.isProtected( ctx, g.model(), untouched ) );
		// ...and it survives the next tool call, which resets the protection from the run's choice.
		assertEquals( config, ctx.getSharedData( ElephantTool.SHARED_PROTECTED_TAG_CONFIG ) );
	}

	@Test
	public void theDetectorsOwnLabelsSurviveTheNewTagSet()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot labelled = g.spot( 0, 0, 0, 0, 5 );
		g.model().getTagSetModel().getVertexTags().set( labelled,
				DetectionTagLabels.fpTag( DetectionTagLabels.ensure( g.model() ) ) );
		final ToolContext ctx = g.context();
		final Set< String > before = UserEdits.snapshot( g.graph() );
		g.spot( 1, 0, 0, 0, 5 );
		UserEdits.protectChanged( ctx, before );
		assertTrue( "the fp label is still on its spot", DetectionTagLabels.fpTest( g.model() ).test( labelled ) );
	}

	@Test
	public void aProtectedTagTheUserChoseIsTheOneUsed()
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot mine = g.spot( 0, 0, 0, 0, 5 );
		final ToolContext ctx = g.protect( mine ); // Status > Completed
		final ProtectedSpots.Config chosen = ProtectedSpots.fromContext( ctx );
		ctx.setSharedData( ElephantTool.SHARED_PROTECTED_TAG_CONFIG, chosen );
		final Set< String > before = UserEdits.snapshot( g.graph() );
		final Spot added = g.spot( 1, 0, 0, 0, 5 );
		UserEdits.protectChanged( ctx, before );
		assertEquals( chosen, ProtectedSpots.fromContext( ctx ) );
		assertTrue( ProtectedSpots.isProtected( ctx, g.model(), added ) );
		assertTrue( ProtectedSpots.isProtected( ctx, g.model(), mine ) );
	}

	@Test
	public void nothingChangedProtectsNothingAndCreatesNoTag()
	{
		final ReplayGraph g = new ReplayGraph();
		g.spot( 0, 0, 0, 0, 5 );
		final ToolContext ctx = g.context();
		assertEquals( 0, UserEdits.protectChanged( ctx, UserEdits.snapshot( g.graph() ) ) );
		assertEquals( null, ProtectedSpots.fromContext( ctx ) );
	}
}
