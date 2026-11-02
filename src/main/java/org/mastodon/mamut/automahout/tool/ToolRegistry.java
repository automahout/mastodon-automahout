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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.mastodon.mamut.automahout.tool.impl.AdaptiveTrackTool;
import org.mastodon.mamut.automahout.tool.impl.AnalyzeVideoTool;
import org.mastodon.mamut.automahout.tool.impl.AutonomousTrackingTool;
import org.mastodon.mamut.automahout.tool.impl.AutoCorrectTool;
import org.mastodon.mamut.automahout.tool.impl.ElephantTool;
import org.mastodon.mamut.automahout.tool.impl.EvaluateGtTool;
import org.mastodon.mamut.automahout.tool.impl.FindMaximaTool;
import org.mastodon.mamut.automahout.tool.impl.ReviewFrameTool;
import org.mastodon.mamut.automahout.tool.impl.LinkEditTool;
import org.mastodon.mamut.automahout.tool.impl.NavigationTool;
import org.mastodon.mamut.automahout.tool.impl.SpotAddTool;
import org.mastodon.mamut.automahout.tool.impl.SpotDeleteTool;
import org.mastodon.mamut.automahout.tool.impl.SpotEditTool;
import org.mastodon.mamut.automahout.tool.impl.EllipsoidFitTool;
import org.mastodon.mamut.automahout.tool.impl.SpotRefitTool;
import org.mastodon.mamut.automahout.tool.impl.SpotSplitTool;
import org.mastodon.mamut.automahout.tool.impl.TimepointTool;
import org.scijava.Context;
import org.scijava.plugin.PluginInfo;
import org.scijava.plugin.PluginService;

/**
 * Registry that maps tool IDs to {@link AutomahoutTool} instances.
 *
 * <p>
 * Use {@link #createDefault()} to get a registry pre-populated with all
 * built-in tools. New tools can be added at any time via
 * {@link #register(AutomahoutTool)}.
 * </p>
 */
public class ToolRegistry {

	private final Map<String, AutomahoutTool> tools = new LinkedHashMap<>();

	/** The package of the built-in tools: they win a tie on id against an external tool of equal priority. */
	private static final String BUILT_IN_PACKAGE = "org.mastodon.mamut.automahout.";

	/**
	 * The registry for a run: every {@link AutomahoutTool} SciJava can find -- the built-in ones and any
	 * a jar on the class path (Fiji's jars folder, an update site) declares with
	 * {@code @Plugin( type = AutomahoutTool.class )} -- or, without a SciJava context (a replay, a
	 * test), the built-in list.
	 */
	public static ToolRegistry forContext( final ToolContext ctx )
	{
		Context context = null;
		try
		{
			if ( ctx.getProjectModel() != null )
				context = ctx.getProjectModel().getContext();
		}
		catch ( final RuntimeException e )
		{
			// no project
		}
		final ToolRegistry registry = context == null ? createDefault() : discover( context, ctx::log );
		registry.addRemote( org.mastodon.mamut.automahout.tool.remote.RemoteToolServer.configured(), ctx::log );
		return registry;
	}

	/**
	 * Adds the tools each server in {@code servers} offers (docs/remote-tools.md). A remote tool never
	 * replaces one already registered: an id that is taken is logged and left out. A server that does not
	 * answer is logged and skipped.
	 */
	public void addRemote( final List< String > servers, final Consumer< String > log )
	{
		for ( final String url : servers )
			for ( final AutomahoutTool tool : new org.mastodon.mamut.automahout.tool.remote.RemoteToolServer( url ).tools( log ) )
			{
				if ( tools.containsKey( tool.getId() ) )
				{
					log.accept( String.format( "  Remote tool '%s' from %s is left out: the id is taken by %s.", tool.getId(),
							url, tools.get( tool.getId() ).getClass().getName() ) );
					continue;
				}
				log.accept( String.format( "  Tool '%s' added from %s (role %s).", tool.getId(), url, tool.getRole() ) );
				register( tool );
			}
	}

