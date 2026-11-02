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
package org.mastodon.mamut.automahout.elephant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.TagSetStructure.TagSet;

/**
 * The three annotation states a detection can be in, and the fact that they are three and not two.
 */
public class DetectionTagLabelsTest
{
	@Test
	public void withheldIsItsOwnStateAndNotAQuietTp()
	{
		// buildSpotsJson sends "fp" for the fp label and "tp" for everything else, so a third state that
		// collided with either of the first two would silently become a training label rather than an
		// omission — which is the exact failure it exists to prevent.
		assertNotEquals( DetectionTagLabels.TP, DetectionTagLabels.UNLABELED );
		assertNotEquals( DetectionTagLabels.FP, DetectionTagLabels.UNLABELED );
		assertEquals( "unlabeled", DetectionTagLabels.UNLABELED );
	}

	@Test
	public void missedIsItsOwnStateToo()
	{
		// fn is a POSITIVE — on the server it paints the same ellipsoid as tp — but at ten times the
		// weight, because it corrects an error rather than confirming a success. Colliding it with tp
		// would silently spend that weight on a confirmation.
		assertEquals( "fn", DetectionTagLabels.FN );
		assertNotEquals( DetectionTagLabels.TP, DetectionTagLabels.FN );
		assertNotEquals( DetectionTagLabels.FP, DetectionTagLabels.FN );
		assertNotEquals( DetectionTagLabels.UNLABELED, DetectionTagLabels.FN );
	}

