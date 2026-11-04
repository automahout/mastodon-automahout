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
package org.mastodon.mamut.automahout.tool.impl;

import org.mastodon.mamut.automahout.tool.ToolRole;
import org.scijava.plugin.Plugin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ClassicalDetector;
import org.mastodon.mamut.automahout.tool.ImageProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;

import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;

/**
 * Scans the full 3-D image volume (within the screen-projected visible region)
 * to find up to {@code maxCount} local intensity maxima, separated by a
 * minimum suppression radius in screen pixels.
 *
 * <h3>Threading model</h3>
 * <p>The two-phase API separates EDT work from background work:</p>
 * <ol>
 *   <li>{@link #captureViewerState(ViewerPanel, int)} — must be called on the
 *       Swing EDT. Reads the viewer transform, source transform, and image RAI
 *       into an immutable {@link ScanState} snapshot.</li>
 *   <li>{@link #scan(ScanState, int, int, int, double)} — safe to call on any
 *       thread. Performs the expensive 3-D voxel iteration using only the
 *       pre-captured state, without touching the viewer.</li>
 * </ol>
 * <p>{@link #findMaxima(ViewerPanel, int, int, int, int, double)} combines
 * both phases synchronously (still blocks the EDT) and is kept for backward
 * compatibility.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>3-D voxel scan</b> — iterate over every voxel
 *       {@code (vx, vy, vz)} in the image with configurable XY and Z strides.
 *       For each voxel, project it to screen space using the combined
 *       {@code V ∘ S} transform. Only voxels whose screen projection falls
 *       within the current viewport are sampled.</li>
 *   <li><b>Sort</b> — candidates are ranked by intensity (descending).</li>
 *   <li><b>Greedy NMS</b> — accept the brightest candidate, reject all
 *       within {@code suppressionRadiusPx} screen-pixels. Screen-space NMS
 *       naturally prevents redundant spots at the same visible XY position
 *       even when they differ in Z.</li>
 * </ol>
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_STRIDE}</td><td>int</td><td>{@value #DEFAULT_STRIDE}</td>
 *       <td>Sampling stride in voxels along X and Y</td></tr>
 *   <tr><td>{@value #PARAM_STRIDE_Z}</td><td>int</td><td>{@value #DEFAULT_STRIDE_Z}</td>
 *       <td>Sampling stride in voxels along Z</td></tr>
 *   <tr><td>{@value #PARAM_MAX_COUNT}</td><td>int</td><td>{@value #DEFAULT_MAX_COUNT}</td>
 *       <td>Maximum number of local maxima to return</td></tr>
 *   <tr><td>{@value #PARAM_SUPPRESSION_PX}</td><td>double</td>
 *       <td>{@value #DEFAULT_SUPPRESSION_PX}</td>
 *       <td>Minimum screen-pixel distance between two accepted maxima (NMS)</td></tr>
 * </table>
 */
