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

/**
 * Callback interface for receiving progress updates from {@link AutomahoutLoop}.
 *
 * <p>All methods are invoked on the Swing EDT.</p>
 */
public interface AutomahoutProgressListener
{

	/**
	 * Called when the loop transitions to a new processing phase or advances
	 * to a new timepoint within the current phase.
	 *
	 * @param phase           human-readable phase name (e.g. {@code "Detection"})
	 * @param currentT        actual timepoint being processed
	 * @param totalT          total number of timepoints in the dataset
	 * @param progressPercent overall progress across all three phases (0–99)
	 */
	void onStatusUpdate( String phase, int currentT, int totalT, int progressPercent );

	/**
	 * Called to append a log entry.
	 *
	 * @param message log message (no trailing newline required)
	 */
	void onLogMessage( String message );

	/** Called when all three phases finish successfully. */
	void onCompleted();

	/** Called when the loop is interrupted by {@link AutomahoutLoop#stop()}. */
	void onStopped();
}
