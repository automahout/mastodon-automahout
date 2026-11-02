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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mastodon.geff.GeffEdge;
import org.mastodon.geff.GeffNode;

/**
 * Learns a {@link LineageConstraintProfile} from a cell-lineage graph, reducing it to structural
 * invariants (in/out-degree limits, mid-movie births/deaths) and statistical bounds (displacement,
 * division interval, radius, daughter geometry, track length) that transfer to new, GT-free data.
 *
 * <p>The learner is a pure function of a {@link LineageGraphData} — it does not mutate any model — so
 * it is deterministic and unit-testable. The input can come from a {@code .geff} ground-truth store
 * (via {@link GeffGtLoader}) or from the current Mastodon model (via {@link ModelGraphAdapter}).
 * Coordinates and radii are multiplied by {@code scale} to bring the source units into Mastodon world
 * units before any distance is measured.</p>
 */
public final class LineageConstraintLearner
{

	private LineageConstraintLearner()
	{}

	/**
	 * Loads the {@code .geff} store at {@code geffPath} and learns a constraint profile from it.
	 *
	 * @param geffPath path to the {@code .geff} ground-truth store
	 * @param scale    factor applied to GT coordinates/radii to match Mastodon world units
	 */
	public static LineageConstraintProfile learnFromGeff( final String geffPath, final double scale )
			throws IOException
	{
		final GeffGtLoader.GtGraph gt = GeffGtLoader.load( geffPath );
		final LineageConstraintProfile p = learn( gt, scale );
		p.sourcePath = geffPath;
		return p;
	}

	/** Learns a profile from an already-loaded GEFF ground-truth graph. */
	public static LineageConstraintProfile learn( final GeffGtLoader.GtGraph gt, final double scale )
	{
		// Convert with scale folded into the coordinates, then learn at scale=1 (identical statistics).
		return learn( toLineageGraphData( gt, scale ), 1.0 );
	}

	/**
	 * Converts a GEFF graph into a format-neutral {@link LineageGraphData}, multiplying coordinates and
	 * radii by {@code scale} so the result is in Mastodon world units.
	 */
	public static LineageGraphData toLineageGraphData( final GeffGtLoader.GtGraph gt, final double scale )
	{
		final List< LineageGraphData.Node > nodes = new ArrayList<>( gt.nodes.size() );
		for ( final GeffNode n : gt.nodes )
			nodes.add( new LineageGraphData.Node( n.getId(), n.getT(), n.getX() * scale, n.getY() * scale,
					n.getZ() * scale, n.getRadius() * scale ) );
		final List< LineageGraphData.Edge > edges = new ArrayList<>( gt.edges.size() );
		for ( final GeffEdge e : gt.edges )
			edges.add( new LineageGraphData.Edge( e.getSourceNodeId(), e.getTargetNodeId() ) );
		return new LineageGraphData( nodes, edges, "", gt.geffVersion );
	}

