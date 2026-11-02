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
package org.mastodon.mamut.automahout.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mastodon.geff.GeffEdge;
import org.mastodon.geff.GeffNode;

/**
 * Verifies {@link LineageConstraintLearner} on a hand-built synthetic lineage whose expected
 * statistics are known by construction.
 *
 * <p>The lineage: a single founder (1) moves through t0→t1→t2, divides at t2 into two daughters
 * (4, 5) at t3, each continuing to t4 (6, 7). Positions are chosen so every continuation step moves
 * exactly 1 world unit and the two daughters are 2 units apart with radii 4 and 6.</p>
 *
 * <pre>
 *   1(t0) → 2(t1) → 3(t2) ─┬→ 4(t3) → 6(t4)
 *                          └→ 5(t3) → 7(t4)
 * </pre>
 */
public class LineageConstraintLearnerTest
{

	private static GeffNode node( final int id, final int t, final double x, final double y,
			final double z, final double r )
	{
		final GeffNode n = new GeffNode();
		n.setId( id );
		n.setT( t );
		n.setX( x );
		n.setY( y );
		n.setZ( z );
		n.setRadius( r );
		return n;
	}

	private static GeffEdge edge( final int src, final int tgt )
	{
		final GeffEdge e = new GeffEdge();
		e.setSourceNodeId( src );
		e.setTargetNodeId( tgt );
		return e;
	}

	private static GeffGtLoader.GtGraph syntheticLineage()
	{
		final List< GeffNode > nodes = new ArrayList<>();
		nodes.add( node( 1, 0, 0, 0, 0, 5 ) );
		nodes.add( node( 2, 1, 0, 1, 0, 5 ) );
		nodes.add( node( 3, 2, 0, 2, 0, 5 ) );
		nodes.add( node( 4, 3, 1, 2, 0, 4 ) );
		nodes.add( node( 5, 3, -1, 2, 0, 6 ) );
		nodes.add( node( 6, 4, 2, 2, 0, 4 ) );
		nodes.add( node( 7, 4, -2, 2, 0, 6 ) );

		final List< GeffEdge > edges = new ArrayList<>();
		edges.add( edge( 1, 2 ) );
		edges.add( edge( 2, 3 ) );
		edges.add( edge( 3, 4 ) );
		edges.add( edge( 3, 5 ) );
		edges.add( edge( 4, 6 ) );
		edges.add( edge( 5, 7 ) );

		return new GeffGtLoader.GtGraph( nodes, edges, "test" );
	}

