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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.scijava.Context;
import org.scijava.Priority;
import org.scijava.plugin.PluginInfo;
import org.scijava.plugin.PluginService;

/**
 * An ordered list of {@link CorrectionPass}es and the loop that runs it. The built-in order is the
 * default; passes found as SciJava plugins are inserted after the pass they name, or replace a built-in
 * pass of the same id when their priority is above normal (as tools do, see {@link ToolRegistry}).
 *
 * <p>Per run: the plan is resolved once and logged once; a pass the data rules out
 * ({@link CorrectionPass#applicability}) is logged once with its reason and then skipped silently.</p>
 */
public final class PassPlan
{
	private final CorrectionPass.Stage stage;

	private final List< CorrectionPass > passes;

	private final List< String > notes;

	private PassPlan( final CorrectionPass.Stage stage, final List< CorrectionPass > passes, final List< String > notes )
	{
		this.stage = stage;
		this.passes = Collections.unmodifiableList( passes );
		this.notes = notes;
	}

	public List< CorrectionPass > passes()
	{
		return passes;
	}

	/** The pass ids in order. */
	public List< String > ids()
	{
		final List< String > ids = new ArrayList<>();
		for ( final CorrectionPass p : passes )
			ids.add( p.getId() );
		return ids;
	}

	/** The built-in passes with these external ones merged in (in the given order, weakest first). */
	public static PassPlan of( final CorrectionPass.Stage stage, final List< CorrectionPass > builtIn,
			final List< CorrectionPass > external, final List< Double > externalPriorities )
	{
		final List< CorrectionPass > plan = new ArrayList<>( builtIn );
		final List< String > notes = new ArrayList<>();
		for ( int i = 0; i < external.size(); i++ )
		{
			final CorrectionPass p = external.get( i );
			if ( p.getStage() != stage )
				continue;
			final int same = indexOf( plan, p.getId() );
			if ( same >= 0 )
			{
				if ( externalPriorities.get( i ) > Priority.NORMAL )
				{
					notes.add( String.format( "'%s' replaced by %s", p.getId(), p.getClass().getName() ) );
					plan.set( same, p );
				}
				else
					notes.add( String.format( "%s left out: '%s' is a built-in pass's id (a higher priority replaces it)",
							p.getClass().getName(), p.getId() ) );
				continue;
			}
			final int anchor = p.after() == null ? -1 : indexOf( plan, p.after() );
			if ( p.after() != null && anchor < 0 )
				notes.add( String.format( "'%s' (%s) names '%s' to follow, which is not in the plan; added at the end",
						p.getId(), p.getClass().getName(), p.after() ) );
			plan.add( anchor < 0 ? plan.size() : anchor + 1, p );
			notes.add( String.format( "'%s' added from %s%s", p.getId(), p.getClass().getName(),
					anchor < 0 ? "" : " after '" + p.after() + "'" ) );
		}
		return new PassPlan( stage, plan, notes );
	}

	/**
	 * The plan for this run: built-in passes plus the plugins the run's SciJava context knows. Resolved once
	 * per run and kept in the context's shared data, since a FRAME plan is run once per timepoint.
	 */
	public static PassPlan forRun( final ToolContext ctx, final CorrectionPass.Stage stage,
			final List< CorrectionPass > builtIn )
	{
		final String key = "pass.plan." + stage;
		final Object cached = ctx.getSharedData( key );
		if ( cached instanceof PassPlan )
			return ( PassPlan ) cached;
		final List< CorrectionPass > external = new ArrayList<>();
		final List< Double > priorities = new ArrayList<>();
		discover( context( ctx ), stage, external, priorities, ctx::log );
		final PassPlan plan = of( stage, builtIn, external, priorities );
		ctx.setSharedData( key, plan );
		ctx.log( "  Correction plan, " + ( stage == CorrectionPass.Stage.DETECTED ? "on each frame ELEPHANT detects"
				: stage == CorrectionPass.Stage.FRAME ? "per frame" : "per window's lineage" )
				+ ": " + String.join( " -> ", plan.ids() ) );
		for ( final String note : plan.notes )
			ctx.log( "    " + note );
		return plan;
	}

