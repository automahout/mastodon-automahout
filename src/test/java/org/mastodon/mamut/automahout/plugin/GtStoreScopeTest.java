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
package org.mastodon.mamut.automahout.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * When a GT annotation store may be used. The settings backing it are one node per USER, so the
 * dataset it was built from is the only thing separating "my annotations" from "another movie".
 */
public class GtStoreScopeTest
{
	/** Mirrors ElephantTool.gtStoreMatchesDataset, which needs a ToolContext to log through. */
	private static boolean usable( final String path, final String storeDataset, final String current )
	{
		if ( path == null || path.trim().isEmpty() )
			return false;
		return storeDataset != null && storeDataset.equals( current );
	}

	/**
	 * Mirrors the note ElephantTool logs when no GT reached the range being tracked: a usable store
	 * whose annotations sit outside that range is NOT an absent store.
	 */
	private static String note( final boolean storeUsable, final int[] storeExtent, final int startTp,
			final int endTp )
	{
		return !storeUsable || storeExtent == null
				? "No GT store active"
				: String.format( "The active GT store holds %d annotation(s), but at t=%d..%d — none inside "
						+ "the t=%d..%d this run tracks", storeExtent[ 2 ], storeExtent[ 0 ],
						storeExtent[ 1 ], startTp, endTp );
	}

	@Test
	public void aStoreWhoseAnnotationsMissTheRangeIsNotReportedAsAbsent()
	{
		// What happened: 88 confirmed spots at t=0..1, a run resumed at t=160, and a log line saying
		// "No GT store active" — which reads as "go and build one", when one was already there and
		// simply had nothing to say about t=160 onwards.
		final String missed = note( true, new int[] { 0, 1, 88 }, 160, 499 );
		assertTrue( missed.contains( "88 annotation(s)" ), missed );
		assertTrue( missed.contains( "t=0..1" ), missed );
		assertTrue( missed.contains( "t=160..499" ), missed );
		assertFalse( missed.contains( "No GT store active" ), missed );
	}

	@Test
	public void aRunWithNoStoreAtAllStillSaysSo()
	{
		assertTrue( note( false, null, 160, 499 ).contains( "No GT store active" ) );
		// Usable path, but the file is unreadable or holds nothing — nothing to report about coverage.
		assertTrue( note( true, null, 160, 499 ).contains( "No GT store active" ) );
	}

	@Test
	public void aStoreFromAnotherDatasetIsRefused()
	{
		// What happened: a new dataset was opened and the previous one's confirmed spots loaded as
		// training labels, because a path carries no evidence about which movie it describes.
		assertTrue( usable( "/gt.json", "hiiragi/BlockA", "hiiragi/BlockA" ) );
		assertFalse( usable( "/gt.json", "hiiragi/BlockA", "hiiragi/BlockB" ) );
	}

	@Test
	public void aStoreThatRecordsNoDatasetIsRefusedRatherThanTrusted()
	{
		// Written before stores were scoped, so there is nothing to check it against. Rebuilding is one
		// menu action; training a detector on another movie's cells does not announce itself.
		assertFalse( usable( "/gt.json", null, "hiiragi/BlockA" ) );
		assertFalse( usable( "/gt.json", "", "hiiragi/BlockA" ) );
	}

	@Test
	public void noStoreConfiguredIsNotAnError()
	{
		assertFalse( usable( null, "hiiragi/BlockA", "hiiragi/BlockA" ) );
		assertFalse( usable( "   ", "hiiragi/BlockA", "hiiragi/BlockA" ) );
	}
}
