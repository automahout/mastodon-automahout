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

import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Edits an existing {@link Spot} identified by its label.
 *
 * <p>The spot is located by an O(n) label scan over all vertices. Provide the
 * optional {@link #PARAM_TIMEPOINT} hint to restrict the search to a single
 * timepoint and improve performance on large datasets.</p>
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_LABEL}</td><td>String</td><td>—</td>
 *       <td>Label of the spot to edit (required)</td></tr>
 *   <tr><td>{@value #PARAM_DX}</td><td>double</td><td>0</td>
 *       <td>Translation delta in X (world units)</td></tr>
 *   <tr><td>{@value #PARAM_DY}</td><td>double</td><td>0</td>
 *       <td>Translation delta in Y (world units)</td></tr>
 *   <tr><td>{@value #PARAM_DZ}</td><td>double</td><td>0</td>
 *       <td>Translation delta in Z (world units)</td></tr>
 *   <tr><td>{@value #PARAM_SCALE}</td><td>double</td><td>—</td>
 *       <td>Uniform scale factor applied to the covariance matrix
 *           (radius scales linearly with this factor)</td></tr>
 * </table>
 *
 * <p>At least one edit parameter ({@code dx}, {@code dy}, {@code dz}, or
 * {@code scale}) should be provided. If none are given, the call is a no-op.</p>
 *
 * <p>Graph modifications are performed under the graph write lock and an
 * undo point is recorded via {@link Model#setUndoPoint()}.</p>
 */
@Plugin( type = AutomahoutTool.class )
public class SpotEditTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.EDIT;
	}


	public static final String ID = "spot.edit";

	/** Label of the spot to edit (required). */
	public static final String PARAM_LABEL = "label";

	/** Translation delta along X in world units. */
	public static final String PARAM_DX = "dx";

	/** Translation delta along Y in world units. */
	public static final String PARAM_DY = "dy";

	/** Translation delta along Z in world units. */
	public static final String PARAM_DZ = "dz";

	/**
	 * Uniform scale factor for the covariance matrix.
	 * A value of {@code 2.0} doubles the effective radius; {@code 0.5} halves it.
	 */
	public static final String PARAM_SCALE = "scale";

	/**
	 * Optional timepoint hint to narrow the label search.
	 * When provided, only vertices at this timepoint are scanned.
	 */
	public static final String PARAM_TIMEPOINT = "t";

	/**
	 * Optional key into {@link ToolContext} shared data that holds an absolute
	 * world position ({@code double[3]}) to which the spot should be moved.
	 *
	 * <p>When this parameter is present its value is treated as the
	 * {@link ToolContext#getSharedData(String)} lookup key (e.g.
	 * {@link FindMaximaTool#RESULT_KEY}).  The retrieved {@code double[3]} is
	 * used as the new absolute world position of the spot, overriding any
	 * relative translation given by {@link #PARAM_DX}/{@link #PARAM_DY}/
	 * {@link #PARAM_DZ}.</p>
	 */
	public static final String PARAM_POSITION_FROM_CONTEXT = "positionFromContext";

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Edit an existing spot found by label. "
				+ "Move to an absolute world position stored in the context via 'positionFromContext' (key string), "
				+ "or translate by relative (dx, dy, dz) in world units. "
				+ "Optionally scale the covariance by 'scale'. "
				+ "Required param: 'label'. Optional hint: 't' (timepoint) to speed up label search.";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final String label = ( String ) params.get( PARAM_LABEL );
		if ( label == null )
			throw new IllegalArgumentException( "SpotEditTool requires the 'label' parameter." );

		final Model model = context.getModel();
		final ModelGraph graph = context.getGraph();
		final StringBuilder result = new StringBuilder();

		graph.getLock().writeLock().lock();
		try
		{
			// O(n) label scan — all vertices or filtered by timepoint if hint given.
			Spot target = null;
			for ( final Spot spot : graph.vertices() )
			{
				if ( label.equals( spot.getLabel() )
						&& timepointMatches( spot, params ) )
				{
					target = spot;
					break;
				}
			}

			if ( target == null )
			{
				return "spot not found: label='" + label + "'";
			}

			// Absolute repositioning from context takes priority over relative translation.
			if ( params.containsKey( PARAM_POSITION_FROM_CONTEXT ) )
			{
				final String contextKey = ( String ) params.get( PARAM_POSITION_FROM_CONTEXT );
				final double[] worldPos = ( double[] ) context.getSharedData( contextKey );
				if ( worldPos == null )
					return "spot '" + label + "': no position found in context for key '"
							+ contextKey + "'";
				applyAbsolutePosition( target, worldPos, result );
			}
			else
			{
				applyTranslation( target, params, result );
			}
			applyScale( target, params, result );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}

		model.setUndoPoint();
		return "spot '" + label + "' edited: " + result;
	}

	// -----------------------------------------------------------------------

	private static void applyAbsolutePosition(
			final Spot spot,
			final double[] worldPos,
			final StringBuilder log )
	{
		spot.setPosition( worldPos[ 0 ], 0 );
		spot.setPosition( worldPos[ 1 ], 1 );
		spot.setPosition( worldPos[ 2 ], 2 );
		log.append( String.format( "moved to (%.2f, %.2f, %.2f) ", worldPos[ 0 ], worldPos[ 1 ], worldPos[ 2 ] ) );
	}

	private static boolean timepointMatches( final Spot spot, final Map< String, Object > params )
	{
		if ( !params.containsKey( PARAM_TIMEPOINT ) )
			return true; // no hint — match any timepoint
		final int hint = ( ( Number ) params.get( PARAM_TIMEPOINT ) ).intValue();
		return spot.getTimepoint() == hint;
	}

	private static void applyTranslation(
			final Spot spot,
			final Map< String, Object > params,
			final StringBuilder log )
	{
		final boolean hasDx = params.containsKey( PARAM_DX );
		final boolean hasDy = params.containsKey( PARAM_DY );
		final boolean hasDz = params.containsKey( PARAM_DZ );

		if ( !hasDx && !hasDy && !hasDz )
			return;

		final double dx = hasDx ? ( ( Number ) params.get( PARAM_DX ) ).doubleValue() : 0.0;
		final double dy = hasDy ? ( ( Number ) params.get( PARAM_DY ) ).doubleValue() : 0.0;
		final double dz = hasDz ? ( ( Number ) params.get( PARAM_DZ ) ).doubleValue() : 0.0;

		spot.setPosition( spot.getDoublePosition( 0 ) + dx, 0 );
		spot.setPosition( spot.getDoublePosition( 1 ) + dy, 1 );
		spot.setPosition( spot.getDoublePosition( 2 ) + dz, 2 );

		log.append( String.format( "translated (%.2f, %.2f, %.2f) ", dx, dy, dz ) );
	}

	private static void applyScale(
			final Spot spot,
			final Map< String, Object > params,
			final StringBuilder log )
	{
		if ( !params.containsKey( PARAM_SCALE ) )
			return;

		final double scale = ( ( Number ) params.get( PARAM_SCALE ) ).doubleValue();
		final double scaleSq = scale * scale;

		final double[][] cov = new double[ 3 ][ 3 ];
		spot.getCovariance( cov );
		for ( int i = 0; i < 3; i++ )
			for ( int j = 0; j < 3; j++ )
				cov[ i ][ j ] *= scaleSq;
		spot.setCovariance( cov );

		log.append( String.format( "scaled x%.3f", scale ) );
	}
}
