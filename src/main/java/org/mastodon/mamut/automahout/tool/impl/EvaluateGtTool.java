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
package org.mastodon.mamut.automahout.tool.impl;

import org.mastodon.mamut.automahout.tool.ToolRole;
import org.scijava.plugin.Plugin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mastodon.geff.GeffEdge;
import org.mastodon.geff.GeffNode;
import org.mastodon.mamut.automahout.eval.GeffGtLoader;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Evaluates the current Mastodon tracking result against a ground-truth lineage graph loaded from a
 * {@code .geff} store, and reports standard accuracy metrics: detection (precision/recall/F1),
 * linking (edge precision/recall/F1), and cell division (precision/recall).
 *
 * <p>Ground-truth and predicted spots are matched per timepoint by greedy nearest-neighbour within
 * {@code match_radius} (world units). Linking and division metrics are computed on that matching, so
 * they measure lineage-graph correctness, not just counts. This turns the pipeline's self-consistency
 * proxies (continuity/completeness) into real accuracy for tuning and calibration.</p>
 *
 * <h3>Parameters</h3>
 * <ul>
 *   <li>{@code geff_path} (required) — path to the {@code .geff} ground-truth store.</li>
 *   <li>{@code match_radius} (optional) — matching distance in world units; {@code <=0} (default)
 *       uses the median GT node radius.</li>
 *   <li>{@code scale} (optional, default 1.0) — factor applied to GT coordinates if the GEFF store
 *       is in a different unit scale than the Mastodon spots (world units).</li>
 * </ul>
 */
