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

import java.util.ArrayList;
import java.util.List;

import org.elephant.setting.main.ElephantMainSettings;
import org.mastodon.mamut.automahout.tool.remote.Frame;

/**
 * The ELEPHANT settings that depend on the data, set from the cell size measured in the image
 * ({@link CellScale}) instead of by hand: the detection radius range and the nearest-neighbour linking
 * distance. Every other ELEPHANT setting stays as the user's settings (ELEPHANT's defaults for a new style)
 * have it. Whether the data is 2D or 3D already reaches ELEPHANT from the image itself ({@code is_3d}).
 *
 * <p>Factors, measured on the development sequences (01) of Fluo-N2DL-HeLa, PhC-C2DL-PSC and Fluo-N3DH-CE
 * against CTC truth ({@code benchmark/cell-scale/RESULTS.md}), with r the estimate:</p>
 * <ul>
 * <li>radius r/2 .. 2.5 r: the truth's 1st..99th percentile radius lies at 0.62..2.11 r, 0.96..2.06 r and
 * 0.86..2.23 r;</li>
 * <li>linking distance 4 r (two cell diameters): covers 99% of the truth's ordinary steps everywhere
 * (2 r already does) and the 99th percentile of the mother-to-daughter step at a division, which 2 r misses
 * 29% / 4% / 41% of the time.</li>
 * </ul>
 */
public final class ElephantDataSettings
{
	private ElephantDataSettings()
	{}

	public static final double R_MIN_FACTOR = 0.5;

	public static final double R_MAX_FACTOR = 2.5;

	public static final double LINK_FACTOR = 4.0;

	/** The values for an estimated cell radius {@code r} (µm): {r_min, r_max, NN linking distance}. */
	public static double[] forRadius( final double r )
	{
		return new double[] { R_MIN_FACTOR * r, R_MAX_FACTOR * r, LINK_FACTOR * r };
	}

	/** The cell radius measured on the first, middle and last frame. */
	public static CellScale.Estimate measure( final Frame.Reader reader, final int tMin, final int tMax )
	{
		final List< CellScale.Sample > samples = new ArrayList<>();
		for ( final int t : new int[] { tMin, ( tMin + tMax ) / 2, tMax } )
		{
			final CellScale.Sample s = reader.readForCellScale( t );
			if ( s != null )
				samples.add( s );
		}
		return CellScale.estimateSamples( samples );
	}

	/** Writes the values into {@code s}; returns what was set, for the log. */
	public static String apply( final ElephantMainSettings s, final double r )
	{
		final double[] v = forRadius( r );
		s.setMinRadius( v[ 0 ] );
		s.setMaxRadius( v[ 1 ] );
		s.setNNLinkingThreshold( v[ 2 ] );
		return describe( r );
	}

	public static String describe( final double r )
	{
		final double[] v = forRadius( r );
		return String.format( "cell radius %.2f µm measured in the image -> ELEPHANT detection radius %.2f..%.2f µm, "
				+ "linking distance %.2f µm", r, v[ 0 ], v[ 1 ], v[ 2 ] );
	}
}
