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
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.mastodon.mamut.automahout.elephant.DetectionTagLabels;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.TagSetStructure;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.model.tag.TagSetStructure.TagSet;

/**
 * Tagging one spot must not relabel the others.
 *
 * <p>A run reported {@code ('tp', 0), ('fn', 59)} on every training frame — every detection arriving
 * as a correction — right after {@code fn} was added to the tag set and started being applied to the
 * handful of spots gap-filling creates. Two spots per timepoint were meant to carry it.</p>
 */
public class TagAssignmentStabilityTest
{
	@Test
	public void ensureIsIdempotentAndKeepsWhatWasAlreadyTagged()
	{
		final ReplayGraph g = new ReplayGraph();
		final Model model = g.model();
		final Spot a = g.spot( 0, 10.0, 10.0, 0.0, 5.0 );
		final Spot b = g.spot( 0, 90.0, 90.0, 0.0, 5.0 );

		final TagSet ts1 = DetectionTagLabels.ensure( model );
		model.getTagSetModel().getVertexTags().set( a, DetectionTagLabels.tpTag( ts1 ) );
		assertEquals( "tp", DetectionTagLabels.labelOf( model, a ) );
		assertNull( "an untagged spot must stay untagged", DetectionTagLabels.labelOf( model, b ) );

		// ensure() is called again on every tagging, including once per gap-filled spot inside the
		// correction loop. If it rebuilds the structure it can drop or re-map what is already assigned.
		final TagSet ts2 = DetectionTagLabels.ensure( model );
		assertEquals( "tp survives a second ensure()", "tp", DetectionTagLabels.labelOf( model, a ) );
		assertNull( DetectionTagLabels.labelOf( model, b ) );

		final Tag fn = DetectionTagLabels.fnTag( ts2 );
		assertEquals( "fn", fn.label() );
		model.getTagSetModel().getVertexTags().set( b, fn );
		assertEquals( "fn", DetectionTagLabels.labelOf( model, b ) );
		assertEquals( "tagging b must not touch a", "tp", DetectionTagLabels.labelOf( model, a ) );
	}

	@Test
	public void manyEnsureCallsDoNotDriftTheAssignments()
	{
		// The correction loop calls ensure() once per spot it adds. Hundreds of calls per window.
		final ReplayGraph g = new ReplayGraph();
		final Model model = g.model();
		final Spot keep = g.spot( 0, 10.0, 10.0, 0.0, 5.0 );
		final TagSet ts = DetectionTagLabels.ensure( model );
		model.getTagSetModel().getVertexTags().set( keep, DetectionTagLabels.tpTag( ts ) );
		for ( int i = 0; i < 50; i++ )
			DetectionTagLabels.ensure( model );
		assertEquals( "tp", DetectionTagLabels.labelOf( model, keep ) );
	}

	/**
	 * The path the real project actually takes. Both tests above start from a model with no
	 * {@code Detection} tag set, so the first {@code ensure()} creates all four tags in one go and
	 * every later call changes nothing. In a real project the tag set already exists with
	 * {@code tp}/{@code fp} only — ELEPHANT's own GUI makes it that way, and a previous run saved it —
	 * so the first {@code ensure()} of the session ADDS two tags to a structure that already carries
	 * assignments.
	 */
	@Test
	public void addingTagsToAnExistingDetectionTagSetKeepsWhatWasAlreadyAssigned()
	{
		final ReplayGraph g = new ReplayGraph();
		final Model model = g.model();
		final Spot positive = g.spot( 0, 10.0, 10.0, 0.0, 5.0 );
		final Spot negative = g.spot( 0, 90.0, 90.0, 0.0, 5.0 );

		// A Detection tag set as ELEPHANT's own GUI leaves it: tp and fp, nothing else.
		final TagSetStructure tss = new TagSetStructure();
		tss.set( model.getTagSetModel().getTagSetStructure() );
		final TagSet created = tss.createTagSet( DetectionTagLabels.TAG_SET_NAME );
		created.createTag( DetectionTagLabels.TP, 0xFF00CC00 );
		created.createTag( DetectionTagLabels.FP, 0xFFCC0000 );
		model.getTagSetModel().setTagSetStructure( tss );
		final TagSet live = model.getTagSetModel().getTagSetStructure().getTagSets().stream()
				.filter( x -> DetectionTagLabels.TAG_SET_NAME.equals( x.getName() ) )
				.findFirst().orElseThrow( IllegalStateException::new );
		for ( final Tag t : live.getTags() )
		{
			if ( DetectionTagLabels.TP.equals( t.label() ) )
				model.getTagSetModel().getVertexTags().set( positive, t );
			if ( DetectionTagLabels.FP.equals( t.label() ) )
				model.getTagSetModel().getVertexTags().set( negative, t );
		}
		assertEquals( "tp", DetectionTagLabels.labelOf( model, positive ) );
		assertEquals( "fp", DetectionTagLabels.labelOf( model, negative ) );

		// ...and now fn and unlabeled are added to it, which is what ensure() does on its first call
		// in any project that has been opened in ELEPHANT before.
		DetectionTagLabels.ensure( model );

		assertEquals( "a tp assignment was re-mapped when the tag set grew", "tp",
				DetectionTagLabels.labelOf( model, positive ) );
		assertEquals( "an fp assignment was re-mapped when the tag set grew", "fp",
				DetectionTagLabels.labelOf( model, negative ) );
	}
}
