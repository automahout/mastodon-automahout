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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.FrameReview;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.ToolParameter;
import org.mastodon.mamut.automahout.tool.ToolRole;
import org.mastodon.mamut.automahout.tool.VisualColdStart;
import org.mastodon.mamut.model.Spot;
import org.scijava.plugin.Plugin;

/**
 * Reviews one frame's spots as a person proofreading does, on its own: the frame's cell size by scale selection (or
 * the radius given), the blobs at that size as reference cells, and {@link FrameReview} of the spots against them --
 * found, missed, false hits, size. Changes nothing. The same review the adaptive tracking runs on every window
 * (ElephantTool.reviewWindow), callable on any frame to check a result or the reference itself.
 */
@Plugin( type = AutomahoutTool.class )
public class ReviewFrameTool implements AutomahoutTool
{
	public static final String ID = "review.frame";

	public static final String PARAM_TIMEPOINT = "timepoint", PARAM_RADIUS = "radius", PARAM_PERSIST = "persist";

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public ToolRole getRole()
	{
		return ToolRole.ASSESS;
	}

	@Override
	public String getDescription()
	{
		return "Review one frame's spots against reference cells (blobs at the frame's cell size, read by scale "
				+ "selection unless 'radius' is given): how many references the spots find, which they miss, which spots "
				+ "are false hits, and their size relative to the cells. Fluorescence (bright cells on dark). Changes nothing.";
	}

	@Override
	public List< ToolParameter > getParameters()
	{
		return Arrays.asList(
				ToolParameter.integer( PARAM_TIMEPOINT, "frame to review (default: the one shown)", null ),
				ToolParameter.number( PARAM_RADIUS, "cell radius in world units (default: by scale selection)", null ),
				ToolParameter.bool( PARAM_PERSIST, "keep only references a neighbouring frame confirms", Boolean.FALSE ) );
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final int t = params.get( PARAM_TIMEPOINT ) instanceof Number ? ( ( Number ) params.get( PARAM_TIMEPOINT ) ).intValue()
				: context.requireViewer().state().getCurrentTimepoint();
		final List< bdv.viewer.SourceAndConverter< ? > > sources = context.requireViewer().state().getSources();
		if ( sources.isEmpty() )
			return "no image";
		final bdv.viewer.Source< ? > src = sources.get( 0 ).getSpimSource();
		// Spots first, under the lock; the image afterwards, outside it.
		final List< double[] > spots = spotsAt( context, t );
		double r;
		String sizeNote;
		if ( params.get( PARAM_RADIUS ) instanceof Number )
		{
			r = ( ( Number ) params.get( PARAM_RADIUS ) ).doubleValue();
			sizeNote = "given";
		}
		else
		{
			final double[] est = VisualColdStart.cellRadius( src, t,
					VisualColdStart.scaleLadder( ElephantTool.REVIEW_MIN_RADIUS, ElephantTool.REVIEW_MAX_RADIUS ) );
			r = est[ 0 ];
			sizeNote = "by scale selection over " + ( int ) est[ 1 ] + " blobs";
		}
		if ( !( r > 0 ) )
			return "t=" + t + ": no blobs at any scale -- nothing to review against";
		final boolean persist = Boolean.TRUE.equals( params.get( PARAM_PERSIST ) );
		final List< double[] > refs = persist ? VisualColdStart.persistentBlobs( src, t, r ) : VisualColdStart.blobs( src, t, r );
		final FrameReview.Result review = FrameReview.review( spots, refs, VisualColdStart.blobs( src, t, r ), r );
		return describe( t, r, sizeNote, persist, review );
	}

	static String describe( final int t, final double r, final String sizeNote, final boolean persist,
			final FrameReview.Result review )
	{
		final boolean passes = ElephantTool.goDecision( review.found, review.references, review.falseHits.size(),
				review.predicted, ElephantTool.GO_MAX_FLOOD * review.references, review.sizeRatio );
		final StringBuilder sb = new StringBuilder( String.format( "t=%d: cell radius %.2f (%s); %d reference cells%s; %s -> %s",
				t, r, sizeNote, review.references, persist ? " a neighbouring frame confirms" : "", review,
				passes ? "passes the review" : "does not pass the review" ) );
		appendPositions( sb, "missed", review.missed );
		appendPositions( sb, "false hits", review.falseHits );
		return sb.toString();
	}

	private static void appendPositions( final StringBuilder sb, final String what, final List< double[] > ps )
	{
		if ( ps.isEmpty() )
			return;
		sb.append( "\n  " ).append( what ).append( ':' );
		for ( int i = 0; i < Math.min( 10, ps.size() ); i++ )
			sb.append( String.format( " (%.1f, %.1f, %.1f)", ps.get( i )[ 0 ], ps.get( i )[ 1 ], ps.get( i )[ 2 ] ) );
		if ( ps.size() > 10 )
			sb.append( " and " ).append( ps.size() - 10 ).append( " more" );
	}

	/** {x, y, z, radius} of each spot at {@code t}; radius of the sphere of the spot's volume. */
	private static List< double[] > spotsAt( final ToolContext context, final int t )
	{
		final List< double[] > out = new ArrayList<>();
		final double[][] cov = new double[ 3 ][ 3 ];
		context.getGraph().getLock().readLock().lock();
		try
		{
			for ( final Spot s : context.getModel().getSpatioTemporalIndex().getSpatialIndex( t ) )
			{
				s.getCovariance( cov );
				final double det = cov[ 0 ][ 0 ] * ( cov[ 1 ][ 1 ] * cov[ 2 ][ 2 ] - cov[ 1 ][ 2 ] * cov[ 2 ][ 1 ] )
						- cov[ 0 ][ 1 ] * ( cov[ 1 ][ 0 ] * cov[ 2 ][ 2 ] - cov[ 1 ][ 2 ] * cov[ 2 ][ 0 ] )
						+ cov[ 0 ][ 2 ] * ( cov[ 1 ][ 0 ] * cov[ 2 ][ 1 ] - cov[ 1 ][ 1 ] * cov[ 2 ][ 0 ] );
				out.add( new double[] { s.getDoublePosition( 0 ), s.getDoublePosition( 1 ), s.getDoublePosition( 2 ),
						det > 0 ? Math.pow( det, 1.0 / 6 ) : Double.NaN } );
			}
		}
		finally
		{
			context.getGraph().getLock().readLock().unlock();
		}
		return out;
	}
}
