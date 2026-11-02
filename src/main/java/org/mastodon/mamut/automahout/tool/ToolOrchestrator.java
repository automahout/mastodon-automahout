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

import java.util.List;
import java.util.Map;

import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the selection and execution of {@link AutomahoutTool}s.
 *
 * <p>Two execution modes are supported:</p>
 * <ul>
 *   <li>{@link #execute(String, Map)} — fire a single tool immediately on the
 *       calling thread.</li>
 *   <li>{@link #executeSequence(List)} — schedule a chain of tool calls on the
 *       Swing EDT, each separated by the call's {@link ToolCall#getDelayMs()}
 *       delay. The next call is scheduled only after the current one has
 *       fired, so delays are relative to the previous step.</li>
 * </ul>
 *
 * <p>All execution happens on the Swing Event Dispatch Thread. Ensure
 * {@link #executeSequence(List)} is called from the EDT.</p>
 */
public class ToolOrchestrator
{

	private static final Logger log = LoggerFactory.getLogger( ToolOrchestrator.class );

	private final ToolRegistry registry;

	private final ToolContext context;

	/**
	 * Creates an orchestrator.
	 *
	 * @param registry registry of available tools
	 * @param context  shared state passed to every tool invocation
	 */
	public ToolOrchestrator( final ToolRegistry registry, final ToolContext context )
	{
		this.registry = registry;
		this.context = context;
	}

	// -----------------------------------------------------------------------

	/**
	 * Executes a single tool immediately on the calling thread.
	 *
	 * @param toolId tool identifier
	 * @param params parameter map
	 * @return the result message returned by the tool
	 * @throws IllegalArgumentException if {@code toolId} is not registered
	 */
	public String execute( final String toolId, final Map< String, Object > params )
	{
		final AutomahoutTool tool = registry.get( toolId )
				.orElseThrow( () -> new IllegalArgumentException( "Unknown tool: " + toolId ) );
		log.debug( "Executing tool '{}' with params {}", toolId, params );
		final String result = tool.execute( context, params );
		log.debug( "Tool '{}' result: {}", toolId, result );
		return result;
	}

	/**
	 * Convenience overload that executes a pre-built {@link ToolCall}.
	 *
	 * @param call the tool call to execute
	 * @return the result message returned by the tool
	 */
	public String execute( final ToolCall call )
	{
		return execute( call.getToolId(), call.getParams() );
	}

	/**
	 * Schedules a sequence of tool calls on the Swing EDT using a timer chain.
	 *
	 * <p>The first call fires after {@code calls.get(0).getDelayMs()} milliseconds.
	 * Each subsequent call fires {@code call.getDelayMs()} milliseconds after the
	 * <em>previous call has fired</em> (not from sequence start). This means that
	 * setting {@code delayMs} larger than each tool's animation duration gives clean,
	 * non-overlapping steps.</p>
	 *
	 * <p>Must be called on the Swing EDT.</p>
	 *
	 * @param calls ordered list of tool calls; may be empty (no-op)
	 */
	public void executeSequence( final List< ToolCall > calls )
	{
		if ( calls.isEmpty() )
			return;
		scheduleNext( calls, 0 );
	}

	// -----------------------------------------------------------------------

	private void scheduleNext( final List< ToolCall > calls, final int index )
	{
		if ( index >= calls.size() )
			return;

		final ToolCall call = calls.get( index );
		final int delay = call.getDelayMs();

		if ( delay <= 0 )
		{
			execute( call );
			scheduleNext( calls, index + 1 );
		}
		else
		{
			final Timer timer = new Timer( delay, e -> {
				execute( call );
				scheduleNext( calls, index + 1 );
			} );
			timer.setRepeats( false );
			timer.start();
		}
	}
}
