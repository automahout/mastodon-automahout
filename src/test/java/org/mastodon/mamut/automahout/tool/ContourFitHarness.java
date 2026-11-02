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

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Runs the SHIPPED fitter over real image windows, so its accuracy is measured rather than assumed.
 *
 * <p>{@code benchmark/annotation-extent/RESULTS.md} reports that an ellipse reaches an IoU of 0.92
 * against CTC silver truth where the pipeline's circles reach 0.56. That number is an UPPER BOUND:
 * it was computed from the truth cell's own axes, which a fit from the image cannot know. What the
 * code actually achieves is a different question and this is how it gets answered.</p>
 *
 * <p>A harness, not a test. Python writes the windows exactly as
 * {@code AutoCorrectOrchestrator.fitEllipseToContour} samples them, this runs {@link EllipseFit} over
 * them and writes the fits back, and Python scores them against the truth masks. Measuring across the
 * two keeps the JAVA under measurement -- reimplementing the fitter in Python would measure the
 * reimplementation.</p>
 *
 * <pre>
 *   benchmark/annotation-extent/export_windows.py
 *   mvn -o test -Dtest=ContourFitHarness -DfailIfNoSpecifiedTests=false
 *   benchmark/annotation-extent/score_fits.py
 * </pre>
 *
 * <p>Without the input file it says so and passes. A harness that FAILED when its data is absent
 * would be a suite that cannot run on a machine without the CTC dataset.</p>
 */
public class ContourFitHarness
{

	private static final String IN = "/tmp/contour-windows.bin";

	private static final String OUT = "/tmp/contour-fits.tsv";

	/** Matches {@code AutoCorrectOrchestrator.CONTOUR_RAYS}. */
	private static final int RAYS = 48;

	@Test
	public void fitEveryExportedWindow() throws IOException
	{
		if ( !Files.exists( Paths.get( IN ) ) )
		{
			System.out.println( "ContourFitHarness: " + IN + " absent — run "
					+ "benchmark/annotation-extent/export_windows.py first. Nothing measured." );
			return;
		}
		int fitted = 0, refused = 0;
		try ( DataInputStream in = new DataInputStream( new FileInputStream( IN ) );
				PrintWriter out = new PrintWriter( OUT, "UTF-8" ) )
		{
			final int side = Integer.reverseBytes( in.readInt() );
			final int count = Integer.reverseBytes( in.readInt() );
			out.println( "t\tseedX\tseedY\tseedR\tfitX\tfitY\ta\tb\ttheta\tinliers" );
			final byte[] buf = new byte[ side * side * 8 ];
			final double[] window = new double[ side * side ];
			for ( int i = 0; i < count; i++ )
			{
				final double[] head = new double[ 5 ];
				for ( int k = 0; k < 5; k++ )
					head[ k ] = readLe( in );
				in.readFully( buf );
				ByteBuffer.wrap( buf ).order( ByteOrder.LITTLE_ENDIAN ).asDoubleBuffer().get( window );

				final double seedR = head[ 3 ], level = head[ 4 ];
				final double step = 2.0 * 1.6 * seedR / side;
				final double[][] pixels = EllipseFit.contourPoints( window, side, side, level,
						side / 2.0, side / 2.0, RAYS );
				EllipseFit.Ellipse e = null;
				if ( pixels.length >= EllipseFit.MIN_POINTS )
				{
					// Window pixels to the seed's own frame, in image pixels — the step is isotropic,
					// so only positions move and the normals carry over unchanged.
					final double[][] world = new double[ pixels.length ][ 4 ];
					for ( int k = 0; k < pixels.length; k++ )
					{
						world[ k ][ 0 ] = head[ 1 ] + ( pixels[ k ][ 0 ] - side / 2.0 ) * step;
						world[ k ][ 1 ] = head[ 2 ] + ( pixels[ k ][ 1 ] - side / 2.0 ) * step;
						world[ k ][ 2 ] = pixels[ k ][ 2 ];
						world[ k ][ 3 ] = pixels[ k ][ 3 ];
					}
					final EllipseFit.Limits lim = new EllipseFit.Limits();
					lim.maxCentreDistance = 0.6 * seedR;
					lim.minRadius = 0.5 * seedR;
					lim.maxRadius = 2.8 * seedR;
					lim.inlierDistance = 1.5 * step;
					lim.minInliers = Math.max( EllipseFit.MIN_POINTS + 1, RAYS / 3 );
					e = EllipseFit.fit( world, new double[] { head[ 1 ], head[ 2 ] }, lim );
				}
				if ( e == null )
				{
					refused++;
					out.printf( "%.0f\t%.4f\t%.4f\t%.4f\t\t\t\t\t\t%n", head[ 0 ], head[ 1 ],
							head[ 2 ], seedR );
				}
				else
				{
					fitted++;
					out.printf( "%.0f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.5f\t%d%n",
							head[ 0 ], head[ 1 ], head[ 2 ], seedR, e.x, e.y, e.a, e.b, e.theta,
							e.inliers );
				}
			}
		}
		catch ( final EOFException e )
		{
			System.out.println( "ContourFitHarness: input truncated after " + ( fitted + refused )
					+ " window(s); re-export it." );
		}
		System.out.printf( "ContourFitHarness: fitted %d, refused %d (%.1f%%) -> %s%n",
				fitted, refused, 100.0 * refused / Math.max( 1, fitted + refused ), OUT );
	}

	private static double readLe( final DataInputStream in ) throws IOException
	{
		return Double.longBitsToDouble( Long.reverseBytes( in.readLong() ) );
	}
}