@Plugin( type = AutomahoutTool.class )
public class FindMaximaTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.DETECT;
	}


	public static final String ID = "find.maxima";

	/**
	 * Context key for the single brightest maxima ({@code double[3]} world
	 * position). Compatible with {@link SpotEditTool#PARAM_POSITION_FROM_CONTEXT}.
	 */
	public static final String RESULT_KEY = "findMaxima.worldPos";

	/**
	 * Context key for the full list of maxima ({@code List<double[]>} of world
	 * positions in order of decreasing intensity).
	 */
	public static final String RESULT_KEY_LIST = "findMaxima.worldPositions";

	/** Sampling stride in voxels along X and Y. */
	public static final String PARAM_STRIDE = "stride";

	/** Sampling stride in voxels along Z. */
	public static final String PARAM_STRIDE_Z = "strideZ";

	/** Maximum number of local maxima to return. */
	public static final String PARAM_MAX_COUNT = "maxCount";

	/** Minimum screen-pixel NMS separation radius. */
	public static final String PARAM_SUPPRESSION_PX = "suppressionPx";

	/**
	 * Mipmap (resolution) level to use for scanning.
	 * {@code -1} (default) selects the coarsest available level automatically,
	 * which is dramatically faster for multi-resolution BDV sources.
	 * {@code 0} forces the finest (full) resolution.
	 */
	public static final String PARAM_MIPMAP_LEVEL = "mipmapLevel";

	static final int DEFAULT_STRIDE = 4;

	static final int DEFAULT_STRIDE_Z = 1;

	static final int DEFAULT_MAX_COUNT = 10;

	static final double DEFAULT_SUPPRESSION_PX = 50.0;

	/** Default mipmap level: {@code -1} = auto-select coarsest. */
	static final int DEFAULT_MIPMAP_LEVEL = -1;

	// -----------------------------------------------------------------------
	// AutomahoutTool interface

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Scan the full 3-D image volume (within the visible viewport) for the "
				+ DEFAULT_MAX_COUNT + " brightest local maxima. "
				+ "Sample every '" + PARAM_STRIDE + "' voxels in XY (default "
				+ DEFAULT_STRIDE + ") and '" + PARAM_STRIDE_Z + "' voxels in Z (default "
				+ DEFAULT_STRIDE_Z + "). "
				+ "NMS radius: '" + PARAM_SUPPRESSION_PX + "' screen pixels (default "
				+ DEFAULT_SUPPRESSION_PX + "). "
				+ "Results stored in context under '" + RESULT_KEY_LIST + "' and '" + RESULT_KEY + "'.";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final ViewerPanel viewer = context.requireViewer();
		final int timepoint = viewer.state().getCurrentTimepoint();
		final int strideXY = toInt( params.getOrDefault( PARAM_STRIDE, DEFAULT_STRIDE ) );
		final int strideZ = toInt( params.getOrDefault( PARAM_STRIDE_Z, DEFAULT_STRIDE_Z ) );
		final int maxCount = toInt( params.getOrDefault( PARAM_MAX_COUNT, DEFAULT_MAX_COUNT ) );
		final double suppressionPx =
				toDouble( params.getOrDefault( PARAM_SUPPRESSION_PX, DEFAULT_SUPPRESSION_PX ) );

		final int mipmapLevel = toInt( params.getOrDefault( PARAM_MIPMAP_LEVEL, DEFAULT_MIPMAP_LEVEL ) );

		// NOTE: execute() is called on the EDT by ToolOrchestrator, so both
		// phases run synchronously here. For large volumes this may be slow;
		// for interactive use, run the scan phase off the EDT (e.g. in a SwingWorker).
		final ScanState state = captureViewerState( viewer, timepoint, mipmapLevel );
		if ( !state.isValid() )
			return "no image data at timepoint " + timepoint;

		final List< double[] > maxima = scan( state, maxCount, strideXY, strideZ, suppressionPx );
		if ( maxima.isEmpty() )
			return "no visible image data at timepoint " + timepoint;

		context.setSharedData( RESULT_KEY, maxima.get( 0 ) );
		context.setSharedData( RESULT_KEY_LIST, maxima );

		return String.format( "found %d maxima in 3-D (brightest at world %.2f, %.2f, %.2f)",
				maxima.size(),
				maxima.get( 0 )[ 0 ], maxima.get( 0 )[ 1 ], maxima.get( 0 )[ 2 ] );
	}

	// -----------------------------------------------------------------------
	// Two-phase public API

	/**
	 * Snapshot of viewer state required for a 3-D scan.
	 *
	 * <p>Instances are created by {@link FindMaximaTool#captureViewerState}
	 * on the EDT and can then be passed to {@link FindMaximaTool#scan} on any
	 * thread.</p>
	 */
	public static final class ScanState
	{
		private final RandomAccessibleInterval< ? > rai;

		private final AffineTransform3D sourceTransform;

		private final AffineTransform3D viewerTransform;

		public final int screenW;

		public final int screenH;

		/** The actual mipmap level that was selected for this scan. */
		public final int mipmapLevel;

		/** Total number of mipmap levels available in the source. */
		public final int numMipmapLevels;

		/** Voxels in the scanned image, or 0 when this state is not valid. */
		public long voxelCount()
		{
			if ( !valid || rai == null )
				return 0;
			long n = 1;
			for ( int d = 0; d < rai.numDimensions(); d++ )
				n *= rai.dimension( d );
			return n;
		}

		private final boolean valid;

		private ScanState( final boolean valid )
		{
			this.valid = valid;
			this.rai = null;
			this.sourceTransform = null;
			this.viewerTransform = null;
			this.screenW = 0;
			this.screenH = 0;
			this.mipmapLevel = 0;
			this.numMipmapLevels = 0;
		}

		private ScanState(
				final RandomAccessibleInterval< ? > rai,
				final AffineTransform3D sourceTransform,
				final AffineTransform3D viewerTransform,
				final int screenW,
				final int screenH,
				final int mipmapLevel,
				final int numMipmapLevels )
		{
			this.valid = true;
			this.rai = rai;
			this.sourceTransform = sourceTransform;
			this.viewerTransform = viewerTransform;
			this.screenW = screenW;
			this.screenH = screenH;
			this.mipmapLevel = mipmapLevel;
			this.numMipmapLevels = numMipmapLevels;
		}

		/** Returns {@code false} if the source was absent at the timepoint. */
		public boolean isValid()
		{
			return valid;
		}
	}

	/**
	 * Samples the image intensity at a world position using the SAME captured (typically coarse-mipmap)
	 * interval a {@link #scanImageSpaceMaxima} scan already used for {@code state} — avoiding a fresh,
	 * much more expensive full-resolution live-source fetch per sample, which is what routing through a
	 * BDV {@code Source} per point would otherwise cost. That difference matters here specifically
	 * because a per-candidate contrast check (see {@code ElephantTool#estimateExpectedCellCount}) can run
	 * this thousands of times per timepoint (up to {@code maxCount} candidates × several shell samples
	 * each) — at full resolution that was measured to add ~5 minutes per completeness computation; reused
	 * against the already-loaded coarse interval it is effectively free.
	 *
	 * @return the intensity, or {@code 0.0} if {@code state} is invalid or {@code worldPos} falls outside
	 *         the captured interval
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public static double sampleIntensity( final ScanState state, final double[] worldPos )
	{
		if ( !state.isValid() )
			return 0.0;
		final double[] voxel = new double[ 3 ];
		state.sourceTransform.applyInverse( voxel, worldPos );
		final long[] voxelL = {
				Math.round( voxel[ 0 ] ), Math.round( voxel[ 1 ] ), Math.round( voxel[ 2 ] ) };
		final RandomAccessibleInterval rai = state.rai;
		for ( int d = 0; d < 3; d++ )
			if ( voxelL[ d ] < rai.min( d ) || voxelL[ d ] > rai.max( d ) )
				return 0.0;
		final RandomAccess ra = rai.randomAccess();
		ra.setPosition( voxelL );
		return ( ( RealType ) ra.get() ).getRealDouble();
	}

	/**
	 * Local contrast (centre intensity minus the mean of a background shell {@code ~2.5x radius} away),
	 * sampled from the same captured {@code state} as {@link #sampleIntensity(ScanState, double[])} — the
	 * brightness-invariant signal that distinguishes a real local peak from a globally-bright-but-flat
	 * region (halo, out-of-focus haze). Mirrors {@code AutoCorrectOrchestrator#sampleContrast}'s shell-
	 * sampling geometry, but reused here for its far lower per-call cost — see that method's docs.
	 */
	public static double sampleContrast( final ScanState state, final double[] worldPos, final double radius )
	{
		final double centre = sampleIntensity( state, worldPos );
		final double d = Math.max( radius * 2.5, 2.0 );
		double sum = 0;
		int n = 0;
		final double[] p = new double[ 3 ];
		for ( int axis = 0; axis < 3; axis++ )
		{
			for ( int sign = -1; sign <= 1; sign += 2 )
			{
				p[ 0 ] = worldPos[ 0 ];
				p[ 1 ] = worldPos[ 1 ];
				p[ 2 ] = worldPos[ 2 ];
				p[ axis ] += sign * d;
				sum += sampleIntensity( state, p );
				n++;
			}
		}
		return centre - ( n > 0 ? sum / n : 0.0 );
	}

	/**
	 * Captures the viewer state needed for a 3-D scan, automatically selecting
	 * the <b>coarsest available mipmap level</b> for maximum scan speed.
	 *
	 * <p>For multi-resolution BDV sources (N5, HDF5, Zarr) the coarsest level
	 * is typically 4–8× smaller in each dimension than the full-resolution
	 * data, resulting in orders-of-magnitude faster scans. For single-resolution
	 * sources the only available level (0) is used.</p>
	 *
	 * <p><b>Must be called on the Swing EDT.</b></p>
	 *
	 * @param viewer    the active BDV viewer panel
	 * @param timepoint image timepoint to prepare
	 * @return an immutable snapshot, or an invalid state if no source is present
	 */
	public static ScanState captureViewerState( final ViewerPanel viewer, final int timepoint )
	{
		return captureViewerState( viewer, timepoint, DEFAULT_MIPMAP_LEVEL );
	}

	/**
	 * Captures the viewer state at an explicit mipmap level.
	 *
	 * @param viewer         the active BDV viewer panel
	 * @param timepoint      image timepoint to prepare
	 * @param requestedLevel mipmap level ({@code -1} = auto/coarsest,
	 *                       {@code 0} = finest/full resolution)
	 * @return an immutable snapshot, or an invalid state if no source is present
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public static ScanState captureViewerState(
			final ViewerPanel viewer,
			final int timepoint,
			final int requestedLevel )
	{
		final List< SourceAndConverter< ? > > allSources = viewer.state().getSources();
		if ( allSources.isEmpty() )
			return new ScanState( false );

		final Source src = allSources.get( 0 ).getSpimSource();
		if ( !src.isPresent( timepoint ) )
			return new ScanState( false );

		final int numLevels = src.getNumMipmapLevels();
		final int level;
		if ( requestedLevel < 0 )
			level = numLevels - 1; // coarsest
		else
			level = Math.min( requestedLevel, numLevels - 1 );

		final AffineTransform3D viewerTransform = viewer.state().getViewerTransform();
		final AffineTransform3D sourceTransform = new AffineTransform3D();
		src.getSourceTransform( timepoint, level, sourceTransform );
		final RandomAccessibleInterval< ? > rai = src.getSource( timepoint, level );

		return new ScanState( rai, sourceTransform, viewerTransform,
				viewer.getWidth(), viewer.getHeight(), level, numLevels );
	}

	/**
	 * Performs the 3-D voxel scan using pre-captured viewer state.
	 *
	 * <p><b>Thread-safe</b> — may be called from any thread (e.g. a
	 * {@code SwingWorker} background thread). Does not access the viewer.</p>
	 *
	 * @param state              pre-captured viewer state from
	 *                           {@link #captureViewerState}
	 * @param maxCount           maximum number of maxima to return
	 * @param strideXY           voxel stride along X and Y
	 * @param strideZ            voxel stride along Z
	 * @param suppressionRadiusPx  screen-pixel NMS radius
	 * @return list of world positions ({@code double[3]}), ordered by
	 *         decreasing intensity; empty if no visible voxels exist
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public static List< double[] > scan(
			final ScanState state,
			final int maxCount,
			final int strideXY,
			final int strideZ,
			final double suppressionRadiusPx )
	{
		if ( !state.isValid() )
			return Collections.emptyList();

		// ---- combined transform: voxel → screen ----------------------------
		final AffineTransform3D voxelToScreen = state.viewerTransform.copy();
		voxelToScreen.concatenate( state.sourceTransform );

		// ---- image bounds --------------------------------------------------
		final long vMinX = state.rai.min( 0 ), vMaxX = state.rai.max( 0 );
		final long vMinY = state.rai.min( 1 ), vMaxY = state.rai.max( 1 );
		final long vMinZ = state.rai.min( 2 ), vMaxZ = state.rai.max( 2 );

		final RandomAccess ra = ( ( RandomAccessibleInterval ) state.rai ).randomAccess();

		// ---- 3-D voxel scan ------------------------------------------------
		final List< Sample > samples = new ArrayList<>();
		final double[] vp = new double[ 3 ]; // voxel pos
		final double[] sp = new double[ 3 ]; // screen pos
		final double[] wp = new double[ 3 ]; // world pos

		final int sz = Math.max( 1, strideZ );
		final int sxy = Math.max( 1, strideXY );

		for ( long vz = vMinZ; vz <= vMaxZ; vz += sz )
		{
			for ( long vy = vMinY; vy <= vMaxY; vy += sxy )
			{
				for ( long vx = vMinX; vx <= vMaxX; vx += sxy )
				{
					vp[ 0 ] = vx;
					vp[ 1 ] = vy;
					vp[ 2 ] = vz;
					voxelToScreen.apply( vp, sp );

					// Keep only voxels visible in the current viewport.
					if ( sp[ 0 ] < 0 || sp[ 0 ] >= state.screenW
							|| sp[ 1 ] < 0 || sp[ 1 ] >= state.screenH )
						continue;

					ra.setPosition( vx, 0 );
					ra.setPosition( vy, 1 );
					ra.setPosition( vz, 2 );
					final double intensity = ( ( RealType ) ra.get() ).getRealDouble();

					state.sourceTransform.apply( vp, wp );
					samples.add( new Sample( sp[ 0 ], sp[ 1 ], wp.clone(), intensity ) );
				}
			}
		}

		if ( samples.isEmpty() )
			return Collections.emptyList();

		// ---- sort by intensity (descending) --------------------------------
		samples.sort( Comparator.comparingDouble( ( Sample s ) -> s.intensity ).reversed() );

		// ---- greedy non-maximum suppression (screen space) -----------------
		final List< double[] > result = new ArrayList<>( maxCount );
		final boolean[] suppressed = new boolean[ samples.size() ];
		final double radiusSq = suppressionRadiusPx * suppressionRadiusPx;

		for ( int i = 0; i < samples.size() && result.size() < maxCount; i++ )
		{
			if ( suppressed[ i ] )
				continue;

			final Sample peak = samples.get( i );
			result.add( peak.worldPos.clone() );

			for ( int j = i + 1; j < samples.size(); j++ )
			{
				if ( !suppressed[ j ] )
				{
					final double dx = peak.sx - samples.get( j ).sx;
					final double dy = peak.sy - samples.get( j ).sy;
					if ( dx * dx + dy * dy <= radiusSq )
						suppressed[ j ] = true;
				}
			}
		}

		return result;
	}

	/**
	 * Finds bright local maxima across the <b>entire image volume</b> (not just the visible
	 * viewport), for use as model-free seed annotations when the detection model is untrained.
	 *
	 * <p>Unlike {@link #scan}, this ignores the viewer transform / screen bounds and performs
	 * non-maximum suppression in <b>world coordinates</b> (µm), and only considers voxels whose
	 * intensity exceeds the {@code intensityPercentile} of all sampled voxels — so only the
	 * brightest, most cell-like peaks survive.</p>
	 *
	 * <p><b>Thread-safe</b> — takes a pre-captured {@link ScanState}; does not touch the viewer.</p>
	 *
	 * @param state               pre-captured image state from {@link #captureViewerState}
	 * @param maxCount            maximum number of seeds to return
	 * @param stride              voxel stride along all axes (coarse scan is fine for seeds)
	 * @param worldNmsRadius      suppression radius in world units (µm)
	 * @param intensityPercentile fraction in [0,1); voxels below this intensity percentile are ignored
	 * @return world positions ({@code double[3]}) of seed maxima, brightest first
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	/**
	 * Cells found by measuring the plane, via {@link ClassicalDetector} — positions and sizes, not
	 * just the brightest N voxels.
	 *
	 * <p>Why this is here rather than another intensity-maxima scan. The cold-start bootstrap seeds a
	 * fixed number of maxima — 20, or 30 — and trains on those. On Fluo-N2DL-HeLa t=0 the truth holds
	 * 43 cells, so the model is shown half of them and no statement about the rest; it then detects
	 * 171, and the run is abandoned for over-detecting. Measured against silver truth, the classical
	 * detector finds essentially all of them (F1 92.8%, six over-segmentations in 2876 cells), which
	 * is a label set worth training on.</p>
	 *
	 * <p>2-D only, deliberately: measured on Fluo-N3DH-CE the same method reaches F1 76.7% with 209
	 * over-segmentations in 671 cells, because nearly every nucleus touches another. A volume returns
	 * empty and the caller keeps its existing seeding.</p>
	 *
	 * @return {@code {x, y, z, radius}} in world units, empty if this is not a single-plane image
	 */
	public static List< double[] > scanClassicalDetections( final ScanState state, final int maxCount )
	{
		if ( !state.isValid() )
			return Collections.emptyList();
		final long w = state.rai.dimension( 0 ), h = state.rai.dimension( 1 );
		if ( state.rai.dimension( 2 ) != 1 || w <= 0 || h <= 0 || w * h > 40_000_000L )
			return Collections.emptyList();

		final int iw = ( int ) w, ih = ( int ) h;
		final double[] plane = new double[ iw * ih ];
		final RandomAccess ra = ( ( RandomAccessibleInterval ) state.rai ).randomAccess();
		final long z = state.rai.min( 2 );
		for ( int y = 0; y < ih; y++ )
		{
			for ( int x = 0; x < iw; x++ )
			{
				ra.setPosition( state.rai.min( 0 ) + x, 0 );
				ra.setPosition( state.rai.min( 1 ) + y, 1 );
				ra.setPosition( z, 2 );
				plane[ y * iw + x ] = ( ( RealType ) ra.get() ).getRealDouble();
			}
		}

		final double[] sorted = plane.clone();
		java.util.Arrays.sort( sorted );
		final double median = sorted[ sorted.length / 2 ];
		final double[] dev = new double[ sorted.length ];
		for ( int i = 0; i < sorted.length; i++ )
			dev[ i ] = Math.abs( sorted[ i ] - median );
		java.util.Arrays.sort( dev );
		double sigma = 1.4826 * dev[ dev.length / 2 ];
		if ( !( sigma > 0 ) )
			// Quantised data with a flat background — the same collapse ImageProfile guards against.
			sigma = 0.5 * Math.max( 1e-9, ImageProfile.quantisationStep( sorted, median ) );
		if ( !( sigma > 0 ) )
			return Collections.emptyList();

		// Voxel -> world scale along x, to convert the detector's pixel radii and to size its minimum.
		final double[] o0 = new double[ 3 ], o1 = new double[ 3 ];
		state.sourceTransform.apply( new double[] { 0, 0, 0 }, o0 );
		state.sourceTransform.apply( new double[] { 1, 0, 0 }, o1 );
		final double voxelWorld = Math.max( 1e-9, Math.sqrt(
				( o1[ 0 ] - o0[ 0 ] ) * ( o1[ 0 ] - o0[ 0 ] ) + ( o1[ 1 ] - o0[ 1 ] ) * ( o1[ 1 ] - o0[ 1 ] )
						+ ( o1[ 2 ] - o0[ 2 ] ) * ( o1[ 2 ] - o0[ 2 ] ) ) );

		final List< ClassicalDetector.Detection > found = ClassicalDetector.detect( plane, iw, ih,
				median + ClassicalDetector.LEVEL_SIGMA * sigma, CLASSICAL_MIN_AREA_VOXELS,
				CLASSICAL_MIN_PEAK_DISTANCE_VOXELS );
		// Brightest-first is meaningless here; biggest-first is what a cap should keep.
		found.sort( ( a, b ) -> Double.compare( b.area, a.area ) );

		final List< double[] > out = new ArrayList<>();
		final double[] vp = new double[ 3 ], wp = new double[ 3 ];
		for ( final ClassicalDetector.Detection d : found )
		{
			if ( maxCount > 0 && out.size() >= maxCount )
				break;
			vp[ 0 ] = state.rai.min( 0 ) + d.x;
			vp[ 1 ] = state.rai.min( 1 ) + d.y;
			vp[ 2 ] = z;
			state.sourceTransform.apply( vp, wp );
			out.add( new double[] { wp[ 0 ], wp[ 1 ], wp[ 2 ], d.radius * voxelWorld } );
		}
		return out;
	}

	/** Smallest region the classical seeder keeps, in voxels of the level it reads. */
	private static final int CLASSICAL_MIN_AREA_VOXELS = 50;

	/** Closest two classical seeds may be, in voxels of the level it reads. */
	private static final int CLASSICAL_MIN_PEAK_DISTANCE_VOXELS = 12;

	public static List< double[] > scanImageSpaceMaxima(
			final ScanState state,
			final int maxCount,
			final int stride,
			final double worldNmsRadius,
			final double intensityPercentile )
	{
		if ( !state.isValid() )
			return Collections.emptyList();

		final long vMinX = state.rai.min( 0 ), vMaxX = state.rai.max( 0 );
		final long vMinY = state.rai.min( 1 ), vMaxY = state.rai.max( 1 );
		final long vMinZ = state.rai.min( 2 ), vMaxZ = state.rai.max( 2 );
		final RandomAccess ra = ( ( RandomAccessibleInterval ) state.rai ).randomAccess();

		final int s = Math.max( 1, stride );
		final List< Sample > samples = new ArrayList<>();
		final double[] vp = new double[ 3 ];
		final double[] wp = new double[ 3 ];
		for ( long vz = vMinZ; vz <= vMaxZ; vz += s )
			for ( long vy = vMinY; vy <= vMaxY; vy += s )
				for ( long vx = vMinX; vx <= vMaxX; vx += s )
				{
					ra.setPosition( vx, 0 );
					ra.setPosition( vy, 1 );
					ra.setPosition( vz, 2 );
					final double intensity = ( ( RealType ) ra.get() ).getRealDouble();
					vp[ 0 ] = vx;
					vp[ 1 ] = vy;
					vp[ 2 ] = vz;
					state.sourceTransform.apply( vp, wp );
					samples.add( new Sample( 0, 0, wp.clone(), intensity ) );
				}
		if ( samples.isEmpty() )
			return Collections.emptyList();

		// Intensity threshold at the requested percentile — keep only the brightest voxels.
		final double[] intensities = new double[ samples.size() ];
		for ( int i = 0; i < samples.size(); i++ )
			intensities[ i ] = samples.get( i ).intensity;
		java.util.Arrays.sort( intensities );
		final double pct = Math.max( 0.0, Math.min( 0.999999, intensityPercentile ) );
		final double threshold = intensities[ ( int ) ( pct * ( intensities.length - 1 ) ) ];

		final List< Sample > bright = new ArrayList<>();
		for ( final Sample sample : samples )
			if ( sample.intensity >= threshold )
				bright.add( sample );
		bright.sort( Comparator.comparingDouble( ( Sample x ) -> x.intensity ).reversed() );

		// Greedy non-maximum suppression in world space.
		final List< double[] > result = new ArrayList<>( maxCount );
		final boolean[] suppressed = new boolean[ bright.size() ];
		final double radiusSq = worldNmsRadius * worldNmsRadius;
		for ( int i = 0; i < bright.size() && result.size() < maxCount; i++ )
		{
			if ( suppressed[ i ] )
				continue;
			final Sample peak = bright.get( i );
			result.add( peak.worldPos.clone() );
			for ( int j = i + 1; j < bright.size(); j++ )
			{
				if ( suppressed[ j ] )
					continue;
				final double dx = peak.worldPos[ 0 ] - bright.get( j ).worldPos[ 0 ];
				final double dy = peak.worldPos[ 1 ] - bright.get( j ).worldPos[ 1 ];
				final double dz = peak.worldPos[ 2 ] - bright.get( j ).worldPos[ 2 ];
				if ( dx * dx + dy * dy + dz * dz <= radiusSq )
					suppressed[ j ] = true;
			}
		}
		return result;
	}

	/**
	 * Convenience method combining {@link #captureViewerState} and
	 * {@link #scan} in a single synchronous call.
	 *
	 * <p><b>Must be called on the EDT</b> and blocks during the scan.
	 * For interactive use, prefer calling the two phases separately with a
	 * {@code SwingWorker}.</p>
	 */
	public static List< double[] > findMaxima(
			final ViewerPanel viewer,
			final int timepoint,
			final int maxCount,
			final int strideXY,
			final int strideZ,
			final double suppressionRadiusPx )
	{
		return scan( captureViewerState( viewer, timepoint ),
				maxCount, strideXY, strideZ, suppressionRadiusPx );
	}

	// -----------------------------------------------------------------------

	private static final class Sample
	{
		final double sx, sy;

		final double[] worldPos;

		final double intensity;

		Sample( final double sx, final double sy,
				final double[] worldPos, final double intensity )
		{
			this.sx = sx;
			this.sy = sy;
			this.worldPos = worldPos;
			this.intensity = intensity;
		}
	}

	// -----------------------------------------------------------------------

	private static int toInt( final Object value )
	{
		return ( ( Number ) value ).intValue();
	}

	private static double toDouble( final Object value )
	{
		return ( ( Number ) value ).doubleValue();
	}
}
