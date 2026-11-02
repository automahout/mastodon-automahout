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
package org.mastodon.mamut.automahout.tool.remote;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mastodon.mamut.automahout.tool.Applicability;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.DataProfile;
import org.mastodon.mamut.automahout.tool.GraphEdits;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.ToolParameter;
import org.mastodon.mamut.automahout.tool.ToolRole;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

/**
 * A tool served over HTTP (docs/remote-tools.md), standing in the registry like any other. Version 1
 * runs {@link ToolRole#DETECT} tools: each frame is read here with no graph lock held, sent, and the
 * detections that come back -- in pixels -- are converted to world units and added through
 * {@link GraphEdits} under one write lock per frame.
 */
public final class RemoteTool implements FrameDetector
{
	public static final String PARAM_T_START = "t_start";

	public static final String PARAM_T_END = "t_end";

	public static final String PARAM_REPLACE = "replace_existing";

	private final RemoteToolServer server;

	private final String id, description, reason;

	private final ToolRole role;

	private final List< ToolParameter > toolParameters;

	/** {@code fluorescenceLike} / {@code volumetric} -> required value; absent = any. */
	private final Map< String, Boolean > requires;

	private final double score;

	/** Where frames come from: the run's viewer, or a test's own. */
	static Function< ToolContext, Frame.Reader > readers = BdvFrameReader::new;

	private RemoteTool( final RemoteToolServer server, final String id, final String description, final ToolRole role,
			final List< ToolParameter > toolParameters, final Map< String, Boolean > requires, final double score,
			final String reason )
	{
		this.server = server;
		this.id = id;
		this.description = description;
		this.role = role;
		this.toolParameters = toolParameters;
		this.requires = requires;
		this.score = score;
		this.reason = reason;
	}

	static RemoteTool fromManifest( final RemoteToolServer server, final JSONObject m )
	{
		final List< ToolParameter > ps = new ArrayList<>();
		final JSONArray params = m.optJSONArray( "parameters" );
		for ( int i = 0; params != null && i < params.length(); i++ )
			ps.add( parameter( params.getJSONObject( i ) ) );
		final Map< String, Boolean > req = new LinkedHashMap<>();
		final JSONObject app = m.optJSONObject( "applicability" );
		final JSONObject r = app == null ? null : app.optJSONObject( "requires" );
		if ( r != null )
			for ( final String k : r.keySet() )
				if ( !r.isNull( k ) )
					req.put( k, r.getBoolean( k ) );
		ToolRole role;
		try
		{
			role = ToolRole.valueOf( m.optString( "role", "OTHER" ) );
		}
		catch ( final IllegalArgumentException e )
		{
			role = ToolRole.OTHER;
		}
		return new RemoteTool( server, m.getString( "id" ), m.optString( "description", "" ), role,
				Collections.unmodifiableList( ps ), req, app == null ? 0.5 : app.optDouble( "score", 0.5 ),
				app == null ? "" : app.optString( "reason", "" ) );
	}

	private static ToolParameter parameter( final JSONObject p )
	{
		final String name = p.getString( "name" ), d = p.optString( "description", "" );
		final Object def = p.has( "default" ) && !p.isNull( "default" ) ? p.get( "default" ) : null;
		final ToolParameter tp;
		switch ( p.optString( "type", "STRING" ) )
		{
		case "INTEGER":
			tp = ToolParameter.integer( name, d, def instanceof Number ? ( ( Number ) def ).intValue() : null );
			break;
		case "NUMBER":
			tp = ToolParameter.number( name, d, def instanceof Number ? ( ( Number ) def ).doubleValue() : null );
			break;
		case "BOOLEAN":
			tp = ToolParameter.bool( name, d, def instanceof Boolean ? ( Boolean ) def : null );
			break;
		case "CHOICE":
			final JSONArray c = p.optJSONArray( "choices" );
			final String[] choices = new String[ c == null ? 0 : c.length() ];
			for ( int i = 0; i < choices.length; i++ )
				choices[ i ] = c.getString( i );
			tp = ToolParameter.choice( name, d, def == null ? null : def.toString(), choices );
			break;
		default:
			tp = ToolParameter.string( name, d, def == null ? null : def.toString() );
		}
		return p.has( "min" ) || p.has( "max" )
				? tp.range( p.optDouble( "min", Double.NEGATIVE_INFINITY ), p.optDouble( "max", Double.POSITIVE_INFINITY ) )
				: tp;
	}

	@Override
	public String getId()
	{
		return id;
	}

	@Override
	public String getDescription()
	{
		return description + " [remote: " + server + "]";
	}

	@Override
	public ToolRole getRole()
	{
		return role;
	}