	/** Learns a profile from a format-neutral lineage graph. */
	public static LineageConstraintProfile learn( final LineageGraphData data, final double scale )
	{
		final LineageConstraintProfile p = new LineageConstraintProfile();
		p.sourcePath = data.source;
		p.geffVersion = data.version;
		p.scale = scale;

		// ---- index nodes ----
		final Map< Integer, double[] > pos = new HashMap<>();
		final Map< Integer, Integer > tp = new HashMap<>();
		final Map< Integer, Double > radius = new HashMap<>();
		final List< Double > radii = new ArrayList<>();
		int tMin = Integer.MAX_VALUE, tMax = Integer.MIN_VALUE;
		for ( final LineageGraphData.Node n : data.nodes )
		{
			pos.put( n.id, new double[] { n.x * scale, n.y * scale, n.z * scale } );
			tp.put( n.id, n.t );
			final double r = n.radius * scale;
			radius.put( n.id, r );
			radii.add( r );
			tMin = Math.min( tMin, n.t );
			tMax = Math.max( tMax, n.t );
		}
		p.numNodes = data.nodes.size();
		p.numEdges = data.edges.size();
		p.tMin = tMin == Integer.MAX_VALUE ? 0 : tMin;
		p.tMax = tMax == Integer.MIN_VALUE ? 0 : tMax;

		// ---- adjacency ----
		final Map< Integer, List< Integer > > children = new HashMap<>();
		final Map< Integer, List< Integer > > parents = new HashMap<>();
		for ( final LineageGraphData.Edge e : data.edges )
		{
			children.computeIfAbsent( e.sourceId, k -> new ArrayList<>() ).add( e.targetId );
			parents.computeIfAbsent( e.targetId, k -> new ArrayList<>() ).add( e.sourceId );
		}

		// ---- structural invariants + division/founder counts ----
		int divisions = 0, maxOut = 0, inViol = 0, outViol = 0, appear = 0, terminate = 0, founders = 0;
		for ( final Integer id : pos.keySet() )
		{
			final int out = children.getOrDefault( id, java.util.Collections.emptyList() ).size();
			final int in = parents.getOrDefault( id, java.util.Collections.emptyList() ).size();
			maxOut = Math.max( maxOut, out );
			if ( out >= 2 )
				divisions++;
			if ( out > 2 )
				outViol++;
			if ( in == 0 )
			{
				founders++;
				if ( tp.get( id ) > p.tMin )
					appear++;
			}
			if ( in > 1 )
				inViol++;
			if ( out == 0 && tp.get( id ) < p.tMax )
				terminate++;
		}
		p.numDivisions = divisions;
		p.maxOutDegreeObserved = maxOut;
		p.inDegreeViolations = inViol;
		p.outDegreeViolations = outViol;
		p.midMovieAppearances = appear;
		p.midMovieTerminations = terminate;
		p.numFounders = founders;
		// How often cells divide — the key "divisions are rare" signal for suppressing false branches.
		p.divisionRate = p.numNodes > 0 ? ( double ) divisions / p.numNodes : Double.NaN;
		p.divisionsPerFounder = founders > 0 ? ( double ) divisions / founders : Double.NaN;

		// Cells present per timepoint — the expected detection density (median) and an over-detection
		// ceiling (max). Lets the pipeline recognise when detection produces far too many spots.
		final Map< Integer, Integer > countPerTp = new HashMap<>();
		for ( final Integer t : tp.values() )
			countPerTp.merge( t, 1, Integer::sum );
		final List< Double > perTpCounts = new ArrayList<>( countPerTp.size() );
		for ( final int c : countPerTp.values() )
			perTpCounts.add( ( double ) c );
		p.spotsPerTimepointP50 = percentile( perTpCounts, 0.50 );
		p.spotsPerTimepointMax = max( perTpCounts );

		// ---- per-frame displacement of continuing cells (out-degree-1 edges) ----
		final List< Double > disp = new ArrayList<>();
		for ( final Map.Entry< Integer, List< Integer > > en : children.entrySet() )
		{
			if ( en.getValue().size() != 1 )
				continue; // skip divisions; displacement is the movement of a single continuing cell
			final int src = en.getKey();
			final int tgt = en.getValue().get( 0 );
			final double[] a = pos.get( src );
			final double[] b = pos.get( tgt );
			if ( a == null || b == null )
				continue;
			// Normalise by the frame gap so a temporally sparse annotation (a link spanning several
			// frames) still yields a per-frame displacement rather than an inflated single-step value.
			final int dt = Math.max( 1, tp.get( tgt ) - tp.get( src ) );
			disp.add( dist( a, b ) / dt );
		}
		p.displacementP50 = percentile( disp, 0.50 );
		p.displacementP95 = percentile( disp, 0.95 );
		p.displacementP99 = percentile( disp, 0.99 );
		p.displacementMax = max( disp );

		// ---- division interval: frames to the nearest ancestor division along the lineage ----
		final List< Double > divInterval = new ArrayList<>();
		for ( final Integer id : pos.keySet() )
		{
			if ( children.getOrDefault( id, java.util.Collections.emptyList() ).size() < 2 )
				continue;
			// walk up unique-parent chain to the previous dividing ancestor
			Integer cur = firstParent( parents, id );
			int guard = 0;
			while ( cur != null && guard++ < p.numNodes )
			{
				if ( children.getOrDefault( cur, java.util.Collections.emptyList() ).size() >= 2 )
				{
					divInterval.add( ( double ) ( tp.get( id ) - tp.get( cur ) ) );
					break;
				}
				cur = firstParent( parents, cur );
			}
		}
		p.divisionIntervalMin = min( divInterval );
		p.divisionIntervalP05 = percentile( divInterval, 0.05 );
		p.divisionIntervalP50 = percentile( divInterval, 0.50 );

		// ---- radius ----
		p.radiusP01 = percentile( radii, 0.01 );
		p.radiusP50 = percentile( radii, 0.50 );
		p.radiusP99 = percentile( radii, 0.99 );

		// ---- daughter split geometry (exactly-2-daughter divisions) ----
		final List< Double > splitDist = new ArrayList<>();
		final List< Double > sizeRatio = new ArrayList<>();
		for ( final Map.Entry< Integer, List< Integer > > en : children.entrySet() )
		{
			if ( en.getValue().size() != 2 )
				continue;
			final Integer d1 = en.getValue().get( 0 ), d2 = en.getValue().get( 1 );
			final double[] p1 = pos.get( d1 ), p2 = pos.get( d2 );
			if ( p1 != null && p2 != null )
				splitDist.add( dist( p1, p2 ) );
			final Double r1 = radius.get( d1 ), r2 = radius.get( d2 );
			if ( r1 != null && r2 != null && r1 > 0 && r2 > 0 )
				sizeRatio.add( Math.max( r1, r2 ) / Math.min( r1, r2 ) );
		}
		p.daughterSplitDistanceP50 = percentile( splitDist, 0.50 );
		p.daughterSplitDistanceP95 = percentile( splitDist, 0.95 );
		p.daughterSizeRatioP50 = percentile( sizeRatio, 0.50 );
		p.daughterSizeRatioP95 = percentile( sizeRatio, 0.95 );

		// ---- track segment length (frames spanned by maximal non-branching chains) ----
		final List< Double > segLen = new ArrayList<>();
		for ( final Integer id : pos.keySet() )
		{
			// a segment starts at a birth: no parent, or the (single) parent divided
			final Integer par = firstParent( parents, id );
			final boolean birth = par == null
					|| children.getOrDefault( par, java.util.Collections.emptyList() ).size() >= 2;
			if ( !birth )
				continue;
			int count = 1;
			Integer cur = id;
			int guard = 0;
			while ( guard++ < p.numNodes )
			{
				final List< Integer > ch = children.getOrDefault( cur, java.util.Collections.emptyList() );
				if ( ch.size() != 1 )
					break; // ends at a division or a leaf
				cur = ch.get( 0 );
				count++;
			}
			segLen.add( ( double ) count );
		}
		p.trackSegmentLengthP05 = percentile( segLen, 0.05 );
		p.trackSegmentLengthP50 = percentile( segLen, 0.50 );
		p.trackSegmentLengthP95 = percentile( segLen, 0.95 );

		return p;
	}

