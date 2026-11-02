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

import org.mastodon.mamut.automahout.tool.ToolContext;

/** The autonomous-tracking pre-flight for a run without the dialog (the benchmark's batch runner). */
public final class BatchPreflight
{
	private BatchPreflight()
	{}

	/**
	 * Analyses the movie as the dialog does and applies exactly what the dialog opens with, except the switches
	 * given here. Returns the guidance to start the tracking agent with.
	 *
	 * @param startFresh delete every spot and the run's saved state first
	 * @param askMergesAndDivisions ask the "which is right?" questions (through the context's callbacks)
	 * @param detectorTrialDialog show the pretrained-detector trial (false: chosen from the image)
	 */
	public static String run( final ToolContext context, final boolean startFresh, final boolean askMergesAndDivisions,
			final boolean detectorTrialDialog )
	{
		return AutonomousTrackingAction.batchPreflight( context, startFresh, askMergesAndDivisions, detectorTrialDialog );
	}
}
