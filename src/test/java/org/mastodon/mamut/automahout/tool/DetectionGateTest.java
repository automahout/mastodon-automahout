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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DetectionGate}, against the real measured distributions.
 *
 * <p>The fixture is 600 cells and 600 background peaks measured on 92 frames of Fluo-N2DL-HeLa
 * silver truth, both by the same procedure -- not numbers chosen to make the gate pass. See
 * {@code benchmark/detection-gating/RESULTS.md}.</p>
 */
public class DetectionGateTest
{
	private static final class Population
	{
		final List< double[] > cells = new ArrayList<>();

		final List< double[] > background = new ArrayList<>();
	}

	private static Population measured() throws IOException
	{
		final Population p = new Population();
		try ( InputStream in = DetectionGateTest.class.getResourceAsStream(
				"/detection-gating/features.tsv" );
				BufferedReader r = new BufferedReader(
						new InputStreamReader( in, StandardCharsets.UTF_8 ) ) )
		{
			String line;
			while ( ( line = r.readLine() ) != null )
			{
				if ( line.isEmpty() || line.charAt( 0 ) == '#' )
					continue;
				final String[] f = line.split( "\\t" );
				final double[] pt = { Double.parseDouble( f[ 1 ] ), Double.parseDouble( f[ 2 ] ) };
				( "cell".equals( f[ 0 ] ) ? p.cells : p.background ).add( pt );
			}
		}
		return p;
	}

	/** Mixes cells with a share of background, then reports what the gate does with each. */
	private static double[] scoreAt( final Population p, final double backgroundShare )
	{
		final int nBg = ( int ) Math.min( p.background.size(),
				Math.round( p.cells.size() * backgroundShare / ( 1 - backgroundShare ) ) );
		final List< double[] > mixed = new ArrayList<>( p.cells );
		mixed.addAll( p.background.subList( 0, nBg ) );
		final double[] standouts = new double[ mixed.size() ];
		final double[] radii = new double[ mixed.size() ];
		for ( int i = 0; i < mixed.size(); i++ )
		{
			standouts[ i ] = mixed.get( i )[ 0 ];
			radii[ i ] = mixed.get( i )[ 1 ];
		}
		final DetectionGate.Gate gate = DetectionGate.learn( standouts, radii );
		assertTrue( gate.decided, gate.reason );
		int keptCells = 0, rejectedBg = 0;
		for ( final double[] c : p.cells )
		{
			if ( gate.admits( c[ 0 ], c[ 1 ] ) )
				keptCells++;
		}
		for ( int i = 0; i < nBg; i++ )
		{
			if ( !gate.admits( p.background.get( i )[ 0 ], p.background.get( i )[ 1 ] ) )
				rejectedBg++;
		}
		return new double[] { ( double ) keptCells / p.cells.size(),
				nBg == 0 ? 1.0 : ( double ) rejectedBg / nBg };
	}

	@Test
	public void itKeepsTheCellsAndRejectsTheBackground() throws IOException
	{
		final Population p = measured();
		for ( final double share : new double[] { 0.05, 0.10, 0.25, 0.50 } )
		{
			final double[] s = scoreAt( p, share );
			assertTrue( s[ 0 ] > 0.90, String.format(
					"at %.0f%% background the gate kept only %.1f%% of real cells; the measured "
							+ "anchored gate keeps 94.5%%", 100 * share, 100 * s[ 0 ] ) );
			assertTrue( s[ 1 ] > 0.99, String.format(
					"at %.0f%% background the gate rejected only %.1f%% of it", 100 * share,
					100 * s[ 1 ] ) );
		}
	}

	@Test
	public void theCutDoesNotMoveWithTheAmountOfBackground() throws IOException
	{
		// The failure this guards: Otsu splits whatever it is given, so given mostly background it
		// splits background and the cut walks up into the cells. The p05 cap is what stops it.
		final Population p = measured();
		final double lo = scoreAt( p, 0.05 )[ 0 ];
		final double hi = scoreAt( p, 0.50 )[ 0 ];
		assertTrue( Math.abs( lo - hi ) < 0.02, String.format(
				"cell recall moved from %.1f%% to %.1f%% with the background share", 100 * lo,
				100 * hi ) );
	}

