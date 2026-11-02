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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * {@link EllipseFit} against ellipses whose answer is known exactly.
 *
 * <p>Pure geometry, so the truth is arithmetic rather than another measurement. Everything the
 * pipeline will ask of it is asked here first: an exact ellipse, a noisy one, one whose points are
 * half a neighbouring cell's, and the degenerate inputs where the honest answer is nothing.</p>
 */
public class EllipseFitTest
{

	/** Points and normals on the ellipse {@code (cx, cy, a, b, theta)}, {@code n} of them. */
	private static double[][] on( final double cx, final double cy, final double a, final double b,
			final double theta, final int n, final double from, final double to )
	{
		final double[][] out = new double[ n ][ 4 ];
		final double c = Math.cos( theta ), s = Math.sin( theta );
		for ( int i = 0; i < n; i++ )
		{
			final double t = from + ( to - from ) * i / n;
			final double lx = a * Math.cos( t ), ly = b * Math.sin( t );
			out[ i ][ 0 ] = cx + c * lx - s * ly;
			out[ i ][ 1 ] = cy + s * lx + c * ly;
			// Gradient of x^2/a^2 + y^2/b^2 in the local frame, rotated back.
			final double gx = Math.cos( t ) / a, gy = Math.sin( t ) / b;
			final double len = Math.hypot( gx, gy );
			out[ i ][ 2 ] = ( c * gx - s * gy ) / len;
			out[ i ][ 3 ] = ( s * gx + c * gy ) / len;
		}
		return out;
	}

	private static double[][] on( final double cx, final double cy, final double a, final double b,
			final double theta, final int n )
	{
		return on( cx, cy, a, b, theta, n, 0, 2 * Math.PI );
	}

	@Test
	public void anExactEllipseIsRecoveredExactly()
	{
		final EllipseFit.Ellipse e = EllipseFit.fit( on( 50, 40, 12, 7, Math.toRadians( 30 ), 60 ),
				new double[] { 50, 40 }, null );
		assertNotNull( e );
		assertEquals( 50.0, e.x, 0.05 );
		assertEquals( 40.0, e.y, 0.05 );
		assertEquals( 12.0, e.a, 0.05 );
		assertEquals( 7.0, e.b, 0.05 );
		assertEquals( Math.toRadians( 30 ), e.theta, 0.01 );
	}

	/** A circle is an ellipse with nothing to say about its angle, and must not be refused for it. */
	@Test
	public void aCircleIsFitted()
	{
		final EllipseFit.Ellipse e = EllipseFit.fit( on( 0, 0, 9, 9, 0, 48 ),
				new double[] { 0, 0 }, null );
		assertNotNull( e );
		assertEquals( 9.0, e.a, 0.05 );
		assertEquals( 9.0, e.b, 0.05 );
		assertEquals( 1.0, e.elongation(), 0.02 );
	}

	/**
	 * The fit must survive a neighbouring cell's edge, which is the case it exists for.
	 *
	 * <p>Half the points belong to another ellipse entirely. A least-squares fit over all of them
	 * lands between the two and describes neither; RANSAC has to find the majority that agree. This is
	 * exactly the confluent-culture case where a circle fitted to "the extent" reaches into the neck.
	 */
	@Test
	public void aNeighboursEdgeDoesNotJoinTheFit()
	{
		final double[][] mine = on( 0, 0, 10, 6, 0, 40 );
		final double[][] neighbour = on( 26, 0, 10, 6, 0, 40 );
		final double[][] both = new double[ 80 ][];
		System.arraycopy( mine, 0, both, 0, 40 );
		System.arraycopy( neighbour, 0, both, 40, 40 );

		final EllipseFit.Ellipse e = EllipseFit.fit( both, new double[] { 0, 0 }, null );

		assertNotNull( e );
		assertEquals( "the fit must be MY cell, not the pair", 0.0, e.x, 0.6 );
		assertEquals( 10.0, e.a, 0.6 );
		assertEquals( 6.0, e.b, 0.6 );
		assertTrue( "and it must not have swallowed the neighbour's points: " + e, e.inliers <= 45 );
	}

