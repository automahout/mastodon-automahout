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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Runs {@link ShapeSplit} on the windows {@code benchmark/split-review/export_shape_windows.py}
 * exported, so {@code compare_shape_parity.py} can hold the Java port to the Python reference it was
 * measured on. Does nothing when the export is absent, as {@link ContourFitHarness}.
 */
public class ShapeSplitParityHarness
{
	private static final String IN = "/tmp/split-windows.bin";

	private static final String OUT = "/tmp/split-shape-java.tsv";

	@Test
	public void fitEveryExportedWindow() throws IOException
	{
		if ( !Files.exists( Paths.get( IN ) ) )
		{
			System.out.println( "ShapeSplitParityHarness: " + IN + " absent -- run "
					+ "benchmark/split-review/export_shape_windows.py first. Nothing measured." );
			return;
		}
		try ( DataInputStream in = new DataInputStream( new FileInputStream( IN ) );
				PrintWriter out = new PrintWriter( OUT, "UTF-8" ) )
		{
			final int count = Integer.reverseBytes( in.readInt() );
			out.println( "i\tarea\tiou1\tiou2\tm0x\tm0y\tm1x\tm1y" );
			for ( int i = 0; i < count; i++ )
			{
				final int w = Integer.reverseBytes( in.readInt() );
				final int h = Integer.reverseBytes( in.readInt() );
				final double cx = readLe( in ), cy = readLe( in ), r = readLe( in ), level = readLe( in );
				final byte[] buf = new byte[ w * h * 8 ];
				in.readFully( buf );
				final double[] window = new double[ w * h ];
				ByteBuffer.wrap( buf ).order( ByteOrder.LITTLE_ENDIAN ).asDoubleBuffer().get( window );
				final ShapeSplit.Result res = ShapeSplit.analyse( window, w, h, level, cx, cy, r );
				if ( res == null )
					out.println( i + "\t0\tnan\tnan\tnan\tnan\tnan\tnan" );
				else if ( !res.twoFitted() )
					out.println( i + "\t" + res.area + "\t" + res.iou1 + "\tnan\tnan\tnan\tnan\tnan" );
				else
					out.println( i + "\t" + res.area + "\t" + res.iou1 + "\t" + res.iou2 + "\t"
							+ res.means[ 0 ][ 0 ] + "\t" + res.means[ 0 ][ 1 ] + "\t"
							+ res.means[ 1 ][ 0 ] + "\t" + res.means[ 1 ][ 1 ] );
			}
			System.out.println( "ShapeSplitParityHarness: " + count + " windows -> " + OUT );
		}
	}

	private static double readLe( final DataInputStream in ) throws IOException
	{
		return Double.longBitsToDouble( Long.reverseBytes( in.readLong() ) );
	}
}
