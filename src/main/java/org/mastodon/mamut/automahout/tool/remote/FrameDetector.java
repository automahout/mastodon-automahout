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
package org.mastodon.mamut.automahout.tool.remote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * A detector that works on one frame of pixels and answers in pixels -- a pretrained model such as
 * StarDist or Cellpose behind a remote tool server. It does not touch the graph: {@link #detect} can be
 * tried on a few frames and shown to the user before anything is added (see DetectorTrial), and
 * {@link #addToGraph} is what adds them when it is chosen.
 */
public interface FrameDetector extends AutomahoutTool
{
	/** One detection, in the frame's pixels: centroid (x, y, z) and covariance (pixels²). */
	final class Detection
	{
		public final double[] centroid;

		public final double[][] covariance;

		public Detection( final double[] centroid, final double[][] covariance )
		{
			this.centroid = centroid;
			this.covariance = covariance;
		}
	}

	/** The detections in {@code frame}, with {@code params} (missing ones at their defaults). */
	List< Detection > detect( Frame frame, Map< String, Object > params ) throws IOException;

	/**
	 * Converts {@code detections} to world units through the frame's transform and adds them at the
	 * frame's timepoint through {@link GraphEdits}, under one write lock; with {@code replace}, the
	 * frame's unprotected spots are removed first.
	 *
	 * @return how many were added
	 */
	/**
	 * Key of {@link #hints}: the cell radius (µm) measured for this run. Not a tool parameter: a remote tool
	 * receives it with the frame ({@code frame.cell_radius_um}) and may use it or not (docs/remote-tools.md).
	 */
	String HINT_CELL_RADIUS_UM = "cell_radius_um";

	/** What a detector may know about the data besides the pixels: {@link #HINT_CELL_RADIUS_UM} when measured. */
	static java.util.Map< String, Object > hints( final ToolContext ctx )
	{
		final java.util.Map< String, Object > h = new java.util.HashMap<>();
		final Object r = ctx.getSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM );
		if ( r instanceof Number && !Double.isNaN( ( ( Number ) r ).doubleValue() ) )
			h.put( HINT_CELL_RADIUS_UM, ( ( Number ) r ).doubleValue() );
		return h;
	}

	static int addToGraph( final ToolContext ctx, final Frame frame, final List< Detection > detections,
			final boolean replace )
	{
		final List< double[] > pos = new ArrayList<>();
		final List< double[][] > cov = new ArrayList<>();
		for ( final Detection d : detections )
		{
			pos.add( frame.toWorld( d.centroid ) );
			cov.add( frame.covarianceToWorld( d.covariance ) );
		}
		final ModelGraph graph = ctx.getGraph();
		int added = 0;
		graph.getLock().writeLock().lock();
		try
		{
			if ( replace )
			{
				final List< Spot > old = new ArrayList<>();
				for ( final Spot s : graph.vertices() )
					if ( s.getTimepoint() == frame.t && !ProtectedSpots.isProtected( ctx, ctx.getModel(), s ) )
						old.add( graph.vertexRef().refTo( s ) );
				for ( final Spot s : old )
				{
					GraphEdits.removeSpot( ctx, graph, s, GraphEdits.Reason.BULK );
					graph.releaseRef( s );
				}
			}
			final Spot ref = graph.vertexRef();
			for ( int i = 0; i < pos.size(); i++ )
				if ( GraphEdits.addSpot( ctx, graph, ref, frame.t, pos.get( i ), cov.get( i ), GraphEdits.Reason.DETECTION ) != null )
					added++;
			graph.releaseRef( ref );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		if ( added > 0 || replace )
		{
			ctx.getModel().setUndoPoint();
			ctx.notifyGraphChanged();
		}
		return added;
	}
}
