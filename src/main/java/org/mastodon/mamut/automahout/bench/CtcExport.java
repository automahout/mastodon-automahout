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
package org.mastodon.mamut.automahout.bench;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ShortProcessor;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Writes a Mastodon tracking result in the Cell Tracking Challenge result format: one 16-bit label image
 * per frame ({@code maskTTT.tif}) and {@code res_track.txt} ("L B E P" per track), so the CTC measures can be
 * computed on it (benchmark protocol, docs/paper/benchmark-protocol.md).
 *
 * <p><b>Tracks.</b> A CTC track is a chain of spots without a division. Each spot keeps one incoming link (from
 * an earlier frame; the nearest in time, then in space, if there are several -- a merge is not representable).
 * A spot with exactly one accepted successor in the next frame passes its label on; with two or more, each
 * successor starts a new track whose parent is this one; a spot without an accepted predecessor starts a track
 * with parent 0. A link across a gap also starts a new track whose parent is the interrupted one: a CTC track
 * must be present in every frame from its begin to its end, and this is how the CTC truth itself encodes a cell
 * that is missing for a few frames.</p>
 *
 * <p><b>Masks.</b> A spot is its ellipsoid: pixel {@code p} belongs to it when {@code (w - c)^T S^-1 (w - c) <= 1},
 * with {@code w} the pixel centre in world coordinates (via {@code pixelToWorld}), {@code c} the spot's position
 * and {@code S} its shape matrix (eigenvalues = squared semi-axes). Where ellipsoids overlap, the pixel goes to
 * the spot it is deepest inside (smallest Mahalanobis distance). A spot smaller than a pixel still gets the
 * pixel under its centre when that is free. In 2D only the x-y block of the shape is used.</p>
 *
 * <p>Begin and end frames are taken from the masks as written, so a label whose pixels were all taken by
 * neighbours is not claimed in a frame where it does not appear; a track left with no pixels at all is dropped
 * and its children become roots. A track hidden in frames between its first and last visible one (a second
 * detection of the same cell lying over it) is split there, as at a gap: the part after the hidden frames is a
 * new track whose parent is the part before, and the track's children hang under its last part. The CTC format
 * requires a track in every frame from its begin to its end; one such track makes the whole result invalid.</p>
 */
public final class CtcExport
{
	private CtcExport()
	{}

	/** What was written. */
	public static final class Result
	{
		public final int tracks;

		public final int spots;

		public final int spotsWithoutPixels;

		public final int mergesCut;

		/** Tracks split where they were hidden mid-track (each split adds one track). */
		public final int hiddenSplits;

		Result( final int tracks, final int spots, final int spotsWithoutPixels, final int mergesCut,
				final int hiddenSplits )
		{
			this.tracks = tracks;
			this.spots = spots;
			this.spotsWithoutPixels = spotsWithoutPixels;
			this.mergesCut = mergesCut;
			this.hiddenSplits = hiddenSplits;
		}

		@Override
		public String toString()
		{
			return String.format( "%d tracks, %d spots (%d with no pixel of their own), %d merge link(s) cut, "
					+ "%d track(s) split where hidden mid-track", tracks, spots, spotsWithoutPixels, mergesCut,
					hiddenSplits );
		}
	}

