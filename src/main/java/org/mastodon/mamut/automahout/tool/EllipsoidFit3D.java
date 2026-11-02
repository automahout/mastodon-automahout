package org.mastodon.mamut.automahout.tool;

import java.util.ArrayList;
import java.util.List;

import org.mastodon.mamut.fitting.edgel.Edgels;
import org.mastodon.mamut.fitting.edgel.SampleEllipsoidEdgel;
import org.mastodon.mamut.fitting.ellipsoid.Ellipsoid;

import bdv.viewer.Source;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.edge.Edgel;
import net.imglib2.algorithm.edge.SubpixelEdgelDetection;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

/**
 * A detection fitted to a 3-D ellipsoid on the cell's own edges, with Mastodon's ellipsoid fitting
 * ({@code mastodon-ellipsoid-fitting}: sub-pixel edgels, filtered by direction and occlusion from the centre,
 * then RANSAC). The pipeline of Mastodon's "Fit ellipsoid" plugin, run in the voxels of one resolution level.
 *
 * <p>Why it exists beside {@link EllipseFit}: that fit finds a cell's edge where the intensity falls below the
 * frame's median plus a multiple of its spread, and on a background of exactly 0 (a sparse mouse embryo on a
 * light-sheet stack) there is no spread, so it fitted 0 of every window's detections and the detections kept
 * ELEPHANT's own size -- its core of the cell, which training then taught back, window after window. Edgels come
 * from the gradient and need no background statistics.</p>
 *
 * <p>Measured on synthetic cells on a background of exactly 0 (semi-axes in voxels, smoothing 1 voxel): 3 -> 2.7,
 * 4 -> 3.7, 6 -> 5.8, 8 -> 7.8, 8/6/5 -> 7.7/5.8/4.8, in 40-150 ms each. Hence {@link #MIN_RADIUS_VOXELS} and the
 * coarsest level that still holds it: a block of about 23^3 voxels per detection, cheap to read over a network.</p>
 */
public final class EllipsoidFit3D
{
	private EllipsoidFit3D()
	{}

	/** Smallest semi-axis, in voxels of the level fitted on, at which the fit was measured to hold. */
	static final double MIN_RADIUS_VOXELS = 4.0;

	/** Gaussian smoothing before edge detection, voxels. 2 (the plugin's) undersized a 4-voxel cell by 16%. */
	static final double SMOOTH_SIGMA = 1.0;

	/** Most a level's voxel may be longer along one axis than another: the fit runs in voxel space. */
	static final double MAX_VOXEL_ANISOTROPY = 2.0;

	/** The block read around a detection reaches this many seed radii from its centre. */
	static final double REACH_RADII = 2.0;

	/** The plugin's constants (FitEllipsoidPlugin), in voxels here. */
	private static final double MIN_GRADIENT_MAGNITUDE = 10;

	private static final double MAX_ANGLE = 5 * Math.PI / 180.0;

	private static final double MAX_FACTOR = 1.1;

	private static final int NUM_SAMPLES = 1000;

	private static final int NUM_CANDIDATES = 100;

	private static final double OUTSIDE_CUTOFF = 3;

	private static final double INSIDE_CUTOFF = 5;

	private static final double ANGLE_CUTOFF = 30 * Math.PI / 180.0;

	/**
	 * A fit is scaled (its shape kept) so that every semi-axis lies within these multiples of the detection's own
	 * radius, and kept only with its centre within one radius. The extent pass's measured bounds (AutoCorrectOrchestrator EXTENT_MIN_FACTOR /
	 * EXTENT_MAX_FACTOR): against CTC silver truth the cell's radius over the detection's runs p05 0.11 .. p95
	 * 1.32, and a reading past 1.4 "is not a bigger cell, it is the walk escaping into a neighbour". This was
	 * 0.3..3 at first, and on the mouse embryo it made giant spots.
	 */
	static final double MIN_RADIUS_FACTOR = 0.5, MAX_RADIUS_FACTOR = 1.4;

	/** The fitted ellipsoid in world coordinates. */
	public static final class Fit
	{
		public final double[] centre;

		public final double[][] covariance;

		Fit( final double[] centre, final double[][] covariance )
		{
			this.centre = centre;
			this.covariance = covariance;
		}
	}

