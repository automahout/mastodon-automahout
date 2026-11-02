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
package org.mastodon.mamut.automahout.plugin;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingWorker;
import javax.swing.Timer;

import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;
import org.mastodon.mamut.automahout.elephant.ElephantIntegration;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.viewer.ViewerPanel;

/**
 * Drives the main Automahout processing loop over all timepoints.
 *
 * <h3>Per-timepoint flow</h3>
 * <ol>
 * <li>Set the viewer to {@link #currentTimepoint} and wait
 * {@value #LOAD_DELAY_MS} ms for the image to load.</li>
 * <li><b>EDT</b>: call {@link FindMaximaTool#captureViewerState} to snapshot
 * the viewer transform and image RAI without blocking.</li>
 * <li><b>Background thread</b> ({@link SwingWorker}): run the full 3-D
 * voxel scan via {@link FindMaximaTool#scan}. The EDT remains responsive
 * during this step.</li>
 * <li><b>EDT</b> ({@code SwingWorker.done()}): for each accepted maxima
 * (up to {@value #MAX_SPOTS}):
 * <ul>
 * <li>Navigate to the maxima world position (pan XY + focus Z,
 * {@value #NAVIGATE_ANIM_MS} ms animation).</li>
 * <li>After the animation ({@value #NAVIGATE_DELAY_MS} ms), add a
 * {@link Spot} at that position.</li>
 * </ul>
 * </li>
 * <li>Advance timepoint and repeat.</li>
 * </ol>
 *
 * <p>
 * The loop stops when {@link #stop()} is called or all timepoints are
 * processed. The {@link AtomicBoolean} stop flag is checked before every step
 * and inside the {@code SwingWorker} body.
 * </p>
 */
public class AutomahoutLoop {

	private static final Logger log = LoggerFactory.getLogger(AutomahoutLoop.class);

	// ---- timing (milliseconds) --------------------------------------------

	/** Delay after a timepoint change to allow the image to finish loading. */
	private static final int LOAD_DELAY_MS = 100;

	/** Pause between finishing one timepoint and starting the next. */
	private static final int TIMEPOINT_ADVANCE_DELAY_MS = 100;

	// -----------------------------------------------------------------------

	/**
	 * {@code true} when idle / stopped.
	 * Initialised to {@code true} so {@link #start} can use
	 * {@code compareAndSet(true, false)} as an idempotent guard.
	 */
	private final AtomicBoolean stopRequested = new AtomicBoolean(true);

	/** Optional progress listener; may be {@code null}. */
	private AutomahoutProgressListener progressListener;

	/**
	 * Guards against firing {@link AutomahoutProgressListener#onStopped()} more
	 * than once per run, and against firing it when the loop completed normally.
	 */
	private boolean stopFired = false;

	/** User-configurable parameters. Never {@code null}. */
	private final AutomahoutSettings settings;

	// -----------------------------------------------------------------------

	/**
	 * Creates a loop backed by the supplied settings object.
	 * The settings are re-read at the start of every timepoint, so changes
	 * made via the settings dialog take effect on the next timepoint.
	 *
	 * @param settings shared, mutable settings; must not be {@code null}
	 */
	public AutomahoutLoop(final AutomahoutSettings settings) {
		this.settings = settings;
	}

	/**
	 * Registers a listener that receives real-time progress updates.
	 * Pass {@code null} to deregister. Safe to call before or after starting.
	 */
	public void setProgressListener(final AutomahoutProgressListener listener) {
		this.progressListener = listener;
	}

	// Loop state — accessed only from the EDT (except stopRequested).
	private ToolContext context;

	private int currentTimepoint;

	private int firstTimepoint;

	private int lastTimepoint;

	private int numTimepoints;

	// -----------------------------------------------------------------------
	// Public API

	/**
	 * Starts the full loop (detection → linking → correction) from the configured
	 * start timepoint. Does nothing if the loop is already running.
	 *
	 * @param context shared context holding the viewer and model
	 */
	public void start(final ToolContext context) {
		if (!initLoop(context))
			return;
		notifyLog("Loop started at t=" + firstTimepoint + " – " + lastTimepoint
				+ " (total dataset: " + numTimepoints + ")");
		processTimepoint();
	}