	private static int indexOf( final List< CorrectionPass > plan, final String id )
	{
		for ( int i = 0; i < plan.size(); i++ )
			if ( plan.get( i ).getId().equals( id ) )
				return i;
		return -1;
	}

	private static Context context( final ToolContext ctx )
	{
		try
		{
			return ctx.getProjectModel() == null ? null : ctx.getProjectModel().getContext();
		}
		catch ( final RuntimeException e )
		{
			return null; // no project: replay
		}
	}

	private static void discover( final Context context, final CorrectionPass.Stage stage,
			final List< CorrectionPass > out, final List< Double > priorities, final Consumer< String > log )
	{
		if ( context == null )
			return;
		final PluginService plugins = context.getService( PluginService.class );
		if ( plugins == null )
			return;
		final List< PluginInfo< CorrectionPass > > infos = new ArrayList<>( plugins.getPluginsOfType( CorrectionPass.class ) );
		infos.sort( Comparator.comparingDouble( PluginInfo::getPriority ) );
		for ( final PluginInfo< CorrectionPass > info : infos )
		{
			final CorrectionPass p;
			try
			{
				p = plugins.createInstance( info );
			}
			catch ( final RuntimeException e )
			{
				log.accept( "  Correction pass " + info.getClassName() + " could not be created and is left out: "
						+ e.getMessage() );
				continue;
			}
			if ( p == null || p.getStage() != stage )
				continue;
			out.add( p );
			priorities.add( info.getPriority() );
		}
	}

	/**
	 * Passes switched off for an ablation run (the benchmark): a comma-separated list of pass ids, or {@code all}.
	 * Set on the command line; a pass switched off is logged once and skipped like one the data rules out.
	 */
	public static final String PROPERTY_DISABLE = "automahout.passes.disable";

	static boolean disabled( final String id )
	{
		final String v = System.getProperty( PROPERTY_DISABLE, "" ).trim();
		if ( v.isEmpty() )
			return false;
		for ( final String s : v.split( "," ) )
			if ( s.trim().equals( "all" ) || s.trim().equals( id ) )
				return true;
		return false;
	}

	/** Runs every pass that applies and runs here, in order; their counts are left in {@code scope}. */
	public void run( final PassScope scope )
	{
		final DataProfile data = DataProfile.of( scope.ctx );
		for ( final CorrectionPass p : passes )
		{
			final Applicability a = disabled( p.getId() )
					? Applicability.notFor( "switched off for this run (" + PROPERTY_DISABLE + ")" )
					: p.applicability( data );
			if ( !a.applies )
				leftOut( scope.ctx, p, a );
			else if ( p.runsAt( scope ) )
				scope.counted( p.getId(), p.run( scope ) );
			if ( p.integrityCheckAfter() != null )
				GraphIntegrity.require( scope.ctx, scope.ctx.getGraph(), p.integrityCheckAfter() );
		}
	}

	/** The sum of the counts of the passes with this effect that ran. */
	public int total( final PassScope scope, final CorrectionPass.Effect effect )
	{
		int n = 0;
		for ( final CorrectionPass p : passes )
			if ( p.getEffect() == effect )
				n += scope.count( p.getId() );
		return n;
	}

	private static void leftOut( final ToolContext ctx, final CorrectionPass p, final Applicability a )
	{
		final String key = "pass.left_out." + p.getId();
		if ( ctx.getSharedData( key ) != null )
			return;
		ctx.setSharedData( key, Boolean.TRUE );
		ctx.log( String.format( "  Correction pass '%s' is off for this run: %s", p.getId(), a.reason ) );
	}

	@Override
	public String toString()
	{
		return stage + ": " + String.join( " -> ", ids() );
	}
}
