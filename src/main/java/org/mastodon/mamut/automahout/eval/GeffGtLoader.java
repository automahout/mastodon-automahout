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

import java.io.IOException;
import java.util.List;

import org.mastodon.geff.GeffEdge;
import org.mastodon.geff.GeffMetadata;
import org.mastodon.geff.GeffNode;

/**
 * Loads a ground-truth cell-lineage graph from a {@code .geff} (Zarr-based Graph Exchange File
 * Format) store, using the {@code geff-java} reader. Returns the raw node and edge lists plus the
 * detected GEFF spec version.
 *
 * <p>Nodes carry {@code id, t, (x,y,z), radius}; edges carry {@code sourceNodeId → targetNodeId}.
 * Node coordinates are in the GEFF store's own axes/units — for distance matching against Mastodon
 * spots (world units) the caller may need to apply a scale (see the evaluation tool's
 * {@code scale} parameter).</p>
 */
public final class GeffGtLoader
{

	/** Immutable holder for a loaded GEFF graph. */
	public static final class GtGraph
	{
		public final List< GeffNode > nodes;

		public final List< GeffEdge > edges;

		public final String geffVersion;

		GtGraph( final List< GeffNode > nodes, final List< GeffEdge > edges, final String geffVersion )
		{
			this.nodes = nodes;
			this.edges = edges;
			this.geffVersion = geffVersion;
		}
	}

	private GeffGtLoader()
	{}

	/**
	 * Reads nodes and edges from the {@code .geff} store at {@code path}.
	 *
	 * @throws IOException if the store cannot be read, or its GEFF spec version is not supported by
	 *                     the bundled {@code geff-java} reader (bump the geff-java dependency in that
	 *                     case)
	 */
	public static GtGraph load( final String path ) throws IOException
	{
		String version = "unknown";
		try
		{
			final GeffMetadata md = GeffMetadata.readFromZarr( path );
			if ( md != null && md.getGeffVersion() != null )
			{
				version = md.getGeffVersion();
				if ( !GeffMetadata.SUPPORTED_VERSIONS.contains( version ) )
					throw new IOException( "GEFF spec version '" + version + "' is not supported by "
							+ "the bundled geff-java reader (supported: " + GeffMetadata.SUPPORTED_VERSIONS
							+ "). Bump the geff-java dependency version." );
			}
			final List< GeffNode > nodes = GeffNode.readFromZarr( path );
			final List< GeffEdge > edges = GeffEdge.readFromZarr( path );
			return new GtGraph( nodes, edges, version );
		}
		catch ( final IOException e )
		{
			throw e;
		}
		catch ( final Exception e )
		{
			// geff-java throws ucar.ma2.InvalidRangeException (checked) and various runtime errors.
			throw new IOException( "Failed to read GEFF store at '" + path + "': " + e.getMessage(), e );
		}
	}
}
