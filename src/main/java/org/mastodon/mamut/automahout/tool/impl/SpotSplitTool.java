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
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.SpotLookupUtil;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Splits an over-merged {@link Spot} into multiple new spots.
 *
 * <p>The original spot is deleted and replaced by N new spots at the provided
 * positions. Incoming and outgoing edges are reconnected to the nearest new
 * spot by Euclidean distance.</p>
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_LABEL}</td><td>String</td>
 *       <td>Label of the spot to split (takes priority over position)</td></tr>
 *   <tr><td>{@value #PARAM_X}/{@value #PARAM_Y}/{@value #PARAM_Z}/{@value #PARAM_T}</td><td>double/int</td>
 *       <td>Position + timepoint for nearest-neighbour lookup</td></tr>
 *   <tr><td>{@value #PARAM_POSITIONS}</td><td>String</td>
 *       <td>JSON array of new spot positions: {@code "[[x1,y1,z1],[x2,y2,z2],...]"} (required)</td></tr>
 *   <tr><td>{@value #PARAM_RADIUS}</td><td>double</td>
 *       <td>Radius for new spots (default: bounding sphere radius of the original)</td></tr>
 *   <tr><td>{@value #PARAM_SEARCH_RADIUS}</td><td>double</td>
 *       <td>Max NN search radius for position-based lookup (default 50.0)</td></tr>
 * </table>
 */
@Plugin( type = AutomahoutTool.class )
public class SpotSplitTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.EDIT;
	}


	public static final String ID = "spot.split";

	public static final String PARAM_LABEL = "label";

	public static final String PARAM_X = "x";

	public static final String PARAM_Y = "y";

	public static final String PARAM_Z = "z";

	public static final String PARAM_T = "t";

	public static final String PARAM_POSITIONS = "positions";

	public static final String PARAM_RADIUS = "radius";

	public static final String PARAM_SEARCH_RADIUS = "search_radius";

	private static final double DEFAULT_SEARCH_RADIUS = 50.0;

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Split an over-merged spot into multiple spots. "
				+ "Identify the source by 'label' or 'x/y/z/t'. "
				+ "Required: 'positions' as JSON array e.g. \"[[x1,y1,z1],[x2,y2,z2]]\". "
				+ "Optional: 'radius' (default: original bounding sphere radius), "
				+ "'search_radius' (default 50.0).";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final String positionsJson = ( String ) params.get( PARAM_POSITIONS );
		if ( positionsJson == null )
			throw new IllegalArgumentException( "SpotSplitTool requires 'positions' parameter." );

		final JSONArray positionsArray = new JSONArray( positionsJson );
		if ( positionsArray.length() < 2 )
			throw new IllegalArgumentException( "SpotSplitTool requires at least 2 positions." );

		final double searchRadius = params.containsKey( PARAM_SEARCH_RADIUS )
				? ( ( Number ) params.get( PARAM_SEARCH_RADIUS ) ).doubleValue()
				: DEFAULT_SEARCH_RADIUS;

		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();

		final Spot spotRef = graph.vertexRef();
		final Spot tempRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();

		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				final Spot target = SpotLookupUtil.resolve(
						graph, params,
						PARAM_LABEL, PARAM_X, PARAM_Y, PARAM_Z, PARAM_T,
						searchRadius, spotRef );

				if ( target == null )
					return "spot not found";

				final int timepoint = target.getTimepoint();
				final double originalRadius = Math.sqrt( target.getBoundingSphereRadiusSquared() );
				final double newRadius = params.containsKey( PARAM_RADIUS )
						? ( ( Number ) params.get( PARAM_RADIUS ) ).doubleValue()
						: originalRadius;

				// Collect predecessor/successor pool indices before deletion
				final List< double[] > predecessorPos = new ArrayList<>();
				final List< double[] > successorPos = new ArrayList<>();
				final List< Integer > predecessorIdx = new ArrayList<>();
				final List< Integer > successorIdx = new ArrayList<>();

				for ( final Link inEdge : target.incomingEdges() )
				{
					inEdge.getSource( tempRef );
					final double[] p = new double[ 3 ];
					tempRef.localize( p );
					predecessorPos.add( p );
					predecessorIdx.add( tempRef.getInternalPoolIndex() );
				}
				for ( final Link outEdge : target.outgoingEdges() )
				{
					outEdge.getTarget( tempRef );
					final double[] p = new double[ 3 ];
					tempRef.localize( p );
					successorPos.add( p );
					successorIdx.add( tempRef.getInternalPoolIndex() );
				}

				GraphEdits.removeSpot( context, graph, target, GraphEdits.Reason.USER );

				// Add new spots and record their refs + positions
				final List< Spot > newRefs = new ArrayList<>();
				final List< double[] > newPositions = new ArrayList<>();
				for ( int i = 0; i < positionsArray.length(); i++ )
				{
					final JSONArray p = positionsArray.getJSONArray( i );
					final double[] pos = {
							p.getDouble( 0 ),
							p.getDouble( 1 ),
							p.length() > 2 ? p.getDouble( 2 ) : 0.0
					};
					final Spot newRef = graph.vertexRef();
					GraphEdits.addSpot( context, graph, newRef, timepoint, pos, newRadius, GraphEdits.Reason.USER );
					newRefs.add( newRef );
					newPositions.add( pos );
				}

				// Reconnect edges to nearest new spot
				for ( int pi = 0; pi < predecessorIdx.size(); pi++ )
				{
					final int predIdx = predecessorIdx.get( pi );
					final double[] predPos = predecessorPos.get( pi );
					final Spot nearest = nearestNewSpot( graph, newRefs, newPositions, predPos, tempRef );
					// find predecessor vertex by pool index
					final Spot predRef = graph.vertexRef();
					try
					{
						for ( final Spot s : graph.vertices() )
						{
							if ( s.getInternalPoolIndex() == predIdx )
							{
								predRef.refTo( s );
								GraphEdits.addLink( context, graph, predRef, nearest, edgeRef, GraphEdits.Reason.USER );
								break;
							}
						}
					}
					finally
					{
						graph.releaseRef( predRef );
					}
				}

				for ( int si = 0; si < successorIdx.size(); si++ )
				{
					final int succIdx = successorIdx.get( si );
					final double[] succPos = successorPos.get( si );
					final Spot nearest = nearestNewSpot( graph, newRefs, newPositions, succPos, tempRef );
					final Spot succRef = graph.vertexRef();
					try
					{
						for ( final Spot s : graph.vertices() )
						{
							if ( s.getInternalPoolIndex() == succIdx )
							{
								succRef.refTo( s );
								GraphEdits.addLink( context, graph, nearest, succRef, edgeRef, GraphEdits.Reason.USER );
								break;
							}
						}
					}
					finally
					{
						graph.releaseRef( succRef );
					}
				}

				for ( final Spot r : newRefs )
					graph.releaseRef( r );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			model.setUndoPoint();
			context.notifyGraphChanged();
			return "spot split into " + positionsArray.length() + " new spots";
		}
		finally
		{
			graph.releaseRef( spotRef );
			graph.releaseRef( tempRef );
			graph.releaseRef( edgeRef );
		}
	}

	private static Spot nearestNewSpot( final ModelGraph graph,
			final List< Spot > refs, final List< double[] > positions,
			final double[] queryPos, final Spot resultRef )
	{
		double bestDistSq = Double.MAX_VALUE;
		int bestIdx = 0;
		for ( int i = 0; i < positions.size(); i++ )
		{
			final double[] p = positions.get( i );
			final double dx = p[ 0 ] - queryPos[ 0 ];
			final double dy = p[ 1 ] - queryPos[ 1 ];
			final double dz = p[ 2 ] - queryPos[ 2 ];
			final double distSq = dx * dx + dy * dy + dz * dz;
			if ( distSq < bestDistSq )
			{
				bestDistSq = distSq;
				bestIdx = i;
			}
		}
		resultRef.refTo( refs.get( bestIdx ) );
		return resultRef;
	}
}