	@Test
	public void backgroundAloneIsRefusedRatherThanGated() throws IOException
	{
		// A gate that rejects everything is not making a gating decision, it is reporting that the
		// detector produced nothing cell-like. That is a question for a person.
		final Population p = measured();
		final double[] standouts = new double[ p.background.size() ];
		final double[] radii = new double[ p.background.size() ];
		for ( int i = 0; i < p.background.size(); i++ )
		{
			standouts[ i ] = p.background.get( i )[ 0 ];
			radii[ i ] = p.background.get( i )[ 1 ];
		}
		final DetectionGate.Gate gate = DetectionGate.learn( standouts, radii );
		assertFalse( gate.decided, "a population with no signal in it must not be silently gated" );
		assertFalse( gate.admits( 100.0, 100.0 ), "an undecided gate admits nothing" );
		assertTrue( gate.reason.contains( "sigma" ), gate.reason );
	}

	@Test
	public void cellsAloneAreGatedWithoutComplaint() throws IOException
	{
		final Population p = measured();
		final double[] standouts = new double[ p.cells.size() ];
		final double[] radii = new double[ p.cells.size() ];
		for ( int i = 0; i < p.cells.size(); i++ )
		{
			standouts[ i ] = p.cells.get( i )[ 0 ];
			radii[ i ] = p.cells.get( i )[ 1 ];
		}
		final DetectionGate.Gate gate = DetectionGate.learn( standouts, radii );
		assertTrue( gate.decided, gate.reason );
		int kept = 0;
		for ( final double[] c : p.cells )
		{
			if ( gate.admits( c[ 0 ], c[ 1 ] ) )
				kept++;
		}
		assertTrue( kept > 0.90 * p.cells.size(),
				"a clean population must not be gated down: kept " + kept + " of " + p.cells.size() );
	}

	@Test
	public void tooFewDetectionsAreRefused()
	{
		final DetectionGate.Gate gate = DetectionGate.learn(
				new double[] { 10, 20, 30 }, new double[] { 5, 6, 7 } );
		assertFalse( gate.decided );
		assertTrue( gate.reason.contains( "too few" ), gate.reason );
	}

	@Test
	public void otsuSplitsTwoSeparatedGroups()
	{
		final Random rng = new Random( 3 );
		final double[] xs = new double[ 400 ];
		for ( int i = 0; i < 200; i++ )
			xs[ i ] = 1.0 + 0.1 * rng.nextGaussian();
		for ( int i = 200; i < 400; i++ )
			xs[ i ] = 9.0 + 0.1 * rng.nextGaussian();
		final double thr = DetectionGate.otsu( xs );
		// Every threshold in the empty gap between the two groups has the same between-group
		// variance, and the scan keeps the first maximum, so it returns the LOWEST separating cut.
		// That is the conservative end for a radius floor: it withholds the least.
		for ( int i = 0; i < 200; i++ )
			assertTrue( xs[ i ] < thr, "low group member " + xs[ i ] + " not below " + thr );
		for ( int i = 200; i < 400; i++ )
			assertTrue( xs[ i ] >= thr, "high group member " + xs[ i ] + " not above " + thr );
	}

	@Test
	public void otsuOnAConstantPopulationIsThatConstant()
	{
		assertEquals( 4.0, DetectionGate.otsu( new double[] { 4, 4, 4, 4 } ), 1e-9 );
	}

	@Test
	public void deepBackgroundIsClaimedAndNoRealCellIsCaught() throws IOException
	{
		// The threshold that lets the gate SUPPLY negatives instead of only withholding. Measured:
		// the faintest real cell in the fixture stands at 1.75 sigma, so 1.5 claims background and
		// catches nothing. If this ever fails, the claim is no longer safe and the gate must go back
		// to withholding without one.
		final Population p = measured();
		int cellsCaught = 0;
		for ( final double[] c : p.cells )
		{
			if ( c[ 0 ] < DetectionGate.DEEP_BACKGROUND_SIGMA )
				cellsCaught++;
		}
		int bgCaught = 0;
		for ( final double[] b : p.background )
		{
			if ( b[ 0 ] < DetectionGate.DEEP_BACKGROUND_SIGMA )
				bgCaught++;
		}
		assertEquals( 0, cellsCaught,
				"tagging below " + DetectionGate.DEEP_BACKGROUND_SIGMA + " sigma as background must "
						+ "cost no real cell; it caught " + cellsCaught + " of " + p.cells.size() );
		assertTrue( bgCaught > 0.8 * p.background.size(),
				"and must still catch most of the background: " + bgCaught + " of "
						+ p.background.size() );
	}

