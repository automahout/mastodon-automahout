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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mastodon.mamut.automahout.tool.GraphWatch;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.EllipseFit;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.automahout.tool.SpotLookupUtil;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * Fits each detection to the contour of the cell under it, in the plane.
 *
 * <p>What it is for, measured on Fluo-N2DL-HeLa_01 against CTC silver truth (2350 detections,
 * {@code benchmark/annotation-extent}): the circular detections this pipeline trains on overlap the
 * cell at an IoU of <b>0.56</b>. They contain it whole — recall 1.00 — at 1.31x its radius, so 42%
 * of what is taught as "cell" is not cell, and between two touching cells that margin reaches into
 * the neck and carries its dark structure into the annotation. An ellipse on the cell's own axes
 * reaches <b>0.92</b>, and reaches 0.92 left at the DETECTOR's own centre against 0.94 perfectly
 * centred: the shape is what matters, not the position. These cells are elongated 1.40x at the
 * median and 1.78x at the 90th percentile, which no circle follows at any radius.</p>
 *
 * <p><b>In plane only, and deliberately.</b> {@code mastodon-ellipsoid-fitting} solves the
 * volumetric nine degree-of-freedom problem and is a dependency of this project for datasets with
 * the planes to support it. Measured ({@code EllipsoidFittingViabilityTest}) it fits a 32-plane blob,
 * refuses a 5-plane one, and on a single plane its edge detection returns nothing at all. This tool
 * solves the five degree-of-freedom problem a plane actually poses, and leaves the out-of-plane
 * radius exactly as it found it rather than asserting anything about a dimension it never saw.</p>
 *
 * <p>Independent of the tracking pipeline: it changes shapes and nothing else. No spot is created,
 * deleted or linked, and a detection whose contour does not support an ellipse is left alone —
 * refusing is a normal outcome here, not a failure.</p>
 */
