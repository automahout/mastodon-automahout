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

import java.util.OptionalDouble;
import java.util.WeakHashMap;

import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.properties.DoublePropertyMap;

/**
 * Records ELEPHANT's own per-spot detection {@code quality} (0..1, the model's mean confidence
 * probability over the detected region) so the auto-correction pipeline can use it as a model-based,
 * modality-agnostic accept/reject signal instead of (or as a preferred alternative to) raw image
 * intensity/contrast. Populated by {@link ElephantIntegration#addSpotFromJson} whenever the server's
 * response includes a {@code "quality"} key; spots added through any other path (older server, manual
 * placement, gap-fill/refit recovery) simply have none recorded, which callers must treat as "fall back
 * to the contrast/intensity heuristic" rather than an error.
 *
 * <p>Storage is a {@link DoublePropertyMap} per {@link ModelGraph} — pool-object-safe (it is notified on
 * spot deletion/pool reuse, unlike a plain {@code HashMap<Spot,Double>}) but deliberately TRANSIENT: it is
 * never serialized into the {@code .mastodon} project file. Quality is a server-computed value that can
 * only be captured at detection time (unlike contrast, which can be resampled from pixels at any later
 * point), so a full persisted Mastodon {@code Feature} (requiring {@code FeatureSpec}/{@code
 * FeatureSerializer}/SciJava plugin registration) would be considerably more machinery than this need
 * justifies today; losing recorded quality across a project close/reopen just means the existing
 * contrast/intensity fallback takes over, which is the intended graceful degradation, not a bug.</p>
 */
public final class DetectionQuality
{

	// Keyed by ModelGraph identity (default equals/hashCode) so the entry — and its DoublePropertyMap —
	// is dropped once a project's graph becomes unreachable (project closed). This registry exists only
	// because a fresh ToolContext (and therefore no shared instance state) is constructed at several call
	// sites per action invocation; the ModelGraph itself is the one stable identity shared across them.
	private static final WeakHashMap< ModelGraph, DoublePropertyMap< Spot > > REGISTRY = new WeakHashMap<>();

	private DetectionQuality()
	{}

	/**
	 * Guards the get-or-create race on {@link #REGISTRY} only (a background autonomous-tracking thread
	 * and an EDT button handler may call this concurrently). Per-spot read/write correctness is NOT
	 * separately synchronized here — callers must already hold the graph's read/write lock, exactly as
	 * every other per-spot accessor in this codebase does.
	 */
	private static synchronized DoublePropertyMap< Spot > mapFor( final ModelGraph graph )
	{
		return REGISTRY.computeIfAbsent( graph,
				g -> new DoublePropertyMap<>( g.vertices().getRefPool(), Double.NaN ) );
	}

	/** Records {@code spot}'s ELEPHANT quality. Caller must hold the graph's write lock. */
	public static void set( final ModelGraph graph, final Spot spot, final double quality )
	{
		mapFor( graph ).set( spot, quality );
	}

	/** The recorded quality, or empty if never recorded. Caller must hold the graph's read/write lock. */
	public static OptionalDouble get( final ModelGraph graph, final Spot spot )
	{
		final DoublePropertyMap< Spot > map = mapFor( graph );
		return map.isSet( spot ) ? OptionalDouble.of( map.getDouble( spot ) ) : OptionalDouble.empty();
	}

	/** Whether {@code spot} has a recorded quality. Caller must hold the graph's read/write lock. */
	public static boolean has( final ModelGraph graph, final Spot spot )
	{
		return mapFor( graph ).isSet( spot );
	}
}
