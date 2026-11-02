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
import java.util.Map;

import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.SpotLookupUtil;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Deletes a {@link Spot} from the Mastodon model.
 *
 * <p>The spot is identified by {@code label} or by nearest-neighbour search
 * using {@code x/y/z/t}. When {@code relink=true}, the spot's predecessor is
 * connected directly to its successor before deletion.</p>
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_LABEL}</td><td>String</td><td>—</td>
 *       <td>Label of the spot to delete (takes priority over position)</td></tr>
 *   <tr><td>{@value #PARAM_X}</td><td>double</td><td>—</td><td>X position</td></tr>
 *   <tr><td>{@value #PARAM_Y}</td><td>double</td><td>—</td><td>Y position</td></tr>
 *   <tr><td>{@value #PARAM_Z}</td><td>double</td><td>0</td><td>Z position</td></tr>
 *   <tr><td>{@value #PARAM_T}</td><td>int</td><td>—</td>
 *       <td>Timepoint (required when using position; optional hint for label lookup)</td></tr>
 *   <tr><td>{@value #PARAM_SEARCH_RADIUS}</td><td>double</td><td>50</td>
 *       <td>Max NN search radius for position-based lookup</td></tr>
 *   <tr><td>{@value #PARAM_RELINK}</td><td>boolean</td><td>false</td>
 *       <td>When true, connects the spot's predecessor directly to its successor</td></tr>
 * </table>
 */
@Plugin( type = AutomahoutTool.class )
public class SpotDeleteTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.EDIT;
	}


	public static final String ID = "spot.delete";

	public static final String PARAM_LABEL = "label";

	public static final String PARAM_X = "x";

	public static final String PARAM_Y = "y";

	public static final String PARAM_Z = "z";

	public static final String PARAM_T = "t";

	public static final String PARAM_SEARCH_RADIUS = "search_radius";

	public static final String PARAM_RELINK = "relink";

	private static final double DEFAULT_SEARCH_RADIUS = 50.0;

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Delete a false-positive spot identified by 'label' or nearest position ('x','y','z','t'). "
				+ "Optional 'relink' (boolean, default false): bridges predecessor to successor. "
				+ "Optional 'search_radius' (default 50.0) for position-based lookup.";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final boolean relink = parseBooleanParam( params, PARAM_RELINK, false );
		final double searchRadius = params.containsKey( PARAM_SEARCH_RADIUS )
				? ( ( Number ) params.get( PARAM_SEARCH_RADIUS ) ).doubleValue()
				: DEFAULT_SEARCH_RADIUS;

		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();

		final Spot spotRef = graph.vertexRef();
		final Spot srcRef = graph.vertexRef();
		final Spot tgtRef = graph.vertexRef();
		final Spot tempRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();

		String label = "?";
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

				label = target.getLabel();

				Spot predecessor = null;
				Spot successor = null;

				if ( relink )
				{
					if ( !target.incomingEdges().isEmpty() )
					{
						target.incomingEdges().iterator().next().getSource( tempRef );
						srcRef.refTo( tempRef );
						predecessor = srcRef;
					}
					if ( !target.outgoingEdges().isEmpty() )
					{
						target.outgoingEdges().iterator().next().getTarget( tempRef );
						tgtRef.refTo( tempRef );
						successor = tgtRef;
					}
				}

				GraphEdits.removeSpot( context, graph, target, GraphEdits.Reason.USER );

				if ( relink && predecessor != null && successor != null )
					GraphEdits.addLink( context, graph, predecessor, successor, edgeRef, GraphEdits.Reason.USER );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}

			model.setUndoPoint();
			context.notifyGraphChanged();
			return "spot '" + label + "' deleted" + ( relink ? " (predecessor relinked to successor)" : "" );
		}
		finally
		{
			graph.releaseRef( spotRef );
			graph.releaseRef( srcRef );
			graph.releaseRef( tgtRef );
			graph.releaseRef( tempRef );
			graph.releaseRef( edgeRef );
		}
	}

	private static boolean parseBooleanParam( final Map< String, Object > params,
			final String key, final boolean defaultValue )
	{
		final Object val = params.get( key );
		if ( val == null ) return defaultValue;
		if ( val instanceof Boolean ) return ( Boolean ) val;
		return Boolean.parseBoolean( val.toString() );
	}
}
