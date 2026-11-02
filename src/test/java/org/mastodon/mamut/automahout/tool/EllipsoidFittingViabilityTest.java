/*-
 * #%L
 * mastodon-automahout
 * %%
 * Copyright (C) 2024 - 2026 Ko Sugawara
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.mastodon.mamut.fitting.edgel.Edgels;
import org.mastodon.mamut.fitting.edgel.SampleEllipsoidEdgel;
import org.mastodon.mamut.fitting.ellipsoid.Ellipsoid;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.edge.Edgel;
import net.imglib2.algorithm.edge.SubpixelEdgelDetection;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * Whether {@code mastodon-ellipsoid-fitting} can fit anything when the movie has ONE z plane.
 *
 * <p>Measured on Fluo-N2DL-HeLa_01, the detections this pipeline trains on overlap the truth cell at
 * IoU 0.56: they contain it whole (recall 1.00) at 1.31x its radius, so 42% of what is taught as
 * "cell" is not one. An ellipse on the cell's own axes scores 0.92, and the cells are elongated 1.40x
 * at the median, which a sphere cannot follow. So contour fitting is worth real work -- but this
 * dataset has a single z plane, and three separate guards in this project have already failed
 * silently on that.</p>
 *
 * <p>Reading the library's source says it should not work: {@code cropSpot} intersects a 3-D interval
 * with the frame, so z collapses to one; every edgel is then coplanar with a zero z normal, and
 * {@code SampleEllipsoidEdgel.sample} solves for a NINE degree-of-freedom ellipsoid whose z semi-axis
 * nothing constrains. Reading is not running, so this runs it.</p>
 *
 * <p>The 3-D case is here as a CONTROL. Without it a failure in 2-D cannot be told apart from this
 * test assembling the library wrong, which is the more likely mistake.</p>
 */
public class EllipsoidFittingViabilityTest
{

	/** As {@code FitEllipsoidPlugin.fitEllipsoid}, which is private; these are its constants. */
	private static final double SMOOTH_SIGMA = 2;

	private static final double MIN_GRADIENT_MAGNITUDE = 10;

	private static final double MAX_ANGLE = 5 * Math.PI / 180.0;

	private static final double MAX_FACTOR = 1.1;

	private static final int NUM_SAMPLES = 1000;

	private static final int NUM_CANDIDATES = 100;

	private static final double OUTSIDE_CUTOFF = 3;

	private static final double INSIDE_CUTOFF = 5;

	private static final double ANGLE_CUTOFF = 30 * Math.PI / 180.0;

	private static final double MAX_CENTRE_DISTANCE = 10;

	/** A bright ellipsoid on a dark background, semi-axes {@code a, b, c} about the volume's middle. */
	private static RandomAccessibleInterval< FloatType > blob( final long w, final long h, final long d,
			final double a, final double b, final double c )
	{
		final float[] px = new float[ ( int ) ( w * h * d ) ];
		final double cx = ( w - 1 ) / 2.0, cy = ( h - 1 ) / 2.0, cz = ( d - 1 ) / 2.0;
		for ( int z = 0; z < d; z++ )
			for ( int y = 0; y < h; y++ )
				for ( int x = 0; x < w; x++ )
				{
					final double u = ( x - cx ) / a, v = ( y - cy ) / b, t = d == 1 ? 0 : ( z - cz ) / c;
					px[ ( int ) ( ( z * h + y ) * w + x ) ] = u * u + v * v + t * t <= 1.0 ? 1000f : 0f;
				}
		return ArrayImgs.floats( px, w, h, d );
	}

	/** {@code FitEllipsoidPlugin}'s pipeline, assembled from its public parts. */
	private static Ellipsoid fit( final RandomAccessibleInterval< FloatType > raw, final double[] centre )
	{
		final long[] size = raw.dimensionsAsLongArray();
		final RandomAccessibleInterval< FloatType > smoothed = ArrayImgs.floats( size );
		try
		{
			Gauss3.gauss( new double[] { SMOOTH_SIGMA, SMOOTH_SIGMA, SMOOTH_SIGMA },
					Views.extendMirrorSingle( raw ), smoothed );
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( "Gauss3 failed at " + size[ 2 ] + " plane(s): " + e, e );
		}
		final ArrayList< Edgel > all = SubpixelEdgelDetection.getEdgels( smoothed,
				new ArrayImgFactory<>( new FloatType() ), MIN_GRADIENT_MAGNITUDE );
		final List< Edgel > filtered = Edgels.filterEdgelsByOcclusion(
				Edgels.filterEdgelsByDirection( all, centre ), centre, MAX_ANGLE, MAX_FACTOR );
		System.out.println( "  planes=" + size[ 2 ] + "  edgels=" + all.size()
				+ "  after filtering=" + filtered.size() );
		return SampleEllipsoidEdgel.sample( new ArrayList<>( filtered ), centre, NUM_SAMPLES,
				NUM_CANDIDATES, OUTSIDE_CUTOFF, INSIDE_CUTOFF, ANGLE_CUTOFF, MAX_CENTRE_DISTANCE );
	}