	/**
	 * @param dims image size in pixels, {x, y} for 2D or {x, y, z} for 3D
	 * @param pixelToWorld the level-0 source transform (pixel index to world coordinates)
	 * @param tMin first frame written
	 * @param tMax last frame written
	 */
	public static Result export( final Model model, final long[] dims, final AffineTransform3D pixelToWorld,
			final int tMin, final int tMax, final Path outDir ) throws IOException
	{
		Files.createDirectories( outDir );
		final boolean is3d = dims.length > 2 && dims[ 2 ] > 1;
		final Snapshot snap = snapshot( model, tMin, tMax );
		final List< Rec > recs = snap.recs;
		final Map< Integer, Integer > trackParent0 = snap.trackParent;
		if ( snap.nextLabel > 65535 )
			throw new IOException( "more than 65535 tracks: the CTC format stores labels as 16-bit" );

		// --- masks: no graph lock held from here on ------------------------------------------------------
		final Map< Integer, List< Rec > > byFrame0 = new HashMap<>();
		for ( final Rec r : recs )
			byFrame0.computeIfAbsent( r.t, k -> new ArrayList<>() ).add( r );
		final int nx = ( int ) dims[ 0 ], ny = ( int ) dims[ 1 ], nz = is3d ? ( int ) dims[ 2 ] : 1;

		// Which labels each frame shows: a painting pass that writes nothing. Painting depends on geometry
		// only, so the pass that writes gives every spot the same pixels, under the labels decided here.
		final Map< Integer, java.util.Set< Integer > > visible = new HashMap<>(); // label -> frames shown
		for ( int t = tMin; t <= tMax; t++ )
			for ( final int label : shown( byFrame0.getOrDefault( t, new ArrayList<>() ), nx, ny, nz, is3d,
					pixelToWorld ) )
				visible.computeIfAbsent( label, k -> new java.util.HashSet<>() ).add( t );
		final Split split = splitHidden( recs, trackParent0, visible, snap.nextLabel );
		final Map< Integer, Integer > trackParent = split.parent;
		final int nextLabel = split.nextLabel;
		if ( nextLabel > 65535 )
			throw new IOException( "more than 65535 tracks after splitting hidden ones: the CTC format stores "
					+ "labels as 16-bit" );
		final Map< Integer, List< Rec > > byFrame = new HashMap<>();
		for ( final Rec r : recs )
			byFrame.computeIfAbsent( r.t, k -> new ArrayList<>() )
					.add( r.withLabel( split.labelOf.getOrDefault( key( r.label, r.t ), r.label ) ) );

		final Map< Integer, int[] > presence = new HashMap<>(); // label -> {first, last}
		int withoutPixels = 0;
		final int digits = tMax >= 1000 ? 4 : 3;
		for ( int t = tMin; t <= tMax; t++ )
		{
			final short[][] planes = new short[ nz ][ nx * ny ];
			final float[][] depth = new float[ nz ][ nx * ny ];
			for ( final float[] d : depth )
				Arrays.fill( d, Float.POSITIVE_INFINITY );
			final List< Rec > here = byFrame.getOrDefault( t, new ArrayList<>() );
			for ( final Rec r : here )
				paint( r, planes, depth, nx, ny, nz, is3d, pixelToWorld );
			final boolean[] seen = new boolean[ nextLabel ];
			for ( final short[] plane : planes )
				for ( final short v : plane )
					seen[ v & 0xffff ] = true;
			for ( final Rec r : here )
			{
				if ( !seen[ r.label ] )
				{
					withoutPixels++;
					continue;
				}
				final int[] fl = presence.computeIfAbsent( r.label, k -> new int[] { Integer.MAX_VALUE, -1 } );
				fl[ 0 ] = Math.min( fl[ 0 ], t );
				fl[ 1 ] = Math.max( fl[ 1 ], t );
			}
			final ImageStack stack = new ImageStack( nx, ny );
			for ( final short[] plane : planes )
				stack.addSlice( new ShortProcessor( nx, ny, plane, null ) );
			final ImagePlus imp = new ImagePlus( "mask", stack );
			final String name = String.format( "mask%0" + digits + "d.tif", t );
			final FileSaver saver = new FileSaver( imp );
			final boolean ok = nz > 1 ? saver.saveAsTiffStack( outDir.resolve( name ).toString() )
					: saver.saveAsTiff( outDir.resolve( name ).toString() );
			if ( !ok )
				throw new IOException( "could not write " + outDir.resolve( name ) );
		}

		// --- res_track.txt ------------------------------------------------------------------------------
		int written = 0;
		try (PrintWriter w = new PrintWriter( Files.newBufferedWriter( outDir.resolve( "res_track.txt" ),
				StandardCharsets.US_ASCII ) ))
		{
			for ( final Map.Entry< Integer, Integer > e : trackParent.entrySet() )
			{
				final int[] fl = presence.get( e.getKey() );
				if ( fl == null )
					continue; // no pixels anywhere: dropped
				int parent = e.getValue();
				if ( parent != 0 && !presence.containsKey( parent ) )
					parent = 0;
				w.printf( "%d %d %d %d%n", e.getKey(), fl[ 0 ], fl[ 1 ], parent );
				written++;
			}
		}
		return new Result( written, recs.size(), withoutPixels, snap.mergesCut, split.splits );
	}

	private static long key( final int label, final int t )
	{
		return ( ( long ) label << 32 ) | ( t & 0xffffffffL );
	}

	/** The labels that keep at least one pixel when these spots are painted into one frame. */
	static java.util.Set< Integer > shown( final List< Rec > here, final int nx, final int ny, final int nz,
			final boolean is3d, final AffineTransform3D pixelToWorld )
	{
		final short[][] planes = new short[ nz ][ nx * ny ];
		final float[][] depth = new float[ nz ][ nx * ny ];
		for ( final float[] d : depth )
			Arrays.fill( d, Float.POSITIVE_INFINITY );
		for ( final Rec r : here )
			paint( r, planes, depth, nx, ny, nz, is3d, pixelToWorld );
		final java.util.Set< Integer > out = new java.util.HashSet<>();
		for ( final short[] plane : planes )
			for ( final short v : plane )
				if ( v != 0 )
					out.add( v & 0xffff );
		return out;
	}

