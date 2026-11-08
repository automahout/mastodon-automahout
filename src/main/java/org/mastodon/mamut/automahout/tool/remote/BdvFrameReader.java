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
package org.mastodon.mamut.automahout.tool.remote;

import org.mastodon.mamut.automahout.tool.CellScale;
import org.mastodon.mamut.automahout.tool.ToolContext;

import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

/**
 * The first source of the run's viewer at full resolution, read whole through a cursor -- one pass in
 * flat order, not one random access per voxel. Holds no graph lock: it reads only the image.
 */
public final class BdvFrameReader implements Frame.Reader
{
	private final ToolContext ctx;

	public BdvFrameReader( final ToolContext ctx )
	{
		this.ctx = ctx;
	}

	@Override
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public Frame read( final int t )
	{
		final java.util.List< SourceAndConverter< ? > > sources = ctx.requireViewer().state().getSources();
		if ( sources.isEmpty() )
			return null;
		return readTyped( ( Source ) sources.get( 0 ).getSpimSource(), t );
	}

	/** Timepoint {@code t} of {@code source} at full resolution, as {@link #read} reads the viewer's first source. */
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	static Frame read( final Source< ? > source, final int t )
	{
		return readTyped( ( Source ) source, t );
	}

	@Override
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public CellScale.Sample readForCellScale( final int t )
	{
		final java.util.List< SourceAndConverter< ? > > sources = ctx.requireViewer().state().getSources();
		if ( sources.isEmpty() )
			return null;
		return readForCellScale( ( Source ) sources.get( 0 ).getSpimSource(), t );
	}

	/**
	 * A frame too large for {@link CellScale} is read from the coarsest resolution level still at least as fine
	 * as it needs, and block-averaged while it streams in: neither the full-resolution frame nor its transfer
	 * over the network is paid for. A frame that fits is read exactly as {@link #read} reads it.
	 */
	static < T extends RealType< T > > CellScale.Sample readForCellScale( final Source< T > source, final int t )
	{
		if ( !source.isPresent( t ) )
			return null;
		final long[] dims0 = dims( source.getSource( t, 0 ) );
		final double[] v0 = voxelSize( source, t, 0 );
		final double s = CellScale.coarseVoxel( dims0, v0[ 0 ], v0[ 2 ] );
		if ( Double.isNaN( s ) )
			return new CellScale.Sample( readTyped( source, t ), false, Double.NaN );
		int level = 0;
		for ( int l = 1; l < source.getNumMipmapLevels(); l++ )
			if ( voxelSize( source, t, l )[ 0 ] <= s * ( 1 + 1e-9 ) )
				level = l;
		final RandomAccessibleInterval< T > rai = source.getSource( t, level );
		final CellScale.Binner b = new CellScale.Binner( dims( rai ), CellScale.blockFactors( voxelSize( source, t, level ), s ) );
		final Cursor< T > c = Views.flatIterable( rai ).cursor();
		while ( c.hasNext() )
			b.add( c.next().getRealFloat() );
		return new CellScale.Sample( b.frame( t, voxelToWorld( source, t, level, rai ) ), true, s );
	}

	private static < T extends RealType< T > > Frame readTyped( final Source< T > source, final int t )
	{
		if ( !source.isPresent( t ) )
			return null;
		final RandomAccessibleInterval< T > rai = source.getSource( t, 0 );
		final long[] dims = dims( rai );
		final long n = dims[ 0 ] * dims[ 1 ] * dims[ 2 ];
		if ( n > Integer.MAX_VALUE )
			throw new IllegalArgumentException( "frame too large to send: " + n + " voxels" );
		final float[] data = new float[ ( int ) n ];
		final Cursor< T > c = Views.flatIterable( rai ).cursor();
		int i = 0;
		while ( c.hasNext() )
			data[ i++ ] = c.next().getRealFloat();
		return new Frame( t, dims, data, voxelToWorld( source, t, 0, rai ) );
	}

	private static long[] dims( final RandomAccessibleInterval< ? > rai )
	{
		final long[] dims = new long[ 3 ];
		for ( int d = 0; d < 3; d++ )
			dims[ d ] = d < rai.numDimensions() ? rai.dimension( d ) : 1;
		return dims;
	}

	/** Length (µm) of a voxel's edge along x, y and z at {@code level}. */
	private static double[] voxelSize( final Source< ? > source, final int t, final int level )
	{
		final AffineTransform3D a = new AffineTransform3D();
		source.getSourceTransform( t, level, a );
		final double[] v = new double[ 3 ];
		for ( int c = 0; c < 3; c++ )
			v[ c ] = Math.sqrt( a.get( 0, c ) * a.get( 0, c ) + a.get( 1, c ) * a.get( 1, c ) + a.get( 2, c ) * a.get( 2, c ) );
		return v;
	}

	private static double[] voxelToWorld( final Source< ? > source, final int t, final int level,
			final RandomAccessibleInterval< ? > rai )
	{
		final AffineTransform3D transform = new AffineTransform3D();
		source.getSourceTransform( t, level, transform );
		// The interval may not start at the origin: fold its minimum into the transform.
		final AffineTransform3D shift = new AffineTransform3D();
		shift.translate( rai.min( 0 ), rai.numDimensions() > 1 ? rai.min( 1 ) : 0, rai.numDimensions() > 2 ? rai.min( 2 ) : 0 );
		transform.concatenate( shift );
		return transform.getRowPackedCopy();
	}
}
