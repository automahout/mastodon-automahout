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
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.elephant.ElephantIntegration;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.SpotLookupUtil;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Re-fits a poorly-shaped {@link Spot} using ELEPHANT spot detection on a
 * cropped region — equivalent to SAM-like 3D ellipsoid fitting via the
 * ELEPHANT deep learning model.
 *
 * <p>The original spot's neighbourhood is sent to ELEPHANT's
 * {@code /seg/predict} endpoint with a {@code crop_box} parameter. The
 * returned detection nearest to the original spot centre replaces the
 * original spot, inheriting its edge connections.</p>
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_LABEL}</td><td>String</td><td>—</td>
 *       <td>Label of the spot to refit (takes priority over position)</td></tr>
 *   <tr><td>{@value #PARAM_X}/{@value #PARAM_Y}/{@value #PARAM_Z}/{@value #PARAM_T}</td><td>double/int</td><td>—</td>
 *       <td>Position + timepoint for nearest-neighbour lookup</td></tr>
 *   <tr><td>{@value #PARAM_CROP_FACTOR}</td><td>double</td><td>3.0</td>
 *       <td>crop_box half-size = bounding sphere radius × crop_factor</td></tr>
 *   <tr><td>{@value #PARAM_SEARCH_RADIUS}</td><td>double</td><td>50.0</td>
 *       <td>Max NN search radius for position-based lookup</td></tr>
 * </table>
 */
@Plugin( type = AutomahoutTool.class )
public class SpotRefitTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.SHAPE;
	}


	public static final String ID = "spot.refit";

	public static final String PARAM_LABEL = "label";

	public static final String PARAM_X = "x";

	public static final String PARAM_Y = "y";

	public static final String PARAM_Z = "z";

	public static final String PARAM_T = "t";

	public static final String PARAM_CROP_FACTOR = "crop_factor";

	public static final String PARAM_SEARCH_RADIUS = "search_radius";

	private static final double DEFAULT_CROP_FACTOR = 3.0;

	private static final double DEFAULT_SEARCH_RADIUS = 50.0;

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Re-fit a poorly-shaped spot using ELEPHANT deep learning (SAM-like 3D ellipsoid fitting). "
				+ "Identify the spot by 'label' or 'x/y/z/t'. "
				+ "Optional 'crop_factor' (default 3.0): crop_box half-size = bounding_radius × factor. "
				+ "Requires ELEPHANT server to be running.";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final double cropFactor = params.containsKey( PARAM_CROP_FACTOR )
				? ( ( Number ) params.get( PARAM_CROP_FACTOR ) ).doubleValue()
				: DEFAULT_CROP_FACTOR;
		final double searchRadius = params.containsKey( PARAM_SEARCH_RADIUS )
				? ( ( Number ) params.get( PARAM_SEARCH_RADIUS ) ).doubleValue()
				: DEFAULT_SEARCH_RADIUS;

		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();

		// Phase 1: read spot info
		final double[] originalPos = new double[ 3 ];
		final int[] timepointHolder = new int[ 1 ];
		final double[] cropBox = new double[ 6 ];
		final List< Integer > predecessorIndices = new ArrayList<>();
		final List< Integer > successorIndices = new ArrayList<>();

		final Spot spotRef = graph.vertexRef();
		final Spot tempRef = graph.vertexRef();

		try
		{
			graph.getLock().readLock().lock();
			try
			{
				final Spot target = SpotLookupUtil.resolve(
						graph, params,
						PARAM_LABEL, PARAM_X, PARAM_Y, PARAM_Z, PARAM_T,
						searchRadius, spotRef );

				if ( target == null )
					return "spot not found";

				target.localize( originalPos );
				timepointHolder[ 0 ] = target.getTimepoint();
				final double r = Math.sqrt( target.getBoundingSphereRadiusSquared() ) * cropFactor;
				cropBox[ 0 ] = originalPos[ 0 ] - r;
				cropBox[ 1 ] = originalPos[ 1 ] - r;
				cropBox[ 2 ] = originalPos[ 2 ] - r;
				cropBox[ 3 ] = originalPos[ 0 ] + r;
				cropBox[ 4 ] = originalPos[ 1 ] + r;
				cropBox[ 5 ] = originalPos[ 2 ] + r;

				for ( final Link inEdge : target.incomingEdges() )
					predecessorIndices.add( inEdge.getSource( tempRef ).getInternalPoolIndex() );
				for ( final Link outEdge : target.outgoingEdges() )
					successorIndices.add( outEdge.getTarget( tempRef ).getInternalPoolIndex() );
			}
			finally
			{
				graph.getLock().readLock().unlock();
			}
		}
		finally
		{
			graph.releaseRef( spotRef );
			graph.releaseRef( tempRef );
		}

		// Phase 2: call ELEPHANT (no lock held)
		final ElephantIntegration elephant = new ElephantIntegration( context );
		if ( !elephant.init() )
			return "Failed to initialize ELEPHANT. Is the server running?";

		final List< JSONObject > candidates =
				elephant.predictSpotsInRegion( timepointHolder[ 0 ], cropBox );
		if ( candidates == null )
			return "ELEPHANT prediction failed";
		if ( candidates.isEmpty() )
			return "ELEPHANT returned no spots in the crop region";

		// Pick the candidate nearest to the original position
		final JSONObject best = pickNearest( candidates, originalPos );

		// Phase 3: replace spot under write lock
		final Spot newSpotRef = graph.vertexRef();
		final Spot predRef = graph.vertexRef();
		final Spot succRef = graph.vertexRef();
		final Spot lookupRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();

		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				// Re-find the original spot by position (it may have been modified by the time
				// we get the write lock, but for interactive single-user use this is acceptable)
				final Spot target2 = SpotLookupUtil.findNearest(
						graph, originalPos, timepointHolder[ 0 ], searchRadius, lookupRef );
				if ( target2 != null )
					GraphEdits.removeSpot( context, graph, target2, GraphEdits.Reason.USER );

				// Parse new spot from ELEPHANT response
				final JSONArray posArray = best.getJSONArray( "pos" );
				final double[] newPos = {
						posArray.getDouble( 0 ),
						posArray.getDouble( 1 ),
						posArray.getDouble( 2 )
				};
				final JSONArray covArray = best.getJSONArray( "covariance" );
				final double[][] cov = new double[ 3 ][ 3 ];
				for ( int row = 0; row < 3; row++ )
					for ( int col = 0; col < 3; col++ )
						cov[ row ][ col ] = covArray.getDouble( row * 3 + col );

				GraphEdits.addSpot( context, graph, newSpotRef, timepointHolder[ 0 ], newPos, cov, GraphEdits.Reason.USER );

				// Reconnect edges
				for ( final int predIdx : predecessorIndices )
				{
					if ( findByPoolIndex( graph, predIdx, predRef ) )
						GraphEdits.addLink( context, graph, predRef, newSpotRef, edgeRef, GraphEdits.Reason.USER );
				}
				for ( final int succIdx : successorIndices )
				{
					if ( findByPoolIndex( graph, succIdx, succRef ) )
						GraphEdits.addLink( context, graph, newSpotRef, succRef, edgeRef, GraphEdits.Reason.USER );
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			model.setUndoPoint();
			context.notifyGraphChanged();
			return "spot refit with new ellipsoid shape";
		}
		finally
		{
			graph.releaseRef( newSpotRef );
			graph.releaseRef( predRef );
			graph.releaseRef( succRef );
			graph.releaseRef( lookupRef );
			graph.releaseRef( edgeRef );
		}
	}

	private static JSONObject pickNearest( final List< JSONObject > candidates,
			final double[] refPos )
	{
		JSONObject best = null;
		double bestDistSq = Double.MAX_VALUE;
		for ( final JSONObject candidate : candidates )
		{
			final JSONArray p = candidate.getJSONArray( "pos" );
			final double dx = p.getDouble( 0 ) - refPos[ 0 ];
			final double dy = p.getDouble( 1 ) - refPos[ 1 ];
			final double dz = p.getDouble( 2 ) - refPos[ 2 ];
			final double distSq = dx * dx + dy * dy + dz * dz;
			if ( distSq < bestDistSq )
			{
				bestDistSq = distSq;
				best = candidate;
			}
		}
		return best;
	}

	private static boolean findByPoolIndex( final ModelGraph graph, final int poolIndex,
			final Spot ref )
	{
		for ( final Spot s : graph.vertices() )
		{
			if ( s.getInternalPoolIndex() == poolIndex )
			{
				ref.refTo( s );
				return true;
			}
		}
		return false;
	}
}
