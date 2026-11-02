package org.mastodon.mamut.automahout.autocorrect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * When two detections turn out to be one cell, deleting one leaves a spot that is off the cell's
 * centre and smaller than the cell — they tile it, so neither of them is it. The result has to be an
 * ellipsoid covering both.
 *
 * <p>The strategy this replaces used {@code Σ Ci + 2 Σ (ci−mean)(ci−mean)ᵀ}, which inflates even two
 * IDENTICAL coincident spots by 41%. That is why it ballooned in dense frames and ended up switched
 * off rather than corrected.</p>
 */
public class CoveringEllipsoidTest
{
	private static double[][] sphere( final double r )
	{
		return new double[][] { { r * r, 0, 0 }, { 0, r * r, 0 }, { 0, 0, r * r } };
	}

	private static double extent( final double[][] cov, final double[] u )
	{
		double q = 0;
		for ( int i = 0; i < 3; i++ )
			for ( int j = 0; j < 3; j++ )
				q += u[ i ] * cov[ i ][ j ] * u[ j ];
		return Math.sqrt( q );
	}

	@Test
	public void itReachesFromOneFarEdgeToTheOther()
	{
		// The measured case: two detections, radius 26, centres 48 apart. The cell they tile runs from
		// one outer edge to the other — 48/2 + 26 = 50 from the midpoint.
		final double[] centre = new double[ 3 ];
		final double[][] cov = AutoCorrectOrchestrator.coveringEllipsoid(
				new double[] { 0, 0, 0 }, sphere( 26 ), new double[] { 48, 0, 0 }, sphere( 26 ), centre );
		assertEquals( 24.0, centre[ 0 ], 1e-9, "centre is the midpoint" );
		assertEquals( 50.0, extent( cov, new double[] { 1, 0, 0 } ), 1e-9 );
		assertEquals( 26.0, extent( cov, new double[] { 0, 1, 0 } ), 1e-9, "unchanged across the pair" );
	}

	@Test
	public void twoIdenticalCoincidentSpotsDoNotGrowAtAll()
	{
		// The old formula returned sqrt(2) r here — 41% bigger than either input, for two spots that
		// are the same spot. Repeated over a dense frame, that is the ballooning.
		final double[] centre = new double[ 3 ];
		final double[][] cov = AutoCorrectOrchestrator.coveringEllipsoid(
				new double[] { 0, 0, 0 }, sphere( 10 ), new double[] { 0, 0, 0 }, sphere( 10 ), centre );
		assertEquals( 10.0, extent( cov, new double[] { 1, 0, 0 } ), 1e-9 );
		assertEquals( 10.0, extent( cov, new double[] { 0, 0, 1 } ), 1e-9 );
	}

	@Test
	public void theResultCannotRunAwayBecauseTheMergeHasAReach()
	{
		// Merging only fires out to SEPARATION_TOO_FAR, so the cover is bounded by construction: at
		// the furthest the pass will ever look, it is 2.1 times the input.
		final double r = 10, d = 2.2 * r;
		final double[] centre = new double[ 3 ];
		final double[][] cov = AutoCorrectOrchestrator.coveringEllipsoid(
				new double[] { 0, 0, 0 }, sphere( r ), new double[] { d, 0, 0 }, sphere( r ), centre );
		assertEquals( 2.1 * r, extent( cov, new double[] { 1, 0, 0 } ), 1e-9 );
	}

	@Test
	public void itCoversBothInputsWhicheverDirectionIsAsked()
	{
		final double[] pa = { 3, -2, 1 }, pb = { -5, 4, 2 };
		final double[] centre = new double[ 3 ];
		final double[][] cov = AutoCorrectOrchestrator.coveringEllipsoid( pa, sphere( 7 ), pb, sphere( 4 ), centre );
		for ( final double[] u : new double[][] { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 },
				{ 0.577, 0.577, 0.577 } } )
		{
			double along = 0, ca = 0, cb = 0;
			for ( int i = 0; i < 3; i++ )
			{
				along += u[ i ] * u[ i ];
				ca += ( pa[ i ] - centre[ i ] ) * u[ i ];
				cb += ( pb[ i ] - centre[ i ] ) * u[ i ];
			}
			final double e = extent( cov, u ) / Math.sqrt( along );
			assertTrue( e >= Math.abs( ca ) - 1e-6, "does not reach A along " + java.util.Arrays.toString( u ) );
			assertTrue( e >= Math.abs( cb ) - 1e-6, "does not reach B along " + java.util.Arrays.toString( u ) );
		}
	}

	@Test
	public void anElongatedDetectionContributesItsLengthAlongThePairAndWidthAcross()
	{
		// Not an averaged radius: a detection stretched along the pair should widen the cover along it
		// and leave it alone across.
		final double[][] longX = { { 400, 0, 0 }, { 0, 25, 0 }, { 0, 0, 25 } }; // 20 long, 5 wide
		final double[] centre = new double[ 3 ];
		final double[][] cov = AutoCorrectOrchestrator.coveringEllipsoid(
				new double[] { 0, 0, 0 }, longX, new double[] { 30, 0, 0 }, sphere( 5 ), centre );
		assertEquals( 15.0 + 20.0, extent( cov, new double[] { 1, 0, 0 } ), 1e-9 );
		assertEquals( 5.0, extent( cov, new double[] { 0, 1, 0 } ), 1e-9 );
	}

	@Test
	public void theLargestSemiAxisIsNotTheLargestDiagonalEntry()
	{
		// The bug this guards. The merge's size cap read the covariance diagonal, which equals the
		// semi-axes only when the ellipsoid is axis-aligned. Joined at 45 degrees the diagonal reads
		// 27% low, so the cap let a tilted merge grow 27% past it -- and the over-merged spots
		// reported were exactly the diagonal ones, while the vertical pairs looked correctly sized.
		final double r = 6.0, d = 2.1 * r;
		final double[] centre = new double[ 3 ];
		final double[][] aligned = AutoCorrectOrchestrator.coveringEllipsoid(
				new double[] { 0, 0, 0 }, sphere( r ), new double[] { d, 0, 0 }, sphere( r ), centre );
		final double t = Math.toRadians( 45 );
		final double[][] tilted = AutoCorrectOrchestrator.coveringEllipsoid(
				new double[] { 0, 0, 0 }, sphere( r ),
				new double[] { d * Math.cos( t ), d * Math.sin( t ), 0 }, sphere( r ), centre );

		final double semiAligned = Math.sqrt( AutoCorrectOrchestrator.largestEigenvalue( aligned ) );
		final double semiTilted = Math.sqrt( AutoCorrectOrchestrator.largestEigenvalue( tilted ) );
		// The same pair, only rotated: the true semi-axis cannot depend on the angle.
		assertEquals( semiAligned, semiTilted, 1e-6 );

		final double diagTilted = Math.sqrt( Math.max( tilted[ 0 ][ 0 ],
				Math.max( tilted[ 1 ][ 1 ], tilted[ 2 ][ 2 ] ) ) );
		assertTrue( diagTilted < semiTilted * 0.85,
				"the diagonal must under-read a tilted ellipsoid, or this test proves nothing: "
						+ diagTilted + " vs " + semiTilted );
	}
}
