package org.mastodon.mamut.automahout.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Random;

import org.junit.Test;

import bdv.util.RandomAccessibleIntervalMipmapSource;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;

/**
 * {@link EllipsoidFit3D} on a stack like the mouse-embryo light-sheet movie it was written for: 0.64 µm voxels and
 * a level twice as coarse, cells of a few µm on a background of exactly 0, where the in-plane fit refuses.
 */
public class EllipsoidFit3DTest
{
	private static final double VOXEL = 0.64;

	/** One textured ellipsoid (semi-axes µm, axis-aligned) at {@code centre} µm in a 64 µm cube. */
	@SuppressWarnings( "unchecked" )
	private static RandomAccessibleIntervalMipmapSource< FloatType > stack( final double[] centre, final double[] axes )
	{
		final int n = 100;
		final Random rnd = new Random( 7 );
		final float[] px = new float[ n * n * n ];
		for ( int z = 0; z < n; z++ )
			for ( int y = 0; y < n; y++ )
				for ( int x = 0; x < n; x++ )
				{
					final double u = ( x * VOXEL - centre[ 0 ] ) / axes[ 0 ], v = ( y * VOXEL - centre[ 1 ] ) / axes[ 1 ],
							w = ( z * VOXEL - centre[ 2 ] ) / axes[ 2 ];
					px[ ( z * n + y ) * n + x ] = u * u + v * v + w * w <= 1 ? ( float ) ( 400 + 60 * rnd.nextGaussian() ) : 0f;
				}
		// Level 1: 2 x 2 x 2 averages, as a BDV pyramid holds.
		final int m = n / 2;
		final float[] q = new float[ m * m * m ];
		for ( int z = 0; z < n; z++ )
			for ( int y = 0; y < n; y++ )
				for ( int x = 0; x < n; x++ )
					q[ ( z / 2 * m + y / 2 ) * m + x / 2 ] += px[ ( z * n + y ) * n + x ] / 8f;
		return new RandomAccessibleIntervalMipmapSource<>( new RandomAccessibleInterval[] { ArrayImgs.floats( px, n, n, n ),
				ArrayImgs.floats( q, m, m, m ) }, new FloatType(),
				new double[][] { { 1, 1, 1 }, { 2, 2, 2 } }, new FinalVoxelDimensions( "µm", VOXEL, VOXEL, VOXEL ),
				scale( VOXEL, VOXEL, VOXEL ), "embryo" );
	}

	private static AffineTransform3D scale( final double x, final double y, final double z )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.set( x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0 );
		return t;
	}

	private static double[] semiAxes( final double[][] cov )
	{
		return new double[] { Math.sqrt( cov[ 0 ][ 0 ] ), Math.sqrt( cov[ 1 ][ 1 ] ), Math.sqrt( cov[ 2 ][ 2 ] ) };
	}

	/**
	 * A cell of semi-axes 7 x 5.5 x 4.5 µm detected as a sphere of radius 5.5 µm, 1.5 µm off centre: fitted to the
	 * cell's own axes and centre (on level 1, where 5.5 µm is 4.3 voxels).
	 */
	@Test
	public void aDetectionIsFittedToItsCell()
	{
		final double[] centre = { 32, 30, 31 }, axes = { 7, 5.5, 4.5 };
		final RandomAccessibleIntervalMipmapSource< FloatType > source = stack( centre, axes );
		final EllipsoidFit3D.Fit f = EllipsoidFit3D.fit( source, 0, new double[] { 33.5, 30, 31 }, 5.5 );
		assertNotNull( f );
		for ( int d = 0; d < 3; d++ )
			assertEquals( "centre " + d, centre[ d ], f.centre[ d ], 0.7 );
		final double[] r = semiAxes( f.covariance );
		for ( int d = 0; d < 3; d++ )
			assertEquals( "semi-axis " + d, axes[ d ], r[ d ], 0.15 * axes[ d ] );
	}

	/** The coarsest level that still puts four voxels across the radius: 6 µm needs no more than 1.28 µm voxels. */
	@Test
	public void theCoarsestLevelThatResolvesTheCellIsRead()
	{
		final RandomAccessibleIntervalMipmapSource< FloatType > source = stack( new double[] { 32, 32, 32 }, new double[] { 6, 6, 6 } );
		assertEquals( 1, EllipsoidFit3D.level( source, 0, 6.0 ) );
		assertEquals( 0, EllipsoidFit3D.level( source, 0, 3.0 ) );
		assertEquals( -1, EllipsoidFit3D.level( source, 0, 2.0 ) );
	}

	/** Nothing there to fit: refused, and the detection keeps its shape. */
	@Test
	public void emptySpaceIsRefused()
	{
		final RandomAccessibleIntervalMipmapSource< FloatType > source = stack( new double[] { 20, 20, 20 }, new double[] { 5, 5, 5 } );
		assertNull( EllipsoidFit3D.fit( source, 0, new double[] { 48, 48, 48 }, 5.0 ) );
	}

	/** Voxels five times longer in z than in plane (Fluo-N3DH-CE-like) are not fitted in voxel space. */
	@Test
	public void stronglyAnisotropicVoxelsAreNotFitted()
	{
		@SuppressWarnings( "unchecked" )
		final RandomAccessibleIntervalMipmapSource< FloatType > source = new RandomAccessibleIntervalMipmapSource<>(
				new RandomAccessibleInterval[] { ArrayImgs.floats( 64, 64, 16 ) }, new FloatType(),
				new double[][] { { 1, 1, 1 } }, new FinalVoxelDimensions( "µm", 0.2, 0.2, 1.0 ), scale( 0.2, 0.2, 1.0 ), "ce" );
		assertEquals( -1, EllipsoidFit3D.level( source, 0, 5.0 ) );
	}

	/**
	 * A detection a third of its cell's size grows by at most 1.4x per fit, toward the cell, instead of either
	 * staying as small as it is (refused, so the shrinking went on) or jumping to a giant (unbounded).
	 */
	@Test
	public void aFitFarLargerThanTheDetectionIsClampedTo1_4x()
	{
		final RandomAccessibleIntervalMipmapSource< FloatType > source = stack( new double[] { 32, 32, 32 },
				new double[] { 9, 9, 9 } );
		final EllipsoidFit3D.Fit f = EllipsoidFit3D.fit( source, 0, new double[] { 32, 32, 32 }, 3.0 );
		assertNotNull( f );
		for ( final double r : semiAxes( f.covariance ) )
			assertEquals( 1.4 * 3.0, r, 0.05 * 3.0 );
		final EllipsoidFit3D.Fit g = EllipsoidFit3D.fit( source, 0, new double[] { 32, 32, 32 }, 7.0 );
		assertNotNull( g );
		for ( final double r : semiAxes( g.covariance ) )
			assertEquals( 9.0, r, 0.15 * 9.0 );
	}
}
