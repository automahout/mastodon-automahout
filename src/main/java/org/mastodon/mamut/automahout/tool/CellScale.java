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
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.mastodon.mamut.automahout.tool.remote.Frame;

/**
 * A cell's typical radius, read off one raw frame, whatever the modality: the characteristic scale of the
 * scale-normalised Laplacian of Gaussian. Port of {@code benchmark/cell-scale/cell_scale.py} (the reference;
 * the steps, constants and the measurement against CTC truth are there and in its RESULTS.md):
 * near-isotropic resampling, a 3x3(x3) median, normalisation to [p1, p99.8], scale-space maxima of
 * {@code -sigma^2 LoG} above {@link #THRESHOLD}, strongest first with a centre inside a stronger kept blob
 * dropped, and the median radius ({@code sqrt(d) sigma}) of the {@link #TOP} strongest, in micrometres.
 *
 * <p>Filters follow scipy.ndimage exactly (kernels truncated at 4 sigma, mirror-reflected borders, linear
 * resampling with aligned corners), so the two implementations agree on the same frame.</p>
 */
public final class CellScale
{
	/**
	 * {@link ToolContext#getSharedData} key, {@link Double}: the cell radius (µm) measured for this run, set by the
	 * pre-flight. Sent to remote detectors as a hint ({@link org.mastodon.mamut.automahout.tool.remote.FrameDetector#hints}).
	 */
	public static final String SHARED_RADIUS_UM = "cell_scale.radius_um";

	private CellScale()
	{}

	static final int NUM_SIGMAS = 30;

	static final double SIGMA_MIN = 1.0, SIGMA_MAX = 25.0;

	static final double THRESHOLD = 0.05;

	static final int TOP = 50;

	/**
	 * Most voxels the scale space may run on, after step 1's resampling. The frames the estimate was measured on
	 * (benchmark/cell-scale/RESULTS.md) come to 0.4-0.8 M there; thirty scales of 2 M floats take 240 MB. A
	 * 600 x 600 x 600 stack would have needed 26 GB and kilotap filters over every voxel: pre-flight never
	 * finished. A larger frame is block-averaged to fit ({@link #coarsen}).
	 */
	public static final long MAX_VOXELS = 2_000_000L;

	/** The radius found and how many blobs it rests on. */
	public static final class Estimate
	{
		/** Micrometres; NaN when no blob was found. */
		public final double radius;

		public final int blobs;

		/**
		 * The voxel size (µm) the frames were block-averaged to because they were too large to measure as they
		 * are; NaN when they were measured at full resolution.
		 */
		public final double coarseVoxel;

		Estimate( final double radius, final int blobs )
		{
			this( radius, blobs, Double.NaN );
		}

		Estimate( final double radius, final int blobs, final double coarseVoxel )
		{
			this.radius = radius;
			this.blobs = blobs;
			this.coarseVoxel = coarseVoxel;
		}

		@Override
		public String toString()
		{
			return String.format( "%.2f µm from %d blob(s)", radius, blobs );
		}
	}

	static double[] sigmas()
	{
		final double[] s = new double[ NUM_SIGMAS ];
		for ( int i = 0; i < NUM_SIGMAS; i++ )
			s[ i ] = SIGMA_MIN * Math.pow( SIGMA_MAX / SIGMA_MIN, i / ( double ) ( NUM_SIGMAS - 1 ) );
		return s;
	}