	@Override
	public List< ToolParameter > getParameters()
	{
		final List< ToolParameter > all = new ArrayList<>( toolParameters );
		all.add( ToolParameter.integer( PARAM_T_START, "first timepoint; default the viewer's", null ) );
		all.add( ToolParameter.integer( PARAM_T_END, "last timepoint; default t_start", null ) );
		all.add( ToolParameter.bool( PARAM_REPLACE, "remove the frame's unprotected spots first", false ) );
		return all;
	}

	/** The server's own conditions, checked against what the run has measured. */
	@Override
	public Applicability applicability( final DataProfile data )
	{
		double s = score;
		final List< String > unknown = new ArrayList<>();
		for ( final Map.Entry< String, Boolean > e : requires.entrySet() )
		{
			final Boolean actual = "fluorescenceLike".equals( e.getKey() ) ? data.fluorescenceLike
					: "volumetric".equals( e.getKey() ) ? Boolean.valueOf( data.volumetric ) : null;
			if ( actual == null )
			{
				unknown.add( e.getKey() );
				continue;
			}
			if ( !actual.equals( e.getValue() ) )
				return Applicability.notFor( reason + " -- made for " + describe( e.getKey(), e.getValue() )
						+ " images; this movie is " + describe( e.getKey(), actual ) );
		}
		if ( !unknown.isEmpty() )
		{
			s *= 0.5;
			return Applicability.suits( s, reason + " -- not measured yet: " + String.join( ", ", unknown ) );
		}
		return Applicability.suits( s, reason );
	}

	private static String describe( final String key, final boolean v )
	{
		if ( "fluorescenceLike".equals( key ) )
			return v ? "fluorescence" : "non-fluorescence (e.g. brightfield)";
		if ( "volumetric".equals( key ) )
			return v ? "3D" : "2D";
		return key + "=" + v;
	}

	@Override
	public String execute( final ToolContext ctx, final Map< String, Object > params )
	{
		if ( role != ToolRole.DETECT )
			return "Remote tool " + id + " has role " + role + "; this version runs DETECT tools only.";
		final int t0 = intParam( params, PARAM_T_START, ctx.hasViewer()
				? ctx.requireViewer().state().getCurrentTimepoint() : 0 );
		final int t1 = intParam( params, PARAM_T_END, t0 );
		final boolean replace = Boolean.TRUE.equals( params.get( PARAM_REPLACE ) )
				|| "true".equalsIgnoreCase( String.valueOf( params.get( PARAM_REPLACE ) ) );
		final Frame.Reader reader = readers.apply( ctx );
		int added = 0, frames = 0;
		final StringBuilder notes = new StringBuilder();
		for ( int t = t0; t <= t1; t++ )
		{
			if ( ctx.isCancelRequested() )
			{
				notes.append( " Cancelled at t=" ).append( t ).append( '.' );
				break;
			}
			final Frame frame = reader.read( t ); // no graph lock: image only
			if ( frame == null )
				continue;
			final List< Detection > dets;
			try
			{
				final Map< String, Object > withHints = new java.util.HashMap<>( FrameDetector.hints( ctx ) );
				withHints.putAll( params );
				dets = detect( frame, withHints );
			}
			catch ( final IOException e )
			{
				ctx.log( String.format( "  %s at t=%d: %s -- nothing added for this frame.", id, t, e.getMessage() ) );
				continue;
			}
			final int n = FrameDetector.addToGraph( ctx, frame, dets, replace );
			added += n;
			frames++;
			ctx.log( String.format( "  %s at t=%d: %d detection(s) added%s", id, t, n,
					lastMessage == null ? "" : " (" + lastMessage + ")" ) );
		}
		return String.format( "%s on t=%d..%d: %d detection(s) added over %d frame(s).%s", id, t0, t1, added, frames, notes );
	}

	static JSONObject request( final Frame frame, final JSONObject toolParams )
	{
		return request( frame, toolParams, null );
	}

