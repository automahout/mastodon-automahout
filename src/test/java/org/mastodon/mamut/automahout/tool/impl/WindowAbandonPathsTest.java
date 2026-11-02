package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A ratchet on the shape of bug this file keeps producing: a behaviour added at the site where it was
 * noticed, while its siblings keep the old one.
 *
 * <p>The window loop abandons a window from six places — five detection-explosion catches and one
 * invariant check. Rebuilding the detector and retrying was added to the invariant one only, which is
 * not the path the first detection of a run takes; a run therefore detected 1185 spots at t=0 where
 * 100 were expected, abandoned, and moved to the next window with the same detector, ten times over.
 * The abandon itself had the same history: it was written for explosions and the invariant path
 * printed and carried on.</p>
 *
 * <p>So: every {@code continue windowLoop} that follows an abandon must go through a helper that
 * decides whether to retry. This test reads the source and counts, because the compiler cannot.</p>
 */
public class WindowAbandonPathsTest
{
	private static final Path SOURCE = Paths.get( "src", "main", "java", "org", "mastodon", "mamut",
			"automahout", "tool", "impl", "ElephantTool.java" );

	/**
	 * The source, with the window loop's {@code w.} prefix taken off: its variables live in a WindowRun so
	 * the stages can be methods of their own, and what these checks are about is the same either way.
	 */
	private static List< String > lines() throws IOException
	{
		final List< String > out = new ArrayList<>();
		for ( final String l : Files.readAllLines( SOURCE, StandardCharsets.UTF_8 ) )
			out.add( l.replaceAll( "\\bw\\.", "" ) );
		return out;
	}

	@Test
	public void everyAbandonGoesThroughAHelperThatDecidesWhetherToRetry() throws IOException
	{
		final List< String > src = lines();
		final List< String > bare = new ArrayList<>();
		for ( int i = 0; i < src.size(); i++ )
		{
			// A stage method gives its window up with 'return Next.ABANDON;', the loop itself with continue.
			if ( !src.get( i ).contains( "continue windowLoop;" ) && !src.get( i ).contains( "return Next.ABANDON;" ) )
				continue;
			// Look back a few lines for a decision. An abandon that just continues is the bug.
			boolean decided = false;
			for ( int j = Math.max( 0, i - 6 ); j < i; j++ )
			{
				final String l = src.get( j );
				if ( l.contains( "abandonWindowOnExplosion" ) || l.contains( "rebuildDetectorFromSeeds" )
						|| l.contains( "wStart -= effectiveWindow" ) )
					decided = true;
			}
			// Cancellation and other non-abandon continues are fine; only flag ones next to a rollback.
			boolean abandons = false;
			for ( int j = Math.max( 0, i - 8 ); j < i; j++ )
				if ( src.get( j ).contains( "abandonWindow" ) || src.get( j ).contains( "rollBackWindow" ) )
					abandons = true;
			if ( abandons && !decided )
				bare.add( ( i + 1 ) + ": " + src.get( i ).trim() );
		}
		assertTrue( bare.isEmpty(),
				"a window is abandoned and the sweep moves on without deciding whether to rebuild the "
						+ "detector and retry, at:\n  " + String.join( "\n  ", bare ) );
	}

	@Test
	public void theOldLogOnlyAbandonIsGone() throws IOException
	{
		// logWindowAbortedByExplosion returned void, so no caller could act on it. Keeping the name
		// around invites the same shape back.
		long uses = lines().stream().filter( l -> l.contains( "logWindowAbortedByExplosion" ) ).count();
		assertEquals( 0, uses, "the void-returning abandon is back" );
	}

	@Test
	public void bothAbandonPathsShareOneRollback() throws IOException
	{
		// A window left half-processed is indistinguishable downstream from a processed one, so the two
		// paths must leave the project in the same state.
		long defs = lines().stream().filter( l -> l.contains( "static void rollBackWindow(" ) ).count();
		assertEquals( 1, defs, "there must be exactly one rollback, shared by both abandon paths" );
	}

	/**
	 * A phase after the detection fails; the detection must survive.
	 *
	 * <p>Four windows of one run were emptied because a cleanup pass took a timepoint one or two cells
	 * below an ESTIMATED floor, and the response was to revert the whole window to nothing. A floor
	 * violation says the cleanup pass is broken; it says nothing about the detection, which passed its
	 * own ceiling and floor checks several phases earlier.</p>
	 */
	@Test
	public void aPhaseAfterTheDetectionIsPutBackToTheDetection() throws IOException
	{
		final List< String > src = lines();
		final List< String > revertingToNothing = new ArrayList<>();
		for ( int i = 0; i < src.size(); i++ )
		{
			if ( !src.get( i ).contains( "abandonWindowOnInvariant(" ) )
				continue;
			final StringBuilder call = new StringBuilder();
			for ( int j = i; j < Math.min( src.size(), i + 5 ); j++ )
				call.append( src.get( j ) );
			if ( call.indexOf( "windowSpotsBefore" ) >= 0 )
				revertingToNothing.add( ( i + 1 ) + ": " + src.get( i ).trim() );
		}
		assertTrue( revertingToNothing.isEmpty(),
				"an invariant abandon reverts to before the window ran instead of to the last state that "
						+ "passed its check, at:\n  " + String.join( "\n  ", revertingToNothing ) );
	}