	/** The estimate for one frame. */
	public static Estimate estimate( final Frame f )
	{
		final double vx = norm( f.voxelToWorld, 0 ), vz = norm( f.voxelToWorld, 2 );
		final int nx = ( int ) f.dims[ 0 ], ny = ( int ) f.dims[ 1 ], nz = ( int ) f.dims[ 2 ];
		final boolean is3d = nz > 1;
		final double iso = is3d ? Math.max( vx, vz / 2.0 ) : vx;
		// 1. resample (z, y, x order of the reference: data is x fastest)
		int[] shape = is3d ? new int[] { nz, ny, nx } : new int[] { ny, nx };
		float[] img = f.data;
		if ( is3d )
		{
			final int[] out = { ( int ) Math.round( nz * vz / iso ), ( int ) Math.round( ny * vx / iso ),
					( int ) Math.round( nx * vx / iso ) };
			img = zoomLinear( img, shape, out );
			shape = out;
		}
		// 2. median, 3. normalise
		img = median3( img, shape );
		final float[] sorted = img.clone();
		Arrays.sort( sorted );
		final double lo = percentile( sorted, 1.0 ), hi = percentile( sorted, 99.8 );
		final double span = Math.max( hi - lo, 1e-6 );
		for ( int i = 0; i < img.length; i++ )
			img[ i ] = ( float ) Math.min( 1.0, Math.max( 0.0, ( img[ i ] - lo ) / span ) );
		// 4. scale space
		final double[] sig = sigmas();
		final float[] base = img;
		final int[] shp = shape;
		final float[][] stack = new float[ sig.length ][];
		IntStream.range( 0, sig.length ).parallel().forEach( k -> {
			final float[] l = gaussianLaplace( base, shp, sig[ k ] );
			final float s2 = ( float ) ( sig[ k ] * sig[ k ] );
			for ( int i = 0; i < l.length; i++ )
				l[ i ] = -s2 * l[ i ];
			stack[ k ] = l;
		} );
		// 5. scale-space maxima above the threshold
		final List< double[] > peaks = scaleSpaceMaxima( stack, shape );
		peaks.sort( ( a, b ) -> Double.compare( b[ 0 ], a[ 0 ] ) );
		// 6. prune, 7. median radius of the strongest kept
		final int d = shape.length;
		final List< double[] > kept = new ArrayList<>(); // {centre..., radius}
		for ( final double[] p : peaks )
		{
			final double rad = Math.sqrt( d ) * sig[ ( int ) p[ 1 ] ];
			boolean inside = false;
			for ( final double[] q : kept )
			{
				double dist2 = 0;
				for ( int i = 0; i < d; i++ )
					dist2 += ( p[ 2 + i ] - q[ i ] ) * ( p[ 2 + i ] - q[ i ] );
				if ( dist2 < q[ d ] * q[ d ] )
				{
					inside = true;
					break;
				}
			}
			if ( inside )
				continue;
			final double[] k = new double[ d + 1 ];
			System.arraycopy( p, 2, k, 0, d );
			k[ d ] = rad;
			kept.add( k );
			if ( kept.size() >= TOP )
				break;
		}
		if ( kept.isEmpty() )
			return new Estimate( Double.NaN, 0 );
		final double[] radii = new double[ kept.size() ];
		for ( int i = 0; i < radii.length; i++ )
			radii[ i ] = kept.get( i )[ d ];
		Arrays.sort( radii );
		final int n = radii.length;
		final double med = n % 2 == 1 ? radii[ n / 2 ] : 0.5 * ( radii[ n / 2 - 1 ] + radii[ n / 2 ] );
		return new Estimate( med * iso, n );
	}

	/** The median of the estimates of several frames: robust to one frame with few cells or much noise. */
	public static Estimate estimate( final List< Frame > frames )
	{
		final List< Estimate > es = new ArrayList<>();
		for ( final Frame f : frames )
			es.add( estimate( f ) );
		return median( es, Double.NaN );
	}

	/**
	 * The median of the estimates of several samples ({@link Frame.Reader#readForCellScale}). A coarsened sample
	 * whose cells sit at the bottom of the scale range is not measured: they may be smaller still, and below
	 * about two voxels a blob's size is the voxel's, not the cell's. At full resolution that check is not made,
	 * as before: CE's first frame reads at the smallest scale (0.87 µm at 0.5 µm voxels) and is within its truth.
	 */
	public static Estimate estimateSamples( final List< Sample > samples )
	{
		final List< Estimate > es = new ArrayList<>();
		double coarse = Double.NaN;
		for ( final Sample s : samples )
		{
			Estimate e = estimate( s.frame );
			if ( s.coarsened )
			{
				coarse = Double.isNaN( coarse ) ? s.voxel : Math.max( coarse, s.voxel );
				if ( !resolved( e, s.frame ) )
					e = new Estimate( Double.NaN, 0 );
			}
			es.add( e );
		}
		return median( es, coarse );
	}