	/**
	 * Starts the correction-only loop, skipping detection and linking.
	 * Does nothing if the loop is already running.
	 *
	 * @param context shared context holding the viewer and model
	 */
	public void startCorrect(final ToolContext context) {
		if (!initLoop(context))
			return;
		notifyLog("Correction loop started at t=" + firstTimepoint + " – " + lastTimepoint
				+ " (total dataset: " + numTimepoints + ")");
		startCorrectionPhase();
	}

	/**
	 * Shared setup for {@link #start} and {@link #startCorrect}.
	 * Returns {@code false} if the loop is already running.
	 */
	private boolean initLoop(final ToolContext context) {
		if (!stopRequested.compareAndSet(true, false)) {
			log.warn("Automahout loop is already running; stop it first.");
			return false;
		}
		stopFired = false;

		this.context = context;

		final ViewerPanel viewer = context.requireViewer();
		numTimepoints = viewer.state().getNumTimepoints();

		final int settingsStart = settings.getStartTimepoint();
		final int settingsEnd = settings.getEndTimepoint();
		firstTimepoint = (settingsStart >= 0)
				? Math.min(settingsStart, numTimepoints - 1)
				: viewer.state().getCurrentTimepoint();
		lastTimepoint = (settingsEnd >= 0)
				? Math.min(settingsEnd, numTimepoints - 1)
				: numTimepoints - 1;
		if (lastTimepoint < firstTimepoint)
			lastTimepoint = firstTimepoint;
		currentTimepoint = firstTimepoint;
		return true;
	}

	/**
	 * Signals the loop to stop. Safe to call from any thread.
	 * The current step (scan or navigation) completes before the loop exits.
	 */
	public void stop() {
		if (stopRequested.compareAndSet(false, true))
			log.info("Automahout loop stop requested.");
	}

	/** Returns {@code true} if the loop is currently running. */
	public boolean isRunning() {
		return !stopRequested.get();
	}

	// -----------------------------------------------------------------------
	// Timer-chain loop

	/** Step 1 — set timepoint, then schedule the scan after the image loads. */
	private void processTimepoint() {
		if (isStopped())
			return;

		if (currentTimepoint > lastTimepoint) {
			notifyLog("=== Detection complete. Starting linking phase. ===");
			startLinkingPhase();
			return;
		}

		notifyStatus("Detection", currentTimepoint);
		log.info("t={} — advancing viewer to timepoint ({} remaining)",
				currentTimepoint, numTimepoints - currentTimepoint);

		final ViewerPanel viewer = context.requireViewer();
		viewer.state().setCurrentTimepoint(currentTimepoint);
		viewer.requestRepaint();

		schedule(LOAD_DELAY_MS, this::searchAndProcess);
	}

	/**
	 * Step 2 — call ELEPHANT server for deep learning-based spot detection.
	 * The HTTP request runs on a background thread so the EDT stays responsive.
	 */
	private void searchAndProcess() {
		if (isStopped())
			return;

		log.info("t={} — ELEPHANT prediction started", currentTimepoint);

		final int tp = currentTimepoint;
		final ElephantIntegration elephant = new ElephantIntegration(context);

		if (!elephant.init()) {
			notifyLog("t=" + tp + " — ELEPHANT init failed, skipping");
			schedule(TIMEPOINT_ADVANCE_DELAY_MS, this::advanceTimepoint);
			return;
		}

		new SwingWorker<Boolean, Void>() {
			@Override
			protected Boolean doInBackground() {
				return elephant.predictSpots( tp );
			}

			@Override
			protected void done() {
				if (isStopped())
					return;
				try {
					if (!get())
						notifyLog("t=" + tp + " — prediction failed, skipping");
					else
						notifyLog("t=" + tp + " — prediction complete");
				} catch (final Exception e) {
					notifyLog("t=" + tp + " — prediction error: " + e.getMessage());
				}
				schedule(TIMEPOINT_ADVANCE_DELAY_MS, AutomahoutLoop.this::advanceTimepoint);
			}
		}.execute();
	}