	/** Sub-pixel edge detection is not exact; a tenth of a pixel must not move the answer much. */
	@Test
	public void noiseMovesTheAnswerLittle()
	{
		final double[][] p = on( 10, -5, 14, 9, Math.toRadians( 100 ), 70 );
		final Random rnd = new Random( 7 );
		for ( final double[] q : p )
		{
			q[ 0 ] += 0.15 * rnd.nextGaussian();
			q[ 1 ] += 0.15 * rnd.nextGaussian();
		}
		final EllipseFit.Ellipse e = EllipseFit.fit( p, new double[] { 10, -5 }, null );
		assertNotNull( e );
		assertEquals( 14.0, e.a, 0.8 );
		assertEquals( 9.0, e.b, 0.8 );
	}

	/**
	 * Only part of a contour is visible when a cell touches another; a two-thirds arc must still fit.
	 */
	@Test
	public void anArcIsEnough()
	{
		final EllipseFit.Ellipse e = EllipseFit.fit(
				on( 0, 0, 11, 7, Math.toRadians( 20 ), 45, 0, 4 * Math.PI / 3 ),
				new double[] { 0, 0 }, null );
		assertNotNull( e );
		assertEquals( 11.0, e.a, 0.6 );
		assertEquals( 7.0, e.b, 0.6 );
	}

	/** Points on a line are a degenerate conic, and the honest answer is nothing. */
	@Test
	public void aStraightEdgeIsNotACell()
	{
		final double[][] line = new double[ 40 ][ 4 ];
		for ( int i = 0; i < line.length; i++ )
		{
			line[ i ][ 0 ] = i - 20;
			line[ i ][ 1 ] = 0;
			line[ i ][ 3 ] = 1;
		}
		assertNull( EllipseFit.fit( line, new double[] { 0, 0 }, null ) );
	}

	/** Nothing to fit is not an error and not a guess. */
	@Test
	public void tooFewPointsIsNull()
	{
		assertNull( EllipseFit.fit( on( 0, 0, 5, 5, 0, 4 ), new double[] { 0, 0 }, null ) );
		assertNull( EllipseFit.fit( null, new double[] { 0, 0 }, null ) );
		assertNull( EllipseFit.fit( on( 0, 0, 5, 5, 0, 30 ), null, null ) );
	}

	/** The limits are the caller's, and saying "nothing this elongated" must actually refuse. */
	@Test
	public void theLimitsAreEnforced()
	{
		final double[][] p = on( 0, 0, 20, 4, 0, 60 );
		assertNotNull( "elongation 5 is inside a limit of 6",
				EllipseFit.fit( p, new double[] { 0, 0 }, limits( l -> l.maxElongation = 6.0 ) ) );
		assertNull( "and outside a limit of 2",
				EllipseFit.fit( p, new double[] { 0, 0 }, limits( l -> l.maxElongation = 2.0 ) ) );
		assertNull( "a centre that must not wander refuses one that has",
				EllipseFit.fit( on( 30, 0, 10, 8, 0, 60 ), new double[] { 0, 0 },
						limits( l -> l.maxCentreDistance = 3.0 ) ) );
	}

	/**
	 * Deterministic. A pipeline whose output changes between identical runs cannot be debugged by
	 * comparing two of them, and this is called once per detection per timepoint.
	 */
	@Test
	public void theSameInputGivesTheSameAnswer()
	{
		final double[][] p = on( 3, 4, 13, 8, 1.1, 55 );
		final EllipseFit.Ellipse a = EllipseFit.fit( p, new double[] { 3, 4 }, null );
		final EllipseFit.Ellipse b = EllipseFit.fit( p, new double[] { 3, 4 }, null );
		assertNotNull( a );
		assertNotNull( b );
		assertEquals( a.a, b.a, 0.0 );
		assertEquals( a.b, b.b, 0.0 );
		assertEquals( a.theta, b.theta, 0.0 );
	}