	/**
	 * The request body. Besides the pixels, the frame carries its voxel size (world units per voxel along x, y,
	 * z: the lengths of the voxel-to-world columns) and, when measured, the cell radius in world units: optional
	 * fields a tool may use (a 3D Cellpose needs a diameter its own size model cannot give) or ignore.
	 */
	static JSONObject request( final Frame frame, final JSONObject toolParams, final Double cellRadius )
	{
		final ByteBuffer buf = ByteBuffer.allocate( 4 * frame.data.length ).order( ByteOrder.LITTLE_ENDIAN );
		buf.asFloatBuffer().put( frame.data );
		final JSONArray shape = frame.dims[ 2 ] > 1
				? new JSONArray().put( frame.dims[ 2 ] ).put( frame.dims[ 1 ] ).put( frame.dims[ 0 ] )
				: new JSONArray().put( frame.dims[ 1 ] ).put( frame.dims[ 0 ] );
		final JSONArray voxel = new JSONArray();
		for ( int c = 0; c < 3; c++ )
		{
			final double[] a = frame.voxelToWorld;
			voxel.put( Math.sqrt( a[ c ] * a[ c ] + a[ 4 + c ] * a[ 4 + c ] + a[ 8 + c ] * a[ 8 + c ] ) );
		}
		final JSONObject f = new JSONObject().put( "t", frame.t ).put( "shape", shape ).put( "dtype", "float32" )
				.put( "voxel_size", voxel ).put( "data", Base64.getEncoder().encodeToString( buf.array() ) );
		if ( cellRadius != null )
			f.put( "cell_radius_um", cellRadius.doubleValue() );
		return new JSONObject().put( "params", toolParams ).put( "frame", f );
	}

	/** The server's message for the last frame detected, for the log. */
	private volatile String lastMessage;

	@Override
	public List< Detection > detect( final Frame frame, final Map< String, Object > params ) throws IOException
	{
		final JSONObject toolParams = new JSONObject();
		for ( final ToolParameter p : toolParameters )
		{
			final Object v = params.containsKey( p.name ) ? params.get( p.name ) : p.defaultValue;
			if ( v != null )
				toolParams.put( p.name, v );
		}
		final Object r = params.get( HINT_CELL_RADIUS_UM );
		final JSONObject response = server.run( id, request( frame, toolParams,
				r instanceof Number ? ( ( Number ) r ).doubleValue() : null ) );
		lastMessage = response.has( "message" ) ? response.optString( "message" ) : null;
		return parse( response.optJSONArray( "detections" ) );
	}

	/**
	 * The detections of a response, in pixels, as Mastodon's shape matrix: {@code covariance} as given
	 * (already that matrix), {@code moments} converted from a mask's second moments, or a radius. One
	 * plane: the third axis is filled with the in-plane geometric mean.
	 *
	 * <p>Mastodon's spot covariance is the ellipsoid's shape matrix -- the boundary is where x^T C^-1 x = 1,
	 * so its eigenvalues are the squared semi-axes (a sphere of radius r is r^2 I). The second moments of a
	 * filled ellipse are a^2/4 along each semi-axis a, of a filled ellipsoid a^2/5: taken as the shape
	 * matrix they give spots of half the size (the user saw StarDist's spots that much too small). Hence
	 * {@code moments} x (n + 2), n the dimensions of the matrix sent.</p>
	 */
	static List< Detection > parse( final JSONArray dets )
	{
		final List< Detection > out = new ArrayList<>();
		for ( int i = 0; dets != null && i < dets.length(); i++ )
		{
			final JSONObject d = dets.getJSONObject( i );
			final JSONArray c = d.getJSONArray( "centroid" );
			final double[] voxel = { c.getDouble( 0 ), c.getDouble( 1 ), c.length() > 2 ? c.getDouble( 2 ) : 0 };
			final double[][] cv = new double[ 3 ][ 3 ];
			final JSONArray shape = d.optJSONArray( "covariance" );
			final JSONArray moments = shape == null ? d.optJSONArray( "moments" ) : null;
			final JSONArray m = shape != null ? shape : moments;
			if ( m != null )
			{
				final double scale = moments != null ? m.length() + 2 : 1;
				for ( int r = 0; r < m.length(); r++ )
					for ( int k = 0; k < m.getJSONArray( r ).length(); k++ )
						cv[ r ][ k ] = scale * m.getJSONArray( r ).getDouble( k );
				if ( m.length() == 2 ) // one plane: the third axis as the in-plane geometric mean
					cv[ 2 ][ 2 ] = Math.sqrt( Math.max( 0, cv[ 0 ][ 0 ] * cv[ 1 ][ 1 ] ) );
			}
			else
			{
				final double r = d.optDouble( "radius", 3 );
				cv[ 0 ][ 0 ] = cv[ 1 ][ 1 ] = cv[ 2 ][ 2 ] = r * r;
			}
			out.add( new Detection( voxel, cv ) );
		}
		return out;
	}

	private static int intParam( final Map< String, Object > params, final String key, final int def )
	{
		final Object v = params.get( key );
		if ( v instanceof Number )
			return ( ( Number ) v ).intValue();
		if ( v != null && !v.toString().trim().isEmpty() )
			return Integer.parseInt( v.toString().trim() );
		return def;
	}
}
