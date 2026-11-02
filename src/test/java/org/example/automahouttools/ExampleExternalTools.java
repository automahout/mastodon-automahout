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
package org.example.automahouttools;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.mastodon.mamut.automahout.tool.Applicability;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.DataProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.ToolParameter;
import org.mastodon.mamut.automahout.tool.ToolRole;
import org.scijava.Priority;

/**
 * Tools as a jar from outside this project would write them, in a package of its own. Deliberately NOT
 * annotated {@code @Plugin}: TestDrive runs with the test classes on its class path, and an annotated
 * dummy would be discovered there -- 'example.detect' was listed in the user's Run a Tool dialog and
 * StrongerNav replaced the real 'nav'. ToolDiscoveryTest registers them with the plugin service itself,
 * which is what the annotation index does for a real jar. Their priorities: see {@link #PRIORITIES}.
 */
public final class ExampleExternalTools
{
	private ExampleExternalTools()
	{}

	/** Each example and the SciJava priority a jar would declare for it. */
	public static final Object[][] PRIORITIES = {
			{ FluorescenceDetector.class, Priority.NORMAL },
			{ StrongerNav.class, Priority.HIGH },
			{ TiedTimepoint.class, Priority.NORMAL } };

	/** A new tool: a detector for fluorescence only. */
	public static final class FluorescenceDetector implements AutomahoutTool
	{
		@Override
		public String getId()
		{
			return "example.detect";
		}

		@Override
		public String getDescription()
		{
			return "Example detector from another jar.";
		}

		@Override
		public ToolRole getRole()
		{
			return ToolRole.DETECT;
		}

		@Override
		public List< ToolParameter > getParameters()
		{
			return Arrays.asList( ToolParameter.number( "threshold", "detection threshold, spreads", 3.0 ).range( 0, 50 ) );
		}

		@Override
		public Applicability applicability( final DataProfile data )
		{
			if ( Boolean.FALSE.equals( data.fluorescenceLike ) )
				return Applicability.notFor( "fluorescence only" );
			return Applicability.suits( 0.8, "bright cells on a dark background" );
		}

		@Override
		public String execute( final ToolContext context, final Map< String, Object > params )
		{
			return "example";
		}
	}

	/** Asks, with a higher priority, to replace the built-in 'nav'. */
	public static final class StrongerNav implements AutomahoutTool
	{
		@Override
		public String getId()
		{
			return "nav";
		}

		@Override
		public String getDescription()
		{
			return "Replacement navigation.";
		}

		@Override
		public String execute( final ToolContext context, final Map< String, Object > params )
		{
			return "replaced";
		}
	}

	/** Claims 'timepoint' at the same priority as the built-in: must not replace it. */
	public static final class TiedTimepoint implements AutomahoutTool
	{
		@Override
		public String getId()
		{
			return "timepoint";
		}

		@Override
		public String getDescription()
		{
			return "Should lose the tie.";
		}

		@Override
		public String execute( final ToolContext context, final Map< String, Object > params )
		{
			return "tied";
		}
	}
}
