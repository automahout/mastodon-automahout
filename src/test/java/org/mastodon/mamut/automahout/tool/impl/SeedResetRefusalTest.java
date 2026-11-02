package org.mastodon.mamut.automahout.tool.impl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Random;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.ImageProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * A model already trained on the movie is not reset from intensity seeds unless cells are bright (fluorescence);
 * the first window, with nothing trained yet, may always start from seeds. PSC 01, condition E (2026-10-20): a
 * trained model was retrained from intensity seeds on phase contrast at t=230.
 */
public class SeedResetRefusalTest
{
	private static ToolContext withProfile( final boolean fluorescence )
	{
		final Random rnd = new Random( 3 );
		final double[] v = new double[ 4000 ];
		for ( int i = 0; i < v.length; i++ )
			v[ i ] = fluorescence
					? ( i < 200 ? 33727 + rnd.nextGaussian() * 40 : 33044 + rnd.nextGaussian() * 25 )
					: 20000 + rnd.nextGaussian() * 1500;
		final ToolContext ctx = ToolContext.forReplay( null );
		ImageProfile.forRun( ctx, () -> v ); // measured once per run, then cached
		return ctx;
	}

	@Test
	public void aTrainedModelIsResetFromSeedsOnlyOnFluorescence()
	{
		final ToolContext phase = withProfile( false ), fluo = withProfile( true );
		// first window: nothing committed yet (lastPlausibleWindowEnd = startTp - 1)
		assertNull( ElephantTool.seedResetRefusal( phase, null, 0, -1 ) );
		assertNull( ElephantTool.seedResetRefusal( fluo, null, 0, -1 ) );
		// windows committed through t=229
		assertNotNull( ElephantTool.seedResetRefusal( phase, null, 0, 229 ) );
		assertNull( ElephantTool.seedResetRefusal( fluo, null, 0, 229 ) );
	}
}