	private static Estimate median( final List< Estimate > es, final double coarseVoxel )
	{
		final List< Double > r = new ArrayList<>();
		int blobs = 0;
		for ( final Estimate e : es )
			if ( !Double.isNaN( e.radius ) )
			{
				r.add( e.radius );
				blobs += e.blobs;
			}
		if ( r.isEmpty() )
			return new Estimate( Double.NaN, 0, coarseVoxel );
		r.sort( Double::compare );
		final int n = r.size();
		return new Estimate( n % 2 == 1 ? r.get( n / 2 ) : 0.5 * ( r.get( n / 2 - 1 ) + r.get( n / 2 ) ), blobs,
				coarseVoxel );
	}

	/** Whether the radius lies above the two smallest scales, in the voxels step 1 resamples {@code f} to. */
	static boolean resolved( final Estimate e, final Frame f )
	{
		if ( Double.isNaN( e.radius ) )
			return true;
		final boolean is3d = f.dims[ 2 ] > 1;
		final double iso = isoVoxel( is3d, norm( f.voxelToWorld, 0 ), norm( f.voxelToWorld, 2 ) );
		return e.radius / iso >= Math.sqrt( is3d ? 3 : 2 ) * sigmas()[ 2 ] - 1e-9;
	}

	/** A frame read for the estimate, and whether it was block-averaged (to {@code voxel} µm) to fit. */
	public static final class Sample
	{
		public final Frame frame;

		public final boolean coarsened;

		/** The voxel size (µm) asked for when coarsened; NaN otherwise. */
		public final double voxel;

		public Sample( final Frame frame, final boolean coarsened, final double voxel )
		{
			this.frame = frame;
			this.coarsened = coarsened;
			this.voxel = voxel;
		}
	}

	/** The voxel size (µm) step 1 resamples to: max(vx, vz / 2) for a stack, vx for one plane. */
	static double isoVoxel( final boolean is3d, final double vx, final double vz )
	{
		return is3d ? Math.max( vx, vz / 2.0 ) : vx;
	}

	/**
	 * The voxel size (µm) a frame of these dimensions and voxel sizes (x, z) must be coarsened to so that the
	 * scale space runs on at most {@link #MAX_VOXELS}; NaN when it fits as it is.
	 */
	public static double coarseVoxel( final long[] dims, final double vx, final double vz )
	{
		final boolean is3d = dims[ 2 ] > 1;
		final double iso = isoVoxel( is3d, vx, vz );
		double n = Math.round( dims[ 0 ] * vx / iso ) * ( double ) Math.round( dims[ 1 ] * vx / iso );
		if ( is3d )
			n *= Math.round( dims[ 2 ] * vz / iso );
		if ( n <= MAX_VOXELS )
			return Double.NaN;
		return iso * Math.pow( n / MAX_VOXELS, 1.0 / ( is3d ? 3 : 2 ) );
	}

	/** Whole voxels per block along x, y, z that bring voxels of the given sizes (µm) to at least {@code s}. */
	public static int[] blockFactors( final double[] voxel, final double s )
	{
		final int[] f = new int[ 3 ];
		for ( int d = 0; d < 3; d++ )
			f[ d ] = Math.max( 1, ( int ) Math.ceil( s / voxel[ d ] - 1e-9 ) );
		return f;
	}

	/** {@code f} as read, or block-averaged when it is too large to measure as it is. */
	public static Sample coarsen( final Frame f )
	{
		if ( f == null )
			return null;
		final double vx = norm( f.voxelToWorld, 0 ), vz = norm( f.voxelToWorld, 2 );
		final double s = coarseVoxel( f.dims, vx, vz );
		if ( Double.isNaN( s ) )
			return new Sample( f, false, Double.NaN );
		final Binner b = new Binner( f.dims, blockFactors( new double[] { vx, norm( f.voxelToWorld, 1 ), vz }, s ) );
		for ( final float v : f.data )
			b.add( v );
		return new Sample( b.frame( f.t, f.voxelToWorld ), true, s );
	}

