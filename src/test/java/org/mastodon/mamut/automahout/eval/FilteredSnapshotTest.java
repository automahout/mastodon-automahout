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
package org.mastodon.mamut.automahout.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A GT annotation store must round-trip what was written, and must not carry links whose other end
 * was filtered out.
 */
public class FilteredSnapshotTest
{
	@Test
	public void aStoreRoundTripsPositionsAndCovariance() throws Exception
	{
		// ElephantTool.loadGtAnnotations reads exactly these fields back and uses the covariance as the
		// training label's extent, so a store that loses it teaches the model the wrong cell size.
		final List< LineageGraphData.Node > nodes = new ArrayList<>();
		final double[] cov = { 4, 0, 0, 0, 5, 0, 0, 0, 6 };
		nodes.add( new LineageGraphData.Node( 1, 7, 10.5, 20.5, 30.5, 3.0, cov ) );
		final List< LineageGraphData.Edge > edges = new ArrayList<>();
		final LineageGraphData written = new LineageGraphData( nodes, edges, "test", "test" );

		final File f = Files.createTempFile( "gt", ".json" ).toFile();
		try
		{
			written.save( f.getAbsolutePath() );
			final LineageGraphData read = LineageGraphData.load( f.getAbsolutePath() );
			assertEquals( 1, read.nodes.size() );
			final LineageGraphData.Node n = read.nodes.get( 0 );
			assertEquals( 7, n.t );
			assertEquals( 10.5, n.x, 1e-9 );
			assertEquals( 30.5, n.z, 1e-9 );
			assertTrue( n.covariance != null && n.covariance.length == 9, "covariance survives the store" );
			assertEquals( 6.0, n.covariance[ 8 ], 1e-9 );
		}
		finally
		{
			f.delete();
		}
	}

	@Test
	public void aStoreCarriesTheUserMarksBackgroundAsBackground() throws Exception
	{
		// Without this the store described only where cells ARE: every node came back untagged and
		// buildSpotsJson sends an untagged spot as tp, so a person's fp marks — the most reliable
		// negatives available — were silently retrained as cells.
		final List< LineageGraphData.Node > nodes = new ArrayList<>();
		nodes.add( new LineageGraphData.Node( 1, 0, 1, 2, 3, 4.0, null, "fp" ) );
		nodes.add( new LineageGraphData.Node( 2, 0, 5, 6, 7, 4.0, null, "tp" ) );
		nodes.add( new LineageGraphData.Node( 3, 0, 8, 9, 10, 4.0, null, null ) );
		final File f = Files.createTempFile( "gt-tags", ".json" ).toFile();
		try
		{
			new LineageGraphData( nodes, new ArrayList<>(), "test", "test" ).save( f.getAbsolutePath() );
			final LineageGraphData read = LineageGraphData.load( f.getAbsolutePath() );
			assertEquals( "fp", read.nodes.get( 0 ).label );
			assertEquals( "tp", read.nodes.get( 1 ).label );
			assertEquals( null, read.nodes.get( 2 ).label, "an unlabelled node stays unlabelled" );
		}
		finally
		{
			f.delete();
		}
	}
}