	/**
	 * The covariance is what Mastodon stores, so its eigenvalues have to be the squared semi-axes.
	 *
	 * <p>And the out-of-plane entry is the caller's, not the fit's: one z slice says nothing about a
	 * third dimension, and a 2-D fit must not pretend otherwise.</p>
	 */
	@Test
	public void theCovarianceCarriesTheAxesAndNotMore()
	{
		final EllipseFit.Ellipse e = EllipseFit.fit( on( 0, 0, 12, 5, Math.toRadians( 35 ), 60 ),
				new double[] { 0, 0 }, null );
		assertNotNull( e );
		final double[][] cov = e.covariance( 3.0 );
		assertEquals( "z is exactly what the caller asked for", 9.0, cov[ 2 ][ 2 ], 1e-9 );
		assertEquals( 0.0, cov[ 0 ][ 2 ], 1e-9 );
		assertEquals( 0.0, cov[ 1 ][ 2 ], 1e-9 );
		// Eigenvalues of the in-plane 2x2 block.
		final double tr = cov[ 0 ][ 0 ] + cov[ 1 ][ 1 ];
		final double det = cov[ 0 ][ 0 ] * cov[ 1 ][ 1 ] - cov[ 0 ][ 1 ] * cov[ 1 ][ 0 ];
		final double root = Math.sqrt( Math.max( 0, tr * tr / 4 - det ) );
		assertEquals( e.a * e.a, tr / 2 + root, 1e-6 );
		assertEquals( e.b * e.b, tr / 2 - root, 1e-6 );
	}

	/** The normal is half the evidence: without it a wrong fit through the same points can win. */
	@Test
	public void theNormalsAreUsed()
	{
		final double[][] p = on( 0, 0, 10, 6, 0, 40 );
		final List< double[] > flipped = new ArrayList<>();
		for ( final double[] q : p )
			flipped.add( new double[] { q[ 0 ], q[ 1 ], -q[ 3 ], q[ 2 ] } ); // normals turned 90 deg
		final EllipseFit.Ellipse e = EllipseFit.fit( flipped.toArray( new double[ 0 ][] ),
				new double[] { 0, 0 }, null );
		assertNull( "every normal points along the curve, so no point supports it", e );
	}

	private static EllipseFit.Limits limits( final java.util.function.Consumer< EllipseFit.Limits > f )
	{
		final EllipseFit.Limits l = new EllipseFit.Limits();
		f.accept( l );
		return l;
	}

	/** A bright ellipse painted into a window, with a soft edge like a real nucleus has. */
	private static double[] blob( final int side, final double cx, final double cy, final double a,
			final double b, final double theta, final double background, final double peak )
	{
		final double[] w = new double[ side * side ];
		final double c = Math.cos( theta ), s = Math.sin( theta );
		for ( int y = 0; y < side; y++ )
			for ( int x = 0; x < side; x++ )
			{
				final double dx = x - cx, dy = y - cy;
				final double u = ( c * dx + s * dy ) / a, v = ( -s * dx + c * dy ) / b;
				final double q = Math.sqrt( u * u + v * v );
				// 1 well inside, 0 well outside, a couple of pixels of ramp across the boundary.
				final double t = Math.max( 0.0, Math.min( 1.0, ( 1.15 - q ) / 0.30 ) );
				w[ y * side + x ] = background + t * ( peak - background );
			}
		return w;
	}

	/**
	 * Window to ellipse, which is what the pipeline will actually do.
	 *
	 * <p>The whole path: sampled window, rays to the level, gradients for the normals, RANSAC. The blob
	 * has a soft edge, because a nucleus does and a hard one would make this test easier than the job.
	 */
	@Test
	public void aWindowWithABlobInItGivesTheBlobsEllipse()
	{
		final int side = 64;
		final double[] w = blob( side, 32, 32, 14, 8, Math.toRadians( 25 ), 100, 1000 );
		final double[][] pts = EllipseFit.contourPoints( w, side, side, 550, 32, 32, 32 );

		assertTrue( "every ray should have found an edge: " + pts.length, pts.length >= 30 );
		final EllipseFit.Ellipse e = EllipseFit.fit( pts, new double[] { 32, 32 }, null );
		assertNotNull( e );
		assertEquals( 14.0, e.a, 1.0 );
		assertEquals( 8.0, e.b, 1.0 );
		assertEquals( Math.toRadians( 25 ), e.theta, 0.10 );
	}

