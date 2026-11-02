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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A format-neutral cell-lineage graph: nodes carrying {@code id, t, (x,y,z), radius} and directed
 * edges {@code source → target}. It is the common input to {@link LineageConstraintLearner}, so the
 * learner does not care whether the graph came from a {@code .geff} ground-truth store (via
 * {@link GeffGtLoader}) or from the current Mastodon model (via {@link ModelGraphAdapter}).
 *
 * <p>Coordinates and radii are stored in whatever units the source provides; the learner applies a
 * {@code scale} factor to bring them into Mastodon world units.</p>
 */
public final class LineageGraphData
{

	/** A lineage-graph node. */
	public static final class Node
	{
		public final int id;

		public final int t;

		public final double x;

		public final double y;

		public final double z;

		public final double radius;

		/**
		 * Row-major 3×3 spot covariance (9 values) encoding the ellipsoid shape/orientation, or
		 * {@code null} if unknown. Preserving it matters when the nodes are used as ELEPHANT training
		 * labels: training on isotropic (radius-only) labels teaches the model to output uniform spheres.
		 */
		public final double[] covariance;

		/**
		 * ELEPHANT's detection label for this node — {@code "tp"}, {@code "fp"}, or {@code null} when
		 * the source carried none (in which case it is trained as a positive).
		 *
		 * <p>Kept because a store without it can only ever describe where cells ARE. A person who has
		 * marked a detection as background has produced the most reliable negative available anywhere in
		 * this pipeline, and dropping that on the way into the store left training with no negatives but
		 * the synthesised ones — which have sat at 2-3% of annotations and rest on a VLM whose verdicts
		 * on these crops were measured at 77% background for flagged spots against 71% for the control,
		 * i.e. no discrimination at all.</p>
		 */
		public final String label;

		public Node( final int id, final int t, final double x, final double y, final double z,
				final double radius )
		{
			this( id, t, x, y, z, radius, null, null );
		}

		public Node( final int id, final int t, final double x, final double y, final double z,
				final double radius, final double[] covariance )
		{
			this( id, t, x, y, z, radius, covariance, null );
		}

		public Node( final int id, final int t, final double x, final double y, final double z,
				final double radius, final double[] covariance, final String label )
		{
			this.id = id;
			this.t = t;
			this.x = x;
			this.y = y;
			this.z = z;
			this.radius = radius;
			this.covariance = covariance;
			this.label = label;
		}
	}

	/** A directed edge {@code sourceId → targetId}. */
	public static final class Edge
	{
		public final int sourceId;

		public final int targetId;

		public Edge( final int sourceId, final int targetId )
		{
			this.sourceId = sourceId;
			this.targetId = targetId;
		}
	}

	public final List< Node > nodes;

	public final List< Edge > edges;

	/** Human-readable provenance (e.g. a file path, or "current Mastodon model"). */
	public final String source;

	/** Source format/version label (e.g. a GEFF spec version, or "mastodon-model"). */
	public final String version;

	public LineageGraphData( final List< Node > nodes, final List< Edge > edges, final String source,
			final String version )
	{
		this.nodes = nodes;
		this.edges = edges;
		this.source = source;
		this.version = version;
	}

	// -----------------------------------------------------------------------

	public JSONObject toJson()
	{
		final JSONObject o = new JSONObject();
		o.put( "source", source );
		o.put( "version", version );
		final JSONArray ns = new JSONArray();
		for ( final Node n : nodes )
		{
			final JSONObject jn = new JSONObject();
			jn.put( "id", n.id );
			jn.put( "t", n.t );
			jn.put( "x", n.x );
			jn.put( "y", n.y );
			jn.put( "z", n.z );
			jn.put( "r", n.radius );
			if ( n.covariance != null )
				jn.put( "cov", new JSONArray( n.covariance ) );
			if ( n.label != null )
				jn.put( "tag", n.label );
			ns.put( jn );
		}
		o.put( "nodes", ns );
		final JSONArray es = new JSONArray();
		for ( final Edge e : edges )
		{
			final JSONObject je = new JSONObject();
			je.put( "s", e.sourceId );
			je.put( "t", e.targetId );
			es.put( je );
		}
		o.put( "edges", es );
		return o;
	}

	public static LineageGraphData fromJson( final JSONObject o )
	{
		final List< Node > nodes = new ArrayList<>();
		final JSONArray ns = o.optJSONArray( "nodes" );
		if ( ns != null )
			for ( int i = 0; i < ns.length(); i++ )
			{
				final JSONObject jn = ns.getJSONObject( i );
				double[] cov = null;
				final JSONArray covArr = jn.optJSONArray( "cov" );
				if ( covArr != null && covArr.length() == 9 )
				{
					cov = new double[ 9 ];
					for ( int k = 0; k < 9; k++ )
						cov[ k ] = covArr.getDouble( k );
				}
				final String tag = jn.optString( "tag", null );
				nodes.add( new Node( jn.optInt( "id" ), jn.optInt( "t" ), jn.optDouble( "x" ),
						jn.optDouble( "y" ), jn.optDouble( "z" ), jn.optDouble( "r" ), cov, tag ) );
			}
		final List< Edge > edges = new ArrayList<>();
		final JSONArray es = o.optJSONArray( "edges" );
		if ( es != null )
			for ( int i = 0; i < es.length(); i++ )
			{
				final JSONObject je = es.getJSONObject( i );
				edges.add( new Edge( je.optInt( "s" ), je.optInt( "t" ) ) );
			}
		return new LineageGraphData( nodes, edges, o.optString( "source", "" ),
				o.optString( "version", "unknown" ) );
	}

	public void save( final String path ) throws IOException
	{
		Files.write( Paths.get( path ), toJson().toString().getBytes( StandardCharsets.UTF_8 ) );
	}

	public static LineageGraphData load( final String path ) throws IOException
	{
		final String json = new String( Files.readAllBytes( Paths.get( path ) ), StandardCharsets.UTF_8 );
		return fromJson( new JSONObject( json ) );
	}
}
