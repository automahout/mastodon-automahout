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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.scijava.plugin.SciJavaPlugin;

/**
 * Contract for every Automahout tool.
 *
 * <p>Each tool has a stable {@link #getId() string ID} and a
 * {@link #getDescription() description} that can be fed directly to an LLM as
 * part of a tool-calling schema. Parameters are passed as a plain
 * {@code Map<String, Object>} so the interface stays forward-compatible as the
 * tool lineup grows.</p>
 *
 * <p>All implementations must be safe to call on the Swing Event Dispatch
 * Thread. Long-running work should be dispatched to a background thread
 * internally.</p>
 */
public interface AutomahoutTool extends SciJavaPlugin
{

	/**
	 * Returns the unique, stable identifier for this tool.
	 *
	 * <p>Conventionally lower-case with dots as separators
	 * (e.g. {@code "nav"}, {@code "spot.add"}).</p>
	 *
	 * @return tool ID, never {@code null}
	 */
	String getId();

	/**
	 * Returns a concise, human-readable description of what this tool does and
	 * which parameters it accepts. Used for logging and potential LLM schema
	 * generation.
	 *
	 * @return description, never {@code null}
	 */
	String getDescription();

	/**
	 * Executes this tool.
	 *
	 * @param context shared state (project model, active BDV viewer)
	 * @param params  key-value parameters; each tool documents its own keys as
	 *                public {@code PARAM_*} constants
	 * @return a short status message describing what was done, suitable for
	 *         logging or returning to an LLM as a tool result
	 */
	String execute( ToolContext context, Map< String, Object > params );

	/**
	 * The stage of tracking this tool serves; the planner offers it as a candidate for that stage.
	 * {@link ToolRole#OTHER} until a tool says.
	 */
	default ToolRole getRole()
	{
		return ToolRole.OTHER;
	}

	/**
	 * The parameters {@link #execute} reads, typed. Empty until a tool declares them; its description
	 * text then remains the only account of them.
	 */
	default List< ToolParameter > getParameters()
	{
		return Collections.emptyList();
	}

	/**
	 * Whether this tool suits data like {@code data}, how well, and why. The rule half of the choice:
	 * tools that do not apply are not offered for their stage. Applies to anything until a tool says.
	 */
	default Applicability applicability( final DataProfile data )
	{
		return Applicability.always();
	}
}