	/** Labels after splitting tracks hidden mid-track, and the parents that go with them. */
	static final class Split
	{
		final Map< Long, Integer > labelOf = new HashMap<>(); // key(label, t) -> label written

		final Map< Integer, Integer > parent = new LinkedHashMap<>();

		int nextLabel;

		int splits;
	}

	/**
	 * Splits each track at frames where it is hidden between visible ones: the part after them gets a new
	 * label whose parent is the part before. Hidden frames at a track's start or end only shorten it (begin
	 * and end come from the masks). A track's children hang under its last part.
	 */
	static Split splitHidden( final List< Rec > recs, final Map< Integer, Integer > trackParent,
			final Map< Integer, java.util.Set< Integer > > visible, final int firstFreeLabel )
	{
		final Map< Integer, java.util.TreeSet< Integer > > frames = new HashMap<>();
		for ( final Rec r : recs )
			frames.computeIfAbsent( r.label, k -> new java.util.TreeSet<>() ).add( r.t );
		final Split s = new Split();
		s.nextLabel = firstFreeLabel;
		final Map< Integer, Integer > lastPart = new HashMap<>();
		final Map< Integer, Integer > parts = new LinkedHashMap<>(); // new part -> part before it
		for ( final int label : trackParent.keySet() )
		{
			final java.util.Set< Integer > shownAt = visible.getOrDefault( label, java.util.Collections.emptySet() );
			int current = label;
			boolean started = false, hidden = false;
			for ( final int t : frames.getOrDefault( label, new java.util.TreeSet<>() ) )
			{
				if ( shownAt.contains( t ) )
				{
					if ( started && hidden )
					{
						final int part = s.nextLabel++;
						parts.put( part, current );
						current = part;
						s.splits++;
					}
					started = true;
					hidden = false;
				}
				else if ( started )
					hidden = true;
				s.labelOf.put( key( label, t ), current );
			}
			lastPart.put( label, current );
		}
		for ( final Map.Entry< Integer, Integer > e : trackParent.entrySet() )
		{
			final int p = e.getValue();
			s.parent.put( e.getKey(), p != 0 && lastPart.containsKey( p ) ? lastPart.get( p ) : p );
		}
		s.parent.putAll( parts );
		return s;
	}

	/** One spot as written: frame, label, position and shape, copied out under the read lock. */
	static final class Rec
	{
		final int t;

		final int label;

		final double[] pos = new double[ 3 ];

		final double[][] cov = new double[ 3 ][ 3 ];

		Rec( final int t, final int label )
		{
			this.t = t;
			this.label = label;
		}

		Rec withLabel( final int newLabel )
		{
			if ( newLabel == label )
				return this;
			final Rec r = new Rec( t, newLabel );
			System.arraycopy( pos, 0, r.pos, 0, 3 );
			for ( int i = 0; i < 3; i++ )
				System.arraycopy( cov[ i ], 0, r.cov[ i ], 0, 3 );
			return r;
		}
	}

	private static final class Snapshot
	{
		final List< Rec > recs = new ArrayList<>();

		final Map< Integer, Integer > trackParent = new LinkedHashMap<>(); // label -> parent label

		int nextLabel = 1;

		int mergesCut;
	}