	@Test
	public void theDeepThresholdSitsBelowTheGate()
	{
		// Claiming background at or above the gate would tag as fp things the gate is unsure about.
		assertTrue( DetectionGate.DEEP_BACKGROUND_SIGMA < DetectionGate.MIN_STANDOUT_SIGMA );
	}

	/**
	 * The radius floor must not move with the population.
	 *
	 * <p>It used to be {@code min(otsu(radii), p05(brightRadii))} -- a percentile, which by
	 * construction always cuts something. Run on a frame whose detections are every one of them real
	 * it still withheld the smallest slice, and again on the next round, and the next. The smallest
	 * cells in a dividing culture are the ones either side of a division, so that is a ratchet against
	 * exactly the population the user watched disappear.</p>
	 *
	 * <p>Measured over the same 8602 cells and 3680 background peaks: the learned cut withheld 396
	 * cells and ALL 396 were in the smallest decile, 396 of 866. It bought 3 background detections.</p>
	 */
	@Test
	public void theRadiusFloorDoesNotMoveWithTheDetections()
	{
		final double[] big = new double[ 40 ], small = new double[ 40 ], standouts = new double[ 40 ];
		for ( int i = 0; i < 40; i++ )
		{
			standouts[ i ] = 10.0 + i;
			big[ i ] = 8.0 + i * 0.5;
			small[ i ] = big[ i ] / 4.0;
		}
		assertEquals( DetectionGate.learn( standouts, big, 1.0 ).minRadius,
				DetectionGate.learn( standouts, small, 1.0 ).minRadius, 1e-9,
				"the floor is a property of the sampling, not of what was detected" );
	}

	/** Every detection real and well above background: none of them may be withheld for its size. */
	@Test
	public void aFrameOfNothingButRealCellsWithholdsNone()
	{
		final double[] standouts = new double[ 30 ], radii = new double[ 30 ];
		for ( int i = 0; i < 30; i++ )
		{
			standouts[ i ] = 5.0 + i;
			radii[ i ] = 3.0 + i * 0.4; // the smallest is a post-division daughter, not a noise peak
		}
		final DetectionGate.Gate gate = DetectionGate.learn( standouts, radii, 1.29 );
		assertTrue( gate.decided );
		for ( int i = 0; i < 30; i++ )
			assertTrue( gate.admits( standouts[ i ], radii[ i ] ),
					"a real cell was withheld for being the smallest one present: r=" + radii[ i ] );
	}

	/**
	 * The case the floor is actually for. Background peaks in the benchmark measure 0.5 px, and the
	 * three that clear the standout floor are all under one voxel.
	 */
	@Test
	public void aDetectionWithLessExtentThanAVoxelIsStillRejectedHoweverBrightItIs()
	{
		final double[] standouts = new double[ 30 ], radii = new double[ 30 ];
		for ( int i = 0; i < 30; i++ )
		{
			standouts[ i ] = 5.0 + i;
			radii[ i ] = 4.0;
		}
		standouts[ 0 ] = 40.0; // as bright as anything in the frame
		radii[ 0 ] = 0.5;      // and with no extent at all
		final DetectionGate.Gate gate = DetectionGate.learn( standouts, radii, 1.29 );
		assertFalse( gate.admits( standouts[ 0 ], radii[ 0 ] ),
				"a sub-voxel noise peak is not a body, however bright" );
		assertTrue( gate.admits( standouts[ 1 ], radii[ 1 ] ) );
	}

	/** A caller that cannot say what a voxel measures here must not get a guessed floor. */
	@Test
	public void noVoxelSizeMeansNoRadiusAxis()
	{
		final double[] standouts = new double[ 30 ], radii = new double[ 30 ];
		for ( int i = 0; i < 30; i++ )
		{
			standouts[ i ] = 5.0 + i;
			radii[ i ] = 0.1 + i * 0.5;
		}
		final DetectionGate.Gate gate = DetectionGate.learn( standouts, radii, 0.0 );
		assertTrue( gate.admits( standouts[ 0 ], radii[ 0 ] ),
				"with no floor the radius axis must admit everything the standout axis does" );
	}
}
