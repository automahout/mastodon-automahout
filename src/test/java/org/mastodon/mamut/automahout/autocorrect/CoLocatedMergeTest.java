package org.mastodon.mamut.automahout.autocorrect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.automahout.tool.CleanupToggles;
import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * The merge pass on a real graph. It needs no pixels — the whole point of moving this decision out of
 * the frame is that it is made from geometry and topology — so it runs against a ReplayGraph.
 *
 * <p>The rate it is tuned to is measured, not chosen: on CTC tracking ground truth the criteria fire
 * on 1 of 90 real divisions and 1 of 16621 pairs of unrelated co-existing tracks. See
 * benchmark/merge-valley.</p>
 */
public class CoLocatedMergeTest
{
	private static final int N = AutoCorrectOrchestrator.COLOCATED_MIN_FRAMES + 4;

	/** The radius ReplayGraph gives a spot, so separations can be written in cell radii. */
	private static final double R = 5.0;

	/** Two linked tracks, {@code sep} apart in x, the second drifting by {@code drift} per frame. */
	private static ReplayGraph twoTracks( final double sep, final double drift )
	{
		final ReplayGraph g = new ReplayGraph();
		g.track( 0, N, 0, 0 );
		g.track( 0, N, sep, drift );
		return g;
	}

	@Test
	public void aPairHoldingItsOffsetIsMergedToOneTrack()
	{
		// 1.82 radii apart, steady: the reported over-segmentation.
		final ReplayGraph g = twoTracks( 1.82 * R, 0.0 );
		final ToolContext ctx = merging( g.context() );
		final int before = g.spotCount();
		final int removed = AutoCorrectOrchestrator.mergePersistentCoLocatedPairs( ctx, 0, N - 1 );
		assertTrue( removed > 0, "a steady co-located pair must be merged" );
		assertEquals( before - removed, g.spotCount() );
	}

	@Test
	public void daughtersThatSeparateAreLeftAlone()
	{
		// The failure that matters: merging these deletes a real cell and loses a division.
		final ReplayGraph g = twoTracks( 1.50 * R, 0.05 * R );
		final int before = g.spotCount();
		assertEquals( 0, AutoCorrectOrchestrator.mergePersistentCoLocatedPairs( merging( g.context() ), 0, N - 1 ) );
		assertEquals( before, g.spotCount() );
	}

	@Test
	public void twoCellsSimplyStandingApartAreLeftAlone()
	{
		final ReplayGraph g = twoTracks( 3.0 * R, 0.0 );
		assertEquals( 0, AutoCorrectOrchestrator.mergePersistentCoLocatedPairs( merging( g.context() ), 0, N - 1 ) );
	}

	@Test
	public void tooBriefAnEncounterIsLeftAlone()
	{
		final ReplayGraph g = new ReplayGraph();
		final int shortRun = AutoCorrectOrchestrator.COLOCATED_MIN_FRAMES - 1;
		g.track( 0, shortRun, 0, 0 );
		g.track( 0, shortRun, 1.5 * R, 0 );
		assertEquals( 0,
				AutoCorrectOrchestrator.mergePersistentCoLocatedPairs( merging( g.context() ), 0, shortRun - 1 ) );
	}

	@Test
	public void aRangeTooShortToDecideIsRefusedRatherThanSilentlyEmpty()
	{
		// How this shipped looking like it worked: wired into a per-window caller scoped to 10
		// timepoints, a rule needing 12 can never fire, and returning 0 is indistinguishable from
		// "nothing to merge". The range must be refused out loud.
		final ReplayGraph g = twoTracks( 1.82 * R, 0.0 );
		final int shortRange = AutoCorrectOrchestrator.COLOCATED_MIN_FRAMES - 2;
		assertEquals( 0, AutoCorrectOrchestrator.mergePersistentCoLocatedPairs( merging( g.context() ), 0,
				shortRange - 1 ) );
		// The same pair over a range long enough IS merged, so the refusal above is about the range and
		// not about the pair.
		assertTrue( AutoCorrectOrchestrator.mergePersistentCoLocatedPairs( merging( g.context() ), 0, N - 1 ) > 0 );
	}

	@Test
	public void theWholeMovieCleanupRunsIt()
	{
		// globalCleanup is what auto.correct calls; runTopologyConsistency is not. A pass wired only
		// into the latter never runs in Phase 2 at all, which is where the whole movie is in scope.
		final ReplayGraph g = twoTracks( 1.82 * R, 0.0 );
		final int before = g.spotCount();
		AutoCorrectOrchestrator.globalCleanup( merging( g.context() ), N );
		assertTrue( g.spotCount() < before, "globalCleanup must merge a steady co-located pair" );
	}

	@Test
	public void anUnlinkedPairIsNotJudged()
	{
		// With no links there is no series to hold together, and a pair co-located in ONE frame is
		// exactly what the within-frame passes exist for.
		final ReplayGraph g = new ReplayGraph();
		for ( int t = 0; t < N; t++ )
		{
			g.spot( t, 0 );
			g.spot( t, 1.5 * R );
		}
		assertEquals( 0, AutoCorrectOrchestrator.mergePersistentCoLocatedPairs( merging( g.context() ), 0, N - 1 ) );
	}

	/**
	 * Merging is opt-in — see {@code CleanupToggles.SHARED_RESOLVE_DUPLICATES_OVERRIDE}. This class
	 * tests the merge itself, so every case here asks for it; otherwise each one would pass by the
	 * pass never running.
	 */
	private static ToolContext merging( final ToolContext ctx )
	{
		ctx.setSharedData( CleanupToggles.SHARED_RESOLVE_DUPLICATES_OVERRIDE, Boolean.TRUE );
		return ctx;
	}

	@Test
	public void nothingIsMergedUnlessAskedFor()
	{
		// The default. The detector separated these two; a geometric rule saying they are one cell is a
		// guess overruling a measurement, and on the user's own frame it drew one ellipse around two
		// clearly resolved nuclei and the dark gap between them.
		final ReplayGraph g = twoTracks( 1.82 * R, 0.0 );
		final int before = g.spotCount();
		assertEquals( 0, AutoCorrectOrchestrator.mergePersistentCoLocatedPairs( g.context(), 0, N - 1 ) );
		assertEquals( before, g.spotCount(), "no detection may be merged away by default" );
	}
}
