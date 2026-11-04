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

import java.awt.event.ActionEvent;

import org.scijava.ui.behaviour.util.AbstractNamedAction;

/**
 * Cancels a running autonomous tracking.
 *
 * <p>This action is registered under
 * {@link MastodonAutomahoutPlugin#ACTION_STOP_NAME} and appears in the menu
 * as <em>Plugins &gt; Automahout &gt; Stop Automahout</em>. It is safe to
 * invoke when nothing is running.</p>
 */
public class AutomahoutStopAction extends AbstractNamedAction
{

	private static final long serialVersionUID = 1L;

	private AutonomousTrackingAction autonomousTrackingAction;

	/** Creates the stop action. */
	public AutomahoutStopAction()
	{
		super( MastodonAutomahoutPlugin.ACTION_STOP_NAME );
	}

	/**
	 * Links an autonomous tracking action so that the Stop button also
	 * cancels any running autonomous tracking.
	 */
	public void setAutonomousTrackingAction( final AutonomousTrackingAction action )
	{
		this.autonomousTrackingAction = action;
	}

	@Override
	public void actionPerformed( final ActionEvent e )
	{
		if ( autonomousTrackingAction != null )
			autonomousTrackingAction.cancel();
	}
}
