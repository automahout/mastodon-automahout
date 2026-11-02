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
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable description of a single tool invocation: which tool to call, with
 * which parameters, and how many milliseconds to wait <em>before</em> firing
 * this call when executing a sequence.
 *
 * <p>Build instances with the fluent {@link Builder}:</p>
 * <pre>{@code
 * ToolCall call = ToolCall.builder("nav")
 *     .param("dx", 100.0)
 *     .param("animationMs", 500L)
 *     .delayMs(700)
 *     .build();
 * }</pre>
 */
public final class ToolCall
{

	private final String toolId;

	private final Map< String, Object > params;

	/** Milliseconds to wait before executing this call in a sequence. */
	private final int delayMs;

	private ToolCall( final Builder builder )
	{
		this.toolId = builder.toolId;
		this.params = Collections.unmodifiableMap( new HashMap<>( builder.params ) );
		this.delayMs = builder.delayMs;
	}

	/** Returns the ID of the tool to invoke. */
	public String getToolId()
	{
		return toolId;
	}

	/** Returns an unmodifiable view of the parameters. */
	public Map< String, Object > getParams()
	{
		return params;
	}

	/**
	 * Returns the delay in milliseconds that the {@link ToolOrchestrator}
	 * should wait before executing this call in a sequence.
	 */
	public int getDelayMs()
	{
		return delayMs;
	}

	// -----------------------------------------------------------------------

	/**
	 * Creates a new {@link Builder} for the given tool ID.
	 *
	 * @param toolId tool identifier, e.g. {@code "nav"} or {@code "spot.add"}
	 * @return a new builder
	 */
	public static Builder builder( final String toolId )
	{
		return new Builder( toolId );
	}

	// -----------------------------------------------------------------------

	/** Fluent builder for {@link ToolCall}. */
	public static final class Builder
	{

		private final String toolId;

		private final Map< String, Object > params = new HashMap<>();

		private int delayMs = 0;

		private Builder( final String toolId )
		{
			if ( toolId == null || toolId.isEmpty() )
				throw new IllegalArgumentException( "toolId must not be null or empty" );
			this.toolId = toolId;
		}

		/**
		 * Adds a parameter.
		 *
		 * @param key   parameter name (matches the tool's {@code PARAM_*} constant)
		 * @param value parameter value
		 * @return this builder
		 */
		public Builder param( final String key, final Object value )
		{
			params.put( key, value );
			return this;
		}

		/**
		 * Sets the number of milliseconds to wait before this call fires in a
		 * sequence. Default is {@code 0}.
		 *
		 * @param ms delay in milliseconds (non-negative)
		 * @return this builder
		 */
		public Builder delayMs( final int ms )
		{
			if ( ms < 0 )
				throw new IllegalArgumentException( "delayMs must be >= 0, got: " + ms );
			this.delayMs = ms;
			return this;
		}

		/** Builds and returns the immutable {@link ToolCall}. */
		public ToolCall build()
		{
			return new ToolCall( this );
		}
	}
}