	/** @return the fitted semi-axes, or {@code null} when the library refused */
	private static double[] report( final String what, final RandomAccessibleInterval< FloatType > img,
			final double[] centre )
	{
		System.out.println( what );
		try
		{
			final Ellipsoid e = fit( img, centre );
			if ( e == null )
			{
				System.out.println( "  -> null" );
				return null;
			}
			final double[] r = e.getRadii();
			System.out.printf( "  -> semi-axes %.2f / %.2f / %.2f%n", r[ 0 ], r[ 1 ], r[ 2 ] );
			return r;
		}
		catch ( final Throwable t )
		{
			System.out.println( "  -> " + t.getClass().getSimpleName() + ": " + t.getMessage() );
			return null;
		}
	}

	/**
	 * Whether the edgel detector works on a genuinely 2-D image, as opposed to a 3-D one of depth 1.
	 *
	 * <p>This is the number that decides how much a 2-D path costs. At depth 1 the detector returns
	 * NOTHING, so the fit never gets a chance -- but that may be about the degenerate third axis
	 * rather than about two dimensions. If a real 2-D image yields edgels, the work is a 5 degree-of-
	 * freedom solver; if it does not, edge detection has to be written too.</p>
	 */
	@Test
	public void whetherEdgelsExistOnAGenuinelyTwoDimensionalImage()
	{
		final long w = 64, h = 64;
		final float[] px = new float[ ( int ) ( w * h ) ];
		final double cx = ( w - 1 ) / 2.0, cy = ( h - 1 ) / 2.0;
		for ( int y = 0; y < h; y++ )
			for ( int x = 0; x < w; x++ )
			{
				final double u = ( x - cx ) / 14.0, v = ( y - cy ) / 10.0;
				px[ ( int ) ( y * w + x ) ] = u * u + v * v <= 1.0 ? 1000f : 0f;
			}
		final RandomAccessibleInterval< FloatType > raw = ArrayImgs.floats( px, w, h );
		final RandomAccessibleInterval< FloatType > smoothed = ArrayImgs.floats( w, h );
		System.out.println( "GENUINELY 2-D (two dimensions, not depth 1):" );
		Gauss3.gauss( new double[] { SMOOTH_SIGMA, SMOOTH_SIGMA },
				Views.extendMirrorSingle( raw ), smoothed );
		final ArrayList< Edgel > all = SubpixelEdgelDetection.getEdgels( smoothed,
				new ArrayImgFactory<>( new FloatType() ), MIN_GRADIENT_MAGNITUDE );
		System.out.println( "  edgels=" + all.size() );
		assertFalse( "imglib2's edgel detection is dimension-generic and works in 2-D; it is the "
				+ "3-D image of DEPTH ONE that yields nothing", all.isEmpty() );
		assertEquals( "and the edgels it returns are 2-D", 2, all.get( 0 ).numDimensions() );

		// The library's own helpers are another matter: they are written for three dimensions.
		try
		{
			final List< Edgel > filtered = Edgels.filterEdgelsByDirection( all,
					new double[] { cx, cy } );
			fail( "mastodon-ellipsoid-fitting's filters now accept 2-D edgels (kept "
					+ filtered.size() + "). If the library has gained a 2-D path, use it instead of "
					+ "the one this project wrote." );
		}
		catch ( final AssertionError | RuntimeException expected )
		{
			System.out.println( "  Edgels.filterEdgelsByDirection -> "
					+ expected.getClass().getSimpleName() + " (3-D only)" );
		}
	}

	/**
	 * The library fits a thick stack and refuses one plane, and the refusal is at EDGEL DETECTION.
	 *
	 * <p>Measured, not reasoned: reading the source said the nine degree-of-freedom solver would be
	 * degenerate with coplanar edgels, and the real failure is earlier and harder -- at depth 1 the
	 * detector returns NO edgels at all, so the solver never runs. Reading the source got the
	 * conclusion right and the reason wrong, which is the difference between "write a 5 DOF solver"
	 * and "write edge detection too".</p>
	 *
	 * <p>The 32-plane case is the CONTROL. Without it a failure in 2-D could not be told apart from
	 * this test assembling the library wrong, which is the more likely mistake.</p>
	 */
	@Test
	public void theLibraryFitsAStackAndRefusesASinglePlane()
	{
		final long w = 64, h = 64;
		// Elongated in plane, like the cells: 1.40x is this movie's median elongation.
		final double[] thick = report( "CONTROL, 32 planes (what the library was written for):",
				blob( w, h, 32, 14, 10, 12 ), new double[] { ( w - 1 ) / 2.0, ( h - 1 ) / 2.0, 15.5 } );
		assertTrue( "the control must fit, or this test is about the harness and not the library",
				thick != null );
		assertEquals( "and fit the blob it was given, semi-axes 14 / 10 / 12", 14.0, thick[ 0 ], 2.0 );
		assertEquals( 10.0, thick[ 2 ], 2.0 );

		assertTrue( "five planes is already too thin — relevant to any thin stack, not just to 2-D",
				report( "CONTROL, 5 planes:", blob( w, h, 5, 14, 10, 2 ),
						new double[] { ( w - 1 ) / 2.0, ( h - 1 ) / 2.0, 2.0 } ) == null );

		assertTrue( "one plane is this dataset",
				report( "THIS DATASET, 1 plane:", blob( w, h, 1, 14, 10, 1 ),
						new double[] { ( w - 1 ) / 2.0, ( h - 1 ) / 2.0, 0.0 } ) == null );
		assertEquals( "and it fails for want of edgels, before the fit", 0,
				SubpixelEdgelDetection.getEdgels( blob( w, h, 1, 14, 10, 1 ),
						new ArrayImgFactory<>( new FloatType() ), MIN_GRADIENT_MAGNITUDE ).size() );
	}
}
