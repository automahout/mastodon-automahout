package org.mastodon.mamut.automahout.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The crop VLM check is a switch now, not a fact of the pipeline, and the pre-flight opens it from
 * what the pixels say. These hold the two things that must not slip: it is ON for anyone who never
 * touched it, and the profile only turns it off where the measured alternative stands on something.
 */
public class VlmCropCheckToggleTest
{
	@Test
	public void itIsOnWhenNobodyHasSaidOtherwise()
	{
		// The measured alternative only works where brightness means signal. Turning the check off by
		// default would replace a bad answer with no answer everywhere else.
		assertTrue( CleanupToggles.resolveDefaultOn( null ) );
	}

	@Test
	public void thePreflightCanTurnItOffAndOnAgain()
	{
		assertFalse( CleanupToggles.resolveDefaultOn( Boolean.FALSE ) );
		assertTrue( CleanupToggles.resolveDefaultOn( Boolean.TRUE ) );
	}

	@Test
	public void somethingElseLeftInSharedDataDoesNotSilentlyDisableIt()
	{
		assertTrue( CleanupToggles.resolveDefaultOn( "no" ) );
		assertTrue( CleanupToggles.resolveDefaultOn( 0 ) );
	}

	@Test
	public void fluorescenceOpensTheDialogWithTheCheckOffAndPhaseContrastWithItOn()
	{
		// The dialog reads exactly this to decide the box's opening state; the user still overrides it.
		final double[] fluorescent = new double[ 4000 ];
		final java.util.Random rnd = new java.util.Random( 3 );
		for ( int i = 0; i < fluorescent.length; i++ )
			fluorescent[ i ] = i < 200 ? 33727 + rnd.nextGaussian() * 40 : 33044 + rnd.nextGaussian() * 25;
		assertFalse( ImageProfile.of( fluorescent ).vlmCropCheckApplies() );

		final double[] phase = new double[ 4000 ];
		for ( int i = 0; i < phase.length; i++ )
			phase[ i ] = 20000 + rnd.nextGaussian() * 1500;
		assertTrue( ImageProfile.of( phase ).vlmCropCheckApplies() );
	}

	@Test
	public void anUnmeasurableFrameLeavesEveryDialogSwitchWhereItWas()
	{
		final ImageProfile none = ImageProfile.of( null );
		assertTrue( none.vlmCropCheckApplies() );
		assertTrue( none.flatContrastDeletionApplies() );
		assertFalse( none.dimSignalFilterApplies() );
	}
}