	/**
	 * The coarsest level of {@code source} whose voxels are near cubic and still put {@link #MIN_RADIUS_VOXELS}
	 * across {@code radius}; -1 when none does (the cell is too small for the finest level, or every level is
	 * too anisotropic for a fit in voxel space).
	 */
	public static int level( final Source< ? > source, final int t, final double radius )
	{
		for ( int l = source.getNumMipmapLevels() - 1; l >= 0; l-- )
		{
			final double[] v = voxelSize( source, t, l );
			final double max = Math.max( v[ 0 ], Math.max( v[ 1 ], v[ 2 ] ) );
			final double min = Math.min( v[ 0 ], Math.min( v[ 1 ], v[ 2 ] ) );
			if ( max / min <= MAX_VOXEL_ANISOTROPY && radius / max >= MIN_RADIUS_VOXELS )
				return l;
		}
		return -1;
	}

	/**
	 * Fits the cell under a detection.
	 *
	 * @param centre world position of the detection
	 * @param radius the detection's radius, world units
	 * @return the fit, or {@code null} when the image does not support one here (no level to fit on, too few
	 *         edges, or an ellipsoid outside {@link #MIN_RADIUS_FACTOR}..{@link #MAX_RADIUS_FACTOR} of the
	 *         detection or centred more than its radius away)
	 */
	public static < T extends RealType< T > > Fit fit( final Source< T > source, final int t, final double[] centre,
			final double radius )
	{
		if ( !source.isPresent( t ) || !( radius > 0 ) )
			return null;
		final int level = level( source, t, radius );
		if ( level < 0 )
			return null;
		final AffineTransform3D toWorld = new AffineTransform3D();
		source.getSourceTransform( t, level, toWorld );
		final RandomAccessibleInterval< T > rai = source.getSource( t, level );
		if ( rai.numDimensions() < 3 || rai.dimension( 2 ) < 2 )
			return null;
		final double[] v = voxelSize( source, t, level );
		final double[] c = new double[ 3 ];
		toWorld.applyInverse( c, centre );
		final long[] min = new long[ 3 ], max = new long[ 3 ];
		for ( int d = 0; d < 3; d++ )
		{
			final long h = ( long ) Math.ceil( REACH_RADII * radius / v[ d ] + 3 * SMOOTH_SIGMA );
			min[ d ] = Math.max( rai.min( d ), Math.round( c[ d ] ) - h );
			max[ d ] = Math.min( rai.max( d ), Math.round( c[ d ] ) + h );
			if ( max[ d ] - min[ d ] < 2 * MIN_RADIUS_VOXELS )
				return null;
		}
		final Interval box = new FinalInterval( min, max );
		final long[] size = Intervals.dimensionsAsLongArray( box );
		final float[] px = new float[ ( int ) Intervals.numElements( box ) ];
		final Cursor< T > cursor = Views.flatIterable( Views.interval( rai, box ) ).cursor();
		int i = 0;
		while ( cursor.hasNext() )
			px[ i++ ] = cursor.next().getRealFloat();
		final double[] local = { c[ 0 ] - min[ 0 ], c[ 1 ] - min[ 1 ], c[ 2 ] - min[ 2 ] };
		final Ellipsoid e = fitVoxels( px, size, local );
		if ( e == null )
			return null;
		return toWorld( e, toWorld, min, radius, v, centre );
	}

	/** The plugin's pipeline on one block of voxels; {@code centre} in the block's voxel coordinates. */
	static Ellipsoid fitVoxels( final float[] px, final long[] size, final double[] centre )
	{
		try
		{
			final RandomAccessibleInterval< FloatType > raw = ArrayImgs.floats( px, size );
			final RandomAccessibleInterval< FloatType > smoothed = ArrayImgs.floats( size );
			Gauss3.gauss( new double[] { SMOOTH_SIGMA, SMOOTH_SIGMA, SMOOTH_SIGMA }, Views.extendMirrorSingle( raw ),
					smoothed );
			final ArrayList< Edgel > all = SubpixelEdgelDetection.getEdgels( smoothed,
					new ArrayImgFactory<>( new FloatType() ), MIN_GRADIENT_MAGNITUDE );
			final List< Edgel > filtered = Edgels.filterEdgelsByOcclusion( Edgels.filterEdgelsByDirection( all, centre ),
					centre, MAX_ANGLE, MAX_FACTOR );
			// The plugin's MAX_CENTRE_DISTANCE is 10, and here it is in voxels: within the block's own reach.
			final Ellipsoid e = SampleEllipsoidEdgel.sample( new ArrayList<>( filtered ), centre, NUM_SAMPLES,
					NUM_CANDIDATES, OUTSIDE_CUTOFF, INSIDE_CUTOFF, ANGLE_CUTOFF, 10 );
			return e != null && e.isLegitimate() ? e : null;
		}
		catch ( final RuntimeException ex )
		{
			// The library throws on degenerate edgel sets (a blob cut by the block's edge, a flat region).
			return null;
		}
	}

