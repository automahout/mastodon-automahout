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
package org.mastodon.mamut.automahout.elephant;

import java.util.WeakHashMap;

import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.properties.DoublePropertyMap;

/**
 * How much of a cell's own outline stood behind each detection, from the contour fit.
 *
 * <p>Exists so the thing the fit already knows can decide what is TAUGHT. Measured against CTC
 * silver truth on 600 real windows, the fit succeeds on 83% of detections that cover a real cell and
 * on 7% of those that cover none — a detection on background has no contour, and it says so.
 * Teaching only what it supports takes the share of training labels that sit on nothing from
 * <b>30.7% to 3.4%</b> while keeping 83.2% of the real cells: 93.5% of the false labels gone.</p>
 *
 * <p>This is what a person does. They do not annotate every detection: they keep the ones they can
 * see are cells and leave the rest alone.</p>
 *
 * <p><b>Every spot the fit looks at gets a value, including the ones it refuses (zero).</b> The map
 * is built on the vertex ref POOL and is not cleared when a spot is removed, so a pool index reused
 * by a later detection would otherwise read the previous occupant's support. Writing every examined
 * spot closes that off; reading a spot the fit never saw is the caller's problem and
 * {@link #has} is how to ask.</p>
 */
public final class ContourSupport
{

	// As DetectionQuality: keyed by ModelGraph identity so the entry is dropped with the project.
	private static final WeakHashMap< ModelGraph, DoublePropertyMap< Spot > > REGISTRY =
			new WeakHashMap<>();

	private ContourSupport()
	{}

	private static synchronized DoublePropertyMap< Spot > mapFor( final ModelGraph graph )
	{
		return REGISTRY.computeIfAbsent( graph,
				g -> new DoublePropertyMap<>( g.vertices().getRefPool(), Double.NaN ) );
	}

	/** @param inliers contour points that supported the fit, or {@code 0} when it refused */
	public static void set( final ModelGraph graph, final Spot spot, final int inliers )
	{
		mapFor( graph ).set( spot, inliers );
	}

	/** Whether the fit has looked at this spot at all. */
	public static boolean has( final ModelGraph graph, final Spot spot )
	{
		return mapFor( graph ).isSet( spot );
	}

	/**
	 * How many contour points stood behind this detection, or {@code 0} when the fit never saw it.
	 *
	 * <p>For choosing BETWEEN two detections rather than judging one: a spot the fit did not examine
	 * has no claim on being the better of a pair.</p>
	 */
	public static double supportOf( final ModelGraph graph, final Spot spot )
	{
		final DoublePropertyMap< Spot > map = mapFor( graph );
		return map.isSet( spot ) ? map.getDouble( spot ) : 0.0;
	}

	/**
	 * Whether the image's own outline stood behind this detection.
	 *
	 * <p>A spot the fit never examined counts as supported. Not knowing is not evidence against, and
	 * on a dataset where the fit cannot run — phase contrast, say — this must not quietly withhold
	 * everything.</p>
	 */
	public static boolean supported( final ModelGraph graph, final Spot spot )
	{
		final DoublePropertyMap< Spot > map = mapFor( graph );
		return !map.isSet( spot ) || map.getDouble( spot ) > 0;
	}
}
