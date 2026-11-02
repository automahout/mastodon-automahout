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
 * Adds or deletes a {@link Link} between two spots.
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_ACTION}</td><td>String</td>
 *       <td>{@code "add"} or {@code "delete"} (required)</td></tr>
 *   <tr><td>{@value #PARAM_SOURCE_LABEL}</td><td>String</td>
 *       <td>Label of the source (earlier timepoint) spot</td></tr>
 *   <tr><td>{@value #PARAM_SX}/{@value #PARAM_SY}/{@value #PARAM_SZ}/{@value #PARAM_ST}</td><td>double/int</td>
 *       <td>Position + timepoint for source, used when source_label is absent</td></tr>
 *   <tr><td>{@value #PARAM_TARGET_LABEL}</td><td>String</td>
 *       <td>Label of the target (later timepoint) spot</td></tr>
 *   <tr><td>{@value #PARAM_TX}/{@value #PARAM_TY}/{@value #PARAM_TZ}/{@value #PARAM_TT}</td><td>double/int</td>
 *       <td>Position + timepoint for target, used when target_label is absent</td></tr>
 *   <tr><td>{@value #PARAM_SEARCH_RADIUS}</td><td>double</td>
 *       <td>Max NN search radius for position lookups (default 50.0)</td></tr>
 * </table>
 */
@Plugin( type = AutomahoutTool.class )
public class LinkEditTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.EDIT;
	}


	public static final String ID = "link.edit";

	public static final String PARAM_ACTION = "action";

	public static final String PARAM_SOURCE_LABEL = "source_label";

	public static final String PARAM_SX = "sx";

	public static final String PARAM_SY = "sy";

	public static final String PARAM_SZ = "sz";

	public static final String PARAM_ST = "st";

	public static final String PARAM_TARGET_LABEL = "target_label";

	public static final String PARAM_TX = "tx";

	public static final String PARAM_TY = "ty";

	public static final String PARAM_TZ = "tz";

	public static final String PARAM_TT = "tt";

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
		return "Add or delete a link between two spots. "
				+ "Required: 'action' ('add' or 'delete'). "
				+ "Specify source by 'source_label' or 'sx/sy/sz/st'. "
				+ "Specify target by 'target_label' or 'tx/ty/tz/tt'. "
				+ "Optional 'search_radius' (default 50.0).";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final String action = ( String ) params.get( PARAM_ACTION );
		if ( action == null )
			throw new IllegalArgumentException( "LinkEditTool requires 'action' ('add' or 'delete')." );

		final double searchRadius = params.containsKey( PARAM_SEARCH_RADIUS )
				? ( ( Number ) params.get( PARAM_SEARCH_RADIUS ) ).doubleValue()
				: DEFAULT_SEARCH_RADIUS;

		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();

		final Spot srcRef = graph.vertexRef();
		final Spot tgtRef = graph.vertexRef();
		final Spot tempRef = graph.vertexRef();
		final Link edgeRef = graph.edgeRef();

		// Capture result and whether the graph was actually modified.
		// setUndoPoint / notifyGraphChanged must be called AFTER releasing
		// the write lock, so we cannot return directly from inside the lock.
		String result;
		boolean changed;
		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				final Spot source = SpotLookupUtil.resolve(
						graph, params,
						PARAM_SOURCE_LABEL, PARAM_SX, PARAM_SY, PARAM_SZ, PARAM_ST,
						searchRadius, srcRef );
				if ( source == null )
				{
					result = "source spot not found";
					changed = false;
				}
				else
				{
					final Spot target = SpotLookupUtil.resolve(
							graph, params,
							PARAM_TARGET_LABEL, PARAM_TX, PARAM_TY, PARAM_TZ, PARAM_TT,
							searchRadius, tgtRef );
					if ( target == null )
					{
						result = "target spot not found";
						changed = false;
					}
					else
					{
						switch ( action )
						{
						case "add":
							result = addLink( context, graph, source, target, tempRef, edgeRef );
							changed = result.startsWith( "link added" );
							break;
						case "delete":
							result = deleteLink( context, graph, source, target, tempRef );
							changed = result.startsWith( "link deleted" );
							break;
						default:
							throw new IllegalArgumentException(
									"Unknown action: '" + action + "'. Use 'add' or 'delete'." );
						}
					}
				}
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
		}
		finally
		{
			graph.releaseRef( srcRef );
			graph.releaseRef( tgtRef );
			graph.releaseRef( tempRef );
			graph.releaseRef( edgeRef );
		}

		if ( changed )
		{
			model.setUndoPoint();
			context.notifyGraphChanged();
		}
		return result;
	}

	private static String addLink( final ToolContext context, final ModelGraph graph,
			final Spot source, final Spot target, final Spot tempRef, final Link edgeRef )
	{
		for ( final Link edge : source.outgoingEdges() )
		{
			if ( edge.getTarget( tempRef ).getInternalPoolIndex() == target.getInternalPoolIndex() )
				return "link already exists between '"
						+ source.getLabel() + "' and '" + target.getLabel() + "'";
		}
		GraphEdits.addLink( context, graph, source, target, edgeRef, GraphEdits.Reason.USER );
		return "link added: '" + source.getLabel() + "' → '" + target.getLabel() + "'";
	}

	private static String deleteLink( final ToolContext context, final ModelGraph graph,
			final Spot source, final Spot target, final Spot tempRef )
	{
		for ( final Link edge : source.outgoingEdges() )
		{
			if ( edge.getTarget( tempRef ).getInternalPoolIndex() == target.getInternalPoolIndex() )
			{
				GraphEdits.removeLink( context, graph, edge, GraphEdits.Reason.USER );
				return "link deleted: '" + source.getLabel() + "' → '" + target.getLabel() + "'";
			}
		}
		return "no link found from '" + source.getLabel() + "' to '" + target.getLabel() + "'";
	}
}
