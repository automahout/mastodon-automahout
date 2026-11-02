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

import java.awt.EventQueue;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.views.bdv.MamutViewBdv;

import bdv.viewer.ViewerPanel;

/**
 * Shared mutable state passed to every {@link AutomahoutTool}.
 *
 * <p>Holds the {@link ProjectModel} (always present) and an optional
 * {@link ViewerPanel} for the currently active BDV window. Tools that need a
 * viewer call {@link #requireViewer()}, which throws if none is open, or let
 * the orchestrator pre-open one via {@link #getOrOpenViewer()}.</p>
 *
 * <p>All methods must be called on the Swing Event Dispatch Thread.</p>
 */
public class ToolContext
{

	private final ProjectModel projectModel;

	/**
	 * Set only by {@link #forReplay(Model)}, where there is no {@link ProjectModel} to go through.
	 * {@code null} in every production path, which keeps {@link #getModel()} reading from the project
	 * exactly as before rather than from a second, drifting copy.
	 */
	private final Model replayModel;

	private ViewerPanel viewer;

	/**
	 * Cross-tool shared data store.
	 * Tools can write results here so that later tools in the same sequence can
	 * read them without hardcoding inter-tool dependencies in parameters.
	 */
	private final Map< String, Object > sharedData = new HashMap<>();

	/**
	 * Optional log callback forwarded to a UI log window.
	 * {@code null} means log messages are silently dropped (callers should also
	 * log via SLF4J independently).
	 */
	private Consumer< String > logCallback;

	/**
	 * Optional callback for consulting the human user (human-in-the-loop). {@code null} means no UI
	 * is attached (headless / cron), in which case {@link #askUser} returns {@code null} and callers
	 * must proceed autonomously.
	 */
	private UserPrompt askCallback;

	/**
	 * Human-in-the-loop prompt: asks the user a question, optionally offering a list of choices, and
	 * returns the user's answer (one of the options, free text, or {@code null} if the user
	 * cancelled / no UI is attached).
	 */
	@FunctionalInterface
	public interface UserPrompt
	{
		String ask( String question, java.util.List< String > options );
	}

	/**
	 * Optional callback that lets the user supply annotations directly in the Mastodon UI (BDV /
	 * TrackScheme) instead of describing them in text. {@code null} means no UI is attached, in which
	 * case {@link #waitForAnnotations} returns {@code false} and callers must fall back to the
	 * text-based {@link #askUser} (or fail).
	 */
	private AnnotationWait annotationWaitCallback;

	/**
	 * Human-in-the-loop choice between pictures: shows {@code question} above one image per candidate
	 * answer, each with its caption, and returns the index of the button pressed, or {@code -1} when
	 * the user closed the dialog.
	 *
	 * <p>For the decisions a person settles by looking -- "is this one cell or two?" -- where a
	 * paragraph of numbers is the wrong thing to ask them to read.</p>
	 */
	@FunctionalInterface
	public interface ImageChoice
	{
		int choose( String question, java.util.List< java.awt.image.BufferedImage > images,
				java.util.List< String > captions, java.util.List< String > buttons );
	}

	private ImageChoice imageChoiceCallback;

	/**
	 * Human-in-the-loop wait while the user edits the graph by hand: shows {@code message} and blocks
	 * until they press Done ({@code true}) or cancel ({@code false}).
	 */
	@FunctionalInterface
	public interface EditWait
	{
		boolean await( String message );
	}

	private EditWait editWaitCallback;

	/**
	 * Human-in-the-loop wait: shows the user {@code message} and a live count of confirmed annotations,
	 * polling {@code currentCount} until it reaches {@code targetCount} (auto-proceeding) or the user
	 * cancels. Confirmation itself is explicit (e.g. an Enter keystroke bound on the dialog) rather than
	 * automatic, so a user unfamiliar with the flow is never carried forward by a timing heuristic they
	 * didn't intend to trigger.
	 */
	@FunctionalInterface
	public interface AnnotationWait
	{
		/**
		 * @param message       instructions shown to the user (what/where to annotate, how to confirm)
		 * @param targetCount   number of annotations to wait for
		 * @param currentCount  supplies the current confirmed count on demand (safe to call repeatedly,
		 *                      e.g. from a Swing {@code Timer} on the EDT — must not block)
		 * @param confirmSelected called when the user asks to confirm right now (e.g. presses Enter on
		 *                      the dialog); reads whatever is currently selected in the Mastodon UI and
		 *                      marks it confirmed. Must not block.
		 * @return {@code true} once {@code targetCount} was reached, {@code false} if the user cancelled
		 */
		boolean await( String message, int targetCount, java.util.function.IntSupplier currentCount,
				Runnable confirmSelected );
	}