	@Test
	public void learnsGraphSummary()
	{
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 1.0 );
		assertEquals( 7, p.numNodes );
		assertEquals( 6, p.numEdges );
		assertEquals( 1, p.numFounders );
		assertEquals( 1, p.numDivisions );
		assertEquals( 0, p.tMin );
		assertEquals( 4, p.tMax );
	}

	@Test
	public void structuralInvariantsAreClean()
	{
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 1.0 );
		assertEquals( 2, p.maxOutDegreeObserved );
		assertEquals( 0, p.inDegreeViolations );
		assertEquals( 0, p.outDegreeViolations );
		assertEquals( 0, p.midMovieAppearances );
		assertEquals( 0, p.midMovieTerminations );
	}

	@Test
	public void displacementIsOnePerFrame()
	{
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 1.0 );
		// Every continuation edge moves exactly one unit.
		assertEquals( 1.0, p.displacementP50, 1e-9 );
		assertEquals( 1.0, p.displacementMax, 1e-9 );
	}

	@Test
	public void daughterGeometryMatches()
	{
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 1.0 );
		assertEquals( 2.0, p.daughterSplitDistanceP50, 1e-9 ); // (1,2,0) to (-1,2,0)
		assertEquals( 1.5, p.daughterSizeRatioP50, 1e-9 ); // 6 / 4
	}

	@Test
	public void scaleIsApplied()
	{
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 2.0 );
		// Distances double under scale=2.
		assertEquals( 2.0, p.displacementP50, 1e-9 );
		assertEquals( 4.0, p.daughterSplitDistanceP50, 1e-9 );
		// A pure ratio is scale-invariant.
		assertEquals( 1.5, p.daughterSizeRatioP50, 1e-9 );
		// Radius scales.
		assertEquals( 10.0, p.radiusP50, 1e-9 );
	}

	@Test
	public void promptSectionDescribesLearnedGrammar()
	{
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 1.0 );
		final String prompt = p.promptSection();
		assertTrue( prompt.contains( "Learned Lineage-Graph Constraints" ), "has a header" );
		assertTrue( prompt.contains( "at most ONE parent" ), "states the single-parent invariant" );
		assertTrue( prompt.contains( "at most TWO daughters" ), "states the binary-division invariant" );
		assertTrue( prompt.contains( "per frame" ), "states the displacement bound" );
	}

	@Test
	public void learnsDivisionRate()
	{
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 1.0 );
		assertEquals( 1.0 / 7.0, p.divisionRate, 1e-9 ); // 1 division among 7 cells
		assertEquals( 1.0, p.divisionsPerFounder, 1e-9 ); // 1 division, 1 founder
	}

	@Test
	public void learnsSpotsPerTimepoint()
	{
		// Synthetic lineage: t0 has 1 cell, t1 has 1, t2 has 1, t3 has 2, t4 has 2 → max 2, median 1.
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 1.0 );
		assertEquals( 2.0, p.spotsPerTimepointMax, 1e-9 );
		assertEquals( 1.0, p.spotsPerTimepointP50, 1e-9 );
		assertTrue( p.promptSection().contains( "OVER-DETECTING" ), "warns against over-detection" );
	}

	@Test
	public void sparseProfileSuppressesDensityGuidanceButKeepsRelationalRules()
	{
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 1.0 );
		p.sparseAnnotation = true;
		final String prompt = p.promptSection();
		// density/rate guidance is suppressed
		assertTrue( !prompt.contains( "OVER-DETECTING" ), "no over-detection claim under sparse GT" );
		assertTrue( !prompt.contains( "divisions are RARE" ), "no division-rarity claim under sparse GT" );
		assertTrue( !prompt.contains( "Real track segments last" ), "no track-length rule under sparse GT" );
		assertTrue( prompt.contains( "SPARSELY annotated" ), "explains the sparse caveat" );
		// relational rules remain
		assertTrue( prompt.contains( "at most ONE parent" ), "keeps structural invariants" );
		assertTrue( prompt.contains( "per frame" ), "keeps the displacement bound" );
	}

	@Test
	public void sparseFlagRoundTripsThroughJson()
	{
		final LineageConstraintProfile p = LineageConstraintLearner.learn( syntheticLineage(), 1.0 );
		p.sparseAnnotation = true;
		final LineageConstraintProfile back =
				LineageConstraintProfile.fromJson( new org.json.JSONObject( p.toJson().toString() ) );
		assertTrue( back.sparseAnnotation );
	}

	@Test
	public void lineageGraphDataJsonRoundTrips()
	{
		final LineageGraphData in = new LineageGraphData(
				java.util.Arrays.asList(
						new LineageGraphData.Node( 1, 0, 1.5, 2.5, 3.5, 4.0 ),
						new LineageGraphData.Node( 2, 1, 5.0, 6.0, 7.0, 4.0 ) ),
				java.util.Arrays.asList( new LineageGraphData.Edge( 1, 2 ) ),
				"current Mastodon model", "mastodon-model" );
		final LineageGraphData out =
				LineageGraphData.fromJson( new org.json.JSONObject( in.toJson().toString() ) );
		assertEquals( 2, out.nodes.size() );
		assertEquals( 1, out.edges.size() );
		assertEquals( 0, out.nodes.get( 0 ).t );
		assertEquals( 1.5, out.nodes.get( 0 ).x, 1e-9 );
		assertEquals( 7.0, out.nodes.get( 1 ).z, 1e-9 );
		assertEquals( 1, out.edges.get( 0 ).sourceId );
		assertEquals( 2, out.edges.get( 0 ).targetId );
	}

	@Test
	public void displacementIsNormalisedByFrameGap()
	{
		// A single link spanning 4 frames with distance 8 → per-frame displacement 2, not 8.
		final java.util.List< GeffNode > nodes = new ArrayList<>();
		nodes.add( node( 1, 0, 0, 0, 0, 5 ) );
		nodes.add( node( 2, 4, 8, 0, 0, 5 ) );
		final java.util.List< GeffEdge > edges = new ArrayList<>();
		edges.add( edge( 1, 2 ) );
		final LineageConstraintProfile p =
				LineageConstraintLearner.learn( new GeffGtLoader.GtGraph( nodes, edges, "t" ), 1.0 );
		assertEquals( 2.0, p.displacementP50, 1e-9 );
	}

	@Test
	public void promptWarnsWhenDivisionsAreRare()
	{
		// 741 cells, 2 divisions ≈ 0.27 % → below the 5 % rarity threshold, so the strong warning fires.
		final java.util.List< GeffNode > nodes = new ArrayList<>();
		final java.util.List< GeffEdge > edges = new ArrayList<>();
		for ( int i = 1; i <= 741; i++ )
			nodes.add( node( i, i % 100, i, 0, 0, 5 ) );
		// two division points (nodes 1 and 2 each get two children)
		edges.add( edge( 1, 3 ) );
		edges.add( edge( 1, 4 ) );
		edges.add( edge( 2, 5 ) );
		edges.add( edge( 2, 6 ) );
		final LineageConstraintProfile p =
				LineageConstraintLearner.learn( new GeffGtLoader.GtGraph( nodes, edges, "t" ), 1.0 );
		assertEquals( 2, p.numDivisions );
		assertTrue( p.divisionRate < 0.05 );
		assertTrue( p.promptSection().contains( "divisions are RARE" ), "fires the rare-division warning" );
	}

	@Test
	public void promptSectionEmptyForEmptyProfile()
	{
		assertEquals( "", new LineageConstraintProfile().promptSection() );
	}

	@Test
	public void jsonRoundTripsWithNonFiniteStats()
	{
		// A plain 2-node chain has no divisions, so division-interval and daughter stats are NaN.
		// org.json rejects non-finite numbers, so toJson must map them to NULL and fromJson back to NaN.
		final java.util.List< GeffNode > nodes = new ArrayList<>();
		nodes.add( node( 1, 0, 0, 0, 0, 5 ) );
		nodes.add( node( 2, 1, 0, 1, 0, 5 ) );
		final java.util.List< GeffEdge > edges = new ArrayList<>();
		edges.add( edge( 1, 2 ) );
		final LineageConstraintProfile p =
				LineageConstraintLearner.learn( new GeffGtLoader.GtGraph( nodes, edges, "test" ), 1.0 );
		assertTrue( Double.isNaN( p.divisionIntervalMin ), "no divisions → NaN interval" );

		final String json = p.toJson().toString( 2 ); // must not throw
		final LineageConstraintProfile back =
				LineageConstraintProfile.fromJson( new org.json.JSONObject( json ) );
		assertTrue( Double.isNaN( back.divisionIntervalMin ), "NaN survives the round trip" );
		assertEquals( 1.0, back.displacementP50, 1e-9 ); // finite values are preserved
	}

	@Test
	public void detectsImpossibleShapes()
	{
		// Add an edge making node 6 have two parents (in-degree 2 = impossible merge) and node 3
		// gain a third daughter (out-degree 3 = impossible).
		final GeffGtLoader.GtGraph g = syntheticLineage();
		g.edges.add( edge( 7, 6 ) ); // 6 now has parents 4 and 7
		g.edges.add( edge( 3, 7 ) ); // 3 now has daughters 4, 5, 7
		final LineageConstraintProfile p = LineageConstraintLearner.learn( g, 1.0 );
		assertTrue( p.inDegreeViolations >= 1, "should flag the in-degree-2 merge" );
		assertTrue( p.outDegreeViolations >= 1, "should flag the out-degree-3 split" );
		assertTrue( p.maxOutDegreeObserved >= 3 );
	}
}