	// -----------------------------------------------------------------------
	// Linking phase (reverse loop)

	private void startLinkingPhase() {
		currentTimepoint = lastTimepoint;
		schedule(TIMEPOINT_ADVANCE_DELAY_MS, this::linkTimepoint);
	}

	private void linkTimepoint() {
		if (isStopped())
			return;

		notifyStatus("Linking", currentTimepoint);

		final ViewerPanel viewer = context.requireViewer();
		viewer.state().setCurrentTimepoint(currentTimepoint);
		viewer.requestRepaint();

		final int tp = currentTimepoint;
		final ElephantIntegration elephant = new ElephantIntegration(context);

		if (!elephant.init()) {
			notifyLog("t=" + tp + " — ELEPHANT init failed for linking");
			schedule(TIMEPOINT_ADVANCE_DELAY_MS, this::retreatTimepoint);
			return;
		}

		new SwingWorker<Boolean, Void>() {
			@Override
			protected Boolean doInBackground() {
				return elephant.linkSpots();
			}

			@Override
			protected void done() {
				if (isStopped())
					return;
				try {
					if (!get())
						notifyLog("t=" + tp + " — linking failed");
					else
						notifyLog("t=" + tp + " — linking complete");
				} catch (final Exception e) {
					notifyLog("t=" + tp + " — linking error: " + e.getMessage());
				}
				schedule(TIMEPOINT_ADVANCE_DELAY_MS, AutomahoutLoop.this::retreatTimepoint);
			}
		}.execute();
	}

	private void retreatTimepoint() {
		if (isStopped())
			return;
		if (currentTimepoint <= firstTimepoint) {
			notifyLog("=== Linking complete. Starting correction phase. ===");
			startCorrectionPhase();
			return;
		}
		currentTimepoint--;
		schedule(TIMEPOINT_ADVANCE_DELAY_MS, this::linkTimepoint);
	}

	// -----------------------------------------------------------------------
	// Correction phase (Phase 3: first → last timepoint)

	private void startCorrectionPhase() {
		currentTimepoint = firstTimepoint;
		schedule(TIMEPOINT_ADVANCE_DELAY_MS, this::correctNextTimepoint);
	}

	private void correctNextTimepoint() {
		if (isStopped())
			return;

		notifyStatus("Correction", currentTimepoint);

		final ViewerPanel viewer = context.requireViewer();
		viewer.state().setCurrentTimepoint(currentTimepoint);
		viewer.requestRepaint();

		final int tp = currentTimepoint;
		final int ntp = numTimepoints;

		new SwingWorker<AutoCorrectOrchestrator.CorrectionResult, Void>() {
			@Override
			protected AutoCorrectOrchestrator.CorrectionResult doInBackground() {
				return AutoCorrectOrchestrator.correctTimepoint(context, tp, ntp, settings);
			}

			@Override
			protected void done() {
				if (isStopped())
					return;
				try {
					final AutoCorrectOrchestrator.CorrectionResult result = get();
					if (result.hasChanges())
						notifyLog("t=" + tp + " — " + result);
				} catch (final Exception e) {
					notifyLog("t=" + tp + " — correction error: " + e.getMessage());
				}
				schedule(TIMEPOINT_ADVANCE_DELAY_MS,
						AutomahoutLoop.this::advanceCorrectionTimepoint);
			}
		}.execute();
	}

	private void advanceCorrectionTimepoint() {
		if (isStopped())
			return;
		if (currentTimepoint >= lastTimepoint) {
			notifyLog("=== Correction complete. Running global cleanup. ===");
			schedule(TIMEPOINT_ADVANCE_DELAY_MS, this::runGlobalCleanup);
			return;
		}
		currentTimepoint++;
		schedule(TIMEPOINT_ADVANCE_DELAY_MS, this::correctNextTimepoint);
	}

