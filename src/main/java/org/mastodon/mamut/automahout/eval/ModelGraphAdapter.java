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
package org.mastodon.mamut.automahout.eval;

import java.util.ArrayList;
import java.util.List;

import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Snapshots the current Mastodon model graph into a format-neutral {@link LineageGraphData} so that
 * {@link LineageConstraintLearner} can learn a constraint profile from the spots and links the user
 * has in the project right now — whatever format they were imported from.
 *
 * <p>Spot positions are read in world units and the radius is taken as
 * {@code sqrt(boundingSphereRadiusSquared)}; the {@link Spot#getInternalPoolIndex() internal pool
 * index} is used as the node id (stable for the duration of the snapshot).</p>
 */
public final class ModelGraphAdapter
{

	private ModelGraphAdapter()
	{}

	/** Builds a {@link LineageGraphData} snapshot of {@code graph} under its read lock. */
	public static LineageGraphData snapshot( final ModelGraph graph )
	{
		return snapshot( graph, null );
	}

	/**
	 * As {@link #snapshot(ModelGraph)}, but keeping only the spots {@code keep} accepts (and the links
	 * between two kept spots). {@code null} keeps everything.
	 *
	 * <p>Exists so a GT annotation store can be built from the spots a HUMAN confirmed rather than from
	 * everything in the graph. Snapshotting the whole graph after a tracking run writes that run's own
	 * detections into the store, and the store is what {@code ElephantTool} then trains on in place of
	 * self-detections — so an unfiltered snapshot would turn the one mechanism that breaks the
	 * self-training loop into another turn of it.</p>
	 */
	public static LineageGraphData snapshot( final ModelGraph graph,
			final java.util.function.Predicate< Spot > keep )
	{
		return snapshot( graph, keep, null );
	}

	/**
	 * As {@link #snapshot(ModelGraph, java.util.function.Predicate)}, but also recording each spot's
	 * ELEPHANT detection label from {@code model} when one is given, so a store carries the user's
	 * {@code fp} marks and not only where cells are.
	 */
	public static LineageGraphData snapshot( final ModelGraph graph,
			final java.util.function.Predicate< Spot > keep,
			final org.mastodon.mamut.model.Model model )
	{
		final List< LineageGraphData.Node > nodes = new ArrayList<>();
		final List< LineageGraphData.Edge > edges = new ArrayList<>();
		final Spot tgtRef = graph.vertexRef();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			final double[][] cov = new double[ 3 ][ 3 ];
			final java.util.Set< Integer > kept = new java.util.HashSet<>();
			if ( keep != null )
				for ( final Spot s : graph.vertices() )
					if ( keep.test( s ) )
						kept.add( s.getInternalPoolIndex() );
			for ( final Spot s : graph.vertices() )
			{
				if ( keep != null && !kept.contains( s.getInternalPoolIndex() ) )
					continue;
				s.localize( pos );
				final double r = Math.sqrt( s.getBoundingSphereRadiusSquared() );
				// Capture the full covariance (ellipsoid shape) so GT used as training labels keeps it.
				s.getCovariance( cov );
				final double[] cov9 = new double[ 9 ];
				for ( int rr = 0; rr < 3; rr++ )
					for ( int cc = 0; cc < 3; cc++ )
						cov9[ rr * 3 + cc ] = cov[ rr ][ cc ];
				nodes.add( new LineageGraphData.Node( s.getInternalPoolIndex(), s.getTimepoint(),
						pos[ 0 ], pos[ 1 ], pos[ 2 ], r, cov9,
						model == null ? null
								: org.mastodon.mamut.automahout.elephant.DetectionTagLabels.labelOf( model, s ) ) );
				for ( final Link e : s.outgoingEdges() )
				{
					final int target = e.getTarget( tgtRef ).getInternalPoolIndex();
					// A link to a spot that was filtered out would dangle, and the constraint learner
					// reads edges as lineage relations.
					if ( keep != null && !kept.contains( target ) )
						continue;
					edges.add( new LineageGraphData.Edge( s.getInternalPoolIndex(), target ) );
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
			graph.releaseRef( tgtRef );
		}
		return new LineageGraphData( nodes, edges, "current Mastodon model", "mastodon-model" );
	}
}