@Plugin( type = AutomahoutTool.class )
public class EllipsoidFitTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.SHAPE;
	}


	public static final String ID = "fit.ellipse";

	private static final String PARAM_START = "start_timepoint";

	private static final String PARAM_END = "end_timepoint";

	private static final String PARAM_LABEL = "label";

	private static final String PARAM_X = "x";

	private static final String PARAM_Y = "y";

	private static final String PARAM_Z = "z";

	private static final String PARAM_T = "t";

	private static final String PARAM_DRY_RUN = "dry_run";

	private static final double LOOKUP_RADIUS = 10.0;

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Fit detections to the cell contour in the image, as ellipses, replacing the circle "
				+ "each one carries. Measured against CTC silver truth, a circular detection overlaps "
				+ "its cell at IoU 0.56 (it contains the whole cell at 1.31x its radius, so 42% of "
				+ "what is annotated is not cell); an ellipse reaches 0.92. Scope: 'start_timepoint' "
				+ "and 'end_timepoint' for a range, or 'label' / 'x,y,z,t' for one spot; with no "
				+ "arguments, the current timepoint. 'dry_run' (default false) reports without "
				+ "changing. IN PLANE ONLY — the out-of-plane radius is left as it was; for volumetric "
				+ "data with enough z planes use Mastodon's own ellipsoid fitting. Reads the image "
				+ "directly; no ELEPHANT server needed. Protected spots are never touched.";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final boolean dryRun = parseBoolean( params, PARAM_DRY_RUN );
		final ModelGraph graph = context.getGraph();
		final Model model = context.getModel();

		final Spot ref = graph.vertexRef();
		try
		{
			if ( params.containsKey( PARAM_LABEL ) || params.containsKey( PARAM_X ) )
			{
				// Found under the read lock, fitted with none (the fit reads the image), written back by
				// fitOne under the write lock and only if the spot is still the one found (GraphWatch).
				final String label;
				graph.getLock().readLock().lock();
				try
				{
					final Spot spot = SpotLookupUtil.resolve( graph, params, PARAM_LABEL, PARAM_X,
							PARAM_Y, PARAM_Z, PARAM_T, LOOKUP_RADIUS, ref );
					if ( spot == null )
						return "spot not found";
					if ( spot != ref )
						ref.refTo( spot );
					label = spot.getLabel();
				}
				finally
				{
					graph.getLock().readLock().unlock();
				}
				return fitOne( context, model, ref, dryRun ).describeOne( label );
			}
			final int start = parseInt( params, PARAM_START,
					parseInt( params, PARAM_T, context.requireViewer().state().getCurrentTimepoint() ) );
			final int end = parseInt( params, PARAM_END, start );
			if ( end < start )
				return "end_timepoint is before start_timepoint";
			final Outcome total = new Outcome();
			for ( int t = start; t <= end; t++ )
				total.add( fitTimepoint( context, model, graph, t, dryRun ) );
			if ( total.fitted > 0 && !dryRun )
			{
				model.setUndoPoint();
				context.notifyGraphChanged();
			}
			return total.describeRange( start, end, dryRun );
		}
		finally
		{
			graph.releaseRef( ref );
		}
	}

	private static Outcome fitTimepoint( final ToolContext context, final Model model,
			final ModelGraph graph, final int timepoint, final boolean dryRun )
	{
		// The spots are collected under a read lock and fitted outside it: the fit reads the IMAGE,
		// which is by far the slowest thing here, and holding a write lock across it would stall every
		// other reader for as long as the window takes.
		final List< Spot > spots = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint )
					continue;
				if ( ProtectedSpots.isProtected( context, model, s ) )
					continue;
				final Spot copy = graph.vertexRef();
				copy.refTo( s );
				spots.add( copy );
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		final Outcome out = new Outcome();
		try
		{
			for ( final Spot s : spots )
				out.add( fitOne( context, model, s, dryRun ) );
		}
		finally
		{
			for ( final Spot s : spots )
				graph.releaseRef( s );
		}
		return out;
	}

	private static Outcome fitOne( final ToolContext context, final Model model, final Spot spot,
			final boolean dryRun )
	{
		final Outcome out = new Outcome();
		out.considered = 1;
		final double[] pos = new double[ 3 ];
		final double seedRadius;
		final int timepoint;
		final ModelGraph lockedGraph = context.getGraph();
		lockedGraph.getLock().readLock().lock();
		try
		{
			if ( GraphWatch.dirty( lockedGraph, spot ) )
				return out; // deleted or replaced in the viewer since it was collected
			spot.localize( pos );
			seedRadius = Math.sqrt( spot.getBoundingSphereRadiusSquared() );
			timepoint = spot.getTimepoint();
		}
		finally
		{
			lockedGraph.getLock().readLock().unlock();
		}
		final EllipseFit.Ellipse e =
				AutoCorrectOrchestrator.fitEllipseToContour( context, timepoint, pos, seedRadius );
		if ( e == null )
			return out; // the contour did not support an ellipse; the circle stands
		out.fitted = 1;
		out.elongationSum = e.elongation();
		out.areaRatioSum = seedRadius > 0 ? Math.sqrt( e.a * e.b ) / seedRadius : 1.0;
		out.ellipse = e;
		if ( !dryRun )
		{
			// The fit read the image without the lock (fitTimepoint collects first): write back under the
			// write lock, and only onto the spot that was fitted -- not one deleted or replaced in the
			// viewer meanwhile. See GraphWatch.
			final ModelGraph graph = context.getGraph();
			GraphEdits.underWriteLock( graph, () -> {
				if ( GraphWatch.dirty( graph, spot ) )
				{
					GraphWatch.refused( graph );
					return null;
				}
				// In plane, shape AND centre, as the pipeline pass does -- a tool that moved spots where the
				// pass does not would be a trap for whoever compares the two. The centre used to be left
				// alone because the median IoU gained little (0.92 -> 0.94); the tail did not: measured on
				// run 20261004-223428, detections off their truth cell by more than half the minor
				// semi-axis go from 26 to 9 of 544. One z plane says nothing about a third dimension, so z
				// is not moved, and on a stack the detection's own out-of-plane shape is kept; see
				// EllipseFit.Ellipse.covarianceKeepingDepth.
				spot.setPosition( e.x, 0 );
				spot.setPosition( e.y, 1 );
				if ( context.isVolumetric() )
				{
					final double[][] original = new double[ 3 ][ 3 ];
					spot.getCovariance( original );
					spot.setCovariance( e.covarianceKeepingDepth( original ) );
				}
				else
					spot.setCovariance( e.covariance( seedRadius ) );
				return null;
			} );
		}
		return out;
	}

	/** What happened, so the log can say it rather than the caller guess. */
	private static final class Outcome
	{

		int considered, fitted;

		double elongationSum, areaRatioSum;

		EllipseFit.Ellipse ellipse;

		void add( final Outcome o )
		{
			considered += o.considered;
			fitted += o.fitted;
			elongationSum += o.elongationSum;
			areaRatioSum += o.areaRatioSum;
		}

		String describeOne( final String label )
		{
			if ( fitted == 0 )
				return "no ellipse fitted to " + label + ": the contour around it did not support one "
						+ "(too few rays reached an edge, or what they found was not an ellipse). "
						+ "The spot is unchanged.";
			return String.format( "%s fitted: semi-axes %.2f and %.2f, elongation %.2f, %d contour "
					+ "point(s) supported it.", label, ellipse.a, ellipse.b, ellipse.elongation(),
					ellipse.inliers );
		}

		String describeRange( final int start, final int end, final boolean dryRun )
		{
			if ( considered == 0 )
				return String.format( "No spots at t=%d..%d to fit.", start, end );
			if ( fitted == 0 )
				return String.format( "%d spot(s) at t=%d..%d, none of them fitted: no contour "
						+ "supported an ellipse. On fluorescence that usually means the level the cell "
						+ "edge is taken at does not suit this image; check the extent report.",
						considered, start, end );
			return String.format(
					"%s %d of %d spot(s) at t=%d..%d to the contour in the image. Mean elongation "
							+ "%.2f, mean radius %.2f x what the detection carried. The other %d were "
							+ "left alone, which is the normal outcome for a detection whose contour is "
							+ "hidden by a neighbour. Out-of-plane radius unchanged: this is a "
							+ "five degree-of-freedom fit in the plane, not a nine degree-of-freedom "
							+ "ellipsoid.",
					dryRun ? "Would fit" : "Fitted", fitted, considered, start, end,
					elongationSum / fitted, areaRatioSum / fitted, considered - fitted );
		}
	}

	private static boolean parseBoolean( final Map< String, Object > params, final String key )
	{
		final Object v = params.get( key );
		if ( v instanceof Boolean )
			return ( Boolean ) v;
		return v != null && Boolean.parseBoolean( String.valueOf( v ) );
	}

	private static int parseInt( final Map< String, Object > params, final String key,
			final int fallback )
	{
		final Object v = params.get( key );
		if ( v instanceof Number )
			return ( ( Number ) v ).intValue();
		if ( v != null )
			try
			{
				return Integer.parseInt( String.valueOf( v ).trim() );
			}
			catch ( final NumberFormatException ignored )
			{
				// fall through to the default rather than failing the call over a typo
			}
		return fallback;
	}
}