	/** Creates a context without a pre-existing viewer. */
	public ToolContext( final ProjectModel projectModel )
	{
		this.projectModel = projectModel;
		this.replayModel = null;		// From the first moment the run could collect a spot: see GraphWatch.
		GraphWatch.watch( projectModel == null && replayModel == null ? null : getGraph() );
	}

	/** Creates a context with an already-open viewer. */
	public ToolContext( final ProjectModel projectModel, final ViewerPanel viewer )
	{
		this.projectModel = projectModel;
		this.replayModel = null;
		this.viewer = viewer;		// From the first moment the run could collect a spot: see GraphWatch.
		GraphWatch.watch( projectModel == null && replayModel == null ? null : getGraph() );
	}

	private ToolContext( final Model model )
	{
		this.projectModel = null;
		this.replayModel = model;		// From the first moment the run could collect a spot: see GraphWatch.
		GraphWatch.watch( projectModel == null && replayModel == null ? null : getGraph() );
	}

	/**
	 * A context backed by a bare {@link Model}, with no project, no viewer and no image data, for
	 * replaying the graph-only correction passes off a fixture.
	 *
	 * <p>This exists because none of this project's tests could construct a real {@link ModelGraph}
	 * before it: a {@link ProjectModel} needs an image on disk, so the only way to exercise a
	 * correction pass was a GUI run against real data, which is why the same class of mutation bug
	 * kept reaching the user. The passes that never touch pixels — topology consistency, the global
	 * cleanup, the division and stability passes — need nothing a fixture cannot provide.</p>
	 *
	 * <p>{@link #requireViewer()} still throws here, deliberately. A pass that samples intensity is
	 * not replayable off a graph, and returning a stub viewer would let such a test claim a coverage
	 * it does not have.</p>
	 */
	public static ToolContext forReplay( final Model model )
	{
		return new ToolContext( model );
	}

	// -----------------------------------------------------------------------
	// Project model accessors

	/** Returns the {@link ProjectModel}, or {@code null} in a {@link #forReplay} context. */
	public ProjectModel getProjectModel()
	{
		return projectModel;
	}

	/**
	 * Whether the dataset has a real Z axis: more than one plane at the FULL-resolution voxel grid,
	 * read from the authoritative SpimData setup rather than from a coarse-mipmap BDV {@code Source}.
	 *
	 * <p>BDV and SpimData always report three dimensions, genuinely 2D data included, so anything that
	 * branches on dimensionality has to ask the Z extent. Two private copies of this test had grown in
	 * {@code ElephantTool} and {@code ElephantIntegration}, one documented as mirroring the other —
	 * which is how the answers drift. Callers ask here.</p>
	 *
	 * <p>{@code true} when it cannot be determined, because every caller's 3D path is the conservative
	 * one: it does more work and assumes less about the data.</p>
	 */
	public boolean isVolumetric()
	{
		try
		{
			final org.mastodon.mamut.model.Model m = getModel();
			if ( m == null || projectModel == null )
				return true;
			final mpicbg.spim.data.generic.AbstractSpimData< ? > spimData =
					projectModel.getSharedBdvData().getSpimData();
			final mpicbg.spim.data.generic.sequence.BasicViewSetup setup =
					( mpicbg.spim.data.generic.sequence.BasicViewSetup ) spimData.getSequenceDescription()
							.getViewSetupsOrdered().get( 0 );
			return setup.getSize().dimension( 2 ) > 1;
		}
		catch ( final Exception e )
		{
			return true;
		}
	}

	/** Convenience shortcut for {@code getProjectModel().getModel()}. */
	public Model getModel()
	{
		return replayModel != null ? replayModel : projectModel.getModel();
	}

	/** Convenience shortcut for {@code getProjectModel().getModel().getGraph()}. */
	public ModelGraph getGraph()
	{
		return getModel().getGraph();
	}

