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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.model.Spot;

import net.imglib2.realtransform.AffineTransform3D;

public class GroundTruthOracleTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** A truth written by the exporter, renamed to the CTC truth's file names. */
	private Path truth( final ReplayGraph g, final int tMax ) throws Exception
	{
		final Path res = tmp.newFolder( "res" ).toPath(), tra = tmp.newFolder( "TRA" ).toPath();
		CtcExport.export( g.model(), new long[] { 100, 100 }, new AffineTransform3D(), 0, tMax, res );
		Files.copy( res.resolve( "res_track.txt" ), tra.resolve( "man_track.txt" ) );
		for ( int t = 0; t <= tMax; t++ )
			Files.copy( res.resolve( String.format( "mask%03d.tif", t ) ), tra.resolve( String.format( "man_track%03d.tif", t ) ) );
		return tra;
	}

	@Test
	public void twoCellsInsideTheDetectionAreTwo() throws Exception
	{
		final ReplayGraph g = new ReplayGraph();
		g.spot( 0, 40, 50, 0, 4 );
		g.spot( 0, 52, 50, 0, 4 );
		g.spot( 0, 85, 20, 0, 4 );
		final GroundTruthOracle o = new GroundTruthOracle( truth( g, 0 ), new AffineTransform3D() );
		final double[][] wide = { { 100, 0, 0 }, { 0, 36, 0 }, { 0, 0, 36 } }; // 10 x 6 around (46, 50)
		assertEquals( Boolean.TRUE, o.twoCells( 0, new double[] { 46, 50, 0 }, wide ) );
		final double[][] small = { { 25, 0, 0 }, { 0, 25, 0 }, { 0, 0, 25 } };
		assertEquals( Boolean.FALSE, o.twoCells( 0, new double[] { 85, 20, 0 }, small ) );
		assertEquals( 2, o.splitQuestions.get() );
		assertEquals( 1, o.twoCellAnswers.get() );
	}

	@Test
	public void aDivisionWithinAFrameIsReal() throws Exception
	{
		final ReplayGraph g = new ReplayGraph();
		final Spot m0 = g.spot( 0, 30, 30, 0, 4 ), m1 = g.spot( 1, 30, 30, 0, 4 );
		g.link( m0, m1 );
		g.link( m1, g.spot( 2, 22, 30, 0, 3 ) );
		g.link( m1, g.spot( 2, 38, 30, 0, 3 ) );
		final Spot s0 = g.spot( 0, 70, 70, 0, 4 ), s1 = g.spot( 1, 71, 70, 0, 4 ), s2 = g.spot( 2, 72, 70, 0, 4 );
		g.link( s0, s1 );
		g.link( s1, s2 );
		final GroundTruthOracle o = new GroundTruthOracle( truth( g, 2 ), new AffineTransform3D() );
		// the detection says the division happens at t=0 -> 1: one frame early, still a real division
		assertEquals( Boolean.TRUE, o.divides( 0, new double[] { 30, 30, 0 }, new double[] { 30, 30, 0 }, new double[] { 39, 30, 0 } ) );
		// a cell that never divides
		assertEquals( Boolean.FALSE, o.divides( 1, new double[] { 71, 70, 0 }, new double[] { 72, 70, 0 }, new double[] { 80, 70, 0 } ) );
		// no truth cell within the question's reach
		assertNull( o.divides( 1, new double[] { 5, 95, 0 }, new double[] { 6, 95, 0 }, new double[] { 9, 95, 0 } ) );
		assertEquals( 1, o.unanswered.get() );
	}
}
