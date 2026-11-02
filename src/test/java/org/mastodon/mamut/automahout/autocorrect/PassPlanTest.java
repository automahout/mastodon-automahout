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
package org.mastodon.mamut.automahout.autocorrect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.Applicability;
import org.mastodon.mamut.automahout.tool.CorrectionPass;
import org.mastodon.mamut.automahout.tool.DataProfile;
import org.mastodon.mamut.automahout.tool.PassPlan;
import org.mastodon.mamut.automahout.tool.PassScope;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.ToolRole;
import org.mastodon.mamut.automahout.tool.impl.DetectedPasses;
import org.scijava.Priority;

/**
 * The two correction steps as plans of passes (step 3 of docs/architecture.md §1.6): the built-in order is
 * the order the steps always ran in, data gates live in {@code applicability}, and a pass from outside joins
 * or replaces one by the stated rules.
 */
public class PassPlanTest
{
	/** The order {@code correctTimepoint} ran its steps in before it became a plan. Changing it is a decision. */
	@Test
	public void theFramePlanKeepsTheOrderCorrectTimepointHad()
	{
		// frame.stacked_duplicates added after frame.sandwiched by the user's decision (2026-10-23).
		assertEquals( Arrays.asList( "frame.weak_spots", "frame.small_spots", "frame.sandwiched",
				"frame.stacked_duplicates", "frame.merge_duplicates", "frame.long_links", "frame.relink", "frame.convergence_links",
				"frame.trifurcations", "elephant.recover_predecessors", "frame.division_daughters",
				"frame.orphan_starts", "frame.short_tracklets", "elephant.fill_gaps", "frame.isolated_rescue",
				"frame.bridge_gaps", "frame.isolated_spots", "elephant.refit_anisotropic", "frame.bridge_after_refit",
				"frame.isolated_final" ), ids( FramePasses.BUILT_IN ) );
	}

	/** The order {@code runTopologyConsistency} ran its steps in; the thresholds are measured after the repair. */
	@Test
	public void theLineagePlanKeepsTheOrderTheConsistencyStepHad()
	{
		assertEquals( Arrays.asList( "lineage.unlink_fp", "lineage.converged_tracks", "lineage.thresholds",
				"lineage.rapid_divisions", "lineage.short_branches", "lineage.relink_free",
				"lineage.reconnect_orphans", "lineage.close_gaps", "lineage.excess_divisions",
				"lineage.staying_divisions", "lineage.implausible_divisions", "lineage.unstable_spots",
				"lineage.co_located_pairs" ), ids( LineagePasses.BUILT_IN ) );
	}

	/** The order predictWindow ran its cleanup in, on each frame ELEPHANT detects; the z refit, then the contour fit, last. */
	@Test
	public void theDetectedPlanKeepsTheOrderPredictWindowHad()
	{
		assertEquals( Arrays.asList( "detected.extent", "detected.split_merged", "detected.duplicates",
				"detected.flat_contrast", "detected.no_signal", "detected.debris", "detected.protected_overlap",
				"detected.weak", "detected.min_size", "detected.flat_z_refit", "detected.recover_missed",
				"detected.contour_fit" ),
				ids( DetectedPasses.BUILT_IN ) );
		for ( final CorrectionPass p : DetectedPasses.BUILT_IN )
			assertEquals( p.getId(), CorrectionPass.Stage.DETECTED, p.getStage() );
	}

	/** The no-signal deletion asks "brighter than the background?", which only fluorescence answers. */
	@Test
	public void theNoSignalDeletionIsForFluorescenceOnly()
	{
		final CorrectionPass noSignal = pass( DetectedPasses.BUILT_IN, DetectedPasses.NO_SIGNAL );
		assertFalse( noSignal.applicability( DataProfile.of( false, 50, Boolean.FALSE, 20, Double.NaN ) ).applies );
		assertTrue( noSignal.applicability( DataProfile.of( false, 50, Boolean.TRUE, 20, Double.NaN ) ).applies );
		assertTrue( "not measured yet: the pass measures it itself",
				noSignal.applicability( DataProfile.of( true, 50, null, Double.NaN, Double.NaN ) ).applies );
		final CorrectionPass recover = pass( DetectedPasses.BUILT_IN, DetectedPasses.RECOVER );
		for ( final CorrectionPass p : DetectedPasses.BUILT_IN )
			if ( p != noSignal && p != recover )
				assertTrue( p.getId(), p.applicability( DataProfile.of( true, 50, Boolean.FALSE, 2, Double.NaN ) ).applies );
	}

