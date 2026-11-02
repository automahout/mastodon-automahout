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
import bdv.viewer.ViewerState;

/**
 * Navigates the BDV viewer to a specific timepoint.
 *
 * <p>Exactly one of {@link #PARAM_TIMEPOINT} or {@link #PARAM_DELTA} must be
 * provided per call.</p>
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_TIMEPOINT}</td><td>int</td>
 *       <td>Absolute timepoint index to navigate to</td></tr>
 *   <tr><td>{@value #PARAM_DELTA}</td><td>int</td>
 *       <td>Relative change in timepoint (positive = forward, negative = backward)</td></tr>
 * </table>
 *
 * <p>The resulting timepoint is clamped to {@code [0, numTimepoints - 1]}.</p>
 */
@Plugin( type = AutomahoutTool.class )
public class TimepointTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.VIEW;
	}


	public static final String ID = "timepoint";

	/** Absolute timepoint index to navigate to. */
	public static final String PARAM_TIMEPOINT = "t";

	/** Relative change in timepoint. Positive moves forward, negative backward. */
	public static final String PARAM_DELTA = "dt";

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Navigate the BDV viewer to an absolute timepoint (param 't') "
				+ "or shift by a relative delta (param 'dt'). "
				+ "The result is clamped to the valid timepoint range.";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		final ViewerPanel viewer = context.getOrOpenViewer();
		final ViewerState state = viewer.state();

		final int numTp = state.getNumTimepoints();
		final int current = state.getCurrentTimepoint();

		final int target;
		if ( params.containsKey( PARAM_TIMEPOINT ) )
		{
			target = clamp( ( ( Number ) params.get( PARAM_TIMEPOINT ) ).intValue(), 0, numTp - 1 );
		}
		else if ( params.containsKey( PARAM_DELTA ) )
		{
			target = clamp( current + ( ( Number ) params.get( PARAM_DELTA ) ).intValue(), 0, numTp - 1 );
		}
		else
		{
			throw new IllegalArgumentException(
					"TimepointTool requires either '" + PARAM_TIMEPOINT + "' or '" + PARAM_DELTA + "'." );
		}

		state.setCurrentTimepoint( target );
		viewer.requestRepaint();

		return String.format( "timepoint %d -> %d (range 0..%d)", current, target, numTp - 1 );
	}

	// -----------------------------------------------------------------------

	private static int clamp( final int value, final int min, final int max )
	{
		return Math.max( min, Math.min( max, value ) );
	}
}