	private void runGlobalCleanup() {
		if (isStopped())
			return;
		final int ntp = numTimepoints;
		new SwingWorker<Integer, Void>() {
			@Override
			protected Integer doInBackground() {
				return AutoCorrectOrchestrator.globalCleanup(context, ntp);
			}

			@Override
			protected void done() {
				try {
					final int cleaned = get();
					if (cleaned > 0)
						notifyLog("Global cleanup — removed " + cleaned + " isolated spot(s)");
				} catch (final Exception e) {
					notifyLog("Global cleanup error: " + e.getMessage());
				}
				schedule(TIMEPOINT_ADVANCE_DELAY_MS, AutomahoutLoop.this::runTopologyConsistencyPhase);
			}
		}.execute();
	}

	/**
	 * Final phase: dissolves biologically-implausible divisions (rapid successive divisions, spurious
	 * short post-division branches, and — when the learned profile says divisions are rare — every
	 * remaining extra branch) using {@link AutoCorrectOrchestrator#runTopologyConsistency}. Without this
	 * pass, the correction phase's purely spatial linking heuristics ({@code linkDivisionDaughters},
	 * {@code linkOrphanStarts}) can fabricate a "division" whenever an orphan spot merely happens to sit
	 * near an unrelated single-child track — e.g. because its OWN true predecessor was never detected —
	 * leaving behind implausibly bushy trees. This mirrors the consistency pass the LLM-driven
	 * {@code auto.correct}/{@code adaptive_track} tools already run, so "Correct only" gets the same
	 * safety net.
	 */
	private void runTopologyConsistencyPhase() {
		if (isStopped())
			return;
		final int ntp = numTimepoints;
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() {
				return AutoCorrectOrchestrator.runTopologyConsistency(context, 0, ntp, settings);
			}

			@Override
			protected void done() {
				try {
					notifyLog("Topology consistency — " + get());
				} catch (final Exception e) {
					notifyLog("Topology consistency error: " + e.getMessage());
				}
				notifyLog("=== All phases complete. ===");
				stopFired = true;
				stopRequested.set(true);
				if (progressListener != null)
					progressListener.onCompleted();
			}
		}.execute();
	}

	// -----------------------------------------------------------------------

	/** Step 4 — increment timepoint and loop back. */
	private void advanceTimepoint() {
		if (isStopped())
			return;
		currentTimepoint++;
		schedule(TIMEPOINT_ADVANCE_DELAY_MS, this::processTimepoint);
	}

	// -----------------------------------------------------------------------
	// Helpers

	private boolean isStopped() {
		if (stopRequested.get()) {
			if (!stopFired && progressListener != null) {
				stopFired = true;
				progressListener.onStopped();
			}
			return true;
		}
		return false;
	}

	/** Logs at INFO level and forwards the message to the progress listener. */
	private void notifyLog(final String msg) {
		log.info(msg);
		if (progressListener != null)
			progressListener.onLogMessage(msg);
	}

	/**
	 * Sends a status update to the progress listener.
	 * Progress percentage is computed from the phase and step position.
	 */
	private void notifyStatus(final String phase, final int t) {
		if (progressListener == null)
			return;
		final int steps = lastTimepoint - firstTimepoint + 1;
		final int step;
		if ("Linking".equals(phase))
			step = lastTimepoint - t;
		else
			step = t - firstTimepoint;
		final int phaseIdx = "Detection".equals(phase) ? 0 : "Linking".equals(phase) ? 1 : 2;
		final int percent = steps == 0 ? 0
				: Math.min(99, (phaseIdx * steps + step) * 100 / (3 * steps));
		progressListener.onStatusUpdate(phase, t, numTimepoints, percent);
	}

	/**
	 * Schedules {@code task} on the EDT after {@code delayMs} ms.
	 * Silently skips if {@link #stopRequested} is {@code true} at fire time.
	 */
	private void schedule(final int delayMs, final Runnable task) {
		if (stopRequested.get())
			return;
		final Timer timer = new Timer(delayMs, e -> {
			if (!stopRequested.get())
				task.run();
		});
		timer.setRepeats(false);
		timer.start();
	}
}