	/** Missed-nucleus recovery looks for bright peaks, and was measured on stacks: fluorescence stacks only. */
	@Test
	public void missedNucleusRecoveryIsForFluorescenceStacksOnly()
	{
		final CorrectionPass recover = pass( DetectedPasses.BUILT_IN, DetectedPasses.RECOVER );
		assertTrue( recover.applicability( DataProfile.of( true, 250, Boolean.TRUE, 20, Double.NaN ) ).applies );
		assertFalse( recover.applicability( DataProfile.of( true, 250, Boolean.FALSE, 20, Double.NaN ) ).applies );
		assertFalse( recover.applicability( DataProfile.of( false, 92, Boolean.TRUE, 20, Double.NaN ) ).applies );
	}

	/** The sandwiched-spot deletion is for stacks: in 2D it took real neighbouring cells (PSC 01, 82%). */
	@Test
	public void theSandwichedDeletionIsForStacksOnly()
	{
		final CorrectionPass sandwiched = pass( FramePasses.BUILT_IN, "frame.sandwiched" );
		assertFalse( sandwiched.applicability( DataProfile.of( false, 300, Boolean.FALSE, 20, Double.NaN ) ).applies );
		assertFalse( sandwiched.applicability( DataProfile.of( false, 92, Boolean.TRUE, 20, Double.NaN ) ).applies );
		assertTrue( sandwiched.applicability( DataProfile.of( true, 250, Boolean.TRUE, 20, Double.NaN ) ).applies );
	}

	/** Frames of one detected window share the window's values and add up their counts. */
	@Test
	public void aDetectedWindowTotalsItsFrames()
	{
		final ToolContext ctx = new ReplayGraph().context();
		final PassScope window = PassScope.detectedWindow( ctx, null, 3, 5 );
		window.put( "k", new int[ 1 ] );
		final CorrectionPass counting = new FakePass( "detected.counting", null )
		{
			@Override
			public int run( final PassScope s )
			{
				s.< int[] >get( "k" )[ 0 ]++;
				return s.timepoint;
			}
		};
		final PassPlan plan = PassPlan.of( CorrectionPass.Stage.FRAME, Collections.singletonList( counting ),
				Collections.emptyList(), Collections.emptyList() );
		for ( int t = 3; t <= 5; t++ )
			plan.run( window.atFrame( t ) );
		assertEquals( 3, window.< int[] >get( "k" )[ 0 ] );
		assertEquals( 3 + 4 + 5, window.total( "detected.counting" ) );
	}

	@Test
	public void everyPassSaysWhatItIs()
	{
		final List< CorrectionPass > all = new ArrayList<>( FramePasses.BUILT_IN );
		all.addAll( LineagePasses.BUILT_IN );
		all.addAll( DetectedPasses.BUILT_IN );
		for ( final CorrectionPass p : all )
		{
			assertFalse( p.getId(), p.getDescription().isEmpty() );
			assertTrue( p.getId(), p.getId().startsWith( p.getStage().name().toLowerCase() + "." )
					|| p.getId().startsWith( "elephant." ) );
		}
	}