	/**
	 * A ray that leaves the window without crossing is not evidence and must not be recorded.
	 *
	 * <p>Recording the window boundary as a contour point is how a confluent frame reports a square
	 * cell. {@code measuredCellRadius} learned this with its own rays; the same mistake is available
	 * here.</p>
	 */
	@Test
	public void aRayThatNeverCrossesContributesNothing()
	{
		final int side = 40;
		final double[] flat = new double[ side * side ];
		java.util.Arrays.fill( flat, 1000.0 ); // bright everywhere: no edge anywhere
		assertEquals( 0, EllipseFit.contourPoints( flat, side, side, 550, 20, 20, 32 ).length );
	}

	/** A seed on background has nothing under it, and the honest answer is no points at all. */
	@Test
	public void aSeedOnBackgroundYieldsNoContour()
	{
		final int side = 40;
		final double[] w = blob( side, 8, 8, 5, 5, 0, 100, 1000 );
		assertEquals( 0, EllipseFit.contourPoints( w, side, side, 550, 30, 30, 32 ).length );
	}

	/**
	 * The normals come from the image, not from the ray direction.
	 *
	 * <p>A ray's own direction agrees with any shape centred on the seed, which would make the normal
	 * test in {@link EllipseFit#fit} vacuous exactly where it is needed -- on a detection sitting
	 * off-centre on its cell, or between two of them.</p>
	 */
	@Test
	public void theNormalsComeFromTheImage()
	{
		final int side = 64;
		// The blob's centre is well away from where the rays start.
		final double[] w = blob( side, 38, 32, 14, 8, 0, 100, 1000 );
		final double[][] pts = EllipseFit.contourPoints( w, side, side, 550, 32, 32, 32 );
		assertTrue( pts.length >= 20 );
		int differing = 0;
		for ( final double[] p : pts )
		{
			final double rx = p[ 0 ] - 32, ry = p[ 1 ] - 32;
			final double rl = Math.hypot( rx, ry );
			if ( rl <= 0 )
				continue;
			final double cos = Math.abs( ( p[ 2 ] * rx + p[ 3 ] * ry ) / rl );
			if ( cos < 0.98 )
				differing++;
		}
		assertTrue( "off-centre, the image normal must differ from the ray direction", differing > 3 );
	}