	/**
	 * Block-averages a frame fed one voxel at a time in its flat order (x fastest), so a reader can coarsen a
	 * frame without holding it. A partial block at the far edge is the mean of the voxels it has.
	 */
	public static final class Binner
	{
		private final long nx, ny;

		private final int[] f;

		private final int ox, oy, oz;

		private final double[] sum;

		private final int[] count;

		private long x, y, z;

		public Binner( final long[] dims, final int[] factors )
		{
			nx = dims[ 0 ];
			ny = dims[ 1 ];
			f = factors.clone();
			ox = ( int ) ( ( dims[ 0 ] + f[ 0 ] - 1 ) / f[ 0 ] );
			oy = ( int ) ( ( dims[ 1 ] + f[ 1 ] - 1 ) / f[ 1 ] );
			oz = ( int ) ( ( dims[ 2 ] + f[ 2 ] - 1 ) / f[ 2 ] );
			final long n = ( long ) ox * oy * oz;
			if ( n > Integer.MAX_VALUE )
				throw new IllegalArgumentException( "block-averaged frame still too large: " + n + " voxels" );
			sum = new double[ ( int ) n ];
			count = new int[ ( int ) n ];
		}

		public void add( final float v )
		{
			final int i = ( int ) ( ( ( z / f[ 2 ] ) * oy + y / f[ 1 ] ) * ox + x / f[ 0 ] );
			sum[ i ] += v;
			count[ i ]++;
			if ( ++x == nx )
			{
				x = 0;
				if ( ++y == ny )
				{
					y = 0;
					z++;
				}
			}
		}

		/** The averaged frame; its voxels are the blocks, centred on the voxels they cover. */
		public Frame frame( final int t, final double[] voxelToWorld )
		{
			final float[] data = new float[ sum.length ];
			for ( int i = 0; i < data.length; i++ )
				data[ i ] = count[ i ] == 0 ? 0f : ( float ) ( sum[ i ] / count[ i ] );
			final double[] a = voxelToWorld.clone();
			for ( int r = 0; r < 3; r++ )
				for ( int c = 0; c < 3; c++ )
				{
					a[ 4 * r + 3 ] += voxelToWorld[ 4 * r + c ] * ( f[ c ] - 1 ) / 2.0;
					a[ 4 * r + c ] = voxelToWorld[ 4 * r + c ] * f[ c ];
				}
			return new Frame( t, new long[] { ox, oy, oz }, data, a );
		}
	}

	private static double norm( final double[] a, final int col )
	{
		return Math.sqrt( a[ col ] * a[ col ] + a[ 4 + col ] * a[ 4 + col ] + a[ 8 + col ] * a[ 8 + col ] );
	}

	/** numpy.percentile, linear interpolation, on sorted data. */
	static double percentile( final float[] sorted, final double q )
	{
		final double pos = q / 100.0 * ( sorted.length - 1 );
		final int i = ( int ) Math.floor( pos );
		final int j = Math.min( i + 1, sorted.length - 1 );
		return sorted[ i ] + ( pos - i ) * ( sorted[ j ] - sorted[ i ] );
	}

	/** scipy.ndimage.zoom(order=1): out index o maps to o * (in - 1) / (out - 1). */
	static float[] zoomLinear( final float[] in, final int[] inShape, final int[] outShape )
	{
		final int d = inShape.length;
		float[] cur = in;
		int[] cs = inShape.clone();
		for ( int axis = 0; axis < d; axis++ )
		{
			final int[] ns = cs.clone();
			ns[ axis ] = outShape[ axis ];
			final float[] out = new float[ size( ns ) ];
			final int[] stride = strides( cs ), nstride = strides( ns );
			final int nIn = cs[ axis ], nOut = ns[ axis ];
			final double scale = nOut > 1 ? ( nIn - 1 ) / ( double ) ( nOut - 1 ) : 0;
			final float[] src = cur;
			final int[] csF = cs;
			final int ax = axis;
			for ( int o = 0; o < out.length; o++ )
			{
				int rem = o, srcBase = 0, oa = 0;
				for ( int k = 0; k < d; k++ )
				{
					final int c = rem / nstride[ k ];
					rem %= nstride[ k ];
					if ( k == ax )
						oa = c;
					else
						srcBase += c * stride[ k ];
				}
				final double x = oa * scale;
				final int i0 = Math.min( ( int ) Math.floor( x ), csF[ ax ] - 1 );
				final int i1 = Math.min( i0 + 1, csF[ ax ] - 1 );
				final double w = x - i0;
				out[ o ] = ( float ) ( src[ srcBase + i0 * stride[ ax ] ] * ( 1 - w ) + src[ srcBase + i1 * stride[ ax ] ] * w );
			}
			cur = out;
			cs = ns;
		}
		return cur;
	}