	/** {@code e} (voxels of the block at {@code min}) in world coordinates, if it describes this detection. */
	static Fit toWorld( final Ellipsoid e, final AffineTransform3D toWorld, final long[] min, final double radius,
			final double[] voxel, final double[] seedCentre )
	{
		// The semi-axes lie along the ellipsoid's own axes, not the image's; with voxels within
		// MAX_VOXEL_ANISOTROPY of cubic their mean edge converts them to well within the loose bounds below.
		final double edge = ( voxel[ 0 ] + voxel[ 1 ] + voxel[ 2 ] ) / 3.0;
		double largest = 0, smallest = Double.POSITIVE_INFINITY;
		for ( final double r : e.getRadii() )
		{
			if ( !( r > 0 ) )
				return null;
			largest = Math.max( largest, r * edge );
			smallest = Math.min( smallest, r * edge );
		}
		// Clamped, as the extent pass clamps, not refused. Refusing left a detection that had shrunk below 1/1.4 of
		// its cell exactly as small as it was, so on the mouse embryo the shrinking it exists to stop went on (a
		// bright isolated cell 2-2.7x its detection at t=149..150). Clamped, it grows by at most 1.4x per fit and
		// reaches the cell over a few windows; a giant leap stays impossible. Uniformly, so the fitted shape is kept.
		double scale = 1.0;
		if ( largest > MAX_RADIUS_FACTOR * radius )
			scale = MAX_RADIUS_FACTOR * radius / largest;
		else if ( smallest < MIN_RADIUS_FACTOR * radius )
			scale = MIN_RADIUS_FACTOR * radius / smallest;
		if ( largest * scale > MAX_RADIUS_FACTOR * radius * ( 1 + 1e-9 )
				|| smallest * scale < MIN_RADIUS_FACTOR * radius * ( 1 - 1e-9 ) )
			return null; // too elongated to fit within the bounds at any scale
		final double[] ec = e.getCenter();
		final double[] centre = new double[ 3 ];
		toWorld.apply( new double[] { ec[ 0 ] + min[ 0 ], ec[ 1 ] + min[ 1 ], ec[ 2 ] + min[ 2 ] }, centre );
		double d2 = 0;
		for ( int d = 0; d < 3; d++ )
			d2 += ( centre[ d ] - seedCentre[ d ] ) * ( centre[ d ] - seedCentre[ d ] );
		if ( d2 > radius * radius )
			return null; // fitted to a neighbour, not to the cell this detection is on
		// A = the linear part of voxel -> world; the covariance maps as A C A^T.
		final double[][] cv = e.getCovariance();
		final double[][] a = new double[ 3 ][ 3 ];
		for ( int row = 0; row < 3; row++ )
			for ( int col = 0; col < 3; col++ )
				a[ row ][ col ] = toWorld.get( row, col );
		final double[][] cw = new double[ 3 ][ 3 ];
		for ( int p = 0; p < 3; p++ )
			for ( int q = 0; q < 3; q++ )
			{
				double s = 0;
				for ( int k = 0; k < 3; k++ )
					for ( int l = 0; l < 3; l++ )
						s += a[ p ][ k ] * cv[ k ][ l ] * a[ q ][ l ];
				cw[ p ][ q ] = s * scale * scale;
			}
		return new Fit( centre, cw );
	}

	private static double[] voxelSize( final Source< ? > source, final int t, final int level )
	{
		final AffineTransform3D a = new AffineTransform3D();
		source.getSourceTransform( t, level, a );
		final double[] v = new double[ 3 ];
		for ( int c = 0; c < 3; c++ )
			v[ c ] = Math.sqrt( a.get( 0, c ) * a.get( 0, c ) + a.get( 1, c ) * a.get( 1, c ) + a.get( 2, c ) * a.get( 2, c ) );
		return v;
	}
}
