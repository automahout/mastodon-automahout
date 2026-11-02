package org.mastodon.mamut.automahout.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * A window whose detector was caught detecting background is now abandoned. On fluorescence that is
 * not the end of it: "where are the bright things" is answerable from the pixels with no model at
 * all, which is the whole reason the cold-start bootstrap exists, so the detector can be rebuilt from
 * the image and the window retried. Where brightness does not mean signal the seeds would be
 * arbitrary and abandoning is the honest outcome.
 *
 * <p>These hold the conditions on that decision. The rebuild itself needs a server and an image;
 * what must not rot is when it is allowed to happen.</p>
 */
public class DetectorRebuildPolicyTest
{
	private static double[] fluorescence()
	{
		final Random rnd = new Random( 5 );
		final double[] v = new double[ 4000 ];
		for ( int i = 0; i < v.length; i++ )
			v[ i ] = i < 200 ? 33727 + rnd.nextGaussian() * 40 : 33044 + rnd.nextGaussian() * 25;
		return v;
	}

	private static double[] phaseContrast()
	{
		final Random rnd = new Random( 9 );
		final double[] v = new double[ 4000 ];
		for ( int i = 0; i < v.length; i++ )
			v[ i ] = 20000 + rnd.nextGaussian() * 1500;
		return v;
	}

	@Test
	public void onlyFluorescenceCanBeRebuiltFromTheImage()
	{
		assertTrue( ImageProfile.of( fluorescence() ).fluorescenceLike );
		assertFalse( ImageProfile.of( phaseContrast() ).fluorescenceLike,
				"intensity seeds on phase contrast would be arbitrary" );
	}

	@Test
	public void theProfileIsMeasuredOncePerRunAndReused()
	{
		// The rebuild decision is made mid-sweep, per abandoned window. Re-measuring each time would
		// pay for thousands of image reads to answer a question about the dataset, not the window.
		final AtomicInteger calls = new AtomicInteger();
		final ToolContext ctx = ToolContext.forReplay( null );
		final ImageProfile first = ImageProfile.forRun( ctx, () -> {
			calls.incrementAndGet();
			return fluorescence();
		} );
		final ImageProfile second = ImageProfile.forRun( ctx, () -> {
			calls.incrementAndGet();
			return fluorescence();
		} );
		assertEquals( 1, calls.get(), "measured more than once" );
		assertSame( first, second );
	}

	@Test
	public void anUnreadableImageYieldsAnUndeterminedProfileRatherThanAnException()
	{
		// Called from an abandon path, which must not itself fail.
		final ToolContext ctx = ToolContext.forReplay( null );
		final ImageProfile p = ImageProfile.forRun( ctx, () -> {
			throw new IllegalStateException( "no image here" );
		} );
		assertTrue( p.undetermined );
		assertFalse( p.fluorescenceLike, "undetermined must not authorise a rebuild" );
	}

	@Test
	public void noContextIsNotAFluorescenceVerdict()
	{
		assertFalse( ImageProfile.forRun( null, DetectorRebuildPolicyTest::fluorescence ).fluorescenceLike );
	}
}