	// -----------------------------------------------------------------------
	// Viewer accessors

	/**
	 * Returns the current viewer, or opens a new BDV window if none is set.
	 * Must be called on the EDT.
	 *
	 * @return active {@link ViewerPanel}, never {@code null}
	 */
	public ViewerPanel getOrOpenViewer()
	{
		if ( viewer == null )
		{
			final MamutViewBdv bdvView = projectModel.getWindowManager().createBigDataViewer();
			viewer = bdvView.getViewerPanelMamut();
		}
		return viewer;
	}

	/**
	 * Returns the current viewer.
	 *
	 * @return active {@link ViewerPanel}, never {@code null}
	 * @throws IllegalStateException if no viewer is currently open
	 */
	public ViewerPanel requireViewer()
	{
		if ( viewer == null )
			throw new IllegalStateException( "No BDV viewer is open. Call getOrOpenViewer() first." );
		return viewer;
	}

	/**
	 * Sets the active viewer. Called by the orchestrator after opening a new
	 * BDV window, or by tests to inject a mock viewer.
	 */
	public void setViewer( final ViewerPanel viewer )
	{
		this.viewer = viewer;
	}

	/** Returns {@code true} if a viewer is currently set. */
	public boolean hasViewer()
	{
		return viewer != null;
	}

	// -----------------------------------------------------------------------
	// Graph notification

	/**
	 * Notifies all graph change listeners (BDV, TrackScheme, etc.) that the
	 * graph has been modified.
	 *
	 * <p>Must be called <em>after</em> releasing any graph write lock, and
	 * <em>after</em> {@link org.mastodon.mamut.model.Model#setUndoPoint()}.
	 * Safe to call from any thread; dispatches to the EDT if needed.</p>
	 */
	public void notifyGraphChanged()
	{
		final ModelGraph g = getGraph();
		if ( EventQueue.isDispatchThread() )
			g.notifyGraphChanged();
		else
			SwingUtilities.invokeLater( g::notifyGraphChanged );
	}

	// -----------------------------------------------------------------------
	// Shared data store

	/**
	 * Stores a value in the cross-tool shared data store.
	 *
	 * @param key   unique key identifying the datum (use a tool-specific prefix)
	 * @param value the value to store; may be any type
	 */
	public void setSharedData( final String key, final Object value )
	{
		sharedData.put( key, value );
	}

	/**
	 * Retrieves a value from the cross-tool shared data store.
	 *
	 * @param key the key used when the datum was stored
	 * @return the stored value, or {@code null} if no entry exists for {@code key}
	 */
	public Object getSharedData( final String key )
	{
		return sharedData.get( key );
	}

	// -----------------------------------------------------------------------
	// Log callback

	/**
	 * Sets a callback that receives human-readable progress messages.
	 * Pass {@code null} to clear. Tools should still log via SLF4J; this
	 * callback is for forwarding messages to a UI window.
	 */
	public void setLogCallback( final Consumer< String > callback )
	{
		this.logCallback = callback;
	}

	/**
	 * Forwards {@code msg} to the registered log callback (if any).
	 * No-op when no callback is set.
	 */
	public void log( final String msg )
	{
		if ( logCallback != null )
			logCallback.accept( msg );
	}

	// -----------------------------------------------------------------------
	// Human-in-the-loop prompt

	/** Sets the callback used to consult the human user. Pass {@code null} to run fully headless. */
	public void setAskCallback( final UserPrompt callback )
	{
		this.askCallback = callback;
	}

	/** Returns {@code true} if a UI is attached that can prompt the user. */
	public boolean canAskUser()
	{
		return askCallback != null;
	}

	/**
	 * Consults the human user. Returns the user's answer, or {@code null} if no UI is attached or the
	 * user cancelled — callers must handle {@code null} by proceeding (or stopping) autonomously.
	 */
	public String askUser( final String question, final java.util.List< String > options )
	{
		if ( askCallback == null )
			return null;
		try
		{
			return askCallback.ask( question, options );
		}
		catch ( final Exception e )
		{
			log( "User prompt failed: " + e.getMessage() );
			return null;
		}
	}