	/**
	 * The passes that re-detect with ELEPHANT's model are exactly the {@code elephant.} ones, and a run that
	 * detects with a pretrained model leaves them out: that model was never trained on its movie.
	 */
	@Test
	public void elephantRedetectionIsLeftOutWhenAPretrainedDetectorDetects() throws IOException
	{
		// A stack, where every frame pass applies (frame.sandwiched is for stacks only).
		final DataProfile elephant = DataProfile.of( true, 92, Boolean.TRUE, 20, Double.NaN );
		final DataProfile stardist = elephant.detectedBy( "cellpose.nuclei3d" );
		for ( final CorrectionPass p : FramePasses.BUILT_IN )
		{
			assertTrue( p.getId(), p.applicability( elephant ).applies );
			assertEquals( p.getId(), !p.getId().startsWith( "elephant." ), p.applicability( stardist ).applies );
		}
		// ...and every call into a re-detecting rule sits in an elephant. pass (comments stripped).
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org", "mastodon", "mamut",
				"automahout", "autocorrect", "FramePasses.java" ) ), StandardCharsets.UTF_8 )
				.replaceAll( "(?s)/\\*.*?\\*/", " " ).replaceAll( "(?m)//.*$", " " );
		final String[] chunks = src.split( "new BuiltinPass\\(" );
		int found = 0;
		for ( final String chunk : chunks )
			for ( final String redetects : new String[] { "recoverMissingPredecessors(", "fillGaps(",
					"refitAnisotropicSpots(" } )
				if ( chunk.contains( redetects ) )
				{
					found++;
					assertTrue( redetects, chunk.trim().startsWith( "\"elephant." ) || chunk.trim().startsWith( "REFIT" ) );
				}
		assertEquals( 3, found );
	}

	@Test
	public void theMergeRepairIsForFluorescenceIn2DAnd3D()
	{
		final CorrectionPass repair = pass( LineagePasses.BUILT_IN, LineagePasses.CONVERGED );
		assertTrue( "a stack: 3-D geometry and fit", repair.applicability( DataProfile.of( true, 50, Boolean.TRUE, 20, Double.NaN ) ).applies );
		assertFalse( repair.applicability( DataProfile.of( true, 50, Boolean.FALSE, 20, Double.NaN ) ).applies );
		assertFalse( repair.applicability( DataProfile.of( false, 50, Boolean.FALSE, 20, Double.NaN ) ).applies );
		assertTrue( repair.applicability( DataProfile.of( false, 50, Boolean.TRUE, 20, Double.NaN ) ).applies );
		assertTrue( "not measured yet: the pass measures it itself",
				repair.applicability( DataProfile.of( false, 50, null, Double.NaN, Double.NaN ) ).applies );
	}

	/** A pass from outside: after its anchor; same id replaces only above normal priority; unknown anchor at the end. */
	@Test
	public void externalPassesJoinOrReplaceByTheStatedRules()
	{
		final List< CorrectionPass > builtIn = Arrays.asList( fake( "frame.a", null ), fake( "frame.b", null ),
				fake( "frame.c", null ) );
		final CorrectionPass afterA = fake( "ext.after_a", "frame.a" );
		final CorrectionPass nowhere = fake( "ext.nowhere", "frame.zzz" );
		final CorrectionPass weakB = fake( "frame.b", null );
		final CorrectionPass strongC = fake( "frame.c", null );
		final PassPlan plan = PassPlan.of( CorrectionPass.Stage.FRAME, builtIn,
				Arrays.asList( afterA, nowhere, weakB, strongC ),
				Arrays.asList( Priority.NORMAL, Priority.NORMAL, Priority.NORMAL, Priority.HIGH ) );
		assertEquals( Arrays.asList( "frame.a", "ext.after_a", "frame.b", "frame.c", "ext.nowhere" ), plan.ids() );
		assertTrue( "a built-in id at normal priority does not replace", plan.passes().get( 2 ) == builtIn.get( 1 ) );
		assertTrue( "above normal it does", plan.passes().get( 3 ) == strongC );
	}

	/** A pass the data rules out does not run, says why once per run, and its integrity check still runs. */
	@Test
	public void aPassTheDataRulesOutIsLoggedOnceAndSkipped()
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 5, 0, 1 );
		final ToolContext ctx = g.context();
		final List< String > log = new ArrayList<>();
		ctx.setLogCallback( log::add );
		final int[] runs = { 0 };
		final CorrectionPass ruledOut = new FakePass( "frame.never", null )
		{
			@Override
			public Applicability applicability( final DataProfile data )
			{
				return Applicability.notFor( "test reason" );
			}

			@Override
			public int run( final PassScope s )
			{
				runs[ 0 ]++;
				return 1;
			}
		};
		final PassPlan plan = PassPlan.forRun( ctx, CorrectionPass.Stage.FRAME, Collections.singletonList( ruledOut ) );
		for ( int t = 0; t < 3; t++ )
			plan.run( PassScope.frame( ctx, new AutomahoutSettings(), t, 5, 0 ) );
		assertEquals( 0, runs[ 0 ] );
		assertEquals( "logged once, not per frame: " + log, 1,
				log.stream().filter( l -> l.contains( "'frame.never' is off for this run: test reason" ) ).count() );
		assertTrue( "the plan is logged: " + log, log.stream().anyMatch( l -> l.contains( "Correction plan, per frame: frame.never" ) ) );
	}

	/** An ablation run switches passes off by id, or all of them. */
	@Test
	public void passesCanBeSwitchedOffForAnAblation()
	{
		final ToolContext ctx = new ReplayGraph().context();
		final int[] runs = { 0 };
		final CorrectionPass counting = new FakePass( "frame.counting", null )
		{
			@Override
			public int run( final PassScope s )
			{
				runs[ 0 ]++;
				return 0;
			}
		};
		final PassPlan plan = PassPlan.of( CorrectionPass.Stage.FRAME, Collections.singletonList( counting ),
				Collections.emptyList(), Collections.emptyList() );
		try
		{
			for ( final String off : new String[] { "frame.counting", "all", "frame.other, frame.counting" } )
			{
				System.setProperty( PassPlan.PROPERTY_DISABLE, off );
				plan.run( PassScope.frame( ctx, new AutomahoutSettings(), 1, 5, 0 ) );
			}
			assertEquals( 0, runs[ 0 ] );
			System.setProperty( PassPlan.PROPERTY_DISABLE, "frame.other" );
			plan.run( PassScope.frame( ctx, new AutomahoutSettings(), 1, 5, 0 ) );
			assertEquals( 1, runs[ 0 ] );
		}
		finally
		{
			System.clearProperty( PassPlan.PROPERTY_DISABLE );
		}
	}

	/** A gap bridge fills timepoints behind the frame; it may not reach behind the first writable one. */
	@Test
	public void theBridgeRangeIsClampedToTheFirstWritableTimepoint()
	{
		final ToolContext ctx = new ReplayGraph().context();
		final AutomahoutSettings settings = new AutomahoutSettings();
		assertEquals( 10, FramePasses.bridgeFrom( PassScope.frame( ctx, settings, 10, 40, 10 ) ) );
		assertEquals( 15 - AutoCorrectOrchestrator.MAX_LINK_GAP,
				FramePasses.bridgeFrom( PassScope.frame( ctx, settings, 15, 40, 10 ) ) );
		assertEquals( 0, FramePasses.bridgeFrom( PassScope.frame( ctx, settings, 1, 40, -5 ) ) );
	}

	/** With no profile and no cap in the settings, the thresholds step measures one: reconnection is never uncapped. */
	@Test
	public void theThresholdsStepMeasuresACapWhenNothingSuppliesOne()
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, 12, 0, 1 );
		g.track( 0, 12, 40, 1 );
		final AutomahoutSettings settings = new AutomahoutSettings();
		settings.setCorrectMaxDisplacement( 0.0 );
		final PassScope s = PassScope.lineage( g.context(), settings, 0, 12 );
		pass( LineagePasses.BUILT_IN, LineagePasses.THRESHOLDS ).run( s );
		final double cap = s.getDouble( LineagePasses.MAX_DISPLACEMENT, 0.0 );
		assertTrue( "measured cap: " + cap, cap > 0.0 && cap < 20.0 );
	}

	private static List< String > ids( final List< CorrectionPass > passes )
	{
		final List< String > ids = new ArrayList<>();
		for ( final CorrectionPass p : passes )
			ids.add( p.getId() );
		return ids;
	}

	private static CorrectionPass pass( final List< CorrectionPass > plan, final String id )
	{
		for ( final CorrectionPass p : plan )
			if ( p.getId().equals( id ) )
				return p;
		throw new AssertionError( id + " is not in the plan" );
	}

	private static CorrectionPass fake( final String id, final String after )
	{
		return new FakePass( id, after );
	}

	private static class FakePass implements CorrectionPass
	{
		private final String id;

		private final String after;

		FakePass( final String id, final String after )
		{
			this.id = id;
			this.after = after;
		}

		@Override
		public String getId()
		{
			return id;
		}

		@Override
		public String getDescription()
		{
			return "test";
		}

		@Override
		public Stage getStage()
		{
			return Stage.FRAME;
		}

		@Override
		public ToolRole getRole()
		{
			return ToolRole.CORRECT_DETECTIONS;
		}

		@Override
		public String after()
		{
			return after;
		}

		@Override
		public int run( final PassScope scope )
		{
			return 0;
		}
	}
}