	static int size( final int[] s )
	{
		int n = 1;
		for ( final int v : s )
			n *= v;
		return n;
	}

	static int[] strides( final int[] s )
	{
		final int[] st = new int[ s.length ];
		int a = 1;
		for ( int k = s.length - 1; k >= 0; k-- )
		{
			st[ k ] = a;
			a *= s[ k ];
		}
		return st;
	}

	/** scipy 'reflect' mode: d c b a | a b c d | d c b a. */
	static int reflect( int i, final int n )
	{
		if ( n == 1 )
			return 0;
		final int period = 2 * n;
		i %= period;
		if ( i < 0 )
			i += period;
		return i < n ? i : period - 1 - i;
	}

	/** scipy 'nearest' mode. */
	static int nearest( final int i, final int n )
	{
		return i < 0 ? 0 : i >= n ? n - 1 : i;
	}

	/** scipy.ndimage.median_filter(size=3), mode 'reflect'. */
	static float[] median3( final float[] in, final int[] shape )
	{
		final int d = shape.length;
		final int[] st = strides( shape );
		final float[] out = new float[ in.length ];
		final int k = d == 3 ? 27 : 9;
		IntStream.range( 0, in.length ).parallel().forEach( i -> {
			final float[] w = new float[ k ];
			final int[] c = new int[ d ];
			int rem = i;
			for ( int a = 0; a < d; a++ )
			{
				c[ a ] = rem / st[ a ];
				rem %= st[ a ];
			}
			int m = 0;
			if ( d == 2 )
			{
				for ( int dy = -1; dy <= 1; dy++ )
					for ( int dx = -1; dx <= 1; dx++ )
						w[ m++ ] = in[ reflect( c[ 0 ] + dy, shape[ 0 ] ) * st[ 0 ] + reflect( c[ 1 ] + dx, shape[ 1 ] ) ];
			}
			else
			{
				for ( int dz = -1; dz <= 1; dz++ )
					for ( int dy = -1; dy <= 1; dy++ )
						for ( int dx = -1; dx <= 1; dx++ )
							w[ m++ ] = in[ reflect( c[ 0 ] + dz, shape[ 0 ] ) * st[ 0 ]
									+ reflect( c[ 1 ] + dy, shape[ 1 ] ) * st[ 1 ] + reflect( c[ 2 ] + dx, shape[ 2 ] ) ];
			}
			Arrays.sort( w );
			out[ i ] = w[ k / 2 ];
		} );
		return out;
	}

	/** scipy's 1D Gaussian kernel of the given derivative order (0 or 2), radius int(4 sigma + 0.5). */
	static double[] kernel( final double sigma, final int order )
	{
		final int radius = ( int ) ( 4.0 * sigma + 0.5 );
		final double[] k = new double[ 2 * radius + 1 ];
		final double s2 = sigma * sigma;
		double sum = 0;
		for ( int x = -radius; x <= radius; x++ )
		{
			k[ x + radius ] = Math.exp( -0.5 / s2 * x * x );
			sum += k[ x + radius ];
		}
		for ( int x = -radius; x <= radius; x++ )
		{
			k[ x + radius ] /= sum;
			if ( order == 2 )
				k[ x + radius ] *= ( x * x / ( s2 * s2 ) - 1.0 / s2 );
		}
		return k;
	}

