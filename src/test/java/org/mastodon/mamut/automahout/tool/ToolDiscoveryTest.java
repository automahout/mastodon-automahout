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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.example.automahouttools.ExampleExternalTools;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mastodon.mamut.automahout.tool.impl.TimepointTool;
import org.scijava.Context;
import org.scijava.plugin.PluginInfo;
import org.scijava.plugin.PluginService;

/**
 * Tools are found by SciJava, built-in and from other jars alike; ExampleExternalTools stands in for a
 * jar from outside this project.
 */
public class ToolDiscoveryTest
{
	private static Context context;

	private static final List< String > log = new ArrayList<>();

	private static ToolRegistry registry;

	@BeforeClass
	public static void discover()
	{
		context = new Context( PluginService.class );
		// What the annotation index does for a jar's @Plugin classes (see ExampleExternalTools).
		final PluginService plugins = context.getService( PluginService.class );
		for ( final Object[] e : ExampleExternalTools.PRIORITIES )
		{
			@SuppressWarnings( "unchecked" )
			final PluginInfo< AutomahoutTool > info =
					new PluginInfo<>( ( Class< ? extends AutomahoutTool > ) e[ 0 ], AutomahoutTool.class );
			info.setPriority( ( Double ) e[ 1 ] );
			plugins.addPlugin( info );
		}
		registry = ToolRegistry.discover( context, log::add );
	}

	@AfterClass
	public static void dispose()
	{
		context.dispose();
	}

	private static Set< String > ids( final ToolRegistry r )
	{
		final Set< String > out = new TreeSet<>();
		for ( final AutomahoutTool t : r.getAll() )
			out.add( t.getId() );
		return out;
	}

	@Test
	public void everyBuiltInToolIsFoundAndTheFallbackListMatches()
	{
		final Set< String > found = ids( registry );
		final Set< String > builtIn = ids( ToolRegistry.createDefault() );
		assertTrue( "discovered: " + found, found.containsAll( builtIn ) );
		found.removeAll( builtIn );
		found.remove( "example.detect" );
		assertTrue( "a built-in @Plugin missing from createDefault: " + found, found.isEmpty() );
	}

	@Test
	public void aToolFromAnotherJarIsFoundWithItsContract()
	{
		final AutomahoutTool t = registry.get( "example.detect" ).orElseThrow( AssertionError::new );
		assertEquals( ToolRole.DETECT, t.getRole() );
		assertEquals( "threshold", t.getParameters().get( 0 ).name );
		assertFalse( t.applicability( DataProfile.of( false, 10, false, 5, Double.NaN ) ).applies );
		assertTrue( t.applicability( DataProfile.of( false, 10, true, 5, Double.NaN ) ).applies );
		assertTrue( log.toString(), log.stream().anyMatch( l -> l.contains( "'example.detect' added from" ) ) );
	}

	@Test
	public void aBuiltInIsReplacedOnlyByAHigherPriority()
	{
		assertEquals( ExampleExternalTools.StrongerNav.class, registry.get( "nav" ).get().getClass() );
		assertEquals( "a tie keeps the built-in", TimepointTool.class, registry.get( "timepoint" ).get().getClass() );
		assertTrue( log.toString(), log.stream().anyMatch( l -> l.contains( "Tool 'nav'" ) && l.contains( "replaces" ) ) );
	}

	@Test
	public void everyBuiltInToolSaysWhichStageItServes()
	{
		for ( final AutomahoutTool t : ToolRegistry.createDefault().getAll() )
			assertFalse( t.getId() + " has no role", t.getRole() == ToolRole.OTHER );
	}
}