	/** Track labels and spot geometry, under the read lock; nothing slow happens while it is held. */
	private static Snapshot snapshot( final Model model, final int tMin, final int tMax )
	{
		final ModelGraph graph = model.getGraph();
		final Snapshot snap = new Snapshot();
		graph.getLock().readLock().lock();
		try
		{
			final List< Spot > spots = new ArrayList<>();
			for ( final Spot s : graph.vertices() )
				if ( s.getTimepoint() >= tMin && s.getTimepoint() <= tMax )
				{
					final Spot copy = graph.vertexRef();
					copy.refTo( s );
					spots.add( copy );
				}
			spots.sort( Comparator.comparingInt( Spot::getTimepoint ) );
			final Map< Integer, Integer > parentOf = new HashMap<>(); // spot id -> accepted predecessor id
			final Map< Integer, List< Integer > > childrenOf = new HashMap<>();
			final Map< Integer, Integer > timeOf = new HashMap<>();
			for ( final Spot s : spots )
				timeOf.put( s.getInternalPoolIndex(), s.getTimepoint() );
			int mergesCut = 0;
			final Spot other = graph.vertexRef();
			for ( final Spot s : spots )
			{
				int best = -1;
				double bestKey = Double.POSITIVE_INFINITY;
				int candidates = 0;
				for ( final Link l : s.incomingEdges() )
				{
					l.getSource( other );
					final int dt = s.getTimepoint() - other.getTimepoint();
					if ( dt <= 0 || other.getTimepoint() < tMin )
						continue;
					candidates++;
					final double key = dt * 1e12 + dist2( s, other );
					if ( key < bestKey )
					{
						bestKey = key;
						best = other.getInternalPoolIndex();
					}
				}
				if ( candidates > 1 )
					mergesCut += candidates - 1;
				if ( best >= 0 )
				{
					parentOf.put( s.getInternalPoolIndex(), best );
					childrenOf.computeIfAbsent( best, k -> new ArrayList<>() ).add( s.getInternalPoolIndex() );
				}
			}
			graph.releaseRef( other );
			final Map< Integer, Integer > labelOf = new HashMap<>();
			final Map< Integer, Integer > trackParent = new LinkedHashMap<>(); // label -> parent label
			int nextLabel = 1;
			for ( final Spot s : spots ) // time order: a predecessor is labelled before its successors
			{
				final int id = s.getInternalPoolIndex();
				final Integer p = parentOf.get( id );
				if ( p != null && labelOf.containsKey( p ) && childrenOf.get( p ).size() == 1
						&& timeOf.get( p ) == s.getTimepoint() - 1 )
					labelOf.put( id, labelOf.get( p ) );
				else
				{
					final int label = nextLabel++;
					labelOf.put( id, label );
					trackParent.put( label, p != null && labelOf.containsKey( p ) ? labelOf.get( p ) : 0 );
				}
			}
			for ( final Spot s : spots )
			{
				final Rec r = new Rec( s.getTimepoint(), labelOf.get( s.getInternalPoolIndex() ) );
				s.localize( r.pos );
				s.getCovariance( r.cov );
				snap.recs.add( r );
				graph.releaseRef( s );
			}
			snap.trackParent.putAll( trackParent );
			snap.nextLabel = nextLabel;
			snap.mergesCut = mergesCut;
			return snap;
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
	}

	private static double dist2( final Spot a, final Spot b )
	{
		double d = 0;
		for ( int i = 0; i < 3; i++ )
		{
			final double x = a.getDoublePosition( i ) - b.getDoublePosition( i );
			d += x * x;
		}
		return d;
	}

	/** Paints one spot's ellipsoid where it is deeper than what is there. */
	static void paint( final Rec r, final short[][] planes, final float[][] depth, final int nx,
			final int ny, final int nz, final boolean is3d, final AffineTransform3D pixelToWorld )
	{
		final double[][] cov = r.cov;
		final int n = is3d ? 3 : 2;
		final double[][] inv = invert( cov, n );
		final double[] c = r.pos;
		final int label = r.label;
		// Bounding box: the ellipsoid's world half-extent along axis i is sqrt(S_ii); map the box's corners.
		final double[] lo = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		final double[] hi = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		final double[] corner = new double[ 3 ], px = new double[ 3 ];
		for ( int k = 0; k < 8; k++ )
		{
			for ( int d = 0; d < 3; d++ )
			{
				final double h = d < n ? Math.sqrt( Math.max( 0, cov[ d ][ d ] ) ) : 0;
				corner[ d ] = c[ d ] + ( ( k >> d & 1 ) == 0 ? -h : h );
			}
			pixelToWorld.applyInverse( px, corner );
			for ( int d = 0; d < 3; d++ )
			{
				lo[ d ] = Math.min( lo[ d ], px[ d ] );
				hi[ d ] = Math.max( hi[ d ], px[ d ] );
			}
		}
		final int x0 = Math.max( 0, ( int ) Math.floor( lo[ 0 ] ) ), x1 = Math.min( nx - 1, ( int ) Math.ceil( hi[ 0 ] ) );
		final int y0 = Math.max( 0, ( int ) Math.floor( lo[ 1 ] ) ), y1 = Math.min( ny - 1, ( int ) Math.ceil( hi[ 1 ] ) );
		final int z0 = is3d ? Math.max( 0, ( int ) Math.floor( lo[ 2 ] ) ) : 0;
		final int z1 = is3d ? Math.min( nz - 1, ( int ) Math.ceil( hi[ 2 ] ) ) : 0;
		final double[] p = new double[ 3 ], w = new double[ 3 ];
		boolean any = false;
		for ( int z = z0; z <= z1; z++ )
			for ( int y = y0; y <= y1; y++ )
				for ( int x = x0; x <= x1; x++ )
				{
					p[ 0 ] = x;
					p[ 1 ] = y;
					p[ 2 ] = z;
					pixelToWorld.apply( p, w );
					double m = 0;
					for ( int i = 0; i < n; i++ )
						for ( int j = 0; j < n; j++ )
							m += ( w[ i ] - c[ i ] ) * inv[ i ][ j ] * ( w[ j ] - c[ j ] );
					if ( m > 1.0 )
						continue;
					final int at = y * nx + x;
					if ( m < depth[ z ][ at ] )
					{
						depth[ z ][ at ] = ( float ) m;
						planes[ z ][ at ] = ( short ) label;
						any = true;
					}
				}
		if ( !any ) // smaller than a pixel: the pixel under the centre, if nobody has it
		{
			pixelToWorld.applyInverse( px, c );
			final int x = ( int ) Math.round( px[ 0 ] ), y = ( int ) Math.round( px[ 1 ] );
			final int z = is3d ? ( int ) Math.round( px[ 2 ] ) : 0;
			if ( x >= 0 && x < nx && y >= 0 && y < ny && z >= 0 && z < nz && planes[ z ][ y * nx + x ] == 0 )
			{
				planes[ z ][ y * nx + x ] = ( short ) label;
				depth[ z ][ y * nx + x ] = 1f;
			}
		}
	}

	/** Inverse of the leading n x n block (n = 2 or 3). */
	static double[][] invert( final double[][] a, final int n )
	{
		final double[][] r = new double[ 3 ][ 3 ];
		if ( n == 2 )
		{
			final double det = a[ 0 ][ 0 ] * a[ 1 ][ 1 ] - a[ 0 ][ 1 ] * a[ 1 ][ 0 ];
			r[ 0 ][ 0 ] = a[ 1 ][ 1 ] / det;
			r[ 0 ][ 1 ] = -a[ 0 ][ 1 ] / det;
			r[ 1 ][ 0 ] = -a[ 1 ][ 0 ] / det;
			r[ 1 ][ 1 ] = a[ 0 ][ 0 ] / det;
			return r;
		}
		final double det = a[ 0 ][ 0 ] * ( a[ 1 ][ 1 ] * a[ 2 ][ 2 ] - a[ 1 ][ 2 ] * a[ 2 ][ 1 ] )
				- a[ 0 ][ 1 ] * ( a[ 1 ][ 0 ] * a[ 2 ][ 2 ] - a[ 1 ][ 2 ] * a[ 2 ][ 0 ] )
				+ a[ 0 ][ 2 ] * ( a[ 1 ][ 0 ] * a[ 2 ][ 1 ] - a[ 1 ][ 1 ] * a[ 2 ][ 0 ] );
		r[ 0 ][ 0 ] = ( a[ 1 ][ 1 ] * a[ 2 ][ 2 ] - a[ 1 ][ 2 ] * a[ 2 ][ 1 ] ) / det;
		r[ 0 ][ 1 ] = ( a[ 0 ][ 2 ] * a[ 2 ][ 1 ] - a[ 0 ][ 1 ] * a[ 2 ][ 2 ] ) / det;
		r[ 0 ][ 2 ] = ( a[ 0 ][ 1 ] * a[ 1 ][ 2 ] - a[ 0 ][ 2 ] * a[ 1 ][ 1 ] ) / det;
		r[ 1 ][ 0 ] = ( a[ 1 ][ 2 ] * a[ 2 ][ 0 ] - a[ 1 ][ 0 ] * a[ 2 ][ 2 ] ) / det;
		r[ 1 ][ 1 ] = ( a[ 0 ][ 0 ] * a[ 2 ][ 2 ] - a[ 0 ][ 2 ] * a[ 2 ][ 0 ] ) / det;
		r[ 1 ][ 2 ] = ( a[ 0 ][ 2 ] * a[ 1 ][ 0 ] - a[ 0 ][ 0 ] * a[ 1 ][ 2 ] ) / det;
		r[ 2 ][ 0 ] = ( a[ 1 ][ 0 ] * a[ 2 ][ 1 ] - a[ 1 ][ 1 ] * a[ 2 ][ 0 ] ) / det;
		r[ 2 ][ 1 ] = ( a[ 0 ][ 1 ] * a[ 2 ][ 0 ] - a[ 0 ][ 0 ] * a[ 2 ][ 1 ] ) / det;
		r[ 2 ][ 2 ] = ( a[ 0 ][ 0 ] * a[ 1 ][ 1 ] - a[ 0 ][ 1 ] * a[ 1 ][ 0 ] ) / det;
		return r;
	}
}
