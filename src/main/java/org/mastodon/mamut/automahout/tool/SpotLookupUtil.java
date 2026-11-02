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
package org.mastodon.mamut.automahout.tool;

import java.util.Map;

import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Utility methods for locating {@link Spot} vertices by label or nearest position.
 *
 * <p>All methods perform O(n) scans over graph vertices and must be called
 * while the caller holds the graph lock (read or write). The {@code ref}
 * parameter must be allocated by the caller via {@code graph.vertexRef()} and
 * released after use.</p>
 */
public final class SpotLookupUtil
{

	private SpotLookupUtil() {}

	/**
	 * Finds a spot by its label.
	 *
	 * @param graph         the model graph
	 * @param label         label to search for
	 * @param timepointHint if &ge; 0, restricts the scan to that timepoint; -1 scans all
	 * @param ref           pre-allocated vertex ref to hold the result
	 * @return {@code ref} pointing to the found spot, or {@code null} if not found
	 */
	public static Spot findByLabel( final ModelGraph graph, final String label,
			final int timepointHint, final Spot ref )
	{
		for ( final Spot spot : graph.vertices() )
		{
			if ( timepointHint >= 0 && spot.getTimepoint() != timepointHint )
				continue;
			if ( label.equals( spot.getLabel() ) )
			{
				ref.refTo( spot );
				return ref;
			}
		}
		return null;
	}

	/**
	 * Finds the nearest spot to {@code pos} at {@code timepoint} within {@code maxRadius}.
	 * Uses O(n) linear scan — suitable for interactive correction tools.
	 *
	 * @param graph     the model graph
	 * @param pos       query position [x, y, z]
	 * @param timepoint timepoint to search
	 * @param maxRadius maximum search radius
	 * @param ref       pre-allocated vertex ref to hold the result
	 * @return {@code ref} pointing to the nearest spot, or {@code null} if none within radius
	 */
	public static Spot findNearest( final ModelGraph graph, final double[] pos,
			final int timepoint, final double maxRadius, final Spot ref )
	{
		final double maxDistSq = maxRadius * maxRadius;
		double bestDistSq = Double.MAX_VALUE;
		boolean found = false;

		for ( final Spot spot : graph.vertices() )
		{
			if ( spot.getTimepoint() != timepoint )
				continue;
			final double dx = spot.getDoublePosition( 0 ) - pos[ 0 ];
			final double dy = spot.getDoublePosition( 1 ) - pos[ 1 ];
			final double dz = spot.getDoublePosition( 2 ) - pos[ 2 ];
			final double distSq = dx * dx + dy * dy + dz * dz;
			if ( distSq < bestDistSq && distSq <= maxDistSq )
			{
				bestDistSq = distSq;
				ref.refTo( spot );
				found = true;
			}
		}
		return found ? ref : null;
	}

	/**
	 * Resolves a spot from a parameter map: uses {@code labelKey} if present, otherwise
	 * performs nearest-neighbour search using {@code xKey/yKey/zKey/tKey}.
	 *
	 * @param graph        model graph
	 * @param params       tool parameter map
	 * @param labelKey     param key for label string
	 * @param xKey         param key for x
	 * @param yKey         param key for y
	 * @param zKey         param key for z (default 0 if absent)
	 * @param tKey         param key for timepoint
	 * @param searchRadius max NN search radius when using position
	 * @param ref          pre-allocated vertex ref
	 * @return the resolved ref, or {@code null} if not found
	 */
	public static Spot resolve( final ModelGraph graph,
			final Map< String, Object > params,
			final String labelKey, final String xKey, final String yKey,
			final String zKey, final String tKey,
			final double searchRadius, final Spot ref )
	{
		if ( params.containsKey( labelKey ) )
		{
			final String label = ( String ) params.get( labelKey );
			final int tHint = params.containsKey( tKey )
					? ( ( Number ) params.get( tKey ) ).intValue() : -1;
			return findByLabel( graph, label, tHint, ref );
		}
		else
		{
			final double x = ( ( Number ) params.get( xKey ) ).doubleValue();
			final double y = ( ( Number ) params.get( yKey ) ).doubleValue();
			final double z = params.containsKey( zKey )
					? ( ( Number ) params.get( zKey ) ).doubleValue() : 0.0;
			final int t = ( ( Number ) params.get( tKey ) ).intValue();
			return findNearest( graph, new double[] { x, y, z }, t, searchRadius, ref );
		}
	}
}
