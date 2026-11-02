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

import org.json.JSONArray;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Adds a new spherical {@link Spot} to the Mastodon model.
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_X}</td><td>double</td><td>—</td>
 *       <td>X coordinate in world space (required)</td></tr>
 *   <tr><td>{@value #PARAM_Y}</td><td>double</td><td>—</td>
 *       <td>Y coordinate in world space (required)</td></tr>
 *   <tr><td>{@value #PARAM_Z}</td><td>double</td><td>0</td>
 *       <td>Z coordinate in world space</td></tr>
 *   <tr><td>{@value #PARAM_TIMEPOINT}</td><td>int</td><td>—</td>
 *       <td>Timepoint index (required)</td></tr>
 *   <tr><td>{@value #PARAM_RADIUS}</td><td>double</td><td>5.0</td>
 *       <td>Sphere radius in world units</td></tr>
 *   <tr><td>{@value #PARAM_LABEL}</td><td>String</td><td><em>auto</em></td>
 *       <td>Label assigned to the new spot; used later by {@code spot.edit} to find it</td></tr>
 * </table>
 *
 * <p>Graph modifications are performed under the graph write lock and an
 * undo point is recorded via {@link Model#setUndoPoint()}.</p>
 */
@Plugin( type = AutomahoutTool.class )
public class SpotAddTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.EDIT;
	}


	public static final String ID = "spot.add";

	/** X position in world coordinates (required). */
	public static final String PARAM_X = "x";

	/** Y position in world coordinates (required). */
	public static final String PARAM_Y = "y";

	/** Z position in world coordinates. Defaults to {@code 0}. */
	public static final String PARAM_Z = "z";

	/** Timepoint index at which to create the spot (required). */
	public static final String PARAM_TIMEPOINT = "t";

	/** Sphere radius in world units. Defaults to {@code 5.0}. */
	public static final String PARAM_RADIUS = "radius";

	/**
	 * Optional label to assign to the new spot.
	 * When set, the spot can be retrieved later by {@code spot.edit} using the
	 * same label string.
	 */
	public static final String PARAM_LABEL = "label";

	/**
	 * Optional covariance matrix as a JSON string {@code "[[c00,c01,c02],[c10,c11,c12],[c20,c21,c22]]"}.
	 * When present, creates an ellipsoid spot instead of a sphere.
	 */
	public static final String PARAM_COVARIANCE = "covariance";

	private static final double DEFAULT_RADIUS = 5.0;

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Add a new spot at world position (x, y, z) at the given timepoint. "
				+ "Required params: 'x', 'y', 't'. "
				+ "Optional: 'z' (default 0), 'radius' (default 5.0), 'label', "
				+ "'covariance' (JSON string \"[[c00,c01,c02],[c10,c11,c12],[c20,c21,c22]]\" for ellipsoid).";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final double x = ( ( Number ) params.get( PARAM_X ) ).doubleValue();
		final double y = ( ( Number ) params.get( PARAM_Y ) ).doubleValue();
		final double z = ( ( Number ) params.getOrDefault( PARAM_Z, 0.0 ) ).doubleValue();
		final int timepoint = ( ( Number ) params.get( PARAM_TIMEPOINT ) ).intValue();
		final double radius = ( ( Number ) params.getOrDefault( PARAM_RADIUS, DEFAULT_RADIUS ) ).doubleValue();
		final String label = ( String ) params.get( PARAM_LABEL ); // nullable
		final String covJson = ( String ) params.get( PARAM_COVARIANCE ); // nullable

		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();
		final double[] pos = { x, y, z };

		final Spot ref = graph.vertexRef();
		try
		{
			graph.getLock().writeLock().lock();
			try
			{
				if ( covJson != null )
				{
					final JSONArray covArray = new JSONArray( covJson );
					final double[][] cov = new double[ 3 ][ 3 ];
					for ( int row = 0; row < 3; row++ )
					{
						final JSONArray rowArray = covArray.getJSONArray( row );
						for ( int col = 0; col < 3; col++ )
							cov[ row ][ col ] = rowArray.getDouble( col );
					}
					GraphEdits.addSpot( context, graph, ref, timepoint, pos, cov, GraphEdits.Reason.USER );
				}
				else
				{
					GraphEdits.addSpot( context, graph, ref, timepoint, pos, radius, GraphEdits.Reason.USER );
				}
				if ( label != null )
					ref.setLabel( label );
			}
			finally
			{
				graph.getLock().writeLock().unlock();
			}
			model.setUndoPoint();
			context.notifyGraphChanged();
		}
		finally
		{
			graph.releaseRef( ref );
		}

		final String labelInfo = ( label != null ) ? " label='" + label + "'" : "";
		final String shapeInfo = ( covJson != null ) ? " (ellipsoid)" : String.format( " r=%.1f", radius );
		return String.format( "spot added at (%.1f, %.1f, %.1f) t=%d%s%s",
				x, y, z, timepoint, shapeInfo, labelInfo );
	}
}
