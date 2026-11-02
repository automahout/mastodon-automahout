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
package org.mastodon.mamut.automahout.autocorrect;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import org.mastodon.graph.GraphListener;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.properties.DoublePropertyMap;

/**
 * What the extent walk measured around each detection, BEFORE it was clamped: measured cell radius
 * over the detection's own radius.
 *
 * <p>{@code resizeToMeasuredExtent} clamps that ratio to {@code EXTENT_MAX_FACTOR} (1.4) because "a
 * reading above it is not a bigger cell, it is the walk escaping into a neighbour". That is exactly
 * the evidence a split needs, and the clamp threw it away. Measured on Fluo-N2DL-HeLa 01: detections
 * covering two truth cells piled up at the clamped radius (47 of 92 at exactly 14.0 µm against 11 of
 * 4256 single cells), and the pile-up only shows for detections whose own radius was the largest;
 * the unclamped ratio says it for every detection.</p>
 *
 * <p>NaN when the walk abstained or the detection was never examined. Registry as in
 * {@code SpotOrigin}: weak key, weak value, kept alive by the listener the graph holds, and the
 * listener clears the entry on every add so a reused pool index reads NaN.</p>
 */
public final class ExtentReading
{
	private static final class State
	{
		final DoublePropertyMap< Spot > ratio;

		State( final ModelGraph graph )
		{
			ratio = new DoublePropertyMap<>( graph.vertices().getRefPool(), Double.NaN );
			graph.addGraphListener( new GraphListener< Spot, Link >()
			{
				@Override
				public void vertexAdded( final Spot vertex )
				{
					ratio.remove( vertex );
				}

				@Override
				public void vertexRemoved( final Spot vertex )
				{
					ratio.remove( vertex );
				}

				@Override
				public void graphRebuilt()
				{}

				@Override
				public void edgeAdded( final Link edge )
				{}

				@Override
				public void edgeRemoved( final Link edge )
				{}
			} );
		}
	}

	private static final WeakHashMap< ModelGraph, WeakReference< State > > REGISTRY = new WeakHashMap<>();

	private ExtentReading()
	{}

	private static synchronized State stateFor( final ModelGraph graph )
	{
		final WeakReference< State > ref = REGISTRY.get( graph );
		State s = ref == null ? null : ref.get();
		if ( s == null )
		{
			s = new State( graph );
			REGISTRY.put( graph, new WeakReference<>( s ) );
		}
		return s;
	}

	/** @param ratio measured radius / detection radius, unclamped; NaN when the walk abstained */
	public static void set( final ModelGraph graph, final Spot spot, final double ratio )
	{
		final State s = stateFor( graph );
		synchronized ( s )
		{
			s.ratio.set( spot, ratio );
		}
	}

	/** The unclamped ratio, or NaN when none was recorded for this spot. */
	public static double get( final ModelGraph graph, final Spot spot )
	{
		final State s = stateFor( graph );
		synchronized ( s )
		{
			return s.ratio.isSet( spot ) ? s.ratio.getDouble( spot ) : Double.NaN;
		}
	}
}