	@Test
	public void theLastCheckedStateIsRefreshedByEveryPhaseThatPasses() throws IOException
	{
		// One snapshot per passing phase: [3/6] commit, [4/6] link, [5/6] autocorrect, [5b/6]
		// refinement. Fewer, and a late failure throws away corrections that were checked and fine.
		final long refreshes = lines().stream()
				.filter( l -> l.contains( "lastChecked = snapshotSpots(" ) ).count();
		assertTrue( refreshes >= 4,
				"every phase that passes its check must refresh the state a later failure falls back "
						+ "to; found " + refreshes );
	}

	@Test
	public void aKeptDetectionIsRelinkedBecauseASnapshotCarriesNoEdges() throws IOException
	{
		final String text = String.join( "\n", lines() );
		final int keep = text.indexOf( "if ( keptDetection && !retry )" );
		assertTrue( keep > 0, "the kept-detection branch of the abandon path is gone" );
		assertTrue( text.indexOf( "linkWindow(", keep ) > 0,
				"restoring a spot snapshot drops every edge, so a kept detection must be re-linked or "
						+ "the window is handed back as unlinked detections" );
	}

	@Test
	public void aFailedDetectionStillRevertsTheWholeWindow() throws IOException
	{
		// detectionChecked is what tells the abandon path whether there is anything worth keeping, and
		// it must only become true AFTER the commit's own check has passed.
		final String text = String.join( "\n", lines() );
		// Within ELEPHANT's detection stage: a pretrained detector's stage sets it too, after its own check.
		final int stage = text.indexOf( "private static Next detectStage(" );
		final int commitAbandon = text.indexOf( "\"[3/6] commit detection\", invOutDetect.firstError", stage );
		final int checked = text.indexOf( "detectionChecked = true;", stage );
		assertTrue( commitAbandon > 0 && checked > 0, "the commit-detection abandon path has moved" );
		assertTrue( checked > commitAbandon,
				"a window whose DETECTION failed has nothing checked to keep, so detectionChecked must "
						+ "still be false when its abandon runs" );
	}

	@Test
	public void theStagesStillGiveWindowsUpAtEveryPlaceTheLoopDid() throws IOException
	{
		// The loop had ten 'continue windowLoop;' abandons, all inside what are now the stage methods,
		// where each became 'return Next.ABANDON;'. Fewer means one was lost in the move.
		final long abandons = lines().stream().filter( l -> l.contains( "return Next.ABANDON;" ) ).count();
		assertEquals( 10, abandons, "a stage's abandon path went missing" );
	}

	@Test
	public void theBuiltInPlanIsTheOrderTheLoopAlwaysRan()
	{
		assertEquals( "elephant.detect (DETECT) -> nn.link (LINK) -> autocorrect.window (CORRECT_DETECTIONS) "
				+ "-> elephant.refine (TRAIN) -> topology.consistency (CORRECT_LINEAGE)", ElephantTool.describePlan() );
	}

	@Test
	public void aPretrainedDetectorsRunNeverRebuildsOrRestoresElephant() throws IOException
	{
		// The user chose StarDist and watched ELEPHANT train: a check failing after the commit went
		// through abandonWindowOnInvariant, which rebuilt ELEPHANT's detector and retried.
		final String text = String.join( "\n", lines() );
		final int abandon = text.indexOf( "private static boolean abandonWindowOnInvariant(" );
		final int rebuild = text.indexOf( "rebuildDetectorFromSeeds(", abandon );
		assertTrue( text.substring( abandon, rebuild ).contains( "DetectorTrial.pretrainedChosen( context )" ),
				"the rebuild in the abandon path must stand down when a pretrained detector detects" );
		final int rollBack = text.indexOf( "private static void rollBackWindow(" );
		final int restore = text.indexOf( "elephant.restoreDetectionModel(", rollBack );
		assertTrue( text.substring( rollBack, restore ).contains( "DetectorTrial.pretrainedChosen( context )" ),
				"no snapshot is taken when a pretrained detector detects, so none may be restored" );
	}
}