	/** 1D correlation along one axis, mode 'reflect'. */
	static float[] correlate1d( final float[] in, final int[] shape, final int axis, final double[] k )
	{
		final int[] st = strides( shape );
		final int n = shape[ axis ], r = ( k.length - 1 ) / 2;
		final float[] out = new float[ in.length ];
		final int lines = in.length / n;
		IntStream.range( 0, lines ).parallel().forEach( line -> {
			// the line's first element: all coordinates but the axis, from the line index
			int rem = line, base = 0;
			for ( int a = shape.length - 1; a >= 0; a-- )
			{
				if ( a == axis )
					continue;
				base += ( rem % shape[ a ] ) * st[ a ];
				rem /= shape[ a ];
			}
			final double[] buf = new double[ n + 2 * r ];
			for ( int i = -r; i < n + r; i++ )
				buf[ i + r ] = in[ base + reflect( i, n ) * st[ axis ] ];
			for ( int i = 0; i < n; i++ )
			{
				double s = 0;
				for ( int j = 0; j < k.length; j++ )
					s += k[ j ] * buf[ i + j ];
				out[ base + i * st[ axis ] ] = ( float ) s;
			}
		} );
		return out;
	}

	/** scipy.ndimage.gaussian_laplace: the sum over axes of the second derivative along it, smoothed along the rest. */
	static float[] gaussianLaplace( final float[] in, final int[] shape, final double sigma )
	{
		final double[] g0 = kernel( sigma, 0 ), g2 = kernel( sigma, 2 );
		final int d = shape.length;
		final float[] sum = new float[ in.length ];
		for ( int axis = 0; axis < d; axis++ )
		{
			float[] t = in;
			for ( int a = 0; a < d; a++ )
				t = correlate1d( t, shape, a, a == axis ? g2 : g0 );
			for ( int i = 0; i < sum.length; i++ )
				sum[ i ] += t[ i ];
		}
		return sum;
	}

	/** {response, sigma index, coordinates...} of each scale-space maximum (3 x 3^d, mode 'nearest') above the threshold. */
	static List< double[] > scaleSpaceMaxima( final float[][] stack, final int[] shape )
	{
		final int d = shape.length, ns = stack.length;
		final int[] st = strides( shape );
		final int n = stack[ 0 ].length;
		final List< double[] > out = new ArrayList<>();
		for ( int k = 0; k < ns; k++ )
			for ( int i = 0; i < n; i++ )
			{
				final float v = stack[ k ][ i ];
				if ( !( v > THRESHOLD ) )
					continue;
				final int[] c = new int[ d ];
				int rem = i;
				for ( int a = 0; a < d; a++ )
				{
					c[ a ] = rem / st[ a ];
					rem %= st[ a ];
				}
				boolean max = true;
				outer:
				for ( int dk = -1; dk <= 1; dk++ )
				{
					final float[] layer = stack[ nearest( k + dk, ns ) ];
					if ( d == 2 )
					{
						for ( int dy = -1; dy <= 1; dy++ )
							for ( int dx = -1; dx <= 1; dx++ )
								if ( layer[ nearest( c[ 0 ] + dy, shape[ 0 ] ) * st[ 0 ] + nearest( c[ 1 ] + dx, shape[ 1 ] ) ] > v )
								{
									max = false;
									break outer;
								}
					}
					else
						for ( int dz = -1; dz <= 1; dz++ )
							for ( int dy = -1; dy <= 1; dy++ )
								for ( int dx = -1; dx <= 1; dx++ )
									if ( layer[ nearest( c[ 0 ] + dz, shape[ 0 ] ) * st[ 0 ]
											+ nearest( c[ 1 ] + dy, shape[ 1 ] ) * st[ 1 ] + nearest( c[ 2 ] + dx, shape[ 2 ] ) ] > v )
									{
										max = false;
										break outer;
									}
				}
				if ( !max )
					continue;
				final double[] p = new double[ 2 + d ];
				p[ 0 ] = v;
				p[ 1 ] = k;
				for ( int a = 0; a < d; a++ )
					p[ 2 + a ] = c[ a ];
				out.add( p );
			}
		return out;
	}
}
