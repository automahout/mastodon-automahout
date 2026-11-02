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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One server speaking {@code automahout-remote/1} (docs/remote-tools.md): what tools it offers, and a
 * run of one of them on one frame. Plain {@link HttpURLConnection}, so a tool server needs nothing but
 * HTTP and JSON.
 */
public final class RemoteToolServer
{
	public static final String PROTOCOL = "automahout-remote/1";

	private static final Preferences PREFS = org.mastodon.mamut.automahout.tool.AutomahoutPrefs.node( RemoteToolServer.class );

	private static final String KEY_SERVERS = "servers";

	/** System property that adds servers without touching the preferences, e.g. for a headless run. */
	public static final String PROPERTY_SERVERS = "automahout.remote.servers";

	private static final int CONNECT_TIMEOUT_MS = 3_000;

	/** A frame can take a model a while on a CPU. */
	private static final int READ_TIMEOUT_MS = 600_000;

	public final String baseUrl;

	public RemoteToolServer( final String baseUrl )
	{
		this.baseUrl = baseUrl.replaceAll( "/+$", "" );
	}

	/** The servers the user configured, then any from {@link #PROPERTY_SERVERS}; comma-separated. */
	public static List< String > configured()
	{
		final List< String > out = new ArrayList<>();
		for ( final String s : ( PREFS.get( KEY_SERVERS, "" ) + "," + System.getProperty( PROPERTY_SERVERS, "" ) ).split( "," ) )
			if ( !s.trim().isEmpty() && !out.contains( s.trim() ) )
				out.add( s.trim() );
		return out;
	}

	/** Remembers {@code servers} (comma-separated) as the configured ones. */
	public static void setConfigured( final String servers )
	{
		PREFS.put( KEY_SERVERS, servers == null ? "" : servers.trim() );
	}

	/** The tools this server offers; empty, with the reason logged, when it cannot be reached. */
	public List< RemoteTool > tools( final Consumer< String > log )
	{
		final List< RemoteTool > out = new ArrayList<>();
		try
		{
			final JSONObject manifest = new JSONObject( request( "GET", "/tools", null ) );
			if ( !PROTOCOL.equals( manifest.optString( "protocol" ) ) )
				log.accept( "  Remote tool server " + baseUrl + " speaks '" + manifest.optString( "protocol" ) + "', not "
						+ PROTOCOL + "; trying anyway." );
			final JSONArray tools = manifest.optJSONArray( "tools" );
			for ( int i = 0; tools != null && i < tools.length(); i++ )
			{
				try
				{
					out.add( RemoteTool.fromManifest( this, tools.getJSONObject( i ) ) );
				}
				catch ( final RuntimeException e )
				{
					log.accept( "  Remote tool #" + i + " of " + baseUrl + " is malformed and left out: " + e.getMessage() );
				}
			}
		}
		catch ( final IOException | RuntimeException e )
		{
			log.accept( "  Remote tool server " + baseUrl + " did not answer (" + e.getMessage() + "); its tools are left out." );
		}
		return out;
	}

	/** Runs tool {@code id} on {@code body}; the response, or an IOException carrying the server's error. */
	JSONObject run( final String id, final JSONObject body ) throws IOException
	{
		return new JSONObject( request( "POST", "/tools/" + id + "/run", body.toString() ) );
	}

	/** GET {@code path}, as JSON. */
	JSONObject get( final String path ) throws IOException
	{
		return new JSONObject( request( "GET", path, null ) );
	}

	/** POST {@code body} to {@code path}, as JSON. */
	JSONObject post( final String path, final JSONObject body ) throws IOException
	{
		return new JSONObject( request( "POST", path, body.toString() ) );
	}

	private String request( final String method, final String path, final String body ) throws IOException
	{
		final HttpURLConnection c = ( HttpURLConnection ) new URL( baseUrl + path ).openConnection();
		c.setRequestMethod( method );
		c.setConnectTimeout( CONNECT_TIMEOUT_MS );
		c.setReadTimeout( READ_TIMEOUT_MS );
		if ( body != null )
		{
			c.setDoOutput( true );
			c.setRequestProperty( "Content-Type", "application/json" );
			try (OutputStream os = c.getOutputStream())
			{
				os.write( body.getBytes( StandardCharsets.UTF_8 ) );
			}
		}
		final int status = c.getResponseCode();
		final String text = readAll( status < 400 ? c.getInputStream() : c.getErrorStream() );
		if ( status != 200 )
		{
			String error = text;
			try
			{
				error = new JSONObject( text ).optString( "error", text );
			}
			catch ( final RuntimeException e )
			{
				// not JSON
			}
			throw new IOException( "HTTP " + status + ": " + error );
		}
		return text;
	}

	private static String readAll( final InputStream in ) throws IOException
	{
		if ( in == null )
			return "";
		try (InputStream is = in)
		{
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buf = new byte[ 1 << 16 ];
			int n;
			while ( ( n = is.read( buf ) ) > 0 )
				out.write( buf, 0, n );
			return new String( out.toByteArray(), StandardCharsets.UTF_8 );
		}
	}

	@Override
	public String toString()
	{
		return baseUrl;
	}

	static List< String > split( final String s )
	{
		return Arrays.asList( s.split( "," ) );
	}
}
