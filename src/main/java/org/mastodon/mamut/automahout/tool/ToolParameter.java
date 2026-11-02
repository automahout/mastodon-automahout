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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One parameter a tool accepts: what the description text used to say in prose, now typed, so the
 * planner, the LLM and a dialog can all read the same schema. Built with the static factories:
 * {@code ToolParameter.number( "max_disp", "largest step a cell takes, um", 20 ).range( 0, 200 )}.
 */
public final class ToolParameter
{
	/** The value's type, as {@link AutomahoutTool#execute} receives it in its params map. */
	public enum Type
	{
		INTEGER, NUMBER, BOOLEAN, STRING, CHOICE
	}

	public final String name;

	public final Type type;

	public final String description;

	/** Used when the parameter is absent; null = required, or no default. */
	public final Object defaultValue;

	public final Double min, max;

	/** For {@link Type#CHOICE}: the allowed values. */
	public final List< String > choices;

	private ToolParameter( final String name, final Type type, final String description, final Object defaultValue,
			final Double min, final Double max, final List< String > choices )
	{
		this.name = name;
		this.type = type;
		this.description = description;
		this.defaultValue = defaultValue;
		this.min = min;
		this.max = max;
		this.choices = choices;
	}

	public static ToolParameter integer( final String name, final String description, final Integer defaultValue )
	{
		return new ToolParameter( name, Type.INTEGER, description, defaultValue, null, null, Collections.emptyList() );
	}

	public static ToolParameter number( final String name, final String description, final Double defaultValue )
	{
		return new ToolParameter( name, Type.NUMBER, description, defaultValue, null, null, Collections.emptyList() );
	}

	public static ToolParameter bool( final String name, final String description, final Boolean defaultValue )
	{
		return new ToolParameter( name, Type.BOOLEAN, description, defaultValue, null, null, Collections.emptyList() );
	}

	public static ToolParameter string( final String name, final String description, final String defaultValue )
	{
		return new ToolParameter( name, Type.STRING, description, defaultValue, null, null, Collections.emptyList() );
	}

	public static ToolParameter choice( final String name, final String description, final String defaultValue,
			final String... choices )
	{
		return new ToolParameter( name, Type.CHOICE, description, defaultValue, null, null,
				Collections.unmodifiableList( Arrays.asList( choices ) ) );
	}

	/** This parameter limited to {@code [min, max]}. */
	public ToolParameter range( final double min, final double max )
	{
		return new ToolParameter( name, type, description, defaultValue, min, max, choices );
	}

	/** One line for a schema listing: {@code name (type, default d, min..max): description}. */
	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder( name ).append( " (" ).append( type.name().toLowerCase() );
		if ( !choices.isEmpty() )
			sb.append( ": " ).append( String.join( "|", choices ) );
		if ( defaultValue != null )
			sb.append( ", default " ).append( defaultValue );
		if ( min != null || max != null )
			sb.append( ", " ).append( min == null ? "" : min ).append( ".." ).append( max == null ? "" : max );
		return sb.append( "): " ).append( description ).toString();
	}
}