	/**
	 * Ratchet: the contour fit runs LAST in the cleanup chain, and the order is load-bearing.
	 *
	 * <p>Every pass above it reads a spot's radius as a scalar — Mastodon reports the bounding sphere,
	 * which is the LARGEST semi-axis — and lays its interior samples out on a sphere of it. On a fitted
	 * ellipse those samples land outside the body along the short axis and read background, so fitting
	 * earlier would quietly degrade every intensity test in front of it. These cells are elongated
	 * 1.40x at the median, so this is not a corner case.</p>
	 */
	@Test
	public void theContourFitRunsAfterEveryIntensityTest() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		assertTrue( "ELEPHANT's predict must run the detected-frame plan",
				src.contains( "PassPlan.forRun( context, CorrectionPass.Stage.DETECTED, DetectedPasses.BUILT_IN )" ) );
		final java.util.List< String > plan = detectedPlan();
		final int fit = plan.indexOf( org.mastodon.mamut.automahout.tool.impl.DetectedPasses.CONTOUR );
		assertTrue( "the contour fit must be in the predict chain", fit >= 0 );
		assertEquals( "the contour fit runs last", plan.size() - 1, fit );
		for ( final String earlier : new String[] { org.mastodon.mamut.automahout.tool.impl.DetectedPasses.NO_SIGNAL,
				org.mastodon.mamut.automahout.tool.impl.DetectedPasses.FLAT_CONTRAST,
				org.mastodon.mamut.automahout.tool.impl.DetectedPasses.DEBRIS,
				org.mastodon.mamut.automahout.tool.impl.DetectedPasses.EXTENT,
				org.mastodon.mamut.automahout.tool.impl.DetectedPasses.SMALL } )
		{
			final int at = plan.indexOf( earlier );
			assertTrue( earlier + " not found in the chain", at >= 0 );
			assertTrue( earlier + " must run BEFORE the contour fit, or it samples a sphere of the "
					+ "largest semi-axis through an ellipse", at < fit );
		}
	}

	/**
	 * Ratchet: whatever samples a spot's interior uses the equal-area radius, not the bounding sphere.
	 *
	 * <p>The cross-window half of the same hazard. A spot fitted in an earlier window comes round again
	 * in the next one and in phase 2, where the interior samplers would read its LONGEST semi-axis.
	 * {@link SpotShape#interiorRadius} is exactly the radius for anything circular, so this changes
	 * nothing until a fit has actually run.</p>
	 */
	@Test
	public void theInteriorSamplersUseTheEqualAreaRadius() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		int n = 0;
		for ( int i = src.indexOf( "SpotShape.interiorRadius( cov," ); i >= 0;
				i = src.indexOf( "SpotShape.interiorRadius( cov,", i + 1 ) )
			n++;
		assertEquals( "all three interior samplers must use it", 3, n );
	}

	/** A circle's equal-area radius IS its radius, so nothing round changes behaviour. */
	@Test
	public void theEqualAreaRadiusOfACircleIsItsRadius()
	{
		final double r = 7.5;
		final double[][] circle = { { r * r, 0, 0 }, { 0, r * r, 0 }, { 0, 0, r * r } };
		assertEquals( r, SpotShape.interiorRadius( circle, false ), 1e-9 );
		assertEquals( r, SpotShape.interiorRadius( circle, true ), 1e-9 );
	}

	/** And an ellipse's is between its axes, which is the entire point. */
	@Test
	public void theEqualAreaRadiusOfAnEllipseSitsBetweenItsAxes()
	{
		final EllipseFit.Ellipse e = EllipseFit.fit( on( 0, 0, 12, 6, Math.toRadians( 40 ), 60 ),
				new double[] { 0, 0 }, null );
		assertNotNull( e );
		final double r = SpotShape.interiorRadius( e.covariance( 5.0 ), false );
		assertEquals( "sqrt(a*b)", Math.sqrt( e.a * e.b ), r, 1e-6 );
		assertTrue( "and strictly inside the long axis, which is what the bounding sphere reports",
				r < e.a && r > e.b );
	}

	/**
	 * Ratchet: the report lines appear in the order the passes RUN.
	 *
	 * <p>They did not. The contour fit runs last and its line was printed before the extent and
	 * no-signal ones, so a reader of the log — which is the only account anyone has of the order — would
	 * have concluded the fit happens first, which is the opposite of the thing the ordering is careful
	 * about. A report about a decision has to be computed by the code that made it, and a report about
	 * a SEQUENCE has to be printed in that sequence.</p>
	 */
	@Test
	public void theReportLinesFollowTheOrderThePassesRun() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 );
		final int contour = src.indexOf( "\"  Contour: fitted %d of %d detection(s)" );
		final int extent = src.indexOf( "\"  Extent: resized %d detection(s)" );
		final int noSignal = src.indexOf( "\"  No signal: deleted %d detection(s)" );
		assertTrue( "all three report lines must exist",
				contour >= 0 && extent >= 0 && noSignal >= 0 );
		assertTrue( "the extent report belongs before the contour one, as the pass does",
				extent < contour );
		assertTrue( "and so does the no-signal report", noSignal < contour );
	}

	/**
	 * Ratchet: the no-signal line quotes the measurement the pass actually rests on.
	 *
	 * <p>It went on saying "99.6% of background peaks for 0.35% of real cells" after both the anchor
	 * and the coefficient had changed. Those figures came from a fixture of truth cells and synthetic
	 * background peaks, at 2.5 sigma on the spot-avoiding background — a setting under which the pass
	 * was measured to delete nothing at all. A log line that survives the change it describes is worse
	 * than no log line.</p>
	 */
	@Test
	public void theNoSignalLineQuotesTheMeasurementItRestsOn() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 );
		assertFalse( "that pair of numbers is from the superseded fixture, at a coefficient that "
				+ "measured 0.0% caught", src.contains( "99.6%% of background" ) );
		assertTrue( "it must say what it is anchored on, since that was the whole bug",
				src.contains( "this FRAME's own median plus %.1f sigma" ) );
	}

	/**
	 * Ratchet: what the contour did not support is WITHHELD, never tagged {@code fp}.
	 *
	 * <p>An outline hidden by a neighbour is not evidence of background — measured, 17% of detections
	 * on a real cell are refused for exactly that reason — and {@code fp} carries ten times the weight
	 * of a positive on the wire. Tagging these would teach the detector to stop finding real cells, at
	 * ten times the force of teaching it to find them.</p>
	 */
	@Test
	public void whatTheContourDidNotSupportIsWithheldNotCalledBackground() throws IOException
	{
		final String body = methodBody( "withholdWithoutContourSupport" );
		assertTrue( "it must withhold", body.contains( "vertexTags.set( spot, unlabeled )" ) );
		assertFalse( "and must never assert background", body.contains( "fpTag" ) );
		assertFalse( body.contains( "DetectionTagLabels.FP" ) );
	}

	/**
	 * Ratchet: the gate stands down when the fit is not describing the movie.
	 *
	 * <p>Without a floor this is a cliff: on phase contrast, or a frame the sampler cannot read, the fit
	 * refuses everything and the gate would withhold the entire training set in silence. A guard that
	 * can empty its own input needs a reason to believe its input first.</p>
	 */
	@Test
	public void theGateStandsDownWhenTooLittleIsSupported() throws IOException
	{
		final String body = methodBody( "withholdWithoutContourSupport" );
		assertTrue( "there must be a floor on the supported fraction",
				body.contains( "fraction < CONTOUR_SUPPORT_MIN_FRACTION" ) );
		assertTrue( "and it must say so rather than silently withhold nothing",
				body.contains( "NOT withholding" ) );
	}

	/** Ratchet: a protected spot is the user's own and is never withheld. */
	@Test
	public void aProtectedSpotIsNeverWithheldByThisGate() throws IOException
	{
		assertTrue( methodBody( "withholdWithoutContourSupport" )
				.contains( "ProtectedSpots.isProtected( context, model, spot )" ) );
	}

	/**
	 * Ratchet: every spot the fit examines gets a support value, refused ones included.
	 *
	 * <p>The map is built on the vertex ref pool and is NOT cleared when a spot is removed. Pool indices
	 * really are reused — there is a test for that on the tag side — so writing only the successes would
	 * let a fresh detection inherit the last occupant's support and be taught on that basis.</p>
	 */
	@Test
	public void everyExaminedSpotGetsASupportValue() throws IOException
	{
		assertTrue( methodBody( "fitDetectionsToContour" ).contains(
				"ContourSupport.set( graph, refs.get( i ), e == null ? 0 : e.inliers )" ) );
	}

	/** The ids of the plan each ELEPHANT-detected frame goes through, in order. */
	private static java.util.List< String > detectedPlan()
	{
		final java.util.List< String > ids = new java.util.ArrayList<>();
		for ( final org.mastodon.mamut.automahout.tool.CorrectionPass p
				: org.mastodon.mamut.automahout.tool.impl.DetectedPasses.BUILT_IN )
			ids.add( p.getId() );
		return ids;
	}

	private static String methodBody( final String name ) throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "impl", "ElephantTool.java" ) ),
				StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		final int from = src.indexOf( "static int " + name + "(" );
		assertTrue( name + " must still be findable", from >= 0 );
		final int to = src.indexOf( "\n\tprivate static", from + 40 );
		return src.substring( from, to > from ? to : src.length() );
	}

	/** Overlap of an ellipse with itself is one; with a distant one, zero. */
	@Test
	public void overlapOfTheSameEllipseIsOneAndOfDistantOnesIsZero()
	{
		final double[] e = { 10, 10, 12, 7, Math.toRadians( 30 ) };
		assertEquals( 1.0, EllipseFit.overlap( e, e ), 0.02 );
		assertEquals( 0.0, EllipseFit.overlap( e, new double[] { 200, 200, 12, 7, 0 } ), 1e-9 );
	}

	/**
	 * The decision this exists for: one cell labelled twice against two cells labelled once.
	 *
	 * <p>Measured on real detections against CTC silver truth, a pair on the same cell overlaps at 0.98
	 * and a pair on two cells at 0.02. These are the two cases in miniature: two nearly-coincident
	 * ellipses, and two a cell-width apart that merely touch.</p>
	 */
	@Test
	public void overlapSeparatesOneCellTwiceFromTwoCellsOnce()
	{
		final double[] a = { 0, 0, 10, 7, 0 };
		final double[] nearlyTheSame = { 1.0, 0.5, 10.5, 7.5, Math.toRadians( 8 ) };
		final double[] theNeighbour = { 17, 0, 10, 7, 0 };
		assertTrue( "one cell twice: " + EllipseFit.overlap( a, nearlyTheSame ),
				EllipseFit.overlap( a, nearlyTheSame ) > 0.5 );
		assertTrue( "two cells once: " + EllipseFit.overlap( a, theNeighbour ),
				EllipseFit.overlap( a, theNeighbour ) < 0.5 );
	}

	/** Degenerate input is not an overlap and not an exception. */
	@Test
	public void aDegenerateEllipseOverlapsNothing()
	{
		final double[] e = { 0, 0, 10, 7, 0 };
		assertEquals( 0.0, EllipseFit.overlap( e, new double[] { 0, 0, 0, 7, 0 } ), 1e-9 );
		assertEquals( 0.0, EllipseFit.overlap( e, null ), 1e-9 );
		assertEquals( 0.0, EllipseFit.overlap( null, e ), 1e-9 );
	}

	/** A covariance read back as an ellipse must be the ellipse that made it. */
	@Test
	public void aCovarianceRoundTripsToItsOwnEllipse()
	{
		final EllipseFit.Ellipse e = EllipseFit.fit( on( 0, 0, 13, 6, Math.toRadians( 50 ), 60 ),
				new double[] { 0, 0 }, null );
		assertNotNull( e );
		final double[] back = SpotShape.inPlaneEllipse( e.covariance( 4.0 ) );
		assertNotNull( back );
		assertEquals( e.a, back[ 0 ], 1e-6 );
		assertEquals( e.b, back[ 1 ], 1e-6 );
		assertEquals( "an axis has no direction, so compare modulo pi",
				EllipseFit.normaliseAngle( e.theta ), EllipseFit.normaliseAngle( back[ 2 ] ), 1e-6 );
	}

	/**
	 * Ratchet: one label per cell WITHHOLDS, it does not merge.
	 *
	 * <p>Post-processing merges are off on the user's own instruction, because merging drew one ellipse
	 * around two resolved nuclei and the gap between them. Nothing here may put that back: both spots
	 * stay in the graph and both stay linked, and only the training label is withheld. The two actions
	 * carry very different risks for the same decision — a wrong merge destroys a cell the detector
	 * found, a wrong withhold costs one round's teaching of it.</p>
	 */
	@Test
	public void oneLabelPerCellWithholdsAndNeverMerges() throws IOException
	{
		final String body = methodBody( "withholdOverlappingAnnotations" );
		assertTrue( "it must withhold", body.contains( "vertexTags.set( spot, unlabeled )" ) );
		assertFalse( "it must not remove a spot", body.contains( "GraphEdits.removeSpot" ) );
		assertFalse( "nor merge one", body.contains( "mergeIntoCoveringEllipsoid" ) );
		assertFalse( "nor touch a link", body.contains( "removeLink" ) );
		assertTrue( "and a protected spot is the user's own",
				body.contains( "ProtectedSpots.isProtected( context, model, spot )" ) );
	}

	/**
	 * Ratchet: the one kept is the one the image vouches for, and the sweep is greedy from there.
	 *
	 * <p>Greedy from the best downward is what makes three labels on one cell leave one rather than
	 * two.</p>
	 */
	@Test
	public void theBestSupportedLabelIsTheOneKept() throws IOException
	{
		final String body = methodBody( "withholdOverlappingAnnotations" );
		assertTrue( "kept by contour support", body.contains( "ContourSupport.supportOf( graph, spot )" ) );
		assertTrue( "best first", body.contains( "Double.compare( shapes.get( y )[ 5 ], shapes.get( x )[ 5 ] )" ) );
		assertTrue( "compared as ellipses, not as the spheres that contain them",
				body.contains( "EllipseFit.overlap( mine, other ) > DUPLICATE_ANNOTATION_OVERLAP" ) );
	}

	/**
	 * On a z stack the fit replaces the in-plane shape only. It used to set z to the detection's
	 * bounding radius -- its longest axis -- and the tilt to zero, so a nucleus flat in z became one as
	 * deep as it was wide.
	 */
	@Test
	public void onAStackTheDetectionKeepsItsOwnDepthAndTilt()
	{
		final EllipseFit.Ellipse e = new EllipseFit.Ellipse( 0, 0, 6, 4, 0, 30 );
		final double[][] original = { { 25, 0, 2 }, { 0, 25, 1 }, { 2, 1, 4 } };
		final double[][] cov = e.covarianceKeepingDepth( original );
		assertEquals( "in plane: the fit", 36, cov[ 0 ][ 0 ], 1e-9 );
		assertEquals( 16, cov[ 1 ][ 1 ], 1e-9 );
		assertEquals( "out of plane: the detection's own depth, not its longest axis", 4, cov[ 2 ][ 2 ], 1e-9 );
		assertEquals( "and its own tilt", 2, cov[ 0 ][ 2 ], 1e-9 );
		assertEquals( 1, cov[ 2 ][ 1 ], 1e-9 );
		assertTrue( EllipseFit.positiveDefinite( cov ) );
	}

	/** A tilt the new in-plane shape cannot carry is dropped; the fit and the depth are not. */
	@Test
	public void aTiltThatNoLongerFitsIsDropped()
	{
		final EllipseFit.Ellipse e = new EllipseFit.Ellipse( 0, 0, 1, 1, 0, 30 );
		final double[][] original = { { 100, 0, 18 }, { 0, 100, 0 }, { 18, 0, 4 } };
		final double[][] cov = e.covarianceKeepingDepth( original );
		assertEquals( 0, cov[ 0 ][ 2 ], 0.0 );
		assertEquals( 4, cov[ 2 ][ 2 ], 1e-9 );
		assertEquals( 1, cov[ 0 ][ 0 ], 1e-9 );
		assertTrue( EllipseFit.positiveDefinite( cov ) );
	}

	/** Ratchet: both places that write a fitted ellipse back keep the depth on volumetric data. */
	@Test
	public void bothWriteBackSitesKeepTheDepthOnAStack() throws IOException
	{
		for ( final String f : new String[] { "ElephantTool.java", "EllipsoidFitTool.java" } )
		{
			final String src = new String( java.nio.file.Files.readAllBytes( java.nio.file.Paths.get( "src", "main",
					"java", "org", "mastodon", "mamut", "automahout", "tool", "impl", f ) ),
					java.nio.charset.StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
							.replaceAll( "//[^\\n]*", "" );
			assertTrue( f, src.contains( ".covarianceKeepingDepth( original )" ) );
		}
	}

	@Test
	public void bothWriteBackSitesMoveTheDetectionToTheOutlinesCentre() throws IOException
	{
		for ( final String f : new String[] { "ElephantTool.java", "EllipsoidFitTool.java" } )
		{
			final String src = new String( java.nio.file.Files.readAllBytes( java.nio.file.Paths.get( "src", "main",
					"java", "org", "mastodon", "mamut", "automahout", "tool", "impl", f ) ),
					java.nio.charset.StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
							.replaceAll( "//[^\\n]*", "" );
			assertTrue( f, src.contains( ".setPosition( e.x, 0 )" ) && src.contains( ".setPosition( e.y, 1 )" ) );
		}
	}
}
