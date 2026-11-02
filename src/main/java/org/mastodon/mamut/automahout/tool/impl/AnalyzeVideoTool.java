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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;

import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.vision.AnthropicBackend;
import org.mastodon.mamut.automahout.vision.OllamaBackend;
import org.mastodon.mamut.automahout.vision.VlmBackend;
import org.mastodon.mamut.model.Spot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.viewer.ViewerPanel;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Applies a Vision Language Model (VLM) to BDV snapshots and dataset metadata
 * to produce a Markdown description of the time-lapse video.
 *
 * <p>The output Markdown is:</p>
 * <ul>
 *   <li>Stored in a file (default: {@code docs/video_description.md})</li>
 *   <li>Placed in the shared data store under key {@value #SHARED_DATA_KEY}
 *       for downstream tools to read</li>
 *   <li>Returned as the tool result string</li>
 * </ul>
 *
 * <h3>Backend selection ({@value #PARAM_BACKEND})</h3>
 * <table border="1">
 *   <tr><th>Value</th><th>Backend</th><th>Auth</th><th>Default model</th></tr>
 *   <tr><td>{@code ollama} (default)</td><td>Local Ollama server</td>
 *       <td>none — runs on localhost</td>
 *       <td>{@code llava}</td></tr>
 *   <tr><td>{@code anthropic}</td><td>Anthropic Messages API</td>
 *       <td>{@code api_key} param or {@code ANTHROPIC_API_KEY} env</td>
 *       <td>{@code claude-opus-4-8}</td></tr>
 * </table>
 *
 * <h3>Common parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_BACKEND}</td><td>String</td><td>{@code anthropic}</td>
 *       <td>VLM backend: {@code anthropic} or {@code ollama}</td></tr>
 *   <tr><td>{@value #PARAM_MODEL}</td><td>String</td><td>backend-specific</td>
 *       <td>Model name / tag</td></tr>
 *   <tr><td>{@value #PARAM_API_KEY}</td><td>String</td><td>env {@code ANTHROPIC_API_KEY}</td>
 *       <td>Anthropic API key (ignored for Ollama)</td></tr>
 *   <tr><td>{@value #PARAM_BASE_URL}</td><td>String</td>
 *       <td>{@code http://localhost:11434}</td>
 *       <td>Ollama server URL (ignored for Anthropic)</td></tr>
 *   <tr><td>{@value #PARAM_MAX_SNAPSHOTS}</td><td>int</td><td>{@value #DEFAULT_MAX_SNAPSHOTS}</td>
 *       <td>Maximum BDV snapshots to send</td></tr>
 *   <tr><td>{@value #PARAM_OUTPUT_FILE}</td><td>String</td><td>{@value #DEFAULT_OUTPUT_FILE}</td>
 *       <td>Markdown output file path</td></tr>
 *   <tr><td>{@value #PARAM_RENDER_DELAY_MS}</td><td>int</td><td>{@value #DEFAULT_RENDER_DELAY_MS}</td>
 *       <td>ms to wait after a timepoint change before capturing</td></tr>
 * </table>
 */
@Plugin( type = AutomahoutTool.class )
public class AnalyzeVideoTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.PROFILE;
	}


	private static final Logger log = LoggerFactory.getLogger( AnalyzeVideoTool.class );

	public static final String ID = "video.analyze";

	// ---- backend selection ----
	public static final String PARAM_BACKEND = "backend";

	public static final String PARAM_API_KEY = "api_key";

	public static final String PARAM_BASE_URL = "base_url";

	// ---- shared params ----
	public static final String PARAM_MODEL = "model";

	public static final String PARAM_MAX_SNAPSHOTS = "max_snapshots";

	public static final String PARAM_OUTPUT_FILE = "output_file";

	public static final String PARAM_RENDER_DELAY_MS = "render_delay_ms";

	public static final String PARAM_Z_SLICES_PER_TIMEPOINT = "z_slices_per_timepoint";

	/** Key under which the generated Markdown is stored in {@link ToolContext#setSharedData}. */
	public static final String SHARED_DATA_KEY = "video.description";

	private static final int DEFAULT_MAX_SNAPSHOTS = 8;

	private static final String DEFAULT_OUTPUT_FILE = "docs/video_description.md";

	private static final int DEFAULT_RENDER_DELAY_MS = 800;

	/** Maximum image dimension (width or height) before resizing. */
	private static final int MAX_IMAGE_DIM = 1568;

	// -----------------------------------------------------------------------

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Apply a Vision Language Model to BDV snapshots and dataset metadata to generate "
				+ "a Markdown description of the time-lapse video (overview, per-timepoint summary, "
				+ "tracking challenges, and parameter recommendations). "
				+ "Result is stored at '" + DEFAULT_OUTPUT_FILE + "' and in shared data under key '"
				+ SHARED_DATA_KEY + "' for downstream autonomous operations.\n"
				+ "  backend (String, default 'anthropic'): 'anthropic' or 'ollama'\n"
				+ "  model (String): model name; default 'claude-opus-4-8' (Anthropic) or 'llava' (Ollama)\n"
				+ "  api_key (String): Anthropic API key; falls back to ANTHROPIC_API_KEY env var\n"
				+ "  base_url (String): Ollama server URL (default 'http://localhost:11434')\n"
				+ "  max_snapshots (int, default " + DEFAULT_MAX_SNAPSHOTS + "): number of BDV frames to capture\n"
				+ "  output_file (String, default '" + DEFAULT_OUTPUT_FILE + "'): Markdown output path\n"
				+ "  render_delay_ms (int, default " + DEFAULT_RENDER_DELAY_MS
				+ "): ms to wait for BDV to render each frame";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		// ---- load persisted settings as defaults ----
		final AutomahoutSettings saved = new AutomahoutSettings();

		// ---- resolve backend ----
		final String backendName = params.containsKey( PARAM_BACKEND )
				? String.valueOf( params.get( PARAM_BACKEND ) ).toLowerCase()
				: saved.getVlmBackend();

		final VlmBackend backend;
		final String model;

		switch ( backendName )
		{
		case "ollama":
		{
			final String baseUrl = params.containsKey( PARAM_BASE_URL )
					? String.valueOf( params.get( PARAM_BASE_URL ) )
					: saved.getVlmBaseUrl();
			backend = new OllamaBackend( baseUrl );
			model = params.containsKey( PARAM_MODEL )
					? String.valueOf( params.get( PARAM_MODEL ) )
					: saved.getVlmModel();
			log.info( "video.analyze — using Ollama backend at {} (model: {})", baseUrl, model );
			break;
		}
		case "bedrock":
		{
			final String region = saved.getVlmAwsRegion();
			backend = new org.mastodon.mamut.automahout.vision.BedrockBackend( region );
			model = params.containsKey( PARAM_MODEL )
					? String.valueOf( params.get( PARAM_MODEL ) )
					: saved.getVlmModel();
			log.info( "video.analyze — using Bedrock backend (region: {}, model: {})", region, model );
			break;
		}
		case "anthropic":
		default:
		{
			// explicit param > saved setting > env var
			String apiKey = params.containsKey( PARAM_API_KEY )
					? String.valueOf( params.get( PARAM_API_KEY ) )
					: saved.getVlmApiKey();
			if ( apiKey == null || apiKey.trim().isEmpty() )
				apiKey = System.getenv( "ANTHROPIC_API_KEY" );
			if ( apiKey == null || apiKey.trim().isEmpty() )
				return "Error: Anthropic API key not found. "
						+ "Set it in 'Automahout VLM/LLM Settings...', "
						+ "pass '" + PARAM_API_KEY + "' as a parameter, "
						+ "or set the ANTHROPIC_API_KEY environment variable.";
			backend = new AnthropicBackend( apiKey );
			model = params.containsKey( PARAM_MODEL )
					? String.valueOf( params.get( PARAM_MODEL ) )
					: saved.getVlmModel();
			log.info( "video.analyze — using Anthropic backend (model: {})", model );
			break;
		}
		}

		// ---- common parameters ----
		final int maxSnapshots = params.containsKey( PARAM_MAX_SNAPSHOTS )
				? ( ( Number ) params.get( PARAM_MAX_SNAPSHOTS ) ).intValue()
				: saved.getVlmMaxSnapshots();

		final String outputPath = params.containsKey( PARAM_OUTPUT_FILE )
				? String.valueOf( params.get( PARAM_OUTPUT_FILE ) )
				: saved.getVlmOutputFile();

		final int renderDelayMs = params.containsKey( PARAM_RENDER_DELAY_MS )
				? ( ( Number ) params.get( PARAM_RENDER_DELAY_MS ) ).intValue()
				: DEFAULT_RENDER_DELAY_MS;

		final int zSlicesPerTp = params.containsKey( PARAM_Z_SLICES_PER_TIMEPOINT )
				? ( ( Number ) params.get( PARAM_Z_SLICES_PER_TIMEPOINT ) ).intValue()
				: saved.getVlmZSlicesPerTimepoint();

		// ---- extent from SpimData (needed for Z slice navigation and the assessment pyramid) ----
		long numZSlices = 0;
		double voxelSizeZ = 1.0;
		double imageWidthWorld = 0.0;
		double imageHeightWorld = 0.0;
		try
		{
			final AbstractSpimData< ? > spimData =
					context.getProjectModel().getSharedBdvData().getSpimData();
			if ( !spimData.getSequenceDescription().getViewSetupsOrdered().isEmpty() )
			{
				final BasicViewSetup setup =
						( BasicViewSetup ) spimData.getSequenceDescription()
								.getViewSetupsOrdered().get( 0 );
				if ( setup.hasSize() )
					numZSlices = setup.getSize().dimension( 2 );
				if ( setup.hasVoxelSize() )
					voxelSizeZ = setup.getVoxelSize().dimension( 2 );
				if ( setup.hasSize() && setup.hasVoxelSize() )
				{
					final Dimensions dims = setup.getSize();
					final VoxelDimensions voxelDims = setup.getVoxelSize();
					imageWidthWorld = dims.dimension( 0 ) * voxelDims.dimension( 0 );
					imageHeightWorld = dims.dimension( 1 ) * voxelDims.dimension( 1 );
				}
			}
		}
		catch ( final Exception e )
		{
			log.warn( "Could not read image extent from SpimData: {}", e.getMessage() );
		}
		if ( numZSlices <= 0 )
			numZSlices = 1;

		// ---- viewer + timepoint selection ----
		final ViewerPanel viewer = context.getOrOpenViewer();
		final int numTimepoints = viewer.state().getNumTimepoints();
		final int[] selectedTps = selectTimepoints( numTimepoints, maxSnapshots );

		// ---- capture snapshots (multiple Z slices per timepoint) ----
		final int effectiveZSlices = ( zSlicesPerTp > 1 && numZSlices > 1 ) ? zSlicesPerTp : 1;
		log.info( "video.analyze — capturing {} timepoint(s) × {} Z slice(s)...",
				selectedTps.length, effectiveZSlices );
		final List< String > base64Images = new ArrayList<>();
		final List< Integer > capturedTps = new ArrayList<>();
		for ( final int tp : selectedTps )
		{
			try
			{
				final List< String > slices = captureZSlices(
						viewer, tp, effectiveZSlices, numZSlices, voxelSizeZ, renderDelayMs );
				if ( !slices.isEmpty() )
				{
					base64Images.addAll( slices );
					capturedTps.add( tp );
				}
			}
			catch ( final Exception e )
			{
				log.warn( "Failed to capture snapshot at t={}: {}", tp, e.getMessage() );
			}
		}

		if ( base64Images.isEmpty() )
			return "Error: Failed to capture any BDV snapshots.";

		// ---- assessment pyramid (one representative timepoint): a full-frame overview plus a grid of
		// high-res patches, so a huge image's density can be judged the way a human would — scan wide,
		// then zoom into a few representative fields to actually count cells and extrapolate. Without
		// this, a single fit-to-window screenshot of a very large image downsamples individual cells
		// below a pixel, making a genuinely dense dataset look sparse (see the cold-start-flood history
		// this caused). Skipped gracefully if the image extent could not be read.
		double pyramidPatchAreaWorld = 0.0;
		double imageAreaWorld = 0.0;
		if ( imageWidthWorld > 0 && imageHeightWorld > 0 )
		{
			final int pyramidTp = capturedTps.get( 0 );
			try
			{
				final List< String > pyramid = captureAssessmentPyramid(
						viewer, pyramidTp, imageWidthWorld, imageHeightWorld, renderDelayMs );
				if ( !pyramid.isEmpty() )
				{
					base64Images.addAll( pyramid );
					imageAreaWorld = imageWidthWorld * imageHeightWorld;
					pyramidPatchAreaWorld = ( imageWidthWorld / ( PYRAMID_PATCH_GRID_SIZE * 2.0 ) )
							* ( imageHeightWorld / ( PYRAMID_PATCH_GRID_SIZE * 2.0 ) ) * 4.0;
					log.info( "video.analyze — captured {} pyramid image(s) (overview + {}x{} patches) "
							+ "at t={} for density calibration.",
							pyramid.size(), PYRAMID_PATCH_GRID_SIZE, PYRAMID_PATCH_GRID_SIZE, pyramidTp );
				}
			}
			catch ( final Exception e )
			{
				log.warn( "video.analyze — assessment pyramid capture failed: {}", e.getMessage() );
			}
		}

		// ---- metadata + prompt ----
		final String metadata = buildMetadata( context, numTimepoints, capturedTps );
		final String prompt = buildPrompt( metadata, capturedTps, imageAreaWorld, pyramidPatchAreaWorld );

		// ---- VLM call ----
		log.info( "video.analyze — sending {} image(s) to {}", base64Images.size(), model );
		final String analysis;
		try
		{
			analysis = backend.analyze( model, prompt, base64Images );
		}
		catch ( final IOException e )
		{
			log.error( "VLM call failed", e );
			return "Error: VLM call failed — " + e.getMessage();
		}

		// ---- assemble Markdown ----
		final String markdown = buildMarkdown( metadata, capturedTps, analysis );

		// ---- persist ----
		context.setSharedData( SHARED_DATA_KEY, markdown );
		try
		{
			writeFile( outputPath, markdown );
			log.info( "video.analyze — written to {}", outputPath );
		}
		catch ( final IOException e )
		{
			log.warn( "Could not write output file '{}': {}", outputPath, e.getMessage() );
		}

		return markdown;
	}

	// -----------------------------------------------------------------------
	// Public utility for other components

	/**
	 * Captures a small set of representative BDV snapshots (one Z slice each)
	 * for lightweight per-action visual assessment during autonomous tracking.
	 *
	 * <p>Thread-safe; may be called from any thread. Returns an empty list on
	 * failure rather than throwing.</p>
	 *
	 * @param context        tool context (used to get viewer and SpimData)
	 * @param numSnapshots   how many timepoints to capture (e.g. 3)
	 * @param renderDelayMs  ms to wait after each timepoint change
	 * @return base-64-encoded PNGs, possibly empty
	 */
	public static List< String > captureAssessmentSnapshots(
			final ToolContext context,
			final int numSnapshots,
			final int renderDelayMs )
	{
		try
		{
			final ViewerPanel viewer = context.getOrOpenViewer();
			final int numTimepoints = viewer.state().getNumTimepoints();
			final int[] selectedTps = selectTimepoints( numTimepoints, numSnapshots );

			long numZSlices = 1;
			double voxelSizeZ = 1.0;
			try
			{
				final AbstractSpimData< ? > spimData =
						context.getProjectModel().getSharedBdvData().getSpimData();
				if ( !spimData.getSequenceDescription().getViewSetupsOrdered().isEmpty() )
				{
					final BasicViewSetup setup =
							( BasicViewSetup ) spimData.getSequenceDescription()
									.getViewSetupsOrdered().get( 0 );
					if ( setup.hasSize() )
						numZSlices = setup.getSize().dimension( 2 );
					if ( setup.hasVoxelSize() )
						voxelSizeZ = setup.getVoxelSize().dimension( 2 );
				}
			}
			catch ( final Exception e )
			{
				log.warn( "captureAssessmentSnapshots: could not read Z extent: {}", e.getMessage() );
			}

			final List< String > result = new ArrayList<>();
			for ( final int tp : selectedTps )
			{
				try
				{
					result.addAll( captureZSlices( viewer, tp, 1, numZSlices, voxelSizeZ, renderDelayMs ) );
				}
				catch ( final Exception e )
				{
					log.warn( "captureAssessmentSnapshots: failed at t={}: {}", tp, e.getMessage() );
				}
			}
			return result;
		}
		catch ( final Exception e )
		{
			log.warn( "captureAssessmentSnapshots: {}", e.getMessage() );
			return new ArrayList<>();
		}
	}

	// -----------------------------------------------------------------------
	// Timepoint selection

	private static int[] selectTimepoints( final int numTimepoints, final int maxSnapshots )
	{
		final int n = Math.min( maxSnapshots, numTimepoints );
		if ( n <= 1 )
			return new int[] { 0 };
		final int[] tps = new int[ n ];
		for ( int i = 0; i < n; i++ )
			tps[ i ] = ( int ) Math.round( ( double ) i * ( numTimepoints - 1 ) / ( n - 1 ) );
		return tps;
	}

	// -----------------------------------------------------------------------
	// BDV snapshot capture

	/**
	 * Captures one PNG screenshot per Z slice at the given timepoint.
	 *
	 * <p>Z fractions are distributed as {@code (i+1)/(n+1)} so they avoid the
	 * very top and bottom of the stack where signal is often absent.
	 * The original viewer transform is always restored before returning.</p>
	 *
	 * @param numZSlices   number of Z slices to capture (1 = current slice only)
	 * @param imageZDepth  total Z size of the image in voxels
	 * @param voxelSizeZ   physical size of one voxel in Z (world units)
	 * @return list of base-64-encoded PNGs (may be shorter than {@code numZSlices} on partial failure)
	 */
	private static List< String > captureZSlices(
			final ViewerPanel viewer,
			final int timepoint,
			final int numZSlices,
			final long imageZDepth,
			final double voxelSizeZ,
			final int renderDelayMs )
			throws Exception
	{
		// Save original transform and set timepoint on EDT
		final AffineTransform3D originalTransform = new AffineTransform3D();
		if ( SwingUtilities.isEventDispatchThread() )
		{
			viewer.state().setCurrentTimepoint( timepoint );
			viewer.requestRepaint();
			viewer.state().getViewerTransform( originalTransform );
		}
		else
		{
			SwingUtilities.invokeAndWait( () -> {
				viewer.state().setCurrentTimepoint( timepoint );
				viewer.requestRepaint();
				viewer.state().getViewerTransform( originalTransform );
			} );
			if ( renderDelayMs > 0 )
				Thread.sleep( renderDelayMs );
		}

		final List< String > results = new ArrayList<>();
		try
		{
			for ( int i = 0; i < numZSlices; i++ )
			{
				// Fraction distributed as (i+1)/(n+1) — avoids empty edges
				final double fraction = ( i + 1.0 ) / ( numZSlices + 1.0 );
				final double targetWorldZ = fraction * ( imageZDepth - 1 ) * voxelSizeZ;

				// Compute where targetWorldZ appears in screen space under the original transform
				final double[] screenPos = new double[ 3 ];
				originalTransform.apply( new double[] { 0.0, 0.0, targetWorldZ }, screenPos );

				// Build new transform: copy original, shift T[2][3] to bring targetWorldZ to screen Z=0
				final AffineTransform3D zTransform = new AffineTransform3D();
				zTransform.set( originalTransform );
				zTransform.set( originalTransform.get( 2, 3 ) - screenPos[ 2 ], 2, 3 );

				// Apply and wait for render
				if ( SwingUtilities.isEventDispatchThread() )
				{
					viewer.state().setViewerTransform( zTransform );
					viewer.requestRepaint();
				}
				else
				{
					SwingUtilities.invokeAndWait( () -> {
						viewer.state().setViewerTransform( zTransform );
						viewer.requestRepaint();
					} );
					if ( renderDelayMs > 0 )
						Thread.sleep( renderDelayMs );
				}

				// Capture the frame
				final AtomicReference< BufferedImage > imgRef = new AtomicReference<>();
				final Runnable capture = () -> {
					final int w = Math.max( 1, viewer.getWidth() );
					final int h = Math.max( 1, viewer.getHeight() );
					final BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
					final Graphics2D g = img.createGraphics();
					g.setColor( Color.BLACK );
					g.fillRect( 0, 0, w, h );
					viewer.paintAll( g );
					g.dispose();
					imgRef.set( img );
				};
				if ( SwingUtilities.isEventDispatchThread() )
					capture.run();
				else
					SwingUtilities.invokeAndWait( capture );

				final BufferedImage img = imgRef.get();
				if ( img != null )
				{
					final BufferedImage scaled = resizeIfNeeded( img, MAX_IMAGE_DIM );
					final ByteArrayOutputStream baos = new ByteArrayOutputStream();
					ImageIO.write( scaled, "PNG", baos );
					results.add( Base64.getEncoder().encodeToString( baos.toByteArray() ) );
				}
			}
		}
		finally
		{
			// Restore the original transform regardless of success or failure
			if ( SwingUtilities.isEventDispatchThread() )
				viewer.state().setViewerTransform( originalTransform );
			else
				SwingUtilities.invokeLater( () -> viewer.setCurrentViewerTransform( originalTransform ) );
		}
		return results;
	}

	/**
	 * Captures a PNG screenshot of the BDV viewer at the given timepoint.
	 *
	 * <p>Thread-safe: switches timepoint on the EDT, sleeps off the EDT to let
	 * BDV render, then captures on the EDT.</p>
	 *
	 * @return base-64-encoded PNG, or {@code null} on failure
	 */
	private static String captureSnapshot( final ViewerPanel viewer, final int timepoint,
			final int renderDelayMs )
			throws Exception
	{
		// Step 1: set timepoint and request repaint on the EDT
		if ( SwingUtilities.isEventDispatchThread() )
		{
			viewer.state().setCurrentTimepoint( timepoint );
			viewer.requestRepaint();
		}
		else
		{
			SwingUtilities.invokeAndWait( () -> {
				viewer.state().setCurrentTimepoint( timepoint );
				viewer.requestRepaint();
			} );
			if ( renderDelayMs > 0 )
				Thread.sleep( renderDelayMs ); // wait off EDT for BDV's render thread
		}

		// Step 2: capture the component on the EDT
		final AtomicReference< BufferedImage > imgRef = new AtomicReference<>();
		final Runnable capture = () -> {
			final int w = Math.max( 1, viewer.getWidth() );
			final int h = Math.max( 1, viewer.getHeight() );
			final BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
			final Graphics2D g = img.createGraphics();
			g.setColor( Color.BLACK );
			g.fillRect( 0, 0, w, h );
			viewer.paintAll( g );
			g.dispose();
			imgRef.set( img );
		};

		if ( SwingUtilities.isEventDispatchThread() )
			capture.run();
		else
			SwingUtilities.invokeAndWait( capture );

		final BufferedImage img = imgRef.get();
		if ( img == null )
			return null;

		final BufferedImage scaled = resizeIfNeeded( img, MAX_IMAGE_DIM );
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageIO.write( scaled, "PNG", baos );
		return Base64.getEncoder().encodeToString( baos.toByteArray() );
	}

	/**
	 * Captures a canonical top-down screenshot showing exactly the WORLD-coordinate window
	 * {@code [centerX-halfWidth, centerX+halfWidth] x [centerY-halfHeight, centerY+halfHeight]},
	 * ignoring whatever 3D rotation/pan the live viewer transform currently has. Used to build the
	 * assessment pyramid ({@link #captureAssessmentPyramid}): a full-frame overview and several
	 * high-res regional patches all need a reproducible, comprehensible flat view rather than
	 * whatever camera angle the user happened to leave BDV in. The original viewer transform is
	 * always restored before returning.
	 *
	 * @return base-64-encoded PNG, or {@code null} on failure
	 */
	private static String captureRegionSnapshot( final ViewerPanel viewer, final int timepoint,
			final double centerX, final double centerY, final double halfWidth, final double halfHeight,
			final int renderDelayMs )
			throws Exception
	{
		final AffineTransform3D originalTransform = new AffineTransform3D();
		if ( SwingUtilities.isEventDispatchThread() )
		{
			viewer.state().setCurrentTimepoint( timepoint );
			viewer.state().getViewerTransform( originalTransform );
		}
		else
		{
			SwingUtilities.invokeAndWait( () -> {
				viewer.state().setCurrentTimepoint( timepoint );
				viewer.state().getViewerTransform( originalTransform );
			} );
		}

		final int w = Math.max( 1, viewer.getWidth() );
		final int h = Math.max( 1, viewer.getHeight() );
		final double scale = Math.min( w / ( 2.0 * Math.max( 1e-9, halfWidth ) ),
				h / ( 2.0 * Math.max( 1e-9, halfHeight ) ) );

		final AffineTransform3D regionTransform = new AffineTransform3D();
		regionTransform.identity();
		regionTransform.set( scale, 0, 0 );
		regionTransform.set( scale, 1, 1 );
		regionTransform.set( 1.0, 2, 2 );
		regionTransform.set( w / 2.0 - scale * centerX, 0, 3 );
		regionTransform.set( h / 2.0 - scale * centerY, 1, 3 );
		regionTransform.set( 0.0, 2, 3 );

		try
		{
			if ( SwingUtilities.isEventDispatchThread() )
			{
				viewer.state().setViewerTransform( regionTransform );
				viewer.requestRepaint();
			}
			else
			{
				SwingUtilities.invokeAndWait( () -> {
					viewer.state().setViewerTransform( regionTransform );
					viewer.requestRepaint();
				} );
				if ( renderDelayMs > 0 )
					Thread.sleep( renderDelayMs );
			}

			final AtomicReference< BufferedImage > imgRef = new AtomicReference<>();
			final Runnable capture = () -> {
				final BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
				final Graphics2D g = img.createGraphics();
				g.setColor( Color.BLACK );
				g.fillRect( 0, 0, w, h );
				viewer.paintAll( g );
				g.dispose();
				imgRef.set( img );
			};
			if ( SwingUtilities.isEventDispatchThread() )
				capture.run();
			else
				SwingUtilities.invokeAndWait( capture );

			final BufferedImage img = imgRef.get();
			if ( img == null )
				return null;
			final BufferedImage scaled = resizeIfNeeded( img, MAX_IMAGE_DIM );
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write( scaled, "PNG", baos );
			return Base64.getEncoder().encodeToString( baos.toByteArray() );
		}
		finally
		{
			if ( SwingUtilities.isEventDispatchThread() )
				viewer.state().setViewerTransform( originalTransform );
			else
				SwingUtilities.invokeLater( () -> viewer.setCurrentViewerTransform( originalTransform ) );
		}
	}

	/** Grid size (N x N) of high-res patches captured by {@link #captureAssessmentPyramid}. */
	private static final int PYRAMID_PATCH_GRID_SIZE = 2;

	/**
	 * Captures a multi-scale "pyramid" for ONE timepoint — a full-frame overview plus a grid of
	 * native-viewport-resolution regional patches — mirroring how a human inspects a huge microscopy
	 * image: scan wide at low magnification first, then zoom into a few representative fields at high
	 * magnification to actually count/characterize individual objects, and extrapolate.
	 *
	 * <p>A naive single full-viewport screenshot of a very large image (tens of thousands of px) gets
	 * downsampled so much by BDV's own fit-to-window rendering that individual cells shrink below a
	 * pixel and vanish — making a genuinely dense dataset (thousands of real cells) look sparse to a
	 * VLM. The high-res patches are a plain grid (NOT biased toward the densest-looking region), so a
	 * per-patch cell count extrapolates to an unbiased whole-image estimate.</p>
	 *
	 * @return base-64 PNGs: {@code [0]} = full-frame overview, {@code [1..]} = grid patches
	 */
	private static List< String > captureAssessmentPyramid( final ViewerPanel viewer, final int timepoint,
			final double imageWidthWorld, final double imageHeightWorld, final int renderDelayMs )
	{
		final List< String > images = new ArrayList<>();
		try
		{
			final String overview = captureRegionSnapshot( viewer, timepoint,
					imageWidthWorld / 2, imageHeightWorld / 2, imageWidthWorld / 2, imageHeightWorld / 2,
					renderDelayMs );
			if ( overview != null )
				images.add( overview );
		}
		catch ( final Exception e )
		{
			log.warn( "captureAssessmentPyramid: overview failed at t={}: {}", timepoint, e.getMessage() );
		}

		final double patchHalfWidth = imageWidthWorld / ( PYRAMID_PATCH_GRID_SIZE * 2.0 );
		final double patchHalfHeight = imageHeightWorld / ( PYRAMID_PATCH_GRID_SIZE * 2.0 );
		for ( int gy = 0; gy < PYRAMID_PATCH_GRID_SIZE; gy++ )
		{
			for ( int gx = 0; gx < PYRAMID_PATCH_GRID_SIZE; gx++ )
			{
				final double cx = imageWidthWorld * ( gx + 0.5 ) / PYRAMID_PATCH_GRID_SIZE;
				final double cy = imageHeightWorld * ( gy + 0.5 ) / PYRAMID_PATCH_GRID_SIZE;
				try
				{
					final String patch = captureRegionSnapshot( viewer, timepoint, cx, cy,
							patchHalfWidth, patchHalfHeight, renderDelayMs );
					if ( patch != null )
						images.add( patch );
				}
				catch ( final Exception e )
				{
					log.warn( "captureAssessmentPyramid: patch ({},{}) failed at t={}: {}",
							gx, gy, timepoint, e.getMessage() );
				}
			}
		}
		return images;
	}

	private static BufferedImage resizeIfNeeded( final BufferedImage src, final int maxDim )
	{
		final int w = src.getWidth();
		final int h = src.getHeight();
		if ( w <= maxDim && h <= maxDim )
			return src;
		final double scale = ( double ) maxDim / Math.max( w, h );
		final int nw = Math.max( 1, ( int ) ( w * scale ) );
		final int nh = Math.max( 1, ( int ) ( h * scale ) );
		final BufferedImage out = new BufferedImage( nw, nh, BufferedImage.TYPE_INT_RGB );
		final Graphics2D g = out.createGraphics();
		g.setRenderingHint( RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR );
		g.drawImage( src, 0, 0, nw, nh, null );
		g.dispose();
		return out;
	}

	// -----------------------------------------------------------------------
	// Metadata

	private static String buildMetadata(
			final ToolContext context,
			final int numTimepoints,
			final List< Integer > capturedTps )
	{
		final StringBuilder sb = new StringBuilder();
		sb.append( "- Total timepoints: " ).append( numTimepoints ).append( "\n" );

		// XYZ voxel dimensions and physical voxel size from SpimData
		try
		{
			final AbstractSpimData< ? > spimData =
					context.getProjectModel().getSharedBdvData().getSpimData();
			if ( !spimData.getSequenceDescription().getViewSetupsOrdered().isEmpty() )
			{
				final BasicViewSetup setup =
						( BasicViewSetup ) spimData.getSequenceDescription()
								.getViewSetupsOrdered().get( 0 );
				if ( setup.hasSize() )
				{
					final Dimensions dims = setup.getSize();
					sb.append( "- Image size (voxels): X=" ).append( dims.dimension( 0 ) )
							.append( ", Y=" ).append( dims.dimension( 1 ) )
							.append( ", Z=" ).append( dims.dimension( 2 ) ).append( "\n" );
				}
				if ( setup.hasVoxelSize() )
				{
					final VoxelDimensions voxelDims = setup.getVoxelSize();
					final String unit = voxelDims.unit() != null ? voxelDims.unit() : "unknown";
					sb.append( "- Voxel size: X=" )
							.append( String.format( "%.4f", voxelDims.dimension( 0 ) ) )
							.append( ", Y=" )
							.append( String.format( "%.4f", voxelDims.dimension( 1 ) ) )
							.append( ", Z=" )
							.append( String.format( "%.4f", voxelDims.dimension( 2 ) ) )
							.append( " (" ).append( unit ).append( ")\n" );
				}
			}
		}
		catch ( final Exception e )
		{
			log.warn( "Could not read image dimensions from SpimData: {}", e.getMessage() );
		}

		if ( context.getModel() != null )
		{
			sb.append( "- Spot counts at captured timepoints"
					+ " (PRELIMINARY — may be zero or incomplete if detection has not been run):\n" );
			context.getGraph().getLock().readLock().lock();
			try
			{
				final int[] counts = new int[ numTimepoints ];
				for ( final Spot s : context.getGraph().vertices() )
				{
					final int t = s.getTimepoint();
					if ( t >= 0 && t < numTimepoints )
						counts[ t ]++;
				}
				for ( final int tp : capturedTps )
					sb.append( "  - t=" ).append( tp ).append( ": " )
							.append( counts[ tp ] ).append( " spots\n" );
			}
			finally
			{
				context.getGraph().getLock().readLock().unlock();
			}
		}

		return sb.toString();
	}

	// -----------------------------------------------------------------------
	// Prompt

	private static String buildPrompt( final String metadata, final List< Integer > capturedTps,
			final double imageAreaWorld, final double pyramidPatchAreaWorld )
	{
		final StringBuilder sb = new StringBuilder();
		sb.append( "You are analysing a biological time-lapse microscopy dataset managed in "
				+ "Mastodon (a large-scale cell tracking tool). The dataset may be any imaging "
				+ "modality (brightfield, fluorescence, confocal, light-sheet, etc.) — "
				+ "infer the modality from the images themselves; do not assume.\n\n" );
		sb.append( "**Dataset metadata:**\n" ).append( metadata ).append( "\n" );
		sb.append( "**IMPORTANT — spot and track data are preliminary:**\n"
				+ "The spot counts listed above reflect the current state of automated "
				+ "detection and tracking, which may be incomplete or not yet run at all. "
				+ "A timepoint showing zero spots does NOT indicate a biological event "
				+ "(e.g. cell death or disappearance); it most likely means that detection "
				+ "has not been executed for that region yet. "
				+ "Treat spot counts as coverage indicators, not as ground truth.\n\n" );
		sb.append( "**Snapshot timepoints shown (in order):** t=" );
		for ( int i = 0; i < capturedTps.size(); i++ )
		{
			if ( i > 0 )
				sb.append( ", " );
			sb.append( capturedTps.get( i ) );
		}
		sb.append( "\n\n" );
		sb.append( "The images are BDV (BigDataViewer) screenshots at those timepoints. "
				+ "Spot ellipsoids and track links may be overlaid if detection has already been run; "
				+ "absence of overlays simply means the analysis has not been performed yet.\n\n" );
		final boolean hasPyramid = imageAreaWorld > 0 && pyramidPatchAreaWorld > 0;
		if ( hasPyramid )
		{
			final int n = PYRAMID_PATCH_GRID_SIZE;
			sb.append( String.format(
					"**Density-calibration image set (shown LAST, after the per-timepoint snapshots "
							+ "above):** one full-frame overview of the ENTIRE imaged area, followed by "
							+ "a %d x %d grid of %d high-resolution patches sampled evenly across the same "
							+ "frame (NOT cherry-picked — a plain grid, so counting cells in them and "
							+ "extrapolating gives an unbiased estimate for the whole image). "
							+ "Total imaged area = %.4g (same physical units as 'Voxel size' above, squared). "
							+ "Each high-resolution patch covers %.4g of that same area (i.e. 1/%d of the "
							+ "total). At this resolution, individual cells should be clearly visible and "
							+ "countable — the full-frame overview alone is NOT reliable for counting, since "
							+ "downsampling a large image to fit a screenshot can shrink small/dim cells "
							+ "below visibility, making a genuinely dense dataset look deceptively sparse.\n\n",
					n, n, n * n, imageAreaWorld, pyramidPatchAreaWorld, n * n ) );
		}
		sb.append( "Please write a concise Markdown document with the following sections:\n\n" );
		sb.append( "## Dataset Overview\n"
				+ "From visual inspection of the images, describe: "
				+ "likely specimen type (cell line, embryo, tissue, organism), "
				+ "inferred imaging modality (brightfield / widefield fluorescence / confocal / "
				+ "light-sheet / other), approximate spatial scale, "
				+ "dimensionality (2D / 3D), number of visible channels, "
				+ "and any notable image artefacts.\n\n" );
		if ( hasPyramid )
		{
			sb.append( "## Cell Density Estimate\n"
					+ "Using ONLY the high-resolution patches (not the low-res overview or the "
					+ "per-timepoint snapshots, which may under-represent small/dim cells): "
					+ "count the visible cells in each patch, average across patches, and divide by a "
					+ "single patch's stated area to get cells per unit area. Multiply by the total "
					+ "imaged area (stated above) to estimate the TOTAL real cell count for one "
					+ "timepoint. State this explicitly, e.g. 'Estimated total cells per timepoint: "
					+ "~N'. If patches disagree a lot (density varies spatially — check the overview "
					+ "for confluent vs. sparse regions), give a range instead of a single number. "
					+ "This estimate is critical: it sets `cold_start_spots_per_tp` for autonomous "
					+ "tracking (the ceiling above which the pipeline assumes the detection model has "
					+ "collapsed into noise and discards it) — setting it from a sparse-looking "
					+ "downsampled overview when the dataset is actually dense/confluent causes the "
					+ "pipeline to wrongly discard correct detections and retrain on tiny garbage seed "
					+ "sets instead, corrupting the model. Recommend `cold_start_spots_per_tp` as "
					+ "roughly 1.5-2x your total estimate.\n\n" );
		}
		else
		{
			sb.append( "## Cell Density Estimate\n"
					+ "From visual inspection, describe the approximate cell density and, if possible, "
					+ "an estimated total cell count per timepoint. NOTE: no high-resolution "
					+ "density-calibration patches were captured for this dataset (image extent could not "
					+ "be read) — treat any density estimate here as LOW CONFIDENCE, since a downsampled "
					+ "full-frame screenshot of a large image can make a genuinely dense dataset look "
					+ "sparse.\n\n" );
		}
		sb.append( "## Timepoint-by-Timepoint Summary\n"
				+ "For **each** snapshot timepoint (heading `### t=N`): "
				+ "describe what is visually apparent — cell density, morphology changes, "
				+ "visible behaviours (division, migration, crowding, apoptosis), "
				+ "and any tracking overlay quality issues. "
				+ "Do not infer biological events from absent spots.\n\n" );
		sb.append( "## Key Tracking Challenges\n"
				+ "List timepoints or regions where automated tracking is likely to struggle "
				+ "(dense packing, rapid movement, low contrast, track merges, dropouts).\n\n" );
		sb.append( "## Recommended Autocorrect Parameters\n"
				+ "Based on the observed biology and image quality, suggest values for these "
				+ "Automahout parameters. Justify each in one sentence.\n"
				+ "- `long_link_factor` (prune links longer than N × spot radius)\n"
				+ "- `merge_overlap_ratio` (merge duplicate spots within N × radius)\n"
				+ "- `division_link_factor` (search radius for division daughter spots, 0 = off)\n"
				+ "- `min_tracklet_length` (minimum track length to keep)\n"
				+ "- `min_intensity_ratio` (delete spots whose mean intensity < N × global mean)\n\n" );
		sb.append( "Output only the Markdown starting from `## Dataset Overview`. "
				+ "Be concise and technically precise." );
		return sb.toString();
	}

	// -----------------------------------------------------------------------
	// Markdown assembly

	private static String buildMarkdown(
			final String metadata,
			final List< Integer > capturedTps,
			final String analysis )
	{
		final StringBuilder sb = new StringBuilder();
		sb.append( "<!-- Generated by Automahout video.analyze -->\n\n" );
		sb.append( "## Dataset Metadata\n\n" );
		sb.append( metadata );
		sb.append( "\n**Snapshots analysed:** t=" );
		for ( int i = 0; i < capturedTps.size(); i++ )
		{
			if ( i > 0 )
				sb.append( ", " );
			sb.append( capturedTps.get( i ) );
		}
		sb.append( "\n\n---\n\n" );
		sb.append( analysis );
		return sb.toString();
	}

	private static void writeFile( final String path, final String content ) throws IOException
	{
		final File file = new File( path );
		if ( file.getParentFile() != null )
			file.getParentFile().mkdirs();
		try ( final FileWriter fw = new FileWriter( file ) )
		{
			fw.write( content );
		}
	}
}