@Plugin( type = AutomahoutTool.class )
public class EvaluateGtTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.ASSESS;
	}


	public static final String ID = "evaluate.gt";

	public static final String PARAM_GEFF_PATH = "geff_path";

	public static final String PARAM_MATCH_RADIUS = "match_radius";

	public static final String PARAM_SCALE = "scale";

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Evaluate the current tracking result against a ground-truth lineage graph in a .geff "
				+ "store. Reports detection (P/R/F1), linking (P/R/F1) and division (P/R) accuracy by "
				+ "matching GT and predicted spots per timepoint. Params: geff_path (required), "
				+ "match_radius (world units; <=0 = median GT radius), scale (GT coordinate scale, "
				+ "default 1.0).";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final Object pathObj = params.get( PARAM_GEFF_PATH );
		if ( pathObj == null || String.valueOf( pathObj ).trim().isEmpty() )
			return "Error: '" + PARAM_GEFF_PATH + "' (path to the .geff ground-truth store) is required.";
		final String geffPath = String.valueOf( pathObj ).trim();
		final double scale = params.containsKey( PARAM_SCALE )
				? ( ( Number ) params.get( PARAM_SCALE ) ).doubleValue() : 1.0;
		double matchRadius = params.containsKey( PARAM_MATCH_RADIUS )
				? ( ( Number ) params.get( PARAM_MATCH_RADIUS ) ).doubleValue() : 0.0;

		// ---- load GT ----
		final GeffGtLoader.GtGraph gt;
		try
		{
			gt = GeffGtLoader.load( geffPath );
		}
		catch ( final Exception e )
		{
			return "Error: " + e.getMessage();
		}

		// GT node structures: id -> (t, pos, radius); grouped by timepoint; out-degree; edge set.
		final Map< Integer, double[] > gtPos = new HashMap<>();
		final Map< Integer, Integer > gtTp = new HashMap<>();
		final Map< Integer, List< Integer > > gtByTp = new HashMap<>();
		final List< Double > gtRadii = new ArrayList<>();
		for ( final GeffNode n : gt.nodes )
		{
			gtPos.put( n.getId(), new double[] { n.getX() * scale, n.getY() * scale, n.getZ() * scale } );
			gtTp.put( n.getId(), n.getT() );
			gtByTp.computeIfAbsent( n.getT(), k -> new ArrayList<>() ).add( n.getId() );
			gtRadii.add( n.getRadius() * scale );
		}
		final Map< Integer, Integer > gtOutDeg = new HashMap<>();
		final Set< Long > gtEdges = new HashSet<>();
		for ( final GeffEdge e : gt.edges )
		{
			gtOutDeg.merge( e.getSourceNodeId(), 1, Integer::sum );
			gtEdges.add( edgeKey( e.getSourceNodeId(), e.getTargetNodeId() ) );
		}

		if ( matchRadius <= 0.0 )
			matchRadius = median( gtRadii );
		if ( matchRadius <= 0.0 )
			matchRadius = 5.0; // last-resort fallback

		// ---- gather predicted graph (read lock) ----
		final Map< Integer, double[] > predPos = new HashMap<>();
		final Map< Integer, Integer > predTp = new HashMap<>();
		final Map< Integer, List< Integer > > predByTp = new HashMap<>();
		final Map< Integer, Integer > predOutDeg = new HashMap<>();
		final Set< Long > predEdges = new HashSet<>();

		final ModelGraph graph = context.getGraph();
		final Spot tgtRef = graph.vertexRef();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				final int id = s.getInternalPoolIndex();
				final int t = s.getTimepoint();
				s.localize( pos );
				predPos.put( id, new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ] } );
				predTp.put( id, t );
				predByTp.computeIfAbsent( t, k -> new ArrayList<>() ).add( id );
				int out = 0;
				for ( final Link e : s.outgoingEdges() )
				{
					final int tgt = e.getTarget( tgtRef ).getInternalPoolIndex();
					predEdges.add( edgeKey( id, tgt ) );
					out++;
				}
				predOutDeg.put( id, out );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( tgtRef );
		}

		if ( predPos.isEmpty() )
			return "Error: no predicted spots — run tracking before evaluating.";

		// ---- per-timepoint greedy nearest matching GT <-> predicted ----
		final Map< Integer, Integer > gtToPred = new HashMap<>();
		final double radiusSq = matchRadius * matchRadius;
		final Set< Integer > allTps = new HashSet<>();
		allTps.addAll( gtByTp.keySet() );
		allTps.addAll( predByTp.keySet() );
		for ( final int t : allTps )
		{
			final List< Integer > gts = gtByTp.getOrDefault( t, java.util.Collections.emptyList() );
			final List< Integer > preds = predByTp.getOrDefault( t, java.util.Collections.emptyList() );
			if ( gts.isEmpty() || preds.isEmpty() )
				continue;
			// candidate pairs within radius, sorted by distance, greedy 1:1 assignment
			final List< double[] > pairs = new ArrayList<>(); // [distSq, gtId, predId]
			for ( final int g : gts )
			{
				final double[] gp = gtPos.get( g );
				for ( final int p : preds )
				{
					final double d = distSq( gp, predPos.get( p ) );
					if ( d <= radiusSq )
						pairs.add( new double[] { d, g, p } );
				}
			}
			pairs.sort( Comparator.comparingDouble( a -> a[ 0 ] ) );
			final Set< Integer > usedPred = new HashSet<>();
			final Set< Integer > usedGt = new HashSet<>();
			for ( final double[] pr : pairs )
			{
				final int g = ( int ) pr[ 1 ], p = ( int ) pr[ 2 ];
				if ( usedGt.contains( g ) || usedPred.contains( p ) )
					continue;
				gtToPred.put( g, p );
				usedGt.add( g );
				usedPred.add( p );
			}
		}

		// ---- detection metrics ----
		final int detTp = gtToPred.size();
		final int detFp = predPos.size() - detTp;
		final int detFn = gtPos.size() - detTp;

		// ---- linking metrics (on matched correspondence) ----
		int linkTp = 0;
		for ( final GeffEdge e : gt.edges )
		{
			final Integer ps = gtToPred.get( e.getSourceNodeId() );
			final Integer pt = gtToPred.get( e.getTargetNodeId() );
			if ( ps != null && pt != null && predEdges.contains( edgeKey( ps, pt ) ) )
				linkTp++;
		}
		final int linkFp = predEdges.size() - linkTp;
		final int linkFn = gtEdges.size() - linkTp;

		// ---- division metrics ----
		int gtDiv = 0, predDiv = 0, divTp = 0;
		for ( final Map.Entry< Integer, Integer > en : gtOutDeg.entrySet() )
		{
			if ( en.getValue() >= 2 )
			{
				gtDiv++;
				final Integer p = gtToPred.get( en.getKey() );
				if ( p != null && predOutDeg.getOrDefault( p, 0 ) >= 2 )
					divTp++;
			}
		}
		for ( final int out : predOutDeg.values() )
			if ( out >= 2 )
				predDiv++;

		// ---- report ----
		final StringBuilder sb = new StringBuilder();
		sb.append( String.format( "GT evaluation (geff=%s, version=%s, match_radius=%.2f, scale=%.3g):\n",
				geffPath, gt.geffVersion, matchRadius, scale ) );
		sb.append( String.format( "  GT: %d spots, %d links, %d divisions | Predicted: %d spots, %d links, %d divisions\n",
				gtPos.size(), gtEdges.size(), gtDiv, predPos.size(), predEdges.size(), predDiv ) );
		sb.append( String.format( "  DETECTION  P=%.3f R=%.3f F1=%.3f  (TP=%d FP=%d FN=%d)\n",
				prec( detTp, detFp ), rec( detTp, detFn ), f1( detTp, detFp, detFn ), detTp, detFp, detFn ) );
		sb.append( String.format( "  LINKING    P=%.3f R=%.3f F1=%.3f  (TP=%d FP=%d FN=%d)\n",
				prec( linkTp, linkFp ), rec( linkTp, linkFn ), f1( linkTp, linkFp, linkFn ), linkTp, linkFp, linkFn ) );
		sb.append( String.format( "  DIVISION   P=%.3f R=%.3f  (TP=%d, GT=%d, pred=%d)",
				predDiv > 0 ? ( double ) divTp / predDiv : 0.0,
				gtDiv > 0 ? ( double ) divTp / gtDiv : 0.0, divTp, gtDiv, predDiv ) );
		return sb.toString();
	}

	// -----------------------------------------------------------------------

	private static long edgeKey( final int src, final int tgt )
	{
		return ( ( ( long ) src ) << 32 ) | ( tgt & 0xffffffffL );
	}

	private static double distSq( final double[] a, final double[] b )
	{
		double s = 0;
		for ( int d = 0; d < 3; d++ )
		{
			final double x = a[ d ] - b[ d ];
			s += x * x;
		}
		return s;
	}

	private static double median( final List< Double > xs )
	{
		if ( xs.isEmpty() )
			return 0.0;
		final List< Double > s = new ArrayList<>( xs );
		s.sort( Comparator.naturalOrder() );
		return s.get( s.size() / 2 );
	}

	private static double prec( final int tp, final int fp )
	{
		return tp + fp > 0 ? ( double ) tp / ( tp + fp ) : 0.0;
	}

	private static double rec( final int tp, final int fn )
	{
		return tp + fn > 0 ? ( double ) tp / ( tp + fn ) : 0.0;
	}

	private static double f1( final int tp, final int fp, final int fn )
	{
		final double p = prec( tp, fp ), r = rec( tp, fn );
		return p + r > 0 ? 2 * p * r / ( p + r ) : 0.0;
	}
}