	@Test
	public void theWireLabelIsThreeWayAndNotBinary() throws IOException
	{
		// It was `FP.equals(label) ? "fp" : "tp"`, which made fn unreachable however it was tagged —
		// every training call of a whole run reported ('fn', 0). A ratchet, because the collapse back
		// to two is a one-line edit that nothing else would notice.
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "elephant", "ElephantIntegration.java" ) ),
				StandardCharsets.UTF_8 );
		assertTrue( src.contains( "DetectionTagLabels.FN.equals( label ) ? \"fn\"" ),
				"the training payload must be able to say fn, or track surgery's findings cannot reach "
						+ "the model at the weight ELEPHANT gives a correction" );
	}

	/**
	 * The counter that is about to be believed over a guess.
	 *
	 * <p>A whole run reported {@code ('tp', 0), ('fn', 59)} on every training frame, and the reason it
	 * could not be diagnosed is that the server's {@code tp} count conflates "tagged tp" with
	 * "untagged" — the wire protocol sends anything that is neither {@code fp} nor {@code fn} as
	 * {@code tp}. These tests are on the tally that replaces the guess, because a wrong counter here
	 * sends the next investigation somewhere else again.</p>
	 */
	@Test
	public void theTallyCountsEachStateAndSeparatesUntaggedFromTp()
	{
		final Model model = new Model();
		final ModelGraph graph = model.getGraph();
		final TagSet ts = DetectionTagLabels.ensure( model );
		final Spot tagged = add( graph, 0, 0 );
		final Spot negative = add( graph, 0, 10 );
		final Spot missed = add( graph, 0, 20 );
		final Spot withheld = add( graph, 0, 30 );
		add( graph, 0, 40 ); // untagged: a positive by default, and the number that matters
		model.getTagSetModel().getVertexTags().set( tagged, DetectionTagLabels.tpTag( ts ) );
		model.getTagSetModel().getVertexTags().set( negative, DetectionTagLabels.fpTag( ts ) );
		model.getTagSetModel().getVertexTags().set( missed, DetectionTagLabels.fnTag( ts ) );
		model.getTagSetModel().getVertexTags().set( withheld, DetectionTagLabels.unlabeledTag( ts ) );

		final DetectionTagLabels.Tally t = DetectionTagLabels.tally( model, graph, new int[] { 0 } );

		assertEquals( 1, t.tp );
		assertEquals( 1, t.fp );
		assertEquals( 1, t.fn );
		assertEquals( 1, t.unlabeled );
		assertEquals( 1, t.untagged, "an untagged spot is not a tp — that conflation is the bug" );
		assertEquals( 5, t.total() );
	}

	@Test
	public void theTallyCountsOnlyTheTimepointsItWasGiven()
	{
		final Model model = new Model();
		final ModelGraph graph = model.getGraph();
		final TagSet ts = DetectionTagLabels.ensure( model );
		final Spot here = add( graph, 3, 0 );
		final Spot elsewhere = add( graph, 9, 0 );
		model.getTagSetModel().getVertexTags().set( here, DetectionTagLabels.fnTag( ts ) );
		model.getTagSetModel().getVertexTags().set( elsewhere, DetectionTagLabels.fnTag( ts ) );

		assertEquals( 1, DetectionTagLabels.tally( model, graph, new int[] { 3 } ).fn );
		assertEquals( 2, DetectionTagLabels.tally( model, graph, new int[] { 3, 9 } ).fn );
		assertEquals( 0, DetectionTagLabels.tally( model, graph, new int[] { 5 } ).total() );
		assertEquals( 0, DetectionTagLabels.tally( model, graph, new int[ 0 ] ).total() );
	}

	@Test
	public void withNoTagSetAtAllEverySpotIsUntagged()
	{
		// Which is to say: every spot would be trained as a positive. A tally that reported them as tp
		// would hide exactly that.
		final Model model = new Model();
		final ModelGraph graph = model.getGraph();
		add( graph, 0, 0 );
		add( graph, 0, 10 );

		final DetectionTagLabels.Tally t = DetectionTagLabels.tally( model, graph, new int[] { 0 } );

		assertEquals( 2, t.untagged );
		assertEquals( 0, t.tp );
	}

	@Test
	public void theTallySaysWhatUntaggedMeansWhereItIsRead()
	{
		// The line goes into the run log, which is where this gets diagnosed from.
		final Model model = new Model();
		add( model.getGraph(), 0, 0 );
		final String line = DetectionTagLabels.tally( model, model.getGraph(), new int[] { 0 } ).toString();
		assertTrue( line.contains( "untagged=1" ) );
		assertTrue( line.contains( "on the wire as tp" ),
				"a reader of the log must not have to know the wire protocol to read the number" );
	}

	private static Spot add( final ModelGraph graph, final int t, final double x )
	{
		final Spot ref = graph.vertexRef();
		return graph.addVertex( ref ).init( t, new double[] { x, 0, 0 }, 5.0 );
	}

	/**
	 * An {@code fp} spot is a label on a place that is NOT a cell, so a cell count must skip it.
	 *
	 * <p>Both cell counters looped over every vertex at a timepoint. Synthetic negatives already
	 * inflated them, and the error grows with every negative the pipeline learns to keep: the explosion
	 * guard, the expected-count ceiling, the deletion budget and the growth floor all read a population
	 * that includes labels saying "no cell here".</p>
	 */
	@Test
	public void anFpSpotIsNotACell()
	{
		final Model model = new Model();
		final ModelGraph graph = model.getGraph();
		final TagSet ts = DetectionTagLabels.ensure( model );
		final Spot cell = add( graph, 0, 0 );
		final Spot negative = add( graph, 0, 10 );
		final Spot untagged = add( graph, 0, 20 );
		model.getTagSetModel().getVertexTags().set( cell, DetectionTagLabels.tpTag( ts ) );
		model.getTagSetModel().getVertexTags().set( negative, DetectionTagLabels.fpTag( ts ) );

		final java.util.function.Predicate< Spot > isFp = DetectionTagLabels.fpTest( model );
		assertTrue( isFp.test( negative ) );
		assertFalse( isFp.test( cell ) );
		assertFalse( isFp.test( untagged ),
				"untagged goes on the wire as tp, so it is a cell here too" );
	}

	/** With no tag set nothing has been called a negative, so the test says so rather than throwing. */
	@Test
	public void withNoTagSetNothingIsANegative()
	{
		final Model model = new Model();
		final Spot s = add( model.getGraph(), 0, 0 );
		assertFalse( DetectionTagLabels.fpTest( model ).test( s ) );
		assertFalse( DetectionTagLabels.fpTest( null ).test( s ) );
	}

	/**
	 * Ratchet: the cell counters skip negatives, and the annotation counter does not.
	 *
	 * <p>They were one method counting every vertex, and the rename exists so that each of its
	 * eighteen callers had to say which it meant. Collapsing them again would put the guards back on a
	 * population that includes labels saying "no cell here" — silently, because the number only
	 * drifts as the pipeline keeps more negatives.</p>
	 */
	@Test
	public void theCellCountersSkipNegativesAndTheAnnotationCounterDoesNot() throws IOException
	{
		final String expected = read( "tool", "ExpectedCellCount.java" );
		assertTrue( expected.contains( "countCellsAt" ) && expected.contains( "!isFp.test( s )" ),
				"the shared cell count must exclude fp" );
		assertFalse( expected.contains( "countSpotsAt" ),
				"the old name meant \"every vertex\" and must not come back" );

		final String tool = read( "tool", "impl", "ElephantTool.java" );
		assertTrue( tool.contains( "countCellsAtTimepoints" ),
				"the cell counter must still exist" );
		assertTrue( tool.contains( "countAnnotationsAtTimepoints" ),
				"and the annotation counter beside it, because training reads tp AND fp" );
		assertTrue( tool.contains( "cellsOnly && isFp.test( spot )" ),
				"and the difference between them must be the fp test, not a comment" );

		final String orch = read( "autocorrect", "AutoCorrectOrchestrator.java" );
		assertTrue( orch.contains( "return ExpectedCellCount.countCellsAt( ctx, timepoint );" ),
				"the second copy of the loop must stay a delegation, or the two rules drift" );
	}

	private static String read( final String... under ) throws IOException
	{
		java.nio.file.Path p = Paths.get( "src", "main", "java", "org", "mastodon", "mamut",
				"automahout" );
		for ( final String part : under )
			p = p.resolve( part );
		return new String( Files.readAllBytes( p ), StandardCharsets.UTF_8 );
	}
}
