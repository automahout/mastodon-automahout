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

import java.util.ArrayList;
import java.util.List;

/**
 * Positions where a correction pass concluded a detection should not be there.
 *
 * <p>A person adapting a detector by hand reads the lineage tree, finds the places where it does not
 * hold together, and turns each one into an annotation: the spurious detection becomes {@code fp},
 * the cell that was missed becomes {@code fn}. Those annotations are what the next round trains on —
 * the finding and the supervision are the same act.</p>
 *
 * <p>This pipeline threw the finding away. Its correction passes deleted the spurious spot and moved
 * on, and its {@code fp} negatives were synthesized somewhere else entirely: offset from a real cell
 * and REJECTED if they collided with any detection, so by construction every negative sat where the
 * model had found nothing. ELEPHANT weights a negative at ten times a positive
 * ({@code false_weight}); all of it was being spent confirming empty space.</p>
 *
 * <p>So: remember where correction concluded the model was wrong, and let the negative synthesis draw
 * from there first. Nothing is asserted here — a recorded position is a CANDIDATE, and it still has
 * to clear the same background measurement every other candidate does before it becomes a label.
 * Track surgery is evidence, not a verdict.</p>
 */
public final class CorrectionEvidence
{
	/** {@link ToolContext#getSharedData} key for the pool. */
	public static final String SHARED_KEY = "adaptive_track.correction_evidence";

	/**
	 * Most positions kept. The pool only has to be larger than one window's appetite -- the negative
	 * budget is a few dozen -- and beyond that it is memory held for no reason.
	 *
	 * <p>Raised from 512 when the [3/6] cleanup chain started feeding this as well as [5c/6] track
	 * surgery. A ten-frame window whose filters refuse ~50 detections a frame fills 512 exactly, and
	 * because the OLDEST record is dropped, the window's early timepoints would have been squeezed out
	 * entirely -- every negative would have come from its last frames. That is a bias with no reason
	 * behind it, and 5 doubles a record makes avoiding it free.</p>
	 */
	public static final int MAX_RECORDS = 4096;

	private CorrectionEvidence()
	{}

	/**
	 * Records that a correction pass removed a detection here.
	 *
	 * @param timepoint the removed spot's timepoint
	 * @param pos       its centre, world units
	 * @param radius    its radius, world units
	 */
	public static void spurious( final ToolContext ctx, final int timepoint, final double[] pos,
			final double radius )
	{
		if ( ctx == null || pos == null )
			return;
		final List< double[] > pool = pool( ctx );
		if ( pool.size() >= MAX_RECORDS )
			pool.remove( 0 );
		pool.add( new double[] { pos[ 0 ], pos[ 1 ], pos[ 2 ], timepoint, radius } );
	}

	/**
	 * Takes everything recorded so far and empties the pool.
	 *
	 * <p>Drained rather than read, so one window's findings are offered to one window's negative
	 * synthesis and do not silently become the next window's evidence — the graph has moved on by
	 * then and a position that was spurious may now hold a cell.</p>
	 *
	 * @return {@code {x, y, z, timepoint, radius}} per record, oldest first
	 */
	public static List< double[] > drain( final ToolContext ctx )
	{
		if ( ctx == null )
			return new ArrayList<>();
		final List< double[] > pool = pool( ctx );
		final List< double[] > out = new ArrayList<>( pool );
		pool.clear();
		return out;
	}

	/** How many positions are recorded, without taking them. */
	public static int size( final ToolContext ctx )
	{
		return ctx == null ? 0 : pool( ctx ).size();
	}

	@SuppressWarnings( "unchecked" )
	private static List< double[] > pool( final ToolContext ctx )
	{
		final Object existing = ctx.getSharedData( SHARED_KEY );
		if ( existing instanceof List )
			return ( List< double[] > ) existing;
		final List< double[] > fresh = new ArrayList<>();
		ctx.setSharedData( SHARED_KEY, fresh );
		return fresh;
	}
}