	// -----------------------------------------------------------------------

	private static Integer firstParent( final Map< Integer, List< Integer > > parents, final int id )
	{
		final List< Integer > ps = parents.get( id );
		return ps == null || ps.isEmpty() ? null : ps.get( 0 );
	}

	private static double dist( final double[] a, final double[] b )
	{
		double s = 0;
		for ( int d = 0; d < 3; d++ )
		{
			final double x = a[ d ] - b[ d ];
			s += x * x;
		}
		return Math.sqrt( s );
	}

	/** Nearest-rank percentile (p in [0,1]); NaN for an empty list. */
	private static double percentile( final List< Double > xs, final double q )
	{
		if ( xs.isEmpty() )
			return Double.NaN;
		final List< Double > s = new ArrayList<>( xs );
		s.sort( Comparator.naturalOrder() );
		int idx = ( int ) Math.round( q * ( s.size() - 1 ) );
		if ( idx < 0 )
			idx = 0;
		if ( idx >= s.size() )
			idx = s.size() - 1;
		return s.get( idx );
	}

	private static double min( final List< Double > xs )
	{
		double m = Double.NaN;
		for ( final double x : xs )
			m = Double.isNaN( m ) ? x : Math.min( m, x );
		return m;
	}

	private static double max( final List< Double > xs )
	{
		double m = Double.NaN;
		for ( final double x : xs )
			m = Double.isNaN( m ) ? x : Math.max( m, x );
		return m;
	}
}