	/** Sets the callback used to wait for a hand edit. Pass {@code null} to run headless. */
	public void setEditWaitCallback( final EditWait callback )
	{
		this.editWaitCallback = callback;
	}

	/** Returns {@code true} if a UI is attached that can wait for the user to edit by hand. */
	public boolean canWaitForEdit()
	{
		return editWaitCallback != null;
	}

	/** Waits for the user's hand edit; {@code false} when cancelled, failed, or no UI is attached. */
	public boolean waitForEdit( final String message )
	{
		if ( editWaitCallback == null )
			return false;
		try
		{
			return editWaitCallback.await( message );
		}
		catch ( final Exception e )
		{
			log( "Waiting for a hand edit failed: " + e.getMessage() );
			return false;
		}
	}

	/** Sets the callback used to choose between pictures. Pass {@code null} to run headless. */
	public void setImageChoiceCallback( final ImageChoice callback )
	{
		this.imageChoiceCallback = callback;
	}

	/** Returns {@code true} if a UI is attached that can show a choice between pictures. */
	public boolean canChooseImages()
	{
		return imageChoiceCallback != null;
	}

	/**
	 * Shows the pictures and returns the index of the button the user pressed; {@code -1} when no UI is
	 * attached, the user closed the dialog, or it failed. Callers must treat {@code -1} as "no answer".
	 */
	public int chooseImage( final String question, final java.util.List< java.awt.image.BufferedImage > images,
			final java.util.List< String > captions, final java.util.List< String > buttons )
	{
		if ( imageChoiceCallback == null )
			return -1;
		try
		{
			return imageChoiceCallback.choose( question, images, captions, buttons );
		}
		catch ( final Exception e )
		{
			log( "Image choice failed: " + e.getMessage() );
			return -1;
		}
	}

	/** Sets the callback used to wait for user-placed annotations. Pass {@code null} to run headless. */
	public void setAnnotationWaitCallback( final AnnotationWait callback )
	{
		this.annotationWaitCallback = callback;
	}

	/** Returns {@code true} if a UI is attached that can wait for user-placed annotations. */
	public boolean canWaitForAnnotations()
	{
		return annotationWaitCallback != null;
	}

	/**
	 * Waits for the user to place {@code targetCount} annotations directly in the Mastodon UI. Returns
	 * {@code false} immediately if no UI is attached — callers must fall back to {@link #askUser} (or
	 * fail) in that case.
	 */
	public boolean waitForAnnotations( final String message, final int targetCount,
			final java.util.function.IntSupplier currentCount, final Runnable confirmSelected )
	{
		if ( annotationWaitCallback == null )
			return false;
		try
		{
			return annotationWaitCallback.await( message, targetCount, currentCount, confirmSelected );
		}
		catch ( final Exception e )
		{
			log( "Annotation wait failed: " + e.getMessage() );
			return false;
		}
	}

	// -----------------------------------------------------------------------
	// Cancellation

	private volatile boolean cancelRequested = false;

	/**
	 * Optional hook called when cancellation is requested.
	 * Used to send an abort signal to the ELEPHANT server so long-running
	 * training/inference is stopped server-side immediately.
	 */
	private volatile Runnable elephantAbortHook;

	/**
	 * Registers a hook that is called (once) when {@link #requestCancel()} is
	 * invoked. Pass {@code null} to clear.
	 */
	public void setElephantAbortHook( final Runnable hook )
	{
		elephantAbortHook = hook;
	}

	/**
	 * Requests cancellation of the currently running tool pipeline.
	 * Also fires the registered ELEPHANT abort hook (if any) so that the
	 * ELEPHANT server stops its current inference or training immediately.
	 * Thread-safe; may be called from any thread.
	 */
	public void requestCancel()
	{
		cancelRequested = true;
		final Runnable hook = elephantAbortHook;
		if ( hook != null )
		{
			try { hook.run(); }
			catch ( final Exception e ) { /* best-effort abort */ }
		}
	}

	/**
	 * Returns {@code true} if cancellation has been requested since this
	 * context was created (or since the last {@link #resetCancel()} call).
	 */
	public boolean isCancelRequested()
	{
		return cancelRequested;
	}

	/** Resets the cancel flag, e.g. when starting a fresh run. */
	public void resetCancel()
	{
		cancelRequested = false;
	}
}