	/**
	 * Every {@link AutomahoutTool} plugin in {@code context}. Two with one id: the higher SciJava
	 * priority wins, and at equal priority the built-in one -- a plugin replaces a built-in tool only by
	 * asking to, with a higher priority. Each tool from outside this project, and each replacement, is
	 * logged. A plugin that cannot be created is logged and left out.
	 */
	public static ToolRegistry discover( final Context context, final Consumer< String > log )
	{
		final PluginService plugins = context.getService( PluginService.class );
		if ( plugins == null )
			return createDefault();
		final List< PluginInfo< AutomahoutTool > > infos = new ArrayList<>( plugins.getPluginsOfType( AutomahoutTool.class ) );
		// Weakest first, so a later register() -- a stronger claim on the id -- replaces it.
		infos.sort( Comparator.< PluginInfo< AutomahoutTool > >comparingDouble( PluginInfo::getPriority )
				.thenComparing( i -> i.getClassName().startsWith( BUILT_IN_PACKAGE ) ) );
		final ToolRegistry registry = new ToolRegistry();
		for ( final PluginInfo< AutomahoutTool > info : infos )
		{
			final AutomahoutTool tool;
			try
			{
				tool = plugins.createInstance( info );
			}
			catch ( final RuntimeException e )
			{
				log.accept( "  Tool plugin " + info.getClassName() + " could not be created and is left out: "
						+ e.getMessage() );
				continue;
			}
			if ( tool == null )
				continue;
			final boolean external = !info.getClassName().startsWith( BUILT_IN_PACKAGE );
			final AutomahoutTool replaced = registry.tools.get( tool.getId() );
			if ( replaced != null )
				log.accept( String.format( "  Tool '%s': %s replaces %s (higher priority).", tool.getId(),
						info.getClassName(), replaced.getClass().getName() ) );
			else if ( external )
				log.accept( String.format( "  Tool '%s' added from %s (role %s).", tool.getId(), info.getClassName(),
						tool.getRole() ) );
			registry.register( tool );
		}
		// A build that did not run SciJava's annotation processor (an IDE's own compiler) leaves no plugin
		// index for this project's classes: the built-in tools must still be there, or a run has none.
		for ( final AutomahoutTool builtIn : createDefault().getAll() )
			if ( !registry.tools.containsKey( builtIn.getId() ) )
				registry.register( builtIn );
		return registry;
	}

	/**
	 * Creates a {@link ToolRegistry} pre-populated with all built-in tools:
	 * {@link NavigationTool}, {@link TimepointTool}, {@link SpotAddTool},
	 * {@link SpotEditTool}, {@link FindMaximaTool}, and {@link ElephantTool}.
	 *
	 * @return a new registry with the default tools registered
	 */
	public static ToolRegistry createDefault() {
		// Kept in step with the @Plugin annotations by ToolDiscoveryTest; used where there is no SciJava
		// context to discover them from.
		final ToolRegistry registry = new ToolRegistry();
		registry.register(new NavigationTool());
		registry.register(new TimepointTool());
		registry.register(new SpotAddTool());
		registry.register(new SpotEditTool());
		registry.register(new SpotDeleteTool());
		registry.register(new SpotSplitTool());
		registry.register(new SpotRefitTool());
		registry.register(new EllipsoidFitTool());
		registry.register(new LinkEditTool());
		registry.register(new AutoCorrectTool());
		registry.register(new FindMaximaTool());
		registry.register(new ReviewFrameTool());
		registry.register(new ElephantTool());
		registry.register(new AdaptiveTrackTool());
		registry.register(new EvaluateGtTool());
		registry.register(new AnalyzeVideoTool());
		registry.register(new AutonomousTrackingTool());
		return registry;
	}

	/**
	 * Registers a tool. If a tool with the same ID is already registered it is
	 * replaced.
	 *
	 * @param tool the tool to register; must not be {@code null}
	 */
	public void register(final AutomahoutTool tool) {
		tools.put(tool.getId(), tool);
	}

	/**
	 * Looks up a tool by its ID.
	 *
	 * @param id tool ID
	 * @return the tool, or {@link Optional#empty()} if not found
	 */
	public Optional<AutomahoutTool> get(final String id) {
		return Optional.ofNullable(tools.get(id));
	}

	/**
	 * Returns an unmodifiable view of all registered tools, in registration
	 * order.
	 *
	 * @return all registered tools
	 */
	public Collection<AutomahoutTool> getAll() {
		return Collections.unmodifiableCollection(tools.values());
	}
}
