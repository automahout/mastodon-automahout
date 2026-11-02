package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The adaptive-tracking progress marker claims "everything below me has been processed". It is kept
 * in preferences that live in one node per USER, not per project, and it outlives the graph it
 * describes — so a run over an EMPTY project inherited a marker at t=10 from an interrupted run and
 * started there, and t=0..9 were never tracked at all. These hold the checks that claim now has to
 * pass.
 */
public class ResumeMarkerTest
{
	private static final String HELA = "Fluo-N2DL-HeLA_01";

	/** The marker is at t=10, the movie is 92 tp, the requested range is the whole movie. */
	private static String refusal( final String markerDataset, final String currentDataset,
			final boolean startFresh, final int lastDone )
	{
		return ElephantTool.resumeMarkerRefusal( 10, 92, markerDataset, 0, 91, 92, currentDataset,
				startFresh, lastDone );
	}

	@Test
	public void anInterruptedSecondPassOverATrackedMovieStillResumes()
	{
		// The case the marker exists for: pass 1 filled the range, pass 2 stopped early, so spot presence
		// cannot say how far pass 2 got.
		assertNull( refusal( HELA, HELA, false, 91 ) );
	}

	@Test
	public void anEmptyProjectDoesNotResume()
	{
		// The reported bug. lastDone = -1 means nothing is tracked, so "everything below t=10 is done" is
		// false, and obeying it drops t=0..9 from the run silently.
		final String r = refusal( HELA, HELA, false, -1 );
		assertNotNull( r );
		assertTrue( r.contains( "no spots" ), r );
	}

	@Test
	public void startFreshIsNotOverriddenByTheMarker()
	{
		// "Start fresh" sets lastDone to -1, which used to route it into the marker branch: the run
		// logged "does not resume from anything already in the project" and then resumed. Silent here
		// because the fresh-start line has already been printed.
		assertEquals( "", refusal( HELA, HELA, true, -1 ) );
		assertEquals( "", refusal( HELA, HELA, true, 91 ) );
	}

	@Test
	public void anotherMoviesMarkerIsRefusedEvenAtTheSameLength()
	{
		// The timepoint count was the only scoping there had ever been, and it is not an identity: any
		// other 92-timepoint movie matched.
		final String r = refusal( "mouse_embryo_240220_sub50", HELA, false, 91 );
		assertNotNull( r );
		assertTrue( r.contains( "mouse_embryo_240220_sub50" ), r );
		assertTrue( r.contains( HELA ), r );
	}

	@Test
	public void aMarkerThatRecordsNoDatasetCannotBeChecked()
	{
		final String r = refusal( "", HELA, false, 91 );
		assertNotNull( r );
		assertTrue( r.contains( "does not record" ), r );
	}

	@Test
	public void aMarkerForADifferentMovieLengthIsNotEvenConsidered()
	{
		assertEquals( "", ElephantTool.resumeMarkerRefusal( 10, 500, HELA, 0, 91, 92, HELA, false, 91 ) );
	}

	@Test
	public void aMarkerOutsideTheRequestedRangeIsNotEvenConsidered()
	{
		// At or below the first timepoint it says nothing; past the end it describes another pass.
		assertEquals( "", ElephantTool.resumeMarkerRefusal( 0, 92, HELA, 0, 91, 92, HELA, false, 91 ) );
		assertEquals( "", ElephantTool.resumeMarkerRefusal( 92, 92, HELA, 0, 91, 92, HELA, false, 91 ) );
		assertEquals( "", ElephantTool.resumeMarkerRefusal( -1, 92, HELA, 0, 91, 92, HELA, false, 91 ) );
	}
}
