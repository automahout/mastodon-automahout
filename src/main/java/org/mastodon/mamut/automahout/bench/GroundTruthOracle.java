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
package org.mastodon.mamut.automahout.bench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.mastodon.mamut.automahout.autocorrect.QuestionOracle;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

import net.imglib2.realtransform.AffineTransform3D;

/**
 * Answers the run's questions from the CTC tracking truth (condition A+O): a stand-in for a person who knows the
 * answer, so what interaction buys can be measured. The truth is read as ellipsoids ({@link CtcGroundTruth}).
 *
 * <ul>
 * <li>One cell or two: two when at least two truth cells' centres lie inside the detection's ellipse(oid).</li>
 * <li>A real division: yes when the truth cell nearest the mother (within the question's reach) divides at
 * t-1, t or t+1 -- a detection's division may lag the truth's by a frame either way.</li>
 * </ul>
 */
public final class GroundTruthOracle implements QuestionOracle
{
	private final ModelGraph truth;

	private final Map< Integer, List< Spot > > byFrame = new HashMap<>();

	private final boolean is3d;

	public final AtomicInteger splitQuestions = new AtomicInteger(), twoCellAnswers = new AtomicInteger();

	public final AtomicInteger divisionQuestions = new AtomicInteger(), divisionYes = new AtomicInteger(),
			unanswered = new AtomicInteger();

	public GroundTruthOracle( final Path traDir, final AffineTransform3D pixelToWorld ) throws IOException
	{
		final CtcGroundTruth.Read r = CtcGroundTruth.read( traDir, pixelToWorld );
		truth = r.model.getGraph();
		is3d = r.dims.length > 2;
		for ( final Spot s : truth.vertices() )
		{
			final Spot ref = truth.vertexRef();
			ref.refTo( s );
			byFrame.computeIfAbsent( s.getTimepoint(), k -> new ArrayList<>() ).add( ref );
		}
	}

	@Override
	public synchronized Boolean twoCells( final int t, final double[] centre, final double[][] cov )
	{
		splitQuestions.incrementAndGet();
		final int n = is3d ? 3 : 2;
		final double[][] inv = CtcExport.invert( cov, n );
		int inside = 0;
		final double[] p = new double[ 3 ];
		for ( final Spot s : byFrame.getOrDefault( t, new ArrayList<>() ) )
		{
			s.localize( p );
			double m = 0;
			for ( int i = 0; i < n; i++ )
				for ( int j = 0; j < n; j++ )
					m += ( p[ i ] - centre[ i ] ) * inv[ i ][ j ] * ( p[ j ] - centre[ j ] );
			if ( m <= 1.0 )
				inside++;
		}
		if ( inside >= 2 )
			twoCellAnswers.incrementAndGet();
		return inside >= 2;
	}

	@Override
	public synchronized Boolean divides( final int t, final double[] mother, final double[] stays, final double[] jumps )
	{
		divisionQuestions.incrementAndGet();
		final double reach = Math.max( dist( mother, stays ), dist( mother, jumps ) );
		Spot nearest = null;
		double best = Double.POSITIVE_INFINITY;
		final double[] p = new double[ 3 ];
		for ( final Spot s : byFrame.getOrDefault( t, new ArrayList<>() ) )
		{
			s.localize( p );
			final double d = dist( p, mother );
			if ( d < best )
			{
				best = d;
				nearest = s;
			}
		}
		if ( nearest == null || best > reach )
		{
			unanswered.incrementAndGet();
			return null;
		}
		final boolean yes = divisionNear( nearest );
		if ( yes )
			divisionYes.incrementAndGet();
		return yes;
	}

	/** Whether the truth divides at this cell, its predecessor or its successor. */
	private boolean divisionNear( final Spot c )
	{
		if ( c.outgoingEdges().size() >= 2 )
			return true;
		final Spot other = truth.vertexRef();
		try
		{
			for ( final Link l : c.incomingEdges() )
				if ( l.getSource( other ).outgoingEdges().size() >= 2 )
					return true;
			for ( final Link l : c.outgoingEdges() )
				if ( l.getTarget( other ).outgoingEdges().size() >= 2 )
					return true;
			return false;
		}
		finally
		{
			truth.releaseRef( other );
		}
	}

	private static double dist( final double[] a, final double[] b )
	{
		double s = 0;
		for ( int i = 0; i < 3; i++ )
			s += ( a[ i ] - b[ i ] ) * ( a[ i ] - b[ i ] );
		return Math.sqrt( s );
	}
}
