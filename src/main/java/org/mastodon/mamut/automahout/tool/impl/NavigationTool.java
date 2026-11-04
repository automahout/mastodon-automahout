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
import java.util.Map;

import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ToolContext;

import bdv.viewer.ViewerPanel;
import bdv.viewer.animate.AbstractTransformAnimator;
import bdv.viewer.animate.TranslationAnimator;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Navigates the BDV viewer by panning or zooming.
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_TYPE}</td><td>String</td><td>{@code "pan"}</td>
 *       <td>{@code "pan"}, {@code "zoom"}, or {@code "goto"}</td></tr>
 *   <tr><td>{@value #PARAM_DX}</td><td>double</td><td>0</td>
 *       <td>Pan offset in screen pixels along X (pan only)</td></tr>
 *   <tr><td>{@value #PARAM_DY}</td><td>double</td><td>0</td>
 *       <td>Pan offset in screen pixels along Y (pan only)</td></tr>
 *   <tr><td>{@value #PARAM_ZOOM_FACTOR}</td><td>double</td><td>1.0</td>
 *       <td>Scale factor: &gt;1 zooms in, &lt;1 zooms out (zoom only)</td></tr>
 *   <tr><td>{@value #PARAM_WORLD_X}</td><td>double</td><td>0</td>
 *       <td>World X coordinate to centre on screen (goto only)</td></tr>
 *   <tr><td>{@value #PARAM_WORLD_Y}</td><td>double</td><td>0</td>
 *       <td>World Y coordinate to centre on screen (goto only)</td></tr>
 *   <tr><td>{@value #PARAM_WORLD_Z}</td><td>double</td><td>0</td>
 *       <td>World Z coordinate to centre on screen (goto only)</td></tr>
 *   <tr><td>{@value #PARAM_ANIMATION_MS}</td><td>long</td><td>500</td>
 *       <td>Animation duration in milliseconds</td></tr>
 * </table>
 */
@Plugin( type = AutomahoutTool.class )
public class NavigationTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.VIEW;
	}


	public static final String ID = "nav";

	/** {@code "pan"} or {@code "zoom"}. Defaults to {@code "pan"}. */
	public static final String PARAM_TYPE = "type";

	/** Relative pan offset in screen pixels along X. */
	public static final String PARAM_DX = "dx";

	/** Relative pan offset in screen pixels along Y. */
	public static final String PARAM_DY = "dy";

	/**
	 * Zoom scale factor applied around the screen centre.
	 * Values &gt; 1 zoom in; values in (0, 1) zoom out.
	 */
	public static final String PARAM_ZOOM_FACTOR = "zoomFactor";

	/** World X coordinate to centre on screen (goto only). */
	public static final String PARAM_WORLD_X = "worldX";

	/** World Y coordinate to centre on screen (goto only). */
	public static final String PARAM_WORLD_Y = "worldY";

	/** World Z coordinate to centre on screen (goto only). */
	public static final String PARAM_WORLD_Z = "worldZ";

	/** Animation duration in milliseconds for pan, zoom, and goto operations. */
	public static final String PARAM_ANIMATION_MS = "animationMs";

	private static final long DEFAULT_ANIMATION_MS = 500L;

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Navigate the BDV viewer. "
				+ "Pan by relative (dx, dy) screen-pixel offsets ('pan'); "
				+ "zoom by a scale factor centred on the screen ('zoom'); "
				+ "or smoothly pan to centre a world position on screen ('goto').";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final ViewerPanel viewer = context.getOrOpenViewer();
		final String type = ( String ) params.getOrDefault( PARAM_TYPE, "pan" );

		switch ( type )
		{
		case "pan":
		{
			final double dx = toDouble( params.getOrDefault( PARAM_DX, 0.0 ) );
			final double dy = toDouble( params.getOrDefault( PARAM_DY, 0.0 ) );
			final long animMs = toLong( params.getOrDefault( PARAM_ANIMATION_MS, DEFAULT_ANIMATION_MS ) );
			pan( viewer, dx, dy, animMs );
			return String.format( "pan dx=%.1f dy=%.1f animationMs=%d", dx, dy, animMs );
		}
		case "zoom":
		{
			final double factor = toDouble( params.getOrDefault( PARAM_ZOOM_FACTOR, 1.0 ) );
			final long animMs = toLong( params.getOrDefault( PARAM_ANIMATION_MS, DEFAULT_ANIMATION_MS ) );
			zoom( viewer, factor, animMs );
			return String.format( "zoom factor=%.3f animationMs=%d", factor, animMs );
		}
		case "goto":
		{
			final double worldX = toDouble( params.getOrDefault( PARAM_WORLD_X, 0.0 ) );
			final double worldY = toDouble( params.getOrDefault( PARAM_WORLD_Y, 0.0 ) );
			final double worldZ = toDouble( params.getOrDefault( PARAM_WORLD_Z, 0.0 ) );
			final long animMs = toLong( params.getOrDefault( PARAM_ANIMATION_MS, DEFAULT_ANIMATION_MS ) );
			navigateTo( viewer, worldX, worldY, worldZ, animMs );
			return String.format( "goto world (%.2f, %.2f, %.2f) animationMs=%d",
					worldX, worldY, worldZ, animMs );
		}
		default:
			throw new IllegalArgumentException(
					"Unknown navigation type: '" + type + "'. Use 'pan', 'zoom', or 'goto'." );
		}
	}

	// -----------------------------------------------------------------------

	/**
	 * Smoothly navigates the viewer so that the given world position is centred
	 * on screen <em>and</em> becomes the current focal plane (screen z = 0).
	 * The zoom level and rotation are unchanged; only the 3D translation
	 * component of the viewer transform is animated.
	 *
	 * <p>A public static utility, so that code can call it directly without going through the tool system.</p>
	 *
	 * @param viewer   the target viewer panel (must be on the EDT)
	 * @param worldX   world X coordinate to centre and focus
	 * @param worldY   world Y coordinate to centre and focus
	 * @param worldZ   world Z coordinate to centre and focus
	 * @param animMs   animation duration in milliseconds
	 */
	public static void navigateTo(
			final ViewerPanel viewer,
			final double worldX,
			final double worldY,
			final double worldZ,
			final long animMs )
	{
		final AffineTransform3D current = viewer.state().getViewerTransform();
		final double cx = viewer.getWidth() / 2.0;
		final double cy = viewer.getHeight() / 2.0;

		// Find where the world point currently projects to on screen.
		final double[] screenPos = new double[ 3 ];
		current.apply( new double[] { worldX, worldY, worldZ }, screenPos );

		// Compute the translation that moves screenPos to the screen centre
		// AND brings the target to the focal plane (screen z = 0).
		final double[] targetTranslation = {
			current.get( 0, 3 ) + ( cx - screenPos[ 0 ] ),
			current.get( 1, 3 ) + ( cy - screenPos[ 1 ] ),
			current.get( 2, 3 ) - screenPos[ 2 ]
		};

		viewer.setTransformAnimator( new TranslationAnimator( current, targetTranslation, animMs ) );
		viewer.requestRepaint();
	}

	// -----------------------------------------------------------------------

	/**
	 * Smoothly translates the viewer by {@code (dx, dy)} screen pixels using
	 * a {@link TranslationAnimator}.
	 */
	private static void pan( final ViewerPanel viewer, final double dx, final double dy, final long animMs )
	{
		final AffineTransform3D current = viewer.state().getViewerTransform();
		final double[] target = {
			current.get( 0, 3 ) + dx,
			current.get( 1, 3 ) + dy,
			current.get( 2, 3 )
		};
		viewer.setTransformAnimator( new TranslationAnimator( current, target, animMs ) );
		viewer.requestRepaint();
	}

	/**
	 * Animates the viewer transform by {@code factor}, keeping the screen
	 * centre fixed, over {@code animMs} milliseconds.
	 *
	 * <p>The target transform {@code T'} is derived from the current transform
	 * {@code T} as follows (zoom centred on screen point {@code (cx, cy)}):</p>
	 * <pre>
	 *   T'[r][c] = f * T[r][c]              for c in 0..2  (linear part)
	 *   T'[0][3] = f * T[0][3] + cx*(1-f)  (X translation)
	 *   T'[1][3] = f * T[1][3] + cy*(1-f)  (Y translation)
	 *   T'[2][3] = f * T[2][3]             (Z translation unchanged)
	 * </pre>
	 *
	 * <p>A {@link ZoomAnimator} then linearly interpolates all 12 matrix
	 * elements from start to target over the requested duration.</p>
	 */
	private static void zoom( final ViewerPanel viewer, final double factor, final long animMs )
	{
		final AffineTransform3D start = viewer.state().getViewerTransform();
		final double cx = viewer.getWidth() / 2.0;
		final double cy = viewer.getHeight() / 2.0;
		final AffineTransform3D end = computeZoomedTransform( start, factor, cx, cy );

		viewer.setTransformAnimator( new ZoomAnimator( start, end, animMs ) );
		viewer.requestRepaint();
	}

	/**
	 * Computes the target affine transform after zooming by {@code factor}
	 * around screen point {@code (cx, cy)}.
	 */
	private static AffineTransform3D computeZoomedTransform(
			final AffineTransform3D current,
			final double factor,
			final double cx,
			final double cy )
	{
		final AffineTransform3D zoomed = new AffineTransform3D();
		for ( int r = 0; r < 3; r++ )
			for ( int c = 0; c < 3; c++ )
				zoomed.set( current.get( r, c ) * factor, r, c );

		zoomed.set( current.get( 0, 3 ) * factor + cx * ( 1.0 - factor ), 0, 3 );
		zoomed.set( current.get( 1, 3 ) * factor + cy * ( 1.0 - factor ), 1, 3 );
		zoomed.set( current.get( 2, 3 ) * factor, 2, 3 );
		return zoomed;
	}

	// -----------------------------------------------------------------------

	private static double toDouble( final Object value )
	{
		return ( ( Number ) value ).doubleValue();
	}

	private static long toLong( final Object value )
	{
		return ( ( Number ) value ).longValue();
	}

	// -----------------------------------------------------------------------

	/**
	 * Animates between two {@link AffineTransform3D} instances by linearly
	 * interpolating all 12 elements of the 3×4 affine matrix.
	 *
	 * <p>Because linear interpolation of affine matrix elements preserves the
	 * affine structure at every intermediate step, the visual result is a
	 * smooth, continuously valid transform throughout the animation.</p>
	 */
	private static final class ZoomAnimator extends AbstractTransformAnimator
	{

		private final AffineTransform3D start;

		private final AffineTransform3D end;

		private ZoomAnimator( final AffineTransform3D start, final AffineTransform3D end,
				final long durationMs )
		{
			super( durationMs );
			this.start = start.copy();
			this.end = end.copy();
		}

		@Override
		public AffineTransform3D get( final double t )
		{
			final double s = 1.0 - t; // weight of start
			final AffineTransform3D result = new AffineTransform3D();
			for ( int r = 0; r < 3; r++ )
				for ( int c = 0; c < 4; c++ )
					result.set( s * start.get( r, c ) + t * end.get( r, c ), r, c );
			return result;
		}
	}
}
